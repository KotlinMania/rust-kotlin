package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.procmacro2.TokenStream
import io.github.kotlinmania.rustkotlin.reused.starlark.AssignCount
import io.github.kotlinmania.syn.Block
import io.github.kotlinmania.syn.Expr
import io.github.kotlinmania.syn.FieldValue
import io.github.kotlinmania.syn.Member
import io.github.kotlinmania.syn.Pat
import io.github.kotlinmania.syn.RangeLimits
import io.github.kotlinmania.syn.Stmt
import io.github.kotlinmania.syn.TraitItem
import io.github.kotlinmania.syn.UnOp
import io.github.kotlinmania.syn.gen.Visit

internal fun KotlinJ2KEmitter.atomicRecvExpr(
    expr: Expr,
    rendered: String,
): String = if (isAtomicPostfixReceiver(expr, rendered)) rendered else "($rendered)"

internal fun KotlinJ2KEmitter.registerCoreMemberImport(
    subpackage: String,
    symbol: String,
    skipIfSamePackage: Boolean = false,
) {
    val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
    val pkg = if (subpackage.isEmpty()) coreNs else "$coreNs.$subpackage"
    if (!skipIfSamePackage || headerContext.packageName != pkg) {
        CoreIntrinsicLowering.registerCoreImport(this, "$pkg.$symbol")
    }
}

internal fun KotlinJ2KEmitter.extractSingleSegmentLocalKotlinName(expr: Expr): String? =
    (unwrapParenOrGroupExpr(expr) as? Expr.Path)
        ?.takeIf { it.qself == null && it.path.segments.size == 1 }
        ?.path
        ?.segments
        ?.first()
        ?.ident
        ?.toString()
        ?.let { raw -> scopeBuilder.resolveLocalKotlinName(raw.removePrefix("r#")) ?: rustValueIdentToKotlin(raw) }

internal fun KotlinJ2KEmitter.markNonNullChecked(
    sourceExpr: Expr,
    rendered: String,
    wrapNonAtomic: Boolean = true,
): String {
    val isSimpleLocal = rendered.isNotEmpty() && rendered.all { it.isLetterOrDigit() || it == '_' }
    if (isSimpleLocal && rendered in currentSmartCastNonNullLocals) {
        return rendered
    }
    if (isSimpleLocal) {
        currentSmartCastNonNullLocals = currentSmartCastNonNullLocals + rendered
    }
    val recv = if (wrapNonAtomic) atomicRecvExpr(sourceExpr, rendered) else rendered
    return "$recv!!"
}

private val NARROW_INTEGER_KINDS: Set<NumericKind> =
    setOf(NumericKind.U8, NumericKind.U16, NumericKind.I8, NumericKind.I16)

private fun KotlinJ2KEmitter.renderNarrowCoercedNumericExpr(
    operand: Expr,
    elemKind: NumericKind?,
    indent: String,
    selfTypeName: String?,
): String {
    if (elemKind == null) return renderExpr(operand, indent, selfTypeName)
    val rendered = renderTypedNumericInitializer(operand, elemKind, indent, selfTypeName)
    val expectedSuffix = NUMERIC_COERCE_SUFFIX_BY_KIND[elemKind]
    val alreadyConverted =
        expectedSuffix == null ||
            elemKind !in NARROW_INTEGER_KINDS ||
            rendered.endsWith(".$expectedSuffix")
    return if (!alreadyConverted && inferExprNumericKind(operand, selfTypeName) != elemKind) {
        coerceRenderedNumericExpr(operand, rendered, elemKind)
    } else {
        rendered
    }
}

private fun KotlinJ2KEmitter.isSafeInlineRepeatOperand(expr: Expr): Boolean =
    when (val core = unwrapParenOrGroupExpr(expr)) {
        is Expr.Lit, is Expr.Path, is Expr.Const -> true
        is Expr.Unary ->
            (core.op is UnOp.Neg || core.op is UnOp.NotOp) &&
                unwrapParenOrGroupExpr(core.expr) is Expr.Lit
        else -> false
    }

internal fun KotlinJ2KEmitter.isDirectDivergingRenderedExpr(
    expr: Expr,
    rendered: String,
): Boolean {
    val core = unwrapParenOrGroupExpr(expr)
    if (core is Expr.Return || core is Expr.Break || core is Expr.Continue) return true
    val trimmed = rendered.trim()
    return trimmed.startsWith("error(") || trimmed.startsWith("TODO(")
}

private fun KotlinJ2KEmitter.renderRepeatCollectionExpr(
    repExpr: Expr,
    elemText: String,
    lenText: String,
    ctorName: String,
): String {
    if (isSafeInlineRepeatOperand(repExpr)) {
        return "$ctorName($lenText) { $elemText }"
    }
    if (isDirectDivergingRenderedExpr(repExpr, elemText)) {
        return elemText
    }
    val tmp = "repeatElem${closureLabelCounter++}"
    return "run { val $tmp = $elemText; $ctorName($lenText) { $tmp } }"
}

private fun KotlinJ2KEmitter.renderArrayOrRepeatExpr(
    containerExpr: Expr,
    elems: List<Expr>?,
    repeatSpec: Pair<Expr, Expr>?,
    indent: String,
    selfTypeName: String?,
): String {
    val targetTy = currentExpectedParseTargetType?.removeSuffix("?")?.trim()
    val resolvedArrayTy =
        targetTy?.takeIf { it in PRIMITIVE_ARRAY_TYPES }
            ?: if (targetTy == null || (!targetTy.startsWith("List<") && !targetTy.startsWith("MutableList<") && !targetTy.startsWith("Slice<"))) {
                inferPrimitiveArrayTypeForArrayLiteral(containerExpr, selfTypeName)
            } else {
                null
            }
    if (resolvedArrayTy != null) {
        return renderStructPrimitiveArrayFieldInit(containerExpr, resolvedArrayTy, indent, selfTypeName)
    }
    val elemKind = targetTy?.let(::primitiveArrayElementNumericKind)
    val elemParseTarget = targetTy?.let { extractIndexedElementRenderedType(it, selfTypeName) }
    val prevParseTarget = currentExpectedParseTargetType
    currentExpectedParseTargetType = elemParseTarget
    val isMutableList = targetTy?.startsWith("MutableList<") == true
    return if (elems != null) {
        val renderedElems =
            try {
                elems.joinToString(", ") { renderNarrowCoercedNumericExpr(it, elemKind, indent, selfTypeName) }
            } finally {
                currentExpectedParseTargetType = prevParseTarget
            }
        if (isMutableList) "mutableListOf($renderedElems)" else "listOf($renderedElems)"
    } else {
        val (repExpr, lenExpr) = repeatSpec!!
        val renderedElem =
            try {
                renderNarrowCoercedNumericExpr(repExpr, elemKind, indent, selfTypeName)
            } finally {
                currentExpectedParseTargetType = prevParseTarget
            }
        val len = normalizeArrayLengthToIntExpr(renderTypedNumericInitializer(lenExpr, NumericKind.I32, indent, selfTypeName), lenExpr, selfTypeName)
        renderRepeatCollectionExpr(
            repExpr = repExpr,
            elemText = renderedElem,
            lenText = len,
            ctorName = if (isMutableList) "MutableList" else "List",
        )
    }
}

internal fun KotlinJ2KEmitter.renderExpr(expr: Expr, indent: String, selfTypeName: String?): String =
    when (expr) {
        is Expr.Lit -> renderLit(expr.lit)
        is Expr.Path -> renderExprPath(expr, selfTypeName)
        is Expr.Binary -> renderExprBinary(expr, indent, selfTypeName)
        is Expr.Unary -> renderExprUnary(expr, indent, selfTypeName)
        is Expr.Reference -> renderExprReference(expr, indent, selfTypeName)
        is Expr.RawAddr -> renderExprRawAddr(expr, indent, selfTypeName)
        is Expr.Paren -> {
            val innerExpr = expr.expr
            val rendered = renderExpr(innerExpr, indent, selfTypeName)
            if (innerExpr is Expr.Unary && innerExpr.op is UnOp.Deref) {
                rendered
            } else {
                "($rendered)"
            }
        }
        is Expr.Group -> renderExpr(expr.expr, indent, selfTypeName)
        is Expr.Try -> {
            val inner = renderExpr(expr.expr, indent, selfTypeName)
            val retKw = currentClosureLabel?.let { "return@$it" } ?: "return"
            val enclosingRet = currentEnclosingReturnRenderedType
            val exprInferredTy = inferExprRenderedType(expr.expr, selfTypeName)
            val exprBase = exprInferredTy.asKotlinRenderedType()?.simpleName
            val enclosingParsed = enclosingRet.asKotlinRenderedType()
            val enclosingBase = enclosingParsed?.simpleName
            if (enclosingRet == "Unit" || isFmtWriteOrUnitExpr(expr.expr, selfTypeName)) {
                inner
            } else if (exprBase == "ControlFlow" || (exprBase == null && enclosingBase == "ControlFlow")) {
                val cfPair = enclosingParsed?.takeIf { !it.isNullable }?.pairArgsOf("ControlFlow")
                val cfFirstArg = cfPair?.first
                val cfSecondArg = cfPair?.second
                renderTaggedTwoArmTry(
                    scrutineeExpr = inner,
                    returnKeyword = retKw,
                    sumTypeName = "ControlFlow",
                    continueArmSimple = "Continue",
                    continueAccessor = "cfContinueValue()",
                    earlyReturnArmSimple = "Break",
                    earlyReturnAccessor = "cfBreakValue()",
                    earlyReturnTypeArgs = buildExplicitResultTypeArgsSuffix(cfFirstArg, cfSecondArg),
                    castEarlyPayloadTo = cfFirstArg?.takeIf(::isUsableExplicitResultTypeArg),
                    isUntypedScrutinee = exprInferredTy != null && exprBase != "ControlFlow",
                )
            } else if (enclosingParsed != null &&
                enclosingParsed.isNullable &&
                !enclosingParsed.matchesSimple("Result", "RustResult")
            ) {
                "($inner ?: $retKw null)"
            } else {
                renderSourceResultTry(inner, retKw, exprInferredTy)
            }
        }
        is Expr.Await -> renderExpr(expr.base, indent, selfTypeName)
        is Expr.Cast -> renderExprCast(expr, indent, selfTypeName)
        is Expr.Call -> renderExprCall(expr, indent, selfTypeName)
        is Expr.MethodCall -> renderExprMethodCall(expr, indent, selfTypeName)
        is Expr.Field -> {
            val savedKind = currentExpectedNumericKind
            currentExpectedNumericKind = null
            val base =
                try {
                    renderExpr(expr.base, indent, selfTypeName)
                } finally {
                    currentExpectedNumericKind = savedKind
                }
            val baseRenderedTy = inferExprRenderedType(expr.base, selfTypeName)
            val safeBase =
                when {
                    mutableReferencePointeeType(baseRenderedTy) != null -> "($base).read()"
                    refCellGuardPointeeType(baseRenderedTy) != null -> "($base).value"
                    baseRenderedTy?.endsWith("?") == true && !base.endsWith("!!") ->
                        markNonNullChecked(expr.base, base, wrapNonAtomic = true)
                    else -> base
                }
            when (val m = expr.member) {
                is Member.Named -> "$safeBase.${rustFieldIdentToKotlin(m.ident.toString())}"
                is Member.Unnamed -> {
                    val idx = m.index.index.toInt()
                    val effectiveBaseTy =
                        baseRenderedTy
                            ?: if ((expr.base as? Expr.Path)?.path?.isIdent("self") == true) selfTypeName else null
                    val cleanBaseTy = effectiveBaseTy?.removeSuffix("?")?.trim()
                    if (cleanBaseTy == "List<Any?>" ||
                        cleanBaseTy?.startsWith("List<") == true ||
                        (idx >= 3 && cleanBaseTy?.startsWith("List<") != false)
                    ) {
                        val recv = if (safeBase.any { it == ' ' }) "($safeBase)" else safeBase
                        val elemTy =
                            resolveUnnamedTupleFieldSynType(expr.base, idx)
                                ?.let { renderType(it, selfTypeName) }
                                ?.takeIf { it.isNotEmpty() && it != "Any?" && it != "Any" && '*' !in it }
                        if (elemTy != null) "($recv[$idx] as $elemTy)" else "$recv[$idx]"
                    } else {
                        "$safeBase.${tupleAccessMemberName(expr.base, idx, selfTypeName)}"
                    }
                }
            }
        }
        is Expr.Index -> {
            val savedKind = currentExpectedNumericKind
            currentExpectedNumericKind = null
            try {
                val base = renderExpr(expr.expr, indent, selfTypeName)
                val indexExpr = expr.index
                if (indexExpr is Expr.Range) {
                    val start = indexExpr.start?.let { renderExpr(it, indent, selfTypeName) }
                    val end = indexExpr.end?.let { renderExpr(it, indent, selfTypeName) }
                    val op =
                        when (indexExpr.limits) {
                            is RangeLimits.HalfOpen -> " until "
                            is RangeLimits.Closed -> ".."
                        }
                    val baseTy = inferExprRenderedType(expr.expr, selfTypeName)?.removeSuffix("?")?.trim()
                    when {
                        baseTy == "String" ->
                            when {
                                start == null && end == null -> base
                                start != null && end != null -> "$base.substring($start$op$end)"
                                start != null -> "$base.substring($start)"
                                else -> "$base.substring(0$op$end)"
                            }
                        baseTy != null && baseTy in PRIMITIVE_ARRAY_TYPES ->
                            when {
                                start == null && end == null -> base
                                start != null && end != null -> "(($base).sliceArray($start$op$end))"
                                start != null -> "(($base).sliceArray($start until $base.size))"
                                else -> "(($base).sliceArray(0$op$end))"
                            }
                        headerContext.rawAbiMode ||
                            headerContext.finalClasses ||
                            baseTy?.startsWith("List<") == true ||
                            baseTy?.startsWith("MutableList<") == true -> {
                            val startIdx = start ?: "0"
                            val endExcl =
                                when {
                                    end == null -> "($base).size"
                                    indexExpr.limits is RangeLimits.Closed -> "(($end) + 1)"
                                    else -> end
                                }
                            when {
                                start == null && end == null -> base
                                else -> "(($base).subList($startIdx, $endExcl))"
                            }
                        }
                        else -> {
                            if (start != null || end != null) {
                                registerCoreMemberImport("mem", "get")
                            }
                            when {
                                start == null && end == null -> base
                                start != null && end != null -> "$base[$start$op$end]"
                                start != null -> "$base[$start until $base.size]"
                                else -> "$base[0$op$end]"
                            }
                        }
                    }
                } else {
                    val idx = renderExpr(indexExpr, indent, selfTypeName)
                    "$base[$idx]"
                }
            } finally {
                currentExpectedNumericKind = savedKind
            }
        }
        is Expr.Tuple -> {
            val elems = expr.elems.toList()
            val pairCtor = if ("Pair" in inspector.declaredTypeNames) "kotlin.Pair" else "Pair"
            val tripleCtor = if ("Triple" in inspector.declaredTypeNames) "kotlin.Triple" else "Triple"
            val hasTransmuteElem =
                elems.any { e ->
                    val unwrapped = unwrapParenOrGroupExpr(e)
                    unwrapped is Expr.Call &&
                        (unwrapped.func as? Expr.Path)
                            ?.path
                            ?.segments
                            ?.last()
                            ?.ident
                            ?.toString() in setOf("transmute", "transmute_unchecked")
                }
            val expectedTupleParsed =
                (
                    currentExpectedParseTargetType
                        ?: if (hasTransmuteElem) currentEnclosingReturnRenderedType else null
                ).asKotlinRenderedType()
            val expectedElemTypes =
                expectedTupleParsed
                    ?.takeIf {
                        elems.size in 2..3 &&
                            (
                                (elems.size == 2 && (it.headPath == "Pair" || it.headPath == "kotlin.Pair")) ||
                                    (elems.size == 3 && (it.headPath == "Triple" || it.headPath == "kotlin.Triple"))
                            ) &&
                            it.arity == elems.size
                    }?.typeArgs
            fun renderTupleElem(idx: Int): String {
                val elemTarget = expectedElemTypes?.get(idx)?.trim()?.takeIf { it.isNotEmpty() }
                if (elemTarget == null) return renderExpr(elems[idx], indent, selfTypeName)
                val savedParseTarget = currentExpectedParseTargetType
                val savedReturnTarget = currentEnclosingReturnRenderedType
                currentExpectedParseTargetType = elemTarget
                currentEnclosingReturnRenderedType = elemTarget
                return try {
                    renderExpr(elems[idx], indent, selfTypeName)
                } finally {
                    currentExpectedParseTargetType = savedParseTarget
                    currentEnclosingReturnRenderedType = savedReturnTarget
                }
            }
            when (elems.size) {
                0 -> "Unit"
                1 -> renderExpr(elems[0], indent, selfTypeName)
                2 -> "$pairCtor(${renderTupleElem(0)}, ${renderTupleElem(1)})"
                3 -> "$tripleCtor(${renderTupleElem(0)}, ${renderTupleElem(1)}, ${renderTupleElem(2)})"
                else -> "listOf(${elems.joinToString(", ") { renderExpr(it, indent, selfTypeName) }})"
            }
        }
        is Expr.Array -> renderArrayOrRepeatExpr(expr, expr.elems.toList(), null, indent, selfTypeName)
        is Expr.Repeat -> renderArrayOrRepeatExpr(expr, null, expr.expr to expr.len, indent, selfTypeName)
        is Expr.Struct -> renderExprStruct(expr, indent, selfTypeName)
        is Expr.Assign -> renderExprAssign(expr, indent, selfTypeName)
        is Expr.Range -> {
            val elemTy =
                (currentExpectedParseTargetType ?: currentEnclosingReturnRenderedType)
                    .asKotlinRenderedType()
                    ?.singleArgOf("RangeInclusive")
            val exprStart = expr.start
            val exprEnd = expr.end
            if (expr.limits is RangeLimits.Closed &&
                elemTy != null &&
                exprStart != null &&
                exprEnd != null
            ) {
                val elemKind = resolveNumericKind(elemTy)
                val savedKind = currentExpectedNumericKind
                val savedParseTarget = currentExpectedParseTargetType
                val (startRendered, endRendered) =
                    try {
                        currentExpectedNumericKind = elemKind
                        currentExpectedParseTargetType = elemTy
                        renderNarrowCoercedNumericExpr(exprStart, elemKind, indent, selfTypeName) to
                            renderNarrowCoercedNumericExpr(exprEnd, elemKind, indent, selfTypeName)
                    } finally {
                        currentExpectedNumericKind = savedKind
                        currentExpectedParseTargetType = savedParseTarget
                    }
                if ("RangeInclusive" !in inspector.declaredTypeNames &&
                    ("core" in headerContext.crateNamespaces || headerContext.packageName?.startsWith("io.github.kotlinmania.rustlang.") == true)
                ) {
                    val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
                    CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.ops.range.RangeInclusive")
                }
                "RangeInclusive($startRendered, $endRendered, false)"
            } else {
                val start = exprStart?.let { renderExpr(it, indent, selfTypeName) }
                val end = exprEnd?.let { renderExpr(it, indent, selfTypeName) }
                val op =
                    when (expr.limits) {
                        is RangeLimits.HalfOpen -> " until "
                        is RangeLimits.Closed -> ".."
                    }
                when {
                    start != null && end != null -> "$start$op$end"
                    start != null -> "$start${op}Int.MAX_VALUE"
                    end != null -> "0$op$end"
                    else -> "0 until 0"
                }
            }
        }
        is Expr.Closure -> renderExprClosure(expr, indent, selfTypeName)
        is Expr.If -> renderExprIf(expr, indent, selfTypeName)
        is Expr.While -> renderExprWhile(expr, indent, selfTypeName)
        is Expr.ForLoop -> renderExprForLoop(expr, indent, selfTypeName)
        is Expr.Loop -> {
            val body = renderBlock(expr.body, indent, selfTypeName)
            "while (true) $body"
        }
        is Expr.Match -> renderExprMatch(expr, indent, selfTypeName)
        is Expr.BlockExpr -> renderBlockAsExpression(expr.block, indent, selfTypeName)
        is Expr.Unsafe -> renderBlockAsExpression(expr.block, indent, selfTypeName)
        is Expr.Async -> renderBlockAsExpression(expr.block, indent, selfTypeName)
        is Expr.Const -> renderBlockAsExpression(expr.block, indent, selfTypeName)
        is Expr.Return -> {
            val retNumKind = currentEnclosingReturnRenderedType?.let(this::resolveNumericKind)
            val valExpr =
                expr.expr?.let { retArg ->
                    val savedRetTarget = currentExpectedParseTargetType
                    val savedInLocalInit = inLocalInitializerPosition
                    inLocalInitializerPosition = false
                    if (unwrapParenOrGroupExpr(retArg) is Expr.Macro && currentExpectedParseTargetType == null) {
                        currentExpectedParseTargetType = currentEnclosingReturnRenderedType
                    }
                    val rawRendered =
                        try {
                            renderTypedNumericInitializer(retArg, retNumKind, indent, selfTypeName)
                        } finally {
                            currentExpectedParseTargetType = savedRetTarget
                            inLocalInitializerPosition = savedInLocalInit
                        }
                    coerceTailFmtUnitToResultOk(retArg, rawRendered, selfTypeName)
                }
            val retKw = currentClosureLabel?.let { "return@$it" } ?: "return"
            if (valExpr != null) "$retKw $valExpr" else retKw
        }
        is Expr.Break -> {
            expr.expr?.let { renderExpr(it, indent, selfTypeName) }
            "break"
        }
        is Expr.Continue -> "continue"
        is Expr.Let -> renderIfLetCondition(expr.pat, expr.expr, indent, selfTypeName)
        is Expr.Macro ->
            CoreIntrinsicLowering.renderConstEvalSelectMacro(expr.mac, this, indent, selfTypeName)
                ?: renderMacroInvocationAsStmtOrExpr(expr.mac, indent, selfTypeName)
        is Expr.TryBlock -> renderBlockAsExpression(expr.block, indent, selfTypeName)
        is Expr.Yield -> {
            val valExpr = expr.expr?.let { renderExpr(it, indent, selfTypeName) }
            if (valExpr != null) "yield($valExpr)" else "yield()"
        }
        is Expr.Infer -> "_"
        is Expr.Verbatim -> renderVerbatimExprTokens(expr.tokens)
    }

private fun renderVerbatimExprTokens(stream: TokenStream): String {
    if (stream.isEmpty()) return ""
    return RustTokenStreamKotlinEmitter(stream.clone().toList()).render()
}

internal fun KotlinJ2KEmitter.renderBlockAsExpression(block: Block, indent: String, selfTypeName: String?): String {
    val stmts = block.stmts
    if (stmts.isEmpty()) return "Unit"
    if (stmts.size == 1) {
        val only = stmts[0]
        if (only is Stmt.ExprStmt && only.semiToken == null) {
            return renderExpr(only.expr, indent, selfTypeName)
        }
        if (only is Stmt.MacroStmt && only.semiToken == null) {
            return renderMacroInvocationAsStmtOrExpr(only.mac, indent, selfTypeName)
        }
    }
    val simplifiedOverwrite = tryExtractPureOverwrittenTailExpr(stmts)
    if (simplifiedOverwrite != null) {
        return renderExpr(simplifiedOverwrite, indent, selfTypeName)
    }
    return "run ${renderBlock(block, indent, selfTypeName)}"
}

internal fun KotlinJ2KEmitter.tryExtractPureOverwrittenTailExpr(stmts: List<Stmt>): Expr? {
    if (stmts.size < 2) return null
    val tailStmt = stmts.last() as? Stmt.ExprStmt ?: return null
    if (tailStmt.semiToken != null) return null
    val tailPath = tailStmt.expr as? Expr.Path ?: return null
    if (tailPath.qself != null || tailPath.path.segments.len() != 1) return null
    val tailIdent =
        tailPath.path.segments
            .first()
            ?.ident
            ?.toString()
            ?.removePrefix("r#") ?: return null

    var lastInitExpr: Expr? = null
    for (i in 0 until stmts.size - 1) {
        val local = stmts[i] as? Stmt.Local ?: return null
        val patIdent = local.pat as? Pat.Ident ?: return null
        if (patIdent.subpat != null) return null
        if (patIdent.ident.toString().removePrefix("r#") != tailIdent) return null
        val init = local.init ?: return null
        if (init.diverge != null) return null
        if (i < stmts.size - 2 && !isPureArithmeticOrSideEffectFreeExpr(init.expr)) {
            return null
        }
        lastInitExpr = init.expr
    }
    val finalExpr = lastInitExpr ?: return null
    if (exprReferencesIdent(finalExpr, tailIdent)) return null
    return finalExpr
}

internal fun KotlinJ2KEmitter.isPureArithmeticOrSideEffectFreeExpr(expr: Expr): Boolean =
    when (expr) {
        is Expr.Lit, is Expr.Path -> true
        is Expr.Paren -> isPureArithmeticOrSideEffectFreeExpr(expr.expr)
        is Expr.Group -> isPureArithmeticOrSideEffectFreeExpr(expr.expr)
        is Expr.Reference -> isPureArithmeticOrSideEffectFreeExpr(expr.expr)
        is Expr.Cast -> isPureArithmeticOrSideEffectFreeExpr(expr.expr)
        is Expr.Unary -> isPureArithmeticOrSideEffectFreeExpr(expr.expr)
        is Expr.Binary ->
            !isCompoundAssignOp(expr.op) &&
                isPureArithmeticOrSideEffectFreeExpr(expr.left) &&
                isPureArithmeticOrSideEffectFreeExpr(expr.right)
        else -> false
    }

internal fun KotlinJ2KEmitter.exprReferencesIdent(expr: Expr, identName: String): Boolean {
    val visitor =
        object : SynPathPredicateVisitor() {
            override fun visitExprPath(e: Expr.Path) {
                if (e.unqualifiedPath()?.singleSegment()?.cleanIdent() == identName) {
                    markMatched()
                    return
                }
                super.visitExprPath(e)
            }
        }
    visitor.visitExpr(expr)
    return visitor.matched
}

internal fun KotlinJ2KEmitter.tupleAccessMemberName(baseExpr: Expr, index: Int, selfTypeName: String?): String {
    val ownerBase = selfTypeName.asKotlinRenderedType()?.headPath
    if (baseExpr is Expr.Path && baseExpr.path.isIdent("self")) {
        if (ownerBase == "Pair") return if (index == 0) "first" else "second"
        if (ownerBase == "Triple") {
            return when (index) {
                0 -> "first"
                1 -> "second"
                else -> "third"
            }
        }
        val arity = ownerBase?.let { inspector.tupleStructArityByType[it] } ?: 1
        return tupleFieldPropertyName(index, arity)
    }
    val baseTy = inferExprRenderedType(baseExpr, selfTypeName).asKotlinRenderedType()?.simpleName
    val effectiveBaseTy =
        baseTy?.let { bt ->
            inspector.typeAliasTargets[bt]
                ?.let { renderType(it, null).asKotlinRenderedType()?.simpleName }
                ?.takeIf { it.isNotEmpty() }
                ?: bt
        }
    if (effectiveBaseTy == "Pair" || effectiveBaseTy == "Triple") {
        return when (index) {
            0 -> "first"
            1 -> "second"
            2 -> "third"
            else -> "field$index"
        }
    }
    val knownArity =
        effectiveBaseTy?.let {
            inspector.tupleStructArityByType[it] ?: headerContext.externalCrateTupleStructArity[it]
        }
    if (knownArity != null) {
        return tupleFieldPropertyName(index, knownArity)
    }
    val hasExternalSingleTupleValueField =
        effectiveBaseTy != null &&
            (
                scopedAbiKeysForRenderedType(effectiveBaseTy).any { key ->
                    "$key.value" in headerContext.crateStructFieldTypes &&
                        "$key.field1" !in headerContext.crateStructFieldTypes
                } ||
                    headerContext.crateStructFieldTypes.keys.any { k ->
                        (k == "$effectiveBaseTy.value" || k.endsWith(".$effectiveBaseTy.value"))
                    }
            )
    val hasExternalMultiField =
        effectiveBaseTy != null &&
            (
                scopedAbiKeysForRenderedType(effectiveBaseTy).any { key ->
                    "$key.field$index" in headerContext.crateStructFieldTypes ||
                        "$key.field1" in headerContext.crateStructFieldTypes
                } ||
                    headerContext.crateStructFieldTypes.keys.any { k ->
                        k == "$effectiveBaseTy.field$index" || k.endsWith(".$effectiveBaseTy.field$index")
                    }
            )
    if (hasExternalMultiField) {
        return "field$index"
    }
    if (effectiveBaseTy == "Wrapping" ||
        hasExternalSingleTupleValueField ||
        (
            ownerBase != null &&
                inspector.tupleStructArityByType[ownerBase] == 1 &&
                baseExpr is Expr.Path &&
                baseExpr.qself == null &&
                baseExpr.path.segments.len() == 1
        )
    ) {
        return if (index == 0) "value" else "field$index"
    }
    if (index >= 1 &&
        effectiveBaseTy != null &&
        effectiveBaseTy !in setOf("Any", "List", "MutableList", "ArrayList") &&
        effectiveBaseTy !in activeTypeParamNames
    ) {
        return "field$index"
    }
    return when (index) {
        0 -> "first"
        1 -> "second"
        2 -> "third"
        else -> "field$index"
    }
}

internal fun KotlinJ2KEmitter.renderExprStruct(expr: Expr.Struct, indent: String, selfTypeName: String?): String {
    val pathSegs = expr.path.segments
    val allSegStrings = pathSegs.toList().map { it.ident.toString() }
    val resolvedCrossTarget = resolveCrossModulePathTarget(allSegStrings, inTypePosition = true)
    val ctorPath =
        if (resolvedCrossTarget != null) {
            val (resolvedCrossPkg, trailing) = resolvedCrossTarget
            val boundHead = bindOrQualifyResolvedCrossSymbol(resolvedCrossPkg)
            if (trailing.isEmpty()) {
                boundHead
            } else {
                (listOf(boundHead) + trailing.map(::rustTypeIdentToKotlin)).joinToString(".")
            }
        } else {
            val rawSegments =
                expr.path.segments
                    .toList()
                    .filter { it.ident.toString() !in IGNORED_PATH_PREFIXES }
            val typeSegments =
                if (rawSegments.isEmpty()) {
                    emptyList()
                } else {
                    rawSegments.dropLast(1).dropWhile {
                        val s = it.ident.toString()
                        s.isNotEmpty() &&
                            s.first().isLowerCase() &&
                            s != "self" &&
                            !scopeBuilder.isImportedModuleInScope(s) &&
                            resolveImportedUppercaseTypeBinding(s) == null
                    } + rawSegments.last()
                }
            if (typeSegments.isEmpty()) {
                selfTypeName?.substringBefore('<')?.trim() ?: "Any"
            } else {
                val mappedSegs =
                    typeSegments.mapIndexed { idx, seg ->
                        val raw = seg.ident.toString()
                        val isLastSeg = (idx == typeSegments.lastIndex)
                        val importedTypeName = resolveImportedUppercaseTypeBinding(raw)
                        when {
                            raw == "Self" -> selfTypeName?.substringBefore('<')?.trim() ?: "Self"
                            importedTypeName != null -> importedTypeName
                            isLastSeg || raw.first().isUpperCase() -> rustTypeIdentToKotlin(raw)
                            scopeBuilder.isImportedModuleInScope(raw) -> scopeBuilder.resolveModuleBinding(raw)?.kotlinName ?: rustModuleIdentToKotlin(raw)
                            else -> rustValueIdentToKotlin(raw)
                        }
                    }
                mappedSegs.joinToString(".")
            }
        }
    val ctorBase = ctorPath.substringAfterLast('.').substringBefore('<').trim()
    if (!ctorPath.contains('.') &&
        ctorBase !in inspector.declaredTypeNames &&
        headerContext.crateSymbolSubpackages.isNotEmpty()
    ) {
        val bestSubpkg = resolveBestSubpackageForSymbol(ctorBase)
        val nsPrefix = currentNamespacePrefix()
        if (bestSubpkg != null && nsPrefix != null) {
            val targetPkg = if (bestSubpkg.isEmpty()) nsPrefix else "$nsPrefix.$bestSubpkg"
            if (targetPkg.isNotEmpty() && targetPkg != headerContext.packageName) {
                val existingPkg = importedSimpleNameToPkg[ctorBase]
                if (existingPkg == null || existingPkg == targetPkg) {
                    importedSimpleNameToPkg[ctorBase] = targetPkg
                    extraTypePathImports += "import $targetPkg.$ctorBase"
                }
            }
        }
    }
    val seenFieldNames = hashSetOf<String>()
    val fieldArgs =
        expr.fields.toList().mapNotNull { fv: FieldValue ->
            val fieldName =
                when (val m = fv.member) {
                    is Member.Named -> rustFieldIdentToKotlin(m.ident.toString())
                    is Member.Unnamed -> tupleFieldPropertyName(m.index.index.toInt(), expr.fields.len())
                }
            if (!seenFieldNames.add(fieldName)) return@mapNotNull null
            val fieldSynTy = inspector.structFieldSynTypesByType[ctorBase]?.get(fieldName)
            val renderedFieldTy =
                fieldSynTy?.let { renderType(it, selfTypeName) }
                    ?: scopedAbiKeysForRenderedType(ctorPath).firstNotNullOfOrNull { headerContext.crateStructFieldTypes["$it.$fieldName"] }
                    ?: headerContext.crateStructFieldTypes["$ctorBase.$fieldName"]
            val prevFieldParseTarget = currentExpectedParseTargetType
            currentExpectedParseTargetType = renderedFieldTy
            var valText =
                try {
                    if (renderedFieldTy != null && renderedFieldTy in PRIMITIVE_ARRAY_TYPES) {
                        renderStructPrimitiveArrayFieldInit(fv.expr, renderedFieldTy, indent, selfTypeName)
                    } else if (renderedFieldTy != null) {
                        renderTypedNumericInitializer(fv.expr, resolveNumericKind(renderedFieldTy), indent, selfTypeName)
                    } else {
                        renderExpr(fv.expr, indent, selfTypeName)
                    }
                } finally {
                    currentExpectedParseTargetType = prevFieldParseTarget
                }
            if (valText == "ArrayDeque()" && fieldSynTy != null && renderedFieldTy != null) {
                val traitBase = renderedFieldTy.substringBefore('<').trim()
                val traitDecl = lookupDeclaredTraitAst(traitBase)
                val iterSpec = traitDecl?.let { extractIteratorSupertraitSpec(it.supertraits.toList(), null) }
                if (traitDecl != null && iterSpec != null) {
                    val elemTy =
                        if ('<' in renderedFieldTy && renderedFieldTy.endsWith(">")) {
                            renderedFieldTy.substringAfter('<').removeSuffix(">").trim()
                        } else {
                            iterSpec.itemType
                        }
                    val methodStubs = mutableListOf<String>()
                    methodStubs += "override fun next(): $elemTy? = null"
                    if (iterSpec.hasDoubleEnded) {
                        methodStubs += "override fun nextBack(): $elemTy? = null"
                    }
                    if (iterSpec.hasExactSize) {
                        methodStubs += "override fun len(): Int = 0"
                    }
                    for (tItem in traitDecl.items) {
                        if (traitItemHasDocOnlyCfg(tItem)) continue
                        if (tItem is TraitItem.Fn && tItem.default == null) {
                            val mName = rustFunctionIdentToKotlin(tItem.sig.ident.toString())
                            if (mName == "cloneBox") {
                                methodStubs += "override fun cloneBox(): $renderedFieldTy = this"
                            }
                        }
                    }
                    valText = "object : $renderedFieldTy { ${methodStubs.joinToString("; ")} }"
                }
            }
            "$fieldName = $valText"
        }
    val declaration = transparentDeclaration(ctorBase)
    val layoutArgs =
        if (declaration != null && sourceTransparentField(declaration, ctorPath) != null) {
            listOf("fieldLayout = ${transparentConstructionLayout(declaration, selfTypeName)}")
        } else {
            emptyList()
        }
    val constructorArgs = fieldArgs + layoutArgs
    val restExpr = expr.rest
    return if (restExpr != null) {
        val baseText = renderExpr(restExpr, indent, selfTypeName)
        "$baseText.copy(${fieldArgs.joinToString(", ")})"
    } else {
        "$ctorPath(${constructorArgs.joinToString(", ")})"
    }
}

internal fun KotlinJ2KEmitter.renderStructPrimitiveArrayFieldInit(
    initExpr: Expr,
    arrayType: String,
    indent: String,
    selfTypeName: String?,
): String {
    val elemKind = primitiveArrayElementNumericKind(arrayType)
    return when (initExpr) {
        is Expr.Array -> {
            val fnName =
                if (arrayType in PRIMITIVE_ARRAY_TYPES) {
                    "${arrayType.removeSuffix("Array").lowercase()}ArrayOf"
                } else {
                    "listOf"
                }
            val elems =
                initExpr.elems.toList().joinToString(", ") { elem ->
                    renderTypedNumericInitializer(elem, elemKind, indent, selfTypeName)
                }
            "$fnName($elems)"
        }
        is Expr.Repeat -> {
            val lenText = normalizeArrayLengthToIntExpr(renderTypedNumericInitializer(initExpr.len, NumericKind.I32, indent, selfTypeName), initExpr.len, selfTypeName)
            val rawElem = renderTypedNumericInitializer(initExpr.expr, elemKind, indent, selfTypeName)
            val alreadyElemKind =
                elemKind != null &&
                    (isAlreadyRenderedAsNumericKind(rawElem, elemKind) || inferExprNumericKind(initExpr.expr, selfTypeName) == elemKind)
            val elemText =
                if (alreadyElemKind) {
                    rawElem
                } else {
                    elemKind?.takeIf { it in NARROW_INTEGER_KINDS }?.let { k ->
                        NUMERIC_COERCE_SUFFIX_BY_KIND[k]?.let { s ->
                            if (rawElem.endsWith(".$s")) rawElem else "$rawElem.$s"
                        }
                    } ?: rawElem
                }
            renderRepeatCollectionExpr(
                repExpr = initExpr.expr,
                elemText = elemText,
                lenText = lenText,
                ctorName = arrayType,
            )
        }
        else -> renderExpr(initExpr, indent, selfTypeName)
    }
}

internal fun KotlinJ2KEmitter.renderExprClosure(expr: Expr.Closure, indent: String, selfTypeName: String?): String {
    val hasDirectReturn = exprContainsDirectClosureReturn(expr.body)
    val prevLabel = currentClosureLabel
    val prevEnclosingRet = currentEnclosingReturnRenderedType
    val prevEnclosingSynRet = currentEnclosingReturnSynType
    val prevExpectedKind = currentExpectedNumericKind
    val prevExpectedParseTarget = currentExpectedParseTargetType
    val prevTypedLocals = currentTypedLocals
    val prevTupleElemType = currentClosureTupleElemType
    val prevInLocalInit = inLocalInitializerPosition
    currentClosureTupleElemType = null
    inLocalInitializerPosition = false
    val assignedLabel =
        if (hasDirectReturn) {
            val idx = closureLabelCounter++
            if (idx == 0) "closure" else "closure$idx"
        } else {
            null
        }
    currentClosureLabel = assignedLabel
    val explicitClosureRet = extractReturnSynType(expr.output)?.let { renderType(it, selfTypeName) }
    val closureRetKind = explicitClosureRet?.let(this::resolveNumericKind)
    currentEnclosingReturnRenderedType = explicitClosureRet
    currentEnclosingReturnSynType = extractReturnSynType(expr.output)
    currentExpectedNumericKind = null
    scopeBuilder.enterDef()
    return try {
        val hoistedDestructurings = mutableListOf<String>()
        var nestedTupleCounter = 0
        val params =
            expr.inputs.toList().mapIndexedNotNull { idx, pat ->
                val renderedParam =
                    renderClosureParam(
                        pat = pat,
                        idx = idx,
                        selfTypeName = selfTypeName,
                        nestedTuple = false,
                        hoistedDestructurings = hoistedDestructurings,
                        allocateTupleTemp = { "tuple${nestedTupleCounter++}" },
                    ).takeIf { it.isNotEmpty() && it != "_" }
                if (prevTupleElemType != null && pat is Pat.Tuple) {
                    val boundNames =
                        pat.elems
                            .toList()
                            .mapNotNull { elemPat ->
                                (elemPat as? Pat.Ident)?.ident?.toString()?.removePrefix("r#")?.takeIf { it != "_" }?.let {
                                    scopeBuilder.resolveLocalKotlinName(it) ?: rustValueIdentToKotlin(it)
                                }
                            }
                    if (boundNames.isNotEmpty()) {
                        currentTypedLocals = currentTypedLocals + boundNames.associateWith { prevTupleElemType }
                    }
                }
                renderedParam
            }
        scopeBuilder.scopeData.mutScope(scopeBuilder.topScopeId()).setParamCount(params.size)
        val body = expr.body
        val innerIndent = "$indent    "
        val bodyText =
            if (body is Expr.BlockExpr) {
                val stmts = body.block.stmts
                if (stmts.isEmpty() && hoistedDestructurings.isEmpty()) {
                    ""
                } else {
                    val renderedStmts =
                        stmts.mapIndexed { stmtIdx, stmt ->
                            val isLastValueExpr =
                                stmtIdx == stmts.lastIndex &&
                                    stmt is Stmt.ExprStmt &&
                                    stmt.semiToken == null &&
                                    !isExplicitControlFlowJump(stmt.expr)
                            currentExpectedNumericKind = if (isLastValueExpr) closureRetKind else null
                            currentExpectedParseTargetType = if (isLastValueExpr) prevExpectedParseTarget else null
                            try {
                                renderStmt(stmt, innerIndent, selfTypeName, isTailReturn = false)
                            } finally {
                                currentExpectedNumericKind = null
                                currentExpectedParseTargetType = null
                            }
                        }
                    val allLines = hoistedDestructurings.map { "$innerIndent$it" } + renderedStmts
                    "\n" + allLines.joinToString("\n") + "\n$indent"
                }
            } else {
                val bodyIndent = if (hoistedDestructurings.isEmpty()) indent else innerIndent
                val renderedBody =
                    if (closureRetKind != null) {
                        renderTypedNumericInitializer(body, closureRetKind, bodyIndent, selfTypeName)
                    } else {
                        renderExpr(body, bodyIndent, selfTypeName)
                    }
                if (hoistedDestructurings.isEmpty()) {
                    " $renderedBody "
                } else {
                    val allLines = hoistedDestructurings.map { "$innerIndent$it" } + "$innerIndent$renderedBody"
                    "\n" + allLines.joinToString("\n") + "\n$indent"
                }
            }
        val labelPrefix = assignedLabel?.let { "$it@" }.orEmpty()
        if (params.isEmpty()) {
            "$labelPrefix{$bodyText}"
        } else {
            "$labelPrefix{ ${params.joinToString(", ")} ->$bodyText}"
        }
    } finally {
        scopeBuilder.exitDef()
        currentClosureLabel = prevLabel
        currentEnclosingReturnRenderedType = prevEnclosingRet
        currentEnclosingReturnSynType = prevEnclosingSynRet
        currentExpectedNumericKind = prevExpectedKind
        currentExpectedParseTargetType = prevExpectedParseTarget
        currentTypedLocals = prevTypedLocals
        currentClosureTupleElemType = prevTupleElemType
        inLocalInitializerPosition = prevInLocalInit
    }
}

internal fun KotlinJ2KEmitter.exprContainsDirectClosureReturn(body: Expr): Boolean {
    val visitor =
        object : ScopedPredicateAstVisitor(skipNestedClosures = true) {
            override fun visitExprReturn(e: Expr.Return) = markMatched()

            override fun visitExprTry(e: Expr.Try) = markMatched()
        }
    visitor.visitExpr(body)
    return visitor.matched
}

internal fun KotlinJ2KEmitter.renderClosureParam(
    pat: Pat,
    idx: Int,
    selfTypeName: String?,
    nestedTuple: Boolean = false,
    hoistedDestructurings: MutableList<String> = mutableListOf(),
    allocateTupleTemp: () -> String = { "tuple$idx" },
): String =
    when (pat) {
        is Pat.Ident -> {
            val raw = pat.ident.toString().removePrefix("r#")
            if (raw == "_") {
                ""
            } else {
                val assignCount = if (pat.mutability != null) AssignCount.Any else AssignCount.AtMostOnce
                scopeBuilder.bindLocal(raw, rustValueIdentToKotlin(raw), pat.ident.span(), assignCount = assignCount).kotlinName
            }
        }
        is Pat.TypeAscription -> {
            val name = renderClosureParam(pat.pat, idx, selfTypeName, nestedTuple, hoistedDestructurings, allocateTupleTemp)
            val ty = renderType(pat.ty, selfTypeName)
            if (name.isEmpty()) "" else "$name: $ty"
        }
        is Pat.Wild -> ""
        is Pat.Tuple -> {
            val childDestructurings = mutableListOf<String>()
            val rawElems =
                pat.elems.toList().mapIndexed { i, p ->
                    renderClosureParam(
                        pat = p,
                        idx = i,
                        selfTypeName = selfTypeName,
                        nestedTuple = true,
                        hoistedDestructurings = childDestructurings,
                        allocateTupleTemp = allocateTupleTemp,
                    )
                }
            val destructuredPattern =
                if (rawElems.size >= 2 && rawElems.any { it.isNotEmpty() && it != "_" }) {
                    "(${rawElems.joinToString(", ") { if (it.isEmpty()) "_" else it }})"
                } else {
                    val inner = rawElems.filter { it.isNotEmpty() && it != "_" }
                    when (inner.size) {
                        0 -> ""
                        1 -> inner[0]
                        else -> "(${inner.joinToString(", ")})"
                    }
                }
            if (nestedTuple && destructuredPattern.startsWith("(")) {
                val tempName = allocateTupleTemp()
                hoistedDestructurings += "val $destructuredPattern = $tempName"
                hoistedDestructurings.addAll(childDestructurings)
                tempName
            } else {
                hoistedDestructurings.addAll(childDestructurings)
                destructuredPattern
            }
        }
        is Pat.Reference -> renderClosureParam(pat.pat, idx, selfTypeName, nestedTuple, hoistedDestructurings, allocateTupleTemp)
        else -> "param$idx"
    }

private class SingleSegmentRawIdentCollector : Visit() {
    private val used = linkedSetOf<String>()

    override fun visitExprPath(e: Expr.Path) {
        val seg = e.unqualifiedPath()?.singleSegment()
        if (seg != null) used += seg.cleanIdent()
        super.visitExprPath(e)
    }

    fun collectExpr(expr: Expr): Set<String> {
        visitExpr(expr)
        return used
    }

    fun collectBlock(block: Block): Set<String> {
        visitBlock(block)
        return used
    }
}

internal fun KotlinJ2KEmitter.collectUsedRawIdentsInExpr(expr: Expr): Set<String> =
    SingleSegmentRawIdentCollector().collectExpr(expr)

internal fun KotlinJ2KEmitter.collectUsedRawIdentsInBlock(block: Block): Set<String> =
    SingleSegmentRawIdentCollector().collectBlock(block)
