package io.github.kotlinmania.rustkotlin

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class StdlibSemanticWaveCTest {
    @Test
    fun qualifiedCrateCfgSelectAndCfgIfExpandInPrimitives() {
        val source =
            """
            macro_rules! c_int_definition {
                (size_4) => {
                    pub type c_int = i32;
                };
            }
            crate::cfg_select! {
                target_pointer_width = "64" => {
                    c_int_definition!(size_4);
                }
                _ => {
                    pub type c_int = i16;
                }
            }
            crate::cfg_if! {
                if #[cfg(target_pointer_width = "64")] {
                    pub type c_long = i64;
                } else {
                    pub type c_long = i32;
                }
            }
            """.trimIndent()
        val output = translateRustSourceToKotlin(recognizeRust(source))
        assertContains(output, "public typealias CInt = Int")
        assertContains(output, "public typealias CLong = Long")
    }

    @Test
    fun reprSimdStructsReceiveDefaultsAndSimdShuffleIdxInfersUIntArray() {
        val source =
            """
            #[repr(simd)]
            #[derive(Copy, Clone)]
            pub struct I32x4(pub [i32; 4]);

            #[repr(simd)]
            pub struct SimdShuffleIdx<const LEN: usize>([u32; LEN]);

            pub fn make_shuffle_Const() -> SimdShuffleIdx<4> {
                SimdShuffleIdx([0, 1, 2, 3])
            }

            pub fn make_shuffle_mut() -> SimdShuffleIdx<4> {
                let mut output = [0; 4];
                output[0] = 3u32;
                SimdShuffleIdx(output)
            }
            """.trimIndent()
        val output =
            translateRustSourceToKotlin(
                recognizeRust(source),
                headerContext =
                    FileHeaderContext(
                        packageName = "io.github.kotlinmania.rustlang.corearch.test",
                        crateNamespaces = mapOf("core" to "io.github.kotlinmania.rustlang.core"),
                        isBatchCrateOutput = true,
                    ),
            )
        assertContains(output, "public var value: IntArray = IntArray(4)")
        assertContains(output, "uintArrayOf(0u, 1u, 2u, 3u)")
        assertContains(output, "output = UIntArray(4) { 0u }")
    }

    @Test
    fun transmutePropagatesSimdAndExplicitTypes() {
        val source =
            """
            #[repr(simd)]
            pub struct I32x4([i32; 4]);

            #[repr(simd)]
            pub struct M128i([u64; 2]);

            pub unsafe fn add_epi32(a: I32x4, b: M128i) -> M128i {
                core::mem::transmute(core::intrinsics::simd::simd_add(a, core::mem::transmute(b)))
            }
            """.trimIndent()
        val output =
            translateRustSourceToKotlin(
                recognizeRust(source),
                headerContext =
                    FileHeaderContext(
                        packageName = "io.github.kotlinmania.rustlang.corearch.x86",
                        crateNamespaces = mapOf("core" to "io.github.kotlinmania.rustlang.core"),
                    ),
            )
        assertContains(output, "transmuteWithLayouts<M128i, I32x4>(")
        assertContains(output, "transmuteWithLayouts<I32x4, M128i>(")
    }

    @Test
    fun ptrCopyNonoverlappingUnwrapsNonNullAndRustAllocUsesAlignmentAsUsize() {
        val source =
            """
            pub unsafe fn grow_copy(old_ptr: NonNull<u8>, new_ptr: NonNull<[u8]>, n: usize) {
                core::ptr::copy_nonoverlapping(old_ptr.as_ptr(), new_ptr.as_mut_ptr(), n);
            }

            pub unsafe fn alloc_with_layout(size: usize, align: core::ptr::Alignment) -> *mut u8 {
                extern "C" {
                    fn __rust_alloc(size: usize, align: usize) -> *mut u8;
                }
                __rust_alloc(size, align)
            }

            pub unsafe fn alloc_with_method(layout: Layout) -> *mut u8 {
                extern "C" {
                    fn __rust_alloc(size: usize, align: usize) -> *mut u8;
                }
                __rust_alloc(layout.size(), layout.alignment())
            }
            """.trimIndent()
        val output =
            translateRustSourceToKotlin(
                recognizeRust(source),
                headerContext =
                    FileHeaderContext(
                        packageName = "io.github.kotlinmania.rustlang.alloc.alloc",
                        crateNamespaces =
                            mapOf(
                                "core" to "io.github.kotlinmania.rustlang.core",
                                "alloc" to "io.github.kotlinmania.rustlang.alloc",
                            ),
                    ),
            )
        assertContains(output, "newPtr.asPtr()")
        assertContains(output, "typeLayoutT = PrimitiveLayouts.U8")
        assertContains(output, ".asUsize().toLong()")
    }

    @Test
    fun nestedPatternInOkAndControlFlowTryAndSelfMutAndCowLowerCleanly() {
        val source =
            """
            pub enum MixedUnit {
                Char(char),
                String(String),
            }

            pub fn unpack_char(res: core::result::Result<MixedUnit, ()>) -> Option<char> {
                match res {
                    Ok(MixedUnit::Char(c)) => Some(c),
                    _ => None,
                }
            }

            pub fn chain_cf(a: core::ops::ControlFlow<bool, ()>, b: core::ops::ControlFlow<bool, u32>) -> core::ops::ControlFlow<bool, u32> {
                a?;
                b
            }

            pub struct Handle(u32);
            impl Handle {
                pub fn step(mut self, next: Handle) -> Handle {
                    self = next;
                    self
                }
            }

            pub trait ToOwned {
                type Owned;
            }

            pub enum Cow<B>
            where
                B: ToOwned,
            {
                Borrowed(B),
                Owned(<B as ToOwned>::Owned),
            }

            impl<B: ToOwned> Cow<B> {
                pub fn is_borrowed(&self) -> bool {
                    match *self {
                        Borrowed(_) => true,
                        Owned(_) => false,
                    }
                }
            }

            pub struct PairBox {
                pub items: Vec<u32>,
            }

            pub fn make_pair_box() -> PairBox {
                PairBox { items: Default::default() }
            }
            """.trimIndent()
        val output =
            translateRustSourceToKotlin(
                recognizeRust(source),
                headerContext =
                    FileHeaderContext(
                        packageName = "io.github.kotlinmania.rustlang.alloc.test",
                        crateNamespaces =
                            mapOf(
                                "core" to "io.github.kotlinmania.rustlang.core",
                                "alloc" to "io.github.kotlinmania.rustlang.alloc",
                            ),
                    ),
            )
        assertContains(output, "val (c) = valueVal as MixedUnit.Char")
        assertContains(output, "is ControlFlow.Continue -> tryResult.value")
        assertContains(output, "ControlFlow.Break<Boolean, UInt>")
        assertContains(output, "var selfMut = this")
        assertContains(output, "selfMut = next")
        assertContains(output, "sealed class Cow<B, Owned>")
        assertContains(output, "is Cow.Owned")
        assertContains(output, "emptyList()")
        assertFalse(output.contains("Default.default()"), "Unexpected unresolved Default.default():\n$output")
    }
}
