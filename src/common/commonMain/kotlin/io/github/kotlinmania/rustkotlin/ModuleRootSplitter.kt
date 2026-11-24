package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.procmacro2.Span
import io.github.kotlinmania.syn.Attribute
import io.github.kotlinmania.syn.File
import io.github.kotlinmania.syn.GenericParam
import io.github.kotlinmania.syn.Generics
import io.github.kotlinmania.syn.Item
import io.github.kotlinmania.syn.ModContent
import io.github.kotlinmania.syn.PathArguments
import io.github.kotlinmania.syn.SynType
import io.github.kotlinmania.syn.UseTree
import io.github.kotlinmania.syn.Visibility

internal object TranslationSessionGuard {
    fun <T> withHeaderContext(
        headerContext: FileHeaderContext,
        preserveSharedModuleFile: Boolean = false,
        block: (allowStdlibDestructors: Boolean) -> T,
    ): T {
        val prevLayoutSource = TranslationSession.sourceLayoutFile
        val prevAllowDestructors = TranslationSession.allowStdlibDestructors
        val prevRawAbi = TranslationSession.rawAbiMode
        val prevFinalStructs = TranslationSession.finalStructs
        val prevRelDir = TranslationSession.currentRelativeSourceDir
        val prevCfgProfile = TranslationSession.cfgTargetProfile
        val prevAliasDefaults = TranslationSession.crateTypeAliasDefaults
        val prevOptionFnTypes = TranslationSession.crateOptionFnTypes
        val prevStructFields = TranslationSession.crateStructFieldTypes
        val prevFnReturns = TranslationSession.crateFnReturnTypes
        val prevSharedMod = TranslationSession.sharedModuleFile
        TranslationSession.rawAbiMode = headerContext.rawAbiMode
        TranslationSession.finalStructs = headerContext.finalClasses
        TranslationSession.currentRelativeSourceDir = headerContext.currentRelativeSourceDir
        if (headerContext.cfgTargetProfile != null) {
            TranslationSession.cfgTargetProfile = headerContext.cfgTargetProfile
        }
        TranslationSession.crateTypeAliasDefaults = headerContext.crateTypeAliasDefaults
        TranslationSession.crateOptionFnTypes = headerContext.crateOptionFnTypes
        TranslationSession.crateStructFieldTypes = headerContext.crateStructFieldTypes
        TranslationSession.crateFnReturnTypes = headerContext.crateFnReturnTypes
        val allowStdlibDestructors =
            prevAllowDestructors ||
                headerContext.crateNamespaces.containsKey("core") ||
                headerContext.packageName?.let {
                    it == "io.github.kotlinmania.rustlang" ||
                        it.startsWith("io.github.kotlinmania.rustlang.") ||
                        it == "org.rustlang" ||
                        it.startsWith("org.rustlang.")
                } == true
        TranslationSession.allowStdlibDestructors = allowStdlibDestructors
        return try {
            block(allowStdlibDestructors)
        } finally {
            if (preserveSharedModuleFile) {
                TranslationSession.sharedModuleFile = prevSharedMod
            }
            TranslationSession.sourceLayoutFile = prevLayoutSource
            TranslationSession.allowStdlibDestructors = prevAllowDestructors
            TranslationSession.rawAbiMode = prevRawAbi
            TranslationSession.finalStructs = prevFinalStructs
            TranslationSession.currentRelativeSourceDir = prevRelDir
            TranslationSession.cfgTargetProfile = prevCfgProfile
            TranslationSession.crateTypeAliasDefaults = prevAliasDefaults
            TranslationSession.crateOptionFnTypes = prevOptionFnTypes
            TranslationSession.crateStructFieldTypes = prevStructFields
            TranslationSession.crateFnReturnTypes = prevFnReturns
        }
    }
}

internal sealed class ModuleRootChunkBucket(
    private val stem: String,
    private val maxUnitsPerChunk: Int,
) {
    object Structs : ModuleRootChunkBucket("Structs", 250)

    object Constants : ModuleRootChunkBucket("Constants", 1500)

    object Functions : ModuleRootChunkBucket("Functions", 500)

    fun appendItems(
        out: MutableList<Pair<String, List<Item>>>,
        modulePascalName: String,
        items: List<Item>,
    ) {
        if (items.isEmpty()) return
        items.chunked(maxUnitsPerChunk).forEachIndexed { idx, slice ->
            out += chunkFileName(modulePascalName, idx) to slice
        }
    }

    fun appendGroups(
        out: MutableList<Pair<String, List<Item>>>,
        modulePascalName: String,
        groups: List<List<Item>>,
    ) {
        if (groups.isEmpty()) return
        groups.chunked(maxUnitsPerChunk).forEachIndexed { idx, slice ->
            out += chunkFileName(modulePascalName, idx) to slice.flatten()
        }
    }

    private fun chunkFileName(modulePascalName: String, idx: Int): String =
        if (idx == 0) "$modulePascalName$stem.kt" else "$modulePascalName$stem${idx + 1}.kt"
}

internal fun isSealedMarkerInlineMod(item: Item.Mod): Boolean {
    val content = item.content as? ModContent.Inline ?: return false
    return item.vis !is Visibility.Public &&
        item.ident.toString() in setOf("private", "sealed") &&
        content.items.all { sub ->
            sub is Item.Use ||
                (sub is Item.Trait && sub.ident.toString() == "Sealed" && sub.items.isEmpty()) ||
                (
                    sub is Item.Impl &&
                        sub.traitPath
                            ?.path
                            ?.segments
                            ?.last()
                            ?.ident
                            ?.toString() == "Sealed" &&
                        sub.items.isEmpty()
                )
        }
}

private fun collectDirectDeclaredItemCollisionNames(items: List<Item>): Set<String> {
    val names = linkedSetOf<String>()
    for (item in items) {
        if (itemHasDocOnlyCfg(item)) continue
        when (item) {
            is Item.Struct -> names += rustTypeIdentToKotlin(item.ident.toString())
            is Item.Enum -> names += rustTypeIdentToKotlin(item.ident.toString())
            is Item.Union -> names += rustTypeIdentToKotlin(item.ident.toString())
            is Item.Trait -> names += rustTypeIdentToKotlin(item.ident.toString())
            is Item.TraitAlias -> names += rustTypeIdentToKotlin(item.ident.toString())
            is Item.ItemType -> names += rustTypeIdentToKotlin(item.ident.toString())
            is Item.Fn -> names += rustFunctionIdentToKotlin(item.sig.ident.toString())
            is Item.Const -> names += rustConstIdentToKotlin(item.ident.toString())
            is Item.Static -> names += rustConstIdentToKotlin(item.ident.toString())
            else -> {}
        }
    }
    return names
}

internal fun selectSplitCandidateInlineModsForFile(file: File): List<Item.Mod> {
    val candidates =
        file.items.filterIsInstance<Item.Mod>().filter { modItem ->
            modItem.content is ModContent.Inline &&
                !itemHasDocOnlyCfg(modItem) &&
                !hasCfgTestModAttribute(modItem.attrs) &&
                !isSealedMarkerInlineMod(modItem) &&
                modItem.ident.toString().removePrefix("r#") !in setOf("tests", "test")
        }
    if (candidates.isEmpty()) return emptyList()
    val seenNames = hashSetOf<String>()
    val seenModTargets = hashSetOf<String>()
    val parentNonModItems = file.items.filterNot { it is Item.Mod }
    seenNames.addAll(collectDirectDeclaredItemCollisionNames(parentNonModItems))
    var hasCollision = false
    for (modItem in candidates) {
        val rawMod = modItem.ident.toString().removePrefix("r#")
        val modKt = rustModuleIdentToKotlin(rawMod.replace('-', '_'))
        val modPascal = rustTypeIdentToKotlin(rawMod)
        if (modKt.isNotEmpty() && !seenModTargets.add("$modKt/$modPascal.kt")) {
            hasCollision = true
            break
        }
        val modItems = (modItem.content as ModContent.Inline).items
        for (declName in collectDirectDeclaredItemCollisionNames(modItems)) {
            if (!seenNames.add(declName)) {
                hasCollision = true
                break
            }
        }
        if (hasCollision) break
    }
    return if (hasCollision) candidates else emptyList()
}

private fun selectSplitCandidateInlineMods(file: File, headerContext: FileHeaderContext): List<Item.Mod> {
    if (headerContext.packageName.isNullOrBlank()) return emptyList()
    return selectSplitCandidateInlineModsForFile(file)
}

internal fun flattenPrivateDefinitionInlineMods(file: File): File {
    val defModsByRaw = linkedMapOf<String, Item.Mod>()
    for (item in file.items) {
        if (itemHasDocOnlyCfg(item)) continue
        if (item is Item.Mod && item.vis !is Visibility.Public && item.content is ModContent.Inline) {
            val modRaw = item.ident.toString().removePrefix("r#")
            if (modRaw.endsWith("_definition")) {
                defModsByRaw[modRaw] = item
            }
        }
    }
    if (defModsByRaw.isEmpty()) return file

    val innerTypesByModAndName = linkedMapOf<Pair<String, String>, MutableList<Item.ItemType>>()
    for ((modRaw, modItem) in defModsByRaw) {
        val content = modItem.content as ModContent.Inline
        for (inner in content.items) {
            if (itemHasDocOnlyCfg(inner)) continue
            if (inner is Item.ItemType) {
                val innerRaw = inner.ident.toString().removePrefix("r#")
                innerTypesByModAndName.getOrPut(modRaw to innerRaw) { mutableListOf() }.add(inner)
            }
        }
    }

    val replacementByIdx = linkedMapOf<Int, List<Item.ItemType>>()
    val matchedPairsByMod = linkedMapOf<String, MutableSet<String>>()
    file.items.forEachIndexed { idx, item ->
        if (itemHasDocOnlyCfg(item)) return@forEachIndexed
        if (item is Item.ItemType && item.generics.params.isEmpty()) {
            val rhs = unwrapSynType(item.ty) as? SynType.Path ?: return@forEachIndexed
            if (rhs.qself != null) return@forEachIndexed
            val segs = rhs.path.segments.toList()
            if (segs.size != 2 || segs[1].arguments !is PathArguments.None) return@forEachIndexed
            val modSeg = segs[0].ident.toString().removePrefix("r#")
            val innerSeg = segs[1].ident.toString().removePrefix("r#")
            val outerRaw = item.ident.toString().removePrefix("r#")
            if (outerRaw != innerSeg) return@forEachIndexed
            val hoisted = innerTypesByModAndName[modSeg to innerSeg] ?: return@forEachIndexed
            replacementByIdx[idx] =
                hoisted.map { inner ->
                    inner.copy(
                        attrs = item.attrs + inner.attrs,
                        vis = item.vis,
                    )
                }
            matchedPairsByMod.getOrPut(modSeg) { hashSetOf() }.add(innerSeg)
        }
    }
    if (replacementByIdx.isEmpty()) return file

    val consumedModNames = hashSetOf<String>()
    for ((modRaw, modItem) in defModsByRaw) {
        val matchedNames = matchedPairsByMod[modRaw] ?: continue
        val content = modItem.content as ModContent.Inline
        val allNonDocMatched =
            content.items.all { inner ->
                itemHasDocOnlyCfg(inner) ||
                    (inner is Item.ItemType && inner.ident.toString().removePrefix("r#") in matchedNames)
            }
        if (allNonDocMatched) {
            consumedModNames += modRaw
        }
    }

    val rewritten = mutableListOf<Item>()
    file.items.forEachIndexed { idx, item ->
        val replacement = replacementByIdx[idx]
        when {
            replacement != null -> rewritten.addAll(replacement)
            item is Item.Mod &&
                item.vis !is Visibility.Public &&
                item.content is ModContent.Inline &&
                item.ident.toString().removePrefix("r#") in consumedModNames -> {
                // Hoisted into outer alias position.
            }
            else -> rewritten += item
        }
    }
    return File(
        shebang = file.shebang,
        frontmatter = file.frontmatter,
        attrs = file.attrs,
        items = rewritten,
    )
}

internal fun selectParentItemsExcludingSplitInlineMods(file: File, headerContext: FileHeaderContext): File {
    val splitMods = selectSplitCandidateInlineMods(file, headerContext)
    if (splitMods.isEmpty()) return file
    val excludedSet = splitMods.toSet()
    return File(
        shebang = file.shebang,
        attrs = file.attrs,
        items =
            file.items.map { item ->
                if (item in excludedSet && item is Item.Mod) item.copy(content = null) else item
            },
    )
}

private fun useTreeStartsWithSuper(tree: UseTree): Boolean =
    when (tree) {
        is UseTree.Path -> tree.ident.toString().removePrefix("r#") == "super"
        is UseTree.Group -> tree.items.toList().any(::useTreeStartsWithSuper)
        else -> false
    }

private fun useTreeRootIdent(tree: UseTree): String? =
    when (tree) {
        is UseTree.Path -> tree.ident.toString().removePrefix("r#")
        is UseTree.Name -> tree.ident.toString().removePrefix("r#")
        else -> null
    }

private fun mergeDuplicateSplitInlineMods(
    modGroup: List<Item.Mod>,
    parentUses: List<Item.Use>,
): Pair<List<Attribute>, List<Item>> {
    if (modGroup.size == 1) {
        val single = modGroup.first()
        val inlineItems = (single.content as ModContent.Inline).items
        val hasSuperImport =
            inlineItems.any { child ->
                child is Item.Use && !itemHasDocOnlyCfg(child) && useTreeStartsWithSuper(child.tree)
            }
        val childItems = if (hasSuperImport && parentUses.isNotEmpty()) parentUses + inlineItems else inlineItems
        return single.attrs to childItems
    }
    val rankedMods = modGroup.sortedByDescending { cfgAttrPreferenceScore(it.attrs) }
    val primaryMod = rankedMods.first()
    val claimedDeclNames = hashSetOf<String>()
    val seenUseTokens = hashSetOf<String>()
    val mergedItems = mutableListOf<Item>()
    var anyHasSuperImport = false
    for (modItem in rankedMods) {
        val inlineItems = (modItem.content as ModContent.Inline).items
        val skippedTypeNamesInCurrentMod = hashSetOf<String>()
        for (item in inlineItems) {
            if (itemHasDocOnlyCfg(item)) continue
            when (item) {
                is Item.Use -> {
                    if (useTreeStartsWithSuper(item.tree)) {
                        anyHasSuperImport = true
                    }
                    if (seenUseTokens.add(item.toTokenStream().toString())) {
                        mergedItems += item
                    }
                }
                is Item.Impl -> {
                    val owner = extractChunkOwnerTypeName(item.selfType, item.generics)
                    if (owner == null || owner !in skippedTypeNamesInCurrentMod) {
                        mergedItems += item
                    }
                }
                else -> {
                    val declNames = collectDirectDeclaredItemCollisionNames(listOf(item))
                    if (declNames.isNotEmpty() && declNames.any { it in claimedDeclNames }) {
                        if (item is Item.Struct || item is Item.Enum || item is Item.Union) {
                            skippedTypeNamesInCurrentMod.addAll(declNames)
                        }
                        continue
                    }
                    claimedDeclNames.addAll(declNames)
                    mergedItems += item
                }
            }
        }
    }
    val finalItems = if (anyHasSuperImport && parentUses.isNotEmpty()) parentUses + mergedItems else mergedItems
    return primaryMod.attrs to finalItems
}

internal fun emitSplitInlineModCompanions(
    expandedFile: File,
    source: String,
    trivia: SourceTrivia,
    macroDefs: Map<String, MacroRulesDef>,
    macroWrapperSpansByTargetOffset: Map<Int, List<Span>>,
    headerContext: FileHeaderContext,
): List<CompanionInteropFile> {
    val splitMods = selectSplitCandidateInlineMods(expandedFile, headerContext)
    if (splitMods.isEmpty()) return emptyList()
    val basePkg = headerContext.packageName!!.trim().removeSuffix(".")
    val effectiveNs = headerContext.projectNamespace ?: basePkg
    val parentLocalMods =
        expandedFile.items
            .filterIsInstance<Item.Mod>()
            .mapTo(hashSetOf()) { it.ident.toString().removePrefix("r#") }
    val parentUses =
        expandedFile.items
            .filterIsInstance<Item.Use>()
            .filterNot(::itemHasDocOnlyCfg)
            .filter { u ->
                val root = useTreeRootIdent(u.tree)
                root != null && root != "self" && root != "super" && root !in parentLocalMods
            }.map { it.copy(vis = Visibility.Inherited) }
    val groupedSplitMods = linkedMapOf<String, MutableList<Item.Mod>>()
    for (modItem in splitMods) {
        val rawMod = modItem.ident.toString().removePrefix("r#")
        val modKt = rustModuleIdentToKotlin(rawMod.replace('-', '_'))
        if (modKt.isEmpty()) continue
        val modPascal = rustTypeIdentToKotlin(rawMod)
        groupedSplitMods.getOrPut("$modKt/$modPascal.kt") { mutableListOf() }.add(modItem)
    }
    val out = mutableListOf<CompanionInteropFile>()
    for ((targetRelPath, modGroup) in groupedSplitMods) {
        val primaryMod = modGroup.first()
        val rawMod = primaryMod.ident.toString().removePrefix("r#")
        val modKt = rustModuleIdentToKotlin(rawMod.replace('-', '_'))
        val childRelDir =
            if (headerContext.currentRelativeSourceDir.isNullOrEmpty()) {
                rawMod
            } else {
                "${headerContext.currentRelativeSourceDir}/$rawMod"
            }
        val childHeader =
            headerContext.copy(
                packageName = "$basePkg.$modKt",
                projectNamespace = effectiveNs,
                currentRelativeSourceDir = childRelDir,
                isModuleRootSource = false,
            )
        val (mergedAttrs, childItems) = mergeDuplicateSplitInlineMods(modGroup, parentUses)
        val rawChildFile =
            File(
                shebang = null,
                attrs = mergedAttrs,
                items = childItems,
            )
        val prevLayout = TranslationSession.sourceLayoutFile
        val prevRelDir = TranslationSession.currentRelativeSourceDir
        TranslationSession.sourceLayoutFile = rawChildFile
        TranslationSession.currentRelativeSourceDir = childRelDir
        try {
            val childEmitter =
                KotlinJ2KEmitter(
                    source = source,
                    originalFile = rawChildFile,
                    file = selectParentItemsExcludingSplitInlineMods(rawChildFile, childHeader),
                    trivia = trivia.withoutTrailingComments(),
                    macroDefs = macroDefs,
                    macroWrapperSpansByTargetOffset = macroWrapperSpansByTargetOffset,
                    headerContext = childHeader,
                )
            val childText = childEmitter.emitFile()
            if (hasEmittedKotlinDeclarations(childText)) {
                out +=
                    CompanionInteropFile(
                        relativePath = targetRelPath,
                        kind = CompanionInteropKind.CommonKotlinSupport,
                        content = childText,
                    )
            }
            for (nested in childEmitter.emitCompanionFiles()) {
                val nestedRel =
                    if (nested.kind is CompanionInteropKind.CommonKotlinSupport) {
                        "$modKt/${nested.relativePath}"
                    } else {
                        nested.relativePath
                    }
                out += nested.copy(relativePath = nestedRel)
            }
            for (nestedSplit in emitSplitInlineModCompanions(
                expandedFile = rawChildFile,
                source = source,
                trivia = trivia.withoutTrailingComments(),
                macroDefs = macroDefs,
                macroWrapperSpansByTargetOffset = macroWrapperSpansByTargetOffset,
                headerContext = childHeader,
            )) {
                out += nestedSplit.copy(relativePath = "$modKt/${nestedSplit.relativePath}")
            }
        } finally {
            TranslationSession.sourceLayoutFile = prevLayout
            TranslationSession.currentRelativeSourceDir = prevRelDir
        }
    }
    return out
}

internal data class ModuleRootChunkTranslation(
    val fileName: String,
    val text: String,
    val itemCount: Int,
    val companionFiles: List<CompanionInteropFile> = emptyList(),
)

internal fun hasEmittedKotlinDeclarations(kotlinText: String): Boolean {
    var inBlockComment = false
    for (rawLine in kotlinText.lineSequence()) {
        var line = rawLine.trim()
        if (inBlockComment) {
            val endIdx = line.indexOf("*/")
            if (endIdx < 0) continue
            inBlockComment = false
            line = line.substring(endIdx + 2).trim()
        }
        while (line.startsWith("/*")) {
            val endIdx = line.indexOf("*/", startIndex = 2)
            if (endIdx < 0) {
                inBlockComment = true
                line = ""
                break
            }
            line = line.substring(endIdx + 2).trim()
        }
        if (line.isEmpty() ||
            line.startsWith("//") ||
            line.startsWith("@file:") ||
            line.startsWith("package ") ||
            line.startsWith("import ")
        ) {
            continue
        }
        return true
    }
    return false
}

private fun isNonEmittingModuleRootMacro(item: Item.Macro): Boolean {
    if (item.mac.path.isIdent("macro_rules")) {
        return item.attrs.none { it.path().isIdent("macro_export") }
    }
    val segs = item.mac.path.segments
    val pathText = segs.toList().joinToString("::") { it.ident.toString() }
    return pathText == "include"
}

private fun extractChunkOwnerTypeName(selfTy: SynType, generics: Generics): String? {
    val typeParams =
        generics.params
            .toList()
            .mapNotNull { (it as? GenericParam.TypeParam)?.ident?.toString()?.let(::rustTypeIdentToKotlin) }
            .toSet()
    return when (val u = unwrapSynType(selfTy)) {
        is SynType.Reference -> extractChunkOwnerTypeName(u.elem, generics)
        is SynType.Path -> {
            if (u.qself != null) return null
            val segs = u.path.segments.toList()
            if (segs.size != 1) return null
            val name = rustTypeIdentToKotlin(segs[0].ident.toString())
            if (name in typeParams) null else name
        }
        else -> null
    }
}


internal fun translateModuleRootIntoFocusedChunks(
    source: String,
    modulePascalName: String,
    headerContext: FileHeaderContext,
): List<ModuleRootChunkTranslation> =
    TranslationSessionGuard.withHeaderContext(headerContext, preserveSharedModuleFile = true) { sessionAllowStdlibDestructors ->
        val recognized = recognizeRust(source)
        if (isDocOnlyCfgAttribute(recognized.file.attrs)) return@withHeaderContext emptyList()
        val allowStdlibDestructors = sessionAllowStdlibDestructors
        validateSupportedRustSemantics(recognized.file, allowStdlibDestructors = allowStdlibDestructors)
        val trivia = SourceTrivia.collect(recognized.source, recognized.tokens)
        val macroExpansion = expandFileMacroRules(recognized.file, headerContext.crateMacroSources)
        val expandedFile = flattenPrivateDefinitionInlineMods(macroExpansion.file)
        if (TranslationSession.sourceLayoutFile == null) TranslationSession.sourceLayoutFile = expandedFile

        val substantiveItems =
            expandedFile.items.filter { item ->
                if (itemHasDocOnlyCfg(item)) return@filter false
                when (item) {
                    is Item.ExternCrate, is Item.Use -> false
                    is Item.Mod -> item.content is ModContent.Inline
                    is Item.Macro -> !isNonEmittingModuleRootMacro(item)
                    else -> true
                }
            }
        if (substantiveItems.isEmpty()) {
            return@withHeaderContext emptyList()
        }

        val fullInspector = CrateSymbolInspector(headerContext.selfCrateAliases).also { it.visitFile(expandedFile) }
        val implsByOwner = linkedMapOf<String, MutableList<Item.Impl>>()
        val standaloneImpls = mutableListOf<Item.Impl>()
        for (item in substantiveItems) {
            if (item is Item.Impl) {
                val owner = extractChunkOwnerTypeName(item.selfType, item.generics)
                if (owner != null && owner in fullInspector.attachableTypeNames) {
                    implsByOwner.getOrPut(owner) { mutableListOf() }.add(item)
                } else {
                    standaloneImpls += item
                }
            }
        }

        val typeBucket = mutableListOf<Item>()
        val structOwnerGroups = mutableListOf<List<Item>>()
        val constBucket = mutableListOf<Item>()
        val fnBucket = mutableListOf<Item>()
        val consumedImplOwners = hashSetOf<String>()

        for (item in substantiveItems) {
            when (item) {
                is Item.Impl -> {}
                is Item.ItemType, is Item.Trait, is Item.TraitAlias, is Item.Mod -> {
                    typeBucket += item
                }
                is Item.Enum -> {
                    val ktName = rustTypeIdentToKotlin(item.ident.toString())
                    typeBucket += item
                    if (consumedImplOwners.add(ktName)) {
                        implsByOwner[ktName]?.let { typeBucket.addAll(it) }
                    }
                }
                is Item.Struct -> {
                    val ktName = rustTypeIdentToKotlin(item.ident.toString())
                    val group = mutableListOf<Item>(item)
                    if (consumedImplOwners.add(ktName)) {
                        implsByOwner[ktName]?.let { group.addAll(it) }
                    }
                    structOwnerGroups += group
                }
                is Item.Union -> {
                    val ktName = rustTypeIdentToKotlin(item.ident.toString())
                    val group = mutableListOf<Item>(item)
                    if (consumedImplOwners.add(ktName)) {
                        implsByOwner[ktName]?.let { group.addAll(it) }
                    }
                    structOwnerGroups += group
                }
                is Item.Const, is Item.Static -> {
                    constBucket += item
                }
                else -> {
                    fnBucket += item
                }
            }
        }
        fnBucket.addAll(standaloneImpls)

        val plannedChunks = mutableListOf<Pair<String, List<Item>>>()
        if (typeBucket.isNotEmpty()) {
            plannedChunks += "${modulePascalName}Types.kt" to typeBucket
        }
        ModuleRootChunkBucket.Structs.appendGroups(plannedChunks, modulePascalName, structOwnerGroups)
        if (constBucket.isNotEmpty()) {
            val dedupedConstsByKtName = linkedMapOf<String, Item>()
            for (cItem in constBucket) {
                val (rawId, isPub) =
                    when (cItem) {
                        is Item.Const -> cItem.ident.toString() to (cItem.vis is io.github.kotlinmania.syn.Visibility.Public)
                        is Item.Static -> cItem.ident.toString() to (cItem.vis is io.github.kotlinmania.syn.Visibility.Public)
                        else -> null to false
                    }
                if (rawId != null) {
                    val cleanRaw = rawId.removePrefix("r#")
                    val ktId = fullInspector.declaredConstNames[cleanRaw] ?: KotlinNamingPolicy.Default.toConstIdentifier(rawId)
                    val existing = dedupedConstsByKtName[ktId]
                    val existingPub =
                        when (existing) {
                            is Item.Const -> existing.vis is io.github.kotlinmania.syn.Visibility.Public
                            is Item.Static -> existing.vis is io.github.kotlinmania.syn.Visibility.Public
                            else -> false
                        }
                    if (existing == null || (isPub && !existingPub)) {
                        dedupedConstsByKtName[ktId] = cItem
                    }
                }
            }
            val sortedConsts = topologicallySortConstItems(dedupedConstsByKtName.values.toList())
            ModuleRootChunkBucket.Constants.appendItems(plannedChunks, modulePascalName, sortedConsts)
        }
        ModuleRootChunkBucket.Functions.appendItems(plannedChunks, modulePascalName, fnBucket)

        TranslationSession.sharedModuleFile = expandedFile
        val chunkHeader =
            headerContext.copy(
                portLintSourcePath = null,
                portLintDirective = "none",
                isModuleRootSource = true,
                moduleDeclaredTypeNames = fullInspector.declaredTypeNames.toSet(),
            )
        val sharedDeclaredTypeNames = fullInspector.declaredTypeNames.toMutableSet()
        val sharedSamNamesByShape = linkedMapOf<String, String>()
        val sharedSamDefaultsByName = linkedMapOf<String, String>()
        var latestEmitter: KotlinJ2KEmitter? = null
        val sharedFfiCollector =
            FfiInteropCollector(
                source = recognized.source,
                headerContext = chunkHeader,
                resolveNumericKind = { ktType -> latestEmitter?.resolveNumericKind(ktType) },
                resolveTypeAliasTarget = { ktType ->
                    val simple = ktType.substringAfterLast('.').removeSuffix("?")
                    fullInspector.typeAliasTargets[ktType]
                        ?: fullInspector.typeAliasTargets[simple]
                        ?: fullInspector.typeAliasTargets[rustTypeIdentToKotlin(simple)]
                },
            )
        fun headerForChunk(chunkFileName: String): FileHeaderContext =
            if (chunkHeader.testClassName != null) {
                val base = chunkFileName.removeSuffix(".kt")
                chunkHeader.copy(testClassName = if (base.endsWith("Tests")) base else "${base}Tests")
            } else {
                chunkHeader
            }
        val emittedChunks = mutableListOf<ModuleRootChunkTranslation>()
        for ((chunkFileName, chunkItems) in plannedChunks) {
            val chunkAst = File(shebang = null, attrs = emptyList(), items = chunkItems)
            val emitter =
                KotlinJ2KEmitter(
                    source = recognized.source,
                    originalFile = chunkAst,
                    file = chunkAst,
                    trivia = trivia,
                    macroDefs = macroExpansion.definitions,
                    macroWrapperSpansByTargetOffset = macroExpansion.macroWrapperSpansByTargetOffset,
                    headerContext = headerForChunk(chunkFileName),
                    sharedFfiCollector = sharedFfiCollector,
                    sharedDeclaredTypeNames = sharedDeclaredTypeNames,
                    sharedSamNamesByShape = sharedSamNamesByShape,
                    sharedSamDefaultsByName = sharedSamDefaultsByName,
                )
            latestEmitter = emitter
            val text = emitter.emitFile()
            if (hasEmittedKotlinDeclarations(text)) {
                emittedChunks +=
                    ModuleRootChunkTranslation(
                        fileName = chunkFileName,
                        text = text,
                        itemCount = chunkItems.size,
                        companionFiles = emptyList(),
                    )
            }
        }
        val companions = sharedFfiCollector.emitCompanionFiles()
        if (companions.isNotEmpty()) {
            if (emittedChunks.isNotEmpty()) {
                val lastIdx = emittedChunks.lastIndex
                emittedChunks[lastIdx] = emittedChunks[lastIdx].copy(companionFiles = companions)
            } else if (plannedChunks.isNotEmpty()) {
                val (firstChunkName, firstChunkItems) = plannedChunks.first()
                val chunkAst = File(shebang = null, attrs = emptyList(), items = firstChunkItems)
                val emitter =
                    KotlinJ2KEmitter(
                        source = recognized.source,
                        originalFile = chunkAst,
                        file = chunkAst,
                        trivia = trivia,
                        macroDefs = macroExpansion.definitions,
                        macroWrapperSpansByTargetOffset = macroExpansion.macroWrapperSpansByTargetOffset,
                        headerContext = headerForChunk(firstChunkName),
                    )
                emittedChunks +=
                    ModuleRootChunkTranslation(
                        fileName = firstChunkName,
                        text = emitter.emitFile(),
                        itemCount = firstChunkItems.size,
                        companionFiles = companions,
                    )
            }
        }
        emittedChunks
    }
