package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.io.buffered
import io.github.kotlinmania.io.files.SystemFileSystem
import io.github.kotlinmania.io.readString
import io.github.kotlinmania.procmacro2.Delimiter
import io.github.kotlinmania.procmacro2.Spacing
import io.github.kotlinmania.procmacro2.TokenStream
import io.github.kotlinmania.procmacro2.TokenTree
import io.github.kotlinmania.syn.Attribute
import io.github.kotlinmania.syn.Expr
import io.github.kotlinmania.syn.Item
import io.github.kotlinmania.syn.Lit
import io.github.kotlinmania.syn.Meta
import io.github.kotlinmania.syn.ModContent
import io.github.kotlinmania.syn.TraitItem
import io.github.kotlinmania.syn.TypeParamBound
import io.github.kotlinmania.syn.UseTree
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
)

internal data class ExternalCrateAbiMetadata(
    val symbolPackages: Map<String, Map<String, List<String>>> = emptyMap(),
    val twoFieldResultFns: Set<String> = emptySet(),
    val nullableReturnFns: Set<String> = emptySet(),
)

private var diskCrateRawAbiMetadataCache: Map<String, CrateRawAbiMetadata> = emptyMap()
private var diskExternalCrateAbiCache: Map<String, ExternalCrateAbiMetadata> = emptyMap()

private fun readScannerKmFileText(path: KmPath): String =
    SystemFileSystem.source(path).buffered().use { it.readString() }

private fun isScannerKmRegularFile(path: KmPath): Boolean =
    SystemFileSystem.metadataOrNull(path)?.isRegularFile == true

private fun isScannerKmDirectory(path: KmPath): Boolean =
    SystemFileSystem.metadataOrNull(path)?.isDirectory == true

internal fun discoverCrateRawAbiMetadataFromDisk(
    inputPath: String?,
    sourceRoot: String? = null,
    rawAbiMode: Boolean = false,
    splitModuleRoots: Boolean = false,
    finalStructs: Boolean = false,
): CrateRawAbiMetadata {
    val rootCandidate =
        when {
            !sourceRoot.isNullOrBlank() -> KmPath(sourceRoot)
            !inputPath.isNullOrBlank() -> {
                val rawFile = KmPath(inputPath)
                if (!isScannerKmRegularFile(rawFile)) return CrateRawAbiMetadata()
                val resolvedFile = SystemFileSystem.resolve(rawFile)
                var cur: KmPath? = resolvedFile.parent
                var found: KmPath? = cur
                var depth = 0
                while (cur != null && depth < 12) {
                    if (isScannerKmRegularFile(KmPath(cur, "lib.rs")) || cur.name == "src") {
                        found = cur
                        break
                    }
                    if (isScannerKmRegularFile(KmPath(cur, "Cargo.toml"))) {
                        break
                    }
                    cur = cur.parent
                    depth++
                }
                found
            }
            else -> null
        } ?: return CrateRawAbiMetadata()

    if (!isScannerKmDirectory(rootCandidate)) return CrateRawAbiMetadata()
    val resolvedRoot = SystemFileSystem.resolve(rootCandidate)
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
            if (isScannerKmDirectory(entry)) {
                collectDir(entry)
            } else if (isScannerKmRegularFile(entry) && entry.name.endsWith(".rs", ignoreCase = true)) {
                collectedSources += relEntry to readScannerKmFileText(entry)
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

private data class RawFieldTokenSpec(
    val ktName: String,
    val typeTokens: List<TokenTree>,
)

private data class RawRecordTokenDecl(
    val subpkg: String,
    val ktName: String,
    val isUnion: Boolean,
    val packAlign: Int?,
    val fields: List<RawFieldTokenSpec>,
)

private data class RawFnTypeTokenSpec(
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
    val traitResultMethods = linkedMapOf<String, List<String>>()

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
        scanModuleTokenTrees(
            trees = tokens,
            subpkg = subpkg,
            rawAbiMode = rawAbiMode,
            finalStructs = finalStructs,
            splitModuleRoots = splitModuleRoots,
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
            "i8", "c_char", "c_schar" -> "0" to "I8"
            "u8", "c_uchar" -> "0u" to "U8"
            "i16", "c_short" -> "0" to "I16"
            "u16", "c_ushort" -> "0u" to "U16"
            "i32", "c_int" -> "0" to "I32"
            "u32", "c_uint" -> "0u" to "U32"
            "c_long" -> if (ptrWidth == 64 && !isWindows) "0L" to "I64" else "0" to "I32"
            "c_ulong" -> if (ptrWidth == 64 && !isWindows) "0uL" to "U64" else "0u" to "U32"
            "i64", "c_longlong" -> "0L" to "I64"
            "u64", "c_ulonglong" -> "0uL" to "U64"
            "i128" -> "I128()" to "I128"
            "u128" -> "U128()" to "U128"
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
            "f32", "c_float" -> "0.0f" to "F32"
            "f64", "c_double" -> "0.0" to "F64"
            "bool" -> "false" to null
            "()" -> "Unit" to null
            else -> null to null
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
                    valueParamPackTemplates.putIfMissing(simpleName, packTemplate)
                    if (layout.subpkg.isNotEmpty()) {
                        valueParamPackTemplates.putIfMissing("${layout.subpkg}.$simpleName", packTemplate)
                    }
                }
                if (layout.fields.size in 1..64) {
                    val unpackTemplate = buildRegisterUnpackExpr("__TYPE__", "rawRet", layout)
                    if (unpackTemplate != null) {
                        valueReturnUnpackTemplates.putIfMissing(simpleName, unpackTemplate)
                        if (layout.subpkg.isNotEmpty()) {
                            valueReturnUnpackTemplates.putIfMissing("${layout.subpkg}.$simpleName", unpackTemplate)
                        }
                    }
                }
            } else if (layout.sizeBytes > 0) {
                val memSlotExpr = buildMemoryCopySlotExpr("__SELF__", layout)
                if (memSlotExpr != null) {
                    valueParamPackTemplates.putIfMissing(simpleName, memSlotExpr)
                    if (layout.subpkg.isNotEmpty()) {
                        valueParamPackTemplates.putIfMissing("${layout.subpkg}.$simpleName", memSlotExpr)
                    }
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
                fnPtrInvokeTemplates.putIfMissing(samName, wrapperExpr)
                if (spec.subpkg.isNotEmpty()) {
                    fnPtrInvokeTemplates.putIfMissing("${spec.subpkg}.$samName", wrapperExpr)
                }
            }
        }
    }

    val fnReturnTypes = rawFnReturnTypes.filterValues { it.isNotEmpty() }

    val declaredTraitAsts = linkedMapOf<String, Item.Trait>()
    val selfParameterizedTraits = linkedSetOf<String>()
    val traitSupertraits = linkedMapOf<String, List<String>>()
    val crateMacroSrcs = normalizedSources.map { it.second }.filter { "macro_rules" in it || "macro " in it }
    val anyMacroDefinesTrait = crateMacroSrcs.any { "trait " in it || "trait\n" in it || "trait\t" in it }
    for ((relFile, text) in normalizedSources) {
        if ("trait" !in text && !(anyMacroDefinesTrait && "!" in text)) continue
        val recognized = recognizeRustOrNull(text) ?: continue
        val expandedFile = expandFileMacroRules(recognized.file, crateMacroSrcs).file
        val (subpkg, _) = computeSubpkgAndRecordAncestors(relFile)
        fun walkTraitItems(items: List<Item>) {
            for (item in items) {
                if (itemHasDocOnlyCfg(item)) continue
                when (item) {
                    is Item.Trait -> {
                        val ktTrait = KotlinNamingPolicy.Default.toTypeIdentifier(item.ident.toString(), allowRawAbi = rawAbiMode)
                        declaredTraitAsts.putIfMissing(ktTrait, item)
                        if (subpkg.isNotEmpty()) {
                            declaredTraitAsts.putIfMissing("$subpkg.$ktTrait", item)
                        }
                        if (traitReferencesSelfOutsideReceiver(item)) {
                            selfParameterizedTraits += ktTrait
                        }
                        val superNames =
                            item.supertraits
                                .toList()
                                .mapNotNull { bound ->
                                    (bound as? TypeParamBound.Trait)
                                        ?.takeIf { it.maybe == null }
                                        ?.path
                                        ?.segments
                                        ?.last()
                                        ?.ident
                                        ?.toString()
                                        ?.let { KotlinNamingPolicy.Default.toTypeIdentifier(it, allowRawAbi = rawAbiMode) }
                                }.distinct()
                        if (superNames.isNotEmpty()) {
                            traitSupertraits[ktTrait] = superNames
                            if (subpkg.isNotEmpty()) {
                                traitSupertraits["$subpkg.$ktTrait"] = superNames
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
                                is TraitItem.Fn ->
                                    if (isSynResultReturnType(tItem.sig.output)) {
                                        astResultMethods += rustFunctionIdentToKotlin(tItem.sig.ident.toString())
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
                    is Item.Mod -> {
                        val content = item.content
                        if (content is ModContent.Inline && !hasCfgTestModAttribute(item.attrs)) {
                            walkTraitItems(content.items)
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
    )
}

private fun lexScannerTokens(sourceText: String): List<TokenTree> {
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

private fun scanRootAliasesAndExternalReexports(
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

private fun scanRootLibDirectives(
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

private fun extractPubModIdentsFromTokens(trees: List<TokenTree>): List<String> {
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
                val cur = trees[idx]
                if (cur is TokenTree.Punct && cur.value.asChar() == '#') {
                    if ((trees.getOrNull(idx + 1) as? TokenTree.Punct)?.value?.asChar() == '!' &&
                        (trees.getOrNull(idx + 2) as? TokenTree.Group)?.value?.delimiter() == Delimiter.Bracket
                    ) {
                        idx += 3
                        continue
                    }
                    val attrGrp = trees.getOrNull(idx + 1) as? TokenTree.Group
                    if (attrGrp != null && attrGrp.value.delimiter() == Delimiter.Bracket) {
                        pendingAttrs += attrGrp.value.stream().toList()
                        idx += 2
                        continue
                    }
                }
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
                    "i8" -> return "I8"
                    "u8" -> return "U8"
                    "i16" -> return "I16"
                    "u16" -> return "U16"
                    "i32" -> return "I32"
                    "u32" -> return "U32"
                    "i64" -> return "I64"
                    "u64" -> return "U64"
                    "isize" -> return if (rawAbiMode && ptrWidth == 64) "I64" else "I32"
                    "usize" -> return if (rawAbiMode) (if (ptrWidth == 64) "U64" else "U32") else "I32"
                }
            }
        }
    }
    return defaultKind
}

private fun collectFfiTokenTypeIdents(
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

private fun scanModuleTokenTrees(
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
) {
    val pendingAttrs = mutableListOf<List<TokenTree>>()
    var i = 0
    while (i < trees.size) {
        val cur = trees[i]
        if (cur is TokenTree.Punct && cur.value.asChar() == '#') {
            if ((trees.getOrNull(i + 1) as? TokenTree.Punct)?.value?.asChar() == '!' &&
                (trees.getOrNull(i + 2) as? TokenTree.Group)?.value?.delimiter() == Delimiter.Bracket
            ) {
                i += 3
                continue
            }
            val attrGrp = trees.getOrNull(i + 1) as? TokenTree.Group
            if (attrGrp != null && attrGrp.value.delimiter() == Delimiter.Bracket) {
                pendingAttrs += attrGrp.value.stream().toList()
                i += 2
                continue
            }
        }

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
                        val hasGenerics = (trees.getOrNull(afterIdent) as? TokenTree.Punct)?.value?.asChar() == '<'
                        if (!hasGenerics) {
                            if (rawIdent.removePrefix("r#") != "c_void") {
                                putScopedDefault(subpkg, ktType, "$ktType()")
                            }
                            val bodyGroup = findStructOrUnionBodyGroup(trees, afterIdent)
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
                )
            }
            i = endImplIdx
            pendingAttrs.clear()
            continue
        }
        if (nonPubKw == "struct" || nonPubKw == "union" || nonPubKw == "enum" || nonPubKw == "trait" || nonPubKw == "type") {
            val rawIdent = (trees.getOrNull(i + 1) as? TokenTree.Ident)?.value?.toString()
            if (rawIdent != null && !skippedByCfg) {
                registerSymbol(KotlinNamingPolicy.Default.toTypeIdentifier(rawIdent, allowRawAbi = rawAbiMode), subpkg)
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
    if (innerTrees.size < 4 || innerTrees[0] !is TokenTree.Literal) return null
    for (k in 1 until innerTrees.size - 2) {
        if ((innerTrees[k] as? TokenTree.Ident)?.value?.toString() == "fn") {
            val fnIdent = (innerTrees[k + 1] as? TokenTree.Ident)?.value?.toString()?.removePrefix("r#")
            val paramsGrp = (innerTrees[k + 2] as? TokenTree.Group)?.value
            if (fnIdent != null && paramsGrp?.delimiter() == Delimiter.Parenthesis) {
                return fnIdent
            }
        }
    }
    return null
}

private fun extractLinkMacroFnParamTokenLists(innerTrees: List<TokenTree>): List<List<TokenTree>>? {
    if (innerTrees.size < 4 || innerTrees[0] !is TokenTree.Literal) return null
    for (k in 1 until innerTrees.size - 2) {
        if ((innerTrees[k] as? TokenTree.Ident)?.value?.toString() == "fn") {
            return extractNonMethodFnParamTokenLists(innerTrees, k)
        }
    }
    return null
}

private fun extractLinkMacroFnReturnType(innerTrees: List<TokenTree>, rawAbiMode: Boolean): String? {
    if (innerTrees.size < 4 || innerTrees[0] !is TokenTree.Literal) return null
    for (k in 1 until innerTrees.size - 2) {
        if ((innerTrees[k] as? TokenTree.Ident)?.value?.toString() == "fn") {
            return extractFnReturnTypeFromHeader(innerTrees, k, innerTrees.size, rawAbiMode, insideExternBlock = true)
        }
    }
    return null
}

private fun extractFnReturnTypeFromHeader(
    trees: List<TokenTree>,
    startIdx: Int,
    endIdx: Int,
    rawAbiMode: Boolean,
    selfOwnerKt: String? = null,
    insideExternBlock: Boolean = false,
): String? {
    var j = startIdx
    while (j < endIdx && j < trees.size) {
        val cur = trees[j]
        if (cur is TokenTree.Ident) {
            when (cur.value.toString()) {
                "const", "unsafe", "async" -> {
                    j++
                    continue
                }
                "extern" -> {
                    j++
                    if (trees.getOrNull(j) is TokenTree.Literal) j++
                    continue
                }
                "fn" -> {
                    var afterIdent = j + 2
                    if ((trees.getOrNull(afterIdent) as? TokenTree.Punct)?.value?.asChar() == '<') {
                        var depth = 1
                        var k = afterIdent + 1
                        while (k < trees.size && depth > 0) {
                            val ch = (trees[k] as? TokenTree.Punct)?.value?.asChar()
                            if (ch == '<') {
                                depth++
                            } else if (ch == '>' && !isScannerRArrow(trees, k)) {
                                depth--
                            }
                            k++
                        }
                        if (depth != 0) return null
                        afterIdent = k
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
                else -> return null
            }
        } else {
            return null
        }
    }
    return null
}

private fun findFnIdentAfterQualifiers(trees: List<TokenTree>, startIdx: Int): String? {
    var j = startIdx
    while (j < trees.size) {
        val cur = trees[j]
        if (cur is TokenTree.Ident) {
            when (cur.value.toString()) {
                "const", "unsafe", "async", "extern" -> {
                    j++
                    continue
                }
                "fn" -> return (trees.getOrNull(j + 1) as? TokenTree.Ident)?.value?.toString()?.removePrefix("r#")
                else -> return null
            }
        }
        if (cur is TokenTree.Literal) {
            j++
            continue
        }
        return null
    }
    return null
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

private fun isScannerRArrow(trees: List<TokenTree>, gtIdx: Int): Boolean {
    if (gtIdx <= 0) return false
    val prev = trees[gtIdx - 1] as? TokenTree.Punct ?: return false
    return prev.value.asChar() == '-' && prev.value.spacing() == Spacing.Joint
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
        val tok = body[j]
        if (tok is TokenTree.Punct && tok.value.asChar() == '#') {
            val grp = body.getOrNull(j + 1) as? TokenTree.Group
            if (grp != null && grp.value.delimiter() == Delimiter.Bracket) {
                itemAttrs += grp.value.stream().toList()
                j += 2
                continue
            }
        }
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
): List<RawFieldTokenSpec> {
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
            mapTokensFieldTypeQuick(rhsTokens, rawAbiMode)?.let { mappedTy ->
                val recordedTy = if (makeNullableForUnion && !mappedTy.endsWith("?")) "$mappedTy?" else mappedTy
                structFieldTypesOut.putIfMissing("$ownerKtType.$ktField", recordedTy)
                if (subpkg.isNotEmpty()) {
                    structFieldTypesOut.putIfMissing("$subpkg.$ownerKtType.$ktField", recordedTy)
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
            mapTokensFieldTypeQuick(rhsTokens, rawAbiMode)?.let { mappedTy ->
                val recordedTy = if (makeNullableForUnion && !mappedTy.endsWith("?")) "$mappedTy?" else mappedTy
                structFieldTypesOut.putIfMissing("$ownerKtType.$ktField", recordedTy)
                if (subpkg.isNotEmpty()) {
                    structFieldTypesOut.putIfMissing("$subpkg.$ownerKtType.$ktField", recordedTy)
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

private fun mapTokensFieldTypeQuick(
    tokens: List<TokenTree>,
    rawAbiMode: Boolean,
    selfOwnerKt: String? = null,
    insideExternBlock: Boolean = false,
    numericKinds: Map<String, String>? = null,
    subpkg: String = "",
): String? {
    if (tokens.isEmpty()) return null
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
                "u8", "c_uchar" -> "UByte"
                "i8", "c_char", "c_schar" -> "Byte"
                "u16", "c_ushort" -> "UShort"
                "i16", "c_short" -> "Short"
                "u32", "c_uint" -> "UInt"
                "i32", "c_int" -> "Int"
                "u64", "c_ulong", "c_ulonglong" -> "ULong"
                "i64", "c_long", "c_longlong" -> "Long"
                "u128" -> "U128"
                "i128" -> "I128"
                "usize", "isize" -> "Int"
                "f32", "c_float" -> "Float"
                "f64", "c_double" -> "Double"
                "bool" -> "Boolean"
                "Self" -> selfOwnerKt ?: "CVoid"
                else -> KotlinNamingPolicy.Default.toTypeIdentifier(pointeeLeaf, allowRawAbi = false).ifEmpty { "CVoid" }
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
            if (resolvedElemKind != null) {
                return when (resolvedElemKind) {
                    "U8" -> "UByteArray"
                    "I8" -> "ByteArray"
                    "U16" -> "UShortArray"
                    "I16" -> "ShortArray"
                    "U32" -> "UIntArray"
                    "I32" -> "IntArray"
                    "U64" -> "ULongArray"
                    "I64" -> "LongArray"
                    "F32" -> "FloatArray"
                    "F64" -> "DoubleArray"
                    else -> null
                }
            }
            return when (elemLeaf) {
                "u8", "c_uchar" -> "UByteArray"
                "c_char" -> if (rawAbiMode) "ByteArray" else "UByteArray"
                "i8", "c_schar" -> "ByteArray"
                "u16", "c_ushort" -> "UShortArray"
                "i16", "c_short" -> "ShortArray"
                "u32", "c_uint" -> "UIntArray"
                "i32", "c_int" -> "IntArray"
                "u64", "c_ulong", "c_ulonglong" -> "ULongArray"
                "i64", "c_long", "c_longlong" -> "LongArray"
                "usize" -> if (rawAbiMode) "ULongArray" else "IntArray"
                "isize" -> if (rawAbiMode) "LongArray" else "IntArray"
                "f32", "c_float" -> "FloatArray"
                "f64", "c_double" -> "DoubleArray"
                "bool" -> "BooleanArray"
                else -> null
            }
        }
    }
    val leaf = extractSimplePathLeafIdent(tokens) ?: return null
    if (leaf == "Self") return selfOwnerKt
    return when (leaf) {
        "u8" -> "UByte"
        "i8" -> "Byte"
        "u16" -> "UShort"
        "i16" -> "Short"
        "u32" -> "UInt"
        "i32" -> "Int"
        "u64" -> "ULong"
        "i64" -> "Long"
        "u128" -> "U128"
        "i128" -> "I128"
        "usize" -> if (rawAbiMode) (if (TranslationSession.cfgTargetProfile.pointerWidth == 64) "ULong" else "UInt") else "Int"
        "isize" -> if (rawAbiMode) (if (TranslationSession.cfgTargetProfile.pointerWidth == 64) "Long" else "Int") else "Int"
        "f32" -> "Float"
        "f64" -> "Double"
        "bool" -> "Boolean"
        else -> KotlinNamingPolicy.Default.toTypeIdentifier(leaf, allowRawAbi = rawAbiMode).takeIf { it.isNotEmpty() }
    }
}

private fun extractSimplePathLeafIdent(tokens: List<TokenTree>): String? {
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

private fun extractOptionAngleInnerTokens(rhsTokens: List<TokenTree>): List<TokenTree>? {
    val tailIdx = findPathTailIdentIndex(rhsTokens) ?: return null
    if ((rhsTokens[tailIdx] as TokenTree.Ident).value.toString() != "Option") return null
    if ((rhsTokens.getOrNull(tailIdx + 1) as? TokenTree.Punct)?.value?.asChar() != '<') return null
    if ((rhsTokens.lastOrNull() as? TokenTree.Punct)?.value?.asChar() != '>') return null
    val inner = rhsTokens.subList(tailIdx + 2, rhsTokens.size - 1)
    return inner.takeIf { it.isNotEmpty() }
}

private fun tryParseBareFnTokens(rawTokens: List<TokenTree>): Pair<List<List<TokenTree>>, List<TokenTree>>? {
    val tokens = stripLeadingScannerAttrs(rawTokens)
    var i = 0
    if ((tokens.getOrNull(i) as? TokenTree.Ident)?.value?.toString() == "for" &&
        (tokens.getOrNull(i + 1) as? TokenTree.Punct)?.value?.asChar() == '<'
    ) {
        var depth = 1
        i += 2
        while (i < tokens.size && depth > 0) {
            val ch = (tokens[i] as? TokenTree.Punct)?.value?.asChar()
            when {
                ch == '<' -> depth++
                ch == '>' && !isScannerRArrow(tokens, i) -> depth--
            }
            i++
        }
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

private fun scalarKindSizeAndAlign(kind: String): Pair<Int, Int>? =
    when (kind) {
        "I8", "U8", "BOOL" -> 1 to 1
        "I16", "U16" -> 2 to 2
        "I32", "U32", "F32" -> 4 to 4
        "I64", "U64", "F64" -> 8 to 8
        "I128", "U128" -> 16 to 16
        else -> null
    }

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
        "i8", "c_char", "c_schar" -> return "I8"
        "u8", "c_uchar" -> return "U8"
        "i16", "c_short" -> return "I16"
        "u16", "c_ushort" -> return "U16"
        "i32", "c_int" -> return "I32"
        "u32", "c_uint" -> return "U32"
        "i64", "c_long", "c_longlong" -> return "I64"
        "u64", "c_ulong", "c_ulonglong" -> return "U64"
        "i128" -> return "I128"
        "u128" -> return "U128"
        "isize" -> return if (rawAbiMode && TranslationSession.cfgTargetProfile.pointerWidth == 64) "I64" else "I32"
        "usize" -> return if (rawAbiMode) (if (TranslationSession.cfgTargetProfile.pointerWidth == 64) "U64" else "U32") else "I32"
        "f32", "c_float" -> return "F32"
        "f64", "c_double" -> return "F64"
        "bool" -> return "BOOL"
    }
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
                        when (shape.elemKind) {
                            "U8" -> "ubyteArrayOf"
                            "I8" -> "byteArrayOf"
                            "U16" -> "ushortArrayOf"
                            "I16" -> "shortArrayOf"
                            "U32" -> "uintArrayOf"
                            "I32" -> "intArrayOf"
                            "BOOL" -> "booleanArrayOf"
                            else -> return null
                        }
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

private fun extractNonMethodFnParamTokenLists(
    trees: List<TokenTree>,
    startIdx: Int,
): List<List<TokenTree>>? {
    var j = startIdx
    while (j < trees.size) {
        val cur = trees[j]
        if (cur is TokenTree.Ident) {
            when (cur.value.toString()) {
                "const", "unsafe", "async" -> {
                    j++
                    continue
                }
                "extern" -> {
                    j++
                    if (trees.getOrNull(j) is TokenTree.Literal) j++
                    continue
                }
                "fn" -> {
                    var afterIdent = j + 2
                    if ((trees.getOrNull(afterIdent) as? TokenTree.Punct)?.value?.asChar() == '<') {
                        var depth = 1
                        var k = afterIdent + 1
                        while (k < trees.size && depth > 0) {
                            val ch = (trees[k] as? TokenTree.Punct)?.value?.asChar()
                            if (ch == '<') {
                                depth++
                            } else if (ch == '>' && !isScannerRArrow(trees, k)) {
                                depth--
                            }
                            k++
                        }
                        if (depth != 0) return null
                        afterIdent = k
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
                else -> return null
            }
        } else {
            return null
        }
    }
    return null
}

private fun scanInherentImplAssociatedFunctions(
    trees: List<TokenTree>,
    implKwIdx: Int,
    endImplIdx: Int,
    subpkg: String,
    rawAbiMode: Boolean,
    registerFnSig: (String, String, List<List<TokenTree>>?, String?, Boolean) -> Unit,
) {
    val headerSlice = trees.subList(implKwIdx + 1, endImplIdx)
    if (headerSlice.any { (it as? TokenTree.Ident)?.value?.toString() == "for" }) return
    val braceTree = headerSlice.lastOrNull { (it as? TokenTree.Group)?.value?.delimiter() == Delimiter.Brace } as? TokenTree.Group ?: return
    val beforeBrace = headerSlice.subList(0, headerSlice.indexOf(braceTree))
    if (beforeBrace.any { (it as? TokenTree.Punct)?.value?.asChar() == '<' }) return
    val ownerLeaf = extractSimplePathLeafIdent(beforeBrace) ?: return
    val ownerKt = KotlinNamingPolicy.Default.toTypeIdentifier(ownerLeaf, allowRawAbi = rawAbiMode)
    if (ownerKt.isEmpty()) return

    val bodyTrees = braceTree.value.stream().toList()
    val pendingAttrs = mutableListOf<List<TokenTree>>()
    var i = 0
    while (i < bodyTrees.size) {
        val cur = bodyTrees[i]
        if (cur is TokenTree.Punct && cur.value.asChar() == '#') {
            val attrGrp = bodyTrees.getOrNull(i + 1) as? TokenTree.Group
            if (attrGrp != null && attrGrp.value.delimiter() == Delimiter.Bracket) {
                pendingAttrs += attrGrp.value.stream().toList()
                i += 2
                continue
            }
        }
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
                val retTy = extractFnReturnTypeFromHeader(bodyTrees, kwIdx, endFnIdx, rawAbiMode, ownerKt)
                if (pLists != null || retTy != null) {
                    registerFnSig(subpkg, "$ownerKt.$ktMethod", pLists, retTy, false)
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
        val cur = trees[i]
        if (cur is TokenTree.Punct && cur.value.asChar() == '#') {
            if ((trees.getOrNull(i + 1) as? TokenTree.Punct)?.value?.asChar() == '!' &&
                (trees.getOrNull(i + 2) as? TokenTree.Group)?.value?.delimiter() == Delimiter.Bracket
            ) {
                i += 3
                continue
            }
            val attrGrp = trees.getOrNull(i + 1) as? TokenTree.Group
            if (attrGrp != null && attrGrp.value.delimiter() == Delimiter.Bracket) {
                pendingAttrs += attrGrp.value.stream().toList()
                i += 2
                continue
            }
        }
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

private fun <K, V> MutableMap<K, V>.putIfMissing(key: K, value: V) {
    if (key !in this) {
        this[key] = value
    }
}

private val EXTERNAL_PKG_REGEX = Regex("""^\s*package\s+([A-Za-z0-9_.]+)""")
private val EXTERNAL_LEADING_ANN_REGEX = Regex("""^(?:@[A-Za-z_][A-Za-z0-9_.]*(?:\([^)]*\))?\s+)+""")
private val EXTERNAL_DATA_CLASS_REGEX = Regex("""^public\s+data\s+class\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
private val EXTERNAL_PUBLIC_FUN_REGEX =
    Regex("""^public\s+(?:(?:inline|infix|operator|suspend|tailrec|external)\s+)*fun\s+(?:<[^>]+>\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
private val EXTERNAL_PUBLIC_TYPE_OR_VAL_REGEX =
    Regex("""^public\s+(?:(?:const|sealed|data|enum|value|abstract|open|inner|expect|actual|external)\s+)*(?:class|interface|object|typealias|val|var)\s+([A-Za-z_][A-Za-z0-9_]*)""")
private val EXTERNAL_RETURN_TYPE_REGEX = Regex("""^\s*:\s*([A-Za-z_][A-Za-z0-9_.]*)""")

internal fun discoverExternalCrateAbiFromDisk(
    anchorPath: String?,
    crateNamespaces: Map<String, String>,
): ExternalCrateAbiMetadata {
    val externalNs =
        crateNamespaces
            .mapValues { it.value.trim().removeSuffix(".") }
            .filterValues { it.isNotEmpty() }
    if (externalNs.isEmpty() || anchorPath.isNullOrBlank()) return ExternalCrateAbiMetadata()

    val rawAnchor = KmPath(anchorPath)
    val startDir =
        when {
            isScannerKmDirectory(rawAnchor) -> SystemFileSystem.resolve(rawAnchor)
            isScannerKmRegularFile(rawAnchor) -> SystemFileSystem.resolve(rawAnchor).parent
            else -> null
        } ?: return ExternalCrateAbiMetadata()

    var currentAnchorRepo: KmPath? = null
    var workspaceRoot: KmPath? = null
    var cur: KmPath? = startDir
    var depth = 0
    while (cur != null && depth < 24) {
        val hasRepoMarker =
            isScannerKmRegularFile(KmPath(cur, "gradle.properties")) ||
                isScannerKmRegularFile(KmPath(cur, "build.gradle.kts"))
        if (hasRepoMarker) {
            currentAnchorRepo = cur
            workspaceRoot = cur.parent
            break
        }
        cur = cur.parent
        depth++
    }
    val resolvedWorkspace = workspaceRoot?.takeIf(::isScannerKmDirectory) ?: return ExternalCrateAbiMetadata()

    val workspaceRepos =
        SystemFileSystem
            .list(resolvedWorkspace)
            .filter { isScannerKmDirectory(it) && it.name.endsWith("-kotlin") }
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
            val propsFile = KmPath(dir, "gradle.properties")
            if (!isScannerKmRegularFile(propsFile)) {
                null
            } else {
                parseGradleProperties(readScannerKmFileText(propsFile)).projectNamespace?.trim()?.removeSuffix(".")
            }
        }

    fun collectKtFilesRecursive(dir: KmPath, out: MutableList<KmPath>) {
        for (entry in SystemFileSystem.list(dir).sortedBy { it.toString() }) {
            if (isScannerKmDirectory(entry)) {
                collectKtFilesRecursive(entry, out)
            } else if (isScannerKmRegularFile(entry) && entry.name.endsWith(".kt", ignoreCase = true)) {
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
        val exactRoot =
            candidateRepos.firstNotNullOfOrNull { dir ->
                val propsNs = readRepoPropsNs(dir)
                val matches =
                    (propsNs != null && propsNs == crateNs) ||
                        (propsNs == null && dirMatchesCrateKey(dir))
                if (!matches) return@firstNotNullOfOrNull null
                KmPath(dir, "src/common/commonMain/kotlin").takeIf(::isScannerKmDirectory)
            }
        val prefixRoot =
            if (exactRoot != null) {
                null
            } else {
                candidateRepos.firstNotNullOfOrNull { dir ->
                    val propsNs = readRepoPropsNs(dir) ?: return@firstNotNullOfOrNull null
                    if (!crateNs.startsWith("$propsNs.")) return@firstNotNullOfOrNull null
                    KmPath(dir, "src/common/commonMain/kotlin/${crateNs.replace('.', '/')}").takeIf(::isScannerKmDirectory)
                }
            }
        val kotlinRoot = exactRoot ?: prefixRoot ?: continue
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

    for (matched in matchedCrates) {
        val crateKey = matched.crateKey
        val crateNs = matched.crateNs
        val pkgMapBySym = linkedMapOf<String, LinkedHashSet<String>>()
        for (ktFile in matched.ktFiles) {
            scanExternalKotlinFileSymbols(
                sourceText = readScannerKmFileText(ktFile),
                pkgMapBySym = pkgMapBySym,
                twoFieldResultFns = twoFieldResultFns,
                nullableReturnFns = nullableReturnFns,
            )
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

private fun scanExternalKotlinFileSymbols(
    sourceText: String,
    pkgMapBySym: MutableMap<String, LinkedHashSet<String>>,
    twoFieldResultFns: MutableSet<String>,
    nullableReturnFns: MutableSet<String>,
) {
    val lines = sourceText.lines()
    var pkg: String? = null
    for (line in lines) {
        val m = EXTERNAL_PKG_REGEX.find(line)
        if (m != null) {
            pkg = m.groupValues[1].trim()
            break
        }
    }
    val packageName = pkg ?: return
    val twoFieldClasses = linkedSetOf<String>()
    val candidateTwoFieldFns = mutableListOf<Pair<String, String>>()
    var braceDepth = 0
    var inBlockComment = false

    fun updateBraceDepth(line: String) {
        var i = 0
        var inStr = false
        var inChar = false
        while (i < line.length) {
            val ch = line[i]
            val next = if (i + 1 < line.length) line[i + 1] else '\u0000'
            when {
                inBlockComment -> {
                    if (ch == '*' && next == '/') {
                        inBlockComment = false
                        i += 2
                        continue
                    }
                }
                inStr -> {
                    if (ch == '\\') {
                        i += 2
                        continue
                    }
                    if (ch == '"') inStr = false
                }
                inChar -> {
                    if (ch == '\\') {
                        i += 2
                        continue
                    }
                    if (ch == '\'') inChar = false
                }
                ch == '/' && next == '/' -> break
                ch == '/' && next == '*' -> {
                    inBlockComment = true
                    i += 2
                    continue
                }
                ch == '"' -> inStr = true
                ch == '\'' -> inChar = true
                ch == '{' -> braceDepth++
                ch == '}' -> if (braceDepth > 0) braceDepth--
            }
            i++
        }
    }

    fun extractAfterMatchingParen(startLineIdx: Int): Pair<String, String>? {
        val joined = StringBuilder()
        var parenDepth = 0
        var started = false
        var idx = startLineIdx
        while (idx < lines.size) {
            val l = lines[idx]
            for (chIdx in l.indices) {
                val ch = l[chIdx]
                joined.append(ch)
                if (ch == '(') {
                    parenDepth++
                    started = true
                } else if (ch == ')' && started) {
                    parenDepth--
                    if (parenDepth == 0) {
                        val restOnLine = l.substring(chIdx + 1)
                        val nextLine = lines.getOrNull(idx + 1).orEmpty()
                        return joined.toString() to "$restOnLine $nextLine"
                    }
                }
            }
            joined.append('\n')
            idx++
        }
        return null
    }

    for (lineIdx in lines.indices) {
        val line = lines[lineIdx]
        val depthBefore = braceDepth
        val wasInComment = inBlockComment
        updateBraceDepth(line)
        if (wasInComment || depthBefore != 0) continue

        val header = line.trim().replace(EXTERNAL_LEADING_ANN_REGEX, "")
        if (!header.startsWith("public ")) continue

        EXTERNAL_DATA_CLASS_REGEX.find(header)?.let { dcMatch ->
            val clsName = dcMatch.groupValues[1]
            pkgMapBySym.getOrPut(clsName) { linkedSetOf() } += packageName
            val paramsText = extractAfterMatchingParen(lineIdx)?.first
            if (paramsText != null) {
                val inner = paramsText.substringAfter('(').substringBeforeLast(')')
                val propCount = Regex("""\b(val|var)\s+""").findAll(inner).count()
                if (propCount == 2) {
                    twoFieldClasses += clsName
                }
            }
        }

        val funMatch = EXTERNAL_PUBLIC_FUN_REGEX.find(header)
        if (funMatch != null) {
            val fnName = funMatch.groupValues[1]
            pkgMapBySym.getOrPut(fnName) { linkedSetOf() } += packageName
            val afterParen = extractAfterMatchingParen(lineIdx)?.second
            val retSimple =
                afterParen
                    ?.let { EXTERNAL_RETURN_TYPE_REGEX.find(it)?.groupValues?.get(1) }
                    ?.substringAfterLast('.')
            if (retSimple != null) {
                candidateTwoFieldFns += fnName to retSimple
            }
            if (afterParen != null && hasNullableExternalReturnType(afterParen)) {
                nullableReturnFns += "$packageName.$fnName"
            }
            continue
        }

        EXTERNAL_PUBLIC_TYPE_OR_VAL_REGEX.find(header)?.let { tvMatch ->
            val symName = tvMatch.groupValues[1]
            pkgMapBySym.getOrPut(symName) { linkedSetOf() } += packageName
        }
    }
    for ((fnName, retSimple) in candidateTwoFieldFns) {
        if (retSimple in twoFieldClasses) {
            twoFieldResultFns += "$packageName.$fnName"
        }
    }
}

private fun hasNullableExternalReturnType(afterParen: String): Boolean {
    var i = 0
    while (i < afterParen.length && afterParen[i].isWhitespace()) i++
    if (i >= afterParen.length || afterParen[i] != ':') return false
    val start = i + 1
    var angleDepth = 0
    var parenDepth = 0
    var bracketDepth = 0
    var end = afterParen.length
    var j = start
    while (j < afterParen.length) {
        val ch = afterParen[j]
        val prev = if (j > 0) afterParen[j - 1] else '\u0000'
        when (ch) {
            '<' -> angleDepth++
            '>' -> if (prev != '-' && angleDepth > 0) angleDepth--
            '(' -> parenDepth++
            ')' -> if (parenDepth > 0) parenDepth--
            '[' -> bracketDepth++
            ']' -> if (bracketDepth > 0) bracketDepth--
            '=', '{' ->
                if (angleDepth == 0 && parenDepth == 0 && bracketDepth == 0) {
                    end = j
                    break
                }
        }
        if (angleDepth == 0 && parenDepth == 0 && bracketDepth == 0 &&
            afterParen.startsWith("where", j) &&
            (j == start || !afterParen[j - 1].isLetterOrDigit()) &&
            (j + 5 >= afterParen.length || afterParen[j + 5].isWhitespace())
        ) {
            end = j
            break
        }
        j++
    }
    val retSlice = afterParen.substring(start, end).trim()
    return retSlice.endsWith("?") && (!retSlice.contains("->") || (retSlice.startsWith("(") && retSlice.endsWith(")?")))
}
