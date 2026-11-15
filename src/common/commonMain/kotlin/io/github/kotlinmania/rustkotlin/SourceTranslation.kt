package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.procmacro2.Span
import io.github.kotlinmania.quote.ToTokens
import io.github.kotlinmania.rustkotlin.reused.starlark.ModuleScopeBuilder
import io.github.kotlinmania.syn.Attribute
import io.github.kotlinmania.syn.Expr
import io.github.kotlinmania.syn.File
import io.github.kotlinmania.syn.FnArg
import io.github.kotlinmania.syn.ForeignItem
import io.github.kotlinmania.syn.GenericArgument
import io.github.kotlinmania.syn.GenericParam
import io.github.kotlinmania.syn.Generics
import io.github.kotlinmania.syn.ImplItem
import io.github.kotlinmania.syn.Item
import io.github.kotlinmania.syn.ModContent
import io.github.kotlinmania.syn.Path
import io.github.kotlinmania.syn.PathArguments
import io.github.kotlinmania.syn.PointerMutability
import io.github.kotlinmania.syn.SynType
import io.github.kotlinmania.syn.TraitItem
import io.github.kotlinmania.syn.TypeParamBound
import io.github.kotlinmania.syn.UseTree
import io.github.kotlinmania.syn.Visibility
import io.github.kotlinmania.syn.WherePredicate
import io.github.kotlinmania.syn.gen.Visit
import io.github.kotlinmania.syn.span

internal fun translateRustSourceToKotlin(
    recognized: RecognizedRust,
    headerContext: FileHeaderContext = FileHeaderContext(),
): String = translateRustSourceToKotlinArtifacts(recognized, headerContext).first

internal fun translateRustSourceToKotlinArtifacts(
    recognized: RecognizedRust,
    headerContext: FileHeaderContext = FileHeaderContext(),
): Pair<String, List<CompanionInteropFile>> {
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
    try {
        if (isDocOnlyCfgAttribute(recognized.file.attrs)) return "" to emptyList()
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
        validateSupportedRustSemantics(recognized.file, allowStdlibDestructors = allowStdlibDestructors)
        val trivia = SourceTrivia.collect(recognized.source, recognized.tokens)
        val macroExpansion = expandFileMacroRules(recognized.file, headerContext.crateMacroSources)
        val flattenedFile = flattenPrivateDefinitionInlineMods(macroExpansion.file)
        if (headerContext.isDocExample) {
            TranslationSession.sourceLayoutFile = flattenedFile
        } else if (TranslationSession.sourceLayoutFile == null) {
            TranslationSession.sourceLayoutFile = flattenedFile
        }
        val emitter =
            KotlinJ2KEmitter(
                source = recognized.source,
                originalFile = recognized.file,
                file = selectParentItemsExcludingSplitInlineMods(flattenedFile, headerContext),
                trivia = trivia,
                macroDefs = macroExpansion.definitions,
                macroWrapperSpansByTargetOffset = macroExpansion.macroWrapperSpansByTargetOffset,
                headerContext = headerContext,
            )
        val parentText = emitter.emitFile()
        val splitModCompanions =
            emitSplitInlineModCompanions(
                expandedFile = flattenedFile,
                source = recognized.source,
                trivia = trivia,
                macroDefs = macroExpansion.definitions,
                macroWrapperSpansByTargetOffset = macroExpansion.macroWrapperSpansByTargetOffset,
                headerContext = headerContext,
            )
        return parentText to (emitter.emitCompanionFiles() + splitModCompanions)
    } finally {
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

private fun isSealedMarkerInlineMod(item: Item.Mod): Boolean {
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

private fun selectSplitCandidateInlineMods(file: File, headerContext: FileHeaderContext): List<Item.Mod> {
    if (headerContext.packageName.isNullOrBlank()) return emptyList()
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
    val parentNonModItems = file.items.filterNot { it is Item.Mod }
    seenNames.addAll(collectDirectDeclaredItemCollisionNames(parentNonModItems))
    var hasCollision = false
    for (modItem in candidates) {
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

private fun flattenPrivateDefinitionInlineMods(file: File): File {
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

private fun selectParentItemsExcludingSplitInlineMods(file: File, headerContext: FileHeaderContext): File {
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

private fun emitSplitInlineModCompanions(
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
    val out = mutableListOf<CompanionInteropFile>()
    for (modItem in splitMods) {
        val rawMod = modItem.ident.toString().removePrefix("r#")
        val modKt = rustModuleIdentToKotlin(rawMod.replace('-', '_'))
        if (modKt.isEmpty()) continue
        val modPascal = rustTypeIdentToKotlin(rawMod)
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
        val inlineItems = (modItem.content as ModContent.Inline).items
        val hasSuperImport =
            inlineItems.any { child ->
                child is Item.Use && !itemHasDocOnlyCfg(child) && useTreeStartsWithSuper(child.tree)
            }
        val childItems = if (hasSuperImport && parentUses.isNotEmpty()) parentUses + inlineItems else inlineItems
        val rawChildFile =
            File(
                shebang = null,
                attrs = modItem.attrs,
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
                    trivia = trivia,
                    macroDefs = macroDefs,
                    macroWrapperSpansByTargetOffset = macroWrapperSpansByTargetOffset,
                    headerContext = childHeader,
                )
            val childText = childEmitter.emitFile()
            if (hasEmittedKotlinDeclarations(childText)) {
                out +=
                    CompanionInteropFile(
                        relativePath = "$modKt/$modPascal.kt",
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
                trivia = trivia,
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
): List<ModuleRootChunkTranslation> {
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
    try {
        val recognized = recognizeRust(source)
        if (isDocOnlyCfgAttribute(recognized.file.attrs)) return emptyList()
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
            return emptyList()
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
        if (structOwnerGroups.isNotEmpty()) {
            val structChunks = structOwnerGroups.chunked(250)
            structChunks.forEachIndexed { idx, groups ->
                val suffix = if (idx == 0) "Structs.kt" else "Structs${idx + 1}.kt"
                plannedChunks += "$modulePascalName$suffix" to groups.flatten()
            }
        }
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
            val constChunks = sortedConsts.chunked(1500)
            constChunks.forEachIndexed { idx, items ->
                val suffix = if (idx == 0) "Constants.kt" else "Constants${idx + 1}.kt"
                plannedChunks += "$modulePascalName$suffix" to items
            }
        }
        if (fnBucket.isNotEmpty()) {
            val fnChunks = fnBucket.chunked(500)
            fnChunks.forEachIndexed { idx, items ->
                val suffix = if (idx == 0) "Functions.kt" else "Functions${idx + 1}.kt"
                plannedChunks += "$modulePascalName$suffix" to items
            }
        }

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
                    headerContext = chunkHeader,
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
                        headerContext = chunkHeader,
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
        return emittedChunks
    } finally {
        TranslationSession.sharedModuleFile = prevSharedMod
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

private val UNSIGNED_ARRAY_IDENTS =
    listOf(
        "UByteArray",
        "UShortArray",
        "UIntArray",
        "ULongArray",
        "ubyteArrayOf",
        "ushortArrayOf",
        "uintArrayOf",
        "ulongArrayOf",
    )

internal class KotlinJ2KEmitter(
    internal val source: String,
    internal val originalFile: File,
    internal val file: File,
    internal val trivia: SourceTrivia,
    internal val macroDefs: Map<String, MacroRulesDef>,
    internal val macroWrapperSpansByTargetOffset: Map<Int, List<Span>>,
    internal val headerContext: FileHeaderContext,
    internal val sharedFfiCollector: FfiInteropCollector? = null,
    internal val sharedDeclaredTypeNames: MutableSet<String>? = null,
    internal val sharedSamNamesByShape: MutableMap<String, String>? = null,
    internal val sharedSamDefaultsByName: MutableMap<String, String>? = null,
) {
    internal val inspector =
        CrateSymbolInspector(headerContext.selfCrateAliases).also { sym ->
            sym.declaredTypeNames.addAll(headerContext.moduleDeclaredTypeNames)
            if (sharedDeclaredTypeNames != null) {
                sym.declaredTypeNames.addAll(sharedDeclaredTypeNames)
            }
            val sharedModFile = TranslationSession.sharedModuleFile
            if (sharedModFile != null) {
                val sharedInsp = CrateSymbolInspector(headerContext.selfCrateAliases).also { it.visitFile(sharedModFile) }
                sym.declaredTypeNames.addAll(sharedInsp.declaredTypeNames)
                sym.unitStructNames.addAll(sharedInsp.unitStructNames)
                sym.unionTypeNames.addAll(sharedInsp.unionTypeNames)
                sym.typeAliasTargets.putAll(sharedInsp.typeAliasTargets)
                sym.declaredConstNames.putAll(sharedInsp.declaredConstNames)
                sym.declaredConstTypes.putAll(sharedInsp.declaredConstTypes)
                sym.structFieldNamesByType.putAll(sharedInsp.structFieldNamesByType)
                sym.structFieldSynTypesByType.putAll(sharedInsp.structFieldSynTypesByType)
                sym.declaredTopLevelFnRawNames.addAll(sharedInsp.declaredTopLevelFnRawNames)
                sym.externFnKotlinNames.addAll(sharedInsp.externFnKotlinNames)
                sym.topLevelFnReturnSynTypes.putAll(sharedInsp.topLevelFnReturnSynTypes)
                sym.implMethodReturnSynTypes.putAll(sharedInsp.implMethodReturnSynTypes)
                sym.localInlineModuleNames.addAll(sharedInsp.localInlineModuleNames)
                sym.localExternalModuleNames.addAll(sharedInsp.localExternalModuleNames)
                sym.allUseItems.addAll(sharedInsp.allUseItems)
                sym.enumVariantSimpleNames.addAll(sharedInsp.enumVariantSimpleNames)
                sym.importedEnumVariantSimpleNames.addAll(sharedInsp.importedEnumVariantSimpleNames)
                sym.ffiReferencedTypeNames.addAll(sharedInsp.ffiReferencedTypeNames)
                sym.referencedUnqualifiedTypeIdents.addAll(sharedInsp.referencedUnqualifiedTypeIdents)
                sym.referencedPathPrefixTypeIdents.addAll(sharedInsp.referencedPathPrefixTypeIdents)
                sym.declaredTraitsByName.putAll(sharedInsp.declaredTraitsByName)
                sym.tupleStructArityByType.putAll(sharedInsp.tupleStructArityByType)
                sym.tupleEnumVariantArity.putAll(sharedInsp.tupleEnumVariantArity)
                sym.enumTypeParamsByVariant.putAll(sharedInsp.enumTypeParamsByVariant)
                sym.enumVariantFieldTypes.putAll(sharedInsp.enumVariantFieldTypes)
                sym.selfParameterizedTraits.addAll(sharedInsp.selfParameterizedTraits)
                sym.traitSupertraits.putAll(sharedInsp.traitSupertraits)
                sym.traitDefaultlessAssocTypes.putAll(sharedInsp.traitDefaultlessAssocTypes)
                sym.traitResultMethods.putAll(sharedInsp.traitResultMethods)
                sym.instanceMethodsByType.putAll(sharedInsp.instanceMethodsByType)
                sym.companionMethodsByType.putAll(sharedInsp.companionMethodsByType)
                if (sharedInsp.hasImportedEnumGlob) sym.hasImportedEnumGlob = true
            }
            sym.visitFile(file)
            sym.selfParameterizedTraits.addAll(headerContext.crateSelfParameterizedTraits)
            var selfClosedChanged = true
            while (selfClosedChanged) {
                selfClosedChanged = false
                for ((ktTrait, superNames) in sym.traitSupertraits) {
                    if (ktTrait in sym.selfParameterizedTraits) continue
                    if (superNames.any { it in sym.selfParameterizedTraits || it in KNOWN_SELF_PARAMETERIZED_TRAITS }) {
                        sym.selfParameterizedTraits += ktTrait
                        selfClosedChanged = true
                    }
                }
            }
            sym.closeFfiReferencedTypeAliases()
            if (sharedModFile != null && sym.allUseItems.size > 1) {
                val deduped =
                    sym.allUseItems.distinctBy { useItem ->
                        val start = useItem.span().startOffset()
                        if (start > 0) "off:$start" else "tok:${useItem.toTokenStream()}"
                    }
                sym.allUseItems.clear()
                sym.allUseItems.addAll(deduped)
            }
            for ((rawConst, ktConst) in sym.declaredConstNames.map { it.key to it.value }) {
                if (ktConst in sym.declaredTypeNames) {
                    val disamb = KotlinNamingPolicy.Default.toConstIdentifier("${rawConst}_CONST")
                    sym.declaredConstNames[rawConst] = disamb
                    sym.declaredConstTypes[ktConst]?.let { cTy -> sym.declaredConstTypes[disamb] = cTy }
                }
            }
        }
    internal val ffiCollector =
        sharedFfiCollector
            ?: FfiInteropCollector(
                source = source,
                headerContext = headerContext,
                resolveNumericKind = { ktType -> resolveNumericKind(ktType) },
                resolveTypeAliasTarget = { ktType ->
                    val simple = ktType.substringAfterLast('.').removeSuffix("?")
                    inspector.typeAliasTargets[ktType]
                        ?: inspector.typeAliasTargets[simple]
                        ?: inspector.typeAliasTargets[rustTypeIdentToKotlin(simple)]
                },
            )
    internal val scopeBuilder = ModuleScopeBuilder()
    internal val topLevelSignatureCounts = linkedMapOf<String, Int>()
    internal val emittedTriviaStartBytes = hashSetOf<Int>()
    internal val synthesizedSamDeclarations = linkedMapOf<String, String>()
    internal val synthesizedSamNamesByShape = sharedSamNamesByShape ?: linkedMapOf<String, String>()
    internal val synthesizedSamDefaultsByName = sharedSamDefaultsByName ?: linkedMapOf<String, String>()
    internal val importedSimpleNameToPkg = linkedMapOf<String, String>()
    internal val extraTypePathImports = linkedSetOf<String>()
    internal val standaloneTraitImplKeys = linkedSetOf<Pair<String, String>>()
    internal val witnessedSuppressedTraitNames = linkedSetOf<String>()
    internal var currentImplAssocTypes: Map<String, SynType> = emptyMap()
    internal var currentTraitAssocParamNames: Set<String> = emptySet()
    internal var currentTypeParamWitnessCalls: Map<Pair<String, String>, String> = emptyMap()
    internal var currentAttachedImplTraitPath: Path? = null
    internal var currentAttachedImplIsRefSelf: Boolean = false
    internal var currentStandaloneImplTraitPath: Path? = null
    internal var currentStandaloneImplSelfSynType: SynType? = null
    internal var currentBoundedTypeName: String? = null
    internal var substitutedTypeParamTypes: Map<String, String> = emptyMap()
    internal var currentIntoSubstitutedTypeParams: Set<String> = emptySet()
    internal var currentIntoSubstitutedParams: Set<String> = emptySet()
    internal var activeGuardBindingSubstitutions: Map<String, String> = emptyMap()
    internal var activeTypeParamNames: Set<String> = emptySet()
    internal var currentSamNameHint: String? = null
    internal var currentEnclosingFunctionName: String? = null
    internal var currentEnclosingReturnRenderedType: String? = null
    internal var currentEnclosingReturnSynType: SynType? = null
    internal var currentExpectedParseTargetType: String? = null
    internal var currentMutableListLocals: Set<String> = emptySet()
    internal var currentDequeLocals: Set<String> = emptySet()
    internal var currentIterableLocals: Set<String> = emptySet()
    internal var currentTypedLocals: Map<String, String> = emptyMap()
    internal var currentSourceParameterTypes: Map<String, SynType> = emptyMap()
    internal var currentBorrowedPlaceLocals: Set<String> = emptySet()
    internal var currentAddressableLocals: Map<String, String> = emptyMap()
    internal var currentAddressableArrayElementTypes: Map<String, SynType> = emptyMap()
    internal var currentWitnessedTraitBounds: Map<String, Set<String>> = emptyMap()
    internal var currentTypeParamTraitBounds: Map<String, Set<String>> = emptyMap()
    internal var currentStructOverrideMethodNames: Set<String> = emptySet()
    internal var currentAttachedImplIsObjectOwner: Boolean = false
    internal var inTraitDefaultMethodBody: Boolean = false
    internal var currentTraitMethodRawNames: Set<String> = emptySet()
    internal var currentModPathPrefix: List<String> = emptyList()
    internal var currentHasherStateParam: String? = null
    internal var currentNullableLocals: Set<String> = emptySet()
    internal var currentSmartCastNonNullLocals: Set<String> = emptySet()
    internal var currentExpectedNumericKind: NumericKind? = null
    internal var currentFunctionParamNumericKinds: Map<String, List<NumericKind?>> = emptyMap()
    internal var currentOmittedTestIdents: Set<String> = emptySet()
    internal var currentClosureLabel: String? = null
    internal var currentClosureTupleElemType: String? = null
    internal var closureLabelCounter: Int = 0
    internal var inExternAbiSignature: Boolean = false
    internal var inExternParamType: Boolean = false
    internal var usesCurrentThreadToken: Boolean = false
    internal var usesQuoteUnderlyingSpan: Boolean = false
    internal val aliasedUnderlyingSelfCalls: MutableMap<String, String> = linkedMapOf()
    internal var currentReceiverIsSharedBorrow: Boolean = false
    internal var currentReceiverIsMutBorrow: Boolean = false
    internal var usesDebugStruct: Boolean = false
    internal var usesDebugTuple: Boolean = false
    internal var usesU128: Boolean = false
    internal var usesRustChar: Boolean = false
    internal var usesSourceResult: Boolean = false
    internal var usesSourceResultResidual: Boolean = false
    internal var currentEnclosingReturnsFmtResult: Boolean = false
    internal var currentTailExpectsUnitResultOk: Boolean = false
    internal var usesI128: Boolean = false
    internal var usesRustAny: Boolean = false
    internal var suppressTraitAssocPlaceholders: Boolean = false
    internal var activeConstParamNames: Set<String> = emptySet()

    internal fun effectiveStructKotlinName(baseKtName: String): String =
        if (baseKtName == "String" && headerContext.packageName?.endsWith(".alloc.string") == true) {
            "RustAllocString"
        } else {
            baseKtName
        }
    internal val computedStructLayoutCache: MutableMap<String, ComputedStructLayout?> = linkedMapOf()
    internal val requiredTypeLayoutParamsCache: MutableMap<String, List<Pair<String, String>>> = linkedMapOf()
    internal val inferExprRenderedTypeCache: MutableMap<InferExprCacheKey, String?> = hashMapOf()
    internal val activeCfgProfile: CfgTargetProfile
        get() = headerContext.cfgTargetProfile ?: TranslationSession.cfgTargetProfile

    internal fun emitCompanionFiles(): List<CompanionInteropFile> = ffiCollector.emitCompanionFiles()

    internal fun renderKDocFromAttributes(attrs: List<Attribute>, indent: String): String =
        io.github.kotlinmania.rustkotlin.renderKDocFromAttributes(
            attrs = attrs,
            indent = indent,
            includeDocExampleAnnotations = headerContext.isDocExample,
        )

    internal fun emitFile(): String {
        val out = StringBuilder()

        // Partition impl blocks into those belonging to a local type declared in this file
        // vs standalone/external impl blocks, and hoist all `use` imports to the top.
        val localTypeImpls = linkedMapOf<String, MutableList<Item.Impl>>()
        val standaloneItems = mutableListOf<Item>()

        currentOmittedTestIdents = computeTransitivelyOmittedQuickcheckOrRandIdents(file.items, emptySet())
        val sharedModFile = TranslationSession.sharedModuleFile
        if (sharedModFile != null) {
            for (sharedItem in sharedModFile.items) {
                if (itemHasDocOnlyCfg(sharedItem)) continue
                if (sharedItem is Item.Use) {
                    registerUseItemInScope(sharedItem)
                }
            }
        }
        for (item in file.items) {
            if (itemHasDocOnlyCfg(item)) continue
            if (item is Item.Use) {
                if (sharedModFile == null || sharedModFile.items.none { it === item }) {
                    registerUseItemInScope(item)
                }
            }
        }
        for (item in file.items) {
            if (itemHasDocOnlyCfg(item) || item is Item.Use) continue
            if (item is Item.Impl && !shouldOmitItemForUnportedQuickcheckOrRand(item)) {
                val ownerType = extractDirectOwnerTypeName(item.selfType, item.generics)
                if (ownerType != null && ownerType in inspector.attachableTypeNames) {
                    localTypeImpls.getOrPut(ownerType) { mutableListOf() }.add(item)
                    continue
                }
            }
            standaloneItems += item
        }

        val attachedBlanketImpls = mutableListOf<Item.Impl>()
        for (item in standaloneItems) {
            if (item is Item.Impl && item.traitPath != null) {
                val matchedOwners = matchBlanketLocalIteratorImplOwners(item, localTypeImpls)
                if (matchedOwners.isNotEmpty()) {
                    for (owner in matchedOwners) {
                        localTypeImpls.getOrPut(owner) { mutableListOf() }.add(item)
                    }
                    attachedBlanketImpls += item
                }
            }
        }
        if (attachedBlanketImpls.isNotEmpty()) {
            standaloneItems.removeAll(attachedBlanketImpls.toSet())
        }
        for (item in standaloneItems) {
            if (item is Item.Impl) {
                val tPath = item.traitPath?.path ?: continue
                val traitKt =
                    tPath.segments
                        .last()
                        ?.ident
                        ?.toString()
                        ?.let(::rustTypeIdentToKotlin) ?: continue
                val selfDesc = describeSynTypeForDisambiguation(item.selfType)
                if (traitKt.isNotEmpty() && selfDesc.isNotEmpty()) {
                    standaloneTraitImplKeys += (traitKt to selfDesc)
                }
            }
        }
        val sortedStandalone = topologicallySortConstItems(standaloneItems)
        if (sortedStandalone !== standaloneItems) {
            standaloneItems.clear()
            standaloneItems.addAll(sortedStandalone)
        }
        val topLevelFnParamKinds = linkedMapOf<String, List<NumericKind?>?>()
        val fnScanItems = TranslationSession.sharedModuleFile?.items ?: standaloneItems
        for (item in fnScanItems) {
            if (item is Item.Fn && !itemHasDocOnlyCfg(item)) {
                val extracted = extractFnParamNumericKinds(item, selfTypeName = null)
                if (extracted != null) {
                    val (fnName, kinds) = extracted
                    topLevelFnParamKinds[fnName] = if (fnName in topLevelFnParamKinds) null else kinds
                    val arityKey = "$fnName#${kinds.size}"
                    topLevelFnParamKinds[arityKey] = if (arityKey in topLevelFnParamKinds) null else kinds
                }
            } else if (item is Item.ForeignMod && !itemHasDocOnlyCfg(item)) {
                val prevExtern = inExternAbiSignature
                inExternAbiSignature = true
                try {
                    for (foreign in item.items) {
                        if (foreign is ForeignItem.Fn && !foreignItemHasDocOnlyCfg(foreign)) {
                            val extracted = extractSigParamNumericKinds(foreign.sig, selfTypeName = null)
                            if (extracted != null) {
                                val (fnName, kinds) = extracted
                                topLevelFnParamKinds[fnName] = if (fnName in topLevelFnParamKinds) null else kinds
                                val arityKey = "$fnName#${kinds.size}"
                                topLevelFnParamKinds[arityKey] = if (arityKey in topLevelFnParamKinds) null else kinds
                            }
                        }
                    }
                } finally {
                    inExternAbiSignature = prevExtern
                }
            }
        }
        val implScanItems = TranslationSession.sharedModuleFile?.items ?: file.items
        val implScanTraitByName = linkedMapOf<String, Item.Trait>()
        implScanTraitByName.putAll(inspector.declaredTraitsByName)
        for (item in implScanItems + file.items) {
            if (item is Item.Trait && !itemHasDocOnlyCfg(item)) {
                val tName = rustTypeIdentToKotlin(item.ident.toString())
                if (tName !in implScanTraitByName) {
                    implScanTraitByName[tName] = item
                }
            }
        }
        val implTraitsByOwner = linkedMapOf<String, MutableSet<String>>()
        for (item in implScanItems) {
            if (item is Item.Impl && item.generics.params.isEmpty() && !itemHasDocOnlyCfg(item)) {
                val ownerType = extractDirectOwnerTypeName(item.selfType, item.generics) ?: continue
                item.traitPath
                    ?.path
                    ?.segments
                    ?.toList()
                    ?.lastOrNull()
                    ?.ident
                    ?.toString()
                    ?.let(::rustTypeIdentToKotlin)
                    ?.let { implTraitsByOwner.getOrPut(ownerType) { linkedSetOf() }.add(it) }
                for (implItem in item.items) {
                    if (implItem is ImplItem.Fn && !implItemHasDocOnlyCfg(implItem)) {
                        val extracted = extractSigParamNumericKinds(implItem.sig, ownerType)
                        if (extracted != null) {
                            val (fnName, kinds) = extracted
                            val qualKey = "$ownerType.$fnName"
                            topLevelFnParamKinds[qualKey] = if (qualKey in topLevelFnParamKinds) null else kinds
                        }
                    }
                }
            }
        }
        for ((ownerType, traitNames) in implTraitsByOwner) {
            for (traitName in traitNames) {
                val traitItem = implScanTraitByName[traitName] ?: continue
                for (tItem in traitItem.items) {
                    if (tItem is TraitItem.Fn && !traitItemHasDocOnlyCfg(tItem)) {
                        val extracted = extractSigParamNumericKinds(tItem.sig, ownerType) ?: continue
                        val (fnName, kinds) = extracted
                        val qualKey = "$ownerType.$fnName"
                        if (qualKey !in topLevelFnParamKinds) {
                            topLevelFnParamKinds[qualKey] = kinds
                        }
                    }
                }
            }
        }
        for ((traitName, traitItem) in implScanTraitByName) {
            for (tItem in traitItem.items) {
                if (tItem is TraitItem.Fn && !traitItemHasDocOnlyCfg(tItem) && tItem.sig.inputs.none { it is FnArg.Receiver }) {
                    val extracted = extractSigParamNumericKinds(tItem.sig, traitName) ?: continue
                    val (fnName, kinds) = extracted
                    val qualKey = "$traitName.$fnName"
                    if (qualKey !in topLevelFnParamKinds) {
                        topLevelFnParamKinds[qualKey] = kinds
                    }
                }
            }
        }
        for (aliasName in inspector.typeAliasTargets.keys) {
            var cur = aliasName
            val seen = hashSetOf<String>()
            while (seen.add(cur) && seen.size <= 8) {
                val ty = inspector.typeAliasTargets[cur] ?: break
                val u = unwrapSynType(ty) as? SynType.Path ?: break
                if (u.qself != null) break
                val next =
                    u.path.segments
                        .toList()
                        .lastOrNull()
                        ?.ident
                        ?.toString()
                        ?.removePrefix("r#")
                        ?.let(::rustTypeIdentToKotlin) ?: break
                if (next == cur) break
                cur = next
            }
            if (cur != aliasName) {
                val ownerPrefix = "$cur."
                val snapshot = topLevelFnParamKinds.entries.map { it.key to it.value }
                for ((k, v) in snapshot) {
                    if (k.startsWith(ownerPrefix)) {
                        val aliasKey = "$aliasName.${k.removePrefix(ownerPrefix)}"
                        if (aliasKey !in topLevelFnParamKinds) {
                            topLevelFnParamKinds[aliasKey] = v
                        }
                    }
                }
            }
        }
        for ((crateFnKey, kindCodes) in headerContext.crateFnParamNumericKinds) {
            if (crateFnKey !in topLevelFnParamKinds) {
                topLevelFnParamKinds[crateFnKey] = kindCodes.map { numericKindFromScannerCode(it.takeIf(String::isNotEmpty)) }
            }
        }
        currentFunctionParamNumericKinds =
            topLevelFnParamKinds.entries
                .mapNotNull { (k, v) -> v?.let { k to it } }
                .toMap()

        val renderedItems = mutableListOf<String>()

        // Emit associated type aliases required for TypeParity if not already declared as types
        val nestedAllItems = sameFileNestedItems(file.items)
        val implementedOrImportedTraitNames =
            buildSet {
                for (it in nestedAllItems) {
                    when (it) {
                        is Item.Trait -> add(rustTypeIdentToKotlin(it.ident.toString()))
                        is Item.Impl ->
                            it.traitPath
                                ?.path
                                ?.segments
                                ?.last()
                                ?.ident
                                ?.toString()
                                ?.let { raw -> add(rustTypeIdentToKotlin(raw)) }
                        else -> {}
                    }
                }
                addAll(importedSimpleNameToPkg.keys)
            }
        for ((assocName, assocDecl) in inspector.associatedTypeDecls) {
            val ownerBindings =
                nestedAllItems.filterIsInstance<Item.Impl>().flatMap { implementation ->
                    implementation.items
                        .filterIsInstance<ImplItem.AssocType>()
                        .filter { it.ident.toString() == assocName }
                        .map { implementation.selfType }
                }
            if (ownerBindings.size > 1) continue
            if (assocName !in inspector.declaredTypeNames &&
                assocName !in implementedOrImportedTraitNames &&
                assocName !in KOTLIN_STDLIB_SHADOWED_IMPORTS
            ) {
                val targetType = renderType(assocDecl.ty, selfTypeName = null)
                if (targetType.isEmpty() || targetType == assocName) continue
                val candidateParams =
                    assocDecl.generics.params
                        .toList()
                        .mapNotNull { param ->
                            (param as? GenericParam.TypeParam)?.ident?.toString()?.let(::rustTypeIdentToKotlin)
                        }.distinct()
                val strippedTarget = targetType.trim().removeSuffix("?")
                if (strippedTarget in candidateParams || strippedTarget in activeTypeParamNames) continue
                inspector.declaredTypeNames += assocName
                val usedParams = candidateParams.filter { containsWholeIdentifier(targetType, it) }
                val typeParamSuffix = if (usedParams.isEmpty()) "" else "<${usedParams.joinToString(", ")}>"
                renderedItems += "public typealias $assocName$typeParamSuffix = $targetType"
            }
        }

        val pendingTopLevelTestFns = mutableListOf<String>()
        val topLevelTestSignatures = linkedMapOf<String, Int>()
        for (item in standaloneItems) {
            if (shouldOmitItemForUnportedQuickcheckOrRand(item)) {
                val omissionComment =
                    "// Omitted quickcheck/rand property test items pending quickcheck-kotlin / rand-kotlin port (Hermes Kanban t_19971811)."
                if (renderedItems.lastOrNull() != omissionComment) {
                    renderedItems += omissionComment
                }
                continue
            }
            val isTopLevelTestFn = item is Item.Fn && hasTestAttribute(item.attrs)
            val targetSignatures = if (isTopLevelTestFn) topLevelTestSignatures else topLevelSignatureCounts
            val rendered = renderItem(item, indent = "", localTypeImpls = localTypeImpls, scopeSignatures = targetSignatures)
            if (rendered.isNotBlank()) {
                val trimmed = rendered.trimEnd()
                if (isTopLevelTestFn && trimmed.lines().any { it.trimStart() == "@kotlin.test.Test" }) {
                    pendingTopLevelTestFns += trimmed
                } else {
                    renderedItems += trimmed
                }
            }
        }
        if (pendingTopLevelTestFns.isNotEmpty()) {
            val suiteCandidate = headerContext.testClassName ?: "InlineTests"
            val suiteName = allocateTopLevelTestSuiteClassName(suiteCandidate, topLevelSignatureCounts)
            val suiteBody = pendingTopLevelTestFns.joinToString("\n\n") { indentBlockByFourSpaces(it) }
            renderedItems += "public class $suiteName {\n$suiteBody\n}"
        }

        val activeCoreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
        val srcPathNorm = headerContext.portLintSourcePath?.replace('\\', '/')
        val isConstPtrSource =
            srcPathNorm?.endsWith("/const_ptr.rs") == true ||
                srcPathNorm == "const_ptr.rs" ||
                (
                    srcPathNorm == null &&
                        standaloneItems.any { item ->
                            item is Item.Impl &&
                                item.traitPath == null &&
                                unwrapSynType(item.selfType).let { u -> u is SynType.Ptr && u.mutability is PointerMutability.Const }
                        }
                )
        val isMutPtrSource =
            srcPathNorm?.endsWith("/mut_ptr.rs") == true ||
                srcPathNorm == "mut_ptr.rs" ||
                (
                    srcPathNorm == null &&
                        standaloneItems.any { item ->
                            item is Item.Impl &&
                                item.traitPath == null &&
                                unwrapSynType(item.selfType).let { u -> u is SynType.Ptr && u.mutability is PointerMutability.Mut }
                        }
                )
        if (headerContext.packageName == "$activeCoreNs.ptr") {
            if (isConstPtrSource && "ConstPtr" !in inspector.declaredTypeNames) {
                inspector.declaredTypeNames += "ConstPtr"
                renderedItems.add(0, CoreIntrinsicFfiArtifacts.renderConstPtrClassHeader())
            }
            if (isMutPtrSource && "MutPtr" !in inspector.declaredTypeNames) {
                inspector.declaredTypeNames += "MutPtr"
                renderedItems.add(0, CoreIntrinsicFfiArtifacts.renderMutPtrClassHeader())
            }
        } else if (headerContext.packageName?.startsWith("$activeCoreNs.ptr.") == true) {
            if (isConstPtrSource && "ConstPtr" !in inspector.declaredTypeNames) {
                CoreIntrinsicLowering.registerCoreImport(this, "$activeCoreNs.ptr.ConstPtr")
            }
            if (isMutPtrSource && "MutPtr" !in inspector.declaredTypeNames) {
                CoreIntrinsicLowering.registerCoreImport(this, "$activeCoreNs.ptr.MutPtr")
            }
        }

        if (usesCurrentThreadToken && topLevelSignatureCounts["currentThreadBoundToken()"] == null) {
            renderedItems.add(0, "internal expect fun currentThreadBoundToken(): Any")
        }
        if (usesDebugTuple && "DebugTuple" !in inspector.declaredTypeNames) {
            renderedItems.add(0, SYNTHESIZED_DEBUG_TUPLE_DECLARATION)
        }
        if (!headerContext.isDocExample && usesDebugStruct && "DebugStruct" !in inspector.declaredTypeNames) {
            renderedItems.add(0, SYNTHESIZED_DEBUG_STRUCT_DECLARATION)
        }
        val usesExternalRustLangCore =
            "core" in headerContext.crateNamespaces ||
                headerContext.packageName == activeCoreNs ||
                headerContext.packageName?.startsWith("$activeCoreNs.") == true
        val needsSynthesizedInt128 =
            !headerContext.isDocExample &&
                !usesExternalRustLangCore &&
                (usesU128 || usesI128) &&
                "U128" !in inspector.declaredTypeNames &&
                "I128" !in inspector.declaredTypeNames &&
                "U128" !in headerContext.crateSymbolSubpackages &&
                "I128" !in headerContext.crateSymbolSubpackages
        if (needsSynthesizedInt128) {
            TranslationSession.emittedInt128Support = true
            if (!headerContext.isBatchCrateOutput) {
                renderedItems.add(0, SYNTHESIZED_I128_DECLARATION)
                renderedItems.add(0, SYNTHESIZED_U128_DECLARATION)
            }
        }
        val needsSynthesizedRustAny =
            !headerContext.isDocExample &&
                !usesExternalRustLangCore &&
                usesRustAny &&
                "RustAny" !in inspector.declaredTypeNames &&
                "RustTypeId" !in inspector.declaredTypeNames &&
                "RustAny" !in headerContext.crateSymbolSubpackages &&
                "RustTypeId" !in headerContext.crateSymbolSubpackages
        if (needsSynthesizedRustAny) {
            TranslationSession.emittedRustAnySupport = true
            if (!headerContext.isBatchCrateOutput) {
                renderedItems.add(0, SYNTHESIZED_RUST_ANY_DECLARATION)
            }
        }

        val inlineExpects = ffiCollector.renderInlineExpectDeclarations()
        renderedItems.addAll(inlineExpects)

        val preMacroBodyEmpty =
            synthesizedSamDeclarations.isEmpty() &&
                renderedItems.isEmpty() &&
                ffiCollector.emitCompanionFiles().isEmpty()
        val standaloneMacros =
            if (headerContext.isDocExample) {
                StandaloneMacroEmission(emptyList(), emptySet())
            } else {
                val existingNames =
                    buildSet {
                        addAll(inspector.declaredTypeNames)
                        addAll(inspector.declaredConstNames.values)
                        addAll(topLevelFnParamKinds.keys)
                    }
                emitStandaloneMacroApproximations(
                    originalFile = originalFile,
                    existingBodyItemsEmpty = preMacroBodyEmpty && !headerContext.isModuleRootSource,
                    includeDocExampleAnnotations = headerContext.isDocExample,
                    declaredNames = existingNames,
                    rawAbiMode = headerContext.rawAbiMode,
                )
            }
        renderedItems.addAll(standaloneMacros.declarations)
        sharedDeclaredTypeNames?.addAll(inspector.declaredTypeNames)

        val allBodyItems =
            buildList {
                for (samDecl in synthesizedSamDeclarations.values) {
                    add(PhantomRawPointerTypeStripper.strip(samDecl))
                }
                for (itemDecl in renderedItems) {
                    add(PhantomRawPointerTypeStripper.strip(itemDecl))
                }
            }
        if (allBodyItems.isEmpty() && ffiCollector.emitCompanionFiles().isEmpty() && !headerContext.isDocExample && !headerContext.isModuleRootSource && !headerContext.isBatchCrateOutput) {
            val unemittedItems = describeUnemittedSubstantiveItems(originalFile = originalFile, expandedFile = file)
            if (unemittedItems.isNotEmpty()) {
                throw IllegalArgumentException(
                    "Unsupported macro-only Rust source: macro invocations (${unemittedItems.joinToString(", ")}) " +
                        "have no standalone Kotlin item translation",
                )
            }
        }

        val bodyTextForImports = stripKotlinCommentsAndStrings(allBodyItems.joinToString("\n\n"))
        val needsUnsignedArrayOptIn =
            UNSIGNED_ARRAY_IDENTS.any { containsWholeIdentifier(bodyTextForImports, it) }

        if (headerContext.portLintDirective != "none") {
            headerContext.portLintSourcePath?.let { relPath ->
                out
                    .append("// port-lint: ")
                    .append(headerContext.portLintDirective)
                    .append(' ')
                    .append(relPath)
                    .append('\n')
            }
        }
        if (needsUnsignedArrayOptIn) {
            out.append("@file:OptIn(ExperimentalUnsignedTypes::class)\n\n")
        }
        headerContext.packageName?.let { pkg ->
            out.append("package ").append(pkg).append("\n\n")
        }

        val useImportLines =
            inspector.allUseItems
                .flatMap { renderItemUse(it, indent = "", bodyTextForPubUseFilter = bodyTextForImports).lines() }
                .map { it.trim() }
                .filter { it.isNotEmpty() }

        val extraImports = mutableListOf<String>()
        if (usesSourceResult && usesTaggedCoreResult() && !hasDeclaredResultEnum()) {
            extraImports +=
                if ("Result" in inspector.declaredTypeNames) {
                    "import $activeCoreNs.result.Result as RustResult"
                } else {
                    "import $activeCoreNs.result.Result"
                }
        }
        val combinedExtraTypeImports = linkedSetOf<String>()
        combinedExtraTypeImports.addAll(extraTypePathImports)
        for (macroImp in standaloneMacros.extraImports) {
            val normalizedImp = if (macroImp.startsWith("import ")) macroImp else "import $macroImp"
            combinedExtraTypeImports += normalizedImp
        }
        val activeAllocNs = CoreIntrinsicLowering.resolveAllocNamespace(headerContext)
        val autoCoreSymbols =
            buildList {
                add("ConstPtr" to "$activeCoreNs.ptr")
                add("MutPtr" to "$activeCoreNs.ptr")
                add("TypeLayout" to "$activeCoreNs.mem")
                add("PrimitiveLayouts" to "$activeCoreNs.mem")
                add("PrimitiveArrayLayouts" to "$activeCoreNs.mem")
                add("fixedListTypeLayout" to "$activeCoreNs.mem")
                add("F16" to activeCoreNs)
                add("F128" to activeCoreNs)
                add("OverflowingResult" to "$activeCoreNs.num")
                if (!headerContext.rawAbiMode) {
                    for (ffiSym in CoreIntrinsicLowering.STDLIB_CORE_FFI_SYMBOLS.values.distinct()) {
                        if (resolveBestSubpackageForSymbol(ffiSym) == null) {
                            add(
                                ffiSym to
                                    CoreIntrinsicLowering.defaultStdlibFfiSymbolPackage(
                                        ffiSym,
                                        activeCoreNs,
                                        activeAllocNs,
                                    ),
                            )
                        }
                    }
                    if (resolveBestSubpackageForSymbol("CString") == null) {
                        add(
                            "CString" to
                                CoreIntrinsicLowering.defaultStdlibFfiSymbolPackage(
                                    "CString",
                                    activeCoreNs,
                                    activeAllocNs,
                                ),
                        )
                    }
                }
                if (usesExternalRustLangCore) {
                    add("U128" to activeCoreNs)
                    add("I128" to activeCoreNs)
                    add("RustAny" to activeCoreNs)
                    add("RustTypeId" to activeCoreNs)
                    add("Ordering" to "$activeCoreNs.cmp")
                    add("compareNaturalOrder" to "$activeCoreNs.cmp")
                    add("ParseIntError" to "$activeCoreNs.num.error")
                    add("IntErrorKind" to "$activeCoreNs.num.error")
                    add("TryFromIntError" to "$activeCoreNs.num.error")
                    add("FpCategory" to "$activeCoreNs.num")
                    add("Wrapping" to "$activeCoreNs.num.wrapping")
                    add("Pin" to "$activeCoreNs.pin")
                    add("ControlFlow" to "$activeCoreNs.ops.controlflow")
                    add("Allocator" to "$activeCoreNs.alloc")
                    add("AllocError" to "$activeCoreNs.alloc")
                    add("Layout" to "$activeCoreNs.alloc.layout")
                    add("transmute" to "$activeCoreNs.mem")
                    add("transmuteUnchecked" to "$activeCoreNs.mem")
                    add("sizeOf" to "$activeCoreNs.mem")
                    add("alignOf" to "$activeCoreNs.mem")
                }
                if ("core" in headerContext.crateNamespaces || headerContext.scalarChar) {
                    add("Cell" to "$activeCoreNs.cell")
                    add("RefCell" to "$activeCoreNs.cell")
                    add("Ref" to "$activeCoreNs.cell")
                    add("RefMut" to "$activeCoreNs.cell")
                    add("OnceCell" to "$activeCoreNs.cell.once")
                    add("RustChar" to "$activeCoreNs.char")
                    add("CharTryFromError" to "$activeCoreNs.char")
                    add("RustCharIndex" to "$activeCoreNs.char")
                    add("rustChars" to "$activeCoreNs.char")
                    add("rustCharIndices" to "$activeCoreNs.char")
                    for (asciiHelper in listOf(
                        "isAscii",
                        "isAsciiAlphabetic",
                        "isAsciiUppercase",
                        "isAsciiLowercase",
                        "isAsciiAlphanumeric",
                        "isAsciiDigit",
                        "isAsciiHexdigit",
                        "isAsciiPunctuation",
                        "isAsciiGraphic",
                        "isAsciiWhitespace",
                        "isAsciiControl",
                        "toAsciiLowercase",
                        "toAsciiUppercase",
                    )) {
                        add(asciiHelper to "$activeCoreNs.char")
                    }
                }
            }
        for ((symSimple, symPkg) in autoCoreSymbols) {
            if (headerContext.packageName != symPkg &&
                symSimple !in inspector.declaredTypeNames &&
                symSimple !in inspector.declaredConstNames.values &&
                symSimple !in importedSimpleNameToPkg &&
                !isDeclaredTopLevelFnKotlinName(symSimple) &&
                containsWholeIdentifier(bodyTextForImports, symSimple)
            ) {
                val imp = "import $symPkg.$symSimple"
                if (imp !in combinedExtraTypeImports) {
                    combinedExtraTypeImports += imp
                }
            }
        }
        if (usesExternalRustLangCore && !headerContext.rawAbiMode) {
            for ((variantName, variantOwner) in listOf(
                "Break" to "$activeCoreNs.ops.controlflow.ControlFlow",
                "Continue" to "$activeCoreNs.ops.controlflow.ControlFlow",
                "Less" to "$activeCoreNs.cmp.Ordering",
                "Equal" to "$activeCoreNs.cmp.Ordering",
                "Greater" to "$activeCoreNs.cmp.Ordering",
            )) {
                val ownerPkg = variantOwner.substringBeforeLast('.')
                if (headerContext.packageName != ownerPkg &&
                    variantName !in inspector.declaredTypeNames &&
                    variantName !in inspector.declaredConstNames.values &&
                    variantName !in inspector.enumVariantSimpleNames &&
                    variantName !in inspector.importedEnumVariantSimpleNames &&
                    variantName !in importedSimpleNameToPkg &&
                    !isDeclaredTopLevelFnKotlinName(variantName) &&
                    containsWholeIdentifier(bodyTextForImports, variantName)
                ) {
                    val imp = "import $variantOwner.$variantName"
                    if (imp !in combinedExtraTypeImports) {
                        combinedExtraTypeImports += imp
                    }
                }
            }
        }
        if (usesRustChar && headerContext.packageName != "$activeCoreNs.char" && "RustChar" !in inspector.declaredTypeNames) {
            val rustCharImport = "import $activeCoreNs.char.RustChar"
            if (rustCharImport !in combinedExtraTypeImports) {
                combinedExtraTypeImports += rustCharImport
            }
        }
        if (usesTaggedCoreResult() && !hasDeclaredResultEnum() && headerContext.packageName != "$activeCoreNs.result") {
            for (resultExt in listOf("andThen", "unwrapOr", "unwrapOrElse", "orElse", "and", "or")) {
                if (resultExt !in inspector.declaredTypeNames &&
                    resultExt !in inspector.declaredConstNames.values &&
                    resultExt !in importedSimpleNameToPkg &&
                    !isDeclaredTopLevelFnKotlinName(resultExt) &&
                    Regex("\\.\\s*$resultExt\\s*[({<]").containsMatchIn(bodyTextForImports)
                ) {
                    val imp = "import $activeCoreNs.result.$resultExt"
                    if (imp !in combinedExtraTypeImports) {
                        combinedExtraTypeImports += imp
                    }
                }
            }
        }
        extraImports.addAll(
            combinedExtraTypeImports.filter { imp ->
                val impPath = imp.removePrefix("import ").substringBefore(" as ").trim()
                val rawSimple = impPath.substringAfterLast('.')
                val targetPkg = impPath.substringBeforeLast('.', missingDelimiterValue = "")
                val isSingleUpperTypeParam = rawSimple.length == 1 && rawSimple[0] in 'A'..'Z'
                val isEnumVariantShadowed =
                    (rawSimple in inspector.enumVariantSimpleNames || rawSimple in inspector.importedEnumVariantSimpleNames) &&
                        !impPath.endsWith(".Result.$rawSimple") &&
                        impPath != "Result.$rawSimple" &&
                        !impPath.endsWith(".Option.$rawSimple") &&
                        impPath != "Option.$rawSimple"
                targetPkg != headerContext.packageName &&
                    rawSimple !in inspector.declaredTypeNames &&
                    !isSingleUpperTypeParam &&
                    !isEnumVariantShadowed &&
                    containsWholeIdentifier(bodyTextForImports, rawSimple)
            },
        )
        val rawProjNs = headerContext.projectNamespace?.trim()?.removeSuffix(".")
        val projNs = rawProjNs?.takeIf { it.isNotEmpty() }
        if (needsSynthesizedInt128 && headerContext.isBatchCrateOutput && projNs != null && headerContext.packageName != projNs) {
            if (containsWholeIdentifier(bodyTextForImports, "U128")) {
                extraImports += "import $projNs.U128"
            }
            if (containsWholeIdentifier(bodyTextForImports, "I128")) {
                extraImports += "import $projNs.I128"
            }
        }
        if (needsSynthesizedRustAny && headerContext.isBatchCrateOutput && projNs != null && headerContext.packageName != projNs) {
            if (containsWholeIdentifier(bodyTextForImports, "RustAny")) {
                extraImports += "import $projNs.RustAny"
            }
            if (containsWholeIdentifier(bodyTextForImports, "RustTypeId")) {
                extraImports += "import $projNs.RustTypeId"
            }
        }
        val libcNs = FfiInteropCollector.resolveLibcNamespace(headerContext)
        if (libcNs != null && headerContext.packageName != libcNs) {
            if (projNs == libcNs &&
                headerContext.crateSymbolSubpackages.isNotEmpty() &&
                "CEnumRepr" !in inspector.declaredTypeNames &&
                "CEnumRepr" !in importedSimpleNameToPkg &&
                containsWholeIdentifier(bodyTextForImports, "CEnumRepr")
            ) {
                val cenumSub = resolveBestSubpackageForSymbol("CEnumRepr")
                if (cenumSub != null) {
                    val cenumPkg = if (cenumSub.isEmpty()) libcNs else "$libcNs.$cenumSub"
                    if (headerContext.packageName != cenumPkg) {
                        extraImports += "import $cenumPkg.CEnumRepr"
                    }
                }
            }
        }
        if (usesQuoteUnderlyingSpan) {
            val quoteNs =
                headerContext.crateNamespaces["quote"]
                    ?.trim()
                    ?.removeSuffix(".")
                    ?.takeIf { it.isNotEmpty() }
                    ?: headerContext.projectGroup
                        ?.trim()
                        ?.removeSuffix(".")
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { "$it.quote" }
            if (quoteNs != null && headerContext.packageName != quoteNs) {
                extraImports += "import $quoteNs.span as quoteSpan"
            }
        }
        if (aliasedUnderlyingSelfCalls.isNotEmpty()) {
            val fallbackPkg =
                headerContext.projectNamespace
                    ?.trim()
                    ?.removeSuffix(".")
                    ?.takeIf { it.isNotEmpty() }
                    ?: headerContext.packageName
                        ?.trim()
                        ?.removeSuffix(".")
                        ?.takeIf { it.isNotEmpty() }
            if (fallbackPkg != null) {
                for ((methodKt, aliasKt) in aliasedUnderlyingSelfCalls) {
                    extraImports += "import $fallbackPkg.$methodKt as $aliasKt"
                }
            }
        }

        val seenImportedSimpleNames = hashSetOf<String>()
        val importLines =
            (useImportLines + extraImports)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .filter { line ->
                    if (line.endsWith(".*")) {
                        true
                    } else {
                        val afterImport = line.removePrefix("import ").trim()
                        val boundName =
                            if (" as " in afterImport) {
                                afterImport.substringAfterLast(" as ").trim()
                            } else {
                                afterImport.substringAfterLast('.').trim()
                            }
                        seenImportedSimpleNames.add(boundName)
                    }
                }.let { filtered ->
                    if (headerContext.isBatchCrateOutput || TranslationSession.finalStructs) {
                        sortImportsForKtlint(filtered)
                    } else {
                        filtered.sorted()
                    }
                }
        ffiCollector.recordImportLines(importLines)
        ffiCollector.recordLocalTypeNames(inspector.declaredTypeNames)
        if (importLines.isNotEmpty()) {
            out.append(importLines.joinToString("\n")).append("\n\n")
        }

        val fileDocLines = mutableListOf<String>()
        fileDocLines.addAll(extractDocLinesFromAttributes(file.attrs))
        val rawInnerDocLines = mutableListOf<String>()
        for (innerDoc in trivia.fileInnerDocs) {
            val raw =
                when (innerDoc.style) {
                    TriviaStyle.InnerDocLine -> innerDoc.text.removePrefix("//!")
                    TriviaStyle.InnerDocBlock -> innerDoc.text.removePrefix("/*!").removeSuffix("*/")
                    else -> innerDoc.text
                }
            for (line in raw.split('\n')) {
                rawInnerDocLines +=
                    line.trimEnd('\r').let {
                        if (innerDoc.style == TriviaStyle.InnerDocBlock) it.trim().removePrefix("*") else it
                    }
            }
        }
        for (sanitized in sanitizeDocLines(rawInnerDocLines)) {
            if (sanitized.isNotEmpty()) {
                fileDocLines += sanitized
            }
        }
        if (allBodyItems.isNotEmpty()) {
            for (origItem in originalFile.items) {
                if (origItem is Item.Macro && origItem.mac.path.isIdent("macro_rules") && !itemHasDocOnlyCfg(origItem)) {
                    val origMacroName = origItem.ident?.toString()?.removePrefix("r#")
                    if (origMacroName != null && origMacroName in standaloneMacros.emittedMacroNames) {
                        continue
                    }
                    val macroDocs = extractDocLinesFromAttributes(origItem.attrs)
                    if (macroDocs.isNotEmpty()) {
                        if (fileDocLines.isNotEmpty()) fileDocLines += ""
                        fileDocLines.addAll(macroDocs)
                    }
                }
            }
        }
        if (fileDocLines.isNotEmpty()) {
            for (docLine in fileDocLines) {
                if (docLine.isEmpty()) {
                    out.append("//\n")
                } else {
                    out.append("// ").append(docLine).append('\n')
                }
            }
            out.append('\n')
        }

        out.append(allBodyItems.joinToString("\n\n"))
        if (out.isNotEmpty() && !out.endsWith('\n')) {
            out.append('\n')
        }
        return ensureBlankLineBeforeKDocAfterLineComment(out.toString())
    }

    internal fun extractDirectOwnerTypeName(selfTy: SynType, generics: Generics): String? {
        val typeParamNames =
            generics.params
                .toList()
                .mapNotNull { param ->
                    (param as? GenericParam.TypeParam)?.ident?.toString()?.let { rustTypeIdentToKotlin(it) }
                }.toSet()
        return when (selfTy) {
            is SynType.Path -> {
                if (selfTy.qself != null) return null
                val segments = selfTy.path.segments.toList()
                if (segments.size != 1) return null
                val rawSeg = segments[0].ident.toString()
                if (rawSeg == "Box" && "Box" !in inspector.declaredTypeNames) {
                    val args = (segments[0].arguments as? PathArguments.AngleBracketed)?.args?.toList().orEmpty()
                    val firstTypeArg = args.filterIsInstance<GenericArgument.TypeArg>().firstOrNull()
                    return if (firstTypeArg != null) extractDirectOwnerTypeName(firstTypeArg.type, generics) else null
                }
                if (rawSeg in PRIMITIVE_TYPE_NAMES) return null
                val name = rustTypeIdentToKotlin(rawSeg)
                val declarationGenerics =
                    file.items.firstNotNullOfOrNull { declaration ->
                        when (declaration) {
                            is Item.Struct -> declaration.generics.takeIf { declaration.ident.toString() == rawSeg }
                            is Item.Enum -> declaration.generics.takeIf { declaration.ident.toString() == rawSeg }
                            is Item.Union -> declaration.generics.takeIf { declaration.ident.toString() == rawSeg }
                            else -> null
                        }
                    }
                val ownerParameters =
                    declarationGenerics
                        ?.params
                        ?.toList()
                        .orEmpty()
                        .filterIsInstance<GenericParam.TypeParam>()
                        .map { it.ident.toString() }
                if (ownerParameters.isNotEmpty()) {
                    val arguments =
                        (segments[0].arguments as? PathArguments.AngleBracketed)
                            ?.args
                            ?.toList()
                            .orEmpty()
                            .filterIsInstance<GenericArgument.TypeArg>()
                            .map { argument ->
                                (argument.type as? SynType.Path)
                                    ?.takeIf { it.qself == null && it.path.segments.size == 1 }
                                    ?.path
                                    ?.segments
                                    ?.last()
                                    ?.ident
                                    ?.toString()
                            }
                    // A specialized impl belongs on its exact receiver, not on
                    // every instantiation of the generic source declaration.
                    if (arguments != ownerParameters) return null
                }
                if (name in typeParamNames) null else name
            }
            is SynType.Reference -> extractDirectOwnerTypeName(selfTy.elem, generics)
            is SynType.Group -> extractDirectOwnerTypeName(selfTy.elem, generics)
            is SynType.Paren -> extractDirectOwnerTypeName(selfTy.elem, generics)
            else -> null
        }
    }

    internal fun classifySynRefKind(ty: SynType): String =
        when (val u = unwrapSynType(ty)) {
            is SynType.Reference -> if (u.mutability != null) "mut_ref" else "shared_ref"
            else -> "owned"
        }

    internal fun matchBlanketLocalIteratorImplOwners(
        item: Item.Impl,
        localTypeImpls: Map<String, List<Item.Impl>>,
    ): List<String> {
        val traitPath = item.traitPath?.path ?: return emptyList()
        val blanketTraitKt =
            traitPath.segments
                .last()
                ?.ident
                ?.toString()
                ?.let(::rustTypeIdentToKotlin) ?: return emptyList()
        if (blanketTraitKt !in inspector.declaredTraitsByName) return emptyList()
        val selfPath =
            (unwrapSynType(item.selfType) as? SynType.Path)
                ?.takeIf { it.qself == null && it.path.segments.len() == 1 }
                ?: return emptyList()
        val firstSelfSeg = selfPath.path.segments.first() ?: return emptyList()
        val blanketParam = rustTypeIdentToKotlin(firstSelfSeg.ident.toString())
        val implTypeParams = collectGenericTypeParamNames(item.generics)
        if (blanketParam !in implTypeParams) return emptyList()

        val requiredStdTraits = linkedSetOf<String>()
        var expectedItemSpec: Pair<String, String>? = null
        fun inspectBoundList(bounds: List<TypeParamBound>): Boolean {
            for (b in bounds) {
                if (b !is TypeParamBound.Trait || b.maybe != null) continue
                val lastSeg = b.path.segments.last() ?: continue
                val rawName = lastSeg.ident.toString()
                if (rawName in IGNORED_TRAIT_BOUNDS) continue
                val ktName = rustTypeIdentToKotlin(rawName)
                if (ktName !in RUST_STD_IMPLICIT_SUPERTRAITS) return false
                requiredStdTraits += ktName
                val angle = lastSeg.arguments as? PathArguments.AngleBracketed
                if (angle != null) {
                    for (ga in angle.args.toList()) {
                        if (ga is GenericArgument.AssocTypeArg && ga.assoc.ident.toString() == "Item") {
                            expectedItemSpec = renderType(ga.assoc.ty, null) to classifySynRefKind(ga.assoc.ty)
                        }
                    }
                }
            }
            return true
        }
        for (gp in item.generics.params.toList()) {
            if (gp is GenericParam.TypeParam && rustTypeIdentToKotlin(gp.ident.toString()) == blanketParam) {
                if (!inspectBoundList(gp.bounds.toList())) return emptyList()
            }
        }
        val implWherePredicates =
            item.generics.whereClause
                ?.predicates
                ?.toList()
                .orEmpty()
        for (pred in implWherePredicates) {
            if (pred is WherePredicate.TypePredicate && renderType(pred.boundedTy, null) == blanketParam) {
                if (!inspectBoundList(pred.bounds.toList())) return emptyList()
            }
        }
        val targetSpec = expectedItemSpec ?: return emptyList()
        if (requiredStdTraits.isEmpty()) return emptyList()

        val matched = mutableListOf<String>()
        for (structName in inspector.attachableTypeNames) {
            val wrapsTrait =
                inspector.structFieldSynTypesByType[structName]
                    ?.values
                    ?.any { renderType(it, null).substringBefore('<').trim() == blanketTraitKt } == true
            if (wrapsTrait) continue
            val candidateImpls = localTypeImpls[structName].orEmpty()
            val implementedTraits =
                candidateImpls
                    .mapNotNull { c ->
                        if (isDocOnlyCfgAttribute(c.attrs)) return@mapNotNull null
                        c.traitPath
                            ?.path
                            ?.segments
                            ?.last()
                            ?.ident
                            ?.toString()
                            ?.let(::rustTypeIdentToKotlin)
                    }.toSet()
            if (!implementedTraits.containsAll(requiredStdTraits)) continue
            var candidateIterSpec: Pair<String, String>? = null
            for (c in candidateImpls) {
                if (isDocOnlyCfgAttribute(c.attrs)) continue
                val rawT =
                    c.traitPath
                        ?.path
                        ?.segments
                        ?.last()
                        ?.ident
                        ?.toString()
                if (rawT == "Iterator") {
                    for (sub in c.items) {
                        if (sub is ImplItem.AssocType && sub.ident.toString() == "Item") {
                            candidateIterSpec = renderType(sub.ty, null) to classifySynRefKind(sub.ty)
                        }
                    }
                }
            }
            if (candidateIterSpec == targetSpec) {
                matched += structName
            }
        }
        return matched
    }

    internal fun renderLeadingTriviaForSpan(span: Span, indent: String): String {
        val startByte = span.startOffset()
        if (!emittedTriviaStartBytes.add(startByte)) return ""
        val comments = trivia.leadingBefore(span)
        return renderInlineComments(comments, indent)
    }

    internal fun renderLeadingTrivia(node: ToTokens, indent: String): String {
        val headerSpans = collectHeaderSourceSpans(node)
        if (headerSpans.isEmpty()) return ""
        val sb = StringBuilder()
        val firstOffset = headerSpans.first().startOffset()
        val wrapperSpans = macroWrapperSpansByTargetOffset[firstOffset].orEmpty()
        for (sp in wrapperSpans) {
            sb.append(renderLeadingTriviaForSpan(sp, indent))
        }
        for (sp in headerSpans) {
            sb.append(renderLeadingTriviaForSpan(sp, indent))
        }
        return sb.toString()
    }

    internal fun containsWholeIdentifier(text: String, ident: String): Boolean {
        if (ident.isEmpty()) return false
        var start = 0
        while (true) {
            val idx = text.indexOf(ident, start)
            if (idx < 0) return false
            val beforeOk = idx == 0 || (!text[idx - 1].isLetterOrDigit() && text[idx - 1] != '_')
            val afterIdx = idx + ident.length
            val afterOk = afterIdx >= text.length || (!text[afterIdx].isLetterOrDigit() && text[afterIdx] != '_')
            if (beforeOk && afterOk) return true
            start = idx + 1
        }
    }

    internal fun stripKotlinCommentsAndStrings(text: String): String {
        val sb = StringBuilder(text.length)
        val interpModes = ArrayList<Char>()
        val interpBraces = ArrayList<Int>()
        var mode = 'C' // 'C' = code, 'S' = "..." string, 'R' = """...""" raw string
        var i = 0
        val n = text.length
        while (i < n) {
            when (mode) {
                'C' -> {
                    val ch = text[i]
                    when {
                        ch == '/' && i + 1 < n && text[i + 1] == '/' -> {
                            i += 2
                            while (i < n && text[i] != '\n') i++
                        }
                        ch == '/' && i + 1 < n && text[i + 1] == '*' -> {
                            var depth = 1
                            i += 2
                            while (i < n && depth > 0) {
                                if (text[i] == '/' && i + 1 < n && text[i + 1] == '*') {
                                    depth++
                                    i += 2
                                } else if (text[i] == '*' && i + 1 < n && text[i + 1] == '/') {
                                    depth--
                                    i += 2
                                } else {
                                    if (text[i] == '\n') sb.append('\n')
                                    i++
                                }
                            }
                            sb.append(' ')
                        }
                        ch == '`' -> {
                            sb.append(ch)
                            i++
                            while (i < n && text[i] != '`' && text[i] != '\n') {
                                sb.append(text[i])
                                i++
                            }
                            if (i < n && text[i] == '`') {
                                sb.append('`')
                                i++
                            }
                        }
                        ch == '\'' -> {
                            i++
                            while (i < n && text[i] != '\'' && text[i] != '\n') {
                                if (text[i] == '\\' && i + 1 < n) i += 2 else i++
                            }
                            if (i < n && text[i] == '\'') i++
                            sb.append(' ')
                        }
                        ch == '"' && i + 2 < n && text[i + 1] == '"' && text[i + 2] == '"' -> {
                            mode = 'R'
                            sb.append(' ')
                            i += 3
                        }
                        ch == '"' -> {
                            mode = 'S'
                            sb.append(' ')
                            i++
                        }
                        ch == '{' -> {
                            if (interpBraces.isNotEmpty()) {
                                interpBraces[interpBraces.lastIndex] = interpBraces.last() + 1
                            }
                            sb.append(ch)
                            i++
                        }
                        ch == '}' -> {
                            if (interpBraces.isNotEmpty()) {
                                val nextDepth = interpBraces.last() - 1
                                if (nextDepth <= 0) {
                                    interpBraces.removeAt(interpBraces.lastIndex)
                                    mode = interpModes.removeAt(interpModes.lastIndex)
                                    sb.append(' ')
                                } else {
                                    interpBraces[interpBraces.lastIndex] = nextDepth
                                    sb.append(ch)
                                }
                            } else {
                                sb.append(ch)
                            }
                            i++
                        }
                        else -> {
                            sb.append(ch)
                            i++
                        }
                    }
                }
                'S' -> {
                    val ch = text[i]
                    when {
                        ch == '\\' && i + 1 < n -> i += 2
                        ch == '"' -> {
                            mode = 'C'
                            sb.append(' ')
                            i++
                        }
                        ch == '$' && i + 1 < n && text[i + 1] == '{' -> {
                            interpModes.add('S')
                            interpBraces.add(1)
                            mode = 'C'
                            sb.append(' ')
                            i += 2
                        }
                        ch == '$' && i + 1 < n && (text[i + 1].isLetter() || text[i + 1] == '_') -> {
                            sb.append(' ')
                            i++
                            while (i < n && (text[i].isLetterOrDigit() || text[i] == '_')) {
                                sb.append(text[i])
                                i++
                            }
                            sb.append(' ')
                        }
                        else -> {
                            if (ch == '\n') sb.append('\n')
                            i++
                        }
                    }
                }
                else -> {
                    val ch = text[i]
                    when {
                        ch == '"' && i + 2 < n && text[i + 1] == '"' && text[i + 2] == '"' -> {
                            if (i + 3 < n && text[i + 3] == '"') {
                                i++
                            } else {
                                mode = 'C'
                                sb.append(' ')
                                i += 3
                            }
                        }
                        ch == '$' && i + 1 < n && text[i + 1] == '{' -> {
                            interpModes.add('R')
                            interpBraces.add(1)
                            mode = 'C'
                            sb.append(' ')
                            i += 2
                        }
                        ch == '$' && i + 1 < n && (text[i + 1].isLetter() || text[i + 1] == '_') -> {
                            sb.append(' ')
                            i++
                            while (i < n && (text[i].isLetterOrDigit() || text[i] == '_')) {
                                sb.append(text[i])
                                i++
                            }
                            sb.append(' ')
                        }
                        else -> {
                            if (ch == '\n') sb.append('\n')
                            i++
                        }
                    }
                }
            }
        }
        return sb.toString()
    }

    internal fun renderItem(
        item: Item,
        indent: String,
        localTypeImpls: Map<String, List<Item.Impl>>,
        isLocalItem: Boolean = false,
        scopeSignatures: MutableMap<String, Int> = topLevelSignatureCounts,
    ): String {
        if (itemHasDocOnlyCfg(item)) return ""
        return when (item) {
            is Item.Use -> ""
            is Item.Const -> renderItemConst(item, indent, scopeSignatures, isLocalItem = isLocalItem)
            is Item.Static -> renderItemStatic(item, indent, scopeSignatures, isLocalItem = isLocalItem)
            is Item.ItemType -> renderItemType(item, indent, scopeSignatures, isLocalItem = isLocalItem)
            is Item.Fn -> renderItemFn(item, indent, scopeSignatures, isLocalItem = isLocalItem)
            is Item.Struct -> {
                val typeName = rustTypeIdentToKotlin(item.ident.toString())
                val attachedImpls = localTypeImpls[typeName].orEmpty()
                renderItemStruct(item, attachedImpls, indent, scopeSignatures, isLocalItem = isLocalItem)
            }
            is Item.Enum -> {
                val typeName = rustTypeIdentToKotlin(item.ident.toString())
                val attachedImpls = localTypeImpls[typeName].orEmpty()
                renderItemEnum(item, attachedImpls, indent, scopeSignatures, isLocalItem = isLocalItem)
            }
            is Item.Union -> {
                val typeName = rustTypeIdentToKotlin(item.ident.toString())
                val attachedImpls = localTypeImpls[typeName].orEmpty()
                renderItemUnion(item, attachedImpls, indent, scopeSignatures, isLocalItem = isLocalItem)
            }
            is Item.Trait -> renderItemTrait(item, indent, scopeSignatures, isLocalItem = isLocalItem)
            is Item.TraitAlias -> renderItemTraitAlias(item, indent, scopeSignatures, isLocalItem = isLocalItem)
            is Item.Impl -> renderStandaloneItemImpl(item, indent, scopeSignatures, isLocalItem = isLocalItem)
            is Item.Mod -> renderItemMod(item, indent, scopeSignatures, isLocalItem = isLocalItem)
            is Item.Macro -> renderItemMacro(item, indent, isLocalItem = isLocalItem)
            is Item.ForeignMod -> renderItemForeignMod(item, indent, isLocalItem = isLocalItem)
            is Item.ExternCrate -> ""
            is Item.Verbatim -> CoreIntrinsicLowering.renderRustcIntrinsicVerbatimItem(item, this, indent).orEmpty()
        }
    }
}

private fun topologicallySortConstItems(items: List<Item>): List<Item> {
    val constIndices = mutableListOf<Int>()
    val constItems = mutableListOf<Item.Const>()
    val indexByRawName = linkedMapOf<String, Int>()
    for ((idx, item) in items.withIndex()) {
        if (item is Item.Const) {
            val raw = item.ident.toString().removePrefix("r#")
            if (raw != "_" && raw !in indexByRawName) {
                val cIdx = constItems.size
                constIndices += idx
                constItems += item
                indexByRawName[raw] = cIdx
                val ktConst = KotlinNamingPolicy.Default.toConstIdentifier(raw)
                if (ktConst != raw && ktConst !in indexByRawName) {
                    indexByRawName[ktConst] = cIdx
                }
            }
        }
    }
    if (constItems.size <= 1) return items

    val hasCall = BooleanArray(constItems.size)
    val deps = Array(constItems.size) { mutableListOf<Int>() }
    for ((cIdx, cItem) in constItems.withIndex()) {
        val collector =
            object : Visit() {
                override fun visitExprCall(e: Expr.Call) {
                    hasCall[cIdx] = true
                    super.visitExprCall(e)
                }

                override fun visitExprMethodCall(e: Expr.MethodCall) {
                    hasCall[cIdx] = true
                    super.visitExprMethodCall(e)
                }

                override fun visitPath(p: Path) {
                    val segs = p.segments.toList()
                    if (segs.isNotEmpty()) {
                        val lastIdent = segs.last().ident.toString()
                        val raw = lastIdent.removePrefix("r#")
                        val depIdx =
                            indexByRawName[raw]
                                ?: indexByRawName[KotlinNamingPolicy.Default.toConstIdentifier(raw)]
                        if (depIdx != null && depIdx != cIdx) {
                            deps[cIdx] += depIdx
                        }
                    }
                    super.visitPath(p)
                }
            }
        collector.visitExpr(cItem.expr)
    }

    val state = IntArray(constItems.size)
    val orderedConsts = ArrayList<Item.Const>(constItems.size)
    fun dfs(u: Int) {
        state[u] = 1
        for (v in deps[u]) {
            if (state[v] == 0) dfs(v)
        }
        state[u] = 2
        orderedConsts += constItems[u]
    }
    for (i in constItems.indices) {
        if (!hasCall[i] && state[i] == 0) dfs(i)
    }
    for (i in constItems.indices) {
        if (state[i] == 0) dfs(i)
    }

    val result = items.toMutableList()
    for ((slotIdx, targetPos) in constIndices.withIndex()) {
        result[targetPos] = orderedConsts[slotIdx]
    }
    return result
}

internal val PRIMITIVE_ARRAY_TYPES =
    setOf(
        "ByteArray",
        "UByteArray",
        "ShortArray",
        "UShortArray",
        "IntArray",
        "UIntArray",
        "LongArray",
        "ULongArray",
        "FloatArray",
        "DoubleArray",
        "BooleanArray",
    )
internal val IGNORED_USE_ROOTS = setOf("super", "self")
internal val STDLIB_CRATE_ROOTS = setOf("std", "core", "alloc")
internal val STDLIB_MACRO_OR_PRIMITIVE_MODULES = setOf("primitive", "concat", "stringify")
internal val FLAT_KOTLINMANIA_CRATES = setOf("procmacro2", "quote")
internal val STDLIB_TYPE_PATH_PREFIXES =
    setOf("std", "core", "alloc", "vec", "boxed", "collections", "rc", "sync", "slice", "option", "marker", "mem")
internal val IGNORED_PATH_PREFIXES = setOf("crate", "super", "self", "std", "core", "alloc")
internal val STDLIB_PRELUDE_REEXPORT_MODULES =
    setOf("__private", "export", "prelude", "v1", "rust_2015", "rust_2018", "rust_2021", "rust_2024")
internal val IGNORED_TRAIT_BOUNDS =
    setOf(
        "Sized",
        "MetaSized",
        "PointeeSized",
        "Unsize",
        "CoerceUnsized",
        "DispatchFromDyn",
        "Send",
        "Sync",
        "Unpin",
        "UnwindSafe",
        "RefUnwindSafe",
        "TrivialDrop",
        "Destruct",
        "Hasher",
    )
internal val RUST_STD_IMPLICIT_SUPERTRAITS =
    setOf(
        "Clone",
        "Copy",
        "Debug",
        "Default",
        "PartialEq",
        "Eq",
        "PartialOrd",
        "Ord",
        "Hash",
        "Drop",
        "Send",
        "Sync",
        "Unpin",
        "UnwindSafe",
        "RefUnwindSafe",
        "Iterator",
        "ExactSizeIterator",
        "DoubleEndedIterator",
        "FusedIterator",
        "FromIterator",
        "IntoIterator",
        "Extend",
        "Index",
        "IndexMut",
        "Deref",
        "DerefMut",
        "Display",
        "Octal",
        "LowerHex",
        "UpperHex",
        "Binary",
        "From",
        "TryFrom",
        "Into",
        "TryInto",
    )

internal val STDLIB_TRAIT_SUBMODULES =
    setOf(
        "error",
        "fmt",
        "cmp",
        "hash",
        "ops",
        "convert",
        "iter",
        "borrow",
        "marker",
        "panic",
        "any",
        "clone",
        "default",
    )

internal val STDLIB_EXPLICIT_SUPERTRAITS =
    RUST_STD_IMPLICIT_SUPERTRAITS + setOf("Error", "Borrow")

internal fun KotlinJ2KEmitter.isImplicitStdlibSupertraitPath(path: io.github.kotlinmania.syn.Path): Boolean {
    val segs = path.segments.toList().map { it.ident.toString().removePrefix("r#") }
    val rawTrait = segs.lastOrNull() ?: return false
    val ktTrait = rustTypeIdentToKotlin(rawTrait)
    if (segs.size >= 2) {
        val isStdRoot = segs.first() in STDLIB_CRATE_ROOTS
        val prefixSubmod = segs.dropLast(1).joinToString("::")
        if ((isStdRoot || prefixSubmod in STDLIB_TRAIT_SUBMODULES) && ktTrait in STDLIB_EXPLICIT_SUPERTRAITS) {
            return true
        }
    }
    if (ktTrait in RUST_STD_IMPLICIT_SUPERTRAITS && ktTrait !in inspector.declaredTypeNames) {
        return true
    }
    if (ktTrait == "Error" && ktTrait !in inspector.declaredTraitsByName) {
        return true
    }
    return false
}
internal fun ensureBlankLineBeforeKDocAfterLineComment(text: String): String {
    if ("/**" !in text) return text
    val lines = text.split('\n')
    val out = ArrayList<String>(lines.size + 8)
    var parenDepth = 0
    var inBlockComment = false
    for (i in lines.indices) {
        val line = lines[i]
        val trimmed = line.trimStart()
        if (!inBlockComment &&
            i > 0 &&
            parenDepth == 0 &&
            trimmed.startsWith("/**") &&
            lines[i - 1].trimStart().startsWith("//")
        ) {
            out += ""
        }
        out += line
        var j = 0
        var inString = false
        var inChar = false
        while (j < line.length) {
            val ch = line[j]
            val next = if (j + 1 < line.length) line[j + 1] else '\u0000'
            when {
                inBlockComment -> {
                    if (ch == '*' && next == '/') {
                        inBlockComment = false
                        j += 2
                        continue
                    }
                }
                inString -> {
                    if (ch == '\\') {
                        j += 2
                        continue
                    }
                    if (ch == '"') {
                        inString = false
                    }
                }
                inChar -> {
                    if (ch == '\\') {
                        j += 2
                        continue
                    }
                    if (ch == '\'') {
                        inChar = false
                    }
                }
                ch == '/' && next == '/' -> break
                ch == '/' && next == '*' -> {
                    inBlockComment = true
                    j += 2
                    continue
                }
                ch == '"' -> inString = true
                ch == '\'' -> inChar = true
                ch == '(' -> parenDepth += 1
                ch == ')' -> if (parenDepth > 0) parenDepth -= 1
            }
            j += 1
        }
    }
    return out.joinToString("\n")
}

internal val SYNTHESIZED_DEBUG_STRUCT_DECLARATION =
    """
    public class DebugStruct(
        private val out: StringBuilder,
        private val name: String,
    ) {
        private val fields: MutableList<String> = mutableListOf()

        public fun field(name: String, value: Any?): DebugStruct =
            apply {
                fields.add("${'$'}name: ${'$'}value")
            }

        public fun finish() {
            if (fields.isEmpty()) {
                out.append(name)
            } else {
                out.append("${'$'}name { ${'$'}{fields.joinToString(", ")} }")
            }
        }

        public fun finishNonExhaustive() {
            fields.add("..")
            finish()
        }
    }
    """.trimIndent()

internal val SYNTHESIZED_DEBUG_TUPLE_DECLARATION =
    """
    public class DebugTuple(
        private val out: StringBuilder,
        private val name: String,
    ) {
        private val fields: MutableList<String> = mutableListOf()

        public fun field(value: Any?): DebugTuple =
            apply {
                fields.add(value.toString())
            }

        public fun finish() {
            if (fields.isEmpty()) {
                out.append(name)
            } else if (name.isEmpty()) {
                out.append("(${'$'}{fields.joinToString(", ")})")
            } else {
                out.append("${'$'}name(${'$'}{fields.joinToString(", ")})")
            }
        }
    }
    """.trimIndent()

internal val STDLIB_ERASED_OR_BUILTIN_IMPORTS =
    RUST_STD_IMPLICIT_SUPERTRAITS +
        setOf(
            "Hasher",
            "Formatter",
            "DebugStruct",
            "DebugTuple",
            "DebugList",
            "DebugSet",
            "DebugMap",
            "ManuallyDrop",
            "needs_drop",
            "PhantomData",
            "ThreadId",
            "Rc",
            "Arc",
            "VecDeque",
            "None",
            "Some",
            "Ok",
            "Err",
            "TypeId",
            "Into",
            "From",
            "Octal",
            "LowerHex",
            "UpperHex",
            "Binary",
        )
internal val KNOWN_SELF_PARAMETERIZED_TRAITS = setOf("Parse", "ParseQuote")
internal val KOTLIN_COLLECTION_INTERFACES = setOf("List", "MutableList", "Map", "MutableMap", "Set", "MutableSet", "ArrayDeque")
internal val MUTATING_VEC_METHODS =
    setOf("push", "pop", "clear", "insert", "remove", "truncate", "retain", "extend", "extend_from_slice", "append", "drain", "swap_remove")
internal val KNOWN_OPTION_RETURNING_METHODS =
    setOf(
        "take",
        "pop",
        "pop_front",
        "removeFirstOrNull",
        "removeLastOrNull",
        "next",
        "next_back",
        "first",
        "first_mut",
        "last",
        "last_mut",
        "get",
        "get_mut",
        "getOrNull",
        "punct",
        "punct_mut",
        "find",
        "position",
        "rposition",
        "checked_add",
        "checked_sub",
        "checked_mul",
        "checked_div",
        "checked_rem",
        "checked_shl",
        "checked_shr",
        "checked_neg",
        "strip_prefix",
        "strip_suffix",
        "split_once",
        "rsplit_once",
        "get_key_value",
    )
internal val KOTLIN_JVM_VALUE_TYPES =
    setOf(
        "Boolean",
        "Byte",
        "Short",
        "Int",
        "Long",
        "Float",
        "Double",
        "Char",
        "UByte",
        "UShort",
        "UInt",
        "ULong",
    )
internal val NUMERIC_CAST_TARGETS = KOTLIN_JVM_VALUE_TYPES - "Boolean"
internal val KOTLIN_STDLIB_SHADOWED_IMPORTS =
    setOf(
        "String",
        "Boolean",
        "Char",
        "Byte",
        "Short",
        "Int",
        "Long",
        "UByte",
        "UShort",
        "UInt",
        "ULong",
        "Float",
        "Double",
        "Unit",
        "Nothing",
        "Any",
        "Result",
        "Iterator",
        "Iterable",
        "Comparable",
        "List",
        "MutableList",
        "Map",
        "MutableMap",
        "Set",
        "MutableSet",
        "Pair",
        "Triple",
        "Vec",
        "Box",
        "HashMap",
        "BTreeMap",
        "HashSet",
        "BTreeSet",
    )
internal val FORWARD_DESERIALIZE_KEYS =
    setOf(
        "bool",
        "i8",
        "i16",
        "i32",
        "i64",
        "i128",
        "u8",
        "u16",
        "u32",
        "u64",
        "u128",
        "f32",
        "f64",
        "char",
        "str",
        "string",
        "bytes",
        "byte_buf",
        "option",
        "unit",
        "unit_struct",
        "newtype_struct",
        "seq",
        "tuple",
        "tuple_struct",
        "map",
        "struct",
        "enum",
        "identifier",
        "ignored_any",
    )
internal val PRIMITIVE_TYPE_NAMES =
    setOf(
        "u8",
        "u16",
        "u32",
        "u64",
        "u128",
        "usize",
        "i8",
        "i16",
        "i32",
        "i64",
        "i128",
        "isize",
        "f16",
        "f32",
        "f64",
        "f128",
        "bool",
        "str",
        "char",
    )
internal val IGNORED_QUOTE_KEYWORDS =
    setOf(
        "use",
        "as",
        "crate",
        "const",
        "let",
        "mut",
        "match",
        "fn",
        "impl",
        "for",
        "in",
        "where",
        "pub",
        "return",
        "if",
        "else",
        "struct",
        "enum",
        "trait",
        "type",
        "static",
        "unsafe",
        "ref",
        "dyn",
        "loop",
        "while",
        "break",
        "continue",
        "move",
        "async",
        "await",
        "super",
        "default",
        "union",
        "try",
        "yield",
        "mod",
        "u8",
        "u16",
        "u32",
        "u64",
        "u128",
        "usize",
        "i8",
        "i16",
        "i32",
        "i64",
        "i128",
        "isize",
        "f16",
        "f32",
        "f64",
        "f128",
        "bool",
        "str",
        "char",
    )

internal val SYN_KEYWORD_TOKENS =
    setOf(
        "abstract",
        "as",
        "async",
        "auto",
        "await",
        "become",
        "box",
        "break",
        "const",
        "continue",
        "crate",
        "default",
        "do",
        "dyn",
        "else",
        "enum",
        "extern",
        "final",
        "fn",
        "for",
        "if",
        "impl",
        "in",
        "let",
        "loop",
        "macro",
        "match",
        "mod",
        "move",
        "mut",
        "override",
        "priv",
        "pub",
        "raw",
        "ref",
        "return",
        "safe",
        "static",
        "struct",
        "super",
        "trait",
        "try",
        "typeof",
        "union",
        "unsafe",
        "unsized",
        "use",
        "virtual",
        "where",
        "while",
        "yield",
    )

internal val SYN_PUNCT_TOKEN_NAMES =
    mapOf(
        "&" to "And",
        "&&" to "AndAnd",
        "&=" to "AndEq",
        "@" to "At",
        "^" to "Caret",
        "^=" to "CaretEq",
        ":" to "Colon",
        "," to "Comma",
        "$" to "Dollar",
        "." to "Dot",
        ".." to "DotDot",
        "..." to "DotDotDot",
        "..=" to "DotDotEq",
        "=" to "Eq",
        "==" to "EqEq",
        "=>" to "FatArrow",
        ">=" to "Ge",
        ">" to "Gt",
        "<-" to "LArrow",
        "<=" to "Le",
        "<" to "Lt",
        "-" to "Minus",
        "-=" to "MinusEq",
        "!=" to "Ne",
        "!" to "Not",
        "|" to "Or",
        "|=" to "OrEq",
        "||" to "OrOr",
        "::" to "PathSep",
        "%" to "Percent",
        "%=" to "PercentEq",
        "+" to "Plus",
        "+=" to "PlusEq",
        "#" to "Pound",
        "?" to "Question",
        "->" to "RArrow",
        ";" to "Semi",
        "<<" to "Shl",
        "<<=" to "ShlEq",
        ">>" to "Shr",
        ">>=" to "ShrEq",
        "/" to "Slash",
        "/=" to "SlashEq",
        "*" to "Star",
        "*=" to "StarEq",
        "~" to "Tilde",
    )

internal val SYN_KEYWORD_TOKEN_CLASS_NAMES =
    SYN_KEYWORD_TOKENS.mapTo(linkedSetOf()) { it[0].uppercaseChar() + it.substring(1) }

internal val SYN_PUNCT_TOKEN_CLASS_NAMES =
    SYN_PUNCT_TOKEN_NAMES.values.toSet()
