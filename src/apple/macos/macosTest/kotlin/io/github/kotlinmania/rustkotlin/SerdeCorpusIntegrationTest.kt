package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.io.files.SystemFileSystem
import io.github.kotlinmania.rand.randomLong
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import io.github.kotlinmania.io.files.Path as KmPath

class SerdeCorpusIntegrationTest {
    private val fs = SystemFileSystem

    private fun deleteRecursively(target: KmPath) {
        val meta = fs.metadataOrNull(target) ?: return
        if (meta.isDirectory) {
            for (child in fs.list(target)) {
                deleteRecursively(child)
            }
        }
        fs.delete(target, mustExist = false)
    }

    @Test
    fun serdeJsonFullDirectoryTranslation() {
        val srcRoot = KmPath("../serde-json-kotlin/tmp/serde_json/src")
        if (fs.metadataOrNull(srcRoot)?.isDirectory != true) return
        val tempOutRoot = KmPath("build/test-scratch/serde-json-${randomLong().toULong()}")
        fs.createDirectories(tempOutRoot, mustCreate = false)
        val rustFiles = mutableListOf<KmPath>()
        fun collectRustFiles(dir: KmPath) {
            for (entry in fs.list(dir)) {
                val meta = fs.metadataOrNull(entry) ?: continue
                if (meta.isDirectory) {
                    collectRustFiles(entry)
                } else if (meta.isRegularFile) {
                    val name = entry.name
                    if (name.endsWith(".rs") && name != "lib.rs" && name != "mod.rs") {
                        rustFiles.add(entry)
                    }
                }
            }
        }
        try {
            collectRustFiles(srcRoot)
            rustFiles.sortBy { it.toString() }
            assertEquals(33, rustFiles.size)
            val srcRootPrefix = srcRoot.toString().replace('\\', '/').trimEnd('/') + "/"
            for (rustFile in rustFiles) {
                val relPath = rustFile.toString().replace('\\', '/').removePrefix(srcRootPrefix)
                val relDir = relPath.substringBeforeLast('/', missingDelimiterValue = "")
                val stem = rustFile.name.removeSuffix(".rs")
                val ktName = "${KotlinNamingPolicy.Default.toTypeIdentifier(stem)}.kt"
                val outDir = if (relDir.isEmpty()) tempOutRoot else KmPath(tempOutRoot, relDir)
                fs.createDirectories(outDir, mustCreate = false)
                val ktFile = KmPath(outDir, ktName)
                runSingleFileCommand(
                    arrayOf("translate", rustFile.toString(), ktFile.toString()),
                    read = ::readInput,
                    write = ::writeOverwrite,
                    report = {},
                )
                if (rustFile.name == "macros.rs") {
                    val macrosKt = readInput(ktFile.toString())
                    assertContains(macrosKt, "const val")
                }
            }
        } finally {
            deleteRecursively(tempOutRoot)
        }
    }

    @Test
    fun serdeCoreFullDirectoryTranslation() {
        val srcRoot = KmPath("../serde-kotlin/tmp/serde/serde_core/src")
        if (fs.metadataOrNull(srcRoot)?.isDirectory != true) return
        val tempOutRoot = KmPath("build/test-scratch/serde-core-${randomLong().toULong()}")
        fs.createDirectories(tempOutRoot, mustCreate = false)
        val serdePropsContent =
            """
            project.group=io.github.kotlinmania
            project.name=serde-kotlin
            project.namespace=io.github.kotlinmania.serde
            crate.serde_core=io.github.kotlinmania.serdecore
            """.trimIndent()
        val serdePropsPath = KmPath(tempOutRoot, "rustkotlin.properties").toString()
        writeOverwrite(serdePropsPath, serdePropsContent)
        val serdeProps = parseRustKotlinProperties(serdePropsContent)
        val rustFiles = mutableListOf<KmPath>()
        fun collectAllRustFiles(dir: KmPath) {
            for (entry in fs.list(dir)) {
                val meta = fs.metadataOrNull(entry) ?: continue
                if (meta.isDirectory) {
                    collectAllRustFiles(entry)
                } else if (meta.isRegularFile && entry.name.endsWith(".rs")) {
                    rustFiles.add(entry)
                }
            }
        }
        try {
            collectAllRustFiles(srcRoot)
            rustFiles.sortBy { it.toString() }
            val srcRootPrefix = srcRoot.toString().replace('\\', '/').trimEnd('/') + "/"
            for (rustFile in rustFiles) {
                val name = rustFile.name
                if (name != "lib.rs" && name != "mod.rs") {
                    val relPath = rustFile.toString().replace('\\', '/').removePrefix(srcRootPrefix)
                    val relDir = relPath.substringBeforeLast('/', missingDelimiterValue = "")
                    val stem = name.removeSuffix(".rs")
                    val ktName = "${KotlinNamingPolicy.Default.toTypeIdentifier(stem)}.kt"
                    val outDir = if (relDir.isEmpty()) tempOutRoot else KmPath(tempOutRoot, relDir)
                    fs.createDirectories(outDir, mustCreate = false)
                    val ktFile = KmPath(outDir, ktName)
                    runSingleFileCommand(
                        arrayOf(
                            "translate",
                            "--rustkotlin-properties",
                            serdePropsPath,
                            rustFile.toString(),
                            ktFile.toString(),
                        ),
                        read = ::readInput,
                        write = ::writeOverwrite,
                        report = {},
                    )
                    if (name == "macros.rs" || name == "crate_root.rs") {
                        val macroKt = readInput(ktFile.toString())
                        assertContains(macroKt, "const val")
                    }
                } else {
                    val source = readInput(rustFile.toString())
                    val headerContext =
                        inferFileHeaderContext(
                            inputPath = rustFile.toString(),
                            outputPath = rustFile.toString().removeSuffix(".rs") + ".kt",
                            rustKotlinProperties = serdeProps,
                        )
                    val translated = renderSingleKotlinFile(source, headerContext)
                    assertTrue(translated.text.isNotEmpty(), "Expected non-empty translation for $rustFile")
                }
            }
            val ignoredAnyKt = readInput(KmPath(tempOutRoot, "de/IgnoredAny.kt").toString())
            assertContains(ignoredAnyKt, "// port-lint: source de/ignored_any.rs")
            assertContains(ignoredAnyKt, "package io.github.kotlinmania.serdecore.de")
            assertContains(ignoredAnyKt, "Result<IgnoredAny>")
            val docKt = readInput(KmPath(tempOutRoot, "private/Doc.kt").toString())
            assertContains(docKt, "// port-lint: source private/doc.rs")
            assertContains(docKt, "package io.github.kotlinmania.serdecore.priv")
            val fmtKt = readInput(KmPath(tempOutRoot, "ser/Fmt.kt").toString())
            assertContains(fmtKt, "fun Formatter.serializeBool(")
            assertContains(fmtKt, "fun Formatter.serializeUnitStruct(")

            for (subcrate in listOf("serde/src", "serde_derive/src", "serde_derive_internals/lib.rs")) {
                val subRoot = KmPath("../serde-kotlin/tmp/serde/$subcrate")
                if (!fs.exists(subRoot)) continue
                val subFiles = mutableListOf<KmPath>()
                if (fs.metadataOrNull(subRoot)?.isDirectory == true) {
                    fun collectSub(d: KmPath) {
                        for (entry in fs.list(d)) {
                            val meta = fs.metadataOrNull(entry) ?: continue
                            if (meta.isDirectory) {
                                collectSub(entry)
                            } else if (meta.isRegularFile && entry.name.endsWith(".rs")) {
                                subFiles.add(entry)
                            }
                        }
                    }
                    collectSub(subRoot)
                } else if (fs.metadataOrNull(subRoot)?.isRegularFile == true) {
                    subFiles.add(subRoot)
                }
                for (rf in subFiles) {
                    val src = readInput(rf.toString())
                    val hc =
                        inferFileHeaderContext(
                            inputPath = rf.toString(),
                            outputPath = rf.toString().removeSuffix(".rs") + ".kt",
                            rustKotlinProperties = serdeProps,
                        )
                    val tr = renderSingleKotlinFile(src, hc)
                    assertTrue(tr.text.isNotEmpty(), "Expected non-empty translation for $rf")
                }
            }
        } finally {
            deleteRecursively(tempOutRoot)
        }
    }

    @Test
    fun synNamedRegressionCorpusTranslation() {
        val srcRoot = KmPath("../syn-kotlin/tmp/syn/src")
        if (fs.metadataOrNull(srcRoot)?.isDirectory != true) return
        val scratchDir = KmPath("build/test-scratch/syn-${randomLong().toULong()}")
        fs.createDirectories(scratchDir, mustCreate = false)
        val synPropsPath = KmPath(scratchDir, "rustkotlin.properties").toString()
        writeOverwrite(
            synPropsPath,
            """
            project.group=io.github.kotlinmania
            project.name=syn-kotlin
            project.namespace=io.github.kotlinmania.syn
            """.trimIndent(),
        )

        fun translateStdout(name: String): String {
            val captured = mutableListOf<String>()
            runSingleFileCommand(
                arrayOf(
                    "translate",
                    "--stdout",
                    "--quiet",
                    "--rustkotlin-properties",
                    synPropsPath,
                    "--package",
                    "io.github.kotlinmania.syn",
                    "--source-root",
                    srcRoot.toString(),
                    "$srcRoot/$name.rs",
                ),
                read = ::readInput,
                report = { captured += it },
            )
            return captured.joinToString("\n")
        }

        try {
            val customKeywordKt = translateStdout("custom_keyword")
            assertContains(customKeywordKt, "// port-lint: source custom_keyword.rs")
            assertContains(customKeywordKt, "public const val CFG_FEATURE_PARSING: Boolean = true")
            assertContains(customKeywordKt, "public const val CFG_FEATURE_CLONE_IMPLS: Boolean = true")
            assertFalse(customKeywordKt.contains("CLONE-IMPLS"))
            assertContains(customKeywordKt, "public fun customKeyword(")

            val customPunctuationKt = translateStdout("custom_punctuation")
            assertContains(customPunctuationKt, "// port-lint: source custom_punctuation.rs")
            assertContains(customPunctuationKt, "public fun customPunctuationLen(")
            assertContains(customPunctuationKt, "when {")

            val macrosKt = translateStdout("macros")
            assertContains(macrosKt, "// port-lint: source macros.rs")
            assertContains(macrosKt, "internal fun astStruct(")
            assertContains(macrosKt, "internal fun astEnum(")

            val parseMacroInputKt = translateStdout("parse_macro_input")
            assertContains(parseMacroInputKt, "// port-lint: source parse_macro_input.rs")
            assertContains(parseMacroInputKt, "public fun parseMacroInput(")

            val dropsErr = assertFailsWith<IllegalArgumentException> { translateStdout("drops") }
            assertContains(dropsErr.message.orEmpty(), "Unsupported Rust destructor-suppression semantics")

            val fixupKt = translateStdout("fixup")
            assertContains(fixupKt, "// port-lint: source fixup.rs")
            assertContains(fixupKt, "package io.github.kotlinmania.syn")

            val exportKt = translateStdout("export")
            assertContains(exportKt, "public typealias Formatter = StringBuilder")
            assertContains(exportKt, "public typealias FmtResult = Unit")
            assertContains(exportKt, "public typealias TokenStream2 = TokenStream")
            assertContains(exportKt, "public open class Private(internal var value: Unit)")
            assertFalse(exportKt.contains("= Any"))
            assertFalse(exportKt.contains("typealias Boolean"))
            assertFalse(exportKt.contains("typealias String"))
            assertFalse(exportKt.contains("typealias Span"))
            assertFalse(exportKt.contains("typealias TokenStream ="))

            val threadKt = translateStdout("thread")
            assertContains(threadKt, "internal open class ThreadBound<T>")
            assertContains(threadKt, "internal fun <T> new(value: T): ThreadBound<T>")
            assertContains(threadKt, "public open fun clone(): ThreadBound<T>")
            assertContains(threadKt, "internal expect fun currentThreadBoundToken(): Any")
            assertContains(threadKt, "Send bound requires Copy")
            assertContains(threadKt, "Copy the bytes of T")

            val spanKt = translateStdout("span")
            assertContains(spanKt, "public interface IntoSpans<S>")
            assertContains(spanKt, "public fun Span.intoSpansDelimSpan(): DelimSpan")
            assertEquals(9, Regex("public fun (Span|List<Span>|DelimSpan)\\.intoSpans").findAll(spanKt).count())

            val spannedKt = translateStdout("spanned")
            assertContains(spannedKt, "import io.github.kotlinmania.quote.span as quoteSpan")
            assertContains(spannedKt, "public interface Spanned")
            assertContains(spannedKt, "return this.quoteSpan()")
            assertFalse(spannedKt.contains("return this.span()"))
            assertFalse(spannedKt.contains("__span"))
            assertFalse(spannedKt.contains("import io.github.kotlinmania.priv.Sealed"))

            val parseKt = translateStdout("parse")
            assertContains(parseKt, "public interface Parse<T>")
            assertContains(parseKt, "public fun parse(input: ParseStream): Result<T>")
            assertContains(parseKt, "public interface Parser<Output>")
            assertContains(parseKt, "public fun <T> ((ParseStream) -> Result<T>).parse2(")
            assertFalse(parseKt.contains("typealias Output<T> = T"))

            val parseQuoteKt = translateStdout("parse_quote")
            assertContains(parseQuoteKt, "public interface ParseQuote<T>")
            assertContains(parseQuoteKt, "parseTFn: (ParseStream) -> Result<T>")
            assertContains(parseQuoteKt, "parseQuoteParsePat(")
            assertContains(parseQuoteKt, "input.parse(Visibility::parse)")
            assertContains(parseQuoteKt, "`@...`")
            assertContains(parseQuoteKt, "`@file:...`")
            assertFalse(parseQuoteKt.contains("<T as"))
            assertFalse(parseQuoteKt.contains("T::parse"))
            assertFalse(parseQuoteKt.contains("Box::new"))

            val punctuatedKt = translateStdout("punctuated")
            assertContains(punctuatedKt, "public open class Punctuated<T, P>")
            assertContains(punctuatedKt, "public sealed class Pair<T, P>")
            assertContains(punctuatedKt, "kotlin.Pair<T, P>")
            assertContains(punctuatedKt, "public open class Iter<T>(public var inner: IterTrait<T>)")
            assertContains(punctuatedKt, "public open class IterMut<T>(public var inner: IterMutTrait<T>)")
            assertContains(punctuatedKt, "public open class IntoIter<T>(public var inner: ArrayDeque<T>)")
            assertContains(punctuatedKt, "open class PrivateIter<T, P>(")
            assertContains(punctuatedKt, ": IterTrait<T>")
            assertContains(punctuatedKt, "override fun hashCode(): Int")
            assertContains(punctuatedKt, "defaultPFn: () -> P")
            assertContains(punctuatedKt, "parseQuote(\"scope\")")
            assertFalse(punctuatedKt.contains("typealias Item<T> = T"))
            assertFalse(punctuatedKt.contains("typealias Output<T> = T"))
            assertFalse(punctuatedKt.contains("is Any ->"))
            assertFalse(punctuatedKt.contains("core.hash"))

            val metaKt = translateStdout("meta")
            assertContains(metaKt, "@ProcMacroAttribute")
            assertContains(metaKt, "@tea(kind = \"EarlGrey\", hot)")
            assertContains(metaKt, "val content = parens.content")
            assertContains(metaKt, "input.call(::parseMetaPath)")
            assertContains(metaKt, "this.input.parse(Eq::parse)")
            assertFalse(metaKt.contains("#[proc_macro_attribute]"))
            assertFalse(metaKt.contains("content: Any?"))
            assertFalse(metaKt.contains("is Ok ->"))
        } finally {
            deleteRecursively(scratchDir)
        }
    }
}
