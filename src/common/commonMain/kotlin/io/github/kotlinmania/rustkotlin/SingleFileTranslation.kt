package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.io.files.SystemFileSystem
import io.github.kotlinmania.io.files.Path as KmPath

public data class SingleFileTranslation(
    public val text: String,
    public val summary: String,
    public val diagnostics: List<String> = emptyList(),
    public val companionFiles: List<CompanionInteropFile> = emptyList(),
) {
    public constructor(
        text: String,
        summary: String,
    ) : this(
        text = text,
        summary = summary,
        diagnostics = emptyList(),
        companionFiles = emptyList(),
    )

    public constructor(
        text: String,
        summary: String,
        diagnostics: List<String>,
    ) : this(
        text = text,
        summary = summary,
        diagnostics = diagnostics,
        companionFiles = emptyList(),
    )
}

/**
 * Single-path deterministic J2K translation of one Rust source file to one Kotlin file.
 * No fallbacks: lexer or parser errors fail immediately via [recognizeRust].
 */
public fun renderSingleKotlinFile(
    source: String,
    headerContext: FileHeaderContext = FileHeaderContext(),
): SingleFileTranslation {
    val recognized = recognizeRust(source)
    val emitted =
        TranslationSession.measure("emit:${source.length}") {
            translateRustSourceToKotlinArtifacts(recognized, headerContext)
        }
    return SingleFileTranslation(
        text = emitted.first,
        summary = "${recognized.file.items.size} items translated",
        companionFiles = emitted.second,
    )
}

public fun isRustModuleRootFileName(fileName: String): Boolean {
    val lower = fileName.lowercase()
    return lower == "lib.rs" || lower == "mod.rs"
}

public fun isTranspilableRustFileName(fileName: String): Boolean {
    if (!fileName.endsWith(".rs", ignoreCase = true)) return false
    return !isRustModuleRootFileName(fileName)
}

public fun computeTargetKotlinFileName(rustFileName: String): String {
    val stem = rustFileName.removeSuffix(".rs").removeSuffix(".RS")
    require(stem.isNotEmpty() && !isRustModuleRootFileName(rustFileName)) {
        "crate/module root files ($rustFileName) must never be emitted as Lib.kt or Mod.kt"
    }
    return "${KotlinNamingPolicy.Default.toTypeIdentifier(stem, allowRawAbi = false)}.kt"
}

public fun collectTranspilableRustFilePaths(roots: List<String>): List<String> {
    val collected = linkedSetOf<String>()
    for (rawRoot in roots) {
        collectRustPathsRecursive(KmPath(rawRoot), includeModuleRoots = false, collected = collected)
    }
    return collected.sorted()
}

public fun collectAllRustFilePathsIncludingModuleRoots(roots: List<String>): List<String> {
    val collected = linkedSetOf<String>()
    for (rawRoot in roots) {
        collectRustPathsRecursive(KmPath(rawRoot), includeModuleRoots = true, collected = collected)
    }
    return collected.sorted()
}

private fun collectRustPathsRecursive(
    path: KmPath,
    includeModuleRoots: Boolean,
    collected: MutableSet<String>,
) {
    val metadata = SystemFileSystem.metadataOrNull(path) ?: return
    val normalizedPath = path.toString().replace('\\', '/')
    if (metadata.isDirectory) {
        val entries = SystemFileSystem.list(path).sortedBy { it.toString().replace('\\', '/') }
        for (entry in entries) {
            collectRustPathsRecursive(entry, includeModuleRoots, collected)
        }
    } else if (metadata.isRegularFile) {
        val fileName = normalizedPath.substringAfterLast('/')
        if (includeModuleRoots) {
            if (fileName.endsWith(".rs", ignoreCase = true)) {
                collected += normalizedPath
            }
        } else if (isTranspilableRustFileName(fileName)) {
            collected += normalizedPath
        }
    }
}
