package io.github.kotlinmania.rustkotlin

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
    fun allGeneratedKmCoreCompanionKotlinFilesHaveZeroForbiddenUnderscoreIdentifiers() {
        val coreNs = "io.github.kotlinmania.rustlang.core"
        val typeLayoutKt = CoreIntrinsicFfiArtifacts.renderTypeLayoutSupportKotlin(coreNs)
        ZeroUnderscoreIdentifierValidator.requireZeroViolations(typeLayoutKt, "TypeLayout.kt")

        val companions = CoreIntrinsicFfiArtifacts.emitAllKmCoreCompanionFiles(coreNs)
        assertTrue(companions.any { it.relativePath == "km_core_intrinsics_inline.h" })
        assertTrue(companions.any { it.relativePath == "km_core_llvm_aarch64.S" })
        assertTrue(companions.any { it.relativePath == "km_core_llvm_x86_64.S" })
        for (file in companions) {
            if (file.relativePath.endsWith(".kt")) {
                ZeroUnderscoreIdentifierValidator.requireZeroViolations(file.content, file.relativePath)
            }
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
        assertContains(kt, "F16.fromFloat(1.5f)")
        assertContains(kt, "F128.fromDouble(2.5)")
        assertContains(kt, "F16.fromBits(")
        assertContains(kt, "F16.INFINITY")
        assertContains(kt, "F128.EPSILON")

        val floatSupport = renderSynthesizedFloatSupportFile(coreNs)
        assertContains(floatSupport, "class F16")
        assertContains(floatSupport, "class F128")
        ZeroUnderscoreIdentifierValidator.requireZeroViolations(floatSupport, "FloatSupport.kt")

        val typeLayout = CoreIntrinsicFfiArtifacts.renderTypeLayoutSupportKotlin(coreNs)
        assertContains(typeLayout, "import $coreNs.F16")
        assertContains(typeLayout, "import $coreNs.F128")
        assertContains(typeLayout, "F16_LAYOUT")
        assertContains(typeLayout, "F128_LAYOUT")

        val bindingGyp = CoreIntrinsicFfiArtifacts.renderKmCoreBindingGyp("kmcore")
        assertFalse(bindingGyp.contains("KM_NAPI_STANDALONE_MODULE"))

        val props =
            parseGradleProperties(
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
}
