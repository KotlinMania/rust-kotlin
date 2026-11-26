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

private val UNSIGNED_ARRAY_IDENTS: Set<String>
    get() = YamlRuleCatalogs.PathsConstsAndCAbi.unsignedArrayIdents

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
                sym.mergeSharedModuleSymbols(sharedInsp)
            }
            sym.visitFile(file)
            sym.selfParameterizedTraits.addAll(headerContext.crateSelfParameterizedTraits)
            closeTraitSupertraitMetadata(
                selfParameterizedTraits = sym.selfParameterizedTraits,
                traitSupertraits = sym.traitSupertraits,
                traitSupertraitBindings = sym.traitSupertraitBindings,
                traitDefaultlessAssocTypes = sym.traitDefaultlessAssocTypes,
                fallbackDefaultlessAssocTypes = headerContext.crateTraitDefaultlessAssocTypes,
                skipImplicitSupertrait = { superKt ->
                    superKt in RUST_STD_IMPLICIT_SUPERTRAITS && superKt !in sym.declaredTraitsByName
                },
            )
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

    private val ownerMatcher by lazy(LazyThreadSafetyMode.NONE) { BlanketImplAndOwnerMatcher(this) }

    internal fun emitFile(): String {
        val out = StringBuilder()
        closeEnumVariantTypeParamArities()

        // Partition impl blocks into those belonging to a local type declared in this file
        // vs standalone/external impl blocks, and hoist all `use` imports to the top.
        val localTypeImpls = linkedMapOf<String, MutableList<Item.Impl>>()
        val standaloneItems = mutableListOf<Item>()

        currentOmittedTestIdents = computeTransitivelyOmittedQuickcheckOrRandIdents(file.items, emptySet())
        val sharedModFile = TranslationSession.sharedModuleFile
        if (sharedModFile != null) {
            for (sharedItem in sharedModFile.items) {
                if (!itemHasDocOnlyCfg(sharedItem) && sharedItem is Item.Use) {
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
                continue
            }
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
                val matchedOwners = ownerMatcher.matchBlanketLocalIteratorImplOwners(item, localTypeImpls)
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
                val traitKt = item.traitPath?.path?.lastKotlinTypeName() ?: continue
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
        val indexedTopLevelFnKeys = FileFunctionNumericParamIndexer(this).indexAndApply(standaloneItems)

        val renderedItems = mutableListOf<String>()
        emitMissingAssocTypeAliases(renderedItems)

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
        if (headerContext.packageName?.startsWith("$activeCoreNs.ptr.") == true) {
            if (isRawPtrImplSource(srcPathNorm, standaloneItems, "const_ptr.rs", isMut = false) &&
                "ConstPtr" !in inspector.declaredTypeNames
            ) {
                CoreIntrinsicLowering.registerCoreImport(this, "$activeCoreNs.ptr.ConstPtr")
            }
            if (isRawPtrImplSource(srcPathNorm, standaloneItems, "mut_ptr.rs", isMut = true) &&
                "MutPtr" !in inspector.declaredTypeNames
            ) {
                CoreIntrinsicLowering.registerCoreImport(this, "$activeCoreNs.ptr.MutPtr")
            }
        }

        if (usesCurrentThreadToken && topLevelSignatureCounts["currentThreadBoundToken()"] == null) {
            renderedItems.add(0, "internal expect fun currentThreadBoundToken(): Any")
        }
        renderedItems.addAll(ffiCollector.renderInlineExpectDeclarations())

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
                        addAll(indexedTopLevelFnKeys)
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
        if (allBodyItems.isEmpty() &&
            ffiCollector.emitCompanionFiles().isEmpty() &&
            !headerContext.isDocExample &&
            !headerContext.isModuleRootSource &&
            !headerContext.isBatchCrateOutput
        ) {
            val unemittedItems = describeUnemittedSubstantiveItems(originalFile = originalFile, expandedFile = file)
            if (unemittedItems.isNotEmpty()) {
                throw IllegalArgumentException(
                    "Unsupported macro-only Rust source: macro invocations (${unemittedItems.joinToString(", ")}) " +
                        "have no standalone Kotlin item translation",
                )
            }
        }

        val bodyTokens = KotlinCodeTokenIndex(allBodyItems.joinToString("\n\n"))
        val needsUnsignedArrayOptIn = UNSIGNED_ARRAY_IDENTS.any(bodyTokens::hasIdent)

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

        val importLines =
            KotlinFileImportSynthesizer(this).synthesizeImports(
                activeCoreNs = activeCoreNs,
                bodyTokens = bodyTokens,
                standaloneMacrosExtraImports = standaloneMacros.extraImports,
            )
        if (importLines.isNotEmpty()) {
            out.append(importLines.joinToString("\n")).append("\n\n")
        }

        appendFileHeaderComments(
            out = out,
            emittedMacroNames = standaloneMacros.emittedMacroNames,
            hasBodyItems = allBodyItems.isNotEmpty(),
        )

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

    private fun closeEnumVariantTypeParamArities() {
        val allEnumItems =
            sameFileNestedItems(TranslationSession.sharedModuleFile?.items.orEmpty() + file.items)
                .filterIsInstance<Item.Enum>()
        for (enumItem in allEnumItems) {
            val enumKt = rustTypeIdentToKotlin(enumItem.ident.toString())
            val explicitParams = enumItem.generics.kotlinTypeParamNames()
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
    }

    private fun emitMissingAssocTypeAliases(renderedItems: MutableList<String>) {
        val nestedAllItems = sameFileNestedItems(file.items)
        val implementedOrImportedTraitNames =
            buildSet {
                for (it in nestedAllItems) {
                    when (it) {
                        is Item.Trait -> add(rustTypeIdentToKotlin(it.ident.toString()))
                        is Item.Impl -> {
                            val traitKt = it.traitPath?.path?.lastKotlinTypeName()
                            if (traitKt != null) add(traitKt)
                        }
                        else -> {}
                    }
                }
                addAll(importedSimpleNameToPkg.keys)
            }
        for ((assocName, assocDecl) in inspector.associatedTypeDecls) {
            val bindingCount =
                nestedAllItems.filterIsInstance<Item.Impl>().sumOf { implementation ->
                    implementation.items.count { it is ImplItem.AssocType && it.ident.toString() == assocName }
                }
            if (bindingCount > 1) continue
            if (assocName !in inspector.declaredTypeNames &&
                assocName !in implementedOrImportedTraitNames &&
                assocName !in KOTLIN_STDLIB_SHADOWED_IMPORTS
            ) {
                val targetType = renderType(assocDecl.ty, selfTypeName = null)
                if (targetType.isEmpty() || targetType == assocName) continue
                val candidateParams = assocDecl.generics.kotlinTypeParamNames().distinct()
                val strippedTarget = targetType.trim().removeSuffix("?")
                if (strippedTarget in candidateParams || strippedTarget in activeTypeParamNames) continue
                inspector.declaredTypeNames += assocName
                val usedParams = candidateParams.filter { containsWholeIdentifier(targetType, it) }
                val typeParamSuffix = if (usedParams.isEmpty()) "" else "<${usedParams.joinToString(", ")}>"
                renderedItems += "public typealias $assocName$typeParamSuffix = $targetType"
            }
        }
    }

    private fun isRawPtrImplSource(
        srcPathNorm: String?,
        standaloneItems: List<Item>,
        fileName: String,
        isMut: Boolean,
    ): Boolean =
        srcPathNorm?.endsWith("/$fileName") == true ||
            srcPathNorm == fileName ||
            (
                srcPathNorm == null &&
                    standaloneItems.any { item ->
                        item is Item.Impl &&
                            item.traitPath == null &&
                            (unwrapSynType(item.selfType) as? SynType.Ptr)?.let { ptr ->
                                (ptr.mutability is PointerMutability.Mut) == isMut
                            } == true
                    }
            )

    private fun appendFileHeaderComments(
        out: StringBuilder,
        emittedMacroNames: Set<String>,
        hasBodyItems: Boolean,
    ) {
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
                val trimmed = line.trimEnd('\r')
                rawInnerDocLines +=
                    if (innerDoc.style == TriviaStyle.InnerDocBlock) trimmed.trim().removePrefix("*") else trimmed
            }
        }
        for (sanitized in sanitizeDocLines(rawInnerDocLines)) {
            if (sanitized.isNotEmpty()) {
                fileDocLines += sanitized
            }
        }
        if (hasBodyItems) {
            for (origItem in originalFile.items) {
                if (origItem is Item.Macro && origItem.mac.path.isIdent("macro_rules") && !itemHasDocOnlyCfg(origItem)) {
                    val origMacroName = origItem.ident?.cleanIdent()
                    if (origMacroName != null && origMacroName in emittedMacroNames) continue
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
                if (docLine.isEmpty()) out.append("//\n") else out.append("// ").append(docLine).append('\n')
            }
            out.append('\n')
        }
    }

    internal fun extractDirectOwnerTypeName(
        selfTy: SynType,
        generics: Generics,
        scopeItems: List<Item> = file.items,
    ): String? = ownerMatcher.extractDirectOwnerTypeName(selfTy, generics, scopeItems)

    internal fun extractAttachableDirectOwnerTypeName(
        impl: Item.Impl,
        scopeItems: List<Item> = file.items,
    ): String? = ownerMatcher.extractAttachableDirectOwnerTypeName(impl, scopeItems)

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

private class FileFunctionNumericParamIndexer(
    private val emitter: KotlinJ2KEmitter,
) {
    private val inspector get() = emitter.inspector
    private val headerContext get() = emitter.headerContext

    fun indexAndApply(standaloneItems: List<Item>): Set<String> {
        val topLevelFnParamKinds = linkedMapOf<String, List<NumericKind?>?>()
        val fnScanItems = TranslationSession.sharedModuleFile?.items ?: standaloneItems
        for (item in fnScanItems) {
            if (item is Item.Fn && !itemHasDocOnlyCfg(item)) {
                emitter.extractFnParamNumericKinds(item, selfTypeName = null)?.let { (fnName, kinds) ->
                    topLevelFnParamKinds.recordTopLevelFn(fnName, kinds)
                }
            } else if (item is Item.ForeignMod && !itemHasDocOnlyCfg(item)) {
                val prevExtern = emitter.inExternAbiSignature
                emitter.inExternAbiSignature = true
                try {
                    for (foreign in item.items) {
                        if (foreign is ForeignItem.Fn && !foreignItemHasDocOnlyCfg(foreign)) {
                            emitter.extractSigParamNumericKinds(foreign.sig, selfTypeName = null)?.let { (defaultKtName, kinds) ->
                                val rawForeignFn = foreign.sig.cleanFnName()
                                val fnName = inspector.collidingTopLevelForeignFnRenames[rawForeignFn] ?: defaultKtName
                                topLevelFnParamKinds.recordTopLevelFn(fnName, kinds)
                            }
                        }
                    }
                } finally {
                    emitter.inExternAbiSignature = prevExtern
                }
            }
        }
        val implScanItems = TranslationSession.sharedModuleFile?.items ?: emitter.file.items
        val implScanTraitByName = linkedMapOf<String, Item.Trait>()
        implScanTraitByName.putAll(inspector.declaredTraitsByName)
        for (item in implScanItems + emitter.file.items) {
            if (item is Item.Trait && !itemHasDocOnlyCfg(item)) {
                val tName = rustTypeIdentToKotlin(item.ident.toString())
                if (tName !in implScanTraitByName) implScanTraitByName[tName] = item
            }
        }
        val implTraitsByOwner = linkedMapOf<String, MutableSet<String>>()
        for (item in implScanItems) {
            if (item is Item.Impl && item.generics.params.isEmpty() && !itemHasDocOnlyCfg(item)) {
                val ownerType = emitter.extractDirectOwnerTypeName(item.selfType, item.generics) ?: continue
                val traitKt = item.traitPath?.path?.lastKotlinTypeName()
                if (traitKt != null) {
                    implTraitsByOwner.getOrPut(ownerType, ::linkedSetOf).add(traitKt)
                }
                for (implItem in item.items) {
                    if (implItem is ImplItem.Fn && !implItemHasDocOnlyCfg(implItem)) {
                        emitter.extractSigParamNumericKinds(implItem.sig, ownerType)?.let { (fnName, kinds) ->
                            topLevelFnParamKinds.recordOverloadable("$ownerType.$fnName", kinds)
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
                        val (fnName, kinds) = emitter.extractSigParamNumericKinds(tItem.sig, ownerType) ?: continue
                        val qualKey = "$ownerType.$fnName"
                        if (qualKey !in topLevelFnParamKinds) topLevelFnParamKinds[qualKey] = kinds
                    }
                }
            }
        }
        for ((traitName, traitItem) in implScanTraitByName) {
            for (tItem in traitItem.items) {
                if (tItem is TraitItem.Fn && !traitItemHasDocOnlyCfg(tItem) && !tItem.sig.hasSelfReceiver()) {
                    val (fnName, kinds) = emitter.extractSigParamNumericKinds(tItem.sig, traitName) ?: continue
                    val qualKey = "$traitName.$fnName"
                    if (qualKey !in topLevelFnParamKinds) topLevelFnParamKinds[qualKey] = kinds
                }
            }
        }
        for (aliasName in inspector.typeAliasTargets.keys) {
            var cur = aliasName
            val seen = hashSetOf<String>()
            while (seen.add(cur) && seen.size <= 8) {
                val ty = inspector.typeAliasTargets[cur] ?: break
                val next =
                    unwrapSynType(ty)
                        .unqualifiedPath()
                        ?.lastCleanIdent()
                        ?.let(::rustTypeIdentToKotlin) ?: break
                if (next == cur) break
                cur = next
            }
            if (cur != aliasName) {
                val ownerPrefix = "$cur."
                for ((k, v) in topLevelFnParamKinds.entries.map { it.key to it.value }) {
                    if (k.startsWith(ownerPrefix)) {
                        val aliasKey = "$aliasName.${k.removePrefix(ownerPrefix)}"
                        if (aliasKey !in topLevelFnParamKinds) topLevelFnParamKinds[aliasKey] = v
                    }
                }
            }
        }
        for ((crateFnKey, kindCodes) in headerContext.crateFnParamNumericKinds) {
            if (crateFnKey !in topLevelFnParamKinds) {
                topLevelFnParamKinds[crateFnKey] =
                    kindCodes.map { emitter.numericKindFromScannerCode(it.takeIf(String::isNotEmpty)) }
            }
        }
        emitter.currentFunctionParamNumericKinds =
            topLevelFnParamKinds.entries
                .mapNotNull { (k, v) -> v?.let { k to it } }
                .toMap()
        return topLevelFnParamKinds.keys.toSet()
    }

    private fun MutableMap<String, List<NumericKind?>?>.recordOverloadable(
        key: String,
        kinds: List<NumericKind?>,
    ) {
        this[key] = if (key in this) null else kinds
    }

    private fun MutableMap<String, List<NumericKind?>?>.recordTopLevelFn(
        fnName: String,
        kinds: List<NumericKind?>,
    ) {
        recordOverloadable(fnName, kinds)
        recordOverloadable("$fnName#${kinds.size}", kinds)
    }
}

private class KotlinFileImportSynthesizer(
    private val emitter: KotlinJ2KEmitter,
) {
    private val inspector get() = emitter.inspector
    private val headerContext get() = emitter.headerContext

    fun synthesizeImports(
        activeCoreNs: String,
        bodyTokens: KotlinCodeTokenIndex,
        standaloneMacrosExtraImports: Set<String>,
    ): List<String> {
        val useImportLines =
            inspector.allUseItems
                .flatMap { emitter.renderItemUse(it, indent = "", bodyTokensForPubUseFilter = bodyTokens).lines() }
                .map(String::trim)
                .filter(String::isNotEmpty)

        val extraImports = mutableListOf<String>()
        if (emitter.usesSourceResult && emitter.usesTaggedCoreResult() && !emitter.hasDeclaredResultEnum()) {
            extraImports +=
                if (emitter.hasConflictingResultBinding()) {
                    "import $activeCoreNs.result.Result as RustResult"
                } else {
                    "import $activeCoreNs.result.Result"
                }
        }
        val combinedExtraTypeImports = linkedSetOf<String>()
        combinedExtraTypeImports.addAll(emitter.extraTypePathImports)
        for (macroImp in standaloneMacrosExtraImports) {
            combinedExtraTypeImports += if (macroImp.startsWith("import ")) macroImp else "import $macroImp"
        }

        val usesExternalCore =
            "core" in headerContext.crateNamespaces ||
                headerContext.packageName == activeCoreNs ||
                headerContext.packageName?.startsWith("$activeCoreNs.") == true
        val stdlibOverloadExtImports = hashSetOf<String>()
        for ((symSimple, symPkg) in buildAutoCoreSymbols(activeCoreNs, usesExternalCore)) {
            val isMemGet = symPkg == "$activeCoreNs.mem" && symSimple == "get"
            if (canSynthesizeSymbolImport(symSimple, symPkg, allowDeclaredTopFn = isMemGet) &&
                bodyTokens.hasIdent(symSimple)
            ) {
                val imp = "import $symPkg.$symSimple"
                if (isMemGet) stdlibOverloadExtImports += imp
                combinedExtraTypeImports += imp
            }
        }
        if (usesExternalCore && !headerContext.rawAbiMode) {
            synthesizeCoreEnumVariantImports(activeCoreNs, bodyTokens, combinedExtraTypeImports)
            if (headerContext.packageName != "$activeCoreNs.mem") {
                for (compIdx in 6..minOf(bodyTokens.maxTupleDestructuringArity, 12)) {
                    val compImp = "import $activeCoreNs.mem.component$compIdx"
                    if (compImp !in extraImports) extraImports += compImp
                }
            }
        }
        if (emitter.usesRustChar &&
            headerContext.packageName != "$activeCoreNs.char" &&
            "RustChar" !in inspector.declaredTypeNames
        ) {
            combinedExtraTypeImports += "import $activeCoreNs.char.RustChar"
        }
        if (usesExternalCore && !headerContext.rawAbiMode) {
            for ((extName, extPkg) in buildCoreExtensionSymbols(activeCoreNs, bodyTokens)) {
                if (canSynthesizeSymbolImport(extName, extPkg, allowDeclaredTopFn = extName == "clone") &&
                    bodyTokens.hasMemberCall(extName)
                ) {
                    val imp = "import $extPkg.$extName"
                    stdlibOverloadExtImports += imp
                    combinedExtraTypeImports += imp
                }
            }
        }
        synthesizeExperimentalBitwiseImports(bodyTokens, combinedExtraTypeImports)
        synthesizeImportedTypeExtensionImports(bodyTokens, combinedExtraTypeImports)

        extraImports.addAll(
            combinedExtraTypeImports.filter { imp ->
                shouldRetainSynthesizedImport(imp, bodyTokens)
            },
        )
        synthesizeSpecialFallbackImports(extraImports, bodyTokens)

        val seenImportedSimpleNames = hashSetOf<String>()
        val importLines =
            (useImportLines + extraImports)
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
                .filter { line ->
                    if (!headerContext.rawAbiMode &&
                        headerContext.packageName?.startsWith("io.github.kotlinmania.rustlang.") == true
                    ) {
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
                        seenImportedSimpleNames.add(boundName) || line in stdlibOverloadExtImports
                    }
                }.let { filtered ->
                    if (headerContext.isBatchCrateOutput || TranslationSession.finalStructs) {
                        sortImportsForKtlint(filtered)
                    } else {
                        filtered.sorted()
                    }
                }
        emitter.ffiCollector.recordImportLines(importLines)
        emitter.ffiCollector.recordLocalTypeNames(inspector.declaredTypeNames)
        return importLines
    }

    private fun canSynthesizeSymbolImport(
        symSimple: String,
        symPkg: String,
        allowDeclaredTopFn: Boolean = false,
    ): Boolean =
        headerContext.packageName != symPkg &&
            symSimple !in inspector.declaredTypeNames &&
            symSimple !in inspector.declaredConstNames.values &&
            symSimple !in emitter.importedSimpleNameToPkg &&
            (allowDeclaredTopFn || !emitter.isDeclaredTopLevelFnKotlinName(symSimple))

    private fun buildAutoCoreSymbols(
        activeCoreNs: String,
        usesExternalCore: Boolean,
    ): List<Pair<String, String>> {
        if (headerContext.isDocExample) return emptyList()
        val activeAllocNs = CoreIntrinsicLowering.resolveAllocNamespace(headerContext)
        val hasCoreCrate = "core" in headerContext.crateNamespaces
        return buildList {
            var emittedAlwaysFfi = false
            for (route in YamlRuleCatalogs.PathsConstsAndCAbi.coreAutoImportRoutes) {
                if (route.condition != "always" && !emittedAlwaysFfi) {
                    emittedAlwaysFfi = true
                    if (!headerContext.rawAbiMode) {
                        for (ffiSym in CoreIntrinsicLowering.STDLIB_CORE_FFI_SYMBOLS.values.distinct() + "CString") {
                            if (emitter.resolveBestSubpackageForSymbol(ffiSym) == null) {
                                add(ffiSym to CoreIntrinsicLowering.defaultStdlibFfiSymbolPackage(ffiSym, activeCoreNs, activeAllocNs))
                            }
                        }
                    }
                }
                val active =
                    when (route.condition) {
                        "always" -> true
                        "externalCore" -> usesExternalCore
                        "coreOrScalarChar" -> hasCoreCrate || headerContext.scalarChar
                        "coreManagedAbi" -> hasCoreCrate && !headerContext.rawAbiMode
                        else -> false
                    }
                if (active) {
                    val pkg = route.targetPackage(activeCoreNs)
                    for (sym in route.symbols) add(sym to pkg)
                }
            }
        }
    }

    private fun synthesizeCoreEnumVariantImports(
        activeCoreNs: String,
        bodyTokens: KotlinCodeTokenIndex,
        combinedExtraTypeImports: MutableSet<String>,
    ) {
        for ((ownerSubpkg, variants) in YamlRuleCatalogs.PathsConstsAndCAbi.coreEnumVariantImports) {
            val variantOwner = "$activeCoreNs.$ownerSubpkg"
            val ownerPkg = variantOwner.substringBeforeLast('.')
            for (variantName in variants) {
                if (canSynthesizeSymbolImport(variantName, ownerPkg) &&
                    variantName !in inspector.enumVariantSimpleNames &&
                    variantName !in inspector.importedEnumVariantSimpleNames &&
                    bodyTokens.hasIdent(variantName)
                ) {
                    combinedExtraTypeImports += "import $variantOwner.$variantName"
                }
            }
        }
    }

    private fun buildCoreExtensionSymbols(
        activeCoreNs: String,
        bodyTokens: KotlinCodeTokenIndex,
    ): List<Pair<String, String>> =
        buildList {
            for (route in YamlRuleCatalogs.PathsConstsAndCAbi.coreExtensionImportRoutes) {
                val active =
                    when (route.condition) {
                        "always" -> true
                        "atomicIdent" -> bodyTokens.hasAtomicTypeIdent
                        "mutPtrIdent" ->
                            bodyTokens.hasIdent("MutPtr") ||
                                bodyTokens.hasIdent("asMutPtr") ||
                                bodyTokens.hasIdent("nullMut")
                        "constPtrIdent" -> bodyTokens.hasIdent("ConstPtr") || bodyTokens.hasIdent("asPtr")
                        else -> false
                    }
                if (active) {
                    val pkg = route.targetPackage(activeCoreNs)
                    for (sym in route.symbols) add(sym to pkg)
                }
            }
        }

    private fun synthesizeExperimentalBitwiseImports(
        bodyTokens: KotlinCodeTokenIndex,
        combinedExtraTypeImports: MutableSet<String>,
    ) {
        if (!bodyTokens.hasIdent("Byte") && !bodyTokens.hasIdent("Short")) {
            return
        }
        val expOps =
            buildList {
                if (bodyTokens.hasZeroArgDotInvCall) add("inv")
                for (op in EXPERIMENTAL_INFIX_BITWISE_OPS) {
                    if (bodyTokens.hasIdent(op)) add(op)
                }
            }
        for (op in expOps) {
            if (!emitter.isDeclaredTopLevelFnKotlinName(op) && op !in emitter.importedSimpleNameToPkg) {
                combinedExtraTypeImports += "import kotlin.experimental.$op"
            }
        }
    }

    private fun synthesizeImportedTypeExtensionImports(
        bodyTokens: KotlinCodeTokenIndex,
        combinedExtraTypeImports: MutableSet<String>,
    ) {
        val nsPrefixForExt = emitter.currentNamespacePrefix()
        for ((symSimple, symPkg) in emitter.importedSimpleNameToPkg.toList()) {
            if (symSimple.firstOrNull()?.isUpperCase() != true || symPkg == headerContext.packageName) continue
            val camelFn = symSimple.replaceFirstChar { it.lowercaseChar() }
            if (!canSynthesizeSymbolImport(camelFn, symPkg) || !bodyTokens.hasMemberCall(camelFn)) {
                continue
            }
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
                combinedExtraTypeImports += "import $symPkg.$camelFn"
            }
        }
    }

    private fun shouldRetainSynthesizedImport(imp: String, bodyTokens: KotlinCodeTokenIndex): Boolean {
        val afterImport = imp.removePrefix("import ").trim()
        val impPath = afterImport.substringBefore(" as ").trim()
        val rawSimple = impPath.substringAfterLast('.')
        val boundSimple = if (" as " in afterImport) afterImport.substringAfter(" as ").trim() else rawSimple
        val targetPkg = impPath.substringBeforeLast('.', missingDelimiterValue = "")
        val isSingleUpperTypeParam = boundSimple.length == 1 && boundSimple[0] in 'A'..'Z'
        val isEnumVariantShadowed =
            (boundSimple in inspector.enumVariantSimpleNames || boundSimple in inspector.importedEnumVariantSimpleNames) &&
                !impPath.endsWith(".Result.$rawSimple") &&
                impPath != "Result.$rawSimple" &&
                !impPath.endsWith(".Option.$rawSimple") &&
                impPath != "Option.$rawSimple"
        return targetPkg != headerContext.packageName &&
            boundSimple !in inspector.declaredTypeNames &&
            !isSingleUpperTypeParam &&
            !isEnumVariantShadowed &&
            bodyTokens.hasIdent(boundSimple)
    }

    private fun synthesizeSpecialFallbackImports(
        extraImports: MutableList<String>,
        bodyTokens: KotlinCodeTokenIndex,
    ) {
        val projNs = headerContext.projectNamespace.nonBlankNormalizedNs()
        val libcNs = FfiInteropCollector.resolveLibcNamespace(headerContext)
        if (libcNs != null &&
            headerContext.packageName != libcNs &&
            projNs == libcNs &&
            headerContext.crateSymbolSubpackages.isNotEmpty() &&
            "CEnumRepr" !in inspector.declaredTypeNames &&
            "CEnumRepr" !in emitter.importedSimpleNameToPkg &&
            bodyTokens.hasIdent("CEnumRepr")
        ) {
            emitter.resolveBestSubpackageForSymbol("CEnumRepr")?.let { cenumSub ->
                val cenumPkg = if (cenumSub.isEmpty()) libcNs else "$libcNs.$cenumSub"
                if (headerContext.packageName != cenumPkg) {
                    extraImports += "import $cenumPkg.CEnumRepr"
                }
            }
        }
        if (emitter.usesQuoteUnderlyingSpan) {
            val quoteNs =
                headerContext.crateNamespaces["quote"].nonBlankNormalizedNs()
                    ?: headerContext.projectGroup.nonBlankNormalizedNs()?.let { "$it.quote" }
            if (quoteNs != null && headerContext.packageName != quoteNs) {
                extraImports += "import $quoteNs.span as quoteSpan"
            }
        }
        if (emitter.aliasedUnderlyingSelfCalls.isNotEmpty()) {
            val fallbackPkg = projNs ?: headerContext.packageName.nonBlankNormalizedNs()
            if (fallbackPkg != null) {
                for ((methodKt, aliasKt) in emitter.aliasedUnderlyingSelfCalls) {
                    extraImports += "import $fallbackPkg.$methodKt as $aliasKt"
                }
            }
        }
    }

    private fun String?.nonBlankNormalizedNs(): String? =
        this?.trim()?.removeSuffix(".")?.takeIf(String::isNotEmpty)

    private companion object {
        val EXPERIMENTAL_INFIX_BITWISE_OPS = listOf("and", "or", "xor")
    }
}

private class BlanketImplAndOwnerMatcher(
    private val emitter: KotlinJ2KEmitter,
) {
    private val inspector get() = emitter.inspector

    fun extractDirectOwnerTypeName(
        selfTy: SynType,
        generics: Generics,
        scopeItems: List<Item>,
    ): String? {
        val typeParamNames = generics.kotlinTypeParamNames().toSet()
        return when (val unwrapped = unwrapSynType(selfTy)) {
            is SynType.Reference -> extractDirectOwnerTypeName(unwrapped.elem, generics, scopeItems)
            is SynType.Path -> {
                val seg = unwrapped.unqualifiedPath()?.singleSegment() ?: return null
                val rawSeg = seg.ident.toString()
                if (rawSeg == "Box" && "Box" !in inspector.declaredTypeNames) {
                    val innerTy = seg.arguments.firstAngleTypeArg() ?: return null
                    return extractDirectOwnerTypeName(innerTy, generics, scopeItems)
                }
                if (rawSeg in PRIMITIVE_TYPE_NAMES) return null
                val name = rustTypeIdentToKotlin(rawSeg)
                val declarationGenerics =
                    findDeclGenericsForOwner(scopeItems) { it == rawSeg }
                        ?: findDeclGenericsForOwner(emitter.file.items) { it == rawSeg }
                if (declarationGenerics != null &&
                    !matchesUnspecializedOwnerGenerics(seg.arguments, declarationGenerics)
                ) {
                    return null
                }
                if (name in typeParamNames) null else name
            }
            else -> null
        }
    }

    fun extractAttachableDirectOwnerTypeName(
        impl: Item.Impl,
        scopeItems: List<Item>,
    ): String? {
        val ownerType = extractDirectOwnerTypeName(impl.selfType, impl.generics, scopeItems) ?: return null
        val ownerDeclGenerics =
            findDeclGenericsForOwner(scopeItems) { rustTypeIdentToKotlin(it) == ownerType }
                ?: findDeclGenericsForOwner(emitter.file.items) { rustTypeIdentToKotlin(it) == ownerType }
                ?: return ownerType
        val ownerTypeParamNames = emitter.collectGenericTypeParamNames(ownerDeclGenerics).toSet()
        if (emitter.collectGenericTypeParamNames(impl.generics).any { it !in ownerTypeParamNames }) {
            return null
        }
        if (impl.traitPath != null &&
            declaresExtraNonImplicitImplTraitBounds(impl, ownerDeclGenerics, emitter::isImplicitStdlibSupertraitPath)
        ) {
            return null
        }
        return ownerType
    }

    fun matchBlanketLocalIteratorImplOwners(
        item: Item.Impl,
        localTypeImpls: Map<String, List<Item.Impl>>,
    ): List<String> {
        val blanketTraitKt = item.traitPath?.path?.lastKotlinTypeName() ?: return emptyList()
        if (blanketTraitKt !in inspector.declaredTraitsByName) return emptyList()
        val firstSelfSeg = unwrapSynType(item.selfType).unqualifiedPath()?.singleSegment() ?: return emptyList()
        val blanketParam = rustTypeIdentToKotlin(firstSelfSeg.ident.toString())
        if (blanketParam !in emitter.collectGenericTypeParamNames(item.generics)) return emptyList()

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
                val angle = lastSeg.arguments as? PathArguments.AngleBracketed ?: continue
                for (ga in angle.args.toList()) {
                    if (ga is GenericArgument.AssocTypeArg && ga.assoc.ident.toString() == "Item") {
                        expectedItemSpec = emitter.renderType(ga.assoc.ty, null) to classifySynRefKind(ga.assoc.ty)
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
        for (pred in item.generics.wherePredicatesList()) {
            if (pred is WherePredicate.TypePredicate && emitter.renderType(pred.boundedTy, null) == blanketParam) {
                if (!inspectBoundList(pred.bounds.toList())) return emptyList()
            }
        }
        val targetSpec = expectedItemSpec ?: return emptyList()
        if (requiredStdTraits.isEmpty()) return emptyList()

        return inspector.attachableTypeNames.filter { structName ->
            val wrapsTrait =
                inspector.structFieldSynTypesByType[structName]
                    ?.values
                    ?.any { emitter.renderType(it, null).substringBefore('<').trim() == blanketTraitKt } == true
            if (wrapsTrait) return@filter false
            val candidateImpls = localTypeImpls[structName].orEmpty()
            val implementedTraits =
                candidateImpls
                    .mapNotNull { c ->
                        if (isDocOnlyCfgAttribute(c.attrs)) null else c.traitPath?.path?.lastKotlinTypeName()
                    }.toSet()
            if (!implementedTraits.containsAll(requiredStdTraits)) return@filter false
            var candidateIterSpec: Pair<String, String>? = null
            for (c in candidateImpls) {
                if (!isDocOnlyCfgAttribute(c.attrs) && c.traitPath?.path?.lastIdentText() == "Iterator") {
                    for (sub in c.items) {
                        if (sub is ImplItem.AssocType && sub.ident.toString() == "Item") {
                            candidateIterSpec = emitter.renderType(sub.ty, null) to classifySynRefKind(sub.ty)
                        }
                    }
                }
            }
            candidateIterSpec == targetSpec
        }
    }

    private fun findDeclGenericsForOwner(
        items: List<Item>,
        matchesRawIdent: (String) -> Boolean,
    ): Generics? =
        items.firstNotNullOfOrNull { declaration ->
            when (declaration) {
                is Item.Struct -> declaration.generics.takeIf { matchesRawIdent(declaration.ident.toString()) }
                is Item.Enum -> declaration.generics.takeIf { matchesRawIdent(declaration.ident.toString()) }
                is Item.Union -> declaration.generics.takeIf { matchesRawIdent(declaration.ident.toString()) }
                else -> null
            }
        }

    private fun classifySynRefKind(ty: SynType): String =
        when (val u = unwrapSynType(ty)) {
            is SynType.Reference -> if (u.mutability != null) "mut_ref" else "shared_ref"
            else -> "owned"
        }
}

internal fun topologicallySortConstItems(items: List<Item>): List<Item> {
    val constIndices = mutableListOf<Int>()
    val constItems = mutableListOf<Item.Const>()
    val indexByRawName = linkedMapOf<String, Int>()
    for ((idx, item) in items.withIndex()) {
        if (item is Item.Const) {
            val raw = item.ident.cleanIdent()
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
                    p.lastCleanIdent()?.let { raw ->
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
