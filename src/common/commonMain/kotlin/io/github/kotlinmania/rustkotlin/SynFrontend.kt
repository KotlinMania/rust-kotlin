package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.procmacro2.Delimiter
import io.github.kotlinmania.procmacro2.Group
import io.github.kotlinmania.procmacro2.Spacing
import io.github.kotlinmania.procmacro2.TokenStream
import io.github.kotlinmania.procmacro2.TokenTree
import io.github.kotlinmania.syn.Expr
import io.github.kotlinmania.syn.ExprParse
import io.github.kotlinmania.syn.File
import io.github.kotlinmania.syn.FileParse
import io.github.kotlinmania.syn.ForeignItem
import io.github.kotlinmania.syn.ImplItem
import io.github.kotlinmania.syn.Item
import io.github.kotlinmania.syn.ItemParse
import io.github.kotlinmania.syn.Pat
import io.github.kotlinmania.syn.Stmt
import io.github.kotlinmania.syn.SynType
import io.github.kotlinmania.syn.TraitItem
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
    val normalizedSource = normalizeRustSourceForLexing(source)
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

internal fun lexRustTokenStreamOrNull(source: String): TokenStream? =
    lexTokenStreamOrNull(normalizeRustSourceForLexing(source))

internal fun lexRustTokenTrees(source: String): List<TokenTree> =
    lexRustTokenStreamOrNull(source)?.toList().orEmpty()

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
    val normalizedSource = normalizeRustSourceForLexing(source)
    val tokens = lexTokenStreamOrNull(normalizedSource) ?: return null
    return recognizePreLexedRustOrNull(normalizedSource, tokens)
}

/**
 * Normalizes a leading `#!...` interpreter shebang (without `#![...]` inner attribute brackets)
 * to equal-length spaces so UTF-16 span offsets remain unchanged, then rewrites unescaped
 * supplementary character literals (`'🦀'` -> `'\\u{1f980}'`) outside strings and comments.
 */
internal fun normalizeRustSourceForLexing(source: String): String {
    val withoutShebang =
        if (source.startsWith("#!") && !source.startsWith("#![")) {
            val nl = source.indexOf('\n')
            if (nl >= 0) " ".repeat(nl) + source.substring(nl) else " ".repeat(source.length)
        } else {
            source
        }
    return SupplementaryCharLiteralRewriter(withoutShebang).rewrite()
}

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

    protected fun findBalancedDelimiterEnd(
        openIdx: Int,
        openCh: Char,
        closeCh: Char,
    ): Int {
        if (openIdx >= len || source[openIdx] != openCh) return -1
        var depth = 1
        var k = openIdx + 1
        while (k < len && depth > 0) {
            val skipped = tryScanCommentOrStringEnd(k)
            if (skipped != null) {
                k = skipped
                continue
            }
            when (source[k]) {
                '\'' -> {
                    val tickEnd = scanEscapedCharLiteralEnd(k)
                    k = if (tickEnd > k + 1 && tickEnd <= len && source[tickEnd - 1] == '\'') tickEnd else k + 1
                }
                openCh -> {
                    depth++
                    k++
                }
                closeCh -> {
                    depth--
                    k++
                }
                else -> k++
            }
        }
        return if (depth == 0) k - 1 else -1
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
 * Token-constructed AST fragment parsers built directly on `proc_macro2` [TokenTree] nodes
 * (zero `TokenStream.fromString` string round-trips).
 */
internal object SynAstFragmentParser {
    fun parseRawFile(stream: TokenStream): File? =
        parse2(FileParse::parse, normalizeLetOrPatternsForSyn(stream)).getOrNull()

    fun parseNormalizedFile(stream: TokenStream): File? =
        parseRawFile(stream)?.let(RustNextAstNormalizer::foldFile)

    fun parseRawImplItems(inner: TokenStream): List<ImplItem>? {
        val wrapped =
            TokenStream.fromTokenTrees(
                listOf(
                    RustTokenCursor.identTree("impl"),
                    RustTokenCursor.identTree("Dummy"),
                    RustTokenCursor.groupStreamTree(Delimiter.Brace, inner),
                ),
            )
        return (parseRawFile(wrapped)?.items?.singleOrNull() as? Item.Impl)?.items
    }

    fun parseNormalizedImplItems(inner: TokenStream): List<ImplItem>? =
        parseRawImplItems(inner)?.map(RustNextAstNormalizer::foldImplItem)

    fun parseRawTraitItems(inner: TokenStream): List<TraitItem>? {
        val wrapped =
            TokenStream.fromTokenTrees(
                listOf(
                    RustTokenCursor.identTree("trait"),
                    RustTokenCursor.identTree("Dummy"),
                    RustTokenCursor.groupStreamTree(Delimiter.Brace, inner),
                ),
            )
        return (parseRawFile(wrapped)?.items?.singleOrNull() as? Item.Trait)?.items
    }

    fun parseNormalizedBlockStmts(inner: TokenStream): List<Stmt>? {
        val wrapped =
            TokenStream.fromTokenTrees(
                listOf(
                    RustTokenCursor.identTree("fn"),
                    RustTokenCursor.identTree("dummy"),
                    RustTokenCursor.groupTree(Delimiter.Parenthesis),
                    RustTokenCursor.groupStreamTree(Delimiter.Brace, inner),
                ),
            )
        return (parseNormalizedFile(wrapped)?.items?.singleOrNull() as? Item.Fn)?.block?.stmts
    }

    fun parseForeignFnFromTokens(fnTokens: List<TokenTree>): ForeignItem.Fn? {
        if (fnTokens.isEmpty()) return null
        val body = ArrayList<TokenTree>(fnTokens.size + 2)
        body += RustTokenCursor.identTree("pub")
        body.addAll(fnTokens)
        if ((fnTokens.last() as? TokenTree.Punct)?.value?.asChar() != ';') {
            body += RustTokenCursor.punctTree(';')
        }
        val wrapped =
            TokenStream.fromTokenTrees(
                listOf(
                    RustTokenCursor.identTree("extern"),
                    RustTokenCursor.stringLiteralTree("C"),
                    RustTokenCursor.groupTree(Delimiter.Brace, body),
                ),
            )
        val foreignMod = parseNormalizedFile(wrapped)?.items?.singleOrNull() as? Item.ForeignMod ?: return null
        return foreignMod.items.singleOrNull() as? ForeignItem.Fn
    }

    fun u8SynType(): SynType =
        parse2(SynType.Companion::parse, TokenStream.fromTokenTrees(listOf(RustTokenCursor.identTree("u8")))).getOrThrow()
}

/**
 * Normalizes RFC 3762 `const trait` / `const impl`, `[const]` / `~const` bounds, `dyn*`,
 * `&pin mut` / `&pin const`, `for<T> fn(...)`, `box pat`, `become expr`, and trait/impl
 * member `Verbatim` nodes that `syn` preserves as `*.Verbatim` into structured AST nodes
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
        if (t is SynType.Verbatim) {
            val stripped = ConstVerbatimTokenStripper(t.tokens.clone().toList()).stripType()
            if (stripped != null) {
                val reparsed = parse2(SynType.Companion::parse, TokenStream.fromTokenTrees(stripped)).getOrNull()
                if (reparsed != null && reparsed !is SynType.Verbatim) {
                    return super.foldType(reparsed)
                }
            }
        }
        return super.foldType(t)
    }

    override fun foldPat(p: Pat): Pat {
        if (p is Pat.Verbatim) {
            val trees = p.tokens.clone().toList()
            if (trees.size >= 2 && RustTokenCursor(trees).identAt(0) == "box") {
                val reparsed = parse2(Pat.Companion::parseSingle, TokenStream.fromTokenTrees(trees.subList(1, trees.size))).getOrNull()
                if (reparsed != null && reparsed !is Pat.Verbatim) {
                    return super.foldPat(reparsed)
                }
            }
        }
        return super.foldPat(p)
    }

    override fun foldExpr(e: Expr): Expr {
        if (e is Expr.Verbatim) {
            val trees = e.tokens.clone().toList()
            if (trees.size >= 2 && RustTokenCursor(trees).identAt(0) == "become") {
                val retTokens = ArrayList<TokenTree>(trees.size)
                retTokens += RustTokenCursor.identTree("return", trees[0].span())
                retTokens.addAll(trees.subList(1, trees.size))
                val reparsed = parse2(ExprParse::parse, TokenStream.fromTokenTrees(retTokens)).getOrNull()
                if (reparsed != null && reparsed !is Expr.Verbatim) {
                    return super.foldExpr(reparsed)
                }
            }
        }
        return super.foldExpr(e)
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

    override fun foldImplItem(item: ImplItem): ImplItem {
        if (item is ImplItem.Verbatim) {
            val stripped = ConstVerbatimTokenStripper(item.tokens.clone().toList()).stripImplMember()
            if (stripped != null) {
                val reparsed = SynAstFragmentParser.parseRawImplItems(TokenStream.fromTokenTrees(stripped))?.singleOrNull()
                if (reparsed != null && reparsed !is ImplItem.Verbatim) {
                    return super.foldImplItem(reparsed)
                }
            }
        }
        return super.foldImplItem(item)
    }

    override fun foldTraitItem(item: TraitItem): TraitItem {
        if (item is TraitItem.Verbatim) {
            val stripped = ConstVerbatimTokenStripper(item.tokens.clone().toList()).stripTraitMember()
            if (stripped != null) {
                val reparsed = SynAstFragmentParser.parseRawTraitItems(TokenStream.fromTokenTrees(stripped))?.singleOrNull()
                if (reparsed != null && reparsed !is TraitItem.Verbatim) {
                    return super.foldTraitItem(reparsed)
                }
            }
        }
        return super.foldTraitItem(item)
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

    fun stripImplMember(): List<TokenTree>? {
        if (size == 0) return null
        val out = ArrayList<TokenTree>(size)
        var idx = copyLeadingOuterAttributes(0, out)
        idx = copyOptionalPubVisibility(idx, out)
        var changed = false
        if (identAt(idx) == "default") {
            out += trees[idx]
            idx++
        }
        if (identAt(idx) == "type" && cleanIdentAt(idx + 1) != null) {
            out += trees[idx]
            out += trees[idx + 1]
            idx = copyBalancedAngles(idx + 2, out)
            if (isSingleColonAt(idx)) {
                var eqIdx = idx + 1
                var angleDepth = 0
                while (eqIdx < size) {
                    val ch = punctCharAt(eqIdx)
                    if (ch == '<') {
                        angleDepth++
                    } else if (ch == '>' && !isRArrowEndAt(eqIdx) && angleDepth > 0) {
                        angleDepth--
                    } else if (angleDepth == 0 && (ch == '=' || ch == ';')) {
                        break
                    }
                    eqIdx++
                }
                if (punctCharAt(eqIdx) == '=') {
                    out.addAll(trees.subList(eqIdx, size))
                    return out
                }
            }
        }
        val rem = stripConstAndPinModifiersRecursive(trees.subList(idx, size))
        if (rem != null) {
            out.addAll(rem)
            changed = true
        }
        return if (changed) out else null
    }

    fun stripTraitMember(): List<TokenTree>? {
        if (size == 0) return null
        val out = ArrayList<TokenTree>(size)
        var idx = copyLeadingOuterAttributes(0, out)
        var changed = false
        val afterPub = copyOptionalPubVisibility(idx)
        if (afterPub > idx) {
            idx = afterPub
            changed = true
        }
        while (identAt(idx) == "default" || identAt(idx) == "final") {
            idx++
            changed = true
        }
        val remSlice = trees.subList(idx, size)
        val remStripped = stripConstAndPinModifiersRecursive(remSlice)
        if (remStripped != null) {
            out.addAll(remStripped)
            return out
        }
        if (!changed) return null
        out.addAll(remSlice)
        return out
    }

    fun stripType(): List<TokenTree>? {
        if (size == 0) return null
        if (identAt(0) == "dyn" && punctCharAt(1) == '*') {
            val out = ArrayList<TokenTree>(size - 1)
            out += trees[0]
            val tail = trees.subList(2, size)
            out.addAll(stripConstAndPinModifiersRecursive(tail) ?: tail)
            return out
        }
        if (identAt(0) == "for" && punctCharAt(1) == '<') {
            val afterAngles = skipBalancedAngles(1)
            if (afterAngles != null && afterAngles < size) {
                val tail = trees.subList(afterAngles, size)
                return stripConstAndPinModifiersRecursive(tail) ?: tail
            }
        }
        return stripConstAndPinModifiersRecursive(trees)
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

    private fun stripConstAndPinModifiersRecursive(slice: List<TokenTree>): List<TokenTree>? {
        if (slice.isEmpty()) return null
        val c = RustTokenCursor(slice)
        val out = ArrayList<TokenTree>(slice.size)
        var changed = false
        var i = 0
        while (i < c.size) {
            val bracketGrp = c.groupAt(i, Delimiter.Bracket)
            if (bracketGrp != null && c.punctCharAt(i - 1) != '#' && isSingleConstBracketGroup(bracketGrp)) {
                changed = true
                i++
                continue
            }
            val ch = c.punctCharAt(i)
            if ((ch == '~' || ch == '?') && c.identAt(i + 1) == "const") {
                changed = true
                i += 2
                continue
            }
            if (ch == '&') {
                out += slice[i]
                var next = i + 1
                if (c.isLifetimeAt(next)) {
                    out += slice[next]
                    out += slice[next + 1]
                    next += 2
                }
                if (c.identAt(next) == "pin") {
                    changed = true
                    next++
                    if (c.identAt(next) == "const") {
                        next++
                    } else if (c.identAt(next) == "mut") {
                        out += slice[next]
                        next++
                    }
                    i = next
                    continue
                }
            }
            val tt = slice[i]
            if (tt is TokenTree.Group) {
                val innerStripped = stripConstAndPinModifiersRecursive(tt.value.stream().toList())
                if (innerStripped != null) {
                    out += RustTokenCursor.groupTree(tt.value.delimiter(), innerStripped, tt.value.span())
                    changed = true
                    i++
                    continue
                }
            }
            out += tt
            i++
        }
        return if (changed) out else null
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
