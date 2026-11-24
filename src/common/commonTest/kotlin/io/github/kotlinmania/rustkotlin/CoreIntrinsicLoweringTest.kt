package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.rustlang.core.F128
import io.github.kotlinmania.rustlang.core.F16
import io.github.kotlinmania.rustlang.core.intrinsics.kmAlloc
import io.github.kotlinmania.rustlang.core.intrinsics.kmAtomicCxchgU128Pair
import io.github.kotlinmania.rustlang.core.intrinsics.kmCtpopU64
import io.github.kotlinmania.rustlang.core.intrinsics.kmDealloc
import io.github.kotlinmania.rustlang.core.intrinsics.kmLoadU64
import io.github.kotlinmania.rustlang.core.intrinsics.kmSmulhI64
import io.github.kotlinmania.rustlang.core.intrinsics.kmStoreU64
import io.github.kotlinmania.rustlang.core.intrinsics.kmUmulhU64
import io.github.kotlinmania.rustlang.core.mem.PrimitiveLayouts
import io.github.kotlinmania.rustlang.core.mem.kmCompareValues
import io.github.kotlinmania.rustlang.core.mem.linearMutSliceView
import io.github.kotlinmania.rustlang.core.mem.linearSliceView
import io.github.kotlinmania.rustlang.core.ptr.ConstPtr
import io.github.kotlinmania.rustlang.core.ptr.MutPtr
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class CoreIntrinsicLoweringTest {
    @Test
    fun memoryPointerBitMathAndAtomicIntrinsicsLowerWithoutRuntimeShims() {
        val rustSource =
            """
            pub unsafe fn roundtrip_u64(dst: *mut u64, src: *const u64) -> u64 {
                core::intrinsics::copy_nonoverlapping(src, dst, 1);
                let prev = core::intrinsics::atomic_xadd_seqcst(dst, 5);
                let cur = core::intrinsics::volatile_load(dst);
                let stepped = core::intrinsics::offset(src, 2);
                let diff = core::intrinsics::ptr_offset_from(stepped, src);
                let pop = core::intrinsics::ctpop(cur);
                let swapped = core::intrinsics::bswap(prev);
                swapped + (pop as u64) + (diff as u64)
            }

            pub unsafe fn generic_read_write<T>(dst: *mut T, src: *const T) {
                let v = core::intrinsics::read_via_copy(src);
                core::intrinsics::write_via_move(dst, v);
            }
            """.trimIndent()
        val kt = renderSingleKotlinFile(rustSource).text
        assertContains(kt, "kmCopyNonoverlapping(")
        assertContains(kt, "kmAtomicRmwU64(")
        assertContains(kt, "kmVolatileLoadU64(")
        assertContains(kt, "kmCtpopU64(")
        assertContains(kt, "kmBswapU64(")
        assertContains(kt, "typeLayoutT: TypeLayout<T>")
        assertContains(kt, "(typeLayoutT).readNative(src.rawAddress)")
        assertContains(kt, "(typeLayoutT).writeNative(dst.rawAddress, v)")
        assertFalse(kt.contains("org.rustlang.runtime"))
        assertFalse(kt.contains("COpaquePointer"))
        ZeroUnderscoreIdentifierValidator.requireZeroViolations(kt, "IntrinsicsSample.kt")
    }

    @Test
    fun rustAllocatorSymbolsLowerToKmCoreAllocatorEntryPoints() {
        val rustSource =
            """
            extern "Rust" {
                fn __rust_alloc(size: usize, align: usize) -> *mut u8;
                fn __rust_dealloc(ptr: *mut u8, size: usize, align: usize);
                fn __rust_realloc(ptr: *mut u8, old_size: usize, align: usize, new_size: usize) -> *mut u8;
                fn __rust_alloc_zeroed(size: usize, align: usize) -> *mut u8;
            }

            pub unsafe fn alloc_and_free() {
                let p = __rust_alloc(64, 8);
                let q = __rust_realloc(p, 64, 8, 128);
                __rust_dealloc(q, 128, 8);
                let z = __rust_alloc_zeroed(32, 8);
                __rust_dealloc(z, 32, 8);
            }
            """.trimIndent()
        val kt = renderSingleKotlinFile(rustSource).text
        assertContains(kt, "kmAlloc(")
        assertContains(kt, "kmRealloc(")
        assertContains(kt, "kmDealloc(")
        assertContains(kt, "kmAllocZeroed(")
        assertFalse(kt.contains("__rust_alloc"))
        ZeroUnderscoreIdentifierValidator.requireZeroViolations(kt, "AllocSymbols.kt")
    }

    @Test
    fun coreIntrinsicsExecuteDirectlyViaImportedRustLangKotlinCrate() {
        val buf = kmAlloc(64L, 16L)
        assertTrue(buf != 0L, "Expected non-null arena/heap allocation from kmAlloc")
        try {
            kmStoreU64(buf, 0x0102030405060708uL)
            val loaded = kmLoadU64(buf)
            assertEquals(0x0102030405060708uL, loaded)
            assertEquals(13u, kmCtpopU64(loaded))

            kmStoreU64(buf, 11uL)
            kmStoreU64(buf + 8L, 22uL)
            val cas1 = kmAtomicCxchgU128Pair(buf, 11uL, 22uL, 33uL, 44uL)
            assertTrue(cas1.succeeded)
            assertEquals(11uL, cas1.prevLo)
            assertEquals(22uL, cas1.prevHi)
            val cas2 = kmAtomicCxchgU128Pair(buf, 11uL, 22uL, 99uL, 99uL)
            assertFalse(cas2.succeeded)
            assertEquals(33uL, cas2.prevLo)
            assertEquals(44uL, cas2.prevHi)

            assertEquals(1uL, kmUmulhU64(ULong.MAX_VALUE, 2uL))
            assertEquals(-1L, kmSmulhI64(-2L, 2L))
        } finally {
            kmDealloc(buf, 64L, 16L)
        }
    }

    @Test
    fun stdlibCoreAndAllocConstructsLowerCleanly() {
        val rustSource =
            """
            macro_rules! compress_step {
                (${'$'}state:expr) => {
                    compress_step!(${'$'}state.0, ${'$'}state.1)
                };
                (${'$'}v0:expr, ${'$'}v1:expr) => {{
                    ${'$'}v0 = ${'$'}v0.wrapping_add(${'$'}v1);
                }};
            }

            macro_rules! apply_op {
                (${'$'}f:expr, ${'$'}x:expr) => {
                    (${'$'}f)(${'$'}x)
                };
            }

            pub trait SampleClone {
                final fn clone_fixed(&self) -> usize {
                    1
                }
            }

            #[repr(transparent)]
            pub struct WrappingVal<T>(pub T);

            impl<T: Default> Default for WrappingVal<T> {
                fn default() -> Self {
                    WrappingVal(T::default())
                }
            }

            pub unsafe fn pointer_inference_and_macro(ptr: *mut u8, s: &str) -> *mut u8 {
                let mut pair = (1u64, 2u64);
                compress_step!(pair);
                let _sum = apply_op!(|a: usize| a + 1, pair.0 as usize);
                super let _borrowed = &pair;
                let mut local_bytes: [u8; 4] = [1, 2, 3, 4];
                let _arr_ptr = local_bytes.as_mut_ptr();
                let _str_ptr = s.as_ptr() as *const usize;
                ptr.add(1) as *mut _
            }
            """.trimIndent()
        val kt = renderSingleKotlinFile(rustSource).text
        assertContains(kt, "fun cloneFixed(): Int")
        assertContains(kt, "AddressableArrayLocal")
        assertContains(kt, "constPtrFromRustCast(")
        assertFalse(kt.contains("__"))
        ZeroUnderscoreIdentifierValidator.requireZeroViolations(kt, "StdlibConstructs.kt")
    }

    @Test
    fun stdlibSubmoduleImportsAndReexportsResolveWithoutLegacyHardcodes() {
        val rustSource =
            """
            use core::iter::Peekable;
            use core::ptr::NonNull;
            use std::ptr::NonNull as StdNonNull;
            use core::mem::MaybeUninit;
            use core::cell::OnceCell;
            use crate::io::{BorrowedBuf, ErrorKind};

            pub struct Buffer<T> {
                pub uninit: MaybeUninit<T>,
                pub ptr: NonNull<T>,
                pub std_ptr: StdNonNull<T>,
                pub iter: Peekable<T>,
                pub cell: OnceCell<i32>,
                pub buf: BorrowedBuf,
                pub kind: ErrorKind,
            }
            """.trimIndent()
        val header =
            FileHeaderContext(
                packageName = "io.github.kotlinmania.rustlang.alloc.io.buffered.bufreader.buffer",
                projectGroup = "io.github.kotlinmania",
                projectNamespace = "io.github.kotlinmania.rustlang.alloc",
                crateNamespaces =
                    mapOf(
                        "core" to "io.github.kotlinmania.rustlang.core",
                        "alloc" to "io.github.kotlinmania.rustlang.alloc",
                        "std" to "io.github.kotlinmania.rustlang.std",
                    ),
                crateSymbolSubpackages =
                    mapOf(
                        "BorrowedBuf" to listOf("io.borrowedbuf"),
                    ),
                externalCrateSymbolPackages =
                    mapOf(
                        "core" to
                            mapOf(
                                "Peekable" to listOf("io.github.kotlinmania.rustlang.core.iter.adapters.peekable"),
                                "NonNull" to listOf("io.github.kotlinmania.rustlang.core.ptr.nonnull"),
                                "MaybeUninit" to listOf("io.github.kotlinmania.rustlang.core.mem.maybeuninit"),
                                "OnceCell" to listOf("io.github.kotlinmania.rustlang.core.cell.once"),
                                "ErrorKind" to listOf("io.github.kotlinmania.rustlang.core.io.error"),
                            ),
                    ),
                isBatchCrateOutput = true,
            )
        val kt = renderSingleKotlinFile(rustSource, header).text
        assertContains(kt, "import io.github.kotlinmania.rustlang.core.mem.maybeuninit.MaybeUninit")
        assertContains(kt, "import io.github.kotlinmania.rustlang.core.iter.adapters.peekable.Peekable")
        assertContains(kt, "import io.github.kotlinmania.rustlang.core.ptr.nonnull.NonNull")
        assertContains(kt, "import io.github.kotlinmania.rustlang.core.ptr.nonnull.NonNull as StdNonNull")
        assertContains(kt, "import io.github.kotlinmania.rustlang.core.cell.once.OnceCell")
        assertContains(kt, "import io.github.kotlinmania.rustlang.alloc.io.borrowedbuf.BorrowedBuf")
        assertContains(kt, "import io.github.kotlinmania.rustlang.core.io.error.ErrorKind")
        assertFalse(kt.contains("core.mem.MaybeUninit"))
        assertFalse(kt.contains("core.cell.OnceCell"))
    }

    @Test
    fun deepMethodBuilderChainMemoizesTypeInference() {
        val builderChain =
            (1..25).joinToString(
                separator = "",
                prefix = "pub fn optgroups() -> Options {\n    Options::new()",
                postfix = "\n}\n",
            ) { idx ->
                if (idx % 2 == 0) {
                    "\n        .optflag(\"f$idx\", \"flag-$idx\", \"desc $idx\")"
                } else {
                    "\n        .optopt(\"o$idx\", \"opt-$idx\", \"desc $idx\", \"VAL\")"
                }
            }
        val mark = TimeSource.Monotonic.markNow()
        val kt = renderSingleKotlinFile(builderChain).text
        val elapsedMs = mark.elapsedNow().inWholeMilliseconds
        assertTrue(elapsedMs < 5000L, "Expected deep builder chain to finish under 5000ms, took ${elapsedMs}ms")
        assertContains(kt, "optflag(\"f2\", \"flag-2\", \"desc 2\")")
        assertContains(kt, "optopt(\"o25\", \"opt-25\", \"desc 25\", \"VAL\")")
    }

    @Test
    fun comparisonAssertEqAndNonStringShouldPanicDoNotThrow() {
        val rustSource =
            """
            #[test]
            #[should_panic(expected = 123)]
            pub fn panics_and_asserts(a: i32, b: i32, c: i32, d: i32) {
                assert_eq!(a < b, c > d);
                assert_eq!(only_one);
            }
            """.trimIndent()
        val kt = renderSingleKotlinFile(rustSource).text
        assertContains(kt, "check(a < b == c > d)")
        assertContains(kt, "check(onlyOne)")
        assertContains(kt, "panicsAndAsserts")
    }

    @Test
    fun f16F128SynthesisLayoutsAndProjectFfiNameUnificationWork() {
        val rustSource =
            """
            pub fn float_ext(x: f16, y: f128) -> (u16, u128, f16, f128) {
                let a = 1.5f16;
                let b = 2.5f128;
                let c = f16::from_bits(x.to_bits());
                let inf = f16::INFINITY;
                let eps = f128::EPSILON;
                let _ = a + inf;
                let _ = b + eps;
                (c.to_bits(), y.to_bits(), c, y)
            }
            """.trimIndent()
        val coreNs = "io.github.kotlinmania.rustlang.core"
        val header =
            FileHeaderContext(
                packageName = "$coreNs.num.flt2dec",
                projectGroup = "io.github.kotlinmania",
                projectNamespace = coreNs,
                crateNamespaces = mapOf("core" to coreNs),
                isBatchCrateOutput = true,
            )
        val kt = renderSingleKotlinFile(rustSource, header).text
        assertContains(kt, "import $coreNs.F16")
        assertContains(kt, "import $coreNs.F128")
        assertFalse(kt.contains("import $coreNs.U128"))
        assertContains(kt, "F16.fromFloat(1.5f)")
        assertContains(kt, "F128.fromDouble(2.5)")
        assertContains(kt, "F16.fromBits(")
        assertContains(kt, "F16.INFINITY")
        assertContains(kt, "F128.EPSILON")
        assertFalse(kt.contains("class F16("))
        assertFalse(kt.contains("class F128("))

        assertEquals(1.5f, F16.fromFloat(1.5f).toFloat())
        assertEquals(2.5, F128.fromDouble(2.5).toDouble())
        assertEquals(2L, PrimitiveLayouts.F16_LAYOUT.sizeBytes)
        assertEquals(16L, PrimitiveLayouts.F128_LAYOUT.sizeBytes)

        val props =
            parseRustKotlinProperties(
                """
                project.group=io.github.kotlinmania
                project.namespace=io.github.kotlinmania.rustlang
                project.ffi.name=kmcore
                """.trimIndent(),
            )
        assertEquals("kmcore", props.projectFfiName)

        val ffiSource =
            """
            extern "C" {
                pub fn custom_km_probe(v: i32) -> i32;
            }
            """.trimIndent()
        val ffiTranslated =
            renderSingleKotlinFile(
                ffiSource,
                FileHeaderContext(
                    packageName = "io.github.kotlinmania.rustlang.std.sys",
                    projectGroup = "io.github.kotlinmania",
                    projectNamespace = "io.github.kotlinmania.rustlang.std",
                    crateNamespaces = mapOf("core" to coreNs),
                    projectFfiName = "kmcore",
                    isBatchCrateOutput = true,
                ),
            )
        assertTrue(ffiTranslated.companionFiles.isNotEmpty())
        assertTrue(
            ffiTranslated.companionFiles.all { it.ffiModuleName == "kmcore" },
            "Expected ffiModuleName=kmcore across companion artifacts, got ${ffiTranslated.companionFiles.map { it.relativePath to it.ffiModuleName }}",
        )
        assertTrue(
            ffiTranslated.companionFiles.any { it.relativePath.endsWith("AndroidActuals.kt") && it.content.contains("\"kmcore_jni\"") },
            "Expected Android actuals to load kmcore_jni",
        )
    }

    @Test
    fun stdlibTransliterationRewritesAsmSimdDefinitionModsNonNullDropAndLibcFallback() {
        val coreNs = "io.github.kotlinmania.rustlang.core"
        val coreArchNs = "io.github.kotlinmania.rustlang.corearch"

        val archHeader =
            FileHeaderContext(
                packageName = "$coreArchNs.x86",
                projectGroup = "io.github.kotlinmania",
                projectNamespace = coreArchNs,
                crateNamespaces = mapOf("core" to coreNs, "core_arch" to coreArchNs),
                rootSelfDirModuleAliases = setOf("core_arch"),
                rootExternalModuleReexports = mapOf("arch" to "core_arch"),
                isBatchCrateOutput = true,
            )
        val archSource =
            """
            use crate::core_arch::simd::Foo;
            use crate::arch::arm::Bar;

            pub unsafe fn pause_cpu(f: Foo, b: Bar) {
                core::arch::asm!("nop");
                core::mem::drop(f);
                core::mem::drop(b);
            }
            """.trimIndent()
        val archKt = renderSingleKotlinFile(archSource, archHeader).text
        assertContains(archKt, "import $coreArchNs.simd.Foo")
        assertContains(archKt, "import $coreArchNs.arm.Bar")
        assertFalse(archKt.contains("$coreArchNs.core_arch"))
        assertFalse(archKt.contains("$coreArchNs.arch."))
        assertContains(archKt, "Unit")
        assertFalse(archKt.contains("asm("))
        assertContains(archKt, "(f).let { }")
        assertContains(archKt, "(b).let { }")

        val coreHeader =
            FileHeaderContext(
                packageName = "$coreNs.ffi",
                projectGroup = "io.github.kotlinmania",
                projectNamespace = coreNs,
                crateNamespaces = mapOf("core" to coreNs),
                isBatchCrateOutput = true,
            )
        val coreSource =
            """
            pub type c_char = c_char_definition::c_char;

            mod c_char_definition {
                pub(super) type c_char = i8;
            }

            #[repr(transparent)]
            pub struct NonNull<T> {
                pointer: *const T,
            }

            pub unsafe fn simd_helpers<T>(a: T, b: T) -> T {
                let sum = core::intrinsics::simd::simd_add(a, b);
                let mask = core::intrinsics::simd::simd_eq::<T, u32>(sum, a);
                core::intrinsics::simd::simd_select(mask, sum, b)
            }
            """.trimIndent()
        val coreKt = renderSingleKotlinFile(coreSource, coreHeader).text
        assertContains(coreKt, "public typealias CChar = Byte")
        assertFalse(coreKt.contains("cCharDefinition"))
        assertFalse(coreKt.contains("c_char_definition"))
        assertContains(coreKt, "public val rawAddress: Long")
        assertContains(coreKt, "public val rawMeta: Long")
        assertFalse(coreKt.contains("core.intrinsics.simd"))

        val headerWithoutLibc =
            FileHeaderContext(
                packageName = "io.github.kotlinmania.rustlang.std.sys",
                projectGroup = "io.github.kotlinmania",
                projectNamespace = "io.github.kotlinmania.rustlang.std",
                crateNamespaces = mapOf("core" to coreNs),
                isBatchCrateOutput = true,
            )
        assertNull(FfiInteropCollector.resolveLibcNamespace(headerWithoutLibc))

        val libcFallbackSource =
            """
            use libc::c_void;
            pub type Sz = libc::size_t;
            pub fn noop(_p: *const c_void) {}
            """.trimIndent()
        val libcFallbackKt = renderSingleKotlinFile(libcFallbackSource, headerWithoutLibc).text
        assertFalse(libcFallbackKt.contains("import io.github.kotlinmania.libc"))
        assertFalse(libcFallbackKt.contains("import io.github.kotlinmania.rustlang.libc"))
    }

    @Test
    fun stdlibModuleAliasesExternalPathsTargetPruningAndZeroedIoctlLowerCleanly() {
        val coreNs = "io.github.kotlinmania.rustlang.core"
        val allocNs = "io.github.kotlinmania.rustlang.alloc"
        val stdNs = "io.github.kotlinmania.rustlang.std"
        val libcNs = "io.github.kotlinmania.rustlang.libc"
        val backtraceNs = "io.github.kotlinmania.rustlang.backtrace"
        val crateNamespaces =
            mapOf(
                "core" to coreNs,
                "alloc" to allocNs,
                "std" to stdNs,
                "libc" to libcNs,
                "backtrace" to backtraceNs,
            )

        // 1. Same-crate module alias & external crate submodule call (`memchr::memrchr`)
        val stdMeta =
            buildCrateRawAbiMetadataFromSources(
                sources =
                    listOf(
                        "lib.rs" to
                            """
                            #[path = "../../backtrace/src/lib.rs"]
                            mod backtrace_rs;
                            pub mod sys {
                                pub mod env {
                                    pub fn environ() -> usize { 0 }
                                }
                            }
                            """.trimIndent(),
                        "sys/mod.rs" to "pub mod env;",
                        "sys/env.rs" to "pub fn environ() -> usize { 0 }",
                        "sys/pal/hermit/mod.rs" to "pub fn dead_hermit() {}",
                        "os/wasip2/mod.rs" to "pub fn dead_wasip2() {}",
                        "os/freebsd/mod.rs" to "pub fn dead_freebsd() {}",
                        "sys/pal/unix/linux/mod.rs" to "pub fn live_linux() {}",
                    ),
                splitModuleRoots = true,
            )
        assertEquals("backtrace", stdMeta.rootExternalModuleReexports["backtrace_rs"])
        assertFalse(
            stdMeta.symbolSubpackages["deadHermit"]?.isNotEmpty() == true,
            "Expected dead hermit file to be pruned from metadata: ${stdMeta.symbolSubpackages}",
        )
        assertTrue("liveLinux" in stdMeta.symbolSubpackages)
        assertTrue(isUnsupportedTargetRustRelPath("sys/pal/hermit/mod.rs", CfgTargetProfile()))
        assertTrue(isUnsupportedTargetRustRelPath("os/wasip2/mod.rs", CfgTargetProfile()))
        assertFalse(isUnsupportedTargetRustRelPath("sys/pal/unix/linux/mod.rs", CfgTargetProfile()))
        assertFalse(isUnsupportedTargetRustRelPath("sys/pal/wasi/mod.rs", CfgTargetProfile()))

        val allocHeader =
            FileHeaderContext(
                packageName = "$allocNs.io.buffered",
                projectGroup = "io.github.kotlinmania",
                projectNamespace = allocNs,
                crateNamespaces = crateNamespaces,
                externalCrateSymbolPackages =
                    mapOf(
                        "core" to mapOf("memrchr" to listOf("$coreNs.slice.memchr")),
                    ),
                isBatchCrateOutput = true,
            )
        val allocSource =
            """
            use core::slice::memchr;
            pub fn find_last(byte: u8, haystack: &[u8]) -> Option<usize> {
                memchr::memrchr(byte, haystack)
            }
            """.trimIndent()
        val allocKt = renderSingleKotlinFile(allocSource, allocHeader).text
        assertContains(allocKt, "import $coreNs.slice.memchr.memrchr")
        assertFalse(allocKt.contains("io.github.kotlinmania.memchr"))

        // 2. Same-crate alias (`use crate::sys::env as env_imp`), external #[path] backtrace_rs, and zeroed + ioctl
        val stdHeader =
            FileHeaderContext(
                packageName = "$stdNs.sys.process.unix",
                projectGroup = "io.github.kotlinmania",
                projectNamespace = stdNs,
                crateNamespaces = crateNamespaces,
                crateSubpackageRawLeafNames = stdMeta.subpackageRawLeafNames,
                crateSymbolSubpackages = stdMeta.symbolSubpackages,
                rootExternalModuleReexports = stdMeta.rootExternalModuleReexports,
                isBatchCrateOutput = true,
            )
        val stdSource =
            """
            use crate::sys::env as env_imp;
            pub fn probe(fd: i32, _bt: crate::backtrace_rs::Frame) -> usize {
                let e = env_imp::environ();
                let mut info: libc::pidfd_info = unsafe { core::mem::zeroed() };
                let _ = unsafe { libc::ioctl(fd, libc::PIDFD_GET_INFO, &mut info) };
                #[cfg(any(target_env = "p2", miri, doc))]
                let _ = wasip2::dead();
                e
            }
            """.trimIndent()
        val stdKt = renderSingleKotlinFile(stdSource, stdHeader).text
        assertContains(stdKt, "import $stdNs.sys.env.environ")
        assertFalse(stdKt.contains("io.github.kotlinmania.envimp"))
        assertContains(stdKt, "import $backtraceNs.Frame")
        assertFalse(stdKt.contains("backtracers"))
        assertFalse(stdKt.contains("wasip2"))
        assertContains(stdKt, "PidfdInfo()")
        assertContains(stdKt, "AddressableLocal(")
        assertContains(stdKt, ".asMutPtr().rawAddress")
    }

    @Test
    fun stdlibFullBuildFixesCfgParamsConstImplLocalModAndNumericConstants() {
        val coreNs = "io.github.kotlinmania.rustlang.core"
        val stdNs = "io.github.kotlinmania.rustlang.std"

        // 1. Parameter-level #[cfg(...)] deduplicates colliding parameter names (fseeko64 pattern)
        val libcSource =
            """
            extern "C" {
                pub fn fseeko64(
                    stream: *mut u8,
                    #[cfg(any(not(target_os = "l4re"), all(target_os = "l4re", not(target_pointer_width = "64"))))]
                    offset: i64,
                    #[cfg(all(target_os = "l4re", target_pointer_width = "64"))]
                    offset: i32,
                    whence: i32,
                ) -> i32;
            }
            """.trimIndent()
        val libcKt =
            renderSingleKotlinFile(
                libcSource,
                FileHeaderContext(
                    packageName = "io.github.kotlinmania.rustlang.libc",
                    projectGroup = "io.github.kotlinmania",
                    projectNamespace = "io.github.kotlinmania.rustlang.libc",
                    isBatchCrateOutput = true,
                ),
            )
        assertEquals(1, Regex("""offset\s*:""").findAll(libcKt.text).count(), "Duplicate cfg param should be pruned:\n${libcKt.text}")

        // 2. CrateTokenAbiScanner survives `const impl` and `pub const trait` to index `OneSidedRangeBound`
        val rangeSource =
            """
            pub const trait RangeBounds<T> {
                fn start_bound(&self) -> usize;
            }
            const impl RangeBounds<usize> for usize {
                fn start_bound(&self) -> usize { *self }
            }
            pub enum OneSidedRangeBound {
                StartInclusive,
                End,
                EndInclusive,
            }
            """.trimIndent()
        val coreMeta =
            buildCrateRawAbiMetadataFromSources(
                sources = listOf("ops/range.rs" to rangeSource),
                splitModuleRoots = true,
            )
        assertContains(coreMeta.symbolSubpackages["OneSidedRangeBound"].orEmpty(), "ops.range")
        assertContains(coreMeta.symbolSubpackages["RangeBounds"].orEmpty(), "ops.range")

        // 3. Block-local inline mod `sigpipe` does not emit `public` or synthesize `io.github.kotlinmania.sigpipe`
        val unixModSource =
            """
            unsafe fn reset_sigpipe(handler: u8) -> bool {
                mod sigpipe {
                    pub const DEFAULT: u8 = 0;
                    pub const INHERIT: u8 = 1;
                }
                match handler {
                    sigpipe::DEFAULT => true,
                    sigpipe::INHERIT => false,
                    _ => false,
                }
            }
            """.trimIndent()
        val unixKt =
            renderSingleKotlinFile(
                unixModSource,
                FileHeaderContext(
                    packageName = "$stdNs.sys.pal.unix",
                    projectGroup = "io.github.kotlinmania",
                    projectNamespace = stdNs,
                    isBatchCrateOutput = true,
                ),
            ).text
        assertFalse(unixKt.contains("io.github.kotlinmania.sigpipe"), "Local mod must not leak phantom crate import:\n$unixKt")
        assertFalse(unixKt.contains("public val DEFAULT"), "Local mod const must not emit `public`:\n$unixKt")

        // 4. C numeric typedef MAX/MIN and F16/F128 IEEE-754 constants/methods avoid phantom imports
        val numConstSource =
            """
            pub fn max_vals(a: f16, b: f128) -> (usize, usize, u16, U128Bits) {
                let _ = a;
                let _ = b;
                (c_int::MAX as usize, c_uint::MAX as usize, f16::SIGN_MASK, f16::from_bits(1) as u16)
            }
            pub fn f128_mask() -> u128 {
                f128::SIGN_MASK
            }
            """.trimIndent()
        val numConstKt =
            renderSingleKotlinFile(
                numConstSource,
                FileHeaderContext(
                    packageName = "io.github.kotlinmania.rustlang.libm.math",
                    projectGroup = "io.github.kotlinmania",
                    projectNamespace = "io.github.kotlinmania.rustlang.libm",
                    crateNamespaces = mapOf("core" to coreNs),
                    isBatchCrateOutput = true,
                ),
            ).text
        assertFalse(numConstKt.contains("io.github.kotlinmania.cint"), numConstKt)
        assertFalse(numConstKt.contains("io.github.kotlinmania.cuint"), numConstKt)
        assertFalse(numConstKt.contains("io.github.kotlinmania.f16"), numConstKt)
        assertFalse(numConstKt.contains("io.github.kotlinmania.f128"), numConstKt)
        assertContains(numConstKt, "Int.MAX_VALUE")
        assertContains(numConstKt, "UInt.MAX_VALUE")
        assertContains(numConstKt, "F16.SIGN_MASK")
        assertContains(numConstKt, "F128.SIGN_MASK")
    }

    @Test
    fun rustLangCoreProvidesLayoutAndLinearSliceSupportDirectly() {
        val addr = kmAlloc(32L, 8L)
        try {
            val mutSlice = linearMutSliceView(MutPtr(addr), 4, PrimitiveLayouts.I32)
            mutSlice[0] = 10
            mutSlice[1] = 20
            mutSlice[2] = 30
            mutSlice[3] = 40
            val roSlice = linearSliceView(ConstPtr(addr), 4, PrimitiveLayouts.I32)
            assertEquals(listOf(10, 20, 30, 40), roSlice.toList())
            assertTrue(kmCompareValues(10, 20) < 0)
        } finally {
            kmDealloc(addr, 32L, 8L)
        }
    }

    @Test
    fun relativeTmpSubcrateInputResolvesConfiguredCrateNamespace() {
        val coreNs = "io.github.kotlinmania.rustlang.core"
        val ctx =
            inferFileHeaderContext(
                inputPath = "tmp/rust/library/core/src/num/uint_macros.rs",
                outputPath = "src/common/commonMain/kotlin/io/github/kotlinmania/rustlang/core/num/NumFunctions.kt",
                rustKotlinProperties =
                    RustKotlinProperties(
                        projectGroup = "io.github.kotlinmania.rustlang",
                        projectNamespace = "io.github.kotlinmania.rustlang",
                        crateNamespaces = mapOf("core" to coreNs),
                    ),
            )
        assertEquals(coreNs, ctx.projectNamespace)
        assertEquals(coreNs, ctx.crateNamespaces["core"])
    }

    @Test
    fun wave1ConstGenericTypeLayoutDefaultsAndMutableSliceSubListEmit() {
        val coreNs = "io.github.kotlinmania.rustlang.core"
        val rustSource =
            """
            #[repr(C)]
            pub struct Buf<const N: usize> {
                pub data: [u8; N],
            }

            pub fn sub(s: &mut [u8]) -> &mut [u8] {
                &mut s[1..3]
            }
            """.trimIndent()
        val kt =
            renderSingleKotlinFile(
                rustSource,
                FileHeaderContext(
                    packageName = "$coreNs.demo",
                    projectGroup = "io.github.kotlinmania",
                    projectNamespace = coreNs,
                    crateNamespaces = mapOf("core" to coreNs),
                    isBatchCrateOutput = true,
                    rawAbiMode = false,
                ),
            ).text
        assertContains(kt, ": Int = 0")
        assertContains(kt, ".subList(")
    }

    @Test
    fun ensureRustLangInLibsVersionsTomlAddsDependencyAndBundlesIdempotently() {
        val initialToml =
            """
            [versions]
            kotlin = "2.4.21"

            [libraries]
            km-io = { module = "io.github.kotlinmania:km-io", version.ref = "kotlin" }

            [bundles]
            demo-commonMain = [
              "km-io",
            ]
            """.trimIndent() + "\n"
        val updated = ensureRustLangInLibsVersionsToml(initialToml, commonMainBundleName = "demo-commonMain")
        assertContains(updated, """rustLang = "$DEFAULT_RUST_LANG_KOTLIN_VERSION"""")
        assertContains(updated, """rust-lang-kotlin = { module = "io.github.kotlinmania:rust-lang-kotlin", version.ref = "rustLang" }""")
        assertContains(updated, "demo-commonMain = [\n  \"km-io\",\n  \"rust-lang-kotlin\",\n]")
        assertContains(updated, "rust-lang-commonMain = [\n  \"rust-lang-kotlin\",\n]")
        assertEquals(updated, ensureRustLangInLibsVersionsToml(updated, commonMainBundleName = "demo-commonMain"))
    }
}
