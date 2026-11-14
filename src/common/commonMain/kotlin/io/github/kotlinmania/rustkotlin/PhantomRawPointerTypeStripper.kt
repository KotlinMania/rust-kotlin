package io.github.kotlinmania.rustkotlin

/**
 * Strips phantom raw-pointer type arguments (`ConstPtr<...>`, `MutPtr<...>`, `NonNull<...>`,
 * `Unique<...>`, `AtomicPtr<...>`, `constPtrLayout<...>`, `mutPtrLayout<...>`,
 * `constPtrFromRustCast<...>`, `mutPtrFromRustCast<...>`, `nullPtr<...>`, `nullMut<...>`,
 * and zero-arg `.cast<...>()` calls) at the Kotlin emission boundary after type/layout
 * inference has already used `<Pointee>` to thread concrete [TypeLayout] descriptors.
 *
 * Also prunes `fun <T>` type parameters that only existed inside stripped phantom pointer
 * type arguments and no longer appear anywhere in the function signature or body.
 */
internal object PhantomRawPointerTypeStripper {
    private val PHANTOM_RAW_POINTER_IDENTIFIERS: Set<String> =
        setOf(
            "ConstPtr",
            "MutPtr",
            "NonNull",
            "Unique",
            "AtomicPtr",
            "constPtrLayout",
            "mutPtrLayout",
            "constPtrFromRustCast",
            "mutPtrFromRustCast",
            "nullPtr",
            "nullMut",
        )

    fun strip(code: String): String {
        if ('<' !in code) return code
        val strippedIdents = linkedSetOf<String>()
        val pass1 = stripTypeArgumentSites(code, strippedIdents)
        if (strippedIdents.isEmpty() || "fun <" !in pass1) {
            return pass1
        }
        return pruneOrphanedFunctionTypeParameters(pass1, strippedIdents)
    }

    private fun stripTypeArgumentSites(
        code: String,
        strippedIdentsOut: MutableSet<String>,
    ): String {
        val sb = StringBuilder(code.length)
        var i = 0
        val n = code.length
        while (i < n) {
            val nextNonCode = skipCommentOrLiteral(code, i)
            if (nextNonCode > i) {
                sb.append(code, i, nextNonCode)
                i = nextNonCode
                continue
            }
            val ch = code[i]
            if (isIdentStart(ch)) {
                val identStart = i
                i++
                while (i < n && isIdentPart(code[i])) {
                    i++
                }
                val ident = code.substring(identStart, i)
                sb.append(ident)

                val precededByDot = previousNonWhitespaceChar(code, identStart) == '.'
                val canStripIdent =
                    ident in PHANTOM_RAW_POINTER_IDENTIFIERS ||
                        (ident == "cast" && precededByDot)
                if (canStripIdent) {
                    var wsEnd = i
                    while (wsEnd < n && (code[wsEnd] == ' ' || code[wsEnd] == '\t')) {
                        wsEnd++
                    }
                    if (wsEnd < n && code[wsEnd] == '<') {
                        val closeAngle = findMatchingTypeAngleBracket(code, wsEnd)
                        if (closeAngle > wsEnd) {
                            val shouldStrip =
                                if (ident == "cast") {
                                    isFollowedByEmptyParens(code, closeAngle + 1)
                                } else {
                                    true
                                }
                            if (shouldStrip) {
                                collectIdentifiersInRange(code, wsEnd + 1, closeAngle, strippedIdentsOut)
                                i = closeAngle + 1
                            }
                        }
                    }
                }
                continue
            }
            sb.append(ch)
            i++
        }
        return sb.toString()
    }

    private fun pruneOrphanedFunctionTypeParameters(
        code: String,
        strippedIdents: Set<String>,
    ): String {
        val sb = StringBuilder(code.length)
        var i = 0
        val n = code.length
        while (i < n) {
            val nextNonCode = skipCommentOrLiteral(code, i)
            if (nextNonCode > i) {
                sb.append(code, i, nextNonCode)
                i = nextNonCode
                continue
            }
            if (isIdentStart(code[i])) {
                val identStart = i
                var identEnd = i + 1
                while (identEnd < n && isIdentPart(code[identEnd])) {
                    identEnd++
                }
                val word = code.substring(identStart, identEnd)
                if (word == "fun") {
                    var ws = identEnd
                    while (ws < n && code[ws].isWhitespace()) {
                        ws++
                    }
                    if (ws < n && code[ws] == '<') {
                        val closeAngle = findMatchingTypeAngleBracket(code, ws)
                        if (closeAngle > ws) {
                            val declEnd = findFunctionDeclarationEnd(code, closeAngle + 1)
                            val bodyIdents = linkedSetOf<String>()
                            collectIdentifiersInRange(code, closeAngle + 1, declEnd, bodyIdents)
                            val rawTypeParams = splitTopLevelCommaList(code.substring(ws + 1, closeAngle))
                            val retainedParams =
                                rawTypeParams.filter { rawParam ->
                                    val paramName = extractTypeParamName(rawParam)
                                    if (paramName == null || paramName !in strippedIdents) {
                                        true
                                    } else {
                                        paramName in bodyIdents
                                    }
                                }
                            if (retainedParams.size != rawTypeParams.size) {
                                sb.append("fun")
                                if (retainedParams.isNotEmpty()) {
                                    sb.append(" <").append(retainedParams.joinToString(", ") { it.trim() }).append('>')
                                    i = closeAngle + 1
                                } else {
                                    var afterAngle = closeAngle + 1
                                    while (afterAngle < n && (code[afterAngle] == ' ' || code[afterAngle] == '\t')) {
                                        afterAngle++
                                    }
                                    sb.append(' ')
                                    i = afterAngle
                                }
                                continue
                            }
                        }
                    }
                }
                sb.append(word)
                i = identEnd
                continue
            }
            sb.append(code[i])
            i++
        }
        return sb.toString()
    }

    private fun findFunctionDeclarationEnd(
        code: String,
        start: Int,
    ): Int {
        var i = start
        val n = code.length
        var parenDepth = 0
        var angleDepth = 0
        while (i < n) {
            val nextNonCode = skipCommentOrLiteral(code, i)
            if (nextNonCode > i) {
                i = nextNonCode
                continue
            }
            val ch = code[i]
            when (ch) {
                '(' -> parenDepth++
                ')' -> if (parenDepth > 0) parenDepth--
                '<' -> if (parenDepth == 0) angleDepth++
                '>' -> if (parenDepth == 0 && angleDepth > 0 && (i == 0 || code[i - 1] != '-')) angleDepth--
                '{' ->
                    if (parenDepth == 0 && angleDepth == 0) {
                        return findMatchingBrace(code, i)
                    }
                '=' ->
                    if (parenDepth == 0 &&
                        angleDepth == 0 &&
                        (i + 1 >= n || code[i + 1] != '=') &&
                        (i == 0 || code[i - 1] !in "=!<>")
                    ) {
                        return findExpressionBodyEnd(code, i + 1)
                    }
                '\n' ->
                    if (parenDepth == 0 && angleDepth == 0) {
                        var peek = i + 1
                        while (peek < n && (code[peek] == ' ' || code[peek] == '\t' || code[peek] == '\r')) {
                            peek++
                        }
                        if (peek >= n || code[peek] == '\n' || startsTopLevelItem(code, peek)) {
                            return i
                        }
                    }
            }
            i++
        }
        return n
    }

    private fun findMatchingBrace(
        code: String,
        openBraceIndex: Int,
    ): Int {
        var i = openBraceIndex
        val n = code.length
        var depth = 0
        while (i < n) {
            val nextNonCode = skipCommentOrLiteral(code, i)
            if (nextNonCode > i) {
                i = nextNonCode
                continue
            }
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i + 1
                }
            }
            i++
        }
        return n
    }

    private fun findExpressionBodyEnd(
        code: String,
        start: Int,
    ): Int {
        var i = start
        val n = code.length
        var parenDepth = 0
        var bracketDepth = 0
        var braceDepth = 0
        var seenExprToken = false
        while (i < n) {
            val nextNonCode = skipCommentOrLiteral(code, i)
            if (nextNonCode > i) {
                if (code[i] != '/') {
                    seenExprToken = true
                }
                i = nextNonCode
                continue
            }
            val ch = code[i]
            when (ch) {
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
                    if (braceDepth == 0) return i
                    braceDepth--
                }
                '\n' ->
                    if (seenExprToken && parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) {
                        var peek = i + 1
                        while (peek < n && (code[peek] == ' ' || code[peek] == '\t' || code[peek] == '\r')) {
                            peek++
                        }
                        if (peek >= n || code[peek] == '\n' || code[peek] == '}' || startsTopLevelItem(code, peek)) {
                            return i
                        }
                    }
                else -> if (!ch.isWhitespace()) seenExprToken = true
            }
            i++
        }
        return n
    }

    private fun startsTopLevelItem(
        code: String,
        index: Int,
    ): Boolean {
        val prefixes =
            listOf(
                "public ",
                "internal ",
                "private ",
                "protected ",
                "override ",
                "fun ",
                "val ",
                "var ",
                "class ",
                "interface ",
                "object ",
                "typealias ",
                "@",
                "/**",
                "//",
            )
        return prefixes.any { code.startsWith(it, index) }
    }

    internal fun splitTopLevelCommaList(segment: String): List<String> {
        val parts = mutableListOf<String>()
        var start = 0
        var i = 0
        var angleDepth = 0
        var parenDepth = 0
        while (i < segment.length) {
            when (segment[i]) {
                '<' -> angleDepth++
                '>' -> if (angleDepth > 0 && (i == 0 || segment[i - 1] != '-')) angleDepth--
                '(' -> parenDepth++
                ')' -> if (parenDepth > 0) parenDepth--
                ',' ->
                    if (angleDepth == 0 && parenDepth == 0) {
                        parts += segment.substring(start, i)
                        start = i + 1
                    }
            }
            i++
        }
        parts += segment.substring(start)
        return parts.filter { it.isNotBlank() }
    }

    private fun extractTypeParamName(rawParam: String): String? {
        val head = rawParam.substringBefore(':').trim()
        val tokens = head.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val candidate = tokens.lastOrNull { it != "in" && it != "out" && it != "reified" } ?: return null
        return candidate.takeIf { it.isNotEmpty() && isIdentStart(it[0]) && it.all(::isIdentPart) }
    }

    private fun isFollowedByEmptyParens(
        code: String,
        start: Int,
    ): Boolean {
        var i = start
        val n = code.length
        while (i < n && (code[i] == ' ' || code[i] == '\t')) {
            i++
        }
        if (i >= n || code[i] != '(') return false
        i++
        while (i < n && code[i].isWhitespace()) {
            i++
        }
        return i < n && code[i] == ')'
    }

    private fun previousNonWhitespaceChar(
        code: String,
        beforeIndex: Int,
    ): Char? {
        var i = beforeIndex - 1
        while (i >= 0 && code[i].isWhitespace()) {
            i--
        }
        return if (i >= 0) code[i] else null
    }

    private fun findMatchingTypeAngleBracket(
        code: String,
        openAngleIndex: Int,
    ): Int {
        var i = openAngleIndex
        val n = code.length
        var angleDepth = 0
        var parenDepth = 0
        while (i < n) {
            val nextNonCode = skipCommentOrLiteral(code, i)
            if (nextNonCode > i) {
                i = nextNonCode
                continue
            }
            val ch = code[i]
            when (ch) {
                '<' -> angleDepth++
                '>' -> {
                    if (i > 0 && code[i - 1] == '-') {
                        i++
                        continue
                    }
                    angleDepth--
                    if (angleDepth == 0 && parenDepth == 0) {
                        return i
                    }
                    if (angleDepth < 0) return -1
                }
                '(' -> parenDepth++
                ')' -> {
                    if (parenDepth == 0) return -1
                    parenDepth--
                }
                '{', '}', ';', '=' -> return -1
            }
            i++
        }
        return -1
    }

    private fun collectIdentifiersInRange(
        code: String,
        start: Int,
        endExclusive: Int,
        out: MutableSet<String>,
    ) {
        var i = start
        val limit = minOf(endExclusive, code.length)
        while (i < limit) {
            val nextNonCode = skipCommentOrLiteral(code, i)
            if (nextNonCode > i) {
                i = nextNonCode
                continue
            }
            if (isIdentStart(code[i])) {
                val s = i
                i++
                while (i < limit && isIdentPart(code[i])) {
                    i++
                }
                out += code.substring(s, i)
            } else {
                i++
            }
        }
    }

    private fun skipCommentOrLiteral(
        code: String,
        index: Int,
    ): Int {
        val n = code.length
        if (index >= n) return index
        if (code.startsWith("//", index)) {
            val nl = code.indexOf('\n', index + 2)
            return if (nl < 0) n else nl + 1
        }
        if (code.startsWith("/*", index)) {
            var i = index + 2
            var depth = 1
            while (i < n && depth > 0) {
                if (code.startsWith("/*", i)) {
                    depth++
                    i += 2
                } else if (code.startsWith("*/", i)) {
                    depth--
                    i += 2
                } else {
                    i++
                }
            }
            return i
        }
        if (code.startsWith("\"\"\"", index)) {
            var i = index + 3
            while (i < n) {
                if (code.startsWith("\"\"\"", i)) {
                    i += 3
                    while (i < n && code[i] == '"') {
                        i++
                    }
                    return i
                }
                i++
            }
            return n
        }
        if (code[index] == '"') {
            var i = index + 1
            while (i < n) {
                when (code[i]) {
                    '\\' -> i += 2
                    '"' -> return i + 1
                    else -> i++
                }
            }
            return n
        }
        if (code[index] == '\'') {
            var i = index + 1
            while (i < n) {
                when (code[i]) {
                    '\\' -> i += 2
                    '\'' -> return i + 1
                    '\n' -> return index
                    else -> i++
                }
            }
            return n
        }
        if (code[index] == '`') {
            val end = code.indexOf('`', index + 1)
            return if (end < 0) n else end + 1
        }
        return index
    }

    private fun isIdentStart(ch: Char): Boolean = ch == '_' || ch.isLetter()

    private fun isIdentPart(ch: Char): Boolean = ch == '_' || ch.isLetterOrDigit()
}
