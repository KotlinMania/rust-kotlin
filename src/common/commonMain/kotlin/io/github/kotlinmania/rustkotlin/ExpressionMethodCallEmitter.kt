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
        "($strTarget.$strParseMethod?.let { $res.Ok(it) } ?: $res.Err(\"failed to parse $targetTypeName\"))"
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

internal fun KotlinJ2KEmitter.renderExprMethodCall(expr: Expr.MethodCall, indent: String, selfTypeName: String?): String {
    val methodEntryParseTarget = currentExpectedParseTargetType
    val prevRecvParseTarget = currentExpectedParseTargetType
    val rawMethod = expr.method.toString()
    val cleanMethod = rawMethod.removePrefix("r#")
    unwrapTryIntoUnwrapExpr(expr)?.let { innerTryRecv ->
        val tryIntoTargetTy =
            (methodEntryParseTarget ?: currentEnclosingReturnRenderedType)
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
    if (cleanMethod == "copy_from_slice" && expr.args.toList().size == 1 && expr.turbofish == null) {
        currentExpectedParseTargetType = null
        val renderedCopy =
            try {
                renderCopyFromSliceCall(expr.receiver, expr.args.toList()[0], indent, selfTypeName)
            } finally {
                currentExpectedParseTargetType = prevRecvParseTarget
            }
        if (renderedCopy != null) {
            return renderedCopy
        }
    }
    currentExpectedParseTargetType =
        when (cleanMethod) {
            "map_err", "or_else", "or", "unwrap", "expect", "ok", "assume_init", "assume_init_read" ->
                methodEntryParseTarget ?: currentEnclosingReturnRenderedType
            else -> null
        }
    if (cleanMethod == "for_each" && expr.turbofish == null && expr.args.len() == 1) {
        tryRenderZippedForEachLoop(expr, indent, selfTypeName)?.let {
            currentExpectedParseTargetType = prevRecvParseTarget
            return it
        }
    }
    if (cleanMethod in setOf("as_ptr", "as_mut_ptr") && expr.args.isEmpty()) {
        sourceArrayLocal(expr.receiver)?.let { (storage, _) ->
            currentExpectedParseTargetType = prevRecvParseTarget
            return if (cleanMethod == "as_mut_ptr") "$storage.elementMutPtr(0)" else "$storage.elementConstPtr(0)"
        }
    }
    val effectiveReceiverExpr =
        if (cleanMethod in setOf("zip", "any", "all", "map", "filter", "filter_map", "flat_map", "fold", "for_each", "forEach", "enumerate", "count", "sum", "find", "position")) {
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
    val nonUnitArgs =
        expr.args
            .toList()
            .filterNot { it is Expr.Tuple && it.elems.isEmpty() }
            .map { arg ->
                if (cleanMethod == "zip") unwrapIndexedCollectionIterReceiver(arg, selfTypeName) else arg
            }
    val recvRawPtrRenderedTy = inferExprRenderedType(expr.receiver, selfTypeName)
    if (rawPointerPointeeType(recvRawPtrRenderedTy) != null) {
        val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
        val ptrPkg = "$coreNs.ptr"
        val intrPkg = "$coreNs.intrinsics"
        val isMutRecv = isMutRawPointerRenderedType(recvRawPtrRenderedTy)
        val ptrCtor = if (isMutRecv) "MutPtr" else "ConstPtr"
        if (nonUnitArgs.size == 1 &&
            rawMethod in
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
        ) {
            CoreIntrinsicLowering.registerCoreImport(this, "$ptrPkg.$ptrCtor")
            val argument = renderExpr(nonUnitArgs.single(), indent, selfTypeName)
            val deltaSign = if (rawMethod.endsWith("sub")) "-" else "+"
            val byteDelta =
                if ("byte_" in rawMethod) {
                    "($argument).toLong()"
                } else {
                    val pointeeLayout = resolveRawPointerPointeeLayout(expr.receiver, selfTypeName)
                    "($argument).toLong() * ($pointeeLayout).strideBytes"
                }
            return "$ptrCtor(($receiver).rawAddress $deltaSign ($byteDelta))"
        }
        if (rawMethod == "is_null" && nonUnitArgs.isEmpty()) {
            return "(($receiver).rawAddress == 0L)"
        }
        if (rawMethod == "cast_mut" && nonUnitArgs.isEmpty() && expr.turbofish == null) {
            val pointee = rawPointerPointeeType(recvRawPtrRenderedTy) ?: "Any"
            CoreIntrinsicLowering.registerCoreImport(this, "$ptrPkg.MutPtr")
            return "MutPtr<$pointee>(($receiver).rawAddress)"
        }
        if (rawMethod == "cast_const" && nonUnitArgs.isEmpty() && expr.turbofish == null) {
            val pointee = rawPointerPointeeType(recvRawPtrRenderedTy) ?: "Any"
            CoreIntrinsicLowering.registerCoreImport(this, "$ptrPkg.ConstPtr")
            return "ConstPtr<$pointee>(($receiver).rawAddress)"
        }
        if (rawMethod == "cast" && nonUnitArgs.isEmpty()) {
            val destination =
                expr.turbofish
                    ?.args
                    ?.toList()
                    ?.firstNotNullOfOrNull { (it as? GenericArgument.TypeArg)?.type }
            val sourcePointee = rawPointerPointeeType(recvRawPtrRenderedTy)
            val returnDestination =
                when (val returnType = currentEnclosingReturnSynType) {
                    is SynType.Ptr -> returnType.elem
                    is SynType.Reference -> (returnType.elem as? SynType.Slice)?.elem ?: returnType.elem
                    else -> null
                }
            val destinationPointee =
                destination?.let { renderType(it, selfTypeName) }
                    ?: rawPointerPointeeType(methodEntryParseTarget)
                    ?: rawPointerPointeeType(currentEnclosingReturnRenderedType)
                    ?: returnDestination?.let { renderType(it, selfTypeName) }
                    ?: sourcePointee
                    ?: "Any"
            if (sourcePointee == destinationPointee) return receiver
            CoreIntrinsicLowering.registerCoreImport(this, "$ptrPkg.$ptrCtor")
            return "$ptrCtor<$destinationPointee>(($receiver).rawAddress)"
        }
        if ((rawMethod == "read" || rawMethod == "read_unaligned") && nonUnitArgs.isEmpty() && expr.turbofish == null) {
            val pointeeLayout = resolveRawPointerPointeeLayout(expr.receiver, selfTypeName)
            return "($pointeeLayout).readNative(($receiver).rawAddress)"
        }
        if (rawMethod == "read_volatile" && nonUnitArgs.isEmpty() && expr.turbofish == null) {
            val pointeeLayout = resolveRawPointerPointeeLayout(expr.receiver, selfTypeName)
            return "($pointeeLayout).readVolatileNative(($receiver).rawAddress)"
        }
        if ((rawMethod == "write" || rawMethod == "write_unaligned") && nonUnitArgs.size == 1 && expr.turbofish == null) {
            val pointeeLayout = resolveRawPointerPointeeLayout(expr.receiver, selfTypeName)
            val valExpr = renderExpr(nonUnitArgs[0], indent, selfTypeName)
            return "($pointeeLayout).writeNative(($receiver).rawAddress, $valExpr)"
        }
        if (rawMethod == "write_volatile" && nonUnitArgs.size == 1 && expr.turbofish == null) {
            val pointeeLayout = resolveRawPointerPointeeLayout(expr.receiver, selfTypeName)
            val valExpr = renderExpr(nonUnitArgs[0], indent, selfTypeName)
            return "($pointeeLayout).writeVolatileNative(($receiver).rawAddress, $valExpr)"
        }
        if (rawMethod in setOf("copy_to", "copy_to_nonoverlapping", "copy_from", "copy_from_nonoverlapping") &&
            nonUnitArgs.size == 2 &&
            expr.turbofish == null
        ) {
            val pointeeLayout = resolveRawPointerPointeeLayout(expr.receiver, selfTypeName)
            val argExpr = renderExpr(nonUnitArgs[0], indent, selfTypeName)
            val countExpr = renderExpr(nonUnitArgs[1], indent, selfTypeName)
            val isFrom = rawMethod.startsWith("copy_from")
            val fnName = if (rawMethod.endsWith("_nonoverlapping")) "kmCopyNonoverlapping" else "kmCopy"
            CoreIntrinsicLowering.registerCoreImport(this, "$intrPkg.$fnName")
            val srcAddr = if (isFrom) "($argExpr).rawAddress" else "($receiver).rawAddress"
            val dstAddr = if (isFrom) "($receiver).rawAddress" else "($argExpr).rawAddress"
            return "$fnName($srcAddr, $dstAddr, ($countExpr).toLong() * ($pointeeLayout).sizeBytes)"
        }
        if (rawMethod == "write_bytes" && nonUnitArgs.size == 2 && expr.turbofish == null) {
            val pointeeLayout = resolveRawPointerPointeeLayout(expr.receiver, selfTypeName)
            val byteExpr = renderExpr(nonUnitArgs[0], indent, selfTypeName)
            val countExpr = renderExpr(nonUnitArgs[1], indent, selfTypeName)
            CoreIntrinsicLowering.registerCoreImport(this, "$intrPkg.kmWriteBytes")
            return "kmWriteBytes(($receiver).rawAddress, ($byteExpr).toUByte(), ($countExpr).toLong() * ($pointeeLayout).sizeBytes)"
        }
        if (rawMethod == "offset_from" && nonUnitArgs.size == 1 && expr.turbofish == null) {
            val pointeeLayout = resolveRawPointerPointeeLayout(expr.receiver, selfTypeName)
            val originExpr = renderExpr(nonUnitArgs[0], indent, selfTypeName)
            val diffExpr = "((($receiver).rawAddress - ($originExpr).rawAddress) / ($pointeeLayout).strideBytes)"
            return if (headerContext.rawAbiMode) diffExpr else "$diffExpr.toInt()"
        }
        if (rawMethod == "addr" && nonUnitArgs.isEmpty() && expr.turbofish == null) {
            return if (headerContext.rawAbiMode) "($receiver).rawAddress.toULong()" else "($receiver).rawAddress.toInt()"
        }
        if (rawMethod == "with_addr" && nonUnitArgs.size == 1 && expr.turbofish == null) {
            val addrExpr = renderExpr(nonUnitArgs[0], indent, selfTypeName)
            CoreIntrinsicLowering.registerCoreImport(this, "$ptrPkg.$ptrCtor")
            return "$ptrCtor(($addrExpr).toLong())"
        }
    }
    if (rawMethod == "is_null" && nonUnitArgs.isEmpty() && headerContext.rawAbiMode) {
        return "($receiver == 0L)"
    }
    if ((rawMethod == "cast_mut" || rawMethod == "cast_const") && nonUnitArgs.isEmpty() && expr.turbofish == null && headerContext.rawAbiMode) {
        return receiver
    }
    if (rawMethod == "count_ones" && nonUnitArgs.isEmpty() && expr.turbofish == null) {
        val recvKind = inferExprNumericKind(expr.receiver, selfTypeName)
        if (recvKind != null &&
            recvKind != NumericKind.F16 &&
            recvKind != NumericKind.F32 &&
            recvKind != NumericKind.F64 &&
            recvKind != NumericKind.F128
        ) {
            val recvAtom = if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
            if ("core" in headerContext.crateNamespaces) {
                val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
                if (headerContext.packageName != "$coreNs.num") {
                    CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.num.countOnes")
                }
                return "$recvAtom.countOnes()"
            }
            return "$recvAtom.countOneBits().toUInt()"
        }
    }
    if (rawMethod == "to_bits" && nonUnitArgs.isEmpty() && expr.turbofish == null) {
        inferExprNumericKind(expr.receiver, selfTypeName)?.let { recvKind ->
            renderFloatToBitsExpr(expr.receiver, receiver, recvKind)?.let { return it }
        }
    }
    if (rawMethod == "cast" && nonUnitArgs.isEmpty() && headerContext.rawAbiMode) {
        return receiver
    }
    // Dereference/volatile reads retain their canonical method call;
    // the pointer object itself is not the value stored at its address.
    if (rawMethod == "len" && nonUnitArgs.isEmpty() && expr.turbofish == null) {
        val recvTy =
            (currentTypedLocals[receiver] ?: inferExprRenderedType(expr.receiver, selfTypeName))
                ?.removeSuffix("?")
                ?.trim()
        val refCellInner = refCellGuardPointeeType(recvTy)
        val effectiveLenTy = refCellInner ?: recvTy
        val recvTarget =
            if (refCellInner != null) {
                val recvAtom = if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
                "$recvAtom.value"
            } else {
                if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
            }
        if (effectiveLenTy != null) {
            if (effectiveLenTy == "String" || effectiveLenTy == "CharSequence" || effectiveLenTy == "StringBuilder") {
                return "$recvTarget.length"
            }
            if (effectiveLenTy.startsWith("List<") ||
                effectiveLenTy.startsWith("MutableList<") ||
                effectiveLenTy.startsWith("Set<") ||
                effectiveLenTy.startsWith("MutableSet<") ||
                effectiveLenTy.startsWith("Map<") ||
                effectiveLenTy.startsWith("MutableMap<") ||
                effectiveLenTy.startsWith("Collection<") ||
                effectiveLenTy.startsWith("ArrayDeque<") ||
                effectiveLenTy in PRIMITIVE_ARRAY_TYPES
            ) {
                return "$recvTarget.size"
            }
        }
        if (inferArrayElementNumericKind(expr.receiver, selfTypeName) != null) {
            return "$receiver.size"
        }
    }
    if (rawMethod == "is_empty" && nonUnitArgs.isEmpty() && expr.turbofish == null) {
        val recvTy =
            (currentTypedLocals[receiver] ?: inferExprRenderedType(expr.receiver, selfTypeName))
                ?.removeSuffix("?")
                ?.trim()
        if (refCellGuardPointeeType(recvTy) != null) {
            val recvAtom = if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
            return "$recvAtom.value.isEmpty()"
        }
    }
    if (rawMethod == "is_none" && !hasDeclaredOptionEnum() && nonUnitArgs.isEmpty() && !isKnownNonNullableNonOptionReceiver(expr.receiver, receiver, selfTypeName)) {
        return "$receiver == null"
    }
    if (rawMethod == "is_some" && !hasDeclaredOptionEnum() && nonUnitArgs.isEmpty() && !isKnownNonNullableNonOptionReceiver(expr.receiver, receiver, selfTypeName)) {
        return "$receiver != null"
    }
    if (rawMethod == "as_bytes" && nonUnitArgs.isEmpty() && expr.turbofish == null) {
        val recvTy =
            (currentTypedLocals[receiver] ?: inferExprRenderedType(expr.receiver, selfTypeName))
                ?.removeSuffix("?")
                ?.trim()
        val refCellInner = refCellGuardPointeeType(recvTy)
        val effectiveBytesTy = refCellInner ?: recvTy
        val isKnownStringReceiver =
            effectiveBytesTy == null ||
                effectiveBytesTy in setOf("String", "CharSequence", "StringBuilder")
        if (isKnownStringReceiver) {
            val recvAtom = if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
            val strExpr = if (refCellInner != null) "$recvAtom.value" else recvAtom
            return if (headerContext.rawAbiMode || headerContext.finalClasses) {
                "$strExpr.encodeToByteArray().asUByteArray()"
            } else {
                "$strExpr.encodeToByteArray().map { it.toUByte() }"
            }
        }
    }
    if (rawMethod == "as_str" && nonUnitArgs.isEmpty() && expr.turbofish == null) {
        val recvTy =
            (currentTypedLocals[receiver] ?: inferExprRenderedType(expr.receiver, selfTypeName))
                ?.removeSuffix("?")
                ?.trim()
        if (refCellGuardPointeeType(recvTy) == "String") {
            val recvAtom = if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
            return "$recvAtom.value"
        }
        if (recvTy == "String") {
            return receiver
        }
        if (recvTy == null &&
            inspector.instanceMethodsByType.values.none { "asStr" in it } &&
            headerContext.crateFnReturnTypes.keys.none { it.endsWith(".asStr") }
        ) {
            return receiver
        }
    }
    if ((rawMethod == "as_ref" || rawMethod == "borrow") && nonUnitArgs.isEmpty() && expr.turbofish == null) {
        val recvTy =
            (currentTypedLocals[receiver] ?: inferExprRenderedType(expr.receiver, selfTypeName))
                ?.removeSuffix("?")
                ?.trim()
        if (receiver in currentIntoSubstitutedParams ||
            recvTy == "String" ||
            recvTy?.startsWith("List<") == true ||
            recvTy?.startsWith("MutableList<") == true
        ) {
            return receiver
        }
    }
    if ((rawMethod == "to_be_bytes" || rawMethod == "to_le_bytes" || rawMethod == "to_ne_bytes") &&
        nonUnitArgs.isEmpty() &&
        expr.turbofish == null
    ) {
        val recvKind = inferExprNumericKind(expr.receiver, selfTypeName)
        if (recvKind != null) {
            return renderIntToBytesMethodCall(receiver, expr.receiver, recvKind, rawMethod, selfTypeName)
        }
    }
    if ((rawMethod == "to_be" || rawMethod == "from_be" || rawMethod == "swap_bytes" || rawMethod == "to_le" || rawMethod == "from_le") &&
        nonUnitArgs.isEmpty() &&
        expr.turbofish == null
    ) {
        val recvKind = inferExprNumericKind(expr.receiver, selfTypeName)
        if (recvKind != null &&
            recvKind != NumericKind.F16 &&
            recvKind != NumericKind.F32 &&
            recvKind != NumericKind.F64 &&
            recvKind != NumericKind.F128
        ) {
            tryRender128BitEndianMethodCall(
                rawMethod = rawMethod,
                primKind = recvKind,
                recvExpr = expr.receiver,
                renderedRecv = receiver,
                guardSelfRecursion = false,
            )?.let { return it }
            if (recvKind != NumericKind.U128 && recvKind != NumericKind.I128) {
                return if (rawMethod == "to_le" || rawMethod == "from_le" || recvKind == NumericKind.I8 || recvKind == NumericKind.U8) {
                    receiver
                } else {
                    renderPrimitiveByteSwapExpr(receiver, recvKind)
                }
            }
        }
    }
    val isThreadCurrentCall =
        when (val recvCore = unwrapParenOrGroupExpr(expr.receiver)) {
            is Expr.Call -> {
                val fnPath = (unwrapParenOrGroupExpr(recvCore.func) as? Expr.Path)?.takeIf { it.qself == null }
                val segs =
                    fnPath
                        ?.path
                        ?.segments
                        ?.toList()
                        ?.map { it.ident.toString().removePrefix("r#") }
                recvCore.args.isEmpty() && (
                    segs == listOf("thread", "current") ||
                        segs == listOf("std", "thread", "current") ||
                        (segs == listOf("current") && scopeBuilder.resolveModuleBinding("current")?.importedPath == "std.thread.current")
                )
            }
            else -> false
        }
    if (rawMethod == "id" &&
        nonUnitArgs.isEmpty() &&
        (receiver == "thread.current()" || receiver.endsWith(".thread.current()") || isThreadCurrentCall)
    ) {
        usesCurrentThreadToken = true
        return "currentThreadBoundToken()"
    }
    if (rawMethod == "debug_list" && nonUnitArgs.isEmpty()) {
        return "object { val entries = mutableListOf<Any?>(); fun entry(v: Any?) = apply { entries.add(v) }; fun entries(iter: Iterable<*>) = apply { for (e in iter) entries.add(e) }; fun finish() { $receiver.append(entries.joinToString(\", \", \"[\", \"]\")) } }"
    }
    if (rawMethod == "debug_set" && nonUnitArgs.isEmpty()) {
        return "object { val entries = mutableListOf<Any?>(); fun entry(v: Any?) = apply { entries.add(v) }; fun entries(iter: Iterable<*>) = apply { for (e in iter) entries.add(e) }; fun finish() { $receiver.append(entries.joinToString(\", \", \"{\", \"}\")) } }"
    }
    if (rawMethod == "debug_map" && nonUnitArgs.isEmpty()) {
        return "object { val entries = mutableListOf<String>(); fun entry(k: Any?, v: Any?) = apply { entries.add(\"\$k: \$v\") }; fun finish() { $receiver.append(entries.joinToString(\", \", \"{\", \"}\")) } }"
    }
    if (rawMethod == "pop_front" && nonUnitArgs.isEmpty()) {
        return "$receiver.removeFirstOrNull()"
    }
    if ((rawMethod == "as_deref" || rawMethod == "as_deref_mut" || rawMethod == "as_ref" || rawMethod == "as_mut") &&
        nonUnitArgs.isEmpty() &&
        expr.turbofish == null &&
        isKnownNullableOptionExpr(expr.receiver, receiver, selfTypeName)
    ) {
        // Only erased Option borrows are identity operations. In particular,
        // NonNull::as_ref/as_mut must load the pointee rather than return
        // the pointer wrapper, and user-defined methods must remain calls.
        return receiver
    }
    val isDequeIterReceiver = isDequeIterFieldExpr(expr.receiver, selfTypeName) || receiver in currentDequeLocals
    if (isDequeIterReceiver && nonUnitArgs.isEmpty() && expr.turbofish == null) {
        when (rawMethod) {
            "next" -> return "$receiver.removeFirstOrNull()"
            "next_back" -> return "$receiver.removeLastOrNull()"
            "len" -> return "$receiver.size"
            "clone" -> return "ArrayDeque($receiver)"
        }
    }
    val isVecReceiver = isMutableVecExpr(expr.receiver, receiver, selfTypeName)
    if (isVecReceiver && expr.turbofish == null) {
        when {
            rawMethod == "pop" && nonUnitArgs.isEmpty() -> return "$receiver.removeLastOrNull()"
            rawMethod == "len" && nonUnitArgs.isEmpty() -> return "$receiver.size"
            (rawMethod == "into_iter" || rawMethod == "iter" || rawMethod == "iter_mut") && nonUnitArgs.isEmpty() ->
                return "ArrayDeque($receiver)"
            rawMethod == "clone" && nonUnitArgs.isEmpty() -> return "$receiver.toMutableList()"
            rawMethod == "last_mut" && nonUnitArgs.isEmpty() -> return "$receiver.lastOrNull()"
            rawMethod == "first_mut" && nonUnitArgs.isEmpty() -> return "$receiver.firstOrNull()"
        }
    }
    if (rawMethod == "into" && nonUnitArgs.isEmpty() && expr.turbofish == null && receiver in currentIntoSubstitutedParams) {
        return receiver
    }
    if (rawMethod == "into_iter" && nonUnitArgs.isEmpty() && expr.turbofish == null && receiver in currentIterableLocals) {
        return "$receiver.iterator()"
    }
    if (rawMethod == "into_iter" &&
        nonUnitArgs.isEmpty() &&
        expr.turbofish == null &&
        !isKnownNullableOptionExpr(expr.receiver, receiver, selfTypeName) &&
        !hasDeclaredOptionEnum() &&
        "IntoIterator" !in inspector.declaredTraitsByName
    ) {
        val recvTy = inferExprRenderedType(expr.receiver, selfTypeName)?.removeSuffix("?")?.trim()
        if (recvTy != null &&
            (
                recvTy == "TokenStream" ||
                    recvTy.endsWith(".TokenStream") ||
                    recvTy.startsWith("Iterable<") ||
                    recvTy.startsWith("Collection<") ||
                    recvTy.startsWith("List<") ||
                    recvTy.startsWith("MutableList<") ||
                    recvTy.startsWith("Set<") ||
                    recvTy.startsWith("MutableSet<") ||
                    recvTy.endsWith("Array")
            )
        ) {
            return "ArrayDeque(($receiver).toList())"
        }
    }
    if (rawMethod == "clone" && nonUnitArgs.isEmpty() && expr.turbofish == null) {
        val recvTy = currentTypedLocals[receiver]
        if (recvTy != null) {
            currentTypeParamWitnessCalls[recvTy to "clone"]?.let { cloneFn ->
                return "$cloneFn($receiver)"
            }
        }
    }
    val turbofish = expr.turbofish
    if (rawMethod == "parse" && nonUnitArgs.isEmpty() && turbofish != null && turbofish.args.size == 1) {
        val tfGa = turbofish.args.first() as? GenericArgument.TypeArg
        if (tfGa != null) {
            val renderedTfTy = renderType(tfGa.type, selfTypeName).trim()
            tryRenderStringPrimitiveParse(expr, receiver, renderedTfTy, selfTypeName)?.let { return it }
            if (renderedTfTy.endsWith("?")) {
                val optInner = renderedTfTy.removeSuffix("?").trim()
                val peekTok = resolveOptionalSynParsePeekToken(optInner)
                if (peekTok != null &&
                    optInner !in activeTypeParamNames &&
                    optInner !in KOTLIN_STDLIB_SHADOWED_IMPORTS
                ) {
                    return renderOptionalSynParseExpr(receiver, peekTok, optInner)
                }
            } else {
                val recvTyBase =
                    (currentTypedLocals[receiver] ?: inferExprRenderedType(expr.receiver, selfTypeName))
                        ?.removeSuffix("?")
                        ?.substringAfterLast('.')
                        ?.trim()
                if ((recvTyBase == "ParseStream" || recvTyBase == "ParseBuffer") &&
                    renderedTfTy.isNotEmpty() &&
                    renderedTfTy != "TokenStream" &&
                    renderedTfTy !in activeTypeParamNames &&
                    renderedTfTy !in KOTLIN_STDLIB_SHADOWED_IMPORTS &&
                    renderedTfTy.all { it.isLetterOrDigit() || it == '_' || it == '.' }
                ) {
                    return "$receiver.parse($renderedTfTy::parse)"
                }
            }
        }
    }
    if (rawMethod == "parse" && nonUnitArgs.isEmpty() && expr.turbofish == null) {
        val inferredPrimitiveTarget =
            (methodEntryParseTarget ?: currentEnclosingReturnRenderedType)
                ?.let { rustResultOkPayloadType(it) ?: it }
                ?.removeSuffix("?")
                ?.trim()
        tryRenderStringPrimitiveParse(expr, receiver, inferredPrimitiveTarget, selfTypeName)?.let { return it }
        if (methodEntryParseTarget?.endsWith("?") == true) {
            val optInner = methodEntryParseTarget.removeSuffix("?").trim()
            val peekTok = resolveOptionalSynParsePeekToken(optInner)
            if (peekTok != null &&
                optInner !in activeTypeParamNames &&
                optInner !in KOTLIN_STDLIB_SHADOWED_IMPORTS
            ) {
                return renderOptionalSynParseExpr(receiver, peekTok, optInner)
            }
        }
        val nonNullParseTarget = methodEntryParseTarget?.takeIf { !it.endsWith("?") }
        val matchedParseWitness =
            (nonNullParseTarget?.let { currentTypeParamWitnessCalls[it to "parse"] })
                ?: currentTypeParamWitnessCalls.entries
                    .filter { it.key.second == "parse" }
                    .map { it.value }
                    .distinct()
                    .singleOrNull()
        if (matchedParseWitness != null) {
            return "$receiver.parse($matchedParseWitness)"
        }
        val concreteTarget =
            nonNullParseTarget?.takeIf { t ->
                t.isNotEmpty() &&
                    t != "TokenStream" &&
                    t !in activeTypeParamNames &&
                    t !in KOTLIN_STDLIB_SHADOWED_IMPORTS &&
                    t.all { it.isLetterOrDigit() || it == '_' || it == '.' }
            }
        if (concreteTarget != null) {
            return "$receiver.parse($concreteTarget::parse)"
        }
    }
    if (rawMethod == "into_token_stream" && nonUnitArgs.isEmpty() && expr.turbofish == null) {
        val unwrappedRecv = unwrapParenOrGroupExpr(expr.receiver)
        val isSharedBorrowRecv =
            (unwrappedRecv is Expr.Reference && unwrappedRecv.mutability == null) ||
                (currentReceiverIsSharedBorrow && unwrappedRecv is Expr.Path && unwrappedRecv.qself == null && unwrappedRecv.path.isIdent("self"))
        if (isSharedBorrowRecv) {
            val recvAtom = if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
            return "TokenStream.new().also { tokens -> $recvAtom.toTokens(tokens) }"
        }
    }
    val isEscapedDoubleUnderscoreSelfCall =
        rawMethod.startsWith("__") && rustMethodIdentToKotlin(rawMethod) == currentEnclosingFunctionName
    // quote's `__span` is emitted as `span` on the definition side, which collides with the
    // enclosing `span`. Call it through a camelCase import alias (never `__<name>`).
    val method =
        when {
            isEscapedDoubleUnderscoreSelfCall && rawMethod == "__span" -> {
                usesQuoteUnderlyingSpan = true
                "quoteSpan"
            }
            isEscapedDoubleUnderscoreSelfCall -> {
                val underlyingKt = rustMethodIdentToKotlin(rawMethod.removePrefix("__"))
                val aliasKt = "underlying${underlyingKt.replaceFirstChar { it.uppercase() }}"
                aliasedUnderlyingSelfCalls[underlyingKt] = aliasKt
                aliasKt
            }
            cleanMethod == "assume_init" -> "assumeInit"
            cleanMethod == "assume_init_read" -> "assumeInitRead"
            (cleanMethod == "as_ptr" || cleanMethod == "as_mut_ptr") &&
                (
                    receiver.startsWith("MaybeUninit.") ||
                        unwrapMaybeUninitRenderedInner(inferExprRenderedType(effectiveReceiverExpr, selfTypeName)) != null
                ) ->
                if (cleanMethod == "as_mut_ptr") "asMutPtr" else "asPtr"
            else -> rustMethodIdentToKotlin(rawMethod)
        }
    val macroTypeArgs = mutableListOf<String>()
    val typeArgs =
        expr.turbofish
            ?.let { tf ->
                val renderedArgs = mutableListOf<String>()
                for (ga in tf.args.toList()) {
                    val unwrappedMacro = (ga as? GenericArgument.TypeArg)?.type?.let { unwrapSynType(it) } as? SynType.Macro
                    if (unwrappedMacro != null) {
                        val renderedMac = renderMacroInvocationAsStmtOrExpr(unwrappedMacro.mac, indent, selfTypeName)
                        if (rawMethod == "parse" && nonUnitArgs.isEmpty() && renderedMac.endsWith(".default()")) {
                            macroTypeArgs += "${renderedMac.removeSuffix(".default()")}::parse"
                        } else {
                            macroTypeArgs += renderedMac
                        }
                    } else {
                        renderGenericArgument(ga, selfTypeName)?.let { renderedArgs += it }
                    }
                }
                if (renderedArgs.isNotEmpty()) "<${renderedArgs.joinToString(", ")}>" else ""
            }.orEmpty()
    val savedKind = currentExpectedNumericKind
    val savedTupleElemType = currentClosureTupleElemType
    val zipElemType =
        if ((cleanMethod == "all" || cleanMethod == "any") &&
            effectiveReceiverExpr is Expr.MethodCall &&
            effectiveReceiverExpr.method.toString().removePrefix("r#") == "zip"
        ) {
            val zipBase = unwrapTrailingIterReceiver(effectiveReceiverExpr.receiver)
            inferExprRenderedType(zipBase, selfTypeName)?.let { extractIndexedElementRenderedType(it, selfTypeName) }
        } else {
            null
        }
    val recvNumericKindForMethod =
        if (typeArgs.isEmpty() &&
            rawMethod in
            setOf(
                "wrapping_add",
                "wrapping_sub",
                "wrapping_mul",
                "wrapping_div",
                "wrapping_rem",
                "wrapping_shl",
                "wrapping_shr",
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
        ) {
            inferExprNumericKind(expr.receiver, selfTypeName)
        } else {
            null
        }
    val wrappingNumericKind =
        if (rawMethod in setOf("wrapping_add", "wrapping_sub", "wrapping_mul") &&
            nonUnitArgs.size == 1 &&
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
    currentExpectedNumericKind =
        when {
            rawMethod == "to_digit" -> NumericKind.U32
            recvNumericKindForMethod == null -> null
            rawMethod in setOf("wrapping_shl", "wrapping_shr", "checked_shl", "checked_shr", "overflowing_shl", "overflowing_shr", "rotate_left", "rotate_right", "pow", "checked_pow") ->
                NumericKind.U32
            rawMethod == "powi" -> NumericKind.I32
            else -> recvNumericKindForMethod
        }
    currentExpectedParseTargetType =
        if (cleanMethod in setOf("and_then", "or_else")) {
            methodEntryParseTarget ?: currentEnclosingReturnRenderedType
        } else {
            null
        }
    currentClosureTupleElemType = zipElemType
    val methodSubslicePre = mutableListOf<String>()
    val methodSubslicePost = mutableListOf<String>()
    val recvOwnerSimple =
        inferExprRenderedType(expr.receiver, selfTypeName)
            ?.removeSuffix("?")
            ?.substringAfterLast('.')
            ?.substringBefore('<')
            ?.trim()
    val renderedValueArgs =
        try {
            nonUnitArgs.mapIndexed { argIdx, argExpr ->
                val subsliceSpec =
                    tryExtractMutableArraySubsliceArg(
                        argExpr = argExpr,
                        argIndex = argIdx,
                        expectMutableList = methodExpectsMutableListSliceParam(recvOwnerSimple, method, argIdx),
                        indent = indent,
                        selfTypeName = selfTypeName,
                    )
                if (subsliceSpec != null) {
                    methodSubslicePre += subsliceSpec.first
                    methodSubslicePost += subsliceSpec.third
                    subsliceSpec.second
                } else {
                    val renderedArg = renderExpr(argExpr, indent, selfTypeName)
                    val isRawPtrRecv =
                        recvOwnerSimple in setOf("MutPtr", "ConstPtr") ||
                            inferExprRenderedType(expr.receiver, selfTypeName)
                                ?.removeSuffix("?")
                                ?.substringBefore('<')
                                ?.substringAfterLast('.')
                                ?.trim() in setOf("MutPtr", "ConstPtr")
                    if (isRawPtrRecv && isStdlibConstPtrMethodParam(rawMethod, null, argIdx)) {
                        coerceMutPtrArgToConstPtr(renderedArg, inferExprRenderedType(argExpr, selfTypeName))
                    } else {
                        renderedArg
                    }
                }
            }
        } finally {
            currentExpectedNumericKind = savedKind
            currentExpectedParseTargetType = methodEntryParseTarget
            currentClosureTupleElemType = savedTupleElemType
        }
    if (wrappingNumericKind != null) {
        val op =
            when (rawMethod) {
                "wrapping_add" -> "+"
                "wrapping_sub" -> "-"
                else -> "*"
            }
        val coercedRhs = coerceRenderedNumericExpr(nonUnitArgs[0], renderedValueArgs[0], wrappingNumericKind)
        val rhs = if (isAtomicPostfixReceiver(nonUnitArgs[0], coercedRhs)) coercedRhs else "($coercedRhs)"
        val lhs = if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
        val arithmetic = "($lhs $op $rhs)"
        // Kotlin promotes byte/short arithmetic; explicitly truncate back to Rust's width.
        return when (wrappingNumericKind) {
            NumericKind.I8 -> "$arithmetic.toByte()"
            NumericKind.U8 -> "$arithmetic.toUByte()"
            NumericKind.I16 -> "$arithmetic.toShort()"
            NumericKind.U16 -> "$arithmetic.toUShort()"
            else -> arithmetic
        }
    }
    val hasherState = currentHasherStateParam
    if (hasherState != null &&
        rawMethod == "hash" &&
        renderedValueArgs.size == 1 &&
        renderedValueArgs[0] == hasherState &&
        typeArgs.isEmpty()
    ) {
        return "$hasherState = 31 * $hasherState + ($receiver).hashCode()"
    }
    if ("core" in headerContext.crateNamespaces && typeArgs.isEmpty()) {
        val recvTyForStr =
            (currentTypedLocals[receiver] ?: inferExprRenderedType(expr.receiver, selfTypeName))
                ?.removeSuffix("?")
                ?.trim()
        val atomicRecv = if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
        when {
            rawMethod == "chars" &&
                renderedValueArgs.isEmpty() &&
                (recvTyForStr == "String" || recvTyForStr == "CharSequence") &&
                "chars" !in inspector.declaredTopLevelFnRawNames &&
                inspector.instanceMethodsByType.values.none { "chars" in it } &&
                !(headerContext.packageName == "${CoreIntrinsicLowering.resolveCoreNamespace(headerContext)}.str" && currentEnclosingFunctionName == "chars") -> {
                val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
                if (headerContext.packageName != "$coreNs.str") {
                    CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.str.chars")
                }
                return "$atomicRecv.chars()"
            }
            (recvTyForStr == "Chars" || recvTyForStr?.endsWith(".Chars") == true) &&
                !(headerContext.packageName == "${CoreIntrinsicLowering.resolveCoreNamespace(headerContext)}.str.iter" && selfTypeName == "Chars") -> {
                when {
                    rawMethod == "next" && renderedValueArgs.isEmpty() -> return "$atomicRecv.next()"
                    rawMethod == "as_str" && renderedValueArgs.isEmpty() -> return "$atomicRecv.asStr()"
                    rawMethod == "clone" && renderedValueArgs.isEmpty() -> return "$atomicRecv.clone()"
                    rawMethod == "enumerate" && renderedValueArgs.isEmpty() -> return "$atomicRecv.enumerate()"
                }
            }
            rawMethod == "to_digit" &&
                renderedValueArgs.size == 1 &&
                (recvTyForStr == "Char" || (unwrapParenOrGroupExpr(expr.receiver) as? Expr.Lit)?.lit is Lit.Char) &&
                !(headerContext.packageName == "${CoreIntrinsicLowering.resolveCoreNamespace(headerContext)}.char.methods" && currentEnclosingFunctionName == "toDigit") -> {
                val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
                if (headerContext.packageName != "$coreNs.char.methods") {
                    CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.char.methods.toDigit")
                }
                val radixArg = coerceNumericArgIfNeeded(nonUnitArgs[0], renderedValueArgs[0], NumericKind.U32, selfTypeName)
                return "$atomicRecv.toDigit($radixArg)"
            }
        }
    }
    renderConcreteNumericMethodCall(
        expr = expr,
        receiver = receiver,
        rawMethod = rawMethod,
        nonUnitArgs = nonUnitArgs,
        renderedValueArgs = renderedValueArgs,
        typeArgs = typeArgs,
        indent = indent,
        selfTypeName = selfTypeName,
    )?.let { return it }
    if (rawMethod == "append_all" && renderedValueArgs.size == 1 && typeArgs.isEmpty()) {
        return "for (item in ${renderedValueArgs[0]}) item.toTokens($receiver)"
    }
    if ((rawMethod == "write_str" || rawMethod == "write_char" || rawMethod == "pad") &&
        renderedValueArgs.size == 1 &&
        typeArgs.isEmpty()
    ) {
        return "$receiver.append(${renderedValueArgs[0]})"
    }
    if (rawMethod == "find" && renderedValueArgs.size == 1 && typeArgs.isEmpty()) {
        return "$receiver.indexOf(${renderedValueArgs[0]}).takeIf { it >= 0 }"
    }
    if ((rawMethod == "position" || rawMethod == "rposition") && renderedValueArgs.size == 1 && typeArgs.isEmpty()) {
        val recvAtom = if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
        val idxFn = if (rawMethod == "position") "indexOfFirst" else "indexOfLast"
        return "$recvAtom.$idxFn(${renderedValueArgs[0]}).takeIf { it >= 0 }"
    }
    if (rawMethod == "enumerate" && renderedValueArgs.isEmpty() && typeArgs.isEmpty()) {
        val recvAtom = if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
        return "$recvAtom.mapIndexed { index, item -> Pair(index, item) }"
    }
    if (rawMethod == "take" && renderedValueArgs.size == 1 && typeArgs.isEmpty()) {
        unwrapRepeatSourceArg(expr.receiver, indent, selfTypeName)?.let { repArg ->
            val elemRendered = renderExpr(repArg, indent, selfTypeName)
            return "List(${renderedValueArgs[0]}) { $elemRendered }"
        }
    }
    if (rawMethod == "collect" && renderedValueArgs.isEmpty()) {
        val explicitCollectTarget = typeArgs.removePrefix("<").removeSuffix(">").trim()
        val effectiveCollectTarget =
            explicitCollectTarget.ifEmpty {
                (methodEntryParseTarget ?: currentEnclosingReturnRenderedType)?.trim().orEmpty()
            }
        if (effectiveCollectTarget == "String") {
            val recvTake = unwrapParenOrGroupExpr(expr.receiver) as? Expr.MethodCall
            if (recvTake != null && recvTake.method.toString() == "take" && recvTake.args.len() == 1) {
                unwrapRepeatSourceArg(recvTake.receiver, indent, selfTypeName)?.let { repArg ->
                    val elemRendered = renderExpr(repArg, indent, selfTypeName)
                    val countRendered = renderExpr(recvTake.args.first()!!, indent, selfTypeName)
                    return "($elemRendered).toString().repeat($countRendered)"
                }
            }
            val recvAtom = if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
            return "$recvAtom.joinToString(\"\")"
        }
        if (explicitCollectTarget == "List<*>" ||
            effectiveCollectTarget.startsWith("List<") ||
            effectiveCollectTarget.startsWith("MutableList<")
        ) {
            val recvAtom = if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
            return "$recvAtom.toMutableList()"
        }
        if (isMapRenderedType(effectiveCollectTarget)) {
            val recvAtom = if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
            return "$recvAtom.toMap(LinkedHashMap())"
        }
        if (isSetRenderedType(effectiveCollectTarget)) {
            val recvAtom = if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
            return "$recvAtom.toMutableSet()"
        }
    }
    if ((rawMethod == "push_str" || (rawMethod == "push" && !isVecReceiver)) &&
        renderedValueArgs.size == 1 &&
        typeArgs.isEmpty()
    ) {
        tryRenderStringAppendMethod(expr, receiver, renderedValueArgs[0], selfTypeName)?.let { return it }
    }
    if (rawMethod == "clear" && renderedValueArgs.isEmpty() && typeArgs.isEmpty()) {
        val recvTy =
            (currentTypedLocals[receiver] ?: inferExprRenderedType(expr.receiver, selfTypeName))
                ?.removeSuffix("?")
                ?.trim()
        if (refCellGuardPointeeType(recvTy) == "String") {
            val recvAtom = if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
            return "$recvAtom.value = \"\""
        }
        if (recvTy == "String" && receiver.all { it.isLetterOrDigit() || it == '_' || it == '.' }) {
            return "$receiver = \"\""
        }
    }
    if (rawMethod == "extend_from_slice" && renderedValueArgs.size == 1 && typeArgs.isEmpty()) {
        val recvTy =
            (currentTypedLocals[receiver] ?: inferExprRenderedType(expr.receiver, selfTypeName))
                ?.removeSuffix("?")
                ?.trim()
        if (isVecReceiver || recvTy?.startsWith("MutableList<") == true) {
            return "$receiver.addAll(${renderedValueArgs[0]})"
        }
    }
    if ((
            rawMethod == "byte_offset" ||
                rawMethod == "byte_add" ||
                rawMethod == "byte_sub" ||
                rawMethod == "wrapping_byte_offset" ||
                rawMethod == "wrapping_byte_add" ||
                rawMethod == "wrapping_byte_sub"
        ) &&
        renderedValueArgs.size == 1 &&
        typeArgs.isEmpty()
    ) {
        val deltaSign =
            if (rawMethod == "sub" || rawMethod == "byte_sub" || rawMethod == "wrapping_sub" || rawMethod == "wrapping_byte_sub") {
                "-"
            } else {
                "+"
            }
        val deltaArg = renderedValueArgs[0]
        val deltaRecv = if (isAtomicPostfixReceiver(nonUnitArgs[0], deltaArg)) deltaArg else "($deltaArg)"
        val deltaExpr = convertNumericReceiver(deltaRecv, "Long", inferExprNumericKind(nonUnitArgs[0], selfTypeName))
        if (headerContext.rawAbiMode) {
            return "($receiver $deltaSign $deltaExpr)"
        }
    }
    if (rawMethod == "debug_struct" && renderedValueArgs.size == 1 && typeArgs.isEmpty() && "DebugStruct" !in inspector.declaredTypeNames) {
        usesDebugStruct = true
        return "DebugStruct($receiver, ${renderedValueArgs[0]})"
    }
    if (rawMethod == "debug_tuple" && renderedValueArgs.size == 1 && typeArgs.isEmpty() && "DebugTuple" !in inspector.declaredTypeNames) {
        usesDebugTuple = true
        return "DebugTuple($receiver, ${renderedValueArgs[0]})"
    }
    if (rawMethod == "push_back" && renderedValueArgs.size == 1 && typeArgs.isEmpty()) {
        return "$receiver.addLast(${renderedValueArgs[0]})"
    }
    if (isVecReceiver && typeArgs.isEmpty()) {
        when {
            rawMethod == "push" && renderedValueArgs.size == 1 ->
                return "$receiver.add(${renderedValueArgs[0]})"
            rawMethod == "insert" && renderedValueArgs.size == 2 ->
                return "$receiver.add(${renderedValueArgs[0]}, ${renderedValueArgs[1]})"
            rawMethod == "clone_from" && renderedValueArgs.size == 1 ->
                return "run { $receiver.clear(); $receiver.addAll(${renderedValueArgs[0]}) }"
            (rawMethod == "get" || rawMethod == "get_mut") && renderedValueArgs.size == 1 ->
                return "$receiver.getOrNull(${renderedValueArgs[0]})"
        }
    }
    val recvCollectionTy = (currentTypedLocals[receiver] ?: inferExprRenderedType(expr.receiver, selfTypeName))
    if (isMapRenderedType(recvCollectionTy) && typeArgs.isEmpty()) {
        val recvAtom = if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
        when {
            rawMethod == "insert" && renderedValueArgs.size == 2 ->
                return "$recvAtom.put(${renderedValueArgs[0]}, ${renderedValueArgs[1]})"
            rawMethod == "clone" && renderedValueArgs.isEmpty() ->
                return "$recvAtom.toMutableMap()"
            (rawMethod == "get" || rawMethod == "get_mut") && renderedValueArgs.size == 1 ->
                return "$recvAtom.get(${renderedValueArgs[0]})"
        }
    }
    if (isSetRenderedType(recvCollectionTy) && typeArgs.isEmpty()) {
        val recvAtom = if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
        when {
            rawMethod == "insert" && renderedValueArgs.size == 1 ->
                return "$recvAtom.add(${renderedValueArgs[0]})"
            rawMethod == "clone" && renderedValueArgs.isEmpty() ->
                return "$recvAtom.toMutableSet()"
        }
    }
    if (rawMethod == "clone_from" &&
        renderedValueArgs.size == 1 &&
        typeArgs.isEmpty() &&
        (expr.receiver is Expr.Field || receiver in currentNullableLocals)
    ) {
        return "$receiver = ${renderedValueArgs[0]}"
    }
    if ((rawMethod == "push" || rawMethod == "extend") &&
        !isVecReceiver &&
        renderedValueArgs.size == 1 &&
        typeArgs.isEmpty()
    ) {
        val defaultWitness =
            currentTypeParamWitnessCalls.entries
                .filter { it.key.second == "default" && it.value !in renderedValueArgs }
                .map { it.value }
                .distinct()
                .singleOrNull()
        if (defaultWitness != null) {
            return "$receiver.$method(${renderedValueArgs[0]}, $defaultWitness)"
        }
    }
    if (rawMethod == "map" && nonUnitArgs.size == 1 && renderedValueArgs.size == 1) {
        if (isTransparentWrapperOrIdentityMapArg(nonUnitArgs[0])) {
            return receiver
        }
        if (typeArgs.isEmpty() && (receiver.startsWith("ArrayDeque(") || isDequeIterReceiver)) {
            val mappedArg = wrapMapCallbackArg(nonUnitArgs[0], renderedValueArgs[0])
            return "ArrayDeque(($receiver).map($mappedArg))"
        }
    }
    if ((rawMethod == "strip_prefix" || rawMethod == "strip_suffix") &&
        renderedValueArgs.size == 1 &&
        typeArgs.isEmpty()
    ) {
        val recvTy = inferExprRenderedType(expr.receiver, selfTypeName)?.removeSuffix("?")?.trim()
        if (recvTy == null || recvTy == "String") {
            val atomicRecv = if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
            val pred = if (rawMethod == "strip_prefix") "startsWith" else "endsWith"
            val rem = if (rawMethod == "strip_prefix") "removePrefix" else "removeSuffix"
            return "$atomicRecv.takeIf { it.$pred(${renderedValueArgs[0]}) }?.$rem(${renderedValueArgs[0]})"
        }
    }
    if ((rawMethod == "split_once" || rawMethod == "rsplit_once") &&
        renderedValueArgs.size == 1 &&
        typeArgs.isEmpty()
    ) {
        val recvTy =
            (currentTypedLocals[receiver] ?: inferExprRenderedType(expr.receiver, selfTypeName))
                ?.removeSuffix("?")
                ?.trim()
        if (recvTy == null || recvTy == "String" || recvTy == "CharSequence" || refCellGuardPointeeType(recvTy) == "String") {
            val atomicRecv = if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
            val strExpr = if (refCellGuardPointeeType(recvTy) == "String") "$atomicRecv.value" else atomicRecv
            val findFn = if (rawMethod == "split_once") "indexOf" else "lastIndexOf"
            val argExpr = nonUnitArgs[0]
            val unwrappedArg = unwrapParenOrGroupExpr(argExpr)
            val argTy = inferExprRenderedType(argExpr, selfTypeName)?.removeSuffix("?")?.trim()
            val isCharPat = (unwrappedArg as? Expr.Lit)?.lit is Lit.Char || argTy in setOf("Char", "RustChar")
            return if (isCharPat) {
                val charVal = if (argTy == "RustChar") "(${renderedValueArgs[0]}).toInt().toChar()" else renderedValueArgs[0]
                "$strExpr.let { s -> val idx = s.$findFn($charVal); if (idx < 0) null else Pair(s.substring(0, idx), s.substring(idx + 1)) }"
            } else {
                "$strExpr.let { s -> val d = ${renderedValueArgs[0]}; val idx = s.$findFn(d); if (idx < 0) null else Pair(s.substring(0, idx), s.substring(idx + d.length)) }"
            }
        }
    }
    if ((rawMethod == "to_lowercase" || rawMethod == "to_uppercase") &&
        renderedValueArgs.isEmpty() &&
        typeArgs.isEmpty()
    ) {
        val recvTy =
            (currentTypedLocals[receiver] ?: inferExprRenderedType(expr.receiver, selfTypeName))
                ?.removeSuffix("?")
                ?.trim()
        if (refCellGuardPointeeType(recvTy) == "String") {
            val recvAtom = if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
            val ktFn = if (rawMethod == "to_lowercase") "lowercase" else "uppercase"
            return "$recvAtom.value.$ktFn()"
        }
        if (recvTy == "String" || recvTy == "CharSequence" ||
            (recvTy == null && inspector.instanceMethodsByType.values.none { rustFunctionIdentToKotlin(rawMethod) in it })
        ) {
            val atomicRecv = if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
            val ktFn = if (rawMethod == "to_lowercase") "lowercase" else "uppercase"
            return "$atomicRecv.$ktFn()"
        }
    }
    if (rawMethod == "filter_map" &&
        renderedValueArgs.size == 1 &&
        typeArgs.isEmpty() &&
        inspector.instanceMethodsByType.values.none { "filterMap" in it }
    ) {
        val atomicRecv = if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
        val mappedArg = wrapMapCallbackArg(nonUnitArgs[0], renderedValueArgs[0])
        return "$atomicRecv.mapNotNull($mappedArg)"
    }
    val inferredMethodRecvTy = inferExprRenderedType(expr.receiver, selfTypeName)?.removeSuffix("?")?.trim()
    if (rawMethod == "to_vec" && renderedValueArgs.isEmpty() && typeArgs.isEmpty()) {
        val unwrappedRecvExpr = unwrapTransparentCastExpr(expr.receiver)
        val isRangeSliceExpr = unwrappedRecvExpr is Expr.Index && unwrappedRecvExpr.index is Expr.Range
        val isListOrByteSlice =
            inferredMethodRecvTy?.startsWith("List<") == true ||
                inferredMethodRecvTy?.startsWith("MutableList<") == true ||
                inferredMethodRecvTy?.startsWith("ArrayList<") == true ||
                isRangeSliceExpr ||
                receiver.endsWith(".toBytes()") ||
                receiver.endsWith(".asBytes()") ||
                receiver.endsWith(".asSlice()")
        if (isListOrByteSlice) {
            val atomicRecv = if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
            return "$atomicRecv.toMutableList()"
        }
    }
    if ((rawMethod == "as_slice" || rawMethod == "size_hint") &&
        renderedValueArgs.isEmpty() &&
        typeArgs.isEmpty() &&
        (receiver in currentDequeLocals || isDequeIterReceiver || inferredMethodRecvTy?.startsWith("ArrayDeque<") == true)
    ) {
        val atomicRecv = if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
        if (rawMethod == "as_slice") {
            return "$atomicRecv.toList()"
        }
        if (rawMethod == "size_hint") {
            return "run { val n = $atomicRecv.size; Pair(n, n) }"
        }
    }
    if (inferredMethodRecvTy == "RustAny" && renderedValueArgs.isEmpty() && typeArgs.isNotEmpty()) {
        val atomicRecv = if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
        when (rawMethod) {
            "is" -> return "$atomicRecv.isType$typeArgs()"
            "downcast_ref" -> return "$atomicRecv.downcastRef$typeArgs()"
            "downcast_mut" -> return "$atomicRecv.downcastMut$typeArgs()"
            "downcast" -> return "$atomicRecv.downcast$typeArgs()"
        }
    }
    if (rawMethod == "type_id" &&
        renderedValueArgs.isEmpty() &&
        typeArgs.isEmpty() &&
        "TypeId" !in inspector.declaredTypeNames &&
        "Any" !in inspector.declaredTraitsByName
    ) {
        usesRustAny = true
        val atomicRecv = if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
        return if (inferredMethodRecvTy == "RustAny") {
            "$atomicRecv.typeId"
        } else {
            "RustAny.from($receiver).typeId"
        }
    }
    if (rawMethod == "take" &&
        nonUnitArgs.isEmpty() &&
        typeArgs.isEmpty() &&
        receiver.all { it.isLetterOrDigit() || it == '_' || it == '.' } &&
        (expr.receiver is Expr.Field || (expr.receiver is Expr.Path && receiver != "cell"))
    ) {
        return "$receiver.also { $receiver = null }"
    }
    if (!hasDeclaredOptionEnum() && isKnownNullableOptionExpr(expr.receiver, receiver, selfTypeName) && typeArgs.isEmpty()) {
        val atomicRecv = if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
        when {
            rawMethod == "unwrap" && renderedValueArgs.isEmpty() -> return "$atomicRecv!!"
            rawMethod == "expect" && renderedValueArgs.size == 1 -> return "($receiver ?: error(${renderedValueArgs[0]}))"
            (rawMethod == "clone" || rawMethod == "cloned" || rawMethod == "copied") && renderedValueArgs.isEmpty() ->
                return receiver
            rawMethod == "ok_or" && renderedValueArgs.size == 1 ->
                return if (usesTaggedCoreResult()) {
                    val res = sourceResultName()
                    "($atomicRecv?.let { $res.Ok(it) } ?: $res.Err(${renderedValueArgs[0]}))"
                } else {
                    "($atomicRecv?.let { Result.success(it) } ?: Result.failure(${renderedValueArgs[0]}))"
                }
            rawMethod == "ok_or_else" && renderedValueArgs.size == 1 -> {
                val errExpr = unwrapZeroArgClosureText(renderedValueArgs[0])
                return if (usesTaggedCoreResult()) {
                    val res = sourceResultName()
                    "($atomicRecv?.let { $res.Ok(it) } ?: $res.Err($errExpr))"
                } else {
                    "($atomicRecv?.let { Result.success(it) } ?: Result.failure($errExpr))"
                }
            }
            rawMethod == "into_iter" && renderedValueArgs.isEmpty() -> return "ArrayDeque(listOfNotNull($receiver))"
            rawMethod == "flatten" && renderedValueArgs.isEmpty() -> return receiver
            (rawMethod == "map" || rawMethod == "and_then") && renderedValueArgs.size == 1 ->
                return "$atomicRecv?.let(${wrapMapCallbackArg(nonUnitArgs[0], renderedValueArgs[0])})"
            rawMethod == "map_or" && renderedValueArgs.size == 2 ->
                return "($atomicRecv?.let(${wrapMapCallbackArg(nonUnitArgs[1], renderedValueArgs[1])}) ?: ${renderedValueArgs[0]})"
            rawMethod == "map_or_else" && renderedValueArgs.size == 2 ->
                return "($atomicRecv?.let(${wrapMapCallbackArg(nonUnitArgs[1], renderedValueArgs[1])}) ?: ${unwrapZeroArgClosureText(renderedValueArgs[0])})"
            (rawMethod == "or_else" || rawMethod == "unwrap_or_else") && renderedValueArgs.size == 1 -> {
                val optionElemKind = inferredMethodRecvTy?.let(this::resolveNumericKind)
                val renderedClosure =
                    if (optionElemKind != null) {
                        val prevKind = currentExpectedNumericKind
                        currentExpectedNumericKind = optionElemKind
                        try {
                            renderExpr(nonUnitArgs[0], indent, selfTypeName)
                        } finally {
                            currentExpectedNumericKind = prevKind
                        }
                    } else {
                        renderedValueArgs[0]
                    }
                return "($receiver ?: ${unwrapZeroArgClosureText(renderedClosure)})"
            }
            rawMethod == "unwrap_or" && renderedValueArgs.size == 1 -> {
                val optionElemKind = inferredMethodRecvTy?.let(this::resolveNumericKind)
                val fallbackArg =
                    if (optionElemKind != null) {
                        renderTypedNumericInitializer(nonUnitArgs[0], optionElemKind, indent, selfTypeName)
                    } else {
                        renderedValueArgs[0]
                    }
                return "($receiver ?: $fallbackArg)"
            }
            rawMethod == "or" && renderedValueArgs.size == 1 -> {
                val optionElemKind = inferredMethodRecvTy?.let(this::resolveNumericKind)
                val fallbackOptArg =
                    if (optionElemKind != null) {
                        val prevKind = currentExpectedNumericKind
                        currentExpectedNumericKind = optionElemKind
                        try {
                            renderExpr(nonUnitArgs[0], indent, selfTypeName)
                        } finally {
                            currentExpectedNumericKind = prevKind
                        }
                    } else {
                        renderedValueArgs[0]
                    }
                return "($receiver ?: $fallbackOptArg)"
            }
        }
    }
    if ((cleanMethod == "fold" || cleanMethod == "rfold") &&
        typeArgs.isEmpty() &&
        nonUnitArgs.size == 2 &&
        renderedValueArgs.size == 2 &&
        renderedValueArgs[0] == "null"
    ) {
        val atomicRecv = if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
        val closureExpr = nonUnitArgs[1] as? Expr.Closure
        val inferredBodyTy =
            closureExpr
                ?.let { inferExprRenderedType(it.body, selfTypeName) }
                ?.removeSuffix("?")
                ?.trim()
                ?.takeIf { it.isNotEmpty() && it != "Nothing" && it != "Any" && !it.startsWith("Pair<Nothing") }
        val inferredRecvElemTy =
            inferExprRenderedType(effectiveReceiverExpr, selfTypeName)
                ?.let { extractIndexedElementRenderedType(it, selfTypeName) }
                ?.removeSuffix("?")
                ?.trim()
                ?.takeIf { it.isNotEmpty() && it != "Nothing" && it != "Any" }
        val renderedClosure = renderedValueArgs[1].trim()
        if (renderedClosure.startsWith("{") && renderedClosure.endsWith("}") && "->" in renderedClosure) {
            val innerClosure = renderedClosure.substring(1, renderedClosure.length - 1).trim()
            val header = innerClosure.substringBefore("->").trim()
            val body = innerClosure.substringAfter("->").trim()
            if ('<' !in header && ':' !in header && '{' !in header && '(' !in header) {
                val headerParams = header.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                if (headerParams.size == 2) {
                    val accParam = headerParams[0]
                    val itemParam = headerParams[1]
                    val accUsed =
                        accParam != "_" &&
                            Regex("\\b${Regex.escape(accParam)}\\b").containsMatchIn(body)
                    if (!accUsed) {
                        if (body == itemParam) {
                            return if (cleanMethod == "fold") "$atomicRecv.lastOrNull()" else "$atomicRecv.firstOrNull()"
                        }
                        if (inferredBodyTy == null) {
                            return if (cleanMethod == "fold") {
                                "$atomicRecv.map({ $itemParam -> $body }).lastOrNull()"
                            } else {
                                "$atomicRecv.asReversed().map({ $itemParam -> $body }).lastOrNull()"
                            }
                        }
                    }
                }
            }
        }
        val accTy = inferredBodyTy ?: inferredRecvElemTy
        if (accTy != null) {
            val typedArgs = (macroTypeArgs + listOf("null as $accTy?", renderedValueArgs[1])).joinToString(", ")
            return "$receiver.$method($typedArgs)"
        }
    }
    val adjustedRenderedValueArgs =
        if ((rawMethod == "peek" || rawMethod == "peek2" || rawMethod == "peek3") &&
            nonUnitArgs.size == 1 &&
            nonUnitArgs[0] is Expr.Macro &&
            renderedValueArgs.size == 1
        ) {
            listOf(renderedValueArgs[0].removeSuffix(".default()"))
        } else if (rawMethod in setOf("map", "map_err", "and_then", "or_else") &&
            nonUnitArgs.size == 1 &&
            renderedValueArgs.size == 1
        ) {
            listOf(wrapMapCallbackArg(nonUnitArgs[0], renderedValueArgs[0]))
        } else {
            renderedValueArgs
        }
    val valueArgs = macroTypeArgs + adjustedRenderedValueArgs
    val args = valueArgs.joinToString(", ")
    if ("core" in headerContext.crateNamespaces &&
        typeArgs.isEmpty() &&
        method in setOf("asPtr", "asMutPtr", "chunksMut", "chunks")
    ) {
        val recvCheckExpr =
            (unwrapParenOrGroupExpr(expr.receiver) as? Expr.Reference)?.let { unwrapParenOrGroupExpr(it.expr) }
                ?: expr.receiver
        val recvType = inferExprRenderedType(recvCheckExpr, selfTypeName)?.removeSuffix("?")?.trim()
        val recvOwnerSimple = recvType?.substringBefore('<')?.substringAfterLast('.')?.trim()
        val hasUserMethod = recvOwnerSimple != null && method in inspector.instanceMethodsByType[recvOwnerSimple].orEmpty()
        val isStorageOrMaybeUninit =
            receiver.startsWith("MaybeUninit.") ||
                unwrapMaybeUninitRenderedInner(recvType) != null ||
                receiver in currentAddressableLocals.values
        val isNonSliceCoreType =
            recvOwnerSimple in setOf("MaybeUninit", "ConstPtr", "MutPtr", "AddressableLocal", "AddressableArrayLocal")
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
        if (!isStorageOrMaybeUninit &&
            !hasUserMethod &&
            !isNonSliceCoreType &&
            (recvType == null || isKnownSliceOrArrayType)
        ) {
            val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
            val importPkg =
                when {
                    isPrimitiveArrayReceiver -> "mem"
                    isStringAsPtrReceiver -> "str"
                    else -> "slice"
                }
            if (headerContext.packageName != "$coreNs.$importPkg") {
                CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.$importPkg.$method")
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
        if (recvKind == NumericKind.U128) {
            usesU128 = true
        } else if (recvKind == NumericKind.I128) {
            usesI128 = true
        }
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
