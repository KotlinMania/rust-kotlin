package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.procmacro2.Delimiter
import io.github.kotlinmania.procmacro2.Group
import io.github.kotlinmania.procmacro2.Literal
import io.github.kotlinmania.procmacro2.Spacing
import io.github.kotlinmania.procmacro2.Span
import io.github.kotlinmania.procmacro2.TokenStream
import io.github.kotlinmania.procmacro2.TokenTree
import io.github.kotlinmania.quote.ToTokens
import io.github.kotlinmania.syn.Attribute
import io.github.kotlinmania.syn.Block
import io.github.kotlinmania.syn.Expr
import io.github.kotlinmania.syn.ExprParse
import io.github.kotlinmania.syn.File
import io.github.kotlinmania.syn.FnArg
import io.github.kotlinmania.syn.ImplItem
import io.github.kotlinmania.syn.Item
import io.github.kotlinmania.syn.Macro
import io.github.kotlinmania.syn.Meta
import io.github.kotlinmania.syn.ModContent
import io.github.kotlinmania.syn.Pat
import io.github.kotlinmania.syn.Signature
import io.github.kotlinmania.syn.Stmt
import io.github.kotlinmania.syn.SynType
import io.github.kotlinmania.syn.gen.Fold
import io.github.kotlinmania.syn.gen.Visit
import io.github.kotlinmania.syn.parse2
import io.github.kotlinmania.procmacro2.Ident as PmIdent

internal sealed class MacroPat {
    data class LiteralToken(
        val text: String,
        val isPunct: Boolean,
    ) : MacroPat()

    data class Metavar(
        val name: String,
        val fragSpec: String,
    ) : MacroPat()

    data class GroupPat(
        val delimiter: Delimiter,
        val inner: List<MacroPat>,
    ) : MacroPat()

    data class Repetition(
        val inner: List<MacroPat>,
        val sep: LiteralToken?,
        val op: Char,
    ) : MacroPat()

    companion object {
        fun collectMetavars(
            pats: List<MacroPat>,
            includeRepetitions: Boolean,
            out: MutableCollection<String>,
        ) {
            for (p in pats) {
                when (p) {
                    is Metavar -> out.add(p.name)
                    is GroupPat -> collectMetavars(p.inner, includeRepetitions, out)
                    is Repetition -> if (includeRepetitions) collectMetavars(p.inner, includeRepetitions = true, out)
                    is LiteralToken -> {}
                }
            }
        }

        fun collectMetavarSet(pats: List<MacroPat>, includeRepetitions: Boolean): Set<String> =
            linkedSetOf<String>().also { collectMetavars(pats, includeRepetitions, it) }

        fun collectMetavarList(pats: List<MacroPat>): List<String> =
            mutableListOf<String>().also { collectMetavars(pats, includeRepetitions = true, it) }

        fun hasTtMetavar(pats: List<MacroPat>): Boolean =
            pats.any { p ->
                when (p) {
                    is Metavar -> p.fragSpec == "tt"
                    is GroupPat -> hasTtMetavar(p.inner)
                    is Repetition -> hasTtMetavar(p.inner)
                    is LiteralToken -> false
                }
            }

        fun tokenMatchesLiteral(tt: TokenTree, pat: LiteralToken): Boolean =
            when (tt) {
                is TokenTree.Punct -> pat.isPunct && tt.value.asChar().toString() == pat.text
                is TokenTree.Ident -> !pat.isPunct && tt.value.toString() == pat.text
                is TokenTree.Literal -> !pat.isPunct && tt.value.toString() == pat.text
                is TokenTree.Group -> {
                    val grp = tt.value
                    if (grp.delimiter() != Delimiter.None) {
                        false
                    } else {
                        val single = grp.stream().toList().singleOrNull()
                        single != null && tokenMatchesLiteral(single, pat)
                    }
                }
            }
    }
}

internal data class RepetitionGroup(
    val directVars: Set<String>,
    val allVars: Set<String>,
    val iterations: List<MacroMatchEnv>,
)

internal data class MacroMatchEnv(
    val singles: MutableMap<String, List<TokenTree>> = linkedMapOf(),
    val repetitions: MutableList<MacroMatchEnv> = mutableListOf(),
    val repetitionGroups: MutableList<RepetitionGroup> = mutableListOf(),
    val matchedKeywordSpans: MutableMap<String, Span> = linkedMapOf(),
)

/**
 * Represents a parsed `macro_rules! name { ($param:frag, ...) => { body }; ... }` branch
 * extracted via our `syn.gen.Visit` subclass [MacroRulesCollector].
 */
internal data class MacroRuleBranch(
    val pattern: List<MacroPat>,
    val paramNames: List<String>,
    val isRepetition: Boolean,
    val bodyTokens: TokenStream,
)

internal data class MacroRulesDef(
    val name: String,
    val branches: List<MacroRuleBranch>,
)

internal fun isDocOnlyCfgAttribute(attrs: List<Attribute>): Boolean {
    for (attr in attrs) {
        // Rust's test attribute itself makes a function test-only, even
        // without a surrounding cfg(test) module.
        val profile = TranslationSession.cfgTargetProfile
        if (attr.path().isIdent("test") && "test" in profile.disabledCfgFlags && "test" !in profile.enabledCfgFlags) {
            return true
        }
        val meta = attr.meta
        if (meta is Meta.List && meta.path.isIdent("cfg")) {
            if (isDocOnlyCfgPredicateTokens(meta.tokens.clone().toList())) {
                return true
            }
        }
    }
    return false
}

internal fun isDocOnlyCfgPredicateTokens(tokens: List<TokenTree>): Boolean =
    evalCfgTriState(tokens, forRuntimeCfgMacro = false) == false

internal fun evalCfgExpressionTokens(tokens: List<TokenTree>): Boolean =
    evalCfgTriState(tokens, forRuntimeCfgMacro = true) ?: false

internal fun cfgAttrPreferenceScore(attrs: List<Attribute>): Int {
    val cfgPredicates =
        attrs.mapNotNull { attr ->
            val meta = attr.meta
            if (meta is Meta.List && meta.path.isIdent("cfg")) meta.tokens.clone().toList() else null
        }
    if (cfgPredicates.isEmpty()) return 1
    if (cfgPredicates.all { evalCfgTriState(it, forRuntimeCfgMacro = false) == true }) return 3
    if (cfgPredicates.all { evalCfgExpressionTokens(it) }) return 2
    return 0
}

private val UNSUPPORTED_RUST_TARGET_OS =
    wordSet(
        "hermit motor solid_asp3 teeos uefi vexos xous zkvm l4re nuttx psp psx trusty vita vxworks",
        "espidf horizon hurd cygwin aix haiku illumos netbsd openbsd dragonfly freebsd solaris redox",
        "fuchsia nto rtems emscripten wasi_p3 lynxos178 qurt cuda nvptx amdgpu helenos freebsdlike",
        "netbsdlike solarish managarm zos",
    )

private val UNSUPPORTED_RUST_TARGET_ENV = wordSet("sgx relibc ohos uclibc newlib p2 p3")

private val UNSUPPORTED_RUST_TARGET_VENDOR = wordSet("fortanix kmc nintendo sony uwp win7")

private val PRUNED_UNSUPPORTED_TARGET_PATH_SEGMENTS =
    UNSUPPORTED_RUST_TARGET_OS +
        UNSUPPORTED_RUST_TARGET_ENV +
        UNSUPPORTED_RUST_TARGET_VENDOR +
        wordSet("fortanix_sgx solid itron wasip2 wasip3 wali miri switch")

internal fun isUnsupportedTargetRustRelPath(
    relPath: String,
    cfgProfile: CfgTargetProfile,
): Boolean {
    val norm = relPath.replace('\\', '/').trimStart('/').trimEnd('/')
    if (norm.isEmpty()) return false
    val withoutExt = norm.substringBeforeLast('.')
    val segs = withoutExt.split('/').filter { it.isNotEmpty() }
    return segs.any { rawSeg ->
        val seg = rawSeg.lowercase()
        seg in PRUNED_UNSUPPORTED_TARGET_PATH_SEGMENTS &&
            seg != cfgProfile.targetOs?.lowercase() &&
            seg != cfgProfile.targetEnv?.lowercase() &&
            seg != cfgProfile.targetVendor?.lowercase()
    }
}

internal fun partitionCfgSelectBranchBodies(innerTrees: List<TokenTree>): List<Pair<List<TokenTree>, Boolean>>? =
    parseCfgSelectArms(innerTrees)?.let(::partitionCfgBranchArms)

private fun chooseSingleActiveCfgSelectBody(innerTrees: List<TokenTree>): List<TokenTree>? {
    val arms = parseCfgSelectArms(innerTrees) ?: return null
    var firstNonFalse: CfgBranchArm? = null
    for (arm in arms) {
        val pred = arm.predicateTokens ?: return firstNonFalse?.bodyTokens ?: arm.bodyTokens
        when (evalCfgTriState(pred, forRuntimeCfgMacro = false)) {
            true -> return arm.bodyTokens
            false -> Unit
            null -> if (firstNonFalse == null) firstNonFalse = arm
        }
    }
    return firstNonFalse?.bodyTokens
}

private fun itemDeclarationKey(item: Item): String? =
    when (item) {
        is Item.Fn -> "fn:${item.sig.ident}"
        is Item.Struct -> "struct:${item.ident}"
        is Item.Enum -> "enum:${item.ident}"
        is Item.Union -> "union:${item.ident}"
        is Item.Trait -> "trait:${item.ident}"
        is Item.ItemType -> "type:${item.ident}"
        is Item.Const -> "const:${item.ident}"
        is Item.Static -> "static:${item.ident}"
        is Item.Mod -> "mod:${item.ident}"
        else -> null
    }

private fun implItemDeclarationKey(item: ImplItem): String? =
    when (item) {
        is ImplItem.Fn -> "fn:${item.sig.ident}"
        is ImplItem.Const -> "const:${item.ident}"
        is ImplItem.AssocType -> "type:${item.ident}"
        else -> null
    }

private object TokenBodyCfgSelector : CfgArmSelector<List<TokenTree>>() {
    override fun parseArmBody(bodyTokens: List<TokenTree>): List<List<TokenTree>> = listOf(bodyTokens)

    override fun extractArmKeys(parsed: List<List<TokenTree>>): List<String>? =
        SynAstFragmentParser.parseNormalizedFile(TokenStream.fromTokenTrees(parsed.single()))?.items?.mapNotNull(::itemDeclarationKey)

    override fun shouldSkipWildcardArm(armKeys: List<String>, seenKeys: Set<String>): Boolean =
        ("mod:unsupported" in armKeys && seenKeys.any { it.startsWith("mod:") }) ||
            (armKeys.isNotEmpty() && armKeys.all { it in seenKeys })
}

private object ImplItemCfgSelector : CfgArmSelector<ImplItem>() {
    override fun parseArmBody(bodyTokens: List<TokenTree>): List<ImplItem>? =
        SynAstFragmentParser.parseNormalizedImplItems(TokenStream.fromTokenTrees(bodyTokens))

    override fun extractArmKeys(parsed: List<ImplItem>): List<String> =
        parsed.mapNotNull(::implItemDeclarationKey)
}

internal fun selectActiveCfgSelectBodies(innerTrees: List<TokenTree>): List<List<TokenTree>>? =
    parseCfgSelectArms(innerTrees)?.let(TokenBodyCfgSelector::selectActive)

private fun selectActiveCfgSelectImplItems(innerTrees: List<TokenTree>): List<ImplItem>? =
    parseCfgSelectArms(innerTrees)?.let(ImplItemCfgSelector::selectActive)

private val ARCHS_16_OR_32_BIT =
    wordSet("avr msp430 arm x86 mips mips32r6 powerpc riscv32 sparc csky hexagon m68k xtensa wasm32")

private val ARCHS_64_BIT =
    wordSet("x86_64 aarch64 mips64 mips64r6 powerpc64 riscv64 s390x sparc64 loongarch64 wasm64")

private fun dirContainsSegment(relDir: String?, target: String): Boolean {
    if (relDir.isNullOrEmpty()) return false
    return relDir.split('/').any { seg ->
        seg.equals(target, ignoreCase = true) || seg.replace('-', '_').equals(target, ignoreCase = true)
    }
}

private fun resolveUnknownCfgOutcome(profile: CfgTargetProfile, forRuntimeCfgMacro: Boolean): Boolean? =
    when (profile.unknownPolicy) {
        UnknownCfgPolicy.OPEN_WORLD_NULL -> if (forRuntimeCfgMacro) false else null
        UnknownCfgPolicy.CLOSED_WORLD_FALSE -> false
        UnknownCfgPolicy.PERMISSIVE_TRUE -> true
    }

internal fun evalCfgTriState(tokens: List<TokenTree>, forRuntimeCfgMacro: Boolean): Boolean? =
    CfgPredicateTokenEvaluator(tokens, forRuntimeCfgMacro).eval()

private class CfgPredicateTokenEvaluator(
    trees: List<TokenTree>,
    private val forRuntimeCfgMacro: Boolean,
) : RustTokenCursor(trees) {
    private val profile: CfgTargetProfile = TranslationSession.cfgTargetProfile
    private val relDir: String? =
        if (profile.useRelativeDirPlatformHints) TranslationSession.currentRelativeSourceDir else null

    fun eval(): Boolean? {
        if (size == 2) {
            val fnIdent = cleanIdentAt(0)
            val grp = groupAt(1, Delimiter.Parenthesis)
            if (fnIdent != null && grp != null) {
                val innerTrees = grp.stream().clone().toList()
                val clauses = RustTokenCursor(innerTrees).splitByTopLevelPunct(',')
                when (fnIdent) {
                    "all" -> {
                        var allTrue = true
                        for (clause in clauses) {
                            val sub = CfgPredicateTokenEvaluator(clause, forRuntimeCfgMacro).eval()
                            if (sub == false) return false
                            if (sub != true) allTrue = false
                        }
                        return if (allTrue) true else null
                    }
                    "any" -> {
                        var allFalse = true
                        for (clause in clauses) {
                            val sub = CfgPredicateTokenEvaluator(clause, forRuntimeCfgMacro).eval()
                            if (sub == true) return true
                            if (sub != false) allFalse = false
                        }
                        return if (allFalse) false else null
                    }
                    "not" -> {
                        if (clauses.size == 1) {
                            return CfgPredicateTokenEvaluator(clauses[0], forRuntimeCfgMacro).eval()?.not()
                        }
                    }
                }
            }
        }
        if (size >= 3 && punctCharAt(size - 2) == '=') {
            val litToken = trees[size - 1] as? TokenTree.Literal
            val key = if (litToken != null) extractPredicateKey(size - 2) else null
            val litText = litToken?.let(::unquoteCfgStringLiteral)
            if (key != null && litText != null) {
                return evalKeyValuePredicate(key, litText)
            }
        }
        val ident = extractPredicateKey(size) ?: return null
        if (ident == "true") return true
        if (ident == "false") return false
        if (ident in profile.enabledCfgFlags) return true
        if (ident in profile.disabledCfgFlags) return false
        if (ident in ALWAYS_FALSE_CFG_FLAGS) return false
        if (ident == "test") return if (forRuntimeCfgMacro) false else null
        if (ident == "unix" || ident == "windows" || ident == "wasm") {
            profile.targetFamilies?.let { return ident in it }
            if (dirContainsSegment(relDir, ident)) return true
        }
        return resolveUnknownCfgOutcome(profile, forRuntimeCfgMacro)
    }

    private fun evalKeyValuePredicate(key: String, litText: String): Boolean? =
        when (key) {
            "target_pointer_width" -> litText.toIntOrNull()?.let { it == profile.pointerWidth }
            "target_endian" -> litText == profile.targetEndian
            "target_arch" ->
                when {
                    profile.targetArch != null -> litText == profile.targetArch
                    dirContainsSegment(relDir, litText) -> true
                    profile.pointerWidth == 64 && litText in ARCHS_16_OR_32_BIT -> false
                    profile.pointerWidth in POINTER_WIDTHS_16_OR_32 && litText in ARCHS_64_BIT -> false
                    else -> resolveUnknownCfgOutcome(profile, forRuntimeCfgMacro)
                }
            "target_has_atomic", "target_has_atomic_load_store", "target_has_atomic_equal_alignment" ->
                when {
                    profile.pointerWidth >= 64 && litText in ATOMIC_WIDTHS_64 -> true
                    profile.pointerWidth == 32 && litText in ATOMIC_WIDTHS_32 -> true
                    profile.pointerWidth == 16 && litText in ATOMIC_WIDTHS_16 -> true
                    else -> resolveUnknownCfgOutcome(profile, forRuntimeCfgMacro)
                }
            "target_os" -> evalTargetProperty(profile.targetOs, UNSUPPORTED_RUST_TARGET_OS, litText)
            "target_env" -> evalTargetProperty(profile.targetEnv, UNSUPPORTED_RUST_TARGET_ENV, litText)
            "target_vendor" -> evalTargetProperty(profile.targetVendor, UNSUPPORTED_RUST_TARGET_VENDOR, litText)
            "target_family" ->
                profile.targetFamilies?.let { litText in it }
                    ?: if (dirContainsSegment(relDir, litText)) {
                        true
                    } else {
                        resolveUnknownCfgOutcome(profile, forRuntimeCfgMacro)
                    }
            "feature" ->
                lookupExplicitKvFlag("feature", litText, checkDisabledFirst = true)
                    ?: profile.enabledFeatures?.let { litText in it }
                    ?: resolveUnknownCfgOutcome(profile, forRuntimeCfgMacro)
            else ->
                lookupExplicitKvFlag(key, litText, checkDisabledFirst = false)
                    ?: resolveUnknownCfgOutcome(profile, forRuntimeCfgMacro)
        }

    private fun evalTargetProperty(
        configured: String?,
        unsupported: Set<String>,
        litText: String,
    ): Boolean? =
        configured?.let { litText == it }
            ?: when {
                litText in unsupported -> false
                dirContainsSegment(relDir, litText) -> true
                else -> resolveUnknownCfgOutcome(profile, forRuntimeCfgMacro)
            }

    private fun lookupExplicitKvFlag(
        key: String,
        litText: String,
        checkDisabledFirst: Boolean,
    ): Boolean? {
        val kvEntry = "$key=$litText"
        val quotedKvEntry = "$key=\"$litText\""
        val inEnabled = kvEntry in profile.enabledCfgFlags || quotedKvEntry in profile.enabledCfgFlags
        val inDisabled = kvEntry in profile.disabledCfgFlags || quotedKvEntry in profile.disabledCfgFlags
        return if (checkDisabledFirst) {
            when {
                inDisabled -> false
                inEnabled -> true
                else -> null
            }
        } else {
            when {
                inEnabled -> true
                inDisabled -> false
                else -> null
            }
        }
    }

    private fun extractPredicateKey(endExclusive: Int): String? {
        if (endExclusive <= 0 || endExclusive % 3 != 1) return null
        val segments = ArrayList<String>((endExclusive + 2) / 3)
        var idx = 0
        while (idx < endExclusive) {
            segments += cleanIdentAt(idx) ?: return null
            if (idx + 1 < endExclusive && !isDoubleColonAt(idx + 1)) return null
            idx += 3
        }
        return segments.joinToString("::")
    }

    companion object {
        private val ALWAYS_FALSE_CFG_FLAGS = setOf("miri", "doc", "rustdoc", "doctest", "bootstrap")
        private val POINTER_WIDTHS_16_OR_32 = setOf(16, 32)
        private val ATOMIC_WIDTHS_64 = setOf("8", "16", "32", "64", "ptr")
        private val ATOMIC_WIDTHS_32 = setOf("8", "16", "32", "ptr")
        private val ATOMIC_WIDTHS_16 = setOf("8", "16", "ptr")

        private fun unquoteCfgStringLiteral(tt: TokenTree.Literal): String? {
            val decoded = tt.value.strValue().value
            if (decoded != null) return decoded
            val raw = tt.value.toString()
            if (raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"')) {
                return raw.substring(1, raw.length - 1)
            }
            if (raw.startsWith('r')) {
                val quoteStart = raw.indexOf('"')
                val quoteEnd = raw.lastIndexOf('"')
                if (quoteStart > 0 && quoteEnd > quoteStart) {
                    val prefixHashes = quoteStart - 1
                    val suffixHashes = raw.length - 1 - quoteEnd
                    val validPrefix = (1 until quoteStart).all { raw[it] == '#' }
                    val validSuffix = (quoteEnd + 1 until raw.length).all { raw[it] == '#' }
                    if (prefixHashes == suffixHashes && validPrefix && validSuffix) {
                        return raw.substring(quoteStart + 1, quoteEnd)
                    }
                }
            }
            return null
        }
    }
}

internal fun isRawPointerLiteralMacroBranches(branches: List<MacroRuleBranch>): Boolean {
    if (branches.isEmpty()) return false
    return branches.all { branch ->
        !branch.isRepetition &&
            branch.paramNames.size == 1 &&
            MacroDeclarativeTokenScanner.endsWithAsPtrCall(branch.bodyTokens.clone().toList())
    }
}

internal fun hasCfgTestModAttribute(attrs: List<Attribute>): Boolean {
    for (attr in attrs) {
        val meta = attr.meta
        if (meta is Meta.List && meta.path.isIdent("cfg")) {
            val list = meta.tokens.clone().toList()
            if (list.size == 1 && (list[0] as? TokenTree.Ident)?.value?.toString() == "test") {
                return true
            }
        }
    }
    return false
}

/**
 * OO extension of `syn-kotlin`'s [Visit] visitor that inspects a [File] for `macro_rules!`
 * declarative macro definitions.
 */
internal class MacroRulesCollector(
    private val skipCfgTestModules: Boolean = false,
    private val skipBlockLocals: Boolean = false,
) : Visit() {
    val definitions: MutableMap<String, MacroRulesDef> = linkedMapOf()

    override fun visitBlock(b: Block) {
        if (skipBlockLocals) return
        super.visitBlock(b)
    }

    override fun visitItemMod(i: Item.Mod) {
        if ((skipCfgTestModules && hasCfgTestModAttribute(i.attrs)) || isDocOnlyCfgAttribute(i.attrs)) return
        super.visitItemMod(i)
    }

    override fun visitItemMacro(i: Item.Macro) {
        if (isDocOnlyCfgAttribute(i.attrs)) return
        extractItemMacroDef(i, skipCfgTestModules)?.let { def ->
            if (def.name !in definitions) definitions[def.name] = def
        }
        super.visitItemMacro(i)
    }

    override fun visitItem(i: Item) {
        if (i is Item.Verbatim) {
            extractItemMacroDef(i, skipCfgTestModules)?.let { def ->
                if (def.name !in definitions) definitions[def.name] = def
            }
        }
        super.visitItem(i)
    }

    companion object {
        internal fun extractItemMacroDef(item: Item, skipCfgTestModules: Boolean): MacroRulesDef? =
            when (item) {
                is Item.Macro -> {
                    val macroName = item.ident?.toString()?.removePrefix("r#")
                    if (isDocOnlyCfgAttribute(item.attrs) || !item.mac.path.isIdent("macro_rules") || macroName == null || macroName == "offset_of") {
                        null
                    } else {
                        val branches = parseMacroRulesBranches(item.mac.tokens.clone())
                        if (branches.isNotEmpty() && !isRawPointerLiteralMacroBranches(branches)) MacroRulesDef(macroName, branches) else null
                    }
                }
                is Item.Verbatim ->
                    tryExtractDeclarativeMacro2FromTokens(item.tokens.clone().toList(), skipCfgTestModules)
                        ?.takeIf { it.name != "offset_of" && !isRawPointerLiteralMacroBranches(it.branches) }
                else -> null
            }

        internal fun buildMacroRuleBranch(patternStream: TokenStream, bodyStream: TokenStream): MacroRuleBranch {
            val patternAst = MacroPatternTokenParser(patternStream.clone().toList()).parsePattern()
            val params = MacroPat.collectMetavarList(patternAst)
            val isRep = patternAst.any { it is MacroPat.Repetition }
            return MacroRuleBranch(patternAst, params, isRep, stripTemplateSpans(bodyStream))
        }

        internal fun parseMacroRulesBranches(tokens: TokenStream): List<MacroRuleBranch> =
            MacroPatternTokenParser(tokens.clone().toList()).parseBranches()

        internal fun parseMacroPattern(patternStream: TokenStream): List<MacroPat> =
            MacroPatternTokenParser(patternStream.clone().toList()).parsePattern()

        private fun stripTemplateSpans(stream: TokenStream): TokenStream {
            val callSite = Span.callSite()
            val out =
                stream.clone().toList().map { tt ->
                    when (tt) {
                        is TokenTree.Group -> {
                            val inner = stripTemplateSpans(tt.value.stream())
                            val grp = Group(tt.value.delimiter(), inner)
                            grp.setSpan(callSite)
                            TokenTree.Group(grp)
                        }
                        else -> tt.setSpan(callSite)
                    }
                }
            return TokenStream.fromTokenTrees(out)
        }
    }
}

private class MacroPatternTokenParser(
    trees: List<TokenTree>,
) : RustTokenCursor(trees) {
    fun parseBranches(): List<MacroRuleBranch> {
        val branches = mutableListOf<MacroRuleBranch>()
        var idx = 0
        while (idx < size) {
            val patternGroup = groupAt(idx)
            if (patternGroup != null &&
                idx + 3 < size &&
                punctCharAt(idx + 1) == '=' &&
                punctCharAt(idx + 2) == '>'
            ) {
                val bodyGroup = groupAt(idx + 3)
                if (bodyGroup != null) {
                    branches.add(MacroRulesCollector.buildMacroRuleBranch(patternGroup.stream(), bodyGroup.stream()))
                    idx += 4
                    val sep = punctCharAt(idx)
                    if (sep == ';' || sep == ',') {
                        idx++
                    }
                    continue
                }
            }
            idx++
        }
        return branches
    }

    fun parsePattern(): List<MacroPat> {
        val out = mutableListOf<MacroPat>()
        var i = 0
        while (i < size) {
            if (punctCharAt(i) == '$' && i + 1 < size) {
                val metavarName = identAt(i + 1)
                if (metavarName != null) {
                    var frag = "tt"
                    i += 2
                    val specIdent = if (punctCharAt(i) == ':') identAt(i + 1) else null
                    if (specIdent != null) {
                        frag = specIdent
                        i += 2
                    }
                    out.add(MacroPat.Metavar(metavarName, frag))
                    continue
                }
                val repGroup = groupAt(i + 1, Delimiter.Parenthesis)
                if (repGroup != null) {
                    val innerPat = MacroPatternTokenParser(repGroup.stream().toList()).parsePattern()
                    i += 2
                    var sep: MacroPat.LiteralToken? = null
                    var op = '*'
                    val p1 = punctCharAt(i)
                    if (p1 == '*' || p1 == '+' || p1 == '?') {
                        op = p1
                        i++
                    } else {
                        val p2 = punctCharAt(i + 1)
                        if (p2 == '*' || p2 == '+') {
                            when (val sepToken = trees.getOrNull(i)) {
                                is TokenTree.Punct -> {
                                    sep = MacroPat.LiteralToken(sepToken.value.asChar().toString(), isPunct = true)
                                    op = p2
                                    i += 2
                                }
                                is TokenTree.Ident -> {
                                    sep = MacroPat.LiteralToken(sepToken.value.toString(), isPunct = false)
                                    op = p2
                                    i += 2
                                }
                                else -> {}
                            }
                        }
                    }
                    out.add(MacroPat.Repetition(innerPat, sep, op))
                    continue
                }
            }
            when (val tt = trees[i]) {
                is TokenTree.Group -> {
                    val inner = MacroPatternTokenParser(tt.value.stream().toList()).parsePattern()
                    out.add(MacroPat.GroupPat(tt.value.delimiter(), inner))
                }
                is TokenTree.Punct -> out.add(MacroPat.LiteralToken(tt.value.asChar().toString(), isPunct = true))
                is TokenTree.Ident -> out.add(MacroPat.LiteralToken(tt.value.toString(), isPunct = false))
                is TokenTree.Literal -> out.add(MacroPat.LiteralToken(tt.value.toString(), isPunct = false))
            }
            i++
        }
        return out
    }
}

/**
 * OO extension of `syn-kotlin`'s [Fold] AST transformer that expands top-level, module-level,
 * impl-level, block-statement, type, and expression invocations of `macro_rules!` definitions
 * directly into full `syn` AST nodes prior to Kotlin emission.
 */
internal class MacroRulesFileExpander(
    definitions: Map<String, MacroRulesDef>,
) : Fold() {
    private val activeDefinitions: MutableMap<String, MacroRulesDef> = definitions.toMutableMap()
    val macroWrapperSpansByTargetOffset: MutableMap<Int, MutableList<Span>> = linkedMapOf()
    private var activeExpansionDepth: Int = 0

    private inline fun <T> withExpansionDepth(newDepth: Int, block: () -> T): T {
        val prev = activeExpansionDepth
        activeExpansionDepth = newDepth
        try {
            return block()
        } finally {
            activeExpansionDepth = prev
        }
    }

    private fun resolveBuiltinCfgMacroName(mac: Macro): String? {
        val segs = mac.path.segments.toList()
        val macName =
            segs
                .lastOrNull()
                ?.ident
                ?.toString()
                ?.removePrefix("r#") ?: return null
        val firstName =
            segs
                .first()
                .ident
                .toString()
                .removePrefix("r#")
        val middleName =
            segs
                .getOrNull(1)
                ?.ident
                ?.toString()
                ?.removePrefix("r#")
        val isStdMacroRoot =
            firstName == "std" ||
                firstName == "core" ||
                firstName == "crate" ||
                firstName == "\$crate" ||
                firstName == "self" ||
                firstName == "super"
        return when {
            macName == "cfg_if" &&
                (segs.size == 1 || (segs.size == 2 && (firstName == "cfg_if" || isStdMacroRoot))) -> "cfg_if"
            macName in setOf("cfg_select", "cfg_select_nofmt") &&
                (
                    segs.size == 1 ||
                        (segs.size == 2 && isStdMacroRoot) ||
                        (segs.size == 3 && isStdMacroRoot && middleName in setOf("macros", "support"))
                ) -> "cfg_select"
            else -> null
        }
    }

    private fun resolveLocalMacroDefinition(mac: Macro): MacroRulesDef? {
        if (mac.path.leadingColon != null) return null
        val segs = mac.path.segments.toList()
        if (segs.isEmpty()) return null
        if (segs.size > 1) {
            val rootSeg =
                segs
                    .first()
                    .ident
                    .toString()
                    .removePrefix("r#")
            if (rootSeg != "crate" && rootSeg != "\$crate" && rootSeg != "self" && rootSeg != "super") {
                return null
            }
        }
        val macName =
            segs
                .last()
                .ident
                .toString()
                .removePrefix("r#")
        if (macName == "Token") return null
        return activeDefinitions[macName]
    }

    private inline fun <T> withSavedMacroDefs(block: () -> T): T {
        val savedDefs = activeDefinitions.toMap()
        return try {
            block()
        } finally {
            activeDefinitions.clear()
            activeDefinitions.putAll(savedDefs)
        }
    }

    private fun extractLocalMacroRulesDef(item: Item): MacroRulesDef? =
        MacroRulesCollector.extractItemMacroDef(item, skipCfgTestModules = false)

    override fun foldFile(file: File): File {
        val foldedAttrs = file.attrs.map(::foldAttribute)
        val expandedAndFoldedItems = expandAndFoldItemList(file.items, depth = activeExpansionDepth)
        return file.copy(attrs = foldedAttrs, items = expandedAndFoldedItems)
    }

    override fun foldItemMod(item: Item.Mod): Item.Mod {
        val content = item.content
        if (content is ModContent.Inline) {
            return withSavedMacroDefs {
                val foldedShell = super.foldItemMod(item.copy(content = content.copy(items = emptyList())))
                val expanded = expandAndFoldItemList(content.items, depth = activeExpansionDepth)
                val foldedContent = foldedShell.content as ModContent.Inline
                foldedShell.copy(content = foldedContent.copy(items = expanded))
            }
        }
        return super.foldItemMod(item)
    }

    override fun foldItemImpl(item: Item.Impl): Item.Impl {
        val foldedShell = super.foldItemImpl(item.copy(items = emptyList()))
        val expandedImplItems = expandAndFoldImplItemList(item.items, depth = activeExpansionDepth)
        return foldedShell.copy(items = expandedImplItems)
    }

    override fun foldBlock(block: Block): Block =
        withSavedMacroDefs {
            block.copy(stmts = expandAndFoldBlockStmts(block.stmts, depth = activeExpansionDepth))
        }

    private fun <A> retainPreferredCfgArgs(
        args: List<A>,
        attrsOf: (A) -> List<Attribute>,
        cleanIdentOf: (A) -> String?,
        retainByPredicate: ((A) -> Boolean) -> Unit,
    ) {
        if (args.isEmpty()) return
        val keptSet = linkedSetOf<Int>()
        for ((idx, arg) in args.withIndex()) {
            if (!isDocOnlyCfgAttribute(attrsOf(arg))) keptSet += idx
        }
        val byCleanIdent = linkedMapOf<String, MutableList<Int>>()
        for (idx in keptSet) {
            val cleanIdent = cleanIdentOf(args[idx]) ?: continue
            if (cleanIdent != "_") byCleanIdent.getOrPut(cleanIdent) { mutableListOf() } += idx
        }
        for ((_, group) in byCleanIdent) {
            if (group.size <= 1) continue
            val winnerIdx = group.maxByOrNull { cfgAttrPreferenceScore(attrsOf(args[it])) }!!
            for (idx in group) {
                if (idx != winnerIdx) keptSet.remove(idx)
            }
        }
        if (keptSet.size < args.size) {
            var idx = 0
            retainByPredicate {
                val keep = idx in keptSet
                idx++
                keep
            }
        }
    }

    override fun foldSignature(sig: Signature): Signature {
        val folded = super.foldSignature(sig)
        retainPreferredCfgArgs(folded.inputs.toList(), ::fnArgAttrs, ::fnArgCleanIdent) { pred ->
            folded.inputs.retain(pred)
        }
        return folded
    }

    override fun foldTypeFnPtr(ty: SynType.FnPtr): SynType.FnPtr {
        val folded = super.foldTypeFnPtr(ty)
        retainPreferredCfgArgs(
            args = folded.inputs.toList(),
            attrsOf = { it.attrs },
            cleanIdentOf = { arg ->
                val raw = arg.name?.ident?.toString()
                raw?.removePrefix("r#")
            },
        ) { pred -> folded.inputs.retain(pred) }
        return folded
    }

    private fun fnArgAttrs(arg: FnArg): List<Attribute> =
        when (arg) {
            is FnArg.Receiver -> arg.attrs
            is FnArg.Typed -> arg.patType.attrs
        }

    private fun fnArgCleanIdent(arg: FnArg): String? =
        (arg as? FnArg.Typed)?.let {
            (it.patType.pat as? Pat.Ident)?.ident?.toString()?.removePrefix("r#")
        }

    override fun foldType(t: SynType): SynType {
        if (t is SynType.Macro && activeDefinitions.isNotEmpty() && activeExpansionDepth <= MAX_MACRO_EXPANSION_DEPTH) {
            val expandedType =
                expandMacroSyntaxRecursive(
                    mac = t.mac,
                    depth = activeExpansionDepth,
                    parseNode = { parse2(SynType.Companion::parse, it).getOrNull()?.let(RustNextAstNormalizer::foldType) },
                    nestedMacroOf = { (it as? SynType.Macro)?.mac },
                )
            if (expandedType != null) {
                return withExpansionDepth(activeExpansionDepth + 1) { super.foldType(expandedType) }
            }
        }
        return super.foldType(t)
    }

    override fun foldExpr(e: Expr): Expr {
        if (e is Expr.Macro && activeExpansionDepth <= MAX_MACRO_EXPANSION_DEPTH) {
            val exprCfgMacroName = resolveBuiltinCfgMacroName(e.mac)
            if (exprCfgMacroName == "cfg_select" || exprCfgMacroName == "cfg_if") {
                val rawToks = e.mac.clonedTokenList()
                val selected =
                    if (exprCfgMacroName == "cfg_select") {
                        chooseSingleActiveCfgSelectBody(rawToks)
                    } else {
                        selectActiveCfgIfBodies(rawToks)?.firstOrNull()
                    }
                if (selected != null) {
                    val parsedExpr =
                        if (selected.isEmpty()) {
                            parse2(ExprParse::parse, TokenStream.fromTokenTrees(listOf(RustTokenCursor.groupTree(Delimiter.Brace)))).getOrNull()
                        } else {
                            val selectedStream = TokenStream.fromTokenTrees(selected)
                            parse2(ExprParse::parse, selectedStream).getOrNull()
                                ?: parse2(
                                    ExprParse::parse,
                                    TokenStream.fromTokenTrees(listOf(RustTokenCursor.groupStreamTree(Delimiter.Brace, selectedStream))),
                                ).getOrNull()
                        }
                    if (parsedExpr != null) {
                        return withExpansionDepth(activeExpansionDepth + 1) {
                            super.foldExpr(RustNextAstNormalizer.foldExpr(parsedExpr))
                        }
                    }
                }
            }
            if (activeDefinitions.isNotEmpty()) {
                val expandedExpr =
                    expandMacroSyntaxRecursive(
                        mac = e.mac,
                        depth = activeExpansionDepth,
                        parseNode = { parse2(ExprParse::parse, it).getOrNull()?.let(RustNextAstNormalizer::foldExpr) },
                        nestedMacroOf = { (it as? Expr.Macro)?.mac },
                    )
                if (expandedExpr != null) {
                    return withExpansionDepth(activeExpansionDepth + 1) { super.foldExpr(expandedExpr) }
                }
            }
        }
        return super.foldExpr(e)
    }

    private fun <N : Any> expandMacroSyntaxRecursive(
        mac: Macro,
        depth: Int,
        parseNode: (TokenStream) -> N?,
        nestedMacroOf: (N) -> Macro?,
    ): N? {
        if (depth > MAX_MACRO_EXPANSION_DEPTH) return null
        val def = resolveLocalMacroDefinition(mac) ?: return null
        val substituted = expandMacroToTokenStream(def, mac) ?: return null
        val parsed = parseNode(substituted.clone()) ?: return null
        val nestedMac = nestedMacroOf(parsed) ?: return parsed
        return expandMacroSyntaxRecursive(nestedMac, depth + 1, parseNode, nestedMacroOf) ?: parsed
    }

    private fun expandAndFoldItemList(items: List<Item>, depth: Int): List<Item> {
        if (depth > MAX_MACRO_EXPANSION_DEPTH || activeExpansionDepth > MAX_MACRO_EXPANSION_DEPTH) {
            return items.map(::foldItem)
        }
        val result = mutableListOf<Item>()
        for (item in items) {
            val localDef = extractLocalMacroRulesDef(item)
            if (localDef != null) {
                activeDefinitions[localDef.name] = localDef
                result.add(item)
                continue
            }
            if (item is Item.Macro && item.ident == null) {
                val def = resolveLocalMacroDefinition(item.mac)
                val builtinCfgName = if (def == null) resolveBuiltinCfgMacroName(item.mac) else null
                if (builtinCfgName != null) {
                    val rawTokens = item.mac.clonedTokenList()
                    val bodies = if (builtinCfgName == "cfg_select") selectActiveCfgSelectBodies(rawTokens) else selectActiveCfgIfBodies(rawTokens)
                    val normalized = bodies?.let { SynAstFragmentParser.parseNormalizedFile(TokenStream.fromTokenTrees(it.flatten())) }
                    if (normalized != null) {
                        val expanded = withExpansionDepth(depth + 1) { expandAndFoldItemList(normalized.items, depth + 1) }
                        val propagated = propagateOuterAttrsToItems(item.attrs, expanded)
                        attachWrapperSpansToFirstExpandedItem(collectTransitiveWrapperSpansForItem(item), propagated)
                        result.addAll(propagated)
                        continue
                    }
                }
                if (def != null) {
                    val wrapperSpans = collectTransitiveWrapperSpansForItem(item)
                    val expanded = tryExpandMacroItem(def, item.mac, depth)
                    if (expanded != null) {
                        val propagated = propagateOuterAttrsToItems(item.attrs, expanded)
                        attachWrapperSpansToFirstExpandedItem(wrapperSpans, propagated)
                        result.addAll(propagated)
                        continue
                    }
                }
            }
            result.add(foldItem(item))
        }
        return result
    }

    private fun expandAndFoldImplItemList(items: List<ImplItem>, depth: Int): List<ImplItem> {
        if (depth > MAX_MACRO_EXPANSION_DEPTH || activeExpansionDepth > MAX_MACRO_EXPANSION_DEPTH) {
            return items.map(::foldImplItem)
        }
        val result = mutableListOf<ImplItem>()
        for (item in items) {
            if (item is ImplItem.Macro) {
                val def = resolveLocalMacroDefinition(item.mac)
                val builtinCfgName = if (def == null) resolveBuiltinCfgMacroName(item.mac) else null
                if (builtinCfgName != null) {
                    val rawTokens = item.mac.clonedTokenList()
                    val selectedItems =
                        if (builtinCfgName == "cfg_select") {
                            selectActiveCfgSelectImplItems(rawTokens)
                        } else {
                            selectActiveCfgIfBodies(rawTokens)?.let { bodies ->
                                SynAstFragmentParser.parseNormalizedImplItems(TokenStream.fromTokenTrees(bodies.flatten()))
                            }
                        }
                    if (selectedItems != null) {
                        val expanded = withExpansionDepth(depth + 1) { expandAndFoldImplItemList(selectedItems, depth + 1) }
                        result.addAll(propagateOuterAttrsToImplItems(item.attrs, expanded))
                        continue
                    }
                }
                if (def != null) {
                    val expanded = tryExpandMacroImplItem(def, item.mac, depth)
                    if (expanded != null) {
                        result.addAll(propagateOuterAttrsToImplItems(item.attrs, expanded))
                        continue
                    }
                }
            }
            result.add(foldImplItem(item))
        }
        return result
    }

    private fun <N> propagateOuterAttrs(
        outerAttrs: List<Attribute>,
        expanded: List<N>,
        isDocOnly: (N) -> Boolean,
        prepend: (N, List<Attribute>) -> N,
    ): List<N> {
        if (outerAttrs.isEmpty() || expanded.isEmpty()) return expanded
        val (docAttrs, nonDocAttrs) =
            outerAttrs.partition { attr ->
                val meta = attr.meta
                meta is Meta.NameValue && meta.path.isIdent("doc")
            }
        val firstNonDocIdx = expanded.indexOfFirst { !isDocOnly(it) }
        val docTargetIndex = if (firstNonDocIdx >= 0) firstNonDocIdx else 0
        return expanded.mapIndexed { idx, node ->
            val extra = if (idx == docTargetIndex) docAttrs + nonDocAttrs else nonDocAttrs
            if (extra.isEmpty()) node else prepend(node, extra)
        }
    }

    private fun propagateOuterAttrsToItems(outerAttrs: List<Attribute>, expanded: List<Item>): List<Item> =
        propagateOuterAttrs(outerAttrs, expanded, ::itemHasDocOnlyCfg) { item, extra -> item.withPrependedAttributes(extra) }

    private fun propagateOuterAttrsToImplItems(outerAttrs: List<Attribute>, expanded: List<ImplItem>): List<ImplItem> =
        propagateOuterAttrs(outerAttrs, expanded, ::implItemHasDocOnlyCfg) { item, extra -> item.withPrependedAttributes(extra) }

    private fun collectTransitiveWrapperSpansForItem(item: Item): List<Span> {
        val ownSpans = collectHeaderSourceSpans(item)
        val out = mutableListOf<Span>()
        ownSpans.firstOrNull()?.startOffset()?.let { firstOwnOffset ->
            macroWrapperSpansByTargetOffset[firstOwnOffset]?.let(out::addAll)
        }
        out.addAll(ownSpans)
        return out
    }

    private fun attachWrapperSpansToFirstExpandedItem(wrapperSpans: List<Span>, expanded: List<Item>) {
        if (wrapperSpans.isEmpty() || expanded.isEmpty()) return
        val targetItem = expanded.firstOrNull { !itemHasDocOnlyCfg(it) } ?: expanded.first()
        val targetOffset = collectHeaderSourceSpans(targetItem).firstOrNull()?.startOffset() ?: return
        val bucket = macroWrapperSpansByTargetOffset.getOrPut(targetOffset) { mutableListOf() }
        for (sp in wrapperSpans) {
            val spStart = sp.startOffset()
            if (spStart != targetOffset && bucket.none { it.startOffset() == spStart }) {
                bucket.add(sp)
            }
        }
    }

    private fun expandAndFoldBlockStmts(stmts: List<Stmt>, depth: Int): List<Stmt> {
        if (depth > MAX_MACRO_EXPANSION_DEPTH || activeExpansionDepth > MAX_MACRO_EXPANSION_DEPTH) {
            return stmts.map(::foldStmt)
        }
        val result = mutableListOf<Stmt>()
        for (stmt in stmts) {
            if (stmt is Stmt.ItemStmt) {
                val localDef = extractLocalMacroRulesDef(stmt.item)
                if (localDef != null) {
                    activeDefinitions[localDef.name] = localDef
                    result.add(stmt)
                    continue
                }
            }
            if (stmt is Stmt.MacroStmt) {
                val def = resolveLocalMacroDefinition(stmt.mac)
                val builtinCfgName = if (def == null) resolveBuiltinCfgMacroName(stmt.mac) else null
                if (builtinCfgName != null) {
                    val rawTokens = stmt.mac.clonedTokenList()
                    val selectedBody =
                        if (builtinCfgName == "cfg_select") {
                            chooseSingleActiveCfgSelectBody(rawTokens)
                        } else {
                            selectActiveCfgIfBodies(rawTokens)?.firstOrNull()
                        }
                    if (selectedBody != null) {
                        if (selectedBody.isEmpty()) continue
                        val parsedStmts = SynAstFragmentParser.parseNormalizedBlockStmts(TokenStream.fromTokenTrees(selectedBody))
                        if (parsedStmts != null) {
                            result.addAll(withExpansionDepth(depth + 1) { expandAndFoldBlockStmts(parsedStmts, depth + 1) })
                            continue
                        }
                    }
                }
                if (def != null) {
                    val expanded = tryExpandMacroStmts(def, stmt.mac, depth)
                    if (expanded != null) {
                        result.addAll(expanded)
                        continue
                    }
                }
            }
            result.add(foldStmt(stmt))
        }
        return result
    }

    private fun tryExpandMacroItem(def: MacroRulesDef, invocation: Macro, depth: Int): List<Item>? {
        val substitutedStream = expandMacroToTokenStream(def, invocation) ?: return null
        if (substitutedStream.isEmpty()) return emptyList()
        val parsedFile = SynAstFragmentParser.parseNormalizedFile(substitutedStream) ?: return null
        return withExpansionDepth(depth + 1) { expandAndFoldItemList(parsedFile.items, depth + 1) }
    }

    private fun tryExpandMacroImplItem(def: MacroRulesDef, invocation: Macro, depth: Int): List<ImplItem>? {
        val substitutedStream = expandMacroToTokenStream(def, invocation) ?: return null
        if (substitutedStream.isEmpty()) return emptyList()
        val implItems = SynAstFragmentParser.parseNormalizedImplItems(substitutedStream) ?: return null
        return withExpansionDepth(depth + 1) { expandAndFoldImplItemList(implItems, depth + 1) }
    }

    private fun tryExpandMacroStmts(def: MacroRulesDef, invocation: Macro, depth: Int): List<Stmt>? {
        val substitutedStream = expandMacroToTokenStream(def, invocation) ?: return null
        if (substitutedStream.isEmpty()) return emptyList()
        val fnStmts = SynAstFragmentParser.parseNormalizedBlockStmts(substitutedStream) ?: return null
        return withExpansionDepth(depth + 1) { expandAndFoldBlockStmts(fnStmts, depth + 1) }
    }

    private fun expandMacroToTokenStream(def: MacroRulesDef, invocation: Macro): TokenStream? {
        fun tryMatch(trees: List<TokenTree>, skipTtMetavar: Boolean): TokenStream? {
            val matcher = MacroFragmentTokenCursor(trees)
            for (branch in def.branches) {
                if (skipTtMetavar && MacroPat.hasTtMetavar(branch.pattern)) continue
                val env = MacroMatchEnv()
                if (matcher.matchSequence(branch.pattern, 0, 0, env) == trees.size) {
                    return substituteMacroTokensWithEnv(branch.bodyTokens.clone(), env)
                }
            }
            return null
        }

        val inputTrees = invocation.tokens.clone().toList()
        tryMatch(inputTrees, skipTtMetavar = false)?.let { return it }
        if (inputTrees.any { it is TokenTree.Group && it.value.delimiter() == Delimiter.None }) {
            return tryMatch(RustTokenCursor.flattenNoneDelimitedGroupList(inputTrees), skipTtMetavar = true)
        }
        return null
    }

    companion object {
        private const val MAX_MACRO_EXPANSION_DEPTH = 64

        internal fun substituteMacroTokensWithEnv(
            template: TokenStream,
            env: MacroMatchEnv,
            repIndexStack: List<Int> = emptyList(),
            repLenStack: List<Int> = emptyList(),
        ): TokenStream =
            MacroTemplateTokenCursor(template.clone().toList()).substituteWithEnv(env, repIndexStack, repLenStack)
    }
}

/**
 * [RustTokenCursor] subclass matching `macro_rules!` patterns and consuming fragment specifiers
 * (`ident`, `tt`, `lifetime`, `literal`, `block`, `vis`, `ty`, `path`, `item`, `stmt`, `expr`, `pat`, `meta`).
 */
private class MacroFragmentTokenCursor(
    trees: List<TokenTree>,
) : RustTokenCursor(trees) {
    fun matchSequence(
        pats: List<MacroPat>,
        patIdx: Int,
        inIdx: Int,
        env: MacroMatchEnv,
        trailingStopPats: List<MacroPat> = emptyList(),
    ): Int? {
        if (patIdx == pats.size) return inIdx
        return when (val pat = pats[patIdx]) {
            is MacroPat.LiteralToken -> {
                if (inIdx >= size || !MacroPat.tokenMatchesLiteral(trees[inIdx], pat)) return null
                if (!pat.isPunct && trees[inIdx] is TokenTree.Ident && pat.text !in env.matchedKeywordSpans) {
                    env.matchedKeywordSpans[pat.text] = trees[inIdx].span()
                }
                matchSequence(pats, patIdx + 1, inIdx + 1, env, trailingStopPats)
            }
            is MacroPat.GroupPat -> {
                val grp = groupAt(inIdx, pat.delimiter) ?: return null
                val innerTrees = grp.stream().toList()
                if (MacroFragmentTokenCursor(innerTrees).matchSequence(pat.inner, 0, 0, env) != innerTrees.size) {
                    return null
                }
                matchSequence(pats, patIdx + 1, inIdx + 1, env, trailingStopPats)
            }
            is MacroPat.Metavar -> {
                val stopPats = collectLeadingStopPats(pats, patIdx + 1, trailingStopPats)
                val spanLen = consumeFragmentLength(pat.fragSpec, inIdx, stopPats) ?: return null
                env.singles[pat.name] = trees.subList(inIdx, inIdx + spanLen)
                matchSequence(pats, patIdx + 1, inIdx + spanLen, env, trailingStopPats)
            }
            is MacroPat.Repetition -> {
                var curIdx = inIdx
                var count = 0
                val nextAfterRepStops = collectLeadingStopPats(pats, patIdx + 1, trailingStopPats)
                val immediateNext = pats.getOrNull(patIdx + 1)
                val groupIters = mutableListOf<MacroMatchEnv>()
                while (curIdx < size) {
                    if (immediateNext is MacroPat.LiteralToken &&
                        MacroPat.tokenMatchesLiteral(trees[curIdx], immediateNext) &&
                        !(count > 0 && pat.sep == immediateNext)
                    ) {
                        break
                    }
                    var iterStart = curIdx
                    if (count > 0 && pat.sep != null) {
                        if (!MacroPat.tokenMatchesLiteral(trees[iterStart], pat.sep)) break
                        iterStart++
                        if (iterStart >= size) break
                    }
                    val childEnv = MacroMatchEnv()
                    val afterIter =
                        matchRepetitionIteration(
                            innerPats = pat.inner,
                            repOp = pat.op,
                            startIdx = iterStart,
                            repSep = pat.sep,
                            nextAfterRepStops = nextAfterRepStops,
                            childEnv = childEnv,
                        )
                    if (afterIter == null || afterIter <= iterStart) break
                    env.repetitions.add(childEnv)
                    groupIters.add(childEnv)
                    curIdx = afterIter
                    count++
                    if (pat.op == '?') break
                }
                if (pat.op == '+' && count == 0) return null
                env.repetitionGroups.add(
                    RepetitionGroup(
                        directVars = MacroPat.collectMetavarSet(pat.inner, includeRepetitions = false),
                        allVars = MacroPat.collectMetavarSet(pat.inner, includeRepetitions = true),
                        iterations = groupIters,
                    ),
                )
                matchSequence(pats, patIdx + 1, curIdx, env, trailingStopPats)
            }
        }
    }

    private fun matchRepetitionIteration(
        innerPats: List<MacroPat>,
        repOp: Char,
        startIdx: Int,
        repSep: MacroPat.LiteralToken?,
        nextAfterRepStops: List<MacroPat>,
        childEnv: MacroMatchEnv,
    ): Int? {
        val iterTrailingStops =
            if (repOp == '?') {
                nextAfterRepStops
            } else {
                buildList {
                    if (repSep != null) {
                        add(repSep)
                    } else {
                        addAll(collectLeadingStopPats(innerPats, 0, emptyList()))
                    }
                    addAll(nextAfterRepStops)
                }
            }
        return matchSequence(innerPats, 0, startIdx, childEnv, iterTrailingStops)
    }

    private fun consumeFragmentLength(
        fragSpec: String,
        startIdx: Int,
        stopPats: List<MacroPat>,
    ): Int? {
        if (fragSpec == "vis") return copyOptionalPubVisibility(startIdx) - startIdx
        if (startIdx >= size) return null
        return when (fragSpec) {
            "ident" -> if (trees[startIdx] is TokenTree.Ident) 1 else null
            "tt" -> 1
            "lifetime" -> if (punctCharAt(startIdx) == '\'' && trees.getOrNull(startIdx + 1) is TokenTree.Ident) 2 else null
            "literal" ->
                when {
                    trees[startIdx] is TokenTree.Literal -> 1
                    punctCharAt(startIdx) == '-' && trees.getOrNull(startIdx + 1) is TokenTree.Literal -> 2
                    else -> null
                }
            "block" -> if (groupAt(startIdx, Delimiter.Brace) != null) 1 else null
            "ty" -> consumeSingleTypeLength(startIdx)
            "path" -> consumeTypePathOrMacro(startIdx, allowMacroBang = false)?.let { it - startIdx }
            "item" -> consumeSingleItemLength(startIdx)
            else -> consumeDelimitedOrExprFragmentLength(fragSpec, startIdx, stopPats)
        }
    }

    private fun consumeDelimitedOrExprFragmentLength(
        fragSpec: String,
        startIdx: Int,
        stopPats: List<MacroPat>,
    ): Int? {
        val literalOrGroupStops = stopPats.filter { it is MacroPat.LiteralToken || it is MacroPat.GroupPat }
        if (fragSpec == "stmt" && literalOrGroupStops.isEmpty()) {
            return consumeSingleStmtLength(startIdx)
        }
        var idx = startIdx
        var angleDepth = 0
        var inClosureParams = false
        while (idx < size) {
            val tt = trees[idx]
            val ch = punctCharAt(idx)
            if (angleDepth == 0 && !inClosureParams) {
                if (literalOrGroupStops.any { stop -> tokenMatchesStopPat(tt, stop) }) break
                if (fragSpec == "stmt" && ch == ';') {
                    idx++
                    break
                }
                if (fragSpec != "stmt" && (ch == ',' || ch == ';' || isFatArrowAt(idx))) break
            }
            if (ch != null) {
                val isJoint = punctSpacingAt(idx) == Spacing.Joint
                val nextCh = punctCharAt(idx + 1)
                if (ch == '|' && angleDepth == 0) {
                    if (isJoint && (nextCh == '|' || nextCh == '=')) {
                        idx += 2
                        continue
                    }
                    if (inClosureParams) {
                        inClosureParams = false
                    } else if (canOpenClosureParamList(startIdx, idx)) {
                        inClosureParams = true
                    }
                } else if (ch == '<' && hasMatchingClosingAngle(idx)) {
                    angleDepth++
                } else if (ch == '>' && angleDepth > 0 && !isRArrowEndAt(idx)) {
                    angleDepth--
                }
            }
            idx++
        }
        if (idx <= startIdx) return null
        if (fragSpec == "expr") {
            val hasNonLiteralStop = stopPats.any { it !is MacroPat.LiteralToken && it !is MacroPat.GroupPat }
            return resolveValidExprFragmentLength(startIdx, idx, allowPrefixShrink = hasNonLiteralStop)
        }
        return idx - startIdx
    }

    private fun resolveValidExprFragmentLength(
        startIdx: Int,
        maxEndIdx: Int,
        allowPrefixShrink: Boolean,
    ): Int? {
        if (isValidMacroExprTokens(trees.subList(startIdx, maxEndIdx))) {
            return maxEndIdx - startIdx
        }
        if (!allowPrefixShrink) return null
        var candidateEnd = maxEndIdx - 1
        while (candidateEnd > startIdx) {
            if (isValidMacroExprTokens(trees.subList(startIdx, candidateEnd))) {
                return candidateEnd - startIdx
            }
            candidateEnd--
        }
        return null
    }

    private fun canOpenClosureParamList(startIdx: Int, pipeIdx: Int): Boolean {
        if (pipeIdx <= startIdx) return true
        val prevPunct = punctCharAt(pipeIdx - 1)
        if (prevPunct != null) return prevPunct != '?' && prevPunct != '>'
        val prevIdent = identAt(pipeIdx - 1)
        return prevIdent != null && prevIdent in CLOSURE_PREFIX_KEYWORDS
    }

    private fun consumeSingleItemLength(startIdx: Int): Int? {
        if (startIdx >= size) return null
        var idx = startIdx
        while (punctCharAt(idx) == '#' && groupAt(idx + 1, Delimiter.Bracket) != null) {
            idx += 2
        }
        if (idx >= size) return null
        val probeIdx = copyOptionalPubVisibility(idx)
        val headIdent = identAt(probeIdx)
        val isConstOrStaticValue =
            when (headIdent) {
                "static", "type", "use" -> true
                "const" -> identAt(probeIdx + 1) !in CONST_FN_FOLLOW_KEYWORDS
                else -> false
            }
        while (idx < size) {
            if (punctCharAt(idx) == ';') return (idx + 1) - startIdx
            if (!isConstOrStaticValue && groupAt(idx, Delimiter.Brace) != null) return (idx + 1) - startIdx
            idx++
        }
        return if (idx > startIdx) idx - startIdx else null
    }

    private fun consumeSingleStmtLength(startIdx: Int): Int? {
        if (startIdx >= size) return null
        var idx = startIdx
        while (idx < size) {
            if (punctCharAt(idx) == ';') return (idx + 1) - startIdx
            idx++
        }
        return size - startIdx
    }

    private fun consumeSingleTypeLength(startIdx: Int): Int? {
        if (startIdx >= size) return null
        var idx = startIdx
        while (idx < size) {
            if (punctCharAt(idx) == '&') {
                idx++
                if (punctCharAt(idx) == '\'' && trees.getOrNull(idx + 1) is TokenTree.Ident) {
                    idx += 2
                }
                if (identAt(idx) == "mut") {
                    idx++
                }
                continue
            }
            if (punctCharAt(idx) == '*') {
                val qual = identAt(idx + 1)
                if (qual == "const" || qual == "mut") {
                    idx += 2
                    continue
                }
            }
            if (identAt(idx) == "for" && punctCharAt(idx + 1) == '<' && hasMatchingClosingAngle(idx + 1)) {
                idx = skipBalancedAngles(idx + 1) ?: size
                continue
            }
            break
        }
        if (idx >= size) return null
        val coreGrp = groupAt(idx)
        if (coreGrp != null && (coreGrp.delimiter() == Delimiter.Bracket || coreGrp.delimiter() == Delimiter.Parenthesis)) {
            return (idx + 1) - startIdx
        }
        if (punctCharAt(idx) == '!') return (idx + 1) - startIdx
        tryConsumeBareFnType(idx)?.let { return it - startIdx }
        val coreIdent = identAt(idx)
        if (coreIdent == "impl" || coreIdent == "dyn") {
            val boundsEnd = consumeTypeParamBounds(idx + 1) ?: return null
            return boundsEnd - startIdx
        }
        val pathEnd = consumeTypePathOrMacro(idx, allowMacroBang = true) ?: return null
        return pathEnd - startIdx
    }

    private fun tryConsumeBareFnType(startIdx: Int): Int? {
        var idx = startIdx
        if (identAt(idx) == "unsafe") idx++
        if (identAt(idx) == "extern") {
            idx++
            if (trees.getOrNull(idx) is TokenTree.Literal) idx++
        }
        if (identAt(idx) != "fn") return null
        idx++
        if (groupAt(idx, Delimiter.Parenthesis) == null) return null
        idx++
        if (punctCharAt(idx) == '-' && punctCharAt(idx + 1) == '>') {
            idx += 2
            val retLen = consumeSingleTypeLength(idx) ?: return null
            idx += retLen
        }
        return idx
    }

    private fun consumeTypeParamBounds(startIdx: Int): Int? {
        var idx = consumeSingleTypeParamBound(startIdx) ?: return null
        while (punctCharAt(idx) == '+') {
            idx = consumeSingleTypeParamBound(idx + 1) ?: break
        }
        return idx
    }

    private fun consumeSingleTypeParamBound(startIdx: Int): Int? {
        if (startIdx >= size) return null
        var idx = startIdx
        if (groupAt(idx, Delimiter.Parenthesis) != null) return idx + 1
        if (punctCharAt(idx) == '\'' && trees.getOrNull(idx + 1) is TokenTree.Ident) return idx + 2
        if (punctCharAt(idx) == '?' || punctCharAt(idx) == '~') idx++
        if (identAt(idx) == "for" && punctCharAt(idx + 1) == '<' && hasMatchingClosingAngle(idx + 1)) {
            idx = skipBalancedAngles(idx + 1) ?: size
        }
        return consumeTypePathOrMacro(idx, allowMacroBang = false, allowAllParenthesizedArgs = true)
    }

    private fun consumeTypePathOrMacro(
        startIdx: Int,
        allowMacroBang: Boolean,
        allowAllParenthesizedArgs: Boolean = false,
    ): Int? {
        if (startIdx >= size) return null
        var idx = startIdx
        if (punctCharAt(idx) == '<' && hasMatchingClosingAngle(idx)) {
            idx = skipBalancedAngles(idx) ?: size
            if (punctCharAt(idx) != ':' || punctCharAt(idx + 1) != ':') return null
            idx += 2
        } else if (punctCharAt(idx) == ':' && punctCharAt(idx + 1) == ':') {
            idx += 2
        }
        var lastSegName = identAt(idx) ?: return null
        idx++
        while (idx < size) {
            if (allowMacroBang && punctCharAt(idx) == '!' && groupAt(idx + 1) != null) {
                return idx + 2
            }
            if (punctCharAt(idx) == '<' && hasMatchingClosingAngle(idx)) {
                idx = skipBalancedAngles(idx) ?: size
                continue
            }
            if (groupAt(idx, Delimiter.Parenthesis) != null &&
                (allowAllParenthesizedArgs || lastSegName.startsWith("Fn"))
            ) {
                idx++
                if (punctCharAt(idx) == '-' && punctCharAt(idx + 1) == '>') {
                    idx += 2
                    val retLen = consumeSingleTypeLength(idx) ?: return null
                    idx += retLen
                }
                continue
            }
            val nextSegName =
                if (punctCharAt(idx) == ':' && punctCharAt(idx + 1) == ':') identAt(idx + 2) else null
            if (nextSegName != null) {
                lastSegName = nextSegName
                idx += 3
                continue
            }
            break
        }
        return idx
    }

    companion object {
        private val CLOSURE_PREFIX_KEYWORDS = wordSet("move async static const return break yield")
        private val CONST_FN_FOLLOW_KEYWORDS = wordSet("fn unsafe async extern")
        private val NON_EXPR_SINGLE_IDENT_KEYWORDS =
            wordSet(
                "as async break const continue crate dyn else enum extern fn for if impl in let loop match",
                "mod move mut pub ref return static struct super trait type union unsafe use where while yield",
            )

        private fun collectLeadingStopPats(
            pats: List<MacroPat>,
            startPatIdx: Int,
            trailingStopPats: List<MacroPat>,
        ): List<MacroPat> {
            val stops = mutableListOf<MacroPat>()
            var idx = startPatIdx
            while (idx < pats.size) {
                when (val p = pats[idx]) {
                    is MacroPat.LiteralToken,
                    is MacroPat.GroupPat,
                    -> {
                        stops.add(p)
                        return stops
                    }
                    is MacroPat.Repetition -> {
                        stops.addAll(collectLeadingStopPats(p.inner, 0, emptyList()))
                        if (p.op == '+') return stops
                    }
                    is MacroPat.Metavar -> {
                        if (p.fragSpec == "vis") {
                            stops.add(MacroPat.LiteralToken("pub", isPunct = false))
                        } else {
                            return stops
                        }
                    }
                }
                idx++
            }
            stops.addAll(trailingStopPats)
            return stops
        }

        private fun tokenMatchesStopPat(tt: TokenTree, stop: MacroPat): Boolean =
            when (stop) {
                is MacroPat.LiteralToken -> MacroPat.tokenMatchesLiteral(tt, stop)
                is MacroPat.GroupPat -> tt is TokenTree.Group && tt.value.delimiter() == stop.delimiter
                else -> false
            }

        private fun isValidMacroExprTokens(tokens: List<TokenTree>): Boolean {
            if (tokens.isEmpty()) return false
            if (tokens.size == 1) {
                when (val single = tokens[0]) {
                    is TokenTree.Literal -> return true
                    is TokenTree.Ident -> if (single.value.toString() !in NON_EXPR_SINGLE_IDENT_KEYWORDS) return true
                    else -> {}
                }
            }
            val normalized = normalizeLetOrPatternsForSyn(TokenStream.fromTokenTrees(tokens))
            return parse2(ExprParse::parse, normalized).getOrNull() != null
        }
    }
}

/**
 * [RustTokenCursor] subclass performing `macro_rules!` template metavar collection,
 * RFC 3086 `${ignore(...)}` / `${index(...)}` / `${len(...)}` / `${count(...)}` evaluation,
 * and repetition-group substitution.
 */
private class MacroTemplateTokenCursor(
    trees: List<TokenTree>,
) : RustTokenCursor(trees) {
    fun substituteWithEnv(
        env: MacroMatchEnv,
        repIndexStack: List<Int> = emptyList(),
        repLenStack: List<Int> = emptyList(),
    ): TokenStream {
        val out = mutableListOf<TokenTree>()
        var i = 0
        while (i < size) {
            val tt = trees[i]
            if (punctCharAt(i) == '$' && i + 1 < size) {
                val varIdent = (trees[i + 1] as? TokenTree.Ident)?.value
                if (varIdent != null) {
                    val varName = varIdent.toString()
                    if (varName == "crate") {
                        out.add(TokenTree.Ident(PmIdent.new("crate", varIdent.span())))
                        i += 2
                        continue
                    }
                    val replacement = env.singles[varName]
                    if (replacement != null) {
                        out.addAll(replacement)
                        i += 2
                        continue
                    }
                }
                val braceGrp = groupAt(i + 1, Delimiter.Brace)
                if (braceGrp != null) {
                    val evaluated =
                        MacroTemplateTokenCursor(braceGrp.stream().toList())
                            .tryEvaluateMetavarExpr(env, repIndexStack, repLenStack)
                    if (evaluated != null) {
                        out.addAll(evaluated)
                        i += 2
                        continue
                    }
                }
                val parenGrp = groupAt(i + 1, Delimiter.Parenthesis)
                if (parenGrp != null) {
                    val adv = repetitionHeaderAdvance(i)
                    val sepToken = if (adv == 4) trees[i + 2] else null
                    val innerTrees = parenGrp.stream().toList()
                    val innerCursor = MacroTemplateTokenCursor(innerTrees)
                    var matchedGroup: RepetitionGroup? = null
                    val targetIterations =
                        if (env.repetitionGroups.isNotEmpty()) {
                            val directVars = innerCursor.collectTemplateMetavars(includeRepetitions = false)
                            val allVars = innerCursor.collectTemplateMetavars(includeRepetitions = true)
                            matchedGroup =
                                env.repetitionGroups.firstOrNull { grp ->
                                    directVars.isNotEmpty() && grp.directVars.intersect(directVars).isNotEmpty()
                                } ?: env.repetitionGroups.firstOrNull { grp ->
                                    allVars.isNotEmpty() && grp.allVars.intersect(allVars).isNotEmpty()
                                }
                            matchedGroup?.iterations.orEmpty()
                        } else {
                            env.repetitions
                        }
                    val nextLenStack = repLenStack + targetIterations.size
                    for ((repIdx, childEnv) in targetIterations.withIndex()) {
                        if (repIdx > 0 && sepToken != null) {
                            out.add(sepToken)
                        }
                        val mergedEnv = mergeRepetitionIterationEnv(env, childEnv, matchedGroup)
                        val innerSub =
                            innerCursor.substituteWithEnv(
                                mergedEnv,
                                repIndexStack = repIndexStack + repIdx,
                                repLenStack = nextLenStack,
                            )
                        out.addAll(innerSub.toList())
                    }
                    i += adv
                    continue
                }
            }
            if (tt is TokenTree.Group) {
                val inner =
                    MacroTemplateTokenCursor(tt.value.stream().toList())
                        .substituteWithEnv(env, repIndexStack, repLenStack)
                val newGroup = Group(tt.value.delimiter(), inner)
                newGroup.setSpan(tt.value.span())
                out.add(TokenTree.Group(newGroup))
            } else if (tt is TokenTree.Ident && tt.span().endExclusiveOffset() <= tt.span().startOffset()) {
                val kwSpan = env.matchedKeywordSpans[tt.value.toString()]
                out.add(if (kwSpan != null) tt.setSpan(kwSpan) else tt)
            } else {
                out.add(tt)
            }
            i++
        }
        return TokenStream.fromTokenTrees(out)
    }

    fun collectTemplateMetavars(includeRepetitions: Boolean): Set<String> {
        val vars = linkedSetOf<String>()
        var i = 0
        while (i < size) {
            if (punctCharAt(i) == '$' && i + 1 < size) {
                val name = identAt(i + 1)
                if (name != null) {
                    if (name != "crate") vars.add(name)
                    i += 2
                    continue
                }
                if (!includeRepetitions && groupAt(i + 1, Delimiter.Parenthesis) != null) {
                    i += repetitionHeaderAdvance(i)
                    continue
                }
                val braceGrp = groupAt(i + 1, Delimiter.Brace)
                if (braceGrp != null) {
                    val ignored = MacroTemplateTokenCursor(braceGrp.stream().toList()).extractIgnoredMetavarName()
                    if (ignored != null && ignored != "crate") {
                        vars.add(ignored)
                    }
                    i += 2
                    continue
                }
            }
            val grp = groupAt(i)
            if (grp != null) {
                vars.addAll(MacroTemplateTokenCursor(grp.stream().toList()).collectTemplateMetavars(includeRepetitions))
            }
            i++
        }
        return vars
    }

    private fun repetitionHeaderAdvance(dollarIdx: Int): Int {
        val c1 = punctCharAt(dollarIdx + 2)
        if (c1 == '*' || c1 == '+' || c1 == '?') return 3
        val t1 = trees.getOrNull(dollarIdx + 2)
        val c2 = punctCharAt(dollarIdx + 3)
        if ((t1 is TokenTree.Punct || t1 is TokenTree.Ident) && (c2 == '*' || c2 == '+')) {
            return 4
        }
        return 2
    }

    private fun extractIgnoredMetavarName(): String? {
        if (size != 2 || identAt(0) != "ignore") return null
        val argGroup = groupAt(1, Delimiter.Parenthesis) ?: return null
        val args = RustTokenCursor(argGroup.stream().toList())
        return when {
            args.size == 1 -> args.cleanIdentAt(0)
            args.size == 2 && args.punctCharAt(0) == '$' -> args.cleanIdentAt(1)
            else -> null
        }
    }

    private fun tryEvaluateMetavarExpr(
        env: MacroMatchEnv,
        repIndexStack: List<Int>,
        repLenStack: List<Int>,
    ): List<TokenTree>? {
        if (size != 2) return null
        val op = identAt(0) ?: return null
        val argGroup = groupAt(1, Delimiter.Parenthesis) ?: return null
        val args = RustTokenCursor(argGroup.stream().toList())
        return when (op) {
            "ignore" -> emptyList()
            "index" -> {
                val depth = parseOptionalDepthArg(args) ?: return null
                val idxVal = repIndexStack.getOrNull(repIndexStack.lastIndex - depth) ?: 0
                listOf(TokenTree.Literal(Literal.usizeUnsuffixed(idxVal.toULong())))
            }
            "len" -> {
                val depth = parseOptionalDepthArg(args) ?: return null
                val lenVal = repLenStack.getOrNull(repLenStack.lastIndex - depth) ?: 0
                listOf(TokenTree.Literal(Literal.usizeUnsuffixed(lenVal.toULong())))
            }
            "count" -> {
                var pos = 0
                if (args.punctCharAt(pos) == '$') pos++
                val targetVar = args.cleanIdentAt(pos) ?: return null
                pos++
                val maxDepth =
                    when {
                        pos == args.size -> null
                        pos + 2 == args.size && args.punctCharAt(pos) == ',' -> args.intLiteralAt(pos + 1) ?: return null
                        else -> return null
                    }
                val cnt = countMetavarOccurrences(env, targetVar, maxDepth)
                listOf(TokenTree.Literal(Literal.usizeUnsuffixed(cnt.toULong())))
            }
            else -> null
        }
    }

    companion object {
        private fun parseOptionalDepthArg(args: RustTokenCursor): Int? {
            if (args.size == 0) return 0
            if (args.size == 1) return args.intLiteralAt(0)
            return null
        }

        private fun countMetavarOccurrences(env: MacroMatchEnv, varName: String, remainingDepth: Int?): Int {
            if (remainingDepth != null && remainingDepth <= 0) {
                return if (varName in env.singles) 1 else 0
            }
            val matchedGrp =
                env.repetitionGroups.firstOrNull { varName in it.directVars }
                    ?: env.repetitionGroups.firstOrNull { varName in it.allVars }
            if (matchedGrp != null) {
                val nextDepth = remainingDepth?.minus(1)
                if (varName in matchedGrp.directVars && (nextDepth == null || nextDepth == 0)) {
                    return matchedGrp.iterations.size
                }
                return matchedGrp.iterations.sumOf { child -> countMetavarOccurrences(child, varName, nextDepth) }
            }
            if (env.repetitions.isNotEmpty()) {
                val nextDepth = remainingDepth?.minus(1)
                return env.repetitions.sumOf { child -> countMetavarOccurrences(child, varName, nextDepth) }
            }
            return if (varName in env.singles) 1 else 0
        }

        private fun mergeRepetitionIterationEnv(
            env: MacroMatchEnv,
            childEnv: MacroMatchEnv,
            matchedGroup: RepetitionGroup?,
        ): MacroMatchEnv {
            val mergedSingles = LinkedHashMap<String, List<TokenTree>>(env.singles)
            mergedSingles.putAll(childEnv.singles)
            val mergedKeywords = LinkedHashMap<String, Span>(env.matchedKeywordSpans)
            mergedKeywords.putAll(childEnv.matchedKeywordSpans)
            val mergedGroups = ArrayList<RepetitionGroup>(childEnv.repetitionGroups)
            for (parentGrp in env.repetitionGroups) {
                if (parentGrp !== matchedGroup && mergedGroups.none { it.allVars == parentGrp.allVars }) {
                    mergedGroups.add(parentGrp)
                }
            }
            return MacroMatchEnv(
                singles = mergedSingles,
                repetitions = childEnv.repetitions,
                repetitionGroups = mergedGroups,
                matchedKeywordSpans = mergedKeywords,
            )
        }
    }
}

private var crateMacroDefsCache: Map<String, Map<String, MacroRulesDef>> = emptyMap()

private fun collectExternalMacroDefs(sourceText: String): Map<String, MacroRulesDef> {
    if (!sourceText.contains("macro_rules") && !sourceText.contains("macro ")) {
        return emptyMap()
    }
    crateMacroDefsCache[sourceText]?.let { return it }
    val defs = linkedMapOf<String, MacroRulesDef>()
    val trees = lexRustTokenTrees(sourceText)
    if (trees.isNotEmpty()) {
        collectMacroRulesFromTokenTrees(trees, defs)
    }
    val result = defs.toMap()
    if (crateMacroDefsCache.size >= 64) {
        crateMacroDefsCache = mapOf(sourceText to result)
    } else {
        crateMacroDefsCache = crateMacroDefsCache + (sourceText to result)
    }
    return result
}

internal fun tryExtractDeclarativeMacro2FromTokens(
    trees: List<TokenTree>,
    skipCfgTest: Boolean,
): MacroRulesDef? = MacroDeclarativeTokenScanner(trees).tryExtractDeclarativeMacro2(skipCfgTest)

internal fun collectMacroRulesFromTokenTrees(
    trees: List<TokenTree>,
    out: MutableMap<String, MacroRulesDef>,
) {
    MacroDeclarativeTokenScanner(trees).collectMacroRules(out)
}

public fun extractMacroUseModuleNames(rustSource: String): List<String> {
    if (!rustSource.contains("macro_use")) return emptyList()
    val trees = lexRustTokenTrees(rustSource)
    if (trees.isEmpty()) return emptyList()
    return MacroDeclarativeTokenScanner(trees).extractMacroUseModules()
}

private class MacroDeclarativeTokenScanner(
    trees: List<TokenTree>,
) : RustTokenCursor(trees) {
    fun tryExtractDeclarativeMacro2(skipCfgTest: Boolean): MacroRulesDef? {
        val attrGroups = mutableListOf<List<TokenTree>>()
        var idx = 0
        while (true) {
            val afterAttr = consumeAttribute(idx, attrGroups, allowInnerAttr = false) ?: break
            idx = afterAttr
        }
        if (hasSkippedMacroAttrs(attrGroups, skipCfgTest)) return null
        idx = copyOptionalPubVisibility(idx)
        return parseDeclarativeMacro2At(idx)?.first
    }

    fun collectMacroRules(out: MutableMap<String, MacroRulesDef>) {
        val pendingAttrs = mutableListOf<List<TokenTree>>()
        var i = 0
        while (i < size) {
            val afterAttr = consumeAttribute(i, pendingAttrs)
            if (afterAttr != null) {
                i = afterAttr
                continue
            }
            val afterVis = copyOptionalPubVisibility(i)
            if (identAt(i) == "macro_rules" &&
                punctCharAt(i + 1) == '!' &&
                trees.getOrNull(i + 2) is TokenTree.Ident
            ) {
                val macroName = cleanIdentAt(i + 2)
                val bodyGroup = groupAt(i + 3)
                if (macroName != null && bodyGroup != null) {
                    registerParsedMacroDef(
                        macroName = macroName,
                        branches = MacroRulesCollector.parseMacroRulesBranches(bodyGroup.stream()),
                        pendingAttrs = pendingAttrs,
                        out = out,
                    )
                    i += 4
                    if (punctCharAt(i) == ';') i++
                    pendingAttrs.clear()
                    continue
                }
            }
            val declMacro2 = parseDeclarativeMacro2At(afterVis)
            if (declMacro2 != null) {
                val (parsedDef, nextIdx) = declMacro2
                if (parsedDef != null) {
                    registerParsedMacroDef(parsedDef.name, parsedDef.branches, pendingAttrs, out)
                }
                i = nextIdx
                pendingAttrs.clear()
                continue
            }
            if (identAt(afterVis) == "mod" && trees.getOrNull(afterVis + 1) is TokenTree.Ident) {
                val modBrace = groupAt(afterVis + 2, Delimiter.Brace)
                if (modBrace != null) {
                    if (!hasSkippedMacroAttrs(pendingAttrs, skipCfgTest = true)) {
                        MacroDeclarativeTokenScanner(modBrace.stream().toList()).collectMacroRules(out)
                    }
                    i = afterVis + 3
                    pendingAttrs.clear()
                    continue
                }
                if (punctCharAt(afterVis + 2) == ';') {
                    i = afterVis + 3
                    pendingAttrs.clear()
                    continue
                }
            }
            if (punctCharAt(i) == ';' || groupAt(i, Delimiter.Brace) != null) {
                pendingAttrs.clear()
            }
            i++
        }
    }

    fun extractMacroUseModules(): List<String> {
        val names = mutableListOf<String>()
        var i = 0
        while (i < size) {
            val attrGrp = if (punctCharAt(i) == '#') groupAt(i + 1, Delimiter.Bracket) else null
            if (attrGrp != null) {
                val attrFirstIdent = RustTokenCursor(attrGrp.stream().toList()).identAt(0)
                if (attrFirstIdent == "macro_use") {
                    var j = i + 2
                    while (punctCharAt(j) == '#' && groupAt(j + 1, Delimiter.Bracket) != null) {
                        j += 2
                    }
                    j = copyOptionalPubVisibility(j)
                    val modName = if (identAt(j) == "mod") cleanIdentAt(j + 1) else null
                    if (modName != null && punctCharAt(j + 2) == ';') {
                        names += modName
                        i = j + 3
                        continue
                    }
                }
            }
            i++
        }
        return names.distinct()
    }

    fun collectHeaderSpans(fallbackSpan: Span): List<Span> {
        val byStart = linkedMapOf<Int, Span>()
        var prevIdentText: String? = null
        var angleDepth = 0
        for (idx in 0 until size) {
            val tt = trees[idx]
            when (tt) {
                is TokenTree.Group -> {
                    when (tt.value.delimiter()) {
                        Delimiter.Brace -> break
                        Delimiter.Parenthesis -> if (angleDepth == 0 && prevIdentText != "pub") break
                        else -> {}
                    }
                    prevIdentText = null
                }
                is TokenTree.Punct -> {
                    val ch = tt.value.asChar()
                    if (ch == '<') {
                        angleDepth++
                    } else if (ch == '>' && angleDepth > 0) {
                        angleDepth--
                    } else if (angleDepth == 0 &&
                        ((isSingleColonAt(idx) && prevIdentText != null) || ch == '=' || ch == ';')
                    ) {
                        break
                    }
                    prevIdentText = null
                }
                is TokenTree.Ident -> prevIdentText = tt.value.toString()
                is TokenTree.Literal -> prevIdentText = null
            }
            val sp = tt.span()
            val start = sp.startOffset()
            if (sp.endExclusiveOffset() > start && start >= 0 && start !in byStart) {
                byStart[start] = sp
            }
        }
        if (byStart.isEmpty()) {
            val start = fallbackSpan.startOffset()
            if (fallbackSpan.endExclusiveOffset() > start && start >= 0) {
                byStart[start] = fallbackSpan
            }
        }
        return byStart.values.sortedBy { it.startOffset() }
    }

    private fun parseDeclarativeMacro2At(macroKwIdx: Int): Pair<MacroRulesDef?, Int>? {
        if (identAt(macroKwIdx) != "macro") return null
        val macroName = cleanIdentAt(macroKwIdx + 1) ?: return null
        val firstGroup = groupAt(macroKwIdx + 2) ?: return null
        return when (firstGroup.delimiter()) {
            Delimiter.Parenthesis -> {
                val bodyGrp = groupAt(macroKwIdx + 3, Delimiter.Brace) ?: return null
                val branch = MacroRulesCollector.buildMacroRuleBranch(firstGroup.stream(), bodyGrp.stream())
                MacroRulesDef(macroName, listOf(branch)) to (macroKwIdx + 4)
            }
            Delimiter.Brace -> {
                val branches = MacroRulesCollector.parseMacroRulesBranches(firstGroup.stream())
                val def = if (branches.isNotEmpty()) MacroRulesDef(macroName, branches) else null
                def to (macroKwIdx + 3)
            }
            Delimiter.Bracket, Delimiter.None -> null
        }
    }

    private fun registerParsedMacroDef(
        macroName: String,
        branches: List<MacroRuleBranch>,
        pendingAttrs: List<List<TokenTree>>,
        out: MutableMap<String, MacroRulesDef>,
    ) {
        if (!hasSkippedMacroAttrs(pendingAttrs, skipCfgTest = true) &&
            macroName != "offset_of" &&
            macroName !in out &&
            branches.isNotEmpty() &&
            !isRawPointerLiteralMacroBranches(branches)
        ) {
            out[macroName] = MacroRulesDef(macroName, branches)
        }
    }

    companion object {
        fun endsWithAsPtrCall(rawTrees: List<TokenTree>): Boolean {
            var curTrees = rawTrees
            while (curTrees.size == 1 && curTrees[0] is TokenTree.Group) {
                val g = (curTrees[0] as TokenTree.Group).value
                val delim = g.delimiter()
                if (delim == Delimiter.Brace || delim == Delimiter.Parenthesis || delim == Delimiter.None) {
                    curTrees = g.stream().clone().toList()
                } else {
                    break
                }
            }
            val cursor = RustTokenCursor(curTrees)
            if (cursor.size < 4) return false
            val callParens = cursor.groupAt(cursor.size - 1, Delimiter.Parenthesis) ?: return false
            if (!callParens.stream().isEmpty()) return false
            val methodIdent = cursor.identAt(cursor.size - 2) ?: return false
            return cursor.punctCharAt(cursor.size - 3) == '.' && (methodIdent == "as_ptr" || methodIdent == "as_mut_ptr")
        }

        private fun hasSkippedMacroAttrs(
            attrGroups: List<List<TokenTree>>,
            skipCfgTest: Boolean,
        ): Boolean {
            for (attrTokens in attrGroups) {
                val attrCursor = RustTokenCursor(attrTokens)
                val firstIdent = attrCursor.identAt(0)
                if (firstIdent == "rustc_builtin_macro") return true
                val cfgParen = if (attrCursor.size == 2 && firstIdent == "cfg") attrCursor.groupAt(1, Delimiter.Parenthesis) else null
                if (cfgParen != null) {
                    val inner = cfgParen.stream().toList()
                    if (isDocOnlyCfgPredicateTokens(inner)) return true
                    if (skipCfgTest && inner.size == 1 && RustTokenCursor(inner).identAt(0) == "test") {
                        return true
                    }
                }
            }
            return false
        }
    }
}

internal data class ExpandedFileMacroRules(
    val file: File,
    val definitions: Map<String, MacroRulesDef>,
    val macroWrapperSpansByTargetOffset: Map<Int, List<Span>> = emptyMap(),
)

/**
 * Extracts source-backed header token spans directly from [node]'s [TokenStream] (stopping before
 * declaration bodies, parameter groups, or initializer separators), avoiding AST-variant dispatch
 * and skipping synthesized zero-width `Span.callSite()` tokens injected by `macro_rules!` templates.
 */
internal fun collectHeaderSourceSpans(node: ToTokens): List<Span> {
    val safeNode =
        when (node) {
            is Item.Verbatim -> Item.Verbatim(node.tokens.clone())
            is ImplItem.Verbatim -> ImplItem.Verbatim(node.tokens.clone())
            is Stmt -> node.deepCopy()
            is Expr -> node.deepCopy()
            else -> node
        }
    val trees = safeNode.toTokenStream().toList()
    val fallbackSpan = trees.firstOrNull()?.span() ?: Span.callSite()
    return MacroDeclarativeTokenScanner(trees).collectHeaderSpans(fallbackSpan)
}

internal fun expandFileMacroRules(
    file: File,
    crateMacroSources: List<String> = emptyList(),
): ExpandedFileMacroRules {
    val mergedDefs = linkedMapOf<String, MacroRulesDef>()
    for (macroSrc in crateMacroSources) {
        mergedDefs.putAll(collectExternalMacroDefs(macroSrc))
    }
    val fileCollector = MacroRulesCollector(skipCfgTestModules = false, skipBlockLocals = true)
    fileCollector.visitFile(file)
    mergedDefs.putAll(fileCollector.definitions)
    val expander = MacroRulesFileExpander(mergedDefs)
    val expandedFile = expander.foldFile(file)
    return ExpandedFileMacroRules(
        file = expandedFile,
        definitions = mergedDefs,
        macroWrapperSpansByTargetOffset = expander.macroWrapperSpansByTargetOffset,
    )
}
