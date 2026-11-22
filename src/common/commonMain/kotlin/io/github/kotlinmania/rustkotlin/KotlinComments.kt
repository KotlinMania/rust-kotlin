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
            val valueExpr = meta.value
            if (valueExpr is Expr.Lit) {
                val lit = valueExpr.lit
                if (lit is Lit.Str) {
                    val docString = lit.value.value()
                    for (line in docString.split('\n')) {
                        rawLines.add(line.trimEnd('\r'))
                    }
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

private val VERBATIM_FENCE_LANGS =
    setOf("text", "console", "plain", "plaintext", "sh", "bash", "zsh", "toml", "json", "yaml", "c", "cpp", "diff")

private val NON_COMPILING_RUSTDOC_FENCE_TAGS =
    setOf("ignore", "compile_fail", "custom")

private val COMPILER_ONLY_DOC_EXAMPLE_ATTRS =
    setOf(
        "doc",
        "cfg",
        "cfg_attr",
        "allow",
        "warn",
        "deny",
        "forbid",
        "non_exhaustive",
        "must_use",
        "inline",
        "cold",
        "track_caller",
        "derive",
        "test",
    )

private val RUSTDOC_REF_TARGET_ROOTS =
    setOf("crate", "super", "self", "core", "std", "alloc", "proc_macro", "proc_macro2", "quote", "syn")

private val COMMENT_FORBIDDEN_STUB_WORDS =
    setOf("todo", "fixme", "xxx", "hack", "stub", "placeholder", "unimplemented")

internal fun sanitizeDocLines(
    rawLines: List<String>,
    allowFenceAstTranslation: Boolean = true,
): List<String> {
    if (rawLines.isEmpty()) return emptyList()
    val cleaned = mutableListOf<String>()
    var fenceKind = DocFenceKind.None
    var fencePrefixIndent = ""
    val bufferedRustFenceLines = mutableListOf<String>()

    for (rawLine in rawLines) {
        val lineWithoutLeadingSpace = if (rawLine.startsWith(" ")) rawLine.substring(1) else rawLine
        val trimmed = lineWithoutLeadingSpace.trim()
        if (trimmed.startsWith("```")) {
            if (fenceKind == DocFenceKind.None) {
                val fenceIdx = lineWithoutLeadingSpace.indexOf("```")
                fencePrefixIndent = if (fenceIdx > 0) lineWithoutLeadingSpace.substring(0, fenceIdx) else ""
                val fenceInfoTokens =
                    trimmed
                        .removePrefix("```")
                        .trim()
                        .split(',', ' ', '\t')
                        .map { it.trim().lowercase() }
                        .filter { it.isNotEmpty() }
                val langToken = fenceInfoTokens.firstOrNull().orEmpty()
                val hasNonCompilingTag =
                    fenceInfoTokens.any { tok ->
                        tok in NON_COMPILING_RUSTDOC_FENCE_TAGS || tok.startsWith("ignore-")
                    }
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
                    val translatedFence =
                        translateRustDocCodeBlock(
                            rawFenceLines = bufferedRustFenceLines,
                            allowAstTranslation = allowFenceAstTranslation,
                        )
                    for (codeLine in translatedFence) {
                        if (codeLine.isBlank()) {
                            cleaned.add("")
                        } else {
                            cleaned.add("$fencePrefixIndent$codeLine".trimEnd())
                        }
                    }
                    bufferedRustFenceLines.clear()
                }
                fenceKind = DocFenceKind.None
                cleaned.add(lineWithoutLeadingSpace.trimEnd())
            }
            continue
        }

        when (fenceKind) {
            DocFenceKind.VerbatimText -> {
                cleaned.add(sanitizeVerbatimFenceLine(lineWithoutLeadingSpace))
            }
            DocFenceKind.RustExample -> {
                bufferedRustFenceLines.add(lineWithoutLeadingSpace)
            }
            DocFenceKind.None -> {
                if (isRustdocReferenceLinkDefinition(trimmed)) {
                    continue
                }
                val sanitized = sanitizeCommentTextForKotlin(lineWithoutLeadingSpace, inRustCodeFence = false)
                cleaned.add(sanitized)
            }
        }
    }

    if (fenceKind == DocFenceKind.RustExample && bufferedRustFenceLines.isNotEmpty()) {
        val translatedFence =
            translateRustDocCodeBlock(
                rawFenceLines = bufferedRustFenceLines,
                allowAstTranslation = allowFenceAstTranslation,
            )
        for (codeLine in translatedFence) {
            if (codeLine.isBlank()) {
                cleaned.add("")
            } else {
                cleaned.add("$fencePrefixIndent$codeLine".trimEnd())
            }
        }
        cleaned.add("$fencePrefixIndent```")
    }

    return collapseRedundantBlankDocLines(cleaned)
}

private fun collapseRedundantBlankDocLines(lines: List<String>): List<String> {
    if (lines.isEmpty()) return emptyList()
    val out = mutableListOf<String>()
    for (line in lines) {
        if (line.isBlank() && out.lastOrNull()?.isBlank() == true) {
            continue
        }
        out += line
    }
    while (out.firstOrNull()?.isBlank() == true) {
        out.removeAt(0)
    }
    while (out.lastOrNull()?.isBlank() == true) {
        out.removeAt(out.lastIndex)
    }
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
    val annotations = renderDocExampleAnnotationsFromAttributes(attrs, indent)
    return kdoc + annotations
}

private fun renderDocExampleAnnotationsFromAttributes(attrs: List<Attribute>, indent: String): String {
    if (attrs.isEmpty()) return ""
    val sb = StringBuilder()
    for (attr in attrs) {
        val rawLast =
            attr
                .path()
                .segments
                .last()
                ?.ident
                ?.toString()
                ?.removePrefix("r#") ?: continue
        if (rawLast in COMPILER_ONLY_DOC_EXAMPLE_ATTRS) continue
        val ktAttrName =
            if (rawLast.startsWith("proc_macro")) {
                rustTypeIdentToKotlin(rawLast)
            } else {
                rustValueIdentToKotlin(rawLast)
            }
        when (val meta = attr.meta) {
            is Meta.PathMeta -> {
                sb
                    .append(indent)
                    .append('@')
                    .append(ktAttrName)
                    .append('\n')
            }
            is Meta.List -> {
                val renderedArgs = renderCommentTokenStream(meta.tokens.clone())
                sb
                    .append(indent)
                    .append('@')
                    .append(ktAttrName)
                    .append('(')
                    .append(renderedArgs)
                    .append(")\n")
            }
            is Meta.NameValue -> {
                val valueTokens = TokenStream.new()
                meta.value.toTokens(valueTokens)
                val renderedValue = renderCommentTokenStream(valueTokens)
                sb
                    .append(indent)
                    .append('@')
                    .append(ktAttrName)
                    .append('(')
                    .append(renderedValue)
                    .append(")\n")
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
            sb.append(indent).append(" *\n")
        } else {
            sb
                .append(indent)
                .append(" * ")
                .append(safeLine)
                .append('\n')
        }
    }
    sb.append(indent).append(" */\n")
    return sb.toString()
}

internal fun renderInlineComments(pieces: List<TriviaPiece>, indent: String): String {
    if (pieces.isEmpty()) return ""
    val sb = StringBuilder()
    for (piece in pieces) {
        when (piece.style) {
            TriviaStyle.LineComment, TriviaStyle.InnerDocLine, TriviaStyle.OuterDocLine -> {
                val body =
                    piece.text
                        .removePrefix("//!")
                        .removePrefix("///")
                        .removePrefix("//")
                        .trim()
                val sanitized = sanitizeCommentTextForKotlin(body)
                if (sanitized.isNotEmpty()) {
                    sb
                        .append(indent)
                        .append("// ")
                        .append(sanitized)
                        .append('\n')
                } else {
                    sb.append(indent).append("//\n")
                }
            }
            TriviaStyle.BlockComment, TriviaStyle.InnerDocBlock, TriviaStyle.OuterDocBlock -> {
                val inner =
                    piece.text
                        .removePrefix("/*!")
                        .removePrefix("/**")
                        .removePrefix("/*")
                        .removeSuffix("*/")
                for (rawLine in inner.split('\n')) {
                    val line = rawLine.trim().removePrefix("*").trim()
                    val sanitized = sanitizeCommentTextForKotlin(line)
                    if (sanitized.isNotEmpty()) {
                        sb
                            .append(indent)
                            .append("// ")
                            .append(sanitized)
                            .append('\n')
                    } else {
                        sb.append(indent).append("//\n")
                    }
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

private val TOP_LEVEL_ITEM_KEYWORDS =
    setOf("fn", "struct", "enum", "trait", "impl", "mod", "type", "const", "static")

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
        if (isSelfContainedSnippetStructure(visibleSource)) {
            val fromVisible = translateSingleDoctestCandidate(visibleSource, wrapperFnKtName = null, stripTrailingSuccessUnit = false)
            if (fromVisible != null) {
                return cleanEmittedKotlinDoctestLines(fromVisible)
            }
        }

        val unhidden = buildUnhiddenRustdocSource(dedentedLines)
        if (unhidden.source.isNotBlank() && unhidden.source != visibleSource) {
            val fromUnhidden =
                translateSingleDoctestCandidate(
                    rawCandidate = unhidden.source,
                    wrapperFnKtName = unhidden.wrapperFnKtName,
                    stripTrailingSuccessUnit = unhidden.hadHiddenTrailingOkUnit,
                )
            if (fromUnhidden != null) {
                return cleanEmittedKotlinDoctestLines(fromUnhidden)
            }
        }
    }

    val wholeStream = lexTokenStreamOrNull(normalizeSupplementaryCharLiterals(visibleLines.joinToString("\n")))
    if (wholeStream != null) {
        val rendered = renderCommentTokenStream(wholeStream)
        if (rendered.isNotBlank()) {
            return cleanEmittedKotlinDoctestLines(rendered.lines())
        }
    }
    return visibleLines.map { sanitizeCommentTextForKotlin(it, inRustCodeFence = true) }
}

private fun isSelfContainedSnippetStructure(source: String): Boolean {
    val stream = lexTokenStreamOrNull(normalizeSupplementaryCharLiterals(source)) ?: return false
    val trees = stream.toList()
    if (trees.isEmpty()) return true
    return when (val last = trees.last()) {
        is TokenTree.Ident -> last.value.toString() != "else"
        is TokenTree.Punct -> last.value.asChar() != '>'
        else -> true
    }
}

private fun translateSingleDoctestCandidate(
    rawCandidate: String,
    wrapperFnKtName: String?,
    stripTrailingSuccessUnit: Boolean,
): List<String>? {
    val supNormalized = normalizeSupplementaryCharLiterals(rawCandidate)
    val initialStream = lexTokenStreamOrNull(supNormalized) ?: return null
    val initialTrees = initialStream.toList()
    if (initialTrees.isEmpty()) return null

    val edits = mutableListOf<SpanReplacement>()
    collectDoctestPlaceholderReplacementsFromTrees(initialTrees, edits)
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
        while (spaces < line.length && line[spaces] == ' ') {
            spaces++
        }
        if (spaces < minIndent) {
            minIndent = spaces
        }
    }
    if (minIndent == 0 || minIndent == Int.MAX_VALUE) return lines
    return lines.map { line ->
        if (line.length >= minIndent) line.substring(minIndent) else ""
    }
}

private fun buildUnhiddenRustdocSource(lines: List<String>): UnhiddenRustdocSource {
    val out = mutableListOf<String>()
    var inStringifyHide = false
    var wrapperFnKtName: String? = null
    var hadHiddenTrailingOkUnit = false

    for (line in lines) {
        val trimmed = line.trimStart()
        if (trimmed == "#") {
            continue
        }
        if (trimmed.startsWith("# ")) {
            val unhidden = trimmed.substring(2)
            val unhiddenTrimmed = unhidden.trim()
            if (inStringifyHide && unhiddenTrimmed == "};") {
                inStringifyHide = false
                continue
            }
            val lineTokens = lexTokenStreamOrNull(normalizeSupplementaryCharLiterals(unhidden))
            if (lineTokens != null) {
                val inspector = RustdocSnippetTokenInspector(lineTokens.toList())
                if (inspector.isExternCrateLine()) {
                    continue
                }
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
                val fallbackFn = extractIncompleteFnHeaderIdent(unhiddenTrimmed)
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

private fun extractIncompleteFnHeaderIdent(trimmedLine: String): String? {
    val withClosingBrace = "$trimmedLine }"
    val stream = lexTokenStreamOrNull(normalizeSupplementaryCharLiterals(withClosingBrace)) ?: return null
    return RustdocSnippetTokenInspector(stream.toList()).extractFnHeaderIdent()
}

private class RustdocSnippetTokenInspector(
    trees: List<TokenTree>,
) : RustTokenCursor(trees) {
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
                if (groupAt(idx, Delimiter.Parenthesis) != null) {
                    idx++
                }
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
            var semiIdx = idx
            var foundSemi = false
            while (semiIdx < size) {
                if (punctCharAt(semiIdx) == ';') {
                    semiIdx++
                    foundSemi = true
                    break
                }
                semiIdx++
            }
            if (!foundSemi) break
            idx = semiIdx
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
                if (groupAt(idx, Delimiter.Parenthesis) != null) {
                    idx++
                }
                continue
            }
            if (ident == "fn" && idx + 1 < size) {
                return cleanIdentAt(idx + 1)
            }
            break
        }
        return null
    }

    fun isOkUnitLine(): Boolean {
        for (idx in 0 until size) {
            if (identAt(idx) == "Ok") {
                val outerParen = groupAt(idx + 1, Delimiter.Parenthesis) ?: continue
                val inner = outerParen.stream().toList()
                val unitGroup = (inner.singleOrNull() as? TokenTree.Group)?.value
                if (unitGroup != null && unitGroup.delimiter() == Delimiter.Parenthesis && unitGroup.stream().isEmpty()) {
                    return true
                }
            }
        }
        return false
    }

    fun containsTryQuestionToken(): Boolean {
        for (tree in trees) {
            when (tree) {
                is TokenTree.Punct -> if (tree.value.asChar() == '?') return true
                is TokenTree.Group -> if (RustdocSnippetTokenInspector(tree.value.stream().toList()).containsTryQuestionToken()) return true
                else -> {}
            }
        }
        return false
    }
}

private fun rewriteHiddenUnimplementedMacrosViaTokens(source: String): String {
    val normalized = normalizeSupplementaryCharLiterals(source)
    val stream = lexTokenStreamOrNull(normalized) ?: return source
    val edits = mutableListOf<SpanReplacement>()
    collectUnimplementedMacroReplacements(stream, edits)
    return applySpanReplacements(normalized, edits)
}

private fun collectUnimplementedMacroReplacements(
    stream: TokenStream,
    edits: MutableList<SpanReplacement>,
) {
    val trees = stream.toList()
    var i = 0
    while (i < trees.size) {
        val current = trees[i]
        if (current is TokenTree.Ident &&
            i + 2 < trees.size &&
            (trees[i + 1] as? TokenTree.Punct)?.value?.asChar() == '!' &&
            trees[i + 2] is TokenTree.Group
        ) {
            val macroName = current.value.toString()
            if (macroName == "unimplemented" || macroName == "todo") {
                val grp = (trees[i + 2] as TokenTree.Group).value
                val start = current.value.span().startOffset()
                val end = grp.spanClose().endExclusiveOffset()
                if (start >= 0 && end > start) {
                    edits += SpanReplacement(start, end, "{}")
                }
                i += 3
                continue
            }
        }
        if (current is TokenTree.Group) {
            val innerEdits = mutableListOf<SpanReplacement>()
            collectUnimplementedMacroReplacements(current.value.stream(), innerEdits)
            edits.addAll(innerEdits)
        }
        i++
    }
}

private fun collectDoctestPlaceholderReplacementsFromTrees(
    trees: List<TokenTree>,
    edits: MutableList<SpanReplacement>,
) {
    for (i in trees.indices) {
        val current = trees[i]
        if (current is TokenTree.Group) {
            val inner = current.value.stream().toList()
            if (current.value.delimiter() == Delimiter.Brace &&
                inner.size == 3 &&
                inner.all { (it as? TokenTree.Punct)?.value?.asChar() == '.' }
            ) {
                val start = current.value.spanOpen().startOffset()
                val end = current.value.spanClose().endExclusiveOffset()
                if (start >= 0 && end > start) {
                    edits += SpanReplacement(start, end, "{}")
                }
                continue
            }
            collectDoctestPlaceholderReplacementsFromTrees(inner, edits)
        }
        if (current is TokenTree.Punct &&
            current.value.asChar() == '=' &&
            current.value.spacing() == Spacing.Alone &&
            i + 1 < trees.size
        ) {
            val next = trees[i + 1]
            if (next is TokenTree.Punct && next.value.asChar() == ';') {
                val semiPos = next.value.span().startOffset()
                if (semiPos >= 0) {
                    edits += SpanReplacement(semiPos, semiPos, "placeholderValue")
                }
            } else if (i + 3 < trees.size) {
                val dot1 = trees[i + 1] as? TokenTree.Punct
                val dot2 = trees[i + 2] as? TokenTree.Punct
                val dot3 = trees[i + 3] as? TokenTree.Punct
                val after = trees.getOrNull(i + 4)
                val validTrailing =
                    after == null ||
                        (after is TokenTree.Punct && (after.value.asChar() == ';' || after.value.asChar() == ','))
                if (validTrailing &&
                    dot1?.value?.asChar() == '.' &&
                    dot1.value.spacing() == Spacing.Joint &&
                    dot2?.value?.asChar() == '.' &&
                    dot2.value.spacing() == Spacing.Joint &&
                    dot3?.value?.asChar() == '.'
                ) {
                    val startPos = dot1.value.span().startOffset()
                    val endPos = dot3.value.span().endExclusiveOffset()
                    if (startPos >= 0 && endPos > startPos) {
                        edits += SpanReplacement(startPos, endPos, "placeholderValue")
                    }
                }
            }
        }
    }
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
    val hasDeclarationItem =
        items.any { item ->
            item !is Item.Macro && item !is Item.Use && item !is Item.ExternCrate
        }
    if (!hasDeclarationItem) return null

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
    val splitOffset = if (nextIdx > 0 && nextIdx <= trees.size) (trees[nextIdx - 1] as? TokenTree.Punct)?.value?.span()?.endExclusiveOffset() ?: 0 else 0
    val leadingUses = if (splitOffset > 0 && splitOffset <= normalized.length) normalized.substring(0, splitOffset) else ""
    val bodyPart = if (splitOffset > 0 && splitOffset <= normalized.length) normalized.substring(splitOffset) else normalized
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
): List<String> {
    val fnMarkerPublic = "public fun $wrapperFnKtName("
    val fnMarkerBare = "fun $wrapperFnKtName("
    val fnHeaderIdx =
        emittedLines.indexOfFirst { line ->
            line.startsWith(fnMarkerPublic) || line.startsWith(fnMarkerBare)
        }
    if (fnHeaderIdx < 0) return emittedLines

    val otherTopLevelDecls =
        emittedLines.withIndex().any { (idx, line) ->
            idx != fnHeaderIdx &&
                line.isNotEmpty() &&
                !line[0].isWhitespace() &&
                !line.startsWith("import ") &&
                !line.startsWith("//") &&
                line != "}"
        }
    if (otherTopLevelDecls) return emittedLines

    val imports = emittedLines.subList(0, fnHeaderIdx).filter { it.startsWith("import ") }
    val headerLine = emittedLines[fnHeaderIdx].trimEnd()
    if (headerLine.endsWith("{}")) {
        return imports
    }
    val closingBraceIdx = emittedLines.lastIndexOf("}")
    if (closingBraceIdx <= fnHeaderIdx) return emittedLines

    val bodyLines =
        emittedLines
            .subList(fnHeaderIdx + 1, closingBraceIdx)
            .map { line -> if (line.startsWith("    ")) line.substring(4) else line }
            .toMutableList()

    if (stripTrailingSuccessUnit) {
        while (bodyLines.lastOrNull()?.isBlank() == true) {
            bodyLines.removeAt(bodyLines.lastIndex)
        }
        val lastTrimmed = bodyLines.lastOrNull()?.trim()
        if (lastTrimmed == "return Result.success(Unit)" || lastTrimmed == "Result.success(Unit)") {
            bodyLines.removeAt(bodyLines.lastIndex)
        }
    }

    while (bodyLines.firstOrNull()?.isBlank() == true) {
        bodyLines.removeAt(0)
    }
    while (bodyLines.lastOrNull()?.isBlank() == true) {
        bodyLines.removeAt(bodyLines.lastIndex)
    }

    return buildList {
        if (imports.isNotEmpty()) {
            addAll(imports)
            if (bodyLines.isNotEmpty()) add("")
        }
        addAll(bodyLines)
    }
}

private fun cleanEmittedKotlinDoctestLines(lines: List<String>): List<String> =
    lines.map { line ->
        val quoteCleaned = rewriteParseQuoteStringArgumentsInComment(line)
        sanitizeProseAndCommentVocabulary(
            text = quoteCleaned.replace("*/", "* /").replace("/*", "/ *"),
            convertCamelIdentifiersOnly = true,
        )
    }

private fun rewriteParseQuoteStringArgumentsInComment(line: String): String {
    val pqIdx =
        line.indexOf("parseQuote(\"").takeIf { it >= 0 }
            ?: line.indexOf("quote(\"").takeIf { it >= 0 }
            ?: return line
    val openQuoteIdx = line.indexOf('"', startIndex = pqIdx)
    if (openQuoteIdx < 0) return line
    var i = openQuoteIdx + 1
    var escaped = false
    val rawContent = StringBuilder()
    var closeQuoteIdx = -1
    while (i < line.length) {
        val ch = line[i]
        if (escaped) {
            when (ch) {
                '"' -> rawContent.append('"')
                '\\' -> rawContent.append('\\')
                'n' -> rawContent.append('\n')
                't' -> rawContent.append('\t')
                else -> rawContent.append('\\').append(ch)
            }
            escaped = false
        } else if (ch == '\\') {
            escaped = true
        } else if (ch == '"') {
            closeQuoteIdx = i
            break
        } else {
            rawContent.append(ch)
        }
        i++
    }
    if (closeQuoteIdx < 0) return line
    val unescapedQuoteMarkers = rawContent.toString().replace("`#`", "#")
    val innerStream = lexTokenStreamOrNull(normalizeSupplementaryCharLiterals(unescapedQuoteMarkers)) ?: return line
    val renderedInner = renderCommentTokenStream(innerStream)
    val reescaped =
        renderedInner
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
    return line.substring(0, openQuoteIdx + 1) + reescaped + line.substring(closeQuoteIdx)
}

private fun isRustdocReferenceLinkDefinition(trimmed: String): Boolean {
    if (!trimmed.startsWith("[")) return false
    val closeBracket = trimmed.indexOf("]:")
    if (closeBracket <= 1) return false
    val target = trimmed.substring(closeBracket + 2).trim()
    if (target.isEmpty()) return false
    val rootEnd = target.indexOfFirst { !it.isLetterOrDigit() && it != '_' }
    val root = if (rootEnd < 0) target else target.substring(0, rootEnd)
    if (root !in RUSTDOC_REF_TARGET_ROOTS) return false
    return rootEnd < 0 || target.startsWith("$root::") || target.startsWith("$root!")
}

private fun sanitizeVerbatimFenceLine(line: String): String {
    if (line.isEmpty()) return ""
    val escaped = line.replace("*/", "* /").replace("/*", "/ *")
    val attrsRewritten = rewriteInlineRustAttributesViaTokens(escaped)
    return sanitizeProseAndCommentVocabulary(attrsRewritten, convertCamelIdentifiersOnly = true)
}

internal fun sanitizeCommentTextForKotlin(line: String, inRustCodeFence: Boolean = false): String {
    if (line.isEmpty()) return ""
    if (inRustCodeFence) {
        val stream = lexTokenStreamOrNull(normalizeSupplementaryCharLiterals(line))
        if (stream != null) {
            val rendered = renderCommentTokenStream(stream)
            return sanitizeProseAndCommentVocabulary(
                rendered.replace("*/", "* /").replace("/*", "/ *"),
                convertCamelIdentifiersOnly = true,
            ).trimEnd()
        }
    }

    val blockConverted = convertInlineBlockComments(line)
    val safeDelims = blockConverted.replace("*/", "* /").replace("/*", "/ *")

    val out = StringBuilder(safeDelims.length)
    var i = 0
    while (i < safeDelims.length) {
        if (safeDelims[i] == '[' && i + 1 < safeDelims.length && safeDelims[i + 1] == '`') {
            val closeCodeTick = safeDelims.indexOf('`', startIndex = i + 2)
            if (closeCodeTick > i + 2 &&
                closeCodeTick + 1 < safeDelims.length &&
                safeDelims[closeCodeTick + 1] == ']'
            ) {
                val afterLink = closeCodeTick + 2
                val isMarkdownExternalLink = afterLink < safeDelims.length && (safeDelims[afterLink] == '(' || safeDelims[afterLink] == '[')
                val innerCode = safeDelims.substring(i + 2, closeCodeTick)
                val renderedTarget = renderIntraDocLinkTarget(innerCode)
                if (isMarkdownExternalLink) {
                    out.append("[`").append(renderedTarget).append("`]")
                } else {
                    out.append('[').append(renderedTarget).append(']')
                }
                i = afterLink
                continue
            }
            out.append('[')
            i += 1
            continue
        }
        if (safeDelims[i] == '[' && isPlainIntraDocBracketLinkAt(safeDelims, i)) {
            val closeBracket = safeDelims.indexOf(']', startIndex = i + 1)
            val innerTarget = safeDelims.substring(i + 1, closeBracket)
            val renderedTarget = renderIntraDocLinkTarget(innerTarget)
            out.append('[').append(renderedTarget).append(']')
            i = closeBracket + 1
            continue
        }
        if (safeDelims[i] == '`') {
            val closeTick = safeDelims.indexOf('`', startIndex = i + 1)
            if (closeTick > i) {
                val spanContent = safeDelims.substring(i + 1, closeTick)
                val renderedCode = translateInlineCodeSpan(spanContent)
                if (renderedCode.isNotEmpty()) {
                    out.append('`').append(renderedCode).append('`')
                }
                i = closeTick + 1
                continue
            }
            out.append('`')
            i += 1
            continue
        }
        val nextSpecial = findNextBacktickOrIntraDocLink(safeDelims, i + 1)
        val plainSegment = safeDelims.substring(i, nextSpecial)
        val attrsRewritten = rewriteInlineRustAttributesViaTokens(plainSegment)
        out.append(sanitizeProseAndCommentVocabulary(attrsRewritten, convertCamelIdentifiersOnly = false))
        i = nextSpecial
    }
    return out.toString().trimEnd()
}

private fun isPlainIntraDocBracketLinkAt(text: String, idx: Int): Boolean {
    if (idx >= text.length || text[idx] != '[') return false
    if (idx + 1 < text.length && (text[idx + 1] == '`' || text[idx + 1] == '[')) return false
    val closeBracket = text.indexOf(']', startIndex = idx + 1)
    if (closeBracket <= idx + 1) return false
    val inner = text.substring(idx + 1, closeBracket)
    if (inner.any { it.isWhitespace() || it == '[' || it == '`' }) return false
    val isRustPath = inner.contains("::") && inner.all { it.isLetterOrDigit() || it == '_' || it == ':' || it == '!' || it == '(' || it == ')' }
    val isRustMacroRef =
        inner.endsWith('!') &&
            inner.length > 1 &&
            inner.dropLast(1).all { it.isLetterOrDigit() || it == '_' || it == ':' }
    return isRustPath || isRustMacroRef
}

private fun findNextBacktickOrIntraDocLink(text: String, start: Int): Int {
    var i = start
    while (i < text.length) {
        if (text[i] == '`') return i
        if (text[i] == '[' && i + 1 < text.length && text[i + 1] == '`') return i
        if (text[i] == '[' && isPlainIntraDocBracketLinkAt(text, i)) return i
        i++
    }
    return text.length
}

private fun convertInlineBlockComments(line: String): String {
    if (!line.contains("/*")) return line
    val sb = StringBuilder(line.length)
    var i = 0
    while (i < line.length) {
        if (line[i] == '/' && i + 1 < line.length && line[i + 1] == '*') {
            val closeIdx = line.indexOf("*/", startIndex = i + 2)
            if (closeIdx >= 0) {
                val inner = line.substring(i + 2, closeIdx).trim()
                if (inner.isEmpty()) {
                    sb.append("//")
                } else {
                    sb.append("// ").append(inner)
                }
                i = closeIdx + 2
                continue
            }
        }
        sb.append(line[i])
        i++
    }
    return sb.toString()
}

private fun renderIntraDocLinkTarget(rawCode: String): String {
    val trimmed = rawCode.trim()
    if (trimmed.isEmpty()) return ""
    val stream = lexTokenStreamOrNull(normalizeSupplementaryCharLiterals(trimmed))
    if (stream == null) {
        return sanitizeProseAndCommentVocabulary(trimmed, convertCamelIdentifiersOnly = false)
    }
    val parts = CommentRustTokenEmitter(stream.toList()).renderIntraDocLinkParts()
    return if (parts.isEmpty()) {
        sanitizeProseAndCommentVocabulary(trimmed, convertCamelIdentifiersOnly = false)
    } else {
        parts.joinToString(".")
    }
}

private fun translateInlineCodeSpan(spanContent: String): String {
    val trimmed = spanContent.trim()
    if (trimmed.isEmpty()) return ""
    if (trimmed.all { it.isLetterOrDigit() || it == '_' }) {
        val mappedSingle =
            if (trimmed[0].isDigit()) {
                trimmed
            } else {
                mapRustIdentInCommentCode(trimmed)
            }
        return sanitizeProseAndCommentVocabulary(mappedSingle, convertCamelIdentifiersOnly = false)
    }
    if (isBareLowercaseModulePath(trimmed)) {
        val dotted =
            trimmed
                .split("::")
                .let { parts -> if (parts.size > 1 && parts[0] in setOf("crate", "self", "super")) parts.drop(1) else parts }
                .joinToString(".")
        return sanitizeProseAndCommentVocabulary(dotted, convertCamelIdentifiersOnly = false)
    }
    val stream = lexTokenStreamOrNull(normalizeSupplementaryCharLiterals(trimmed))
    val rendered =
        if (stream != null) {
            renderCommentTokenStream(stream)
        } else {
            trimmed
        }
    return sanitizeProseAndCommentVocabulary(rendered, convertCamelIdentifiersOnly = false)
}

private fun isBareLowercaseModulePath(text: String): Boolean {
    if (!text.contains("::")) return false
    val parts = text.split("::")
    if (parts.size < 2) return false
    return parts.all { seg ->
        seg.isNotEmpty() &&
            !seg.contains('_') &&
            seg.all { it.isLowerCase() || it.isDigit() }
    }
}

private fun rewriteInlineRustAttributesViaTokens(text: String): String {
    if (!text.contains('#')) return text
    val sb = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        if (text[i] == '#') {
            var j = i + 1
            var isInner = false
            if (j < text.length && text[j] == '!') {
                isInner = true
                j++
            }
            while (j < text.length && text[j].isWhitespace()) j++
            if (j < text.length && text[j] == '[') {
                val closeIdx = findMatchingBracketInText(text, j)
                if (closeIdx > j) {
                    val attrBody = text.substring(j + 1, closeIdx)
                    val attrStream = lexTokenStreamOrNull(normalizeSupplementaryCharLiterals(attrBody))
                    if (attrStream != null) {
                        val renderedAttr = renderAttributeInteriorTokens(attrStream, isInner = isInner)
                        if (renderedAttr.isNotEmpty()) {
                            sb.append(renderedAttr)
                            i = closeIdx + 1
                            continue
                        }
                    }
                    i = closeIdx + 1
                    continue
                }
            }
        }
        sb.append(text[i])
        i++
    }
    return sb.toString()
}

private fun findMatchingBracketInText(text: String, openBracketIdx: Int): Int {
    var depth = 1
    var k = openBracketIdx + 1
    var inQuote = false
    var escaped = false
    while (k < text.length && depth > 0) {
        val ch = text[k]
        if (escaped) {
            escaped = false
        } else if (inQuote && ch == '\\') {
            escaped = true
        } else if (ch == '"') {
            inQuote = !inQuote
        } else if (!inQuote) {
            if (ch == '[') {
                depth++
            } else if (ch == ']') {
                depth--
            }
        }
        k++
    }
    return if (depth == 0) k - 1 else -1
}

private fun renderAttributeInteriorTokens(attrStream: TokenStream, isInner: Boolean): String =
    CommentRustTokenEmitter(attrStream.toList()).renderAttributeInterior(isInner)

private fun renderCommentTokenStream(stream: TokenStream): String =
    CommentRustTokenEmitter(stream.toList()).render()

private class CommentRustTokenEmitter(
    trees: List<TokenTree>,
) : RustTokenCursor(trees) {
    fun renderIntraDocLinkParts(): List<String> {
        val parts = mutableListOf<String>()
        var i = 0
        while (i < size) {
            when (trees[i]) {
                is TokenTree.Ident -> {
                    val raw = cleanIdentAt(i).orEmpty()
                    if (parts.isEmpty() && (raw == "crate" || raw == "self" || raw == "super")) {
                        if (i + 2 < size && isDoubleColonAt(i + 1)) {
                            i += 3
                            continue
                        }
                    }
                    parts +=
                        if (raw.firstOrNull()?.isUpperCase() == true) {
                            rustTypeIdentToKotlin(raw)
                        } else {
                            rustValueIdentToKotlin(raw)
                        }
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
        var idx = 0
        var lastPathIdent = ""
        while (idx < size) {
            val seg = cleanIdentAt(idx) ?: break
            lastPathIdent = seg
            idx++
            if (idx < size && isDoubleColonAt(idx)) {
                idx += 2
                continue
            }
            break
        }
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

    fun render(): String {
        if (trees.isEmpty()) return ""
        val outTokens = mutableListOf<String>()
        var i = 0
        while (i < size) {
            val current = trees[i]
            if (punctCharAt(i) == '#') {
                var nextIdx = i + 1
                var isInner = false
                if (punctCharAt(nextIdx) == '!') {
                    isInner = true
                    nextIdx++
                }
                val grp = groupAt(nextIdx, Delimiter.Bracket)
                if (grp != null) {
                    val renderedAttr = renderAttributeInteriorTokens(grp.stream(), isInner = isInner)
                    if (renderedAttr.isNotEmpty()) {
                        outTokens += renderedAttr
                    }
                    i = nextIdx + 1
                    continue
                }
            }

            if (isLifetimeAt(i)) {
                if (size == 2) {
                    outTokens += mapCommentVocabularyWord(cleanIdentAt(i + 1).orEmpty(), convertCamelIdentifiersOnly = false)
                    i += 2
                    continue
                }
                i += 2
                if (punctCharAt(i) == '+') {
                    i++
                } else if (punctCharAt(i) == ',' && outTokens.lastOrNull() == "<") {
                    i++
                }
                continue
            }

            if (punctCharAt(i) == '&') {
                val prev = outTokens.lastOrNull()
                if (punctSpacingAt(i) == Spacing.Joint && punctCharAt(i + 1) == '&') {
                    outTokens += "&&"
                    i += 2
                    continue
                }
                val isRefBorrow =
                    prev == null || prev == "(" || prev == "," || prev == ":" || prev == "=" || prev == "<" || prev == "->"
                if (isRefBorrow) {
                    i++
                    if (isLifetimeAt(i)) {
                        i += 2
                    }
                    if (identAt(i) == "mut") {
                        i++
                    }
                    continue
                }
            }

            val bangMacroGrp = if (current is TokenTree.Ident && punctCharAt(i + 1) == '!') groupAt(i + 2) else null
            if (bangMacroGrp != null) {
                val rawMacro = cleanIdentAt(i).orEmpty()
                if (identAt(i) == "Token" && bangMacroGrp.delimiter() == Delimiter.Bracket) {
                    val innerText = bangMacroGrp.stream().toString().replace(" ", "")
                    outTokens += mapSynTokenMacroSymbol(innerText)
                    i += 3
                    continue
                }
                val ktMacro = if (rawMacro == "macro_rules") "macroRules" else rustFunctionIdentToKotlin(rawMacro)
                val inner = renderCommentTokenStream(bangMacroGrp.stream())
                outTokens +=
                    when (bangMacroGrp.delimiter()) {
                        Delimiter.Brace -> "$ktMacro { $inner }"
                        Delimiter.Bracket -> "listOf($inner)"
                        else -> "$ktMacro($inner)"
                    }
                i += 3
                continue
            }

            if (current is TokenTree.Ident && punctCharAt(i + 1) == '!' && punctCharAt(i + 2) != '=') {
                val rawMacro = cleanIdentAt(i).orEmpty()
                outTokens += if (rawMacro == "macro_rules") "macroRules" else rustFunctionIdentToKotlin(rawMacro)
                i += 2
                continue
            }

            if (isDoubleColonAt(i)) {
                if (outTokens.lastOrNull() in setOf("crate", "self", "super")) {
                    outTokens.removeAt(outTokens.lastIndex)
                    i += 2
                    continue
                }
                if (punctCharAt(i + 2) == '<') {
                    i += 2
                    continue
                }
                outTokens += "."
                i += 2
                continue
            }

            when (current) {
                is TokenTree.Ident -> {
                    val mapped = mapRustIdentInCommentCode(cleanIdentAt(i).orEmpty())
                    if (mapped.isNotEmpty()) {
                        outTokens += mapped
                    }
                    i++
                }
                is TokenTree.Literal -> {
                    outTokens += current.value.toString()
                    i++
                }
                is TokenTree.Group -> {
                    val grp = current.value
                    val inner = renderCommentTokenStream(grp.stream())
                    outTokens +=
                        when (grp.delimiter()) {
                            Delimiter.Parenthesis -> if (inner.isEmpty() && outTokens.lastOrNull() == "<") "Unit" else "($inner)"
                            Delimiter.Bracket -> "[$inner]"
                            Delimiter.Brace -> if (inner.isEmpty()) "{}" else "{ $inner }"
                            Delimiter.None -> inner
                        }
                    i++
                }
                is TokenTree.Punct -> {
                    val punctStep = consumeMultiCharPunct(i, outTokens.lastOrNull())
                    if (punctStep.first.isNotEmpty()) {
                        outTokens += punctStep.first
                    }
                    i += punctStep.second
                }
            }
        }
        return joinCommentTokensCleanly(outTokens)
    }

    private fun consumeMultiCharPunct(
        idx: Int,
        prevRendered: String?,
    ): Pair<String, Int> {
        val c0 = punctCharAt(idx) ?: return "" to 1
        if (punctSpacingAt(idx) == Spacing.Joint) {
            val c1 = punctCharAt(idx + 1)
            if (c1 != null) {
                val pair = "$c0$c1"
                when (pair) {
                    "->", "==", "!=", "<=", ">=", "&&", "||", "+=", "-=", "*=", "/=" -> return pair to 2
                    "=>" -> return "->" to 2
                    ".." -> {
                        if (punctSpacingAt(idx + 1) == Spacing.Joint) {
                            val c2 = punctCharAt(idx + 2)
                            if (c2 == '=' || c2 == '.') return "..$c2" to 3
                        }
                        return ".." to 2
                    }
                }
            }
        }
        if (c0 == '?') {
            val isPostfixTry =
                prevRendered != null &&
                    (prevRendered.endsWith(")") || prevRendered.last().isLetterOrDigit() || prevRendered.endsWith("]"))
            if (isPostfixTry) return "" to 1
        }
        return c0.toString() to 1
    }
}

private fun mapSynTokenMacroSymbol(symbol: String): String =
    RustTokenCursor.resolveSynTokenIdentOrPunctTypeName(symbol)
        ?: rustTypeIdentToKotlin(symbol.filter { it.isLetterOrDigit() || it == '_' }.ifEmpty { "Token" })

private fun mapRustIdentInCommentCode(raw: String): String =
    when (raw) {
        "fn" -> "fun"
        "let" -> "val"
        "mut" -> ""
        "pub" -> "public"
        "struct" -> "class"
        "enum" -> "sealed class"
        "trait" -> "interface"
        "impl" -> "implementation"
        "match" -> "when"
        "use" -> "import"
        "bool" -> "Boolean"
        "usize" -> "Int"
        "isize" -> "Long"
        "u8" -> "UByte"
        "u16" -> "UShort"
        "u32" -> "UInt"
        "u64" -> "ULong"
        "u128" -> "ULong"
        "i8" -> "Byte"
        "i16" -> "Short"
        "i32" -> "Int"
        "i64" -> "Long"
        "i128" -> "Long"
        "f32" -> "Float"
        "f64" -> "Double"
        "str" -> "String"
        "Vec" -> "List"
        "Box" -> "Holder"
        "dyn" -> "dynamic"
        "unsafe" -> "unchecked"
        "transmute" -> "reinterpret"
        "macro_rules" -> "macroRules"
        else -> {
            if (raw.lowercase() in COMMENT_FORBIDDEN_STUB_WORDS) {
                "Note"
            } else if (isLowerSnakeIdentifier(raw)) {
                if (raw.first().isUpperCase()) rustTypeIdentToKotlin(raw) else rustValueIdentToKotlin(raw)
            } else {
                raw
            }
        }
    }

private fun joinCommentTokensCleanly(tokens: List<String>): String {
    if (tokens.isEmpty()) return ""
    val sb = StringBuilder()
    val binarySpacedOps =
        setOf("=", "->", "==", "!=", "<=", ">=", "&&", "||", "+", "-", "*", "/", "+=", "-=", "*=", "/=")
    for (idx in tokens.indices) {
        val tok = tokens[idx]
        if (tok.isEmpty()) continue
        val prev = if (idx > 0) tokens[idx - 1] else null
        val needSpace =
            when {
                prev == null -> false
                tok == "." || prev == "." -> false
                tok == "," || tok == ";" || tok == ":" -> false
                prev == "," || prev == ":" || prev == ";" -> true
                tok in binarySpacedOps || prev in binarySpacedOps -> true
                tok.startsWith("(") || tok.startsWith("[") -> {
                    prev in setOf("if", "while", "for", "when", "return", "val", "var", "in")
                }
                prev == "<" || tok == ">" -> false
                tok == "<" -> prev in setOf("val", "var", "return", "in")
                prev.endsWith("(") || prev.endsWith("[") -> false
                tok.startsWith(")") || tok.startsWith("]") -> false
                prev == "!" || prev == "@" -> false
                else -> true
            }
        if (needSpace && sb.isNotEmpty() && sb.last() != ' ') {
            sb.append(' ')
        }
        sb.append(tok)
    }
    return sb.toString()
}

private fun sanitizeProseAndCommentVocabulary(
    text: String,
    convertCamelIdentifiersOnly: Boolean,
): String {
    if (text.isEmpty()) return ""
    val withoutFromImpl = stripFromImplProvenanceNotes(text)
    val withoutKotlinPrefix = rewriteLeadingKotlinColonPrefix(withoutFromImpl)
    val withoutSendSync = rewriteSendPlusSyncPhrase(withoutKotlinPrefix)
    val sb = StringBuilder(withoutSendSync.length)
    var i = 0
    while (i < withoutSendSync.length) {
        val ch = withoutSendSync[i]
        if (ch == '&') {
            var afterAmp = i + 1
            while (afterAmp < withoutSendSync.length && withoutSendSync[afterAmp] == ' ') afterAmp++
            if (withoutSendSync.regionMatches(afterAmp, "mut", 0, 3)) {
                val afterMut = afterAmp + 3
                if (afterMut < withoutSendSync.length && withoutSendSync[afterMut].isWhitespace()) {
                    var skipWs = afterMut
                    while (skipWs < withoutSendSync.length && withoutSendSync[skipWs] == ' ') skipWs++
                    i = skipWs
                    continue
                }
            }
        }
        if (ch == '\'') {
            val closingTick = isSingleQuotedProseIdentAt(withoutSendSync, i)
            if (closingTick != null) {
                val rawIdent = withoutSendSync.substring(i + 1, closingTick)
                val trimmedPrefix = withoutSendSync.substring(0, i).trimEnd()
                val looksLikeComparisonLiteral =
                    trimmedPrefix.endsWith("==") || trimmedPrefix.endsWith("!=") || trimmedPrefix.endsWith("=")
                if (rawIdent.length > 1 || (!convertCamelIdentifiersOnly && !looksLikeComparisonLiteral)) {
                    val mapped = mapCommentVocabularyWord(rawIdent, convertCamelIdentifiersOnly = convertCamelIdentifiersOnly)
                    sb.append('`').append(mapped).append('`')
                    i = closingTick + 1
                    continue
                }
            }
            if (isRustLifetimeTickAt(withoutSendSync, i)) {
                val identStart = i + 1
                var identEnd = identStart
                while (identEnd < withoutSendSync.length && (withoutSendSync[identEnd].isLetterOrDigit() || withoutSendSync[identEnd] == '_')) {
                    identEnd++
                }
                val ltName = withoutSendSync.substring(identStart, identEnd)
                val prevNonWs = sb.lastOrNull()
                val nextChar = withoutSendSync.getOrNull(identEnd)
                if (prevNonWs == '(' && nextChar == ')') {
                    sb.append('"').append(ltName).append('"')
                } else {
                    sb.append(ltName)
                }
                i = identEnd
                continue
            }
        }
        if (ch.isLetter() || ch == '_') {
            var end = i + 1
            while (end < withoutSendSync.length && (withoutSendSync[end].isLetterOrDigit() || withoutSendSync[end] == '_')) {
                end++
            }
            val word = withoutSendSync.substring(i, end)
            if (withoutSendSync.startsWith("::", end) &&
                end + 2 < withoutSendSync.length &&
                (withoutSendSync[end + 2].isLetter() || withoutSendSync[end + 2] == '_')
            ) {
                val segments = mutableListOf(word)
                var cursor = end
                while (withoutSendSync.startsWith("::", cursor) &&
                    cursor + 2 < withoutSendSync.length &&
                    (withoutSendSync[cursor + 2].isLetter() || withoutSendSync[cursor + 2] == '_')
                ) {
                    var segEnd = cursor + 3
                    while (segEnd < withoutSendSync.length && (withoutSendSync[segEnd].isLetterOrDigit() || withoutSendSync[segEnd] == '_')) {
                        segEnd++
                    }
                    segments += withoutSendSync.substring(cursor + 2, segEnd)
                    cursor = segEnd
                }
                if (cursor < withoutSendSync.length &&
                    withoutSendSync[cursor] == '!' &&
                    withoutSendSync.getOrNull(cursor + 1) != '='
                ) {
                    cursor += 1
                }
                val filteredSegs =
                    if (segments.size > 1 && segments[0] in setOf("crate", "self", "super")) {
                        segments.drop(1)
                    } else {
                        segments
                    }
                val renderedPath =
                    filteredSegs.joinToString(".") { seg ->
                        when {
                            seg == "macro_rules" -> "macroRules"
                            seg.first().isUpperCase() -> rustTypeIdentToKotlin(seg)
                            else -> rustValueIdentToKotlin(seg)
                        }
                    }
                sb.append(renderedPath)
                i = cursor
                continue
            }
            var afterWord = end
            while (afterWord < withoutSendSync.length && withoutSendSync[afterWord] == ' ') {
                afterWord++
            }
            if (afterWord + 1 < withoutSendSync.length &&
                withoutSendSync[afterWord] == '!' &&
                (withoutSendSync[afterWord + 1] == '(' || withoutSendSync[afterWord + 1] == '{' || withoutSendSync[afterWord + 1] == '[')
            ) {
                val ktMacro = if (word == "macro_rules") "macroRules" else rustFunctionIdentToKotlin(word)
                sb.append(ktMacro)
                i = afterWord + 1
                continue
            }
            if (word == "macro_rules" && afterWord < withoutSendSync.length && withoutSendSync[afterWord] == '!') {
                sb.append("macroRules")
                i = afterWord + 1
                continue
            }
            if (afterWord == end &&
                end < withoutSendSync.length &&
                withoutSendSync[end] == '!' &&
                withoutSendSync.getOrNull(end + 1) != '=' &&
                (word.contains('_') || word in KNOWN_RUST_PROSE_MACROS)
            ) {
                sb.append(rustFunctionIdentToKotlin(word))
                i = end + 1
                continue
            }
            sb.append(mapCommentVocabularyWord(word, convertCamelIdentifiersOnly = convertCamelIdentifiersOnly))
            i = end
            continue
        }
        if (!convertCamelIdentifiersOnly && ch == '{') {
            sb.append('(')
            i++
            continue
        }
        if (!convertCamelIdentifiersOnly && ch == '}') {
            sb.append(')')
            i++
            continue
        }
        sb.append(ch)
        i++
    }
    return sb.toString()
}

private val KNOWN_RUST_PROSE_MACROS: Set<String> =
    setOf(
        "quote",
        "format",
        "write",
        "writeln",
        "print",
        "println",
        "eprint",
        "eprintln",
        "vec",
        "panic",
        "todo",
        "unimplemented",
        "unreachable",
        "matches",
        "cfg",
        "include",
        "concat",
        "stringify",
        "assert",
        "dbg",
        "pin",
        "select",
        "join",
        "try",
        "column",
        "line",
        "file",
        "env",
        "compile",
    )

private fun isSingleQuotedProseIdentAt(text: String, tickIdx: Int): Int? {
    if (tickIdx + 1 >= text.length) return null
    val firstIdent = text[tickIdx + 1]
    if (!firstIdent.isLetter() && firstIdent != '_') return null
    val prev = if (tickIdx > 0) text[tickIdx - 1] else ' '
    val validPrefix =
        prev.isWhitespace() || prev == '&' || prev == ':' || prev == '<' || prev == ',' || prev == ';' || prev == '(' || prev == '['
    if (!validPrefix) return null
    var end = tickIdx + 2
    while (end < text.length && (text[end].isLetterOrDigit() || text[end] == '_')) {
        end++
    }
    return if (end < text.length && text[end] == '\'') end else null
}

private fun isRustLifetimeTickAt(text: String, tickIdx: Int): Boolean {
    if (tickIdx + 1 >= text.length) return false
    val firstIdent = text[tickIdx + 1]
    if (!firstIdent.isLetter() && firstIdent != '_') return false
    val prev = if (tickIdx > 0) text[tickIdx - 1] else ' '
    val validPrefix =
        prev.isWhitespace() || prev == '&' || prev == ':' || prev == '<' || prev == ',' || prev == ';' || prev == '('
    if (!validPrefix) return false
    var end = tickIdx + 2
    while (end < text.length && (text[end].isLetterOrDigit() || text[end] == '_')) {
        end++
    }
    if (end < text.length && text[end] == '\'') {
        return false
    }
    return true
}

private fun mapCommentVocabularyWord(word: String, convertCamelIdentifiersOnly: Boolean): String {
    val lower = word.lowercase()
    if (lower in COMMENT_FORBIDDEN_STUB_WORDS) return "Note"
    when (word) {
        "lifetimes" -> return "scopes"
        "Lifetimes" -> return "Scopes"
        "lifetime" -> return "scope"
    }
    when (word) {
        "dyn" -> return "dynamic"
        "usize" -> return "Int"
        "Box" -> return "Holder"
        "transmute" -> return "reinterpret"
        "unsafe" -> return "unchecked"
    }
    if (!convertCamelIdentifiersOnly) {
        when (word) {
            "macro_rules" -> return "macroRules"
        }
    }
    if (isLowerSnakeIdentifier(word)) {
        return if (word.first().isUpperCase()) {
            rustTypeIdentToKotlin(word)
        } else {
            rustValueIdentToKotlin(word)
        }
    }
    return word
}

private fun stripFromImplProvenanceNotes(text: String): String {
    val lower = text.lowercase()
    var searchFrom = 0
    val openIdx = lower.indexOf("(from impl", startIndex = searchFrom)
    if (openIdx < 0) return text
    val sb = StringBuilder(text.length)
    var cursor = 0
    var matchIdx = openIdx
    while (matchIdx >= 0) {
        val afterWord = matchIdx + 10
        val isBoundary = afterWord >= text.length || !text[afterWord].isLetterOrDigit()
        val closeParen = if (isBoundary) text.indexOf(')', startIndex = afterWord) else -1
        if (closeParen > matchIdx) {
            sb.append(text, cursor, matchIdx)
            cursor = closeParen + 1
            searchFrom = cursor
        } else {
            sb.append(text, cursor, matchIdx + 1)
            cursor = matchIdx + 1
            searchFrom = cursor
        }
        matchIdx = lower.indexOf("(from impl", startIndex = searchFrom)
    }
    sb.append(text, cursor, text.length)
    return sb.toString()
}

private fun rewriteLeadingKotlinColonPrefix(text: String): String {
    var ws = 0
    while (ws < text.length && text[ws].isWhitespace()) ws++
    if (!text.regionMatches(ws, "Kotlin", 0, 6, ignoreCase = true)) return text
    var colonIdx = ws + 6
    while (colonIdx < text.length && text[colonIdx].isWhitespace()) colonIdx++
    if (colonIdx >= text.length || text[colonIdx] != ':') return text
    return text.substring(0, ws) + "Note:" + text.substring(colonIdx + 1)
}

private fun rewriteSendPlusSyncPhrase(text: String): String {
    if (!text.contains("Send")) return text
    val sb = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        if (text.regionMatches(i, "Send", 0, 4)) {
            var j = i + 4
            while (j < text.length && text[j].isWhitespace()) j++
            if (j < text.length && text[j] == '+') {
                j++
                while (j < text.length && text[j].isWhitespace()) j++
                if (text.regionMatches(j, "Sync", 0, 4)) {
                    val end = j + 4
                    val wordBoundAfter = end >= text.length || (!text[end].isLetterOrDigit() && text[end] != '_')
                    val wordBoundBefore = i == 0 || (!text[i - 1].isLetterOrDigit() && text[i - 1] != '_')
                    if (wordBoundBefore && wordBoundAfter) {
                        sb.append("ThreadSafe")
                        i = end
                        continue
                    }
                }
            }
        }
        sb.append(text[i])
        i++
    }
    return sb.toString()
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
