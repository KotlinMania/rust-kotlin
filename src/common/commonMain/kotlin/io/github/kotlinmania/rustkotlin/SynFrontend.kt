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
    return recognizePreLexedRustOrNull(normalizedSource, tokens)
}

/**
 * `proc-macro2` character literal lexing steps one UTF-16 `Char` for unescaped character bodies,
 * rejecting UTF-16 surrogate pairs (`'🦀'`) while accepting `'\\u{1f980}'` and decoding both to the
 * exact same 21-bit scalar in `syn`'s `LitChar`. Rewrite unescaped supplementary character literals
 * outside comments and string literals in a single lexical pass so token spans and trivia align.
 */
internal fun normalizeSupplementaryCharLiterals(source: String): String =
    SupplementaryCharLiteralRewriter(source).rewrite()

/**
 * Reusable lexical scanner base class over raw Rust source text for comment, raw-string,
 * cooked-string, and character-literal boundary navigation prior to `proc_macro2` tokenization.
 */
internal open class RustLexicalSourceScanner(
    protected val source: String,
) {
    protected val len: Int = source.length

    protected fun isIdentContinue(ch: Char): Boolean = ch.isLetterOrDigit() || ch == '_'

    protected fun tryScanCommentOrStringEnd(index: Int): Int? {
        val ch = source[index]
        if (ch == '/' && index + 1 < len) {
            if (source[index + 1] == '/') {
                val nl = source.indexOf('\n', index + 2)
                return if (nl < 0) len else nl + 1
            }
            if (source[index + 1] == '*') {
                return scanNestedBlockCommentEnd(index)
            }
        }
        val rawStrEnd = tryScanRawStringEnd(index)
        if (rawStrEnd > index) return rawStrEnd
        val cookedPrefixLen = cookedStringPrefixLength(index)
        if (cookedPrefixLen >= 0) return scanCookedStringEnd(index + cookedPrefixLen)
        return null
    }

    protected fun scanNestedBlockCommentEnd(start: Int): Int {
        var depth = 1
        var k = start + 2
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

    protected fun tryScanRawStringEnd(index: Int): Int {
        if (index > 0 && isIdentContinue(source[index - 1])) return index
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
        if (j >= len || source[j] != '"') return index
        j++
        while (j < len) {
            if (source[j] == '"') {
                var matched = 0
                while (matched < hashes && j + 1 + matched < len && source[j + 1 + matched] == '#') {
                    matched++
                }
                if (matched == hashes) return j + 1 + hashes
            }
            j++
        }
        return len
    }

    protected fun cookedStringPrefixLength(index: Int): Int {
        val ch = source[index]
        if (ch == '"') return 0
        if ((ch == 'b' || ch == 'c') &&
            (index == 0 || !isIdentContinue(source[index - 1])) &&
            index + 1 < len &&
            source[index + 1] == '"'
        ) {
            return 1
        }
        return -1
    }

    protected fun scanCookedStringEnd(openQuoteIdx: Int): Int {
        var j = openQuoteIdx + 1
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

    protected fun scanEscapedCharLiteralEnd(openTickIdx: Int): Int {
        var j = openTickIdx + 1
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
}

private class SupplementaryCharLiteralRewriter(
    source: String,
) : RustLexicalSourceScanner(source) {
    fun rewrite(): String {
        if (!containsCandidate()) return source
        val out = StringBuilder(len + 32)
        var i = 0
        while (i < len) {
            val skippedEnd = tryScanCommentOrStringEnd(i)
            if (skippedEnd != null) {
                out.append(source, i, skippedEnd)
                i = skippedEnd
                continue
            }
            val ch = source[i]
            if (ch == '\'') {
                val isByteCharPrefix =
                    i > 0 &&
                        source[i - 1] == 'b' &&
                        (i == 1 || !isIdentContinue(source[i - 2]))
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
                    val end = scanEscapedCharLiteralEnd(i)
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

    private fun containsCandidate(): Boolean {
        val limit = len - 3
        for (i in 0 until limit) {
            if (source[i] == '\'' &&
                source[i + 1].isHighSurrogate() &&
                source[i + 2].isLowSurrogate() &&
                source[i + 3] == '\''
            ) {
                return true
            }
        }
        return false
    }
}

/**
 * Rust 2021 permits unparenthesized top-level OR-patterns in `if let` / `while let` / `let`
 * bindings (`if let A | B = expr`), whereas `syn`'s `Pat::parse_single` expects parenthesized
 * OR-patterns (`if let (A | B) = expr`). Normalizing at the token-tree level preserves spans.
 */
internal fun normalizeLetOrPatternsForSyn(stream: TokenStream): TokenStream {
    val trees = stream.toList()
    if (trees.isEmpty()) return stream
    return SynTokenStreamNormalizer(trees).normalize()
}

private val FN_MODIFIER_KEYWORDS = setOf("default", "const", "async", "unsafe", "extern")
private val COMPOUND_EQ_PREFIX_CHARS = charArrayOf('=', '!', '<', '>', '+', '-', '*', '/', '%', '^', '&', '|')

/**
 * Extensible [RustTokenCursor] subclass normalizing Rust 2021 unparenthesized `let A | B = ...`
 * OR-patterns, `final fn`, `super let`, and `[const]` inline bracket modifiers before `syn` parsing.
 */
internal open class SynTokenStreamNormalizer(
    trees: List<TokenTree>,
) : RustTokenCursor(trees) {
    open fun normalize(): TokenStream {
        val out = ArrayList<TokenTree>(size)
        var i = 0
        while (i < size) {
            val tt = trees[i]
            val ident = identAt(i)
            if (ident == "final" && isFinalFnModifierAt(i)) {
                i++
                continue
            }
            if (ident == "super" && identAt(i + 1) == "let") {
                i++
                continue
            }
            val bracketGrp = groupAt(i, Delimiter.Bracket)
            if (bracketGrp != null && punctCharAt(i - 1) != '#' && isSingleConstBracketGroup(bracketGrp)) {
                i++
                continue
            }
            if (ident == "let") {
                out.add(tt)
                val patInfo = findLetPatternEndAndTopLevelOr(i + 1)
                if (patInfo != null && patInfo.second && patInfo.first > i + 1) {
                    val normalizedPat = normalizeLetOrPatternsForSyn(TokenStream.fromTokenTrees(trees.subList(i + 1, patInfo.first)))
                    val parenGroup = Group(Delimiter.Parenthesis, normalizedPat).also { it.setSpan(trees[i + 1].span()) }
                    out.add(TokenTree.Group(parenGroup))
                    i = patInfo.first
                    continue
                }
                i++
                continue
            }
            if (tt is TokenTree.Group) {
                val rebuiltGroup = Group(tt.value.delimiter(), normalizeLetOrPatternsForSyn(tt.value.stream())).also { it.setSpan(tt.value.span()) }
                out.add(TokenTree.Group(rebuiltGroup))
            } else {
                out.add(tt)
            }
            i++
        }
        return TokenStream.fromTokenTrees(out)
    }

    protected fun isFinalFnModifierAt(finalIdx: Int): Boolean {
        var k = finalIdx + 1
        while (k < size) {
            val kw = identAt(k) ?: return false
            if (kw == "fn") return true
            if (kw !in FN_MODIFIER_KEYWORDS) return false
            k++
            if (kw == "extern" && trees.getOrNull(k) is TokenTree.Literal) k++
        }
        return false
    }

    protected fun findLetPatternEndAndTopLevelOr(startIdx: Int): Pair<Int, Boolean>? {
        if (startIdx >= size) return null
        var angleDepth = 0
        var hasTopLevelOr = false
        for (j in startIdx until size) {
            val punct = (trees[j] as? TokenTree.Punct)?.value ?: continue
            val ch = punct.asChar()
            val isJoint = punct.spacing() == Spacing.Joint
            val nextCh = punctCharAt(j + 1)
            val prevPunct = if (j > startIdx) (trees.getOrNull(j - 1) as? TokenTree.Punct)?.value else null
            val prevCh = prevPunct?.asChar()
            val prevJoint = prevPunct?.spacing() == Spacing.Joint

            if (ch == '<' && !(isJoint && (nextCh == '=' || nextCh == '<' || nextCh == '-'))) {
                angleDepth++
            } else if (ch == '>' && !(prevJoint && (prevCh == '-' || prevCh == '=' || prevCh == '>'))) {
                if (angleDepth > 0) angleDepth--
            } else if (angleDepth == 0) {
                when (ch) {
                    ';' -> return j to hasTopLevelOr
                    ':' -> if (!isJoint && !(prevJoint && prevCh == ':')) return j to hasTopLevelOr
                    '=' -> {
                        val isCompoundAfter = isJoint && (nextCh == '=' || nextCh == '>')
                        val isCompoundBefore = prevJoint && prevCh != null && prevCh in COMPOUND_EQ_PREFIX_CHARS
                        if (!isCompoundAfter && !isCompoundBefore) return j to hasTopLevelOr
                    }
                    '|' -> {
                        val isLogicalOrAssign = (isJoint && (nextCh == '|' || nextCh == '=')) || (prevJoint && prevCh == '|')
                        if (!isLogicalOrAssign) hasTopLevelOr = true
                    }
                }
            }
        }
        return null
    }
}

internal fun isSingleConstBracketGroup(group: Group): Boolean {
    if (group.delimiter() != Delimiter.Bracket) return false
    val inner = group.stream().toList()
    return inner.size == 1 && (inner[0] as? TokenTree.Ident)?.value?.toString() == "const"
}

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
            val stripped = ConstVerbatimTokenStripper(i.tokens.clone().toList()).stripItem()
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
            val stripped = ConstVerbatimTokenStripper(bound.tokens.clone().toList()).stripBound()
            if (stripped != null) {
                val reparsed = parse2(TypeParamBound.Companion::parse, TokenStream.fromTokenTrees(stripped)).getOrNull()
                if (reparsed != null && reparsed !is TypeParamBound.Verbatim) {
                    return super.foldTypeParamBound(reparsed)
                }
            }
        }
        return super.foldTypeParamBound(bound)
    }
}

private class ConstVerbatimTokenStripper(
    trees: List<TokenTree>,
) : RustTokenCursor(trees) {
    fun stripItem(): List<TokenTree>? {
        if (size == 0) return null
        val out = ArrayList<TokenTree>(size)
        var idx = copyLeadingOuterAttributes(0, out)
        idx = copyOptionalPubVisibility(idx, out)
        var stripped = false
        if (identAt(idx) == "impl" && groupAt(idx + 1, Delimiter.Parenthesis) != null) {
            val afterRestriction = identAt(idx + 2)
            if (afterRestriction == "const" || afterRestriction == "unsafe" || afterRestriction == "auto" || afterRestriction == "trait") {
                idx += 2
                stripped = true
            }
        }
        val keptModifiers = ArrayList<TokenTree>(4)
        var scan = idx
        var sawConstHeader = false
        while (scan < size) {
            when (identAt(scan) ?: break) {
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
        val keyword = identAt(scan)
        if (keyword != "trait" && keyword != "impl") return null
        if (sawConstHeader) stripped = true
        out.addAll(keptModifiers)
        out.add(trees[scan])
        idx = scan + 1
        if (keyword == "impl") {
            idx = copyBalancedAngles(idx, out)
            if (punctCharAt(idx) == '!') {
                out.add(trees[idx])
                idx++
            }
            val constModLen = leadingConstBoundModifierLength(idx)
            if (constModLen > 0) {
                idx += constModLen
                stripped = true
            }
        }
        if (!stripped) return null
        out.addAll(trees.subList(idx, size))
        return out
    }

    fun stripBound(): List<TokenTree>? {
        if (size == 0) return null
        if (size == 1) {
            val parenGrp = groupAt(0, Delimiter.Parenthesis)
            if (parenGrp != null) {
                val innerStripped = ConstVerbatimTokenStripper(parenGrp.stream().toList()).stripBound() ?: return null
                val rebuiltGrp = Group(Delimiter.Parenthesis, TokenStream.fromTokenTrees(innerStripped))
                rebuiltGrp.setSpan(parenGrp.span())
                return listOf(TokenTree.Group(rebuiltGrp))
            }
        }
        val out = ArrayList<TokenTree>(size)
        var idx = 0
        if (identAt(idx) == "for" && punctCharAt(idx + 1) == '<') {
            out.add(trees[idx])
            idx = copyBalancedAngles(idx + 1, out)
        }
        val constModLen = leadingConstBoundModifierLength(idx)
        if (constModLen == 0) return null
        out.addAll(trees.subList(idx + constModLen, size))
        return out
    }

    private fun leadingConstBoundModifierLength(startIdx: Int): Int {
        val bracketGrp = groupAt(startIdx, Delimiter.Bracket)
        if (bracketGrp != null && isSingleConstBracketGroup(bracketGrp)) return 1
        if (identAt(startIdx) == "const") return 1
        val ch = punctCharAt(startIdx)
        if ((ch == '~' || ch == '?') && identAt(startIdx + 1) == "const") return 2
        return 0
    }
}
