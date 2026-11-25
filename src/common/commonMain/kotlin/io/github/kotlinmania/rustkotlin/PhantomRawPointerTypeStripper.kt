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
        wordSet(
            "ConstPtr MutPtr NonNull Unique AtomicPtr constPtrLayout mutPtrLayout",
            "constPtrFromRustCast mutPtrFromRustCast nullPtr nullMut",
        )

    private val TOP_LEVEL_ITEM_STARTERS: Set<String> =
        wordSet("public internal private protected override fun val var class interface object typealias")

    fun strip(code: String): String {
        if ('<' !in code) return code
        val strippedIdents = linkedSetOf<String>()
        val pass1 = TypeArgumentSiteRewriter(code, strippedIdents).rewrite()
        if (strippedIdents.isEmpty() || "fun <" !in pass1) {
            return pass1
        }
        return OrphanedFunTypeParamRewriter(pass1, strippedIdents).rewrite()
    }

    internal fun splitTopLevelCommaList(segment: String): List<String> =
        KotlinTokenWalker.splitTopLevelCommaList(segment)

    private class TypeArgumentSiteRewriter(
        code: String,
        private val strippedIdentsOut: MutableSet<String>,
    ) : KotlinTokenRewriter(code) {
        override fun emitAt(index: Int, out: StringBuilder): Int {
            val tok = tokens[index]
            if (tok.interpolationDepth == 0 && tok is KotlinLexToken.Ident) {
                val ident = tok.rawText
                val prev = previousNonTriviaToken(index)
                val precededByDot = prev is KotlinLexToken.Punct && prev.ch == '.'
                if (ident in PHANTOM_RAW_POINTER_IDENTIFIERS || (ident == "cast" && precededByDot)) {
                    val wsIdx = skipHorizontalWhitespace(index + 1)
                    val closeAngleIdx = findMatchingTypeAngleToken(wsIdx)
                    if (closeAngleIdx > wsIdx && (ident != "cast" || isFollowedByEmptyParens(closeAngleIdx + 1))) {
                        collectCodeIdentifiers(wsIdx + 1, closeAngleIdx, strippedIdentsOut)
                        out.append(ident)
                        return closeAngleIdx + 1
                    }
                }
            }
            out.append(tok.rawText)
            return index + 1
        }

        private fun isFollowedByEmptyParens(startTokenIdx: Int): Boolean {
            val openIdx = skipHorizontalWhitespace(startTokenIdx)
            val openTok = tokens.getOrNull(openIdx) as? KotlinLexToken.Punct ?: return false
            if (openTok.ch != '(') return false
            val closeIdx = skipAllWhitespace(openIdx + 1)
            val closeTok = tokens.getOrNull(closeIdx) as? KotlinLexToken.Punct ?: return false
            return closeTok.ch == ')'
        }
    }

    private class OrphanedFunTypeParamRewriter(
        code: String,
        private val strippedIdents: Set<String>,
    ) : KotlinTokenRewriter(code) {
        override fun emitAt(index: Int, out: StringBuilder): Int {
            val tok = tokens[index]
            if (tok.interpolationDepth == 0 && tok is KotlinLexToken.Ident && tok.rawText == "fun") {
                val wsIdx = skipAllWhitespace(index + 1)
                val closeAngleIdx = findMatchingTypeAngleToken(wsIdx)
                if (closeAngleIdx > wsIdx) {
                    val openAngleTok = tokens[wsIdx]
                    val closeAngleTok = tokens[closeAngleIdx]
                    val declEndIdx = findFunctionDeclarationEndToken(closeAngleIdx + 1)
                    val bodyIdents = linkedSetOf<String>()
                    collectCodeIdentifiers(closeAngleIdx + 1, declEndIdx, bodyIdents)
                    val rawTypeParams = splitTopLevelCommaList(source.substring(openAngleTok.end, closeAngleTok.start))
                    val retainedParams =
                        rawTypeParams.filter { rawParam ->
                            val paramName = extractTypeParamName(rawParam)
                            paramName == null || paramName !in strippedIdents || paramName in bodyIdents
                        }
                    if (retainedParams.size != rawTypeParams.size) {
                        out.append("fun")
                        return if (retainedParams.isNotEmpty()) {
                            out.append(" <").append(retainedParams.joinToString(", ") { it.trim() }).append('>')
                            closeAngleIdx + 1
                        } else {
                            out.append(' ')
                            skipHorizontalWhitespace(closeAngleIdx + 1)
                        }
                    }
                }
            }
            out.append(tok.rawText)
            return index + 1
        }

        private fun findFunctionDeclarationEndToken(startTokenIdx: Int): Int {
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
                                    if (parenDepth == 0 &&
                                        angleDepth == 0 &&
                                        !isAdjacentComparisonOrEq(k)
                                    ) {
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

        private fun findExpressionBodyEndToken(startTokenIdx: Int): Int {
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

        private fun isAdjacentComparisonOrEq(eqIdx: Int): Boolean {
            val next = tokens.getOrNull(eqIdx + 1)
            if (next is KotlinLexToken.Punct && next.ch == '=') return true
            val prev = tokens.getOrNull(eqIdx - 1)
            return prev is KotlinLexToken.Punct && prev.ch in "=!<>?"
        }

        private fun startsTopLevelItemAt(tokenIdx: Int): Boolean {
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
    }

    private fun extractTypeParamName(rawParam: String): String? {
        val walker = KotlinTokenWalker(rawParam.substringBefore(':'))
        var candidate: String? = null
        for (idx in walker.tokens.indices) {
            val id = walker.identTextAt(idx) ?: continue
            if (id != "in" && id != "out" && id != "reified") {
                candidate = id
            }
        }
        return candidate
    }
}
