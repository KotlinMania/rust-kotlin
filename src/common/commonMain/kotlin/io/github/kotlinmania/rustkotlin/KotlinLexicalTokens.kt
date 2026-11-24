package io.github.kotlinmania.rustkotlin

/**
 * Lossless lexical token representation for emitted Kotlin source and rendered type strings.
 *
 * Concatenating [rawText] across the list produced by [KotlinSourceTokenizer.tokenize]
 * reproduces the exact input string character-for-character while exposing comments,
 * string literals, `$ident` / `${...}` interpolations, char literals, backticked identifiers,
 * numeric literals, `->` arrows, identifiers, and punctuation for token-based walkers and emitters.
 */
internal sealed class KotlinLexToken {
    abstract val rawText: String
    abstract val start: Int
    abstract val end: Int
    abstract val line: Int
    abstract val column: Int
    abstract val interpolationDepth: Int

    data class LineComment(
        override val rawText: String,
        override val start: Int,
        override val end: Int,
        override val line: Int,
        override val column: Int,
        override val interpolationDepth: Int,
    ) : KotlinLexToken()

    data class BlockComment(
        override val rawText: String,
        override val start: Int,
        override val end: Int,
        override val line: Int,
        override val column: Int,
        override val interpolationDepth: Int,
    ) : KotlinLexToken()

    data class StringOpen(
        override val rawText: String,
        val isRaw: Boolean,
        override val start: Int,
        override val end: Int,
        override val line: Int,
        override val column: Int,
        override val interpolationDepth: Int,
    ) : KotlinLexToken()

    data class StringText(
        override val rawText: String,
        override val start: Int,
        override val end: Int,
        override val line: Int,
        override val column: Int,
        override val interpolationDepth: Int,
    ) : KotlinLexToken()

    data class StringIdentTemplate(
        override val rawText: String,
        val ident: String,
        override val start: Int,
        override val end: Int,
        override val line: Int,
        override val column: Int,
        override val interpolationDepth: Int,
    ) : KotlinLexToken()

    data class TemplateExprOpen(
        override val rawText: String,
        override val start: Int,
        override val end: Int,
        override val line: Int,
        override val column: Int,
        override val interpolationDepth: Int,
    ) : KotlinLexToken()

    data class TemplateExprClose(
        override val rawText: String,
        override val start: Int,
        override val end: Int,
        override val line: Int,
        override val column: Int,
        override val interpolationDepth: Int,
    ) : KotlinLexToken()

    data class StringClose(
        override val rawText: String,
        override val start: Int,
        override val end: Int,
        override val line: Int,
        override val column: Int,
        override val interpolationDepth: Int,
    ) : KotlinLexToken()

    data class CharLiteral(
        override val rawText: String,
        override val start: Int,
        override val end: Int,
        override val line: Int,
        override val column: Int,
        override val interpolationDepth: Int,
    ) : KotlinLexToken()

    data class BacktickIdent(
        override val rawText: String,
        val ident: String,
        override val start: Int,
        override val end: Int,
        override val line: Int,
        override val column: Int,
        override val interpolationDepth: Int,
    ) : KotlinLexToken()

    data class NumberLiteral(
        override val rawText: String,
        override val start: Int,
        override val end: Int,
        override val line: Int,
        override val column: Int,
        override val interpolationDepth: Int,
    ) : KotlinLexToken()

    data class Ident(
        override val rawText: String,
        override val start: Int,
        override val end: Int,
        override val line: Int,
        override val column: Int,
        override val interpolationDepth: Int,
    ) : KotlinLexToken()

    data class Arrow(
        override val rawText: String,
        override val start: Int,
        override val end: Int,
        override val line: Int,
        override val column: Int,
        override val interpolationDepth: Int,
    ) : KotlinLexToken()

    data class Whitespace(
        override val rawText: String,
        override val start: Int,
        override val end: Int,
        override val line: Int,
        override val column: Int,
        override val interpolationDepth: Int,
    ) : KotlinLexToken()

    data class Newline(
        override val rawText: String,
        override val start: Int,
        override val end: Int,
        override val line: Int,
        override val column: Int,
        override val interpolationDepth: Int,
    ) : KotlinLexToken()

    data class Punct(
        override val rawText: String,
        val ch: Char,
        override val start: Int,
        override val end: Int,
        override val line: Int,
        override val column: Int,
        override val interpolationDepth: Int,
    ) : KotlinLexToken()
}

internal open class KotlinSourceTokenizer {
    open fun tokenize(source: String): List<KotlinLexToken> {
        if (source.isEmpty()) return emptyList()
        val out = ArrayList<KotlinLexToken>(maxOf(16, source.length / 4))
        val interpModes = ArrayList<Char>()
        val interpBraces = ArrayList<Int>()
        var mode = 'C' // 'C' = code, 'S' = "...", 'R' = """..."""
        var i = 0
        var line = 1
        var col = 1
        val n = source.length

        fun advanceTo(target: Int) {
            while (i < target) {
                if (source[i] == '\n') {
                    line++
                    col = 1
                } else {
                    col++
                }
                i++
            }
        }

        while (i < n) {
            val depth = interpModes.size
            val tokStart = i
            val tokLine = line
            val tokCol = col
            when (mode) {
                'C' -> {
                    val ch = source[i]
                    when {
                        ch == '/' && i + 1 < n && source[i + 1] == '/' -> {
                            var j = i + 2
                            while (j < n && source[j] != '\n') j++
                            val raw = source.substring(tokStart, j)
                            advanceTo(j)
                            out += KotlinLexToken.LineComment(raw, tokStart, j, tokLine, tokCol, depth)
                        }
                        ch == '/' && i + 1 < n && source[i + 1] == '*' -> {
                            var j = i + 2
                            var cDepth = 1
                            while (j < n && cDepth > 0) {
                                if (source[j] == '/' && j + 1 < n && source[j + 1] == '*') {
                                    cDepth++
                                    j += 2
                                } else if (source[j] == '*' && j + 1 < n && source[j + 1] == '/') {
                                    cDepth--
                                    j += 2
                                } else {
                                    j++
                                }
                            }
                            val raw = source.substring(tokStart, j)
                            advanceTo(j)
                            out += KotlinLexToken.BlockComment(raw, tokStart, j, tokLine, tokCol, depth)
                        }
                        ch == '"' && i + 2 < n && source[i + 1] == '"' && source[i + 2] == '"' -> {
                            val j = i + 3
                            advanceTo(j)
                            mode = 'R'
                            out += KotlinLexToken.StringOpen("\"\"\"", true, tokStart, j, tokLine, tokCol, depth)
                        }
                        ch == '"' -> {
                            val j = i + 1
                            advanceTo(j)
                            mode = 'S'
                            out += KotlinLexToken.StringOpen("\"", false, tokStart, j, tokLine, tokCol, depth)
                        }
                        ch == '\'' -> {
                            var j = i + 1
                            while (j < n && source[j] != '\'' && source[j] != '\n') {
                                j += if (source[j] == '\\' && j + 1 < n) 2 else 1
                            }
                            if (j < n && source[j] == '\'') {
                                j++
                                val raw = source.substring(tokStart, j)
                                advanceTo(j)
                                out += KotlinLexToken.CharLiteral(raw, tokStart, j, tokLine, tokCol, depth)
                            } else {
                                advanceTo(i + 1)
                                out += KotlinLexToken.Punct("'", '\'', tokStart, tokStart + 1, tokLine, tokCol, depth)
                            }
                        }
                        ch == '`' -> {
                            var j = i + 1
                            while (j < n && source[j] != '`' && source[j] != '\n') j++
                            val ident = source.substring(tokStart + 1, j)
                            if (j < n && source[j] == '`') j++
                            val raw = source.substring(tokStart, j)
                            advanceTo(j)
                            out += KotlinLexToken.BacktickIdent(raw, ident, tokStart, j, tokLine, tokCol, depth)
                        }
                        ch in '0'..'9' -> {
                            var j = i + 1
                            while (j < n) {
                                val c = source[j]
                                if ((c == 'e' || c == 'E') && j + 1 < n && (source[j + 1] == '+' || source[j + 1] == '-')) {
                                    j += 2
                                } else if (c == '.' && j + 1 < n && source[j + 1] in '0'..'9') {
                                    j += 2
                                } else if (c.isLetterOrDigit() || c == '_') {
                                    j++
                                } else {
                                    break
                                }
                            }
                            val raw = source.substring(tokStart, j)
                            advanceTo(j)
                            out += KotlinLexToken.NumberLiteral(raw, tokStart, j, tokLine, tokCol, depth)
                        }
                        isKotlinIdentStart(ch) -> {
                            var j = i + 1
                            while (j < n && isKotlinIdentPart(source[j])) j++
                            val raw = source.substring(tokStart, j)
                            advanceTo(j)
                            out += KotlinLexToken.Ident(raw, tokStart, j, tokLine, tokCol, depth)
                        }
                        ch == '\n' -> {
                            advanceTo(i + 1)
                            out += KotlinLexToken.Newline("\n", tokStart, tokStart + 1, tokLine, tokCol, depth)
                        }
                        ch == ' ' || ch == '\t' || ch == '\r' -> {
                            var j = i + 1
                            while (j < n && (source[j] == ' ' || source[j] == '\t' || source[j] == '\r')) j++
                            val raw = source.substring(tokStart, j)
                            advanceTo(j)
                            out += KotlinLexToken.Whitespace(raw, tokStart, j, tokLine, tokCol, depth)
                        }
                        ch == '-' && i + 1 < n && source[i + 1] == '>' -> {
                            val j = i + 2
                            advanceTo(j)
                            out += KotlinLexToken.Arrow("->", tokStart, j, tokLine, tokCol, depth)
                        }
                        ch == '{' -> {
                            if (interpBraces.isNotEmpty()) {
                                interpBraces[interpBraces.lastIndex] = interpBraces.last() + 1
                            }
                            advanceTo(i + 1)
                            out += KotlinLexToken.Punct("{", '{', tokStart, tokStart + 1, tokLine, tokCol, depth)
                        }
                        ch == '}' -> {
                            advanceTo(i + 1)
                            if (interpBraces.isNotEmpty()) {
                                val nextDepth = interpBraces.last() - 1
                                if (nextDepth <= 0) {
                                    interpBraces.removeAt(interpBraces.lastIndex)
                                    mode = interpModes.removeAt(interpModes.lastIndex)
                                    out += KotlinLexToken.TemplateExprClose("}", tokStart, tokStart + 1, tokLine, tokCol, interpModes.size)
                                } else {
                                    interpBraces[interpBraces.lastIndex] = nextDepth
                                    out += KotlinLexToken.Punct("}", '}', tokStart, tokStart + 1, tokLine, tokCol, depth)
                                }
                            } else {
                                out += KotlinLexToken.Punct("}", '}', tokStart, tokStart + 1, tokLine, tokCol, depth)
                            }
                        }
                        else -> {
                            advanceTo(i + 1)
                            out += KotlinLexToken.Punct(ch.toString(), ch, tokStart, tokStart + 1, tokLine, tokCol, depth)
                        }
                    }
                }
                'S' -> {
                    val ch = source[i]
                    when {
                        ch == '"' -> {
                            advanceTo(i + 1)
                            mode = 'C'
                            out += KotlinLexToken.StringClose("\"", tokStart, tokStart + 1, tokLine, tokCol, depth)
                        }
                        ch == '$' && i + 1 < n && source[i + 1] == '{' -> {
                            val j = i + 2
                            advanceTo(j)
                            interpModes.add('S')
                            interpBraces.add(1)
                            mode = 'C'
                            out += KotlinLexToken.TemplateExprOpen("\${", tokStart, j, tokLine, tokCol, depth)
                        }
                        ch == '$' && i + 1 < n && isKotlinIdentStart(source[i + 1]) -> {
                            var j = i + 2
                            while (j < n && isKotlinIdentPart(source[j])) j++
                            val raw = source.substring(tokStart, j)
                            val ident = source.substring(tokStart + 1, j)
                            advanceTo(j)
                            out += KotlinLexToken.StringIdentTemplate(raw, ident, tokStart, j, tokLine, tokCol, depth)
                        }
                        else -> {
                            var j = i
                            while (j < n) {
                                val c = source[j]
                                if (c == '\\' && j + 1 < n) {
                                    j += 2
                                } else if (c == '"') {
                                    break
                                } else if (c == '$' && j + 1 < n && (source[j + 1] == '{' || isKotlinIdentStart(source[j + 1]))) {
                                    break
                                } else {
                                    j++
                                }
                            }
                            val raw = source.substring(tokStart, j)
                            advanceTo(j)
                            out += KotlinLexToken.StringText(raw, tokStart, j, tokLine, tokCol, depth)
                        }
                    }
                }
                else -> {
                    val ch = source[i]
                    when {
                        ch == '"' && i + 2 < n && source[i + 1] == '"' && source[i + 2] == '"' &&
                            (i + 3 >= n || source[i + 3] != '"') -> {
                            val j = i + 3
                            advanceTo(j)
                            mode = 'C'
                            out += KotlinLexToken.StringClose("\"\"\"", tokStart, j, tokLine, tokCol, depth)
                        }
                        ch == '$' && i + 1 < n && source[i + 1] == '{' -> {
                            val j = i + 2
                            advanceTo(j)
                            interpModes.add('R')
                            interpBraces.add(1)
                            mode = 'C'
                            out += KotlinLexToken.TemplateExprOpen("\${", tokStart, j, tokLine, tokCol, depth)
                        }
                        ch == '$' && i + 1 < n && isKotlinIdentStart(source[i + 1]) -> {
                            var j = i + 2
                            while (j < n && isKotlinIdentPart(source[j])) j++
                            val raw = source.substring(tokStart, j)
                            val ident = source.substring(tokStart + 1, j)
                            advanceTo(j)
                            out += KotlinLexToken.StringIdentTemplate(raw, ident, tokStart, j, tokLine, tokCol, depth)
                        }
                        else -> {
                            var j = i
                            while (j < n) {
                                val c = source[j]
                                if (c == '"' && j + 2 < n && source[j + 1] == '"' && source[j + 2] == '"' &&
                                    (j + 3 >= n || source[j + 3] != '"')
                                ) {
                                    break
                                } else if (c == '$' && j + 1 < n && (source[j + 1] == '{' || isKotlinIdentStart(source[j + 1]))) {
                                    break
                                } else {
                                    j++
                                }
                            }
                            val raw = source.substring(tokStart, j)
                            advanceTo(j)
                            out += KotlinLexToken.StringText(raw, tokStart, j, tokLine, tokCol, depth)
                        }
                    }
                }
            }
        }
        return out
    }

    companion object Default : KotlinSourceTokenizer()
}

internal fun isKotlinIdentStart(ch: Char): Boolean = ch == '_' || ch.isLetter()

internal fun isKotlinIdentPart(ch: Char): Boolean = ch == '_' || ch.isLetterOrDigit()

/**
 * Extensible walker over [KotlinLexToken] streams with reusable balanced-delimiter,
 * type-argument `<...>`, identifier-collection, and top-level comma-splitting primitives.
 */
internal open class KotlinTokenWalker(
    val source: String,
    val tokens: List<KotlinLexToken> = KotlinSourceTokenizer.tokenize(source),
) {
    fun skipHorizontalWhitespace(startIdx: Int): Int {
        var k = startIdx
        while (k < tokens.size && tokens[k] is KotlinLexToken.Whitespace) k++
        return k
    }

    fun skipAllWhitespace(startIdx: Int): Int {
        var k = startIdx
        while (k < tokens.size && (tokens[k] is KotlinLexToken.Whitespace || tokens[k] is KotlinLexToken.Newline)) k++
        return k
    }

    fun nextNonTriviaIndex(startIdx: Int, limit: Int = tokens.size): Int {
        var k = startIdx
        while (k < limit) {
            val t = tokens[k]
            if (t !is KotlinLexToken.Whitespace &&
                t !is KotlinLexToken.Newline &&
                t !is KotlinLexToken.LineComment &&
                t !is KotlinLexToken.BlockComment
            ) {
                return k
            }
            k++
        }
        return limit
    }

    fun identTextAt(idx: Int): String? {
        val tok = tokens.getOrNull(idx) ?: return null
        if (tok.interpolationDepth != 0) return null
        return when (tok) {
            is KotlinLexToken.Ident -> tok.rawText
            is KotlinLexToken.BacktickIdent -> tok.ident
            else -> null
        }
    }

    fun punctAt(idx: Int): Char? {
        val tok = tokens.getOrNull(idx) as? KotlinLexToken.Punct ?: return null
        return if (tok.interpolationDepth == 0) tok.ch else null
    }

    fun findMatchingParenToken(openParenTokenIdx: Int): Int {
        val openTok = tokens.getOrNull(openParenTokenIdx) as? KotlinLexToken.Punct ?: return -1
        if (openTok.ch != '(') return -1
        val baseInterp = openTok.interpolationDepth
        var depth = 0
        var k = openParenTokenIdx
        while (k < tokens.size) {
            val tok = tokens[k]
            if (tok.interpolationDepth == baseInterp && tok is KotlinLexToken.Punct) {
                when (tok.ch) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) return k
                    }
                }
            }
            k++
        }
        return -1
    }

    fun findMatchingBracketToken(openBracketTokenIdx: Int): Int {
        val openTok = tokens.getOrNull(openBracketTokenIdx) as? KotlinLexToken.Punct ?: return -1
        if (openTok.ch != '[') return -1
        val baseInterp = openTok.interpolationDepth
        var depth = 0
        var k = openBracketTokenIdx
        while (k < tokens.size) {
            val tok = tokens[k]
            if (tok.interpolationDepth == baseInterp && tok is KotlinLexToken.Punct) {
                when (tok.ch) {
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) return k
                    }
                }
            }
            k++
        }
        return -1
    }

    fun skipAnnotation(atTokenIdx: Int, limit: Int = tokens.size): Int {
        if (punctAt(atTokenIdx) != '@') return atTokenIdx
        var k = nextNonTriviaIndex(atTokenIdx + 1, limit)
        if (punctAt(k) == '[') {
            val closeBracket = findMatchingBracketToken(k)
            return if (closeBracket >= k) closeBracket + 1 else limit
        }
        if (identTextAt(k) != null && punctAt(nextNonTriviaIndex(k + 1, limit)) == ':') {
            k = nextNonTriviaIndex(nextNonTriviaIndex(k + 1, limit) + 1, limit)
        }
        while (identTextAt(k) != null) {
            val dotIdx = nextNonTriviaIndex(k + 1, limit)
            if (punctAt(dotIdx) == '.') {
                k = nextNonTriviaIndex(dotIdx + 1, limit)
            } else {
                k = dotIdx
                break
            }
        }
        if (punctAt(k) == '<') {
            val closeAngle = findMatchingTypeAngleToken(k)
            if (closeAngle > k) {
                k = nextNonTriviaIndex(closeAngle + 1, limit)
            }
        }
        if (punctAt(k) == '(') {
            val closeParen = findMatchingParenToken(k)
            if (closeParen > k) {
                return closeParen + 1
            }
        }
        return k
    }

    fun containsIdentifier(ident: String): Boolean {
        if (ident.isEmpty()) return false
        for (tok in tokens) {
            when (tok) {
                is KotlinLexToken.Ident -> if (tok.rawText == ident) return true
                is KotlinLexToken.BacktickIdent -> if (tok.ident == ident) return true
                is KotlinLexToken.StringIdentTemplate -> if (tok.ident == ident) return true
                else -> {}
            }
        }
        return false
    }

    fun previousNonTriviaToken(beforeIdx: Int): KotlinLexToken? {
        var k = beforeIdx - 1
        while (k >= 0) {
            val t = tokens[k]
            if (t !is KotlinLexToken.Whitespace &&
                t !is KotlinLexToken.Newline &&
                t !is KotlinLexToken.LineComment &&
                t !is KotlinLexToken.BlockComment
            ) {
                return t
            }
            k--
        }
        return null
    }

    fun findMatchingBraceEndToken(openTokenIdx: Int): Int {
        var k = openTokenIdx
        var depth = 0
        val baseInterp = tokens.getOrNull(openTokenIdx)?.interpolationDepth ?: 0
        while (k < tokens.size) {
            val tok = tokens[k]
            if (tok.interpolationDepth == baseInterp && tok is KotlinLexToken.Punct) {
                when (tok.ch) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return k + 1
                    }
                }
            }
            k++
        }
        return tokens.size
    }

    fun findMatchingTypeAngleToken(openAngleTokenIdx: Int): Int {
        val openTok = tokens.getOrNull(openAngleTokenIdx) as? KotlinLexToken.Punct ?: return -1
        if (openTok.ch != '<') return -1
        val baseInterp = openTok.interpolationDepth
        var angleDepth = 0
        var parenDepth = 0
        var k = openAngleTokenIdx
        while (k < tokens.size) {
            val tok = tokens[k]
            if (tok.interpolationDepth == baseInterp && tok is KotlinLexToken.Punct) {
                when (tok.ch) {
                    '<' -> angleDepth++
                    '>' -> {
                        angleDepth--
                        if (angleDepth == 0 && parenDepth == 0) return k
                        if (angleDepth < 0) return -1
                    }
                    '(' -> parenDepth++
                    ')' -> {
                        if (parenDepth == 0) return -1
                        parenDepth--
                    }
                    '{', '}', ';', '=' -> return -1
                }
            }
            k++
        }
        return -1
    }

    fun collectCodeIdentifiers(startTokenIdx: Int, endTokenExclusive: Int, out: MutableSet<String>) {
        val limit = minOf(endTokenExclusive, tokens.size)
        for (k in startTokenIdx until limit) {
            val tok = tokens[k]
            if (tok.interpolationDepth == 0 && tok is KotlinLexToken.Ident) {
                out += tok.rawText
            }
        }
    }

    inline fun anyGenericApplication(
        predicate: (ownerSimpleIdent: String, typeArgs: List<String>) -> Boolean,
    ): Boolean {
        var idx = 0
        while (idx < tokens.size) {
            val tok = tokens[idx]
            if (tok.interpolationDepth == 0 && tok is KotlinLexToken.Punct && tok.ch == '<') {
                val closeIdx = findMatchingTypeAngleToken(idx)
                if (closeIdx > idx) {
                    val prev = previousNonTriviaToken(idx)
                    val ownerIdent =
                        when (prev) {
                            is KotlinLexToken.Ident -> prev.rawText
                            is KotlinLexToken.BacktickIdent -> prev.ident
                            else -> ""
                        }
                    val innerSlice = source.substring(tok.end, tokens[closeIdx].start)
                    val topArgs = splitTopLevelCommaList(innerSlice).map { it.trim() }
                    if (predicate(ownerIdent, topArgs)) return true
                    idx = closeIdx + 1
                    continue
                }
            }
            idx++
        }
        return false
    }

    companion object {
        fun splitTopLevelCommaList(segment: String): List<String> {
            if (segment.isBlank()) return emptyList()
            val walker = KotlinTokenWalker(segment)
            val parts = mutableListOf<String>()
            var partCharStart = 0
            var angleDepth = 0
            var parenDepth = 0
            var bracketDepth = 0
            var braceDepth = 0
            for (tok in walker.tokens) {
                if (tok.interpolationDepth != 0) continue
                if (tok is KotlinLexToken.Punct) {
                    when (tok.ch) {
                        '<' -> angleDepth++
                        '>' -> if (angleDepth > 0) angleDepth--
                        '(' -> parenDepth++
                        ')' -> if (parenDepth > 0) parenDepth--
                        '[' -> bracketDepth++
                        ']' -> if (bracketDepth > 0) bracketDepth--
                        '{' -> braceDepth++
                        '}' -> if (braceDepth > 0) braceDepth--
                        ',' ->
                            if (angleDepth == 0 && parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) {
                                parts += segment.substring(partCharStart, tok.start)
                                partCharStart = tok.end
                            }
                    }
                }
            }
            parts += segment.substring(partCharStart)
            return parts.filter { it.isNotBlank() }
        }

        fun findTopLevelCommaIndex(
            text: String,
            trackParens: Boolean = false,
        ): Int {
            if (',' !in text) return -1
            val walker = KotlinTokenWalker(text)
            var angleDepth = 0
            var parenDepth = 0
            for (tok in walker.tokens) {
                if (tok.interpolationDepth != 0 || tok !is KotlinLexToken.Punct) continue
                when (tok.ch) {
                    '<' -> angleDepth++
                    '>' -> if (angleDepth > 0) angleDepth--
                    '(' -> if (trackParens) parenDepth++
                    ')' -> if (trackParens && parenDepth > 0) parenDepth--
                    ',' -> if (angleDepth == 0 && (!trackParens || parenDepth == 0)) return tok.start
                }
            }
            return -1
        }

        inline fun scanTopLevelTokens(
            text: String,
            trackAllBrackets: Boolean,
            onTopLevelToken: (tok: KotlinLexToken, depth: Int) -> Boolean,
        ): Boolean {
            val tokens = KotlinSourceTokenizer.tokenize(text)
            var depth = 0
            var unclosedQuotes = 0
            for (tok in tokens) {
                when (tok) {
                    is KotlinLexToken.StringOpen -> {
                        unclosedQuotes++
                        continue
                    }
                    is KotlinLexToken.StringClose -> {
                        if (unclosedQuotes > 0) unclosedQuotes--
                        continue
                    }
                    is KotlinLexToken.StringText,
                    is KotlinLexToken.StringIdentTemplate,
                    is KotlinLexToken.TemplateExprOpen,
                    is KotlinLexToken.TemplateExprClose,
                    is KotlinLexToken.CharLiteral,
                    -> continue
                    else -> if (tok.interpolationDepth != 0) continue
                }
                if (tok is KotlinLexToken.Punct) {
                    if (tok.ch == '\'') return false
                    if (tok.ch == '(' || (trackAllBrackets && (tok.ch == '[' || tok.ch == '{'))) {
                        depth++
                    } else if (tok.ch == ')' || (trackAllBrackets && (tok.ch == ']' || tok.ch == '}'))) {
                        if (depth > 0) depth--
                    }
                }
                if (!onTopLevelToken(tok, depth)) return false
            }
            return depth == 0 && unclosedQuotes == 0
        }
    }
}

/**
 * Extensible token-to-source emitter over [KotlinLexToken] streams.
 * Subclasses override [emitAt] to transform or skip token spans and fall back
 * to emitting [KotlinLexToken.rawText].
 */
internal abstract class KotlinTokenRewriter(
    source: String,
    tokens: List<KotlinLexToken> = KotlinSourceTokenizer.tokenize(source),
) : KotlinTokenWalker(source, tokens) {
    /**
     * Emits replacement text for token(s) starting at [index] into [out] and returns
     * the next token index to visit.
     */
    protected open fun emitAt(index: Int, out: StringBuilder): Int {
        out.append(tokens[index].rawText)
        return index + 1
    }

    fun rewrite(): String {
        if (tokens.isEmpty()) return ""
        val out = StringBuilder(source.length)
        var idx = 0
        while (idx < tokens.size) {
            idx = emitAt(idx, out)
        }
        return out.toString()
    }
}
