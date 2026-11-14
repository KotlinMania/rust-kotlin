package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.syn.Item
import io.github.kotlinmania.syn.ModContent

/** Select the original AST nodes that become available only when Rust's test cfg is enabled. */
internal fun renderInlineRustTests(
    source: String,
    headerContext: FileHeaderContext,
): SingleFileTranslation? {
    val recognized = recognizeRust(source)
    val baseProfile = headerContext.cfgTargetProfile ?: TranslationSession.cfgTargetProfile
    val productProfile =
        baseProfile.copy(
            enabledCfgFlags = baseProfile.enabledCfgFlags - "test",
            disabledCfgFlags = baseProfile.disabledCfgFlags + "test",
        )
    val testProfile =
        baseProfile.copy(
            enabledCfgFlags = baseProfile.enabledCfgFlags + "test",
            disabledCfgFlags = baseProfile.disabledCfgFlags - "test",
        )
    fun excludedFile(profile: CfgTargetProfile): Boolean {
        val previous = TranslationSession.cfgTargetProfile
        TranslationSession.cfgTargetProfile = profile
        return try {
            isDocOnlyCfgAttribute(recognized.file.attrs)
        } finally {
            TranslationSession.cfgTargetProfile = previous
        }
    }
    if (excludedFile(testProfile)) return null
    fun excluded(item: Item, profile: CfgTargetProfile): Boolean {
        val previous = TranslationSession.cfgTargetProfile
        TranslationSession.cfgTargetProfile = profile
        return try {
            itemHasDocOnlyCfg(item)
        } finally {
            TranslationSession.cfgTargetProfile = previous
        }
    }
    fun select(items: List<Item>): List<Item> {
        val selected =
            items.mapNotNull { item ->
                when {
                    excluded(item, testProfile) -> null
                    excluded(item, productProfile) -> item
                    item is Item.Mod -> {
                        val content = item.content as? ModContent.Inline
                        if (content == null) {
                            null
                        } else {
                            val children = select(content.items)
                            if (children.isEmpty()) null else item.copy(content = content.copy(items = children))
                        }
                    }
                    else -> null
                }
            }
        if (selected.isEmpty()) return emptyList()
        // Preserve imports from each containing module. Test-module helpers/bodies remain intact.
        return (items.filterIsInstance<Item.Use>().filterNot { excluded(it, testProfile) } + selected).distinct()
    }
    val items = if (excludedFile(productProfile)) recognized.file.items else select(recognized.file.items)
    if (items.isEmpty()) return null
    val preserveNoPortLint =
        headerContext.portLintDirective == "none" ||
            headerContext.portLintSourcePath?.let { path ->
                val norm = path.replace('\\', '/')
                norm == "lib.rs" ||
                    norm == "mod.rs" ||
                    norm.endsWith("/lib.rs") ||
                    norm.endsWith("/mod.rs")
            } == true
    val testHeader =
        if (preserveNoPortLint) {
            headerContext.copy(
                portLintDirective = "none",
                portLintSourcePath = null,
                cfgTargetProfile = testProfile,
            )
        } else {
            headerContext.copy(portLintDirective = "tests", cfgTargetProfile = testProfile)
        }
    // This is still the same Rust file. Reuse its macro-expanded declarations for test-body type/import lowering,
    // without emitting product declarations a second time or resolving another source file.
    val expandedFullFile = expandFileMacroRules(recognized.file, headerContext.crateMacroSources).file
    val previousSharedFile = TranslationSession.sharedModuleFile
    TranslationSession.sharedModuleFile = expandedFullFile
    val emitted =
        try {
            translateRustSourceToKotlinArtifacts(
                recognized.copy(file = recognized.file.copy(items = items)),
                testHeader,
            )
        } finally {
            TranslationSession.sharedModuleFile = previousSharedFile
        }
    return SingleFileTranslation(
        text = emitted.first,
        summary = "inline Rust test items translated",
        companionFiles = emitted.second,
    )
}
