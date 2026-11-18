package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.procmacro2.Delimiter
import io.github.kotlinmania.procmacro2.Group
import io.github.kotlinmania.procmacro2.Punct
import io.github.kotlinmania.procmacro2.Spacing
import io.github.kotlinmania.procmacro2.TokenStream
import io.github.kotlinmania.procmacro2.TokenTree
import io.github.kotlinmania.syn.BinOp
import io.github.kotlinmania.syn.Expr
import io.github.kotlinmania.syn.FnArg
import io.github.kotlinmania.syn.GenericParam
import io.github.kotlinmania.syn.Item
import io.github.kotlinmania.syn.Lit
import io.github.kotlinmania.syn.Macro
import io.github.kotlinmania.syn.Member
import io.github.kotlinmania.syn.Path
import io.github.kotlinmania.syn.PathArguments
import io.github.kotlinmania.syn.Signature
import io.github.kotlinmania.syn.SynType
import io.github.kotlinmania.syn.UnOp

/** Maps Rust `core::f*::consts` names to Kotlin `(f32Literal, f64Literal)` pairs. */
private val RUST_FLOAT_MATH_CONSTANTS: Map<String, Pair<String, String>> =
    mapOf(
        "PI" to ("3.1415927f" to "kotlin.math.PI"),
        "TAU" to ("6.2831855f" to "6.283185307179586"),
        "FRAC_PI_2" to ("1.5707964f" to "1.5707963267948966"),
        "FRAC_PI_3" to ("1.0471976f" to "1.0471975511965979"),
        "FRAC_PI_4" to ("0.7853982f" to "0.7853981633974483"),
        "FRAC_PI_6" to ("0.5235988f" to "0.5235987755982989"),
        "FRAC_PI_8" to ("0.3926991f" to "0.39269908169872414"),
        "FRAC_1_PI" to ("0.31830987f" to "0.3183098861837907"),
        "FRAC_2_PI" to ("0.63661975f" to "0.6366197723675814"),
        "FRAC_2_SQRT_PI" to ("1.1283792f" to "1.1283791670955126"),
        "SQRT_2" to ("1.4142135f" to "1.4142135623730951"),
        "FRAC_1_SQRT_2" to ("0.70710677f" to "0.7071067811865476"),
        "E" to ("2.7182817f" to "kotlin.math.E"),
        "LOG2_E" to ("1.442695f" to "1.4426950408889634"),
        "LOG2_10" to ("3.321928f" to "3.321928094887362"),
        "LOG10_E" to ("0.4342945f" to "0.4342944819032518"),
        "LOG10_2" to ("0.30103f" to "0.3010299956639812"),
        "LN_2" to ("0.6931472f" to "0.6931471805599453"),
        "LN_10" to ("2.3025851f" to "2.302585092994046"),
    )

private val INT_BITS_BY_PRIM =
    mapOf(
        "u8" to "8u",
        "i8" to "8u",
        "u16" to "16u",
        "i16" to "16u",
        "u32" to "32u",
        "i32" to "32u",
        "u64" to "64u",
        "i64" to "64u",
        "u128" to "128u",
        "i128" to "128u",
    )

internal fun KotlinJ2KEmitter.renderMacroInvocationAsStmtOrExpr(
    mac: Macro,
    indent: String,
    selfTypeName: String?,
): String {
    CoreIntrinsicLowering.renderConstEvalSelectMacro(mac, this, indent, selfTypeName)?.let { return it }
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
        "const_panic" -> {
            val parts = MacroRulesFileExpander.splitMacroArguments(tokens)
            val firstMsg =
                if (parts.isEmpty()) {
                    "\"const_panic\""
                } else {
                    parseAndRenderTokenStreamAsExpr(TokenStream.fromTokenTrees(parts[0]), indent, selfTypeName)
                }
            "error($firstMsg)"
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
            val outerListTarget = currentExpectedParseTargetType?.removeSuffix("?")?.trim()
            val elemTarget =
                when {
                    outerListTarget != null && outerListTarget.startsWith("MutableList<") && outerListTarget.endsWith(">") ->
                        outerListTarget.removePrefix("MutableList<").dropLast(1).trim()
                    outerListTarget != null && outerListTarget.startsWith("List<") && outerListTarget.endsWith(">") ->
                        outerListTarget.removePrefix("List<").dropLast(1).trim()
                    else -> null
                }
            val savedParseTarget = currentExpectedParseTargetType
            val savedKind = currentExpectedNumericKind
            val args =
                try {
                    if (elemTarget != null) {
                        currentExpectedParseTargetType = elemTarget
                        resolveNumericKind(elemTarget)?.let { currentExpectedNumericKind = it }
                    }
                    parseCommaSeparatedExprsFromTokens(tokens, indent, selfTypeName)
                } finally {
                    currentExpectedParseTargetType = savedParseTarget
                    currentExpectedNumericKind = savedKind
                }
            "mutableListOf(${args.joinToString(", ")})"
        }
        "const_assert" -> {
            val parts = MacroRulesFileExpander.splitMacroArguments(tokens)
            if (parts.isEmpty()) {
                "check(true)"
            } else {
                val cond = parseAndRenderTokenStreamAsExpr(TokenStream.fromTokenTrees(parts[0]), indent, selfTypeName)
                if (parts.size >= 2) {
                    val firstMsg = parseAndRenderTokenStreamAsExpr(TokenStream.fromTokenTrees(parts[1]), indent, selfTypeName)
                    "check($cond) { $firstMsg }"
                } else {
                    "check($cond)"
                }
            }
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
            val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
            if (("core" in headerContext.crateNamespaces || headerContext.packageName?.startsWith(coreNs) == true) &&
                headerContext.packageName != "$coreNs.mem"
            ) {
                CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.mem.$callee")
            }
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
    return when (inferExprNumericKind(operand, selfTypeName)) {
        NumericKind.I8 -> "($recv.toInt().inv()).toByte()"
        NumericKind.I16 -> "($recv.toInt().inv()).toShort()"
        else -> "$recv.inv()"
    }
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
                !last.startsWith("IS_") &&
                !last.startsWith("HAS_") &&
                !last.startsWith("NEEDS_") &&
                !last.startsWith("CAN_") &&
                !last.startsWith("ALLOW_") &&
                inferExprRenderedType(expr, null)?.removeSuffix("?")?.trim() != "Boolean" &&
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
    val nonDecAtom =
        when {
            low64 != null && low64 >= 0x8000000000000000uL -> "${clean}uL"
            low64 != null && low64 >= 0x80000000uL -> "${clean}L"
            else -> clean
        }
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
            NumericKind.F16 -> if (clean.all { it.isDigit() }) "F16.fromFloat($clean.0f)" else "F16.fromDouble(($nonDecAtom).toDouble())"
            NumericKind.F32 -> if (clean.all { it.isDigit() }) "$clean.0f" else "($nonDecAtom).toFloat()"
            NumericKind.F64 -> if (clean.all { it.isDigit() }) "$clean.0" else "($nonDecAtom).toDouble()"
            NumericKind.F128 -> if (clean.all { it.isDigit() }) "F128.fromDouble($clean.0)" else "F128.fromDouble(($nonDecAtom).toDouble())"
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
        "f16" -> if (clean.all { it.isDigit() }) "F16.fromFloat($clean.0f)" else "F16.fromDouble(($nonDecAtom).toDouble())"
        "f32" -> if (clean.all { it.isDigit() }) "$clean.0f" else "($nonDecAtom).toFloat()"
        "f64" -> if (clean.all { it.isDigit() }) "$clean.0" else "($nonDecAtom).toDouble()"
        "f128" -> if (clean.all { it.isDigit() }) "F128.fromDouble($clean.0)" else "F128.fromDouble(($nonDecAtom).toDouble())"
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
        val lastConst =
            path.segments
                .toList()
                .lastOrNull()
                ?.ident
                ?.toString()
                ?.removePrefix("r#")
        if (lastConst in SIZED_TYPE_PROPERTY_NAMES) {
            val segs = path.segments.toList()
            val traitSegName =
                if (qself.position > 0) {
                    segs
                        .getOrNull(qself.position - 1)
                        ?.ident
                        ?.toString()
                        ?.removePrefix("r#")
                } else {
                    null
                }
            val qselfInner = unwrapSynType(qself.ty)
            val qselfSingleKt =
                (qselfInner as? SynType.Path)
                    ?.takeIf { it.qself == null && it.path.segments.size == 1 }
                    ?.path
                    ?.segments
                    ?.first()
                    ?.ident
                    ?.toString()
                    ?.let(::rustTypeIdentToKotlin)
            val isSizedTraitOrTypeParam =
                traitSegName == "SizedTypeProperties" ||
                    (qselfSingleKt != null && qselfSingleKt in activeTypeParamNames) ||
                    (
                        (qself.position == 0 || traitSegName == null) &&
                            lastConst in setOf("IS_ZST", "LAYOUT", "MAX_SLICE_LEN", "ALIGNMENT")
                    )
            if (isSizedTraitOrTypeParam) {
                renderSizedTypePropertyConst(qself.ty, lastConst!!, selfTypeName)?.let { return it }
            }
        }
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
                "SIGN_MASK", "EXP_MASK", "SIG_MASK", "IMPLICIT_BIT", "SIG_TOP_BIT",
                "SIG_BITS", "EXP_BITS", "EXP_SAT", "EXP_BIAS", "EXP_MAX", "EXP_MIN",
                "EXP_MIN_SUBNORM", "TINY_BITS", "MIN_POSITIVE_SUBNORMAL",
                -> {
                    val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
                    CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.$owner")
                    "$owner.$c"
                }
                else -> null
            }
        }
        val fixedBits = INT_BITS_BY_PRIM[prim]
        if (fixedBits != null || prim == "usize" || prim == "isize") {
            if (c == "BITS") {
                return fixedBits ?: if (headerContext.rawAbiMode) "64u" else "32u"
            }
            if (c == "MIN" || c == "MAX") {
                return when (prim) {
                    "usize" ->
                        when {
                            headerContext.rawAbiMode -> "ULong.${c}_VALUE"
                            c == "MIN" -> "0"
                            else -> "Int.MAX_VALUE"
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
        val primKt = rustTypeIdentToKotlin(prim)
        val isPrimitiveAliasOrLower =
            prim.firstOrNull()?.isLowerCase() == true ||
                primKt in inspector.typeAliasTargets ||
                scopedAbiKeysForRenderedType(primKt).any { it in headerContext.crateNumericTypeKinds }
        if (isPrimitiveAliasOrLower && primKt !in activeTypeParamNames && (c == "MIN" || c == "MAX" || c == "BITS")) {
            val kind = resolveNumericKind(primKt)
            val mapped = resolvedPrimOwnerAndBits(kind)
            if (mapped != null) {
                val (ktOwner, bitsStr) = mapped
                if (c == "BITS") return bitsStr
                if (c == "MIN" && (kind == NumericKind.F32 || kind == NumericKind.F64)) {
                    return "(-$ktOwner.MAX_VALUE)"
                }
                return "$ktOwner.${c}_VALUE"
            }
        }
    }
    if (norm.size == 3 && norm[1] == "consts") {
        val mathPair = RUST_FLOAT_MATH_CONSTANTS[norm[2]]
        if (mathPair != null) {
            return when (norm[0]) {
                "f32" -> mathPair.first
                "f16" -> "F16.fromFloat(${mathPair.first})"
                "f64" -> mathPair.second
                "f128" -> "F128.fromDouble(${mathPair.second})"
                else -> null
            }
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
    if (!asCallee && rawSegments.size >= 2) {
        val constIdent = rawSegNames.last()
        if (constIdent in SIZED_TYPE_PROPERTY_NAMES) {
            val headKt = rustTypeIdentToKotlin(rawSegNames[0])
            val isSizedOwner =
                headKt in activeTypeParamNames ||
                    (
                        rawSegments.size == 2 &&
                            constIdent in setOf("IS_ZST", "LAYOUT", "MAX_SLICE_LEN", "ALIGNMENT") &&
                            constIdent !in inspector.declaredConstNames &&
                            headKt !in inspector.unitStructNames &&
                            (rawSegNames[0] == "Self" || headKt in inspector.declaredTypeNames || !isScreamingSnakeIdent(rawSegNames[0]))
                    )
            if (isSizedOwner) {
                val ownerSegIdent =
                    if (rawSegments.size == 2) rawSegments[0].ident else rawSegments[rawSegments.lastIndex - 1].ident
                val ownerSyn = SynType.Path(null, Path.from(ownerSegIdent))
                renderSizedTypePropertyConst(ownerSyn, constIdent, selfTypeName)?.let { return it }
            }
        }
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
    if (rawSegments.size == 2 &&
        rawSegments[0].arguments is PathArguments.None &&
        rawSegments[1].arguments is PathArguments.None
    ) {
        val collidedRename = inspector.collidingInlineForeignFnRenames[rawSegNames[0] to rawSegNames[1]]
        if (collidedRename != null) {
            return if (asCallee) collidedRename else "::$collidedRename"
        }
    }

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
            scopeBuilder.resolveLocalKotlinName("self") ?: "this"
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
            firstRaw != "f16" &&
            firstRaw != "f128" &&
            (firstRaw in PRIMITIVE_TYPE_NAMES || resolveNumericKind(firstKtType) != null) &&
            (secondRaw == "MAX" || secondRaw == "MIN")
        ) {
            if (firstRaw == "u128") usesU128 = true
            if (firstRaw == "i128") usesI128 = true
            val kind = resolveNumericKind(firstKtType)
            val boundOwner = if (firstRaw in PRIMITIVE_TYPE_NAMES) kotlinBuiltinTypeName(firstKtType) else firstKtType
            if (secondRaw == "MIN" && (kind == NumericKind.F32 || kind == NumericKind.F64)) {
                return "(-$boundOwner.MAX_VALUE)"
            }
            return "$boundOwner.${secondRaw}_VALUE"
        }
        if (filtered[1].arguments is PathArguments.None && (firstRaw == "u128" || firstRaw == "i128" || firstRaw == "f16" || firstRaw == "f128")) {
            val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
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
                    "f16" -> {
                        CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.F16")
                        "F16"
                    }
                    else -> {
                        CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.F128")
                        "F128"
                    }
                }
            val bitsCount = if (firstRaw == "f16") "16u" else "128u"
            when (cleanSecond) {
                "from_be_bytes" -> return if (asCallee) "$ownerPrim.fromBeBytes" else "$ownerPrim::fromBeBytes"
                "from_le_bytes" -> return if (asCallee) "$ownerPrim.fromLeBytes" else "$ownerPrim::fromLeBytes"
                "from_ne_bytes" -> {
                    val fn = if (activeCfgProfile.targetEndian == "big") "fromBeBytes" else "fromLeBytes"
                    return if (asCallee) "$ownerPrim.$fn" else "$ownerPrim::$fn"
                }
                "from_bits" -> if (firstRaw == "f16" || firstRaw == "f128") return if (asCallee) "$ownerPrim.fromBits" else "$ownerPrim::fromBits"
                "to_bits" -> if (firstRaw == "f16" || firstRaw == "f128") return if (asCallee) "$ownerPrim.toBits" else "$ownerPrim::toBits"
                "ZERO" -> return "$ownerPrim.ZERO"
                "BITS" -> return bitsCount
            }
        }
        val selfBaseLookup = selfTypeName?.substringBefore('<')?.trim()
        val firstWitnessKey = if (firstRaw == "Self" && selfBaseLookup != null) selfBaseLookup else rustTypeIdentToKotlin(firstRaw)
        currentTypeParamWitnessCalls[firstWitnessKey to cleanSecond]?.let { return it }
        if (firstRaw == "Default" && cleanSecond == "default") {
            val targetTp = currentExpectedParseTargetType?.trim()
            if (targetTp != null) {
                currentTypeParamWitnessCalls[targetTp to "default"]?.let { return it }
            }
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
                        if (lastRaw.first().isUpperCase() &&
                            !isScreamingSnakeIdent(lastRaw) &&
                            (boundHead in inspector.unitStructNames || isGenericUnitEnumVariant(boundHead))
                        ) {
                            return "$boundHead()"
                        }
                        if (lastRaw.first().isUpperCase() && !isScreamingSnakeIdent(lastRaw)) {
                            resolveStdlibUnitStructValueExpr(rawSegNames)?.let { return it }
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
                val fullParts = (listOf(boundHead) + renderedTail).toMutableList()
                if (fullParts.size >= 2) {
                    val lastRaw = tailSegs.last().ident.toString()
                    val prevRaw = if (tailSegs.size >= 2) tailSegs[tailSegs.size - 2].ident.toString() else headSeg.ident.toString()
                    if (isKnownEnumVariantPair(prevRaw, lastRaw, selfTypeName)) {
                        val varKt = rustTypeIdentToKotlin(lastRaw)
                        val prevRendered = fullParts[fullParts.lastIndex]
                        val typeArgSuffix = prevRendered.indexOf('<').let { if (it >= 0) prevRendered.substring(it) else "" }
                        fullParts[fullParts.lastIndex] = varKt + typeArgSuffix
                        if (!asCallee && prevRaw.first().isUpperCase()) {
                            val enumKt =
                                if (prevRaw == "Self") {
                                    selfTypeName?.substringBefore('<')?.trim().orEmpty()
                                } else {
                                    rustTypeIdentToKotlin(prevRaw)
                                }
                            val arity = inspector.tupleEnumVariantArity[enumKt to varKt]
                            if (arity != null && arity > 0) {
                                return renderTupleVariantConstructorLambda(fullParts.joinToString("."), arity)
                            }
                            if (enumKt.isNotEmpty() && isGenericUnitEnumVariant("$enumKt.$varKt")) {
                                return "${fullParts.joinToString(".")}()"
                            }
                        }
                        return fullParts.joinToString(".")
                    }
                }
                if (!asCallee && fullParts.size >= 2) {
                    val lastRaw = tailSegs.last().ident.toString()
                    val prevRaw = if (tailSegs.size >= 2) tailSegs[tailSegs.size - 2].ident.toString() else headSeg.ident.toString()
                    if (prevRaw.first().isUpperCase() && lastRaw.first().isUpperCase() && !isScreamingSnakeIdent(lastRaw)) {
                        val enumKt = rustTypeIdentToKotlin(prevRaw)
                        val varKt = rustTypeIdentToKotlin(lastRaw)
                        val arity = inspector.tupleEnumVariantArity[enumKt to varKt]
                        if (arity != null && arity > 0) {
                            return renderTupleVariantConstructorLambda(fullParts.joinToString("."), arity)
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
            val rawKt = rustTypeIdentToKotlin(singleRaw)
            val singleTypeIdent = samePackageUseRenames[rawKt] ?: rawKt
            if (singleTypeIdent in inspector.unitStructNames || isGenericUnitEnumVariant(singleTypeIdent)) {
                return "$singleTypeIdent()"
            }
            if (!asCallee) {
                resolveStdlibUnitStructValueExpr(listOf(singleRaw))?.let { return it }
            }
        }
        if (cleanSingle != "self" &&
            singleRaw.first().isLowerCase() &&
            cleanSingle !in activeGuardBindingSubstitutions &&
            scopeBuilder.resolveLocalKotlinName(cleanSingle) == null &&
            cleanSingle in inspector.declaredTopLevelFnRawNames &&
            cleanSingle !in inspector.declaredConstNames
        ) {
            val fnKt = inspector.collidingTopLevelForeignFnRenames[cleanSingle] ?: rustFunctionIdentToKotlin(singleRaw)
            return "::$fnKt"
        }
        if (!asCallee &&
            cleanSingle == "drop" &&
            scopeBuilder.resolveLocalKotlinName("drop") == null &&
            "drop" !in inspector.declaredTopLevelFnRawNames &&
            "drop" !in inspector.declaredConstNames &&
            (
                "core" in headerContext.crateNamespaces ||
                    headerContext.packageName?.startsWith(CoreIntrinsicLowering.resolveCoreNamespace(headerContext)) == true
            )
        ) {
            val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
            if (headerContext.packageName != "$coreNs.mem") {
                CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.mem.drop")
            }
            return "{ drop(it) }"
        }
    }

    val isSingleSegment = (rawSegments.size == 1)
    if (filtered.size >= 2 && !asCallee && filtered
            .last()
            .ident
            .toString()
            .removePrefix("r#") == "ZERO"
    ) {
        val ownerRaw = filtered[filtered.size - 2].ident.toString().removePrefix("r#")
        val simdSpec = parseSimdLaneVectorSpec(ownerRaw, this)
        if (simdSpec != null) {
            registerSimdWrapperImport(this, simdSpec.wrapperName)
            val zeroLit = simdZeroLiteralExpr(simdSpec.elemTy, simdSpec.elemKind, this)
            return "${simdSpec.wrapperName}<${simdSpec.elemTy}>(List(${simdSpec.lanes}) { $zeroLit })"
        }
    }
    if (filtered.size == 2) {
        val ownerRaw = filtered[0].ident.toString()
        val memberRaw = filtered[1].ident.toString()
        val cleanMember = memberRaw.removePrefix("r#")
        val ownerKt =
            if (ownerRaw == "Self") {
                selfTypeName?.substringBefore('<')?.trim()
            } else {
                rustTypeIdentToKotlin(ownerRaw)
            }
        if (ownerKt != null &&
            ownerKt in activeTypeParamNames &&
            cleanMember.firstOrNull()?.isLowerCase() == true &&
            !isScreamingSnakeIdent(memberRaw)
        ) {
            val memberRendered =
                renderPathSegmentInExpr(
                    rawIdent = memberRaw,
                    arguments = filtered[1].arguments,
                    selfTypeName = selfTypeName,
                    isLast = true,
                    isSingleSegment = false,
                    asCallee = asCallee,
                )
            return if (asCallee) memberRendered else "::$memberRendered"
        }
    }
    val parts =
        filtered
            .mapIndexed { idx, seg ->
                renderPathSegmentInExpr(
                    rawIdent = seg.ident.toString(),
                    arguments = seg.arguments,
                    selfTypeName = selfTypeName,
                    isLast = (idx == filtered.lastIndex),
                    isSingleSegment = isSingleSegment,
                    asCallee = asCallee,
                )
            }.toMutableList()
    if (parts.size >= 2 && filtered.size >= 2) {
        val ownerIdx = parts.size - 2
        val ownerRaw = filtered[ownerIdx].ident.toString().removePrefix("r#")
        if (ownerRaw != "Self" && ownerRaw !in PRIMITIVE_TYPE_NAMES) {
            val ownerKt = rustTypeIdentToKotlin(ownerRaw)
            if (ownerKt in inspector.typeAliasTargets) {
                val resolvedOwner = resolveTypeAliasOwnerName(ownerKt)
                if (resolvedOwner != ownerKt &&
                    (
                        resolvedOwner in inspector.declaredTypeNames ||
                            resolvedOwner in headerContext.crateSymbolSubpackages ||
                            headerContext.externalCrateSymbolPackages.values.any { resolvedOwner in it }
                    )
                ) {
                    if (resolvedOwner !in inspector.declaredTypeNames && resolvedOwner !in importedSimpleNameToPkg) {
                        val sub = resolveBestSubpackageForSymbol(resolvedOwner)
                        val ns = currentNamespacePrefix()
                        if (sub != null && ns != null) {
                            val pkg = if (sub.isEmpty()) ns else "$ns.$sub"
                            if (pkg != headerContext.packageName) {
                                importedSimpleNameToPkg[resolvedOwner] = pkg
                                extraTypePathImports += "import $pkg.$resolvedOwner"
                            }
                        }
                    }
                    parts[ownerIdx] = resolvedOwner
                }
            }
        }
    }
    if (parts.size >= 2) {
        val lastRaw = filtered.last().ident.toString()
        val prevRaw = filtered[filtered.size - 2].ident.toString()
        if (prevRaw != "Self" &&
            prevRaw.first().isUpperCase() &&
            !isScreamingSnakeIdent(prevRaw)
        ) {
            val ownerKt = rustTypeIdentToKotlin(prevRaw)
            if (ownerKt !in inspector.declaredTypeNames &&
                ownerKt !in activeTypeParamNames &&
                ownerKt !in importedSimpleNameToPkg &&
                ownerKt !in KOTLIN_STDLIB_SHADOWED_IMPORTS
            ) {
                val sub = resolveBestSubpackageForSymbol(ownerKt)
                val ns = currentNamespacePrefix()
                if (sub != null && ns != null) {
                    val pkg = if (sub.isEmpty()) ns else "$ns.$sub"
                    if (pkg != headerContext.packageName) {
                        importedSimpleNameToPkg[ownerKt] = pkg
                        extraTypePathImports += "import $pkg.$ownerKt"
                    }
                }
            }
        }
        if (isKnownEnumVariantPair(prevRaw, lastRaw, selfTypeName)) {
            val varKt = rustTypeIdentToKotlin(lastRaw)
            val prevRendered = parts[parts.lastIndex]
            val typeArgSuffix = prevRendered.indexOf('<').let { if (it >= 0) prevRendered.substring(it) else "" }
            parts[parts.lastIndex] = varKt + typeArgSuffix
            if (!asCallee && prevRaw.first().isUpperCase()) {
                val enumKt = if (prevRaw == "Self") selfTypeName?.substringBefore('<')?.trim().orEmpty() else rustTypeIdentToKotlin(prevRaw)
                val arity = inspector.tupleEnumVariantArity[enumKt to varKt]
                if (arity != null && arity > 0) {
                    return renderTupleVariantConstructorLambda(parts.joinToString("."), arity)
                }
                if (enumKt.isNotEmpty() && isGenericUnitEnumVariant("$enumKt.$varKt")) {
                    return "${parts.joinToString(".")}()"
                }
            }
            return parts.joinToString(".")
        }
    }
    if (!asCallee && parts.size >= 2) {
        val lastRaw = filtered.last().ident.toString()
        val prevRaw = filtered[filtered.size - 2].ident.toString()
        if (prevRaw.first().isUpperCase() && lastRaw.first().isUpperCase() && !isScreamingSnakeIdent(lastRaw)) {
            val enumKt = if (prevRaw == "Self") selfTypeName?.substringBefore('<')?.trim().orEmpty() else rustTypeIdentToKotlin(prevRaw)
            val varKt = rustTypeIdentToKotlin(lastRaw)
            val arity = inspector.tupleEnumVariantArity[enumKt to varKt]
            if (arity != null && arity > 0) {
                return renderTupleVariantConstructorLambda(parts.joinToString("."), arity)
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
            rawIdent == "self" -> scopeBuilder.resolveLocalKotlinName("self") ?: "this"
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
                    inspector.collidingTopLevelForeignFnRenames[cleanIdent] ?: rustFunctionIdentToKotlin(rawIdent)
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
            rawIdent.first().isUpperCase() -> {
                val rawKt = rustTypeIdentToKotlin(rawIdent)
                val ktSym = if (isSingleSegment) (samePackageUseRenames[rawKt] ?: rawKt) else rawKt
                if (isSingleSegment && ktSym in activeTypeParamNames && ktSym in inspector.enumVariantSimpleNames) {
                    val selfBase = selfTypeName?.substringBefore('<')?.substringAfterLast('.')?.trim()
                    val ownerEnum =
                        selfBase?.takeIf { "$it.$ktSym" in inspector.enumVariantFieldTypes || ktSym in inspector.enumVariantSimpleNames }
                            ?: inspector.enumVariantFieldTypes.keys
                                .firstOrNull { it.endsWith(".$ktSym") && '.' in it }
                                ?.substringBeforeLast('.')
                    if (ownerEnum != null && ownerEnum !in activeTypeParamNames) "$ownerEnum.$ktSym" else ktSym
                } else {
                    ktSym
                }
            }
            !isLast &&
                rustTypeIdentToKotlin(rawIdent) in inspector.declaredTypeNames &&
                rustTypeIdentToKotlin(rawIdent) !in inspector.typeAliasTargets ->
                rustTypeIdentToKotlin(rawIdent)
            isSingleSegment &&
                isLast &&
                asCallee &&
                cleanIdent !in inspector.declaredTopLevelFnRawNames &&
                scopeBuilder.resolveLocalKotlinName(cleanIdent) == null &&
                rustTypeIdentToKotlin(rawIdent) in inspector.declaredTypeNames &&
                rustTypeIdentToKotlin(rawIdent) !in inspector.typeAliasTargets ->
                rustTypeIdentToKotlin(rawIdent)
            !isLast && importedTypeKotlinName != null -> importedTypeKotlinName
            isSingleSegment &&
                isLast &&
                asCallee &&
                cleanIdent !in inspector.declaredTopLevelFnRawNames &&
                scopeBuilder.resolveLocalKotlinName(cleanIdent) == null &&
                importedTypeKotlinName != null ->
                importedTypeKotlinName
            !isLast && scopeBuilder.isImportedModuleInScope(rawIdent) ->
                scopeBuilder.resolveModuleBinding(rawIdent)?.kotlinName ?: rustModuleIdentToKotlin(rawIdent)
            (!isLast || (asCallee && isSingleSegment)) &&
                rawIdent.first().isLowerCase() &&
                rawIdent !in PRIMITIVE_TYPE_NAMES &&
                rawIdent != "self" &&
                !scopeBuilder.isImportedModuleInScope(rawIdent) &&
                scopeBuilder.resolveLocalKotlinName(cleanIdent) == null &&
                cleanIdent !in inspector.declaredTopLevelFnRawNames &&
                rustTypeIdentToKotlin(rawIdent).let { candKt ->
                    candKt in inspector.declaredTypeNames ||
                        candKt in importedSimpleNameToPkg ||
                        candKt in headerContext.crateSymbolSubpackages
                } -> {
                val candKt = rustTypeIdentToKotlin(rawIdent)
                if (candKt !in inspector.declaredTypeNames && candKt !in importedSimpleNameToPkg) {
                    val bestSub = resolveBestSubpackageForSymbol(candKt)
                    val ns = currentNamespacePrefix()
                    if (bestSub != null && ns != null) {
                        val pkg = if (bestSub.isEmpty()) ns else "$ns.$bestSub"
                        if (pkg != headerContext.packageName) {
                            importedSimpleNameToPkg[candKt] = pkg
                            extraTypePathImports += "import $pkg.$candKt"
                        }
                    }
                }
                candKt
            }
            isSingleSegment -> {
                if (asCallee && activeLocalForeignFnRenames[cleanIdent] != null) {
                    activeLocalForeignFnRenames.getValue(cleanIdent)
                } else if (arguments is PathArguments.None && cleanIdent in activeGuardBindingSubstitutions) {
                    activeGuardBindingSubstitutions.getValue(cleanIdent)
                } else if (scopeBuilder.resolveLocalKotlinName(cleanIdent) != null) {
                    scopeBuilder.resolveLocalKotlinName(cleanIdent)!!
                } else {
                    inspector.collidingTopLevelForeignFnRenames[cleanIdent] ?: rustValueIdentToKotlin(rawIdent)
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

private fun KotlinJ2KEmitter.renderValuePositionStaticFnRef(
    prevRaw: String,
    ownerPath: String,
    methodKt: String,
    selfTypeName: String?,
): String {
    val cleanPrev = prevRaw.removePrefix("r#")
    val baseMethodKt = methodKt.substringBefore('<').trim()
    val typeArgContent =
        if ('<' in methodKt && methodKt.endsWith(">")) {
            methodKt.substringAfter('<').removeSuffix(">").trim()
        } else {
            null
        }
    if ((cleanPrev == "str" || cleanPrev == "String") && baseMethodKt == "parse") {
        val strParseMethod = typeArgContent?.let { STR_PRIMITIVE_PARSE_METHODS[it] }
        if (strParseMethod != null) {
            return if (usesTaggedCoreResult()) {
                val res = sourceResultName()
                "{ (it.$strParseMethod?.let { v -> $res.Ok(v) } ?: $res.Err(\"failed to parse $typeArgContent\")) }"
            } else {
                "{ (it.$strParseMethod?.let { v -> Result.success(v) } ?: Result.failure(IllegalArgumentException(\"failed to parse $typeArgContent\"))) }"
            }
        }
        return "{ it.$methodKt() }"
    }
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
            baseMethodKt in inspector.instanceMethodsByType[ownerKt].orEmpty() &&
                baseMethodKt !in inspector.companionMethodsByType[ownerKt].orEmpty()
        ) ||
            (ownerKt == "PoisonError" && baseMethodKt == "intoInner") ||
            (
                baseMethodKt in
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
    if ('<' in methodKt) {
        return "{ $ownerPath.$methodKt(it) }"
    }
    return "$ownerPath::$methodKt"
}

private fun KotlinJ2KEmitter.resolvedPrimOwnerAndBits(kind: NumericKind?): Pair<String, String>? =
    when (kind) {
        NumericKind.I8 -> "Byte" to "8u"
        NumericKind.U8 -> "UByte" to "8u"
        NumericKind.I16 -> "Short" to "16u"
        NumericKind.U16 -> "UShort" to "16u"
        NumericKind.I32 -> "Int" to "32u"
        NumericKind.U32 -> "UInt" to "32u"
        NumericKind.I64 -> "Long" to "64u"
        NumericKind.U64 -> "ULong" to "64u"
        NumericKind.I128 -> {
            usesI128 = true
            "I128" to "128u"
        }
        NumericKind.U128 -> {
            usesU128 = true
            "U128" to "128u"
        }
        NumericKind.F32 -> "Float" to "32u"
        NumericKind.F64 -> "Double" to "64u"
        else -> null
    }

private fun renderTupleVariantConstructorLambda(ctorRef: String, arity: Int): String {
    if (arity == 1) return "{ $ctorRef(it) }"
    val pNames = (0 until arity).joinToString(", ") { "p$it" }
    return "{ $pNames -> $ctorRef($pNames) }"
}

private fun KotlinJ2KEmitter.isKnownEnumVariantPair(
    prevRaw: String,
    lastRaw: String,
    selfTypeName: String?,
): Boolean {
    val cleanPrev = prevRaw.removePrefix("r#")
    val cleanLast = lastRaw.removePrefix("r#")
    if (cleanPrev.isEmpty() || cleanLast.isEmpty() || cleanLast == "new" || cleanLast == "default") return false
    val enumKt =
        if (cleanPrev == "Self") {
            selfTypeName
                ?.substringBefore('<')
                ?.substringAfterLast('.')
                ?.trim()
                .orEmpty()
        } else {
            rustTypeIdentToKotlin(cleanPrev)
        }
    if (enumKt.isEmpty()) return false
    if (enumKt == "Feature" &&
        cleanLast !in setOf("to_str", "from_str") &&
        !isScreamingSnakeIdent(cleanLast) &&
        (
            headerContext.packageName?.contains(".stddetect.") == true ||
                importedSimpleNameToPkg["Feature"]?.contains(".detect.arch.") == true
        )
    ) {
        return true
    }
    val varKt = rustTypeIdentToKotlin(cleanLast)
    if (varKt.isEmpty()) return false
    val directKey = "$enumKt.$varKt"
    if (directKey in headerContext.crateEnumVariantTypeArity ||
        directKey in headerContext.externalEnumVariantTypeArity ||
        directKey in inspector.enumVariantFieldTypes ||
        (enumKt to varKt) in inspector.tupleEnumVariantArity ||
        (enumKt in inspector.enumTypeNames && varKt in inspector.enumVariantSimpleNames)
    ) {
        return true
    }
    importedSimpleNameToPkg[enumKt]?.let { pkg ->
        val ns = currentNamespacePrefix()
        val sub = if (ns != null && pkg.startsWith("$ns.")) pkg.removePrefix("$ns.") else pkg.substringAfterLast('.')
        if (sub.isNotEmpty() && "$sub.$directKey" in headerContext.crateEnumVariantTypeArity) {
            return true
        }
    }
    return scopedAbiKeysForRenderedType(enumKt).any { "$it.$varKt" in headerContext.crateEnumVariantTypeArity }
}
