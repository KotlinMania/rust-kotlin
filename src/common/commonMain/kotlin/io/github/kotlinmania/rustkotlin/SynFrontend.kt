package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.procmacro2.Delimiter
import io.github.kotlinmania.procmacro2.Group
import io.github.kotlinmania.procmacro2.Spacing
import io.github.kotlinmania.procmacro2.TokenStream
import io.github.kotlinmania.procmacro2.TokenTree
import io.github.kotlinmania.syn.File
import io.github.kotlinmania.syn.FileParse
import io.github.kotlinmania.syn.Item
import io.github.kotlinmania.syn.ItemParse
import io.github.kotlinmania.syn.SynType
import io.github.kotlinmania.syn.TypeParamBound
import io.github.kotlinmania.syn.gen.Fold
import io.github.kotlinmania.syn.parse2

/** Existing KotlinMania lexer and complete-stream Rust grammar parser; no recognition fallback. */
internal data class RecognizedRust(
    val tokens: TokenStream,
    val file: File,
    val source: String,
)

internal fun recognizeRust(source: String): RecognizedRust {
    val normalizedSource = normalizeSupplementaryCharLiterals(source)
    val tokens = TranslationSession.measure("lex:${normalizedSource.length}") { TokenStream.fromString(normalizedSource).getOrThrow() }
    val parseStream = normalizeLetOrPatternsForSyn(tokens.clone())
    // Syn consumes its token stream. Preserve the original library token trees for provenance.
    val rawFile =
        TranslationSession.measure("parse:${normalizedSource.length}") { parse2(FileParse::parse, parseStream) }.getOrElse { diagnostic ->
            val position =
                if (diagnostic.span().sourceUtf16Range() == null) {
                    val lastToken = tokens.clone().lastOrNull()
                    lastToken?.span()?.end() ?: diagnostic.span().start()
                } else {
                    diagnostic.span().start()
                }
            throw IllegalArgumentException("Syn parser at ${position.line}:${position.column + 1}: $diagnostic")
        }
    val file = RustNextAstNormalizer.foldFile(rawFile)
    return RecognizedRust(tokens, file, normalizedSource)
}

internal fun lexTokenStreamOrNull(source: String): TokenStream? {
    val lexed = TokenStream.fromString(source)
    return if (lexed.isSuccess()) lexed.getOrThrow() else null
}

internal fun recognizePreLexedRustOrNull(
    normalizedSource: String,
    tokens: TokenStream,
): RecognizedRust? {
    val parseStream = normalizeLetOrPatternsForSyn(tokens.clone())
    val rawFile = parse2(FileParse::parse, parseStream).getOrElse { return null }
    val file = RustNextAstNormalizer.foldFile(rawFile)
    return RecognizedRust(tokens, file, normalizedSource)
}

internal fun recognizeRustOrNull(source: String): RecognizedRust? {
    val normalizedSource = normalizeSupplementaryCharLiterals(source)
    val tokens = lexTokenStreamOrNull(normalizedSource) ?: return null
    val parseStream = normalizeLetOrPatternsForSyn(tokens.clone())
    val parsed = parse2(FileParse::parse, parseStream)
    val rawFile = parsed.getOrElse { return null }
    val file = RustNextAstNormalizer.foldFile(rawFile)
    return RecognizedRust(tokens, file, normalizedSource)
}

/**
 * `proc-macro2` character literal lexing steps one UTF-16 `Char` for unescaped character bodies,
 * rejecting UTF-16 surrogate pairs (`'🦀'`) while accepting `'\\u{1f980}'` and decoding both to the
 * exact same 21-bit scalar in `syn`'s `LitChar`. Rewrite unescaped supplementary character literals
 * outside comments and string literals in a single lexical pass so token spans and trivia align.
 */
internal fun normalizeSupplementaryCharLiterals(source: String): String {
    if (!containsCandidateSupplementaryCharLiteral(source)) {
        return source
    }
    val out = StringBuilder(source.length + 32)
    var i = 0
    val len = source.length
    while (i < len) {
        val ch = source[i]
        if (ch == '/' && i + 1 < len && source[i + 1] == '/') {
            val end = source.indexOf('\n', i + 2)
            if (end < 0) {
                out.append(source, i, len)
                return out.toString()
            }
            out.append(source, i, end + 1)
            i = end + 1
            continue
        }
        if (ch == '/' && i + 1 < len && source[i + 1] == '*') {
            val end = scanNestedBlockCommentEnd(source, i)
            out.append(source, i, end)
            i = end
            continue
        }
        val rawStrEnd = tryScanRawStringEnd(source, i)
        if (rawStrEnd > i) {
            out.append(source, i, rawStrEnd)
            i = rawStrEnd
            continue
        }
        val cookedStrPrefixLen = cookedStringPrefixLength(source, i)
        if (cookedStrPrefixLen >= 0) {
            val end = scanCookedStringEnd(source, i + cookedStrPrefixLen)
            out.append(source, i, end)
            i = end
            continue
        }
        if (ch == '\'') {
            val isByteCharPrefix =
                i > 0 &&
                    source[i - 1] == 'b' &&
                    (i == 1 || !isRustLexIdentContinue(source[i - 2]))
            if (!isByteCharPrefix &&
                i + 3 < len &&
                source[i + 1].isHighSurrogate() &&
                source[i + 2].isLowSurrogate() &&
                source[i + 3] == '\''
            ) {
                val codePoint =
                    ((source[i + 1].code - 0xD800) shl 10) +
                        (source[i + 2].code - 0xDC00) +
                        0x10000
                out
                    .append("'\\u{")
                    .append(codePoint.toString(16))
                    .append("}'")
                i += 4
                continue
            }
            if (i + 3 < len && source[i + 1] == '\\') {
                val end = scanEscapedCharLiteralEnd(source, i)
                out.append(source, i, end)
                i = end
                continue
            }
            if (i + 2 < len && source[i + 1] != '\'' && source[i + 1] != '\\' && source[i + 2] == '\'') {
                out.append(source, i, i + 3)
                i += 3
                continue
            }
        }
        out.append(ch)
        i++
    }
    return out.toString()
}

private fun containsCandidateSupplementaryCharLiteral(source: String): Boolean {
    var i = 0
    val limit = source.length - 3
    while (i < limit) {
        if (source[i] == '\'' &&
            source[i + 1].isHighSurrogate() &&
            source[i + 2].isLowSurrogate() &&
            source[i + 3] == '\''
        ) {
            return true
        }
        i++
    }
    return false
}

private fun isRustLexIdentContinue(ch: Char): Boolean = ch.isLetterOrDigit() || ch == '_'

private fun scanNestedBlockCommentEnd(source: String, start: Int): Int {
    var depth = 1
    var k = start + 2
    val len = source.length
    while (k < len && depth > 0) {
        if (source[k] == '/' && k + 1 < len && source[k + 1] == '*') {
            depth++
            k += 2
        } else if (source[k] == '*' && k + 1 < len && source[k + 1] == '/') {
            depth--
            k += 2
        } else {
            k++
        }
    }
    return k
}

private fun tryScanRawStringEnd(source: String, index: Int): Int {
    if (index > 0 && isRustLexIdentContinue(source[index - 1])) {
        return index
    }
    val len = source.length
    val rPos =
        when {
            source[index] == 'r' -> index
            (source[index] == 'b' || source[index] == 'c') && index + 1 < len && source[index + 1] == 'r' -> index + 1
            else -> return index
        }
    var j = rPos + 1
    var hashes = 0
    while (j < len && source[j] == '#') {
        hashes++
        j++
    }
    if (j >= len || source[j] != '"') {
        return index
    }
    j++
    while (j < len) {
        if (source[j] == '"') {
            var matched = 0
            while (matched < hashes && j + 1 + matched < len && source[j + 1 + matched] == '#') {
                matched++
            }
            if (matched == hashes) {
                return j + 1 + hashes
            }
        }
        j++
    }
    return len
}

private fun cookedStringPrefixLength(source: String, index: Int): Int {
    val ch = source[index]
    if (ch == '"') {
        return 0
    }
    if ((ch == 'b' || ch == 'c') &&
        (index == 0 || !isRustLexIdentContinue(source[index - 1])) &&
        index + 1 < source.length &&
        source[index + 1] == '"'
    ) {
        return 1
    }
    return -1
}

private fun scanCookedStringEnd(source: String, openQuoteIdx: Int): Int {
    var j = openQuoteIdx + 1
    val len = source.length
    while (j < len) {
        val c = source[j]
        if (c == '\\') {
            j += if (j + 1 < len) 2 else 1
        } else if (c == '"') {
            return j + 1
        } else {
            j++
        }
    }
    return len
}

private fun scanEscapedCharLiteralEnd(source: String, openTickIdx: Int): Int {
    var j = openTickIdx + 1
    val len = source.length
    while (j < len) {
        val c = source[j]
        if (c == '\\') {
            j += if (j + 1 < len) 2 else 1
        } else if (c == '\'') {
            return j + 1
        } else if (c == '\n') {
            return j
        } else {
            j++
        }
    }
    return len
}

/**
 * Rust 2021 permits unparenthesized top-level OR-patterns in `if let` / `while let` / `let`
 * bindings (`if let A | B = expr`), whereas `syn`'s `Pat::parse_single` expects parenthesized
 * OR-patterns (`if let (A | B) = expr`). Normalizing at the token-tree level preserves spans.
 */
internal fun normalizeLetOrPatternsForSyn(stream: TokenStream): TokenStream {
    val trees = stream.toList()
    if (trees.isEmpty()) return stream
    val out = ArrayList<TokenTree>(trees.size)
    var i = 0
    while (i < trees.size) {
        val tt = trees[i]
        if (tt is TokenTree.Ident && tt.value.toString() == "final" && isFinalFnModifierAt(trees, i)) {
            i++
            continue
        }
        if (tt is TokenTree.Ident &&
            tt.value.toString() == "super" &&
            (trees.getOrNull(i + 1) as? TokenTree.Ident)?.value?.toString() == "let"
        ) {
            i++
            continue
        }
        if (tt is TokenTree.Group && tt.value.delimiter() == Delimiter.Bracket) {
            val prevIsHash = (trees.getOrNull(i - 1) as? TokenTree.Punct)?.value?.asChar() == '#'
            if (!prevIsHash) {
                val innerList = tt.value.stream().toList()
                if (innerList.size == 1 && (innerList[0] as? TokenTree.Ident)?.value?.toString() == "const") {
                    i++
                    continue
                }
            }
        }
        if (tt is TokenTree.Ident && tt.value.toString() == "let") {
            out.add(tt)
            val patInfo = findLetPatternEndAndTopLevelOr(trees, i + 1)
            if (patInfo != null) {
                val (patEnd, hasOr) = patInfo
                if (hasOr && patEnd > i + 1) {
                    val normalizedPat = normalizeLetOrPatternsForSyn(TokenStream.fromTokenTrees(trees.subList(i + 1, patEnd)))
                    val parenGroup = Group(Delimiter.Parenthesis, normalizedPat)
                    parenGroup.setSpan(trees[i + 1].span())
                    out.add(TokenTree.Group(parenGroup))
                    i = patEnd
                    continue
                }
            }
            i++
            continue
        }
        if (tt is TokenTree.Group) {
            val normalizedInner = normalizeLetOrPatternsForSyn(tt.value.stream())
            val rebuiltGroup = Group(tt.value.delimiter(), normalizedInner)
            rebuiltGroup.setSpan(tt.value.span())
            out.add(TokenTree.Group(rebuiltGroup))
        } else {
            out.add(tt)
        }
        i++
    }
    return TokenStream.fromTokenTrees(out)
}

private val FN_MODIFIER_KEYWORDS = setOf("default", "const", "async", "unsafe", "extern")

private fun isFinalFnModifierAt(trees: List<TokenTree>, finalIdx: Int): Boolean {
    var k = finalIdx + 1
    while (k < trees.size) {
        val cur = trees[k]
        if (cur is TokenTree.Ident) {
            val kw = cur.value.toString()
            if (kw == "fn") return true
            if (kw in FN_MODIFIER_KEYWORDS) {
                k++
                if (kw == "extern" && trees.getOrNull(k) is TokenTree.Literal) {
                    k++
                }
                continue
            }
        }
        return false
    }
    return false
}

private fun findLetPatternEndAndTopLevelOr(trees: List<TokenTree>, startIdx: Int): Pair<Int, Boolean>? {
    if (startIdx >= trees.size) return null
    var angleDepth = 0
    var hasTopLevelOr = false
    for (j in startIdx until trees.size) {
        val tt = trees[j]
        if (tt is TokenTree.Punct) {
            val ch = tt.value.asChar()
            val isJoint = tt.value.spacing() == Spacing.Joint
            val nextCh = (trees.getOrNull(j + 1) as? TokenTree.Punct)?.value?.asChar()
            val prevPunct = if (j > startIdx) trees.getOrNull(j - 1) as? TokenTree.Punct else null
            val prevCh = prevPunct?.value?.asChar()
            val prevJoint = prevPunct?.value?.spacing() == Spacing.Joint

            if (ch == '<' && !(isJoint && (nextCh == '=' || nextCh == '<' || nextCh == '-'))) {
                angleDepth++
            } else if (ch == '>' && !(prevJoint && (prevCh == '-' || prevCh == '=' || prevCh == '>'))) {
                if (angleDepth > 0) angleDepth--
            } else if (angleDepth == 0) {
                when (ch) {
                    ';' -> return j to hasTopLevelOr
                    ':' -> {
                        if (!isJoint && !(prevJoint && prevCh == ':')) {
                            return j to hasTopLevelOr
                        }
                    }
                    '=' -> {
                        val isCompoundAfter = isJoint && (nextCh == '=' || nextCh == '>')
                        val isCompoundBefore = prevJoint && prevCh != null && prevCh in COMPOUND_EQ_PREFIX_CHARS
                        if (!isCompoundAfter && !isCompoundBefore) {
                            return j to hasTopLevelOr
                        }
                    }
                    '|' -> {
                        val isLogicalOrAssign = (isJoint && (nextCh == '|' || nextCh == '=')) || (prevJoint && prevCh == '|')
                        if (!isLogicalOrAssign) {
                            hasTopLevelOr = true
                        }
                    }
                }
            }
        }
    }
    return null
}

private val COMPOUND_EQ_PREFIX_CHARS = charArrayOf('=', '!', '<', '>', '+', '-', '*', '/', '%', '^', '&', '|')

/**
 * Normalizes RFC 3762 `const trait` / `const impl` items and `[const]` / `const` trait bounds
 * that `syn` preserves as `Item.Verbatim` and `TypeParamBound.Verbatim` into structured AST nodes
 * while preserving outer attributes and source spans.
 */
internal object RustNextAstNormalizer : Fold() {
    override fun foldType(t: SynType): SynType {
        if (t is SynType.Macro && t.mac.path.segments
                .last()
                ?.ident
                ?.toString() == "pattern_type"
        ) {
            val tokens =
                t.mac.tokens
                    .clone()
                    .toList()
            val separator = tokens.indexOfFirst { it is TokenTree.Ident && it.value.toString() == "is" }
            require(separator > 0 && separator < tokens.lastIndex) { "pattern_type requires a type and an is-pattern" }
            val underlying = parse2(SynType.Companion::parse, TokenStream.fromTokenTrees(tokens.take(separator))).getOrThrow()
            return foldType(underlying)
        }
        return super.foldType(t)
    }

    override fun foldItem(i: Item): Item {
        if (i is Item.Verbatim) {
            val stripped = stripConstFromVerbatimItemTokens(i.tokens.clone().toList())
            if (stripped != null) {
                val reparsed = parse2(ItemParse::parse, TokenStream.fromTokenTrees(stripped)).getOrNull()
                if (reparsed != null && reparsed !is Item.Verbatim) {
                    return super.foldItem(reparsed)
                }
            }
        }
        return super.foldItem(i)
    }

    override fun foldTypeParamBound(bound: TypeParamBound): TypeParamBound {
        if (bound is TypeParamBound.Verbatim) {
            val stripped = stripConstFromVerbatimBoundTokens(bound.tokens.clone().toList())
            if (stripped != null) {
                val reparsed = parse2(TypeParamBound.Companion::parse, TokenStream.fromTokenTrees(stripped)).getOrNull()
                if (reparsed != null && reparsed !is TypeParamBound.Verbatim) {
                    return super.foldTypeParamBound(reparsed)
                }
            }
        }
        return super.foldTypeParamBound(bound)
    }

    private fun stripConstFromVerbatimItemTokens(trees: List<TokenTree>): List<TokenTree>? {
        if (trees.isEmpty()) return null
        val out = ArrayList<TokenTree>(trees.size)
        var idx = 0
        while (idx + 1 < trees.size &&
            (trees[idx] as? TokenTree.Punct)?.value?.asChar() == '#' &&
            (trees[idx + 1] as? TokenTree.Group)?.value?.delimiter() == Delimiter.Bracket
        ) {
            out.add(trees[idx])
            out.add(trees[idx + 1])
            idx += 2
        }
        if ((trees.getOrNull(idx) as? TokenTree.Ident)?.value?.toString() == "pub") {
            out.add(trees[idx])
            idx++
            if ((trees.getOrNull(idx) as? TokenTree.Group)?.value?.delimiter() == Delimiter.Parenthesis) {
                out.add(trees[idx])
                idx++
            }
        }
        var stripped = false
        if ((trees.getOrNull(idx) as? TokenTree.Ident)?.value?.toString() == "impl" &&
            (trees.getOrNull(idx + 1) as? TokenTree.Group)?.value?.delimiter() == Delimiter.Parenthesis
        ) {
            val afterRestriction = (trees.getOrNull(idx + 2) as? TokenTree.Ident)?.value?.toString()
            if (afterRestriction == "const" || afterRestriction == "unsafe" || afterRestriction == "auto" || afterRestriction == "trait") {
                idx += 2
                stripped = true
            }
        }
        val keptModifiers = ArrayList<TokenTree>(4)
        var scan = idx
        var sawConstHeader = false
        while (scan < trees.size) {
            val ident = (trees[scan] as? TokenTree.Ident)?.value?.toString() ?: break
            when (ident) {
                "const" -> {
                    sawConstHeader = true
                    scan++
                }
                "default", "unsafe", "auto" -> {
                    keptModifiers.add(trees[scan])
                    scan++
                }
                else -> break
            }
        }
        val keyword = (trees.getOrNull(scan) as? TokenTree.Ident)?.value?.toString()
        if (keyword != "trait" && keyword != "impl") {
            return null
        }
        if (sawConstHeader) {
            stripped = true
        }
        out.addAll(keptModifiers)
        out.add(trees[scan])
        idx = scan + 1
        if (keyword == "impl") {
            if ((trees.getOrNull(idx) as? TokenTree.Punct)?.value?.asChar() == '<') {
                var angleDepth = 0
                while (idx < trees.size) {
                    val tt = trees[idx]
                    out.add(tt)
                    idx++
                    if (tt is TokenTree.Punct) {
                        val ch = tt.value.asChar()
                        if (ch == '<') {
                            angleDepth++
                        } else if (ch == '>' && angleDepth > 0) {
                            angleDepth--
                            if (angleDepth == 0) break
                        }
                    }
                }
            }
            if ((trees.getOrNull(idx) as? TokenTree.Punct)?.value?.asChar() == '!') {
                out.add(trees[idx])
                idx++
            }
            val constModLen = leadingConstBoundModifierLength(trees, idx)
            if (constModLen > 0) {
                idx += constModLen
                stripped = true
            }
        }
        if (!stripped) return null
        while (idx < trees.size) {
            out.add(trees[idx])
            idx++
        }
        return out
    }

    private fun stripConstFromVerbatimBoundTokens(trees: List<TokenTree>): List<TokenTree>? {
        if (trees.isEmpty()) return null
        if (trees.size == 1) {
            val parenGrp = trees[0] as? TokenTree.Group
            if (parenGrp != null && parenGrp.value.delimiter() == Delimiter.Parenthesis) {
                val innerStripped = stripConstFromVerbatimBoundTokens(parenGrp.value.stream().toList()) ?: return null
                val rebuiltGrp = Group(Delimiter.Parenthesis, TokenStream.fromTokenTrees(innerStripped))
                rebuiltGrp.setSpan(parenGrp.value.span())
                return listOf(TokenTree.Group(rebuiltGrp))
            }
        }
        val out = ArrayList<TokenTree>(trees.size)
        var idx = 0
        if ((trees.getOrNull(idx) as? TokenTree.Ident)?.value?.toString() == "for" &&
            (trees.getOrNull(idx + 1) as? TokenTree.Punct)?.value?.asChar() == '<'
        ) {
            out.add(trees[idx])
            idx++
            var angleDepth = 0
            while (idx < trees.size) {
                val tt = trees[idx]
                out.add(tt)
                idx++
                if (tt is TokenTree.Punct) {
                    val ch = tt.value.asChar()
                    if (ch == '<') {
                        angleDepth++
                    } else if (ch == '>' && angleDepth > 0) {
                        angleDepth--
                        if (angleDepth == 0) break
                    }
                }
            }
        }
        val constModLen = leadingConstBoundModifierLength(trees, idx)
        if (constModLen == 0) return null
        idx += constModLen
        while (idx < trees.size) {
            out.add(trees[idx])
            idx++
        }
        return out
    }

    private fun leadingConstBoundModifierLength(trees: List<TokenTree>, startIdx: Int): Int {
        val cur = trees.getOrNull(startIdx) ?: return 0
        if (cur is TokenTree.Group && cur.value.delimiter() == Delimiter.Bracket) {
            val inner = cur.value.stream().toList()
            if (inner.size == 1 && (inner[0] as? TokenTree.Ident)?.value?.toString() == "const") {
                return 1
            }
        }
        if ((cur as? TokenTree.Ident)?.value?.toString() == "const") {
            return 1
        }
        val ch = (cur as? TokenTree.Punct)?.value?.asChar()
        if ((ch == '~' || ch == '?') &&
            (trees.getOrNull(startIdx + 1) as? TokenTree.Ident)?.value?.toString() == "const"
        ) {
            return 2
        }
        return 0
    }
}
