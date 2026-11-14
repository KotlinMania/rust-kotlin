package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.procmacro2.Delimiter
import io.github.kotlinmania.procmacro2.Group
import io.github.kotlinmania.procmacro2.Punct
import io.github.kotlinmania.procmacro2.Spacing
import io.github.kotlinmania.procmacro2.TokenStream
import io.github.kotlinmania.procmacro2.TokenTree
import io.github.kotlinmania.syn.Attribute
import io.github.kotlinmania.syn.BinOp
import io.github.kotlinmania.syn.Expr
import io.github.kotlinmania.syn.FnArg
import io.github.kotlinmania.syn.GenericArgument
import io.github.kotlinmania.syn.GenericParam
import io.github.kotlinmania.syn.Generics
import io.github.kotlinmania.syn.Item
import io.github.kotlinmania.syn.Lit
import io.github.kotlinmania.syn.Macro
import io.github.kotlinmania.syn.Member
import io.github.kotlinmania.syn.Meta
import io.github.kotlinmania.syn.Pat
import io.github.kotlinmania.syn.Path
import io.github.kotlinmania.syn.PathArguments
import io.github.kotlinmania.syn.PointerMutability
import io.github.kotlinmania.syn.Signature
import io.github.kotlinmania.syn.Stmt
import io.github.kotlinmania.syn.SynType
import io.github.kotlinmania.syn.TypeParamBound
import io.github.kotlinmania.syn.UnOp
import io.github.kotlinmania.syn.Visibility
import io.github.kotlinmania.syn.WhereClause
import io.github.kotlinmania.syn.WherePredicate

private val CORE_OPS_BINARY_TRAITS =
    setOf("Add", "Sub", "Mul", "Div", "Rem", "BitAnd", "BitOr", "BitXor", "Shl", "Shr")
private val CORE_OPS_UNARY_OUTPUT_TRAITS =
    setOf("Neg", "Not")
private val CORE_OPS_ASSIGN_TRAITS =
    setOf(
        "AddAssign",
        "SubAssign",
        "MulAssign",
        "DivAssign",
        "RemAssign",
        "BitAndAssign",
        "BitOrAssign",
        "BitXorAssign",
        "ShlAssign",
        "ShrAssign",
    )

internal fun KotlinJ2KEmitter.renderMacroInvocationAsStmtOrExpr(
    mac: Macro,
    indent: String,
    selfTypeName: String?,
): String {
    val macroName =
        mac.path.segments
            .last()
            ?.ident
            ?.toString() ?: "macroCall"
    val tokens = mac.tokens.clone()
    return when (macroName) {
        "tri" -> {
            val inner = parseAndRenderTokenStreamAsExpr(tokens, indent, selfTypeName)
            renderSourceResultTry(inner, "return")
        }
        "format", "format_args" -> renderFormatMacroInvocation(tokens, indent, selfTypeName)
        "write", "writeln" -> renderWriteMacroInvocation(macroName, tokens, indent, selfTypeName)
        "panic", "unreachable", "unimplemented", "todo" -> {
            val msg =
                if (tokens.isEmpty()) {
                    val safeLabel = if (macroName == "unimplemented" || macroName == "todo") "unsupported" else macroName
                    "\"$safeLabel\""
                } else {
                    renderFormatMacroInvocation(tokens, indent, selfTypeName)
                }
            "error($msg)"
        }
        "println", "eprintln" -> {
            val msg = renderFormatMacroInvocation(tokens, indent, selfTypeName)
            "println($msg)"
        }
        "print", "eprint" -> {
            val msg = renderFormatMacroInvocation(tokens, indent, selfTypeName)
            "print($msg)"
        }
        "vec" -> {
            val args = parseCommaSeparatedExprsFromTokens(tokens, indent, selfTypeName)
            "mutableListOf(${args.joinToString(", ")})"
        }
        "assert", "debug_assert" -> {
            val parts = MacroRulesFileExpander.splitMacroArguments(tokens)
            if (parts.isEmpty()) {
                "check(true)"
            } else {
                val cond = parseAndRenderTokenStreamAsExpr(TokenStream.fromTokenTrees(parts[0]), indent, selfTypeName)
                if (parts.size >= 2) {
                    val msgTokens = mutableListOf<TokenTree>()
                    for (i in 1 until parts.size) {
                        if (i > 1) {
                            msgTokens += TokenTree.Punct(Punct(',', Spacing.Alone))
                        }
                        msgTokens.addAll(parts[i])
                    }
                    val msg = renderFormatMacroInvocation(TokenStream.fromTokenTrees(msgTokens), indent, selfTypeName)
                    "check($cond) { $msg }"
                } else {
                    "check($cond)"
                }
            }
        }
        "assert_eq", "debug_assert_eq", "assert_ne", "debug_assert_ne" -> {
            val parts = splitAssertEqArgsForMacro(tokens)
            val isNotEq = macroName.endsWith("_ne")
            when {
                parts.isEmpty() -> "check(true)"
                parts.size == 1 -> {
                    val single = parseAndRenderTokenStreamAsExpr(TokenStream.fromTokenTrees(parts[0]), indent, selfTypeName)
                    "check($single)"
                }
                else -> {
                    val leftStream = TokenStream.fromTokenTrees(parts[0])
                    val rightStream = TokenStream.fromTokenTrees(parts[1])
                    val left =
                        io.github.kotlinmania.syn
                            .parse2(io.github.kotlinmania.syn.ExprParse::parse, leftStream.clone())
                            .getOrNull()
                    val right =
                        io.github.kotlinmania.syn
                            .parse2(io.github.kotlinmania.syn.ExprParse::parse, rightStream.clone())
                            .getOrNull()
                    if (left != null && right != null) {
                        "check(${renderSourceEquality(left, right, isNotEq, indent, selfTypeName)})"
                    } else {
                        val lRendered =
                            left?.let { renderExpr(it, indent, selfTypeName) }
                                ?: parseAndRenderTokenStreamAsExpr(leftStream, indent, selfTypeName)
                        val rRendered =
                            right?.let { renderExpr(it, indent, selfTypeName) }
                                ?: parseAndRenderTokenStreamAsExpr(rightStream, indent, selfTypeName)
                        val op = if (isNotEq) "!=" else "=="
                        "check(($lRendered) $op ($rRendered))"
                    }
                }
            }
        }
        "cfg" -> evalCfgExpressionTokens(tokens.clone().toList()).toString()
        "stringify" -> renderKotlinDoubleQuotedString(stringifyTokenStream(tokens))
        "concat" ->
            tryEvalConcatTokens(tokens)?.let(::renderKotlinDoubleQuotedString) ?: run {
                val parts = parseCommaSeparatedExprsFromTokens(tokens, indent, selfTypeName)
                if (parts.isEmpty()) "\"\"" else parts.joinToString(" + ")
            }
        "matches" -> renderMatchesMacroInvocation(tokens, indent, selfTypeName)
        "line" -> {
            if (tokens.isEmpty()) {
                val startLoc = mac.path.span().start()
                "${startLoc.line}u"
            } else {
                val args = parseCommaSeparatedExprsFromTokens(tokens, indent, selfTypeName)
                "line(${args.joinToString(", ")})"
            }
        }
        "column" -> {
            if (tokens.isEmpty()) {
                val startLoc = mac.path.span().start()
                "${startLoc.column + 1}u"
            } else {
                val args = parseCommaSeparatedExprsFromTokens(tokens, indent, selfTypeName)
                "column(${args.joinToString(", ")})"
            }
        }
        "file" -> {
            if (tokens.isEmpty()) {
                renderKotlinDoubleQuotedString(headerContext.portLintSourcePath.orEmpty())
            } else {
                val args = parseCommaSeparatedExprsFromTokens(tokens, indent, selfTypeName)
                "file(${args.joinToString(", ")})"
            }
        }
        "Token" -> renderTokenMacro(tokens)
        "parenthesized", "braced", "bracketed" -> {
            val delim = extractDelimitedMacroParts(mac, indent, selfTypeName)
            if (delim != null) {
                renderSourceResultTry("${delim.first}(${delim.third})", "return")
            } else {
                val fnName = rustFunctionIdentToKotlin(macroName)
                val args = parseCommaSeparatedExprsFromTokens(tokens, indent, selfTypeName)
                "$fnName(${args.joinToString(", ")})"
            }
        }
        "quote", "parse_quote" ->
            renderQuoteMacroInvocation(rustFunctionIdentToKotlin(macroName), tokens, indent, selfTypeName, spanned = false)
        "quote_block" ->
            "Fragment.Block(${renderQuoteMacroInvocation("quote", tokens, indent, selfTypeName, spanned = false)})"
        "quote_expr" ->
            "Fragment.Expr(${renderQuoteMacroInvocation("quote", tokens, indent, selfTypeName, spanned = false)})"
        "quote_spanned", "parse_quote_spanned" ->
            renderQuoteMacroInvocation(rustFunctionIdentToKotlin(macroName), tokens, indent, selfTypeName, spanned = true)
        "cpp", "c" -> {
            val inlineCall =
                ffiCollector.recordInlineExpressionMacro(
                    mac = mac,
                    enclosingFnKotlinName = currentEnclosingFunctionName,
                    renderRustType = { rustTy, isParam ->
                        val prevAbi = inExternAbiSignature
                        val prevParam = inExternParamType
                        inExternAbiSignature = true
                        inExternParamType = isParam
                        try {
                            renderType(rustTy, selfTypeName)
                        } finally {
                            inExternParamType = prevParam
                            inExternAbiSignature = prevAbi
                        }
                    },
                    resolveLocalExprName = { raw -> scopeBuilder.resolveLocalKotlinName(raw) ?: rustValueIdentToKotlin(raw) },
                )
            if (inlineCall != null) {
                inlineCall
            } else {
                val fnName = rustFunctionIdentToKotlin(macroName)
                val args = parseCommaSeparatedExprsFromTokens(tokens, indent, selfTypeName)
                "$fnName(${args.joinToString(", ")})"
            }
        }
        "offset_of" -> {
            if (headerContext.rawAbiMode && currentExpectedNumericKind == null) {
                "0uL"
            } else {
                renderIntLiteral("0", "")
            }
        }
        "addr_of", "addr_of_mut" -> {
            // Taking an address must not become null or a copied pointee value.
            // Retain the raw-place operation for the pointer/storage lowering,
            // including field/index places rather than only simple identifiers.
            val place = parseAndRenderTokenStreamAsExpr(tokens, indent, selfTypeName)
            val callee = rustFunctionIdentToKotlin(macroName)
            "$callee($place)"
        }
        "asm", "naked_asm", "global_asm" -> "Unit"
        else -> {
            val macSegs = mac.path.segments.toList()
            val segStrings = macSegs.map { it.ident.toString() }
            val args = parseCommaSeparatedExprsFromTokens(tokens, indent, selfTypeName)
            val fnName = rustFunctionIdentToKotlin(macroName)
            val callee =
                if (segStrings.size >= 2) {
                    val crossTarget = resolveCrossModulePathTarget(segStrings)
                    if (crossTarget != null && crossTarget.second.isEmpty()) {
                        bindOrQualifyResolvedCrossSymbol(crossTarget.first)
                    } else if (scopeBuilder.isImportedModuleInScope(segStrings.first().removePrefix("r#"))) {
                        val modKt =
                            scopeBuilder.resolveModuleBinding(segStrings.first().removePrefix("r#"))?.kotlinName
                                ?: rustModuleIdentToKotlin(segStrings.first())
                        "$modKt.$fnName"
                    } else {
                        fnName
                    }
                } else if (macroName.removePrefix("r#") !in inspector.declaredTopLevelFnRawNames &&
                    fnName !in KOTLIN_STDLIB_SHADOWED_IMPORTS &&
                    !(fnName.length == 1 && fnName[0] in 'A'..'Z') &&
                    fnName !in inspector.enumVariantSimpleNames &&
                    fnName !in inspector.importedEnumVariantSimpleNames &&
                    scopeBuilder.resolveLocalKotlinName(macroName.removePrefix("r#")) == null &&
                    headerContext.crateSymbolSubpackages.isNotEmpty()
                ) {
                    val subpkg = resolveBestSubpackageForSymbol(fnName)
                    val nsPrefix = currentNamespacePrefix()
                    if (subpkg != null && nsPrefix != null) {
                        val targetPkg = if (subpkg.isEmpty()) nsPrefix else "$nsPrefix.$subpkg"
                        bindOrQualifyResolvedCrossSymbol("$targetPkg.$fnName")
                    } else {
                        fnName
                    }
                } else {
                    fnName
                }
            "$callee(${args.joinToString(", ")})"
        }
    }
}

internal fun KotlinJ2KEmitter.renderTokenMacro(tokens: TokenStream): String =
    resolveSynTokenMacroTypeName(tokens)?.let { "$it.default()" } ?: "Token"

internal fun KotlinJ2KEmitter.resolveSynTokenMacroTypeName(tokens: TokenStream): String? {
    val trees = tokens.clone().toList()
    if (trees.isEmpty()) return null
    if (trees.size == 1 && trees[0] is TokenTree.Ident) {
        val ident = (trees[0] as TokenTree.Ident).value.toString()
        return when (ident) {
            "Self" -> "SelfType"
            "self" -> "SelfValue"
            "type" -> "SynTypeToken"
            "_" -> "Underscore"
            in SYN_KEYWORD_TOKENS -> ident[0].uppercaseChar() + ident.substring(1)
            else -> null
        }
    }
    if (trees.size <= 3 && trees.all { it is TokenTree.Punct }) {
        for (idx in 0 until trees.lastIndex) {
            val p = (trees[idx] as TokenTree.Punct).value
            if (p.spacing() != Spacing.Joint) return null
        }
        val seq = buildString(trees.size) { trees.forEach { append((it as TokenTree.Punct).value.asChar()) } }
        return SYN_PUNCT_TOKEN_NAMES[seq]
    }
    return null
}

internal fun KotlinJ2KEmitter.renderQuoteMacroInvocation(
    fnName: String,
    tokens: TokenStream,
    indent: String,
    selfTypeName: String?,
    spanned: Boolean,
): String {
    val list = tokens.clone().toList()
    var bodyStartIdx = 0
    var spanArg: String? = null
    if (spanned) {
        for (i in 0 until list.size - 1) {
            val p1 = list[i] as? TokenTree.Punct
            val p2 = list[i + 1] as? TokenTree.Punct
            if (p1?.value?.asChar() == '=' && p2?.value?.asChar() == '>') {
                val spanStream = TokenStream.fromTokenTrees(list.subList(0, i))
                spanArg = parseAndRenderTokenStreamAsExpr(spanStream, indent, selfTypeName)
                bodyStartIdx = i + 2
                break
            }
        }
    }
    val bodyTrees =
        if (bodyStartIdx > 0) {
            list.subList(bodyStartIdx, list.size)
        } else {
            list
        }
    val templateSb = StringBuilder()
    val bindings = linkedMapOf<String, String>()
    buildQuoteTemplateAndBindings(bodyTrees, templateSb, bindings, selfTypeName)
    val templateLit = renderKotlinDoubleQuotedString(templateSb.toString().trim())
    val callArgs = mutableListOf<String>()
    if (spanArg != null) callArgs += spanArg
    callArgs += templateLit
    for ((k, v) in bindings) {
        callArgs += "\"$k\" to $v"
    }
    return "$fnName(${callArgs.joinToString(", ")})"
}

internal fun KotlinJ2KEmitter.buildQuoteTemplateAndBindings(
    list: List<TokenTree>,
    out: StringBuilder,
    bindings: MutableMap<String, String>,
    selfTypeName: String?,
) {
    var i = 0
    while (i < list.size) {
        val tt = list[i]
        if (tt is TokenTree.Punct && tt.value.asChar() == '#' && i + 1 < list.size) {
            val next = list[i + 1]
            if (next is TokenTree.Ident) {
                val raw = next.value.toString().removePrefix("r#")
                val ktIdent =
                    when {
                        raw == "self" -> "this"
                        raw == "Self" -> selfTypeName ?: "Self"
                        raw.first().isUpperCase() -> rustTypeIdentToKotlin(raw)
                        else -> scopeBuilder.resolveLocalKotlinName(raw) ?: rustValueIdentToKotlin(raw)
                    }
                out.append("`#`").append(ktIdent).append(' ')
                if (ktIdent != "this") {
                    bindings[ktIdent] = ktIdent
                }
                i += 2
                continue
            } else if (next is TokenTree.Group && next.value.delimiter() == Delimiter.Bracket) {
                out.append("`#`[")
                buildQuoteTemplateAndBindings(next.value.stream().toList(), out, bindings, selfTypeName)
                out.append("] ")
                i += 2
                continue
            } else if (next is TokenTree.Group && next.value.delimiter() == Delimiter.Parenthesis) {
                buildQuoteTemplateAndBindings(next.value.stream().toList(), out, bindings, selfTypeName)
                i += 2
                if (i < list.size && list[i] is TokenTree.Punct && (list[i] as TokenTree.Punct).value.asChar() == '*') {
                    i++
                }
                continue
            }
        }
        when (tt) {
            is TokenTree.Ident -> out.append(tt.value.toString()).append(' ')
            is TokenTree.Literal -> out.append(tt.value.toString()).append(' ')
            is TokenTree.Punct -> {
                out.append(tt.value.asChar())
                if (tt.value.spacing() == io.github.kotlinmania.procmacro2.Spacing.Alone) {
                    out.append(' ')
                }
            }
            is TokenTree.Group -> {
                val (open, close) =
                    when (tt.value.delimiter()) {
                        Delimiter.Brace -> "{ " to " } "
                        Delimiter.Parenthesis -> "(" to ") "
                        Delimiter.Bracket -> "[" to "] "
                        Delimiter.None -> "" to " "
                    }
                out.append(open)
                buildQuoteTemplateAndBindings(tt.value.stream().toList(), out, bindings, selfTypeName)
                out.append(close)
            }
        }
        i++
    }
}

internal fun KotlinJ2KEmitter.flattenNoneDelimitedGroups(stream: TokenStream, out: MutableList<TokenTree>) {
    for (tt in stream.clone().toList()) {
        if (tt is TokenTree.Group && tt.value.delimiter() == Delimiter.None) {
            flattenNoneDelimitedGroups(tt.value.stream(), out)
        } else {
            out += tt
        }
    }
}

internal fun KotlinJ2KEmitter.stringifyTokenStream(tokens: TokenStream): String {
    val flat = mutableListOf<TokenTree>()
    flattenNoneDelimitedGroups(tokens, flat)
    return TokenStream.fromTokenTrees(flat).toString()
}

internal fun KotlinJ2KEmitter.unwrapNoneGroupSlice(trees: List<TokenTree>): List<TokenTree> {
    var cur = trees
    while (cur.size == 1 && cur[0] is TokenTree.Group && (cur[0] as TokenTree.Group).value.delimiter() == Delimiter.None) {
        cur = (cur[0] as TokenTree.Group).value.stream().toList()
    }
    return cur
}

internal fun KotlinJ2KEmitter.tryEvalCompileTimeStringMacroArg(rawTrees: List<TokenTree>): String? {
    val trees = unwrapNoneGroupSlice(rawTrees)
    if (trees.isEmpty()) return null
    if (trees.size == 1) {
        when (val single = trees[0]) {
            is TokenTree.Literal -> {
                val strRes = single.value.strValue()
                strRes.value?.let { return it }
                return when (val lit = Lit.new(single.value)) {
                    is Lit.Str -> lit.value.value()
                    is Lit.Char -> {
                        val cp = lit.value.value()
                        if (cp > 0xFFFF && cp <= 0x10FFFF) {
                            val hi = ((cp - 0x10000) ushr 10) + 0xD800
                            val lo = ((cp - 0x10000) and 0x3FF) + 0xDC00
                            charArrayOf(hi.toChar(), lo.toChar()).concatToString()
                        } else {
                            cp.toChar().toString()
                        }
                    }
                    is Lit.Int -> lit.value.base10Digits()
                    is Lit.Float -> lit.value.base10Digits()
                    is Lit.Bool -> lit.value.value().toString()
                    else -> null
                }
            }
            is TokenTree.Ident -> {
                val id = single.value.toString()
                if (id == "true" || id == "false") return id
            }
            else -> {}
        }
    }
    if (trees.size >= 3 &&
        trees.last() is TokenTree.Group &&
        (trees[trees.size - 2] as? TokenTree.Punct)?.value?.asChar() == '!'
    ) {
        val pathTrees = trees.subList(0, trees.size - 2)
        val validPath =
            pathTrees.all { tt ->
                tt is TokenTree.Ident || (tt is TokenTree.Punct && tt.value.asChar() == ':')
            }
        val macroIdent = (pathTrees.lastOrNull() as? TokenTree.Ident)?.value?.toString()
        if (validPath && macroIdent != null) {
            val argStream = (trees.last() as TokenTree.Group).value.stream()
            return when (macroIdent) {
                "stringify" -> stringifyTokenStream(argStream)
                "concat" -> tryEvalConcatTokens(argStream)
                else -> null
            }
        }
    }
    return null
}

internal fun KotlinJ2KEmitter.tryEvalConcatTokens(tokens: TokenStream): String? {
    val parts = MacroRulesFileExpander.splitMacroArguments(tokens)
    val sb = StringBuilder()
    for (part in parts) {
        if (part.isEmpty()) continue
        val piece = tryEvalCompileTimeStringMacroArg(part) ?: return null
        sb.append(piece)
    }
    return sb.toString()
}

internal fun KotlinJ2KEmitter.renderMatchesMacroInvocation(
    tokens: TokenStream,
    indent: String,
    selfTypeName: String?,
): String {
    val parts = MacroRulesFileExpander.splitMacroArguments(tokens).filter { it.isNotEmpty() }
    if (parts.size < 2) return "false"
    val matchHead = TokenStream.fromString("match").getOrThrow().toList()
    val matchTail = TokenStream.fromString("=> true, _ => false").getOrThrow().toList()
    val scrutineeTokens =
        if (parts[0].any { it is TokenTree.Group && it.value.delimiter() == Delimiter.Brace }) {
            listOf(TokenTree.Group(Group(Delimiter.Parenthesis, TokenStream.fromTokenTrees(parts[0]))))
        } else {
            parts[0]
        }
    val armTokens =
        buildList {
            addAll(parts[1])
            addAll(matchTail)
        }
    val fullTokens =
        buildList {
            addAll(matchHead)
            addAll(scrutineeTokens)
            add(TokenTree.Group(Group(Delimiter.Brace, TokenStream.fromTokenTrees(armTokens))))
        }
    return parseAndRenderTokenStreamAsExpr(TokenStream.fromTokenTrees(fullTokens), indent, selfTypeName)
}

internal fun KotlinJ2KEmitter.renderFormatMacroInvocation(
    tokens: TokenStream,
    indent: String,
    selfTypeName: String?,
): String {
    val parts = MacroRulesFileExpander.splitMacroArguments(tokens)
    if (parts.isEmpty()) return "\"\""
    val rawFormatBody = tryEvalCompileTimeStringMacroArg(parts[0])
    if (rawFormatBody != null) {
        val positional = mutableListOf<String>()
        val named = linkedMapOf<String, String>()
        for (i in 1 until parts.size) {
            val argTrees = parts[i]
            if (argTrees.size >= 3 &&
                argTrees[0] is TokenTree.Ident &&
                (argTrees[1] as? TokenTree.Punct)?.value?.asChar() == '='
            ) {
                val key = (argTrees[0] as TokenTree.Ident).value.toString()
                val valTokens = TokenStream.fromTokenTrees(argTrees.drop(2))
                val renderedVal = parseAndRenderTokenStreamAsExpr(valTokens, indent, selfTypeName)
                named[key] = renderedVal
                positional += renderedVal
            } else {
                val valTokens = TokenStream.fromTokenTrees(argTrees)
                positional += parseAndRenderTokenStreamAsExpr(valTokens, indent, selfTypeName)
            }
        }
        return renderRustFormatAsKotlinTemplate(rawFormatBody, positional, named)
    }
    val renderedParts = parts.map { parseAndRenderTokenStreamAsExpr(TokenStream.fromTokenTrees(it), indent, selfTypeName) }
    return renderedParts.joinToString(" + ")
}

internal fun KotlinJ2KEmitter.renderWriteMacroInvocation(
    macroName: String,
    tokens: TokenStream,
    indent: String,
    selfTypeName: String?,
): String {
    val parts = MacroRulesFileExpander.splitMacroArguments(tokens)
    if (parts.isEmpty()) return "${rustFunctionIdentToKotlin(macroName)}()"
    val target = parseAndRenderTokenStreamAsExpr(TokenStream.fromTokenTrees(parts[0]), indent, selfTypeName)
    val appendMethod = if (macroName == "writeln") "appendLine" else "append"
    if (parts.size == 1) {
        return if (macroName == "writeln") "$target.appendLine()" else "$target.append(\"\")"
    }
    val restTokens = mutableListOf<TokenTree>()
    for (i in 1 until parts.size) {
        if (i > 1) {
            restTokens += TokenTree.Punct(Punct(',', Spacing.Alone))
        }
        restTokens.addAll(parts[i])
    }
    val formatted = renderFormatMacroInvocation(TokenStream.fromTokenTrees(restTokens), indent, selfTypeName)
    return "$target.$appendMethod($formatted)"
}

private fun splitAssertEqArgsForMacro(stream: TokenStream): List<List<TokenTree>> {
    val parts = MacroRulesFileExpander.splitMacroArguments(stream.clone())
    if (parts.size >= 2) return parts
    val trees = stream.clone().toList()
    if (trees.isEmpty()) return emptyList()
    val out = mutableListOf<List<TokenTree>>()
    val cur = mutableListOf<TokenTree>()
    var turbofishAngleDepth = 0
    for ((idx, tt) in trees.withIndex()) {
        if (tt is TokenTree.Punct) {
            val ch = tt.value.asChar()
            val prevIsColon =
                idx >= 2 &&
                    (trees[idx - 1] as? TokenTree.Punct)?.value?.asChar() == ':' &&
                    (trees[idx - 2] as? TokenTree.Punct)?.value?.asChar() == ':'
            if (ch == '<' && prevIsColon) {
                turbofishAngleDepth++
                cur += tt
                continue
            }
            if (ch == '>' && turbofishAngleDepth > 0) {
                turbofishAngleDepth--
                cur += tt
                continue
            }
            if (ch == ',' && turbofishAngleDepth == 0) {
                if (cur.isNotEmpty()) {
                    out += cur.toList()
                    cur.clear()
                }
                continue
            }
        }
        cur += tt
    }
    if (cur.isNotEmpty()) {
        out += cur.toList()
    }
    return out
}

internal fun KotlinJ2KEmitter.parseCommaSeparatedExprsFromTokens(
    tokens: TokenStream,
    indent: String,
    selfTypeName: String?,
): List<String> {
    val parts = MacroRulesFileExpander.splitMacroArguments(tokens)
    if (parts.isEmpty()) return emptyList()
    return parts
        .map { part ->
            parseAndRenderTokenStreamAsExpr(TokenStream.fromTokenTrees(part), indent, selfTypeName)
        }.filter { it.isNotBlank() }
}

internal fun KotlinJ2KEmitter.parseAndRenderTokenStreamAsExpr(
    stream: TokenStream,
    indent: String,
    selfTypeName: String?,
): String {
    val parsed =
        io.github.kotlinmania.syn
            .parse2(io.github.kotlinmania.syn.ExprParse::parse, stream.clone())
            .getOrNull()
    if (parsed != null) {
        return renderExpr(parsed, indent, selfTypeName)
    }
    val flat = renderFlatMacroTokenArgs(stream, indent, selfTypeName)
    return flat.joinToString(", ")
}

internal fun KotlinJ2KEmitter.renderFlatMacroTokenArgs(
    stream: TokenStream,
    indent: String,
    selfTypeName: String?,
): List<String> {
    val out = mutableListOf<String>()
    for (tt in stream.clone()) {
        when (tt) {
            is TokenTree.Ident -> {
                val raw = tt.value.toString().removePrefix("r#")
                if (raw != "_" && raw !in IGNORED_QUOTE_KEYWORDS) {
                    val mapped =
                        when {
                            raw == "self" -> "this"
                            raw == "Self" -> selfTypeName ?: "Self"
                            raw == "true" || raw == "false" -> raw
                            scopeBuilder.resolveLocalKotlinName(raw) != null -> scopeBuilder.resolveLocalKotlinName(raw)!!
                            raw in inspector.declaredConstNames -> inspector.declaredConstNames.getValue(raw)
                            isScreamingSnakeIdent(raw) -> rustConstIdentToKotlin(raw)
                            raw.first().isUpperCase() -> rustTypeIdentToKotlin(raw)
                            else -> rustValueIdentToKotlin(raw)
                        }
                    out += mapped
                }
            }
            is TokenTree.Literal -> {
                out += renderLit(Lit.new(tt.value))
            }
            is TokenTree.Group -> {
                out.addAll(renderFlatMacroTokenArgs(tt.value.stream(), indent, selfTypeName))
            }
            is TokenTree.Punct -> {}
        }
    }
    return out
}

internal fun KotlinJ2KEmitter.renderBitwiseComplementOperand(
    operand: Expr,
    indent: String,
    selfTypeName: String?,
): String {
    val rendered = renderExpr(operand, indent, selfTypeName)
    val recv = if (isAtomicPostfixReceiver(operand, rendered)) rendered else "($rendered)"
    return "$recv.inv()"
}

internal fun KotlinJ2KEmitter.isKnownIntegerBitmaskExpr(expr: Expr): Boolean =
    when (expr) {
        is Expr.Lit -> expr.lit is Lit.Int || expr.lit is Lit.Byte
        is Expr.Paren -> isKnownIntegerBitmaskExpr(expr.expr)
        is Expr.Group -> isKnownIntegerBitmaskExpr(expr.expr)
        is Expr.Unary -> (expr.op is UnOp.NotOp || expr.op is UnOp.Neg) && isKnownIntegerBitmaskExpr(expr.expr)
        is Expr.Cast -> {
            val target = renderType(expr.ty, null)
            target in NUMERIC_CAST_TARGETS || resolveNumericKind(target) != null
        }
        is Expr.Binary ->
            when (expr.op) {
                is BinOp.BitAnd, is BinOp.BitOr, is BinOp.BitXor, is BinOp.Shl, is BinOp.Shr,
                is BinOp.Add, is BinOp.Sub, is BinOp.Mul,
                -> true
                else -> false
            }
        is Expr.Path -> {
            val last =
                expr.path.segments
                    .last()
                    ?.ident
                    ?.toString()
                    ?.removePrefix("r#")
            last != null &&
                last != "true" &&
                last != "false" &&
                (isScreamingSnakeIdent(last) || last in inspector.declaredConstNames)
        }
        else -> false
    }

internal fun KotlinJ2KEmitter.isKnownBooleanCastSource(expr: Expr): Boolean =
    when (expr) {
        is Expr.Lit -> expr.lit is Lit.Bool
        is Expr.Paren -> isKnownBooleanCastSource(expr.expr)
        is Expr.Group -> isKnownBooleanCastSource(expr.expr)
        is Expr.Let -> true
        is Expr.Unary -> expr.op is UnOp.NotOp && !isKnownIntegerBitmaskExpr(expr.expr)
        is Expr.Binary ->
            when (expr.op) {
                is BinOp.Eq, is BinOp.Ne, is BinOp.Lt, is BinOp.Le, is BinOp.Gt, is BinOp.Ge,
                is BinOp.And, is BinOp.Or,
                -> true
                else -> false
            }
        is Expr.MethodCall -> {
            val m = expr.method.toString()
            m == "is_empty" ||
                m == "is_some" ||
                m == "is_none" ||
                m == "is_ok" ||
                m == "is_err" ||
                m == "contains" ||
                m == "starts_with" ||
                m == "ends_with"
        }
        else -> false
    }

internal fun KotlinJ2KEmitter.renderCastExpression(
    inner: String,
    targetType: String,
    sourceExpr: Expr,
    selfTypeName: String? = null,
    pointerLayoutType: SynType? = null,
): String {
    if (targetType == "RustAny") {
        usesRustAny = true
        return if (inferExprRenderedType(sourceExpr, selfTypeName) == "RustAny") inner else "RustAny.from($inner)"
    }
    val targetBase = targetType.removeSuffix("?").trim()
    val aliasPtrTarget =
        (inspector.typeAliasTargets[targetBase] as? SynType.Ptr)?.let { renderType(it, selfTypeName) }
    val resolvedPtrTarget = if (rawPointerPointeeType(targetType) != null) targetType else aliasPtrTarget
    val pointerPointee = rawPointerPointeeType(resolvedPtrTarget)
    if (pointerPointee != null) {
        val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
        val ptrCtor = if (isMutRawPointerRenderedType(resolvedPtrTarget)) "MutPtr" else "ConstPtr"
        CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.ptr.$ptrCtor")
        if (inner in setOf("0", "0L", "0u", "0uL", "null")) {
            return "$ptrCtor<$pointerPointee>(0L)"
        }
        val reference = unwrapParenOrGroupExpr(sourceExpr) as? Expr.Reference
        val referencePlace = reference?.expr?.let { unwrapParenOrGroupExpr(it) } as? Expr.Path
        val referenceLocal =
            referencePlace
                ?.takeIf { it.qself == null && it.path.segments.size == 1 }
                ?.path
                ?.segments
                ?.first()
                ?.ident
                ?.toString()
                ?.removePrefix("r#")
                ?.let { scopeBuilder.resolveLocalKotlinName(it) ?: rustValueIdentToKotlin(it) }
        val referenceStorage = referenceLocal?.let { currentAddressableLocals[it] }
        if (referenceStorage != null) {
            return if (referenceLocal in currentAddressableArrayElementTypes) {
                "$ptrCtor<$pointerPointee>($referenceStorage.elementMutPtr(0).rawAddress)"
            } else {
                "$ptrCtor<$pointerPointee>($referenceStorage.asMutPtr().rawAddress)"
            }
        }
        val sourceType = inferExprRenderedType(sourceExpr, selfTypeName)
        val sourceReceiver = unwrapParenOrGroupExpr(sourceExpr) as? Expr.Path
        val unwrappedRefReceiver = (reference?.expr?.let { unwrapParenOrGroupExpr(it) } as? Expr.Path) ?: sourceReceiver
        if (unwrappedRefReceiver?.qself == null && unwrappedRefReceiver?.path?.isIdent("self") == true &&
            selfTypeName != null && currentTypedLocals["this"] == selfTypeName
        ) {
            val declaration = transparentDeclaration(selfTypeName)
            if (declaration != null && sourceTransparentField(declaration, selfTypeName) != null) {
                return "$ptrCtor<$pointerPointee>(this.fieldStorage.rawAddress)"
            }
        }
        val borrowedPointee = mutableReferencePointeeType(sourceType)
        if (borrowedPointee != null) {
            val recv = if (isAtomicPostfixReceiver(sourceExpr, inner)) inner else "($inner)"
            return "$ptrCtor<$pointerPointee>($recv.asMutPtr().rawAddress)"
        }
        val sourceAliasPtr =
            sourceType?.removeSuffix("?")?.trim()?.let { st ->
                (inspector.typeAliasTargets[st] as? SynType.Ptr)?.let { renderType(it, selfTypeName) }
            }
        val effectiveSourcePtrType = if (rawPointerPointeeType(sourceType) != null) sourceType else sourceAliasPtr
        if (rawPointerPointeeType(effectiveSourcePtrType) != null ||
            (sourceType != null && scopedAbiKeysForRenderedType(sourceType.removeSuffix("?")).any { it in headerContext.cratePointerTypeAliases })
        ) {
            if (effectiveSourcePtrType == resolvedPtrTarget && sourceType == targetType) return inner
            val rawSource = unwrapParenOrGroupExpr(sourceExpr)
            if (rawSource is Expr.Path && isAtomicPostfixReceiver(sourceExpr, inner)) {
                return "$ptrCtor<$pointerPointee>($inner.rawAddress, $inner.rawMeta)"
            }
            return "($inner).let { ptrSrc -> $ptrCtor<$pointerPointee>(ptrSrc.rawAddress, ptrSrc.rawMeta) }"
        }
        val localTyForPtr = currentTypedLocals[inner]
        if (isKnownIntegerBitmaskExpr(sourceExpr) ||
            (sourceExpr is Expr.Unary && (sourceExpr.op is UnOp.Neg || sourceExpr.op is UnOp.NotOp)) ||
            (localTyForPtr != null && resolveNumericKind(localTyForPtr) != null) ||
            inferExprNumericKind(sourceExpr, selfTypeName) != null
        ) {
            val recv = if (isAtomicPostfixReceiver(sourceExpr, inner)) inner else "($inner)"
            val longArg = convertNumericReceiver(recv, "Long", inferExprNumericKind(sourceExpr, selfTypeName))
            return "$ptrCtor<$pointerPointee>($longArg)"
        }
        val castHelper = if (ptrCtor == "MutPtr") "mutPtrFromRustCast" else "constPtrFromRustCast"
        CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.mem.$castHelper")
        return "$castHelper<$pointerPointee>($inner)"
    }
    val targetNumericKind = resolveNumericKind(targetType)
    val canonicalNumericTarget =
        when (targetNumericKind) {
            NumericKind.I8 -> "Byte"
            NumericKind.I16 -> "Short"
            NumericKind.I32 -> "Int"
            NumericKind.I64 -> "Long"
            NumericKind.I128 -> {
                usesI128 = true
                "I128"
            }
            NumericKind.U8 -> "UByte"
            NumericKind.U16 -> "UShort"
            NumericKind.U32 -> "UInt"
            NumericKind.U64 -> "ULong"
            NumericKind.U128 -> {
                usesU128 = true
                "U128"
            }
            NumericKind.F16 -> "F16"
            NumericKind.F32 -> kotlinF32TypeName()
            NumericKind.F64 -> "Double"
            NumericKind.F128 -> "F128"
            null -> if (targetType == "Char" || targetType == "RustChar") targetType else null
        }
    val sourceInferredForNum = inferExprRenderedType(sourceExpr, selfTypeName)
    val isSourceRawPtrOrAlias =
        rawPointerPointeeType(sourceInferredForNum) != null ||
            (
                sourceInferredForNum != null && (
                    inspector.typeAliasTargets[sourceInferredForNum.removeSuffix("?")] is SynType.Ptr ||
                        scopedAbiKeysForRenderedType(sourceInferredForNum.removeSuffix("?")).any { it in headerContext.cratePointerTypeAliases }
                )
            )
    if (canonicalNumericTarget != null && isSourceRawPtrOrAlias) {
        val recv = if (isAtomicPostfixReceiver(sourceExpr, inner)) inner else "($inner)"
        return convertNumericReceiver("$recv.rawAddress", canonicalNumericTarget, NumericKind.I64)
    }
    if (canonicalNumericTarget != null && isKnownBooleanCastSource(sourceExpr)) {
        val cleanCond =
            if (hasOuterBalancedParens(inner)) {
                inner.substring(1, inner.length - 1)
            } else {
                inner
            }
        val intBranch = "(if ($cleanCond) 1 else 0)"
        return when (canonicalNumericTarget) {
            "Byte" -> "$intBranch.toByte()"
            "Short" -> "$intBranch.toShort()"
            "Int" -> intBranch
            "Long" -> "(if ($cleanCond) 1L else 0L)"
            "I128" -> "(if ($cleanCond) I128.ONE else I128.ZERO)"
            "UByte" -> "(if ($cleanCond) 1u else 0u).toUByte()"
            "UShort" -> "(if ($cleanCond) 1u else 0u).toUShort()"
            "UInt" -> "(if ($cleanCond) 1u else 0u)"
            "ULong" -> "(if ($cleanCond) 1uL else 0uL)"
            "U128" -> "(if ($cleanCond) U128.ONE else U128.ZERO)"
            "Float", "kotlin.Float" -> "(if ($cleanCond) 1.0f else 0.0f)"
            "Double" -> "(if ($cleanCond) 1.0 else 0.0)"
            "Char" -> "$intBranch.toChar()"
            "RustChar" -> {
                usesRustChar = true
                "(if ($cleanCond) RustChar(1u) else RustChar(0u))"
            }
            else -> "($inner as $targetType)"
        }
    }
    val unwrappedSource = unwrapParenOrGroupExpr(unwrapTransparentCastExpr(sourceExpr))
    val localTy = currentTypedLocals[inner]
    if (canonicalNumericTarget != null) {
        if (!headerContext.rawAbiMode) {
            val cleanLocalBase = localTy?.removeSuffix("?")
            if (
                inner in currentNullableLocals ||
                localTy?.endsWith("?") == true
            ) {
                error("Cannot expose an address for $inner ($localTy): the Rust pointer requires address-bearing storage")
            }
            if (
                cleanLocalBase != null &&
                (
                    cleanLocalBase.endsWith("Callback") ||
                        cleanLocalBase.endsWith("Fn") ||
                        cleanLocalBase in synthesizedSamDeclarations
                )
            ) {
                val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
                CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.ffi.ExternFnPtr")
                val recv = if (isAtomicPostfixReceiver(sourceExpr, inner)) inner else "($inner)"
                return convertNumericReceiver(
                    "(($recv as? ExternFnPtr)?.rawAddress ?: error(\"Kotlin closure callback has no native C function address\"))",
                    canonicalNumericTarget,
                    NumericKind.I64,
                )
            }
            val fieldTy = resolveFieldRenderedType(sourceExpr, selfTypeName)
            if (fieldTy != null && rawPointerPointeeType(fieldTy) != null) {
                val recv = if (isAtomicPostfixReceiver(sourceExpr, inner)) inner else "($inner)"
                return convertNumericReceiver("$recv.rawAddress", canonicalNumericTarget, NumericKind.I64)
            }
            if (fieldTy != null && fieldTy.endsWith("?")) {
                error("Cannot expose an address for $inner ($fieldTy): the Rust field requires address-bearing storage")
            }
        }
        val inferredRustCharSource =
            headerContext.scalarChar &&
                inferExprRenderedType(sourceExpr, selfTypeName)?.removeSuffix("?")?.trim() == "RustChar"
        if ((unwrappedSource as? Expr.Lit)?.lit is Lit.Char || inferredRustCharSource) {
            if (canonicalNumericTarget == "RustChar") {
                return inner
            }
            val recv = if (isAtomicPostfixReceiver(sourceExpr, inner)) inner else "($inner)"
            val charCodeKind = if (headerContext.scalarChar) NumericKind.U32 else NumericKind.I32
            return convertNumericReceiver("$recv.code", canonicalNumericTarget, charCodeKind)
        }
        val sourceKind = inferExprNumericKind(sourceExpr, selfTypeName)
        if (sourceKind == null && unwrappedSource is Expr.Path && unwrappedSource.qself == null) {
            val segs = unwrappedSource.path.segments.toList()
            if (segs.size >= 2) {
                val prevIdentStr = segs[segs.size - 2].ident.toString()
                val prevRaw = prevIdentStr.removePrefix("r#")
                val prevKtType = rustTypeIdentToKotlin(prevRaw)
                val lastIdentStr = segs.last().ident.toString()
                val lastRaw = lastIdentStr.removePrefix("r#")
                val isPrevKnownType =
                    (
                        prevRaw.first().isUpperCase() ||
                            prevKtType in inspector.declaredTypeNames ||
                            scopedAbiKeysForRenderedType(prevKtType).any { it in headerContext.crateSymbolSubpackages }
                    ) &&
                        resolveNumericKind(prevKtType) == null
                if (isPrevKnownType &&
                    lastRaw !in setOf("MAX", "MIN", "BITS", "ZERO") &&
                    lastRaw.first().isUpperCase() &&
                    (!isScreamingSnakeIdent(lastRaw) || !prevRaw.first().isUpperCase())
                ) {
                    val recv = if (isAtomicPostfixReceiver(sourceExpr, inner)) inner else "($inner)"
                    val enumKind =
                        scopedAbiKeysForRenderedType(prevKtType).firstNotNullOfOrNull {
                            numericKindFromScannerCode(headerContext.crateNumericTypeKinds[it])
                        } ?: NumericKind.U32
                    return convertNumericReceiver("$recv.value", canonicalNumericTarget, enumKind)
                }
            }
        }
        if ((sourceKind != null && numericKindToPureTypeName(sourceKind) == canonicalNumericTarget) ||
            isAlreadyRenderedAsNumericKind(inner, resolveNumericKind(canonicalNumericTarget))
        ) {
            return inner
        }
        val recv = if (isAtomicPostfixReceiver(sourceExpr, inner)) inner else "($inner)"
        return convertNumericReceiver(recv, canonicalNumericTarget, sourceKind)
    }
    val targetPtrAliasKind =
        scopedAbiKeysForRenderedType(targetBase)
            .firstNotNullOfOrNull { headerContext.cratePointerTypeAliases[it] }
    if (targetPtrAliasKind != null) {
        val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
        val ptrCtor = if (targetPtrAliasKind.startsWith("const:")) "ConstPtr" else "MutPtr"
        CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.ptr.$ptrCtor")
        val isZeroLit = inner == "0" || inner == "0L" || inner == "0u" || inner == "0uL" || inner == "null"
        if (isZeroLit) {
            return "$ptrCtor(0L)"
        }
        if (isSourceRawPtrOrAlias) {
            val recv = if (isAtomicPostfixReceiver(sourceExpr, inner)) inner else "($inner)"
            return "$ptrCtor($recv.rawAddress)"
        }
        if (inner in currentNullableLocals) {
            error("Cannot cast $inner ($localTy) to $targetType: the Rust pointer requires address-bearing storage")
        }
        if (isKnownIntegerBitmaskExpr(sourceExpr) ||
            (sourceExpr is Expr.Unary && (sourceExpr.op is UnOp.Neg || sourceExpr.op is UnOp.NotOp)) ||
            (localTy != null && resolveNumericKind(localTy) != null) ||
            inferExprNumericKind(sourceExpr, selfTypeName) != null
        ) {
            val recv = if (isAtomicPostfixReceiver(sourceExpr, inner)) inner else "($inner)"
            val longArg = convertNumericReceiver(recv, "Long", inferExprNumericKind(sourceExpr, selfTypeName))
            return "$ptrCtor($longArg)"
        }
    }
    if (targetType.endsWith("?")) {
        val nonNullTarget = targetType.removeSuffix("?")
        val isZeroLit = inner == "0" || inner == "0L" || inner == "0u" || inner == "0uL" || inner == "null"
        if (isZeroLit) {
            return "null"
        }
        val isNumericSource =
            isKnownIntegerBitmaskExpr(sourceExpr) ||
                isKnownBooleanCastSource(sourceExpr) ||
                (sourceExpr is Expr.Unary && sourceExpr.op is UnOp.Neg) ||
                (localTy != null && resolveNumericKind(localTy) != null) ||
                inferExprNumericKind(sourceExpr, selfTypeName) != null
        if (resolveNumericKind(nonNullTarget) != null && isNumericSource) {
            return renderCastExpression(inner, nonNullTarget, sourceExpr, selfTypeName)
        }
        if (isNumericSource) {
            return if (nonNullTarget == "String") "\"\\uFFFF\"" else "null"
        }
    }
    if (targetType == inner) return inner
    return "(($inner as Any?) as $targetType)"
}

private fun hasTopLevelOperatorOutsidePostfix(s: String): Boolean {
    var depth = 0
    for (i in s.indices) {
        when (s[i]) {
            '(', '[', '{' -> depth++
            ')', ']', '}' -> depth--
            ' ' -> if (depth == 0) return true
            '+', '-', '*', '/', '%', '&', '|', '^', '=', '<', '>' -> if (depth == 0 && i > 0) return true
        }
    }
    return false
}

internal fun KotlinJ2KEmitter.isAlreadyRenderedAsNumericKind(rendered: String, kind: NumericKind?): Boolean {
    if (kind == null) return false
    var s = rendered.trim()
    while (true) {
        while (s.length >= 2 && s.startsWith("(") && s.endsWith(")") && hasOuterBalancedParens(s)) {
            s = s.substring(1, s.length - 1).trim()
        }
        if (s.endsWith(".inv()")) {
            s = s.removeSuffix(".inv()").trim()
        } else {
            break
        }
    }
    if (hasTopLevelOperatorOutsidePostfix(s)) {
        return (kind == NumericKind.U64 && s.endsWith("uL") && !s.contains(".to")) ||
            (kind == NumericKind.U32 && s.endsWith("u") && !s.endsWith("uL") && !s.contains(".to")) ||
            (kind == NumericKind.I64 && s.endsWith("L") && !s.endsWith("uL") && !s.contains(".to"))
    }
    val expectedSuffix =
        when (kind) {
            NumericKind.I8 -> ".toByte()"
            NumericKind.U8 -> ".toUByte()"
            NumericKind.I16 -> ".toShort()"
            NumericKind.U16 -> ".toUShort()"
            NumericKind.I32 -> ".toInt()"
            NumericKind.U32 -> ".toUInt()"
            NumericKind.I64 -> ".toLong()"
            NumericKind.U64 -> ".toULong()"
            NumericKind.F16 -> ".toF16()"
            NumericKind.F32 -> ".toFloat()"
            NumericKind.F64 -> ".toDouble()"
            NumericKind.F128 -> ".toF128()"
            NumericKind.I128 -> ".toI128()"
            NumericKind.U128 -> ".toU128()"
        }
    if (s.endsWith(expectedSuffix)) return true
    if (kind == NumericKind.F16 && (s.startsWith("F16.") || s == "F16()")) return true
    if (kind == NumericKind.F128 && (s.startsWith("F128.") || s == "F128()")) return true
    if (s.endsWith(".code") && kind == NumericKind.I32) return true
    if (s.endsWith(".size") && kind == NumericKind.I32) return true
    if (s.endsWith(".hashCode()") && kind == NumericKind.I32) return true
    if (s.endsWith(".rawValue") && kind == NumericKind.I64) return true
    val withoutSign = if (s.startsWith("-")) s.drop(1).trim() else s
    val isDigitsOrHex =
        withoutSign.isNotEmpty() &&
            (
                withoutSign.all { it.isDigit() || it == '_' } ||
                    (
                        (withoutSign.startsWith("0x") || withoutSign.startsWith("0X")) &&
                            withoutSign.drop(2).isNotEmpty() &&
                            withoutSign.drop(2).all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == '_' }
                    )
            )
    if (isDigitsOrHex && kind == NumericKind.I32) return true
    if ((withoutSign.endsWith("uL") || withoutSign.endsWith("UL")) && kind == NumericKind.U64) {
        val core = withoutSign.dropLast(2)
        if (core.isNotEmpty() && (core.all { it.isDigit() || it == '_' } || ((core.startsWith("0x") || core.startsWith("0X")) && core.drop(2).all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == '_' }))) {
            return true
        }
    }
    if ((withoutSign.endsWith("u") || withoutSign.endsWith("U")) && !withoutSign.endsWith("uL") && !withoutSign.endsWith("UL") && kind == NumericKind.U32) {
        val core = withoutSign.dropLast(1)
        if (core.isNotEmpty() && (core.all { it.isDigit() || it == '_' } || ((core.startsWith("0x") || core.startsWith("0X")) && core.drop(2).all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == '_' }))) {
            return true
        }
    }
    if (withoutSign.endsWith("L") && !withoutSign.endsWith("uL") && !withoutSign.endsWith("UL") && kind == NumericKind.I64) {
        val core = withoutSign.dropLast(1)
        if (core.isNotEmpty() && (core.all { it.isDigit() || it == '_' } || ((core.startsWith("0x") || core.startsWith("0X")) && core.drop(2).all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == '_' }))) {
            return true
        }
    }
    return false
}

internal fun KotlinJ2KEmitter.convertNumericReceiver(
    recv: String,
    canonicalNumericTarget: String,
    sourceKind: NumericKind? = null,
): String {
    val targetKind = resolveNumericKind(canonicalNumericTarget)
    if ((sourceKind != null && numericKindToPureTypeName(sourceKind) == canonicalNumericTarget) ||
        isAlreadyRenderedAsNumericKind(recv, targetKind)
    ) {
        return recv
    }
    return when (canonicalNumericTarget) {
        "Byte" -> "$recv.toByte()"
        "Short" -> "$recv.toShort()"
        "Int" -> "$recv.toInt()"
        "Long" -> "$recv.toLong()"
        "UByte" -> "$recv.toUByte()"
        "UShort" -> "$recv.toUShort()"
        "UInt" -> "$recv.toUInt()"
        "ULong" -> "$recv.toULong()"
        "U128" ->
            when (sourceKind) {
                NumericKind.U128 -> recv
                NumericKind.I128 -> "$recv.toU128()"
                NumericKind.U64 -> "U128.fromULong($recv)"
                NumericKind.U32 -> "U128.fromUInt($recv)"
                NumericKind.U16 -> "U128.fromUShort($recv)"
                NumericKind.U8 -> "U128.fromUByte($recv)"
                NumericKind.I64 -> "U128.fromLong($recv)"
                NumericKind.I32 -> "U128.fromInt($recv)"
                NumericKind.I16 -> "U128.fromShort($recv)"
                NumericKind.I8 -> "U128.fromByte($recv)"
                NumericKind.F16, NumericKind.F32, NumericKind.F64, NumericKind.F128 -> "U128.fromULong($recv.toULong())"
                null ->
                    when {
                        recv.endsWith("uL") -> "U128.fromULong($recv)"
                        recv.endsWith("u") -> "U128.fromUInt($recv)"
                        else -> "U128.fromLong($recv.toLong())"
                    }
            }
        "I128" ->
            when (sourceKind) {
                NumericKind.I128 -> recv
                NumericKind.U128 -> "$recv.toI128()"
                NumericKind.I64 -> "I128.fromLong($recv)"
                NumericKind.I32 -> "I128.fromInt($recv)"
                NumericKind.I16 -> "I128.fromShort($recv)"
                NumericKind.I8 -> "I128.fromByte($recv)"
                NumericKind.U64 -> "I128.fromULong($recv)"
                NumericKind.U32 -> "I128.fromUInt($recv)"
                NumericKind.U16 -> "I128.fromUShort($recv)"
                NumericKind.U8 -> "I128.fromUByte($recv)"
                NumericKind.F16, NumericKind.F32, NumericKind.F64, NumericKind.F128 -> "I128.fromLong($recv.toLong())"
                null ->
                    when {
                        recv.endsWith("uL") -> "I128.fromULong($recv)"
                        recv.endsWith("u") -> "I128.fromUInt($recv)"
                        else -> "I128.fromLong($recv.toLong())"
                    }
            }
        "Float", "kotlin.Float" -> "$recv.toFloat()"
        "Double" -> "$recv.toDouble()"
        "F16" ->
            when (sourceKind) {
                NumericKind.F16 -> recv
                NumericKind.F32 -> "F16.fromFloat($recv)"
                NumericKind.F64 -> "F16.fromDouble($recv)"
                NumericKind.I8 -> "F16.fromByte($recv)"
                NumericKind.I16 -> "F16.fromShort($recv)"
                NumericKind.I32 -> "F16.fromInt($recv)"
                NumericKind.I64 -> "F16.fromLong($recv)"
                NumericKind.U8 -> "F16.fromUByte($recv)"
                NumericKind.U16 -> "F16.fromUShort($recv)"
                NumericKind.U32 -> "F16.fromUInt($recv)"
                NumericKind.U64 -> "F16.fromULong($recv)"
                else -> "F16.fromDouble($recv.toDouble())"
            }
        "F128" ->
            when (sourceKind) {
                NumericKind.F128 -> recv
                NumericKind.F16 -> "F128.fromF16($recv)"
                NumericKind.F32 -> "F128.fromFloat($recv)"
                NumericKind.F64 -> "F128.fromDouble($recv)"
                NumericKind.I8 -> "F128.fromByte($recv)"
                NumericKind.I16 -> "F128.fromShort($recv)"
                NumericKind.I32 -> "F128.fromInt($recv)"
                NumericKind.I64 -> "F128.fromLong($recv)"
                NumericKind.U8 -> "F128.fromUByte($recv)"
                NumericKind.U16 -> "F128.fromUShort($recv)"
                NumericKind.U32 -> "F128.fromUInt($recv)"
                NumericKind.U64 -> "F128.fromULong($recv)"
                else -> "F128.fromDouble($recv.toDouble())"
            }
        "Char" -> "$recv.toInt().toChar()"
        "RustChar" -> {
            usesRustChar = true
            "RustChar(${convertNumericReceiver(recv, "UInt", sourceKind)})"
        }
        else -> recv
    }
}

internal fun KotlinJ2KEmitter.resolveFieldRenderedType(expr: Expr, selfTypeName: String? = null): String? {
    val fieldExpr = unwrapTransparentCastExpr(expr) as? Expr.Field ?: return null
    return inferExprRenderedType(fieldExpr, selfTypeName)
}

internal fun KotlinJ2KEmitter.unwrapTransparentCastExpr(expr: Expr): Expr =
    when (expr) {
        is Expr.Group -> unwrapTransparentCastExpr(expr.expr)
        is Expr.Reference -> unwrapTransparentCastExpr(expr.expr)
        is Expr.RawAddr -> unwrapTransparentCastExpr(expr.expr)
        is Expr.Await -> unwrapTransparentCastExpr(expr.base)
        is Expr.Unary -> if (expr.op is UnOp.Deref) unwrapTransparentCastExpr(expr.expr) else expr
        else -> expr
    }

internal fun KotlinJ2KEmitter.isAtomicPostfixReceiver(sourceExpr: Expr, rendered: String): Boolean {
    if (rendered.isEmpty() || rendered.first() == '-' || rendered.first() == '!') return false
    val core = unwrapTransparentCastExpr(sourceExpr)
    if (core is Expr.Paren && hasOuterBalancedParens(rendered)) return true
    return when (core) {
        is Expr.Path, is Expr.Field, is Expr.Lit ->
            rendered.all { it.isLetterOrDigit() || it == '_' || it == '.' || it == '\'' || it == '"' } &&
                !rendered.contains("..")
        is Expr.MethodCall, is Expr.Index, is Expr.Call, is Expr.Tuple, is Expr.Array ->
            hasNoTopLevelSpacesOrColons(rendered)
        else -> false
    }
}

internal fun KotlinJ2KEmitter.hasOuterBalancedParens(text: String): Boolean {
    if (text.length < 2 || text.first() != '(' || text.last() != ')') return false
    var depth = 0
    var inString = false
    var inChar = false
    var escape = false
    for ((idx, ch) in text.withIndex()) {
        if (escape) {
            escape = false
            continue
        }
        if ((inString || inChar) && ch == '\\') {
            escape = true
            continue
        }
        if (!inChar && ch == '"') {
            inString = !inString
            continue
        }
        if (!inString && ch == '\'') {
            inChar = !inChar
            continue
        }
        if (inString || inChar) continue
        if (ch == '(') depth++
        if (ch == ')') {
            depth--
            if (depth == 0 && idx < text.lastIndex) return false
        }
    }
    return depth == 0 && !inString && !inChar
}

internal fun KotlinJ2KEmitter.hasNoTopLevelSpacesOrColons(text: String): Boolean {
    var depth = 0
    var inString = false
    var inChar = false
    var escape = false
    for (ch in text) {
        if (escape) {
            escape = false
            continue
        }
        if ((inString || inChar) && ch == '\\') {
            escape = true
            continue
        }
        if (!inChar && ch == '"') {
            inString = !inString
            continue
        }
        if (!inString && ch == '\'') {
            inChar = !inChar
            continue
        }
        if (inString || inChar) continue
        when (ch) {
            '(', '[', '{' -> depth++
            ')', ']', '}' -> if (depth > 0) depth--
            ' ', '\n', '\r', '\t', ':' -> if (depth == 0) return false
        }
    }
    return depth == 0 && !inString && !inChar
}

internal fun KotlinJ2KEmitter.renderBinOp(op: BinOp): String =
    when (op) {
        is BinOp.Add -> "+"
        is BinOp.Sub -> "-"
        is BinOp.Mul -> "*"
        is BinOp.Div -> "/"
        is BinOp.Rem -> "%"
        is BinOp.And -> "&&"
        is BinOp.Or -> "||"
        is BinOp.BitXor -> "xor"
        is BinOp.BitAnd -> "and"
        is BinOp.BitOr -> "or"
        is BinOp.Shl -> "shl"
        is BinOp.Shr -> "shr"
        is BinOp.Eq -> "=="
        is BinOp.Lt -> "<"
        is BinOp.Le -> "<="
        is BinOp.Ne -> "!="
        is BinOp.Ge -> ">="
        is BinOp.Gt -> ">"
        is BinOp.AddAssign -> "+="
        is BinOp.SubAssign -> "-="
        is BinOp.MulAssign -> "*="
        is BinOp.DivAssign -> "/="
        is BinOp.RemAssign -> "%="
        is BinOp.BitXorAssign -> "="
        is BinOp.BitAndAssign -> "="
        is BinOp.BitOrAssign -> "="
        is BinOp.ShlAssign -> "="
        is BinOp.ShrAssign -> "="
    }

internal fun KotlinJ2KEmitter.renderLit(lit: Lit): String =
    when (lit) {
        is Lit.Str -> renderKotlinDoubleQuotedString(lit.value.value())
        is Lit.ByteStr -> {
            val text =
                lit.value
                    .value()
                    .map { it.toInt().toChar() }
                    .joinToString("")
            val encoded = "${renderKotlinDoubleQuotedString(text)}.encodeToByteArray()"
            val targetTy = currentExpectedParseTargetType?.removeSuffix("?")?.trim()
            if (targetTy == "List<UByte>" || targetTy == "MutableList<UByte>") {
                "$encoded.map { it.toUByte() }"
            } else if (targetTy == "UByteArray") {
                "$encoded.toUByteArray()"
            } else {
                encoded
            }
        }
        is Lit.CStr -> {
            val text = lit.value.value().decodeToString()
            renderKotlinDoubleQuotedString(text)
        }
        is Lit.Byte -> "${lit.value.value()}u"
        is Lit.Char -> renderKotlinCharLiteral(lit.value.value())
        is Lit.Int -> {
            val suffix = lit.value.suffix()
            val rawToken = lit.value.token().toString()
            val rawDigits =
                if (suffix.isNotEmpty() && rawToken.endsWith(suffix)) {
                    rawToken.dropLast(suffix.length)
                } else {
                    rawToken
                }
            val isOctal = rawDigits.startsWith("0o") || rawDigits.startsWith("0O")
            val is128Context =
                suffix == "u128" ||
                    suffix == "i128" ||
                    currentExpectedNumericKind == NumericKind.U128 ||
                    currentExpectedNumericKind == NumericKind.I128 ||
                    parseUnsigned128LiteralDigits(rawDigits)?.exceeds64BitUnsigned == true
            val digits =
                when {
                    rawDigits.startsWith("0x") || rawDigits.startsWith("0X") -> rawDigits
                    rawDigits.startsWith("0b") || rawDigits.startsWith("0B") ->
                        if (is128Context) rawDigits else binaryDigitsToHexLiteral(rawDigits)
                    isOctal && is128Context -> rawDigits
                    else -> lit.value.base10Digits()
                }
            renderIntLiteral(digits, suffix)
        }
        is Lit.Float -> renderFloatLiteral(lit.value.base10Digits(), lit.value.suffix())
        is Lit.Bool -> lit.value.value().toString()
        is Lit.Verbatim -> lit.value.toString()
    }

internal fun KotlinJ2KEmitter.binaryDigitsToHexLiteral(rawDigits: String): String {
    val bits = rawDigits.substring(2).replace("_", "")
    if (bits.isEmpty() || bits.any { it != '0' && it != '1' }) {
        return rawDigits
    }
    val pad = (4 - bits.length % 4) % 4
    val padded = "0".repeat(pad) + bits
    val hexChars = "0123456789ABCDEF"
    val hex = StringBuilder(padded.length / 4)
    var idx = 0
    while (idx < padded.length) {
        val nibble =
            ((padded[idx] - '0') shl 3) or
                ((padded[idx + 1] - '0') shl 2) or
                ((padded[idx + 2] - '0') shl 1) or
                (padded[idx + 3] - '0')
        hex.append(hexChars[nibble])
        idx += 4
    }
    val trimmed = hex.toString().trimStart('0').ifEmpty { "0" }
    return "0x$trimmed"
}

internal fun KotlinJ2KEmitter.renderIntLiteral(digits: String, suffix: String): String {
    val parsed128 = parseUnsigned128LiteralDigits(digits)
    if (suffix == "u128") {
        usesU128 = true
        return if (parsed128 != null) formatU128ConstructorCall(parsed128.high, parsed128.low) else "U128()"
    }
    if (suffix == "i128") {
        usesI128 = true
        return if (parsed128 != null) formatI128ConstructorCall(parsed128.high, parsed128.low) else "I128()"
    }
    if (suffix.isEmpty()) {
        if (currentExpectedNumericKind == NumericKind.U128) {
            usesU128 = true
            return if (parsed128 != null) formatU128ConstructorCall(parsed128.high, parsed128.low) else "U128()"
        }
        if (currentExpectedNumericKind == NumericKind.I128) {
            usesI128 = true
            return if (parsed128 != null) formatI128ConstructorCall(parsed128.high, parsed128.low) else "I128()"
        }
        if (parsed128?.exceeds64BitUnsigned == true) {
            usesU128 = true
            return formatU128ConstructorCall(parsed128.high, parsed128.low)
        }
    }
    val clean = digits.replace("_", "")
    val low64 = if (parsed128 != null && parsed128.high == 0uL) parsed128.low else null
    if (suffix.isEmpty()) {
        return when (currentExpectedNumericKind) {
            NumericKind.U8 ->
                if (low64 != null && low64 > 0xFFuL) "${clean}u.toUByte()" else "${clean}u"
            NumericKind.U16 ->
                if (low64 != null && low64 > 0xFFFFuL) "${clean}u.toUShort()" else "${clean}u"
            NumericKind.U32 ->
                if (low64 != null && low64 > 0xFFFFFFFFuL) "${clean}uL.toUInt()" else "${clean}u"
            NumericKind.U64 -> "${clean}uL"
            NumericKind.I8 ->
                if (low64 != null && low64 > 0x7FuL) "$clean.toByte()" else clean
            NumericKind.I16 ->
                if (low64 != null && low64 > 0x7FFFuL) "$clean.toShort()" else clean
            NumericKind.I32 ->
                if (low64 != null && low64 > 0xFFFFFFFFuL) {
                    "${clean}L.toInt()"
                } else if (low64 != null && low64 >= 0x80000000uL) {
                    "$clean.toInt()"
                } else {
                    clean
                }
            NumericKind.I64 ->
                if (low64 != null && low64 >= 0x8000000000000000uL) "${clean}uL.toLong()" else "${clean}L"
            NumericKind.F16 -> if (clean.all { it.isDigit() }) "F16.fromFloat($clean.0f)" else "F16.fromDouble(($clean).toDouble())"
            NumericKind.F32 -> "$clean.0f"
            NumericKind.F64 -> "$clean.0"
            NumericKind.F128 -> if (clean.all { it.isDigit() }) "F128.fromDouble($clean.0)" else "F128.fromDouble(($clean).toDouble())"
            else ->
                if (low64 != null && low64 >= 0x8000000000000000uL) "${clean}uL" else clean
        }
    }
    return when (suffix) {
        "u8" -> "${clean}u.toUByte()"
        "u16" -> "${clean}u.toUShort()"
        "u32" -> "${clean}u"
        "usize" ->
            if (headerContext.rawAbiMode || inExternAbiSignature || currentExpectedNumericKind == NumericKind.U64) {
                when (activeCfgProfile.pointerWidth) {
                    16 -> "${clean}u.toUShort()"
                    32 -> "${clean}u"
                    else -> "${clean}uL"
                }
            } else {
                clean
            }
        "u64" -> "${clean}uL"
        "i8" -> if (low64 != null && low64 in 0x80uL..0xFFuL) "$clean.toByte()" else clean
        "i16" -> if (low64 != null && low64 in 0x8000uL..0xFFFFuL) "$clean.toShort()" else clean
        "i32" -> if (low64 != null && low64 in 0x80000000uL..0xFFFFFFFFuL) "$clean.toInt()" else clean
        "isize" ->
            if (headerContext.rawAbiMode || inExternAbiSignature || currentExpectedNumericKind == NumericKind.I64) {
                when (activeCfgProfile.pointerWidth) {
                    16 -> "($clean).toShort()"
                    32 -> clean
                    else -> "${clean}L"
                }
            } else {
                clean
            }
        "i64" -> if (low64 != null && low64 >= 0x8000000000000000uL) "${clean}uL.toLong()" else "${clean}L"
        "f16" -> if (clean.all { it.isDigit() }) "F16.fromFloat($clean.0f)" else "F16.fromDouble(($clean).toDouble())"
        "f32" -> "$clean.0f"
        "f64" -> "$clean.0"
        "f128" -> if (clean.all { it.isDigit() }) "F128.fromDouble($clean.0)" else "F128.fromDouble(($clean).toDouble())"
        else -> clean
    }
}

internal fun KotlinJ2KEmitter.renderFloatLiteral(digits: String, suffix: String): String {
    val clean = digits.replace("_", "")
    val normalized = if (clean.endsWith('.')) "${clean}0" else clean
    return when {
        suffix == "f16" || (suffix.isEmpty() && currentExpectedNumericKind == NumericKind.F16) -> "F16.fromFloat(${normalized}f)"
        suffix == "f128" || (suffix.isEmpty() && currentExpectedNumericKind == NumericKind.F128) -> "F128.fromDouble($normalized)"
        suffix == "f32" || (suffix.isEmpty() && currentExpectedNumericKind == NumericKind.F32) -> "${normalized}f"
        else -> normalized
    }
}

internal fun KotlinJ2KEmitter.renderKotlinCharLiteral(codePoint: Int): String {
    if (headerContext.scalarChar) {
        usesRustChar = true
        return "RustChar(0x${codePoint.toString(16)}u)"
    }
    if (codePoint > 0xFFFF) {
        return "0x${codePoint.toString(16)}"
    }
    return when (val ch = codePoint.toChar()) {
        '\'' -> "'\\''"
        '\\' -> "'\\\\'"
        '\n' -> "'\\n'"
        '\r' -> "'\\r'"
        '\t' -> "'\\t'"
        '\b' -> "'\\b'"
        else -> if (codePoint in 32..126) "'$ch'" else "'\\u${codePoint.toString(16).padStart(4, '0')}'"
    }
}

internal fun KotlinJ2KEmitter.extractFnParamNumericKinds(
    item: Item.Fn,
    selfTypeName: String?,
): Pair<String, List<NumericKind?>>? =
    extractSigParamNumericKinds(item.sig, selfTypeName)

internal fun KotlinJ2KEmitter.extractSigParamNumericKinds(
    sig: Signature,
    selfTypeName: String?,
): Pair<String, List<NumericKind?>>? {
    val inputs = sig.inputs.toList()
    if (inputs.any { it is FnArg.Receiver }) {
        return null
    }
    val fnGenerics = sig.generics
    if (fnGenerics.params.any { it is GenericParam.ConstParam }) {
        return null
    }
    val fnTypeParams = collectGenericTypeParamNames(fnGenerics)
    val prevTypeParams = activeTypeParamNames
    activeTypeParamNames = activeTypeParamNames + fnTypeParams
    val kinds =
        try {
            inputs.map { arg ->
                val typed = arg as? FnArg.Typed ?: return null
                val unwrappedTy = unwrapSynType(typed.patType.ty)
                if (unwrappedTy is SynType.FnPtr ||
                    (!headerContext.rawAbiMode && unwrappedTy is SynType.Ptr) ||
                    extractOptionBareFn(unwrappedTy) != null
                ) {
                    null
                } else {
                    val renderedType = renderType(typed.patType.ty, selfTypeName)
                    if (renderedType in fnTypeParams) null else resolveNumericKind(renderedType)
                }
            }
        } finally {
            activeTypeParamNames = prevTypeParams
        }
    val fnName = rustFunctionIdentToKotlin(sig.ident.toString())
    return fnName to kinds
}

internal fun KotlinJ2KEmitter.renderCalleeExpr(funcExpr: Expr, indent: String, selfTypeName: String?): String {
    if (funcExpr is Expr.Path && funcExpr.qself == null) {
        return renderPathAsExpression(funcExpr.path, selfTypeName, asCallee = true)
    }
    return renderExpr(funcExpr, indent, selfTypeName)
}

internal fun KotlinJ2KEmitter.renderExprPath(exprPath: Expr.Path, selfTypeName: String?): String {
    val qself = exprPath.qself
    val path = exprPath.path
    if (qself != null) {
        val baseTy = renderType(qself.ty, selfTypeName)
        val allSegs = path.segments.toList()
        val lastSeg = allSegs.lastOrNull()
        val lastIdent = lastSeg?.ident?.toString()
        val rawTail = lastIdent?.removePrefix("r#")
        if (rawTail != null) {
            currentTypeParamWitnessCalls[baseTy to rawTail]?.let { return it }
        }
        if (qself.position > 0 && qself.position < allSegs.size) {
            val traitSegs = allSegs.take(qself.position)
            val remSegs = allSegs.drop(qself.position)
            if (traitSegs.isNotEmpty() && remSegs.size == 1) {
                val traitKt = rustTypeIdentToKotlin(traitSegs.last().ident.toString())
                val selfDesc = describeSynTypeForDisambiguation(qself.ty)
                val fnKt = rustFunctionIdentToKotlin(remSegs[0].ident.toString())
                if ((traitKt to selfDesc) in standaloneTraitImplKeys) {
                    val synthTraitPath =
                        Path(
                            leadingColon = path.leadingColon,
                            segments =
                                io.github.kotlinmania.syn.PathSegmentList().apply {
                                    traitSegs.forEach {
                                        push(it) {
                                            io.github.kotlinmania.syn.token.PathSep
                                                .default()
                                        }
                                    }
                                },
                        )
                    return disambiguateReceiverlessTraitImplFnName(synthTraitPath, fnKt, qself.ty)
                }
            }
        }
        val qselfSegs =
            (qself.ty as? SynType.Path)
                ?.takeIf { it.qself == null }
                ?.path
                ?.segments
                ?.toList()
                ?.map { it.ident.toString().removePrefix("r#") }
                .orEmpty()
        val qselfPrim =
            when {
                qselfSegs.size == 1 && qselfSegs[0] in PRIMITIVE_TYPE_NAMES -> qselfSegs[0]
                qselfSegs.size == 3 &&
                    qselfSegs[0] in setOf("core", "std") &&
                    qselfSegs[1] == "primitive" &&
                    qselfSegs[2] in PRIMITIVE_TYPE_NAMES -> qselfSegs[2]
                else -> null
            }
        val tailRawSegs = allSegs.drop(qself.position)
        if (qselfPrim != null && qself.position == 0 && tailRawSegs.size == 1) {
            val seg = tailRawSegs[0]
            renderPrimitiveAssociatedConst(
                listOf(qselfPrim, seg.ident.toString().removePrefix("r#")),
                hasTrailingArgs = seg.arguments !is PathArguments.None,
                selfTypeName = selfTypeName,
            )?.let { return it }
        }
        val tailSegs =
            tailRawSegs.map { seg ->
                renderPathSegmentInExpr(
                    rawIdent = seg.ident.toString(),
                    arguments = seg.arguments,
                    selfTypeName = selfTypeName,
                    isLast = true,
                    isSingleSegment = false,
                )
            }
        val receiverBase = baseTy.substringBefore('<').trim()
        return (listOf(receiverBase) + tailSegs).joinToString(".")
    }
    return renderPathAsExpression(path, selfTypeName, asCallee = false)
}

internal fun KotlinJ2KEmitter.isKotlinFloatShadowed(): Boolean =
    "Float" in inspector.declaredTypeNames ||
        "Float" in importedSimpleNameToPkg ||
        "Float" in headerContext.crateSymbolSubpackages ||
        resolveBestSubpackageForSymbol("Float") != null

internal fun KotlinJ2KEmitter.kotlinF32TypeName(): String =
    if (isKotlinFloatShadowed()) "kotlin.Float" else "Float"

internal fun KotlinJ2KEmitter.renderPrimitiveAssociatedConst(
    rawSegNames: List<String>,
    hasTrailingArgs: Boolean,
    selfTypeName: String? = null,
): String? {
    if (hasTrailingArgs || rawSegNames.isEmpty()) return null
    if (rawSegNames.size == 1 && scopeBuilder.resolveLocalKotlinName(rawSegNames[0]) != null) return null
    val mappedSelfSegs =
        if (rawSegNames.size == 2 && rawSegNames[0] == "Self" && selfTypeName != null) {
            val selfBase = selfTypeName.substringBefore('<').trim()
            val mappedPrim =
                when (selfBase) {
                    "Float", "kotlin.Float" -> "f32"
                    "Double" -> "f64"
                    "F16" -> "f16"
                    "F128" -> "f128"
                    "Byte" -> "i8"
                    "UByte" -> "u8"
                    "Short" -> "i16"
                    "UShort" -> "u16"
                    "Int" -> "i32"
                    "UInt" -> "u32"
                    "Long" -> "i64"
                    "ULong" -> "u64"
                    "I128" -> "i128"
                    "U128" -> "u128"
                    else -> null
                }
            if (mappedPrim != null) listOf(mappedPrim, rawSegNames[1]) else rawSegNames
        } else if (rawSegNames.size == 1 &&
            isScreamingSnakeIdent(rawSegNames[0]) &&
            scopeBuilder.resolveModuleBinding(rawSegNames[0]) == null
        ) {
            val globPrims = linkedSetOf<String>()
            val leaves = mutableListOf<UseLeaf>()
            for (u in inspector.allUseItems) {
                collectUseLeaves(u.tree, emptyList(), leaves)
            }
            for (leaf in leaves) {
                val segs = leaf.segments.map { it.removePrefix("r#") }
                if (segs.size == 4 &&
                    segs[0] in setOf("core", "std") &&
                    segs[1] in setOf("f16", "f32", "f64", "f128") &&
                    segs[2] == "consts" &&
                    segs[3] == "*"
                ) {
                    globPrims += segs[1]
                }
            }
            if (globPrims.size == 1) {
                listOf(globPrims.single(), "consts", rawSegNames[0])
            } else {
                rawSegNames
            }
        } else {
            rawSegNames
        }
    val resolvedFirst = scopeBuilder.resolveModuleBinding(mappedSelfSegs.first())?.importedPath?.split(".")
    val withImportedPrefix =
        if (resolvedFirst != null &&
            (resolvedFirst.firstOrNull() == "core" || resolvedFirst.firstOrNull() == "std")
        ) {
            resolvedFirst + mappedSelfSegs.drop(1)
        } else {
            mappedSelfSegs
        }
    val norm =
        if (withImportedPrefix.size >= 3 && withImportedPrefix[0] in setOf("core", "std")) {
            withImportedPrefix.drop(1)
        } else {
            withImportedPrefix
        }
    if (norm.size == 2) {
        val prim = norm[0]
        val c = norm[1]
        if (prim == "f32") {
            val f32 = kotlinF32TypeName()
            return when (c) {
                "MIN" -> "(-$f32.MAX_VALUE)"
                "MAX" -> "$f32.MAX_VALUE"
                "MIN_POSITIVE" -> "1.17549435E-38f"
                "EPSILON" -> "1.1920929E-7f"
                "INFINITY" -> "$f32.POSITIVE_INFINITY"
                "NEG_INFINITY" -> "$f32.NEGATIVE_INFINITY"
                "NAN" -> "$f32.NaN"
                "RADIX" -> "2u"
                "MANTISSA_DIGITS" -> "24u"
                "DIGITS" -> "6u"
                "MIN_EXP" -> "(-125)"
                "MAX_EXP" -> "128"
                "MIN_10_EXP" -> "(-37)"
                "MAX_10_EXP" -> "38"
                else -> null
            }
        }
        if (prim == "f64") {
            return when (c) {
                "MIN" -> "(-Double.MAX_VALUE)"
                "MAX" -> "Double.MAX_VALUE"
                "MIN_POSITIVE" -> "2.2250738585072014E-308"
                "EPSILON" -> "2.220446049250313E-16"
                "INFINITY" -> "Double.POSITIVE_INFINITY"
                "NEG_INFINITY" -> "Double.NEGATIVE_INFINITY"
                "NAN" -> "Double.NaN"
                "RADIX" -> "2u"
                "MANTISSA_DIGITS" -> "53u"
                "DIGITS" -> "15u"
                "MIN_EXP" -> "(-1021)"
                "MAX_EXP" -> "1024"
                "MIN_10_EXP" -> "(-307)"
                "MAX_10_EXP" -> "308"
                else -> null
            }
        }
        if (prim == "f16" || prim == "f128") {
            val owner = if (prim == "f16") "F16" else "F128"
            return when (c) {
                "MIN", "MAX", "MIN_POSITIVE", "EPSILON", "INFINITY", "NEG_INFINITY", "NAN",
                "ZERO", "ONE", "NEG_ZERO", "RADIX", "MANTISSA_DIGITS", "DIGITS",
                "MIN_EXP", "MAX_EXP", "MIN_10_EXP", "MAX_10_EXP", "BITS",
                -> "$owner.$c"
                else -> null
            }
        }
        if (prim in setOf("u8", "i8", "u16", "i16", "u32", "i32", "u64", "i64", "usize", "isize", "u128", "i128")) {
            if (c == "BITS") {
                return when (prim) {
                    "u8", "i8" -> "8u"
                    "u16", "i16" -> "16u"
                    "u32", "i32" -> "32u"
                    "u64", "i64" -> "64u"
                    "u128", "i128" -> "128u"
                    "usize", "isize" -> if (headerContext.rawAbiMode) "64u" else "32u"
                    else -> null
                }
            }
            if (c == "MIN" || c == "MAX") {
                return when (prim) {
                    "usize" ->
                        if (headerContext.rawAbiMode) {
                            "ULong.${c}_VALUE"
                        } else if (c == "MIN") {
                            "0"
                        } else {
                            "Int.MAX_VALUE"
                        }
                    "isize" -> if (headerContext.rawAbiMode) "Long.${c}_VALUE" else "Int.${c}_VALUE"
                    "u128" -> {
                        usesU128 = true
                        "U128.${c}_VALUE"
                    }
                    "i128" -> {
                        usesI128 = true
                        "I128.${c}_VALUE"
                    }
                    else -> "${rustTypeIdentToKotlin(prim)}.${c}_VALUE"
                }
            }
        }
    }
    if (norm.size == 3 && norm[1] == "consts") {
        val c = norm[2]
        if (norm[0] == "f32") {
            return when (c) {
                "PI" -> "3.1415927f"
                "TAU" -> "6.2831855f"
                "FRAC_PI_2" -> "1.5707964f"
                "FRAC_PI_3" -> "1.0471976f"
                "FRAC_PI_4" -> "0.7853982f"
                "FRAC_PI_6" -> "0.5235988f"
                "FRAC_PI_8" -> "0.3926991f"
                "FRAC_1_PI" -> "0.31830987f"
                "FRAC_2_PI" -> "0.63661975f"
                "FRAC_2_SQRT_PI" -> "1.1283792f"
                "SQRT_2" -> "1.4142135f"
                "FRAC_1_SQRT_2" -> "0.70710677f"
                "E" -> "2.7182817f"
                "LOG2_E" -> "1.442695f"
                "LOG2_10" -> "3.321928f"
                "LOG10_E" -> "0.4342945f"
                "LOG10_2" -> "0.30103f"
                "LN_2" -> "0.6931472f"
                "LN_10" -> "2.3025851f"
                else -> null
            }
        }
        if (norm[0] == "f64") {
            return when (c) {
                "PI" -> "kotlin.math.PI"
                "TAU" -> "6.283185307179586"
                "FRAC_PI_2" -> "1.5707963267948966"
                "FRAC_PI_3" -> "1.0471975511965979"
                "FRAC_PI_4" -> "0.7853981633974483"
                "FRAC_PI_6" -> "0.5235987755982989"
                "FRAC_PI_8" -> "0.39269908169872414"
                "FRAC_1_PI" -> "0.3183098861837907"
                "FRAC_2_PI" -> "0.6366197723675814"
                "FRAC_2_SQRT_PI" -> "1.1283791670955126"
                "SQRT_2" -> "1.4142135623730951"
                "FRAC_1_SQRT_2" -> "0.7071067811865476"
                "E" -> "kotlin.math.E"
                "LOG2_E" -> "1.4426950408889634"
                "LOG2_10" -> "3.321928094887362"
                "LOG10_E" -> "0.4342944819032518"
                "LOG10_2" -> "0.3010299956639812"
                "LN_2" -> "0.6931471805599453"
                "LN_10" -> "2.302585092994046"
                else -> null
            }
        }
        if (norm[0] == "f16") {
            val f32Lit =
                when (c) {
                    "PI" -> "3.1415927f"
                    "TAU" -> "6.2831855f"
                    "FRAC_PI_2" -> "1.5707964f"
                    "FRAC_PI_3" -> "1.0471976f"
                    "FRAC_PI_4" -> "0.7853982f"
                    "FRAC_PI_6" -> "0.5235988f"
                    "FRAC_PI_8" -> "0.3926991f"
                    "FRAC_1_PI" -> "0.31830987f"
                    "FRAC_2_PI" -> "0.63661975f"
                    "FRAC_2_SQRT_PI" -> "1.1283792f"
                    "SQRT_2" -> "1.4142135f"
                    "FRAC_1_SQRT_2" -> "0.70710677f"
                    "E" -> "2.7182817f"
                    "LOG2_E" -> "1.442695f"
                    "LOG2_10" -> "3.321928f"
                    "LOG10_E" -> "0.4342945f"
                    "LOG10_2" -> "0.30103f"
                    "LN_2" -> "0.6931472f"
                    "LN_10" -> "2.3025851f"
                    else -> null
                }
            if (f32Lit != null) return "F16.fromFloat($f32Lit)"
        }
        if (norm[0] == "f128") {
            val f64Lit =
                when (c) {
                    "PI" -> "kotlin.math.PI"
                    "TAU" -> "6.283185307179586"
                    "FRAC_PI_2" -> "1.5707963267948966"
                    "FRAC_PI_3" -> "1.0471975511965979"
                    "FRAC_PI_4" -> "0.7853981633974483"
                    "FRAC_PI_6" -> "0.5235987755982989"
                    "FRAC_PI_8" -> "0.39269908169872414"
                    "FRAC_1_PI" -> "0.3183098861837907"
                    "FRAC_2_PI" -> "0.6366197723675814"
                    "FRAC_2_SQRT_PI" -> "1.1283791670955126"
                    "SQRT_2" -> "1.4142135623730951"
                    "FRAC_1_SQRT_2" -> "0.7071067811865476"
                    "E" -> "kotlin.math.E"
                    "LOG2_E" -> "1.4426950408889634"
                    "LOG2_10" -> "3.321928094887362"
                    "LOG10_E" -> "0.4342944819032518"
                    "LOG10_2" -> "0.3010299956639812"
                    "LN_2" -> "0.6931471805599453"
                    "LN_10" -> "2.302585092994046"
                    else -> null
                }
            if (f64Lit != null) return "F128.fromDouble($f64Lit)"
        }
    }
    return null
}

internal fun KotlinJ2KEmitter.isScreamingSnakeIdent(raw: String): Boolean {
    val clean = raw.removePrefix("r#")
    return clean.length >= 2 &&
        clean.any { it.isUpperCase() } &&
        clean.all { it.isUpperCase() || it.isDigit() || it == '_' }
}

internal fun KotlinJ2KEmitter.resolveImportedUppercaseTypeBinding(rawIdent: String): String? {
    val clean = rawIdent.removePrefix("r#")
    val binding = scopeBuilder.resolveModuleBinding(clean) ?: return null
    return if (!binding.isModuleImport && binding.kotlinName.firstOrNull()?.isUpperCase() == true) {
        binding.kotlinName
    } else {
        null
    }
}

internal fun KotlinJ2KEmitter.renderPathAsExpression(path: Path, selfTypeName: String?, asCallee: Boolean = true): String {
    val rawSegments = path.segments.toList()
    if (rawSegments.isEmpty()) return "Unit"

    val rawSegNames = rawSegments.map { it.ident.toString().removePrefix("r#") }
    if (inTraitDefaultMethodBody &&
        rawSegments.size == 2 &&
        rawSegNames[0] == "Self" &&
        rawSegNames[1] in currentTraitMethodRawNames
    ) {
        val ktMethod = rustFunctionIdentToKotlin(rawSegNames[1])
        val methodTypeArgs =
            (rawSegments[1].arguments as? PathArguments.AngleBracketed)
                ?.args
                ?.toList()
                ?.mapNotNull { renderGenericArgument(it, selfTypeName) }
                ?.takeIf { it.isNotEmpty() }
                ?.joinToString(", ", prefix = "<", postfix = ">")
                .orEmpty()
        return if (asCallee) "this.$ktMethod$methodTypeArgs" else "this::$ktMethod"
    }
    renderPrimitiveAssociatedConst(
        rawSegNames,
        hasTrailingArgs = rawSegments.last().arguments !is PathArguments.None,
        selfTypeName = selfTypeName,
    )?.let { return it }
    val stdlibFfiDropCount =
        when {
            !headerContext.rawAbiMode &&
                rawSegNames.size >= 3 &&
                rawSegNames[0] in setOf("core", "std", "alloc") &&
                rawSegNames[1] == "ffi" &&
                (rawSegNames[2] == "CString" || rawSegNames[2] in CoreIntrinsicLowering.STDLIB_CORE_FFI_SYMBOLS) -> 2
            !headerContext.rawAbiMode &&
                rawSegNames.size >= 4 &&
                rawSegNames[0] == "std" &&
                rawSegNames[1] == "os" &&
                rawSegNames[2] == "raw" &&
                (rawSegNames[3] == "CString" || rawSegNames[3] in CoreIntrinsicLowering.STDLIB_CORE_FFI_SYMBOLS) -> 3
            !headerContext.rawAbiMode &&
                rawSegNames.size >= 2 &&
                rawSegNames[0] in setOf("ffi", "raw") &&
                (rawSegNames[1] == "CString" || rawSegNames[1] in CoreIntrinsicLowering.STDLIB_CORE_FFI_SYMBOLS) &&
                run {
                    val imp = scopeBuilder.resolveModuleBinding(rawSegNames[0])?.importedPath
                    imp == null || imp.startsWith("core.") || imp.startsWith("std.") || imp.startsWith("alloc.")
                } -> 1
            else -> 0
        }
    val effectiveRawSegments = if (stdlibFfiDropCount > 0) rawSegments.drop(stdlibFfiDropCount) else rawSegments

    val filtered =
        effectiveRawSegments.filterIndexed { idx, seg ->
            val id = seg.ident.toString()
            val cleanId = id.removePrefix("r#")
            val idKtType = rustTypeIdentToKotlin(cleanId)
            idx == effectiveRawSegments.lastIndex ||
                id.first().isUpperCase() ||
                id in PRIMITIVE_TYPE_NAMES ||
                (scopeBuilder.isImportedModuleInScope(id) && !isSuppressedStdlibModuleAlias(id)) ||
                resolveImportedUppercaseTypeBinding(id) != null ||
                idKtType in inspector.declaredTypeNames ||
                scopedAbiKeysForRenderedType(idKtType).any { it in headerContext.crateSymbolSubpackages }
        }
    if (filtered.isEmpty()) {
        val only = rawSegments.last().ident.toString()
        val cleanOnly = only.removePrefix("r#")
        return if (only == "self") {
            "this"
        } else if (only == "Self") {
            selfTypeName?.substringBefore('<')?.trim() ?: "Self"
        } else {
            activeGuardBindingSubstitutions[cleanOnly]
                ?: scopeBuilder.resolveLocalKotlinName(cleanOnly)
                ?: rustValueIdentToKotlin(only)
        }
    }
    if (filtered.size == 1 && filtered[0].ident.toString() == "None" && !hasDeclaredOptionEnum() && !inspector.hasLocalNoneVariant) {
        return "null"
    }
    if (filtered.size == 2 &&
        filtered[0].ident.toString() == "Option" &&
        filtered[1].ident.toString() == "None" &&
        !hasDeclaredOptionEnum() &&
        !inspector.hasLocalNoneVariant
    ) {
        return "null"
    }
    if (filtered.size == 1 && filtered[0].ident.toString() == "PhantomData" && "PhantomData" !in inspector.declaredTypeNames) {
        return "Unit"
    }
    if (filtered.size == 2 && filtered[0].arguments is PathArguments.None) {
        val firstRaw = filtered[0].ident.toString()
        val secondRaw = filtered[1].ident.toString()
        val cleanSecond = secondRaw.removePrefix("r#")
        val firstKtType = rustTypeIdentToKotlin(firstRaw)
        if (filtered[1].arguments is PathArguments.None &&
            (firstRaw in PRIMITIVE_TYPE_NAMES || resolveNumericKind(firstKtType) != null) &&
            (secondRaw == "MAX" || secondRaw == "MIN")
        ) {
            if (firstRaw == "u128") usesU128 = true
            if (firstRaw == "i128") usesI128 = true
            val kind = resolveNumericKind(firstKtType)
            if (secondRaw == "MIN" && (kind == NumericKind.F32 || kind == NumericKind.F64)) {
                return "(-$firstKtType.MAX_VALUE)"
            }
            return "$firstKtType.${secondRaw}_VALUE"
        }
        if (filtered[1].arguments is PathArguments.None && (firstRaw == "u128" || firstRaw == "i128" || firstRaw == "f16" || firstRaw == "f128")) {
            val ownerPrim =
                when (firstRaw) {
                    "u128" -> {
                        usesU128 = true
                        "U128"
                    }
                    "i128" -> {
                        usesI128 = true
                        "I128"
                    }
                    "f16" -> "F16"
                    else -> "F128"
                }
            val bitsCount = if (firstRaw == "f16") "16u" else "128u"
            when (cleanSecond) {
                "from_be_bytes" -> return if (asCallee) "$ownerPrim.fromBeBytes" else "$ownerPrim::fromBeBytes"
                "from_le_bytes" -> return if (asCallee) "$ownerPrim.fromLeBytes" else "$ownerPrim::fromLeBytes"
                "from_ne_bytes" -> {
                    val fn = if (activeCfgProfile.targetEndian == "big") "fromBeBytes" else "fromLeBytes"
                    return if (asCallee) "$ownerPrim.$fn" else "$ownerPrim::$fn"
                }
                "ZERO" -> return "$ownerPrim.ZERO"
                "BITS" -> return bitsCount
            }
        }
        currentTypeParamWitnessCalls[rustTypeIdentToKotlin(firstRaw) to cleanSecond]?.let { return it }
        if (firstRaw == "Default" && cleanSecond == "default") {
            currentTypeParamWitnessCalls["*" to "default"]?.let { return it }
        }
    }
    if (rawSegments.size >= 2) {
        val crossTarget = resolveCrossModulePathTarget(rawSegments.map { it.ident.toString() })
        if (crossTarget != null) {
            val (resolvedSym, trailing) = crossTarget
            val moduleSegsValid = rawSegments.dropLast(1 + trailing.size).all { it.arguments is PathArguments.None }
            if (moduleSegsValid) {
                val boundHead = bindOrQualifyResolvedCrossSymbol(resolvedSym)
                val headSeg = rawSegments[rawSegments.lastIndex - trailing.size]
                val headIdent = headSeg.ident.toString()
                val headTypeArgs =
                    if (trailing.isEmpty() && headSeg.arguments is PathArguments.AngleBracketed && !headIdent.first().isUpperCase()) {
                        val renderedArgs =
                            (headSeg.arguments as PathArguments.AngleBracketed)
                                .args
                                .toList()
                                .mapNotNull { renderGenericArgument(it, selfTypeName) }
                        if (renderedArgs.isNotEmpty()) "<${renderedArgs.joinToString(", ")}>" else ""
                    } else {
                        ""
                    }
                if (trailing.isEmpty()) {
                    val lastRaw = headSeg.ident.toString()
                    val cleanLast = lastRaw.removePrefix("r#")
                    if (!asCallee && headSeg.arguments is PathArguments.None) {
                        if (lastRaw.first().isUpperCase() && !isScreamingSnakeIdent(lastRaw) && boundHead in inspector.unitStructNames) {
                            return "$boundHead()"
                        }
                        if (lastRaw.first().isLowerCase() &&
                            '.' !in boundHead &&
                            cleanLast in inspector.declaredTopLevelFnRawNames &&
                            cleanLast !in inspector.declaredConstNames
                        ) {
                            return "::$boundHead"
                        }
                    }
                    return "$boundHead$headTypeArgs"
                }
                val tailSegs = rawSegments.takeLast(trailing.size)
                val renderedTail =
                    tailSegs.mapIndexed { idx, seg ->
                        renderPathSegmentInExpr(
                            rawIdent = seg.ident.toString(),
                            arguments = seg.arguments,
                            selfTypeName = selfTypeName,
                            isLast = (idx == tailSegs.lastIndex),
                            isSingleSegment = false,
                        )
                    }
                val fullParts = listOf(boundHead) + renderedTail
                if (!asCallee && fullParts.size >= 2) {
                    val lastRaw = tailSegs.last().ident.toString()
                    val prevRaw = if (tailSegs.size >= 2) tailSegs[tailSegs.size - 2].ident.toString() else headSeg.ident.toString()
                    if (prevRaw.first().isUpperCase() && lastRaw.first().isUpperCase() && !isScreamingSnakeIdent(lastRaw)) {
                        val enumKt = rustTypeIdentToKotlin(prevRaw)
                        val varKt = rustTypeIdentToKotlin(lastRaw)
                        val arity = inspector.tupleEnumVariantArity[enumKt to varKt]
                        if (arity != null && arity > 0) {
                            val ctorRef = fullParts.joinToString(".")
                            return if (arity == 1) {
                                "{ $ctorRef(it) }"
                            } else {
                                val pNames = (0 until arity).map { "p$it" }
                                "{ ${pNames.joinToString(", ")} -> $ctorRef(${pNames.joinToString(", ")}) }"
                            }
                        }
                    }
                    val isStaticFnRef =
                        prevRaw.first().isUpperCase() &&
                            lastRaw.first().isLowerCase() &&
                            !lastRaw.all { it.isUpperCase() || it.isDigit() || it == '_' }
                    if (isStaticFnRef) {
                        return renderValuePositionStaticFnRef(
                            prevRaw = prevRaw,
                            ownerPath = fullParts.dropLast(1).joinToString("."),
                            methodKt = fullParts.last(),
                            selfTypeName = selfTypeName,
                        )
                    }
                }
                return fullParts.joinToString(".")
            }
        }
    }
    if (!asCallee && filtered.size == 1 && filtered[0].arguments is PathArguments.None) {
        val singleRaw = filtered[0].ident.toString()
        val cleanSingle = singleRaw.removePrefix("r#")
        if (singleRaw == "Ok" && "Ok" !in inspector.declaredTypeNames && !hasDeclaredResultEnum()) {
            return if (usesTaggedCoreResult()) "{ ${sourceResultName()}.Ok(it) }" else "{ Result.success(it) }"
        }
        if (singleRaw == "Err" && "Err" !in inspector.declaredTypeNames && !hasDeclaredResultEnum()) {
            return if (usesTaggedCoreResult()) "{ ${sourceResultName()}.Err(it) }" else "{ Result.failure(it) }"
        }
        if (singleRaw == "Some" && "Some" !in inspector.declaredTypeNames && !hasDeclaredOptionEnum()) {
            return "{ it }"
        }
        if (singleRaw.first().isUpperCase() && !isScreamingSnakeIdent(singleRaw)) {
            val singleTypeIdent = rustTypeIdentToKotlin(singleRaw)
            if (singleTypeIdent in inspector.unitStructNames) {
                return "$singleTypeIdent()"
            }
        }
        if (cleanSingle != "self" &&
            singleRaw.first().isLowerCase() &&
            cleanSingle !in activeGuardBindingSubstitutions &&
            scopeBuilder.resolveLocalKotlinName(cleanSingle) == null &&
            cleanSingle in inspector.declaredTopLevelFnRawNames &&
            cleanSingle !in inspector.declaredConstNames
        ) {
            return "::${rustFunctionIdentToKotlin(singleRaw)}"
        }
    }

    val isSingleSegment = (rawSegments.size == 1)
    val parts =
        filtered.mapIndexed { idx, seg ->
            renderPathSegmentInExpr(
                rawIdent = seg.ident.toString(),
                arguments = seg.arguments,
                selfTypeName = selfTypeName,
                isLast = (idx == filtered.lastIndex),
                isSingleSegment = isSingleSegment,
                asCallee = asCallee,
            )
        }
    if (!asCallee && parts.size >= 2) {
        val lastRaw = filtered.last().ident.toString()
        val prevRaw = filtered[filtered.size - 2].ident.toString()
        if (prevRaw.first().isUpperCase() && lastRaw.first().isUpperCase() && !isScreamingSnakeIdent(lastRaw)) {
            val enumKt = if (prevRaw == "Self") selfTypeName?.substringBefore('<')?.trim().orEmpty() else rustTypeIdentToKotlin(prevRaw)
            val varKt = rustTypeIdentToKotlin(lastRaw)
            val arity = inspector.tupleEnumVariantArity[enumKt to varKt]
            if (arity != null && arity > 0) {
                val ctorRef = parts.joinToString(".")
                return if (arity == 1) {
                    "{ $ctorRef(it) }"
                } else {
                    val pNames = (0 until arity).map { "p$it" }
                    "{ ${pNames.joinToString(", ")} -> $ctorRef(${pNames.joinToString(", ")}) }"
                }
            }
        }
        val isStaticFnRef =
            (prevRaw.first().isUpperCase() || prevRaw in PRIMITIVE_TYPE_NAMES || resolveImportedUppercaseTypeBinding(prevRaw) != null) &&
                lastRaw.first().isLowerCase() &&
                !lastRaw.all { it.isUpperCase() || it.isDigit() || it == '_' }
        if (isStaticFnRef) {
            return renderValuePositionStaticFnRef(
                prevRaw = prevRaw,
                ownerPath = parts.dropLast(1).joinToString("."),
                methodKt = parts.last(),
                selfTypeName = selfTypeName,
            )
        }
    }
    return parts.joinToString(".")
}

internal fun KotlinJ2KEmitter.renderPathSegmentInExpr(
    rawIdent: String,
    arguments: PathArguments,
    selfTypeName: String?,
    isLast: Boolean,
    isSingleSegment: Boolean = false,
    asCallee: Boolean = false,
): String {
    val cleanIdent = rawIdent.removePrefix("r#")
    val importedTypeKotlinName = resolveImportedUppercaseTypeBinding(rawIdent)
    val base =
        when {
            rawIdent == "self" -> "this"
            rawIdent == "Self" -> selfTypeName?.substringBefore('<')?.trim() ?: "Self"
            isSingleSegment &&
                isLast &&
                arguments is PathArguments.None &&
                !asCallee &&
                scopeBuilder.resolveLocalKotlinName(cleanIdent) != null ->
                scopeBuilder.resolveLocalKotlinName(cleanIdent)!!
            isLast && isScreamingSnakeIdent(rawIdent) ->
                if (cleanIdent in inspector.declaredTopLevelFnRawNames ||
                    (asCallee && cleanIdent !in inspector.declaredConstNames && cleanIdent !in inspector.declaredTypeNames)
                ) {
                    rustFunctionIdentToKotlin(rawIdent)
                } else {
                    inspector.declaredConstNames[cleanIdent] ?: rustConstIdentToKotlin(rawIdent)
                }
            isLast &&
                arguments is PathArguments.None &&
                scopeBuilder.resolveLocalKotlinName(cleanIdent) == null &&
                cleanIdent !in activeGuardBindingSubstitutions &&
                cleanIdent in inspector.declaredConstNames ->
                inspector.declaredConstNames.getValue(cleanIdent)
            !isLast && rawIdent in PRIMITIVE_TYPE_NAMES ->
                if (rawIdent == "f32") kotlinF32TypeName() else rustTypeIdentToKotlin(rawIdent)
            rawIdent.first().isUpperCase() -> rustTypeIdentToKotlin(rawIdent)
            !isLast &&
                rustTypeIdentToKotlin(rawIdent) in inspector.declaredTypeNames &&
                rustTypeIdentToKotlin(rawIdent) !in inspector.typeAliasTargets ->
                rustTypeIdentToKotlin(rawIdent)
            isLast &&
                asCallee &&
                cleanIdent !in inspector.declaredTopLevelFnRawNames &&
                scopeBuilder.resolveLocalKotlinName(cleanIdent) == null &&
                rustTypeIdentToKotlin(rawIdent) in inspector.declaredTypeNames &&
                rustTypeIdentToKotlin(rawIdent) !in inspector.typeAliasTargets ->
                rustTypeIdentToKotlin(rawIdent)
            !isLast && importedTypeKotlinName != null -> importedTypeKotlinName
            isLast &&
                asCallee &&
                cleanIdent !in inspector.declaredTopLevelFnRawNames &&
                scopeBuilder.resolveLocalKotlinName(cleanIdent) == null &&
                importedTypeKotlinName != null ->
                importedTypeKotlinName
            !isLast && scopeBuilder.isImportedModuleInScope(rawIdent) ->
                scopeBuilder.resolveModuleBinding(rawIdent)?.kotlinName ?: rustModuleIdentToKotlin(rawIdent)
            isSingleSegment -> {
                if (arguments is PathArguments.None && cleanIdent in activeGuardBindingSubstitutions) {
                    activeGuardBindingSubstitutions.getValue(cleanIdent)
                } else {
                    scopeBuilder.resolveLocalKotlinName(cleanIdent) ?: rustValueIdentToKotlin(rawIdent)
                }
            }
            else -> rustValueIdentToKotlin(rawIdent)
        }
    val resolvedBase =
        if (isSingleSegment &&
            isLast &&
            (arguments is PathArguments.None || (arguments is PathArguments.AngleBracketed && !rawIdent.first().isUpperCase())) &&
            base != "this" &&
            base != "Self" &&
            base != "true" &&
            base != "false" &&
            base !in KOTLIN_STDLIB_SHADOWED_IMPORTS &&
            scopeBuilder.resolveLocalKotlinName(cleanIdent) == null &&
            cleanIdent !in activeGuardBindingSubstitutions &&
            cleanIdent !in inspector.declaredConstNames &&
            cleanIdent !in inspector.declaredTopLevelFnRawNames &&
            base !in inspector.declaredTypeNames &&
            headerContext.crateSymbolSubpackages.isNotEmpty()
        ) {
            val constCandidate =
                if (arguments is PathArguments.None && cleanIdent.contains('_') && cleanIdent.any { it.isUpperCase() }) {
                    rustConstIdentToKotlin(rawIdent)
                } else {
                    null
                }
            val primarySubpkg = resolveBestSubpackageForSymbol(base)
            val altConstSubpkg = if (primarySubpkg == null && constCandidate != null) resolveBestSubpackageForSymbol(constCandidate) else null
            val effectiveSym = if (primarySubpkg != null) base else (constCandidate ?: base)
            val subpkg = primarySubpkg ?: altConstSubpkg
            val nsPrefix = currentNamespacePrefix()
            if (subpkg != null && nsPrefix != null) {
                val targetPkg = if (subpkg.isEmpty()) nsPrefix else "$nsPrefix.$subpkg"
                if (targetPkg.isNotEmpty() && targetPkg != headerContext.packageName) {
                    val collidedPkg = importedSimpleNameToPkg[effectiveSym]
                    if (collidedPkg != null && collidedPkg != targetPkg) {
                        "$targetPkg.$effectiveSym"
                    } else {
                        importedSimpleNameToPkg[effectiveSym] = targetPkg
                        extraTypePathImports += "import $targetPkg.$effectiveSym"
                        effectiveSym
                    }
                } else {
                    effectiveSym
                }
            } else {
                base
            }
        } else {
            base
        }
    val typeArgs =
        when (arguments) {
            is PathArguments.None -> ""
            is PathArguments.AngleBracketed -> {
                val rendered = arguments.args.toList().mapNotNull { renderGenericArgument(it, selfTypeName) }
                if (rendered.isNotEmpty() && isLast && !rawIdent.first().isUpperCase()) {
                    "<${rendered.joinToString(", ")}>"
                } else {
                    ""
                }
            }
            is PathArguments.Parenthesized -> ""
        }
    return "$resolvedBase$typeArgs"
}

internal fun KotlinJ2KEmitter.renderMemberName(member: Member): String =
    when (member) {
        is Member.Named -> rustFieldIdentToKotlin(member.ident.toString())
        is Member.Unnamed -> "field${member.index.index}"
    }

internal fun KotlinJ2KEmitter.renderType(ty: SynType, selfTypeName: String?): String =
    when (ty) {
        is SynType.Reference ->
            if (ty.mutability != null && unwrapSynType(ty.elem) is SynType.Slice) {
                "MutableList<${renderType((unwrapSynType(ty.elem) as SynType.Slice).elem, selfTypeName)}>"
            } else if (ty.mutability != null && shouldUseAddressBackedMutableReference(ty.elem, selfTypeName)) {
                val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
                CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.mem.RustMutableReference")
                "RustMutableReference<${renderType(ty.elem, selfTypeName)}>"
            } else {
                renderType(ty.elem, selfTypeName)
            }
        is SynType.Ptr -> renderPointerType(ty, selfTypeName)
        is SynType.Slice -> "List<${renderType(ty.elem, selfTypeName)}>"
        is SynType.Array -> {
            val elemRendered = renderType(ty.elem, selfTypeName)
            if (elemRendered == "Boolean") {
                "BooleanArray"
            } else {
                when (resolveNumericKind(elemRendered)) {
                    NumericKind.I8 -> "ByteArray"
                    NumericKind.U8 -> "UByteArray"
                    NumericKind.I16 -> "ShortArray"
                    NumericKind.U16 -> "UShortArray"
                    NumericKind.I32 -> "IntArray"
                    NumericKind.U32 -> "UIntArray"
                    NumericKind.I64 -> "LongArray"
                    NumericKind.U64 -> "ULongArray"
                    NumericKind.F32 -> "FloatArray"
                    NumericKind.F64 -> "DoubleArray"
                    NumericKind.I128, NumericKind.U128, NumericKind.F16, NumericKind.F128, null -> {
                        val nonNullElem =
                            when {
                                elemRendered.endsWith("?") ->
                                    elemRendered.removeSuffix("?")
                                isOptionFnTypeAliasRenderedType(elemRendered) ->
                                    "${elemRendered}Fn"
                                else -> elemRendered
                            }
                        "List<$nonNullElem>"
                    }
                }
            }
        }
        is SynType.Tuple -> {
            val elems = ty.elems.toList()
            val pairType = if ("Pair" in inspector.declaredTypeNames) "kotlin.Pair" else "Pair"
            val tripleType = if ("Triple" in inspector.declaredTypeNames) "kotlin.Triple" else "Triple"
            when (elems.size) {
                0 -> "Unit"
                1 -> renderType(elems[0], selfTypeName)
                2 -> "$pairType<${renderType(elems[0], selfTypeName)}, ${renderType(elems[1], selfTypeName)}>"
                3 -> "$tripleType<${renderType(elems[0], selfTypeName)}, ${renderType(elems[1], selfTypeName)}, ${renderType(elems[2], selfTypeName)}>"
                else -> "List<Any?>"
            }
        }
        is SynType.Path -> {
            val qself = ty.qself
            if (qself != null) {
                val lastSeg =
                    ty.path.segments
                        .last()
                        ?.ident
                        ?.toString()
                if (lastSeg != null && lastSeg != "Self") {
                    rustTypeIdentToKotlin(lastSeg)
                } else {
                    renderType(qself.ty, selfTypeName)
                }
            } else {
                renderPathAsType(ty.path, selfTypeName)
            }
        }
        is SynType.FnPtr -> {
            val prevParam = inExternParamType
            inExternParamType = false
            try {
                synthesizeOrReuseBareFnSam(ty, selfTypeName)
            } finally {
                inExternParamType = prevParam
            }
        }
        is SynType.ImplTrait -> {
            val bounds = ty.bounds.toList().mapNotNull { renderTypeParamBound(it, selfTypeName) }
            bounds.firstOrNull() ?: "Any"
        }
        is SynType.TraitObject -> {
            if (isStdlibDynAnyTraitObject(ty)) {
                usesRustAny = true
                "RustAny"
            } else {
                val bounds = ty.bounds.toList().mapNotNull { renderTypeParamBound(it, selfTypeName) }
                bounds.firstOrNull() ?: "Any"
            }
        }
        is SynType.Paren -> renderType(ty.elem, selfTypeName)
        is SynType.Group -> renderType(ty.elem, selfTypeName)
        is SynType.Never -> if ("Nothing" in inspector.declaredTypeNames) "kotlin.Nothing" else "Nothing"
        is SynType.Infer -> "Any?"
        is SynType.Macro -> {
            val macroName =
                ty.mac.path.segments
                    .last()
                    ?.ident
                    ?.toString()
            if (macroName == "Token") {
                resolveSynTokenMacroTypeName(ty.mac.tokens) ?: "Token"
            } else {
                rustTypeIdentToKotlin(macroName ?: "Token")
            }
        }
        is SynType.Verbatim -> "Any"
    }

internal fun KotlinJ2KEmitter.renderPathAsType(path: Path, selfTypeName: String?): String {
    val rawSegments = path.segments.toList()
    if (rawSegments.isEmpty()) return "Any"
    if (rawSegments.size >= 2 && rawSegments[0].ident.toString() == "Self") {
        val assocIdent = rawSegments.last().ident.toString()
        val assocTy = currentImplAssocTypes[assocIdent]
        if (assocTy != null) {
            val prev = currentImplAssocTypes
            currentImplAssocTypes = prev - assocIdent
            return try {
                renderType(assocTy, selfTypeName)
            } finally {
                currentImplAssocTypes = prev
            }
        }
        return rustTypeIdentToKotlin(assocIdent)
    }
    if (rawSegments.size == 1 && rawSegments[0].arguments is PathArguments.None) {
        val singleKt = rustTypeIdentToKotlin(rawSegments[0].ident.toString())
        substitutedTypeParamTypes[singleKt]?.let { return it }
    }
    if (rawSegments.size == 2 &&
        rawSegments[0].arguments is PathArguments.None &&
        rustTypeIdentToKotlin(rawSegments[0].ident.toString()) in activeTypeParamNames
    ) {
        return rustTypeIdentToKotlin(rawSegments[1].ident.toString())
    }
    val lastSeg = rawSegments.last()
    val rawName = lastSeg.ident.toString()
    if (rawName == "PhantomData" && "PhantomData" !in inspector.declaredTypeNames) {
        return "Unit"
    }
    val isStdlibFmtModule =
        (
            rawSegments.size >= 3 &&
                rawSegments[0].ident.toString() in STDLIB_CRATE_ROOTS &&
                rawSegments[1].ident.toString() == "fmt"
        ) ||
            (
                rawSegments.size == 2 &&
                    rawSegments[0].ident.toString() == "fmt" &&
                    (
                        scopeBuilder.resolveModuleBinding("fmt") != null ||
                            headerContext.rawAbiMode ||
                            headerContext.finalClasses ||
                            currentAttachedImplTraitPath
                                ?.segments
                                ?.last()
                                ?.ident
                                ?.toString() in setOf("Debug", "Display", "Octal", "LowerHex", "UpperHex", "Binary")
                    )
            )
    if (isStdlibFmtModule) {
        if (rawName == "Formatter") return "StringBuilder"
        if (rawName == "Write") return "Appendable"
        if (rawName == "Result" || rawName == "Error") return "Unit"
        if (rawName == "DebugStruct" && "DebugStruct" !in inspector.declaredTypeNames) {
            usesDebugStruct = true
            return "DebugStruct"
        }
        if (rawName == "DebugTuple" && "DebugTuple" !in inspector.declaredTypeNames) {
            usesDebugTuple = true
            return "DebugTuple"
        }
    }
    if (rawSegments.size == 1 && lastSeg.arguments is PathArguments.None) {
        if (rawName == "DebugStruct" && "DebugStruct" !in inspector.declaredTypeNames) {
            usesDebugStruct = true
            return "DebugStruct"
        }
        if (rawName == "DebugTuple" && "DebugTuple" !in inspector.declaredTypeNames) {
            usesDebugTuple = true
            return "DebugTuple"
        }
    }
    if (rawName == "ThreadId" && "ThreadId" !in inspector.declaredTypeNames) {
        return "Any"
    }
    if (!headerContext.rawAbiMode && lastSeg.arguments is PathArguments.None) {
        val rawSegNames =
            rewriteCrateRootAliasAndReexportSegments(
                rawSegments.map { it.ident.toString().removePrefix("r#") },
            )
        val symLeaf = rawSegNames.last()
        val isSelfContainedLibcFfi =
            rawSegNames.size == 2 &&
                rawSegNames[0] == "libc" &&
                FfiInteropCollector.resolveLibcNamespace(headerContext) == null
        val isStdlibFfiModule =
            (rawSegNames.size == 3 && rawSegNames[0] in setOf("core", "std") && rawSegNames[1] == "ffi") ||
                (rawSegNames.size == 4 && rawSegNames[0] == "std" && rawSegNames[1] == "os" && rawSegNames[2] == "raw") ||
                isSelfContainedLibcFfi ||
                (
                    rawSegNames.size == 2 && rawSegNames[0] in setOf("ffi", "raw") &&
                        run {
                            val imp = scopeBuilder.resolveModuleBinding(rawSegNames[0])?.importedPath
                            imp == null || imp.startsWith("core.") || imp.startsWith("std.") || imp.startsWith("alloc.")
                        }
                )
        val isAllocFfiCString =
            rawSegNames.size == 3 &&
                rawSegNames[0] == "alloc" &&
                rawSegNames[1] == "ffi" &&
                symLeaf == "CString"
        if (isSelfContainedLibcFfi && symLeaf !in CoreIntrinsicLowering.STDLIB_CORE_FFI_SYMBOLS) {
            val ptr64 = activeCfgProfile.pointerWidth != 32
            when (symLeaf) {
                "size_t", "usize", "uintptr_t" -> return if (ptr64) "ULong" else "UInt"
                "ssize_t", "isize", "intptr_t", "ptrdiff_t" -> return if (ptr64) "Long" else "Int"
                "int8_t" -> return "Byte"
                "uint8_t" -> return "UByte"
                "int16_t" -> return "Short"
                "uint16_t" -> return "UShort"
                "int32_t", "pid_t", "clockid_t", "wchar_t" -> return "Int"
                "uint32_t", "uid_t", "gid_t", "socklen_t", "useconds_t", "mode_t" -> return "UInt"
                "int64_t", "off_t", "time_t", "suseconds_t" -> return "Long"
                "uint64_t", "dev_t", "ino_t", "rlim_t" -> return "ULong"
            }
        }
        val resolvedFfiImport =
            when {
                isAllocFfiCString || (isStdlibFfiModule && symLeaf == "CString") ->
                    resolveStdlibFfiSymbolImport("CString", isAllocCString = true)
                isStdlibFfiModule && symLeaf in CoreIntrinsicLowering.STDLIB_CORE_FFI_SYMBOLS ->
                    resolveStdlibFfiSymbolImport(symLeaf)
                else -> null
            }
        if (resolvedFfiImport != null) {
            return bindOrQualifyResolvedCrossSymbol(resolvedFfiImport)
        }
    }

    if (!headerContext.rawAbiMode &&
        rawName == "Result" &&
        "Result" !in inspector.declaredTypeNames &&
        !hasDeclaredResultEnum() &&
        lastSeg.arguments is PathArguments.AngleBracketed
    ) {
        val typeArgs =
            (lastSeg.arguments as PathArguments.AngleBracketed)
                .args
                .toList()
                .mapNotNull { renderGenericArgument(it, selfTypeName) }
        if (typeArgs.size == 1) {
            val rawSegNames = rawSegments.map { it.ident.toString().removePrefix("r#") }
            val modHead = rawSegNames.getOrNull(rawSegNames.size - 2)
            val resolvedModImp =
                if (rawSegNames.size == 2 && modHead != null) {
                    scopeBuilder.resolveModuleBinding(modHead)?.importedPath
                } else {
                    null
                }
            val matchesStdMod =
                { expected: String ->
                    modHead == expected &&
                        (
                            (rawSegNames.size == 3 && rawSegNames[0] in setOf("std", "crate")) ||
                                (
                                    rawSegNames.size == 2 &&
                                        (
                                            resolvedModImp == null ||
                                                resolvedModImp.startsWith("std.") ||
                                                resolvedModImp.startsWith("crate.") ||
                                                headerContext.packageName?.startsWith("io.github.kotlinmania.rustlang.std") == true
                                        )
                                )
                        )
                }
            val isTaggedOrRustLangStd =
                usesTaggedCoreResult() ||
                    headerContext.packageName?.startsWith("io.github.kotlinmania.rustlang.") == true
            if (matchesStdMod("io") && isTaggedOrRustLangStd) {
                val stdNs =
                    headerContext.crateNamespaces["std"]
                        ?.trim()
                        ?.removeSuffix(".")
                        ?.takeIf { it.isNotEmpty() }
                        ?: "io.github.kotlinmania.rustlang.std".takeIf {
                            headerContext.packageName?.startsWith(it) == true
                        }
                val ioErr =
                    if (stdNs != null) {
                        bindOrQualifyResolvedCrossSymbol("$stdNs.io.error.Error")
                    } else {
                        "Error"
                    }
                val resName = if (usesTaggedCoreResult()) sourceResultName() else "Result"
                return "$resName<${typeArgs[0]}, $ioErr>"
            }
            if (matchesStdMod("thread") && isTaggedOrRustLangStd) {
                usesRustAny = true
                val resName = if (usesTaggedCoreResult()) sourceResultName() else "Result"
                return "$resName<${typeArgs[0]}, RustAny>"
            }
        }
    }

    var externalQualifiedOverride: String? = null
    val crossTarget =
        if (rawSegments.size >= 2 && rawSegments.dropLast(1).all { it.arguments is PathArguments.None }) {
            resolveCrossModulePathTarget(rawSegments.map { it.ident.toString() }, inTypePosition = true)
        } else {
            null
        }
    val prefixQualifier =
        if (crossTarget != null) {
            val (resolvedSym, trailing) = crossTarget
            val boundHead = bindOrQualifyResolvedCrossSymbol(resolvedSym)
            if (trailing.isEmpty()) {
                externalQualifiedOverride = boundHead
                ""
            } else {
                val midPrefix = trailing.dropLast(1).map(::rustTypeIdentToKotlin)
                (listOf(boundHead) + midPrefix).joinToString(".") + "."
            }
        } else {
            val prefixSegments =
                rawSegments
                    .dropLast(1)
                    .map { it.ident.toString() }
                    .filter {
                        it !in IGNORED_PATH_PREFIXES &&
                            it !in STDLIB_PRELUDE_REEXPORT_MODULES &&
                            it !in inspector.localInlineModuleNames &&
                            (it.first().isUpperCase() || (scopeBuilder.isImportedModuleInScope(it) && !isSuppressedStdlibModuleAlias(it)))
                    }.map {
                        if (it.first().isUpperCase()) {
                            rustTypeIdentToKotlin(it)
                        } else {
                            scopeBuilder.resolveModuleBinding(it)?.kotlinName ?: rustModuleIdentToKotlin(it)
                        }
                    }
            if (prefixSegments.isNotEmpty()) prefixSegments.joinToString(".") + "." else ""
        }

    val isStdlibUnqualified =
        (prefixQualifier.isEmpty() && externalQualifiedOverride == null) ||
            rawSegments.dropLast(1).all { it.ident.toString() in STDLIB_TYPE_PATH_PREFIXES }
    if (isStdlibUnqualified && lastSeg.arguments is PathArguments.AngleBracketed) {
        val typeArgs =
            (lastSeg.arguments as PathArguments.AngleBracketed)
                .args
                .toList()
                .mapNotNull { renderGenericArgument(it, selfTypeName) }
        if (rawName == "Option" && !hasDeclaredOptionEnum() && typeArgs.size == 1) {
            val inner = typeArgs[0]
            return if (isAlreadyNullableRenderedType(inner)) inner else "$inner?"
        }
        if (rawName == "Padding" && "Padding" !in inspector.declaredTypeNames && typeArgs.size == 1) {
            return typeArgs[0]
        }
        if (rawName == "MaybeUninit" && "MaybeUninit" !in inspector.declaredTypeNames && typeArgs.size == 1) {
            val inner = typeArgs[0]
            val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
            CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.mem.maybeuninit.MaybeUninit")
            return "MaybeUninit<$inner>"
        }
        val pathHasSlice = rawSegments.any { it.ident.toString() == "slice" }
        val pathHasVecOrOption = rawSegments.any { it.ident.toString() == "vec" || it.ident.toString() == "option" }
        if ((rawName == "Iter" || rawName == "IterMut") && pathHasSlice && typeArgs.isNotEmpty()) {
            return "ArrayDeque<${typeArgs[0]}>"
        }
        if (rawName == "IntoIter" && pathHasVecOrOption && typeArgs.isNotEmpty()) {
            return "ArrayDeque<${typeArgs[0]}>"
        }
        // `std::result::Result` names the standard type even when this file declares its own
        // `Result` (commonly `type Result<T> = std::result::Result<T, Error>`); qualify it so it
        // cannot resolve to that local declaration.
        val isExplicitStdResultPath =
            rawName == "Result" &&
                rawSegments.size >= 3 &&
                rawSegments[rawSegments.size - 3].ident.toString() in setOf("std", "core") &&
                rawSegments[rawSegments.size - 2].ident.toString() == "result"
        if (isExplicitStdResultPath && rawName in inspector.declaredTypeNames && typeArgs.isNotEmpty()) {
            val coreNs =
                headerContext.crateNamespaces["core"]
                    ?.trim()
                    ?.removeSuffix(".")
                    ?.takeIf { it.isNotEmpty() }
            return if (coreNs != null && typeArgs.size == 2) {
                "$coreNs.result.Result<${typeArgs.joinToString(", ")}>"
            } else {
                "kotlin.Result<${typeArgs[0]}>"
            }
        }
        if (rawName !in inspector.declaredTypeNames) {
            if (rawName in setOf("Cell", "RefCell", "OnceCell") && typeArgs.size == 1) {
                val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
                val subpkg = if (rawName == "OnceCell") "cell.once" else "cell"
                CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.$subpkg.$rawName")
                return "$rawName<${typeArgs[0]}>"
            }
            val isErasedSmartPointer =
                rawName == "Box" ||
                    rawName == "Rc" ||
                    rawName == "Arc" ||
                    rawName == "NoDrop" ||
                    rawName == "ManuallyDrop"
            when {
                rawName == "Vec" && typeArgs.isNotEmpty() -> return "List<${typeArgs[0]}>"
                rawName == "Cow" && typeArgs.isNotEmpty() -> return typeArgs.last()
                (rawName == "Range" || rawName == "RangeInclusive") && typeArgs.size == 1 && typeArgs[0] == "Int" ->
                    return "IntRange"
                isErasedSmartPointer && typeArgs.isNotEmpty() -> return typeArgs[0]
                (rawName == "HashMap" || rawName == "BTreeMap") && typeArgs.size >= 2 ->
                    return "MutableMap<${typeArgs[0]}, ${typeArgs[1]}>"
                (rawName == "HashSet" || rawName == "BTreeSet") && typeArgs.isNotEmpty() ->
                    return "MutableSet<${typeArgs[0]}>"
                rawName == "Result" && "Ok" !in inspector.declaredTypeNames && !hasDeclaredResultEnum() && usesTaggedCoreResult() && typeArgs.size == 2 ->
                    return "${sourceResultName()}<${typeArgs.joinToString(", ")}>"
                rawName == "Result" && "Ok" !in inspector.declaredTypeNames && !hasDeclaredResultEnum() && !usesTaggedCoreResult() && typeArgs.isNotEmpty() ->
                    return "Result<${typeArgs[0]}>"
            }
        }
    } else if (isStdlibUnqualified && lastSeg.arguments is PathArguments.None && rawName == "Vec" && "Vec" !in inspector.declaredTypeNames) {
        return "List<*>"
    }

    if (rawName == "TypeId" &&
        lastSeg.arguments is PathArguments.None &&
        "TypeId" !in inspector.declaredTypeNames &&
        isStdlibAnyPathSegments(rawSegments.map { it.ident.toString() })
    ) {
        usesRustAny = true
        return "RustTypeId"
    }

    val isPrimitivePrefix =
        rawSegments.size == 1 ||
            rawSegments.dropLast(1).all {
                val p = it.ident.toString()
                p in IGNORED_PATH_PREFIXES || p == "primitive"
            }
    if ((inExternAbiSignature || headerContext.rawAbiMode) && lastSeg.arguments is PathArguments.None && isPrimitivePrefix) {
        if (rawName == "usize") {
            return when (activeCfgProfile.pointerWidth) {
                16 -> "UShort"
                32 -> "UInt"
                else -> "ULong"
            }
        }
        if (rawName == "isize") {
            return when (activeCfgProfile.pointerWidth) {
                16 -> "Short"
                32 -> "Int"
                else -> "Long"
            }
        }
    }

    if (isPrimitivePrefix && lastSeg.arguments is PathArguments.None) {
        if (rawName == "u128") usesU128 = true
        if (rawName == "i128") usesI128 = true
        if (rawName == "f16") return "F16"
        if (rawName == "f32") return kotlinF32TypeName()
        if (rawName == "f128") return "F128"
        if (rawName == "char" && headerContext.scalarChar && "Char" !in inspector.declaredTypeNames) {
            usesRustChar = true
            return "RustChar"
        }
        if (rawName == "c_void" &&
            !headerContext.rawAbiMode &&
            "CVoid" !in inspector.declaredTypeNames &&
            "CVoid" !in importedSimpleNameToPkg &&
            resolveBestSubpackageForSymbol("CVoid") == null
        ) {
            resolveStdlibFfiSymbolImport("c_void")?.let {
                CoreIntrinsicLowering.registerCoreImport(this, it)
            }
        }
    }
    val ktBaseIdent = rustTypeIdentToKotlin(rawName)
    if (prefixQualifier.isEmpty() &&
        externalQualifiedOverride == null &&
        rawSegments.size <= 2 &&
        rawName != "Self" &&
        isPrimitivePrefix &&
        ktBaseIdent !in inspector.declaredTypeNames &&
        ktBaseIdent !in activeTypeParamNames &&
        !(ktBaseIdent.length == 1 && ktBaseIdent[0] in 'A'..'Z') &&
        ktBaseIdent !in inspector.enumVariantSimpleNames &&
        ktBaseIdent !in inspector.importedEnumVariantSimpleNames &&
        (ktBaseIdent !in KOTLIN_STDLIB_SHADOWED_IMPORTS || rawName == "Float") &&
        headerContext.crateSymbolSubpackages.isNotEmpty()
    ) {
        val bestSubpkg = resolveBestSubpackageForSymbol(ktBaseIdent)
        val nsPrefix = currentNamespacePrefix()
        if (bestSubpkg != null && nsPrefix != null) {
            val targetPkg = if (bestSubpkg.isEmpty()) nsPrefix else "$nsPrefix.$bestSubpkg"
            if (targetPkg.isNotEmpty() && targetPkg != headerContext.packageName) {
                val collidedPkg = importedSimpleNameToPkg[ktBaseIdent]
                if (collidedPkg != null && collidedPkg != targetPkg) {
                    externalQualifiedOverride = "$targetPkg.$ktBaseIdent"
                } else {
                    importedSimpleNameToPkg[ktBaseIdent] = targetPkg
                    extraTypePathImports += "import $targetPkg.$ktBaseIdent"
                }
            }
        }
    }
    val mappedBase =
        when {
            rawName == "Self" -> selfTypeName ?: "Any"
            externalQualifiedOverride != null -> externalQualifiedOverride
            else -> prefixQualifier + ktBaseIdent
        }

    val traitDecl = lookupDeclaredTraitAst(ktBaseIdent, path)
    val isExternalCoreOps =
        traitDecl == null &&
            ktBaseIdent !in inspector.declaredTypeNames &&
            prefixQualifier.isEmpty() &&
            externalQualifiedOverride == null &&
            resolveBestSubpackageForSymbol(ktBaseIdent) == null
    val bounded = currentBoundedTypeName
    val opsDefaultRhs = bounded ?: selfTypeName ?: "Any?"
    val declaredTypeParams =
        traitDecl
            ?.generics
            ?.params
            ?.toList()
            ?.filterIsInstance<GenericParam.TypeParam>()
            .orEmpty()
    val lookupAssocs = lookupTraitDefaultlessAssocTypes(ktBaseIdent, path)
    val isKnownCoreOps =
        isExternalCoreOps &&
            (
                ktBaseIdent in CORE_OPS_BINARY_TRAITS ||
                    ktBaseIdent in CORE_OPS_UNARY_OUTPUT_TRAITS ||
                    ktBaseIdent in CORE_OPS_ASSIGN_TRAITS
            )
    if (isKnownCoreOps && !headerContext.rawAbiMode && "core" in headerContext.crateNamespaces) {
        CoreIntrinsicLowering.registerCoreImport(
            this,
            "${CoreIntrinsicLowering.resolveCoreNamespace(headerContext)}.ops.$ktBaseIdent",
        )
    }

    return when (val args = lastSeg.arguments) {
        is PathArguments.None -> {
            when {
                isExternalCoreOps && ktBaseIdent in CORE_OPS_BINARY_TRAITS ->
                    "$mappedBase<$opsDefaultRhs, Any?>"
                isExternalCoreOps && ktBaseIdent in CORE_OPS_UNARY_OUTPUT_TRAITS ->
                    "$mappedBase<Any?>"
                isExternalCoreOps && ktBaseIdent in CORE_OPS_ASSIGN_TRAITS ->
                    "$mappedBase<$opsDefaultRhs>"
                else -> {
                    val defaultedParams =
                        declaredTypeParams.mapNotNull { gp ->
                            gp.default?.type?.let { renderType(it, selfTypeName = bounded ?: selfTypeName) }
                        }
                    val typeArgs =
                        buildList {
                            addAll(defaultedParams)
                            if (bounded != null && isSelfParameterizedTraitName(ktBaseIdent)) {
                                add(bounded)
                            }
                            if (bounded != null) {
                                repeat(lookupAssocs.size) { add("Any?") }
                            }
                        }
                    if (typeArgs.isEmpty()) mappedBase else "$mappedBase<${typeArgs.joinToString(", ")}>"
                }
            }
        }
        is PathArguments.AngleBracketed -> {
            val declaredTraitAssocs =
                if (lookupAssocs.isNotEmpty() || ktBaseIdent in inspector.declaredTypeNames || isKnownCoreOps) {
                    lookupAssocs
                } else {
                    null
                }
            val rawArgList = args.args.toList()
            if (traitDecl != null || isKnownCoreOps) {
                val explicitPositionalArgs =
                    rawArgList
                        .filter { it !is GenericArgument.AssocTypeArg && it !is GenericArgument.LifetimeArg }
                        .mapNotNull { renderGenericArgument(it, selfTypeName) }
                        .toMutableList()
                val explicitAssocByName = linkedMapOf<String, String>()
                for (ga in rawArgList) {
                    if (ga is GenericArgument.AssocTypeArg) {
                        val assocKt = rustTypeIdentToKotlin(ga.assoc.ident.toString())
                        if (declaredTraitAssocs == null || assocKt in declaredTraitAssocs) {
                            explicitAssocByName[assocKt] = renderType(ga.assoc.ty, selfTypeName)
                        }
                    }
                }
                if (isKnownCoreOps) {
                    if ((ktBaseIdent in CORE_OPS_BINARY_TRAITS || ktBaseIdent in CORE_OPS_ASSIGN_TRAITS) &&
                        explicitPositionalArgs.isEmpty()
                    ) {
                        explicitPositionalArgs += opsDefaultRhs
                    }
                } else if (declaredTypeParams.isNotEmpty() && explicitPositionalArgs.size < declaredTypeParams.size) {
                    for (idx in explicitPositionalArgs.size until declaredTypeParams.size) {
                        val defTy = declaredTypeParams[idx].default?.type ?: break
                        explicitPositionalArgs += renderType(defTy, selfTypeName = bounded ?: selfTypeName)
                    }
                }
                val finalAssocs =
                    lookupAssocs.mapNotNull { assocKt ->
                        explicitAssocByName[assocKt] ?: if (bounded != null || isKnownCoreOps) "Any?" else null
                    }
                val typeArgs =
                    buildList {
                        addAll(explicitPositionalArgs)
                        if (bounded != null && isSelfParameterizedTraitName(ktBaseIdent)) {
                            add(bounded)
                        }
                        addAll(finalAssocs)
                        addAll(sourceAssociatedArguments(path, selfTypeName))
                    }
                if (typeArgs.isEmpty()) mappedBase else "$mappedBase<${typeArgs.joinToString(", ")}>"
            } else {
                val filteredArgs =
                    rawArgList.filter { ga ->
                        if (ga is GenericArgument.AssocTypeArg && declaredTraitAssocs != null) {
                            rustTypeIdentToKotlin(ga.assoc.ident.toString()) in declaredTraitAssocs
                        } else {
                            true
                        }
                    }
                val typeArgs =
                    filteredArgs.mapNotNull { renderGenericArgument(it, selfTypeName) } +
                        sourceAssociatedArguments(path, selfTypeName)
                if (typeArgs.isEmpty()) mappedBase else "$mappedBase<${typeArgs.joinToString(", ")}>"
            }
        }
        is PathArguments.Parenthesized -> {
            val inputs = args.inputs.toList().joinToString(", ") { renderType(it.ty, selfTypeName) }
            val out = extractReturnSynType(args.output)?.let { renderType(it, selfTypeName) } ?: "Unit"
            "($inputs) -> $out"
        }
    }
}

// Rust const generic identifiers can parse as type arguments. Keep bound
// value names out of Kotlin's type list and forward them as runtime arguments.
internal fun KotlinJ2KEmitter.runtimeConstTypeArgument(arg: GenericArgument.TypeArg): String? {
    val ty = arg.type as? SynType.Path ?: return null
    if (ty.qself != null) return null
    val segments = ty.path.segments.toList()
    if (segments.size != 1 || segments[0].arguments !is PathArguments.None) return null
    val raw = segments[0].ident.toString().removePrefix("r#")
    val typeName = rustTypeIdentToKotlin(raw)
    if (typeName in activeTypeParamNames || typeName in inspector.declaredTypeNames) return null
    return scopeBuilder.resolveLocalKotlinName(raw) ?: inspector.declaredConstNames[raw]
}

internal fun KotlinJ2KEmitter.renderGenericArgument(arg: GenericArgument, selfTypeName: String?): String? =
    when (arg) {
        is GenericArgument.LifetimeArg -> null
        is GenericArgument.TypeArg -> if (arg.type is SynType.Infer || runtimeConstTypeArgument(arg) != null) null else renderType(arg.type, selfTypeName)
        is GenericArgument.ConstArg -> null
        is GenericArgument.AssocTypeArg -> renderType(arg.assoc.ty, selfTypeName)
        is GenericArgument.AssocConstArg -> null
        is GenericArgument.ConstraintArg -> null
    }

internal fun KotlinJ2KEmitter.renderTypeParameters(
    generics: Generics,
    selfTypeName: String?,
    excludeNames: Set<String> = emptySet(),
): String {
    val declared = renderTypeParametersFromParams(generics.params.toList(), selfTypeName, excludeNames).removePrefix("<").removeSuffix(">")
    val associated = sourceAssociatedParameters(generics).map { it.associated }.distinct().filter { it !in excludeNames }
    val parts = listOf(declared).filter(String::isNotEmpty) + associated
    return if (parts.isEmpty()) "" else "<${parts.joinToString(", ")}>"
}

internal fun KotlinJ2KEmitter.renderTypeParametersFromParams(
    params: List<GenericParam>,
    selfTypeName: String?,
    excludeNames: Set<String> = emptySet(),
): String {
    val rendered = mutableListOf<String>()
    val seenNames = hashSetOf<String>()
    for (param in params) {
        when (param) {
            is GenericParam.LifetimeParam -> {}
            is GenericParam.TypeParam -> {
                val name = rustTypeIdentToKotlin(param.ident.toString())
                if (name in excludeNames || !seenNames.add(name)) continue
                val prevBounded = currentBoundedTypeName
                currentBoundedTypeName = name
                val bounds =
                    try {
                        param.bounds
                            .toList()
                            .mapNotNull { renderTypeParamBound(it, selfTypeName) }
                            .filter { it != "Any" && it != name && !it.contains("->") }
                    } finally {
                        currentBoundedTypeName = prevBounded
                    }
                if (bounds.size == 1) {
                    rendered += "$name : ${bounds[0]}"
                } else {
                    rendered += name
                }
            }
            is GenericParam.ConstParam -> {}
        }
    }
    return if (rendered.isEmpty()) "" else "<${rendered.joinToString(", ")}>"
}

internal fun KotlinJ2KEmitter.renderTypeArgumentsFromGenerics(generics: Generics): String {
    val names =
        generics.params.toList().mapNotNull { param ->
            (param as? GenericParam.TypeParam)?.ident?.toString()?.let { rustTypeIdentToKotlin(it) }
        }
    val allNames = (names + sourceAssociatedParameters(generics).map { it.associated }).distinct()
    return if (allNames.isEmpty()) "" else "<${allNames.joinToString(", ")}>"
}

internal fun KotlinJ2KEmitter.renderWhereClause(
    whereClause: WhereClause?,
    selfTypeName: String?,
    ownerGenerics: Generics? = null,
    extraDeclaredNames: Set<String> = emptySet(),
): String {
    val assocDeclared = ownerGenerics?.let { sourceAssociatedParameters(it).map { a -> a.associated } }.orEmpty()
    return renderCombinedWhereClause(
        ownerWhere = null,
        fnWhere = whereClause,
        params = ownerGenerics?.params?.toList().orEmpty(),
        selfTypeName = selfTypeName,
        extraDeclaredNames = extraDeclaredNames + assocDeclared,
    )
}

internal fun KotlinJ2KEmitter.renderCombinedWhereClause(
    ownerWhere: WhereClause?,
    fnWhere: WhereClause?,
    params: List<GenericParam>,
    selfTypeName: String?,
    excludeNames: Set<String> = emptySet(),
    extraDeclaredNames: Set<String> = emptySet(),
): String {
    val declaredTypeParamNames =
        (
            params
                .mapNotNull { (it as? GenericParam.TypeParam)?.ident?.toString()?.let(::rustTypeIdentToKotlin) }
                .filter { it !in excludeNames } +
                extraDeclaredNames.filter { it !in excludeNames }
        ).toSet()
    if (declaredTypeParamNames.isEmpty()) return ""
    val clauses = mutableListOf<String>()
    val seenParamNames = hashSetOf<String>()
    for (param in params) {
        if (param is GenericParam.TypeParam) {
            val name = rustTypeIdentToKotlin(param.ident.toString())
            if (name in excludeNames || !seenParamNames.add(name)) continue
            val prevBounded = currentBoundedTypeName
            currentBoundedTypeName = name
            val bounds =
                try {
                    param.bounds
                        .toList()
                        .mapNotNull { renderTypeParamBound(it, selfTypeName) }
                        .filter { it != "Any" && it != name && !it.contains("->") }
                } finally {
                    currentBoundedTypeName = prevBounded
                }
            if (bounds.size > 1) {
                for (b in bounds) {
                    clauses += "$name : $b"
                }
            }
        }
    }
    val predicates =
        buildList {
            ownerWhere?.predicates?.toList()?.let { addAll(it) }
            fnWhere?.predicates?.toList()?.let { addAll(it) }
        }
    for (pred in predicates) {
        if (pred is WherePredicate.TypePredicate) {
            if (!isSimpleTypeParamWhereTarget(pred.boundedTy)) continue
            val boundedTy = renderType(pred.boundedTy, selfTypeName)
            if (boundedTy in excludeNames || !isValidKotlinIdentifierText(boundedTy)) continue
            if (boundedTy !in declaredTypeParamNames) continue
            val prevBounded = currentBoundedTypeName
            currentBoundedTypeName = boundedTy
            try {
                for (bound in pred.bounds.toList()) {
                    val b = renderTypeParamBound(bound, selfTypeName)
                    if (b != null && b != "Any" && b != boundedTy && !b.contains("->")) {
                        clauses += "$boundedTy : $b"
                    }
                }
            } finally {
                currentBoundedTypeName = prevBounded
            }
        }
    }
    val distinct = clauses.distinct()
    return if (distinct.isEmpty()) "" else " where ${distinct.joinToString(", ")}"
}

internal fun KotlinJ2KEmitter.renderTypeParamBound(bound: TypeParamBound, selfTypeName: String?): String? =
    when (bound) {
        is TypeParamBound.LifetimeBound -> null
        is TypeParamBound.PreciseCapture -> null
        is TypeParamBound.Verbatim -> null
        is TypeParamBound.Trait -> {
            if (bound.maybe != null) {
                null
            } else {
                val segs = bound.path.segments.toList()
                val lastSeg = segs.lastOrNull()
                val last = lastSeg?.ident?.toString()
                val ktTrait = last?.let(::rustTypeIdentToKotlin)
                val boundedTp = currentBoundedTypeName
                val isPrivateSealedBound =
                    segs.size >= 2 &&
                        segs[segs.size - 2].ident.toString() in setOf("private", "sealed") &&
                        last == "Sealed"
                if (last == null ||
                    isPrivateSealedBound ||
                    last in IGNORED_TRAIT_BOUNDS ||
                    (
                        ktTrait != null &&
                            lookupTraitDefaultlessAssocTypes(ktTrait, bound.path).isNotEmpty() &&
                            sameFileNestedItems(file.items).filterIsInstance<Item.Impl>().any {
                                it.traitPath
                                    ?.path
                                    ?.segments
                                    ?.last()
                                    ?.ident
                                    .toString() == last && fixedScalarPointerCodec(it.selfType) != null
                            }
                    ) ||
                    (boundedTp != null && ktTrait != null && ktTrait in currentWitnessedTraitBounds[boundedTp].orEmpty())
                ) {
                    null
                } else if (boundedTp != null && (last == "IntoIterator" || last == "Iterator") && ktTrait !in inspector.declaredTypeNames) {
                    val angle = lastSeg.arguments as? PathArguments.AngleBracketed
                    val itemTy =
                        angle
                            ?.args
                            ?.toList()
                            ?.firstNotNullOfOrNull { ga ->
                                if (ga is GenericArgument.AssocTypeArg && ga.assoc.ident.toString() == "Item") {
                                    renderType(ga.assoc.ty, selfTypeName)
                                } else {
                                    null
                                }
                            }
                    if (itemTy != null) {
                        if (last == "IntoIterator") "Iterable<$itemTy>" else "Iterator<$itemTy>"
                    } else {
                        null
                    }
                } else if (isImplicitStdlibSupertraitPath(bound.path)) {
                    null
                } else {
                    renderPathAsType(bound.path, selfTypeName)
                }
            }
        }
    }

internal fun KotlinJ2KEmitter.renderVisibility(vis: Visibility): String =
    when (vis) {
        is Visibility.Public -> "public "
        is Visibility.Restricted -> if (headerContext.isBatchCrateOutput) "public " else "internal "
        is Visibility.Inherited -> "public "
    }

internal fun KotlinJ2KEmitter.hasTestAttribute(attrs: List<Attribute>): Boolean {
    for (attr in attrs) {
        val path = attr.path()
        if (path.isIdent("test")) return true
        val lastSeg =
            path.segments
                .last()
                ?.ident
                ?.toString()
        if (lastSeg == "test") return true
        val meta = attr.meta
        if (meta is Meta.List && meta.path.isIdent("cfg")) {
            if (containsPositiveCfgTestIdent(meta.tokens.clone())) return true
        }
    }
    return false
}

internal fun KotlinJ2KEmitter.containsPositiveCfgTestIdent(stream: TokenStream): Boolean {
    val trees = stream.clone().toList()
    var idx = 0
    while (idx < trees.size) {
        when (val tree = trees[idx]) {
            is TokenTree.Ident -> {
                val word = tree.value.toString()
                if (word == "not" && idx + 1 < trees.size && trees[idx + 1] is TokenTree.Group) {
                    idx += 2
                    continue
                }
                if (word == "test") return true
            }
            is TokenTree.Group -> {
                if (containsPositiveCfgTestIdent(tree.value.stream())) return true
            }
            else -> {}
        }
        idx++
    }
    return false
}

internal fun KotlinJ2KEmitter.isTransparentWrapperPath(path: Path): Boolean {
    val segs = path.segments.toList()
    if (segs.size != 2) return false
    val owner = segs[0].ident.toString()
    val member = segs[1].ident.toString()
    return (owner in setOf("Box", "Rc", "Arc", "NoDrop") && member == "new") ||
        (owner == "Box" && (member == "as_ref" || member == "as_mut")) ||
        ((owner == "Rc" || owner == "Arc") && member == "as_ref")
}

internal fun KotlinJ2KEmitter.isTransparentIdentityExpr(body: Expr, rawParamName: String): Boolean =
    when (body) {
        is Expr.Path -> {
            val firstSeg = body.path.segments.first()
            val identText = firstSeg?.ident?.toString()
            body.qself == null &&
                body.path.segments.size == 1 &&
                identText?.removePrefix("r#") == rawParamName
        }
        is Expr.Paren -> isTransparentIdentityExpr(body.expr, rawParamName)
        is Expr.Group -> isTransparentIdentityExpr(body.expr, rawParamName)
        is Expr.Reference -> isTransparentIdentityExpr(body.expr, rawParamName)
        is Expr.BlockExpr -> {
            val single = body.block.stmts.singleOrNull() as? Stmt.ExprStmt
            single != null && single.semiToken == null && isTransparentIdentityExpr(single.expr, rawParamName)
        }
        is Expr.Call -> {
            val fnPath = (body.func as? Expr.Path)?.takeIf { it.qself == null }?.path
            val singleArg = body.args.toList().singleOrNull()
            fnPath != null && singleArg != null && isTransparentWrapperPath(fnPath) && isTransparentIdentityExpr(singleArg, rawParamName)
        }
        else -> false
    }

internal fun KotlinJ2KEmitter.isTransparentWrapperOrIdentityMapArg(argExpr: Expr): Boolean =
    when (argExpr) {
        is Expr.Path -> argExpr.qself == null && isTransparentWrapperPath(argExpr.path)
        is Expr.Closure -> {
            val singleInput = argExpr.inputs.toList().singleOrNull()
            val rawParam =
                when (val unwrappedPat = singleInput?.let(this::unwrapTransparentPat)) {
                    is Pat.Ident -> unwrappedPat.ident.toString().removePrefix("r#")
                    else -> null
                }
            rawParam != null && isTransparentIdentityExpr(argExpr.body, rawParam)
        }
        is Expr.Paren -> isTransparentWrapperOrIdentityMapArg(argExpr.expr)
        is Expr.Group -> isTransparentWrapperOrIdentityMapArg(argExpr.expr)
        else -> false
    }

internal fun KotlinJ2KEmitter.isSimpleTypeParamWhereTarget(ty: SynType): Boolean {
    val pathTy = unwrapSynType(ty) as? SynType.Path ?: return false
    if (pathTy.qself != null || pathTy.path.leadingColon != null) return false
    val segList = pathTy.path.segments.toList()
    val singleSeg = segList.singleOrNull() ?: return false
    return singleSeg.arguments is PathArguments.None
}

internal fun KotlinJ2KEmitter.isValidKotlinIdentifierText(text: String): Boolean {
    if (text.isEmpty()) return false
    val first = text[0]
    if (!first.isLetter() && first != '_') return false
    return text.all { it.isLetterOrDigit() || it == '_' }
}

internal fun KotlinJ2KEmitter.extractLocalTypeAnnotation(pat: Pat, selfTypeName: String?): String? =
    when (pat) {
        is Pat.TypeAscription -> if (pat.ty is SynType.Infer) null else renderType(pat.ty, selfTypeName)
        is Pat.Reference -> extractLocalTypeAnnotation(pat.pat, selfTypeName)
        is Pat.PatParen -> extractLocalTypeAnnotation(pat.pat, selfTypeName)
        else -> null
    }

internal fun KotlinJ2KEmitter.collectGenericTypeParamNames(generics: Generics?): Set<String> {
    if (generics == null) return emptySet()
    return generics.params
        .toList()
        .mapNotNull { param ->
            (param as? GenericParam.TypeParam)?.ident?.toString()?.let(::rustTypeIdentToKotlin)
        }.toSet()
}

private val C_POINTER_WIDTH_UNSIGNED_TYPE_NAMES =
    setOf(
        "size_t",
        "SizeT",
        "uintptr_t",
        "UintptrT",
        "rsize_t",
        "RsizeT",
        "__size_t",
    )

private val C_POINTER_WIDTH_SIGNED_TYPE_NAMES =
    setOf(
        "ssize_t",
        "SsizeT",
        "ptrdiff_t",
        "PtrdiffT",
        "intptr_t",
        "IntptrT",
        "__ssize_t",
    )

internal fun isCPointerWidthUnsignedTypeName(name: String): Boolean =
    name in C_POINTER_WIDTH_UNSIGNED_TYPE_NAMES

internal fun isCPointerWidthSignedTypeName(name: String): Boolean =
    name in C_POINTER_WIDTH_SIGNED_TYPE_NAMES

internal fun extractSinglePrimitiveTargetIdent(ty: SynType): String? {
    val pathTy = unwrapSynType(ty) as? SynType.Path ?: return null
    if (pathTy.qself != null) return null
    val segs = pathTy.path.segments.toList()
    if (segs.isEmpty()) return null
    val last = segs.last()
    if (last.arguments !is PathArguments.None) return null
    val validPrefix =
        segs.dropLast(1).all { seg ->
            val p = seg.ident.toString().removePrefix("r#")
            seg.arguments is PathArguments.None && (p in IGNORED_PATH_PREFIXES || p == "primitive")
        }
    return if (validPrefix) last.ident.toString().removePrefix("r#") else null
}

internal fun KotlinJ2KEmitter.resolveNumericKind(kotlinType: String, depth: Int = 0): NumericKind? {
    val base = kotlinType.trim()
    if (base.endsWith("?")) return null
    val simpleBase = base.substringAfterLast('.')
    when (base) {
        "Byte" -> return NumericKind.I8
        "Short" -> return NumericKind.I16
        "Int" -> return NumericKind.I32
        "Long" -> return NumericKind.I64
        "I128" -> return NumericKind.I128
        "UByte" -> return NumericKind.U8
        "UShort" -> return NumericKind.U16
        "UInt" -> return NumericKind.U32
        "ULong" -> return NumericKind.U64
        "U128" -> return NumericKind.U128
        "kotlin.Float" -> return NumericKind.F32
        "Float" -> return if (isKotlinFloatShadowed()) null else NumericKind.F32
        "Double" -> return NumericKind.F64
        "F16" -> return NumericKind.F16
        "F128" -> return NumericKind.F128
    }
    val ptrWidth = activeCfgProfile.pointerWidth
    val isWindows =
        activeCfgProfile.targetOs == "windows" ||
            activeCfgProfile.targetFamilies?.contains("windows") == true
    val ptrSigned =
        when (ptrWidth) {
            64 -> NumericKind.I64
            16 -> NumericKind.I16
            else -> NumericKind.I32
        }
    val ptrUnsigned =
        when (ptrWidth) {
            64 -> NumericKind.U64
            16 -> NumericKind.U16
            else -> NumericKind.U32
        }
    val cLongSigned = if (ptrWidth == 64 && !isWindows) NumericKind.I64 else NumericKind.I32
    val cLongUnsigned = if (ptrWidth == 64 && !isWindows) NumericKind.U64 else NumericKind.U32

    if (depth < 8) {
        val aliasTarget =
            inspector.typeAliasTargets[base]
                ?: inspector.typeAliasTargets[simpleBase]
                ?: inspector.typeAliasTargets[rustTypeIdentToKotlin(simpleBase)]
        if (aliasTarget != null) {
            if (!headerContext.rawAbiMode && unwrapSynType(aliasTarget) is SynType.Ptr) {
                return null
            }
            val primTarget = extractSinglePrimitiveTargetIdent(aliasTarget)
            val isFfiAlias =
                headerContext.rawAbiMode ||
                    inExternAbiSignature ||
                    base in inspector.ffiReferencedTypeNames ||
                    simpleBase in inspector.ffiReferencedTypeNames
            val isScannerPointerWidthUnsigned =
                scopedAbiKeysForRenderedType(base).any {
                    headerContext.crateNumericTypeKinds[it] in setOf("U64", "U32", "U16")
                } ||
                    (
                        simpleBase != base &&
                            scopedAbiKeysForRenderedType(simpleBase).any {
                                headerContext.crateNumericTypeKinds[it] in setOf("U64", "U32", "U16")
                            }
                    )
            val isScannerPointerWidthSigned =
                scopedAbiKeysForRenderedType(base).any {
                    headerContext.crateNumericTypeKinds[it] in setOf("I64", "I32", "I16")
                } ||
                    (
                        simpleBase != base &&
                            scopedAbiKeysForRenderedType(simpleBase).any {
                                headerContext.crateNumericTypeKinds[it] in setOf("I64", "I32", "I16")
                            }
                    )
            if (primTarget == "usize" && (isFfiAlias || isCPointerWidthUnsignedTypeName(simpleBase) || isScannerPointerWidthUnsigned)) {
                return ptrUnsigned
            }
            if (primTarget == "isize" && (isFfiAlias || isCPointerWidthSignedTypeName(simpleBase) || isScannerPointerWidthSigned)) {
                return ptrSigned
            }
            val prevParam = inExternParamType
            inExternParamType = false
            val renderedAlias =
                try {
                    renderType(aliasTarget, selfTypeName = null)
                } finally {
                    inExternParamType = prevParam
                }
            if (renderedAlias != base) {
                resolveNumericKind(renderedAlias, depth + 1)?.let { return it }
            }
        }
    }
    if (simpleBase in inspector.declaredTypeNames &&
        simpleBase !in inspector.typeAliasTargets &&
        simpleBase !in setOf("U128", "I128", "F16", "F128")
    ) {
        return null
    }
    if (simpleBase == "CLong" || simpleBase == "c_long") {
        val curSub = currentSubpackageString()
        if (curSub.isNotEmpty()) {
            numericKindFromScannerCode(headerContext.crateNumericTypeKinds["$curSub.$simpleBase"])?.let { return it }
        }
        return cLongSigned
    }
    if (simpleBase == "CUlong" || simpleBase == "c_ulong") {
        val curSub = currentSubpackageString()
        if (curSub.isNotEmpty()) {
            numericKindFromScannerCode(headerContext.crateNumericTypeKinds["$curSub.$simpleBase"])?.let { return it }
        }
        return cLongUnsigned
    }
    for (key in scopedAbiKeysForRenderedType(base)) {
        val scopedDef = headerContext.crateTypeAliasDefaults[key]
        if (key in headerContext.cratePointerTypeAliases ||
            key in headerContext.crateOptionFnTypes ||
            (
                scopedDef != null &&
                    scopedDef !in setOf("I128()", "U128()", "F16()", "F128()") &&
                    (scopedDef.endsWith("()") || scopedDef.endsWith("(0)") || scopedDef == "emptyList()" || (!headerContext.rawAbiMode && scopedDef == "null"))
            )
        ) {
            return null
        }
        numericKindFromScannerCode(headerContext.crateNumericTypeKinds[key])?.let { return it }
    }
    if (simpleBase != base) {
        for (key in scopedAbiKeysForRenderedType(simpleBase)) {
            val scopedDef = headerContext.crateTypeAliasDefaults[key]
            if (key in headerContext.cratePointerTypeAliases ||
                key in headerContext.crateOptionFnTypes ||
                (
                    scopedDef != null &&
                        scopedDef !in setOf("I128()", "U128()", "F16()", "F128()") &&
                        (scopedDef.endsWith("()") || scopedDef.endsWith("(0)") || scopedDef == "emptyList()" || (!headerContext.rawAbiMode && scopedDef == "null"))
                )
            ) {
                return null
            }
            numericKindFromScannerCode(headerContext.crateNumericTypeKinds[key])?.let { return it }
        }
    }
    if (isCPointerWidthSignedTypeName(simpleBase)) return ptrSigned
    if (isCPointerWidthUnsignedTypeName(simpleBase)) return ptrUnsigned
    return when (simpleBase) {
        "CSchar", "c_schar", "Int8T", "int8_t" -> NumericKind.I8
        "CChar", "c_char" -> if (headerContext.rawAbiMode) NumericKind.I8 else NumericKind.U8
        "CShort", "c_short", "Int16T", "int16_t" -> NumericKind.I16
        "CInt", "c_int", "Int32T", "int32_t", "PidT", "pid_t", "ClockidT", "clockid_t", "WcharT", "wchar_t",
        "KeyT", "key_t", "IdT", "id_t", "SusecondsT", "suseconds_t",
        -> NumericKind.I32
        "CLong", "c_long" -> cLongSigned
        "CLonglong", "c_longlong", "Int64T", "int64_t", "OffT", "off_t", "Off64T", "off64_t",
        "TimeT", "time_t", "BlkcntT", "blkcnt_t", "BlksizeT", "blksize_t",
        -> NumericKind.I64
        "CUchar", "c_uchar", "Uint8T", "uint8_t" -> NumericKind.U8
        "CUshort", "c_ushort", "Uint16T", "uint16_t", "SaFamilyT", "sa_family_t", "InPortT", "in_port_t" ->
            NumericKind.U16
        "CUint", "c_uint", "Uint32T", "uint32_t", "CanidT", "canid_t", "UidT", "uid_t", "GidT", "gid_t",
        "ModeT", "mode_t", "SocklenT", "socklen_t", "InAddrT", "in_addr_t",
        -> NumericKind.U32
        "CUlong", "c_ulong" -> cLongUnsigned
        "CUlonglong", "c_ulonglong", "Uint64T", "uint64_t" -> NumericKind.U64
        "CFloat", "c_float" -> NumericKind.F32
        "CDouble", "c_double" -> NumericKind.F64
        else -> null
    }
}

internal fun KotlinJ2KEmitter.renderTypedNumericInitializer(
    expr: Expr,
    kind: NumericKind?,
    indent: String,
    selfTypeName: String?,
): String {
    val prev = currentExpectedNumericKind
    currentExpectedNumericKind = kind
    val rendered =
        try {
            renderExpr(expr, indent, selfTypeName)
        } finally {
            currentExpectedNumericKind = prev
        }
    if (kind == null) return rendered
    if (isAlreadyRenderedAsNumericKind(rendered, kind)) return rendered
    val core = unwrapTransparentCastExpr(expr)
    val isCompound =
        core is Expr.Binary ||
            (core is Expr.Unary && (core.op is UnOp.NotOp || core.op is UnOp.Neg))
    if (!isCompound) {
        if (core is Expr.Lit) return rendered
        val inferred = inferExprNumericKind(expr, selfTypeName)
        if (inferred == null || inferred == kind) return rendered
        val recv = if (isAtomicPostfixReceiver(expr, rendered)) rendered else "($rendered)"
        return when (kind) {
            NumericKind.U8 -> if (rendered.endsWith(".toUByte()")) rendered else "$recv.toUByte()"
            NumericKind.U16 -> if (rendered.endsWith(".toUShort()")) rendered else "$recv.toUShort()"
            NumericKind.U32 -> if (rendered.endsWith(".toUInt()")) rendered else "$recv.toUInt()"
            NumericKind.U64 -> if (rendered.endsWith(".toULong()")) rendered else "$recv.toULong()"
            NumericKind.I8 -> if (rendered.endsWith(".toByte()")) rendered else "$recv.toByte()"
            NumericKind.I16 -> if (rendered.endsWith(".toShort()")) rendered else "$recv.toShort()"
            NumericKind.I32 -> if (rendered.endsWith(".toInt()")) rendered else "$recv.toInt()"
            NumericKind.I64 -> if (rendered.endsWith(".toLong()")) rendered else "$recv.toLong()"
            NumericKind.F16 -> convertNumericReceiver(recv, "F16", inferred)
            NumericKind.F32 -> if (rendered.endsWith(".toFloat()")) rendered else "$recv.toFloat()"
            NumericKind.F64 -> if (rendered.endsWith(".toDouble()")) rendered else "$recv.toDouble()"
            NumericKind.F128 -> convertNumericReceiver(recv, "F128", inferred)
            else -> rendered
        }
    }
    if ((kind == NumericKind.U8 || kind == NumericKind.U16) &&
        (
            (
                core is Expr.Binary &&
                    (
                        core.op is BinOp.BitAnd ||
                            core.op is BinOp.BitOr ||
                            core.op is BinOp.BitXor ||
                            core.op is BinOp.Shl ||
                            core.op is BinOp.Shr
                    )
            ) ||
                (core is Expr.Unary && core.op is UnOp.NotOp)
        ) &&
        inferExprNumericKind(core, selfTypeName) == kind
    ) {
        return rendered
    }
    return when (kind) {
        NumericKind.U8 ->
            if (rendered.endsWith(".toUByte()")) {
                rendered
            } else if (isAtomicPostfixReceiver(expr, rendered)) {
                "$rendered.toUByte()"
            } else {
                "($rendered).toUByte()"
            }
        NumericKind.U16 ->
            if (rendered.endsWith(".toUShort()")) {
                rendered
            } else if (isAtomicPostfixReceiver(expr, rendered)) {
                "$rendered.toUShort()"
            } else {
                "($rendered).toUShort()"
            }
        NumericKind.I8 ->
            if (rendered.endsWith(".toByte()")) {
                rendered
            } else if (isAtomicPostfixReceiver(expr, rendered)) {
                "$rendered.toByte()"
            } else {
                "($rendered).toByte()"
            }
        NumericKind.I16 ->
            if (rendered.endsWith(".toShort()")) {
                rendered
            } else if (isAtomicPostfixReceiver(expr, rendered)) {
                "$rendered.toShort()"
            } else {
                "($rendered).toShort()"
            }
        else -> rendered
    }
}

internal fun KotlinJ2KEmitter.renderPointerType(ptr: SynType.Ptr, selfTypeName: String?): String {
    if (headerContext.rawAbiMode) {
        return "Long"
    }
    val prevParam = inExternParamType
    inExternParamType = false
    return try {
        val pointee = renderType(ptr.elem, selfTypeName)
        val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
        val ptrClass = if (ptr.mutability is PointerMutability.Mut) "MutPtr" else "ConstPtr"
        CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.ptr.$ptrClass")
        "$ptrClass<$pointee>"
    } finally {
        inExternParamType = prevParam
    }
}

internal fun KotlinJ2KEmitter.isAlreadyNullableRenderedType(rendered: String): Boolean {
    val trimmed = rendered.trim()
    if (trimmed.endsWith("?")) return true
    val simple = trimmed.substringAfterLast('.')
    if (inspector.typeAliasTargets[simple]?.let(::unwrapSynType)?.let(::extractOptionBareFn) != null) {
        return true
    }
    val keys = scopedAbiKeysForRenderedType(trimmed)
    if (keys.any { it in headerContext.crateOptionFnTypes }) {
        return true
    }
    return false
}

internal fun KotlinJ2KEmitter.isOptionFnTypeAliasRenderedType(rendered: String): Boolean {
    val trimmed = rendered.trim().removeSuffix("?")
    val simple = trimmed.substringAfterLast('.')
    if (inspector.typeAliasTargets[simple]?.let(::unwrapSynType)?.let(::extractOptionBareFn) != null) {
        return true
    }
    return scopedAbiKeysForRenderedType(trimmed).any { it in headerContext.crateOptionFnTypes }
}

internal fun KotlinJ2KEmitter.extractOptionBareFn(ty: SynType): SynType.FnPtr? {
    val pathTy = unwrapSynType(ty) as? SynType.Path ?: return null
    if (pathTy.qself != null) return null
    val segs = pathTy.path.segments.toList()
    if (segs.isEmpty()) return null
    val last = segs.last()
    if (last.ident.toString() != "Option") return null
    if (segs.dropLast(1).any { it.ident.toString() !in STDLIB_TYPE_PATH_PREFIXES }) return null
    val angle = last.arguments as? PathArguments.AngleBracketed ?: return null
    val args = angle.args.toList()
    if (args.size != 1) return null
    val inner = (args[0] as? GenericArgument.TypeArg)?.type?.let(::unwrapSynType) ?: return null
    return inner as? SynType.FnPtr
}

internal fun KotlinJ2KEmitter.synthesizeOrReuseBareFnSam(ty: SynType.FnPtr, selfTypeName: String?): String {
    val prevHint = currentSamNameHint
    val prevExternAbi = inExternAbiSignature
    currentSamNameHint = null
    inExternAbiSignature = prevExternAbi || (ty.abi != null)
    val (inputTypes, outType) =
        try {
            val ins = ty.inputs.toList().map { renderType(it.ty, selfTypeName) }
            val out = extractReturnSynType(ty.output)?.let { renderType(it, selfTypeName) } ?: "Unit"
            ins to out
        } finally {
            currentSamNameHint = prevHint
            inExternAbiSignature = prevExternAbi
        }
    val referencesGeneric =
        activeTypeParamNames.any { tp ->
            inputTypes.any { containsWholeIdentifier(it, tp) } || containsWholeIdentifier(outType, tp)
        }
    if (referencesGeneric) {
        return "(${inputTypes.joinToString(", ")}) -> $outType"
    }
    val shapeKey = "(${inputTypes.joinToString(", ")})->$outType"
    synthesizedSamNamesByShape[shapeKey]?.let { return it }

    val rawCandidate = rustTypeIdentToKotlin(currentSamNameHint ?: "FnCallback")
    var candidate = rawCandidate
    var suffixIdx = 1
    while (candidate in inspector.declaredTypeNames || candidate in synthesizedSamDeclarations) {
        candidate = "$rawCandidate$suffixIdx"
        suffixIdx++
    }
    inspector.declaredTypeNames += candidate
    synthesizedSamNamesByShape[shapeKey] = candidate
    synthesizedSamDeclarations[candidate] = renderBareFnSamInterface("public ", candidate, ty, "")
    return candidate
}

internal fun KotlinJ2KEmitter.renderBareFnSamInterface(
    vis: String,
    samName: String,
    bareFn: SynType.FnPtr,
    indent: String,
): String {
    val prevHint = currentSamNameHint
    val prevExternAbi = inExternAbiSignature
    currentSamNameHint = null
    inExternAbiSignature = prevExternAbi || (bareFn.abi != null)
    return try {
        val usedNames = hashSetOf<String>()
        val paramTypes = mutableListOf<String>()
        val params =
            bareFn.inputs.toList().mapIndexed { idx, arg ->
                val rawName =
                    arg.name
                        ?.ident
                        ?.toString()
                        ?.removePrefix("r#")
                        ?.takeIf { it.isNotEmpty() && it != "_" }
                val rawBase = rawName?.let(::rustValueIdentToKotlin) ?: "arg$idx"
                val baseName = if (rawBase == "errno") "errnum" else rawBase
                val pName = if (usedNames.add(baseName)) baseName else "${baseName}$idx"
                usedNames.add(pName)
                val pType = renderType(arg.ty, selfTypeName = null)
                paramTypes += pType
                "$pName: $pType"
            }
        val retType = extractReturnSynType(bareFn.output)?.let { renderType(it, selfTypeName = null) } ?: "Unit"
        val shapeKey = "(${paramTypes.joinToString(", ")})->$retType"
        if (shapeKey !in synthesizedSamNamesByShape) {
            synthesizedSamNamesByShape[shapeKey] = samName
        }
        if (samName !in synthesizedSamDefaultsByName) {
            val retDefault = if (retType == "Unit") "" else renderFallbackDefaultForRenderedType(retType)
            val lambdaParams =
                if (paramTypes.isEmpty()) {
                    ""
                } else {
                    paramTypes.indices.joinToString(", ") { "_" } + " -> "
                }
            synthesizedSamDefaultsByName[samName] =
                if (lambdaParams.isEmpty() && retDefault.isEmpty()) {
                    "$samName {}"
                } else {
                    "$samName { ${(lambdaParams + retDefault).trim()} }"
                }
        }
        val retSuffix = if (retType == "Unit") "" else ": $retType"
        buildString {
            append("$indent${vis}fun interface $samName {\n")
            append(indent)
                .append("    public operator fun invoke(")
                .append(params.joinToString(", "))
                .append(')')
                .append(retSuffix)
                .append('\n')
            append(indent).append('}')
        }
    } finally {
        currentSamNameHint = prevHint
        inExternAbiSignature = prevExternAbi
    }
}

internal fun KotlinJ2KEmitter.extractEnumReprKotlinType(attrs: List<Attribute>): String? {
    for (attr in attrs) {
        val meta = attr.meta
        if (meta is Meta.List && meta.path.isIdent("repr")) {
            for (tt in meta.tokens.clone().toList()) {
                if (tt is TokenTree.Ident) {
                    val ident = tt.value.toString()
                    if (ident != "C" && ident != "transparent" && ident != "packed" && ident != "align") {
                        return rustTypeIdentToKotlin(ident)
                    }
                }
            }
        }
    }
    return null
}

internal fun KotlinJ2KEmitter.isStdlibAnyPathSegments(segNames: List<String>): Boolean {
    if (segNames.isEmpty()) return false
    val last = segNames.last()
    if (last != "Any" && last != "TypeId") return false
    if ("Any" in inspector.declaredTraitsByName || "TypeId" in inspector.declaredTypeNames) return false
    if (segNames.size == 1) return true
    val prefix = segNames.dropLast(1)
    return prefix == listOf("any") ||
        prefix == listOf("std", "any") ||
        prefix == listOf("core", "any")
}

internal fun KotlinJ2KEmitter.isStdlibDynAnyTraitObject(ty: SynType.TraitObject): Boolean {
    if ("Any" in inspector.declaredTraitsByName || "TypeId" in inspector.declaredTypeNames) return false
    var sawStdlibAny = false
    for (bound in ty.bounds.toList()) {
        when (bound) {
            is TypeParamBound.LifetimeBound -> continue
            is TypeParamBound.Trait -> {
                if (bound.maybe != null) continue
                val segNames =
                    bound.path.segments
                        .toList()
                        .map { it.ident.toString() }
                val last = segNames.lastOrNull() ?: return false
                if (last in setOf("Send", "Sync", "Unpin")) continue
                if (last == "Any" && isStdlibAnyPathSegments(segNames)) {
                    sawStdlibAny = true
                } else {
                    return false
                }
            }
            else -> return false
        }
    }
    return sawStdlibAny
}

internal fun KotlinJ2KEmitter.isSuppressedStdlibModuleAlias(id: String): Boolean {
    val imp = scopeBuilder.resolveModuleBinding(id)?.importedPath ?: return false
    val segs = imp.split('.')
    if (segs.size == 2) {
        val isMappedOrSelfStdCrate =
            (
                segs[0] in STDLIB_CRATE_ROOTS &&
                    (
                        segs[0] in headerContext.crateNamespaces ||
                            headerContext.packageName?.startsWith("io.github.kotlinmania.rustlang.") == true
                    )
            ) ||
                (segs[0] == "crate" && headerContext.packageName?.startsWith("io.github.kotlinmania.rustlang.") == true)
        if (isMappedOrSelfStdCrate && segs[1].firstOrNull()?.isLowerCase() == true && !isKnownFunctionLeafIdent(segs[1])) {
            return true
        }
    }
    if (segs.size == 6 &&
        segs[0] == "io" &&
        segs[1] == "github" &&
        segs[2] == "kotlinmania" &&
        segs[3] == "rustlang" &&
        segs[5].firstOrNull()?.isLowerCase() == true &&
        !isKnownFunctionLeafIdent(segs[5])
    ) {
        return true
    }
    return false
}

private fun KotlinJ2KEmitter.renderValuePositionStaticFnRef(
    prevRaw: String,
    ownerPath: String,
    methodKt: String,
    selfTypeName: String?,
): String {
    val cleanPrev = prevRaw.removePrefix("r#")
    val ownerKt =
        if (cleanPrev == "Self") {
            selfTypeName?.substringBefore('<')?.trim().orEmpty()
        } else {
            rustTypeIdentToKotlin(cleanPrev)
        }
    val isTraitOwner =
        ownerKt.isNotEmpty() &&
            (
                ownerKt in inspector.declaredTraitsByName ||
                    ownerKt in headerContext.crateTraitSupertraits ||
                    ownerKt.endsWith("Ext")
            )
    if (isTraitOwner) {
        return "{ $ownerPath.$methodKt(it) }"
    }
    val isInstanceMethodRef =
        (
            methodKt in inspector.instanceMethodsByType[ownerKt].orEmpty() &&
                methodKt !in inspector.companionMethodsByType[ownerKt].orEmpty()
        ) ||
            (ownerKt == "PoisonError" && methodKt == "intoInner") ||
            (
                methodKt in
                    setOf(
                        "intoInner",
                        "asRef",
                        "asMut",
                        "asSlice",
                        "asBytes",
                        "intoVec",
                        "intoBoxedSlice",
                        "intoString",
                        "toOwned",
                    ) &&
                    ownerKt !in inspector.declaredTypeNames
            )
    if (isInstanceMethodRef) {
        return "{ it.$methodKt() }"
    }
    return "$ownerPath::$methodKt"
}
