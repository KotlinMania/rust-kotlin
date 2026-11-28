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
