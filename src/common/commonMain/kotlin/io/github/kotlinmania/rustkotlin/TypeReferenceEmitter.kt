package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.procmacro2.TokenStream
import io.github.kotlinmania.procmacro2.TokenTree
import io.github.kotlinmania.syn.Attribute
import io.github.kotlinmania.syn.BinOp
import io.github.kotlinmania.syn.Expr
import io.github.kotlinmania.syn.GenericArgument
import io.github.kotlinmania.syn.GenericParam
import io.github.kotlinmania.syn.Generics
import io.github.kotlinmania.syn.Item
import io.github.kotlinmania.syn.Lit
import io.github.kotlinmania.syn.Meta
import io.github.kotlinmania.syn.Pat
import io.github.kotlinmania.syn.Path
import io.github.kotlinmania.syn.PathArguments
import io.github.kotlinmania.syn.PointerMutability
import io.github.kotlinmania.syn.Stmt
import io.github.kotlinmania.syn.SynType
import io.github.kotlinmania.syn.TypeParamBound
import io.github.kotlinmania.syn.UnOp
import io.github.kotlinmania.syn.Visibility
import io.github.kotlinmania.syn.WhereClause
import io.github.kotlinmania.syn.WherePredicate

private val CORE_OPS_BINARY_TRAITS =
    setOf("Add", "Sub", "Mul", "Div", "Rem", "BitAnd", "BitOr", "BitXor", "Shl", "Shr")
private val CORE_OPS_UNARY_OUTPUT_TRAITS =
    setOf("Neg", "Not")
private val CORE_OPS_ASSIGN_TRAITS =
    setOf(
        "AddAssign",
        "SubAssign",
        "MulAssign",
        "DivAssign",
        "RemAssign",
        "BitAndAssign",
        "BitOrAssign",
        "BitXorAssign",
        "ShlAssign",
        "ShrAssign",
    )

private val NUMERIC_SUFFIX_BY_KIND: Map<NumericKind, String> =
    mapOf(
        NumericKind.I8 to ".toByte()",
        NumericKind.U8 to ".toUByte()",
        NumericKind.I16 to ".toShort()",
        NumericKind.U16 to ".toUShort()",
        NumericKind.I32 to ".toInt()",
        NumericKind.U32 to ".toUInt()",
        NumericKind.I64 to ".toLong()",
        NumericKind.U64 to ".toULong()",
        NumericKind.F16 to ".toF16()",
        NumericKind.F32 to ".toFloat()",
        NumericKind.F64 to ".toDouble()",
        NumericKind.F128 to ".toF128()",
        NumericKind.I128 to ".toI128()",
        NumericKind.U128 to ".toU128()",
    )

private val BOXED_INT_FROM_SUFFIX_BY_KIND: Map<NumericKind, String> =
    mapOf(
        NumericKind.I8 to "fromByte",
        NumericKind.I16 to "fromShort",
        NumericKind.I32 to "fromInt",
        NumericKind.I64 to "fromLong",
        NumericKind.U8 to "fromUByte",
        NumericKind.U16 to "fromUShort",
        NumericKind.U32 to "fromUInt",
        NumericKind.U64 to "fromULong",
    )

internal fun isDecimalOrHexLiteralDigits(text: String): Boolean {
    if (text.isEmpty()) return false
    if (text.all { it.isDigit() || it == '_' }) return true
    if (text.startsWith("0x") || text.startsWith("0X")) {
        val hex = text.drop(2)
        return hex.isNotEmpty() && hex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == '_' }
    }
    return false
}

internal fun KotlinJ2KEmitter.renderCastExpression(
    inner: String,
    targetType: String,
    sourceExpr: Expr,
    selfTypeName: String? = null,
    pointerLayoutType: SynType? = null,
): String {
    if (targetType == "RustAny") {
        usesRustAny = true
        return if (inferExprRenderedType(sourceExpr, selfTypeName) == "RustAny") inner else "RustAny.from($inner)"
    }
    val targetBase = targetType.removeSuffix("?").trim()
    val aliasPtrTarget =
        (inspector.typeAliasTargets[targetBase] as? SynType.Ptr)?.let { renderType(it, selfTypeName) }
    val resolvedPtrTarget = if (rawPointerPointeeType(targetType) != null) targetType else aliasPtrTarget
    val pointerPointee = rawPointerPointeeType(resolvedPtrTarget)
    if (pointerPointee != null) {
        val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
        val ptrCtor = if (isMutRawPointerRenderedType(resolvedPtrTarget)) "MutPtr" else "ConstPtr"
        CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.ptr.$ptrCtor")
        if (inner in setOf("0", "0L", "0u", "0uL", "null")) {
            return "$ptrCtor<$pointerPointee>(0L)"
        }
        val reference = unwrapParenOrGroupExpr(sourceExpr) as? Expr.Reference
        val referencePlace = reference?.expr?.let { unwrapParenOrGroupExpr(it) } as? Expr.Path
        val referenceLocal =
            referencePlace
                ?.takeIf { it.qself == null && it.path.segments.size == 1 }
                ?.path
                ?.segments
                ?.first()
                ?.ident
                ?.toString()
                ?.removePrefix("r#")
                ?.let { scopeBuilder.resolveLocalKotlinName(it) ?: rustValueIdentToKotlin(it) }
        val referenceStorage = referenceLocal?.let { currentAddressableLocals[it] }
        if (referenceStorage != null) {
            return if (referenceLocal in currentAddressableArrayElementTypes) {
                "$ptrCtor<$pointerPointee>($referenceStorage.elementMutPtr(0).rawAddress)"
            } else {
                "$ptrCtor<$pointerPointee>($referenceStorage.asMutPtr().rawAddress)"
            }
        }
        val sourceType = inferExprRenderedType(sourceExpr, selfTypeName)
        val sourceReceiver = unwrapParenOrGroupExpr(sourceExpr) as? Expr.Path
        val unwrappedRefReceiver = (reference?.expr?.let { unwrapParenOrGroupExpr(it) } as? Expr.Path) ?: sourceReceiver
        if (unwrappedRefReceiver?.qself == null && unwrappedRefReceiver?.path?.isIdent("self") == true &&
            selfTypeName != null && currentTypedLocals["this"] == selfTypeName
        ) {
            val declaration = transparentDeclaration(selfTypeName)
            if (declaration != null && sourceTransparentField(declaration, selfTypeName) != null) {
                return "$ptrCtor<$pointerPointee>(this.fieldStorage.rawAddress)"
            }
        }
        val borrowedPointee = mutableReferencePointeeType(sourceType)
        if (borrowedPointee != null) {
            val recv = if (isAtomicPostfixReceiver(sourceExpr, inner)) inner else "($inner)"
            return "$ptrCtor<$pointerPointee>($recv.asMutPtr().rawAddress)"
        }
        val sourceAliasPtr =
            sourceType?.removeSuffix("?")?.trim()?.let { st ->
                (inspector.typeAliasTargets[st] as? SynType.Ptr)?.let { renderType(it, selfTypeName) }
            }
        val effectiveSourcePtrType = if (rawPointerPointeeType(sourceType) != null) sourceType else sourceAliasPtr
        if (rawPointerPointeeType(effectiveSourcePtrType) != null ||
            (sourceType != null && scopedAbiKeysForRenderedType(sourceType.removeSuffix("?")).any { it in headerContext.cratePointerTypeAliases })
        ) {
            if (effectiveSourcePtrType == resolvedPtrTarget && sourceType == targetType) return inner
            val rawSource = unwrapParenOrGroupExpr(sourceExpr)
            if (rawSource is Expr.Path && isAtomicPostfixReceiver(sourceExpr, inner)) {
                return "$ptrCtor<$pointerPointee>($inner.rawAddress, $inner.rawMeta)"
            }
            return "($inner).let { ptrSrc -> $ptrCtor<$pointerPointee>(ptrSrc.rawAddress, ptrSrc.rawMeta) }"
        }
        val localTyForPtr = currentTypedLocals[inner]
        if (isKnownIntegerBitmaskExpr(sourceExpr) ||
            (sourceExpr is Expr.Unary && (sourceExpr.op is UnOp.Neg || sourceExpr.op is UnOp.NotOp)) ||
            (localTyForPtr != null && resolveNumericKind(localTyForPtr) != null) ||
            inferExprNumericKind(sourceExpr, selfTypeName) != null
        ) {
            val recv = if (isAtomicPostfixReceiver(sourceExpr, inner)) inner else "($inner)"
            val longArg = convertNumericReceiver(recv, "Long", inferExprNumericKind(sourceExpr, selfTypeName))
            return "$ptrCtor<$pointerPointee>($longArg)"
        }
        val castHelper = if (ptrCtor == "MutPtr") "mutPtrFromRustCast" else "constPtrFromRustCast"
        CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.mem.$castHelper")
        return "$castHelper<$pointerPointee>($inner)"
    }
    val targetNumericKind = resolveNumericKind(targetType)
    val canonicalNumericTarget =
        when (targetNumericKind) {
            NumericKind.I8 -> "Byte"
            NumericKind.I16 -> "Short"
            NumericKind.I32 -> "Int"
            NumericKind.I64 -> "Long"
            NumericKind.I128 -> {
                usesI128 = true
                "I128"
            }
            NumericKind.U8 -> "UByte"
            NumericKind.U16 -> "UShort"
            NumericKind.U32 -> "UInt"
            NumericKind.U64 -> "ULong"
            NumericKind.U128 -> {
                usesU128 = true
                "U128"
            }
            NumericKind.F16 -> "F16"
            NumericKind.F32 -> kotlinF32TypeName()
            NumericKind.F64 -> "Double"
            NumericKind.F128 -> "F128"
            null -> if (targetType == "Char" || targetType == "RustChar") targetType else null
        }
    val sourceInferredForNum = inferExprRenderedType(sourceExpr, selfTypeName)
    val isSourceRawPtrOrAlias =
        rawPointerPointeeType(sourceInferredForNum) != null ||
            (
                sourceInferredForNum != null && (
                    inspector.typeAliasTargets[sourceInferredForNum.removeSuffix("?")] is SynType.Ptr ||
                        scopedAbiKeysForRenderedType(sourceInferredForNum.removeSuffix("?")).any { it in headerContext.cratePointerTypeAliases }
                )
            )
    if (canonicalNumericTarget != null && isSourceRawPtrOrAlias) {
        val recv = if (isAtomicPostfixReceiver(sourceExpr, inner)) inner else "($inner)"
        return convertNumericReceiver("$recv.rawAddress", canonicalNumericTarget, NumericKind.I64)
    }
    if (canonicalNumericTarget != null && mutableReferencePointeeType(sourceInferredForNum) != null) {
        val recv = if (isAtomicPostfixReceiver(sourceExpr, inner)) inner else "($inner)"
        return convertNumericReceiver("$recv.pointer.rawAddress", canonicalNumericTarget, NumericKind.I64)
    }
    if (canonicalNumericTarget != null && (isKnownBooleanCastSource(sourceExpr) || sourceInferredForNum?.removeSuffix("?")?.trim() == "Boolean")) {
        val cleanCond =
            if (hasOuterBalancedParens(inner)) {
                inner.substring(1, inner.length - 1)
            } else {
                inner
            }
        val intBranch = "(if ($cleanCond) 1 else 0)"
        return when (canonicalNumericTarget) {
            "Byte" -> "$intBranch.toByte()"
            "Short" -> "$intBranch.toShort()"
            "Int" -> intBranch
            "Long" -> "(if ($cleanCond) 1L else 0L)"
            "I128" -> "(if ($cleanCond) I128.ONE else I128.ZERO)"
            "UByte" -> "(if ($cleanCond) 1u else 0u).toUByte()"
            "UShort" -> "(if ($cleanCond) 1u else 0u).toUShort()"
            "UInt" -> "(if ($cleanCond) 1u else 0u)"
            "ULong" -> "(if ($cleanCond) 1uL else 0uL)"
            "U128" -> "(if ($cleanCond) U128.ONE else U128.ZERO)"
            "Float", "kotlin.Float" -> "(if ($cleanCond) 1.0f else 0.0f)"
            "Double" -> "(if ($cleanCond) 1.0 else 0.0)"
            "Char" -> "$intBranch.toChar()"
            "RustChar" -> {
                usesRustChar = true
                "(if ($cleanCond) RustChar(1u) else RustChar(0u))"
            }
            else -> "($inner as $targetType)"
        }
    }
    val unwrappedSource = unwrapParenOrGroupExpr(unwrapTransparentCastExpr(sourceExpr))
    val localTy = currentTypedLocals[inner]
    if (canonicalNumericTarget != null) {
        if (!headerContext.rawAbiMode) {
            val cleanLocalBase = localTy?.removeSuffix("?")
            if (
                inner in currentNullableLocals ||
                localTy?.endsWith("?") == true
            ) {
                error("Cannot expose an address for $inner ($localTy): the Rust pointer requires address-bearing storage")
            }
            if (
                cleanLocalBase != null &&
                (
                    cleanLocalBase.endsWith("Callback") ||
                        cleanLocalBase.endsWith("Fn") ||
                        cleanLocalBase in synthesizedSamDeclarations
                )
            ) {
                val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
                CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.ffi.ExternFnPtr")
                val recv = if (isAtomicPostfixReceiver(sourceExpr, inner)) inner else "($inner)"
                return convertNumericReceiver(
                    "(($recv as? ExternFnPtr)?.rawAddress ?: error(\"Kotlin closure callback has no native C function address\"))",
                    canonicalNumericTarget,
                    NumericKind.I64,
                )
            }
            val fieldTy = resolveFieldRenderedType(sourceExpr, selfTypeName)
            if (fieldTy != null && rawPointerPointeeType(fieldTy) != null) {
                val recv = if (isAtomicPostfixReceiver(sourceExpr, inner)) inner else "($inner)"
                return convertNumericReceiver("$recv.rawAddress", canonicalNumericTarget, NumericKind.I64)
            }
            if (fieldTy != null && fieldTy.endsWith("?")) {
                error("Cannot expose an address for $inner ($fieldTy): the Rust field requires address-bearing storage")
            }
        }
        val cleanSourceCharTy = sourceInferredForNum?.removeSuffix("?")?.trim()
        val inferredRustCharSource = headerContext.scalarChar && cleanSourceCharTy == "RustChar"
        val inferredKtCharSource = !headerContext.scalarChar && cleanSourceCharTy == "Char"
        if ((unwrappedSource as? Expr.Lit)?.lit is Lit.Char || inferredRustCharSource || inferredKtCharSource) {
            if ((inferredRustCharSource && canonicalNumericTarget == "RustChar") ||
                (inferredKtCharSource && canonicalNumericTarget == "Char")
            ) {
                return inner
            }
            val recv = if (isAtomicPostfixReceiver(sourceExpr, inner)) inner else "($inner)"
            val charCodeKind = if (headerContext.scalarChar && !inferredKtCharSource) NumericKind.U32 else NumericKind.I32
            return convertNumericReceiver("$recv.code", canonicalNumericTarget, charCodeKind)
        }
        val sourceKind = inferExprNumericKind(sourceExpr, selfTypeName)
        if (sourceKind == null && unwrappedSource is Expr.Path && unwrappedSource.qself == null) {
            val segs = unwrappedSource.path.segments.toList()
            if (segs.size >= 2) {
                val prevIdentStr = segs[segs.size - 2].ident.toString()
                val prevRaw = prevIdentStr.removePrefix("r#")
                val prevKtType = rustTypeIdentToKotlin(prevRaw)
                val lastIdentStr = segs.last().ident.toString()
                val lastRaw = lastIdentStr.removePrefix("r#")
                val isPrevKnownType =
                    (
                        prevRaw.first().isUpperCase() ||
                            prevKtType in inspector.declaredTypeNames ||
                            scopedAbiKeysForRenderedType(prevKtType).any { it in headerContext.crateSymbolSubpackages }
                    ) &&
                        resolveNumericKind(prevKtType) == null
                if (isPrevKnownType &&
                    lastRaw !in setOf("MAX", "MIN", "BITS", "ZERO") &&
                    lastRaw.first().isUpperCase() &&
                    (!isScreamingSnakeIdent(lastRaw) || !prevRaw.first().isUpperCase())
                ) {
                    val recv = if (isAtomicPostfixReceiver(sourceExpr, inner)) inner else "($inner)"
                    val enumKind =
                        scopedAbiKeysForRenderedType(prevKtType).firstNotNullOfOrNull {
                            numericKindFromScannerCode(headerContext.crateNumericTypeKinds[it])
                        } ?: NumericKind.U32
                    return convertNumericReceiver("$recv.value", canonicalNumericTarget, enumKind)
                }
            }
        }
        if (sourceKind == null) {
            val inferredSourceType = inferExprRenderedType(sourceExpr, selfTypeName)?.removeSuffix("?")?.trim()
            if (inferredSourceType != null && resolveNumericKind(inferredSourceType) == null) {
                val inferredEnumKind =
                    scopedAbiKeysForRenderedType(inferredSourceType).firstNotNullOfOrNull {
                        numericKindFromScannerCode(headerContext.crateNumericTypeKinds[it])
                    } ?: NumericKind.I8.takeIf {
                        (inferredSourceType == "Ordering" || inferredSourceType.endsWith(".cmp.Ordering")) &&
                            headerContext.packageName?.contains(".sync.atomic") != true
                    }
                if (inferredEnumKind != null) {
                    val recv = if (isAtomicPostfixReceiver(sourceExpr, inner)) inner else "($inner)"
                    return convertNumericReceiver("$recv.value", canonicalNumericTarget, inferredEnumKind)
                }
            }
        }
        if ((sourceKind != null && numericKindToPureTypeName(sourceKind) == canonicalNumericTarget) ||
            isAlreadyRenderedAsNumericKind(inner, resolveNumericKind(canonicalNumericTarget))
        ) {
            return inner
        }
        val recv = if (isAtomicPostfixReceiver(sourceExpr, inner)) inner else "($inner)"
        return convertNumericReceiver(recv, canonicalNumericTarget, sourceKind)
    }
    val targetPtrAliasKind =
        scopedAbiKeysForRenderedType(targetBase)
            .firstNotNullOfOrNull { headerContext.cratePointerTypeAliases[it] }
    if (targetPtrAliasKind != null) {
        val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
        val ptrCtor = if (targetPtrAliasKind.startsWith("const:")) "ConstPtr" else "MutPtr"
        CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.ptr.$ptrCtor")
        val isZeroLit = inner == "0" || inner == "0L" || inner == "0u" || inner == "0uL" || inner == "null"
        if (isZeroLit) {
            return "$ptrCtor(0L)"
        }
        if (isSourceRawPtrOrAlias) {
            val recv = if (isAtomicPostfixReceiver(sourceExpr, inner)) inner else "($inner)"
            return "$ptrCtor($recv.rawAddress)"
        }
        if (inner in currentNullableLocals) {
            error("Cannot cast $inner ($localTy) to $targetType: the Rust pointer requires address-bearing storage")
        }
        if (isKnownIntegerBitmaskExpr(sourceExpr) ||
            (sourceExpr is Expr.Unary && (sourceExpr.op is UnOp.Neg || sourceExpr.op is UnOp.NotOp)) ||
            (localTy != null && resolveNumericKind(localTy) != null) ||
            inferExprNumericKind(sourceExpr, selfTypeName) != null
        ) {
            val recv = if (isAtomicPostfixReceiver(sourceExpr, inner)) inner else "($inner)"
            val longArg = convertNumericReceiver(recv, "Long", inferExprNumericKind(sourceExpr, selfTypeName))
            return "$ptrCtor($longArg)"
        }
    }
    if (targetType.endsWith("?")) {
        val nonNullTarget = targetType.removeSuffix("?")
        val isZeroLit = inner == "0" || inner == "0L" || inner == "0u" || inner == "0uL" || inner == "null"
        if (isZeroLit) {
            return "null"
        }
        val isNumericSource =
            isKnownIntegerBitmaskExpr(sourceExpr) ||
                isKnownBooleanCastSource(sourceExpr) ||
                (sourceExpr is Expr.Unary && sourceExpr.op is UnOp.Neg) ||
                (localTy != null && resolveNumericKind(localTy) != null) ||
                inferExprNumericKind(sourceExpr, selfTypeName) != null
        if (resolveNumericKind(nonNullTarget) != null && isNumericSource) {
            return renderCastExpression(inner, nonNullTarget, sourceExpr, selfTypeName)
        }
        if (isNumericSource) {
            return if (nonNullTarget == "String") "\"\\uFFFF\"" else "null"
        }
    }
    if (targetType == inner) return inner
    return "(($inner as Any?) as $targetType)"
}

private fun hasTopLevelOperatorOutsidePostfix(s: String): Boolean {
    var depth = 0
    for (i in s.indices) {
        when (s[i]) {
            '(', '[', '{' -> depth++
            ')', ']', '}' -> depth--
            ' ' -> if (depth == 0) return true
            '+', '-', '*', '/', '%', '&', '|', '^', '=', '<', '>' -> if (depth == 0 && i > 0) return true
        }
    }
    return false
}

internal fun KotlinJ2KEmitter.isAlreadyRenderedAsNumericKind(rendered: String, kind: NumericKind?): Boolean {
    if (kind == null) return false
    var s = rendered.trim()
    while (true) {
        while (s.length >= 2 && s.startsWith("(") && s.endsWith(")") && hasOuterBalancedParens(s)) {
            s = s.substring(1, s.length - 1).trim()
        }
        if (s.endsWith(".inv()")) {
            s = s.removeSuffix(".inv()").trim()
        } else {
            break
        }
    }
    if (hasTopLevelOperatorOutsidePostfix(s)) {
        return (kind == NumericKind.U64 && s.endsWith("uL") && !s.contains(".to")) ||
            (kind == NumericKind.U32 && s.endsWith("u") && !s.endsWith("uL") && !s.contains(".to")) ||
            (kind == NumericKind.I64 && s.endsWith("L") && !s.endsWith("uL") && !s.contains(".to"))
    }
    val expectedSuffix = NUMERIC_SUFFIX_BY_KIND[kind] ?: return false
    if (s.endsWith(expectedSuffix)) return true
    if (kind == NumericKind.F16 && (s.startsWith("F16.") || s == "F16()")) return true
    if (kind == NumericKind.F128 && (s.startsWith("F128.") || s == "F128()")) return true
    if (s.endsWith(".code") && kind == NumericKind.I32) return true
    if (s.endsWith(".size") && kind == NumericKind.I32) return true
    if (s.endsWith(".hashCode()") && kind == NumericKind.I32) return true
    if (s.endsWith(".rawValue") && kind == NumericKind.I64) return true
    val withoutSign = if (s.startsWith("-")) s.drop(1).trim() else s
    if (kind == NumericKind.I32 && isDecimalOrHexLiteralDigits(withoutSign)) return true
    if (kind == NumericKind.U64 && (withoutSign.endsWith("uL") || withoutSign.endsWith("UL"))) {
        return isDecimalOrHexLiteralDigits(withoutSign.dropLast(2))
    }
    if (kind == NumericKind.U32 && (withoutSign.endsWith("u") || withoutSign.endsWith("U")) &&
        !withoutSign.endsWith("uL") && !withoutSign.endsWith("UL")
    ) {
        return isDecimalOrHexLiteralDigits(withoutSign.dropLast(1))
    }
    if (kind == NumericKind.I64 && withoutSign.endsWith("L") &&
        !withoutSign.endsWith("uL") && !withoutSign.endsWith("UL")
    ) {
        return isDecimalOrHexLiteralDigits(withoutSign.dropLast(1))
    }
    return false
}

private fun convertTo128BitBoxedNumeric(
    recv: String,
    targetBoxType: String,
    sourceKind: NumericKind?,
    directSelfKind: NumericKind,
    directPeerKind: NumericKind,
    directPeerMethod: String,
    floatFallbackMethod: String,
    floatFallbackCast: String,
): String {
    if (sourceKind == directSelfKind) return recv
    if (sourceKind == directPeerKind) return "$recv.$directPeerMethod()"
    BOXED_INT_FROM_SUFFIX_BY_KIND[sourceKind]?.let { suffix ->
        return "$targetBoxType.$suffix($recv)"
    }
    return when (sourceKind) {
        NumericKind.F16, NumericKind.F32, NumericKind.F64, NumericKind.F128 ->
            "$targetBoxType.$floatFallbackMethod($recv.$floatFallbackCast())"
        null ->
            when {
                recv.endsWith("uL") -> "$targetBoxType.fromULong($recv)"
                recv.endsWith("u") -> "$targetBoxType.fromUInt($recv)"
                else -> "$targetBoxType.fromLong($recv.toLong())"
            }
        else -> recv
    }
}

private fun convertToBoxedFloatNumeric(
    recv: String,
    targetBoxType: String,
    targetSelfKind: NumericKind,
    sourceKind: NumericKind?,
): String {
    if (sourceKind == targetSelfKind) return recv
    BOXED_INT_FROM_SUFFIX_BY_KIND[sourceKind]?.let { suffix ->
        return "$targetBoxType.$suffix($recv)"
    }
    return when (sourceKind) {
        NumericKind.F16 -> "$targetBoxType.fromF16($recv)"
        NumericKind.F32 -> "$targetBoxType.fromFloat($recv)"
        NumericKind.F64 -> "$targetBoxType.fromDouble($recv)"
        else -> "$targetBoxType.fromDouble($recv.toDouble())"
    }
}

internal fun KotlinJ2KEmitter.convertNumericReceiver(
    recv: String,
    canonicalNumericTarget: String,
    sourceKind: NumericKind? = null,
): String {
    val targetKind = resolveNumericKind(canonicalNumericTarget)
    if ((sourceKind != null && numericKindToPureTypeName(sourceKind) == canonicalNumericTarget) ||
        isAlreadyRenderedAsNumericKind(recv, targetKind)
    ) {
        return recv
    }
    return when (canonicalNumericTarget) {
        "Byte" -> "$recv.toByte()"
        "Short" -> "$recv.toShort()"
        "Int" -> "$recv.toInt()"
        "Long" -> "$recv.toLong()"
        "UByte" -> "$recv.toUByte()"
        "UShort" -> "$recv.toUShort()"
        "UInt" -> "$recv.toUInt()"
        "ULong" -> "$recv.toULong()"
        "U128" ->
            convertTo128BitBoxedNumeric(
                recv = recv,
                targetBoxType = "U128",
                sourceKind = sourceKind,
                directSelfKind = NumericKind.U128,
                directPeerKind = NumericKind.I128,
                directPeerMethod = "toU128",
                floatFallbackMethod = "fromULong",
                floatFallbackCast = "toULong",
            )
        "I128" ->
            convertTo128BitBoxedNumeric(
                recv = recv,
                targetBoxType = "I128",
                sourceKind = sourceKind,
                directSelfKind = NumericKind.I128,
                directPeerKind = NumericKind.U128,
                directPeerMethod = "toI128",
                floatFallbackMethod = "fromLong",
                floatFallbackCast = "toLong",
            )
        "Float", "kotlin.Float" -> "$recv.toFloat()"
        "Double" -> "$recv.toDouble()"
        "F16" -> convertToBoxedFloatNumeric(recv, "F16", NumericKind.F16, sourceKind)
        "F128" -> convertToBoxedFloatNumeric(recv, "F128", NumericKind.F128, sourceKind)
        "Char" -> "$recv.toInt().toChar()"
        "RustChar" -> {
            usesRustChar = true
            "RustChar(${convertNumericReceiver(recv, "UInt", sourceKind)})"
        }
        else -> recv
    }
}

internal fun KotlinJ2KEmitter.resolveFieldRenderedType(expr: Expr, selfTypeName: String? = null): String? {
    val fieldExpr = unwrapTransparentCastExpr(expr) as? Expr.Field ?: return null
    return inferExprRenderedType(fieldExpr, selfTypeName)
}

internal fun KotlinJ2KEmitter.unwrapTransparentCastExpr(expr: Expr): Expr =
    when (expr) {
        is Expr.Group -> unwrapTransparentCastExpr(expr.expr)
        is Expr.Reference -> unwrapTransparentCastExpr(expr.expr)
        is Expr.RawAddr -> unwrapTransparentCastExpr(expr.expr)
        is Expr.Await -> unwrapTransparentCastExpr(expr.base)
        is Expr.Unary -> if (expr.op is UnOp.Deref) unwrapTransparentCastExpr(expr.expr) else expr
        else -> expr
    }

internal fun KotlinJ2KEmitter.isAtomicPostfixReceiver(sourceExpr: Expr, rendered: String): Boolean {
    if (rendered.isEmpty() || rendered.first() == '-' || rendered.first() == '!') return false
    val core = unwrapTransparentCastExpr(sourceExpr)
    if (core is Expr.Paren && hasOuterBalancedParens(rendered)) return true
    return when (core) {
        is Expr.Path, is Expr.Field, is Expr.Lit ->
            rendered.all { it.isLetterOrDigit() || it == '_' || it == '.' || it == '\'' || it == '"' } &&
                !rendered.contains("..")
        is Expr.MethodCall, is Expr.Index, is Expr.Call, is Expr.Tuple, is Expr.Array ->
            hasNoTopLevelSpacesOrColons(rendered)
        else -> false
    }
}

internal fun KotlinJ2KEmitter.hasOuterBalancedParens(text: String): Boolean {
    if (text.length < 2 || text.first() != '(' || text.last() != ')') return false
    return KotlinTokenWalker.scanTopLevelTokens(text, trackAllBrackets = false) { tok, depth ->
        !(tok is KotlinLexToken.Punct && tok.ch == ')' && depth == 0 && tok.end < text.length)
    }
}

internal fun KotlinJ2KEmitter.hasNoTopLevelSpacesOrColons(text: String): Boolean =
    KotlinTokenWalker.scanTopLevelTokens(text, trackAllBrackets = true) { tok, depth ->
        !(
            depth == 0 &&
                (
                    tok is KotlinLexToken.Whitespace ||
                        tok is KotlinLexToken.Newline ||
                        (tok is KotlinLexToken.Punct && tok.ch == ':')
                )
        )
    }

internal fun KotlinJ2KEmitter.renderType(ty: SynType, selfTypeName: String?): String =
    when (ty) {
        is SynType.Reference -> {
            val mutElem = if (ty.mutability != null) unwrapSynType(ty.elem) else null
            val mutVecElemType =
                if (mutElem is SynType.Path &&
                    mutElem.qself == null &&
                    "Vec" !in inspector.declaredTypeNames
                ) {
                    val segs = mutElem.path.segments.toList()
                    val lastSeg = segs.lastOrNull()
                    val prefixOk =
                        segs.dropLast(1).all { seg ->
                            val raw = seg.ident.toString().removePrefix("r#")
                            raw in STDLIB_TYPE_PATH_PREFIXES || raw == "vec"
                        }
                    if (prefixOk && lastSeg?.ident?.toString()?.removePrefix("r#") == "Vec") {
                        (lastSeg.arguments as? PathArguments.AngleBracketed)
                            ?.args
                            ?.toList()
                            .orEmpty()
                            .filterIsInstance<GenericArgument.TypeArg>()
                            .firstOrNull()
                            ?.type
                    } else {
                        null
                    }
                } else {
                    null
                }
            if (mutElem is SynType.Slice) {
                "MutableList<${renderType(mutElem.elem, selfTypeName)}>"
            } else if (mutVecElemType != null) {
                "MutableList<${renderType(mutVecElemType, selfTypeName)}>"
            } else if (ty.mutability != null && shouldUseAddressBackedMutableReference(ty.elem, selfTypeName)) {
                val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
                CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.mem.RustMutableReference")
                "RustMutableReference<${renderType(ty.elem, selfTypeName)}>"
            } else {
                renderType(ty.elem, selfTypeName)
            }
        }
        is SynType.Ptr -> renderPointerType(ty, selfTypeName)
        is SynType.Slice -> "List<${renderType(ty.elem, selfTypeName)}>"
        is SynType.Array -> {
            val elemRendered = renderType(ty.elem, selfTypeName)
            if (elemRendered == "Boolean") {
                "BooleanArray"
            } else {
                when (resolveNumericKind(elemRendered)) {
                    NumericKind.I8 -> "ByteArray"
                    NumericKind.U8 -> "UByteArray"
                    NumericKind.I16 -> "ShortArray"
                    NumericKind.U16 -> "UShortArray"
                    NumericKind.I32 -> "IntArray"
                    NumericKind.U32 -> "UIntArray"
                    NumericKind.I64 -> "LongArray"
                    NumericKind.U64 -> "ULongArray"
                    NumericKind.F32 -> "FloatArray"
                    NumericKind.F64 -> "DoubleArray"
                    NumericKind.I128, NumericKind.U128, NumericKind.F16, NumericKind.F128, null -> {
                        val nonNullElem =
                            when {
                                elemRendered.endsWith("?") ->
                                    elemRendered.removeSuffix("?")
                                isOptionFnTypeAliasRenderedType(elemRendered) ->
                                    "${elemRendered}Fn"
                                else -> elemRendered
                            }
                        "List<$nonNullElem>"
                    }
                }
            }
        }
        is SynType.Tuple -> {
            val elems = ty.elems.toList()
            val pairType = if ("Pair" in inspector.declaredTypeNames) "kotlin.Pair" else "Pair"
            val tripleType = if ("Triple" in inspector.declaredTypeNames) "kotlin.Triple" else "Triple"
            when (elems.size) {
                0 -> kotlinBuiltinTypeName("Unit")
                1 -> renderType(elems[0], selfTypeName)
                2 -> "$pairType<${renderType(elems[0], selfTypeName)}, ${renderType(elems[1], selfTypeName)}>"
                3 -> "$tripleType<${renderType(elems[0], selfTypeName)}, ${renderType(elems[1], selfTypeName)}, ${renderType(elems[2], selfTypeName)}>"
                else -> "List<Any?>"
            }
        }
        is SynType.Path -> {
            val qself = ty.qself
            if (qself != null) {
                val lastSeg =
                    ty.path.segments
                        .last()
                        ?.ident
                        ?.toString()
                if (lastSeg != null && lastSeg != "Self") {
                    rustTypeIdentToKotlin(lastSeg)
                } else {
                    renderType(qself.ty, selfTypeName)
                }
            } else {
                renderPathAsType(ty.path, selfTypeName)
            }
        }
        is SynType.FnPtr -> {
            val prevParam = inExternParamType
            inExternParamType = false
            try {
                synthesizeOrReuseBareFnSam(ty, selfTypeName)
            } finally {
                inExternParamType = prevParam
            }
        }
        is SynType.ImplTrait -> {
            val bounds = ty.bounds.toList().mapNotNull { renderTypeParamBound(it, selfTypeName) }
            bounds.firstOrNull() ?: "Any"
        }
        is SynType.TraitObject -> {
            if (isStdlibDynAnyTraitObject(ty)) {
                usesRustAny = true
                "RustAny"
            } else {
                val bounds = ty.bounds.toList().mapNotNull { renderTypeParamBound(it, selfTypeName) }
                bounds.firstOrNull() ?: "Any"
            }
        }
        is SynType.Paren -> renderType(ty.elem, selfTypeName)
        is SynType.Group -> renderType(ty.elem, selfTypeName)
        is SynType.Never -> if ("Nothing" in inspector.declaredTypeNames) "kotlin.Nothing" else "Nothing"
        is SynType.Infer -> "Any?"
        is SynType.Macro -> {
            val macroName =
                ty.mac.path.segments
                    .last()
                    ?.ident
                    ?.toString()
            if (macroName == "Token") {
                resolveSynTokenMacroTypeName(ty.mac.tokens) ?: "Token"
            } else {
                rustTypeIdentToKotlin(macroName ?: "Token")
            }
        }
        is SynType.Verbatim -> "Any"
    }

private val KOTLIN_BUILTIN_VALUE_TYPE_NAMES: Set<String> =
    setOf(
        "Byte",
        "Short",
        "Int",
        "Long",
        "UByte",
        "UShort",
        "UInt",
        "ULong",
        "Float",
        "Double",
        "Boolean",
        "Char",
        "String",
        "Unit",
        "Any",
    )

internal fun KotlinJ2KEmitter.kotlinBuiltinTypeName(simpleType: String): String {
    if (simpleType !in KOTLIN_BUILTIN_VALUE_TYPE_NAMES) return simpleType
    val shadowed =
        simpleType in inspector.declaredTypeNames ||
            simpleType in headerContext.moduleDeclaredTypeNames ||
            simpleType in activeTypeParamNames ||
            simpleType in activeEnclosingEnumVariantNames ||
            importedSimpleNameToPkg[simpleType]?.let { !it.startsWith("kotlin") } == true ||
            extraTypePathImports.any { it.endsWith(".$simpleType") || it.endsWith(" as $simpleType") }
    return if (shadowed) "kotlin.$simpleType" else simpleType
}

internal fun KotlinJ2KEmitter.renderPathAsType(path: Path, selfTypeName: String?): String {
    val rawSegments = path.segments.toList()
    if (rawSegments.isEmpty()) return "Any"
    if (rawSegments.size >= 2 && rawSegments[0].ident.toString() == "Self") {
        val assocIdent = rawSegments.last().ident.toString()
        val assocTy = currentImplAssocTypes[assocIdent]
        if (assocTy != null) {
            val prev = currentImplAssocTypes
            currentImplAssocTypes = prev - assocIdent
            return try {
                renderType(assocTy, selfTypeName)
            } finally {
                currentImplAssocTypes = prev
            }
        }
        return rustTypeIdentToKotlin(assocIdent)
    }
    if (rawSegments.size == 1 && rawSegments[0].arguments is PathArguments.None) {
        val singleKt = rustTypeIdentToKotlin(rawSegments[0].ident.toString())
        substitutedTypeParamTypes[singleKt]?.let { return it }
        renamedTypeParamIdentifiers[singleKt]?.let { return it }
    }
    if (rawSegments.size == 2 &&
        rawSegments[0].arguments is PathArguments.None &&
        rustTypeIdentToKotlin(rawSegments[0].ident.toString()) in activeTypeParamNames
    ) {
        return rustTypeIdentToKotlin(rawSegments[1].ident.toString())
    }
    val lastSeg = rawSegments.last()
    val rawName = lastSeg.ident.toString()
    if (rawName == "PhantomData" && "PhantomData" !in inspector.declaredTypeNames) {
        return "Unit"
    }
    val fmtSegNames =
        rewriteCrateRootAliasAndReexportSegments(
            rawSegments.map { it.ident.toString().removePrefix("r#") },
        )
    val isStdlibFmtModule =
        (
            fmtSegNames.size >= 3 &&
                fmtSegNames[0] in STDLIB_CRATE_ROOTS &&
                fmtSegNames[1] == "fmt"
        ) ||
            (
                fmtSegNames.size == 2 &&
                    fmtSegNames[0] == "fmt" &&
                    (
                        scopeBuilder.resolveModuleBinding("fmt") != null ||
                            headerContext.rawAbiMode ||
                            headerContext.finalClasses ||
                            currentAttachedImplTraitPath
                                ?.segments
                                ?.last()
                                ?.ident
                                ?.toString() in FMT_TRAIT_NAMES
                    )
            )
    if (isStdlibFmtModule) {
        if (rawName == "Formatter") return "StringBuilder"
        if (rawName == "Write") return "Appendable"
        if ((rawName == "Result" || rawName == "Error") &&
            headerContext.packageName?.startsWith("io.github.kotlinmania.rustlang.") != true
        ) {
            return "Unit"
        }
        if (rawName == "DebugStruct" && "DebugStruct" !in inspector.declaredTypeNames) {
            usesDebugStruct = true
            return "DebugStruct"
        }
        if (rawName == "DebugTuple" && "DebugTuple" !in inspector.declaredTypeNames) {
            usesDebugTuple = true
            return "DebugTuple"
        }
    }
    if (rawSegments.size == 1 && lastSeg.arguments is PathArguments.None) {
        if (rawName == "DebugStruct" && "DebugStruct" !in inspector.declaredTypeNames) {
            usesDebugStruct = true
            return "DebugStruct"
        }
        if (rawName == "DebugTuple" && "DebugTuple" !in inspector.declaredTypeNames) {
            usesDebugTuple = true
            return "DebugTuple"
        }
    }
    if (rawName == "ThreadId" && "ThreadId" !in inspector.declaredTypeNames) {
        return "Any"
    }
    if (!headerContext.rawAbiMode && lastSeg.arguments is PathArguments.None) {
        val rawSegNames =
            rewriteCrateRootAliasAndReexportSegments(
                rawSegments.map { it.ident.toString().removePrefix("r#") },
            )
        val symLeaf = rawSegNames.last()
        val isSelfContainedLibcFfi =
            rawSegNames.size == 2 &&
                rawSegNames[0] == "libc" &&
                FfiInteropCollector.resolveLibcNamespace(headerContext) == null
        val isStdlibFfiModule =
            (rawSegNames.size == 3 && rawSegNames[0] in setOf("core", "std") && rawSegNames[1] == "ffi") ||
                (rawSegNames.size == 4 && rawSegNames[0] == "std" && rawSegNames[1] == "os" && rawSegNames[2] == "raw") ||
                isSelfContainedLibcFfi ||
                (
                    rawSegNames.size == 2 && rawSegNames[0] in setOf("ffi", "raw") &&
                        run {
                            val imp = scopeBuilder.resolveModuleBinding(rawSegNames[0])?.importedPath
                            imp == null || imp.startsWith("core.") || imp.startsWith("std.") || imp.startsWith("alloc.")
                        }
                )
        val isAllocFfiCString =
            rawSegNames.size == 3 &&
                rawSegNames[0] == "alloc" &&
                rawSegNames[1] == "ffi" &&
                symLeaf == "CString"
        if (isSelfContainedLibcFfi && symLeaf !in CoreIntrinsicLowering.STDLIB_CORE_FFI_SYMBOLS) {
            val ptr64 = activeCfgProfile.pointerWidth != 32
            when (symLeaf) {
                "size_t", "usize", "uintptr_t" -> return if (ptr64) "ULong" else "UInt"
                "ssize_t", "isize", "intptr_t", "ptrdiff_t" -> return if (ptr64) "Long" else "Int"
                "int8_t" -> return "Byte"
                "uint8_t" -> return "UByte"
                "int16_t" -> return "Short"
                "uint16_t" -> return "UShort"
                "int32_t", "pid_t", "clockid_t", "wchar_t" -> return "Int"
                "uint32_t", "uid_t", "gid_t", "socklen_t", "useconds_t", "mode_t" -> return "UInt"
                "int64_t", "off_t", "time_t", "suseconds_t" -> return "Long"
                "uint64_t", "dev_t", "ino_t", "rlim_t" -> return "ULong"
            }
        }
        val resolvedFfiImport =
            when {
                isAllocFfiCString || (isStdlibFfiModule && symLeaf == "CString") ->
                    resolveStdlibFfiSymbolImport("CString", isAllocCString = true)
                isStdlibFfiModule && symLeaf in CoreIntrinsicLowering.STDLIB_CORE_FFI_SYMBOLS ->
                    resolveStdlibFfiSymbolImport(symLeaf)
                else -> null
            }
        if (resolvedFfiImport != null) {
            return bindOrQualifyResolvedCrossSymbol(resolvedFfiImport)
        }
    }

    if (!headerContext.rawAbiMode &&
        rawName == "Result" &&
        "Ok" !in inspector.declaredTypeNames
    ) {
        val rawSegNames =
            rewriteCrateRootAliasAndReexportSegments(
                rawSegments.map { it.ident.toString().removePrefix("r#") },
            )
        val modHead = rawSegNames.getOrNull(rawSegNames.size - 2)
        val resolvedModImp =
            if (rawSegNames.size == 2 && modHead != null) {
                scopeBuilder.resolveModuleBinding(modHead)?.importedPath
            } else {
                null
            }
        val matchesStdMod: (String) -> Boolean = { expected ->
            val modIdx = rawSegNames.size - 2
            (
                modIdx >= 0 &&
                    rawSegNames[modIdx] == expected &&
                    (modIdx == 0 || rawSegNames[modIdx - 1] in setOf("core", "std", "alloc"))
            ) ||
                resolvedModImp == expected ||
                resolvedModImp == "core.$expected" ||
                resolvedModImp == "std.$expected" ||
                resolvedModImp == "alloc.$expected"
        }
        val isTaggedOrRustLangStd =
            usesTaggedCoreResult() ||
                headerContext.packageName?.startsWith("io.github.kotlinmania.rustlang.") == true
        if (lastSeg.arguments is PathArguments.None &&
            rawSegNames.size >= 2 &&
            matchesStdMod("fmt") &&
            isTaggedOrRustLangStd &&
            ("Result" !in inspector.declaredTypeNames || rawSegNames.dropLast(1).lastOrNull() == "fmt" || resolvedModImp != null)
        ) {
            if (!usesTaggedCoreResult()) {
                return if ("Result" in inspector.declaredTypeNames) "kotlin.Result<Unit>" else "Result<Unit>"
            }
            val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
            val errRef = bindOrQualifyResolvedCrossSymbol("$coreNs.fmt.Error")
            val resName =
                if (hasDeclaredResultEnum()) {
                    "Result"
                } else if ("Result" in inspector.declaredTypeNames) {
                    "$coreNs.result.Result"
                } else {
                    sourceResultName()
                }
            return "$resName<Unit, $errRef>"
        }
        if (!hasDeclaredResultEnum() && lastSeg.arguments is PathArguments.AngleBracketed) {
            val isStdOrCoreResult =
                rawSegNames.size == 1 ||
                    matchesStdMod("result") ||
                    (rawSegNames.size == 2 && modHead == "result")
            val argsList =
                (lastSeg.arguments as PathArguments.AngleBracketed)
                    .args
                    .toList()
                    .mapNotNull { renderGenericArgument(it, selfTypeName) }
            if (isStdOrCoreResult &&
                argsList.size == 2 &&
                (rawSegNames.size >= 2 || "Result" !in inspector.declaredTypeNames)
            ) {
                return when {
                    usesTaggedCoreResult() && "Result" in inspector.declaredTypeNames ->
                        "${CoreIntrinsicLowering.resolveCoreNamespace(headerContext)}.result.Result<${argsList.joinToString(", ")}>"
                    usesTaggedCoreResult() -> "${sourceResultName()}<${argsList.joinToString(", ")}>"
                    "Result" in inspector.declaredTypeNames -> "kotlin.Result<${argsList[0]}>"
                    else -> "Result<${argsList[0]}>"
                }
            }
        }
    }

    if (!headerContext.rawAbiMode &&
        rawName == "Result" &&
        "Result" !in inspector.declaredTypeNames &&
        !hasDeclaredResultEnum() &&
        lastSeg.arguments is PathArguments.AngleBracketed
    ) {
        val typeArgs =
            (lastSeg.arguments as PathArguments.AngleBracketed)
                .args
                .toList()
                .mapNotNull { renderGenericArgument(it, selfTypeName) }
        if (typeArgs.size == 1) {
            val rawSegNames =
                rewriteCrateRootAliasAndReexportSegments(
                    rawSegments.map { it.ident.toString().removePrefix("r#") },
                )
            val modHead = rawSegNames.getOrNull(rawSegNames.size - 2)
            val resolvedModImp =
                if (rawSegNames.size == 2 && modHead != null) {
                    scopeBuilder.resolveModuleBinding(modHead)?.importedPath
                } else {
                    null
                }
            val matchesStdMod =
                { expected: String ->
                    modHead == expected &&
                        (
                            (rawSegNames.size == 3 && rawSegNames[0] in setOf("std", "crate")) ||
                                (
                                    rawSegNames.size == 2 &&
                                        (
                                            resolvedModImp == null ||
                                                resolvedModImp.startsWith("std.") ||
                                                resolvedModImp.startsWith("crate.") ||
                                                headerContext.packageName?.startsWith("io.github.kotlinmania.rustlang.std") == true
                                        )
                                )
                        )
                }
            val isTaggedOrRustLangStd =
                usesTaggedCoreResult() ||
                    headerContext.packageName?.startsWith("io.github.kotlinmania.rustlang.") == true
            if (matchesStdMod("io") && isTaggedOrRustLangStd) {
                val stdNs =
                    headerContext.crateNamespaces["std"]
                        ?.trim()
                        ?.removeSuffix(".")
                        ?.takeIf { it.isNotEmpty() }
                        ?: "io.github.kotlinmania.rustlang.std".takeIf {
                            headerContext.packageName?.startsWith(it) == true
                        }
                val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
                val ioErr =
                    if (stdNs != null ||
                        "core" in headerContext.crateNamespaces ||
                        headerContext.packageName?.startsWith("$coreNs.") == true
                    ) {
                        bindOrQualifyResolvedCrossSymbol("$coreNs.io.error.Error")
                    } else {
                        "Error"
                    }
                val resName = if (usesTaggedCoreResult()) sourceResultName() else "Result"
                return "$resName<${typeArgs[0]}, $ioErr>"
            }
            if (matchesStdMod("thread") && isTaggedOrRustLangStd) {
                usesRustAny = true
                val resName = if (usesTaggedCoreResult()) sourceResultName() else "Result"
                return "$resName<${typeArgs[0]}, RustAny>"
            }
        }
    }

    var externalQualifiedOverride: String? = null
    val crossTarget =
        if (rawSegments.size >= 2 && rawSegments.dropLast(1).all { it.arguments is PathArguments.None }) {
            resolveCrossModulePathTarget(rawSegments.map { it.ident.toString() }, inTypePosition = true)
        } else {
            null
        }
    val prefixQualifier =
        if (crossTarget != null) {
            val (resolvedSym, trailing) = crossTarget
            val boundHead = bindOrQualifyResolvedCrossSymbol(resolvedSym)
            if (trailing.isEmpty()) {
                externalQualifiedOverride = boundHead
                ""
            } else {
                val midPrefix = trailing.dropLast(1).map(::rustTypeIdentToKotlin)
                (listOf(boundHead) + midPrefix).joinToString(".") + "."
            }
        } else {
            val prefixSegments =
                rawSegments
                    .dropLast(1)
                    .map { it.ident.toString() }
                    .filter {
                        it !in IGNORED_PATH_PREFIXES &&
                            it !in STDLIB_PRELUDE_REEXPORT_MODULES &&
                            it !in inspector.localInlineModuleNames &&
                            (it.first().isUpperCase() || (scopeBuilder.isImportedModuleInScope(it) && !isSuppressedStdlibModuleAlias(it)))
                    }.map {
                        if (it.first().isUpperCase()) {
                            rustTypeIdentToKotlin(it)
                        } else {
                            scopeBuilder.resolveModuleBinding(it)?.kotlinName ?: rustModuleIdentToKotlin(it)
                        }
                    }
            if (prefixSegments.isNotEmpty()) prefixSegments.joinToString(".") + "." else ""
        }

    val isStdlibUnqualified =
        (prefixQualifier.isEmpty() && externalQualifiedOverride == null) ||
            rawSegments.dropLast(1).all { it.ident.toString() in STDLIB_TYPE_PATH_PREFIXES }
    if (isStdlibUnqualified && lastSeg.arguments is PathArguments.AngleBracketed) {
        val typeArgs =
            (lastSeg.arguments as PathArguments.AngleBracketed)
                .args
                .toList()
                .mapNotNull { renderGenericArgument(it, selfTypeName) }
        if (rawName == "Option" && !hasDeclaredOptionEnum() && typeArgs.size == 1) {
            val inner = typeArgs[0]
            return if (isAlreadyNullableRenderedType(inner)) inner else "$inner?"
        }
        if (rawName == "Padding" && "Padding" !in inspector.declaredTypeNames && typeArgs.size == 1) {
            return typeArgs[0]
        }
        if (rawName == "MaybeUninit" && "MaybeUninit" !in inspector.declaredTypeNames && typeArgs.size == 1) {
            val inner = typeArgs[0]
            val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
            CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.mem.maybeuninit.MaybeUninit")
            return "MaybeUninit<$inner>"
        }
        val pathHasSlice = rawSegments.any { it.ident.toString() == "slice" }
        val pathHasVecOrOption = rawSegments.any { it.ident.toString() == "vec" || it.ident.toString() == "option" }
        if ((rawName == "Iter" || rawName == "IterMut") && pathHasSlice && typeArgs.isNotEmpty()) {
            return "ArrayDeque<${typeArgs[0]}>"
        }
        if (rawName == "IntoIter" && pathHasVecOrOption && typeArgs.isNotEmpty()) {
            return "ArrayDeque<${typeArgs[0]}>"
        }
        // `std::result::Result` names the standard type even when this file declares its own
        // `Result` (commonly `type Result<T> = std::result::Result<T, Error>`); qualify it so it
        // cannot resolve to that local declaration.
        val isExplicitStdResultPath =
            rawName == "Result" &&
                rawSegments.size >= 3 &&
                rawSegments[rawSegments.size - 3].ident.toString() in setOf("std", "core") &&
                rawSegments[rawSegments.size - 2].ident.toString() == "result"
        if (isExplicitStdResultPath && rawName in inspector.declaredTypeNames && typeArgs.isNotEmpty()) {
            val coreNs =
                headerContext.crateNamespaces["core"]
                    ?.trim()
                    ?.removeSuffix(".")
                    ?.takeIf { it.isNotEmpty() }
            return if (coreNs != null && typeArgs.size == 2) {
                "$coreNs.result.Result<${typeArgs.joinToString(", ")}>"
            } else {
                "kotlin.Result<${typeArgs[0]}>"
            }
        }
        if (rawName !in inspector.declaredTypeNames) {
            if (rawName in setOf("Cell", "RefCell", "OnceCell") && typeArgs.size == 1) {
                val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
                val subpkg = if (rawName == "OnceCell") "cell.once" else "cell"
                CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.$subpkg.$rawName")
                return "$rawName<${typeArgs[0]}>"
            }
            val isErasedSmartPointer =
                rawName == "Box" ||
                    rawName == "Rc" ||
                    rawName == "Arc" ||
                    rawName == "NoDrop" ||
                    rawName == "ManuallyDrop"
            when {
                rawName == "Vec" && typeArgs.isNotEmpty() -> return "List<${typeArgs[0]}>"
                rawName == "Cow" && typeArgs.isNotEmpty() -> return typeArgs.last()
                (rawName == "Range" || rawName == "RangeInclusive") && typeArgs.size == 1 && typeArgs[0] == "Int" ->
                    return "IntRange"
                isErasedSmartPointer && typeArgs.isNotEmpty() -> return typeArgs[0]
                (rawName == "HashMap" || rawName == "BTreeMap") && typeArgs.size >= 2 ->
                    return "MutableMap<${typeArgs[0]}, ${typeArgs[1]}>"
                (rawName == "HashSet" || rawName == "BTreeSet") && typeArgs.isNotEmpty() ->
                    return "MutableSet<${typeArgs[0]}>"
                rawName == "Result" && "Ok" !in inspector.declaredTypeNames && !hasDeclaredResultEnum() && usesTaggedCoreResult() && typeArgs.size == 2 ->
                    return "${sourceResultName()}<${typeArgs.joinToString(", ")}>"
                rawName == "Result" && "Ok" !in inspector.declaredTypeNames && !hasDeclaredResultEnum() && !usesTaggedCoreResult() && typeArgs.isNotEmpty() ->
                    return "Result<${typeArgs[0]}>"
            }
        }
    } else if (isStdlibUnqualified && lastSeg.arguments is PathArguments.None && rawName == "Vec" && "Vec" !in inspector.declaredTypeNames) {
        return "List<*>"
    }

    if (rawName == "TypeId" &&
        lastSeg.arguments is PathArguments.None &&
        "TypeId" !in inspector.declaredTypeNames &&
        isStdlibAnyPathSegments(rawSegments.map { it.ident.toString() })
    ) {
        usesRustAny = true
        return "RustTypeId"
    }

    val isPrimitivePrefix =
        rawSegments.size == 1 ||
            rawSegments.dropLast(1).all {
                val p = it.ident.toString()
                p in IGNORED_PATH_PREFIXES || p == "primitive"
            }
    if ((inExternAbiSignature || headerContext.rawAbiMode) && lastSeg.arguments is PathArguments.None && isPrimitivePrefix) {
        if (rawName == "usize") {
            return when (activeCfgProfile.pointerWidth) {
                16 -> "UShort"
                32 -> "UInt"
                else -> "ULong"
            }
        }
        if (rawName == "isize") {
            return when (activeCfgProfile.pointerWidth) {
                16 -> "Short"
                32 -> "Int"
                else -> "Long"
            }
        }
    }

    if (isPrimitivePrefix && lastSeg.arguments is PathArguments.None) {
        if (rawName == "u128") usesU128 = true
        if (rawName == "i128") usesI128 = true
        if (rawName == "f16") return "F16"
        if (rawName == "f32") return kotlinF32TypeName()
        if (rawName == "f128") return "F128"
        if (rawName == "char" && headerContext.scalarChar && "Char" !in inspector.declaredTypeNames) {
            usesRustChar = true
            return "RustChar"
        }
        when (rawName) {
            "i8" -> return kotlinBuiltinTypeName("Byte")
            "i16" -> return kotlinBuiltinTypeName("Short")
            "i32" -> return kotlinBuiltinTypeName("Int")
            "i64" -> return kotlinBuiltinTypeName("Long")
            "isize" -> return kotlinBuiltinTypeName("Int")
            "u8" -> return kotlinBuiltinTypeName("UByte")
            "u16" -> return kotlinBuiltinTypeName("UShort")
            "u32" -> return kotlinBuiltinTypeName("UInt")
            "u64" -> return kotlinBuiltinTypeName("ULong")
            "usize" -> return kotlinBuiltinTypeName("Int")
            "f64" -> return kotlinBuiltinTypeName("Double")
            "bool" -> return kotlinBuiltinTypeName("Boolean")
            "str" -> return kotlinBuiltinTypeName("String")
            "char" -> return kotlinBuiltinTypeName("Char")
        }
        if (rawName == "c_void" &&
            !headerContext.rawAbiMode &&
            "CVoid" !in inspector.declaredTypeNames &&
            "CVoid" !in importedSimpleNameToPkg &&
            resolveBestSubpackageForSymbol("CVoid") == null
        ) {
            resolveStdlibFfiSymbolImport("c_void")?.let {
                CoreIntrinsicLowering.registerCoreImport(this, it)
            }
        }
    }
    val mappedRawBaseIdent = rustTypeIdentToKotlin(rawName)
    val ktBaseIdent =
        if (rawSegments.size == 1 && prefixQualifier.isEmpty() && externalQualifiedOverride == null) {
            samePackageUseRenames[mappedRawBaseIdent] ?: mappedRawBaseIdent
        } else {
            mappedRawBaseIdent
        }
    if (rawSegments.size == 1 &&
        prefixQualifier.isEmpty() &&
        externalQualifiedOverride == null &&
        rawName != "Self" &&
        ktBaseIdent != selfTypeName?.substringBefore('<')?.trim() &&
        ktBaseIdent in activeEnclosingEnumVariantNames &&
        ktBaseIdent !in activeTypeParamNames &&
        rawName !in PRIMITIVE_TYPE_NAMES
    ) {
        val outerPkg =
            importedSimpleNameToPkg[ktBaseIdent]
                ?: headerContext.packageName
                    ?.takeIf {
                        it.isNotEmpty() &&
                            (ktBaseIdent in inspector.declaredTypeNames || ktBaseIdent in headerContext.moduleDeclaredTypeNames)
                    }
                ?: resolveBestSubpackageForSymbol(ktBaseIdent)?.let { sub ->
                    currentNamespacePrefix()?.let { ns ->
                        if (sub.isEmpty()) ns else "$ns.$sub"
                    }
                }
        if (!outerPkg.isNullOrEmpty()) {
            externalQualifiedOverride = "$outerPkg.$ktBaseIdent"
        }
    }
    if (prefixQualifier.isEmpty() &&
        externalQualifiedOverride == null &&
        rawSegments.size <= 2 &&
        rawName != "Self" &&
        isPrimitivePrefix &&
        ktBaseIdent !in inspector.declaredTypeNames &&
        ktBaseIdent !in activeTypeParamNames &&
        !(ktBaseIdent.length == 1 && ktBaseIdent[0] in 'A'..'Z') &&
        ktBaseIdent !in inspector.enumVariantSimpleNames &&
        ktBaseIdent !in inspector.importedEnumVariantSimpleNames &&
        (ktBaseIdent !in KOTLIN_STDLIB_SHADOWED_IMPORTS || rawName == "Float") &&
        headerContext.crateSymbolSubpackages.isNotEmpty()
    ) {
        val bestSubpkg = resolveBestSubpackageForSymbol(ktBaseIdent)
        val nsPrefix = currentNamespacePrefix()
        if (bestSubpkg != null && nsPrefix != null) {
            val targetPkg = if (bestSubpkg.isEmpty()) nsPrefix else "$nsPrefix.$bestSubpkg"
            if (targetPkg.isNotEmpty() && targetPkg != headerContext.packageName) {
                val collidedPkg = importedSimpleNameToPkg[ktBaseIdent]
                if (collidedPkg != null && collidedPkg != targetPkg) {
                    externalQualifiedOverride = "$targetPkg.$ktBaseIdent"
                } else {
                    importedSimpleNameToPkg[ktBaseIdent] = targetPkg
                    extraTypePathImports += "import $targetPkg.$ktBaseIdent"
                }
            }
        }
    }
    val mappedBase =
        when {
            rawName == "Self" -> selfTypeName ?: "Any"
            externalQualifiedOverride != null -> externalQualifiedOverride
            else -> prefixQualifier + ktBaseIdent
        }

    val traitDecl = lookupDeclaredTraitAst(ktBaseIdent, path)
    val isCoreOpsName =
        ktBaseIdent in CORE_OPS_BINARY_TRAITS ||
            ktBaseIdent in CORE_OPS_UNARY_OUTPUT_TRAITS ||
            ktBaseIdent in CORE_OPS_ASSIGN_TRAITS
    val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
    val selfOpsSubpkg = resolveBestSubpackageForSymbol(ktBaseIdent)
    val isExternalCoreOps =
        traitDecl == null &&
            ktBaseIdent !in inspector.declaredTypeNames &&
            isCoreOpsName &&
            (
                (
                    prefixQualifier.isEmpty() &&
                        externalQualifiedOverride == null &&
                        (selfOpsSubpkg == null || selfOpsSubpkg.startsWith("ops"))
                ) ||
                    importedSimpleNameToPkg[ktBaseIdent]?.startsWith("$coreNs.ops") == true ||
                    prefixQualifier == "ops." ||
                    prefixQualifier.endsWith(".ops.")
            )
    val bounded = currentBoundedTypeName
    val opsDefaultRhs = bounded ?: selfTypeName ?: "Any?"
    val declaredTypeParams =
        traitDecl
            ?.generics
            ?.params
            ?.toList()
            ?.filterIsInstance<GenericParam.TypeParam>()
            .orEmpty()
    val lookupAssocs = lookupTraitDefaultlessAssocTypes(ktBaseIdent, path)
    val isKnownCoreOps = isExternalCoreOps
    val effectiveLookupAssocs =
        if (lookupAssocs.isNotEmpty()) {
            lookupAssocs
        } else if (isKnownCoreOps && (ktBaseIdent in CORE_OPS_BINARY_TRAITS || ktBaseIdent in CORE_OPS_UNARY_OUTPUT_TRAITS)) {
            listOf("Output")
        } else {
            emptyList()
        }
    if (isKnownCoreOps &&
        prefixQualifier.isEmpty() &&
        externalQualifiedOverride == null &&
        ktBaseIdent !in importedSimpleNameToPkg &&
        !headerContext.rawAbiMode &&
        "core" in headerContext.crateNamespaces
    ) {
        val extCoreOpsPkg = headerContext.externalCrateSymbolPackages["core"]?.get(ktBaseIdent)?.firstOrNull()
        val selfCoreOpsPkg = selfOpsSubpkg?.let { if (it.isEmpty()) coreNs else "$coreNs.$it" }
        val opsFqcn =
            when {
                extCoreOpsPkg != null -> "$extCoreOpsPkg.$ktBaseIdent"
                selfCoreOpsPkg != null -> "$selfCoreOpsPkg.$ktBaseIdent"
                else -> "$coreNs.ops.$ktBaseIdent"
            }
        CoreIntrinsicLowering.registerCoreImport(this, opsFqcn)
    }

    return when (val args = lastSeg.arguments) {
        is PathArguments.None -> {
            when {
                isExternalCoreOps && ktBaseIdent in CORE_OPS_BINARY_TRAITS ->
                    if (suppressTraitAssocPlaceholders) {
                        "$mappedBase<$opsDefaultRhs>"
                    } else {
                        "$mappedBase<$opsDefaultRhs, Any?>"
                    }
                isExternalCoreOps && ktBaseIdent in CORE_OPS_UNARY_OUTPUT_TRAITS ->
                    if (suppressTraitAssocPlaceholders) mappedBase else "$mappedBase<Any?>"
                isExternalCoreOps && ktBaseIdent in CORE_OPS_ASSIGN_TRAITS ->
                    "$mappedBase<$opsDefaultRhs>"
                else -> {
                    val defaultedParams =
                        declaredTypeParams.mapNotNull { gp ->
                            gp.default?.type?.let { renderType(it, selfTypeName = bounded ?: selfTypeName) }
                        }
                    val typeArgs =
                        buildList {
                            addAll(defaultedParams)
                            if (bounded != null && isSelfParameterizedTraitName(ktBaseIdent)) {
                                add(bounded)
                            } else if (bounded == null &&
                                !suppressTraitAssocPlaceholders &&
                                isSelfParameterizedTraitName(ktBaseIdent) &&
                                defaultedParams.isEmpty() &&
                                effectiveLookupAssocs.isEmpty() &&
                                ktBaseIdent !in activeTypeParamNames
                            ) {
                                add("*")
                            }
                            for (assocKt in effectiveLookupAssocs) {
                                if (assocKt in currentTraitAssocParamNames) {
                                    add(assocKt)
                                } else if (bounded != null && !suppressTraitAssocPlaceholders) {
                                    add("Any?")
                                }
                            }
                        }
                    if (typeArgs.isEmpty()) mappedBase else "$mappedBase<${typeArgs.joinToString(", ")}>"
                }
            }
        }
        is PathArguments.AngleBracketed -> {
            val declaredTraitAssocs =
                if (effectiveLookupAssocs.isNotEmpty() || ktBaseIdent in inspector.declaredTypeNames || isKnownCoreOps) {
                    effectiveLookupAssocs
                } else {
                    null
                }
            val rawArgList = args.args.toList()
            if (traitDecl != null || isKnownCoreOps) {
                val explicitPositionalArgs =
                    rawArgList
                        .filter { it !is GenericArgument.AssocTypeArg && it !is GenericArgument.LifetimeArg }
                        .mapNotNull { renderGenericArgument(it, selfTypeName) }
                        .toMutableList()
                val explicitAssocByName = linkedMapOf<String, String>()
                for (ga in rawArgList) {
                    if (ga is GenericArgument.AssocTypeArg) {
                        val assocKt = rustTypeIdentToKotlin(ga.assoc.ident.toString())
                        if (declaredTraitAssocs == null || assocKt in declaredTraitAssocs) {
                            explicitAssocByName[assocKt] = renderType(ga.assoc.ty, selfTypeName)
                        }
                    }
                }
                if (isKnownCoreOps) {
                    if ((ktBaseIdent in CORE_OPS_BINARY_TRAITS || ktBaseIdent in CORE_OPS_ASSIGN_TRAITS) &&
                        explicitPositionalArgs.isEmpty()
                    ) {
                        explicitPositionalArgs += opsDefaultRhs
                    }
                } else if (declaredTypeParams.isNotEmpty() && explicitPositionalArgs.size < declaredTypeParams.size) {
                    for (idx in explicitPositionalArgs.size until declaredTypeParams.size) {
                        val defTy = declaredTypeParams[idx].default?.type ?: break
                        explicitPositionalArgs += renderType(defTy, selfTypeName = bounded ?: selfTypeName)
                    }
                }
                val finalAssocs =
                    effectiveLookupAssocs.mapNotNull { assocKt ->
                        explicitAssocByName[assocKt]
                            ?: if (assocKt in currentTraitAssocParamNames) {
                                assocKt
                            } else if (!suppressTraitAssocPlaceholders && (bounded != null || isKnownCoreOps)) {
                                "Any?"
                            } else {
                                null
                            }
                    }
                val typeArgs =
                    buildList {
                        addAll(explicitPositionalArgs)
                        if (bounded != null && isSelfParameterizedTraitName(ktBaseIdent)) {
                            add(bounded)
                        }
                        addAll(finalAssocs)
                        addAll(sourceAssociatedArguments(path, selfTypeName))
                    }
                if (typeArgs.isEmpty()) mappedBase else "$mappedBase<${typeArgs.joinToString(", ")}>"
            } else {
                val filteredArgs =
                    rawArgList.filter { ga ->
                        if (ga is GenericArgument.AssocTypeArg && declaredTraitAssocs != null) {
                            rustTypeIdentToKotlin(ga.assoc.ident.toString()) in declaredTraitAssocs
                        } else {
                            true
                        }
                    }
                val rawSegNames = rawSegments.map { it.ident.toString().removePrefix("r#") }
                val explicitArgs = filteredArgs.mapNotNull { renderGenericArgument(it, selfTypeName) }
                val rawTypeArgs = rawArgList.filterIsInstance<GenericArgument.TypeArg>()
                if (ktBaseIdent == "Atomic" &&
                    "Atomic" !in inspector.declaredTypeNames &&
                    !headerContext.rawAbiMode &&
                    "core" in headerContext.crateNamespaces &&
                    rawTypeArgs.size == 1
                ) {
                    val concreteAtomic = resolveConcreteStdlibAtomicSimpleName(rawTypeArgs[0].type, explicitArgs.firstOrNull())
                    if (concreteAtomic != null) {
                        return bindOrQualifyResolvedCrossSymbol("$coreNs.sync.atomic.$concreteAtomic")
                    }
                }
                val explicitAssocNames =
                    rawArgList
                        .filterIsInstance<GenericArgument.AssocTypeArg>()
                        .mapTo(hashSetOf()) { rustTypeIdentToKotlin(it.assoc.ident.toString()) }
                val defaultedArgs = appendStdlibDefaultTypeArgsIfNeeded(mappedBase, rawName, rawSegNames, explicitArgs, selfTypeName)
                val lookupAssocs =
                    lookupTraitDefaultlessAssocTypes(ktBaseIdent, path)
                        .filter { it in currentTraitAssocParamNames && it !in explicitAssocNames && it !in explicitArgs }
                val typeArgs = defaultedArgs + lookupAssocs + sourceAssociatedArguments(path, selfTypeName)
                if (typeArgs.isEmpty()) mappedBase else "$mappedBase<${typeArgs.joinToString(", ")}>"
            }
        }
        is PathArguments.Parenthesized -> {
            val inputs = args.inputs.toList().joinToString(", ") { renderType(it.ty, selfTypeName) }
            val out = extractReturnSynType(args.output)?.let { renderType(it, selfTypeName) } ?: "Unit"
            "($inputs) -> $out"
        }
    }
}

// Rust const generic identifiers can parse as type arguments. Keep bound
// value names out of Kotlin's type list and forward them as runtime arguments.
internal fun KotlinJ2KEmitter.runtimeConstTypeArgument(arg: GenericArgument.TypeArg): String? {
    val ty = arg.type as? SynType.Path ?: return null
    if (ty.qself != null) return null
    val segments = ty.path.segments.toList()
    if (segments.size != 1 || segments[0].arguments !is PathArguments.None) return null
    val raw = segments[0].ident.toString().removePrefix("r#")
    val typeName = rustTypeIdentToKotlin(raw)
    if (typeName in activeTypeParamNames || typeName in inspector.declaredTypeNames) return null
    if (raw in activeConstParamNames || rustValueIdentToKotlin(raw) in activeConstParamNames) {
        return rustValueIdentToKotlin(raw)
    }
    scopeBuilder.resolveLocalKotlinName(raw)?.let { return it }
    inspector.declaredConstNames[raw]?.let { return it }
    if (raw.length >= 2 && isScreamingSnakeIdent(raw)) {
        val constKt = rustConstIdentToKotlin(raw)
        if (constKt !in inspector.declaredTypeNames &&
            constKt !in importedSimpleNameToPkg &&
            headerContext.crateSymbolSubpackages.isNotEmpty()
        ) {
            val sub = resolveBestSubpackageForSymbol(constKt)
            val ns = currentNamespacePrefix()
            if (sub != null && ns != null) {
                val pkg = if (sub.isEmpty()) ns else "$ns.$sub"
                if (pkg != headerContext.packageName) {
                    importedSimpleNameToPkg[constKt] = pkg
                    extraTypePathImports += "import $pkg.$constKt"
                }
            }
        }
        return constKt
    }
    return null
}

internal fun KotlinJ2KEmitter.renderGenericArgument(arg: GenericArgument, selfTypeName: String?): String? =
    when (arg) {
        is GenericArgument.LifetimeArg -> null
        is GenericArgument.TypeArg -> if (arg.type is SynType.Infer || runtimeConstTypeArgument(arg) != null) null else renderType(arg.type, selfTypeName)
        is GenericArgument.ConstArg -> null
        is GenericArgument.AssocTypeArg -> renderType(arg.assoc.ty, selfTypeName)
        is GenericArgument.AssocConstArg -> null
        is GenericArgument.ConstraintArg -> null
    }

internal fun KotlinJ2KEmitter.renderTypeParameters(
    generics: Generics,
    selfTypeName: String?,
    excludeNames: Set<String> = emptySet(),
): String {
    val ownerSimple = selfTypeName?.substringBefore('<')?.substringAfterLast('.')?.trim()
    val declared = renderTypeParametersFromParams(generics.params.toList(), selfTypeName, excludeNames).removePrefix("<").removeSuffix(">")
    val associated =
        sourceAssociatedParameters(generics, ownerSimple)
            .map { it.associated }
            .distinct()
            .filter { it !in excludeNames }
    val parts = listOf(declared).filter(String::isNotEmpty) + associated
    return if (parts.isEmpty()) "" else "<${parts.joinToString(", ")}>"
}

internal fun KotlinJ2KEmitter.renderTypeParametersFromParams(
    params: List<GenericParam>,
    selfTypeName: String?,
    excludeNames: Set<String> = emptySet(),
): String {
    val rendered = mutableListOf<String>()
    val seenNames = hashSetOf<String>()
    for (param in params) {
        when (param) {
            is GenericParam.LifetimeParam -> {}
            is GenericParam.TypeParam -> {
                val rawTpName = rustTypeIdentToKotlin(param.ident.toString())
                val name = renamedTypeParamIdentifiers[rawTpName] ?: rawTpName
                if (rawTpName in excludeNames || name in excludeNames || !seenNames.add(name)) continue
                val prevBounded = currentBoundedTypeName
                currentBoundedTypeName = name
                val bounds =
                    try {
                        param.bounds
                            .toList()
                            .mapNotNull { renderTypeParamBound(it, selfTypeName) }
                            .filter { it != "Any" && it != name && !it.contains("->") && !it.startsWith("SimdElement") }
                    } finally {
                        currentBoundedTypeName = prevBounded
                    }
                if (bounds.size == 1) {
                    rendered += "$name : ${bounds[0]}"
                } else {
                    rendered += name
                }
            }
            is GenericParam.ConstParam -> {}
        }
    }
    return if (rendered.isEmpty()) "" else "<${rendered.joinToString(", ")}>"
}

internal fun KotlinJ2KEmitter.renderTypeArgumentsFromGenerics(
    generics: Generics,
    ownerTypeSimpleName: String? = null,
): String {
    val names =
        generics.params.toList().mapNotNull { param ->
            (param as? GenericParam.TypeParam)?.ident?.toString()?.let { rawIdent ->
                val rawTp = rustTypeIdentToKotlin(rawIdent)
                renamedTypeParamIdentifiers[rawTp] ?: rawTp
            }
        }
    val allNames = (names + sourceAssociatedParameters(generics, ownerTypeSimpleName).map { it.associated }).distinct()
    return if (allNames.isEmpty()) "" else "<${allNames.joinToString(", ")}>"
}

internal fun KotlinJ2KEmitter.renderWhereClause(
    whereClause: WhereClause?,
    selfTypeName: String?,
    ownerGenerics: Generics? = null,
    extraDeclaredNames: Set<String> = emptySet(),
): String {
    val ownerSimple = selfTypeName?.substringBefore('<')?.substringAfterLast('.')?.trim()
    val assocDeclared = ownerGenerics?.let { sourceAssociatedParameters(it, ownerSimple).map { a -> a.associated } }.orEmpty()
    return renderCombinedWhereClause(
        ownerWhere = null,
        fnWhere = whereClause,
        params = ownerGenerics?.params?.toList().orEmpty(),
        selfTypeName = selfTypeName,
        extraDeclaredNames = extraDeclaredNames + assocDeclared,
    )
}

internal fun KotlinJ2KEmitter.renderCombinedWhereClause(
    ownerWhere: WhereClause?,
    fnWhere: WhereClause?,
    params: List<GenericParam>,
    selfTypeName: String?,
    excludeNames: Set<String> = emptySet(),
    extraDeclaredNames: Set<String> = emptySet(),
): String {
    val declaredTypeParamNames =
        (
            params
                .mapNotNull { (it as? GenericParam.TypeParam)?.ident?.toString()?.let(::rustTypeIdentToKotlin) }
                .filter { it !in excludeNames }
                .map { renamedTypeParamIdentifiers[it] ?: it }
                .filter { it !in excludeNames } +
                extraDeclaredNames.filter { it !in excludeNames }
        ).toSet()
    if (declaredTypeParamNames.isEmpty()) return ""
    val clauses = mutableListOf<String>()
    val seenParamNames = hashSetOf<String>()
    for (param in params) {
        if (param is GenericParam.TypeParam) {
            val rawTpName = rustTypeIdentToKotlin(param.ident.toString())
            val name = renamedTypeParamIdentifiers[rawTpName] ?: rawTpName
            if (rawTpName in excludeNames || name in excludeNames || !seenParamNames.add(name)) continue
            val prevBounded = currentBoundedTypeName
            currentBoundedTypeName = name
            val bounds =
                try {
                    param.bounds
                        .toList()
                        .mapNotNull { renderTypeParamBound(it, selfTypeName) }
                        .filter { it != "Any" && it != name && !it.contains("->") }
                } finally {
                    currentBoundedTypeName = prevBounded
                }
            if (bounds.size > 1) {
                for (b in bounds) {
                    clauses += "$name : $b"
                }
            }
        }
    }
    val predicates =
        buildList {
            ownerWhere?.predicates?.toList()?.let { addAll(it) }
            fnWhere?.predicates?.toList()?.let { addAll(it) }
        }
    for (pred in predicates) {
        if (pred is WherePredicate.TypePredicate) {
            if (!isSimpleTypeParamWhereTarget(pred.boundedTy)) continue
            val boundedTy = renderType(pred.boundedTy, selfTypeName)
            if (boundedTy in excludeNames || !isValidKotlinIdentifierText(boundedTy)) continue
            if (boundedTy !in declaredTypeParamNames) continue
            val prevBounded = currentBoundedTypeName
            currentBoundedTypeName = boundedTy
            try {
                for (bound in pred.bounds.toList()) {
                    val b = renderTypeParamBound(bound, selfTypeName)
                    if (b != null && b != "Any" && b != boundedTy && !b.contains("->") && !b.startsWith("SimdElement")) {
                        clauses += "$boundedTy : $b"
                    }
                }
            } finally {
                currentBoundedTypeName = prevBounded
            }
        }
    }
    val distinct = clauses.distinct()
    return if (distinct.isEmpty()) "" else " where ${distinct.joinToString(", ")}"
}

internal fun KotlinJ2KEmitter.renderTypeParamBound(bound: TypeParamBound, selfTypeName: String?): String? =
    when (bound) {
        is TypeParamBound.LifetimeBound -> null
        is TypeParamBound.PreciseCapture -> null
        is TypeParamBound.Verbatim -> null
        is TypeParamBound.Trait -> {
            if (bound.maybe != null) {
                null
            } else {
                val segs = bound.path.segments.toList()
                val lastSeg = segs.lastOrNull()
                val last = lastSeg?.ident?.toString()
                val ktTrait = last?.let(::rustTypeIdentToKotlin)
                val boundedTp = currentBoundedTypeName
                val isPrivateSealedBound =
                    segs.size >= 2 &&
                        segs[segs.size - 2].ident.toString() in setOf("private", "sealed") &&
                        last == "Sealed"
                if (last == null ||
                    isPrivateSealedBound ||
                    last in IGNORED_TRAIT_BOUNDS ||
                    (
                        ktTrait != null &&
                            lookupTraitDefaultlessAssocTypes(ktTrait, bound.path).isNotEmpty() &&
                            sameFileNestedItems(file.items).filterIsInstance<Item.Impl>().any {
                                it.traitPath
                                    ?.path
                                    ?.segments
                                    ?.last()
                                    ?.ident
                                    .toString() == last && fixedScalarPointerCodec(it.selfType) != null
                            }
                    ) ||
                    (boundedTp != null && ktTrait != null && ktTrait in currentWitnessedTraitBounds[boundedTp].orEmpty())
                ) {
                    null
                } else if (boundedTp != null &&
                    (last == "IntoIterator" || last in setOf("Iterator", "DoubleEndedIterator", "ExactSizeIterator", "FusedIterator")) &&
                    ktTrait !in inspector.declaredTypeNames
                ) {
                    val angle = lastSeg.arguments as? PathArguments.AngleBracketed
                    val itemTy =
                        angle
                            ?.args
                            ?.toList()
                            ?.firstNotNullOfOrNull { ga ->
                                if (ga is GenericArgument.AssocTypeArg && ga.assoc.ident.toString() == "Item") {
                                    renderType(ga.assoc.ty, selfTypeName)
                                } else {
                                    null
                                }
                            }
                    if (itemTy != null) {
                        if (last == "IntoIterator") "Iterable<$itemTy>" else "Iterator<$itemTy>"
                    } else if (last != "IntoIterator" && "Item" in activeTypeParamNames) {
                        "Iterator<Item>"
                    } else {
                        null
                    }
                } else if (isImplicitStdlibSupertraitPath(bound.path)) {
                    null
                } else {
                    renderPathAsType(bound.path, selfTypeName)
                }
            }
        }
    }

internal fun KotlinJ2KEmitter.renderVisibility(vis: Visibility): String =
    when (vis) {
        is Visibility.Public -> "public "
        is Visibility.Restricted -> if (headerContext.isBatchCrateOutput) "public " else "internal "
        is Visibility.Inherited -> "public "
    }

internal fun KotlinJ2KEmitter.hasTestAttribute(attrs: List<Attribute>): Boolean {
    for (attr in attrs) {
        val path = attr.path()
        if (path.isIdent("test")) return true
        val lastSeg =
            path.segments
                .last()
                ?.ident
                ?.toString()
        if (lastSeg == "test") return true
        val meta = attr.meta
        if (meta is Meta.List && meta.path.isIdent("cfg")) {
            if (containsPositiveCfgTestIdent(meta.tokens.clone())) return true
        }
    }
    return false
}

internal fun KotlinJ2KEmitter.containsPositiveCfgTestIdent(stream: TokenStream): Boolean {
    val trees = stream.clone().toList()
    var idx = 0
    while (idx < trees.size) {
        when (val tree = trees[idx]) {
            is TokenTree.Ident -> {
                val word = tree.value.toString()
                if (word == "not" && idx + 1 < trees.size && trees[idx + 1] is TokenTree.Group) {
                    idx += 2
                    continue
                }
                if (word == "test") return true
            }
            is TokenTree.Group -> {
                if (containsPositiveCfgTestIdent(tree.value.stream())) return true
            }
            else -> {}
        }
        idx++
    }
    return false
}

internal fun KotlinJ2KEmitter.isTransparentWrapperPath(path: Path): Boolean {
    val segs = path.segments.toList()
    if (segs.size != 2) return false
    val owner = segs[0].ident.toString()
    val member = segs[1].ident.toString()
    return (owner in setOf("Box", "Rc", "Arc", "NoDrop") && member == "new") ||
        (owner == "Box" && (member == "as_ref" || member == "as_mut")) ||
        ((owner == "Rc" || owner == "Arc") && member == "as_ref")
}

internal fun KotlinJ2KEmitter.isTransparentIdentityExpr(body: Expr, rawParamName: String): Boolean =
    when (body) {
        is Expr.Path -> {
            val firstSeg = body.path.segments.first()
            val identText = firstSeg?.ident?.toString()
            body.qself == null &&
                body.path.segments.size == 1 &&
                identText?.removePrefix("r#") == rawParamName
        }
        is Expr.Paren -> isTransparentIdentityExpr(body.expr, rawParamName)
        is Expr.Group -> isTransparentIdentityExpr(body.expr, rawParamName)
        is Expr.Reference -> isTransparentIdentityExpr(body.expr, rawParamName)
        is Expr.BlockExpr -> {
            val single = body.block.stmts.singleOrNull() as? Stmt.ExprStmt
            single != null && single.semiToken == null && isTransparentIdentityExpr(single.expr, rawParamName)
        }
        is Expr.Call -> {
            val fnPath = (body.func as? Expr.Path)?.takeIf { it.qself == null }?.path
            val singleArg = body.args.toList().singleOrNull()
            fnPath != null && singleArg != null && isTransparentWrapperPath(fnPath) && isTransparentIdentityExpr(singleArg, rawParamName)
        }
        else -> false
    }

internal fun KotlinJ2KEmitter.isTransparentWrapperOrIdentityMapArg(argExpr: Expr): Boolean =
    when (argExpr) {
        is Expr.Path -> argExpr.qself == null && isTransparentWrapperPath(argExpr.path)
        is Expr.Closure -> {
            val singleInput = argExpr.inputs.toList().singleOrNull()
            val rawParam =
                when (val unwrappedPat = singleInput?.let(this::unwrapTransparentPat)) {
                    is Pat.Ident -> unwrappedPat.ident.toString().removePrefix("r#")
                    else -> null
                }
            rawParam != null && isTransparentIdentityExpr(argExpr.body, rawParam)
        }
        is Expr.Paren -> isTransparentWrapperOrIdentityMapArg(argExpr.expr)
        is Expr.Group -> isTransparentWrapperOrIdentityMapArg(argExpr.expr)
        else -> false
    }

internal fun KotlinJ2KEmitter.isSimpleTypeParamWhereTarget(ty: SynType): Boolean {
    val pathTy = unwrapSynType(ty) as? SynType.Path ?: return false
    if (pathTy.qself != null || pathTy.path.leadingColon != null) return false
    val segList = pathTy.path.segments.toList()
    val singleSeg = segList.singleOrNull() ?: return false
    return singleSeg.arguments is PathArguments.None
}

internal fun KotlinJ2KEmitter.isValidKotlinIdentifierText(text: String): Boolean {
    if (text.isEmpty()) return false
    val first = text[0]
    if (!first.isLetter() && first != '_') return false
    return text.all { it.isLetterOrDigit() || it == '_' }
}

internal fun KotlinJ2KEmitter.extractLocalTypeAnnotation(pat: Pat, selfTypeName: String?): String? =
    when (pat) {
        is Pat.TypeAscription -> if (pat.ty is SynType.Infer) null else renderType(pat.ty, selfTypeName)
        is Pat.Reference -> extractLocalTypeAnnotation(pat.pat, selfTypeName)
        is Pat.PatParen -> extractLocalTypeAnnotation(pat.pat, selfTypeName)
        else -> null
    }

internal fun KotlinJ2KEmitter.collectGenericTypeParamNames(generics: Generics?): Set<String> {
    if (generics == null) return emptySet()
    return generics.params
        .toList()
        .mapNotNull { param ->
            (param as? GenericParam.TypeParam)?.ident?.toString()?.let(::rustTypeIdentToKotlin)
        }.toSet()
}

internal fun KotlinJ2KEmitter.collectGenericConstParamNames(generics: Generics?): Set<String> {
    if (generics == null) return emptySet()
    return generics.params
        .toList()
        .mapNotNull { param ->
            (param as? GenericParam.ConstParam)?.ident?.toString()?.let(::rustValueIdentToKotlin)
        }.toSet()
}

private val C_POINTER_WIDTH_UNSIGNED_TYPE_NAMES =
    setOf(
        "size_t",
        "SizeT",
        "uintptr_t",
        "UintptrT",
        "rsize_t",
        "RsizeT",
        "__size_t",
    )

private val C_POINTER_WIDTH_SIGNED_TYPE_NAMES =
    setOf(
        "ssize_t",
        "SsizeT",
        "ptrdiff_t",
        "PtrdiffT",
        "intptr_t",
        "IntptrT",
        "__ssize_t",
    )

internal fun isCPointerWidthUnsignedTypeName(name: String): Boolean =
    name in C_POINTER_WIDTH_UNSIGNED_TYPE_NAMES

internal fun isCPointerWidthSignedTypeName(name: String): Boolean =
    name in C_POINTER_WIDTH_SIGNED_TYPE_NAMES

internal fun extractSinglePrimitiveTargetIdent(ty: SynType): String? {
    val pathTy = unwrapSynType(ty) as? SynType.Path ?: return null
    if (pathTy.qself != null) return null
    val segs = pathTy.path.segments.toList()
    if (segs.isEmpty()) return null
    val last = segs.last()
    if (last.arguments !is PathArguments.None) return null
    val validPrefix =
        segs.dropLast(1).all { seg ->
            val p = seg.ident.toString().removePrefix("r#")
            seg.arguments is PathArguments.None && (p in IGNORED_PATH_PREFIXES || p == "primitive")
        }
    return if (validPrefix) last.ident.toString().removePrefix("r#") else null
}

internal fun KotlinJ2KEmitter.resolveNumericKind(kotlinType: String, depth: Int = 0): NumericKind? {
    val base = kotlinType.trim()
    if (base.endsWith("?")) return null
    val simpleBase = base.substringAfterLast('.')
    when (base) {
        "Byte" -> return NumericKind.I8
        "Short" -> return NumericKind.I16
        "Int" -> return NumericKind.I32
        "Long" -> return NumericKind.I64
        "I128" -> return NumericKind.I128
        "UByte" -> return NumericKind.U8
        "UShort" -> return NumericKind.U16
        "UInt" -> return NumericKind.U32
        "ULong" -> return NumericKind.U64
        "U128" -> return NumericKind.U128
        "kotlin.Float" -> return NumericKind.F32
        "Float" -> return if (isKotlinFloatShadowed()) null else NumericKind.F32
        "Double" -> return NumericKind.F64
        "F16" -> return NumericKind.F16
        "F128" -> return NumericKind.F128
    }
    val ptrWidth = activeCfgProfile.pointerWidth
    val isWindows =
        activeCfgProfile.targetOs == "windows" ||
            activeCfgProfile.targetFamilies?.contains("windows") == true
    val ptrSigned =
        when (ptrWidth) {
            64 -> NumericKind.I64
            16 -> NumericKind.I16
            else -> NumericKind.I32
        }
    val ptrUnsigned =
        when (ptrWidth) {
            64 -> NumericKind.U64
            16 -> NumericKind.U16
            else -> NumericKind.U32
        }
    val cLongSigned = if (ptrWidth == 64 && !isWindows) NumericKind.I64 else NumericKind.I32
    val cLongUnsigned = if (ptrWidth == 64 && !isWindows) NumericKind.U64 else NumericKind.U32

    if (depth < 8) {
        val aliasTarget =
            inspector.typeAliasTargets[base]
                ?: inspector.typeAliasTargets[simpleBase]
                ?: inspector.typeAliasTargets[rustTypeIdentToKotlin(simpleBase)]
        if (aliasTarget != null) {
            if (!headerContext.rawAbiMode && unwrapSynType(aliasTarget) is SynType.Ptr) {
                return null
            }
            val primTarget = extractSinglePrimitiveTargetIdent(aliasTarget)
            val isFfiAlias =
                headerContext.rawAbiMode ||
                    inExternAbiSignature ||
                    base in inspector.ffiReferencedTypeNames ||
                    simpleBase in inspector.ffiReferencedTypeNames
            val keyVariants = distinctBaseAndSimpleKeys(base, simpleBase)
            val isScannerPointerWidthUnsigned =
                keyVariants.any { candidate ->
                    scopedAbiKeysForRenderedType(candidate).any {
                        headerContext.crateNumericTypeKinds[it] in setOf("U64", "U32", "U16")
                    }
                }
            val isScannerPointerWidthSigned =
                keyVariants.any { candidate ->
                    scopedAbiKeysForRenderedType(candidate).any {
                        headerContext.crateNumericTypeKinds[it] in setOf("I64", "I32", "I16")
                    }
                }
            if (primTarget == "usize" && (isFfiAlias || isCPointerWidthUnsignedTypeName(simpleBase) || isScannerPointerWidthUnsigned)) {
                return ptrUnsigned
            }
            if (primTarget == "isize" && (isFfiAlias || isCPointerWidthSignedTypeName(simpleBase) || isScannerPointerWidthSigned)) {
                return ptrSigned
            }
            val prevParam = inExternParamType
            inExternParamType = false
            val renderedAlias =
                try {
                    renderType(aliasTarget, selfTypeName = null)
                } finally {
                    inExternParamType = prevParam
                }
            if (renderedAlias != base) {
                resolveNumericKind(renderedAlias, depth + 1)?.let { return it }
            }
        }
    }
    if (simpleBase in inspector.declaredTypeNames &&
        simpleBase !in inspector.typeAliasTargets &&
        simpleBase !in setOf("U128", "I128", "F16", "F128")
    ) {
        return null
    }
    if (simpleBase == "CLong" || simpleBase == "c_long") {
        val curSub = currentSubpackageString()
        if (curSub.isNotEmpty()) {
            numericKindFromScannerCode(headerContext.crateNumericTypeKinds["$curSub.$simpleBase"])?.let { return it }
        }
        return cLongSigned
    }
    if (simpleBase == "CUlong" || simpleBase == "c_ulong") {
        val curSub = currentSubpackageString()
        if (curSub.isNotEmpty()) {
            numericKindFromScannerCode(headerContext.crateNumericTypeKinds["$curSub.$simpleBase"])?.let { return it }
        }
        return cLongUnsigned
    }
    for (variant in distinctBaseAndSimpleKeys(base, simpleBase)) {
        for (key in scopedAbiKeysForRenderedType(variant)) {
            if (isNonNumericScopedAbiKey(key)) return null
            numericKindFromScannerCode(headerContext.crateNumericTypeKinds[key])?.let { return it }
        }
    }
    if (isCPointerWidthSignedTypeName(simpleBase)) return ptrSigned
    if (isCPointerWidthUnsignedTypeName(simpleBase)) return ptrUnsigned
    return when (simpleBase) {
        "CSchar", "c_schar", "Int8T", "int8_t" -> NumericKind.I8
        "CChar", "c_char" -> if (headerContext.rawAbiMode) NumericKind.I8 else NumericKind.U8
        "CShort", "c_short", "Int16T", "int16_t" -> NumericKind.I16
        "CInt", "c_int", "Int32T", "int32_t", "PidT", "pid_t", "ClockidT", "clockid_t", "WcharT", "wchar_t",
        "KeyT", "key_t", "IdT", "id_t", "SusecondsT", "suseconds_t",
        -> NumericKind.I32
        "CLong", "c_long" -> cLongSigned
        "CLonglong", "c_longlong", "Int64T", "int64_t", "OffT", "off_t", "Off64T", "off64_t",
        "TimeT", "time_t", "BlkcntT", "blkcnt_t", "BlksizeT", "blksize_t",
        -> NumericKind.I64
        "CUchar", "c_uchar", "Uint8T", "uint8_t" -> NumericKind.U8
        "CUshort", "c_ushort", "Uint16T", "uint16_t", "SaFamilyT", "sa_family_t", "InPortT", "in_port_t" ->
            NumericKind.U16
        "CUint", "c_uint", "Uint32T", "uint32_t", "CanidT", "canid_t", "UidT", "uid_t", "GidT", "gid_t",
        "ModeT", "mode_t", "SocklenT", "socklen_t", "InAddrT", "in_addr_t",
        -> NumericKind.U32
        "CUlong", "c_ulong" -> cLongUnsigned
        "CUlonglong", "c_ulonglong", "Uint64T", "uint64_t" -> NumericKind.U64
        "CFloat", "c_float" -> NumericKind.F32
        "CDouble", "c_double" -> NumericKind.F64
        else -> null
    }
}

internal fun KotlinJ2KEmitter.renderTypedNumericInitializer(
    expr: Expr,
    kind: NumericKind?,
    indent: String,
    selfTypeName: String?,
): String {
    val prev = currentExpectedNumericKind
    currentExpectedNumericKind = kind
    val rendered =
        try {
            renderExpr(expr, indent, selfTypeName)
        } finally {
            currentExpectedNumericKind = prev
        }
    if (kind == null) return rendered
    if (isAlreadyRenderedAsNumericKind(rendered, kind)) return rendered
    val core = unwrapTransparentCastExpr(expr)
    val isCompound =
        core is Expr.Binary ||
            (core is Expr.Unary && (core.op is UnOp.NotOp || core.op is UnOp.Neg))
    if (!isCompound) {
        if (core is Expr.Lit) return rendered
        val inferred = inferExprNumericKind(expr, selfTypeName)
        if (inferred == null || inferred == kind) return rendered
        val recv = if (isAtomicPostfixReceiver(expr, rendered)) rendered else "($rendered)"
        return when (kind) {
            NumericKind.U8 -> if (rendered.endsWith(".toUByte()")) rendered else "$recv.toUByte()"
            NumericKind.U16 -> if (rendered.endsWith(".toUShort()")) rendered else "$recv.toUShort()"
            NumericKind.U32 -> if (rendered.endsWith(".toUInt()")) rendered else "$recv.toUInt()"
            NumericKind.U64 -> if (rendered.endsWith(".toULong()")) rendered else "$recv.toULong()"
            NumericKind.I8 -> if (rendered.endsWith(".toByte()")) rendered else "$recv.toByte()"
            NumericKind.I16 -> if (rendered.endsWith(".toShort()")) rendered else "$recv.toShort()"
            NumericKind.I32 -> if (rendered.endsWith(".toInt()")) rendered else "$recv.toInt()"
            NumericKind.I64 -> if (rendered.endsWith(".toLong()")) rendered else "$recv.toLong()"
            NumericKind.F16 -> convertNumericReceiver(recv, "F16", inferred)
            NumericKind.F32 -> if (rendered.endsWith(".toFloat()")) rendered else "$recv.toFloat()"
            NumericKind.F64 -> if (rendered.endsWith(".toDouble()")) rendered else "$recv.toDouble()"
            NumericKind.F128 -> convertNumericReceiver(recv, "F128", inferred)
            else -> rendered
        }
    }
    if ((kind == NumericKind.U8 || kind == NumericKind.U16) &&
        (
            (
                core is Expr.Binary &&
                    (
                        core.op is BinOp.BitAnd ||
                            core.op is BinOp.BitOr ||
                            core.op is BinOp.BitXor ||
                            core.op is BinOp.Shl ||
                            core.op is BinOp.Shr
                    )
            ) ||
                (core is Expr.Unary && core.op is UnOp.NotOp)
        ) &&
        inferExprNumericKind(core, selfTypeName) == kind
    ) {
        return rendered
    }
    return when (kind) {
        NumericKind.U8 ->
            if (rendered.endsWith(".toUByte()")) {
                rendered
            } else if (isAtomicPostfixReceiver(expr, rendered)) {
                "$rendered.toUByte()"
            } else {
                "($rendered).toUByte()"
            }
        NumericKind.U16 ->
            if (rendered.endsWith(".toUShort()")) {
                rendered
            } else if (isAtomicPostfixReceiver(expr, rendered)) {
                "$rendered.toUShort()"
            } else {
                "($rendered).toUShort()"
            }
        NumericKind.I8 ->
            if (rendered.endsWith(".toByte()")) {
                rendered
            } else if (isAtomicPostfixReceiver(expr, rendered)) {
                "$rendered.toByte()"
            } else {
                "($rendered).toByte()"
            }
        NumericKind.I16 ->
            if (rendered.endsWith(".toShort()")) {
                rendered
            } else if (isAtomicPostfixReceiver(expr, rendered)) {
                "$rendered.toShort()"
            } else {
                "($rendered).toShort()"
            }
        else -> rendered
    }
}

internal fun KotlinJ2KEmitter.renderPointerType(ptr: SynType.Ptr, selfTypeName: String?): String {
    if (headerContext.rawAbiMode) {
        return "Long"
    }
    val prevParam = inExternParamType
    inExternParamType = false
    return try {
        val pointee = renderType(ptr.elem, selfTypeName)
        val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
        val ptrClass = if (ptr.mutability is PointerMutability.Mut) "MutPtr" else "ConstPtr"
        CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.ptr.$ptrClass")
        "$ptrClass<$pointee>"
    } finally {
        inExternParamType = prevParam
    }
}

internal fun KotlinJ2KEmitter.isAlreadyNullableRenderedType(rendered: String): Boolean {
    val trimmed = rendered.trim()
    if (trimmed.endsWith("?")) return true
    val simple = trimmed.substringAfterLast('.')
    if (inspector.typeAliasTargets[simple]?.let(::unwrapSynType)?.let(::extractOptionBareFn) != null) {
        return true
    }
    val keys = scopedAbiKeysForRenderedType(trimmed)
    if (keys.any { it in headerContext.crateOptionFnTypes }) {
        return true
    }
    return false
}

internal fun KotlinJ2KEmitter.isOptionFnTypeAliasRenderedType(rendered: String): Boolean {
    val trimmed = rendered.trim().removeSuffix("?")
    val simple = trimmed.substringAfterLast('.')
    if (inspector.typeAliasTargets[simple]?.let(::unwrapSynType)?.let(::extractOptionBareFn) != null) {
        return true
    }
    return scopedAbiKeysForRenderedType(trimmed).any { it in headerContext.crateOptionFnTypes }
}

internal fun KotlinJ2KEmitter.extractOptionBareFn(ty: SynType): SynType.FnPtr? {
    val pathTy = unwrapSynType(ty) as? SynType.Path ?: return null
    if (pathTy.qself != null) return null
    val segs = pathTy.path.segments.toList()
    if (segs.isEmpty()) return null
    val last = segs.last()
    if (last.ident.toString() != "Option") return null
    if (segs.dropLast(1).any { it.ident.toString() !in STDLIB_TYPE_PATH_PREFIXES }) return null
    val angle = last.arguments as? PathArguments.AngleBracketed ?: return null
    val args = angle.args.toList()
    if (args.size != 1) return null
    val inner = (args[0] as? GenericArgument.TypeArg)?.type?.let(::unwrapSynType) ?: return null
    return inner as? SynType.FnPtr
}

internal fun KotlinJ2KEmitter.synthesizeOrReuseBareFnSam(ty: SynType.FnPtr, selfTypeName: String?): String {
    val prevHint = currentSamNameHint
    val prevExternAbi = inExternAbiSignature
    currentSamNameHint = null
    inExternAbiSignature = prevExternAbi || (ty.abi != null)
    val (inputTypes, outType) =
        try {
            val ins = ty.inputs.toList().map { renderType(it.ty, selfTypeName) }
            val out = extractReturnSynType(ty.output)?.let { renderType(it, selfTypeName) } ?: "Unit"
            ins to out
        } finally {
            currentSamNameHint = prevHint
            inExternAbiSignature = prevExternAbi
        }
    val referencesGeneric =
        activeTypeParamNames.any { tp ->
            inputTypes.any { containsWholeIdentifier(it, tp) } || containsWholeIdentifier(outType, tp)
        }
    if (referencesGeneric) {
        return "(${inputTypes.joinToString(", ")}) -> $outType"
    }
    val shapeKey = "(${inputTypes.joinToString(", ")})->$outType"
    synthesizedSamNamesByShape[shapeKey]?.let { return it }

    val rawCandidate = rustTypeIdentToKotlin(currentSamNameHint ?: "FnCallback")
    var candidate = rawCandidate
    var suffixIdx = 1
    while (candidate in inspector.declaredTypeNames || candidate in synthesizedSamDeclarations) {
        candidate = "$rawCandidate$suffixIdx"
        suffixIdx++
    }
    inspector.declaredTypeNames += candidate
    synthesizedSamNamesByShape[shapeKey] = candidate
    synthesizedSamDeclarations[candidate] = renderBareFnSamInterface("public ", candidate, ty, "")
    return candidate
}

internal fun KotlinJ2KEmitter.renderBareFnSamInterface(
    vis: String,
    samName: String,
    bareFn: SynType.FnPtr,
    indent: String,
): String {
    val prevHint = currentSamNameHint
    val prevExternAbi = inExternAbiSignature
    currentSamNameHint = null
    inExternAbiSignature = prevExternAbi || (bareFn.abi != null)
    return try {
        val usedNames = hashSetOf<String>()
        val paramTypes = mutableListOf<String>()
        val params =
            bareFn.inputs.toList().mapIndexed { idx, arg ->
                val rawName =
                    arg.name
                        ?.ident
                        ?.toString()
                        ?.removePrefix("r#")
                        ?.takeIf { it.isNotEmpty() && it != "_" }
                val rawBase = rawName?.let(::rustValueIdentToKotlin) ?: "arg$idx"
                val baseName = if (rawBase == "errno") "errnum" else rawBase
                val pName = if (usedNames.add(baseName)) baseName else "${baseName}$idx"
                usedNames.add(pName)
                val pType = renderType(arg.ty, selfTypeName = null)
                paramTypes += pType
                "$pName: $pType"
            }
        val retType = extractReturnSynType(bareFn.output)?.let { renderType(it, selfTypeName = null) } ?: "Unit"
        val shapeKey = "(${paramTypes.joinToString(", ")})->$retType"
        if (shapeKey !in synthesizedSamNamesByShape) {
            synthesizedSamNamesByShape[shapeKey] = samName
        }
        if (samName !in synthesizedSamDefaultsByName) {
            val retDefault = if (retType == "Unit") "" else renderFallbackDefaultForRenderedType(retType)
            val lambdaParams =
                if (paramTypes.isEmpty()) {
                    ""
                } else {
                    paramTypes.indices.joinToString(", ") { "_" } + " -> "
                }
            synthesizedSamDefaultsByName[samName] =
                if (lambdaParams.isEmpty() && retDefault.isEmpty()) {
                    "$samName {}"
                } else {
                    "$samName { ${(lambdaParams + retDefault).trim()} }"
                }
        }
        val retSuffix = if (retType == "Unit") "" else ": $retType"
        buildString {
            append("$indent${vis}fun interface $samName {\n")
            append(indent)
                .append("    public operator fun invoke(")
                .append(params.joinToString(", "))
                .append(')')
                .append(retSuffix)
                .append('\n')
            append(indent).append('}')
        }
    } finally {
        currentSamNameHint = prevHint
        inExternAbiSignature = prevExternAbi
    }
}

internal fun KotlinJ2KEmitter.extractEnumReprKotlinType(attrs: List<Attribute>): String? {
    for (attr in attrs) {
        val meta = attr.meta
        if (meta is Meta.List && meta.path.isIdent("repr")) {
            for (tt in meta.tokens.clone().toList()) {
                if (tt is TokenTree.Ident) {
                    val ident = tt.value.toString()
                    if (ident != "C" && ident != "transparent" && ident != "packed" && ident != "align") {
                        return rustTypeIdentToKotlin(ident)
                    }
                }
            }
        }
    }
    return null
}

internal fun KotlinJ2KEmitter.hasReprSimdAttr(attrs: List<Attribute>): Boolean =
    attrs.any { attr ->
        val meta = attr.meta
        meta is Meta.List &&
            meta.path.isIdent("repr") &&
            meta.tokens.clone().toList().any { tt ->
                (tt as? TokenTree.Ident)?.value?.toString() == "simd"
            }
    }

internal fun KotlinJ2KEmitter.isStdlibAnyPathSegments(segNames: List<String>): Boolean {
    if (segNames.isEmpty()) return false
    val last = segNames.last()
    if (last != "Any" && last != "TypeId") return false
    if ("Any" in inspector.declaredTraitsByName || "TypeId" in inspector.declaredTypeNames) return false
    if (segNames.size == 1) return true
    val prefix = segNames.dropLast(1)
    return prefix == listOf("any") ||
        prefix == listOf("std", "any") ||
        prefix == listOf("core", "any")
}

internal fun KotlinJ2KEmitter.isStdlibDynAnyTraitObject(ty: SynType.TraitObject): Boolean {
    if ("Any" in inspector.declaredTraitsByName || "TypeId" in inspector.declaredTypeNames) return false
    var sawStdlibAny = false
    for (bound in ty.bounds.toList()) {
        when (bound) {
            is TypeParamBound.LifetimeBound -> continue
            is TypeParamBound.Trait -> {
                if (bound.maybe != null) continue
                val segNames =
                    bound.path.segments
                        .toList()
                        .map { it.ident.toString() }
                val last = segNames.lastOrNull() ?: return false
                if (last in setOf("Send", "Sync", "Unpin")) continue
                if (last == "Any" && isStdlibAnyPathSegments(segNames)) {
                    sawStdlibAny = true
                } else {
                    return false
                }
            }
            else -> return false
        }
    }
    return sawStdlibAny
}

internal fun KotlinJ2KEmitter.isSuppressedStdlibModuleAlias(id: String): Boolean {
    val imp = scopeBuilder.resolveModuleBinding(id)?.importedPath ?: return false
    val segs = imp.split('.')
    if (segs.size == 2) {
        val isMappedOrSelfStdCrate =
            (
                segs[0] in STDLIB_CRATE_ROOTS &&
                    (
                        segs[0] in headerContext.crateNamespaces ||
                            headerContext.packageName?.startsWith("io.github.kotlinmania.rustlang.") == true
                    )
            ) ||
                (segs[0] == "crate" && headerContext.packageName?.startsWith("io.github.kotlinmania.rustlang.") == true)
        if (isMappedOrSelfStdCrate && segs[1].firstOrNull()?.isLowerCase() == true && !isKnownFunctionLeafIdent(segs[1])) {
            return true
        }
    }
    if (segs.size == 6 &&
        segs[0] == "io" &&
        segs[1] == "github" &&
        segs[2] == "kotlinmania" &&
        segs[3] == "rustlang" &&
        segs[5].firstOrNull()?.isLowerCase() == true &&
        !isKnownFunctionLeafIdent(segs[5])
    ) {
        return true
    }
    return false
}

private fun distinctBaseAndSimpleKeys(base: String, simpleBase: String): List<String> =
    if (simpleBase != base) listOf(base, simpleBase) else listOf(base)

private fun KotlinJ2KEmitter.isNonNumericScopedAbiKey(key: String): Boolean {
    val scopedDef = headerContext.crateTypeAliasDefaults[key]
    return key in headerContext.cratePointerTypeAliases ||
        key in headerContext.crateOptionFnTypes ||
        (
            scopedDef != null &&
                scopedDef !in setOf("I128()", "U128()", "F16()", "F128()") &&
                (scopedDef.endsWith("()") || scopedDef.endsWith("(0)") || scopedDef == "emptyList()" || (!headerContext.rawAbiMode && scopedDef == "null"))
        )
}
