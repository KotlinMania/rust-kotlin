package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.procmacro2.Delimiter
import io.github.kotlinmania.procmacro2.Spacing
import io.github.kotlinmania.procmacro2.TokenStream
import io.github.kotlinmania.procmacro2.TokenTree
import io.github.kotlinmania.syn.Attribute
import io.github.kotlinmania.syn.Expr
import io.github.kotlinmania.syn.ExprParse
import io.github.kotlinmania.syn.File
import io.github.kotlinmania.syn.Item
import io.github.kotlinmania.syn.Lit
import io.github.kotlinmania.syn.Meta
import io.github.kotlinmania.syn.Stmt
import io.github.kotlinmania.syn.UnOp
import io.github.kotlinmania.syn.gen.Visit
import io.github.kotlinmania.syn.parse2

internal data class StandaloneMacroEmission(
    val declarations: List<String>,
    val emittedMacroNames: Set<String>,
    val extraImports: Set<String> = emptySet(),
)

private sealed class CfgConditionAst {
    abstract fun render(parenthesizeBinary: Boolean = false): String

    data class Flag(
        val constName: String,
        val defaultValue: Boolean,
    ) : CfgConditionAst() {
        override fun render(parenthesizeBinary: Boolean): String = constName
    }

    data class Not(
        val inner: CfgConditionAst,
    ) : CfgConditionAst() {
        override fun render(parenthesizeBinary: Boolean): String = "!${inner.render(parenthesizeBinary = true)}"
    }

    data class All(
        val items: List<CfgConditionAst>,
    ) : CfgConditionAst() {
        override fun render(parenthesizeBinary: Boolean): String {
            val joined = items.joinToString(" && ") { it.render(parenthesizeBinary = true) }
            return if (parenthesizeBinary && items.size > 1) "($joined)" else joined
        }
    }

    data class AnyOf(
        val items: List<CfgConditionAst>,
    ) : CfgConditionAst() {
        override fun render(parenthesizeBinary: Boolean): String {
            val joined = items.joinToString(" || ") { it.render(parenthesizeBinary = true) }
            return if (parenthesizeBinary && items.size > 1) "($joined)" else joined
        }
    }
}

private data class ScalarMacroLiteral(
    val kotlinType: String,
    val renderedExpr: String,
)

private data class MacroVariantAst(
    val item: Item.Macro,
    val cfgCondition: CfgConditionAst?,
    val branches: List<MacroRuleBranch>,
)

internal fun emitStandaloneMacroApproximations(
    originalFile: File,
    existingBodyItemsEmpty: Boolean,
    includeDocExampleAnnotations: Boolean,
    declaredNames: Set<String>,
    rawAbiMode: Boolean = false,
    coreNamespace: String = CoreIntrinsicLowering.DEFAULT_CORE_NAMESPACE,
): StandaloneMacroEmission =
    MacroApproximationContext(
        existingBodyItemsEmpty = existingBodyItemsEmpty,
        includeDocExampleAnnotations = includeDocExampleAnnotations,
        declaredNames = declaredNames,
        rawAbiMode = rawAbiMode,
        coreNamespace = coreNamespace,
    ).emitForFile(originalFile)

private class MacroRuleCollectorVisitor : Visit() {
    val grouped: LinkedHashMap<String, MutableList<Item.Macro>> = linkedMapOf()

    override fun visitItem(i: Item) {
        if (itemHasDocOnlyCfg(i)) return
        when (i) {
            is Item.Macro -> visitItemMacro(i)
            is Item.Mod -> super.visitItemMod(i)
            else -> Unit
        }
    }

    override fun visitItemMacro(i: Item.Macro) {
        if (i.mac.path.isIdent("macro_rules")) {
            val rawName = i.ident?.toString()?.removePrefix("r#")
            if (!rawName.isNullOrEmpty()) {
                grouped.getOrPut(rawName) { mutableListOf() }.add(i)
            }
        }
    }
}

private class MacroApproximationContext(
    private val existingBodyItemsEmpty: Boolean,
    private val includeDocExampleAnnotations: Boolean,
    declaredNames: Set<String>,
    val rawAbiMode: Boolean,
    val coreNamespace: String,
) {
    val cfgConstants: LinkedHashMap<String, Pair<Boolean, Boolean>> = linkedMapOf()
    val templateConstants: MutableList<String> = mutableListOf()
    val occupiedNames: MutableSet<String> = declaredNames.toMutableSet()
    val extraImports: MutableSet<String> = linkedSetOf()

    fun emitForFile(originalFile: File): StandaloneMacroEmission {
        val collector = MacroRuleCollectorVisitor().apply { visitFile(originalFile) }
        if (collector.grouped.isEmpty()) {
            return StandaloneMacroEmission(emptyList(), emptySet())
        }
        val eligibleGroups =
            collector.grouped.filter { (_, items) ->
                existingBodyItemsEmpty || items.any { hasMacroExportAttr(it.attrs) }
            }
        if (eligibleGroups.isEmpty()) {
            return StandaloneMacroEmission(emptyList(), emptySet())
        }

        val macroDeclarations = mutableListOf<String>()
        val emittedNames = linkedSetOf<String>()
        for ((rawMacroName, rawVariants) in eligibleGroups) {
            val isPublic = rawVariants.any { hasMacroExportAttr(it.attrs) }
            val cfgParser = MacroCfgAttributeParser(isPublic, cfgConstants)
            val parsedVariants =
                rawVariants.map { item ->
                    MacroVariantAst(
                        item = item,
                        cfgCondition = cfgParser.parseAttributes(item.attrs),
                        branches = MacroRulesCollector.parseMacroRulesBranches(item.mac.tokens.clone()),
                    )
                }
            val rendered = renderMacroGroup(rawMacroName, parsedVariants, isPublic)
            if (rendered != null) {
                macroDeclarations += rendered
                emittedNames += rawMacroName
            }
        }

        if (macroDeclarations.isEmpty()) {
            return StandaloneMacroEmission(emptyList(), emptySet())
        }

        val declarations =
            buildList {
                for ((constName, meta) in cfgConstants) {
                    if (constName !in occupiedNames) {
                        val (defaultValue, isPublic) = meta
                        val vis = if (isPublic) "public" else "internal"
                        add("$vis const val $constName: Boolean = $defaultValue")
                        occupiedNames += constName
                    }
                }
                addAll(templateConstants)
                addAll(macroDeclarations)
            }
        return StandaloneMacroEmission(
            declarations = declarations,
            emittedMacroNames = emittedNames,
            extraImports = extraImports,
        )
    }

    private fun renderMacroGroup(
        rawMacroName: String,
        variants: List<MacroVariantAst>,
        isPublic: Boolean,
    ): String? {
        val vis = if (isPublic) "public" else "internal"
        val kdoc =
            variants
                .firstNotNullOfOrNull { v ->
                    renderKDocFromAttributes(
                        attrs = v.item.attrs,
                        indent = "",
                        includeDocExampleAnnotations = includeDocExampleAnnotations,
                    ).takeIf { it.isNotEmpty() }
                }.orEmpty()
        val kdocPrefix = if (kdoc.isEmpty()) "" else "$kdoc\n"
        val groupSpec =
            MacroGroupSpec(
                rawMacroName = rawMacroName,
                constName = macroHelperConstIdentifier(rawMacroName),
                fnName = rustFunctionIdentToKotlin(rawMacroName),
                vis = vis,
                kdocPrefix = kdocPrefix,
                variants = variants,
            )
        return MACRO_APPROXIMATION_RULES.firstNotNullOfOrNull { rule ->
            rule.tryRender(groupSpec, this)
        }
    }

    companion object {
        private val MACRO_APPROXIMATION_RULES: List<MacroApproximationRule> =
            listOf(
                RawPointerLiteralMacroRule,
                ScalarConstMacroRule,
                ScalarConditionalMacroRule,
                TemplateConditionalMacroRule,
            )
    }
}

private data class MacroGroupSpec(
    val rawMacroName: String,
    val constName: String,
    val fnName: String,
    val vis: String,
    val kdocPrefix: String,
    val variants: List<MacroVariantAst>,
) {
    val unconditionalBranches: List<MacroRuleBranch>?
        get() = variants.singleOrNull()?.takeIf { it.cfgCondition == null }?.branches
}

/**
 * Base rule in the standalone `macro_rules!` approximation chain.
 * Concrete subclasses match progressively more general macro shapes (interned raw C/wide string
 * pointer literals, zero-arg scalar constants, multi-branch scalar lookup tables, and general
 * `cfg`-gated string template approximations).
 */
private abstract class MacroApproximationRule {
    abstract fun tryRender(
        spec: MacroGroupSpec,
        ctx: MacroApproximationContext,
    ): String?
}

private object RawPointerLiteralMacroRule : MacroApproximationRule() {
    override fun tryRender(
        spec: MacroGroupSpec,
        ctx: MacroApproximationContext,
    ): String? {
        val branches = spec.unconditionalBranches ?: return null
        if (!isRawPointerLiteralMacroBranches(branches) || spec.fnName in ctx.occupiedNames) {
            return null
        }
        val rawParam = branches[0].paramNames.firstOrNull()?.removePrefix("r#") ?: "lit"
        val ktParam = sanitizeMacroParamName(rustValueIdentToKotlin(rawParam).ifEmpty { "lit" })
        val bodyText = branches.joinToString(" ") { it.bodyTokens.toString() }
        val isWide = spec.rawMacroName == "w" || "u16" in bodyText || "utf16" in bodyText
        val internFn = if (isWide) "kmInternWideStr" else "kmInternCStr"
        ctx.extraImports += "import ${ctx.coreNamespace}.intrinsics.$internFn"
        ctx.occupiedNames += spec.fnName
        return if (ctx.rawAbiMode) {
            "${spec.kdocPrefix}${spec.vis} fun ${spec.fnName}($ktParam: String): Long = $internFn($ktParam).rawAddress"
        } else {
            ctx.extraImports += "import ${ctx.coreNamespace}.ptr.ConstPtr"
            "${spec.kdocPrefix}${spec.vis} fun ${spec.fnName}($ktParam: String): ConstPtr = $internFn($ktParam)"
        }
    }
}

private object ScalarConstMacroRule : MacroApproximationRule() {
    override fun tryRender(
        spec: MacroGroupSpec,
        ctx: MacroApproximationContext,
    ): String? {
        val branch = spec.unconditionalBranches?.singleOrNull() ?: return null
        if (branch.pattern.isNotEmpty() || branch.isRepetition || spec.constName in ctx.occupiedNames) {
            return null
        }
        if (branch.bodyTokens.isEmpty()) {
            ctx.occupiedNames += spec.constName
            return "${spec.kdocPrefix}${spec.vis} const val ${spec.constName}: String = \"\""
        }
        val scalar = extractScalarLiteralFromMacroBody(branch.bodyTokens) ?: return null
        ctx.occupiedNames += spec.constName
        return "${spec.kdocPrefix}${spec.vis} const val ${spec.constName}: ${scalar.kotlinType} = ${scalar.renderedExpr}"
    }
}

private object ScalarConditionalMacroRule : MacroApproximationRule() {
    override fun tryRender(
        spec: MacroGroupSpec,
        ctx: MacroApproximationContext,
    ): String? {
        val branches = spec.unconditionalBranches ?: return null
        if (branches.isEmpty() || spec.fnName in ctx.occupiedNames) return null
        val branchScalars = branches.map { extractScalarLiteralFromMacroBody(it.bodyTokens) ?: return null }
        val returnType = branchScalars.map { it.kotlinType }.distinct().singleOrNull() ?: return null

        val splitPatterns = branches.map { splitPatternTopLevelCommaClauses(it.pattern) }
        val arity = splitPatterns.maxOfOrNull { it.size } ?: 0
        if (arity == 0) return null

        val paramNames = deriveSlotParameterNames(splitPatterns, arity)
        val whenArms = mutableListOf<String>()
        var fallbackExpr: String? = null

        for (branchIdx in branches.indices) {
            val clauses = splitPatterns[branchIdx]
            val scalar = branchScalars[branchIdx]
            val condParts = mutableListOf<String>()
            if (clauses.size != arity) {
                condParts += "${paramNames[0]}.isNotEmpty()"
            }
            for (slotIdx in clauses.indices) {
                val literalMatch = extractPureLiteralClauseText(clauses[slotIdx])
                if (literalMatch != null) {
                    condParts += "${paramNames[slotIdx]} == ${renderKotlinDoubleQuotedString(literalMatch)}"
                }
            }
            if (condParts.isEmpty()) {
                if (fallbackExpr == null) {
                    fallbackExpr = scalar.renderedExpr
                }
            } else {
                whenArms += "        ${condParts.joinToString(" && ")} -> ${scalar.renderedExpr}"
            }
        }

        val defaultScalar = fallbackExpr ?: defaultInitializerForType(returnType)
        val armWalkers = whenArms.map(::KotlinTokenWalker)
        val usedParams = paramNames.filter { p -> armWalkers.any { it.containsIdentifier(p) } }
        val unusedGuard = paramNames.filter { it !in usedParams }.joinToString(" && ") { "$it.isEmpty()" }

        ctx.occupiedNames += spec.fnName
        val sigParams = paramNames.joinToString(", ") { "$it: String = \"\"" }
        if (unusedGuard.isNotEmpty() && whenArms.isEmpty()) {
            return buildString {
                append(spec.kdocPrefix)
                append("${spec.vis} fun ${spec.fnName}($sigParams): $returnType =\n")
                append("    if ($unusedGuard) $defaultScalar else $defaultScalar")
            }
        }
        return buildString {
            append(spec.kdocPrefix)
            append("${spec.vis} fun ${spec.fnName}($sigParams): $returnType =\n")
            append("    when {\n")
            for (arm in whenArms) {
                append(arm).append('\n')
            }
            if (unusedGuard.isNotEmpty()) {
                append("        $unusedGuard -> $defaultScalar\n")
            }
            append("        else -> $defaultScalar\n")
            append("    }")
        }
    }

    private fun splitPatternTopLevelCommaClauses(pattern: List<MacroPat>): List<List<MacroPat>> {
        if (pattern.isEmpty()) return emptyList()
        val clauses = mutableListOf<List<MacroPat>>()
        val current = mutableListOf<MacroPat>()
        for (pat in pattern) {
            if (pat is MacroPat.LiteralToken && pat.isPunct && pat.text == ",") {
                clauses += current.toList()
                current.clear()
            } else {
                current += pat
            }
        }
        if (current.isNotEmpty()) {
            clauses += current.toList()
        }
        return clauses
    }

    private fun deriveSlotParameterNames(
        splitPatterns: List<List<List<MacroPat>>>,
        arity: Int,
    ): List<String> {
        val used = linkedSetOf<String>()
        val result = mutableListOf<String>()
        for (slot in 0 until arity) {
            val candidate =
                splitPatterns.firstNotNullOfOrNull { clauses ->
                    clauses
                        .getOrNull(slot)
                        ?.let(::findFirstMetavarName)
                        ?.let { sanitizeMacroParamName(rustValueIdentToKotlin(it)) }
                }
            var base = candidate?.takeIf { it.isNotEmpty() && it != "_" } ?: "arg$slot"
            if (base in used) {
                base = "${base}$slot"
            }
            used += base
            result += base
        }
        return result
    }

    private fun findFirstMetavarName(pats: List<MacroPat>): String? {
        for (pat in pats) {
            when (pat) {
                is MacroPat.Metavar -> return pat.name
                is MacroPat.GroupPat -> findFirstMetavarName(pat.inner)?.let { return it }
                is MacroPat.Repetition -> findFirstMetavarName(pat.inner)?.let { return it }
                is MacroPat.LiteralToken -> Unit
            }
        }
        return null
    }

    private fun extractPureLiteralClauseText(clause: List<MacroPat>): String? {
        if (clause.isEmpty()) return null
        val sb = StringBuilder()
        var prevWasPunct = false
        for (pat in clause) {
            when (pat) {
                is MacroPat.LiteralToken -> {
                    if (sb.isNotEmpty() && !prevWasPunct && !pat.isPunct) {
                        sb.append(' ')
                    }
                    sb.append(pat.text)
                    prevWasPunct = pat.isPunct
                }
                else -> return null
            }
        }
        return sb.toString()
    }
}

private object TemplateConditionalMacroRule : MacroApproximationRule() {
    private val IGNORED_STRUCTURAL_KEYWORDS: Set<String> = setOf("fn", "let")

    override fun tryRender(
        spec: MacroGroupSpec,
        ctx: MacroApproximationContext,
    ): String {
        val effectiveFnName = if (spec.fnName in ctx.occupiedNames) "${spec.fnName}Macro" else spec.fnName
        ctx.occupiedNames += effectiveFnName

        val allMetavars = collectNormalizedMetavars(spec.variants)
        val needsClauseSelector =
            spec.variants.any { v ->
                v.branches.size > 1 && v.branches.any { extractPatternDistinguishingLiterals(it.pattern).isNotEmpty() }
            }
        val paramDecls =
            buildList {
                for (ktParam in allMetavars.values) {
                    add("$ktParam: String = \"\"")
                }
                if (needsClauseSelector) {
                    add("clause: String = \"\"")
                }
            }

        var templateCounter = 0

        fun renderBranch(branch: MacroRuleBranch): String =
            renderSingleTemplateBranchExpr(
                branch = branch,
                macroConstPrefix = spec.constName,
                templateIndex = templateCounter++,
                allMetavars = allMetavars,
                ctx = ctx,
            )

        fun renderVariantBodyExpr(variant: MacroVariantAst, indent: String): String {
            val branches = variant.branches
            if (branches.isEmpty()) return "\"\""
            if (branches.size == 1) return renderBranch(branches[0])

            val armPairs = mutableListOf<Pair<String, String>>()
            val seenConditions = linkedSetOf<String>()
            var fallbackArmExpr: String? = null
            for (branch in branches) {
                val cond = buildTemplateBranchCondition(branch, allMetavars, needsClauseSelector)
                if (cond == null) {
                    if (fallbackArmExpr == null) {
                        fallbackArmExpr = renderBranch(branch)
                    }
                } else if (seenConditions.add(cond)) {
                    armPairs += cond to renderBranch(branch)
                }
            }
            val finalElse = fallbackArmExpr ?: "\"\""
            if (armPairs.isEmpty()) return finalElse
            val sortedArms =
                armPairs
                    .sortedByDescending { (cond, _) ->
                        (if (cond.contains("clause ==")) 100 else 0) + cond.count { it == '&' }
                    }.map { (cond, expr) -> "$indent    $cond -> $expr" }
            return buildString {
                append("when {\n")
                for (arm in sortedArms) {
                    append(arm).append('\n')
                }
                append("$indent    else -> $finalElse\n")
                append("$indent}")
            }
        }

        val variants = spec.variants
        val bodyExpr =
            if (variants.size == 1) {
                val single = variants[0]
                val inner = renderVariantBodyExpr(single, indent = "    ")
                val cfg = single.cfgCondition
                if (cfg == null) {
                    inner
                } else {
                    "if (${cfg.render()}) {\n        ${inner.replace("\n    ", "\n        ")}\n    } else {\n        \"\"\n    }"
                }
            } else if (variants.size == 2 && isNegationOf(variants[1].cfgCondition, variants[0].cfgCondition)) {
                val condText = variants[0].cfgCondition!!.render()
                val thenExpr = renderVariantBodyExpr(variants[0], indent = "        ")
                val elseExpr = renderVariantBodyExpr(variants[1], indent = "        ")
                "if ($condText) {\n        $thenExpr\n    } else {\n        $elseExpr\n    }"
            } else {
                buildString {
                    append("when {\n")
                    var elseExpr: String? = null
                    for (variant in variants) {
                        val vExpr = renderVariantBodyExpr(variant, indent = "        ")
                        val cfg = variant.cfgCondition
                        if (cfg == null) {
                            if (elseExpr == null) elseExpr = vExpr
                        } else {
                            append("        ${cfg.render()} -> $vExpr\n")
                        }
                    }
                    append("        else -> ${elseExpr ?: "\"\""}\n")
                    append("    }")
                }
            }

        val bodyWalker = KotlinTokenWalker(bodyExpr)
        val unusedParams =
            buildList {
                for (ktParam in allMetavars.values) {
                    if (!bodyWalker.containsIdentifier(ktParam)) {
                        add(ktParam)
                    }
                }
                if (needsClauseSelector && !bodyWalker.containsIdentifier("clause")) {
                    add("clause")
                }
            }

        val finalBodyExpr =
            if (unusedParams.isEmpty()) {
                bodyExpr
            } else {
                val touchCond = unusedParams.joinToString(" && ") { "$it.isEmpty()" }
                val indentedBody = bodyExpr.replace("\n    ", "\n        ")
                "if ($touchCond) {\n        $indentedBody\n    } else {\n        $indentedBody\n    }"
            }

        return buildString {
            append(spec.kdocPrefix)
            append("${spec.vis} fun $effectiveFnName(${paramDecls.joinToString(", ")}): String =\n")
            append("    $finalBodyExpr")
        }
    }

    private fun collectNormalizedMetavars(variants: List<MacroVariantAst>): LinkedHashMap<String, String> {
        val allMetavars = linkedMapOf<String, String>()
        for (variant in variants) {
            for (branch in variant.branches) {
                for (rawParam in branch.paramNames) {
                    val cleaned = rawParam.removePrefix("r#")
                    if (cleaned.isNotEmpty() && cleaned !in allMetavars) {
                        var ktParam = sanitizeMacroParamName(rustValueIdentToKotlin(cleaned))
                        if (ktParam == "_" || ktParam.isEmpty()) ktParam = "value"
                        if (ktParam == "clause" || ktParam in allMetavars.values) {
                            ktParam = "${ktParam}Arg"
                        }
                        allMetavars[cleaned] = ktParam
                    }
                }
            }
        }
        return allMetavars
    }

    private fun renderSingleTemplateBranchExpr(
        branch: MacroRuleBranch,
        macroConstPrefix: String,
        templateIndex: Int,
        allMetavars: Map<String, String>,
        ctx: MacroApproximationContext,
    ): String {
        if (branch.bodyTokens.isEmpty()) return "\"\""
        val templateText = MacroBodyTokenFormatter(branch.bodyTokens.clone().toList(), allMetavars).format()
        if (templateText.isEmpty()) return "\"\""

        var constName = if (templateIndex == 0) "${macroConstPrefix}_TEMPLATE" else "${macroConstPrefix}_TEMPLATE_$templateIndex"
        while (constName in ctx.occupiedNames) {
            constName = "${constName}_ALT"
        }
        ctx.occupiedNames += constName
        ctx.templateConstants += "private const val $constName: String = ${renderKotlinDoubleQuotedString(templateText)}"

        val usedKtParams =
            branch.paramNames
                .map { it.removePrefix("r#") }
                .distinct()
                .mapNotNull { allMetavars[it] }
                .distinct()
                .filter { ktParam -> templateText.contains("\$$ktParam") }
        return when (usedKtParams.size) {
            0 -> constName
            1 -> {
                val ktParam = usedKtParams[0]
                "$constName.replace(${renderKotlinDoubleQuotedString("\$$ktParam")}, $ktParam)"
            }
            else -> {
                val pairs = usedKtParams.joinToString(", ") { ktParam -> "${renderKotlinDoubleQuotedString("\$$ktParam")} to $ktParam" }
                "arrayOf($pairs).fold($constName) { acc0, (k0, v0) -> acc0.replace(k0, v0) }"
            }
        }
    }

    private fun buildTemplateBranchCondition(
        branch: MacroRuleBranch,
        allMetavars: Map<String, String>,
        hasClauseSelector: Boolean,
    ): String? {
        val conds = mutableListOf<String>()
        val literals = extractPatternDistinguishingLiterals(branch.pattern)
        if (hasClauseSelector && literals.isNotEmpty()) {
            conds += "clause == ${renderKotlinDoubleQuotedString(literals.joinToString(" "))}"
        }
        val distinctParams =
            branch.paramNames
                .map { it.removePrefix("r#") }
                .distinct()
                .mapNotNull { allMetavars[it] }
        for (ktParam in distinctParams) {
            conds += "$ktParam.isNotEmpty()"
        }
        return conds.takeIf { it.isNotEmpty() }?.joinToString(" && ")
    }

    private fun extractPatternDistinguishingLiterals(pattern: List<MacroPat>): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < pattern.size) {
            when (val pat = pattern[i]) {
                is MacroPat.LiteralToken -> {
                    if (pat.isPunct && (pat.text == "#" || pat.text == "@") && i + 1 < pattern.size) {
                        val next = pattern[i + 1]
                        if (next is MacroPat.LiteralToken && !next.isPunct) {
                            out += "@${translateMacroTemplateIdent(next.text)}"
                            i += 2
                            continue
                        }
                    }
                    if (!pat.isPunct && pat.text !in IGNORED_STRUCTURAL_KEYWORDS) {
                        out += translateMacroTemplateIdent(pat.text)
                    } else if (pat.isPunct && pat.text != "," && pat.text != ";" && pat.text != ":" && pat.text != "!") {
                        out += pat.text
                    }
                }
                is MacroPat.GroupPat -> out.addAll(extractPatternDistinguishingLiterals(pat.inner))
                is MacroPat.Repetition -> out.addAll(extractPatternDistinguishingLiterals(pat.inner))
                is MacroPat.Metavar -> Unit
            }
            i++
        }
        return out
    }

    private fun isNegationOf(candidate: CfgConditionAst?, original: CfgConditionAst?): Boolean =
        candidate is CfgConditionAst.Not && original != null && candidate.inner == original
}

private class MacroCfgAttributeParser(
    private val isPublic: Boolean,
    private val cfgConstants: LinkedHashMap<String, Pair<Boolean, Boolean>>,
) {
    fun parseAttributes(attrs: List<Attribute>): CfgConditionAst? {
        val conditions =
            attrs.mapNotNull { attr ->
                val meta = attr.meta
                if (meta is Meta.List && meta.path.isIdent("cfg")) {
                    parseTokens(meta.tokens.clone().toList())
                } else {
                    null
                }
            }
        return when (conditions.size) {
            0 -> null
            1 -> conditions[0]
            else -> CfgConditionAst.All(conditions)
        }
    }

    private fun parseTokens(tokens: List<TokenTree>): CfgConditionAst? {
        if (tokens.isEmpty()) return null
        val cursor = RustTokenCursor(tokens)
        val firstIdent = cursor.cleanIdentAt(0)
        val parenGroup = if (firstIdent != null && cursor.size == 2) cursor.groupAt(1, Delimiter.Parenthesis) else null
        if (firstIdent != null && parenGroup != null) {
            val groupTokens = parenGroup.stream().clone().toList()
            val clauses = RustTokenCursor(groupTokens).splitByTopLevelPunct(',').mapNotNull(::parseTokens)
            return when (firstIdent) {
                "not" -> clauses.firstOrNull()?.let(CfgConditionAst::Not)
                "all" -> clauses.singleOrNull() ?: clauses.takeIf { it.isNotEmpty() }?.let(CfgConditionAst::All)
                "any" -> clauses.singleOrNull() ?: clauses.takeIf { it.isNotEmpty() }?.let(CfgConditionAst::AnyOf)
                else -> registerLeaf("cfg_${firstIdent}_${joinCfgIdentParts(groupTokens)}", defaultValue = true)
            }
        }
        if (firstIdent != null && cursor.size >= 3 && cursor.punctCharAt(1) == '=') {
            val rhsRaw = extractCfgLiteralValue(tokens.subList(2, tokens.size))
            val constName = rustConstIdentToKotlin("cfg_${firstIdent}_$rhsRaw")
            return registerLeaf(constName, defaultValue = firstIdent != "doc" && firstIdent != "docsrs")
        }
        if (firstIdent != null && cursor.size == 1) {
            val constName = rustConstIdentToKotlin("cfg_$firstIdent")
            return registerLeaf(constName, defaultValue = firstIdent != "doc" && firstIdent != "docsrs" && firstIdent != "test")
        }
        val fallback = joinCfgIdentParts(tokens)
        return if (fallback.isEmpty()) null else registerLeaf(rustConstIdentToKotlin("cfg_$fallback"), defaultValue = true)
    }

    private fun registerLeaf(
        rawConstName: String,
        defaultValue: Boolean,
    ): CfgConditionAst.Flag {
        val sanitized = sanitizeConstIdentifierSegment(rawConstName).ifEmpty { "cfg_flag" }
        val constName = macroHelperConstIdentifier(sanitized)
        val prev = cfgConstants[constName]
        val mergedPublic = (prev?.second ?: false) || isPublic
        val mergedDefault = prev?.first ?: defaultValue
        cfgConstants[constName] = mergedDefault to mergedPublic
        return CfgConditionAst.Flag(constName, mergedDefault)
    }

    private fun extractCfgLiteralValue(tokens: List<TokenTree>): String {
        val parts = mutableListOf<String>()
        for (tt in tokens) {
            when (tt) {
                is TokenTree.Literal -> {
                    val raw = tt.value.strValue().value ?: tt.value.toString().trim('"')
                    val clean = sanitizeConstIdentifierSegment(raw)
                    if (clean.isNotEmpty()) parts += clean
                }
                is TokenTree.Ident -> {
                    val clean = sanitizeConstIdentifierSegment(tt.value.toString())
                    if (clean.isNotEmpty()) parts += clean
                }
                else -> Unit
            }
        }
        return parts.joinToString("_").ifEmpty { "enabled" }
    }

    private fun joinCfgIdentParts(tokens: List<TokenTree>): String {
        val parts = mutableListOf<String>()
        for (tt in tokens) {
            when (tt) {
                is TokenTree.Ident -> {
                    val clean = sanitizeConstIdentifierSegment(tt.value.toString())
                    if (clean.isNotEmpty()) parts += clean
                }
                is TokenTree.Literal -> parts += extractCfgLiteralValue(listOf(tt))
                is TokenTree.Group -> {
                    val groupStream = tt.value.stream()
                    val nested = joinCfgIdentParts(groupStream.clone().toList())
                    if (nested.isNotEmpty()) parts += nested
                }
                else -> Unit
            }
        }
        return parts.joinToString("_")
    }
}

private class MacroBodyTokenFormatter(
    trees: List<TokenTree>,
    private val allMetavars: Map<String, String>,
) : RustTokenCursor(trees) {
    fun format(): String {
        val sb = StringBuilder()
        appendInto(sb)
        return sb.toString().trim()
    }

    private fun appendInto(sb: StringBuilder) {
        var i = 0
        while (i < size) {
            consumeAttribute(i, allowInnerAttr = true)?.let {
                i = it
                continue
            }
            if (isLifetimeAt(i)) {
                i += 2
                continue
            }
            if (punctCharAt(i) == '&') {
                if (punctCharAt(i + 1) == '$') {
                    i += 1
                    continue
                }
                if (identAt(i + 1) == "mut" && punctCharAt(i + 2) == '$') {
                    i += 2
                    continue
                }
            }
            if (punctCharAt(i) == '$' && i + 1 < size) {
                val nextIdent = identAt(i + 1)
                if (nextIdent == "crate") {
                    i += 2
                    while (punctCharAt(i) == ':' && punctCharAt(i + 1) == ':') {
                        i += 2
                        if (identAt(i) == "__private") {
                            i++
                        } else {
                            break
                        }
                    }
                    continue
                }
                if (nextIdent != null) {
                    if (sb.isNotEmpty() && sb.last() != ' ' && sb.last() != '(' && sb.last() != '[' && sb.last() != '{') {
                        sb.append(' ')
                    }
                    val raw = nextIdent.removePrefix("r#")
                    val ktParam = allMetavars[raw] ?: sanitizeMacroParamName(rustValueIdentToKotlin(raw))
                    sb.append('$').append(ktParam)
                    i += 2
                    continue
                }
            }
            if (isDoubleColonAt(i)) {
                sb.append('.')
                i += 2
                continue
            }
            when (val tt = trees[i]) {
                is TokenTree.Ident -> {
                    val word = translateMacroTemplateIdent(tt.value.toString())
                    if (sb.isNotEmpty() && !sb.endsWith('.') && !sb.endsWith('(') && !sb.endsWith('[') && !sb.endsWith('{') && !sb.endsWith(' ')) {
                        sb.append(' ')
                    }
                    sb.append(word)
                    if (punctCharAt(i + 1) == '!') {
                        i += 2
                        continue
                    }
                }
                is TokenTree.Literal -> {
                    if (sb.isNotEmpty() && !sb.endsWith('(') && !sb.endsWith('[') && !sb.endsWith(' ')) {
                        sb.append(' ')
                    }
                    sb.append(tt.value.toString())
                }
                is TokenTree.Punct -> {
                    val ch = tt.value.asChar()
                    sb.append(ch)
                    if ((ch == ';' || ch == ',') && tt.value.spacing() == Spacing.Alone) {
                        sb.append(' ')
                    }
                }
                is TokenTree.Group -> {
                    val (open, close) =
                        when (tt.value.delimiter()) {
                            Delimiter.Parenthesis -> "(" to ")"
                            Delimiter.Brace -> "{" to "}"
                            Delimiter.Bracket -> "[" to "]"
                            Delimiter.None -> "" to ""
                        }
                    if (open == "{" && sb.isNotEmpty() && !sb.endsWith(' ')) {
                        sb.append(' ')
                    }
                    sb.append(open)
                    val groupStream = tt.value.stream()
                    MacroBodyTokenFormatter(groupStream.clone().toList(), allMetavars).appendInto(sb)
                    sb.append(close)
                }
            }
            i++
        }
    }
}

private fun hasMacroExportAttr(attrs: List<Attribute>): Boolean =
    attrs.any { it.path().isIdent("macro_export") }

private fun sanitizeConstIdentifierSegment(raw: String): String {
    val sb = StringBuilder(raw.length)
    var prevUnderscore = false
    for (ch in raw) {
        if (ch.isLetterOrDigit()) {
            sb.append(ch)
            prevUnderscore = false
        } else if (!prevUnderscore && sb.isNotEmpty()) {
            sb.append('_')
            prevUnderscore = true
        }
    }
    return sb.toString().trim('_')
}

private fun macroHelperConstIdentifier(raw: String): String {
    val prevRaw = TranslationSession.rawAbiMode
    TranslationSession.rawAbiMode = false
    return try {
        KotlinNamingPolicy.Default.toConstIdentifier(raw)
    } finally {
        TranslationSession.rawAbiMode = prevRaw
    }
}

private fun sanitizeMacroParamName(ktIdent: String): String =
    when (ktIdent) {
        "this", "self", "Self" -> "thisRef"
        else -> ktIdent
    }

private fun translateMacroTemplateIdent(rawIdent: String): String {
    val word = rawIdent.removePrefix("r#")
    return when (word) {
        "pub" -> "public"
        "fn" -> "fun"
        "struct" -> "class"
        "enum" -> "sealed class"
        "trait" -> "interface"
        "impl" -> "class"
        "let" -> "val"
        "mut" -> "var"
        "match" -> "when"
        "use" -> "import"
        "macro_rules" -> "macro"
        else ->
            if (word.firstOrNull()?.isUpperCase() == true) {
                rustTypeIdentToKotlin(word)
            } else {
                rustValueIdentToKotlin(word)
            }
    }
}

private fun extractScalarLiteralFromMacroBody(bodyTokens: TokenStream): ScalarMacroLiteral? {
    if (bodyTokens.isEmpty()) return null
    val expr = parse2(ExprParse::parse, bodyTokens.clone()).getOrNull() ?: return null
    return extractScalarLiteralFromExpr(expr)
}

private fun extractScalarLiteralFromExpr(expr: Expr): ScalarMacroLiteral? =
    when (expr) {
        is Expr.Group -> extractScalarLiteralFromExpr(expr.expr)
        is Expr.Paren -> extractScalarLiteralFromExpr(expr.expr)
        is Expr.BlockExpr -> {
            val tailStmt = expr.block.stmts.lastOrNull()
            if (tailStmt is Stmt.ExprStmt && tailStmt.semiToken == null) {
                extractScalarLiteralFromExpr(tailStmt.expr)
            } else {
                null
            }
        }
        is Expr.Unary ->
            if (expr.op is UnOp.Neg) {
                val inner = extractScalarLiteralFromExpr(expr.expr)
                if (inner != null && (inner.kotlinType == "Int" || inner.kotlinType == "Long" || inner.kotlinType == "Double")) {
                    ScalarMacroLiteral(inner.kotlinType, "-${inner.renderedExpr}")
                } else {
                    null
                }
            } else {
                null
            }
        is Expr.Lit -> convertSynLitToScalar(expr.lit)
        else -> null
    }

private fun convertSynLitToScalar(lit: Lit): ScalarMacroLiteral? =
    when (lit) {
        is Lit.Bool -> ScalarMacroLiteral("Boolean", lit.value.value().toString())
        is Lit.Int -> {
            val digits = lit.value.base10Digits()
            val suffix = lit.value.suffix()
            val intVal = digits.toIntOrNull()
            if (intVal != null && suffix != "i64" && suffix != "u64") {
                ScalarMacroLiteral("Int", intVal.toString())
            } else {
                digits.toLongOrNull()?.let { ScalarMacroLiteral("Long", "${it}L") }
            }
        }
        is Lit.Float -> {
            val digits = lit.value.base10Digits()
            digits.toDoubleOrNull()?.let { ScalarMacroLiteral("Double", digits) }
        }
        is Lit.Str -> ScalarMacroLiteral("String", renderKotlinDoubleQuotedString(lit.value.value()))
        is Lit.Char -> {
            val cp = lit.value.value()
            if (cp in 0..0xFFFF) {
                ScalarMacroLiteral("Char", quoteKotlinCharLiteral(cp.toChar()))
            } else {
                null
            }
        }
        else -> null
    }

private fun quoteKotlinCharLiteral(value: Char): String =
    when (value) {
        '\\' -> "'\\\\'"
        '\'' -> "'\\''"
        '\n' -> "'\\n'"
        '\r' -> "'\\r'"
        '\t' -> "'\\t'"
        else -> "'$value'"
    }
