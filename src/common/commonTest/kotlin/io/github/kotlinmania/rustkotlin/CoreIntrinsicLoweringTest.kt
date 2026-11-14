package io.github.kotlinmania.rustkotlin

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
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
}
