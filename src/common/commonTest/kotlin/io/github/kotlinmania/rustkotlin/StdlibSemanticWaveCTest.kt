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

    @Test
    fun waveC1AndC2SimdElementMaskAndPubCrateShuffleIdx() {
        val source =
            """
            pub trait SimdElement {
                type Mask;
            }

            pub struct Simd<T, const LANES: usize>(T)
            where
                T: SimdElement;

            pub(crate) struct SimdShuffleIdx<const LEN: usize>(pub(crate) [u32; LEN]);

            pub fn make_idx<T: SimdElement>(x: Simd<T, 4>) -> Simd<T, 4> {
                let _s = SimdShuffleIdx::<4>([0, 1, 2, 3]);
                x
            }
            """.trimIndent()
        val output =
            translateRustSourceToKotlin(
                recognizeRust(source),
                headerContext =
                    FileHeaderContext(
                        packageName = "io.github.kotlinmania.rustlang.coresimd.vector",
                        crateNamespaces = mapOf("core" to "io.github.kotlinmania.rustlang.core"),
                        crateStructFieldTypes = mapOf("macros.SimdShuffleIdx.value" to "UIntArray"),
                    ),
            )
        assertContains(output, "class Simd<T>")
        assertFalse(output.contains("Simd<T, Mask>"), "Unexpected SimdElement::Mask type param:\n$output")
        assertContains(output, "uintArrayOf(0u, 1u, 2u, 3u)")
    }

    @Test
    fun waveC3MacroNoneDelimiterUnwrappingFromMetavar() {
        val source =
            """
            macro_rules! t_t_l {
                (vector_signed_char) => { u8 };
                (i8) => { vector_signed_char };
            }

            macro_rules! outer_wrap {
                (${'$'}ty:ty) => {
                    pub fn wrap_fn(x: t_t_l!(${'$'}ty)) -> t_t_l!(${'$'}ty) {
                        x
                    }
                };
            }

            pub type VectorSignedChar = u8;
            outer_wrap!(vector_signed_char);
            """.trimIndent()
        val output = translateRustSourceToKotlin(recognizeRust(source))
        assertContains(output, "fun wrapFn(x: UByte): UByte")
        assertFalse(output.contains("TTL"), "Unexpected unexpanded t_t_l! macro:\n$output")
    }

    @Test
    fun waveC4IfLetAndWhileLetOkErrLowering() {
        val source =
            """
            pub fn handle(res: Result<i32, i32>) -> i32 {
                if let Ok(v) = res {
                    return v;
                }
                if let Ok(v2) = fetch_res() {
                    return v2;
                }
                while let Err(Some(e)) = next_item() {
                    if e == 0 {
                        break;
                    }
                }
                0
            }

            pub fn fetch_res() -> Result<i32, i32> {
                Ok(1)
            }

            pub fn next_item() -> Result<i32, Option<i32>> {
                Ok(1)
            }
            """.trimIndent()
        val output =
            translateRustSourceToKotlin(
                recognizeRust(source),
                headerContext =
                    FileHeaderContext(
                        packageName = "io.github.kotlinmania.rustlang.alloc.collections.test",
                        crateNamespaces =
                            mapOf(
                                "core" to "io.github.kotlinmania.rustlang.core",
                                "alloc" to "io.github.kotlinmania.rustlang.alloc",
                            ),
                    ),
            )
        assertContains(output, "if (res is Result.Ok)")
        assertContains(output, "is Result.Ok ->")
        assertFalse(output.contains("is Ok ->"), "Unqualified Ok variant match:\n$output")
        assertContains(output, "is Result.Err &&")
        assertFalse(output.contains("!is Result.Err &&"), "Broken compound !is in while-let:\n$output")
    }

    @Test
    fun waveC5AndC6aControlFlowWindowsPalAndPtrWriteResolution() {
        val cmpSource =
            """
            use crate::ops::ControlFlow::{self, Break, Continue};

            pub fn step(flag: bool) -> ControlFlow<i32, i32> {
                if flag { Break(1) } else { Continue(2) }
            }
            """.trimIndent()
        val cmpOut =
            translateRustSourceToKotlin(
                recognizeRust(cmpSource),
                headerContext =
                    FileHeaderContext(
                        packageName = "io.github.kotlinmania.rustlang.core.cmp",
                        projectNamespace = "io.github.kotlinmania.rustlang.core",
                        crateNamespaces = mapOf("core" to "io.github.kotlinmania.rustlang.core"),
                        crateSymbolSubpackages =
                            mapOf(
                                "ControlFlow" to listOf("ops.controlflow"),
                            ),
                    ),
            )
        assertContains(cmpOut, "import io.github.kotlinmania.rustlang.core.ops.controlflow.ControlFlow")
        assertContains(cmpOut, "import io.github.kotlinmania.rustlang.core.ops.controlflow.ControlFlow.Break")
        assertContains(cmpOut, "import io.github.kotlinmania.rustlang.core.ops.controlflow.ControlFlow.Continue")

        val winSource =
            """
            use crate::sys::{c, cvt};

            pub fn query() -> i32 {
                let _g = c::FOLDERID_LocalAppData;
                let _h = unsafe { c::SHGetKnownFolderPath() };
                cvt(0)
            }
            """.trimIndent()
        val winOut =
            translateRustSourceToKotlin(
                recognizeRust(winSource),
                headerContext =
                    FileHeaderContext(
                        packageName = "io.github.kotlinmania.rustlang.std.os.windows.fs.dirs",
                        projectNamespace = "io.github.kotlinmania.rustlang.std",
                        crateNamespaces =
                            mapOf(
                                "core" to "io.github.kotlinmania.rustlang.core",
                                "std" to "io.github.kotlinmania.rustlang.std",
                            ),
                        crateSymbolSubpackages =
                            mapOf(
                                "cvt" to listOf("sys.pal.unix", "sys.pal.windows"),
                                "FOLDERID_LOCAL_APP_DATA" to listOf("sys.pal.windows.c"),
                                "call" to listOf("sys.pal.windows.c.shgetknownfolderpath"),
                            ),
                        crateSubpackageRawLeafNames =
                            mapOf(
                                "sys.pal.unix.c" to "c",
                                "sys.pal.windows.c" to "c",
                                "sys.pal.windows.c.shgetknownfolderpath" to "SHGetKnownFolderPath",
                            ),
                    ),
            )
        assertFalse(winOut.contains("import io.github.kotlinmania.rustlang.std.sys.c\n"), "Illegal package import:\n$winOut")
        assertContains(winOut, "import io.github.kotlinmania.rustlang.std.sys.pal.windows.cvt")
        assertContains(winOut, "import io.github.kotlinmania.rustlang.std.sys.pal.windows.c.FOLDERID_LOCAL_APP_DATA")
        assertContains(winOut, "import io.github.kotlinmania.rustlang.std.sys.pal.windows.c.shgetknownfolderpath.call as SHGetKnownFolderPath")

        val ptrWriteSource =
            """
            use core::ptr;

            pub unsafe fn store_val<T>(p: *mut T, v: T) {
                unsafe { ptr::write(p, v); }
            }
            """.trimIndent()
        val ptrWriteOut =
            translateRustSourceToKotlin(
                recognizeRust(ptrWriteSource),
                headerContext =
                    FileHeaderContext(
                        packageName = "io.github.kotlinmania.rustlang.alloc.mem",
                        projectNamespace = "io.github.kotlinmania.rustlang.alloc",
                        crateNamespaces =
                            mapOf(
                                "core" to "io.github.kotlinmania.rustlang.core",
                                "alloc" to "io.github.kotlinmania.rustlang.alloc",
                            ),
                        externalCrateSymbolPackages =
                            mapOf(
                                "core" to
                                    mapOf(
                                        "MutPtr" to listOf("io.github.kotlinmania.rustlang.core.ptr"),
                                        "write" to
                                            listOf(
                                                "io.github.kotlinmania.rustlang.core.array",
                                                "io.github.kotlinmania.rustlang.core.ptr",
                                            ),
                                    ),
                            ),
                    ),
            )
        assertContains(ptrWriteOut, "import io.github.kotlinmania.rustlang.core.ptr.write")
        assertFalse(ptrWriteOut.contains("core.array.write"), "Unexpected core.array.write resolution:\n$ptrWriteOut")
    }

    @Test
    fun waveC6bAndC7ConstForwardingAndWasip1Collision() {
        val constSource =
            """
            impl<T, const N: usize> ExternalArr<T> {
                pub fn as_slice(&self) -> &[T] {
                    &[]
                }

                pub fn call_self(&self) -> &[T] {
                    self.as_slice()
                }
            }

            unsafe fn helper_array<T, const N: usize>(x: i32) -> i32 {
                x
            }

            pub unsafe fn outer_array<T, const N: usize>(x: i32) -> i32 {
                unsafe { helper_array(x) }
            }

            pub unsafe fn call_ext<T, const N: usize>(buf: Vec<T>) {
                let _ = MaybeUninit::array_assume_init(buf);
            }
            """.trimIndent()
        val constOut =
            translateRustSourceToKotlin(
                recognizeRust(constSource),
                headerContext =
                    FileHeaderContext(
                        packageName = "io.github.kotlinmania.rustlang.alloc.boxed",
                        crateNamespaces =
                            mapOf(
                                "core" to "io.github.kotlinmania.rustlang.core",
                                "alloc" to "io.github.kotlinmania.rustlang.alloc",
                            ),
                        externalCrateFnConstParams =
                            mapOf(
                                "MaybeUninit.arrayAssumeInit" to (2 to listOf("array", "n")),
                            ),
                    ),
            )
        assertContains(constOut, "this.asSlice(n)")
        assertContains(constOut, "helperArray(x, n)")
        assertContains(constOut, "MaybeUninit.arrayAssumeInit(buf, n)")

        val wasiSource =
            """
            pub unsafe fn sched_yield() -> i32 {
                wasi_snapshot_preview1::sched_yield()
            }

            pub mod wasi_snapshot_preview1 {
                extern "C" {
                    pub fn sched_yield() -> i32;
                }
            }
            """.trimIndent()
        val wasiOut = translateRustSourceToKotlin(recognizeRust(wasiSource))
        assertContains(wasiOut, "public fun schedYield(): Int")
        assertContains(wasiOut, "wasiSnapshotPreview1SchedYield()")
        assertContains(wasiOut, "public expect fun wasiSnapshotPreview1SchedYield(): Int")
    }
}
