package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.procmacro2.TokenTree
import io.github.kotlinmania.syn.Expr
import io.github.kotlinmania.syn.GenericArgument
import io.github.kotlinmania.syn.Item
import io.github.kotlinmania.syn.Meta
import io.github.kotlinmania.syn.Path
import io.github.kotlinmania.syn.PathArguments
import io.github.kotlinmania.syn.QSelf
import io.github.kotlinmania.syn.RangeLimits
import io.github.kotlinmania.syn.SynType
internal fun KotlinJ2KEmitter.unwrapTryIntoUnwrapExpr(expr: Expr): Expr? {
    val outer = unwrapParenOrGroupExpr(expr) as? Expr.MethodCall ?: return null
    val outerMethod = outer.method.toString().removePrefix("r#")
    val outerArgsOk =
        (outerMethod == "unwrap" && outer.args.isEmpty()) ||
            (outerMethod == "expect" && outer.args.toList().size == 1)
    if (!outerArgsOk) return null
    val inner = unwrapParenOrGroupExpr(outer.receiver) as? Expr.MethodCall ?: return null
    val innerMethod = inner.method.toString().removePrefix("r#")
    if (innerMethod != "try_into" || !inner.args.isEmpty()) return null
    return inner.receiver
}

internal fun KotlinJ2KEmitter.transparentPointerField(type: String, selfTypeName: String?): Pair<String, String>? {
    val owner = type.substringBefore('<').substringAfterLast('.')
    val struct =
        file.items.filterIsInstance<Item.Struct>().singleOrNull {
            rustTypeIdentToKotlin(it.ident.toString()) == owner
        } ?: return null
    if (!struct.attrs.any { attr ->
            val meta = attr.meta as? Meta.List
            meta != null && meta.path.isIdent("repr") &&
                meta.tokens.clone().toList().any {
                    it is TokenTree.Ident && it.value.toString() == "transparent"
                }
        }
    ) {
        return null
    }
    val params = renderTypeArgumentsFromGenerics(struct.generics)
    if (type.substringAfterLast('.') != owner + params) return null
    val field = inspector.structFieldSynTypesByType[owner]?.entries?.singleOrNull() ?: return null
    if (field.value !is SynType.Ptr) return null
    return rustFieldIdentToKotlin(field.key) to renderType(field.value, selfTypeName)
}

internal fun KotlinJ2KEmitter.resolveRawPointerPointeeLayout(ptrExpr: Expr, selfTypeName: String?): String {
    (sourceInitializerType(ptrExpr) as? SynType.Ptr)?.elem?.let { pointeeSyn ->
        return CoreIntrinsicLowering.resolveTypeLayoutExpression(pointeeSyn, selfTypeName, this)
    }
    val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
    val memPkg = "$coreNs.mem"
    val pointeeKt =
        rawPointerPointeeType(inferExprRenderedType(ptrExpr, selfTypeName))?.trim()
            ?: run {
                CoreIntrinsicLowering.registerCoreImport(this, "$memPkg.PrimitiveLayouts")
                return "PrimitiveLayouts.U8"
            }
    return resolveRenderedTypeLayoutExpr(pointeeKt)
}

private val PRIMITIVE_LAYOUT_NAME_BY_KT_TYPE: Map<String, String> =
    mapOf(
        "Boolean" to "BOOL",
        "Byte" to "I8",
        "UByte" to "U8",
        "CVoid" to "U8",
        "Short" to "I16",
        "UShort" to "U16",
        "Int" to "I32",
        "UInt" to "U32",
        "Long" to "I64",
        "ULong" to "U64",
        "Float" to "F32",
        "kotlin.Float" to "F32",
        "Double" to "F64",
        "F16" to "F16_LAYOUT",
        "F128" to "F128_LAYOUT",
        "Char" to "CHAR",
        "Unit" to "UNIT",
    )

internal fun KotlinJ2KEmitter.resolveRenderedTypeLayoutExpr(targetKtRaw: String): String {
    val pointeeKt = targetKtRaw.trim().ifEmpty { "UByte" }
    val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
    val memPkg = "$coreNs.mem"
    val expectedLayoutTypes = setOf("TypeLayout<$pointeeKt>", "$memPkg.TypeLayout<$pointeeKt>")
    currentTypedLocals
        .filterValues { it in expectedLayoutTypes }
        .keys
        .singleOrNull()
        ?.let { return it }
    val candidateParam = StructLayoutComputer.typeLayoutParamName(pointeeKt)
    if (candidateParam in currentTypedLocals) return candidateParam
    PRIMITIVE_LAYOUT_NAME_BY_KT_TYPE[pointeeKt]?.let { layoutConst ->
        CoreIntrinsicLowering.registerCoreImport(this, "$memPkg.PrimitiveLayouts")
        return "PrimitiveLayouts.$layoutConst"
    }
    rawPointerPointeeType(pointeeKt)?.let { innerPointee ->
        CoreIntrinsicLowering.registerCoreImport(this, "$memPkg.PrimitiveLayouts")
        val factory = if (isMutRawPointerRenderedType(pointeeKt)) "mutPtrLayout" else "constPtrLayout"
        return "PrimitiveLayouts.$factory<$innerPointee>(${activeCfgProfile.pointerWidth}L)"
    }
    val structSimple = pointeeKt.substringBefore('<').substringAfterLast('.')
    val structDecl =
        sameFileLayoutItems()
            .filterIsInstance<Item.Struct>()
            .singleOrNull { rustTypeIdentToKotlin(it.ident.toString()) == structSimple }
    if (structDecl != null) {
        val computed = StructLayoutComputer.computeStructLayout(structDecl, this)
        if (computed != null && computed.requiredTypeLayoutParams.isEmpty()) {
            return "$structSimple.typeLayout()"
        }
    }
    CoreIntrinsicLowering.registerCoreImport(this, "$memPkg.UnresolvedTypeLayout")
    return "UnresolvedTypeLayout<$pointeeKt>()"
}

internal fun renderPrimitiveByteSwapExpr(renderedOperand: String, kind: NumericKind): String {
    val isSimpleLocal = renderedOperand.isNotEmpty() && renderedOperand.all { it.isLetterOrDigit() }
    return if (isSimpleLocal) {
        renderPrimitiveByteSwapBody(renderedOperand, kind)
    } else {
        "run { val swapVal0 = ($renderedOperand); ${renderPrimitiveByteSwapBody("swapVal0", kind)} }"
    }
}

private fun renderPrimitiveByteSwapBody(v: String, kind: NumericKind): String =
    when (kind) {
        NumericKind.I8, NumericKind.U8 -> v
        NumericKind.U16 ->
            "((((($v).toUInt() and 0xFFu) shl 8) or ((($v).toUInt() shr 8) and 0xFFu)).toUShort())"
        NumericKind.I16 ->
            "((((($v).toInt() and 0xFF) shl 8) or ((($v).toInt() ushr 8) and 0xFF)).toShort())"
        NumericKind.U32 ->
            "(((($v) shl 24)) or ((($v) and 0x0000FF00u) shl 8) or ((($v) shr 8) and 0x0000FF00u) or (($v) shr 24))"
        NumericKind.I32 ->
            "(((($v) shl 24)) or ((($v) and 0x0000FF00) shl 8) or ((($v) ushr 8) and 0x0000FF00) or (($v) ushr 24))"
        NumericKind.U64 ->
            "(((($v) shl 56)) or ((($v) and 0x000000000000FF00uL) shl 40) or ((($v) and 0x0000000000FF0000uL) shl 24) or ((($v) and 0x00000000FF000000uL) shl 8) or (((($v) shr 8) and 0x00000000FF000000uL)) or (((($v) shr 24) and 0x0000000000FF0000uL)) or (((($v) shr 40) and 0x000000000000FF00uL)) or (($v) shr 56))"
        NumericKind.I64 ->
            "((((($v).toULong() shl 56)) or ((($v).toULong() and 0x000000000000FF00uL) shl 40) or ((($v).toULong() and 0x0000000000FF0000uL) shl 24) or ((($v).toULong() and 0x00000000FF000000uL) shl 8) or (((($v).toULong() shr 8) and 0x00000000FF000000uL)) or (((($v).toULong() shr 24) and 0x0000000000FF0000uL)) or (((($v).toULong() shr 40) and 0x000000000000FF00uL)) or (($v).toULong() shr 56)).toLong())"
        else -> v
    }

internal fun KotlinJ2KEmitter.tryExtractMutableArraySubsliceArg(
    argExpr: Expr,
    argIndex: Int,
    expectMutableList: () -> Boolean,
    indent: String,
    selfTypeName: String?,
): Triple<String, String, String>? {
    if (headerContext.rawAbiMode) return null
    val refExpr = unwrapParenOrGroupExpr(argExpr) as? Expr.Reference ?: return null
    if (refExpr.mutability == null) return null
    val place = unwrapParenOrGroupExpr(refExpr.expr) as? Expr.Index ?: return null
    val rangeExpr = unwrapParenOrGroupExpr(place.index) as? Expr.Range ?: return null
    if (rangeExpr.start == null && rangeExpr.end == null) return null
    val baseTy = inferExprRenderedType(place.expr, selfTypeName)?.removeSuffix("?")?.trim() ?: return null
    val isKotlinArray =
        baseTy in PRIMITIVE_ARRAY_TYPES ||
            baseTy == "CharArray" ||
            baseTy.startsWith("Array<")
    if (!isKotlinArray) return null
    val needsMutableList = expectMutableList()
    val base = renderExpr(place.expr, indent, selfTypeName)
    val baseAtom = if (isAtomicPostfixReceiver(place.expr, base)) base else "($base)"
    val start = rangeExpr.start?.let { renderTypedNumericInitializer(it, NumericKind.I32, indent, selfTypeName) }
    val end = rangeExpr.end?.let { renderTypedNumericInitializer(it, NumericKind.I32, indent, selfTypeName) }
    val op = if (rangeExpr.limits is RangeLimits.Closed) ".." else " until "
    val rangeStr =
        when {
            start != null && end != null -> "$start$op$end"
            start != null -> "$start until $baseAtom.size"
            else -> "0$op$end"
        }
    val startOffset = start ?: "0"
    val subVar = "subSlice$argIndex"
    return if (needsMutableList) {
        val idxVar = "subIdx$argIndex"
        val destIdx = if (startOffset == "0") idxVar else "($startOffset) + $idxVar"
        Triple(
            "val $subVar = $baseAtom.slice($rangeStr).toMutableList()",
            subVar,
            "for ($idxVar in $subVar.indices) $baseAtom[$destIdx] = $subVar[$idxVar]",
        )
    } else {
        Triple(
            "val $subVar = $baseAtom.sliceArray($rangeStr)",
            subVar,
            "$subVar.copyInto($baseAtom, destinationOffset = $startOffset)",
        )
    }
}

private fun KotlinJ2KEmitter.unwrapPtrCastChainToInnerRef(
    expr: Expr,
    selfTypeName: String?,
): Expr? {
    var cur = unwrapParenOrGroupExpr(expr)
    var ptrCastCount = 0
    var innermostPtrElem: SynType? = null
    while (cur is Expr.Cast) {
        val castTy = unwrapSynType(cur.ty)
        if (castTy !is SynType.Ptr) return null
        ptrCastCount++
        innermostPtrElem = unwrapSynType(castTy.elem)
        cur = unwrapParenOrGroupExpr(cur.expr)
    }
    (cur as? Expr.Reference)?.let { return unwrapParenOrGroupExpr(it.expr) }
    if (ptrCastCount >= 2 &&
        innermostPtrElem != null &&
        rawPointerPointeeType(inferExprRenderedType(cur, selfTypeName)) == null
    ) {
        val innermostKind = resolveNumericKind(renderType(innermostPtrElem, selfTypeName))
        val curKind = inferExprNumericKind(cur, selfTypeName)
        if (innermostKind != null && curKind == innermostKind) {
            return cur
        }
    }
    return null
}

internal fun KotlinJ2KEmitter.renderUnOpDerefPointerPunning(
    expr: Expr.Unary,
    indent: String,
    selfTypeName: String?,
): String? {
    val outerCast = unwrapParenOrGroupExpr(expr.expr) as? Expr.Cast ?: return null
    val dstPtr = unwrapSynType(outerCast.ty) as? SynType.Ptr ?: return null
    val dstPointee = unwrapSynType(dstPtr.elem)
    val srcInner = unwrapPtrCastChainToInnerRef(outerCast, selfTypeName) ?: return null
    if (dstPointee is SynType.Array) {
        val elemPath = unwrapSynType(dstPointee.elem) as? SynType.Path ?: return null
        val elemRaw =
            elemPath.path.segments
                .last()
                ?.ident
                ?.toString()
                ?.removePrefix("r#")
        if ((elemRaw == "u8" || elemRaw == "c_uchar") && "core" in headerContext.crateNamespaces) {
            val srcKind = inferExprNumericKind(srcInner, selfTypeName)
            if (srcKind != null) {
                val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
                val subPkg =
                    when (srcKind) {
                        NumericKind.F16 -> ".num.f16"
                        NumericKind.F32 -> ".num.f32"
                        NumericKind.F64 -> ".num.f64"
                        NumericKind.F128 -> ".num.f128"
                        else -> ".num"
                    }
                val pkg = "$coreNs$subPkg"
                if (headerContext.packageName == pkg && currentEnclosingFunctionName == "toNeBytes") {
                    return null
                }
                if (headerContext.packageName != pkg) {
                    CoreIntrinsicLowering.registerCoreImport(this, "$pkg.toNeBytes")
                }
                val rval = renderExpr(srcInner, indent, selfTypeName)
                val atom = if (isAtomicPostfixReceiver(srcInner, rval)) rval else "($rval)"
                return "$atom.toNeBytes()"
            }
        }
        return null
    }
    val dstKind = resolveNumericKind(renderType(dstPointee, selfTypeName)) ?: return null
    val srcKind = inferExprNumericKind(srcInner, selfTypeName) ?: return null
    val rval = renderExpr(srcInner, indent, selfTypeName)
    val atom = if (isAtomicPostfixReceiver(srcInner, rval)) rval else "($rval)"
    return when {
        srcKind == dstKind -> rval
        srcKind == NumericKind.F16 && dstKind == NumericKind.U16 -> "$atom.toBits()"
        srcKind == NumericKind.F16 && dstKind == NumericKind.I16 -> "$atom.toBits().toShort()"
        srcKind == NumericKind.U16 && dstKind == NumericKind.F16 -> "F16.fromBits($rval)"
        srcKind == NumericKind.I16 && dstKind == NumericKind.F16 -> "F16.fromBits($atom.toUShort())"
        srcKind == NumericKind.F32 && dstKind == NumericKind.U32 -> "$atom.toRawBits().toUInt()"
        srcKind == NumericKind.F32 && dstKind == NumericKind.I32 -> "$atom.toRawBits()"
        srcKind == NumericKind.F64 && dstKind == NumericKind.U64 -> "$atom.toRawBits().toULong()"
        srcKind == NumericKind.F64 && dstKind == NumericKind.I64 -> "$atom.toRawBits()"
        srcKind == NumericKind.F128 && dstKind == NumericKind.U128 -> {
            usesU128 = true
            "U128($atom.high, $atom.low)"
        }
        srcKind == NumericKind.F128 && dstKind == NumericKind.I128 -> {
            usesI128 = true
            "I128($atom.high.toLong(), $atom.low)"
        }
        srcKind == NumericKind.U128 && dstKind == NumericKind.F128 -> "F128.fromBits($rval)"
        srcKind == NumericKind.I128 && dstKind == NumericKind.F128 -> "F128.fromBits($atom.high.toULong(), $atom.low)"
        srcKind == NumericKind.U32 && dstKind == NumericKind.F32 -> "${kotlinF32TypeName()}.fromBits($atom.toInt())"
        srcKind == NumericKind.I32 && dstKind == NumericKind.F32 -> "${kotlinF32TypeName()}.fromBits($rval)"
        srcKind == NumericKind.U64 && dstKind == NumericKind.F64 -> "Double.fromBits($atom.toLong())"
        srcKind == NumericKind.I64 && dstKind == NumericKind.F64 -> "Double.fromBits($rval)"
        else -> null
    }
}

internal fun KotlinJ2KEmitter.renderFloatToBitsExpr(
    recvExpr: Expr,
    renderedRecv: String,
    recvKind: NumericKind,
): String? {
    val recvAtom = atomicRecvExpr(recvExpr, renderedRecv)
    return when (recvKind) {
        NumericKind.F16, NumericKind.F128 -> "$recvAtom.toBits()"
        NumericKind.F32 -> "$recvAtom.toRawBits().toUInt()"
        NumericKind.F64 -> "$recvAtom.toRawBits().toULong()"
        else -> null
    }
}

internal fun KotlinJ2KEmitter.tryRender128BitEndianMethodCall(
    rawMethod: String,
    primKind: NumericKind,
    recvExpr: Expr,
    renderedRecv: String,
    guardSelfRecursion: Boolean,
): String? {
    if ((primKind != NumericKind.U128 && primKind != NumericKind.I128) ||
        "core" !in headerContext.crateNamespaces ||
        rawMethod !in setOf("to_be", "from_be", "swap_bytes", "to_le", "from_le")
    ) {
        return null
    }
    val ktMethod =
        when (rawMethod) {
            "to_be" -> "toBe"
            "from_be" -> "fromBe"
            "swap_bytes" -> "swapBytes"
            "to_le" -> "toLe"
            else -> "fromLe"
        }
    val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
    if (guardSelfRecursion && headerContext.packageName == "$coreNs.num" && currentEnclosingFunctionName == ktMethod) {
        return null
    }
    registerCoreMemberImport("num", ktMethod, skipIfSamePackage = true)
    if (ktMethod == "fromBe" || ktMethod == "fromLe") {
        val ktType = if (primKind == NumericKind.U128) "U128" else "I128"
        registerCoreMemberImport("", ktType, skipIfSamePackage = true)
        return "$ktType.$ktMethod($renderedRecv)"
    }
    val recvAtom = atomicRecvExpr(recvExpr, renderedRecv)
    return "$recvAtom.$ktMethod()"
}

private fun KotlinJ2KEmitter.renderFloatFromBitsArg(
    argExpr: Expr,
    renderExpectKind: NumericKind,
    castKind: NumericKind,
    targetOwner: String,
    indent: String,
    selfTypeName: String?,
): String {
    val saved = currentExpectedNumericKind
    currentExpectedNumericKind = renderExpectKind
    val argRendered =
        try {
            renderExpr(argExpr, indent, selfTypeName)
        } finally {
            currentExpectedNumericKind = saved
        }
    val bitsExpr =
        if (inferExprNumericKind(argExpr, selfTypeName) == castKind) {
            argRendered
        } else {
            coerceRenderedNumericExpr(argExpr, argRendered, castKind)
        }
    return "$targetOwner.fromBits($bitsExpr)"
}

internal data class StaticPrimitiveCallSpec(
    val primKind: NumericKind,
    val methodName: String,
    val turbofishType: SynType?,
)

internal fun KotlinJ2KEmitter.rustPrimitivePathNameToKind(raw: String): NumericKind? {
    var cur = raw.removePrefix("r#")
    val visited = hashSetOf<String>()
    repeat(5) {
        when (cur) {
            "i8" -> return NumericKind.I8
            "u8" -> return NumericKind.U8
            "i16" -> return NumericKind.I16
            "u16" -> return NumericKind.U16
            "i32" -> return NumericKind.I32
            "u32" -> return NumericKind.U32
            "i64" -> return NumericKind.I64
            "u64" -> return NumericKind.U64
            "i128" -> return NumericKind.I128
            "u128" -> return NumericKind.U128
            "f16" -> return NumericKind.F16
            "f32" -> return NumericKind.F32
            "f64" -> return NumericKind.F64
            "f128" -> return NumericKind.F128
            "isize" -> return resolvePointerSizedNumericKind(signed = true)
            "usize" -> return resolvePointerSizedNumericKind(signed = false)
        }
        if (!visited.add(cur)) return null
        val aliasSyn =
            inspector.typeAliasTargets[cur]
                ?: inspector.typeAliasTargets[rustTypeIdentToKotlin(cur)]
                ?: return null
        val pathTy = unwrapSynType(aliasSyn) as? SynType.Path ?: return null
        if (pathTy.qself != null || pathTy.path.segments.len() != 1) return null
        val nextSeg = pathTy.path.segments.first() ?: return null
        if (nextSeg.arguments !is PathArguments.None) return null
        cur = nextSeg.ident.toString().removePrefix("r#")
    }
    return null
}

internal fun KotlinJ2KEmitter.resolvePrimitiveAssociatedConstKind(
    path: Path,
    qself: QSelf?,
): NumericKind? {
    val segs = path.segments.toList().map { it.ident.toString().removePrefix("r#") }
    if (segs.isEmpty()) return null
    val allowedPrefixes = IGNORED_PATH_PREFIXES + setOf("core", "std", "primitive")
    val primKind =
        when {
            qself != null -> {
                if (segs.size != 1) return null
                val qPath = unwrapSynType(qself.ty) as? SynType.Path ?: return null
                val qSegs =
                    qPath.path.segments
                        .toList()
                        .map { it.ident.toString().removePrefix("r#") }
                if (qSegs.isEmpty() || !qSegs.dropLast(1).all { it in allowedPrefixes }) return null
                rustPrimitivePathNameToKind(qSegs.last())
            }
            segs.size >= 3 && segs[segs.size - 2] == "consts" && segs.dropLast(3).all { it in allowedPrefixes } ->
                rustPrimitivePathNameToKind(segs[segs.size - 3])?.takeIf {
                    it == NumericKind.F16 || it == NumericKind.F32 || it == NumericKind.F64 || it == NumericKind.F128
                }
            segs.size >= 2 && segs.dropLast(2).all { it in allowedPrefixes } ->
                rustPrimitivePathNameToKind(segs[segs.size - 2])
            else -> null
        } ?: return null
    return when (segs.last()) {
        "BITS", "RADIX", "MANTISSA_DIGITS", "DIGITS" -> NumericKind.U32
        "MIN_EXP", "MAX_EXP", "MIN_10_EXP", "MAX_10_EXP" -> NumericKind.I32
        else -> primKind
    }
}

internal fun KotlinJ2KEmitter.parseStaticPrimitiveCallSpec(expr: Expr.Call): StaticPrimitiveCallSpec? {
    val fnPath = expr.func as? Expr.Path ?: return null
    val segs = fnPath.path.segments.toList()
    if (segs.isEmpty()) return null
    val allowedPrefixes = IGNORED_PATH_PREFIXES + setOf("core", "std", "primitive")
    val turbofishType =
        (segs.last().arguments as? PathArguments.AngleBracketed)
            ?.args
            ?.toList()
            ?.firstNotNullOfOrNull { (it as? GenericArgument.TypeArg)?.type }
    val rawSegs = segs.map { it.ident.toString().removePrefix("r#") }
    val methodName = rawSegs.last()
    val primKind =
        if (fnPath.qself != null) {
            if (rawSegs.size != 1) return null
            val qPath = unwrapSynType(fnPath.qself!!.ty) as? SynType.Path ?: return null
            val qSegs =
                qPath.path.segments
                    .toList()
                    .map { it.ident.toString().removePrefix("r#") }
            if (qSegs.isEmpty() || !qSegs.dropLast(1).all { it in allowedPrefixes }) return null
            rustPrimitivePathNameToKind(qSegs.last()) ?: return null
        } else {
            if (rawSegs.size < 2 || !rawSegs.dropLast(2).all { it in allowedPrefixes }) return null
            rustPrimitivePathNameToKind(rawSegs[rawSegs.size - 2]) ?: return null
        }
    return StaticPrimitiveCallSpec(primKind = primKind, methodName = methodName, turbofishType = turbofishType)
}

internal fun KotlinJ2KEmitter.inferPrimitiveOpReturnRenderedType(
    recvKind: NumericKind,
    rawMethod: String,
    turbofishType: SynType?,
    selfTypeName: String?,
): String? {
    val isCoreAvailable = "core" in headerContext.crateNamespaces
    if (!isCoreAvailable) {
        return if (recvKind == NumericKind.I32 &&
            rawMethod in setOf("checked_add", "checked_sub", "checked_mul") &&
            turbofishType == null
        ) {
            "Int?"
        } else {
            null
        }
    }
    val primTy = numericKindToPureTypeName(recvKind)
    val isFloat =
        recvKind == NumericKind.F16 ||
            recvKind == NumericKind.F32 ||
            recvKind == NumericKind.F64 ||
            recvKind == NumericKind.F128
    return when {
        !isFloat &&
            rawMethod in
            setOf(
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
                "checked_neg",
                "checked_abs",
            ) -> "$primTy?"
        !isFloat &&
            rawMethod in
            setOf(
                "overflowing_add",
                "overflowing_sub",
                "overflowing_mul",
                "overflowing_shl",
                "overflowing_shr",
                "overflowing_neg",
                "overflowing_abs",
            ) -> "Pair<$primTy, Boolean>"
        !isFloat &&
            rawMethod in
            setOf(
                "wrapping_add",
                "wrapping_sub",
                "wrapping_mul",
                "wrapping_div",
                "wrapping_rem",
                "wrapping_shl",
                "wrapping_shr",
                "wrapping_neg",
                "wrapping_abs",
                "saturating_add",
                "saturating_sub",
                "saturating_mul",
                "saturating_neg",
                "saturating_abs",
                "div_euclid",
                "rem_euclid",
                "pow",
                "reverse_bits",
                "rotate_left",
                "rotate_right",
            ) -> primTy
        !isFloat &&
            rawMethod in
            setOf(
                "count_ones",
                "count_zeros",
                "leading_zeros",
                "trailing_zeros",
                "leading_ones",
                "trailing_ones",
            ) -> "UInt"
        isFloat &&
            rawMethod in
            setOf(
                "is_nan",
                "is_infinite",
                "is_finite",
                "is_normal",
                "is_subnormal",
                "is_sign_positive",
                "is_sign_negative",
            ) -> "Boolean"
        isFloat && rawMethod == "classify" -> "FpCategory"
        isFloat && rawMethod == "to_int_unchecked" ->
            turbofishType?.let { renderType(it, selfTypeName) }
        isFloat &&
            rawMethod in
            setOf(
                "abs",
                "floor",
                "ceil",
                "round",
                "trunc",
                "sqrt",
                "exp",
                "exp2",
                "ln",
                "log",
                "log2",
                "log10",
                "cbrt",
                "sin",
                "cos",
                "tan",
                "asin",
                "acos",
                "atan",
                "atan2",
                "hypot",
                "sinh",
                "cosh",
                "tanh",
                "asinh",
                "acosh",
                "atanh",
                "powf",
                "powi",
                "min",
                "max",
                "fract",
                "mul_add",
                "recip",
                "to_degrees",
                "to_radians",
                "signum",
            ) -> primTy
        else -> null
    }
}

internal fun KotlinJ2KEmitter.renderStaticPrimitiveCall(
    expr: Expr.Call,
    nonUnitArgs: List<Expr>,
    indent: String,
    selfTypeName: String?,
): String? {
    val spec = parseStaticPrimitiveCallSpec(expr) ?: return null
    val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
    if (spec.methodName == "from_bits" && spec.turbofishType == null && nonUnitArgs.size == 1) {
        when (spec.primKind) {
            NumericKind.F16 ->
                return renderFloatFromBitsArg(nonUnitArgs[0], NumericKind.U16, NumericKind.U16, "F16", indent, selfTypeName)
            NumericKind.F32 ->
                return renderFloatFromBitsArg(nonUnitArgs[0], NumericKind.U32, NumericKind.I32, kotlinF32TypeName(), indent, selfTypeName)
            NumericKind.F64 ->
                return renderFloatFromBitsArg(nonUnitArgs[0], NumericKind.U64, NumericKind.I64, "Double", indent, selfTypeName)
            NumericKind.F128 -> {
                usesU128 = true
                return renderFloatFromBitsArg(nonUnitArgs[0], NumericKind.U128, NumericKind.U128, "F128", indent, selfTypeName)
            }
            else -> Unit
        }
    }
    if (spec.methodName == "from_str_radix" &&
        spec.turbofishType == null &&
        nonUnitArgs.size == 2 &&
        "core" in headerContext.crateNamespaces
    ) {
        if (spec.primKind in setOf(NumericKind.F16, NumericKind.F32, NumericKind.F64, NumericKind.F128)) {
            return null
        }
        val ktType = numericKindToPureTypeName(spec.primKind)
        if (headerContext.packageName == "$coreNs.num" && currentEnclosingFunctionName == "fromStrRadix") {
            return null
        }
        if (headerContext.packageName != "$coreNs.num") {
            CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.num.fromStrRadix")
        }
        if (spec.primKind == NumericKind.I128 || spec.primKind == NumericKind.U128) {
            if (headerContext.packageName != coreNs) {
                CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.$ktType")
            }
        }
        usesSourceResult = true
        val savedKind = currentExpectedNumericKind
        val savedParse = currentExpectedParseTargetType
        val srcRendered =
            try {
                currentExpectedNumericKind = null
                currentExpectedParseTargetType = "String"
                renderExpr(nonUnitArgs[0], indent, selfTypeName)
            } finally {
                currentExpectedNumericKind = savedKind
                currentExpectedParseTargetType = savedParse
            }
        val radixRendered =
            try {
                currentExpectedNumericKind = NumericKind.U32
                currentExpectedParseTargetType = null
                renderExpr(nonUnitArgs[1], indent, selfTypeName)
            } finally {
                currentExpectedNumericKind = savedKind
                currentExpectedParseTargetType = savedParse
            }
        val radixArg = coerceNumericArgIfNeeded(nonUnitArgs[1], radixRendered, NumericKind.U32, selfTypeName)
        return "$ktType.fromStrRadix($srcRendered, $radixArg)"
    }
    if (nonUnitArgs.isEmpty()) return null
    val savedKind = currentExpectedNumericKind
    val recvRendered =
        try {
            currentExpectedNumericKind = spec.primKind
            renderExpr(nonUnitArgs[0], indent, selfTypeName)
        } finally {
            currentExpectedNumericKind = savedKind
        }
    val extraExprs = nonUnitArgs.drop(1)
    val extraExpectedKind =
        when {
            spec.methodName in setOf("wrapping_shl", "wrapping_shr", "checked_shl", "checked_shr", "overflowing_shl", "overflowing_shr", "rotate_left", "rotate_right", "pow", "checked_pow") ->
                NumericKind.U32
            spec.methodName == "powi" -> NumericKind.I32
            else -> spec.primKind
        }
    val extraRendered =
        try {
            currentExpectedNumericKind = extraExpectedKind
            extraExprs.map { renderExpr(it, indent, selfTypeName) }
        } finally {
            currentExpectedNumericKind = savedKind
        }
    if (spec.methodName == "to_bits" && spec.turbofishType == null && extraExprs.isEmpty()) {
        renderFloatToBitsExpr(nonUnitArgs[0], recvRendered, spec.primKind)?.let { return it }
    }
    if (spec.turbofishType == null && extraExprs.isEmpty()) {
        if (spec.methodName in setOf("to_be", "from_be", "swap_bytes", "to_le", "from_le") &&
            (spec.primKind == NumericKind.U128 || spec.primKind == NumericKind.I128) &&
            "core" in headerContext.crateNamespaces
        ) {
            return tryRender128BitEndianMethodCall(
                rawMethod = spec.methodName,
                primKind = spec.primKind,
                recvExpr = nonUnitArgs[0],
                renderedRecv = recvRendered,
                guardSelfRecursion = true,
            )
        }
    }
    return renderConcretePrimitiveOp(
        recvKind = spec.primKind,
        rawMethod = spec.methodName,
        recvExpr = nonUnitArgs[0],
        renderedRecv = recvRendered,
        extraExprs = extraExprs,
        renderedExtras = extraRendered,
        turbofishType = spec.turbofishType,
        selfTypeName = selfTypeName,
    )
}

internal fun KotlinJ2KEmitter.renderConcreteNumericMethodCall(
    expr: Expr.MethodCall,
    receiver: String,
    rawMethod: String,
    nonUnitArgs: List<Expr>,
    renderedValueArgs: List<String>,
    typeArgs: String,
    indent: String,
    selfTypeName: String?,
): String? {
    val recvKind = inferExprNumericKind(expr.receiver, selfTypeName) ?: return null
    val tfType =
        expr.turbofish
            ?.args
            ?.toList()
            ?.firstNotNullOfOrNull { (it as? GenericArgument.TypeArg)?.type }
    if (typeArgs.isNotEmpty() && rawMethod != "to_int_unchecked") return null
    return renderConcretePrimitiveOp(
        recvKind = recvKind,
        rawMethod = rawMethod,
        recvExpr = expr.receiver,
        renderedRecv = receiver,
        extraExprs = nonUnitArgs,
        renderedExtras = renderedValueArgs,
        turbofishType = tfType,
        selfTypeName = selfTypeName,
    )
}

private fun KotlinJ2KEmitter.renderConcretePrimitiveOp(
    recvKind: NumericKind,
    rawMethod: String,
    recvExpr: Expr,
    renderedRecv: String,
    extraExprs: List<Expr>,
    renderedExtras: List<String>,
    turbofishType: SynType?,
    selfTypeName: String?,
): String? {
    val isCoreAvailable = "core" in headerContext.crateNamespaces
    if (!isCoreAvailable) {
        if (recvKind == NumericKind.I32 &&
            rawMethod in setOf("checked_add", "checked_sub", "checked_mul") &&
            extraExprs.size == 1 &&
            renderedExtras.size == 1 &&
            turbofishType == null
        ) {
            val opSym =
                when (rawMethod) {
                    "checked_add" -> "+"
                    "checked_sub" -> "-"
                    else -> "*"
                }
            return "(($renderedRecv).toLong() $opSym (${renderedExtras[0]}).toLong()).takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()"
        }
        return null
    }
    val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
    val recvAtom = if (isAtomicPostfixReceiver(recvExpr, renderedRecv)) renderedRecv else "($renderedRecv)"
    if (recvKind in setOf(NumericKind.F16, NumericKind.F32, NumericKind.F64, NumericKind.F128)) {
        return renderFloatConcretePrimitiveOp(
            coreNs = coreNs,
            recvKind = recvKind,
            rawMethod = rawMethod,
            recvAtom = recvAtom,
            renderedRecv = renderedRecv,
            extraExprs = extraExprs,
            renderedExtras = renderedExtras,
            turbofishType = turbofishType,
            selfTypeName = selfTypeName,
        )
    }
    if (turbofishType != null) return null
    val isUnsignedKind =
        recvKind in setOf(NumericKind.U8, NumericKind.U16, NumericKind.U32, NumericKind.U64, NumericKind.U128)
    if (isUnsignedKind && rawMethod in setOf("checked_abs", "wrapping_abs", "saturating_abs", "overflowing_abs", "saturating_neg")) {
        return null
    }
    if (extraExprs.isEmpty()) {
        val ktMethod = INTEGER_UNARY_OP_METHODS[rawMethod] ?: return null
        if (headerContext.packageName == "$coreNs.num" && currentEnclosingFunctionName == ktMethod) {
            return null
        }
        if (headerContext.packageName != "$coreNs.num") {
            CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.num.$ktMethod")
        }
        return "$recvAtom.$ktMethod()"
    }
    if (extraExprs.size == 1 && renderedExtras.size == 1) {
        val shiftOrPowMethod = INTEGER_SHIFT_OR_POW_METHODS[rawMethod]
        if (shiftOrPowMethod != null) {
            if (headerContext.packageName == "$coreNs.num" && currentEnclosingFunctionName == shiftOrPowMethod) {
                return null
            }
            if (headerContext.packageName != "$coreNs.num") {
                CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.num.$shiftOrPowMethod")
            }
            val rhs = coerceNumericArgIfNeeded(extraExprs[0], renderedExtras[0], NumericKind.U32, selfTypeName)
            return "$recvAtom.$shiftOrPowMethod($rhs)"
        }
        val binMethod = INTEGER_BINARY_OP_METHODS[rawMethod]
        if (binMethod != null) {
            if (headerContext.packageName == "$coreNs.num" && currentEnclosingFunctionName == binMethod) {
                return null
            }
            if (headerContext.packageName != "$coreNs.num") {
                CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.num.$binMethod")
            }
            val rhs = coerceNumericArgIfNeeded(extraExprs[0], renderedExtras[0], recvKind, selfTypeName)
            return "$recvAtom.$binMethod($rhs)"
        }
    }
    return null
}

private val FLOAT_LIBRARY_METHODS: Map<String, String> =
    mapOf(
        "is_sign_positive" to "isSignPositive",
        "is_sign_negative" to "isSignNegative",
        "classify" to "classify",
        "is_normal" to "isNormal",
        "is_subnormal" to "isSubnormal",
        "signum" to "signum",
        "to_degrees" to "toDegrees",
        "to_radians" to "toRadians",
        "next_up" to "nextUp",
        "next_down" to "nextDown",
    )

private val FLOAT_KM_UNARY_BASES: Map<String, String> =
    mapOf(
        "abs" to "kmFabs",
        "floor" to "kmFloor",
        "ceil" to "kmCeil",
        "round" to "kmRound",
        "round_ties_even" to "kmRoundTiesEven",
        "trunc" to "kmTrunc",
        "sqrt" to "kmSqrt",
        "exp" to "kmExp",
        "exp2" to "kmExp2",
        "ln" to "kmLog",
        "log2" to "kmLog2",
        "log10" to "kmLog10",
        "sin" to "kmSin",
        "cos" to "kmCos",
    )

private val FLOAT_LIBM_UNARY_OPS: Set<String> =
    setOf("tan", "asin", "acos", "atan", "cbrt", "sinh", "cosh", "tanh", "asinh", "acosh", "atanh")

private val FLOAT_KM_BINARY_BASES: Map<String, String> =
    mapOf(
        "powf" to "kmPow",
        "copysign" to "kmCopysign",
        "min" to "kmMinnum",
        "max" to "kmMaxnum",
    )

private val INTEGER_UNARY_OP_METHODS: Map<String, String> =
    mapOf(
        "checked_neg" to "checkedNeg",
        "checked_abs" to "checkedAbs",
        "wrapping_neg" to "wrappingNeg",
        "wrapping_abs" to "wrappingAbs",
        "saturating_neg" to "saturatingNeg",
        "saturating_abs" to "saturatingAbs",
        "overflowing_neg" to "overflowingNeg",
        "overflowing_abs" to "overflowingAbs",
        "reverse_bits" to "reverseBits",
        "count_ones" to "countOnes",
        "count_zeros" to "countZeros",
        "leading_zeros" to "leadingZeros",
        "trailing_zeros" to "trailingZeros",
        "leading_ones" to "leadingOnes",
        "trailing_ones" to "trailingOnes",
    )

private val INTEGER_SHIFT_OR_POW_METHODS: Map<String, String> =
    mapOf(
        "checked_shl" to "checkedShl",
        "checked_shr" to "checkedShr",
        "checked_pow" to "checkedPow",
        "wrapping_shl" to "wrappingShl",
        "wrapping_shr" to "wrappingShr",
        "overflowing_shl" to "overflowingShl",
        "overflowing_shr" to "overflowingShr",
        "rotate_left" to "rotateLeft",
        "rotate_right" to "rotateRight",
        "pow" to "pow",
    )

private val INTEGER_BINARY_OP_METHODS: Map<String, String> =
    mapOf(
        "checked_add" to "checkedAdd",
        "checked_sub" to "checkedSub",
        "checked_mul" to "checkedMul",
        "checked_div" to "checkedDiv",
        "checked_rem" to "checkedRem",
        "checked_div_euclid" to "checkedDivEuclid",
        "checked_rem_euclid" to "checkedRemEuclid",
        "wrapping_add" to "wrappingAdd",
        "wrapping_sub" to "wrappingSub",
        "wrapping_mul" to "wrappingMul",
        "wrapping_div" to "wrappingDiv",
        "wrapping_rem" to "wrappingRem",
        "saturating_add" to "saturatingAdd",
        "saturating_sub" to "saturatingSub",
        "saturating_mul" to "saturatingMul",
        "overflowing_add" to "overflowingAdd",
        "overflowing_sub" to "overflowingSub",
        "overflowing_mul" to "overflowingMul",
        "div_euclid" to "divEuclid",
        "rem_euclid" to "remEuclid",
    )

private fun KotlinJ2KEmitter.resolveLibmNamespace(coreNs: String): String {
    val pkg = headerContext.packageName?.trim().orEmpty()
    val defaultLibm = "io.github.kotlinmania.rustlang.libm"
    if (pkg == defaultLibm || pkg.startsWith("$defaultLibm.")) {
        return defaultLibm
    }
    headerContext.crateNamespaces["libm"]
        ?.trim()
        ?.removeSuffix(".")
        ?.takeIf { it.isNotEmpty() }
        ?.let { return it }
    return if (coreNs.endsWith(".core")) "${coreNs.removeSuffix(".core")}.libm" else defaultLibm
}

private fun KotlinJ2KEmitter.renderFloatConcretePrimitiveOp(
    coreNs: String,
    recvKind: NumericKind,
    rawMethod: String,
    recvAtom: String,
    renderedRecv: String,
    extraExprs: List<Expr>,
    renderedExtras: List<String>,
    turbofishType: SynType?,
    selfTypeName: String?,
): String? {
    val isWrapperFloat = recvKind == NumericKind.F16 || recvKind == NumericKind.F128
    val useF32 = recvKind == NumericKind.F16 || recvKind == NumericKind.F32
    val sfx = if (useF32) "f32" else "f64"
    val unwrapSuffix =
        when (recvKind) {
            NumericKind.F16 -> ".toFloat()"
            NumericKind.F128 -> ".toDouble()"
            else -> ""
        }
    val wrapPrefix =
        when (recvKind) {
            NumericKind.F16 -> "F16.fromFloat("
            NumericKind.F128 -> "F128.fromDouble("
            else -> ""
        }
    fun wrapResult(expr: String): String = if (wrapPrefix.isEmpty()) expr else "$wrapPrefix$expr)"
    fun scalarizeArg(argExpr: Expr, renderedArg: String): String {
        val coerced = coerceNumericArgIfNeeded(argExpr, renderedArg, recvKind, selfTypeName)
        if (unwrapSuffix.isEmpty()) return coerced
        val atom = if (isAtomicPostfixReceiver(argExpr, coerced)) coerced else "($coerced)"
        return "$atom$unwrapSuffix"
    }
    val scalarExpr = if (unwrapSuffix.isEmpty()) renderedRecv else "$recvAtom$unwrapSuffix"
    val scalarAtom = if (unwrapSuffix.isEmpty()) recvAtom else "$recvAtom$unwrapSuffix"
    val intrinsicsPkg = "$coreNs.intrinsics"
    fun registerIntrinsic(fn: String) {
        if (headerContext.packageName != intrinsicsPkg) {
            CoreIntrinsicLowering.registerCoreImport(this, "$intrinsicsPkg.$fn")
        }
    }

    if (extraExprs.isEmpty() && rawMethod == "to_int_unchecked") {
        val dstType = turbofishType?.let { renderType(it, selfTypeName) } ?: currentExpectedParseTargetType ?: currentEnclosingReturnRenderedType
        val dstKind = dstType?.let(this::resolveNumericKind) ?: return null
        return when (dstKind) {
            NumericKind.I8 -> "$scalarAtom.toInt().toByte()"
            NumericKind.I16 -> "$scalarAtom.toInt().toShort()"
            NumericKind.I32 -> "$scalarAtom.toInt()"
            NumericKind.I64 -> "$scalarAtom.toLong()"
            NumericKind.U8 -> "$scalarAtom.toInt().toUByte()"
            NumericKind.U16 -> "$scalarAtom.toInt().toUShort()"
            NumericKind.U32 -> "$scalarAtom.toUInt()"
            NumericKind.U64 -> {
                val fn = if (useF32) "kmFloatToU64Unchecked" else "kmDoubleToU64Unchecked"
                registerIntrinsic(fn)
                "$fn($scalarExpr)"
            }
            NumericKind.U128 -> {
                val fn = if (useF32) "kmFloatToU128Unchecked" else "kmDoubleToU128Unchecked"
                registerIntrinsic(fn)
                if (headerContext.packageName != coreNs) {
                    CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.U128")
                }
                "$fn($scalarExpr).let { U128(it.hi, it.lo) }"
            }
            NumericKind.I128 -> {
                val fn = if (useF32) "kmFloatToU128Unchecked" else "kmDoubleToU128Unchecked"
                registerIntrinsic(fn)
                if (headerContext.packageName != coreNs) {
                    CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.I128")
                }
                val zeroLit = if (useF32) "0.0f" else "0.0"
                "($scalarExpr).let { v -> if (v < $zeroLit) $fn(-v).let { -I128(it.hi.toLong(), it.lo) } else $fn(v).let { I128(it.hi.toLong(), it.lo) } }"
            }
            else -> null
        }
    }
    if (turbofishType != null) return null
    if (extraExprs.isEmpty()) {
        FLOAT_LIBRARY_METHODS[rawMethod]?.let { ktMethod ->
            val fSub =
                when (recvKind) {
                    NumericKind.F16 -> "f16"
                    NumericKind.F128 -> "f128"
                    NumericKind.F32 -> "f32"
                    else -> "f64"
                }
            val fPkg = "$coreNs.num.$fSub"
            if (headerContext.packageName == fPkg && currentEnclosingFunctionName == ktMethod) {
                return null
            }
            if (headerContext.packageName != fPkg) {
                CoreIntrinsicLowering.registerCoreImport(this, "$fPkg.$ktMethod")
            }
            return "$recvAtom.$ktMethod()"
        }
        FLOAT_KM_UNARY_BASES[rawMethod]?.let { kmBase ->
            val kmFn = "$kmBase$sfx"
            registerIntrinsic(kmFn)
            return wrapResult("$kmFn($scalarExpr)")
        }
        if (rawMethod in FLOAT_LIBM_UNARY_OPS) {
            val libmNs = resolveLibmNamespace(coreNs)
            val fn = if (useF32) "${rawMethod}f" else rawMethod
            val libmPkg = "$libmNs.math.$fn"
            if (headerContext.packageName == libmPkg && currentEnclosingFunctionName == fn) {
                return null
            }
            if (headerContext.packageName != libmPkg) {
                CoreIntrinsicLowering.registerCoreImport(this, "$libmPkg.$fn")
            }
            return wrapResult("$fn($scalarExpr)")
        }
        return when (rawMethod) {
            "is_nan" -> "$recvAtom.isNaN()"
            "is_infinite" -> "$recvAtom.isInfinite()"
            "is_finite" -> "$recvAtom.isFinite()"
            "to_bits" -> if (isWrapperFloat) "$recvAtom.toBits()" else null
            "fract" -> {
                val kmTrunc = "kmTrunc$sfx"
                registerIntrinsic(kmTrunc)
                "$recvAtom.let { v -> ${wrapResult("v$unwrapSuffix - $kmTrunc(v$unwrapSuffix)")} }"
            }
            "recip" -> wrapResult("${if (useF32) "1.0f" else "1.0"} / $scalarAtom")
            else -> null
        }
    }
    if (extraExprs.size == 1 && renderedExtras.size == 1) {
        FLOAT_KM_BINARY_BASES[rawMethod]?.let { kmBase ->
            val kmFn = "$kmBase$sfx"
            registerIntrinsic(kmFn)
            val arg0Scalar = scalarizeArg(extraExprs[0], renderedExtras[0])
            return wrapResult("$kmFn($scalarExpr, $arg0Scalar)")
        }
        return when (rawMethod) {
            "powi" -> {
                val intArg = coerceNumericArgIfNeeded(extraExprs[0], renderedExtras[0], NumericKind.I32, selfTypeName)
                val kmFn = "kmPowi$sfx"
                registerIntrinsic(kmFn)
                wrapResult("$kmFn($scalarExpr, $intArg)")
            }
            "log" -> {
                val kmLog = "kmLog$sfx"
                registerIntrinsic(kmLog)
                val arg0Scalar = scalarizeArg(extraExprs[0], renderedExtras[0])
                wrapResult("($kmLog($scalarExpr) / $kmLog($arg0Scalar))")
            }
            "atan2", "hypot" -> {
                val libmNs = resolveLibmNamespace(coreNs)
                val fn = if (useF32) "${rawMethod}f" else rawMethod
                val libmPkg = "$libmNs.math.$fn"
                if (headerContext.packageName == libmPkg && currentEnclosingFunctionName == fn) {
                    return null
                }
                if (headerContext.packageName != libmPkg) {
                    CoreIntrinsicLowering.registerCoreImport(this, "$libmPkg.$fn")
                }
                val arg0Scalar = scalarizeArg(extraExprs[0], renderedExtras[0])
                wrapResult("$fn($scalarExpr, $arg0Scalar)")
            }
            else -> null
        }
    }
    if (extraExprs.size == 2 && renderedExtras.size == 2 && rawMethod == "mul_add") {
        val kmFma = "kmFma$sfx"
        registerIntrinsic(kmFma)
        val arg0Scalar = scalarizeArg(extraExprs[0], renderedExtras[0])
        val arg1Scalar = scalarizeArg(extraExprs[1], renderedExtras[1])
        return wrapResult("$kmFma($scalarExpr, $arg0Scalar, $arg1Scalar)")
    }
    return null
}
