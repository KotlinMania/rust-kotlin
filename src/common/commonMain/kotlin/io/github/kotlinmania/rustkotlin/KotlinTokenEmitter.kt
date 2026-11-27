package io.github.kotlinmania.rustkotlin

/**
 * Typed output token hierarchy for structured Kotlin code and token-stream emission.
 *
 * Replaces ad-hoc `MutableList<String>` spacing heuristics and raw `StringBuilder` line pasting
 * with structural token kinds so [KotlinTokenEmitter] and its subclasses ([KotlinDeclarationEmitter],
 * [KotlinFileDocumentEmitter], [RustTokenStreamKotlinEmitter]) enforce Kotlin spacing, indentation,
 * comment/KDoc formatting, and raw-string-safe block indentation uniformly.
 */
internal sealed class KotlinEmitToken {
    data class Keyword(
        val text: String,
    ) : KotlinEmitToken()

    data class Ident(
        val text: String,
    ) : KotlinEmitToken()

    data class Literal(
        val text: String,
    ) : KotlinEmitToken()

    data class Operator(
        val symbol: String,
        val spaced: Boolean = true,
    ) : KotlinEmitToken()

    data class Punct(
        val ch: Char,
    ) : KotlinEmitToken()

    data class Delimited(
        val open: String,
        val inner: List<KotlinEmitToken>,
        val close: String,
        val padInside: Boolean = false,
    ) : KotlinEmitToken()

    data class MacroCall(
        val callee: String,
        val open: String,
        val inner: List<KotlinEmitToken>,
        val close: String,
        val spaceBeforeOpen: Boolean = false,
        val padInside: Boolean = false,
    ) : KotlinEmitToken()

    data class TemplateRef(
        val ident: String,
    ) : KotlinEmitToken()

    data class Annotation(
        val prefix: String = "@",
        val name: String,
        val args: List<KotlinEmitToken>? = null,
    ) : KotlinEmitToken()

    data class LineComment(
        val body: String,
    ) : KotlinEmitToken()

    data class KDoc(
        val lines: List<String>,
    ) : KotlinEmitToken()

    data class RawFragment(
        val text: String,
    ) : KotlinEmitToken()

    data object Newline : KotlinEmitToken()

    data object BlankLine : KotlinEmitToken()

    data object Indent : KotlinEmitToken()

    data object Dedent : KotlinEmitToken()

    fun canPrecedePostfixTry(): Boolean =
        when (this) {
            is Ident, is Keyword, is Literal, is TemplateRef, is MacroCall -> true
            is Delimited -> close == ")" || close == "]"
            is Punct -> ch == ')' || ch == ']'
            is RawFragment ->
                text.isNotEmpty() && (text.last().isLetterOrDigit() || text.endsWith(')') || text.endsWith(']'))
            else -> false
        }
}

/**
 * Extensible token-based Kotlin source writer with structural boundary spacing,
 * nested indentation tracking, and KDoc/comment rendering.
 */
internal open class KotlinTokenEmitter(
    protected val baseIndent: String = "",
    protected val indentUnit: String = "    ",
) {
    protected val emitted: MutableList<KotlinEmitToken> = ArrayList()

    val isEmpty: Boolean
        get() = emitted.isEmpty()

    val size: Int
        get() = emitted.size

    fun toTokenList(): List<KotlinEmitToken> = emitted.toList()

    fun lastToken(): KotlinEmitToken? = emitted.lastOrNull()

    fun lastWordText(): String? =
        when (val last = emitted.lastOrNull()) {
            is KotlinEmitToken.Keyword -> last.text
            is KotlinEmitToken.Ident -> last.text
            else -> null
        }

    fun dropLastToken(): KotlinEmitToken? =
        if (emitted.isEmpty()) null else emitted.removeAt(emitted.lastIndex)

    open fun emit(token: KotlinEmitToken): KotlinTokenEmitter =
        apply {
            emitted += token
        }

    fun emitAll(tokens: Iterable<KotlinEmitToken>): KotlinTokenEmitter =
        apply {
            for (tok in tokens) emit(tok)
        }

    fun keyword(text: String): KotlinTokenEmitter =
        apply {
            if (text.isNotEmpty()) emit(KotlinEmitToken.Keyword(text))
        }

    fun ident(text: String): KotlinTokenEmitter =
        apply {
            if (text.isNotEmpty()) emit(KotlinEmitToken.Ident(text))
        }

    fun wordOrKeywords(text: String): KotlinTokenEmitter =
        apply {
            if (text.isEmpty()) return@apply
            if (' ' in text) {
                for (part in text.split(' ')) {
                    if (part.isNotEmpty()) wordOrSingleToken(part)
                }
            } else {
                wordOrSingleToken(text)
            }
        }

    private fun wordOrSingleToken(word: String) {
        if (word in KOTLIN_KEYWORDS) {
            emit(KotlinEmitToken.Keyword(word))
        } else {
            emit(KotlinEmitToken.Ident(word))
        }
    }

    fun literal(text: String): KotlinTokenEmitter =
        apply {
            if (text.isNotEmpty()) emit(KotlinEmitToken.Literal(text))
        }

    fun operator(symbol: String, spaced: Boolean = symbol in BINARY_SPACED_OPS): KotlinTokenEmitter =
        apply {
            if (symbol.isNotEmpty()) emit(KotlinEmitToken.Operator(symbol, spaced = spaced))
        }

    fun punct(ch: Char): KotlinTokenEmitter =
        apply {
            if (ch.toString() in BINARY_SPACED_OPS) {
                emit(KotlinEmitToken.Operator(ch.toString(), spaced = true))
            } else {
                emit(KotlinEmitToken.Punct(ch))
            }
        }

    fun templateRef(ident: String): KotlinTokenEmitter =
        apply {
            if (ident.isNotEmpty()) emit(KotlinEmitToken.TemplateRef(ident))
        }

    fun annotation(
        name: String,
        prefix: String = "@",
        args: List<KotlinEmitToken>? = null,
    ): KotlinTokenEmitter =
        apply {
            if (name.isNotEmpty()) emit(KotlinEmitToken.Annotation(prefix = prefix, name = name, args = args))
        }

    fun lineComment(body: String): KotlinTokenEmitter =
        emit(KotlinEmitToken.LineComment(body))

    fun kdoc(lines: List<String>): KotlinTokenEmitter =
        apply {
            if (lines.isNotEmpty()) emit(KotlinEmitToken.KDoc(lines))
        }

    fun raw(text: String): KotlinTokenEmitter =
        apply {
            if (text.isNotEmpty()) emit(KotlinEmitToken.RawFragment(text))
        }

    fun newline(): KotlinTokenEmitter = emit(KotlinEmitToken.Newline)

    fun blankLine(): KotlinTokenEmitter = emit(KotlinEmitToken.BlankLine)

    inline fun indented(block: KotlinTokenEmitter.() -> Unit): KotlinTokenEmitter =
        apply {
            emit(KotlinEmitToken.Indent)
            block()
            emit(KotlinEmitToken.Dedent)
        }

    fun delimited(
        open: String,
        close: String,
        padInside: Boolean = false,
        buildInner: (KotlinTokenEmitter) -> Unit,
    ): KotlinTokenEmitter =
        apply {
            val child = spawnInlineChild()
            buildInner(child)
            emit(KotlinEmitToken.Delimited(open, child.toTokenList(), close, padInside = padInside && !child.isEmpty))
        }

    fun macroCall(
        callee: String,
        open: String,
        close: String,
        spaceBeforeOpen: Boolean = false,
        padInside: Boolean = false,
        buildInner: (KotlinTokenEmitter) -> Unit,
    ): KotlinTokenEmitter =
        apply {
            val child = spawnInlineChild()
            buildInner(child)
            emit(
                KotlinEmitToken.MacroCall(
                    callee = callee,
                    open = open,
                    inner = child.toTokenList(),
                    close = close,
                    spaceBeforeOpen = spaceBeforeOpen,
                    padInside = padInside && !child.isEmpty,
                ),
            )
        }

    protected open fun spawnInlineChild(): KotlinTokenEmitter =
        KotlinTokenEmitter(baseIndent = "", indentUnit = indentUnit)

    /**
     * Structural spacing predicate between two inline [KotlinEmitToken]s.
     * Subclasses may override to refine spacing rules for specialized DSLs.
     */
    protected open fun needsSpaceBetween(prev: KotlinEmitToken, next: KotlinEmitToken): Boolean {
        if (isLineStructural(prev) || isLineStructural(next)) return false
        if ((next is KotlinEmitToken.Punct && next.ch == '.') || (prev is KotlinEmitToken.Punct && prev.ch == '.')) {
            return false
        }
        if (next is KotlinEmitToken.Punct && (next.ch == ',' || next.ch == ';' || next.ch == ':')) {
            return false
        }
        if (prev is KotlinEmitToken.Punct && (prev.ch == ',' || prev.ch == ':' || prev.ch == ';')) {
            return true
        }
        if ((next is KotlinEmitToken.Operator && next.spaced) || (prev is KotlinEmitToken.Operator && prev.spaced)) {
            return true
        }
        if (opensParenOrBracket(next)) {
            return prev is KotlinEmitToken.Keyword && prev.text in KEYWORD_SPACE_BEFORE_PAREN
        }
        if ((prev is KotlinEmitToken.Punct && prev.ch == '<') || (next is KotlinEmitToken.Punct && next.ch == '>')) {
            return false
        }
        if (next is KotlinEmitToken.Punct && next.ch == '<') {
            return prev is KotlinEmitToken.Keyword && prev.text in KEYWORD_SPACE_BEFORE_ANGLE
        }
        if (prev is KotlinEmitToken.Punct && (prev.ch == '(' || prev.ch == '[')) {
            return false
        }
        if (next is KotlinEmitToken.Punct && (next.ch == ')' || next.ch == ']')) {
            return false
        }
        if (prev is KotlinEmitToken.Punct && (prev.ch == '!' || prev.ch == '@')) {
            return false
        }
        return true
    }

    open fun render(): String {
        if (emitted.isEmpty()) return ""
        val sb = StringBuilder()
        var depth = 0
        var atLineStart = true
        var prevInline: KotlinEmitToken? = null

        fun currentIndent(): String =
            if (depth <= 0) baseIndent else baseIndent + indentUnit.repeat(depth)

        fun ensureIndentIfAtLineStart() {
            if (atLineStart) {
                sb.append(currentIndent())
                atLineStart = false
            }
        }

        fun emitInlineList(list: List<KotlinEmitToken>): String {
            if (list.isEmpty()) return ""
            val inlineEmitter = spawnInlineChild()
            inlineEmitter.emitAll(list)
            return inlineEmitter.render()
        }

        for (tok in emitted) {
            when (tok) {
                KotlinEmitToken.Indent -> depth++
                KotlinEmitToken.Dedent -> if (depth > 0) depth--
                KotlinEmitToken.Newline -> {
                    sb.append('\n')
                    atLineStart = true
                    prevInline = null
                }
                KotlinEmitToken.BlankLine -> {
                    if (sb.isNotEmpty()) {
                        if (!sb.endsWith("\n\n")) {
                            if (!sb.endsWith('\n')) sb.append('\n')
                            sb.append('\n')
                        }
                        atLineStart = true
                        prevInline = null
                    }
                }
                is KotlinEmitToken.LineComment -> {
                    if (!atLineStart) sb.append('\n')
                    val indent = currentIndent()
                    if (tok.body.isEmpty()) {
                        sb.append("$indent//\n")
                    } else {
                        sb.append("$indent// ${tok.body}\n")
                    }
                    atLineStart = true
                    prevInline = null
                }
                is KotlinEmitToken.KDoc -> {
                    if (tok.lines.isNotEmpty()) {
                        if (!atLineStart) sb.append('\n')
                        val indent = currentIndent()
                        sb.append("$indent/**\n")
                        for (line in tok.lines) {
                            val safeLine = line.replace("*/", "* /")
                            if (safeLine.isBlank()) {
                                sb.append("$indent *\n")
                            } else {
                                sb.append("$indent * $safeLine\n")
                            }
                        }
                        sb.append("$indent */\n")
                        atLineStart = true
                        prevInline = null
                    }
                }
                else -> {
                    ensureIndentIfAtLineStart()
                    val prev = prevInline
                    if (prev != null && needsSpaceBetween(prev, tok) && sb.isNotEmpty() && sb.last() != ' ' && sb.last() != '\n') {
                        sb.append(' ')
                    }
                    val renderedText =
                        when (tok) {
                            is KotlinEmitToken.Keyword -> tok.text
                            is KotlinEmitToken.Ident -> tok.text
                            is KotlinEmitToken.Literal -> tok.text
                            is KotlinEmitToken.Operator -> tok.symbol
                            is KotlinEmitToken.Punct -> tok.ch.toString()
                            is KotlinEmitToken.TemplateRef -> "$${tok.ident}"
                            is KotlinEmitToken.Delimited -> {
                                val inner = emitInlineList(tok.inner)
                                if (tok.padInside && inner.isNotEmpty()) {
                                    "${tok.open} $inner ${tok.close}"
                                } else {
                                    "${tok.open}$inner${tok.close}"
                                }
                            }
                            is KotlinEmitToken.MacroCall -> {
                                val inner = emitInlineList(tok.inner)
                                val sep = if (tok.spaceBeforeOpen) " " else ""
                                val body =
                                    if (tok.padInside && inner.isNotEmpty()) {
                                        "${tok.open} $inner ${tok.close}"
                                    } else {
                                        "${tok.open}$inner${tok.close}"
                                    }
                                "${tok.callee}$sep$body"
                            }
                            is KotlinEmitToken.Annotation -> {
                                val argsText = tok.args?.let { "(${emitInlineList(it)})" }.orEmpty()
                                "${tok.prefix}${tok.name}$argsText"
                            }
                            is KotlinEmitToken.RawFragment -> tok.text
                        }
                    sb.append(renderedText)
                    if (renderedText.endsWith('\n')) {
                        atLineStart = true
                        prevInline = null
                    } else if (renderedText.isNotEmpty()) {
                        prevInline = tok
                    }
                }
            }
        }
        return sb.toString()
    }

    companion object {
        internal val KOTLIN_KEYWORDS: Set<String> =
            wordSet(
                "package import class interface object fun val var typealias constructor companion",
                "public internal private protected open abstract final sealed data value inline external",
                "override operator infix tailrec suspend expect actual const lateinit vararg reified",
                "if else when for while do return break continue throw try catch finally",
                "in is as by where out this super true false null",
            )

        internal val KEYWORD_SPACE_BEFORE_PAREN: Set<String> =
            wordSet("if while for when return val var in catch")

        internal val KEYWORD_SPACE_BEFORE_ANGLE: Set<String> =
            wordSet("val var return in fun")

        internal val BINARY_SPACED_OPS: Set<String> =
            setOf(
                "=",
                "->",
                "==",
                "!=",
                "<=",
                ">=",
                "&&",
                "||",
                "+",
                "-",
                "*",
                "/",
                "+=",
                "-=",
                "*=",
                "/=",
                "%=",
                "&=",
                "|=",
                "^=",
            )

        private fun isLineStructural(tok: KotlinEmitToken): Boolean =
            tok is KotlinEmitToken.Newline ||
                tok is KotlinEmitToken.BlankLine ||
                tok is KotlinEmitToken.Indent ||
                tok is KotlinEmitToken.Dedent ||
                tok is KotlinEmitToken.LineComment ||
                tok is KotlinEmitToken.KDoc

        private fun opensParenOrBracket(tok: KotlinEmitToken): Boolean =
            when (tok) {
                is KotlinEmitToken.Punct -> tok.ch == '(' || tok.ch == '['
                is KotlinEmitToken.Delimited -> tok.open == "(" || tok.open == "["
                else -> false
            }
    }
}

/**
 * Token-aware Kotlin block indenter backed by [KotlinTokenRewriter].
 *
 * Preserves verbatim interior lines of multiline `"""..."""` raw string literals
 * (outside `${...}` template expressions) so re-indenting test functions or nested declarations
 * never corrupts raw string contents.
 */
internal class KotlinTokenIndenter(
    source: String,
    private val indentPrefix: String = "    ",
) : KotlinTokenRewriter(source) {
    private var rawStringStackDepth: Int = 0
    private val stringModeStack = ArrayList<Boolean>()
    private var atLineStart: Boolean = true

    override fun emitAt(index: Int, out: StringBuilder): Int {
        val tok = tokens[index]
        val insideRawStringText =
            stringModeStack.lastOrNull() == true &&
                tok.interpolationDepth == rawStringStackDepth - 1 &&
                (tok is KotlinLexToken.StringText || tok is KotlinLexToken.StringClose)

        if (atLineStart && tok !is KotlinLexToken.Newline && !insideRawStringText) {
            out.append(indentPrefix)
            atLineStart = false
        }

        when (tok) {
            is KotlinLexToken.StringOpen -> {
                stringModeStack.add(tok.isRaw)
                if (tok.isRaw) rawStringStackDepth++
                out.append(tok.rawText)
                if ('\n' in tok.rawText) atLineStart = tok.rawText.endsWith('\n')
            }
            is KotlinLexToken.StringClose -> {
                val wasRaw = if (stringModeStack.isNotEmpty()) stringModeStack.removeAt(stringModeStack.lastIndex) else false
                if (wasRaw && rawStringStackDepth > 0) rawStringStackDepth--
                out.append(tok.rawText)
                atLineStart = false
            }
            is KotlinLexToken.Newline -> {
                out.append(tok.rawText)
                atLineStart = true
            }
            is KotlinLexToken.BlockComment -> {
                if ('\n' !in tok.rawText) {
                    out.append(tok.rawText)
                    atLineStart = false
                } else {
                    val lines = tok.rawText.split('\n')
                    for ((lineIdx, part) in lines.withIndex()) {
                        if (lineIdx > 0) {
                            out.append('\n')
                            if (part.isNotEmpty()) out.append(indentPrefix)
                        }
                        out.append(part)
                    }
                    atLineStart = lines.last().isEmpty()
                }
            }
            is KotlinLexToken.StringText -> {
                if (stringModeStack.lastOrNull() == true || '\n' !in tok.rawText) {
                    out.append(tok.rawText)
                    atLineStart = tok.rawText.endsWith('\n')
                } else {
                    out.append(tok.rawText)
                    atLineStart = false
                }
            }
            else -> {
                out.append(tok.rawText)
                atLineStart = false
            }
        }
        return index + 1
    }
}

/**
 * Extensible declaration-level Kotlin emitter built on [KotlinTokenEmitter] for structured
 * classes, interfaces, objects, functions, properties, typealiases, and complete `.kt` files.
 */
internal open class KotlinDeclarationEmitter(
    baseIndent: String = "",
    indentUnit: String = "    ",
) : KotlinTokenEmitter(baseIndent, indentUnit) {
    fun emitLeadingPreformatted(triviaAndDocs: String): KotlinDeclarationEmitter =
        apply {
            if (triviaAndDocs.isNotEmpty()) {
                raw(triviaAndDocs)
            }
        }

    fun emitPropertyOrConst(
        leadingHeader: String = "",
        visibilityPrefix: String,
        declKeyword: String,
        name: String,
        typeText: String,
        initializerExpr: String,
        multilineBatchIndent: Boolean = false,
    ): KotlinDeclarationEmitter =
        apply {
            emitLeadingPreformatted(leadingHeader)
            for (mod in visibilityPrefix.trim().split(' ')) {
                if (mod.isNotEmpty()) keyword(mod)
            }
            for (kw in declKeyword.trim().split(' ')) {
                if (kw.isNotEmpty()) keyword(kw)
            }
            ident(name)
            punct(':')
            raw(typeText)
            operator("=")
            if (multilineBatchIndent && '\n' in initializerExpr) {
                newline()
                indented {
                    raw(KotlinTokenIndenter(initializerExpr, indentUnit).rewrite().removePrefix(indentUnit))
                }
            } else {
                raw(initializerExpr)
            }
        }

    fun emitTypeAliasDeclaration(
        leadingHeader: String = "",
        visibilityPrefix: String,
        name: String,
        typeParamsSuffix: String = "",
        rhsType: String,
    ): KotlinDeclarationEmitter =
        apply {
            emitLeadingPreformatted(leadingHeader)
            for (mod in visibilityPrefix.trim().split(' ')) {
                if (mod.isNotEmpty()) keyword(mod)
            }
            keyword("typealias")
            raw("$name$typeParamsSuffix")
            operator("=")
            raw(rhsType)
        }

    fun emitSuiteClass(
        visibility: String = "public",
        className: String,
        memberDeclarations: List<String>,
    ): KotlinDeclarationEmitter =
        apply {
            if (visibility.isNotEmpty()) keyword(visibility)
            keyword("class")
            ident(className)
            punct('{')
            newline()
            val indentedMembers = memberDeclarations.map { KotlinTokenIndenter(it, indentUnit).rewrite() }
            raw(indentedMembers.joinToString("\n\n"))
            newline()
            punct('}')
        }
}

/**
 * Structured `.kt` file document emitter assembling `port-lint` headers, file-level `@file:OptIn`
 * annotations, `package` headers, `import` blocks, file-level doc comments, body declarations,
 * and trailing comments through [KotlinDeclarationEmitter].
 */
internal class KotlinFileDocumentEmitter(
    private val headerContext: FileHeaderContext,
) : KotlinDeclarationEmitter() {
    fun emitPortLintHeaderIfPresent(): KotlinFileDocumentEmitter =
        apply {
            if (headerContext.portLintDirective != "none") {
                headerContext.portLintSourcePath?.let { relPath ->
                    lineComment("port-lint: ${headerContext.portLintDirective} $relPath")
                }
            }
        }

    fun emitUnsignedOptInIfNeeded(needed: Boolean): KotlinFileDocumentEmitter =
        apply {
            if (needed) {
                annotation(
                    name = "OptIn",
                    prefix = "@file:",
                    args = listOf(KotlinEmitToken.RawFragment("ExperimentalUnsignedTypes::class")),
                )
                blankLine()
            }
        }

    fun emitPackageHeaderIfPresent(): KotlinFileDocumentEmitter =
        apply {
            headerContext.packageName?.let { pkg ->
                keyword("package")
                raw(pkg)
                blankLine()
            }
        }

    fun emitImportBlock(importLines: List<String>): KotlinFileDocumentEmitter =
        apply {
            if (importLines.isNotEmpty()) {
                raw(importLines.joinToString("\n"))
                blankLine()
            }
        }

    fun emitFileDocComments(docLines: List<String>): KotlinFileDocumentEmitter =
        apply {
            if (docLines.isNotEmpty()) {
                for (docLine in docLines) {
                    lineComment(docLine)
                }
                blankLine()
            }
        }

    fun emitBodyItemsAndTrailingComments(
        bodyItems: List<String>,
        renderedTrailingComments: String,
    ): String {
        if (bodyItems.isNotEmpty()) {
            raw(bodyItems.joinToString("\n\n"))
        }
        var result = render()
        if (result.isNotEmpty() && !result.endsWith('\n')) {
            result += "\n"
        }
        if (renderedTrailingComments.isNotEmpty()) {
            if (bodyItems.isNotEmpty() && !result.endsWith("\n\n")) {
                result += "\n"
            }
            result += renderedTrailingComments
        }
        return ensureBlankLineBeforeKDocAfterLineComment(result)
    }
}
