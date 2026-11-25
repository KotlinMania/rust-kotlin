package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.procmacro2.Span
import io.github.kotlinmania.procmacro2.TokenStream
import io.github.kotlinmania.procmacro2.TokenTree

internal fun Span.startOffset(): Int = sourceUtf16Range()?.first ?: byteRange().first

internal fun Span.endExclusiveOffset(): Int {
    val r = sourceUtf16Range() ?: byteRange()
    return if (r.isEmpty()) r.first else r.last + 1
}

internal enum class TriviaStyle {
    LineComment,
    BlockComment,
    InnerDocLine,
    InnerDocBlock,
    OuterDocLine,
    OuterDocBlock,
}

internal data class TriviaPiece(
    val startOffset: Int,
    val endOffset: Int,
    val style: TriviaStyle,
    val text: String,
)

/**
 * Extracts non-token trivia (inline `//` comments, `/* ... */` comments, and `//!` inner doc comments)
 * from the gaps between [TokenStream] token spans in the original Rust [source].
 *
 * Outer doc comments (`///` and `/** */`) are already parsed by `syn-kotlin` into `#[doc = "..."]`
 * [io.github.kotlinmania.syn.Attribute] nodes on AST items, so [SourceTrivia] focuses on preserving
 * inline implementation comments and file-level inner doc comments that live between token spans.
 */
internal class SourceTrivia private constructor(
    val fileInnerDocs: List<TriviaPiece>,
    private val inlineByNextTokenStart: Map<Int, List<TriviaPiece>>,
    val trailingComments: List<TriviaPiece> = emptyList(),
) {
    fun withoutTrailingComments(): SourceTrivia =
        if (trailingComments.isEmpty()) {
            this
        } else {
            SourceTrivia(
                fileInnerDocs = fileInnerDocs,
                inlineByNextTokenStart = inlineByNextTokenStart,
                trailingComments = emptyList(),
            )
        }

    fun leadingBefore(span: Span): List<TriviaPiece> {
        val start = span.startOffset()
        return inlineByNextTokenStart[start].orEmpty()
    }

    companion object {
        fun collect(source: String, tokens: TokenStream): SourceTrivia {
            val intervals = ArrayList<IntRange>()
            collectTokenByteRanges(tokens.clone(), intervals)
            intervals.sortBy { it.first }

            val fileInnerDocs = mutableListOf<TriviaPiece>()
            val inlineByNextTokenStart = linkedMapOf<Int, MutableList<TriviaPiece>>()

            var cursor = 0
            for (range in intervals) {
                val tokenStart = range.first
                val tokenEnd = range.last + 1
                if (tokenStart > cursor && tokenStart <= source.length) {
                    val pieces = scanGapForComments(source, cursor, tokenStart)
                    for (piece in pieces) {
                        when (piece.style) {
                            TriviaStyle.InnerDocLine, TriviaStyle.InnerDocBlock -> {
                                fileInnerDocs += piece
                            }
                            TriviaStyle.LineComment, TriviaStyle.BlockComment -> {
                                inlineByNextTokenStart.getOrPut(tokenStart) { mutableListOf() }.add(piece)
                            }
                            TriviaStyle.OuterDocLine, TriviaStyle.OuterDocBlock -> {
                                // Handled via syn Attribute #[doc = "..."]
                            }
                        }
                    }
                }
                if (tokenEnd > cursor) {
                    cursor = tokenEnd
                }
            }

            val trailingComments = mutableListOf<TriviaPiece>()
            if (cursor < source.length) {
                val pieces = scanGapForComments(source, cursor, source.length)
                for (piece in pieces) {
                    when (piece.style) {
                        TriviaStyle.InnerDocLine, TriviaStyle.InnerDocBlock -> {
                            fileInnerDocs += piece
                        }
                        TriviaStyle.LineComment, TriviaStyle.BlockComment -> {
                            trailingComments += piece
                        }
                        TriviaStyle.OuterDocLine, TriviaStyle.OuterDocBlock -> {
                            // EOF outer doc comments have no following declaration.
                        }
                    }
                }
            }

            return SourceTrivia(
                fileInnerDocs = fileInnerDocs,
                inlineByNextTokenStart = inlineByNextTokenStart,
                trailingComments = trailingComments,
            )
        }

        private fun collectTokenByteRanges(stream: TokenStream, out: MutableList<IntRange>) {
            for (tree in stream) {
                when (tree) {
                    is TokenTree.Group -> {
                        val openSpan = tree.value.spanOpen()
                        val closeSpan = tree.value.spanClose()
                        addSpanRange(openSpan, out)
                        collectTokenByteRanges(tree.value.stream(), out)
                        addSpanRange(closeSpan, out)
                    }
                    is TokenTree.Ident -> {
                        addSpanRange(tree.value.span(), out)
                    }
                    is TokenTree.Punct -> {
                        addSpanRange(tree.value.span(), out)
                    }
                    is TokenTree.Literal -> {
                        addSpanRange(tree.value.span(), out)
                    }
                }
            }
        }

        private fun addSpanRange(span: Span, out: MutableList<IntRange>) {
            val start = span.startOffset()
            val endExclusive = span.endExclusiveOffset()
            if (endExclusive > start && start >= 0) {
                out.add(start until endExclusive)
            }
        }

        private fun scanGapForComments(source: String, start: Int, end: Int): List<TriviaPiece> =
            TriviaGapScanner(source).scanRange(start, end)
    }
}

private class TriviaGapScanner(
    source: String,
) : RustLexicalSourceScanner(source) {
    fun scanRange(start: Int, end: Int): List<TriviaPiece> {
        val result = mutableListOf<TriviaPiece>()
        var i = start.coerceAtLeast(0)
        val limit = end.coerceAtMost(len)
        while (i < limit) {
            val ch = source[i]
            if (ch.isWhitespace()) {
                i++
                continue
            }
            if (ch == '/' && i + 1 < limit) {
                val next = source[i + 1]
                if (next == '/') {
                    val commentStart = i
                    i += 2
                    while (i < limit && source[i] != '\n') i++
                    val raw = source.substring(commentStart, i).trimEnd('\r')
                    val style =
                        when {
                            raw.startsWith("//!") -> TriviaStyle.InnerDocLine
                            raw.startsWith("///") && !raw.startsWith("////") -> TriviaStyle.OuterDocLine
                            else -> TriviaStyle.LineComment
                        }
                    result.add(TriviaPiece(commentStart, i, style, raw))
                    if (i < limit && source[i] == '\n') i++
                    continue
                }
                if (next == '*') {
                    val commentStart = i
                    val commentEnd = scanNestedBlockCommentEnd(commentStart).coerceAtMost(limit)
                    val raw = source.substring(commentStart, commentEnd)
                    val style =
                        when {
                            raw.startsWith("/*!") -> TriviaStyle.InnerDocBlock
                            raw.startsWith("/**") && !raw.startsWith("/***") && raw != "/**/" -> TriviaStyle.OuterDocBlock
                            else -> TriviaStyle.BlockComment
                        }
                    result.add(TriviaPiece(commentStart, commentEnd, style, raw))
                    i = commentEnd
                    continue
                }
            }
            i++
        }
        return result
    }
}
