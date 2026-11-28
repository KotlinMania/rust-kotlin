package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.io.buffered
import io.github.kotlinmania.io.files.SystemFileSystem
import io.github.kotlinmania.io.readString
import io.github.kotlinmania.io.files.Path as KmPath

public data class RustKotlinProperties(
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

private fun Map<String, String>.firstNonEmpty(vararg keys: String): String? =
    keys.firstNotNullOfOrNull { get(it)?.takeIf(String::isNotEmpty) }

private fun extractCrateNamespaceOverrides(map: Map<String, String>): Map<String, String> {
    val overrides = linkedMapOf<String, String>()
    for ((key, value) in map) {
        if (value.isEmpty()) continue
        val rawCrate =
            when {
                key.startsWith("crate.") -> key.removePrefix("crate.")
                key.startsWith("namespace.") -> key.removePrefix("namespace.")
                key.endsWith(".namespace") && key != "project.namespace" -> key.removeSuffix(".namespace")
                else -> null
            }
        val trimmedCrate = rawCrate?.trim()
        if (!trimmedCrate.isNullOrEmpty()) {
            overrides[rustModuleIdentToKotlin(trimmedCrate)] = value
        }
    }
    return overrides
}

public fun parseRustKotlinProperties(content: String): RustKotlinProperties {
    val (map, _) = parsePropertiesMap(content)
    val group = map.firstNonEmpty("project.group", "group")
    val name = map.firstNonEmpty("project.name", "name")
    val explicitNamespace = map.firstNonEmpty("project.namespace", "namespace")
    val derivedNamespace =
        if (explicitNamespace == null && group != null && name != null) {
            val crateSegment = rustModuleIdentToKotlin(name.removeSuffix("-kotlin"))
            if (crateSegment.isNotEmpty()) "${group.removeSuffix(".")}.$crateSegment" else group
        } else {
            explicitNamespace
        }
    return RustKotlinProperties(
        projectGroup = group,
        projectNamespace = derivedNamespace,
        projectName = name,
        crateNamespaces = extractCrateNamespaceOverrides(map),
        projectFfiName = map.firstNonEmpty("project.ffi.name"),
    )
}

public fun parsePackageProperties(content: String): FolderPackageProperties {
    val (map, bareLine) = parsePropertiesMap(content)
    return FolderPackageProperties(
        packageName = map.firstNonEmpty("package", "package.name", "namespace", "project.namespace") ?: bareLine,
        projectGroup = map.firstNonEmpty("project.group", "group"),
        projectNamespace = map.firstNonEmpty("project.namespace", "namespace"),
        crateNamespaces = extractCrateNamespaceOverrides(map),
    )
}

internal fun readKmFileText(path: KmPath): String =
    SystemFileSystem.source(path).buffered().use { it.readString() }

internal fun isKmRegularFile(path: KmPath): Boolean =
    SystemFileSystem.metadataOrNull(path)?.isRegularFile == true

internal fun isKmDirectory(path: KmPath): Boolean =
    SystemFileSystem.metadataOrNull(path)?.isDirectory == true

/**
 * Abstract filesystem/VFS navigator unifying upward project/package property resolution,
 * crate `src/` root discovery, flat-file module enumeration, and `macro_rules!` / `#[macro_use]`
 * companion source collection across KMP `KmPath` disk trees and IntelliJ `VirtualFile` trees.
 */
public abstract class CrateFileTreeNavigator<Node : Any> {
    protected abstract fun parentOf(node: Node): Node?

    protected abstract fun childNamed(dir: Node, name: String): Node?

    protected abstract fun childrenOf(dir: Node): List<Node>

    protected abstract fun nameOf(node: Node): String

    protected abstract fun pathKey(node: Node): String

    protected abstract fun isDirectory(node: Node): Boolean

    protected abstract fun isRegularFile(node: Node): Boolean

    protected abstract fun canonicalize(node: Node): Node

    protected abstract fun readText(file: Node): String

    public fun startingDirectory(startNode: Node?): Node? {
        if (startNode == null) return null
        val resolved = canonicalize(startNode)
        return if (isDirectory(resolved)) resolved else parentOf(resolved)
    }

    public fun findNearestRustKotlinPropertiesNode(startNode: Node?): Node? {
        var current = startingDirectory(startNode)
        while (current != null) {
            val candidate = childNamed(current, "rustkotlin.properties")
            if (candidate != null && isRegularFile(candidate)) {
                return candidate
            }
            current = parentOf(current)
        }
        return null
    }

    public fun findNearestRustKotlinProperties(startNode: Node?): RustKotlinProperties? =
        findNearestRustKotlinPropertiesNode(startNode)?.let { parseRustKotlinProperties(readText(it)) }

    public fun resolveLayeredPackageProperties(startNode: Node?): FolderPackageProperties? {
        var current: Node? = startingDirectory(startNode) ?: return null
        val traversedDirs = mutableListOf<String>()
        val foundLayers = mutableListOf<Pair<FolderPackageProperties, List<String>>>()

        while (current != null) {
            val pkgCandidate = childNamed(current, "package.properties")
            if (pkgCandidate != null && isRegularFile(pkgCandidate)) {
                val parsed = parsePackageProperties(readText(pkgCandidate))
                foundLayers += parsed to traversedDirs.reversed()
            }
            val rustKotlinCandidate = childNamed(current, "rustkotlin.properties")
            if (rustKotlinCandidate != null && isRegularFile(rustKotlinCandidate)) {
                break
            }
            val dirName = nameOf(current)
            if (dirName.isNotEmpty()) {
                traversedDirs += dirName
            }
            current = parentOf(current)
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

    public fun findCrateSrcRootAndAncestors(startDir: Node): Pair<Node, List<Node>> {
        val dirsUpward = mutableListOf<Node>()
        var cur: Node? = startDir
        var crateSrcRoot: Node = startDir
        var depth = 0
        while (cur != null && depth < 12) {
            dirsUpward.add(cur)
            val libRs = childNamed(cur, "lib.rs")
            if ((libRs != null && isRegularFile(libRs)) || nameOf(cur) == "src") {
                crateSrcRoot = cur
                break
            }
            val cargoToml = childNamed(cur, "Cargo.toml")
            if (cargoToml != null && isRegularFile(cargoToml)) {
                break
            }
            cur = parentOf(cur)
            depth++
        }
        val rootIdx = dirsUpward.indexOf(crateSrcRoot)
        val ancestorDirs =
            if (rootIdx >= 0) {
                dirsUpward.take(rootIdx + 1).reversed()
            } else {
                listOf(startDir)
            }
        return crateSrcRoot to ancestorDirs
    }

    public fun discoverFlatFileModulesInRoot(crateSrcRoot: Node): Set<String> {
        val flatModules = linkedSetOf<String>()
        for (entry in childrenOf(crateSrcRoot)) {
            if (!isRegularFile(entry)) continue
            val fileName = nameOf(entry)
            if (!fileName.endsWith(".rs")) continue
            val stem = fileName.removeSuffix(".rs")
            if (stem == "lib" || stem == "mod" || stem == "main" || stem == "build") continue
            val siblingDir = childNamed(crateSrcRoot, stem)
            if (siblingDir == null || !isDirectory(siblingDir)) {
                flatModules += stem
            }
        }
        val preludeDir = childNamed(crateSrcRoot, "prelude")
        if (preludeDir == null || !isDirectory(preludeDir)) {
            flatModules += "prelude"
        }
        return flatModules
    }

    public fun collectMacroSourceEntries(inputDir: Node): List<Pair<String, String>> {
        val (crateSrcRoot, ancestorDirs) = findCrateSrcRootAndAncestors(inputDir)
        val visitedPaths = linkedSetOf<String>()
        val entries = mutableListOf<Pair<String, String>>()

        fun addMacroSourceFile(candidate: Node?, followMacroUse: Boolean) {
            if (candidate == null || !isRegularFile(candidate)) return
            val resolvedCandidate = canonicalize(candidate)
            val resolvedKey = pathKey(resolvedCandidate)
            if (!visitedPaths.add(resolvedKey)) return
            val text = readText(resolvedCandidate)
            if (text.contains("macro_rules") || text.contains("macro ")) {
                entries.add(resolvedKey to text)
            }
            if (followMacroUse && text.contains("macro_use")) {
                val baseDir = parentOf(resolvedCandidate)
                if (baseDir != null) {
                    for (modName in extractMacroUseModuleNames(text)) {
                        addMacroSourceFile(childNamed(baseDir, "$modName.rs"), followMacroUse = true)
                        addMacroSourceFile(
                            childNamed(baseDir, modName)?.let { childNamed(it, "mod.rs") },
                            followMacroUse = true,
                        )
                    }
                }
            }
        }

        addMacroSourceFile(childNamed(crateSrcRoot, "lib.rs"), followMacroUse = true)
        addMacroSourceFile(childNamed(crateSrcRoot, "macros.rs"), followMacroUse = true)
        for (dir in ancestorDirs) {
            addMacroSourceFile(childNamed(dir, "mod.rs"), followMacroUse = true)
            addMacroSourceFile(childNamed(dir, "macros.rs"), followMacroUse = true)
        }
        return entries
    }

    public fun discoverMacroSourcesForFile(rustFile: Node): List<String> {
        if (!isRegularFile(rustFile)) return emptyList()
        val resolvedFile = canonicalize(rustFile)
        val inputDir = parentOf(resolvedFile) ?: return emptyList()
        val selfKey = pathKey(resolvedFile)
        return collectMacroSourceEntries(inputDir).mapNotNull { (k, v) -> if (k != selfKey) v else null }
    }
}

internal object KmPathFileTreeNavigator : CrateFileTreeNavigator<KmPath>() {
    override fun parentOf(node: KmPath): KmPath? = node.parent

    override fun childNamed(dir: KmPath, name: String): KmPath = KmPath(dir, name)

    override fun childrenOf(dir: KmPath): List<KmPath> = SystemFileSystem.list(dir).toList()

    override fun nameOf(node: KmPath): String = node.name

    override fun pathKey(node: KmPath): String = node.toString()

    override fun isDirectory(node: KmPath): Boolean = isKmDirectory(node)

    override fun isRegularFile(node: KmPath): Boolean = isKmRegularFile(node)

    override fun canonicalize(node: KmPath): KmPath =
        if (SystemFileSystem.exists(node)) SystemFileSystem.resolve(node) else node

    override fun readText(file: KmPath): String = readKmFileText(file)

    fun resolveCrateSrcRootFromDisk(
        inputPath: String?,
        sourceRoot: String? = null,
    ): KmPath? {
        val candidate =
            when {
                !sourceRoot.isNullOrBlank() -> KmPath(sourceRoot)
                !inputPath.isNullOrBlank() -> {
                    val rawFile = KmPath(inputPath)
                    if (!isKmRegularFile(rawFile)) return null
                    val parentDir = canonicalize(rawFile).parent ?: return null
                    findCrateSrcRootAndAncestors(parentDir).first
                }
                else -> null
            } ?: return null
        return if (isKmDirectory(candidate)) canonicalize(candidate) else null
    }
}

public fun findNearestRustKotlinProperties(startPath: String?): RustKotlinProperties? =
    KmPathFileTreeNavigator.findNearestRustKotlinProperties(
        startPath?.takeIf { it.isNotBlank() }?.let(::KmPath),
    )

public fun findNearestPackageProperties(startPath: String?): FolderPackageProperties? =
    KmPathFileTreeNavigator.resolveLayeredPackageProperties(
        startPath?.takeIf { it.isNotBlank() }?.let(::KmPath),
    )

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
    public val crateFnConstParamNames: Map<String, Pair<Int, List<String>>> = emptyMap(),
    public val crateEnumVariantTypeArity: Map<String, Int> = emptyMap(),
    public val crateUnitEnumVariants: Set<String> = emptySet(),
    public val scalarChar: Boolean = false,
    public val externalCrateSymbolPackages: Map<String, Map<String, List<String>>> = emptyMap(),
    public val externalCrateTwoFieldResultFns: Set<String> = emptySet(),
    public val externalCrateNullableReturnFns: Set<String> = emptySet(),
    public val externalCrateFnConstParams: Map<String, Pair<Int, List<String>>> = emptyMap(),
    public val externalCrateTupleStructArity: Map<String, Int> = emptyMap(),
    public val externalEnumVariantTypeArity: Map<String, Int> = emptyMap(),
    public val externalUnitEnumVariants: Set<String> = emptySet(),
    public val testClassName: String? = null,
    public val sourceFilePath: String? = null,
) {
    public constructor(
        portLintSourcePath: String?,
        packageName: String?,
        projectGroup: String?,
        projectNamespace: String?,
        crateNamespaces: Map<String, String>,
    ) : this(portLintSourcePath, packageName, projectGroup, projectNamespace, crateNamespaces, "source")

    public constructor(
        portLintSourcePath: String?,
        packageName: String?,
        projectGroup: String?,
        projectNamespace: String?,
        crateNamespaces: Map<String, String>,
        portLintDirective: String,
    ) : this(portLintSourcePath, packageName, projectGroup, projectNamespace, crateNamespaces, portLintDirective, emptyList())

    public constructor(
        portLintSourcePath: String?,
        packageName: String?,
        projectGroup: String?,
        projectNamespace: String?,
        crateNamespaces: Map<String, String>,
        portLintDirective: String,
        crateMacroSources: List<String>,
    ) : this(
        portLintSourcePath,
        packageName,
        projectGroup,
        projectNamespace,
        crateNamespaces,
        portLintDirective,
        crateMacroSources,
        emptyList(),
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

internal class BoundedDiskCache<V : Any>(
    private val maxEntries: Int = 128,
) {
    private var entries: Map<String, V> = emptyMap()

    operator fun get(key: String): V? = entries[key]

    fun put(key: String, value: V) {
        entries = if (entries.size >= maxEntries) mapOf(key to value) else entries + (key to value)
    }

    fun getOrPut(key: String, compute: () -> V): V {
        entries[key]?.let { return it }
        return compute().also { put(key, it) }
    }
}

private val diskMacroSourcesByDirCache = BoundedDiskCache<List<Pair<String, String>>>()
private val diskFlatFileModulesByRootCache = BoundedDiskCache<Set<String>>()

internal fun discoverCrateFlatFileModulesFromDisk(
    inputPath: String?,
    sourceRoot: String? = null,
): Set<String> {
    val resolvedRoot = KmPathFileTreeNavigator.resolveCrateSrcRootFromDisk(inputPath, sourceRoot) ?: return emptySet()
    return diskFlatFileModulesByRootCache.getOrPut(resolvedRoot.toString()) {
        KmPathFileTreeNavigator.discoverFlatFileModulesInRoot(resolvedRoot)
    }
}

internal fun discoverCrateMacroSourcesFromDisk(inputPath: String?): List<String> {
    if (inputPath.isNullOrBlank()) return emptyList()
    val rawFile = KmPath(inputPath)
    if (!isKmRegularFile(rawFile)) return emptyList()
    val resolvedFile = SystemFileSystem.resolve(rawFile)
    val resolvedFileStr = resolvedFile.toString()
    val inputDir = resolvedFile.parent ?: return emptyList()
    val entries =
        diskMacroSourcesByDirCache.getOrPut(inputDir.toString()) {
            KmPathFileTreeNavigator.collectMacroSourceEntries(inputDir)
        }
    return entries.mapNotNull { (k, v) -> if (k != resolvedFileStr) v else null }
}

public fun inferFileHeaderContext(
    inputPath: String?,
    outputPath: String?,
    rustKotlinProperties: RustKotlinProperties? = null,
    packageProperties: FolderPackageProperties? = null,
): FileHeaderContext =
    inferFileHeaderContextWithResolvers(
        inputPath = inputPath,
        outputPath = outputPath,
        rustKotlinProperties = rustKotlinProperties,
        packageProperties = packageProperties,
    )

internal fun inferFileHeaderContextWithResolvers(
    inputPath: String?,
    outputPath: String?,
    rustKotlinProperties: RustKotlinProperties? = null,
    packageProperties: FolderPackageProperties? = null,
    sourceRoot: String? = null,
    fallbackSourceRoot: String? = null,
    portLintDirective: String = "source",
    resolveRustKotlinProperties: (String?) -> RustKotlinProperties? = ::findNearestRustKotlinProperties,
    resolvePackageProperties: (String?) -> FolderPackageProperties? = ::findNearestPackageProperties,
    resolveCrateMacroSources: (String?) -> List<String> = ::discoverCrateMacroSourcesFromDisk,
    resolveCrateFlatFileModules: (String?, String?) -> Set<String> = ::discoverCrateFlatFileModulesFromDisk,
): FileHeaderContext {
    val resolvedProps =
        rustKotlinProperties
            ?: resolveRustKotlinProperties(outputPath)
            ?: resolveRustKotlinProperties(inputPath)
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
        externalCrateFnConstParams = externalAbi.fnConstParams,
        sourceFilePath = inputPath,
    )
}

private fun extractAfterTmpRepoAndInside(rawPath: String): Pair<String, String>? {
    var cleaned = rawPath.replace('\\', '/')
    while (cleaned.startsWith("./")) {
        cleaned = cleaned.substring(2)
    }
    val afterTmp =
        if (cleaned.startsWith("tmp/")) {
            cleaned.substring("tmp/".length)
        } else {
            val tmpIdx = cleaned.indexOf("/tmp/")
            if (tmpIdx >= 0) cleaned.substring(tmpIdx + "/tmp/".length) else return null
        }
    val slashAfterRepo = afterTmp.indexOf('/')
    if (slashAfterRepo < 0) return null
    return afterTmp.substring(0, slashAfterRepo) to afterTmp.substring(slashAfterRepo + 1)
}

private fun stripSourceRootPrefix(normalizedPath: String, root: String?): String? {
    if (root.isNullOrBlank()) return null
    val rootPrefix = root.replace('\\', '/').trimEnd('/') + "/"
    if (!normalizedPath.startsWith(rootPrefix)) return null
    return normalizedPath.removePrefix(rootPrefix).takeIf { it.isNotEmpty() }
}

internal fun extractTmpSubcrateName(inputPath: String): String? {
    val (repoName, insideRepo) = extractAfterTmpRepoAndInside(inputPath) ?: return null
    if (repoName.isEmpty()) return null
    val srcIdx = insideRepo.indexOf("/src/")
    if (srcIdx <= 0) return null
    val subcrate = insideRepo.substring(0, srcIdx).substringAfterLast('/')
    return subcrate.takeIf { it.isNotEmpty() && it != repoName }
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
    if (fileName == "lib.rs" || fileName == "mod.rs" || fileName == "main.rs") return null
    stripSourceRootPrefix(normalized, sourceRoot)?.let { return it }
    val srcIdx = normalized.lastIndexOf("/src/")
    if (srcIdx >= 0) return normalized.substring(srcIdx + "/src/".length)
    extractAfterTmpRepoAndInside(normalized)?.let { (_, insideRepo) ->
        return insideRepo.removePrefix("src/")
    }
    return stripSourceRootPrefix(normalized, fallbackSourceRoot)
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

public const val DEFAULT_RUST_LANG_KOTLIN_VERSION: String = "0.2.0"

public fun emittedKotlinImportsRustLang(kotlinSource: String): Boolean =
    kotlinSource.lineSequence().any { it.trimStart().startsWith("import io.github.kotlinmania.rustlang.") }

public fun extractProjectRootPrefixFromKotlinPath(kotlinPath: String): String? {
    val normalized = kotlinPath.replace('\\', '/')
    for (marker in listOf("src/common/commonMain/kotlin/", "src/common/commonTest/kotlin/")) {
        when {
            normalized.startsWith(marker) -> return ""
            normalized.contains("/$marker") -> return normalized.substring(0, normalized.indexOf("/$marker") + 1)
        }
    }
    return null
}

public fun isRustLangSelfProject(
    projectNamespace: String?,
    gradlePropertiesText: String?,
): Boolean {
    val ns = projectNamespace?.trim()?.removeSuffix(".")
    if (ns == "io.github.kotlinmania.rustlang" || ns?.startsWith("io.github.kotlinmania.rustlang.") == true) {
        return true
    }
    if (gradlePropertiesText != null) {
        for (rawLine in gradlePropertiesText.lineSequence()) {
            val line = rawLine.trim()
            if (line.startsWith("#") || '=' !in line) continue
            val key = line.substringBefore('=').trim()
            val value = line.substringAfter('=').trim()
            if (key == "project.name" && value == "rust-lang-kotlin") return true
            if (key == "project.namespace" &&
                (value == "io.github.kotlinmania.rustlang" || value.startsWith("io.github.kotlinmania.rustlang."))
            ) {
                return true
            }
        }
    }
    return false
}

public fun ensureRustLangInGradleProperties(propsText: String): Pair<String, String?> {
    val lines = propsText.split('\n').toMutableList()
    var configuredBundle: String? = null
    var emptyBundleLineIdx = -1
    var alreadyInSourceSetDeps = false
    for ((idx, rawLine) in lines.withIndex()) {
        val trimmed = rawLine.trim()
        if (trimmed.startsWith("#") || '=' !in trimmed) continue
        val key = trimmed.substringBefore('=').trim()
        val value = trimmed.substringAfter('=').trim()
        if (key == "project.dependencies.commonMainBundle") {
            if (value.isNotEmpty()) {
                configuredBundle = value
            } else {
                emptyBundleLineIdx = idx
            }
        } else if (key == "project.dependencies.sourceSet.commonMain") {
            val items = value.split(',').map { it.trim() }
            if ("rust-lang-kotlin" in items) {
                alreadyInSourceSetDeps = true
            }
        }
    }
    if (configuredBundle != null || alreadyInSourceSetDeps) {
        return propsText to configuredBundle
    }
    if (emptyBundleLineIdx >= 0) {
        val cr = if (lines[emptyBundleLineIdx].endsWith('\r')) "\r" else ""
        lines[emptyBundleLineIdx] = "project.dependencies.commonMainBundle=rust-lang-commonMain$cr"
        return lines.joinToString("\n") to "rust-lang-commonMain"
    }
    return propsText to null
}

/**
 * Lossless lexical token hierarchy for TOML version-catalog manifests (`libs.versions.toml`).
 *
 * Replaces ad-hoc `Regex` line matching and raw `']' in line` slicing so inline `#` comments,
 * quoted brackets, dotted keys (`version.ref`), and multiline bundle arrays are tokenized cleanly.
 */
internal sealed class TomlLexToken {
    abstract val rawText: String
    abstract val start: Int
    abstract val end: Int

    data class LBracket(
        override val start: Int,
        override val end: Int,
        override val rawText: String = "[",
    ) : TomlLexToken()

    data class RBracket(
        override val start: Int,
        override val end: Int,
        override val rawText: String = "]",
    ) : TomlLexToken()

    data class LBrace(
        override val start: Int,
        override val end: Int,
        override val rawText: String = "{",
    ) : TomlLexToken()

    data class RBrace(
        override val start: Int,
        override val end: Int,
        override val rawText: String = "}",
    ) : TomlLexToken()

    data class Equals(
        override val start: Int,
        override val end: Int,
        override val rawText: String = "=",
    ) : TomlLexToken()

    data class Comma(
        override val start: Int,
        override val end: Int,
        override val rawText: String = ",",
    ) : TomlLexToken()

    data class Dot(
        override val start: Int,
        override val end: Int,
        override val rawText: String = ".",
    ) : TomlLexToken()

    data class BareKey(
        override val rawText: String,
        override val start: Int,
        override val end: Int,
    ) : TomlLexToken()

    data class StringLit(
        override val rawText: String,
        val value: String,
        override val start: Int,
        override val end: Int,
    ) : TomlLexToken()

    data class Comment(
        override val rawText: String,
        override val start: Int,
        override val end: Int,
    ) : TomlLexToken()

    data class Whitespace(
        override val rawText: String,
        override val start: Int,
        override val end: Int,
    ) : TomlLexToken()

    data class Newline(
        override val rawText: String,
        override val start: Int,
        override val end: Int,
    ) : TomlLexToken()

    data class Other(
        override val rawText: String,
        override val start: Int,
        override val end: Int,
    ) : TomlLexToken()
}

internal open class TomlTokenizer {
    open fun tokenize(source: String): List<TomlLexToken> {
        if (source.isEmpty()) return emptyList()
        val out = ArrayList<TomlLexToken>(maxOf(8, source.length / 4))
        var i = 0
        val n = source.length
        while (i < n) {
            val ch = source[i]
            when {
                ch == '\n' -> {
                    out += TomlLexToken.Newline("\n", i, i + 1)
                    i++
                }
                ch == ' ' || ch == '\t' || ch == '\r' -> {
                    var j = i + 1
                    while (j < n && (source[j] == ' ' || source[j] == '\t' || source[j] == '\r')) j++
                    out += TomlLexToken.Whitespace(source.substring(i, j), i, j)
                    i = j
                }
                ch == '#' -> {
                    var j = i + 1
                    while (j < n && source[j] != '\n') j++
                    out += TomlLexToken.Comment(source.substring(i, j), i, j)
                    i = j
                }
                ch == '[' -> {
                    out += TomlLexToken.LBracket(i, i + 1)
                    i++
                }
                ch == ']' -> {
                    out += TomlLexToken.RBracket(i, i + 1)
                    i++
                }
                ch == '{' -> {
                    out += TomlLexToken.LBrace(i, i + 1)
                    i++
                }
                ch == '}' -> {
                    out += TomlLexToken.RBrace(i, i + 1)
                    i++
                }
                ch == '=' -> {
                    out += TomlLexToken.Equals(i, i + 1)
                    i++
                }
                ch == ',' -> {
                    out += TomlLexToken.Comma(i, i + 1)
                    i++
                }
                ch == '.' -> {
                    out += TomlLexToken.Dot(i, i + 1)
                    i++
                }
                ch == '"' || ch == '\'' -> {
                    val quote = ch
                    val sb = StringBuilder()
                    var j = i + 1
                    while (j < n && source[j] != quote && source[j] != '\n') {
                        if (quote == '"' && source[j] == '\\' && j + 1 < n) {
                            val esc = source[j + 1]
                            sb.append(
                                when (esc) {
                                    'n' -> '\n'
                                    't' -> '\t'
                                    'r' -> '\r'
                                    '\\' -> '\\'
                                    '"' -> '"'
                                    else -> esc
                                },
                            )
                            j += 2
                        } else {
                            sb.append(source[j])
                            j++
                        }
                    }
                    if (j < n && source[j] == quote) j++
                    out += TomlLexToken.StringLit(source.substring(i, j), sb.toString(), i, j)
                    i = j
                }
                isTomlBareKeyChar(ch) -> {
                    var j = i + 1
                    while (j < n && isTomlBareKeyChar(source[j])) j++
                    out += TomlLexToken.BareKey(source.substring(i, j), i, j)
                    i = j
                }
                else -> {
                    out += TomlLexToken.Other(ch.toString(), i, i + 1)
                    i++
                }
            }
        }
        return out
    }

    fun analyzeLine(line: String): TomlTokenLineView = TomlTokenLineView(line, tokenize(line))

    companion object Default : TomlTokenizer() {
        private fun isTomlBareKeyChar(ch: Char): Boolean =
            ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '_' || ch == '-'
    }
}

internal class TomlTokenLineView(
    val sourceLine: String,
    val tokens: List<TomlLexToken>,
) {
    private val significant: List<TomlLexToken> =
        tokens.filterNot { it is TomlLexToken.Whitespace || it is TomlLexToken.Newline || it is TomlLexToken.Comment }

    fun sectionHeaderName(): String? {
        if (significant.size < 3) return null
        if (significant.first() !is TomlLexToken.LBracket || significant.last() !is TomlLexToken.RBracket) return null
        val middle = significant.subList(1, significant.size - 1)
        val sb = StringBuilder()
        for ((idx, tok) in middle.withIndex()) {
            if (idx % 2 == 0) {
                val key = tok as? TomlLexToken.BareKey ?: return null
                sb.append(key.rawText)
            } else {
                if (tok !is TomlLexToken.Dot) return null
                sb.append('.')
            }
        }
        return sb.toString().takeIf { it.isNotEmpty() && !it.endsWith('.') }
    }

    fun assignmentKey(): String? {
        val eqIdx = significant.indexOfFirst { it is TomlLexToken.Equals }
        if (eqIdx <= 0) return null
        val keyTokens = significant.subList(0, eqIdx)
        val sb = StringBuilder()
        for ((idx, tok) in keyTokens.withIndex()) {
            when {
                idx % 2 == 0 && tok is TomlLexToken.BareKey -> sb.append(tok.rawText)
                idx % 2 == 0 && tok is TomlLexToken.StringLit -> sb.append(tok.value)
                idx % 2 == 1 && tok is TomlLexToken.Dot -> sb.append('.')
                else -> return null
            }
        }
        return sb.toString().takeIf { it.isNotEmpty() }
    }

    fun hasOpenBracketAfterEquals(): Boolean {
        val eqIdx = significant.indexOfFirst { it is TomlLexToken.Equals }
        return eqIdx >= 0 && significant.drop(eqIdx + 1).any { it is TomlLexToken.LBracket }
    }

    fun lastCloseBracketToken(): TomlLexToken.RBracket? =
        significant.filterIsInstance<TomlLexToken.RBracket>().lastOrNull()

    fun containsStringValue(target: String): Boolean =
        significant.any { it is TomlLexToken.StringLit && it.value == target }

    fun inlineStringProperty(dottedProperty: String): String? {
        val parts = dottedProperty.split('.')
        if (parts.isEmpty()) return null
        val expectedCount = parts.size * 2 - 1
        for (i in 0..(significant.size - expectedCount - 2)) {
            var matched = true
            for (pIdx in parts.indices) {
                val keyTok = significant[i + pIdx * 2]
                if (keyTok !is TomlLexToken.BareKey || keyTok.rawText != parts[pIdx]) {
                    matched = false
                    break
                }
                if (pIdx + 1 < parts.size && significant[i + pIdx * 2 + 1] !is TomlLexToken.Dot) {
                    matched = false
                    break
                }
            }
            if (!matched) continue
            val eqTok = significant[i + expectedCount]
            val valTok = significant[i + expectedCount + 1]
            if (eqTok is TomlLexToken.Equals && valTok is TomlLexToken.StringLit) {
                return valTok.value
            }
        }
        return null
    }
}

internal class TomlManifestEditor(
    private val rawText: String,
    private val tokenizer: TomlTokenizer = TomlTokenizer,
) {
    private val hadTrailingNewline = rawText.endsWith('\n')
    val lines: MutableList<String> = rawText.trimEnd('\n').split('\n').toMutableList()

    fun analyze(lineIndex: Int): TomlTokenLineView = tokenizer.analyzeLine(lines[lineIndex])

    fun findSectionBounds(sectionName: String): IntRange? {
        var start = -1
        for (i in lines.indices) {
            val header = analyze(i).sectionHeaderName() ?: continue
            if (start < 0) {
                if (header == sectionName) start = i
            } else {
                return start until i
            }
        }
        return if (start >= 0) start until lines.size else null
    }

    fun sectionInsertIndex(bounds: IntRange): Int {
        var idx = bounds.last + 1
        while (idx > bounds.first + 1 && lines[idx - 1].isBlank()) {
            idx--
        }
        return idx
    }

    fun ensureSection(sectionName: String): IntRange {
        findSectionBounds(sectionName)?.let { return it }
        if (lines.isNotEmpty() && lines.last().isNotBlank()) lines.add("")
        lines.add("[$sectionName]")
        return findSectionBounds(sectionName)!!
    }

    fun appendLineToSection(sectionName: String, entryLine: String) {
        val bounds = findSectionBounds(sectionName)
        if (bounds != null) {
            lines.add(sectionInsertIndex(bounds), entryLine)
        } else {
            ensureSection(sectionName)
            lines.add(entryLine)
        }
    }

    fun addLibToExistingBundle(bundleName: String, libKey: String): Boolean {
        val curBounds = findSectionBounds("bundles") ?: return false
        val startLine =
            ((curBounds.first + 1)..curBounds.last).firstOrNull { i ->
                val view = analyze(i)
                view.assignmentKey() == bundleName && view.hasOpenBracketAfterEquals()
            } ?: return false
        var endLine = startLine
        var closeBracket: TomlLexToken.RBracket? = null
        while (endLine <= curBounds.last) {
            closeBracket = analyze(endLine).lastCloseBracketToken()
            if (closeBracket != null) break
            endLine++
        }
        if (endLine > curBounds.last || closeBracket == null) return false
        if ((startLine..endLine).any { analyze(it).containsStringValue(libKey) }) return true
        if (startLine == endLine) {
            val lineText = lines[startLine]
            val beforeClose = lineText.substring(0, closeBracket.start).trimEnd()
            val afterClose = lineText.substring(closeBracket.end)
            val sep = if (beforeClose.endsWith('[') || beforeClose.endsWith(',')) " " else ", "
            lines[startLine] = """$beforeClose$sep"$libKey"]$afterClose"""
        } else {
            lines.add(endLine, """  "$libKey",""")
        }
        return true
    }

    fun render(): String {
        val joined = lines.joinToString("\n")
        return if (hadTrailingNewline || rawText.isEmpty()) "$joined\n" else joined
    }
}

public fun ensureRustLangInLibsVersionsToml(
    tomlText: String,
    commonMainBundleName: String? = null,
    version: String = DEFAULT_RUST_LANG_KOTLIN_VERSION,
): String {
    val editor = TomlManifestEditor(tomlText)
    var existingLibKey: String? = null
    var existingVerRef: String? = null
    editor.findSectionBounds("libraries")?.let { libBounds ->
        for (i in (libBounds.first + 1)..libBounds.last) {
            val view = editor.analyze(i)
            val key = view.assignmentKey() ?: continue
            val moduleVal = view.inlineStringProperty("module")
            val containsRustLang =
                moduleVal == "io.github.kotlinmania:rust-lang-kotlin" ||
                    view.containsStringValue("io.github.kotlinmania:rust-lang-kotlin") ||
                    view.tokens.any {
                        it is TomlLexToken.StringLit &&
                            it.value.startsWith("io.github.kotlinmania:rust-lang-kotlin:")
                    }
            if (containsRustLang) {
                existingLibKey = key
                existingVerRef = view.inlineStringProperty("version.ref")
                break
            }
        }
    }

    var versionKey = existingVerRef
    val verBounds = if (versionKey == null) editor.findSectionBounds("versions") else null
    if (verBounds != null) {
        for (i in (verBounds.first + 1)..verBounds.last) {
            val k = editor.analyze(i).assignmentKey() ?: continue
            if (k == "rustLang" || k == "rustLangKotlin") {
                versionKey = k
                break
            }
        }
    }
    if (versionKey == null) {
        versionKey = "rustLang"
        editor.appendLineToSection("versions", """$versionKey = "$version"""")
    }

    val libKey = existingLibKey ?: "rust-lang-kotlin"
    if (existingLibKey == null) {
        editor.appendLineToSection(
            "libraries",
            """$libKey = { module = "io.github.kotlinmania:rust-lang-kotlin", version.ref = "$versionKey" }""",
        )
    }

    val bundleBounds = editor.ensureSection("bundles")
    val preferredBundle =
        commonMainBundleName?.trim()?.takeIf { it.isNotEmpty() }
            ?: ((bundleBounds.first + 1)..bundleBounds.last).firstNotNullOfOrNull { i ->
                val view = editor.analyze(i)
                if (!view.hasOpenBracketAfterEquals()) {
                    null
                } else {
                    view.assignmentKey()?.takeIf { it.endsWith("-commonMain") && it != "rust-lang-commonMain" }
                }
            }

    if (preferredBundle != null && preferredBundle != "rust-lang-commonMain") {
        editor.addLibToExistingBundle(preferredBundle, libKey)
    }
    if (!editor.addLibToExistingBundle("rust-lang-commonMain", libKey)) {
        val curBounds = editor.findSectionBounds("bundles")!!
        editor.lines.addAll(
            editor.sectionInsertIndex(curBounds),
            listOf("rust-lang-commonMain = [", """  "$libKey",""", "]"),
        )
    }
    return editor.render()
}

public fun isManualProtectedContent(content: String): Boolean =
    content
        .lineSequence()
        .take(10)
        .any { line ->
            val trimmed = line.trimStart()
            trimmed.startsWith("// rust-kotlin: manual") || trimmed.startsWith("# rust-kotlin: manual")
        }

public fun synchronizeRustLangProjectCatalog(
    projectNamespace: String?,
    existingToml: String,
    existingProps: String?,
    canUpdateProps: Boolean = true,
    writeProps: (String) -> Unit,
    writeToml: (String) -> Unit,
): Boolean {
    if (isRustLangSelfProject(projectNamespace, existingProps)) return false
    var targetBundle: String? = null
    if (canUpdateProps && existingProps != null && !isManualProtectedContent(existingProps)) {
        val (updatedProps, bundleName) = ensureRustLangInGradleProperties(existingProps)
        targetBundle = bundleName
        if (updatedProps != existingProps) {
            writeProps(updatedProps)
        }
    }
    if (isManualProtectedContent(existingToml)) return false
    val updatedToml = ensureRustLangInLibsVersionsToml(existingToml, commonMainBundleName = targetBundle)
    if (updatedToml == existingToml) return false
    writeToml(updatedToml)
    return true
}
