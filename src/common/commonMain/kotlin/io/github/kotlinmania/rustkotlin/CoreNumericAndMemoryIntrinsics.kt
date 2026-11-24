package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.syn.Expr
import io.github.kotlinmania.syn.PointerMutability
import io.github.kotlinmania.syn.SynType

private fun numericKindWidthTag(kind: NumericKind): String? =
    when (kind) {
        NumericKind.I8 -> "I8"
        NumericKind.U8 -> "U8"
        NumericKind.I16 -> "I16"
        NumericKind.U16 -> "U16"
        NumericKind.I32 -> "I32"
        NumericKind.U32 -> "U32"
        NumericKind.I64 -> "I64"
        NumericKind.U64 -> "U64"
        NumericKind.I128 -> "I128"
        NumericKind.U128 -> "U128"
        else -> null
    }

private fun numericKindUnsignedBitTag(kind: NumericKind): String? =
    when (kind) {
        NumericKind.I8, NumericKind.U8 -> "U8"
        NumericKind.I16, NumericKind.U16 -> "U16"
        NumericKind.I32, NumericKind.U32 -> "U32"
        NumericKind.I64, NumericKind.U64 -> "U64"
        NumericKind.I128, NumericKind.U128 -> "U128"
        else -> null
    }

private fun toExactWidthUnsignedExpr(expr: String, kind: NumericKind): String =
    when (kind) {
        NumericKind.I8 -> "($expr).toUByte()"
        NumericKind.I16 -> "($expr).toUShort()"
        NumericKind.I32 -> "($expr).toUInt()"
        NumericKind.I64 -> "($expr).toULong()"
        else -> expr
    }

private fun fromExactWidthUnsignedExpr(expr: String, kind: NumericKind): String =
    when (kind) {
        NumericKind.I8 -> "($expr).toByte()"
        NumericKind.I16 -> "($expr).toShort()"
        NumericKind.I32 -> "($expr).toInt()"
        NumericKind.I64 -> "($expr).toLong()"
        else -> expr
    }

private fun reconstruct128Expr(
    kind: NumericKind,
    loField: String,
    hiField: String,
    coreNs: String,
    emitter: KotlinJ2KEmitter,
): String =
    if (kind == NumericKind.I128) {
        CoreIntrinsicLowering.registerCoreImport(emitter, "$coreNs.I128")
        "I128($hiField.toLong(), $loField)"
    } else {
        CoreIntrinsicLowering.registerCoreImport(emitter, "$coreNs.U128")
        "U128($hiField, $loField)"
    }

private fun layoutExprForNumericKind(
    kind: NumericKind,
    coreNs: String,
    memPkg: String,
    emitter: KotlinJ2KEmitter,
): String {
    CoreIntrinsicLowering.registerCoreImport(emitter, "$memPkg.PrimitiveLayouts")
    return when (kind) {
        NumericKind.I8 -> "PrimitiveLayouts.I8"
        NumericKind.U8 -> "PrimitiveLayouts.U8"
        NumericKind.I16 -> "PrimitiveLayouts.I16"
        NumericKind.U16 -> "PrimitiveLayouts.U16"
        NumericKind.I32 -> "PrimitiveLayouts.I32"
        NumericKind.U32 -> "PrimitiveLayouts.U32"
        NumericKind.I64 -> "PrimitiveLayouts.I64"
        NumericKind.U64 -> "PrimitiveLayouts.U64"
        NumericKind.I128 -> {
            CoreIntrinsicLowering.registerCoreImport(emitter, "$coreNs.I128")
            "PrimitiveLayouts.i128Layout(::I128, { it.high }, { it.low })"
        }
        NumericKind.U128 -> {
            CoreIntrinsicLowering.registerCoreImport(emitter, "$coreNs.U128")
            "PrimitiveLayouts.u128Layout(::U128, { it.high }, { it.low })"
        }
        NumericKind.F16 -> "PrimitiveLayouts.F16_LAYOUT"
        NumericKind.F32 -> "PrimitiveLayouts.F32"
        NumericKind.F64 -> "PrimitiveLayouts.F64"
        NumericKind.F128 -> "PrimitiveLayouts.F128_LAYOUT"
    }
}

internal data class IntIntrinsicCallContext(
    val canonicalName: String,
    val renderedArgs: List<String>,
    val callArgsList: List<Expr>,
    val explicitTypes: List<SynType>,
    val kind: NumericKind?,
    val firstTargetTy: SynType?,
    val selfTypeName: String?,
    val coreNs: String,
    val intrPkg: String,
    val memPkg: String,
    val emitter: KotlinJ2KEmitter,
) {
    fun fallbackWithLayout(fnName: String, argsPart: String): String? {
        val ty = firstTargetTy ?: return null
        val layout = CoreIntrinsicLowering.resolveTypeLayoutExpression(ty, selfTypeName, emitter)
        CoreIntrinsicLowering.registerCoreImport(emitter, "$memPkg.$fnName")
        return "$fnName($argsPart, $layout)"
    }

    fun binary128Call(
        kmFn: String,
        lhsExpr: String,
        rhsExpr: String,
        wrapResult: (String) -> String = { it },
    ): String {
        CoreIntrinsicLowering.registerCoreImport(emitter, "$intrPkg.$kmFn")
        val hiL = if (kind == NumericKind.I128) "lhs.high.toULong()" else "lhs.high"
        val hiR = if (kind == NumericKind.I128) "rhs.high.toULong()" else "rhs.high"
        val built = reconstruct128Expr(kind ?: NumericKind.U128, "it.lo", "it.hi", coreNs, emitter)
        return "run { val lhs = $lhsExpr; val rhs = $rhsExpr; $kmFn(lhs.low, $hiL, rhs.low, $hiR).let { ${wrapResult(built)} } }"
    }
}

internal abstract class IntIntrinsicLoweringRule(
    val names: Set<String>,
    val withLayoutFn: String,
    val symmetricArgCount: Int = 2,
) {
    constructor(
        name: String,
        withLayoutFn: String,
        symmetricArgCount: Int = 2,
    ) : this(setOf(name), withLayoutFn, symmetricArgCount)

    open fun renderSynthesizedBody(
        p0: String,
        p1: String,
        typeParamCount: Int,
        layout0: String,
        layout1: String,
        memPkg: String,
        emitter: KotlinJ2KEmitter,
    ): String {
        CoreIntrinsicLowering.registerCoreImport(emitter, "$memPkg.$withLayoutFn")
        return "$withLayoutFn($p0, $p1, $layout0)"
    }

    abstract fun renderCall(ctx: IntIntrinsicCallContext): String?
}

private class UnaryBitCountIntrinsicRule(
    names: Set<String>,
    private val kmStem: String,
    withLayoutFn: String,
) : IntIntrinsicLoweringRule(names, withLayoutFn, symmetricArgCount = 1) {
    override fun renderSynthesizedBody(
        p0: String,
        p1: String,
        typeParamCount: Int,
        layout0: String,
        layout1: String,
        memPkg: String,
        emitter: KotlinJ2KEmitter,
    ): String {
        CoreIntrinsicLowering.registerCoreImport(emitter, "$memPkg.$withLayoutFn")
        return "$withLayoutFn($p0, $layout0)"
    }

    override fun renderCall(ctx: IntIntrinsicCallContext): String? {
        val arg0 = ctx.renderedArgs.firstOrNull() ?: return null
        val kind = ctx.kind
        val uTag = kind?.let(::numericKindUnsignedBitTag)
        return when {
            uTag == "U128" -> {
                val kmFn = "${kmStem}U128"
                CoreIntrinsicLowering.registerCoreImport(ctx.emitter, "${ctx.intrPkg}.$kmFn")
                val hiExpr = if (kind == NumericKind.I128) "v.high.toULong()" else "v.high"
                "($arg0).let { v -> $kmFn(v.low, $hiExpr) }"
            }
            uTag != null -> {
                val kmFn = "$kmStem$uTag"
                CoreIntrinsicLowering.registerCoreImport(ctx.emitter, "${ctx.intrPkg}.$kmFn")
                "$kmFn(${toExactWidthUnsignedExpr(arg0, kind)})"
            }
            else -> ctx.fallbackWithLayout(withLayoutFn, arg0)
        }
    }
}

private class UnaryBitTransformIntrinsicRule(
    name: String,
    private val kmStem: String,
    withLayoutFn: String,
    private val identityOnByte: Boolean,
) : IntIntrinsicLoweringRule(name, withLayoutFn, symmetricArgCount = 1) {
    override fun renderSynthesizedBody(
        p0: String,
        p1: String,
        typeParamCount: Int,
        layout0: String,
        layout1: String,
        memPkg: String,
        emitter: KotlinJ2KEmitter,
    ): String {
        CoreIntrinsicLowering.registerCoreImport(emitter, "$memPkg.$withLayoutFn")
        return "$withLayoutFn($p0, $layout0)"
    }

    override fun renderCall(ctx: IntIntrinsicCallContext): String? {
        val arg0 = ctx.renderedArgs.firstOrNull() ?: return null
        val kind = ctx.kind
        if (identityOnByte && (kind == NumericKind.I8 || kind == NumericKind.U8)) return arg0
        val uTag = kind?.let(::numericKindUnsignedBitTag)
        return when {
            uTag == "U128" -> {
                val kmFn = "${kmStem}U128"
                CoreIntrinsicLowering.registerCoreImport(ctx.emitter, "${ctx.intrPkg}.$kmFn")
                val hiExpr = if (kind == NumericKind.I128) "v.high.toULong()" else "v.high"
                val built = reconstruct128Expr(kind, "r.lo", "r.hi", ctx.coreNs, ctx.emitter)
                "($arg0).let { v -> $kmFn(v.low, $hiExpr).let { r -> $built } }"
            }
            uTag != null -> {
                val kmFn = "$kmStem$uTag"
                CoreIntrinsicLowering.registerCoreImport(ctx.emitter, "${ctx.intrPkg}.$kmFn")
                fromExactWidthUnsignedExpr("$kmFn(${toExactWidthUnsignedExpr(arg0, kind)})", kind)
            }
            else -> ctx.fallbackWithLayout(withLayoutFn, arg0)
        }
    }
}

private class RotateIntrinsicRule(
    name: String,
    private val kmStem: String,
    withLayoutFn: String,
) : IntIntrinsicLoweringRule(name, withLayoutFn, symmetricArgCount = 1) {
    override fun renderSynthesizedBody(
        p0: String,
        p1: String,
        typeParamCount: Int,
        layout0: String,
        layout1: String,
        memPkg: String,
        emitter: KotlinJ2KEmitter,
    ): String {
        CoreIntrinsicLowering.registerCoreImport(emitter, "$memPkg.$withLayoutFn")
        return "$withLayoutFn($p0, ($p1).toUInt(), $layout0)"
    }

    override fun renderCall(ctx: IntIntrinsicCallContext): String? {
        if (ctx.renderedArgs.size < 2) return null
        val arg0 = ctx.renderedArgs[0]
        val shift = "(${ctx.renderedArgs[1]}).toUInt()"
        val kind = ctx.kind
        val uTag = kind?.let(::numericKindUnsignedBitTag)
        return when {
            uTag == "U128" -> {
                val kmFn = "${kmStem}U128"
                CoreIntrinsicLowering.registerCoreImport(ctx.emitter, "${ctx.intrPkg}.$kmFn")
                val hiExpr = if (kind == NumericKind.I128) "v.high.toULong()" else "v.high"
                val built = reconstruct128Expr(kind, "r.lo", "r.hi", ctx.coreNs, ctx.emitter)
                "($arg0).let { v -> $kmFn(v.low, $hiExpr, $shift).let { r -> $built } }"
            }
            uTag != null -> {
                val kmFn = "$kmStem$uTag"
                CoreIntrinsicLowering.registerCoreImport(ctx.emitter, "${ctx.intrPkg}.$kmFn")
                fromExactWidthUnsignedExpr("$kmFn(${toExactWidthUnsignedExpr(arg0, kind)}, $shift)", kind)
            }
            else -> ctx.fallbackWithLayout(withLayoutFn, "$arg0, $shift")
        }
    }
}

private class WrappingArithmeticIntrinsicRule(
    names: Set<String>,
    private val opSymbol: String,
    private val u128Fn: String,
    withLayoutFn: String,
) : IntIntrinsicLoweringRule(names, withLayoutFn) {
    override fun renderCall(ctx: IntIntrinsicCallContext): String? {
        if (ctx.renderedArgs.size < 2) return null
        val a = ctx.renderedArgs[0]
        val b = ctx.renderedArgs[1]
        return when (ctx.kind) {
            NumericKind.I8 -> "((($a) $opSymbol ($b))).toByte()"
            NumericKind.U8 -> "((($a) $opSymbol ($b))).toUByte()"
            NumericKind.I16 -> "((($a) $opSymbol ($b))).toShort()"
            NumericKind.U16 -> "((($a) $opSymbol ($b))).toUShort()"
            NumericKind.I32, NumericKind.U32, NumericKind.I64, NumericKind.U64 -> "(($a) $opSymbol ($b))"
            NumericKind.I128, NumericKind.U128 -> ctx.binary128Call(u128Fn, a, b)
            else -> ctx.fallbackWithLayout(withLayoutFn, "$a, $b")
        }
    }
}

private class WidthTaggedBinaryIntrinsicRule(
    name: String,
    private val kmPrefix: String,
    withLayoutFn: String,
    private val wrapPairOverflow: Boolean,
) : IntIntrinsicLoweringRule(name, withLayoutFn) {
    override fun renderCall(ctx: IntIntrinsicCallContext): String? {
        if (ctx.renderedArgs.size < 2) return null
        val a = ctx.renderedArgs[0]
        val b = ctx.renderedArgs[1]
        val kind = ctx.kind
        val wTag = kind?.let(::numericKindWidthTag)
        return when {
            (kind == NumericKind.I128 || kind == NumericKind.U128) && wTag != null ->
                ctx.binary128Call("$kmPrefix$wTag", a, b) { built ->
                    if (wrapPairOverflow) "Pair($built, it.overflowed)" else built
                }
            wTag != null -> {
                val kmFn = "$kmPrefix$wTag"
                CoreIntrinsicLowering.registerCoreImport(ctx.emitter, "${ctx.intrPkg}.$kmFn")
                if (wrapPairOverflow) "$kmFn($a, $b).let { Pair(it.value, it.overflowed) }" else "$kmFn($a, $b)"
            }
            else -> ctx.fallbackWithLayout(withLayoutFn, "$a, $b")
        }
    }
}

private class DivisionIntrinsicRule(
    name: String,
    private val opSymbol: String,
    withLayoutFn: String,
) : IntIntrinsicLoweringRule(name, withLayoutFn) {
    override fun renderCall(ctx: IntIntrinsicCallContext): String? {
        if (ctx.renderedArgs.size < 2) return null
        val a = ctx.renderedArgs[0]
        val b = ctx.renderedArgs[1]
        return when (ctx.kind) {
            NumericKind.I8 -> "((($a) $opSymbol ($b))).toByte()"
            NumericKind.U8 -> "((($a) $opSymbol ($b))).toUByte()"
            NumericKind.I16 -> "((($a) $opSymbol ($b))).toShort()"
            NumericKind.U16 -> "((($a) $opSymbol ($b))).toUShort()"
            NumericKind.I32, NumericKind.U32, NumericKind.I64, NumericKind.U64, NumericKind.I128, NumericKind.U128 ->
                "(($a) $opSymbol ($b))"
            else -> ctx.fallbackWithLayout(withLayoutFn, "$a, $b")
        }
    }
}

private class UncheckedShiftIntrinsicRule(
    name: String,
    private val isLeft: Boolean,
    withLayoutFn: String,
) : IntIntrinsicLoweringRule(name, withLayoutFn, symmetricArgCount = 1) {
    override fun renderSynthesizedBody(
        p0: String,
        p1: String,
        typeParamCount: Int,
        layout0: String,
        layout1: String,
        memPkg: String,
        emitter: KotlinJ2KEmitter,
    ): String {
        CoreIntrinsicLowering.registerCoreImport(emitter, "$memPkg.$withLayoutFn")
        return if (typeParamCount >= 2) {
            "$withLayoutFn($p0, $p1, $layout0, $layout1)"
        } else {
            "$withLayoutFn($p0, ($p1).toUInt(), $layout0)"
        }
    }

    override fun renderCall(ctx: IntIntrinsicCallContext): String? {
        if (ctx.renderedArgs.size < 2) return null
        val x = ctx.renderedArgs[0]
        val shift = ctx.renderedArgs[1]
        val op = if (isLeft) "shl" else "shr"
        return when (ctx.kind) {
            NumericKind.I8 -> "((($x).toInt() $op ($shift).toInt()).toByte())"
            NumericKind.U8 -> "(((($x).toUInt() and 0xFFu) $op ($shift).toInt()).toUByte())"
            NumericKind.I16 -> "((($x).toInt() $op ($shift).toInt()).toShort())"
            NumericKind.U16 -> "(((($x).toUInt() and 0xFFFFu) $op ($shift).toInt()).toUShort())"
            NumericKind.I32, NumericKind.U32, NumericKind.I64, NumericKind.U64 -> "(($x) $op ($shift).toInt())"
            NumericKind.U128 -> {
                val kmFn = if (isLeft) "kmU128Shl" else "kmU128Shr"
                CoreIntrinsicLowering.registerCoreImport(ctx.emitter, "${ctx.intrPkg}.$kmFn")
                CoreIntrinsicLowering.registerCoreImport(ctx.emitter, "${ctx.coreNs}.U128")
                "($x).let { v -> $kmFn(v.low, v.high, ($shift).toUInt()).let { r -> U128(r.hi, r.lo) } }"
            }
            NumericKind.I128 -> {
                val kmFn = if (isLeft) "kmU128Shl" else "kmI128Shr"
                CoreIntrinsicLowering.registerCoreImport(ctx.emitter, "${ctx.intrPkg}.$kmFn")
                CoreIntrinsicLowering.registerCoreImport(ctx.emitter, "${ctx.coreNs}.I128")
                "($x).let { v -> $kmFn(v.low, v.high.toULong(), ($shift).toUInt()).let { r -> I128(r.hi.toLong(), r.lo) } }"
            }
            else -> {
                val ty0 = ctx.firstTargetTy ?: return null
                val layout0 = CoreIntrinsicLowering.resolveTypeLayoutExpression(ty0, ctx.selfTypeName, ctx.emitter)
                CoreIntrinsicLowering.registerCoreImport(ctx.emitter, "${ctx.memPkg}.$withLayoutFn")
                val shiftTy =
                    ctx.explicitTypes.getOrNull(1)?.takeUnless { it is SynType.Infer }
                        ?: ctx.callArgsList.getOrNull(1)?.let { ctx.emitter.sourceInitializerType(it) }
                if (shiftTy != null && ctx.explicitTypes.size >= 2) {
                    val layout1 = CoreIntrinsicLowering.resolveTypeLayoutExpression(shiftTy, ctx.selfTypeName, ctx.emitter)
                    "$withLayoutFn($x, $shift, $layout0, $layout1)"
                } else {
                    "$withLayoutFn($x, ($shift).toUInt(), $layout0)"
                }
            }
        }
    }
}

private object DisjointBitorIntrinsicRule : IntIntrinsicLoweringRule("disjoint_bitor", "disjointBitorWithLayout") {
    override fun renderCall(ctx: IntIntrinsicCallContext): String? {
        if (ctx.renderedArgs.size < 2) return null
        val a = ctx.renderedArgs[0]
        val b = ctx.renderedArgs[1]
        return if (ctx.kind != null && numericKindWidthTag(ctx.kind) != null) {
            "(($a) or ($b))"
        } else {
            ctx.fallbackWithLayout(withLayoutFn, "$a, $b")
        }
    }
}

private object ThreeWayCompareIntrinsicRule : IntIntrinsicLoweringRule("three_way_compare", "threeWayCompareWithLayout") {
    override fun renderCall(ctx: IntIntrinsicCallContext): String? {
        if (ctx.renderedArgs.size < 2) return null
        val a = ctx.renderedArgs[0]
        val b = ctx.renderedArgs[1]
        return if (ctx.kind != null && numericKindWidthTag(ctx.kind) != null) {
            CoreIntrinsicLowering.registerCoreImport(ctx.emitter, "${ctx.coreNs}.cmp.Ordering")
            "run { val lhs = $a; val rhs = $b; when { lhs < rhs -> Ordering.Less; lhs > rhs -> Ordering.Greater; else -> Ordering.Equal } }"
        } else {
            ctx.fallbackWithLayout(withLayoutFn, "$a, $b")
        }
    }
}

private val INT_INTRINSIC_RULES: List<IntIntrinsicLoweringRule> =
    listOf(
        UnaryBitCountIntrinsicRule(setOf("ctpop"), "kmCtpop", "ctpopWithLayout"),
        UnaryBitCountIntrinsicRule(setOf("ctlz", "ctlz_nonzero"), "kmCtlz", "ctlzWithLayout"),
        UnaryBitCountIntrinsicRule(setOf("cttz", "cttz_nonzero"), "kmCttz", "cttzWithLayout"),
        UnaryBitTransformIntrinsicRule("bswap", "kmBswap", "bswapWithLayout", identityOnByte = true),
        UnaryBitTransformIntrinsicRule("bitreverse", "kmBitreverse", "bitreverseWithLayout", identityOnByte = false),
        RotateIntrinsicRule("rotate_left", "kmRotateLeft", "rotateLeftWithLayout"),
        RotateIntrinsicRule("rotate_right", "kmRotateRight", "rotateRightWithLayout"),
        WrappingArithmeticIntrinsicRule(setOf("wrapping_add", "unchecked_add"), "+", "kmU128Add", "wrappingAddWithLayout"),
        WrappingArithmeticIntrinsicRule(setOf("wrapping_sub", "unchecked_sub"), "-", "kmU128Sub", "wrappingSubWithLayout"),
        WrappingArithmeticIntrinsicRule(setOf("wrapping_mul", "unchecked_mul"), "*", "kmU128Mul", "wrappingMulWithLayout"),
        WidthTaggedBinaryIntrinsicRule("saturating_add", "kmSaturatingAdd", "saturatingAddWithLayout", wrapPairOverflow = false),
        WidthTaggedBinaryIntrinsicRule("saturating_sub", "kmSaturatingSub", "saturatingSubWithLayout", wrapPairOverflow = false),
        WidthTaggedBinaryIntrinsicRule("add_with_overflow", "kmAddWithOverflow", "addWithOverflowWithLayout", wrapPairOverflow = true),
        WidthTaggedBinaryIntrinsicRule("sub_with_overflow", "kmSubWithOverflow", "subWithOverflowWithLayout", wrapPairOverflow = true),
        WidthTaggedBinaryIntrinsicRule("mul_with_overflow", "kmMulWithOverflow", "mulWithOverflowWithLayout", wrapPairOverflow = true),
        DivisionIntrinsicRule("unchecked_div", "/", "uncheckedDivWithLayout"),
        DivisionIntrinsicRule("exact_div", "/", "exactDivWithLayout"),
        DivisionIntrinsicRule("unchecked_rem", "%", "uncheckedRemWithLayout"),
        UncheckedShiftIntrinsicRule("unchecked_shl", isLeft = true, "uncheckedShlWithLayout"),
        UncheckedShiftIntrinsicRule("unchecked_shr", isLeft = false, "uncheckedShrWithLayout"),
        DisjointBitorIntrinsicRule,
        ThreeWayCompareIntrinsicRule,
    )

internal val INT_INTRINSIC_RULES_BY_NAME: Map<String, IntIntrinsicLoweringRule> =
    buildMap {
        for (rule in INT_INTRINSIC_RULES) {
            for (name in rule.names) {
                put(name, rule)
            }
        }
    }

internal fun renderIntOrBitIntrinsicCall(
    canonicalName: String,
    renderedArgs: List<String>,
    callArgsList: List<Expr>,
    explicitTypes: List<SynType>,
    selfTypeName: String?,
    coreNs: String,
    intrPkg: String,
    memPkg: String,
    emitter: KotlinJ2KEmitter,
): String? {
    val rule = INT_INTRINSIC_RULES_BY_NAME[canonicalName] ?: return null
    val kind = CoreIntrinsicLowering.resolveIntrinsicNumericKind(explicitTypes.firstOrNull(), callArgsList.take(rule.symmetricArgCount), selfTypeName, emitter)
    val firstTargetTy =
        explicitTypes.firstOrNull()?.takeUnless { it is SynType.Infer }
            ?: callArgsList.firstOrNull()?.let { emitter.sourceInitializerType(it) }
    return rule.renderCall(
        IntIntrinsicCallContext(
            canonicalName = canonicalName,
            renderedArgs = renderedArgs,
            callArgsList = callArgsList,
            explicitTypes = explicitTypes,
            kind = kind,
            firstTargetTy = firstTargetTy,
            selfTypeName = selfTypeName,
            coreNs = coreNs,
            intrPkg = intrPkg,
            memPkg = memPkg,
            emitter = emitter,
        ),
    )
}


internal data class FloatIntrinsicCallContext(
    val canonicalName: String,
    val renderedArgs: List<String>,
    val callArgsList: List<Expr>,
    val explicitTypes: List<SynType>,
    val selfTypeName: String?,
    val coreNs: String,
    val intrPkg: String,
    val memPkg: String,
    val emitter: KotlinJ2KEmitter,
) {
    fun kindForArity(arity: Int): NumericKind? =
        CoreIntrinsicLowering.resolveIntrinsicNumericKind(
            explicitTypes.firstOrNull(),
            callArgsList.take(arity),
            selfTypeName,
            emitter,
        )

    fun floatLayoutExpr(kind: NumericKind?): String? {
        val targetTy =
            explicitTypes.firstOrNull()?.takeUnless { it is SynType.Infer }
                ?: callArgsList.firstOrNull()?.let { emitter.sourceInitializerType(it) }
        return when {
            kind != null && kind in setOf(NumericKind.F16, NumericKind.F32, NumericKind.F64, NumericKind.F128) ->
                layoutExprForNumericKind(kind, coreNs, memPkg, emitter)
            targetTy != null -> CoreIntrinsicLowering.resolveTypeLayoutExpression(targetTy, selfTypeName, emitter)
            else -> null
        }
    }

    fun dispatchF32F64OrFallback(
        rule: FloatIntrinsicLoweringRule,
        f32Km: String,
        f64Km: String,
        requiredArity: Int,
        kind: NumericKind?,
    ): String? {
        if (renderedArgs.size < requiredArity) return null
        val callArgsJoined = renderedArgs.take(requiredArity).joinToString(", ")
        return when (kind) {
            NumericKind.F32 -> {
                CoreIntrinsicLowering.registerCoreImport(emitter, "$intrPkg.$f32Km")
                "$f32Km($callArgsJoined)"
            }
            NumericKind.F64 -> {
                CoreIntrinsicLowering.registerCoreImport(emitter, "$intrPkg.$f64Km")
                "$f64Km($callArgsJoined)"
            }
            else -> {
                val layout = floatLayoutExpr(kind) ?: return null
                rule.renderSynthesizedBody(
                    canonicalName = canonicalName,
                    p0 = renderedArgs.getOrElse(0) { "" },
                    p1 = renderedArgs.getOrElse(1) { "" },
                    p2 = renderedArgs.getOrElse(2) { "" },
                    layout0 = layout,
                    layout1 = layout,
                    intrPkg = intrPkg,
                    memPkg = memPkg,
                    emitter = emitter,
                )
            }
        }
    }
}

internal abstract class FloatIntrinsicLoweringRule(
    val names: Set<String>,
) {
    abstract fun renderSynthesizedBody(
        canonicalName: String,
        p0: String,
        p1: String,
        p2: String,
        layout0: String,
        layout1: String,
        intrPkg: String,
        memPkg: String,
        emitter: KotlinJ2KEmitter,
    ): String?

    abstract fun renderCall(ctx: FloatIntrinsicCallContext): String?
}

private class FixedUnaryFloatIntrinsicRule :
    FloatIntrinsicLoweringRule(CoreIntrinsicLowering.SUFFIXED_FLOAT_UNARY_KM.keys) {
    override fun renderSynthesizedBody(
        canonicalName: String,
        p0: String,
        p1: String,
        p2: String,
        layout0: String,
        layout1: String,
        intrPkg: String,
        memPkg: String,
        emitter: KotlinJ2KEmitter,
    ): String? {
        val kmFn = CoreIntrinsicLowering.SUFFIXED_FLOAT_UNARY_KM[canonicalName] ?: return null
        CoreIntrinsicLowering.registerCoreImport(emitter, "$intrPkg.$kmFn")
        return "$kmFn($p0)"
    }

    override fun renderCall(ctx: FloatIntrinsicCallContext): String? {
        val arg0 = ctx.renderedArgs.firstOrNull() ?: return null
        return renderSynthesizedBody(ctx.canonicalName, arg0, "", "", "", "", ctx.intrPkg, ctx.memPkg, ctx.emitter)
    }
}

private class FixedBinaryFloatIntrinsicRule :
    FloatIntrinsicLoweringRule(CoreIntrinsicLowering.SUFFIXED_FLOAT_BINARY_KM.keys) {
    override fun renderSynthesizedBody(
        canonicalName: String,
        p0: String,
        p1: String,
        p2: String,
        layout0: String,
        layout1: String,
        intrPkg: String,
        memPkg: String,
        emitter: KotlinJ2KEmitter,
    ): String? {
        val kmFn = CoreIntrinsicLowering.SUFFIXED_FLOAT_BINARY_KM[canonicalName] ?: return null
        CoreIntrinsicLowering.registerCoreImport(emitter, "$intrPkg.$kmFn")
        return "$kmFn($p0, $p1)"
    }

    override fun renderCall(ctx: FloatIntrinsicCallContext): String? {
        if (ctx.renderedArgs.size < 2) return null
        return renderSynthesizedBody(
            ctx.canonicalName,
            ctx.renderedArgs[0],
            ctx.renderedArgs[1],
            "",
            "",
            "",
            ctx.intrPkg,
            ctx.memPkg,
            ctx.emitter,
        )
    }
}

private object FixedFmaFloatIntrinsicRule :
    FloatIntrinsicLoweringRule(setOf("fmaf32", "fmuladdf32", "fmaf64", "fmuladdf64")) {
    override fun renderSynthesizedBody(
        canonicalName: String,
        p0: String,
        p1: String,
        p2: String,
        layout0: String,
        layout1: String,
        intrPkg: String,
        memPkg: String,
        emitter: KotlinJ2KEmitter,
    ): String {
        val kmFn = if (canonicalName.endsWith("f32")) "kmFmaf32" else "kmFmaf64"
        CoreIntrinsicLowering.registerCoreImport(emitter, "$intrPkg.$kmFn")
        return "$kmFn($p0, $p1, $p2)"
    }

    override fun renderCall(ctx: FloatIntrinsicCallContext): String? {
        if (ctx.renderedArgs.size < 3) return null
        return renderSynthesizedBody(
            ctx.canonicalName,
            ctx.renderedArgs[0],
            ctx.renderedArgs[1],
            ctx.renderedArgs[2],
            "",
            "",
            ctx.intrPkg,
            ctx.memPkg,
            ctx.emitter,
        )
    }
}

private object FixedMinMaxFloatIntrinsicRule :
    FloatIntrinsicLoweringRule(setOf("minimumf32", "maximumf32", "minimumf64", "maximumf64")) {
    override fun renderSynthesizedBody(
        canonicalName: String,
        p0: String,
        p1: String,
        p2: String,
        layout0: String,
        layout1: String,
        intrPkg: String,
        memPkg: String,
        emitter: KotlinJ2KEmitter,
    ): String {
        val op = if (canonicalName.startsWith("min")) "minimum" else "maximum"
        val layoutName = if (canonicalName.endsWith("f32")) "PrimitiveLayouts.F32" else "PrimitiveLayouts.F64"
        CoreIntrinsicLowering.registerCoreImport(emitter, "$memPkg.floatBinaryWithLayout")
        CoreIntrinsicLowering.registerCoreImport(emitter, "$memPkg.PrimitiveLayouts")
        return "floatBinaryWithLayout(\"$op\", $p0, $p1, $layoutName)"
    }

    override fun renderCall(ctx: FloatIntrinsicCallContext): String? {
        if (ctx.renderedArgs.size < 2) return null
        return renderSynthesizedBody(
            ctx.canonicalName,
            ctx.renderedArgs[0],
            ctx.renderedArgs[1],
            "",
            "",
            "",
            ctx.intrPkg,
            ctx.memPkg,
            ctx.emitter,
        )
    }
}

private class GenericUnaryFloatIntrinsicRule :
    FloatIntrinsicLoweringRule(CoreIntrinsicLowering.GENERIC_FLOAT_UNARY_KM.keys) {
    override fun renderSynthesizedBody(
        canonicalName: String,
        p0: String,
        p1: String,
        p2: String,
        layout0: String,
        layout1: String,
        intrPkg: String,
        memPkg: String,
        emitter: KotlinJ2KEmitter,
    ): String {
        CoreIntrinsicLowering.registerCoreImport(emitter, "$memPkg.floatUnaryWithLayout")
        return "floatUnaryWithLayout(\"$canonicalName\", $p0, $layout0)"
    }

    override fun renderCall(ctx: FloatIntrinsicCallContext): String? {
        val (f32Km, f64Km) = CoreIntrinsicLowering.GENERIC_FLOAT_UNARY_KM[ctx.canonicalName] ?: return null
        return ctx.dispatchF32F64OrFallback(this, f32Km, f64Km, requiredArity = 1, kind = ctx.kindForArity(1))
    }
}

private class GenericBinaryFloatOpIntrinsicRule :
    FloatIntrinsicLoweringRule(CoreIntrinsicLowering.GENERIC_FLOAT_BINARY_OP_NAMES.keys) {
    override fun renderSynthesizedBody(
        canonicalName: String,
        p0: String,
        p1: String,
        p2: String,
        layout0: String,
        layout1: String,
        intrPkg: String,
        memPkg: String,
        emitter: KotlinJ2KEmitter,
    ): String {
        val op = CoreIntrinsicLowering.GENERIC_FLOAT_BINARY_OP_NAMES.getValue(canonicalName)
        CoreIntrinsicLowering.registerCoreImport(emitter, "$memPkg.floatBinaryWithLayout")
        return "floatBinaryWithLayout(\"$op\", $p0, $p1, $layout0)"
    }

    override fun renderCall(ctx: FloatIntrinsicCallContext): String? {
        val op = CoreIntrinsicLowering.GENERIC_FLOAT_BINARY_OP_NAMES[ctx.canonicalName] ?: return null
        if (ctx.renderedArgs.size < 2) return null
        val a = ctx.renderedArgs[0]
        val b = ctx.renderedArgs[1]
        val kind = ctx.kindForArity(2)
        if (kind == NumericKind.F32 || kind == NumericKind.F64) {
            when (op) {
                "fadd" -> return "(($a) + ($b))"
                "fsub" -> return "(($a) - ($b))"
                "fmul" -> return "(($a) * ($b))"
                "fdiv" -> return "(($a) / ($b))"
                "frem" -> return "(($a) % ($b))"
                "powf" -> return ctx.dispatchF32F64OrFallback(this, "kmPowf32", "kmPowf64", requiredArity = 2, kind = kind)
                "copysign" -> return ctx.dispatchF32F64OrFallback(this, "kmCopysignf32", "kmCopysignf64", requiredArity = 2, kind = kind)
                "minnum" -> return ctx.dispatchF32F64OrFallback(this, "kmMinnumf32", "kmMinnumf64", requiredArity = 2, kind = kind)
                "maxnum" -> return ctx.dispatchF32F64OrFallback(this, "kmMaxnumf32", "kmMaxnumf64", requiredArity = 2, kind = kind)
            }
        }
        val layout = ctx.floatLayoutExpr(kind) ?: return null
        return renderSynthesizedBody(ctx.canonicalName, a, b, "", layout, layout, ctx.intrPkg, ctx.memPkg, ctx.emitter)
    }
}

private object GenericPowiFloatIntrinsicRule : FloatIntrinsicLoweringRule(setOf("powi")) {
    override fun renderSynthesizedBody(
        canonicalName: String,
        p0: String,
        p1: String,
        p2: String,
        layout0: String,
        layout1: String,
        intrPkg: String,
        memPkg: String,
        emitter: KotlinJ2KEmitter,
    ): String {
        CoreIntrinsicLowering.registerCoreImport(emitter, "$memPkg.floatPowiWithLayout")
        return "floatPowiWithLayout($p0, $p1, $layout0)"
    }

    override fun renderCall(ctx: FloatIntrinsicCallContext): String? =
        ctx.dispatchF32F64OrFallback(this, "kmPowif32", "kmPowif64", requiredArity = 2, kind = ctx.kindForArity(1))
}

private object GenericFmaFloatIntrinsicRule : FloatIntrinsicLoweringRule(setOf("fma", "fmuladd")) {
    override fun renderSynthesizedBody(
        canonicalName: String,
        p0: String,
        p1: String,
        p2: String,
        layout0: String,
        layout1: String,
        intrPkg: String,
        memPkg: String,
        emitter: KotlinJ2KEmitter,
    ): String {
        CoreIntrinsicLowering.registerCoreImport(emitter, "$memPkg.floatFmaWithLayout")
        return "floatFmaWithLayout($p0, $p1, $p2, $layout0)"
    }

    override fun renderCall(ctx: FloatIntrinsicCallContext): String? =
        ctx.dispatchF32F64OrFallback(this, "kmFmaf32", "kmFmaf64", requiredArity = 3, kind = ctx.kindForArity(3))
}

private object FloatToIntUncheckedIntrinsicRule : FloatIntrinsicLoweringRule(setOf("float_to_int_unchecked")) {
    override fun renderSynthesizedBody(
        canonicalName: String,
        p0: String,
        p1: String,
        p2: String,
        layout0: String,
        layout1: String,
        intrPkg: String,
        memPkg: String,
        emitter: KotlinJ2KEmitter,
    ): String {
        CoreIntrinsicLowering.registerCoreImport(emitter, "$memPkg.floatToIntUncheckedWithLayout")
        return "floatToIntUncheckedWithLayout($p0, $layout0, $layout1)"
    }

    override fun renderCall(ctx: FloatIntrinsicCallContext): String? {
        val arg0 = ctx.renderedArgs.firstOrNull() ?: return null
        val srcTy =
            ctx.explicitTypes.getOrNull(0)?.takeUnless { it is SynType.Infer }
                ?: ctx.callArgsList.firstOrNull()?.let { ctx.emitter.sourceInitializerType(it) }
        val dstTy =
            ctx.explicitTypes.getOrNull(1)?.takeUnless { it is SynType.Infer }
                ?: ctx.emitter.currentEnclosingReturnSynType
        val srcKind = CoreIntrinsicLowering.resolveIntrinsicNumericKind(srcTy, ctx.callArgsList.take(1), ctx.selfTypeName, ctx.emitter)
        val dstKind =
            dstTy?.let { CoreIntrinsicLowering.explicitScalarRustName(it, ctx.emitter)?.let { s -> CoreIntrinsicLowering.rustScalarToNumericKind(s, ctx.emitter) } }
                ?: ctx.emitter.currentExpectedNumericKind
        if (srcKind == NumericKind.F32 || srcKind == NumericKind.F64) {
            val isF32 = srcKind == NumericKind.F32
            val u64Km = if (isF32) "kmFloatToU64Unchecked" else "kmDoubleToU64Unchecked"
            val u128Km = if (isF32) "kmFloatToU128Unchecked" else "kmDoubleToU128Unchecked"
            when (dstKind) {
                NumericKind.I8 -> return "($arg0).toInt().toByte()"
                NumericKind.I16 -> return "($arg0).toInt().toShort()"
                NumericKind.I32 -> return "($arg0).toInt()"
                NumericKind.I64 -> return "($arg0).toLong()"
                NumericKind.U8, NumericKind.U16, NumericKind.U32, NumericKind.U64 -> {
                    CoreIntrinsicLowering.registerCoreImport(ctx.emitter, "${ctx.intrPkg}.$u64Km")
                    val suffix =
                        when (dstKind) {
                            NumericKind.U8 -> ".toUByte()"
                            NumericKind.U16 -> ".toUShort()"
                            NumericKind.U32 -> ".toUInt()"
                            else -> ""
                        }
                    return "$u64Km($arg0)$suffix"
                }
                NumericKind.U128 -> {
                    CoreIntrinsicLowering.registerCoreImport(ctx.emitter, "${ctx.intrPkg}.$u128Km")
                    CoreIntrinsicLowering.registerCoreImport(ctx.emitter, "${ctx.coreNs}.U128")
                    return "$u128Km($arg0).let { U128(it.hi, it.lo) }"
                }
                NumericKind.I128 -> {
                    val srcLayout = layoutExprForNumericKind(srcKind, ctx.coreNs, ctx.memPkg, ctx.emitter)
                    val dstLayout = layoutExprForNumericKind(NumericKind.I128, ctx.coreNs, ctx.memPkg, ctx.emitter)
                    return renderSynthesizedBody(ctx.canonicalName, arg0, "", "", srcLayout, dstLayout, ctx.intrPkg, ctx.memPkg, ctx.emitter)
                }
                else -> Unit
            }
        }
        val srcLayout =
            srcKind?.let { layoutExprForNumericKind(it, ctx.coreNs, ctx.memPkg, ctx.emitter) }
                ?: srcTy?.let { CoreIntrinsicLowering.resolveTypeLayoutExpression(it, ctx.selfTypeName, ctx.emitter) }
                ?: return null
        val dstLayout =
            dstKind?.let { layoutExprForNumericKind(it, ctx.coreNs, ctx.memPkg, ctx.emitter) }
                ?: dstTy?.let { CoreIntrinsicLowering.resolveTypeLayoutExpression(it, ctx.selfTypeName, ctx.emitter) }
                ?: return null
        return renderSynthesizedBody(ctx.canonicalName, arg0, "", "", srcLayout, dstLayout, ctx.intrPkg, ctx.memPkg, ctx.emitter)
    }
}

private val FLOAT_INTRINSIC_RULES: List<FloatIntrinsicLoweringRule> =
    listOf(
        FixedUnaryFloatIntrinsicRule(),
        FixedBinaryFloatIntrinsicRule(),
        FixedFmaFloatIntrinsicRule,
        FixedMinMaxFloatIntrinsicRule,
        GenericUnaryFloatIntrinsicRule(),
        GenericBinaryFloatOpIntrinsicRule(),
        GenericPowiFloatIntrinsicRule,
        GenericFmaFloatIntrinsicRule,
        FloatToIntUncheckedIntrinsicRule,
    )

internal val FLOAT_INTRINSIC_RULES_BY_NAME: Map<String, FloatIntrinsicLoweringRule> =
    buildMap {
        for (rule in FLOAT_INTRINSIC_RULES) {
            for (name in rule.names) {
                put(name, rule)
            }
        }
    }

internal fun renderFloatIntrinsicCall(
    canonicalName: String,
    renderedArgs: List<String>,
    callArgsList: List<Expr>,
    explicitTypes: List<SynType>,
    selfTypeName: String?,
    coreNs: String,
    intrPkg: String,
    memPkg: String,
    emitter: KotlinJ2KEmitter,
): String? {
    val rule = FLOAT_INTRINSIC_RULES_BY_NAME[canonicalName] ?: return null
    return rule.renderCall(
        FloatIntrinsicCallContext(
            canonicalName = canonicalName,
            renderedArgs = renderedArgs,
            callArgsList = callArgsList,
            explicitTypes = explicitTypes,
            selfTypeName = selfTypeName,
            coreNs = coreNs,
            intrPkg = intrPkg,
            memPkg = memPkg,
            emitter = emitter,
        ),
    )
}

/**
 * Base class for direct scalar/pointer memory & atomic intrinsic emitters (`kmLoad*`,
 * `kmVolatileLoad*`, `kmStore*`, `kmVolatileStore*`, `kmAtomicLoad*`, `kmAtomicStore*`,
 * `kmAtomicXchg*`, `kmAtomicRmw*`, and `kmAtomicCxchg*`), centralizing pointee width
 * resolution, unsigned bit packing/unpacking, pointer constructor wrapping, and import registration.
 */
internal abstract class DirectPointeeMemoryIntrinsicEmitter(
    protected val intrPkg: String,
    protected val ptrPkg: String,
    protected val emitter: KotlinJ2KEmitter,
) {
    protected val ptrBits: Int = emitter.activeCfgProfile.pointerWidth
    protected val rawAbi: Boolean = emitter.headerContext.rawAbiMode
    protected val ptrUnsignedTag: String
        get() = if (ptrBits == 64) "U64" else "U32"

    protected open val allowChar: Boolean = false

    protected data class EncodedUnsignedArg(
        val widthTag: String,
        val expr: String,
    )

    protected fun resolvePointee(pointee: SynType): Pair<SynType, String?> {
        val resolved = emitter.resolveLayoutType(pointee, emptyMap())
        return resolved to CoreIntrinsicLowering.explicitScalarRustName(resolved, emitter)
    }

    protected fun resolveLoadWidthTag(resolved: SynType, scalarName: String?): String? {
        if (resolved is SynType.Ptr && !rawAbi) return ptrUnsignedTag
        return when (scalarName) {
            "bool", "u8", "i8" -> "U8"
            "u16", "i16" -> "U16"
            "u32", "i32" -> "U32"
            "char" -> if (allowChar) "U32" else null
            "u64", "i64" -> "U64"
            "usize", "isize" -> ptrUnsignedTag
            else -> null
        }
    }

    protected fun encodePointeeToUnsigned(
        resolved: SynType,
        scalarName: String?,
        valExpr: String,
    ): EncodedUnsignedArg? {
        if (resolved is SynType.Ptr && !rawAbi) {
            val conv = if (ptrBits == 64) "($valExpr).rawAddress.toULong()" else "($valExpr).rawAddress.toUInt()"
            return EncodedUnsignedArg(ptrUnsignedTag, conv)
        }
        return when (scalarName) {
            "bool" -> EncodedUnsignedArg("U8", "if ($valExpr) 1u else 0u")
            "u8" -> EncodedUnsignedArg("U8", valExpr)
            "i8" -> EncodedUnsignedArg("U8", "($valExpr).toUByte()")
            "u16" -> EncodedUnsignedArg("U16", valExpr)
            "i16" -> EncodedUnsignedArg("U16", "($valExpr).toUShort()")
            "u32" -> EncodedUnsignedArg("U32", valExpr)
            "i32" -> EncodedUnsignedArg("U32", "($valExpr).toUInt()")
            "char" -> if (allowChar) EncodedUnsignedArg("U32", "($valExpr).code.toUInt()") else null
            "u64" -> EncodedUnsignedArg("U64", valExpr)
            "i64" -> EncodedUnsignedArg("U64", "($valExpr).toULong()")
            "usize" -> {
                val v =
                    when {
                        rawAbi -> valExpr
                        ptrBits == 64 -> "($valExpr).toUInt().toULong()"
                        else -> "($valExpr).toUInt()"
                    }
                EncodedUnsignedArg(ptrUnsignedTag, v)
            }
            "isize" -> {
                val v = if (ptrBits == 64) "($valExpr).toLong().toULong()" else "($valExpr).toUInt()"
                EncodedUnsignedArg(ptrUnsignedTag, v)
            }
            else -> null
        }
    }

    protected fun decodeUnsignedToPointee(
        resolved: SynType,
        scalarName: String?,
        rawExpr: String,
        parenthesizeBool: Boolean = true,
    ): String? {
        if (resolved is SynType.Ptr && !rawAbi) {
            val ctor = if (resolved.mutability is PointerMutability.Mut) "MutPtr" else "ConstPtr"
            CoreIntrinsicLowering.registerCoreImport(emitter, "$ptrPkg.$ctor")
            return "$ctor($rawExpr.toLong())"
        }
        return when (scalarName) {
            "bool" -> if (parenthesizeBool) "($rawExpr != 0u.toUByte())" else "$rawExpr != 0u.toUByte()"
            "u8", "u16", "u32", "u64" -> rawExpr
            "i8" -> "$rawExpr.toByte()"
            "i16" -> "$rawExpr.toShort()"
            "i32" -> "$rawExpr.toInt()"
            "char" -> if (allowChar) "$rawExpr.toInt().toChar()" else null
            "i64" -> "$rawExpr.toLong()"
            "usize" -> if (rawAbi) rawExpr else "$rawExpr.toInt()"
            "isize" -> if (rawAbi) "$rawExpr.toLong()" else "$rawExpr.toLong().toInt()"
            else -> null
        }
    }

    protected fun registerAndCall(fnName: String, args: List<String>): String {
        CoreIntrinsicLowering.registerCoreImport(emitter, "$intrPkg.$fnName")
        return "$fnName(${args.joinToString(", ")})"
    }
}

internal class DirectLoadIntrinsicEmitter(
    private val fnPrefix: String,
    override val allowChar: Boolean,
    private val allowFloat: Boolean,
    private val isVolatile: Boolean,
    private val trailingArgs: List<String>,
    intrPkg: String,
    ptrPkg: String,
    emitter: KotlinJ2KEmitter,
) : DirectPointeeMemoryIntrinsicEmitter(intrPkg, ptrPkg, emitter) {
    fun render(pointee: SynType, addrExpr: String): String? {
        val (resolved, scalar) = resolvePointee(pointee)
        if (allowFloat && (scalar == "f32" || scalar == "f64")) {
            val isF32 = scalar == "f32"
            return if (!isVolatile) {
                registerAndCall(if (isF32) "kmLoadF32" else "kmLoadF64", listOf(addrExpr))
            } else if (isF32) {
                "Float.fromBits(${registerAndCall("kmVolatileLoadU32", listOf(addrExpr))}.toInt())"
            } else {
                "Double.fromBits(${registerAndCall("kmVolatileLoadU64", listOf(addrExpr))}.toLong())"
            }
        }
        val widthTag = resolveLoadWidthTag(resolved, scalar) ?: return null
        val call = registerAndCall("$fnPrefix$widthTag", listOf(addrExpr) + trailingArgs)
        return decodeUnsignedToPointee(resolved, scalar, call)
    }
}

internal class DirectStoreIntrinsicEmitter(
    private val fnPrefix: String,
    override val allowChar: Boolean,
    private val allowFloat: Boolean,
    private val isVolatile: Boolean,
    private val trailingArgs: List<String>,
    intrPkg: String,
    ptrPkg: String,
    emitter: KotlinJ2KEmitter,
) : DirectPointeeMemoryIntrinsicEmitter(intrPkg, ptrPkg, emitter) {
    fun render(pointee: SynType, addrExpr: String, valExpr: String): String? {
        val (resolved, scalar) = resolvePointee(pointee)
        if (allowFloat && (scalar == "f32" || scalar == "f64")) {
            val isF32 = scalar == "f32"
            return if (!isVolatile) {
                registerAndCall(if (isF32) "kmStoreF32" else "kmStoreF64", listOf(addrExpr, valExpr))
            } else if (isF32) {
                registerAndCall("kmVolatileStoreU32", listOf(addrExpr, "($valExpr).toRawBits().toUInt()"))
            } else {
                registerAndCall("kmVolatileStoreU64", listOf(addrExpr, "($valExpr).toRawBits().toULong()"))
            }
        }
        val encoded = encodePointeeToUnsigned(resolved, scalar, valExpr) ?: return null
        return registerAndCall("$fnPrefix${encoded.widthTag}", listOf(addrExpr, encoded.expr) + trailingArgs)
    }
}

internal class DirectReadModifyWriteIntrinsicEmitter(
    private val fnPrefix: String,
    private val trailingArgs: List<String>,
    intrPkg: String,
    ptrPkg: String,
    emitter: KotlinJ2KEmitter,
) : DirectPointeeMemoryIntrinsicEmitter(intrPkg, ptrPkg, emitter) {
    fun render(pointee: SynType, addrExpr: String, valExpr: String): String? {
        val (resolved, scalar) = resolvePointee(pointee)
        val encoded = encodePointeeToUnsigned(resolved, scalar, valExpr) ?: return null
        val call = registerAndCall("$fnPrefix${encoded.widthTag}", listOf(addrExpr, encoded.expr) + trailingArgs)
        return decodeUnsignedToPointee(resolved, scalar, call)
    }
}

internal class DirectCompareExchangeIntrinsicEmitter(
    intrPkg: String,
    ptrPkg: String,
    emitter: KotlinJ2KEmitter,
) : DirectPointeeMemoryIntrinsicEmitter(intrPkg, ptrPkg, emitter) {
    fun render(
        pointee: SynType,
        addrExpr: String,
        expectedExpr: String,
        desiredExpr: String,
        succOrd: String,
        failOrd: String,
    ): String? {
        val (resolved, scalar) = resolvePointee(pointee)
        if (scalar == "usize" || scalar == "isize") return null
        val pairCtor = if ("Pair" in emitter.inspector.declaredTypeNames) "kotlin.Pair" else "Pair"
        if (scalar == "u128" || scalar == "i128") {
            val isSigned = scalar == "i128"
            val expHi = if (isSigned) "($expectedExpr).high.toULong()" else "($expectedExpr).high"
            val desHi = if (isSigned) "($desiredExpr).high.toULong()" else "($desiredExpr).high"
            val call =
                registerAndCall(
                    "kmAtomicCxchgU128Pair",
                    listOf(addrExpr, "($expectedExpr).low", expHi, "($desiredExpr).low", desHi),
                )
            val prevVal = if (isSigned) "I128(cxchgRes.prevHi.toLong(), cxchgRes.prevLo)" else "U128(cxchgRes.prevHi, cxchgRes.prevLo)"
            return "$call.let { cxchgRes -> $pairCtor($prevVal, cxchgRes.succeeded) }"
        }
        val expEnc = encodePointeeToUnsigned(resolved, scalar, expectedExpr) ?: return null
        val desEnc = encodePointeeToUnsigned(resolved, scalar, desiredExpr) ?: return null
        val call =
            registerAndCall(
                "kmAtomicCxchg${expEnc.widthTag}",
                listOf(addrExpr, expEnc.expr, desEnc.expr, succOrd, failOrd),
            )
        val prevDecoded = decodeUnsignedToPointee(resolved, scalar, "cxchgRes.previous", parenthesizeBool = false) ?: return null
        return "$call.let { cxchgRes -> $pairCtor($prevDecoded, cxchgRes.succeeded) }"
    }
}
