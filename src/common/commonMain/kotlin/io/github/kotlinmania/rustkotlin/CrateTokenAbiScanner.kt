package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.io.files.SystemFileSystem
import io.github.kotlinmania.procmacro2.Delimiter
import io.github.kotlinmania.procmacro2.TokenStream
import io.github.kotlinmania.procmacro2.TokenTree
import io.github.kotlinmania.syn.GenericArgument
import io.github.kotlinmania.syn.Item
import io.github.kotlinmania.syn.ModContent
import io.github.kotlinmania.syn.PathArguments
import io.github.kotlinmania.syn.TraitItem
import io.github.kotlinmania.syn.TypeParamBound
import io.github.kotlinmania.io.files.Path as KmPath

internal object CrateTraitAstRegistry {
    private var registry: Map<String, Map<String, Item.Trait>> = emptyMap()

    fun register(key: String, asts: Map<String, Item.Trait>) {
        if (asts.isEmpty()) return
        registry =
            if (registry.size >= 64) {
                mapOf(key to asts)
            } else {
                registry + (key to asts)
            }
    }

    fun lookup(key: String?): Map<String, Item.Trait> =
        if (key == null) emptyMap() else registry[key].orEmpty()
}

internal data class CrateRawAbiMetadata(
    val selfCrateAliases: Set<String> = emptySet(),
    val includedSubdirModuleMap: Map<String, String> = emptyMap(),
    val rootSelfDirModuleAliases: Set<String> = emptySet(),
    val rootExternalModuleReexports: Map<String, String> = emptyMap(),
    val typeAliasDefaults: Map<String, String> = emptyMap(),
    val optionFnTypes: Set<String> = emptySet(),
    val numericTypeKinds: Map<String, String> = emptyMap(),
    val pointerTypeAliases: Map<String, String> = emptyMap(),
    val structFieldTypes: Map<String, String> = emptyMap(),
    val fnReturnTypes: Map<String, String> = emptyMap(),
    val symbolSubpackages: Map<String, List<String>> = emptyMap(),
    val subpackageRawLeafNames: Map<String, String> = emptyMap(),
    val valueParamPackTemplates: Map<String, String> = emptyMap(),
    val valueReturnUnpackTemplates: Map<String, String> = emptyMap(),
    val fnPtrInvokeTemplates: Map<String, String> = emptyMap(),
    val fnParamNumericKinds: Map<String, List<String>> = emptyMap(),
    val sharedInlineModuleRelFiles: Set<String> = emptySet(),
    val traitDefaultlessAssocTypes: Map<String, List<String>> = emptyMap(),
    val traitResultMethods: Map<String, List<String>> = emptyMap(),
    val selfParameterizedTraits: Set<String> = emptySet(),
    val traitSupertraits: Map<String, List<String>> = emptyMap(),
    val traitAstRegistryKey: String? = null,
    val fnConstParamNames: Map<String, Pair<Int, List<String>>> = emptyMap(),
    val enumVariantTypeArity: Map<String, Int> = emptyMap(),
    val unitEnumVariants: Set<String> = emptySet(),
)

internal data class ExternalCrateAbiMetadata(
    val symbolPackages: Map<String, Map<String, List<String>>> = emptyMap(),
    val twoFieldResultFns: Set<String> = emptySet(),
    val nullableReturnFns: Set<String> = emptySet(),
    val fnConstParams: Map<String, Pair<Int, List<String>>> = emptyMap(),
    val tupleStructArity: Map<String, Int> = emptyMap(),
    val enumVariantTypeArity: Map<String, Int> = emptyMap(),
    val unitEnumVariants: Set<String> = emptySet(),
)

private var diskCrateRawAbiMetadataCache: Map<String, CrateRawAbiMetadata> = emptyMap()
private var diskExternalCrateAbiCache: Map<String, ExternalCrateAbiMetadata> = emptyMap()

internal fun discoverCrateRawAbiMetadataFromDisk(
    inputPath: String?,
    sourceRoot: String? = null,
    rawAbiMode: Boolean = false,
    splitModuleRoots: Boolean = false,
    finalStructs: Boolean = false,
): CrateRawAbiMetadata {
    val resolvedRoot = KmPathFileTreeNavigator.resolveCrateSrcRootFromDisk(inputPath, sourceRoot) ?: return CrateRawAbiMetadata()
    val cacheKey = "$resolvedRoot|$rawAbiMode|$splitModuleRoots|$finalStructs|${TranslationSession.cfgTargetProfile}"
    diskCrateRawAbiMetadataCache[cacheKey]?.let { return it }

    val normResolvedRoot = resolvedRoot.toString().replace('\\', '/').trimEnd('/')
    val collectedSources = mutableListOf<Pair<String, String>>()

    fun collectDir(dir: KmPath) {
        val entries = SystemFileSystem.list(dir).sortedBy { it.toString() }
        for (entry in entries) {
            val normEntry = entry.toString().replace('\\', '/')
            val relEntry =
                if (normEntry.startsWith("$normResolvedRoot/")) {
                    normEntry.removePrefix("$normResolvedRoot/")
                } else {
                    entry.name
                }
            if (isUnsupportedTargetRustRelPath(relEntry, TranslationSession.cfgTargetProfile)) {
                continue
            }
            if (isKmDirectory(entry)) {
                collectDir(entry)
            } else if (isKmRegularFile(entry) && entry.name.endsWith(".rs", ignoreCase = true)) {
                collectedSources += relEntry to readKmFileText(entry)
            }
        }
    }
    collectDir(resolvedRoot)

    val metadata =
        buildCrateRawAbiMetadataFromSources(
            sources = collectedSources,
            rawAbiMode = rawAbiMode,
            splitModuleRoots = splitModuleRoots,
            finalStructs = finalStructs,
        )
    if (diskCrateRawAbiMetadataCache.size >= 32) {
        diskCrateRawAbiMetadataCache = mapOf(cacheKey to metadata)
    } else {
        diskCrateRawAbiMetadataCache = diskCrateRawAbiMetadataCache + (cacheKey to metadata)
    }
    return metadata
}

internal data class RawFieldTokenSpec(
    val ktName: String,
    val typeTokens: List<TokenTree>,
)

internal data class RawRecordTokenDecl(
    val subpkg: String,
    val ktName: String,
    val isUnion: Boolean,
    val packAlign: Int?,
    val fields: List<RawFieldTokenSpec>,
)

internal data class RawFnTypeTokenSpec(
    val subpkg: String,
    val aliasKtName: String,
    val samKtName: String,
    val isOptionWrapped: Boolean,
    val paramTokenLists: List<List<TokenTree>>,
    val retTokens: List<TokenTree>,
)

private sealed class ComputedFieldShape {
    abstract val sizeBytes: Int
    abstract val alignBytes: Int

    data class Scalar(
        override val sizeBytes: Int,
        override val alignBytes: Int,
        val kind: String,
    ) : ComputedFieldShape()

    data class SimpleEnum(
        override val sizeBytes: Int,
        override val alignBytes: Int,
        val enumKtName: String,
        val reprKind: String,
    ) : ComputedFieldShape()

    data class CallbackPtr(
        val samKtName: String,
        val isNullable: Boolean,
    ) : ComputedFieldShape() {
        override val sizeBytes: Int = 8
        override val alignBytes: Int = 8
    }

    data class FixedArray(
        val elemKind: String,
        val elemSizeBytes: Int,
        val count: Int,
    ) : ComputedFieldShape() {
        override val sizeBytes: Int = elemSizeBytes * count
        override val alignBytes: Int = elemSizeBytes
    }

    data class NestedRecord(
        val recordKtName: String,
        val layout: ComputedRecordLayout,
    ) : ComputedFieldShape() {
        override val sizeBytes: Int = layout.sizeBytes
        override val alignBytes: Int = layout.alignBytes
    }
}

private data class ComputedFieldLayout(
    val ktName: String,
    val offsetBytes: Int,
    val shape: ComputedFieldShape,
)

private data class ComputedRecordLayout(
    val ktName: String,
    val subpkg: String,
    val isUnion: Boolean,
    val sizeBytes: Int,
    val alignBytes: Int,
    val fields: List<ComputedFieldLayout>,
)

internal data class ScannerScalarDescriptor(
    val kind: String,
    val kotlinType: String,
    val defaultExpr: String,
    val arrayType: String?,
    val arrayFactory: String?,
    val sizeAlign: Pair<Int, Int>?,
)

internal val SCANNER_SCALAR_DESCRIPTORS_BY_KIND: Map<String, ScannerScalarDescriptor> =
    listOf(
        ScannerScalarDescriptor("I8", "Byte", "0", "ByteArray", "byteArrayOf", 1 to 1),
        ScannerScalarDescriptor("U8", "UByte", "0u", "UByteArray", "ubyteArrayOf", 1 to 1),
        ScannerScalarDescriptor("I16", "Short", "0", "ShortArray", "shortArrayOf", 2 to 2),
        ScannerScalarDescriptor("U16", "UShort", "0u", "UShortArray", "ushortArrayOf", 2 to 2),
        ScannerScalarDescriptor("I32", "Int", "0", "IntArray", "intArrayOf", 4 to 4),
        ScannerScalarDescriptor("U32", "UInt", "0u", "UIntArray", "uintArrayOf", 4 to 4),
        ScannerScalarDescriptor("I64", "Long", "0L", "LongArray", null, 8 to 8),
        ScannerScalarDescriptor("U64", "ULong", "0uL", "ULongArray", null, 8 to 8),
        ScannerScalarDescriptor("I128", "I128", "I128()", null, null, 16 to 16),
        ScannerScalarDescriptor("U128", "U128", "U128()", null, null, 16 to 16),
        ScannerScalarDescriptor("F32", "Float", "0.0f", "FloatArray", null, 4 to 4),
        ScannerScalarDescriptor("F64", "Double", "0.0", "DoubleArray", null, 8 to 8),
        ScannerScalarDescriptor("BOOL", "Boolean", "false", "BooleanArray", "booleanArrayOf", 1 to 1),
    ).associateBy { it.kind }

internal val SCANNER_RUST_TO_SCALAR_KIND: Map<String, String> =
    mapOf(
        "i8" to "I8",
        "u8" to "U8",
        "i16" to "I16",
        "u16" to "U16",
        "i32" to "I32",
        "u32" to "U32",
        "i64" to "I64",
        "u64" to "U64",
        "i128" to "I128",
        "u128" to "U128",
        "f32" to "F32",
        "f64" to "F64",
        "bool" to "BOOL",
    )

internal val SCANNER_C_ALIAS_TO_SCALAR_KIND: Map<String, String> =
    mapOf(
        "c_char" to "I8",
        "c_schar" to "I8",
        "c_uchar" to "U8",
        "c_short" to "I16",
        "c_ushort" to "U16",
        "c_int" to "I32",
        "c_uint" to "U32",
        "c_long" to "I64",
        "c_ulong" to "U64",
        "c_longlong" to "I64",
        "c_ulonglong" to "U64",
        "c_float" to "F32",
        "c_double" to "F64",
    )

internal fun <K, V> MutableMap<K, V>.putIfMissing(
    key: K,
    value: V,
) {
    if (key !in this) {
        this[key] = value
    }
}

internal fun MutableMap<String, String>.putScopedAndSimpleIfMissing(
    subpkg: String,
    simpleKey: String,
    value: String,
) {
    putIfMissing(simpleKey, value)
    if (subpkg.isNotEmpty()) {
        putIfMissing("$subpkg.$simpleKey", value)
    }
}

internal fun buildCrateRawAbiMetadataFromSources(
    sources: List<Pair<String, String>>,
    rawAbiMode: Boolean = false,
    splitModuleRoots: Boolean = false,
    finalStructs: Boolean = false,
): CrateRawAbiMetadata {
    if (sources.isEmpty()) return CrateRawAbiMetadata()

    val prevRawAbi = TranslationSession.rawAbiMode
    val prevFinalStructs = TranslationSession.finalStructs
    val prevRelDir = TranslationSession.currentRelativeSourceDir
    TranslationSession.rawAbiMode = rawAbiMode
    TranslationSession.finalStructs = finalStructs
    try {
        return scanSourcesWithActiveSession(sources, rawAbiMode, splitModuleRoots, finalStructs)
    } finally {
        TranslationSession.rawAbiMode = prevRawAbi
        TranslationSession.finalStructs = prevFinalStructs
        TranslationSession.currentRelativeSourceDir = prevRelDir
    }
}

private fun scanSourcesWithActiveSession(
    sources: List<Pair<String, String>>,
    rawAbiMode: Boolean,
    splitModuleRoots: Boolean,
    finalStructs: Boolean,
): CrateRawAbiMetadata {
    val rawNormalizedSources =
        sources
            .map { (rel, txt) ->
                rel.replace('\\', '/').trimStart('/') to txt
            }.filterNot { (rel, _) ->
                isUnsupportedTargetRustRelPath(rel, TranslationSession.cfgTargetProfile)
            }
    val rawByRelPath = rawNormalizedSources.toMap()
    val skipCfgTest =
        "test" in TranslationSession.cfgTargetProfile.disabledCfgFlags ||
            "test" !in TranslationSession.cfgTargetProfile.enabledCfgFlags
    val disabledModulePrefixes =
        collectCfgDisabledChildModuleRelPrefixes(
            sourcesByRelPath = rawByRelPath,
            cfgProfile = TranslationSession.cfgTargetProfile,
            skipCfgTest = skipCfgTest,
        )
    val normalizedSources =
        if (disabledModulePrefixes.isEmpty()) {
            rawNormalizedSources
        } else {
            rawNormalizedSources.filterNot { (rel, _) -> isRelPathUnderDisabledModulePrefix(rel, disabledModulePrefixes) }
        }
    val byRelPath = normalizedSources.toMap()

    val selfAliases = linkedSetOf<String>()
    val includedSubdirModules = linkedMapOf<String, String>()
    val rootSelfDirModuleAliases = linkedSetOf<String>()
    val rootExternalModuleReexports = linkedMapOf<String, String>()
    val subpkgToRawLeaf = linkedMapOf<String, String>()
    val optionFnTypes = linkedSetOf<String>()
    val aliasDefaults = linkedMapOf<String, String>()
    val numericKinds = linkedMapOf<String, String>()
    val pointerAliases = linkedMapOf<String, Pair<Boolean, String>>()
    val aliasLeafTargets = linkedMapOf<String, String>()
    val qualifiedAliasLeafTargets = linkedMapOf<String, Pair<String, String>>()
    val fnTypeSpecs = linkedMapOf<String, RawFnTypeTokenSpec>()
    val structFieldTypes = linkedMapOf<String, String>()
    val symbolSubpackages = linkedMapOf<String, MutableSet<String>>()
    val recordDecls = linkedMapOf<String, RawRecordTokenDecl>()
    val recordDeclsBySimple = linkedMapOf<String, RawRecordTokenDecl>()
    val simpleEnumReprs = linkedMapOf<String, String>()
    val rawFnParamDeclTokens = linkedMapOf<String, MutableList<Triple<String, Boolean, List<List<TokenTree>>>>>()
    val rawFnReturnTypes = linkedMapOf<String, String>()
    val ffiReferencedIdents = linkedSetOf<String>()
    val constLeafTargets = mutableListOf<Triple<String, String, String>>()
    val sharedInlineRelFiles = discoverSharedInlineModuleRelFiles(byRelPath)
    val traitDefaultlessAssocTypes = linkedMapOf<String, List<String>>()
    val traitSupertraitBindings = linkedMapOf<String, List<Pair<String, Set<String>>>>()
    val traitResultMethods = linkedMapOf<String, List<String>>()
    val fnConstParamNames = linkedMapOf<String, Pair<Int, List<String>>>()
    val enumVariantTypeArity = linkedMapOf<String, Int>()
    val unitEnumVariants = linkedSetOf<String>()

    fun registerFnConstParams(
        subpkg: String,
        key: String,
        valueArity: Int,
        constParams: List<String>,
    ) {
        if (constParams.isEmpty()) return
        if (key !in fnConstParamNames) {
            fnConstParamNames[key] = valueArity to constParams
        }
        if (subpkg.isNotEmpty()) {
            val scopedKey = "$subpkg.$key"
            if (scopedKey !in fnConstParamNames) {
                fnConstParamNames[scopedKey] = valueArity to constParams
            }
        }
    }

    fun registerFnSignatureTokens(
        subpkg: String,
        key: String,
        paramTokensList: List<List<TokenTree>>?,
        returnType: String?,
        insideExternBlock: Boolean = false,
    ) {
        if (paramTokensList != null) {
            val entry = Triple(subpkg, insideExternBlock, paramTokensList)
            val arityKey = "$key#${paramTokensList.size}"
            rawFnParamDeclTokens.getOrPut(key) { mutableListOf() } += entry
            rawFnParamDeclTokens.getOrPut(arityKey) { mutableListOf() } += entry
            if (subpkg.isNotEmpty()) {
                rawFnParamDeclTokens.getOrPut("$subpkg.$key") { mutableListOf() } += entry
                rawFnParamDeclTokens.getOrPut("$subpkg.$arityKey") { mutableListOf() } += entry
            }
        }
        if (!returnType.isNullOrEmpty()) {
            val prevRet = rawFnReturnTypes[key]
            rawFnReturnTypes[key] = if (prevRet == null || prevRet == returnType) returnType else ""
            if (subpkg.isNotEmpty()) {
                val scopedKey = "$subpkg.$key"
                val prevScopedRet = rawFnReturnTypes[scopedKey]
                rawFnReturnTypes[scopedKey] =
                    if (prevScopedRet == null || prevScopedRet == returnType) returnType else ""
            }
        }
    }

    fun registerSymbolInSubpkg(ktIdent: String, subpkg: String) {
        if (ktIdent.isEmpty() || ktIdent == "_") return
        symbolSubpackages.getOrPut(ktIdent) { linkedSetOf() } += subpkg
    }

    fun putScopedDefault(subpkg: String, ktIdent: String, value: String) {
        if (ktIdent !in aliasDefaults) {
            aliasDefaults[ktIdent] = value
        }
        if (subpkg.isNotEmpty()) {
            aliasDefaults.putIfMissing("$subpkg.$ktIdent", value)
        }
    }

    fun putScopedNumericKind(subpkg: String, ktIdent: String, kind: String) {
        if (ktIdent !in numericKinds) {
            numericKinds[ktIdent] = kind
        }
        if (subpkg.isNotEmpty()) {
            numericKinds.putIfMissing("$subpkg.$ktIdent", kind)
        }
    }

    fun addScopedOptionFnType(subpkg: String, ktIdent: String) {
        optionFnTypes += ktIdent
        if (subpkg.isNotEmpty()) {
            optionFnTypes += "$subpkg.$ktIdent"
        }
    }

    fun putScopedPointerAlias(subpkg: String, ktIdent: String, spec: Pair<Boolean, String>) {
        if (ktIdent !in pointerAliases) {
            pointerAliases[ktIdent] = spec
        }
        if (subpkg.isNotEmpty()) {
            pointerAliases.putIfMissing("$subpkg.$ktIdent", spec)
        }
    }

    fun computeSubpkgAndRecordAncestors(relFile: String): Pair<String, String?> {
        val relDir = relFile.substringBeforeLast('/', missingDelimiterValue = "")
        val fileName = relFile.substringAfterLast('/')
        val stem = fileName.removeSuffix(".rs").removeSuffix(".RS")
        val rawDirParts = relDir.split('/').filter { it.isNotEmpty() }
        val ktDirSegs = mutableListOf<String>()
        for (rawSeg in rawDirParts) {
            val ktSeg = rustModuleIdentToKotlin(rawSeg.replace('-', '_'))
            if (ktSeg.isNotEmpty()) {
                ktDirSegs += ktSeg
                subpkgToRawLeaf.putIfMissing(ktDirSegs.joinToString("."), rawSeg)
            }
        }
        val includeStem =
            splitModuleRoots &&
                !isRustModuleRootFileName(fileName) &&
                relFile !in sharedInlineRelFiles
        if (includeStem) {
            val ktStem = rustModuleIdentToKotlin(stem.replace('-', '_'))
            if (ktStem.isNotEmpty()) {
                ktDirSegs += ktStem
                subpkgToRawLeaf.putIfMissing(ktDirSegs.joinToString("."), stem)
            }
        }
        return ktDirSegs.joinToString(".") to relDir.ifEmpty { null }
    }

    byRelPath["lib.rs"]?.let { libText ->
        val libTokens = lexScannerTokens(libText)
        val includedSubdirs = mutableListOf<String>()
        scanRootLibDirectives(libTokens, selfAliases, includedSubdirs)
        for (subdir in includedSubdirs) {
            val subModText = byRelPath["$subdir/mod.rs"] ?: continue
            val subTokens = lexScannerTokens(subModText)
            for (modIdent in extractPubModIdentsFromTokens(subTokens)) {
                includedSubdirModules[modIdent] = subdir
            }
        }
        scanRootAliasesAndExternalReexports(
            libText = libText,
            byRelPath = byRelPath,
            selfAliases = selfAliases,
            includedSubdirModules = includedSubdirModules,
            rootSelfDirModuleAliasesOut = rootSelfDirModuleAliases,
            rootExternalModuleReexportsOut = rootExternalModuleReexports,
        )
    }

    for ((relFile, text) in normalizedSources) {
        val (subpkg, relDir) = computeSubpkgAndRecordAncestors(relFile)
        TranslationSession.currentRelativeSourceDir = relDir
        val tokens = lexScannerTokens(text)
        if (tokens.isEmpty()) continue
        val topLevelSplitInlineMods =
            if (splitModuleRoots && "mod" in text && "{" in text) {
                recognizeRustOrNull(text)
                    ?.let { rec ->
                        selectSplitCandidateInlineModsForFile(rec.file)
                            .mapTo(linkedSetOf()) { it.ident.toString().removePrefix("r#") }
                    }.orEmpty()
            } else {
                emptySet()
            }
        scanModuleTokenTrees(
            trees = tokens,
            subpkg = subpkg,
            rawAbiMode = rawAbiMode,
            finalStructs = finalStructs,
            splitModuleRoots = splitModuleRoots,
            topLevelSplitInlineMods = topLevelSplitInlineMods,
            registerSymbol = ::registerSymbolInSubpkg,
            registerFnSig = ::registerFnSignatureTokens,
            subpkgToRawLeaf = subpkgToRawLeaf,
            putScopedDefault = ::putScopedDefault,
            putScopedNumericKind = ::putScopedNumericKind,
            addScopedOptionFnType = ::addScopedOptionFnType,
            putScopedPointerAlias = ::putScopedPointerAlias,
            aliasLeafTargets = aliasLeafTargets,
            qualifiedAliasLeafTargets = qualifiedAliasLeafTargets,
            fnTypeSpecs = fnTypeSpecs,
            structFieldTypes = structFieldTypes,
            recordDecls = recordDecls,
            recordDeclsBySimple = recordDeclsBySimple,
            simpleEnumReprs = simpleEnumReprs,
            ffiReferencedIdents = ffiReferencedIdents,
            constLeafTargets = constLeafTargets,
            traitDefaultlessAssocTypes = traitDefaultlessAssocTypes,
            traitResultMethods = traitResultMethods,
            registerFnConstParams = ::registerFnConstParams,
            enumVariantTypeArity = enumVariantTypeArity,
            unitEnumVariants = unitEnumVariants,
        )
    }

    var ffiAliasesChanged = true
    var ffiAliasRounds = 0
    while (ffiAliasesChanged && ffiAliasRounds < 12) {
        ffiAliasesChanged = false
        ffiAliasRounds++
        for ((aliasKt, leaf) in aliasLeafTargets) {
            if (aliasKt in ffiReferencedIdents) {
                val targetKt = KotlinNamingPolicy.Default.toTypeIdentifier(leaf, allowRawAbi = rawAbiMode)
                if (ffiReferencedIdents.add(leaf) || ffiReferencedIdents.add(targetKt)) {
                    ffiAliasesChanged = true
                }
            }
        }
    }

    val cfgProfile = TranslationSession.cfgTargetProfile
    val ptrWidth = cfgProfile.pointerWidth
    val isWindows =
        cfgProfile.targetOs == "windows" ||
            cfgProfile.targetFamilies?.contains("windows") == true

    fun classifyBuiltinLeaf(leaf: String, ownerKtName: String): Pair<String?, String?> =
        when (leaf) {
            "c_long" -> if (ptrWidth == 64 && !isWindows) "0L" to "I64" else "0" to "I32"
            "c_ulong" -> if (ptrWidth == 64 && !isWindows) "0uL" to "U64" else "0u" to "U32"
            "isize" ->
                if (rawAbiMode || isCPointerWidthSignedTypeName(ownerKtName) || ownerKtName in ffiReferencedIdents) {
                    when (ptrWidth) {
                        64 -> "0L" to "I64"
                        16 -> "0" to "I16"
                        else -> "0" to "I32"
                    }
                } else {
                    "0" to "I32"
                }
            "usize" ->
                if (rawAbiMode || isCPointerWidthUnsignedTypeName(ownerKtName) || ownerKtName in ffiReferencedIdents) {
                    when (ptrWidth) {
                        64 -> "0uL" to "U64"
                        16 -> "0u" to "U16"
                        else -> "0u" to "U32"
                    }
                } else {
                    "0" to "I32"
                }
            "bool" -> "false" to null
            "()" -> "Unit" to null
            else -> {
                val kind = SCANNER_RUST_TO_SCALAR_KIND[leaf] ?: SCANNER_C_ALIAS_TO_SCALAR_KIND[leaf]
                val desc = kind?.let(SCANNER_SCALAR_DESCRIPTORS_BY_KIND::get)
                if (desc != null) desc.defaultExpr to desc.kind else null to null
            }
        }

    fun resolveSimpleAlias(ktName: String, depth: Int = 0): Pair<String?, String?> {
        if (ktName in aliasDefaults) return aliasDefaults[ktName] to numericKinds[ktName]
        if (depth >= 8) return null to null
        val leaf = aliasLeafTargets[ktName] ?: return null to null
        val (builtinDef, builtinKind) = classifyBuiltinLeaf(leaf, ktName)
        if (builtinDef != null) {
            aliasDefaults[ktName] = builtinDef
            if (builtinKind != null) numericKinds[ktName] = builtinKind
            return builtinDef to builtinKind
        }
        val targetKt = KotlinNamingPolicy.Default.toTypeIdentifier(leaf, allowRawAbi = rawAbiMode)
        if (targetKt == ktName) return null to null
        if (targetKt in optionFnTypes) {
            optionFnTypes += ktName
            aliasDefaults[ktName] = "null"
            return "null" to null
        }
        val (transDef, transKind) = resolveSimpleAlias(targetKt, depth + 1)
        if (transDef != null) {
            aliasDefaults[ktName] = transDef
            if (transKind != null) numericKinds[ktName] = transKind
            if (targetKt in optionFnTypes) optionFnTypes += ktName
            pointerAliases[targetKt]?.let { pointerAliases[ktName] = it }
            return transDef to transKind
        }
        return null to null
    }

    val pointeeStopIdents = setOf("c_char", "c_void", "*")
    for ((ptrKey, spec) in pointerAliases.map { it.key to it.value }) {
        var curPointee = spec.second
        var hops = 0
        while (hops < 8 && curPointee !in pointeeStopIdents) {
            val mappedKt = KotlinNamingPolicy.Default.toTypeIdentifier(curPointee, allowRawAbi = rawAbiMode)
            val nextLeaf = aliasLeafTargets[curPointee] ?: aliasLeafTargets[mappedKt] ?: break
            if (nextLeaf == curPointee) break
            curPointee = nextLeaf
            hops++
        }
        if (curPointee != spec.second) {
            pointerAliases[ptrKey] = spec.first to curPointee
        }
    }

    for (ktName in aliasLeafTargets.keys.toList()) {
        resolveSimpleAlias(ktName)
    }
    val initialStructFieldTypes = structFieldTypes.map { it.key to it.value }
    for ((scopedKey, subpkgAndLeaf) in qualifiedAliasLeafTargets) {
        val (subpkg, leaf) = subpkgAndLeaf
        val simpleKt = scopedKey.substringAfterLast('.')
        val (bDef, bKind) = classifyBuiltinLeaf(leaf, simpleKt)
        if (bDef != null) {
            putScopedDefault(subpkg, simpleKt, bDef)
            if (subpkg.isNotEmpty()) aliasDefaults["$subpkg.$simpleKt"] = bDef
            if (symbolSubpackages[simpleKt]?.size == 1) aliasDefaults[simpleKt] = bDef
            if (bKind != null) {
                putScopedNumericKind(subpkg, simpleKt, bKind)
                if (subpkg.isNotEmpty()) numericKinds["$subpkg.$simpleKt"] = bKind
                if (symbolSubpackages[simpleKt]?.size == 1) numericKinds[simpleKt] = bKind
            }
        }
    }
    repeat(2) { _ ->
        for ((scopedKey, subpkgAndLeaf) in qualifiedAliasLeafTargets) {
            val (subpkg, leaf) = subpkgAndLeaf
            val simpleKt = scopedKey.substringAfterLast('.')
            if (classifyBuiltinLeaf(leaf, simpleKt).first != null) continue
            val targetKt = KotlinNamingPolicy.Default.toTypeIdentifier(leaf, allowRawAbi = rawAbiMode)
            val resolvedDef = aliasDefaults["$subpkg.$targetKt"] ?: aliasDefaults[targetKt]
            val resolvedKind = numericKinds["$subpkg.$targetKt"] ?: numericKinds[targetKt]
            if (resolvedDef != null) {
                putScopedDefault(subpkg, simpleKt, resolvedDef)
                if (subpkg.isNotEmpty()) aliasDefaults["$subpkg.$simpleKt"] = resolvedDef
                if (symbolSubpackages[simpleKt]?.size == 1) aliasDefaults[simpleKt] = resolvedDef
            }
            if (resolvedKind != null) {
                putScopedNumericKind(subpkg, simpleKt, resolvedKind)
                if (subpkg.isNotEmpty()) numericKinds["$subpkg.$simpleKt"] = resolvedKind
                if (symbolSubpackages[simpleKt]?.size == 1) numericKinds[simpleKt] = resolvedKind
            }
            if (targetKt in optionFnTypes || "$subpkg.$targetKt" in optionFnTypes) {
                addScopedOptionFnType(subpkg, simpleKt)
            }
            val ptrAlias = pointerAliases["$subpkg.$targetKt"] ?: pointerAliases[targetKt]
            if (ptrAlias != null) {
                putScopedPointerAlias(subpkg, simpleKt, ptrAlias)
            }
            for ((sfKey, sfVal) in initialStructFieldTypes) {
                if (subpkg.isNotEmpty() && sfKey.startsWith("$subpkg.$targetKt.")) {
                    val fieldPart = sfKey.removePrefix("$subpkg.$targetKt.")
                    structFieldTypes.putIfMissing("$subpkg.$simpleKt.$fieldPart", sfVal)
                    structFieldTypes.putIfMissing("$simpleKt.$fieldPart", sfVal)
                } else if (sfKey.startsWith("$targetKt.") && '.' !in sfKey.removePrefix("$targetKt.")) {
                    val fieldPart = sfKey.removePrefix("$targetKt.")
                    if (subpkg.isNotEmpty()) {
                        structFieldTypes.putIfMissing("$subpkg.$simpleKt.$fieldPart", sfVal)
                    }
                    structFieldTypes.putIfMissing("$simpleKt.$fieldPart", sfVal)
                }
            }
        }
    }
    for ((subpkg, ktConst, leaf) in constLeafTargets) {
        val targetKt = KotlinNamingPolicy.Default.toTypeIdentifier(leaf, allowRawAbi = rawAbiMode)
        val kind =
            classifyBuiltinLeaf(leaf, ktConst).second
                ?: numericKinds["$subpkg.$targetKt"]
                ?: numericKinds[targetKt]
        if (kind != null) {
            putScopedNumericKind(subpkg, ktConst, kind)
        }
    }
    for ((_, decl) in recordDecls) {
        val makeNullableForUnion = decl.isUnion && !rawAbiMode
        for (field in decl.fields) {
            val mappedTy =
                mapTokensFieldTypeQuick(
                    tokens = field.typeTokens,
                    rawAbiMode = rawAbiMode,
                    selfOwnerKt = decl.ktName,
                    numericKinds = numericKinds,
                    subpkg = decl.subpkg,
                ) ?: continue
            val recordedTy = if (makeNullableForUnion && !mappedTy.endsWith("?")) "$mappedTy?" else mappedTy
            if (decl.subpkg.isNotEmpty()) {
                structFieldTypes["${decl.subpkg}.${decl.ktName}.${field.ktName}"] = recordedTy
            }
            if (symbolSubpackages[decl.ktName]?.size == 1 || "${decl.ktName}.${field.ktName}" !in structFieldTypes) {
                structFieldTypes["${decl.ktName}.${field.ktName}"] = recordedTy
            }
        }
    }

    val valueParamPackTemplates = linkedMapOf<String, String>()
    val valueReturnUnpackTemplates = linkedMapOf<String, String>()
    val fnPtrInvokeTemplates = linkedMapOf<String, String>()

    if (rawAbiMode) {
        for ((enumKey, reprKind) in simpleEnumReprs) {
            val packExpr =
                when (reprKind) {
                    "I64", "U64" -> "__SELF__.value.toLong()"
                    "I16", "U16" -> "(__SELF__.value.toLong() and 0xFFFFL)"
                    "I8", "U8" -> "(__SELF__.value.toLong() and 0xFFL)"
                    else -> "(__SELF__.value.toLong() and 0xFFFF_FFFFL)"
                }
            val simpleEnum = enumKey.substringAfterLast('.')
            valueParamPackTemplates.putIfMissing(simpleEnum, packExpr)
            valueParamPackTemplates.putIfMissing(enumKey, packExpr)
        }

        val computedLayouts = linkedMapOf<String, ComputedRecordLayout?>()
        val visitingRecords = hashSetOf<String>()

        fun computeLayout(scopedKey: String): ComputedRecordLayout? {
            if (scopedKey in computedLayouts) return computedLayouts[scopedKey]
            if (!visitingRecords.add(scopedKey)) return null
            val decl = recordDecls[scopedKey] ?: recordDeclsBySimple[scopedKey.substringAfterLast('.')]
            val result =
                if (decl == null || decl.fields.isEmpty()) {
                    null
                } else {
                    buildRecordLayout(
                        decl = decl,
                        rawAbiMode = rawAbiMode,
                        numericKinds = numericKinds,
                        aliasDefaults = aliasDefaults,
                        optionFnTypes = optionFnTypes,
                        fnTypeSpecs = fnTypeSpecs,
                        simpleEnumReprs = simpleEnumReprs,
                        recordDecls = recordDecls,
                        recordDeclsBySimple = recordDeclsBySimple,
                        computeNested = ::computeLayout,
                    )
                }
            visitingRecords.remove(scopedKey)
            computedLayouts[scopedKey] = result
            return result
        }

        for (scopedKey in recordDecls.keys) {
            val layout = computeLayout(scopedKey) ?: continue
            val simpleName = layout.ktName
            if (layout.sizeBytes in setOf(1, 2, 4, 8)) {
                val packTemplate = buildRegisterPackExpr("__SELF__", layout)
                if (packTemplate != null) {
                    valueParamPackTemplates.putScopedAndSimpleIfMissing(layout.subpkg, simpleName, packTemplate)
                }
                if (layout.fields.size in 1..64) {
                    val unpackTemplate = buildRegisterUnpackExpr("__TYPE__", "rawRet", layout)
                    if (unpackTemplate != null) {
                        valueReturnUnpackTemplates.putScopedAndSimpleIfMissing(layout.subpkg, simpleName, unpackTemplate)
                    }
                }
            } else if (layout.sizeBytes > 0) {
                val memSlotExpr = buildMemoryCopySlotExpr("__SELF__", layout)
                if (memSlotExpr != null) {
                    valueParamPackTemplates.putScopedAndSimpleIfMissing(layout.subpkg, simpleName, memSlotExpr)
                }
            }
        }
    }

    val fnParamNumericKinds = linkedMapOf<String, List<String>>()
    for ((fnKey, entries) in rawFnParamDeclTokens) {
        var agreedKinds: List<String>? = null
        var conflict = false
        for ((fnSubpkg, isExtern, paramLists) in entries) {
            val resolvedKinds =
                paramLists.map { pTokens ->
                    resolveTokenTypeScalarKind(
                        pTokens,
                        fnSubpkg,
                        rawAbiMode,
                        numericKinds,
                        aliasDefaults,
                        insideExternBlock = isExtern,
                    ).orEmpty()
                }
            if (agreedKinds == null) {
                agreedKinds = resolvedKinds
            } else if (agreedKinds != resolvedKinds) {
                conflict = true
                break
            }
        }
        if (!conflict && agreedKinds != null && agreedKinds.any { it.isNotEmpty() }) {
            fnParamNumericKinds[fnKey] = agreedKinds
        }
    }

    for ((_, spec) in fnTypeSpecs) {
        val samName = spec.samKtName
        val arity = spec.paramTokenLists.size
        val retDefault = resolveFnReturnDefaultExpr(spec.retTokens, spec.subpkg, samName, rawAbiMode, aliasDefaults, ::classifyBuiltinLeaf)
        val paramsPlaceholder =
            if (arity == 0) {
                ""
            } else {
                (0 until arity).joinToString(", ") { "_" } + " -> "
            }
        val bodyExpr = if (retDefault == "Unit") "" else retDefault
        val samDefault =
            if (paramsPlaceholder.isEmpty() && bodyExpr.isEmpty()) {
                "$samName {}"
            } else {
                "$samName { ${(paramsPlaceholder + bodyExpr).trim()} }"
            }
        putScopedDefault(spec.subpkg, samName, samDefault)

        if (rawAbiMode) {
            val wrapperExpr =
                buildFnPtrInvokeTemplate(
                    spec = spec,
                    rawAbiMode = rawAbiMode,
                    numericKinds = numericKinds,
                    aliasDefaults = aliasDefaults,
                    valueParamPackTemplates = valueParamPackTemplates,
                )
            if (wrapperExpr != null) {
                fnPtrInvokeTemplates.putScopedAndSimpleIfMissing(spec.subpkg, samName, wrapperExpr)
            }
        }
    }

    val fnReturnTypes = rawFnReturnTypes.filterValues { it.isNotEmpty() }

    val declaredTraitAsts = linkedMapOf<String, Item.Trait>()
    val selfParameterizedTraits = linkedSetOf<String>()
    val traitSupertraits = linkedMapOf<String, List<String>>()
    val crateMacroSrcs = normalizedSources.map { it.second }.filter { "macro_rules" in it || "macro " in it }
    val anyMacroDefinesTrait = crateMacroSrcs.any { "trait " in it || "trait\n" in it || "trait\t" in it }
    val anyMacroDefinesItem =
        anyMacroDefinesTrait ||
            crateMacroSrcs.any { src ->
                "struct " in src || "struct\n" in src || "struct\t" in src ||
                    "enum " in src || "enum\n" in src || "enum\t" in src ||
                    "type " in src || "type\n" in src || "type\t" in src ||
                    "fn " in src || "fn\n" in src || "fn\t" in src ||
                    "mod " in src || "mod\n" in src || "mod\t" in src ||
                    "const " in src || "const\n" in src || "const\t" in src ||
                    "union " in src || "union\n" in src || "union\t" in src
            }
    for ((relFile, text) in normalizedSources) {
        if ("trait" !in text && !(anyMacroDefinesItem && "!" in text)) continue
        val recognized = recognizeRustOrNull(text) ?: continue
        val expandedFile = expandFileMacroRules(recognized.file, crateMacroSrcs).file
        val (subpkg, _) = computeSubpkgAndRecordAncestors(relFile)
        val sameFileTraitNames = linkedSetOf<String>()
        fun collectFileTraitNames(items: List<Item>) {
            for (item in items) {
                if (itemHasDocOnlyCfg(item)) continue
                when (item) {
                    is Item.Trait ->
                        sameFileTraitNames += KotlinNamingPolicy.Default.toTypeIdentifier(item.ident.toString(), allowRawAbi = rawAbiMode)
                    is Item.Mod -> {
                        val content = item.content
                        if (content is ModContent.Inline && !hasCfgTestModAttribute(item.attrs)) {
                            collectFileTraitNames(content.items)
                        }
                    }
                    else -> {}
                }
            }
        }
        collectFileTraitNames(expandedFile.items)
        val splitInlineMods =
            if (splitModuleRoots) {
                selectSplitCandidateInlineModsForFile(expandedFile).toSet()
            } else {
                emptySet()
            }
        fun walkTraitItems(items: List<Item>, curSubpkg: String = subpkg) {
            for (item in items) {
                if (itemHasDocOnlyCfg(item)) continue
                when (item) {
                    is Item.Trait -> {
                        val ktTrait = KotlinNamingPolicy.Default.toTypeIdentifier(item.ident.toString(), allowRawAbi = rawAbiMode)
                        registerSymbolInSubpkg(ktTrait, curSubpkg)
                        declaredTraitAsts.putIfMissing(ktTrait, item)
                        if (subpkg.isNotEmpty()) {
                            declaredTraitAsts.putIfMissing("$subpkg.$ktTrait", item)
                        }
                        if (traitReferencesSelfOutsideReceiver(item)) {
                            selfParameterizedTraits += ktTrait
                        }
                        val superEntries =
                            item.supertraits
                                .toList()
                                .mapNotNull { bound ->
                                    val path =
                                        (bound as? TypeParamBound.Trait)
                                            ?.takeIf { it.maybe == null }
                                            ?.path
                                            ?: return@mapNotNull null
                                    val lastSeg = path.segments.last() ?: return@mapNotNull null
                                    val raw =
                                        lastSeg.ident
                                            .toString()
                                            .removePrefix("r#")
                                    if (raw in IGNORED_TRAIT_BOUNDS) return@mapNotNull null
                                    val kt = KotlinNamingPolicy.Default.toTypeIdentifier(raw, allowRawAbi = rawAbiMode)
                                    if (kt in RUST_STD_IMPLICIT_SUPERTRAITS && kt !in sameFileTraitNames) return@mapNotNull null
                                    val boundAssocs =
                                        (lastSeg.arguments as? PathArguments.AngleBracketed)
                                            ?.args
                                            ?.toList()
                                            ?.filterIsInstance<GenericArgument.AssocTypeArg>()
                                            ?.map {
                                                KotlinNamingPolicy.Default.toTypeIdentifier(
                                                    it.assoc.ident.toString(),
                                                    allowRawAbi = false,
                                                )
                                            }?.toSet()
                                            .orEmpty()
                                    kt to boundAssocs
                                }
                        if (superEntries.isNotEmpty()) {
                            val superNames = superEntries.map { it.first }.distinct()
                            traitSupertraits[ktTrait] = superNames
                            traitSupertraitBindings[ktTrait] = superEntries
                            if (subpkg.isNotEmpty()) {
                                traitSupertraits["$subpkg.$ktTrait"] = superNames
                                traitSupertraitBindings["$subpkg.$ktTrait"] = superEntries
                            }
                        }
                        val astDefaultlessAssocs = mutableListOf<String>()
                        val astResultMethods = linkedSetOf<String>()
                        for (tItem in item.items) {
                            if (traitItemHasDocOnlyCfg(tItem)) continue
                            when (tItem) {
                                is TraitItem.AssocType ->
                                    if (tItem.default == null) {
                                        val assocKt = KotlinNamingPolicy.Default.toTypeIdentifier(tItem.ident.toString(), allowRawAbi = false)
                                        if (assocKt !in astDefaultlessAssocs) astDefaultlessAssocs += assocKt
                                    }
                                is TraitItem.Fn -> {
                                    val ktFn = rustFunctionIdentToKotlin(tItem.sig.ident.toString())
                                    registerSymbolInSubpkg(ktFn, curSubpkg)
                                    if (isSynResultReturnType(tItem.sig.output)) {
                                        astResultMethods += ktFn
                                    }
                                }
                                else -> {}
                            }
                        }
                        if (astDefaultlessAssocs.isNotEmpty()) {
                            if (subpkg.isNotEmpty()) traitDefaultlessAssocTypes["$subpkg.$ktTrait"] = astDefaultlessAssocs
                            if (ktTrait !in traitDefaultlessAssocTypes) traitDefaultlessAssocTypes[ktTrait] = astDefaultlessAssocs
                        }
                        if (astResultMethods.isNotEmpty()) {
                            if (subpkg.isNotEmpty()) traitResultMethods["$subpkg.$ktTrait"] = astResultMethods.toList()
                            if (ktTrait !in traitResultMethods) traitResultMethods[ktTrait] = astResultMethods.toList()
                        }
                    }
                    is Item.Struct -> {
                        val ktType = KotlinNamingPolicy.Default.toTypeIdentifier(item.ident.toString(), allowRawAbi = rawAbiMode)
                        registerSymbolInSubpkg(ktType, curSubpkg)
                    }
                    is Item.Enum -> {
                        val ktType = KotlinNamingPolicy.Default.toTypeIdentifier(item.ident.toString(), allowRawAbi = rawAbiMode)
                        registerSymbolInSubpkg(ktType, curSubpkg)
                    }
                    is Item.Union -> {
                        val ktType = KotlinNamingPolicy.Default.toTypeIdentifier(item.ident.toString(), allowRawAbi = rawAbiMode)
                        registerSymbolInSubpkg(ktType, curSubpkg)
                    }
                    is Item.ItemType -> {
                        val ktType = KotlinNamingPolicy.Default.toTypeIdentifier(item.ident.toString(), allowRawAbi = rawAbiMode)
                        registerSymbolInSubpkg(ktType, curSubpkg)
                    }
                    is Item.Fn -> {
                        val ktFn = rustFunctionIdentToKotlin(item.sig.ident.toString())
                        registerSymbolInSubpkg(ktFn, curSubpkg)
                    }
                    is Item.Const -> {
                        val ktConst = KotlinNamingPolicy.Default.toConstIdentifier(item.ident.toString())
                        registerSymbolInSubpkg(ktConst, curSubpkg)
                    }
                    is Item.Static -> {
                        val ktStatic = rustValueIdentToKotlin(item.ident.toString())
                        registerSymbolInSubpkg(ktStatic, curSubpkg)
                    }
                    is Item.Mod -> {
                        val content = item.content
                        if (content is ModContent.Inline && !hasCfgTestModAttribute(item.attrs)) {
                            val rawMod = item.ident.toString().removePrefix("r#")
                            val modKt = rustModuleIdentToKotlin(rawMod.replace('-', '_'))
                            val nextSub =
                                if (item in splitInlineMods && modKt.isNotEmpty()) {
                                    val childSub = if (curSubpkg.isEmpty()) modKt else "$curSubpkg.$modKt"
                                    subpkgToRawLeaf.putIfMissing(childSub, rawMod)
                                    childSub
                                } else {
                                    curSubpkg
                                }
                            walkTraitItems(content.items, nextSub)
                        }
                    }
                    else -> {}
                }
            }
        }
        walkTraitItems(expandedFile.items)
    }
    var selfClosureChanged = true
    while (selfClosureChanged) {
        selfClosureChanged = false
        for ((ktTrait, superNames) in traitSupertraits) {
            val simpleTrait = ktTrait.substringAfterLast('.')
            if (simpleTrait in selfParameterizedTraits) continue
            if (superNames.any { it in selfParameterizedTraits || it in KNOWN_SELF_PARAMETERIZED_TRAITS }) {
                selfParameterizedTraits += simpleTrait
                selfClosureChanged = true
            }
        }
    }
    var assocClosureChanged = true
    while (assocClosureChanged) {
        assocClosureChanged = false
        for ((ktTrait, superEntries) in traitSupertraitBindings) {
            val cur = traitDefaultlessAssocTypes[ktTrait].orEmpty().toMutableList()
            var added = false
            for ((superKt, boundAssocs) in superEntries) {
                val inherited = traitDefaultlessAssocTypes[superKt].orEmpty()
                for (assoc in inherited) {
                    if (assoc !in boundAssocs && assoc !in cur) {
                        cur += assoc
                        added = true
                    }
                }
            }
            if (added) {
                traitDefaultlessAssocTypes[ktTrait] = cur
                assocClosureChanged = true
            }
        }
    }
    val traitRegistryKey =
        if (declaredTraitAsts.isNotEmpty()) {
            val key =
                "crate:${normalizedSources.size}:${declaredTraitAsts.keys.sorted().joinToString(",")}:${TranslationSession.cfgTargetProfile}"
            CrateTraitAstRegistry.register(key, declaredTraitAsts)
            key
        } else {
            null
        }

    return CrateRawAbiMetadata(
        selfCrateAliases = selfAliases,
        includedSubdirModuleMap = includedSubdirModules,
        rootSelfDirModuleAliases = rootSelfDirModuleAliases,
        rootExternalModuleReexports = rootExternalModuleReexports,
        typeAliasDefaults = aliasDefaults,
        optionFnTypes = optionFnTypes,
        numericTypeKinds = numericKinds,
        pointerTypeAliases = pointerAliases.mapValues { (_, spec) -> "${if (spec.first) "mut" else "const"}:${spec.second}" },
        structFieldTypes = structFieldTypes,
        fnReturnTypes = fnReturnTypes,
        symbolSubpackages = symbolSubpackages.mapValues { it.value.toList() },
        subpackageRawLeafNames = subpkgToRawLeaf,
        valueParamPackTemplates = valueParamPackTemplates,
        valueReturnUnpackTemplates = valueReturnUnpackTemplates,
        fnPtrInvokeTemplates = fnPtrInvokeTemplates,
        fnParamNumericKinds = fnParamNumericKinds,
        sharedInlineModuleRelFiles = sharedInlineRelFiles,
        traitDefaultlessAssocTypes = traitDefaultlessAssocTypes,
        traitResultMethods = traitResultMethods,
        selfParameterizedTraits = selfParameterizedTraits,
        traitSupertraits = traitSupertraits,
        traitAstRegistryKey = traitRegistryKey,
        fnConstParamNames = fnConstParamNames,
        enumVariantTypeArity = enumVariantTypeArity,
        unitEnumVariants = unitEnumVariants,
    )
}

internal fun lexScannerTokens(sourceText: String): List<TokenTree> {
    val sanitized =
        if (sourceText.startsWith("#!") && !sourceText.startsWith("#![")) {
            val nl = sourceText.indexOf('\n')
            if (nl >= 0) sourceText.substring(nl + 1) else ""
        } else {
            sourceText
        }
    val lexed = TokenStream.fromString(sanitized)
    return if (lexed.isSuccess()) lexed.getOrThrow().toList() else emptyList()
}

private fun scalarKindSizeAndAlign(kind: String): Pair<Int, Int>? =
    SCANNER_SCALAR_DESCRIPTORS_BY_KIND[kind]?.sizeAlign

private fun resolveTokenTypeScalarKind(
    typeTokens: List<TokenTree>,
    subpkg: String,
    rawAbiMode: Boolean,
    numericKinds: Map<String, String>,
    aliasDefaults: Map<String, String>,
    insideExternBlock: Boolean = false,
): String? {
    if (typeTokens.isEmpty()) return null
    val first = typeTokens[0]
    if (first is TokenTree.Punct && (first.value.asChar() == '*' || first.value.asChar() == '&')) {
        if (rawAbiMode) return if (TranslationSession.cfgTargetProfile.pointerWidth == 64) "I64" else "I32"
        if (insideExternBlock && first.value.asChar() == '*') {
            return "OPAQUE_PTR"
        }
        return null
    }
    val leaf = extractSimplePathLeafIdent(typeTokens) ?: return null
    if (typeTokens.any { (it as? TokenTree.Punct)?.value?.asChar() == '<' }) return null
    when (leaf) {
        "isize" -> return if (rawAbiMode && TranslationSession.cfgTargetProfile.pointerWidth == 64) "I64" else "I32"
        "usize" -> return if (rawAbiMode) (if (TranslationSession.cfgTargetProfile.pointerWidth == 64) "U64" else "U32") else "I32"
    }
    (SCANNER_RUST_TO_SCALAR_KIND[leaf] ?: SCANNER_C_ALIAS_TO_SCALAR_KIND[leaf])?.let { return it }
    val ktIdent = KotlinNamingPolicy.Default.toTypeIdentifier(leaf, allowRawAbi = rawAbiMode)
    val scopedKey = if (subpkg.isEmpty()) ktIdent else "$subpkg.$ktIdent"
    numericKinds[scopedKey]?.let { return it }
    numericKinds[ktIdent]?.let { return it }
    if ((aliasDefaults[scopedKey] ?: aliasDefaults[ktIdent]) == "false") {
        return "BOOL"
    }
    return null
}

private fun parseArrayLenIntFromTokens(lenTokens: List<TokenTree>): Int? {
    if (lenTokens.size != 1) return null
    val lit = (lenTokens[0] as? TokenTree.Literal)?.value?.toString()?.replace("_", "") ?: return null
    val clean =
        lit.trimEnd { it.isLetter() }
    return if (clean.startsWith("0x") || clean.startsWith("0X")) {
        clean.drop(2).toIntOrNull(16)
    } else {
        clean.toIntOrNull()
    }
}

private fun buildRecordLayout(
    decl: RawRecordTokenDecl,
    rawAbiMode: Boolean,
    numericKinds: Map<String, String>,
    aliasDefaults: Map<String, String>,
    optionFnTypes: Set<String>,
    fnTypeSpecs: Map<String, RawFnTypeTokenSpec>,
    simpleEnumReprs: Map<String, String>,
    recordDecls: Map<String, RawRecordTokenDecl>,
    recordDeclsBySimple: Map<String, RawRecordTokenDecl>,
    computeNested: (String) -> ComputedRecordLayout?,
): ComputedRecordLayout? {
    val computedFields = mutableListOf<ComputedFieldLayout>()
    var offset = 0
    var maxAlign = 1
    var maxUnionFieldSize = 0

    for (field in decl.fields) {
        val shape =
            resolveFieldShape(
                typeTokens = field.typeTokens,
                subpkg = decl.subpkg,
                rawAbiMode = rawAbiMode,
                numericKinds = numericKinds,
                aliasDefaults = aliasDefaults,
                optionFnTypes = optionFnTypes,
                fnTypeSpecs = fnTypeSpecs,
                simpleEnumReprs = simpleEnumReprs,
                recordDecls = recordDecls,
                recordDeclsBySimple = recordDeclsBySimple,
                computeNested = computeNested,
            ) ?: return null
        if (shape.sizeBytes <= 0) return null
        val effAlign = decl.packAlign?.let { minOf(shape.alignBytes, it) } ?: shape.alignBytes
        maxAlign = maxOf(maxAlign, effAlign)
        if (decl.isUnion) {
            computedFields += ComputedFieldLayout(ktName = field.ktName, offsetBytes = 0, shape = shape)
            maxUnionFieldSize = maxOf(maxUnionFieldSize, shape.sizeBytes)
        } else {
            offset = alignUpBytes(offset, effAlign)
            computedFields += ComputedFieldLayout(ktName = field.ktName, offsetBytes = offset, shape = shape)
            offset += shape.sizeBytes
        }
    }

    val rawTotal = if (decl.isUnion) maxUnionFieldSize else offset
    val totalSize = alignUpBytes(rawTotal, maxAlign)
    return ComputedRecordLayout(
        ktName = decl.ktName,
        subpkg = decl.subpkg,
        isUnion = decl.isUnion,
        sizeBytes = totalSize,
        alignBytes = maxAlign,
        fields = computedFields,
    )
}

private fun alignUpBytes(value: Int, align: Int): Int {
    if (align <= 1) return value
    val rem = value % align
    return if (rem == 0) value else value + (align - rem)
}

private fun resolveFieldShape(
    typeTokens: List<TokenTree>,
    subpkg: String,
    rawAbiMode: Boolean,
    numericKinds: Map<String, String>,
    aliasDefaults: Map<String, String>,
    optionFnTypes: Set<String>,
    fnTypeSpecs: Map<String, RawFnTypeTokenSpec>,
    simpleEnumReprs: Map<String, String>,
    recordDecls: Map<String, RawRecordTokenDecl>,
    recordDeclsBySimple: Map<String, RawRecordTokenDecl>,
    computeNested: (String) -> ComputedRecordLayout?,
): ComputedFieldShape? {
    val scalarKind = resolveTokenTypeScalarKind(typeTokens, subpkg, rawAbiMode, numericKinds, aliasDefaults)
    if (scalarKind != null) {
        val (sz, al) = scalarKindSizeAndAlign(scalarKind) ?: return null
        return ComputedFieldShape.Scalar(sizeBytes = sz, alignBytes = al, kind = scalarKind)
    }

    if (typeTokens.size == 1 && typeTokens[0] is TokenTree.Group) {
        val grp = (typeTokens[0] as TokenTree.Group).value
        if (grp.delimiter() == Delimiter.Bracket) {
            val inner = grp.stream().toList()
            val semiIdx = inner.indexOfFirst { (it as? TokenTree.Punct)?.value?.asChar() == ';' }
            if (semiIdx > 0 && semiIdx + 1 < inner.size) {
                val count = parseArrayLenIntFromTokens(inner.subList(semiIdx + 1, inner.size)) ?: return null
                if (count <= 0) return null
                val elemKind =
                    resolveTokenTypeScalarKind(inner.subList(0, semiIdx), subpkg, rawAbiMode, numericKinds, aliasDefaults)
                        ?: return null
                val (elemSz, _) = scalarKindSizeAndAlign(elemKind) ?: return null
                return ComputedFieldShape.FixedArray(elemKind = elemKind, elemSizeBytes = elemSz, count = count)
            }
        }
    }

    if (extractOptionAngleInnerTokens(typeTokens)?.let(::tryParseBareFnTokens) != null) {
        return ComputedFieldShape.CallbackPtr(samKtName = "", isNullable = true)
    }
    if (tryParseBareFnTokens(typeTokens) != null) {
        return ComputedFieldShape.CallbackPtr(samKtName = "", isNullable = false)
    }

    val leaf = extractSimplePathLeafIdent(typeTokens) ?: return null
    if (typeTokens.any { (it as? TokenTree.Punct)?.value?.asChar() == '<' }) return null
    val ktIdent = KotlinNamingPolicy.Default.toTypeIdentifier(leaf, allowRawAbi = rawAbiMode)
    val scopedKey = if (subpkg.isEmpty()) ktIdent else "$subpkg.$ktIdent"

    if (scopedKey in optionFnTypes || ktIdent in optionFnTypes) {
        return ComputedFieldShape.CallbackPtr(samKtName = "${ktIdent}Fn", isNullable = true)
    }
    if (scopedKey in fnTypeSpecs || ktIdent in fnTypeSpecs) {
        return ComputedFieldShape.CallbackPtr(samKtName = ktIdent, isNullable = false)
    }

    val enumRepr =
        simpleEnumReprs[scopedKey]
            ?: simpleEnumReprs.entries.firstOrNull { it.key.substringAfterLast('.') == ktIdent }?.value
    if (enumRepr != null) {
        val (sz, al) = scalarKindSizeAndAlign(enumRepr) ?: return null
        return ComputedFieldShape.SimpleEnum(sizeBytes = sz, alignBytes = al, enumKtName = ktIdent, reprKind = enumRepr)
    }

    val nestedKey =
        when {
            scopedKey in recordDecls -> scopedKey
            ktIdent in recordDeclsBySimple -> {
                val found = recordDeclsBySimple.getValue(ktIdent)
                if (found.subpkg.isEmpty()) found.ktName else "${found.subpkg}.${found.ktName}"
            }
            else -> return null
        }
    val nestedLayout = computeNested(nestedKey) ?: return null
    return ComputedFieldShape.NestedRecord(recordKtName = ktIdent, layout = nestedLayout)
}

private fun appendLittleEndianByteWrites(
    bufVar: String,
    baseOffset: Int,
    valueExpr: String,
    kind: String,
    stmtsOut: MutableList<String>,
): Boolean {
    when (kind) {
        "I8", "U8" -> stmtsOut += "$bufVar[$baseOffset] = $valueExpr.toByte()"
        "BOOL" -> stmtsOut += "$bufVar[$baseOffset] = if ($valueExpr) 1 else 0"
        "I16", "U16" -> {
            stmtsOut += "$bufVar[$baseOffset] = ($valueExpr.toInt() and 0xFF).toByte()"
            stmtsOut += "$bufVar[${baseOffset + 1}] = (($valueExpr.toInt() ushr 8) and 0xFF).toByte()"
        }
        "I32", "U32" -> {
            for (b in 0..3) {
                val shift = b * 8
                val shifted = if (shift == 0) "$valueExpr.toInt()" else "($valueExpr.toInt() ushr $shift)"
                stmtsOut += "$bufVar[${baseOffset + b}] = ($shifted and 0xFF).toByte()"
            }
        }
        "F32" -> {
            for (b in 0..3) {
                val shift = b * 8
                val shifted = if (shift == 0) "$valueExpr.toRawBits()" else "($valueExpr.toRawBits() ushr $shift)"
                stmtsOut += "$bufVar[${baseOffset + b}] = ($shifted and 0xFF).toByte()"
            }
        }
        "I64", "U64" -> {
            for (b in 0..7) {
                val shift = b * 8
                val shifted = if (shift == 0) "$valueExpr.toLong()" else "($valueExpr.toLong() ushr $shift)"
                stmtsOut += "$bufVar[${baseOffset + b}] = ($shifted and 0xFF).toByte()"
            }
        }
        "F64" -> {
            for (b in 0..7) {
                val shift = b * 8
                val shifted = if (shift == 0) "$valueExpr.toRawBits()" else "($valueExpr.toRawBits() ushr $shift)"
                stmtsOut += "$bufVar[${baseOffset + b}] = ($shifted and 0xFF).toByte()"
            }
        }
        "I128", "U128" -> {
            stmtsOut += "$valueExpr.toLeBytes().asByteArray().copyInto($bufVar, destinationOffset = $baseOffset)"
        }
        else -> return false
    }
    return true
}

private fun appendRecordByteWrites(
    bufVar: String,
    receiverExpr: String,
    baseOffset: Int,
    layout: ComputedRecordLayout,
    stmtsOut: MutableList<String>,
): Boolean {
    if (layout.isUnion) return false
    for (field in layout.fields) {
        val fieldAccess = "$receiverExpr.${field.ktName}"
        val fieldOff = baseOffset + field.offsetBytes
        when (val shape = field.shape) {
            is ComputedFieldShape.Scalar -> {
                if (!appendLittleEndianByteWrites(bufVar, fieldOff, fieldAccess, shape.kind, stmtsOut)) return false
            }
            is ComputedFieldShape.SimpleEnum -> {
                if (!appendLittleEndianByteWrites(bufVar, fieldOff, "$fieldAccess.value", shape.reprKind, stmtsOut)) return false
            }
            is ComputedFieldShape.CallbackPtr -> {
                val slotExpr = "__FFI__.objectSlot($fieldAccess)"
                if (!appendLittleEndianByteWrites(bufVar, fieldOff, slotExpr, "I64", stmtsOut)) return false
            }
            is ComputedFieldShape.FixedArray -> {
                if (shape.elemKind == "U8") {
                    stmtsOut += "for (i in 0 until minOf(${shape.count}, $fieldAccess.size)) $bufVar[$fieldOff + i] = $fieldAccess[i].toByte()"
                } else if (shape.elemKind == "I8") {
                    stmtsOut += "$fieldAccess.copyInto($bufVar, destinationOffset = $fieldOff, startIndex = 0, endIndex = minOf(${shape.count}, $fieldAccess.size))"
                } else {
                    for (idx in 0 until shape.count) {
                        val elemOff = fieldOff + idx * shape.elemSizeBytes
                        if (!appendLittleEndianByteWrites(bufVar, elemOff, "$fieldAccess[$idx]", shape.elemKind, stmtsOut)) {
                            return false
                        }
                    }
                }
            }
            is ComputedFieldShape.NestedRecord -> {
                if (!appendRecordByteWrites(bufVar, fieldAccess, fieldOff, shape.layout, stmtsOut)) return false
            }
        }
    }
    return true
}

private fun buildMemoryCopySlotExpr(receiverExpr: String, layout: ComputedRecordLayout): String? {
    val stmts = mutableListOf<String>()
    if (!appendRecordByteWrites("buf", receiverExpr, 0, layout, stmts)) return null
    if (stmts.isEmpty()) return null
    return "run { val buf = ByteArray(${layout.sizeBytes}); ${stmts.joinToString("; ")}; val ptr = __FFI__.allocZeroed(${layout.sizeBytes}); __FFI__.writeBytes(ptr, buf); ptr }"
}

private fun packScalarBitsToLong(valueExpr: String, kind: String, bitOffset: Int): String? {
    val lowBitsExpr =
        when (kind) {
            "I64" -> valueExpr
            "U64" -> "$valueExpr.toLong()"
            "F64" -> "$valueExpr.toRawBits()"
            "F32" -> "($valueExpr.toRawBits().toLong() and 0xFFFF_FFFFL)"
            "I32", "U32" -> "($valueExpr.toLong() and 0xFFFF_FFFFL)"
            "I16", "U16" -> "($valueExpr.toLong() and 0xFFFFL)"
            "I8", "U8" -> "($valueExpr.toLong() and 0xFFL)"
            "BOOL" -> "(if ($valueExpr) 1L else 0L)"
            else -> return null
        }
    return if (bitOffset == 0) lowBitsExpr else "($lowBitsExpr shl $bitOffset)"
}

private fun buildRegisterPackExpr(receiverExpr: String, layout: ComputedRecordLayout): String? {
    val terms = mutableListOf<String>()
    for (field in layout.fields) {
        val fieldAccess = "$receiverExpr.${field.ktName}"
        val baseBitOffset = field.offsetBytes * 8
        when (val shape = field.shape) {
            is ComputedFieldShape.Scalar -> {
                val term = packScalarBitsToLong(fieldAccess, shape.kind, baseBitOffset) ?: return null
                terms += term
            }
            is ComputedFieldShape.SimpleEnum -> {
                val term = packScalarBitsToLong("$fieldAccess.value", shape.reprKind, baseBitOffset) ?: return null
                terms += term
            }
            is ComputedFieldShape.CallbackPtr -> {
                val slot = "__FFI__.objectSlot($fieldAccess)"
                terms += if (baseBitOffset == 0) slot else "($slot shl $baseBitOffset)"
            }
            is ComputedFieldShape.FixedArray -> {
                val elemBits = shape.elemSizeBytes * 8
                for (idx in 0 until shape.count) {
                    val elemBitOff = baseBitOffset + idx * elemBits
                    val term = packScalarBitsToLong("$fieldAccess[$idx]", shape.elemKind, elemBitOff) ?: return null
                    terms += term
                }
            }
            is ComputedFieldShape.NestedRecord -> {
                val nested = buildRegisterPackExpr(fieldAccess, shape.layout) ?: return null
                terms += if (baseBitOffset == 0) nested else "(($nested) shl $baseBitOffset)"
            }
        }
    }
    if (terms.isEmpty()) return null
    val deduped = terms.distinct()
    return if (deduped.size == 1) deduped[0] else "(${deduped.joinToString(" or ")})"
}

private fun unpackScalarFromRawLong(rawLongExpr: String, kind: String, bitOffset: Int): String? {
    val shifted = if (bitOffset == 0) rawLongExpr else "($rawLongExpr ushr $bitOffset)"
    return when (kind) {
        "I8" -> "$shifted.toByte()"
        "U8" -> "$shifted.toUByte()"
        "I16" -> "$shifted.toShort()"
        "U16" -> "$shifted.toUShort()"
        "I32" -> "$shifted.toInt()"
        "U32" -> "$shifted.toUInt()"
        "I64" -> shifted
        "U64" -> "$shifted.toULong()"
        "F32" -> "Float.fromBits($shifted.toInt())"
        "F64" -> "Double.fromBits($shifted)"
        "BOOL" -> "(($shifted and 1L) != 0L)"
        else -> null
    }
}

private fun buildRegisterUnpackExpr(
    ctorTypePlaceholder: String,
    rawLongExpr: String,
    layout: ComputedRecordLayout,
): String? {
    val namedArgs = mutableListOf<String>()
    for (field in layout.fields) {
        val baseBitOffset = field.offsetBytes * 8
        val fieldValueExpr =
            when (val shape = field.shape) {
                is ComputedFieldShape.Scalar ->
                    unpackScalarFromRawLong(rawLongExpr, shape.kind, baseBitOffset) ?: return null
                is ComputedFieldShape.SimpleEnum -> return null
                is ComputedFieldShape.CallbackPtr -> {
                    if (shape.isNullable) "null" else return null
                }
                is ComputedFieldShape.FixedArray -> {
                    val arrayFactory =
                        SCANNER_SCALAR_DESCRIPTORS_BY_KIND[shape.elemKind]?.arrayFactory ?: return null
                    val elemBits = shape.elemSizeBytes * 8
                    val elems =
                        (0 until shape.count).map { idx ->
                            unpackScalarFromRawLong(rawLongExpr, shape.elemKind, baseBitOffset + idx * elemBits)
                                ?: return null
                        }
                    "$arrayFactory(${elems.joinToString(", ")})"
                }
                is ComputedFieldShape.NestedRecord -> return null
            }
        namedArgs += "${field.ktName} = $fieldValueExpr"
    }
    if (namedArgs.isEmpty()) return null
    return "$ctorTypePlaceholder(${namedArgs.joinToString(", ")})"
}

private fun resolveFnReturnDefaultExpr(
    retTokens: List<TokenTree>,
    subpkg: String,
    ownerKtName: String,
    rawAbiMode: Boolean,
    aliasDefaults: Map<String, String>,
    classifyBuiltinLeaf: (String, String) -> Pair<String?, String?>,
): String {
    if (retTokens.isEmpty()) return "Unit"
    val first = retTokens[0]
    if (retTokens.size == 1 && first is TokenTree.Group && first.value.delimiter() == Delimiter.Parenthesis) {
        if (first.value.stream().isEmpty()) return "Unit"
    }
    if (first is TokenTree.Punct && first.value.asChar() == '*') {
        val qual = (retTokens.getOrNull(1) as? TokenTree.Ident)?.value?.toString()
        return if (rawAbiMode) {
            "0L"
        } else if (qual == "mut") {
            "MutPtr(0L)"
        } else {
            "ConstPtr(0L)"
        }
    }
    if (extractOptionAngleInnerTokens(retTokens) != null) {
        return "null"
    }
    val leaf = extractSimplePathLeafIdent(retTokens) ?: return "Unit"
    val (bDef, _) = classifyBuiltinLeaf(leaf, ownerKtName)
    if (bDef != null) return bDef
    val retKt = KotlinNamingPolicy.Default.toTypeIdentifier(leaf, allowRawAbi = rawAbiMode)
    val scopedRet = if (subpkg.isEmpty()) retKt else "$subpkg.$retKt"
    return aliasDefaults[scopedRet] ?: aliasDefaults[retKt] ?: if (rawAbiMode) "$retKt()" else "Unit"
}

private fun buildFnPtrInvokeTemplate(
    spec: RawFnTypeTokenSpec,
    rawAbiMode: Boolean,
    numericKinds: Map<String, String>,
    aliasDefaults: Map<String, String>,
    valueParamPackTemplates: Map<String, String>,
): String? {
    val arity = spec.paramTokenLists.size
    val paramNames = (0 until arity).map { "p$it" }
    val slotExprs =
        spec.paramTokenLists.mapIndexed { idx, paramTokens ->
            val pName = paramNames[idx]
            val pKind = resolveTokenTypeScalarKind(paramTokens, spec.subpkg, rawAbiMode, numericKinds, aliasDefaults)
            when (pKind) {
                "BOOL" -> "if ($pName) 1L else 0L"
                "I64" -> pName
                "F32" -> "($pName.toRawBits().toLong() and 0xFFFF_FFFFL)"
                "F64" -> "$pName.toRawBits()"
                "I8", "U8", "I16", "U16", "I32", "U32", "U64" -> "$pName.toLong()"
                else -> {
                    val leaf = extractSimplePathLeafIdent(paramTokens)
                    val ktIdent = leaf?.let { KotlinNamingPolicy.Default.toTypeIdentifier(it, allowRawAbi = rawAbiMode) }
                    val scoped = if (ktIdent != null && spec.subpkg.isNotEmpty()) "${spec.subpkg}.$ktIdent" else ktIdent
                    val packTpl =
                        (scoped?.let { valueParamPackTemplates[it] })
                            ?: (ktIdent?.let { valueParamPackTemplates[it] })
                    packTpl?.replace("__SELF__", pName) ?: "__FFI__.objectSlot($pName)"
                }
            }
        }
    val invokeExpr = "__FFI__.invokePtr(rawRet, longArrayOf(${slotExprs.joinToString(", ")}))"
    val retBody =
        if (spec.retTokens.isEmpty() ||
            (
                spec.retTokens.size == 1 &&
                    (spec.retTokens[0] as? TokenTree.Group)?.value?.delimiter() == Delimiter.Parenthesis &&
                    (spec.retTokens[0] as TokenTree.Group).value.stream().isEmpty()
            )
        ) {
            invokeExpr
        } else {
            val retKind = resolveTokenTypeScalarKind(spec.retTokens, spec.subpkg, rawAbiMode, numericKinds, aliasDefaults)
            when (retKind) {
                "I8" -> "$invokeExpr.toByte()"
                "U8" -> "$invokeExpr.toUByte()"
                "I16" -> "$invokeExpr.toShort()"
                "U16" -> "$invokeExpr.toUShort()"
                "I32" -> "$invokeExpr.toInt()"
                "U32" -> "$invokeExpr.toUInt()"
                "I64" -> invokeExpr
                "U64" -> "$invokeExpr.toULong()"
                "F32" -> "Float.fromBits($invokeExpr.toInt())"
                "F64" -> "Double.fromBits($invokeExpr)"
                "BOOL" -> "(($invokeExpr and 0xFFL) != 0L)"
                else -> {
                    if (extractOptionAngleInnerTokens(spec.retTokens) != null) {
                        "run { $invokeExpr; null }"
                    } else {
                        return null
                    }
                }
            }
        }
    val lambdaHeader = if (paramNames.isEmpty()) "" else "${paramNames.joinToString(", ")} -> "
    return "__SAM__ { $lambdaHeader$retBody }"
}

private val TUPLE_CTOR_FIELD_REGEX = Regex("""^field\d+(?:CtorArg)?$""")

private val KOTLIN_DECL_MODIFIERS =
    setOf(
        "public",
        "internal",
        "private",
        "protected",
        "sealed",
        "data",
        "enum",
        "value",
        "annotation",
        "abstract",
        "open",
        "final",
        "inner",
        "expect",
        "actual",
        "inline",
        "noinline",
        "crossinline",
        "infix",
        "operator",
        "suspend",
        "tailrec",
        "external",
        "const",
        "override",
        "lateinit",
        "companion",
    )

private val KOTLIN_DECL_KEYWORDS =
    setOf("class", "interface", "object", "fun", "typealias", "val", "var")

internal fun discoverExternalCrateAbiFromDisk(
    anchorPath: String?,
    crateNamespaces: Map<String, String>,
): ExternalCrateAbiMetadata {
    val externalNs =
        crateNamespaces
            .mapValues { it.value.trim().removeSuffix(".") }
            .filterValues { it.isNotEmpty() }
    if (externalNs.isEmpty() || anchorPath.isNullOrBlank()) return ExternalCrateAbiMetadata()

    val startDir = KmPathFileTreeNavigator.startingDirectory(KmPath(anchorPath)) ?: return ExternalCrateAbiMetadata()

    var currentAnchorRepo: KmPath? = null
    var workspaceRoot: KmPath? = null
    var cur: KmPath? = startDir
    var depth = 0
    while (cur != null && depth < 24) {
        val hasRepoMarker =
            isKmRegularFile(KmPath(cur, "rustkotlin.properties")) ||
                isKmRegularFile(KmPath(cur, "build.gradle.kts"))
        if (hasRepoMarker) {
            currentAnchorRepo = cur
            workspaceRoot = cur.parent
            break
        }
        cur = cur.parent
        depth++
    }
    val resolvedWorkspace = workspaceRoot?.takeIf(::isKmDirectory) ?: return ExternalCrateAbiMetadata()

    val workspaceRepos =
        SystemFileSystem
            .list(resolvedWorkspace)
            .filter { isKmDirectory(it) && it.name.endsWith("-kotlin") }
            .sortedBy { it.name }
    val candidateRepos =
        buildList {
            currentAnchorRepo?.let { add(it) }
            for (dir in workspaceRepos) {
                if (dir.toString() != currentAnchorRepo?.toString()) {
                    add(dir)
                }
            }
        }
    val propsNsCache = linkedMapOf<KmPath, String?>()
    fun readRepoPropsNs(dir: KmPath): String? =
        propsNsCache.getOrPut(dir) {
            val propsFile = KmPath(dir, "rustkotlin.properties")
            if (!isKmRegularFile(propsFile)) {
                null
            } else {
                parseRustKotlinProperties(readKmFileText(propsFile)).projectNamespace?.trim()?.removeSuffix(".")
            }
        }

    fun collectKtFilesRecursive(dir: KmPath, out: MutableList<KmPath>) {
        for (entry in SystemFileSystem.list(dir).sortedBy { it.toString() }) {
            if (isKmDirectory(entry)) {
                collectKtFilesRecursive(entry, out)
            } else if (isKmRegularFile(entry) && entry.name.endsWith(".kt", ignoreCase = true)) {
                out += entry
            }
        }
    }

    data class MatchedCrateFiles(
        val crateKey: String,
        val crateNs: String,
        val ktFiles: List<KmPath>,
    )

    val matchedCrates = mutableListOf<MatchedCrateFiles>()
    for ((crateKey, crateNs) in externalNs) {
        fun dirMatchesCrateKey(dir: KmPath): Boolean {
            val stem = dir.name.removeSuffix("-kotlin")
            return rustModuleIdentToKotlin(stem.replace('-', '_')) == crateKey ||
                stem.replace("-", "").equals(crateKey.replace("_", ""), ignoreCase = true)
        }
        val exactPropsNsRoot =
            candidateRepos.firstNotNullOfOrNull { dir ->
                val propsNs = readRepoPropsNs(dir) ?: return@firstNotNullOfOrNull null
                if (propsNs != crateNs) return@firstNotNullOfOrNull null
                KmPath(dir, "src/common/commonMain/kotlin").takeIf(::isKmDirectory)
            }
        val prefixRoot =
            if (exactPropsNsRoot != null) {
                null
            } else {
                candidateRepos.firstNotNullOfOrNull { dir ->
                    val propsNs = readRepoPropsNs(dir) ?: return@firstNotNullOfOrNull null
                    if (!crateNs.startsWith("$propsNs.")) return@firstNotNullOfOrNull null
                    KmPath(dir, "src/common/commonMain/kotlin/${crateNs.replace('.', '/')}").takeIf(::isKmDirectory)
                }
            }
        val fallbackDirRoot =
            if (exactPropsNsRoot != null || prefixRoot != null) {
                null
            } else {
                candidateRepos.firstNotNullOfOrNull { dir ->
                    if (readRepoPropsNs(dir) != null || !dirMatchesCrateKey(dir)) return@firstNotNullOfOrNull null
                    KmPath(dir, "src/common/commonMain/kotlin").takeIf(::isKmDirectory)
                }
            }
        val kotlinRoot = exactPropsNsRoot ?: prefixRoot ?: fallbackDirRoot ?: continue
        val ktFiles = mutableListOf<KmPath>()
        collectKtFilesRecursive(kotlinRoot, ktFiles)
        if (ktFiles.isNotEmpty()) {
            matchedCrates += MatchedCrateFiles(crateKey = crateKey, crateNs = crateNs, ktFiles = ktFiles)
        }
    }

    val cacheKey =
        resolvedWorkspace.toString().replace('\\', '/') + "|" +
            matchedCrates
                .sortedBy { it.crateKey }
                .joinToString(",") { "${it.crateKey}=${it.crateNs}:${it.ktFiles.size}" }
    diskExternalCrateAbiCache[cacheKey]?.let { return it }

    val symbolPackages = linkedMapOf<String, Map<String, List<String>>>()
    val twoFieldResultFns = linkedSetOf<String>()
    val nullableReturnFns = linkedSetOf<String>()
    val fnConstParams = linkedMapOf<String, Pair<Int, List<String>>>()
    val tupleStructArity = linkedMapOf<String, Int>()
    val enumVariantTypeArity = linkedMapOf<String, Int>()
    val unitEnumVariants = linkedSetOf<String>()

    for (matched in matchedCrates) {
        val crateKey = matched.crateKey
        val crateNs = matched.crateNs
        val pkgMapBySym = linkedMapOf<String, LinkedHashSet<String>>()
        for (ktFile in matched.ktFiles) {
            ExternalKotlinSymbolWalker(
                sourceText = readKmFileText(ktFile),
                expectedCrateNs = crateNs,
                pkgMapBySym = pkgMapBySym,
                twoFieldResultFns = twoFieldResultFns,
                nullableReturnFns = nullableReturnFns,
                fnConstParams = fnConstParams,
                tupleStructArity = tupleStructArity,
                enumVariantTypeArity = enumVariantTypeArity,
                unitEnumVariants = unitEnumVariants,
            ).scan()
        }
        if (pkgMapBySym.isNotEmpty()) {
            symbolPackages[crateKey] =
                pkgMapBySym.mapValues { (_, pkgs) ->
                    pkgs.sortedWith(
                        compareBy<String>(
                            { it != crateNs },
                            { ".generic." in it || it.endsWith(".generic") },
                            { it.count { ch -> ch == '.' } },
                            { it },
                        ),
                    )
                }
        }
    }

    val metadata =
        ExternalCrateAbiMetadata(
            symbolPackages = symbolPackages,
            twoFieldResultFns = twoFieldResultFns,
            nullableReturnFns = nullableReturnFns,
            fnConstParams = fnConstParams,
            tupleStructArity = tupleStructArity,
            enumVariantTypeArity = enumVariantTypeArity,
            unitEnumVariants = unitEnumVariants,
        )
    if (symbolPackages.isNotEmpty()) {
        if (diskExternalCrateAbiCache.size >= 32) {
            diskExternalCrateAbiCache = mapOf(cacheKey to metadata)
        } else {
            diskExternalCrateAbiCache = diskExternalCrateAbiCache + (cacheKey to metadata)
        }
    }
    return metadata
}

/**
 * Lexical Kotlin declaration walker extending [KotlinTokenWalker] to extract exported symbol
 * packages, tuple-struct arity, generic enum variant arity, non-default const parameter shapes,
 * two-field result data classes, and nullable return types from sibling crate `.kt` files.
 */
private class ExternalKotlinSymbolWalker(
    sourceText: String,
    private val expectedCrateNs: String,
    private val pkgMapBySym: MutableMap<String, LinkedHashSet<String>>,
    private val twoFieldResultFns: MutableSet<String>,
    private val nullableReturnFns: MutableSet<String>,
    private val fnConstParams: MutableMap<String, Pair<Int, List<String>>>,
    private val tupleStructArity: MutableMap<String, Int>,
    private val enumVariantTypeArity: MutableMap<String, Int>,
    private val unitEnumVariants: MutableSet<String>,
) : KotlinTokenWalker(sourceText) {
    private val twoFieldClasses = linkedSetOf<String>()
    private val candidateTwoFieldFns = mutableListOf<Pair<String, String>>()

    fun scan() {
        val packageName = parsePackageHeader() ?: return
        if (packageName != expectedCrateNs && !packageName.startsWith("$expectedCrateNs.")) return
        scanScope(
            startIdx = 0,
            endExclusive = tokens.size,
            packageName = packageName,
            ownerCls = null,
            inCompanion = false,
        )
        for ((fnName, retSimple) in candidateTwoFieldFns) {
            if (retSimple in twoFieldClasses) {
                twoFieldResultFns += "$packageName.$fnName"
            }
        }
    }

    private fun parsePackageHeader(): String? {
        var idx = nextNonTriviaIndex(0)
        while (punctAt(idx) == '@') {
            idx = nextNonTriviaIndex(skipAnnotation(idx))
        }
        if (identTextAt(idx) != "package") return null
        val (pkg, _) = readDottedIdentifier(nextNonTriviaIndex(idx + 1)) ?: return null
        return pkg
    }

    private fun readDottedIdentifier(startIdx: Int, limit: Int = tokens.size): Pair<String, Int>? {
        val first = identTextAt(startIdx) ?: return null
        val sb = StringBuilder(first)
        var cur = nextNonTriviaIndex(startIdx + 1, limit)
        while (punctAt(cur) == '.') {
            val segIdx = nextNonTriviaIndex(cur + 1, limit)
            val seg = identTextAt(segIdx) ?: break
            sb.append('.').append(seg)
            cur = nextNonTriviaIndex(segIdx + 1, limit)
        }
        return sb.toString() to cur
    }

    private fun recordExternalVariantArity(
        owner: String,
        variant: String,
        arity: Int,
        isUnit: Boolean,
    ) {
        enumVariantTypeArity.putIfMissing("$owner.$variant", arity)
        val prev = enumVariantTypeArity[variant]
        if (prev == null) {
            enumVariantTypeArity[variant] = arity
        } else if (prev != arity) {
            enumVariantTypeArity[variant] = -1
        }
        if (isUnit && arity > 0) {
            unitEnumVariants += "$owner.$variant"
            unitEnumVariants += variant
        }
    }

    private fun scanScope(
        startIdx: Int,
        endExclusive: Int,
        packageName: String,
        ownerCls: String?,
        inCompanion: Boolean,
    ) {
        var idx = startIdx
        var atStatementStart = true
        var parenDepth = 0
        var bracketDepth = 0

        while (idx < endExclusive) {
            val tok = tokens[idx]
            if (tok is KotlinLexToken.Whitespace ||
                tok is KotlinLexToken.LineComment ||
                tok is KotlinLexToken.BlockComment
            ) {
                idx++
                continue
            }
            if (tok is KotlinLexToken.Newline) {
                if (parenDepth == 0 && bracketDepth == 0) {
                    atStatementStart = true
                }
                idx++
                continue
            }
            if (tok.interpolationDepth == 0 && tok is KotlinLexToken.Punct) {
                when (tok.ch) {
                    '{' ->
                        if (parenDepth == 0 && bracketDepth == 0) {
                            idx = findMatchingBraceEndToken(idx)
                            atStatementStart = true
                            continue
                        }
                    '(' -> parenDepth++
                    ')' -> if (parenDepth > 0) parenDepth--
                    '[' -> bracketDepth++
                    ']' -> if (bracketDepth > 0) bracketDepth--
                    ';' ->
                        if (parenDepth == 0 && bracketDepth == 0) {
                            atStatementStart = true
                            idx++
                            continue
                        }
                }
            }

            if (atStatementStart && parenDepth == 0 && bracketDepth == 0) {
                atStatementStart = false
                val prev = previousNonTriviaToken(idx)
                val afterAssign = prev is KotlinLexToken.Punct && prev.ch == '='
                if (!afterAssign) {
                    val jumpTo = tryInspectDeclarationAt(idx, endExclusive, packageName, ownerCls, inCompanion)
                    if (jumpTo != null) {
                        idx = jumpTo
                        atStatementStart = true
                        continue
                    }
                }
            }
            idx++
        }
    }

    private fun tryInspectDeclarationAt(
        declStartIdx: Int,
        endExclusive: Int,
        packageName: String,
        ownerCls: String?,
        inCompanion: Boolean,
    ): Int? {
        var cur = declStartIdx
        while (punctAt(cur) == '@') {
            cur = nextNonTriviaIndex(skipAnnotation(cur, endExclusive), endExclusive)
        }
        val modifiers = linkedSetOf<String>()
        while (cur < endExclusive) {
            val mod = identTextAt(cur) ?: break
            if (mod == "fun" && identTextAt(nextNonTriviaIndex(cur + 1, endExclusive)) == "interface") {
                modifiers += "fun"
                cur = nextNonTriviaIndex(cur + 1, endExclusive)
                break
            }
            if (mod !in KOTLIN_DECL_MODIFIERS) break
            modifiers += mod
            cur = nextNonTriviaIndex(cur + 1, endExclusive)
        }
        val declKw = identTextAt(cur) ?: return null
        if (declKw !in KOTLIN_DECL_KEYWORDS) return null

        if (ownerCls != null && !inCompanion && "companion" in modifiers && declKw == "object") {
            val braceIdx = findDeclarationBodyBrace(nextNonTriviaIndex(cur + 1, endExclusive), endExclusive)
            if (braceIdx >= 0) {
                val closeEnd = findMatchingBraceEndToken(braceIdx)
                scanScope(braceIdx + 1, closeEnd - 1, packageName, ownerCls = ownerCls, inCompanion = true)
                return closeEnd
            }
            return null
        }

        return when (declKw) {
            "class", "interface", "object" ->
                inspectTypeLikeDeclaration(cur, declKw, modifiers, endExclusive, packageName, ownerCls, inCompanion)
            "fun" -> {
                if ("public" in modifiers && (ownerCls == null || inCompanion)) {
                    inspectPublicFunctionDeclaration(cur, endExclusive, packageName, ownerCls)
                }
                null
            }
            "typealias", "val", "var" -> {
                if ("public" in modifiers) {
                    inspectPublicPropertyOrAlias(cur, declKw, endExclusive, packageName, ownerCls, inCompanion)
                }
                null
            }
            else -> null
        }
    }

    private fun inspectTypeLikeDeclaration(
        kwIdx: Int,
        declKw: String,
        modifiers: Set<String>,
        endExclusive: Int,
        packageName: String,
        ownerCls: String?,
        inCompanion: Boolean,
    ): Int? {
        val nameIdx = nextNonTriviaIndex(kwIdx + 1, endExclusive)
        val clsName = identTextAt(nameIdx)
        var afterName = if (clsName != null) nextNonTriviaIndex(nameIdx + 1, endExclusive) else nameIdx
        var typeParamSlice: String? = null
        if (punctAt(afterName) == '<') {
            val closeAngle = findMatchingTypeAngleToken(afterName)
            if (closeAngle > afterName) {
                typeParamSlice = source.substring(tokens[afterName].end, tokens[closeAngle].start)
                afterName = nextNonTriviaIndex(closeAngle + 1, endExclusive)
            }
        }

        var ctorScan = afterName
        while (punctAt(ctorScan) == '@') {
            ctorScan = nextNonTriviaIndex(skipAnnotation(ctorScan, endExclusive), endExclusive)
        }
        while (identTextAt(ctorScan) in setOf("public", "internal", "private", "protected")) {
            ctorScan = nextNonTriviaIndex(ctorScan + 1, endExclusive)
        }
        val hasExplicitCtorKw = identTextAt(ctorScan) == "constructor"
        if (hasExplicitCtorKw) {
            ctorScan = nextNonTriviaIndex(ctorScan + 1, endExclusive)
        }
        var openParenIdx = -1
        var closeParenIdx = -1
        if (punctAt(ctorScan) == '(') {
            val cp = findMatchingParenToken(ctorScan)
            if (cp >= ctorScan) {
                openParenIdx = ctorScan
                closeParenIdx = cp
                ctorScan = nextNonTriviaIndex(cp + 1, endExclusive)
            }
        } else if (!hasExplicitCtorKw) {
            ctorScan = afterName
        }

        if (ownerCls == null && clsName != null && "public" in modifiers) {
            pkgMapBySym.getOrPut(clsName) { linkedSetOf() } += packageName
            if (openParenIdx >= 0) {
                val ctorNames = extractNonDefaultParamNames(openParenIdx, closeParenIdx)
                if (ctorNames.size == 1 && ctorNames[0] in setOf("value", "valueCtorArg")) {
                    tupleStructArity.putIfMissing(clsName, 1)
                } else if (ctorNames.size >= 2 && ctorNames[0] in setOf("field0", "field0CtorArg")) {
                    val fieldCount = ctorNames.count { TUPLE_CTOR_FIELD_REGEX.matches(it) }
                    if (fieldCount >= 2) {
                        tupleStructArity.putIfMissing(clsName, fieldCount)
                    }
                }
                if ("data" in modifiers && declKw == "class" && countPrimaryCtorProperties(openParenIdx, closeParenIdx) == 2) {
                    twoFieldClasses += clsName
                }
            }
        } else if (ownerCls != null && !inCompanion && clsName != null && "public" in modifiers) {
            inspectNestedVariantDeclaration(
                ownerCls = ownerCls,
                variantName = clsName,
                declKw = declKw,
                modifiers = modifiers,
                typeParamSlice = typeParamSlice,
                hasExplicitCtorKw = hasExplicitCtorKw,
                openParenIdx = openParenIdx,
                closeParenIdx = closeParenIdx,
                afterHeaderIdx = ctorScan,
                endExclusive = endExclusive,
            )
        }

        val braceIdx = findDeclarationBodyBrace(ctorScan, endExclusive)
        if (braceIdx < 0) return null
        val closeEnd = findMatchingBraceEndToken(braceIdx)
        if (ownerCls == null && clsName != null) {
            scanScope(braceIdx + 1, closeEnd - 1, packageName, ownerCls = clsName, inCompanion = false)
        }
        return closeEnd
    }

    private fun inspectNestedVariantDeclaration(
        ownerCls: String,
        variantName: String,
        declKw: String,
        modifiers: Set<String>,
        typeParamSlice: String?,
        hasExplicitCtorKw: Boolean,
        openParenIdx: Int,
        closeParenIdx: Int,
        afterHeaderIdx: Int,
        endExclusive: Int,
    ) {
        if ("data" in modifiers && declKw == "object" && punctAt(afterHeaderIdx) == ':') {
            val baseOwner = identTextAt(nextNonTriviaIndex(afterHeaderIdx + 1, endExclusive))
            if (baseOwner == ownerCls) {
                recordExternalVariantArity(ownerCls, variantName, 0, isUnit = false)
            }
            return
        }
        if (declKw == "class" && typeParamSlice != null && openParenIdx >= 0 && punctAt(afterHeaderIdx) == ':') {
            val typeArity = splitTopLevelCommaList(typeParamSlice).size
            if (typeArity <= 0) return
            val baseIdx = nextNonTriviaIndex(afterHeaderIdx + 1, endExclusive)
            if (identTextAt(baseIdx) != ownerCls || punctAt(nextNonTriviaIndex(baseIdx + 1, endExclusive)) != '<') {
                return
            }
            val isEmptyUnitParens =
                !hasExplicitCtorKw &&
                    "data" !in modifiers &&
                    nextNonTriviaIndex(openParenIdx + 1, closeParenIdx) == closeParenIdx
            recordExternalVariantArity(ownerCls, variantName, typeArity, isUnit = isEmptyUnitParens)
        }
    }

    private fun findDeclarationBodyBrace(afterHeaderIdx: Int, endExclusive: Int): Int {
        if (punctAt(afterHeaderIdx) == '{') return afterHeaderIdx
        if (punctAt(afterHeaderIdx) != ':' && identTextAt(afterHeaderIdx) != "where") return -1
        var k = afterHeaderIdx
        var parenDepth = 0
        var angleDepth = 0
        while (k < endExclusive) {
            val tok = tokens[k]
            if (tok is KotlinLexToken.Newline && parenDepth == 0 && angleDepth == 0) {
                val next = nextNonTriviaIndex(k + 1, endExclusive)
                if (startsSiblingDeclaration(next, endExclusive)) return -1
            } else if (tok.interpolationDepth == 0 && tok is KotlinLexToken.Punct) {
                when (tok.ch) {
                    '(' -> parenDepth++
                    ')' -> if (parenDepth > 0) parenDepth--
                    '<' -> angleDepth++
                    '>' -> if (angleDepth > 0) angleDepth--
                    '{' -> if (parenDepth == 0 && angleDepth == 0) return k
                    ';' -> if (parenDepth == 0 && angleDepth == 0) return -1
                }
            }
            k++
        }
        return -1
    }

    private fun startsSiblingDeclaration(tokenIdx: Int, endExclusive: Int): Boolean {
        if (tokenIdx >= endExclusive) return true
        if (punctAt(tokenIdx) == '@' || punctAt(tokenIdx) == '}') return true
        val word = identTextAt(tokenIdx) ?: return false
        return word == "package" ||
            word == "import" ||
            word in KOTLIN_DECL_MODIFIERS ||
            (word in KOTLIN_DECL_KEYWORDS && word != "where")
    }

    private fun inspectPublicFunctionDeclaration(
        funKwIdx: Int,
        endExclusive: Int,
        packageName: String,
        ownerCls: String?,
    ) {
        var cur = nextNonTriviaIndex(funKwIdx + 1, endExclusive)
        if (punctAt(cur) == '<') {
            val closeAngle = findMatchingTypeAngleToken(cur)
            if (closeAngle > cur) {
                cur = nextNonTriviaIndex(closeAngle + 1, endExclusive)
            }
        }
        val fnName = identTextAt(cur) ?: return
        val parenIdx = nextNonTriviaIndex(cur + 1, endExclusive)
        if (punctAt(parenIdx) != '(') return
        val closeParen = findMatchingParenToken(parenIdx)
        if (closeParen < parenIdx) return

        val paramNames = extractNonDefaultParamNames(parenIdx, closeParen)
        if (paramNames.isNotEmpty()) {
            val shape = paramNames.size to paramNames
            if (ownerCls == null) {
                fnConstParams.putIfMissing("$packageName.$fnName", shape)
                fnConstParams.putIfMissing(fnName, shape)
            } else {
                fnConstParams.putIfMissing("$packageName.$ownerCls.$fnName", shape)
                fnConstParams.putIfMissing("$ownerCls.$fnName", shape)
            }
        }
        if (ownerCls != null) return

        pkgMapBySym.getOrPut(fnName) { linkedSetOf() } += packageName
        inspectTopLevelFnReturnType(fnName, packageName, closeParen + 1, endExclusive)
    }

    private fun inspectTopLevelFnReturnType(
        fnName: String,
        packageName: String,
        afterParenIdx: Int,
        endExclusive: Int,
    ) {
        val colonIdx = nextNonTriviaIndex(afterParenIdx, endExclusive)
        if (punctAt(colonIdx) != ':') return
        val retStart = nextNonTriviaIndex(colonIdx + 1, endExclusive)
        readDottedIdentifier(retStart, endExclusive)?.first?.let { dotted ->
            candidateTwoFieldFns += fnName to dotted.substringAfterLast('.')
        }

        val retTokens = mutableListOf<KotlinLexToken>()
        var k = retStart
        var angleDepth = 0
        var parenDepth = 0
        var bracketDepth = 0
        while (k < endExclusive) {
            val tok = tokens[k]
            val atTop = angleDepth == 0 && parenDepth == 0 && bracketDepth == 0
            if (atTop) {
                if (tok is KotlinLexToken.Newline && startsSiblingDeclaration(nextNonTriviaIndex(k + 1, endExclusive), endExclusive)) {
                    break
                }
                if (identTextAt(k) == "where") break
                val ch = punctAt(k)
                if (ch == '=' || ch == '{' || ch == ';') break
            }
            if (tok.interpolationDepth == 0 && tok is KotlinLexToken.Punct) {
                when (tok.ch) {
                    '<' -> angleDepth++
                    '>' -> if (angleDepth > 0) angleDepth--
                    '(' -> parenDepth++
                    ')' -> if (parenDepth > 0) parenDepth--
                    '[' -> bracketDepth++
                    ']' -> if (bracketDepth > 0) bracketDepth--
                }
            }
            if (tok !is KotlinLexToken.Whitespace &&
                tok !is KotlinLexToken.Newline &&
                tok !is KotlinLexToken.LineComment &&
                tok !is KotlinLexToken.BlockComment
            ) {
                retTokens += tok
            }
            k++
        }
        if (retTokens.isEmpty()) return
        val lastTok = retTokens.last()
        if (lastTok !is KotlinLexToken.Punct || lastTok.ch != '?') return
        val hasArrow = retTokens.any { it is KotlinLexToken.Arrow }
        val parenthesizedFnNullable =
            retTokens.size >= 3 &&
                retTokens.first().let { it is KotlinLexToken.Punct && it.ch == '(' } &&
                retTokens[retTokens.size - 2].let { it is KotlinLexToken.Punct && it.ch == ')' }
        if (!hasArrow || parenthesizedFnNullable) {
            nullableReturnFns += "$packageName.$fnName"
        }
    }

    private fun inspectPublicPropertyOrAlias(
        kwIdx: Int,
        declKw: String,
        endExclusive: Int,
        packageName: String,
        ownerCls: String?,
        inCompanion: Boolean,
    ) {
        val nameIdx = nextNonTriviaIndex(kwIdx + 1, endExclusive)
        val symName = identTextAt(nameIdx) ?: return
        val afterSymIdx = nextNonTriviaIndex(nameIdx + 1, endExclusive)
        if (ownerCls == null) {
            if (punctAt(afterSymIdx) != '.') {
                pkgMapBySym.getOrPut(symName) { linkedSetOf() } += packageName
            }
        } else if (!inCompanion && (declKw == "val" || declKw == "var") && punctAt(afterSymIdx) == ':') {
            if (symName == "value") {
                tupleStructArity.putIfMissing(ownerCls, 1)
            } else if (symName.startsWith("field")) {
                val fieldIdx = symName.removePrefix("field").toIntOrNull()
                if (fieldIdx != null && fieldIdx >= 1) {
                    tupleStructArity[ownerCls] = maxOf(tupleStructArity[ownerCls] ?: 0, fieldIdx + 1)
                }
            }
        }
    }

    private fun splitParameterTokenClauses(openParenIdx: Int, closeParenIdx: Int): List<IntRange> {
        val clauses = mutableListOf<IntRange>()
        var clauseStart = openParenIdx + 1
        var angleDepth = 0
        var parenDepth = 0
        var bracketDepth = 0
        var braceDepth = 0
        for (k in (openParenIdx + 1) until closeParenIdx) {
            val ch = punctAt(k) ?: continue
            when (ch) {
                '<' -> angleDepth++
                '>' -> if (angleDepth > 0) angleDepth--
                '(' -> parenDepth++
                ')' -> if (parenDepth > 0) parenDepth--
                '[' -> bracketDepth++
                ']' -> if (bracketDepth > 0) bracketDepth--
                '{' -> braceDepth++
                '}' -> if (braceDepth > 0) braceDepth--
                ',' ->
                    if (angleDepth == 0 && parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) {
                        clauses += clauseStart until k
                        clauseStart = k + 1
                    }
            }
        }
        if (clauseStart < closeParenIdx) {
            clauses += clauseStart until closeParenIdx
        }
        return clauses
    }

    private fun extractNonDefaultParamNames(openParenIdx: Int, closeParenIdx: Int): List<String> {
        val names = mutableListOf<String>()
        for (range in splitParameterTokenClauses(openParenIdx, closeParenIdx)) {
            val rangeEnd = range.last + 1
            var k = nextNonTriviaIndex(range.first, rangeEnd)
            while (punctAt(k) == '@') {
                k = nextNonTriviaIndex(skipAnnotation(k, rangeEnd), rangeEnd)
            }
            if (k >= rangeEnd) continue
            var colonIdx = -1
            var hasDefault = false
            var angleDepth = 0
            var parenDepth = 0
            var bracketDepth = 0
            while (k < rangeEnd) {
                val ch = punctAt(k)
                if (ch != null) {
                    when (ch) {
                        '<' -> angleDepth++
                        '>' -> if (angleDepth > 0) angleDepth--
                        '(' -> parenDepth++
                        ')' -> if (parenDepth > 0) parenDepth--
                        '[' -> bracketDepth++
                        ']' -> if (bracketDepth > 0) bracketDepth--
                        ':' ->
                            if (colonIdx < 0 && angleDepth == 0 && parenDepth == 0 && bracketDepth == 0) {
                                colonIdx = k
                            }
                        '=' ->
                            if (angleDepth == 0 && parenDepth == 0 && bracketDepth == 0) {
                                hasDefault = true
                                break
                            }
                    }
                }
                k++
            }
            if (hasDefault || colonIdx <= range.first) continue
            val prevTok = previousNonTriviaToken(colonIdx)
            val paramName =
                when (prevTok) {
                    is KotlinLexToken.Ident -> prevTok.rawText
                    is KotlinLexToken.BacktickIdent -> prevTok.ident
                    else -> null
                }
            if (!paramName.isNullOrEmpty()) {
                names += paramName
            }
        }
        return names
    }

    private fun countPrimaryCtorProperties(openParenIdx: Int, closeParenIdx: Int): Int {
        var count = 0
        for (range in splitParameterTokenClauses(openParenIdx, closeParenIdx)) {
            val rangeEnd = range.last + 1
            var k = nextNonTriviaIndex(range.first, rangeEnd)
            while (punctAt(k) == '@') {
                k = nextNonTriviaIndex(skipAnnotation(k, rangeEnd), rangeEnd)
            }
            while (k < rangeEnd) {
                val ch = punctAt(k)
                if (ch == ':') break
                val word = identTextAt(k)
                if (word == "val" || word == "var") {
                    count++
                    break
                }
                k++
            }
        }
        return count
    }
}
