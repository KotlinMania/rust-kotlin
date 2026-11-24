package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.procmacro2.Ident
import io.github.kotlinmania.rustkotlin.reused.starlark.AssignCount
import io.github.kotlinmania.syn.BinOp
import io.github.kotlinmania.syn.Expr
import io.github.kotlinmania.syn.GenericArgument
import io.github.kotlinmania.syn.Lit
import io.github.kotlinmania.syn.Pat
import io.github.kotlinmania.syn.Stmt
import io.github.kotlinmania.syn.SynType
import io.github.kotlinmania.syn.UnOp
private fun unwrapTrailingIterReceiver(expr: Expr): Expr {
    val mc = expr as? Expr.MethodCall ?: return expr
    val rawName = mc.method.toString().removePrefix("r#")
    return if (mc.args.isEmpty() && mc.turbofish == null && rawName in setOf("iter", "iter_mut", "into_iter")) {
        mc.receiver
    } else {
        expr
    }
}

private fun KotlinJ2KEmitter.unwrapIndexedCollectionIterReceiver(
    expr: Expr,
    selfTypeName: String?,
): Expr {
    val unwrapped = unwrapTrailingIterReceiver(expr)
    if (unwrapped === expr) return expr
    val isIndexed =
        inferArrayElementNumericKind(unwrapped, selfTypeName) != null ||
            inferExprRenderedType(unwrapped, selfTypeName)?.let {
                extractIndexedElementRenderedType(it, selfTypeName)
            } != null ||
            (unwrapped is Expr.Index && unwrapped.index is Expr.Range)
    return if (isIndexed) unwrapped else expr
}

private fun KotlinJ2KEmitter.unwrapRepeatSourceArg(
    expr: Expr,
    indent: String,
    selfTypeName: String?,
): Expr? {
    val call = unwrapParenOrGroupExpr(expr) as? Expr.Call ?: return null
    val callee = renderCalleeExpr(call.func, indent, selfTypeName).substringBefore('<').trim()
    val args =
        call.args
            .toList()
            .filterNot { it is Expr.Tuple && it.elems.isEmpty() }
    return if (args.size == 1 &&
        "repeat" !in inspector.declaredTopLevelFnRawNames &&
        (callee == "repeat" || callee == "iter.repeat" || callee.endsWith(".iter.repeat"))
    ) {
        args[0]
    } else {
        null
    }
}

private fun KotlinJ2KEmitter.tryRenderStringPrimitiveParse(
    expr: Expr.MethodCall,
    receiver: String,
    targetTypeName: String?,
    selfTypeName: String?,
): String? {
    val strParseMethod = targetTypeName?.let { STR_PRIMITIVE_PARSE_METHODS[it] } ?: return null
    val parseRecvTy =
        (currentTypedLocals[receiver] ?: inferExprRenderedType(expr.receiver, selfTypeName))
            ?.removeSuffix("?")
            ?.trim()
    val parseRecvBase = parseRecvTy?.substringAfterLast('.')?.trim()
    if (parseRecvBase in setOf("ParseStream", "ParseBuffer")) return null
    val isStringLike =
        parseRecvTy == "String" ||
            parseRecvTy == "CharSequence" ||
            refCellGuardPointeeType(parseRecvTy) == "String" ||
            (parseRecvTy == null && inspector.instanceMethodsByType.values.none { "parse" in it } && "parse" !in inspector.declaredTopLevelFnRawNames)
    if (!isStringLike) return null
    val atom = atomicRecvExpr(expr.receiver, receiver)
    val strTarget = if (refCellGuardPointeeType(parseRecvTy) == "String") "$atom.value" else atom
    return if (usesTaggedCoreResult()) {
        val res = sourceResultName()
        val suffix = buildExplicitResultTypeArgsSuffix(targetTypeName, "String")
        "($strTarget.$strParseMethod?.let { $res.Ok$suffix(it) } ?: $res.Err$suffix(\"failed to parse $targetTypeName\"))"
    } else {
        "($strTarget.$strParseMethod?.let { Result.success(it) } ?: Result.failure(IllegalArgumentException(\"failed to parse $targetTypeName\")))"
    }
}

private fun KotlinJ2KEmitter.tryRenderStringAppendMethod(
    expr: Expr.MethodCall,
    receiver: String,
    arg: String,
    selfTypeName: String?,
): String? {
    val recvTy =
        (currentTypedLocals[receiver] ?: inferExprRenderedType(expr.receiver, selfTypeName))
            ?.removeSuffix("?")
            ?.trim()
    return when {
        recvTy == "StringBuilder" -> "$receiver.append($arg)"
        recvTy == "String" -> "$receiver += $arg"
        refCellGuardPointeeType(recvTy) == "String" -> "${atomicRecvExpr(expr.receiver, receiver)}.value += $arg"
        else -> null
    }
}

private fun KotlinJ2KEmitter.isDirectDivergingOptionArg(
    expr: Expr,
    rendered: String,
): Boolean = isDirectDivergingRenderedExpr(expr, rendered)

internal fun KotlinJ2KEmitter.isFmtStringBuilderReceiver(
    recvExpr: Expr,
    renderedRecv: String,
    selfTypeName: String?,
): Boolean {
    fun isStdFmtReceiverType(ty: String?): Boolean {
        val clean = ty?.removeSuffix("?")?.substringBefore('<')?.trim() ?: return false
        val simple = clean.substringAfterLast('.')
        return clean == "StringBuilder" ||
            simple == "StringBuilder" ||
            ((clean == "Formatter" || clean.endsWith(".fmt.Formatter")) && "Formatter" !in inspector.declaredTypeNames)
    }
    val explicitLocalTy =
        renderedRecv
            .takeIf { it.isNotEmpty() }
            ?.let { currentTypedLocals[it]?.removeSuffix("?")?.trim() }
    val inferred = inferExprRenderedType(recvExpr, selfTypeName)?.removeSuffix("?")?.trim()
    if (isStdFmtReceiverType(explicitLocalTy) || isStdFmtReceiverType(inferred)) return true
    if (explicitLocalTy != null || inferred != null) return false
    return renderedRecv in setOf("f", "fmt", "formatter")
}

internal fun KotlinJ2KEmitter.renderExprMethodCall(expr: Expr.MethodCall, indent: String, selfTypeName: String?): String {
    val rawMethod = expr.method.toString()
    val cleanMethod = rawMethod.removePrefix("r#")
    tryRenderPreReceiverMethodCall(expr, rawMethod, cleanMethod, indent, selfTypeName)?.let { return it }

    val methodEntryParseTarget = currentExpectedParseTargetType
    val prevRecvParseTarget = currentExpectedParseTargetType
    currentExpectedParseTargetType = computeReceiverExpectedParseTarget(cleanMethod, methodEntryParseTarget)

    if (cleanMethod == "for_each" && expr.turbofish == null && expr.args.len() == 1) {
        tryRenderZippedForEachLoop(expr, indent, selfTypeName)?.let {
            currentExpectedParseTargetType = prevRecvParseTarget
            return it
        }
    }
    if ((cleanMethod == "as_ptr" || cleanMethod == "as_mut_ptr") && expr.args.isEmpty()) {
        sourceArrayLocal(expr.receiver)?.let { (storage, _) ->
            currentExpectedParseTargetType = prevRecvParseTarget
            return if (cleanMethod == "as_mut_ptr") "$storage.elementMutPtr(0)" else "$storage.elementConstPtr(0)"
        }
    }
    val effectiveReceiverExpr =
        if (cleanMethod in INDEXED_ITER_UNWRAP_METHODS) {
            unwrapIndexedCollectionIterReceiver(expr.receiver, selfTypeName)
        } else {
            expr.receiver
        }
    val receiver =
        try {
            val value = renderExpr(effectiveReceiverExpr, indent, selfTypeName)
            if (mutableReferencePointeeType(inferExprRenderedType(effectiveReceiverExpr, selfTypeName)) != null) {
                "($value).read()"
            } else {
                value
            }
        } finally {
            currentExpectedParseTargetType = prevRecvParseTarget
        }
    val allMethodArgs =
        expr.args
            .toList()
            .map { arg -> if (cleanMethod == "zip") unwrapIndexedCollectionIterReceiver(arg, selfTypeName) else arg }
    val nonUnitArgs = allMethodArgs.filterNot { it is Expr.Tuple && it.elems.isEmpty() }

    val ctx =
        MethodCallLoweringContext(
            emitter = this,
            expr = expr,
            rawMethod = rawMethod,
            cleanMethod = cleanMethod,
            indent = indent,
            selfTypeName = selfTypeName,
            methodEntryParseTarget = methodEntryParseTarget,
            effectiveReceiverExpr = effectiveReceiverExpr,
            receiver = receiver,
            allMethodArgs = allMethodArgs,
            nonUnitArgs = nonUnitArgs,
        )

    for (rule in RECEIVER_FIRST_METHOD_RULES) {
        rule.tryRender(ctx)?.let { return it }
    }

    ctx.prepareEvaluatedCallArguments()
    for (rule in POST_ARG_METHOD_RULES) {
        rule.tryRender(ctx)?.let { return it }
    }
    return ctx.renderStandardMethodCall()
}

private val POINTER_DISTANCE_SUB_METHODS =
    setOf(
        "checked_sub",
        "wrapping_sub",
        "unchecked_sub",
        "saturating_sub",
        "offset_from",
        "offset_from_unsigned",
        "sub_ptr",
        "byte_offset_from",
    )

private val INDEXED_ITER_UNWRAP_METHODS =
    setOf(
        "zip",
        "any",
        "all",
        "map",
        "filter",
        "filter_map",
        "flat_map",
        "fold",
        "for_each",
        "forEach",
        "enumerate",
        "count",
        "sum",
        "find",
        "position",
    )

private val NUMERIC_RECEIVER_KIND_METHODS =
    setOf(
        "wrapping_add",
        "wrapping_sub",
        "wrapping_mul",
        "wrapping_div",
        "wrapping_rem",
        "wrapping_shl",
        "wrapping_shr",
        "unchecked_shl",
        "unchecked_shr",
        "checked_add",
        "checked_sub",
        "checked_mul",
        "checked_div",
        "checked_rem",
        "checked_div_euclid",
        "checked_rem_euclid",
        "checked_shl",
        "checked_shr",
        "checked_pow",
        "saturating_add",
        "saturating_sub",
        "saturating_mul",
        "overflowing_add",
        "overflowing_sub",
        "overflowing_mul",
        "overflowing_shl",
        "overflowing_shr",
        "div_euclid",
        "rem_euclid",
        "rotate_left",
        "rotate_right",
        "pow",
        "powf",
        "powi",
        "min",
        "max",
        "atan2",
        "hypot",
        "log",
        "mul_add",
        "to_digit",
    )

private val U32_SHIFT_OR_EXP_METHODS =
    setOf(
        "wrapping_shl",
        "wrapping_shr",
        "checked_shl",
        "checked_shr",
        "overflowing_shl",
        "overflowing_shr",
        "rotate_left",
        "rotate_right",
        "pow",
        "checked_pow",
    )

private fun KotlinJ2KEmitter.tryRenderPreReceiverMethodCall(
    expr: Expr.MethodCall,
    rawMethod: String,
    cleanMethod: String,
    indent: String,
    selfTypeName: String?,
): String? {
    val prevRecvParseTarget = currentExpectedParseTargetType
    if (expr.turbofish == null && expr.args.len() == 1 && cleanMethod in POINTER_DISTANCE_SUB_METHODS) {
        val firstArg = expr.args.toList().first()
        tryRenderManagedSlicePointerDistance(
            subPtrExpr = expr.receiver,
            basePtrExpr = firstArg,
            mode = cleanMethod,
            indent = indent,
            selfTypeName = selfTypeName,
        )?.let { return it }
    }
    unwrapTryIntoUnwrapExpr(expr)?.let { innerTryRecv ->
        val tryIntoTargetTy =
            (prevRecvParseTarget ?: currentEnclosingReturnRenderedType)
                ?.removeSuffix("?")
                ?.trim()
        if (tryIntoTargetTy != null && tryIntoTargetTy in PRIMITIVE_ARRAY_TYPES) {
            currentExpectedParseTargetType = null
            return try {
                renderPrimitiveArrayTryIntoConversion(innerTryRecv, tryIntoTargetTy, indent, selfTypeName)
            } finally {
                currentExpectedParseTargetType = prevRecvParseTarget
            }
        }
    }
    if (cleanMethod == "copy_from_slice" && expr.args.len() == 1 && expr.turbofish == null) {
        currentExpectedParseTargetType = null
        val firstArg = expr.args.toList()[0]
        val renderedCopy =
            try {
                renderCopyFromSliceCall(expr.receiver, firstArg, indent, selfTypeName)
            } finally {
                currentExpectedParseTargetType = prevRecvParseTarget
            }
        if (renderedCopy != null && rawMethod.isNotEmpty()) {
            return renderedCopy
        }
    }
    return null
}

private fun KotlinJ2KEmitter.computeReceiverExpectedParseTarget(
    cleanMethod: String,
    methodEntryParseTarget: String?,
): String? =
    when (cleanMethod) {
        "unwrap", "unwrap_unchecked", "expect", "ok" -> {
            val outerTarget =
                methodEntryParseTarget
                    ?: if (callArgumentNestingDepth == 0) currentEnclosingReturnRenderedType else null
            val cleanOuter = outerTarget?.trim()
            if (usesTaggedCoreResult() &&
                cleanOuter != null &&
                cleanOuter.isNotEmpty() &&
                cleanOuter != "Unit" &&
                !cleanOuter.startsWith("${sourceResultName()}<") &&
                !cleanOuter.startsWith("Result<") &&
                !cleanOuter.startsWith("kotlin.Result<")
            ) {
                val okPayload = if (cleanMethod == "ok") cleanOuter.removeSuffix("?").trim() else cleanOuter
                if (okPayload.isNotEmpty() && okPayload != "Any?" && '*' !in okPayload) {
                    "${sourceResultName()}<$okPayload, Any?>"
                } else {
                    outerTarget
                }
            } else {
                outerTarget
            }
        }
        "map_err", "or_else", "or", "assume_init", "assume_init_read" ->
            methodEntryParseTarget ?: currentEnclosingReturnRenderedType
        else -> null
    }

private class MethodCallLoweringContext(
    val emitter: KotlinJ2KEmitter,
    val expr: Expr.MethodCall,
    val rawMethod: String,
    val cleanMethod: String,
    val indent: String,
    val selfTypeName: String?,
    val methodEntryParseTarget: String?,
    val effectiveReceiverExpr: Expr,
    val receiver: String,
    val allMethodArgs: List<Expr>,
    val nonUnitArgs: List<Expr>,
) {
    val recvAtom: String
        get() = emitter.atomicRecvExpr(expr.receiver, receiver)

    val localOrInferredRecvTy: String?
        get() =
            (emitter.currentTypedLocals[receiver] ?: emitter.inferExprRenderedType(expr.receiver, selfTypeName))
                ?.removeSuffix("?")
                ?.trim()

    val inferredRecvTy: String?
        get() = emitter.inferExprRenderedType(expr.receiver, selfTypeName)?.removeSuffix("?")?.trim()

    val isDequeIterReceiver: Boolean =
        emitter.isDequeIterFieldExpr(expr.receiver, selfTypeName) || receiver in emitter.currentDequeLocals

    val isVecReceiver: Boolean =
        emitter.isMutableVecExpr(expr.receiver, receiver, selfTypeName)

    var method: String = ""
    var typeArgs: String = ""
    val macroTypeArgs: MutableList<String> = mutableListOf()
    val explicitMethodConstArgs: MutableList<String> = mutableListOf()
    val methodSubslicePre: MutableList<String> = mutableListOf()
    val methodSubslicePost: MutableList<String> = mutableListOf()
    var recvNumericKindForMethod: NumericKind? = null
    var wrappingNumericKind: NumericKind? = null
    var renderedValueArgs: List<String> = emptyList()

    fun refCellInnerType(): String? =
        refCellGuardPointeeType(localOrInferredRecvTy)

    fun derefRefCellOrAtom(): String =
        if (refCellInnerType() != null) "$recvAtom.value" else recvAtom

    fun renderIndexedGetOrNull(recvTarget: String): String {
        val indexExpr = allMethodArgs[0]
        val idxKind = emitter.inferExprNumericKind(indexExpr, selfTypeName)
        val idxArg =
            if (idxKind != null && idxKind != NumericKind.I32) {
                emitter.normalizeArrayLengthToIntExpr(renderedValueArgs[0], indexExpr, selfTypeName)
            } else {
                renderedValueArgs[0]
            }
        return "$recvTarget.getOrNull($idxArg)"
    }

    fun renderOptionEagerEvaluation(
        isEager: Boolean,
        argExpr: Expr,
        argText: String,
        lazyExpr: String,
        defaultTmpPrefix: String = "optDefault",
        someExpr: (String) -> String,
        runtimeIfExpr: (String, String) -> String,
    ): String {
        if (!isEager) return lazyExpr
        if (emitter.isDirectDivergingOptionArg(argExpr, argText)) {
            return "run { $receiver; $argText }"
        }
        if (emitter.unwrapUnqualifiedOptionSomeArg(expr.receiver) != null) {
            val recvTmp = "optRecv${emitter.closureLabelCounter++}"
            return "run { val $recvTmp = $receiver; $argText; ${someExpr(recvTmp)} }"
        }
        val idx = emitter.closureLabelCounter++
        val recvTmp = "optRecv$idx"
        val argTmp = "$defaultTmpPrefix$idx"
        return "run { val $recvTmp = $receiver; val $argTmp = $argText; ${runtimeIfExpr(recvTmp, argTmp)} }"
    }

    fun prepareEvaluatedCallArguments() {
        val isEscapedDoubleUnderscoreSelfCall =
            rawMethod.startsWith("__") && rustMethodIdentToKotlin(rawMethod) == emitter.currentEnclosingFunctionName
        method =
            when {
                isEscapedDoubleUnderscoreSelfCall && rawMethod == "__span" -> {
                    emitter.usesQuoteUnderlyingSpan = true
                    "quoteSpan"
                }
                isEscapedDoubleUnderscoreSelfCall -> {
                    val underlyingKt = rustMethodIdentToKotlin(rawMethod.removePrefix("__"))
                    val aliasKt = "underlying${underlyingKt.replaceFirstChar { it.uppercase() }}"
                    emitter.aliasedUnderlyingSelfCalls[underlyingKt] = aliasKt
                    aliasKt
                }
                cleanMethod == "assume_init" -> "assumeInit"
                cleanMethod == "assume_init_read" -> "assumeInitRead"
                (cleanMethod == "as_ptr" || cleanMethod == "as_mut_ptr") &&
                    (
                        receiver.startsWith("MaybeUninit.") ||
                            unwrapMaybeUninitRenderedInner(emitter.inferExprRenderedType(effectiveReceiverExpr, selfTypeName)) != null
                    ) ->
                    if (cleanMethod == "as_mut_ptr") "asMutPtr" else "asPtr"
                cleanMethod == "as_mut_ptr" && isNonNullReceiver() -> "asPtr"
                else -> rustMethodIdentToKotlin(rawMethod)
            }
        typeArgs =
            expr.turbofish
                ?.let { tf ->
                    val renderedArgs = mutableListOf<String>()
                    for (ga in tf.args.toList()) {
                        when (ga) {
                            is GenericArgument.ConstArg -> explicitMethodConstArgs += emitter.renderExpr(ga.expr, indent, selfTypeName)
                            is GenericArgument.TypeArg -> {
                                emitter.runtimeConstTypeArgument(ga)?.let { explicitMethodConstArgs += it }
                                val unwrappedMacro = unwrapSynType(ga.type) as? SynType.Macro
                                if (unwrappedMacro != null) {
                                    val renderedMac = emitter.renderMacroInvocationAsStmtOrExpr(unwrappedMacro.mac, indent, selfTypeName)
                                    if (rawMethod == "parse" && nonUnitArgs.isEmpty() && renderedMac.endsWith(".default()")) {
                                        macroTypeArgs += "${renderedMac.removeSuffix(".default()")}::parse"
                                    } else {
                                        macroTypeArgs += renderedMac
                                    }
                                } else {
                                    emitter.renderGenericArgument(ga, selfTypeName)?.let { renderedArgs += it }
                                }
                            }
                            else -> emitter.renderGenericArgument(ga, selfTypeName)?.let { renderedArgs += it }
                        }
                    }
                    if (renderedArgs.isNotEmpty()) "<${renderedArgs.joinToString(", ")}>" else ""
                }.orEmpty()
        val savedKind = emitter.currentExpectedNumericKind
        val savedTupleElemType = emitter.currentClosureTupleElemType
        val zipElemType =
            if ((cleanMethod == "all" || cleanMethod == "any") &&
                effectiveReceiverExpr is Expr.MethodCall &&
                effectiveReceiverExpr.method.toString().removePrefix("r#") == "zip"
            ) {
                val zipBase = unwrapTrailingIterReceiver(effectiveReceiverExpr.receiver)
                emitter.inferExprRenderedType(zipBase, selfTypeName)?.let {
                    emitter.extractIndexedElementRenderedType(it, selfTypeName)
                }
            } else {
                null
            }
        recvNumericKindForMethod =
            if (typeArgs.isEmpty() && rawMethod in NUMERIC_RECEIVER_KIND_METHODS) {
                emitter.inferExprNumericKind(expr.receiver, selfTypeName)
            } else {
                null
            }
        wrappingNumericKind =
            if ((rawMethod == "wrapping_add" || rawMethod == "wrapping_sub" || rawMethod == "wrapping_mul") &&
                allMethodArgs.size == 1 &&
                typeArgs.isEmpty()
            ) {
                recvNumericKindForMethod?.takeUnless {
                    it == NumericKind.F16 ||
                        it == NumericKind.F32 ||
                        it == NumericKind.F64 ||
                        it == NumericKind.F128 ||
                        it == NumericKind.I128 ||
                        it == NumericKind.U128
                }
            } else {
                null
            }
        emitter.currentExpectedNumericKind =
            when {
                rawMethod == "to_digit" -> NumericKind.U32
                recvNumericKindForMethod == null -> null
                rawMethod in U32_SHIFT_OR_EXP_METHODS -> NumericKind.U32
                rawMethod == "powi" || rawMethod == "unchecked_shl" || rawMethod == "unchecked_shr" -> NumericKind.I32
                else -> recvNumericKindForMethod
            }
        emitter.currentExpectedParseTargetType =
            if (cleanMethod == "and_then" || cleanMethod == "or_else") {
                methodEntryParseTarget ?: emitter.currentEnclosingReturnRenderedType
            } else {
                null
            }
        emitter.currentClosureTupleElemType = zipElemType
        val recvOwnerSimple = inferredRecvTy?.substringAfterLast('.')?.substringBefore('<')?.trim()
        renderedValueArgs =
            try {
                allMethodArgs.mapIndexed { argIdx, argExpr ->
                    val subsliceSpec =
                        emitter.tryExtractMutableArraySubsliceArg(
                            argExpr = argExpr,
                            argIndex = argIdx,
                            expectMutableList = { emitter.methodExpectsMutableListSliceParam(recvOwnerSimple, method, argIdx) },
                            indent = indent,
                            selfTypeName = selfTypeName,
                        )
                    if (subsliceSpec != null) {
                        methodSubslicePre += subsliceSpec.first
                        methodSubslicePost += subsliceSpec.third
                        subsliceSpec.second
                    } else {
                        val atomicArgKind = recvOwnerSimple?.let(::atomicOwnerValueNumericKind)
                        val expectsAtomicArg =
                            atomicArgKind != null &&
                                (
                                    (rawMethod in ATOMIC_SINGLE_VALUE_METHODS && argIdx == 0) ||
                                        ((rawMethod == "compare_exchange" || rawMethod == "compare_exchange_weak") && argIdx in 0..1)
                                )
                        val renderedArg =
                            if (expectsAtomicArg) {
                                emitter.renderTypedNumericInitializer(argExpr, atomicArgKind, indent, selfTypeName)
                            } else {
                                emitter.renderExpr(argExpr, indent, selfTypeName)
                            }
                        val recvHeadSimple = inferredRecvTy?.substringBefore('<')?.substringAfterLast('.')?.trim()
                        val isRawPtrRecv = recvOwnerSimple in RAW_PTR_OWNER_NAMES || recvHeadSimple in RAW_PTR_OWNER_NAMES
                        if (isRawPtrRecv && isStdlibConstPtrMethodParam(rawMethod, null, argIdx)) {
                            coerceMutPtrArgToConstPtr(renderedArg, emitter.inferExprRenderedType(argExpr, selfTypeName))
                        } else {
                            renderedArg
                        }
                    }
                }
            } finally {
                emitter.currentExpectedNumericKind = savedKind
                emitter.currentExpectedParseTargetType = methodEntryParseTarget
                emitter.currentClosureTupleElemType = savedTupleElemType
            }
    }

    private fun isNonNullReceiver(): Boolean {
        val effSimple =
            emitter
                .inferExprRenderedType(effectiveReceiverExpr, selfTypeName)
                ?.removeSuffix("?")
                ?.substringBefore('<')
                ?.substringAfterLast('.')
                ?.trim()
        if (effSimple == "NonNull") return true
        val synPath = emitter.sourceInitializerType(effectiveReceiverExpr)?.let(::unwrapSynType) as? SynType.Path ?: return false
        val segs = synPath.path.segments.toList()
        val lastSeg = segs.lastOrNull() ?: return false
        return lastSeg.ident.toString().removePrefix("r#") == "NonNull"
    }

    companion object {
        private val ATOMIC_SINGLE_VALUE_METHODS =
            setOf("store", "swap", "fetch_add", "fetch_sub", "fetch_and", "fetch_or", "fetch_xor", "fetch_max", "fetch_min")
        private val RAW_PTR_OWNER_NAMES = setOf("MutPtr", "ConstPtr")
    }
}

private abstract class ReceiverFirstMethodRule {
    abstract fun tryRender(ctx: MethodCallLoweringContext): String?
}

private object RawPointerMethodCallRule : ReceiverFirstMethodRule() {
    private val PTR_ARITH_METHODS =
        setOf(
            "offset",
            "add",
            "sub",
            "wrapping_offset",
            "wrapping_add",
            "wrapping_sub",
            "byte_offset",
            "byte_add",
            "byte_sub",
            "wrapping_byte_offset",
            "wrapping_byte_add",
            "wrapping_byte_sub",
        )

    override fun tryRender(ctx: MethodCallLoweringContext): String? {
        val emitter = ctx.emitter
        val expr = ctx.expr
        val rawMethod = ctx.rawMethod
        val receiver = ctx.receiver
        val nonUnitArgs = ctx.nonUnitArgs
        val recvRawPtrRenderedTy = emitter.inferExprRenderedType(expr.receiver, ctx.selfTypeName)
        if (rawPointerPointeeType(recvRawPtrRenderedTy) != null) {
            val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(emitter.headerContext)
            val ptrPkg = "$coreNs.ptr"
            val intrPkg = "$coreNs.intrinsics"
            val ptrCtor = if (isMutRawPointerRenderedType(recvRawPtrRenderedTy)) "MutPtr" else "ConstPtr"
            if (nonUnitArgs.size == 1 && rawMethod in PTR_ARITH_METHODS) {
                CoreIntrinsicLowering.registerCoreImport(emitter, "$ptrPkg.$ptrCtor")
                val argument = emitter.renderExpr(nonUnitArgs.single(), ctx.indent, ctx.selfTypeName)
                val deltaSign = if (rawMethod.endsWith("sub")) "-" else "+"
                val byteDelta =
                    if ("byte_" in rawMethod) {
                        "($argument).toLong()"
                    } else {
                        val pointeeLayout = emitter.resolveRawPointerPointeeLayout(expr.receiver, ctx.selfTypeName)
                        "($argument).toLong() * ($pointeeLayout).strideBytes"
                    }
                return "$ptrCtor(($receiver).rawAddress $deltaSign ($byteDelta))"
            }
            if (rawMethod == "is_null" && nonUnitArgs.isEmpty()) {
                return "(($receiver).rawAddress == 0L)"
            }
            if ((rawMethod == "cast_mut" || rawMethod == "cast_const") && nonUnitArgs.isEmpty() && expr.turbofish == null) {
                val pointee = rawPointerPointeeType(recvRawPtrRenderedTy) ?: "Any"
                val targetCtor = if (rawMethod == "cast_mut") "MutPtr" else "ConstPtr"
                CoreIntrinsicLowering.registerCoreImport(emitter, "$ptrPkg.$targetCtor")
                return "$targetCtor<$pointee>(($receiver).rawAddress)"
            }
            if (rawMethod == "cast" && nonUnitArgs.isEmpty()) {
                val tfArgs = expr.turbofish?.args?.toList()
                val destination = tfArgs?.firstNotNullOfOrNull { (it as? GenericArgument.TypeArg)?.type }
                val sourcePointee = rawPointerPointeeType(recvRawPtrRenderedTy)
                val returnDestination =
                    when (val returnType = emitter.currentEnclosingReturnSynType) {
                        is SynType.Ptr -> returnType.elem
                        is SynType.Reference -> (returnType.elem as? SynType.Slice)?.elem ?: returnType.elem
                        else -> null
                    }
                val destinationPointee =
                    destination?.let { emitter.renderType(it, ctx.selfTypeName) }
                        ?: rawPointerPointeeType(ctx.methodEntryParseTarget)
                        ?: rawPointerPointeeType(emitter.currentEnclosingReturnRenderedType)
                        ?: returnDestination?.let { emitter.renderType(it, ctx.selfTypeName) }
                        ?: sourcePointee
                        ?: "Any"
                if (sourcePointee == destinationPointee) return receiver
                CoreIntrinsicLowering.registerCoreImport(emitter, "$ptrPkg.$ptrCtor")
                return "$ptrCtor<$destinationPointee>(($receiver).rawAddress)"
            }
            if ((rawMethod == "read" || rawMethod == "read_unaligned" || rawMethod == "read_volatile") &&
                nonUnitArgs.isEmpty() &&
                expr.turbofish == null
            ) {
                val pointeeLayout = emitter.resolveRawPointerPointeeLayout(expr.receiver, ctx.selfTypeName)
                val readFn = if (rawMethod == "read_volatile") "readVolatileNative" else "readNative"
                return "($pointeeLayout).$readFn(($receiver).rawAddress)"
            }
            if ((rawMethod == "write" || rawMethod == "write_unaligned" || rawMethod == "write_volatile") &&
                nonUnitArgs.size == 1 &&
                expr.turbofish == null
            ) {
                val pointeeLayout = emitter.resolveRawPointerPointeeLayout(expr.receiver, ctx.selfTypeName)
                val valExpr = emitter.renderExpr(nonUnitArgs[0], ctx.indent, ctx.selfTypeName)
                val writeFn = if (rawMethod == "write_volatile") "writeVolatileNative" else "writeNative"
                return "($pointeeLayout).$writeFn(($receiver).rawAddress, $valExpr)"
            }
            if (rawMethod in setOf("copy_to", "copy_to_nonoverlapping", "copy_from", "copy_from_nonoverlapping") &&
                nonUnitArgs.size == 2 &&
                expr.turbofish == null
            ) {
                val pointeeLayout = emitter.resolveRawPointerPointeeLayout(expr.receiver, ctx.selfTypeName)
                val argExpr = emitter.renderExpr(nonUnitArgs[0], ctx.indent, ctx.selfTypeName)
                val countExpr = emitter.renderExpr(nonUnitArgs[1], ctx.indent, ctx.selfTypeName)
                val isFrom = rawMethod.startsWith("copy_from")
                val fnName = if (rawMethod.endsWith("_nonoverlapping")) "kmCopyNonoverlapping" else "kmCopy"
                CoreIntrinsicLowering.registerCoreImport(emitter, "$intrPkg.$fnName")
                val srcAddr = if (isFrom) "($argExpr).rawAddress" else "($receiver).rawAddress"
                val dstAddr = if (isFrom) "($receiver).rawAddress" else "($argExpr).rawAddress"
                return "$fnName($srcAddr, $dstAddr, ($countExpr).toLong() * ($pointeeLayout).sizeBytes)"
            }
            if (rawMethod == "write_bytes" && nonUnitArgs.size == 2 && expr.turbofish == null) {
                val pointeeLayout = emitter.resolveRawPointerPointeeLayout(expr.receiver, ctx.selfTypeName)
                val byteExpr = emitter.renderExpr(nonUnitArgs[0], ctx.indent, ctx.selfTypeName)
                val countExpr = emitter.renderExpr(nonUnitArgs[1], ctx.indent, ctx.selfTypeName)
                CoreIntrinsicLowering.registerCoreImport(emitter, "$intrPkg.kmWriteBytes")
                return "kmWriteBytes(($receiver).rawAddress, ($byteExpr).toUByte(), ($countExpr).toLong() * ($pointeeLayout).sizeBytes)"
            }
            if (rawMethod == "offset_from" && nonUnitArgs.size == 1 && expr.turbofish == null) {
                val pointeeLayout = emitter.resolveRawPointerPointeeLayout(expr.receiver, ctx.selfTypeName)
                val originExpr = emitter.renderExpr(nonUnitArgs[0], ctx.indent, ctx.selfTypeName)
                val diffExpr = "((($receiver).rawAddress - ($originExpr).rawAddress) / ($pointeeLayout).strideBytes)"
                return if (emitter.headerContext.rawAbiMode) diffExpr else "$diffExpr.toInt()"
            }
            if (rawMethod == "addr" && nonUnitArgs.isEmpty() && expr.turbofish == null) {
                return if (emitter.headerContext.rawAbiMode) "($receiver).rawAddress.toULong()" else "($receiver).rawAddress.toInt()"
            }
            if (rawMethod == "with_addr" && nonUnitArgs.size == 1 && expr.turbofish == null) {
                val addrExpr = emitter.renderExpr(nonUnitArgs[0], ctx.indent, ctx.selfTypeName)
                CoreIntrinsicLowering.registerCoreImport(emitter, "$ptrPkg.$ptrCtor")
                return "$ptrCtor(($addrExpr).toLong())"
            }
        }
        if (emitter.headerContext.rawAbiMode && nonUnitArgs.isEmpty()) {
            if (rawMethod == "is_null") return "($receiver == 0L)"
            if ((rawMethod == "cast_mut" || rawMethod == "cast_const") && expr.turbofish == null) return receiver
        }
        return null
    }
}

private object EarlyPrimitiveAndBitsMethodRule : ReceiverFirstMethodRule() {
    override fun tryRender(ctx: MethodCallLoweringContext): String? {
        val emitter = ctx.emitter
        val expr = ctx.expr
        val rawMethod = ctx.rawMethod
        val receiver = ctx.receiver
        if (ctx.nonUnitArgs.isNotEmpty()) return null
        if (rawMethod == "count_ones" && expr.turbofish == null) {
            val recvKind = emitter.inferExprNumericKind(expr.receiver, ctx.selfTypeName)
            if (recvKind != null && recvKind !in FLOAT_KINDS) {
                val recvAtom = ctx.recvAtom
                if ("core" in emitter.headerContext.crateNamespaces) {
                    val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(emitter.headerContext)
                    if (emitter.headerContext.packageName != "$coreNs.num") {
                        CoreIntrinsicLowering.registerCoreImport(emitter, "$coreNs.num.countOnes")
                    }
                    return "$recvAtom.countOnes()"
                }
                return "$recvAtom.countOneBits().toUInt()"
            }
        }
        if (rawMethod == "to_bits" && expr.turbofish == null) {
            emitter.inferExprNumericKind(expr.receiver, ctx.selfTypeName)?.let { recvKind ->
                emitter.renderFloatToBitsExpr(expr.receiver, receiver, recvKind)?.let { return it }
            }
        }
        if (rawMethod == "cast" && emitter.headerContext.rawAbiMode) {
            return receiver
        }
        if ((rawMethod == "to_be_bytes" || rawMethod == "to_le_bytes" || rawMethod == "to_ne_bytes") && expr.turbofish == null) {
            emitter.inferExprNumericKind(expr.receiver, ctx.selfTypeName)?.let { recvKind ->
                return emitter.renderIntToBytesMethodCall(receiver, expr.receiver, recvKind, rawMethod, ctx.selfTypeName)
            }
        }
        if (rawMethod in ENDIAN_SWAP_METHODS && expr.turbofish == null) {
            val recvKind = emitter.inferExprNumericKind(expr.receiver, ctx.selfTypeName)
            if (recvKind != null && recvKind !in FLOAT_KINDS) {
                val endian128 =
                    emitter.tryRender128BitEndianMethodCall(
                        rawMethod = rawMethod,
                        primKind = recvKind,
                        recvExpr = expr.receiver,
                        renderedRecv = receiver,
                        guardSelfRecursion = false,
                    )
                if (endian128 != null) return endian128
                if (recvKind != NumericKind.U128 && recvKind != NumericKind.I128) {
                    return if (rawMethod == "to_le" || rawMethod == "from_le" || recvKind == NumericKind.I8 || recvKind == NumericKind.U8) {
                        receiver
                    } else {
                        renderPrimitiveByteSwapExpr(receiver, recvKind)
                    }
                }
            }
        }
        return null
    }

    private val FLOAT_KINDS = setOf(NumericKind.F16, NumericKind.F32, NumericKind.F64, NumericKind.F128)
    private val ENDIAN_SWAP_METHODS = setOf("to_be", "from_be", "swap_bytes", "to_le", "from_le")
}

private object EarlyReceiverStateAndCollectionRule : ReceiverFirstMethodRule() {
    override fun tryRender(ctx: MethodCallLoweringContext): String? {
        if (ctx.nonUnitArgs.isNotEmpty()) return null
        val emitter = ctx.emitter
        val expr = ctx.expr
        val rawMethod = ctx.rawMethod
        val receiver = ctx.receiver
        val noTurbofish = expr.turbofish == null
        if (rawMethod == "len" && noTurbofish) {
            val effectiveLenTy = ctx.refCellInnerType() ?: ctx.localOrInferredRecvTy
            val recvTarget = ctx.derefRefCellOrAtom()
            if (effectiveLenTy != null) {
                if (effectiveLenTy == "String" || effectiveLenTy == "CharSequence" || effectiveLenTy == "StringBuilder") {
                    return "$recvTarget.length"
                }
                if (effectiveLenTy in PRIMITIVE_ARRAY_TYPES || SIZED_COLLECTION_PREFIXES.any { effectiveLenTy.startsWith(it) }) {
                    return "$recvTarget.size"
                }
            }
            if (emitter.inferArrayElementNumericKind(expr.receiver, ctx.selfTypeName) != null) {
                return "$receiver.size"
            }
        }
        if (rawMethod == "is_empty" && noTurbofish && ctx.refCellInnerType() != null) {
            return "${ctx.recvAtom}.value.isEmpty()"
        }
        if ((rawMethod == "is_none" || rawMethod == "is_some") &&
            !emitter.hasDeclaredOptionEnum() &&
            !emitter.isKnownNonNullableNonOptionReceiver(expr.receiver, receiver, ctx.selfTypeName)
        ) {
            return if (rawMethod == "is_none") "$receiver == null" else "$receiver != null"
        }
        if (rawMethod == "as_bytes" && noTurbofish) {
            val effectiveBytesTy = ctx.refCellInnerType() ?: ctx.localOrInferredRecvTy
            if (effectiveBytesTy == null || effectiveBytesTy in STRING_LIKE_TYPES) {
                val strExpr = ctx.derefRefCellOrAtom()
                return if (emitter.headerContext.rawAbiMode || emitter.headerContext.finalClasses) {
                    "$strExpr.encodeToByteArray().asUByteArray()"
                } else {
                    "$strExpr.encodeToByteArray().map { it.toUByte() }"
                }
            }
        }
        if (rawMethod == "as_str" && noTurbofish) {
            val recvTy = ctx.localOrInferredRecvTy
            if (ctx.refCellInnerType() == "String") return "${ctx.recvAtom}.value"
            if (recvTy == "String") return receiver
            val instanceMethods = emitter.inspector.instanceMethodsByType.values
            val crateFnKeys = emitter.headerContext.crateFnReturnTypes.keys
            if (recvTy == null &&
                instanceMethods.none { "asStr" in it } &&
                crateFnKeys.none { it.endsWith(".asStr") }
            ) {
                return receiver
            }
        }
        if ((rawMethod == "as_ref" || rawMethod == "borrow") && noTurbofish) {
            val recvTy = ctx.localOrInferredRecvTy
            if (receiver in emitter.currentIntoSubstitutedParams ||
                recvTy == "String" ||
                recvTy?.startsWith("List<") == true ||
                recvTy?.startsWith("MutableList<") == true
            ) {
                return receiver
            }
        }
        if (rawMethod == "id" && (receiver == "thread.current()" || receiver.endsWith(".thread.current()") || isThreadCurrentCall(ctx))) {
            emitter.usesCurrentThreadToken = true
            return "currentThreadBoundToken()"
        }
        if (rawMethod == "debug_list" || rawMethod == "debug_set") {
            val (open, close) = if (rawMethod == "debug_list") "[" to "]" else "{" to "}"
            return "object { val entries = mutableListOf<Any?>(); fun entry(v: Any?) = apply { entries.add(v) }; fun entries(iter: Iterable<*>) = apply { for (e in iter) entries.add(e) }; fun finish() { $receiver.append(entries.joinToString(\", \", \"$open\", \"$close\")) } }"
        }
        if (rawMethod == "debug_map") {
            return "object { val entries = mutableListOf<String>(); fun entry(k: Any?, v: Any?) = apply { entries.add(\"\$k: \$v\") }; fun finish() { $receiver.append(entries.joinToString(\", \", \"{\", \"}\")) } }"
        }
        if (rawMethod == "pop_front") {
            return "$receiver.removeFirstOrNull()"
        }
        if ((rawMethod == "as_deref" || rawMethod == "as_deref_mut" || rawMethod == "as_ref" || rawMethod == "as_mut") &&
            noTurbofish &&
            emitter.isKnownNullableOptionExpr(expr.receiver, receiver, ctx.selfTypeName)
        ) {
            return receiver
        }
        if (ctx.isDequeIterReceiver && noTurbofish) {
            when (rawMethod) {
                "next" -> return "$receiver.removeFirstOrNull()"
                "next_back" -> return "$receiver.removeLastOrNull()"
                "len" -> return "$receiver.size"
                "clone" -> return "ArrayDeque($receiver)"
            }
        }
        if (ctx.isVecReceiver && noTurbofish) {
            when (rawMethod) {
                "pop" -> return "$receiver.removeLastOrNull()"
                "len" -> return "$receiver.size"
                "into_iter", "iter", "iter_mut" -> return "ArrayDeque($receiver)"
                "clone" -> return "$receiver.toMutableList()"
                "last_mut" -> return "$receiver.lastOrNull()"
                "first_mut" -> return "$receiver.firstOrNull()"
            }
        }
        if (rawMethod == "into" && noTurbofish && receiver in emitter.currentIntoSubstitutedParams) {
            return receiver
        }
        if (rawMethod == "sve_into" && noTurbofish) {
            val recvTy = ctx.inferredRecvTy
            val recvSimple = recvTy?.substringBefore('<')?.substringAfterLast('.')?.trim()
            val targetRaw =
                ctx.methodEntryParseTarget
                    ?: if (emitter.callArgumentNestingDepth == 0) emitter.currentEnclosingReturnRenderedType else null
            val targetNonNull = targetRaw?.removeSuffix("?")
            val targetSimple = targetNonNull?.substringBefore('<')?.substringAfterLast('.')?.trim()
            if (recvSimple != null && targetSimple != null) {
                if (recvSimple == targetSimple) return receiver
                if (recvSimple == "SvboolT" && (targetSimple == "Svbool4T" || targetSimple == "Svbool8T")) {
                    return "$receiver.sveInto$targetSimple()"
                }
            }
        }
        return null
    }

    private fun isThreadCurrentCall(ctx: MethodCallLoweringContext): Boolean {
        val recvCore = ctx.emitter.unwrapParenOrGroupExpr(ctx.expr.receiver) as? Expr.Call ?: return false
        if (!recvCore.args.isEmpty()) return false
        val fnPath = (ctx.emitter.unwrapParenOrGroupExpr(recvCore.func) as? Expr.Path)?.takeIf { it.qself == null } ?: return false
        val segList = fnPath.path.segments.toList()
        val segs = segList.map { it.ident.toString().removePrefix("r#") }
        val scope = ctx.emitter.scopeBuilder
        return segs == listOf("thread", "current") ||
            segs == listOf("std", "thread", "current") ||
            (segs == listOf("current") && scope.resolveModuleBinding("current")?.importedPath == "std.thread.current")
    }

    private val STRING_LIKE_TYPES = setOf("String", "CharSequence", "StringBuilder")
    private val SIZED_COLLECTION_PREFIXES =
        listOf("List<", "MutableList<", "Set<", "MutableSet<", "Map<", "MutableMap<", "Collection<", "ArrayDeque<")
}

private object TryIntoStdlibIntegerMethodRule : ReceiverFirstMethodRule() {
    private val STDLIB_INT_TYPES = setOf("Byte", "Short", "Int", "Long", "I128", "UByte", "UShort", "UInt", "ULong", "U128")
    private val FROM_ONLY_PAIRS =
        setOf(
            "Byte" to "Short",
            "Byte" to "Long",
            "Byte" to "I128",
            "Short" to "Long",
            "Short" to "I128",
            "Long" to "I128",
            "UByte" to "Short",
            "UByte" to "Int",
            "UByte" to "Long",
            "UByte" to "I128",
            "UByte" to "UShort",
            "UByte" to "UInt",
            "UByte" to "ULong",
            "UByte" to "U128",
            "UShort" to "Long",
            "UShort" to "I128",
            "UShort" to "UInt",
            "UShort" to "ULong",
            "UShort" to "U128",
            "UInt" to "Long",
            "UInt" to "I128",
            "UInt" to "ULong",
            "UInt" to "U128",
            "ULong" to "I128",
            "ULong" to "U128",
        )

    override fun tryRender(ctx: MethodCallLoweringContext): String? {
        val emitter = ctx.emitter
        val headerCtx = emitter.headerContext
        val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerCtx)
        if (ctx.cleanMethod != "try_into" ||
            ctx.nonUnitArgs.isNotEmpty() ||
            !emitter.usesTaggedCoreResult() ||
            !("core" in headerCtx.crateNamespaces || headerCtx.packageName?.startsWith(coreNs) == true)
        ) {
            return null
        }
        val srcSimple = ctx.inferredRecvTy?.removePrefix("kotlin.")?.trim()
        val turbofish = ctx.expr.turbofish
        val tfArgs = turbofish?.args?.toList()
        val explicitDst =
            (tfArgs?.firstOrNull() as? GenericArgument.TypeArg)
                ?.type
                ?.let {
                    emitter
                        .renderType(it, ctx.selfTypeName)
                        .removeSuffix("?")
                        .removePrefix("kotlin.")
                        .trim()
                }
        val dstSimple =
            explicitDst
                ?: rustResultTypeArguments(ctx.methodEntryParseTarget)
                    ?.first
                    ?.removeSuffix("?")
                    ?.removePrefix("kotlin.")
                    ?.trim()
                ?: if (ctx.methodEntryParseTarget == null && emitter.callArgumentNestingDepth == 0) {
                    emitter.currentEnclosingReturnRenderedType
                        ?.let { rustResultTypeArguments(it)?.first ?: it }
                        ?.removeSuffix("?")
                        ?.removePrefix("kotlin.")
                        ?.trim()
                } else {
                    null
                }
        if (srcSimple == null || dstSimple == null || srcSimple !in STDLIB_INT_TYPES || dstSimple !in STDLIB_INT_TYPES) {
            return null
        }
        val dstOwner = if (dstSimple == "I128" || dstSimple == "U128") dstSimple else emitter.kotlinBuiltinTypeName(dstSimple)
        val resName = emitter.sourceResultName()
        if ((srcSimple to dstSimple) in FROM_ONLY_PAIRS) {
            if (headerCtx.packageName != "$coreNs.convert.num") {
                CoreIntrinsicLowering.registerCoreImport(emitter, "$coreNs.convert.num.from")
            }
            return "$resName.Ok<$dstOwner, kotlin.Nothing>($dstOwner.from(${ctx.receiver}))"
        }
        if (srcSimple == dstSimple) {
            val srcRaw = extractSynTypeLeafIdent(emitter.sourceInitializerType(ctx.expr.receiver))
            val dstRaw = extractSynTypeLeafIdent(emitter.currentEnclosingReturnSynType)
            if (srcSimple == "Int" &&
                ((srcRaw == "usize" && dstRaw in setOf("i32", "isize")) || (dstRaw == "usize" && srcRaw in setOf("i32", "isize")))
            ) {
                if (headerCtx.packageName != "$coreNs.convert.num") {
                    CoreIntrinsicLowering.registerCoreImport(emitter, "$coreNs.convert.num.tryFrom")
                }
                return "$dstOwner.tryFrom(${ctx.receiver})"
            }
            return "$resName.Ok<$dstOwner, kotlin.Nothing>(${ctx.receiver})"
        }
        if (headerCtx.packageName != "$coreNs.convert.num") {
            CoreIntrinsicLowering.registerCoreImport(emitter, "$coreNs.convert.num.tryFrom")
        }
        return "$dstOwner.tryFrom(${ctx.receiver})"
    }

    private fun extractSynTypeLeafIdent(ty: SynType?): String? {
        val synPath = ty?.let(::unwrapSynType) as? SynType.Path ?: return null
        val lastSeg = synPath.path.segments.last()
        return lastSeg?.ident?.toString()
    }
}

private object EarlyIntoIterAndCloneWitnessRule : ReceiverFirstMethodRule() {
    override fun tryRender(ctx: MethodCallLoweringContext): String? {
        if (ctx.nonUnitArgs.isNotEmpty() || ctx.expr.turbofish != null) return null
        val emitter = ctx.emitter
        val receiver = ctx.receiver
        if (ctx.rawMethod == "into_iter") {
            if (receiver in emitter.currentIterableLocals) return "$receiver.iterator()"
            if (!emitter.isKnownNullableOptionExpr(ctx.expr.receiver, receiver, ctx.selfTypeName) &&
                !emitter.hasDeclaredOptionEnum() &&
                "IntoIterator" !in emitter.inspector.declaredTraitsByName
            ) {
                val recvTy = ctx.inferredRecvTy
                if (recvTy != null &&
                    (
                        recvTy == "TokenStream" ||
                            recvTy.endsWith(".TokenStream") ||
                            recvTy.endsWith("Array") ||
                            INTO_ITER_COLLECTION_PREFIXES.any { recvTy.startsWith(it) }
                    )
                ) {
                    return "ArrayDeque(($receiver).toList())"
                }
            }
        }
        if (ctx.rawMethod == "clone") {
            emitter.currentTypedLocals[receiver]?.let { recvTy ->
                emitter.currentTypeParamWitnessCalls[recvTy to "clone"]?.let { cloneFn ->
                    return "$cloneFn($receiver)"
                }
            }
        }
        return null
    }

    private val INTO_ITER_COLLECTION_PREFIXES =
        listOf("Iterable<", "Collection<", "List<", "MutableList<", "Set<", "MutableSet<")
}

private object SynAndStringParseMethodRule : ReceiverFirstMethodRule() {
    override fun tryRender(ctx: MethodCallLoweringContext): String? {
        if (ctx.rawMethod != "parse" || ctx.nonUnitArgs.isNotEmpty()) return null
        val emitter = ctx.emitter
        val expr = ctx.expr
        val receiver = ctx.receiver
        val turbofish = expr.turbofish
        if (turbofish != null && turbofish.args.size == 1) {
            val tfGa = turbofish.args.first() as? GenericArgument.TypeArg ?: return null
            val renderedTfTy = emitter.renderType(tfGa.type, ctx.selfTypeName).trim()
            emitter.tryRenderStringPrimitiveParse(expr, receiver, renderedTfTy, ctx.selfTypeName)?.let { return it }
            if (renderedTfTy.endsWith("?")) {
                tryRenderOptionalSynParse(ctx, renderedTfTy)?.let { return it }
            } else {
                val recvTyBase = ctx.localOrInferredRecvTy?.substringAfterLast('.')?.trim()
                if ((recvTyBase == "ParseStream" || recvTyBase == "ParseBuffer") && isConcreteSynParseTarget(emitter, renderedTfTy)) {
                    return "$receiver.parse($renderedTfTy::parse)"
                }
            }
            return null
        }
        if (turbofish == null) {
            val inferredPrimitiveTarget =
                (ctx.methodEntryParseTarget ?: emitter.currentEnclosingReturnRenderedType)
                    ?.let { rustResultOkPayloadType(it) ?: it }
                    ?.removeSuffix("?")
                    ?.trim()
            emitter.tryRenderStringPrimitiveParse(expr, receiver, inferredPrimitiveTarget, ctx.selfTypeName)?.let { return it }
            if (ctx.methodEntryParseTarget?.endsWith("?") == true) {
                tryRenderOptionalSynParse(ctx, ctx.methodEntryParseTarget)?.let { return it }
            }
            val nonNullParseTarget = ctx.methodEntryParseTarget?.takeIf { !it.endsWith("?") }
            val matchedParseWitness =
                (nonNullParseTarget?.let { emitter.currentTypeParamWitnessCalls[it to "parse"] })
                    ?: emitter.currentTypeParamWitnessCalls.entries
                        .filter { it.key.second == "parse" }
                        .map { it.value }
                        .distinct()
                        .singleOrNull()
            if (matchedParseWitness != null) {
                return "$receiver.parse($matchedParseWitness)"
            }
            val concreteTarget = nonNullParseTarget?.takeIf { isConcreteSynParseTarget(emitter, it) }
            if (concreteTarget != null) {
                return "$receiver.parse($concreteTarget::parse)"
            }
        }
        return null
    }

    private fun tryRenderOptionalSynParse(ctx: MethodCallLoweringContext, optionalTarget: String): String? {
        val emitter = ctx.emitter
        val optInner = optionalTarget.removeSuffix("?").trim()
        val peekTok = emitter.resolveOptionalSynParsePeekToken(optInner) ?: return null
        if (optInner in emitter.activeTypeParamNames || optInner in KOTLIN_STDLIB_SHADOWED_IMPORTS) return null
        return emitter.renderOptionalSynParseExpr(ctx.receiver, peekTok, optInner)
    }

    private fun isConcreteSynParseTarget(emitter: KotlinJ2KEmitter, target: String): Boolean =
        target.isNotEmpty() &&
            target != "TokenStream" &&
            target !in emitter.activeTypeParamNames &&
            target !in KOTLIN_STDLIB_SHADOWED_IMPORTS &&
            target.all { it.isLetterOrDigit() || it == '_' || it == '.' }
}

private object IntoTokenStreamBorrowRule : ReceiverFirstMethodRule() {
    override fun tryRender(ctx: MethodCallLoweringContext): String? {
        if (ctx.rawMethod != "into_token_stream" || ctx.nonUnitArgs.isNotEmpty() || ctx.expr.turbofish != null) return null
        val emitter = ctx.emitter
        val unwrappedRecv = emitter.unwrapParenOrGroupExpr(ctx.expr.receiver)
        val isSharedBorrowRecv =
            (unwrappedRecv is Expr.Reference && unwrappedRecv.mutability == null) ||
                (emitter.currentReceiverIsSharedBorrow && unwrappedRecv is Expr.Path && unwrappedRecv.qself == null && unwrappedRecv.path.isIdent("self"))
        return if (isSharedBorrowRecv) {
            "TokenStream.new().also { tokens -> ${ctx.recvAtom}.toTokens(tokens) }"
        } else {
            null
        }
    }
}

private val RECEIVER_FIRST_METHOD_RULES: List<ReceiverFirstMethodRule> =
    listOf(
        RawPointerMethodCallRule,
        EarlyPrimitiveAndBitsMethodRule,
        EarlyReceiverStateAndCollectionRule,
        TryIntoStdlibIntegerMethodRule,
        EarlyIntoIterAndCloneWitnessRule,
        SynAndStringParseMethodRule,
        IntoTokenStreamBorrowRule,
    )
private abstract class PostArgMethodCallRule {
    abstract fun tryRender(ctx: MethodCallLoweringContext): String?
}

private object PostArgArithmeticAndHashRule : PostArgMethodCallRule() {
    override fun tryRender(ctx: MethodCallLoweringContext): String? {
        val emitter = ctx.emitter
        val wrappingKind = ctx.wrappingNumericKind
        if (wrappingKind != null) {
            val op =
                when (ctx.rawMethod) {
                    "wrapping_add" -> "+"
                    "wrapping_sub" -> "-"
                    else -> "*"
                }
            val coercedRhs = emitter.coerceRenderedNumericExpr(ctx.allMethodArgs[0], ctx.renderedValueArgs[0], wrappingKind)
            val rhs = emitter.atomicRecvExpr(ctx.allMethodArgs[0], coercedRhs)
            val arithmetic = "(${ctx.recvAtom} $op $rhs)"
            return when (wrappingKind) {
                NumericKind.I8 -> "$arithmetic.toByte()"
                NumericKind.U8 -> "$arithmetic.toUByte()"
                NumericKind.I16 -> "$arithmetic.toShort()"
                NumericKind.U16 -> "$arithmetic.toUShort()"
                else -> arithmetic
            }
        }
        val hasherState = emitter.currentHasherStateParam
        if (hasherState != null &&
            ctx.rawMethod == "hash" &&
            ctx.renderedValueArgs.size == 1 &&
            ctx.renderedValueArgs[0] == hasherState &&
            ctx.typeArgs.isEmpty()
        ) {
            return "$hasherState = 31 * $hasherState + (${ctx.receiver}).hashCode()"
        }
        return null
    }
}

private object CoreStrAndCharMethodRule : PostArgMethodCallRule() {
    override fun tryRender(ctx: MethodCallLoweringContext): String? {
        val emitter = ctx.emitter
        val headerCtx = emitter.headerContext
        if ("core" !in headerCtx.crateNamespaces || ctx.typeArgs.isNotEmpty()) return null
        val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerCtx)
        val recvTyForStr = ctx.localOrInferredRecvTy
        val atomicRecv = ctx.recvAtom
        val rawMethod = ctx.rawMethod
        val args = ctx.renderedValueArgs
        val instanceMethods = emitter.inspector.instanceMethodsByType.values
        return when {
            rawMethod == "chars" &&
                args.isEmpty() &&
                (recvTyForStr == "String" || recvTyForStr == "CharSequence") &&
                "chars" !in emitter.inspector.declaredTopLevelFnRawNames &&
                instanceMethods.none { "chars" in it } &&
                !(headerCtx.packageName == "$coreNs.str" && emitter.currentEnclosingFunctionName == "chars") -> {
                if (headerCtx.packageName != "$coreNs.str") {
                    CoreIntrinsicLowering.registerCoreImport(emitter, "$coreNs.str.chars")
                }
                "$atomicRecv.chars()"
            }
            (recvTyForStr == "Chars" || recvTyForStr?.endsWith(".Chars") == true) &&
                !(headerCtx.packageName == "$coreNs.str.iter" && ctx.selfTypeName == "Chars") &&
                args.isEmpty() ->
                when (rawMethod) {
                    "next" -> "$atomicRecv.next()"
                    "as_str" -> "$atomicRecv.asStr()"
                    "clone" -> "$atomicRecv.clone()"
                    "enumerate" -> "$atomicRecv.enumerate()"
                    else -> null
                }
            rawMethod == "to_digit" &&
                args.size == 1 &&
                (recvTyForStr == "Char" || (emitter.unwrapParenOrGroupExpr(ctx.expr.receiver) as? Expr.Lit)?.lit is Lit.Char) &&
                !(headerCtx.packageName == "$coreNs.char.methods" && emitter.currentEnclosingFunctionName == "toDigit") -> {
                if (headerCtx.packageName != "$coreNs.char.methods") {
                    CoreIntrinsicLowering.registerCoreImport(emitter, "$coreNs.char.methods.toDigit")
                }
                val radixArg = emitter.coerceNumericArgIfNeeded(ctx.allMethodArgs[0], args[0], NumericKind.U32, ctx.selfTypeName)
                "$atomicRecv.toDigit($radixArg)"
            }
            else -> null
        }
    }
}

private object ConcreteNumericDispatchMethodRule : PostArgMethodCallRule() {
    override fun tryRender(ctx: MethodCallLoweringContext): String? =
        ctx.emitter.renderConcreteNumericMethodCall(
            expr = ctx.expr,
            receiver = ctx.receiver,
            rawMethod = ctx.rawMethod,
            nonUnitArgs = ctx.allMethodArgs,
            renderedValueArgs = ctx.renderedValueArgs,
            typeArgs = ctx.typeArgs,
            indent = ctx.indent,
            selfTypeName = ctx.selfTypeName,
        )
}

private object FmtAndCollectionTransformRule : PostArgMethodCallRule() {
    private val RAW_ABI_BYTE_OFFSET_METHODS =
        setOf("byte_offset", "byte_add", "byte_sub", "wrapping_byte_offset", "wrapping_byte_add", "wrapping_byte_sub")

    override fun tryRender(ctx: MethodCallLoweringContext): String? {
        if (ctx.rawMethod == "collect" && ctx.renderedValueArgs.isEmpty()) {
            return tryRenderCollect(ctx)
        }
        if (ctx.typeArgs.isNotEmpty()) return null
        val emitter = ctx.emitter
        val expr = ctx.expr
        val rawMethod = ctx.rawMethod
        val receiver = ctx.receiver
        val args = ctx.renderedValueArgs
        if (rawMethod == "append_all" && args.size == 1) {
            return "for (item in ${args[0]}) item.toTokens($receiver)"
        }
        if ((rawMethod == "write_str" || rawMethod == "write_char" || rawMethod == "pad" || rawMethod == "write_fmt") &&
            args.size == 1 &&
            emitter.isFmtStringBuilderReceiver(expr.receiver, receiver, ctx.selfTypeName)
        ) {
            return "$receiver.append(${args[0]})"
        }
        val isStringLitRecv = emitter.unwrapParenOrGroupExpr(expr.receiver).let { it is Expr.Lit && it.lit is Lit.Str }
        if (rawMethod == "fmt" &&
            args.size == 1 &&
            (ctx.inferredRecvTy == "String" || isStringLitRecv) &&
            emitter.isFmtStringBuilderReceiver(ctx.allMethodArgs[0], args[0], ctx.selfTypeName)
        ) {
            return "${args[0]}.append($receiver)"
        }
        if (rawMethod == "find" && args.size == 1) {
            return "$receiver.indexOf(${args[0]}).takeIf { it >= 0 }"
        }
        if ((rawMethod == "position" || rawMethod == "rposition") && args.size == 1) {
            val idxFn = if (rawMethod == "position") "indexOfFirst" else "indexOfLast"
            return "${ctx.recvAtom}.$idxFn(${args[0]}).takeIf { it >= 0 }"
        }
        if (rawMethod == "enumerate" && args.isEmpty()) {
            return "${ctx.recvAtom}.mapIndexed { index, item -> Pair(index, item) }"
        }
        if (rawMethod == "take" && args.size == 1) {
            emitter.unwrapRepeatSourceArg(expr.receiver, ctx.indent, ctx.selfTypeName)?.let { repArg ->
                val elemRendered = emitter.renderExpr(repArg, ctx.indent, ctx.selfTypeName)
                return "List(${args[0]}) { $elemRendered }"
            }
        }
        if ((rawMethod == "push_str" || (rawMethod == "push" && !ctx.isVecReceiver)) && args.size == 1) {
            emitter.tryRenderStringAppendMethod(expr, receiver, args[0], ctx.selfTypeName)?.let { return it }
        }
        if (rawMethod == "clear" && args.isEmpty()) {
            if (ctx.refCellInnerType() == "String") return "${ctx.recvAtom}.value = \"\""
            if (ctx.localOrInferredRecvTy == "String" && receiver.all { it.isLetterOrDigit() || it == '_' || it == '.' }) {
                return "$receiver = \"\""
            }
        }
        if (rawMethod == "extend_from_slice" && args.size == 1) {
            if (ctx.isVecReceiver || ctx.localOrInferredRecvTy?.startsWith("MutableList<") == true) {
                return "$receiver.addAll(${args[0]})"
            }
        }
        if (rawMethod in RAW_ABI_BYTE_OFFSET_METHODS && args.size == 1 && emitter.headerContext.rawAbiMode) {
            val deltaSign = if (rawMethod.endsWith("sub")) "-" else "+"
            val deltaRecv = emitter.atomicRecvExpr(ctx.allMethodArgs[0], args[0])
            val deltaExpr =
                emitter.convertNumericReceiver(
                    deltaRecv,
                    "Long",
                    emitter.inferExprNumericKind(ctx.allMethodArgs[0], ctx.selfTypeName),
                )
            return "($receiver $deltaSign $deltaExpr)"
        }
        if (rawMethod == "debug_struct" && args.size == 1 && "DebugStruct" !in emitter.inspector.declaredTypeNames) {
            return "DebugStruct($receiver, ${args[0]})"
        }
        if (rawMethod == "debug_tuple" && args.size == 1 && "DebugTuple" !in emitter.inspector.declaredTypeNames) {
            return "DebugTuple($receiver, ${args[0]})"
        }
        if (rawMethod == "push_back" && args.size == 1) {
            return "$receiver.addLast(${args[0]})"
        }
        return null
    }

    private fun tryRenderCollect(ctx: MethodCallLoweringContext): String? {
        val emitter = ctx.emitter
        val rawTypeArgs = ctx.typeArgs
        val explicitCollectTarget = rawTypeArgs.removePrefix("<").removeSuffix(">").trim()
        val fallbackTarget = (ctx.methodEntryParseTarget ?: emitter.currentEnclosingReturnRenderedType)?.trim().orEmpty()
        val effectiveCollectTarget = explicitCollectTarget.ifEmpty { fallbackTarget }
        val recvAtom = ctx.recvAtom
        if (effectiveCollectTarget == "String") {
            val recvTake = emitter.unwrapParenOrGroupExpr(ctx.expr.receiver) as? Expr.MethodCall
            if (recvTake != null && recvTake.method.toString() == "take" && recvTake.args.len() == 1) {
                emitter.unwrapRepeatSourceArg(recvTake.receiver, ctx.indent, ctx.selfTypeName)?.let { repArg ->
                    val elemRendered = emitter.renderExpr(repArg, ctx.indent, ctx.selfTypeName)
                    val countRendered = emitter.renderExpr(recvTake.args.first()!!, ctx.indent, ctx.selfTypeName)
                    return "($elemRendered).toString().repeat($countRendered)"
                }
            }
            return "$recvAtom.joinToString(\"\")"
        }
        if (explicitCollectTarget == "List<*>" ||
            effectiveCollectTarget.startsWith("List<") ||
            effectiveCollectTarget.startsWith("MutableList<")
        ) {
            return "$recvAtom.toMutableList()"
        }
        if (isMapRenderedType(effectiveCollectTarget)) {
            return "$recvAtom.toMap(LinkedHashMap())"
        }
        if (isSetRenderedType(effectiveCollectTarget)) {
            return "$recvAtom.toMutableSet()"
        }
        return null
    }
}

private object ContainerMutationAndIndexingRule : PostArgMethodCallRule() {
    override fun tryRender(ctx: MethodCallLoweringContext): String? {
        val emitter = ctx.emitter
        val rawMethod = ctx.rawMethod
        val receiver = ctx.receiver
        val args = ctx.renderedValueArgs
        val noTypeArgs = ctx.typeArgs.isEmpty()
        if (ctx.isVecReceiver && noTypeArgs) {
            when {
                rawMethod == "push" && args.size == 1 -> return "$receiver.add(${args[0]})"
                rawMethod == "insert" && args.size == 2 -> return "$receiver.add(${args[0]}, ${args[1]})"
                rawMethod == "clone_from" && args.size == 1 ->
                    return "run { $receiver.clear(); $receiver.addAll(${args[0]}) }"
                (rawMethod == "get" || rawMethod == "get_mut") &&
                    args.size == 1 &&
                    emitter.unwrapParenOrGroupExpr(ctx.allMethodArgs[0]) !is Expr.Range ->
                    return ctx.renderIndexedGetOrNull(receiver)
            }
        }
        val recvCollectionTy = emitter.currentTypedLocals[receiver] ?: emitter.inferExprRenderedType(ctx.expr.receiver, ctx.selfTypeName)
        if (isMapRenderedType(recvCollectionTy) && noTypeArgs) {
            val recvAtom = ctx.recvAtom
            when {
                rawMethod == "insert" && args.size == 2 -> return "$recvAtom.put(${args[0]}, ${args[1]})"
                rawMethod == "clone" && args.isEmpty() -> return "$recvAtom.toMutableMap()"
                (rawMethod == "get" || rawMethod == "get_mut") && args.size == 1 -> return "$recvAtom.get(${args[0]})"
            }
        }
        if ((rawMethod == "get" || rawMethod == "get_mut") &&
            args.size == 1 &&
            noTypeArgs &&
            emitter.unwrapParenOrGroupExpr(ctx.allMethodArgs[0]) !is Expr.Range &&
            isIndexedSliceOrArrayRenderedType(recvCollectionTy)
        ) {
            return ctx.renderIndexedGetOrNull(ctx.recvAtom)
        }
        if (isSetRenderedType(recvCollectionTy) && noTypeArgs) {
            val recvAtom = ctx.recvAtom
            when {
                rawMethod == "insert" && (args.size == 1 || (args.size == 2 && args[1] == "Unit")) ->
                    return "$recvAtom.add(${args[0]})"
                rawMethod == "clone" && args.isEmpty() ->
                    return "$recvAtom.toMutableSet()"
            }
        }
        if (rawMethod == "clone_from" &&
            args.size == 1 &&
            noTypeArgs &&
            (ctx.expr.receiver is Expr.Field || receiver in emitter.currentNullableLocals)
        ) {
            return "$receiver = ${args[0]}"
        }
        if ((rawMethod == "push" || rawMethod == "extend") && !ctx.isVecReceiver && args.size == 1 && noTypeArgs) {
            val defaultWitness =
                emitter.currentTypeParamWitnessCalls.entries
                    .filter { it.key.second == "default" && it.value !in args }
                    .map { it.value }
                    .distinct()
                    .singleOrNull()
            if (defaultWitness != null) {
                return "$receiver.${ctx.method}(${args[0]}, $defaultWitness)"
            }
        }
        if (rawMethod == "map" && ctx.allMethodArgs.size == 1 && args.size == 1) {
            if (emitter.isTransparentWrapperOrIdentityMapArg(ctx.allMethodArgs[0])) {
                return receiver
            }
            if (noTypeArgs && (receiver.startsWith("ArrayDeque(") || ctx.isDequeIterReceiver)) {
                val mappedArg = emitter.wrapMapCallbackArg(ctx.allMethodArgs[0], args[0])
                return "ArrayDeque(($receiver).map($mappedArg))"
            }
        }
        return null
    }
}

private object StringCaseAndSplitMethodRule : PostArgMethodCallRule() {
    override fun tryRender(ctx: MethodCallLoweringContext): String? {
        if (ctx.typeArgs.isNotEmpty()) return null
        val emitter = ctx.emitter
        val rawMethod = ctx.rawMethod
        val receiver = ctx.receiver
        val args = ctx.renderedValueArgs
        val atomicRecv = ctx.recvAtom
        if ((rawMethod == "strip_prefix" || rawMethod == "strip_suffix") && args.size == 1) {
            val recvTy = ctx.inferredRecvTy
            if (recvTy == null || recvTy == "String") {
                val pred = if (rawMethod == "strip_prefix") "startsWith" else "endsWith"
                val rem = if (rawMethod == "strip_prefix") "removePrefix" else "removeSuffix"
                return "$atomicRecv.takeIf { it.$pred(${args[0]}) }?.$rem(${args[0]})"
            }
        }
        if ((rawMethod == "split_once" || rawMethod == "rsplit_once") && args.size == 1) {
            val recvTy = ctx.localOrInferredRecvTy
            val refInner = ctx.refCellInnerType()
            if (recvTy == null || recvTy == "String" || recvTy == "CharSequence" || refInner == "String") {
                val strExpr = if (refInner == "String") "$atomicRecv.value" else atomicRecv
                val findFn = if (rawMethod == "split_once") "indexOf" else "lastIndexOf"
                val argExpr = ctx.allMethodArgs[0]
                val unwrappedArg = emitter.unwrapParenOrGroupExpr(argExpr)
                val argTy = emitter.inferExprRenderedType(argExpr, ctx.selfTypeName)?.removeSuffix("?")?.trim()
                val isCharPat = (unwrappedArg as? Expr.Lit)?.lit is Lit.Char || argTy == "Char" || argTy == "RustChar"
                return if (isCharPat) {
                    val charVal = if (argTy == "RustChar") "(${args[0]}).toInt().toChar()" else args[0]
                    "$strExpr.let { s -> val idx = s.$findFn($charVal); if (idx < 0) null else Pair(s.substring(0, idx), s.substring(idx + 1)) }"
                } else {
                    "$strExpr.let { s -> val d = ${args[0]}; val idx = s.$findFn(d); if (idx < 0) null else Pair(s.substring(0, idx), s.substring(idx + d.length)) }"
                }
            }
        }
        val instanceMethods = emitter.inspector.instanceMethodsByType.values
        if ((rawMethod == "to_lowercase" || rawMethod == "to_uppercase") && args.isEmpty()) {
            val recvTy = ctx.localOrInferredRecvTy
            val ktFn = if (rawMethod == "to_lowercase") "lowercase" else "uppercase"
            if (ctx.refCellInnerType() == "String") return "$atomicRecv.value.$ktFn()"
            val ktMethodIdent = rustFunctionIdentToKotlin(rawMethod)
            if (recvTy == "String" ||
                recvTy == "CharSequence" ||
                (recvTy == null && instanceMethods.none { ktMethodIdent in it })
            ) {
                return "$atomicRecv.$ktFn()"
            }
        }
        if (rawMethod == "filter_map" &&
            args.size == 1 &&
            instanceMethods.none { "filterMap" in it }
        ) {
            val mappedArg = emitter.wrapMapCallbackArg(ctx.allMethodArgs[0], args[0])
            return "$atomicRecv.mapNotNull($mappedArg)"
        }
        val inferredMethodRecvTy = ctx.inferredRecvTy
        if (rawMethod == "to_vec" && args.isEmpty()) {
            val unwrappedRecvExpr = emitter.unwrapTransparentCastExpr(ctx.expr.receiver)
            val isRangeSliceExpr = unwrappedRecvExpr is Expr.Index && unwrappedRecvExpr.index is Expr.Range
            val isListOrByteSlice =
                inferredMethodRecvTy?.startsWith("List<") == true ||
                    inferredMethodRecvTy?.startsWith("MutableList<") == true ||
                    inferredMethodRecvTy?.startsWith("ArrayList<") == true ||
                    isRangeSliceExpr ||
                    receiver.endsWith(".toBytes()") ||
                    receiver.endsWith(".asBytes()") ||
                    receiver.endsWith(".asSlice()")
            if (isListOrByteSlice) return "$atomicRecv.toMutableList()"
        }
        if ((rawMethod == "as_slice" || rawMethod == "size_hint") &&
            args.isEmpty() &&
            (receiver in emitter.currentDequeLocals || ctx.isDequeIterReceiver || inferredMethodRecvTy?.startsWith("ArrayDeque<") == true)
        ) {
            return if (rawMethod == "as_slice") {
                "$atomicRecv.toList()"
            } else {
                "run { val n = $atomicRecv.size; Pair(n, n) }"
            }
        }
        return null
    }
}

private object AnyReflectionAndUncheckedShiftRule : PostArgMethodCallRule() {
    private val UNCHECKED_SHIFT_INT_KINDS =
        setOf(
            NumericKind.I8,
            NumericKind.I16,
            NumericKind.I32,
            NumericKind.I64,
            NumericKind.U8,
            NumericKind.U16,
            NumericKind.U32,
            NumericKind.U64,
        )

    override fun tryRender(ctx: MethodCallLoweringContext): String? {
        val emitter = ctx.emitter
        val rawMethod = ctx.rawMethod
        val args = ctx.renderedValueArgs
        val inferredMethodRecvTy = ctx.inferredRecvTy
        if (inferredMethodRecvTy == "RustAny" && args.isEmpty() && ctx.typeArgs.isNotEmpty()) {
            val atomicRecv = ctx.recvAtom
            when (rawMethod) {
                "is" -> return "$atomicRecv.isType${ctx.typeArgs}()"
                "downcast_ref" -> return "$atomicRecv.downcastRef${ctx.typeArgs}()"
                "downcast_mut" -> return "$atomicRecv.downcastMut${ctx.typeArgs}()"
                "downcast" -> return "$atomicRecv.downcast${ctx.typeArgs}()"
            }
        }
        if (ctx.typeArgs.isNotEmpty()) return null
        if (rawMethod == "type_id" &&
            args.isEmpty() &&
            "TypeId" !in emitter.inspector.declaredTypeNames &&
            "Any" !in emitter.inspector.declaredTraitsByName
        ) {
            return if (inferredMethodRecvTy == "RustAny") {
                "${ctx.recvAtom}.typeId"
            } else {
                "RustAny.from(${ctx.receiver}).typeId"
            }
        }
        val cleanRecvForCmp =
            inferredMethodRecvTy ?: emitter.inferExprRenderedType(ctx.effectiveReceiverExpr, ctx.selfTypeName)?.removeSuffix("?")?.trim()
        if ((rawMethod == "unchecked_shl" || rawMethod == "unchecked_shr") && args.size == 1) {
            val intKind =
                ctx.recvNumericKindForMethod
                    ?: cleanRecvForCmp?.let(emitter::resolveNumericKind)
                    ?: emitter.inferExprNumericKind(ctx.effectiveReceiverExpr, ctx.selfTypeName)
            if (intKind in UNCHECKED_SHIFT_INT_KINDS) {
                val atomicRecv = ctx.recvAtom
                val op = if (rawMethod == "unchecked_shl") "shl" else "shr"
                val argKind = emitter.inferExprNumericKind(ctx.allMethodArgs[0], ctx.selfTypeName)
                val argText = args[0].trim()
                val shiftAmt =
                    if (argKind == NumericKind.I32 || isDecimalOrHexLiteralDigits(argText)) {
                        argText
                    } else {
                        "${emitter.atomicRecvExpr(ctx.allMethodArgs[0], argText)}.toInt()"
                    }
                return when (intKind) {
                    NumericKind.I8 -> "(($atomicRecv).toInt() $op $shiftAmt).toByte()"
                    NumericKind.I16 -> "(($atomicRecv).toInt() $op $shiftAmt).toShort()"
                    NumericKind.U8 -> "(($atomicRecv).toUInt() $op $shiftAmt).toUByte()"
                    NumericKind.U16 -> "(($atomicRecv).toUInt() $op $shiftAmt).toUShort()"
                    else -> "($atomicRecv $op $shiftAmt)"
                }
            }
        }
        if (args.size == 1) {
            val cmpLowered =
                emitter.tryRenderUnconstrainedCmpLowering(
                    rawMethod = rawMethod,
                    cleanRecvTy = cleanRecvForCmp,
                    recvRendered = ctx.receiver,
                    argRendered = args[0],
                )
            if (cmpLowered != null) return cmpLowered
        }
        return null
    }
}

private object ErasedOptionCombinatorMethodRule : PostArgMethodCallRule() {
    override fun tryRender(ctx: MethodCallLoweringContext): String? {
        val emitter = ctx.emitter
        val receiver = ctx.receiver
        if (ctx.typeArgs.isNotEmpty() ||
            emitter.hasDeclaredOptionEnum() ||
            !emitter.isKnownNullableOptionExpr(ctx.expr.receiver, receiver, ctx.selfTypeName)
        ) {
            return null
        }
        val rawMethod = ctx.rawMethod
        val args = ctx.renderedValueArgs
        if (rawMethod == "take" &&
            args.isEmpty() &&
            receiver.all { it.isLetterOrDigit() || it == '_' || it == '.' } &&
            (ctx.expr.receiver is Expr.Field || (ctx.expr.receiver is Expr.Path && receiver != "cell"))
        ) {
            return "$receiver.also { $receiver = null }"
        }
        val atomicRecv = ctx.recvAtom
        return when {
            rawMethod == "unwrap" && args.isEmpty() -> "$atomicRecv!!"
            rawMethod == "expect" && args.size == 1 -> "($receiver ?: error(${args[0]}))"
            (rawMethod == "clone" || rawMethod == "cloned" || rawMethod == "copied") && args.isEmpty() -> receiver
            (rawMethod == "ok_or" || rawMethod == "ok_or_else") && args.size == 1 -> renderOkOr(ctx)
            rawMethod == "into_iter" && args.isEmpty() -> "ArrayDeque(listOfNotNull($receiver))"
            rawMethod == "flatten" && args.isEmpty() -> receiver
            (rawMethod == "map" || rawMethod == "and_then") && args.size == 1 ->
                "$atomicRecv?.let(${emitter.wrapMapCallbackArg(ctx.allMethodArgs[0], args[0])})"
            rawMethod == "map_or" && args.size == 2 -> {
                val callback = emitter.wrapMapCallbackArg(ctx.allMethodArgs[1], args[1])
                val defaultExpr = ctx.allMethodArgs[0]
                val defaultArg = args[0]
                ctx.renderOptionEagerEvaluation(
                    isEager = !emitter.isSideEffectFreeExpr(defaultExpr),
                    argExpr = defaultExpr,
                    argText = defaultArg,
                    lazyExpr = "($atomicRecv?.let($callback) ?: $defaultArg)",
                    someExpr = { recvTmp -> "$recvTmp.let($callback)" },
                    runtimeIfExpr = { recvTmp, defTmp -> "if ($recvTmp != null) $recvTmp.let($callback) else $defTmp" },
                )
            }
            rawMethod == "map_or_else" && args.size == 2 ->
                "($atomicRecv?.let(${emitter.wrapMapCallbackArg(ctx.allMethodArgs[1], args[1])}) ?: ${emitter.unwrapZeroArgClosureText(args[0])})"
            (rawMethod == "or_else" || rawMethod == "unwrap_or_else") && args.size == 1 -> {
                val renderedClosure = renderWithOptionElemKind(ctx, ctx.allMethodArgs[0], args[0], asTypedInitializer = false)
                "($receiver ?: ${emitter.unwrapZeroArgClosureText(renderedClosure)})"
            }
            (rawMethod == "unwrap_or" || rawMethod == "or") && args.size == 1 -> {
                val argExpr = ctx.allMethodArgs[0]
                val fallbackArg = renderWithOptionElemKind(ctx, argExpr, args[0], asTypedInitializer = rawMethod == "unwrap_or")
                ctx.renderOptionEagerEvaluation(
                    isEager = !emitter.isSideEffectFreeExpr(argExpr),
                    argExpr = argExpr,
                    argText = fallbackArg,
                    lazyExpr = "($receiver ?: $fallbackArg)",
                    someExpr = { recvTmp -> recvTmp },
                    runtimeIfExpr = { recvTmp, defTmp -> "$recvTmp ?: $defTmp" },
                )
            }
            else -> null
        }
    }

    private fun renderOkOr(ctx: MethodCallLoweringContext): String {
        val emitter = ctx.emitter
        val arg0Expr = ctx.allMethodArgs[0]
        val arg0Rendered = ctx.renderedValueArgs[0]
        val errExprText =
            if (ctx.rawMethod == "ok_or_else") {
                emitter.unwrapZeroArgClosureText(arg0Rendered)
            } else {
                arg0Rendered
            }
        val isEager = ctx.rawMethod == "ok_or" && !emitter.isSideEffectFreeExpr(arg0Expr)
        if (!emitter.usesTaggedCoreResult()) {
            return ctx.renderOptionEagerEvaluation(
                isEager = isEager,
                argExpr = arg0Expr,
                argText = errExprText,
                lazyExpr = "(${ctx.recvAtom}?.let { Result.success(it) } ?: Result.failure($errExprText))",
                defaultTmpPrefix = "optErr",
                someExpr = { recvTmp -> "Result.success($recvTmp)" },
                runtimeIfExpr = { recvTmp, errTmp -> "if ($recvTmp != null) Result.success($recvTmp) else Result.failure($errTmp)" },
            )
        }
        val res = emitter.sourceResultName()
        val expectedRes =
            rustResultTypeArguments(emitter.currentExpectedParseTargetType ?: emitter.currentEnclosingReturnRenderedType)
        val okTy =
            ctx.inferredRecvTy
                ?.takeIf { it.isNotEmpty() && it != "*" && it != "Nothing" && it != "Any?" }
                ?: expectedRes?.first
        val rawErrArg =
            if (ctx.rawMethod == "ok_or_else") {
                (emitter.unwrapParenOrGroupExpr(arg0Expr) as? Expr.Closure)?.body ?: arg0Expr
            } else {
                arg0Expr
            }
        val errTy =
            emitter
                .inferExprRenderedType(rawErrArg, ctx.selfTypeName)
                ?.trim()
                ?.takeIf { it.isNotEmpty() && it != "*" && it != "Nothing" && it != "Any?" }
                ?: expectedRes?.second
        val resSuffix = buildExplicitResultTypeArgsSuffix(okTy, errTy)
        return ctx.renderOptionEagerEvaluation(
            isEager = isEager,
            argExpr = arg0Expr,
            argText = errExprText,
            lazyExpr = "(${ctx.recvAtom}?.let { $res.Ok$resSuffix(it) } ?: $res.Err$resSuffix($errExprText))",
            defaultTmpPrefix = "optErr",
            someExpr = { recvTmp -> "$res.Ok$resSuffix($recvTmp)" },
            runtimeIfExpr = { recvTmp, errTmp -> "if ($recvTmp != null) $res.Ok$resSuffix($recvTmp) else $res.Err$resSuffix($errTmp)" },
        )
    }

    private fun renderWithOptionElemKind(
        ctx: MethodCallLoweringContext,
        argExpr: Expr,
        preRendered: String,
        asTypedInitializer: Boolean,
    ): String {
        val emitter = ctx.emitter
        val optionElemKind = ctx.inferredRecvTy?.let(emitter::resolveNumericKind) ?: return preRendered
        if (asTypedInitializer) {
            return emitter.renderTypedNumericInitializer(argExpr, optionElemKind, ctx.indent, ctx.selfTypeName)
        }
        val prevKind = emitter.currentExpectedNumericKind
        emitter.currentExpectedNumericKind = optionElemKind
        return try {
            emitter.renderExpr(argExpr, ctx.indent, ctx.selfTypeName)
        } finally {
            emitter.currentExpectedNumericKind = prevKind
        }
    }
}

private object NullAccumulatorFoldMethodRule : PostArgMethodCallRule() {
    override fun tryRender(ctx: MethodCallLoweringContext): String? {
        if ((ctx.cleanMethod != "fold" && ctx.cleanMethod != "rfold") ||
            ctx.typeArgs.isNotEmpty() ||
            ctx.allMethodArgs.size != 2 ||
            ctx.renderedValueArgs.size != 2 ||
            ctx.renderedValueArgs[0] != "null"
        ) {
            return null
        }
        val emitter = ctx.emitter
        val atomicRecv = ctx.recvAtom
        val closureExpr = ctx.allMethodArgs[1] as? Expr.Closure
        val inferredBodyTy =
            closureExpr
                ?.let { emitter.inferExprRenderedType(it.body, ctx.selfTypeName) }
                ?.removeSuffix("?")
                ?.trim()
                ?.takeIf { it.isNotEmpty() && it != "Nothing" && it != "Any" && !it.startsWith("Pair<Nothing") }
        val inferredRecvElemTy =
            emitter
                .inferExprRenderedType(ctx.effectiveReceiverExpr, ctx.selfTypeName)
                ?.let { emitter.extractIndexedElementRenderedType(it, ctx.selfTypeName) }
                ?.removeSuffix("?")
                ?.trim()
                ?.takeIf { it.isNotEmpty() && it != "Nothing" && it != "Any" }
        val renderedClosure = ctx.renderedValueArgs[1].trim()
        if (renderedClosure.startsWith("{") && renderedClosure.endsWith("}") && "->" in renderedClosure) {
            val innerClosure = renderedClosure.substring(1, renderedClosure.length - 1).trim()
            val header = innerClosure.substringBefore("->").trim()
            val body = innerClosure.substringAfter("->").trim()
            if ('<' !in header && ':' !in header && '{' !in header && '(' !in header) {
                val headerParams = header.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                if (headerParams.size == 2) {
                    val accParam = headerParams[0]
                    val itemParam = headerParams[1]
                    val accUsed = accParam != "_" && KotlinTokenWalker(body).containsIdentifier(accParam)
                    if (!accUsed) {
                        if (body == itemParam) {
                            return if (ctx.cleanMethod == "fold") "$atomicRecv.lastOrNull()" else "$atomicRecv.firstOrNull()"
                        }
                        if (inferredBodyTy == null) {
                            return if (ctx.cleanMethod == "fold") {
                                "$atomicRecv.map({ $itemParam -> $body }).lastOrNull()"
                            } else {
                                "$atomicRecv.asReversed().map({ $itemParam -> $body }).lastOrNull()"
                            }
                        }
                    }
                }
            }
        }
        val accTy = inferredBodyTy ?: inferredRecvElemTy ?: return null
        val typedArgs = (ctx.macroTypeArgs + listOf("null as $accTy?", ctx.renderedValueArgs[1])).joinToString(", ")
        return "${ctx.receiver}.${ctx.method}($typedArgs)"
    }
}

private val POST_ARG_METHOD_RULES: List<PostArgMethodCallRule> =
    listOf(
        PostArgArithmeticAndHashRule,
        CoreStrAndCharMethodRule,
        ConcreteNumericDispatchMethodRule,
        FmtAndCollectionTransformRule,
        ContainerMutationAndIndexingRule,
        StringCaseAndSplitMethodRule,
        AnyReflectionAndUncheckedShiftRule,
        ErasedOptionCombinatorMethodRule,
        NullAccumulatorFoldMethodRule,
    )

private val CORE_SLICE_OR_PTR_METHODS = setOf("asPtr", "asMutPtr", "chunksMut", "chunks")
private val NON_SLICE_CORE_OWNER_TYPES =
    setOf("MaybeUninit", "ConstPtr", "MutPtr", "AddressableLocal", "AddressableArrayLocal")

private fun MethodCallLoweringContext.renderStandardMethodCall(): String {
    val adjustedRenderedValueArgs =
        if ((rawMethod == "peek" || rawMethod == "peek2" || rawMethod == "peek3") &&
            allMethodArgs.size == 1 &&
            allMethodArgs[0] is Expr.Macro &&
            renderedValueArgs.size == 1
        ) {
            listOf(renderedValueArgs[0].removeSuffix(".default()"))
        } else if ((rawMethod == "map" || rawMethod == "map_err" || rawMethod == "and_then" || rawMethod == "or_else") &&
            allMethodArgs.size == 1 &&
            renderedValueArgs.size == 1
        ) {
            listOf(emitter.wrapMapCallbackArg(allMethodArgs[0], renderedValueArgs[0]))
        } else {
            renderedValueArgs
        }
    val inferredRecvOwnerSimple =
        if (emitter.unwrapParenOrGroupExpr(expr.receiver).let { it is Expr.Path && it.path.isIdent("self") }) {
            selfTypeName
        } else {
            emitter.currentTypedLocals[receiver] ?: emitter.inferExprRenderedType(expr.receiver, selfTypeName)
        }?.removeSuffix("?")?.substringBefore('<')?.substringAfterLast('.')?.trim()
    val methodConstEntry =
        inferredRecvOwnerSimple?.let { owner ->
            emitter.inspector.methodConstParamNames["$owner.$method"]
                ?: emitter.headerContext.crateFnConstParamNames["$owner.$method"]
                ?: emitter
                    .scopedAbiKeysForRenderedType("$owner.$method")
                    .firstNotNullOfOrNull { emitter.headerContext.crateFnConstParamNames[it] }
                ?: emitter.headerContext.externalCrateFnConstParams["$owner.$method"]
        }
    val forwardedMethodConstArgs =
        if (explicitMethodConstArgs.isEmpty() &&
            emitter.activeConstParamNames.isNotEmpty() &&
            methodConstEntry != null &&
            methodConstEntry.first == allMethodArgs.size &&
            methodConstEntry.second.all { it in emitter.activeConstParamNames }
        ) {
            methodConstEntry.second
        } else {
            emptyList()
        }
    val valueArgs = macroTypeArgs + adjustedRenderedValueArgs + explicitMethodConstArgs.ifEmpty { forwardedMethodConstArgs }
    val args = valueArgs.joinToString(", ")
    if ("core" in emitter.headerContext.crateNamespaces &&
        typeArgs.isEmpty() &&
        method in CORE_SLICE_OR_PTR_METHODS
    ) {
        val recvCheckExpr =
            (emitter.unwrapParenOrGroupExpr(expr.receiver) as? Expr.Reference)?.let { emitter.unwrapParenOrGroupExpr(it.expr) }
                ?: expr.receiver
        val recvType = emitter.inferExprRenderedType(recvCheckExpr, selfTypeName)?.removeSuffix("?")?.trim()
        val recvOwnerSimple = recvType?.substringBefore('<')?.substringAfterLast('.')?.trim()
        val hasUserMethod = recvOwnerSimple != null && method in emitter.inspector.instanceMethodsByType[recvOwnerSimple].orEmpty()
        val isStorageOrMaybeUninit =
            receiver.startsWith("MaybeUninit.") ||
                unwrapMaybeUninitRenderedInner(recvType) != null ||
                receiver in emitter.currentAddressableLocals.values
        val isNonSliceCoreType = recvOwnerSimple in NON_SLICE_CORE_OWNER_TYPES
        val isPrimitiveArrayReceiver = recvOwnerSimple in PRIMITIVE_ARRAY_TYPES
        val isStringAsPtrReceiver = method == "asPtr" && (recvType == "String" || recvType == "CharSequence")
        val isKnownSliceOrArrayType =
            recvType != null &&
                (
                    recvType.startsWith("List<") ||
                        recvType.startsWith("MutableList<") ||
                        recvType.startsWith("Slice<") ||
                        isPrimitiveArrayReceiver ||
                        isStringAsPtrReceiver ||
                        recvOwnerSimple?.endsWith("Slice") == true
                )
        if (!isStorageOrMaybeUninit && !hasUserMethod && !isNonSliceCoreType && (recvType == null || isKnownSliceOrArrayType)) {
            val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(emitter.headerContext)
            val importPkg =
                when {
                    isPrimitiveArrayReceiver -> "mem"
                    isStringAsPtrReceiver -> "str"
                    else -> "slice"
                }
            if (emitter.headerContext.packageName != "$coreNs.$importPkg") {
                CoreIntrinsicLowering.registerCoreImport(emitter, "$coreNs.$importPkg.$method")
            }
        }
    }
    val callRendered = "$receiver.$method$typeArgs($args)"
    val finalMethodCall = if (method == "zip") "($callRendered)" else callRendered
    return if (methodSubslicePre.isEmpty()) {
        finalMethodCall
    } else {
        val preJoined = methodSubslicePre.joinToString("; ")
        val postJoined = methodSubslicePost.joinToString("; ")
        "run { $preJoined; val subCall0 = $finalMethodCall; $postJoined; subCall0 }"
    }
}

internal fun KotlinJ2KEmitter.renderIntToBytesMethodCall(
    receiver: String,
    sourceReceiver: Expr,
    recvKind: NumericKind,
    rawMethod: String,
    selfTypeName: String? = null,
): String {
    val isBigEndian =
        rawMethod == "to_be_bytes" ||
            (rawMethod == "to_ne_bytes" && activeCfgProfile.targetEndian == "big")
    if (recvKind == NumericKind.U128 || recvKind == NumericKind.I128 || recvKind == NumericKind.F16 || recvKind == NumericKind.F128) {
        val recvAtom = if (isAtomicPostfixReceiver(sourceReceiver, receiver)) receiver else "($receiver)"
        val fn = if (isBigEndian) "toBeBytes" else "toLeBytes"
        return "$recvAtom.$fn()"
    }
    val inferredReceiverType = inferExprRenderedType(sourceReceiver, selfTypeName)
    val ctor = "ubyteArrayOf"
    return when (recvKind) {
        NumericKind.U8, NumericKind.I8 -> {
            val byteExpr =
                if (inferredReceiverType == "UByte") {
                    receiver
                } else {
                    coerceRenderedNumericExpr(sourceReceiver, receiver, NumericKind.U8)
                }
            "$ctor($byteExpr)"
        }
        else -> {
            val rawBitsExpr =
                when (recvKind) {
                    NumericKind.U16, NumericKind.I16 ->
                        coerceRenderedNumericExpr(sourceReceiver, receiver, NumericKind.U32)
                    NumericKind.U32, NumericKind.I32 ->
                        if (inferredReceiverType == "UInt") receiver else coerceRenderedNumericExpr(sourceReceiver, receiver, NumericKind.U32)
                    NumericKind.U64, NumericKind.I64 ->
                        if (inferredReceiverType == "ULong") receiver else coerceRenderedNumericExpr(sourceReceiver, receiver, NumericKind.U64)
                    NumericKind.F32 -> "($receiver).toRawBits().toUInt()"
                    NumericKind.F64 -> "($receiver).toRawBits().toULong()"
                }
            val bitWidths =
                when (recvKind) {
                    NumericKind.U16, NumericKind.I16 -> listOf(8, 0)
                    NumericKind.U32, NumericKind.I32, NumericKind.F32 -> listOf(24, 16, 8, 0)
                    else -> listOf(56, 48, 40, 32, 24, 16, 8, 0)
                }
            val shifts = if (isBigEndian) bitWidths else bitWidths.reversed()
            val elems = shifts.joinToString(", ") { s -> if (s == 0) "rawBits.toUByte()" else "(rawBits shr $s).toUByte()" }
            "run { val rawBits = $rawBitsExpr; $ctor($elems) }"
        }
    }
}

private data class ZippedSliceLeaf(
    val sliceExpr: Expr,
    val rawIdent: String?,
    val patIdent: Ident?,
)

private fun KotlinJ2KEmitter.stripSliceIterMethodCall(expr: Expr): Expr {
    val unwrapped = unwrapParenOrGroupExpr(expr)
    if (unwrapped is Expr.MethodCall &&
        unwrapped.args.isEmpty() &&
        unwrapped.turbofish == null &&
        unwrapped.method.toString().removePrefix("r#") in setOf("iter", "iter_mut", "into_iter")
    ) {
        return unwrapParenOrGroupExpr(unwrapped.receiver)
    }
    return unwrapped
}

private fun unwrapTupleClosurePat(pat: Pat): Pat =
    when (pat) {
        is Pat.Reference -> unwrapTupleClosurePat(pat.pat)
        is Pat.TypeAscription -> unwrapTupleClosurePat(pat.pat)
        else -> pat
    }

private fun KotlinJ2KEmitter.collectZippedSliceLeaves(
    chainExpr: Expr,
    pat: Pat,
    out: MutableList<ZippedSliceLeaf>,
): Boolean {
    val cleanChain = stripSliceIterMethodCall(chainExpr)
    val cleanPat = unwrapTupleClosurePat(pat)
    if (cleanChain is Expr.MethodCall &&
        cleanChain.method.toString().removePrefix("r#") == "zip" &&
        cleanChain.turbofish == null &&
        cleanChain.args.len() == 1 &&
        cleanPat is Pat.Tuple &&
        cleanPat.elems.len() == 2
    ) {
        val patElems = cleanPat.elems.toList()
        val rhsArg = cleanChain.args.first() ?: return false
        return collectZippedSliceLeaves(cleanChain.receiver, patElems[0], out) &&
            collectZippedSliceLeaves(rhsArg, patElems[1], out)
    }
    return when (cleanPat) {
        is Pat.Ident -> {
            if (cleanPat.subpat != null) return false
            out += ZippedSliceLeaf(cleanChain, cleanPat.ident.toString().removePrefix("r#"), cleanPat.ident)
            true
        }
        is Pat.Wild -> {
            out += ZippedSliceLeaf(cleanChain, null, null)
            true
        }
        else -> false
    }
}

private fun KotlinJ2KEmitter.unwrapSingleExprClosureBody(body: Expr): Expr {
    val unwrapped = unwrapParenOrGroupExpr(body)
    if (unwrapped is Expr.BlockExpr && unwrapped.label == null && unwrapped.block.stmts.size == 1) {
        val single = unwrapped.block.stmts[0]
        if (single is Stmt.ExprStmt && single.semiToken == null) {
            return unwrapSingleExprClosureBody(single.expr)
        }
    }
    return unwrapped
}

private fun KotlinJ2KEmitter.extractDerefPathTargetIdent(lhs: Expr): String? {
    val u = unwrapParenOrGroupExpr(lhs)
    if (u is Expr.Unary && u.op is UnOp.Deref) {
        val inner = unwrapParenOrGroupExpr(u.expr)
        if (inner is Expr.Path && inner.qself == null && inner.path.segments.len() == 1) {
            return inner.path.segments
                .first()
                ?.ident
                ?.toString()
                ?.removePrefix("r#")
        }
    }
    return null
}

private fun KotlinJ2KEmitter.tryRenderZippedForEachLoop(
    expr: Expr.MethodCall,
    indent: String,
    selfTypeName: String?,
): String? {
    val rawFirstArg = expr.args.first() ?: return null
    val closure = unwrapParenOrGroupExpr(rawFirstArg) as? Expr.Closure ?: return null
    val closureInputs = closure.inputs.toList()
    if (closureInputs.size != 1) return null
    val leaves = mutableListOf<ZippedSliceLeaf>()
    if (!collectZippedSliceLeaves(expr.receiver, closureInputs[0], leaves) || leaves.size < 2) {
        return null
    }
    val bodyExpr = unwrapSingleExprClosureBody(closure.body)
    val derefAssignTargetRaw: String? =
        when (bodyExpr) {
            is Expr.Assign -> extractDerefPathTargetIdent(bodyExpr.left)
            is Expr.Binary ->
                if (bodyExpr.op is BinOp.AddAssign ||
                    bodyExpr.op is BinOp.SubAssign ||
                    bodyExpr.op is BinOp.MulAssign ||
                    bodyExpr.op is BinOp.DivAssign ||
                    bodyExpr.op is BinOp.RemAssign ||
                    bodyExpr.op is BinOp.BitAndAssign ||
                    bodyExpr.op is BinOp.BitOrAssign ||
                    bodyExpr.op is BinOp.BitXorAssign
                ) {
                    extractDerefPathTargetIdent(bodyExpr.left)
                } else {
                    null
                }
            else -> null
        }
    val leafIdents = leaves.mapNotNull { it.rawIdent }.toSet()
    val mutatesZippedLeaf = derefAssignTargetRaw != null && derefAssignTargetRaw in leafIdents
    if (leaves.size < 3 && !mutatesZippedLeaf) {
        return null
    }
    val prepLines = mutableListOf<String>()
    val sliceRefs =
        leaves.mapIndexed { idx, leaf ->
            val rendered = renderExpr(leaf.sliceExpr, indent, selfTypeName)
            val isReusablePath =
                leaf.sliceExpr is Expr.Path &&
                    leaf.sliceExpr.qself == null &&
                    leaf.sliceExpr.path.segments
                        .len() == 1
            if (isReusablePath) {
                rendered
            } else {
                val tmp = "zipSlice$idx"
                prepLines += "val $tmp = $rendered"
                tmp
            }
        }
    val idxCandidates = listOf("i", "zipIdx", "zipIndex")
    val idxVar =
        idxCandidates.firstOrNull { cand ->
            cand !in leafIdents && scopeBuilder.resolveLocalKotlinName(cand) == null
        } ?: "zipIdx0"
    val bodyIndent = "$indent    "
    scopeBuilder.enterDef()
    val loopLines =
        try {
            val stmts = mutableListOf<String>()
            var targetSliceRef: String? = null
            for ((idx, leaf) in leaves.withIndex()) {
                val raw = leaf.rawIdent ?: continue
                val patIdent = leaf.patIdent ?: continue
                if (mutatesZippedLeaf && raw == derefAssignTargetRaw) {
                    targetSliceRef = sliceRefs[idx]
                    continue
                }
                val ktLocal =
                    scopeBuilder
                        .bindLocal(
                            rawName = raw,
                            baseKotlinName = rustValueIdentToKotlin(raw),
                            span = patIdent.span(),
                            assignCount = AssignCount.AtMostOnce,
                        ).kotlinName
                stmts += "${bodyIndent}val $ktLocal = ${sliceRefs[idx]}[$idxVar]"
            }
            if (mutatesZippedLeaf && targetSliceRef != null) {
                when (bodyExpr) {
                    is Expr.Assign -> {
                        val rhs = renderExpr(bodyExpr.right, bodyIndent, selfTypeName)
                        stmts += "$bodyIndent$targetSliceRef[$idxVar] = $rhs"
                    }
                    is Expr.Binary -> {
                        val opStr =
                            when (bodyExpr.op) {
                                is BinOp.AddAssign -> "+="
                                is BinOp.SubAssign -> "-="
                                is BinOp.MulAssign -> "*="
                                is BinOp.DivAssign -> "/="
                                is BinOp.RemAssign -> "%="
                                is BinOp.BitAndAssign -> "and"
                                is BinOp.BitOrAssign -> "or"
                                is BinOp.BitXorAssign -> "xor"
                                else -> "="
                            }
                        val rhs = renderExpr(bodyExpr.right, bodyIndent, selfTypeName)
                        if (opStr in setOf("and", "or", "xor")) {
                            stmts += "$bodyIndent$targetSliceRef[$idxVar] = $targetSliceRef[$idxVar] $opStr ($rhs)"
                        } else {
                            stmts += "$bodyIndent$targetSliceRef[$idxVar] $opStr $rhs"
                        }
                    }
                    else -> {}
                }
            } else {
                val renderedBody = renderExpr(closure.body, bodyIndent, selfTypeName)
                stmts += "$bodyIndent$renderedBody"
            }
            stmts
        } finally {
            scopeBuilder.exitDef()
        }
    val sizeArgs = sliceRefs.joinToString(", ") { "$it.size" }
    val forBlock =
        buildString {
            append("for (")
            append(idxVar)
            append(" in 0 until minOf(")
            append(sizeArgs)
            append(")) {\n")
            append(loopLines.joinToString("\n"))
            append('\n')
            append(indent)
            append('}')
        }
    return if (prepLines.isEmpty()) {
        forBlock
    } else {
        buildString {
            append("run {\n")
            for (p in prepLines) {
                append(bodyIndent).append(p).append('\n')
            }
            append(bodyIndent).append(forBlock.replace("\n$indent", "\n$bodyIndent")).append('\n')
            append(indent).append('}')
        }
    }
}

private fun KotlinJ2KEmitter.primitiveArrayToMethodName(arrayType: String): String? =
    when (arrayType) {
        "BooleanArray" -> "toBooleanArray"
        "ByteArray" -> "toByteArray"
        "UByteArray" -> "toUByteArray"
        "ShortArray" -> "toShortArray"
        "UShortArray" -> "toUShortArray"
        "IntArray" -> "toIntArray"
        "UIntArray" -> "toUIntArray"
        "LongArray" -> "toLongArray"
        "ULongArray" -> "toULongArray"
        "FloatArray" -> "toFloatArray"
        "DoubleArray" -> "toDoubleArray"
        else -> null
    }

private fun KotlinJ2KEmitter.renderPrimitiveArrayTryIntoConversion(
    innerExpr: Expr,
    targetArrayType: String,
    indent: String,
    selfTypeName: String?,
): String {
    val innerTy = inferExprRenderedType(innerExpr, selfTypeName)?.removeSuffix("?")?.trim()
    val rendered = renderExpr(innerExpr, indent, selfTypeName)
    if (innerTy == targetArrayType) return rendered
    val convFn = primitiveArrayToMethodName(targetArrayType) ?: return rendered
    val atom = if (isAtomicPostfixReceiver(innerExpr, rendered)) rendered else "($rendered)"
    return "$atom.$convFn()"
}

private fun KotlinJ2KEmitter.renderCopyFromSliceCall(
    receiverExpr: Expr,
    rawArgExpr: Expr,
    indent: String,
    selfTypeName: String?,
): String? {
    val unwrappedArg = unwrapParenOrGroupExpr(rawArgExpr)
    val srcExpr = (unwrappedArg as? Expr.Reference)?.let { unwrapParenOrGroupExpr(it.expr) } ?: unwrappedArg
    val recvUnwrapped =
        unwrapParenOrGroupExpr(receiverExpr).let { node ->
            (node as? Expr.Reference)?.let { unwrapParenOrGroupExpr(it.expr) } ?: node
        }
    val rangePlace = recvUnwrapped as? Expr.Index
    val rangeExpr = rangePlace?.let { unwrapParenOrGroupExpr(it.index) as? Expr.Range }
    val dstExpr = if (rangeExpr != null) rangePlace.expr else recvUnwrapped
    val dstTy = inferExprRenderedType(dstExpr, selfTypeName)?.removeSuffix("?")?.trim()
    val dstIsPrimArray = dstTy in PRIMITIVE_ARRAY_TYPES
    val dstIsListLike = dstTy?.startsWith("MutableList<") == true || dstTy?.startsWith("List<") == true
    val dstRendered = renderExpr(dstExpr, indent, selfTypeName)
    val dstAtom = if (isAtomicPostfixReceiver(dstExpr, dstRendered)) dstRendered else "($dstRendered)"
    val prevParseTarget = currentExpectedParseTargetType
    if (dstIsPrimArray) {
        currentExpectedParseTargetType = dstTy
    }
    val srcRendered =
        try {
            renderExpr(srcExpr, indent, selfTypeName)
        } finally {
            currentExpectedParseTargetType = prevParseTarget
        }
    val srcTy =
        inferExprRenderedType(srcExpr, selfTypeName)?.removeSuffix("?")?.trim()
            ?: if (dstIsPrimArray && unwrapParenOrGroupExpr(srcExpr).let { it is Expr.Array || it is Expr.Repeat }) {
                dstTy
            } else {
                null
            }
    val srcIsPrimArray = srcTy in PRIMITIVE_ARRAY_TYPES
    val srcAtom = if (isAtomicPostfixReceiver(srcExpr, srcRendered)) srcRendered else "($srcRendered)"
    if (rangeExpr != null) {
        val startOffset = rangeExpr.start?.let { renderTypedNumericInitializer(it, NumericKind.I32, indent, selfTypeName) } ?: "0"
        if (dstIsPrimArray && srcIsPrimArray) {
            return "$srcAtom.copyInto($dstAtom, destinationOffset = $startOffset)"
        }
        val suffix = closureLabelCounter++
        val dstVar = "dstTmp$suffix"
        val srcVar = "srcTmp$suffix"
        val idxVar = "idxTmp$suffix"
        val destIdx = if (startOffset == "0") idxVar else "($startOffset) + $idxVar"
        return "run { val $dstVar = $dstAtom; val $srcVar = $srcAtom; for ($idxVar in $srcVar.indices) $dstVar[$destIdx] = $srcVar[$idxVar] }"
    }
    if (dstIsPrimArray && srcIsPrimArray) {
        return "$srcAtom.copyInto($dstAtom)"
    }
    if (dstIsPrimArray || dstIsListLike) {
        val suffix = closureLabelCounter++
        val dstVar = "dstTmp$suffix"
        val srcVar = "srcTmp$suffix"
        val idxVar = "idxTmp$suffix"
        return "run { val $dstVar = $dstAtom; val $srcVar = $srcAtom; for ($idxVar in $srcVar.indices) $dstVar[$idxVar] = $srcVar[$idxVar] }"
    }
    return null
}

internal fun KotlinJ2KEmitter.tryRenderUnconstrainedCmpLowering(
    rawMethod: String,
    cleanRecvTy: String?,
    recvRendered: String,
    argRendered: String,
    allowWhenRecvUninferred: Boolean = false,
): String? {
    val recvBase = cleanRecvTy?.substringBefore('<')?.substringAfterLast('.')?.trim()
    val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
    val isCoreRuntimeActive =
        "core" in headerContext.crateNamespaces ||
            headerContext.packageName == coreNs ||
            headerContext.packageName?.startsWith("$coreNs.") == true
    val isCmpMethod =
        rawMethod in
            setOf(
                "partial_cmp",
                "partialCmp",
                "cmp",
                "lt",
                "le",
                "gt",
                "ge",
                "__chaining_less",
                "chainingLess",
                "__chaining_greater",
                "chainingGreater",
            )
    if (!isCoreRuntimeActive ||
        !isCmpMethod ||
        "PartialOrd" in inspector.declaredTypeNames ||
        "Ord" in inspector.declaredTypeNames
    ) {
        return null
    }
    val isEligibleRecv =
        (
            recvBase != null &&
                recvBase in activeTypeParamNames &&
                currentTypeParamWitnessCalls.none { it.key.first == recvBase }
        ) ||
            cleanRecvTy == "Any" ||
            (allowWhenRecvUninferred && cleanRecvTy == null)
    if (!isEligibleRecv) return null

    return when (rawMethod) {
        "partial_cmp", "partialCmp" -> {
            CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.mem.kmPartialCompareValues")
            val ordRef = bindOrQualifyResolvedCrossSymbol("$coreNs.cmp.Ordering")
            "kmPartialCompareValues($recvRendered, $argRendered)?.let { cmpSign -> when { cmpSign < 0 -> $ordRef.Less; cmpSign > 0 -> $ordRef.Greater; else -> $ordRef.Equal } }"
        }
        "cmp" -> {
            CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.mem.kmCompareValues")
            val ordRef = bindOrQualifyResolvedCrossSymbol("$coreNs.cmp.Ordering")
            "kmCompareValues($recvRendered, $argRendered).let { cmpSign -> when { cmpSign < 0 -> $ordRef.Less; cmpSign > 0 -> $ordRef.Greater; else -> $ordRef.Equal } }"
        }
        "lt", "le", "gt", "ge" -> {
            CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.mem.kmPartialCompareValues")
            val cmpOp =
                when (rawMethod) {
                    "lt" -> "< 0"
                    "le" -> "<= 0"
                    "gt" -> "> 0"
                    else -> ">= 0"
                }
            "(kmPartialCompareValues($recvRendered, $argRendered)?.let { cmpSign -> cmpSign $cmpOp } == true)"
        }
        "__chaining_less", "chainingLess", "__chaining_greater", "chainingGreater" -> {
            CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.mem.kmPartialCompareValues")
            val cfRef = bindOrQualifyResolvedCrossSymbol("$coreNs.ops.controlflow.ControlFlow")
            val cmpOp = if (rawMethod == "__chaining_less" || rawMethod == "chainingLess") "< 0" else "> 0"
            "kmPartialCompareValues($recvRendered, $argRendered).let { cmpSign -> if (cmpSign == 0) $cfRef.Continue<Boolean, Unit>(Unit) else $cfRef.Break<Boolean, Unit>(cmpSign != null && cmpSign $cmpOp) }"
        }
        else -> null
    }
}
