package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.procmacro2.TokenStream
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
import io.github.kotlinmania.syn.PathSegment
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

private val ZERO_OR_NULL_POINTER_LITERALS = setOf("0", "0L", "0u", "0uL", "null")

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

private val C_POINTER_WIDTH_UNSIGNED_TYPE_NAMES =
    setOf("size_t", "SizeT", "uintptr_t", "UintptrT", "rsize_t", "RsizeT", "__size_t")

private val C_POINTER_WIDTH_SIGNED_TYPE_NAMES =
    setOf("ssize_t", "SsizeT", "ptrdiff_t", "PtrdiffT", "intptr_t", "IntptrT", "__ssize_t")

internal fun isDecimalOrHexLiteralDigits(text: String): Boolean {
    if (text.isEmpty()) return false
    if (text.all { it.isDigit() || it == '_' }) return true
    if (text.startsWith("0x") || text.startsWith("0X")) {
        val hex = text.drop(2)
        return hex.isNotEmpty() && hex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == '_' }
    }
    return false
}

/**
 * Lowers Rust `as` cast expressions across `RustAny`, `ConstPtr`/`MutPtr` raw pointers,
 * integer/float/128-bit/char scalar conversions, C-ABI callback pointers, and nullable targets.
 */
internal class CastExpressionLowering(
    private val emitter: KotlinJ2KEmitter,
    private val inner: String,
    private val targetType: String,
    private val sourceExpr: Expr,
    private val selfTypeName: String?,
) {
    private val atomicRecv: String by lazy {
        if (emitter.isAtomicPostfixReceiver(sourceExpr, inner)) inner else "($inner)"
    }
    private val localTy: String? = emitter.currentTypedLocals[inner]
    private val isZeroOrNullLit: Boolean = inner in ZERO_OR_NULL_POINTER_LITERALS

    private fun isIntegerOrNumericSource(includeBitwiseNot: Boolean): Boolean =
        emitter.isKnownIntegerBitmaskExpr(sourceExpr) ||
            (sourceExpr is Expr.Unary && (sourceExpr.op is UnOp.Neg || (includeBitwiseNot && sourceExpr.op is UnOp.NotOp))) ||
            (localTy != null && emitter.resolveNumericKind(localTy) != null) ||
            emitter.inferExprNumericKind(sourceExpr, selfTypeName) != null

    fun render(): String {
        if (targetType == "RustAny") {
            return if (emitter.inferExprRenderedType(sourceExpr, selfTypeName) == "RustAny") inner else "RustAny.from($inner)"
        }
        val targetBase = targetType.removeSuffix("?").trim()
        val aliasPtrTarget = (emitter.inspector.typeAliasTargets[targetBase] as? SynType.Ptr)?.let { emitter.renderType(it, selfTypeName) }
        val resolvedPtrTarget = if (rawPointerPointeeType(targetType) != null) targetType else aliasPtrTarget
        val pointerPointee = rawPointerPointeeType(resolvedPtrTarget)
        if (pointerPointee != null) {
            return renderRawPointerTargetCast(resolvedPtrTarget, pointerPointee)
        }

        val targetNumericKind = emitter.resolveNumericKind(targetType)
        val canonicalNumericTarget =
            when (targetNumericKind) {
                NumericKind.F32 -> emitter.kotlinF32TypeName()
                null -> if (targetType == "Char" || targetType == "RustChar") targetType else null
                else -> emitter.numericKindToPureTypeName(targetNumericKind)
            }
        val sourceInferredForNum = emitter.inferExprRenderedType(sourceExpr, selfTypeName)
        val isSourceRawPtrOrAlias =
            rawPointerPointeeType(sourceInferredForNum) != null ||
                (
                    sourceInferredForNum != null &&
                        (
                            emitter.inspector.typeAliasTargets[sourceInferredForNum.removeSuffix("?")] is SynType.Ptr ||
                                emitter.scopedAbiKeysForRenderedType(sourceInferredForNum.removeSuffix("?")).any { it in emitter.headerContext.cratePointerTypeAliases }
                        )
                )
        if (canonicalNumericTarget != null) {
            return renderNumericTargetCast(canonicalNumericTarget, sourceInferredForNum, isSourceRawPtrOrAlias)
        }
        tryRenderCratePointerAliasCast(targetBase, isSourceRawPtrOrAlias)?.let { return it }
        tryRenderNullableTargetCast()?.let { return it }
        if (targetType == inner) return inner
        return "(($inner as Any?) as $targetType)"
    }

    private fun renderRawPointerTargetCast(resolvedPtrTarget: String?, pointerPointee: String): String {
        val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(emitter.headerContext)
        val ptrCtor = if (isMutRawPointerRenderedType(resolvedPtrTarget)) "MutPtr" else "ConstPtr"
        CoreIntrinsicLowering.registerCoreImport(emitter, "$coreNs.ptr.$ptrCtor")
        if (isZeroOrNullLit) return "$ptrCtor<$pointerPointee>(0L)"

        val reference = emitter.unwrapParenOrGroupExpr(sourceExpr) as? Expr.Reference
        val referencePlace = reference?.expr?.let(emitter::unwrapParenOrGroupExpr) as? Expr.Path
        val singleRefSeg = referencePlace?.takeIf { it.qself == null && it.path.segments.size == 1 }?.path
        val firstRefIdent = singleRefSeg?.segments?.first()?.ident
        val rawRefIdent = firstRefIdent?.toString()?.removePrefix("r#")
        val referenceLocal = rawRefIdent?.let { emitter.scopeBuilder.resolveLocalKotlinName(it) ?: rustValueIdentToKotlin(it) }
        val referenceStorage = referenceLocal?.let { emitter.currentAddressableLocals[it] }
        if (referenceStorage != null) {
            return if (referenceLocal in emitter.currentAddressableArrayElementTypes) {
                "$ptrCtor<$pointerPointee>($referenceStorage.elementMutPtr(0).rawAddress)"
            } else {
                "$ptrCtor<$pointerPointee>($referenceStorage.asMutPtr().rawAddress)"
            }
        }

        val sourceType = emitter.inferExprRenderedType(sourceExpr, selfTypeName)
        val sourceReceiver = emitter.unwrapParenOrGroupExpr(sourceExpr) as? Expr.Path
        val unwrappedRefReceiver = referencePlace ?: sourceReceiver
        if (unwrappedRefReceiver?.qself == null && unwrappedRefReceiver?.path?.isIdent("self") == true &&
            selfTypeName != null && emitter.currentTypedLocals["this"] == selfTypeName
        ) {
            val declaration = emitter.transparentDeclaration(selfTypeName)
            if (declaration != null && emitter.sourceTransparentField(declaration, selfTypeName) != null) {
                return "$ptrCtor<$pointerPointee>(this.fieldStorage.rawAddress)"
            }
        }
        if (mutableReferencePointeeType(sourceType) != null) {
            return "$ptrCtor<$pointerPointee>($atomicRecv.asMutPtr().rawAddress)"
        }
        val sourceAliasPtr =
            sourceType?.removeSuffix("?")?.trim()?.let { st ->
                (emitter.inspector.typeAliasTargets[st] as? SynType.Ptr)?.let { emitter.renderType(it, selfTypeName) }
            }
        val effectiveSourcePtrType = if (rawPointerPointeeType(sourceType) != null) sourceType else sourceAliasPtr
        if (rawPointerPointeeType(effectiveSourcePtrType) != null ||
            (sourceType != null && emitter.scopedAbiKeysForRenderedType(sourceType.removeSuffix("?")).any { it in emitter.headerContext.cratePointerTypeAliases })
        ) {
            if (effectiveSourcePtrType == resolvedPtrTarget && sourceType == targetType) return inner
            if (emitter.unwrapParenOrGroupExpr(sourceExpr) is Expr.Path && emitter.isAtomicPostfixReceiver(sourceExpr, inner)) {
                return "$ptrCtor<$pointerPointee>($inner.rawAddress, $inner.rawMeta)"
            }
            return "($inner).let { ptrSrc -> $ptrCtor<$pointerPointee>(ptrSrc.rawAddress, ptrSrc.rawMeta) }"
        }
        if (isIntegerOrNumericSource(includeBitwiseNot = true)) {
            val longArg = emitter.convertNumericReceiver(atomicRecv, "Long", emitter.inferExprNumericKind(sourceExpr, selfTypeName))
            return "$ptrCtor<$pointerPointee>($longArg)"
        }
        val castHelper = if (ptrCtor == "MutPtr") "mutPtrFromRustCast" else "constPtrFromRustCast"
        CoreIntrinsicLowering.registerCoreImport(emitter, "$coreNs.mem.$castHelper")
        return "$castHelper<$pointerPointee>($inner)"
    }

    private fun renderNumericTargetCast(
        canonicalNumericTarget: String,
        sourceInferredForNum: String?,
        isSourceRawPtrOrAlias: Boolean,
    ): String {
        if (isSourceRawPtrOrAlias) {
            return emitter.convertNumericReceiver("$atomicRecv.rawAddress", canonicalNumericTarget, NumericKind.I64)
        }
        if (mutableReferencePointeeType(sourceInferredForNum) != null) {
            return emitter.convertNumericReceiver("$atomicRecv.pointer.rawAddress", canonicalNumericTarget, NumericKind.I64)
        }
        if (emitter.isKnownBooleanCastSource(sourceExpr) || sourceInferredForNum?.removeSuffix("?")?.trim() == "Boolean") {
            return renderBooleanToNumericCast(canonicalNumericTarget)
        }
        if (!emitter.headerContext.rawAbiMode) {
            val cleanLocalBase = localTy?.removeSuffix("?")
            if (inner in emitter.currentNullableLocals || localTy?.endsWith("?") == true) {
                error("Cannot expose an address for $inner ($localTy): the Rust pointer requires address-bearing storage")
            }
            if (cleanLocalBase != null &&
                (cleanLocalBase.endsWith("Callback") || cleanLocalBase.endsWith("Fn") || cleanLocalBase in emitter.synthesizedSamDeclarations)
            ) {
                val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(emitter.headerContext)
                CoreIntrinsicLowering.registerCoreImport(emitter, "$coreNs.ffi.ExternFnPtr")
                return emitter.convertNumericReceiver(
                    "(($atomicRecv as? ExternFnPtr)?.rawAddress ?: error(\"Kotlin closure callback has no native C function address\"))",
                    canonicalNumericTarget,
                    NumericKind.I64,
                )
            }
            val fieldTy = emitter.resolveFieldRenderedType(sourceExpr, selfTypeName)
            if (fieldTy != null && rawPointerPointeeType(fieldTy) != null) {
                return emitter.convertNumericReceiver("$atomicRecv.rawAddress", canonicalNumericTarget, NumericKind.I64)
            }
            if (fieldTy != null && fieldTy.endsWith("?")) {
                error("Cannot expose an address for $inner ($fieldTy): the Rust field requires address-bearing storage")
            }
        }
        val unwrappedSource = emitter.unwrapParenOrGroupExpr(emitter.unwrapTransparentCastExpr(sourceExpr))
        val cleanSourceCharTy = sourceInferredForNum?.removeSuffix("?")?.trim()
        val inferredRustCharSource = emitter.headerContext.scalarChar && cleanSourceCharTy == "RustChar"
        val inferredKtCharSource = !emitter.headerContext.scalarChar && cleanSourceCharTy == "Char"
        if ((unwrappedSource as? Expr.Lit)?.lit is Lit.Char || inferredRustCharSource || inferredKtCharSource) {
            if ((inferredRustCharSource && canonicalNumericTarget == "RustChar") || (inferredKtCharSource && canonicalNumericTarget == "Char")) {
                return inner
            }
            val charCodeKind = if (emitter.headerContext.scalarChar && !inferredKtCharSource) NumericKind.U32 else NumericKind.I32
            return emitter.convertNumericReceiver("$atomicRecv.code", canonicalNumericTarget, charCodeKind)
        }
        val sourceKind = emitter.inferExprNumericKind(sourceExpr, selfTypeName)
        if (sourceKind == null) {
            tryRenderEnumDiscriminantCast(unwrappedSource, canonicalNumericTarget)?.let { return it }
        }
        if ((sourceKind != null && emitter.numericKindToPureTypeName(sourceKind) == canonicalNumericTarget) ||
            emitter.isAlreadyRenderedAsNumericKind(inner, emitter.resolveNumericKind(canonicalNumericTarget))
        ) {
            return inner
        }
        return emitter.convertNumericReceiver(atomicRecv, canonicalNumericTarget, sourceKind)
    }

    private fun renderBooleanToNumericCast(canonicalNumericTarget: String): String {
        val cleanCond = if (emitter.hasOuterBalancedParens(inner)) inner.substring(1, inner.length - 1) else inner
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
                emitter.usesRustChar = true
                "(if ($cleanCond) RustChar(1u) else RustChar(0u))"
            }
            else -> "($inner as $targetType)"
        }
    }

    private fun tryRenderEnumDiscriminantCast(unwrappedSource: Expr, canonicalNumericTarget: String): String? {
        if (unwrappedSource is Expr.Path && unwrappedSource.qself == null) {
            val segs = unwrappedSource.path.segments.toList()
            if (segs.size >= 2) {
                val prevRaw = segs[segs.size - 2].ident.toString().removePrefix("r#")
                val prevKtType = rustTypeIdentToKotlin(prevRaw)
                val lastIdent = segs.last().ident.toString()
                val lastRaw = lastIdent.removePrefix("r#")
                val isPrevKnownType =
                    (
                        prevRaw.first().isUpperCase() ||
                            prevKtType in emitter.inspector.declaredTypeNames ||
                            emitter.scopedAbiKeysForRenderedType(prevKtType).any { it in emitter.headerContext.crateSymbolSubpackages }
                    ) &&
                        emitter.resolveNumericKind(prevKtType) == null
                if (isPrevKnownType &&
                    lastRaw !in setOf("MAX", "MIN", "BITS", "ZERO") &&
                    lastRaw.first().isUpperCase() &&
                    (!emitter.isScreamingSnakeIdent(lastRaw) || !prevRaw.first().isUpperCase())
                ) {
                    val enumKind =
                        emitter.scopedAbiKeysForRenderedType(prevKtType).firstNotNullOfOrNull {
                            emitter.numericKindFromScannerCode(emitter.headerContext.crateNumericTypeKinds[it])
                        } ?: NumericKind.U32
                    return emitter.convertNumericReceiver("$atomicRecv.value", canonicalNumericTarget, enumKind)
                }
            }
        }
        val inferredSourceType = emitter.inferExprRenderedType(sourceExpr, selfTypeName)?.removeSuffix("?")?.trim() ?: return null
        if (emitter.resolveNumericKind(inferredSourceType) != null) return null
        val inferredEnumKind =
            emitter.scopedAbiKeysForRenderedType(inferredSourceType).firstNotNullOfOrNull {
                emitter.numericKindFromScannerCode(emitter.headerContext.crateNumericTypeKinds[it])
            } ?: NumericKind.I8.takeIf {
                (inferredSourceType == "Ordering" || inferredSourceType.endsWith(".cmp.Ordering")) &&
                    emitter.headerContext.packageName?.contains(".sync.atomic") != true
            } ?: return null
        return emitter.convertNumericReceiver("$atomicRecv.value", canonicalNumericTarget, inferredEnumKind)
    }

    private fun tryRenderCratePointerAliasCast(targetBase: String, isSourceRawPtrOrAlias: Boolean): String? {
        val targetPtrAliasKind =
            emitter
                .scopedAbiKeysForRenderedType(targetBase)
                .firstNotNullOfOrNull { emitter.headerContext.cratePointerTypeAliases[it] } ?: return null
        val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(emitter.headerContext)
        val ptrCtor = if (targetPtrAliasKind.startsWith("const:")) "ConstPtr" else "MutPtr"
        CoreIntrinsicLowering.registerCoreImport(emitter, "$coreNs.ptr.$ptrCtor")
        if (isZeroOrNullLit) return "$ptrCtor(0L)"
        if (isSourceRawPtrOrAlias) return "$ptrCtor($atomicRecv.rawAddress)"
        if (inner in emitter.currentNullableLocals) {
            error("Cannot cast $inner ($localTy) to $targetType: the Rust pointer requires address-bearing storage")
        }
        if (isIntegerOrNumericSource(includeBitwiseNot = true)) {
            val longArg = emitter.convertNumericReceiver(atomicRecv, "Long", emitter.inferExprNumericKind(sourceExpr, selfTypeName))
            return "$ptrCtor($longArg)"
        }
        return null
    }

    private fun tryRenderNullableTargetCast(): String? {
        if (!targetType.endsWith("?")) return null
        if (isZeroOrNullLit) return "null"
        val nonNullTarget = targetType.removeSuffix("?")
        val isNumericSource = emitter.isKnownBooleanCastSource(sourceExpr) || isIntegerOrNumericSource(includeBitwiseNot = false)
        if (!isNumericSource) return null
        if (emitter.resolveNumericKind(nonNullTarget) != null) {
            return emitter.renderCastExpression(inner, nonNullTarget, sourceExpr, selfTypeName)
        }
        return if (nonNullTarget == "String") "\"\\uFFFF\"" else "null"
    }
}

internal fun KotlinJ2KEmitter.renderCastExpression(
    inner: String,
    targetType: String,
    sourceExpr: Expr,
    selfTypeName: String? = null,
): String =
    CastExpressionLowering(this, inner, targetType, sourceExpr, selfTypeName).render()

private fun hasTopLevelOperatorOutsidePostfix(s: String): Boolean =
    !KotlinTokenWalker.scanTopLevelTokens(s, trackAllBrackets = true) { tok, depth ->
        if (depth != 0) {
            true
        } else {
            when (tok) {
                is KotlinLexToken.Whitespace, is KotlinLexToken.Newline, is KotlinLexToken.Arrow -> false
                is KotlinLexToken.Punct -> tok.start == 0 || tok.ch !in "+-*/%&|^=<>"
                else -> true
            }
        }
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
    if (kind == NumericKind.I32 && (s.endsWith(".code") || s.endsWith(".size") || s.endsWith(".hashCode()"))) return true
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
    BOXED_INT_FROM_SUFFIX_BY_KIND[sourceKind]?.let { suffix -> return "$targetBoxType.$suffix($recv)" }
    return when {
        sourceKind != null && PrimitiveNumericOpCatalog.isFloatKind(sourceKind) ->
            "$targetBoxType.$floatFallbackMethod($recv.$floatFallbackCast())"
        sourceKind == null ->
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
    BOXED_INT_FROM_SUFFIX_BY_KIND[sourceKind]?.let { suffix -> return "$targetBoxType.$suffix($recv)" }
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
        "Byte", "Short", "Int", "Long", "UByte", "UShort", "UInt", "ULong", "Double" ->
            "$recv${NUMERIC_SUFFIX_BY_KIND.getValue(requireNotNull(targetKind))}"
        "Float", "kotlin.Float" -> "$recv.toFloat()"
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
        is SynType.Reference -> renderReferenceType(ty, selfTypeName)
        is SynType.Ptr -> renderPointerType(ty, selfTypeName)
        is SynType.Slice -> "List<${renderType(ty.elem, selfTypeName)}>"
        is SynType.Array -> renderArrayType(ty, selfTypeName)
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
                val pathSegs = ty.path.segments
                val lastSeg = pathSegs.last()?.ident?.toString()
                if (lastSeg != null && lastSeg != "Self") rustTypeIdentToKotlin(lastSeg) else renderType(qself.ty, selfTypeName)
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
        is SynType.ImplTrait -> ty.bounds.toList().firstNotNullOfOrNull { renderTypeParamBound(it, selfTypeName) } ?: "Any"
        is SynType.TraitObject ->
            if (isStdlibDynAnyTraitObject(ty)) {
                "RustAny"
            } else {
                ty.bounds.toList().firstNotNullOfOrNull { renderTypeParamBound(it, selfTypeName) } ?: "Any"
            }
        is SynType.Paren -> renderType(ty.elem, selfTypeName)
        is SynType.Group -> renderType(ty.elem, selfTypeName)
        is SynType.Never -> if ("Nothing" in inspector.declaredTypeNames) "kotlin.Nothing" else "Nothing"
        is SynType.Infer -> "Any?"
        is SynType.Macro -> {
            val macPath = ty.mac.path
            val macroSegs = macPath.segments.toList()
            val macroName = macroSegs.lastOrNull()?.ident?.toString()
            if (macroName == "Token") {
                RustTokenCursor.resolveSynTokenStreamTypeName(ty.mac.tokens) ?: "Token"
            } else {
                rustTypeIdentToKotlin(macroName ?: "Token")
            }
        }
        is SynType.Verbatim -> "Any"
    }

private fun KotlinJ2KEmitter.renderReferenceType(ty: SynType.Reference, selfTypeName: String?): String {
    val mutElem = if (ty.mutability != null) unwrapSynType(ty.elem) else null
    val mutVecElemType =
        if (mutElem is SynType.Path && mutElem.qself == null && "Vec" !in inspector.declaredTypeNames) {
            val segs = mutElem.path.segments.toList()
            val lastSeg = segs.lastOrNull()
            val prefixOk =
                segs.dropLast(1).all { seg ->
                    val raw = seg.ident.toString().removePrefix("r#")
                    raw in STDLIB_TYPE_PATH_PREFIXES || raw == "vec"
                }
            val lastName = lastSeg?.ident?.toString()?.removePrefix("r#")
            if (prefixOk && lastName == "Vec") {
                val angleArgs = (lastSeg.arguments as? PathArguments.AngleBracketed)?.args?.toList()
                angleArgs?.firstNotNullOfOrNull { (it as? GenericArgument.TypeArg)?.type }
            } else {
                null
            }
        } else {
            null
        }
    return when {
        mutElem is SynType.Slice -> "MutableList<${renderType(mutElem.elem, selfTypeName)}>"
        mutVecElemType != null -> "MutableList<${renderType(mutVecElemType, selfTypeName)}>"
        ty.mutability != null && shouldUseAddressBackedMutableReference(ty.elem, selfTypeName) -> {
            val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
            CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.mem.RustMutableReference")
            "RustMutableReference<${renderType(ty.elem, selfTypeName)}>"
        }
        else -> renderType(ty.elem, selfTypeName)
    }
}

private fun KotlinJ2KEmitter.renderArrayType(ty: SynType.Array, selfTypeName: String?): String {
    val elemRendered = renderType(ty.elem, selfTypeName)
    if (elemRendered == "Boolean") return "BooleanArray"
    return when (resolveNumericKind(elemRendered)) {
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
                    elemRendered.endsWith("?") -> elemRendered.removeSuffix("?")
                    isOptionFnTypeAliasRenderedType(elemRendered) -> "${elemRendered}Fn"
                    else -> elemRendered
                }
            "List<$nonNullElem>"
        }
    }
}

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

/**
 * Shared state and trait/nominal generic-argument resolution for lowering a `syn.Path` in type position.
 */
internal class TypePathLoweringContext(
    val emitter: KotlinJ2KEmitter,
    val path: Path,
    val rawSegments: List<PathSegment>,
    val selfTypeName: String?,
) {
    val lastSeg: PathSegment = rawSegments.last()
    val rawName: String = lastSeg.ident.toString()
    val rewrittenCleanSegs: List<String> by lazy {
        emitter.rewriteCrateRootAliasAndReexportSegments(
            rawSegments.map { it.ident.toString().removePrefix("r#") },
        )
    }
    private val cachedRenderedAngleArgs: List<String> by lazy {
        val angle = lastSeg.arguments as? PathArguments.AngleBracketed ?: return@lazy emptyList()
        angle.args.toList().mapNotNull { emitter.renderGenericArgument(it, selfTypeName) }
    }
    var externalQualifiedOverride: String? = null
    var prefixQualifier: String = ""

    val isPrimitivePrefix: Boolean =
        rawSegments.size == 1 ||
            rawSegments.dropLast(1).all {
                val p = it.ident.toString()
                p in IGNORED_PATH_PREFIXES || p == "primitive"
            }

    fun renderedAngleTypeArgs(): List<String> = cachedRenderedAngleArgs

    fun resolvePrefixAndQualification() {
        val crossTarget =
            if (rawSegments.size >= 2 && rawSegments.dropLast(1).all { it.arguments is PathArguments.None }) {
                emitter.resolveCrossModulePathTarget(rawSegments.map { it.ident.toString() }, inTypePosition = true)
            } else {
                null
            }
        if (crossTarget != null) {
            val (resolvedSym, trailing) = crossTarget
            val boundHead = emitter.bindOrQualifyResolvedCrossSymbol(resolvedSym)
            if (trailing.isEmpty()) {
                externalQualifiedOverride = boundHead
                prefixQualifier = ""
            } else {
                val midPrefix = trailing.dropLast(1).map(::rustTypeIdentToKotlin)
                prefixQualifier = (listOf(boundHead) + midPrefix).joinToString(".") + "."
            }
            return
        }
        val prefixSegments =
            rawSegments
                .dropLast(1)
                .map { it.ident.toString() }
                .filter {
                    it !in IGNORED_PATH_PREFIXES &&
                        it !in STDLIB_PRELUDE_REEXPORT_MODULES &&
                        it !in emitter.inspector.localInlineModuleNames &&
                        (it.first().isUpperCase() || (emitter.scopeBuilder.isImportedModuleInScope(it) && !emitter.isSuppressedStdlibModuleAlias(it)))
                }.map {
                    if (it.first().isUpperCase()) {
                        rustTypeIdentToKotlin(it)
                    } else {
                        emitter.scopeBuilder.resolveModuleBinding(it)?.kotlinName ?: rustModuleIdentToKotlin(it)
                    }
                }
        prefixQualifier = if (prefixSegments.isNotEmpty()) prefixSegments.joinToString(".") + "." else ""
    }

    fun renderNominalOrTraitTypePath(): String {
        val mappedRawBaseIdent = rustTypeIdentToKotlin(rawName)
        val ktBaseIdent =
            if (rawSegments.size == 1 && prefixQualifier.isEmpty() && externalQualifiedOverride == null) {
                emitter.samePackageUseRenames[mappedRawBaseIdent] ?: mappedRawBaseIdent
            } else {
                mappedRawBaseIdent
            }
        qualifyEnumVariantShadowedOuterTypeIfNeeded(ktBaseIdent)
        bindSameCrateSubpackageSymbolIfNeeded(ktBaseIdent)

        val mappedBase =
            when {
                rawName == "Self" -> selfTypeName ?: "Any"
                externalQualifiedOverride != null -> externalQualifiedOverride!!
                else -> prefixQualifier + ktBaseIdent
            }

        val traitDecl = emitter.lookupDeclaredTraitAst(ktBaseIdent, path)
        val isCoreOpsName =
            ktBaseIdent in CORE_OPS_BINARY_TRAITS ||
                ktBaseIdent in CORE_OPS_UNARY_OUTPUT_TRAITS ||
                ktBaseIdent in CORE_OPS_ASSIGN_TRAITS
        val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(emitter.headerContext)
        val selfOpsSubpkg = emitter.resolveBestSubpackageForSymbol(ktBaseIdent)
        val isExternalCoreOps =
            traitDecl == null &&
                ktBaseIdent !in emitter.inspector.declaredTypeNames &&
                isCoreOpsName &&
                (
                    (prefixQualifier.isEmpty() && externalQualifiedOverride == null && (selfOpsSubpkg == null || selfOpsSubpkg.startsWith("ops"))) ||
                        emitter.importedSimpleNameToPkg[ktBaseIdent]?.startsWith("$coreNs.ops") == true ||
                        prefixQualifier == "ops." ||
                        prefixQualifier.endsWith(".ops.")
                )
        val bounded = emitter.currentBoundedTypeName
        val opsDefaultRhs = bounded ?: selfTypeName ?: "Any?"
        val traitGenerics = traitDecl?.generics
        val rawTraitParams = traitGenerics?.params?.toList().orEmpty()
        val declaredTypeParams = rawTraitParams.filterIsInstance<GenericParam.TypeParam>()
        val lookupAssocs = emitter.lookupTraitDefaultlessAssocTypes(ktBaseIdent, path)
        val effectiveLookupAssocs =
            when {
                lookupAssocs.isNotEmpty() -> lookupAssocs
                isExternalCoreOps && (ktBaseIdent in CORE_OPS_BINARY_TRAITS || ktBaseIdent in CORE_OPS_UNARY_OUTPUT_TRAITS) -> listOf("Output")
                else -> emptyList()
            }
        if (isExternalCoreOps &&
            prefixQualifier.isEmpty() &&
            externalQualifiedOverride == null &&
            ktBaseIdent !in emitter.importedSimpleNameToPkg &&
            !emitter.headerContext.rawAbiMode &&
            "core" in emitter.headerContext.crateNamespaces
        ) {
            val coreExtPkgs = emitter.headerContext.externalCrateSymbolPackages["core"]
            val extCoreOpsPkg = coreExtPkgs?.get(ktBaseIdent)?.firstOrNull()
            val selfCoreOpsPkg = selfOpsSubpkg?.let { if (it.isEmpty()) coreNs else "$coreNs.$it" }
            val opsFqcn =
                when {
                    extCoreOpsPkg != null -> "$extCoreOpsPkg.$ktBaseIdent"
                    selfCoreOpsPkg != null -> "$selfCoreOpsPkg.$ktBaseIdent"
                    else -> "$coreNs.ops.$ktBaseIdent"
                }
            CoreIntrinsicLowering.registerCoreImport(emitter, opsFqcn)
        }

        return when (val args = lastSeg.arguments) {
            is PathArguments.None ->
                renderUnparameterizedPathArgs(mappedBase, ktBaseIdent, isExternalCoreOps, opsDefaultRhs, bounded, declaredTypeParams, effectiveLookupAssocs)
            is PathArguments.AngleBracketed ->
                renderAngleBracketedPathArgs(args, mappedBase, ktBaseIdent, coreNs, traitDecl, isExternalCoreOps, opsDefaultRhs, bounded, declaredTypeParams, effectiveLookupAssocs)
            is PathArguments.Parenthesized -> {
                val inputs = args.inputs.toList().joinToString(", ") { emitter.renderType(it.ty, selfTypeName) }
                val out = extractReturnSynType(args.output)?.let { emitter.renderType(it, selfTypeName) } ?: "Unit"
                "($inputs) -> $out"
            }
        }
    }

    private fun qualifyEnumVariantShadowedOuterTypeIfNeeded(ktBaseIdent: String) {
        if (rawSegments.size != 1 ||
            prefixQualifier.isNotEmpty() ||
            externalQualifiedOverride != null ||
            rawName == "Self" ||
            ktBaseIdent == selfTypeName?.substringBefore('<')?.trim() ||
            ktBaseIdent !in emitter.activeEnclosingEnumVariantNames ||
            ktBaseIdent in emitter.activeTypeParamNames ||
            rawName in PRIMITIVE_TYPE_NAMES
        ) {
            return
        }
        val outerPkg =
            emitter.importedSimpleNameToPkg[ktBaseIdent]
                ?: emitter.headerContext.packageName?.takeIf {
                    it.isNotEmpty() &&
                        (ktBaseIdent in emitter.inspector.declaredTypeNames || ktBaseIdent in emitter.headerContext.moduleDeclaredTypeNames)
                }
                ?: emitter.resolveBestSubpackageForSymbol(ktBaseIdent)?.let { sub ->
                    emitter.currentNamespacePrefix()?.let { ns -> if (sub.isEmpty()) ns else "$ns.$sub" }
                }
        if (!outerPkg.isNullOrEmpty()) {
            externalQualifiedOverride = "$outerPkg.$ktBaseIdent"
        }
    }

    private fun bindSameCrateSubpackageSymbolIfNeeded(ktBaseIdent: String) {
        if (prefixQualifier.isNotEmpty() ||
            externalQualifiedOverride != null ||
            rawSegments.size > 2 ||
            rawName == "Self" ||
            !isPrimitivePrefix ||
            ktBaseIdent in emitter.inspector.declaredTypeNames ||
            ktBaseIdent in emitter.activeTypeParamNames ||
            (ktBaseIdent.length == 1 && ktBaseIdent[0] in 'A'..'Z') ||
            ktBaseIdent in emitter.inspector.enumVariantSimpleNames ||
            ktBaseIdent in emitter.inspector.importedEnumVariantSimpleNames ||
            (ktBaseIdent in KOTLIN_STDLIB_SHADOWED_IMPORTS && rawName != "Float") ||
            emitter.headerContext.crateSymbolSubpackages.isEmpty()
        ) {
            return
        }
        val bestSubpkg = emitter.resolveBestSubpackageForSymbol(ktBaseIdent) ?: return
        val nsPrefix = emitter.currentNamespacePrefix() ?: return
        val targetPkg = if (bestSubpkg.isEmpty()) nsPrefix else "$nsPrefix.$bestSubpkg"
        if (targetPkg.isEmpty() || targetPkg == emitter.headerContext.packageName) return
        val collidedPkg = emitter.importedSimpleNameToPkg[ktBaseIdent]
        if (collidedPkg != null && collidedPkg != targetPkg) {
            externalQualifiedOverride = "$targetPkg.$ktBaseIdent"
        } else {
            emitter.importedSimpleNameToPkg[ktBaseIdent] = targetPkg
            emitter.extraTypePathImports += "import $targetPkg.$ktBaseIdent"
        }
    }

    private fun renderUnparameterizedPathArgs(
        mappedBase: String,
        ktBaseIdent: String,
        isExternalCoreOps: Boolean,
        opsDefaultRhs: String,
        bounded: String?,
        declaredTypeParams: List<GenericParam.TypeParam>,
        effectiveLookupAssocs: List<String>,
    ): String {
        if (isExternalCoreOps) {
            when (ktBaseIdent) {
                in CORE_OPS_BINARY_TRAITS ->
                    return if (emitter.suppressTraitAssocPlaceholders) "$mappedBase<$opsDefaultRhs>" else "$mappedBase<$opsDefaultRhs, Any?>"
                in CORE_OPS_UNARY_OUTPUT_TRAITS ->
                    return if (emitter.suppressTraitAssocPlaceholders) mappedBase else "$mappedBase<Any?>"
                in CORE_OPS_ASSIGN_TRAITS ->
                    return "$mappedBase<$opsDefaultRhs>"
            }
        }
        val defaultedParams =
            declaredTypeParams.mapNotNull { gp ->
                gp.default?.type?.let { emitter.renderType(it, selfTypeName = bounded ?: selfTypeName) }
            }
        val typeArgs =
            buildList {
                addAll(defaultedParams)
                if (bounded != null && emitter.isSelfParameterizedTraitName(ktBaseIdent)) {
                    add(bounded)
                } else if (bounded == null &&
                    !emitter.suppressTraitAssocPlaceholders &&
                    emitter.isSelfParameterizedTraitName(ktBaseIdent) &&
                    defaultedParams.isEmpty() &&
                    effectiveLookupAssocs.isEmpty() &&
                    ktBaseIdent !in emitter.activeTypeParamNames
                ) {
                    add("*")
                }
                for (assocKt in effectiveLookupAssocs) {
                    if (assocKt in emitter.currentTraitAssocParamNames) {
                        add(assocKt)
                    } else if (bounded != null && !emitter.suppressTraitAssocPlaceholders) {
                        add("Any?")
                    }
                }
            }
        return if (typeArgs.isEmpty()) mappedBase else "$mappedBase<${typeArgs.joinToString(", ")}>"
    }

    private fun renderAngleBracketedPathArgs(
        args: PathArguments.AngleBracketed,
        mappedBase: String,
        ktBaseIdent: String,
        coreNs: String,
        traitDecl: Item.Trait?,
        isExternalCoreOps: Boolean,
        opsDefaultRhs: String,
        bounded: String?,
        declaredTypeParams: List<GenericParam.TypeParam>,
        effectiveLookupAssocs: List<String>,
    ): String {
        val declaredTraitAssocs =
            if (effectiveLookupAssocs.isNotEmpty() || ktBaseIdent in emitter.inspector.declaredTypeNames || isExternalCoreOps) {
                effectiveLookupAssocs
            } else {
                null
            }
        val rawArgList = args.args.toList()
        if (traitDecl != null || isExternalCoreOps) {
            val explicitPositionalArgs =
                rawArgList
                    .filter { it !is GenericArgument.AssocTypeArg && it !is GenericArgument.LifetimeArg }
                    .mapNotNull { emitter.renderGenericArgument(it, selfTypeName) }
                    .toMutableList()
            val explicitAssocByName = linkedMapOf<String, String>()
            for (ga in rawArgList) {
                if (ga is GenericArgument.AssocTypeArg) {
                    val assocKt = rustTypeIdentToKotlin(ga.assoc.ident.toString())
                    if (declaredTraitAssocs == null || assocKt in declaredTraitAssocs) {
                        explicitAssocByName[assocKt] = emitter.renderType(ga.assoc.ty, selfTypeName)
                    }
                }
            }
            if (isExternalCoreOps) {
                if ((ktBaseIdent in CORE_OPS_BINARY_TRAITS || ktBaseIdent in CORE_OPS_ASSIGN_TRAITS) && explicitPositionalArgs.isEmpty()) {
                    explicitPositionalArgs += opsDefaultRhs
                }
            } else if (declaredTypeParams.isNotEmpty() && explicitPositionalArgs.size < declaredTypeParams.size) {
                for (idx in explicitPositionalArgs.size until declaredTypeParams.size) {
                    val defTy = declaredTypeParams[idx].default?.type ?: break
                    explicitPositionalArgs += emitter.renderType(defTy, selfTypeName = bounded ?: selfTypeName)
                }
            }
            val finalAssocs =
                effectiveLookupAssocs.mapNotNull { assocKt ->
                    explicitAssocByName[assocKt]
                        ?: when {
                            assocKt in emitter.currentTraitAssocParamNames -> assocKt
                            !emitter.suppressTraitAssocPlaceholders && (bounded != null || isExternalCoreOps) -> "Any?"
                            else -> null
                        }
                }
            val typeArgs =
                buildList {
                    addAll(explicitPositionalArgs)
                    if (bounded != null && emitter.isSelfParameterizedTraitName(ktBaseIdent)) {
                        add(bounded)
                    }
                    addAll(finalAssocs)
                    addAll(emitter.sourceAssociatedArguments(path, selfTypeName))
                }
            return if (typeArgs.isEmpty()) mappedBase else "$mappedBase<${typeArgs.joinToString(", ")}>"
        }

        val filteredArgs =
            rawArgList.filter { ga ->
                ga !is GenericArgument.AssocTypeArg || declaredTraitAssocs == null || rustTypeIdentToKotlin(ga.assoc.ident.toString()) in declaredTraitAssocs
            }
        val rawSegNames = rawSegments.map { it.ident.toString().removePrefix("r#") }
        val explicitArgs = filteredArgs.mapNotNull { emitter.renderGenericArgument(it, selfTypeName) }
        val rawTypeArgs = rawArgList.filterIsInstance<GenericArgument.TypeArg>()
        if (ktBaseIdent == "Atomic" &&
            "Atomic" !in emitter.inspector.declaredTypeNames &&
            !emitter.headerContext.rawAbiMode &&
            "core" in emitter.headerContext.crateNamespaces &&
            rawTypeArgs.size == 1
        ) {
            resolveConcreteStdlibAtomicSimpleName(rawTypeArgs[0].type, explicitArgs.firstOrNull())?.let {
                return emitter.bindOrQualifyResolvedCrossSymbol("$coreNs.sync.atomic.$it")
            }
        }
        val explicitAssocNames =
            rawArgList
                .filterIsInstance<GenericArgument.AssocTypeArg>()
                .mapTo(hashSetOf()) { rustTypeIdentToKotlin(it.assoc.ident.toString()) }
        val defaultedArgs = emitter.appendStdlibDefaultTypeArgsIfNeeded(mappedBase, rawName, rawSegNames, explicitArgs, selfTypeName)
        val extraAssocs =
            emitter
                .lookupTraitDefaultlessAssocTypes(ktBaseIdent, path)
                .filter { it in emitter.currentTraitAssocParamNames && it !in explicitAssocNames && it !in explicitArgs }
        val typeArgs = defaultedArgs + extraAssocs + emitter.sourceAssociatedArguments(path, selfTypeName)
        return if (typeArgs.isEmpty()) mappedBase else "$mappedBase<${typeArgs.joinToString(", ")}>"
    }
}

internal abstract class TypePathLoweringRule {
    abstract fun tryRender(ctx: TypePathLoweringContext): String?
}

private object SelfAndTypeParamPathRule : TypePathLoweringRule() {
    override fun tryRender(ctx: TypePathLoweringContext): String? {
        val emitter = ctx.emitter
        val segs = ctx.rawSegments
        if (segs.size >= 2 && segs[0].ident.toString() == "Self") {
            val assocIdent = segs.last().ident.toString()
            val assocTy = emitter.currentImplAssocTypes[assocIdent]
            if (assocTy != null) {
                val prev = emitter.currentImplAssocTypes
                emitter.currentImplAssocTypes = prev - assocIdent
                return try {
                    emitter.renderType(assocTy, ctx.selfTypeName)
                } finally {
                    emitter.currentImplAssocTypes = prev
                }
            }
            return rustTypeIdentToKotlin(assocIdent)
        }
        if (segs.size == 1 && segs[0].arguments is PathArguments.None) {
            val singleKt = rustTypeIdentToKotlin(segs[0].ident.toString())
            emitter.substitutedTypeParamTypes[singleKt]?.let { return it }
            emitter.renamedTypeParamIdentifiers[singleKt]?.let { return it }
        }
        if (segs.size == 2 &&
            segs[0].arguments is PathArguments.None &&
            rustTypeIdentToKotlin(segs[0].ident.toString()) in emitter.activeTypeParamNames
        ) {
            return rustTypeIdentToKotlin(segs[1].ident.toString())
        }
        if (ctx.rawName == "PhantomData" && "PhantomData" !in emitter.inspector.declaredTypeNames) {
            return "Unit"
        }
        return null
    }
}

private object StdlibFmtAndFfiTypePathRule : TypePathLoweringRule() {
    override fun tryRender(ctx: TypePathLoweringContext): String? {
        val emitter = ctx.emitter
        val rawName = ctx.rawName
        val fmtSegNames = ctx.rewrittenCleanSegs
        val attachedTraitSegs = emitter.currentAttachedImplTraitPath?.segments?.toList()
        val attachedTraitLast = attachedTraitSegs?.lastOrNull()?.ident?.toString()
        val isStdlibFmtModule =
            (fmtSegNames.size >= 3 && fmtSegNames[0] in STDLIB_CRATE_ROOTS && fmtSegNames[1] == "fmt") ||
                (
                    fmtSegNames.size == 2 &&
                        fmtSegNames[0] == "fmt" &&
                        (
                            emitter.scopeBuilder.resolveModuleBinding("fmt") != null ||
                                emitter.headerContext.rawAbiMode ||
                                emitter.headerContext.finalClasses ||
                                attachedTraitLast in FMT_TRAIT_NAMES
                        )
                )
        if (isStdlibFmtModule) {
            when {
                rawName == "Formatter" -> return "StringBuilder"
                rawName == "Write" -> return "Appendable"
                (rawName == "Result" || rawName == "Error") &&
                    emitter.headerContext.packageName?.startsWith("io.github.kotlinmania.rustlang.") != true ->
                    return "Unit"
                (rawName == "DebugStruct" || rawName == "DebugTuple") && rawName !in emitter.inspector.declaredTypeNames ->
                    return rawName
            }
        }
        if (ctx.rawSegments.size == 1 && ctx.lastSeg.arguments is PathArguments.None) {
            if ((rawName == "DebugStruct" || rawName == "DebugTuple") && rawName !in emitter.inspector.declaredTypeNames) {
                return rawName
            }
        }
        if (rawName == "ThreadId" && "ThreadId" !in emitter.inspector.declaredTypeNames) {
            return "Any"
        }
        if (emitter.headerContext.rawAbiMode || ctx.lastSeg.arguments !is PathArguments.None) return null

        val rawSegNames = ctx.rewrittenCleanSegs
        val symLeaf = rawSegNames.last()
        val isSelfContainedLibcFfi =
            rawSegNames.size == 2 &&
                rawSegNames[0] == "libc" &&
                FfiInteropCollector.resolveLibcNamespace(emitter.headerContext) == null
        val isStdlibFfiModule =
            (rawSegNames.size == 3 && rawSegNames[0] in setOf("core", "std") && rawSegNames[1] == "ffi") ||
                (rawSegNames.size == 4 && rawSegNames[0] == "std" && rawSegNames[1] == "os" && rawSegNames[2] == "raw") ||
                isSelfContainedLibcFfi ||
                (
                    rawSegNames.size == 2 && rawSegNames[0] in setOf("ffi", "raw") &&
                        run {
                            val imp = emitter.scopeBuilder.resolveModuleBinding(rawSegNames[0])?.importedPath
                            imp == null || imp.startsWith("core.") || imp.startsWith("std.") || imp.startsWith("alloc.")
                        }
                )
        val isAllocFfiCString = rawSegNames.size == 3 && rawSegNames[0] == "alloc" && rawSegNames[1] == "ffi" && symLeaf == "CString"
        if (isSelfContainedLibcFfi && symLeaf !in CoreIntrinsicLowering.STDLIB_CORE_FFI_SYMBOLS) {
            val ptr64 = emitter.activeCfgProfile.pointerWidth != 32
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
                    emitter.resolveStdlibFfiSymbolImport("CString", isAllocCString = true)
                isStdlibFfiModule && symLeaf in CoreIntrinsicLowering.STDLIB_CORE_FFI_SYMBOLS ->
                    emitter.resolveStdlibFfiSymbolImport(symLeaf)
                else -> null
            } ?: return null
        return emitter.bindOrQualifyResolvedCrossSymbol(resolvedFfiImport)
    }
}

private object StdlibResultTypePathRule : TypePathLoweringRule() {
    override fun tryRender(ctx: TypePathLoweringContext): String? {
        val emitter = ctx.emitter
        if (emitter.headerContext.rawAbiMode || ctx.rawName != "Result") return null
        val rawSegNames = ctx.rewrittenCleanSegs
        val modHead = rawSegNames.getOrNull(rawSegNames.size - 2)
        val resolvedModImp = if (rawSegNames.size == 2 && modHead != null) emitter.scopeBuilder.resolveModuleBinding(modHead)?.importedPath else null
        val isTaggedOrRustLangStd =
            emitter.usesTaggedCoreResult() ||
                emitter.headerContext.packageName?.startsWith("io.github.kotlinmania.rustlang.") == true
        if ("Ok" !in emitter.inspector.declaredTypeNames) {
            fun matchesCoreOrStdMod(expected: String): Boolean {
                val modIdx = rawSegNames.size - 2
                return (modIdx >= 0 && rawSegNames[modIdx] == expected && (modIdx == 0 || rawSegNames[modIdx - 1] in setOf("core", "std", "alloc"))) ||
                    resolvedModImp == expected ||
                    resolvedModImp == "core.$expected" ||
                    resolvedModImp == "std.$expected" ||
                    resolvedModImp == "alloc.$expected"
            }
            if (ctx.lastSeg.arguments is PathArguments.None &&
                rawSegNames.size >= 2 &&
                matchesCoreOrStdMod("fmt") &&
                isTaggedOrRustLangStd &&
                ("Result" !in emitter.inspector.declaredTypeNames || rawSegNames.dropLast(1).lastOrNull() == "fmt" || resolvedModImp != null)
            ) {
                if (!emitter.usesTaggedCoreResult()) {
                    return if ("Result" in emitter.inspector.declaredTypeNames) "kotlin.Result<Unit>" else "Result<Unit>"
                }
                val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(emitter.headerContext)
                val errRef = emitter.bindOrQualifyResolvedCrossSymbol("$coreNs.fmt.Error")
                val resName =
                    when {
                        emitter.hasDeclaredResultEnum() -> "Result"
                        "Result" in emitter.inspector.declaredTypeNames -> "$coreNs.result.Result"
                        else -> emitter.sourceResultName()
                    }
                return "$resName<Unit, $errRef>"
            }
            if (!emitter.hasDeclaredResultEnum() && ctx.lastSeg.arguments is PathArguments.AngleBracketed) {
                val isStdOrCoreResult = rawSegNames.size == 1 || matchesCoreOrStdMod("result") || (rawSegNames.size == 2 && modHead == "result")
                val argsList = ctx.renderedAngleTypeArgs()
                if (isStdOrCoreResult && argsList.size == 2 && (rawSegNames.size >= 2 || "Result" !in emitter.inspector.declaredTypeNames)) {
                    return when {
                        emitter.usesTaggedCoreResult() && "Result" in emitter.inspector.declaredTypeNames ->
                            "${CoreIntrinsicLowering.resolveCoreNamespace(emitter.headerContext)}.result.Result<${argsList.joinToString(", ")}>"
                        emitter.usesTaggedCoreResult() -> "${emitter.sourceResultName()}<${argsList.joinToString(", ")}>"
                        "Result" in emitter.inspector.declaredTypeNames -> "kotlin.Result<${argsList[0]}>"
                        else -> "Result<${argsList[0]}>"
                    }
                }
            }
        }
        if ("Result" !in emitter.inspector.declaredTypeNames &&
            !emitter.hasDeclaredResultEnum() &&
            isTaggedOrRustLangStd &&
            ctx.lastSeg.arguments is PathArguments.AngleBracketed
        ) {
            val typeArgs = ctx.renderedAngleTypeArgs()
            if (typeArgs.size == 1) {
                fun matchesStdCrateMod(expected: String): Boolean =
                    modHead == expected &&
                        (
                            (rawSegNames.size == 3 && rawSegNames[0] in setOf("std", "crate")) ||
                                (
                                    rawSegNames.size == 2 &&
                                        (
                                            resolvedModImp == null ||
                                                resolvedModImp.startsWith("std.") ||
                                                resolvedModImp.startsWith("crate.") ||
                                                emitter.headerContext.packageName?.startsWith("io.github.kotlinmania.rustlang.std") == true
                                        )
                                )
                        )
                val resName = if (emitter.usesTaggedCoreResult()) emitter.sourceResultName() else "Result"
                if (matchesStdCrateMod("io")) {
                    val crateNsMap = emitter.headerContext.crateNamespaces
                    val rawStd = crateNsMap["std"]?.trim()?.removeSuffix(".")
                    val stdNs =
                        rawStd?.takeIf { it.isNotEmpty() }
                            ?: "io.github.kotlinmania.rustlang.std".takeIf { emitter.headerContext.packageName?.startsWith(it) == true }
                    val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(emitter.headerContext)
                    val ioErr =
                        if (stdNs != null || "core" in emitter.headerContext.crateNamespaces || emitter.headerContext.packageName?.startsWith("$coreNs.") == true) {
                            emitter.bindOrQualifyResolvedCrossSymbol("$coreNs.io.error.Error")
                        } else {
                            "Error"
                        }
                    return "$resName<${typeArgs[0]}, $ioErr>"
                }
                if (matchesStdCrateMod("thread")) {
                    return "$resName<${typeArgs[0]}, RustAny>"
                }
            }
        }
        return null
    }
}

private object StdlibContainerAndPrimitiveTypePathRule : TypePathLoweringRule() {
    private val erasedSmartPointers = setOf("Box", "Rc", "Arc", "NoDrop", "ManuallyDrop")
    private val builtinScalarMap =
        mapOf(
            "i8" to "Byte",
            "i16" to "Short",
            "i32" to "Int",
            "i64" to "Long",
            "isize" to "Int",
            "u8" to "UByte",
            "u16" to "UShort",
            "u32" to "UInt",
            "u64" to "ULong",
            "usize" to "Int",
            "f64" to "Double",
            "bool" to "Boolean",
            "str" to "String",
            "char" to "Char",
        )

    override fun tryRender(ctx: TypePathLoweringContext): String? {
        val emitter = ctx.emitter
        val rawName = ctx.rawName
        val rawSegments = ctx.rawSegments
        val isStdlibUnqualified =
            (ctx.prefixQualifier.isEmpty() && ctx.externalQualifiedOverride == null) ||
                rawSegments.dropLast(1).all { it.ident.toString() in STDLIB_TYPE_PATH_PREFIXES }
        if (isStdlibUnqualified && ctx.lastSeg.arguments is PathArguments.AngleBracketed) {
            renderStdlibGenericType(ctx, emitter, rawName, rawSegments)?.let { return it }
        } else if (isStdlibUnqualified && ctx.lastSeg.arguments is PathArguments.None && rawName == "Vec" && "Vec" !in emitter.inspector.declaredTypeNames) {
            return "List<*>"
        }

        if (rawName == "TypeId" &&
            ctx.lastSeg.arguments is PathArguments.None &&
            "TypeId" !in emitter.inspector.declaredTypeNames &&
            emitter.isStdlibAnyPathSegments(rawSegments.map { it.ident.toString() })
        ) {
            return "RustTypeId"
        }

        if (!ctx.isPrimitivePrefix || ctx.lastSeg.arguments !is PathArguments.None) return null
        if (emitter.inExternAbiSignature || emitter.headerContext.rawAbiMode) {
            when (rawName) {
                "usize" ->
                    return when (emitter.activeCfgProfile.pointerWidth) {
                        16 -> "UShort"
                        32 -> "UInt"
                        else -> "ULong"
                    }
                "isize" ->
                    return when (emitter.activeCfgProfile.pointerWidth) {
                        16 -> "Short"
                        32 -> "Int"
                        else -> "Long"
                    }
            }
        }
        when {
            rawName == "f16" -> return "F16"
            rawName == "f32" -> return emitter.kotlinF32TypeName()
            rawName == "f128" -> return "F128"
            rawName == "char" && emitter.headerContext.scalarChar && "Char" !in emitter.inspector.declaredTypeNames -> {
                emitter.usesRustChar = true
                return "RustChar"
            }
        }
        builtinScalarMap[rawName]?.let { return emitter.kotlinBuiltinTypeName(it) }
        if (rawName == "c_void" &&
            !emitter.headerContext.rawAbiMode &&
            "CVoid" !in emitter.inspector.declaredTypeNames &&
            "CVoid" !in emitter.importedSimpleNameToPkg &&
            emitter.resolveBestSubpackageForSymbol("CVoid") == null
        ) {
            emitter.resolveStdlibFfiSymbolImport("c_void")?.let {
                CoreIntrinsicLowering.registerCoreImport(emitter, it)
            }
        }
        return null
    }

    private fun renderStdlibGenericType(
        ctx: TypePathLoweringContext,
        emitter: KotlinJ2KEmitter,
        rawName: String,
        rawSegments: List<PathSegment>,
    ): String? {
        val typeArgs = ctx.renderedAngleTypeArgs()
        if (rawName == "Option" && !emitter.hasDeclaredOptionEnum() && typeArgs.size == 1) {
            val inner = typeArgs[0]
            return if (emitter.isAlreadyNullableRenderedType(inner)) inner else "$inner?"
        }
        if (rawName == "Padding" && "Padding" !in emitter.inspector.declaredTypeNames && typeArgs.size == 1) {
            return typeArgs[0]
        }
        if (rawName == "MaybeUninit" && "MaybeUninit" !in emitter.inspector.declaredTypeNames && typeArgs.size == 1) {
            val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(emitter.headerContext)
            CoreIntrinsicLowering.registerCoreImport(emitter, "$coreNs.mem.maybeuninit.MaybeUninit")
            return "MaybeUninit<${typeArgs[0]}>"
        }
        if ((rawName == "Iter" || rawName == "IterMut") && rawSegments.any { it.ident.toString() == "slice" } && typeArgs.isNotEmpty()) {
            return "ArrayDeque<${typeArgs[0]}>"
        }
        if (rawName == "IntoIter" && rawSegments.any { it.ident.toString() == "vec" || it.ident.toString() == "option" } && typeArgs.isNotEmpty()) {
            return "ArrayDeque<${typeArgs[0]}>"
        }
        val isExplicitStdResultPath =
            rawName == "Result" &&
                rawSegments.size >= 3 &&
                rawSegments[rawSegments.size - 3].ident.toString() in setOf("std", "core") &&
                rawSegments[rawSegments.size - 2].ident.toString() == "result"
        if (isExplicitStdResultPath && rawName in emitter.inspector.declaredTypeNames && typeArgs.isNotEmpty()) {
            val crateNsMap = emitter.headerContext.crateNamespaces
            val rawCore = crateNsMap["core"]?.trim()?.removeSuffix(".")
            val coreNs = rawCore?.takeIf { it.isNotEmpty() }
            return if (coreNs != null && typeArgs.size == 2) {
                "$coreNs.result.Result<${typeArgs.joinToString(", ")}>"
            } else {
                "kotlin.Result<${typeArgs[0]}>"
            }
        }
        if (rawName in emitter.inspector.declaredTypeNames) return null
        if (rawName in setOf("Cell", "RefCell", "OnceCell") && typeArgs.size == 1) {
            val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(emitter.headerContext)
            val subpkg = if (rawName == "OnceCell") "cell.once" else "cell"
            CoreIntrinsicLowering.registerCoreImport(emitter, "$coreNs.$subpkg.$rawName")
            return "$rawName<${typeArgs[0]}>"
        }
        return when {
            rawName == "Vec" && typeArgs.isNotEmpty() -> "List<${typeArgs[0]}>"
            rawName == "Cow" && typeArgs.isNotEmpty() -> typeArgs.last()
            (rawName == "Range" || rawName == "RangeInclusive") && typeArgs.size == 1 && typeArgs[0] == "Int" -> "IntRange"
            rawName in erasedSmartPointers && typeArgs.isNotEmpty() -> typeArgs[0]
            (rawName == "HashMap" || rawName == "BTreeMap") && typeArgs.size >= 2 -> "MutableMap<${typeArgs[0]}, ${typeArgs[1]}>"
            (rawName == "HashSet" || rawName == "BTreeSet") && typeArgs.isNotEmpty() -> "MutableSet<${typeArgs[0]}>"
            rawName == "Result" && "Ok" !in emitter.inspector.declaredTypeNames && !emitter.hasDeclaredResultEnum() && emitter.usesTaggedCoreResult() && typeArgs.size == 2 ->
                "${emitter.sourceResultName()}<${typeArgs.joinToString(", ")}>"
            rawName == "Result" && "Ok" !in emitter.inspector.declaredTypeNames && !emitter.hasDeclaredResultEnum() && !emitter.usesTaggedCoreResult() && typeArgs.isNotEmpty() ->
                "Result<${typeArgs[0]}>"
            else -> null
        }
    }
}

private val EARLY_TYPE_PATH_RULES: List<TypePathLoweringRule> =
    listOf(
        SelfAndTypeParamPathRule,
        StdlibFmtAndFfiTypePathRule,
        StdlibResultTypePathRule,
    )

internal fun KotlinJ2KEmitter.renderPathAsType(path: Path, selfTypeName: String?): String {
    val rawSegments = path.segments.toList()
    if (rawSegments.isEmpty()) return "Any"
    val ctx = TypePathLoweringContext(this, path, rawSegments, selfTypeName)
    for (rule in EARLY_TYPE_PATH_RULES) {
        rule.tryRender(ctx)?.let { return it }
    }
    ctx.resolvePrefixAndQualification()
    StdlibContainerAndPrimitiveTypePathRule.tryRender(ctx)?.let { return it }
    return ctx.renderNominalOrTraitTypePath()
}

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

/**
 * Emits Kotlin generic type parameter declarations (`<T : Bound>`), type argument lists (`<T, Assoc>`),
 * trait bounds, and merged `where` clauses from Rust `syn.Generics` and `WhereClause` ASTs.
 */
internal class TypeParameterClauseEmitter(
    private val emitter: KotlinJ2KEmitter,
    private val selfTypeName: String?,
) {
    fun renderTypeParameters(generics: Generics, excludeNames: Set<String> = emptySet()): String {
        val ownerSimple = selfTypeName?.substringBefore('<')?.substringAfterLast('.')?.trim()
        val declared = renderTypeParametersFromParams(generics.params.toList(), excludeNames).removePrefix("<").removeSuffix(">")
        val associated =
            emitter
                .sourceAssociatedParameters(generics, ownerSimple)
                .map { it.associated }
                .distinct()
                .filter { it !in excludeNames }
        val parts = listOf(declared).filter(String::isNotEmpty) + associated
        return if (parts.isEmpty()) "" else "<${parts.joinToString(", ")}>"
    }

    fun renderTypeParametersFromParams(params: List<GenericParam>, excludeNames: Set<String> = emptySet()): String {
        val rendered = mutableListOf<String>()
        val seenNames = hashSetOf<String>()
        for (param in params) {
            if (param is GenericParam.TypeParam) {
                val rawTpName = rustTypeIdentToKotlin(param.ident.toString())
                val name = emitter.renamedTypeParamIdentifiers[rawTpName] ?: rawTpName
                if (rawTpName in excludeNames || name in excludeNames || !seenNames.add(name)) continue
                val bounds = collectRenderedBoundsForTypeParam(param, name, excludeSimdElement = true)
                rendered += if (bounds.size == 1) "$name : ${bounds[0]}" else name
            }
        }
        return if (rendered.isEmpty()) "" else "<${rendered.joinToString(", ")}>"
    }

    fun renderCombinedWhereClause(
        ownerWhere: WhereClause?,
        fnWhere: WhereClause?,
        params: List<GenericParam>,
        excludeNames: Set<String> = emptySet(),
        extraDeclaredNames: Set<String> = emptySet(),
    ): String {
        val declaredFromParams =
            params
                .mapNotNull { (it as? GenericParam.TypeParam)?.ident?.toString()?.let(::rustTypeIdentToKotlin) }
                .filter { it !in excludeNames }
                .map { emitter.renamedTypeParamIdentifiers[it] ?: it }
                .filter { it !in excludeNames }
        val declaredTypeParamNames = (declaredFromParams + extraDeclaredNames.filter { it !in excludeNames }).toSet()
        if (declaredTypeParamNames.isEmpty()) return ""
        val clauses = mutableListOf<String>()
        val seenParamNames = hashSetOf<String>()
        for (param in params) {
            if (param is GenericParam.TypeParam) {
                val rawTpName = rustTypeIdentToKotlin(param.ident.toString())
                val name = emitter.renamedTypeParamIdentifiers[rawTpName] ?: rawTpName
                if (rawTpName in excludeNames || name in excludeNames || !seenParamNames.add(name)) continue
                val bounds = collectRenderedBoundsForTypeParam(param, name, excludeSimdElement = false)
                if (bounds.size > 1) {
                    bounds.forEach { clauses += "$name : $it" }
                }
            }
        }
        val predicates =
            buildList {
                ownerWhere?.predicates?.let { addAll(it.toList()) }
                fnWhere?.predicates?.let { addAll(it.toList()) }
            }
        for (pred in predicates) {
            if (pred is WherePredicate.TypePredicate) {
                if (!emitter.isSimpleTypeParamWhereTarget(pred.boundedTy)) continue
                val boundedTy = emitter.renderType(pred.boundedTy, selfTypeName)
                if (boundedTy in excludeNames || !emitter.isValidKotlinIdentifierText(boundedTy)) continue
                if (boundedTy !in declaredTypeParamNames) continue
                val prevBounded = emitter.currentBoundedTypeName
                emitter.currentBoundedTypeName = boundedTy
                try {
                    for (bound in pred.bounds.toList()) {
                        val b = renderTypeParamBound(bound)
                        if (b != null && b != "Any" && b != boundedTy && !b.contains("->") && !b.startsWith("SimdElement")) {
                            clauses += "$boundedTy : $b"
                        }
                    }
                } finally {
                    emitter.currentBoundedTypeName = prevBounded
                }
            }
        }
        val distinct = clauses.distinct()
        return if (distinct.isEmpty()) "" else " where ${distinct.joinToString(", ")}"
    }

    fun renderTypeParamBound(bound: TypeParamBound): String? {
        if (bound !is TypeParamBound.Trait || bound.maybe != null) return null
        val segs = bound.path.segments.toList()
        val lastSeg = segs.lastOrNull() ?: return null
        val last = lastSeg.ident.toString()
        val ktTrait = rustTypeIdentToKotlin(last)
        val boundedTp = emitter.currentBoundedTypeName
        val isPrivateSealedBound = segs.size >= 2 && segs[segs.size - 2].ident.toString() in setOf("private", "sealed") && last == "Sealed"
        if (isPrivateSealedBound ||
            last in IGNORED_TRAIT_BOUNDS ||
            isFixedScalarCodecAssocTrait(last, ktTrait, bound.path) ||
            (boundedTp != null && ktTrait in emitter.currentWitnessedTraitBounds[boundedTp].orEmpty())
        ) {
            return null
        }
        if (boundedTp != null &&
            (last == "IntoIterator" || last in setOf("Iterator", "DoubleEndedIterator", "ExactSizeIterator", "FusedIterator")) &&
            ktTrait !in emitter.inspector.declaredTypeNames
        ) {
            val angleArgs = (lastSeg.arguments as? PathArguments.AngleBracketed)?.args?.toList()
            val itemTy =
                angleArgs?.firstNotNullOfOrNull { ga ->
                    if (ga is GenericArgument.AssocTypeArg && ga.assoc.ident.toString() == "Item") {
                        emitter.renderType(ga.assoc.ty, selfTypeName)
                    } else {
                        null
                    }
                }
            return when {
                itemTy != null -> if (last == "IntoIterator") "Iterable<$itemTy>" else "Iterator<$itemTy>"
                last != "IntoIterator" && "Item" in emitter.activeTypeParamNames -> "Iterator<Item>"
                else -> null
            }
        }
        if (emitter.isImplicitStdlibSupertraitPath(bound.path)) return null
        return emitter.renderPathAsType(bound.path, selfTypeName)
    }

    private fun isFixedScalarCodecAssocTrait(last: String, ktTrait: String, boundPath: Path): Boolean {
        if (emitter.lookupTraitDefaultlessAssocTypes(ktTrait, boundPath).isEmpty()) return false
        return sameFileNestedItems(emitter.file.items).filterIsInstance<Item.Impl>().any { implItem ->
            val traitPath = implItem.traitPath?.path
            val traitSegs = traitPath?.segments?.toList()
            traitSegs?.lastOrNull()?.ident?.toString() == last && fixedScalarPointerCodec(implItem.selfType) != null
        }
    }

    private fun collectRenderedBoundsForTypeParam(
        param: GenericParam.TypeParam,
        boundedName: String,
        excludeSimdElement: Boolean,
    ): List<String> {
        val prevBounded = emitter.currentBoundedTypeName
        emitter.currentBoundedTypeName = boundedName
        return try {
            param.bounds
                .toList()
                .mapNotNull(::renderTypeParamBound)
                .filter {
                    it != "Any" &&
                        it != boundedName &&
                        !it.contains("->") &&
                        (!excludeSimdElement || !it.startsWith("SimdElement"))
                }
        } finally {
            emitter.currentBoundedTypeName = prevBounded
        }
    }
}

internal fun KotlinJ2KEmitter.renderTypeParameters(
    generics: Generics,
    selfTypeName: String?,
    excludeNames: Set<String> = emptySet(),
): String =
    TypeParameterClauseEmitter(this, selfTypeName).renderTypeParameters(generics, excludeNames)

internal fun KotlinJ2KEmitter.renderTypeParametersFromParams(
    params: List<GenericParam>,
    selfTypeName: String?,
    excludeNames: Set<String> = emptySet(),
): String =
    TypeParameterClauseEmitter(this, selfTypeName).renderTypeParametersFromParams(params, excludeNames)

internal fun KotlinJ2KEmitter.renderTypeArgumentsFromGenerics(
    generics: Generics,
    ownerTypeSimpleName: String? = null,
): String {
    val names =
        generics.params.toList().mapNotNull { param ->
            val rawIdent = (param as? GenericParam.TypeParam)?.ident?.toString() ?: return@mapNotNull null
            val rawTp = rustTypeIdentToKotlin(rawIdent)
            renamedTypeParamIdentifiers[rawTp] ?: rawTp
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
): String =
    TypeParameterClauseEmitter(this, selfTypeName).renderCombinedWhereClause(ownerWhere, fnWhere, params, excludeNames, extraDeclaredNames)

internal fun KotlinJ2KEmitter.renderTypeParamBound(bound: TypeParamBound, selfTypeName: String?): String? =
    TypeParameterClauseEmitter(this, selfTypeName).renderTypeParamBound(bound)

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
        val segs = path.segments.toList()
        if (segs.lastOrNull()?.ident?.toString() == "test") return true
        val meta = attr.meta
        if (meta is Meta.List && meta.path.isIdent("cfg") && containsPositiveCfgTestIdent(meta.tokens.clone())) {
            return true
        }
    }
    return false
}

internal fun KotlinJ2KEmitter.containsPositiveCfgTestIdent(stream: TokenStream): Boolean {
    val cursor = RustTokenCursor(stream.clone().toList())
    var idx = 0
    while (idx < cursor.size) {
        val word = cursor.identAt(idx)
        if (word == "not" && cursor.groupAt(idx + 1) != null) {
            idx += 2
            continue
        }
        if (word == "test") return true
        if (cursor.groupAt(idx)?.let { containsPositiveCfgTestIdent(it.stream()) } == true) return true
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
            val pathSegs = body.path.segments
            val identText = pathSegs.first()?.ident?.toString()
            body.qself == null &&
                pathSegs.size == 1 &&
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
            val rawParam = (singleInput?.let(this::unwrapTransparentPat) as? Pat.Ident)?.ident?.toString()?.removePrefix("r#")
            rawParam != null && isTransparentIdentityExpr(argExpr.body, rawParam)
        }
        is Expr.Paren -> isTransparentWrapperOrIdentityMapArg(argExpr.expr)
        is Expr.Group -> isTransparentWrapperOrIdentityMapArg(argExpr.expr)
        else -> false
    }

internal fun KotlinJ2KEmitter.isSimpleTypeParamWhereTarget(ty: SynType): Boolean {
    val pathTy = unwrapSynType(ty) as? SynType.Path ?: return false
    if (pathTy.qself != null || pathTy.path.leadingColon != null) return false
    val pathSegs = pathTy.path.segments
    val singleSeg = pathSegs.toList().singleOrNull() ?: return false
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
        .mapNotNull { (it as? GenericParam.TypeParam)?.ident?.toString()?.let(::rustTypeIdentToKotlin) }
        .toSet()
}

internal fun KotlinJ2KEmitter.collectGenericConstParamNames(generics: Generics?): Set<String> {
    if (generics == null) return emptySet()
    return generics.params
        .toList()
        .mapNotNull { (it as? GenericParam.ConstParam)?.ident?.toString()?.let(::rustValueIdentToKotlin) }
        .toSet()
}

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
    val simpleBase = base.substringAfterLast('.')
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
            if (!headerContext.rawAbiMode && unwrapSynType(aliasTarget) is SynType.Ptr) return null
            val primTarget = extractSinglePrimitiveTargetIdent(aliasTarget)
            val isFfiAlias =
                headerContext.rawAbiMode ||
                    inExternAbiSignature ||
                    base in inspector.ffiReferencedTypeNames ||
                    simpleBase in inspector.ffiReferencedTypeNames
            val keyVariants = distinctBaseAndSimpleKeys(base, simpleBase)
            fun hasScannerPointerWidthKind(codes: Set<String>): Boolean =
                keyVariants.any { candidate ->
                    scopedAbiKeysForRenderedType(candidate).any { headerContext.crateNumericTypeKinds[it] in codes }
                }
            if (primTarget == "usize" && (isFfiAlias || isCPointerWidthUnsignedTypeName(simpleBase) || hasScannerPointerWidthKind(setOf("U64", "U32", "U16")))) {
                return ptrUnsigned
            }
            if (primTarget == "isize" && (isFfiAlias || isCPointerWidthSignedTypeName(simpleBase) || hasScannerPointerWidthKind(setOf("I64", "I32", "I16")))) {
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
    if (simpleBase == "CLong" || simpleBase == "c_long" || simpleBase == "CUlong" || simpleBase == "c_ulong") {
        val curSub = currentSubpackageString()
        if (curSub.isNotEmpty()) {
            numericKindFromScannerCode(headerContext.crateNumericTypeKinds["$curSub.$simpleBase"])?.let { return it }
        }
        return if (simpleBase == "CLong" || simpleBase == "c_long") cLongSigned else cLongUnsigned
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
        "CLonglong", "c_longlong", "Int64T", "int64_t", "OffT", "off_t", "Off64T", "off64_t",
        "TimeT", "time_t", "BlkcntT", "blkcnt_t", "BlksizeT", "blksize_t",
        -> NumericKind.I64
        "CUchar", "c_uchar", "Uint8T", "uint8_t" -> NumericKind.U8
        "CUshort", "c_ushort", "Uint16T", "uint16_t", "SaFamilyT", "sa_family_t", "InPortT", "in_port_t" ->
            NumericKind.U16
        "CUint", "c_uint", "Uint32T", "uint32_t", "CanidT", "canid_t", "UidT", "uid_t", "GidT", "gid_t",
        "ModeT", "mode_t", "SocklenT", "socklen_t", "InAddrT", "in_addr_t",
        -> NumericKind.U32
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
    if (kind == null || isAlreadyRenderedAsNumericKind(rendered, kind)) return rendered
    val core = unwrapTransparentCastExpr(expr)
    val isCompound = core is Expr.Binary || (core is Expr.Unary && (core.op is UnOp.NotOp || core.op is UnOp.Neg))
    val recv = if (isAtomicPostfixReceiver(expr, rendered)) rendered else "($rendered)"
    if (!isCompound) {
        if (core is Expr.Lit) return rendered
        val inferred = inferExprNumericKind(expr, selfTypeName)
        if (inferred == null || inferred == kind) return rendered
        return when (kind) {
            NumericKind.F16 -> convertNumericReceiver(recv, "F16", inferred)
            NumericKind.F128 -> convertNumericReceiver(recv, "F128", inferred)
            NumericKind.I128, NumericKind.U128 -> rendered
            else -> {
                val suffix = NUMERIC_SUFFIX_BY_KIND.getValue(kind)
                if (rendered.endsWith(suffix)) rendered else "$recv$suffix"
            }
        }
    }
    val isBitwiseCompound =
        (
            core is Expr.Binary &&
                (core.op is BinOp.BitAnd || core.op is BinOp.BitOr || core.op is BinOp.BitXor || core.op is BinOp.Shl || core.op is BinOp.Shr)
        ) ||
            (core is Expr.Unary && core.op is UnOp.NotOp)
    if ((kind == NumericKind.U8 || kind == NumericKind.U16) && isBitwiseCompound && inferExprNumericKind(core, selfTypeName) == kind) {
        return rendered
    }
    if (kind == NumericKind.U8 || kind == NumericKind.U16 || kind == NumericKind.I8 || kind == NumericKind.I16) {
        val suffix = NUMERIC_SUFFIX_BY_KIND.getValue(kind)
        return if (rendered.endsWith(suffix)) rendered else "$recv$suffix"
    }
    return rendered
}

internal fun KotlinJ2KEmitter.renderPointerType(ptr: SynType.Ptr, selfTypeName: String?): String {
    if (headerContext.rawAbiMode) return "Long"
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

internal fun KotlinJ2KEmitter.isAlreadyNullableRenderedType(rendered: String): Boolean =
    rendered.trim().endsWith("?") || isOptionFnTypeAliasRenderedType(rendered)

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
                val argIdent = arg.name?.ident
                val rawIdent = argIdent?.toString()?.removePrefix("r#")
                val rawName = rawIdent?.takeIf { it.isNotEmpty() && it != "_" }
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
            val lambdaParams = if (paramTypes.isEmpty()) "" else paramTypes.indices.joinToString(", ") { "_" } + " -> "
            synthesizedSamDefaultsByName[samName] =
                if (lambdaParams.isEmpty() && retDefault.isEmpty()) {
                    "$samName {}"
                } else {
                    "$samName { ${(lambdaParams + retDefault).trim()} }"
                }
        }
        val retSuffix = if (retType == "Unit") "" else ": $retType"
        val joinedParams = params.joinToString(", ")
        "$indent${vis}fun interface $samName {\n$indent    public operator fun invoke($joinedParams)$retSuffix\n$indent}"
    } finally {
        currentSamNameHint = prevHint
        inExternAbiSignature = prevExternAbi
    }
}

internal fun KotlinJ2KEmitter.extractEnumReprKotlinType(attrs: List<Attribute>): String? {
    for (attr in attrs) {
        val meta = attr.meta
        if (meta is Meta.List && meta.path.isIdent("repr")) {
            val cursor = RustTokenCursor(meta.tokens.clone().toList())
            for (idx in 0 until cursor.size) {
                val ident = cursor.identAt(idx) ?: continue
                if (ident != "C" && ident != "transparent" && ident != "packed" && ident != "align") {
                    return rustTypeIdentToKotlin(ident)
                }
            }
        }
    }
    return null
}

internal fun KotlinJ2KEmitter.hasReprSimdAttr(attrs: List<Attribute>): Boolean =
    attrs.any { attr ->
        val meta = attr.meta as? Meta.List ?: return@any false
        if (!meta.path.isIdent("repr")) return@any false
        val cursor = RustTokenCursor(meta.tokens.clone().toList())
        (0 until cursor.size).any { cursor.identAt(it) == "simd" }
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
                val segs = bound.path.segments.toList()
                val segNames = segs.map { it.ident.toString() }
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
                    (segs[0] in headerContext.crateNamespaces || headerContext.packageName?.startsWith("io.github.kotlinmania.rustlang.") == true)
            ) ||
                (segs[0] == "crate" && headerContext.packageName?.startsWith("io.github.kotlinmania.rustlang.") == true)
        if (isMappedOrSelfStdCrate && segs[1].firstOrNull()?.isLowerCase() == true && !isKnownFunctionLeafIdent(segs[1])) {
            return true
        }
    }
    return segs.size == 6 &&
        segs[0] == "io" &&
        segs[1] == "github" &&
        segs[2] == "kotlinmania" &&
        segs[3] == "rustlang" &&
        segs[5].firstOrNull()?.isLowerCase() == true &&
        !isKnownFunctionLeafIdent(segs[5])
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
