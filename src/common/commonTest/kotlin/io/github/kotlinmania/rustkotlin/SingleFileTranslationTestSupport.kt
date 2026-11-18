package io.github.kotlinmania.rustkotlin

internal class TestFilesHarness(
    val source: String = "",
    val extraFiles: Map<String, String> = emptyMap(),
    val existingOutputs: Set<String> = emptySet(),
    val existingOutputContents: Map<String, String> = emptyMap(),
    val directories: Map<String, List<String>> = emptyMap(),
) {
    val reads = mutableListOf<String>()
    val writes = linkedMapOf<String, String>()
    val forcedWrites = linkedMapOf<String, String>()
    val messages = mutableListOf<String>()

    fun run(vararg args: String) =
        runSingleFileCommand(
            arrayOf(*args),
            read = {
                reads += it
                extraFiles[it] ?: source
            },
            readExistingOrNull = { path -> writes[path] ?: existingOutputContents[path] },
            write = { path, text ->
                check(path !in existingOutputs && path !in existingOutputContents && writes.put(path, text) == null)
            },
            writeForce = { path, text ->
                forcedWrites[path] = text
                writes[path] = text
            },
            exists = { path -> path in existingOutputs || path in existingOutputContents || path in writes },
            isDirectory = { path -> path in directories },
            collectRustFiles = { roots ->
                roots.flatMap { r -> directories[r].orEmpty() }
            },
            report = { messages += it },
            resolveRustKotlinProperties = { null },
            resolvePackageProperties = { null },
            resolveCrateMacroSources = { emptyList() },
            resolveCrateFlatFileModules = { _, _ -> emptySet() },
            resolveCrateRawAbiMetadata = { _, _, raw, split ->
                if (extraFiles.isEmpty()) {
                    CrateRawAbiMetadata()
                } else {
                    buildCrateRawAbiMetadataFromSources(
                        sources = extraFiles.entries.map { it.key to it.value },
                        rawAbiMode = raw,
                        splitModuleRoots = split,
                        finalStructs = TranslationSession.finalStructs,
                    )
                }
            },
        )
}

internal fun runSplitCrateCommand(
    args: Array<String>,
    sources: Map<String, String>,
    dirRoot: String? = null,
    metadataStripPrefix: String? = null,
    macroSources: List<String> = emptyList(),
    flatFileModules: Set<String> = emptySet(),
    finalStructsOverride: Boolean? = null,
    useCrateMetadata: Boolean = true,
): LinkedHashMap<String, String> {
    val written = linkedMapOf<String, String>()
    runSingleFileCommand(
        args = args,
        read = { path -> sources[path] ?: error("missing $path") },
        readExistingOrNull = { path -> written[path] },
        write = { path, text -> written[path] = text },
        writeForce = { path, text -> written[path] = text },
        exists = { path -> path in written },
        isDirectory = { dirRoot != null && it == dirRoot },
        collectRustFiles = { sources.keys.toList() },
        report = {},
        resolveRustKotlinProperties = { null },
        resolvePackageProperties = { null },
        resolveCrateMacroSources = { macroSources },
        resolveCrateFlatFileModules = { _, _ -> flatFileModules },
        resolveCrateRawAbiMetadata = { _, _, raw, split ->
            if (!useCrateMetadata) {
                CrateRawAbiMetadata()
            } else {
                val metaSources =
                    if (metadataStripPrefix != null) {
                        sources.entries.map { it.key.removePrefix(metadataStripPrefix) to it.value }
                    } else {
                        sources.entries.map { it.key to it.value }
                    }
                buildCrateRawAbiMetadataFromSources(
                    sources = metaSources,
                    rawAbiMode = raw,
                    splitModuleRoots = split,
                    finalStructs = finalStructsOverride ?: TranslationSession.finalStructs,
                )
            }
        },
    )
    return written
}
