package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.procmacro2.Delimiter
import io.github.kotlinmania.procmacro2.Group
import io.github.kotlinmania.procmacro2.Ident
import io.github.kotlinmania.procmacro2.Literal
import io.github.kotlinmania.procmacro2.Punct
import io.github.kotlinmania.procmacro2.Spacing
import io.github.kotlinmania.procmacro2.Span
import io.github.kotlinmania.procmacro2.TokenStream
import io.github.kotlinmania.procmacro2.TokenTree
import io.github.kotlinmania.syn.Lit

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

    fun stringLiteralAt(idx: Int): String? {
        val litTree = trees.getOrNull(idx) as? TokenTree.Literal ?: return null
        return (Lit.new(litTree.value) as? Lit.Str)?.value?.value()
    }

    fun longLiteralAt(idx: Int): Long? =
        trees.getOrNull(idx)?.let(::parseIntegerLiteral)

    fun intLiteralAt(idx: Int): Int? =
        longLiteralAt(idx)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

    fun hasOuterAttributeIdent(attrIdent: String): Boolean {
        var idx = 0
        val attrGroups = mutableListOf<List<TokenTree>>()
        while (true) {
            val next = consumeAttribute(idx, attrGroups, allowInnerAttr = false) ?: break
            idx = next
        }
        return attrGroups.any { RustTokenCursor(it).cleanIdentAt(0) == attrIdent }
    }

    fun consumeJointOperator(idx: Int): Pair<String, Int>? {
        val c0 = punctCharAt(idx) ?: return null
        if (punctSpacingAt(idx) != Spacing.Joint) return null
        val c1 = punctCharAt(idx + 1) ?: return null
        return when (val pair = "$c0$c1") {
            "->", "=>", "==", "!=", "<=", ">=", "&&", "||", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<", ">>" ->
                pair to 2
            ".." -> {
                if (punctSpacingAt(idx + 1) == Spacing.Joint) {
                    val c2 = punctCharAt(idx + 2)
                    if (c2 == '=' || c2 == '.') return "..$c2" to 3
                }
                ".." to 2
            }
            else -> null
        }
    }

    fun extractGroupInnerSource(source: String, idx: Int, delimiter: Delimiter? = null): String? {
        val grp = groupAt(idx, delimiter) ?: return null
        val openEnd = grp.spanOpen().endExclusiveOffset()
        val closeStart = grp.spanClose().startOffset()
        return if (openEnd >= 0 && closeStart >= openEnd && closeStart <= source.length) {
            source.substring(openEnd, closeStart)
        } else {
            grp.stream().toString()
        }
    }

    fun extractSliceSource(source: String, startIdx: Int = 0, endIdx: Int = trees.size): String {
        val slice = trees.subList(startIdx, endIdx)
        if (slice.isEmpty()) return ""
        val start = tokenTreeStartOffset(slice.first())
        val end = tokenTreeEndOffset(slice.last())
        return if (start >= 0 && end >= start && end <= source.length) {
            source.substring(start, end)
        } else {
            TokenStream.fromTokenTrees(slice).toString()
        }
    }

    fun isDoubleColonAt(idx: Int): Boolean {
        if (idx + 1 >= trees.size) return false
        val p0 = (trees[idx] as? TokenTree.Punct)?.value ?: return false
        val p1 = (trees[idx + 1] as? TokenTree.Punct)?.value ?: return false
        return p0.asChar() == ':' && p0.spacing() == Spacing.Joint && p1.asChar() == ':'
    }

    fun isRArrowStartAt(minusIdx: Int): Boolean =
        punctCharAt(minusIdx) == '-' && punctSpacingAt(minusIdx) == Spacing.Joint && punctCharAt(minusIdx + 1) == '>'

    fun isRArrowEndAt(gtIdx: Int): Boolean {
        if (gtIdx <= 0) return false
        val prev = (trees.getOrNull(gtIdx - 1) as? TokenTree.Punct)?.value ?: return false
        return prev.asChar() == '-' && prev.spacing() == Spacing.Joint
    }

    fun isSingleColonAt(idx: Int): Boolean =
        punctCharAt(idx) == ':' && punctSpacingAt(idx) != Spacing.Joint

    fun isUnitParens(): Boolean =
        trees.size == 1 && groupAt(0, Delimiter.Parenthesis)?.stream()?.isEmpty() == true

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

    fun stripLeadingPubVisibility(): List<TokenTree> {
        val nextIdx = copyOptionalPubVisibility(0)
        return if (nextIdx == 0) trees else trees.subList(nextIdx, trees.size)
    }

    fun findPathTailIdentIndex(startIdx: Int = 0): Int? {
        var i = startIdx
        if (i + 1 < trees.size && punctCharAt(i) == ':' && punctCharAt(i + 1) == ':') {
            i += 2
        }
        if (trees.getOrNull(i) !is TokenTree.Ident) return null
        var lastIdent = i
        i++
        while (i + 2 < trees.size &&
            punctCharAt(i) == ':' &&
            punctCharAt(i + 1) == ':' &&
            trees[i + 2] is TokenTree.Ident
        ) {
            lastIdent = i + 2
            i += 3
        }
        return lastIdent
    }

    fun simplePathLeafIdent(startIdx: Int = 0): String? =
        findPathTailIdentIndex(startIdx)?.let(::cleanIdentAt)

    fun extractPureColonPathSegments(
        allowTrailingBang: Boolean = false,
        allowTrailingEmptyParens: Boolean = false,
    ): Pair<List<String>, Boolean>? {
        if (size == 0) return null
        var idx = if (isDoubleColonAt(0)) 2 else 0
        val first = cleanIdentAt(idx) ?: return null
        val segments = mutableListOf(first)
        idx++
        while (idx < size && isDoubleColonAt(idx)) {
            val nextSeg = cleanIdentAt(idx + 2) ?: return null
            segments += nextSeg
            idx += 3
        }
        var hadBang = false
        if (allowTrailingBang && idx < size && punctCharAt(idx) == '!') {
            hadBang = true
            idx++
        }
        if (allowTrailingEmptyParens && idx < size) {
            val parenGrp = groupAt(idx, Delimiter.Parenthesis)
            if (parenGrp != null && parenGrp.stream().isEmpty()) {
                idx++
            }
        }
        return if (idx == size) segments to hadBang else null
    }

    fun skipFnQualifiersToFnKeyword(
        startIdx: Int,
        endIdx: Int = trees.size,
        allowBareLiteral: Boolean = false,
    ): Int? {
        var j = startIdx
        val limit = minOf(endIdx, trees.size)
        while (j < limit) {
            when (val cur = trees[j]) {
                is TokenTree.Ident ->
                    when (cur.value.toString()) {
                        "const", "unsafe", "async" -> j++
                        "extern" -> {
                            j++
                            if (j < limit && trees[j] is TokenTree.Literal) j++
                        }
                        "fn" -> return j
                        else -> return null
                    }
                is TokenTree.Literal -> if (allowBareLiteral) j++ else return null
                else -> return null
            }
        }
        return null
    }

    fun skipAfterItemHeader(
        startIdx: Int,
        semicolonOnly: Boolean = false,
    ): Int {
        var j = startIdx
        while (j < trees.size) {
            if (punctCharAt(j) == ';') return j + 1
            if (!semicolonOnly && groupAt(j, Delimiter.Brace) != null && punctCharAt(j - 1) != '<') {
                return j + 1
            }
            j++
        }
        return trees.size
    }

    fun skipQualifiers(startIdx: Int, qualifiers: Set<String>): Int {
        var idx = startIdx
        while (identAt(idx) in qualifiers) idx++
        return idx
    }

    fun extractLeadingPathIdents(startIdx: Int = 0): Pair<List<String>, Int> {
        var idx = if (isDoubleColonAt(startIdx)) startIdx + 2 else startIdx
        val first = cleanIdentAt(idx) ?: return emptyList<String>() to startIdx
        val segments = mutableListOf(first)
        idx++
        while (idx < size && isDoubleColonAt(idx)) {
            val nextSeg = cleanIdentAt(idx + 2) ?: break
            segments += nextSeg
            idx += 3
        }
        return segments to idx
    }

    fun matchModDeclAt(startIdx: Int): Triple<Boolean, String, Int>? {
        val isPub = identAt(startIdx) == "pub"
        val kwIdx = if (isPub) copyOptionalPubVisibility(startIdx) else startIdx
        if (identAt(kwIdx) != "mod") return null
        val modName = cleanIdentAt(kwIdx + 1) ?: return null
        if (punctCharAt(kwIdx + 2) != ';') return null
        return Triple(isPub, modName, kwIdx + 3)
    }

    fun matchMacroRulesDefAt(startIdx: Int): Pair<String, Int>? {
        if (identAt(startIdx) != "macro_rules" || punctCharAt(startIdx + 1) != '!') return null
        var k = startIdx + 2
        val macroName = cleanIdentAt(k).orEmpty()
        if (identAt(k) != null) k++
        if (groupAt(k) == null) return null
        k++
        if (punctCharAt(k) == ';') k++
        return macroName to k
    }

    fun matchExternBlockBraceIndex(startIdx: Int): Int? {
        var j = startIdx
        if (identAt(j) == "unsafe") j++
        if (identAt(j) != "extern") return null
        j++
        if (trees.getOrNull(j) is TokenTree.Literal) j++
        return if (groupAt(j, Delimiter.Brace) != null) j else null
    }

    fun extractAngleInnerSlice(openAngleIdx: Int): Pair<List<TokenTree>, Int>? {
        val afterCloseIdx = skipBalancedAngles(openAngleIdx) ?: return null
        return trees.subList(openAngleIdx + 1, afterCloseIdx - 1) to afterCloseIdx
    }

    fun extractBracketArrayTypeAndLen(idx: Int = 0): Pair<List<TokenTree>, List<TokenTree>>? {
        val bracketGrp = groupAt(idx, Delimiter.Bracket) ?: return null
        val inner = bracketGrp.stream().toList()
        val semiParts = RustTokenCursor(inner).splitByTopLevelPunct(';')
        return if (semiParts.size == 2 && semiParts[0].isNotEmpty() && semiParts[1].isNotEmpty()) {
            semiParts[0] to semiParts[1]
        } else {
            null
        }
    }

    fun collectTopLevelIdentsOutsideAngles(): List<String> {
        val out = mutableListOf<String>()
        anyTopLevelOutsideAngles { idx ->
            cleanIdentAt(idx)?.let { out += it }
            false
        }
        return out
    }

    inline fun anyTopLevelOutsideAngles(
        startIdx: Int = 0,
        endIdx: Int = trees.size,
        predicate: (idx: Int) -> Boolean,
    ): Boolean {
        var angleDepth = 0
        val limit = minOf(endIdx, trees.size)
        for (idx in startIdx until limit) {
            when (punctCharAt(idx)) {
                '<' -> angleDepth++
                '>' -> if (angleDepth > 0 && !isRArrowEndAt(idx)) angleDepth--
                else -> if (angleDepth == 0 && predicate(idx)) return true
            }
        }
        return false
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
        unconditionalAngles: Boolean = false,
    ): List<List<TokenTree>> {
        if (trees.isEmpty()) return emptyList()
        val args = mutableListOf<List<TokenTree>>()
        val current = mutableListOf<TokenTree>()
        var angleDepth = 0
        for ((idx, tt) in trees.withIndex()) {
            if (tt is TokenTree.Punct) {
                val ch = tt.value.asChar()
                val opensAngle =
                    when {
                        unconditionalAngles -> ch == '<'
                        turbofishOnlyAngles -> ch == '<' && idx >= 2 && punctCharAt(idx - 1) == ':' && punctCharAt(idx - 2) == ':'
                        else -> ch == '<' && hasMatchingClosingAngle(idx)
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
        fun identTree(name: String, span: Span = Span.callSite()): TokenTree =
            TokenTree.Ident(Ident.new(name, span))

        fun punctTree(
            ch: Char,
            spacing: Spacing = Spacing.Alone,
            span: Span = Span.callSite(),
        ): TokenTree = TokenTree.Punct(Punct(ch, spacing).also { it.setSpan(span) })

        fun stringLiteralTree(value: String): TokenTree =
            TokenTree.Literal(Literal.string(value))

        fun groupStreamTree(
            delimiter: Delimiter,
            stream: TokenStream,
            span: Span? = null,
        ): TokenTree =
            TokenTree.Group(
                Group(delimiter, stream).also { grp ->
                    if (span != null) grp.setSpan(span)
                },
            )

        fun groupTree(
            delimiter: Delimiter,
            trees: List<TokenTree> = emptyList(),
            span: Span? = null,
        ): TokenTree = groupStreamTree(delimiter, TokenStream.fromTokenTrees(trees), span)

        fun parseIntegerLiteral(litTree: TokenTree): Long? {
            val rawLit = (litTree as? TokenTree.Literal)?.value?.toString() ?: return null
            val withoutUnderscore = rawLit.replace("_", "")
            if (withoutUnderscore.isEmpty()) return null
            return when {
                withoutUnderscore.startsWith("0x") || withoutUnderscore.startsWith("0X") ->
                    stripHexOrRadixSuffix(withoutUnderscore.drop(2), allowHexDigits = true).toLongOrNull(16)
                withoutUnderscore.startsWith("0b") || withoutUnderscore.startsWith("0B") ->
                    stripHexOrRadixSuffix(withoutUnderscore.drop(2), allowHexDigits = false).toLongOrNull(2)
                withoutUnderscore.startsWith("0o") || withoutUnderscore.startsWith("0O") ->
                    stripHexOrRadixSuffix(withoutUnderscore.drop(2), allowHexDigits = false).toLongOrNull(8)
                else -> withoutUnderscore.trimEnd { it.isLetter() }.toLongOrNull()
            }
        }

        private fun stripHexOrRadixSuffix(body: String, allowHexDigits: Boolean): String {
            for (suffix in INT_LITERAL_SUFFIXES) {
                if (body.endsWith(suffix) && body.length > suffix.length) {
                    return body.dropLast(suffix.length)
                }
            }
            return if (allowHexDigits) body else body.trimEnd { it.isLetter() }
        }

        private val INT_LITERAL_SUFFIXES =
            listOf("u128", "i128", "usize", "isize", "u64", "i64", "u32", "i32", "u16", "i16", "u8", "i8")

        fun canonicalTokenKey(trees: List<TokenTree>): String =
            trees.joinToString(" ") { tt ->
                when (tt) {
                    is TokenTree.Ident -> "I:${tt.value}"
                    is TokenTree.Punct -> "P:${tt.value.asChar()}:${tt.value.spacing()}"
                    is TokenTree.Literal -> "L:${tt.value}"
                    is TokenTree.Group -> "G:${tt.value.delimiter()}(${canonicalTokenKey(tt.value.stream().toList())})"
                }
            }

        fun extractCfgComplementSignature(predTrees: List<TokenTree>): Pair<String, Boolean>? {
            if (predTrees.isEmpty()) return null
            val cursor = RustTokenCursor(predTrees)
            val innerGroup = cursor.groupAt(1, Delimiter.Parenthesis)
            if (cursor.size == 2 && cursor.identAt(0) == "not" && innerGroup != null) {
                val inner = canonicalTokenKey(innerGroup.stream().toList())
                if (inner.isNotEmpty()) return inner to true
            }
            val raw = canonicalTokenKey(predTrees)
            return if (raw.isNotEmpty()) raw to false else null
        }

        fun tokenTreeStartOffset(tt: TokenTree): Int =
            when (tt) {
                is TokenTree.Group -> tt.value.spanOpen().startOffset()
                is TokenTree.Ident -> tt.value.span().startOffset()
                is TokenTree.Punct -> tt.value.span().startOffset()
                is TokenTree.Literal -> tt.value.span().startOffset()
            }

        fun tokenTreeEndOffset(tt: TokenTree): Int =
            when (tt) {
                is TokenTree.Group -> tt.value.spanClose().endExclusiveOffset()
                is TokenTree.Ident -> tt.value.span().endExclusiveOffset()
                is TokenTree.Punct -> tt.value.span().endExclusiveOffset()
                is TokenTree.Literal -> tt.value.span().endExclusiveOffset()
            }

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

        fun anyIdentRecursive(
            stream: TokenStream,
            predicate: (String) -> Boolean,
        ): Boolean {
            for (tt in stream.clone().toList()) {
                when (tt) {
                    is TokenTree.Ident -> if (predicate(tt.value.toString().removePrefix("r#"))) return true
                    is TokenTree.Group -> if (anyIdentRecursive(tt.value.stream(), predicate)) return true
                    else -> {}
                }
            }
            return false
        }

        fun containsAnyIdentRecursive(
            stream: TokenStream,
            idents: Set<String>,
        ): Boolean = idents.isNotEmpty() && anyIdentRecursive(stream) { it in idents }
    }
}

/**
 * Shared `proc_macro2` [TokenTree] -> typed [KotlinEmitToken] stream emitter.
 *
 * Consolidates Rust attribute elision/rendering, `'a` lifetime elision, `&` / `&mut` borrow
 * stripping, `Token![...]` resolution, `macro!(...)` invocations, `::` -> `.` path emission,
 * `=>` -> `->` and joint multi-character operators, delimited groups, and structural Kotlin
 * token-boundary spacing via [KotlinTokenEmitter] for comment/KDoc code blocks, `macro_rules!`
 * body templates, and `Verbatim` AST nodes.
 */
internal open class RustTokenStreamKotlinEmitter(
    trees: List<TokenTree>,
) : RustTokenCursor(trees) {
    protected open val stripModuleRootPrefixes: Boolean = true
    protected open val omitTurbofishDot: Boolean = true
    protected open val bracketMacroAsListOf: Boolean = true
    protected open val emptyParensAfterAngleAsUnit: Boolean = true
    protected open val stripPostfixQuestion: Boolean = true

    protected open fun spawnChild(childTrees: List<TokenTree>): RustTokenStreamKotlinEmitter =
        RustTokenStreamKotlinEmitter(childTrees)

    protected open fun tryConsumeSpecialAt(idx: Int, out: KotlinTokenEmitter): Int? = null

    protected open fun renderAttributeInteriorStream(stream: TokenStream, isInner: Boolean): String? = null

    protected open fun consumeLifetimeAt(idx: Int, out: KotlinTokenEmitter): Int {
        var next = idx + 2
        val lastTok = out.lastToken()
        val isLeadingAngleComma =
            punctCharAt(next) == ',' && lastTok is KotlinEmitToken.Punct && lastTok.ch == '<'
        if (punctCharAt(next) == '+' || isLeadingAngleComma) {
            next++
        }
        return next
    }

    protected open fun mapMacroName(rawMacro: String): String =
        if (rawMacro == "macro_rules") "macroRules" else rustFunctionIdentToKotlin(rawMacro)

    protected open fun mapIdent(rawIdent: String): String =
        if (rawIdent.firstOrNull()?.isUpperCase() == true) {
            rustTypeIdentToKotlin(rawIdent)
        } else {
            rustValueIdentToKotlin(rawIdent)
        }

    protected open fun mapSynTokenMacro(grp: Group): String {
        resolveSynTokenStreamTypeName(grp.stream())?.let { return it }
        val fallback =
            grp
                .stream()
                .toList()
                .mapNotNull { (it as? TokenTree.Ident)?.value?.toString() }
                .joinToString("_")
                .ifEmpty { "Token" }
        return rustTypeIdentToKotlin(fallback)
    }

    open fun emitInto(out: KotlinTokenEmitter) {
        if (trees.isEmpty()) return
        var i = 0
        while (i < size) {
            val specialNext = tryConsumeSpecialAt(i, out)
            if (specialNext != null) {
                i = specialNext
                continue
            }
            val current = trees[i]
            if (punctCharAt(i) == '#') {
                var nextIdx = i + 1
                var isInner = false
                if (punctCharAt(nextIdx) == '!') {
                    isInner = true
                    nextIdx++
                }
                val grp = groupAt(nextIdx, Delimiter.Bracket)
                if (grp != null) {
                    val renderedAttr = renderAttributeInteriorStream(grp.stream(), isInner)
                    if (!renderedAttr.isNullOrEmpty()) out.raw(renderedAttr)
                    i = nextIdx + 1
                    continue
                }
            }

            if (isLifetimeAt(i)) {
                i = consumeLifetimeAt(i, out)
                continue
            }

            if (punctCharAt(i) == '&') {
                val prev = out.lastToken()
                if (punctSpacingAt(i) == Spacing.Joint && punctCharAt(i + 1) == '&') {
                    out.operator("&&")
                    i += 2
                    continue
                }
                if (prev == null || isBorrowDropPredecessor(prev)) {
                    i++
                    if (isLifetimeAt(i)) i += 2
                    if (identAt(i) == "mut") i++
                    continue
                }
            }

            val bangMacroGrp = if (current is TokenTree.Ident && punctCharAt(i + 1) == '!') groupAt(i + 2) else null
            if (bangMacroGrp != null) {
                val rawMacro = cleanIdentAt(i).orEmpty()
                if (identAt(i) == "Token" && bangMacroGrp.delimiter() == Delimiter.Bracket) {
                    out.ident(mapSynTokenMacro(bangMacroGrp))
                    i += 3
                    continue
                }
                val ktMacro = mapMacroName(rawMacro)
                val innerTrees = bangMacroGrp.stream().toList()
                when (bangMacroGrp.delimiter()) {
                    Delimiter.Brace ->
                        out.macroCall(ktMacro, "{", "}", spaceBeforeOpen = true, padInside = true) { childOut ->
                            spawnChild(innerTrees).emitInto(childOut)
                        }
                    Delimiter.Bracket ->
                        if (bracketMacroAsListOf) {
                            out.macroCall("listOf", "(", ")") { childOut ->
                                spawnChild(innerTrees).emitInto(childOut)
                            }
                        } else {
                            out.macroCall(ktMacro, "[", "]") { childOut ->
                                spawnChild(innerTrees).emitInto(childOut)
                            }
                        }
                    else ->
                        out.macroCall(ktMacro, "(", ")") { childOut ->
                            spawnChild(innerTrees).emitInto(childOut)
                        }
                }
                i += 3
                continue
            }

            if (current is TokenTree.Ident && punctCharAt(i + 1) == '!' && punctCharAt(i + 2) != '=') {
                out.ident(mapMacroName(cleanIdentAt(i).orEmpty()))
                i += 2
                continue
            }

            if (isDoubleColonAt(i)) {
                if (stripModuleRootPrefixes && out.lastWordText() in MODULE_ROOT_PREFIXES) {
                    out.dropLastToken()
                } else if (!omitTurbofishDot || punctCharAt(i + 2) != '<') {
                    out.punct('.')
                }
                i += 2
                continue
            }

            when (current) {
                is TokenTree.Ident -> {
                    val mapped = mapIdent(cleanIdentAt(i).orEmpty())
                    if (mapped.isNotEmpty()) out.wordOrKeywords(mapped)
                    i++
                }
                is TokenTree.Literal -> {
                    out.literal(current.value.toString())
                    i++
                }
                is TokenTree.Group -> {
                    val grp = current.value
                    val innerTrees = grp.stream().toList()
                    val lastTok = out.lastToken()
                    when (grp.delimiter()) {
                        Delimiter.Parenthesis ->
                            if (emptyParensAfterAngleAsUnit &&
                                innerTrees.isEmpty() &&
                                lastTok is KotlinEmitToken.Punct &&
                                lastTok.ch == '<'
                            ) {
                                out.ident("Unit")
                            } else {
                                out.delimited("(", ")") { childOut ->
                                    spawnChild(innerTrees).emitInto(childOut)
                                }
                            }
                        Delimiter.Bracket ->
                            out.delimited("[", "]") { childOut ->
                                spawnChild(innerTrees).emitInto(childOut)
                            }
                        Delimiter.Brace ->
                            out.delimited("{", "}", padInside = true) { childOut ->
                                spawnChild(innerTrees).emitInto(childOut)
                            }
                        Delimiter.None -> spawnChild(innerTrees).emitInto(out)
                    }
                    i++
                }
                is TokenTree.Punct -> {
                    i += consumePunctOrOperatorInto(i, out)
                }
            }
        }
    }

    open fun render(): String {
        if (trees.isEmpty()) return ""
        val out = KotlinTokenEmitter()
        emitInto(out)
        return out.render()
    }

    private fun consumePunctOrOperatorInto(idx: Int, out: KotlinTokenEmitter): Int {
        val jointOp = consumeJointOperator(idx)
        if (jointOp != null) {
            val (op, step) = jointOp
            val mapped = if (op == "=>") "->" else op
            out.operator(mapped)
            return step
        }
        val c0 = punctCharAt(idx) ?: return 1
        val prevTok = out.lastToken()
        if (stripPostfixQuestion && c0 == '?' && prevTok != null && prevTok.canPrecedePostfixTry()) {
            return 1
        }
        out.punct(c0)
        return 1
    }

    companion object {
        private val MODULE_ROOT_PREFIXES = setOf("crate", "self", "super")

        private fun isBorrowDropPredecessor(tok: KotlinEmitToken): Boolean =
            when (tok) {
                is KotlinEmitToken.Punct -> tok.ch == '(' || tok.ch == ',' || tok.ch == ':' || tok.ch == '<'
                is KotlinEmitToken.Operator -> tok.symbol == "=" || tok.symbol == "->"
                else -> false
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

/**
 * Reusable item-level token stream scanner base class that accumulates outer/inner attributes
 * into [pendingAttrs] and resets them automatically across `;` and `{...}` item boundaries.
 */
internal abstract class RustItemStreamScanner(
    trees: List<TokenTree>,
) : RustTokenCursor(trees) {
    protected val pendingAttrs: MutableList<List<TokenTree>> = mutableListOf()

    protected fun hasPendingAttrIdent(ident: String): Boolean =
        pendingAttrs.any { RustTokenCursor(it).cleanIdentAt(0) == ident }

    protected inline fun walkItemStream(
        onItemToken: (idx: Int, attrs: List<List<TokenTree>>) -> Int?,
    ) {
        pendingAttrs.clear()
        var idx = 0
        while (idx < size) {
            consumeAttribute(idx, pendingAttrs)?.let {
                idx = it
                continue
            }
            val nextIdx = onItemToken(idx, pendingAttrs)
            if (nextIdx != null) {
                pendingAttrs.clear()
                idx = nextIdx
                continue
            }
            if (punctCharAt(idx) == ';' || groupAt(idx, Delimiter.Brace) != null) {
                pendingAttrs.clear()
            }
            idx++
        }
    }
}
