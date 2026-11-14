package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.rustkotlin.reused.starlark.FormatParser
import io.github.kotlinmania.rustkotlin.reused.starlark.FormatToken

internal data class FormatNamedArg(
    val name: String,
    val renderedExpr: String,
)

internal sealed interface FormatTemplatePiece {
    data class LiteralText(
        val text: String,
    ) : FormatTemplatePiece
    data class Interpolation(
        val expr: String,
    ) : FormatTemplatePiece
}

internal fun renderRustFormatStringToKotlin(
    rawFormatContent: String,
    positionalExprs: List<String>,
    namedExprs: List<FormatNamedArg> = emptyList(),
    identFallbackTransform: (String) -> String = ::rustIdentToKotlin,
): String {
    val parser = FormatParser(rawFormatContent)
    val pieces = mutableListOf<FormatTemplatePiece>()
    var nextAutoIndex = 0
    val namedLookup = namedExprs.associate { it.name to it.renderedExpr }

    while (true) {
        val tokenResult = parser.next()
        if (tokenResult.isFailure) {
            return renderKotlinDoubleQuotedString(rawFormatContent)
        }
        val token = tokenResult.getOrNull() ?: break
        when (token) {
            is FormatToken.Text -> {
                if (token.text.isNotEmpty()) {
                    pieces += FormatTemplatePiece.LiteralText(token.text)
                }
            }
            is FormatToken.Escape -> {
                pieces += FormatTemplatePiece.LiteralText(token.escape.asStr())
            }
            is FormatToken.Capture -> {
                val selector = token.capture.substringBefore(':').trim()
                val resolvedExpr =
                    when {
                        selector.isEmpty() -> {
                            val idx = nextAutoIndex++
                            positionalExprs.getOrNull(idx) ?: "arg$idx"
                        }
                        selector.all { it.isDigit() } -> {
                            val idx = selector.toIntOrNull() ?: 0
                            positionalExprs.getOrNull(idx) ?: "arg$idx"
                        }
                        else -> {
                            namedLookup[selector] ?: identFallbackTransform(selector)
                        }
                    }
                pieces += FormatTemplatePiece.Interpolation(resolvedExpr)
            }
        }
    }

    val hasNestedQuotes =
        pieces.any { piece ->
            piece is FormatTemplatePiece.Interpolation && piece.expr.contains('"')
        }
    if (hasNestedQuotes) {
        if (pieces.isEmpty()) return "\"\""
        return pieces.joinToString(" + ") { piece ->
            when (piece) {
                is FormatTemplatePiece.LiteralText -> renderKotlinDoubleQuotedString(piece.text)
                is FormatTemplatePiece.Interpolation -> "(${piece.expr}).toString()"
            }
        }
    }

    val sb = StringBuilder("\"")
    for (index in pieces.indices) {
        when (val piece = pieces[index]) {
            is FormatTemplatePiece.LiteralText -> {
                sb.append(escapeKotlinStringContent(piece.text))
            }
            is FormatTemplatePiece.Interpolation -> {
                val nextLiteral = (pieces.getOrNull(index + 1) as? FormatTemplatePiece.LiteralText)?.text
                val nextContinuesIdent =
                    nextLiteral?.firstOrNull()?.let { ch ->
                        ch.isLetterOrDigit() || ch == '_'
                    } ?: false
                if (isSimpleKotlinIdentifier(piece.expr) && !nextContinuesIdent) {
                    sb.append('$').append(piece.expr)
                } else {
                    sb.append("\${").append(piece.expr).append('}')
                }
            }
        }
    }
    sb.append('"')
    return sb.toString()
}

internal fun renderKotlinDoubleQuotedString(text: String): String =
    "\"${escapeKotlinStringContent(text)}\""

internal fun escapeKotlinStringContent(text: String): String =
    buildString(text.length + 8) {
        for (ch in text) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '$' -> append("\\$")
                else -> {
                    if (ch.code < 0x20) {
                        val hex = ch.code.toString(16).padStart(4, '0')
                        append("\\u").append(hex)
                    } else {
                        append(ch)
                    }
                }
            }
        }
    }

private fun isSimpleKotlinIdentifier(expr: String): Boolean {
    if (expr.isEmpty()) return false
    val first = expr.first()
    if (!(first.isLetter() || first == '_')) return false
    return expr.all { it.isLetterOrDigit() || it == '_' }
}

internal fun renderRustFormatAsKotlinTemplate(
    rawFormatContent: String,
    positionalExprs: List<String>,
    namedExprs: Map<String, String> = emptyMap(),
): String =
    renderRustFormatStringToKotlin(
        rawFormatContent = rawFormatContent,
        positionalExprs = positionalExprs,
        namedExprs = namedExprs.map { (k, v) -> FormatNamedArg(k, v) },
    )
