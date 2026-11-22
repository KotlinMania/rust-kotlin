package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.procmacro2.Delimiter
import io.github.kotlinmania.procmacro2.Group
import io.github.kotlinmania.procmacro2.Spacing
import io.github.kotlinmania.procmacro2.TokenStream
import io.github.kotlinmania.procmacro2.TokenTree

/**
 * Extensible cursor and structural helper base class over `proc_macro2` [TokenTree] lists.
 *
 * Centralizes Rust token-level navigation (`#[...]` / `#![...]` attributes, `pub(...)` visibility,
 * `::` path separators, `->` / `=>` arrows, generic `<...>` vs comparison disambiguation,
 * `Delimiter.None` group unwrapping, and top-level `,` / `;` splitting) so scanners,
 * macro expanders, frontend normalizers, and comment/doctest translators share one implementation.
 */
internal open class RustTokenCursor(
    val trees: List<TokenTree>,
) {
    val size: Int
        get() = trees.size

    fun identAt(idx: Int): String? =
        (trees.getOrNull(idx) as? TokenTree.Ident)?.value?.toString()

    fun cleanIdentAt(idx: Int): String? =
        identAt(idx)?.removePrefix("r#")

    fun punctCharAt(idx: Int): Char? =
        (trees.getOrNull(idx) as? TokenTree.Punct)?.value?.asChar()

    fun punctSpacingAt(idx: Int): Spacing? =
        (trees.getOrNull(idx) as? TokenTree.Punct)?.value?.spacing()

    fun isLifetimeAt(idx: Int): Boolean {
        val p0 = (trees.getOrNull(idx) as? TokenTree.Punct)?.value ?: return false
        return p0.asChar() == '\'' && p0.spacing() == Spacing.Joint && trees.getOrNull(idx + 1) is TokenTree.Ident
    }

    fun groupAt(idx: Int, delimiter: Delimiter? = null): Group? {
        val grp = (trees.getOrNull(idx) as? TokenTree.Group)?.value ?: return null
        return if (delimiter == null || grp.delimiter() == delimiter) grp else null
    }

    fun isDoubleColonAt(idx: Int): Boolean {
        if (idx + 1 >= trees.size) return false
        val p0 = (trees[idx] as? TokenTree.Punct)?.value ?: return false
        val p1 = (trees[idx + 1] as? TokenTree.Punct)?.value ?: return false
        return p0.asChar() == ':' && p0.spacing() == Spacing.Joint && p1.asChar() == ':'
    }

    fun isRArrowEndAt(gtIdx: Int): Boolean {
        if (gtIdx <= 0) return false
        val prev = (trees.getOrNull(gtIdx - 1) as? TokenTree.Punct)?.value ?: return false
        return prev.asChar() == '-' && prev.spacing() == Spacing.Joint
    }

    fun isFatArrowAt(eqIdx: Int): Boolean {
        if (eqIdx + 1 >= trees.size) return false
        val eq = (trees[eqIdx] as? TokenTree.Punct)?.value ?: return false
        val gt = (trees[eqIdx + 1] as? TokenTree.Punct)?.value ?: return false
        return eq.asChar() == '=' && eq.spacing() == Spacing.Joint && gt.asChar() == '>'
    }

    fun consumeAttribute(
        idx: Int,
        pendingAttrsOut: MutableList<List<TokenTree>>? = null,
        allowInnerAttr: Boolean = true,
    ): Int? {
        if (punctCharAt(idx) != '#') return null
        if (allowInnerAttr && punctCharAt(idx + 1) == '!' && groupAt(idx + 2, Delimiter.Bracket) != null) {
            return idx + 3
        }
        val attrGrp = groupAt(idx + 1, Delimiter.Bracket)
        if (attrGrp != null) {
            pendingAttrsOut?.add(attrGrp.stream().toList())
            return idx + 2
        }
        return null
    }

    fun copyLeadingOuterAttributes(startIdx: Int, out: MutableList<TokenTree>): Int {
        var idx = startIdx
        while (punctCharAt(idx) == '#' && groupAt(idx + 1, Delimiter.Bracket) != null) {
            out += trees[idx]
            out += trees[idx + 1]
            idx += 2
        }
        return idx
    }

    fun copyOptionalPubVisibility(startIdx: Int, out: MutableList<TokenTree>? = null): Int {
        if (identAt(startIdx) != "pub") return startIdx
        out?.add(trees[startIdx])
        var idx = startIdx + 1
        if (groupAt(idx, Delimiter.Parenthesis) != null) {
            out?.add(trees[idx])
            idx++
        }
        return idx
    }

    fun skipBalancedAngles(openIdx: Int): Int? {
        if (punctCharAt(openIdx) != '<') return null
        var depth = 1
        var k = openIdx + 1
        while (k < trees.size && depth > 0) {
            val ch = punctCharAt(k)
            if (ch == '<') {
                depth++
            } else if (ch == '>' && !isRArrowEndAt(k)) {
                depth--
            }
            k++
        }
        return if (depth == 0) k else null
    }

    fun copyBalancedAngles(openIdx: Int, out: MutableList<TokenTree>): Int {
        if (punctCharAt(openIdx) != '<') return openIdx
        var angleDepth = 0
        var idx = openIdx
        while (idx < trees.size) {
            val tt = trees[idx]
            out += tt
            idx++
            if (tt is TokenTree.Punct) {
                val ch = tt.value.asChar()
                if (ch == '<') {
                    angleDepth++
                } else if (ch == '>' && !isRArrowEndAt(idx - 1) && angleDepth > 0) {
                    angleDepth--
                    if (angleDepth == 0) break
                }
            }
        }
        return idx
    }

    fun hasMatchingClosingAngle(openIdx: Int): Boolean {
        if (openIdx > 0 && punctCharAt(openIdx - 1) == '<') return false
        if (openIdx + 1 < trees.size) {
            val nextPunct = punctCharAt(openIdx + 1)
            if (nextPunct == '=' || nextPunct == '<' || nextPunct == '-') return false
        }
        val isTurbofish = openIdx >= 2 && punctCharAt(openIdx - 1) == ':' && punctCharAt(openIdx - 2) == ':'
        val afterUpperIdent = identAt(openIdx - 1)?.firstOrNull()?.isUpperCase() == true
        val allowLiterals = isTurbofish || afterUpperIdent
        var depth = 1
        for (j in (openIdx + 1) until trees.size) {
            when (trees[j]) {
                is TokenTree.Literal -> if (!allowLiterals) return false
                is TokenTree.Punct -> {
                    val ch = punctCharAt(j)!!
                    if (ch == ';') return false
                    val nextCh = punctCharAt(j + 1)
                    if ((ch == '=' && nextCh == '>') ||
                        (ch == '&' && nextCh == '&') ||
                        (ch == '|' && nextCh == '|') ||
                        (ch == '=' && nextCh == '=') ||
                        (ch == '!' && nextCh == '=') ||
                        (ch == '<' && nextCh == '=') ||
                        (ch == '>' && nextCh == '=')
                    ) {
                        return false
                    }
                    if (ch == '<') {
                        depth++
                    } else if (ch == '>' && !isRArrowEndAt(j)) {
                        depth--
                        if (depth == 0) return true
                    }
                }
                else -> Unit
            }
        }
        return false
    }

    fun splitByTopLevelPunct(
        sep: Char,
        turbofishOnlyAngles: Boolean = false,
    ): List<List<TokenTree>> {
        if (trees.isEmpty()) return emptyList()
        val args = mutableListOf<List<TokenTree>>()
        val current = mutableListOf<TokenTree>()
        var angleDepth = 0
        for ((idx, tt) in trees.withIndex()) {
            if (tt is TokenTree.Punct) {
                val ch = tt.value.asChar()
                val opensAngle =
                    if (turbofishOnlyAngles) {
                        ch == '<' && idx >= 2 && punctCharAt(idx - 1) == ':' && punctCharAt(idx - 2) == ':'
                    } else {
                        ch == '<' && hasMatchingClosingAngle(idx)
                    }
                if (opensAngle) {
                    angleDepth++
                } else if (ch == '>' && angleDepth > 0 && !isRArrowEndAt(idx)) {
                    angleDepth--
                } else if (ch == sep && angleDepth == 0) {
                    if (current.isNotEmpty()) {
                        args += current.toList()
                        current.clear()
                    }
                    continue
                }
            }
            current += tt
        }
        if (current.isNotEmpty()) {
            args += current.toList()
        }
        return args
    }

    companion object {
        fun flattenNoneDelimitedGroups(stream: TokenStream, out: MutableList<TokenTree>) {
            for (tt in stream.clone().toList()) {
                if (tt is TokenTree.Group && tt.value.delimiter() == Delimiter.None) {
                    flattenNoneDelimitedGroups(tt.value.stream(), out)
                } else {
                    out += tt
                }
            }
        }

        fun flattenNoneDelimitedGroupList(trees: List<TokenTree>): List<TokenTree> {
            val out = mutableListOf<TokenTree>()
            for (tt in trees) {
                if (tt is TokenTree.Group && tt.value.delimiter() == Delimiter.None) {
                    flattenNoneDelimitedGroups(tt.value.stream(), out)
                } else {
                    out += tt
                }
            }
            return out
        }

        fun unwrapNoneGroupSlice(trees: List<TokenTree>): List<TokenTree> {
            var cur = trees
            while (cur.size == 1 && cur[0] is TokenTree.Group && (cur[0] as TokenTree.Group).value.delimiter() == Delimiter.None) {
                cur = (cur[0] as TokenTree.Group).value.stream().toList()
            }
            return cur
        }

        fun splitMacroArguments(stream: TokenStream): List<List<TokenTree>> {
            val trees = stream.clone().toList()
            if (trees.isEmpty()) return emptyList()
            val cursor = RustTokenCursor(trees)
            val commaArgs = cursor.splitByTopLevelPunct(',')
            if (commaArgs.size > 1) return commaArgs
            val semiArgs = cursor.splitByTopLevelPunct(';')
            if (semiArgs.size > 1) return semiArgs
            return commaArgs
        }

        fun splitMacroCommaArgsWithComparisonFallback(stream: TokenStream): List<List<TokenTree>> {
            val parts = splitMacroArguments(stream)
            if (parts.size >= 2) return parts
            val trees = stream.clone().toList()
            if (trees.isEmpty()) return emptyList()
            return RustTokenCursor(trees).splitByTopLevelPunct(',', turbofishOnlyAngles = true)
        }

        fun resolveSynTokenIdentOrPunctTypeName(symbol: String): String? =
            when (symbol) {
                "Self" -> "SelfType"
                "self" -> "SelfValue"
                "type" -> "SynTypeToken"
                "_" -> "Underscore"
                in SYN_KEYWORD_TOKENS -> symbol[0].uppercaseChar() + symbol.substring(1)
                else -> SYN_PUNCT_TOKEN_NAMES[symbol]
            }

        fun resolveSynTokenStreamTypeName(tokens: TokenStream): String? {
            val trees = tokens.clone().toList()
            if (trees.isEmpty()) return null
            if (trees.size == 1 && trees[0] is TokenTree.Ident) {
                return resolveSynTokenIdentOrPunctTypeName((trees[0] as TokenTree.Ident).value.toString())
            }
            if (trees.size <= 3 && trees.all { it is TokenTree.Punct }) {
                for (idx in 0 until trees.lastIndex) {
                    val p = (trees[idx] as TokenTree.Punct).value
                    if (p.spacing() != Spacing.Joint) return null
                }
                val seq = buildString(trees.size) { trees.forEach { append((it as TokenTree.Punct).value.asChar()) } }
                return SYN_PUNCT_TOKEN_NAMES[seq]
            }
            return null
        }
    }
}

/**
 * Shared representation and evaluation engine for conditional macro branch arms
 * across both `cfg_select! { pred => { ... }, _ => { ... } }` and `cfg_if! { if #[cfg(pred)] { ... } else { ... } }`.
 */
internal data class CfgBranchArm(
    val predicateTokens: List<TokenTree>?,
    val bodyTokens: List<TokenTree>,
    val rejectDocOnlyPredicate: Boolean = false,
)

internal abstract class CfgArmSelector<T> {
    protected abstract fun parseArmBody(bodyTokens: List<TokenTree>): List<T>?

    protected abstract fun extractArmKeys(parsed: List<T>): List<String>?

    protected open fun shouldSkipWildcardArm(
        armKeys: List<String>,
        seenKeys: Set<String>,
    ): Boolean = false

    fun selectActive(arms: List<CfgBranchArm>): List<T>? {
        val candidates = mutableListOf<Pair<CfgBranchArm, Boolean>>()
        for (arm in arms) {
            val pred = arm.predicateTokens
            if (pred == null) {
                candidates += arm to true
                break
            }
            if (arm.rejectDocOnlyPredicate && isDocOnlyCfgPredicateTokens(pred)) {
                continue
            }
            when (evalCfgTriState(pred, forRuntimeCfgMacro = false)) {
                true -> return parseArmBody(arm.bodyTokens)
                false -> Unit
                null -> candidates += arm to false
            }
        }
        if (candidates.isEmpty()) return emptyList()
        val hasNonWildcard = candidates.any { !it.second }
        val seenKeys = linkedSetOf<String>()
        val collected = mutableListOf<T>()
        for ((arm, isWildcard) in candidates) {
            if (arm.bodyTokens.isEmpty()) continue
            val parsed = parseArmBody(arm.bodyTokens) ?: return null
            val armKeys = extractArmKeys(parsed)
            if (armKeys == null) {
                if (!isWildcard || !hasNonWildcard) {
                    collected += parsed
                }
                continue
            }
            if (isWildcard && hasNonWildcard && shouldSkipWildcardArm(armKeys, seenKeys)) {
                continue
            }
            if (armKeys.isNotEmpty() && armKeys.all { it in seenKeys }) {
                continue
            }
            seenKeys += armKeys
            collected += parsed
        }
        return collected
    }
}

internal fun parseCfgSelectArms(innerTrees: List<TokenTree>): List<CfgBranchArm>? {
    if (innerTrees.isEmpty()) return emptyList()
    val cursor = RustTokenCursor(innerTrees)
    val arms = mutableListOf<CfgBranchArm>()
    var idx = 0
    while (idx < innerTrees.size) {
        var arrowIdx = -1
        for (scan in idx until innerTrees.size - 1) {
            if (cursor.isFatArrowAt(scan)) {
                arrowIdx = scan
                break
            }
        }
        if (arrowIdx <= idx) return null
        val predSlice = innerTrees.subList(idx, arrowIdx)
        val isWildcard = predSlice.size == 1 && cursor.identAt(idx) == "_"
        val bodyStart = arrowIdx + 2
        if (bodyStart > innerTrees.size) return null
        val braceGroup = cursor.groupAt(bodyStart, Delimiter.Brace)
        val (bodyTokens, nextIdx) =
            if (braceGroup != null) {
                val afterGrp = bodyStart + 1
                val nextAfter = if (cursor.punctCharAt(afterGrp) == ',') afterGrp + 1 else afterGrp
                braceGroup.stream().toList() to nextAfter
            } else {
                var commaIdx = bodyStart
                while (commaIdx < innerTrees.size && cursor.punctCharAt(commaIdx) != ',') {
                    commaIdx++
                }
                val slice = innerTrees.subList(bodyStart, commaIdx)
                val nextAfter = if (commaIdx < innerTrees.size) commaIdx + 1 else commaIdx
                slice to nextAfter
            }
        arms += CfgBranchArm(predicateTokens = if (isWildcard) null else predSlice, bodyTokens = bodyTokens)
        idx = nextIdx
    }
    return arms
}

internal fun parseCfgIfArms(innerTrees: List<TokenTree>): List<CfgBranchArm>? {
    val cursor = RustTokenCursor(innerTrees)
    if (cursor.identAt(0) != "if") return null
    val arms = mutableListOf<CfgBranchArm>()
    var idx = 0
    while (idx < innerTrees.size) {
        if (cursor.identAt(idx) != "if") break
        val attrGrp = cursor.groupAt(idx + 2, Delimiter.Bracket)
        val bodyGrp = cursor.groupAt(idx + 3, Delimiter.Brace)
        if (cursor.punctCharAt(idx + 1) != '#' || attrGrp == null || bodyGrp == null) {
            return null
        }
        val attrCursor = RustTokenCursor(attrGrp.stream().toList())
        val predGrp = attrCursor.groupAt(1, Delimiter.Parenthesis)
        if (attrCursor.size != 2 || attrCursor.identAt(0) != "cfg" || predGrp == null) {
            return null
        }
        arms +=
            CfgBranchArm(
                predicateTokens = predGrp.stream().toList(),
                bodyTokens = bodyGrp.stream().toList(),
                rejectDocOnlyPredicate = true,
            )
        idx += 4
        if (cursor.identAt(idx) == "else") {
            idx++
            if (cursor.identAt(idx) == "if") continue
            val elseBody = cursor.groupAt(idx, Delimiter.Brace)
            if (elseBody != null) {
                arms += CfgBranchArm(predicateTokens = null, bodyTokens = elseBody.stream().toList())
                idx++
            }
        }
    }
    return arms
}

internal fun partitionCfgBranchArms(arms: List<CfgBranchArm>): List<Pair<List<TokenTree>, Boolean>> {
    val result = mutableListOf<Pair<List<TokenTree>, Boolean>>()
    var matchedActive = false
    for (arm in arms) {
        if (matchedActive) {
            result += arm.bodyTokens to false
            continue
        }
        val pred = arm.predicateTokens
        if (pred == null) {
            matchedActive = true
            result += arm.bodyTokens to true
            continue
        }
        if (arm.rejectDocOnlyPredicate && isDocOnlyCfgPredicateTokens(pred)) {
            result += arm.bodyTokens to false
            continue
        }
        when (evalCfgTriState(pred, forRuntimeCfgMacro = false)) {
            true -> {
                matchedActive = true
                result += arm.bodyTokens to true
            }
            false -> result += arm.bodyTokens to false
            null -> result += arm.bodyTokens to true
        }
    }
    return result
}
