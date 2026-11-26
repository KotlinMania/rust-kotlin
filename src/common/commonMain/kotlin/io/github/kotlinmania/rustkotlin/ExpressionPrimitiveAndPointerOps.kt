package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.syn.Expr
import io.github.kotlinmania.syn.GenericArgument
import io.github.kotlinmania.syn.Item
import io.github.kotlinmania.syn.Meta
import io.github.kotlinmania.syn.Path
import io.github.kotlinmania.syn.PathArguments
import io.github.kotlinmania.syn.QSelf
import io.github.kotlinmania.syn.RangeLimits
import io.github.kotlinmania.syn.SynType

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

/**
 * Single authoritative catalog of Rust integer and floating-point primitive operations,
 * their Kotlin / `core` / `libm` target names, extra-argument numeric kinds, and return-type
 * shapes so both expression lowering and return-type inference query one source of truth.
 */
internal object PrimitiveNumericOpCatalog {
    val integerBitCountMethods: Map<String, String> =
        mapOf(
            "count_ones" to "countOnes",
            "count_zeros" to "countZeros",
            "leading_zeros" to "leadingZeros",
            "trailing_zeros" to "trailingZeros",
            "leading_ones" to "leadingOnes",
            "trailing_ones" to "trailingOnes",
        )

    val integerUnaryOpMethods: Map<String, String> =
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
        ) + integerBitCountMethods

    val integerShiftOrPowMethods: Map<String, String> =
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

    val integerBinaryOpMethods: Map<String, String> =
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

    private val allIntegerOpNames: Set<String> =
        integerUnaryOpMethods.keys + integerShiftOrPowMethods.keys + integerBinaryOpMethods.keys

    val signedOnlyIntegerMethods: Set<String> =
        setOf("checked_abs", "wrapping_abs", "saturating_abs", "overflowing_abs", "saturating_neg")

    val endian128Methods: Map<String, String> =
        mapOf(
            "to_be" to "toBe",
            "from_be" to "fromBe",
            "swap_bytes" to "swapBytes",
            "to_le" to "toLe",
            "from_le" to "fromLe",
        )

    val standaloneCheckedI32Ops: Map<String, String> =
        mapOf(
            "checked_add" to "+",
            "checked_sub" to "-",
            "checked_mul" to "*",
        )

    val floatStdlibBoolPredicates: Map<String, String> =
        mapOf(
            "is_nan" to "isNaN",
            "is_infinite" to "isInfinite",
            "is_finite" to "isFinite",
        )

    val floatCoreBoolPredicates: Map<String, String> =
        mapOf(
            "is_sign_positive" to "isSignPositive",
            "is_sign_negative" to "isSignNegative",
            "is_normal" to "isNormal",
            "is_subnormal" to "isSubnormal",
        )

    val floatCoreSelfMethods: Map<String, String> =
        mapOf(
            "signum" to "signum",
            "to_degrees" to "toDegrees",
            "to_radians" to "toRadians",
            "next_up" to "nextUp",
            "next_down" to "nextDown",
        )

    val floatLibraryMethods: Map<String, String> =
        floatCoreBoolPredicates + mapOf("classify" to "classify") + floatCoreSelfMethods

    val floatKmUnaryBases: Map<String, String> =
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

    val floatLibmUnaryOps: Set<String> =
        setOf("tan", "asin", "acos", "atan", "cbrt", "sinh", "cosh", "tanh", "asinh", "acosh", "atanh")

    val floatLibmBinaryOps: Set<String> = setOf("atan2", "hypot")

    val floatKmBinaryBases: Map<String, String> =
        mapOf(
            "powf" to "kmPow",
            "copysign" to "kmCopysign",
            "min" to "kmMinnum",
            "max" to "kmMaxnum",
        )

    private val floatSameTypeReturnMethods: Set<String> =
        (floatKmUnaryBases.keys - "round_ties_even") +
            floatLibmUnaryOps +
            floatLibmBinaryOps +
            setOf("powf", "powi", "min", "max", "fract", "mul_add", "recip", "to_degrees", "to_radians", "signum", "log")

    private val floatBoolReturnMethods: Set<String> =
        floatStdlibBoolPredicates.keys + floatCoreBoolPredicates.keys

    fun isFloatKind(kind: NumericKind): Boolean =
        kind == NumericKind.F16 || kind == NumericKind.F32 || kind == NumericKind.F64 || kind == NumericKind.F128

    fun isUnsignedKind(kind: NumericKind): Boolean =
        kind == NumericKind.U8 || kind == NumericKind.U16 || kind == NumericKind.U32 || kind == NumericKind.U64 || kind == NumericKind.U128

    fun floatSubpackage(kind: NumericKind): String =
        when (kind) {
            NumericKind.F16 -> "f16"
            NumericKind.F128 -> "f128"
            NumericKind.F32 -> "f32"
            else -> "f64"
        }

    fun expectedExtraArgKind(methodName: String, defaultKind: NumericKind): NumericKind =
        when {
            methodName in integerShiftOrPowMethods -> NumericKind.U32
            methodName == "powi" -> NumericKind.I32
            else -> defaultKind
        }

    fun inferReturnRenderedType(
        emitter: KotlinJ2KEmitter,
        recvKind: NumericKind,
        rawMethod: String,
        turbofishType: SynType?,
        selfTypeName: String?,
    ): String? {
        if ("core" !in emitter.headerContext.crateNamespaces) {
            return if (recvKind == NumericKind.I32 && rawMethod in standaloneCheckedI32Ops && turbofishType == null) {
                "Int?"
            } else {
                null
            }
        }
        val primTy = emitter.numericKindToPureTypeName(recvKind)
        if (isFloatKind(recvKind)) {
            return when {
                rawMethod in floatBoolReturnMethods -> "Boolean"
                rawMethod == "classify" -> "FpCategory"
                rawMethod == "to_int_unchecked" -> turbofishType?.let { emitter.renderType(it, selfTypeName) }
                rawMethod in floatSameTypeReturnMethods -> primTy
                else -> null
            }
        }
        if (rawMethod !in allIntegerOpNames) return null
        return when {
            rawMethod.startsWith("checked_") -> "$primTy?"
            rawMethod.startsWith("overflowing_") -> "Pair<$primTy, Boolean>"
            rawMethod in integerBitCountMethods -> "UInt"
            else -> primTy
        }
    }

    fun extractPrimitiveOwnerRawName(
        path: Path,
        qself: QSelf?,
        extraAllowedPrefixes: Set<String> = emptySet(),
        requirePlainQSelfArgs: Boolean = false,
    ): String? {
        val segs = path.segments.toList()
        if (segs.isEmpty()) return null
        val allowedPrefixes = IGNORED_PATH_PREFIXES + setOf("core", "std", "primitive") + extraAllowedPrefixes
        if (qself != null) {
            if (segs.size != 1) return null
            val qPath = unwrapSynType(qself.ty) as? SynType.Path ?: return null
            val qSegs = qPath.path.segments.toList()
            if (qSegs.isEmpty()) return null
            if (requirePlainQSelfArgs && qSegs.any { it.arguments !is PathArguments.None }) return null
            if (!qSegs.dropLast(1).all { it.ident.toString().removePrefix("r#") in allowedPrefixes }) return null
            val lastQIdent = qSegs.last().ident.toString()
            return lastQIdent.removePrefix("r#")
        }
        if (segs.size < 2) return null
        if (!segs.dropLast(2).all { it.ident.toString().removePrefix("r#") in allowedPrefixes }) return null
        return segs[segs.size - 2].ident.toString().removePrefix("r#")
    }
}

/**
 * Lowers concrete integer and floating-point (`F16`, `F32`, `F64`, `F128`) primitive operations
 * to Kotlin stdlib, `rust-lang-kotlin` `core.num` / `core.intrinsics`, and `libm` calls,
 * guarding against self-recursion when translating `core` or `libm` itself.
 */
private class ConcretePrimitiveOpEmitter(
    private val emitter: KotlinJ2KEmitter,
    private val coreNs: String,
    private val recvKind: NumericKind,
    private val recvAtom: String,
    private val renderedRecv: String,
    private val extraExprs: List<Expr>,
    private val renderedExtras: List<String>,
    private val turbofishType: SynType?,
    private val selfTypeName: String?,
) {
    fun render(rawMethod: String): String? =
        if (PrimitiveNumericOpCatalog.isFloatKind(recvKind)) {
            FloatPrimitiveOpContext().render(rawMethod)
        } else {
            renderIntegerOp(rawMethod)
        }

    private fun emitCorePackageMemberCall(pkg: String, ktMethod: String, argsList: String): String? {
        if (emitter.headerContext.packageName == pkg && emitter.currentEnclosingFunctionName == ktMethod) {
            return null
        }
        if (emitter.headerContext.packageName != pkg) {
            CoreIntrinsicLowering.registerCoreImport(emitter, "$pkg.$ktMethod")
        }
        return "$recvAtom.$ktMethod($argsList)"
    }

    private fun renderIntegerOp(rawMethod: String): String? {
        if (turbofishType != null) return null
        if (PrimitiveNumericOpCatalog.isUnsignedKind(recvKind) && rawMethod in PrimitiveNumericOpCatalog.signedOnlyIntegerMethods) {
            return null
        }
        val numPkg = "$coreNs.num"
        if (extraExprs.isEmpty()) {
            val ktMethod = PrimitiveNumericOpCatalog.integerUnaryOpMethods[rawMethod] ?: return null
            return emitCorePackageMemberCall(numPkg, ktMethod, "")
        }
        if (extraExprs.size == 1 && renderedExtras.size == 1) {
            PrimitiveNumericOpCatalog.integerShiftOrPowMethods[rawMethod]?.let { shiftOrPowMethod ->
                val rhs = emitter.coerceNumericArgIfNeeded(extraExprs[0], renderedExtras[0], NumericKind.U32, selfTypeName)
                return emitCorePackageMemberCall(numPkg, shiftOrPowMethod, rhs)
            }
            PrimitiveNumericOpCatalog.integerBinaryOpMethods[rawMethod]?.let { binMethod ->
                val rhs = emitter.coerceNumericArgIfNeeded(extraExprs[0], renderedExtras[0], recvKind, selfTypeName)
                return emitCorePackageMemberCall(numPkg, binMethod, rhs)
            }
        }
        return null
    }

    private inner class FloatPrimitiveOpContext {
        private val isWrapperFloat = recvKind == NumericKind.F16 || recvKind == NumericKind.F128
        private val useF32 = recvKind == NumericKind.F16 || recvKind == NumericKind.F32
        private val sfx = if (useF32) "f32" else "f64"
        private val unwrapSuffix =
            when (recvKind) {
                NumericKind.F16 -> ".toFloat()"
                NumericKind.F128 -> ".toDouble()"
                else -> ""
            }
        private val wrapPrefix =
            when (recvKind) {
                NumericKind.F16 -> "F16.fromFloat("
                NumericKind.F128 -> "F128.fromDouble("
                else -> ""
            }
        private val scalarExpr = if (unwrapSuffix.isEmpty()) renderedRecv else "$recvAtom$unwrapSuffix"
        private val scalarAtom = if (unwrapSuffix.isEmpty()) recvAtom else "$recvAtom$unwrapSuffix"
        private val intrinsicsPkg = "$coreNs.intrinsics"

        private fun wrapResult(expr: String): String = if (wrapPrefix.isEmpty()) expr else "$wrapPrefix$expr)"

        private fun scalarizeArg(argExpr: Expr, renderedArg: String): String {
            val coerced = emitter.coerceNumericArgIfNeeded(argExpr, renderedArg, recvKind, selfTypeName)
            if (unwrapSuffix.isEmpty()) return coerced
            val atom = if (emitter.isAtomicPostfixReceiver(argExpr, coerced)) coerced else "($coerced)"
            return "$atom$unwrapSuffix"
        }

        private fun registerIntrinsic(fn: String) {
            if (emitter.headerContext.packageName != intrinsicsPkg) {
                CoreIntrinsicLowering.registerCoreImport(emitter, "$intrinsicsPkg.$fn")
            }
        }

        private fun emitLibmCall(rawMethod: String, argsList: String): String? {
            val libmNs = emitter.resolveLibmNamespace(coreNs)
            val fn = if (useF32) "${rawMethod}f" else rawMethod
            val libmPkg = "$libmNs.math.$fn"
            if (emitter.headerContext.packageName == libmPkg && emitter.currentEnclosingFunctionName == fn) {
                return null
            }
            if (emitter.headerContext.packageName != libmPkg) {
                CoreIntrinsicLowering.registerCoreImport(emitter, "$libmPkg.$fn")
            }
            return wrapResult("$fn($argsList)")
        }

        fun render(rawMethod: String): String? {
            if (extraExprs.isEmpty() && rawMethod == "to_int_unchecked") {
                return renderToIntUnchecked()
            }
            if (turbofishType != null) return null
            if (extraExprs.isEmpty()) {
                return renderUnaryFloatOp(rawMethod)
            }
            if (extraExprs.size == 1 && renderedExtras.size == 1) {
                return renderBinaryFloatOp(rawMethod)
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

        private fun renderToIntUnchecked(): String? {
            val dstType =
                turbofishType?.let { emitter.renderType(it, selfTypeName) }
                    ?: emitter.currentExpectedParseTargetType
                    ?: emitter.currentEnclosingReturnRenderedType
            val dstKind = dstType?.let(emitter::resolveNumericKind) ?: return null
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
                    if (emitter.headerContext.packageName != coreNs) {
                        CoreIntrinsicLowering.registerCoreImport(emitter, "$coreNs.U128")
                    }
                    "$fn($scalarExpr).let { U128(it.hi, it.lo) }"
                }
                NumericKind.I128 -> {
                    val fn = if (useF32) "kmFloatToU128Unchecked" else "kmDoubleToU128Unchecked"
                    registerIntrinsic(fn)
                    if (emitter.headerContext.packageName != coreNs) {
                        CoreIntrinsicLowering.registerCoreImport(emitter, "$coreNs.I128")
                    }
                    val zeroLit = if (useF32) "0.0f" else "0.0"
                    "($scalarExpr).let { v -> if (v < $zeroLit) $fn(-v).let { -I128(it.hi.toLong(), it.lo) } else $fn(v).let { I128(it.hi.toLong(), it.lo) } }"
                }
                else -> null
            }
        }

        private fun renderUnaryFloatOp(rawMethod: String): String? {
            PrimitiveNumericOpCatalog.floatLibraryMethods[rawMethod]?.let { ktMethod ->
                val fPkg = "$coreNs.num.${PrimitiveNumericOpCatalog.floatSubpackage(recvKind)}"
                return emitCorePackageMemberCall(fPkg, ktMethod, "")
            }
            PrimitiveNumericOpCatalog.floatKmUnaryBases[rawMethod]?.let { kmBase ->
                val kmFn = "$kmBase$sfx"
                registerIntrinsic(kmFn)
                return wrapResult("$kmFn($scalarExpr)")
            }
            if (rawMethod in PrimitiveNumericOpCatalog.floatLibmUnaryOps) {
                return emitLibmCall(rawMethod, scalarExpr)
            }
            PrimitiveNumericOpCatalog.floatStdlibBoolPredicates[rawMethod]?.let { stdPred ->
                return "$recvAtom.$stdPred()"
            }
            return when (rawMethod) {
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

        private fun renderBinaryFloatOp(rawMethod: String): String? {
            PrimitiveNumericOpCatalog.floatKmBinaryBases[rawMethod]?.let { kmBase ->
                val kmFn = "$kmBase$sfx"
                registerIntrinsic(kmFn)
                val arg0Scalar = scalarizeArg(extraExprs[0], renderedExtras[0])
                return wrapResult("$kmFn($scalarExpr, $arg0Scalar)")
            }
            if (rawMethod in PrimitiveNumericOpCatalog.floatLibmBinaryOps) {
                val arg0Scalar = scalarizeArg(extraExprs[0], renderedExtras[0])
                return emitLibmCall(rawMethod, "$scalarExpr, $arg0Scalar")
            }
            return when (rawMethod) {
                "powi" -> {
                    val intArg = emitter.coerceNumericArgIfNeeded(extraExprs[0], renderedExtras[0], NumericKind.I32, selfTypeName)
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
                else -> null
            }
        }
    }
}

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
    val hasReprTransparent =
        struct.attrs.any { attr ->
            val meta = attr.meta as? Meta.List ?: return@any false
            if (!meta.path.isIdent("repr")) return@any false
            val cursor = RustTokenCursor(meta.tokens.clone().toList())
            (0 until cursor.size).any { cursor.identAt(it) == "transparent" }
        }
    if (!hasReprTransparent) return null
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

internal fun KotlinJ2KEmitter.lookupInScopeTypeLayoutLocal(
    pointeeKt: String,
    memPkg: String,
    allowUnqualified: Boolean = false,
): String? {
    val expectedLayoutTypes =
        if (allowUnqualified) {
            setOf("TypeLayout<$pointeeKt>", "$memPkg.TypeLayout<$pointeeKt>")
        } else {
            setOf("$memPkg.TypeLayout<$pointeeKt>")
        }
    currentTypedLocals.entries.singleOrNull { it.value in expectedLayoutTypes }?.let { return it.key }
    val candidateParam = StructLayoutComputer.typeLayoutParamName(pointeeKt)
    return candidateParam.takeIf { it in currentTypedLocals }
}

internal fun KotlinJ2KEmitter.resolveStructOrUnresolvedTypeLayoutExpr(
    pointeeKt: String,
    memPkg: String,
): String {
    val structSimple = pointeeKt.substringBefore('<').substringAfterLast('.')
    val structDecl = sameFileUniqueSimpleStruct(structSimple)
    if (structDecl != null) {
        val computed = StructLayoutComputer.computeStructLayout(structDecl, this)
        if (computed != null && computed.requiredTypeLayoutParams.isEmpty()) {
            return "$structSimple.typeLayout()"
        }
    }
    CoreIntrinsicLowering.registerCoreImport(this, "$memPkg.UnresolvedTypeLayout")
    return "UnresolvedTypeLayout<$pointeeKt>()"
}

internal fun KotlinJ2KEmitter.resolveRenderedTypeLayoutExpr(targetKtRaw: String): String {
    val pointeeKt = targetKtRaw.trim().ifEmpty { "UByte" }
    val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
    val memPkg = "$coreNs.mem"
    lookupInScopeTypeLayoutLocal(pointeeKt, memPkg, allowUnqualified = true)?.let { return it }
    PRIMITIVE_LAYOUT_NAME_BY_KT_TYPE[pointeeKt]?.let { layoutConst ->
        CoreIntrinsicLowering.registerCoreImport(this, "$memPkg.PrimitiveLayouts")
        return "PrimitiveLayouts.$layoutConst"
    }
    rawPointerPointeeType(pointeeKt)?.let { innerPointee ->
        CoreIntrinsicLowering.registerCoreImport(this, "$memPkg.PrimitiveLayouts")
        val factory = if (isMutRawPointerRenderedType(pointeeKt)) "mutPtrLayout" else "constPtrLayout"
        return "PrimitiveLayouts.$factory<$innerPointee>(${activeCfgProfile.pointerWidth}L)"
    }
    return resolveStructOrUnresolvedTypeLayoutExpr(pointeeKt, memPkg)
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
        val elemSegs = elemPath.path.segments.toList()
        val elemLastIdent = elemSegs.lastOrNull()?.ident?.toString()
        val elemRaw = elemLastIdent?.removePrefix("r#")
        if ((elemRaw == "u8" || elemRaw == "c_uchar") && "core" in headerContext.crateNamespaces) {
            val srcKind = inferExprNumericKind(srcInner, selfTypeName)
            if (srcKind != null) {
                val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
                val subPkg =
                    if (PrimitiveNumericOpCatalog.isFloatKind(srcKind)) {
                        ".num.${PrimitiveNumericOpCatalog.floatSubpackage(srcKind)}"
                    } else {
                        ".num"
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
        srcKind == NumericKind.F128 && dstKind == NumericKind.U128 -> "U128($atom.high, $atom.low)"
        srcKind == NumericKind.F128 && dstKind == NumericKind.I128 -> "I128($atom.high.toLong(), $atom.low)"
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
    if ((primKind != NumericKind.U128 && primKind != NumericKind.I128) || "core" !in headerContext.crateNamespaces) {
        return null
    }
    val ktMethod = PrimitiveNumericOpCatalog.endian128Methods[rawMethod] ?: return null
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
    val rawSegs = path.segments.toList()
    val segs = rawSegs.map { it.ident.toString().removePrefix("r#") }
    if (segs.isEmpty()) return null
    val allowedPrefixes = IGNORED_PATH_PREFIXES + setOf("core", "std", "primitive")
    val primKind =
        if (qself == null && segs.size >= 3 && segs[segs.size - 2] == "consts" && segs.dropLast(3).all { it in allowedPrefixes }) {
            rustPrimitivePathNameToKind(segs[segs.size - 3])?.takeIf(PrimitiveNumericOpCatalog::isFloatKind)
        } else {
            PrimitiveNumericOpCatalog.extractPrimitiveOwnerRawName(path, qself)?.let(::rustPrimitivePathNameToKind)
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
    val ownerRaw = PrimitiveNumericOpCatalog.extractPrimitiveOwnerRawName(fnPath.path, fnPath.qself) ?: return null
    val primKind = rustPrimitivePathNameToKind(ownerRaw) ?: return null
    val angleArgs = (segs.last().arguments as? PathArguments.AngleBracketed)?.args?.toList()
    val turbofishType = angleArgs?.firstNotNullOfOrNull { (it as? GenericArgument.TypeArg)?.type }
    val lastIdent = segs.last().ident.toString()
    val methodName = lastIdent.removePrefix("r#")
    return StaticPrimitiveCallSpec(primKind = primKind, methodName = methodName, turbofishType = turbofishType)
}

internal fun KotlinJ2KEmitter.inferPrimitiveOpReturnRenderedType(
    recvKind: NumericKind,
    rawMethod: String,
    turbofishType: SynType?,
    selfTypeName: String?,
): String? =
    PrimitiveNumericOpCatalog.inferReturnRenderedType(this, recvKind, rawMethod, turbofishType, selfTypeName)

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
            NumericKind.F128 ->
                return renderFloatFromBitsArg(nonUnitArgs[0], NumericKind.U128, NumericKind.U128, "F128", indent, selfTypeName)
            else -> Unit
        }
    }
    if (spec.methodName == "from_str_radix" &&
        spec.turbofishType == null &&
        nonUnitArgs.size == 2 &&
        "core" in headerContext.crateNamespaces
    ) {
        if (PrimitiveNumericOpCatalog.isFloatKind(spec.primKind)) return null
        val ktType = numericKindToPureTypeName(spec.primKind)
        if (headerContext.packageName == "$coreNs.num" && currentEnclosingFunctionName == "fromStrRadix") {
            return null
        }
        if (headerContext.packageName != "$coreNs.num") {
            CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.num.fromStrRadix")
        }
        if ((spec.primKind == NumericKind.I128 || spec.primKind == NumericKind.U128) && headerContext.packageName != coreNs) {
            CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.$ktType")
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
    val extraExpectedKind = PrimitiveNumericOpCatalog.expectedExtraArgKind(spec.methodName, spec.primKind)
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
    if (spec.turbofishType == null &&
        extraExprs.isEmpty() &&
        spec.methodName in PrimitiveNumericOpCatalog.endian128Methods &&
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
    val tfArgs = expr.turbofish?.args?.toList()
    val tfType = tfArgs?.firstNotNullOfOrNull { (it as? GenericArgument.TypeArg)?.type }
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
    if ("core" !in headerContext.crateNamespaces) {
        val opSym = PrimitiveNumericOpCatalog.standaloneCheckedI32Ops[rawMethod]
        if (recvKind == NumericKind.I32 && opSym != null && extraExprs.size == 1 && renderedExtras.size == 1 && turbofishType == null) {
            return "(($renderedRecv).toLong() $opSym (${renderedExtras[0]}).toLong()).takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()"
        }
        return null
    }
    val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
    val recvAtom = if (isAtomicPostfixReceiver(recvExpr, renderedRecv)) renderedRecv else "($renderedRecv)"
    return ConcretePrimitiveOpEmitter(
        emitter = this,
        coreNs = coreNs,
        recvKind = recvKind,
        recvAtom = recvAtom,
        renderedRecv = renderedRecv,
        extraExprs = extraExprs,
        renderedExtras = renderedExtras,
        turbofishType = turbofishType,
        selfTypeName = selfTypeName,
    ).render(rawMethod)
}

internal fun KotlinJ2KEmitter.resolveLibmNamespace(coreNs: String): String {
    val pkg = headerContext.packageName?.trim().orEmpty()
    val defaultLibm = "io.github.kotlinmania.rustlang.libm"
    if (pkg == defaultLibm || pkg.startsWith("$defaultLibm.")) {
        return defaultLibm
    }
    val rawLibm = headerContext.crateNamespaces["libm"]?.trim()?.removeSuffix(".")
    if (!rawLibm.isNullOrEmpty()) return rawLibm
    return if (coreNs.endsWith(".core")) "${coreNs.removeSuffix(".core")}.libm" else defaultLibm
}
