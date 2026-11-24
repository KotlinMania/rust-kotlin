package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.procmacro2.Delimiter
import io.github.kotlinmania.procmacro2.Group
import io.github.kotlinmania.procmacro2.TokenStream
import io.github.kotlinmania.procmacro2.TokenTree
import io.github.kotlinmania.rustkotlin.reused.starlark.AssignCount
import io.github.kotlinmania.syn.FnArg
import io.github.kotlinmania.syn.ForeignItem
import io.github.kotlinmania.syn.GenericParam
import io.github.kotlinmania.syn.Generics
import io.github.kotlinmania.syn.Item
import io.github.kotlinmania.syn.Lit
import io.github.kotlinmania.syn.Macro
import io.github.kotlinmania.syn.ModContent
import io.github.kotlinmania.syn.Path
import io.github.kotlinmania.syn.Signature
import io.github.kotlinmania.syn.StaticMutability
import io.github.kotlinmania.syn.SynType
import io.github.kotlinmania.syn.span

private val UNPORTED_QUICKCHECK_IDENTS =
    setOf(
        "quickcheck",
        "QuickCheck",
        "StdGen",
        "TestResult",
        "Arbitrary",
    )

private val UNPORTED_RAND_TEST_IDENTS =
    setOf(
        "rand",
        "Rng",
        "RngCore",
        "SeedableRng",
        "SmallRng",
        "StdRng",
        "ThreadRng",
        "thread_rng",
    )

internal fun KotlinJ2KEmitter.itemDependsOnUnportedQuickcheckOrRand(item: Item): Boolean {
    val unportedQuickcheck = "quickcheck" !in headerContext.crateNamespaces
    val unportedRand = "rand" !in headerContext.crateNamespaces
    if (!unportedQuickcheck && !unportedRand) return false
    if (item is Item.Mod || item is Item.Use) return false
    if (item is Item.Macro) {
        val macroName =
            item.mac.path.segments
                .toList()
                .lastOrNull()
                ?.ident
                ?.toString()
                ?.removePrefix("r#")
        if (unportedQuickcheck && macroName == "quickcheck") return true
        if (unportedRand && macroName == "rand") return true
    }
    val inTestContext = isQuickcheckOrRandTestContext(item)
    val importedQuickcheckGen =
        unportedQuickcheck &&
            inspector.allUseItems.any { useItem ->
                val leaves = mutableListOf<UseLeaf>()
                collectUseLeaves(useItem.tree, emptyList(), leaves)
                leaves.any { leaf ->
                    leaf.segments.firstOrNull()?.removePrefix("r#") == "quickcheck" &&
                        (leaf.segments.lastOrNull() == "*" || leaf.segments.lastOrNull()?.removePrefix("r#") == "Gen")
                }
            }
    return UnportedTestDepTokenScanner(
        trees = item.toTokenStream().toList(),
        unportedQuickcheck = unportedQuickcheck,
        unportedRand = unportedRand,
        inTestContext = inTestContext,
        importedQuickcheckGen = importedQuickcheckGen,
    ).matches()
}

private class UnportedTestDepTokenScanner(
    trees: List<TokenTree>,
    private val unportedQuickcheck: Boolean,
    private val unportedRand: Boolean,
    private val inTestContext: Boolean,
    private val importedQuickcheckGen: Boolean,
) : RustTokenCursor(trees) {
    fun matches(): Boolean {
        for (idx in 0 until size) {
            when (val tt = trees[idx]) {
                is TokenTree.Ident -> {
                    val raw = cleanIdentAt(idx).orEmpty()
                    val nextIsPathSep = punctCharAt(idx + 1) == ':' && punctCharAt(idx + 2) == ':'
                    if (unportedQuickcheck &&
                        (
                            (raw == "quickcheck" && nextIsPathSep) ||
                                (inTestContext && raw in UNPORTED_QUICKCHECK_IDENTS) ||
                                (inTestContext && importedQuickcheckGen && raw == "Gen")
                        )
                    ) {
                        return true
                    }
                    if (unportedRand &&
                        ((raw == "rand" && nextIsPathSep) || (inTestContext && raw in UNPORTED_RAND_TEST_IDENTS))
                    ) {
                        return true
                    }
                }
                is TokenTree.Group ->
                    if (UnportedTestDepTokenScanner(
                            trees = tt.value.stream().toList(),
                            unportedQuickcheck = unportedQuickcheck,
                            unportedRand = unportedRand,
                            inTestContext = inTestContext,
                            importedQuickcheckGen = importedQuickcheckGen,
                        ).matches()
                    ) {
                        return true
                    }
                else -> {}
            }
        }
        return false
    }
}

private const val OMITTED_QUICKCHECK_OR_RAND_COMMENT: String =
    "// Omitted quickcheck/rand property test items pending quickcheck-kotlin / rand-kotlin port (Hermes Kanban card 19971811)."

private fun KotlinJ2KEmitter.isQuickcheckOrRandTestContext(item: Item? = null): Boolean =
    headerContext.portLintDirective == "tests" ||
        headerContext.testClassName != null ||
        currentModPathPrefix.any { it == "test" || it == "tests" || it == "stdtests" } ||
        (item != null && hasTestAttribute(item.attributes))

private fun KotlinJ2KEmitter.declaredOmittedQuickcheckIdents(item: Item): Set<String> =
    when (item) {
        is Item.Fn ->
            setOf(
                item.sig.ident
                    .toString()
                    .removePrefix("r#"),
            )
        is Item.Struct -> setOf(item.ident.toString().removePrefix("r#"))
        is Item.Enum -> setOf(item.ident.toString().removePrefix("r#"))
        is Item.Union -> setOf(item.ident.toString().removePrefix("r#"))
        is Item.ItemType -> setOf(item.ident.toString().removePrefix("r#"))
        is Item.Const -> setOf(item.ident.toString().removePrefix("r#"))
        is Item.Static -> setOf(item.ident.toString().removePrefix("r#"))
        is Item.Impl -> {
            val unportedQuickcheck = "quickcheck" !in headerContext.crateNamespaces
            val unportedRand = "rand" !in headerContext.crateNamespaces
            val traitLast =
                item.traitPath
                    ?.path
                    ?.segments
                    ?.toList()
                    ?.lastOrNull()
                    ?.ident
                    ?.toString()
                    ?.removePrefix("r#")
            if ((unportedQuickcheck && traitLast == "Arbitrary") ||
                (unportedRand && traitLast in UNPORTED_RAND_TEST_IDENTS)
            ) {
                val selfRaw =
                    (unwrapSynType(item.selfType) as? SynType.Path)
                        ?.takeIf { it.qself == null }
                        ?.path
                        ?.segments
                        ?.toList()
                        ?.lastOrNull()
                        ?.ident
                        ?.toString()
                        ?.removePrefix("r#")
                listOfNotNull(selfRaw, selfRaw?.let(::rustTypeIdentToKotlin)).toSet()
            } else {
                emptySet()
            }
        }
        else -> emptySet()
    }

internal fun KotlinJ2KEmitter.computeTransitivelyOmittedQuickcheckOrRandIdents(
    items: List<Item>,
    parentOmitted: Set<String>,
): Set<String> {
    if ("quickcheck" in headerContext.crateNamespaces && "rand" in headerContext.crateNamespaces) {
        return emptySet()
    }
    val omitted = parentOmitted.toMutableSet()
    var changed = true
    while (changed) {
        changed = false
        for (item in items) {
            if (item is Item.Mod || item is Item.Use || itemHasDocOnlyCfg(item)) continue
            if (!isQuickcheckOrRandTestContext(item)) continue
            val directHit = itemDependsOnUnportedQuickcheckOrRand(item)
            val declared = declaredOmittedQuickcheckIdents(item)
            val transitiveHit =
                !directHit &&
                    (
                        declared.any { it in omitted } ||
                            RustTokenCursor.containsAnyIdentRecursive(item.toTokenStream(), omitted)
                    )
            if (directHit || transitiveHit) {
                if (omitted.addAll(declared)) {
                    changed = true
                }
            }
        }
    }
    return omitted
}

internal fun KotlinJ2KEmitter.shouldOmitItemForUnportedQuickcheckOrRand(item: Item): Boolean {
    if (itemDependsOnUnportedQuickcheckOrRand(item)) return true
    if (item is Item.Mod || item is Item.Use) return false
    if (!isQuickcheckOrRandTestContext(item) || currentOmittedTestIdents.isEmpty()) return false
    return declaredOmittedQuickcheckIdents(item).any { it in currentOmittedTestIdents } ||
        RustTokenCursor.containsAnyIdentRecursive(item.toTokenStream(), currentOmittedTestIdents)
}

internal fun indentBlockByFourSpaces(text: String): String =
    text.lines().joinToString("\n") { line ->
        if (line.isEmpty()) "" else "    $line"
    }

internal fun KotlinJ2KEmitter.allocateTopLevelTestSuiteClassName(
    candidate: String,
    scopeSignatures: MutableMap<String, Int>,
): String {
    var resolved = candidate
    var suffix = 2
    while (resolved in inspector.declaredTypeNames ||
        "type:$resolved" in scopeSignatures ||
        resolved in scopeSignatures
    ) {
        resolved = "$candidate$suffix"
        suffix++
    }
    scopeSignatures["type:$resolved"] = 1
    scopeSignatures[resolved] = 1
    return resolved
}

internal fun KotlinJ2KEmitter.resolveModTestSuiteCandidateName(): String {
    val fileTestBase = headerContext.testClassName
    val nonGenericSegs =
        currentModPathPrefix.filterNot {
            val lower = it.lowercase()
            lower == "test" || lower == "tests"
        }
    if (fileTestBase != null) {
        if (nonGenericSegs.isEmpty()) return fileTestBase
        val suffixKind = if (fileTestBase.endsWith("InlineTests")) "InlineTests" else "Tests"
        val stem = fileTestBase.removeSuffix("InlineTests").removeSuffix("Tests").ifEmpty { fileTestBase }
        val modSuffix = nonGenericSegs.joinToString("") { rustTypeIdentToKotlin(it) }
        return "$stem$modSuffix$suffixKind"
    }
    if (nonGenericSegs.isEmpty()) return "InlineTests"
    return nonGenericSegs.joinToString("") { rustTypeIdentToKotlin(it) } + "Tests"
}

internal fun KotlinJ2KEmitter.renderItemMod(
    item: Item.Mod,
    indent: String,
    scopeSignatures: MutableMap<String, Int> = topLevelSignatureCounts,
    isLocalItem: Boolean = false,
): String {
    val content = item.content as? ModContent.Inline ?: return ""
    if (isSealedMarkerInlineMod(item)) {
        return ""
    }
    val modRawName = item.ident.toString().removePrefix("r#")
    val prevModPath = currentModPathPrefix
    currentModPathPrefix = prevModPath + modRawName
    scopeBuilder.enterModuleSubscope()
    return try {
        val prevOmittedTestIdents = currentOmittedTestIdents
        try {
            val visibleItems = content.items.filterNot { itemHasDocOnlyCfg(it) }
            currentOmittedTestIdents =
                computeTransitivelyOmittedQuickcheckOrRandIdents(visibleItems, prevOmittedTestIdents)
            val modAttachableNames =
                visibleItems
                    .filterNot { shouldOmitItemForUnportedQuickcheckOrRand(it) }
                    .mapNotNull { sub ->
                        when (sub) {
                            is Item.Struct -> rustTypeIdentToKotlin(sub.ident.toString())
                            is Item.Enum -> rustTypeIdentToKotlin(sub.ident.toString())
                            is Item.Union -> rustTypeIdentToKotlin(sub.ident.toString())
                            else -> null
                        }
                    }.toSet()
            val modLocalTypeImpls = linkedMapOf<String, MutableList<Item.Impl>>()
            val standaloneModItems = mutableListOf<Item>()
            val nonTestOutputs = mutableListOf<String>()
            val modTestFns = mutableListOf<String>()
            val nestedModOutputs = mutableListOf<String>()
            val modTestSignatures = linkedMapOf<String, Int>()
            var emittedOmissionComment = false
            for (sub in visibleItems) {
                if (sub is Item.Use) {
                    registerUseItemInScope(sub)
                    continue
                }
                if (shouldOmitItemForUnportedQuickcheckOrRand(sub)) {
                    if (!emittedOmissionComment) {
                        nonTestOutputs += "$indent$OMITTED_QUICKCHECK_OR_RAND_COMMENT"
                        emittedOmissionComment = true
                    }
                    continue
                }
                if (sub is Item.Impl) {
                    val ownerType = extractDirectOwnerTypeName(sub.selfType, sub.generics)
                    if (ownerType != null && ownerType in modAttachableNames) {
                        modLocalTypeImpls.getOrPut(ownerType) { mutableListOf() }.add(sub)
                        continue
                    }
                }
                standaloneModItems += sub
            }
            for (sub in standaloneModItems) {
                val isTestFn = sub is Item.Fn && hasTestAttribute(sub.attrs)
                val targetSignatures = if (isTestFn) modTestSignatures else scopeSignatures
                val r = renderItem(sub, indent, modLocalTypeImpls, isLocalItem = isLocalItem, scopeSignatures = targetSignatures).trimEnd()
                if (r.isBlank()) continue
                when {
                    sub is Item.Mod -> nestedModOutputs += r
                    isTestFn && r.lines().any { it.trimStart() == "@kotlin.test.Test" } -> modTestFns += r
                    else -> nonTestOutputs += r
                }
            }
            if (modTestFns.isNotEmpty()) {
                if (indent.isEmpty()) {
                    val suiteName = allocateTopLevelTestSuiteClassName(resolveModTestSuiteCandidateName(), scopeSignatures)
                    val suiteBody = modTestFns.joinToString("\n\n") { indentBlockByFourSpaces(it) }
                    nonTestOutputs += "public class $suiteName {\n$suiteBody\n}"
                } else {
                    nonTestOutputs += modTestFns
                }
            }
            val combined = mutableListOf<String>()
            for (part in nonTestOutputs + nestedModOutputs) {
                if (part.trim() == OMITTED_QUICKCHECK_OR_RAND_COMMENT) {
                    if (emittedOmissionComment && combined.any { it.trim() == OMITTED_QUICKCHECK_OR_RAND_COMMENT }) {
                        continue
                    }
                    emittedOmissionComment = true
                }
                combined += part
            }
            combined.joinToString("\n\n")
        } finally {
            currentOmittedTestIdents = prevOmittedTestIdents
        }
    } finally {
        currentModPathPrefix = prevModPath
        scopeBuilder.exitModuleSubscope()
    }
}

internal fun KotlinJ2KEmitter.renderItemMacro(
    item: Item.Macro,
    indent: String,
    isLocalItem: Boolean = false,
): String {
    if (item.mac.path.isIdent("macro_rules")) {
        return ""
    }
    val lastMacroIdent =
        item.mac.path.segments
            .last()
            ?.ident
            ?.toString()
    if (lastMacroIdent == "include") {
        return ""
    }
    if (isWindowsLinkMacroPath(item.mac.path)) {
        return renderWindowsLinkMacro(item, indent)
    }
    if (!isLocalItem) {
        ffiCollector.recordTopLevelInlineMacro(item.mac)
        return ""
    }
    val rendered = renderMacroInvocationAsStmtOrExpr(item.mac, indent, selfTypeName = null)
    return if (rendered.isBlank()) "" else "$indent$rendered"
}

internal fun KotlinJ2KEmitter.isWindowsLinkMacroPath(path: Path): Boolean {
    val segs = path.segments.toList().map { it.ident.toString() }
    return (segs.size == 2 && (segs[0] == "windows_link" || segs[0] == "windows_targets") && segs[1] == "link") ||
        segs.lastOrNull() == "link"
}

internal fun KotlinJ2KEmitter.renderWindowsLinkMacro(item: Item.Macro, indent: String): String {
    val macTokens = item.mac.tokens
    val trees = macTokens.clone().toList()
    val fnIdx =
        trees.indexOfFirst { tt ->
            tt is TokenTree.Ident && tt.value.toString() == "fn"
        }
    if (fnIdx < 0) return ""
    val leadingStrings = mutableListOf<String>()
    for (i in 0 until fnIdx) {
        val tt = trees[i]
        if (tt is TokenTree.Literal) {
            val lit = Lit.new(tt.value)
            if (lit is Lit.Str) {
                leadingStrings += lit.value.value()
            }
        }
    }
    val dllName = leadingStrings.getOrNull(0).orEmpty()
    val customSymbol = leadingStrings.getOrNull(2)
    val fnTokens = trees.subList(fnIdx, trees.size)
    val pubTokens = TokenStream.fromString("pub").getOrThrow().toList()
    val hasTrailingSemi = (fnTokens.lastOrNull() as? TokenTree.Punct)?.value?.asChar() == ';'
    val semiTokens = if (hasTrailingSemi) emptyList() else TokenStream.fromString(";").getOrThrow().toList()
    val braceBody = TokenTree.Group(Group(Delimiter.Brace, TokenStream.fromTokenTrees(pubTokens + fnTokens + semiTokens)))
    val externPrefix = TokenStream.fromString("extern \"C\"").getOrThrow().toList()
    val wrappedStream = TokenStream.fromTokenTrees(externPrefix + braceBody)
    val parsedFile =
        io.github.kotlinmania.syn
            .parse2(io.github.kotlinmania.syn.FileParse::parse, wrappedStream)
            .getOrNull() ?: return ""
    val foreignMod = parsedFile.items.firstOrNull() as? Item.ForeignMod ?: return ""
    val foreignFn = foreignMod.items.firstOrNull() as? ForeignItem.Fn ?: return ""

    val foreignIdent = foreignFn.sig.ident.toString()
    val rawFnName = foreignIdent.removePrefix("r#")
    val fnName = rustFunctionIdentToKotlin(rawFnName)
    val exportedSymbol = customSymbol ?: rawFnName
    val trivia = renderLeadingTrivia(item, indent)
    val doc = renderKDocFromAttributes(item.attrs + foreignFn.attrs, indent)

    val interopParams = mutableListOf<InteropParamSpec>()
    scopeBuilder.enterDef()
    val (sig, retRendered) =
        try {
            renderForeignFunctionExpectSignature(
                sig = foreignFn.sig,
                visPrefix = "public ",
                fnName = fnName,
                scopeSignatures = topLevelSignatureCounts,
                interopParamsOut = interopParams,
            )
        } finally {
            scopeBuilder.exitDef()
        } ?: return ""
    val retSynType = extractReturnSynType(foreignFn.sig.output)
    ffiCollector.recordForeignFunction(
        visibilityPrefix = "public ",
        kotlinName = fnName,
        rawRustName = exportedSymbol,
        attrs = item.attrs + foreignFn.attrs,
        params = interopParams,
        kotlinReturnType = retRendered,
        returnSynType = retSynType,
        isVariadic = foreignFn.sig.variadic != null,
        returnDefaultExpr = if (retRendered == "Unit") "" else renderFallbackDefaultForRenderedType(retRendered),
        dllName = dllName,
    )
    return "$trivia$doc$indent$sig"
}

internal fun KotlinJ2KEmitter.renderImplOrTraitMacroMembers(
    mac: Macro,
    indent: String,
    selfTypeName: String?,
    ownerGenerics: Generics?,
    scopeSignatures: MutableMap<String, Int>,
    asTopLevelExtension: Boolean,
    isInterface: Boolean,
    isLocalItem: Boolean = false,
): List<String> {
    val macroName =
        mac.path.segments
            .last()
            ?.ident
            ?.toString()
    if (macroName == "forward_to_deserialize_any") {
        return renderForwardToDeserializeAnyMembers(
            mac = mac,
            indent = indent,
            selfTypeName = selfTypeName,
            ownerGenerics = ownerGenerics,
            scopeSignatures = scopeSignatures,
            asTopLevelExtension = asTopLevelExtension,
            isInterface = isInterface,
            isLocalItem = isLocalItem,
        )
    }
    if (isInterface || asTopLevelExtension) {
        return emptyList()
    }
    val expanded = renderMacroInvocationAsStmtOrExpr(mac, "$indent    ", selfTypeName)
    return if (expanded.isBlank()) {
        emptyList()
    } else {
        listOf("${indent}init {\n$indent    $expanded\n$indent}")
    }
}

internal fun KotlinJ2KEmitter.renderForwardToDeserializeAnyMembers(
    mac: Macro,
    indent: String,
    selfTypeName: String?,
    ownerGenerics: Generics?,
    scopeSignatures: MutableMap<String, Int>,
    asTopLevelExtension: Boolean,
    isInterface: Boolean,
    isLocalItem: Boolean,
): List<String> {
    val keys =
        RustTokenCursor(mac.tokens.clone().toList())
            .collectTopLevelIdentsOutsideAngles()
            .filter { it in FORWARD_DESERIALIZE_KEYS }
    if (keys.isEmpty()) return emptyList()

    val ownerParams = ownerGenerics?.params?.toList().orEmpty()
    val ownerTypeParamNames =
        ownerParams
            .mapNotNull { param ->
                (param as? GenericParam.TypeParam)?.ident?.toString()?.let(::rustTypeIdentToKotlin)
            }.toSet()
    val visitorParam = if ("V" in ownerTypeParamNames) "W" else "V"
    val ownerTypeParamsRendered =
        if (asTopLevelExtension) {
            renderTypeParametersFromParams(ownerParams, selfTypeName)
                .removePrefix("<")
                .removeSuffix(">")
                .takeIf { it.isNotEmpty() }
        } else {
            null
        }
    val typeParamsStr =
        if (ownerTypeParamsRendered != null) {
            "<$ownerTypeParamsRendered, $visitorParam : Visitor>"
        } else {
            "<$visitorParam : Visitor>"
        }
    val whereClause =
        if (asTopLevelExtension) {
            renderCombinedWhereClause(
                ownerWhere = ownerGenerics?.whereClause,
                fnWhere = null,
                params = ownerParams,
                selfTypeName = selfTypeName,
                extraDeclaredNames = setOf(visitorParam),
            )
        } else {
            ""
        }

    val vis = if (isLocalItem) "" else "public "
    val openMod = if (!isInterface && !asTopLevelExtension && !isLocalItem && indent.isNotEmpty()) "open " else ""
    val receiverPrefix = if (asTopLevelExtension && !selfTypeName.isNullOrBlank()) "$selfTypeName." else ""
    val receiverKey = computeReceiverSignatureKey(receiverPrefix)
    val errorType = currentImplAssocTypes["Error"]?.let { renderType(it, selfTypeName) } ?: "Error"

    val out = mutableListOf<String>()
    for (key in keys) {
        val fnName = rustFunctionIdentToKotlin("deserialize_$key")
        val (extraParams, extraErased) = forwardDeserializeExtraParams(key)
        val allParams = extraParams + "visitor: $visitorParam"
        val allErased = extraErased + visitorParam
        val sigKey = "$receiverKey$fnName(${allErased.joinToString(",")})"
        val seenCount = scopeSignatures[sigKey] ?: 0
        scopeSignatures[sigKey] = seenCount + 1
        if (seenCount > 0) continue

        val sigHeader =
            "${vis}${openMod}fun $typeParamsStr ${receiverPrefix}$fnName(${allParams.joinToString(", ")}): Result<Any?, $errorType>$whereClause"
        out += "$indent$sigHeader {\n$indent    return this.deserializeAny(visitor)\n$indent}"
    }
    return out
}

internal fun KotlinJ2KEmitter.forwardDeserializeExtraParams(key: String): Pair<List<String>, List<String>> =
    when (key) {
        "unit_struct", "newtype_struct" -> listOf("name: String") to listOf("String")
        "tuple" -> listOf("len: Int") to listOf("Int")
        "tuple_struct" -> listOf("name: String", "len: Int") to listOf("String", "Int")
        "struct" -> listOf("name: String", "fields: List<String>") to listOf("String", "List")
        "enum" -> listOf("name: String", "variants: List<String>") to listOf("String", "List")
        else -> emptyList<String>() to emptyList()
    }


internal data class ForeignFnRenderContext(
    val emitter: KotlinJ2KEmitter,
    val foreign: ForeignItem.Fn,
    val vis: String,
    val rawFnName: String,
    val fnName: String,
    val sig: String,
    val retRendered: String,
    val retDefault: String,
    val interopParams: List<InteropParamSpec>,
    val fnTrivia: String,
    val doc: String,
    val indent: String,
    val isLocalItem: Boolean,
    val modLinkLibrary: String?,
)

internal sealed class ForeignFnEmissionStrategy {
    abstract fun tryRender(ctx: ForeignFnRenderContext): String?

    object RustAllocShim : ForeignFnEmissionStrategy() {
        private val SHIM_NAMES =
            setOf(
                "__rust_alloc",
                "__rust_alloc_zeroed",
                "__rust_dealloc",
                "__rust_realloc",
                "__rust_no_alloc_shim_is_unstable",
                "__rust_no_alloc_shim_is_unstable_v2",
            )

        override fun tryRender(ctx: ForeignFnRenderContext): String? {
            if (ctx.rawFnName !in SHIM_NAMES) return null
            val emitter = ctx.emitter
            val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(emitter.headerContext)
            val pNames = ctx.interopParams.map { it.kotlinName }

            fun ptrArgToLong(idx: Int): String =
                if (TranslationSession.rawAbiMode) pNames[idx] else "${pNames[idx]}.rawAddress"

            fun alignArgToLong(idx: Int): String =
                if (ctx.interopParams.getOrNull(idx)?.kotlinType?.substringAfterLast('.') == "Alignment") {
                    "${pNames[idx]}.asUsize().toLong()"
                } else {
                    "${pNames[idx]}.toLong()"
                }

            fun wrapLongToRet(expr: String): String =
                if (TranslationSession.rawAbiMode) {
                    expr
                } else {
                    CoreIntrinsicLowering.registerCoreImport(emitter, "$coreNs.ptr.MutPtr")
                    "MutPtr($expr)"
                }

            val shimBody =
                when (ctx.rawFnName) {
                    "__rust_alloc" -> {
                        CoreIntrinsicLowering.registerCoreImport(emitter, "$coreNs.intrinsics.kmAlloc")
                        "= ${wrapLongToRet("kmAlloc(${pNames[0]}.toLong(), ${alignArgToLong(1)})")}"
                    }
                    "__rust_alloc_zeroed" -> {
                        CoreIntrinsicLowering.registerCoreImport(emitter, "$coreNs.intrinsics.kmAllocZeroed")
                        "= ${wrapLongToRet("kmAllocZeroed(${pNames[0]}.toLong(), ${alignArgToLong(1)})")}"
                    }
                    "__rust_dealloc" -> {
                        CoreIntrinsicLowering.registerCoreImport(emitter, "$coreNs.intrinsics.kmDealloc")
                        "= kmDealloc(${ptrArgToLong(0)}, ${pNames[1]}.toLong(), ${alignArgToLong(2)})"
                    }
                    "__rust_realloc" -> {
                        CoreIntrinsicLowering.registerCoreImport(emitter, "$coreNs.intrinsics.kmRealloc")
                        "= ${wrapLongToRet("kmRealloc(${ptrArgToLong(0)}, ${pNames[1]}.toLong(), ${alignArgToLong(2)}, ${pNames[3]}.toLong())")}"
                    }
                    else -> "= Unit"
                }
            val directSig = ctx.sig.replaceFirst("expect fun ", "fun ")
            return "${ctx.fnTrivia}${ctx.doc}${ctx.indent}$directSig $shimBody"
        }
    }

    object LocalScope : ForeignFnEmissionStrategy() {
        override fun tryRender(ctx: ForeignFnRenderContext): String? {
            if (!ctx.isLocalItem) return null
            val emitter = ctx.emitter
            val localSig = ctx.sig.replaceFirst("expect fun ", "fun ")
            val retSynTy = extractReturnSynType(ctx.foreign.sig.output)
            val retBaseSimple = ctx.retRendered.substringBefore('<').substringAfterLast('.').trim()
            val isReturnStructWithFields =
                ctx.retDefault.endsWith("()") &&
                    (
                        retBaseSimple in emitter.inspector.tupleStructArityByType ||
                            emitter.inspector.structFieldSynTypesByType[retBaseSimple]?.isNotEmpty() == true ||
                            retBaseSimple in emitter.headerContext.externalCrateTupleStructArity ||
                            emitter.scopedAbiKeysForRenderedType(retBaseSimple).any { k ->
                                "$k.value" in emitter.headerContext.crateStructFieldTypes ||
                                    "$k.field0" in emitter.headerContext.crateStructFieldTypes
                            }
                    )
            val localBody =
                when {
                    ctx.retRendered == "Unit" -> "{}"
                    (ctx.retDefault.startsWith("null as ") || ctx.retDefault.startsWith("error(") || isReturnStructWithFields) &&
                        retSynTy != null -> {
                        val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(emitter.headerContext)
                        val layoutExpr = CoreIntrinsicLowering.resolveTypeLayoutExpression(retSynTy, null, emitter)
                        "= ${CoreIntrinsicLowering.renderZeroedLayoutReadExpression(layoutExpr, "$coreNs.intrinsics", emitter)}"
                    }
                    else -> "= ${ctx.retDefault}"
                }
            return "${ctx.fnTrivia}${ctx.doc}${ctx.indent}$localSig $localBody"
        }
    }

    object CollectedExtern : ForeignFnEmissionStrategy() {
        override fun tryRender(ctx: ForeignFnRenderContext): String {
            val retType = extractReturnSynType(ctx.foreign.sig.output)
            ctx.emitter.ffiCollector.recordForeignFunction(
                visibilityPrefix = ctx.vis,
                kotlinName = ctx.fnName,
                rawRustName = ctx.rawFnName,
                attrs = ctx.foreign.attrs,
                params = ctx.interopParams,
                kotlinReturnType = ctx.retRendered,
                returnSynType = retType,
                isVariadic = ctx.foreign.sig.variadic != null,
                returnDefaultExpr = ctx.retDefault,
                linkLibrary = ctx.modLinkLibrary,
            )
            return "${ctx.fnTrivia}${ctx.doc}${ctx.indent}${ctx.sig}"
        }
    }

    companion object {
        private val STRATEGIES: List<ForeignFnEmissionStrategy> =
            listOf(RustAllocShim, LocalScope, CollectedExtern)

        fun emit(ctx: ForeignFnRenderContext): String =
            STRATEGIES.firstNotNullOf { it.tryRender(ctx) }
    }
}

internal fun KotlinJ2KEmitter.renderItemForeignMod(
    item: Item.ForeignMod,
    indent: String,
    isLocalItem: Boolean = false,
): String {
    val modLinkLibrary = if (!isLocalItem) ffiCollector.recordForeignModAttributes(item.attrs) else null
    val modComments = (renderLeadingTrivia(item, indent) + renderKDocFromAttributes(item.attrs, indent)).trimEnd()
    val out = mutableListOf<String>()
    val localForeignSignatures = linkedMapOf<String, Int>()
    for (foreign in item.items) {
        if (foreignItemHasDocOnlyCfg(foreign)) continue
        when (foreign) {
            is ForeignItem.Fn -> {
                val vis = if (isLocalItem) "" else renderVisibility(foreign.vis)
                val rawFnName = foreign.sig.ident.toString().removePrefix("r#")
                val fnName = resolveForeignFnKotlinName(rawFnName, isLocalItem)
                val interopParams = mutableListOf<InteropParamSpec>()
                scopeBuilder.enterDef()
                val (sig, retRendered) =
                    try {
                        renderForeignFunctionExpectSignature(
                            sig = foreign.sig,
                            visPrefix = vis,
                            fnName = fnName,
                            scopeSignatures = if (isLocalItem) localForeignSignatures else topLevelSignatureCounts,
                            interopParamsOut = interopParams,
                        )
                    } finally {
                        scopeBuilder.exitDef()
                    } ?: continue
                val retDefault = if (retRendered == "Unit") "" else renderFallbackDefaultForRenderedType(retRendered)
                out +=
                    ForeignFnEmissionStrategy.emit(
                        ForeignFnRenderContext(
                            emitter = this,
                            foreign = foreign,
                            vis = vis,
                            rawFnName = rawFnName,
                            fnName = fnName,
                            sig = sig,
                            retRendered = retRendered,
                            retDefault = retDefault,
                            interopParams = interopParams,
                            fnTrivia = renderLeadingTrivia(foreign, indent),
                            doc = renderKDocFromAttributes(foreign.attrs, indent),
                            indent = indent,
                            isLocalItem = isLocalItem,
                            modLinkLibrary = modLinkLibrary,
                        ),
                    )
            }
            is ForeignItem.Static -> {
                out += renderForeignStaticItem(foreign, indent, isLocalItem)
            }
            else -> {}
        }
    }
    if (modComments.isNotBlank() && out.isNotEmpty()) {
        out[0] = "$modComments\n${out[0]}"
    }
    return out.joinToString("\n\n")
}

private fun KotlinJ2KEmitter.resolveForeignFnKotlinName(rawFnName: String, isLocalItem: Boolean): String =
    if (isLocalItem) {
        activeLocalForeignFnRenames[rawFnName]
            ?: rustFunctionIdentToKotlin(rawFnName).let { kt ->
                if (kt == currentEnclosingFunctionName) {
                    "extern${kt.replaceFirstChar { c -> c.uppercase() }}"
                } else {
                    kt
                }
            }
    } else {
        val collidedRename =
            currentModPathPrefix
                .lastOrNull()
                ?.let { inspector.collidingInlineForeignFnRenames[it to rawFnName] }
                ?: if (currentModPathPrefix.isEmpty()) inspector.collidingTopLevelForeignFnRenames[rawFnName] else null
        collidedRename ?: rustFunctionIdentToKotlin(rawFnName)
    }

private fun KotlinJ2KEmitter.renderForeignStaticItem(
    foreign: ForeignItem.Static,
    indent: String,
    isLocalItem: Boolean,
): String {
    val stTrivia = renderLeadingTrivia(foreign, indent)
    val doc = renderKDocFromAttributes(foreign.attrs, indent)
    val vis = if (isLocalItem) "" else renderVisibility(foreign.vis)
    val rawIdent = foreign.ident.toString().removePrefix("r#")
    val ktConst = inspector.declaredConstNames[rawIdent] ?: rustConstIdentToKotlin(rawIdent)
    if (rawIdent !in inspector.declaredConstNames) {
        inspector.declaredConstNames[rawIdent] = ktConst
    }
    val name = disambiguateName(ktConst, topLevelSignatureCounts)
    val prevExternAbi = inExternAbiSignature
    inExternAbiSignature = true
    val ty =
        try {
            renderType(foreign.ty, null)
        } finally {
            inExternAbiSignature = prevExternAbi
        }
    val isMut = foreign.mutability is StaticMutability.Mut
    val kw = if (isMut) "var" else "val"
    val defaultExpr = renderFallbackDefaultForRenderedType(ty)
    val isUnstableAllocMarker =
        rawIdent == "__rust_no_alloc_shim_is_unstable" || rawIdent == "__rust_no_alloc_shim_is_unstable_v2"
    return if (isUnstableAllocMarker || isLocalItem) {
        "$stTrivia$doc$indent$vis$kw $name: $ty = $defaultExpr"
    } else {
        ffiCollector.recordForeignStatic(
            visibilityPrefix = vis,
            kotlinName = name,
            rawRustName = rawIdent,
            attrs = foreign.attrs,
            synType = foreign.ty,
            kotlinType = ty,
            isMutable = isMut,
            defaultExpr = defaultExpr,
        )
        "$stTrivia$doc$indent${vis}expect $kw $name: $ty"
    }
}

internal fun KotlinJ2KEmitter.renderForeignFunctionExpectSignature(
    sig: Signature,
    visPrefix: String,
    fnName: String,
    scopeSignatures: MutableMap<String, Int>,
    interopParamsOut: MutableList<InteropParamSpec>,
): Pair<String, String>? {
    val prevExternAbi = inExternAbiSignature
    inExternAbiSignature = true
    return try {
        val combinedParams = sig.generics.params.toList()
        val typeParamsStr = renderTypeParametersFromParams(combinedParams, null)
        val params = mutableListOf<String>()
        val usedParamNames = hashSetOf<String>()
        var paramIdx = 0
        for (arg in sig.inputs.toList()) {
            if (arg is FnArg.Typed) {
                val rawParam = extractRawParamIdent(arg.patType.pat)
                val basePName =
                    if (rawParam != null && rawParam != "_") {
                        scopeBuilder
                            .bindLocal(
                                rawName = rawParam,
                                baseKotlinName = rustValueIdentToKotlin(rawParam),
                                span = arg.patType.pat.span(),
                                assignCount = AssignCount.AtMostOnce,
                            ).kotlinName
                    } else {
                        extractParamName(arg.patType.pat, paramIdx)
                    }
                var pName = basePName
                var suffix = 2
                while (!usedParamNames.add(pName)) {
                    pName = "${basePName}_$suffix"
                    suffix++
                }
                val prevHint = currentSamNameHint
                val prevParam = inExternParamType
                currentSamNameHint = "${fnName.replaceFirstChar { it.uppercase() }}${pName.replaceFirstChar { it.uppercase() }}Callback"
                inExternParamType = true
                val pType =
                    try {
                        renderType(arg.patType.ty, null)
                    } finally {
                        inExternParamType = prevParam
                        currentSamNameHint = prevHint
                    }
                val bridge = ffiCollector.classifyParamAbiBridge(arg.patType.ty, pType)
                val cParamName = rawParam?.takeIf { it != "_" } ?: "arg$paramIdx"
                interopParamsOut +=
                    InteropParamSpec(
                        kotlinName = pName,
                        cName = cParamName,
                        kotlinType = pType,
                        cType = pType,
                        bridgeKind = bridge,
                    )
                params += "$pName: $pType"
                paramIdx++
            }
        }
        if (sig.variadic != null) {
            params += "vararg variadicArgs: Long"
        }
        val sigKey = "extern_fn:$fnName"
        val seenCount = scopeSignatures[sigKey] ?: 0
        scopeSignatures[sigKey] = seenCount + 1
        if (seenCount > 0) {
            return null
        }
        val retType = extractReturnSynType(sig.output)
        val prevHint = currentSamNameHint
        currentSamNameHint = "${fnName.replaceFirstChar { it.uppercase() }}ResultCallback"
        val renderedReturn =
            try {
                retType?.let { renderType(it, null) } ?: "Unit"
            } finally {
                currentSamNameHint = prevHint
            }
        val retTypeStr = if (renderedReturn == "Unit") "" else ": $renderedReturn"
        val whereClause = renderWhereClause(sig.generics.whereClause, null, ownerGenerics = sig.generics)
        val typeParamsPart = if (typeParamsStr.isNotEmpty()) "$typeParamsStr " else ""
        val expectSig = "${visPrefix}expect fun ${typeParamsPart}$fnName(${params.joinToString(", ")})${retTypeStr}$whereClause"
        expectSig to renderedReturn
    } finally {
        inExternAbiSignature = prevExternAbi
    }
}
