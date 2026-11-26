package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.syn.Expr
import io.github.kotlinmania.syn.Item
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

internal class SynthesizedIntrinsicDeclContext(
    val fnItem: Item.Fn,
    val rawName: String,
    val canonicalName: String,
    val paramIdents: List<String>,
    val typeParams: List<String>,
    val ord0: String,
    val ord1: String,
    coreNs: String,
    emitter: KotlinJ2KEmitter,
) : CoreIntrinsicPackageScope(coreNs, emitter) {
    val t0: String = typeParams.getOrElse(0) { "Any" }
    val t1: String = typeParams.getOrElse(1) { t0 }
    val layout0: String = StructLayoutComputer.typeLayoutParamName(t0)
    val layout1: String = StructLayoutComputer.typeLayoutParamName(t1)
    val p0: String = paramIdents.getOrElse(0) { "arg0" }
    val p1: String = paramIdents.getOrElse(1) { "arg1" }
    val p2: String = paramIdents.getOrElse(2) { "arg2" }
}

internal class IntIntrinsicCallContext(
    call: IntrinsicCallContext,
    val kind: NumericKind?,
    val firstTargetTy: SynType?,
) : CoreIntrinsicPackageScope(call.coreNs, call.emitter) {
    val canonicalName: String = call.canonicalName
    val renderedArgs: List<String> = call.renderedArgs
    val callArgsList: List<Expr> = call.callArgsList
    val explicitTypes: List<SynType> = call.explicitTypes
    val selfTypeName: String? = call.selfTypeName

    fun fallbackWithLayout(fnName: String, argsPart: String): String? {
        val ty = firstTargetTy ?: return null
        val layout = CoreIntrinsicLowering.resolveTypeLayoutExpression(ty, selfTypeName, emitter)
        return callMem(fnName, argsPart, layout)
    }

    fun binary128Call(
        kmFn: String,
        lhsExpr: String,
        rhsExpr: String,
        wrapResult: (String) -> String = { it },
    ): String {
        importIntr(kmFn)
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

    protected open fun synthesizedArgs(decl: SynthesizedIntrinsicDeclContext): List<String> =
        listOf(decl.p0, decl.p1, decl.layout0)

    open fun renderSynthesizedBody(decl: SynthesizedIntrinsicDeclContext): String =
        decl.callMemArgs(withLayoutFn, synthesizedArgs(decl))

    abstract fun renderCall(ctx: IntIntrinsicCallContext): String?
}

private class BitwiseUnsignedIntrinsicRule(
    names: Set<String>,
    private val kmStem: String,
    withLayoutFn: String,
    private val returnsUIntCount: Boolean = false,
    private val takesUIntShift: Boolean = false,
    private val identityOnByte: Boolean = false,
) : IntIntrinsicLoweringRule(names, withLayoutFn, symmetricArgCount = 1) {
    override fun synthesizedArgs(decl: SynthesizedIntrinsicDeclContext): List<String> =
        if (takesUIntShift) listOf(decl.p0, "(${decl.p1}).toUInt()", decl.layout0) else listOf(decl.p0, decl.layout0)

    override fun renderCall(ctx: IntIntrinsicCallContext): String? {
        if (takesUIntShift && ctx.renderedArgs.size < 2) return null
        val arg0 = ctx.renderedArgs.firstOrNull() ?: return null
        val shift = if (takesUIntShift) "(${ctx.renderedArgs[1]}).toUInt()" else null
        val shiftSuffix = if (shift != null) ", $shift" else ""
        val kind = ctx.kind
        if (identityOnByte && (kind == NumericKind.I8 || kind == NumericKind.U8)) return arg0
        val uTag = kind?.let(::numericKindUnsignedBitTag)
        return when {
            uTag == "U128" -> {
                val kmFn = "${kmStem}U128"
                CoreIntrinsicLowering.registerCoreImport(ctx.emitter, "${ctx.intrPkg}.$kmFn")
                val hiExpr = if (kind == NumericKind.I128) "v.high.toULong()" else "v.high"
                if (returnsUIntCount) {
                    "($arg0).let { v -> $kmFn(v.low, $hiExpr$shiftSuffix) }"
                } else {
                    val built = reconstruct128Expr(kind, "r.lo", "r.hi", ctx.coreNs, ctx.emitter)
                    "($arg0).let { v -> $kmFn(v.low, $hiExpr$shiftSuffix).let { r -> $built } }"
                }
            }
            uTag != null -> {
                val kmFn = "$kmStem$uTag"
                CoreIntrinsicLowering.registerCoreImport(ctx.emitter, "${ctx.intrPkg}.$kmFn")
                val rawCall = "$kmFn(${toExactWidthUnsignedExpr(arg0, kind)}$shiftSuffix)"
                if (returnsUIntCount) rawCall else fromExactWidthUnsignedExpr(rawCall, kind)
            }
            else -> ctx.fallbackWithLayout(withLayoutFn, if (shift != null) "$arg0, $shift" else arg0)
        }
    }
}

private class BinaryInfixIntIntrinsicRule(
    names: Set<String>,
    private val opSymbol: String,
    withLayoutFn: String,
    private val u128Fn: String? = null,
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
            NumericKind.I128, NumericKind.U128 ->
                if (u128Fn != null) ctx.binary128Call(u128Fn, a, b) else "(($a) $opSymbol ($b))"
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

private class UncheckedShiftIntrinsicRule(
    name: String,
    private val isLeft: Boolean,
    withLayoutFn: String,
) : IntIntrinsicLoweringRule(name, withLayoutFn, symmetricArgCount = 1) {
    override fun synthesizedArgs(decl: SynthesizedIntrinsicDeclContext): List<String> =
        if (decl.typeParams.size >= 2) {
            listOf(decl.p0, decl.p1, decl.layout0, decl.layout1)
        } else {
            listOf(decl.p0, "(${decl.p1}).toUInt()", decl.layout0)
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
            NumericKind.U128, NumericKind.I128 -> {
                val isSigned = ctx.kind == NumericKind.I128
                val kmFn =
                    when {
                        isLeft -> "kmU128Shl"
                        isSigned -> "kmI128Shr"
                        else -> "kmU128Shr"
                    }
                CoreIntrinsicLowering.registerCoreImport(ctx.emitter, "${ctx.intrPkg}.$kmFn")
                val hiExpr = if (isSigned) "v.high.toULong()" else "v.high"
                val built = reconstruct128Expr(ctx.kind, "r.lo", "r.hi", ctx.coreNs, ctx.emitter)
                "($x).let { v -> $kmFn(v.low, $hiExpr, ($shift).toUInt()).let { r -> $built } }"
            }
            else -> {
                val ty0 = ctx.firstTargetTy ?: return null
                val layout0 = CoreIntrinsicLowering.resolveTypeLayoutExpression(ty0, ctx.selfTypeName, ctx.emitter)
                CoreIntrinsicLowering.registerCoreImport(ctx.emitter, "${ctx.memPkg}.$withLayoutFn")
                val shiftTy =
                    ctx.explicitTypes.nonInferAt(1)
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

private val INT_INTRINSIC_RULES_BY_NAME_MEMO =
    RegistryMemo { reg ->
        val rules =
            reg.mappings("intIntrinsicRules").map { node ->
                val names = node.wordSet("names")
                val withLayoutFn = node.string("withLayoutFn")
                when (val kind = node.string("kind")) {
                    "bitwiseUnsigned" ->
                        BitwiseUnsignedIntrinsicRule(
                            names = names,
                            kmStem = node.string("kmStem"),
                            withLayoutFn = withLayoutFn,
                            returnsUIntCount = node.bool("returnsUIntCount"),
                            takesUIntShift = node.bool("takesUIntShift"),
                            identityOnByte = node.bool("identityOnByte"),
                        )
                    "binaryInfix" ->
                        BinaryInfixIntIntrinsicRule(
                            names = names,
                            opSymbol = node.string("opSymbol"),
                            withLayoutFn = withLayoutFn,
                            u128Fn = node.optionalString("u128Fn"),
                        )
                    "widthTaggedBinary" ->
                        WidthTaggedBinaryIntrinsicRule(
                            name = names.single(),
                            kmPrefix = node.string("kmPrefix"),
                            withLayoutFn = withLayoutFn,
                            wrapPairOverflow = node.bool("wrapPairOverflow"),
                        )
                    "uncheckedShift" ->
                        UncheckedShiftIntrinsicRule(
                            name = names.single(),
                            isLeft = node.bool("isLeft"),
                            withLayoutFn = withLayoutFn,
                        )
                    "disjointBitor" -> DisjointBitorIntrinsicRule
                    "threeWayCompare" -> ThreeWayCompareIntrinsicRule
                    else -> throw IllegalArgumentException("Unknown intIntrinsicRules kind: '$kind'")
                }
            }
        buildMap {
            for (rule in rules) {
                for (name in rule.names) put(name, rule)
            }
        }
    }

internal val INT_INTRINSIC_RULES_BY_NAME: Map<String, IntIntrinsicLoweringRule>
    get() = INT_INTRINSIC_RULES_BY_NAME_MEMO.get()

internal fun renderIntOrBitIntrinsicCall(call: IntrinsicCallContext): String? {
    val rule = INT_INTRINSIC_RULES_BY_NAME[call.canonicalName] ?: return null
    val kind =
        CoreIntrinsicLowering.resolveIntrinsicNumericKind(
            call.explicitTypes.firstOrNull(),
            call.callArgsList.take(rule.symmetricArgCount),
            call.selfTypeName,
            call.emitter,
        )
    val firstTargetTy =
        call.explicitTypes.nonInferAt(0)
            ?: call.callArgsList.firstOrNull()?.let { call.emitter.sourceInitializerType(it) }
    return rule.renderCall(IntIntrinsicCallContext(call, kind, firstTargetTy))
}

internal class FloatEmitSpec(
    val canonicalName: String,
    val args: List<String>,
    val layout0: String,
    val layout1: String,
    coreNs: String,
    emitter: KotlinJ2KEmitter,
) : CoreIntrinsicPackageScope(coreNs, emitter) {
    val p0: String get() = args.getOrElse(0) { "" }
    val p1: String get() = args.getOrElse(1) { "" }
    val p2: String get() = args.getOrElse(2) { "" }

    fun callIntr(fnName: String, count: Int): String =
        "${importIntr(fnName)}(${args.take(count).joinToString(", ")})"
}

internal class FloatIntrinsicCallContext(
    call: IntrinsicCallContext,
) : CoreIntrinsicPackageScope(call.coreNs, call.emitter) {
    val canonicalName: String = call.canonicalName
    val renderedArgs: List<String> = call.renderedArgs
    val callArgsList: List<Expr> = call.callArgsList
    val explicitTypes: List<SynType> = call.explicitTypes
    val selfTypeName: String? = call.selfTypeName

    fun kindForArity(arity: Int): NumericKind? =
        CoreIntrinsicLowering.resolveIntrinsicNumericKind(
            explicitTypes.firstOrNull(),
            callArgsList.take(arity),
            selfTypeName,
            emitter,
        )

    fun floatLayoutExpr(kind: NumericKind?): String? {
        val targetTy =
            explicitTypes.nonInferAt(0)
                ?: callArgsList.firstOrNull()?.let { emitter.sourceInitializerType(it) }
        return when {
            kind != null && kind in setOf(NumericKind.F16, NumericKind.F32, NumericKind.F64, NumericKind.F128) ->
                layoutExprForNumericKind(kind, coreNs, memPkg, emitter)
            targetTy != null -> CoreIntrinsicLowering.resolveTypeLayoutExpression(targetTy, selfTypeName, emitter)
            else -> null
        }
    }

    fun emitWithLayouts(rule: FloatIntrinsicLoweringRule, layout0: String, layout1: String = layout0): String? =
        rule.renderWithLayouts(FloatEmitSpec(canonicalName, renderedArgs, layout0, layout1, coreNs, emitter))

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
            NumericKind.F32 -> "${importIntr(f32Km)}($callArgsJoined)"
            NumericKind.F64 -> "${importIntr(f64Km)}($callArgsJoined)"
            else -> {
                val layout = floatLayoutExpr(kind) ?: return null
                emitWithLayouts(rule, layout)
            }
        }
    }
}

internal abstract class FloatIntrinsicLoweringRule(
    val names: Set<String>,
    private val directCallArity: Int? = null,
) {
    abstract fun renderWithLayouts(spec: FloatEmitSpec): String?

    fun renderSynthesizedBody(decl: SynthesizedIntrinsicDeclContext): String? =
        renderWithLayouts(
            FloatEmitSpec(
                canonicalName = decl.canonicalName,
                args = listOf(decl.p0, decl.p1, decl.p2),
                layout0 = decl.layout0,
                layout1 = decl.layout1,
                coreNs = decl.coreNs,
                emitter = decl.emitter,
            ),
        )

    open fun renderCall(ctx: FloatIntrinsicCallContext): String? {
        val arity = directCallArity ?: return null
        if (ctx.renderedArgs.size < arity) return null
        return ctx.emitWithLayouts(this, "")
    }
}

private class FixedKmFloatIntrinsicRule(
    private val kmByName: Map<String, String>,
    private val arity: Int,
) : FloatIntrinsicLoweringRule(kmByName.keys, directCallArity = arity) {
    override fun renderWithLayouts(spec: FloatEmitSpec): String? {
        val kmFn = kmByName[spec.canonicalName] ?: return null
        return spec.callIntr(kmFn, arity)
    }
}

private object FixedMinMaxFloatIntrinsicRule :
    FloatIntrinsicLoweringRule(setOf("minimumf32", "maximumf32", "minimumf64", "maximumf64"), directCallArity = 2) {
    override fun renderWithLayouts(spec: FloatEmitSpec): String {
        val op = if (spec.canonicalName.startsWith("min")) "minimum" else "maximum"
        val layoutName = if (spec.canonicalName.endsWith("f32")) "PrimitiveLayouts.F32" else "PrimitiveLayouts.F64"
        CoreIntrinsicLowering.registerCoreImport(spec.emitter, "${spec.memPkg}.PrimitiveLayouts")
        return spec.callMem("floatBinaryWithLayout", "\"$op\"", spec.p0, spec.p1, layoutName)
    }
}

private class GenericUnaryFloatIntrinsicRule : FloatIntrinsicLoweringRule(CoreIntrinsicLowering.GENERIC_FLOAT_UNARY_KM.keys) {
    override fun renderWithLayouts(spec: FloatEmitSpec): String =
        spec.callMem("floatUnaryWithLayout", "\"${spec.canonicalName}\"", spec.p0, spec.layout0)

    override fun renderCall(ctx: FloatIntrinsicCallContext): String? {
        val (f32Km, f64Km) = CoreIntrinsicLowering.GENERIC_FLOAT_UNARY_KM[ctx.canonicalName] ?: return null
        return ctx.dispatchF32F64OrFallback(this, f32Km, f64Km, requiredArity = 1, kind = ctx.kindForArity(1))
    }
}

private class GenericBinaryFloatOpIntrinsicRule : FloatIntrinsicLoweringRule(CoreIntrinsicLowering.GENERIC_FLOAT_BINARY_OP_NAMES.keys) {
    override fun renderWithLayouts(spec: FloatEmitSpec): String {
        val op = CoreIntrinsicLowering.GENERIC_FLOAT_BINARY_OP_NAMES.getValue(spec.canonicalName)
        return spec.callMem("floatBinaryWithLayout", "\"$op\"", spec.p0, spec.p1, spec.layout0)
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
        return ctx.emitWithLayouts(this, layout)
    }
}

private object GenericPowiFloatIntrinsicRule : FloatIntrinsicLoweringRule(setOf("powi")) {
    override fun renderWithLayouts(spec: FloatEmitSpec): String =
        spec.callMem("floatPowiWithLayout", spec.p0, spec.p1, spec.layout0)

    override fun renderCall(ctx: FloatIntrinsicCallContext): String? =
        ctx.dispatchF32F64OrFallback(this, "kmPowif32", "kmPowif64", requiredArity = 2, kind = ctx.kindForArity(1))
}

private object GenericFmaFloatIntrinsicRule : FloatIntrinsicLoweringRule(setOf("fma", "fmuladd")) {
    override fun renderWithLayouts(spec: FloatEmitSpec): String =
        spec.callMem("floatFmaWithLayout", spec.p0, spec.p1, spec.p2, spec.layout0)

    override fun renderCall(ctx: FloatIntrinsicCallContext): String? =
        ctx.dispatchF32F64OrFallback(this, "kmFmaf32", "kmFmaf64", requiredArity = 3, kind = ctx.kindForArity(3))
}

private object FloatToIntUncheckedIntrinsicRule : FloatIntrinsicLoweringRule(setOf("float_to_int_unchecked")) {
    override fun renderWithLayouts(spec: FloatEmitSpec): String =
        spec.callMem("floatToIntUncheckedWithLayout", spec.p0, spec.layout0, spec.layout1)

    override fun renderCall(ctx: FloatIntrinsicCallContext): String? {
        val arg0 = ctx.renderedArgs.firstOrNull() ?: return null
        val srcTy =
            ctx.explicitTypes.nonInferAt(0)
                ?: ctx.callArgsList.firstOrNull()?.let { ctx.emitter.sourceInitializerType(it) }
        val dstTy = ctx.explicitTypes.nonInferAt(1) ?: ctx.emitter.currentEnclosingReturnSynType
        val srcKind = CoreIntrinsicLowering.resolveIntrinsicNumericKind(srcTy, ctx.callArgsList.take(1), ctx.selfTypeName, ctx.emitter)
        val dstScalar = dstTy?.let { CoreIntrinsicLowering.explicitScalarRustName(it, ctx.emitter) }
        val dstKind =
            dstScalar?.let { CoreIntrinsicLowering.rustScalarToNumericKind(it, ctx.emitter) }
                ?: ctx.emitter.currentExpectedNumericKind
        if (srcKind == NumericKind.F32 || srcKind == NumericKind.F64) {
            CoreIntrinsicLowering
                .renderScalarFloatToIntUnchecked(
                    emitter = ctx.emitter,
                    coreNs = ctx.coreNs,
                    isF32 = srcKind == NumericKind.F32,
                    valueExpr = arg0,
                    atomExpr = "($arg0)",
                    dstKind = dstKind,
                )?.let { return it }
        }
        val srcLayout =
            srcKind?.let { layoutExprForNumericKind(it, ctx.coreNs, ctx.memPkg, ctx.emitter) }
                ?: srcTy?.let { CoreIntrinsicLowering.resolveTypeLayoutExpression(it, ctx.selfTypeName, ctx.emitter) }
                ?: return null
        val dstLayout =
            dstKind?.let { layoutExprForNumericKind(it, ctx.coreNs, ctx.memPkg, ctx.emitter) }
                ?: dstTy?.let { CoreIntrinsicLowering.resolveTypeLayoutExpression(it, ctx.selfTypeName, ctx.emitter) }
                ?: return null
        return ctx.emitWithLayouts(this, srcLayout, dstLayout)
    }
}

private val FLOAT_INTRINSIC_RULES_BY_NAME_MEMO =
    RegistryMemo { reg ->
        val rules =
            listOf(
                FixedKmFloatIntrinsicRule(reg.suffixedFloatUnaryKm, arity = 1),
                FixedKmFloatIntrinsicRule(reg.map("suffixedFloatBinaryKm"), arity = 2),
                FixedKmFloatIntrinsicRule(reg.map("suffixedFloatTernaryKm"), arity = 3),
                FixedMinMaxFloatIntrinsicRule,
                GenericUnaryFloatIntrinsicRule(),
                GenericBinaryFloatOpIntrinsicRule(),
                GenericPowiFloatIntrinsicRule,
                GenericFmaFloatIntrinsicRule,
                FloatToIntUncheckedIntrinsicRule,
            )
        buildMap {
            for (rule in rules) {
                for (name in rule.names) put(name, rule)
            }
        }
    }

internal val FLOAT_INTRINSIC_RULES_BY_NAME: Map<String, FloatIntrinsicLoweringRule>
    get() = FLOAT_INTRINSIC_RULES_BY_NAME_MEMO.get()

internal fun renderFloatIntrinsicCall(call: IntrinsicCallContext): String? {
    val rule = FLOAT_INTRINSIC_RULES_BY_NAME[call.canonicalName] ?: return null
    return rule.renderCall(FloatIntrinsicCallContext(call))
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
