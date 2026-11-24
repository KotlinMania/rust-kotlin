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
import io.github.kotlinmania.syn.Path
import io.github.kotlinmania.syn.PathArguments
import io.github.kotlinmania.syn.PointerMutability
import io.github.kotlinmania.syn.SynType
import io.github.kotlinmania.syn.TraitItem
import io.github.kotlinmania.syn.TypeParamBound
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
): Pair<String, List<CompanionInteropFile>> =
    TranslationSessionGuard.withHeaderContext(headerContext) { allowStdlibDestructors ->
        if (isDocOnlyCfgAttribute(recognized.file.attrs)) return@withHeaderContext "" to emptyList()
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
        parentText to (emitter.emitCompanionFiles() + splitModCompanions)
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

internal class IndexedFnEntry(
    val order: Int,
    val cleanDecl: String,
    val ktDecl: String,
    val isForeign: Boolean,
    val typedParams: List<FnArg.Typed>,
)

internal class EmitterFnLookupIndex(
    val sharedModFile: File?,
    val entriesByKey: Map<String, List<IndexedFnEntry>>,
    val nonForeignByKtName: Map<String, List<IndexedFnEntry>>,
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
                sym.enumTypeNames.addAll(sharedInsp.enumTypeNames)
                sym.enumVariantSimpleNames.addAll(sharedInsp.enumVariantSimpleNames)
                sym.importedEnumVariantSimpleNames.addAll(sharedInsp.importedEnumVariantSimpleNames)
                sym.ffiReferencedTypeNames.addAll(sharedInsp.ffiReferencedTypeNames)
                sym.referencedUnqualifiedTypeIdents.addAll(sharedInsp.referencedUnqualifiedTypeIdents)
                sym.referencedPathPrefixTypeIdents.addAll(sharedInsp.referencedPathPrefixTypeIdents)
                sym.declaredTraitsByName.putAll(sharedInsp.declaredTraitsByName)
                sym.typeDefaultArgsByType.putAll(sharedInsp.typeDefaultArgsByType)
                sym.tupleStructArityByType.putAll(sharedInsp.tupleStructArityByType)
                sym.tupleEnumVariantArity.putAll(sharedInsp.tupleEnumVariantArity)
                sym.genericUnitEnumVariants.addAll(sharedInsp.genericUnitEnumVariants)
                sym.enumTypeParamsByVariant.putAll(sharedInsp.enumTypeParamsByVariant)
                sym.enumVariantFieldTypes.putAll(sharedInsp.enumVariantFieldTypes)
                sym.collidingInlineForeignFnRenames.putAll(sharedInsp.collidingInlineForeignFnRenames)
                sym.collidingTopLevelForeignFnRenames.putAll(sharedInsp.collidingTopLevelForeignFnRenames)
                sym.selfParameterizedTraits.addAll(sharedInsp.selfParameterizedTraits)
                sym.traitSupertraits.putAll(sharedInsp.traitSupertraits)
                sym.traitSupertraitBindings.putAll(sharedInsp.traitSupertraitBindings)
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
            var assocClosedChanged = true
            while (assocClosedChanged) {
                assocClosedChanged = false
                for ((ktTrait, superBindings) in sym.traitSupertraitBindings) {
                    val cur = sym.traitDefaultlessAssocTypes[ktTrait].orEmpty().toMutableList()
                    var addedHere = false
                    for ((superKt, boundAssocs) in superBindings) {
                        if (superKt in RUST_STD_IMPLICIT_SUPERTRAITS && superKt !in sym.declaredTraitsByName) continue
                        val inherited =
                            (
                                sym.traitDefaultlessAssocTypes[superKt]
                                    ?: headerContext.crateTraitDefaultlessAssocTypes[superKt]
                            ).orEmpty()
                        for (assoc in inherited) {
                            if (assoc !in boundAssocs && assoc !in cur) {
                                cur += assoc
                                addedHere = true
                            }
                        }
                    }
                    if (addedHere) {
                        sym.traitDefaultlessAssocTypes[ktTrait] = cur
                        assocClosedChanged = true
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
    internal val samePackageUseRenames = linkedMapOf<String, String>()
    internal val standaloneTraitImplKeys = linkedSetOf<Pair<String, String>>()
    internal val witnessedSuppressedTraitNames = linkedSetOf<String>()
    internal var callArgumentNestingDepth: Int = 0
    internal var activeLocalBlockItems: List<Item> = emptyList()
    internal var activeEnclosingEnumVariantNames: Set<String> = emptySet()
    internal var activeLocalForeignFnRenames: Map<String, String> = emptyMap()
    internal var renamedTypeParamIdentifiers: Map<String, String> = emptyMap()
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
    internal var currentAttachedStructSuperTraitBaseNames: Set<String> = emptySet()
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
    internal var usesRustChar: Boolean = false
    internal var usesSourceResult: Boolean = false
    internal var usesSourceResultResidual: Boolean = false
    internal var currentEnclosingReturnsFmtResult: Boolean = false
    internal var currentTailExpectsUnitResultOk: Boolean = false
    internal var inLocalInitializerPosition: Boolean = false
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
    internal var cachedFnLookupIndex: EmitterFnLookupIndex? = null
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

        val allEnumItemsForVariantArity =
            sameFileNestedItems(TranslationSession.sharedModuleFile?.items.orEmpty() + file.items)
                .filterIsInstance<Item.Enum>()
        for (enumItem in allEnumItemsForVariantArity) {
            val enumKt = rustTypeIdentToKotlin(enumItem.ident.toString())
            val explicitParams =
                enumItem.generics.params
                    .toList()
                    .mapNotNull { (it as? GenericParam.TypeParam)?.ident?.toString()?.let(::rustTypeIdentToKotlin) }
            val assocParams = sourceAssociatedParameters(enumItem.generics).map { it.associated }
            val fullParams = (explicitParams + assocParams).distinct()
            if (fullParams.size > explicitParams.size) {
                for (v in enumItem.variants.toList()) {
                    val vName = rustTypeIdentToKotlin(v.ident.toString())
                    inspector.enumTypeParamsByVariant["$enumKt.$vName"] = fullParams
                    val prevSimple = inspector.enumTypeParamsByVariant[vName]
                    if (prevSimple == null || prevSimple.size == explicitParams.size) {
                        inspector.enumTypeParamsByVariant[vName] = fullParams
                    }
                }
            }
        }

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
                val ownerType = extractAttachableDirectOwnerTypeName(item)
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
                                val rawForeignFn =
                                    foreign.sig.ident
                                        .toString()
                                        .removePrefix("r#")
                                val fnName = inspector.collidingTopLevelForeignFnRenames[rawForeignFn] ?: extracted.first
                                val kinds = extracted.second
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
                    "// Omitted quickcheck/rand property test items pending quickcheck-kotlin / rand-kotlin port (Hermes Kanban card 19971811)."
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
        if (headerContext.packageName?.startsWith("$activeCoreNs.ptr.") == true) {
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
        val usesExternalRustLangCore =
            "core" in headerContext.crateNamespaces ||
                headerContext.packageName == activeCoreNs ||
                headerContext.packageName?.startsWith("$activeCoreNs.") == true

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
                if (hasConflictingResultBinding()) {
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
            if (headerContext.isDocExample) {
                emptyList()
            } else {
                buildList {
                    add("ConstPtr" to "$activeCoreNs.ptr")
                    add("MutPtr" to "$activeCoreNs.ptr")
                    add("TypeLayout" to "$activeCoreNs.mem")
                    add("PrimitiveLayouts" to "$activeCoreNs.mem")
                    add("PrimitiveArrayLayouts" to "$activeCoreNs.mem")
                    add("fixedListTypeLayout" to "$activeCoreNs.mem")
                    add("U128" to activeCoreNs)
                    add("I128" to activeCoreNs)
                    add("F16" to activeCoreNs)
                    add("F128" to activeCoreNs)
                    add("RustAny" to activeCoreNs)
                    add("RustTypeId" to activeCoreNs)
                    add("DebugStruct" to "$activeCoreNs.fmt")
                    add("DebugTuple" to "$activeCoreNs.fmt")
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
                        add("Ordering" to "$activeCoreNs.cmp")
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
                    if (usesExternalRustLangCore ||
                        "core" in headerContext.crateNamespaces ||
                        headerContext.packageName == activeCoreNs ||
                        headerContext.packageName?.startsWith("$activeCoreNs.") == true
                    ) {
                        for (ptrFn in listOf(
                            "nullMut",
                            "nullVal",
                            "withoutProvenance",
                            "withoutProvenanceMut",
                            "dangling",
                            "danglingMut",
                            "fromRef",
                            "fromMut",
                            "sliceFromRawParts",
                            "sliceFromRawPartsMut",
                            "copyNonoverlapping",
                            "writeBytes",
                            "dropInPlace",
                        )) {
                            add(ptrFn to "$activeCoreNs.ptr")
                        }
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
                            add(asciiHelper to "$activeCoreNs.char.methods")
                        }
                    }
                    if ("core" in headerContext.crateNamespaces && !headerContext.rawAbiMode) {
                        add("unreachableUnchecked" to "$activeCoreNs.hint")
                        add("assertUnchecked" to "$activeCoreNs.hint")
                        add("sizeOf" to "$activeCoreNs.mem")
                        add("alignOf" to "$activeCoreNs.mem")
                        add("sizeOfVal" to "$activeCoreNs.mem")
                        add("alignOfVal" to "$activeCoreNs.mem")
                        add("readUnaligned" to "$activeCoreNs.ptr")
                        add("writeUnaligned" to "$activeCoreNs.ptr")
                        add("min" to "$activeCoreNs.cmp")
                        add("max" to "$activeCoreNs.cmp")
                        add("clamp" to "$activeCoreNs.cmp")
                    }
                }
            }
        val stdlibOverloadExtImports = hashSetOf<String>()
        for ((symSimple, symPkg) in autoCoreSymbols) {
            if (headerContext.packageName != symPkg &&
                symSimple !in inspector.declaredTypeNames &&
                symSimple !in inspector.declaredConstNames.values &&
                symSimple !in importedSimpleNameToPkg &&
                (!isDeclaredTopLevelFnKotlinName(symSimple) || (symPkg == "$activeCoreNs.mem" && symSimple == "get")) &&
                containsWholeIdentifier(bodyTextForImports, symSimple)
            ) {
                val imp = "import $symPkg.$symSimple"
                if (symPkg == "$activeCoreNs.mem" && symSimple == "get") {
                    stdlibOverloadExtImports += imp
                }
                if (imp !in combinedExtraTypeImports) {
                    combinedExtraTypeImports += imp
                }
            }
        }
        val isInCoreOrUsingExternalCore =
            usesExternalRustLangCore ||
                "core" in headerContext.crateNamespaces ||
                headerContext.packageName == activeCoreNs ||
                headerContext.packageName?.startsWith("$activeCoreNs.") == true
        if (isInCoreOrUsingExternalCore && !headerContext.rawAbiMode) {
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
        if (isInCoreOrUsingExternalCore && !headerContext.rawAbiMode && headerContext.packageName != "$activeCoreNs.mem") {
            val maxTupleDestructuringArity =
                Regex("""\b(?:val|var)\s*\(([^()]+)\)\s*=""")
                    .findAll(bodyTextForImports)
                    .maxOfOrNull { match -> match.groupValues[1].count { it == ',' } + 1 }
                    ?: 0
            if (maxTupleDestructuringArity >= 6) {
                for (compIdx in 6..minOf(maxTupleDestructuringArity, 12)) {
                    val compImp = "import $activeCoreNs.mem.component$compIdx"
                    if (compImp !in extraImports) {
                        extraImports += compImp
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
        if (isInCoreOrUsingExternalCore && !headerContext.rawAbiMode) {
            val mutPtrExts =
                listOf(
                    "isNull",
                    "cast",
                    "add",
                    "sub",
                    "offset",
                    "byteAdd",
                    "byteSub",
                    "byteOffset",
                    "wrappingAdd",
                    "wrappingSub",
                    "wrappingOffset",
                    "wrappingByteAdd",
                    "wrappingByteSub",
                    "wrappingByteOffset",
                    "read",
                    "readUnaligned",
                    "readVolatile",
                    "write",
                    "writeUnaligned",
                    "writeVolatile",
                    "writeBytes",
                    "copyTo",
                    "copyToNonoverlapping",
                    "copyFrom",
                    "copyFromNonoverlapping",
                    "asRef",
                    "asMut",
                    "asUninitRef",
                    "asUninitMut",
                    "asUninitSlice",
                    "asUninitSliceMut",
                    "addr",
                    "withAddr",
                    "mapAddr",
                    "exposeProvenance",
                    "castConst",
                    "castUninit",
                    "castInit",
                    "castSlice",
                    "offsetFrom",
                    "offsetFromUnsigned",
                    "byteOffsetFrom",
                    "byteOffsetFromUnsigned",
                    "isAligned",
                    "isAlignedTo",
                    "alignOffset",
                    "dropInPlace",
                    "replace",
                    "swap",
                    "withMetadataOf",
                    "toRawParts",
                    "mask",
                    "len",
                    "splitAtMut",
                )
            val constPtrExts =
                listOf(
                    "isNull",
                    "cast",
                    "add",
                    "sub",
                    "offset",
                    "byteAdd",
                    "byteSub",
                    "byteOffset",
                    "wrappingAdd",
                    "wrappingSub",
                    "wrappingOffset",
                    "wrappingByteAdd",
                    "wrappingByteSub",
                    "wrappingByteOffset",
                    "read",
                    "readUnaligned",
                    "readVolatile",
                    "copyTo",
                    "copyToNonoverlapping",
                    "asRef",
                    "asUninitRef",
                    "asUninitSlice",
                    "addr",
                    "withAddr",
                    "mapAddr",
                    "exposeProvenance",
                    "castMut",
                    "castUninit",
                    "castInit",
                    "castSlice",
                    "offsetFrom",
                    "offsetFromUnsigned",
                    "byteOffsetFrom",
                    "byteOffsetFromUnsigned",
                    "isAligned",
                    "isAlignedTo",
                    "alignOffset",
                    "withMetadataOf",
                    "toRawParts",
                    "mask",
                    "len",
                )
            val numExts =
                listOf(
                    "uncheckedAdd",
                    "uncheckedSub",
                    "uncheckedMul",
                    "checkedAdd",
                    "checkedSub",
                    "checkedMul",
                    "checkedDiv",
                    "checkedRem",
                    "checkedAddUnsigned",
                    "checkedSubUnsigned",
                    "checkedAddSigned",
                    "checkedSubSigned",
                    "overflowingAdd",
                    "overflowingSub",
                    "overflowingMul",
                    "overflowingAddUnsigned",
                    "overflowingSubUnsigned",
                    "overflowingAddSigned",
                    "overflowingSubSigned",
                    "wrappingAddUnsigned",
                    "wrappingSubUnsigned",
                    "wrappingAddSigned",
                    "wrappingSubSigned",
                    "saturatingAdd",
                    "saturatingSub",
                    "saturatingMul",
                    "saturatingAddUnsigned",
                    "saturatingSubUnsigned",
                    "carryingAdd",
                    "borrowingSub",
                    "carryingMul",
                    "carryingMulAdd",
                    "wideningMul",
                    "absDiff",
                    "divCeil",
                    "nextMultipleOf",
                    "checkedNextMultipleOf",
                    "isMultipleOf",
                    "midpoint",
                    "castSigned",
                    "castUnsigned",
                    "isPowerOfTwo",
                    "nextPowerOfTwo",
                    "checkedNextPowerOfTwo",
                    "wrappingNextPowerOfTwo",
                    "ilog",
                    "ilog2",
                    "ilog10",
                    "checkedIlog",
                    "checkedIlog2",
                    "checkedIlog10",
                    "isqrt",
                    "checkedIsqrt",
                )
            val stdlibExts =
                buildList {
                    add("clone" to "$activeCoreNs.clone")
                    add("len" to "$activeCoreNs.slice")
                    add("iter" to "$activeCoreNs.slice")
                    add("iterMut" to "$activeCoreNs.slice")
                    add("splitAt" to "$activeCoreNs.slice")
                    add("len" to "$activeCoreNs.str")
                    add("isCharBoundary" to "$activeCoreNs.str")
                    add("splitAt" to "$activeCoreNs.str")
                    add("splitFirst" to "$activeCoreNs.slice")
                    add("splitFirstMut" to "$activeCoreNs.slice")
                    add("splitLast" to "$activeCoreNs.slice")
                    add("splitLastMut" to "$activeCoreNs.slice")
                    add("splitAtMut" to "$activeCoreNs.slice")
                    add("chunksExact" to "$activeCoreNs.slice")
                    add("chunksExactMut" to "$activeCoreNs.slice")
                    if (Regex("""\bAtomic(?:Bool|Ptr|I8|U8|I16|U16|I32|U32|I64|U64|I128|U128|Isize|Usize)?\b""")
                            .containsMatchIn(bodyTextForImports)
                    ) {
                        for (atomicExt in listOf(
                            "load",
                            "store",
                            "swap",
                            "compareExchange",
                            "compareExchangeWeak",
                            "fetchAdd",
                            "fetchSub",
                            "fetchAnd",
                            "fetchOr",
                            "fetchXor",
                            "fetchNand",
                            "fetchMax",
                            "fetchMin",
                            "fetchUpdate",
                            "intoInner",
                            "getMut",
                            "asPtr",
                        )) {
                            add(atomicExt to "$activeCoreNs.sync.atomic")
                        }
                    }
                    for (ext in numExts) {
                        add(ext to "$activeCoreNs.num")
                    }
                    if (containsWholeIdentifier(bodyTextForImports, "MutPtr") ||
                        containsWholeIdentifier(bodyTextForImports, "asMutPtr") ||
                        containsWholeIdentifier(bodyTextForImports, "nullMut")
                    ) {
                        for (ext in mutPtrExts) {
                            add(ext to "$activeCoreNs.ptr.mutptr")
                        }
                    }
                    if (containsWholeIdentifier(bodyTextForImports, "ConstPtr") ||
                        containsWholeIdentifier(bodyTextForImports, "asPtr")
                    ) {
                        for (ext in constPtrExts) {
                            add(ext to "$activeCoreNs.ptr.constptr")
                        }
                    }
                }
            for ((extName, extPkg) in stdlibExts) {
                if (headerContext.packageName != extPkg &&
                    extName !in inspector.declaredTypeNames &&
                    extName !in inspector.declaredConstNames.values &&
                    extName !in importedSimpleNameToPkg &&
                    (!isDeclaredTopLevelFnKotlinName(extName) || extName == "clone") &&
                    Regex("\\.\\s*$extName\\s*[({<]").containsMatchIn(bodyTextForImports)
                ) {
                    val imp = "import $extPkg.$extName"
                    stdlibOverloadExtImports += imp
                    if (imp !in combinedExtraTypeImports) {
                        combinedExtraTypeImports += imp
                    }
                }
            }
        }
        if (containsWholeIdentifier(bodyTextForImports, "Byte") ||
            containsWholeIdentifier(bodyTextForImports, "Short")
        ) {
            val expOps =
                buildList {
                    if (Regex("\\.\\s*inv\\s*\\(\\s*\\)").containsMatchIn(bodyTextForImports)) add("inv")
                    if (Regex("\\band\\b").containsMatchIn(bodyTextForImports)) add("and")
                    if (Regex("\\bor\\b").containsMatchIn(bodyTextForImports)) add("or")
                    if (Regex("\\bxor\\b").containsMatchIn(bodyTextForImports)) add("xor")
                }
            for (op in expOps) {
                if (!isDeclaredTopLevelFnKotlinName(op) && op !in importedSimpleNameToPkg) {
                    val imp = "import kotlin.experimental.$op"
                    if (imp !in combinedExtraTypeImports) {
                        combinedExtraTypeImports += imp
                    }
                }
            }
        }
        val nsPrefixForExt = currentNamespacePrefix()
        for ((symSimple, symPkg) in importedSimpleNameToPkg.toList()) {
            if (symSimple.firstOrNull()?.isUpperCase() == true && symPkg != headerContext.packageName) {
                val camelFn = symSimple.replaceFirstChar { it.lowercaseChar() }
                if (camelFn !in inspector.declaredTypeNames &&
                    camelFn !in inspector.declaredConstNames.values &&
                    camelFn !in importedSimpleNameToPkg &&
                    !isDeclaredTopLevelFnKotlinName(camelFn) &&
                    Regex("\\.\\s*$camelFn\\s*[({<]").containsMatchIn(bodyTextForImports)
                ) {
                    val relSub =
                        when {
                            nsPrefixForExt == null -> null
                            symPkg == nsPrefixForExt -> ""
                            symPkg.startsWith("$nsPrefixForExt.") -> symPkg.removePrefix("$nsPrefixForExt.")
                            else -> null
                        }
                    val existsInPkg =
                        (relSub != null && relSub in headerContext.crateSymbolSubpackages[camelFn].orEmpty()) ||
                            headerContext.externalCrateSymbolPackages.values.any { symMap ->
                                symPkg in symMap[camelFn].orEmpty()
                            }
                    if (existsInPkg) {
                        val imp = "import $symPkg.$camelFn"
                        if (imp !in combinedExtraTypeImports) {
                            combinedExtraTypeImports += imp
                        }
                    }
                }
            }
        }
        extraImports.addAll(
            combinedExtraTypeImports.filter { imp ->
                val afterImport = imp.removePrefix("import ").trim()
                val impPath = afterImport.substringBefore(" as ").trim()
                val rawSimple = impPath.substringAfterLast('.')
                val boundSimple =
                    if (" as " in afterImport) {
                        afterImport.substringAfter(" as ").trim()
                    } else {
                        rawSimple
                    }
                val targetPkg = impPath.substringBeforeLast('.', missingDelimiterValue = "")
                val isSingleUpperTypeParam = boundSimple.length == 1 && boundSimple[0] in 'A'..'Z'
                val isEnumVariantShadowed =
                    (boundSimple in inspector.enumVariantSimpleNames || boundSimple in inspector.importedEnumVariantSimpleNames) &&
                        !impPath.endsWith(".Result.$rawSimple") &&
                        impPath != "Result.$rawSimple" &&
                        !impPath.endsWith(".Option.$rawSimple") &&
                        impPath != "Option.$rawSimple"
                targetPkg != headerContext.packageName &&
                    boundSimple !in inspector.declaredTypeNames &&
                    !isSingleUpperTypeParam &&
                    !isEnumVariantShadowed &&
                    containsWholeIdentifier(bodyTextForImports, boundSimple)
            },
        )
        val rawProjNs = headerContext.projectNamespace?.trim()?.removeSuffix(".")
        val projNs = rawProjNs?.takeIf { it.isNotEmpty() }
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
                    if (!headerContext.rawAbiMode && headerContext.packageName?.startsWith("io.github.kotlinmania.rustlang.") == true) {
                        val impTarget = line.removePrefix("import ").substringBefore(" as ").trim()
                        if (impTarget.endsWith(".os.raw") || impTarget.endsWith(".ffi")) {
                            return@filter false
                        }
                    }
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
                        val firstSeen = seenImportedSimpleNames.add(boundName)
                        firstSeen || line in stdlibOverloadExtImports
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
        if (!headerContext.isModuleRootSource && sharedDeclaredTypeNames == null && trivia.trailingComments.isNotEmpty()) {
            val renderedTrailing = renderInlineComments(trivia.trailingComments, indent = "")
            if (renderedTrailing.isNotEmpty()) {
                if (allBodyItems.isNotEmpty() && !out.endsWith("\n\n")) {
                    out.append('\n')
                }
                out.append(renderedTrailing)
            }
        }
        return ensureBlankLineBeforeKDocAfterLineComment(out.toString())
    }

    internal fun extractDirectOwnerTypeName(
        selfTy: SynType,
        generics: Generics,
        scopeItems: List<Item> = file.items,
    ): String? {
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
                    return if (firstTypeArg != null) extractDirectOwnerTypeName(firstTypeArg.type, generics, scopeItems) else null
                }
                if (rawSeg in PRIMITIVE_TYPE_NAMES) return null
                val name = rustTypeIdentToKotlin(rawSeg)
                val declarationGenerics = findDeclGenericsForOwnerRawName(rawSeg, scopeItems) ?: findDeclGenericsForOwnerRawName(rawSeg, file.items)
                if (declarationGenerics != null && !matchesUnspecializedOwnerGenerics(segments[0].arguments, declarationGenerics)) {
                    return null
                }
                if (name in typeParamNames) null else name
            }
            is SynType.Reference -> extractDirectOwnerTypeName(selfTy.elem, generics, scopeItems)
            is SynType.Group -> extractDirectOwnerTypeName(selfTy.elem, generics, scopeItems)
            is SynType.Paren -> extractDirectOwnerTypeName(selfTy.elem, generics, scopeItems)
            else -> null
        }
    }

    internal fun extractAttachableDirectOwnerTypeName(
        impl: Item.Impl,
        scopeItems: List<Item> = file.items,
    ): String? {
        val ownerType = extractDirectOwnerTypeName(impl.selfType, impl.generics, scopeItems) ?: return null
        val ownerDeclGenerics =
            findDeclGenericsForOwnerKotlinName(ownerType, scopeItems)
                ?: findDeclGenericsForOwnerKotlinName(ownerType, file.items)
                ?: return ownerType
        val ownerTypeParamNames = collectGenericTypeParamNames(ownerDeclGenerics).toSet()
        val implTypeParamNames = collectGenericTypeParamNames(impl.generics)
        if (implTypeParamNames.any { it !in ownerTypeParamNames }) {
            return null
        }
        if (impl.traitPath != null && declaresExtraNonImplicitImplTraitBounds(impl, ownerDeclGenerics, this::isImplicitStdlibSupertraitPath)) {
            return null
        }
        return ownerType
    }

    private fun findDeclGenericsForOwnerRawName(rawSeg: String, items: List<Item>): Generics? =
        items.firstNotNullOfOrNull { declaration ->
            when (declaration) {
                is Item.Struct -> declaration.generics.takeIf { declaration.ident.toString() == rawSeg }
                is Item.Enum -> declaration.generics.takeIf { declaration.ident.toString() == rawSeg }
                is Item.Union -> declaration.generics.takeIf { declaration.ident.toString() == rawSeg }
                else -> null
            }
        }

    private fun findDeclGenericsForOwnerKotlinName(ownerType: String, items: List<Item>): Generics? =
        items.firstNotNullOfOrNull { declaration ->
            when (declaration) {
                is Item.Struct -> declaration.generics.takeIf { rustTypeIdentToKotlin(declaration.ident.toString()) == ownerType }
                is Item.Enum -> declaration.generics.takeIf { rustTypeIdentToKotlin(declaration.ident.toString()) == ownerType }
                is Item.Union -> declaration.generics.takeIf { rustTypeIdentToKotlin(declaration.ident.toString()) == ownerType }
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

    internal fun stripKotlinCommentsAndStrings(text: String): String =
        object : KotlinTokenRewriter(text) {
            override fun emitAt(index: Int, out: StringBuilder): Int {
                when (val tok = tokens[index]) {
                    is KotlinLexToken.LineComment -> {}
                    is KotlinLexToken.BlockComment -> {
                        for (ch in tok.rawText) {
                            if (ch == '\n') out.append('\n')
                        }
                        out.append(' ')
                    }
                    is KotlinLexToken.CharLiteral,
                    is KotlinLexToken.StringOpen,
                    is KotlinLexToken.StringClose,
                    is KotlinLexToken.TemplateExprOpen,
                    is KotlinLexToken.TemplateExprClose,
                    -> out.append(' ')
                    is KotlinLexToken.StringText ->
                        for (ch in tok.rawText) {
                            if (ch == '\n') out.append('\n')
                        }
                    is KotlinLexToken.StringIdentTemplate ->
                        out.append(' ').append(tok.ident).append(' ')
                    else -> out.append(tok.rawText)
                }
                return index + 1
            }
        }.rewrite()

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

internal fun topologicallySortConstItems(items: List<Item>): List<Item> {
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

