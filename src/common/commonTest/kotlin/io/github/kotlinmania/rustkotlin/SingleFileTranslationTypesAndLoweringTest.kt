package io.github.kotlinmania.rustkotlin

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue
class SingleFileTranslationTypesAndLoweringTest {
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
            val written =
                runSplitCrateCommand(
                    args = targetArgs + extraArgs,
                    sources = mapOf("plain/config.rs" to cfgSource),
                    useCrateMetadata = false,
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
        assertContains(typesKt, "MaybeUninit.zeroed(UnresolvedTypeLayout<T>())")
        assertContains(typesKt, "MaybeUninit.uninit(UnresolvedTypeLayout<T>())")
        assertContains(typesKt, "public fun clone(): Padding<T>")
        assertContains(typesKt, "public fun <T> default(): Padding<T>")
        assertContains(typesKt, "MaybeUninit.zeroed(UnresolvedTypeLayout<T>()).assumeInit()")

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
        val out =
            runSplitCrateCommand(
                args = flags,
                sources = crateSources,
                dirRoot = "tmp/ws/src",
                metadataStripPrefix = "tmp/ws/src/",
                finalStructsOverride = true,
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
