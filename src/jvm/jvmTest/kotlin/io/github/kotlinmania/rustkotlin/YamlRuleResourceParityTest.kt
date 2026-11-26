package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.io.files.SystemFileSystem
import io.github.kotlinmania.io.files.readBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import io.github.kotlinmania.io.files.Path as KmPath

class YamlRuleResourceParityTest {
    private val rulesDir = KmPath("src/common/commonMain/resources/io/github/kotlinmania/rustkotlin/rules")

    @Test
    fun bundledYamlRuleSourcesMatchResourceFilesByteForByte() {
        val dirEntries =
            SystemFileSystem
                .list(rulesDir)
                .map { it.name }
                .filter { it.endsWith(".yml") }
                .sorted()
        assertTrue(dirEntries.isNotEmpty(), "Expected `.yml` rule files under $rulesDir")
        assertEquals(
            dirEntries,
            BundledYamlRuleSources.filesByName.keys.sorted(),
            "Run `python3 tools/sync-yaml-rules.py` to synchronize bundled YAML rule files",
        )
        for (fileName in dirEntries) {
            val diskPath = KmPath("$rulesDir/$fileName")
            val diskMeta = SystemFileSystem.metadataOrNull(diskPath)
            assertNotNull(diskMeta, "Missing YAML resource file: $diskPath")
            assertTrue(diskMeta.isRegularFile, "Expected regular file: $diskPath")
            val diskText = SystemFileSystem.readBytes(diskPath).decodeToString().replace("\r\n", "\n")
            val bundledText = BundledYamlRuleSources.filesByName.getValue(fileName)
            assertEquals(
                diskText,
                bundledText,
                "Resource `$fileName` drifted from `BundledYamlRuleSources`; run `python3 tools/sync-yaml-rules.py`",
            )
            // Also ensure the raw disk file parses directly with zero errors.
            val parsed = YamlDocumentParser.parse(diskText, sourceName = fileName)
            assertTrue(parsed.entries.isNotEmpty(), "Expected non-empty YAML mapping in $fileName")
        }
    }
}
