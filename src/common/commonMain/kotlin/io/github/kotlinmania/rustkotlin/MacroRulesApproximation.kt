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
import io.github.kotlinmania.syn.ModContent
import io.github.kotlinmania.syn.Stmt
import io.github.kotlinmania.syn.UnOp
import io.github.kotlinmania.syn.parse2

internal data class StandaloneMacroEmission(
    val declarations: List<String>,
    val emittedMacroNames: Set<String>,
    val extraImports: Set<String> = emptySet(),
)

private sealed class CfgConditionAst {
    data class Flag(
        val constName: String,
        val defaultValue: Boolean,
    ) : CfgConditionAst()

    data class Not(
        val inner: CfgConditionAst,
    ) : CfgConditionAst()

    data class All(
        val items: List<CfgConditionAst>,
    ) : CfgConditionAst()

    data class AnyOf(
        val items: List<CfgConditionAst>,
    ) : CfgConditionAst()
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
): StandaloneMacroEmission {
    val grouped = collectMacroRuleItems(originalFile)
    if (grouped.isEmpty()) {
        return StandaloneMacroEmission(emptyList(), emptySet())
    }

    val eligibleGroups =
        grouped.filter { (_, items) ->
            existingBodyItemsEmpty || items.any { hasMacroExportAttr(it.attrs) }
        }
    if (eligibleGroups.isEmpty()) {
        return StandaloneMacroEmission(emptyList(), emptySet())
    }

    val cfgConstants = linkedMapOf<String, Pair<Boolean, Boolean>>()
    val templateConstants = mutableListOf<String>()
    val macroDeclarations = mutableListOf<String>()
    val emittedNames = linkedSetOf<String>()
    val occupiedNames = declaredNames.toMutableSet()
    val extraImports = linkedSetOf<String>()

    for ((rawMacroName, rawVariants) in eligibleGroups) {
        val isPublic = rawVariants.any { hasMacroExportAttr(it.attrs) }
        val parsedVariants =
            rawVariants.map { item ->
                val cfgAst = parseItemCfgAttributes(item.attrs, isPublic, cfgConstants)
                val branches = MacroRulesCollector.parseMacroRulesBranches(item.mac.tokens.clone())
                MacroVariantAst(item = item, cfgCondition = cfgAst, branches = branches)
            }

        val rendered =
            renderMacroGroupApproximation(
                rawMacroName = rawMacroName,
                variants = parsedVariants,
                isPublic = isPublic,
                includeDocExampleAnnotations = includeDocExampleAnnotations,
                occupiedNames = occupiedNames,
                templateConstants = templateConstants,
                rawAbiMode = rawAbiMode,
                coreNamespace = coreNamespace,
                extraImports = extraImports,
            )
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

private fun collectMacroRuleItems(file: File): LinkedHashMap<String, MutableList<Item.Macro>> {
    val grouped = linkedMapOf<String, MutableList<Item.Macro>>()
    collectMacroRuleItemsInto(file.items, grouped)
    return grouped
}

private fun collectMacroRuleItemsInto(
    items: List<Item>,
    grouped: LinkedHashMap<String, MutableList<Item.Macro>>,
) {
    for (item in items) {
        if (isMacroItemDocOnly(item)) continue
        when (item) {
            is Item.Macro -> {
                if (item.mac.path.isIdent("macro_rules")) {
                    val rawName = item.ident?.toString()?.removePrefix("r#")
                    if (!rawName.isNullOrEmpty()) {
                        grouped.getOrPut(rawName) { mutableListOf() }.add(item)
                    }
                }
            }
            is Item.Mod -> {
                val content = item.content
                if (content is ModContent.Inline) {
                    collectMacroRuleItemsInto(content.items, grouped)
                }
            }
            else -> Unit
        }
    }
}

private fun isMacroItemDocOnly(item: Item): Boolean =
    when (item) {
        is Item.Macro -> isDocOnlyCfgAttribute(item.attrs)
        is Item.Mod -> isDocOnlyCfgAttribute(item.attrs)
        else -> false
    }

private fun hasMacroExportAttr(attrs: List<Attribute>): Boolean =
    attrs.any { it.path().isIdent("macro_export") }

private fun parseItemCfgAttributes(
    attrs: List<Attribute>,
    isPublic: Boolean,
    cfgConstants: LinkedHashMap<String, Pair<Boolean, Boolean>>,
): CfgConditionAst? {
    val conditions = mutableListOf<CfgConditionAst>()
    for (attr in attrs) {
        val meta = attr.meta
        if (meta is Meta.List && meta.path.isIdent("cfg")) {
            val parsed = parseCfgTokens(meta.tokens.clone().toList(), isPublic, cfgConstants)
            if (parsed != null) {
                conditions += parsed
            }
        }
    }
    return when (conditions.size) {
        0 -> null
        1 -> conditions[0]
        else -> CfgConditionAst.All(conditions)
    }
}

private fun parseCfgTokens(
    tokens: List<TokenTree>,
    isPublic: Boolean,
    cfgConstants: LinkedHashMap<String, Pair<Boolean, Boolean>>,
): CfgConditionAst? {
    if (tokens.isEmpty()) return null
    val firstIdent = (tokens[0] as? TokenTree.Ident)?.value?.toString()?.removePrefix("r#")
    if (firstIdent != null && tokens.size == 2) {
        val group = tokens[1] as? TokenTree.Group
        if (group != null && group.value.delimiter() == Delimiter.Parenthesis) {
            val groupStream = group.value.stream()
            val groupTokens = groupStream.clone().toList()
            val clauses =
                MacroRulesFileExpander
                    .splitTopLevelCommaClauses(groupTokens)
                    .mapNotNull { parseCfgTokens(it, isPublic, cfgConstants) }
            return when (firstIdent) {
                "not" -> clauses.firstOrNull()?.let { CfgConditionAst.Not(it) }
                "all" -> clauses.singleOrNull() ?: clauses.takeIf { it.isNotEmpty() }?.let { CfgConditionAst.All(it) }
                "any" -> clauses.singleOrNull() ?: clauses.takeIf { it.isNotEmpty() }?.let { CfgConditionAst.AnyOf(it) }
                else ->
                    registerCfgLeaf(
                        "cfg_${firstIdent}_${joinCfgIdentParts(groupTokens)}",
                        true,
                        isPublic,
                        cfgConstants,
                    )
            }
        }
    }
    if (firstIdent != null && tokens.size >= 3 && (tokens[1] as? TokenTree.Punct)?.value?.asChar() == '=') {
        val rhsRaw = extractCfgLiteralValue(tokens.subList(2, tokens.size))
        val constName = rustConstIdentToKotlin("cfg_${firstIdent}_$rhsRaw")
        val defaultVal = firstIdent != "doc" && firstIdent != "docsrs"
        return registerCfgLeaf(constName, defaultVal, isPublic, cfgConstants)
    }
    if (firstIdent != null && tokens.size == 1) {
        val constName = rustConstIdentToKotlin("cfg_$firstIdent")
        val defaultVal = firstIdent != "doc" && firstIdent != "docsrs" && firstIdent != "test"
        return registerCfgLeaf(constName, defaultVal, isPublic, cfgConstants)
    }
    val fallback = joinCfgIdentParts(tokens)
    if (fallback.isEmpty()) return null
    return registerCfgLeaf(rustConstIdentToKotlin("cfg_$fallback"), true, isPublic, cfgConstants)
}

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

private fun registerCfgLeaf(
    rawConstName: String,
    defaultValue: Boolean,
    isPublic: Boolean,
    cfgConstants: LinkedHashMap<String, Pair<Boolean, Boolean>>,
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
                val strVal = tt.value.strValue().value
                val raw = strVal ?: tt.value.toString().trim('"')
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

private fun renderCfgConditionExpr(cond: CfgConditionAst, parenthesizeBinary: Boolean = false): String =
    when (cond) {
        is CfgConditionAst.Flag -> cond.constName
        is CfgConditionAst.Not -> {
            val inner = renderCfgConditionExpr(cond.inner, parenthesizeBinary = true)
            "!$inner"
        }
        is CfgConditionAst.All -> {
            val joined = cond.items.joinToString(" && ") { renderCfgConditionExpr(it, parenthesizeBinary = true) }
            if (parenthesizeBinary && cond.items.size > 1) "($joined)" else joined
        }
        is CfgConditionAst.AnyOf -> {
            val joined = cond.items.joinToString(" || ") { renderCfgConditionExpr(it, parenthesizeBinary = true) }
            if (parenthesizeBinary && cond.items.size > 1) "($joined)" else joined
        }
    }

private fun isNegationOf(candidate: CfgConditionAst?, original: CfgConditionAst?): Boolean =
    candidate is CfgConditionAst.Not && original != null && candidate.inner == original

private fun renderMacroGroupApproximation(
    rawMacroName: String,
    variants: List<MacroVariantAst>,
    isPublic: Boolean,
    includeDocExampleAnnotations: Boolean,
    occupiedNames: MutableSet<String>,
    templateConstants: MutableList<String>,
    rawAbiMode: Boolean = false,
    coreNamespace: String = CoreIntrinsicLowering.DEFAULT_CORE_NAMESPACE,
    extraImports: MutableSet<String> = linkedSetOf(),
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

    val constName = macroHelperConstIdentifier(rawMacroName)
    val fnName = rustFunctionIdentToKotlin(rawMacroName)

    if (variants.size == 1 && variants[0].cfgCondition == null) {
        val branches = variants[0].branches
        if (isRawPointerLiteralMacroBranches(branches) && fnName !in occupiedNames) {
            val rawParam = branches[0].paramNames.firstOrNull()?.removePrefix("r#") ?: "lit"
            val ktParam = rustValueIdentToKotlin(rawParam).ifEmpty { "lit" }
            val bodyText = branches.joinToString(" ") { it.bodyTokens.toString() }
            val isWide = rawMacroName == "w" || "u16" in bodyText || "utf16" in bodyText
            val internFn = if (isWide) "kmInternWideStr" else "kmInternCStr"
            extraImports += "import $coreNamespace.intrinsics.$internFn"
            occupiedNames += fnName
            return if (rawAbiMode) {
                "${kdocPrefix}$vis fun $fnName($ktParam: String): Long = $internFn($ktParam).rawAddress"
            } else {
                extraImports += "import $coreNamespace.ptr.ConstPtr"
                "${kdocPrefix}$vis fun $fnName($ktParam: String): ConstPtr = $internFn($ktParam)"
            }
        }
        if (branches.size == 1 && branches[0].pattern.isEmpty() && !branches[0].isRepetition) {
            if (constName !in occupiedNames) {
                val scalar = extractScalarLiteralFromMacroBody(branches[0].bodyTokens)
                if (branches[0].bodyTokens.isEmpty()) {
                    occupiedNames += constName
                    return "${kdocPrefix}$vis const val $constName: String = \"\""
                }
                if (scalar != null) {
                    occupiedNames += constName
                    return "${kdocPrefix}$vis const val $constName: ${scalar.kotlinType} = ${scalar.renderedExpr}"
                }
            }
        }
        val scalarTable = tryRenderScalarConditionalMacro(fnName, vis, kdocPrefix, branches, occupiedNames)
        if (scalarTable != null) {
            return scalarTable
        }
    }

    return renderTemplateConditionalMacro(
        rawMacroName = rawMacroName,
        fnName = fnName,
        vis = vis,
        kdocPrefix = kdocPrefix,
        variants = variants,
        occupiedNames = occupiedNames,
        templateConstants = templateConstants,
    )
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
        is Expr.Unary -> {
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
                val longVal = digits.toLongOrNull()
                longVal?.let { ScalarMacroLiteral("Long", "${it}L") }
            }
        }
        is Lit.Float -> {
            val digits = lit.value.base10Digits()
            digits.toDoubleOrNull()?.let { ScalarMacroLiteral("Double", digits) }
        }
        is Lit.Str -> ScalarMacroLiteral("String", quoteKotlinString(lit.value.value()))
        is Lit.Char -> {
            val cp = lit.value.value()
            if (cp in 0..0xFFFF) {
                ScalarMacroLiteral("Char", quoteKotlinChar(cp.toChar()))
            } else {
                null
            }
        }
        else -> null
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

private fun tryRenderScalarConditionalMacro(
    fnName: String,
    vis: String,
    kdocPrefix: String,
    branches: List<MacroRuleBranch>,
    occupiedNames: MutableSet<String>,
): String? {
    if (branches.isEmpty() || fnName in occupiedNames) return null
    val branchScalars = branches.map { extractScalarLiteralFromMacroBody(it.bodyTokens) ?: return null }
    val scalarTypes = branchScalars.map { it.kotlinType }.distinct()
    if (scalarTypes.size != 1) return null
    val returnType = scalarTypes[0]

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
            val clause = clauses[slotIdx]
            val literalMatch = extractPureLiteralClauseText(clause)
            if (literalMatch != null) {
                condParts += "${paramNames[slotIdx]} == ${quoteKotlinString(literalMatch)}"
            }
        }
        if (condParts.isEmpty()) {
            if (fallbackExpr == null) {
                fallbackExpr = scalar.renderedExpr
            }
        } else {
            val joinedCond = condParts.joinToString(" && ")
            whenArms += "        $joinedCond -> ${scalar.renderedExpr}"
        }
    }

    val defaultScalar = fallbackExpr ?: defaultInitializerForType(returnType)
    val usedParams =
        paramNames.filter { p ->
            whenArms.any { containsIdentifierToken(it, p) }
        }
    val unusedGuard =
        paramNames
            .filter { it !in usedParams }
            .joinToString(" && ") { "$it.isEmpty()" }

    if (unusedGuard.isNotEmpty() && whenArms.isEmpty()) {
        occupiedNames += fnName
        val sigParams = paramNames.joinToString(", ") { "$it: String = \"\"" }
        return buildString {
            append(kdocPrefix)
            append("$vis fun $fnName($sigParams): $returnType =\n")
            append("    if ($unusedGuard) $defaultScalar else $defaultScalar")
        }
    }

    occupiedNames += fnName
    val sigParams = paramNames.joinToString(", ") { "$it: String = \"\"" }
    return buildString {
        append(kdocPrefix)
        append("$vis fun $fnName($sigParams): $returnType =\n")
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

private fun deriveSlotParameterNames(
    splitPatterns: List<List<List<MacroPat>>>,
    arity: Int,
): List<String> {
    val used = linkedSetOf<String>()
    val result = mutableListOf<String>()
    for (slot in 0 until arity) {
        var candidate: String? = null
        for (clauses in splitPatterns) {
            if (slot < clauses.size) {
                val mv = findFirstMetavarName(clauses[slot])
                if (mv != null) {
                    candidate = rustValueIdentToKotlin(mv)
                    break
                }
            }
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

private fun renderTemplateConditionalMacro(
    rawMacroName: String,
    fnName: String,
    vis: String,
    kdocPrefix: String,
    variants: List<MacroVariantAst>,
    occupiedNames: MutableSet<String>,
    templateConstants: MutableList<String>,
): String? {
    val effectiveFnName = if (fnName in occupiedNames) "${fnName}Macro" else fnName
    occupiedNames += effectiveFnName

    val allMetavars = linkedMapOf<String, String>()
    for (variant in variants) {
        for (branch in variant.branches) {
            for (rawParam in branch.paramNames) {
                val cleaned = rawParam.removePrefix("r#")
                if (cleaned.isNotEmpty() && cleaned !in allMetavars) {
                    var ktParam = rustValueIdentToKotlin(cleaned)
                    if (ktParam == "_" || ktParam.isEmpty()) ktParam = "value"
                    if (ktParam == "clause" || ktParam in allMetavars.values) {
                        ktParam = "${ktParam}Arg"
                    }
                    allMetavars[cleaned] = ktParam
                }
            }
        }
    }

    val needsClauseSelector =
        variants.any { v ->
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

    val macroConstPrefix = macroHelperConstIdentifier(rawMacroName)
    var templateCounter = 0

    fun renderVariantBodyExpr(variant: MacroVariantAst, indent: String): String {
        val branches = variant.branches
        if (branches.isEmpty()) {
            return "\"\""
        }
        if (branches.size == 1) {
            return renderSingleTemplateBranchExpr(
                branch = branches[0],
                macroConstPrefix = macroConstPrefix,
                templateIndex = templateCounter++,
                allMetavars = allMetavars,
                occupiedNames = occupiedNames,
                templateConstants = templateConstants,
            )
        }
        val armPairs = mutableListOf<Pair<String, String>>()
        val seenConditions = linkedSetOf<String>()
        var fallbackArmExpr: String? = null
        for (branch in branches) {
            val cond = buildTemplateBranchCondition(branch, allMetavars, needsClauseSelector)
            if (cond == null) {
                if (fallbackArmExpr == null) {
                    fallbackArmExpr =
                        renderSingleTemplateBranchExpr(
                            branch = branch,
                            macroConstPrefix = macroConstPrefix,
                            templateIndex = templateCounter++,
                            allMetavars = allMetavars,
                            occupiedNames = occupiedNames,
                            templateConstants = templateConstants,
                        )
                }
            } else if (seenConditions.add(cond)) {
                val branchExpr =
                    renderSingleTemplateBranchExpr(
                        branch = branch,
                        macroConstPrefix = macroConstPrefix,
                        templateIndex = templateCounter++,
                        allMetavars = allMetavars,
                        occupiedNames = occupiedNames,
                        templateConstants = templateConstants,
                    )
                armPairs += cond to branchExpr
            }
        }
        val finalElse = fallbackArmExpr ?: "\"\""
        if (armPairs.isEmpty()) {
            return finalElse
        }
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

    val bodyExpr =
        if (variants.size == 1) {
            val single = variants[0]
            val inner = renderVariantBodyExpr(single, indent = "    ")
            val cfg = single.cfgCondition
            if (cfg == null) {
                inner
            } else {
                val cfgText = renderCfgConditionExpr(cfg)
                "if ($cfgText) {\n        ${inner.replace("\n    ", "\n        ")}\n    } else {\n        \"\"\n    }"
            }
        } else if (variants.size == 2 && isNegationOf(variants[1].cfgCondition, variants[0].cfgCondition)) {
            val condText = renderCfgConditionExpr(variants[0].cfgCondition!!)
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
                        append("        ${renderCfgConditionExpr(cfg)} -> $vExpr\n")
                    }
                }
                append("        else -> ${elseExpr ?: "\"\""}\n")
                append("    }")
            }
        }

    val unusedParams =
        buildList {
            for (ktParam in allMetavars.values) {
                if (!containsIdentifierToken(bodyExpr, ktParam)) {
                    add(ktParam)
                }
            }
            if (needsClauseSelector && !containsIdentifierToken(bodyExpr, "clause")) {
                add("clause")
            }
        }

    val finalBodyExpr =
        if (unusedParams.isEmpty()) {
            bodyExpr
        } else {
            val touchCond = unusedParams.joinToString(" && ") { "$it.isEmpty()" }
            "if ($touchCond) {\n        ${bodyExpr.replace("\n    ", "\n        ")}\n    } else {\n        ${bodyExpr.replace("\n    ", "\n        ")}\n    }"
        }

    return buildString {
        append(kdocPrefix)
        append("$vis fun $effectiveFnName(${paramDecls.joinToString(", ")}): String =\n")
        append("    $finalBodyExpr")
    }
}

private fun renderSingleTemplateBranchExpr(
    branch: MacroRuleBranch,
    macroConstPrefix: String,
    templateIndex: Int,
    allMetavars: Map<String, String>,
    occupiedNames: MutableSet<String>,
    templateConstants: MutableList<String>,
): String {
    if (branch.bodyTokens.isEmpty()) {
        return "\"\""
    }
    val templateText = formatMacroBodyTemplate(branch.bodyTokens, allMetavars)
    if (templateText.isEmpty()) {
        return "\"\""
    }
    var constName =
        if (templateIndex == 0) {
            "${macroConstPrefix}_TEMPLATE"
        } else {
            "${macroConstPrefix}_TEMPLATE_$templateIndex"
        }
    while (constName in occupiedNames) {
        constName = "${constName}_ALT"
    }
    occupiedNames += constName
    templateConstants += "private const val $constName: String = ${quoteKotlinString(templateText)}"

    val usedKtParams =
        branch.paramNames
            .map { it.removePrefix("r#") }
            .distinct()
            .mapNotNull { raw -> allMetavars[raw] }
            .distinct()
            .filter { ktParam -> templateText.contains("\$$ktParam") }
    if (usedKtParams.isEmpty()) {
        return constName
    }
    if (usedKtParams.size == 1) {
        val ktParam = usedKtParams[0]
        return "$constName.replace(${quoteKotlinString("\$$ktParam")}, $ktParam)"
    }
    val pairs = usedKtParams.joinToString(", ") { ktParam -> "${quoteKotlinString("\$$ktParam")} to $ktParam" }
    return "arrayOf($pairs).fold($constName) { acc0, (k0, v0) -> acc0.replace(k0, v0) }"
}

private fun buildTemplateBranchCondition(
    branch: MacroRuleBranch,
    allMetavars: Map<String, String>,
    hasClauseSelector: Boolean,
): String? {
    val conds = mutableListOf<String>()
    val literals = extractPatternDistinguishingLiterals(branch.pattern)
    if (hasClauseSelector && literals.isNotEmpty()) {
        conds += "clause == ${quoteKotlinString(literals.joinToString(" "))}"
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

private val IGNORED_STRUCTURAL_KEYWORDS: Set<String> =
    setOf("fn", "let")

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

private fun formatMacroBodyTemplate(stream: TokenStream, allMetavars: Map<String, String>): String {
    val sb = StringBuilder()
    appendFormattedMacroTokens(stream.clone().toList(), allMetavars, sb)
    return sb.toString().trim()
}

private fun appendFormattedMacroTokens(
    trees: List<TokenTree>,
    allMetavars: Map<String, String>,
    sb: StringBuilder,
) {
    var i = 0
    while (i < trees.size) {
        val tt = trees[i]
        // Skip outer `#[...]` and inner `#![...]` Rust attributes in macro templates.
        if (tt is TokenTree.Punct && tt.value.asChar() == '#') {
            if (i + 1 < trees.size &&
                (trees[i + 1] as? TokenTree.Group)?.value?.delimiter() == Delimiter.Bracket
            ) {
                i += 2
                continue
            }
            if (i + 2 < trees.size &&
                (trees[i + 1] as? TokenTree.Punct)?.value?.asChar() == '!' &&
                (trees[i + 2] as? TokenTree.Group)?.value?.delimiter() == Delimiter.Bracket
            ) {
                i += 3
                continue
            }
        }
        // Skip Rust lifetime tokens (`'a`, `'static`, `'_`).
        if (tt is TokenTree.Punct &&
            tt.value.asChar() == '\'' &&
            tt.value.spacing() == Spacing.Joint &&
            i + 1 < trees.size &&
            trees[i + 1] is TokenTree.Ident
        ) {
            i += 2
            continue
        }
        if (tt is TokenTree.Punct && tt.value.asChar() == '&') {
            if (i + 1 < trees.size && (trees[i + 1] as? TokenTree.Punct)?.value?.asChar() == '$') {
                i += 1
                continue
            }
            if (i + 2 < trees.size &&
                (trees[i + 1] as? TokenTree.Ident)?.value?.toString() == "mut" &&
                (trees[i + 2] as? TokenTree.Punct)?.value?.asChar() == '$'
            ) {
                i += 2
                continue
            }
        }
        if (tt is TokenTree.Punct && tt.value.asChar() == '$' && i + 1 < trees.size) {
            val next = trees[i + 1]
            if (next is TokenTree.Ident && next.value.toString() == "crate") {
                i += 2
                while (i + 1 < trees.size &&
                    (trees[i] as? TokenTree.Punct)?.value?.asChar() == ':' &&
                    (trees[i + 1] as? TokenTree.Punct)?.value?.asChar() == ':'
                ) {
                    i += 2
                    if (i < trees.size &&
                        (trees[i] as? TokenTree.Ident)?.value?.toString() == "__private"
                    ) {
                        i++
                    } else {
                        break
                    }
                }
                continue
            }
            if (next is TokenTree.Ident) {
                if (sb.isNotEmpty() && sb.last() != ' ' && sb.last() != '(' && sb.last() != '[' && sb.last() != '{') {
                    sb.append(' ')
                }
                val raw = next.value.toString().removePrefix("r#")
                val ktParam = allMetavars[raw] ?: rustValueIdentToKotlin(raw)
                sb.append('$').append(ktParam)
                i += 2
                continue
            }
        }
        if (tt is TokenTree.Punct &&
            tt.value.asChar() == ':' &&
            tt.value.spacing() == Spacing.Joint &&
            i + 1 < trees.size &&
            (trees[i + 1] as? TokenTree.Punct)?.value?.asChar() == ':'
        ) {
            sb.append('.')
            i += 2
            continue
        }
        when (tt) {
            is TokenTree.Ident -> {
                val word = translateMacroTemplateIdent(tt.value.toString())
                if (sb.isNotEmpty() && !sb.endsWith('.') && !sb.endsWith('(') && !sb.endsWith('[') && !sb.endsWith('{') && !sb.endsWith(' ')) {
                    sb.append(' ')
                }
                sb.append(word)
                if (i + 1 < trees.size && (trees[i + 1] as? TokenTree.Punct)?.value?.asChar() == '!') {
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
                appendFormattedMacroTokens(groupStream.clone().toList(), allMetavars, sb)
                sb.append(close)
            }
        }
        i++
    }
}

private fun quoteKotlinString(value: String): String =
    buildString(value.length + 2) {
        append('"')
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '$' -> append("\\$")
                else -> append(ch)
            }
        }
        append('"')
    }

private fun quoteKotlinChar(value: Char): String =
    when (value) {
        '\\' -> "'\\\\'"
        '\'' -> "'\\''"
        '\n' -> "'\\n'"
        '\r' -> "'\\r'"
        '\t' -> "'\\t'"
        else -> "'$value'"
    }

private fun containsIdentifierToken(source: String, ident: String): Boolean {
    if (ident.isEmpty()) return false
    var idx = source.indexOf(ident)
    while (idx >= 0) {
        val beforeOk = idx == 0 || (!source[idx - 1].isLetterOrDigit() && source[idx - 1] != '_')
        val afterIdx = idx + ident.length
        val afterOk = afterIdx >= source.length || (!source[afterIdx].isLetterOrDigit() && source[afterIdx] != '_')
        if (beforeOk && afterOk) return true
        idx = source.indexOf(ident, idx + 1)
    }
    return false
}
