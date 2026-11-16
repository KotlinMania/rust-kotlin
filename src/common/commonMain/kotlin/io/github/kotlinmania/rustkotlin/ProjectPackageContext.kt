package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.io.buffered
import io.github.kotlinmania.io.files.SystemFileSystem
import io.github.kotlinmania.io.readString
import io.github.kotlinmania.io.files.Path as KmPath

public data class GradleProjectProperties(
    public val projectGroup: String? = null,
    public val projectNamespace: String? = null,
    public val projectName: String? = null,
    public val crateNamespaces: Map<String, String> = emptyMap(),
    public val projectFfiName: String? = null,
)

public data class FolderPackageProperties(
    public val packageName: String? = null,
    public val projectGroup: String? = null,
    public val projectNamespace: String? = null,
    public val crateNamespaces: Map<String, String> = emptyMap(),
)

private fun parsePropertiesMap(content: String): Pair<Map<String, String>, String?> {
    val map = linkedMapOf<String, String>()
    var bareLine: String? = null
    for (rawLine in content.lineSequence()) {
        val line = rawLine.trim()
        if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) continue
        val sepIdx = line.indexOfFirst { it == '=' || it == ':' }
        if (sepIdx <= 0) {
            if (bareLine == null && line.all { it.isLetterOrDigit() || it == '.' || it == '_' }) {
                bareLine = line
            }
            continue
        }
        val key = line.substring(0, sepIdx).trim()
        val value = line.substring(sepIdx + 1).trim()
        if (key.isNotEmpty()) {
            map[key] = value
        }
    }
    return map to bareLine
}

private fun extractCrateNamespaceOverrides(map: Map<String, String>): Map<String, String> {
    val overrides = linkedMapOf<String, String>()
    for ((key, value) in map) {
        if (value.isEmpty()) continue
        when {
            key.startsWith("crate.") -> {
                val crateName = key.removePrefix("crate.").trim()
                if (crateName.isNotEmpty()) overrides[rustModuleIdentToKotlin(crateName)] = value
            }
            key.startsWith("namespace.") -> {
                val crateName = key.removePrefix("namespace.").trim()
                if (crateName.isNotEmpty()) overrides[rustModuleIdentToKotlin(crateName)] = value
            }
            key.endsWith(".namespace") && key != "project.namespace" -> {
                val crateName = key.removeSuffix(".namespace").trim()
                if (crateName.isNotEmpty()) overrides[rustModuleIdentToKotlin(crateName)] = value
            }
        }
    }
    return overrides
}

public fun parseGradleProperties(content: String): GradleProjectProperties {
    val (map, _) = parsePropertiesMap(content)
    val group =
        map["project.group"]?.takeIf { it.isNotEmpty() }
            ?: map["group"]?.takeIf { it.isNotEmpty() }
    val name =
        map["project.name"]?.takeIf { it.isNotEmpty() }
            ?: map["name"]?.takeIf { it.isNotEmpty() }
    val explicitNamespace =
        map["project.namespace"]?.takeIf { it.isNotEmpty() }
            ?: map["namespace"]?.takeIf { it.isNotEmpty() }
    val derivedNamespace =
        if (explicitNamespace == null && group != null && name != null) {
            val crateSegment = rustModuleIdentToKotlin(name.removeSuffix("-kotlin"))
            if (crateSegment.isNotEmpty()) "${group.removeSuffix(".")}.$crateSegment" else group
        } else {
            explicitNamespace
        }
    return GradleProjectProperties(
        projectGroup = group,
        projectNamespace = derivedNamespace,
        projectName = name,
        crateNamespaces = extractCrateNamespaceOverrides(map),
        projectFfiName = map["project.ffi.name"]?.takeIf { it.isNotEmpty() },
    )
}

public fun parsePackageProperties(content: String): FolderPackageProperties {
    val (map, bareLine) = parsePropertiesMap(content)
    val pkg =
        map["package"]?.takeIf { it.isNotEmpty() }
            ?: map["package.name"]?.takeIf { it.isNotEmpty() }
            ?: map["namespace"]?.takeIf { it.isNotEmpty() }
            ?: map["project.namespace"]?.takeIf { it.isNotEmpty() }
            ?: bareLine
    val group =
        map["project.group"]?.takeIf { it.isNotEmpty() }
            ?: map["group"]?.takeIf { it.isNotEmpty() }
    val namespace =
        map["project.namespace"]?.takeIf { it.isNotEmpty() }
            ?: map["namespace"]?.takeIf { it.isNotEmpty() }
    return FolderPackageProperties(
        packageName = pkg,
        projectGroup = group,
        projectNamespace = namespace,
        crateNamespaces = extractCrateNamespaceOverrides(map),
    )
}

private fun readKmFileText(path: KmPath): String =
    SystemFileSystem.source(path).buffered().use { it.readString() }

private fun isKmRegularFile(path: KmPath): Boolean =
    SystemFileSystem.metadataOrNull(path)?.isRegularFile == true

private fun isKmDirectory(path: KmPath): Boolean =
    SystemFileSystem.metadataOrNull(path)?.isDirectory == true

private fun startingDirectoryKmPath(startPath: String?): KmPath? {
    if (startPath.isNullOrBlank()) return null
    val initial = KmPath(startPath)
    val resolved =
        if (SystemFileSystem.exists(initial)) {
            SystemFileSystem.resolve(initial)
        } else {
            initial
        }
    return if (isKmDirectory(resolved)) resolved else (resolved.parent ?: resolved)
}

public fun findNearestGradleProperties(startPath: String?): GradleProjectProperties? {
    var current = startingDirectoryKmPath(startPath)
    while (current != null) {
        val candidate = KmPath(current, "gradle.properties")
        if (isKmRegularFile(candidate)) {
            return parseGradleProperties(readKmFileText(candidate))
        }
        current = current.parent
    }
    return null
}

public fun findNearestPackageProperties(startPath: String?): FolderPackageProperties? {
    var current: KmPath? = startingDirectoryKmPath(startPath) ?: return null
    val traversedDirs = mutableListOf<String>()
    val foundLayers = mutableListOf<Pair<FolderPackageProperties, List<String>>>()

    while (current != null) {
        val pkgCandidate = KmPath(current, "package.properties")
        if (isKmRegularFile(pkgCandidate)) {
            val parsed = parsePackageProperties(readKmFileText(pkgCandidate))
            foundLayers += parsed to traversedDirs.reversed()
        }
        val gradleCandidate = KmPath(current, "gradle.properties")
        if (isKmRegularFile(gradleCandidate)) {
            break
        }
        val dirName = current.name
        if (dirName.isNotEmpty()) {
            traversedDirs += dirName
        }
        current = current.parent
    }

    if (foundLayers.isEmpty()) return null

    var resolvedPackage: String? = null
    var resolvedGroup: String? = null
    var resolvedNamespace: String? = null
    val mergedCrateNamespaces = linkedMapOf<String, String>()

    // Merge from outermost ancestor to innermost folder so closer package.properties win
    for ((layer, relativeSubdirs) in foundLayers.asReversed()) {
        if (layer.projectGroup != null) resolvedGroup = layer.projectGroup
        if (layer.projectNamespace != null) resolvedNamespace = layer.projectNamespace
        mergedCrateNamespaces.putAll(layer.crateNamespaces)
        if (layer.packageName != null) {
            val basePkg = layer.packageName.trim().removeSuffix(".")
            val subSuffix =
                relativeSubdirs
                    .map { rustModuleIdentToKotlin(it) }
                    .filter { it.isNotEmpty() }
                    .joinToString(".")
            resolvedPackage = if (subSuffix.isEmpty()) basePkg else "$basePkg.$subSuffix"
        }
    }

    return FolderPackageProperties(
        packageName = resolvedPackage,
        projectGroup = resolvedGroup,
        projectNamespace = resolvedNamespace,
        crateNamespaces = mergedCrateNamespaces,
    )
}

public data class FileHeaderContext(
    public val portLintSourcePath: String? = null,
    public val packageName: String? = null,
    public val projectGroup: String? = null,
    public val projectNamespace: String? = null,
    public val crateNamespaces: Map<String, String> = emptyMap(),
    public val projectFfiName: String? = null,
    public val portLintDirective: String = "source",
    public val crateMacroSources: List<String> = emptyList(),
    public val crateFlatFileModules: List<String> = emptyList(),
    public val isDocExample: Boolean = false,
    public val rawAbiMode: Boolean = false,
    public val finalClasses: Boolean = false,
    public val selfCrateAliases: Set<String> = emptySet(),
    public val includedSubdirModuleMap: Map<String, String> = emptyMap(),
    public val rootSelfDirModuleAliases: Set<String> = emptySet(),
    public val rootExternalModuleReexports: Map<String, String> = emptyMap(),
    public val crateTypeAliasDefaults: Map<String, String> = emptyMap(),
    public val crateOptionFnTypes: Set<String> = emptySet(),
    public val crateNumericTypeKinds: Map<String, String> = emptyMap(),
    public val cratePointerTypeAliases: Map<String, String> = emptyMap(),
    public val crateStructFieldTypes: Map<String, String> = emptyMap(),
    public val crateFnReturnTypes: Map<String, String> = emptyMap(),
    public val crateSymbolSubpackages: Map<String, List<String>> = emptyMap(),
    public val crateSubpackageRawLeafNames: Map<String, String> = emptyMap(),
    public val crateValueParamPackTemplates: Map<String, String> = emptyMap(),
    public val crateValueReturnUnpackTemplates: Map<String, String> = emptyMap(),
    public val crateFnPtrInvokeTemplates: Map<String, String> = emptyMap(),
    public val crateFnParamNumericKinds: Map<String, List<String>> = emptyMap(),
    public val cfgTargetProfile: CfgTargetProfile? = null,
    public val isBatchCrateOutput: Boolean = false,
    public val currentRelativeSourceDir: String? = null,
    public val isModuleRootSource: Boolean = false,
    public val moduleDeclaredTypeNames: Set<String> = emptySet(),
    public val crateTraitDefaultlessAssocTypes: Map<String, List<String>> = emptyMap(),
    public val crateTraitResultMethods: Map<String, List<String>> = emptyMap(),
    public val crateSelfParameterizedTraits: Set<String> = emptySet(),
    public val crateTraitSupertraits: Map<String, List<String>> = emptyMap(),
    public val crateTraitAstRegistryKey: String? = null,
    public val scalarChar: Boolean = false,
    public val externalCrateSymbolPackages: Map<String, Map<String, List<String>>> = emptyMap(),
    public val externalCrateTwoFieldResultFns: Set<String> = emptySet(),
    public val externalCrateNullableReturnFns: Set<String> = emptySet(),
    public val testClassName: String? = null,
) {
    public constructor(
        portLintSourcePath: String?,
        packageName: String?,
        projectGroup: String?,
        projectNamespace: String?,
        crateNamespaces: Map<String, String>,
    ) : this(
        portLintSourcePath = portLintSourcePath,
        packageName = packageName,
        projectGroup = projectGroup,
        projectNamespace = projectNamespace,
        crateNamespaces = crateNamespaces,
        portLintDirective = "source",
        crateMacroSources = emptyList(),
        crateFlatFileModules = emptyList(),
        isDocExample = false,
    )

    public constructor(
        portLintSourcePath: String?,
        packageName: String?,
        projectGroup: String?,
        projectNamespace: String?,
        crateNamespaces: Map<String, String>,
        portLintDirective: String,
    ) : this(
        portLintSourcePath = portLintSourcePath,
        packageName = packageName,
        projectGroup = projectGroup,
        projectNamespace = projectNamespace,
        crateNamespaces = crateNamespaces,
        portLintDirective = portLintDirective,
        crateMacroSources = emptyList(),
        crateFlatFileModules = emptyList(),
        isDocExample = false,
    )

    public constructor(
        portLintSourcePath: String?,
        packageName: String?,
        projectGroup: String?,
        projectNamespace: String?,
        crateNamespaces: Map<String, String>,
        portLintDirective: String,
        crateMacroSources: List<String>,
    ) : this(
        portLintSourcePath = portLintSourcePath,
        packageName = packageName,
        projectGroup = projectGroup,
        projectNamespace = projectNamespace,
        crateNamespaces = crateNamespaces,
        portLintDirective = portLintDirective,
        crateMacroSources = crateMacroSources,
        crateFlatFileModules = emptyList(),
        isDocExample = false,
    )

    public constructor(
        portLintSourcePath: String?,
        packageName: String?,
        projectGroup: String?,
        projectNamespace: String?,
        crateNamespaces: Map<String, String>,
        portLintDirective: String,
        crateMacroSources: List<String>,
        crateFlatFileModules: List<String>,
    ) : this(
        portLintSourcePath = portLintSourcePath,
        packageName = packageName,
        projectGroup = projectGroup,
        projectNamespace = projectNamespace,
        crateNamespaces = crateNamespaces,
        portLintDirective = portLintDirective,
        crateMacroSources = crateMacroSources,
        crateFlatFileModules = crateFlatFileModules,
        isDocExample = false,
    )
}

private var diskMacroSourcesByDirCache: Map<String, List<Pair<String, String>>> = emptyMap()
private var diskFlatFileModulesByRootCache: Map<String, Set<String>> = emptyMap()

internal fun discoverCrateFlatFileModulesFromDisk(
    inputPath: String?,
    sourceRoot: String? = null,
): Set<String> {
    val rootCandidate =
        when {
            !sourceRoot.isNullOrBlank() -> KmPath(sourceRoot)
            !inputPath.isNullOrBlank() -> {
                val rawFile = KmPath(inputPath)
                if (!isKmRegularFile(rawFile)) return emptySet()
                val resolvedFile = SystemFileSystem.resolve(rawFile)
                var cur: KmPath? = resolvedFile.parent
                var found: KmPath? = cur
                var depth = 0
                while (cur != null && depth < 12) {
                    if (isKmRegularFile(KmPath(cur, "lib.rs")) || cur.name == "src") {
                        found = cur
                        break
                    }
                    if (isKmRegularFile(KmPath(cur, "Cargo.toml"))) {
                        break
                    }
                    cur = cur.parent
                    depth++
                }
                found
            }
            else -> null
        } ?: return emptySet()

    if (!isKmDirectory(rootCandidate)) return emptySet()
    val resolvedRoot = SystemFileSystem.resolve(rootCandidate)
    val cacheKey = resolvedRoot.toString()
    diskFlatFileModulesByRootCache[cacheKey]?.let { return it }

    val flatModules = linkedSetOf<String>()
    for (entry in SystemFileSystem.list(resolvedRoot)) {
        if (!isKmRegularFile(entry)) continue
        val fileName = entry.name
        if (!fileName.endsWith(".rs")) continue
        val stem = fileName.removeSuffix(".rs")
        if (stem == "lib" || stem == "mod" || stem == "main" || stem == "build") continue
        if (!isKmDirectory(KmPath(resolvedRoot, stem))) {
            flatModules += stem
        }
    }
    if (!isKmDirectory(KmPath(resolvedRoot, "prelude"))) {
        flatModules += "prelude"
    }
    val result = flatModules.toSet()
    if (diskFlatFileModulesByRootCache.size >= 128) {
        diskFlatFileModulesByRootCache = mapOf(cacheKey to result)
    } else {
        diskFlatFileModulesByRootCache = diskFlatFileModulesByRootCache + (cacheKey to result)
    }
    return result
}

internal fun discoverCrateMacroSourcesFromDisk(inputPath: String?): List<String> {
    if (inputPath.isNullOrBlank()) return emptyList()
    val rawFile = KmPath(inputPath)
    if (!isKmRegularFile(rawFile)) return emptyList()
    val resolvedFile = SystemFileSystem.resolve(rawFile)
    val resolvedFileStr = resolvedFile.toString()
    val inputDir = resolvedFile.parent ?: return emptyList()
    val cacheKey = inputDir.toString()
    diskMacroSourcesByDirCache[cacheKey]?.let { cached ->
        return cached.filter { it.first != resolvedFileStr }.map { it.second }
    }

    val dirsUpward = mutableListOf<KmPath>()
    var cur: KmPath? = inputDir
    var crateSrcRoot: KmPath = inputDir
    var depth = 0
    while (cur != null && depth < 12) {
        dirsUpward.add(cur)
        if (isKmRegularFile(KmPath(cur, "lib.rs")) || cur.name == "src") {
            crateSrcRoot = cur
            break
        }
        if (isKmRegularFile(KmPath(cur, "Cargo.toml"))) {
            break
        }
        cur = cur.parent
        depth++
    }
    val rootIdx = dirsUpward.indexOf(crateSrcRoot)
    val ancestorDirs =
        if (rootIdx >= 0) {
            dirsUpward.take(rootIdx + 1).reversed()
        } else {
            listOf(inputDir)
        }

    val visitedPaths = linkedSetOf<String>()
    val entries = mutableListOf<Pair<String, String>>()

    fun addMacroSourceFile(candidate: KmPath, followMacroUse: Boolean) {
        if (!isKmRegularFile(candidate)) return
        val resolvedCandidate = SystemFileSystem.resolve(candidate)
        val resolvedCandidateStr = resolvedCandidate.toString()
        if (!visitedPaths.add(resolvedCandidateStr)) return
        val text = readKmFileText(resolvedCandidate)
        if (text.contains("macro_rules")) {
            entries.add(resolvedCandidateStr to text)
        }
        if (followMacroUse && text.contains("macro_use")) {
            val baseDir = resolvedCandidate.parent
            if (baseDir != null) {
                for (modName in extractMacroUseModuleNames(text)) {
                    addMacroSourceFile(KmPath(baseDir, "$modName.rs"), followMacroUse = true)
                    addMacroSourceFile(KmPath(KmPath(baseDir, modName), "mod.rs"), followMacroUse = true)
                }
            }
        }
    }

    addMacroSourceFile(KmPath(crateSrcRoot, "lib.rs"), followMacroUse = true)
    addMacroSourceFile(KmPath(crateSrcRoot, "macros.rs"), followMacroUse = true)
    for (dir in ancestorDirs) {
        addMacroSourceFile(KmPath(dir, "mod.rs"), followMacroUse = true)
    }

    val cachedEntries = entries.toList()
    if (diskMacroSourcesByDirCache.size >= 128) {
        diskMacroSourcesByDirCache = mapOf(cacheKey to cachedEntries)
    } else {
        diskMacroSourcesByDirCache = diskMacroSourcesByDirCache + (cacheKey to cachedEntries)
    }
    return cachedEntries.filter { it.first != resolvedFileStr }.map { it.second }
}

public fun inferFileHeaderContext(
    inputPath: String?,
    outputPath: String?,
    gradleProperties: GradleProjectProperties? = null,
    packageProperties: FolderPackageProperties? = null,
): FileHeaderContext =
    inferFileHeaderContextWithResolvers(
        inputPath = inputPath,
        outputPath = outputPath,
        gradleProperties = gradleProperties,
        packageProperties = packageProperties,
    )

internal fun inferFileHeaderContextWithResolvers(
    inputPath: String?,
    outputPath: String?,
    gradleProperties: GradleProjectProperties? = null,
    packageProperties: FolderPackageProperties? = null,
    sourceRoot: String? = null,
    fallbackSourceRoot: String? = null,
    portLintDirective: String = "source",
    resolveGradleProperties: (String?) -> GradleProjectProperties? = ::findNearestGradleProperties,
    resolvePackageProperties: (String?) -> FolderPackageProperties? = ::findNearestPackageProperties,
    resolveCrateMacroSources: (String?) -> List<String> = ::discoverCrateMacroSourcesFromDisk,
    resolveCrateFlatFileModules: (String?, String?) -> Set<String> = ::discoverCrateFlatFileModulesFromDisk,
): FileHeaderContext {
    val resolvedProps =
        gradleProperties
            ?: resolveGradleProperties(outputPath)
            ?: resolveGradleProperties(inputPath)
    val folderOverride =
        packageProperties
            ?: resolvePackageProperties(outputPath)
            ?: resolvePackageProperties(inputPath)
    val portLint =
        if (portLintDirective == "none") {
            null
        } else {
            inputPath?.let {
                inferPortLintRelativePath(
                    inputPath = it,
                    sourceRoot = sourceRoot,
                    fallbackSourceRoot = fallbackSourceRoot,
                )
            }
        }
    val effectiveGroup = folderOverride?.projectGroup ?: resolvedProps?.projectGroup
    val effectiveCrateNamespaces =
        buildMap {
            resolvedProps?.crateNamespaces?.let { putAll(it) }
            folderOverride?.crateNamespaces?.let { putAll(it) }
        }
    val subcrateName = inputPath?.let { extractTmpSubcrateName(it) }
    val subcrateNamespace =
        subcrateName?.let { subcrate ->
            val subcrateIdent = rustModuleIdentToKotlin(subcrate)
            effectiveCrateNamespaces[subcrateIdent]
                ?: effectiveGroup
                    ?.trim()
                    ?.removeSuffix(".")
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { "$it.$subcrateIdent" }
        }
    val effectiveNamespace =
        folderOverride?.projectNamespace
            ?: subcrateNamespace
            ?: resolvedProps?.projectNamespace
    val pkg =
        folderOverride?.packageName
            ?: outputPath?.let { inferKotlinPackageName(it) }
            ?: inferPackageFromNamespaceAndSource(effectiveNamespace, portLint, portLintDirective)
    val crateMacros = resolveCrateMacroSources(inputPath)
    val crateFlatModules = resolveCrateFlatFileModules(inputPath, sourceRoot ?: fallbackSourceRoot).toList()
    val externalAbi = discoverExternalCrateAbiFromDisk(inputPath ?: outputPath, effectiveCrateNamespaces)
    return FileHeaderContext(
        portLintSourcePath = portLint,
        packageName = pkg,
        projectGroup = effectiveGroup,
        projectNamespace = effectiveNamespace,
        crateNamespaces = effectiveCrateNamespaces,
        projectFfiName = resolvedProps?.projectFfiName,
        portLintDirective = portLintDirective,
        crateMacroSources = crateMacros,
        crateFlatFileModules = crateFlatModules,
        externalCrateSymbolPackages = externalAbi.symbolPackages,
        externalCrateTwoFieldResultFns = externalAbi.twoFieldResultFns,
        externalCrateNullableReturnFns = externalAbi.nullableReturnFns,
    )
}

internal fun extractTmpSubcrateName(inputPath: String): String? {
    var cleaned = inputPath.replace('\\', '/')
    while (cleaned.startsWith("./")) {
        cleaned = cleaned.substring(2)
    }
    val afterTmp =
        when {
            cleaned.startsWith("tmp/") -> cleaned.substring("tmp/".length)
            else -> {
                val tmpIdx = cleaned.indexOf("/tmp/")
                if (tmpIdx < 0) return null
                cleaned.substring(tmpIdx + "/tmp/".length)
            }
        }
    val slashAfterRepo = afterTmp.indexOf('/')
    if (slashAfterRepo <= 0) return null
    val repoName = afterTmp.substring(0, slashAfterRepo)
    val insideRepo = afterTmp.substring(slashAfterRepo + 1)
    val srcIdx = insideRepo.indexOf("/src/")
    if (srcIdx <= 0) return null
    val subcrate = insideRepo.substring(0, srcIdx).substringAfterLast('/')
    if (subcrate.isEmpty() || subcrate == repoName) return null
    return subcrate
}

internal fun inferPackageFromNamespaceAndSource(
    projectNamespace: String?,
    portLintSourcePath: String?,
    portLintDirective: String = "source",
): String? {
    val base = projectNamespace?.trim()?.removeSuffix(".")?.takeIf { it.isNotEmpty() } ?: return null
    if (portLintSourcePath.isNullOrBlank()) return base
    val rawNormalized = portLintSourcePath.replace('\\', '/')
    val normalized =
        if (portLintDirective == "tests" && rawNormalized.startsWith("tests/")) {
            rawNormalized.removePrefix("tests/")
        } else {
            rawNormalized
        }
    val dirPart = normalized.substringBeforeLast('/', missingDelimiterValue = "")
    if (dirPart.isEmpty()) return base
    val subPkg =
        dirPart
            .split('/')
            .filter { it.isNotEmpty() }
            .joinToString(".") { seg -> rustModuleIdentToKotlin(seg.replace("-", "_")) }
    return if (subPkg.isEmpty()) base else "$base.$subPkg"
}

internal fun inferPortLintRelativePath(
    inputPath: String,
    sourceRoot: String? = null,
    fallbackSourceRoot: String? = null,
): String? {
    val normalized = inputPath.replace('\\', '/')
    val fileName = normalized.substringAfterLast('/')
    if (fileName == "lib.rs" || fileName == "mod.rs" || fileName == "main.rs") {
        return null
    }
    if (!sourceRoot.isNullOrBlank()) {
        val rootPrefix = sourceRoot.replace('\\', '/').trimEnd('/') + "/"
        if (normalized.startsWith(rootPrefix)) {
            val rel = normalized.removePrefix(rootPrefix)
            if (rel.isNotEmpty()) {
                return rel
            }
        }
    }
    val srcIdx = normalized.lastIndexOf("/src/")
    if (srcIdx >= 0) {
        return normalized.substring(srcIdx + "/src/".length)
    }
    var cleanedForTmp = normalized
    while (cleanedForTmp.startsWith("./")) {
        cleanedForTmp = cleanedForTmp.substring(2)
    }
    val afterTmp =
        when {
            cleanedForTmp.startsWith("tmp/") -> cleanedForTmp.substring("tmp/".length)
            else -> {
                val tmpIdx = cleanedForTmp.indexOf("/tmp/")
                if (tmpIdx >= 0) cleanedForTmp.substring(tmpIdx + "/tmp/".length) else null
            }
        }
    if (afterTmp != null) {
        val slashAfterRepo = afterTmp.indexOf('/')
        if (slashAfterRepo >= 0) {
            val insideRepo = afterTmp.substring(slashAfterRepo + 1)
            if (insideRepo.startsWith("src/")) {
                return insideRepo.removePrefix("src/")
            }
            return insideRepo
        }
    }
    if (!fallbackSourceRoot.isNullOrBlank()) {
        val rootPrefix = fallbackSourceRoot.replace('\\', '/').trimEnd('/') + "/"
        if (normalized.startsWith(rootPrefix)) {
            val rel = normalized.removePrefix(rootPrefix)
            if (rel.isNotEmpty()) {
                return rel
            }
        }
    }
    return null
}

internal fun inferKotlinPackageName(outputPath: String): String? {
    val normalized = outputPath.replace('\\', '/')
    val marker = "/kotlin/"
    val idx = normalized.indexOf(marker)
    if (idx < 0) return null
    val afterKotlin = normalized.substring(idx + marker.length)
    val dirPart = afterKotlin.substringBeforeLast('/', missingDelimiterValue = "")
    if (dirPart.isEmpty()) return null
    return dirPart.split('/').filter { it.isNotEmpty() }.joinToString(".")
}
