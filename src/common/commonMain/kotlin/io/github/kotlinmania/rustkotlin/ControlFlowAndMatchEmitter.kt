package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.procmacro2.Delimiter
import io.github.kotlinmania.procmacro2.TokenTree
import io.github.kotlinmania.rustkotlin.reused.starlark.AssignCount
import io.github.kotlinmania.syn.Arm
import io.github.kotlinmania.syn.Attribute
import io.github.kotlinmania.syn.Block
import io.github.kotlinmania.syn.Expr
import io.github.kotlinmania.syn.GenericArgument
import io.github.kotlinmania.syn.Item
import io.github.kotlinmania.syn.Meta
import io.github.kotlinmania.syn.Pat
import io.github.kotlinmania.syn.Path
import io.github.kotlinmania.syn.PathArguments
import io.github.kotlinmania.syn.RangeLimits
import io.github.kotlinmania.syn.Stmt
import io.github.kotlinmania.syn.SynType
import io.github.kotlinmania.syn.UnOp
import io.github.kotlinmania.syn.gen.Visit
import io.github.kotlinmania.syn.span

internal fun KotlinJ2KEmitter.renderExprIf(expr: Expr.If, indent: String, selfTypeName: String?): String {
    val savedKind = currentExpectedNumericKind
    val savedTailOk = currentTailExpectsUnitResultOk
    val cond = expr.cond
    if (cond is Expr.Let) {
        if ((!isSideEffectFreeExpr(cond.expr) || unwrapTransparentCastExpr(cond.expr) is Expr.Field) && armReferencesSubject(cond.pat)) {
            val singleEval = tryRenderSingleEvalIfLet(expr, cond, indent, selfTypeName)
            if (singleEval != null) {
                return singleEval
            }
        }
        val (patHeader, rhsText) =
            try {
                currentExpectedNumericKind = null
                currentTailExpectsUnitResultOk = false
                renderIfLetCondition(cond.pat, cond.expr, indent, selfTypeName) to
                    renderExpr(cond.expr, "$indent    ", selfTypeName)
            } finally {
                currentExpectedNumericKind = savedKind
                currentTailExpectsUnitResultOk = savedTailOk
            }
        val isSimpleSmartCast =
            !patHeader.contains(" || ") &&
                patHeader.startsWith("$rhsText is ") &&
                '<' !in patHeader.removePrefix("$rhsText is ").substringBefore(" && ") &&
                ((!rhsText.contains('.') && !patHeader.contains(" && ")) || scopeBuilder.isSmartCastableLocalKotlinName(rhsText))
        val usedInThen = collectUsedRawIdentsInBlock(expr.thenBranch)
        val thenBlock =
            run {
                val prevTyped = currentTypedLocals
                scopeBuilder.enterCompr()
                try {
                    val bindingLines =
                        extractMatchArmBindings(
                            cond.pat,
                            rhsText,
                            "$indent    ",
                            selfTypeName,
                            bindToScope = true,
                            subjectAlreadySmartCast = isSimpleSmartCast,
                            usedBodyIdents = usedInThen,
                        )
                    recordOptionLetPatternLocalTypes(cond.pat, cond.expr, selfTypeName)
                    renderBlock(
                        block = expr.thenBranch,
                        indent = indent,
                        selfTypeName = selfTypeName,
                        leadingInjectedLines = bindingLines,
                    )
                } finally {
                    scopeBuilder.exitCompr()
                    currentTypedLocals = prevTyped
                }
            }
        val elseSuffix = renderElseBranchSuffix(expr, indent, selfTypeName)
        return "if ($patHeader) $thenBlock$elseSuffix"
    }
    val condText =
        try {
            currentExpectedNumericKind = null
            currentTailExpectsUnitResultOk = false
            renderExpr(cond, indent, selfTypeName)
        } finally {
            currentExpectedNumericKind = savedKind
            currentTailExpectsUnitResultOk = savedTailOk
        }
    val postCondSmartCast = currentSmartCastNonNullLocals
    val nullChecked = extractNullCheckedIdent(condText)
    if (nullChecked != null && !nullChecked.second) {
        currentSmartCastNonNullLocals = postCondSmartCast + nullChecked.first
    }
    val thenText =
        try {
            renderBlock(expr.thenBranch, indent, selfTypeName)
        } finally {
            currentSmartCastNonNullLocals = postCondSmartCast
        }
    if (nullChecked != null && nullChecked.second) {
        currentSmartCastNonNullLocals = postCondSmartCast + nullChecked.first
    }
    val elseSuffix =
        try {
            renderElseBranchSuffix(expr, indent, selfTypeName)
        } finally {
            currentSmartCastNonNullLocals =
                if (nullChecked != null && nullChecked.second && expr.elseBranch == null && blockAlwaysDiverges(expr.thenBranch)) {
                    postCondSmartCast + nullChecked.first
                } else {
                    postCondSmartCast
                }
        }
    return "if ($condText) $thenText$elseSuffix"
}

internal fun KotlinJ2KEmitter.extractNullCheckedIdent(condText: String): Pair<String, Boolean>? {
    var s = condText.trim()
    var negated = false
    while (true) {
        if (s.length >= 2 && s.startsWith("(") && s.endsWith(")") && hasOuterBalancedParens(s)) {
            s = s.substring(1, s.length - 1).trim()
            continue
        }
        if (s.startsWith("!")) {
            negated = !negated
            s = s.drop(1).trim()
            continue
        }
        break
    }
    val isEqNull =
        when {
            s.endsWith(" == null") -> true
            s.endsWith(" != null") -> false
            else -> return null
        }
    val rawId = s.substringBefore(" ").trim()
    if (rawId.isEmpty() || !rawId.all { it.isLetterOrDigit() || it == '_' }) return null
    return rawId to (if (negated) !isEqNull else isEqNull)
}

internal fun KotlinJ2KEmitter.blockAlwaysDiverges(block: Block): Boolean {
    val lastStmt = block.stmts.lastOrNull() as? Stmt.ExprStmt ?: return false
    return when (unwrapParenOrGroupExpr(lastStmt.expr)) {
        is Expr.Return, is Expr.Break, is Expr.Continue -> true
        else -> false
    }
}

internal fun KotlinJ2KEmitter.tryRenderSingleEvalIfLet(
    ifExpr: Expr.If,
    cond: Expr.Let,
    indent: String,
    selfTypeName: String?,
): String? {
    val unwrappedPat = unwrapTransparentPat(cond.pat)
    val isPlainSome =
        unwrappedPat is Pat.TupleStruct &&
            isOptionSomeTupleStructPat(unwrappedPat) &&
            unwrappedPat.elems
                .toList()
                .firstOrNull()
                ?.let { !isDiscriminatingOptionInnerPattern(it) } != false
    val coreResultInfo = if (unwrappedPat is Pat.TupleStruct) coreResultVariantMatchInfo(unwrappedPat) else null
    val requiresBoolCond = !isPlainSome && patRequiresBooleanCondition(unwrappedPat)
    val isSupportedPat =
        isPlainSome ||
            coreResultInfo != null ||
            unwrappedPat is Pat.TupleStruct ||
            unwrappedPat is Pat.Struct
    if (!isSupportedPat) return null

    val savedKind = currentExpectedNumericKind
    val savedTailOk = currentTailExpectsUnitResultOk
    val rhsText =
        try {
            currentExpectedNumericKind = null
            currentTailExpectsUnitResultOk = false
            renderExpr(cond.expr, indent, selfTypeName)
        } finally {
            currentExpectedNumericKind = savedKind
            currentTailExpectsUnitResultOk = savedTailOk
        }
    val armIndent = "$indent    "
    val elseBody =
        when (val elseBr = ifExpr.elseBranch) {
            null -> "{}"
            else ->
                when (val elseTarget = elseBr.expr) {
                    is Expr.BlockExpr -> renderBlock(elseTarget.block, armIndent, selfTypeName)
                    is Expr.If -> "{\n$armIndent    ${renderExprIf(elseTarget, "$armIndent    ", selfTypeName)}\n$armIndent}"
                    else -> {
                        val rendered = renderExpr(elseTarget, armIndent, selfTypeName)
                        if (savedTailOk) coerceTailFmtUnitToResultOk(elseTarget, rendered, selfTypeName) else rendered
                    }
                }
        }

    val usedInThen = collectUsedRawIdentsInBlock(ifExpr.thenBranch)
    val prevOuterTyped = currentTypedLocals
    scopeBuilder.enterCompr()
    return try {
        val subjectVar = scopeBuilder.bindLocal("_match", "matchVal", cond.expr.span()).kotlinName
        inferExprRenderedType(cond.expr, selfTypeName)?.let {
            currentTypedLocals = currentTypedLocals + (subjectVar to it)
        }
        val typeCheckTarget =
            when {
                isPlainSome -> null
                coreResultInfo != null -> {
                    val starArgs = if (isUntypedResultScrutinee(currentTypedLocals[subjectVar])) "<*, *>" else ""
                    "${coreResultInfo.first}$starArgs"
                }
                unwrappedPat is Pat.TupleStruct ->
                    qualifyShadowedPatternVariantType(renderPathAsType(unwrappedPat.path, selfTypeName), subjectVar, selfTypeName)
                unwrappedPat is Pat.Struct -> {
                    val vt = qualifyShadowedPatternVariantType(renderPathAsType(unwrappedPat.path, selfTypeName), subjectVar, selfTypeName)
                    if (isPlainStructPattern(unwrappedPat, vt)) null else vt
                }
                else -> null
            }
        val boolCond =
            if (requiresBoolCond) {
                val savedBoolKind = currentExpectedNumericKind
                try {
                    currentExpectedNumericKind = null
                    renderPatternBooleanCondition(cond.pat, subjectVar, armIndent, selfTypeName)
                } finally {
                    currentExpectedNumericKind = savedBoolKind
                }
            } else {
                null
            }
        val smartCastInThen =
            typeCheckTarget != null &&
                '<' !in typeCheckTarget &&
                (boolCond == null || !boolCond.contains(" || "))
        val thenBlock =
            run {
                val prevTyped = currentTypedLocals
                scopeBuilder.enterCompr()
                try {
                    val bindingLines =
                        extractMatchArmBindings(
                            cond.pat,
                            subjectVar,
                            "$armIndent    ",
                            selfTypeName,
                            bindToScope = true,
                            subjectAlreadySmartCast = smartCastInThen,
                            usedBodyIdents = usedInThen,
                        )
                    recordOptionLetPatternLocalTypes(cond.pat, cond.expr, selfTypeName)
                    renderBlock(
                        block = ifExpr.thenBranch,
                        indent = armIndent,
                        selfTypeName = selfTypeName,
                        leadingInjectedLines = bindingLines,
                    )
                } finally {
                    scopeBuilder.exitCompr()
                    currentTypedLocals = prevTyped
                }
            }
        when {
            isPlainSome ->
                "when (val $subjectVar = $rhsText) {\n${armIndent}null -> $elseBody\n${armIndent}else -> $thenBlock\n$indent}"
            boolCond != null -> {
                val elsePart = if (ifExpr.elseBranch == null) "" else " else $elseBody"
                "run {\n${armIndent}val $subjectVar = $rhsText\n${armIndent}if ($boolCond) $thenBlock$elsePart\n$indent}"
            }
            typeCheckTarget != null ->
                "when (val $subjectVar = $rhsText) {\n${armIndent}is $typeCheckTarget -> $thenBlock\n${armIndent}else -> $elseBody\n$indent}"
            else -> {
                val elsePart = if (ifExpr.elseBranch == null) "" else " else $elseBody"
                "run {\n${armIndent}val $subjectVar = $rhsText\n${armIndent}if (true) $thenBlock$elsePart\n$indent}"
            }
        }
    } finally {
        scopeBuilder.exitCompr()
        currentTypedLocals = prevOuterTyped
    }
}

internal fun KotlinJ2KEmitter.unwrapTransparentPat(pat: Pat): Pat =
    when (pat) {
        is Pat.Reference -> unwrapTransparentPat(pat.pat)
        is Pat.PatParen -> unwrapTransparentPat(pat.pat)
        is Pat.TypeAscription -> unwrapTransparentPat(pat.pat)
        else -> pat
    }

internal fun KotlinJ2KEmitter.renderElseBranchSuffix(expr: Expr.If, indent: String, selfTypeName: String?): String =
    when (val elseBr = expr.elseBranch) {
        null -> ""
        else ->
            when (val elseTarget = elseBr.expr) {
                is Expr.BlockExpr -> " else ${renderBlock(elseTarget.block, indent, selfTypeName)}"
                is Expr.If -> " else ${renderExprIf(elseTarget, indent, selfTypeName)}"
                else -> {
                    val rendered = renderExpr(elseTarget, indent, selfTypeName)
                    val coerced =
                        if (currentTailExpectsUnitResultOk) {
                            coerceTailFmtUnitToResultOk(elseTarget, rendered, selfTypeName)
                        } else {
                            rendered
                        }
                    " else $coerced"
                }
            }
    }

internal fun KotlinJ2KEmitter.renderExprWhile(expr: Expr.While, indent: String, selfTypeName: String?): String {
    val cond = expr.cond
    if (cond is Expr.Let) {
        if (!isSideEffectFreeExpr(cond.expr) && armReferencesSubject(cond.pat)) {
            val innerIndent = "$indent    "
            val rhsText = renderExpr(cond.expr, innerIndent, selfTypeName)
            val prevTyped = currentTypedLocals
            scopeBuilder.enterCompr()
            val bodyBlock =
                try {
                    val subjectVar = scopeBuilder.bindLocal("_match", "matchVal", cond.expr.span()).kotlinName
                    inferExprRenderedType(cond.expr, selfTypeName)?.let {
                        currentTypedLocals = currentTypedLocals + (subjectVar to it)
                    }
                    val condCheck = renderIfLetPatternAgainstRhs(cond.pat, subjectVar, innerIndent, selfTypeName, cond.expr)
                    val breakLine =
                        when {
                            condCheck == "$subjectVar != null" -> "${innerIndent}if ($subjectVar == null) break"
                            condCheck.startsWith("$subjectVar is ") && !condCheck.contains(" && ") && !condCheck.contains(" || ") ->
                                "${innerIndent}if ($subjectVar !is ${condCheck.removePrefix("$subjectVar is ")}) break"
                            else -> "${innerIndent}if (!($condCheck)) break"
                        }
                    val bindingLines =
                        listOf("${innerIndent}val $subjectVar = $rhsText", breakLine) +
                            extractMatchArmBindings(cond.pat, subjectVar, innerIndent, selfTypeName, bindToScope = true)
                    recordOptionLetPatternLocalTypes(cond.pat, cond.expr, selfTypeName)
                    renderBlock(
                        block = expr.body,
                        indent = indent,
                        selfTypeName = selfTypeName,
                        leadingInjectedLines = bindingLines,
                    )
                } finally {
                    scopeBuilder.exitCompr()
                    currentTypedLocals = prevTyped
                }
            return "while (true) $bodyBlock"
        }
        val patHeader = renderIfLetCondition(cond.pat, cond.expr, indent, selfTypeName)
        val rhsText = renderExpr(cond.expr, "$indent    ", selfTypeName)
        val prevTyped = currentTypedLocals
        scopeBuilder.enterCompr()
        val bodyBlock =
            try {
                val bindingLines = extractMatchArmBindings(cond.pat, rhsText, "$indent    ", selfTypeName, bindToScope = true)
                recordOptionLetPatternLocalTypes(cond.pat, cond.expr, selfTypeName)
                renderBlock(
                    block = expr.body,
                    indent = indent,
                    selfTypeName = selfTypeName,
                    leadingInjectedLines = bindingLines,
                )
            } finally {
                scopeBuilder.exitCompr()
                currentTypedLocals = prevTyped
            }
        return "while ($patHeader) $bodyBlock"
    }
    val condText = renderExpr(cond, indent, selfTypeName)
    val prevSmart = currentSmartCastNonNullLocals
    val bodyText =
        try {
            renderBlock(expr.body, indent, selfTypeName)
        } finally {
            currentSmartCastNonNullLocals = prevSmart
        }
    return "while ($condText) $bodyText"
}

internal fun KotlinJ2KEmitter.renderIfLetCondition(pat: Pat, expr: Expr, indent: String, selfTypeName: String?): String {
    val rhs = renderExpr(expr, indent, selfTypeName)
    return renderIfLetPatternAgainstRhs(pat, rhs, indent, selfTypeName, expr)
}

internal fun KotlinJ2KEmitter.recordOptionLetPatternLocalTypes(pat: Pat, expr: Expr, selfTypeName: String?) {
    val tupleStruct = unwrapTransparentPat(pat) as? Pat.TupleStruct ?: return
    if (!isOptionSomeTupleStructPat(tupleStruct)) return
    val tupleElems = tupleStruct.elems.toList()
    val firstSub = tupleElems.firstOrNull()?.let(this::unwrapTransparentPat) ?: return
    val callExpr = unwrapTransparentCastExpr(expr) as? Expr.Call ?: return
    val calleePath = (callExpr.func as? Expr.Path)?.takeIf { it.qself == null }?.path ?: return
    val lastIdent =
        calleePath.segments
            .last()
            ?.ident
            ?.toString() ?: return
    val ktFn = rustFunctionIdentToKotlin(lastIdent)
    val retSyn = inspector.topLevelFnReturnSynTypes[ktFn]?.let(::unwrapSynType) ?: return
    val retPath = (retSyn as? SynType.Path)?.takeIf { it.qself == null }?.path ?: return
    val retLast = retPath.segments.last() ?: return
    if (retLast.ident.toString() != "Option") return
    val angle = retLast.arguments as? PathArguments.AngleBracketed ?: return
    val innerSyn =
        angle.args
            .toList()
            .firstNotNullOfOrNull { (it as? GenericArgument.TypeArg)?.type }
            ?.let(::unwrapSynType)
            ?: return
    when (firstSub) {
        is Pat.Ident -> {
            val rawName = firstSub.ident.toString().removePrefix("r#")
            val ktLocal = scopeBuilder.resolveLocalKotlinName(rawName) ?: rustValueIdentToKotlin(firstSub.ident.toString())
            currentTypedLocals = currentTypedLocals + (ktLocal to renderType(innerSyn, selfTypeName))
        }
        is Pat.Tuple -> {
            if (innerSyn is SynType.Tuple) {
                val patElems = firstSub.elems.toList()
                val tyElems = innerSyn.elems.toList()
                val limit = minOf(patElems.size, tyElems.size)
                for (idx in 0 until limit) {
                    val subIdent = unwrapTransparentPat(patElems[idx]) as? Pat.Ident ?: continue
                    val rawName = subIdent.ident.toString().removePrefix("r#")
                    val ktLocal = scopeBuilder.resolveLocalKotlinName(rawName) ?: rustValueIdentToKotlin(subIdent.ident.toString())
                    currentTypedLocals = currentTypedLocals + (ktLocal to renderType(tyElems[idx], selfTypeName))
                }
            }
        }
        else -> {}
    }
}

internal fun KotlinJ2KEmitter.coreResultVariantMatchInfo(pat: Pat.TupleStruct): Pair<String, String>? {
    if (!usesTaggedCoreResult() || hasDeclaredResultEnum()) return null
    if ("Ok" in inspector.declaredTypeNames || "Err" in inspector.declaredTypeNames) return null
    val segs =
        pat.path.segments
            .toList()
            .map { it.ident.toString() }
    val variant = segs.lastOrNull() ?: return null
    if (variant != "Ok" && variant != "Err") return null
    val isCoreResultPath =
        segs.size == 1 ||
            (segs.size == 2 && segs[0] == "Result") ||
            (segs.size >= 3 && segs[segs.size - 2] == "Result" && segs.dropLast(2).all { it in STDLIB_TYPE_PATH_PREFIXES || it == "result" })
    if (!isCoreResultPath) return null
    val prop = if (variant == "Ok") "value" else "error"
    return "${sourceResultName()}.$variant" to prop
}

internal fun KotlinJ2KEmitter.renderIfLetPatternAgainstRhs(
    pat: Pat,
    rhs: String,
    indent: String,
    selfTypeName: String?,
    subjectExpr: Expr? = null,
): String =
    when (pat) {
        is Pat.TupleStruct -> {
            val coreResultInfo = coreResultVariantMatchInfo(pat)
            if (isOptionSomeTupleStructPat(pat)) {
                val firstSub = pat.elems.toList().firstOrNull()
                if (firstSub != null && isDiscriminatingOptionInnerPattern(firstSub)) {
                    renderIfLetPatternAgainstRhs(firstSub, rhs, indent, selfTypeName, subjectExpr)
                } else {
                    "$rhs != null"
                }
            } else if (patRequiresBooleanCondition(pat)) {
                renderPatternBooleanCondition(pat, rhs, indent, selfTypeName)
            } else if (coreResultInfo != null) {
                val scrutineeType = subjectExpr?.let { inferExprRenderedType(it, selfTypeName) } ?: currentTypedLocals[rhs]
                val starArgs = if (isUntypedResultScrutinee(scrutineeType)) "<*, *>" else ""
                "$rhs is ${coreResultInfo.first}$starArgs"
            } else {
                "$rhs is ${qualifyShadowedPatternVariantType(renderPathAsType(pat.path, selfTypeName), rhs, selfTypeName)}"
            }
        }
        is Pat.Struct -> {
            val variantType = qualifyShadowedPatternVariantType(renderPathAsType(pat.path, selfTypeName), rhs, selfTypeName)
            if (patRequiresBooleanCondition(pat)) {
                renderPatternBooleanCondition(pat, rhs, indent, selfTypeName)
            } else if (isPlainStructPattern(pat, variantType)) {
                "true"
            } else {
                "$rhs is $variantType"
            }
        }
        is Pat.Path -> {
            val segs = pat.path.segments.toList()
            if (segs.size == 1 && segs[0].ident.toString() == "None" && !inspector.hasLocalNoneVariant) {
                "$rhs == null"
            } else {
                "$rhs == ${renderPathAsExpression(pat.path, selfTypeName)}"
            }
        }
        is Pat.Lit -> "$rhs == ${renderLit(pat.lit)}"
        is Pat.Range -> renderPatternBooleanCondition(pat, rhs, indent, selfTypeName)
        is Pat.Or -> {
            pat.cases.toList().joinToString(" || ") { renderIfLetPatternAgainstRhs(it, rhs, indent, selfTypeName) }
        }
        is Pat.Ident -> {
            val raw = pat.ident.toString()
            val subpat = pat.subpat
            if (subpat != null) {
                renderIfLetPatternAgainstRhs(subpat, rhs, indent, selfTypeName)
            } else if (raw == "None") {
                "$rhs == null"
            } else {
                "$rhs != null"
            }
        }
        is Pat.Reference -> renderIfLetPatternAgainstRhs(pat.pat, rhs, indent, selfTypeName)
        is Pat.PatParen -> renderIfLetPatternAgainstRhs(pat.pat, rhs, indent, selfTypeName)
        else -> "$rhs != null"
    }

internal fun KotlinJ2KEmitter.isDiscriminatingOptionInnerPattern(pat: Pat): Boolean =
    when (pat) {
        is Pat.Wild, is Pat.Rest -> false
        is Pat.Ident -> pat.subpat != null && isDiscriminatingOptionInnerPattern(pat.subpat!!)
        is Pat.Tuple -> pat.elems.toList().any { isDiscriminatingOptionInnerPattern(it) }
        is Pat.Reference -> isDiscriminatingOptionInnerPattern(pat.pat)
        is Pat.PatParen -> isDiscriminatingOptionInnerPattern(pat.pat)
        is Pat.TypeAscription -> isDiscriminatingOptionInnerPattern(pat.pat)
        is Pat.Path -> {
            val last =
                pat.path.segments
                    .last()
                    ?.ident
                    ?.toString()
            last != null && rustTypeIdentToKotlin(last) !in inspector.unitStructNames
        }
        else -> true
    }

internal fun KotlinJ2KEmitter.renderExprForLoop(expr: Expr.ForLoop, indent: String, selfTypeName: String?): String {
    val loopIterExpr = expr.expr
    val loopPat = unwrapTransparentPat(expr.pat)
    val singleStmt = expr.body.stmts.singleOrNull() as? Stmt.ExprStmt
    val singleAssign = singleStmt?.expr as? Expr.Assign
    val rawPatIdent = (loopPat as? Pat.Ident)?.ident?.toString()?.removePrefix("r#")
    val mutatesDerefedPat = rawPatIdent != null && blockMutatesDerefedIdent(expr.body, rawPatIdent)
    val mutIterTargetExpr =
        when {
            loopIterExpr is Expr.Reference && loopIterExpr.mutability != null -> loopIterExpr.expr
            loopIterExpr is Expr.MethodCall &&
                loopIterExpr.method.toString().removePrefix("r#") == "iter_mut" &&
                loopIterExpr.args.isEmpty() -> loopIterExpr.receiver
            mutatesDerefedPat &&
                (
                    inferArrayElementNumericKind(loopIterExpr, selfTypeName) != null ||
                        isIndexableMutableSeqRenderedType(inferExprRenderedType(loopIterExpr, selfTypeName))
                ) -> loopIterExpr
            else -> null
        }
    if (mutIterTargetExpr != null && loopPat is Pat.Ident && rawPatIdent != null && mutatesDerefedPat) {
        val iterText = renderExpr(mutIterTargetExpr, indent, selfTypeName)
        val elemKind = inferArrayElementNumericKind(mutIterTargetExpr, selfTypeName)
        val elemRenderedTy =
            inferExprRenderedType(mutIterTargetExpr, selfTypeName)
                ?.let { extractIndexedElementRenderedType(it, selfTypeName) }
                ?: elemKind?.let(::numericKindToPureTypeName)
        val safeIter = if (isAtomicPostfixReceiver(mutIterTargetExpr, iterText)) iterText else "($iterText)"
        val bodyIter = safeIter.replace("!!.", ".")
        val prevSubs = activeGuardBindingSubstitutions
        val prevTypedLocals = currentTypedLocals
        scopeBuilder.enterCompr()
        return try {
            val idxVar = scopeBuilder.bindLocal("_idx", "idx", loopPat.ident.span()).kotlinName
            val slotAccess = "$bodyIter[$idxVar]"
            activeGuardBindingSubstitutions = prevSubs + (rawPatIdent to slotAccess)
            if (elemRenderedTy != null) {
                val ktPatLocal = rustValueIdentToKotlin(rawPatIdent)
                currentTypedLocals = prevTypedLocals + (ktPatLocal to elemRenderedTy) + (slotAccess to elemRenderedTy)
            }
            if (singleAssign != null &&
                singleAssign.left is Expr.Unary &&
                (singleAssign.left as Expr.Unary).op is UnOp.Deref &&
                isDerefedSingleIdent((singleAssign.left as Expr.Unary).expr, rawPatIdent)
            ) {
                val valRendered = renderTypedNumericInitializer(singleAssign.right, elemKind, "$indent    ", selfTypeName)
                "for ($idxVar in $safeIter.indices) {\n$indent    $slotAccess = $valRendered\n$indent}"
            } else {
                val bodyText = renderBlock(expr.body, indent, selfTypeName)
                "for ($idxVar in $safeIter.indices) $bodyText"
            }
        } finally {
            scopeBuilder.exitCompr()
            activeGuardBindingSubstitutions = prevSubs
            currentTypedLocals = prevTypedLocals
        }
    }
    val effectiveIterExpr =
        if (loopIterExpr is Expr.MethodCall &&
            loopIterExpr.args.isEmpty() &&
            loopIterExpr.turbofish == null &&
            loopIterExpr.method.toString().removePrefix("r#") in setOf("iter", "iter_mut", "into_iter") &&
            inferArrayElementNumericKind(loopIterExpr.receiver, selfTypeName) != null
        ) {
            loopIterExpr.receiver
        } else {
            loopIterExpr
        }
    val rawIterText = renderExpr(effectiveIterExpr, indent, selfTypeName)
    val iterText =
        if (effectiveIterExpr !== loopIterExpr && inferExprRenderedType(effectiveIterExpr, selfTypeName)?.endsWith("?") == true && !rawIterText.endsWith("!!")) {
            "$rawIterText!!"
        } else {
            rawIterText
        }
    val prevSmart = currentSmartCastNonNullLocals
    scopeBuilder.enterCompr()
    return try {
        val bindings =
            extractPatternBindingNames(
                pat = expr.pat,
                selfTypeName = selfTypeName,
                includeCtorInTupleStruct = false,
                bindToScope = true,
            )
        val loopHeader =
            when {
                bindings.isEmpty() -> scopeBuilder.bindLocal("_unused", "unused", expr.pat.span()).kotlinName
                bindings.size == 1 -> bindings[0]
                else -> "(${bindings.joinToString(", ")})"
            }
        val bodyText = renderBlock(expr.body, indent, selfTypeName)
        "for ($loopHeader in $iterText) $bodyText"
    } finally {
        scopeBuilder.exitCompr()
        currentSmartCastNonNullLocals = prevSmart
    }
}

private fun KotlinJ2KEmitter.isDerefedSingleIdent(expr: Expr, rawIdent: String): Boolean {
    val unwrapped = unwrapParenOrGroupExpr(expr)
    if (unwrapped !is Expr.Path || unwrapped.qself != null || unwrapped.path.segments.size != 1) return false
    val firstSeg = unwrapped.path.segments.first() ?: return false
    return firstSeg.ident.toString().removePrefix("r#") == rawIdent
}

private fun isIndexableMutableSeqRenderedType(renderedType: String?): Boolean {
    val clean = renderedType?.removeSuffix("?")?.trim() ?: return false
    return clean in PRIMITIVE_ARRAY_TYPES ||
        clean == "CharArray" ||
        clean == "U128Array" ||
        clean == "I128Array" ||
        clean.startsWith("Array<") ||
        clean.startsWith("MutableList<") ||
        clean.startsWith("ArrayList<")
}

private fun KotlinJ2KEmitter.patBindsRawIdent(pat: Pat, rawIdent: String): Boolean =
    when (val u = unwrapTransparentPat(pat)) {
        is Pat.Ident ->
            u.ident.toString().removePrefix("r#") == rawIdent ||
                (u.subpat?.let { patBindsRawIdent(it, rawIdent) } == true)
        is Pat.Tuple -> u.elems.toList().any { patBindsRawIdent(it, rawIdent) }
        is Pat.TupleStruct -> u.elems.toList().any { patBindsRawIdent(it, rawIdent) }
        is Pat.Slice -> u.elems.toList().any { patBindsRawIdent(it, rawIdent) }
        is Pat.Or -> u.cases.toList().any { patBindsRawIdent(it, rawIdent) }
        is Pat.Struct -> u.fields.toList().any { patBindsRawIdent(it.pat, rawIdent) }
        else -> false
    }

private fun KotlinJ2KEmitter.blockMutatesDerefedIdent(block: Block, rawIdent: String): Boolean {
    val emitter = this
    var mutates = false
    var shadowed = false
    val visitor =
        object : Visit() {
            override fun visitItem(i: Item) {}

            override fun visitExprClosure(e: Expr.Closure) {}

            override fun visitStmt(s: Stmt) {
                if (isLeadingCfgStmtDisabled(s)) return
                super.visitStmt(s)
                if (s is Stmt.Local && emitter.patBindsRawIdent(s.pat, rawIdent)) {
                    shadowed = true
                }
            }

            override fun visitExprForLoop(e: Expr.ForLoop) {
                visitExpr(e.expr)
                if (!emitter.patBindsRawIdent(e.pat, rawIdent)) {
                    visitBlock(e.body)
                }
            }

            override fun visitExprAssign(e: Expr.Assign) {
                if (!shadowed) {
                    val lhs = emitter.unwrapParenOrGroupExpr(e.left)
                    if (lhs is Expr.Unary && lhs.op is UnOp.Deref && emitter.isDerefedSingleIdent(lhs.expr, rawIdent)) {
                        mutates = true
                    }
                }
                super.visitExprAssign(e)
            }

            override fun visitExprBinary(e: Expr.Binary) {
                if (!shadowed && emitter.isCompoundAssignOp(e.op)) {
                    val lhs = emitter.unwrapParenOrGroupExpr(e.left)
                    if (lhs is Expr.Unary && lhs.op is UnOp.Deref && emitter.isDerefedSingleIdent(lhs.expr, rawIdent)) {
                        mutates = true
                    }
                }
                super.visitExprBinary(e)
            }
        }
    visitor.visitBlock(block)
    return mutates && !shadowed
}

internal fun KotlinJ2KEmitter.renderExprMatch(expr: Expr.Match, indent: String, selfTypeName: String?): String {
    val savedKind = currentExpectedNumericKind
    val savedTailOk = currentTailExpectsUnitResultOk
    val subject =
        try {
            currentExpectedNumericKind = null
            currentTailExpectsUnitResultOk = false
            renderExpr(expr.expr, indent, selfTypeName)
        } finally {
            currentExpectedNumericKind = savedKind
            currentTailExpectsUnitResultOk = savedTailOk
        }
    val arms = deduplicateComplementaryCfgArms(expr.arms)
    if (arms.isEmpty()) {
        return "when ($subject) {}"
    }
    val isSimpleSubject = subject.all { it.isLetterOrDigit() || it == '_' }
    val stdlibResultMatch = tryRenderStdlibResultMatch(expr, subject, isSimpleSubject, indent, selfTypeName)
    if (stdlibResultMatch != null) {
        return stdlibResultMatch
    }
    val reorderedOptionArms =
        if (arms.size == 2 &&
            arms.all { armGuardExpr(it) == null } &&
            isPlainIrrefutableSomePat(armPattern(arms[0])) &&
            isOptionNonePat(armPattern(arms[1]))
        ) {
            listOf(arms[1], arms[0])
        } else {
            arms
        }
    val useBooleanWhen =
        reorderedOptionArms.any { armGuardExpr(it) != null || patRequiresBooleanCondition(armPattern(it)) } ||
            reorderedOptionArms.dropLast(1).any { isPlainIrrefutableSomePat(armPattern(it)) }
    if (useBooleanWhen) {
        return renderBooleanExprMatch(expr, subject, isSimpleSubject, indent, selfTypeName)
    }

    val effectiveArms = expandOrArmsWithBindings(reorderedOptionArms)
    val unwrappedSubjExpr = unwrapParenOrGroupExpr(expr.expr)
    val mutRefWritebackTarget =
        if (unwrappedSubjExpr is Expr.Reference &&
            unwrappedSubjExpr.mutability != null &&
            isSimpleSubject &&
            effectiveArms.any { matchArmMutatesTupleVariantBinding(it) }
        ) {
            subject
        } else {
            null
        }
    val needsSubjectVar =
        mutRefWritebackTarget != null ||
            (!isSimpleSubject && effectiveArms.any { armReferencesSubject(armPattern(it)) })
    val prevMatchTyped = currentTypedLocals
    scopeBuilder.enterCompr()
    return try {
        val subjectVar =
            if (needsSubjectVar) {
                scopeBuilder.bindLocal("_match", "matchVal", expr.expr.span()).kotlinName
            } else {
                subject
            }
        inferExprRenderedType(expr.expr, selfTypeName)?.let {
            currentTypedLocals = currentTypedLocals + (subjectVar to it)
        }
        val whenHeader = if (needsSubjectVar) "val $subjectVar = $subject" else subject
        val armIndent = "$indent    "
        val patConditions =
            try {
                currentExpectedNumericKind = null
                effectiveArms.mapIndexed { idx, arm ->
                    renderMatchPatternCondition(
                        armPattern(arm),
                        subjectVar,
                        armIndent,
                        selfTypeName,
                        isLastArm = idx == effectiveArms.lastIndex,
                    )
                }
            } finally {
                currentExpectedNumericKind = savedKind
            }

        val sb = StringBuilder()
        sb.append("when (").append(whenHeader).append(") {\n")
        for ((idx, arm) in effectiveArms.withIndex()) {
            val nextCond = patConditions.getOrNull(idx + 1)
            val nextStartsWithIn = nextCond != null && (nextCond.startsWith("in ") || nextCond.startsWith("!in "))
            sb.append(
                renderSubjectMatchArm(
                    arm = arm,
                    patCond = patConditions[idx],
                    subjectText = subjectVar,
                    indent = armIndent,
                    selfTypeName = selfTypeName,
                    wrapBodyBeforeInArm = nextStartsWithIn,
                    mutRefWritebackTarget = mutRefWritebackTarget,
                ),
            )
            sb.append('\n')
        }
        sb.append(indent).append("}")
        sb.toString()
    } finally {
        scopeBuilder.exitCompr()
        currentTypedLocals = prevMatchTyped
    }
}

internal fun KotlinJ2KEmitter.armPattern(arm: Arm): Pat =
    (arm.pat as? Pat.Guard)?.pat ?: arm.pat

internal fun KotlinJ2KEmitter.armGuardExpr(arm: Arm): Expr? =
    (arm.pat as? Pat.Guard)?.guard

internal fun KotlinJ2KEmitter.tryRenderStdlibResultMatch(
    expr: Expr.Match,
    subject: String,
    isSimpleSubject: Boolean,
    indent: String,
    selfTypeName: String?,
): String? {
    if (hasDeclaredResultEnum() || "Ok" in inspector.declaredTypeNames || "Err" in inspector.declaredTypeNames) return null
    val arms = expr.arms
    if (arms.size != 2 || arms.any { armGuardExpr(it) != null }) return null
    val p0 = unwrapTransparentPat(armPattern(arms[0]))
    val p1 = unwrapTransparentPat(armPattern(arms[1]))
    val (okArm, errArm) =
        when {
            isStdlibResultArmPat(p0, "Ok") && (isStdlibResultArmPat(p1, "Err") || p1 is Pat.Wild) -> arms[0] to arms[1]
            isStdlibResultArmPat(p1, "Ok") && (isStdlibResultArmPat(p0, "Err") || p0 is Pat.Wild) -> arms[1] to arms[0]
            else -> return null
        }
    val taggedCoreResult = usesTaggedCoreResult()
    scopeBuilder.enterCompr()
    return try {
        val wrapInRun = !isSimpleSubject
        val subjectVar =
            if (wrapInRun) {
                scopeBuilder.bindLocal("_match", "matchVal", expr.expr.span()).kotlinName
            } else {
                subject
            }
        inferExprRenderedType(expr.expr, selfTypeName)?.let {
            currentTypedLocals = currentTypedLocals + (subjectVar to it)
        }
        val ifIndent = if (wrapInRun) "$indent    " else indent
        val okSubPat = (unwrapTransparentPat(armPattern(okArm)) as Pat.TupleStruct).elems.toList().firstOrNull()
        val errSubPat = (unwrapTransparentPat(armPattern(errArm)) as? Pat.TupleStruct)?.elems?.toList()?.firstOrNull()
        val okValueExpr = if (taggedCoreResult) "$subjectVar.value" else "$subjectVar.getOrThrow()"
        val errValueExpr = if (taggedCoreResult) "$subjectVar.error" else "$subjectVar.exceptionOrNull()!!"

        val okBranch =
            run {
                scopeBuilder.enterCompr()
                try {
                    val bindings = mutableListOf<String>()
                    if (okSubPat != null && armReferencesSubject(okSubPat)) {
                        if (unwrapTransparentPat(okSubPat) is Pat.Ident) {
                            bindings += extractMatchArmBindings(okSubPat, okValueExpr, "$ifIndent    ", selfTypeName, bindToScope = true)
                        } else {
                            val okTmp = scopeBuilder.bindLocal("_okVal", "okVal", okSubPat.span()).kotlinName
                            bindings += "$ifIndent    val $okTmp = $okValueExpr"
                            bindings += extractMatchArmBindings(okSubPat, okTmp, "$ifIndent    ", selfTypeName, bindToScope = true)
                        }
                    }
                    renderResultBranchBlock(okArm.body, bindings, ifIndent, selfTypeName)
                } finally {
                    scopeBuilder.exitCompr()
                }
            }
        val errBranch =
            run {
                scopeBuilder.enterCompr()
                try {
                    val bindings =
                        if (errSubPat != null && armReferencesSubject(errSubPat)) {
                            extractMatchArmBindings(errSubPat, errValueExpr, "$ifIndent    ", selfTypeName, bindToScope = true)
                        } else {
                            emptyList()
                        }
                    renderResultBranchBlock(errArm.body, bindings, ifIndent, selfTypeName)
                } finally {
                    scopeBuilder.exitCompr()
                }
            }

        val ifText =
            if (taggedCoreResult) {
                val result = sourceResultName()
                val starArgs = if (isUntypedResultScrutinee(inferExprRenderedType(expr.expr, selfTypeName))) "<*, *>" else ""
                "when ($subjectVar) { is $result.Ok$starArgs -> $okBranch; is $result.Err$starArgs -> $errBranch }"
            } else {
                "if ($subjectVar.isSuccess) $okBranch else $errBranch"
            }
        if (wrapInRun) {
            "run {\n${ifIndent}val $subjectVar = $subject\n$ifIndent$ifText\n$indent}"
        } else {
            ifText
        }
    } finally {
        scopeBuilder.exitCompr()
    }
}

internal fun KotlinJ2KEmitter.renderResultBranchBlock(
    bodyExpr: Expr,
    bindings: List<String>,
    indent: String,
    selfTypeName: String?,
): String {
    val blockLines = mutableListOf<String>()
    blockLines.addAll(bindings)
    if (bodyExpr is Expr.BlockExpr) {
        val localSigs = linkedMapOf<String, Int>()
        for (s in bodyExpr.block.stmts) {
            val r = renderStmt(s, "$indent    ", selfTypeName, isTailReturn = false, localSignatures = localSigs)
            if (r.isNotBlank()) blockLines += r
        }
    } else {
        blockLines += "$indent    ${renderExpr(bodyExpr, "$indent    ", selfTypeName)}"
    }
    return if (blockLines.isEmpty()) "{}" else "{\n${blockLines.joinToString("\n")}\n$indent}"
}

internal fun KotlinJ2KEmitter.expandOrArmsWithBindings(arms: List<Arm>): List<Arm> =
    arms.flatMap { arm ->
        val guardWrapper = arm.pat as? Pat.Guard
        val unwrapped = unwrapTransparentPat(guardWrapper?.pat ?: arm.pat)
        if (unwrapped is Pat.Or && armReferencesSubject(unwrapped)) {
            unwrapped.cases.toList().map { subCase ->
                val rebuiltPat =
                    if (guardWrapper != null) {
                        Pat.Guard(
                            attrs = guardWrapper.attrs,
                            pat = subCase,
                            ifToken = guardWrapper.ifToken,
                            guard = guardWrapper.guard,
                        )
                    } else {
                        subCase
                    }
                Arm(
                    attrs = arm.attrs,
                    pat = rebuiltPat,
                    fatArrowToken = arm.fatArrowToken,
                    body = arm.body,
                    comma = arm.comma,
                )
            }
        } else {
            listOf(arm)
        }
    }

internal fun KotlinJ2KEmitter.isOptionSomeTupleStructPat(pat: Pat.TupleStruct): Boolean {
    if ("Some" in inspector.declaredTypeNames || hasDeclaredOptionEnum()) return false
    val segs = pat.path.segments.toList()
    return (segs.size == 1 && segs[0].ident.toString() == "Some") ||
        (
            segs.size >= 2 &&
                segs.last().ident.toString() == "Some" &&
                segs.dropLast(1).all {
                    val prefix = it.ident.toString()
                    prefix in IGNORED_PATH_PREFIXES ||
                        prefix in STDLIB_PRELUDE_REEXPORT_MODULES ||
                        prefix == "Option" ||
                        prefix == "option"
                }
        )
}

internal fun KotlinJ2KEmitter.isPlainIrrefutableSomePat(pat: Pat): Boolean {
    val u = unwrapTransparentPat(pat)
    if (u !is Pat.TupleStruct || !isOptionSomeTupleStructPat(u)) return false
    val sub = u.elems.toList().firstOrNull() ?: return true
    return !isDiscriminatingSubpattern(sub) && !patRequiresBooleanCondition(sub)
}

internal fun KotlinJ2KEmitter.isOptionNonePat(pat: Pat): Boolean {
    if (inspector.hasLocalNoneVariant) return false
    return when (val u = unwrapTransparentPat(pat)) {
        is Pat.Path -> {
            val segs = u.path.segments.toList()
            (segs.size == 1 && segs[0].ident.toString() == "None") ||
                (
                    segs.size >= 2 &&
                        segs.last().ident.toString() == "None" &&
                        segs.dropLast(1).all {
                            val prefix = it.ident.toString()
                            prefix in IGNORED_PATH_PREFIXES ||
                                prefix in STDLIB_PRELUDE_REEXPORT_MODULES ||
                                prefix == "Option" ||
                                prefix == "option"
                        }
                )
        }
        is Pat.Ident -> u.subpat == null && u.ident.toString() == "None"
        else -> false
    }
}

internal fun KotlinJ2KEmitter.deduplicateComplementaryCfgArms(arms: List<Arm>): List<Arm> {
    val activeArms = arms.filterNot { isDocOnlyCfgAttribute(it.attrs) }
    if (activeArms.size < 2) return activeArms
    val seenCfgByPatAndPred = linkedMapOf<Pair<String, String>, Pair<Boolean, Int>>()
    val result = ArrayList<Arm>(activeArms.size)
    for (arm in activeArms) {
        if (armGuardExpr(arm) == null) {
            val cfgSig = extractArmCfgSignature(arm.attrs)
            val patKey = armIrrefutableConstructorKey(armPattern(arm))
            if (cfgSig != null && patKey != null) {
                val key = patKey to cfgSig.first
                val prev = seenCfgByPatAndPred[key]
                if (prev != null && prev.first != cfgSig.second) {
                    // Prefer positive `#[cfg(pred)]` (second == false) over `#[cfg(not(pred))]` (second == true).
                    if (!cfgSig.second) {
                        result[prev.second] = arm
                        seenCfgByPatAndPred[key] = cfgSig.second to prev.second
                    }
                    continue
                }
                seenCfgByPatAndPred[key] = cfgSig.second to result.size
            }
        }
        result += arm
    }
    return result
}

internal fun KotlinJ2KEmitter.extractArmCfgSignature(attrs: List<Attribute>): Pair<String, Boolean>? {
    for (attr in attrs) {
        val meta = attr.meta
        if (meta is Meta.List && meta.path.isIdent("cfg")) {
            val trees = meta.tokens.clone().toList()
            if (trees.size == 2 &&
                (trees[0] as? TokenTree.Ident)?.value?.toString() == "not" &&
                (trees[1] as? TokenTree.Group)?.value?.delimiter() == Delimiter.Parenthesis
            ) {
                val innerStream = (trees[1] as TokenTree.Group).value.stream()
                val inner = innerStream.toString().trim()
                if (inner.isNotEmpty()) return inner to true
            }
            val raw = meta.tokens.toString().trim()
            if (raw.isNotEmpty()) return raw to false
        }
    }
    return null
}

internal fun KotlinJ2KEmitter.joinPathIdents(path: Path): String {
    val segs = path.segments.toList()
    return segs.joinToString("::") { it.ident.toString() }
}

internal fun KotlinJ2KEmitter.armIrrefutableConstructorKey(pat: Pat): String? =
    when (val u = unwrapTransparentPat(pat)) {
        is Pat.TupleStruct -> {
            if (u.elems.toList().any { isDiscriminatingSubpattern(it) }) {
                null
            } else {
                joinPathIdents(u.path)
            }
        }
        is Pat.Struct -> {
            if (u.fields.toList().any { isDiscriminatingSubpattern(it.pat) }) {
                null
            } else {
                joinPathIdents(u.path)
            }
        }
        is Pat.Path -> joinPathIdents(u.path)
        is Pat.Wild -> "_"
        is Pat.Ident -> if (u.subpat == null && u.ident.toString() == "_") "_" else null
        else -> null
    }

internal fun KotlinJ2KEmitter.renderBooleanExprMatch(
    expr: Expr.Match,
    subject: String,
    isSimpleSubject: Boolean,
    indent: String,
    selfTypeName: String?,
): String {
    val arms = expandOrArmsWithBindings(deduplicateComplementaryCfgArms(expr.arms))
    scopeBuilder.enterCompr()
    return try {
        val wrapInRun = !isSimpleSubject
        val subjectVar =
            if (wrapInRun) {
                scopeBuilder.bindLocal("_match", "matchVal", expr.expr.span()).kotlinName
            } else {
                subject
            }
        inferExprRenderedType(expr.expr, selfTypeName)?.let {
            currentTypedLocals = currentTypedLocals + (subjectVar to it)
        }
        val whenIndent = if (wrapInRun) "$indent    " else indent
        val armIndent = "$whenIndent    "

        val sb = StringBuilder()
        if (wrapInRun) {
            sb.append("run {\n")
            sb
                .append(whenIndent)
                .append("val ")
                .append(subjectVar)
                .append(" = ")
                .append(subject)
                .append('\n')
            sb.append(whenIndent).append("when {\n")
        } else {
            sb.append("when {\n")
        }

        var hasElseArm = false
        for ((idx, arm) in arms.withIndex()) {
            val isLastArm = idx == arms.lastIndex
            val (renderedArm, isElse) =
                renderBooleanMatchArm(
                    arm = arm,
                    subjectText = subjectVar,
                    indent = armIndent,
                    selfTypeName = selfTypeName,
                    isLastArm = isLastArm,
                )
            if (isElse) {
                hasElseArm = true
            }
            sb.append(renderedArm).append('\n')
        }
        if (!hasElseArm) {
            sb.append(armIndent).append("else -> error(\"unreachable\")\n")
        }
        sb.append(whenIndent).append("}")
        if (wrapInRun) {
            sb.append('\n').append(indent).append("}")
        }
        sb.toString()
    } finally {
        scopeBuilder.exitCompr()
    }
}

internal fun KotlinJ2KEmitter.armReferencesSubject(pat: Pat): Boolean =
    when (pat) {
        is Pat.Ident -> {
            val raw = pat.ident.toString().removePrefix("r#")
            val isBinding =
                raw != "_" &&
                    raw != "None" &&
                    !isScreamingSnakeIdent(raw) &&
                    raw !in inspector.declaredConstNames &&
                    (!raw.first().isUpperCase() || pat.subpat != null)
            isBinding || (pat.subpat != null && armReferencesSubject(pat.subpat!!))
        }
        is Pat.TupleStruct -> pat.elems.toList().isNotEmpty()
        is Pat.Struct -> pat.fields.toList().isNotEmpty()
        is Pat.Tuple -> pat.elems.toList().isNotEmpty()
        is Pat.Slice -> true
        is Pat.Or -> pat.cases.toList().any { armReferencesSubject(it) }
        is Pat.Reference -> armReferencesSubject(pat.pat)
        is Pat.PatParen -> armReferencesSubject(pat.pat)
        is Pat.TypeAscription -> armReferencesSubject(pat.pat)
        is Pat.Guard -> armReferencesSubject(pat.pat)
        else -> false
    }

internal fun KotlinJ2KEmitter.parameterizedVariantCastType(variantType: String): String {
    if ('<' in variantType) return variantType
    val tps =
        inspector.enumTypeParamsByVariant[variantType]
            ?: inspector.enumTypeParamsByVariant[variantType.substringAfterLast('.')]
            ?: return variantType
    if (tps.isEmpty()) return variantType
    val args =
        if (tps.all { it in activeTypeParamNames }) {
            tps.joinToString(", ")
        } else {
            tps.joinToString(", ") { "*" }
        }
    return "$variantType<$args>"
}

internal fun KotlinJ2KEmitter.renderSubjectMatchArm(
    arm: Arm,
    patCond: String,
    subjectText: String,
    indent: String,
    selfTypeName: String?,
    wrapBodyBeforeInArm: Boolean,
    mutRefWritebackTarget: String? = null,
): String {
    val triviaPrefix = renderLeadingTriviaForSpan(arm.span(), indent)
    val isSingleSmartCast = patCond.startsWith("is ") && !patCond.contains(", ")
    val usedIdents = collectUsedRawIdentsInExpr(arm.body)
    val prevTyped = currentTypedLocals
    val needsWriteback = mutRefWritebackTarget != null && matchArmMutatesTupleVariantBinding(arm)
    val trailingLines = mutableListOf<String>()
    scopeBuilder.enterCompr()
    return try {
        val bindings =
            extractMatchArmBindings(
                armPattern(arm),
                subjectText,
                "$indent    ",
                selfTypeName,
                bindToScope = true,
                subjectAlreadySmartCast = isSingleSmartCast,
                usedBodyIdents = usedIdents,
                mutRefWritebackTarget = if (needsWriteback) mutRefWritebackTarget else null,
                writebackLinesOut = trailingLines,
            )
        val renderedBody =
            renderMatchArmBody(
                arm.body,
                bindings,
                indent,
                selfTypeName,
                wrapBodyBeforeInArm,
                trailingLines = trailingLines,
            )
        "$triviaPrefix$indent$patCond -> $renderedBody"
    } finally {
        scopeBuilder.exitCompr()
        currentTypedLocals = prevTyped
    }
}

internal fun KotlinJ2KEmitter.renderBooleanMatchArm(
    arm: Arm,
    subjectText: String,
    indent: String,
    selfTypeName: String?,
    isLastArm: Boolean,
): Pair<String, Boolean> {
    val triviaPrefix = renderLeadingTriviaForSpan(arm.span(), indent)
    val basePat = armPattern(arm)
    val usedIdents = collectUsedRawIdentsInExpr(arm.body)
    val prevTyped = currentTypedLocals
    val savedKind = currentExpectedNumericKind
    val savedTailOk = currentTailExpectsUnitResultOk
    scopeBuilder.enterCompr()
    return try {
        val (patCond, guardText) =
            try {
                currentExpectedNumericKind = null
                currentTailExpectsUnitResultOk = false
                val condPart = renderPatternBooleanCondition(basePat, subjectText, indent, selfTypeName)
                val guardExpr = armGuardExpr(arm)
                val guardPart =
                    if (guardExpr != null) {
                        val guardAccess = linkedMapOf<String, String>()
                        collectPatternGuardAccessMap(basePat, subjectText, selfTypeName, guardAccess)
                        val prevSubstitutions = activeGuardBindingSubstitutions
                        activeGuardBindingSubstitutions = prevSubstitutions + guardAccess
                        try {
                            renderExpr(guardExpr, indent, selfTypeName)
                        } finally {
                            activeGuardBindingSubstitutions = prevSubstitutions
                        }
                    } else {
                        null
                    }
                condPart to guardPart
            } finally {
                currentExpectedNumericKind = savedKind
                currentTailExpectsUnitResultOk = savedTailOk
            }
        val isSingleSmartCast =
            guardText?.contains(" || ") != true &&
                !patCond.contains(" || ") &&
                patCond.startsWith("$subjectText is ") &&
                '<' !in patCond.removePrefix("$subjectText is ").substringBefore(" && ") &&
                ((guardText == null && !patCond.contains(" && ")) || scopeBuilder.isSmartCastableLocalKotlinName(subjectText))
        val bindings =
            extractMatchArmBindings(
                basePat,
                subjectText,
                "$indent    ",
                selfTypeName,
                bindToScope = true,
                subjectAlreadySmartCast = isSingleSmartCast,
                usedBodyIdents = usedIdents,
            )
        val renderedBody = renderMatchArmBody(arm.body, bindings, indent, selfTypeName, wrapBodyBeforeInArm = false)

        val fullCond =
            if (isLastArm && guardText == null) {
                "else"
            } else if (patCond == "true" && guardText == null) {
                "true"
            } else if (patCond == "true" && guardText != null) {
                guardText
            } else if (guardText == null) {
                patCond
            } else {
                val lhs = if (patCond.contains(" || ")) "($patCond)" else patCond
                val rhs = if (guardText.contains(" || ")) "($guardText)" else guardText
                "$lhs && $rhs"
            }

        "$triviaPrefix$indent$fullCond -> $renderedBody" to (fullCond == "else")
    } finally {
        scopeBuilder.exitCompr()
        currentTypedLocals = prevTyped
    }
}

internal fun KotlinJ2KEmitter.renderMatchArmBody(
    bodyExpr: Expr,
    bindings: List<String>,
    indent: String,
    selfTypeName: String?,
    wrapBodyBeforeInArm: Boolean,
    trailingLines: List<String> = emptyList(),
): String {
    if (bindings.isNotEmpty() || trailingLines.isNotEmpty()) {
        val savedTailOk = currentTailExpectsUnitResultOk
        val blockLines = mutableListOf<String>()
        blockLines.addAll(bindings)
        var endsInJump = false
        if (bodyExpr is Expr.BlockExpr) {
            val localSigs = linkedMapOf<String, Int>()
            val stmts = bodyExpr.block.stmts
            for ((idx, s) in stmts.withIndex()) {
                val isTailStmt = (idx == stmts.lastIndex) && trailingLines.isEmpty()
                val r =
                    try {
                        currentTailExpectsUnitResultOk = isTailStmt && savedTailOk
                        renderStmt(
                            s,
                            "$indent    ",
                            selfTypeName,
                            isTailReturn = false,
                            isLastExecutableStmt = isTailStmt,
                            localSignatures = localSigs,
                        )
                    } finally {
                        currentTailExpectsUnitResultOk = savedTailOk
                    }
                if (r.isNotBlank()) blockLines += r
            }
            val lastStmt = stmts.lastOrNull()
            if (lastStmt is Stmt.ExprStmt &&
                (lastStmt.expr is Expr.Return || lastStmt.expr is Expr.Break || lastStmt.expr is Expr.Continue)
            ) {
                endsInJump = true
            }
        } else {
            val rawLeaf = renderExpr(bodyExpr, "$indent    ", selfTypeName)
            val renderedLeaf =
                if (savedTailOk && trailingLines.isEmpty()) {
                    coerceTailFmtUnitToResultOk(bodyExpr, rawLeaf, selfTypeName)
                } else {
                    rawLeaf
                }
            blockLines += "$indent    $renderedLeaf"
            if (bodyExpr is Expr.Return || bodyExpr is Expr.Break || bodyExpr is Expr.Continue) {
                endsInJump = true
            }
        }
        if (!endsInJump) {
            blockLines.addAll(trailingLines)
        }
        return "{\n${blockLines.joinToString("\n")}\n$indent}"
    }
    if (bodyExpr is Expr.BlockExpr) {
        return renderBlock(bodyExpr.block, indent, selfTypeName)
    }
    val rawRendered = renderExpr(bodyExpr, indent, selfTypeName)
    val isJumpExpr = bodyExpr is Expr.Return || bodyExpr is Expr.Break || bodyExpr is Expr.Continue
    val rendered =
        if (currentTailExpectsUnitResultOk && !isJumpExpr) {
            coerceTailFmtUnitToResultOk(bodyExpr, rawRendered, selfTypeName)
        } else {
            rawRendered
        }
    if (!wrapBodyBeforeInArm && !isJumpExpr) {
        return rendered
    }
    return if (rendered.contains('\n')) {
        "{\n$indent    $rendered\n$indent}"
    } else {
        "{ $rendered }"
    }
}

internal fun KotlinJ2KEmitter.isPlainStructPattern(pat: Pat.Struct, variantType: String): Boolean {
    if (variantType in inspector.structFieldNamesByType) return true
    val segs = pat.path.segments.toList()
    if (segs.size != 1 || '.' in variantType) return false
    val rawSeg = segs[0].ident.toString().removePrefix("r#")
    val modBinding = scopeBuilder.resolveModuleBinding(rawSeg)
    val importedPath = modBinding?.importedPath
    if (importedPath != null) {
        val importedOwner =
            importedPath
                .substringBeforeLast('.', missingDelimiterValue = "")
                .substringAfterLast('.')
        if (importedOwner.isNotEmpty()) {
            return !importedOwner.first().isUpperCase()
        }
    }
    if (inspector.hasImportedEnumGlob) return false
    return variantType !in inspector.enumVariantSimpleNames &&
        variantType !in inspector.importedEnumVariantSimpleNames
}

internal fun KotlinJ2KEmitter.patRequiresBooleanCondition(pat: Pat): Boolean =
    when (pat) {
        is Pat.Wild, is Pat.Rest, is Pat.Lit, is Pat.Path -> false
        is Pat.Range -> pat.start == null || pat.end == null
        is Pat.Ident -> pat.subpat?.let(this::patRequiresBooleanCondition) ?: false
        is Pat.Or -> pat.cases.toList().any(this::patRequiresBooleanCondition)
        is Pat.Tuple -> pat.elems.toList().any(this::isDiscriminatingSubpattern)
        is Pat.Slice -> pat.elems.toList().any { it !is Pat.Rest && !(it is Pat.Ident && it.subpat is Pat.Rest) } || pat.elems.isEmpty()
        is Pat.TupleStruct -> {
            val elems = pat.elems.toList()
            if (isOptionSomeTupleStructPat(pat)) {
                elems.firstOrNull()?.let(this::patRequiresBooleanCondition) ?: false
            } else {
                elems.any(this::isDiscriminatingSubpattern)
            }
        }
        is Pat.Struct -> pat.fields.toList().any { isDiscriminatingSubpattern(it.pat) }
        is Pat.Reference -> patRequiresBooleanCondition(pat.pat)
        is Pat.PatParen -> patRequiresBooleanCondition(pat.pat)
        is Pat.TypeAscription -> patRequiresBooleanCondition(pat.pat)
        is Pat.Guard -> true
        else -> false
    }

internal fun KotlinJ2KEmitter.isDiscriminatingSubpattern(pat: Pat): Boolean =
    when (pat) {
        is Pat.Wild, is Pat.Rest -> false
        is Pat.Ident -> {
            val raw = pat.ident.toString().removePrefix("r#")
            val isCtorOrConst =
                raw == "None" ||
                    isScreamingSnakeIdent(raw) ||
                    raw in inspector.declaredConstNames ||
                    (raw.first().isUpperCase() && pat.subpat == null)
            isCtorOrConst || (pat.subpat?.let(this::isDiscriminatingSubpattern) ?: false)
        }
        is Pat.Tuple -> pat.elems.toList().any(this::isDiscriminatingSubpattern)
        is Pat.Reference -> isDiscriminatingSubpattern(pat.pat)
        is Pat.PatParen -> isDiscriminatingSubpattern(pat.pat)
        is Pat.TypeAscription -> isDiscriminatingSubpattern(pat.pat)
        else -> true
    }

internal fun KotlinJ2KEmitter.renderPatternBooleanCondition(
    pat: Pat,
    subjectExpr: String,
    indent: String,
    selfTypeName: String?,
): String =
    when (pat) {
        is Pat.Wild, is Pat.Rest -> "true"
        is Pat.Lit -> "$subjectExpr == ${renderLit(pat.lit)}"
        is Pat.Range -> {
            val start = pat.start?.let { renderExpr(it, indent, selfTypeName) }
            val end = pat.end?.let { renderExpr(it, indent, selfTypeName) }
            val isHalfOpen = pat.limits is RangeLimits.HalfOpen
            when {
                start != null && end != null ->
                    if (isHalfOpen) "$subjectExpr in $start until $end" else "$subjectExpr in $start..$end"
                start != null -> "$subjectExpr >= $start"
                end != null -> if (isHalfOpen) "$subjectExpr < $end" else "$subjectExpr <= $end"
                else -> "true"
            }
        }
        is Pat.Path -> {
            val segs = pat.path.segments.toList()
            if (segs.size == 1 && segs[0].ident.toString() == "None" && !inspector.hasLocalNoneVariant) {
                "$subjectExpr == null"
            } else {
                "$subjectExpr == ${renderPathAsExpression(pat.path, selfTypeName, asCallee = false)}"
            }
        }
        is Pat.Ident -> {
            val raw = pat.ident.toString().removePrefix("r#")
            val selfCond =
                when {
                    raw == "_" -> "true"
                    raw == "None" && !inspector.hasLocalNoneVariant -> "$subjectExpr == null"
                    raw in inspector.declaredConstNames -> "$subjectExpr == ${inspector.declaredConstNames.getValue(raw)}"
                    isScreamingSnakeIdent(raw) -> "$subjectExpr == ${rustConstIdentToKotlin(raw)}"
                    raw.first().isUpperCase() && pat.subpat == null -> {
                        val ktType = rustTypeIdentToKotlin(raw)
                        if (ktType in inspector.unitStructNames) "$subjectExpr is $ktType" else "$subjectExpr == $ktType"
                    }
                    else -> "true"
                }
            val subCond = pat.subpat?.let { renderPatternBooleanCondition(it, subjectExpr, indent, selfTypeName) } ?: "true"
            combineAndConditions(listOf(selfCond, subCond))
        }
        is Pat.TupleStruct -> {
            val elems = pat.elems.toList()
            if (isOptionSomeTupleStructPat(pat)) {
                val innerCond =
                    elems.firstOrNull()?.let { renderPatternBooleanCondition(it, subjectExpr, indent, selfTypeName) } ?: "true"
                combineAndConditions(listOf("$subjectExpr != null", innerCond))
            } else {
                val coreResultInfo = coreResultVariantMatchInfo(pat)
                if (coreResultInfo != null) {
                    val starArgs = if (isUntypedResultScrutinee(currentTypedLocals[subjectExpr])) "<*, *>" else ""
                    val castType = "${coreResultInfo.first}$starArgs"
                    val elemRecv =
                        if (scopeBuilder.isSmartCastableLocalKotlinName(subjectExpr)) {
                            subjectExpr
                        } else {
                            "($subjectExpr as $castType)"
                        }
                    val parts = mutableListOf("$subjectExpr is $castType")
                    elems.firstOrNull()?.let { sub ->
                        if (sub !is Pat.Rest) {
                            parts += renderPatternBooleanCondition(sub, "$elemRecv.${coreResultInfo.second}", indent, selfTypeName)
                        }
                    }
                    combineAndConditions(parts)
                } else {
                    val variantType = qualifyShadowedPatternVariantType(renderPathAsType(pat.path, selfTypeName), subjectExpr, selfTypeName)
                    val castVariantType = parameterizedVariantCastType(variantType)
                    val elemRecv =
                        if ('<' !in variantType && scopeBuilder.isSmartCastableLocalKotlinName(subjectExpr)) {
                            subjectExpr
                        } else {
                            "($subjectExpr as $castVariantType)"
                        }
                    val parts = mutableListOf("$subjectExpr is $variantType")
                    for ((idx, sub) in elems.withIndex()) {
                        if (sub is Pat.Rest) break
                        val propName = tupleFieldPropertyName(idx, elems.size)
                        parts += renderPatternBooleanCondition(sub, "$elemRecv.$propName", indent, selfTypeName)
                    }
                    combineAndConditions(parts)
                }
            }
        }
        is Pat.Struct -> {
            val variantType = qualifyShadowedPatternVariantType(renderPathAsType(pat.path, selfTypeName), subjectExpr, selfTypeName)
            val isPlainStruct = isPlainStructPattern(pat, variantType)
            val parts = if (isPlainStruct) mutableListOf() else mutableListOf("$subjectExpr is $variantType")
            val structRecv =
                when {
                    isPlainStruct -> subjectExpr
                    '<' !in variantType && scopeBuilder.isSmartCastableLocalKotlinName(subjectExpr) -> subjectExpr
                    else -> "($subjectExpr as ${parameterizedVariantCastType(variantType)})"
                }
            for (fp in pat.fields.toList()) {
                val fieldName = renderMemberName(fp.member)
                parts += renderPatternBooleanCondition(fp.pat, "$structRecv.$fieldName", indent, selfTypeName)
            }
            combineAndConditions(parts)
        }
        is Pat.Tuple -> {
            val elems = pat.elems.toList()
            val parts = mutableListOf<String>()
            for ((idx, sub) in elems.withIndex()) {
                if (sub is Pat.Rest) break
                val elemAccess = tupleIndexExpr(subjectExpr, idx, elems.size)
                parts += renderPatternBooleanCondition(sub, elemAccess, indent, selfTypeName)
            }
            combineAndConditions(parts)
        }
        is Pat.Slice -> {
            val elems = pat.elems.toList()
            val restIdx = elems.indexOfFirst { it is Pat.Rest || (it is Pat.Ident && it.subpat is Pat.Rest) }
            val parts = mutableListOf<String>()
            if (restIdx < 0) {
                parts += if (elems.isEmpty()) "$subjectExpr.isEmpty()" else "$subjectExpr.size == ${elems.size}"
                for ((idx, sub) in elems.withIndex()) {
                    parts += renderPatternBooleanCondition(sub, "$subjectExpr[$idx]", indent, selfTypeName)
                }
            } else {
                val suffix = elems.subList(restIdx + 1, elems.size)
                val minLen = restIdx + suffix.size
                if (minLen > 0) {
                    parts += "$subjectExpr.size >= $minLen"
                }
                for (idx in 0 until restIdx) {
                    parts += renderPatternBooleanCondition(elems[idx], "$subjectExpr[$idx]", indent, selfTypeName)
                }
                for ((offset, sub) in suffix.withIndex()) {
                    val tailOffset = suffix.size - offset
                    parts += renderPatternBooleanCondition(sub, "$subjectExpr[$subjectExpr.size - $tailOffset]", indent, selfTypeName)
                }
            }
            combineAndConditions(parts)
        }
        is Pat.Or -> {
            val cases = pat.cases.toList().map { renderPatternBooleanCondition(it, subjectExpr, indent, selfTypeName) }
            if (cases.any { it == "true" }) {
                "true"
            } else {
                cases.joinToString(" || ") { if (it.contains(" && ")) "($it)" else it }
            }
        }
        is Pat.Reference -> renderPatternBooleanCondition(pat.pat, subjectExpr, indent, selfTypeName)
        is Pat.PatParen -> renderPatternBooleanCondition(pat.pat, subjectExpr, indent, selfTypeName)
        is Pat.TypeAscription -> renderPatternBooleanCondition(pat.pat, subjectExpr, indent, selfTypeName)
        is Pat.Guard -> {
            val baseCond = renderPatternBooleanCondition(pat.pat, subjectExpr, indent, selfTypeName)
            val guardAccess = linkedMapOf<String, String>()
            collectPatternGuardAccessMap(pat.pat, subjectExpr, selfTypeName, guardAccess)
            val prevSubstitutions = activeGuardBindingSubstitutions
            activeGuardBindingSubstitutions = prevSubstitutions + guardAccess
            val guardCond =
                try {
                    renderExpr(pat.guard, indent, selfTypeName)
                } finally {
                    activeGuardBindingSubstitutions = prevSubstitutions
                }
            combineAndConditions(listOf(baseCond, guardCond))
        }
        else -> "true"
    }

internal fun KotlinJ2KEmitter.combineAndConditions(parts: List<String>): String {
    val active = parts.filter { it.isNotEmpty() && it != "true" }
    if (active.isEmpty()) return "true"
    if (active.size == 1) return active[0]
    return active.joinToString(" && ") { if (it.contains(" || ")) "($it)" else it }
}

internal fun KotlinJ2KEmitter.collectPatternGuardAccessMap(
    pat: Pat,
    subjectText: String,
    selfTypeName: String?,
    out: MutableMap<String, String>,
) {
    when (pat) {
        is Pat.Ident -> {
            val raw = pat.ident.toString().removePrefix("r#")
            val isBinding =
                raw != "_" &&
                    raw != "None" &&
                    !isScreamingSnakeIdent(raw) &&
                    raw !in inspector.declaredConstNames &&
                    (!raw.first().isUpperCase() || pat.subpat != null)
            if (isBinding) {
                out[raw] = subjectText
            }
            pat.subpat?.let { collectPatternGuardAccessMap(it, subjectText, selfTypeName, out) }
        }
        is Pat.TupleStruct -> {
            val elems = pat.elems.toList()
            if (isOptionSomeTupleStructPat(pat) && elems.size == 1) {
                collectPatternGuardAccessMap(elems[0], subjectText, selfTypeName, out)
            } else {
                val coreResultInfo = coreResultVariantMatchInfo(pat)
                if (coreResultInfo != null && elems.size == 1 && elems[0] !is Pat.Rest) {
                    val starArgs = if (isUntypedResultScrutinee(currentTypedLocals[subjectText])) "<*, *>" else ""
                    collectPatternGuardAccessMap(elems[0], "($subjectText as ${coreResultInfo.first}$starArgs).${coreResultInfo.second}", selfTypeName, out)
                } else {
                    val variantType = qualifyShadowedPatternVariantType(renderPathAsType(pat.path, selfTypeName), subjectText, selfTypeName)
                    for ((idx, sub) in elems.withIndex()) {
                        if (sub is Pat.Rest) break
                        val propName = tupleFieldPropertyName(idx, elems.size)
                        collectPatternGuardAccessMap(sub, "($subjectText as $variantType).$propName", selfTypeName, out)
                    }
                }
            }
        }
        is Pat.Struct -> {
            val variantType = qualifyShadowedPatternVariantType(renderPathAsType(pat.path, selfTypeName), subjectText, selfTypeName)
            val isPlainStruct = isPlainStructPattern(pat, variantType)
            val structRecv =
                if (isPlainStruct) {
                    subjectText
                } else {
                    "($subjectText as ${parameterizedVariantCastType(variantType)})"
                }
            for (fp in pat.fields.toList()) {
                val fieldName = renderMemberName(fp.member)
                collectPatternGuardAccessMap(fp.pat, "$structRecv.$fieldName", selfTypeName, out)
            }
        }
        is Pat.Tuple -> {
            val elems = pat.elems.toList()
            for ((idx, sub) in elems.withIndex()) {
                if (sub is Pat.Rest) break
                val elemAccess = tupleIndexExpr(subjectText, idx, elems.size)
                collectPatternGuardAccessMap(sub, elemAccess, selfTypeName, out)
            }
        }
        is Pat.Slice -> {
            val elems = pat.elems.toList()
            val restIdx = elems.indexOfFirst { it is Pat.Rest || (it is Pat.Ident && it.subpat is Pat.Rest) }
            if (restIdx < 0) {
                for ((idx, sub) in elems.withIndex()) {
                    collectPatternGuardAccessMap(sub, "$subjectText[$idx]", selfTypeName, out)
                }
            } else {
                val suffix = elems.subList(restIdx + 1, elems.size)
                for (idx in 0 until restIdx) {
                    collectPatternGuardAccessMap(elems[idx], "$subjectText[$idx]", selfTypeName, out)
                }
                val restPat = elems[restIdx]
                if (restPat is Pat.Ident) {
                    val restAccess =
                        when {
                            restIdx == 0 && suffix.isEmpty() -> subjectText
                            suffix.isEmpty() -> "$subjectText.subList($restIdx, $subjectText.size)"
                            else -> "$subjectText.subList($restIdx, $subjectText.size - ${suffix.size})"
                        }
                    collectPatternGuardAccessMap(restPat, restAccess, selfTypeName, out)
                }
                for ((offset, sub) in suffix.withIndex()) {
                    val tailOffset = suffix.size - offset
                    collectPatternGuardAccessMap(sub, "$subjectText[$subjectText.size - $tailOffset]", selfTypeName, out)
                }
            }
        }
        is Pat.Or -> {
            val firstCase = pat.cases.toList().firstOrNull()
            if (firstCase != null) {
                collectPatternGuardAccessMap(firstCase, subjectText, selfTypeName, out)
            }
        }
        is Pat.Reference -> collectPatternGuardAccessMap(pat.pat, subjectText, selfTypeName, out)
        is Pat.PatParen -> collectPatternGuardAccessMap(pat.pat, subjectText, selfTypeName, out)
        is Pat.TypeAscription -> collectPatternGuardAccessMap(pat.pat, subjectText, selfTypeName, out)
        is Pat.Guard -> collectPatternGuardAccessMap(pat.pat, subjectText, selfTypeName, out)
        else -> {}
    }
}

internal fun KotlinJ2KEmitter.extractMatchArmBindings(
    pat: Pat,
    subjectText: String,
    indent: String,
    selfTypeName: String?,
    bindToScope: Boolean = false,
    subjectAlreadySmartCast: Boolean = false,
    usedBodyIdents: Set<String>? = null,
    mutRefWritebackTarget: String? = null,
    writebackLinesOut: MutableList<String>? = null,
): List<String> =
    when (pat) {
        is Pat.Ident -> {
            val raw = pat.ident.toString().removePrefix("r#")
            val isUnusedUnderscore = raw.startsWith("_") && usedBodyIdents != null && raw !in usedBodyIdents
            val isBinding =
                !isUnusedUnderscore &&
                    raw != "_" &&
                    raw != "None" &&
                    !isScreamingSnakeIdent(raw) &&
                    raw !in inspector.declaredConstNames &&
                    (!raw.first().isUpperCase() || pat.subpat != null)
            val selfLine =
                if (isBinding) {
                    val assignCount = if (pat.mutability != null) AssignCount.Any else AssignCount.AtMostOnce
                    val ktName =
                        if (bindToScope) {
                            scopeBuilder.bindLocal(raw, rustValueIdentToKotlin(raw), pat.ident.span(), assignCount = assignCount).kotlinName
                        } else {
                            rustValueIdentToKotlin(raw)
                        }
                    if (bindToScope) {
                        currentTypedLocals[subjectText]?.let {
                            currentTypedLocals = currentTypedLocals + (ktName to it.removeSuffix("?"))
                        }
                    }
                    if (ktName == subjectText) emptyList() else listOf("${indent}val $ktName = $subjectText")
                } else {
                    emptyList()
                }
            val subLines =
                pat.subpat
                    ?.let {
                        extractMatchArmBindings(
                            it,
                            subjectText,
                            indent,
                            selfTypeName,
                            bindToScope,
                            subjectAlreadySmartCast = subjectAlreadySmartCast,
                            usedBodyIdents = usedBodyIdents,
                        )
                    }.orEmpty()
            selfLine + subLines
        }
        is Pat.TupleStruct -> {
            if (isOptionSomeTupleStructPat(pat) && pat.elems.size == 1) {
                extractMatchArmBindings(
                    pat.elems.first()!!,
                    subjectText,
                    indent,
                    selfTypeName,
                    bindToScope,
                    subjectAlreadySmartCast = false,
                    usedBodyIdents = usedBodyIdents,
                )
            } else {
                val coreResultInfo = coreResultVariantMatchInfo(pat)
                if (coreResultInfo != null && pat.elems.size == 1) {
                    val sub = pat.elems.first()!!
                    val rawSubIdent = extractRawParamIdent(sub)
                    val isUnusedSub =
                        rawSubIdent != null &&
                            rawSubIdent.startsWith("_") &&
                            usedBodyIdents != null &&
                            rawSubIdent !in usedBodyIdents
                    val lines = mutableListOf<String>()
                    val starArgs = if (isUntypedResultScrutinee(currentTypedLocals[subjectText])) "<*, *>" else ""
                    val accessTarget =
                        if (subjectAlreadySmartCast) {
                            "$subjectText.${coreResultInfo.second}"
                        } else {
                            "($subjectText as ${coreResultInfo.first}$starArgs).${coreResultInfo.second}"
                        }
                    if (!isUnusedSub && rawSubIdent != null && rawSubIdent != "_" && (sub is Pat.Ident || sub is Pat.Reference || sub is Pat.TypeAscription)) {
                        val subNames = extractPatternBindingNames(sub, selfTypeName, includeCtorInTupleStruct = false, bindToScope = bindToScope)
                        val boundName = subNames.singleOrNull()
                        if (boundName != null && boundName != "_") {
                            lines += "${indent}val $boundName = $accessTarget"
                        }
                    } else if (!isUnusedSub && rawSubIdent != "_" && sub !is Pat.Wild && sub !is Pat.Rest) {
                        val holderRaw = "__res_${coreResultInfo.second}"
                        val innerHolder =
                            if (bindToScope) {
                                scopeBuilder
                                    .bindLocal(
                                        rawName = holderRaw,
                                        baseKotlinName = "${coreResultInfo.second}Val",
                                        span = sub.span(),
                                        assignCount = AssignCount.AtMostOnce,
                                    ).kotlinName
                            } else {
                                "${coreResultInfo.second}Val"
                            }
                        lines += "${indent}val $innerHolder = $accessTarget"
                        lines +=
                            extractMatchArmBindings(
                                pat = sub,
                                subjectText = innerHolder,
                                indent = indent,
                                selfTypeName = selfTypeName,
                                bindToScope = bindToScope,
                                subjectAlreadySmartCast = false,
                                usedBodyIdents = usedBodyIdents,
                            )
                    }
                    return lines
                }
                val variantType = qualifyShadowedPatternVariantType(renderPathAsType(pat.path, selfTypeName), subjectText, selfTypeName)
                val castVariantType = parameterizedVariantCastType(variantType)
                val variantFieldTys =
                    inspector.enumVariantFieldTypes[variantType]
                        ?: inspector.enumVariantFieldTypes[variantType.substringAfterLast('.')]
                val elems = pat.elems.toList()
                val mutableVariantWriteback = mutRefWritebackTarget != null && writebackLinesOut != null
                val names =
                    elems.mapIndexed { idx, sub ->
                        val rawSubIdent = extractRawParamIdent(sub)
                        val isUnusedSub =
                            rawSubIdent != null &&
                                rawSubIdent.startsWith("_") &&
                                usedBodyIdents != null &&
                                rawSubIdent !in usedBodyIdents
                        if (!isUnusedSub && (sub is Pat.Ident || sub is Pat.Reference || sub is Pat.TypeAscription)) {
                            val subNames =
                                if (mutableVariantWriteback && bindToScope && rawSubIdent != null && rawSubIdent != "_" && !rawSubIdent.first().isUpperCase()) {
                                    listOf(
                                        scopeBuilder
                                            .bindLocal(
                                                rawSubIdent,
                                                rustValueIdentToKotlin(rawSubIdent),
                                                sub.span(),
                                                assignCount = AssignCount.Any,
                                            ).kotlinName,
                                    )
                                } else {
                                    extractPatternBindingNames(sub, selfTypeName, includeCtorInTupleStruct = false, bindToScope = bindToScope)
                                }
                            if (subNames.size == 1) {
                                val boundName = subNames[0]
                                val fieldSynTy = variantFieldTys?.getOrNull(idx)
                                if (fieldSynTy != null) {
                                    val renderedFieldTy = renderType(fieldSynTy, selfTypeName)
                                    currentTypedLocals = currentTypedLocals + (boundName to renderedFieldTy)
                                }
                                boundName
                            } else {
                                "_"
                            }
                        } else {
                            "_"
                        }
                    }
                val nonWild = names.filter { it != "_" }
                val castTargetExpr = if (subjectAlreadySmartCast) subjectText else "$subjectText as $castVariantType"
                val nestedSubjectExpr = if (subjectAlreadySmartCast) subjectText else "($subjectText as $castVariantType)"
                val bindingKeyword = if (mutableVariantWriteback) "var" else "val"
                val directDestructure =
                    if (nonWild.isNotEmpty()) {
                        listOf("${indent}$bindingKeyword (${names.joinToString(", ")}) = $castTargetExpr")
                    } else {
                        emptyList()
                    }
                if (mutableVariantWriteback && nonWild.isNotEmpty()) {
                    val writebackArgs =
                        names.mapIndexed { idx, bound ->
                            if (bound != "_") {
                                bound
                            } else {
                                "$nestedSubjectExpr.${tupleFieldPropertyName(idx, elems.size)}"
                            }
                        }
                    writebackLinesOut += "${indent}$mutRefWritebackTarget = $variantType(${writebackArgs.joinToString(", ")})"
                }
                val nestedLines =
                    elems.flatMapIndexed { idx, sub ->
                        if (sub is Pat.TupleStruct || sub is Pat.Struct || sub is Pat.Tuple || sub is Pat.Slice) {
                            val propName = tupleFieldPropertyName(idx, elems.size)
                            extractMatchArmBindings(
                                sub,
                                "$nestedSubjectExpr.$propName",
                                indent,
                                selfTypeName,
                                bindToScope,
                                subjectAlreadySmartCast = false,
                                usedBodyIdents = usedBodyIdents,
                            )
                        } else {
                            emptyList()
                        }
                    }
                directDestructure + nestedLines
            }
        }
        is Pat.Struct -> {
            val variantType = qualifyShadowedPatternVariantType(renderPathAsType(pat.path, selfTypeName), subjectText, selfTypeName)
            val isPlainStruct = isPlainStructPattern(pat, variantType)
            val castVariantType = parameterizedVariantCastType(variantType)
            val structRecv =
                if (subjectAlreadySmartCast || isPlainStruct) {
                    subjectText
                } else {
                    "($subjectText as $castVariantType)"
                }
            val lines = mutableListOf<String>()
            for (fp in pat.fields.toList()) {
                val fieldName = renderMemberName(fp.member)
                lines +=
                    extractMatchArmBindings(
                        fp.pat,
                        "$structRecv.$fieldName",
                        indent,
                        selfTypeName,
                        bindToScope,
                        subjectAlreadySmartCast = false,
                        usedBodyIdents = usedBodyIdents,
                    )
            }
            lines
        }
        is Pat.Tuple -> {
            val elems = pat.elems.toList()
            val lines = mutableListOf<String>()
            for ((idx, sub) in elems.withIndex()) {
                if (sub is Pat.Rest) break
                val elemAccess = tupleIndexExpr(subjectText, idx, elems.size)
                lines +=
                    extractMatchArmBindings(
                        sub,
                        elemAccess,
                        indent,
                        selfTypeName,
                        bindToScope,
                        subjectAlreadySmartCast = false,
                        usedBodyIdents = usedBodyIdents,
                    )
            }
            lines
        }
        is Pat.Slice -> {
            val elems = pat.elems.toList()
            val restIdx = elems.indexOfFirst { it is Pat.Rest || (it is Pat.Ident && it.subpat is Pat.Rest) }
            val lines = mutableListOf<String>()
            if (restIdx < 0) {
                for ((idx, sub) in elems.withIndex()) {
                    lines +=
                        extractMatchArmBindings(
                            sub,
                            "$subjectText[$idx]",
                            indent,
                            selfTypeName,
                            bindToScope,
                            subjectAlreadySmartCast = false,
                            usedBodyIdents = usedBodyIdents,
                        )
                }
            } else {
                val suffix = elems.subList(restIdx + 1, elems.size)
                for (idx in 0 until restIdx) {
                    lines +=
                        extractMatchArmBindings(
                            elems[idx],
                            "$subjectText[$idx]",
                            indent,
                            selfTypeName,
                            bindToScope,
                            subjectAlreadySmartCast = false,
                            usedBodyIdents = usedBodyIdents,
                        )
                }
                val restPat = elems[restIdx]
                if (restPat is Pat.Ident) {
                    val restAccess =
                        when {
                            restIdx == 0 && suffix.isEmpty() -> subjectText
                            suffix.isEmpty() -> "$subjectText.subList($restIdx, $subjectText.size)"
                            else -> "$subjectText.subList($restIdx, $subjectText.size - ${suffix.size})"
                        }
                    lines +=
                        extractMatchArmBindings(
                            restPat,
                            restAccess,
                            indent,
                            selfTypeName,
                            bindToScope,
                            subjectAlreadySmartCast = false,
                            usedBodyIdents = usedBodyIdents,
                        )
                }
                for ((offset, sub) in suffix.withIndex()) {
                    val tailOffset = suffix.size - offset
                    lines +=
                        extractMatchArmBindings(
                            sub,
                            "$subjectText[$subjectText.size - $tailOffset]",
                            indent,
                            selfTypeName,
                            bindToScope,
                            subjectAlreadySmartCast = false,
                            usedBodyIdents = usedBodyIdents,
                        )
                }
            }
            lines
        }
        is Pat.Or -> {
            val firstCase = pat.cases.toList().firstOrNull()
            if (firstCase != null) {
                extractMatchArmBindings(
                    firstCase,
                    subjectText,
                    indent,
                    selfTypeName,
                    bindToScope,
                    subjectAlreadySmartCast = subjectAlreadySmartCast,
                    usedBodyIdents = usedBodyIdents,
                )
            } else {
                emptyList()
            }
        }
        is Pat.Reference ->
            extractMatchArmBindings(
                pat.pat,
                subjectText,
                indent,
                selfTypeName,
                bindToScope,
                subjectAlreadySmartCast = subjectAlreadySmartCast,
                usedBodyIdents = usedBodyIdents,
            )
        is Pat.PatParen ->
            extractMatchArmBindings(
                pat.pat,
                subjectText,
                indent,
                selfTypeName,
                bindToScope,
                subjectAlreadySmartCast = subjectAlreadySmartCast,
                usedBodyIdents = usedBodyIdents,
            )
        is Pat.TypeAscription ->
            extractMatchArmBindings(
                pat.pat,
                subjectText,
                indent,
                selfTypeName,
                bindToScope,
                subjectAlreadySmartCast = subjectAlreadySmartCast,
                usedBodyIdents = usedBodyIdents,
            )
        is Pat.Guard ->
            extractMatchArmBindings(
                pat.pat,
                subjectText,
                indent,
                selfTypeName,
                bindToScope,
                subjectAlreadySmartCast = subjectAlreadySmartCast,
                usedBodyIdents = usedBodyIdents,
            )
        else -> emptyList()
    }

internal fun KotlinJ2KEmitter.tupleIndexExpr(subjectText: String, idx: Int, arity: Int): String =
    when {
        arity <= 3 && idx == 0 -> "$subjectText.first"
        arity <= 3 && idx == 1 -> "$subjectText.second"
        arity == 3 && idx == 2 -> "$subjectText.third"
        else -> "$subjectText[$idx]"
    }

private fun KotlinJ2KEmitter.qualifyShadowedPatternVariantType(
    variantType: String,
    subjectText: String,
    selfTypeName: String?,
): String {
    if ('.' in variantType || variantType !in activeTypeParamNames) return variantType
    val scrutineeBase =
        (currentTypedLocals[subjectText] ?: selfTypeName)
            ?.substringBefore('<')
            ?.substringAfterLast('.')
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it !in activeTypeParamNames }
    val ownerEnum =
        scrutineeBase?.takeIf {
            "$it.$variantType" in inspector.enumVariantFieldTypes || variantType in inspector.enumVariantSimpleNames
        }
            ?: inspector.enumVariantFieldTypes.keys
                .firstOrNull { it.endsWith(".$variantType") && '.' in it }
                ?.substringBeforeLast('.')
    return if (ownerEnum != null) "$ownerEnum.$variantType" else variantType
}

internal fun KotlinJ2KEmitter.renderMatchPatternCondition(
    pat: Pat,
    subjectText: String,
    indent: String,
    selfTypeName: String?,
    isLastArm: Boolean,
): String =
    when (pat) {
        is Pat.Wild -> "else"
        is Pat.Rest -> "else"
        is Pat.Or -> {
            val cases = pat.cases.toList().map { renderMatchPatternCondition(it, subjectText, indent, selfTypeName, isLastArm = false) }
            val nonElse = cases.filter { it.isNotEmpty() && it != "else" }
            if (nonElse.isEmpty()) "else" else nonElse.joinToString(", ")
        }
        is Pat.Range -> {
            val start = pat.start?.let { renderExpr(it, indent, selfTypeName) }
            val end = pat.end?.let { renderExpr(it, indent, selfTypeName) }
            val op =
                when (pat.limits) {
                    is RangeLimits.HalfOpen -> " until "
                    is RangeLimits.Closed -> ".."
                }
            when {
                start != null && end != null -> "in $start$op$end"
                start != null -> start
                end != null -> end
                else -> "else"
            }
        }
        is Pat.Lit -> renderLit(pat.lit)
        is Pat.Path -> {
            val segs = pat.path.segments.toList()
            if (segs.size == 1 && segs[0].ident.toString() == "None" && !inspector.hasLocalNoneVariant) {
                "null"
            } else {
                renderPathAsExpression(pat.path, selfTypeName)
            }
        }
        is Pat.Ident -> {
            val raw = pat.ident.toString()
            val cleanRaw = raw.removePrefix("r#")
            val subpat = pat.subpat
            when {
                subpat != null -> renderMatchPatternCondition(subpat, subjectText, indent, selfTypeName, isLastArm)
                raw == "_" -> "else"
                raw == "None" && !inspector.hasLocalNoneVariant -> "null"
                cleanRaw in inspector.declaredConstNames -> inspector.declaredConstNames.getValue(cleanRaw)
                isScreamingSnakeIdent(raw) -> rustConstIdentToKotlin(raw)
                raw.first().isUpperCase() -> rustTypeIdentToKotlin(raw)
                else -> "else"
            }
        }
        is Pat.TupleStruct -> {
            val elemsList = pat.elems.toList()
            val coreResultInfo = coreResultVariantMatchInfo(pat)
            if (isOptionSomeTupleStructPat(pat)) {
                val firstSub = elemsList.firstOrNull()
                if (firstSub != null) {
                    val innerCond = renderMatchPatternCondition(firstSub, subjectText, indent, selfTypeName, isLastArm = false)
                    if (innerCond != "else") {
                        innerCond
                    } else {
                        "else"
                    }
                } else {
                    "else"
                }
            } else if (coreResultInfo != null) {
                val starArgs = if (isUntypedResultScrutinee(currentTypedLocals[subjectText])) "<*, *>" else ""
                "is ${coreResultInfo.first}$starArgs"
            } else {
                "is ${qualifyShadowedPatternVariantType(renderPathAsType(pat.path, selfTypeName), subjectText, selfTypeName)}"
            }
        }
        is Pat.Struct -> {
            val variantType = qualifyShadowedPatternVariantType(renderPathAsType(pat.path, selfTypeName), subjectText, selfTypeName)
            if (isPlainStructPattern(pat, variantType)) "else" else "is $variantType"
        }
        is Pat.Tuple -> "else"
        is Pat.Slice -> "else"
        is Pat.Reference -> renderMatchPatternCondition(pat.pat, subjectText, indent, selfTypeName, isLastArm)
        is Pat.PatParen -> renderMatchPatternCondition(pat.pat, subjectText, indent, selfTypeName, isLastArm)
        is Pat.TypeAscription -> renderMatchPatternCondition(pat.pat, subjectText, indent, selfTypeName, isLastArm)
        is Pat.Guard -> renderMatchPatternCondition(pat.pat, subjectText, indent, selfTypeName, isLastArm)
        else -> "else"
    }
