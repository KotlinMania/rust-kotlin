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
import io.github.kotlinmania.syn.FileParse
import io.github.kotlinmania.syn.ImplItem
import io.github.kotlinmania.syn.Item
import io.github.kotlinmania.syn.Macro
import io.github.kotlinmania.syn.Meta
import io.github.kotlinmania.syn.ModContent
import io.github.kotlinmania.syn.Stmt
import io.github.kotlinmania.syn.SynType
import io.github.kotlinmania.syn.gen.Fold
import io.github.kotlinmania.syn.gen.Visit
import io.github.kotlinmania.syn.parse2
import io.github.kotlinmania.syn.span
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

private data class CfgSelectArm(
    val predicateTokens: List<TokenTree>?,
    val bodyTokens: List<TokenTree>,
)

private val UNSUPPORTED_RUST_TARGET_OS =
    setOf(
        "hermit",
        "motor",
        "solid_asp3",
        "teeos",
        "uefi",
        "vexos",
        "xous",
        "zkvm",
        "l4re",
        "nuttx",
        "psp",
        "psx",
        "trusty",
        "vita",
        "vxworks",
        "espidf",
        "horizon",
        "hurd",
        "cygwin",
        "aix",
        "haiku",
        "illumos",
        "netbsd",
        "openbsd",
        "dragonfly",
        "freebsd",
        "solaris",
        "redox",
        "fuchsia",
        "nto",
        "rtems",
        "emscripten",
        "wasi_p3",
        "lynxos178",
        "qurt",
        "cuda",
        "nvptx",
        "amdgpu",
    )

private val UNSUPPORTED_RUST_TARGET_ENV =
    setOf(
        "sgx",
        "relibc",
        "ohos",
        "uclibc",
        "newlib",
        "p2",
        "p3",
    )

private val UNSUPPORTED_RUST_TARGET_VENDOR =
    setOf(
        "fortanix",
        "kmc",
        "nintendo",
        "sony",
        "uwp",
        "win7",
    )

private val PRUNED_UNSUPPORTED_TARGET_PATH_SEGMENTS =
    UNSUPPORTED_RUST_TARGET_OS +
        UNSUPPORTED_RUST_TARGET_ENV +
        UNSUPPORTED_RUST_TARGET_VENDOR +
        setOf(
            "fortanix_sgx",
            "solid",
            "itron",
            "wasip2",
            "wasip3",
            "wali",
            "miri",
            "switch",
        )

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

private fun parseCfgSelectArms(innerTrees: List<TokenTree>): List<CfgSelectArm>? {
    if (innerTrees.isEmpty()) return emptyList()
    val arms = mutableListOf<CfgSelectArm>()
    var idx = 0
    while (idx < innerTrees.size) {
        var arrowIdx = -1
        var scan = idx
        while (scan + 1 < innerTrees.size) {
            val eq = (innerTrees[scan] as? TokenTree.Punct)?.value
            val gt = (innerTrees[scan + 1] as? TokenTree.Punct)?.value
            if (eq?.asChar() == '=' && eq.spacing() == Spacing.Joint && gt?.asChar() == '>') {
                arrowIdx = scan
                break
            }
            scan++
        }
        if (arrowIdx <= idx) return null
        val predSlice = innerTrees.subList(idx, arrowIdx)
        val isWildcard =
            predSlice.size == 1 && (predSlice[0] as? TokenTree.Ident)?.value?.toString() == "_"
        val bodyStart = arrowIdx + 2
        if (bodyStart > innerTrees.size) return null
        val bodyTok = innerTrees.getOrNull(bodyStart)
        val (bodyTokens, nextIdx) =
            if (bodyTok is TokenTree.Group && bodyTok.value.delimiter() == Delimiter.Brace) {
                val afterGrp = bodyStart + 1
                val nextAfter =
                    if (afterGrp < innerTrees.size &&
                        (innerTrees[afterGrp] as? TokenTree.Punct)?.value?.asChar() == ','
                    ) {
                        afterGrp + 1
                    } else {
                        afterGrp
                    }
                bodyTok.value.stream().toList() to nextAfter
            } else {
                var commaIdx = bodyStart
                while (commaIdx < innerTrees.size &&
                    (innerTrees[commaIdx] as? TokenTree.Punct)?.value?.asChar() != ','
                ) {
                    commaIdx++
                }
                val slice = innerTrees.subList(bodyStart, commaIdx)
                val nextAfter = if (commaIdx < innerTrees.size) commaIdx + 1 else commaIdx
                slice to nextAfter
            }
        arms +=
            CfgSelectArm(
                predicateTokens = if (isWildcard) null else predSlice,
                bodyTokens = bodyTokens,
            )
        idx = nextIdx
    }
    return arms
}

internal fun partitionCfgSelectBranchBodies(innerTrees: List<TokenTree>): List<Pair<List<TokenTree>, Boolean>>? {
    val arms = parseCfgSelectArms(innerTrees) ?: return null
    val result = mutableListOf<Pair<List<TokenTree>, Boolean>>()
    var guaranteedWinner = false
    for (arm in arms) {
        if (guaranteedWinner) {
            result += arm.bodyTokens to false
            continue
        }
        val pred = arm.predicateTokens
        if (pred == null) {
            guaranteedWinner = true
            result += arm.bodyTokens to true
            continue
        }
        val tri = evalCfgTriState(pred, forRuntimeCfgMacro = false)
        when (tri) {
            true -> {
                guaranteedWinner = true
                result += arm.bodyTokens to true
            }
            false -> result += arm.bodyTokens to false
            null -> result += arm.bodyTokens to true
        }
    }
    return result
}

private fun chooseSingleActiveCfgSelectBody(innerTrees: List<TokenTree>): List<TokenTree>? {
    val arms = parseCfgSelectArms(innerTrees) ?: return null
    var firstNonFalse: CfgSelectArm? = null
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

internal fun selectActiveCfgSelectBodies(innerTrees: List<TokenTree>): List<List<TokenTree>>? {
    val arms = parseCfgSelectArms(innerTrees) ?: return null
    val candidates = mutableListOf<Pair<CfgSelectArm, Boolean>>()
    for (arm in arms) {
        val pred = arm.predicateTokens
        if (pred == null) {
            candidates += arm to true
            break
        }
        when (evalCfgTriState(pred, forRuntimeCfgMacro = false)) {
            true -> return listOf(arm.bodyTokens)
            false -> Unit
            null -> candidates += arm to false
        }
    }
    if (candidates.isEmpty()) return emptyList()
    val hasNonWildcard = candidates.any { !it.second }
    val seenKeys = linkedSetOf<String>()
    val keptBodies = mutableListOf<List<TokenTree>>()
    for ((arm, isWildcard) in candidates) {
        if (arm.bodyTokens.isEmpty()) continue
        val parsed =
            parse2(FileParse::parse, TokenStream.fromTokenTrees(arm.bodyTokens))
                .getOrNull()
                ?.let(RustNextAstNormalizer::foldFile)
        if (parsed == null) {
            if (!isWildcard || !hasNonWildcard) {
                keptBodies += arm.bodyTokens
            }
            continue
        }
        val armKeys = parsed.items.mapNotNull(::itemDeclarationKey)
        if (isWildcard && hasNonWildcard) {
            val isUnsupportedFallbackMod =
                "mod:unsupported" in armKeys &&
                    seenKeys.any { it.startsWith("mod:") }
            val allKeysAlreadySeen =
                armKeys.isNotEmpty() && armKeys.all { it in seenKeys }
            if (isUnsupportedFallbackMod || allKeysAlreadySeen) {
                continue
            }
        }
        if (armKeys.isNotEmpty() && armKeys.all { it in seenKeys }) {
            continue
        }
        seenKeys += armKeys
        keptBodies += arm.bodyTokens
    }
    return keptBodies
}

private fun selectActiveCfgSelectImplItems(innerTrees: List<TokenTree>): List<ImplItem>? {
    val arms = parseCfgSelectArms(innerTrees) ?: return null
    val candidates = mutableListOf<Pair<CfgSelectArm, Boolean>>()
    for (arm in arms) {
        val pred = arm.predicateTokens
        if (pred == null) {
            candidates += arm to true
            break
        }
        when (evalCfgTriState(pred, forRuntimeCfgMacro = false)) {
            true -> {
                val wrapped = wrapImplDummyStream(TokenStream.fromTokenTrees(arm.bodyTokens))
                val dummy =
                    parse2(FileParse::parse, wrapped)
                        .getOrNull()
                        ?.let(RustNextAstNormalizer::foldFile)
                        ?.items
                        ?.singleOrNull() as? Item.Impl ?: return null
                return dummy.items
            }
            false -> Unit
            null -> candidates += arm to false
        }
    }
    if (candidates.isEmpty()) return emptyList()
    val hasNonWildcard = candidates.any { !it.second }
    val seenKeys = linkedSetOf<String>()
    val collected = mutableListOf<ImplItem>()
    for ((arm, isWildcard) in candidates) {
        if (arm.bodyTokens.isEmpty()) continue
        val wrapped = wrapImplDummyStream(TokenStream.fromTokenTrees(arm.bodyTokens))
        val dummy =
            parse2(FileParse::parse, wrapped)
                .getOrNull()
                ?.let(RustNextAstNormalizer::foldFile)
                ?.items
                ?.singleOrNull() as? Item.Impl ?: return null
        val armKeys = dummy.items.mapNotNull(::implItemDeclarationKey)
        if (isWildcard && hasNonWildcard && armKeys.isNotEmpty() && armKeys.all { it in seenKeys }) {
            continue
        }
        if (armKeys.isNotEmpty() && armKeys.all { it in seenKeys }) {
            continue
        }
        seenKeys += armKeys
        collected += dummy.items
    }
    return collected
}

private fun wrapImplDummyStream(inner: TokenStream): TokenStream {
    val prefix = TokenStream.fromString("impl Dummy").getOrThrow().toList()
    val group = TokenTree.Group(Group(Delimiter.Brace, inner))
    return TokenStream.fromTokenTrees(prefix + group)
}

private val ARCHS_16_OR_32_BIT =
    setOf(
        "avr",
        "msp430",
        "arm",
        "x86",
        "mips",
        "mips32r6",
        "powerpc",
        "riscv32",
        "sparc",
        "csky",
        "hexagon",
        "m68k",
        "xtensa",
        "wasm32",
    )

private val ARCHS_64_BIT =
    setOf(
        "x86_64",
        "aarch64",
        "mips64",
        "mips64r6",
        "powerpc64",
        "riscv64",
        "s390x",
        "sparc64",
        "loongarch64",
        "wasm64",
    )

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

private fun extractCfgPredicateKey(keyTokens: List<TokenTree>): String? {
    if (keyTokens.isEmpty() || keyTokens.size % 3 != 1) return null
    val segments = mutableListOf<String>()
    var idx = 0
    while (idx < keyTokens.size) {
        val segIdent = (keyTokens[idx] as? TokenTree.Ident)?.value?.toString()?.removePrefix("r#") ?: return null
        segments += segIdent
        if (idx + 1 < keyTokens.size) {
            val c1 = (keyTokens[idx + 1] as? TokenTree.Punct)?.value
            val c2 = (keyTokens[idx + 2] as? TokenTree.Punct)?.value
            if (c1?.asChar() != ':' || c1.spacing() != Spacing.Joint || c2?.asChar() != ':') {
                return null
            }
        }
        idx += 3
    }
    return segments.joinToString("::")
}

internal fun evalCfgTriState(tokens: List<TokenTree>, forRuntimeCfgMacro: Boolean): Boolean? {
    val profile = TranslationSession.cfgTargetProfile
    val relDir = if (profile.useRelativeDirPlatformHints) TranslationSession.currentRelativeSourceDir else null
    if (tokens.size == 2) {
        val fnIdent = (tokens[0] as? TokenTree.Ident)?.value?.toString()?.removePrefix("r#")
        val grp = tokens[1] as? TokenTree.Group
        if (grp != null && grp.value.delimiter() == Delimiter.Parenthesis) {
            val innerStream = grp.value.stream()
            val clauses = MacroRulesFileExpander.splitTopLevelCommaClauses(innerStream.clone().toList())
            when (fnIdent) {
                "all" -> {
                    var allTrue = true
                    for (clause in clauses) {
                        val sub = evalCfgTriState(clause, forRuntimeCfgMacro)
                        if (sub == false) return false
                        if (sub != true) allTrue = false
                    }
                    return if (allTrue) true else null
                }
                "any" -> {
                    var allFalse = true
                    for (clause in clauses) {
                        val sub = evalCfgTriState(clause, forRuntimeCfgMacro)
                        if (sub == true) return true
                        if (sub != false) allFalse = false
                    }
                    return if (allFalse) false else null
                }
                "not" -> {
                    if (clauses.size == 1) {
                        return evalCfgTriState(clauses[0], forRuntimeCfgMacro)?.not()
                    }
                }
            }
        }
    }
    if (tokens.size >= 3) {
        val eq = (tokens[tokens.size - 2] as? TokenTree.Punct)?.value?.asChar()
        val litToken = tokens.last() as? TokenTree.Literal
        val key = if (eq == '=' && litToken != null) extractCfgPredicateKey(tokens.subList(0, tokens.size - 2)) else null
        val litText = litToken?.let(::unquoteCfgStringLiteral)
        if (eq == '=' && key != null && litText != null) {
            return when (key) {
                "target_pointer_width" -> litText.toIntOrNull()?.let { it == profile.pointerWidth }
                "target_endian" -> litText == profile.targetEndian
                "target_arch" -> {
                    if (profile.targetArch != null) {
                        litText == profile.targetArch
                    } else if (dirContainsSegment(relDir, litText)) {
                        true
                    } else if (profile.pointerWidth == 64 && litText in ARCHS_16_OR_32_BIT) {
                        false
                    } else if (profile.pointerWidth in setOf(16, 32) && litText in ARCHS_64_BIT) {
                        false
                    } else {
                        resolveUnknownCfgOutcome(profile, forRuntimeCfgMacro)
                    }
                }
                "target_has_atomic", "target_has_atomic_load_store", "target_has_atomic_equal_alignment" -> {
                    when {
                        profile.pointerWidth >= 64 && litText in setOf("8", "16", "32", "64", "ptr") -> true
                        profile.pointerWidth == 32 && litText in setOf("8", "16", "32", "ptr") -> true
                        profile.pointerWidth == 16 && litText in setOf("8", "16", "ptr") -> true
                        else -> resolveUnknownCfgOutcome(profile, forRuntimeCfgMacro)
                    }
                }
                "target_os" -> {
                    profile.targetOs?.let { litText == it }
                        ?: if (litText in UNSUPPORTED_RUST_TARGET_OS) {
                            false
                        } else if (dirContainsSegment(relDir, litText)) {
                            true
                        } else {
                            resolveUnknownCfgOutcome(profile, forRuntimeCfgMacro)
                        }
                }
                "target_env" -> {
                    profile.targetEnv?.let { litText == it }
                        ?: if (litText in UNSUPPORTED_RUST_TARGET_ENV) {
                            false
                        } else if (dirContainsSegment(relDir, litText)) {
                            true
                        } else {
                            resolveUnknownCfgOutcome(profile, forRuntimeCfgMacro)
                        }
                }
                "target_vendor" -> {
                    profile.targetVendor?.let { litText == it }
                        ?: if (litText in UNSUPPORTED_RUST_TARGET_VENDOR) {
                            false
                        } else if (dirContainsSegment(relDir, litText)) {
                            true
                        } else {
                            resolveUnknownCfgOutcome(profile, forRuntimeCfgMacro)
                        }
                }
                "target_family" -> {
                    profile.targetFamilies?.let { litText in it }
                        ?: if (dirContainsSegment(relDir, litText)) {
                            true
                        } else {
                            resolveUnknownCfgOutcome(profile, forRuntimeCfgMacro)
                        }
                }
                "feature" -> {
                    val kvEntry = "feature=$litText"
                    val quotedKvEntry = "feature=\"$litText\""
                    when {
                        kvEntry in profile.disabledCfgFlags || quotedKvEntry in profile.disabledCfgFlags -> false
                        kvEntry in profile.enabledCfgFlags || quotedKvEntry in profile.enabledCfgFlags -> true
                        profile.enabledFeatures != null -> litText in profile.enabledFeatures
                        else -> resolveUnknownCfgOutcome(profile, forRuntimeCfgMacro)
                    }
                }
                else -> {
                    val kvEntry = "$key=$litText"
                    val quotedKvEntry = "$key=\"$litText\""
                    when {
                        kvEntry in profile.enabledCfgFlags || quotedKvEntry in profile.enabledCfgFlags -> true
                        kvEntry in profile.disabledCfgFlags || quotedKvEntry in profile.disabledCfgFlags -> false
                        else -> resolveUnknownCfgOutcome(profile, forRuntimeCfgMacro)
                    }
                }
            }
        }
    }
    val ident = extractCfgPredicateKey(tokens) ?: return null
    if (ident == "true") return true
    if (ident == "false") return false
    if (ident in profile.enabledCfgFlags) return true
    if (ident in profile.disabledCfgFlags) return false
    if (ident in setOf("miri", "doc", "rustdoc", "doctest", "bootstrap")) return false
    if (ident == "test") return if (forRuntimeCfgMacro) false else null
    if (ident == "unix" || ident == "windows" || ident == "wasm") {
        profile.targetFamilies?.let { return ident in it }
        if (dirContainsSegment(relDir, ident)) return true
    }
    return resolveUnknownCfgOutcome(profile, forRuntimeCfgMacro)
}

internal fun isRawPointerLiteralMacroBranches(branches: List<MacroRuleBranch>): Boolean {
    if (branches.isEmpty()) return false
    return branches.all { branch ->
        !branch.isRepetition &&
            branch.paramNames.size == 1 &&
            tokenTreesEndWithAsPtrCall(branch.bodyTokens.clone().toList())
    }
}

private fun tokenTreesEndWithAsPtrCall(rawTrees: List<TokenTree>): Boolean {
    var trees = rawTrees
    while (trees.size == 1 && trees[0] is TokenTree.Group) {
        val g = (trees[0] as TokenTree.Group).value
        if (g.delimiter() == Delimiter.Brace || g.delimiter() == Delimiter.Parenthesis || g.delimiter() == Delimiter.None) {
            trees = g.stream().clone().toList()
        } else {
            break
        }
    }
    if (trees.size < 4) return false
    val last = trees.last() as? TokenTree.Group ?: return false
    if (last.value.delimiter() != Delimiter.Parenthesis || !last.value.stream().isEmpty()) return false
    val methodIdent = (trees[trees.size - 2] as? TokenTree.Ident)?.value?.toString() ?: return false
    val dotPunct = (trees[trees.size - 3] as? TokenTree.Punct)?.value?.asChar() ?: return false
    return dotPunct == '.' && (methodIdent == "as_ptr" || methodIdent == "as_mut_ptr")
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
        if (skipCfgTestModules && hasCfgTestModAttribute(i.attrs)) {
            return
        }
        if (isDocOnlyCfgAttribute(i.attrs)) {
            return
        }
        super.visitItemMod(i)
    }

    override fun visitItemMacro(i: Item.Macro) {
        if (isDocOnlyCfgAttribute(i.attrs)) {
            return
        }
        if (i.mac.path.isIdent("macro_rules")) {
            val macroName = i.ident?.toString()?.removePrefix("r#")
            if (macroName != null && macroName != "offset_of" && macroName !in definitions) {
                val branches = parseMacroRulesBranches(i.mac.tokens.clone())
                if (branches.isNotEmpty() && !isRawPointerLiteralMacroBranches(branches)) {
                    definitions[macroName] = MacroRulesDef(macroName, branches)
                }
            }
        }
        super.visitItemMacro(i)
    }

    override fun visitItem(i: Item) {
        if (i is Item.Verbatim) {
            val extracted = tryExtractDeclarativeMacro2FromTokens(i.tokens.clone().toList(), skipCfgTestModules)
            if (extracted != null && extracted.name != "offset_of" && extracted.name !in definitions) {
                if (!isRawPointerLiteralMacroBranches(extracted.branches)) {
                    definitions[extracted.name] = extracted
                }
            }
        }
        super.visitItem(i)
    }

    companion object {
        internal fun buildMacroRuleBranch(patternStream: TokenStream, bodyStream: TokenStream): MacroRuleBranch {
            val patternAst = parseMacroPattern(patternStream.clone())
            val params = extractPatternParams(patternStream.clone())
            val isRep = hasRepetitionPattern(patternStream.clone())
            return MacroRuleBranch(patternAst, params, isRep, stripTemplateSpans(bodyStream))
        }

        internal fun parseMacroRulesBranches(tokens: TokenStream): List<MacroRuleBranch> {
            val list = tokens.clone().toList()
            val branches = mutableListOf<MacroRuleBranch>()
            var idx = 0
            while (idx < list.size) {
                val patternGroup = list[idx] as? TokenTree.Group
                if (patternGroup == null) {
                    idx++
                    continue
                }
                if (idx + 3 < list.size) {
                    val eqPunct = list[idx + 1] as? TokenTree.Punct
                    val gtPunct = list[idx + 2] as? TokenTree.Punct
                    val bodyGroup = list[idx + 3] as? TokenTree.Group
                    if (eqPunct?.value?.asChar() == '=' &&
                        gtPunct?.value?.asChar() == '>' &&
                        bodyGroup != null
                    ) {
                        branches.add(buildMacroRuleBranch(patternGroup.value.stream(), bodyGroup.value.stream()))
                        idx += 4
                        val sep = (list.getOrNull(idx) as? TokenTree.Punct)?.value?.asChar()
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

        internal fun parseMacroPattern(patternStream: TokenStream): List<MacroPat> {
            val trees = patternStream.clone().toList()
            val out = mutableListOf<MacroPat>()
            var i = 0
            while (i < trees.size) {
                val tt = trees[i]
                if (tt is TokenTree.Punct && tt.value.asChar() == '$' && i + 1 < trees.size) {
                    val next = trees[i + 1]
                    if (next is TokenTree.Ident) {
                        val name = next.value.toString()
                        var frag = "tt"
                        i += 2
                        if (i + 1 < trees.size &&
                            (trees[i] as? TokenTree.Punct)?.value?.asChar() == ':' &&
                            trees[i + 1] is TokenTree.Ident
                        ) {
                            frag = (trees[i + 1] as TokenTree.Ident).value.toString()
                            i += 2
                        }
                        out.add(MacroPat.Metavar(name, frag))
                        continue
                    } else if (next is TokenTree.Group && next.value.delimiter() == Delimiter.Parenthesis) {
                        val innerPat = parseMacroPattern(next.value.stream())
                        i += 2
                        var sep: MacroPat.LiteralToken? = null
                        var op = '*'
                        if (i < trees.size) {
                            val t1 = trees[i]
                            if (t1 is TokenTree.Punct &&
                                (t1.value.asChar() == '*' || t1.value.asChar() == '+' || t1.value.asChar() == '?')
                            ) {
                                op = t1.value.asChar()
                                i++
                            } else if (i + 1 < trees.size && trees[i + 1] is TokenTree.Punct) {
                                val p2 = (trees[i + 1] as TokenTree.Punct).value.asChar()
                                if (p2 == '*' || p2 == '+') {
                                    when (t1) {
                                        is TokenTree.Punct -> {
                                            sep = MacroPat.LiteralToken(t1.value.asChar().toString(), isPunct = true)
                                            op = p2
                                            i += 2
                                        }
                                        is TokenTree.Ident -> {
                                            sep = MacroPat.LiteralToken(t1.value.toString(), isPunct = false)
                                            op = p2
                                            i += 2
                                        }
                                        else -> {}
                                    }
                                }
                            }
                        }
                        out.add(MacroPat.Repetition(innerPat, sep, op))
                        continue
                    }
                }
                when (tt) {
                    is TokenTree.Group -> {
                        out.add(MacroPat.GroupPat(tt.value.delimiter(), parseMacroPattern(tt.value.stream())))
                    }
                    is TokenTree.Punct -> {
                        out.add(MacroPat.LiteralToken(tt.value.asChar().toString(), isPunct = true))
                    }
                    is TokenTree.Ident -> {
                        out.add(MacroPat.LiteralToken(tt.value.toString(), isPunct = false))
                    }
                    is TokenTree.Literal -> {
                        out.add(MacroPat.LiteralToken(tt.value.toString(), isPunct = false))
                    }
                }
                i++
            }
            return out
        }

        private fun hasRepetitionPattern(patternStream: TokenStream): Boolean {
            val trees = patternStream.clone().toList()
            for (i in 0 until trees.size - 1) {
                val tt = trees[i]
                val next = trees[i + 1]
                if (tt is TokenTree.Punct && tt.value.asChar() == '$' &&
                    next is TokenTree.Group && next.value.delimiter() == Delimiter.Parenthesis
                ) {
                    return true
                }
            }
            return false
        }

        private fun extractPatternParams(patternStream: TokenStream): List<String> {
            val params = mutableListOf<String>()
            val trees = patternStream.clone().toList()
            var i = 0
            while (i < trees.size) {
                val tt = trees[i]
                if (tt is TokenTree.Punct && tt.value.asChar() == '$' && i + 1 < trees.size) {
                    val next = trees[i + 1]
                    if (next is TokenTree.Ident) {
                        params.add(next.value.toString())
                        i += 2
                        if (i + 1 < trees.size &&
                            (trees[i] as? TokenTree.Punct)?.value?.asChar() == ':'
                        ) {
                            i += 2
                        }
                        continue
                    } else if (next is TokenTree.Group) {
                        params.addAll(extractPatternParams(next.value.stream()))
                        i += 2
                        continue
                    }
                } else if (tt is TokenTree.Group) {
                    params.addAll(extractPatternParams(tt.value.stream()))
                }
                i++
            }
            return params
        }
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

    private fun extractLocalMacroRulesDef(item: Item): MacroRulesDef? {
        if (item is Item.Macro) {
            if (isDocOnlyCfgAttribute(item.attrs)) return null
            if (item.mac.path.isIdent("macro_rules")) {
                val macroName = item.ident?.toString()?.removePrefix("r#")
                if (macroName != null && macroName != "offset_of") {
                    val branches = MacroRulesCollector.parseMacroRulesBranches(item.mac.tokens.clone())
                    if (branches.isNotEmpty() && !isRawPointerLiteralMacroBranches(branches)) {
                        return MacroRulesDef(macroName, branches)
                    }
                }
            }
        } else if (item is Item.Verbatim) {
            val extracted = tryExtractDeclarativeMacro2FromTokens(item.tokens.clone().toList(), false)
            if (extracted != null && extracted.name != "offset_of" && !isRawPointerLiteralMacroBranches(extracted.branches)) {
                return extracted
            }
        }
        return null
    }

    override fun foldFile(file: File): File {
        val foldedAttrs = file.attrs.map(::foldAttribute)
        val expandedAndFoldedItems = expandAndFoldItemList(file.items, depth = 0)
        return file.copy(attrs = foldedAttrs, items = expandedAndFoldedItems)
    }

    override fun foldItemMod(item: Item.Mod): Item.Mod {
        val content = item.content
        if (content is ModContent.Inline) {
            val savedDefs = activeDefinitions.toMap()
            val foldedShell = super.foldItemMod(item.copy(content = content.copy(items = emptyList())))
            val expanded = expandAndFoldItemList(content.items, depth = 0)
            activeDefinitions.clear()
            activeDefinitions.putAll(savedDefs)
            val foldedContent = foldedShell.content as ModContent.Inline
            return foldedShell.copy(content = foldedContent.copy(items = expanded))
        }
        return super.foldItemMod(item)
    }

    override fun foldItemImpl(item: Item.Impl): Item.Impl {
        val foldedShell = super.foldItemImpl(item.copy(items = emptyList()))
        val expandedImplItems = expandAndFoldImplItemList(item.items, depth = 0)
        return foldedShell.copy(items = expandedImplItems)
    }

    override fun foldBlock(block: Block): Block {
        val savedDefs = activeDefinitions.toMap()
        val expandedStmts = expandAndFoldBlockStmts(block.stmts, depth = 0)
        activeDefinitions.clear()
        activeDefinitions.putAll(savedDefs)
        return block.copy(stmts = expandedStmts)
    }

    override fun foldType(t: SynType): SynType {
        if (t is SynType.Macro && activeDefinitions.isNotEmpty()) {
            val expandedType = expandMacroTypeRecursive(t, depth = 0)
            if (expandedType != null) {
                return super.foldType(expandedType)
            }
        }
        return super.foldType(t)
    }

    override fun foldExpr(e: Expr): Expr {
        if (e is Expr.Macro) {
            val macName =
                e.mac.path.segments
                    .last()
                    ?.ident
                    ?.toString()
                    ?.removePrefix("r#")
            if (macName == "cfg_select") {
                val selected =
                    chooseSingleActiveCfgSelectBody(
                        e.mac.tokens
                            .clone()
                            .toList(),
                    )
                if (selected != null) {
                    val parsedExpr =
                        if (selected.isEmpty()) {
                            parse2(ExprParse::parse, TokenStream.fromString("{}").getOrThrow()).getOrNull()
                        } else {
                            parse2(ExprParse::parse, TokenStream.fromTokenTrees(selected)).getOrNull()
                                ?: parse2(
                                    ExprParse::parse,
                                    TokenStream.fromTokenTrees(
                                        listOf(TokenTree.Group(Group(Delimiter.Brace, TokenStream.fromTokenTrees(selected)))),
                                    ),
                                ).getOrNull()
                        }
                    if (parsedExpr != null) {
                        return super.foldExpr(RustNextAstNormalizer.foldExpr(parsedExpr))
                    }
                }
            }
            if (activeDefinitions.isNotEmpty()) {
                val expandedExpr = expandMacroExprRecursive(e, depth = 0)
                if (expandedExpr != null) {
                    return super.foldExpr(expandedExpr)
                }
            }
        }
        return super.foldExpr(e)
    }

    private fun expandMacroTypeRecursive(typeMacro: SynType.Macro, depth: Int): SynType? {
        if (depth > MAX_MACRO_EXPANSION_DEPTH) return null
        val macName =
            typeMacro.mac.path.segments
                .last()
                ?.ident
                ?.toString()
        if (macName == null || macName == "Token") return null
        val def = activeDefinitions[macName] ?: return null
        val substituted = expandMacroToTokenStream(def, typeMacro.mac) ?: return null
        val parsedType =
            parse2(SynType.Companion::parse, substituted.clone())
                .getOrNull()
                ?.let(RustNextAstNormalizer::foldType) ?: return null
        if (parsedType is SynType.Macro) {
            return expandMacroTypeRecursive(parsedType, depth + 1) ?: parsedType
        }
        return parsedType
    }

    private fun expandMacroExprRecursive(exprMacro: Expr.Macro, depth: Int): Expr? {
        if (depth > MAX_MACRO_EXPANSION_DEPTH) return null
        val macName =
            exprMacro.mac.path.segments
                .last()
                ?.ident
                ?.toString() ?: return null
        val def = activeDefinitions[macName] ?: return null
        val substituted = expandMacroToTokenStream(def, exprMacro.mac) ?: return null
        val parsedExpr =
            parse2(ExprParse::parse, substituted.clone())
                .getOrNull()
                ?.let(RustNextAstNormalizer::foldExpr) ?: return null
        if (parsedExpr is Expr.Macro) {
            return expandMacroExprRecursive(parsedExpr, depth + 1) ?: parsedExpr
        }
        return parsedExpr
    }

    private fun expandAndFoldItemList(items: List<Item>, depth: Int): List<Item> {
        if (depth > MAX_MACRO_EXPANSION_DEPTH) return items.map(::foldItem)
        val result = mutableListOf<Item>()
        for (item in items) {
            val localDef = extractLocalMacroRulesDef(item)
            if (localDef != null) {
                activeDefinitions[localDef.name] = localDef
                result.add(item)
                continue
            }
            if (item is Item.Macro && item.ident == null) {
                val macName =
                    item.mac.path.segments
                        .last()
                        ?.ident
                        ?.toString()
                val def = if (macName != null) activeDefinitions[macName] else null
                if (def == null && (macName == "cfg_if" || macName == "cfg_select")) {
                    val rawTokens =
                        item.mac.tokens
                            .clone()
                            .toList()
                    val bodies =
                        if (macName == "cfg_select") {
                            selectActiveCfgSelectBodies(rawTokens)
                        } else {
                            selectActiveCfgIfBodies(rawTokens)
                        }
                    if (bodies != null) {
                        val tokens = TokenStream.fromTokenTrees(bodies.flatten())
                        val parsed = parse2(FileParse::parse, tokens).getOrNull()
                        if (parsed != null) {
                            val normalized = RustNextAstNormalizer.foldFile(parsed)
                            val expanded = expandAndFoldItemList(normalized.items, depth + 1)
                            val propagated = propagateOuterAttrsToItems(item.attrs, expanded)
                            attachWrapperSpansToFirstExpandedItem(collectTransitiveWrapperSpansForItem(item), propagated)
                            result.addAll(propagated)
                            continue
                        }
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
        if (depth > MAX_MACRO_EXPANSION_DEPTH) return items.map(::foldImplItem)
        val result = mutableListOf<ImplItem>()
        for (item in items) {
            if (item is ImplItem.Macro) {
                val macName =
                    item.mac.path.segments
                        .last()
                        ?.ident
                        ?.toString()
                val def = if (macName != null) activeDefinitions[macName] else null
                if (def == null && (macName == "cfg_if" || macName == "cfg_select")) {
                    val rawTokens =
                        item.mac.tokens
                            .clone()
                            .toList()
                    val selectedItems =
                        if (macName == "cfg_select") {
                            selectActiveCfgSelectImplItems(rawTokens)
                        } else {
                            selectActiveCfgIfBodies(rawTokens)?.let { bodies ->
                                val wrapped = wrapImplDummyStream(TokenStream.fromTokenTrees(bodies.flatten()))
                                (
                                    parse2(FileParse::parse, wrapped)
                                        .getOrNull()
                                        ?.let(RustNextAstNormalizer::foldFile)
                                        ?.items
                                        ?.singleOrNull() as? Item.Impl
                                )?.items
                            }
                        }
                    if (selectedItems != null) {
                        val expanded = expandAndFoldImplItemList(selectedItems, depth + 1)
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

    private fun propagateOuterAttrsToItems(outerAttrs: List<Attribute>, expanded: List<Item>): List<Item> {
        if (outerAttrs.isEmpty() || expanded.isEmpty()) return expanded
        val (docAttrs, nonDocAttrs) =
            outerAttrs.partition { attr ->
                val meta = attr.meta
                meta is Meta.NameValue && meta.path.isIdent("doc")
            }
        val docTargetIndex =
            expanded.indexOfFirst { !isDocOnlyCfgAttribute(itemAttrs(it)) }.let { if (it >= 0) it else 0 }
        return expanded.mapIndexed { idx, item ->
            val extra = if (idx == docTargetIndex) docAttrs + nonDocAttrs else nonDocAttrs
            if (extra.isEmpty()) item else prependItemAttrs(item, extra)
        }
    }

    private fun collectTransitiveWrapperSpansForItem(item: Item): List<Span> {
        val ownSpans = collectHeaderSourceSpans(item)
        val out = mutableListOf<Span>()
        val firstOwnOffset = ownSpans.firstOrNull()?.startOffset()
        if (firstOwnOffset != null) {
            macroWrapperSpansByTargetOffset[firstOwnOffset]?.let { inherited ->
                out.addAll(inherited)
            }
        }
        out.addAll(ownSpans)
        return out
    }

    private fun attachWrapperSpansToFirstExpandedItem(wrapperSpans: List<Span>, expanded: List<Item>) {
        if (wrapperSpans.isEmpty() || expanded.isEmpty()) return
        val targetItem = expanded.firstOrNull { !isDocOnlyCfgAttribute(itemAttrs(it)) } ?: expanded.first()
        val targetOffset = collectHeaderSourceSpans(targetItem).firstOrNull()?.startOffset() ?: return
        val bucket = macroWrapperSpansByTargetOffset.getOrPut(targetOffset) { mutableListOf() }
        for (sp in wrapperSpans) {
            val spStart = sp.startOffset()
            if (spStart != targetOffset && bucket.none { it.startOffset() == spStart }) {
                bucket.add(sp)
            }
        }
    }

    private fun propagateOuterAttrsToImplItems(outerAttrs: List<Attribute>, expanded: List<ImplItem>): List<ImplItem> {
        if (outerAttrs.isEmpty() || expanded.isEmpty()) return expanded
        val (docAttrs, nonDocAttrs) =
            outerAttrs.partition { attr ->
                val meta = attr.meta
                meta is Meta.NameValue && meta.path.isIdent("doc")
            }
        val docTargetIndex =
            expanded.indexOfFirst { !isDocOnlyCfgAttribute(implItemAttrs(it)) }.let { if (it >= 0) it else 0 }
        return expanded.mapIndexed { idx, item ->
            val extra = if (idx == docTargetIndex) docAttrs + nonDocAttrs else nonDocAttrs
            if (extra.isEmpty()) item else prependImplItemAttrs(item, extra)
        }
    }

    private fun itemAttrs(item: Item): List<Attribute> =
        when (item) {
            is Item.Const -> item.attrs
            is Item.Enum -> item.attrs
            is Item.ExternCrate -> item.attrs
            is Item.Fn -> item.attrs
            is Item.ForeignMod -> item.attrs
            is Item.Impl -> item.attrs
            is Item.Macro -> item.attrs
            is Item.Mod -> item.attrs
            is Item.Static -> item.attrs
            is Item.Struct -> item.attrs
            is Item.Trait -> item.attrs
            is Item.TraitAlias -> item.attrs
            is Item.ItemType -> item.attrs
            is Item.Union -> item.attrs
            is Item.Use -> item.attrs
            is Item.Verbatim -> emptyList()
        }

    private fun prependItemAttrs(item: Item, prefixAttrs: List<Attribute>): Item =
        when (item) {
            is Item.Const -> item.copy(attrs = prefixAttrs + item.attrs)
            is Item.Enum -> item.copy(attrs = prefixAttrs + item.attrs)
            is Item.ExternCrate -> item.copy(attrs = prefixAttrs + item.attrs)
            is Item.Fn -> item.copy(attrs = prefixAttrs + item.attrs)
            is Item.ForeignMod -> item.copy(attrs = prefixAttrs + item.attrs)
            is Item.Impl -> item.copy(attrs = prefixAttrs + item.attrs)
            is Item.Macro -> item.copy(attrs = prefixAttrs + item.attrs)
            is Item.Mod -> item.copy(attrs = prefixAttrs + item.attrs)
            is Item.Static -> item.copy(attrs = prefixAttrs + item.attrs)
            is Item.Struct -> item.copy(attrs = prefixAttrs + item.attrs)
            is Item.Trait -> item.copy(attrs = prefixAttrs + item.attrs)
            is Item.TraitAlias -> item.copy(attrs = prefixAttrs + item.attrs)
            is Item.ItemType -> item.copy(attrs = prefixAttrs + item.attrs)
            is Item.Union -> item.copy(attrs = prefixAttrs + item.attrs)
            is Item.Use -> item.copy(attrs = prefixAttrs + item.attrs)
            is Item.Verbatim -> item
        }

    private fun implItemAttrs(item: ImplItem): List<Attribute> =
        when (item) {
            is ImplItem.Const -> item.attrs
            is ImplItem.Fn -> item.attrs
            is ImplItem.AssocType -> item.attrs
            is ImplItem.Macro -> item.attrs
            is ImplItem.Verbatim -> emptyList()
        }

    private fun prependImplItemAttrs(item: ImplItem, prefixAttrs: List<Attribute>): ImplItem =
        when (item) {
            is ImplItem.Const -> item.copy(attrs = prefixAttrs + item.attrs)
            is ImplItem.Fn -> item.copy(attrs = prefixAttrs + item.attrs)
            is ImplItem.AssocType -> item.copy(attrs = prefixAttrs + item.attrs)
            is ImplItem.Macro -> item.copy(attrs = prefixAttrs + item.attrs)
            is ImplItem.Verbatim -> item
        }

    private fun expandAndFoldBlockStmts(stmts: List<Stmt>, depth: Int): List<Stmt> {
        if (depth > MAX_MACRO_EXPANSION_DEPTH) return stmts.map(::foldStmt)
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
                val macName =
                    stmt.mac.path.segments
                        .last()
                        ?.ident
                        ?.toString()
                val def = if (macName != null) activeDefinitions[macName] else null
                if (def == null && (macName == "cfg_if" || macName == "cfg_select")) {
                    val rawTokens =
                        stmt.mac.tokens
                            .clone()
                            .toList()
                    val selectedBody =
                        if (macName == "cfg_select") {
                            chooseSingleActiveCfgSelectBody(rawTokens)
                        } else {
                            selectActiveCfgIfBodies(rawTokens)?.firstOrNull()
                        }
                    if (selectedBody != null) {
                        if (selectedBody.isEmpty()) {
                            continue
                        }
                        val wrapped = buildWrappedStream("fn dummy()", Delimiter.Brace, TokenStream.fromTokenTrees(selectedBody))
                        val parsedFile = parse2(FileParse::parse, wrapped).getOrNull()?.let(RustNextAstNormalizer::foldFile)
                        val dummyFn = parsedFile?.items?.singleOrNull() as? Item.Fn
                        if (dummyFn != null) {
                            result.addAll(expandAndFoldBlockStmts(dummyFn.block.stmts, depth + 1))
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
        val rawFile = parse2(FileParse::parse, substitutedStream).getOrNull() ?: return null
        val parsedFile = RustNextAstNormalizer.foldFile(rawFile)
        return expandAndFoldItemList(parsedFile.items, depth + 1)
    }

    private fun tryExpandMacroImplItem(def: MacroRulesDef, invocation: Macro, depth: Int): List<ImplItem>? {
        val substitutedStream = expandMacroToTokenStream(def, invocation) ?: return null
        if (substitutedStream.isEmpty()) return emptyList()
        val wrappedTokens = buildWrappedStream("impl Dummy", Delimiter.Brace, substitutedStream)
        val rawFile = parse2(FileParse::parse, wrappedTokens).getOrNull() ?: return null
        val parsedFile = RustNextAstNormalizer.foldFile(rawFile)
        val dummyImpl = parsedFile.items.singleOrNull() as? Item.Impl ?: return null
        return expandAndFoldImplItemList(dummyImpl.items, depth + 1)
    }

    private fun tryExpandMacroStmts(def: MacroRulesDef, invocation: Macro, depth: Int): List<Stmt>? {
        val substitutedStream = expandMacroToTokenStream(def, invocation) ?: return null
        if (substitutedStream.isEmpty()) return emptyList()
        val wrappedTokens = buildWrappedStream("fn dummy()", Delimiter.Brace, substitutedStream)
        val rawFile = parse2(FileParse::parse, wrappedTokens).getOrNull() ?: return null
        val parsedFile = RustNextAstNormalizer.foldFile(rawFile)
        val dummyFn = parsedFile.items.singleOrNull() as? Item.Fn ?: return null
        val fnBlock = dummyFn.block
        return expandAndFoldBlockStmts(fnBlock.stmts, depth + 1)
    }

    private fun buildWrappedStream(prefixCode: String, delimiter: Delimiter, inner: TokenStream): TokenStream {
        val prefix = TokenStream.fromString(prefixCode).getOrThrow().toList()
        val group = TokenTree.Group(Group(delimiter, inner))
        return TokenStream.fromTokenTrees(prefix + group)
    }

    private fun expandMacroToTokenStream(def: MacroRulesDef, invocation: Macro): TokenStream? {
        val inputTrees = invocation.tokens.clone().toList()
        for (branch in def.branches) {
            val env = MacroMatchEnv()
            val consumed = matchPatternSequence(branch.pattern, 0, inputTrees, 0, env)
            if (consumed == inputTrees.size) {
                return substituteMacroTokensWithEnv(branch.bodyTokens.clone(), env)
            }
        }
        return null
    }

    companion object {
        private const val MAX_MACRO_EXPANSION_DEPTH = 64

        private fun matchPatternSequence(
            pats: List<MacroPat>,
            patIdx: Int,
            input: List<TokenTree>,
            inIdx: Int,
            env: MacroMatchEnv,
            trailingStopPats: List<MacroPat> = emptyList(),
        ): Int? {
            if (patIdx == pats.size) {
                return inIdx
            }
            return when (val pat = pats[patIdx]) {
                is MacroPat.LiteralToken -> {
                    if (inIdx >= input.size) return null
                    if (!tokenMatchesLiteral(input[inIdx], pat)) return null
                    if (!pat.isPunct && input[inIdx] is TokenTree.Ident) {
                        val kw = pat.text
                        if (kw !in env.matchedKeywordSpans) {
                            env.matchedKeywordSpans[kw] = input[inIdx].span()
                        }
                    }
                    matchPatternSequence(pats, patIdx + 1, input, inIdx + 1, env, trailingStopPats)
                }
                is MacroPat.GroupPat -> {
                    if (inIdx >= input.size) return null
                    val grp = input[inIdx] as? TokenTree.Group ?: return null
                    if (grp.value.delimiter() != pat.delimiter) return null
                    val innerTrees = grp.value.stream().toList()
                    val innerConsumed = matchPatternSequence(pat.inner, 0, innerTrees, 0, env)
                    if (innerConsumed != innerTrees.size) return null
                    matchPatternSequence(pats, patIdx + 1, input, inIdx + 1, env, trailingStopPats)
                }
                is MacroPat.Metavar -> {
                    val stopPats = collectLeadingStopPats(pats, patIdx + 1, trailingStopPats)
                    val spanLen = consumeFragmentLength(pat.fragSpec, input, inIdx, stopPats) ?: return null
                    env.singles[pat.name] = input.subList(inIdx, inIdx + spanLen)
                    matchPatternSequence(pats, patIdx + 1, input, inIdx + spanLen, env, trailingStopPats)
                }
                is MacroPat.Repetition -> {
                    var curIdx = inIdx
                    var count = 0
                    val nextAfterRepStops = collectLeadingStopPats(pats, patIdx + 1, trailingStopPats)
                    val immediateNext = pats.getOrNull(patIdx + 1)
                    val groupIters = mutableListOf<MacroMatchEnv>()
                    while (curIdx < input.size) {
                        if (immediateNext is MacroPat.LiteralToken &&
                            tokenMatchesLiteral(input[curIdx], immediateNext) &&
                            !(count > 0 && pat.sep == immediateNext)
                        ) {
                            break
                        }
                        var iterStart = curIdx
                        if (count > 0 && pat.sep != null) {
                            if (!tokenMatchesLiteral(input[iterStart], pat.sep)) {
                                break
                            }
                            iterStart++
                            if (iterStart >= input.size) {
                                break
                            }
                        }
                        val childEnv = MacroMatchEnv()
                        val afterIter =
                            matchRepetitionIteration(
                                innerPats = pat.inner,
                                repOp = pat.op,
                                input = input,
                                startIdx = iterStart,
                                repSep = pat.sep,
                                nextAfterRepStops = nextAfterRepStops,
                                childEnv = childEnv,
                            )
                        if (afterIter == null || afterIter <= iterStart) {
                            break
                        }
                        env.repetitions.add(childEnv)
                        groupIters.add(childEnv)
                        curIdx = afterIter
                        count++
                        if (pat.op == '?') break
                    }
                    if (pat.op == '+' && count == 0) return null
                    env.repetitionGroups.add(
                        RepetitionGroup(
                            directVars = collectDirectPatMetavars(pat.inner),
                            allVars = collectAllPatMetavars(pat.inner),
                            iterations = groupIters,
                        ),
                    )
                    matchPatternSequence(pats, patIdx + 1, input, curIdx, env, trailingStopPats)
                }
            }
        }

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
                        if (p.op == '+') {
                            return stops
                        }
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

        private fun matchRepetitionIteration(
            innerPats: List<MacroPat>,
            repOp: Char,
            input: List<TokenTree>,
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
                            val restartStops = collectLeadingStopPats(innerPats, 0, emptyList())
                            addAll(restartStops)
                        }
                        addAll(nextAfterRepStops)
                    }
                }
            return matchPatternSequence(innerPats, 0, input, startIdx, childEnv, iterTrailingStops)
        }

        private fun consumeFragmentLength(
            fragSpec: String,
            input: List<TokenTree>,
            startIdx: Int,
            stopPats: List<MacroPat>,
        ): Int? {
            if (fragSpec == "vis") {
                return consumeVisLength(input, startIdx)
            }
            if (startIdx >= input.size) return null
            return when (fragSpec) {
                "ident" -> if (input[startIdx] is TokenTree.Ident) 1 else null
                "tt" -> 1
                "lifetime" -> {
                    val p = input[startIdx] as? TokenTree.Punct
                    if (p?.value?.asChar() == '\'' && startIdx + 1 < input.size && input[startIdx + 1] is TokenTree.Ident) 2 else null
                }
                "literal" -> {
                    if (input[startIdx] is TokenTree.Literal) {
                        1
                    } else if ((input[startIdx] as? TokenTree.Punct)?.value?.asChar() == '-' &&
                        startIdx + 1 < input.size && input[startIdx + 1] is TokenTree.Literal
                    ) {
                        2
                    } else {
                        null
                    }
                }
                "block" -> {
                    val g = input[startIdx] as? TokenTree.Group
                    if (g != null && g.value.delimiter() == Delimiter.Brace) 1 else null
                }
                "ty" -> consumeSingleTypeLength(input, startIdx)
                "path" -> consumeTypePathOrMacro(input, startIdx, allowMacroBang = false)?.let { it - startIdx }
                "item" -> consumeSingleItemLength(input, startIdx)
                else -> {
                    // expr, pat, pat_param, stmt, meta
                    val literalOrGroupStops =
                        stopPats.filter { it is MacroPat.LiteralToken || it is MacroPat.GroupPat }
                    if (fragSpec == "stmt" && literalOrGroupStops.isEmpty()) {
                        consumeSingleStmtLength(input, startIdx)
                    } else {
                        var idx = startIdx
                        var angleDepth = 0
                        var inClosureParams = false
                        while (idx < input.size) {
                            val tt = input[idx]
                            if (angleDepth == 0 && !inClosureParams) {
                                if (literalOrGroupStops.any { stop -> tokenMatchesStopPat(tt, stop) }) {
                                    break
                                }
                                val punctChar = (tt as? TokenTree.Punct)?.value?.asChar()
                                if (fragSpec == "stmt" && punctChar == ';') {
                                    idx++
                                    break
                                }
                                val isFatArrow =
                                    tt is TokenTree.Punct &&
                                        punctChar == '=' &&
                                        tt.value.spacing() == Spacing.Joint &&
                                        (input.getOrNull(idx + 1) as? TokenTree.Punct)?.value?.asChar() == '>'
                                if (fragSpec != "stmt" && (punctChar == ',' || punctChar == ';' || isFatArrow)) {
                                    break
                                }
                            }
                            if (tt is TokenTree.Punct) {
                                val ch = tt.value.asChar()
                                val isJoint = tt.value.spacing() == Spacing.Joint
                                val nextCh = (input.getOrNull(idx + 1) as? TokenTree.Punct)?.value?.asChar()
                                if (ch == '|' && angleDepth == 0) {
                                    if (isJoint && (nextCh == '|' || nextCh == '=')) {
                                        idx += 2
                                        continue
                                    }
                                    if (inClosureParams) {
                                        inClosureParams = false
                                    } else if (canOpenClosureParamList(input, startIdx, idx)) {
                                        inClosureParams = true
                                    }
                                } else if (ch == '<' && hasMatchingClosingAngle(input, idx)) {
                                    angleDepth++
                                } else if (ch == '>' && angleDepth > 0 && !isPartOfRArrow(input, idx)) {
                                    angleDepth--
                                }
                            }
                            idx++
                        }
                        if (idx <= startIdx) {
                            null
                        } else if (fragSpec == "expr") {
                            val hasNonLiteralStop =
                                stopPats.any { it !is MacroPat.LiteralToken && it !is MacroPat.GroupPat }
                            resolveValidExprFragmentLength(input, startIdx, idx, allowPrefixShrink = hasNonLiteralStop)
                        } else {
                            idx - startIdx
                        }
                    }
                }
            }
        }

        private val NON_EXPR_SINGLE_IDENT_KEYWORDS =
            setOf(
                "as",
                "async",
                "break",
                "const",
                "continue",
                "crate",
                "dyn",
                "else",
                "enum",
                "extern",
                "fn",
                "for",
                "if",
                "impl",
                "in",
                "let",
                "loop",
                "match",
                "mod",
                "move",
                "mut",
                "pub",
                "ref",
                "return",
                "static",
                "struct",
                "super",
                "trait",
                "type",
                "union",
                "unsafe",
                "use",
                "where",
                "while",
                "yield",
            )

        private fun isValidMacroExprTokens(tokens: List<TokenTree>): Boolean {
            if (tokens.isEmpty()) return false
            if (tokens.size == 1) {
                when (val single = tokens[0]) {
                    is TokenTree.Literal -> return true
                    is TokenTree.Ident -> {
                        val raw = single.value.toString()
                        if (raw !in NON_EXPR_SINGLE_IDENT_KEYWORDS) return true
                    }
                    else -> {}
                }
            }
            val normalized = normalizeLetOrPatternsForSyn(TokenStream.fromTokenTrees(tokens))
            return parse2(ExprParse::parse, normalized).getOrNull() != null
        }

        private fun resolveValidExprFragmentLength(
            input: List<TokenTree>,
            startIdx: Int,
            maxEndIdx: Int,
            allowPrefixShrink: Boolean,
        ): Int? {
            if (isValidMacroExprTokens(input.subList(startIdx, maxEndIdx))) {
                return maxEndIdx - startIdx
            }
            if (!allowPrefixShrink) return null
            var candidateEnd = maxEndIdx - 1
            while (candidateEnd > startIdx) {
                if (isValidMacroExprTokens(input.subList(startIdx, candidateEnd))) {
                    return candidateEnd - startIdx
                }
                candidateEnd--
            }
            return null
        }

        private val CLOSURE_PREFIX_KEYWORDS =
            setOf("move", "async", "static", "const", "return", "break", "yield")

        private fun canOpenClosureParamList(
            input: List<TokenTree>,
            startIdx: Int,
            pipeIdx: Int,
        ): Boolean {
            if (pipeIdx <= startIdx) return true
            val prev = input[pipeIdx - 1]
            if (prev is TokenTree.Punct) {
                val ch = prev.value.asChar()
                return ch != '?' && ch != '>'
            }
            return prev is TokenTree.Ident && prev.value.toString() in CLOSURE_PREFIX_KEYWORDS
        }

        private fun tokenMatchesStopPat(tt: TokenTree, stop: MacroPat): Boolean =
            when (stop) {
                is MacroPat.LiteralToken -> tokenMatchesLiteral(tt, stop)
                is MacroPat.GroupPat -> tt is TokenTree.Group && tt.value.delimiter() == stop.delimiter
                else -> false
            }

        private fun consumeVisLength(input: List<TokenTree>, startIdx: Int): Int {
            if (startIdx >= input.size) return 0
            val first = input[startIdx] as? TokenTree.Ident ?: return 0
            if (first.value.toString() != "pub") return 0
            val second = input.getOrNull(startIdx + 1) as? TokenTree.Group
            return if (second != null && second.value.delimiter() == Delimiter.Parenthesis) 2 else 1
        }

        private fun consumeSingleItemLength(input: List<TokenTree>, startIdx: Int): Int? {
            if (startIdx >= input.size) return null
            var idx = startIdx
            // Outer attributes: #[...]
            while (idx + 1 < input.size &&
                (input[idx] as? TokenTree.Punct)?.value?.asChar() == '#' &&
                (input[idx + 1] as? TokenTree.Group)?.value?.delimiter() == Delimiter.Bracket
            ) {
                idx += 2
            }
            if (idx >= input.size) return null

            // Visibility + item modifiers to determine whether `{ ... }` is a const/static initializer
            val probeIdx = idx + consumeVisLength(input, idx)
            var isConstOrStaticValue = false
            val headIdent = (input.getOrNull(probeIdx) as? TokenTree.Ident)?.value?.toString()
            if (headIdent == "static" || headIdent == "type" || headIdent == "use") {
                isConstOrStaticValue = true
            } else if (headIdent == "const") {
                val afterConst = (input.getOrNull(probeIdx + 1) as? TokenTree.Ident)?.value?.toString()
                if (afterConst != "fn" && afterConst != "unsafe" && afterConst != "async" && afterConst != "extern") {
                    isConstOrStaticValue = true
                }
            }

            while (idx < input.size) {
                val tt = input[idx]
                if (tt is TokenTree.Punct && tt.value.asChar() == ';') {
                    return (idx + 1) - startIdx
                }
                if (!isConstOrStaticValue && tt is TokenTree.Group && tt.value.delimiter() == Delimiter.Brace) {
                    return (idx + 1) - startIdx
                }
                idx++
            }
            return if (idx > startIdx) idx - startIdx else null
        }

        private fun consumeSingleStmtLength(input: List<TokenTree>, startIdx: Int): Int? {
            if (startIdx >= input.size) return null
            var idx = startIdx
            while (idx < input.size) {
                val tt = input[idx]
                if (tt is TokenTree.Punct && tt.value.asChar() == ';') {
                    return (idx + 1) - startIdx
                }
                idx++
            }
            return input.size - startIdx
        }

        private fun consumeSingleTypeLength(input: List<TokenTree>, startIdx: Int): Int? {
            if (startIdx >= input.size) return null
            var idx = startIdx
            while (idx < input.size) {
                val tt = input[idx]
                if (tt is TokenTree.Punct && tt.value.asChar() == '&') {
                    idx++
                    if (idx + 1 < input.size &&
                        (input[idx] as? TokenTree.Punct)?.value?.asChar() == '\'' &&
                        input[idx + 1] is TokenTree.Ident
                    ) {
                        idx += 2
                    }
                    if (idx < input.size && (input[idx] as? TokenTree.Ident)?.value?.toString() == "mut") {
                        idx++
                    }
                    continue
                }
                if (tt is TokenTree.Punct && tt.value.asChar() == '*' && idx + 1 < input.size) {
                    val qual = (input[idx + 1] as? TokenTree.Ident)?.value?.toString()
                    if (qual == "const" || qual == "mut") {
                        idx += 2
                        continue
                    }
                }
                if (tt is TokenTree.Ident && tt.value.toString() == "for" &&
                    idx + 1 < input.size && (input[idx + 1] as? TokenTree.Punct)?.value?.asChar() == '<' &&
                    hasMatchingClosingAngle(input, idx + 1)
                ) {
                    idx = skipBalancedAngles(input, idx + 1)
                    continue
                }
                break
            }
            if (idx >= input.size) return null
            val core = input[idx]
            if (core is TokenTree.Group &&
                (core.value.delimiter() == Delimiter.Bracket || core.value.delimiter() == Delimiter.Parenthesis)
            ) {
                return (idx + 1) - startIdx
            }
            if (core is TokenTree.Punct && core.value.asChar() == '!') {
                return (idx + 1) - startIdx
            }
            val bareFnEnd = tryConsumeBareFnType(input, idx)
            if (bareFnEnd != null) {
                return bareFnEnd - startIdx
            }
            if (core is TokenTree.Ident && (core.value.toString() == "impl" || core.value.toString() == "dyn")) {
                idx++
                val boundsEnd = consumeTypeParamBounds(input, idx) ?: return null
                return boundsEnd - startIdx
            }
            val pathEnd = consumeTypePathOrMacro(input, idx, allowMacroBang = true) ?: return null
            return pathEnd - startIdx
        }

        private fun tryConsumeBareFnType(input: List<TokenTree>, startIdx: Int): Int? {
            var idx = startIdx
            if ((input.getOrNull(idx) as? TokenTree.Ident)?.value?.toString() == "unsafe") {
                idx++
            }
            if ((input.getOrNull(idx) as? TokenTree.Ident)?.value?.toString() == "extern") {
                idx++
                if (input.getOrNull(idx) is TokenTree.Literal) {
                    idx++
                }
            }
            if ((input.getOrNull(idx) as? TokenTree.Ident)?.value?.toString() != "fn") {
                return null
            }
            idx++
            val paramsGroup = input.getOrNull(idx) as? TokenTree.Group ?: return null
            if (paramsGroup.value.delimiter() != Delimiter.Parenthesis) return null
            idx++
            if (idx + 1 < input.size &&
                (input[idx] as? TokenTree.Punct)?.value?.asChar() == '-' &&
                (input[idx + 1] as? TokenTree.Punct)?.value?.asChar() == '>'
            ) {
                idx += 2
                val retLen = consumeSingleTypeLength(input, idx) ?: return null
                idx += retLen
            }
            return idx
        }

        private fun consumeTypeParamBounds(input: List<TokenTree>, startIdx: Int): Int? {
            var idx = consumeSingleTypeParamBound(input, startIdx) ?: return null
            while (idx < input.size && (input[idx] as? TokenTree.Punct)?.value?.asChar() == '+') {
                val nextBoundEnd = consumeSingleTypeParamBound(input, idx + 1) ?: break
                idx = nextBoundEnd
            }
            return idx
        }

        private fun consumeSingleTypeParamBound(input: List<TokenTree>, startIdx: Int): Int? {
            if (startIdx >= input.size) return null
            var idx = startIdx
            if ((input[idx] as? TokenTree.Group)?.value?.delimiter() == Delimiter.Parenthesis) {
                return idx + 1
            }
            if ((input[idx] as? TokenTree.Punct)?.value?.asChar() == '\'' &&
                idx + 1 < input.size && input[idx + 1] is TokenTree.Ident
            ) {
                return idx + 2
            }
            if ((input[idx] as? TokenTree.Punct)?.value?.asChar() == '?' ||
                (input[idx] as? TokenTree.Punct)?.value?.asChar() == '~'
            ) {
                idx++
            }
            if ((input.getOrNull(idx) as? TokenTree.Ident)?.value?.toString() == "for" &&
                idx + 1 < input.size && (input[idx + 1] as? TokenTree.Punct)?.value?.asChar() == '<' &&
                hasMatchingClosingAngle(input, idx + 1)
            ) {
                idx = skipBalancedAngles(input, idx + 1)
            }
            return consumeTypePathOrMacro(input, idx, allowMacroBang = false, allowAllParenthesizedArgs = true)
        }

        private fun consumeTypePathOrMacro(
            input: List<TokenTree>,
            startIdx: Int,
            allowMacroBang: Boolean,
            allowAllParenthesizedArgs: Boolean = false,
        ): Int? {
            if (startIdx >= input.size) return null
            var idx = startIdx
            if ((input[idx] as? TokenTree.Punct)?.value?.asChar() == '<' && hasMatchingClosingAngle(input, idx)) {
                idx = skipBalancedAngles(input, idx)
                if (idx + 1 >= input.size ||
                    (input[idx] as? TokenTree.Punct)?.value?.asChar() != ':' ||
                    (input[idx + 1] as? TokenTree.Punct)?.value?.asChar() != ':'
                ) {
                    return null
                }
                idx += 2
            } else if (idx + 1 < input.size &&
                (input[idx] as? TokenTree.Punct)?.value?.asChar() == ':' &&
                (input[idx + 1] as? TokenTree.Punct)?.value?.asChar() == ':'
            ) {
                idx += 2
            }
            val firstIdent = input.getOrNull(idx) as? TokenTree.Ident ?: return null
            var lastSegName = firstIdent.value.toString()
            idx++
            while (idx < input.size) {
                val tt = input[idx]
                if (allowMacroBang &&
                    tt is TokenTree.Punct && tt.value.asChar() == '!' &&
                    input.getOrNull(idx + 1) is TokenTree.Group
                ) {
                    return idx + 2
                }
                if (tt is TokenTree.Punct && tt.value.asChar() == '<' && hasMatchingClosingAngle(input, idx)) {
                    idx = skipBalancedAngles(input, idx)
                    continue
                }
                if (tt is TokenTree.Group && tt.value.delimiter() == Delimiter.Parenthesis &&
                    (allowAllParenthesizedArgs || lastSegName.startsWith("Fn"))
                ) {
                    idx++
                    if (idx + 1 < input.size &&
                        (input[idx] as? TokenTree.Punct)?.value?.asChar() == '-' &&
                        (input[idx + 1] as? TokenTree.Punct)?.value?.asChar() == '>'
                    ) {
                        idx += 2
                        val retLen = consumeSingleTypeLength(input, idx) ?: return null
                        idx += retLen
                    }
                    continue
                }
                if (tt is TokenTree.Punct && tt.value.asChar() == ':' &&
                    idx + 2 < input.size &&
                    (input[idx + 1] as? TokenTree.Punct)?.value?.asChar() == ':' &&
                    input[idx + 2] is TokenTree.Ident
                ) {
                    lastSegName = (input[idx + 2] as TokenTree.Ident).value.toString()
                    idx += 3
                    continue
                }
                break
            }
            return idx
        }

        private fun skipBalancedAngles(input: List<TokenTree>, openIdx: Int): Int {
            var depth = 1
            var idx = openIdx + 1
            while (idx < input.size && depth > 0) {
                val c = (input[idx] as? TokenTree.Punct)?.value?.asChar()
                if (c == '<') {
                    depth++
                } else if (c == '>' && !isPartOfRArrow(input, idx)) {
                    depth--
                }
                idx++
            }
            return idx
        }

        private fun isPartOfRArrow(trees: List<TokenTree>, gtIdx: Int): Boolean {
            if (gtIdx <= 0) return false
            val prev = trees[gtIdx - 1] as? TokenTree.Punct ?: return false
            return prev.value.asChar() == '-' && prev.value.spacing() == Spacing.Joint
        }

        private fun tokenMatchesLiteral(tt: TokenTree, pat: MacroPat.LiteralToken): Boolean =
            when (tt) {
                is TokenTree.Punct -> pat.isPunct && tt.value.asChar().toString() == pat.text
                is TokenTree.Ident -> !pat.isPunct && tt.value.toString() == pat.text
                is TokenTree.Literal -> !pat.isPunct && tt.value.toString() == pat.text
                is TokenTree.Group -> false
            }

        private fun collectDirectPatMetavars(pats: List<MacroPat>): Set<String> {
            val vars = linkedSetOf<String>()
            for (p in pats) {
                when (p) {
                    is MacroPat.Metavar -> vars.add(p.name)
                    is MacroPat.GroupPat -> vars.addAll(collectDirectPatMetavars(p.inner))
                    is MacroPat.Repetition, is MacroPat.LiteralToken -> {}
                }
            }
            return vars
        }

        private fun collectAllPatMetavars(pats: List<MacroPat>): Set<String> {
            val vars = linkedSetOf<String>()
            for (p in pats) {
                when (p) {
                    is MacroPat.Metavar -> vars.add(p.name)
                    is MacroPat.GroupPat -> vars.addAll(collectAllPatMetavars(p.inner))
                    is MacroPat.Repetition -> vars.addAll(collectAllPatMetavars(p.inner))
                    is MacroPat.LiteralToken -> {}
                }
            }
            return vars
        }

        private fun extractIgnoredMetavarName(braceStream: TokenStream): String? {
            val inner = braceStream.clone().toList()
            if (inner.size != 2 || (inner[0] as? TokenTree.Ident)?.value?.toString() != "ignore") {
                return null
            }
            val argGroup = inner[1] as? TokenTree.Group ?: return null
            if (argGroup.value.delimiter() != Delimiter.Parenthesis) return null
            val args = argGroup.value.stream().toList()
            return when {
                args.size == 1 && args[0] is TokenTree.Ident ->
                    (args[0] as TokenTree.Ident).value.toString().removePrefix("r#")
                args.size == 2 &&
                    (args[0] as? TokenTree.Punct)?.value?.asChar() == '$' &&
                    args[1] is TokenTree.Ident ->
                    (args[1] as TokenTree.Ident).value.toString().removePrefix("r#")
                else -> null
            }
        }

        private fun parseOptionalDepthArg(args: List<TokenTree>): Int? {
            if (args.isEmpty()) return 0
            if (args.size == 1 && args[0] is TokenTree.Literal) {
                return (args[0] as TokenTree.Literal).value.toString().toIntOrNull()
            }
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

        private fun tryEvaluateMetavarExpr(
            braceStream: TokenStream,
            env: MacroMatchEnv,
            repIndexStack: List<Int>,
            repLenStack: List<Int>,
        ): List<TokenTree>? {
            val inner = braceStream.clone().toList()
            if (inner.size != 2) return null
            val op = (inner[0] as? TokenTree.Ident)?.value?.toString() ?: return null
            val argGroup = inner[1] as? TokenTree.Group ?: return null
            if (argGroup.value.delimiter() != Delimiter.Parenthesis) return null
            val args = argGroup.value.stream().toList()
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
                    val targetVar: String
                    val maxDepth: Int?
                    var pos = 0
                    if ((args.getOrNull(pos) as? TokenTree.Punct)?.value?.asChar() == '$') {
                        pos++
                    }
                    val varIdent = (args.getOrNull(pos) as? TokenTree.Ident)?.value?.toString()?.removePrefix("r#") ?: return null
                    targetVar = varIdent
                    pos++
                    if (pos == args.size) {
                        maxDepth = null
                    } else if (pos + 1 < args.size &&
                        (args[pos] as? TokenTree.Punct)?.value?.asChar() == ',' &&
                        args[pos + 1] is TokenTree.Literal &&
                        pos + 2 == args.size
                    ) {
                        maxDepth = (args[pos + 1] as TokenTree.Literal).value.toString().toIntOrNull() ?: return null
                    } else {
                        return null
                    }
                    val cnt = countMetavarOccurrences(env, targetVar, maxDepth)
                    listOf(TokenTree.Literal(Literal.usizeUnsuffixed(cnt.toULong())))
                }
                else -> null
            }
        }

        private fun collectDirectTemplateMetavars(stream: TokenStream): Set<String> {
            val vars = linkedSetOf<String>()
            val trees = stream.clone().toList()
            var i = 0
            while (i < trees.size) {
                val tt = trees[i]
                if (tt is TokenTree.Punct && tt.value.asChar() == '$' && i + 1 < trees.size) {
                    val next = trees[i + 1]
                    if (next is TokenTree.Ident) {
                        val name = next.value.toString()
                        if (name != "crate") vars.add(name)
                        i += 2
                        continue
                    } else if (next is TokenTree.Group && next.value.delimiter() == Delimiter.Parenthesis) {
                        i += templateRepetitionHeaderAdvance(trees, i)
                        continue
                    } else if (next is TokenTree.Group && next.value.delimiter() == Delimiter.Brace) {
                        val ignored = extractIgnoredMetavarName(next.value.stream())
                        if (ignored != null && ignored != "crate") {
                            vars.add(ignored)
                        }
                        i += 2
                        continue
                    }
                }
                if (tt is TokenTree.Group) {
                    vars.addAll(collectDirectTemplateMetavars(tt.value.stream()))
                }
                i++
            }
            return vars
        }

        private fun collectAllTemplateMetavars(stream: TokenStream): Set<String> {
            val vars = linkedSetOf<String>()
            val trees = stream.clone().toList()
            var i = 0
            while (i < trees.size) {
                val tt = trees[i]
                if (tt is TokenTree.Punct && tt.value.asChar() == '$' && i + 1 < trees.size) {
                    val next = trees[i + 1]
                    if (next is TokenTree.Ident) {
                        val name = next.value.toString()
                        if (name != "crate") vars.add(name)
                        i += 2
                        continue
                    } else if (next is TokenTree.Group && next.value.delimiter() == Delimiter.Brace) {
                        val ignored = extractIgnoredMetavarName(next.value.stream())
                        if (ignored != null && ignored != "crate") {
                            vars.add(ignored)
                        }
                        i += 2
                        continue
                    }
                }
                if (tt is TokenTree.Group) {
                    vars.addAll(collectAllTemplateMetavars(tt.value.stream()))
                }
                i++
            }
            return vars
        }

        private fun templateRepetitionHeaderAdvance(trees: List<TokenTree>, dollarIdx: Int): Int {
            if (dollarIdx + 2 < trees.size) {
                val t1 = trees[dollarIdx + 2]
                if (t1 is TokenTree.Punct) {
                    val c1 = t1.value.asChar()
                    if (c1 == '*' || c1 == '+' || c1 == '?') {
                        return 3
                    }
                }
                if (dollarIdx + 3 < trees.size &&
                    (t1 is TokenTree.Punct || t1 is TokenTree.Ident) &&
                    trees[dollarIdx + 3] is TokenTree.Punct
                ) {
                    val c2 = (trees[dollarIdx + 3] as TokenTree.Punct).value.asChar()
                    if (c2 == '*' || c2 == '+') {
                        return 4
                    }
                }
            }
            return 2
        }

        internal fun substituteMacroTokensWithEnv(
            template: TokenStream,
            env: MacroMatchEnv,
            repIndexStack: List<Int> = emptyList(),
            repLenStack: List<Int> = emptyList(),
        ): TokenStream {
            val out = mutableListOf<TokenTree>()
            val trees = template.clone().toList()
            var i = 0
            while (i < trees.size) {
                val tt = trees[i]
                if (tt is TokenTree.Punct && tt.value.asChar() == '$' && i + 1 < trees.size) {
                    val next = trees[i + 1]
                    if (next is TokenTree.Ident) {
                        val varName = next.value.toString()
                        if (varName == "crate") {
                            out.add(TokenTree.Ident(PmIdent.new("crate", next.value.span())))
                            i += 2
                            continue
                        }
                        val replacement = env.singles[varName]
                        if (replacement != null) {
                            out.addAll(replacement)
                            i += 2
                            continue
                        }
                    } else if (next is TokenTree.Group && next.value.delimiter() == Delimiter.Brace) {
                        val evaluated = tryEvaluateMetavarExpr(next.value.stream(), env, repIndexStack, repLenStack)
                        if (evaluated != null) {
                            out.addAll(evaluated)
                            i += 2
                            continue
                        }
                    } else if (next is TokenTree.Group && next.value.delimiter() == Delimiter.Parenthesis) {
                        var sepToken: TokenTree? = null
                        val adv = templateRepetitionHeaderAdvance(trees, i)
                        if (adv == 4) {
                            sepToken = trees[i + 2]
                        }
                        val innerStream = next.value.stream()
                        var matchedGroup: RepetitionGroup? = null
                        val targetIterations =
                            if (env.repetitionGroups.isNotEmpty()) {
                                val directVars = collectDirectTemplateMetavars(innerStream.clone())
                                val allVars = collectAllTemplateMetavars(innerStream.clone())
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
                            val mergedEnv =
                                MacroMatchEnv(
                                    singles = mergedSingles,
                                    repetitions = childEnv.repetitions,
                                    repetitionGroups = mergedGroups,
                                    matchedKeywordSpans = mergedKeywords,
                                )
                            val innerSub =
                                substituteMacroTokensWithEnv(
                                    innerStream.clone(),
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
                    val inner = substituteMacroTokensWithEnv(tt.value.stream(), env, repIndexStack, repLenStack)
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

        internal fun splitMacroArguments(stream: TokenStream): List<List<TokenTree>> {
            val trees = stream.clone().toList()
            if (trees.isEmpty()) return emptyList()
            val commaArgs = splitByPunct(trees, ',')
            if (commaArgs.size > 1) return commaArgs
            val semiArgs = splitByPunct(trees, ';')
            if (semiArgs.size > 1) return semiArgs
            return commaArgs
        }

        internal fun splitTopLevelCommaClauses(trees: List<TokenTree>): List<List<TokenTree>> =
            splitByPunct(trees, ',')

        private fun hasMatchingClosingAngle(trees: List<TokenTree>, openIdx: Int): Boolean {
            if (openIdx > 0 && (trees[openIdx - 1] as? TokenTree.Punct)?.value?.asChar() == '<') {
                return false
            }
            if (openIdx + 1 < trees.size) {
                val nextPunct = (trees[openIdx + 1] as? TokenTree.Punct)?.value?.asChar()
                if (nextPunct == '=' || nextPunct == '<' || nextPunct == '-') return false
            }
            var depth = 1
            for (j in (openIdx + 1) until trees.size) {
                when (val t = trees[j]) {
                    is TokenTree.Literal -> return false
                    is TokenTree.Punct -> {
                        val ch = t.value.asChar()
                        if (ch == ';') return false
                        if (ch == '=' && (trees.getOrNull(j + 1) as? TokenTree.Punct)?.value?.asChar() == '>') {
                            return false
                        }
                        if (ch == '<') {
                            depth++
                        } else if (ch == '>' && !isPartOfRArrow(trees, j)) {
                            depth--
                            if (depth == 0) return true
                        }
                    }
                    else -> {}
                }
            }
            return false
        }

        private fun splitByPunct(trees: List<TokenTree>, sep: Char): List<List<TokenTree>> {
            val args = mutableListOf<List<TokenTree>>()
            val current = mutableListOf<TokenTree>()
            var angleDepth = 0
            for ((idx, tt) in trees.withIndex()) {
                if (tt is TokenTree.Punct) {
                    val ch = tt.value.asChar()
                    if (ch == '<' && hasMatchingClosingAngle(trees, idx)) {
                        angleDepth++
                    } else if (ch == '>' && angleDepth > 0 && !isPartOfRArrow(trees, idx)) {
                        angleDepth--
                    } else if (ch == sep && angleDepth == 0) {
                        if (current.isNotEmpty()) {
                            args.add(current.toList())
                            current.clear()
                        }
                        continue
                    }
                }
                current.add(tt)
            }
            if (current.isNotEmpty()) {
                args.add(current.toList())
            }
            return args
        }

        internal fun substituteMacroTokens(
            template: TokenStream,
            bindings: Map<String, List<TokenTree>>,
            repetitionBindings: List<Map<String, List<TokenTree>>> = emptyList(),
        ): TokenStream {
            val env =
                MacroMatchEnv(
                    singles = LinkedHashMap(bindings),
                    repetitions = repetitionBindings.map { MacroMatchEnv(singles = LinkedHashMap(it)) }.toMutableList(),
                )
            return substituteMacroTokensWithEnv(template, env)
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
    val lexed = TokenStream.fromString(sourceText)
    if (lexed.isSuccess()) {
        collectMacroRulesFromTokenTrees(lexed.getOrThrow().toList(), defs)
    }
    val result = defs.toMap()
    if (crateMacroDefsCache.size >= 64) {
        crateMacroDefsCache = mapOf(sourceText to result)
    } else {
        crateMacroDefsCache = crateMacroDefsCache + (sourceText to result)
    }
    return result
}

private fun hasSkippedMacroAttrs(
    attrGroups: List<List<TokenTree>>,
    skipCfgTest: Boolean,
): Boolean {
    for (attrTokens in attrGroups) {
        val firstIdent = (attrTokens.firstOrNull() as? TokenTree.Ident)?.value?.toString()
        if (firstIdent == "rustc_builtin_macro") return true
        if (attrTokens.size == 2 &&
            firstIdent == "cfg" &&
            (attrTokens[1] as? TokenTree.Group)?.value?.delimiter() == Delimiter.Parenthesis
        ) {
            val inner = (attrTokens[1] as TokenTree.Group).value.stream().toList()
            if (isDocOnlyCfgPredicateTokens(inner)) return true
            if (skipCfgTest && inner.size == 1 && (inner[0] as? TokenTree.Ident)?.value?.toString() == "test") {
                return true
            }
        }
    }
    return false
}

private fun hasSkippedExternalMacroAttrs(attrGroups: List<List<TokenTree>>): Boolean =
    hasSkippedMacroAttrs(attrGroups, skipCfgTest = true)

internal fun tryExtractDeclarativeMacro2FromTokens(
    trees: List<TokenTree>,
    skipCfgTest: Boolean,
): MacroRulesDef? {
    val attrGroups = mutableListOf<List<TokenTree>>()
    var idx = 0
    while (idx + 1 < trees.size &&
        (trees[idx] as? TokenTree.Punct)?.value?.asChar() == '#' &&
        (trees[idx + 1] as? TokenTree.Group)?.value?.delimiter() == Delimiter.Bracket
    ) {
        attrGroups += (trees[idx + 1] as TokenTree.Group).value.stream().toList()
        idx += 2
    }
    if (hasSkippedMacroAttrs(attrGroups, skipCfgTest)) return null
    if ((trees.getOrNull(idx) as? TokenTree.Ident)?.value?.toString() == "pub") {
        idx++
        if ((trees.getOrNull(idx) as? TokenTree.Group)?.value?.delimiter() == Delimiter.Parenthesis) {
            idx++
        }
    }
    if ((trees.getOrNull(idx) as? TokenTree.Ident)?.value?.toString() != "macro") return null
    val macroName = (trees.getOrNull(idx + 1) as? TokenTree.Ident)?.value?.toString()?.removePrefix("r#") ?: return null
    val firstGroup = (trees.getOrNull(idx + 2) as? TokenTree.Group)?.value ?: return null
    val branches =
        when (firstGroup.delimiter()) {
            Delimiter.Parenthesis -> {
                val bodyGroup = (trees.getOrNull(idx + 3) as? TokenTree.Group)?.value ?: return null
                if (bodyGroup.delimiter() != Delimiter.Brace) return null
                listOf(MacroRulesCollector.buildMacroRuleBranch(firstGroup.stream(), bodyGroup.stream()))
            }
            Delimiter.Brace -> MacroRulesCollector.parseMacroRulesBranches(firstGroup.stream())
            Delimiter.Bracket, Delimiter.None -> return null
        }
    if (branches.isEmpty()) return null
    return MacroRulesDef(macroName, branches)
}

internal fun collectMacroRulesFromTokenTrees(
    trees: List<TokenTree>,
    out: MutableMap<String, MacroRulesDef>,
) {
    val pendingAttrs = mutableListOf<List<TokenTree>>()
    var i = 0
    while (i < trees.size) {
        val cur = trees[i]
        if (cur is TokenTree.Punct && cur.value.asChar() == '#') {
            if ((trees.getOrNull(i + 1) as? TokenTree.Punct)?.value?.asChar() == '!' &&
                (trees.getOrNull(i + 2) as? TokenTree.Group)?.value?.delimiter() == Delimiter.Bracket
            ) {
                i += 3
                continue
            }
            val attrGrp = trees.getOrNull(i + 1) as? TokenTree.Group
            if (attrGrp != null && attrGrp.value.delimiter() == Delimiter.Bracket) {
                pendingAttrs += attrGrp.value.stream().toList()
                i += 2
                continue
            }
        }
        var afterVis = i
        if ((cur as? TokenTree.Ident)?.value?.toString() == "pub") {
            afterVis++
            if ((trees.getOrNull(afterVis) as? TokenTree.Group)?.value?.delimiter() == Delimiter.Parenthesis) {
                afterVis++
            }
        }
        if ((cur as? TokenTree.Ident)?.value?.toString() == "macro_rules" &&
            (trees.getOrNull(i + 1) as? TokenTree.Punct)?.value?.asChar() == '!' &&
            trees.getOrNull(i + 2) is TokenTree.Ident &&
            trees.getOrNull(i + 3) is TokenTree.Group
        ) {
            val macroName = (trees[i + 2] as TokenTree.Ident).value.toString().removePrefix("r#")
            val bodyGroup = (trees[i + 3] as TokenTree.Group).value
            if (!hasSkippedExternalMacroAttrs(pendingAttrs) && macroName != "offset_of" && macroName !in out) {
                val branches = MacroRulesCollector.parseMacroRulesBranches(bodyGroup.stream())
                if (branches.isNotEmpty() && !isRawPointerLiteralMacroBranches(branches)) {
                    out[macroName] = MacroRulesDef(macroName, branches)
                }
            }
            i += 4
            if ((trees.getOrNull(i) as? TokenTree.Punct)?.value?.asChar() == ';') {
                i++
            }
            pendingAttrs.clear()
            continue
        }
        if ((trees.getOrNull(afterVis) as? TokenTree.Ident)?.value?.toString() == "macro" &&
            trees.getOrNull(afterVis + 1) is TokenTree.Ident &&
            trees.getOrNull(afterVis + 2) is TokenTree.Group
        ) {
            val macroName = (trees[afterVis + 1] as TokenTree.Ident).value.toString().removePrefix("r#")
            val firstGroup = (trees[afterVis + 2] as TokenTree.Group).value
            var nextIdx: Int? = null
            val branches: List<MacroRuleBranch> =
                when (firstGroup.delimiter()) {
                    Delimiter.Parenthesis -> {
                        val bodyGrp = (trees.getOrNull(afterVis + 3) as? TokenTree.Group)?.value
                        if (bodyGrp != null && bodyGrp.delimiter() == Delimiter.Brace) {
                            nextIdx = afterVis + 4
                            listOf(MacroRulesCollector.buildMacroRuleBranch(firstGroup.stream(), bodyGrp.stream()))
                        } else {
                            emptyList()
                        }
                    }
                    Delimiter.Brace -> {
                        nextIdx = afterVis + 3
                        MacroRulesCollector.parseMacroRulesBranches(firstGroup.stream())
                    }
                    Delimiter.Bracket, Delimiter.None -> emptyList()
                }
            if (nextIdx != null) {
                if (!hasSkippedExternalMacroAttrs(pendingAttrs) && macroName != "offset_of" && macroName !in out && branches.isNotEmpty()) {
                    if (!isRawPointerLiteralMacroBranches(branches)) {
                        out[macroName] = MacroRulesDef(macroName, branches)
                    }
                }
                i = nextIdx
                pendingAttrs.clear()
                continue
            }
        }
        if ((trees.getOrNull(afterVis) as? TokenTree.Ident)?.value?.toString() == "mod" &&
            trees.getOrNull(afterVis + 1) is TokenTree.Ident
        ) {
            val modBody = trees.getOrNull(afterVis + 2)
            if (modBody is TokenTree.Group && modBody.value.delimiter() == Delimiter.Brace) {
                if (!hasSkippedExternalMacroAttrs(pendingAttrs)) {
                    collectMacroRulesFromTokenTrees(modBody.value.stream().toList(), out)
                }
                i = afterVis + 3
                pendingAttrs.clear()
                continue
            }
            if ((modBody as? TokenTree.Punct)?.value?.asChar() == ';') {
                i = afterVis + 3
                pendingAttrs.clear()
                continue
            }
        }
        if ((cur is TokenTree.Punct && cur.value.asChar() == ';') ||
            (cur is TokenTree.Group && cur.value.delimiter() == Delimiter.Brace)
        ) {
            pendingAttrs.clear()
        }
        i++
    }
}

public fun extractMacroUseModuleNames(rustSource: String): List<String> {
    if (!rustSource.contains("macro_use")) return emptyList()
    val lexed = TokenStream.fromString(rustSource)
    if (lexed.isFailure()) return emptyList()
    val trees = lexed.getOrThrow().toList()
    val names = mutableListOf<String>()
    var i = 0
    while (i < trees.size) {
        if ((trees[i] as? TokenTree.Punct)?.value?.asChar() == '#' &&
            (trees.getOrNull(i + 1) as? TokenTree.Group)?.value?.delimiter() == Delimiter.Bracket
        ) {
            val attrTokens = (trees[i + 1] as TokenTree.Group).value.stream().toList()
            val firstAttrIdent = (attrTokens.firstOrNull() as? TokenTree.Ident)?.value?.toString()
            if (firstAttrIdent == "macro_use") {
                var j = i + 2
                while (j + 1 < trees.size &&
                    (trees[j] as? TokenTree.Punct)?.value?.asChar() == '#' &&
                    (trees[j + 1] as? TokenTree.Group)?.value?.delimiter() == Delimiter.Bracket
                ) {
                    j += 2
                }
                if ((trees.getOrNull(j) as? TokenTree.Ident)?.value?.toString() == "pub") {
                    j++
                    if ((trees.getOrNull(j) as? TokenTree.Group)?.value?.delimiter() == Delimiter.Parenthesis) {
                        j++
                    }
                }
                if ((trees.getOrNull(j) as? TokenTree.Ident)?.value?.toString() == "mod") {
                    val modName = (trees.getOrNull(j + 1) as? TokenTree.Ident)?.value?.toString()
                    if (modName != null && (trees.getOrNull(j + 2) as? TokenTree.Punct)?.value?.asChar() == ';') {
                        names += modName.removePrefix("r#")
                        i = j + 3
                        continue
                    }
                }
            }
        }
        i++
    }
    return names.distinct()
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
    val byStart = linkedMapOf<Int, Span>()
    var prevIdentText: String? = null
    var angleDepth = 0
    for (tt in node.toTokenStream()) {
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
                    ((ch == ':' && tt.value.spacing() != Spacing.Joint && prevIdentText != null) || ch == '=' || ch == ';')
                ) {
                    break
                }
                prevIdentText = null
            }
            is TokenTree.Ident -> {
                prevIdentText = tt.value.toString()
            }
            is TokenTree.Literal -> {
                prevIdentText = null
            }
        }
        val sp = tt.span()
        val start = sp.startOffset()
        val end = sp.endExclusiveOffset()
        if (end > start && start >= 0 && start !in byStart) {
            byStart[start] = sp
        }
    }
    if (byStart.isEmpty()) {
        val fallback = node.span()
        val start = fallback.startOffset()
        val end = fallback.endExclusiveOffset()
        if (end > start && start >= 0) {
            byStart[start] = fallback
        }
    }
    return byStart.values.sortedBy { it.startOffset() }
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
