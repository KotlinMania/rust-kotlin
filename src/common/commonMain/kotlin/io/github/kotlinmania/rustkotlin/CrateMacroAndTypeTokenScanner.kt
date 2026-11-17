package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.procmacro2.Delimiter
import io.github.kotlinmania.procmacro2.Spacing
import io.github.kotlinmania.procmacro2.TokenTree
import io.github.kotlinmania.syn.Attribute
import io.github.kotlinmania.syn.Expr
import io.github.kotlinmania.syn.Item
import io.github.kotlinmania.syn.Lit
import io.github.kotlinmania.syn.Meta
import io.github.kotlinmania.syn.ModContent
import io.github.kotlinmania.syn.UseTree

internal fun tryConsumeScannerAttr(
    trees: List<TokenTree>,
    idx: Int,
    pendingAttrsOut: MutableList<List<TokenTree>>? = null,
    allowInnerAttr: Boolean = true,
): Int? {
    val cur = trees.getOrNull(idx) as? TokenTree.Punct ?: return null
    if (cur.value.asChar() != '#') return null
    if (allowInnerAttr &&
        (trees.getOrNull(idx + 1) as? TokenTree.Punct)?.value?.asChar() == '!' &&
        (trees.getOrNull(idx + 2) as? TokenTree.Group)?.value?.delimiter() == Delimiter.Bracket
    ) {
        return idx + 3
    }
    val attrGrp = trees.getOrNull(idx + 1) as? TokenTree.Group
    if (attrGrp != null && attrGrp.value.delimiter() == Delimiter.Bracket) {
        pendingAttrsOut?.add(attrGrp.value.stream().toList())
        return idx + 2
    }
    return null
}

internal fun skipBalancedAngleBrackets(
    trees: List<TokenTree>,
    startIdx: Int,
): Int? {
    if ((trees.getOrNull(startIdx) as? TokenTree.Punct)?.value?.asChar() != '<') return null
    var depth = 1
    var k = startIdx + 1
    while (k < trees.size && depth > 0) {
        val ch = (trees[k] as? TokenTree.Punct)?.value?.asChar()
        if (ch == '<') {
            depth++
        } else if (ch == '>' && !isScannerRArrow(trees, k)) {
            depth--
        }
        k++
    }
    return if (depth == 0) k else null
}

internal fun skipFnQualifiersAndFindFnKw(
    trees: List<TokenTree>,
    startIdx: Int,
    endIdx: Int = trees.size,
    allowBareLiteral: Boolean = false,
): Int? {
    var j = startIdx
    val limit = minOf(endIdx, trees.size)
    while (j < limit) {
        when (val cur = trees[j]) {
            is TokenTree.Ident ->
                when (cur.value.toString()) {
                    "const", "unsafe", "async" -> j++
                    "extern" -> {
                        j++
                        if (j < limit && trees[j] is TokenTree.Literal) j++
                    }
                    "fn" -> return j
                    else -> return null
                }
            is TokenTree.Literal -> if (allowBareLiteral) j++ else return null
            else -> return null
        }
    }
    return null
}

private fun findLinkMacroFnIndex(innerTrees: List<TokenTree>): Int? {
    if (innerTrees.size < 4 || innerTrees[0] !is TokenTree.Literal) return null
    for (k in 1 until innerTrees.size - 2) {
        if ((innerTrees[k] as? TokenTree.Ident)?.value?.toString() == "fn") {
            return k
        }
    }
    return null
}

private fun extractScannerPathAttrValue(attrs: List<Attribute>): String? {
    for (attr in attrs) {
        val meta = attr.meta
        if (meta is Meta.NameValue && meta.path.isIdent("path")) {
            val expr = meta.value
            if (expr is Expr.Lit && expr.lit is Lit.Str) {
                return (expr.lit as Lit.Str).value.value()
            }
        }
    }
    return null
}

private fun collectScannerUseLeaves(
    tree: UseTree,
    prefix: List<String>,
    out: MutableList<Pair<List<String>, String?>>,
) {
    when (tree) {
        is UseTree.Path -> {
            val seg = tree.ident.toString().removePrefix("r#")
            tree.tree?.let { collectScannerUseLeaves(it, prefix + seg, out) }
        }
        is UseTree.Name -> {
            val seg = tree.ident.toString().removePrefix("r#")
            val alias =
                tree.rename
                    ?.ident
                    ?.toString()
                    ?.removePrefix("r#")
                    ?.takeIf { it != "_" }
            if (seg == "self") {
                if (prefix.isNotEmpty()) {
                    out += prefix to alias
                }
            } else {
                out += (prefix + seg) to alias
            }
        }
        is UseTree.Glob -> out += (prefix + "*") to null
        is UseTree.Group -> {
            for (sub in tree.items.toList()) {
                collectScannerUseLeaves(sub, prefix, out)
            }
        }
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
            is Item.Use -> collectScannerUseLeaves(sub.tree, emptyList(), out)
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
                val modIdent = item.ident.toString().removePrefix("r#")
                if (item.content !is ModContent.Inline) {
                    val pathVal = extractScannerPathAttrValue(item.attrs)?.replace('\\', '/')?.removePrefix("./")
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
            collectScannerUseLeaves(item.tree, emptyList(), leaves)
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
    var i = 0
    while (i < trees.size) {
        val cur = trees[i]
        if ((cur as? TokenTree.Ident)?.value?.toString() == "extern" &&
            (trees.getOrNull(i + 1) as? TokenTree.Ident)?.value?.toString() == "crate" &&
            (trees.getOrNull(i + 2) as? TokenTree.Ident)?.value?.toString() == "self" &&
            (trees.getOrNull(i + 3) as? TokenTree.Ident)?.value?.toString() == "as" &&
            trees.getOrNull(i + 4) is TokenTree.Ident
        ) {
            selfAliasesOut += (trees[i + 4] as TokenTree.Ident).value.toString().removePrefix("r#")
            i += 5
            continue
        }
        if ((cur as? TokenTree.Ident)?.value?.toString() == "include" &&
            (trees.getOrNull(i + 1) as? TokenTree.Punct)?.value?.asChar() == '!' &&
            trees.getOrNull(i + 2) is TokenTree.Group
        ) {
            val grpTokens = (trees[i + 2] as TokenTree.Group).value.stream().toList()
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

internal fun extractPubModIdentsFromTokens(trees: List<TokenTree>): List<String> {
    val result = mutableListOf<String>()
    var i = 0
    while (i < trees.size) {
        if ((trees[i] as? TokenTree.Ident)?.value?.toString() == "pub") {
            var j = i + 1
            if ((trees.getOrNull(j) as? TokenTree.Group)?.value?.delimiter() == Delimiter.Parenthesis) {
                j++
            }
            if ((trees.getOrNull(j) as? TokenTree.Ident)?.value?.toString() == "mod" &&
                trees.getOrNull(j + 1) is TokenTree.Ident &&
                (trees.getOrNull(j + 2) as? TokenTree.Punct)?.value?.asChar() == ';'
            ) {
                result += (trees[j + 1] as TokenTree.Ident).value.toString().removePrefix("r#")
                i = j + 3
                continue
            }
        }
        i++
    }
    return result
}

private fun hasSkippedScannerAttrs(
    attrGroups: List<List<TokenTree>>,
    skipCfgTest: Boolean = true,
): Boolean {
    for (attrTokens in attrGroups) {
        if (attrTokens.size == 2 &&
            (attrTokens[0] as? TokenTree.Ident)?.value?.toString() == "cfg" &&
            (attrTokens[1] as? TokenTree.Group)?.value?.delimiter() == Delimiter.Parenthesis
        ) {
            val inner = (attrTokens[1] as TokenTree.Group).value.stream().toList()
            if (isDocOnlyCfgPredicateTokens(inner)) return true
            if (skipCfgTest && inner.size == 1 && (inner[0] as? TokenTree.Ident)?.value?.toString() == "test") return true
            if (evalCfgTriState(inner, forRuntimeCfgMacro = false) == false) return true
        }
    }
    return false
}

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

        fun extractPathAttrOverride(attrGroups: List<List<TokenTree>>): String? {
            for (attrTokens in attrGroups) {
                if (attrTokens.size == 3 &&
                    (attrTokens[0] as? TokenTree.Ident)?.value?.toString() == "path" &&
                    (attrTokens[1] as? TokenTree.Punct)?.value?.asChar() == '=' &&
                    attrTokens[2] is TokenTree.Literal
                ) {
                    val strVal = (attrTokens[2] as TokenTree.Literal).value.strValue().value
                    if (!strVal.isNullOrBlank()) return strVal.replace('\\', '/').trimStart('/')
                }
            }
            return null
        }

        fun scanTreesForExternalModDecls(trees: List<TokenTree>, relDir: String, childDir: String, inheritedDisabled: Boolean) {
            val pendingAttrs = mutableListOf<List<TokenTree>>()
            var idx = 0
            while (idx < trees.size) {
                val nextAttrIdx = tryConsumeScannerAttr(trees, idx, pendingAttrs)
                if (nextAttrIdx != null) {
                    idx = nextAttrIdx
                    continue
                }
                val cur = trees[idx]
                val selfDisabled = inheritedDisabled || hasSkippedScannerAttrs(pendingAttrs, skipCfgTest = skipCfgTest)
                var kwIdx = idx
                if ((cur as? TokenTree.Ident)?.value?.toString() == "pub") {
                    kwIdx++
                    if ((trees.getOrNull(kwIdx) as? TokenTree.Group)?.value?.delimiter() == Delimiter.Parenthesis) {
                        kwIdx++
                    }
                }
                if ((trees.getOrNull(kwIdx) as? TokenTree.Ident)?.value?.toString() == "mod" &&
                    trees.getOrNull(kwIdx + 1) is TokenTree.Ident
                ) {
                    val rawMod = (trees[kwIdx + 1] as TokenTree.Ident).value.toString().removePrefix("r#")
                    val afterIdent = trees.getOrNull(kwIdx + 2)
                    if ((afterIdent as? TokenTree.Punct)?.value?.asChar() == ';') {
                        val pathOverride = extractPathAttrOverride(pendingAttrs)
                        val targetPrefix =
                            if (pathOverride != null) {
                                val stripped = pathOverride.removeSuffix(".rs").removeSuffix(".RS").removeSuffix("/mod")
                                if (relDir.isEmpty()) stripped else "$relDir/$stripped"
                            } else if (childDir.isEmpty()) {
                                rawMod
                            } else {
                                "$childDir/$rawMod"
                            }
                        totalModDecls[targetPrefix] = (totalModDecls[targetPrefix] ?: 0) + 1
                        if (!selfDisabled) {
                            enabledModDecls[targetPrefix] = (enabledModDecls[targetPrefix] ?: 0) + 1
                        }
                        idx = kwIdx + 3
                        pendingAttrs.clear()
                        continue
                    }
                }
                val curIdent = (cur as? TokenTree.Ident)?.value?.toString()
                val nextGrp = (trees.getOrNull(idx + 2) as? TokenTree.Group)?.value
                if ((curIdent == "cfg_if" || curIdent == "cfg_select") &&
                    (trees.getOrNull(idx + 1) as? TokenTree.Punct)?.value?.asChar() == '!' &&
                    (nextGrp?.delimiter() == Delimiter.Brace || nextGrp?.delimiter() == Delimiter.Parenthesis)
                ) {
                    val innerTrees = nextGrp.stream().toList()
                    val branchBodies =
                        if (curIdent == "cfg_select") {
                            partitionCfgSelectBranchBodies(innerTrees)
                        } else {
                            partitionCfgIfBranchBodies(innerTrees)
                        }
                    if (branchBodies != null) {
                        for ((bodyTokens, isActive) in branchBodies) {
                            scanTreesForExternalModDecls(
                                trees = bodyTokens,
                                relDir = relDir,
                                childDir = childDir,
                                inheritedDisabled = selfDisabled || !isActive,
                            )
                        }
                        idx += 3
                        pendingAttrs.clear()
                        continue
                    }
                }
                if ((cur is TokenTree.Punct && cur.value.asChar() == ';') ||
                    (cur is TokenTree.Group && cur.value.delimiter() == Delimiter.Brace)
                ) {
                    pendingAttrs.clear()
                }
                idx++
            }
        }

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
                scanTreesForExternalModDecls(tokens, relDir, childDir, inheritedDisabled = false)
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

internal fun selectActiveCfgIfBodies(innerTrees: List<TokenTree>): List<List<TokenTree>>? =
    partitionCfgIfBranchBodies(innerTrees)?.filter { it.second }?.map { it.first }

private fun partitionCfgIfBranchBodies(innerTrees: List<TokenTree>): List<Pair<List<TokenTree>, Boolean>>? {
    if ((innerTrees.firstOrNull() as? TokenTree.Ident)?.value?.toString() != "if") return null
    val branches = mutableListOf<Pair<List<TokenTree>, Boolean>>()
    var matchedActiveBranch = false
    var idx = 0
    while (idx < innerTrees.size) {
        val kw = (innerTrees.getOrNull(idx) as? TokenTree.Ident)?.value?.toString()
        if (kw == "if") {
            val hash = (innerTrees.getOrNull(idx + 1) as? TokenTree.Punct)?.value?.asChar()
            val attrGrp = innerTrees.getOrNull(idx + 2) as? TokenTree.Group
            val bodyGrp = innerTrees.getOrNull(idx + 3) as? TokenTree.Group
            if (hash != '#' ||
                attrGrp?.value?.delimiter() != Delimiter.Bracket ||
                bodyGrp?.value?.delimiter() != Delimiter.Brace
            ) {
                return null
            }
            val attrTokens = attrGrp.value.stream().toList()
            if (attrTokens.size != 2 ||
                (attrTokens[0] as? TokenTree.Ident)?.value?.toString() != "cfg" ||
                (attrTokens[1] as? TokenTree.Group)?.value?.delimiter() != Delimiter.Parenthesis
            ) {
                return null
            }
            val predTokens = (attrTokens[1] as TokenTree.Group).value.stream().toList()
            val bodyTokens = bodyGrp.value.stream().toList()
            val isActive =
                when {
                    matchedActiveBranch -> false
                    isDocOnlyCfgPredicateTokens(predTokens) -> false
                    else -> {
                        when (evalCfgTriState(predTokens, forRuntimeCfgMacro = false)) {
                            true -> {
                                matchedActiveBranch = true
                                true
                            }
                            false -> false
                            null -> true
                        }
                    }
                }
            branches += bodyTokens to isActive
            idx += 4
            if ((innerTrees.getOrNull(idx) as? TokenTree.Ident)?.value?.toString() == "else") {
                idx++
                if ((innerTrees.getOrNull(idx) as? TokenTree.Ident)?.value?.toString() == "if") {
                    continue
                }
                val elseBody = innerTrees.getOrNull(idx) as? TokenTree.Group
                if (elseBody?.value?.delimiter() == Delimiter.Brace) {
                    branches += elseBody.value.stream().toList() to !matchedActiveBranch
                    idx++
                }
            }
        } else {
            break
        }
    }
    return branches
}

private fun extractReprPackAlign(attrGroups: List<List<TokenTree>>): Int? {
    var pack: Int? = null
    for (attrTokens in attrGroups) {
        if (attrTokens.size == 2 &&
            (attrTokens[0] as? TokenTree.Ident)?.value?.toString() == "repr" &&
            (attrTokens[1] as? TokenTree.Group)?.value?.delimiter() == Delimiter.Parenthesis
        ) {
            val inner = (attrTokens[1] as TokenTree.Group).value.stream().toList()
            for ((idx, tt) in inner.withIndex()) {
                if ((tt as? TokenTree.Ident)?.value?.toString() == "packed") {
                    val nextGrp = inner.getOrNull(idx + 1) as? TokenTree.Group
                    if (nextGrp != null && nextGrp.value.delimiter() == Delimiter.Parenthesis) {
                        val argStream = nextGrp.value.stream()
                        val argText = argStream.toList().firstOrNull()?.toString()
                        val parsed = argText?.toIntOrNull()
                        if (parsed != null && parsed > 0) {
                            pack = if (pack == null) parsed else minOf(pack, parsed)
                        }
                    } else {
                        pack = 1
                    }
                }
            }
        }
    }
    return pack
}

private fun extractEnumReprKindFromAttrs(
    attrGroups: List<List<TokenTree>>,
    rawAbiMode: Boolean,
    defaultKind: String = "U32",
): String {
    val ptrWidth = TranslationSession.cfgTargetProfile.pointerWidth
    for (attrTokens in attrGroups) {
        if (attrTokens.size == 2 &&
            (attrTokens[0] as? TokenTree.Ident)?.value?.toString() == "repr" &&
            (attrTokens[1] as? TokenTree.Group)?.value?.delimiter() == Delimiter.Parenthesis
        ) {
            for (tt in (attrTokens[1] as TokenTree.Group).value.stream().toList()) {
                val ident = (tt as? TokenTree.Ident)?.value?.toString() ?: continue
                when (ident) {
                    "isize" -> return if (rawAbiMode && ptrWidth == 64) "I64" else "I32"
                    "usize" -> return if (rawAbiMode) (if (ptrWidth == 64) "U64" else "U32") else "I32"
                    else -> {
                        val kind = SCANNER_RUST_TO_SCALAR_KIND[ident]
                        if (kind != null && kind !in setOf("I128", "U128", "F32", "F64", "BOOL")) {
                            return kind
                        }
                    }
                }
            }
        }
    }
    return defaultKind
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

internal fun scanModuleTokenTrees(
    trees: List<TokenTree>,
    subpkg: String,
    rawAbiMode: Boolean,
    finalStructs: Boolean,
    splitModuleRoots: Boolean = false,
    registerSymbol: (String, String) -> Unit,
    registerFnSig: (String, String, List<List<TokenTree>>?, String?, Boolean) -> Unit,
    subpkgToRawLeaf: MutableMap<String, String>,
    putScopedDefault: (String, String, String) -> Unit,
    putScopedNumericKind: (String, String, String) -> Unit,
    addScopedOptionFnType: (String, String) -> Unit,
    putScopedPointerAlias: (String, String, Pair<Boolean, String>) -> Unit,
    aliasLeafTargets: MutableMap<String, String>,
    qualifiedAliasLeafTargets: MutableMap<String, Pair<String, String>>,
    fnTypeSpecs: MutableMap<String, RawFnTypeTokenSpec>,
    structFieldTypes: MutableMap<String, String>,
    recordDecls: MutableMap<String, RawRecordTokenDecl>,
    recordDeclsBySimple: MutableMap<String, RawRecordTokenDecl>,
    simpleEnumReprs: MutableMap<String, String>,
    ffiReferencedIdents: MutableSet<String> = linkedSetOf(),
    constLeafTargets: MutableList<Triple<String, String, String>> = mutableListOf(),
    insideCEnumMacro: Boolean = false,
    insideExternBlock: Boolean = false,
    traitDefaultlessAssocTypes: MutableMap<String, List<String>> = linkedMapOf(),
    traitResultMethods: MutableMap<String, List<String>> = linkedMapOf(),
    registerFnConstParams: (String, String, Int, List<String>) -> Unit = { _, _, _, _ -> },
    enumVariantTypeArity: MutableMap<String, Int> = linkedMapOf(),
) {
    val pendingAttrs = mutableListOf<List<TokenTree>>()
    var i = 0
    while (i < trees.size) {
        val nextAttrIdx = tryConsumeScannerAttr(trees, i, pendingAttrs)
        if (nextAttrIdx != null) {
            i = nextAttrIdx
            continue
        }
        val cur = trees[i]

        val skippedByCfg = hasSkippedScannerAttrs(pendingAttrs)

        // `macro_rules! <ident> { ... }`
        if ((cur as? TokenTree.Ident)?.value?.toString() == "macro_rules" &&
            (trees.getOrNull(i + 1) as? TokenTree.Punct)?.value?.asChar() == '!' &&
            trees.getOrNull(i + 2) is TokenTree.Ident &&
            trees.getOrNull(i + 3) is TokenTree.Group
        ) {
            if (!skippedByCfg) {
                val rawMacroName = (trees[i + 2] as TokenTree.Ident).value.toString().removePrefix("r#")
                registerSymbol(rustFunctionIdentToKotlin(rawMacroName), subpkg)
            }
            i += 4
            if ((trees.getOrNull(i) as? TokenTree.Punct)?.value?.asChar() == ';') i++
            pendingAttrs.clear()
            continue
        }

        // `[unsafe] extern ["ABI"] { ... }`
        val externBraceIdx = matchExternBlockBraceIndex(trees, i)
        if (externBraceIdx != null) {
            if (!skippedByCfg) {
                val braceGroup = (trees[externBraceIdx] as TokenTree.Group).value
                val externInnerTrees = braceGroup.stream().toList()
                collectFfiTokenTypeIdents(externInnerTrees, rawAbiMode, ffiReferencedIdents)
                scanModuleTokenTrees(
                    trees = externInnerTrees,
                    subpkg = subpkg,
                    rawAbiMode = rawAbiMode,
                    finalStructs = finalStructs,
                    registerSymbol = registerSymbol,
                    registerFnSig = registerFnSig,
                    subpkgToRawLeaf = subpkgToRawLeaf,
                    putScopedDefault = putScopedDefault,
                    putScopedNumericKind = putScopedNumericKind,
                    addScopedOptionFnType = addScopedOptionFnType,
                    putScopedPointerAlias = putScopedPointerAlias,
                    aliasLeafTargets = aliasLeafTargets,
                    qualifiedAliasLeafTargets = qualifiedAliasLeafTargets,
                    fnTypeSpecs = fnTypeSpecs,
                    structFieldTypes = structFieldTypes,
                    recordDecls = recordDecls,
                    recordDeclsBySimple = recordDeclsBySimple,
                    simpleEnumReprs = simpleEnumReprs,
                    ffiReferencedIdents = ffiReferencedIdents,
                    constLeafTargets = constLeafTargets,
                    insideExternBlock = true,
                    registerFnConstParams = registerFnConstParams,
                    enumVariantTypeArity = enumVariantTypeArity,
                )
            }
            i = externBraceIdx + 1
            pendingAttrs.clear()
            continue
        }

        // Macro invocation `<path> ! <Group> [;]`
        val macroGroupIdx = matchPathMacroGroupIndex(trees, i)
        if (macroGroupIdx != null) {
            if (!skippedByCfg) {
                val grp = (trees[macroGroupIdx] as TokenTree.Group).value
                val innerTrees = grp.stream().toList()
                val linkFnIdent = extractLinkMacroFnIdent(innerTrees)
                if (linkFnIdent != null) {
                    collectFfiTokenTypeIdents(innerTrees, rawAbiMode, ffiReferencedIdents)
                    val ktLinkFn = rustFunctionIdentToKotlin(linkFnIdent)
                    registerSymbol(ktLinkFn, subpkg)
                    val linkParams = extractLinkMacroFnParamTokenLists(innerTrees)
                    val linkRet = extractLinkMacroFnReturnType(innerTrees, rawAbiMode)
                    if (linkParams != null || linkRet != null) {
                        registerFnSig(subpkg, ktLinkFn, linkParams, linkRet, true)
                    }
                } else if (grp.delimiter() == Delimiter.Brace || grp.delimiter() == Delimiter.Parenthesis) {
                    val cfgIfBodies = selectActiveCfgIfBodies(innerTrees) ?: selectActiveCfgSelectBodies(innerTrees)
                    if (cfgIfBodies != null) {
                        for (branchBody in cfgIfBodies) {
                            scanModuleTokenTrees(
                                trees = branchBody,
                                subpkg = subpkg,
                                rawAbiMode = rawAbiMode,
                                finalStructs = finalStructs,
                                registerSymbol = registerSymbol,
                                registerFnSig = registerFnSig,
                                subpkgToRawLeaf = subpkgToRawLeaf,
                                putScopedDefault = putScopedDefault,
                                putScopedNumericKind = putScopedNumericKind,
                                addScopedOptionFnType = addScopedOptionFnType,
                                putScopedPointerAlias = putScopedPointerAlias,
                                aliasLeafTargets = aliasLeafTargets,
                                qualifiedAliasLeafTargets = qualifiedAliasLeafTargets,
                                fnTypeSpecs = fnTypeSpecs,
                                structFieldTypes = structFieldTypes,
                                recordDecls = recordDecls,
                                recordDeclsBySimple = recordDeclsBySimple,
                                simpleEnumReprs = simpleEnumReprs,
                                ffiReferencedIdents = ffiReferencedIdents,
                                constLeafTargets = constLeafTargets,
                                insideCEnumMacro = insideCEnumMacro,
                                insideExternBlock = insideExternBlock,
                                traitDefaultlessAssocTypes = traitDefaultlessAssocTypes,
                                traitResultMethods = traitResultMethods,
                                registerFnConstParams = registerFnConstParams,
                                enumVariantTypeArity = enumVariantTypeArity,
                            )
                        }
                    } else if (innerTrees.any { (it as? TokenTree.Ident)?.value?.toString() == "pub" }) {
                        val macroLeafIdent = (trees.getOrNull(macroGroupIdx - 2) as? TokenTree.Ident)?.value?.toString()
                        scanModuleTokenTrees(
                            trees = innerTrees,
                            subpkg = subpkg,
                            rawAbiMode = rawAbiMode,
                            finalStructs = finalStructs,
                            registerSymbol = registerSymbol,
                            registerFnSig = registerFnSig,
                            subpkgToRawLeaf = subpkgToRawLeaf,
                            putScopedDefault = putScopedDefault,
                            putScopedNumericKind = putScopedNumericKind,
                            addScopedOptionFnType = addScopedOptionFnType,
                            putScopedPointerAlias = putScopedPointerAlias,
                            aliasLeafTargets = aliasLeafTargets,
                            qualifiedAliasLeafTargets = qualifiedAliasLeafTargets,
                            fnTypeSpecs = fnTypeSpecs,
                            structFieldTypes = structFieldTypes,
                            recordDecls = recordDecls,
                            recordDeclsBySimple = recordDeclsBySimple,
                            simpleEnumReprs = simpleEnumReprs,
                            ffiReferencedIdents = ffiReferencedIdents,
                            constLeafTargets = constLeafTargets,
                            insideCEnumMacro = insideCEnumMacro || macroLeafIdent == "c_enum",
                            insideExternBlock = insideExternBlock,
                            registerFnConstParams = registerFnConstParams,
                            enumVariantTypeArity = enumVariantTypeArity,
                        )
                    }
                }
            }
            i = macroGroupIdx + 1
            if ((trees.getOrNull(i) as? TokenTree.Punct)?.value?.asChar() == ';') i++
            pendingAttrs.clear()
            continue
        }

        // `pub ...` declarations
        if ((cur as? TokenTree.Ident)?.value?.toString() == "pub") {
            var kwIdx = i + 1
            if ((trees.getOrNull(kwIdx) as? TokenTree.Group)?.value?.delimiter() == Delimiter.Parenthesis) {
                kwIdx++
            }
            var traitLookIdx = kwIdx
            while ((trees.getOrNull(traitLookIdx) as? TokenTree.Ident)?.value?.toString() in setOf("const", "unsafe", "auto")) {
                traitLookIdx++
            }
            if ((trees.getOrNull(traitLookIdx) as? TokenTree.Ident)?.value?.toString() == "trait") {
                kwIdx = traitLookIdx
            }
            val kw = (trees.getOrNull(kwIdx) as? TokenTree.Ident)?.value?.toString()
            when (kw) {
                "mod" -> {
                    val rawMod = (trees.getOrNull(kwIdx + 1) as? TokenTree.Ident)?.value?.toString()?.removePrefix("r#")
                    val inlineGrp = (trees.getOrNull(kwIdx + 2) as? TokenTree.Group)?.value
                    if (rawMod != null && !skippedByCfg) {
                        val modKt = rustModuleIdentToKotlin(rawMod.replace('-', '_'))
                        val childSub = if (subpkg.isEmpty()) modKt else "$subpkg.$modKt"
                        if (modKt.isNotEmpty() && rawMod !in setOf("tests", "test", "private", "sealed")) {
                            subpkgToRawLeaf.putIfMissing(childSub, rawMod)
                        }
                        if (inlineGrp != null &&
                            inlineGrp.delimiter() == Delimiter.Brace &&
                            rawMod !in setOf("tests", "test", "private", "sealed")
                        ) {
                            scanModuleTokenTrees(
                                trees = inlineGrp.stream().toList(),
                                subpkg = if (splitModuleRoots && modKt.isNotEmpty()) childSub else subpkg,
                                rawAbiMode = rawAbiMode,
                                finalStructs = finalStructs,
                                splitModuleRoots = splitModuleRoots,
                                registerSymbol = registerSymbol,
                                registerFnSig = registerFnSig,
                                subpkgToRawLeaf = subpkgToRawLeaf,
                                putScopedDefault = putScopedDefault,
                                putScopedNumericKind = putScopedNumericKind,
                                addScopedOptionFnType = addScopedOptionFnType,
                                putScopedPointerAlias = putScopedPointerAlias,
                                aliasLeafTargets = aliasLeafTargets,
                                qualifiedAliasLeafTargets = qualifiedAliasLeafTargets,
                                fnTypeSpecs = fnTypeSpecs,
                                structFieldTypes = structFieldTypes,
                                recordDecls = recordDecls,
                                recordDeclsBySimple = recordDeclsBySimple,
                                simpleEnumReprs = simpleEnumReprs,
                                ffiReferencedIdents = ffiReferencedIdents,
                                constLeafTargets = constLeafTargets,
                                insideCEnumMacro = insideCEnumMacro,
                                insideExternBlock = insideExternBlock,
                                traitDefaultlessAssocTypes = traitDefaultlessAssocTypes,
                                traitResultMethods = traitResultMethods,
                                registerFnConstParams = registerFnConstParams,
                                enumVariantTypeArity = enumVariantTypeArity,
                            )
                        }
                    }
                    i = skipItemAfterHeader(trees, kwIdx + 1)
                    pendingAttrs.clear()
                    continue
                }
                "struct", "union" -> {
                    val rawIdent = (trees.getOrNull(kwIdx + 1) as? TokenTree.Ident)?.value?.toString()
                    if (rawIdent != null && !skippedByCfg) {
                        val ktType = KotlinNamingPolicy.Default.toTypeIdentifier(rawIdent, allowRawAbi = rawAbiMode)
                        registerSymbol(ktType, subpkg)
                        val afterIdent = kwIdx + 2
                        val (genTypeParams, _, afterGenericsIdx) =
                            extractHeaderGenericTypeParams(trees, afterIdent, rawAbiMode)
                        val hasAnyGenerics = afterGenericsIdx > afterIdent
                        val bodyGroup = findStructOrUnionBodyGroup(trees, afterGenericsIdx)
                        if (!hasAnyGenerics) {
                            if (rawIdent.removePrefix("r#") != "c_void") {
                                putScopedDefault(subpkg, ktType, "$ktType()")
                            }
                            if (bodyGroup != null) {
                                val parsedFields =
                                    parseRecordFieldsFromGroup(
                                        bodyGroup,
                                        rawAbiMode,
                                        structFieldTypes,
                                        ktType,
                                        subpkg = subpkg,
                                        isUnion = (kw == "union"),
                                    )
                                if (parsedFields.isNotEmpty()) {
                                    val decl =
                                        RawRecordTokenDecl(
                                            subpkg = subpkg,
                                            ktName = ktType,
                                            isUnion = (kw == "union"),
                                            packAlign = extractReprPackAlign(pendingAttrs),
                                            fields = parsedFields,
                                        )
                                    val scopedKey = if (subpkg.isEmpty()) ktType else "$subpkg.$ktType"
                                    recordDecls.putIfMissing(scopedKey, decl)
                                    recordDeclsBySimple.putIfMissing(ktType, decl)
                                }
                            }
                        } else if (bodyGroup != null) {
                            parseRecordFieldsFromGroup(
                                bodyGroup = bodyGroup,
                                rawAbiMode = rawAbiMode,
                                structFieldTypesOut = structFieldTypes,
                                ownerKtType = ktType,
                                subpkg = subpkg,
                                isUnion = (kw == "union"),
                                excludedTypeParamNames = genTypeParams,
                            )
                        }
                    }
                    i = skipItemAfterHeader(trees, kwIdx + 1)
                    pendingAttrs.clear()
                    continue
                }
                "enum" -> {
                    val rawIdent = (trees.getOrNull(kwIdx + 1) as? TokenTree.Ident)?.value?.toString()
                    if (rawIdent != null && !skippedByCfg) {
                        val ktType = KotlinNamingPolicy.Default.toTypeIdentifier(rawIdent, allowRawAbi = rawAbiMode)
                        registerSymbol(ktType, subpkg)
                        val afterIdent = kwIdx + 2
                        val hasGenerics = (trees.getOrNull(afterIdent) as? TokenTree.Punct)?.value?.asChar() == '<'
                        val braceGrp = (trees.getOrNull(afterIdent) as? TokenTree.Group)?.value
                        scanEnumVariantTypeArities(
                            trees = trees,
                            afterIdentIdx = afterIdent,
                            subpkg = subpkg,
                            ktEnum = ktType,
                            rawAbiMode = rawAbiMode,
                            out = enumVariantTypeArity,
                            traitDefaultlessAssocTypes = traitDefaultlessAssocTypes,
                        )
                        if (insideCEnumMacro) {
                            val reprKind = extractEnumReprKindFromAttrs(pendingAttrs, rawAbiMode, defaultKind = "I32")
                            putScopedNumericKind(subpkg, ktType, reprKind)
                            putScopedDefault(subpkg, ktType, if (reprKind.startsWith("U")) "0u" else "0")
                            if (braceGrp != null && braceGrp.delimiter() == Delimiter.Brace) {
                                val bodyTokens = braceGrp.stream().toList()
                                for (bIdx in 0 until bodyTokens.size - 1) {
                                    if ((bodyTokens[bIdx] as? TokenTree.Ident)?.value?.toString() == "pub") {
                                        val vIdent = (bodyTokens[bIdx + 1] as? TokenTree.Ident)?.value?.toString()
                                        if (vIdent != null) {
                                            val ktVar = KotlinNamingPolicy.Default.toConstIdentifier(vIdent)
                                            registerSymbol(ktVar, subpkg)
                                            putScopedNumericKind(subpkg, ktVar, reprKind)
                                        }
                                    }
                                }
                            }
                        } else if (!hasGenerics &&
                            braceGrp != null &&
                            braceGrp.delimiter() == Delimiter.Brace &&
                            (rawAbiMode || finalStructs) &&
                            isFieldlessNonEmptyEnumBody(braceGrp.stream().toList())
                        ) {
                            val reprKind = extractEnumReprKindFromAttrs(pendingAttrs, rawAbiMode)
                            val scopedKey = if (subpkg.isEmpty()) ktType else "$subpkg.$ktType"
                            simpleEnumReprs.putIfMissing(scopedKey, reprKind)
                            putScopedDefault(subpkg, ktType, "$ktType.entries.first()")
                        }
                    }
                    i = skipItemAfterHeader(trees, kwIdx + 1)
                    pendingAttrs.clear()
                    continue
                }
                "trait" -> {
                    val rawIdent = (trees.getOrNull(kwIdx + 1) as? TokenTree.Ident)?.value?.toString()
                    if (rawIdent != null && !skippedByCfg) {
                        val ktTrait = KotlinNamingPolicy.Default.toTypeIdentifier(rawIdent, allowRawAbi = rawAbiMode)
                        registerSymbol(ktTrait, subpkg)
                        scanTraitTokenMetadata(
                            trees = trees,
                            startAfterIdent = kwIdx + 2,
                            subpkg = subpkg,
                            ktTrait = ktTrait,
                            traitDefaultlessAssocTypes = traitDefaultlessAssocTypes,
                            traitResultMethods = traitResultMethods,
                        )
                    }
                    i = skipItemAfterHeader(trees, kwIdx + 1)
                    pendingAttrs.clear()
                    continue
                }
                "type" -> {
                    val nextIdx =
                        scanPubTypeDeclaration(
                            trees = trees,
                            typeKwIdx = kwIdx,
                            subpkg = subpkg,
                            skippedByCfg = skippedByCfg,
                            rawAbiMode = rawAbiMode,
                            registerSymbol = registerSymbol,
                            putScopedDefault = putScopedDefault,
                            putScopedNumericKind = putScopedNumericKind,
                            addScopedOptionFnType = addScopedOptionFnType,
                            putScopedPointerAlias = putScopedPointerAlias,
                            aliasLeafTargets = aliasLeafTargets,
                            qualifiedAliasLeafTargets = qualifiedAliasLeafTargets,
                            fnTypeSpecs = fnTypeSpecs,
                        )
                    i = nextIdx
                    pendingAttrs.clear()
                    continue
                }
                "const" -> {
                    val afterConst = trees.getOrNull(kwIdx + 1)
                    val afterConstText = (afterConst as? TokenTree.Ident)?.value?.toString()
                    val isConstFn = afterConstText == "fn" || afterConstText == "unsafe" || afterConstText == "extern"
                    val endFnIdx = skipItemAfterHeader(trees, kwIdx + 1, semicolonOnly = !isConstFn)
                    if (isConstFn) {
                        val fnIdent = findFnIdentAfterQualifiers(trees, kwIdx + 1)
                        if (fnIdent != null && !skippedByCfg) {
                            val ktFn = rustFunctionIdentToKotlin(fnIdent)
                            registerSymbol(ktFn, subpkg)
                            val pLists = extractNonMethodFnParamTokenLists(trees, kwIdx + 1)
                            val retTy = extractFnReturnTypeFromHeader(trees, kwIdx + 1, endFnIdx, rawAbiMode, insideExternBlock = insideExternBlock)
                            if (pLists != null || retTy != null) {
                                registerFnSig(subpkg, ktFn, pLists, retTy, insideExternBlock)
                            }
                            val constParams = extractFnConstParamNamesFromHeader(trees, kwIdx + 1)
                            if (pLists != null && constParams.isNotEmpty()) {
                                registerFnConstParams(subpkg, ktFn, pLists.size, constParams)
                            }
                        }
                    } else if (afterConstText != null && afterConstText != "_" && afterConstText !in SCANNER_ITEM_KEYWORDS && !skippedByCfg) {
                        val ktConst = KotlinNamingPolicy.Default.toConstIdentifier(afterConstText)
                        registerSymbol(ktConst, subpkg)
                        extractConstOrStaticTypeLeaf(trees, kwIdx + 1, endFnIdx)?.let { leaf ->
                            constLeafTargets += Triple(subpkg, ktConst, leaf)
                        }
                    }
                    i = endFnIdx
                    pendingAttrs.clear()
                    continue
                }
                "static" -> {
                    var idIdx = kwIdx + 1
                    if ((trees.getOrNull(idIdx) as? TokenTree.Ident)?.value?.toString() == "mut") idIdx++
                    val rawIdent = (trees.getOrNull(idIdx) as? TokenTree.Ident)?.value?.toString()
                    val endStaticIdx = skipItemAfterHeader(trees, idIdx, semicolonOnly = true)
                    if (rawIdent != null && rawIdent != "_" && !skippedByCfg) {
                        val ktConst = KotlinNamingPolicy.Default.toConstIdentifier(rawIdent)
                        registerSymbol(ktConst, subpkg)
                        extractConstOrStaticTypeLeaf(trees, idIdx, endStaticIdx)?.let { leaf ->
                            constLeafTargets += Triple(subpkg, ktConst, leaf)
                        }
                    }
                    i = endStaticIdx
                    pendingAttrs.clear()
                    continue
                }
                "fn", "unsafe", "async", "extern" -> {
                    val fnIdent = findFnIdentAfterQualifiers(trees, kwIdx)
                    val endFnIdx = skipItemAfterHeader(trees, kwIdx)
                    if (fnIdent != null && !skippedByCfg) {
                        val ktFn = rustFunctionIdentToKotlin(fnIdent)
                        registerSymbol(ktFn, subpkg)
                        val pLists = extractNonMethodFnParamTokenLists(trees, kwIdx)
                        val retTy = extractFnReturnTypeFromHeader(trees, kwIdx, endFnIdx, rawAbiMode, insideExternBlock = insideExternBlock)
                        if (pLists != null || retTy != null) {
                            registerFnSig(subpkg, ktFn, pLists, retTy, insideExternBlock)
                        }
                        val constParams = extractFnConstParamNamesFromHeader(trees, kwIdx)
                        if (pLists != null && constParams.isNotEmpty()) {
                            registerFnConstParams(subpkg, ktFn, pLists.size, constParams)
                        }
                    }
                    i = endFnIdx
                    pendingAttrs.clear()
                    continue
                }
            }
        }

        val nonPubKw = (cur as? TokenTree.Ident)?.value?.toString()
        if (nonPubKw == "mod" && trees.getOrNull(i + 1) is TokenTree.Ident) {
            val rawMod = (trees[i + 1] as TokenTree.Ident).value.toString().removePrefix("r#")
            val inlineGrp = (trees.getOrNull(i + 2) as? TokenTree.Group)?.value
            if (!skippedByCfg &&
                inlineGrp != null &&
                inlineGrp.delimiter() == Delimiter.Brace &&
                rawMod !in setOf("tests", "test", "private", "sealed")
            ) {
                val modKt = rustModuleIdentToKotlin(rawMod.replace('-', '_'))
                val childSub = if (subpkg.isEmpty()) modKt else "$subpkg.$modKt"
                if (splitModuleRoots && modKt.isNotEmpty()) {
                    subpkgToRawLeaf.putIfMissing(childSub, rawMod)
                }
                scanModuleTokenTrees(
                    trees = inlineGrp.stream().toList(),
                    subpkg = if (splitModuleRoots && modKt.isNotEmpty()) childSub else subpkg,
                    rawAbiMode = rawAbiMode,
                    finalStructs = finalStructs,
                    splitModuleRoots = splitModuleRoots,
                    registerSymbol = registerSymbol,
                    registerFnSig = registerFnSig,
                    subpkgToRawLeaf = subpkgToRawLeaf,
                    putScopedDefault = putScopedDefault,
                    putScopedNumericKind = putScopedNumericKind,
                    addScopedOptionFnType = addScopedOptionFnType,
                    putScopedPointerAlias = putScopedPointerAlias,
                    aliasLeafTargets = aliasLeafTargets,
                    qualifiedAliasLeafTargets = qualifiedAliasLeafTargets,
                    fnTypeSpecs = fnTypeSpecs,
                    structFieldTypes = structFieldTypes,
                    recordDecls = recordDecls,
                    recordDeclsBySimple = recordDeclsBySimple,
                    simpleEnumReprs = simpleEnumReprs,
                    ffiReferencedIdents = ffiReferencedIdents,
                    constLeafTargets = constLeafTargets,
                    insideCEnumMacro = insideCEnumMacro,
                    insideExternBlock = insideExternBlock,
                    traitDefaultlessAssocTypes = traitDefaultlessAssocTypes,
                    traitResultMethods = traitResultMethods,
                    registerFnConstParams = registerFnConstParams,
                    enumVariantTypeArity = enumVariantTypeArity,
                )
            }
            i = skipItemAfterHeader(trees, i + 1)
            pendingAttrs.clear()
            continue
        }
        val nonPubTraitQualIdx =
            generateSequence(i) { idx ->
                val tok = (trees.getOrNull(idx) as? TokenTree.Ident)?.value?.toString()
                if (tok in setOf("const", "unsafe", "auto")) idx + 1 else null
            }.last()
        if ((trees.getOrNull(nonPubTraitQualIdx) as? TokenTree.Ident)?.value?.toString() == "trait") {
            val rawIdent = (trees.getOrNull(nonPubTraitQualIdx + 1) as? TokenTree.Ident)?.value?.toString()
            if (rawIdent != null && !skippedByCfg) {
                val ktTrait = KotlinNamingPolicy.Default.toTypeIdentifier(rawIdent, allowRawAbi = rawAbiMode)
                registerSymbol(ktTrait, subpkg)
                scanTraitTokenMetadata(
                    trees = trees,
                    startAfterIdent = nonPubTraitQualIdx + 2,
                    subpkg = subpkg,
                    ktTrait = ktTrait,
                    traitDefaultlessAssocTypes = traitDefaultlessAssocTypes,
                    traitResultMethods = traitResultMethods,
                )
            }
            i = skipItemAfterHeader(trees, nonPubTraitQualIdx + 1)
            pendingAttrs.clear()
            continue
        }
        val nonPubImplQualIdx =
            generateSequence(i) { idx ->
                val tok = (trees.getOrNull(idx) as? TokenTree.Ident)?.value?.toString()
                if (tok in setOf("const", "unsafe", "default")) idx + 1 else null
            }.last()
        if ((trees.getOrNull(nonPubImplQualIdx) as? TokenTree.Ident)?.value?.toString() == "impl") {
            val endImplIdx = skipItemAfterHeader(trees, nonPubImplQualIdx + 1)
            if (!skippedByCfg) {
                scanInherentImplAssociatedFunctions(
                    trees = trees,
                    implKwIdx = nonPubImplQualIdx,
                    endImplIdx = endImplIdx,
                    subpkg = subpkg,
                    rawAbiMode = rawAbiMode,
                    registerFnSig = registerFnSig,
                    registerFnConstParams = registerFnConstParams,
                )
            }
            i = endImplIdx
            pendingAttrs.clear()
            continue
        }
        if (nonPubKw == "struct" || nonPubKw == "union" || nonPubKw == "enum" || nonPubKw == "trait" || nonPubKw == "type") {
            val rawIdent = (trees.getOrNull(i + 1) as? TokenTree.Ident)?.value?.toString()
            if (rawIdent != null && !skippedByCfg) {
                val ktType = KotlinNamingPolicy.Default.toTypeIdentifier(rawIdent, allowRawAbi = rawAbiMode)
                registerSymbol(ktType, subpkg)
                val afterIdent = i + 2
                if (nonPubKw == "enum") {
                    scanEnumVariantTypeArities(
                        trees = trees,
                        afterIdentIdx = afterIdent,
                        subpkg = subpkg,
                        ktEnum = ktType,
                        rawAbiMode = rawAbiMode,
                        out = enumVariantTypeArity,
                        traitDefaultlessAssocTypes = traitDefaultlessAssocTypes,
                    )
                } else if (nonPubKw == "struct" || nonPubKw == "union") {
                    val (genTypeParams, _, afterGenericsIdx) =
                        extractHeaderGenericTypeParams(trees, afterIdent, rawAbiMode)
                    val bodyGroup = findStructOrUnionBodyGroup(trees, afterGenericsIdx)
                    if (bodyGroup != null) {
                        parseRecordFieldsFromGroup(
                            bodyGroup = bodyGroup,
                            rawAbiMode = rawAbiMode,
                            structFieldTypesOut = structFieldTypes,
                            ownerKtType = ktType,
                            subpkg = subpkg,
                            isUnion = (nonPubKw == "union"),
                            excludedTypeParamNames = genTypeParams,
                        )
                    }
                }
            }
            i = skipItemAfterHeader(trees, i + 1, semicolonOnly = (nonPubKw == "type"))
            pendingAttrs.clear()
            continue
        }
        if (nonPubKw == "fn" || nonPubKw == "const" || nonPubKw == "unsafe" || nonPubKw == "async" || nonPubKw == "extern") {
            val fnIdent = findFnIdentAfterQualifiers(trees, i)
            if (fnIdent != null) {
                val endFnIdx = skipItemAfterHeader(trees, i)
                if (!skippedByCfg) {
                    val ktFn = rustFunctionIdentToKotlin(fnIdent)
                    registerSymbol(ktFn, subpkg)
                    val pLists = extractNonMethodFnParamTokenLists(trees, i)
                    val retTy = extractFnReturnTypeFromHeader(trees, i, endFnIdx, rawAbiMode, insideExternBlock = insideExternBlock)
                    if (pLists != null || retTy != null) {
                        registerFnSig(subpkg, ktFn, pLists, retTy, insideExternBlock)
                    }
                    val constParams = extractFnConstParamNamesFromHeader(trees, i)
                    if (pLists != null && constParams.isNotEmpty()) {
                        registerFnConstParams(subpkg, ktFn, pLists.size, constParams)
                    }
                }
                i = endFnIdx
                pendingAttrs.clear()
                continue
            }
        }
        if (nonPubKw == "const" || nonPubKw == "static") {
            var idIdx = i + 1
            if (nonPubKw == "static" && (trees.getOrNull(idIdx) as? TokenTree.Ident)?.value?.toString() == "mut") idIdx++
            val rawIdent = (trees.getOrNull(idIdx) as? TokenTree.Ident)?.value?.toString()
            val endConstIdx = skipItemAfterHeader(trees, idIdx, semicolonOnly = true)
            if (rawIdent != null && rawIdent != "_" && rawIdent !in SCANNER_ITEM_KEYWORDS && !skippedByCfg) {
                val ktConst = KotlinNamingPolicy.Default.toConstIdentifier(rawIdent)
                registerSymbol(ktConst, subpkg)
                extractConstOrStaticTypeLeaf(trees, idIdx, endConstIdx)?.let { leaf ->
                    constLeafTargets += Triple(subpkg, ktConst, leaf)
                }
            }
            i = endConstIdx
            pendingAttrs.clear()
            continue
        }

        if ((cur is TokenTree.Punct && cur.value.asChar() == ';') ||
            (cur is TokenTree.Group && cur.value.delimiter() == Delimiter.Brace)
        ) {
            pendingAttrs.clear()
        }
        i++
    }
}

private fun extractConstOrStaticTypeLeaf(
    trees: List<TokenTree>,
    identIdx: Int,
    endIdx: Int,
): String? {
    val colon = trees.getOrNull(identIdx + 1) as? TokenTree.Punct ?: return null
    if (colon.value.asChar() != ':' || colon.value.spacing() == Spacing.Joint) return null
    val typeTokens = mutableListOf<TokenTree>()
    var k = identIdx + 2
    val limit = minOf(endIdx, trees.size)
    while (k < limit) {
        val tt = trees[k]
        val ch = (tt as? TokenTree.Punct)?.value?.asChar()
        if (ch == '=' || ch == ';') break
        typeTokens += tt
        k++
    }
    return extractSimplePathLeafIdent(typeTokens)
}

private fun matchExternBlockBraceIndex(trees: List<TokenTree>, startIdx: Int): Int? {
    var j = startIdx
    if ((trees.getOrNull(j) as? TokenTree.Ident)?.value?.toString() == "unsafe") j++
    if ((trees.getOrNull(j) as? TokenTree.Ident)?.value?.toString() != "extern") return null
    j++
    if (trees.getOrNull(j) is TokenTree.Literal) j++
    val grp = trees.getOrNull(j) as? TokenTree.Group ?: return null
    return if (grp.value.delimiter() == Delimiter.Brace) j else null
}

private fun matchPathMacroGroupIndex(trees: List<TokenTree>, startIdx: Int): Int? {
    var j = startIdx
    if ((trees.getOrNull(j) as? TokenTree.Punct)?.value?.asChar() == ':' &&
        (trees.getOrNull(j + 1) as? TokenTree.Punct)?.value?.asChar() == ':'
    ) {
        j += 2
    }
    val firstIdent = (trees.getOrNull(j) as? TokenTree.Ident)?.value?.toString() ?: return null
    if (firstIdent in SCANNER_ITEM_KEYWORDS) return null
    j++
    while (j + 2 < trees.size &&
        (trees[j] as? TokenTree.Punct)?.value?.asChar() == ':' &&
        (trees[j + 1] as? TokenTree.Punct)?.value?.asChar() == ':' &&
        trees[j + 2] is TokenTree.Ident
    ) {
        j += 3
    }
    if ((trees.getOrNull(j) as? TokenTree.Punct)?.value?.asChar() != '!') return null
    if (trees.getOrNull(j + 1) !is TokenTree.Group) return null
    return j + 1
}

private val SCANNER_ITEM_KEYWORDS =
    setOf("pub", "mod", "struct", "union", "enum", "trait", "type", "const", "static", "fn", "unsafe", "async", "auto", "extern", "use", "impl")

private fun extractLinkMacroFnIdent(innerTrees: List<TokenTree>): String? {
    val fnIdx = findLinkMacroFnIndex(innerTrees) ?: return null
    val fnIdent = (innerTrees[fnIdx + 1] as? TokenTree.Ident)?.value?.toString()?.removePrefix("r#")
    val paramsGrp = (innerTrees[fnIdx + 2] as? TokenTree.Group)?.value
    return if (fnIdent != null && paramsGrp?.delimiter() == Delimiter.Parenthesis) fnIdent else null
}

private fun extractLinkMacroFnParamTokenLists(innerTrees: List<TokenTree>): List<List<TokenTree>>? {
    val fnIdx = findLinkMacroFnIndex(innerTrees) ?: return null
    return extractNonMethodFnParamTokenLists(innerTrees, fnIdx)
}

private fun extractLinkMacroFnReturnType(innerTrees: List<TokenTree>, rawAbiMode: Boolean): String? {
    val fnIdx = findLinkMacroFnIndex(innerTrees) ?: return null
    return extractFnReturnTypeFromHeader(innerTrees, fnIdx, innerTrees.size, rawAbiMode, insideExternBlock = true)
}

private fun extractFnReturnTypeFromHeader(
    trees: List<TokenTree>,
    startIdx: Int,
    endIdx: Int,
    rawAbiMode: Boolean,
    selfOwnerKt: String? = null,
    insideExternBlock: Boolean = false,
): String? {
    val fnIdx = skipFnQualifiersAndFindFnKw(trees, startIdx, endIdx) ?: return null
    var afterIdent = fnIdx + 2
    if ((trees.getOrNull(afterIdent) as? TokenTree.Punct)?.value?.asChar() == '<') {
        afterIdent = skipBalancedAngleBrackets(trees, afterIdent) ?: return null
    }
    val paramsGrp = (trees.getOrNull(afterIdent) as? TokenTree.Group)?.value ?: return null
    if (paramsGrp.delimiter() != Delimiter.Parenthesis) return null
    var k = afterIdent + 1
    val limit = minOf(endIdx, trees.size)
    while (k < limit) {
        val tt = trees[k]
        if (tt is TokenTree.Punct) {
            val ch = tt.value.asChar()
            if (ch == ';') return "Unit"
            if (ch == '-' &&
                tt.value.spacing() == Spacing.Joint &&
                (trees.getOrNull(k + 1) as? TokenTree.Punct)?.value?.asChar() == '>'
            ) {
                val retTokens = mutableListOf<TokenTree>()
                var r = k + 2
                while (r < limit) {
                    val rtt = trees[r]
                    if ((rtt as? TokenTree.Punct)?.value?.asChar() == ';') break
                    if ((rtt as? TokenTree.Group)?.value?.delimiter() == Delimiter.Brace) break
                    if ((rtt as? TokenTree.Ident)?.value?.toString() == "where") break
                    retTokens += rtt
                    r++
                }
                return mapTokensFieldTypeQuick(
                    unwrapPaddingOrMaybeUninitTokens(retTokens),
                    rawAbiMode,
                    selfOwnerKt,
                    insideExternBlock = insideExternBlock,
                )
            }
        } else if (tt is TokenTree.Group && tt.value.delimiter() == Delimiter.Brace) {
            return "Unit"
        } else if ((tt as? TokenTree.Ident)?.value?.toString() == "where") {
            return "Unit"
        }
        k++
    }
    return "Unit"
}

private fun findFnIdentAfterQualifiers(trees: List<TokenTree>, startIdx: Int): String? {
    val fnIdx = skipFnQualifiersAndFindFnKw(trees, startIdx, allowBareLiteral = true) ?: return null
    return (trees.getOrNull(fnIdx + 1) as? TokenTree.Ident)?.value?.toString()?.removePrefix("r#")
}

private fun skipItemAfterHeader(
    trees: List<TokenTree>,
    startIdx: Int,
    semicolonOnly: Boolean = false,
): Int {
    var j = startIdx
    while (j < trees.size) {
        val tt = trees[j]
        if (tt is TokenTree.Punct && tt.value.asChar() == ';') {
            return j + 1
        }
        if (!semicolonOnly &&
            tt is TokenTree.Group &&
            tt.value.delimiter() == Delimiter.Brace &&
            (trees.getOrNull(j - 1) as? TokenTree.Punct)?.value?.asChar() != '<'
        ) {
            return j + 1
        }
        j++
    }
    return trees.size
}

internal fun isScannerRArrow(trees: List<TokenTree>, gtIdx: Int): Boolean {
    if (gtIdx <= 0) return false
    val prev = trees[gtIdx - 1] as? TokenTree.Punct ?: return false
    return prev.value.asChar() == '-' && prev.value.spacing() == Spacing.Joint
}

private data class HeaderGenericTypeParams(
    val paramNames: Set<String>,
    val typeArity: Int,
    val nextIdx: Int,
)

private fun extractHeaderGenericTypeParams(
    trees: List<TokenTree>,
    startIdx: Int,
    rawAbiMode: Boolean,
): HeaderGenericTypeParams {
    if ((trees.getOrNull(startIdx) as? TokenTree.Punct)?.value?.asChar() != '<') {
        return HeaderGenericTypeParams(emptySet(), 0, startIdx)
    }
    var angleDepth = 0
    var closeIdx = -1
    var k = startIdx
    while (k < trees.size) {
        val tt = trees[k]
        if (tt is TokenTree.Punct && !isScannerRArrow(trees, k)) {
            when (tt.value.asChar()) {
                '<' -> angleDepth++
                '>' -> {
                    angleDepth--
                    if (angleDepth == 0) {
                        closeIdx = k
                        break
                    }
                }
            }
        }
        k++
    }
    if (closeIdx < 0) return HeaderGenericTypeParams(emptySet(), 0, startIdx)
    val inner = trees.subList(startIdx + 1, closeIdx)
    val clauses = mutableListOf<List<TokenTree>>()
    val cur = mutableListOf<TokenTree>()
    var d = 0
    for (tt in inner) {
        if (tt is TokenTree.Punct) {
            when (tt.value.asChar()) {
                '<' -> {
                    d++
                    cur += tt
                }
                '>' -> {
                    if (d > 0) d--
                    cur += tt
                }
                ',' ->
                    if (d == 0) {
                        clauses += cur.toList()
                        cur.clear()
                    } else {
                        cur += tt
                    }
                else -> cur += tt
            }
        } else {
            cur += tt
        }
    }
    if (cur.isNotEmpty()) clauses += cur.toList()
    val typeParams = linkedSetOf<String>()
    var typeArity = 0
    for (clause in clauses) {
        val stripped = stripLeadingScannerAttrs(clause)
        val first = stripped.firstOrNull() ?: continue
        if (first is TokenTree.Punct && first.value.asChar() == '\'') continue
        if (first is TokenTree.Ident && first.value.toString() == "const") continue
        val paramIdent = (first as? TokenTree.Ident)?.value?.toString()?.removePrefix("r#") ?: continue
        typeArity++
        typeParams += paramIdent
        val ktParam = KotlinNamingPolicy.Default.toTypeIdentifier(paramIdent, allowRawAbi = rawAbiMode)
        if (ktParam.isNotEmpty()) typeParams += ktParam
    }
    return HeaderGenericTypeParams(typeParams, typeArity, closeIdx + 1)
}

private fun findEnumBodyBraceIndex(trees: List<TokenTree>, startIdx: Int): Int? {
    var j = startIdx
    while (j < trees.size) {
        val tt = trees[j]
        if (tt is TokenTree.Punct && tt.value.asChar() == ';') return null
        if (tt is TokenTree.Group && tt.value.delimiter() == Delimiter.Brace) {
            return j
        }
        j++
    }
    return null
}

private fun countScannerEnumLiftedAssocs(
    headerTrees: List<TokenTree>,
    explicitParamNames: Set<String>,
    traitDefaultlessAssocTypes: Map<String, List<String>>,
): Int {
    val lifted = linkedSetOf<String>()
    var inBound = false
    var boundAngleDepth = 0
    var k = 0
    while (k < headerTrees.size) {
        val tt = headerTrees[k]
        when {
            tt is TokenTree.Punct && !isScannerRArrow(headerTrees, k) && tt.value.asChar() == '<' ->
                if (inBound) boundAngleDepth++
            tt is TokenTree.Punct && !isScannerRArrow(headerTrees, k) && tt.value.asChar() == '>' ->
                if (inBound && boundAngleDepth > 0) boundAngleDepth--
            tt is TokenTree.Punct && boundAngleDepth == 0 && tt.value.asChar() == ':' -> inBound = true
            tt is TokenTree.Punct && boundAngleDepth == 0 && tt.value.asChar() == ',' -> inBound = false
            tt is TokenTree.Ident && inBound && boundAngleDepth == 0 -> {
                val raw = tt.value.toString().removePrefix("r#")
                val assocs =
                    when (raw) {
                        "ToOwned" -> listOf("Owned")
                        "Deref", "DerefMut" -> listOf("Target")
                        "Iterator", "DoubleEndedIterator", "ExactSizeIterator", "FusedIterator", "TrustedLen", "AsyncIterator" ->
                            listOf("Item")
                        else -> traitDefaultlessAssocTypes[KotlinNamingPolicy.Default.toTypeIdentifier(raw, allowRawAbi = false)].orEmpty()
                    }
                for (a in assocs) {
                    if (a !in explicitParamNames) {
                        lifted += a
                    }
                }
            }
        }
        k++
    }
    return lifted.size
}

private fun scanEnumVariantTypeArities(
    trees: List<TokenTree>,
    afterIdentIdx: Int,
    subpkg: String,
    ktEnum: String,
    rawAbiMode: Boolean,
    out: MutableMap<String, Int>,
    traitDefaultlessAssocTypes: Map<String, List<String>> = emptyMap(),
) {
    val headerParams = extractHeaderGenericTypeParams(trees, afterIdentIdx, rawAbiMode)
    val braceIdx = findEnumBodyBraceIndex(trees, headerParams.nextIdx) ?: return
    val braceGrp = (trees[braceIdx] as TokenTree.Group).value
    val headerSlice = if (braceIdx > afterIdentIdx) trees.subList(afterIdentIdx, braceIdx) else emptyList()
    val extraLiftedAssocs = countScannerEnumLiftedAssocs(headerSlice, headerParams.paramNames, traitDefaultlessAssocTypes)
    val typeArity = headerParams.typeArity + extraLiftedAssocs
    for (clause in MacroRulesFileExpander.splitTopLevelCommaClauses(braceGrp.stream().toList())) {
        val stripped = stripLeadingScannerAttrs(clause)
        val rawVar = (stripped.firstOrNull() as? TokenTree.Ident)?.value?.toString()?.removePrefix("r#") ?: continue
        val afterVar = stripped.getOrNull(1)
        val ktVar = KotlinNamingPolicy.Default.toTypeIdentifier(rawVar, allowRawAbi = rawAbiMode)
        if (ktVar.isEmpty()) continue
        val hasPayload =
            afterVar is TokenTree.Group &&
                (afterVar.value.delimiter() == Delimiter.Parenthesis || afterVar.value.delimiter() == Delimiter.Brace)
        if (!hasPayload) {
            if (typeArity == 0) {
                out.putIfMissing("$ktEnum.$ktVar", 0)
                if (subpkg.isNotEmpty()) {
                    out.putIfMissing("$subpkg.$ktEnum.$ktVar", 0)
                }
            }
            continue
        }
        if (typeArity > 0) {
            out.putIfMissing("$ktEnum.$ktVar", typeArity)
            if (subpkg.isNotEmpty()) {
                out.putIfMissing("$subpkg.$ktEnum.$ktVar", typeArity)
            }
            val prevSimple = out[ktVar]
            if (prevSimple == null) {
                out[ktVar] = typeArity
            } else if (prevSimple != typeArity) {
                out[ktVar] = -1
            }
        } else {
            val prevSimple = out[ktVar]
            if (prevSimple == null) {
                out[ktVar] = 0
            } else if (prevSimple > 0) {
                out[ktVar] = -1
            }
        }
    }
}

private fun findStructOrUnionBodyGroup(trees: List<TokenTree>, startIdx: Int): io.github.kotlinmania.procmacro2.Group? {
    var j = startIdx
    while (j < trees.size) {
        val tt = trees[j]
        if (tt is TokenTree.Punct && tt.value.asChar() == ';') return null
        if (tt is TokenTree.Group) {
            if (tt.value.delimiter() == Delimiter.Brace || tt.value.delimiter() == Delimiter.Parenthesis) {
                return tt.value
            }
        }
        j++
    }
    return null
}

private fun scanTraitTokenMetadata(
    trees: List<TokenTree>,
    startAfterIdent: Int,
    subpkg: String,
    ktTrait: String,
    traitDefaultlessAssocTypes: MutableMap<String, List<String>>,
    traitResultMethods: MutableMap<String, List<String>>,
) {
    var braceIdx = startAfterIdent
    while (braceIdx < trees.size) {
        val tt = trees[braceIdx]
        if (tt is TokenTree.Punct && tt.value.asChar() == ';') return
        if (tt is TokenTree.Group && tt.value.delimiter() == Delimiter.Brace) break
        braceIdx++
    }
    if (braceIdx >= trees.size) return
    val body = (trees[braceIdx] as TokenTree.Group).value.stream().toList()
    val assocNames = mutableListOf<String>()
    val resultMethods = linkedSetOf<String>()
    val itemAttrs = mutableListOf<List<TokenTree>>()
    var j = 0
    while (j < body.size) {
        val nextAttrIdx = tryConsumeScannerAttr(body, j, itemAttrs, allowInnerAttr = false)
        if (nextAttrIdx != null) {
            j = nextAttrIdx
            continue
        }
        val tok = body[j]
        val skipped = hasSkippedScannerAttrs(itemAttrs)
        val kw = (tok as? TokenTree.Ident)?.value?.toString()
        when (kw) {
            "type" -> {
                val assocRaw = (body.getOrNull(j + 1) as? TokenTree.Ident)?.value?.toString()
                var k = j + 2
                var angleDepth = 0
                var hasDefault = false
                while (k < body.size) {
                    val t = body[k]
                    if (t is TokenTree.Punct) {
                        when (t.value.asChar()) {
                            '<' -> angleDepth++
                            '>' -> angleDepth = maxOf(0, angleDepth - 1)
                            '=' -> if (angleDepth == 0) hasDefault = true
                            ';' -> break
                        }
                    }
                    k++
                }
                if (!skipped && assocRaw != null && !hasDefault) {
                    val assocKt = KotlinNamingPolicy.Default.toTypeIdentifier(assocRaw, allowRawAbi = false)
                    if (assocKt !in assocNames) assocNames += assocKt
                }
                j = k + 1
                itemAttrs.clear()
                continue
            }
            "fn" -> {
                val fnRaw = (body.getOrNull(j + 1) as? TokenTree.Ident)?.value?.toString()
                var k = j + 2
                var arrowIdx = -1
                var endIdx = body.size
                while (k < body.size) {
                    val t = body[k]
                    if (t is TokenTree.Punct && t.value.asChar() == '-' && t.value.spacing() == Spacing.Joint &&
                        (body.getOrNull(k + 1) as? TokenTree.Punct)?.value?.asChar() == '>'
                    ) {
                        if (arrowIdx < 0) arrowIdx = k + 2
                        k += 2
                        continue
                    }
                    if (t is TokenTree.Punct && t.value.asChar() == ';') {
                        endIdx = k
                        break
                    }
                    if (t is TokenTree.Group && t.value.delimiter() == Delimiter.Brace) {
                        endIdx = k
                        break
                    }
                    k++
                }
                if (!skipped && fnRaw != null && arrowIdx in 0 until endIdx) {
                    if (isResultReturnTokenSlice(body.subList(arrowIdx, endIdx))) {
                        resultMethods += rustFunctionIdentToKotlin(fnRaw)
                    }
                }
                j = endIdx + 1
                itemAttrs.clear()
                continue
            }
        }
        j++
        if (tok is TokenTree.Punct && tok.value.asChar() == ';') itemAttrs.clear()
    }
    if (assocNames.isNotEmpty()) {
        if (subpkg.isNotEmpty()) traitDefaultlessAssocTypes["$subpkg.$ktTrait"] = assocNames.toList()
        if (ktTrait !in traitDefaultlessAssocTypes) traitDefaultlessAssocTypes[ktTrait] = assocNames.toList()
    }
    if (resultMethods.isNotEmpty()) {
        if (subpkg.isNotEmpty()) traitResultMethods["$subpkg.$ktTrait"] = resultMethods.toList()
        if (ktTrait !in traitResultMethods) traitResultMethods[ktTrait] = resultMethods.toList()
    }
}

private fun isResultReturnTokenSlice(tokens: List<TokenTree>): Boolean {
    val pathIdents = mutableListOf<String>()
    for (t in tokens) {
        when {
            t is TokenTree.Ident -> pathIdents += t.value.toString().removePrefix("r#")
            t is TokenTree.Punct && t.value.asChar() == ':' -> {}
            else -> break
        }
    }
    if (pathIdents.lastOrNull() != "Result") return false
    val prev = pathIdents.getOrNull(pathIdents.lastIndex - 1)
    return prev != "fmt"
}

private fun isFieldlessNonEmptyEnumBody(trees: List<TokenTree>): Boolean {
    val clauses = MacroRulesFileExpander.splitTopLevelCommaClauses(trees)
    var variantCount = 0
    for (clause in clauses) {
        val stripped = stripLeadingScannerAttrs(clause)
        if (stripped.isEmpty()) continue
        variantCount++
        for (tt in stripped) {
            if (tt is TokenTree.Group &&
                (tt.value.delimiter() == Delimiter.Brace || tt.value.delimiter() == Delimiter.Parenthesis)
            ) {
                return false
            }
            if ((tt as? TokenTree.Punct)?.value?.asChar() == '=') {
                break
            }
        }
    }
    return variantCount > 0
}

private fun stripLeadingScannerAttrs(tokens: List<TokenTree>): List<TokenTree> {
    var i = 0
    val attrs = mutableListOf<List<TokenTree>>()
    while (i + 1 < tokens.size &&
        (tokens[i] as? TokenTree.Punct)?.value?.asChar() == '#' &&
        (tokens[i + 1] as? TokenTree.Group)?.value?.delimiter() == Delimiter.Bracket
    ) {
        attrs += (tokens[i + 1] as TokenTree.Group).value.stream().toList()
        i += 2
    }
    if (hasSkippedScannerAttrs(attrs)) return emptyList()
    return tokens.subList(i, tokens.size)
}

private fun stripVisibilityPrefix(tokens: List<TokenTree>): List<TokenTree> {
    if ((tokens.firstOrNull() as? TokenTree.Ident)?.value?.toString() != "pub") return tokens
    var i = 1
    if ((tokens.getOrNull(i) as? TokenTree.Group)?.value?.delimiter() == Delimiter.Parenthesis) {
        i++
    }
    return tokens.subList(i, tokens.size)
}

private fun parseRecordFieldsFromGroup(
    bodyGroup: io.github.kotlinmania.procmacro2.Group,
    rawAbiMode: Boolean,
    structFieldTypesOut: MutableMap<String, String>,
    ownerKtType: String,
    subpkg: String = "",
    isUnion: Boolean = false,
    excludedTypeParamNames: Set<String> = emptySet(),
): List<RawFieldTokenSpec> {
    fun containsExcludedTypeParam(tokens: List<TokenTree>): Boolean {
        if (excludedTypeParamNames.isEmpty()) return false
        for (tt in tokens) {
            when (tt) {
                is TokenTree.Ident -> {
                    val raw = tt.value.toString().removePrefix("r#")
                    if (raw in excludedTypeParamNames) return true
                }
                is TokenTree.Group -> {
                    if (tt.value.delimiter() == Delimiter.Bracket) {
                        val inner = tt.value.stream().toList()
                        val semiIdx = inner.indexOfFirst { (it as? TokenTree.Punct)?.value?.asChar() == ';' }
                        val elemTokens = if (semiIdx > 0) inner.subList(0, semiIdx) else inner
                        if (containsExcludedTypeParam(elemTokens)) return true
                    } else if (containsExcludedTypeParam(tt.value.stream().toList())) {
                        return true
                    }
                }
                else -> {}
            }
        }
        return false
    }
    val clauses = MacroRulesFileExpander.splitTopLevelCommaClauses(bodyGroup.stream().toList())
    val specs = mutableListOf<RawFieldTokenSpec>()
    val seenNames = hashSetOf<String>()
    val makeNullableForUnion = isUnion && !rawAbiMode && !TranslationSession.finalStructs
    if (bodyGroup.delimiter() == Delimiter.Brace) {
        for (clause in clauses) {
            val strippedAttrs = stripLeadingScannerAttrs(clause)
            if (strippedAttrs.isEmpty()) continue
            val afterVis = stripVisibilityPrefix(strippedAttrs)
            if (afterVis.size < 3) continue
            val rawField = (afterVis[0] as? TokenTree.Ident)?.value?.toString() ?: continue
            val colon = afterVis[1] as? TokenTree.Punct ?: continue
            if (colon.value.asChar() != ':' || colon.value.spacing() == Spacing.Joint) continue
            val ktField = rustFieldIdentToKotlin(rawField)
            if (!seenNames.add(ktField)) continue
            val rhsTokens = unwrapPaddingOrMaybeUninitTokens(afterVis.subList(2, afterVis.size))
            if (!containsExcludedTypeParam(rhsTokens)) {
                mapTokensFieldTypeQuick(rhsTokens, rawAbiMode)?.let { mappedTy ->
                    val recordedTy = if (makeNullableForUnion && !mappedTy.endsWith("?")) "$mappedTy?" else mappedTy
                    structFieldTypesOut.putIfMissing("$ownerKtType.$ktField", recordedTy)
                    if (subpkg.isNotEmpty()) {
                        structFieldTypesOut.putIfMissing("$subpkg.$ownerKtType.$ktField", recordedTy)
                    }
                }
            }
            specs += RawFieldTokenSpec(ktName = ktField, typeTokens = rhsTokens)
        }
    } else if (bodyGroup.delimiter() == Delimiter.Parenthesis) {
        val rawFieldTypes = mutableListOf<List<TokenTree>>()
        for (clause in clauses) {
            val strippedAttrs = stripLeadingScannerAttrs(clause)
            if (strippedAttrs.isEmpty()) continue
            val afterVis = stripVisibilityPrefix(strippedAttrs)
            if (afterVis.isEmpty()) continue
            rawFieldTypes += unwrapPaddingOrMaybeUninitTokens(afterVis)
        }
        val arity = rawFieldTypes.size
        for ((idx, rhsTokens) in rawFieldTypes.withIndex()) {
            val ktField = tupleFieldPropertyName(idx, arity)
            if (!containsExcludedTypeParam(rhsTokens)) {
                mapTokensFieldTypeQuick(rhsTokens, rawAbiMode)?.let { mappedTy ->
                    val recordedTy = if (makeNullableForUnion && !mappedTy.endsWith("?")) "$mappedTy?" else mappedTy
                    structFieldTypesOut.putIfMissing("$ownerKtType.$ktField", recordedTy)
                    if (subpkg.isNotEmpty()) {
                        structFieldTypesOut.putIfMissing("$subpkg.$ownerKtType.$ktField", recordedTy)
                    }
                }
            }
            specs += RawFieldTokenSpec(ktName = ktField, typeTokens = rhsTokens)
        }
    }
    return specs
}

private fun unwrapPaddingOrMaybeUninitTokens(tokens: List<TokenTree>): List<TokenTree> {
    if (tokens.isEmpty()) return tokens
    val lastIdentIdx = findPathTailIdentIndex(tokens) ?: return tokens
    val tailName = (tokens[lastIdentIdx] as TokenTree.Ident).value.toString()
    if (tailName != "Padding" && tailName != "MaybeUninit") return tokens
    if ((tokens.getOrNull(lastIdentIdx + 1) as? TokenTree.Punct)?.value?.asChar() != '<') return tokens
    if ((tokens.lastOrNull() as? TokenTree.Punct)?.value?.asChar() != '>') return tokens
    val inner = tokens.subList(lastIdentIdx + 2, tokens.size - 1)
    return if (inner.isNotEmpty()) unwrapPaddingOrMaybeUninitTokens(inner) else tokens
}

private fun findPathTailIdentIndex(tokens: List<TokenTree>): Int? {
    var i = 0
    if (tokens.size >= 2 &&
        (tokens[0] as? TokenTree.Punct)?.value?.asChar() == ':' &&
        (tokens[1] as? TokenTree.Punct)?.value?.asChar() == ':'
    ) {
        i = 2
    }
    if (tokens.getOrNull(i) !is TokenTree.Ident) return null
    var lastIdent = i
    i++
    while (i + 2 < tokens.size &&
        (tokens[i] as? TokenTree.Punct)?.value?.asChar() == ':' &&
        (tokens[i + 1] as? TokenTree.Punct)?.value?.asChar() == ':' &&
        tokens[i + 2] is TokenTree.Ident
    ) {
        lastIdent = i + 2
        i += 3
    }
    return lastIdent
}

internal fun mapTokensFieldTypeQuick(
    tokens: List<TokenTree>,
    rawAbiMode: Boolean,
    selfOwnerKt: String? = null,
    insideExternBlock: Boolean = false,
    numericKinds: Map<String, String>? = null,
    subpkg: String = "",
): String? {
    if (tokens.isEmpty()) return null
    val firstTok = tokens[0]
    if (firstTok is TokenTree.Ident && firstTok.value.toString() == "pub") {
        val secondTok = tokens.getOrNull(1)
        val visLen =
            if (secondTok is TokenTree.Group && secondTok.value.delimiter() == Delimiter.Parenthesis) {
                2
            } else {
                1
            }
        return mapTokensFieldTypeQuick(
            tokens = tokens.drop(visLen),
            rawAbiMode = rawAbiMode,
            selfOwnerKt = selfOwnerKt,
            insideExternBlock = insideExternBlock,
            numericKinds = numericKinds,
            subpkg = subpkg,
        )
    }
    if (tokens.size == 1 && (tokens[0] as? TokenTree.Group)?.value?.delimiter() == Delimiter.Parenthesis) {
        if ((tokens[0] as TokenTree.Group).value.stream().isEmpty()) return "Unit"
    }
    val first = tokens[0]
    if (first is TokenTree.Punct && first.value.asChar() == '*') {
        if (rawAbiMode) return "Long"
        val restAfterStar = tokens.drop(1)
        val mutOrConst = (restAfterStar.firstOrNull() as? TokenTree.Ident)?.value?.toString()
        val ptrWrapper = if (mutOrConst == "mut") "MutPtr" else "ConstPtr"
        val pointeeTokens = if (mutOrConst == "mut" || mutOrConst == "const") restAfterStar.drop(1) else restAfterStar
        if (pointeeTokens.isNotEmpty() && (pointeeTokens[0] as? TokenTree.Punct)?.value?.asChar() == '*') {
            val innerPointee =
                mapTokensFieldTypeQuick(
                    tokens = pointeeTokens,
                    rawAbiMode = false,
                    selfOwnerKt = selfOwnerKt,
                    insideExternBlock = insideExternBlock,
                    numericKinds = numericKinds,
                    subpkg = subpkg,
                ) ?: "CVoid"
            return "$ptrWrapper<$innerPointee>"
        }
        val pointeeLeaf = extractSimplePathLeafIdent(pointeeTokens) ?: return "$ptrWrapper<CVoid>"
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
    if (tokens.size == 1 && first is TokenTree.Group && first.value.delimiter() == Delimiter.Bracket) {
        val inner = first.value.stream().toList()
        val semiIdx = inner.indexOfFirst { (it as? TokenTree.Punct)?.value?.asChar() == ';' }
        if (semiIdx > 0) {
            val elemLeaf = extractSimplePathLeafIdent(inner.subList(0, semiIdx))
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
    val leaf = extractSimplePathLeafIdent(tokens) ?: return null
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

internal fun extractSimplePathLeafIdent(tokens: List<TokenTree>): String? {
    val tailIdx = findPathTailIdentIndex(tokens) ?: return null
    return (tokens[tailIdx] as TokenTree.Ident).value.toString().removePrefix("r#")
}

private fun scanPubTypeDeclaration(
    trees: List<TokenTree>,
    typeKwIdx: Int,
    subpkg: String,
    skippedByCfg: Boolean,
    rawAbiMode: Boolean,
    registerSymbol: (String, String) -> Unit,
    putScopedDefault: (String, String, String) -> Unit,
    putScopedNumericKind: (String, String, String) -> Unit,
    addScopedOptionFnType: (String, String) -> Unit,
    putScopedPointerAlias: (String, String, Pair<Boolean, String>) -> Unit,
    aliasLeafTargets: MutableMap<String, String>,
    qualifiedAliasLeafTargets: MutableMap<String, Pair<String, String>>,
    fnTypeSpecs: MutableMap<String, RawFnTypeTokenSpec>,
): Int {
    val rawIdent = (trees.getOrNull(typeKwIdx + 1) as? TokenTree.Ident)?.value?.toString()
    val endIdx = skipItemAfterHeader(trees, typeKwIdx + 1, semicolonOnly = true)
    if (rawIdent == null || skippedByCfg) return endIdx

    val ktName = KotlinNamingPolicy.Default.toTypeIdentifier(rawIdent, allowRawAbi = rawAbiMode)
    registerSymbol(ktName, subpkg)

    val afterIdent = typeKwIdx + 2
    if ((trees.getOrNull(afterIdent) as? TokenTree.Punct)?.value?.asChar() != '=') {
        return endIdx
    }
    val semiIdx = endIdx - 1
    if (semiIdx <= afterIdent) return endIdx
    val rhsTokens = trees.subList(afterIdent + 1, semiIdx)
    if (rhsTokens.isEmpty()) return endIdx

    val optionInner = extractOptionAngleInnerTokens(rhsTokens)
    val optionFnParsed = optionInner?.let(::tryParseBareFnTokens)
    if (optionFnParsed != null) {
        addScopedOptionFnType(subpkg, ktName)
        putScopedDefault(subpkg, ktName, "null")
        val samFnName = "${ktName}Fn"
        registerSymbol(samFnName, subpkg)
        val scopedKey = if (subpkg.isEmpty()) samFnName else "$subpkg.$samFnName"
        fnTypeSpecs.putIfMissing(
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

    val bareFnParsed = tryParseBareFnTokens(rhsTokens)
    if (bareFnParsed != null) {
        val scopedKey = if (subpkg.isEmpty()) ktName else "$subpkg.$ktName"
        fnTypeSpecs.putIfMissing(
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

    val first = rhsTokens[0]
    if (first is TokenTree.Punct && first.value.asChar() == '*') {
        val qual = (rhsTokens.getOrNull(1) as? TokenTree.Ident)?.value?.toString()
        val isMut = qual == "mut"
        val elemStart = if (qual == "mut" || qual == "const") 2 else 1
        val elemTokens = if (elemStart < rhsTokens.size) rhsTokens.subList(elemStart, rhsTokens.size) else emptyList()
        val pointeeIdent =
            if ((elemTokens.firstOrNull() as? TokenTree.Punct)?.value?.asChar() == '*') {
                "*"
            } else {
                extractSimplePathLeafIdent(elemTokens) ?: "*"
            }
        putScopedPointerAlias(subpkg, ktName, isMut to pointeeIdent)
        if (rawAbiMode) {
            putScopedDefault(subpkg, ktName, "0L")
            putScopedNumericKind(subpkg, ktName, "I64")
        } else {
            putScopedDefault(subpkg, ktName, if (isMut) "MutPtr(0L)" else "ConstPtr(0L)")
        }
        return endIdx
    }

    if (rhsTokens.size == 1 && first is TokenTree.Group && first.value.delimiter() == Delimiter.Parenthesis) {
        val inner = first.value.stream().toList()
        if (inner.isEmpty()) {
            putScopedDefault(subpkg, ktName, "Unit")
            return endIdx
        }
    }

    if (rhsTokens.size == 1 && first is TokenTree.Group && first.value.delimiter() == Delimiter.Bracket) {
        val mapped = mapTokensFieldTypeQuick(rhsTokens, rawAbiMode)
        val defaultExpr =
            when {
                mapped != null && mapped.endsWith("Array") -> "$mapped(0)"
                else -> "emptyList()"
            }
        putScopedDefault(subpkg, ktName, defaultExpr)
        return endIdx
    }

    if (first !is TokenTree.Group) {
        val leaf = extractSimplePathLeafIdent(rhsTokens)
        if (leaf != null && rhsTokens.none { (it as? TokenTree.Punct)?.value?.asChar() == '<' }) {
            aliasLeafTargets.putIfMissing(ktName, leaf)
            if (subpkg.isNotEmpty()) {
                qualifiedAliasLeafTargets.putIfMissing("$subpkg.$ktName", subpkg to leaf)
            }
        }
    }
    return endIdx
}

internal fun extractOptionAngleInnerTokens(rhsTokens: List<TokenTree>): List<TokenTree>? {
    val tailIdx = findPathTailIdentIndex(rhsTokens) ?: return null
    if ((rhsTokens[tailIdx] as TokenTree.Ident).value.toString() != "Option") return null
    if ((rhsTokens.getOrNull(tailIdx + 1) as? TokenTree.Punct)?.value?.asChar() != '<') return null
    if ((rhsTokens.lastOrNull() as? TokenTree.Punct)?.value?.asChar() != '>') return null
    val inner = rhsTokens.subList(tailIdx + 2, rhsTokens.size - 1)
    return inner.takeIf { it.isNotEmpty() }
}

internal fun tryParseBareFnTokens(rawTokens: List<TokenTree>): Pair<List<List<TokenTree>>, List<TokenTree>>? {
    val tokens = stripLeadingScannerAttrs(rawTokens)
    var i = 0
    if ((tokens.getOrNull(i) as? TokenTree.Ident)?.value?.toString() == "for" &&
        (tokens.getOrNull(i + 1) as? TokenTree.Punct)?.value?.asChar() == '<'
    ) {
        i = skipBalancedAngleBrackets(tokens, i + 1) ?: tokens.size
    }
    if ((tokens.getOrNull(i) as? TokenTree.Ident)?.value?.toString() == "unsafe") i++
    if ((tokens.getOrNull(i) as? TokenTree.Ident)?.value?.toString() == "extern") {
        i++
        if (tokens.getOrNull(i) is TokenTree.Literal) i++
    }
    if ((tokens.getOrNull(i) as? TokenTree.Ident)?.value?.toString() != "fn") return null
    val paramsGroup = (treesOrNullGroup(tokens, i + 1)) ?: return null
    if (paramsGroup.delimiter() != Delimiter.Parenthesis) return null
    val paramClauses =
        MacroRulesFileExpander
            .splitTopLevelCommaClauses(paramsGroup.stream().toList())
            .filter { it.isNotEmpty() && !isVariadicDotsClause(it) }
            .map { clause -> extractBareFnParamTypeTokens(clause) }
    val afterParams = i + 2
    val retTokens =
        if (afterParams + 1 < tokens.size &&
            (tokens[afterParams] as? TokenTree.Punct)?.value?.asChar() == '-' &&
            (tokens[afterParams + 1] as? TokenTree.Punct)?.value?.asChar() == '>'
        ) {
            tokens.subList(afterParams + 2, tokens.size)
        } else {
            emptyList()
        }
    return paramClauses to retTokens
}

private fun isVariadicDotsClause(clause: List<TokenTree>): Boolean {
    val stripped = stripLeadingScannerAttrs(clause)
    return stripped.size == 3 && stripped.all { (it as? TokenTree.Punct)?.value?.asChar() == '.' }
}

private fun treesOrNullGroup(tokens: List<TokenTree>, idx: Int): io.github.kotlinmania.procmacro2.Group? =
    (tokens.getOrNull(idx) as? TokenTree.Group)?.value

private fun extractBareFnParamTypeTokens(clause: List<TokenTree>): List<TokenTree> {
    val stripped = stripLeadingScannerAttrs(clause)
    if (stripped.size >= 3 && stripped[0] is TokenTree.Ident) {
        val colon = stripped[1] as? TokenTree.Punct
        if (colon != null && colon.value.asChar() == ':' && colon.value.spacing() != Spacing.Joint) {
            return stripped.subList(2, stripped.size)
        }
    }
    if (stripped.size >= 4 &&
        (stripped[0] as? TokenTree.Ident)?.value?.toString() == "mut" &&
        stripped[1] is TokenTree.Ident
    ) {
        val colon = stripped[2] as? TokenTree.Punct
        if (colon != null && colon.value.asChar() == ':' && colon.value.spacing() != Spacing.Joint) {
            return stripped.subList(3, stripped.size)
        }
    }
    return stripped
}

private fun extractNonMethodFnParamTokenLists(
    trees: List<TokenTree>,
    startIdx: Int,
): List<List<TokenTree>>? {
    val fnIdx = skipFnQualifiersAndFindFnKw(trees, startIdx) ?: return null
    var afterIdent = fnIdx + 2
    if ((trees.getOrNull(afterIdent) as? TokenTree.Punct)?.value?.asChar() == '<') {
        afterIdent = skipBalancedAngleBrackets(trees, afterIdent) ?: return null
    }
    val paramsGrp = (trees.getOrNull(afterIdent) as? TokenTree.Group)?.value ?: return null
    if (paramsGrp.delimiter() != Delimiter.Parenthesis) return null
    val clauses =
        MacroRulesFileExpander
            .splitTopLevelCommaClauses(paramsGrp.stream().toList())
            .filter { it.isNotEmpty() && !isVariadicDotsClause(it) }
    val paramTypes = mutableListOf<List<TokenTree>>()
    for (clause in clauses) {
        val stripped = stripLeadingScannerAttrs(clause)
        val firstId = (stripped.firstOrNull() as? TokenTree.Ident)?.value?.toString()
        val secondId = (stripped.getOrNull(1) as? TokenTree.Ident)?.value?.toString()
        val firstPunct = (stripped.firstOrNull() as? TokenTree.Punct)?.value?.asChar()
        if (firstId == "self" || secondId == "self" || firstPunct == '&') {
            if (stripped.any { (it as? TokenTree.Ident)?.value?.toString() == "self" }) {
                return null
            }
        }
        paramTypes += extractBareFnParamTypeTokens(stripped)
    }
    return paramTypes
}

private fun extractConstParamNamesFromGenericBrackets(
    trees: List<TokenTree>,
    ltIdx: Int,
): List<String> {
    val closeIdx = skipBalancedAngleBrackets(trees, ltIdx) ?: return emptyList()
    if (closeIdx <= ltIdx + 1) return emptyList()
    val inner = trees.subList(ltIdx + 1, closeIdx - 1)
    val clauses = MacroRulesFileExpander.splitTopLevelCommaClauses(inner)
    val constNames = mutableListOf<String>()
    for (clause in clauses) {
        val stripped = stripLeadingScannerAttrs(clause)
        if ((stripped.firstOrNull() as? TokenTree.Ident)?.value?.toString() == "const") {
            val identRaw = (stripped.getOrNull(1) as? TokenTree.Ident)?.value?.toString()?.removePrefix("r#") ?: continue
            constNames += rustValueIdentToKotlin(identRaw)
        }
    }
    return constNames
}

private fun extractFnConstParamNamesFromHeader(
    trees: List<TokenTree>,
    startIdx: Int,
): List<String> {
    val fnIdx = skipFnQualifiersAndFindFnKw(trees, startIdx, allowBareLiteral = true) ?: return emptyList()
    val afterIdent = fnIdx + 2
    if ((trees.getOrNull(afterIdent) as? TokenTree.Punct)?.value?.asChar() != '<') return emptyList()
    return extractConstParamNamesFromGenericBrackets(trees, afterIdent)
}

private fun scanInherentImplAssociatedFunctions(
    trees: List<TokenTree>,
    implKwIdx: Int,
    endImplIdx: Int,
    subpkg: String,
    rawAbiMode: Boolean,
    registerFnSig: (String, String, List<List<TokenTree>>?, String?, Boolean) -> Unit,
    registerFnConstParams: (String, String, Int, List<String>) -> Unit = { _, _, _, _ -> },
) {
    val headerSlice = trees.subList(implKwIdx + 1, endImplIdx)
    val braceTree = headerSlice.lastOrNull { (it as? TokenTree.Group)?.value?.delimiter() == Delimiter.Brace } as? TokenTree.Group ?: return
    val beforeBrace = headerSlice.subList(0, headerSlice.indexOf(braceTree))
    val hasGenericsOrFor =
        beforeBrace.any {
            (it as? TokenTree.Punct)?.value?.asChar() == '<' ||
                (it as? TokenTree.Ident)?.value?.toString() == "for"
        }
    var scanIdx = 0
    val implConsts =
        if ((beforeBrace.firstOrNull() as? TokenTree.Punct)?.value?.asChar() == '<') {
            val closeIdx = skipBalancedAngleBrackets(beforeBrace, 0) ?: return
            val consts = extractConstParamNamesFromGenericBrackets(beforeBrace, 0)
            scanIdx = closeIdx
            consts
        } else {
            emptyList()
        }
    if (scanIdx < beforeBrace.size) {
        val afterImplType = beforeBrace.subList(scanIdx, beforeBrace.size)
        var angleDepth = 0
        for (tok in afterImplType) {
            if (tok is TokenTree.Punct) {
                val c = tok.value.asChar()
                if (c == '<') {
                    angleDepth++
                } else if (c == '>' && angleDepth > 0) {
                    angleDepth--
                }
            } else if (angleDepth == 0 && tok is TokenTree.Ident && tok.value.toString() == "for") {
                return
            }
        }
    }
    val typeHeadTokens =
        beforeBrace
            .subList(scanIdx, beforeBrace.size)
            .takeWhile {
                val c = (it as? TokenTree.Punct)?.value?.asChar()
                val kw = (it as? TokenTree.Ident)?.value?.toString()
                c != '<' && kw != "where"
            }
    val ownerLeaf = extractSimplePathLeafIdent(typeHeadTokens) ?: return
    val ownerKt = KotlinNamingPolicy.Default.toTypeIdentifier(ownerLeaf, allowRawAbi = rawAbiMode)
    if (ownerKt.isEmpty()) return

    val bodyTrees = braceTree.value.stream().toList()
    val pendingAttrs = mutableListOf<List<TokenTree>>()
    var i = 0
    while (i < bodyTrees.size) {
        val nextAttrIdx = tryConsumeScannerAttr(bodyTrees, i, pendingAttrs, allowInnerAttr = false)
        if (nextAttrIdx != null) {
            i = nextAttrIdx
            continue
        }
        val cur = bodyTrees[i]
        val skipped = hasSkippedScannerAttrs(pendingAttrs)
        if ((cur as? TokenTree.Ident)?.value?.toString() == "pub") {
            var kwIdx = i + 1
            if ((bodyTrees.getOrNull(kwIdx) as? TokenTree.Group)?.value?.delimiter() == Delimiter.Parenthesis) {
                kwIdx++
            }
            val fnIdent = findFnIdentAfterQualifiers(bodyTrees, kwIdx)
            val endFnIdx = skipItemAfterHeader(bodyTrees, kwIdx, semicolonOnly = fnIdent == null)
            if (fnIdent != null && !skipped) {
                val ktMethod = rustFunctionIdentToKotlin(fnIdent)
                val pLists = extractNonMethodFnParamTokenLists(bodyTrees, kwIdx)
                if (!hasGenericsOrFor) {
                    val retTy = extractFnReturnTypeFromHeader(bodyTrees, kwIdx, endFnIdx, rawAbiMode, ownerKt)
                    if (pLists != null || retTy != null) {
                        registerFnSig(subpkg, "$ownerKt.$ktMethod", pLists, retTy, false)
                    }
                }
                val combinedConsts = (implConsts + extractFnConstParamNamesFromHeader(bodyTrees, kwIdx)).distinct()
                if (pLists != null && combinedConsts.isNotEmpty()) {
                    registerFnConstParams(subpkg, "$ownerKt.$ktMethod", pLists.size, combinedConsts)
                }
            }
            i = endFnIdx
            pendingAttrs.clear()
            continue
        }
        if ((cur is TokenTree.Punct && cur.value.asChar() == ';') ||
            (cur is TokenTree.Group && cur.value.delimiter() == Delimiter.Brace)
        ) {
            pendingAttrs.clear()
        }
        i++
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

        scanInlineModulePatternsInTokens(
            trees = rootTrees,
            isTopLevel = true,
            multiplier = 1,
            macroUseStemsOut = macroUseStems,
            topLevelPrivateModsOut = topLevelPrivateMods,
            nestedPrivateModCountsOut = nestedPrivateModCounts,
            pubUseGlobStemsOut = pubUseGlobStems,
        )

        val zeroArgCallCounts = countZeroArgMacroCalls(rootTrees)
        for (macroStem in macroUseStems) {
            val macroRel = "$dirPrefix$macroStem.rs"
            val macroSubMod = "$dirPrefix$macroStem/mod.rs"
            if (macroRel in byRelPath && macroSubMod !in byRelPath) {
                result += macroRel
                val macroTrees = lexScannerTokens(byRelPath.getValue(macroRel))
                val macroDefs = LinkedHashMap<String, MacroRulesDef>()
                collectMacroRulesFromTokenTrees(macroTrees, macroDefs)
                for ((mName, mDef) in macroDefs) {
                    val callCount = zeroArgCallCounts[mName] ?: 0
                    if (callCount >= 2) {
                        for (branch in mDef.branches) {
                            if (branch.pattern.isEmpty()) {
                                scanInlineModulePatternsInTokens(
                                    trees = branch.bodyTokens.toList(),
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

private fun scanInlineModulePatternsInTokens(
    trees: List<TokenTree>,
    isTopLevel: Boolean,
    multiplier: Int,
    macroUseStemsOut: MutableSet<String>,
    topLevelPrivateModsOut: MutableSet<String>,
    nestedPrivateModCountsOut: MutableMap<String, Int>,
    pubUseGlobStemsOut: MutableSet<String>,
) {
    val pendingAttrs = mutableListOf<List<TokenTree>>()
    var i = 0
    while (i < trees.size) {
        val nextAttrIdx = tryConsumeScannerAttr(trees, i, pendingAttrs)
        if (nextAttrIdx != null) {
            i = nextAttrIdx
            continue
        }
        val cur = trees[i]
        if (cur is TokenTree.Group) {
            scanInlineModulePatternsInTokens(
                trees = cur.value.stream().toList(),
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
        val curId = (cur as? TokenTree.Ident)?.value?.toString()
        if (curId == "mod") {
            val prevId = (trees.getOrNull(i - 1) as? TokenTree.Ident)?.value?.toString()
            val prevIsVisGroup =
                i >= 2 &&
                    (trees.getOrNull(i - 2) as? TokenTree.Ident)?.value?.toString() == "pub" &&
                    (trees.getOrNull(i - 1) as? TokenTree.Group)?.value?.delimiter() == Delimiter.Parenthesis
            val modIdent = (trees.getOrNull(i + 1) as? TokenTree.Ident)?.value?.toString()?.removePrefix("r#")
            val semi = (trees.getOrNull(i + 2) as? TokenTree.Punct)?.value?.asChar() == ';'
            if (prevId != "pub" && !prevIsVisGroup && modIdent != null && semi) {
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
            var uIdx = i + 1
            if ((trees.getOrNull(uIdx) as? TokenTree.Group)?.value?.delimiter() == Delimiter.Parenthesis) {
                uIdx++
            }
            if ((trees.getOrNull(uIdx) as? TokenTree.Ident)?.value?.toString() == "use") {
                var endUse = uIdx + 1
                while (endUse < trees.size && (trees[endUse] as? TokenTree.Punct)?.value?.asChar() != ';') {
                    endUse++
                }
                val useSlice = trees.subList(uIdx + 1, endUse)
                if (useSlice.size >= 4 &&
                    (useSlice.last() as? TokenTree.Punct)?.value?.asChar() == '*' &&
                    (useSlice[useSlice.size - 2] as? TokenTree.Punct)?.value?.asChar() == ':' &&
                    (useSlice[useSlice.size - 3] as? TokenTree.Punct)?.value?.asChar() == ':' &&
                    useSlice[useSlice.size - 4] is TokenTree.Ident
                ) {
                    pubUseGlobStemsOut += (useSlice[useSlice.size - 4] as TokenTree.Ident).value.toString().removePrefix("r#")
                }
                pendingAttrs.clear()
                i = if (endUse < trees.size) endUse + 1 else endUse
                continue
            }
        }
        if (cur is TokenTree.Punct && cur.value.asChar() == ';') {
            pendingAttrs.clear()
        }
        i++
    }
}

private fun countZeroArgMacroCalls(trees: List<TokenTree>): Map<String, Int> {
    val counts = linkedMapOf<String, Int>()
    fun walk(list: List<TokenTree>) {
        var i = 0
        while (i < list.size) {
            val tt = list[i]
            if (tt is TokenTree.Group) {
                walk(tt.value.stream().toList())
                i++
                continue
            }
            if (tt is TokenTree.Ident &&
                (list.getOrNull(i + 1) as? TokenTree.Punct)?.value?.asChar() == '!' &&
                list.getOrNull(i + 2) is TokenTree.Group
            ) {
                val grp = (list[i + 2] as TokenTree.Group).value
                if (grp.stream().isEmpty()) {
                    val mName = tt.value.toString().removePrefix("r#")
                    counts[mName] = (counts[mName] ?: 0) + 1
                } else {
                    walk(grp.stream().toList())
                }
                i += 3
                continue
            }
            i++
        }
    }
    walk(trees)
    return counts
}
