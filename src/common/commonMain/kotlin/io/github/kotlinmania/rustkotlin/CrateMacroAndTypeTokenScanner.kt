package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.procmacro2.Delimiter
import io.github.kotlinmania.procmacro2.Group
import io.github.kotlinmania.procmacro2.TokenTree
import io.github.kotlinmania.syn.Item
import io.github.kotlinmania.syn.ModContent
import io.github.kotlinmania.syn.UseTree

private fun collectCleanScannerUseLeaves(
    tree: UseTree,
    out: MutableList<Pair<List<String>, String?>>,
) {
    val leaves = mutableListOf<UseLeaf>()
    collectUseLeaves(tree, emptyList(), leaves)
    for (leaf in leaves) {
        out += leaf.segments.map { it.removePrefix("r#") } to leaf.alias
    }
}

private fun isPureUseInlineScannerMod(modItem: Item.Mod): Boolean {
    val content = modItem.content as? ModContent.Inline ?: return false
    for (sub in content.items) {
        if (itemHasDocOnlyCfg(sub)) continue
        when (sub) {
            is Item.Use -> {}
            is Item.Mod -> {
                if (hasCfgTestModAttribute(sub.attrs) || !isPureUseInlineScannerMod(sub)) return false
            }
            else -> return false
        }
    }
    return true
}

private fun collectInlineScannerModUseLeaves(
    modItem: Item.Mod,
    out: MutableList<Pair<List<String>, String?>>,
) {
    val content = modItem.content as? ModContent.Inline ?: return
    for (sub in content.items) {
        if (itemHasDocOnlyCfg(sub)) continue
        when (sub) {
            is Item.Use -> collectCleanScannerUseLeaves(sub.tree, out)
            is Item.Mod -> if (!hasCfgTestModAttribute(sub.attrs)) collectInlineScannerModUseLeaves(sub, out)
            else -> {}
        }
    }
}

internal fun scanRootAliasesAndExternalReexports(
    libText: String,
    byRelPath: Map<String, String>,
    selfAliases: Set<String>,
    includedSubdirModules: Map<String, String>,
    rootSelfDirModuleAliasesOut: MutableSet<String>,
    rootExternalModuleReexportsOut: MutableMap<String, String>,
) {
    val recognized = recognizeRustOrNull(libText) ?: return
    val allRelKeys = byRelPath.keys

    fun isLocalFileModule(modName: String): Boolean =
        "$modName.rs" in byRelPath ||
            "$modName/mod.rs" in byRelPath ||
            modName in includedSubdirModules ||
            allRelKeys.any { it.startsWith("$modName/") }

    val externCrateAliases = linkedMapOf<String, String>()
    val externalCrateNames = linkedSetOf("core", "alloc", "std", "proc_macro")
    val activeRootItems =
        recognized.file.items.filterNot { item ->
            itemHasDocOnlyCfg(item) || (item is Item.Mod && hasCfgTestModAttribute(item.attrs))
        }

    var pointsToRootModRs = false
    for (item in activeRootItems) {
        when (item) {
            is Item.ExternCrate -> {
                val orig = item.ident.toString().removePrefix("r#")
                val rename =
                    item.rename
                        ?.ident
                        ?.toString()
                        ?.removePrefix("r#")
                if (orig != "self") {
                    externalCrateNames += orig
                    if (rename != null && rename != orig && rename != "_") {
                        externCrateAliases[rename] = orig
                        if (!isLocalFileModule(rename)) {
                            rootExternalModuleReexportsOut[rename] = orig
                        }
                    }
                }
            }
            is Item.Mod -> {
                val modIdent = item.ident.cleanIdent()
                if (item.content !is ModContent.Inline) {
                    val pathVal = extractStringMetaAttribute(item.attrs, "path")?.replace('\\', '/')?.removePrefix("./")
                    if (pathVal == "mod.rs") {
                        rootSelfDirModuleAliasesOut += modIdent
                        pointsToRootModRs = true
                    } else if (
                        pathVal != null &&
                        pathVal.startsWith("../") &&
                        (pathVal.endsWith("/src/lib.rs") || pathVal.endsWith("/src/mod.rs"))
                    ) {
                        val targetCrate =
                            pathVal
                                .removeSuffix("/src/lib.rs")
                                .removeSuffix("/src/mod.rs")
                                .substringAfterLast('/')
                                .replace('-', '_')
                                .removePrefix("r#")
                        if (targetCrate.isNotEmpty() && !isLocalFileModule(modIdent)) {
                            rootExternalModuleReexportsOut[modIdent] = targetCrate
                            externalCrateNames += targetCrate
                        }
                    }
                }
            }
            else -> {}
        }
    }

    for (item in activeRootItems) {
        if (item is Item.Use) {
            val leaves = mutableListOf<Pair<List<String>, String?>>()
            collectCleanScannerUseLeaves(item.tree, leaves)
            for ((rawSegs, alias) in leaves) {
                val firstRaw = rawSegs.firstOrNull() ?: continue
                val resolvedExt = externCrateAliases[firstRaw] ?: firstRaw
                if (rawSegs.size == 2 &&
                    rawSegs[1] != "*" &&
                    resolvedExt in externalCrateNames &&
                    resolvedExt !in selfAliases &&
                    !isLocalFileModule(resolvedExt)
                ) {
                    val boundName = alias ?: rawSegs[1]
                    if (!isLocalFileModule(boundName) && boundName !in rootSelfDirModuleAliasesOut) {
                        rootExternalModuleReexportsOut[boundName] = "$resolvedExt::${rawSegs[1]}"
                    }
                }
            }
        }
    }

    fun inspectPureUseInlineMod(modItem: Item.Mod) {
        val modName = modItem.ident.toString().removePrefix("r#")
        if (isLocalFileModule(modName) || !isPureUseInlineScannerMod(modItem)) return
        val leaves = mutableListOf<Pair<List<String>, String?>>()
        collectInlineScannerModUseLeaves(modItem, leaves)
        if (leaves.isEmpty()) return
        val extTargets =
            leaves
                .mapNotNull { (segs, _) ->
                    val firstRaw = segs.firstOrNull() ?: return@mapNotNull null
                    val resolvedExt = externCrateAliases[firstRaw] ?: firstRaw
                    val second = segs.getOrNull(1)?.takeIf { it != "*" } ?: return@mapNotNull null
                    if (resolvedExt in externalCrateNames && resolvedExt !in selfAliases && !isLocalFileModule(resolvedExt)) {
                        "$resolvedExt::$second"
                    } else {
                        null
                    }
                }.distinct()
        if (extTargets.size == 1 && extTargets.size == leaves.map { it.first.take(2) }.distinct().size) {
            rootExternalModuleReexportsOut.putIfMissing(modName, extTargets.single())
            return
        }
        val targetsCrate =
            leaves.any { (segs, _) ->
                segs.firstOrNull() == "crate" || segs.firstOrNull() == "self" || segs.firstOrNull() in selfAliases
            }
        if (targetsCrate) {
            rootSelfDirModuleAliasesOut += modName
        }
    }

    for (item in activeRootItems) {
        if (item is Item.Mod && item.content is ModContent.Inline) {
            inspectPureUseInlineMod(item)
        }
    }
    if (pointsToRootModRs) {
        val modRsText = byRelPath["mod.rs"]
        val recognizedModRs = if (modRsText != null) recognizeRustOrNull(modRsText) else null
        recognizedModRs
            ?.file
            ?.items
            ?.filterNot { itemHasDocOnlyCfg(it) || (it is Item.Mod && hasCfgTestModAttribute(it.attrs)) }
            ?.forEach { item ->
                if (item is Item.Mod && item.content is ModContent.Inline) {
                    inspectPureUseInlineMod(item)
                }
            }
    }
}

internal fun scanRootLibDirectives(
    trees: List<TokenTree>,
    selfAliasesOut: MutableSet<String>,
    includedSubdirsOut: MutableList<String>,
) {
    ExternalModDeclTokenScanner(trees).scanRootLibDirectives(selfAliasesOut, includedSubdirsOut)
}

internal fun extractPubModIdentsFromTokens(trees: List<TokenTree>): List<String> =
    ExternalModDeclTokenScanner(trees).extractPubModIdents()

internal fun isRelPathUnderDisabledModulePrefix(relPath: String, disabledPrefixes: Set<String>): Boolean {
    if (disabledPrefixes.isEmpty()) return false
    val norm = relPath.replace('\\', '/').trimStart('/')
    return disabledPrefixes.any { prefix ->
        norm == "$prefix.rs" || norm == "$prefix.RS" || norm.startsWith("$prefix/")
    }
}

internal fun collectCfgDisabledChildModuleRelPrefixes(
    sourcesByRelPath: Map<String, String>,
    cfgProfile: CfgTargetProfile,
    skipCfgTest: Boolean = true,
): Set<String> {
    if (sourcesByRelPath.isEmpty()) return emptySet()
    val prevProfile = TranslationSession.cfgTargetProfile
    val prevRelDir = TranslationSession.currentRelativeSourceDir
    TranslationSession.cfgTargetProfile = cfgProfile
    try {
        val totalModDecls = linkedMapOf<String, Int>()
        val enabledModDecls = linkedMapOf<String, Int>()

        for ((relFileRaw, text) in sourcesByRelPath) {
            if ("mod" !in text) continue
            val relFile = relFileRaw.replace('\\', '/').trimStart('/')
            val relDir = relFile.substringBeforeLast('/', missingDelimiterValue = "")
            val fileName = relFile.substringAfterLast('/')
            val stem = fileName.removeSuffix(".rs").removeSuffix(".RS")
            val childDir =
                if (isRustModuleRootFileName(fileName) || fileName.equals("main.rs", ignoreCase = true)) {
                    relDir
                } else if (relDir.isEmpty()) {
                    stem
                } else {
                    "$relDir/$stem"
                }
            TranslationSession.currentRelativeSourceDir = relDir.ifEmpty { null }
            val tokens = lexScannerTokens(text)
            if (tokens.isNotEmpty()) {
                ExternalModDeclTokenScanner(tokens).scanExternalModDecls(
                    relDir = relDir,
                    childDir = childDir,
                    inheritedDisabled = false,
                    skipCfgTest = skipCfgTest,
                    totalModDeclsOut = totalModDecls,
                    enabledModDeclsOut = enabledModDecls,
                )
            }
        }

        val disabled = linkedSetOf<String>()
        for ((prefix, total) in totalModDecls) {
            if (total > 0 && (enabledModDecls[prefix] ?: 0) == 0) {
                disabled += prefix
            }
        }
        return disabled
    } finally {
        TranslationSession.cfgTargetProfile = prevProfile
        TranslationSession.currentRelativeSourceDir = prevRelDir
    }
}

private class ExternalModDeclTokenScanner(
    trees: List<TokenTree>,
) : RustTokenCursor(trees) {
    fun scanRootLibDirectives(
        selfAliasesOut: MutableSet<String>,
        includedSubdirsOut: MutableList<String>,
    ) {
        var i = 0
        while (i < size) {
            if (identAt(i) == "extern" &&
                identAt(i + 1) == "crate" &&
                identAt(i + 2) == "self" &&
                identAt(i + 3) == "as"
            ) {
                val alias = cleanIdentAt(i + 4)
                if (alias != null) {
                    selfAliasesOut += alias
                    i += 5
                    continue
                }
            }
            val incGrp = if (identAt(i) == "include" && punctCharAt(i + 1) == '!') groupAt(i + 2) else null
            if (incGrp != null) {
                val grpTokens = incGrp.stream().toList()
                val litVal = (grpTokens.singleOrNull() as? TokenTree.Literal)?.value?.strValue()?.value
                if (litVal != null && litVal.endsWith("/mod.rs")) {
                    val sub = litVal.removeSuffix("/mod.rs")
                    if (sub.isNotEmpty() && '/' !in sub && '\\' !in sub) {
                        includedSubdirsOut += sub
                    }
                }
                i += 3
                continue
            }
            i++
        }
    }

    fun extractPubModIdents(): List<String> {
        val result = mutableListOf<String>()
        var i = 0
        while (i < size) {
            if (identAt(i) == "pub") {
                val j = copyOptionalPubVisibility(i)
                val modName = cleanIdentAt(j + 1)
                if (identAt(j) == "mod" && modName != null && punctCharAt(j + 2) == ';') {
                    result += modName
                    i = j + 3
                    continue
                }
            }
            i++
        }
        return result
    }

    fun scanExternalModDecls(
        relDir: String,
        childDir: String,
        inheritedDisabled: Boolean,
        skipCfgTest: Boolean,
        totalModDeclsOut: MutableMap<String, Int>,
        enabledModDeclsOut: MutableMap<String, Int>,
    ) {
        val pendingAttrs = mutableListOf<List<TokenTree>>()
        var idx = 0
        while (idx < size) {
            consumeAttribute(idx, pendingAttrs)?.let {
                idx = it
                continue
            }
            val attrInspector = ScannerAttrGroupInspector(pendingAttrs)
            val selfDisabled = inheritedDisabled || attrInspector.hasSkippedAttrs(skipCfgTest = skipCfgTest)
            val kwIdx = copyOptionalPubVisibility(idx)
            val rawMod = cleanIdentAt(kwIdx + 1)
            if (identAt(kwIdx) == "mod" && rawMod != null && punctCharAt(kwIdx + 2) == ';') {
                val pathOverride = attrInspector.extractPathAttrOverride()
                val targetPrefix =
                    when {
                        pathOverride != null -> {
                            val stripped = pathOverride.removeSuffix(".rs").removeSuffix(".RS").removeSuffix("/mod")
                            if (relDir.isEmpty()) stripped else "$relDir/$stripped"
                        }
                        childDir.isEmpty() -> rawMod
                        else -> "$childDir/$rawMod"
                    }
                totalModDeclsOut[targetPrefix] = (totalModDeclsOut[targetPrefix] ?: 0) + 1
                if (!selfDisabled) {
                    enabledModDeclsOut[targetPrefix] = (enabledModDeclsOut[targetPrefix] ?: 0) + 1
                }
                idx = kwIdx + 3
                pendingAttrs.clear()
                continue
            }
            val curIdent = identAt(idx)
            val nextGrp = groupAt(idx + 2)
            if ((curIdent == "cfg_if" || curIdent == "cfg_select" || curIdent == "cfg_select_nofmt") &&
                punctCharAt(idx + 1) == '!' &&
                (nextGrp?.delimiter() == Delimiter.Brace || nextGrp?.delimiter() == Delimiter.Parenthesis)
            ) {
                val innerTrees = nextGrp.stream().toList()
                val branchBodies =
                    if (curIdent == "cfg_select" || curIdent == "cfg_select_nofmt") {
                        partitionCfgSelectBranchBodies(innerTrees)
                    } else {
                        partitionCfgIfBranchBodies(innerTrees)
                    }
                if (branchBodies != null) {
                    for ((bodyTokens, isActive) in branchBodies) {
                        ExternalModDeclTokenScanner(bodyTokens).scanExternalModDecls(
                            relDir = relDir,
                            childDir = childDir,
                            inheritedDisabled = selfDisabled || !isActive,
                            skipCfgTest = skipCfgTest,
                            totalModDeclsOut = totalModDeclsOut,
                            enabledModDeclsOut = enabledModDeclsOut,
                        )
                    }
                    idx += 3
                    pendingAttrs.clear()
                    continue
                }
            }
            if (punctCharAt(idx) == ';' || groupAt(idx, Delimiter.Brace) != null) {
                pendingAttrs.clear()
            }
            idx++
        }
    }
}

internal fun selectActiveCfgIfBodies(innerTrees: List<TokenTree>): List<List<TokenTree>>? =
    partitionCfgIfBranchBodies(innerTrees)?.filter { it.second }?.map { it.first }

private fun partitionCfgIfBranchBodies(innerTrees: List<TokenTree>): List<Pair<List<TokenTree>, Boolean>>? =
    parseCfgIfArms(innerTrees)?.let(::partitionCfgBranchArms)

private class ScannerAttrGroupInspector(
    private val attrGroups: List<List<TokenTree>>,
) {
    fun hasSkippedAttrs(skipCfgTest: Boolean = true): Boolean {
        for (attrTokens in attrGroups) {
            val cursor = RustTokenCursor(attrTokens)
            val innerGrp = if (cursor.size == 2 && cursor.identAt(0) == "cfg") cursor.groupAt(1, Delimiter.Parenthesis) else null
            if (innerGrp != null) {
                val inner = innerGrp.stream().toList()
                if (isDocOnlyCfgPredicateTokens(inner)) return true
                if (skipCfgTest && inner.size == 1 && (inner[0] as? TokenTree.Ident)?.value?.toString() == "test") return true
                if (evalCfgTriState(inner, forRuntimeCfgMacro = false) == false) return true
            }
        }
        return false
    }

    fun extractPathAttrOverride(): String? {
        for (attrTokens in attrGroups) {
            val c = RustTokenCursor(attrTokens)
            if (c.size == 3 && c.identAt(0) == "path" && c.punctCharAt(1) == '=' && attrTokens[2] is TokenTree.Literal) {
                val strVal = (attrTokens[2] as TokenTree.Literal).value.strValue().value
                if (!strVal.isNullOrBlank()) return strVal.replace('\\', '/').trimStart('/')
            }
        }
        return null
    }

    private fun reprBodies(): Sequence<RustTokenCursor> =
        attrGroups.asSequence().mapNotNull { attrTokens ->
            val c = RustTokenCursor(attrTokens)
            if (c.size == 2 && c.identAt(0) == "repr") {
                c.groupAt(1, Delimiter.Parenthesis)?.let { grp -> RustTokenCursor(grp.stream().toList()) }
            } else {
                null
            }
        }

    fun extractReprPackAlign(): Int? {
        var pack: Int? = null
        for (inner in reprBodies()) {
            for (idx in 0 until inner.size) {
                if (inner.identAt(idx) == "packed") {
                    val nextGrp = inner.groupAt(idx + 1, Delimiter.Parenthesis)
                    if (nextGrp != null) {
                        val argTokens = nextGrp.stream().toList()
                        val parsed = argTokens.firstOrNull()?.toString()?.toIntOrNull()
                        if (parsed != null && parsed > 0) {
                            pack = if (pack == null) parsed else minOf(pack, parsed)
                        }
                    } else {
                        pack = 1
                    }
                }
            }
        }
        return pack
    }

    fun extractEnumReprKind(
        rawAbiMode: Boolean,
        defaultKind: String = "U32",
    ): String {
        val ptrWidth = TranslationSession.cfgTargetProfile.pointerWidth
        for (inner in reprBodies()) {
            for (idx in 0 until inner.size) {
                val ident = inner.identAt(idx) ?: continue
                when (ident) {
                    "isize" -> return if (rawAbiMode && ptrWidth == 64) "I64" else "I32"
                    "usize" -> return if (rawAbiMode) (if (ptrWidth == 64) "U64" else "U32") else "I32"
                    else -> {
                        val kind = SCANNER_RUST_TO_SCALAR_KIND[ident]
                        if (kind != null && kind !in NON_INT_ENUM_REPR_KINDS) {
                            return kind
                        }
                    }
                }
            }
        }
        return defaultKind
    }

    companion object {
        private val NON_INT_ENUM_REPR_KINDS = setOf("I128", "U128", "F32", "F64", "BOOL")
    }
}

internal fun collectFfiTokenTypeIdents(
    trees: List<TokenTree>,
    rawAbiMode: Boolean,
    out: MutableSet<String>,
) {
    for (tt in trees) {
        when (tt) {
            is TokenTree.Ident -> {
                val raw = tt.value.toString().removePrefix("r#")
                out += raw
                out += KotlinNamingPolicy.Default.toTypeIdentifier(raw, allowRawAbi = rawAbiMode)
            }
            is TokenTree.Group -> collectFfiTokenTypeIdents(tt.value.stream().toList(), rawAbiMode, out)
            else -> {}
        }
    }
}

internal class ModuleTokenTreeScanner(
    private val sink: CrateTokenScanSink,
) {
    private val rawAbiMode: Boolean get() = sink.rawAbiMode
    private val finalStructs: Boolean get() = sink.finalStructs
    private val splitModuleRoots: Boolean get() = sink.splitModuleRoots

    fun scan(
        trees: List<TokenTree>,
        subpkg: String,
        topLevelSplitInlineMods: Set<String> = emptySet(),
        insideCEnumMacro: Boolean = false,
        insideExternBlock: Boolean = false,
    ) {
        val cursor = RustItemHeaderTokenScanner(trees)
        val pendingAttrs = mutableListOf<List<TokenTree>>()
        var i = 0
        while (i < cursor.size) {
            cursor.consumeAttribute(i, pendingAttrs)?.let {
                i = it
                continue
            }
            val skippedByCfg = ScannerAttrGroupInspector(pendingAttrs).hasSkippedAttrs()

            if (cursor.identAt(i) == "macro_rules" &&
                cursor.punctCharAt(i + 1) == '!' &&
                cursor.identAt(i + 2) != null &&
                cursor.groupAt(i + 3) != null
            ) {
                if (!skippedByCfg) {
                    val rawMacroName = cursor.cleanIdentAt(i + 2).orEmpty()
                    sink.registerSymbol(rustFunctionIdentToKotlin(rawMacroName), subpkg)
                }
                i += 4
                if (cursor.punctCharAt(i) == ';') i++
                pendingAttrs.clear()
                continue
            }

            val externBraceIdx = cursor.matchExternBlockBraceIndex(i)
            if (externBraceIdx != null) {
                if (!skippedByCfg) {
                    val externInnerTrees = cursor.groupAt(externBraceIdx)!!.stream().toList()
                    collectFfiTokenTypeIdents(externInnerTrees, rawAbiMode, sink.ffiReferencedIdents)
                    scan(trees = externInnerTrees, subpkg = subpkg, insideExternBlock = true)
                }
                i = externBraceIdx + 1
                pendingAttrs.clear()
                continue
            }

            val macroGroupIdx = cursor.matchPathMacroGroupIndex(i)
            if (macroGroupIdx != null) {
                if (!skippedByCfg) {
                    val grp = cursor.groupAt(macroGroupIdx)!!
                    val innerTrees = grp.stream().toList()
                    val innerScanner = RustItemHeaderTokenScanner(innerTrees)
                    val linkFnIdent = innerScanner.extractLinkMacroFnIdent()
                    if (linkFnIdent != null) {
                        collectFfiTokenTypeIdents(innerTrees, rawAbiMode, sink.ffiReferencedIdents)
                        val ktLinkFn = rustFunctionIdentToKotlin(linkFnIdent)
                        sink.registerSymbol(ktLinkFn, subpkg)
                        val linkParams = innerScanner.extractLinkMacroFnParamTokenLists()
                        val linkRet = innerScanner.extractLinkMacroFnReturnType(rawAbiMode)
                        if (linkParams != null || linkRet != null) {
                            sink.registerFnSig(subpkg, ktLinkFn, linkParams, linkRet, true)
                        }
                    } else if (grp.delimiter() == Delimiter.Brace || grp.delimiter() == Delimiter.Parenthesis) {
                        val cfgIfBodies = selectActiveCfgIfBodies(innerTrees) ?: selectActiveCfgSelectBodies(innerTrees)
                        if (cfgIfBodies != null) {
                            for (branchBody in cfgIfBodies) {
                                scan(branchBody, subpkg, topLevelSplitInlineMods, insideCEnumMacro, insideExternBlock)
                            }
                        } else if ((0 until innerScanner.size).any { innerScanner.identAt(it) == "pub" }) {
                            val macroLeafIdent = cursor.identAt(macroGroupIdx - 2)
                            scan(innerTrees, subpkg, topLevelSplitInlineMods, insideCEnumMacro || macroLeafIdent == "c_enum", insideExternBlock)
                        }
                    }
                }
                i = macroGroupIdx + 1
                if (cursor.punctCharAt(i) == ';') i++
                pendingAttrs.clear()
                continue
            }

            val nextItemIdx =
                tryScanDeclarationItem(
                    cursor = cursor,
                    startIdx = i,
                    subpkg = subpkg,
                    topLevelSplitInlineMods = topLevelSplitInlineMods,
                    pendingAttrs = pendingAttrs,
                    skippedByCfg = skippedByCfg,
                    insideCEnumMacro = insideCEnumMacro,
                    insideExternBlock = insideExternBlock,
                )
            if (nextItemIdx != null) {
                i = nextItemIdx
                pendingAttrs.clear()
                continue
            }

            if (cursor.punctCharAt(i) == ';' || cursor.groupAt(i, Delimiter.Brace) != null) {
                pendingAttrs.clear()
            }
            i++
        }
    }

    private fun tryScanDeclarationItem(
        cursor: RustItemHeaderTokenScanner,
        startIdx: Int,
        subpkg: String,
        topLevelSplitInlineMods: Set<String>,
        pendingAttrs: List<List<TokenTree>>,
        skippedByCfg: Boolean,
        insideCEnumMacro: Boolean,
        insideExternBlock: Boolean,
    ): Int? {
        val isPublic = cursor.identAt(startIdx) == "pub"
        val baseIdx = if (isPublic) cursor.copyOptionalPubVisibility(startIdx) else startIdx
        val traitQualIdx = cursor.skipQualifiers(baseIdx, TRAIT_QUALIFIERS)
        if (cursor.identAt(traitQualIdx) == "trait") {
            return scanTraitItem(cursor, traitQualIdx, subpkg, skippedByCfg)
        }
        if (!isPublic) {
            val implQualIdx = cursor.skipQualifiers(startIdx, IMPL_QUALIFIERS)
            if (cursor.identAt(implQualIdx) == "impl") {
                val endImplIdx = cursor.skipAfterItemHeader(implQualIdx + 1)
                if (!skippedByCfg) {
                    cursor.scanInherentImplAssociatedFunctions(implQualIdx, endImplIdx, subpkg, sink)
                }
                return endImplIdx
            }
        }
        return when (val kw = cursor.identAt(baseIdx)) {
            "mod" ->
                if (isPublic || cursor.identAt(baseIdx + 1) != null) {
                    scanModItem(cursor, baseIdx, subpkg, topLevelSplitInlineMods, skippedByCfg, insideCEnumMacro, insideExternBlock)
                } else {
                    null
                }
            "struct", "union" ->
                scanStructOrUnionItem(cursor, baseIdx, kw == "union", isPublic = isPublic, subpkg, pendingAttrs, skippedByCfg)
            "enum" ->
                scanEnumItem(cursor, baseIdx, isPublic = isPublic, subpkg, pendingAttrs, skippedByCfg, insideCEnumMacro)
            "type" ->
                if (isPublic) {
                    cursor.scanPubTypeDeclaration(baseIdx, subpkg, skippedByCfg, sink)
                } else {
                    val rawIdent = cursor.identAt(baseIdx + 1)
                    if (rawIdent != null && !skippedByCfg) {
                        sink.registerSymbol(KotlinNamingPolicy.Default.toTypeIdentifier(rawIdent, allowRawAbi = rawAbiMode), subpkg)
                    }
                    cursor.skipAfterItemHeader(baseIdx + 1, semicolonOnly = true)
                }
            "const" -> {
                val afterConstText = cursor.identAt(baseIdx + 1)
                val isConstFn =
                    if (isPublic) {
                        afterConstText == "fn" || afterConstText == "unsafe" || afterConstText == "extern"
                    } else {
                        cursor.findFnIdentAfterQualifiers(baseIdx) != null
                    }
                if (isConstFn) {
                    scanFnItem(cursor, if (isPublic) baseIdx + 1 else baseIdx, subpkg, skippedByCfg, insideExternBlock)
                } else {
                    scanConstOrStaticValueItem(cursor, baseIdx + 1, subpkg, skippedByCfg)
                }
            }
            "static" -> {
                var idIdx = baseIdx + 1
                if (cursor.identAt(idIdx) == "mut") idIdx++
                scanConstOrStaticValueItem(cursor, idIdx, subpkg, skippedByCfg)
            }
            "fn", "unsafe", "async", "extern" ->
                if (isPublic || cursor.findFnIdentAfterQualifiers(baseIdx) != null) {
                    scanFnItem(cursor, baseIdx, subpkg, skippedByCfg, insideExternBlock)
                } else {
                    null
                }
            else -> null
        }
    }

    private fun scanModItem(
        cursor: RustItemHeaderTokenScanner,
        modKwIdx: Int,
        subpkg: String,
        topLevelSplitInlineMods: Set<String>,
        skippedByCfg: Boolean,
        insideCEnumMacro: Boolean,
        insideExternBlock: Boolean,
    ): Int {
        val rawMod = cursor.cleanIdentAt(modKwIdx + 1)
        val inlineGrp = cursor.groupAt(modKwIdx + 2)
        if (rawMod != null && !skippedByCfg && rawMod !in IGNORED_SCANNER_MOD_NAMES) {
            val modKt = rustModuleIdentToKotlin(rawMod.replace('-', '_'))
            val childSub = if (subpkg.isEmpty()) modKt else "$subpkg.$modKt"
            val isSplitInlineMod =
                splitModuleRoots &&
                    inlineGrp != null &&
                    inlineGrp.delimiter() == Delimiter.Brace &&
                    rawMod in topLevelSplitInlineMods
            if ((inlineGrp == null || isSplitInlineMod) && modKt.isNotEmpty()) {
                sink.subpkgToRawLeaf.putIfMissing(childSub, rawMod)
            }
            if (inlineGrp != null && inlineGrp.delimiter() == Delimiter.Brace) {
                scan(
                    trees = inlineGrp.stream().toList(),
                    subpkg = if (isSplitInlineMod && modKt.isNotEmpty()) childSub else subpkg,
                    insideCEnumMacro = insideCEnumMacro,
                    insideExternBlock = insideExternBlock,
                )
            }
        }
        return cursor.skipAfterItemHeader(modKwIdx + 1)
    }

    private fun scanStructOrUnionItem(
        cursor: RustItemHeaderTokenScanner,
        kwIdx: Int,
        isUnion: Boolean,
        isPublic: Boolean,
        subpkg: String,
        pendingAttrs: List<List<TokenTree>>,
        skippedByCfg: Boolean,
    ): Int {
        val rawIdent = cursor.identAt(kwIdx + 1)
        if (rawIdent != null && !skippedByCfg) {
            val ktType = KotlinNamingPolicy.Default.toTypeIdentifier(rawIdent, allowRawAbi = rawAbiMode)
            sink.registerSymbol(ktType, subpkg)
            val afterIdent = kwIdx + 2
            val (genTypeParams, _, afterGenericsIdx) = cursor.extractHeaderGenericTypeParams(afterIdent, rawAbiMode)
            val hasAnyGenerics = afterGenericsIdx > afterIdent
            val bodyGroup = cursor.findStructOrUnionBodyGroup(afterGenericsIdx)
            if (isPublic && !hasAnyGenerics) {
                if (rawIdent.removePrefix("r#") != "c_void") {
                    sink.putScopedDefault(subpkg, ktType, "$ktType()")
                }
                if (bodyGroup != null) {
                    val parsedFields =
                        RustItemHeaderTokenScanner.parseRecordFieldsFromGroup(
                            bodyGroup = bodyGroup,
                            rawAbiMode = rawAbiMode,
                            structFieldTypesOut = sink.structFieldTypes,
                            ownerKtType = ktType,
                            subpkg = subpkg,
                            isUnion = isUnion,
                        )
                    if (parsedFields.isNotEmpty()) {
                        val decl =
                            RawRecordTokenDecl(
                                subpkg = subpkg,
                                ktName = ktType,
                                isUnion = isUnion,
                                packAlign = ScannerAttrGroupInspector(pendingAttrs).extractReprPackAlign(),
                                fields = parsedFields,
                            )
                        val scopedKey = if (subpkg.isEmpty()) ktType else "$subpkg.$ktType"
                        sink.recordDecls.putIfMissing(scopedKey, decl)
                        sink.recordDeclsBySimple.putIfMissing(ktType, decl)
                    }
                }
            } else if (bodyGroup != null) {
                RustItemHeaderTokenScanner.parseRecordFieldsFromGroup(
                    bodyGroup = bodyGroup,
                    rawAbiMode = rawAbiMode,
                    structFieldTypesOut = sink.structFieldTypes,
                    ownerKtType = ktType,
                    subpkg = subpkg,
                    isUnion = isUnion,
                    excludedTypeParamNames = genTypeParams,
                )
            }
        }
        return cursor.skipAfterItemHeader(kwIdx + 1)
    }

    private fun scanEnumItem(
        cursor: RustItemHeaderTokenScanner,
        kwIdx: Int,
        isPublic: Boolean,
        subpkg: String,
        pendingAttrs: List<List<TokenTree>>,
        skippedByCfg: Boolean,
        insideCEnumMacro: Boolean,
    ): Int {
        val rawIdent = cursor.identAt(kwIdx + 1)
        if (rawIdent != null && !skippedByCfg) {
            val ktType = KotlinNamingPolicy.Default.toTypeIdentifier(rawIdent, allowRawAbi = rawAbiMode)
            sink.registerSymbol(ktType, subpkg)
            val afterIdent = kwIdx + 2
            cursor.scanEnumVariantTypeArities(afterIdent, subpkg, ktType, sink)
            if (isPublic) {
                val hasGenerics = cursor.punctCharAt(afterIdent) == '<'
                val braceGrp = cursor.groupAt(afterIdent, Delimiter.Brace)
                val attrInspector = ScannerAttrGroupInspector(pendingAttrs)
                if (insideCEnumMacro) {
                    val reprKind = attrInspector.extractEnumReprKind(rawAbiMode, defaultKind = "I32")
                    sink.putScopedNumericKind(subpkg, ktType, reprKind)
                    sink.putScopedDefault(subpkg, ktType, if (reprKind.startsWith("U")) "0u" else "0")
                    if (braceGrp != null) {
                        val bodyCursor = RustTokenCursor(braceGrp.stream().toList())
                        for (bIdx in 0 until bodyCursor.size - 1) {
                            if (bodyCursor.identAt(bIdx) == "pub") {
                                val vIdent = bodyCursor.identAt(bIdx + 1)
                                if (vIdent != null) {
                                    val ktVar = KotlinNamingPolicy.Default.toConstIdentifier(vIdent)
                                    sink.registerSymbol(ktVar, subpkg)
                                    sink.putScopedNumericKind(subpkg, ktVar, reprKind)
                                }
                            }
                        }
                    }
                } else if (!hasGenerics &&
                    braceGrp != null &&
                    (rawAbiMode || finalStructs) &&
                    RustTypeSliceInspector(braceGrp.stream().toList()).isFieldlessNonEmptyEnumBody()
                ) {
                    val reprKind = attrInspector.extractEnumReprKind(rawAbiMode)
                    val scopedKey = if (subpkg.isEmpty()) ktType else "$subpkg.$ktType"
                    sink.simpleEnumReprs.putIfMissing(scopedKey, reprKind)
                    sink.putScopedDefault(subpkg, ktType, "$ktType.entries.first()")
                }
            }
        }
        return cursor.skipAfterItemHeader(kwIdx + 1)
    }

    private fun scanTraitItem(
        cursor: RustItemHeaderTokenScanner,
        traitKwIdx: Int,
        subpkg: String,
        skippedByCfg: Boolean,
    ): Int {
        val rawIdent = cursor.identAt(traitKwIdx + 1)
        if (rawIdent != null && !skippedByCfg) {
            val ktTrait = KotlinNamingPolicy.Default.toTypeIdentifier(rawIdent, allowRawAbi = rawAbiMode)
            sink.registerSymbol(ktTrait, subpkg)
            cursor.scanTraitTokenMetadata(traitKwIdx + 2, subpkg, ktTrait, sink)
        }
        return cursor.skipAfterItemHeader(traitKwIdx + 1)
    }

    private fun scanFnItem(
        cursor: RustItemHeaderTokenScanner,
        qualStartIdx: Int,
        subpkg: String,
        skippedByCfg: Boolean,
        insideExternBlock: Boolean,
    ): Int {
        val fnIdent = cursor.findFnIdentAfterQualifiers(qualStartIdx)
        val endFnIdx = cursor.skipAfterItemHeader(qualStartIdx)
        if (fnIdent != null && !skippedByCfg) {
            val ktFn = rustFunctionIdentToKotlin(fnIdent)
            sink.registerSymbol(ktFn, subpkg)
            val pLists = cursor.extractNonMethodFnParamTokenLists(qualStartIdx)
            val retTy = cursor.extractFnReturnTypeFromHeader(qualStartIdx, endFnIdx, rawAbiMode, insideExternBlock = insideExternBlock)
            if (pLists != null || retTy != null) {
                sink.registerFnSig(subpkg, ktFn, pLists, retTy, insideExternBlock)
            }
            val constParams = cursor.extractFnConstParamNamesFromHeader(qualStartIdx)
            if (pLists != null && constParams.isNotEmpty()) {
                sink.registerFnConstParams(subpkg, ktFn, pLists.size, constParams)
            }
        }
        return endFnIdx
    }

    private fun scanConstOrStaticValueItem(
        cursor: RustItemHeaderTokenScanner,
        idIdx: Int,
        subpkg: String,
        skippedByCfg: Boolean,
    ): Int {
        val rawIdent = cursor.identAt(idIdx)
        val endIdx = cursor.skipAfterItemHeader(idIdx, semicolonOnly = true)
        if (rawIdent != null && rawIdent != "_" && rawIdent !in RustItemHeaderTokenScanner.SCANNER_ITEM_KEYWORDS && !skippedByCfg) {
            val ktConst = KotlinNamingPolicy.Default.toConstIdentifier(rawIdent)
            sink.registerSymbol(ktConst, subpkg)
            cursor.extractConstOrStaticTypeLeaf(idIdx, endIdx)?.let { leaf ->
                sink.constLeafTargets += Triple(subpkg, ktConst, leaf)
            }
        }
        return endIdx
    }

    companion object {
        private val IGNORED_SCANNER_MOD_NAMES = setOf("tests", "test", "private", "sealed")
        private val TRAIT_QUALIFIERS = setOf("const", "unsafe", "auto")
        private val IMPL_QUALIFIERS = setOf("const", "unsafe", "default")
    }
}

internal data class HeaderGenericTypeParams(
    val paramNames: Set<String>,
    val typeArity: Int,
    val nextIdx: Int,
)

internal open class RustTypeSliceInspector(
    trees: List<TokenTree>,
) : RustTokenCursor(trees) {
    fun stripLeadingScannerAttrs(): List<TokenTree> {
        var i = 0
        val attrs = mutableListOf<List<TokenTree>>()
        while (punctCharAt(i) == '#' && groupAt(i + 1, Delimiter.Bracket) != null) {
            attrs += groupAt(i + 1, Delimiter.Bracket)!!.stream().toList()
            i += 2
        }
        if (ScannerAttrGroupInspector(attrs).hasSkippedAttrs()) return emptyList()
        return if (i == 0) trees else trees.subList(i, size)
    }

    fun unwrapPaddingOrMaybeUninit(): List<TokenTree> {
        if (trees.isEmpty()) return trees
        val lastIdentIdx = findPathTailIdentIndex() ?: return trees
        val tailName = identAt(lastIdentIdx)
        if (tailName != "Padding" && tailName != "MaybeUninit") return trees
        if (punctCharAt(lastIdentIdx + 1) != '<' || punctCharAt(size - 1) != '>') return trees
        val inner = trees.subList(lastIdentIdx + 2, size - 1)
        return if (inner.isNotEmpty()) RustTypeSliceInspector(inner).unwrapPaddingOrMaybeUninit() else trees
    }

    fun extractOptionAngleInnerTokens(): List<TokenTree>? {
        val tailIdx = findPathTailIdentIndex() ?: return null
        if (identAt(tailIdx) != "Option") return null
        if (punctCharAt(tailIdx + 1) != '<' || punctCharAt(size - 1) != '>') return null
        val inner = trees.subList(tailIdx + 2, size - 1)
        return inner.takeIf { it.isNotEmpty() }
    }

    fun isVariadicDotsClause(): Boolean {
        val stripped = stripLeadingScannerAttrs()
        return stripped.size == 3 && stripped.all { (it as? TokenTree.Punct)?.value?.asChar() == '.' }
    }

    fun extractBareFnParamTypeTokens(): List<TokenTree> {
        val stripped = stripLeadingScannerAttrs()
        val c = RustTokenCursor(stripped)
        if (c.size >= 3 && c.identAt(0) != null && c.isSingleColonAt(1)) {
            return stripped.subList(2, c.size)
        }
        if (c.size >= 4 && c.identAt(0) == "mut" && c.identAt(1) != null && c.isSingleColonAt(2)) {
            return stripped.subList(3, c.size)
        }
        return stripped
    }

    fun tryParseBareFnTokens(): Pair<List<List<TokenTree>>, List<TokenTree>>? {
        val stripped = stripLeadingScannerAttrs()
        val c = RustTokenCursor(stripped)
        var i = 0
        if (c.identAt(i) == "for" && c.punctCharAt(i + 1) == '<') {
            i = c.skipBalancedAngles(i + 1) ?: c.size
        }
        if (c.identAt(i) == "unsafe") i++
        if (c.identAt(i) == "extern") {
            i++
            if (stripped.getOrNull(i) is TokenTree.Literal) i++
        }
        if (c.identAt(i) != "fn") return null
        val paramsGroup = c.groupAt(i + 1, Delimiter.Parenthesis) ?: return null
        val paramClauses =
            RustTokenCursor(paramsGroup.stream().toList())
                .splitByTopLevelPunct(',')
                .filter { it.isNotEmpty() && !RustTypeSliceInspector(it).isVariadicDotsClause() }
                .map { clause -> RustTypeSliceInspector(clause).extractBareFnParamTypeTokens() }
        val afterParams = i + 2
        val retTokens =
            if (afterParams + 1 < c.size && c.punctCharAt(afterParams) == '-' && c.punctCharAt(afterParams + 1) == '>') {
                stripped.subList(afterParams + 2, c.size)
            } else {
                emptyList()
            }
        return paramClauses to retTokens
    }

    fun isResultReturnSlice(): Boolean {
        val pathIdents = mutableListOf<String>()
        for (i in 0 until size) {
            val id = cleanIdentAt(i)
            when {
                id != null -> pathIdents += id
                punctCharAt(i) == ':' -> Unit
                else -> break
            }
        }
        if (pathIdents.lastOrNull() != "Result") return false
        return pathIdents.getOrNull(pathIdents.lastIndex - 1) != "fmt"
    }

    fun isFieldlessNonEmptyEnumBody(): Boolean {
        var variantCount = 0
        for (clause in splitByTopLevelPunct(',')) {
            val stripped = RustTypeSliceInspector(clause).stripLeadingScannerAttrs()
            if (stripped.isEmpty()) continue
            variantCount++
            val c = RustTokenCursor(stripped)
            for (idx in 0 until c.size) {
                val delim = c.groupAt(idx)?.delimiter()
                if (delim == Delimiter.Brace || delim == Delimiter.Parenthesis) return false
                if (c.punctCharAt(idx) == '=') break
            }
        }
        return variantCount > 0
    }

    fun containsExcludedTypeParam(excludedTypeParamNames: Set<String>): Boolean {
        if (excludedTypeParamNames.isEmpty()) return false
        for (tt in trees) {
            when (tt) {
                is TokenTree.Ident -> if (tt.value.toString().removePrefix("r#") in excludedTypeParamNames) return true
                is TokenTree.Group -> {
                    val inner = tt.value.stream().toList()
                    val subTokens =
                        if (tt.value.delimiter() == Delimiter.Bracket) {
                            val semiIdx = inner.indexOfFirst { (it as? TokenTree.Punct)?.value?.asChar() == ';' }
                            if (semiIdx > 0) inner.subList(0, semiIdx) else inner
                        } else {
                            inner
                        }
                    if (RustTypeSliceInspector(subTokens).containsExcludedTypeParam(excludedTypeParamNames)) return true
                }
                else -> Unit
            }
        }
        return false
    }

    fun mapFieldTypeQuick(
        rawAbiMode: Boolean,
        selfOwnerKt: String? = null,
        insideExternBlock: Boolean = false,
        numericKinds: Map<String, String>? = null,
        subpkg: String = "",
    ): String? {
        if (trees.isEmpty()) return null
        val afterPub = stripLeadingPubVisibility()
        if (afterPub.size != size) {
            return RustTypeSliceInspector(afterPub).mapFieldTypeQuick(
                rawAbiMode = rawAbiMode,
                selfOwnerKt = selfOwnerKt,
                insideExternBlock = insideExternBlock,
                numericKinds = numericKinds,
                subpkg = subpkg,
            )
        }
        if (isUnitParens()) return "Unit"
        if (punctCharAt(0) == '*') {
            if (rawAbiMode) return "Long"
            val mutOrConst = identAt(1)
            val ptrWrapper = if (mutOrConst == "mut") "MutPtr" else "ConstPtr"
            val pointeeStart = if (mutOrConst == "mut" || mutOrConst == "const") 2 else 1
            val pointeeSlice = RustTypeSliceInspector(trees.subList(pointeeStart, size))
            if (pointeeSlice.punctCharAt(0) == '*') {
                val innerPointee =
                    pointeeSlice.mapFieldTypeQuick(
                        rawAbiMode = false,
                        selfOwnerKt = selfOwnerKt,
                        insideExternBlock = insideExternBlock,
                        numericKinds = numericKinds,
                        subpkg = subpkg,
                    ) ?: "CVoid"
                return "$ptrWrapper<$innerPointee>"
            }
            val pointeeLeaf = pointeeSlice.simplePathLeafIdent() ?: return "$ptrWrapper<CVoid>"
            val pointeeKt =
                when (pointeeLeaf) {
                    "c_void" -> "CVoid"
                    "usize", "isize" -> "Int"
                    "Self" -> selfOwnerKt ?: "CVoid"
                    else -> {
                        val kind = SCANNER_RUST_TO_SCALAR_KIND[pointeeLeaf] ?: SCANNER_C_ALIAS_TO_SCALAR_KIND[pointeeLeaf]
                        kind?.let { SCANNER_SCALAR_DESCRIPTORS_BY_KIND[it]?.kotlinType }
                            ?: KotlinNamingPolicy.Default.toTypeIdentifier(pointeeLeaf, allowRawAbi = false).ifEmpty { "CVoid" }
                    }
                }
            return "$ptrWrapper<$pointeeKt>"
        }
        val bracketGrp = if (size == 1) groupAt(0, Delimiter.Bracket) else null
        if (bracketGrp != null) {
            val inner = bracketGrp.stream().toList()
            val semiIdx = inner.indexOfFirst { (it as? TokenTree.Punct)?.value?.asChar() == ';' }
            if (semiIdx > 0) {
                val elemLeaf = RustTokenCursor(inner.subList(0, semiIdx)).simplePathLeafIdent()
                val elemKt = elemLeaf?.let { KotlinNamingPolicy.Default.toTypeIdentifier(it, allowRawAbi = rawAbiMode) }
                val resolvedElemKind =
                    if (elemKt != null && numericKinds != null) {
                        (if (subpkg.isNotEmpty()) numericKinds["$subpkg.$elemKt"] else null) ?: numericKinds[elemKt]
                    } else {
                        null
                    }
                if (resolvedElemKind != null && resolvedElemKind != "BOOL") {
                    return SCANNER_SCALAR_DESCRIPTORS_BY_KIND[resolvedElemKind]?.arrayType
                }
                return when (elemLeaf) {
                    "c_char" -> if (rawAbiMode) "ByteArray" else "UByteArray"
                    "usize" -> if (rawAbiMode) "ULongArray" else "IntArray"
                    "isize" -> if (rawAbiMode) "LongArray" else "IntArray"
                    else -> {
                        val kind = elemLeaf?.let { SCANNER_RUST_TO_SCALAR_KIND[it] ?: SCANNER_C_ALIAS_TO_SCALAR_KIND[it] }
                        kind?.let { SCANNER_SCALAR_DESCRIPTORS_BY_KIND[it]?.arrayType }
                    }
                }
            }
        }
        val leaf = simplePathLeafIdent() ?: return null
        if (leaf == "Self") return selfOwnerKt
        return when (leaf) {
            "usize" -> if (rawAbiMode) (if (TranslationSession.cfgTargetProfile.pointerWidth == 64) "ULong" else "UInt") else "Int"
            "isize" -> if (rawAbiMode) (if (TranslationSession.cfgTargetProfile.pointerWidth == 64) "Long" else "Int") else "Int"
            else -> {
                val kind = SCANNER_RUST_TO_SCALAR_KIND[leaf]
                kind?.let { SCANNER_SCALAR_DESCRIPTORS_BY_KIND[it]?.kotlinType }
                    ?: KotlinNamingPolicy.Default.toTypeIdentifier(leaf, allowRawAbi = rawAbiMode).takeIf { it.isNotEmpty() }
            }
        }
    }
}

internal class RustItemHeaderTokenScanner(
    trees: List<TokenTree>,
) : RustTypeSliceInspector(trees) {
    fun skipQualifiers(startIdx: Int, qualifiers: Set<String>): Int {
        var idx = startIdx
        while (identAt(idx) in qualifiers) idx++
        return idx
    }

    fun extractConstOrStaticTypeLeaf(identIdx: Int, endIdx: Int): String? {
        if (!isSingleColonAt(identIdx + 1)) return null
        val typeTokens = mutableListOf<TokenTree>()
        var k = identIdx + 2
        val limit = minOf(endIdx, size)
        while (k < limit) {
            val ch = punctCharAt(k)
            if (ch == '=' || ch == ';') break
            typeTokens += trees[k]
            k++
        }
        return RustTokenCursor(typeTokens).simplePathLeafIdent()
    }

    fun matchExternBlockBraceIndex(startIdx: Int): Int? {
        var j = startIdx
        if (identAt(j) == "unsafe") j++
        if (identAt(j) != "extern") return null
        j++
        if (trees.getOrNull(j) is TokenTree.Literal) j++
        return if (groupAt(j, Delimiter.Brace) != null) j else null
    }

    fun matchPathMacroGroupIndex(startIdx: Int): Int? {
        val tailIdx = findPathTailIdentIndex(startIdx) ?: return null
        val firstIdentIdx = if (punctCharAt(startIdx) == ':' && punctCharAt(startIdx + 1) == ':') startIdx + 2 else startIdx
        if (identAt(firstIdentIdx) in SCANNER_ITEM_KEYWORDS) return null
        if (punctCharAt(tailIdx + 1) != '!' || groupAt(tailIdx + 2) == null) return null
        return tailIdx + 2
    }

    private fun findLinkMacroFnIndex(): Int? {
        if (size < 4 || trees[0] !is TokenTree.Literal) return null
        for (k in 1 until size - 2) {
            if (identAt(k) == "fn") return k
        }
        return null
    }

    fun extractLinkMacroFnIdent(): String? {
        val fnIdx = findLinkMacroFnIndex() ?: return null
        val fnIdent = cleanIdentAt(fnIdx + 1)
        return if (fnIdent != null && groupAt(fnIdx + 2, Delimiter.Parenthesis) != null) fnIdent else null
    }

    fun extractLinkMacroFnParamTokenLists(): List<List<TokenTree>>? {
        val fnIdx = findLinkMacroFnIndex() ?: return null
        return extractNonMethodFnParamTokenLists(fnIdx)
    }

    fun extractLinkMacroFnReturnType(rawAbiMode: Boolean): String? {
        val fnIdx = findLinkMacroFnIndex() ?: return null
        return extractFnReturnTypeFromHeader(fnIdx, size, rawAbiMode, insideExternBlock = true)
    }

    fun findFnIdentAfterQualifiers(startIdx: Int): String? {
        val fnIdx = skipFnQualifiersToFnKeyword(startIdx, allowBareLiteral = true) ?: return null
        return cleanIdentAt(fnIdx + 1)
    }

    fun extractFnReturnTypeFromHeader(
        startIdx: Int,
        endIdx: Int,
        rawAbiMode: Boolean,
        selfOwnerKt: String? = null,
        insideExternBlock: Boolean = false,
    ): String? {
        val fnIdx = skipFnQualifiersToFnKeyword(startIdx, endIdx) ?: return null
        var afterIdent = fnIdx + 2
        if (punctCharAt(afterIdent) == '<') {
            afterIdent = skipBalancedAngles(afterIdent) ?: return null
        }
        if (groupAt(afterIdent, Delimiter.Parenthesis) == null) return null
        var k = afterIdent + 1
        val limit = minOf(endIdx, size)
        while (k < limit) {
            when {
                punctCharAt(k) == ';' || groupAt(k, Delimiter.Brace) != null || identAt(k) == "where" -> return "Unit"
                isRArrowStartAt(k) -> {
                    val retTokens = mutableListOf<TokenTree>()
                    var r = k + 2
                    while (r < limit) {
                        if (punctCharAt(r) == ';' || groupAt(r, Delimiter.Brace) != null || identAt(r) == "where") break
                        retTokens += trees[r]
                        r++
                    }
                    val unwrapped = RustTypeSliceInspector(retTokens).unwrapPaddingOrMaybeUninit()
                    return RustTypeSliceInspector(unwrapped).mapFieldTypeQuick(
                        rawAbiMode = rawAbiMode,
                        selfOwnerKt = selfOwnerKt,
                        insideExternBlock = insideExternBlock,
                    )
                }
            }
            k++
        }
        return "Unit"
    }

    fun extractHeaderGenericTypeParams(
        startIdx: Int,
        rawAbiMode: Boolean,
    ): HeaderGenericTypeParams {
        val afterCloseIdx = skipBalancedAngles(startIdx) ?: return HeaderGenericTypeParams(emptySet(), 0, startIdx)
        val inner = trees.subList(startIdx + 1, afterCloseIdx - 1)
        val clauses = RustTokenCursor(inner).splitByTopLevelPunct(',', unconditionalAngles = true)
        val typeParams = linkedSetOf<String>()
        var typeArity = 0
        for (clause in clauses) {
            val stripped = RustTypeSliceInspector(clause).stripLeadingScannerAttrs()
            val c = RustTokenCursor(stripped)
            if (c.size == 0) continue
            if (c.punctCharAt(0) == '\'' || c.identAt(0) == "const") continue
            val paramIdent = c.cleanIdentAt(0) ?: continue
            typeArity++
            typeParams += paramIdent
            val ktParam = KotlinNamingPolicy.Default.toTypeIdentifier(paramIdent, allowRawAbi = rawAbiMode)
            if (ktParam.isNotEmpty()) typeParams += ktParam
        }
        return HeaderGenericTypeParams(typeParams, typeArity, afterCloseIdx)
    }

    private fun findEnumBodyBraceIndex(startIdx: Int): Int? {
        var j = startIdx
        while (j < size) {
            if (punctCharAt(j) == ';') return null
            if (groupAt(j, Delimiter.Brace) != null) return j
            j++
        }
        return null
    }

    fun findStructOrUnionBodyGroup(startIdx: Int): Group? {
        var j = startIdx
        while (j < size) {
            if (punctCharAt(j) == ';') return null
            val grp = groupAt(j)
            if (grp != null && (grp.delimiter() == Delimiter.Brace || grp.delimiter() == Delimiter.Parenthesis)) {
                return grp
            }
            j++
        }
        return null
    }

    private fun countEnumLiftedAssocs(
        explicitParamNames: Set<String>,
        traitDefaultlessAssocTypes: Map<String, List<String>>,
    ): Int {
        val lifted = linkedSetOf<String>()
        var inBound = false
        var boundAngleDepth = 0
        for (k in 0 until size) {
            val ch = punctCharAt(k)
            val id = cleanIdentAt(k)
            when {
                ch == '<' && !isRArrowEndAt(k) -> if (inBound) boundAngleDepth++
                ch == '>' && !isRArrowEndAt(k) -> if (inBound && boundAngleDepth > 0) boundAngleDepth--
                ch == ':' && boundAngleDepth == 0 -> inBound = true
                ch == ',' && boundAngleDepth == 0 -> inBound = false
                id != null && inBound && boundAngleDepth == 0 -> {
                    val assocs =
                        when (id) {
                            "ToOwned" -> listOf("Owned")
                            "Deref", "DerefMut" -> listOf("Target")
                            "Iterator", "DoubleEndedIterator", "ExactSizeIterator", "FusedIterator", "TrustedLen", "AsyncIterator" ->
                                listOf("Item")
                            else -> traitDefaultlessAssocTypes[KotlinNamingPolicy.Default.toTypeIdentifier(id, allowRawAbi = false)].orEmpty()
                        }
                    for (a in assocs) {
                        if (a !in explicitParamNames) lifted += a
                    }
                }
            }
        }
        return lifted.size
    }

    fun scanEnumVariantTypeArities(
        afterIdentIdx: Int,
        subpkg: String,
        ktEnum: String,
        sink: CrateTokenScanSink,
    ) {
        val rawAbiMode = sink.rawAbiMode
        val out = sink.enumVariantTypeArity
        val unitEnumVariants = sink.unitEnumVariants
        val headerParams = extractHeaderGenericTypeParams(afterIdentIdx, rawAbiMode)
        val braceIdx = findEnumBodyBraceIndex(headerParams.nextIdx) ?: return
        val braceGrp = groupAt(braceIdx, Delimiter.Brace) ?: return
        val headerSlice = if (braceIdx > afterIdentIdx) trees.subList(afterIdentIdx, braceIdx) else emptyList()
        val extraLiftedAssocs =
            RustItemHeaderTokenScanner(headerSlice).countEnumLiftedAssocs(headerParams.paramNames, sink.traitDefaultlessAssocTypes)
        val typeArity = headerParams.typeArity + extraLiftedAssocs
        for (clause in RustTokenCursor(braceGrp.stream().toList()).splitByTopLevelPunct(',')) {
            val stripped = RustTypeSliceInspector(clause).stripLeadingScannerAttrs()
            val clauseCursor = RustTokenCursor(stripped)
            val rawVar = clauseCursor.cleanIdentAt(0) ?: continue
            val ktVar = KotlinNamingPolicy.Default.toTypeIdentifier(rawVar, allowRawAbi = rawAbiMode)
            if (ktVar.isEmpty()) continue
            val payloadDelim = clauseCursor.groupAt(1)?.delimiter()
            val hasPayload = payloadDelim == Delimiter.Parenthesis || payloadDelim == Delimiter.Brace
            if (!hasPayload || typeArity > 0) {
                out.putIfMissing("$ktEnum.$ktVar", typeArity)
                if (subpkg.isNotEmpty()) {
                    out.putIfMissing("$subpkg.$ktEnum.$ktVar", typeArity)
                }
            }
            if (!hasPayload && typeArity > 0) {
                unitEnumVariants += "$ktEnum.$ktVar"
                if (subpkg.isNotEmpty()) {
                    unitEnumVariants += "$subpkg.$ktEnum.$ktVar"
                }
                unitEnumVariants += ktVar
            }
            val prevSimple = out[ktVar]
            when {
                prevSimple == null -> out[ktVar] = typeArity
                (typeArity > 0 && prevSimple != typeArity) || (typeArity == 0 && prevSimple > 0) -> out[ktVar] = -1
            }
        }
    }

    fun scanTraitTokenMetadata(
        startAfterIdent: Int,
        subpkg: String,
        ktTrait: String,
        sink: CrateTokenScanSink,
    ) {
        val braceIdx = findEnumBodyBraceIndex(startAfterIdent) ?: return
        val bodyScanner = RustItemHeaderTokenScanner(groupAt(braceIdx, Delimiter.Brace)!!.stream().toList())
        val assocNames = mutableListOf<String>()
        val resultMethods = linkedSetOf<String>()
        val itemAttrs = mutableListOf<List<TokenTree>>()
        var j = 0
        while (j < bodyScanner.size) {
            bodyScanner.consumeAttribute(j, itemAttrs, allowInnerAttr = false)?.let {
                j = it
                continue
            }
            val skipped = ScannerAttrGroupInspector(itemAttrs).hasSkippedAttrs()
            when (bodyScanner.identAt(j)) {
                "type" -> {
                    val assocRaw = bodyScanner.identAt(j + 1)
                    val endSemi = ((j + 2) until bodyScanner.size).firstOrNull { bodyScanner.punctCharAt(it) == ';' } ?: bodyScanner.size
                    val hasDefault = bodyScanner.anyTopLevelOutsideAngles(j + 2, endSemi) { bodyScanner.punctCharAt(it) == '=' }
                    if (!skipped && assocRaw != null && !hasDefault) {
                        val assocKt = KotlinNamingPolicy.Default.toTypeIdentifier(assocRaw, allowRawAbi = false)
                        if (assocKt !in assocNames) assocNames += assocKt
                    }
                    j = endSemi + 1
                    itemAttrs.clear()
                    continue
                }
                "fn" -> {
                    val fnRaw = bodyScanner.identAt(j + 1)
                    var k = j + 2
                    var arrowIdx = -1
                    var endIdx = bodyScanner.size
                    while (k < bodyScanner.size) {
                        if (bodyScanner.isRArrowStartAt(k)) {
                            if (arrowIdx < 0) arrowIdx = k + 2
                            k += 2
                            continue
                        }
                        if (bodyScanner.punctCharAt(k) == ';' || bodyScanner.groupAt(k, Delimiter.Brace) != null) {
                            endIdx = k
                            break
                        }
                        k++
                    }
                    if (!skipped && fnRaw != null && arrowIdx in 0 until endIdx) {
                        if (RustTypeSliceInspector(bodyScanner.trees.subList(arrowIdx, endIdx)).isResultReturnSlice()) {
                            resultMethods += rustFunctionIdentToKotlin(fnRaw)
                        }
                    }
                    j = endIdx + 1
                    itemAttrs.clear()
                    continue
                }
            }
            if (bodyScanner.punctCharAt(j) == ';') itemAttrs.clear()
            j++
        }
        if (assocNames.isNotEmpty()) {
            val list = assocNames.toList()
            if (subpkg.isNotEmpty()) sink.traitDefaultlessAssocTypes["$subpkg.$ktTrait"] = list
            sink.traitDefaultlessAssocTypes.putIfMissing(ktTrait, list)
        }
        if (resultMethods.isNotEmpty()) {
            val list = resultMethods.toList()
            if (subpkg.isNotEmpty()) sink.traitResultMethods["$subpkg.$ktTrait"] = list
            sink.traitResultMethods.putIfMissing(ktTrait, list)
        }
    }

    fun scanPubTypeDeclaration(
        typeKwIdx: Int,
        subpkg: String,
        skippedByCfg: Boolean,
        sink: CrateTokenScanSink,
    ): Int {
        val rawAbiMode = sink.rawAbiMode
        val rawIdent = identAt(typeKwIdx + 1)
        val endIdx = skipAfterItemHeader(typeKwIdx + 1, semicolonOnly = true)
        if (rawIdent == null || skippedByCfg) return endIdx

        val ktName = KotlinNamingPolicy.Default.toTypeIdentifier(rawIdent, allowRawAbi = rawAbiMode)
        sink.registerSymbol(ktName, subpkg)

        val afterIdent = typeKwIdx + 2
        if (punctCharAt(afterIdent) != '=') return endIdx
        val semiIdx = endIdx - 1
        if (semiIdx <= afterIdent) return endIdx
        val rhsTokens = trees.subList(afterIdent + 1, semiIdx)
        if (rhsTokens.isEmpty()) return endIdx
        val rhsSlice = RustTypeSliceInspector(rhsTokens)

        val optionFnParsed = rhsSlice.extractOptionAngleInnerTokens()?.let { RustTypeSliceInspector(it).tryParseBareFnTokens() }
        if (optionFnParsed != null) {
            sink.addScopedOptionFnType(subpkg, ktName)
            sink.putScopedDefault(subpkg, ktName, "null")
            val samFnName = "${ktName}Fn"
            sink.registerSymbol(samFnName, subpkg)
            val scopedKey = if (subpkg.isEmpty()) samFnName else "$subpkg.$samFnName"
            sink.fnTypeSpecs.putIfMissing(
                scopedKey,
                RawFnTypeTokenSpec(
                    subpkg = subpkg,
                    aliasKtName = ktName,
                    samKtName = samFnName,
                    isOptionWrapped = true,
                    paramTokenLists = optionFnParsed.first,
                    retTokens = optionFnParsed.second,
                ),
            )
            return endIdx
        }

        val bareFnParsed = rhsSlice.tryParseBareFnTokens()
        if (bareFnParsed != null) {
            val scopedKey = if (subpkg.isEmpty()) ktName else "$subpkg.$ktName"
            sink.fnTypeSpecs.putIfMissing(
                scopedKey,
                RawFnTypeTokenSpec(
                    subpkg = subpkg,
                    aliasKtName = ktName,
                    samKtName = ktName,
                    isOptionWrapped = false,
                    paramTokenLists = bareFnParsed.first,
                    retTokens = bareFnParsed.second,
                ),
            )
            return endIdx
        }

        if (rhsSlice.punctCharAt(0) == '*') {
            val qual = rhsSlice.identAt(1)
            val isMut = qual == "mut"
            val elemStart = if (qual == "mut" || qual == "const") 2 else 1
            val elemSlice = RustTokenCursor(if (elemStart < rhsSlice.size) rhsTokens.subList(elemStart, rhsSlice.size) else emptyList())
            val pointeeIdent = if (elemSlice.punctCharAt(0) == '*') "*" else elemSlice.simplePathLeafIdent() ?: "*"
            sink.putScopedPointerAlias(subpkg, ktName, isMut to pointeeIdent)
            if (rawAbiMode) {
                sink.putScopedDefault(subpkg, ktName, "0L")
                sink.putScopedNumericKind(subpkg, ktName, "I64")
            } else {
                sink.putScopedDefault(subpkg, ktName, if (isMut) "MutPtr(0L)" else "ConstPtr(0L)")
            }
            return endIdx
        }

        if (rhsSlice.isUnitParens()) {
            sink.putScopedDefault(subpkg, ktName, "Unit")
            return endIdx
        }

        if (rhsSlice.size == 1 && rhsSlice.groupAt(0, Delimiter.Bracket) != null) {
            val mapped = rhsSlice.mapFieldTypeQuick(rawAbiMode)
            val defaultExpr = if (mapped != null && mapped.endsWith("Array")) "$mapped(0)" else "emptyList()"
            sink.putScopedDefault(subpkg, ktName, defaultExpr)
            return endIdx
        }

        if (rhsSlice.groupAt(0) == null) {
            val leaf = rhsSlice.simplePathLeafIdent()
            if (leaf != null && (0 until rhsSlice.size).none { rhsSlice.punctCharAt(it) == '<' }) {
                sink.aliasLeafTargets.putIfMissing(ktName, leaf)
                if (subpkg.isNotEmpty()) {
                    sink.qualifiedAliasLeafTargets.putIfMissing("$subpkg.$ktName", subpkg to leaf)
                }
            }
        }
        return endIdx
    }

    fun extractNonMethodFnParamTokenLists(startIdx: Int): List<List<TokenTree>>? {
        val fnIdx = skipFnQualifiersToFnKeyword(startIdx) ?: return null
        var afterIdent = fnIdx + 2
        if (punctCharAt(afterIdent) == '<') {
            afterIdent = skipBalancedAngles(afterIdent) ?: return null
        }
        val paramsGrp = groupAt(afterIdent, Delimiter.Parenthesis) ?: return null
        val clauses =
            RustTokenCursor(paramsGrp.stream().toList())
                .splitByTopLevelPunct(',')
                .filter { it.isNotEmpty() && !RustTypeSliceInspector(it).isVariadicDotsClause() }
        val paramTypes = mutableListOf<List<TokenTree>>()
        for (clause in clauses) {
            val stripped = RustTypeSliceInspector(clause).stripLeadingScannerAttrs()
            val c = RustTokenCursor(stripped)
            if (c.identAt(0) == "self" || c.identAt(1) == "self" || c.punctCharAt(0) == '&') {
                if ((0 until c.size).any { c.identAt(it) == "self" }) return null
            }
            paramTypes += RustTypeSliceInspector(stripped).extractBareFnParamTypeTokens()
        }
        return paramTypes
    }

    fun extractConstParamNamesFromGenericBrackets(ltIdx: Int): List<String> {
        val closeIdx = skipBalancedAngles(ltIdx) ?: return emptyList()
        if (closeIdx <= ltIdx + 1) return emptyList()
        val inner = trees.subList(ltIdx + 1, closeIdx - 1)
        val constNames = mutableListOf<String>()
        for (clause in RustTokenCursor(inner).splitByTopLevelPunct(',')) {
            val stripped = RustTypeSliceInspector(clause).stripLeadingScannerAttrs()
            val c = RustTokenCursor(stripped)
            if (c.identAt(0) == "const") {
                val identRaw = c.cleanIdentAt(1) ?: continue
                constNames += rustValueIdentToKotlin(identRaw)
            }
        }
        return constNames
    }

    fun extractFnConstParamNamesFromHeader(startIdx: Int): List<String> {
        val fnIdx = skipFnQualifiersToFnKeyword(startIdx, allowBareLiteral = true) ?: return emptyList()
        val afterIdent = fnIdx + 2
        if (punctCharAt(afterIdent) != '<') return emptyList()
        return extractConstParamNamesFromGenericBrackets(afterIdent)
    }

    fun scanInherentImplAssociatedFunctions(
        implKwIdx: Int,
        endImplIdx: Int,
        subpkg: String,
        sink: CrateTokenScanSink,
    ) {
        val rawAbiMode = sink.rawAbiMode
        val headerSlice = trees.subList(implKwIdx + 1, endImplIdx)
        val braceTree = headerSlice.lastOrNull { (it as? TokenTree.Group)?.value?.delimiter() == Delimiter.Brace } as? TokenTree.Group ?: return
        val beforeBrace = headerSlice.subList(0, headerSlice.indexOf(braceTree))
        val beforeCursor = RustItemHeaderTokenScanner(beforeBrace)
        val hasGenericsOrFor =
            (0 until beforeCursor.size).any {
                beforeCursor.punctCharAt(it) == '<' || beforeCursor.identAt(it) == "for"
            }
        var scanIdx = 0
        val implConsts =
            if (beforeCursor.punctCharAt(0) == '<') {
                val closeIdx = beforeCursor.skipBalancedAngles(0) ?: return
                val consts = beforeCursor.extractConstParamNamesFromGenericBrackets(0)
                scanIdx = closeIdx
                consts
            } else {
                emptyList()
            }
        if (scanIdx < beforeCursor.size &&
            beforeCursor.anyTopLevelOutsideAngles(scanIdx) { beforeCursor.identAt(it) == "for" }
        ) {
            return
        }
        val typeHeadTokens =
            beforeBrace
                .subList(scanIdx, beforeBrace.size)
                .takeWhile {
                    val c = (it as? TokenTree.Punct)?.value?.asChar()
                    val kw = (it as? TokenTree.Ident)?.value?.toString()
                    c != '<' && kw != "where"
                }
        val ownerLeaf = RustTokenCursor(typeHeadTokens).simplePathLeafIdent() ?: return
        val ownerKt = KotlinNamingPolicy.Default.toTypeIdentifier(ownerLeaf, allowRawAbi = rawAbiMode)
        if (ownerKt.isEmpty()) return

        val bodyScanner = RustItemHeaderTokenScanner(braceTree.value.stream().toList())
        val pendingAttrs = mutableListOf<List<TokenTree>>()
        var i = 0
        while (i < bodyScanner.size) {
            bodyScanner.consumeAttribute(i, pendingAttrs, allowInnerAttr = false)?.let {
                i = it
                continue
            }
            val skipped = ScannerAttrGroupInspector(pendingAttrs).hasSkippedAttrs()
            if (bodyScanner.identAt(i) == "pub") {
                val kwIdx = bodyScanner.copyOptionalPubVisibility(i)
                val fnIdent = bodyScanner.findFnIdentAfterQualifiers(kwIdx)
                val endFnIdx = bodyScanner.skipAfterItemHeader(kwIdx, semicolonOnly = fnIdent == null)
                if (fnIdent != null && !skipped) {
                    val ktMethod = rustFunctionIdentToKotlin(fnIdent)
                    val pLists = bodyScanner.extractNonMethodFnParamTokenLists(kwIdx)
                    if (!hasGenericsOrFor) {
                        val retTy = bodyScanner.extractFnReturnTypeFromHeader(kwIdx, endFnIdx, rawAbiMode, ownerKt)
                        if (pLists != null || retTy != null) {
                            sink.registerFnSig(subpkg, "$ownerKt.$ktMethod", pLists, retTy, false)
                        }
                    }
                    val combinedConsts = (implConsts + bodyScanner.extractFnConstParamNamesFromHeader(kwIdx)).distinct()
                    if (pLists != null && combinedConsts.isNotEmpty()) {
                        sink.registerFnConstParams(subpkg, "$ownerKt.$ktMethod", pLists.size, combinedConsts)
                    }
                }
                i = endFnIdx
                pendingAttrs.clear()
                continue
            }
            if (bodyScanner.punctCharAt(i) == ';' || bodyScanner.groupAt(i, Delimiter.Brace) != null) {
                pendingAttrs.clear()
            }
            i++
        }
    }

    companion object {
        val SCANNER_ITEM_KEYWORDS =
            wordSet("pub mod struct union enum trait type const static fn unsafe async auto extern use impl")

        fun parseRecordFieldsFromGroup(
            bodyGroup: Group,
            rawAbiMode: Boolean,
            structFieldTypesOut: MutableMap<String, String>,
            ownerKtType: String,
            subpkg: String = "",
            isUnion: Boolean = false,
            excludedTypeParamNames: Set<String> = emptySet(),
        ): List<RawFieldTokenSpec> {
            val clauses = RustTokenCursor(bodyGroup.stream().toList()).splitByTopLevelPunct(',')
            val specs = mutableListOf<RawFieldTokenSpec>()
            val seenNames = hashSetOf<String>()
            val makeNullableForUnion = isUnion && !rawAbiMode && !TranslationSession.finalStructs

            fun recordFieldQuickType(ktField: String, rhsSlice: RustTypeSliceInspector) {
                if (rhsSlice.containsExcludedTypeParam(excludedTypeParamNames)) return
                rhsSlice.mapFieldTypeQuick(rawAbiMode)?.let { mappedTy ->
                    val recordedTy = if (makeNullableForUnion && !mappedTy.endsWith("?")) "$mappedTy?" else mappedTy
                    structFieldTypesOut.putIfMissing("$ownerKtType.$ktField", recordedTy)
                    if (subpkg.isNotEmpty()) {
                        structFieldTypesOut.putIfMissing("$subpkg.$ownerKtType.$ktField", recordedTy)
                    }
                }
            }

            if (bodyGroup.delimiter() == Delimiter.Brace) {
                for (clause in clauses) {
                    val strippedAttrs = RustTypeSliceInspector(clause).stripLeadingScannerAttrs()
                    if (strippedAttrs.isEmpty()) continue
                    val afterVis = RustTokenCursor(strippedAttrs).stripLeadingPubVisibility()
                    val fieldCursor = RustTokenCursor(afterVis)
                    if (fieldCursor.size < 3) continue
                    val rawField = fieldCursor.identAt(0) ?: continue
                    if (!fieldCursor.isSingleColonAt(1)) continue
                    val ktField = rustFieldIdentToKotlin(rawField)
                    if (!seenNames.add(ktField)) continue
                    val rhsTokens = RustTypeSliceInspector(afterVis.subList(2, fieldCursor.size)).unwrapPaddingOrMaybeUninit()
                    recordFieldQuickType(ktField, RustTypeSliceInspector(rhsTokens))
                    specs += RawFieldTokenSpec(ktName = ktField, typeTokens = rhsTokens)
                }
            } else if (bodyGroup.delimiter() == Delimiter.Parenthesis) {
                val rawFieldTypes = mutableListOf<List<TokenTree>>()
                for (clause in clauses) {
                    val strippedAttrs = RustTypeSliceInspector(clause).stripLeadingScannerAttrs()
                    if (strippedAttrs.isEmpty()) continue
                    val afterVis = RustTokenCursor(strippedAttrs).stripLeadingPubVisibility()
                    if (afterVis.isEmpty()) continue
                    rawFieldTypes += RustTypeSliceInspector(afterVis).unwrapPaddingOrMaybeUninit()
                }
                val arity = rawFieldTypes.size
                for ((idx, rhsTokens) in rawFieldTypes.withIndex()) {
                    val ktField = tupleFieldPropertyName(idx, arity)
                    recordFieldQuickType(ktField, RustTypeSliceInspector(rhsTokens))
                    specs += RawFieldTokenSpec(ktName = ktField, typeTokens = rhsTokens)
                }
            }
            return specs
        }
    }
}

internal fun discoverSharedInlineModuleRelFiles(byRelPath: Map<String, String>): Set<String> {
    val result = linkedSetOf<String>()
    for ((relFile, rootText) in byRelPath) {
        val fileName = relFile.substringAfterLast('/')
        if (!isRustModuleRootFileName(fileName)) continue
        val relDir = relFile.substringBeforeLast('/', missingDelimiterValue = "")
        val dirPrefix = if (relDir.isEmpty()) "" else "$relDir/"
        val rootTrees = lexScannerTokens(rootText)
        if (rootTrees.isEmpty()) continue

        val macroUseStems = linkedSetOf<String>()
        val topLevelPrivateMods = linkedSetOf<String>()
        val nestedPrivateModCounts = linkedMapOf<String, Int>()
        val pubUseGlobStems = linkedSetOf<String>()

        val rootPatternScanner = InlineModulePatternTokenScanner(rootTrees)
        rootPatternScanner.scanPatterns(
            isTopLevel = true,
            multiplier = 1,
            macroUseStemsOut = macroUseStems,
            topLevelPrivateModsOut = topLevelPrivateMods,
            nestedPrivateModCountsOut = nestedPrivateModCounts,
            pubUseGlobStemsOut = pubUseGlobStems,
        )

        val zeroArgCallCounts = rootPatternScanner.countZeroArgMacroCalls()
        for (macroStem in macroUseStems) {
            val macroRel = "$dirPrefix$macroStem.rs"
            val macroSubMod = "$dirPrefix$macroStem/mod.rs"
            if (macroRel in byRelPath && macroSubMod !in byRelPath) {
                val macroTrees = lexScannerTokens(byRelPath.getValue(macroRel))
                val shouldInlineMacroMod =
                    macroStem == "macros" ||
                        macroStem.endsWith("_macros") ||
                        (macroUseStems.size == 1 && !InlineModulePatternTokenScanner(macroTrees).containsTopLevelTypeDeclTokens())
                if (shouldInlineMacroMod) {
                    result += macroRel
                }
                val macroDefs = LinkedHashMap<String, MacroRulesDef>()
                collectMacroRulesFromTokenTrees(macroTrees, macroDefs)
                for ((mName, mDef) in macroDefs) {
                    val callCount = zeroArgCallCounts[mName] ?: 0
                    if (callCount >= 2) {
                        for (branch in mDef.branches) {
                            if (branch.pattern.isEmpty()) {
                                InlineModulePatternTokenScanner(branch.bodyTokens.toList()).scanPatterns(
                                    isTopLevel = false,
                                    multiplier = callCount,
                                    macroUseStemsOut = macroUseStems,
                                    topLevelPrivateModsOut = topLevelPrivateMods,
                                    nestedPrivateModCountsOut = nestedPrivateModCounts,
                                    pubUseGlobStemsOut = pubUseGlobStems,
                                )
                            }
                        }
                    }
                }
            }
        }

        for (stem in topLevelPrivateMods) {
            val candRel = "$dirPrefix$stem.rs"
            val candSubMod = "$dirPrefix$stem/mod.rs"
            if (stem in pubUseGlobStems && candRel in byRelPath && candSubMod !in byRelPath) {
                result += candRel
            }
        }
        for ((stem, count) in nestedPrivateModCounts) {
            val candRel = "$dirPrefix$stem.rs"
            val candSubMod = "$dirPrefix$stem/mod.rs"
            if (count >= 2 && stem in pubUseGlobStems && candRel in byRelPath && candSubMod !in byRelPath) {
                result += candRel
            }
        }
    }
    return result
}

private class InlineModulePatternTokenScanner(
    trees: List<TokenTree>,
) : RustTokenCursor(trees) {
    fun containsTopLevelTypeDeclTokens(): Boolean {
        var i = 0
        while (i < size) {
            val grp = groupAt(i)
            if (grp != null) {
                if (InlineModulePatternTokenScanner(grp.stream().toList()).containsTopLevelTypeDeclTokens()) return true
                i++
                continue
            }
            val id = identAt(i)
            if (id == "macro_rules" && punctCharAt(i + 1) == '!') {
                var k = i + 2
                if (identAt(k) != null) k++
                if (groupAt(k) != null) k++
                if (punctCharAt(k) == ';') k++
                i = k
                continue
            }
            if (id in setOf("struct", "enum", "union", "trait")) return true
            if (id == "features" && punctCharAt(i + 1) == '!') return true
            i++
        }
        return false
    }

    fun scanPatterns(
        isTopLevel: Boolean,
        multiplier: Int,
        macroUseStemsOut: MutableSet<String>,
        topLevelPrivateModsOut: MutableSet<String>,
        nestedPrivateModCountsOut: MutableMap<String, Int>,
        pubUseGlobStemsOut: MutableSet<String>,
    ) {
        val pendingAttrs = mutableListOf<List<TokenTree>>()
        var i = 0
        while (i < size) {
            consumeAttribute(i, pendingAttrs)?.let {
                i = it
                continue
            }
            val grp = groupAt(i)
            if (grp != null) {
                InlineModulePatternTokenScanner(grp.stream().toList()).scanPatterns(
                    isTopLevel = false,
                    multiplier = multiplier,
                    macroUseStemsOut = macroUseStemsOut,
                    topLevelPrivateModsOut = topLevelPrivateModsOut,
                    nestedPrivateModCountsOut = nestedPrivateModCountsOut,
                    pubUseGlobStemsOut = pubUseGlobStemsOut,
                )
                pendingAttrs.clear()
                i++
                continue
            }
            val curId = identAt(i)
            if (curId == "mod") {
                val prevIsPub =
                    identAt(i - 1) == "pub" ||
                        (i >= 2 && identAt(i - 2) == "pub" && groupAt(i - 1, Delimiter.Parenthesis) != null)
                val modIdent = cleanIdentAt(i + 1)
                if (!prevIsPub && modIdent != null && punctCharAt(i + 2) == ';') {
                    val hasCfg = pendingAttrs.any { (it.firstOrNull() as? TokenTree.Ident)?.value?.toString() == "cfg" }
                    val hasMacroUse = pendingAttrs.any { (it.firstOrNull() as? TokenTree.Ident)?.value?.toString() == "macro_use" }
                    if (isTopLevel && !hasCfg) {
                        if (hasMacroUse) macroUseStemsOut += modIdent
                        topLevelPrivateModsOut += modIdent
                    } else {
                        nestedPrivateModCountsOut[modIdent] = (nestedPrivateModCountsOut[modIdent] ?: 0) + multiplier
                    }
                    pendingAttrs.clear()
                    i += 3
                    continue
                }
            }
            if (curId == "pub") {
                val uIdx = copyOptionalPubVisibility(i)
                if (identAt(uIdx) == "use") {
                    var endUse = uIdx + 1
                    while (endUse < size && punctCharAt(endUse) != ';') {
                        endUse++
                    }
                    if (endUse - (uIdx + 1) >= 4 &&
                        punctCharAt(endUse - 1) == '*' &&
                        punctCharAt(endUse - 2) == ':' &&
                        punctCharAt(endUse - 3) == ':'
                    ) {
                        cleanIdentAt(endUse - 4)?.let { pubUseGlobStemsOut += it }
                    }
                    pendingAttrs.clear()
                    i = if (endUse < size) endUse + 1 else endUse
                    continue
                }
            }
            if (punctCharAt(i) == ';') {
                pendingAttrs.clear()
            }
            i++
        }
    }

    fun countZeroArgMacroCalls(): Map<String, Int> {
        val counts = linkedMapOf<String, Int>()
        collectZeroArgCallsInto(counts)
        return counts
    }

    private fun collectZeroArgCallsInto(counts: MutableMap<String, Int>) {
        var i = 0
        while (i < size) {
            val grp = groupAt(i)
            if (grp != null) {
                InlineModulePatternTokenScanner(grp.stream().toList()).collectZeroArgCallsInto(counts)
                i++
                continue
            }
            val macroGrp = if (identAt(i) != null && punctCharAt(i + 1) == '!') groupAt(i + 2) else null
            if (macroGrp != null) {
                if (macroGrp.stream().isEmpty()) {
                    val mName = cleanIdentAt(i).orEmpty()
                    counts[mName] = (counts[mName] ?: 0) + 1
                } else {
                    InlineModulePatternTokenScanner(macroGrp.stream().toList()).collectZeroArgCallsInto(counts)
                }
                i += 3
                continue
            }
            i++
        }
    }
}
