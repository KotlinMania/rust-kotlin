package io.github.kotlinmania.rustkotlin

/**
 * Lexical gate enforcing KotlinMania's identifier invariant:
 * no emitted Kotlin identifier may contain an underscore (`_`) unless it is
 * Kotlin's single-character wildcard keyword (`_`) or strict `SCREAMING_SNAKE_CASE`
 * (`^[A-Z][A-Z0-9]*(_[A-Z0-9]+)*$`, with no leading/trailing `_` and no `__`).
 *
 * Comments, character literals, numeric underscores (`0xFFFF_FFFFuL`), and raw text
 * inside string literals are skipped; string template expressions (`$ident` and
 * `${...}`) and backtick-escaped identifiers (`` `foo_bar` ``) are scanned via [KotlinTokenWalker].
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

    public fun findViolations(kotlinSource: String): List<Violation> =
        IdentifierViolationWalker(kotlinSource).collectViolations()

    public fun requireZeroViolations(kotlinSource: String, fileLabel: String = "<emitted Kotlin>") {
        val violations = findViolations(kotlinSource)
        require(violations.isEmpty()) {
            val preview = violations.take(15).joinToString(", ")
            val extra = if (violations.size > 15) " (+${violations.size - 15} more)" else ""
            "Forbidden underscore Kotlin identifier(s) in $fileLabel: $preview$extra"
        }
    }

    private class IdentifierViolationWalker(source: String) : KotlinTokenWalker(source) {
        fun collectViolations(): List<Violation> {
            val out = mutableListOf<Violation>()
            for (tok in tokens) {
                when (tok) {
                    is KotlinLexToken.Ident ->
                        if (!isAllowedIdentifier(tok.rawText)) {
                            out += Violation(tok.rawText, tok.line, tok.column)
                        }
                    is KotlinLexToken.BacktickIdent ->
                        if (!isAllowedIdentifier(tok.ident)) {
                            out += Violation(tok.ident, tok.line, tok.column)
                        }
                    is KotlinLexToken.StringIdentTemplate ->
                        if (!isAllowedIdentifier(tok.ident)) {
                            out += Violation(tok.ident, tok.line, tok.column + 1)
                        }
                    else -> {}
                }
            }
            return out
        }
    }
}
