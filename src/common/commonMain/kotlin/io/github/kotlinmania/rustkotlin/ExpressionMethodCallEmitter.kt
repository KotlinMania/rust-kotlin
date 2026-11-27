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

private val ITER_RECEIVER_METHODS: Set<String> get() = activeRules().words("iterReceiverMethods")
private val POINTER_DISTANCE_SUB_METHODS: Set<String> get() = activeRules().words("pointerDistanceSubMethods")
private val INDEXED_ITER_UNWRAP_METHODS: Set<String> get() = activeRules().words("indexedIterUnwrapMethods")
private val NUMERIC_RECEIVER_KIND_METHODS: Set<String> get() = activeRules().words("numericReceiverKindMethods")
private val U32_SHIFT_OR_EXP_METHODS: Set<String> get() = activeRules().words("u32ShiftOrExpMethods")

private fun unwrapTrailingIterReceiver(expr: Expr): Expr {
    val mc = expr as? Expr.MethodCall ?: return expr
    val rawName = mc.method.toString().removePrefix("r#")
    return if (mc.args.isEmpty() && mc.turbofish == null && rawName in ITER_RECEIVER_METHODS) mc.receiver else expr
}

private fun KotlinJ2KEmitter.unwrapIndexedCollectionIterReceiver(
    expr: Expr,
    selfTypeName: String?,
): Expr {
    val unwrapped = unwrapTrailingIterReceiver(expr)
    if (unwrapped === expr) return expr
    val isIndexed =
        inferArrayElementNumericKind(unwrapped, selfTypeName) != null ||
            inferExprRenderedType(unwrapped, selfTypeName)?.let { extractIndexedElementRenderedType(it, selfTypeName) } != null ||
            (unwrapped is Expr.Index && unwrapped.index is Expr.Range)
    return if (isIndexed) unwrapped else expr
}

private fun KotlinJ2KEmitter.unwrapRepeatSourceArg(
    expr: Expr,
    indent: String,
    selfTypeName: String?,
): Expr? {
    val call = unwrapParenOrGroupExpr(expr) as? Expr.Call ?: return null
    val callee = KotlinCalleeShape.parse(renderCalleeExpr(call.func, indent, selfTypeName)).erasedBasePath
    val args = call.args.toList().filterNot { it is Expr.Tuple && it.elems.isEmpty() }
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
    val parseRecvTy = (currentTypedLocals[receiver] ?: inferExprRenderedType(expr.receiver, selfTypeName))?.removeSuffix("?")?.trim()
    val parseRecvBase = parseRecvTy?.substringAfterLast('.')?.trim()
    if (parseRecvBase == "ParseStream" || parseRecvBase == "ParseBuffer") return null
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
    val recvTy = (currentTypedLocals[receiver] ?: inferExprRenderedType(expr.receiver, selfTypeName))?.removeSuffix("?")?.trim()
    return when {
        recvTy == "StringBuilder" -> "$receiver.append($arg)"
        recvTy == "String" -> "$receiver += $arg"
        refCellGuardPointeeType(recvTy) == "String" -> "${atomicRecvExpr(expr.receiver, receiver)}.value += $arg"
        else -> null
    }
}

internal fun KotlinJ2KEmitter.isFmtStringBuilderReceiver(
    recvExpr: Expr,
    renderedRecv: String,
    selfTypeName: String?,
): Boolean {
    fun isStdFmtReceiverType(ty: String?): Boolean {
        val clean = ty?.headKotlinTypePath() ?: return false
        val simple = clean.substringAfterLast('.')
        return clean == "StringBuilder" ||
            simple == "StringBuilder" ||
            ((clean == "Formatter" || clean.endsWith(".fmt.Formatter")) && "Formatter" !in inspector.declaredTypeNames)
    }
    val explicitLocalTy = if (renderedRecv.isNotEmpty()) currentTypedLocals[renderedRecv]?.removeSuffix("?")?.trim() else null
    val inferred = inferExprRenderedType(recvExpr, selfTypeName)?.removeSuffix("?")?.trim()
    if (isStdFmtReceiverType(explicitLocalTy) || isStdFmtReceiverType(inferred)) return true
    if (explicitLocalTy != null || inferred != null) return false
    return renderedRecv == "f" || renderedRecv == "fmt" || renderedRecv == "formatter"
}

internal fun KotlinJ2KEmitter.renderExprMethodCall(expr: Expr.MethodCall, indent: String, selfTypeName: String?): String {
    val rawMethod = expr.method.toString()
    val cleanMethod = rawMethod.removePrefix("r#")
    tryRenderPreReceiverMethodCall(expr, rawMethod, cleanMethod, indent, selfTypeName)?.let { return it }

    val methodEntryParseTarget = currentExpectedParseTargetType
    val recvExpectedTarget = computeReceiverExpectedParseTarget(cleanMethod, methodEntryParseTarget)
    val effectiveReceiverExpr =
        if (cleanMethod in INDEXED_ITER_UNWRAP_METHODS) {
            unwrapIndexedCollectionIterReceiver(expr.receiver, selfTypeName)
        } else {
            expr.receiver
        }
    val receiver =
        withExpectedTargetAndKind(recvExpectedTarget, currentExpectedNumericKind) {
            if (cleanMethod == "for_each" && expr.turbofish == null && expr.args.len() == 1) {
                ZippedForEachLoopEmitter(this, indent, selfTypeName).tryRender(expr)?.let { return it }
            }
            if ((cleanMethod == "as_ptr" || cleanMethod == "as_mut_ptr") && expr.args.isEmpty()) {
                sourceArrayLocal(expr.receiver)?.let { (storage, _) ->
                    return if (cleanMethod == "as_mut_ptr") "$storage.elementMutPtr(0)" else "$storage.elementConstPtr(0)"
                }
            }
            val value = renderExpr(effectiveReceiverExpr, indent, selfTypeName)
            if (mutableReferencePointeeType(inferExprRenderedType(effectiveReceiverExpr, selfTypeName)) != null) {
                "($value).read()"
            } else {
                value
            }
        }
    val allMethodArgs =
        expr.args.toList().map { arg ->
            if (cleanMethod == "zip") unwrapIndexedCollectionIterReceiver(arg, selfTypeName) else arg
        }
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

private fun KotlinJ2KEmitter.tryRenderPreReceiverMethodCall(
    expr: Expr.MethodCall,
    rawMethod: String,
    cleanMethod: String,
    indent: String,
    selfTypeName: String?,
): String? {
    val prevRecvParseTarget = currentExpectedParseTargetType
    if (expr.turbofish == null && expr.args.len() == 1 && cleanMethod in POINTER_DISTANCE_SUB_METHODS) {
        tryRenderManagedSlicePointerDistance(expr.receiver, expr.args.toList().first(), cleanMethod, indent, selfTypeName)
            ?.let { return it }
    }
    unwrapTryIntoUnwrapExpr(expr)?.let { innerTryRecv ->
        val tryIntoTargetTy = (prevRecvParseTarget ?: currentEnclosingReturnRenderedType)?.removeSuffix("?")?.trim()
        if (tryIntoTargetTy != null && tryIntoTargetTy in PRIMITIVE_ARRAY_TYPES) {
            return withExpectedTargetAndKind(null, currentExpectedNumericKind) {
                renderPrimitiveArrayTryIntoConversion(innerTryRecv, tryIntoTargetTy, indent, selfTypeName)
            }
        }
    }
    if (cleanMethod == "copy_from_slice" && expr.args.len() == 1 && expr.turbofish == null) {
        val renderedCopy =
            withExpectedTargetAndKind(null, currentExpectedNumericKind) {
                renderCopyFromSliceCall(expr.receiver, expr.args.toList()[0], indent, selfTypeName)
            }
        if (renderedCopy != null && rawMethod.isNotEmpty()) return renderedCopy
    }
    return null
}

private fun KotlinJ2KEmitter.computeReceiverExpectedParseTarget(
    cleanMethod: String,
    methodEntryParseTarget: String?,
): String? =
    when (cleanMethod) {
        "unwrap", "unwrap_unchecked", "expect", "ok" -> {
            val outerTarget = methodEntryParseTarget ?: if (callArgumentNestingDepth == 0) currentEnclosingReturnRenderedType else null
            val cleanOuter = outerTarget?.trim()
            if (usesTaggedCoreResult() &&
                !cleanOuter.isNullOrEmpty() &&
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
    val coreNs: String
        get() = CoreIntrinsicLowering.resolveCoreNamespace(emitter.headerContext)

    val recvAtom: String
        get() = emitter.atomicRecvExpr(expr.receiver, receiver)

    val localOrInferredRecvTy: String?
        get() = (emitter.currentTypedLocals[receiver] ?: emitter.inferExprRenderedType(expr.receiver, selfTypeName))?.removeSuffix("?")?.trim()

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

    fun registerCoreSubpkgImport(subpkg: String, symbol: String) {
        val pkg = "$coreNs.$subpkg"
        if (emitter.headerContext.packageName != pkg) {
            CoreIntrinsicLowering.registerCoreImport(emitter, "$pkg.$symbol")
        }
    }

    fun refCellInnerType(): String? = refCellGuardPointeeType(localOrInferredRecvTy)

    fun derefRefCellOrAtom(): String = if (refCellInnerType() != null) "$recvAtom.value" else recvAtom

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
        if (emitter.isDirectDivergingRenderedExpr(argExpr, argText)) return "run { $receiver; $argText }"
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
                    ) -> if (cleanMethod == "as_mut_ptr") "asMutPtr" else "asPtr"
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
                                    macroTypeArgs +=
                                        if (rawMethod == "parse" && nonUnitArgs.isEmpty() && renderedMac.endsWith(".default()")) {
                                            "${renderedMac.removeSuffix(".default()")}::parse"
                                        } else {
                                            renderedMac
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
                emitter.inferExprRenderedType(zipBase, selfTypeName)?.let { emitter.extractIndexedElementRenderedType(it, selfTypeName) }
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
                recvNumericKindForMethod?.takeIf { it.isIntegerNumericKind() && it != NumericKind.I128 && it != NumericKind.U128 }
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
        val recvOwnerSimple = inferredRecvTy?.simpleKotlinTypeName()
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
                        if (recvOwnerSimple in RAW_PTR_OWNER_NAMES && isStdlibConstPtrMethodParam(rawMethod, null, argIdx)) {
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
        val effSimple = emitter.inferExprRenderedType(effectiveReceiverExpr, selfTypeName)?.removeSuffix("?")?.simpleKotlinTypeName()
        if (effSimple == "NonNull") return true
        val synPath = emitter.sourceInitializerType(effectiveReceiverExpr)?.let(::unwrapSynType) as? SynType.Path ?: return false
        return synPath.path.lastCleanIdent() == "NonNull"
    }

    private fun isThreadCurrentCall(): Boolean {
        val recvCore = emitter.unwrapParenOrGroupExpr(expr.receiver) as? Expr.Call ?: return false
        if (!recvCore.args.isEmpty()) return false
        val fnPath = (emitter.unwrapParenOrGroupExpr(recvCore.func) as? Expr.Path)?.takeIf { it.qself == null } ?: return false
        val segs = fnPath.path.cleanIdentStrings()
        val scope = emitter.scopeBuilder
        return segs == listOf("thread", "current") ||
            segs == listOf("std", "thread", "current") ||
            (segs == listOf("current") && scope.resolveModuleBinding("current")?.importedPath == "std.thread.current")
    }

    private fun matchesReceiverGuard(guard: String): Boolean {
        val headerCtx = emitter.headerContext
        return when (guard) {
            "any", "fmtOrQueue" -> true
            "potentialOption" ->
                !emitter.hasDeclaredOptionEnum() &&
                    !emitter.isKnownNonNullableNonOptionReceiver(expr.receiver, receiver, selfTypeName)
            "nullableOption" ->
                emitter.isKnownNullableOptionExpr(expr.receiver, receiver, selfTypeName)
            "erasedOption" ->
                !emitter.hasDeclaredOptionEnum() &&
                    emitter.isKnownNullableOptionExpr(expr.receiver, receiver, selfTypeName) &&
                    !(rawMethod == "map" && allMethodArgs.size == 1 && emitter.isTransparentWrapperOrIdentityMapArg(allMethodArgs[0]))
            "erasedOptionPlace" ->
                !emitter.hasDeclaredOptionEnum() &&
                    emitter.isKnownNullableOptionExpr(expr.receiver, receiver, selfTypeName) &&
                    receiver.all { it.isLetterOrDigit() || it == '_' || it == '.' } &&
                    (expr.receiver is Expr.Field || (expr.receiver is Expr.Path && receiver != "cell"))
            "dequeIter" -> isDequeIterReceiver
            "dequeOrIter" ->
                receiver in emitter.currentDequeLocals ||
                    isDequeIterReceiver ||
                    inferredRecvTy?.startsWith("ArrayDeque<") == true
            "vec" -> isVecReceiver
            "vecOrMutableList" -> isVecReceiver || localOrInferredRecvTy?.startsWith("MutableList<") == true
            "nonVecFieldOrNullableLocal" ->
                !isVecReceiver && (expr.receiver is Expr.Field || receiver in emitter.currentNullableLocals)
            "indexedSliceOrArray" -> {
                val collTy = emitter.currentTypedLocals[receiver] ?: emitter.inferExprRenderedType(expr.receiver, selfTypeName)
                isIndexedSliceOrArrayRenderedType(collTy)
            }
            "map" -> {
                val collTy = emitter.currentTypedLocals[receiver] ?: emitter.inferExprRenderedType(expr.receiver, selfTypeName)
                isMapRenderedType(collTy)
            }
            "set" -> {
                val collTy = emitter.currentTypedLocals[receiver] ?: emitter.inferExprRenderedType(expr.receiver, selfTypeName)
                isSetRenderedType(collTy)
            }
            "charsIter" -> {
                val recvTy = localOrInferredRecvTy
                "core" in headerCtx.crateNamespaces &&
                    (recvTy == "Chars" || recvTy?.endsWith(".Chars") == true) &&
                    !(headerCtx.packageName == "$coreNs.str.iter" && selfTypeName == "Chars")
            }
            "fmtBuilder" -> emitter.isFmtStringBuilderReceiver(expr.receiver, receiver, selfTypeName)
            "stringOrUninferred" -> inferredRecvTy == null || inferredRecvTy == "String"
            "stringOrStrLit" ->
                inferredRecvTy == "String" ||
                    emitter.unwrapParenOrGroupExpr(expr.receiver).let { it is Expr.Lit && it.lit is Lit.Str }
            "stringOrCharSeq" -> {
                val ty = localOrInferredRecvTy
                ty == "String" || ty == "CharSequence"
            }
            "uninferredRecv" -> localOrInferredRecvTy == null
            "charOrCharLit" ->
                localOrInferredRecvTy == "Char" ||
                    (emitter.unwrapParenOrGroupExpr(expr.receiver) as? Expr.Lit)?.lit is Lit.Char
            "assignableStringPath" ->
                localOrInferredRecvTy == "String" &&
                    receiver.all { it.isLetterOrDigit() || it == '_' || it == '.' }
            "stringSplitRecv" -> {
                val ty = localOrInferredRecvTy
                ty == null || ty == "String" || ty == "CharSequence" || refCellInnerType() == "String"
            }
            "listOrByteSlice" -> {
                val unwrappedRecvExpr = emitter.unwrapTransparentCastExpr(expr.receiver)
                val isRangeSliceExpr = unwrappedRecvExpr is Expr.Index && unwrappedRecvExpr.index is Expr.Range
                val ty = inferredRecvTy
                ty?.startsWith("List<") == true ||
                    ty?.startsWith("MutableList<") == true ||
                    ty?.startsWith("ArrayList<") == true ||
                    isRangeSliceExpr ||
                    receiver.endsWith(".toBytes()") ||
                    receiver.endsWith(".asBytes()") ||
                    receiver.endsWith(".asSlice()")
            }
            "rustAny" -> inferredRecvTy == "RustAny"
            "intNumeric" -> emitter.inferExprNumericKind(expr.receiver, selfTypeName)?.isIntegerNumericKind() == true
            "stringLikeOrRefCell" -> (refCellInnerType() ?: localOrInferredRecvTy)?.let { it in STRING_LIKE_TYPES } == true
            "sizedCollectionOrRefCell" ->
                (refCellInnerType() ?: localOrInferredRecvTy)?.let { ty ->
                    ty in PRIMITIVE_ARRAY_TYPES || SIZED_COLLECTION_PREFIXES.any(ty::startsWith)
                } == true
            "arrayElemKind" -> emitter.inferArrayElementNumericKind(expr.receiver, selfTypeName) != null
            "refCellGuard" -> refCellInnerType() != null
            "refCellString" -> refCellInnerType() == "String"
            "stringBytesOrUninferred" -> {
                val ty = refCellInnerType() ?: localOrInferredRecvTy
                ty == null || ty in STRING_LIKE_TYPES
            }
            "plainStringOrUnboundAsStr" -> {
                val recvTy = localOrInferredRecvTy
                val methodSets = emitter.inspector.instanceMethodsByType
                val crateFns = emitter.headerContext.crateFnReturnTypes
                recvTy == "String" ||
                    (recvTy == null && methodSets.values.none { "asStr" in it } && crateFns.keys.none { it.endsWith(".asStr") })
            }
            "intoSubstOrStrOrList" -> {
                val recvTy = localOrInferredRecvTy
                receiver in emitter.currentIntoSubstitutedParams ||
                    recvTy == "String" ||
                    recvTy?.startsWith("List<") == true ||
                    recvTy?.startsWith("MutableList<") == true
            }
            "intoSubstParam" -> receiver in emitter.currentIntoSubstitutedParams
            "threadCurrent" ->
                receiver == "thread.current()" || receiver.endsWith(".thread.current()") || isThreadCurrentCall()
            "sharedBorrowRecv" -> {
                val unwrappedRecv = emitter.unwrapParenOrGroupExpr(expr.receiver)
                (unwrappedRecv is Expr.Reference && unwrappedRecv.mutability == null) ||
                    (
                        emitter.currentReceiverIsSharedBorrow &&
                            unwrappedRecv is Expr.Path &&
                            unwrappedRecv.qself == null &&
                            unwrappedRecv.path.isIdent("self")
                    )
            }
            else -> throw IllegalArgumentException("Unknown method receiver guard '$guard'")
        }
    }

    private fun matchesArgGuard(guard: String): Boolean =
        when (guard) {
            "arg0FmtBuilder" ->
                allMethodArgs.isNotEmpty() &&
                    renderedValueArgs.isNotEmpty() &&
                    emitter.isFmtStringBuilderReceiver(allMethodArgs[0], renderedValueArgs[0], selfTypeName)
            "nonRangeArg0" ->
                allMethodArgs.isNotEmpty() && emitter.unwrapParenOrGroupExpr(allMethodArgs[0]) !is Expr.Range
            "setInsertArgs" ->
                renderedValueArgs.size == 1 || (renderedValueArgs.size == 2 && renderedValueArgs[1] == "Unit")
            "charPatArg0" -> {
                val argExpr = allMethodArgs.firstOrNull() ?: return false
                val argTy = emitter.inferExprRenderedType(argExpr, selfTypeName)?.removeSuffix("?")?.trim()
                (emitter.unwrapParenOrGroupExpr(argExpr) as? Expr.Lit)?.lit is Lit.Char || argTy == "Char" || argTy == "RustChar"
            }
            else -> throw IllegalArgumentException("Unknown method argGuard '$guard'")
        }

    fun tryRenderDeclarativeMethodRules(
        rulesByName: Map<String, List<DeclarativeMethodPatternRule>>,
        preArg: Boolean,
    ): String? {
        val candidates = rulesByName[rawMethod] ?: return null
        val argCount = if (preArg) nonUnitArgs.size else renderedValueArgs.size
        val hasTurbofish = if (preArg) expr.turbofish != null else typeArgs.isNotEmpty()
        val currentPkg = emitter.headerContext.packageName
        val currentFn = emitter.currentEnclosingFunctionName
        val currentPkgAndFn = if (currentPkg != null && currentFn != null) "$currentPkg#$currentFn" else null
        val matched =
            candidates.firstOrNull { rule ->
                rule.matches(
                    rawMethod = rawMethod,
                    argCount = argCount,
                    hasTurbofish = hasTurbofish,
                    hasCoreCrate = "core" in emitter.headerContext.crateNamespaces,
                    rawAbiMode = emitter.headerContext.rawAbiMode,
                    coreNs = coreNs,
                    currentPkgAndFn = currentPkgAndFn,
                    declaredTypeNames = emitter.inspector.declaredTypeNames,
                    declaredTraitsByName = emitter.inspector.declaredTraitsByName.keys,
                    declaredTopLevelFnRawNames = emitter.inspector.declaredTopLevelFnRawNames,
                    instanceMethodSets = emitter.inspector.instanceMethodsByType.values,
                    argPredicate = ::matchesArgGuard,
                    receiverPredicate = ::matchesReceiverGuard,
                )
            } ?: return null
        return matched.action.render(
            rawAbi = emitter.headerContext.rawAbiMode,
            finalClasses = emitter.headerContext.finalClasses,
            onImport = { CoreIntrinsicLowering.registerCoreImport(emitter, it) },
            onSideEffect = { effect ->
                when (effect) {
                    "usesCurrentThreadToken" -> emitter.usesCurrentThreadToken = true
                    else -> throw IllegalArgumentException("Unknown method sideEffect '$effect'")
                }
            },
        ) { key ->
            when (key) {
                "recv" -> receiver
                "recvAtom" -> recvAtom
                "derefRecv" -> derefRefCellOrAtom()
                "derefStrRecv" -> if (refCellInnerType() == "String") "$recvAtom.value" else recvAtom
                "typeArgs" -> if (preArg) "" else typeArgs
                "coreNs" -> coreNs
                "arg0" -> if (preArg) null else renderedValueArgs.getOrNull(0)
                "arg1" -> if (preArg) null else renderedValueArgs.getOrNull(1)
                "arg0U32" ->
                    if (preArg) {
                        null
                    } else {
                        allMethodArgs.getOrNull(0)?.let { argExpr ->
                            renderedValueArgs.getOrNull(0)?.let {
                                emitter.coerceNumericArgIfNeeded(argExpr, it, NumericKind.U32, selfTypeName)
                            }
                        }
                    }
                "idx0" ->
                    if (preArg) {
                        null
                    } else {
                        allMethodArgs.getOrNull(0)?.let { indexExpr ->
                            renderedValueArgs.getOrNull(0)?.let { renderedIdx ->
                                val idxKind = emitter.inferExprNumericKind(indexExpr, selfTypeName)
                                if (idxKind != null && idxKind != NumericKind.I32) {
                                    emitter.normalizeArrayLengthToIntExpr(renderedIdx, indexExpr, selfTypeName)
                                } else {
                                    renderedIdx
                                }
                            }
                        }
                    }
                "charArg0" ->
                    if (preArg) {
                        null
                    } else {
                        allMethodArgs.getOrNull(0)?.let { argExpr ->
                            renderedValueArgs.getOrNull(0)?.let { renderedArg ->
                                val argTy = emitter.inferExprRenderedType(argExpr, selfTypeName)?.removeSuffix("?")?.trim()
                                if (argTy == "RustChar") "($renderedArg).toInt().toChar()" else renderedArg
                            }
                        }
                    }
                "cb0" ->
                    if (preArg) {
                        null
                    } else {
                        allMethodArgs.getOrNull(0)?.let { argExpr ->
                            renderedValueArgs.getOrNull(0)?.let { emitter.wrapMapCallbackArg(argExpr, it) }
                        }
                    }
                "cb1" ->
                    if (preArg) {
                        null
                    } else {
                        allMethodArgs.getOrNull(1)?.let { argExpr ->
                            renderedValueArgs.getOrNull(1)?.let { emitter.wrapMapCallbackArg(argExpr, it) }
                        }
                    }
                "zeroClosure0" -> if (preArg) null else renderedValueArgs.getOrNull(0)?.let(emitter::unwrapZeroArgClosureText)
                else -> null
            }
        }
    }

    companion object {
        private val ATOMIC_SINGLE_VALUE_METHODS: Set<String>
            get() = activeRules().words("atomicSingleValueMethods")
        private val RAW_PTR_OWNER_NAMES = wordSet("MutPtr ConstPtr")
        private val STRING_LIKE_TYPES: Set<String>
            get() = activeRules().words("stringLikeTypes")
        private val SIZED_COLLECTION_PREFIXES: List<String>
            get() = activeRules().stringList("sizedCollectionPrefixes")
    }
}

private abstract class MethodLoweringRule {
    abstract fun tryRender(ctx: MethodCallLoweringContext): String?
}

private object RawPointerMethodCallRule : MethodLoweringRule() {
    override fun tryRender(ctx: MethodCallLoweringContext): String? {
        val emitter = ctx.emitter
        val expr = ctx.expr
        val rawMethod = ctx.rawMethod
        val receiver = ctx.receiver
        val nonUnitArgs = ctx.nonUnitArgs
        val recvRawPtrRenderedTy = emitter.inferExprRenderedType(expr.receiver, ctx.selfTypeName)
        val sourcePointee = rawPointerPointeeType(recvRawPtrRenderedTy)
        if (sourcePointee != null) {
            val ptrPkg = "${ctx.coreNs}.ptr"
            val ptrCtor = if (isMutRawPointerRenderedType(recvRawPtrRenderedTy)) "MutPtr" else "ConstPtr"
            if (nonUnitArgs.size == 1 && rawMethod in RAW_POINTER_ARITH_METHODS) {
                CoreIntrinsicLowering.registerCoreImport(emitter, "$ptrPkg.$ptrCtor")
                val argument = emitter.renderExpr(nonUnitArgs.single(), ctx.indent, ctx.selfTypeName)
                val deltaSign = if (rawMethod.endsWith("sub")) "-" else "+"
                val byteDelta =
                    if ("byte_" in rawMethod) {
                        "($argument).toLong()"
                    } else {
                        "($argument).toLong() * (${emitter.resolveRawPointerPointeeLayout(expr.receiver, ctx.selfTypeName)}).strideBytes"
                    }
                return "$ptrCtor(($receiver).rawAddress $deltaSign ($byteDelta))"
            }
            if (rawMethod == "cast" && nonUnitArgs.isEmpty()) {
                val destination = expr.firstTurbofishTypeArg()
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
                if (sourcePointee == destinationPointee) return receiver
                CoreIntrinsicLowering.registerCoreImport(emitter, "$ptrPkg.$ptrCtor")
                return "$ptrCtor<$destinationPointee>(($receiver).rawAddress)"
            }
            val ptrRule = activeRules().rawPtrMethodRulesByName[rawMethod]
            if (ptrRule != null && ptrRule.matches(rawMethod, nonUnitArgs.size, expr.turbofish != null) { true }) {
                var cachedLayout: String? = null
                val renderedArgs = arrayOfNulls<String>(nonUnitArgs.size)
                return ptrRule.action.render(
                    rawAbi = emitter.headerContext.rawAbiMode,
                    onImport = { CoreIntrinsicLowering.registerCoreImport(emitter, it) },
                ) { key ->
                    when (key) {
                        "recv" -> receiver
                        "coreNs" -> ctx.coreNs
                        "ptrCtor" -> ptrCtor
                        "pointee" -> sourcePointee
                        "pointeeLayout" ->
                            cachedLayout ?: emitter.resolveRawPointerPointeeLayout(expr.receiver, ctx.selfTypeName).also { cachedLayout = it }
                        "arg0" ->
                            renderedArgs[0] ?: emitter.renderExpr(nonUnitArgs[0], ctx.indent, ctx.selfTypeName).also { renderedArgs[0] = it }
                        "arg1" ->
                            renderedArgs[1] ?: emitter.renderExpr(nonUnitArgs[1], ctx.indent, ctx.selfTypeName).also { renderedArgs[1] = it }
                        else -> null
                    }
                }
            }
        }
        return null
    }
}

private object EarlyPrimitiveAndBitsMethodRule : MethodLoweringRule() {
    override fun tryRender(ctx: MethodCallLoweringContext): String? {
        if (ctx.nonUnitArgs.isNotEmpty() || ctx.expr.turbofish != null) return null
        val emitter = ctx.emitter
        val expr = ctx.expr
        val rawMethod = ctx.rawMethod
        val receiver = ctx.receiver
        val recvKind = emitter.inferExprNumericKind(expr.receiver, ctx.selfTypeName) ?: return null
        if (rawMethod == "to_bits") {
            emitter.renderFloatToBitsExpr(expr.receiver, receiver, recvKind)?.let { return it }
        }
        if (rawMethod == "to_be_bytes" || rawMethod == "to_le_bytes" || rawMethod == "to_ne_bytes") {
            return emitter.renderIntToBytesMethodCall(receiver, expr.receiver, recvKind, rawMethod, ctx.selfTypeName)
        }
        if (rawMethod in ENDIAN_SWAP_METHODS && recvKind.isIntegerNumericKind()) {
            emitter
                .tryRender128BitEndianMethodCall(rawMethod, recvKind, expr.receiver, receiver, guardSelfRecursion = false)
                ?.let { return it }
            if (recvKind != NumericKind.U128 && recvKind != NumericKind.I128) {
                return if (rawMethod == "to_le" || rawMethod == "from_le" || recvKind == NumericKind.I8 || recvKind == NumericKind.U8) {
                    receiver
                } else {
                    renderPrimitiveByteSwapExpr(receiver, recvKind)
                }
            }
        }
        return null
    }

    private val ENDIAN_SWAP_METHODS: Set<String> get() = activeRules().words("endianIntMethods")
}

private object DeclarativePreArgMethodRule : MethodLoweringRule() {
    override fun tryRender(ctx: MethodCallLoweringContext): String? =
        ctx.tryRenderDeclarativeMethodRules(activeRules().preArgMethodRulesByName, preArg = true)
}

private object SveIntoMethodRule : MethodLoweringRule() {
    override fun tryRender(ctx: MethodCallLoweringContext): String? {
        if (ctx.rawMethod != "sve_into" || ctx.nonUnitArgs.isNotEmpty() || ctx.expr.turbofish != null) return null
        val emitter = ctx.emitter
        val recvSimple = ctx.inferredRecvTy?.simpleKotlinTypeName()
        val targetRaw =
            ctx.methodEntryParseTarget
                ?: if (emitter.callArgumentNestingDepth == 0) emitter.currentEnclosingReturnRenderedType else null
        val targetSimple = targetRaw?.removeSuffix("?")?.simpleKotlinTypeName()
        if (recvSimple != null && targetSimple != null) {
            if (recvSimple == targetSimple) return ctx.receiver
            if (recvSimple == "SvboolT" && (targetSimple == "Svbool4T" || targetSimple == "Svbool8T")) {
                return "${ctx.receiver}.sveInto$targetSimple()"
            }
        }
        return null
    }
}

private object TryIntoStdlibIntegerMethodRule : MethodLoweringRule() {
    private val STDLIB_INT_TYPES: Set<String> get() = activeRules().words("stdlibIntTypes")
    private val FROM_ONLY_TARGETS_BY_SRC: Map<String, Set<String>>
        get() = activeRules().tryIntoFromOnlyTargetsBySrc

    override fun tryRender(ctx: MethodCallLoweringContext): String? {
        val emitter = ctx.emitter
        val headerCtx = emitter.headerContext
        val coreNs = ctx.coreNs
        if (ctx.cleanMethod != "try_into" ||
            ctx.nonUnitArgs.isNotEmpty() ||
            !emitter.usesTaggedCoreResult() ||
            !("core" in headerCtx.crateNamespaces || headerCtx.packageName?.startsWith(coreNs) == true)
        ) {
            return null
        }
        fun stripStdlibType(raw: String?): String? = raw?.removeSuffix("?")?.removePrefix("kotlin.")?.trim()

        val srcSimple = ctx.inferredRecvTy?.removePrefix("kotlin.")?.trim()
        val explicitDst = ctx.expr.firstTurbofishTypeArg()?.let { stripStdlibType(emitter.renderType(it, ctx.selfTypeName)) }
        val dstSimple =
            explicitDst
                ?: stripStdlibType(rustResultTypeArguments(ctx.methodEntryParseTarget)?.first)
                ?: if (ctx.methodEntryParseTarget == null && emitter.callArgumentNestingDepth == 0) {
                    stripStdlibType(emitter.currentEnclosingReturnRenderedType?.let { rustResultTypeArguments(it)?.first ?: it })
                } else {
                    null
                }
        if (srcSimple == null || dstSimple == null || srcSimple !in STDLIB_INT_TYPES || dstSimple !in STDLIB_INT_TYPES) {
            return null
        }
        val dstOwner = if (dstSimple == "I128" || dstSimple == "U128") dstSimple else emitter.kotlinBuiltinTypeName(dstSimple)
        val resName = emitter.sourceResultName()
        if (dstSimple in FROM_ONLY_TARGETS_BY_SRC[srcSimple].orEmpty()) {
            ctx.registerCoreSubpkgImport("convert.num", "from")
            return "$resName.Ok<$dstOwner, kotlin.Nothing>($dstOwner.from(${ctx.receiver}))"
        }
        if (srcSimple == dstSimple) {
            val srcRaw = (emitter.sourceInitializerType(ctx.expr.receiver)?.let(::unwrapSynType) as? SynType.Path)?.path?.lastIdentText()
            val dstRaw = (emitter.currentEnclosingReturnSynType?.let(::unwrapSynType) as? SynType.Path)?.path?.lastIdentText()
            if (srcSimple == "Int" &&
                ((srcRaw == "usize" && (dstRaw == "i32" || dstRaw == "isize")) || (dstRaw == "usize" && (srcRaw == "i32" || srcRaw == "isize")))
            ) {
                ctx.registerCoreSubpkgImport("convert.num", "tryFrom")
                return "$dstOwner.tryFrom(${ctx.receiver})"
            }
            return "$resName.Ok<$dstOwner, kotlin.Nothing>(${ctx.receiver})"
        }
        ctx.registerCoreSubpkgImport("convert.num", "tryFrom")
        return "$dstOwner.tryFrom(${ctx.receiver})"
    }
}

private object EarlyIntoIterAndCloneWitnessRule : MethodLoweringRule() {
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
                            INTO_ITER_COLLECTION_PREFIXES.any(recvTy::startsWith)
                    )
                ) {
                    return "ArrayDeque(($receiver).toList())"
                }
            }
        }
        if (ctx.rawMethod == "clone") {
            emitter.currentTypedLocals[receiver]?.let { recvTy ->
                emitter.currentTypeParamWitnessCalls[recvTy to "clone"]?.let { return "$it($receiver)" }
            }
        }
        return null
    }

    private val INTO_ITER_COLLECTION_PREFIXES: List<String>
        get() = activeRules().stringList("intoIterCollectionPrefixes")
}

private object SynAndStringParseMethodRule : MethodLoweringRule() {
    override fun tryRender(ctx: MethodCallLoweringContext): String? {
        if (ctx.rawMethod != "parse" || ctx.nonUnitArgs.isNotEmpty()) return null
        val emitter = ctx.emitter
        val expr = ctx.expr
        val receiver = ctx.receiver
        val turbofish = expr.turbofish
        if (turbofish != null && turbofish.args.size == 1) {
            val tfType = expr.firstTurbofishTypeArg() ?: return null
            val renderedTfTy = emitter.renderType(tfType, ctx.selfTypeName).trim()
            emitter.tryRenderStringPrimitiveParse(expr, receiver, renderedTfTy, ctx.selfTypeName)?.let { return it }
            if (renderedTfTy.endsWith("?")) {
                return tryRenderOptionalSynParse(ctx, renderedTfTy)
            }
            val localRecvTy = ctx.localOrInferredRecvTy
            val recvTyBase = localRecvTy?.substringAfterLast('.')?.trim()
            if ((recvTyBase == "ParseStream" || recvTyBase == "ParseBuffer") && isConcreteSynParseTarget(emitter, renderedTfTy)) {
                return "$receiver.parse($renderedTfTy::parse)"
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
                        .mapNotNull { if (it.key.second == "parse") it.value else null }
                        .distinct()
                        .singleOrNull()
            if (matchedParseWitness != null) return "$receiver.parse($matchedParseWitness)"
            val concreteTarget = nonNullParseTarget?.takeIf { isConcreteSynParseTarget(emitter, it) }
            if (concreteTarget != null) return "$receiver.parse($concreteTarget::parse)"
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

private val RECEIVER_FIRST_METHOD_RULES: List<MethodLoweringRule> =
    listOf(
        RawPointerMethodCallRule,
        EarlyPrimitiveAndBitsMethodRule,
        DeclarativePreArgMethodRule,
        SveIntoMethodRule,
        TryIntoStdlibIntegerMethodRule,
        EarlyIntoIterAndCloneWitnessRule,
        SynAndStringParseMethodRule,
    )

private object PostArgArithmeticAndHashRule : MethodLoweringRule() {
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

private object ConcreteNumericDispatchMethodRule : MethodLoweringRule() {
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

private object DeclarativePostArgMethodRule : MethodLoweringRule() {
    override fun tryRender(ctx: MethodCallLoweringContext): String? =
        ctx.tryRenderDeclarativeMethodRules(activeRules().postArgMethodRulesByName, preArg = false)
}

private object FmtAndCollectionTransformRule : MethodLoweringRule() {
    private val RAW_ABI_BYTE_OFFSET_METHODS: Set<String>
        get() = activeRules().words("rawAbiByteOffsetMethods")

    override fun tryRender(ctx: MethodCallLoweringContext): String? {
        if (ctx.rawMethod == "collect" && ctx.renderedValueArgs.isEmpty()) return tryRenderCollect(ctx)
        if (ctx.typeArgs.isNotEmpty()) return null
        val emitter = ctx.emitter
        val expr = ctx.expr
        val rawMethod = ctx.rawMethod
        val receiver = ctx.receiver
        val args = ctx.renderedValueArgs
        if (rawMethod == "take" && args.size == 1) {
            emitter.unwrapRepeatSourceArg(expr.receiver, ctx.indent, ctx.selfTypeName)?.let { repArg ->
                return "List(${args[0]}) { ${emitter.renderExpr(repArg, ctx.indent, ctx.selfTypeName)} }"
            }
        }
        if ((rawMethod == "push_str" || (rawMethod == "push" && !ctx.isVecReceiver)) && args.size == 1) {
            emitter.tryRenderStringAppendMethod(expr, receiver, args[0], ctx.selfTypeName)?.let { return it }
        }
        if (rawMethod in RAW_ABI_BYTE_OFFSET_METHODS && args.size == 1 && emitter.headerContext.rawAbiMode) {
            val deltaSign = if (rawMethod.endsWith("sub")) "-" else "+"
            val deltaRecv = emitter.atomicRecvExpr(ctx.allMethodArgs[0], args[0])
            val deltaExpr = emitter.convertNumericReceiver(deltaRecv, "Long", emitter.inferExprNumericKind(ctx.allMethodArgs[0], ctx.selfTypeName))
            return "($receiver $deltaSign $deltaExpr)"
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
        if (explicitCollectTarget == "List<*>" || effectiveCollectTarget.startsWith("List<") || effectiveCollectTarget.startsWith("MutableList<")) {
            return "$recvAtom.toMutableList()"
        }
        if (isMapRenderedType(effectiveCollectTarget)) return "$recvAtom.toMap(LinkedHashMap())"
        if (isSetRenderedType(effectiveCollectTarget)) return "$recvAtom.toMutableSet()"
        return null
    }
}

private object DefaultWitnessAndDequeMapMethodRule : MethodLoweringRule() {
    override fun tryRender(ctx: MethodCallLoweringContext): String? {
        val emitter = ctx.emitter
        val rawMethod = ctx.rawMethod
        val receiver = ctx.receiver
        val args = ctx.renderedValueArgs
        val noTypeArgs = ctx.typeArgs.isEmpty()
        if ((rawMethod == "push" || rawMethod == "extend") && !ctx.isVecReceiver && args.size == 1 && noTypeArgs) {
            val defaultWitness =
                emitter.currentTypeParamWitnessCalls.entries
                    .mapNotNull { if (it.key.second == "default" && it.value !in args) it.value else null }
                    .distinct()
                    .singleOrNull()
            if (defaultWitness != null) return "$receiver.${ctx.method}(${args[0]}, $defaultWitness)"
        }
        if (rawMethod == "map" && ctx.allMethodArgs.size == 1 && args.size == 1) {
            if (emitter.isTransparentWrapperOrIdentityMapArg(ctx.allMethodArgs[0])) return receiver
            if (noTypeArgs && (receiver.startsWith("ArrayDeque(") || ctx.isDequeIterReceiver)) {
                return "ArrayDeque(($receiver).map(${emitter.wrapMapCallbackArg(ctx.allMethodArgs[0], args[0])}))"
            }
        }
        return null
    }
}

private object UncheckedShiftAndUnconstrainedCmpRule : MethodLoweringRule() {
    override fun tryRender(ctx: MethodCallLoweringContext): String? {
        if (ctx.typeArgs.isNotEmpty() || ctx.renderedValueArgs.size != 1) return null
        val emitter = ctx.emitter
        val rawMethod = ctx.rawMethod
        val args = ctx.renderedValueArgs
        val cleanRecvForCmp =
            ctx.inferredRecvTy ?: emitter.inferExprRenderedType(ctx.effectiveReceiverExpr, ctx.selfTypeName)?.removeSuffix("?")?.trim()
        if (rawMethod == "unchecked_shl" || rawMethod == "unchecked_shr") {
            val intKind =
                ctx.recvNumericKindForMethod
                    ?: cleanRecvForCmp?.let(emitter::resolveNumericKind)
                    ?: emitter.inferExprNumericKind(ctx.effectiveReceiverExpr, ctx.selfTypeName)
            if (intKind != null && intKind.isIntegerNumericKind() && intKind != NumericKind.I128 && intKind != NumericKind.U128) {
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
        return emitter.tryRenderUnconstrainedCmpLowering(rawMethod, cleanRecvForCmp, ctx.receiver, args[0])
    }
}

private object ErasedOptionCombinatorMethodRule : MethodLoweringRule() {
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
        val atomicRecv = ctx.recvAtom
        return when {
            (rawMethod == "ok_or" || rawMethod == "ok_or_else") && args.size == 1 -> renderOkOr(ctx)
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
        val errExprText = if (ctx.rawMethod == "ok_or_else") emitter.unwrapZeroArgClosureText(arg0Rendered) else arg0Rendered
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
        val expectedRes = rustResultTypeArguments(emitter.currentExpectedParseTargetType ?: emitter.currentEnclosingReturnRenderedType)
        val okTy = ctx.inferredRecvTy?.takeIf { it.isNotEmpty() && it != "*" && it != "Nothing" && it != "Any?" } ?: expectedRes?.first
        val rawErrArg =
            if (ctx.rawMethod == "ok_or_else") {
                (emitter.unwrapParenOrGroupExpr(arg0Expr) as? Expr.Closure)?.body ?: arg0Expr
            } else {
                arg0Expr
            }
        val inferredErr = emitter.inferExprRenderedType(rawErrArg, ctx.selfTypeName)?.trim()
        val errTy =
            inferredErr?.takeIf { it.isNotEmpty() && it != "*" && it != "Nothing" && it != "Any?" }
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
        return emitter.withExpectedTargetAndKind(emitter.currentExpectedParseTargetType, optionElemKind) {
            emitter.renderExpr(argExpr, ctx.indent, ctx.selfTypeName)
        }
    }
}

private object NullAccumulatorFoldMethodRule : MethodLoweringRule() {
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
        val recvRenderedTy = emitter.inferExprRenderedType(ctx.effectiveReceiverExpr, ctx.selfTypeName)
        val inferredRecvElemTy =
            recvRenderedTy
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

private val POST_ARG_METHOD_RULES: List<MethodLoweringRule> =
    listOf(
        PostArgArithmeticAndHashRule,
        ConcreteNumericDispatchMethodRule,
        FmtAndCollectionTransformRule,
        DeclarativePostArgMethodRule,
        DefaultWitnessAndDequeMapMethodRule,
        UncheckedShiftAndUnconstrainedCmpRule,
        ErasedOptionCombinatorMethodRule,
        NullAccumulatorFoldMethodRule,
    )

private val CORE_SLICE_OR_PTR_METHODS: Set<String>
    get() = activeRules().words("coreSliceOrPtrMethods")
private val NON_SLICE_CORE_OWNER_TYPES: Set<String>
    get() = activeRules().words("nonSliceCoreOwnerTypes")
private val PEEK_MACRO_METHODS: Set<String>
    get() = activeRules().words("peekMacroMethods")
private val CALLBACK_WRAP_METHODS: Set<String>
    get() = activeRules().words("callbackWrapMethods")

private fun MethodCallLoweringContext.renderStandardMethodCall(): String {
    val adjustedRenderedValueArgs =
        when {
            rawMethod in PEEK_MACRO_METHODS && allMethodArgs.size == 1 && allMethodArgs[0] is Expr.Macro && renderedValueArgs.size == 1 ->
                listOf(renderedValueArgs[0].removeSuffix(".default()"))
            rawMethod in CALLBACK_WRAP_METHODS && allMethodArgs.size == 1 && renderedValueArgs.size == 1 ->
                listOf(emitter.wrapMapCallbackArg(allMethodArgs[0], renderedValueArgs[0]))
            else -> renderedValueArgs
        }
    val inferredRecvOwnerSimple =
        (
            if (emitter.unwrapParenOrGroupExpr(expr.receiver).let { it is Expr.Path && it.path.isIdent("self") }) {
                selfTypeName
            } else {
                emitter.currentTypedLocals[receiver] ?: emitter.inferExprRenderedType(expr.receiver, selfTypeName)
            }
        )?.removeSuffix("?")?.simpleKotlinTypeName()
    val methodConstEntry =
        inferredRecvOwnerSimple?.let { owner ->
            emitter.inspector.methodConstParamNames["$owner.$method"]
                ?: emitter.headerContext.crateFnConstParamNames["$owner.$method"]
                ?: emitter.scopedAbiKeysForRenderedType("$owner.$method").firstNotNullOfOrNull { emitter.headerContext.crateFnConstParamNames[it] }
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
    if ("core" in emitter.headerContext.crateNamespaces && typeArgs.isEmpty() && method in CORE_SLICE_OR_PTR_METHODS) {
        val recvCheckExpr =
            (emitter.unwrapParenOrGroupExpr(expr.receiver) as? Expr.Reference)?.let { emitter.unwrapParenOrGroupExpr(it.expr) }
                ?: expr.receiver
        val recvType = emitter.inferExprRenderedType(recvCheckExpr, selfTypeName)?.removeSuffix("?")?.trim()
        val recvOwnerSimple = recvType?.simpleKotlinTypeName()
        val hasUserMethod = recvOwnerSimple != null && method in emitter.inspector.instanceMethodsByType[recvOwnerSimple].orEmpty()
        val isStorageOrMaybeUninit =
            receiver.startsWith("MaybeUninit.") ||
                unwrapMaybeUninitRenderedInner(recvType) != null ||
                receiver in emitter.currentAddressableLocals.values
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
        if (!isStorageOrMaybeUninit && !hasUserMethod && recvOwnerSimple !in NON_SLICE_CORE_OWNER_TYPES && (recvType == null || isKnownSliceOrArrayType)) {
            val importPkg =
                when {
                    isPrimitiveArrayReceiver -> "mem"
                    isStringAsPtrReceiver -> "str"
                    else -> "slice"
                }
            registerCoreSubpkgImport(importPkg, method)
        }
    }
    val callRendered = "$receiver.$method$typeArgs($args)"
    val finalMethodCall = if (method == "zip") "($callRendered)" else callRendered
    return if (methodSubslicePre.isEmpty()) {
        finalMethodCall
    } else {
        "run { ${methodSubslicePre.joinToString("; ")}; val subCall0 = $finalMethodCall; ${methodSubslicePost.joinToString("; ")}; subCall0 }"
    }
}

internal fun KotlinJ2KEmitter.renderIntToBytesMethodCall(
    receiver: String,
    sourceReceiver: Expr,
    recvKind: NumericKind,
    rawMethod: String,
    selfTypeName: String? = null,
): String {
    val isBigEndian = rawMethod == "to_be_bytes" || (rawMethod == "to_ne_bytes" && activeCfgProfile.targetEndian == "big")
    if (recvKind == NumericKind.U128 || recvKind == NumericKind.I128 || recvKind == NumericKind.F16 || recvKind == NumericKind.F128) {
        val recvAtom = if (isAtomicPostfixReceiver(sourceReceiver, receiver)) receiver else "($receiver)"
        val fn = if (isBigEndian) "toBeBytes" else "toLeBytes"
        return "$recvAtom.$fn()"
    }
    val inferredReceiverType = inferExprRenderedType(sourceReceiver, selfTypeName)
    return when (recvKind) {
        NumericKind.U8, NumericKind.I8 -> {
            val byteExpr = if (inferredReceiverType == "UByte") receiver else coerceRenderedNumericExpr(sourceReceiver, receiver, NumericKind.U8)
            "ubyteArrayOf($byteExpr)"
        }
        else -> {
            val rawBitsExpr =
                when (recvKind) {
                    NumericKind.U16, NumericKind.I16 -> coerceRenderedNumericExpr(sourceReceiver, receiver, NumericKind.U32)
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
            "run { val rawBits = $rawBitsExpr; ubyteArrayOf($elems) }"
        }
    }
}

private class ZippedForEachLoopEmitter(
    private val emitter: KotlinJ2KEmitter,
    private val indent: String,
    private val selfTypeName: String?,
) {
    private data class ZippedSliceLeaf(
        val sliceExpr: Expr,
        val rawIdent: String?,
        val patIdent: Ident?,
    )

    fun tryRender(expr: Expr.MethodCall): String? {
        val rawFirstArg = expr.args.first() ?: return null
        val closure = emitter.unwrapParenOrGroupExpr(rawFirstArg) as? Expr.Closure ?: return null
        val closureInputs = closure.inputs.toList()
        if (closureInputs.size != 1) return null
        val leaves = mutableListOf<ZippedSliceLeaf>()
        if (!collectLeaves(expr.receiver, closureInputs[0], leaves) || leaves.size < 2) return null
        val bodyExpr = unwrapSingleExprClosureBody(closure.body)
        val derefAssignTargetRaw =
            when (bodyExpr) {
                is Expr.Assign -> extractDerefPathTargetIdent(bodyExpr.left)
                is Expr.Binary -> if (isCompoundAssignOp(bodyExpr.op)) extractDerefPathTargetIdent(bodyExpr.left) else null
                else -> null
            }
        val leafIdents = leaves.mapNotNullTo(linkedSetOf()) { it.rawIdent }
        val mutatesZippedLeaf = derefAssignTargetRaw != null && derefAssignTargetRaw in leafIdents
        if (leaves.size < 3 && !mutatesZippedLeaf) return null

        val prepLines = mutableListOf<String>()
        val sliceRefs =
            leaves.mapIndexed { idx, leaf ->
                val sliceNode = leaf.sliceExpr
                val rendered = emitter.renderExpr(sliceNode, indent, selfTypeName)
                val isReusablePath = sliceNode is Expr.Path && sliceNode.qself == null && sliceNode.path.segments.len() == 1
                if (isReusablePath) {
                    rendered
                } else {
                    val tmp = "zipSlice$idx"
                    prepLines += "val $tmp = $rendered"
                    tmp
                }
            }
        val idxVar =
            listOf("i", "zipIdx", "zipIndex").firstOrNull { cand ->
                cand !in leafIdents && emitter.scopeBuilder.resolveLocalKotlinName(cand) == null
            } ?: "zipIdx0"
        val bodyIndent = "$indent    "
        emitter.scopeBuilder.enterDef()
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
                        emitter.scopeBuilder
                            .bindLocal(raw, rustValueIdentToKotlin(raw), patIdent.span(), assignCount = AssignCount.AtMostOnce)
                            .kotlinName
                    stmts += "${bodyIndent}val $ktLocal = ${sliceRefs[idx]}[$idxVar]"
                }
                if (mutatesZippedLeaf && targetSliceRef != null) {
                    when (bodyExpr) {
                        is Expr.Assign -> stmts += "$bodyIndent$targetSliceRef[$idxVar] = ${emitter.renderExpr(bodyExpr.right, bodyIndent, selfTypeName)}"
                        is Expr.Binary -> {
                            val rhs = emitter.renderExpr(bodyExpr.right, bodyIndent, selfTypeName)
                            when (val op = bodyExpr.op) {
                                is BinOp.BitAndAssign, is BinOp.BitOrAssign, is BinOp.BitXorAssign -> {
                                    val bitOp =
                                        when (op) {
                                            is BinOp.BitAndAssign -> "and"
                                            is BinOp.BitOrAssign -> "or"
                                            else -> "xor"
                                        }
                                    stmts += "$bodyIndent$targetSliceRef[$idxVar] = $targetSliceRef[$idxVar] $bitOp ($rhs)"
                                }
                                else -> stmts += "$bodyIndent$targetSliceRef[$idxVar] ${emitter.renderBinOp(op)} $rhs"
                            }
                        }
                        else -> Unit
                    }
                } else {
                    stmts += "$bodyIndent${emitter.renderExpr(closure.body, bodyIndent, selfTypeName)}"
                }
                stmts
            } finally {
                emitter.scopeBuilder.exitDef()
            }
        val sizeArgs = sliceRefs.joinToString(", ") { "$it.size" }
        val forBlock = "for ($idxVar in 0 until minOf($sizeArgs)) {\n${loopLines.joinToString("\n")}\n$indent}"
        if (prepLines.isEmpty()) return forBlock
        return buildString {
            append("run {\n")
            for (p in prepLines) append(bodyIndent).append(p).append('\n')
            append(bodyIndent).append(forBlock.replace("\n$indent", "\n$bodyIndent")).append('\n')
            append(indent).append('}')
        }
    }

    private fun stripSliceIterMethodCall(expr: Expr): Expr {
        val unwrapped = emitter.unwrapParenOrGroupExpr(expr)
        val stripped = unwrapTrailingIterReceiver(unwrapped)
        return if (stripped !== unwrapped) emitter.unwrapParenOrGroupExpr(stripped) else unwrapped
    }

    private fun unwrapTupleClosurePat(pat: Pat): Pat =
        when (pat) {
            is Pat.Reference -> unwrapTupleClosurePat(pat.pat)
            is Pat.TypeAscription -> unwrapTupleClosurePat(pat.pat)
            else -> pat
        }

    private fun collectLeaves(chainExpr: Expr, pat: Pat, out: MutableList<ZippedSliceLeaf>): Boolean {
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
            return collectLeaves(cleanChain.receiver, patElems[0], out) && collectLeaves(rhsArg, patElems[1], out)
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

    private fun unwrapSingleExprClosureBody(body: Expr): Expr {
        val unwrapped = emitter.unwrapParenOrGroupExpr(body)
        if (unwrapped is Expr.BlockExpr && unwrapped.label == null && unwrapped.block.stmts.size == 1) {
            val single = unwrapped.block.stmts[0]
            if (single is Stmt.ExprStmt && single.semiToken == null) return unwrapSingleExprClosureBody(single.expr)
        }
        return unwrapped
    }

    private fun extractDerefPathTargetIdent(lhs: Expr): String? {
        val u = emitter.unwrapParenOrGroupExpr(lhs) as? Expr.Unary ?: return null
        if (u.op !is UnOp.Deref) return null
        val inner = emitter.unwrapParenOrGroupExpr(u.expr) as? Expr.Path ?: return null
        return if (inner.qself == null && inner.path.segments.len() == 1) inner.path.firstCleanIdent() else null
    }

    private fun isCompoundAssignOp(op: BinOp): Boolean =
        op is BinOp.AddAssign ||
            op is BinOp.SubAssign ||
            op is BinOp.MulAssign ||
            op is BinOp.DivAssign ||
            op is BinOp.RemAssign ||
            op is BinOp.BitAndAssign ||
            op is BinOp.BitOrAssign ||
            op is BinOp.BitXorAssign
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
    val convFn = PrimitiveArrayTypeCatalog.toArrayMethodName(targetArrayType) ?: return rendered
    val atom = if (isAtomicPostfixReceiver(innerExpr, rendered)) rendered else "($rendered)"
    return "$atom.$convFn()"
}

private fun KotlinJ2KEmitter.renderCopyFromSliceCall(
    receiverExpr: Expr,
    rawArgExpr: Expr,
    indent: String,
    selfTypeName: String?,
): String? {
    fun unwrapRefOrParens(node: Expr): Expr {
        val u = unwrapParenOrGroupExpr(node)
        return (u as? Expr.Reference)?.let { unwrapParenOrGroupExpr(it.expr) } ?: u
    }

    val srcExpr = unwrapRefOrParens(rawArgExpr)
    val recvUnwrapped = unwrapRefOrParens(receiverExpr)
    val rangePlace = recvUnwrapped as? Expr.Index
    val rangeExpr = rangePlace?.let { unwrapParenOrGroupExpr(it.index) as? Expr.Range }
    val dstExpr = if (rangeExpr != null) rangePlace.expr else recvUnwrapped
    val dstTy = inferExprRenderedType(dstExpr, selfTypeName)?.removeSuffix("?")?.trim()
    val dstIsPrimArray = dstTy in PRIMITIVE_ARRAY_TYPES
    val dstIsListLike = dstTy?.startsWith("MutableList<") == true || dstTy?.startsWith("List<") == true
    val dstRendered = renderExpr(dstExpr, indent, selfTypeName)
    val dstAtom = if (isAtomicPostfixReceiver(dstExpr, dstRendered)) dstRendered else "($dstRendered)"
    val srcRendered =
        withExpectedTargetAndKind(if (dstIsPrimArray) dstTy else currentExpectedParseTargetType, currentExpectedNumericKind) {
            renderExpr(srcExpr, indent, selfTypeName)
        }
    val srcTy =
        inferExprRenderedType(srcExpr, selfTypeName)?.removeSuffix("?")?.trim()
            ?: if (dstIsPrimArray && unwrapParenOrGroupExpr(srcExpr).let { it is Expr.Array || it is Expr.Repeat }) dstTy else null
    val srcIsPrimArray = srcTy in PRIMITIVE_ARRAY_TYPES
    val srcAtom = if (isAtomicPostfixReceiver(srcExpr, srcRendered)) srcRendered else "($srcRendered)"
    val startOffset = rangeExpr?.let { it.start?.let { s -> renderTypedNumericInitializer(s, NumericKind.I32, indent, selfTypeName) } ?: "0" }
    if (dstIsPrimArray && srcIsPrimArray) {
        return if (startOffset != null) "$srcAtom.copyInto($dstAtom, destinationOffset = $startOffset)" else "$srcAtom.copyInto($dstAtom)"
    }
    if (startOffset != null || dstIsPrimArray || dstIsListLike) {
        val suffix = closureLabelCounter++
        val dstVar = "dstTmp$suffix"
        val srcVar = "srcTmp$suffix"
        val idxVar = "idxTmp$suffix"
        val destIdx = if (startOffset == null || startOffset == "0") idxVar else "($startOffset) + $idxVar"
        return "run { val $dstVar = $dstAtom; val $srcVar = $srcAtom; for ($idxVar in $srcVar.indices) $dstVar[$destIdx] = $srcVar[$idxVar] }"
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
    val rule = activeRules().unconstrainedCmpRulesByName[rawMethod] ?: return null
    val recvBase = cleanRecvTy?.simpleKotlinTypeName()
    val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
    val isCoreRuntimeActive =
        "core" in headerContext.crateNamespaces ||
            headerContext.packageName == coreNs ||
            headerContext.packageName?.startsWith("$coreNs.") == true
    if (!isCoreRuntimeActive ||
        "PartialOrd" in inspector.declaredTypeNames ||
        "Ord" in inspector.declaredTypeNames
    ) {
        return null
    }
    val isEligibleRecv =
        (recvBase != null && recvBase in activeTypeParamNames && currentTypeParamWitnessCalls.none { it.key.first == recvBase }) ||
            cleanRecvTy == "Any" ||
            (allowWhenRecvUninferred && cleanRecvTy == null)
    if (!isEligibleRecv) return null

    var cachedOrdRef: String? = null
    var cachedCfRef: String? = null
    return rule.action.render(
        rawAbi = headerContext.rawAbiMode,
        onImport = { CoreIntrinsicLowering.registerCoreImport(this, it) },
    ) { key ->
        when (key) {
            "coreNs" -> coreNs
            "recv" -> recvRendered
            "arg0" -> argRendered
            "ordRef" -> cachedOrdRef ?: bindOrQualifyResolvedCrossSymbol("$coreNs.cmp.Ordering").also { cachedOrdRef = it }
            "cfRef" -> cachedCfRef ?: bindOrQualifyResolvedCrossSymbol("$coreNs.ops.controlflow.ControlFlow").also { cachedCfRef = it }
            else -> null
        }
    }
}
