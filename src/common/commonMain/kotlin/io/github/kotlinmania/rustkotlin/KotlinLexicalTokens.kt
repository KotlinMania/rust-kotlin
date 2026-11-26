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

        fun tryConsumeStringTemplate(activeStringMode: Char, tokStart: Int, tokLine: Int, tokCol: Int, depth: Int): Boolean {
            if (source[i] != '$' || i + 1 >= n) return false
            if (source[i + 1] == '{') {
                val j = i + 2
                advanceTo(j)
                interpModes.add(activeStringMode)
                interpBraces.add(1)
                mode = 'C'
                out += KotlinLexToken.TemplateExprOpen("\${", tokStart, j, tokLine, tokCol, depth)
                return true
            }
            if (isKotlinIdentStart(source[i + 1])) {
                var j = i + 2
                while (j < n && isKotlinIdentPart(source[j])) j++
                val raw = source.substring(tokStart, j)
                val ident = source.substring(tokStart + 1, j)
                advanceTo(j)
                out += KotlinLexToken.StringIdentTemplate(raw, ident, tokStart, j, tokLine, tokCol, depth)
                return true
            }
            return false
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
                    when {
                        source[i] == '"' -> {
                            advanceTo(i + 1)
                            mode = 'C'
                            out += KotlinLexToken.StringClose("\"", tokStart, tokStart + 1, tokLine, tokCol, depth)
                        }
                        tryConsumeStringTemplate('S', tokStart, tokLine, tokCol, depth) -> {}
                        else -> {
                            var j = i
                            while (j < n) {
                                val c = source[j]
                                if (c == '\\' && j + 1 < n) {
                                    j += 2
                                } else if (c == '"' || (c == '$' && j + 1 < n && (source[j + 1] == '{' || isKotlinIdentStart(source[j + 1])))) {
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
                    when {
                        source[i] == '"' && i + 2 < n && source[i + 1] == '"' && source[i + 2] == '"' &&
                            (i + 3 >= n || source[i + 3] != '"') -> {
                            val j = i + 3
                            advanceTo(j)
                            mode = 'C'
                            out += KotlinLexToken.StringClose("\"\"\"", tokStart, tokStart + 1, tokLine, tokCol, depth)
                        }
                        tryConsumeStringTemplate('R', tokStart, tokLine, tokCol, depth) -> {}
                        else -> {
                            var j = i
                            while (j < n) {
                                val c = source[j]
                                val isRawClose =
                                    c == '"' && j + 2 < n && source[j + 1] == '"' && source[j + 2] == '"' &&
                                        (j + 3 >= n || source[j + 3] != '"')
                                if (isRawClose || (c == '$' && j + 1 < n && (source[j + 1] == '{' || isKotlinIdentStart(source[j + 1])))) {
                                    break
                                }
                                j++
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
 * type-argument `<...>`, dotted-identifier, identifier-collection, and top-level
 * comma-splitting primitives.
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

    fun isTriviaToken(tok: KotlinLexToken): Boolean =
        tok is KotlinLexToken.Whitespace ||
            tok is KotlinLexToken.Newline ||
            tok is KotlinLexToken.LineComment ||
            tok is KotlinLexToken.BlockComment

    fun nextNonTriviaIndex(startIdx: Int, limit: Int = tokens.size): Int {
        var k = startIdx
        while (k < limit && isTriviaToken(tokens[k])) {
            k++
        }
        return k
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

    fun anyIdentAt(idx: Int): String? =
        when (val tok = tokens.getOrNull(idx)) {
            is KotlinLexToken.Ident -> tok.rawText
            is KotlinLexToken.BacktickIdent -> tok.ident
            else -> null
        }

    fun anyPunctAt(idx: Int): Char? =
        (tokens.getOrNull(idx) as? KotlinLexToken.Punct)?.ch

    fun previousNonTriviaIndex(beforeIdx: Int): Int {
        var k = beforeIdx - 1
        while (k >= 0) {
            if (!isTriviaToken(tokens[k])) return k
            k--
        }
        return -1
    }

    fun readDottedIdentifier(startIdx: Int, limit: Int = tokens.size): Pair<String, Int>? {
        val first = identTextAt(startIdx) ?: return null
        val sb = StringBuilder(first)
        var cur = nextNonTriviaIndex(startIdx + 1, limit)
        while (punctAt(cur) == '.') {
            val segIdx = nextNonTriviaIndex(cur + 1, limit)
            val seg = identTextAt(segIdx) ?: break
            sb.append('.').append(seg)
            cur = nextNonTriviaIndex(segIdx + 1, limit)
        }
        return sb.toString() to cur
    }

    private fun findMatchingPunctPairToken(openTokenIdx: Int, openCh: Char, closeCh: Char): Int {
        val openTok = tokens.getOrNull(openTokenIdx) as? KotlinLexToken.Punct ?: return -1
        if (openTok.ch != openCh) return -1
        val baseInterp = openTok.interpolationDepth
        var depth = 0
        var k = openTokenIdx
        while (k < tokens.size) {
            val tok = tokens[k]
            if (tok.interpolationDepth == baseInterp && tok is KotlinLexToken.Punct) {
                when (tok.ch) {
                    openCh -> depth++
                    closeCh -> {
                        depth--
                        if (depth == 0) return k
                    }
                }
            }
            k++
        }
        return -1
    }

    fun findMatchingParenToken(openParenTokenIdx: Int): Int =
        findMatchingPunctPairToken(openParenTokenIdx, '(', ')')

    fun findMatchingBracketToken(openBracketTokenIdx: Int): Int =
        findMatchingPunctPairToken(openBracketTokenIdx, '[', ']')

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
        readDottedIdentifier(k, limit)?.let { (_, afterDotted) -> k = afterDotted }
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

    fun skipLeadingAnnotations(startIdx: Int = 0, limit: Int = tokens.size): Int {
        var idx = nextNonTriviaIndex(startIdx, limit)
        while (punctAt(idx) == '@') {
            idx = nextNonTriviaIndex(skipAnnotation(idx, limit), limit)
        }
        return idx
    }

    fun parsePackageHeader(): String? {
        val idx = skipLeadingAnnotations(0)
        if (identTextAt(idx) != "package") return null
        return readDottedIdentifier(nextNonTriviaIndex(idx + 1))?.first
    }

    fun isFollowedByEmptyParens(startTokenIdx: Int): Boolean {
        val openIdx = skipHorizontalWhitespace(startTokenIdx)
        val openTok = tokens.getOrNull(openIdx) as? KotlinLexToken.Punct ?: return false
        if (openTok.ch != '(') return false
        val closeIdx = skipAllWhitespace(openIdx + 1)
        val closeTok = tokens.getOrNull(closeIdx) as? KotlinLexToken.Punct ?: return false
        return closeTok.ch == ')'
    }

    fun isAdjacentComparisonOrEq(eqIdx: Int): Boolean {
        val next = tokens.getOrNull(eqIdx + 1)
        if (next is KotlinLexToken.Punct && next.ch == '=') return true
        val prev = tokens.getOrNull(eqIdx - 1)
        return prev != null && prev is KotlinLexToken.Punct && prev.ch in "=!<>?"
    }

    fun startsTopLevelItemAt(tokenIdx: Int): Boolean {
        val tok = tokens.getOrNull(tokenIdx) ?: return false
        return when (tok) {
            is KotlinLexToken.LineComment -> true
            is KotlinLexToken.BlockComment -> tok.rawText.startsWith("/**")
            is KotlinLexToken.Punct -> tok.ch == '@'
            is KotlinLexToken.Ident ->
                tok.rawText in TOP_LEVEL_ITEM_STARTERS &&
                    tokens.getOrNull(tokenIdx + 1) is KotlinLexToken.Whitespace
            else -> false
        }
    }

    fun findExpressionBodyEndToken(startTokenIdx: Int): Int {
        var k = startTokenIdx
        var parenDepth = 0
        var bracketDepth = 0
        var braceDepth = 0
        var seenExprToken = false
        while (k < tokens.size) {
            val tok = tokens[k]
            if (tok.interpolationDepth == 0) {
                when (tok) {
                    is KotlinLexToken.Whitespace,
                    is KotlinLexToken.LineComment,
                    is KotlinLexToken.BlockComment,
                    -> {}
                    is KotlinLexToken.Newline ->
                        if (seenExprToken && parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) {
                            val peek = skipHorizontalWhitespace(k + 1)
                            val peekTok = tokens.getOrNull(peek)
                            if (peekTok == null ||
                                peekTok is KotlinLexToken.Newline ||
                                (peekTok is KotlinLexToken.Punct && peekTok.ch == '}') ||
                                startsTopLevelItemAt(peek)
                            ) {
                                return k
                            }
                        }
                    is KotlinLexToken.Punct ->
                        when (tok.ch) {
                            '(' -> {
                                seenExprToken = true
                                parenDepth++
                            }
                            ')' -> if (parenDepth > 0) parenDepth--
                            '[' -> {
                                seenExprToken = true
                                bracketDepth++
                            }
                            ']' -> if (bracketDepth > 0) bracketDepth--
                            '{' -> {
                                seenExprToken = true
                                braceDepth++
                            }
                            '}' -> {
                                if (braceDepth == 0) return k
                                braceDepth--
                            }
                            else -> seenExprToken = true
                        }
                    else -> seenExprToken = true
                }
            }
            k++
        }
        return tokens.size
    }

    fun findFunctionDeclarationEndToken(startTokenIdx: Int): Int {
        var k = startTokenIdx
        var parenDepth = 0
        var angleDepth = 0
        while (k < tokens.size) {
            val tok = tokens[k]
            if (tok.interpolationDepth == 0) {
                when (tok) {
                    is KotlinLexToken.Punct ->
                        when (tok.ch) {
                            '(' -> parenDepth++
                            ')' -> if (parenDepth > 0) parenDepth--
                            '<' -> if (parenDepth == 0) angleDepth++
                            '>' -> if (parenDepth == 0 && angleDepth > 0) angleDepth--
                            '{' -> if (parenDepth == 0 && angleDepth == 0) return findMatchingBraceEndToken(k)
                            '=' ->
                                if (parenDepth == 0 && angleDepth == 0 && !isAdjacentComparisonOrEq(k)) {
                                    return findExpressionBodyEndToken(k + 1)
                                }
                        }
                    is KotlinLexToken.Newline ->
                        if (parenDepth == 0 && angleDepth == 0) {
                            val peek = skipHorizontalWhitespace(k + 1)
                            if (peek >= tokens.size || tokens[peek] is KotlinLexToken.Newline || startsTopLevelItemAt(peek)) {
                                return k
                            }
                        }
                    else -> {}
                }
            }
            k++
        }
        return tokens.size
    }

    fun collectFilteredIdentifiers(exclude: Set<String> = emptySet()): Set<String> {
        val out = linkedSetOf<String>()
        for (token in tokens) {
            val ident =
                when (token) {
                    is KotlinLexToken.Ident -> token.rawText
                    is KotlinLexToken.BacktickIdent -> token.ident
                    is KotlinLexToken.StringIdentTemplate -> token.ident
                    else -> continue
                }
            if (ident.isNotEmpty() && !ident.first().isDigit() && ident !in exclude) {
                out += ident
            }
        }
        return out
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

    fun containsNonMemberIdentifier(ident: String): Boolean {
        if (ident.isEmpty()) return false
        for (idx in tokens.indices) {
            when (val tok = tokens[idx]) {
                is KotlinLexToken.StringIdentTemplate -> if (tok.ident == ident) return true
                is KotlinLexToken.Ident, is KotlinLexToken.BacktickIdent -> {
                    if (anyIdentAt(idx) == ident) {
                        val prevIdx = previousNonTriviaIndex(idx)
                        if (anyPunctAt(prevIdx) != '.') return true
                    }
                }
                else -> {}
            }
        }
        return false
    }

    fun isSingleSimpleIdentifierOrThis(): Boolean {
        val first = nextNonTriviaIndex(0)
        if (first >= tokens.size) return false
        if (identTextAt(first) == null) return false
        return nextNonTriviaIndex(first + 1) >= tokens.size
    }

    fun declaresFunctionNamed(fnName: String): Boolean {
        var idx = 0
        while (idx < tokens.size) {
            if (identTextAt(idx) == "fun") {
                var cur = nextNonTriviaIndex(idx + 1)
                if (punctAt(cur) == '<') {
                    val closeAngle = findMatchingTypeAngleToken(cur)
                    if (closeAngle > cur) cur = nextNonTriviaIndex(closeAngle + 1)
                }
                var lastIdent: String? = null
                while (cur < tokens.size && punctAt(cur) != '(' && punctAt(cur) != '{' && punctAt(cur) != '=') {
                    identTextAt(cur)?.let { lastIdent = it }
                    cur =
                        if (punctAt(cur) == '<') {
                            val ca = findMatchingTypeAngleToken(cur)
                            if (ca > cur) nextNonTriviaIndex(ca + 1) else cur + 1
                        } else {
                            nextNonTriviaIndex(cur + 1)
                        }
                }
                if (lastIdent == fnName && punctAt(cur) == '(') return true
            }
            idx++
        }
        return false
    }

    fun parseSingleLineLocalDeclaration(): SingleLineLocalDecl? {
        if (source.isEmpty() || '\n' in source) return null
        val indent = (tokens.firstOrNull() as? KotlinLexToken.Whitespace)?.rawText.orEmpty()
        val kwIdx = if (indent.isNotEmpty()) 1 else 0
        val kw = identTextAt(kwIdx)
        if (kw != "val" && kw != "var") return null
        val nameIdx = skipHorizontalWhitespace(kwIdx + 1)
        val ident = (tokens.getOrNull(nameIdx) as? KotlinLexToken.Ident)?.rawText ?: return null
        val afterNameIdx = skipHorizontalWhitespace(nameIdx + 1)
        val eqIdx =
            when (punctAt(afterNameIdx)) {
                '=' -> {
                    if (punctAt(afterNameIdx + 1) == '=') return null
                    afterNameIdx
                }
                ':' -> {
                    var foundEq = -1
                    walkTopLevelTokens(afterNameIdx + 1, tokens.size, trackBraces = true) { k, _, isTop ->
                        if (isTop &&
                            punctAt(k) == '=' &&
                            punctAt(k + 1) != '=' &&
                            punctAt(k - 1)?.let { it in "=!<>+-*/%" } != true
                        ) {
                            foundEq = k
                            false
                        } else {
                            true
                        }
                    }
                    if (foundEq < 0) return null
                    foundEq
                }
                else -> return null
            }
        val typeClause =
            if (punctAt(afterNameIdx) == ':') {
                source.substring(tokens[nameIdx].end, tokens[eqIdx].start).trimEnd()
            } else {
                ""
            }
        val rhs = source.substring(tokens[eqIdx].end).trim()
        if (rhs.isEmpty()) return null
        return SingleLineLocalDecl(indent, kw, ident, typeClause, rhs)
    }

    fun isIdentifierMutated(targetIdent: String): Boolean {
        if (targetIdent.isEmpty()) return false
        for (idx in tokens.indices) {
            if (anyIdentAt(idx) != targetIdent) continue
            val prevIdx = previousNonTriviaIndex(idx)
            if (anyPunctAt(prevIdx) == '.') continue
            if (prevIdx >= 1) {
                val p1 = anyPunctAt(prevIdx)
                if ((p1 == '+' || p1 == '-') &&
                    anyPunctAt(prevIdx - 1) == p1 &&
                    tokens[prevIdx - 1].end == tokens[prevIdx].start
                ) {
                    return true
                }
            }
            val nextIdx = nextNonTriviaIndex(idx + 1)
            val n1 = anyPunctAt(nextIdx) ?: continue
            val n2 = anyPunctAt(nextIdx + 1)
            val isAdjacent = nextIdx + 1 < tokens.size && tokens[nextIdx].end == tokens[nextIdx + 1].start
            if (n1 == '=' && !(isAdjacent && (n2 == '=' || n2 == '>'))) return true
            if (n1 in "+-*/%" && isAdjacent && n2 == '=') return true
            if ((n1 == '+' || n1 == '-') && isAdjacent && n2 == n1) return true
        }
        return false
    }

    fun previousNonTriviaToken(beforeIdx: Int): KotlinLexToken? =
        previousNonTriviaIndex(beforeIdx).takeIf { it >= 0 }?.let(tokens::get)

    fun findMatchingBraceEndToken(openTokenIdx: Int): Int {
        val closeIdx = findMatchingPunctPairToken(openTokenIdx, '{', '}')
        return if (closeIdx >= 0) closeIdx + 1 else tokens.size
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

    fun splitTopLevelCommaTokenRanges(startIdx: Int, endExclusive: Int): List<IntRange> {
        val clauses = mutableListOf<IntRange>()
        var clauseStart = startIdx
        walkTopLevelTokens(startIdx, endExclusive, trackBraces = true) { idx, _, isAtTopLevel ->
            if (isAtTopLevel && punctAt(idx) == ',') {
                if (clauseStart < idx) {
                    clauses += clauseStart until idx
                }
                clauseStart = idx + 1
            }
            true
        }
        if (clauseStart < endExclusive) {
            clauses += clauseStart until endExclusive
        }
        return clauses
    }

    inline fun walkTopLevelTokens(
        startIdx: Int,
        endExclusive: Int = tokens.size,
        trackBraces: Boolean = false,
        onToken: (idx: Int, tok: KotlinLexToken, isAtTopLevel: Boolean) -> Boolean,
    ) {
        val limit = minOf(endExclusive, tokens.size)
        var angleDepth = 0
        var parenDepth = 0
        var bracketDepth = 0
        var braceDepth = 0
        for (idx in startIdx until limit) {
            val tok = tokens[idx]
            val atTop = angleDepth == 0 && parenDepth == 0 && bracketDepth == 0 && (!trackBraces || braceDepth == 0)
            if (!onToken(idx, tok, atTop)) return
            if (tok.interpolationDepth == 0 && tok is KotlinLexToken.Punct) {
                when (tok.ch) {
                    '<' -> angleDepth++
                    '>' -> if (angleDepth > 0) angleDepth--
                    '(' -> parenDepth++
                    ')' -> if (parenDepth > 0) parenDepth--
                    '[' -> bracketDepth++
                    ']' -> if (bracketDepth > 0) bracketDepth--
                    '{' -> if (trackBraces) braceDepth++
                    '}' -> if (trackBraces && braceDepth > 0) braceDepth--
                }
            }
        }
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

    inline fun <R : Any> firstGenericApplication(
        selector: (ownerSimpleIdent: String, typeArgs: List<String>) -> R?,
    ): R? {
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
                    selector(ownerIdent, topArgs)?.let { return it }
                }
            }
            idx++
        }
        return null
    }

    inline fun anyGenericApplication(
        predicate: (ownerSimpleIdent: String, typeArgs: List<String>) -> Boolean,
    ): Boolean = firstGenericApplication { owner, args -> true.takeIf { predicate(owner, args) } } != null

    companion object {
        fun splitTopLevelCommaList(segment: String): List<String> {
            if (segment.isBlank()) return emptyList()
            val walker = KotlinTokenWalker(segment)
            return walker
                .splitTopLevelCommaTokenRanges(0, walker.tokens.size)
                .map { range -> segment.substring(walker.tokens[range.first].start, walker.tokens[range.last].end) }
                .filter { it.isNotBlank() }
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

        fun hasOuterBalancedParens(text: String): Boolean {
            if (text.length < 2 || text.first() != '(' || text.last() != ')') return false
            return scanTopLevelTokens(text, trackAllBrackets = false) { tok, depth ->
                !(tok is KotlinLexToken.Punct && tok.ch == ')' && depth == 0 && tok.end < text.length)
            }
        }

        fun hasNoTopLevelSpacesOrColons(text: String): Boolean =
            scanTopLevelTokens(text, trackAllBrackets = true) { tok, depth ->
                !(
                    depth == 0 &&
                        (
                            tok is KotlinLexToken.Whitespace ||
                                tok is KotlinLexToken.Newline ||
                                (tok is KotlinLexToken.Punct && tok.ch == ':')
                        )
                )
            }

        fun hasTopLevelBinaryOperator(text: String): Boolean =
            !scanTopLevelTokens(text, trackAllBrackets = true) { tok, depth ->
                if (depth != 0) {
                    true
                } else {
                    when (tok) {
                        is KotlinLexToken.Whitespace, is KotlinLexToken.Newline, is KotlinLexToken.Arrow -> false
                        is KotlinLexToken.Punct -> tok.start == 0 || tok.ch !in "+-*/%&|^=<>"
                        else -> true
                    }
                }
            }

        private val TOP_LEVEL_ITEM_STARTERS: Set<String> =
            wordSet("public internal private protected override fun val var class interface object typealias")
    }
}

/**
 * Reusable declaration-scope walker over [KotlinLexToken] streams that tracks top-level statement
 * starts across newlines, `;`, `(...)`, `[...]`, and skipped `{...}` bodies.
 */
internal abstract class KotlinDeclarationScopeWalker(
    sourceText: String,
) : KotlinTokenWalker(sourceText) {
    protected fun walkDeclarationStatements(
        startIdx: Int,
        endExclusive: Int,
        onStatementStart: (declStartIdx: Int) -> Int?,
    ) {
        var idx = startIdx
        var atStatementStart = true
        var parenDepth = 0
        var bracketDepth = 0
        while (idx < endExclusive) {
            val tok = tokens[idx]
            if (tok is KotlinLexToken.Whitespace || tok is KotlinLexToken.LineComment || tok is KotlinLexToken.BlockComment) {
                idx++
                continue
            }
            if (tok is KotlinLexToken.Newline) {
                if (parenDepth == 0 && bracketDepth == 0) atStatementStart = true
                idx++
                continue
            }
            if (tok.interpolationDepth == 0 && tok is KotlinLexToken.Punct) {
                when (tok.ch) {
                    '{' ->
                        if (parenDepth == 0 && bracketDepth == 0) {
                            idx = findMatchingBraceEndToken(idx)
                            atStatementStart = true
                            continue
                        }
                    '(' -> parenDepth++
                    ')' -> if (parenDepth > 0) parenDepth--
                    '[' -> bracketDepth++
                    ']' -> if (bracketDepth > 0) bracketDepth--
                    ';' ->
                        if (parenDepth == 0 && bracketDepth == 0) {
                            atStatementStart = true
                            idx++
                            continue
                        }
                }
            }
            if (atStatementStart && parenDepth == 0 && bracketDepth == 0) {
                atStatementStart = false
                val prev = previousNonTriviaToken(idx)
                if (!(prev is KotlinLexToken.Punct && prev.ch == '=')) {
                    val jumpTo = onStatementStart(idx)
                    if (jumpTo != null) {
                        idx = jumpTo
                        atStatementStart = true
                        continue
                    }
                }
            }
            idx++
        }
    }
}

/**
 * Token-parsed structural view of a rendered Kotlin type expression (`pkg.Owner<A, List<B>>?` or
 * `((Int) -> String)?`), eliminating fragile `substringBefore('<')` / `substringAfter('<')` slicing.
 */
internal data class KotlinRenderedType(
    val rawTrimmed: String,
    val nonNullText: String,
    val headPath: String,
    val simpleName: String,
    val isNullable: Boolean,
    val typeArgs: List<String>,
    val callableReturnType: String?,
) {
    val hasTypeArgs: Boolean
        get() = typeArgs.isNotEmpty()

    val arity: Int
        get() = typeArgs.size

    val singleTypeArg: String?
        get() = typeArgs.singleOrNull()

    val pairTypeArgs: Pair<String, String>?
        get() = if (typeArgs.size == 2) typeArgs[0] to typeArgs[1] else null

    fun matchesSimple(vararg names: String): Boolean = simpleName in names

    fun singleArgOf(vararg names: String): String? =
        if (simpleName in names) typeArgs.singleOrNull() else null

    fun pairArgsOf(vararg names: String): Pair<String, String>? =
        if (simpleName in names && typeArgs.size == 2) typeArgs[0] to typeArgs[1] else null

    fun isStarProjectedOrPlain(): Boolean =
        typeArgs.isEmpty() || typeArgs.all { it == "*" }

    fun withStarProjection(targetArity: Int = typeArgs.size): String =
        if (targetArity <= 0) headPath else "$headPath<${List(targetArity) { "*" }.joinToString(", ")}>"

    companion object {
        private val FN_TRAIT_NAMES: Set<String> = wordSet("Fn FnMut FnOnce")

        fun parse(raw: String?): KotlinRenderedType? {
            val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if ('<' !in trimmed && '(' !in trimmed && '`' !in trimmed) {
                val isNullable = trimmed.endsWith('?')
                val nonNull = if (isNullable) trimmed.dropLast(1).trimEnd() else trimmed
                if (nonNull.isEmpty()) return null
                val simple = nonNull.substringAfterLast('.').trim()
                return KotlinRenderedType(
                    rawTrimmed = trimmed,
                    nonNullText = nonNull,
                    headPath = nonNull,
                    simpleName = simple,
                    isNullable = isNullable,
                    typeArgs = emptyList(),
                    callableReturnType = null,
                )
            }
            val walker = KotlinTokenWalker(trimmed)
            val firstIdx = walker.nextNonTriviaIndex(0)
            val lastIdx = walker.previousNonTriviaIndex(walker.tokens.size)
            if (firstIdx > lastIdx) return null
            val isNullable = walker.punctAt(lastIdx) == '?'
            val coreLastIdx = if (isNullable) walker.previousNonTriviaIndex(lastIdx) else lastIdx
            if (coreLastIdx < firstIdx) return null
            val coreEndExclusive = coreLastIdx + 1
            val nonNull = trimmed.substring(walker.tokens[firstIdx].start, walker.tokens[coreLastIdx].end).trim()

            if (walker.punctAt(firstIdx) == '(') {
                val closeParen = walker.findMatchingParenToken(firstIdx)
                if (closeParen == coreLastIdx) {
                    val innerText = trimmed.substring(walker.tokens[firstIdx].end, walker.tokens[closeParen].start).trim()
                    val innerParsed = parse(innerText) ?: return null
                    return innerParsed.copy(
                        rawTrimmed = trimmed,
                        isNullable = isNullable || innerParsed.isNullable,
                    )
                }
                if (closeParen > firstIdx) {
                    val arrowIdx = walker.nextNonTriviaIndex(closeParen + 1, coreEndExclusive)
                    if (walker.tokens.getOrNull(arrowIdx) is KotlinLexToken.Arrow) {
                        val retText = trimmed.substring(walker.tokens[arrowIdx].end, walker.tokens[coreLastIdx].end).trim()
                        return KotlinRenderedType(
                            rawTrimmed = trimmed,
                            nonNullText = nonNull,
                            headPath = nonNull,
                            simpleName = "",
                            isNullable = isNullable,
                            typeArgs = emptyList(),
                            callableReturnType = retText.ifEmpty { null },
                        )
                    }
                }
            }

            var idx = firstIdx
            var trailingOpenAngle = -1
            while (idx < coreEndExclusive) {
                if (walker.punctAt(idx) == '<') {
                    val closeAngle = walker.findMatchingTypeAngleToken(idx)
                    if (closeAngle == coreLastIdx) {
                        trailingOpenAngle = idx
                        break
                    }
                    if (closeAngle > idx) {
                        idx = closeAngle + 1
                        continue
                    }
                }
                idx++
            }
            if (trailingOpenAngle > firstIdx) {
                val headEndIdx = walker.previousNonTriviaIndex(trailingOpenAngle)
                val headPath = trimmed.substring(walker.tokens[firstIdx].start, walker.tokens[headEndIdx].end).trim()
                val simpleName = walker.anyIdentAt(headEndIdx) ?: headPath.substringAfterLast('.').trim()
                val typeArgs =
                    walker
                        .splitTopLevelCommaTokenRanges(trailingOpenAngle + 1, coreLastIdx)
                        .map { range -> trimmed.substring(walker.tokens[range.first].start, walker.tokens[range.last].end).trim() }
                        .filter { it.isNotEmpty() }
                val callableRet =
                    if (simpleName in FN_TRAIT_NAMES && typeArgs.size >= 2) {
                        typeArgs.drop(1).joinToString(", ").takeIf { it.isNotEmpty() }
                    } else {
                        null
                    }
                return KotlinRenderedType(
                    rawTrimmed = trimmed,
                    nonNullText = nonNull,
                    headPath = headPath,
                    simpleName = simpleName,
                    isNullable = isNullable,
                    typeArgs = typeArgs,
                    callableReturnType = callableRet,
                )
            }

            val simpleName = walker.anyIdentAt(coreLastIdx) ?: nonNull.substringBefore('<').substringAfterLast('.').trim()
            return KotlinRenderedType(
                rawTrimmed = trimmed,
                nonNullText = nonNull,
                headPath = nonNull,
                simpleName = simpleName,
                isNullable = isNullable,
                typeArgs = emptyList(),
                callableReturnType = null,
            )
        }
    }
}

internal fun String?.asKotlinRenderedType(): KotlinRenderedType? = KotlinRenderedType.parse(this)

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

internal data class SingleLineLocalDecl(
    val indent: String,
    val keyword: String,
    val ident: String,
    val typeClause: String,
    val rhs: String,
)

/**
 * Single-pass token index over emitted Kotlin file bodies so import synthesis and `pub use`
 * filtering answer whole-identifier, `.member(...)` call, `.inv()`, `Atomic*`, and tuple
 * destructuring queries in O(1) without Regexes or repeated full-file re-tokenization.
 */
internal class KotlinCodeTokenIndex(
    source: String,
) : KotlinTokenWalker(source) {
    val identifiers: Set<String>
    val dotCallMemberNames: Set<String>
    val maxTupleDestructuringArity: Int
    val hasZeroArgDotInvCall: Boolean
    val hasAtomicTypeIdent: Boolean

    init {
        val idents = HashSet<String>(maxOf(16, tokens.size / 4))
        val dotCalls = HashSet<String>()
        var maxTuple = 0
        var sawDotInv = false
        for (idx in tokens.indices) {
            when (val tok = tokens[idx]) {
                is KotlinLexToken.Ident -> idents += tok.rawText
                is KotlinLexToken.BacktickIdent -> idents += tok.ident
                is KotlinLexToken.StringIdentTemplate -> idents += tok.ident
                else -> {}
            }
            if (anyPunctAt(idx) == '.') {
                val memberIdx = nextNonTriviaIndex(idx + 1)
                val member = anyIdentAt(memberIdx)
                if (member != null) {
                    val afterMember = nextNonTriviaIndex(memberIdx + 1)
                    val openCh = anyPunctAt(afterMember)
                    if (openCh == '(' || openCh == '{' || openCh == '<') {
                        dotCalls += member
                        if (member == "inv" && openCh == '(' && anyPunctAt(nextNonTriviaIndex(afterMember + 1)) == ')') {
                            sawDotInv = true
                        }
                    }
                }
            }
            val kw = anyIdentAt(idx)
            if (kw == "val" || kw == "var") {
                val openParen = nextNonTriviaIndex(idx + 1)
                if (anyPunctAt(openParen) == '(') {
                    val closeParen = findMatchingParenAnyDepth(openParen)
                    if (closeParen > openParen) {
                        val afterClose = nextNonTriviaIndex(closeParen + 1)
                        if (anyPunctAt(afterClose) == '=' && anyPunctAt(afterClose + 1) != '=') {
                            val arity = countTopLevelCommasInRange(openParen + 1, closeParen) + 1
                            if (arity > maxTuple) maxTuple = arity
                        }
                    }
                }
            }
        }
        identifiers = idents
        dotCallMemberNames = dotCalls
        maxTupleDestructuringArity = maxTuple
        hasZeroArgDotInvCall = sawDotInv
        hasAtomicTypeIdent = idents.any { it in ATOMIC_TYPE_IDENT_NAMES }
    }

    fun hasIdent(ident: String): Boolean = ident in identifiers

    fun hasMemberCall(memberName: String): Boolean = memberName in dotCallMemberNames

    private fun findMatchingParenAnyDepth(openIdx: Int): Int {
        val baseInterp = tokens[openIdx].interpolationDepth
        var depth = 0
        for (k in openIdx until tokens.size) {
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
        }
        return -1
    }

    private fun countTopLevelCommasInRange(startIdx: Int, endExclusive: Int): Int {
        val baseInterp = tokens.getOrNull(startIdx)?.interpolationDepth ?: return 0
        var commas = 0
        var angleDepth = 0
        var parenDepth = 0
        for (k in startIdx until endExclusive) {
            val tok = tokens[k]
            if (tok.interpolationDepth != baseInterp || tok !is KotlinLexToken.Punct) continue
            when (tok.ch) {
                '<' -> angleDepth++
                '>' -> if (angleDepth > 0) angleDepth--
                '(' -> parenDepth++
                ')' -> if (parenDepth > 0) parenDepth--
                ',' -> if (angleDepth == 0 && parenDepth == 0) commas++
            }
        }
        return commas
    }

    private companion object {
        val ATOMIC_TYPE_IDENT_NAMES: Set<String> =
            setOf(
                "Atomic",
                "AtomicBool",
                "AtomicPtr",
                "AtomicI8",
                "AtomicU8",
                "AtomicI16",
                "AtomicU16",
                "AtomicI32",
                "AtomicU32",
                "AtomicI64",
                "AtomicU64",
                "AtomicI128",
                "AtomicU128",
                "AtomicIsize",
                "AtomicUsize",
            )
    }
}

internal class KotlinIdentifierTokenRewriter(
    source: String,
    private val replacements: Map<String, String>,
    private val skipMemberAccess: Boolean = true,
) : KotlinTokenRewriter(source) {
    override fun emitAt(index: Int, out: StringBuilder): Int {
        when (val tok = tokens[index]) {
            is KotlinLexToken.Ident -> {
                val mapped = replacements[tok.rawText]
                val afterDot = skipMemberAccess && anyPunctAt(previousNonTriviaIndex(index)) == '.'
                if (mapped != null && !afterDot) {
                    out.append(mapped)
                    return index + 1
                }
            }
            is KotlinLexToken.StringIdentTemplate -> {
                val mapped = replacements[tok.ident]
                if (mapped != null) {
                    out.append('$').append(mapped)
                    return index + 1
                }
            }
            else -> {}
        }
        out.append(tokens[index].rawText)
        return index + 1
    }
}
