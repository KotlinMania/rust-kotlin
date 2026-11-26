package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.io.files.SystemFileSystem
import io.github.kotlinmania.io.files.readBytes
import io.github.kotlinmania.procmacro2.Delimiter
import io.github.kotlinmania.procmacro2.Punct
import io.github.kotlinmania.procmacro2.Spacing
import io.github.kotlinmania.procmacro2.TokenStream
import io.github.kotlinmania.procmacro2.TokenTree
import io.github.kotlinmania.syn.BinOp
import io.github.kotlinmania.syn.Expr
import io.github.kotlinmania.syn.ExprParse
import io.github.kotlinmania.syn.FnArg
import io.github.kotlinmania.syn.GenericParam
import io.github.kotlinmania.syn.Item
import io.github.kotlinmania.syn.Lit
import io.github.kotlinmania.syn.Macro
import io.github.kotlinmania.syn.Member
import io.github.kotlinmania.syn.Path
import io.github.kotlinmania.syn.PathArguments
import io.github.kotlinmania.syn.PathSegment
import io.github.kotlinmania.syn.PathSegmentList
import io.github.kotlinmania.syn.Signature
import io.github.kotlinmania.syn.SynType
import io.github.kotlinmania.syn.UnOp
import io.github.kotlinmania.syn.parse2
import io.github.kotlinmania.syn.token.PathSep
import kotlin.io.encoding.Base64
import io.github.kotlinmania.io.files.Path as KmPath

private val RUST_FLOAT_MATH_CONSTANTS: Map<String, Pair<String, String>>
    get() = YamlRuleCatalogs.PathsConstsAndCAbi.rustFloatMathConstants

private val INT_BITS_BY_PRIM: Map<String, String>
    get() = YamlRuleCatalogs.PathsConstsAndCAbi.intBitsByPrim

private val F16_F128_ASSOC_CONSTS: Set<String>
    get() = YamlRuleCatalogs.PathsConstsAndCAbi.f16F128AssocConsts

private val BOOLEAN_CAST_SOURCE_METHODS: Set<String>
    get() = YamlRuleCatalogs.PathsConstsAndCAbi.booleanCastSourceMethods

private val VALUE_POS_INSTANCE_CONV_METHODS: Set<String>
    get() = YamlRuleCatalogs.PathsConstsAndCAbi.valuePosInstanceConvMethods

private val SELF_TYPE_TO_RUST_PRIM: Map<String, String>
    get() = YamlRuleCatalogs.PathsConstsAndCAbi.selfTypeToRustPrim

internal inline fun <T> KotlinJ2KEmitter.withExpectedTargetAndKind(
    targetType: String?,
    numericKind: NumericKind?,
    block: () -> T,
): T {
    val savedTarget = currentExpectedParseTargetType
    val savedKind = currentExpectedNumericKind
    currentExpectedParseTargetType = targetType
    currentExpectedNumericKind = numericKind
    return try {
        block()
    } finally {
        currentExpectedParseTargetType = savedTarget
        currentExpectedNumericKind = savedKind
    }
}

internal fun KotlinJ2KEmitter.resolveCrateSubpackageTargetPkg(symbol: String): String? {
    val subpkg = resolveBestSubpackageForSymbol(symbol) ?: return null
    val ns = currentNamespacePrefix() ?: return null
    return if (subpkg.isEmpty()) ns else "$ns.$subpkg"
}

internal fun KotlinJ2KEmitter.registerCrateSubpackageSymbolImport(
    symbol: String,
    targetPkg: String,
    qualifyOnCollision: Boolean = false,
): String {
    if (targetPkg.isEmpty() || targetPkg == headerContext.packageName) return symbol
    val collidedPkg = importedSimpleNameToPkg[symbol]
    if (qualifyOnCollision && collidedPkg != null && collidedPkg != targetPkg) {
        return "$targetPkg.$symbol"
    }
    importedSimpleNameToPkg[symbol] = targetPkg
    extraTypePathImports += "import $targetPkg.$symbol"
    return symbol
}

internal fun KotlinJ2KEmitter.ensureCrateSubpackageTypeImport(typeName: String) {
    if (typeName in inspector.declaredTypeNames || typeName in importedSimpleNameToPkg) return
    val pkg = resolveCrateSubpackageTargetPkg(typeName) ?: return
    registerCrateSubpackageSymbolImport(typeName, pkg)
}

internal fun KotlinJ2KEmitter.renderMacroInvocationAsStmtOrExpr(
    mac: Macro,
    indent: String,
    selfTypeName: String?,
): String {
    CoreIntrinsicLowering.renderConstEvalSelectMacro(mac, this, indent, selfTypeName)?.let { return it }
    val macroName = mac.path.lastIdentText() ?: "macroCall"
    val ctx =
        MacroInvocationContext(
            emitter = this,
            mac = mac,
            macroName = macroName,
            tokens = mac.tokens.clone(),
            indent = indent,
            selfTypeName = selfTypeName,
        )
    val rule = BUILTIN_MACRO_CALL_RULES_BY_NAME[macroName]
    return if (rule != null) rule.render(ctx) else ctx.renderUserOrCrateMacroCall()
}

private class MacroInvocationContext(
    val emitter: KotlinJ2KEmitter,
    val mac: Macro,
    val macroName: String,
    val tokens: TokenStream,
    val indent: String,
    val selfTypeName: String?,
) {
    fun splitArgs(): List<List<TokenTree>> =
        RustTokenCursor.splitMacroArguments(tokens)

    fun renderTreesAsExpr(trees: List<TokenTree>): String =
        emitter.parseAndRenderTokenStreamAsExpr(TokenStream.fromTokenTrees(trees), indent, selfTypeName)

    fun renderStreamAsExpr(stream: TokenStream): String =
        emitter.parseAndRenderTokenStreamAsExpr(stream, indent, selfTypeName)

    fun renderCommaExprs(): List<String> =
        splitArgs()
            .map(::renderTreesAsExpr)
            .filter { it.isNotBlank() }

    fun renderPlainFnCallFallback(callee: String = rustFunctionIdentToKotlin(macroName)): String =
        "$callee(${renderCommaExprs().joinToString(", ")})"

    fun joinCommaSeparatedTail(parts: List<List<TokenTree>>, fromIndex: Int = 1): TokenStream {
        val joined = mutableListOf<TokenTree>()
        for (i in fromIndex until parts.size) {
            if (i > fromIndex) {
                joined += TokenTree.Punct(Punct(',', Spacing.Alone))
            }
            joined.addAll(parts[i])
        }
        return TokenStream.fromTokenTrees(joined)
    }

    fun renderFormat(stream: TokenStream = tokens): String {
        val parts = RustTokenCursor.splitMacroArguments(stream)
        if (parts.isEmpty()) return "\"\""
        val rawFormatBody = CompileTimeMacroStringEvaluator(parts[0]).evaluate()
        if (rawFormatBody != null) {
            val positional = mutableListOf<String>()
            val named = linkedMapOf<String, String>()
            for (i in 1 until parts.size) {
                val argTrees = parts[i]
                val argCursor = RustTokenCursor(argTrees)
                if (argCursor.size >= 3 && argCursor.identAt(0) != null && argCursor.punctCharAt(1) == '=') {
                    val key = argCursor.identAt(0)!!
                    val renderedVal = renderTreesAsExpr(argTrees.drop(2))
                    named[key] = renderedVal
                    positional += renderedVal
                } else {
                    positional += renderTreesAsExpr(argTrees)
                }
            }
            return renderRustFormatAsKotlinTemplate(rawFormatBody, positional, named)
        }
        return parts.joinToString(" + ", transform = ::renderTreesAsExpr)
    }

    fun renderUserOrCrateMacroCall(): String {
        val segStrings = mac.path.identStrings()
        val args = renderCommaExprs()
        val fnName = rustFunctionIdentToKotlin(macroName)
        val rawMacro = macroName.removePrefix("r#")
        val callee =
            when {
                segStrings.size >= 2 -> {
                    val crossTarget = emitter.resolveCrossModulePathTarget(segStrings)
                    val firstModRaw = segStrings.first().removePrefix("r#")
                    when {
                        crossTarget != null && crossTarget.second.isEmpty() ->
                            emitter.bindOrQualifyResolvedCrossSymbol(crossTarget.first)
                        emitter.scopeBuilder.isImportedModuleInScope(firstModRaw) -> {
                            val modKt =
                                emitter.scopeBuilder.resolveModuleBinding(firstModRaw)?.kotlinName
                                    ?: rustModuleIdentToKotlin(segStrings.first())
                            "$modKt.$fnName"
                        }
                        else -> fnName
                    }
                }
                rawMacro !in emitter.inspector.declaredTopLevelFnRawNames &&
                    fnName !in KOTLIN_STDLIB_SHADOWED_IMPORTS &&
                    !(fnName.length == 1 && fnName[0] in 'A'..'Z') &&
                    fnName !in emitter.inspector.enumVariantSimpleNames &&
                    fnName !in emitter.inspector.importedEnumVariantSimpleNames &&
                    emitter.scopeBuilder.resolveLocalKotlinName(rawMacro) == null &&
                    emitter.headerContext.crateSymbolSubpackages.isNotEmpty() ->
                    emitter.resolveCrateSubpackageTargetPkg(fnName)?.let { targetPkg ->
                        emitter.bindOrQualifyResolvedCrossSymbol("$targetPkg.$fnName")
                    } ?: fnName
                else -> fnName
            }
        return "$callee(${args.joinToString(", ")})"
    }
}

private abstract class BuiltinMacroCallRule(
    val names: Set<String>,
) {
    constructor(vararg names: String) : this(names.toSet())

    abstract fun render(ctx: MacroInvocationContext): String
}

private object TriMacroRule : BuiltinMacroCallRule("tri") {
    override fun render(ctx: MacroInvocationContext): String =
        ctx.emitter.renderSourceResultTry(ctx.renderStreamAsExpr(ctx.tokens), "return")
}

private object FormatMacroRule : BuiltinMacroCallRule("format", "format_args") {
    override fun render(ctx: MacroInvocationContext): String =
        ctx.renderFormat()
}

private object WriteMacroRule : BuiltinMacroCallRule("write", "writeln") {
    override fun render(ctx: MacroInvocationContext): String {
        val parts = ctx.splitArgs()
        if (parts.isEmpty()) return "${rustFunctionIdentToKotlin(ctx.macroName)}()"
        val target = ctx.renderTreesAsExpr(parts[0])
        val isWriteln = ctx.macroName == "writeln"
        if (parts.size == 1) {
            return if (isWriteln) "$target.appendLine()" else "$target.append(\"\")"
        }
        val appendMethod = if (isWriteln) "appendLine" else "append"
        return "$target.$appendMethod(${ctx.renderFormat(ctx.joinCommaSeparatedTail(parts))})"
    }
}

private object PrintMacroRule : BuiltinMacroCallRule("println", "eprintln", "print", "eprint") {
    override fun render(ctx: MacroInvocationContext): String {
        val fn = if (ctx.macroName.endsWith("ln")) "println" else "print"
        return "$fn(${ctx.renderFormat()})"
    }
}

private object PanicMacroRule : BuiltinMacroCallRule("panic", "unreachable", "unimplemented", "todo", "const_panic") {
    override fun render(ctx: MacroInvocationContext): String {
        if (ctx.macroName == "const_panic") {
            val parts = ctx.splitArgs()
            val firstMsg = if (parts.isEmpty()) "\"const_panic\"" else ctx.renderTreesAsExpr(parts[0])
            return "error($firstMsg)"
        }
        val msg =
            if (ctx.tokens.isEmpty()) {
                val safeLabel = if (ctx.macroName == "unimplemented" || ctx.macroName == "todo") "unsupported" else ctx.macroName
                "\"$safeLabel\""
            } else {
                ctx.renderFormat()
            }
        return "error($msg)"
    }
}

private object ConstErrorMacroRule : BuiltinMacroCallRule("const_error") {
    override fun render(ctx: MacroInvocationContext): String {
        val parts = ctx.splitArgs()
        val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(ctx.emitter.headerContext)
        val errType = ctx.emitter.bindOrQualifyResolvedCrossSymbol("$coreNs.io.error.Error")
        val msgType = ctx.emitter.bindOrQualifyResolvedCrossSymbol("$coreNs.io.error.SimpleMessage")
        return if (parts.size >= 2) {
            val kindExpr = ctx.renderTreesAsExpr(parts[0])
            val msgExpr = ctx.renderTreesAsExpr(parts[1])
            "$errType.fromStaticMessage($msgType(kind = $kindExpr, message = $msgExpr))"
        } else {
            val kindType = ctx.emitter.bindOrQualifyResolvedCrossSymbol("$coreNs.io.error.ErrorKind")
            "$errType.fromStaticMessage($msgType(kind = $kindType.Other, message = \"const_error\"))"
        }
    }
}

private object VecMacroRule : BuiltinMacroCallRule("vec") {
    override fun render(ctx: MacroInvocationContext): String {
        val emitter = ctx.emitter
        val outerListTarget = emitter.currentExpectedParseTargetType?.removeSuffix("?")?.trim()
        val elemTarget =
            when {
                outerListTarget != null && outerListTarget.startsWith("MutableList<") && outerListTarget.endsWith(">") ->
                    outerListTarget.removePrefix("MutableList<").dropLast(1).trim()
                outerListTarget != null && outerListTarget.startsWith("List<") && outerListTarget.endsWith(">") ->
                    outerListTarget.removePrefix("List<").dropLast(1).trim()
                else -> null
            }
        val repeatStreams = splitTopLevelVecRepeatSemicolon(ctx.tokens)
        if (repeatStreams != null) {
            val (elemStream, countStream) = repeatStreams
            val elemKind = elemTarget?.let(emitter::resolveNumericKind)
            val (elemRendered, isLiteralElem) =
                emitter.withExpectedTargetAndKind(elemTarget, elemKind) {
                    val parsedElem = parse2(ExprParse::parse, elemStream.clone()).getOrNull()
                    val rendered =
                        if (parsedElem != null) {
                            emitter.renderTypedNumericInitializer(parsedElem, elemKind, ctx.indent, ctx.selfTypeName)
                        } else {
                            ctx.renderStreamAsExpr(elemStream)
                        }
                    val isLit = parsedElem != null && emitter.unwrapParenOrGroupExpr(parsedElem) is Expr.Lit
                    rendered to isLit
                }
            val countInt =
                emitter.withExpectedTargetAndKind(null, NumericKind.I32) {
                    val parsedCount = parse2(ExprParse::parse, countStream.clone()).getOrNull()
                    val rawCount =
                        if (parsedCount != null) {
                            emitter.renderTypedNumericInitializer(parsedCount, NumericKind.I32, ctx.indent, ctx.selfTypeName)
                        } else {
                            ctx.renderStreamAsExpr(countStream)
                        }
                    emitter.normalizeArrayLengthToIntExpr(rawCount, parsedCount, ctx.selfTypeName)
                }
            return if (isLiteralElem) {
                "MutableList($countInt) { $elemRendered }"
            } else {
                val repeatElemName = "repeatElem${emitter.closureLabelCounter++}"
                "run { val $repeatElemName = $elemRendered; MutableList($countInt) { $repeatElemName } }"
            }
        }
        val targetArgType = elemTarget ?: emitter.currentExpectedParseTargetType
        val targetArgKind = elemTarget?.let(emitter::resolveNumericKind) ?: emitter.currentExpectedNumericKind
        val args = emitter.withExpectedTargetAndKind(targetArgType, targetArgKind) { ctx.renderCommaExprs() }
        return "mutableListOf(${args.joinToString(", ")})"
    }

    private fun splitTopLevelVecRepeatSemicolon(tokens: TokenStream): Pair<TokenStream, TokenStream>? {
        val cursor = RustTokenCursor(tokens.clone().toList())
        if (cursor.size == 0) return null
        var semiIndex = -1
        for (idx in 0 until cursor.size) {
            if (cursor.punctCharAt(idx) == ';') {
                if (semiIndex >= 0) return null
                semiIndex = idx
            }
        }
        if (semiIndex <= 0 || semiIndex >= cursor.size - 1) return null
        return TokenStream.fromTokenTrees(cursor.trees.subList(0, semiIndex)) to
            TokenStream.fromTokenTrees(cursor.trees.subList(semiIndex + 1, cursor.size))
    }
}

private object AssertMacroRule : BuiltinMacroCallRule("assert", "debug_assert", "const_assert") {
    override fun render(ctx: MacroInvocationContext): String {
        val parts = ctx.splitArgs()
        if (parts.isEmpty()) return "check(true)"
        val cond = ctx.renderTreesAsExpr(parts[0])
        if (parts.size < 2) return "check($cond)"
        val msg =
            if (ctx.macroName == "const_assert") {
                ctx.renderTreesAsExpr(parts[1])
            } else {
                ctx.renderFormat(ctx.joinCommaSeparatedTail(parts))
            }
        return "check($cond) { $msg }"
    }
}

private object EqualityAssertMacroRule : BuiltinMacroCallRule("assert_eq", "debug_assert_eq", "assert_ne", "debug_assert_ne") {
    override fun render(ctx: MacroInvocationContext): String {
        val parts = RustTokenCursor.splitMacroCommaArgsWithComparisonFallback(ctx.tokens)
        val isNotEq = ctx.macroName.endsWith("_ne")
        return when {
            parts.isEmpty() -> "check(true)"
            parts.size == 1 -> "check(${ctx.renderTreesAsExpr(parts[0])})"
            else -> {
                val leftStream = TokenStream.fromTokenTrees(parts[0])
                val rightStream = TokenStream.fromTokenTrees(parts[1])
                val left = parse2(ExprParse::parse, leftStream.clone()).getOrNull()
                val right = parse2(ExprParse::parse, rightStream.clone()).getOrNull()
                if (left != null && right != null) {
                    "check(${ctx.emitter.renderSourceEquality(left, right, isNotEq, ctx.indent, ctx.selfTypeName)})"
                } else {
                    val lRendered = left?.let { ctx.emitter.renderExpr(it, ctx.indent, ctx.selfTypeName) } ?: ctx.renderStreamAsExpr(leftStream)
                    val rRendered = right?.let { ctx.emitter.renderExpr(it, ctx.indent, ctx.selfTypeName) } ?: ctx.renderStreamAsExpr(rightStream)
                    val op = if (isNotEq) "!=" else "=="
                    "check(($lRendered) $op ($rRendered))"
                }
            }
        }
    }
}

private object CompileTimeTextMacroRule : BuiltinMacroCallRule("cfg", "stringify", "concat") {
    override fun render(ctx: MacroInvocationContext): String =
        when (ctx.macroName) {
            "cfg" -> evalCfgExpressionTokens(ctx.tokens.clone().toList()).toString()
            "stringify" -> renderKotlinDoubleQuotedString(CompileTimeMacroStringEvaluator.stringifyStream(ctx.tokens))
            else ->
                CompileTimeMacroStringEvaluator.evaluateConcat(ctx.tokens)?.let(::renderKotlinDoubleQuotedString) ?: run {
                    val parts = ctx.renderCommaExprs()
                    if (parts.isEmpty()) "\"\"" else parts.joinToString(" + ")
                }
        }
}

private object IncludeFileMacroRule : BuiltinMacroCallRule("include_bytes", "include_str") {
    override fun render(ctx: MacroInvocationContext): String {
        val singlePart = ctx.splitArgs().singleOrNull { it.isNotEmpty() }
        val relPath =
            singlePart?.let { CompileTimeMacroStringEvaluator(it).evaluate() }
                ?: error("${ctx.macroName}! expects a compile-time string path argument")
        val resolvedPath = resolveIncludeTargetPath(ctx.emitter.headerContext.sourceFilePath, relPath)
        val kmPath = KmPath(resolvedPath)
        val meta = SystemFileSystem.metadataOrNull(kmPath)
        require(meta != null && meta.isRegularFile) {
            "${ctx.macroName}! target file not found: $resolvedPath"
        }
        val bytes = SystemFileSystem.readBytes(kmPath)
        return when (ctx.macroName) {
            "include_bytes" -> {
                if (bytes.isEmpty()) {
                    "byteArrayOf()"
                } else {
                    ctx.emitter.extraTypePathImports += "import kotlin.io.encoding.Base64"
                    renderChunkedBase64ByteArrayExpr(Base64.Default.encode(bytes), ctx.indent)
                }
            }
            "include_str" -> renderChunkedStringLiteralExpr(bytes.decodeToString(throwOnInvalidSequence = true), ctx.indent)
            else -> error("unsupported include macro: ${ctx.macroName}")
        }
    }
}

private object MatchesMacroRule : BuiltinMacroCallRule("matches") {
    private val matchArmTail: List<TokenTree> =
        listOf(
            RustTokenCursor.punctTree('=', Spacing.Joint),
            RustTokenCursor.punctTree('>'),
            RustTokenCursor.identTree("true"),
            RustTokenCursor.punctTree(','),
            RustTokenCursor.identTree("_"),
            RustTokenCursor.punctTree('=', Spacing.Joint),
            RustTokenCursor.punctTree('>'),
            RustTokenCursor.identTree("false"),
        )

    override fun render(ctx: MacroInvocationContext): String {
        val parts = ctx.splitArgs().filter { it.isNotEmpty() }
        if (parts.size < 2) return "false"
        val scrutineeTokens =
            if (parts[0].any { it is TokenTree.Group && it.value.delimiter() == Delimiter.Brace }) {
                listOf(RustTokenCursor.groupTree(Delimiter.Parenthesis, parts[0]))
            } else {
                parts[0]
            }
        val armTokens = parts[1] + matchArmTail
        val fullTokens = listOf(RustTokenCursor.identTree("match")) + scrutineeTokens + RustTokenCursor.groupTree(Delimiter.Brace, armTokens)
        return ctx.renderTreesAsExpr(fullTokens)
    }
}

private object SourceLocationMacroRule : BuiltinMacroCallRule("line", "column", "file") {
    override fun render(ctx: MacroInvocationContext): String {
        if (!ctx.tokens.isEmpty()) {
            return ctx.renderPlainFnCallFallback(ctx.macroName)
        }
        val macSpan = ctx.mac.path.span()
        val startLoc = macSpan.start()
        val sourcePath = ctx.emitter.headerContext.portLintSourcePath
        return when (ctx.macroName) {
            "line" -> "${startLoc.line}u"
            "column" -> "${startLoc.column + 1}u"
            else -> renderKotlinDoubleQuotedString(sourcePath.orEmpty())
        }
    }
}

private object SynDelimiterOrTokenMacroRule : BuiltinMacroCallRule("Token", "parenthesized", "braced", "bracketed") {
    override fun render(ctx: MacroInvocationContext): String {
        if (ctx.macroName == "Token") {
            return RustTokenCursor.resolveSynTokenStreamTypeName(ctx.tokens)?.let { "$it.default()" } ?: "Token"
        }
        val delim = ctx.emitter.extractDelimitedMacroParts(ctx.mac, ctx.indent, ctx.selfTypeName)
        return if (delim != null) {
            ctx.emitter.renderSourceResultTry("${delim.first}(${delim.third})", "return")
        } else {
            ctx.renderPlainFnCallFallback()
        }
    }
}

private object QuoteFamilyMacroRule : BuiltinMacroCallRule(
    "quote",
    "parse_quote",
    "quote_block",
    "quote_expr",
    "quote_spanned",
    "parse_quote_spanned",
) {
    override fun render(ctx: MacroInvocationContext): String {
        val quoteEmitter = QuoteMacroTokenEmitter(ctx.tokens.clone().toList(), ctx.emitter, ctx.selfTypeName)
        return when (ctx.macroName) {
            "quote_block" -> "Fragment.Block(${quoteEmitter.renderCall("quote", ctx.indent, spanned = false)})"
            "quote_expr" -> "Fragment.Expr(${quoteEmitter.renderCall("quote", ctx.indent, spanned = false)})"
            "quote_spanned", "parse_quote_spanned" ->
                quoteEmitter.renderCall(rustFunctionIdentToKotlin(ctx.macroName), ctx.indent, spanned = true)
            else -> quoteEmitter.renderCall(rustFunctionIdentToKotlin(ctx.macroName), ctx.indent, spanned = false)
        }
    }
}

private object InlineHostMacroRule : BuiltinMacroCallRule("cpp", "c") {
    override fun render(ctx: MacroInvocationContext): String {
        val emitter = ctx.emitter
        val inlineCall =
            emitter.ffiCollector.recordInlineExpressionMacro(
                mac = ctx.mac,
                enclosingFnKotlinName = emitter.currentEnclosingFunctionName,
                renderRustType = { rustTy, isParam ->
                    val prevAbi = emitter.inExternAbiSignature
                    val prevParam = emitter.inExternParamType
                    emitter.inExternAbiSignature = true
                    emitter.inExternParamType = isParam
                    try {
                        emitter.renderType(rustTy, ctx.selfTypeName)
                    } finally {
                        emitter.inExternParamType = prevParam
                        emitter.inExternAbiSignature = prevAbi
                    }
                },
                resolveLocalExprName = { raw -> emitter.scopeBuilder.resolveLocalKotlinName(raw) ?: rustValueIdentToKotlin(raw) },
            )
        return inlineCall ?: ctx.renderPlainFnCallFallback()
    }
}

private object LowLevelMemoryOrAsmMacroRule : BuiltinMacroCallRule("offset_of", "addr_of", "addr_of_mut", "asm", "naked_asm", "global_asm") {
    override fun render(ctx: MacroInvocationContext): String =
        when (ctx.macroName) {
            "offset_of" ->
                if (ctx.emitter.headerContext.rawAbiMode && ctx.emitter.currentExpectedNumericKind == null) {
                    "0uL"
                } else {
                    ctx.emitter.renderIntLiteral("0", "")
                }
            "addr_of", "addr_of_mut" -> {
                val place = ctx.renderStreamAsExpr(ctx.tokens)
                val callee = rustFunctionIdentToKotlin(ctx.macroName)
                val headerCtx = ctx.emitter.headerContext
                val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerCtx)
                val pkgName = headerCtx.packageName
                if (("core" in headerCtx.crateNamespaces || pkgName?.startsWith(coreNs) == true) && pkgName != "$coreNs.mem") {
                    CoreIntrinsicLowering.registerCoreImport(ctx.emitter, "$coreNs.mem.$callee")
                }
                "$callee($place)"
            }
            else -> "Unit"
        }
}

private val BUILTIN_MACRO_CALL_RULES: List<BuiltinMacroCallRule> =
    listOf(
        TriMacroRule,
        FormatMacroRule,
        WriteMacroRule,
        PrintMacroRule,
        PanicMacroRule,
        ConstErrorMacroRule,
        VecMacroRule,
        AssertMacroRule,
        EqualityAssertMacroRule,
        CompileTimeTextMacroRule,
        IncludeFileMacroRule,
        MatchesMacroRule,
        SourceLocationMacroRule,
        SynDelimiterOrTokenMacroRule,
        QuoteFamilyMacroRule,
        InlineHostMacroRule,
        LowLevelMemoryOrAsmMacroRule,
    )

private val BUILTIN_MACRO_CALL_RULES_BY_NAME: Map<String, BuiltinMacroCallRule> =
    buildMap {
        for (rule in BUILTIN_MACRO_CALL_RULES) {
            for (name in rule.names) {
                put(name, rule)
            }
        }
    }

private class QuoteMacroTokenEmitter(
    trees: List<TokenTree>,
    private val emitter: KotlinJ2KEmitter,
    private val selfTypeName: String?,
) : RustTokenCursor(trees) {
    fun renderCall(
        fnName: String,
        indent: String,
        spanned: Boolean,
    ): String {
        var bodyStartIdx = 0
        var spanArg: String? = null
        if (spanned) {
            for (i in 0 until size - 1) {
                if (punctCharAt(i) == '=' && punctCharAt(i + 1) == '>') {
                    val spanStream = TokenStream.fromTokenTrees(trees.subList(0, i))
                    spanArg = emitter.parseAndRenderTokenStreamAsExpr(spanStream, indent, selfTypeName)
                    bodyStartIdx = i + 2
                    break
                }
            }
        }
        val bodyEmitter =
            if (bodyStartIdx > 0) {
                QuoteMacroTokenEmitter(trees.subList(bodyStartIdx, size), emitter, selfTypeName)
            } else {
                this
            }
        val templateSb = StringBuilder()
        val bindings = linkedMapOf<String, String>()
        bodyEmitter.emitTemplateAndBindings(templateSb, bindings)
        val templateLit = renderKotlinDoubleQuotedString(templateSb.toString().trim())
        val callArgs = mutableListOf<String>()
        if (spanArg != null) callArgs += spanArg
        callArgs += templateLit
        for ((k, v) in bindings) {
            callArgs += "\"$k\" to $v"
        }
        return "$fnName(${callArgs.joinToString(", ")})"
    }

    fun emitTemplateAndBindings(
        out: StringBuilder,
        bindings: MutableMap<String, String>,
    ) {
        var i = 0
        while (i < size) {
            if (punctCharAt(i) == '#' && i + 1 < size) {
                val nextIdent = cleanIdentAt(i + 1)
                val nextBracket = groupAt(i + 1, Delimiter.Bracket)
                val nextParen = groupAt(i + 1, Delimiter.Parenthesis)
                when {
                    nextIdent != null -> {
                        val ktIdent =
                            when {
                                nextIdent == "self" -> "this"
                                nextIdent == "Self" -> selfTypeName ?: "Self"
                                nextIdent.first().isUpperCase() -> rustTypeIdentToKotlin(nextIdent)
                                else -> emitter.scopeBuilder.resolveLocalKotlinName(nextIdent) ?: rustValueIdentToKotlin(nextIdent)
                            }
                        out.append("`#`").append(ktIdent).append(' ')
                        if (ktIdent != "this") {
                            bindings[ktIdent] = ktIdent
                        }
                        i += 2
                        continue
                    }
                    nextBracket != null -> {
                        out.append("`#`[")
                        QuoteMacroTokenEmitter(nextBracket.stream().toList(), emitter, selfTypeName).emitTemplateAndBindings(out, bindings)
                        out.append("] ")
                        i += 2
                        continue
                    }
                    nextParen != null -> {
                        QuoteMacroTokenEmitter(nextParen.stream().toList(), emitter, selfTypeName).emitTemplateAndBindings(out, bindings)
                        i += 2
                        if (punctCharAt(i) == '*') i++
                        continue
                    }
                }
            }
            when (val tt = trees[i]) {
                is TokenTree.Ident -> out.append(tt.value.toString()).append(' ')
                is TokenTree.Literal -> out.append(tt.value.toString()).append(' ')
                is TokenTree.Punct -> {
                    out.append(tt.value.asChar())
                    if (tt.value.spacing() == Spacing.Alone) {
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
                    QuoteMacroTokenEmitter(tt.value.stream().toList(), emitter, selfTypeName).emitTemplateAndBindings(out, bindings)
                    out.append(close)
                }
            }
            i++
        }
    }

    fun collectFlatMacroTokenArgs(): List<String> {
        val out = mutableListOf<String>()
        for (tt in trees) {
            when (tt) {
                is TokenTree.Ident -> {
                    val raw = tt.value.toString().removePrefix("r#")
                    if (raw != "_" && raw !in IGNORED_QUOTE_KEYWORDS) {
                        val localName = emitter.scopeBuilder.resolveLocalKotlinName(raw)
                        out +=
                            when {
                                raw == "self" -> "this"
                                raw == "Self" -> selfTypeName ?: "Self"
                                raw == "true" || raw == "false" -> raw
                                localName != null -> localName
                                raw in emitter.inspector.declaredConstNames -> emitter.inspector.declaredConstNames.getValue(raw)
                                emitter.isScreamingSnakeIdent(raw) -> rustConstIdentToKotlin(raw)
                                raw.first().isUpperCase() -> rustTypeIdentToKotlin(raw)
                                else -> rustValueIdentToKotlin(raw)
                            }
                    }
                }
                is TokenTree.Literal -> out += emitter.renderLit(Lit.new(tt.value))
                is TokenTree.Group ->
                    out.addAll(QuoteMacroTokenEmitter(tt.value.stream().toList(), emitter, selfTypeName).collectFlatMacroTokenArgs())
                is TokenTree.Punct -> Unit
            }
        }
        return out
    }
}

private class CompileTimeMacroStringEvaluator(
    rawTrees: List<TokenTree>,
) : RustTokenCursor(unwrapNoneGroupSlice(rawTrees)) {
    fun evaluate(): String? {
        if (size == 0) return null
        if (size == 1) {
            when (val single = trees[0]) {
                is TokenTree.Literal -> {
                    val strRes = single.value.strValue()
                    strRes.value?.let { return it }
                    return when (val lit = Lit.new(single.value)) {
                        is Lit.Str -> lit.value.value()
                        is Lit.Char -> {
                            val cp = lit.value.value()
                            if (cp in 0x10000..0x10FFFF) {
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
                else -> Unit
            }
        }
        val lastGrp = if (size >= 3 && punctCharAt(size - 2) == '!') groupAt(size - 1) else null
        if (lastGrp != null) {
            val validPath = (0 until size - 2).all { idx -> identAt(idx) != null || punctCharAt(idx) == ':' }
            val macroIdent = identAt(size - 3)
            if (validPath && macroIdent != null) {
                val argStream = lastGrp.stream()
                return when (macroIdent) {
                    "stringify" -> stringifyStream(argStream)
                    "concat" -> evaluateConcat(argStream)
                    else -> null
                }
            }
        }
        return null
    }

    companion object {
        fun stringifyStream(tokens: TokenStream): String {
            val flat = mutableListOf<TokenTree>()
            flattenNoneDelimitedGroups(tokens, flat)
            return TokenStream.fromTokenTrees(flat).toString()
        }

        fun evaluateConcat(tokens: TokenStream): String? {
            val sb = StringBuilder()
            for (part in splitMacroArguments(tokens)) {
                if (part.isEmpty()) continue
                val piece = CompileTimeMacroStringEvaluator(part).evaluate() ?: return null
                sb.append(piece)
            }
            return sb.toString()
        }
    }
}

private const val INCLUDE_CONSTANT_POOL_CHUNK_BYTES: Int = 48_000

internal fun resolveIncludeTargetPath(
    sourceFilePath: String?,
    relativeIncludePath: String,
): String {
    val baseFile =
        sourceFilePath?.trim()?.takeIf { it.isNotEmpty() }
            ?: error("include_bytes!/include_str! requires FileHeaderContext.sourceFilePath")
    val normalizedInclude = relativeIncludePath.replace('\\', '/')
    val isAbsoluteInclude =
        normalizedInclude.startsWith("/") ||
            (normalizedInclude.length >= 3 && normalizedInclude[0].isLetter() && normalizedInclude[1] == ':' && normalizedInclude[2] == '/')
    val combined =
        if (isAbsoluteInclude) {
            normalizedInclude
        } else {
            val normBase = baseFile.replace('\\', '/')
            val slashIdx = normBase.lastIndexOf('/')
            if (slashIdx >= 0) "${normBase.substring(0, slashIdx)}/$normalizedInclude" else normalizedInclude
        }
    return normalizeLexicalPath(combined)
}

internal fun normalizeLexicalPath(path: String): String {
    val normalized = path.replace('\\', '/')
    val hasDrivePrefix = normalized.length >= 3 && normalized[0].isLetter() && normalized[1] == ':' && normalized[2] == '/'
    val rootPrefix =
        when {
            hasDrivePrefix -> normalized.substring(0, 3)
            normalized.startsWith("/") -> "/"
            else -> ""
        }
    val stack = mutableListOf<String>()
    for (seg in normalized.substring(rootPrefix.length).split('/')) {
        when (seg) {
            "", "." -> Unit
            ".." ->
                when {
                    stack.isNotEmpty() && stack.last() != ".." -> stack.removeAt(stack.lastIndex)
                    rootPrefix.isEmpty() -> stack += ".."
                }
            else -> stack += seg
        }
    }
    return rootPrefix + stack.joinToString("/")
}

private fun renderIndentedBuildStringChunks(
    chunks: List<String>,
    outerIndent: String,
): String =
    buildString {
        append("buildString {\n")
        for (chunk in chunks) {
            append(outerIndent)
                .append("    append(")
                .append(renderKotlinDoubleQuotedString(chunk))
                .append(")\n")
        }
        append(outerIndent).append('}')
    }

private fun renderChunkedBase64ByteArrayExpr(
    encoded: String,
    indent: String,
): String {
    if (encoded.length <= INCLUDE_CONSTANT_POOL_CHUNK_BYTES) {
        return "Base64.decode(${renderKotlinDoubleQuotedString(encoded)})"
    }
    val chunks = encoded.chunked(INCLUDE_CONSTANT_POOL_CHUNK_BYTES)
    val innerBuildString = renderIndentedBuildStringChunks(chunks, "$indent    ")
    return "Base64.decode(\n$indent    $innerBuildString,\n$indent)"
}

private fun renderChunkedStringLiteralExpr(
    text: String,
    indent: String,
): String {
    val chunks = chunkModifiedUtf8String(text, INCLUDE_CONSTANT_POOL_CHUNK_BYTES)
    if (chunks.size <= 1) return renderKotlinDoubleQuotedString(text)
    return renderIndentedBuildStringChunks(chunks, indent)
}

internal fun chunkModifiedUtf8String(
    text: String,
    maxBytesPerChunk: Int = INCLUDE_CONSTANT_POOL_CHUNK_BYTES,
): List<String> {
    if (text.isEmpty()) return listOf("")
    val chunks = mutableListOf<String>()
    var chunkStart = 0
    var chunkBytes = 0
    var idx = 0
    while (idx < text.length) {
        val ch = text[idx]
        val isSurrogatePair = ch.isHighSurrogate() && idx + 1 < text.length && text[idx + 1].isLowSurrogate()
        val charLen = if (isSurrogatePair) 2 else 1
        // JVM constant-pool Strings use Modified UTF-8: NUL or U+0080..U+07FF takes 2 bytes,
        // other BMP chars take 3 bytes, and supplementary pairs take 2 * 3 = 6 bytes.
        val addedBytes =
            when {
                isSurrogatePair -> 6
                ch.code in 0x0001..0x007F -> 1
                ch.code <= 0x07FF -> 2
                else -> 3
            }
        if (chunkBytes + addedBytes > maxBytesPerChunk && idx > chunkStart) {
            chunks += text.substring(chunkStart, idx)
            chunkStart = idx
            chunkBytes = 0
        }
        chunkBytes += addedBytes
        idx += charLen
    }
    if (chunkStart < text.length) {
        chunks += text.substring(chunkStart)
    }
    return chunks
}

internal fun KotlinJ2KEmitter.parseAndRenderTokenStreamAsExpr(
    stream: TokenStream,
    indent: String,
    selfTypeName: String?,
): String {
    val parsed = parse2(ExprParse::parse, stream.clone()).getOrNull()
    if (parsed != null) return renderExpr(parsed, indent, selfTypeName)
    return QuoteMacroTokenEmitter(stream.clone().toList(), this, selfTypeName)
        .collectFlatMacroTokenArgs()
        .joinToString(", ")
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
            val last = expr.path.lastCleanIdent()
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
        is Expr.MethodCall -> expr.method.toString() in BOOLEAN_CAST_SOURCE_METHODS
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
        is BinOp.BitXorAssign,
        is BinOp.BitAndAssign,
        is BinOp.BitOrAssign,
        is BinOp.ShlAssign,
        is BinOp.ShrAssign,
        -> "="
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
            when (currentExpectedParseTargetType?.removeSuffix("?")?.trim()) {
                "List<UByte>", "MutableList<UByte>" -> "$encoded.map { it.toUByte() }"
                "UByteArray" -> "$encoded.toUByteArray()"
                else -> encoded
            }
        }
        is Lit.CStr -> renderKotlinDoubleQuotedString(lit.value.value().decodeToString())
        is Lit.Byte ->
            when (currentExpectedNumericKind) {
                NumericKind.U32 -> "${lit.value.value()}u"
                NumericKind.U64 -> "${lit.value.value()}uL"
                NumericKind.I32 -> "${lit.value.value()}"
                NumericKind.I64 -> "${lit.value.value()}L"
                else -> "${lit.value.value()}u.toUByte()"
            }
        is Lit.Char -> renderKotlinCharLiteral(lit.value.value())
        is Lit.Int -> {
            val suffix = lit.value.suffix()
            val rawToken = lit.value.token().toString()
            val rawDigits = if (suffix.isNotEmpty() && rawToken.endsWith(suffix)) rawToken.dropLast(suffix.length) else rawToken
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
    if (bits.isEmpty() || bits.any { it != '0' && it != '1' }) return rawDigits
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
    return "0x${hex.toString().trimStart('0').ifEmpty { "0" }}"
}

private fun renderIntAsFloatKind(
    clean: String,
    nonDecAtom: String,
    kind: NumericKind,
): String? {
    val isDec = clean.all { it.isDigit() }
    return when (kind) {
        NumericKind.F16 -> if (isDec) "F16.fromFloat($clean.0f)" else "F16.fromDouble(($nonDecAtom).toDouble())"
        NumericKind.F32 -> if (isDec) "$clean.0f" else "($nonDecAtom).toFloat()"
        NumericKind.F64 -> if (isDec) "$clean.0" else "($nonDecAtom).toDouble()"
        NumericKind.F128 -> if (isDec) "F128.fromDouble($clean.0)" else "F128.fromDouble(($nonDecAtom).toDouble())"
        else -> null
    }
}

internal fun KotlinJ2KEmitter.renderIntLiteral(digits: String, suffix: String): String {
    val parsed128 = parseUnsigned128LiteralDigits(digits)
    if (suffix == "u128" || (suffix.isEmpty() && currentExpectedNumericKind == NumericKind.U128)) {
        return if (parsed128 != null) formatU128ConstructorCall(parsed128.high, parsed128.low) else "U128()"
    }
    if (suffix == "i128" || (suffix.isEmpty() && currentExpectedNumericKind == NumericKind.I128)) {
        return if (parsed128 != null) formatI128ConstructorCall(parsed128.high, parsed128.low) else "I128()"
    }
    if (suffix.isEmpty() && parsed128?.exceeds64BitUnsigned == true) {
        return formatU128ConstructorCall(parsed128.high, parsed128.low)
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
        val expected = currentExpectedNumericKind
        if (expected != null) {
            renderIntAsFloatKind(clean, nonDecAtom, expected)?.let { return it }
        }
        return when (expected) {
            NumericKind.U8 -> "${clean}u.toUByte()"
            NumericKind.U16 -> "${clean}u.toUShort()"
            NumericKind.U32 -> if (low64 != null && low64 > 0xFFFFFFFFuL) "${clean}uL.toUInt()" else "${clean}u"
            NumericKind.U64 -> "${clean}uL"
            NumericKind.I8 -> if (low64 != null && low64 > 0x7FuL) "$clean.toByte()" else clean
            NumericKind.I16 -> if (low64 != null && low64 > 0x7FFFuL) "$clean.toShort()" else clean
            NumericKind.I32 ->
                when {
                    low64 != null && low64 > 0xFFFFFFFFuL -> "${clean}L.toInt()"
                    low64 != null && low64 >= 0x80000000uL -> "$clean.toInt()"
                    else -> clean
                }
            NumericKind.I64 -> if (low64 != null && low64 >= 0x8000000000000000uL) "${clean}uL.toLong()" else "${clean}L"
            else -> if (low64 != null && low64 >= 0x8000000000000000uL) "${clean}uL" else clean
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
        "f16" -> renderIntAsFloatKind(clean, nonDecAtom, NumericKind.F16)!!
        "f32" -> renderIntAsFloatKind(clean, nonDecAtom, NumericKind.F32)!!
        "f64" -> renderIntAsFloatKind(clean, nonDecAtom, NumericKind.F64)!!
        "f128" -> renderIntAsFloatKind(clean, nonDecAtom, NumericKind.F128)!!
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

internal fun formatBmpKotlinCharLiteral(
    ch: Char,
    useUnicodeEscapeForNonAscii: Boolean = true,
): String =
    when (ch) {
        '\'' -> "'\\''"
        '\\' -> "'\\\\'"
        '\n' -> "'\\n'"
        '\r' -> "'\\r'"
        '\t' -> "'\\t'"
        '\b' -> if (useUnicodeEscapeForNonAscii) "'\\b'" else "'$ch'"
        else ->
            if (!useUnicodeEscapeForNonAscii || ch.code in 32..126) {
                "'$ch'"
            } else {
                "'\\u${ch.code.toString(16).padStart(4, '0')}'"
            }
    }

internal fun KotlinJ2KEmitter.renderKotlinCharLiteral(codePoint: Int): String {
    if (headerContext.scalarChar) {
        usesRustChar = true
        return "RustChar(0x${codePoint.toString(16)}u)"
    }
    if (codePoint > 0xFFFF) return "0x${codePoint.toString(16)}"
    return formatBmpKotlinCharLiteral(codePoint.toChar())
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
    if (inputs.any { it is FnArg.Receiver } || sig.generics.params.any { it is GenericParam.ConstParam }) {
        return null
    }
    val fnTypeParams = collectGenericTypeParamNames(sig.generics)
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
    return rustFunctionIdentToKotlin(sig.ident.toString()) to kinds
}

internal fun KotlinJ2KEmitter.renderCalleeExpr(funcExpr: Expr, indent: String, selfTypeName: String?): String {
    if (funcExpr is Expr.Path && funcExpr.qself == null) {
        return renderPathAsExpression(funcExpr.path, selfTypeName, asCallee = true)
    }
    return renderExpr(funcExpr, indent, selfTypeName)
}

internal fun KotlinJ2KEmitter.renderExprPath(exprPath: Expr.Path, selfTypeName: String?): String =
    ExprPathResolutionEmitter(this, selfTypeName).renderExprPath(exprPath)

internal fun KotlinJ2KEmitter.renderPathAsExpression(
    path: Path,
    selfTypeName: String?,
    asCallee: Boolean = true,
): String =
    ExprPathResolutionEmitter(this, selfTypeName).renderPath(path, asCallee)

internal fun KotlinJ2KEmitter.isKotlinFloatShadowed(): Boolean =
    "Float" in inspector.declaredTypeNames ||
        "Float" in importedSimpleNameToPkg ||
        "Float" in headerContext.crateSymbolSubpackages ||
        resolveBestSubpackageForSymbol("Float") != null

internal fun KotlinJ2KEmitter.kotlinF32TypeName(): String =
    if (isKotlinFloatShadowed()) "kotlin.Float" else "Float"

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

internal fun KotlinJ2KEmitter.renderMemberName(member: Member): String =
    when (member) {
        is Member.Named -> rustFieldIdentToKotlin(member.ident.toString())
        is Member.Unnamed -> "field${member.index.index}"
    }

/**
 * Resolves Rust value-position and callee-position [Expr.Path] / [Path] AST nodes into Kotlin
 * expressions, unifying `<QSelf as Trait>::item`, primitive associated constants, cross-module
 * paths, enum variants, witness calls, and value-position callable references.
 */
private class ExprPathResolutionEmitter(
    private val emitter: KotlinJ2KEmitter,
    private val selfTypeName: String?,
) {
    private val inspector get() = emitter.inspector
    private val headerContext get() = emitter.headerContext
    private val scopeBuilder get() = emitter.scopeBuilder
    private val selfBase: String? = selfTypeName?.substringBefore('<')?.trim()
    private val selfSimpleBase: String? = selfBase?.substringAfterLast('.')?.trim()

    private fun selfBaseOrTypeIdent(raw: String, stripOwnerQualification: Boolean = false): String =
        if (raw.removePrefix("r#") == "Self") {
            if (stripOwnerQualification) selfSimpleBase.orEmpty() else selfBase.orEmpty()
        } else {
            rustTypeIdentToKotlin(raw)
        }

    private fun renderAngleTypeArgs(
        arguments: PathArguments,
        rawIdent: String = "a",
        requireLowercaseIdent: Boolean = true,
    ): String {
        val angle = arguments as? PathArguments.AngleBracketed ?: return ""
        if (requireLowercaseIdent && rawIdent.firstOrNull()?.isUpperCase() == true) return ""
        val rendered = angle.args.toList().mapNotNull { emitter.renderGenericArgument(it, selfTypeName) }
        return if (rendered.isNotEmpty()) rendered.joinToString(", ", prefix = "<", postfix = ">") else ""
    }

    private fun resolveTypeParamWitnessExpr(typeParamName: String): String? {
        if (selfTypeName == typeParamName || emitter.currentTypedLocals["this"] == typeParamName) {
            return "this"
        }
        return emitter.currentTypedLocals.entries
            .firstOrNull { (k, v) -> v == typeParamName && k !in emitter.currentNullableLocals && k != "_" && k != "this" }
            ?.key
    }

    private fun tryRenderTypeParamWitnessAssocConst(ownerKt: String, tailSeg: PathSegment): String? {
        if (ownerKt !in emitter.activeTypeParamNames || tailSeg.arguments !is PathArguments.None) return null
        val constRaw = tailSeg.ident.toString().removePrefix("r#")
        if (!emitter.isScreamingSnakeIdent(constRaw) || constRaw in SIZED_TYPE_PROPERTY_NAMES) return null
        return resolveTypeParamWitnessExpr(ownerKt)?.let { "$it.${rustConstIdentToKotlin(constRaw)}" }
    }

    fun renderExprPath(exprPath: Expr.Path): String {
        val qself = exprPath.qself ?: return renderPath(exprPath.path, asCallee = false)
        val path = exprPath.path
        val allSegs = path.segments.toList()
        val lastConst = path.lastCleanIdent()
        if (lastConst != null && lastConst in SIZED_TYPE_PROPERTY_NAMES) {
            val traitSegName =
                if (qself.position > 0) allSegs.getOrNull(qself.position - 1)?.cleanIdent() else null
            val qselfInner = unwrapSynType(qself.ty)
            val qselfSingleKt =
                (qselfInner as? SynType.Path)
                    ?.takeIf { it.qself == null && it.path.segments.size == 1 }
                    ?.path
                    ?.firstKotlinTypeName()
            val isSizedTraitOrTypeParam =
                traitSegName == "SizedTypeProperties" ||
                    (qselfSingleKt != null && qselfSingleKt in emitter.activeTypeParamNames) ||
                    ((qself.position == 0 || traitSegName == null) && lastConst in SIZED_DIRECT_OWNER_CONSTS)
            if (isSizedTraitOrTypeParam) {
                emitter.renderSizedTypePropertyConst(qself.ty, lastConst, selfTypeName)?.let { return it }
            }
        }
        val baseTy = emitter.renderType(qself.ty, selfTypeName)
        if (lastConst != null) {
            emitter.currentTypeParamWitnessCalls[baseTy to lastConst]?.let { return it }
        }
        if (qself.position > 0 && qself.position < allSegs.size) {
            val traitSegs = allSegs.take(qself.position)
            val remSegs = allSegs.drop(qself.position)
            if (traitSegs.isNotEmpty() && remSegs.size == 1) {
                val traitKt = rustTypeIdentToKotlin(traitSegs.last().ident.toString())
                val selfDesc = emitter.describeSynTypeForDisambiguation(qself.ty)
                val fnKt = rustFunctionIdentToKotlin(remSegs[0].ident.toString())
                if ((traitKt to selfDesc) in emitter.standaloneTraitImplKeys) {
                    val synthSegments =
                        PathSegmentList().apply {
                            traitSegs.forEach { push(it) { PathSep.default() } }
                        }
                    val synthTraitPath = Path(leadingColon = path.leadingColon, segments = synthSegments)
                    return emitter.disambiguateReceiverlessTraitImplFnName(synthTraitPath, fnKt, qself.ty)
                }
            }
        }
        val qselfTypePath = qself.ty as? SynType.Path
        val qselfSegs = if (qselfTypePath != null && qselfTypePath.qself == null) qselfTypePath.path.cleanIdentStrings() else emptyList()
        val qselfPrim =
            when {
                qselfSegs.size == 1 && qselfSegs[0] in PRIMITIVE_TYPE_NAMES -> qselfSegs[0]
                qselfSegs.size == 3 &&
                    (qselfSegs[0] == "core" || qselfSegs[0] == "std") &&
                    qselfSegs[1] == "primitive" &&
                    qselfSegs[2] in PRIMITIVE_TYPE_NAMES -> qselfSegs[2]
                else -> null
            }
        val tailRawSegs = allSegs.drop(qself.position)
        if (qselfPrim != null && qself.position == 0 && tailRawSegs.size == 1) {
            val seg = tailRawSegs[0]
            renderPrimitiveAssociatedConst(
                rawSegNames = listOf(qselfPrim, seg.ident.toString().removePrefix("r#")),
                hasTrailingArgs = seg.arguments !is PathArguments.None,
            )?.let { return it }
        }
        val receiverBase = baseTy.substringBefore('<').trim()
        if (tailRawSegs.size == 1) {
            tryRenderTypeParamWitnessAssocConst(receiverBase, tailRawSegs[0])?.let { return it }
        }
        val tailSegs = tailRawSegs.map { seg -> renderSegment(seg.ident.toString(), seg.arguments, isLast = true) }
        return (listOf(receiverBase) + tailSegs).joinToString(".")
    }

    fun renderPath(path: Path, asCallee: Boolean): String {
        val rawSegments = path.segments.toList()
        if (rawSegments.isEmpty()) return "Unit"

        val rawSegNames = rawSegments.map { it.ident.toString().removePrefix("r#") }
        if (emitter.inTraitDefaultMethodBody &&
            rawSegments.size == 2 &&
            rawSegNames[0] == "Self" &&
            rawSegNames[1] in emitter.currentTraitMethodRawNames
        ) {
            val ktMethod = rustFunctionIdentToKotlin(rawSegNames[1])
            val methodTypeArgs = renderAngleTypeArgs(rawSegments[1].arguments, requireLowercaseIdent = false)
            return if (asCallee) "this.$ktMethod$methodTypeArgs" else "this::$ktMethod"
        }
        if (!asCallee && rawSegments.size >= 2) {
            val constIdent = rawSegNames.last()
            if (constIdent in SIZED_TYPE_PROPERTY_NAMES) {
                val headKt = rustTypeIdentToKotlin(rawSegNames[0])
                val isSizedOwner =
                    headKt in emitter.activeTypeParamNames ||
                        (
                            rawSegments.size == 2 &&
                                constIdent in SIZED_DIRECT_OWNER_CONSTS &&
                                constIdent !in inspector.declaredConstNames &&
                                headKt !in inspector.unitStructNames &&
                                (rawSegNames[0] == "Self" || headKt in inspector.declaredTypeNames || !emitter.isScreamingSnakeIdent(rawSegNames[0]))
                        )
                if (isSizedOwner) {
                    val ownerSegIdent = if (rawSegments.size == 2) rawSegments[0].ident else rawSegments[rawSegments.lastIndex - 1].ident
                    emitter.renderSizedTypePropertyConst(SynType.Path(null, Path.from(ownerSegIdent)), constIdent, selfTypeName)?.let { return it }
                }
            }
        }
        renderPrimitiveAssociatedConst(
            rawSegNames = rawSegNames,
            hasTrailingArgs = rawSegments.last().arguments !is PathArguments.None,
        )?.let { return it }
        if (rawSegNames.size == 2 && rawSegments[0].arguments is PathArguments.None) {
            tryRenderTypeParamWitnessAssocConst(rustTypeIdentToKotlin(rawSegNames[0]), rawSegments[1])?.let { return it }
        }
        val stdlibFfiDropCount = computeStdlibFfiDropCount(rawSegNames)
        val effectiveRawSegments = if (stdlibFfiDropCount > 0) rawSegments.drop(stdlibFfiDropCount) else rawSegments
        if (rawSegments.size == 2 &&
            rawSegments[0].arguments is PathArguments.None &&
            rawSegments[1].arguments is PathArguments.None
        ) {
            inspector.collidingInlineForeignFnRenames[rawSegNames[0] to rawSegNames[1]]?.let { collidedRename ->
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
                    (scopeBuilder.isImportedModuleInScope(id) && !emitter.isSuppressedStdlibModuleAlias(id)) ||
                    emitter.resolveImportedUppercaseTypeBinding(id) != null ||
                    idKtType in inspector.declaredTypeNames ||
                    emitter.scopedAbiKeysForRenderedType(idKtType).any { it in headerContext.crateSymbolSubpackages }
            }
        if (filtered.isEmpty()) {
            val only = rawSegments.last().ident.toString()
            val cleanOnly = only.removePrefix("r#")
            return when (only) {
                "self" -> scopeBuilder.resolveLocalKotlinName("self") ?: "this"
                "Self" -> selfBase ?: "Self"
                else ->
                    emitter.activeGuardBindingSubstitutions[cleanOnly]
                        ?: scopeBuilder.resolveLocalKotlinName(cleanOnly)
                        ?: rustValueIdentToKotlin(only)
            }
        }
        if (!emitter.hasDeclaredOptionEnum() && !inspector.hasLocalNoneVariant) {
            if (filtered.size == 1 && filtered[0].ident.toString() == "None") return "null"
            if (filtered.size == 2 && filtered[0].ident.toString() == "Option" && filtered[1].ident.toString() == "None") return "null"
        }
        if (filtered.size == 1 && filtered[0].ident.toString() == "PhantomData" && "PhantomData" !in inspector.declaredTypeNames) {
            return "Unit"
        }
        if (filtered.size == 2 && filtered[0].arguments is PathArguments.None) {
            tryRenderTwoSegmentPrimitiveOrWitness(filtered[0], filtered[1], asCallee)?.let { return it }
        }
        if (rawSegments.size >= 2) {
            tryRenderCrossModulePath(rawSegments, rawSegNames, asCallee)?.let { return it }
        }
        if (!asCallee && filtered.size == 1 && filtered[0].arguments is PathArguments.None) {
            tryRenderSingleValuePositionSegment(filtered[0].ident.toString())?.let { return it }
        }

        val isSingleSegment = rawSegments.size == 1
        if (filtered.size >= 2 && !asCallee && filtered.last().cleanIdent() == "ZERO") {
            val ownerRaw = filtered[filtered.size - 2].cleanIdent()
            val simdSpec = parseSimdLaneVectorSpec(ownerRaw, emitter)
            if (simdSpec != null) {
                registerSimdWrapperImport(emitter, simdSpec.wrapperName)
                val zeroLit = simdZeroLiteralExpr(simdSpec.elemTy, simdSpec.elemKind, emitter)
                return "${simdSpec.wrapperName}<${simdSpec.elemTy}>(List(${simdSpec.lanes}) { $zeroLit })"
            }
        }
        if (filtered.size == 2) {
            val ownerRaw = filtered[0].ident.toString()
            val memberRaw = filtered[1].ident.toString()
            val cleanMember = memberRaw.removePrefix("r#")
            val ownerKt = if (ownerRaw == "Self") selfBase else rustTypeIdentToKotlin(ownerRaw)
            if (ownerKt != null &&
                ownerKt in emitter.activeTypeParamNames &&
                cleanMember.firstOrNull()?.isLowerCase() == true &&
                !emitter.isScreamingSnakeIdent(memberRaw)
            ) {
                val memberRendered = renderSegment(memberRaw, filtered[1].arguments, isLast = true, asCallee = asCallee)
                return if (asCallee) memberRendered else "::$memberRendered"
            }
        }
        val parts =
            filtered
                .mapIndexed { idx, seg ->
                    renderSegment(
                        rawIdent = seg.ident.toString(),
                        arguments = seg.arguments,
                        isLast = idx == filtered.lastIndex,
                        isSingleSegment = isSingleSegment,
                        asCallee = asCallee,
                    )
                }.toMutableList()
        if (parts.size >= 2) {
            val ownerIdx = parts.size - 2
            val ownerRaw = filtered[ownerIdx].ident.toString().removePrefix("r#")
            if (ownerRaw != "Self" && ownerRaw !in PRIMITIVE_TYPE_NAMES) {
                val ownerKt = rustTypeIdentToKotlin(ownerRaw)
                if (ownerKt in inspector.typeAliasTargets) {
                    val resolvedOwner = emitter.resolveTypeAliasOwnerName(ownerKt)
                    if (resolvedOwner != ownerKt &&
                        (
                            resolvedOwner in inspector.declaredTypeNames ||
                                resolvedOwner in headerContext.crateSymbolSubpackages ||
                                headerContext.externalCrateSymbolPackages.values.any { resolvedOwner in it }
                        )
                    ) {
                        emitter.ensureCrateSubpackageTypeImport(resolvedOwner)
                        parts[ownerIdx] = resolvedOwner
                    }
                }
            }
            val lastRaw = filtered.last().ident.toString()
            val prevRaw = filtered[filtered.size - 2].ident.toString()
            if (prevRaw != "Self" && prevRaw.first().isUpperCase() && !emitter.isScreamingSnakeIdent(prevRaw)) {
                val ownerKt = rustTypeIdentToKotlin(prevRaw)
                if (ownerKt !in emitter.activeTypeParamNames && ownerKt !in KOTLIN_STDLIB_SHADOWED_IMPORTS) {
                    emitter.ensureCrateSubpackageTypeImport(ownerKt)
                }
            }
            return finalizeMultiPartPath(
                fullParts = parts,
                prevRaw = prevRaw,
                lastRaw = lastRaw,
                asCallee = asCallee,
                allowImportedOrPrimStaticOwner = true,
            )
        }
        return parts.joinToString(".")
    }

    private fun computeStdlibFfiDropCount(rawSegNames: List<String>): Int {
        if (headerContext.rawAbiMode || rawSegNames.size < 2) return 0
        fun isStdFfiSym(sym: String): Boolean = sym == "CString" || sym in CoreIntrinsicLowering.STDLIB_CORE_FFI_SYMBOLS
        val imp = scopeBuilder.resolveModuleBinding(rawSegNames[0])?.importedPath
        val isStdFfiAlias = imp == null || STDLIB_ROOT_CRATES.any { imp.startsWith("$it.") }
        return when {
            rawSegNames.size >= 3 && rawSegNames[0] in STDLIB_ROOT_CRATES && rawSegNames[1] == "ffi" && isStdFfiSym(rawSegNames[2]) -> 2
            rawSegNames.size >= 4 && rawSegNames[0] == "std" && rawSegNames[1] == "os" && rawSegNames[2] == "raw" && isStdFfiSym(rawSegNames[3]) -> 3
            (rawSegNames[0] == "ffi" || rawSegNames[0] == "raw") && isStdFfiSym(rawSegNames[1]) && isStdFfiAlias -> 1
            else -> 0
        }
    }

    private fun tryRenderTwoSegmentPrimitiveOrWitness(
        firstSeg: PathSegment,
        secondSeg: PathSegment,
        asCallee: Boolean,
    ): String? {
        val firstRaw = firstSeg.ident.toString()
        val secondRaw = secondSeg.ident.toString()
        val cleanSecond = secondRaw.removePrefix("r#")
        val firstKtType = rustTypeIdentToKotlin(firstRaw)
        if (secondSeg.arguments is PathArguments.None &&
            firstRaw != "f16" &&
            firstRaw != "f128" &&
            (firstRaw in PRIMITIVE_TYPE_NAMES || emitter.resolveNumericKind(firstKtType) != null) &&
            (secondRaw == "MAX" || secondRaw == "MIN")
        ) {
            val kind = emitter.resolveNumericKind(firstKtType)
            val boundOwner = if (firstRaw in PRIMITIVE_TYPE_NAMES) emitter.kotlinBuiltinTypeName(firstKtType) else firstKtType
            return if (secondRaw == "MIN" && (kind == NumericKind.F32 || kind == NumericKind.F64)) {
                "(-$boundOwner.MAX_VALUE)"
            } else {
                "$boundOwner.${secondRaw}_VALUE"
            }
        }
        if (secondSeg.arguments is PathArguments.None &&
            (firstRaw == "u128" || firstRaw == "i128" || firstRaw == "f16" || firstRaw == "f128")
        ) {
            val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
            val ownerPrim =
                when (firstRaw) {
                    "u128" -> "U128"
                    "i128" -> "I128"
                    "f16" -> {
                        CoreIntrinsicLowering.registerCoreImport(emitter, "$coreNs.F16")
                        "F16"
                    }
                    else -> {
                        CoreIntrinsicLowering.registerCoreImport(emitter, "$coreNs.F128")
                        "F128"
                    }
                }
            fun refOrCall(fn: String): String = if (asCallee) "$ownerPrim.$fn" else "$ownerPrim::$fn"
            when (cleanSecond) {
                "from_be_bytes" -> return refOrCall("fromBeBytes")
                "from_le_bytes" -> return refOrCall("fromLeBytes")
                "from_ne_bytes" -> return refOrCall(if (emitter.activeCfgProfile.targetEndian == "big") "fromBeBytes" else "fromLeBytes")
                "from_bits" -> if (firstRaw == "f16" || firstRaw == "f128") return refOrCall("fromBits")
                "to_bits" -> if (firstRaw == "f16" || firstRaw == "f128") return refOrCall("toBits")
                "ZERO" -> return "$ownerPrim.ZERO"
                "BITS" -> return if (firstRaw == "f16") "16u" else "128u"
            }
        }
        val firstWitnessKey = if (firstRaw == "Self" && selfBase != null) selfBase else firstKtType
        emitter.currentTypeParamWitnessCalls[firstWitnessKey to cleanSecond]?.let { return it }
        if (firstRaw == "Default" && cleanSecond == "default") {
            val targetTp = emitter.currentExpectedParseTargetType?.trim()
            if (targetTp != null) {
                emitter.currentTypeParamWitnessCalls[targetTp to "default"]?.let { return it }
            }
            emitter.currentTypeParamWitnessCalls["*" to "default"]?.let { return it }
        }
        return null
    }

    private fun tryRenderUnitStructOrVariantValue(typeIdent: String, rawSegNames: List<String>): String? =
        if (typeIdent in inspector.unitStructNames || emitter.isGenericUnitEnumVariant(typeIdent)) {
            "$typeIdent()"
        } else {
            emitter.resolveStdlibUnitStructValueExpr(rawSegNames)
        }

    private fun tryRenderCrossModulePath(
        rawSegments: List<PathSegment>,
        rawSegNames: List<String>,
        asCallee: Boolean,
    ): String? {
        val (resolvedSym, trailing) = emitter.resolveCrossModulePathTarget(rawSegments.map { it.ident.toString() }) ?: return null
        if (!rawSegments.dropLast(1 + trailing.size).all { it.arguments is PathArguments.None }) return null
        val boundHead = emitter.bindOrQualifyResolvedCrossSymbol(resolvedSym)
        val headSeg = rawSegments[rawSegments.lastIndex - trailing.size]
        val headIdent = headSeg.ident.toString()
        if (trailing.isEmpty()) {
            val cleanLast = headIdent.removePrefix("r#")
            if (!asCallee && headSeg.arguments is PathArguments.None) {
                if (headIdent.first().isUpperCase() && !emitter.isScreamingSnakeIdent(headIdent)) {
                    tryRenderUnitStructOrVariantValue(boundHead, rawSegNames)?.let { return it }
                }
                if (headIdent.first().isLowerCase() &&
                    '.' !in boundHead &&
                    cleanLast in inspector.declaredTopLevelFnRawNames &&
                    cleanLast !in inspector.declaredConstNames
                ) {
                    return "::$boundHead"
                }
            }
            val headTypeArgs = renderAngleTypeArgs(headSeg.arguments, headIdent)
            return "$boundHead$headTypeArgs"
        }
        val tailSegs = rawSegments.takeLast(trailing.size)
        val renderedTail =
            tailSegs.mapIndexed { idx, seg ->
                renderSegment(seg.ident.toString(), seg.arguments, isLast = idx == tailSegs.lastIndex)
            }
        val fullParts = (listOf(boundHead) + renderedTail).toMutableList()
        val lastRaw = tailSegs.last().ident.toString()
        val prevRaw = if (tailSegs.size >= 2) tailSegs[tailSegs.size - 2].ident.toString() else headIdent
        return finalizeMultiPartPath(
            fullParts = fullParts,
            prevRaw = prevRaw,
            lastRaw = lastRaw,
            asCallee = asCallee,
            allowImportedOrPrimStaticOwner = false,
        )
    }

    private fun tryRenderSingleValuePositionSegment(singleRaw: String): String? {
        val cleanSingle = singleRaw.removePrefix("r#")
        if (singleRaw == "Ok" && "Ok" !in inspector.declaredTypeNames && !emitter.hasDeclaredResultEnum()) {
            return if (emitter.usesTaggedCoreResult()) "{ ${emitter.sourceResultName()}.Ok(it) }" else "{ Result.success(it) }"
        }
        if (singleRaw == "Err" && "Err" !in inspector.declaredTypeNames && !emitter.hasDeclaredResultEnum()) {
            return if (emitter.usesTaggedCoreResult()) "{ ${emitter.sourceResultName()}.Err(it) }" else "{ Result.failure(it) }"
        }
        if (singleRaw == "Some" && "Some" !in inspector.declaredTypeNames && !emitter.hasDeclaredOptionEnum()) {
            return "{ it }"
        }
        if (singleRaw.first().isUpperCase() && !emitter.isScreamingSnakeIdent(singleRaw)) {
            val rawKt = rustTypeIdentToKotlin(singleRaw)
            val singleTypeIdent = emitter.samePackageUseRenames[rawKt] ?: rawKt
            tryRenderUnitStructOrVariantValue(singleTypeIdent, listOf(singleRaw))?.let { return it }
        }
        if (cleanSingle != "self" &&
            singleRaw.first().isLowerCase() &&
            cleanSingle !in emitter.activeGuardBindingSubstitutions &&
            scopeBuilder.resolveLocalKotlinName(cleanSingle) == null &&
            cleanSingle in inspector.declaredTopLevelFnRawNames &&
            cleanSingle !in inspector.declaredConstNames
        ) {
            val fnKt = inspector.collidingTopLevelForeignFnRenames[cleanSingle] ?: rustFunctionIdentToKotlin(singleRaw)
            return "::$fnKt"
        }
        if (cleanSingle == "drop" &&
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
                CoreIntrinsicLowering.registerCoreImport(emitter, "$coreNs.mem.drop")
            }
            return "{ drop(it) }"
        }
        return null
    }

    private fun finalizeMultiPartPath(
        fullParts: MutableList<String>,
        prevRaw: String,
        lastRaw: String,
        asCallee: Boolean,
        allowImportedOrPrimStaticOwner: Boolean,
    ): String {
        if (fullParts.size >= 2 && isKnownEnumVariantPair(prevRaw, lastRaw)) {
            val varKt = rustTypeIdentToKotlin(lastRaw)
            val prevRendered = fullParts[fullParts.lastIndex]
            val typeArgSuffix = prevRendered.indexOf('<').let { if (it >= 0) prevRendered.substring(it) else "" }
            fullParts[fullParts.lastIndex] = varKt + typeArgSuffix
            val joined = fullParts.joinToString(".")
            if (!asCallee && prevRaw.first().isUpperCase()) {
                val enumKt = selfBaseOrTypeIdent(prevRaw)
                val arity = inspector.tupleEnumVariantArity[enumKt to varKt]
                if (arity != null && arity > 0) return renderTupleVariantConstructorLambda(joined, arity)
                if (enumKt.isNotEmpty() && emitter.isGenericUnitEnumVariant("$enumKt.$varKt")) return "$joined()"
            }
            return joined
        }
        val joined = fullParts.joinToString(".")
        if (!asCallee && fullParts.size >= 2) {
            if (prevRaw.first().isUpperCase() && lastRaw.first().isUpperCase() && !emitter.isScreamingSnakeIdent(lastRaw)) {
                val enumKt = selfBaseOrTypeIdent(prevRaw)
                val varKt = rustTypeIdentToKotlin(lastRaw)
                val arity = inspector.tupleEnumVariantArity[enumKt to varKt]
                if (arity != null && arity > 0) return renderTupleVariantConstructorLambda(joined, arity)
            }
            val validStaticOwner =
                prevRaw.first().isUpperCase() ||
                    (allowImportedOrPrimStaticOwner && (prevRaw in PRIMITIVE_TYPE_NAMES || emitter.resolveImportedUppercaseTypeBinding(prevRaw) != null))
            val isStaticFnRef =
                validStaticOwner &&
                    lastRaw.first().isLowerCase() &&
                    !lastRaw.all { it.isUpperCase() || it.isDigit() || it == '_' }
            if (isStaticFnRef) {
                return renderValuePositionStaticFnRef(
                    prevRaw = prevRaw,
                    ownerPath = fullParts.dropLast(1).joinToString("."),
                    methodKt = fullParts.last(),
                )
            }
        }
        return joined
    }

    private fun renderSegment(
        rawIdent: String,
        arguments: PathArguments,
        isLast: Boolean,
        isSingleSegment: Boolean = false,
        asCallee: Boolean = false,
    ): String {
        val cleanIdent = rawIdent.removePrefix("r#")
        val importedTypeKotlinName = emitter.resolveImportedUppercaseTypeBinding(rawIdent)
        val localKotlinName = scopeBuilder.resolveLocalKotlinName(cleanIdent)
        val rawTypeKt = rustTypeIdentToKotlin(rawIdent)
        val isSingleCalleeUnshadowed =
            isSingleSegment && isLast && asCallee && cleanIdent !in inspector.declaredTopLevelFnRawNames && localKotlinName == null
        val base =
            when {
                rawIdent == "self" -> scopeBuilder.resolveLocalKotlinName("self") ?: "this"
                rawIdent == "Self" -> selfBase ?: "Self"
                isSingleSegment && isLast && arguments is PathArguments.None && !asCallee && localKotlinName != null -> localKotlinName
                isLast && emitter.isScreamingSnakeIdent(rawIdent) ->
                    if (cleanIdent in inspector.declaredTopLevelFnRawNames ||
                        (asCallee && cleanIdent !in inspector.declaredConstNames && cleanIdent !in inspector.declaredTypeNames)
                    ) {
                        inspector.collidingTopLevelForeignFnRenames[cleanIdent] ?: rustFunctionIdentToKotlin(rawIdent)
                    } else {
                        inspector.declaredConstNames[cleanIdent] ?: rustConstIdentToKotlin(rawIdent)
                    }
                isLast &&
                    arguments is PathArguments.None &&
                    localKotlinName == null &&
                    cleanIdent !in emitter.activeGuardBindingSubstitutions &&
                    cleanIdent in inspector.declaredConstNames -> inspector.declaredConstNames.getValue(cleanIdent)
                !isLast && rawIdent in PRIMITIVE_TYPE_NAMES ->
                    if (rawIdent == "f32") emitter.kotlinF32TypeName() else rawTypeKt
                rawIdent.first().isUpperCase() -> {
                    val ktSym = if (isSingleSegment) (emitter.samePackageUseRenames[rawTypeKt] ?: rawTypeKt) else rawTypeKt
                    if (isSingleSegment && ktSym in emitter.activeTypeParamNames && ktSym in inspector.enumVariantSimpleNames) {
                        val ownerEnum =
                            selfSimpleBase?.takeIf { "$it.$ktSym" in inspector.enumVariantFieldTypes || ktSym in inspector.enumVariantSimpleNames }
                                ?: inspector.enumVariantFieldTypes.keys
                                    .firstOrNull { it.endsWith(".$ktSym") && '.' in it }
                                    ?.substringBeforeLast('.')
                        if (ownerEnum != null && ownerEnum !in emitter.activeTypeParamNames) "$ownerEnum.$ktSym" else ktSym
                    } else {
                        ktSym
                    }
                }
                (!isLast || isSingleCalleeUnshadowed) && rawTypeKt in inspector.declaredTypeNames && rawTypeKt !in inspector.typeAliasTargets ->
                    rawTypeKt
                (!isLast || isSingleCalleeUnshadowed) && importedTypeKotlinName != null -> importedTypeKotlinName
                !isLast && scopeBuilder.isImportedModuleInScope(rawIdent) ->
                    scopeBuilder.resolveModuleBinding(rawIdent)?.kotlinName ?: rustModuleIdentToKotlin(rawIdent)
                (!isLast || (asCallee && isSingleSegment)) &&
                    rawIdent.first().isLowerCase() &&
                    rawIdent !in PRIMITIVE_TYPE_NAMES &&
                    rawIdent != "self" &&
                    !scopeBuilder.isImportedModuleInScope(rawIdent) &&
                    localKotlinName == null &&
                    cleanIdent !in inspector.declaredTopLevelFnRawNames &&
                    (
                        rawTypeKt in inspector.declaredTypeNames ||
                            rawTypeKt in emitter.importedSimpleNameToPkg ||
                            rawTypeKt in headerContext.crateSymbolSubpackages
                    ) -> {
                    emitter.ensureCrateSubpackageTypeImport(rawTypeKt)
                    rawTypeKt
                }
                isSingleSegment ->
                    when {
                        asCallee && emitter.activeLocalForeignFnRenames[cleanIdent] != null ->
                            emitter.activeLocalForeignFnRenames.getValue(cleanIdent)
                        arguments is PathArguments.None && cleanIdent in emitter.activeGuardBindingSubstitutions ->
                            emitter.activeGuardBindingSubstitutions.getValue(cleanIdent)
                        localKotlinName != null -> localKotlinName
                        else -> inspector.collidingTopLevelForeignFnRenames[cleanIdent] ?: rustValueIdentToKotlin(rawIdent)
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
                localKotlinName == null &&
                cleanIdent !in emitter.activeGuardBindingSubstitutions &&
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
                val primaryPkg = emitter.resolveCrateSubpackageTargetPkg(base)
                val altPkg = if (primaryPkg == null && constCandidate != null) emitter.resolveCrateSubpackageTargetPkg(constCandidate) else null
                val effectiveSym = if (primaryPkg != null) base else (constCandidate ?: base)
                val targetPkg = primaryPkg ?: altPkg
                if (targetPkg != null) {
                    emitter.registerCrateSubpackageSymbolImport(effectiveSym, targetPkg, qualifyOnCollision = true)
                } else {
                    base
                }
            } else {
                base
            }
        val typeArgs = if (isLast) renderAngleTypeArgs(arguments, rawIdent) else ""
        return "$resolvedBase$typeArgs"
    }

    private fun renderPrimitiveAssociatedConst(
        rawSegNames: List<String>,
        hasTrailingArgs: Boolean,
    ): String? {
        if (hasTrailingArgs || rawSegNames.isEmpty()) return null
        if (rawSegNames.size == 1 && scopeBuilder.resolveLocalKotlinName(rawSegNames[0]) != null) return null
        val mappedSelfSegs =
            when {
                rawSegNames.size == 2 && rawSegNames[0] == "Self" && selfBase != null -> {
                    val mappedPrim = SELF_TYPE_TO_RUST_PRIM[selfBase]
                    if (mappedPrim != null) listOf(mappedPrim, rawSegNames[1]) else rawSegNames
                }
                rawSegNames.size == 1 &&
                    emitter.isScreamingSnakeIdent(rawSegNames[0]) &&
                    scopeBuilder.resolveModuleBinding(rawSegNames[0]) == null -> {
                    val globPrims = collectGlobImportedFloatConstsPrims()
                    if (globPrims.size == 1) listOf(globPrims.single(), "consts", rawSegNames[0]) else rawSegNames
                }
                else -> rawSegNames
            }
        val resolvedFirst = scopeBuilder.resolveModuleBinding(mappedSelfSegs.first())?.importedPath?.split(".")
        val withImportedPrefix =
            if (resolvedFirst != null && (resolvedFirst.firstOrNull() == "core" || resolvedFirst.firstOrNull() == "std")) {
                resolvedFirst + mappedSelfSegs.drop(1)
            } else {
                mappedSelfSegs
            }
        val norm =
            if (withImportedPrefix.size >= 3 && (withImportedPrefix[0] == "core" || withImportedPrefix[0] == "std")) {
                withImportedPrefix.drop(1)
            } else {
                withImportedPrefix
            }
        if (norm.size == 2) {
            val prim = norm[0]
            val c = norm[1]
            if (prim == "f32") {
                val f32Name = emitter.kotlinF32TypeName()
                return YamlRuleCatalogs.PathsConstsAndCAbi.f32AssocConstTemplates[c]?.render { if (it == "typeName") f32Name else null }
            }
            if (prim == "f64") {
                return YamlRuleCatalogs.PathsConstsAndCAbi.f64AssocConstTemplates[c]?.render { if (it == "typeName") "Double" else null }
            }
            if (prim == "f16" || prim == "f128") {
                if (c !in F16_F128_ASSOC_CONSTS) return null
                val owner = if (prim == "f16") "F16" else "F128"
                val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
                CoreIntrinsicLowering.registerCoreImport(emitter, "$coreNs.$owner")
                return "$owner.$c"
            }
            val fixedBits = INT_BITS_BY_PRIM[prim]
            if (fixedBits != null || prim == "usize" || prim == "isize") {
                if (c == "BITS") return fixedBits ?: if (headerContext.rawAbiMode) "64u" else "32u"
                if (c == "MIN" || c == "MAX") {
                    return when (prim) {
                        "usize" ->
                            when {
                                headerContext.rawAbiMode -> "ULong.${c}_VALUE"
                                c == "MIN" -> "0"
                                else -> "Int.MAX_VALUE"
                            }
                        "isize" -> if (headerContext.rawAbiMode) "Long.${c}_VALUE" else "Int.${c}_VALUE"
                        "u128" -> "U128.${c}_VALUE"
                        "i128" -> "I128.${c}_VALUE"
                        else -> "${rustTypeIdentToKotlin(prim)}.${c}_VALUE"
                    }
                }
            }
            val primKt = rustTypeIdentToKotlin(prim)
            val isPrimitiveAliasOrLower =
                prim.firstOrNull()?.isLowerCase() == true ||
                    primKt in inspector.typeAliasTargets ||
                    emitter.scopedAbiKeysForRenderedType(primKt).any { it in headerContext.crateNumericTypeKinds }
            if (isPrimitiveAliasOrLower && primKt !in emitter.activeTypeParamNames && (c == "MIN" || c == "MAX" || c == "BITS")) {
                val kind = emitter.resolveNumericKind(primKt)
                val mapped = resolvedPrimOwnerAndBits(kind)
                if (mapped != null) {
                    val (ktOwner, bitsStr) = mapped
                    if (c == "BITS") return bitsStr
                    return if (c == "MIN" && (kind == NumericKind.F32 || kind == NumericKind.F64)) {
                        "(-$ktOwner.MAX_VALUE)"
                    } else {
                        "$ktOwner.${c}_VALUE"
                    }
                }
            }
        }
        if (norm.size == 3 && norm[1] == "consts") {
            val mathPair = RUST_FLOAT_MATH_CONSTANTS[norm[2]] ?: return null
            return when (norm[0]) {
                "f32" -> mathPair.first
                "f16" -> "F16.fromFloat(${mathPair.first})"
                "f64" -> mathPair.second
                "f128" -> "F128.fromDouble(${mathPair.second})"
                else -> null
            }
        }
        return null
    }

    private fun collectGlobImportedFloatConstsPrims(): Set<String> {
        val globPrims = linkedSetOf<String>()
        val leaves = mutableListOf<UseLeaf>()
        for (u in inspector.allUseItems) {
            collectUseLeaves(u.tree, emptyList(), leaves)
        }
        for (leaf in leaves) {
            val segs = leaf.segments.map { it.removePrefix("r#") }
            if (segs.size == 4 &&
                (segs[0] == "core" || segs[0] == "std") &&
                segs[1] in FLOAT_PRIMS &&
                segs[2] == "consts" &&
                segs[3] == "*"
            ) {
                globPrims += segs[1]
            }
        }
        return globPrims
    }

    private fun renderValuePositionStaticFnRef(
        prevRaw: String,
        ownerPath: String,
        methodKt: String,
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
                return if (emitter.usesTaggedCoreResult()) {
                    val res = emitter.sourceResultName()
                    "{ (it.$strParseMethod?.let { v -> $res.Ok(v) } ?: $res.Err(\"failed to parse $typeArgContent\")) }"
                } else {
                    "{ (it.$strParseMethod?.let { v -> Result.success(v) } ?: Result.failure(IllegalArgumentException(\"failed to parse $typeArgContent\"))) }"
                }
            }
            return "{ it.$methodKt() }"
        }
        val ownerKt = selfBaseOrTypeIdent(cleanPrev)
        val isTraitOwner =
            ownerKt.isNotEmpty() &&
                (ownerKt in inspector.declaredTraitsByName || ownerKt in headerContext.crateTraitSupertraits || ownerKt.endsWith("Ext"))
        if (isTraitOwner) return "{ $ownerPath.$methodKt(it) }"
        val isInstanceMethodRef =
            (
                baseMethodKt in inspector.instanceMethodsByType[ownerKt].orEmpty() &&
                    baseMethodKt !in inspector.companionMethodsByType[ownerKt].orEmpty()
            ) ||
                (ownerKt == "PoisonError" && baseMethodKt == "intoInner") ||
                (baseMethodKt in VALUE_POS_INSTANCE_CONV_METHODS && ownerKt !in inspector.declaredTypeNames)
        if (isInstanceMethodRef) return "{ it.$methodKt() }"
        if ('<' in methodKt) return "{ $ownerPath.$methodKt(it) }"
        return "$ownerPath::$methodKt"
    }

    private fun isKnownEnumVariantPair(prevRaw: String, lastRaw: String): Boolean {
        val cleanPrev = prevRaw.removePrefix("r#")
        val cleanLast = lastRaw.removePrefix("r#")
        if (cleanPrev.isEmpty() || cleanLast.isEmpty() || cleanLast == "new" || cleanLast == "default") return false
        val enumKt = selfBaseOrTypeIdent(cleanPrev, stripOwnerQualification = true)
        if (enumKt.isEmpty()) return false
        if (enumKt == "Feature" &&
            cleanLast != "to_str" &&
            cleanLast != "from_str" &&
            !emitter.isScreamingSnakeIdent(cleanLast) &&
            (
                headerContext.packageName?.contains(".stddetect.") == true ||
                    emitter.importedSimpleNameToPkg["Feature"]?.contains(".detect.arch.") == true
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
        emitter.importedSimpleNameToPkg[enumKt]?.let { pkg ->
            val ns = emitter.currentNamespacePrefix()
            val sub = if (ns != null && pkg.startsWith("$ns.")) pkg.removePrefix("$ns.") else pkg.substringAfterLast('.')
            if (sub.isNotEmpty() && "$sub.$directKey" in headerContext.crateEnumVariantTypeArity) {
                return true
            }
        }
        return emitter.scopedAbiKeysForRenderedType(enumKt).any { "$it.$varKt" in headerContext.crateEnumVariantTypeArity }
    }

    companion object {
        private val SIZED_DIRECT_OWNER_CONSTS: Set<String>
            get() = YamlRuleCatalogs.TypeAndMethodPatterns.sizedCoreConstNames
        private val STDLIB_ROOT_CRATES: Set<String>
            get() = STDLIB_CRATE_ROOTS
        private val FLOAT_PRIMS: Set<String>
            get() = YamlRuleCatalogs.TypeAndMethodPatterns.floatSuffixNumericKinds.keys

        private fun resolvedPrimOwnerAndBits(kind: NumericKind?): Pair<String, String>? =
            kind?.let(YamlRuleCatalogs.PathsConstsAndCAbi.primOwnerAndBitsByKind::get)

        private fun renderTupleVariantConstructorLambda(ctorRef: String, arity: Int): String {
            if (arity == 1) return "{ $ctorRef(it) }"
            val pNames = (0 until arity).joinToString(", ") { "p$it" }
            return "{ $pNames -> $ctorRef($pNames) }"
        }
    }
}
