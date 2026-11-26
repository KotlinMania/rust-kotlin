package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.procmacro2.Delimiter
import io.github.kotlinmania.procmacro2.TokenStream
import io.github.kotlinmania.procmacro2.TokenTree
import io.github.kotlinmania.syn.Block
import io.github.kotlinmania.syn.Expr
import io.github.kotlinmania.syn.ExprParse
import io.github.kotlinmania.syn.FnArg
import io.github.kotlinmania.syn.GenericArgument
import io.github.kotlinmania.syn.GenericParam
import io.github.kotlinmania.syn.Generics
import io.github.kotlinmania.syn.Item
import io.github.kotlinmania.syn.ItemParse
import io.github.kotlinmania.syn.Macro
import io.github.kotlinmania.syn.Pat
import io.github.kotlinmania.syn.PathArguments
import io.github.kotlinmania.syn.PointerMutability
import io.github.kotlinmania.syn.ReturnType
import io.github.kotlinmania.syn.Signature
import io.github.kotlinmania.syn.Stmt
import io.github.kotlinmania.syn.SynType
import io.github.kotlinmania.syn.parse2

internal fun wordSet(vararg lines: String): Set<String> =
    lines.flatMapTo(linkedSetOf()) { it.split(' ').filter(String::isNotEmpty) }

internal fun List<SynType>.nonInferAt(idx: Int): SynType? =
    getOrNull(idx)?.takeUnless { it is SynType.Infer }

internal data class CoreIntrinsicCallSpec(
    val rawName: String,
    val canonicalName: String,
    val ord0: String?,
    val ord1: String?,
    val isIntrinsicsModuleCall: Boolean,
    val penultName: String?,
    val rewrittenSegNames: List<String>,
    val explicitTypes: List<SynType>,
    val explicitConsts: List<Expr>,
    val callArgsList: List<Expr>,
) {
    fun renderedExplicitTypeAt(idx: Int, selfTypeName: String?, emitter: KotlinJ2KEmitter): String? =
        explicitTypes.nonInferAt(idx)?.let { emitter.renderType(it, selfTypeName) }

    fun inferredPointeeTypeAt0(emitter: KotlinJ2KEmitter): SynType? {
        explicitTypes.nonInferAt(0)?.let { return it }
        val initTy = callArgsList.firstOrNull()?.let { emitter.sourceInitializerType(it) } ?: return null
        return (unwrapSynType(initTy) as? SynType.Ptr)?.elem
    }
}

internal open class CoreIntrinsicPackageScope(
    val coreNs: String,
    val emitter: KotlinJ2KEmitter,
) {
    val intrPkg: String = "$coreNs.intrinsics"
    val memPkg: String = "$coreNs.mem"
    val ptrPkg: String = "$coreNs.ptr"
    val rawAbi: Boolean get() = emitter.headerContext.rawAbiMode

    fun usizeLiteral(lit: Int): String = if (rawAbi) "${lit}uL" else "$lit"

    fun asUsizeExpr(expr: String): String = if (rawAbi) "$expr.toULong()" else "$expr.toInt()"

    fun importIntr(sym: String): String {
        CoreIntrinsicLowering.registerCoreImport(emitter, "$intrPkg.$sym")
        return sym
    }

    fun importMem(sym: String): String {
        CoreIntrinsicLowering.registerCoreImport(emitter, "$memPkg.$sym")
        return sym
    }

    fun importPtr(sym: String): String {
        CoreIntrinsicLowering.registerCoreImport(emitter, "$ptrPkg.$sym")
        return sym
    }

    fun callIntr(fnName: String, vararg args: String): String =
        "${importIntr(fnName)}(${args.joinToString(", ")})"

    fun callMem(fnName: String, vararg args: String): String =
        "${importMem(fnName)}(${args.joinToString(", ")})"

    fun callMemArgs(fnName: String, args: List<String>): String =
        "${importMem(fnName)}(${args.joinToString(", ")})"
}

internal class IntrinsicCallContext(
    val spec: CoreIntrinsicCallSpec,
    val renderedArgs: List<String>,
    val indent: String,
    val selfTypeName: String?,
    coreNs: String,
    val isCoreSimdCall: Boolean,
    emitter: KotlinJ2KEmitter,
) : CoreIntrinsicPackageScope(coreNs, emitter) {
    val rawName: String get() = spec.rawName
    val canonicalName: String get() = spec.canonicalName
    val explicitTypes: List<SynType> get() = spec.explicitTypes
    val explicitConsts: List<Expr> get() = spec.explicitConsts
    val callArgsList: List<Expr> get() = spec.callArgsList

    fun pointeeAt(argIdx: Int = 0): SynType? =
        explicitTypes.firstOrNull()
            ?: (callArgsList.getOrNull(argIdx)?.let { emitter.sourceInitializerType(it) } as? SynType.Ptr)?.elem

    fun targetOrPointeeAt0(): SynType? =
        CoreIntrinsicLowering.unwrapTargetOrPointeeType(explicitTypes, callArgsList, emitter)

    fun layoutOf(ty: SynType): String =
        CoreIntrinsicLowering.resolveTypeLayoutExpression(ty, selfTypeName, emitter)

    fun rawAddrOf(argIdx: Int): String {
        val argExpr = callArgsList.getOrNull(argIdx)
        val rendered = renderedArgs[argIdx]
        if (rawAbi) return rendered
        val inferred = argExpr?.let { emitter.inferExprRenderedType(it, selfTypeName) }
        val atom = if (argExpr != null && emitter.isAtomicPostfixReceiver(argExpr, rendered)) rendered else "($rendered)"
        return if (inferred == "Long") rendered else "$atom.rawAddress"
    }

    fun orderingCode(constIdx: Int): String {
        val legacyOrd = if (constIdx == 0) spec.ord0 else spec.ord1
        if (legacyOrd != null && constIdx >= explicitConsts.size) return legacyOrd
        var expr = explicitConsts.getOrNull(constIdx) ?: return legacyOrd ?: "4"
        while (true) {
            expr =
                when (val unwrapped = emitter.unwrapParenOrGroupExpr(expr)) {
                    is Expr.BlockExpr -> (unwrapped.block.stmts.singleOrNull() as? Stmt.ExprStmt)?.expr ?: break
                    else -> unwrapped
                }
            if (expr !is Expr.BlockExpr) break
        }
        return when ((expr as? Expr.Path)?.path?.lastIdentText()) {
            "Relaxed" -> "0"
            "Release" -> "1"
            "Acquire" -> "2"
            "AcqRel" -> "3"
            "SeqCst" -> "4"
            else -> "(${emitter.renderExpr(expr, indent, selfTypeName)}).ordinal"
        }
    }
}

private abstract class CoreIntrinsicFamilyRule {
    abstract fun matches(canonicalName: String, isCoreSimdCall: Boolean = false): Boolean

    open fun renderSynthesizedDecl(decl: SynthesizedIntrinsicDeclContext): String? = null

    abstract fun renderCall(ctx: IntrinsicCallContext): String?
}

private fun SynthesizedIntrinsicDeclContext.renderDeclarativeBody(
    rulesByName: Map<String, DeclarativeIntrinsicPatternRule>,
): String? {
    val action = rulesByName[canonicalName]?.declAction ?: return null
    return action.render(
        rawAbi = rawAbi,
        onImport = { CoreIntrinsicLowering.registerCoreImport(emitter, it) },
    ) { key ->
        when (key) {
            "coreNs" -> coreNs
            "p0" -> p0
            "p1" -> p1
            "p2" -> p2
            "t0" -> t0
            "t1" -> t1
            "layout0" -> layout0
            "layout1" -> layout1
            "ord0" -> ord0
            "ord1" -> ord1
            else -> null
        }
    }
}

private object ControlAndHintIntrinsicFamilyRule : CoreIntrinsicFamilyRule() {
    override fun matches(canonicalName: String, isCoreSimdCall: Boolean): Boolean =
        canonicalName == "black_box" || canonicalName in YamlRuleCatalogs.CoreIntrinsics.controlAndHintRulesByName

    override fun renderSynthesizedDecl(decl: SynthesizedIntrinsicDeclContext): String? {
        if (decl.canonicalName == "black_box") return decl.p0
        return decl.renderDeclarativeBody(YamlRuleCatalogs.CoreIntrinsics.controlAndHintRulesByName)
    }

    override fun renderCall(ctx: IntrinsicCallContext): String? {
        if (ctx.canonicalName == "black_box") {
            val arg0 = ctx.renderedArgs.firstOrNull() ?: return "Unit"
            val kind = ctx.callArgsList.firstOrNull()?.let { ctx.emitter.inferExprNumericKind(it, ctx.selfTypeName) }
            return if (kind == NumericKind.U64) ctx.callIntr("kmBlackBoxU64", arg0) else arg0
        }
        val rule = YamlRuleCatalogs.CoreIntrinsics.controlAndHintRulesByName[ctx.canonicalName] ?: return null
        val callAction = rule.callAction ?: return null
        if (ctx.renderedArgs.size < rule.minCallArgs) return null
        return callAction.render(
            rawAbi = ctx.rawAbi,
            onImport = { CoreIntrinsicLowering.registerCoreImport(ctx.emitter, it) },
        ) { key ->
            when (key) {
                "coreNs" -> ctx.coreNs
                "arg0OrUnit" -> ctx.renderedArgs.firstOrNull() ?: "Unit"
                "arg0" -> ctx.renderedArgs.getOrNull(0)
                "arg1" -> ctx.renderedArgs.getOrNull(1)
                "arg2" -> ctx.renderedArgs.getOrNull(2)
                else -> null
            }
        }
    }
}

private object LayoutAndReflectionIntrinsicFamilyRule : CoreIntrinsicFamilyRule() {
    private val ALIGN_NAMES = setOf("align_of", "align_of_val", "min_align_of", "min_align_of_val", "pref_align_of")
    private val TRIVIAL_SCALARS: Set<String>
        get() = YamlRuleCatalogs.CoreIntrinsics.trivialDropScalars

    override fun matches(canonicalName: String, isCoreSimdCall: Boolean): Boolean =
        canonicalName in YamlRuleCatalogs.CoreIntrinsics.layoutDeclRulesByName

    override fun renderSynthesizedDecl(decl: SynthesizedIntrinsicDeclContext): String? =
        decl.renderDeclarativeBody(YamlRuleCatalogs.CoreIntrinsics.layoutDeclRulesByName)

    override fun renderCall(ctx: IntrinsicCallContext): String? =
        when (ctx.canonicalName) {
            "size_of" -> {
                val ty = ctx.explicitTypes.firstOrNull() ?: return null
                ctx.asUsizeExpr("(${ctx.layoutOf(ty)}).sizeBytes")
            }
            "size_of_val" -> {
                val ptrExpr = ctx.renderedArgs.firstOrNull() ?: return null
                val firstArg = ctx.callArgsList.firstOrNull()
                val pointeeTy = ctx.targetOrPointeeAt0() ?: return null
                val layout = ctx.layoutOf(pointeeTy)
                if (ctx.rawAbi) {
                    "($layout).sizeBytes.toULong()"
                } else {
                    val argRenderedTy = firstArg?.let { ctx.emitter.inferExprRenderedType(it, ctx.selfTypeName) }
                    val isPtrArg =
                        argRenderedTy != null &&
                            (argRenderedTy.startsWith("ConstPtr") || argRenderedTy.startsWith("MutPtr") || ".ptr." in argRenderedTy)
                    if (isPtrArg) {
                        val atomPtr = if (ctx.emitter.isAtomicPostfixReceiver(firstArg, ptrExpr)) ptrExpr else "($ptrExpr)"
                        "(if ($atomPtr.rawMeta > 0L) ($atomPtr.rawMeta * ($layout).sizeBytes).toInt() else ($layout).sizeBytes.toInt())"
                    } else {
                        "($layout).sizeBytes.toInt()"
                    }
                }
            }
            in ALIGN_NAMES -> {
                val ty = ctx.targetOrPointeeAt0() ?: return null
                ctx.asUsizeExpr("(${ctx.layoutOf(ty)}).alignBytes")
            }
            "vtable_size", "vtable_align" -> ctx.usizeLiteral(8)
            "variant_count" -> ctx.usizeLiteral(1)
            "raw_eq" -> {
                if (ctx.renderedArgs.size < 2) return null
                val targetTy = ctx.targetOrPointeeAt0() ?: return null
                ctx.callMem("rawEqWithLayout", ctx.renderedArgs[0], ctx.renderedArgs[1], ctx.layoutOf(targetTy))
            }
            "needs_drop" -> {
                val ty = ctx.explicitTypes.firstOrNull() ?: return "true"
                val primName =
                    (ty as? SynType.Path)
                        ?.takeIf { it.qself == null && it.path.segments.size == 1 }
                        ?.path
                        ?.lastIdentText()
                val isTrivial = ty is SynType.Ptr || ty is SynType.Reference || primName in TRIVIAL_SCALARS
                if (isTrivial) "false" else "true"
            }
            "type_name" -> {
                val label = ctx.explicitTypes.firstOrNull()?.let { ctx.emitter.renderType(it, ctx.selfTypeName) } ?: "Any"
                "\"$label\""
            }
            else -> null
        }
}

private object PointerAndMemoryIntrinsicFamilyRule : CoreIntrinsicFamilyRule() {
    private val LOAD_NAMES = setOf("read_via_copy", "volatile_load", "unaligned_volatile_load")
    private val STORE_NAMES = setOf("write_via_move", "volatile_store", "unaligned_volatile_store", "nontemporal_store")
    private val COPY_NAMES = setOf("copy_nonoverlapping", "volatile_copy_nonoverlapping_memory", "copy", "volatile_copy_memory")
    private val SET_NAMES = setOf("write_bytes", "volatile_set_memory")
    private val PTR_OPS =
        setOf("offset", "arith_offset", "ptr_offset_from", "ptr_offset_from_unsigned", "ptr_mask", "aggregate_raw_ptr", "ptr_metadata", "compare_bytes")

    override fun matches(canonicalName: String, isCoreSimdCall: Boolean): Boolean =
        canonicalName in PTR_OPS || canonicalName in LOAD_NAMES || canonicalName in STORE_NAMES || canonicalName in COPY_NAMES || canonicalName in SET_NAMES

    override fun renderSynthesizedDecl(decl: SynthesizedIntrinsicDeclContext): String? =
        decl.renderDeclarativeBody(YamlRuleCatalogs.CoreIntrinsics.pointerDeclRulesByName)

    override fun renderCall(ctx: IntrinsicCallContext): String? =
        when (ctx.canonicalName) {
            "offset", "arith_offset" -> {
                val firstArg = ctx.callArgsList.firstOrNull() ?: return null
                val ptrSynType =
                    (ctx.explicitTypes.firstOrNull() as? SynType.Ptr)
                        ?: (ctx.emitter.sourceInitializerType(firstArg) as? SynType.Ptr)
                val pointeeSynType = ptrSynType?.elem ?: ctx.explicitTypes.firstOrNull()?.takeUnless { it is SynType.Ptr }
                val inferredPtrRendered = ctx.emitter.inferExprRenderedType(firstArg, ctx.selfTypeName)
                val isMut =
                    (ptrSynType?.mutability is PointerMutability.Mut) ||
                        inferredPtrRendered?.substringAfterLast('.')?.startsWith("MutPtr<") == true
                val ctor = ctx.importPtr(if (isMut) "MutPtr" else "ConstPtr")
                val strideExpr = if (pointeeSynType != null) "(${ctx.layoutOf(pointeeSynType)}).strideBytes" else "1L"
                "$ctor(${ctx.rawAddrOf(0)} + (${ctx.renderedArgs[1]}).toLong() * $strideExpr)"
            }
            "ptr_offset_from", "ptr_offset_from_unsigned" -> {
                val pointeeSynType = ctx.pointeeAt(0) ?: ctx.pointeeAt(1)
                val sizeExpr = if (pointeeSynType != null) "maxOf(1L, (${ctx.layoutOf(pointeeSynType)}).sizeBytes)" else "1L"
                val diffDiv = "((${ctx.rawAddrOf(0)} - ${ctx.rawAddrOf(1)}) / $sizeExpr)"
                when {
                    ctx.canonicalName == "ptr_offset_from_unsigned" && ctx.rawAbi -> "$diffDiv.toULong()"
                    ctx.rawAbi -> diffDiv
                    else -> "$diffDiv.toInt()"
                }
            }
            "ptr_mask" -> "${ctx.importPtr("ConstPtr")}(${ctx.rawAddrOf(0)} and (${ctx.renderedArgs[1]}).toLong())"
            "aggregate_raw_ptr" -> {
                val enclosingRet = ctx.emitter.currentEnclosingReturnRenderedType
                val isMut = enclosingRet?.substringAfterLast('.')?.startsWith("MutPtr<") == true
                val ctor = ctx.importPtr(if (isMut) "MutPtr" else "ConstPtr")
                "$ctor(${ctx.rawAddrOf(0)}, (${ctx.renderedArgs[1]}).toLong())"
            }
            "ptr_metadata" -> ctx.asUsizeExpr("${ctx.renderedArgs[0]}.rawMeta")
            in LOAD_NAMES -> {
                val isVolatile = ctx.rawName != "read_via_copy"
                val pointee = ctx.pointeeAt(0) ?: return null
                DirectLoadIntrinsicEmitter(
                    fnPrefix = if (isVolatile) "kmVolatileLoad" else "kmLoad",
                    allowChar = true,
                    allowFloat = true,
                    isVolatile = isVolatile,
                    trailingArgs = emptyList(),
                    intrPkg = ctx.intrPkg,
                    ptrPkg = ctx.ptrPkg,
                    emitter = ctx.emitter,
                ).render(pointee, ctx.rawAddrOf(0)) ?: run {
                    val method = if (isVolatile) "readVolatileNative" else "readNative"
                    "(${ctx.layoutOf(pointee)}).$method(${ctx.rawAddrOf(0)})"
                }
            }
            in STORE_NAMES -> {
                val isVolatile = ctx.rawName == "volatile_store" || ctx.rawName == "unaligned_volatile_store"
                val pointee = ctx.pointeeAt(0) ?: return null
                DirectStoreIntrinsicEmitter(
                    fnPrefix = if (isVolatile) "kmVolatileStore" else "kmStore",
                    allowChar = true,
                    allowFloat = true,
                    isVolatile = isVolatile,
                    trailingArgs = emptyList(),
                    intrPkg = ctx.intrPkg,
                    ptrPkg = ctx.ptrPkg,
                    emitter = ctx.emitter,
                ).render(pointee, ctx.rawAddrOf(0), ctx.renderedArgs[1]) ?: run {
                    val method = if (isVolatile) "writeVolatileNative" else "writeNative"
                    "(${ctx.layoutOf(pointee)}).$method(${ctx.rawAddrOf(0)}, ${ctx.renderedArgs[1]})"
                }
            }
            in COPY_NAMES -> {
                val fnName = if (ctx.rawName.contains("nonoverlapping")) "kmCopyNonoverlapping" else "kmCopy"
                val srcIdx = if (ctx.rawName.startsWith("volatile_")) 1 else 0
                val dstIdx = if (ctx.rawName.startsWith("volatile_")) 0 else 1
                val bytesExpr = CoreIntrinsicLowering.renderScaledByteCountExpr(ctx.pointeeAt(srcIdx), ctx.renderedArgs[2], ctx.selfTypeName, ctx.emitter)
                ctx.callIntr(fnName, ctx.rawAddrOf(dstIdx), ctx.rawAddrOf(srcIdx), bytesExpr)
            }
            in SET_NAMES -> {
                val bytesExpr = CoreIntrinsicLowering.renderScaledByteCountExpr(ctx.pointeeAt(0), ctx.renderedArgs[2], ctx.selfTypeName, ctx.emitter)
                ctx.callIntr("kmWriteBytes", ctx.rawAddrOf(0), "(${ctx.renderedArgs[1]}).toUByte()", bytesExpr)
            }
            "compare_bytes" ->
                ctx.callIntr("kmCompareBytes", ctx.rawAddrOf(0), ctx.rawAddrOf(1), "(${ctx.renderedArgs[2]}).toLong()")
            else -> null
        }
}

private object AtomicMemoryIntrinsicFamilyRule : CoreIntrinsicFamilyRule() {
    val RMW_OP_CODES: Map<String, Int> = YamlRuleCatalogs.CoreIntrinsics.atomicRmwOpCodes
    val NON_RMW_NAMES: Set<String> = YamlRuleCatalogs.CoreIntrinsics.atomicNonRmwNames

    override fun matches(canonicalName: String, isCoreSimdCall: Boolean): Boolean =
        canonicalName in NON_RMW_NAMES || canonicalName in RMW_OP_CODES

    override fun renderSynthesizedDecl(decl: SynthesizedIntrinsicDeclContext): String? {
        decl.renderDeclarativeBody(YamlRuleCatalogs.CoreIntrinsics.atomicRulesByName)?.let { return it }
        val rmwCode = RMW_OP_CODES[decl.canonicalName]?.toString() ?: return null
        val opLayout = if (decl.typeParams.size >= 2) decl.layout1 else decl.layout0
        return decl.callMem("atomicRmwWithLayout", "${decl.p0}.rawAddress", decl.p1, rmwCode, decl.ord0, decl.layout0, opLayout)
    }

    override fun renderCall(ctx: IntrinsicCallContext): String? {
        YamlRuleCatalogs.CoreIntrinsics.atomicRulesByName[ctx.canonicalName]?.callAction?.let { action ->
            return action.render(
                rawAbi = ctx.rawAbi,
                onImport = { CoreIntrinsicLowering.registerCoreImport(ctx.emitter, it) },
            ) { key ->
                when (key) {
                    "coreNs" -> ctx.coreNs
                    "ord0" -> ctx.orderingCode(0)
                    else -> null
                }
            }
        }
        return when (ctx.canonicalName) {
            "atomic_load" -> {
                val pointee = ctx.pointeeAt(0) ?: return null
                val ord0 = ctx.orderingCode(0)
                DirectLoadIntrinsicEmitter("kmAtomicLoad", false, false, false, listOf(ord0), ctx.intrPkg, ctx.ptrPkg, ctx.emitter)
                    .render(pointee, ctx.rawAddrOf(0))
                    ?: ctx.callMem("atomicLoadWithLayout", ctx.rawAddrOf(0), ord0, ctx.layoutOf(pointee))
            }
            "atomic_store" -> {
                val pointee = ctx.pointeeAt(0) ?: return null
                val ord0 = ctx.orderingCode(0)
                DirectStoreIntrinsicEmitter("kmAtomicStore", false, false, false, listOf(ord0), ctx.intrPkg, ctx.ptrPkg, ctx.emitter)
                    .render(pointee, ctx.rawAddrOf(0), ctx.renderedArgs[1])
                    ?: ctx.callMem("atomicStoreWithLayout", ctx.rawAddrOf(0), ctx.renderedArgs[1], ord0, ctx.layoutOf(pointee))
            }
            "atomic_xchg" -> {
                val pointee = ctx.pointeeAt(0) ?: return null
                val ord0 = ctx.orderingCode(0)
                DirectReadModifyWriteIntrinsicEmitter("kmAtomicXchg", listOf(ord0), ctx.intrPkg, ctx.ptrPkg, ctx.emitter)
                    .render(pointee, ctx.rawAddrOf(0), ctx.renderedArgs[1])
                    ?: ctx.callMem("atomicXchgWithLayout", ctx.rawAddrOf(0), ctx.renderedArgs[1], ord0, ctx.layoutOf(pointee))
            }
            "atomic_cxchg", "atomic_cxchgweak" -> {
                val pointee = ctx.pointeeAt(0) ?: return null
                val ord0 = ctx.orderingCode(0)
                val ord1 = ctx.orderingCode(1)
                DirectCompareExchangeIntrinsicEmitter(ctx.intrPkg, ctx.ptrPkg, ctx.emitter)
                    .render(pointee, ctx.rawAddrOf(0), ctx.renderedArgs[1], ctx.renderedArgs[2], ord0, ord1)
                    ?: ctx.callMem("atomicCxchgWithLayout", ctx.rawAddrOf(0), ctx.renderedArgs[1], ctx.renderedArgs[2], ord0, ord1, ctx.layoutOf(pointee))
            }
            in RMW_OP_CODES -> {
                val opCode = RMW_OP_CODES.getValue(ctx.canonicalName).toString()
                val pointee = ctx.pointeeAt(0) ?: return null
                val operandType = ctx.explicitTypes.getOrNull(1) ?: pointee
                val ord0 = ctx.orderingCode(0)
                val direct =
                    if (ctx.explicitTypes.size <= 1) {
                        DirectReadModifyWriteIntrinsicEmitter("kmAtomicRmw", listOf(opCode, ord0), ctx.intrPkg, ctx.ptrPkg, ctx.emitter)
                            .render(pointee, ctx.rawAddrOf(0), ctx.renderedArgs[1])
                    } else {
                        null
                    }
                direct ?: ctx.callMem("atomicRmwWithLayout", ctx.rawAddrOf(0), ctx.renderedArgs[1], opCode, ord0, ctx.layoutOf(pointee), ctx.layoutOf(operandType))
            }
            else -> null
        }
    }
}

private object NumericAndFloatIntrinsicFamilyRule : CoreIntrinsicFamilyRule() {
    override fun matches(canonicalName: String, isCoreSimdCall: Boolean): Boolean =
        canonicalName in INT_INTRINSIC_RULES_BY_NAME || canonicalName in FLOAT_INTRINSIC_RULES_BY_NAME

    override fun renderSynthesizedDecl(decl: SynthesizedIntrinsicDeclContext): String? =
        INT_INTRINSIC_RULES_BY_NAME[decl.canonicalName]?.renderSynthesizedBody(decl)
            ?: FLOAT_INTRINSIC_RULES_BY_NAME[decl.canonicalName]?.renderSynthesizedBody(decl)

    override fun renderCall(ctx: IntrinsicCallContext): String? =
        when (ctx.canonicalName) {
            in INT_INTRINSIC_RULES_BY_NAME -> renderIntOrBitIntrinsicCall(ctx)
            in FLOAT_INTRINSIC_RULES_BY_NAME -> renderFloatIntrinsicCall(ctx)
            else -> null
        }
}

private object TransmuteIntrinsicFamilyRule : CoreIntrinsicFamilyRule() {
    override fun matches(canonicalName: String, isCoreSimdCall: Boolean): Boolean =
        canonicalName == "transmute" || canonicalName == "transmute_unchecked"

    override fun renderSynthesizedDecl(decl: SynthesizedIntrinsicDeclContext): String =
        decl.callMem("transmuteWithLayouts", decl.p0, decl.layout0, decl.layout1)

    override fun renderCall(ctx: IntrinsicCallContext): String? =
        ctx.callArgsList.singleOrNull()?.let { arg0 ->
            CoreIntrinsicLowering.renderTransmuteCall(
                rawTransmuteIdent = ctx.rawName,
                explicitTypes = ctx.explicitTypes,
                argExpr = arg0,
                indent = ctx.indent,
                selfTypeName = ctx.selfTypeName,
                emitter = ctx.emitter,
            )
        }
}

private object SimdIntrinsicFamilyRule : CoreIntrinsicFamilyRule() {
    val NAMES: Set<String> = YamlRuleCatalogs.CoreIntrinsics.simdIntrinsicNames
    val BOOL_RETURN_NAMES: Set<String> = YamlRuleCatalogs.CoreIntrinsics.simdBoolReturnNames
    val UNIT_RETURN_NAMES: Set<String> = YamlRuleCatalogs.CoreIntrinsics.simdUnitReturnNames
    val SAME_ARG0_NAMES: Set<String> = YamlRuleCatalogs.CoreIntrinsics.simdSameArg0Names
    private val SAME_ARG1_NAMES: Set<String> = YamlRuleCatalogs.CoreIntrinsics.simdSameArg1Names
    private val SAME_ARG2_NAMES: Set<String> = YamlRuleCatalogs.CoreIntrinsics.simdSameArg2Names
    private val NO_PEER_INFER_EXPECTED: Set<String> = YamlRuleCatalogs.CoreIntrinsics.simdNoPeerInferExpected
    private val NO_EXPECTED_TARGET_PROPAGATION: Set<String> = YamlRuleCatalogs.CoreIntrinsics.simdNoExpectedTargetPropagation
    private val NO_PEER_INFER_RETURN: Set<String> = NO_PEER_INFER_EXPECTED + NO_EXPECTED_TARGET_PROPAGATION

    fun isSimdIntrinsicName(name: String): Boolean = name in NAMES || name.startsWith("sve_tuple_")

    override fun matches(canonicalName: String, isCoreSimdCall: Boolean): Boolean =
        isCoreSimdCall || isSimdIntrinsicName(canonicalName)

    private fun extractDirectNonTransmuteArgType(
        argExpr: Expr,
        selfTypeName: String?,
        emitter: KotlinJ2KEmitter,
        unwrapParensForCheck: Boolean,
    ): String? {
        val checkedCall = (if (unwrapParensForCheck) emitter.unwrapParenOrGroupExpr(argExpr) else argExpr) as? Expr.Call
        val rawCallName = (checkedCall?.func as? Expr.Path)?.path?.lastCleanIdent()
        if (rawCallName == "transmute" || rawCallName == "transmute_unchecked") return null
        return emitter.inferExprRenderedType(argExpr, selfTypeName)?.takeIf(CoreIntrinsicLowering::isUsableRenderedTypeArg)
    }

    private fun extractSingleArgTransmuteInputType(
        argExpr: Expr,
        selfTypeName: String?,
        emitter: KotlinJ2KEmitter,
    ): String? {
        val callArg = emitter.unwrapParenOrGroupExpr(argExpr) as? Expr.Call ?: return null
        val callPath = (callArg.func as? Expr.Path)?.takeIf { it.qself == null }?.path ?: return null
        val callName = callPath.lastCleanIdent()
        if ((callName == "transmute" || callName == "transmute_unchecked") && callArg.args.len() == 1) {
            return emitter.inferExprRenderedType(callArg.args.first()!!, selfTypeName)?.takeIf(CoreIntrinsicLowering::isUsableRenderedTypeArg)
        }
        return null
    }

    fun resolveExpectedArgTarget(
        spec: CoreIntrinsicCallSpec,
        selfTypeName: String?,
        emitter: KotlinJ2KEmitter,
    ): String? {
        val canInferFromPeerArgs = spec.canonicalName !in NO_PEER_INFER_EXPECTED
        return spec.renderedExplicitTypeAt(0, selfTypeName, emitter)?.takeIf(CoreIntrinsicLowering::isUsableRenderedTypeArg)
            ?: if (canInferFromPeerArgs) {
                spec.callArgsList.firstNotNullOfOrNull { extractDirectNonTransmuteArgType(it, selfTypeName, emitter, unwrapParensForCheck = false) }
            } else {
                null
            }
            ?: if (spec.canonicalName in SAME_ARG0_NAMES && spec.canonicalName !in NO_EXPECTED_TARGET_PROPAGATION) {
                val candidate =
                    emitter.currentExpectedParseTargetType
                        ?: if (emitter.callArgumentNestingDepth == 0) emitter.currentEnclosingReturnRenderedType else null
                candidate?.takeIf(CoreIntrinsicLowering::isUsableRenderedTypeArg)
            } else {
                null
            }
            ?: if (canInferFromPeerArgs) {
                spec.callArgsList.firstNotNullOfOrNull { extractSingleArgTransmuteInputType(it, selfTypeName, emitter) }
            } else {
                null
            }
    }

    fun inferReturnRenderedType(
        spec: CoreIntrinsicCallSpec,
        selfTypeName: String?,
        emitter: KotlinJ2KEmitter,
    ): String? {
        val canonical = spec.canonicalName
        if (canonical.startsWith("sve_tuple_")) return null
        if (canonical !in NAMES) return null
        return when (canonical) {
            "simd_splat" -> spec.renderedExplicitTypeAt(0, selfTypeName, emitter)
            "simd_shuffle" -> spec.renderedExplicitTypeAt(2, selfTypeName, emitter)
            "simd_cast", "simd_as", "simd_bitmask" -> spec.renderedExplicitTypeAt(1, selfTypeName, emitter)
            "simd_select" ->
                spec.renderedExplicitTypeAt(1, selfTypeName, emitter)
                    ?: spec.callArgsList.getOrNull(1)?.let { emitter.inferExprRenderedType(it, selfTypeName) }
            in BOOL_RETURN_NAMES -> "Boolean"
            in UNIT_RETURN_NAMES -> "Unit"
            in SAME_ARG0_NAMES ->
                spec.renderedExplicitTypeAt(0, selfTypeName, emitter)?.takeIf(CoreIntrinsicLowering::isUsableRenderedTypeArg)
                    ?: (
                        if (canonical !in NO_PEER_INFER_RETURN) {
                            spec.callArgsList.firstNotNullOfOrNull {
                                extractDirectNonTransmuteArgType(it, selfTypeName, emitter, unwrapParensForCheck = true)
                            }
                        } else {
                            null
                        }
                    )
                    ?: spec.callArgsList.firstOrNull()?.let { arg0 ->
                        emitter.inferExprRenderedType(arg0, selfTypeName)?.takeIf(CoreIntrinsicLowering::isUsableRenderedTypeArg)
                            ?: extractSingleArgTransmuteInputType(arg0, selfTypeName, emitter)
                    }
            in SAME_ARG1_NAMES -> spec.callArgsList.getOrNull(1)?.let { emitter.inferExprRenderedType(it, selfTypeName) }
            in SAME_ARG2_NAMES -> spec.callArgsList.getOrNull(2)?.let { emitter.inferExprRenderedType(it, selfTypeName) }
            else ->
                spec.renderedExplicitTypeAt(0, selfTypeName, emitter)
                    ?: spec.callArgsList.firstOrNull()?.let { emitter.inferExprRenderedType(it, selfTypeName) }
        }
    }

    override fun renderSynthesizedDecl(decl: SynthesizedIntrinsicDeclContext): String? {
        if (!isSimdIntrinsicName(decl.canonicalName)) return null
        if (decl.canonicalName in BOOL_RETURN_NAMES) return "false"
        if (decl.canonicalName in UNIT_RETURN_NAMES) return "Unit"
        val retSynTy = (decl.fnItem.sig.output as? ReturnType.TypeReturn)?.ty
        val retKtIdent =
            (retSynTy?.let(::unwrapSynType) as? SynType.Path)
                ?.takeIf { it.qself == null && it.path.segments.size == 1 }
                ?.path
                ?.firstKotlinTypeName()
        val layoutIdent =
            when {
                retKtIdent != null && retKtIdent in decl.typeParams -> StructLayoutComputer.typeLayoutParamName(retKtIdent)
                decl.typeParams.isNotEmpty() -> StructLayoutComputer.typeLayoutParamName(decl.typeParams.last())
                else -> null
            }
        if (layoutIdent != null) {
            return CoreIntrinsicLowering.renderZeroedLayoutReadExpression(layoutIdent, decl.intrPkg, decl.emitter)
        }
        return if (retSynTy != null) {
            decl.emitter.renderFallbackDefaultForRenderedType(decl.emitter.renderType(retSynTy, null))
        } else {
            "Unit"
        }
    }

    override fun renderCall(ctx: IntrinsicCallContext): String? {
        if (!ctx.isCoreSimdCall) return null
        val renderedArgs = ctx.renderedArgs
        val canonicalName = ctx.canonicalName

        fun wrapWithSideEffects(resultExpr: String): String {
            if (renderedArgs.isEmpty()) return resultExpr
            val evalPrefix = renderedArgs.mapIndexed { idx, arg -> "val simdArg$idx = $arg" }.joinToString("; ")
            return "run { $evalPrefix; $resultExpr }"
        }
        if (canonicalName in BOOL_RETURN_NAMES) return wrapWithSideEffects("false")
        if (canonicalName in UNIT_RETURN_NAMES) return wrapWithSideEffects("Unit")
        val sameArgIndex =
            when (canonicalName) {
                in SAME_ARG0_NAMES -> 0
                in SAME_ARG1_NAMES -> 1
                in SAME_ARG2_NAMES -> 2
                else -> -1
            }
        if (sameArgIndex in renderedArgs.indices) {
            val valBind = renderedArgs.mapIndexed { idx, arg -> "val simdArg$idx = $arg" }.joinToString("; ")
            return "run { $valBind; simdArg$sameArgIndex }"
        }
        val explicitRetSlot =
            when (canonicalName) {
                "simd_splat" -> ctx.explicitTypes.nonInferAt(0)
                "simd_shuffle" -> ctx.explicitTypes.nonInferAt(2)
                else -> ctx.explicitTypes.nonInferAt(1)
            }
        if (explicitRetSlot != null) {
            val renderedRet = ctx.emitter.renderType(explicitRetSlot, ctx.selfTypeName)
            val fallback = ctx.emitter.renderFallbackDefaultForRenderedType(renderedRet)
            if (!fallback.startsWith("null as ") && !fallback.startsWith("error(")) {
                return wrapWithSideEffects(fallback)
            }
            val layoutExpr = ctx.layoutOf(explicitRetSlot)
            return wrapWithSideEffects(CoreIntrinsicLowering.renderZeroedLayoutReadExpression(layoutExpr, ctx.intrPkg, ctx.emitter))
        }
        val expectedRendered =
            (ctx.emitter.currentExpectedParseTargetType ?: ctx.emitter.currentEnclosingReturnRenderedType)
                ?.trim()
                ?.takeIf { it.isNotEmpty() && it != "Unit" }
        if (expectedRendered != null) {
            val fallback = ctx.emitter.renderFallbackDefaultForRenderedType(expectedRendered)
            if (!fallback.startsWith("null as ") && !fallback.startsWith("error(")) {
                return wrapWithSideEffects(fallback)
            }
            val layoutExpr = CoreIntrinsicLowering.resolveRenderedTypeLayoutExpression(expectedRendered, ctx.emitter)
            return wrapWithSideEffects(CoreIntrinsicLowering.renderZeroedLayoutReadExpression(layoutExpr, ctx.intrPkg, ctx.emitter))
        }
        if (renderedArgs.isNotEmpty()) {
            val valBind = renderedArgs.mapIndexed { idx, arg -> "val simdArg$idx = $arg" }.joinToString("; ")
            return "run { $valBind; simdArg0 }"
        }
        return "Unit"
    }
}

/**
 * Lowers Rust compiler-supplied `core::intrinsics`, `core::arch`, and `alloc` global allocator
 * boundaries into:
 * - Tier A: Pure `Long` address arithmetic and compile-time/runtime `TypeLayout<T>` queries.
 * - Tier B: `static inline __attribute__((always_inline))` C11/SIMD intrinsics (`kmLoad*`, `kmStore*`,
 *   `kmVolatile*`, `kmCopy*`, `kmWriteBytes`, `kmCompareBytes`, `kmAtomic*`, bit/math builtins).
 * - Tier C: Architecture-specific `.S` assembly routines (`kmSpinLoopHint`, `kmBlackBoxU64`,
 *   `kmUmulhU64`, `kmSmulhI64`, `kmAtomicCxchgU128Pair`).
 */
public object CoreIntrinsicLowering {
    public const val DEFAULT_CORE_NAMESPACE: String = "io.github.kotlinmania.rustlang.core"
    public const val DEFAULT_ALLOC_NAMESPACE: String = "io.github.kotlinmania.rustlang.alloc"

    public val STDLIB_CORE_FFI_SYMBOLS: Map<String, String> =
        YamlRuleCatalogs.CoreIntrinsics.stdlibCoreFfiSymbols

    public fun defaultStdlibFfiSymbolPackage(
        ktSym: String,
        coreNs: String,
        allocNs: String,
    ): String =
        when (ktSym) {
            "CString" -> "$allocNs.ffi.cstr"
            "CStr" -> "$coreNs.ffi.cstr"
            "CVoid" -> "$coreNs.ffi"
            else -> "$coreNs.ffi.primitives"
        }

    private val FIRST_TYPE_ARG_LAYOUT_INTRINSICS: Set<String> =
        YamlRuleCatalogs.CoreIntrinsics.firstTypeArgLayoutIntrinsics

    private val TWO_TYPE_ARG_LAYOUT_INTRINSICS: Set<String> =
        YamlRuleCatalogs.CoreIntrinsics.twoTypeArgLayoutIntrinsics

    internal val GENERIC_FLOAT_UNARY_KM: Map<String, Pair<String, String>> =
        YamlRuleCatalogs.CoreIntrinsics.genericFloatUnaryKm

    internal val SUFFIXED_FLOAT_UNARY_KM: Map<String, String> =
        YamlRuleCatalogs.CoreIntrinsics.suffixedFloatUnaryKm

    internal val SUFFIXED_FLOAT_BINARY_KM: Map<String, String> =
        YamlRuleCatalogs.CoreIntrinsics.suffixedFloatBinaryKm

    internal val GENERIC_FLOAT_BINARY_OP_NAMES: Map<String, String> =
        YamlRuleCatalogs.CoreIntrinsics.genericFloatBinaryOpNames

    private val KNOWN_STDLIB_LAYOUT_FUNCTIONS: Set<String> =
        YamlRuleCatalogs.CoreIntrinsics.knownStdlibLayoutFunctions

    private val POINTED_LOAD_RETURN_INTRINSICS: Set<String> =
        YamlRuleCatalogs.CoreIntrinsics.pointedLoadReturnIntrinsics

    private val FIRST_ARG_RETURN_INTRINSICS: Set<String> =
        YamlRuleCatalogs.CoreIntrinsics.firstArgReturnIntrinsics

    private val USIZE_RETURN_INTRINSICS: Set<String> =
        YamlRuleCatalogs.CoreIntrinsics.usizeReturnIntrinsics

    private val FAMILY_RULES: List<CoreIntrinsicFamilyRule> =
        listOf(
            ControlAndHintIntrinsicFamilyRule,
            LayoutAndReflectionIntrinsicFamilyRule,
            PointerAndMemoryIntrinsicFamilyRule,
            AtomicMemoryIntrinsicFamilyRule,
            NumericAndFloatIntrinsicFamilyRule,
            TransmuteIntrinsicFamilyRule,
            SimdIntrinsicFamilyRule,
        )

    private fun resolveStdlibNamespace(headerContext: FileHeaderContext, crateKey: String, defaultNs: String): String {
        val pkg = headerContext.packageName?.trim().orEmpty()
        if (pkg == defaultNs || pkg.startsWith("$defaultNs.")) return defaultNs
        val configured = headerContext.crateNamespaces[crateKey]?.trim()?.removeSuffix(".")
        return if (!configured.isNullOrEmpty()) configured else defaultNs
    }

    public fun resolveCoreNamespace(headerContext: FileHeaderContext): String =
        resolveStdlibNamespace(headerContext, "core", DEFAULT_CORE_NAMESPACE)

    public fun resolveAllocNamespace(headerContext: FileHeaderContext): String =
        resolveStdlibNamespace(headerContext, "alloc", DEFAULT_ALLOC_NAMESPACE)

    public fun layoutConsumingTypeArgIndices(
        intrinsicName: String,
        explicitTypeArgCount: Int,
    ): List<Int> {
        val canonical = parseLegacyAtomicOrderingSuffix(intrinsicName).baseName
        return when {
            canonical in SimdIntrinsicFamilyRule.BOOL_RETURN_NAMES || canonical in SimdIntrinsicFamilyRule.UNIT_RETURN_NAMES -> emptyList()
            SimdIntrinsicFamilyRule.isSimdIntrinsicName(canonical) -> (0 until explicitTypeArgCount).toList()
            canonical in TWO_TYPE_ARG_LAYOUT_INTRINSICS ->
                if (explicitTypeArgCount >= 2) listOf(0, 1) else listOf(0)
            canonical in FIRST_TYPE_ARG_LAYOUT_INTRINSICS -> listOf(0)
            else -> emptyList()
        }
    }

    private data class ParsedIntrinsicName(
        val baseName: String,
        val ord0: String? = null,
        val ord1: String? = null,
    )

    private val LEGACY_ORDERING_CODES: Map<String, String> =
        YamlRuleCatalogs.CoreIntrinsics.legacyOrderingCodes

    private fun parseLegacyAtomicOrderingSuffix(rawName: String): ParsedIntrinsicName {
        if (!rawName.startsWith("atomic_")) return ParsedIntrinsicName(rawName)
        for ((suf2, code2) in LEGACY_ORDERING_CODES) {
            if (rawName.endsWith("_$suf2")) {
                val withoutLast = rawName.removeSuffix("_$suf2")
                for ((suf1, code1) in LEGACY_ORDERING_CODES) {
                    if (withoutLast.endsWith("_$suf1")) {
                        val base2 = withoutLast.removeSuffix("_$suf1")
                        if (base2 == "atomic_cxchg" || base2 == "atomic_cxchgweak") {
                            return ParsedIntrinsicName(base2, code1, code2)
                        }
                    }
                }
                if (withoutLast in AtomicMemoryIntrinsicFamilyRule.RMW_OP_CODES ||
                    withoutLast in AtomicMemoryIntrinsicFamilyRule.NON_RMW_NAMES
                ) {
                    return ParsedIntrinsicName(withoutLast, code2, code2)
                }
            }
        }
        return ParsedIntrinsicName(rawName)
    }

    internal var activeRustcIntrinsicDeclarationName: String? = null

    internal fun registerCoreImport(emitter: KotlinJ2KEmitter, fqSymbol: String) {
        if (emitter.headerContext.isDocExample) return
        val targetPkg = fqSymbol.substringBeforeLast('.', missingDelimiterValue = "")
        if (targetPkg.isNotEmpty() && targetPkg != emitter.headerContext.packageName) {
            emitter.extraTypePathImports += "import $fqSymbol"
        }
    }

    internal fun unwrapTargetOrPointeeType(
        explicitTypes: List<SynType>,
        callArgsList: List<Expr>,
        emitter: KotlinJ2KEmitter,
    ): SynType? =
        explicitTypes.nonInferAt(0)
            ?: callArgsList.firstOrNull()?.let { firstArg ->
                when (val argTy = emitter.sourceInitializerType(firstArg)) {
                    is SynType.Ptr -> argTy.elem
                    is SynType.Reference -> argTy.elem
                    else -> argTy
                }
            }

    internal fun coreIntrinsicSignatureParameters(
        sig: Signature,
        block: Block?,
        ownerGenerics: Generics?,
        selfTypeName: String?,
        emitter: KotlinJ2KEmitter,
    ): List<Pair<String, String>> {
        val rawFnName = sig.cleanFnName()
        if (activeRustcIntrinsicDeclarationName == rawFnName && !emitter.headerContext.rawAbiMode) {
            val genericParams = sig.generics.params.toList()
            val typeParams =
                genericParams
                    .filterIsInstance<GenericParam.TypeParam>()
                    .map { rustTypeIdentToKotlin(it.ident.toString()) }
            val indices = layoutConsumingTypeArgIndices(rawFnName, typeParams.size)
            if (indices.isNotEmpty()) {
                val coreNs = resolveCoreNamespace(emitter.headerContext)
                registerCoreImport(emitter, "$coreNs.mem.TypeLayout")
                return indices.mapNotNull { idx ->
                    val ktParam = typeParams.getOrNull(idx) ?: return@mapNotNull null
                    StructLayoutComputer.typeLayoutParamName(ktParam) to "TypeLayout<$ktParam>"
                }
            }
        }
        return emitter.computeRequiredTypeLayoutParameters(
            sig = sig,
            block = block,
            ownerGenerics = ownerGenerics,
            selfTypeName = selfTypeName,
        )
    }

    internal fun coreIntrinsicCallArguments(
        call: Expr.Call,
        selfTypeName: String?,
        emitter: KotlinJ2KEmitter,
    ): List<String> {
        if (emitter.headerContext.rawAbiMode) return emptyList()
        val spec = resolveCoreIntrinsicCallSpec(call, emitter) ?: return emptyList()
        val rawName = spec.rawName
        val penultName = spec.penultName
        val explicitTypes = spec.explicitTypes
        val callArgsList = spec.callArgsList
        val segCount = ((call.func as? Expr.Path)?.path?.segments?.size) ?: 0

        // 1. Same-file free or local function requiring `typeLayout<P>`
        if (segCount == 1) {
            val sameFileFn = emitter.sameFileUniqueTopFn(rawName)
            if (sameFileFn != null) {
                val required =
                    emitter.computeRequiredTypeLayoutParameters(
                        sig = sameFileFn.sig,
                        block = sameFileFn.block,
                        ownerGenerics = null,
                        selfTypeName = null,
                    )
                if (required.isNotEmpty()) {
                    val sigGenerics = sameFileFn.sig.generics
                    val fnGenericParams = sigGenerics.params.toList()
                    val fnTypeParams =
                        fnGenericParams
                            .filterIsInstance<GenericParam.TypeParam>()
                            .map { it.ident.toString().removePrefix("r#") }
                    val fnInputs = sameFileFn.sig.inputs.toList()
                    val typedInputs = fnInputs.filterIsInstance<FnArg.Typed>()
                    return required.mapNotNull { (reqLayoutParamName, _) ->
                        val calleeKtParam = reqLayoutParamName.removePrefix("typeLayout")
                        val paramIdx = fnTypeParams.indexOfFirst { rustTypeIdentToKotlin(it) == calleeKtParam }
                        val rawParamName = fnTypeParams.getOrNull(paramIdx) ?: return@mapNotNull null
                        val specified = explicitTypes.nonInferAt(paramIdx)
                        val inferredFromArg =
                            if (specified == null) {
                                val argIdx =
                                    typedInputs.indexOfFirst { input ->
                                        val ty = input.patType.ty
                                        (ty is SynType.Path && ty.path.isIdent(rawParamName)) ||
                                            (ty is SynType.Ptr && (ty.elem as? SynType.Path)?.path?.isIdent(rawParamName) == true)
                                    }
                                val initTy = callArgsList.getOrNull(argIdx)?.let { emitter.sourceInitializerType(it) }
                                if (initTy is SynType.Ptr && typedInputs.getOrNull(argIdx)?.patType?.ty is SynType.Ptr) {
                                    initTy.elem
                                } else {
                                    initTy
                                }
                            } else {
                                null
                            }
                        val actualTy = specified ?: inferredFromArg ?: return@mapNotNull null
                        "$reqLayoutParamName = ${resolveTypeLayoutExpression(actualTy, selfTypeName, emitter)}"
                    }
                }
            }
        }

        // 2. Stdlib layout-consuming calls (`size_of::<T>()`, `align_of::<T>()`, `ptr::read(p)`, `Layout::new::<T>()`, etc.)
        val isLayoutNewOrArray = penultName == "Layout" && (rawName == "new" || rawName == "array")
        val isStdlibMemOrPtrFn =
            rawName in KNOWN_STDLIB_LAYOUT_FUNCTIONS &&
                (segCount == 1 || penultName in setOf("mem", "ptr")) &&
                rawName !in emitter.inspector.declaredTopLevelFnRawNames
        if (isLayoutNewOrArray || isStdlibMemOrPtrFn) {
            if (isStdlibMemOrPtrFn &&
                "core" in emitter.headerContext.crateNamespaces &&
                penultName != "ptr" &&
                rawName in setOf("size_of", "align_of", "size_of_val", "align_of_val")
            ) {
                registerCoreImport(emitter, "${resolveCoreNamespace(emitter.headerContext)}.mem.${rustFunctionIdentToKotlin(rawName)}")
            }
            val targetSynType = unwrapTargetOrPointeeType(explicitTypes, callArgsList, emitter)
            if (targetSynType != null) {
                return listOf("typeLayoutT = ${resolveTypeLayoutExpression(targetSynType, selfTypeName, emitter)}")
            }
        }

        // 3. Cross-crate / local calls to synthesized `core::intrinsics` layout-consuming functions
        if (spec.isIntrinsicsModuleCall && rawName !in emitter.inspector.declaredTopLevelFnRawNames) {
            val canonical = spec.canonicalName
            val minTypeArgs =
                when (canonical) {
                    in TWO_TYPE_ARG_LAYOUT_INTRINSICS -> maxOf(explicitTypes.size, 2)
                    in FIRST_TYPE_ARG_LAYOUT_INTRINSICS -> maxOf(explicitTypes.size, 1)
                    else -> explicitTypes.size
                }
            val indices = layoutConsumingTypeArgIndices(rawName, minTypeArgs)
            if (indices.isNotEmpty()) {
                val argSynType = unwrapTargetOrPointeeType(emptyList(), callArgsList, emitter)
                return indices.mapNotNull { idx ->
                    val synTy = explicitTypes.nonInferAt(idx) ?: (if (idx == 0) argSynType else null) ?: return@mapNotNull null
                    val layoutExpr = resolveTypeLayoutExpression(synTy, selfTypeName, emitter)
                    val paramName =
                        when {
                            idx == 0 && canonical in setOf("transmute", "transmute_unchecked") -> "typeLayoutSrc"
                            idx == 1 && canonical in setOf("transmute", "transmute_unchecked") -> "typeLayoutDst"
                            idx == 0 && canonical == "float_to_int_unchecked" -> "typeLayoutFloat"
                            idx == 1 && canonical == "float_to_int_unchecked" -> "typeLayoutInt"
                            idx == 0 -> "typeLayoutT"
                            idx == 1 -> "typeLayoutU"
                            else -> "typeLayoutT$idx"
                        }
                    "$paramName = $layoutExpr"
                }
            }
        }
        return emptyList()
    }

    internal fun resolveTypeLayoutExpression(
        type: SynType,
        selfTypeName: String?,
        emitter: KotlinJ2KEmitter,
    ): String {
        emitter.sourcePointerLayout(type, selfTypeName)?.let { return it }
        val resolved = emitter.resolveLayoutType(type, emptyMap())
        return resolveRenderedTypeLayoutExpression(emitter.renderType(resolved, selfTypeName), emitter)
    }

    internal fun resolveRenderedTypeLayoutExpression(
        renderedType: String,
        emitter: KotlinJ2KEmitter,
    ): String {
        val memPkg = "${resolveCoreNamespace(emitter.headerContext)}.mem"
        val clean = renderedType.trim()
        emitter.lookupInScopeTypeLayoutLocal(clean, memPkg)?.let { return it }
        return emitter.resolveStructOrUnresolvedTypeLayoutExpr(clean, memPkg)
    }

    internal fun renderConstEvalSelectMacro(
        mac: Macro,
        emitter: KotlinJ2KEmitter,
        indent: String,
        selfTypeName: String?,
    ): String? {
        val macroName = mac.path.lastCleanIdent() ?: return null
        if (macroName == "assert_unsafe_precondition") return "Unit"
        if (macroName != "const_eval_select") return null
        val cursor = RustTokenCursor(mac.tokens.clone().toList())
        // Form 1: `const_eval_select!(@capture { ... } -> Ret: if const { ... } else { runtime_body })`
        for (idx in cursor.trees.indices) {
            if (cursor.identAt(idx) == "else") {
                val elseGroup = ((idx + 1) until cursor.size).firstNotNullOfOrNull { cursor.groupAt(it, Delimiter.Brace) }
                if (elseGroup != null) {
                    val blockStream = TokenStream.fromTokenTrees(listOf(TokenTree.Group(elseGroup)))
                    val parsedExpr = parse2(ExprParse::parse, blockStream).getOrNull()
                    if (parsedExpr != null) return emitter.renderExpr(parsedExpr, indent, selfTypeName)
                }
            }
        }
        // Form 2: `const_eval_select!((arg0, arg1), const_fn, runtime_fn)`
        val topArgs = cursor.splitByTopLevelPunct(',')
        if (topArgs.size >= 3) {
            val tupleTree = topArgs[0].singleOrNull() as? TokenTree.Group
            val callArgsGroup =
                RustTokenCursor.groupStreamTree(
                    Delimiter.Parenthesis,
                    tupleTree?.value?.stream() ?: TokenStream.new(),
                )
            val callStream = TokenStream.fromTokenTrees(topArgs[2] + callArgsGroup)
            val parsedCall = parse2(ExprParse::parse, callStream).getOrNull()
            if (parsedCall != null) return emitter.renderExpr(parsedCall, indent, selfTypeName)
        }
        return null
    }

    internal fun renderRustcIntrinsicVerbatimItem(
        item: Item.Verbatim,
        emitter: KotlinJ2KEmitter,
        indent: String,
    ): String? {
        val cursor = RustTokenCursor(item.tokens.clone().toList())
        if (cursor.size < 4 || cursor.punctCharAt(cursor.size - 1) != ';' || !cursor.hasOuterAttributeIdent("rustc_intrinsic")) {
            return null
        }
        val fnTokens = ArrayList<TokenTree>(cursor.size)
        fnTokens.addAll(cursor.trees.subList(0, cursor.size - 1))
        fnTokens += RustTokenCursor.groupTree(Delimiter.Brace)
        val parsedFn = (parse2(ItemParse::parse, TokenStream.fromTokenTrees(fnTokens)).getOrNull() as? Item.Fn) ?: return null
        val rawFnName = parsedFn.sig.cleanFnName()
        val synthesizedBody = renderSynthesizedIntrinsicFnBody(parsedFn, rawFnName, emitter) ?: return null
        val prevIntrinsicDecl = activeRustcIntrinsicDeclarationName
        activeRustcIntrinsicDeclarationName = rawFnName
        val fnWithStubBody =
            try {
                emitter.renderItemFn(parsedFn, indent, mutableMapOf())
            } finally {
                activeRustcIntrinsicDeclarationName = prevIntrinsicDecl
            }
        val emptyBraceIdx = fnWithStubBody.lastIndexOf("{}")
        return if (emptyBraceIdx >= 0) {
            fnWithStubBody.substring(0, emptyBraceIdx) + "{\n$indent    return $synthesizedBody\n$indent}"
        } else {
            fnWithStubBody
        }
    }

    private fun renderSynthesizedIntrinsicFnBody(
        fnItem: Item.Fn,
        rawName: String,
        emitter: KotlinJ2KEmitter,
    ): String? {
        val fnInputs = fnItem.sig.inputs.toList()
        val paramIdents =
            fnInputs.filterIsInstance<FnArg.Typed>().mapIndexed { idx, input ->
                val raw = (input.patType.pat as? Pat.Ident)?.ident?.toString()?.removePrefix("r#")
                if (raw == null || raw == "_") "arg$idx" else rustValueIdentToKotlin(raw)
            }
        val sigGenerics = fnItem.sig.generics
        val genericParams = sigGenerics.params.toList()
        val typeParams = genericParams.filterIsInstance<GenericParam.TypeParam>().map { rustTypeIdentToKotlin(it.ident.toString()) }
        val constParams = genericParams.filterIsInstance<GenericParam.ConstParam>().map { rustValueIdentToKotlin(it.ident.toString()) }
        val parsedAtomic = parseLegacyAtomicOrderingSuffix(rawName)
        val decl =
            SynthesizedIntrinsicDeclContext(
                fnItem = fnItem,
                rawName = rawName,
                canonicalName = parsedAtomic.baseName,
                paramIdents = paramIdents,
                typeParams = typeParams,
                ord0 = constParams.getOrNull(0)?.let { "($it).ordinal" } ?: parsedAtomic.ord0 ?: "4",
                ord1 = constParams.getOrNull(1)?.let { "($it).ordinal" } ?: parsedAtomic.ord1 ?: "4",
                coreNs = resolveCoreNamespace(emitter.headerContext),
                emitter = emitter,
            )
        val matchedRule = FAMILY_RULES.firstOrNull { it.matches(decl.canonicalName) } ?: return null
        return matchedRule.renderSynthesizedDecl(decl)
    }

    internal fun renderZeroedLayoutReadExpression(
        layoutExpr: String,
        intrPkg: String,
        emitter: KotlinJ2KEmitter,
    ): String {
        registerCoreImport(emitter, "$intrPkg.kmAllocZeroed")
        registerCoreImport(emitter, "$intrPkg.kmDealloc")
        return "run { val ptr = kmAllocZeroed(maxOf(1L, ($layoutExpr).sizeBytes), maxOf(1L, ($layoutExpr).alignBytes)); " +
            "val res = ($layoutExpr).readNative(ptr); " +
            "kmDealloc(ptr, maxOf(1L, ($layoutExpr).sizeBytes), maxOf(1L, ($layoutExpr).alignBytes)); res }"
    }

    private fun resolveCoreIntrinsicCallSpec(
        call: Expr.Call,
        emitter: KotlinJ2KEmitter,
    ): CoreIntrinsicCallSpec? {
        val path = (call.func as? Expr.Path)?.takeIf { it.qself == null }?.path ?: return null
        val segments = path.segments.toList()
        if (segments.isEmpty()) return null
        val lastSeg = segments.last()
        val rawName = lastSeg.ident.toString().removePrefix("r#")
        val rewrittenSegNames =
            emitter.rewriteCrateRootAliasAndReexportSegments(
                segments.map { it.ident.toString().removePrefix("r#") },
            )
        val penultName = rewrittenSegNames.getOrNull(rewrittenSegNames.size - 2)?.removePrefix("r#")
        val isIntrinsicsModuleCall =
            penultName == "intrinsics" ||
                "intrinsics" in rewrittenSegNames.dropLast(1) ||
                (segments.size == 1 && emitter.headerContext.packageName?.endsWith(".intrinsics") == true)
        val genericArgs = (lastSeg.arguments as? PathArguments.AngleBracketed)?.args?.toList().orEmpty()
        val parsedAtomic = parseLegacyAtomicOrderingSuffix(rawName)
        return CoreIntrinsicCallSpec(
            rawName = rawName,
            canonicalName = parsedAtomic.baseName,
            ord0 = parsedAtomic.ord0,
            ord1 = parsedAtomic.ord1,
            isIntrinsicsModuleCall = isIntrinsicsModuleCall,
            penultName = penultName,
            rewrittenSegNames = rewrittenSegNames,
            explicitTypes = genericArgs.filterIsInstance<GenericArgument.TypeArg>().map { it.type },
            explicitConsts = genericArgs.filterIsInstance<GenericArgument.ConstArg>().map { it.expr },
            callArgsList = call.args.toList(),
        )
    }

    internal fun inferCoreIntrinsicReturnSynType(
        call: Expr.Call,
        emitter: KotlinJ2KEmitter,
    ): SynType? {
        val spec = resolveCoreIntrinsicCallSpec(call, emitter) ?: return null
        if (!spec.isIntrinsicsModuleCall) return null
        return when (spec.canonicalName) {
            in POINTED_LOAD_RETURN_INTRINSICS -> spec.inferredPointeeTypeAt0(emitter)
            "offset", "arith_offset" ->
                spec.callArgsList.firstOrNull()?.let { emitter.sourceInitializerType(it) }
            in FIRST_ARG_RETURN_INTRINSICS,
            in GENERIC_FLOAT_UNARY_KM,
            in GENERIC_FLOAT_BINARY_OP_NAMES,
            ->
                spec.explicitTypes.nonInferAt(0)
                    ?: spec.callArgsList.firstOrNull()?.let { emitter.sourceInitializerType(it) }
            "transmute", "transmute_unchecked", "float_to_int_unchecked" ->
                spec.explicitTypes.nonInferAt(1)
            else -> null
        }
    }

    internal fun isUsableRenderedTypeArg(ty: String?): Boolean {
        val clean = ty?.trim() ?: return false
        if (clean.isEmpty()) return false
        if (clean in setOf("Any", "Any?", "Nothing", "RustAny", "_")) return false
        if ('*' in clean) return false
        if ("->" in clean && !clean.startsWith("(")) return false
        return !clean.startsWith("Result.Err")
    }

    internal fun renderTransmuteCall(
        rawTransmuteIdent: String,
        explicitTypes: List<SynType>,
        argExpr: Expr,
        indent: String,
        selfTypeName: String?,
        emitter: KotlinJ2KEmitter,
    ): String? {
        val srcExplicitSyn = explicitTypes.nonInferAt(0)
        val dstExplicitSyn = explicitTypes.nonInferAt(1)
        val expectedTarget = emitter.currentExpectedParseTargetType?.trim()?.takeIf { it.isNotEmpty() }
        val srcSyn = srcExplicitSyn ?: emitter.sourceInitializerType(argExpr)
        val dstSyn =
            dstExplicitSyn
                ?: if (expectedTarget == null && emitter.callArgumentNestingDepth == 0 && !emitter.inLocalInitializerPosition) {
                    emitter.currentEnclosingReturnSynType
                } else {
                    null
                }
        val prevParseTarget = emitter.currentExpectedParseTargetType
        emitter.currentExpectedParseTargetType = srcExplicitSyn?.let { emitter.renderType(it, selfTypeName) }
        val renderedArg =
            try {
                emitter.renderExpr(argExpr, indent, selfTypeName)
            } finally {
                emitter.currentExpectedParseTargetType = prevParseTarget
            }
        val srcRendered =
            (srcSyn?.let { emitter.renderType(it, selfTypeName) } ?: emitter.inferExprRenderedType(argExpr, selfTypeName))
                ?.takeIf(::isUsableRenderedTypeArg)
        val dstRendered =
            (
                dstSyn?.let { emitter.renderType(it, selfTypeName) }
                    ?: expectedTarget
                    ?: if (emitter.callArgumentNestingDepth == 0 && !emitter.inLocalInitializerPosition) {
                        emitter.currentEnclosingReturnRenderedType
                    } else {
                        null
                    }
            )?.takeIf(::isUsableRenderedTypeArg)
        val coreNs = resolveCoreNamespace(emitter.headerContext)
        val memPkg = "$coreNs.mem"
        if (srcRendered != null && dstRendered != null) {
            if (srcRendered == dstRendered && srcRendered != "Unit") return renderedArg
            if (srcSyn != null && dstSyn != null) {
                renderFastScalarTransmute(srcSyn, dstSyn, renderedArg, "$coreNs.ptr", emitter)?.let { return it }
            }
            val fromField = emitter.transparentPointerField(srcRendered, selfTypeName)
            if (fromField != null && fromField.second == dstRendered) return "($renderedArg).${fromField.first}"
            val toField = emitter.transparentPointerField(dstRendered, selfTypeName)
            if (toField != null && toField.second == srcRendered) return "$dstRendered($renderedArg)"
            val srcLayout = srcSyn?.let { resolveTypeLayoutExpression(it, selfTypeName, emitter) } ?: resolveRenderedTypeLayoutExpression(srcRendered, emitter)
            val dstLayout = dstSyn?.let { resolveTypeLayoutExpression(it, selfTypeName, emitter) } ?: resolveRenderedTypeLayoutExpression(dstRendered, emitter)
            registerCoreImport(emitter, "$memPkg.transmuteWithLayouts")
            return "transmuteWithLayouts<$srcRendered, $dstRendered>($renderedArg, $srcLayout, $dstLayout)"
        }
        if ("transmute" !in emitter.inspector.declaredTopLevelFnRawNames &&
            "transmute_unchecked" !in emitter.inspector.declaredTopLevelFnRawNames
        ) {
            if (srcRendered == null && dstRendered != null) {
                val dstLayout = dstSyn?.let { resolveTypeLayoutExpression(it, selfTypeName, emitter) } ?: resolveRenderedTypeLayoutExpression(dstRendered, emitter)
                registerCoreImport(emitter, "$memPkg.transmuteWithLayouts")
                registerCoreImport(emitter, "$memPkg.UnresolvedTypeLayout")
                return "transmuteWithLayouts<Any?, $dstRendered>($renderedArg, UnresolvedTypeLayout<Any?>(), $dstLayout)"
            }
            val fnName = if (rawTransmuteIdent == "transmute_unchecked") "transmuteUnchecked" else "transmute"
            if (emitter.headerContext.packageName != memPkg) registerCoreImport(emitter, "$memPkg.$fnName")
            return "$fnName($renderedArg)"
        }
        return null
    }

    private fun isCoreSimdCallSite(
        spec: CoreIntrinsicCallSpec,
        segCount: Int,
        emitter: KotlinJ2KEmitter,
        allowsimdSubpath: Boolean,
    ): Boolean {
        val directMatch =
            SimdIntrinsicFamilyRule.isSimdIntrinsicName(spec.canonicalName) &&
                (
                    spec.isIntrinsicsModuleCall ||
                        spec.penultName == "simd" ||
                        spec.penultName == "scalable" ||
                        (segCount == 1 && spec.rawName !in emitter.inspector.declaredTopLevelFnRawNames)
                )
        return directMatch || (allowsimdSubpath && spec.isIntrinsicsModuleCall && "simd" in spec.rewrittenSegNames.dropLast(1))
    }

    internal fun inferCoreIntrinsicReturnRenderedType(
        call: Expr.Call,
        selfTypeName: String?,
        emitter: KotlinJ2KEmitter,
    ): String? {
        val spec = resolveCoreIntrinsicCallSpec(call, emitter) ?: return null
        if (spec.rawName in setOf("__rust_alloc", "__rust_alloc_zeroed", "__rust_realloc")) return "MutPtr"
        val segCount = ((call.func as? Expr.Path)?.path?.segments?.size) ?: 0
        val isCoreSimdCall = isCoreSimdCallSite(spec, segCount, emitter, allowsimdSubpath = false)
        if (!spec.isIntrinsicsModuleCall && !isCoreSimdCall) return null
        val canonical = spec.canonicalName
        if (canonical in setOf("ctpop", "ctlz", "ctlz_nonzero", "cttz", "cttz_nonzero")) return "UInt"
        if (canonical in setOf("needs_drop", "raw_eq", "likely", "unlikely")) return "Boolean"
        if (canonical in USIZE_RETURN_INTRINSICS) {
            return when {
                emitter.currentExpectedNumericKind != null -> null
                emitter.headerContext.rawAbiMode -> "ULong"
                else -> "Int"
            }
        }
        if (canonical in setOf("add_with_overflow", "sub_with_overflow", "mul_with_overflow")) {
            val elemTy =
                spec.explicitTypes.nonInferAt(0)?.let { emitter.renderType(it, selfTypeName) }
                    ?: spec.callArgsList.firstOrNull()?.let { emitter.inferExprRenderedType(it, selfTypeName) }
                    ?: resolveIntrinsicNumericKind(spec.explicitTypes.firstOrNull(), spec.callArgsList.take(2), selfTypeName, emitter)
                        ?.let(emitter::numericKindToPureTypeName)
                    ?: return null
            return "Pair<$elemTy, Boolean>"
        }
        if (canonical in setOf("atomic_cxchg", "atomic_cxchgweak")) {
            val pointeeTy = spec.inferredPointeeTypeAt0(emitter)
            val elemTy =
                pointeeTy?.let { emitter.renderType(it, selfTypeName) }
                    ?: spec.callArgsList.getOrNull(1)?.let { emitter.inferExprRenderedType(it, selfTypeName) }
                    ?: return null
            return "Pair<$elemTy, Boolean>"
        }
        val suffixedKm = SUFFIXED_FLOAT_UNARY_KM[canonical] ?: SUFFIXED_FLOAT_BINARY_KM[canonical]
        if (suffixedKm != null) return if (suffixedKm.endsWith("f32")) "Float" else "Double"
        if (canonical in setOf("fmaf32", "fmuladdf32", "minimumf32", "maximumf32")) return "Float"
        if (canonical in setOf("fmaf64", "fmuladdf64", "minimumf64", "maximumf64")) return "Double"
        inferCoreIntrinsicReturnSynType(call, emitter)?.let { return emitter.renderType(it, selfTypeName) }
        if (canonical in FIRST_ARG_RETURN_INTRINSICS || canonical in GENERIC_FLOAT_UNARY_KM || canonical in GENERIC_FLOAT_BINARY_OP_NAMES) {
            return spec.callArgsList.firstOrNull()?.let { emitter.inferExprRenderedType(it, selfTypeName) }
        }
        if (canonical in setOf("transmute", "transmute_unchecked", "float_to_int_unchecked")) {
            return spec.explicitTypes.nonInferAt(1)?.let { emitter.renderType(it, selfTypeName) }
        }
        return SimdIntrinsicFamilyRule.inferReturnRenderedType(spec, selfTypeName, emitter)
    }

    internal val RUST_GLOBAL_ALLOC_FN_NAMES: Set<String> =
        YamlRuleCatalogs.CoreIntrinsics.rustGlobalAllocActionsByName.keys

    internal fun renderRustGlobalAllocExpr(
        rawFnName: String,
        emitter: KotlinJ2KEmitter,
        rawAbiMode: Boolean,
        ptrRawAddressAt: (Int) -> String,
        sizeToLongAt: (Int) -> String,
        alignToLongAt: (Int) -> String,
    ): String? {
        val action = YamlRuleCatalogs.CoreIntrinsics.rustGlobalAllocActionsByName[rawFnName] ?: return null
        val coreNs = resolveCoreNamespace(emitter.headerContext)
        return action.render(
            rawAbi = rawAbiMode,
            onImport = { registerCoreImport(emitter, it) },
        ) { key ->
            when (key) {
                "coreNs" -> coreNs
                "ptr0" -> ptrRawAddressAt(0)
                "size0" -> sizeToLongAt(0)
                "size1" -> sizeToLongAt(1)
                "size3" -> sizeToLongAt(3)
                "align1" -> alignToLongAt(1)
                "align2" -> alignToLongAt(2)
                else -> null
            }
        }
    }

    private fun renderGlobalAllocOrHintCall(
        spec: CoreIntrinsicCallSpec,
        emitter: KotlinJ2KEmitter,
        indent: String,
        selfTypeName: String?,
    ): String? {
        val rawName = spec.rawName
        if (rawName in RUST_GLOBAL_ALLOC_FN_NAMES) {
            val args = spec.callArgsList.map { emitter.renderExpr(it, indent, selfTypeName) }
            return renderRustGlobalAllocExpr(
                rawFnName = rawName,
                emitter = emitter,
                rawAbiMode = false,
                ptrRawAddressAt = { idx -> "(${args[idx]}).rawAddress" },
                sizeToLongAt = { idx -> "(${args[idx]}).toLong()" },
                alignToLongAt = { idx ->
                    val alignSyn = spec.callArgsList.getOrNull(idx)
                    val alignTy = alignSyn?.let { emitter.inferExprRenderedType(it, selfTypeName) }?.removeSuffix("?")?.trim()
                    val methodCall = alignSyn?.let { emitter.unwrapParenOrGroupExpr(it) } as? Expr.MethodCall
                    val isAlignmentMethodCall = methodCall?.method?.toString()?.removePrefix("r#") == "alignment"
                    if (alignTy == "Alignment" || alignTy?.endsWith(".Alignment") == true || (alignTy == null && isAlignmentMethodCall)) {
                        "(${args[idx]}).asUsize().toLong()"
                    } else {
                        "(${args[idx]}).toLong()"
                    }
                },
            )
        }
        if (spec.penultName == "hint" && rawName == "spin_loop" && spec.callArgsList.isEmpty()) {
            registerCoreImport(emitter, "${resolveCoreNamespace(emitter.headerContext)}.intrinsics.kmSpinLoopHint")
            return "kmSpinLoopHint()"
        }
        if (spec.penultName == "hint" && rawName == "black_box" && spec.callArgsList.size == 1) {
            val firstArgExpr = spec.callArgsList[0]
            val argRendered = emitter.renderExpr(firstArgExpr, indent, selfTypeName)
            return when (emitter.inferExprNumericKind(firstArgExpr, selfTypeName)) {
                NumericKind.U64 -> {
                    registerCoreImport(emitter, "${resolveCoreNamespace(emitter.headerContext)}.intrinsics.kmBlackBoxU64")
                    "kmBlackBoxU64($argRendered)"
                }
                NumericKind.I64 -> {
                    registerCoreImport(emitter, "${resolveCoreNamespace(emitter.headerContext)}.intrinsics.kmBlackBoxU64")
                    "kmBlackBoxU64(($argRendered).toULong()).toLong()"
                }
                else -> argRendered
            }
        }
        return null
    }

    internal fun renderCoreIntrinsicCall(
        call: Expr.Call,
        emitter: KotlinJ2KEmitter,
        indent: String,
        selfTypeName: String?,
    ): String? {
        val path = (call.func as? Expr.Path)?.takeIf { it.qself == null }?.path ?: return null
        val segCount = path.segments.size
        if (segCount == 0) return null
        val spec = resolveCoreIntrinsicCallSpec(call, emitter) ?: return null
        renderGlobalAllocOrHintCall(spec, emitter, indent, selfTypeName)?.let { return it }

        val isCoreSimdCall = isCoreSimdCallSite(spec, segCount, emitter, allowsimdSubpath = true)
        if (!spec.isIntrinsicsModuleCall && !isCoreSimdCall) return null

        val simdExpectedArgTarget = if (isCoreSimdCall) SimdIntrinsicFamilyRule.resolveExpectedArgTarget(spec, selfTypeName, emitter) else null
        val renderedArgs =
            if (spec.canonicalName == "transmute" || spec.canonicalName == "transmute_unchecked") {
                emptyList()
            } else {
                renderIntrinsicCallArguments(spec, simdExpectedArgTarget, emitter, indent, selfTypeName)
            }
        val ctx =
            IntrinsicCallContext(
                spec = spec,
                renderedArgs = renderedArgs,
                indent = indent,
                selfTypeName = selfTypeName,
                coreNs = resolveCoreNamespace(emitter.headerContext),
                isCoreSimdCall = isCoreSimdCall,
                emitter = emitter,
            )
        val matchedRule = FAMILY_RULES.firstOrNull { it.matches(ctx.canonicalName, isCoreSimdCall) } ?: return null
        return matchedRule.renderCall(ctx)
    }

    private fun renderIntrinsicCallArguments(
        spec: CoreIntrinsicCallSpec,
        simdExpectedArgTarget: String?,
        emitter: KotlinJ2KEmitter,
        indent: String,
        selfTypeName: String?,
    ): List<String> {
        emitter.callArgumentNestingDepth++
        return try {
            if (simdExpectedArgTarget != null) {
                spec.callArgsList.mapIndexed { argIdx, argExpr ->
                    val shouldPropagate =
                        when (spec.canonicalName) {
                            "simd_shuffle" -> argIdx < 2
                            "simd_extract", "simd_insert" -> argIdx == 0
                            else -> true
                        }
                    val prevTarget = emitter.currentExpectedParseTargetType
                    emitter.currentExpectedParseTargetType = if (shouldPropagate) simdExpectedArgTarget else null
                    try {
                        emitter.renderExpr(argExpr, indent, selfTypeName)
                    } finally {
                        emitter.currentExpectedParseTargetType = prevTarget
                    }
                }
            } else {
                val prevTarget = emitter.currentExpectedParseTargetType
                emitter.currentExpectedParseTargetType = null
                try {
                    spec.callArgsList.map { emitter.renderExpr(it, indent, selfTypeName) }
                } finally {
                    emitter.currentExpectedParseTargetType = prevTarget
                }
            }
        } finally {
            emitter.callArgumentNestingDepth--
        }
    }

    internal fun renderFastScalarTransmute(
        srcType: SynType,
        dstType: SynType,
        valueExpr: String,
        ptrPkg: String,
        emitter: KotlinJ2KEmitter,
    ): String? {
        val srcResolved = emitter.resolveLayoutType(srcType, emptyMap())
        val dstResolved = emitter.resolveLayoutType(dstType, emptyMap())
        val srcPrim = explicitScalarRustName(srcResolved, emitter)
        val dstPrim = explicitScalarRustName(dstResolved, emitter)
        if (srcPrim != null && srcPrim == dstPrim) return valueExpr
        if (srcResolved is SynType.Ptr && dstResolved is SynType.Ptr) {
            if (emitter.headerContext.rawAbiMode) return valueExpr
            val ctor = if (dstResolved.mutability is PointerMutability.Mut) "MutPtr" else "ConstPtr"
            registerCoreImport(emitter, "$ptrPkg.$ctor")
            return "$ctor(($valueExpr).rawAddress)"
        }
        if (srcPrim == null || dstPrim == null) return null
        val tmpl = YamlRuleCatalogs.CoreIntrinsics.fastScalarTransmuteTemplates["$srcPrim->$dstPrim"] ?: return null
        return tmpl.render { key ->
            when (key) {
                "val" -> valueExpr
                "f32Type" -> emitter.kotlinF32TypeName()
                else -> null
            }
        }
    }

    internal fun explicitScalarRustName(type: SynType?, emitter: KotlinJ2KEmitter): String? {
        if (type == null) return null
        val resolved = emitter.resolveLayoutType(type, emptyMap())
        val path = (resolved as? SynType.Path)?.takeIf { it.qself == null && it.path.segments.size == 1 }?.path ?: return null
        val ident = path.lastCleanIdent() ?: return null
        return ident.takeIf {
            it in setOf("bool", "char", "i8", "u8", "i16", "u16", "i32", "u32", "i64", "u64", "i128", "u128", "isize", "usize", "f16", "f32", "f64", "f128")
        }
    }

    private fun staticScalarByteWidth(type: SynType, emitter: KotlinJ2KEmitter): Long? {
        val resolved = emitter.resolveLayoutType(type, emptyMap())
        if (resolved is SynType.Ptr) return (emitter.activeCfgProfile.pointerWidth / 8).toLong()
        return when (explicitScalarRustName(resolved, emitter)) {
            "bool", "i8", "u8" -> 1L
            "i16", "u16", "f16" -> 2L
            "i32", "u32", "f32", "char" -> 4L
            "i64", "u64", "f64" -> 8L
            "isize", "usize" -> (emitter.activeCfgProfile.pointerWidth / 8).toLong()
            "i128", "u128", "f128" -> 16L
            else -> null
        }
    }

    internal fun rustScalarToNumericKind(prim: String, emitter: KotlinJ2KEmitter): NumericKind? =
        when (prim) {
            "i8" -> NumericKind.I8
            "u8" -> NumericKind.U8
            "i16" -> NumericKind.I16
            "u16" -> NumericKind.U16
            "i32" -> NumericKind.I32
            "u32" -> NumericKind.U32
            "i64" -> NumericKind.I64
            "u64" -> NumericKind.U64
            "i128" -> NumericKind.I128
            "u128" -> NumericKind.U128
            "isize" -> if (emitter.headerContext.rawAbiMode) NumericKind.I64 else NumericKind.I32
            "usize" -> if (emitter.headerContext.rawAbiMode) NumericKind.U64 else NumericKind.U32
            "f16" -> NumericKind.F16
            "f32" -> NumericKind.F32
            "f64" -> NumericKind.F64
            "f128" -> NumericKind.F128
            else -> null
        }

    internal fun resolveIntrinsicNumericKind(
        explicitType: SynType?,
        argExprs: List<Expr>,
        selfTypeName: String?,
        emitter: KotlinJ2KEmitter,
    ): NumericKind? {
        explicitType?.takeUnless { it is SynType.Infer }?.let { ty ->
            explicitScalarRustName(ty, emitter)?.let { rustScalarToNumericKind(it, emitter) }?.let { return it }
        }
        val firstNonLit = argExprs.firstOrNull { emitter.unwrapParenOrGroupExpr(it) !is Expr.Lit }
        if (firstNonLit != null) {
            emitter.inferExprNumericKind(firstNonLit, selfTypeName)?.let { return it }
        }
        for (arg in argExprs) {
            emitter.inferExprNumericKind(arg, selfTypeName)?.let { return it }
        }
        return null
    }

    internal fun renderScaledByteCountExpr(
        pointee: SynType?,
        countExpr: String,
        selfTypeName: String?,
        emitter: KotlinJ2KEmitter,
    ): String {
        val elemSize = pointee?.let { staticScalarByteWidth(it, emitter) }
        return when {
            elemSize == 1L -> "($countExpr).toLong()"
            elemSize != null -> "($countExpr).toLong() * ${elemSize}L"
            pointee != null -> "($countExpr).toLong() * (${resolveTypeLayoutExpression(pointee, selfTypeName, emitter)}).sizeBytes"
            else -> "($countExpr).toLong()"
        }
    }
}
