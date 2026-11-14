package io.github.kotlinmania.rustkotlin

/**
 * Lexical gate enforcing KotlinMania's identifier invariant:
 * no emitted Kotlin identifier may contain an underscore (`_`) unless it is
 * Kotlin's single-character wildcard keyword (`_`) or strict `SCREAMING_SNAKE_CASE`
 * (`^[A-Z][A-Z0-9]*(_[A-Z0-9]+)*$`, with no leading/trailing `_` and no `__`).
 *
 * Comments, character literals, numeric underscores (`0xFFFF_FFFFuL`), and raw text
 * inside string literals are skipped; string template expressions (`$ident` and
 * `${...}`) and backtick-escaped identifiers (`` `foo_bar` ``) are scanned.
 */
public object ZeroUnderscoreIdentifierValidator {
    public data class Violation(
        public val identifier: String,
        public val line: Int,
        public val column: Int,
    ) {
        override fun toString(): String = "line $line:$column '$identifier'"
    }

    public fun isValidScreamingSnakeCase(ident: String): Boolean {
        if (ident.isEmpty() || ident.startsWith('_') || ident.endsWith('_') || "__" in ident) {
            return false
        }
        if (ident[0] !in 'A'..'Z') {
            return false
        }
        var hasLetter = false
        for (ch in ident) {
            when {
                ch in 'A'..'Z' -> hasLetter = true
                ch in '0'..'9' || ch == '_' -> {}
                else -> return false
            }
        }
        return hasLetter
    }

    public fun isAllowedIdentifier(ident: String): Boolean {
        if ('_' !in ident) return true
        if (ident == "_") return true
        return isValidScreamingSnakeCase(ident)
    }

    public fun findViolations(kotlinSource: String): List<Violation> {
        val violations = mutableListOf<Violation>()
        scanRange(kotlinSource, 0, kotlinSource.length, 1, 1, violations)
        return violations
    }

    public fun requireZeroViolations(kotlinSource: String, fileLabel: String = "<emitted Kotlin>") {
        val violations = findViolations(kotlinSource)
        require(violations.isEmpty()) {
            val preview = violations.take(15).joinToString(", ")
            val extra = if (violations.size > 15) " (+${violations.size - 15} more)" else ""
            "Forbidden underscore Kotlin identifier(s) in $fileLabel: $preview$extra"
        }
    }

    private fun scanRange(
        src: String,
        startIdx: Int,
        endIdx: Int,
        initialLine: Int,
        initialCol: Int,
        out: MutableList<Violation>,
    ) {
        var i = startIdx
        var line = initialLine
        var col = initialCol

        fun advance(count: Int = 1) {
            repeat(count) {
                if (i < endIdx) {
                    if (src[i] == '\n') {
                        line++
                        col = 1
                    } else {
                        col++
                    }
                    i++
                }
            }
        }

        while (i < endIdx) {
            val ch = src[i]

            // 1. Line comment //
            if (ch == '/' && i + 1 < endIdx && src[i + 1] == '/') {
                while (i < endIdx && src[i] != '\n') {
                    advance()
                }
                continue
            }

            // 2. Nested block comment /* ... */
            if (ch == '/' && i + 1 < endIdx && src[i + 1] == '*') {
                advance(2)
                var depth = 1
                while (i < endIdx && depth > 0) {
                    if (src[i] == '/' && i + 1 < endIdx && src[i + 1] == '*') {
                        depth++
                        advance(2)
                    } else if (src[i] == '*' && i + 1 < endIdx && src[i + 1] == '/') {
                        depth--
                        advance(2)
                    } else {
                        advance()
                    }
                }
                continue
            }

            // 3. Triple-quoted raw string """ ... """
            if (ch == '"' && i + 2 < endIdx && src[i + 1] == '"' && src[i + 2] == '"') {
                advance(3)
                while (i < endIdx) {
                    if (src[i] == '"' && i + 2 < endIdx && src[i + 1] == '"' && src[i + 2] == '"') {
                        // Consume trailing quotes if >3 quotes at closing delimiter
                        advance(3)
                        while (i < endIdx && src[i] == '"') {
                            advance()
                        }
                        break
                    }
                    if (src[i] == '$') {
                        if (i + 1 < endIdx && src[i + 1] == '{') {
                            advance(2)
                            val exprStart = i
                            val exprLine = line
                            val exprCol = col
                            val exprEnd = findMatchingBrace(src, i, endIdx)
                            scanRange(src, exprStart, exprEnd, exprLine, exprCol, out)
                            while (i < exprEnd) advance()
                            if (i < endIdx && src[i] == '}') advance()
                            continue
                        } else if (i + 1 < endIdx && isIdentStart(src[i + 1])) {
                            advance()
                            val tokLine = line
                            val tokCol = col
                            val tokStart = i
                            while (i < endIdx && isIdentPart(src[i])) {
                                advance()
                            }
                            val ident = src.substring(tokStart, i)
                            if (!isAllowedIdentifier(ident)) {
                                out += Violation(ident, tokLine, tokCol)
                            }
                            continue
                        }
                    }
                    advance()
                }
                continue
            }

            // 4. Double-quoted string "..."
            if (ch == '"') {
                advance()
                while (i < endIdx && src[i] != '"') {
                    if (src[i] == '\\') {
                        advance(2)
                        continue
                    }
                    if (src[i] == '$') {
                        if (i + 1 < endIdx && src[i + 1] == '{') {
                            advance(2)
                            val exprStart = i
                            val exprLine = line
                            val exprCol = col
                            val exprEnd = findMatchingBrace(src, i, endIdx)
                            scanRange(src, exprStart, exprEnd, exprLine, exprCol, out)
                            while (i < exprEnd) advance()
                            if (i < endIdx && src[i] == '}') advance()
                            continue
                        } else if (i + 1 < endIdx && isIdentStart(src[i + 1])) {
                            advance()
                            val tokLine = line
                            val tokCol = col
                            val tokStart = i
                            while (i < endIdx && isIdentPart(src[i])) {
                                advance()
                            }
                            val ident = src.substring(tokStart, i)
                            if (!isAllowedIdentifier(ident)) {
                                out += Violation(ident, tokLine, tokCol)
                            }
                            continue
                        }
                    }
                    advance()
                }
                if (i < endIdx && src[i] == '"') advance()
                continue
            }

            // 5. Character literal '...'
            if (ch == '\'') {
                advance()
                while (i < endIdx && src[i] != '\'' && src[i] != '\n') {
                    if (src[i] == '\\') {
                        advance(2)
                    } else {
                        advance()
                    }
                }
                if (i < endIdx && src[i] == '\'') advance()
                continue
            }

            // 6. Backticked identifier `...`
            if (ch == '`') {
                val tokLine = line
                val tokCol = col
                advance()
                val tokStart = i
                while (i < endIdx && src[i] != '`' && src[i] != '\n') {
                    advance()
                }
                val ident = src.substring(tokStart, i)
                if (i < endIdx && src[i] == '`') advance()
                if (!isAllowedIdentifier(ident)) {
                    out += Violation(ident, tokLine, tokCol)
                }
                continue
            }

            // 7. Numeric literal (including hex 0xFFFF_FFFFuL, binary 0b1010_0101, floats 1_000.5e-2f)
            if (ch in '0'..'9') {
                advance()
                while (i < endIdx) {
                    val c = src[i]
                    if ((c == 'e' || c == 'E') && i + 1 < endIdx && (src[i + 1] == '+' || src[i + 1] == '-')) {
                        advance(2)
                    } else if (c == '.' && i + 1 < endIdx && src[i + 1] in '0'..'9') {
                        advance(2)
                    } else if (c.isLetterOrDigit() || c == '_') {
                        advance()
                    } else {
                        break
                    }
                }
                continue
            }

            // 8. Identifier
            if (isIdentStart(ch)) {
                val tokLine = line
                val tokCol = col
                val tokStart = i
                while (i < endIdx && isIdentPart(src[i])) {
                    advance()
                }
                val ident = src.substring(tokStart, i)
                if (!isAllowedIdentifier(ident)) {
                    out += Violation(ident, tokLine, tokCol)
                }
                continue
            }

            advance()
        }
    }

    private fun findMatchingBrace(src: String, startIdx: Int, endIdx: Int): Int {
        var i = startIdx
        var depth = 1
        while (i < endIdx && depth > 0) {
            val c = src[i]
            when {
                c == '/' && i + 1 < endIdx && src[i + 1] == '/' -> {
                    i += 2
                    while (i < endIdx && src[i] != '\n') i++
                }
                c == '/' && i + 1 < endIdx && src[i + 1] == '*' -> {
                    i += 2
                    var cDepth = 1
                    while (i < endIdx && cDepth > 0) {
                        if (src[i] == '/' && i + 1 < endIdx && src[i + 1] == '*') {
                            cDepth++
                            i += 2
                        } else if (src[i] == '*' && i + 1 < endIdx && src[i + 1] == '/') {
                            cDepth--
                            i += 2
                        } else {
                            i++
                        }
                    }
                }
                c == '"' && i + 2 < endIdx && src[i + 1] == '"' && src[i + 2] == '"' -> {
                    i += 3
                    while (i < endIdx) {
                        if (src[i] == '"' && i + 2 < endIdx && src[i + 1] == '"' && src[i + 2] == '"') {
                            i += 3
                            break
                        }
                        i++
                    }
                }
                c == '"' -> {
                    i++
                    while (i < endIdx && src[i] != '"') {
                        i += if (src[i] == '\\') 2 else 1
                    }
                    if (i < endIdx) i++
                }
                c == '\'' -> {
                    i++
                    while (i < endIdx && src[i] != '\'' && src[i] != '\n') {
                        i += if (src[i] == '\\') 2 else 1
                    }
                    if (i < endIdx) i++
                }
                c == '{' -> {
                    depth++
                    i++
                }
                c == '}' -> {
                    depth--
                    if (depth == 0) return i
                    i++
                }
                else -> i++
            }
        }
        return i
    }

    private fun isIdentStart(ch: Char): Boolean = ch == '_' || ch.isLetter()

    private fun isIdentPart(ch: Char): Boolean = ch == '_' || ch.isLetterOrDigit()
}
