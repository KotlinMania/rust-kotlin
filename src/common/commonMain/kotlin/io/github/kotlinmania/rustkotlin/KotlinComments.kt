package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.procmacro2.Delimiter
import io.github.kotlinmania.procmacro2.Spacing
import io.github.kotlinmania.procmacro2.TokenStream
import io.github.kotlinmania.procmacro2.TokenTree
import io.github.kotlinmania.syn.Attribute
import io.github.kotlinmania.syn.Expr
import io.github.kotlinmania.syn.Item
import io.github.kotlinmania.syn.Lit
import io.github.kotlinmania.syn.Meta

internal fun extractDocLinesFromAttributes(
    attrs: List<Attribute>,
    allowFenceAstTranslation: Boolean = true,
): List<String> {
    val rawLines = mutableListOf<String>()
    for (attr in attrs) {
        val meta = attr.meta
        if (meta is Meta.NameValue && meta.path.isIdent("doc")) {
            val lit = (meta.value as? Expr.Lit)?.lit
            if (lit is Lit.Str) {
                for (line in lit.value.value().split('\n')) {
                    rawLines.add(line.trimEnd('\r'))
                }
            }
        }
    }
    return sanitizeDocLines(rawLines, allowFenceAstTranslation = allowFenceAstTranslation)
}

private enum class DocFenceKind {
    None,
    RustExample,
    VerbatimText,
}

private val VERBATIM_FENCE_LANGS: Set<String>
    get() = YamlRuleCatalogs.NamingAndComments.verbatimFenceLangs

private val NON_COMPILING_RUSTDOC_FENCE_TAGS: Set<String>
    get() = YamlRuleCatalogs.NamingAndComments.nonCompilingRustdocFenceTags

private val COMPILER_ONLY_DOC_EXAMPLE_ATTRS: Set<String>
    get() = YamlRuleCatalogs.NamingAndComments.compilerOnlyDocExampleAttrs

private val RUSTDOC_REF_TARGET_ROOTS: Set<String>
    get() = YamlRuleCatalogs.NamingAndComments.rustdocRefTargetRoots

private val COMMENT_FORBIDDEN_STUB_WORDS: Set<String>
    get() = YamlRuleCatalogs.NamingAndComments.commentForbiddenStubWords

private val COMMENT_COMMON_VOCAB_MAP: Map<String, String>
    get() = YamlRuleCatalogs.NamingAndComments.commentCommonVocab

private val COMMENT_CODE_IDENT_MAP: Map<String, String>
    get() = YamlRuleCatalogs.NamingAndComments.commentCodeIdents

private val KNOWN_RUST_PROSE_MACROS: Set<String>
    get() = YamlRuleCatalogs.NamingAndComments.knownRustProseMacros

private val TOP_LEVEL_ITEM_KEYWORDS: Set<String>
    get() = YamlRuleCatalogs.NamingAndComments.topLevelItemKeywords

internal fun sanitizeDocLines(
    rawLines: List<String>,
    allowFenceAstTranslation: Boolean = true,
): List<String> {
    if (rawLines.isEmpty()) return emptyList()
    val cleaned = mutableListOf<String>()
    var fenceKind = DocFenceKind.None
    var fencePrefixIndent = ""
    val bufferedRustFenceLines = mutableListOf<String>()

    fun flushRustExampleFence() {
        val translatedFence =
            translateRustDocCodeBlock(
                rawFenceLines = bufferedRustFenceLines,
                allowAstTranslation = allowFenceAstTranslation,
            )
        for (codeLine in translatedFence) {
            cleaned.add(if (codeLine.isBlank()) "" else "$fencePrefixIndent$codeLine".trimEnd())
        }
        bufferedRustFenceLines.clear()
    }

    for (rawLine in rawLines) {
        val lineWithoutLeadingSpace = if (rawLine.startsWith(" ")) rawLine.substring(1) else rawLine
        val trimmed = lineWithoutLeadingSpace.trim()
        if (trimmed.startsWith("```")) {
            if (fenceKind == DocFenceKind.None) {
                val fenceIdx = lineWithoutLeadingSpace.indexOf("```")
                fencePrefixIndent = if (fenceIdx > 0) lineWithoutLeadingSpace.substring(0, fenceIdx) else ""
                val rawInfo = trimmed.removePrefix("```").trim()
                val fenceInfoTokens = rawInfo.split(',', ' ', '\t').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                val langToken = fenceInfoTokens.firstOrNull().orEmpty()
                val hasNonCompilingTag = fenceInfoTokens.any { it in NON_COMPILING_RUSTDOC_FENCE_TAGS || it.startsWith("ignore-") }
                if (langToken in VERBATIM_FENCE_LANGS || hasNonCompilingTag) {
                    fenceKind = DocFenceKind.VerbatimText
                    cleaned.add(lineWithoutLeadingSpace.trimEnd())
                } else {
                    fenceKind = DocFenceKind.RustExample
                    bufferedRustFenceLines.clear()
                    cleaned.add("$fencePrefixIndent```kotlin")
                }
            } else {
                if (fenceKind == DocFenceKind.RustExample) {
                    flushRustExampleFence()
                }
                fenceKind = DocFenceKind.None
                cleaned.add(lineWithoutLeadingSpace.trimEnd())
            }
            continue
        }

        when (fenceKind) {
            DocFenceKind.VerbatimText -> cleaned.add(sanitizeVerbatimFenceLine(lineWithoutLeadingSpace))
            DocFenceKind.RustExample -> bufferedRustFenceLines.add(lineWithoutLeadingSpace)
            DocFenceKind.None -> {
                if (!isRustdocReferenceLinkDefinition(trimmed)) {
                    cleaned.add(sanitizeCommentTextForKotlin(lineWithoutLeadingSpace, inRustCodeFence = false))
                }
            }
        }
    }

    if (fenceKind == DocFenceKind.RustExample && bufferedRustFenceLines.isNotEmpty()) {
        flushRustExampleFence()
        cleaned.add("$fencePrefixIndent```")
    }

    return collapseRedundantBlankDocLines(cleaned)
}

private fun collapseRedundantBlankDocLines(lines: List<String>): List<String> {
    if (lines.isEmpty()) return emptyList()
    val out = mutableListOf<String>()
    for (line in lines) {
        if (line.isBlank() && out.lastOrNull()?.isBlank() == true) continue
        out += line
    }
    while (out.firstOrNull()?.isBlank() == true) out.removeAt(0)
    while (out.lastOrNull()?.isBlank() == true) out.removeAt(out.lastIndex)
    return out
}

internal fun renderKDocFromAttributes(
    attrs: List<Attribute>,
    indent: String,
    includeDocExampleAnnotations: Boolean = false,
): String {
    val lines = extractDocLinesFromAttributes(attrs, allowFenceAstTranslation = !includeDocExampleAnnotations)
    val kdoc = renderKDocLines(lines, indent)
    if (!includeDocExampleAnnotations) return kdoc
    return kdoc + renderDocExampleAnnotationsFromAttributes(attrs, indent)
}

private fun renderDocExampleAnnotationsFromAttributes(attrs: List<Attribute>, indent: String): String {
    if (attrs.isEmpty()) return ""
    val sb = StringBuilder()
    for (attr in attrs) {
        val attrSegs = attr.path().segments.toList()
        val lastIdent = attrSegs.lastOrNull()?.ident?.toString()
        val rawLast = lastIdent?.removePrefix("r#") ?: continue
        if (rawLast in COMPILER_ONLY_DOC_EXAMPLE_ATTRS) continue
        val ktAttrName = if (rawLast.startsWith("proc_macro")) rustTypeIdentToKotlin(rawLast) else rustValueIdentToKotlin(rawLast)
        when (val meta = attr.meta) {
            is Meta.PathMeta -> sb.append("$indent@$ktAttrName\n")
            is Meta.List -> {
                val renderedArgs = renderCommentTokenStream(meta.tokens.clone())
                sb.append("$indent@$ktAttrName($renderedArgs)\n")
            }
            is Meta.NameValue -> {
                val valueTokens = TokenStream.new()
                meta.value.toTokens(valueTokens)
                val renderedValue = renderCommentTokenStream(valueTokens)
                sb.append("$indent@$ktAttrName($renderedValue)\n")
            }
        }
    }
    return sb.toString()
}

internal fun renderKDocLines(lines: List<String>, indent: String): String {
    if (lines.isEmpty()) return ""
    val sb = StringBuilder()
    sb.append(indent).append("/**\n")
    for (line in lines) {
        val safeLine = line.replace("*/", "* /")
        if (safeLine.isBlank()) {
            sb.append("$indent *\n")
        } else {
            sb.append("$indent * $safeLine\n")
        }
    }
    sb.append("$indent */\n")
    return sb.toString()
}

internal fun renderInlineComments(pieces: List<TriviaPiece>, indent: String): String {
    if (pieces.isEmpty()) return ""
    val sb = StringBuilder()
    fun appendCommentLine(rawBody: String) {
        val sanitized = sanitizeCommentTextForKotlin(rawBody)
        if (sanitized.isNotEmpty()) {
            sb.append("$indent// $sanitized\n")
        } else {
            sb.append("$indent//\n")
        }
    }
    for (piece in pieces) {
        when (piece.style) {
            TriviaStyle.LineComment, TriviaStyle.InnerDocLine, TriviaStyle.OuterDocLine -> {
                val stripped = piece.text.removePrefix("//!").removePrefix("///")
                appendCommentLine(stripped.removePrefix("//").trim())
            }
            TriviaStyle.BlockComment, TriviaStyle.InnerDocBlock, TriviaStyle.OuterDocBlock -> {
                val noOpen = piece.text.removePrefix("/*!").removePrefix("/**")
                val inner = noOpen.removePrefix("/*").removeSuffix("*/")
                for (rawLine in inner.split('\n')) {
                    appendCommentLine(rawLine.trim().removePrefix("*").trim())
                }
            }
        }
    }
    return sb.toString()
}

private data class UnhiddenRustdocSource(
    val source: String,
    val wrapperFnKtName: String?,
    val hadHiddenTrailingOkUnit: Boolean,
)

private data class SpanReplacement(
    val startOffset: Int,
    val endOffset: Int,
    val replacement: String,
)

private fun translateRustDocCodeBlock(
    rawFenceLines: List<String>,
    allowAstTranslation: Boolean,
): List<String> {
    if (rawFenceLines.isEmpty()) return emptyList()
    val dedentedLines = stripCommonFenceIndentation(rawFenceLines)
    val visibleLines =
        dedentedLines.filter { line ->
            val trimmed = line.trimStart()
            trimmed != "#" && !trimmed.startsWith("# ")
        }
    if (visibleLines.all { it.isBlank() }) return emptyList()

    if (allowAstTranslation) {
        val visibleSource = visibleLines.joinToString("\n")
        val visibleStream = lexRustTokenStreamOrNull(visibleSource)
        if (visibleStream != null && RustdocSnippetTokenInspector(visibleStream.toList()).isCompleteSnippetEnding()) {
            translateSingleDoctestCandidate(visibleSource, wrapperFnKtName = null, stripTrailingSuccessUnit = false)?.let {
                return cleanEmittedKotlinDoctestLines(it)
            }
        }

        val unhidden = buildUnhiddenRustdocSource(dedentedLines)
        if (unhidden.source.isNotBlank() && unhidden.source != visibleSource) {
            translateSingleDoctestCandidate(
                rawCandidate = unhidden.source,
                wrapperFnKtName = unhidden.wrapperFnKtName,
                stripTrailingSuccessUnit = unhidden.hadHiddenTrailingOkUnit,
            )?.let {
                return cleanEmittedKotlinDoctestLines(it)
            }
        }
    }

    val wholeStream = lexRustTokenStreamOrNull(visibleLines.joinToString("\n"))
    if (wholeStream != null) {
        val rendered = renderCommentTokenStream(wholeStream)
        if (rendered.isNotBlank()) {
            return cleanEmittedKotlinDoctestLines(rendered.lines())
        }
    }
    return visibleLines.map { sanitizeCommentTextForKotlin(it, inRustCodeFence = true) }
}

private fun translateSingleDoctestCandidate(
    rawCandidate: String,
    wrapperFnKtName: String?,
    stripTrailingSuccessUnit: Boolean,
): List<String>? {
    val supNormalized = normalizeRustSourceForLexing(rawCandidate)
    val initialStream = lexTokenStreamOrNull(supNormalized) ?: return null
    val initialTrees = initialStream.toList()
    if (initialTrees.isEmpty()) return null

    val edits = mutableListOf<SpanReplacement>()
    RustdocSnippetTokenInspector(initialTrees).collectDoctestPlaceholderReplacements(edits)
    val normalizedSource = applySpanReplacements(supNormalized, edits)
    val trees =
        if (edits.isEmpty()) {
            initialTrees
        } else {
            lexTokenStreamOrNull(normalizedSource)?.toList() ?: return null
        }

    return if (RustdocSnippetTokenInspector(trees).looksLikeTopLevelItemFile()) {
        tryAstTranslateAsFile(
            source = normalizedSource,
            preLexedStream = if (edits.isEmpty()) initialStream else null,
            wrapperFnKtName = wrapperFnKtName,
            stripTrailingSuccessUnit = stripTrailingSuccessUnit,
        ) ?: tryAstTranslateAsWrappedBody(normalizedSource, trees)
    } else {
        tryAstTranslateAsWrappedBody(normalizedSource, trees)
    }
}

private fun stripCommonFenceIndentation(lines: List<String>): List<String> {
    var minIndent = Int.MAX_VALUE
    for (line in lines) {
        if (line.isBlank()) continue
        var spaces = 0
        while (spaces < line.length && line[spaces] == ' ') spaces++
        if (spaces < minIndent) minIndent = spaces
    }
    if (minIndent == 0 || minIndent == Int.MAX_VALUE) return lines
    return lines.map { line -> if (line.length >= minIndent) line.substring(minIndent) else "" }
}

private fun buildUnhiddenRustdocSource(lines: List<String>): UnhiddenRustdocSource {
    val out = mutableListOf<String>()
    var inStringifyHide = false
    var wrapperFnKtName: String? = null
    var hadHiddenTrailingOkUnit = false

    for (line in lines) {
        val trimmed = line.trimStart()
        if (trimmed == "#") continue
        if (trimmed.startsWith("# ")) {
            val unhidden = trimmed.substring(2)
            val unhiddenTrimmed = unhidden.trim()
            if (inStringifyHide && unhiddenTrimmed == "};") {
                inStringifyHide = false
                continue
            }
            val lineTokens = lexRustTokenStreamOrNull(unhidden)
            if (lineTokens != null) {
                val inspector = RustdocSnippetTokenInspector(lineTokens.toList())
                if (inspector.isExternCrateLine()) continue
                if (inspector.isStringifyConstHideHeader()) {
                    inStringifyHide = true
                    continue
                }
                val detectedFn = inspector.extractFnHeaderIdent()
                if (detectedFn != null && wrapperFnKtName == null) {
                    wrapperFnKtName = rustFunctionIdentToKotlin(detectedFn)
                }
                if (inspector.isOkUnitLine()) {
                    hadHiddenTrailingOkUnit = true
                }
            } else {
                val fallbackStream = lexRustTokenStreamOrNull("$unhiddenTrimmed }")
                val fallbackFn = fallbackStream?.let { RustdocSnippetTokenInspector(it.toList()).extractFnHeaderIdent() }
                if (fallbackFn != null && wrapperFnKtName == null) {
                    wrapperFnKtName = rustFunctionIdentToKotlin(fallbackFn)
                }
            }
            out += rewriteHiddenUnimplementedMacrosViaTokens(unhidden)
        } else {
            out += line
        }
    }

    return UnhiddenRustdocSource(
        source = out.joinToString("\n"),
        wrapperFnKtName = wrapperFnKtName,
        hadHiddenTrailingOkUnit = hadHiddenTrailingOkUnit,
    )
}

/**
 * Inspects and extracts placeholder/macro replacement spans over `proc_macro2` token streams
 * inside Rustdoc code fences, extending [RustTokenCursor].
 */
private class RustdocSnippetTokenInspector(
    trees: List<TokenTree>,
) : RustTokenCursor(trees) {
    fun isCompleteSnippetEnding(): Boolean {
        if (trees.isEmpty()) return true
        val lastIdx = size - 1
        return identAt(lastIdx) != "else" && punctCharAt(lastIdx) != '>'
    }

    fun looksLikeTopLevelItemFile(): Boolean {
        var idx = computeLeadingUseItemsNextIndex()
        while (idx < size) {
            consumeAttribute(idx, allowInnerAttr = true)?.let {
                idx = it
                continue
            }
            val ident = identAt(idx) ?: return false
            if (ident == "pub" || ident == "async" || ident == "unsafe") {
                idx++
                if (groupAt(idx, Delimiter.Parenthesis) != null) idx++
                continue
            }
            return ident in TOP_LEVEL_ITEM_KEYWORDS
        }
        return false
    }

    fun computeLeadingUseItemsNextIndex(): Int {
        var idx = 0
        while (idx < size) {
            val firstIdent = identAt(idx)
            val isLeadingUseOrExtern =
                firstIdent == "use" ||
                    (firstIdent == "pub" && identAt(idx + 1) == "use") ||
                    (firstIdent == "extern" && identAt(idx + 1) == "crate")
            if (!isLeadingUseOrExtern) break
            val semiIdx = (idx until size).firstOrNull { punctCharAt(it) == ';' } ?: break
            idx = semiIdx + 1
        }
        return idx
    }

    fun isExternCrateLine(): Boolean =
        size >= 3 && identAt(0) == "extern" && identAt(1) == "crate"

    fun isStringifyConstHideHeader(): Boolean =
        size > 0 && identAt(0) == "const" && (0 until size).any { identAt(it) == "stringify" }

    fun extractFnHeaderIdent(): String? {
        var idx = 0
        while (idx < size) {
            val ident = identAt(idx)
            if (ident == "pub" || ident == "async" || ident == "const") {
                idx++
                if (groupAt(idx, Delimiter.Parenthesis) != null) idx++
                continue
            }
            if (ident == "fn" && idx + 1 < size) return cleanIdentAt(idx + 1)
            break
        }
        return null
    }

    fun isOkUnitLine(): Boolean {
        for (idx in 0 until size) {
            if (identAt(idx) == "Ok") {
                val outerParen = groupAt(idx + 1, Delimiter.Parenthesis) ?: continue
                if (RustTokenCursor(outerParen.stream().toList()).isUnitParens()) return true
            }
        }
        return false
    }

    fun containsTryQuestionToken(): Boolean =
        (0 until size).any { idx ->
            punctCharAt(idx) == '?' ||
                groupAt(idx)?.let { RustdocSnippetTokenInspector(it.stream().toList()).containsTryQuestionToken() } == true
        }

    fun collectUnimplementedMacroReplacements(edits: MutableList<SpanReplacement>) {
        var i = 0
        while (i < size) {
            val macroName = identAt(i)
            val macroGrp = if (macroName != null && punctCharAt(i + 1) == '!') groupAt(i + 2) else null
            if (macroGrp != null && (macroName == "unimplemented" || macroName == "todo")) {
                val start = (trees[i] as TokenTree.Ident).value.span().startOffset()
                val end = macroGrp.spanClose().endExclusiveOffset()
                if (start >= 0 && end > start) {
                    edits += SpanReplacement(start, end, "{}")
                }
                i += 3
                continue
            }
            groupAt(i)?.let { grp ->
                RustdocSnippetTokenInspector(grp.stream().toList()).collectUnimplementedMacroReplacements(edits)
            }
            i++
        }
    }

    fun collectDoctestPlaceholderReplacements(edits: MutableList<SpanReplacement>) {
        for (i in 0 until size) {
            val grp = groupAt(i)
            if (grp != null) {
                val innerInspector = RustdocSnippetTokenInspector(grp.stream().toList())
                if (grp.delimiter() == Delimiter.Brace &&
                    innerInspector.size == 3 &&
                    (0 until 3).all { innerInspector.punctCharAt(it) == '.' }
                ) {
                    val start = grp.spanOpen().startOffset()
                    val end = grp.spanClose().endExclusiveOffset()
                    if (start >= 0 && end > start) {
                        edits += SpanReplacement(start, end, "{}")
                    }
                    continue
                }
                innerInspector.collectDoctestPlaceholderReplacements(edits)
            }
            if (punctCharAt(i) == '=' && punctSpacingAt(i) == Spacing.Alone && i + 1 < size) {
                if (punctCharAt(i + 1) == ';') {
                    val semiPos = (trees[i + 1] as TokenTree.Punct).value.span().startOffset()
                    if (semiPos >= 0) {
                        edits += SpanReplacement(semiPos, semiPos, "placeholderValue")
                    }
                } else if (i + 3 < size) {
                    val afterCh = punctCharAt(i + 4)
                    val validTrailing = i + 4 >= size || afterCh == ';' || afterCh == ','
                    val isEllipsis =
                        punctCharAt(i + 1) == '.' && punctSpacingAt(i + 1) == Spacing.Joint &&
                            punctCharAt(i + 2) == '.' && punctSpacingAt(i + 2) == Spacing.Joint &&
                            punctCharAt(i + 3) == '.'
                    if (validTrailing && isEllipsis) {
                        val startPos = (trees[i + 1] as TokenTree.Punct).value.span().startOffset()
                        val endPos = (trees[i + 3] as TokenTree.Punct).value.span().endExclusiveOffset()
                        if (startPos >= 0 && endPos > startPos) {
                            edits += SpanReplacement(startPos, endPos, "placeholderValue")
                        }
                    }
                }
            }
        }
    }
}

private fun rewriteHiddenUnimplementedMacrosViaTokens(source: String): String {
    val normalized = normalizeRustSourceForLexing(source)
    val stream = lexTokenStreamOrNull(normalized) ?: return source
    val edits = mutableListOf<SpanReplacement>()
    RustdocSnippetTokenInspector(stream.toList()).collectUnimplementedMacroReplacements(edits)
    return applySpanReplacements(normalized, edits)
}

private fun applySpanReplacements(source: String, edits: List<SpanReplacement>): String {
    if (edits.isEmpty()) return source
    val sorted = edits.sortedByDescending { it.startOffset }
    val sb = StringBuilder(source)
    var lastStart = source.length + 1
    for (edit in sorted) {
        if (edit.startOffset < 0 || edit.endOffset > sb.length || edit.endOffset > lastStart) continue
        sb.deleteRange(edit.startOffset, edit.endOffset)
        sb.insert(edit.startOffset, edit.replacement)
        lastStart = edit.startOffset
    }
    return sb.toString()
}

private fun tryAstTranslateAsFile(
    source: String,
    preLexedStream: TokenStream? = null,
    wrapperFnKtName: String?,
    stripTrailingSuccessUnit: Boolean,
): List<String>? {
    val recognized =
        if (preLexedStream != null) {
            recognizePreLexedRustOrNull(source, preLexedStream)
        } else {
            recognizeRustOrNull(source)
        } ?: return null
    val items = recognized.file.items
    if (items.isEmpty()) return null
    if (items.none { it !is Item.Macro && it !is Item.Use && it !is Item.ExternCrate }) return null

    val emitted =
        translateRustSourceToKotlinArtifacts(
            recognized = recognized,
            headerContext = FileHeaderContext(portLintDirective = "none", isDocExample = true),
        ).first
    val lines = emitted.trimEnd('\n').lines()
    if (lines.all { it.isBlank() }) return null
    return if (wrapperFnKtName != null) {
        unwrapSyntheticDoctestFunction(lines, wrapperFnKtName, stripTrailingSuccessUnit)
    } else {
        lines
    }
}

private fun tryAstTranslateAsWrappedBody(
    normalized: String,
    trees: List<TokenTree>,
): List<String>? {
    if (trees.isEmpty()) return null
    val nextIdx = RustdocSnippetTokenInspector(trees).computeLeadingUseItemsNextIndex()
    val prevPunct = if (nextIdx in 1..trees.size) (trees[nextIdx - 1] as? TokenTree.Punct)?.value else null
    val splitOffset = prevPunct?.span()?.endExclusiveOffset() ?: 0
    val leadingUses = if (splitOffset in 1..normalized.length) normalized.substring(0, splitOffset) else ""
    val bodyPart = if (splitOffset in 1..normalized.length) normalized.substring(splitOffset) else normalized
    if (bodyPart.isBlank()) return null

    val bodyTrees = if (nextIdx > 0) trees.subList(nextIdx, trees.size) else trees
    val usesTryOperator = RustdocSnippetTokenInspector(bodyTrees).containsTryQuestionToken()
    val wrapped =
        if (usesTryOperator) {
            "$leadingUses\nfn __doctest_entry__() -> Result<()> {\n$bodyPart\nOk(())\n}"
        } else {
            "$leadingUses\nfn __doctest_entry__() {\n$bodyPart\n}"
        }
    return tryAstTranslateAsFile(
        source = wrapped,
        preLexedStream = null,
        wrapperFnKtName = "doctestEntry",
        stripTrailingSuccessUnit = usesTryOperator,
    )
}

private fun unwrapSyntheticDoctestFunction(
    emittedLines: List<String>,
    wrapperFnKtName: String,
    stripTrailingSuccessUnit: Boolean,
): List<String> =
    DoctestWrapperFunctionUnwrapper(emittedLines, wrapperFnKtName, stripTrailingSuccessUnit).unwrap()

private class DoctestWrapperFunctionUnwrapper(
    private val emittedLines: List<String>,
    private val wrapperFnKtName: String,
    private val stripTrailingSuccessUnit: Boolean,
) : KotlinTokenWalker(emittedLines.joinToString("\n")) {
    fun unwrap(): List<String> {
        val imports = mutableListOf<String>()
        var idx = nextNonTriviaIndex(0)
        while (identTextAt(idx) == "import") {
            val importStart = tokens[idx].start
            var endTok = idx + 1
            while (endTok < tokens.size && tokens[endTok] !is KotlinLexToken.Newline) endTok++
            val importEnd = tokens[endTok - 1].end
            imports += source.substring(importStart, importEnd).trim()
            idx = nextNonTriviaIndex(endTok)
        }
        var funIdx = idx
        if (identTextAt(funIdx) == "public") {
            funIdx = nextNonTriviaIndex(funIdx + 1)
        }
        if (identTextAt(funIdx) != "fun") return emittedLines
        val nameIdx = nextNonTriviaIndex(funIdx + 1)
        if (identTextAt(nameIdx) != wrapperFnKtName) return emittedLines
        val openParenIdx = nextNonTriviaIndex(nameIdx + 1)
        val closeParenIdx = findMatchingParenToken(openParenIdx)
        if (closeParenIdx < openParenIdx) return emittedLines

        var scan = nextNonTriviaIndex(closeParenIdx + 1)
        while (scan < tokens.size && punctAt(scan) != '{') {
            if (punctAt(scan) == '=' || punctAt(scan) == ';') return emittedLines
            scan++
        }
        if (punctAt(scan) != '{') return emittedLines
        val openBraceIdx = scan
        val afterCloseBrace = findMatchingBraceEndToken(openBraceIdx)
        val closeBraceIdx = afterCloseBrace - 1
        if (closeBraceIdx <= openBraceIdx || punctAt(closeBraceIdx) != '}') return emittedLines
        if (nextNonTriviaIndex(afterCloseBrace) < tokens.size) return emittedLines

        val rawBody = source.substring(tokens[openBraceIdx].end, tokens[closeBraceIdx].start)
        if (rawBody.isBlank()) return imports
        val bodyLines =
            rawBody
                .removePrefix("\n")
                .lines()
                .map { line -> if (line.startsWith("    ")) line.substring(4) else line }
                .toMutableList()

        if (stripTrailingSuccessUnit) {
            while (bodyLines.lastOrNull()?.isBlank() == true) bodyLines.removeAt(bodyLines.lastIndex)
            val lastTrimmed = bodyLines.lastOrNull()?.trim()
            if (lastTrimmed == "return Result.success(Unit)" || lastTrimmed == "Result.success(Unit)") {
                bodyLines.removeAt(bodyLines.lastIndex)
            }
        }
        while (bodyLines.firstOrNull()?.isBlank() == true) bodyLines.removeAt(0)
        while (bodyLines.lastOrNull()?.isBlank() == true) bodyLines.removeAt(bodyLines.lastIndex)

        return buildList {
            if (imports.isNotEmpty()) {
                addAll(imports)
                if (bodyLines.isNotEmpty()) add("")
            }
            addAll(bodyLines)
        }
    }
}

private fun cleanEmittedKotlinDoctestLines(lines: List<String>): List<String> =
    lines.map { line ->
        val quoteCleaned = ParseQuoteStringArgRewriter(line).rewriteIfCandidate()
        val safeDelims = quoteCleaned.replace("*/", "* /").replace("/*", "/ *")
        CommentLexicalScanner(safeDelims).sanitizeProseVocabulary(convertCamelIdentifiersOnly = true)
    }

private fun isRustdocReferenceLinkDefinition(trimmed: String): Boolean {
    if (!trimmed.startsWith("[")) return false
    val closeBracket = trimmed.indexOf("]:")
    if (closeBracket <= 1) return false
    val target = trimmed.substring(closeBracket + 2).trim()
    if (target.isEmpty()) return false
    val trees = lexRustTokenTrees(target)
    if (trees.isEmpty()) return false
    val cursor = RustTokenCursor(trees)
    val root = cursor.cleanIdentAt(0) ?: return false
    if (root !in RUSTDOC_REF_TARGET_ROOTS) return false
    return cursor.size == 1 || cursor.isDoubleColonAt(1) || cursor.punctCharAt(1) == '!'
}

private fun sanitizeVerbatimFenceLine(line: String): String {
    if (line.isEmpty()) return ""
    val escaped = line.replace("*/", "* /").replace("/*", "/ *")
    val attrsRewritten = CommentLexicalScanner(escaped).rewriteInlineRustAttributes()
    return CommentLexicalScanner(attrsRewritten).sanitizeProseVocabulary(convertCamelIdentifiersOnly = true)
}

internal fun sanitizeCommentTextForKotlin(line: String, inRustCodeFence: Boolean = false): String {
    if (line.isEmpty()) return ""
    if (inRustCodeFence) {
        val stream = lexRustTokenStreamOrNull(line)
        if (stream != null) {
            val rendered = renderCommentTokenStream(stream).replace("*/", "* /").replace("/*", "/ *")
            return CommentLexicalScanner(rendered).sanitizeProseVocabulary(convertCamelIdentifiersOnly = true).trimEnd()
        }
    }
    return CommentLexicalScanner(line).sanitizeMarkdownCommentLine()
}

private fun renderIntraDocLinkTarget(rawCode: String): String {
    val trimmed = rawCode.trim()
    if (trimmed.isEmpty()) return ""
    val stream = lexRustTokenStreamOrNull(trimmed)
    val parts = stream?.let { CommentRustTokenEmitter(it.toList()).renderIntraDocLinkParts() }.orEmpty()
    return if (parts.isEmpty()) {
        CommentLexicalScanner(trimmed).sanitizeProseVocabulary(convertCamelIdentifiersOnly = false)
    } else {
        parts.joinToString(".")
    }
}

private fun translateInlineCodeSpan(spanContent: String): String {
    val trimmed = spanContent.trim()
    if (trimmed.isEmpty()) return ""
    if (trimmed.all { it.isLetterOrDigit() || it == '_' }) {
        val mappedSingle = if (trimmed[0].isDigit()) trimmed else mapRustIdentInCommentCode(trimmed)
        return CommentLexicalScanner(mappedSingle).sanitizeProseVocabulary(convertCamelIdentifiersOnly = false)
    }
    val trees = lexRustTokenTrees(trimmed)
    val bareModuleParts =
        if (trees.isNotEmpty()) {
            RustTokenCursor(trees)
                .extractPureColonPathSegments()
                ?.first
                ?.takeIf { parts ->
                    parts.size >= 2 &&
                        parts.all { seg -> seg.isNotEmpty() && !seg.contains('_') && seg.all { it.isLowerCase() || it.isDigit() } }
                }
        } else {
            null
        }
    if (bareModuleParts != null) {
        val trimmedParts = if (bareModuleParts[0] in setOf("crate", "self", "super")) bareModuleParts.drop(1) else bareModuleParts
        return CommentLexicalScanner(trimmedParts.joinToString(".")).sanitizeProseVocabulary(convertCamelIdentifiersOnly = false)
    }
    val rendered = if (trees.isNotEmpty()) CommentRustTokenEmitter(trees).render() else trimmed
    return CommentLexicalScanner(rendered).sanitizeProseVocabulary(convertCamelIdentifiersOnly = false)
}

/**
 * Lexical scanner for comment/KDoc prose, inline Rust `#[...]` attributes, `parseQuote("...")`
 * string arguments, intra-doc links, and lifetime/macro vocabulary sanitization,
 * reusing string/escape/identifier primitives from [RustLexicalSourceScanner].
 */
private class CommentLexicalScanner(
    source: String,
) : RustLexicalSourceScanner(source) {
    fun sanitizeMarkdownCommentLine(): String {
        val safeDelims = convertInlineBlockComments().replace("*/", "* /").replace("/*", "/ *")
        return CommentLexicalScanner(safeDelims).rewriteMarkdownSpansAndProse().trimEnd()
    }

    private fun convertInlineBlockComments(): String {
        if (!source.contains("/*")) return source
        val sb = StringBuilder(len)
        var i = 0
        while (i < len) {
            if (source[i] == '/' && i + 1 < len && source[i + 1] == '*') {
                val closeIdx = source.indexOf("*/", startIndex = i + 2)
                if (closeIdx >= 0) {
                    val inner = source.substring(i + 2, closeIdx).trim()
                    if (inner.isEmpty()) sb.append("//") else sb.append("// ").append(inner)
                    i = closeIdx + 2
                    continue
                }
            }
            sb.append(source[i])
            i++
        }
        return sb.toString()
    }

    private fun rewriteMarkdownSpansAndProse(): String {
        val out = StringBuilder(len)
        var i = 0
        while (i < len) {
            if (source[i] == '[' && i + 1 < len && source[i + 1] == '`') {
                val closeCodeTick = source.indexOf('`', startIndex = i + 2)
                if (closeCodeTick > i + 2 && closeCodeTick + 1 < len && source[closeCodeTick + 1] == ']') {
                    val afterLink = closeCodeTick + 2
                    val isExternal = afterLink < len && (source[afterLink] == '(' || source[afterLink] == '[')
                    val renderedTarget = renderIntraDocLinkTarget(source.substring(i + 2, closeCodeTick))
                    if (isExternal) {
                        out.append("[`").append(renderedTarget).append("`]")
                    } else {
                        out.append('[').append(renderedTarget).append(']')
                    }
                    i = afterLink
                    continue
                }
                out.append('[')
                i++
                continue
            }
            if (source[i] == '[' && isPlainIntraDocBracketLinkAt(i)) {
                val closeBracket = source.indexOf(']', startIndex = i + 1)
                out.append('[').append(renderIntraDocLinkTarget(source.substring(i + 1, closeBracket))).append(']')
                i = closeBracket + 1
                continue
            }
            if (source[i] == '`') {
                val closeTick = source.indexOf('`', startIndex = i + 1)
                if (closeTick > i) {
                    val renderedCode = translateInlineCodeSpan(source.substring(i + 1, closeTick))
                    if (renderedCode.isNotEmpty()) {
                        out.append('`').append(renderedCode).append('`')
                    }
                    i = closeTick + 1
                    continue
                }
                out.append('`')
                i++
                continue
            }
            val nextSpecial = findNextBacktickOrIntraDocLink(i + 1)
            val attrsRewritten = CommentLexicalScanner(source.substring(i, nextSpecial)).rewriteInlineRustAttributes()
            out.append(CommentLexicalScanner(attrsRewritten).sanitizeProseVocabulary(convertCamelIdentifiersOnly = false))
            i = nextSpecial
        }
        return out.toString()
    }

    private fun isPlainIntraDocBracketLinkAt(idx: Int): Boolean {
        if (idx >= len || source[idx] != '[') return false
        if (idx + 1 < len && (source[idx + 1] == '`' || source[idx + 1] == '[')) return false
        val closeBracket = source.indexOf(']', startIndex = idx + 1)
        if (closeBracket <= idx + 1) return false
        val inner = source.substring(idx + 1, closeBracket)
        if (inner.any { it.isWhitespace() || it == '[' || it == '`' }) return false
        val parsed =
            RustTokenCursor(lexRustTokenTrees(inner))
                .extractPureColonPathSegments(allowTrailingBang = true, allowTrailingEmptyParens = true)
                ?: return false
        return parsed.first.size >= 2 || parsed.second
    }

    private fun findNextBacktickOrIntraDocLink(start: Int): Int {
        var i = start
        while (i < len) {
            if (source[i] == '`') return i
            if (source[i] == '[' && i + 1 < len && source[i + 1] == '`') return i
            if (source[i] == '[' && isPlainIntraDocBracketLinkAt(i)) return i
            i++
        }
        return len
    }

    fun rewriteInlineRustAttributes(): String {
        if (!source.contains('#')) return source
        val sb = StringBuilder(len)
        var i = 0
        while (i < len) {
            if (source[i] == '#') {
                var j = i + 1
                if (j < len && source[j] == '!') j++
                while (j < len && source[j].isWhitespace()) j++
                if (j < len && source[j] == '[') {
                    val closeIdx = findBalancedDelimiterEnd(j, '[', ']')
                    if (closeIdx > j) {
                        val attrStream = lexRustTokenStreamOrNull(source.substring(i, closeIdx + 1))
                        val renderedAttr = attrStream?.let(::renderCommentTokenStream).orEmpty()
                        if (renderedAttr.isNotEmpty()) sb.append(renderedAttr)
                        i = closeIdx + 1
                        continue
                    }
                }
            }
            sb.append(source[i])
            i++
        }
        return sb.toString()
    }

    fun sanitizeProseVocabulary(convertCamelIdentifiersOnly: Boolean): String {
        if (source.isEmpty()) return ""
        val normalized = rewriteLeadingKotlinColonPrefix(source)
        return CommentLexicalScanner(normalized).rewriteNormalizedProse(convertCamelIdentifiersOnly)
    }

    private fun rewriteNormalizedProse(convertCamelIdentifiersOnly: Boolean): String {
        val sb = StringBuilder(len)
        var i = 0
        while (i < len) {
            val ch = source[i]
            if (ch == '(' && source.regionMatches(i, "(from impl", 0, 10, ignoreCase = true)) {
                val afterWord = i + 10
                val isBoundary = afterWord >= len || !source[afterWord].isLetterOrDigit()
                val closeParen = if (isBoundary) source.indexOf(')', startIndex = afterWord) else -1
                if (closeParen > i) {
                    i = closeParen + 1
                    continue
                }
            }
            if (ch == '&') {
                var afterAmp = i + 1
                while (afterAmp < len && source[afterAmp] == ' ') afterAmp++
                if (source.regionMatches(afterAmp, "mut", 0, 3)) {
                    val afterMut = afterAmp + 3
                    if (afterMut < len && source[afterMut].isWhitespace()) {
                        var skipWs = afterMut
                        while (skipWs < len && source[skipWs] == ' ') skipWs++
                        i = skipWs
                        continue
                    }
                }
            }
            if (ch == '\'') {
                val tickScan = scanTickPrefixedIdentEnd(i)
                if (tickScan != null) {
                    val (identEnd, isClosedByTick, allowBracketPrefix) = tickScan
                    if (isClosedByTick) {
                        val rawIdent = source.substring(i + 1, identEnd)
                        val trimmedPrefix = source.substring(0, i).trimEnd()
                        val looksLikeComparison =
                            trimmedPrefix.endsWith("==") || trimmedPrefix.endsWith("!=") || trimmedPrefix.endsWith("=")
                        if (rawIdent.length > 1 || (!convertCamelIdentifiersOnly && !looksLikeComparison)) {
                            val mapped = mapCommentVocabularyWord(rawIdent, convertCamelIdentifiersOnly)
                            sb.append('`').append(mapped).append('`')
                            i = identEnd + 1
                            continue
                        }
                    } else if (!allowBracketPrefix) {
                        val ltName = source.substring(i + 1, identEnd)
                        if (sb.lastOrNull() == '(' && source.getOrNull(identEnd) == ')') {
                            sb.append('"').append(ltName).append('"')
                        } else {
                            sb.append(ltName)
                        }
                        i = identEnd
                        continue
                    }
                }
            }
            if (ch.isLetter() || ch == '_') {
                var end = i + 1
                while (end < len && isIdentContinue(source[end])) end++
                val word = source.substring(i, end)
                if (word == "Send") {
                    val sendSyncEnd = tryConsumeSendPlusSyncAt(end)
                    if (sendSyncEnd > end) {
                        sb.append("ThreadSafe")
                        i = sendSyncEnd
                        continue
                    }
                }
                if (source.startsWith("::", end) && end + 2 < len && (source[end + 2].isLetter() || source[end + 2] == '_')) {
                    i = appendDoubleColonPath(word, end, sb)
                    continue
                }
                var afterWord = end
                while (afterWord < len && source[afterWord] == ' ') afterWord++
                if (afterWord + 1 < len &&
                    source[afterWord] == '!' &&
                    (source[afterWord + 1] == '(' || source[afterWord + 1] == '{' || source[afterWord + 1] == '[')
                ) {
                    sb.append(if (word == "macro_rules") "macroRules" else rustFunctionIdentToKotlin(word))
                    i = afterWord + 1
                    continue
                }
                if (word == "macro_rules" && afterWord < len && source[afterWord] == '!') {
                    sb.append("macroRules")
                    i = afterWord + 1
                    continue
                }
                if (afterWord == end &&
                    end < len &&
                    source[end] == '!' &&
                    source.getOrNull(end + 1) != '=' &&
                    (word.contains('_') || word in KNOWN_RUST_PROSE_MACROS)
                ) {
                    sb.append(rustFunctionIdentToKotlin(word))
                    i = end + 1
                    continue
                }
                sb.append(mapCommentVocabularyWord(word, convertCamelIdentifiersOnly))
                i = end
                continue
            }
            if (!convertCamelIdentifiersOnly && (ch == '{' || ch == '}')) {
                sb.append(if (ch == '{') '(' else ')')
                i++
                continue
            }
            sb.append(ch)
            i++
        }
        return sb.toString()
    }

    private fun tryConsumeSendPlusSyncAt(afterSend: Int): Int {
        var j = afterSend
        while (j < len && source[j].isWhitespace()) j++
        if (j >= len || source[j] != '+') return -1
        j++
        while (j < len && source[j].isWhitespace()) j++
        if (!source.regionMatches(j, "Sync", 0, 4)) return -1
        val end = j + 4
        return if (end >= len || !isIdentContinue(source[end])) end else -1
    }

    private fun scanTickPrefixedIdentEnd(tickIdx: Int): Triple<Int, Boolean, Boolean>? {
        if (tickIdx + 1 >= len) return null
        val firstIdent = source[tickIdx + 1]
        if (!firstIdent.isLetter() && firstIdent != '_') return null
        val prev = if (tickIdx > 0) source[tickIdx - 1] else ' '
        val isBracketPrefix = prev == '['
        val validBasePrefix =
            prev.isWhitespace() || prev == '&' || prev == ':' || prev == '<' || prev == ',' || prev == ';' || prev == '('
        if (!validBasePrefix && !isBracketPrefix) return null
        var end = tickIdx + 2
        while (end < len && isIdentContinue(source[end])) end++
        val closedByTick = end < len && source[end] == '\''
        return Triple(end, closedByTick, isBracketPrefix)
    }

    private fun appendDoubleColonPath(firstWord: String, firstEnd: Int, sb: StringBuilder): Int {
        val segments = mutableListOf(firstWord)
        var cursor = firstEnd
        while (source.startsWith("::", cursor) && cursor + 2 < len && (source[cursor + 2].isLetter() || source[cursor + 2] == '_')) {
            var segEnd = cursor + 3
            while (segEnd < len && isIdentContinue(source[segEnd])) segEnd++
            segments += source.substring(cursor + 2, segEnd)
            cursor = segEnd
        }
        if (cursor < len && source[cursor] == '!' && source.getOrNull(cursor + 1) != '=') {
            cursor++
        }
        val filteredSegs = if (segments.size > 1 && segments[0] in setOf("crate", "self", "super")) segments.drop(1) else segments
        val renderedPath =
            filteredSegs.joinToString(".") { seg ->
                when {
                    seg == "macro_rules" -> "macroRules"
                    seg.first().isUpperCase() -> rustTypeIdentToKotlin(seg)
                    else -> rustValueIdentToKotlin(seg)
                }
            }
        sb.append(renderedPath)
        return cursor
    }
}

private class ParseQuoteStringArgRewriter(
    source: String,
) : KotlinTokenRewriter(source) {
    fun rewriteIfCandidate(): String =
        if ("quote(" !in source && "parseQuote(" !in source) source else rewrite()

    override fun emitAt(index: Int, out: StringBuilder): Int {
        val fnIdent = identTextAt(index)
        if (fnIdent == "parseQuote" || fnIdent == "quote") {
            val openParenIdx = nextNonTriviaIndex(index + 1)
            val strOpenIdx = if (punctAt(openParenIdx) == '(') nextNonTriviaIndex(openParenIdx + 1) else -1
            val strOpen = tokens.getOrNull(strOpenIdx) as? KotlinLexToken.StringOpen
            if (strOpen != null && !strOpen.isRaw) {
                var k = strOpenIdx + 1
                val rawBody = StringBuilder()
                var validPlainString = true
                while (k < tokens.size && tokens[k] !is KotlinLexToken.StringClose) {
                    val part = tokens[k]
                    if (part is KotlinLexToken.StringText) {
                        rawBody.append(unescapeKotlinDoubleQuotedText(part.rawText))
                    } else {
                        validPlainString = false
                        break
                    }
                    k++
                }
                if (validPlainString && k < tokens.size && tokens[k] is KotlinLexToken.StringClose) {
                    val innerStream = lexRustTokenStreamOrNull(rawBody.toString().replace("`#`", "#"))
                    if (innerStream != null) {
                        val reescaped = renderCommentTokenStream(innerStream).replace("\\", "\\\\").replace("\"", "\\\"")
                        for (t in index..strOpenIdx) out.append(tokens[t].rawText)
                        out.append(reescaped).append('"')
                        return k + 1
                    }
                }
            }
        }
        out.append(tokens[index].rawText)
        return index + 1
    }

    private fun unescapeKotlinDoubleQuotedText(raw: String): String {
        if ('\\' !in raw) return raw
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (ch == '\\' && i + 1 < raw.length) {
                when (val esc = raw[i + 1]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '$' -> sb.append('$')
                    else -> sb.append('\\').append(esc)
                }
                i += 2
            } else {
                sb.append(ch)
                i++
            }
        }
        return sb.toString()
    }
}

private fun renderCommentTokenStream(stream: TokenStream): String =
    CommentRustTokenEmitter(stream.toList()).render()

private class CommentRustTokenEmitter(
    trees: List<TokenTree>,
) : RustTokenStreamKotlinEmitter(trees) {
    override fun spawnChild(childTrees: List<TokenTree>): RustTokenStreamKotlinEmitter =
        CommentRustTokenEmitter(childTrees)

    override fun renderAttributeInteriorStream(stream: TokenStream, isInner: Boolean): String =
        CommentRustTokenEmitter(stream.toList()).renderAttributeInterior(isInner)

    override fun consumeLifetimeAt(idx: Int, outTokens: MutableList<String>): Int {
        if (size == 2) {
            outTokens += mapCommentVocabularyWord(cleanIdentAt(idx + 1).orEmpty(), convertCamelIdentifiersOnly = false)
            return idx + 2
        }
        return super.consumeLifetimeAt(idx, outTokens)
    }

    override fun mapIdent(rawIdent: String): String = mapRustIdentInCommentCode(rawIdent)

    fun renderIntraDocLinkParts(): List<String> {
        val parts = mutableListOf<String>()
        var i = 0
        while (i < size) {
            when (trees[i]) {
                is TokenTree.Ident -> {
                    val raw = cleanIdentAt(i).orEmpty()
                    if (parts.isEmpty() && (raw == "crate" || raw == "self" || raw == "super") && i + 2 < size && isDoubleColonAt(i + 1)) {
                        i += 3
                        continue
                    }
                    parts += if (raw.firstOrNull()?.isUpperCase() == true) rustTypeIdentToKotlin(raw) else rustValueIdentToKotlin(raw)
                }
                is TokenTree.Punct ->
                    if (isDoubleColonAt(i)) {
                        i += 2
                        continue
                    }
                is TokenTree.Group, is TokenTree.Literal -> {}
            }
            i++
        }
        return parts
    }

    fun renderAttributeInterior(isInner: Boolean): String {
        if (trees.isEmpty()) return ""
        val prefix = if (isInner) "@file:" else "@"
        val (pathSegs, idx) = extractLeadingPathIdents(0)
        val lastPathIdent = pathSegs.lastOrNull().orEmpty()
        if (lastPathIdent.isEmpty()) {
            val fallbackBody = render()
            return if (fallbackBody.isEmpty()) "" else "$prefix$fallbackBody"
        }
        val ktName =
            if (lastPathIdent.startsWith("proc_macro") || lastPathIdent == "test") {
                rustTypeIdentToKotlin(lastPathIdent)
            } else {
                rustValueIdentToKotlin(lastPathIdent)
            }
        if (idx >= size) return "$prefix$ktName"
        val rest = trees.subList(idx, size)
        val singleParen = if (rest.size == 1) groupAt(idx, Delimiter.Parenthesis) else null
        if (singleParen != null) {
            return "$prefix$ktName(${renderCommentTokenStream(singleParen.stream())})"
        }
        return "$prefix$ktName${CommentRustTokenEmitter(rest).render()}"
    }
}

private fun mapSnakeOrForbiddenCommentWord(word: String): String {
    if (word.lowercase() in COMMENT_FORBIDDEN_STUB_WORDS) return "Note"
    if (isLowerSnakeIdentifier(word)) {
        return if (word.first().isUpperCase()) rustTypeIdentToKotlin(word) else rustValueIdentToKotlin(word)
    }
    return word
}

private fun mapRustIdentInCommentCode(raw: String): String =
    COMMENT_CODE_IDENT_MAP[raw] ?: mapSnakeOrForbiddenCommentWord(raw)

private fun mapCommentVocabularyWord(word: String, convertCamelIdentifiersOnly: Boolean): String {
    if (word.lowercase() in COMMENT_FORBIDDEN_STUB_WORDS) return "Note"
    YamlRuleCatalogs.NamingAndComments.commentProseLifetimeWords[word]?.let { return it }
    COMMENT_COMMON_VOCAB_MAP[word]?.let { return it }
    if (!convertCamelIdentifiersOnly && word == "macro_rules") return "macroRules"
    return mapSnakeOrForbiddenCommentWord(word)
}

private fun rewriteLeadingKotlinColonPrefix(text: String): String {
    val walker = KotlinTokenWalker(text)
    val kwIdx = walker.nextNonTriviaIndex(0)
    if (!walker.identTextAt(kwIdx).equals("Kotlin", ignoreCase = true)) return text
    val colonIdx = walker.nextNonTriviaIndex(kwIdx + 1)
    if (walker.punctAt(colonIdx) != ':') return text
    return text.substring(0, walker.tokens[kwIdx].start) + "Note:" + text.substring(walker.tokens[colonIdx].end)
}

private fun isLowerSnakeIdentifier(token: String): Boolean {
    if (token.isEmpty() || token == "_" || !token.contains('_')) return false
    if (token.first() == '_' || token.last() == '_') return false
    var hasLower = false
    var hasLetter = false
    for (ch in token) {
        if (ch.isLetter()) {
            hasLetter = true
            if (ch.isLowerCase()) hasLower = true
        }
    }
    return hasLetter && hasLower
}
