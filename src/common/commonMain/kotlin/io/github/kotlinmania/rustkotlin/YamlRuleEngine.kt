package io.github.kotlinmania.rustkotlin

/**
 * Pure-KMP YAML AST, strict comment-aware parser, and pre-compiled declarative
 * pattern-matching / template-replacement engine for data-driven Rust-to-Kotlin rules.
 */
internal sealed class YamlNode {
    class Scalar(
        val value: String,
    ) : YamlNode()

    class Sequence(
        val items: List<YamlNode>,
    ) : YamlNode()

    class Mapping(
        val entries: Map<String, YamlNode>,
    ) : YamlNode() {
        fun requireNode(key: String): YamlNode =
            entries[key] ?: throw IllegalArgumentException("Missing required YAML key '$key' (available: ${entries.keys})")

        fun mapping(key: String): Mapping =
            requireNode(key) as? Mapping
                ?: throw IllegalArgumentException("Expected YAML mapping at key '$key'")

        fun optionalMapping(key: String): Mapping? =
            entries[key]?.let {
                it as? Mapping ?: throw IllegalArgumentException("Expected YAML mapping at key '$key'")
            }

        fun sequence(key: String): List<YamlNode> =
            (requireNode(key) as? Sequence)?.items
                ?: throw IllegalArgumentException("Expected YAML sequence at key '$key'")

        fun mappingList(key: String): List<Mapping> =
            (entries[key] as? Sequence)?.items.orEmpty().mapIndexed { idx, item ->
                item as? Mapping ?: throw IllegalArgumentException("Expected YAML mapping at '$key[$idx]'")
            }

        fun string(key: String): String =
            (requireNode(key) as? Scalar)?.value
                ?: throw IllegalArgumentException("Expected YAML scalar string at key '$key'")

        fun optionalString(key: String): String? =
            entries[key]?.let {
                (it as? Scalar)?.value ?: throw IllegalArgumentException("Expected YAML scalar string at key '$key'")
            }

        fun int(key: String): Int =
            string(key).toIntOrNull()
                ?: throw IllegalArgumentException("Expected integer scalar at key '$key', got '${string(key)}'")

        fun optionalInt(key: String): Int? =
            optionalString(key)?.let { raw ->
                raw.toIntOrNull() ?: throw IllegalArgumentException("Expected integer scalar at key '$key', got '$raw'")
            }

        fun bool(key: String, default: Boolean = false): Boolean {
            val raw = optionalString(key) ?: return default
            return when (raw) {
                "true" -> true
                "false" -> false
                else -> throw IllegalArgumentException("Expected boolean 'true' or 'false' at key '$key', got '$raw'")
            }
        }

        fun explicitStringList(key: String): List<String> =
            when (val node = entries[key]) {
                null -> emptyList()
                is Scalar -> if (node.value.isEmpty()) emptyList() else listOf(node.value)
                is Sequence ->
                    node.items.mapIndexed { idx, item ->
                        (item as? Scalar)?.value
                            ?: throw IllegalArgumentException("Expected YAML scalar at '$key[$idx]'")
                    }
                is Mapping -> throw IllegalArgumentException("Expected YAML sequence or scalar at '$key'")
            }

        fun wordSet(key: String): Set<String> {
            val out = LinkedHashSet<String>()
            when (val node = requireNode(key)) {
                is Scalar -> appendSplitWords(node.value, out)
                is Sequence ->
                    for ((idx, item) in node.items.withIndex()) {
                        val scalar =
                            item as? Scalar
                                ?: throw IllegalArgumentException("Expected YAML scalar in wordSet '$key[$idx]'")
                        appendSplitWords(scalar.value, out)
                    }
                is Mapping -> throw IllegalArgumentException("Expected YAML scalar or sequence for wordSet '$key'")
            }
            return out
        }

        fun optionalWordSet(key: String): Set<String> =
            if (key in entries) wordSet(key) else emptySet()

        fun stringMap(key: String): Map<String, String> {
            val mapNode = mapping(key)
            val out = LinkedHashMap<String, String>(mapNode.entries.size)
            for ((k, v) in mapNode.entries) {
                val scalar =
                    v as? Scalar
                        ?: throw IllegalArgumentException("Expected YAML scalar at '$key.$k'")
                out[k] = scalar.value
            }
            return out
        }

        fun groupedTargetMap(key: String): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            for (group in mappingList(key)) {
                val value = group.string("value")
                for (k in group.wordSet("keys")) {
                    out[k] = value
                }
            }
            return out
        }

        fun intKeyedWordGroups(key: String): Map<String, Int> {
            val mapNode = mapping(key)
            val out = LinkedHashMap<String, Int>()
            for ((intKey, v) in mapNode.entries) {
                val arity =
                    intKey.toIntOrNull()
                        ?: throw IllegalArgumentException("Expected integer key in '$key', got '$intKey'")
                val words = LinkedHashSet<String>()
                when (v) {
                    is Scalar -> appendSplitWords(v.value, words)
                    is Sequence ->
                        for (item in v.items) {
                            val s = item as? Scalar ?: throw IllegalArgumentException("Expected scalar in '$key.$intKey'")
                            appendSplitWords(s.value, words)
                        }
                    is Mapping -> throw IllegalArgumentException("Expected scalar or sequence in '$key.$intKey'")
                }
                for (w in words) out[w] = arity
            }
            return out
        }

        private fun appendSplitWords(text: String, sink: MutableSet<String>) {
            for (part in text.split(' ', '\n', '\r', '\t')) {
                if (part.isNotEmpty()) sink.add(part)
            }
        }
    }
}

internal object YamlDocumentParser {
    private data class RawLine(
        val lineNo: Int,
        val rawText: String,
        val indent: Int,
        val strippedContent: String,
    )

    fun parse(source: String, sourceName: String = "<yaml>"): YamlNode.Mapping {
        val rawLines = preprocessLines(source, sourceName)
        val firstIdx = nextMeaningfulLine(rawLines, 0)
        if (firstIdx >= rawLines.size) return YamlNode.Mapping(emptyMap())
        val (root, nextIdx) = parseBlock(rawLines, firstIdx, rawLines[firstIdx].indent, sourceName)
        val trailingIdx = nextMeaningfulLine(rawLines, nextIdx)
        if (trailingIdx < rawLines.size) {
            fail(sourceName, rawLines[trailingIdx].lineNo, "Unexpected trailing content at indent ${rawLines[trailingIdx].indent}")
        }
        return root as? YamlNode.Mapping
            ?: fail(sourceName, rawLines[firstIdx].lineNo, "Top-level YAML document must be a mapping")
    }

    private fun preprocessLines(source: String, sourceName: String): List<RawLine> {
        val split = source.split('\n')
        val out = ArrayList<RawLine>(split.size)
        for ((idx, rawWithCr) in split.withIndex()) {
            val lineNo = idx + 1
            val raw = rawWithCr.trimEnd('\r')
            var indent = 0
            while (indent < raw.length && (raw[indent] == ' ' || raw[indent] == '\t')) {
                if (raw[indent] == '\t') {
                    fail(sourceName, lineNo, "Tab indentation is not allowed in YAML")
                }
                indent++
            }
            val stripped = stripCommentsOutsideQuotes(raw.substring(indent))
            val content = if (stripped == "---" || stripped == "...") "" else stripped
            out.add(RawLine(lineNo = lineNo, rawText = raw, indent = indent, strippedContent = content))
        }
        return out
    }

    private fun stripCommentsOutsideQuotes(content: String): String {
        var inSingle = false
        var inDouble = false
        var i = 0
        while (i < content.length) {
            val ch = content[i]
            when {
                inSingle -> {
                    if (ch == '\'') {
                        if (i + 1 < content.length && content[i + 1] == '\'') {
                            i += 2
                            continue
                        }
                        inSingle = false
                    }
                }
                inDouble -> {
                    if (ch == '\\' && i + 1 < content.length) {
                        i += 2
                        continue
                    }
                    if (ch == '"') inDouble = false
                }
                else -> {
                    when (ch) {
                        '\'' -> inSingle = true
                        '"' -> inDouble = true
                        '#' ->
                            if (i == 0 || content[i - 1].isWhitespace()) {
                                return content.substring(0, i).trimEnd()
                            }
                    }
                }
            }
            i++
        }
        return content.trimEnd()
    }

    private fun nextMeaningfulLine(lines: List<RawLine>, fromIdx: Int): Int {
        var idx = fromIdx
        while (idx < lines.size && lines[idx].strippedContent.isEmpty()) idx++
        return idx
    }

    private fun parseBlock(
        lines: List<RawLine>,
        startIdx: Int,
        expectedIndent: Int,
        sourceName: String,
    ): Pair<YamlNode, Int> {
        val first = lines[startIdx]
        if (first.indent != expectedIndent) {
            fail(sourceName, first.lineNo, "Expected indentation $expectedIndent, found ${first.indent}")
        }
        return if (isSequenceItemContent(first.strippedContent)) {
            parseBlockSequence(lines, startIdx, expectedIndent, sourceName)
        } else {
            parseBlockMapping(lines, startIdx, expectedIndent, sourceName)
        }
    }

    private fun isSequenceItemContent(content: String): Boolean =
        content == "-" || content.startsWith("- ")

    private fun parseBlockSequence(
        lines: List<RawLine>,
        startIdx: Int,
        seqIndent: Int,
        sourceName: String,
    ): Pair<YamlNode.Sequence, Int> {
        val items = ArrayList<YamlNode>()
        var idx = startIdx
        while (true) {
            idx = nextMeaningfulLine(lines, idx)
            if (idx >= lines.size) break
            val line = lines[idx]
            if (line.indent < seqIndent) break
            if (line.indent > seqIndent) {
                fail(sourceName, line.lineNo, "Unexpected indentation ${line.indent} inside sequence at indent $seqIndent")
            }
            if (!isSequenceItemContent(line.strippedContent)) break
            if (line.strippedContent == "-") {
                val childIdx = nextMeaningfulLine(lines, idx + 1)
                if (childIdx >= lines.size || lines[childIdx].indent <= seqIndent) {
                    fail(sourceName, line.lineNo, "Empty '-' sequence entry must be followed by an indented block")
                }
                val (childNode, afterChild) = parseBlock(lines, childIdx, lines[childIdx].indent, sourceName)
                items.add(childNode)
                idx = afterChild
                continue
            }

            var contentOffset = seqIndent + 1
            while (contentOffset < line.rawText.length && line.rawText[contentOffset] == ' ') contentOffset++
            val afterDash = line.strippedContent.substring(2).trimStart()
            val inlineMappingSplit = splitMappingEntry(afterDash, line.lineNo, sourceName, required = false)
            if (inlineMappingSplit != null) {
                val (itemMap, afterMap) =
                    parseInlineStartedMapping(
                        lines = lines,
                        firstLine = line,
                        firstEntry = inlineMappingSplit,
                        mapIndent = contentOffset,
                        sourceName = sourceName,
                        nextLineStart = idx + 1,
                    )
                items.add(itemMap)
                idx = afterMap
            } else {
                items.add(parseInlineValue(afterDash, line.lineNo, sourceName))
                idx++
            }
        }
        return YamlNode.Sequence(items) to idx
    }

    private fun parseInlineStartedMapping(
        lines: List<RawLine>,
        firstLine: RawLine,
        firstEntry: Pair<String, String>,
        mapIndent: Int,
        sourceName: String,
        nextLineStart: Int,
    ): Pair<YamlNode.Mapping, Int> {
        val entries = LinkedHashMap<String, YamlNode>()
        val (firstKey, firstRawVal) = firstEntry
        val (firstNode, afterFirstVal) =
            resolveMappingEntryValue(
                lines = lines,
                entryLineNo = firstLine.lineNo,
                parentIndent = mapIndent,
                rawVal = firstRawVal,
                nextIdx = nextLineStart,
                sourceName = sourceName,
            )
        entries[firstKey] = firstNode
        return parseRemainingMappingEntries(lines, afterFirstVal, mapIndent, entries, sourceName)
    }

    private fun parseBlockMapping(
        lines: List<RawLine>,
        startIdx: Int,
        mapIndent: Int,
        sourceName: String,
    ): Pair<YamlNode.Mapping, Int> =
        parseRemainingMappingEntries(lines, startIdx, mapIndent, LinkedHashMap(), sourceName)

    private fun parseRemainingMappingEntries(
        lines: List<RawLine>,
        startIdx: Int,
        mapIndent: Int,
        entries: LinkedHashMap<String, YamlNode>,
        sourceName: String,
    ): Pair<YamlNode.Mapping, Int> {
        var idx = startIdx
        while (true) {
            idx = nextMeaningfulLine(lines, idx)
            if (idx >= lines.size) break
            val line = lines[idx]
            if (line.indent < mapIndent) break
            if (line.indent > mapIndent) {
                fail(sourceName, line.lineNo, "Unexpected indentation ${line.indent} inside mapping at indent $mapIndent")
            }
            if (isSequenceItemContent(line.strippedContent)) {
                fail(sourceName, line.lineNo, "Unexpected sequence item '-' inside mapping at indent $mapIndent")
            }
            val (key, rawVal) = splitMappingEntry(line.strippedContent, line.lineNo, sourceName, required = true)!!
            if (key in entries) {
                fail(sourceName, line.lineNo, "Duplicate YAML key '$key'")
            }
            val (valueNode, afterValueIdx) =
                resolveMappingEntryValue(
                    lines = lines,
                    entryLineNo = line.lineNo,
                    parentIndent = mapIndent,
                    rawVal = rawVal,
                    nextIdx = idx + 1,
                    sourceName = sourceName,
                )
            entries[key] = valueNode
            idx = afterValueIdx
        }
        return YamlNode.Mapping(entries) to idx
    }

    private fun resolveMappingEntryValue(
        lines: List<RawLine>,
        entryLineNo: Int,
        parentIndent: Int,
        rawVal: String,
        nextIdx: Int,
        sourceName: String,
    ): Pair<YamlNode, Int> {
        if (rawVal == "|" || rawVal == "|-" || rawVal == ">" || rawVal == ">-") {
            val fold = rawVal.startsWith(">")
            val stripFinalNewline = rawVal.endsWith("-")
            return parseBlockScalar(lines, nextIdx, parentIndent, fold, stripFinalNewline, entryLineNo, sourceName)
        }
        if (rawVal.isEmpty()) {
            val childIdx = nextMeaningfulLine(lines, nextIdx)
            if (childIdx >= lines.size || lines[childIdx].indent <= parentIndent) {
                fail(sourceName, entryLineNo, "Key with empty inline value must be followed by an indented block (use \"\", [], or {} for empty values)")
            }
            return parseBlock(lines, childIdx, lines[childIdx].indent, sourceName)
        }
        return parseInlineValue(rawVal, entryLineNo, sourceName) to nextIdx
    }

    private fun parseBlockScalar(
        lines: List<RawLine>,
        startIdx: Int,
        parentIndent: Int,
        fold: Boolean,
        stripFinalNewline: Boolean,
        headerLineNo: Int,
        sourceName: String,
    ): Pair<YamlNode.Scalar, Int> {
        var idx = startIdx
        var blockIndent = -1
        val collected = ArrayList<String>()
        while (idx < lines.size) {
            val raw = lines[idx].rawText
            if (raw.isBlank()) {
                collected.add("")
                idx++
                continue
            }
            val lineIndent = lines[idx].indent
            if (lineIndent <= parentIndent) break
            if (blockIndent < 0) blockIndent = lineIndent
            if (lineIndent < blockIndent) {
                fail(sourceName, lines[idx].lineNo, "Inconsistent indentation inside multiline scalar")
            }
            collected.add(raw.substring(blockIndent))
            idx++
        }
        while (collected.isNotEmpty() && collected.last().isEmpty()) {
            collected.removeAt(collected.lastIndex)
        }
        if (collected.isEmpty()) {
            fail(sourceName, headerLineNo, "Multiline block scalar must contain at least one indented line")
        }
        val body =
            if (!fold) {
                collected.joinToString("\n")
            } else {
                val sb = StringBuilder()
                for ((lineIdx, part) in collected.withIndex()) {
                    if (lineIdx > 0) {
                        if (part.isEmpty() || collected[lineIdx - 1].isEmpty()) sb.append('\n') else sb.append(' ')
                    }
                    sb.append(part)
                }
                sb.toString()
            }
        val finalText = if (stripFinalNewline) body else "$body\n"
        return YamlNode.Scalar(finalText) to idx
    }

    private fun parseInlineValue(raw: String, lineNo: Int, sourceName: String): YamlNode {
        val trimmed = raw.trim()
        if (trimmed.startsWith("[")) {
            if (!trimmed.endsWith("]")) {
                fail(sourceName, lineNo, "Unterminated flow sequence '$trimmed'")
            }
            val inner = trimmed.substring(1, trimmed.length - 1).trim()
            if (inner.isEmpty()) return YamlNode.Sequence(emptyList())
            val parts = splitTopLevelCommaItems(inner, lineNo, sourceName)
            return YamlNode.Sequence(parts.map { parseInlineValue(it, lineNo, sourceName) })
        }
        if (trimmed.startsWith("{")) {
            if (!trimmed.endsWith("}")) {
                fail(sourceName, lineNo, "Unterminated flow mapping '$trimmed'")
            }
            val inner = trimmed.substring(1, trimmed.length - 1).trim()
            if (inner.isEmpty()) return YamlNode.Mapping(emptyMap())
            val parts = splitTopLevelCommaItems(inner, lineNo, sourceName)
            val map = LinkedHashMap<String, YamlNode>(parts.size)
            for (part in parts) {
                val (k, v) = splitMappingEntry(part, lineNo, sourceName, required = true)!!
                if (k in map) fail(sourceName, lineNo, "Duplicate key '$k' in flow mapping")
                if (v.isEmpty()) fail(sourceName, lineNo, "Flow mapping key '$k' requires an explicit value")
                map[k] = parseInlineValue(v, lineNo, sourceName)
            }
            return YamlNode.Mapping(map)
        }
        return YamlNode.Scalar(parseScalar(trimmed, lineNo, sourceName))
    }

    private fun splitTopLevelCommaItems(inner: String, lineNo: Int, sourceName: String): List<String> {
        val out = ArrayList<String>()
        var inSingle = false
        var inDouble = false
        var bracketDepth = 0
        var braceDepth = 0
        var segStart = 0
        var i = 0
        while (i < inner.length) {
            val ch = inner[i]
            when {
                inSingle -> {
                    if (ch == '\'') {
                        if (i + 1 < inner.length && inner[i + 1] == '\'') {
                            i += 2
                            continue
                        }
                        inSingle = false
                    }
                }
                inDouble -> {
                    if (ch == '\\' && i + 1 < inner.length) {
                        i += 2
                        continue
                    }
                    if (ch == '"') inDouble = false
                }
                else ->
                    when (ch) {
                        '\'' -> inSingle = true
                        '"' -> inDouble = true
                        '[' -> bracketDepth++
                        ']' -> bracketDepth--
                        '{' -> braceDepth++
                        '}' -> braceDepth--
                        ',' ->
                            if (bracketDepth == 0 && braceDepth == 0) {
                                val piece = inner.substring(segStart, i).trim()
                                if (piece.isNotEmpty()) out.add(piece)
                                segStart = i + 1
                            }
                    }
            }
            i++
        }
        if (inSingle || inDouble || bracketDepth != 0 || braceDepth != 0) {
            fail(sourceName, lineNo, "Unbalanced quotes or brackets in '$inner'")
        }
        val tail = inner.substring(segStart).trim()
        if (tail.isNotEmpty()) out.add(tail)
        return out
    }

    private fun splitMappingEntry(
        content: String,
        lineNo: Int,
        sourceName: String,
        required: Boolean,
    ): Pair<String, String>? {
        var inSingle = false
        var inDouble = false
        var bracketDepth = 0
        var braceDepth = 0
        var i = 0
        while (i < content.length) {
            val ch = content[i]
            when {
                inSingle -> {
                    if (ch == '\'') {
                        if (i + 1 < content.length && content[i + 1] == '\'') {
                            i += 2
                            continue
                        }
                        inSingle = false
                    }
                }
                inDouble -> {
                    if (ch == '\\' && i + 1 < content.length) {
                        i += 2
                        continue
                    }
                    if (ch == '"') inDouble = false
                }
                else ->
                    when (ch) {
                        '\'' -> inSingle = true
                        '"' -> inDouble = true
                        '[' -> bracketDepth++
                        ']' -> if (bracketDepth > 0) bracketDepth--
                        '{' -> braceDepth++
                        '}' -> if (braceDepth > 0) braceDepth--
                        ':' ->
                            if (bracketDepth == 0 && braceDepth == 0 && (i + 1 == content.length || content[i + 1] == ' ')) {
                                val rawKey = content.substring(0, i).trim()
                                if (rawKey.isEmpty()) {
                                    fail(sourceName, lineNo, "Empty mapping key in '$content'")
                                }
                                val key = parseScalar(rawKey, lineNo, sourceName)
                                val rawVal = content.substring(i + 1).trim()
                                return key to rawVal
                            }
                    }
            }
            i++
        }
        if (required) {
            fail(sourceName, lineNo, "Expected 'key: value' mapping entry, got '$content'")
        }
        return null
    }

    private fun parseScalar(raw: String, lineNo: Int, sourceName: String): String {
        if (raw.startsWith('"')) {
            if (raw.length < 2 || !raw.endsWith('"')) {
                fail(sourceName, lineNo, "Unterminated double-quoted string: $raw")
            }
            val sb = StringBuilder(raw.length - 2)
            var i = 1
            val end = raw.length - 1
            while (i < end) {
                val ch = raw[i]
                if (ch == '\\') {
                    if (i + 1 >= end) fail(sourceName, lineNo, "Trailing backslash in string: $raw")
                    when (val esc = raw[i + 1]) {
                        '\\' -> sb.append('\\')
                        '"' -> sb.append('"')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        '$' -> sb.append('$')
                        '/' -> sb.append('/')
                        '0' -> sb.append('\u0000')
                        'u' -> {
                            if (i + 6 > end) fail(sourceName, lineNo, "Invalid \\uXXXX escape in string: $raw")
                            val hex = raw.substring(i + 2, i + 6)
                            val code = hex.toIntOrNull(16) ?: fail(sourceName, lineNo, "Invalid \\u$hex escape in string: $raw")
                            sb.append(code.toChar())
                            i += 6
                            continue
                        }
                        else -> fail(sourceName, lineNo, "Unsupported escape '\\$esc' in double-quoted YAML string: $raw")
                    }
                    i += 2
                } else if (ch == '"') {
                    fail(sourceName, lineNo, "Unescaped '\"' inside double-quoted YAML string: $raw")
                } else {
                    sb.append(ch)
                    i++
                }
            }
            return sb.toString()
        }
        if (raw.startsWith('\'')) {
            if (raw.length < 2 || !raw.endsWith('\'')) {
                fail(sourceName, lineNo, "Unterminated single-quoted string: $raw")
            }
            val sb = StringBuilder(raw.length - 2)
            var i = 1
            val end = raw.length - 1
            while (i < end) {
                val ch = raw[i]
                if (ch == '\'') {
                    if (i + 1 < end && raw[i + 1] == '\'') {
                        sb.append('\'')
                        i += 2
                        continue
                    }
                    fail(sourceName, lineNo, "Unescaped single quote inside single-quoted YAML string: $raw")
                }
                sb.append(ch)
                i++
            }
            return sb.toString()
        }
        return raw
    }

    private fun fail(sourceName: String, lineNo: Int, message: String): Nothing =
        throw IllegalArgumentException("YAML error in $sourceName:$lineNo: $message")
}

/**
 * Pre-compiled `{placeholder}` template supporting `{{` and `}}` literal brace escapes.
 */
internal class YamlTemplate private constructor(
    private val segments: List<Segment>,
    val placeholders: Set<String>,
) {
    private sealed class Segment {
        class Literal(
            val text: String,
        ) : Segment()

        class Placeholder(
            val key: String,
        ) : Segment()
    }

    fun render(lookup: (String) -> String?): String {
        if (segments.isEmpty()) return ""
        if (segments.size == 1 && segments[0] is Segment.Literal) {
            return (segments[0] as Segment.Literal).text
        }
        val sb = StringBuilder()
        for (seg in segments) {
            when (seg) {
                is Segment.Literal -> sb.append(seg.text)
                is Segment.Placeholder -> {
                    val value =
                        lookup(seg.key)
                            ?: throw IllegalArgumentException("Unbound YAML template placeholder '{${seg.key}}'")
                    sb.append(value)
                }
            }
        }
        return sb.toString()
    }

    companion object {
        fun compile(pattern: String): YamlTemplate {
            val segments = ArrayList<Segment>()
            val placeholders = LinkedHashSet<String>()
            val literalBuf = StringBuilder()
            var i = 0
            while (i < pattern.length) {
                val ch = pattern[i]
                if (ch == '{') {
                    if (i + 1 < pattern.length && pattern[i + 1] == '{') {
                        literalBuf.append('{')
                        i += 2
                        continue
                    }
                    val close = pattern.indexOf('}', startIndex = i + 1)
                    require(close > i + 1) { "Invalid or unclosed placeholder in YAML template: '$pattern'" }
                    if (literalBuf.isNotEmpty()) {
                        segments.add(Segment.Literal(literalBuf.toString()))
                        literalBuf.clear()
                    }
                    val key = pattern.substring(i + 1, close)
                    placeholders.add(key)
                    segments.add(Segment.Placeholder(key))
                    i = close + 1
                } else if (ch == '}') {
                    require(i + 1 < pattern.length && pattern[i + 1] == '}') {
                        "Unescaped '}' in YAML template (use '}}' for a literal brace): '$pattern'"
                    }
                    literalBuf.append('}')
                    i += 2
                } else {
                    literalBuf.append(ch)
                    i++
                }
            }
            if (literalBuf.isNotEmpty()) {
                segments.add(Segment.Literal(literalBuf.toString()))
            }
            return YamlTemplate(segments, placeholders)
        }
    }
}

/**
 * Declarative replacement action combining a primary template, an optional `rawAbi` override
 * template, and optional auto-registered Kotlin symbol imports.
 */
internal class DeclarativeReplacementAction(
    private val replaceTemplate: YamlTemplate,
    private val rawAbiTemplate: YamlTemplate?,
    private val importTemplates: List<YamlTemplate>,
) {
    fun render(
        rawAbi: Boolean = false,
        onImport: (String) -> Unit = {},
        lookup: (String) -> String?,
    ): String {
        val activeTemplate = if (rawAbi && rawAbiTemplate != null) rawAbiTemplate else replaceTemplate
        for (imp in importTemplates) {
            val renderedImp = imp.render(lookup)
            if (renderedImp.isNotEmpty()) onImport(renderedImp)
        }
        return activeTemplate.render(lookup)
    }

    companion object {
        fun fromYaml(node: YamlNode.Mapping, replaceKey: String = "replace", rawAbiKey: String = "rawAbiReplace"): DeclarativeReplacementAction =
            DeclarativeReplacementAction(
                replaceTemplate = YamlTemplate.compile(node.string(replaceKey)),
                rawAbiTemplate = node.optionalString(rawAbiKey)?.let(YamlTemplate::compile),
                importTemplates = node.explicitStringList("imports").map(YamlTemplate::compile),
            )
    }
}

/**
 * Declarative pattern-match and template-replacement rule for stdlib generic Rust type paths
 * (`Vec<T>`, `HashMap<K, V>`, `Cell<T>`, `MaybeUninit<T>`, `Box<T>`, `Cow<B>`, etc.).
 */
internal class DeclarativeTypePathPatternRule(
    val names: Set<String>,
    val arity: Int?,
    val minArity: Int?,
    val unlessDeclared: Boolean,
    val anySegmentIn: Set<String>,
    val arg0Equals: String?,
    val action: DeclarativeReplacementAction,
) {
    fun tryMatchAndRender(
        rawName: String,
        segmentIdents: List<String>,
        typeArgs: List<String>,
        declaredTypeNames: Set<String>,
        coreNs: String,
        onImport: (String) -> Unit,
    ): String? {
        if (rawName !in names) return null
        if (unlessDeclared && rawName in declaredTypeNames) return null
        if (arity != null && typeArgs.size != arity) return null
        if (minArity != null && typeArgs.size < minArity) return null
        if (anySegmentIn.isNotEmpty() && segmentIdents.none { it in anySegmentIn }) return null
        if (arg0Equals != null && typeArgs.firstOrNull() != arg0Equals) return null
        return action.render(rawAbi = false, onImport = onImport) { key ->
            when (key) {
                "rawName" -> rawName
                "coreNs" -> coreNs
                "last" -> typeArgs.lastOrNull()
                "args" -> typeArgs.joinToString(", ")
                else -> key.toIntOrNull()?.let { typeArgs.getOrNull(it) }
            }
        }
    }

    companion object {
        fun fromYaml(node: YamlNode.Mapping): DeclarativeTypePathPatternRule =
            DeclarativeTypePathPatternRule(
                names = node.wordSet("match"),
                arity = node.optionalInt("arity"),
                minArity = node.optionalInt("minArity"),
                unlessDeclared = node.bool("unlessDeclared", default = false),
                anySegmentIn = node.optionalWordSet("anySegmentIn"),
                arg0Equals = node.optionalString("arg0Equals"),
                action = DeclarativeReplacementAction.fromYaml(node),
            )
    }
}

/**
 * Declarative pattern-match and template-replacement rule for method calls on typed receivers
 * (`rawPtr`, `dequeIter`, `vec`, `any`, etc.).
 */
internal class DeclarativeMethodPatternRule(
    val methods: Set<String>,
    val receiverGuard: String,
    val arity: Int?,
    val noTurbofish: Boolean,
    val action: DeclarativeReplacementAction,
) {
    fun matchesHeader(
        rawMethod: String,
        receiverKind: String,
        argCount: Int,
        hasTurbofish: Boolean,
    ): Boolean {
        if (rawMethod !in methods) return false
        if (receiverGuard != receiverKind) return false
        if (arity != null && argCount != arity) return false
        if (noTurbofish && hasTurbofish) return false
        return true
    }

    companion object {
        fun fromYaml(node: YamlNode.Mapping): DeclarativeMethodPatternRule =
            DeclarativeMethodPatternRule(
                methods = node.wordSet("match"),
                receiverGuard = node.optionalString("receiver") ?: "any",
                arity = node.optionalInt("arity"),
                noTurbofish = node.bool("noTurbofish", default = false),
                action = DeclarativeReplacementAction.fromYaml(node),
            )
    }
}

/**
 * Declarative pattern-match and template-replacement rule for `#[rustc_intrinsic]` declarations
 * and `core::intrinsics` call sites.
 */
internal class DeclarativeIntrinsicPatternRule(
    val names: Set<String>,
    val minCallArgs: Int,
    val declAction: DeclarativeReplacementAction?,
    val callAction: DeclarativeReplacementAction?,
) {
    companion object {
        fun fromYaml(node: YamlNode.Mapping): DeclarativeIntrinsicPatternRule =
            DeclarativeIntrinsicPatternRule(
                names = node.wordSet("match"),
                minCallArgs = node.optionalInt("minCallArgs") ?: 0,
                declAction =
                    if ("declReplace" in node.entries) {
                        DeclarativeReplacementAction(
                            replaceTemplate = YamlTemplate.compile(node.string("declReplace")),
                            rawAbiTemplate = node.optionalString("declRawAbiReplace")?.let(YamlTemplate::compile),
                            importTemplates = node.explicitStringList("declImports").ifEmpty { node.explicitStringList("imports") }.map(YamlTemplate::compile),
                        )
                    } else {
                        null
                    },
                callAction =
                    if ("callReplace" in node.entries) {
                        DeclarativeReplacementAction(
                            replaceTemplate = YamlTemplate.compile(node.string("callReplace")),
                            rawAbiTemplate = node.optionalString("callRawAbiReplace")?.let(YamlTemplate::compile),
                            importTemplates = node.explicitStringList("callImports").ifEmpty { node.explicitStringList("imports") }.map(YamlTemplate::compile),
                        )
                    } else {
                        null
                    },
            )
    }
}
