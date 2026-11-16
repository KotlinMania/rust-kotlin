package io.github.kotlinmania.rustkotlin

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
class SingleFileTranslationTest {
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
        val files = TestFilesHarness("pub fn choose_item(flag_value: bool) -> bool { !flag_value }")
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
            val files = TestFilesHarness("pub const X: u32 = 1;")
            assertFailsWith<Exception>(args.toString()) { files.run(*args.toTypedArray()) }
            assertEquals(emptyList(), files.reads)
            assertEquals(emptyMap(), files.writes)
        }
    }

    @Test fun inspectNeverProducesOutputFiles() {
        val inspection = TestFilesHarness("pub const X: u32 = 1;")
        inspection.run("inspect", "one.rs")
        assertEquals(listOf("one.rs"), inspection.reads)
        assertEquals(emptyMap(), inspection.writes)
    }

    @Test fun unparsedInputFailsImmediatelyWithoutPassThroughFallback() {
        val files = TestFilesHarness("fn invalid( {")
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
        val helpRun = TestFilesHarness("pub const X: u32 = 1;")
        helpRun.run("--help")
        assertEquals(emptyList(), helpRun.reads)
        assertContains(helpRun.messages.single(), "Usage:")

        val versionRun = TestFilesHarness("pub const X: u32 = 1;")
        versionRun.run("--version")
        assertEquals(emptyList(), versionRun.reads)
        assertContains(versionRun.messages.single(), "rust-kotlin 0.1.0")

        val stdoutRun = TestFilesHarness("use serde_core::de::Visitor;\npub fn ping() -> i32 { 7 }")
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

        val noPortLintRun = TestFilesHarness("pub fn quiet_fn() -> bool { true }")
        noPortLintRun.run("translate", "--in-place", "--port-lint=none", "-q", "dir/quiet_case.rs")
        assertEquals(setOf("dir/QuietCase.kt"), noPortLintRun.writes.keys)
        assertEquals(emptyList(), noPortLintRun.messages)
        assertFalse(noPortLintRun.writes.getValue("dir/QuietCase.kt").contains("// port-lint:"))

        val dryRun = TestFilesHarness("pub fn check_dry() -> u8 { 1 }")
        dryRun.run("translate", "--dry-run", "sub/check_dry.rs")
        assertEquals(emptyMap(), dryRun.writes)
        assertContains(dryRun.messages.single(), "dry-run: sub/check_dry.rs -> sub/CheckDry.kt")

        val batchFiles =
            TestFilesHarness(
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
            TestFilesHarness(
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
        val written =
            runSplitCrateCommand(
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
                sources = files,
                dirRoot = "tmp/windows-sys/src",
                useCrateMetadata = false,
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
}
