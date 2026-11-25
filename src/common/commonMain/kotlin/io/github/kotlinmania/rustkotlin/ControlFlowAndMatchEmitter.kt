package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.rustkotlin.reused.starlark.AssignCount
import io.github.kotlinmania.syn.Arm
import io.github.kotlinmania.syn.Attribute
import io.github.kotlinmania.syn.Block
import io.github.kotlinmania.syn.Expr
import io.github.kotlinmania.syn.GenericArgument
import io.github.kotlinmania.syn.Item
import io.github.kotlinmania.syn.Lit
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

private inline fun <T> KotlinJ2KEmitter.withNeutralConditionContext(block: () -> T): T {
    val savedKind = currentExpectedNumericKind
    val savedTailOk = currentTailExpectsUnitResultOk
    currentExpectedNumericKind = null
    currentTailExpectsUnitResultOk = false
    return try {
        block()
    } finally {
        currentExpectedNumericKind = savedKind
        currentTailExpectsUnitResultOk = savedTailOk
    }
}

private inline fun <T> KotlinJ2KEmitter.withComprehensionTypedScope(block: () -> T): T {
    val prevTyped = currentTypedLocals
    scopeBuilder.enterCompr()
    return try {
        block()
    } finally {
        scopeBuilder.exitCompr()
        currentTypedLocals = prevTyped
    }
}

private inline fun <T> KotlinJ2KEmitter.withMatchScrutineeScope(
    scrutineeExpr: Expr,
    renderedSubject: String,
    bindFreshSubject: Boolean,
    selfTypeName: String?,
    block: (subjectVar: String) -> T,
): T =
    withComprehensionTypedScope {
        val subjectVar =
            if (bindFreshSubject) {
                scopeBuilder.bindLocal("_match", "matchVal", scrutineeExpr.span()).kotlinName
            } else {
                renderedSubject
            }
        inferExprRenderedType(scrutineeExpr, selfTypeName)?.let {
            currentTypedLocals = currentTypedLocals + (subjectVar to it)
        }
        block(subjectVar)
    }

private fun KotlinJ2KEmitter.inferScrutineeNumericKind(scrutineeExpr: Expr, selfTypeName: String?): NumericKind? {
    inferExprNumericKind(scrutineeExpr, selfTypeName)?.let { return it }
    val rendered = inferExprRenderedType(scrutineeExpr, selfTypeName) ?: return null
    return resolveNumericKind(rendered.removeSuffix("?").trim())
}

/**
 * Shared structural base class for `syn.Pat` AST inspection, condition lowering,
 * guard binding substitution, and destructuring local extraction.
 *
 * Centralizes `Option` / `Result` / `ControlFlow` variant classification, shadowed enum-variant
 * qualification, smart-cast vs explicit-cast receiver projection, `Pat.Tuple` indexed projection,
 * and `Pat.Slice` prefix / `..` rest / tail indexing so every pattern pass shares one model.
 */
internal abstract class MatchPatternTraversal(
    protected val emitter: KotlinJ2KEmitter,
    protected val selfTypeName: String? = null,
) {
    protected fun unwrapTransparent(pat: Pat): Pat = emitter.unwrapTransparentPat(pat)

    protected fun isBindingPatIdent(pat: Pat.Ident, usedBodyIdents: Set<String>? = null): Boolean {
        val raw = pat.ident.toString().removePrefix("r#")
        if (raw.startsWith("_") && usedBodyIdents != null && raw !in usedBodyIdents) return false
        return raw != "_" &&
            raw != "None" &&
            !emitter.isScreamingSnakeIdent(raw) &&
            raw !in emitter.inspector.declaredConstNames &&
            (!raw.first().isUpperCase() || pat.subpat != null)
    }

    protected fun resultOrControlFlowInfo(pat: Pat.TupleStruct): Pair<String, String>? =
        emitter.coreResultVariantMatchInfo(pat) ?: emitter.controlFlowVariantMatchInfo(pat)

    protected fun resultOrControlFlowPayloadAccess(
        subjectText: String,
        info: Pair<String, String>,
        alreadySmartCast: Boolean,
    ): String {
        val scrutineeTy = emitter.resolveScrutineeRenderedType(subjectText, selfTypeName, fallbackToSelf = false)
        val isCf = info.first.startsWith("ControlFlow.")
        val subjectAtom =
            if (subjectText.all { it.isLetterOrDigit() || it == '_' || it == '.' }) {
                subjectText
            } else {
                "($subjectText)"
            }
        val recv =
            if (alreadySmartCast || !isUntypedTaggedSumScrutinee(scrutineeTy, isCf)) {
                subjectAtom
            } else {
                "($subjectText as ${info.first}<*, *>)"
            }
        return "$recv.${info.second}"
    }

    protected fun qualifiedVariantType(path: Path, subjectText: String): String =
        emitter.qualifyShadowedPatternVariantType(
            emitter.renderPathAsType(path, selfTypeName),
            subjectText,
            selfTypeName,
        )

    protected fun canSmartCastSubject(isVariantType: String, subjectText: String, allowSmartCast: Boolean): Boolean =
        allowSmartCast &&
            isStarProjectedOrPlainCheckType(isVariantType) &&
            emitter.scopeBuilder.isSmartCastableLocalKotlinName(subjectText)

    protected fun projectedVariantReceiver(
        variantType: String,
        subjectText: String,
        bypassCast: Boolean,
    ): String =
        if (bypassCast) {
            subjectText
        } else {
            "($subjectText as ${emitter.parameterizedVariantCastType(variantType)})"
        }

    protected fun renderPathMatchCondition(
        path: Path,
        subjectText: String,
        includeSubjectReceiver: Boolean,
        asCallee: Boolean,
    ): String {
        val segs = path.segments.toList()
        if (segs.size == 1 && segs[0].ident.toString() == "None" && !emitter.inspector.hasLocalNoneVariant) {
            return if (includeSubjectReceiver) "$subjectText == null" else "null"
        }
        val patType = qualifiedVariantType(path, subjectText)
        if (emitter.lookupEnumVariantTypeArity(patType) > 0) {
            val target = emitter.starProjectedVariantIsType(patType)
            return if (includeSubjectReceiver) "$subjectText is $target" else "is $target"
        }
        val exprText = emitter.renderPathAsExpression(path, selfTypeName, asCallee = asCallee)
        return if (includeSubjectReceiver) "$subjectText == $exprText" else exprText
    }

    internal fun renderGuardWithPatternAccess(
        basePat: Pat,
        guardExpr: Expr,
        subjectText: String,
        indent: String,
        allowSmartCast: Boolean = true,
    ): String {
        val guardAccess = linkedMapOf<String, String>()
        PatternGuardAccessCollector(emitter, selfTypeName).collect(basePat, subjectText, guardAccess, allowSmartCast)
        val prevSubstitutions = emitter.activeGuardBindingSubstitutions
        emitter.activeGuardBindingSubstitutions = prevSubstitutions + guardAccess
        return try {
            emitter.renderExpr(guardExpr, indent, selfTypeName)
        } finally {
            emitter.activeGuardBindingSubstitutions = prevSubstitutions
        }
    }

    protected inline fun forEachTupleSubpattern(
        pat: Pat.Tuple,
        subjectText: String,
        action: (sub: Pat, elemAccess: String) -> Unit,
    ) {
        val elems = pat.elems.toList()
        for ((idx, sub) in elems.withIndex()) {
            if (sub is Pat.Rest) break
            action(sub, emitter.tupleIndexExpr(subjectText, idx, elems.size))
        }
    }

    protected inline fun visitSlicePattern(
        pat: Pat.Slice,
        subjectText: String,
        onFixedLength: (Int) -> Unit = {},
        onRestMinLength: (Int) -> Unit = {},
        onRestIdent: (Pat.Ident, String) -> Unit = { _, _ -> },
        onElem: (Pat, String) -> Unit,
    ) {
        val elems = pat.elems.toList()
        val restIdx = elems.indexOfFirst { it is Pat.Rest || (it is Pat.Ident && it.subpat is Pat.Rest) }
        if (restIdx < 0) {
            onFixedLength(elems.size)
            for ((idx, sub) in elems.withIndex()) {
                onElem(sub, "$subjectText[$idx]")
            }
            return
        }
        val suffix = elems.subList(restIdx + 1, elems.size)
        onRestMinLength(restIdx + suffix.size)
        for (idx in 0 until restIdx) {
            onElem(elems[idx], "$subjectText[$idx]")
        }
        val restPat = elems[restIdx]
        if (restPat is Pat.Ident) {
            val restAccess =
                when {
                    restIdx == 0 && suffix.isEmpty() -> subjectText
                    suffix.isEmpty() -> "$subjectText.subList($restIdx, $subjectText.size)"
                    else -> "$subjectText.subList($restIdx, $subjectText.size - ${suffix.size})"
                }
            onRestIdent(restPat, restAccess)
        }
        for ((offset, sub) in suffix.withIndex()) {
            val tailOffset = suffix.size - offset
            onElem(sub, "$subjectText[$subjectText.size - $tailOffset]")
        }
    }

    companion object {
        fun isStdlibOptionVariantPath(path: Path, variantName: String): Boolean {
            val segs = path.segments.toList()
            if (segs.size == 1 && segs[0].ident.toString() == variantName) return true
            if (segs.size < 2 || segs.last().ident.toString() != variantName) return false
            return segs.dropLast(1).all {
                val prefix = it.ident.toString()
                prefix in IGNORED_PATH_PREFIXES ||
                    prefix in STDLIB_PRELUDE_REEXPORT_MODULES ||
                    prefix == "Option" ||
                    prefix == "option"
            }
        }
    }
}

/**
 * Pure structural predicate inspector over `syn.Pat` nodes (`referencesSubject`,
 * `requiresBooleanCondition`, `isDiscriminatingSubpattern`, `bindsRawIdent`, and
 * `irrefutableConstructorKey`).
 */
internal class PatternPredicateInspector(
    emitter: KotlinJ2KEmitter,
    selfTypeName: String? = null,
) : MatchPatternTraversal(emitter, selfTypeName) {
    fun referencesSubject(pat: Pat): Boolean =
        when (val u = unwrapTransparent(pat)) {
            is Pat.Ident -> isBindingPatIdent(u) || (u.subpat != null && referencesSubject(u.subpat!!))
            is Pat.TupleStruct -> u.elems.toList().isNotEmpty()
            is Pat.Struct -> u.fields.toList().isNotEmpty()
            is Pat.Tuple -> u.elems.toList().isNotEmpty()
            is Pat.Slice -> true
            is Pat.Or -> u.cases.toList().any(::referencesSubject)
            is Pat.Guard -> referencesSubject(u.pat)
            else -> false
        }

    fun requiresBooleanCondition(pat: Pat): Boolean =
        when (val u = unwrapTransparent(pat)) {
            is Pat.Wild, is Pat.Rest, is Pat.Lit, is Pat.Path -> false
            is Pat.Range -> u.start == null || u.end == null
            is Pat.Ident -> u.subpat?.let(::requiresBooleanCondition) ?: false
            is Pat.Or -> u.cases.toList().any(::requiresBooleanCondition)
            is Pat.Tuple -> u.elems.toList().any(::isDiscriminatingSubpattern)
            is Pat.Slice ->
                u.elems.isEmpty() ||
                    u.elems.toList().any { it !is Pat.Rest && !(it is Pat.Ident && it.subpat is Pat.Rest) }
            is Pat.TupleStruct -> {
                val elems = u.elems.toList()
                if (emitter.isOptionSomeTupleStructPat(u)) {
                    elems.firstOrNull()?.let(::requiresBooleanCondition) ?: false
                } else {
                    elems.any(::isDiscriminatingSubpattern)
                }
            }
            is Pat.Struct -> u.fields.toList().any { isDiscriminatingSubpattern(it.pat) }
            is Pat.Guard -> true
            else -> false
        }

    fun isDiscriminatingSubpattern(pat: Pat): Boolean =
        when (val u = unwrapTransparent(pat)) {
            is Pat.Wild, is Pat.Rest -> false
            is Pat.Ident -> {
                val raw = u.ident.toString().removePrefix("r#")
                (raw != "_" && !isBindingPatIdent(u)) || (u.subpat?.let(::isDiscriminatingSubpattern) ?: false)
            }
            is Pat.Tuple -> u.elems.toList().any(::isDiscriminatingSubpattern)
            else -> true
        }

    fun isDiscriminatingOptionInnerPattern(pat: Pat): Boolean =
        when (val u = unwrapTransparent(pat)) {
            is Pat.Wild, is Pat.Rest -> false
            is Pat.Ident -> u.subpat != null && isDiscriminatingOptionInnerPattern(u.subpat!!)
            is Pat.Tuple -> u.elems.toList().any(::isDiscriminatingOptionInnerPattern)
            is Pat.Path -> {
                val segs = u.path.segments.toList()
                val last = segs.lastOrNull()?.ident?.toString()
                last != null && rustTypeIdentToKotlin(last) !in emitter.inspector.unitStructNames
            }
            else -> true
        }

    fun bindsRawIdent(pat: Pat, rawIdent: String): Boolean =
        when (val u = unwrapTransparent(pat)) {
            is Pat.Ident ->
                u.ident.toString().removePrefix("r#") == rawIdent ||
                    (u.subpat?.let { bindsRawIdent(it, rawIdent) } == true)
            is Pat.Tuple -> u.elems.toList().any { bindsRawIdent(it, rawIdent) }
            is Pat.TupleStruct -> u.elems.toList().any { bindsRawIdent(it, rawIdent) }
            is Pat.Slice -> u.elems.toList().any { bindsRawIdent(it, rawIdent) }
            is Pat.Or -> u.cases.toList().any { bindsRawIdent(it, rawIdent) }
            is Pat.Struct -> u.fields.toList().any { bindsRawIdent(it.pat, rawIdent) }
            else -> false
        }

    fun irrefutableConstructorKey(pat: Pat): String? =
        when (val u = unwrapTransparent(pat)) {
            is Pat.TupleStruct ->
                if (u.elems.toList().any(::isDiscriminatingSubpattern)) null else emitter.joinPathIdents(u.path)
            is Pat.Struct ->
                if (u.fields.toList().any { isDiscriminatingSubpattern(it.pat) }) null else emitter.joinPathIdents(u.path)
            is Pat.Path -> emitter.joinPathIdents(u.path)
            is Pat.Wild -> "_"
            is Pat.Ident -> if (u.subpat == null && u.ident.toString() == "_") "_" else null
            else -> null
        }
}

/**
 * Lowers `syn.Pat` into subject-scoped `when (subject)` arm headers and `if let` / `while let`
 * header expressions against an evaluated RHS.
 */
internal class SubjectMatchConditionEmitter(
    emitter: KotlinJ2KEmitter,
    selfTypeName: String?,
) : MatchPatternTraversal(emitter, selfTypeName) {
    private val predicates = PatternPredicateInspector(emitter, selfTypeName)

    fun renderSubjectArm(pat: Pat, subjectText: String, indent: String, isLastArm: Boolean): String =
        when (pat) {
            is Pat.Wild, is Pat.Rest, is Pat.Tuple, is Pat.Slice -> "else"
            is Pat.Or -> {
                val cases = pat.cases.toList().map { renderSubjectArm(it, subjectText, indent, isLastArm = false) }
                val nonElse = cases.filter { it.isNotEmpty() && it != "else" }
                if (nonElse.isEmpty()) "else" else nonElse.joinToString(", ")
            }
            is Pat.Range -> {
                val start = pat.start?.let { emitter.renderMatchRangeBound(it, indent, selfTypeName) }
                val end = pat.end?.let { emitter.renderMatchRangeBound(it, indent, selfTypeName) }
                val op = if (pat.limits is RangeLimits.HalfOpen) " until " else ".."
                when {
                    start != null && end != null -> "in $start$op$end"
                    start != null -> start
                    end != null -> end
                    else -> "else"
                }
            }
            is Pat.Lit -> emitter.renderMatchLit(pat.lit)
            is Pat.Path -> renderPathMatchCondition(pat.path, subjectText, includeSubjectReceiver = false, asCallee = true)
            is Pat.Ident -> renderSubjectArmIdent(pat, subjectText, indent, isLastArm)
            is Pat.TupleStruct -> {
                val coreResultInfo = resultOrControlFlowInfo(pat)
                when {
                    emitter.isOptionSomeTupleStructPat(pat) -> {
                        val firstSub = pat.elems.toList().firstOrNull()
                        if (firstSub != null) renderSubjectArm(firstSub, subjectText, indent, isLastArm = false) else "else"
                    }
                    coreResultInfo != null -> "is ${coreResultInfo.first}<*, *>"
                    else -> "is ${emitter.starProjectedVariantIsType(qualifiedVariantType(pat.path, subjectText))}"
                }
            }
            is Pat.Struct -> {
                val variantType = qualifiedVariantType(pat.path, subjectText)
                if (emitter.isPlainStructPattern(pat, variantType)) "else" else "is ${emitter.starProjectedVariantIsType(variantType)}"
            }
            is Pat.Reference -> renderSubjectArm(pat.pat, subjectText, indent, isLastArm)
            is Pat.PatParen -> renderSubjectArm(pat.pat, subjectText, indent, isLastArm)
            is Pat.TypeAscription -> renderSubjectArm(pat.pat, subjectText, indent, isLastArm)
            is Pat.Guard -> renderSubjectArm(pat.pat, subjectText, indent, isLastArm)
            else -> "else"
        }

    private fun renderSubjectArmIdent(pat: Pat.Ident, subjectText: String, indent: String, isLastArm: Boolean): String {
        val raw = pat.ident.toString()
        val cleanRaw = raw.removePrefix("r#")
        val subpat = pat.subpat
        return when {
            subpat != null -> renderSubjectArm(subpat, subjectText, indent, isLastArm)
            raw == "_" -> "else"
            raw == "None" && !emitter.inspector.hasLocalNoneVariant -> "null"
            cleanRaw in emitter.inspector.declaredConstNames -> emitter.inspector.declaredConstNames.getValue(cleanRaw)
            emitter.isScreamingSnakeIdent(raw) -> rustConstIdentToKotlin(raw)
            raw.first().isUpperCase() -> {
                val ktVar = emitter.qualifyShadowedPatternVariantType(rustTypeIdentToKotlin(raw), subjectText, selfTypeName)
                if (emitter.isGenericUnitEnumVariant(ktVar)) "is ${emitter.starProjectedVariantIsType(ktVar)}" else ktVar
            }
            else -> "else"
        }
    }

    fun renderIfLetAgainstRhs(
        pat: Pat,
        rhs: String,
        indent: String,
        subjectExpr: Expr? = null,
    ): String =
        when (pat) {
            is Pat.TupleStruct -> {
                val coreResultInfo = resultOrControlFlowInfo(pat)
                when {
                    emitter.isOptionSomeTupleStructPat(pat) -> {
                        val firstSub = pat.elems.toList().firstOrNull()
                        if (firstSub != null && predicates.isDiscriminatingOptionInnerPattern(firstSub)) {
                            renderIfLetAgainstRhs(firstSub, rhs, indent, subjectExpr)
                        } else {
                            "$rhs != null"
                        }
                    }
                    predicates.requiresBooleanCondition(pat) ->
                        PatternBooleanConditionEmitter(emitter, selfTypeName).render(pat, rhs, indent)
                    coreResultInfo != null -> "$rhs is ${coreResultInfo.first}<*, *>"
                    else -> "$rhs is ${emitter.starProjectedVariantIsType(qualifiedVariantType(pat.path, rhs))}"
                }
            }
            is Pat.Struct -> {
                val variantType = qualifiedVariantType(pat.path, rhs)
                when {
                    predicates.requiresBooleanCondition(pat) ->
                        PatternBooleanConditionEmitter(emitter, selfTypeName).render(pat, rhs, indent)
                    emitter.isPlainStructPattern(pat, variantType) -> "true"
                    else -> "$rhs is ${emitter.starProjectedVariantIsType(variantType)}"
                }
            }
            is Pat.Path -> renderPathMatchCondition(pat.path, rhs, includeSubjectReceiver = true, asCallee = true)
            is Pat.Lit -> "$rhs == ${emitter.renderMatchLit(pat.lit)}"
            is Pat.Range -> PatternBooleanConditionEmitter(emitter, selfTypeName).render(pat, rhs, indent)
            is Pat.Or -> pat.cases.toList().joinToString(" || ") { renderIfLetAgainstRhs(it, rhs, indent) }
            is Pat.Ident -> {
                val raw = pat.ident.toString()
                val cleanRaw = raw.removePrefix("r#")
                val subpat = pat.subpat
                when {
                    subpat != null -> renderIfLetAgainstRhs(subpat, rhs, indent, subjectExpr)
                    raw == "None" && !emitter.inspector.hasLocalNoneVariant -> "$rhs == null"
                    cleanRaw.firstOrNull()?.isUpperCase() == true &&
                        !emitter.isScreamingSnakeIdent(cleanRaw) &&
                        cleanRaw !in emitter.inspector.declaredConstNames -> {
                        val ktVar = emitter.qualifyShadowedPatternVariantType(rustTypeIdentToKotlin(cleanRaw), rhs, selfTypeName)
                        if (emitter.isGenericUnitEnumVariant(ktVar)) {
                            "$rhs is ${emitter.starProjectedVariantIsType(ktVar)}"
                        } else {
                            "$rhs != null"
                        }
                    }
                    else -> "$rhs != null"
                }
            }
            is Pat.Reference -> renderIfLetAgainstRhs(pat.pat, rhs, indent)
            is Pat.PatParen -> renderIfLetAgainstRhs(pat.pat, rhs, indent)
            else -> "$rhs != null"
        }
}

/**
 * Lowers `syn.Pat` trees into boolean Kotlin predicate expressions for subjectless `when { ... }`
 * match arms and nested discriminating `if let` / `while let` conditions.
 */
internal class PatternBooleanConditionEmitter(
    emitter: KotlinJ2KEmitter,
    selfTypeName: String?,
) : MatchPatternTraversal(emitter, selfTypeName) {
    fun render(
        pat: Pat,
        subjectExpr: String,
        indent: String,
        allowSmartCast: Boolean = true,
    ): String =
        when (val u = unwrapTransparent(pat)) {
            is Pat.Wild, is Pat.Rest -> "true"
            is Pat.Lit -> "$subjectExpr == ${emitter.renderMatchLit(u.lit)}"
            is Pat.Range -> renderRangeCondition(u, subjectExpr, indent)
            is Pat.Path -> renderPathMatchCondition(u.path, subjectExpr, includeSubjectReceiver = true, asCallee = false)
            is Pat.Ident -> renderIdentCondition(u, subjectExpr, indent, allowSmartCast)
            is Pat.TupleStruct -> renderTupleStructCondition(u, subjectExpr, indent, allowSmartCast)
            is Pat.Struct -> renderStructCondition(u, subjectExpr, indent, allowSmartCast)
            is Pat.Tuple -> {
                val parts = mutableListOf<String>()
                forEachTupleSubpattern(u, subjectExpr) { sub, elemAccess ->
                    parts += render(sub, elemAccess, indent, allowSmartCast)
                }
                emitter.combineAndConditions(parts)
            }
            is Pat.Slice -> {
                val parts = mutableListOf<String>()
                visitSlicePattern(
                    pat = u,
                    subjectText = subjectExpr,
                    onFixedLength = { len ->
                        parts += if (len == 0) "$subjectExpr.isEmpty()" else "$subjectExpr.size == $len"
                    },
                    onRestMinLength = { minLen ->
                        if (minLen > 0) parts += "$subjectExpr.size >= $minLen"
                    },
                    onElem = { sub, access ->
                        parts += render(sub, access, indent, allowSmartCast)
                    },
                )
                emitter.combineAndConditions(parts)
            }
            is Pat.Or -> {
                val cases = u.cases.toList().map { render(it, subjectExpr, indent, allowSmartCast = false) }
                if (cases.any { it == "true" }) {
                    "true"
                } else {
                    cases.joinToString(" || ") { if (it.contains(" && ")) "($it)" else it }
                }
            }
            is Pat.Guard -> {
                val baseCond = render(u.pat, subjectExpr, indent, allowSmartCast)
                val guardCond = renderGuardWithPatternAccess(u.pat, u.guard, subjectExpr, indent, allowSmartCast)
                emitter.combineAndConditions(listOf(baseCond, guardCond))
            }
            else -> "true"
        }

    private fun renderRangeCondition(pat: Pat.Range, subjectExpr: String, indent: String): String {
        val start = pat.start?.let { emitter.renderMatchRangeBound(it, indent, selfTypeName) }
        val end = pat.end?.let { emitter.renderMatchRangeBound(it, indent, selfTypeName) }
        val isHalfOpen = pat.limits is RangeLimits.HalfOpen
        return when {
            start != null && end != null ->
                if (isHalfOpen) "$subjectExpr in $start until $end" else "$subjectExpr in $start..$end"
            start != null -> "$subjectExpr >= $start"
            end != null -> if (isHalfOpen) "$subjectExpr < $end" else "$subjectExpr <= $end"
            else -> "true"
        }
    }

    private fun renderIdentCondition(
        pat: Pat.Ident,
        subjectExpr: String,
        indent: String,
        allowSmartCast: Boolean,
    ): String {
        val raw = pat.ident.toString().removePrefix("r#")
        val selfCond =
            when {
                raw == "_" -> "true"
                raw == "None" && !emitter.inspector.hasLocalNoneVariant -> "$subjectExpr == null"
                raw in emitter.inspector.declaredConstNames -> "$subjectExpr == ${emitter.inspector.declaredConstNames.getValue(raw)}"
                emitter.isScreamingSnakeIdent(raw) -> "$subjectExpr == ${rustConstIdentToKotlin(raw)}"
                raw.first().isUpperCase() && pat.subpat == null -> {
                    val ktType = emitter.qualifyShadowedPatternVariantType(rustTypeIdentToKotlin(raw), subjectExpr, selfTypeName)
                    when {
                        ktType in emitter.inspector.unitStructNames -> "$subjectExpr is $ktType"
                        emitter.isGenericUnitEnumVariant(ktType) -> "$subjectExpr is ${emitter.starProjectedVariantIsType(ktType)}"
                        else -> "$subjectExpr == $ktType"
                    }
                }
                else -> "true"
            }
        val subCond = pat.subpat?.let { render(it, subjectExpr, indent, allowSmartCast) } ?: "true"
        return emitter.combineAndConditions(listOf(selfCond, subCond))
    }

    private fun renderTupleStructCondition(
        pat: Pat.TupleStruct,
        subjectExpr: String,
        indent: String,
        allowSmartCast: Boolean,
    ): String {
        val elems = pat.elems.toList()
        if (emitter.isOptionSomeTupleStructPat(pat)) {
            val innerCond = elems.firstOrNull()?.let { render(it, subjectExpr, indent, allowSmartCast) } ?: "true"
            return emitter.combineAndConditions(listOf("$subjectExpr != null", innerCond))
        }
        val coreResultInfo = resultOrControlFlowInfo(pat)
        if (coreResultInfo != null) {
            val castType = "${coreResultInfo.first}<*, *>"
            val elemRecv =
                if (allowSmartCast && emitter.scopeBuilder.isSmartCastableLocalKotlinName(subjectExpr)) {
                    subjectExpr
                } else {
                    "($subjectExpr as $castType)"
                }
            val parts = mutableListOf("$subjectExpr is $castType")
            elems.firstOrNull()?.let { sub ->
                if (sub !is Pat.Rest) {
                    parts += render(sub, "$elemRecv.${coreResultInfo.second}", indent, allowSmartCast)
                }
            }
            return emitter.combineAndConditions(parts)
        }
        val variantType = qualifiedVariantType(pat.path, subjectExpr)
        val isVariantType = emitter.starProjectedVariantIsType(variantType)
        val elemRecv =
            projectedVariantReceiver(
                variantType = variantType,
                subjectText = subjectExpr,
                bypassCast = canSmartCastSubject(isVariantType, subjectExpr, allowSmartCast),
            )
        val parts = mutableListOf("$subjectExpr is $isVariantType")
        for ((idx, sub) in elems.withIndex()) {
            if (sub is Pat.Rest) break
            val propName = tupleFieldPropertyName(idx, elems.size)
            parts += render(sub, "$elemRecv.$propName", indent, allowSmartCast)
        }
        return emitter.combineAndConditions(parts)
    }

    private fun renderStructCondition(
        pat: Pat.Struct,
        subjectExpr: String,
        indent: String,
        allowSmartCast: Boolean,
    ): String {
        val variantType = qualifiedVariantType(pat.path, subjectExpr)
        val isPlainStruct = emitter.isPlainStructPattern(pat, variantType)
        val isVariantType = if (isPlainStruct) variantType else emitter.starProjectedVariantIsType(variantType)
        val parts = if (isPlainStruct) mutableListOf() else mutableListOf("$subjectExpr is $isVariantType")
        val structRecv =
            projectedVariantReceiver(
                variantType = variantType,
                subjectText = subjectExpr,
                bypassCast = isPlainStruct || canSmartCastSubject(isVariantType, subjectExpr, allowSmartCast),
            )
        for (fp in pat.fields.toList()) {
            val fieldName = emitter.renderMemberName(fp.member)
            parts += render(fp.pat, "$structRecv.$fieldName", indent, allowSmartCast)
        }
        return emitter.combineAndConditions(parts)
    }
}

/**
 * Collects raw pattern-binding identifiers mapped to inline subject field/element projection
 * expressions so `if <guard>` expressions inside Kotlin `when` headers can reference bound values
 * before arm body destructuring executes.
 */
internal class PatternGuardAccessCollector(
    emitter: KotlinJ2KEmitter,
    selfTypeName: String?,
) : MatchPatternTraversal(emitter, selfTypeName) {
    fun collect(
        pat: Pat,
        subjectText: String,
        out: MutableMap<String, String>,
        allowSmartCast: Boolean = true,
    ) {
        when (val u = unwrapTransparent(pat)) {
            is Pat.Ident -> {
                if (isBindingPatIdent(u)) {
                    out[u.ident.toString().removePrefix("r#")] = subjectText
                }
                u.subpat?.let { collect(it, subjectText, out, allowSmartCast) }
            }
            is Pat.TupleStruct -> collectTupleStruct(u, subjectText, out, allowSmartCast)
            is Pat.Struct -> {
                val variantType = qualifiedVariantType(u.path, subjectText)
                val isPlainStruct = emitter.isPlainStructPattern(u, variantType)
                val isVariantType = if (isPlainStruct) variantType else emitter.starProjectedVariantIsType(variantType)
                val structRecv =
                    projectedVariantReceiver(
                        variantType = variantType,
                        subjectText = subjectText,
                        bypassCast = isPlainStruct || canSmartCastSubject(isVariantType, subjectText, allowSmartCast),
                    )
                for (fp in u.fields.toList()) {
                    val fieldName = emitter.renderMemberName(fp.member)
                    collect(fp.pat, "$structRecv.$fieldName", out, allowSmartCast)
                }
            }
            is Pat.Tuple ->
                forEachTupleSubpattern(u, subjectText) { sub, elemAccess ->
                    collect(sub, elemAccess, out, allowSmartCast)
                }
            is Pat.Slice ->
                visitSlicePattern(
                    pat = u,
                    subjectText = subjectText,
                    onRestIdent = { restIdent, access -> collect(restIdent, access, out, allowSmartCast) },
                    onElem = { sub, access -> collect(sub, access, out, allowSmartCast) },
                )
            is Pat.Or -> {
                u.cases.toList().firstOrNull()?.let { firstCase ->
                    collect(firstCase, subjectText, out, allowSmartCast = false)
                }
            }
            is Pat.Guard -> collect(u.pat, subjectText, out, allowSmartCast)
            else -> {}
        }
    }

    private fun collectTupleStruct(
        pat: Pat.TupleStruct,
        subjectText: String,
        out: MutableMap<String, String>,
        allowSmartCast: Boolean,
    ) {
        val elems = pat.elems.toList()
        if (emitter.isOptionSomeTupleStructPat(pat) && elems.size == 1) {
            collect(elems[0], subjectText, out, allowSmartCast)
            return
        }
        val coreResultInfo = resultOrControlFlowInfo(pat)
        if (coreResultInfo != null && elems.size == 1 && elems[0] !is Pat.Rest) {
            val isSmartCastSubject = allowSmartCast && emitter.scopeBuilder.isSmartCastableLocalKotlinName(subjectText)
            val access = resultOrControlFlowPayloadAccess(subjectText, coreResultInfo, isSmartCastSubject)
            collect(elems[0], access, out, allowSmartCast)
            return
        }
        val variantType = qualifiedVariantType(pat.path, subjectText)
        val isVariantType = emitter.starProjectedVariantIsType(variantType)
        val recv =
            projectedVariantReceiver(
                variantType = variantType,
                subjectText = subjectText,
                bypassCast = canSmartCastSubject(isVariantType, subjectText, allowSmartCast),
            )
        for ((idx, sub) in elems.withIndex()) {
            if (sub is Pat.Rest) break
            val propName = tupleFieldPropertyName(idx, elems.size)
            collect(sub, "$recv.$propName", out, allowSmartCast)
        }
    }
}

/**
 * Emits local `val` / `var` binding declarations (and optional `&mut` enum-variant writeback
 * assignments) for `match`, `if let`, and `while let` arm bodies.
 */
internal class MatchArmBindingExtractor(
    emitter: KotlinJ2KEmitter,
    selfTypeName: String?,
    private val bindToScope: Boolean,
    private val usedBodyIdents: Set<String>?,
) : MatchPatternTraversal(emitter, selfTypeName) {
    fun extract(
        pat: Pat,
        subjectText: String,
        indent: String,
        subjectAlreadySmartCast: Boolean = false,
        mutRefWritebackTarget: String? = null,
        writebackLinesOut: MutableList<String>? = null,
    ): List<String> =
        when (val u = unwrapTransparent(pat)) {
            is Pat.Ident -> extractIdent(u, subjectText, indent, subjectAlreadySmartCast)
            is Pat.TupleStruct ->
                extractTupleStruct(
                    pat = u,
                    subjectText = subjectText,
                    indent = indent,
                    subjectAlreadySmartCast = subjectAlreadySmartCast,
                    mutRefWritebackTarget = mutRefWritebackTarget,
                    writebackLinesOut = writebackLinesOut,
                )
            is Pat.Struct -> {
                val variantType = qualifiedVariantType(u.path, subjectText)
                val isPlainStruct = emitter.isPlainStructPattern(u, variantType)
                val structRecv = projectedVariantReceiver(variantType, subjectText, bypassCast = subjectAlreadySmartCast || isPlainStruct)
                val lines = mutableListOf<String>()
                for (fp in u.fields.toList()) {
                    val fieldName = emitter.renderMemberName(fp.member)
                    lines += extract(fp.pat, "$structRecv.$fieldName", indent, subjectAlreadySmartCast = false)
                }
                lines
            }
            is Pat.Tuple -> {
                val lines = mutableListOf<String>()
                forEachTupleSubpattern(u, subjectText) { sub, elemAccess ->
                    lines += extract(sub, elemAccess, indent, subjectAlreadySmartCast = false)
                }
                lines
            }
            is Pat.Slice -> {
                val lines = mutableListOf<String>()
                visitSlicePattern(
                    pat = u,
                    subjectText = subjectText,
                    onRestIdent = { restIdent, access ->
                        lines += extract(restIdent, access, indent, subjectAlreadySmartCast = false)
                    },
                    onElem = { sub, access ->
                        lines += extract(sub, access, indent, subjectAlreadySmartCast = false)
                    },
                )
                lines
            }
            is Pat.Or -> {
                val firstCase = u.cases.toList().firstOrNull()
                if (firstCase != null) extract(firstCase, subjectText, indent, subjectAlreadySmartCast) else emptyList()
            }
            is Pat.Guard -> extract(u.pat, subjectText, indent, subjectAlreadySmartCast)
            else -> emptyList()
        }

    private fun extractIdent(
        pat: Pat.Ident,
        subjectText: String,
        indent: String,
        subjectAlreadySmartCast: Boolean,
    ): List<String> {
        val raw = pat.ident.toString().removePrefix("r#")
        val selfLine =
            if (isBindingPatIdent(pat, usedBodyIdents)) {
                val assignCount = if (pat.mutability != null) AssignCount.Any else AssignCount.AtMostOnce
                val ktName =
                    if (bindToScope) {
                        emitter.scopeBuilder.bindLocal(raw, rustValueIdentToKotlin(raw), pat.ident.span(), assignCount = assignCount).kotlinName
                    } else {
                        rustValueIdentToKotlin(raw)
                    }
                if (bindToScope) {
                    emitter.currentTypedLocals[subjectText]?.let {
                        emitter.currentTypedLocals = emitter.currentTypedLocals + (ktName to it.removeSuffix("?"))
                    }
                }
                if (ktName == subjectText) emptyList() else listOf("${indent}val $ktName = $subjectText")
            } else {
                emptyList()
            }
        val subLines = pat.subpat?.let { extract(it, subjectText, indent, subjectAlreadySmartCast) }.orEmpty()
        return selfLine + subLines
    }

    private fun extractTupleStruct(
        pat: Pat.TupleStruct,
        subjectText: String,
        indent: String,
        subjectAlreadySmartCast: Boolean,
        mutRefWritebackTarget: String?,
        writebackLinesOut: MutableList<String>?,
    ): List<String> {
        val elems = pat.elems.toList()
        if (emitter.isOptionSomeTupleStructPat(pat) && elems.size == 1) {
            return extract(elems[0], subjectText, indent, subjectAlreadySmartCast = false)
        }
        val coreResultInfo = resultOrControlFlowInfo(pat)
        if (coreResultInfo != null && elems.size == 1) {
            return extractResultOrControlFlowPayload(elems[0], subjectText, indent, subjectAlreadySmartCast, coreResultInfo)
        }
        val variantType = qualifiedVariantType(pat.path, subjectText)
        val castVariantType = emitter.parameterizedVariantCastType(variantType)
        val variantFieldTys =
            emitter.inspector.enumVariantFieldTypes[variantType]
                ?: emitter.inspector.enumVariantFieldTypes[variantType.substringAfterLast('.')]
        val mutableVariantWriteback = mutRefWritebackTarget != null && writebackLinesOut != null
        val names =
            elems.mapIndexed { idx, sub ->
                val rawSubIdent = emitter.extractRawParamIdent(sub)
                val isUnusedSub =
                    rawSubIdent != null &&
                        rawSubIdent.startsWith("_") &&
                        usedBodyIdents != null &&
                        rawSubIdent !in usedBodyIdents
                if (!isUnusedSub && (sub is Pat.Ident || sub is Pat.Reference || sub is Pat.TypeAscription)) {
                    val subNames =
                        if (mutableVariantWriteback && bindToScope && rawSubIdent != null && rawSubIdent != "_" && !rawSubIdent.first().isUpperCase()) {
                            listOf(
                                emitter.scopeBuilder
                                    .bindLocal(
                                        rawSubIdent,
                                        rustValueIdentToKotlin(rawSubIdent),
                                        sub.span(),
                                        assignCount = AssignCount.Any,
                                    ).kotlinName,
                            )
                        } else {
                            emitter.extractPatternBindingNames(sub, selfTypeName, includeCtorInTupleStruct = false, bindToScope = bindToScope)
                        }
                    if (subNames.size == 1) {
                        val boundName = subNames[0]
                        variantFieldTys?.getOrNull(idx)?.let { fieldSynTy ->
                            emitter.currentTypedLocals = emitter.currentTypedLocals + (boundName to emitter.renderType(fieldSynTy, selfTypeName))
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
        val nestedSubjectExpr = projectedVariantReceiver(variantType, subjectText, bypassCast = subjectAlreadySmartCast)
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
                    if (bound != "_") bound else "$nestedSubjectExpr.${tupleFieldPropertyName(idx, elems.size)}"
                }
            writebackLinesOut += "${indent}$mutRefWritebackTarget = $variantType(${writebackArgs.joinToString(", ")})"
        }
        val nestedLines =
            elems.flatMapIndexed { idx, sub ->
                if (sub is Pat.TupleStruct || sub is Pat.Struct || sub is Pat.Tuple || sub is Pat.Slice) {
                    val propName = tupleFieldPropertyName(idx, elems.size)
                    extract(sub, "$nestedSubjectExpr.$propName", indent, subjectAlreadySmartCast = false)
                } else {
                    emptyList()
                }
            }
        return directDestructure + nestedLines
    }

    private fun extractResultOrControlFlowPayload(
        sub: Pat,
        subjectText: String,
        indent: String,
        subjectAlreadySmartCast: Boolean,
        coreResultInfo: Pair<String, String>,
    ): List<String> {
        val rawSubIdent = emitter.extractRawParamIdent(sub)
        val isUnusedSub =
            rawSubIdent != null &&
                rawSubIdent.startsWith("_") &&
                usedBodyIdents != null &&
                rawSubIdent !in usedBodyIdents
        val lines = mutableListOf<String>()
        val accessTarget = resultOrControlFlowPayloadAccess(subjectText, coreResultInfo, subjectAlreadySmartCast)
        if (!isUnusedSub && rawSubIdent != null && rawSubIdent != "_" && (sub is Pat.Ident || sub is Pat.Reference || sub is Pat.TypeAscription)) {
            val subNames = emitter.extractPatternBindingNames(sub, selfTypeName, includeCtorInTupleStruct = false, bindToScope = bindToScope)
            val boundName = subNames.singleOrNull()
            if (boundName != null && boundName != "_") {
                lines += "${indent}val $boundName = $accessTarget"
            }
        } else if (!isUnusedSub && rawSubIdent != "_" && sub !is Pat.Wild && sub !is Pat.Rest) {
            val cleanProp = coreResultInfo.second.removeSuffix("()")
            val innerHolder =
                if (bindToScope) {
                    emitter.scopeBuilder
                        .bindLocal(
                            rawName = "__res_$cleanProp",
                            baseKotlinName = "${cleanProp}Val",
                            span = sub.span(),
                            assignCount = AssignCount.AtMostOnce,
                        ).kotlinName
                } else {
                    "${cleanProp}Val"
                }
            lines += "${indent}val $innerHolder = $accessTarget"
            lines += extract(sub, innerHolder, indent, subjectAlreadySmartCast = false)
        }
        return lines
    }
}

private class DerefedIdentMutationVisitor(
    private val emitter: KotlinJ2KEmitter,
    private val rawIdent: String,
) : Visit() {
    private val predicates = PatternPredicateInspector(emitter)
    private var mutates = false
    private var shadowed = false

    fun check(block: Block): Boolean {
        visitBlock(block)
        return mutates && !shadowed
    }

    override fun visitItem(i: Item) {}

    override fun visitExprClosure(e: Expr.Closure) {}

    override fun visitStmt(s: Stmt) {
        if (isLeadingCfgStmtDisabled(s)) return
        super.visitStmt(s)
        if (s is Stmt.Local && predicates.bindsRawIdent(s.pat, rawIdent)) {
            shadowed = true
        }
    }

    override fun visitExprForLoop(e: Expr.ForLoop) {
        visitExpr(e.expr)
        if (!predicates.bindsRawIdent(e.pat, rawIdent)) {
            visitBlock(e.body)
        }
    }

    override fun visitExprAssign(e: Expr.Assign) {
        checkMutationTarget(e.left)
        super.visitExprAssign(e)
    }

    override fun visitExprBinary(e: Expr.Binary) {
        if (emitter.isCompoundAssignOp(e.op)) {
            checkMutationTarget(e.left)
        }
        super.visitExprBinary(e)
    }

    private fun checkMutationTarget(target: Expr) {
        if (shadowed) return
        val lhs = emitter.unwrapParenOrGroupExpr(target)
        if (lhs is Expr.Unary && lhs.op is UnOp.Deref && emitter.isDerefedSingleIdent(lhs.expr, rawIdent)) {
            mutates = true
        }
    }
}

internal fun KotlinJ2KEmitter.renderExprIf(expr: Expr.If, indent: String, selfTypeName: String?): String {
    val cond = expr.cond
    if (cond is Expr.Let) {
        if ((!isSideEffectFreeExpr(cond.expr) || unwrapTransparentCastExpr(cond.expr) is Expr.Field) && armReferencesSubject(cond.pat)) {
            tryRenderSingleEvalIfLet(expr, cond, indent, selfTypeName)?.let { return it }
        }
        val (patHeader, rhsText) =
            withNeutralConditionContext {
                renderIfLetCondition(cond.pat, cond.expr, indent, selfTypeName) to
                    renderExpr(cond.expr, "$indent    ", selfTypeName)
            }
        val isSimpleSmartCast =
            !patHeader.contains(" || ") &&
                patHeader.startsWith("$rhsText is ") &&
                isStarProjectedOrPlainCheckType(patHeader.removePrefix("$rhsText is ").substringBefore(" && ").trim()) &&
                ((!rhsText.contains('.') && !patHeader.contains(" && ")) || scopeBuilder.isSmartCastableLocalKotlinName(rhsText))
        val usedInThen = collectUsedRawIdentsInBlock(expr.thenBranch)
        val thenBlock =
            withComprehensionTypedScope {
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
            }
        val elseSuffix = renderElseBranchSuffix(expr, indent, selfTypeName)
        return "if ($patHeader) $thenBlock$elseSuffix"
    }
    val condText = withNeutralConditionContext { renderExpr(cond, indent, selfTypeName) }
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
    val firstTupleSub = (unwrappedPat as? Pat.TupleStruct)?.elems?.toList()?.firstOrNull()
    val isPlainSome =
        unwrappedPat is Pat.TupleStruct &&
            isOptionSomeTupleStructPat(unwrappedPat) &&
            firstTupleSub?.let { !isDiscriminatingOptionInnerPattern(it) } != false
    val coreResultInfo =
        if (unwrappedPat is Pat.TupleStruct) {
            coreResultVariantMatchInfo(unwrappedPat) ?: controlFlowVariantMatchInfo(unwrappedPat)
        } else {
            null
        }
    val requiresBoolCond = !isPlainSome && patRequiresBooleanCondition(unwrappedPat)
    val isSupportedPat =
        isPlainSome ||
            coreResultInfo != null ||
            unwrappedPat is Pat.TupleStruct ||
            unwrappedPat is Pat.Struct
    if (!isSupportedPat) return null

    val savedTailOk = currentTailExpectsUnitResultOk
    val rhsText = withNeutralConditionContext { renderExpr(cond.expr, indent, selfTypeName) }
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
    return withMatchScrutineeScope(cond.expr, rhsText, bindFreshSubject = true, selfTypeName = selfTypeName) { subjectVar ->
        val typeCheckTarget =
            when {
                isPlainSome -> null
                coreResultInfo != null -> "${coreResultInfo.first}<*, *>"
                unwrappedPat is Pat.TupleStruct ->
                    starProjectedVariantIsType(qualifyShadowedPatternVariantType(renderPathAsType(unwrappedPat.path, selfTypeName), subjectVar, selfTypeName))
                unwrappedPat is Pat.Struct -> {
                    val vt = qualifyShadowedPatternVariantType(renderPathAsType(unwrappedPat.path, selfTypeName), subjectVar, selfTypeName)
                    if (isPlainStructPattern(unwrappedPat, vt)) null else starProjectedVariantIsType(vt)
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
                isStarProjectedOrPlainCheckType(typeCheckTarget) &&
                (boolCond == null || !boolCond.contains(" || "))
        val thenBlock =
            withComprehensionTypedScope {
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
            val bodyBlock =
                withMatchScrutineeScope(cond.expr, rhsText, bindFreshSubject = true, selfTypeName = selfTypeName) { subjectVar ->
                    val condCheck = renderIfLetPatternAgainstRhs(cond.pat, subjectVar, innerIndent, selfTypeName, cond.expr)
                    val breakLine =
                        when {
                            condCheck == "$subjectVar != null" -> "${innerIndent}if ($subjectVar == null) break"
                            condCheck.startsWith("$subjectVar is ") && !condCheck.contains(" && ") && !condCheck.contains(" || ") ->
                                "${innerIndent}if ($subjectVar !is ${condCheck.removePrefix("$subjectVar is ")}) break"
                            else -> "${innerIndent}if (!($condCheck)) break"
                        }
                    val isWhileSmartCast =
                        condCheck.startsWith("$subjectVar is ") &&
                            !condCheck.contains(" && ") &&
                            !condCheck.contains(" || ") &&
                            isStarProjectedOrPlainCheckType(condCheck.removePrefix("$subjectVar is ").trim())
                    val bindingLines =
                        listOf("${innerIndent}val $subjectVar = $rhsText", breakLine) +
                            extractMatchArmBindings(
                                cond.pat,
                                subjectVar,
                                innerIndent,
                                selfTypeName,
                                bindToScope = true,
                                subjectAlreadySmartCast = isWhileSmartCast,
                            )
                    recordOptionLetPatternLocalTypes(cond.pat, cond.expr, selfTypeName)
                    renderBlock(
                        block = expr.body,
                        indent = indent,
                        selfTypeName = selfTypeName,
                        leadingInjectedLines = bindingLines,
                    )
                }
            return "while (true) $bodyBlock"
        }
        val patHeader = renderIfLetCondition(cond.pat, cond.expr, indent, selfTypeName)
        val rhsText = renderExpr(cond.expr, "$indent    ", selfTypeName)
        val bodyBlock =
            withComprehensionTypedScope {
                val bindingLines = extractMatchArmBindings(cond.pat, rhsText, "$indent    ", selfTypeName, bindToScope = true)
                recordOptionLetPatternLocalTypes(cond.pat, cond.expr, selfTypeName)
                renderBlock(
                    block = expr.body,
                    indent = indent,
                    selfTypeName = selfTypeName,
                    leadingInjectedLines = bindingLines,
                )
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
    val calleeSegs = calleePath.segments.toList()
    val lastIdent = calleeSegs.lastOrNull()?.ident?.toString() ?: return
    val ktFn = rustFunctionIdentToKotlin(lastIdent)
    val retSyn = inspector.topLevelFnReturnSynTypes[ktFn]?.let(::unwrapSynType) ?: return
    val retPath = (retSyn as? SynType.Path)?.takeIf { it.qself == null }?.path ?: return
    val retLast = retPath.segments.toList().lastOrNull() ?: return
    if (retLast.ident.toString() != "Option") return
    val angle = retLast.arguments as? PathArguments.AngleBracketed ?: return
    val rawInnerSyn = angle.args.toList().firstNotNullOfOrNull { (it as? GenericArgument.TypeArg)?.type } ?: return
    val innerSyn = unwrapSynType(rawInnerSyn)
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
    if (!usesTaggedCoreResult()) return null
    if ("Ok" in inspector.declaredTypeNames || "Err" in inspector.declaredTypeNames) return null
    val pathSegs = pat.path.segments.toList()
    val segs = pathSegs.map { it.ident.toString() }
    val variant = segs.lastOrNull() ?: return null
    if (variant != "Ok" && variant != "Err") return null
    val isCoreResultPath =
        segs.size == 1 ||
            (segs.size == 2 && segs[0] == "Result") ||
            (segs.size >= 3 && segs[segs.size - 2] == "Result" && segs.dropLast(2).all { it in STDLIB_TYPE_PATH_PREFIXES || it == "result" })
    if (!isCoreResultPath) return null
    val prop = if (variant == "Ok") "okValue()" else "errValue()"
    return "${sourceResultName()}.$variant" to prop
}

internal fun KotlinJ2KEmitter.controlFlowVariantMatchInfo(pat: Pat.TupleStruct): Pair<String, String>? {
    val hasCore =
        "core" in headerContext.crateNamespaces ||
            headerContext.packageName?.startsWith("io.github.kotlinmania.rustlang.core") == true
    if (!hasCore) return null
    if ("Break" in inspector.declaredTypeNames || "Continue" in inspector.declaredTypeNames) return null
    val pathSegs = pat.path.segments.toList()
    val segs = pathSegs.map { it.ident.toString().removePrefix("r#") }
    val variant = segs.lastOrNull() ?: return null
    if (variant != "Break" && variant != "Continue") return null
    val isCfPath =
        segs.size == 1 ||
            (segs.size == 2 && segs[0] == "ControlFlow") ||
            (segs.size >= 3 && segs[segs.size - 2] == "ControlFlow")
    if (!isCfPath) return null
    if ("ControlFlow" !in inspector.declaredTypeNames) {
        val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
        CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.ops.controlflow.ControlFlow")
    }
    val prop = if (variant == "Break") "cfBreakValue()" else "cfContinueValue()"
    return "ControlFlow.$variant" to prop
}

internal fun KotlinJ2KEmitter.renderMatchLit(lit: Lit): String {
    val base = renderLit(lit)
    if (lit is Lit.Int) {
        val kind = currentExpectedNumericKind
        if (kind != null && (kind == NumericKind.U8 || kind == NumericKind.U16 || kind == NumericKind.I8 || kind == NumericKind.I16)) {
            val suffix = ".${NUMERIC_COERCE_SUFFIX_BY_KIND.getValue(kind)}"
            if (!base.endsWith(suffix)) {
                return "$base$suffix"
            }
        }
    }
    return base
}

internal fun KotlinJ2KEmitter.renderMatchRangeBound(
    boundExpr: Expr,
    indent: String,
    selfTypeName: String?,
): String {
    val u = unwrapParenOrGroupExpr(boundExpr)
    return if (u is Expr.Lit && u.lit is Lit.Int) {
        renderMatchLit(u.lit)
    } else {
        renderExpr(boundExpr, indent, selfTypeName)
    }
}

internal fun KotlinJ2KEmitter.renderIfLetPatternAgainstRhs(
    pat: Pat,
    rhs: String,
    indent: String,
    selfTypeName: String?,
    subjectExpr: Expr? = null,
): String =
    SubjectMatchConditionEmitter(this, selfTypeName).renderIfLetAgainstRhs(pat, rhs, indent, subjectExpr)

internal fun KotlinJ2KEmitter.isDiscriminatingOptionInnerPattern(pat: Pat): Boolean =
    PatternPredicateInspector(this).isDiscriminatingOptionInnerPattern(pat)

internal fun KotlinJ2KEmitter.renderExprForLoop(expr: Expr.ForLoop, indent: String, selfTypeName: String?): String {
    val loopIterExpr = expr.expr
    val loopPat = unwrapTransparentPat(expr.pat)
    val singleStmt = expr.body.stmts.singleOrNull() as? Stmt.ExprStmt
    val singleAssign = singleStmt?.expr as? Expr.Assign
    val rawPatIdent = (loopPat as? Pat.Ident)?.ident?.toString()?.removePrefix("r#")
    val mutatesDerefedPat = rawPatIdent != null && DerefedIdentMutationVisitor(this, rawPatIdent).check(expr.body)
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
        return withComprehensionTypedScope {
            try {
                val idxVar = scopeBuilder.bindLocal("_idx", "idx", loopPat.ident.span()).kotlinName
                val slotAccess = "$bodyIter[$idxVar]"
                activeGuardBindingSubstitutions = prevSubs + (rawPatIdent to slotAccess)
                if (elemRenderedTy != null) {
                    val ktPatLocal = rustValueIdentToKotlin(rawPatIdent)
                    currentTypedLocals = currentTypedLocals + (ktPatLocal to elemRenderedTy) + (slotAccess to elemRenderedTy)
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
                activeGuardBindingSubstitutions = prevSubs
            }
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

internal fun KotlinJ2KEmitter.isDerefedSingleIdent(expr: Expr, rawIdent: String): Boolean {
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

internal fun KotlinJ2KEmitter.renderExprMatch(expr: Expr.Match, indent: String, selfTypeName: String?): String {
    val savedKind = currentExpectedNumericKind
    val subject = withNeutralConditionContext { renderExpr(expr.expr, indent, selfTypeName) }
    val arms = deduplicateComplementaryCfgArms(expr.arms)
    if (arms.isEmpty()) {
        return "when ($subject) {}"
    }
    val isSimpleSubject = subject.all { it.isLetterOrDigit() || it == '_' }
    tryRenderStdlibResultMatch(expr, subject, isSimpleSubject, indent, selfTypeName)?.let { return it }
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
    return withMatchScrutineeScope(expr.expr, subject, bindFreshSubject = needsSubjectVar, selfTypeName = selfTypeName) { subjectVar ->
        val whenHeader = if (needsSubjectVar) "val $subjectVar = $subject" else subject
        val armIndent = "$indent    "
        val condEmitter = SubjectMatchConditionEmitter(this, selfTypeName)
        val patConditions =
            try {
                currentExpectedNumericKind = inferScrutineeNumericKind(expr.expr, selfTypeName)
                effectiveArms.mapIndexed { idx, arm ->
                    condEmitter.renderSubjectArm(
                        armPattern(arm),
                        subjectVar,
                        armIndent,
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
    if ("Ok" in inspector.declaredTypeNames || "Err" in inspector.declaredTypeNames) return null
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
    val wrapInRun = !isSimpleSubject
    return withMatchScrutineeScope(expr.expr, subject, bindFreshSubject = wrapInRun, selfTypeName = selfTypeName) { subjectVar ->
        val ifIndent = if (wrapInRun) "$indent    " else indent
        val okSubPat = (unwrapTransparentPat(armPattern(okArm)) as Pat.TupleStruct).elems.toList().firstOrNull()
        val errSubPat = (unwrapTransparentPat(armPattern(errArm)) as? Pat.TupleStruct)?.elems?.toList()?.firstOrNull()
        val okValueExpr = if (taggedCoreResult) "$subjectVar.okValue()" else "$subjectVar.getOrThrow()"
        val errValueExpr = if (taggedCoreResult) "$subjectVar.errValue()" else "$subjectVar.exceptionOrNull()!!"

        val okBranch =
            withComprehensionTypedScope {
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
            }
        val errBranch =
            withComprehensionTypedScope {
                val bindings =
                    if (errSubPat != null && armReferencesSubject(errSubPat)) {
                        extractMatchArmBindings(errSubPat, errValueExpr, "$ifIndent    ", selfTypeName, bindToScope = true)
                    } else {
                        emptyList()
                    }
                renderResultBranchBlock(errArm.body, bindings, ifIndent, selfTypeName)
            }

        val ifText =
            if (taggedCoreResult) {
                val result = sourceResultName()
                "when ($subjectVar) { is $result.Ok<*, *> -> $okBranch; is $result.Err<*, *> -> $errBranch }"
            } else {
                "if ($subjectVar.isSuccess) $okBranch else $errBranch"
            }
        if (wrapInRun) {
            "run {\n${ifIndent}val $subjectVar = $subject\n$ifIndent$ifText\n$indent}"
        } else {
            ifText
        }
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
    return MatchPatternTraversal.isStdlibOptionVariantPath(pat.path, "Some")
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
        is Pat.Path -> MatchPatternTraversal.isStdlibOptionVariantPath(u.path, "None")
        is Pat.Ident -> u.subpat == null && u.ident.toString() == "None"
        else -> false
    }
}

internal fun KotlinJ2KEmitter.deduplicateComplementaryCfgArms(arms: List<Arm>): List<Arm> {
    val activeArms = arms.filterNot { isDocOnlyCfgAttribute(it.attrs) }
    if (activeArms.size < 2) return activeArms
    val seenCfgByPatAndPred = linkedMapOf<Pair<String, String>, Pair<Boolean, Int>>()
    val result = ArrayList<Arm>(activeArms.size)
    val predicates = PatternPredicateInspector(this)
    for (arm in activeArms) {
        if (armGuardExpr(arm) == null) {
            val cfgSig = extractArmCfgSignature(arm.attrs)
            val patKey = predicates.irrefutableConstructorKey(armPattern(arm))
            if (cfgSig != null && patKey != null) {
                val key = patKey to cfgSig.first
                val prev = seenCfgByPatAndPred[key]
                if (prev != null && prev.first != cfgSig.second) {
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
            RustTokenCursor.extractCfgComplementSignature(meta.tokens.clone().toList())?.let { return it }
        }
    }
    return null
}

internal fun KotlinJ2KEmitter.joinPathIdents(path: Path): String =
    path.segments.toList().joinToString("::") { it.ident.toString() }

internal fun KotlinJ2KEmitter.armIrrefutableConstructorKey(pat: Pat): String? =
    PatternPredicateInspector(this).irrefutableConstructorKey(pat)

internal fun KotlinJ2KEmitter.renderBooleanExprMatch(
    expr: Expr.Match,
    subject: String,
    isSimpleSubject: Boolean,
    indent: String,
    selfTypeName: String?,
): String {
    val arms = expandOrArmsWithBindings(deduplicateComplementaryCfgArms(expr.arms))
    val wrapInRun = !isSimpleSubject
    return withMatchScrutineeScope(expr.expr, subject, bindFreshSubject = wrapInRun, selfTypeName = selfTypeName) { subjectVar ->
        val whenIndent = if (wrapInRun) "$indent    " else indent
        val armIndent = "$whenIndent    "

        val sb = StringBuilder()
        if (wrapInRun) {
            sb.append("run {\n")
            sb.append(whenIndent).append("val ").append(subjectVar)
            sb.append(" = ").append(subject).append('\n')
            sb.append(whenIndent).append("when {\n")
        } else {
            sb.append("when {\n")
        }

        val scrutineeNumericKind = inferScrutineeNumericKind(expr.expr, selfTypeName)
        var hasElseArm = false
        for ((idx, arm) in arms.withIndex()) {
            val (renderedArm, isElse) =
                renderBooleanMatchArm(
                    arm = arm,
                    subjectText = subjectVar,
                    indent = armIndent,
                    selfTypeName = selfTypeName,
                    isLastArm = idx == arms.lastIndex,
                    scrutineeNumericKind = scrutineeNumericKind,
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
    }
}

internal fun KotlinJ2KEmitter.armReferencesSubject(pat: Pat): Boolean =
    PatternPredicateInspector(this).referencesSubject(pat)

internal fun isStarProjectedOrPlainCheckType(typeTarget: String): Boolean {
    val lt = typeTarget.indexOf('<')
    if (lt < 0) return true
    if (!typeTarget.endsWith('>')) return false
    val inner = typeTarget.substring(lt + 1, typeTarget.length - 1).trim()
    if (inner.isEmpty()) return false
    return inner.split(',').all { it.trim() == "*" }
}

private fun hasTopLevelCommaOutsideGenerics(text: String): Boolean =
    KotlinTokenWalker.findTopLevelCommaIndex(text, trackParens = true) >= 0

internal fun KotlinJ2KEmitter.lookupEnumVariantTypeArity(variantType: String): Int {
    if ('<' in variantType) return 0
    inspector.enumTypeParamsByVariant[variantType]?.let { return it.size }
    val simple = variantType.substringAfterLast('.')
    if ('.' in variantType) {
        val parts = variantType.split('.')
        val enumSimple = parts[parts.size - 2]
        val lastTwo = "$enumSimple.$simple"
        inspector.enumTypeParamsByVariant[lastTwo]?.let { return it.size }
        importedSimpleNameToPkg[enumSimple]?.let { pkg ->
            val ns = currentNamespacePrefix()
            val sub = if (ns != null && pkg.startsWith("$ns.")) pkg.removePrefix("$ns.") else pkg.substringAfterLast('.')
            if (sub.isNotEmpty()) {
                headerContext.crateEnumVariantTypeArity["$sub.$lastTwo"]?.let { return it }
            }
        }
        headerContext.crateEnumVariantTypeArity[lastTwo]?.takeIf { it >= 0 }?.let { return it }
        for (scoped in scopedAbiKeysForRenderedType(lastTwo)) {
            headerContext.crateEnumVariantTypeArity[scoped]?.takeIf { it >= 0 }?.let { return it }
        }
        headerContext.externalEnumVariantTypeArity[lastTwo]?.takeIf { it >= 0 }?.let { return it }
        if (enumSimple in inspector.enumTypeNames) {
            return inspector.enumTypeParamsByVariant[lastTwo]?.size ?: 0
        }
        return 0
    }
    inspector.enumTypeParamsByVariant[simple]?.let { return it.size }
    headerContext.crateEnumVariantTypeArity[simple]?.takeIf { it >= 0 }?.let { return it }
    headerContext.externalEnumVariantTypeArity[simple]?.takeIf { it > 0 }?.let { return it }
    return 0
}

internal fun KotlinJ2KEmitter.isGenericUnitEnumVariant(variantType: String): Boolean {
    if ('<' in variantType) {
        return isGenericUnitEnumVariant(variantType.substringBefore('<').trim())
    }
    if (lookupEnumVariantTypeArity(variantType) <= 0) return false
    if ('.' in variantType) {
        val parts = variantType.split('.')
        if (parts.size < 2) return false
        val lastTwo = "${parts[parts.size - 2]}.${parts.last()}"
        return lastTwo in inspector.genericUnitEnumVariants ||
            lastTwo in headerContext.crateUnitEnumVariants ||
            lastTwo in headerContext.externalUnitEnumVariants ||
            scopedAbiKeysForRenderedType(lastTwo).any { it in headerContext.crateUnitEnumVariants }
    }
    if (variantType in activeTypeParamNames) return false
    return variantType in inspector.genericUnitEnumVariants ||
        (variantType in headerContext.crateUnitEnumVariants && (headerContext.crateEnumVariantTypeArity[variantType] ?: 0) > 0) ||
        (variantType in headerContext.externalUnitEnumVariants && (headerContext.externalEnumVariantTypeArity[variantType] ?: 0) > 0)
}

internal fun KotlinJ2KEmitter.starProjectedVariantIsType(variantType: String): String {
    if ('<' in variantType) return variantType
    val arity = lookupEnumVariantTypeArity(variantType)
    if (arity <= 0) return variantType
    return "$variantType<${List(arity) { "*" }.joinToString(", ")}>"
}

internal fun KotlinJ2KEmitter.parameterizedVariantCastType(variantType: String): String {
    if ('<' in variantType) return variantType
    val tps =
        inspector.enumTypeParamsByVariant[variantType]
            ?: inspector.enumTypeParamsByVariant[variantType.substringAfterLast('.')]
    if (tps != null && tps.isNotEmpty()) {
        val args =
            if (tps.all { it in activeTypeParamNames }) {
                tps.joinToString(", ")
            } else {
                tps.joinToString(", ") { "*" }
            }
        return "$variantType<$args>"
    }
    return starProjectedVariantIsType(variantType)
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
    val isSingleSmartCast =
        patCond.startsWith("is ") &&
            !hasTopLevelCommaOutsideGenerics(patCond) &&
            isStarProjectedOrPlainCheckType(patCond.removePrefix("is ").trim()) &&
            scopeBuilder.isSmartCastableLocalKotlinName(subjectText)
    val usedIdents = collectUsedRawIdentsInExpr(arm.body)
    val needsWriteback = mutRefWritebackTarget != null && matchArmMutatesTupleVariantBinding(arm)
    val trailingLines = mutableListOf<String>()
    return withComprehensionTypedScope {
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
    }
}

internal fun KotlinJ2KEmitter.renderBooleanMatchArm(
    arm: Arm,
    subjectText: String,
    indent: String,
    selfTypeName: String?,
    isLastArm: Boolean,
    scrutineeNumericKind: NumericKind? = null,
): Pair<String, Boolean> {
    val triviaPrefix = renderLeadingTriviaForSpan(arm.span(), indent)
    val basePat = armPattern(arm)
    val usedIdents = collectUsedRawIdentsInExpr(arm.body)
    val savedKind = currentExpectedNumericKind
    val savedTailOk = currentTailExpectsUnitResultOk
    val boolEmitter = PatternBooleanConditionEmitter(this, selfTypeName)
    return withComprehensionTypedScope {
        val (patCond, guardText) =
            try {
                currentExpectedNumericKind = scrutineeNumericKind
                currentTailExpectsUnitResultOk = false
                val condPart = boolEmitter.render(basePat, subjectText, indent)
                currentExpectedNumericKind = null
                val guardPart =
                    armGuardExpr(arm)?.let { guardExpr ->
                        boolEmitter.renderGuardWithPatternAccess(basePat, guardExpr, subjectText, indent)
                    }
                condPart to guardPart
            } finally {
                currentExpectedNumericKind = savedKind
                currentTailExpectsUnitResultOk = savedTailOk
            }
        val isSingleSmartCast =
            !(isLastArm && guardText == null) &&
                guardText?.contains(" || ") != true &&
                !patCond.contains(" || ") &&
                patCond.startsWith("$subjectText is ") &&
                isStarProjectedOrPlainCheckType(patCond.removePrefix("$subjectText is ").substringBefore(" && ").trim()) &&
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
            when {
                isLastArm && guardText == null -> "else"
                patCond == "true" && guardText == null -> "true"
                patCond == "true" && guardText != null -> guardText
                guardText == null -> patCond
                else -> {
                    val lhs = if (patCond.contains(" || ")) "($patCond)" else patCond
                    val rhs = if (guardText.contains(" || ")) "($guardText)" else guardText
                    "$lhs && $rhs"
                }
            }

        "$triviaPrefix$indent$fullCond -> $renderedBody" to (fullCond == "else")
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
    val importedPath = scopeBuilder.resolveModuleBinding(rawSeg)?.importedPath
    if (importedPath != null) {
        val parentPkg = importedPath.substringBeforeLast('.', missingDelimiterValue = "")
        val importedOwner = parentPkg.substringAfterLast('.')
        if (importedOwner.isNotEmpty()) {
            return !importedOwner.first().isUpperCase()
        }
    }
    if (inspector.hasImportedEnumGlob) return false
    return variantType !in inspector.enumVariantSimpleNames &&
        variantType !in inspector.importedEnumVariantSimpleNames
}

internal fun KotlinJ2KEmitter.patRequiresBooleanCondition(pat: Pat): Boolean =
    PatternPredicateInspector(this).requiresBooleanCondition(pat)

internal fun KotlinJ2KEmitter.isDiscriminatingSubpattern(pat: Pat): Boolean =
    PatternPredicateInspector(this).isDiscriminatingSubpattern(pat)

internal fun KotlinJ2KEmitter.renderPatternBooleanCondition(
    pat: Pat,
    subjectExpr: String,
    indent: String,
    selfTypeName: String?,
    allowSmartCast: Boolean = true,
): String =
    PatternBooleanConditionEmitter(this, selfTypeName).render(pat, subjectExpr, indent, allowSmartCast)

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
    allowSmartCast: Boolean = true,
) {
    PatternGuardAccessCollector(this, selfTypeName).collect(pat, subjectText, out, allowSmartCast)
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
    MatchArmBindingExtractor(
        emitter = this,
        selfTypeName = selfTypeName,
        bindToScope = bindToScope,
        usedBodyIdents = usedBodyIdents,
    ).extract(
        pat = pat,
        subjectText = subjectText,
        indent = indent,
        subjectAlreadySmartCast = subjectAlreadySmartCast,
        mutRefWritebackTarget = mutRefWritebackTarget,
        writebackLinesOut = writebackLinesOut,
    )

internal fun KotlinJ2KEmitter.tupleIndexExpr(subjectText: String, idx: Int, arity: Int): String =
    when {
        arity <= 3 && idx == 0 -> "$subjectText.first"
        arity <= 3 && idx == 1 -> "$subjectText.second"
        arity == 3 && idx == 2 -> "$subjectText.third"
        else -> "$subjectText[$idx]"
    }

internal fun KotlinJ2KEmitter.resolveScrutineeRenderedType(
    subjectText: String,
    selfTypeName: String?,
    fallbackToSelf: Boolean = true,
): String? {
    currentTypedLocals[subjectText]?.let { return it }
    val dotIdx = subjectText.lastIndexOf('.')
    if (dotIdx > 0) {
        val baseName = subjectText.substring(0, dotIdx)
        val memberName = subjectText.substring(dotIdx + 1)
        val compIdx =
            when (memberName) {
                "first" -> 0
                "second" -> 1
                "third" -> 2
                else -> -1
            }
        if (compIdx >= 0) {
            val baseType = currentTypedLocals[baseName]?.removeSuffix("?")?.trim()
            if (baseType != null && (baseType.startsWith("Pair<") || baseType.startsWith("Triple<")) && baseType.endsWith(">")) {
                val argsInner = baseType.substringAfter('<').dropLast(1)
                val args = PhantomRawPointerTypeStripper.splitTopLevelCommaList(argsInner)
                val resolvedComp = args.getOrNull(compIdx)?.trim()
                if (!resolvedComp.isNullOrEmpty()) return resolvedComp
            }
        }
    }
    if (subjectText == "this") {
        return currentTypedLocals["this"] ?: selfTypeName
    }
    return if (fallbackToSelf) selfTypeName else null
}

internal fun KotlinJ2KEmitter.qualifyShadowedPatternVariantType(
    variantType: String,
    subjectText: String,
    selfTypeName: String?,
): String {
    if ('.' in variantType) return variantType
    val rawScrutinee = resolveScrutineeRenderedType(subjectText, selfTypeName)
    val scrutineeNoNull = rawScrutinee?.removeSuffix("?")?.substringBefore('<')
    val scrutineeBase =
        scrutineeNoNull
            ?.substringAfterLast('.')
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it !in activeTypeParamNames }
    if (variantType in activeTypeParamNames) {
        val ownerEnum =
            scrutineeBase?.takeIf {
                "$it.$variantType" in inspector.enumVariantFieldTypes || variantType in inspector.enumVariantSimpleNames
            }
                ?: inspector.enumVariantFieldTypes.keys
                    .firstOrNull { it.endsWith(".$variantType") && '.' in it }
                    ?.substringBeforeLast('.')
        return if (ownerEnum != null) "$ownerEnum.$variantType" else variantType
    }
    if (scrutineeBase != null &&
        scrutineeBase !in inspector.enumTypeNames &&
        (variantType in inspector.enumVariantSimpleNames || variantType in inspector.importedEnumVariantSimpleNames)
    ) {
        val qualifiedPair = "$scrutineeBase.$variantType"
        val existsInKnownEnums =
            qualifiedPair in headerContext.crateEnumVariantTypeArity ||
                scopedAbiKeysForRenderedType(qualifiedPair).any { it in headerContext.crateEnumVariantTypeArity } ||
                qualifiedPair in headerContext.externalEnumVariantTypeArity
        if (existsInKnownEnums) {
            val resolvedOwner =
                if (scrutineeBase in importedSimpleNameToPkg) {
                    scrutineeBase
                } else {
                    val sub = resolveBestSubpackageForSymbol(scrutineeBase)
                    val ns = currentNamespacePrefix()
                    val pkg =
                        if (sub != null && ns != null) {
                            if (sub.isEmpty()) ns else "$ns.$sub"
                        } else {
                            headerContext.externalCrateSymbolPackages.values
                                .firstNotNullOfOrNull { it[scrutineeBase]?.firstOrNull() }
                        }
                    if (pkg != null) bindOrQualifyResolvedCrossSymbol("$pkg.$scrutineeBase") else scrutineeBase
                }
            return "$resolvedOwner.$variantType"
        }
    }
    return variantType
}

internal fun KotlinJ2KEmitter.renderMatchPatternCondition(
    pat: Pat,
    subjectText: String,
    indent: String,
    selfTypeName: String?,
    isLastArm: Boolean,
): String =
    SubjectMatchConditionEmitter(this, selfTypeName).renderSubjectArm(pat, subjectText, indent, isLastArm)
