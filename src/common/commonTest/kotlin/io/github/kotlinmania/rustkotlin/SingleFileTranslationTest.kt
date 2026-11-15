package io.github.kotlinmania.rustkotlin

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SingleFileTranslationTest {
    private class Files(
        val source: String,
        val extraFiles: Map<String, String> = emptyMap(),
        val existingOutputs: Set<String> = emptySet(),
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
                write = { path, text ->
                    check(path !in existingOutputs && writes.put(path, text) == null)
                },
                writeForce = { path, text ->
                    forcedWrites[path] = text
                    writes[path] = text
                },
                exists = { path -> path in existingOutputs || path in writes },
                isDirectory = { path -> path in directories },
                collectRustFiles = { roots ->
                    roots.flatMap { r -> directories[r].orEmpty() }
                },
                report = { messages += it },
                resolveGradleProperties = { null },
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

    @Test fun crossbeamEpochUsesUnsignedWrappingArithmetic() {
        // crossbeam-epoch/src/epoch.rs: Epoch, wrapping_sub and successor with their 64-bit aliases.
        val source =
            """
            type EpochRepr = u64;
            type EpochReprSigned = i64;
            pub(crate) struct Epoch {
                /// The least significant bit is set if pinned. The rest of the bits hold the epoch.
                data: EpochRepr,
            }

            impl Epoch {
                pub(crate) fn wrapping_sub(self, rhs: Self) -> EpochReprSigned {
                    // The result is the same with `(self.data & !1).wrapping_sub(rhs.data & !1) as isize >> 1`,
                    // because the possible difference of LSB in `(self.data & !1).wrapping_sub(rhs.data & !1)`
                    // will be ignored in the shift operation.
                    self.data.wrapping_sub(rhs.data & !1) as EpochReprSigned >> 1
                }

                pub(crate) fn successor(self) -> Epoch {
                    Epoch {
                        data: self.data.wrapping_add(2),
                    }
                }
            }
            """.trimIndent()
        val result = renderSingleKotlinFile(source, FileHeaderContext(portLintSourcePath = "epoch.rs"))
        assertContains(result.text, "(this.data + 2uL)")
        assertContains(result.text, "(this.data - (rhs.data and 1uL.inv())).toLong() shr 1")
        assertFalse(result.text.contains("wrappingAdd("))
    }

    @Test fun completeFunctionTranslationReadsAndWritesExactlyOneNamedFile() {
        val files = Files("pub fn choose_item(flag_value: bool) -> bool { !flag_value }")
        files.run("translate", "one.rs", "One.kt")
        assertEquals(listOf("one.rs"), files.reads)
        assertEquals(setOf("One.kt"), files.writes.keys)
        val output = files.writes.getValue("One.kt")
        assertContains(output, "public fun chooseItem(flagValue: Boolean): Boolean")
        assertContains(output, "!flagValue")
    }

    @Test fun invalidCommandsExtraOptionsAndRootFilesRejectBeforeAnyIo() {
        for (args in listOf(
            listOf("other", "one.rs", "One.kt"),
            listOf("candidates", "one.rs", "One.kt"),
            listOf("translate", "one.rs", "directory"),
            listOf("translate", "one.rs", "One.kt", "--extra-option"),
            listOf("translate", "lib.rs", "Lib.kt"),
            listOf("translate", "mod.rs", "Mod.kt"),
        )) {
            val files = Files("pub const X: u32 = 1;")
            assertFailsWith<Exception>(args.toString()) { files.run(*args.toTypedArray()) }
            assertEquals(emptyList(), files.reads)
            assertEquals(emptyMap(), files.writes)
        }
    }

    @Test fun inspectNeverProducesOutputFiles() {
        val inspection = Files("pub const X: u32 = 1;")
        inspection.run("inspect", "one.rs")
        assertEquals(listOf("one.rs"), inspection.reads)
        assertEquals(emptyMap(), inspection.writes)
    }

    @Test fun unparsedInputFailsImmediatelyWithoutPassThroughFallback() {
        val files = Files("fn invalid( {")
        assertFailsWith<Exception> {
            files.run("translate", "one.rs", "One.kt")
        }
        assertEquals(emptyMap(), files.writes)

        var attempts = 0
        assertFailsWith<Exception> {
            runSingleFileCommand(
                arrayOf("translate", "one.rs", "One.kt"),
                read = { "pub const X: u32 = 1;" },
                write = { _, _ ->
                    attempts += 1
                    error("write failed")
                },
                report = {},
                resolveGradleProperties = { null },
                resolvePackageProperties = { null },
                resolveCrateMacroSources = { emptyList() },
                resolveCrateFlatFileModules = { _, _ -> emptySet() },
                resolveCrateRawAbiMetadata = { _, _, _, _ -> CrateRawAbiMetadata() },
            )
        }
        assertEquals(1, attempts)
    }

    @Test fun unionStructEnumAndAttachedImplTranslateAsKotlinClasses() {
        val source =
            """
            pub struct GlyphId(pub u16);
            impl GlyphId {
                pub fn next_id(&self) -> u16 {
                    self.0 + 1
                }
            }
            pub union Bits {
                pub integer: u32,
                pub float: f32,
            }
            """.trimIndent()
        val result = renderSingleKotlinFile(source)
        assertContains(result.text, "public open class GlyphId")
        assertContains(result.text, "public open fun nextId(): UShort")
        assertContains(result.text, "public open class Bits")
        assertFalse(result.text.contains("pub union"))
    }

    @Test fun macroRulesAndFormatStringsExpandIntoKotlinAst() {
        val source =
            """
            macro_rules! impl_val {
                (${'$'}t:ty) => {
                    impl ${'$'}t {
                        pub fn describe(&self) -> String {
                            format!("value={}", self.code)
                        }
                    }
                };
            }
            pub struct Entry {
                pub code: u32,
            }
            impl_val!(Entry);
            """.trimIndent()
        val result = renderSingleKotlinFile(source)
        assertContains(result.text, "public open class Entry")
        assertContains(result.text, "public open fun describe(): String")
        assertContains(result.text, "\"value=\${this.code}\"")
    }

    @Test fun quoteAndTokenMacrosTranslateToTypedKotlinCalls() {
        val source =
            """
            pub fn build_tokens(serde_path: &syn::Path) -> proc_macro2::TokenStream {
                let sep = syn::Token![::](proc_macro2::Span::call_site());
                quote! {
                    use #serde_path as _serde;
                }
            }
            """.trimIndent()
        val result = renderSingleKotlinFile(source)
        assertContains(result.text, "val sep = PathSep.from(Span.callSite())")
        assertContains(result.text, "return quote(\"use `#`serdePath as _serde ;\", \"serdePath\" to serdePath)")
    }

    @Test fun tryOperatorTranslatesToGetOrElseNonLocalFailureReturn() {
        val source =
            """
            pub fn arbitrary(u: &mut Unstructured) -> Result<IndexMap> {
                u.arbitrary_iter()?.collect()
            }
            """.trimIndent()
        val result = renderSingleKotlinFile(source)
        assertContains(result.text, "return u.arbitraryIter().getOrElse { return Result.failure(it) }.collect()")
    }

    @Test fun gradlePropertiesAndPerFolderPackagePropertiesResolveViaKmIo() {
        val gradleProps =
            parseGradleProperties(
                """
                project.group=io.github.kotlinmania
                project.name=indexmap-kotlin
                """.trimIndent(),
            )
        assertEquals("io.github.kotlinmania", gradleProps.projectGroup)
        assertEquals("io.github.kotlinmania.indexmap", gradleProps.projectNamespace)

        val folderProps =
            parsePackageProperties(
                """
                package=io.github.kotlinmania.indexmap.map.custom
                crate.core=io.github.kotlinmania.rustcore
                """.trimIndent(),
            )
        val ctx =
            inferFileHeaderContextWithResolvers(
                inputPath = "/workspace/indexmap-kotlin/tmp/indexmap/src/map/slice.rs",
                outputPath = "/workspace/indexmap-kotlin/tmp/indexmap/src/map/Slice.kt",
                gradleProperties = gradleProps,
                packageProperties = folderProps,
                resolveGradleProperties = { null },
                resolvePackageProperties = { null },
                resolveCrateMacroSources = { emptyList() },
                resolveCrateFlatFileModules = { _, _ -> emptySet() },
            )
        assertEquals("io.github.kotlinmania.indexmap.map.custom", ctx.packageName)
        assertEquals("io.github.kotlinmania.rustcore", ctx.crateNamespaces["core"])
    }

    @Test fun starlarkScopeTracksShadowingBlockUnscopingAndModuleImports() {
        val source =
            """
            use std::io::{self, Read};

            pub fn check_crc(data: &[u8], start: usize) -> io::Result<usize> {
                let mut reader = Crc32Reader::new(data, 0x9be3e0a3, false);
                let count = reader.read(&mut buf[..0])?;
                {
                    let reader = reader.into_inner();
                    consume(reader);
                }
                let mut reader = Crc32Reader::new(&data[start..], 1, false);
                if reader.read(&mut buf[..count]).is_err() {
                    return Err(io::Error::new(io::ErrorKind::InvalidData, "Invalid checksum"));
                }
                Ok(count)
            }
            """.trimIndent()
        val result = renderSingleKotlinFile(source)
        assertContains(result.text, "public fun checkCrc(data: List<UByte>, start: Int): io.Result<Int>")
        assertContains(result.text, "var reader = Crc32Reader.new(data, 0x9be3e0a3, false)")
        assertContains(result.text, "val count = reader.read(buf[0 until 0]).getOrElse { return Result.failure(it) }")
        assertContains(result.text, "val reader1 = reader.intoInner()")
        assertContains(result.text, "consume(reader1)")
        assertContains(result.text, "var reader2 = Crc32Reader.new(((data).subList(start, (data).size)), 1, false)")
        assertContains(result.text, "if (reader2.read(buf[0 until count]).isErr())")
        assertContains(result.text, "io.Error.new(io.ErrorKind.InvalidData, \"Invalid checksum\")")
    }

    @Test fun unitStructValueConstructionUnreachableOkUnitAndDerefSelf() {
        val source =
            """
            //! Reimplements core logic and types from `std::io` in an `alloc`-friendly
            //! fashion.

            use alloc::vec::Vec;
            use core::fmt::{self, Display};
            use core::result;

            pub enum ErrorKind {
                Other,
            }

            pub struct Error;

            impl Display for Error {
                fn fmt(&self, _formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
                    unreachable!()
                }
            }

            impl Error {
                pub(crate) fn new(_kind: ErrorKind, _error: &'static str) -> Error {
                    Error
                }
            }

            pub type Result<T> = result::Result<T, Error>;

            pub trait Write {
                fn write(&mut self, buf: &[u8]) -> Result<usize>;

                fn write_all(&mut self, buf: &[u8]) -> Result<()> {
                    // All our Write impls in no_std mode always write the whole buffer in
                    // one call infallibly.
                    let result = self.write(buf);
                    debug_assert!(result.is_ok());
                    debug_assert_eq!(result.unwrap_or(0), buf.len());
                    Ok(())
                }

                fn flush(&mut self) -> Result<()>;
            }

            impl<W: Write> Write for &mut W {
                #[inline]
                fn write(&mut self, buf: &[u8]) -> Result<usize> {
                    (*self).write(buf)
                }
            }
            """.trimIndent()
        val result = renderSingleKotlinFile(source)
        assertContains(result.text, "// Reimplements core logic and types from `std.io` in an `alloc`-friendly")
        assertContains(result.text, "// All our Write impls in noStd mode always write the whole buffer in")
        assertContains(result.text, "return error(\"unreachable\")")
        assertContains(result.text, "return Error()")
        assertContains(result.text, "check(result.unwrapOr(0) == buf.size)")
        assertContains(result.text, "return Result.success(Unit)")
        assertFalse(result.text.contains("fun <W : Write> W.write("))
    }

    @Test fun serdeJsonPatternsSubpatMatchCompoundShiftScreamingSnakeTriAndNestedMacros() {
        val source =
            """
            const F64_POW10: [f64; 2] = [1.0, 10.0];

            macro_rules! overflow {
                (${'$'}a:ident * 10 + ${'$'}b:ident, ${'$'}c:expr) => {
                    match ${'$'}c {
                        c => ${'$'}a >= c / 10 && (${'$'}a > c / 10 || ${'$'}b > c % 10),
                    }
                };
            }

            macro_rules! partialeq_numeric {
                (${'$'}(${'$'}eq:ident [${'$'}(${'$'}ty:ty)*])*) => {
                    ${'$'}(${'$'}(
                        impl PartialEq<${'$'}ty> for Value {
                            fn eq(&self, other: &${'$'}ty) -> bool {
                                ${'$'}eq(self, *other as _)
                            }
                        }
                    )*)*
                }
            }

            partialeq_numeric! {
                eq_i64[i32 i64]
                eq_bool[bool]
            }

            pub fn parse_digits(next: u8, fp_mant: u64, shift: i32) -> Result<u64, Error> {
                let mut mant = fp_mant;
                mant >>= shift;
                mant <<= shift;
                debug_assert!(shift < 64, "shift overflow");
                let scale = F64_POW10[0];
                let limit = u64::MAX;
                match next {
                    b'0' => Ok(0),
                    c @ b'1'..=b'9' => {
                        let digit = (c - b'0') as u64;
                        if overflow!(mant * 10 + digit, u64::MAX) {
                            return Err(Error);
                        }
                        Ok(mant + digit + (scale as u64) + (limit & 0))
                    }
                    other => Ok(other as u64),
                }
            }

            pub fn read_step(self_peek: Result<Option<u8>, Error>) -> Result<u8, Error> {
                match tri!(self_peek) {
                    Some(b' ' | b'\n' | b'\t' | b'\r') => Ok(0),
                    Some(other) => Ok(other),
                    None => Ok(0),
                }
            }
            """.trimIndent()
        val result = renderSingleKotlinFile(source)
        assertContains(result.text, "public fun Value.eq(other: Int): Boolean")
        assertContains(result.text, "public fun Value.eq(other: Long): Boolean")
        assertContains(result.text, "public fun Value.eq(other: Boolean): Boolean")
        assertContains(result.text, "mant = mant shr shift")
        assertContains(result.text, "mant = mant shl shift")
        assertContains(result.text, "check(shift < 64) { \"shift overflow\" }")
        assertContains(result.text, "val scale = F64_POW10[0]")
        assertContains(result.text, "val limit = ULong.MAX_VALUE")
        assertContains(result.text, "in 49u..57u ->")
        assertContains(result.text, "val c = next")
        assertContains(result.text, "val other = next")
        assertContains(result.text, "val matchVal = selfPeek.getOrElse { return Result.failure(it) }")
        assertContains(result.text, "matchVal != null && (matchVal == 32u || matchVal == 10u || matchVal == 9u || matchVal == 13u) -> Result.success(0u)")
    }

    @Test fun cliRichOptionsSupportRecursiveBatchStdoutDryRunForceSkipAndHeaderOverrides() {
        val helpRun = Files("pub const X: u32 = 1;")
        helpRun.run("--help")
        assertEquals(emptyList(), helpRun.reads)
        assertContains(helpRun.messages.single(), "Usage:")

        val versionRun = Files("pub const X: u32 = 1;")
        versionRun.run("--version")
        assertEquals(emptyList(), versionRun.reads)
        assertContains(versionRun.messages.single(), "rust-kotlin 0.1.0")

        val stdoutRun = Files("use serde_core::de::Visitor;\npub fn ping() -> i32 { 7 }")
        stdoutRun.run(
            "--stdout",
            "-p",
            "io.github.kotlinmania.demo",
            "-c",
            "serde_core=io.github.kotlinmania.serdecore",
            "--source-root",
            "/repo/tests",
            "--port-lint",
            "tests",
            "/repo/tests/suite/ping_case.rs",
        )
        assertEquals(emptyMap(), stdoutRun.writes)
        val stdoutKt = stdoutRun.messages.single()
        assertContains(stdoutKt, "// port-lint: tests suite/ping_case.rs")
        assertContains(stdoutKt, "package io.github.kotlinmania.demo")
        assertContains(stdoutKt, "import io.github.kotlinmania.serdecore.de.Visitor")
        assertContains(stdoutKt, "public fun ping(): Int")

        val noPortLintRun = Files("pub fn quiet_fn() -> bool { true }")
        noPortLintRun.run("translate", "--in-place", "--port-lint=none", "-q", "dir/quiet_case.rs")
        assertEquals(setOf("dir/QuietCase.kt"), noPortLintRun.writes.keys)
        assertEquals(emptyList(), noPortLintRun.messages)
        assertFalse(noPortLintRun.writes.getValue("dir/QuietCase.kt").contains("// port-lint:"))

        val dryRun = Files("pub fn check_dry() -> u8 { 1 }")
        dryRun.run("translate", "--dry-run", "sub/check_dry.rs")
        assertEquals(emptyMap(), dryRun.writes)
        assertContains(dryRun.messages.single(), "dry-run: sub/check_dry.rs -> sub/CheckDry.kt")

        val batchFiles =
            Files(
                source = "pub fn entry() -> i32 { 1 }",
                extraFiles =
                    mapOf(
                        "crate/src/top_level.rs" to "pub fn top() -> i32 { 1 }",
                        "crate/src/private/inner_mod.rs" to "pub fn inner() -> i32 { 2 }",
                    ),
                existingOutputs = setOf("out/src/common/commonMain/kotlin/io/github/kotlinmania/sample/TopLevel.kt"),
                directories =
                    mapOf(
                        "crate/src" to
                            listOf(
                                "crate/src/top_level.rs",
                                "crate/src/private/inner_mod.rs",
                            ),
                    ),
            )
        batchFiles.run(
            "translate",
            "-r",
            "--skip-existing",
            "-o",
            "out/src/common/commonMain/kotlin/io/github/kotlinmania/sample",
            "crate/src",
        )
        assertEquals(
            setOf("out/src/common/commonMain/kotlin/io/github/kotlinmania/sample/priv/InnerMod.kt"),
            batchFiles.writes.keys,
        )
        val innerKt = batchFiles.writes.getValue("out/src/common/commonMain/kotlin/io/github/kotlinmania/sample/priv/InnerMod.kt")
        assertContains(innerKt, "// port-lint: source private/inner_mod.rs")
        assertContains(innerKt, "package io.github.kotlinmania.sample.priv")

        val forceRun =
            Files(
                source = "pub fn replace_me() -> i32 { 9 }",
                existingOutputs = setOf("Existing.kt"),
            )
        forceRun.run("translate", "-f", "-o", "Existing.kt", "existing.rs")
        assertEquals(setOf("Existing.kt"), forceRun.forcedWrites.keys)
    }

    @Test fun phaseFourRegressionCoversUntilLoopsWhenGuardsForwardDeserializeLocalItemsCompanionAndDeduplication() {
        val source =
            """
            use alloc::string::String;
            use core::fmt::Display;
            use core::result::Result;

            pub struct Config {
                pub limit: usize,
            }

            impl Default for Config {
                fn default() -> Self {
                    Config { limit: 16 }
                }
            }

            impl From<Vec<u8>> for Config {
                fn from(bytes: Vec<u8>) -> Self {
                    Config { limit: bytes.len() }
                }
            }

            impl From<Vec<String>> for Config {
                fn from(items: Vec<String>) -> Self {
                    Config { limit: items.len() }
                }
            }

            impl<T> SeqSpec for ExternalSeq<T> {
                type ItemSlot = Vec<T>;
            }

            impl<'de> Deserializer<'de> for ExternalDe {
                type Error = DeError;

                forward_to_deserialize_any! {
                    bool i8 byte_buf unit_struct tuple tuple_struct struct enum ignored_any
                }
            }

            impl<'de> Deserializer<'de> for &'de ExternalDe {
                type Error = DeError;

                forward_to_deserialize_any! {
                    bool i8
                }
            }

            pub fn evaluate_cases(pair: (Option<i32>, Option<i32>), code: u8) -> i32 {
                #[cfg(target_pointer_width = "64")]
                type WordChunk = u64;
                #[cfg(target_pointer_width = "32")]
                type WordChunk = u32;
                const STEP_SIZE: usize = 8;
                struct LocalHelper;
                impl LocalHelper {
                    fn seed() -> i32 {
                        1
                    }
                }
                fn local_step(v: i32) -> i32 {
                    v + 1
                }

                let tail_range = 3..;
                let mut total = code as i32 + (code - 1) as i32 + (-1 as i64) as i32;
                for _ in 0..4 {
                    for _ in 0..2 {
                        total += local_step(STEP_SIZE as i32);
                    }
                }
                let bonus = match code {
                    b'0' => 0,
                    b'1'..=b'9' => {
                        total += 1;
                        2
                    }
                    0xFF => return -1,
                    _ => (code & 0b0001_1111 | 0b1100_0000) as i32,
                };
                match pair {
                    (Some(a), Some(b)) if a == b => a + b + bonus + tail_range.start,
                    (Some(n), None) => n + total,
                    _ => total,
                }
            }
            """.trimIndent()
        val result = renderSingleKotlinFile(source)
        val kt = result.text

        assertFalse(kt.contains("import alloc.string.String"))
        assertFalse(kt.contains("import core.result.Result"))
        assertFalse(kt.contains("import core.fmt.Display"))

        assertContains(kt, "public typealias ItemSlot<T> = List<T>")
        assertContains(kt, "public typealias WordChunk = ULong")
        assertFalse(kt.contains("typealias WordChunk = UInt"))

        assertContains(kt, "public companion object")
        assertContains(kt, "public fun default(): Config")
        assertEquals(1, Regex("fun from\\(").findAll(kt).count())

        assertContains(kt, "public fun <V : Visitor> ExternalDe.deserializeBool(visitor: V): Result<Any?, DeError>")
        assertContains(kt, "public fun <V : Visitor> ExternalDe.deserializeI8(visitor: V): Result<Any?, DeError>")
        assertContains(kt, "public fun <V : Visitor> ExternalDe.deserializeByteBuf(visitor: V): Result<Any?, DeError>")
        assertContains(kt, "public fun <V : Visitor> ExternalDe.deserializeUnitStruct(name: String, visitor: V): Result<Any?, DeError>")
        assertContains(kt, "public fun <V : Visitor> ExternalDe.deserializeTuple(len: Int, visitor: V): Result<Any?, DeError>")
        assertContains(
            kt,
            "public fun <V : Visitor> ExternalDe.deserializeTupleStruct(name: String, len: Int, visitor: V): Result<Any?, DeError>",
        )
        assertContains(
            kt,
            "public fun <V : Visitor> ExternalDe.deserializeStruct(name: String, fields: List<String>, visitor: V): Result<Any?, DeError>",
        )
        assertContains(
            kt,
            "public fun <V : Visitor> ExternalDe.deserializeEnum(name: String, variants: List<String>, visitor: V): Result<Any?, DeError>",
        )
        assertContains(kt, "public fun <V : Visitor> ExternalDe.deserializeIgnoredAny(visitor: V): Result<Any?, DeError>")
        assertEquals(1, Regex("ExternalDe\\.deserializeBool\\(").findAll(kt).count())

        assertContains(kt, "val STEP_SIZE: Int = 8")
        assertContains(kt, "open class LocalHelper")
        assertFalse(kt.contains("public open class LocalHelper"))
        assertContains(kt, "fun localStep(v: Int): Int")
        assertFalse(kt.contains("public fun localStep"))

        assertContains(kt, "val tailRange = 3 until Int.MAX_VALUE")
        assertContains(kt, "code.toInt() + (code - 1u).toInt()")
        assertContains(kt, "for (unused in 0 until 4)")
        assertContains(kt, "for (unused1 in 0 until 2)")
        assertContains(kt, "48u -> { 0 }")
        assertContains(kt, "in 49u..57u -> {")
        assertContains(kt, "0xFF -> { return -1 }")
        assertContains(kt, "0x1F")
        assertContains(kt, "0xC0")
        assertFalse(kt.contains("0b0001"))
        assertFalse(kt.contains("-> run {"))
        assertContains(kt, "when {")
        assertContains(kt, "pair.first != null && pair.second != null && pair.first == pair.second ->")
        assertFalse(kt.contains(" if "))
        assertFalse(kt.contains("..<"))
    }

    @Test fun libcMacrosExpandStructsUnionsTupleStructsCfgIfCEnumsAndFunctions() {
        val libcMacrosRs =
            """
            macro_rules! cfg_if {
                (${'$'}(
                    if #[cfg(${'$'}(${'$'}meta:meta),*)] { ${'$'}(${'$'}it:item)* }
                ) else * else {
                    ${'$'}(${'$'}it2:item)*
                }) => {
                    cfg_if! {
                        @__items
                        () ;
                        ${'$'}( ( (${'$'}(${'$'}meta),*) (${'$'}(${'$'}it)*) ), )*
                        ( () (${'$'}(${'$'}it2)*) ),
                    }
                };
                (
                    if #[cfg(${'$'}(${'$'}i_met:meta),*)] { ${'$'}(${'$'}i_it:item)* }
                    ${'$'}(
                        else if #[cfg(${'$'}(${'$'}e_met:meta),*)] { ${'$'}(${'$'}e_it:item)* }
                    )*
                ) => {
                    cfg_if! {
                        @__items
                        () ;
                        ( (${'$'}(${'$'}i_met),*) (${'$'}(${'$'}i_it)*) ),
                        ${'$'}( ( (${'$'}(${'$'}e_met),*) (${'$'}(${'$'}e_it)*) ), )*
                        ( () () ),
                    }
                };
                (@__items (${'$'}(${'$'}not:meta,)*) ; ) => {};
                (@__items (${'$'}(${'$'}not:meta,)*) ; ( (${'$'}(${'$'}m:meta),*) (${'$'}(${'$'}it:item)*) ), ${'$'}(${'$'}rest:tt)*) => {
                    cfg_if! { @__apply cfg(all(${'$'}(${'$'}m,)* not(any(${'$'}(${'$'}not),*)))), ${'$'}(${'$'}it)* }
                    cfg_if! { @__items (${'$'}(${'$'}not,)* ${'$'}(${'$'}m,)*) ; ${'$'}(${'$'}rest)* }
                };
                (@__apply ${'$'}m:meta, ${'$'}(${'$'}it:item)*) => {
                    ${'$'}(#[${'$'}m] ${'$'}it)*
                };
            }

            macro_rules! s {
                (${'$'}(
                    ${'$'}(#[${'$'}attr:meta])*
                    ${'$'}pub:vis ${'$'}t:ident ${'$'}i:ident { ${'$'}(${'$'}field:tt)* }
                )*) => (${'$'}(
                    s!(it: ${'$'}(#[${'$'}attr])* ${'$'}pub ${'$'}t ${'$'}i { ${'$'}(${'$'}field)* });
                )*);
                (it: ${'$'}(#[${'$'}attr:meta])* ${'$'}pub:vis struct ${'$'}i:ident { ${'$'}(${'$'}field:tt)* }) => (
                    #[repr(C)]
                    ${'$'}(#[${'$'}attr])*
                    ${'$'}pub struct ${'$'}i { ${'$'}(${'$'}field)* }
                );
            }

            macro_rules! s_paren {
                (${'$'}(
                    ${'$'}(#[${'$'}attr:meta])*
                    ${'$'}pub:vis struct ${'$'}i:ident ( ${'$'}(${'$'}field:tt)* );
                )*) => (${'$'}(
                    ${'$'}(#[${'$'}attr])*
                    ${'$'}pub struct ${'$'}i ( ${'$'}(${'$'}field)* );
                )*);
            }

            macro_rules! s_no_extra_traits {
                (${'$'}(
                    ${'$'}(#[${'$'}attr:meta])*
                    ${'$'}pub:vis ${'$'}t:ident ${'$'}i:ident { ${'$'}(${'$'}field:tt)* }
                )*) => (${'$'}(
                    s_no_extra_traits!(it: ${'$'}(#[${'$'}attr])* ${'$'}pub ${'$'}t ${'$'}i { ${'$'}(${'$'}field)* });
                )*);
                (it: ${'$'}(#[${'$'}attr:meta])* ${'$'}pub:vis union ${'$'}i:ident { ${'$'}(${'$'}field:tt)* }) => (
                    #[repr(C)]
                    ${'$'}(#[${'$'}attr])*
                    ${'$'}pub union ${'$'}i { ${'$'}(${'$'}field)* }
                );
                (it: ${'$'}(#[${'$'}attr:meta])* ${'$'}pub:vis struct ${'$'}i:ident { ${'$'}(${'$'}field:tt)* }) => (
                    #[repr(C)]
                    ${'$'}(#[${'$'}attr])*
                    ${'$'}pub struct ${'$'}i { ${'$'}(${'$'}field)* }
                );
            }

            macro_rules! c_enum {
                (${'$'}(
                    ${'$'}(#[repr(${'$'}repr:ty)])?
                    pub enum ${'$'}(${'$'}ty_name:ident)? ${'$'}(#${'$'}anon:ident)? {
                        ${'$'}(${'$'}vis:vis ${'$'}variant:ident ${'$'}(= ${'$'}value:expr)?,)+
                    }
                )+) => {
                    ${'$'}(c_enum!(@single;
                        ${'$'}(#[repr(${'$'}repr)])?
                        pub enum ${'$'}(${'$'}ty_name)? ${'$'}(#${'$'}anon)? {
                            ${'$'}(${'$'}vis ${'$'}variant ${'$'}(= ${'$'}value)?,)+
                        }
                    );)+
                };
                (@single;
                    ${'$'}(#[repr(${'$'}repr:ty)])?
                    pub enum ${'$'}ty_name:ident {
                        ${'$'}(${'$'}vis:vis ${'$'}variant:ident ${'$'}(= ${'$'}value:expr)?,)+
                    }
                ) => {
                    pub type ${'$'}ty_name = c_enum!(@ty ${'$'}(${'$'}repr)?);
                    c_enum! {
                        @variant;
                        ty: ${'$'}ty_name;
                        default: 0;
                        variants: [${'$'}(${'$'}vis ${'$'}variant ${'$'}(= ${'$'}value)?,)+]
                    }
                };
                (@single;
                    ${'$'}(#[repr(${'$'}repr:ty)])?
                    pub enum #anon {
                        ${'$'}(${'$'}vis:vis ${'$'}variant:ident ${'$'}(= ${'$'}value:expr)?,)+
                    }
                ) => {
                    c_enum! {
                        @variant;
                        ty: c_enum!(@ty ${'$'}(${'$'}repr)?);
                        default: 0;
                        variants: [${'$'}(${'$'}vis ${'$'}variant ${'$'}(= ${'$'}value)?,)+]
                    }
                };
                (@variant; ty: ${'$'}_ty_name:ty; default: ${'$'}_idx:expr; variants: []) => {};
                (
                    @variant;
                    ty: ${'$'}ty_name:ty;
                    default: ${'$'}default_val:expr;
                    variants: [
                        ${'$'}vis:vis ${'$'}variant:ident ${'$'}(= ${'$'}value:expr)?,
                        ${'$'}(${'$'}tail:tt)*
                    ]
                ) => {
                    ${'$'}vis const ${'$'}variant: ${'$'}ty_name = {
                        #[allow(unused_variables)]
                        let r = ${'$'}default_val;
                        ${'$'}(let r = ${'$'}value;)?
                        r
                    };
                    c_enum! {
                        @variant;
                        ty: ${'$'}ty_name;
                        default: ${'$'}variant + 1;
                        variants: [${'$'}(${'$'}tail)*]
                    }
                };
                (@ty ${'$'}repr:ty) => { ${'$'}repr };
                (@ty) => { ${'$'}crate::prelude::CEnumRepr };
            }

            macro_rules! f {
                (${'$'}(
                    ${'$'}(#[${'$'}attr:meta])*
                    pub ${'$'}(fn ${'$'}i:ident)? ${'$'}(const fn ${'$'}const_i:ident)?
                    (${'$'}(${'$'}arg:ident: ${'$'}argty:ty),* ${'$'}(,)*) -> ${'$'}ret:ty
                        ${'$'}body:block
                )+) => {${'$'}(
                    #[inline]
                    ${'$'}(#[${'$'}attr])*
                    pub ${'$'}(unsafe extern "C" fn ${'$'}i)? ${'$'}(const unsafe extern "C" fn ${'$'}const_i)?
                    (${'$'}(${'$'}arg: ${'$'}argty),*) -> ${'$'}ret
                        ${'$'}body
                )+};
            }
            """.trimIndent()

        val source =
            """
            s! {
                pub struct timeval {
                    pub tv_sec: i64,
                    #[cfg(target_arch = "x86_64")]
                    pub tv_usec: i64,
                    #[cfg(not(target_arch = "x86_64"))]
                    pub tv_usec: i32,
                }

                struct InternalPair {
                    a: u32,
                    b: u32,
                }
            }

            s_no_extra_traits! {
                pub union epoll_data {
                    pub u64_val: u64,
                    pub u32_val: u32,
                }
            }

            s_paren! {
                pub struct SceUid(pub i32);
            }

            cfg_if! {
                if #[cfg(target_env = "gnu")] {
                    pub const MODE_A: u32 = !0;
                    s! {
                        pub struct sched_param {
                            pub sched_priority: i32,
                        }
                    }
                } else {
                    pub const MODE_B: u32 = 1;
                    s! {
                        pub struct sched_param {
                            pub sched_priority: i32,
                        }
                    }
                }
            }

            c_enum! {
                pub enum clock_kind {
                    pub CLOCK_REALTIME = 0,
                    pub CLOCK_MONOTONIC,
                }

                #[repr(u32)]
                pub enum #anon {
                    pub TX_SETUP = 1,
                    pub TX_DELETE,
                }
            }

            f! {
                pub const fn mask_clear(flags: u32,) -> u32 {
                    flags & !MODE_B
                }
            }
            """.trimIndent()

        val header =
            FileHeaderContext(
                portLintSourcePath = "unix/sched.rs",
                packageName = "io.github.kotlinmania.libc.unix",
                crateMacroSources = listOf(libcMacrosRs),
            )
        val kt = renderSingleKotlinFile(source, header).text

        assertContains(kt, "public open class Timeval(public var tvSec: Long, public var tvUsec: Long)")
        assertEquals(1, Regex("\\bvar tvUsec:").findAll(kt).count())
        assertContains(kt, "public open class InternalPair(public var a: UInt, public var b: UInt)")
        assertContains(kt, "public open class EpollData(public var u64Val: ULong? = null, public var u32Val: UInt? = null)")
        assertContains(kt, "public open class SceUid(public var value: Int)")
        assertEquals(1, Regex("class SchedParam\\b").findAll(kt).count())
        assertContains(kt, "public val MODE_A: UInt = 0u.inv()")
        assertContains(kt, "public typealias ClockKind = CEnumRepr")
        assertContains(kt, "public val CLOCK_REALTIME: ClockKind = 0")
        assertContains(kt, "public val CLOCK_MONOTONIC: ClockKind = CLOCK_REALTIME + 1")
        assertContains(kt, "public val TX_SETUP: UInt = 1u")
        assertContains(kt, "public val TX_DELETE: UInt = TX_SETUP + 1u")
        assertFalse(kt.contains("run {"))
        assertContains(kt, "public fun maskClear(flags: UInt): UInt")
        assertContains(kt, "return flags and MODE_B.inv()")
    }

    @Test fun synMacrosAndAstEmissionFixesExpandAstStructEnumReturnImplTraitAndCleanKotlin() {
        val synMacrosRs =
            """
            macro_rules! ast_struct {
                (
                    ${'$'}attrs_pub:tt
                    struct ${'$'}name:ident #full ${'$'}(${'$'}rest:tt)*
                ) => {
                    #[cfg(feature = "full")]
                    ast_struct! {
                        ${'$'}attrs_pub
                        struct ${'$'}name ${'$'}(${'$'}rest)*
                    }

                    #[cfg(not(feature = "full"))]
                    ast_struct! {
                        ${'$'}attrs_pub
                        struct ${'$'}name {
                            _noconstruct: (),
                        }
                    }
                };
                (
                    [${'$'}(${'$'}attrs_pub:tt)*]
                    struct ${'$'}(${'$'}rest:tt)*
                ) => {
                    ${'$'}(${'$'}attrs_pub)* struct ${'$'}(${'$'}rest)*
                };
                (
                    ${'$'}t:tt
                    ${'$'}(${'$'}rest:tt)*
                ) => {
                    ast_struct!([${'$'}t] ${'$'}(${'$'}rest)*);
                };
                (
                    [${'$'}(${'$'}attrs_pub:tt)*]
                    ${'$'}t:tt
                    ${'$'}(${'$'}rest:tt)*
                ) => {
                    ast_struct!([${'$'}(${'$'}attrs_pub)* ${'$'}t] ${'$'}(${'$'}rest)*);
                };
            }

            macro_rules! return_impl_trait {
                (
                    ${'$'}(#[${'$'}attr:meta])*
                    ${'$'}vis:vis fn ${'$'}ident:ident ${'$'}args:tt -> ${'$'}impl_trait:ty [${'$'}concrete:ty] ${'$'}body:block
                ) => {
                    #[cfg(not(docsrs))]
                    ${'$'}(#[${'$'}attr])*
                    ${'$'}vis fn ${'$'}ident ${'$'}args -> ${'$'}concrete ${'$'}body

                    #[cfg(docsrs)]
                    ${'$'}(#[${'$'}attr])*
                    ${'$'}vis fn ${'$'}ident ${'$'}args -> ${'$'}impl_trait ${'$'}body
                };
            }
            """.trimIndent()

        val source =
            """
            /// Example node.
            ///
            /// ```edition2021
            /// # macro_rules! hidden_helper { () => {} }
            /// let x = 1;
            /// ```
            ast_struct! {
                pub struct Arm #full {
                    pub guard_flag: bool,
                }
            }

            impl Arm {
                pub fn to_string(&self) -> String {
                    String::new()
                }

                return_impl_trait! {
                    pub fn members(&self) -> impl Iterator<Item = u32> + Clone + '_ [Vec<u32>] {
                        Vec::new()
                    }
                }
            }

            pub fn advance_stream(mut step: usize, items: &[u32]) -> u8 {
                let any_zero = items.iter().any(|x| {
                    if *x == 0 {
                        return true;
                    }
                    false
                });
                if let Some(first) = items.iter().next() {
                    step += helper_inc(*first as usize);
                }
                while let Some(next_item) = items.iter().next() {
                    if *next_item > 10 {
                        break;
                    }
                    step += 1;
                }
                fn helper_inc(v: usize) -> usize {
                    v + 1
                }
                (step > 0 && !any_zero) as u8
            }
            """.trimIndent()

        val header =
            FileHeaderContext(
                portLintSourcePath = "expr.rs",
                packageName = "io.github.kotlinmania.syn",
                crateMacroSources = listOf(synMacrosRs),
            )
        val kt = renderSingleKotlinFile(source, header).text

        assertContains(kt, "```kotlin")
        assertFalse(kt.contains("hidden_helper"))
        assertFalse(kt.contains("hiddenHelper"))
        assertContains(kt, "public open class Arm(public var guardFlag: Boolean)")
        assertEquals(1, Regex("class Arm\\b").findAll(kt).count())
        assertFalse(kt.contains("noconstruct"))
        assertContains(kt, "public override fun toString(): String")
        assertEquals(1, Regex("fun members\\(").findAll(kt).count())
        assertContains(kt, "var step = step")
        val helperPos = kt.indexOf("fun helperInc(")
        val callPos = kt.indexOf("step += helperInc(")
        assertTrue(helperPos >= 0 && helperPos < callPos)
        assertContains(kt, "closure@{ x ->")
        assertContains(kt, "return@closure true")
        assertEquals(2, Regex("items\\.iter\\(\\)\\.next\\(\\)").findAll(kt).count())
        assertContains(kt, "(if (step > 0 && !anyZero) 1u else 0u).toUByte()")
    }

    @Test fun foreignExternCGeneratesExpectDeclarationsDefHeaderCSourceAndNativeActuals() {
        val rustSource =
            """
            //! Header: `stdlib.h`
            #[link(name = "m")]
            extern "C" {
                pub fn realloc(p: *mut c_void, size: usize) -> *mut c_void;
                pub fn strchr(cs: *const c_char, c: i32) -> *mut c_char;
                pub fn fclose(stream: *mut FILE) -> i32;
            }
            """.trimIndent()

        val files = Files(rustSource)
        files.run(
            "translate",
            "-n",
            "io.github.kotlinmania.libc",
            "tmp/libc/src/new/common/posix/stdlib.rs",
            "/proj/src/common/commonMain/kotlin/io/github/kotlinmania/libc/new/common/posix/Stdlib.kt",
        )

        val commonKt =
            files.writes.getValue(
                "/proj/src/common/commonMain/kotlin/io/github/kotlinmania/libc/new/common/posix/Stdlib.kt",
            )
        assertFalse(commonKt.contains("import io.github.kotlinmania.rustlang.core.ffi.CVoid"))
        assertContains(commonKt, "import io.github.kotlinmania.rustlang.core.ptr.ConstPtr")
        assertContains(commonKt, "import io.github.kotlinmania.rustlang.core.ptr.MutPtr")
        assertContains(commonKt, "public expect fun realloc(p: MutPtr, size: ULong): MutPtr")
        assertContains(commonKt, "public expect fun strchr(cs: ConstPtr, c: Int): MutPtr")
        assertContains(commonKt, "public expect fun fclose(stream: MutPtr): Int")

        val defText = files.writes.getValue("/proj/src/native/interop/cinterop/Stdlib.def")
        assertContains(defText, "package = io.github.kotlinmania.libc.new.common.posix.cinterop")
        assertContains(defText, "headers = stdlib_wrapper.h")
        assertContains(defText, "staticLibraries = stdlib_wrapper.a")
        assertContains(defText, "noStringConversion = libcStdlibStrchr")

        val hText = files.writes.getValue("/proj/src/native/interop/cinterop/stdlib_wrapper.h")
        assertContains(hText, "#include <stdint.h>")
        assertContains(hText, "void* libcStdlibRealloc(void* p, uint64_t size);")
        assertContains(hText, "char* libcStdlibStrchr(const char* cs, int32_t c);")
        assertContains(hText, "int32_t libcStdlibFclose(void* stream);")

        val cText = files.writes.getValue("/proj/src/native/interop/cinterop/stdlib_wrapper.c")
        assertContains(cText, "#include \"stdlib_wrapper.h\"")
        assertContains(cText, "#include <stdlib.h>")
        assertContains(cText, "#include <stdio.h>")
        assertContains(cText, "#include <string.h>")
        assertContains(cText, "return (void*)realloc(p, size);")
        assertContains(cText, "return (char*)strchr(cs, c);")
        assertContains(cText, "return (int32_t)fclose((FILE*)stream);")

        val nativeKt =
            files.writes.getValue(
                "/proj/src/native/nativeMain/kotlin/io/github/kotlinmania/libc/new/common/posix/StdlibNativeActuals.kt",
            )
        assertContains(nativeKt, "@file:OptIn(ExperimentalForeignApi::class")
        assertContains(nativeKt, "import kotlinx.cinterop.ExperimentalForeignApi")
        assertContains(nativeKt, "public actual fun realloc(p: MutPtr, size: ULong): MutPtr")
        assertContains(nativeKt, "public actual fun strchr(cs: ConstPtr, c: Int): MutPtr")
        assertContains(nativeKt, "MutPtr(libcStdlibRealloc(p.rawAddress.toCPointer<ByteVar>(), size)?.toLong() ?: 0L)")
        assertFalse(nativeKt.contains("toKString"))
        assertFalse(nativeKt.contains("COpaquePointer"))
    }

    @Test fun inlineCppMacroGeneratesDefHeaderCppSourceAndExpectActualBridge() {
        val rustSource =
            """
            cpp! {{
                #include <cmath>
                static int scale_factor() { return 3; }
            }}

            pub fn compute_scaled(input_val: i32) -> i32 {
                cpp!([input_val as "int32_t"] -> i32 as "int32_t" {
                    return input_val * scale_factor();
                })
            }
            """.trimIndent()

        val header =
            FileHeaderContext(
                portLintSourcePath = "inline_math.rs",
                packageName = "io.github.kotlinmania.sample",
                projectNamespace = "io.github.kotlinmania.sample",
            )
        val result = renderSingleKotlinFile(rustSource, header)

        assertContains(result.text, "public fun computeScaled(inputVal: Int): Int")
        assertContains(result.text, "return computeScaledCpp(inputVal)")
        assertContains(result.text, "internal expect fun computeScaledCpp(inputVal: Int): Int")

        assertEquals(11, result.companionFiles.size)
        val byName = result.companionFiles.associateBy { it.relativePath }
        assertTrue(byName.containsKey("InlineMath.def"))
        assertTrue(byName.containsKey("inline_math_wrapper.h"))
        val cppFile = requireNotNull(byName["inline_math_wrapper.cpp"])
        assertEquals(CompanionInteropKind.CppSource, cppFile.kind)
        assertContains(cppFile.content, "#include <cmath>")
        assertContains(cppFile.content, "extern \"C\" {")
        assertContains(cppFile.content, "return input_val * scale_factor();")
        val nativeActuals = requireNotNull(byName["InlineMathNativeActuals.kt"])
        assertContains(nativeActuals.content, "internal actual fun computeScaledCpp(inputVal: Int): Int")
    }

    @Test fun libcConstantNamingUnsignedAliasesSamCallbacksAndSealedDiscriminantEnums() {
        val rustSource =
            """
            pub type canid_t = u32;
            pub type sighandler_t = Option<extern "C" fn(i32)>;

            pub const SYS_exit: i32 = 60;
            pub const __O_LARGEFILE: u32 = 0x8000;
            pub const AV_386_AMD_3DNow: u32 = 1 << 31;
            pub const CAN_EFF_FLAG: canid_t = 0x80000000;
            pub const MASK_REST: canid_t = !__O_LARGEFILE;

            #[repr(C)]
            pub enum AudioSampleFrequency {
                Khz44_1 = 44100,
                Khz48,
            }

            pub struct HandlerConfig {
                pub stub: i32,
                pub on_tick: Option<extern "C" fn(i32) -> i32>,
            }

            pub fn next_syscall() -> i32 {
                SYS_exit + 1
            }
            """.trimIndent()

        val header =
            FileHeaderContext(
                portLintSourcePath = "linux/can.rs",
                packageName = "io.github.kotlinmania.libc.linux",
                projectNamespace = "io.github.kotlinmania.libc",
            )
        val kt = renderSingleKotlinFile(rustSource, header).text

        assertContains(kt, "public typealias CanidT = UInt")
        assertContains(kt, "public fun interface SighandlerTFn")
        assertContains(kt, "public typealias SighandlerT = SighandlerTFn?")
        assertContains(kt, "public val SYS_EXIT: Int = 60")
        assertContains(kt, "public val O_LARGEFILE: UInt = 0x8000u")
        assertContains(kt, "public val AV_386_AMD_3D_NOW: UInt = 1u shl 31")
        assertContains(kt, "public val CAN_EFF_FLAG: CanidT = 0x80000000u")
        assertContains(kt, "public val MASK_REST: CanidT = O_LARGEFILE.inv()")
        assertContains(kt, "public sealed class AudioSampleFrequency(public val value: Int)")
        assertContains(kt, "public data object Khz441 : AudioSampleFrequency(44100)")
        assertContains(kt, "public data object Khz48 : AudioSampleFrequency(Khz441.value + 1)")
        assertContains(kt, "public var skeletalImpl: Int")
        assertContains(kt, "public fun interface HandlerConfigOnTickCallback")
        assertContains(kt, "public var onTick: HandlerConfigOnTickCallback?")
        assertFalse(kt.contains("-> Int"))
        assertContains(kt, "return SYS_EXIT + 1")
    }

    @Test fun synMacroOnlyApproximatesWithConstantsAndConditionalsWhileDestructorSuppressionFailsExplicitly() {
        val macroOnlySource =
            """
            #[macro_export]
            macro_rules! custom_punctuation_len {
                (${'$'}mode:ident, &) => { 1 };
                (${'$'}mode:ident, &&) => { 2 };
                (lenient, ${'$'}tt:tt) => { 0 };
            }

            #[cfg(feature = "parsing")]
            #[macro_export]
            macro_rules! impl_parse_for_custom_keyword {
                (${'$'}ident:ident) => {
                    pub struct ${'$'}ident;
                };
            }

            #[cfg(not(feature = "parsing"))]
            #[macro_export]
            macro_rules! impl_parse_for_custom_keyword {
                (${'$'}ident:ident) => {};
            }

            #[macro_export]
            macro_rules! custom_punctuation_unexpected {
                () => {};
            }
            """.trimIndent()
        val macroKt = renderSingleKotlinFile(macroOnlySource).text
        assertContains(macroKt, "public const val CFG_FEATURE_PARSING: Boolean = true")
        assertContains(macroKt, "public fun customPunctuationLen(mode: String = \"\", tt: String = \"\"): Int =")
        assertContains(macroKt, "when {")
        assertContains(macroKt, "tt == \"&&\" -> 2")
        assertContains(macroKt, "mode == \"lenient\" -> 0")
        assertContains(macroKt, "if (CFG_FEATURE_PARSING)")
        assertContains(macroKt, "public const val CUSTOM_PUNCTUATION_UNEXPECTED: String = \"\"")

        val dropsSource =
            """
            use core::mem::{needs_drop, ManuallyDrop};

            pub struct NoDrop<T: ?Sized>(ManuallyDrop<T>);

            impl<T> NoDrop<T> {
                pub fn new(value: T) -> Self
                where
                    T: TrivialDrop,
                {
                    assert!(!needs_drop::<T>());
                    NoDrop(ManuallyDrop::new(value))
                }
            }

            pub trait TrivialDrop {}
            """.trimIndent()
        val dropsErr =
            assertFailsWith<IllegalArgumentException> {
                renderSingleKotlinFile(dropsSource)
            }
        assertContains(dropsErr.message.orEmpty(), "Unsupported Rust destructor-suppression semantics")
        assertContains(dropsErr.message.orEmpty(), "ManuallyDrop")
        assertContains(dropsErr.message.orEmpty(), "needs_drop")
    }

    @Test fun synLetOrPatternsExportAliasesThreadBoundSpansAndSpannedFidelity() {
        val letOrSource =
            """
            pub enum Expr {
                Break(Option<i32>),
                Return(Option<i32>),
                Yield(Option<i32>),
                Assign,
                Binary,
                Cast,
            }

            pub fn has_value(expr: &Expr) -> bool {
                let Expr::Break(val) | Expr::Return(val) | Expr::Yield(val) = expr else {
                    return false;
                };
                let Expr::Assign
                | Expr::Binary
                | Expr::Cast = expr else {
                    return val.is_some();
                };
                true
            }
            """.trimIndent()
        val letOrKt = renderSingleKotlinFile(letOrSource).text
        assertContains(letOrKt, "public fun hasValue(expr: Expr): Boolean")
        assertContains(letOrKt, "!= null")

        fun synHeader(sourcePath: String, flatModules: List<String> = listOf("span", "parse_quote")): FileHeaderContext =
            FileHeaderContext(
                portLintSourcePath = sourcePath,
                packageName = "io.github.kotlinmania.syn",
                projectNamespace = "io.github.kotlinmania.syn",
                projectGroup = "io.github.kotlinmania",
                crateFlatFileModules = flatModules,
            )

        val exportHeader = synHeader("export.rs")
        val exportSource =
            """
            pub use core::clone::Clone;
            pub use core::option::Option::{None, Some};
            pub type Formatter<'a> = core::fmt::Formatter<'a>;
            pub type FmtResult = core::fmt::Result;
            pub type bool = core::primitive::bool;
            pub type str = core::primitive::str;
            pub type Span = proc_macro2::Span;
            pub type TokenStream2 = proc_macro2::TokenStream;
            pub type TokenStream = proc_macro::TokenStream;
            pub struct private(pub(crate) ());
            """.trimIndent()
        val exportKt = renderSingleKotlinFile(exportSource, exportHeader).text
        assertContains(exportKt, "import io.github.kotlinmania.procmacro2.TokenStream")
        assertContains(exportKt, "public typealias Formatter = StringBuilder")
        assertContains(exportKt, "public typealias FmtResult = Unit")
        assertContains(exportKt, "public typealias TokenStream2 = TokenStream")
        assertContains(exportKt, "public open class Private(internal var value: Unit)")
        assertFalse(exportKt.contains("= Any"))
        assertFalse(exportKt.contains("typealias Boolean"))
        assertFalse(exportKt.contains("typealias String"))
        assertFalse(exportKt.contains("typealias Span "))
        assertFalse(exportKt.contains("typealias TokenStream "))

        val threadHeader = synHeader("thread.rs")
        val threadSource =
            """
            use core::fmt::{self, Debug};
            use std::thread::{self, ThreadId};

            pub(crate) struct ThreadBound<T> {
                value: T,
                thread_id: ThreadId,
            }

            // Send bound requires Copy, as otherwise Drop could run in the wrong place.
            unsafe impl<T: Copy> Send for ThreadBound<T> {}

            impl<T> ThreadBound<T> {
                pub(crate) fn new(value: T) -> Self {
                    ThreadBound {
                        value,
                        thread_id: thread::current().id(),
                    }
                }

                pub(crate) fn get(&self) -> Option<&T> {
                    if thread::current().id() == self.thread_id {
                        Some(&self.value)
                    } else {
                        None
                    }
                }
            }

            // Copy the bytes of T, even if the currently running thread is the "wrong" thread.
            impl<T: Copy> Copy for ThreadBound<T> {}

            impl<T: Copy> Clone for ThreadBound<T> {
                fn clone(&self) -> Self {
                    *self
                }
            }
            """.trimIndent()
        val threadKt = renderSingleKotlinFile(threadSource, threadHeader).text
        assertContains(threadKt, "internal expect fun currentThreadBoundToken(): Any")
        assertContains(threadKt, "internal fun <T> new(value: T): ThreadBound<T>")
        assertContains(threadKt, "public open fun clone(): ThreadBound<T>")
        assertContains(threadKt, "return ThreadBound(this.value, this.threadId)")
        assertContains(threadKt, "// Send bound requires Copy")
        assertContains(threadKt, "// Copy the bytes of T")
        assertFalse(threadKt.contains("import core."))
        assertFalse(threadKt.contains("import std."))

        val spanHeader = synHeader("span.rs")
        val spanSource =
            """
            use proc_macro2::extra::DelimSpan;
            use proc_macro2::Span;

            pub trait IntoSpans<S> {
                fn into_spans(self) -> S;
            }

            impl IntoSpans<Span> for Span {
                fn into_spans(self) -> Span { self }
            }
            impl IntoSpans<[Span; 1]> for Span {
                fn into_spans(self) -> [Span; 1] { [self] }
            }
            impl IntoSpans<[Span; 2]> for Span {
                fn into_spans(self) -> [Span; 2] { [self, self] }
            }
            impl IntoSpans<[Span; 3]> for Span {
                fn into_spans(self) -> [Span; 3] { [self, self, self] }
            }
            impl IntoSpans<[Span; 1]> for [Span; 1] {
                fn into_spans(self) -> [Span; 1] { self }
            }
            impl IntoSpans<[Span; 2]> for [Span; 2] {
                fn into_spans(self) -> [Span; 2] { self }
            }
            impl IntoSpans<[Span; 3]> for [Span; 3] {
                fn into_spans(self) -> [Span; 3] { self }
            }
            impl IntoSpans<DelimSpan> for Span {
                fn into_spans(self) -> DelimSpan { unreachable!() }
            }
            impl IntoSpans<DelimSpan> for DelimSpan {
                fn into_spans(self) -> DelimSpan { self }
            }
            """.trimIndent()
        val spanKt = renderSingleKotlinFile(spanSource, spanHeader).text
        assertEquals(9, Regex("public fun (Span|List<Span>|DelimSpan)\\.intoSpans").findAll(spanKt).count())
        assertContains(spanKt, "import io.github.kotlinmania.procmacro2.DelimSpan")

        val spannedHeader = synHeader("spanned.rs")
        val spannedSource =
            """
            use proc_macro2::Span;
            use quote::spanned::Spanned as ToTokens;

            /// A trait that can provide the `Span` of the complete contents of a syntax node.
            ///
            /// ```
            /// #[proc_macro_attribute]
            /// pub fn my_attr(args: TokenStream, input: TokenStream) -> TokenStream {
            ///     /* ... */
            /// }
            /// ```
            pub trait Spanned: private::Sealed {
                fn span(&self) -> Span;
            }

            impl<T: ?Sized + ToTokens> Spanned for T {
                fn span(&self) -> Span {
                    self.__span()
                }
            }
            """.trimIndent()
        val spannedKt = renderSingleKotlinFile(spannedSource, spannedHeader).text
        assertContains(spannedKt, "import io.github.kotlinmania.quote.span as quoteSpan")
        assertContains(spannedKt, "return this.quoteSpan()")
        assertFalse(spannedKt.contains("return this.span()"))
        assertFalse(spannedKt.contains("__span"))
        assertContains(spannedKt, "@ProcMacroAttribute")
        assertFalse(spannedKt.contains("/* ... */"))
    }

    @Test fun synParseParseQuotePunctuatedAndMetaGenericAndWitnessFidelity() {
        val header =
            FileHeaderContext(
                portLintSourcePath = "parse_quote.rs",
                packageName = "io.github.kotlinmania.syn",
                projectNamespace = "io.github.kotlinmania.syn",
                projectGroup = "io.github.kotlinmania",
                crateFlatFileModules = listOf("parse", "error", "punctuated", "token"),
            )
        val parseAndQuoteSource =
            """
            use crate::error::Result;
            use proc_macro2::TokenStream;

            pub struct ParseBuffer<'a> {
                marker: core::marker::PhantomData<&'a ()>,
            }
            pub type ParseStream<'a> = &'a ParseBuffer<'a>;

            pub trait Parse: Sized {
                fn parse(input: ParseStream) -> Result<Self>;
            }

            pub trait Parser: Sized {
                type Output;
                fn parse2(self, tokens: TokenStream) -> Result<Self::Output>;
            }

            impl<F, T> Parser for F
            where
                F: FnOnce(ParseStream) -> Result<T>,
            {
                type Output = T;
                fn parse2(self, _tokens: TokenStream) -> Result<T> {
                    unreachable!()
                }
            }

            pub trait ParseQuote: Sized {
                fn parse(input: ParseStream) -> Result<Self>;
            }

            impl<T: Parse> ParseQuote for T {
                fn parse(input: ParseStream) -> Result<Self> {
                    <T as Parse>::parse(input)
                }
            }

            pub fn parse_item<T: ParseQuote>(tokens: TokenStream) -> T {
                let parser = T::parse;
                let _ = parser;
                let _ = tokens;
                unreachable!()
            }
            """.trimIndent()
        val pqKt = renderSingleKotlinFile(parseAndQuoteSource, header).text
        assertContains(pqKt, "public interface Parse<T>")
        assertContains(pqKt, "public fun parse(input: ParseStream): Result<T>")
        assertContains(pqKt, "public interface Parser<Output>")
        assertContains(pqKt, "public fun <T> ((ParseStream) -> Result<T>).parse2(")
        assertContains(pqKt, "public interface ParseQuote<T>")
        assertContains(pqKt, "parseTFn: (ParseStream) -> Result<T>")
        assertFalse(pqKt.contains("<T as"))
        assertFalse(pqKt.contains("T::parse"))

        val punctHeader = header.copy(portLintSourcePath = "punctuated.rs")
        val punctSource =
            """
            pub struct Punctuated<T, P> {
                inner: Vec<(T, P)>,
                last: Option<Box<T>>,
            }

            pub enum Pair<T, P> {
                Punctuated(T, P),
                End(T),
            }

            impl<T, P> Punctuated<T, P> {
                pub const fn new() -> Self {
                    Punctuated {
                        inner: Vec::new(),
                        last: None,
                    }
                }

                pub fn push_value(&mut self, value: T) {
                    self.last = Some(Box::new(value));
                }

                pub fn push_punct(&mut self, punctuation: P) {
                    let last = self.last.take().unwrap();
                    self.inner.push((*last, punctuation));
                }

                pub fn push(&mut self, value: T)
                where
                    P: Default,
                {
                    if self.last.is_some() {
                        self.push_punct(Default::default());
                    }
                    self.push_value(value);
                }
            }

            impl<T, P> FromIterator<T> for Punctuated<T, P>
            where
                P: Default,
            {
                fn from_iter<I: IntoIterator<Item = T>>(i: I) -> Self {
                    let mut ret = Punctuated::new();
                    ret.extend(i);
                    ret
                }
            }

            impl<T, P> FromIterator<Pair<T, P>> for Punctuated<T, P> {
                fn from_iter<I: IntoIterator<Item = Pair<T, P>>>(_i: I) -> Self {
                    Punctuated::new()
                }
            }
            """.trimIndent()
        val punctKt = renderSingleKotlinFile(punctSource, punctHeader).text
        assertContains(punctKt, "public open class Punctuated<T, P>(public var inner: MutableList<kotlin.Pair<T, P>>, public var last: T?)")
        assertContains(punctKt, "kotlin.Pair(last, punctuation)")
        assertContains(punctKt, "defaultPFn: () -> P")
        assertContains(punctKt, "public fun <T, P, I : Iterable<T>> fromIter(")
        assertContains(punctKt, "public fun <T, P, I : Iterable<Pair<T, P>>> fromIterPairTP(")
    }

    @Test fun synContainerTraitDispatchIteratorsDeferredMatchAndDocExamples() {
        val header =
            FileHeaderContext(
                portLintSourcePath = "punctuated.rs",
                packageName = "io.github.kotlinmania.syn",
                projectNamespace = "io.github.kotlinmania.syn",
                projectGroup = "io.github.kotlinmania",
                crateFlatFileModules = listOf("parse", "error", "punctuated", "attr"),
            )

        val punctRichSource =
            """
            use core::ops::{Index, IndexMut};
            use core::slice;
            use std::option;
            use std::vec;

            pub struct Punctuated<T, P> {
                inner: Vec<(T, P)>,
                last: Option<Box<T>>,
            }

            pub enum Pair<T, P> {
                Punctuated(T, P),
                End(T),
            }

            impl<T, P> Pair<T, P> {
                pub fn into_value(self) -> T {
                    match self {
                        Pair::Punctuated(t, _) | Pair::End(t) => t,
                    }
                }
            }

            pub struct Iter<'a, T: 'a> {
                inner: slice::Iter<'a, T>,
            }

            trait IterMutTrait<'a, T: 'a>: Iterator<Item = &'a mut T> + DoubleEndedIterator + ExactSizeIterator {}

            pub struct IterMut<'a, T: 'a> {
                inner: Box<dyn IterMutTrait<'a, T, Item = &'a mut T> + 'a>,
            }

            pub struct IntoIter<T> {
                inner: vec::IntoIter<T>,
            }

            impl<T, P> Punctuated<T, P> {
                pub fn first(&self) -> Option<&T> {
                    self.inner
                        .first()
                        .map(|pair| &pair.0)
                        .or_else(|| self.last.as_deref())
                }

                pub fn pop_punct(&mut self) -> Option<P> {
                    if self.last.is_some() {
                        return None;
                    }
                    let (t, p) = self.inner.pop()?;
                    self.last = Some(Box::new(t));
                    Some(p)
                }

                pub fn into_tokens(self) -> usize {
                    let Punctuated { inner, last } = self;
                    if last.is_some() {
                        inner.len() + 1
                    } else {
                        inner.len()
                    }
                }
            }

            impl<T, P> Index<usize> for Punctuated<T, P> {
                type Output = T;

                fn index(&self, index: usize) -> &Self::Output {
                    if index == self.len() - 1 {
                        match &self.last {
                            Some(t) => t,
                            None => &self.inner[index].0,
                        }
                    } else {
                        &self.inner[index].0
                    }
                }
            }

            impl<T, P> IndexMut<usize> for Punctuated<T, P> {
                fn index_mut(&mut self, index: usize) -> &mut Self::Output {
                    if index == self.len() - 1 {
                        match &mut self.last {
                            Some(t) => t,
                            None => &mut self.inner[index].0,
                        }
                    } else {
                        &mut self.inner[index].0
                    }
                }
            }

            impl<T> Iterator for IntoIter<T> {
                type Item = T;

                fn next(&mut self) -> Option<Self::Item> {
                    self.inner.next()
                }
            }

            impl<T> DoubleEndedIterator for IntoIter<T> {
                fn next_back(&mut self) -> Option<Self::Item> {
                    self.inner.next_back()
                }
            }
            """.trimIndent()
        val punctRichKt = renderSingleKotlinFile(punctRichSource, header).text
        assertContains(punctRichKt, "public open class Iter<T>(public var inner: ArrayDeque<T>)")
        assertContains(punctRichKt, "public open class IterMut<T>(public var inner: IterMutTrait<T>)")
        assertContains(punctRichKt, "public open class IntoIter<T>(public var inner: ArrayDeque<T>)")
        assertContains(punctRichKt, "public open fun index(index: Int): T")
        assertContains(punctRichKt, "public open fun indexMut(index: Int): T")
        assertContains(punctRichKt, "public open fun nextBack(): T?")
        assertContains(punctRichKt, "return this.inner.removeFirstOrNull()")
        assertContains(punctRichKt, "return this.inner.removeLastOrNull()")
        assertContains(punctRichKt, "val inner = this.inner")
        assertContains(punctRichKt, "val last = this.last")
        assertContains(punctRichKt, "(this.inner.removeLastOrNull() ?: return null)")
        assertFalse(punctRichKt.contains("is Any ->"))

        val metaAndParseSource =
            """
            use crate::error::{Error, Result};

            pub struct Nothing;

            pub enum Unexpected {
                None,
                Some(i32),
            }

            /// Parse nested meta attributes.
            ///
            /// ```
            /// #[tea(kind = "EarlGrey", hot)]
            /// struct Picard;
            /// ```
            pub fn check_unexpected(u: Unexpected, step: Result<i32>) -> Result<()> {
                let content;
                match step {
                    Ok(v) => {
                        content = v;
                    }
                    Err(err) => return Err(err),
                }
                let _ = content;
                match u {
                    Unexpected::None => Ok(()),
                    Unexpected::Some(_) => Ok(()),
                }
            }

            pub fn diverge() -> ! {
                panic!("stop")
            }
            """.trimIndent()
        val metaParseKt = renderSingleKotlinFile(metaAndParseSource, header.copy(portLintSourcePath = "meta.rs")).text
        assertContains(metaParseKt, "@tea(kind = \"EarlGrey\", hot)")
        assertContains(metaParseKt, "public fun diverge(): kotlin.Nothing")
        assertContains(metaParseKt, "is Unexpected.Some -> Result.success(Unit)")
        assertContains(metaParseKt, ".getOrElse {")
        assertFalse(metaParseKt.contains("var content: Any?"))

        val parseQuoteAndIterSource =
            """
            use crate::error::Result;
            use crate::parse::ParseStream;
            use crate::pat::Pat;
            use core::hash::{Hash, Hasher};

            pub trait Parse: Sized {
                fn parse(input: ParseStream) -> Result<Self>;
            }

            pub trait ParseQuote: Sized {
                fn parse(input: ParseStream) -> Result<Self>;
            }

            pub struct Visibility;

            impl Parse for Visibility {
                fn parse(_input: ParseStream) -> Result<Self> {
                    Ok(Visibility)
                }
            }

            impl ParseQuote for Pat {
                fn parse(_input: ParseStream) -> Result<Self> {
                    Ok(Pat)
                }
            }

            pub fn parse_vis_and_pat(input: ParseStream) -> Result<Pat> {
                let _vis: Visibility = input.parse()?;
                <Pat as ParseQuote>::parse(input)
            }

            pub struct Counter {
                pub count: usize,
            }

            impl Hash for Counter {
                fn hash<H: Hasher>(&self, state: &mut H) {
                    self.count.hash(state);
                }
            }

            pub fn checked_next(a: usize, b: usize) -> Option<usize> {
                a.checked_add(b)
            }
            """.trimIndent()
        val pqKt = renderSingleKotlinFile(parseQuoteAndIterSource, header.copy(portLintSourcePath = "parse_quote.rs")).text
        assertContains(pqKt, "input.parse(Visibility::parse)")
        assertContains(pqKt, "parseQuoteParsePat(input)")
        assertContains(pqKt, "override fun hashCode(): Int")
        assertFalse(pqKt.contains("core.hash"))
        assertContains(pqKt, "checkedNext(a: Int, b: Int): Int?")

        val privateReexportMacroSource =
            """
            macro_rules! parenthesized {
                (${'$'}content:ident in ${'$'}cursor:expr) => {
                    match ${'$'}crate::__private::parse_parens(&${'$'}cursor) {
                        ${'$'}crate::__private::Ok(parens) => {
                            ${'$'}content = parens.content;
                            _ = ${'$'}content;
                            parens.token
                        }
                        ${'$'}crate::__private::Err(error) => {
                            return ${'$'}crate::__private::Err(error);
                        }
                    }
                };
            }

            pub fn nested(input: ParseStream) -> Result<Int> {
                let content;
                parenthesized!(content in input);
                content.call()
            }
            """.trimIndent()
        val macroExpandedMetaKt =
            renderSingleKotlinFile(
                privateReexportMacroSource,
                header.copy(portLintSourcePath = "meta.rs"),
            ).text
        assertContains(macroExpandedMetaKt, "val content = parens.content")
        assertFalse(macroExpandedMetaKt.contains("content: Any?"))
        assertFalse(macroExpandedMetaKt.contains("is Ok ->"))
    }

    @Test
    fun preservesCommentsInsideMacroInvocationsAndLowercaseTypeImports() {
        val macrosRs =
            """
            macro_rules! s {
                (${'$'}(
                    ${'$'}(#[${'$'}attr:meta])*
                    ${'$'}pub:vis ${'$'}t:ident ${'$'}i:ident { ${'$'}(${'$'}field:tt)* }
                )*) => (${'$'}(
                    s!(it: ${'$'}(#[${'$'}attr])* ${'$'}pub ${'$'}t ${'$'}i { ${'$'}(${'$'}field)* });
                )*);
                (it: ${'$'}(#[${'$'}attr:meta])* ${'$'}pub:vis struct ${'$'}i:ident { ${'$'}(${'$'}field:tt)* }) => (
                    #[repr(C)]
                    #[::core::prelude::v1::derive(::core::clone::Clone, ::core::marker::Copy, ::core::fmt::Debug)]
                    #[allow(deprecated)]
                    ${'$'}(#[${'$'}attr])*
                    ${'$'}pub struct ${'$'}i { ${'$'}(${'$'}field)* }
                );
            }
            """.trimIndent()
        val riscv64Rs =
            """
            use crate::off_t;
            use crate::prelude::*;
            use libc::{c_void, SOL_SOCKET, _SC_PAGESIZE, termios as Termios};

            // From psABI Calling Convention for RV64
            pub type stat64 = stat;
            s! {
                pub struct stat {
                    pub st_size: off_t,
                    // union
                    pub st_blocks: crate::blkcnt_t,
                }

                // Not actually used, IPC calls just return ENOSYS
                pub struct ipc_perm {
                    pub __ipc_perm_key: crate::key_t,
                    pub __seq: c_ushort,
                }
            }
            """.trimIndent()
        val header =
            FileHeaderContext(
                portLintSourcePath = "fuchsia/riscv64.rs",
                packageName = "io.github.kotlinmania.libc.fuchsia",
                projectGroup = "io.github.kotlinmania",
                projectNamespace = "io.github.kotlinmania.libc",
                crateMacroSources = listOf(macrosRs),
                crateFlatFileModules = listOf("prelude", "primitives", "types"),
            )
        val kt = renderSingleKotlinFile(riscv64Rs, header).text
        assertContains(kt, "import io.github.kotlinmania.libc.*")
        assertContains(kt, "import io.github.kotlinmania.libc.OffT")
        assertContains(kt, "import io.github.kotlinmania.libc.SOL_SOCKET")
        assertContains(kt, "import io.github.kotlinmania.libc.SC_PAGESIZE")
        assertContains(kt, "import io.github.kotlinmania.libc.Termios")
        assertFalse(kt.contains("offT"))
        assertFalse(kt.contains("cVoid"))
        assertFalse(kt.contains("libc.prelude"))
        assertContains(kt, "// From psABI Calling Convention for RV64\npublic typealias Stat64 = Stat")
        assertContains(kt, "    // union\n    public var stBlocks: BlkcntT,")
        assertContains(kt, "// Not actually used, IPC calls just return ENOSYS\npublic open class IpcPerm(")
    }

    @Test fun ffiPointerWidthTypedefsRawBytePointersAndInlineOperatorSamInterfaces() {
        val rustSource =
            """
            pub type size_t = usize;
            pub type uintptr_t = usize;
            pub type ssize_t = isize;
            pub type ptrdiff_t = isize;
            pub type intptr_t = isize;

            pub struct ThreadContext {
                pub id: i32,
            }

            // Thread entry callback typedef
            pub type ThreadEntry = Option<extern "C" fn(argc: size_t, argp: *mut u8) -> i32>;
            """.trimIndent()
        val header =
            FileHeaderContext(
                portLintSourcePath = "psp.rs",
                packageName = "io.github.kotlinmania.libc.psp",
                projectNamespace = "io.github.kotlinmania.libc",
            )
        val kt = renderSingleKotlinFile(rustSource, header).text

        assertContains(kt, "import io.github.kotlinmania.rustlang.core.ptr.MutPtr")
        assertContains(kt, "public typealias SizeT = ULong")
        assertContains(kt, "public typealias UintptrT = ULong")
        assertContains(kt, "public typealias SsizeT = Long")
        assertContains(kt, "public typealias PtrdiffT = Long")
        assertContains(kt, "public typealias IntptrT = Long")
        assertContains(
            kt,
            "// Thread entry callback typedef\n" +
                "public fun interface ThreadEntryFn {\n" +
                "    public operator fun invoke(argc: SizeT, argp: MutPtr): Int\n" +
                "}\n\n" +
                "public typealias ThreadEntry = ThreadEntryFn?",
        )
        assertTrue(kt.indexOf("class ThreadContext") < kt.indexOf("fun interface ThreadEntryFn"))
    }

    @Test fun tokenDrivenSynTokenMacrosLiteralUnescapeAndComplementaryCfgStatements() {
        val rustSource =
            """
            pub struct TypeBinding {
                pub kw: Token![type],
                pub sep: Token![..=],
            }

            pub fn make_binding(sp: Span, x: i32) -> String {
                let _kw = Token![struct](sp);
                let _def = Token![>>=];
                #[cfg(wrap_proc_macro)]
                let label = format!(r#"raw\n:{x}"#);
                #[cfg(not(wrap_proc_macro))]
                let label = format!("fallback:{x}");
                format!("\x41:{label}")
            }
            """.trimIndent()
        val kt = renderSingleKotlinFile(rustSource).text

        assertContains(kt, "public var kw: SynTypeToken")
        assertContains(kt, "public var sep: DotDotEq")
        assertContains(kt, "val kw = Struct.from(sp)")
        assertContains(kt, "val def = ShrEq.default()")
        assertContains(kt, "val label = \"raw\\\\n:${'$'}x\"")
        assertFalse(kt.contains("label1"))
        assertContains(kt, "return \"A:${'$'}label\"")
    }

    @Test fun supplementaryCharLiteralsLosslessScalarsAndScopedFnNumericArgs() {
        val rustSource =
            """
            // Preserve '🦀' in line comments and /* nested /* '🚀' */ block comments */
            pub fn inspect_literals() {
                fn check_byte(literal_text: &str, expected_byte: u8) {
                    let _ = (literal_text, expected_byte);
                }
                let quote_and_crab = ('\'', '🦀', '\u{1F63b}');
                let repeated = ['🚀'; 4];
                let raw_note = r#"keep '🦀' inside raw string"#;
                let cooked_note = "keep '😻' inside cooked string";
                // skip_ws(&mut input.chars(), &mut cb)
                check_byte(r"\x5a", b'Z');
                check_byte(r"\x7f", 127);
                check_byte(r"\xff", 255);
                let _ = (quote_and_crab, repeated, raw_note, cooked_note);
            }
            """.trimIndent()
        val kt = renderSingleKotlinFile(rustSource).text

        assertContains(kt, "// Preserve '🦀' in line comments")
        assertContains(kt, "Triple('\\'', 0x1f980, 0x1f63b)")
        assertContains(kt, "List(4) { 0x1f680 }")
        assertContains(kt, "\"keep '🦀' inside raw string\"")
        assertContains(kt, "\"keep '😻' inside cooked string\"")
        assertFalse(kt.contains("\\uf980"))
        assertFalse(kt.contains("\\uf63b"))
        assertFalse(kt.contains("\\uf680"))
        assertFalse(kt.contains("&mut"))
        assertContains(kt, "checkByte(\"\\\\x5a\", 90u)")
        assertContains(kt, "checkByte(\"\\\\x7f\", 127u)")
        assertContains(kt, "checkByte(\"\\\\xff\", 255u)")
    }

    @Test fun builtinMacrosDebugBuildersComplementaryMatchArmsAndExternUsizeAbi() {
        val macroDefs =
            """
            macro_rules! c_union_no_extra {
                (pub union ${'$'}name:ident { $(${'$'}f:ident: ${'$'}t:ty,)* }) => {
                    pub union ${'$'}name { $(pub ${'$'}f: ${'$'}t,)* }
                    impl ::core::fmt::Debug for ${'$'}name {
                        fn fmt(&self, f: &mut ::core::fmt::Formatter) -> ::core::fmt::Result {
                            f.debug_struct(::core::stringify!(${'$'}name)).finish_non_exhaustive()
                        }
                    }
                };
            }
            """.trimIndent()
        val rustSource =
            """
            use core::fmt;
            use proc_macro::Span as CompilerSpan;

            pub type HttpMallocFunction = Option<extern "C" fn(usize) -> *mut c_void>;

            pub trait EscaperOut {
                type Unit;
            }
            impl EscaperOut for char {
                type Unit = char;
            }

            c_union_no_extra! {
                pub union RawColor {
                    rgba: u32,
                }
            }

            pub enum BackendSpan {
                Compiler(String),
                Fallback(String),
            }

            pub fn format_suffixed(n: u8) -> String {
                format!(concat!("{}", stringify!(u8)), n)
            }

            pub fn check_span(span: &BackendSpan, ch: char) -> (String, bool, u32, &'static str) {
                let text = match span {
                    #[cfg(not(no_source_text))]
                    BackendSpan::Compiler(s) => s.clone(),
                    #[cfg(no_source_text)]
                    BackendSpan::Compiler(_) => String::new(),
                    BackendSpan::Fallback(s) => s.clone(),
                };
                let matched = matches!(ch, 'a' | 'z' if ch.is_ascii());
                (text, matched, line!(), file!())
            }

            pub fn append_debug(debug: &mut fmt::DebugStruct, f: &mut fmt::Formatter, label: &str) {
                debug.field("label", &label);
                f.debug_tuple("Tag").field(&label).finish();
            }
            """.trimIndent()

        val header =
            FileHeaderContext(
                portLintSourcePath = "wrapper.rs",
                packageName = "io.github.kotlinmania.procmacro2",
                projectGroup = "io.github.kotlinmania",
                projectNamespace = "io.github.kotlinmania.procmacro2",
                crateMacroSources = listOf(macroDefs),
            )
        val kt = renderSingleKotlinFile(rustSource, header).text

        assertContains(kt, "import io.github.kotlinmania.procmacro.Span as CompilerSpan")
        assertContains(kt, "public fun interface HttpMallocFunctionFn {\n    public operator fun invoke(arg0: ULong): MutPtr\n}")
        assertFalse(kt.contains("typealias Unit ="))
        assertContains(kt, "public open class RawColor(public var rgba: UInt? = null)")
        assertFalse(kt.contains("RawColor).finishNonExhaustive"))
        assertContains(kt, "return \"${'$'}{n}u8\"")
        assertEquals(1, Regex("is BackendSpan\\.Compiler ->").findAll(kt).count())
        assertContains(kt, "val matched = when {")
        assertContains(kt, "line!()".let { "u, \"wrapper.rs\"" })
        assertContains(kt, "public class DebugStruct(")
        assertContains(kt, "public class DebugTuple(")
        assertContains(kt, "public fun appendDebug(debug: DebugStruct, f: StringBuilder, label: String)")

        val manualDropFnSource =
            """
            use core::mem::ManuallyDrop;
            use core::ptr;
            pub struct Holder { inner: String }
            impl Holder {
                pub fn take_inner(self) -> String {
                    let nodrop = ManuallyDrop::new(self);
                    unsafe { ptr::read(&nodrop.inner) }
                }
            }
            """.trimIndent()
        val dropErr =
            assertFailsWith<IllegalArgumentException> {
                renderSingleKotlinFile(manualDropFnSource, header)
            }
        assertContains(dropErr.message.orEmpty(), "ManuallyDrop")
    }

    @Test fun kdocExamplesAreAstTransliteratedThroughSynAndTokensWithoutRustResidue() {
        val rustSource =
            """
            /// Parse macro body using [`Attribute::parse_nested_meta`] and `syn::parse2::<T>(mac.tokens)`.
            /// Supports [`Visitor::visit_seq`
            /// ] and [`first_item`, `second_item`] and stray ` backticks.
            ///
            /// [`Attribute::parse_nested_meta`]: crate::Attribute::parse_nested_meta
            ///
            /// # Example
            ///
            /// ```
            /// use syn::{parse_quote, Expr, Ident, Macro, Token};
            /// use syn::parse::{Parse, ParseStream, Result};
            ///
            /// struct FormatArgs {
            ///     format_string: Expr,
            ///     positional_args: Vec<Expr>,
            ///     named_args: Vec<(Ident, Expr)>,
            /// }
            ///
            /// impl Parse for FormatArgs {
            ///     fn parse(input: ParseStream) -> Result<Self> {
            ///         let format_string: Expr = input.parse()?;
            ///         let mut positional_args = Vec::new();
            ///         let mut named_args = Vec::new();
            ///         while !input.is_empty() {
            ///             input.parse::<Token![,]>()?;
            ///             positional_args.push(input.parse()?);
            ///         }
            ///         Ok(FormatArgs {
            ///             format_string,
            ///             positional_args,
            ///             named_args,
            ///         })
            ///     }
            /// }
            ///
            /// fn main() {
            ///     let invocation: Macro = parse_quote! {
            ///         println!("{:?}", Instant::now())
            ///     };
            ///     let _ = invocation;
            /// }
            /// ```
            ///
            /// # Statement Example
            ///
            /// ```
            /// use syn::{parse_quote, Attribute, LitStr};
            ///
            /// let attr: Attribute = parse_quote! {
            ///     #[tea(kind = "EarlGrey")]
            /// };
            /// if attr.path().is_ident("tea") {
            ///     attr.parse_nested_meta(|meta| {
            ///         if meta.path.is_ident("kind") {
            ///             let value = meta.value()?;
            ///             let _s: LitStr = value.parse()?;
            ///             Ok(())
            ///         } else {
            ///             Err(meta.error("unsupported"))
            ///         }
            ///     })?;
            /// }
            /// # anyhow::Ok(())
            /// ```
            pub fn parse_body_demo(tokens: i32) -> i32 {
                tokens
            }
            """.trimIndent()

        val kt = renderSingleKotlinFile(rustSource).text
        assertContains(kt, "[Attribute.parseNestedMeta]")
        assertContains(kt, "[`firstItem`, `secondItem`]")
        assertContains(kt, "`syn.parse2<T>(mac.tokens)`")
        assertFalse(kt.contains("crate::Attribute"))
        assertContains(kt, "open class FormatArgs(")
        assertContains(kt, "public var positionalArgs: List<Expr>")
        assertContains(kt, ".getOrElse { return Result.failure(it) }")
        assertContains(kt, "parseQuote(\"@tea(kind = \\\"EarlGrey\\\")\")")
        assertFalse(kt.contains("input.parse()?"))
        assertFalse(kt.contains("Token!["))
        assertFalse(kt.contains("anyhow"))
    }

    @Test fun splitModuleRootsSplitsImplementationModAndSkipsPureContainersWithoutPortLintOrModKt() {
        val files =
            mapOf(
                "tmp/windows-sys/src/lib.rs" to
                    """
                    pub mod Win32;
                    """.trimIndent(),
                "tmp/windows-sys/src/Win32/mod.rs" to
                    """
                    pub mod Foundation;
                    pub mod System;
                    """.trimIndent(),
                "tmp/windows-sys/src/Win32/Foundation/mod.rs" to
                    """
                    pub type BOOL = i32;
                    pub type HANDLE = *mut core::ffi::c_void;
                    pub const INVALID_HANDLE_VALUE: HANDLE = -1isize as _;
                    #[repr(C)]
                    pub struct FILETIME {
                        pub dwLowDateTime: u32,
                        pub dwHighDateTime: u32,
                    }
                    windows_link::link!("kernel32.dll" "system" fn CloseHandle(hobject : HANDLE) -> BOOL);
                    """.trimIndent(),
                "tmp/windows-sys/src/Win32/System/Console.rs" to
                    """
                    pub const STD_OUTPUT_HANDLE: u32 = 4294967285u32;
                    windows_link::link!("kernel32.dll" "system" fn GetStdHandle(nstdhandle : u32) -> super::Foundation::HANDLE);
                    """.trimIndent(),
            )
        val written = linkedMapOf<String, String>()
        runSingleFileCommand(
            args =
                arrayOf(
                    "-r",
                    "--force",
                    "--raw-abi",
                    "--split-module-roots",
                    "-n",
                    "io.github.kotlinmania.windowssys",
                    "-o",
                    "proj/src/common/commonMain/kotlin/io/github/kotlinmania/windowssys",
                    "tmp/windows-sys/src",
                ),
            read = { path -> files[path] ?: error("missing $path") },
            writeForce = { path, text -> written[path] = text },
            exists = { false },
            isDirectory = { it == "tmp/windows-sys/src" },
            collectRustFiles = { files.keys.toList() },
            report = {},
            resolveGradleProperties = { null },
            resolvePackageProperties = { null },
            resolveCrateMacroSources = { emptyList() },
            resolveCrateFlatFileModules = { _, _ -> emptySet() },
            resolveCrateRawAbiMetadata = { _, _, _, _ -> CrateRawAbiMetadata() },
        )

        assertFalse(written.keys.any { it.endsWith("/Lib.kt") || it.endsWith("/Mod.kt") })
        assertFalse(written.keys.any { it.contains("/win32/Mod") })

        val foundationTypes = written["proj/src/common/commonMain/kotlin/io/github/kotlinmania/windowssys/win32/foundation/FoundationTypes.kt"]
        val foundationStructs = written["proj/src/common/commonMain/kotlin/io/github/kotlinmania/windowssys/win32/foundation/FoundationStructs.kt"]
        val foundationConsts = written["proj/src/common/commonMain/kotlin/io/github/kotlinmania/windowssys/win32/foundation/FoundationConstants.kt"]
        val foundationFns = written["proj/src/common/commonMain/kotlin/io/github/kotlinmania/windowssys/win32/foundation/FoundationFunctions.kt"]
        val consoleKt = written["proj/src/common/commonMain/kotlin/io/github/kotlinmania/windowssys/win32/system/console/Console.kt"]

        assertTrue(foundationTypes != null && !foundationTypes.contains("port-lint"))
        assertTrue(foundationStructs != null && !foundationStructs.contains("port-lint"))
        assertTrue(foundationConsts != null && !foundationConsts.contains("port-lint"))
        assertTrue(foundationFns != null && !foundationFns.contains("port-lint"))
        assertContains(foundationStructs, "public data class FILETIME(")
        assertContains(foundationFns, "public expect fun CloseHandle(")
        assertTrue(consoleKt != null)
        assertContains(consoleKt, "package io.github.kotlinmania.windowssys.win32.system.console")
        assertContains(consoleKt, "public expect fun GetStdHandle(")

        assertTrue("proj/src/native/interop/cinterop/Win32Foundation.def" in written)
        assertTrue("proj/src/native/nativeMain/kotlin/io/github/kotlinmania/windowssys/win32/foundation/FoundationNativeActuals.kt" in written)
        assertTrue("proj/src/jvm/jvmMain/kotlin/io/github/kotlinmania/windowssys/win32/foundation/FoundationJvmActuals.kt" in written)
        assertTrue("proj/src/jvm/jvmMain/c/windowssys_win32_foundation_jni.c" in written)
        assertTrue("proj/src/android/jvm/androidMain/kotlin/io/github/kotlinmania/windowssys/win32/foundation/FoundationAndroidActuals.kt" in written)
        assertTrue("proj/src/js/jsMain/kotlin/io/github/kotlinmania/windowssys/win32/foundation/FoundationJsActuals.kt" in written)
        assertTrue("proj/src/js/node-windowssys/src/windowssys_win32_foundation_bindings.cpp" in written)
        assertTrue("proj/src/js/wasm/wasmJsMain/kotlin/io/github/kotlinmania/windowssys/win32/foundation/FoundationWasmJsActuals.kt" in written)
        assertTrue("proj/src/js/wasm/wasmWasiMain/kotlin/io/github/kotlinmania/windowssys/win32/foundation/FoundationWasmWasiActuals.kt" in written)
    }

    @Test fun libcMacrosCEnumAndSafeFExpandCleanly() {
        val libcMacros =
            """
            macro_rules! s {
                (${'$'}(
                    ${'$'}(#[${'$'}attr:meta])*
                    ${'$'}pub:vis struct ${'$'}i:ident { ${'$'}(${'$'}field:tt)* }
                )*) => (${'$'}(
                    #[repr(C)]
                    ${'$'}(#[${'$'}attr])*
                    ${'$'}pub struct ${'$'}i { ${'$'}(${'$'}field)* }
                )*);
            }
            macro_rules! c_enum {
                (${'$'}(
                    ${'$'}(#[repr(${'$'}repr:ty)])?
                    pub enum ${'$'}(${'$'}ty_name:ident)? ${'$'}(#${'$'}anon:ident)? {
                        ${'$'}(${'$'}vis:vis ${'$'}variant:ident ${'$'}(= ${'$'}value:expr)?,)+
                    }
                )+) => {
                    ${'$'}(c_enum!(@single;
                        ${'$'}(#[repr(${'$'}repr)])?
                        pub enum ${'$'}(${'$'}ty_name)? ${'$'}(#${'$'}anon)? {
                            ${'$'}(${'$'}vis ${'$'}variant ${'$'}(= ${'$'}value)?,)+
                        }
                    );)+
                };
                (@single;
                    ${'$'}(#[repr(${'$'}repr:ty)])?
                    pub enum ${'$'}ty_name:ident {
                        ${'$'}(${'$'}vis:vis ${'$'}variant:ident ${'$'}(= ${'$'}value:expr)?,)+
                    }
                ) => {
                    pub type ${'$'}ty_name = c_enum!(@ty ${'$'}(${'$'}repr)?);
                    c_enum! {
                        @variant;
                        ty: ${'$'}ty_name;
                        default: 0;
                        variants: [${'$'}(${'$'}vis ${'$'}variant ${'$'}(= ${'$'}value)?,)+]
                    }
                };
                (@variant; ty: ${'$'}_ty_name:ty; default: ${'$'}_idx:expr; variants: []) => {};
                (
                    @variant;
                    ty: ${'$'}ty_name:ty;
                    default: ${'$'}default_val:expr;
                    variants: [
                        ${'$'}vis:vis ${'$'}variant:ident ${'$'}(= ${'$'}value:expr)?,
                        ${'$'}(${'$'}tail:tt)*
                    ]
                ) => {
                    ${'$'}vis const ${'$'}variant: ${'$'}ty_name = {
                        let r = ${'$'}default_val;
                        ${'$'}(let r = ${'$'}value;)?
                        r
                    };
                    c_enum! {
                        @variant;
                        ty: ${'$'}ty_name;
                        default: ${'$'}variant + 1;
                        variants: [${'$'}(${'$'}tail)*]
                    }
                };
                (@ty ${'$'}repr:ty) => { ${'$'}repr };
                (@ty) => { ${'$'}crate::prelude::CEnumRepr };
            }
            """.trimIndent()
        val rustSource =
            """
            c_enum! {
                pub enum clockid_t {
                    pub CLOCK_REALTIME = 0,
                    pub CLOCK_MONOTONIC = 6,
                }
            }
            s! {
                pub struct timespec {
                    pub tv_sec: i64,
                    pub tv_nsec: i64,
                }
            }
            """.trimIndent()
        val kt =
            renderSingleKotlinFile(
                rustSource,
                FileHeaderContext(
                    portLintSourcePath = "unix/bsd/apple/mod.rs",
                    packageName = "io.github.kotlinmania.libc.unix.bsd.apple",
                    projectGroup = "io.github.kotlinmania",
                    projectNamespace = "io.github.kotlinmania.libc",
                    portLintDirective = "none",
                    crateMacroSources = listOf(libcMacros),
                    finalClasses = true,
                ),
            ).text
        assertContains(kt, "public typealias ClockidT = CEnumRepr")
        assertContains(kt, "CLOCK_REALTIME")
        assertContains(kt, "CLOCK_MONOTONIC")
        assertContains(kt, "public data class Timespec(")
    }

    @Test fun cDynamicFfiEmitsConcreteFunctionsAndMultiTargetScaffoldWithZeroExpects() {
        val files =
            mapOf(
                "tmp/libc/src/lib.rs" to
                    """
                    pub type c_int = i32;
                    pub type c_char = i8;
                    pub mod unix;
                    """.trimIndent(),
                "tmp/libc/src/unix/mod.rs" to
                    """
                    use crate::{c_char, c_int};
                    extern "C" {
                        pub static mut optind: c_int;
                        pub fn getpid() -> c_int;
                        #[link_name = "_putenv_s"]
                        pub fn putenv_s(name: *const c_char, value: *const c_char) -> c_int;
                    }
                    """.trimIndent(),
            )
        val written = linkedMapOf<String, String>()
        runSingleFileCommand(
            args =
                arrayOf(
                    "-r",
                    "--force",
                    "--final-structs",
                    "--split-module-roots",
                    "-n",
                    "io.github.kotlinmania.libc",
                    "-o",
                    "proj/src/common/commonMain/kotlin/io/github/kotlinmania/libc",
                    "tmp/libc/src",
                ),
            read = { path -> files[path] ?: error("missing $path") },
            writeForce = { path, text -> written[path] = text },
            exists = { false },
            isDirectory = { it == "tmp/libc/src" },
            collectRustFiles = { files.keys.toList() },
            report = {},
            resolveGradleProperties = { null },
            resolvePackageProperties = { null },
            resolveCrateMacroSources = { emptyList() },
            resolveCrateFlatFileModules = { _, _ -> emptySet() },
            resolveCrateRawAbiMetadata = { _, _, _, _ -> CrateRawAbiMetadata() },
        )

        assertFalse(written.keys.any { it.endsWith("/Lib.kt") || it.endsWith("/Mod.kt") })
        assertFalse(written.values.any { it.contains("port-lint") })
        val unixFns = written["proj/src/common/commonMain/kotlin/io/github/kotlinmania/libc/unix/UnixFunctions.kt"]
        val unixConsts = written["proj/src/common/commonMain/kotlin/io/github/kotlinmania/libc/unix/UnixConstants.kt"]
        assertTrue(unixFns != null)
        assertContains(unixFns, "public expect fun getpid(): CInt")
        assertContains(unixFns, "public expect fun putenvS(name: ConstPtr, value: ConstPtr): CInt")
        val staticContainer = unixConsts ?: unixFns
        assertContains(staticContainer, "public expect var OPTIND: CInt")

        assertTrue("proj/src/native/interop/cinterop/Unix.def" in written)
        assertTrue("proj/src/native/interop/cinterop/unix_wrapper.h" in written)
        assertTrue("proj/src/native/interop/cinterop/unix_wrapper.c" in written)
        assertTrue("proj/src/native/nativeMain/kotlin/io/github/kotlinmania/libc/unix/UnixNativeActuals.kt" in written)
        assertTrue("proj/src/jvm/jvmMain/kotlin/io/github/kotlinmania/libc/unix/UnixJvmActuals.kt" in written)
        assertTrue("proj/src/jvm/jvmMain/c/libc_unix_jni.c" in written)
        assertTrue("proj/src/android/jvm/androidMain/kotlin/io/github/kotlinmania/libc/unix/UnixAndroidActuals.kt" in written)
        assertTrue("proj/src/js/jsMain/kotlin/io/github/kotlinmania/libc/unix/UnixJsActuals.kt" in written)
        assertTrue("proj/src/js/node-libc/src/libc_unix_bindings.cpp" in written)
        assertTrue("proj/src/js/wasm/wasmJsMain/kotlin/io/github/kotlinmania/libc/unix/UnixWasmJsActuals.kt" in written)
        assertTrue("proj/src/js/wasm/wasmWasiMain/kotlin/io/github/kotlinmania/libc/unix/UnixWasmWasiActuals.kt" in written)
    }

    @Test fun vhfNtstatusAndCrossModuleTypeAliasCollisionPreserveNativeAbi() {
        val sources =
            listOf(
                "lib.rs" to "pub mod Windows;",
                "Windows/mod.rs" to "pub mod Wdk;\npub mod Win32;",
                "Windows/Win32/mod.rs" to "pub mod Foundation;\npub mod Networking;",
                "Windows/Win32/Foundation/mod.rs" to
                    """
                    pub type NTSTATUS = i32;
                    pub type BOOL = i32;
                    pub type HANDLE = *mut core::ffi::c_void;
                    """.trimIndent(),
                "Windows/Wdk/mod.rs" to "pub mod Devices;",
                "Windows/Wdk/Devices/mod.rs" to "pub mod HumanInterfaceDevice;",
                "Windows/Wdk/Devices/HumanInterfaceDevice/mod.rs" to
                    """
                    windows_link::link!("VhfUm.dll" "C" fn VhfAsyncOperationComplete(vhfoperationhandle : *const core::ffi::c_void, completionstatus : super::super::super::Win32::Foundation::NTSTATUS) -> super::super::super::Win32::Foundation::NTSTATUS);
                    windows_link::link!("VhfUm.dll" "C" fn VhfCreate(vhfconfig : *const core::ffi::c_void, vhfhandle : *mut *mut core::ffi::c_void) -> super::super::super::Win32::Foundation::NTSTATUS);
                    windows_link::link!("VhfUm.dll" "C" fn VhfReadReportSubmit(vhfhandle : *const core::ffi::c_void, hidtransferpacket : *const core::ffi::c_void) -> super::super::super::Win32::Foundation::NTSTATUS);
                    windows_link::link!("VhfUm.dll" "C" fn VhfStart(vhfhandle : *const core::ffi::c_void) -> super::super::super::Win32::Foundation::NTSTATUS);
                    """.trimIndent(),
                "Windows/Win32/Networking/mod.rs" to "pub mod ActiveDirectory;",
                "Windows/Win32/Networking/ActiveDirectory/mod.rs" to
                    """
                    pub type BOOL = bool;
                    windows_link::link!("activeds.dll" "system" fn ADsCheckFlag(flag : super::super::Foundation::BOOL) -> super::super::Foundation::BOOL);
                    """.trimIndent(),
            )
        val fileMap = sources.associate { (rel, txt) -> "tmp/windows-sys/src/$rel" to txt }
        val meta =
            buildCrateRawAbiMetadataFromSources(
                sources = sources,
                rawAbiMode = true,
                splitModuleRoots = true,
                finalStructs = true,
            )
        val written = linkedMapOf<String, String>()
        runSingleFileCommand(
            args =
                arrayOf(
                    "-r",
                    "--force",
                    "--raw-abi",
                    "--final-structs",
                    "--split-module-roots",
                    "-n",
                    "io.github.kotlinmania.windowssys",
                    "-o",
                    "proj/src/common/commonMain/kotlin/io/github/kotlinmania/windowssys",
                    "tmp/windows-sys/src",
                ),
            read = { path -> fileMap[path] ?: error("missing $path") },
            writeForce = { path, text -> written[path] = text },
            exists = { false },
            isDirectory = { it == "tmp/windows-sys/src" },
            collectRustFiles = { fileMap.keys.toList() },
            report = {},
            resolveGradleProperties = { null },
            resolvePackageProperties = { null },
            resolveCrateMacroSources = { emptyList() },
            resolveCrateFlatFileModules = { _, _ -> emptySet() },
            resolveCrateRawAbiMetadata = { _, _, _, _ -> meta },
        )

        val hidFns =
            written[
                "proj/src/common/commonMain/kotlin/io/github/kotlinmania/windowssys/" +
                    "windows/wdk/devices/humaninterfacedevice/HumanInterfaceDeviceFunctions.kt",
            ]
        assertTrue(hidFns != null)
        assertContains(hidFns, "import io.github.kotlinmania.windowssys.windows.win32.foundation.NTSTATUS")
        assertContains(
            hidFns,
            "public expect fun VhfAsyncOperationComplete(vhfoperationhandle: Long, completionstatus: NTSTATUS): NTSTATUS",
        )
        assertContains(hidFns, "public expect fun VhfCreate(vhfconfig: Long, vhfhandle: Long): NTSTATUS")
        assertContains(hidFns, "public expect fun VhfReadReportSubmit(vhfhandle: Long, hidtransferpacket: Long): NTSTATUS")
        assertContains(hidFns, "public expect fun VhfStart(vhfhandle: Long): NTSTATUS")

        val adTypes =
            written[
                "proj/src/common/commonMain/kotlin/io/github/kotlinmania/windowssys/" +
                    "windows/win32/networking/activedirectory/ActiveDirectoryTypes.kt",
            ]
        val adFns =
            written[
                "proj/src/common/commonMain/kotlin/io/github/kotlinmania/windowssys/" +
                    "windows/win32/networking/activedirectory/ActiveDirectoryFunctions.kt",
            ]
        assertTrue(adTypes != null && adFns != null)
        assertContains(adTypes, "public typealias BOOL = Boolean")
        assertContains(
            adFns,
            "public expect fun ADsCheckFlag(flag: io.github.kotlinmania.windowssys.windows.win32.foundation.BOOL): " +
                "io.github.kotlinmania.windowssys.windows.win32.foundation.BOOL",
        )
    }

    @Test fun u128AndGuidLoweringIsPurelyStructuralAcrossSentinelAndRenamedTypes() {
        val guid128Source =
            """
            #[repr(C)]
            pub struct GUID {
                pub data1: u32,
                pub data2: u16,
                pub data3: u16,
                pub data4: [u8; 8],
            }
            impl GUID {
                pub const fn from_u128(uuid: u128) -> Self {
                    Self {
                        data1: (uuid >> 96) as u32,
                        data2: ((uuid >> 80) & 0xffff) as u16,
                        data3: ((uuid >> 64) & 0xffff) as u16,
                        data4: [
                            (uuid >> 56) as u8,
                            (uuid >> 48) as u8,
                            (uuid >> 40) as u8,
                            (uuid >> 32) as u8,
                            (uuid >> 24) as u8,
                            (uuid >> 16) as u8,
                            (uuid >> 8) as u8,
                            uuid as u8,
                        ],
                    }
                }
            }
            pub const IID_IUNKNOWN: GUID = GUID::from_u128(0x00000000_0000_0000_c000_000000000046);
            """.trimIndent()
        val guidKt =
            renderSingleKotlinFile(
                guid128Source,
                FileHeaderContext(
                    portLintSourcePath = "guid.rs",
                    packageName = "io.github.kotlinmania.sample",
                    rawAbiMode = true,
                    finalClasses = true,
                ),
            ).text
        assertContains(guidKt, "public class U128(")
        assertContains(guidKt, "public fun from_u128(uuid: U128): GUID")
        assertContains(guidKt, "GUID.from_u128(U128(0x0uL, 0xc000000000000046uL))")

        val sentinelGuidSource =
            """
            pub struct GUID(pub u64);
            impl GUID {
                pub const fn from_u128(seed: u64) -> Self {
                    Self(seed ^ 0x55)
                }
            }
            pub const SENTINEL_ID: GUID = GUID::from_u128(7);
            """.trimIndent()
        val sentinelKt =
            renderSingleKotlinFile(
                sentinelGuidSource,
                FileHeaderContext(
                    portLintSourcePath = "sentinel.rs",
                    packageName = "io.github.kotlinmania.sample",
                ),
            ).text
        assertFalse(sentinelKt.contains("class U128"))
        assertFalse(sentinelKt.contains("data1"))
        assertContains(sentinelKt, "seed xor 0x55uL")
        assertContains(sentinelKt, "Guid.fromU128(7uL)")

        val renamed128Source =
            """
            pub struct CustomKey128 {
                pub hi_word: u32,
                pub tail: [u8; 16],
            }
            impl CustomKey128 {
                pub fn make_key(bits: u128) -> Self {
                    Self {
                        hi_word: (bits >> 96) as u32,
                        tail: bits.to_be_bytes(),
                    }
                }
            }
            """.trimIndent()
        val renamedKt =
            renderSingleKotlinFile(
                renamed128Source,
                FileHeaderContext(
                    portLintSourcePath = "custom_key.rs",
                    packageName = "io.github.kotlinmania.sample",
                    finalClasses = true,
                ),
            ).text
        assertContains(renamedKt, "public class U128(")
        assertContains(renamedKt, "bits.toBeBytes()")
        assertContains(renamedKt, "(bits shr 96).toUInt()")
    }

    @Test fun cfgTargetProfileEvaluatesPointerWidthArchEndianEnvAndOpenVsClosedWorld() {
        val cfgSource =
            """
            pub type size_t = usize;
            #[cfg(all(target_arch = "wasm32", target_pointer_width = "32"))]
            pub const IS_WASM32: bool = true;
            #[cfg(target_pointer_width = "64")]
            pub const ONLY_64: i32 = 64;
            #[cfg(feature = "experimental_net")]
            pub const HAS_EXP_NET: i32 = 1;
            pub fn runtime_flags() -> (bool, bool, bool) {
                (
                    cfg!(target_pointer_width = "32"),
                    cfg!(target_endian = "big"),
                    cfg!(windows),
                )
            }
            """.trimIndent()

        val wasmOpenKt =
            renderSingleKotlinFile(
                cfgSource,
                FileHeaderContext(
                    portLintSourcePath = "windows/check.rs",
                    packageName = "io.github.kotlinmania.sample.windows",
                    currentRelativeSourceDir = "windows",
                    rawAbiMode = true,
                    cfgTargetProfile =
                        CfgTargetProfile(
                            pointerWidth = 32,
                            targetEndian = "big",
                            targetArch = "wasm32",
                            targetOs = "unknown",
                            targetFamilies = setOf("wasm"),
                            unknownPolicy = UnknownCfgPolicy.OPEN_WORLD_NULL,
                            useRelativeDirPlatformHints = false,
                        ),
                ),
            ).text
        assertContains(wasmOpenKt, "public typealias size_t = UInt")
        assertContains(wasmOpenKt, "public val IS_WASM32: Boolean = true")
        assertFalse(wasmOpenKt.contains("ONLY_64"))
        assertContains(wasmOpenKt, "public val HAS_EXP_NET: Int = 1")
        assertContains(wasmOpenKt, "Triple(true, true, false)")

        val closedWorld64Kt =
            renderSingleKotlinFile(
                cfgSource,
                FileHeaderContext(
                    portLintSourcePath = "windows/check.rs",
                    packageName = "io.github.kotlinmania.sample.windows",
                    currentRelativeSourceDir = "windows",
                    rawAbiMode = true,
                    cfgTargetProfile =
                        CfgTargetProfile(
                            pointerWidth = 64,
                            targetEndian = "little",
                            targetArch = "aarch64",
                            targetOs = "linux",
                            targetFamilies = setOf("unix"),
                            enabledFeatures = emptySet(),
                            unknownPolicy = UnknownCfgPolicy.CLOSED_WORLD_FALSE,
                            useRelativeDirPlatformHints = false,
                        ),
                ),
            ).text
        assertContains(closedWorld64Kt, "public typealias size_t = ULong")
        assertFalse(closedWorld64Kt.contains("IS_WASM32"))
        assertContains(closedWorld64Kt, "public val ONLY_64: Int = 64")
        assertFalse(closedWorld64Kt.contains("HAS_EXP_NET"))
        assertContains(closedWorld64Kt, "Triple(false, false, false)")
    }

    @Test fun cEnumReprFollowsDeclaredAliasTargetAcrossMsvcUnixI16AndRenamedAliases() {
        val enumMacro =
            """
            macro_rules! c_enum {
                (
                    #[repr(${'$'}repr:ty)]
                    pub enum ${'$'}name:ident {
                        ${'$'}(${'$'}variant:ident = ${'$'}val:expr,)*
                    }
                ) => {
                    pub type ${'$'}name = ${'$'}repr;
                    ${'$'}(pub const ${'$'}variant: ${'$'}name = ${'$'}val;)*
                };
            }
            """.trimIndent()
        val libcTypesAndEnum =
            """
            pub type c_int = i32;
            pub type c_uint = u32;
            #[cfg(all(windows, target_env = "msvc"))]
            pub type CEnumRepr = c_int;
            #[cfg(not(all(windows, target_env = "msvc")))]
            pub type CEnumRepr = c_uint;
            c_enum! {
                #[repr(CEnumRepr)]
                pub enum ModeKind {
                    MODE_FIRST = 0,
                    MODE_SECOND = 1,
                }
            }
            """.trimIndent()

        val msvcKt =
            renderSingleKotlinFile(
                libcTypesAndEnum,
                FileHeaderContext(
                    portLintSourcePath = "types.rs",
                    packageName = "io.github.kotlinmania.libc",
                    crateMacroSources = listOf(enumMacro),
                    cfgTargetProfile =
                        CfgTargetProfile(
                            targetOs = "windows",
                            targetEnv = "msvc",
                            targetFamilies = setOf("windows"),
                        ),
                ),
            ).text
        assertContains(msvcKt, "public typealias CEnumRepr = CInt")
        assertFalse(msvcKt.contains("public typealias CEnumRepr = CUint"))
        assertContains(msvcKt, "public val MODE_FIRST: ModeKind = 0")

        val gnuKt =
            renderSingleKotlinFile(
                libcTypesAndEnum,
                FileHeaderContext(
                    portLintSourcePath = "types.rs",
                    packageName = "io.github.kotlinmania.libc",
                    crateMacroSources = listOf(enumMacro),
                    cfgTargetProfile =
                        CfgTargetProfile(
                            targetOs = "linux",
                            targetEnv = "gnu",
                            targetFamilies = setOf("unix"),
                        ),
                ),
            ).text
        assertContains(gnuKt, "public typealias CEnumRepr = CUint")
        assertFalse(gnuKt.contains("public typealias CEnumRepr = CInt"))
        assertContains(gnuKt, "public val MODE_FIRST: ModeKind = 0u")

        val i16AndRenamed =
            """
            pub type CEnumRepr = i16;
            pub type CustomEnumBacking = i16;
            c_enum! {
                #[repr(CEnumRepr)]
                pub enum SmallTag {
                    TAG_A = 1 + 2,
                }
            }
            c_enum! {
                #[repr(CustomEnumBacking)]
                pub enum OtherTag {
                    OTHER_A = 3 + 4,
                }
            }
            """.trimIndent()
        val smallKt =
            renderSingleKotlinFile(
                i16AndRenamed,
                FileHeaderContext(
                    portLintSourcePath = "small.rs",
                    packageName = "io.github.kotlinmania.sample",
                    crateMacroSources = listOf(enumMacro),
                ),
            ).text
        assertContains(smallKt, "public typealias CEnumRepr = Short")
        assertContains(smallKt, "public typealias CustomEnumBacking = Short")
        assertContains(smallKt, "public val TAG_A: SmallTag = (1 + 2).toShort()")
        assertContains(smallKt, "public val OTHER_A: OtherTag = (3 + 4).toShort()")
    }

    @Test fun tokenAbiScannerPacksArbitraryStructsUnionsSpacedCallbacksAndPointerMacros() {
        val sources =
            listOf(
                "lib.rs" to "pub mod ffi;",
                "ffi/mod.rs" to
                    """
                    #[macro_export]
                    macro_rules! utf8_ptr {
                        (${'$'}lit:literal) => {
                            ::core::concat!(${'$'}lit, '\0').as_ptr()
                        };
                    }
                    #[repr(C)]
                    pub struct PairWord {
                        pub lo: u16,
                        pub hi: u16,
                    }
                    #[repr(C)]
                    pub struct Block16 {
                        pub w0: u64,
                        pub w1: u64,
                    }
                    #[repr(C)]
                    pub union WordOrBytes {
                        pub word: u32,
                        pub half: u16,
                    }
                    pub type HookCb = Option < unsafe /* spaced comment */ extern "system" fn ( code : i32 , ctx : * mut core::ffi::c_void ) -> i32 > ;
                    windows_link::link!("demo.dll" "system" fn DispatchPair(pair : PairWord, blk : Block16, u : WordOrBytes, cb : HookCb) -> PairWord);
                    windows_link::link!("demo.dll" "system" fn ResolveHook() -> HookCb);
                    """.trimIndent(),
            )
        val meta =
            buildCrateRawAbiMetadataFromSources(
                sources = sources,
                rawAbiMode = true,
                splitModuleRoots = true,
                finalStructs = true,
            )
        assertTrue("ffi.PairWord" in meta.valueParamPackTemplates)
        assertTrue("ffi.PairWord" in meta.valueReturnUnpackTemplates)
        assertTrue("ffi.Block16" in meta.valueParamPackTemplates)
        assertTrue("ffi.WordOrBytes" in meta.valueParamPackTemplates)
        assertTrue("ffi.HookCbFn" in meta.fnPtrInvokeTemplates)

        val chunks =
            translateModuleRootIntoFocusedChunks(
                source = sources[1].second,
                modulePascalName = "Ffi",
                headerContext =
                    FileHeaderContext(
                        packageName = "io.github.kotlinmania.demo.ffi",
                        projectGroup = "io.github.kotlinmania",
                        projectNamespace = "io.github.kotlinmania.demo",
                        portLintDirective = "none",
                        rawAbiMode = true,
                        finalClasses = true,
                        crateTypeAliasDefaults = meta.typeAliasDefaults,
                        crateOptionFnTypes = meta.optionFnTypes,
                        crateNumericTypeKinds = meta.numericTypeKinds,
                        crateStructFieldTypes = meta.structFieldTypes,
                        crateSymbolSubpackages = meta.symbolSubpackages,
                        crateSubpackageRawLeafNames = meta.subpackageRawLeafNames,
                        crateValueParamPackTemplates = meta.valueParamPackTemplates,
                        crateValueReturnUnpackTemplates = meta.valueReturnUnpackTemplates,
                        crateFnPtrInvokeTemplates = meta.fnPtrInvokeTemplates,
                        crateFnParamNumericKinds = meta.fnParamNumericKinds,
                    ),
            )
        val allChunkText = chunks.joinToString("\n\n") { it.text }
        assertContains(allChunkText, "public fun utf8_ptr(lit: String): Long")
        assertContains(allChunkText, "public expect fun DispatchPair(pair: PairWord, blk: Block16, u: WordOrBytes, cb: HookCb): PairWord")
        assertContains(allChunkText, "public expect fun ResolveHook(): HookCb")
    }

    @Test fun customCfgKeyValueHonorsConfiguredAndUnconfiguredPredicatesAcrossProfilesT6225cc1a() {
        val cfgSource =
            """
            #[cfg(target_pointer_width = "32")]
            pub fn width() -> u32 {
                32
            }
            #[cfg(target_pointer_width = "64")]
            pub fn width() -> u32 {
                64
            }
            pub fn configured() -> bool {
                cfg!(all(feature = "tracing", probe_flag, flavor = "mint"))
            }
            pub fn featureonly() -> bool {
                cfg!(feature = "tracing")
            }
            pub fn flagonly() -> bool {
                cfg!(probe_flag)
            }
            pub fn keyonly() -> bool {
                cfg!(flavor = "mint")
            }
            pub fn unconfigured() -> bool {
                cfg!(flavor = "lemon")
            }
            #[cfg(flavor = "mint")]
            pub const MINT_ACTIVE: bool = true;
            #[cfg(flavor = "lemon")]
            pub const LEMON_ACTIVE: bool = true;
            """.trimIndent()

        val profiles =
            listOf(
                Triple("wasm32", 32, arrayOf("--cfg", "target_arch=wasm32", "--cfg", "target_pointer_width=32", "--cfg", "target_os=unknown", "--cfg", "target_family=wasm")),
                Triple("x86_64-msvc", 64, arrayOf("--cfg", "target_arch=x86_64", "--cfg", "target_pointer_width=64", "--cfg", "target_os=windows", "--cfg", "target_env=msvc", "--cfg", "target_family=windows")),
                Triple("x86_64-gnu", 64, arrayOf("--cfg", "target_arch=x86_64", "--cfg", "target_pointer_width=64", "--cfg", "target_os=linux", "--cfg", "target_env=gnu", "--cfg", "target_family=unix")),
                Triple("aarch64-macos", 64, arrayOf("--cfg", "target_arch=aarch64", "--cfg", "target_pointer_width=64", "--cfg", "target_os=macos", "--cfg", "target_family=unix")),
            )

        val extraArgs =
            arrayOf(
                "--force",
                "--feature",
                "tracing",
                "--cfg",
                "probe_flag",
                "--cfg",
                "flavor=\"mint\"",
                "--cfg-closed-world",
                "-n",
                "io.github.kotlinmania.probe",
                "-o",
                "out/Config.kt",
                "plain/config.rs",
            )
        for ((label, expectedWidth, targetArgs) in profiles) {
            val written = linkedMapOf<String, String>()
            runSingleFileCommand(
                args = targetArgs + extraArgs,
                read = { cfgSource },
                writeForce = { path, text -> written[path] = text },
                exists = { false },
                isDirectory = { false },
                collectRustFiles = { listOf("plain/config.rs") },
                report = {},
                resolveGradleProperties = { null },
                resolvePackageProperties = { null },
                resolveCrateMacroSources = { emptyList() },
                resolveCrateFlatFileModules = { _, _ -> emptySet() },
                resolveCrateRawAbiMetadata = { _, _, _, _ -> CrateRawAbiMetadata() },
            )
            val kt = written["out/Config.kt"] ?: error("missing output for $label")
            assertContains(kt, "public fun configured(): Boolean {\n    return true\n}")
            assertContains(kt, "public fun featureonly(): Boolean {\n    return true\n}")
            assertContains(kt, "public fun flagonly(): Boolean {\n    return true\n}")
            assertContains(kt, "public fun keyonly(): Boolean {\n    return true\n}")
            assertContains(kt, "public fun unconfigured(): Boolean {\n    return false\n}")
            assertContains(kt, "public val MINT_ACTIVE: Boolean = true")
            assertFalse(kt.contains("LEMON_ACTIVE"))
            assertContains(kt, "return ${expectedWidth}u")
        }
    }

    @Test fun inferredComparisonLiteralsMatchUnsignedFieldsIndexedArraysAndShortAliasesT4c390c99() {
        val guidSentinelSource =
            """
            #[repr(C)]
            pub struct GUID {
                pub marker: u32,
            }
            impl GUID {
                pub const fn from_u128(value: u128) -> Self {
                    Self { marker: (value & 0xff) as u32 + 1 }
                }
            }
            pub fn check_sentinel() -> bool {
                let number: u128 = 0x11223344_5566_7788_99aa_bbccddeeff48;
                GUID::from_u128(number).marker == 73
            }
            """.trimIndent()
        val sentinelKt = renderSingleKotlinFile(guidSentinelSource).text
        assertContains(sentinelKt, "Guid.fromU128(number).marker == 73u")

        val guidOwnerRenamedSource =
            guidSentinelSource
                .replace("struct GUID", "struct RecordId")
                .replace("impl GUID", "impl RecordId")
                .replace("GUID::from_u128", "RecordId::from_u128")
        val ownerRenamedKt = renderSingleKotlinFile(guidOwnerRenamedSource).text
        assertContains(ownerRenamedKt, "RecordId.fromU128(number).marker == 73u")

        val guidMethodRenamedSource =
            guidSentinelSource
                .replace("fn from_u128", "fn from_bits")
                .replace("GUID::from_u128", "GUID::from_bits")
        val methodRenamedKt = renderSingleKotlinFile(guidMethodRenamedSource).text
        assertContains(methodRenamedKt, "Guid.fromBits(number).marker == 73u")

        val guidLayoutSource =
            """
            #[repr(C)]
            pub struct GUID {
                pub data1: u32,
                pub data2: u16,
                pub data3: u16,
                pub data4: [u8; 8],
            }
            impl GUID {
                pub const fn from_u128(uuid: u128) -> Self {
                    Self {
                        data1: (uuid >> 96) as u32,
                        data2: ((uuid >> 80) & 0xffff) as u16,
                        data3: ((uuid >> 64) & 0xffff) as u16,
                        data4: [
                            (uuid >> 56) as u8,
                            (uuid >> 48) as u8,
                            (uuid >> 40) as u8,
                            (uuid >> 32) as u8,
                            (uuid >> 24) as u8,
                            (uuid >> 16) as u8,
                            (uuid >> 8) as u8,
                            uuid as u8,
                        ],
                    }
                }
            }
            pub fn check_layout() -> bool {
                let number: u128 = 0x11223344_5566_7788_99aa_bbccddeeff00;
                let id = GUID::from_u128(number);
                id.data1 == 0x11223344 && id.data2 == 0x5566 && id.data3 == 0x7788 && id.data4[0] == 0x99 && id.data4[7] == 0x00
            }
            """.trimIndent()
        val layoutPlainKt = renderSingleKotlinFile(guidLayoutSource).text
        assertContains(layoutPlainKt, "id.data1 == 0x11223344u")
        assertContains(layoutPlainKt, "id.data2 == 0x5566u.toUShort()")
        assertContains(layoutPlainKt, "id.data3 == 0x7788u.toUShort()")
        assertContains(layoutPlainKt, "id.data4[0] == 0x99u.toUByte()")
        assertContains(layoutPlainKt, "id.data4[7] == 0x00u.toUByte()")

        val layoutRawKt =
            renderSingleKotlinFile(
                guidLayoutSource,
                FileHeaderContext(
                    portLintSourcePath = "guid.rs",
                    packageName = "io.github.kotlinmania.sample",
                    rawAbiMode = true,
                    finalClasses = true,
                ),
            ).text
        assertContains(layoutRawKt, "id.data1 == 0x11223344u")
        assertContains(layoutRawKt, "id.data2 == 0x5566u.toUShort()")
        assertContains(layoutRawKt, "id.data4[0] == 0x99u.toUByte()")

        val aliasNamedSource =
            """
            pub type CEnumRepr = i16;
            pub const fn negative() -> CEnumRepr {
                -7
            }
            pub fn check_alias() -> bool {
                negative() == -7
            }
            """.trimIndent()
        val aliasNamedKt = renderSingleKotlinFile(aliasNamedSource).text
        assertContains(aliasNamedKt, "public typealias CEnumRepr = Short")
        assertContains(aliasNamedKt, "negative() == (-7).toShort()")

        val aliasRenamedSource = aliasNamedSource.replace("CEnumRepr", "WireRepr")
        val aliasRenamedKt = renderSingleKotlinFile(aliasRenamedSource).text
        assertContains(aliasRenamedKt, "public typealias WireRepr = Short")
        assertContains(aliasRenamedKt, "negative() == (-7).toShort()")
    }

    @Test fun uintReturnContextPropagatesIntoUnsuffixedReturnLiteralsTae1a4eff() {
        val cfgWidthSource =
            """
            #[cfg(target_pointer_width = "32")]
            pub fn width() -> u32 {
                32
            }
            #[cfg(target_pointer_width = "64")]
            pub fn width() -> u32 {
                64
            }
            pub fn branch_width(flag: bool, tag: i32) -> u32 {
                if flag {
                    return 16;
                }
                match tag {
                    0 => 32,
                    _ => if flag { 48 } else { 64 },
                }
            }
            """.trimIndent()

        for ((pathPrefix, pkgSuffix) in listOf("plain" to "plain", "windows" to "windows")) {
            for (ptrWidth in listOf(32, 64)) {
                val kt =
                    renderSingleKotlinFile(
                        cfgWidthSource,
                        FileHeaderContext(
                            portLintSourcePath = "$pathPrefix/config.rs",
                            packageName = "io.github.kotlinmania.probe.$pkgSuffix",
                            currentRelativeSourceDir = pathPrefix,
                            cfgTargetProfile =
                                CfgTargetProfile(
                                    pointerWidth = ptrWidth,
                                    useRelativeDirPlatformHints = false,
                                ),
                        ),
                    ).text
                assertContains(kt, "public fun width(): UInt {\n    return ${ptrWidth}u\n}")
                assertContains(kt, "return 16u")
                assertContains(kt, "0 -> 32u")
                assertContains(kt, "48u")
                assertContains(kt, "64u")
            }
        }
    }

    @Test fun syn309GeneratorDefectsNonDrainingTokensOptionalParseIsSomeAndNestedIfLet() {
        val rustSource =
            """
            pub struct TypePath {
                pub attrs: Vec<i32>,
                pub qself: Option<i32>,
                pub path: i32,
            }

            pub enum AstType {
                Path(TypePath),
                Infer,
            }

            pub enum Visibility {
                Public,
                Inherited,
            }

            impl Visibility {
                pub fn is_some(&self) -> bool {
                    matches!(self, Visibility::Public)
                }
                pub fn is_none(&self) -> bool {
                    matches!(self, Visibility::Inherited)
                }
            }

            pub struct Holder {
                pub vis: Visibility,
                pub opt: Option<i32>,
                pub dot2_token: Option<Token![..]>,
            }

            pub fn parse_items(input: ParseStream, h: Holder, first_ty: AstType) -> Result<Holder> {
                let semi_token: Token![;] = input.parse()?;
                let safe_token: Option<Token![safe]> = input.parse()?;
                let abi: Option<Abi> = input.parse()?;
                let maybe: Option<Token![?]> = input.parse::<Option<Token![?]>>()?;
                let built = Holder {
                    vis: Visibility::Public,
                    opt: safe_token.map(|_| 1),
                    dot2_token: Some(input.parse()?),
                };
                let Holder { vis, opt, .. } = h;
                if vis.is_some() && !vis.is_none() && opt.is_some() {
                    let _ = (semi_token, abi, maybe);
                }
                if let AstType::Path(TypePath { attrs: _, qself: None, path }) = first_ty {
                    let _ = path;
                }
                if let AstType::Path(TypePath { attrs: _, qself: None, path }) = next_ty() {
                    let _ = path;
                }
                Ok(built)
            }

            pub fn next_ty() -> AstType {
                AstType::Infer
            }
            """.trimIndent()

        val kt = renderSingleKotlinFile(rustSource).text
        assertContains(kt, "val semiToken: Semi = input.parse(Semi::parse)")
        assertFalse(kt.contains("val semiToken: Token"))
        assertContains(kt, "val safeToken: Safe? = (if (input.peek(Safe)) input.parse(Safe::parse) else Result.success(null))")
        assertContains(kt, "val abi: Abi? = (if (input.peek(Extern)) input.parse(Abi::parse) else Result.success(null))")
        assertContains(kt, "(if (input.peek(Question)) input.parse(Question::parse) else Result.success(null))")
        assertContains(kt, "dot2Token = input.parse(DotDot::parse).getOrElse { return Result.failure(it) }")
        assertContains(kt, "vis.isSome()")
        assertContains(kt, "!vis.isNone()")
        assertContains(kt, "opt != null")
        assertContains(kt, "if (firstTy is AstType.Path && firstTy.value.qself == null)")
        assertContains(kt, "val path = firstTy.value.path")
        assertContains(kt, "if (matchVal is AstType.Path && matchVal.value.qself == null)")
        assertContains(kt, "val path1 = matchVal.value.path")
        assertFalse(kt.contains("is TypePath"))
        assertFalse(kt.contains("as TypePath"))
    }

    @Test
    fun rfc3762ConstTraitsMacros2AndMetavarExpressionsTranslateCleanly() {
        val dollar = '$'
        val rustSource =
            """
            pub struct BoxVal<T> {
                pub inner: T,
            }

            pub(crate) const unsafe trait BytewiseEq {
                fn tag(&self) -> i32 {
                    1
                }
            }

            pub const trait ScoreBound: [const] BytewiseEq {
                fn score(&self) -> i32;
            }

            macro_rules! impl_bytewise {
                ($dollar(${dollar}t:ty)+) => {
                    $dollar(
                        const unsafe impl BytewiseEq for ${dollar}t {}
                    )+
                };
            }

            impl_bytewise! { i32 i64 }

            const impl<T: [const] BytewiseEq> ScoreBound for BoxVal<T> {
                fn score(&self) -> i32 {
                    self.inner.tag()
                }
            }

            pub const fn read_score<T: [const] ScoreBound>(item: &T) -> i32 {
                item.score()
            }

            macro sum_weighted($dollar(${dollar}x:ident),+) {
                {
                    let mut total: usize = 0;
                    $dollar(
                        let _ = ($dollar{ignore(${dollar}x)} 0usize);
                        total += $dollar{index()} + $dollar{len()};
                    )+
                    total + $dollar{count(${dollar}x)}
                }
            }

            pub fn check_guard_and_macros(opt: Option<i32>) -> usize {
                let a = 1;
                let b = 2;
                let c = 3;
                let base = sum_weighted!(a, b, c);
                match opt {
                    Some(v) if v > 0 => base + (v as usize),
                    _ => base,
                }
            }
            """.trimIndent()

        val kt = renderSingleKotlinFile(rustSource).text
        assertContains(kt, "internal interface BytewiseEq")
        assertContains(kt, "public interface ScoreBound : BytewiseEq")
        assertContains(kt, "public open class BoxVal<T>(public var inner: T) : ScoreBound")
        assertContains(kt, "override fun score(): Int")
        assertContains(kt, "public fun <T : ScoreBound> readScore(item: T): Int")
        assertContains(kt, "total += 0 + 3")
        assertContains(kt, "total += 1 + 3")
        assertContains(kt, "total += 2 + 3")
        assertContains(kt, "total + 3")
        assertContains(kt, "opt != null && opt > 0")
    }

    @Test
    fun ffiPointerReturnsPointeeMutabilityAndTargetSizeAliasesRespect64And32BitProfiles() {
        val libRs =
            """
            pub type c_char = i8;
            pub type c_void = u8;
            pub type size_t = usize;
            pub type ssize_t = isize;
            pub type count_alias_t = size_t;
            pub type mut_cstr_t = *mut c_char;
            cfg_if! {
                if #[cfg(all(target_pointer_width = "64", not(windows)))] {
                    pub type c_long = i64;
                } else {
                    pub type c_long = i32;
                }
            }
            #[cfg(unix)]
            pub mod unix;
            #[cfg(target_os = "wasi")]
            pub mod wasi;
            #[cfg(windows)]
            pub mod windows;
            """.trimIndent()
        val unixModRs =
            """
            //! Header: `stdlib.h`
            //! Header: `string.h`
            use crate::{c_char, c_long, c_void, count_alias_t, mut_cstr_t, size_t, ssize_t};
            extern "C" {
                pub fn strdup(cs: *const c_char) -> *mut c_char;
                pub fn strndup(cs: *const c_char, n: size_t) -> *mut c_char;
                pub fn custom_ndup(cs: *const c_char, n: count_alias_t) -> mut_cstr_t;
                pub fn getenv(name: *const c_char) -> *const c_char;
                pub fn strcpy(dst: *mut c_char, src: *const c_char) -> *mut c_char;
                pub fn read_bytes(fd: i32, buf: *mut c_void, count: size_t) -> ssize_t;
                pub fn free(p: *mut c_void);
            }
            """.trimIndent()
        val wasiModRs =
            """
            //! Header: `stdlib.h`
            //! Header: `string.h`
            use crate::{c_char, c_long, c_void, count_alias_t, size_t, ssize_t};
            extern "C" {
                pub fn strdup(cs: *const c_char) -> *mut c_char;
                pub fn strndup(cs: *const c_char, n: size_t) -> *mut c_char;
                pub fn custom_ndup(cs: *const c_char, n: count_alias_t) -> *mut c_char;
                pub fn strtol(s: *const c_char, endp: *mut *mut c_char, base: i32) -> c_long;
                pub fn read_bytes(fd: i32, buf: *mut c_void, count: size_t) -> ssize_t;
                pub fn free(p: *mut c_void);
            }
            """.trimIndent()
        val windowsModRs =
            """
            //! Header: `stdlib.h`
            //! Header: `string.h`
            use crate::{c_char, c_long, count_alias_t, size_t};
            extern "C" {
                pub fn strndup(cs: *const c_char, n: size_t) -> *mut c_char;
                pub fn custom_ndup(cs: *const c_char, n: count_alias_t) -> *mut c_char;
                pub fn strtol(s: *const c_char, endp: *mut *mut c_char, base: i32) -> c_long;
            }
            """.trimIndent()
        val crateSources =
            mapOf(
                "tmp/libc/src/lib.rs" to libRs,
                "tmp/libc/src/unix/mod.rs" to unixModRs,
                "tmp/libc/src/wasi/mod.rs" to wasiModRs,
                "tmp/libc/src/windows/mod.rs" to windowsModRs,
            )

        fun runModuleSplit(
            modPath: String,
            rawAbi: Boolean,
            pointerWidth: String,
            arch: String,
            os: String,
        ): Map<String, String> {
            val out = linkedMapOf<String, String>()
            val flags = mutableListOf<String>()
            flags += "translate"
            flags += "--force"
            flags += "--split-module-roots"
            flags += "--final-structs"
            if (rawAbi) flags += "--raw-abi"
            flags += listOf("--source-root", "tmp/libc/src")
            flags += listOf("--target-pointer-width", pointerWidth)
            flags += listOf("--target-arch", arch)
            flags += listOf("--target-os", os)
            flags += listOf("-n", "io.github.kotlinmania.libc")
            flags += listOf("-o", "/proj/src/common/commonMain/kotlin/io/github/kotlinmania/libc")
            flags += modPath
            runSingleFileCommand(
                args = flags.toTypedArray(),
                read = { path -> crateSources[path] ?: error("missing $path") },
                write = { path, text -> out[path] = text },
                writeForce = { path, text -> out[path] = text },
                exists = { path -> path in out },
                isDirectory = { it == "tmp/libc/src" },
                collectRustFiles = { crateSources.keys.toList() },
                report = {},
                resolveGradleProperties = { null },
                resolvePackageProperties = { null },
                resolveCrateMacroSources = { emptyList() },
                resolveCrateFlatFileModules = { _, _ -> emptySet() },
                resolveCrateRawAbiMetadata = { _, _, raw, split ->
                    buildCrateRawAbiMetadataFromSources(
                        sources = crateSources.entries.map { it.key.removePrefix("tmp/libc/src/") to it.value },
                        rawAbiMode = raw,
                        splitModuleRoots = split,
                        finalStructs = TranslationSession.finalStructs,
                    )
                },
            )
            return out
        }

        // 1. 64-bit Unix --raw-abi
        val unixRaw = runModuleSplit("tmp/libc/src/unix/mod.rs", rawAbi = true, pointerWidth = "64", arch = "aarch64", os = "macos")
        val unixRawFns = unixRaw.getValue("/proj/src/common/commonMain/kotlin/io/github/kotlinmania/libc/unix/UnixFunctions.kt")
        val unixRawH = unixRaw.getValue("/proj/src/native/interop/cinterop/unix_wrapper.h")
        val unixRawC = unixRaw.getValue("/proj/src/native/interop/cinterop/unix_wrapper.c")
        val unixRawDef = unixRaw.getValue("/proj/src/native/interop/cinterop/Unix.def")
        val unixRawNative = unixRaw.getValue("/proj/src/native/nativeMain/kotlin/io/github/kotlinmania/libc/unix/UnixNativeActuals.kt")

        assertContains(unixRawFns, "public expect fun strdup(cs: Long): Long")
        assertContains(unixRawFns, "public expect fun strndup(cs: Long, n: size_t): Long")
        assertContains(unixRawFns, "public expect fun custom_ndup(cs: Long, n: count_alias_t): mut_cstr_t")
        assertContains(unixRawFns, "public expect fun getenv(name: Long): Long")

        assertContains(unixRawH, "char* libcUnixStrdup(const char* cs);")
        assertContains(unixRawH, "char* libcUnixStrndup(const char* cs, uint64_t n);")
        assertContains(unixRawH, "char* libcUnixCustomNdup(const char* cs, uint64_t n);")
        assertContains(unixRawH, "const char* libcUnixGetenv(const char* name);")
        assertContains(unixRawH, "char* libcUnixStrcpy(char* dst, const char* src);")
        assertContains(unixRawH, "int64_t libcUnixReadBytes(int32_t fd, void* buf, uint64_t count);")
        assertFalse(unixRawH.contains("int32_t n"))

        assertContains(unixRawC, "return (char*)strdup(cs);")
        assertContains(unixRawC, "return (char*)strndup(cs, n);")
        assertContains(unixRawC, "return (const char*)getenv(name);")

        assertContains(unixRawDef, "noStringConversion = ")
        assertContains(unixRawDef, "libcUnixStrdup")
        assertContains(unixRawDef, "libcUnixStrndup")
        assertContains(unixRawDef, "libcUnixGetenv")

        assertFalse(unixRawNative.contains("toKString"))
        assertFalse(unixRawNative.contains("n.toInt()"))
        assertFalse(unixRawNative.contains("?.value"))
        assertContains(unixRawNative, "import io.github.kotlinmania.libc.unix.cinterop.libcUnixStrdup")
        assertContains(
            unixRawNative,
            "public actual fun strdup(cs: Long): Long =\n" +
                "    libcUnixStrdup(cs.toCPointer<ByteVar>())?.toLong() ?: 0L",
        )
        assertContains(
            unixRawNative,
            "public actual fun strndup(cs: Long, n: size_t): Long =\n" +
                "    libcUnixStrndup(cs.toCPointer<ByteVar>(), n)?.toLong() ?: 0L",
        )
        assertContains(
            unixRawNative,
            "public actual fun custom_ndup(cs: Long, n: count_alias_t): mut_cstr_t =\n" +
                "    libcUnixCustomNdup(cs.toCPointer<ByteVar>(), n)?.toLong() ?: 0L",
        )
        assertContains(
            unixRawNative,
            "public actual fun free(p: Long) {\n" +
                "    libcUnixFree(p.toCPointer<ByteVar>())\n" +
                "}",
        )

        // 2. 64-bit Unix mapped mode
        val unixMapped = runModuleSplit("tmp/libc/src/unix/mod.rs", rawAbi = false, pointerWidth = "64", arch = "aarch64", os = "macos")
        val unixMappedFns = unixMapped.getValue("/proj/src/common/commonMain/kotlin/io/github/kotlinmania/libc/unix/UnixFunctions.kt")
        val unixMappedH = unixMapped.getValue("/proj/src/native/interop/cinterop/unix_wrapper.h")
        val unixMappedDef = unixMapped.getValue("/proj/src/native/interop/cinterop/Unix.def")
        val unixMappedNative = unixMapped.getValue("/proj/src/native/nativeMain/kotlin/io/github/kotlinmania/libc/unix/UnixNativeActuals.kt")

        assertContains(unixMappedFns, "public expect fun strdup(cs: ConstPtr): MutPtr")
        assertContains(unixMappedFns, "public expect fun strndup(cs: ConstPtr, n: SizeT): MutPtr")
        assertContains(unixMappedFns, "public expect fun customNdup(cs: ConstPtr, n: CountAliasT): MutCstrT")
        assertContains(unixMappedFns, "public expect fun getenv(name: ConstPtr): ConstPtr")
        assertContains(unixMappedFns, "public expect fun strcpy(dst: MutPtr, src: ConstPtr): MutPtr")
        assertContains(unixMappedH, "char* libcUnixStrdup(const char* cs);")
        assertContains(unixMappedH, "char* libcUnixStrndup(const char* cs, uint64_t n);")
        assertContains(unixMappedH, "const char* libcUnixGetenv(const char* name);")
        assertContains(unixMappedDef, "noStringConversion = ")
        assertFalse(unixMappedNative.contains("toKString"))
        assertFalse(unixMappedNative.contains("COpaquePointer"))
        assertContains(unixMappedNative, "import io.github.kotlinmania.libc.unix.cinterop.libcUnixStrdup")
        assertContains(
            unixMappedNative,
            "public actual fun strdup(cs: ConstPtr): MutPtr =\n" +
                "    MutPtr(libcUnixStrdup(cs.rawAddress.toCPointer<ByteVar>())?.toLong() ?: 0L)",
        )
        assertContains(
            unixMappedNative,
            "public actual fun strndup(cs: ConstPtr, n: SizeT): MutPtr =\n" +
                "    MutPtr(libcUnixStrndup(cs.rawAddress.toCPointer<ByteVar>(), n)?.toLong() ?: 0L)",
        )
        for ((path, content) in unixMapped) {
            if (path.endsWith(".kt")) {
                ZeroUnderscoreIdentifierValidator.requireZeroViolations(content, path)
            }
        }

        // 3. 32-bit WASI control (--raw-abi and mapped)
        val wasiRaw = runModuleSplit("tmp/libc/src/wasi/mod.rs", rawAbi = true, pointerWidth = "32", arch = "wasm32", os = "wasi")
        val wasiRawFns = wasiRaw.getValue("/proj/src/common/commonMain/kotlin/io/github/kotlinmania/libc/wasi/WasiFunctions.kt")
        val wasiRawH = wasiRaw.getValue("/proj/src/native/interop/cinterop/wasi_wrapper.h")
        val wasiRawNative = wasiRaw.getValue("/proj/src/native/nativeMain/kotlin/io/github/kotlinmania/libc/wasi/WasiNativeActuals.kt")
        assertContains(wasiRawFns, "public expect fun strndup(cs: Long, n: size_t): Long")
        assertContains(wasiRawH, "char* libcWasiStrdup(const char* cs);")
        assertContains(wasiRawH, "char* libcWasiStrndup(const char* cs, uint32_t n);")
        assertContains(wasiRawH, "char* libcWasiCustomNdup(const char* cs, uint32_t n);")
        assertContains(wasiRawH, "int32_t libcWasiStrtol(const char* s, void* endp, int32_t base);")
        assertContains(wasiRawH, "int32_t libcWasiReadBytes(int32_t fd, void* buf, uint32_t count);")
        assertFalse(wasiRawH.contains("uint64_t"))
        assertFalse(wasiRawNative.contains("toKString"))
        assertContains(wasiRawNative, "import io.github.kotlinmania.libc.wasi.cinterop.libcWasiStrndup")
        assertContains(
            wasiRawNative,
            "public actual fun strndup(cs: Long, n: size_t): Long =\n" +
                "    libcWasiStrndup(cs.toCPointer<ByteVar>(), n)?.toLong() ?: 0L",
        )
        assertFalse(wasiRawNative.contains("n.toInt()"))
        assertFalse(wasiRawNative.contains("n.toULong()"))

        val wasiMapped = runModuleSplit("tmp/libc/src/wasi/mod.rs", rawAbi = false, pointerWidth = "32", arch = "wasm32", os = "wasi")
        val wasiMappedH = wasiMapped.getValue("/proj/src/native/interop/cinterop/wasi_wrapper.h")
        val wasiMappedNative = wasiMapped.getValue("/proj/src/native/nativeMain/kotlin/io/github/kotlinmania/libc/wasi/WasiNativeActuals.kt")
        assertContains(wasiMappedH, "char* libcWasiStrdup(const char* cs);")
        assertContains(wasiMappedH, "char* libcWasiStrndup(const char* cs, uint32_t n);")
        assertContains(wasiMappedH, "int32_t libcWasiStrtol(const char* s, void* endp, int32_t base);")
        assertFalse(wasiMappedH.contains("uint64_t"))
        assertFalse(wasiMappedNative.contains("toKString"))
        assertContains(wasiMappedNative, "import io.github.kotlinmania.libc.wasi.cinterop.libcWasiStrndup")
        assertContains(
            wasiMappedNative,
            "public actual fun strndup(cs: ConstPtr, n: SizeT): MutPtr =\n" +
                "    MutPtr(libcWasiStrndup(cs.rawAddress.toCPointer<ByteVar>(), n)?.toLong() ?: 0L)",
        )
        assertFalse(wasiMappedNative.contains("n.toULong()"))
        for ((path, content) in wasiMapped) {
            if (path.endsWith(".kt")) {
                ZeroUnderscoreIdentifierValidator.requireZeroViolations(content, path)
            }
        }

        // 4. 64-bit Windows LLP64 control: size_t is 64-bit while c_long remains 32-bit
        val winRaw = runModuleSplit("tmp/libc/src/windows/mod.rs", rawAbi = true, pointerWidth = "64", arch = "x86_64", os = "windows")
        val winRawH = winRaw.getValue("/proj/src/native/interop/cinterop/windows_wrapper.h")
        val winRawNative = winRaw.getValue("/proj/src/native/nativeMain/kotlin/io/github/kotlinmania/libc/windows/WindowsNativeActuals.kt")
        assertContains(winRawH, "char* libcWindowsStrndup(const char* cs, uint64_t n);")
        assertContains(winRawH, "char* libcWindowsCustomNdup(const char* cs, uint64_t n);")
        assertContains(winRawH, "int32_t libcWindowsStrtol(const char* s, void* endp, int32_t base);")
        assertContains(winRawNative, "import io.github.kotlinmania.libc.windows.cinterop.libcWindowsStrndup")
        assertContains(
            winRawNative,
            "public actual fun strndup(cs: Long, n: size_t): Long =\n" +
                "    libcWindowsStrndup(cs.toCPointer<ByteVar>(), n)?.toLong() ?: 0L",
        )
        assertFalse(winRawNative.contains("n.toInt()"))
    }

    @Test
    fun ffiEmitsAndroidBionicAndAppleDarwinAwareWrappersAcrossCInteropJniAndNapi() {
        val libRs =
            """
            pub type c_int = i32;
            pub type c_char = i8;
            pub type c_void = u8;
            pub type size_t = usize;
            pub type time64_t = i64;
            pub type iconv_t = *mut c_void;
            """.trimIndent()
        val appleAndBionicModRs =
            """
            use crate::{c_char, c_int, c_void, iconv_t, size_t, time64_t};

            #[repr(u32)]
            pub enum qos_class_t {
                QOS_CLASS_DEFAULT = 0x15,
                QOS_CLASS_BACKGROUND = 0x09,
            }

            #[repr(C)]
            pub struct SmallRecord {
                // param comment
                /// Field doc.
                pub flags: c_int,
            }

            extern "C" {
                pub fn atexit(cb: Option<unsafe extern "C" fn()>) -> c_int;
                pub fn timegm64(tm: *const c_void) -> time64_t;
                pub fn pthread_barrier_init(barrier: *mut c_void, attr: *const c_void, count: u32) -> c_int;
                pub fn pthread_equal(t1: usize, t2: usize) -> c_int;
            }

            #[cfg_attr(not(feature = "rustc-dep-of-std"), link(name = "iconv"))]
            extern "C" {
                pub fn iconv_open(tocode: *const c_char, fromcode: *const c_char) -> iconv_t;
            }
            """.trimIndent()
        val crateSources =
            mapOf(
                "tmp/libc/src/lib.rs" to libRs,
                "tmp/libc/src/platform/mod.rs" to appleAndBionicModRs,
            )
        val out = linkedMapOf<String, String>()
        val flags =
            arrayOf(
                "translate",
                "--force",
                "--split-module-roots",
                "--final-structs",
                "--source-root",
                "tmp/libc/src",
                "--target-pointer-width",
                "64",
                "--target-arch",
                "aarch64",
                "--target-os",
                "macos",
                "-n",
                "io.github.kotlinmania.libc",
                "-o",
                "/proj/src/common/commonMain/kotlin/io/github/kotlinmania/libc",
                "tmp/libc/src/platform/mod.rs",
            )
        runSingleFileCommand(
            args = flags,
            read = { path -> crateSources[path] ?: error("missing $path") },
            write = { path, text -> out[path] = text },
            writeForce = { path, text -> out[path] = text },
            exists = { path -> path in out },
            isDirectory = { it == "tmp/libc/src" },
            collectRustFiles = { crateSources.keys.toList() },
            report = {},
            resolveGradleProperties = { null },
            resolvePackageProperties = { null },
            resolveCrateMacroSources = { emptyList() },
            resolveCrateFlatFileModules = { _, _ -> emptySet() },
            resolveCrateRawAbiMetadata = { _, _, raw, split ->
                buildCrateRawAbiMetadataFromSources(
                    sources = crateSources.entries.map { it.key.removePrefix("tmp/libc/src/") to it.value },
                    rawAbiMode = raw,
                    splitModuleRoots = split,
                    finalStructs = TranslationSession.finalStructs,
                )
            },
        )

        val cSrc = out.getValue("/proj/src/native/interop/cinterop/platform_wrapper.c")
        val jniSrc = out.getValue("/proj/src/jvm/jvmMain/c/libc_platform_jni.c")
        val napiSrc = out.getValue("/proj/src/js/node-libc/src/libc_platform_bindings.cpp")

        // 1. atexit: <stdlib.h>, NULL/nullptr EINVAL guard, and #ifdef __ANDROID__ direct crtbegin atexit
        assertContains(cSrc, "#include <stdlib.h>")
        assertContains(cSrc, "if (cb == NULL) {\n        errno = EINVAL;\n        return -1;\n    }")
        assertContains(cSrc, "#ifdef __ANDROID__\n    return (int32_t)atexit((void (*)(void))(uintptr_t)cb);\n#else")
        assertContains(jniSrc, "#include <stdlib.h>")
        assertContains(jniSrc, "if ((void*)(uintptr_t)cb == NULL) {\n        errno = EINVAL;\n        return (jint)-1;\n    }")
        assertContains(jniSrc, "#ifdef __ANDROID__\n    return (jint)atexit((void (*)(void))(uintptr_t)cb);\n#else")
        assertContains(napiSrc, "if (cb == nullptr) {\n        errno = EINVAL;")
        assertContains(napiSrc, "std::atexit(reinterpret_cast<void (*)()>(reinterpret_cast<uintptr_t>(cb)))")

        // 2. 64-bit Android Bionic *64 -> base symbol fallback
        assertContains(
            cSrc,
            "#if defined(__ANDROID__) && defined(__LP64__)\n" +
                "        if (km_fn_ptr == NULL) {\n" +
                "            km_fn_ptr = (km_fn_t)dlsym(RTLD_DEFAULT, \"timegm\");\n" +
                "        }\n" +
                "#endif",
        )
        assertContains(jniSrc, "dlsym(RTLD_DEFAULT, \"timegm\")")
        assertContains(napiSrc, "dlsym(RTLD_DEFAULT, \"timegm\")")

        // 3. Per-extern-block #[cfg_attr(..., link(name = "iconv"))] Apple & desktop Linux dlopen fallback
        assertContains(cSrc, "dlopen(\"/usr/lib/libiconv.dylib\", RTLD_LAZY | RTLD_LOCAL)")
        assertContains(cSrc, "dlopen(\"libiconv.so\", RTLD_LAZY | RTLD_LOCAL)")
        assertContains(jniSrc, "dlopen(\"/usr/lib/libiconv.dylib\", RTLD_LAZY | RTLD_LOCAL)")
        assertContains(napiSrc, "dlopen(\"/usr/lib/libiconv.dylib\", RTLD_LAZY | RTLD_LOCAL)")
        // Scoped strictly to iconv_open (1 occurrence of /usr/lib/libiconv.dylib per file, not leaked to other fns)
        kotlin.test.assertEquals(1, cSrc.split("/usr/lib/libiconv.dylib").size - 1)
        kotlin.test.assertEquals(1, jniSrc.split("/usr/lib/libiconv.dylib").size - 1)
        kotlin.test.assertEquals(1, napiSrc.split("/usr/lib/libiconv.dylib").size - 1)

        // 4. POSIX pthread_* direct ENOSYS return + JNI/N-API errno = ENOSYS; (excluding pthread_equal)
        assertContains(cSrc, "errno = ENOSYS;\n    return ENOSYS;")
        assertContains(jniSrc, "errno = ENOSYS;\n        km_res = ENOSYS;")
        assertContains(napiSrc, "errno = ENOSYS;\n        km_res = ENOSYS;")
        val pthreadEqualC = cSrc.substringAfter("libcPlatformPthreadEqual(").substringBefore("\n}\n")
        assertContains(pthreadEqualC, "return -1;")

        // 5. ktlint-clean NativeActuals imports, multi-line enum constructor, and class-body vs param KDoc spacing
        val nativeActuals = out.getValue("/proj/src/native/nativeMain/kotlin/io/github/kotlinmania/libc/platform/PlatformNativeActuals.kt")
        assertContains(nativeActuals, "import io.github.kotlinmania.libc.platform.cinterop.libcPlatformIconvOpen")
        assertContains(nativeActuals, "    MutPtr(libcPlatformIconvOpen(tocode.rawAddress.toCPointer<ByteVar>(), fromcode.rawAddress.toCPointer<ByteVar>())?.toLong() ?: 0L)")
        for ((path, content) in out) {
            if (path.endsWith(".kt")) {
                ZeroUnderscoreIdentifierValidator.requireZeroViolations(content, path)
            }
        }
        val platformTypes = out.getValue("/proj/src/common/commonMain/kotlin/io/github/kotlinmania/libc/platform/PlatformTypes.kt")
        assertContains(
            platformTypes,
            "public enum class QosClassT(\n" +
                "    public val value: UInt,\n" +
                ") {",
        )
        val platformStructs = out.getValue("/proj/src/common/commonMain/kotlin/io/github/kotlinmania/libc/platform/PlatformStructs.kt")
        assertContains(platformStructs, "    // param comment\n    /**\n     * Field doc.")
        val classBodySample =
            ensureBlankLineBeforeKDocAfterLineComment(
                "public class KinfoProc {\n" +
                    "    // This is normally \"struct proc\".\n" +
                    "    /**\n" +
                    "     * Address of proc.\n" +
                    "     */\n" +
                    "    public var kiPaddr: COpaquePointer? = null\n" +
                    "}",
            )
        assertContains(classBodySample, "    // This is normally \"struct proc\".\n\n    /**")
    }

    @Test
    fun windowsSysSplitModuleRootsOpaquePtrLiteralsIsizeHandlesAndStringShadowing() {
        val crateSources =
            linkedMapOf(
                "tmp/windows-sys/src/lib.rs" to
                    """
                    pub mod core;
                    pub mod Foundation;
                    pub mod Kernel;
                    pub mod FileSystem;
                    """.trimIndent(),
                "tmp/windows-sys/src/core/mod.rs" to
                    """
                    pub mod literals;
                    pub type PCSTR = *const u8;
                    pub type PWSTR = *mut u16;
                    pub type PCWSTR = *const u16;
                    #[repr(C)]
                    #[derive(Clone, Copy)]
                    pub struct GUID {
                        pub data4: [u8; 8],
                    }
                    impl GUID {
                        pub const fn from_u128(uuid: u128) -> Self {
                            Self { data4: (uuid as u64).to_be_bytes() }
                        }
                    }
                    """.trimIndent(),
                "tmp/windows-sys/src/core/literals.rs" to
                    """
                    #[macro_export]
                    macro_rules! s {
                        (${'$'}s:literal) => {
                            ::core::concat!(${'$'}s, '\0').as_ptr()
                        };
                    }
                    #[macro_export]
                    macro_rules! w {
                        (${'$'}s:literal) => {
                            [0u16].as_ptr()
                        };
                    }
                    """.trimIndent(),
                "tmp/windows-sys/src/Foundation/mod.rs" to
                    """
                    #[repr(C)]
                    #[derive(Clone, Copy, Default)]
                    pub struct SIZE {
                        pub cx: i32,
                        pub cy: i32,
                    }
                    """.trimIndent(),
                "tmp/windows-sys/src/Kernel/mod.rs" to
                    """
                    #[repr(C)]
                    #[derive(Clone, Copy)]
                    pub struct STRING {
                        pub Length: u16,
                        pub Buffer: super::core::PCSTR,
                    }
                    impl Default for STRING {
                        fn default() -> Self {
                            unsafe { core::mem::zeroed() }
                        }
                    }
                    pub type HPOWERNOTIFY = isize;
                    pub type DRIVER_DISPATCH = Option<unsafe extern "system" fn(self_: *mut core::ffi::c_void) -> i32>;
                    #[repr(C)]
                    #[derive(Clone, Copy)]
                    pub struct NT_TIB {
                        pub Self_: *mut core::ffi::c_void,
                        pub ClassName: super::core::PWSTR,
                        pub Io: u32,
                        pub MajorFunction: [DRIVER_DISPATCH; 28],
                    }
                    impl Default for NT_TIB {
                        fn default() -> Self {
                            unsafe { core::mem::zeroed() }
                        }
                    }
                    windows_link::link!("kernel32.dll" "system" fn RtlFirstEntrySList(head: *const core::ffi::c_void) -> *mut core::ffi::c_void);
                    windows_link::link!("user32.dll" "system" fn RegisterPowerNotify(name: super::core::PCWSTR) -> HPOWERNOTIFY);
                    """.trimIndent(),
                "tmp/windows-sys/src/FileSystem/mod.rs" to
                    """
                    #[repr(C)]
                    #[derive(Clone, Copy, Default)]
                    pub struct SIZE {
                        pub width: u32,
                    }
                    #[repr(C)]
                    #[derive(Clone, Copy)]
                    pub struct META_HDR {
                        pub devSize: super::Foundation::SIZE,
                        pub prgActions: *mut super::core::PWSTR,
                    }
                    impl Default for META_HDR {
                        fn default() -> Self {
                            unsafe { core::mem::zeroed() }
                        }
                    }
                    pub const REG_KEY_W: super::core::PCWSTR = crate::core::literals::w!("System\\CurrentControlSet");
                    pub const REG_KEY_A: super::core::PCSTR = crate::core::literals::s!("System\\CurrentControlSet");
                    windows_link::link!("ntoskrnl.exe" "system" fn FsRtlDissectDbcs(path: super::Kernel::STRING, rest: *mut super::Kernel::STRING));
                    """.trimIndent(),
            )

        val out = linkedMapOf<String, String>()
        val flags =
            arrayOf(
                "translate",
                "-r",
                "--force",
                "--final-structs",
                "--split-module-roots",
                "--source-root",
                "tmp/windows-sys/src",
                "--target-pointer-width",
                "64",
                "--target-arch",
                "x86_64",
                "--target-os",
                "windows",
                "-n",
                "io.github.kotlinmania.windowssys",
                "-o",
                "/proj/src/common/commonMain/kotlin/io/github/kotlinmania/windowssys",
                "tmp/windows-sys/src",
            )
        runSingleFileCommand(
            args = flags,
            read = { path -> crateSources[path] ?: error("missing $path") },
            write = { path, text -> out[path] = text },
            writeForce = { path, text -> out[path] = text },
            exists = { path -> path in out },
            isDirectory = { it == "tmp/windows-sys/src" },
            collectRustFiles = { crateSources.keys.toList() },
            report = {},
            resolveGradleProperties = { null },
            resolvePackageProperties = { null },
            resolveCrateMacroSources = {
                listOf(crateSources.getValue("tmp/windows-sys/src/core/literals.rs"))
            },
            resolveCrateFlatFileModules = { _, _ -> setOf("core/literals.rs") },
            resolveCrateRawAbiMetadata = { _, _, raw, split ->
                buildCrateRawAbiMetadataFromSources(
                    sources = crateSources.entries.map { it.key.removePrefix("tmp/windows-sys/src/") to it.value },
                    rawAbiMode = raw,
                    splitModuleRoots = split,
                    finalStructs = TranslationSession.finalStructs,
                )
            },
        )

        val coreTypes = out.getValue("/proj/src/common/commonMain/kotlin/io/github/kotlinmania/windowssys/core/CoreTypes.kt")
        assertContains(coreTypes, "public typealias Pwstr = MutPtr")
        assertContains(coreTypes, "public typealias Pcwstr = ConstPtr")

        val coreStructs = out.getValue("/proj/src/common/commonMain/kotlin/io/github/kotlinmania/windowssys/core/CoreStructs.kt")
        assertFalse(coreStructs.contains(".toULong()).toULong()"), "Expected no redundant .toULong() in:\n$coreStructs")
        assertFalse(coreStructs.contains(".toULong())).toULong()"), "Expected no redundant .toULong() in:\n$coreStructs")

        val literalsKt = out.getValue("/proj/src/common/commonMain/kotlin/io/github/kotlinmania/windowssys/core/literals/Literals.kt")
        assertContains(literalsKt, "import io.github.kotlinmania.rustlang.core.ptr.ConstPtr")
        assertContains(literalsKt, "public fun s(s: String): ConstPtr = kmInternCStr(s)")
        assertContains(literalsKt, "public fun w(s: String): ConstPtr = kmInternWideStr(s)")

        val kernelTypes = out.getValue("/proj/src/common/commonMain/kotlin/io/github/kotlinmania/windowssys/kernel/KernelTypes.kt")
        assertContains(kernelTypes, "public typealias Hpowernotify = Long")
        assertContains(kernelTypes, "public operator fun invoke(selfRef: MutPtr): Int")

        val kernelStructs = out.getValue("/proj/src/common/commonMain/kotlin/io/github/kotlinmania/windowssys/kernel/KernelStructs.kt")
        assertContains(kernelStructs, "selfRefCtorArg: MutPtr")
        assertContains(kernelStructs, "public var selfRef: MutPtr = selfRefCtorArg")
        assertContains(kernelStructs, "internal set")
        assertContains(kernelStructs, "clsNameCtorArg: Pwstr")
        assertContains(kernelStructs, "public var clsName: Pwstr = clsNameCtorArg")
        assertContains(kernelStructs, "public var ioVal: UInt")
        assertContains(kernelStructs, "public var majorFunction: List<DriverDispatchFn>")
        assertContains(kernelStructs, "selfRef: MutPtr = MutPtr(0L)")
        assertContains(kernelStructs, "clsName: Pwstr = MutPtr(0L)")

        val kernelJs = out.getValue("/proj/src/js/jsMain/kotlin/io/github/kotlinmania/windowssys/kernel/KernelJsActuals.kt")
        assertContains(kernelJs, "as kotlin.String).toLong()")
        assertFalse(kernelJs.contains("as Int)"), "Expected isize handle return to bridge as 64-bit Long in JS actuals:\n$kernelJs")

        val kernelWasm = out.getValue("/proj/src/js/wasm/wasmJsMain/kotlin/io/github/kotlinmania/windowssys/kernel/KernelWasmJsActuals.kt")
        assertContains(kernelWasm, ": kotlin.String")

        val fsStructs = out.getValue("/proj/src/common/commonMain/kotlin/io/github/kotlinmania/windowssys/filesystem/FileSystemStructs.kt")
        assertContains(fsStructs, "public var devSize: io.github.kotlinmania.windowssys.foundation.Size")
        assertContains(fsStructs, "prgActionsCtorArg: MutPtr")
        assertContains(fsStructs, "public var prgActions: MutPtr = prgActionsCtorArg")
        assertContains(fsStructs, "prgActions: MutPtr = MutPtr(0L)")
        assertFalse(fsStructs.contains("Pwstr?"), "Expected no '?' on non-nullable Pwstr alias:\n$fsStructs")

        assertTrue(
            out.containsKey("/proj/src/native/interop/cinterop/km_core_intrinsics_inline.h"),
            "Expected downstream N-API batch translation to emit km_core_intrinsics_inline.h",
        )
        assertTrue(
            out.containsKey("/proj/src/js/node-windowssys/src/windowssys_kmcore_intrinsics_bindings.cpp"),
            "Expected downstream N-API batch translation to emit windowssys_kmcore_intrinsics_bindings.cpp, got keys: ${out.keys}",
        )

        val fsFunctions = out.getValue("/proj/src/common/commonMain/kotlin/io/github/kotlinmania/windowssys/filesystem/FileSystemFunctions.kt")
        assertContains(fsFunctions, "path: io.github.kotlinmania.windowssys.kernel.String")
        val fsJs = out.getValue("/proj/src/js/jsMain/kotlin/io/github/kotlinmania/windowssys/filesystem/FilesystemJsActuals.kt")
        assertContains(fsJs, "path: io.github.kotlinmania.windowssys.kernel.String")
        assertFalse(fsJs.contains("fsRtlDissectDbcs(") && fsJs.contains("): Unit ="), "Expected void unsupported actual to use block body without ': Unit':\n$fsJs")
        val fsWasmWasi = out.getValue("/proj/src/js/wasm/wasmWasiMain/kotlin/io/github/kotlinmania/windowssys/filesystem/FilesystemWasmWasiActuals.kt")
        assertContains(fsWasmWasi, "public actual fun fsRtlDissectDbcs(path: io.github.kotlinmania.windowssys.kernel.String, rest: MutPtr) {")
    }

    @Test fun splitModuleRootsCrateModuleUsePathsImportFromTheDeclaringSubpackage() {
        val sources =
            listOf(
                "lib.rs" to "pub mod cpu;\npub mod op;\npub mod shape;\npub mod tensor;\n",
                "op.rs" to "pub enum Op { Neg }\npub struct BackpropOp(Option<Op>);\n",
                "shape.rs" to "pub trait Dim {}\npub struct Shape(Vec<usize>);\n",
                "cpu/mod.rs" to "pub mod kernels;\n",
                "cpu/kernels.rs" to "pub trait VecOps {}\n",
                "tensor.rs" to
                    """
                    use crate::cpu::kernels::VecOps;
                    use crate::op::{BackpropOp, Op};
                    use crate::shape::Dim;
                    pub struct Tensor { op: BackpropOp }
                    pub fn apply<D: Dim, V: VecOps>(op: Op, dim: D, ops: V) {}
                    """.trimIndent(),
            )
        val meta = buildCrateRawAbiMetadataFromSources(sources = sources, splitModuleRoots = true)
        val tensorKt =
            renderSingleKotlinFile(
                sources.last().second,
                FileHeaderContext(
                    packageName = "io.github.kotlinmania.demo.tensor",
                    projectGroup = "io.github.kotlinmania",
                    projectNamespace = "io.github.kotlinmania.demo",
                    portLintDirective = "none",
                    crateFlatFileModules = listOf("op", "shape", "tensor"),
                    crateSymbolSubpackages = meta.symbolSubpackages,
                ),
            ).text
        assertContains(tensorKt, "import io.github.kotlinmania.demo.op.Op\n")
        assertContains(tensorKt, "import io.github.kotlinmania.demo.op.BackpropOp\n")
        assertContains(tensorKt, "import io.github.kotlinmania.demo.shape.Dim\n")
        assertContains(tensorKt, "import io.github.kotlinmania.demo.cpu.kernels.VecOps\n")
        assertFalse(tensorKt.contains("import io.github.kotlinmania.demo.Op\n"), tensorKt)
    }

    @Test fun explicitStdResultIsQualifiedWhenTheFileDeclaresItsOwnResult() {
        val source =
            """
            #[derive(Debug)]
            pub enum Error { Msg(String) }
            pub type Result<T> = std::result::Result<T, Error>;
            """.trimIndent()
        fun header(crateNamespaces: Map<String, String>) =
            FileHeaderContext(
                packageName = "io.github.kotlinmania.demo.error",
                projectGroup = "io.github.kotlinmania",
                projectNamespace = "io.github.kotlinmania.demo",
                portLintDirective = "none",
                crateNamespaces = crateNamespaces,
            )
        val stdlibKt = renderSingleKotlinFile(source, header(emptyMap())).text
        assertContains(stdlibKt, "typealias Result<T> = kotlin.Result<T>")
        val taggedKt = renderSingleKotlinFile(source, header(mapOf("core" to "org.rustlang.core"))).text
        assertContains(taggedKt, "typealias Result<T> = org.rustlang.core.result.Result<T, Error>")
    }

    @Test fun swiftExportConcreteGenericStructUnionAndEnumVariantsUseInternalConstructorAndSetter() {
        val source =
            """
            use core::ffi::c_void;

            #[repr(C)]
            pub struct iovec {
                pub iov_base: *mut c_void,
                pub iov_len: usize,
            }

            #[repr(C)]
            pub union sigval {
                pub sival_int: i32,
                pub sival_ptr: *mut c_void,
            }

            pub enum PtrEvent {
                Empty,
                Captured(*const c_void),
            }
            """.trimIndent()
        val kt =
            renderSingleKotlinFile(
                source,
                FileHeaderContext(
                    packageName = "io.github.kotlinmania.libc.unix",
                    projectGroup = "io.github.kotlinmania",
                    projectNamespace = "io.github.kotlinmania.libc",
                    crateNamespaces = mapOf("core" to "io.github.kotlinmania.rustlang.core"),
                    portLintDirective = "none",
                    finalClasses = true,
                ),
            ).text
        assertContains(kt, "public class Iovec internal constructor(")
        assertContains(kt, "iovBaseCtorArg: MutPtr")
        assertContains(kt, "public var iovBase: MutPtr = iovBaseCtorArg\n        internal set")
        assertContains(kt, "public constructor() : this(")
        assertContains(kt, "public operator fun invoke(")
        assertContains(kt, "public fun copy(")
        assertContains(kt, "public class Sigval internal constructor(")
        assertContains(kt, "sivalPtrCtorArg: MutPtr")
        assertContains(kt, "public class Captured internal constructor(valueCtorArg: ConstPtr) : PtrEvent()")
        assertFalse(Regex("""\b_[a-zA-Z0-9]|__""").containsMatchIn(kt), "Expected zero forbidden underscores in:\n$kt")

        val int128File = renderSynthesizedInt128SupportFile("io.github.kotlinmania.libc")
        assertFalse(int128File.contains("Comparable<U128>"), "Expected U128 not to inherit Comparable<U128>")
        assertFalse(int128File.contains("Comparable<I128>"), "Expected I128 not to inherit Comparable<I128>")
        assertContains(int128File, "public operator fun compareTo(other: U128): Int")
        assertContains(int128File, "public operator fun compareTo(other: I128): Int")
    }

    @Test fun unwrappedExternFnCallbackCastToUsizeLowersViaExternFnPtr() {
        val source =
            """
            #[repr(C)]
            pub union sa_u_t {
                pub sa_handler: Option<unsafe extern "C" fn(i32) -> !>,
            }

            impl PartialEq for sa_u_t {
                fn eq(&self, other: &sa_u_t) -> bool {
                    unsafe {
                        let h1 = match self.sa_handler {
                            Some(handler) => handler as usize,
                            None => 0 as usize,
                        };
                        let h2 = match other.sa_handler {
                            Some(handler) => handler as usize,
                            None => 0 as usize,
                        };
                        h1 == h2
                    }
                }
            }
            """.trimIndent()
        val kt =
            renderSingleKotlinFile(
                source,
                FileHeaderContext(
                    packageName = "io.github.kotlinmania.libc.vxworks",
                    projectGroup = "io.github.kotlinmania",
                    projectNamespace = "io.github.kotlinmania.libc",
                    crateNamespaces = mapOf("core" to "io.github.kotlinmania.rustlang.core"),
                    portLintDirective = "none",
                    finalClasses = true,
                ),
            ).text
        assertContains(kt, "import io.github.kotlinmania.rustlang.core.ffi.ExternFnPtr")
        assertContains(kt, "as? ExternFnPtr)?.rawAddress ?: error(")
        assertFalse(kt.contains("hashCode()"), "Expected zero hashCode() address fabrication in:\n$kt")
        assertFalse(Regex("""\b_[a-zA-Z0-9]|__""").containsMatchIn(kt), "Expected zero forbidden underscores in:\n$kt")
    }

    @Test fun foldNoneIdentityClosureLowersToLastOrNullWithoutNothingMismatch() {
        val source =
            """
            use proc_macro2::{Span, TokenStream};

            fn join_spans(tokens: TokenStream) -> Span {
                let mut iter = tokens.into_iter().map(|tt| tt.span());
                let Some(first) = iter.next() else {
                    return Span::call_site();
                };
                iter.fold(None, |_prev, next| Some(next))
                    .and_then(|last| first.join(last))
                    .unwrap_or(first)
            }
            """.trimIndent()
        val kt =
            renderSingleKotlinFile(
                source,
                FileHeaderContext(
                    packageName = "io.github.kotlinmania.quote",
                    projectGroup = "io.github.kotlinmania",
                    projectNamespace = "io.github.kotlinmania.quote",
                    crateNamespaces = mapOf("proc_macro2" to "io.github.kotlinmania.procmacro2"),
                    portLintDirective = "none",
                ),
            ).text
        assertContains(kt, "iter.lastOrNull()?.let({ last -> first.join(last) }) ?: first")
        assertFalse(kt.contains("iter.fold(null,"), "Expected zero untyped fold(null, ...) in:\n$kt")
    }

    @Test fun structTypeLayoutUsesFlatOffsetsPrimitiveArraysAndBodyApplyForLargeStructs() {
        val extraFields = (0 until 66).joinToString("\n") { "    pub f$it: u32," }
        val source =
            """
            pub type c_char = u8;
            pub type size_t = usize;
            pub const KI_NGROUPS: usize = 16;

            #[repr(C)]
            pub struct SmallBuf {
                pub len: size_t,
                pub name: [c_char; 8],
            }

            #[repr(C)]
            pub struct HugeProc {
                pub groups: [u32; KI_NGROUPS],
            $extraFields
            }

            extern "C" {
                pub fn touch_len(n: size_t);
            }
            """.trimIndent()
        val kt =
            renderSingleKotlinFile(
                source,
                FileHeaderContext(
                    packageName = "io.github.kotlinmania.libc",
                    projectGroup = "io.github.kotlinmania",
                    projectNamespace = "io.github.kotlinmania.libc",
                    portLintDirective = "none",
                    finalClasses = true,
                ),
            ).text
        assertContains(kt, "PrimitiveLayouts.U64).readNative(addr)")
        assertContains(kt, "PrimitiveArrayLayouts.uByteArray(8)).readNative(addr + 8L)")
        assertContains(kt, ".writeNative(addr + 8L, value.name)")
        assertContains(kt, "override fun readBytes(buf: ByteArray, offset: Int): SmallBuf")
        assertContains(kt, "PrimitiveArrayLayouts.uByteArray(8)).readBytes(buf, offset + 8)")
        assertContains(kt, "override fun writeBytes(buf: ByteArray, offset: Int, value: SmallBuf)")
        assertFalse(kt.contains(".toUByteArray()"), "Expected PrimitiveArrayLayouts directly without .toUByteArray():\n$kt")
        assertFalse(kt.contains(".toList()"), "Expected PrimitiveArrayLayouts directly without .toList():\n$kt")
        assertContains(kt, "private val off1: Long =")
        assertContains(kt, "HugeProc().apply {")
        assertFalse(kt.contains("USIZE64"), "Expected FFI size_t = ULong to use U64, not USIZE64, in:\n$kt")
        val maxDepth =
            kt.lines().maxOf { line ->
                var cur = 0
                var best = 0
                for (ch in line) {
                    if (ch == '(') {
                        cur++
                        if (cur > best) best = cur
                    } else if (ch == ')') {
                        cur--
                    }
                }
                best
            }
        kotlin.test.assertTrue(maxDepth <= 8, "Expected flat offset expressions with max paren depth <= 8, got $maxDepth in:\n$kt")
    }

    @Test fun regexInlineSubmoduleSplitLocalHelperOrderConstGenericsAndLowering() {
        val buildersRs =
            """
            struct Builder {
                pats: Vec<String>,
            }

            impl Builder {
                fn new(pat: &str) -> Builder {
                    Builder { pats: vec![pat.to_string()] }
                }
            }

            pub(crate) mod string {
                use super::Builder;
                pub struct RegexBuilder {
                    builder: Builder,
                }
                impl RegexBuilder {
                    pub fn new(pattern: &str) -> RegexBuilder {
                        RegexBuilder { builder: Builder::new(pattern) }
                    }
                }
            }

            pub(crate) mod bytes {
                use super::Builder;
                pub struct RegexBuilder {
                    builder: Builder,
                }
                impl RegexBuilder {
                    pub fn new(pattern: &str) -> RegexBuilder {
                        RegexBuilder { builder: Builder::new(pattern) }
                    }
                }
            }
            """.trimIndent()
        val buildersArtifact =
            renderSingleKotlinFile(
                buildersRs,
                FileHeaderContext(
                    packageName = "io.github.kotlinmania.regex.builders",
                    projectGroup = "io.github.kotlinmania",
                    projectNamespace = "io.github.kotlinmania.regex",
                    portLintDirective = "none",
                ),
            )
        assertContains(buildersArtifact.text, "package io.github.kotlinmania.regex.builders")
        assertContains(buildersArtifact.text, "class Builder(")
        assertFalse(buildersArtifact.text.contains("class RegexBuilder"), buildersArtifact.text)
        val companionByPath = buildersArtifact.companionFiles.associate { it.relativePath to it.content }
        val stringMod = companionByPath.getValue("string/String.kt")
        val bytesMod = companionByPath.getValue("bytes/Bytes.kt")
        assertContains(stringMod, "package io.github.kotlinmania.regex.builders.string")
        assertContains(stringMod, "import io.github.kotlinmania.regex.builders.Builder")
        assertContains(stringMod, "public open class RegexBuilder(")
        assertContains(bytesMod, "package io.github.kotlinmania.regex.builders.bytes")
        assertContains(bytesMod, "import io.github.kotlinmania.regex.builders.Builder")
        assertContains(bytesMod, "public open class RegexBuilder(")

        val loweringRs =
            """
            use alloc::borrow::Cow;
            use alloc::sync::Arc;
            use core::iter::repeat;

            pub struct Match {
                data: Vec<u8>,
            }

            impl Match {
                pub fn as_bytes(&self) -> &[u8] {
                    &self.data
                }
            }

            pub struct Captures {
                items: Vec<Option<Match>>,
            }

            impl Captures {
                pub fn extract<const N: usize>(&self) -> usize {
                    assert_eq!(N, self.items.len());
                    N
                }
            }

            impl core::fmt::Debug for Captures {
                fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
                    struct CapturesDebugMap<'a>(&'a Captures);
                    impl<'a> core::fmt::Debug for CapturesDebugMap<'a> {
                        fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
                            for (group_index, maybe_match) in self.0.items.iter().enumerate() {
                                let key = Key(group_index, None);
                                match maybe_match {
                                    None => {
                                        let _ = key;
                                        let _ = None::<()>;
                                    }
                                    Some(mat) => {
                                        let _ = Value(mat);
                                    }
                                }
                            }
                            Ok(())
                        }
                    }
                    struct Key<'a>(usize, Option<&'a str>);
                    impl<'a> core::fmt::Debug for Key<'a> {
                        fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
                            write!(f, "{}", self.0)?;
                            Ok(())
                        }
                    }
                    struct Value<'a>(&'a Match);
                    f.debug_tuple("Captures").field(&CapturesDebugMap(self)).finish()
                }
            }

            pub fn helpers<S: AsRef<str>>(
                haystack: &[u8],
                needle: u8,
                matches: &mut [bool],
                pat: S,
                arc_val: Arc<Match>,
                mat: &Match,
            ) -> String {
                let pos = haystack.iter().position(|&b| b == needle).expect("found");
                matches[pos] = true;
                let _ = mat.as_bytes();
                let _ = Arc::clone(&arc_val);
                let _ = Cow::Borrowed(pat.as_ref());
                repeat('~').take(79).collect::<String>()
            }
            """.trimIndent()
        val loweredKt =
            renderSingleKotlinFile(
                loweringRs,
                FileHeaderContext(
                    packageName = "io.github.kotlinmania.regex.bytes",
                    projectGroup = "io.github.kotlinmania",
                    projectNamespace = "io.github.kotlinmania.regex",
                    crateNamespaces = mapOf("core" to "io.github.kotlinmania.rustlang.core"),
                    portLintDirective = "none",
                ),
            ).text
        val keyIdx = loweredKt.indexOf("class Key(")
        val valIdx = loweredKt.indexOf("class Value(")
        val mapIdx = loweredKt.indexOf("class CapturesDebugMap(")
        kotlin.test.assertTrue(keyIdx in 0..<mapIdx, "Expected local class Key before CapturesDebugMap:\n$loweredKt")
        kotlin.test.assertTrue(valIdx in 0..<mapIdx, "Expected local class Value before CapturesDebugMap:\n$loweredKt")
        assertContains(loweredKt, "this.field0")
        assertFalse(loweredKt.contains("Result.failure"), loweredKt)
        assertContains(loweredKt, "public open fun extract(n: Int): Int")
        assertContains(loweredKt, "check(n == this.items.size)")
        assertContains(loweredKt, "matches: MutableList<Boolean>")
        assertContains(loweredKt, "pat: String")
        assertContains(loweredKt, "mat.asBytes()")
        assertContains(loweredKt, ".indexOfFirst({ b -> b == needle }).takeIf { it >= 0 }")
        assertContains(loweredKt, "('~').toString().repeat(79)")
    }

    @Test
    fun maybeUninitPaddingAndZippedForEachPreserveImportsAndMethodNames() {
        val typesRs =
            """
            use core::mem::MaybeUninit;

            #[repr(transparent)]
            #[derive(Clone, Copy)]
            pub(crate) struct Padding<T: Copy>(MaybeUninit<T>);

            impl<T: Copy> Default for Padding<T> {
                fn default() -> Self {
                    Self(MaybeUninit::zeroed())
                }
            }

            impl<T: Copy> Padding<T> {
                pub(crate) const fn uninit() -> Self {
                    Self(MaybeUninit::uninit())
                }
            }

            pub trait GgmlType: Sized {
                fn zeros() -> Self {
                    unsafe { core::mem::MaybeUninit::zeroed().assume_init() }
                }
            }
            """.trimIndent()
        val typesKt =
            renderSingleKotlinFile(
                typesRs,
                FileHeaderContext(
                    packageName = "io.github.kotlinmania.libc.types",
                    projectGroup = "io.github.kotlinmania",
                    projectNamespace = "io.github.kotlinmania.libc",
                    crateNamespaces = mapOf("core" to "io.github.kotlinmania.rustlang.core"),
                    rawAbiMode = true,
                    finalClasses = true,
                    portLintDirective = "none",
                ),
            ).text
        assertContains(typesKt, "import io.github.kotlinmania.rustlang.core.mem.maybeuninit.MaybeUninit")
        assertContains(typesKt, "MaybeUninit.zeroed(ObjectSlotLayout<T>())")
        assertContains(typesKt, "MaybeUninit.uninit(ObjectSlotLayout<T>())")
        assertContains(typesKt, "public fun clone(): Padding<T>")
        assertContains(typesKt, "public fun <T> default(): Padding<T>")
        assertContains(typesKt, "MaybeUninit.zeroed(ObjectSlotLayout<T>()).assumeInit()")

        val kernelsRs =
            """
            pub trait VecOps: Sized {
                fn vec_add(lhs: &[Self], rhs: &[Self], res: &mut [Self]);
                unsafe fn vec_dot(lhs: *const Self, rhs: *const Self, res: *mut Self, len: usize);
                unsafe fn vec_reduce_sum(xs: *const Self, res: *mut Self, len: usize);
            }

            impl VecOps for f32 {
                fn vec_add(lhs: &[Self], rhs: &[Self], res: &mut [Self]) {
                    #[cfg(feature = "mkl")]
                    crate::mkl::vs_add(lhs, rhs, res);
                    #[cfg(not(feature = "mkl"))]
                    lhs.iter().zip(rhs).zip(res).for_each(|((&a, &b), y)| *y = a + b)
                }
                unsafe fn vec_dot(_lhs: *const Self, _rhs: *const Self, _res: *mut Self, _len: usize) {}
                unsafe fn vec_reduce_sum(_xs: *const Self, _res: *mut Self, _len: usize) {}
            }
            """.trimIndent()
        val kernelsKt =
            renderSingleKotlinFile(
                kernelsRs,
                FileHeaderContext(
                    packageName = "io.github.kotlinmania.candle.cpu.kernels",
                    projectGroup = "io.github.kotlinmania",
                    projectNamespace = "io.github.kotlinmania.candle",
                    crateNamespaces = mapOf("core" to "io.github.kotlinmania.rustlang.core"),
                    cfgTargetProfile = CfgTargetProfile(enabledFeatures = emptySet()),
                    portLintDirective = "none",
                ),
            ).text
        assertFalse(kernelsKt.contains("vsAdd"), "Disabled #[cfg] call leaked import or body:\n$kernelsKt")
        assertContains(kernelsKt, "public fun vecOpsVecAddFloat(")
        assertContains(kernelsKt, "public fun vecOpsVecDotFloat(")
        assertContains(kernelsKt, "public fun vecOpsVecReduceSumFloat(")
        assertContains(kernelsKt, "for (i in 0 until minOf(lhs.size, rhs.size, res.size))")
        assertContains(kernelsKt, "res[i] = a + b")
    }

    @Test
    fun splitModuleRootsStripsCommentIdentifiersAndResolvesPascalCaseModulesAndConstPtrCasts() {
        val crateSources =
            linkedMapOf(
                "tmp/ws/src/core/mod.rs" to
                    """
                    pub type PCSTR = *const u8;
                    pub type PCWSTR = *const u16;
                    """.trimIndent(),
                "tmp/ws/src/Win32/System/Variant/mod.rs" to
                    """
                    #[repr(C)]
                    pub struct VARIANT {
                        pub vt: u16,
                    }
                    """.trimIndent(),
                "tmp/ws/src/Win32/Devices/Demo/mod.rs" to
                    """
                    use crate::core::PCSTR;
                    pub const RT_RCDATA: crate::core::PCWSTR = 10u16 as _;
                    pub struct HOLDER {
                        // Mentions PCSTR only in a comment, not in code!
                        pub item: super::super::System::Variant::VARIANT,
                    }
                    pub fn label_of(p: PCSTR) -> usize {
                        p as usize
                    }
                    """.trimIndent(),
            )
        val out = linkedMapOf<String, String>()
        val flags =
            arrayOf(
                "translate",
                "-r",
                "--force",
                "--final-structs",
                "--split-module-roots",
                "--source-root",
                "tmp/ws/src",
                "-n",
                "io.github.kotlinmania.ws",
                "-o",
                "/proj/src/common/commonMain/kotlin/io/github/kotlinmania/ws",
                "tmp/ws/src",
            )
        runSingleFileCommand(
            args = flags,
            read = { path -> crateSources[path] ?: error("missing $path") },
            write = { path, text -> out[path] = text },
            writeForce = { path, text -> out[path] = text },
            exists = { path -> path in out },
            isDirectory = { it == "tmp/ws/src" },
            collectRustFiles = { crateSources.keys.toList() },
            report = {},
            resolveGradleProperties = { null },
            resolvePackageProperties = { null },
            resolveCrateMacroSources = { emptyList() },
            resolveCrateFlatFileModules = { _, _ -> emptySet() },
            resolveCrateRawAbiMetadata = { _, _, raw, split ->
                buildCrateRawAbiMetadataFromSources(
                    sources = crateSources.entries.map { it.key.removePrefix("tmp/ws/src/") to it.value },
                    rawAbiMode = raw,
                    splitModuleRoots = split,
                    finalStructs = true,
                )
            },
        )
        val demoConsts = out.getValue("/proj/src/common/commonMain/kotlin/io/github/kotlinmania/ws/win32/devices/demo/DemoConstants.kt")
        assertContains(demoConsts, "import io.github.kotlinmania.ws.core.Pcwstr")
        assertContains(demoConsts, "ConstPtr(")
        assertFalse(demoConsts.contains("MutPtr"), "Expected ConstPtr for PCWSTR cast:\n$demoConsts")

        val demoStructs = out.getValue("/proj/src/common/commonMain/kotlin/io/github/kotlinmania/ws/win32/devices/demo/DemoStructs.kt")
        assertContains(demoStructs, "import io.github.kotlinmania.ws.win32.system.variant.Variant")
        assertFalse(demoStructs.contains("import io.github.kotlinmania.ws.core.Pcstr"), "Comment-only Pcstr leaked into DemoStructs:\n$demoStructs")
    }

    @Test fun normalModePrimitiveArraysAndF16StructLayoutsEmitFlatBytes() {
        val source =
            """
            #[repr(C)]
            pub struct BlockQ40 {
                pub d: f16,
                pub qs: [u8; 4],
                pub mat: [[u8; 4]; 2],
            }
            """.trimIndent()
        val kt =
            renderSingleKotlinFile(
                source,
                FileHeaderContext(
                    packageName = "io.github.kotlinmania.candle.quantized",
                    projectGroup = "io.github.kotlinmania",
                    projectNamespace = "io.github.kotlinmania.candle",
                    portLintDirective = "none",
                ),
            ).text
        assertContains(kt, "public var d: F16,")
        assertContains(kt, "public var qs: UByteArray,")
        assertContains(kt, "public var mat: List<UByteArray>,")
        assertContains(kt, "PrimitiveLayouts.F16_LAYOUT")
        assertContains(kt, "PrimitiveArrayLayouts.uByteArray(4)")
        assertContains(kt, "fixedListTypeLayout(")
        assertContains(kt, "override fun readBytes(buf: ByteArray, offset: Int): BlockQ40")
        assertContains(kt, "override fun writeBytes(buf: ByteArray, offset: Int, value: BlockQ40)")
        assertFalse(kt.contains("FixedArray<"), "Expected unboxed UByteArray instead of FixedArray in:\n$kt")
    }

    @Test fun endianByteArraysFromBytesAndCopyFromSliceLowerCleanly() {
        val source =
            """
            pub fn encode(x: u32, y: u128) -> ([u8; 4], [u8; 16]) {
                (x.to_be_bytes(), y.to_ne_bytes())
            }

            pub fn decode(buf: &[u8]) -> (u16, u32, u64, u128, f32, f64) {
                let a = u16::from_be_bytes(buf[..2].try_into().unwrap());
                let b = u32::from_le_bytes(buf[2..6].try_into().unwrap());
                let c = u64::from_ne_bytes(buf[6..14].try_into().unwrap());
                let d = u128::from_be_bytes(buf[14..30].try_into().unwrap());
                let e = f32::from_le_bytes(buf[30..34].try_into().unwrap());
                let f = f64::from_be_bytes(buf[34..42].try_into().unwrap());
                (a, b, c, d, e, f)
            }

            pub fn decode_array(arr: &[u8; 4]) -> u16 {
                u16::from_be_bytes(arr[..2].try_into().unwrap())
            }

            pub fn vendor_bytes(ebx: u32) -> [u8; 12] {
                let mut vendor_id = [0u8; 12];
                vendor_id[0..4].copy_from_slice(&ebx.to_ne_bytes());
                vendor_id
            }

            pub unsafe fn bytes_ptr() -> *const u8 {
                let bytes = [1u8, 2u8, 3u8, 4u8];
                (&bytes).as_ptr()
            }
            """.trimIndent()
        val kt =
            renderSingleKotlinFile(
                source,
                FileHeaderContext(
                    packageName = "io.github.kotlinmania.demo",
                    projectGroup = "io.github.kotlinmania",
                    projectNamespace = "io.github.kotlinmania.demo",
                    portLintDirective = "none",
                ),
            ).text
        assertContains(kt, "Pair<UByteArray, UByteArray>")
        assertContains(kt, "ubyteArrayOf((rawBits shr 24).toUByte()")
        assertContains(kt, "y.toLeBytes()")
        assertContains(kt, "U128.fromBeBytes(((buf).subList(14, 30)).toUByteArray())")
        assertContains(kt, "(arr).sliceArray(0 until 2)")
        assertContains(kt, "Float.fromBits(")
        assertContains(kt, "Double.fromBits(")
        assertContains(kt, "var vendorId = UByteArray(12) { 0u.toUByte() }")
        assertContains(kt, ".copyInto(vendorId, destinationOffset = 0)")
        assertContains(kt, "AddressableArrayLocal.fromUByteArray(ubyteArrayOf(1u.toUByte(), 2u.toUByte(), 3u.toUByte(), 4u.toUByte()))")
        assertFalse(kt.contains("FixedArray<"), "Expected unboxed UByteArray instead of FixedArray in:\n$kt")
    }

    @Test fun stdlibCoreAndAllocFfiTypesImportFromRustLangInNormalMode() {
        val source =
            """
            use core::ffi::{c_int, CStr};
            use std::os::raw::c_char;
            use alloc::ffi::CString;

            pub fn ffi_roundtrip(code: c_int, ch: c_char, s: &CStr) -> CString {
                let _ = (code, ch, s);
                todo!()
            }
            """.trimIndent()
        val kt =
            renderSingleKotlinFile(
                source,
                FileHeaderContext(
                    packageName = "io.github.kotlinmania.demo",
                    projectGroup = "io.github.kotlinmania",
                    projectNamespace = "io.github.kotlinmania.demo",
                    portLintDirective = "none",
                ),
            ).text
        assertContains(kt, "import io.github.kotlinmania.rustlang.core.ffi.primitives.CInt")
        assertContains(kt, "import io.github.kotlinmania.rustlang.core.ffi.primitives.CChar")
        assertContains(kt, "import io.github.kotlinmania.rustlang.core.ffi.cstr.CStr")
        assertContains(kt, "import io.github.kotlinmania.rustlang.alloc.ffi.cstr.CString")
        assertContains(kt, "fun ffiRoundtrip(code: CInt, ch: CChar, s: CStr): CString")
    }

    @Test fun cfgSelectTargetHasAtomicAndNonCompilingRustdocFences() {
        val source =
            """
            /// Example:
            /// ```ignore
            /// let skipped = ...;
            /// ```
            /// ```rust
            /// let a = ...;
            /// ```
            pub fn pick_exit() -> i32 {
                cfg_select! {
                    target_os = "hermit" => { 10 }
                    all(target_has_atomic = "64", target_os = "macos") => { 42 }
                    _ => { 0 }
                }
            }
            """.trimIndent()
        val kt =
            renderSingleKotlinFile(
                source,
                FileHeaderContext(
                    packageName = "io.github.kotlinmania.rustlang.std.sys.exit",
                    projectGroup = "io.github.kotlinmania",
                    projectNamespace = "io.github.kotlinmania.rustlang.std",
                    portLintDirective = "none",
                    cfgTargetProfile =
                        CfgTargetProfile(
                            pointerWidth = 64,
                            targetArch = "aarch64",
                            targetOs = "macos",
                            targetFamilies = setOf("unix"),
                        ),
                ),
            ).text
        assertContains(kt, "```ignore")
        assertContains(kt, "let skipped = ...;")
        assertContains(kt, "```kotlin")
        assertContains(kt, "return 42")
        assertFalse(kt.contains("cfgSelect("), "cfg_select! should expand statically:\n$kt")
    }

    @Test fun tupleStructDestructuringMutBorrowAndValueBinding() {
        val source =
            """
            pub struct Buf(pub Vec<u8>);

            impl Buf {
                pub fn push_byte(&mut self, b: u8) {
                    let Self(inner) = self;
                    inner.push(b);
                }

                pub fn take_len(self) -> usize {
                    let Buf(v) = self;
                    v.len()
                }
            }
            """.trimIndent()
        val kt = renderSingleKotlinFile(source).text
        assertContains(kt, "var inner by run {")
        assertContains(kt, "kotlin.properties.ReadWriteProperty<Any?, V>")
        assertContains(kt, "place({ this.value }, { this.value = it })")
        assertContains(kt, "val v = this.value")
        assertFalse(kt.contains("val (inner)"), "TupleStruct destructuring must not emit Kotlin component destructuring:\n$kt")
    }

    @Test fun hoistedLocalFnOptionInferenceEmptyListEnvAndTailUnsafeReturn() {
        val source =
            """
            use core::slice::memchr;

            pub struct Env(pub Vec<(String, String)>);
            impl Env {
                pub fn new(entries: Vec<(String, String)>) -> Self {
                    Env(entries)
                }
            }

            pub fn read_env(bytes: &[u8]) -> Env {
                unsafe {
                    let mut result = Vec::new();
                    if let Some(entry) = parse(bytes) {
                        result.push(entry);
                    }
                    return Env::new(result);
                }

                fn parse(input: &[u8]) -> Option<(String, String)> {
                    if input.is_empty() {
                        return None;
                    }
                    let pos = memchr::memchr(b'=', &input[1..]).map(|p| p + 1);
                    pos.map(|p| {
                        let head = input[..p].to_vec();
                        let _ = head;
                        (String::new(), String::new())
                    })
                }
            }

            pub fn opt_flatten(r: Result<Option<i32>, i32>) -> Option<i32> {
                r.ok().flatten()
            }
            """.trimIndent()
        val kt = renderSingleKotlinFile(source).text
        assertContains(kt, "var result = mutableListOf<Pair<String, String>>()")
        assertContains(kt, ".subList(1, (input).size)))?.let({")
        assertContains(kt, "return pos?.let({")
        assertContains(kt, "return Env.new(result)")
        assertFalse(kt.contains("return run {"), "Tail unsafe ending in return should not emit unreachable outer return:\n$kt")
        assertFalse(kt.contains(".toVec()"), "Byte slice .to_vec() should lower to .toMutableList():\n$kt")
        assertFalse(kt.contains(".flatten()"), "Option .flatten() should erase in Kotlin:\n$kt")
    }

    @Test fun ioResultPoisonErrorIntoInnerDropMapAndTraitCompanion() {
        val source =
            """
            use crate::io;

            pub struct Holder(pub Vec<u8>);

            pub trait HolderExt {
                fn from_bytes_vec(vec: Vec<u8>) -> Self;
            }

            impl HolderExt for Holder {
                fn from_bytes_vec(vec: Vec<u8>) -> Self {
                    Holder(vec)
                }
            }

            pub fn use_helpers(res: Result<(), i32>, r: io::Result<()>) -> Result<(), i32> {
                let _ = HolderExt::from_bytes_vec(Vec::new());
                let _ = r;
                res.map(drop)
            }

            pub fn poison_mapper<T>(p: PoisonError<T>) -> T {
                let f: fn(PoisonError<T>) -> T = PoisonError::into_inner;
                f(p)
            }

            pub fn trait_ref_mapper() -> fn(Vec<u8>) -> Holder {
                HolderExt::from_bytes_vec
            }
            """.trimIndent()
        val kt =
            renderSingleKotlinFile(
                source,
                FileHeaderContext(
                    packageName = "io.github.kotlinmania.rustlang.std.sample",
                    projectGroup = "io.github.kotlinmania",
                    projectNamespace = "io.github.kotlinmania.rustlang.std",
                    portLintDirective = "none",
                ),
            ).text
        assertContains(kt, "import io.github.kotlinmania.rustlang.std.io.error.Error")
        assertContains(kt, "r: Result<Unit, Error>")
        assertContains(kt, "res.map({ Unit })")
        assertContains(kt, "public companion object {")
        assertContains(kt, "public fun fromBytesVec(vec: List<UByte>): Holder = Holder.fromBytesVec(vec)")
        assertContains(kt, "{ it.intoInner() }")
        assertContains(kt, "{ HolderExt.fromBytesVec(it) }")
    }
}
