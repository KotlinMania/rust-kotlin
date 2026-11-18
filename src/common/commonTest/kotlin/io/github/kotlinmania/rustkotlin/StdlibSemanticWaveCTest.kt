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
        assertContains(output, "val (c) = okValueVal as MixedUnit.Char")
        assertContains(output, "is ControlFlow.Continue<*, *> -> tryResult.cfContinueValue()")
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
        assertContains(output, "if (res is Result.Ok<*, *>)")
        assertContains(output, "is Result.Ok<*, *> ->")
        assertFalse(output.contains("is Ok ->"), "Unqualified Ok variant match:\n$output")
        assertContains(output, "is Result.Err<*, *> &&")
        assertFalse(output.contains("!is Result.Err<*, *> &&"), "Broken compound !is in while-let:\n$output")
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

    @Test
    fun waveC2ResultAndGenericEnumStarProjectionsWithRangeInclusive() {
        val source =
            """
            use crate::borrow::Cow;

            pub fn call_local_try<F>(f: F) -> Result<i32, i32>
            where
                F: FnOnce() -> Result<i32, i32>,
            {
                let x = f()?;
                Ok(x)
            }

            pub fn match_cow(c: Cow<'_, str>) -> usize {
                match c {
                    Cow::Borrowed(s) => s.len(),
                    Cow::Owned(o) => o.len(),
                }
            }

            pub fn check_ranges(r: core::ops::RangeInclusive<u16>) -> Vec<core::ops::RangeInclusive<u16>> {
                let _ = take_range(0x00C0..=0x00D6);
                vec![r, 0x0100..=0x017E]
            }

            pub fn take_range(r: core::ops::RangeInclusive<u16>) -> u16 {
                *r.start()
            }
            """.trimIndent()
        val out =
            translateRustSourceToKotlin(
                recognizeRust(source),
                headerContext =
                    FileHeaderContext(
                        packageName = "io.github.kotlinmania.rustlang.alloc.test",
                        projectNamespace = "io.github.kotlinmania.rustlang.alloc",
                        crateNamespaces =
                            mapOf(
                                "core" to "io.github.kotlinmania.rustlang.core",
                                "alloc" to "io.github.kotlinmania.rustlang.alloc",
                            ),
                        crateEnumVariantTypeArity =
                            mapOf(
                                "Cow.Borrowed" to 2,
                                "Cow.Owned" to 2,
                            ),
                    ),
            )
        assertContains(out, "is Result.Ok<*, *> -> tryResult.okValue()")
        assertContains(out, "is Cow.Borrowed<*, *> ->")
        assertContains(out, "is Cow.Owned<*, *> ->")
        assertContains(out, "import io.github.kotlinmania.rustlang.core.ops.range.RangeInclusive")
        assertContains(out, "takeRange(RangeInclusive(0x00C0u.toUShort(), 0x00D6u.toUShort(), false))")
        assertContains(out, "RangeInclusive(0x0100u.toUShort(), 0x017Eu.toUShort(), false)")
    }

    @Test
    fun waveC2SimdLaneAliasesBlockLocalTypesAndConstArrayDefaults() {
        val source =
            """
            #[repr(simd)]
            pub(crate) struct ShuffleBuf<const LEN: usize>(pub(crate) [u32; LEN]);

            pub fn make_simd(a: f32) -> f32x4 {
                type V = f32x4;
                type Lane = u16;
                let _bits = Lane::BITS;
                let _z = V::ZERO;
                let _s = V::splat(a);
                i32x2::new(1, 2);
                f32x4::splat(a)
            }
            """.trimIndent()
        val out =
            translateRustSourceToKotlin(
                recognizeRust(source),
                headerContext =
                    FileHeaderContext(
                        packageName = "io.github.kotlinmania.rustlang.corearch.wasm32",
                        projectNamespace = "io.github.kotlinmania.rustlang.corearch",
                        crateNamespaces = mapOf("core" to "io.github.kotlinmania.rustlang.core"),
                        crateSymbolSubpackages = mapOf("Simd" to listOf("simd")),
                    ),
            )
        assertContains(out, "import io.github.kotlinmania.rustlang.corearch.simd.Simd")
        assertFalse(out.contains("UIntArray(len)"), "Erased struct const param leaked into default:\n$out")
        assertContains(out, "UIntArray(0)")
        assertContains(out, "16u")
        assertContains(out, "Simd<Float>(List(4) { 0.0f })")
        assertContains(out, "Simd<Float>(List(4) { a })")
        assertContains(out, "Simd<Int>(listOf(1, 2))")
    }

    @Test
    fun waveC2InferCastUnitArgsBlockForeignFnAndIoErrorResultConflict() {
        val source =
            """
            use libc::c_char;
            use core::fmt::{self, Debug};

            pub type Result<T> = std::io::Result<T>;

            pub fn wrap_io<E>(v: Result<i32>, slot: &mut () ) -> Result<i32> {
                let _ = take_unit((), 0 as _);
                drop_void(0 as _);
                let _f = v?;
                unsafe {
                    extern "C" {
                        fn strlen(s: *const c_char) -> usize;
                    }
                    let _n = strlen(0 as *const c_char) + 1;
                }
                let _ = slot;
                v
            }

            pub fn take_unit(u: (), x: u32) -> u32 {
                x
            }

            pub fn drop_void(_: u32) {}

            pub struct ItemWrap;
            impl Debug for ItemWrap {
                fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
                    f.debug_tuple("ItemWrap").finish()
                }
            }
            """.trimIndent()
        val out =
            translateRustSourceToKotlin(
                recognizeRust(source),
                headerContext =
                    FileHeaderContext(
                        packageName = "io.github.kotlinmania.rustlang.std.sample",
                        projectNamespace = "io.github.kotlinmania.rustlang.std",
                        crateNamespaces =
                            mapOf(
                                "core" to "io.github.kotlinmania.rustlang.core",
                                "alloc" to "io.github.kotlinmania.rustlang.alloc",
                                "std" to "io.github.kotlinmania.rustlang.std",
                            ),
                    ),
            )
        assertContains(out, "import io.github.kotlinmania.rustlang.core.ffi.primitives.CChar")
        assertContains(out, "import io.github.kotlinmania.rustlang.core.io.error.Error")
        assertContains(out, "import io.github.kotlinmania.rustlang.core.result.Result as RustResult")
        assertContains(out, "is RustResult.Ok<*, *> -> tryResult.okValue()")
        assertContains(out, "takeUnit(Unit, 0.toUInt())")
        assertFalse(out.contains("as Unit"), "Unconstrained `as _` must not cast to Unit:\n$out")
        assertContains(out, "strlen(ConstPtr(0L)) + 1uL")
        assertContains(out, "RustResult.Ok<Unit, io.github.kotlinmania.rustlang.core.fmt.Error>(Unit)")
    }

    @Test
    fun waveC2FnOnceOutputLiftingStandaloneAssocInheritanceAndExtraBounds() {
        val source =
            """
            pub struct Boxed<T>(T);

            pub trait FnOnce<Args> {
                type Output;
                fn call_once(self, args: Args) -> Self::Output;
            }

            impl<F: FnOnce<Args>, Args> FnOnce<Args> for Boxed<F> {
                type Output = F::Output;
                fn call_once(self, args: Args) -> F::Output {
                    (self.0).call_once(args)
                }
            }

            pub trait IterTrait {
                type Item;
                fn next(&mut self) -> Option<Self::Item>;
            }

            pub trait DoubleIterTrait: IterTrait {
                fn next_back(&mut self) -> Option<Self::Item>;
            }

            pub struct ToUpper<I>(I);

            impl<I: IterTrait<Item = char>> IterTrait for ToUpper<I> {
                type Item = char;
                fn next(&mut self) -> Option<Self::Item> {
                    None
                }
            }

            impl<I: DoubleIterTrait<Item = char>> DoubleIterTrait for ToUpper<I> {
                fn next_back(&mut self) -> Option<Self::Item> {
                    None
                }
            }
            """.trimIndent()
        val out = translateRustSourceToKotlin(recognizeRust(source))
        assertFalse(out.contains("class Boxed<T>\n"), "Extra-bound FnOnce impl should stay standalone, not break class")
        assertContains(out, "public fun <Output, F : FnOnce<Args, Output>, Args> Boxed<F>.callOnce(")
        assertContains(out, "public fun <I : DoubleIterTrait<Char>> ToUpper<I>.nextBack(): Char?")
    }

    @Test
    fun waveC3TypedResultUnwrapTupleSelfDestructAssignAndCorearchPaths() {
        val source =
            """
            use crate::core_arch::simd;

            pub enum Result<T, E> {
                Ok(T),
                Err(E),
            }

            pub enum ControlFlow<B, C> {
                Break(B),
                Continue(C),
            }

            pub fn propagate_result(r: Result<u16, i32>) -> Result<u16, i32> {
                let v = r?;
                match v {
                    0 => Result::Ok(1),
                    1..=3 => Result::Ok(2),
                    _ => Result::Ok(v),
                }
            }

            pub fn propagate_cf(cf: ControlFlow<i32, u32>) -> ControlFlow<i32, u32> {
                let v = cf?;
                ControlFlow::Continue(v)
            }

            pub trait SliceIndex<T> {
                type Output;
            }

            pub trait Index<Idx> {
                type Output;
                fn index(&self, idx: Idx) -> Self::Output;
            }

            pub struct MyString(u32);

            impl<I: SliceIndex<str>> Index<I> for MyString {
                type Output = I::Output;
                fn index(&self, _idx: I) -> Self::Output {
                    loop {}
                }
            }

            pub fn inspect_pair(p: (u32, u32)) -> u32 {
                let mut a = 0u32;
                let mut b = 0u32;
                (a, b) = p;
                a + b
            }

            pub trait Not {
                type Output;
                fn not(self) -> Self::Output;
            }

            impl Not for i32 {
                type Output = i32;
                fn not(self) -> i32 {
                    !self
                }
            }

            impl Not for bool {
                type Output = bool;
                fn not(self) -> bool {
                    !self
                }
            }

            pub fn invert_byte(x: i8) -> i8 {
                !x
            }

            pub struct V4i32([i32; 4]);

            impl V4i32 {
                pub fn fmt(&self, f: &mut crate::fmt::Formatter<'_>) -> crate::fmt::Result {
                    crate::fmt::debug_simd_finish(f, "v4i32", [self.0[0]])
                }

                pub fn pair_from(a: simd::u8x16, b: simd::u8x16) -> V4i32 {
                    simd::sve_tuple_create2(a, b)
                }
            }
            """.trimIndent()
        val out =
            translateRustSourceToKotlin(
                recognizeRust(source),
                headerContext =
                    FileHeaderContext(
                        packageName = "io.github.kotlinmania.rustlang.corearch.sample",
                        projectNamespace = "io.github.kotlinmania.rustlang.corearch",
                        crateNamespaces =
                            mapOf(
                                "core" to "io.github.kotlinmania.rustlang.core",
                                "core_arch" to "io.github.kotlinmania.rustlang.corearch",
                            ),
                        rootSelfDirModuleAliases = setOf("core_arch"),
                        rootExternalModuleReexports = mapOf("fmt" to "core::fmt"),
                    ),
            )
        ZeroUnderscoreIdentifierValidator.requireZeroViolations(out, "WaveC3Sample.kt")
        assertContains(out, "public fun okValue(): T = when (this) { is Ok -> value; is Err -> error(\"not Ok\") }")
        assertContains(out, "public fun errValue(): E = when (this) { is Err -> value; is Ok -> error(\"not Err\") }")
        assertFalse(out.contains("override fun okValue"), "Result.Ok must not override okValue():\n$out")
        assertFalse(out.contains("override fun errValue"), "Result.Err must not override errValue():\n$out")
        assertContains(out, "public fun cfBreakValue(): B = when (this) { is Break -> value; is Continue -> error(\"not Break\") }")
        assertContains(out, "public fun cfContinueValue(): C = when (this) { is Continue -> value; is Break -> error(\"not Continue\") }")
        assertFalse(out.contains("override fun cfBreakValue"), "ControlFlow.Break must not override cfBreakValue():\n$out")
        assertFalse(out.contains("override fun cfContinueValue"), "ControlFlow.Continue must not override cfContinueValue():\n$out")
        assertContains(out, "is Result.Ok<*, *> -> tryResult.okValue()")
        assertContains(out, "tryResult.cfContinueValue()")
        assertContains(out, "tryResult.cfBreakValue()")
        assertContains(out, "public fun <Output, I : SliceIndex<String, Output>> MyString.index(")
        assertContains(out, "val (destructVal0, destructVal1) = p; a = destructVal0; b = destructVal1")
        assertContains(out, "0u.toUShort()")
        assertContains(out, "in 1u.toUShort()..3u.toUShort()")
        assertContains(out, "public operator fun Int.not(): Int")
        assertContains(out, "return this.inv()")
        assertFalse(out.contains("operator fun Boolean.not("), "Boolean.not must not be marked operator:\n$out")
        assertContains(out, "return (x.toInt().inv()).toByte()")
        assertContains(out, "public open fun fmt(f: StringBuilder): Result<Unit, Error>")
        assertContains(out, "val simdDbgArr =")
        assertContains(out, "Result.Ok<Unit, Error>(Unit)")
        assertFalse(out.contains("simd.sveTupleCreate2"), "sve_tuple_create2 must lower instead of raw call:\n$out")
        assertFalse(out.contains("_simdArg"), "Must not emit underscore-prefixed _simdArg locals:\n$out")
    }

    @Test
    fun `wave c4 fixes foreign fn collision subpkg module import cow arity io result bool inv ops tuple and lowercase types`() {
        val source =
            """
            use crate::core_arch::simd;
            use crate::fmt::Error;
            use crate::io::Result;

            unsafe extern "llvm-intrinsic" {
                fn msa_add_a_b(a: i32, b: i32) -> i32;
            }

            pub unsafe fn __msa_add_a_b(a: i32, b: i32) -> i32 {
                msa_add_a_b(a, b)
            }

            pub fn make_vec(x: simd::u8x16) -> simd::u8x16 {
                poly8x8_t::splat(255);
                x
            }

            pub trait ToOwned {
                type Owned;
                fn to_owned(&self) -> Self::Owned;
            }

            pub enum Cow<'a, B: 'a> where B: ToOwned {
                Borrowed(&'a B),
                Owned(<B as ToOwned>::Owned),
            }

            impl<'a, B: ToOwned> Cow<'a, B> {
                pub fn inspect_cow(&self) -> bool {
                    match *self {
                        Cow::Borrowed(_) => true,
                        Cow::Owned(_) => false,
                    }
                }
            }

            pub trait SizedTypeProperties {
                const IS_ZST: bool = false;
            }

            pub fn check_zst<T: SizedTypeProperties>() -> bool {
                if !T::IS_ZST {
                    true
                } else {
                    false
                }
            }

            pub fn read_io(x: Result<usize>) -> Result<usize> {
                let y = x?;
                Ok(y)
            }

            pub trait Shr<Rhs> {
                type Output;
                fn shr(self, rhs: Rhs) -> Self::Output;
            }

            impl Shr<i32> for i8 {
                type Output = i8;
                fn shr(self, other: i32) -> i8 {
                    self >> other
                }
            }

            impl Shr<&i32> for i8 {
                type Output = <i8 as Shr<i32>>::Output;
                fn shr(self, other: &i32) -> <i8 as Shr<i32>>::Output {
                    Shr::shr(self, *other)
                }
            }

            pub trait ShrAssign<Rhs> {
                fn shr_assign(&mut self, rhs: Rhs);
            }

            impl ShrAssign<u8> for i32 {
                fn shr_assign(&mut self, other: u8) {
                    *self >>= other;
                }
            }

            impl ShrAssign<&u8> for i32 {
                fn shr_assign(&mut self, other: &u8) {
                    ShrAssign::shr_assign(self, *other);
                }
            }

            pub trait Default {
                fn default() -> Self;
            }

            pub fn cmp_tuple<T, U, V, W, X>(a: &(T, U, V, W, X), b: &(T, U, V, W, X)) -> Ordering {
                a.4.cmp(&b.4)
            }

            pub fn pair_defaults<U: Default, T: Default>() -> (U, T) {
                ({ let x: U = Default::default(); x }, { let x: T = Default::default(); x })
            }
            """.trimIndent()
        val out =
            translateRustSourceToKotlin(
                recognizeRust(source),
                headerContext =
                    FileHeaderContext(
                        packageName = "io.github.kotlinmania.rustlang.corearch.wasm32",
                        projectNamespace = "io.github.kotlinmania.rustlang.corearch",
                        crateNamespaces =
                            mapOf(
                                "core" to "io.github.kotlinmania.rustlang.core",
                                "core_arch" to "io.github.kotlinmania.rustlang.corearch",
                            ),
                        crateSubpackageRawLeafNames =
                            mapOf(
                                "simd" to "simd",
                                "armshared.neon" to "neon",
                            ),
                        crateSymbolSubpackages =
                            mapOf(
                                "Simd" to listOf("simd"),
                                "U8x16" to listOf("simd"),
                                "Poly8x8T" to listOf("armshared.neon"),
                                "Result" to listOf("io.error"),
                            ),
                        crateTraitDefaultlessAssocTypes = mapOf("ToOwned" to listOf("Owned")),
                        rootSelfDirModuleAliases = setOf("core_arch"),
                        rootExternalModuleReexports = mapOf("fmt" to "core::fmt", "io" to "core::io"),
                    ),
            )
        ZeroUnderscoreIdentifierValidator.requireZeroViolations(out, "WaveC4Sample.kt")
        assertContains(out, "public expect fun externMsaAddAB(a: Int, b: Int): Int")
        assertContains(out, "return externMsaAddAB(a, b)")
        assertContains(out, "import io.github.kotlinmania.rustlang.corearch.simd.U8x16")
        assertContains(out, "public fun makeVec(x: U8x16): U8x16")
        assertFalse(out.contains("import io.github.kotlinmania.rustlang.corearch.simd\n"), "Must not emit package import:\n$out")
        assertContains(out, "Poly8x8T.splat(")
        assertContains(out, "is Cow.Borrowed<*, *>")
        assertContains(out, "is Cow.Owned<*, *>")
        assertContains(out, "io.github.kotlinmania.rustlang.core.io.error.Error")
        assertFalse(out.contains(".inv()"), "Boolean IS_ZST must not emit .inv():\n$out")
        assertContains(out, "public infix fun Byte.shr(other: Int): Byte")
        assertContains(out, "return (this.toInt() shr other).toByte()")
        assertFalse(out.contains("shrInt("), "Forward ref Shr<&i32> should dedup without mangling:\n$out")
        assertFalse(out.contains("shrAssignUByte("), "Forward ref ShrAssign<&u8> should dedup without mangling:\n$out")
        assertFalse(out.contains("this = "), "Must not assign to immutable Kotlin receiver this:\n$out")
        assertContains(out, "kmCompareValues((a[4] as X), (b[4] as X))")
        assertContains(out, "defaultUFn: () -> U")
        assertContains(out, "defaultTFn: () -> T")
        assertContains(out, "val x: U = defaultUFn()")
        assertContains(out, "val x1: T = defaultTFn()")
    }

    @Test
    fun `wave c5 fixes tuple ufcs component6 quote transmute arg foreign collision trait dedup unit enum cast uncheckedShl and self compound assign`() {
        val rustSource =
            """
            use crate::{ToTokens, ext::HasIterator as HasIter};
            use core::ops::{self, BitAnd};

            pub trait MyInt:
                ops::AddAssign
                + ops::Add<Output = Self>
                + ops::Shl<i32, Output = Self>
                + ops::Shl<u32, Output = Self>
            {}

            pub struct MaskPair(pub u32, pub u32);
            pub struct HasIterator;

            pub enum Feature {
                rv32i,
                zvbb,
            }

            pub struct TwoF64(pub f64, pub f64);

            pub fn check_feature_and_shift(index: i64, p: TwoF64) -> (u32, i64, f64) {
                (Feature::zvbb as u32, index.unchecked_shl(2), p.1)
            }

            pub fn test_tuple_6_from(arr: [i32; 6]) -> (i32, i32, i32, i32, i32, i32) {
                let (a, b, c, d, e, f) = arr;
                (a, b, c, d, e, f)
            }

            impl<U: PartialOrd, T: PartialOrd> PartialOrd for (U, T) {
                fn lt(&self, other: &(U, T)) -> bool {
                    PartialOrd::lt(&self.0, &other.0)
                }
            }

            pub struct V16(i8);
            pub fn simd_add<T>(a: T, b: T) -> T { a }

            pub unsafe fn msa_addvi_b<const IMM5: u32>(a: V16) -> V16 {
                extern "C" {
                    fn msa_addvi_b(a: V16, imm: i32) -> V16;
                }
                msa_addvi_b(a, IMM5 as i32)
            }

            extern "C" {
                #[link_name = "llvm.s390.v"]
                fn s390_v(a: i32, b: i32) -> i32;
            }
            pub unsafe fn s390_v(a: i32) -> i32 { a }

            pub fn call_local_vst(ptr: *mut u8) {
                extern "C" {
                    fn vst2(a: *mut u8);
                }
                unsafe { vst2(core::mem::transmute(ptr)) }
            }

            pub fn call_simd_add_transmute(a: V16, b: V16) -> V16 {
                simd_add(core::mem::transmute(a), b)
            }

            pub fn emit_tok(t: HasIter) -> HasIter {
                ToTokens::to_tokens(t);
                t
            }

            pub struct MyCow(pub i32);
            impl ops::AddAssign<i32> for MyCow {
                fn add_assign(&mut self, rhs: i32) { self.0 += rhs; }
            }
            impl ops::Add<i32> for MyCow {
                type Output = MyCow;
                fn add(mut self, rhs: i32) -> MyCow {
                    self += rhs;
                    self
                }
            }
            """
        val out =
            translateRustSourceToKotlin(
                recognizeRust(rustSource.trimIndent()),
                headerContext =
                    FileHeaderContext(
                        packageName = "io.github.kotlinmania.rustlang.procmacro.quote",
                        projectNamespace = "io.github.kotlinmania.rustlang.procmacro",
                        crateNamespaces =
                            mapOf(
                                "core" to "io.github.kotlinmania.rustlang.core",
                                "proc_macro" to "io.github.kotlinmania.rustlang.procmacro",
                            ),
                        crateSubpackageRawLeafNames =
                            mapOf(
                                "quote" to "quote",
                                "totokens" to "totokens",
                            ),
                        crateSymbolSubpackages =
                            mapOf(
                                "HasIterator" to listOf("quote"),
                                "ToTokens" to listOf("totokens"),
                            ),
                        externalCrateSymbolPackages =
                            mapOf(
                                "core" to
                                    mapOf(
                                        "AddAssign" to listOf("io.github.kotlinmania.rustlang.core.ops.arith"),
                                        "Add" to listOf("io.github.kotlinmania.rustlang.core.ops.arith"),
                                        "Shl" to listOf("io.github.kotlinmania.rustlang.core.ops.bit"),
                                        "BitAnd" to listOf("io.github.kotlinmania.rustlang.core.ops.bit"),
                                    ),
                            ),
                    ),
            )
        ZeroUnderscoreIdentifierValidator.requireZeroViolations(out, "WaveC5Sample.kt")
        assertContains(out, "import io.github.kotlinmania.rustlang.core.mem.component6")
        assertContains(out, "Feature.Zvbb")
        assertFalse(out.contains("Feature::zvbb"), "Must resolve lowercase enum variant to Feature.Zvbb:\n$out")
        assertContains(out, "public fun toUInt(): UInt = kmDiscriminant().toUInt()")
        assertContains(out, "(index shl 2)")
        assertContains(out, "p.field1")
        assertContains(out, "kmPartialCompareValues(this.first, other.first)")
        assertContains(out, "public expect fun externS390V(a: Int, b: Int): Int")
        assertContains(out, "public interface MyInt : AddAssign<MyInt>, Add<MyInt, MyInt>, Shl<Int, MyInt>")
        assertFalse(out.contains("Shl<UInt"), "Must deduplicate Shl supertrait instantiations:\n$out")
        assertFalse(out.contains("ToTokens.ToTokens("), "Multi-segment ToTokens::to_tokens must not emit ToTokens.ToTokens:\n$out")
        assertFalse(out.contains("UnresolvedTypeLayout<T>"), "Homogeneous binary simdAdd transmute must not leak callee generic T:\n$out")
        assertFalse(out.contains("UnresolvedTypeLayout<Unit>"), "Call argument transmute must not fall back to enclosing Unit return:\n$out")
        assertFalse(Regex("""\bHasIter\b""").containsMatchIn(out), "Same-package use rename HasIter must rewrite to HasIterator without shadowing:\n$out")
        assertContains(out, "public fun emitTok(t: HasIterator): HasIterator")
        assertContains(out, "this.addAssign(rhs)")
    }

    @Test
    fun `wave c6 fixes tokenTree outer qualification forceResult typeParam rename maskElement Exclusion intShadow localForeignRename sveInto maybeUninit default and primitiveTryInto`() {
        val rustSource =
            """
            pub struct Group(u32);
            pub struct Punct(char);

            pub enum TokenTree {
                Group(Group),
                Punct(Punct),
            }

            pub fn check_tt(t: TokenTree) -> bool {
                match t {
                    TokenTree::Group(_) => true,
                    TokenTree::Punct(_) => false,
                }
            }

            pub enum ForceResult<Leaf, Internal> {
                Leaf(Leaf),
                Internal(Internal),
            }

            pub trait MaskElement {
                type Mask;
                type Unsigned;
            }

            pub struct Mask<T, const N: usize>(T)
            where
                T: MaskElement;

            pub trait Shl<Rhs = Self> {
                type Output;
            }

            pub trait Int: Shl<i32, Output = Self> {
                fn checked_val(&self, x: i32) -> i32;
            }

            pub struct SvboolT(bool);
            pub struct Svbool2T(bool);
            pub struct Svbool4T(bool);
            pub struct Svfloat32T(f32);
            pub trait SveInto<T> {
                fn sve_into(self) -> T;
            }
            impl SveInto<Svbool2T> for SvboolT {
                fn sve_into(self) -> Svbool2T { Svbool2T(self.0) }
            }
            impl SveInto<Svbool4T> for SvboolT {
                fn sve_into(self) -> Svbool4T { Svbool4T(self.0) }
            }

            pub unsafe fn svabd_f32_m(pg: SvboolT) -> Svfloat32T {
                extern "C" {
                    fn svabd_f32_m(pg: Svbool4T) -> Svfloat32T;
                }
                unsafe { svabd_f32_m(pg.sve_into()) }
            }

            pub struct MaybeUninit<T>(T);
            impl<T> MaybeUninit<T> {
                pub fn uninit() -> MaybeUninit<T> {
                    loop {}
                }
            }

            pub struct TryFromIntError(u8);
            pub trait TryFrom<T> {
                type Error;
                fn try_from(value: T) -> Result<Self, Self::Error>;
            }

            impl TryFrom<u16> for u8 {
                type Error = TryFromIntError;
                fn try_from(u: u16) -> Result<Self, TryFromIntError> {
                    Ok(u as u8)
                }
            }

            pub fn narrow_u8(value: u16) -> Option<u8> {
                value.try_into().ok()
            }

            pub fn widen_u16(value: u8) -> u16 {
                value.try_into().unwrap()
            }
            """
        val out =
            translateRustSourceToKotlin(
                recognizeRust(rustSource.trimIndent()),
                headerContext =
                    FileHeaderContext(
                        packageName = "io.github.kotlinmania.rustlang.procmacro",
                        projectNamespace = "io.github.kotlinmania.rustlang.procmacro",
                        crateNamespaces =
                            mapOf(
                                "core" to "io.github.kotlinmania.rustlang.core",
                                "proc_macro" to "io.github.kotlinmania.rustlang.procmacro",
                            ),
                        crateEnumVariantTypeArity =
                            mapOf(
                                "TokenTree.Group" to 0,
                                "Group" to 2,
                            ),
                    ),
            )
        ZeroUnderscoreIdentifierValidator.requireZeroViolations(out, "WaveC6Sample.kt")
        assertContains(out, "public var value: io.github.kotlinmania.rustlang.procmacro.Group")
        assertContains(out, "is TokenTree.Group -> true")
        assertFalse(out.contains("is TokenTree.Group<*"), "Non-generic TokenTree.Group must not receive star projections:\n$out")
        assertContains(out, "public sealed class ForceResult<LeafParam, InternalParam>")
        assertContains(out, "public data class Leaf<LeafParam, InternalParam>(public var value: LeafParam) : ForceResult<LeafParam, InternalParam>()")
        assertContains(out, "public open class Mask<T>(")
        assertContains(out, "where T : MaskElement<Any?, Any?>")
        assertFalse(out.contains("class Mask<T, Mask"), "MaskElement Mask associated type must not collide with struct Mask:\n$out")
        assertContains(out, "public interface Int : Shl<kotlin.Int, Int>")
        assertFalse(out.contains("Shl<Int,"))
        assertContains(out, "public fun checkedVal(x: kotlin.Int): kotlin.Int")
        assertContains(out, "public open fun sveIntoSvbool4T(): Svbool4T")
        assertContains(out, "fun externSvabdF32M(pg1: Svbool4T): Svfloat32T")
        assertContains(out, "externSvabdF32M(pg.sveIntoSvbool4T())")
        assertContains(out, "public fun <T> uninit(typeLayoutT: TypeLayout<T> = UnresolvedTypeLayout()): MaybeUninit<T>")
        assertContains(out, "public fun UByte.Companion.tryFrom(u: UShort): Result<UByte, TryFromIntError>")
        assertContains(out, "UByte.tryFrom(value).ok()")
        assertContains(out, "Result.Ok<UShort, kotlin.Nothing>(UShort.from(value)).unwrap()")
    }

    @Test
    fun waveC7SimdPeerInferenceGenericUnitVariantsConstParamsAndExperimentalBitwise() {
        val rustSource =
            """
            #![allow(unused)]

            pub struct I32x4(i32, i32, i32, i32);
            pub struct U32x4(u32, u32, u32, u32);
            extern "C" {
                fn simd_add(a: U32x4, b: U32x4) -> U32x4;
                fn _mm512_mask_cmp_epu16_mask<const IMM: i32>(k: u16, a: U32x4, b: U32x4) -> u16;
            }
            pub const _MM_CMPINT_LT: i32 = 1;

            pub unsafe fn va_sc_bc(a: I32x4, b: U32x4) -> U32x4 {
                core::intrinsics::transmute(simd_add(core::intrinsics::transmute(a), b))
            }

            pub unsafe fn call_cmp(k: u16, a: U32x4, b: U32x4) -> u16 {
                _mm512_mask_cmp_epu16_mask::<_MM_CMPINT_LT>(k, a, b)
            }

            pub enum Bound<T> {
                Included(T),
                Excluded(T),
                Unbounded,
            }

            pub fn check_bound(b: Bound<i32>) -> Bound<i32> {
                match b {
                    Bound::Unbounded => Bound::Unbounded,
                    Bound::Included(_) => Bound::Unbounded,
                    Bound::Excluded(_) => b,
                }
            }

            pub trait Iterator {
                type Item;
                fn next(&mut self) -> Option<Self::Item>;
            }

            pub trait DoubleEndedIterator: Iterator {
                fn next_back(&mut self) -> Option<Self::Item>;
            }

            pub fn iterate_back<I: DoubleEndedIterator<Item = i32>>(it: &mut I) -> Option<i32> {
                it.next_back()
            }

            pub struct Libm<T>(core::marker::PhantomData<T>);

            impl Libm<f32> {
                pub fn acos(x: f32) -> f32 { x }
            }

            pub struct Simd<T, const N: usize> {
                data: [T; N],
            }

            impl<T, const N: usize> Simd<T, N> {
                pub fn splat(value: T) -> Simd<T, N> {
                    loop {}
                }

                pub fn next_chunk<const M: usize>(&self) -> usize {
                    M
                }
            }

            pub fn make_simd(x: i32, b: i8) -> i8 {
                let s = Simd::<i32, 4>::splat(x);
                let _c = s.next_chunk::<4>();
                if !(!b == 0) {
                    const_assert!(b != 0, "non-zero");
                } else {
                    const_panic!("zero");
                }
                !b
            }
            """
        val out =
            translateRustSourceToKotlin(
                recognizeRust(rustSource.trimIndent()),
                headerContext =
                    FileHeaderContext(
                        packageName = "io.github.kotlinmania.rustlang.core",
                        projectNamespace = "io.github.kotlinmania.rustlang.core",
                        crateNamespaces = mapOf("core" to "io.github.kotlinmania.rustlang.core"),
                    ),
            )
        ZeroUnderscoreIdentifierValidator.requireZeroViolations(out, "WaveC7Sample.kt")
        assertContains(out, "import kotlin.experimental.inv")
        assertContains(out, "is Bound.Unbounded<*> -> Bound.Unbounded()")
        assertContains(out, "public open class Libm<T>(public var value: Unit)")
        assertContains(out, "public companion object")
        assertContains(out, "public fun Libm.Companion.acos(x: Float): Float")
        assertContains(out, "public val n: Int = 0")
        assertContains(out, "public fun <T> splat(value: T, n: Int = 0): Simd<T>")
        assertContains(out, "s.nextChunk(4)")
        assertContains(out, "check(b != 0.toByte()) { \"non-zero\" }")
        assertContains(out, "error(\"zero\")")
        assertContains(out, "mm512MaskCmpEpu16Mask(k, a, b, MM_CMPINT_LT)")
        assertContains(out, "simdAdd(transmuteWithLayouts<I32x4, U32x4>(a, I32x4.typeLayout(), U32x4.typeLayout()), b)")
        assertFalse(out.contains("Iterator<Any?, Any?>"), "Sibling Iterator + DoubleEndedIterator must not duplicate Item:\n$out")
    }

    @Test
    fun waveC8SelfParameterizedTraitsCfgSelectNofmtConstErrorWriteFmtAndTupleTransmute() {
        val rustSource =
            """
            #![allow(unused)]

            use core::sync::atomic::Ordering::Relaxed;

            pub struct VectorSignedChar(i32);
            pub struct VectorUnsignedChar(u32);

            pub fn vec_sel_pair(x: PairArg, y: i32) -> (VectorUnsignedChar, i32) {
                (core::intrinsics::transmute(x), y)
            }

            pub struct PairArg(i32);

            pub fn read_all_dyn(r: &mut dyn Read) -> i32 {
                0
            }

            pub fn make_err(f: &mut Formatter) -> core::fmt::Result {
                let _ord = Relaxed;
                f.write_fmt(format_args!("hello"))?;
                let _e = const_error!(ErrorKind::OutOfMemory, "oom");
                Ok(())
            }

            cfg_select_nofmt! {
                _ => {
                    pub type CmpResult = i32;
                }
            }

            pub fn use_cmp_result(x: CmpResult) -> CmpResult {
                x
            }
            """
        val out =
            translateRustSourceToKotlin(
                recognizeRust(rustSource.trimIndent()),
                headerContext =
                    FileHeaderContext(
                        packageName = "io.github.kotlinmania.rustlang.alloc.io",
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
                                        "Ordering" to listOf("io.github.kotlinmania.rustlang.core.sync.atomic"),
                                    ),
                            ),
                    ),
            )
        ZeroUnderscoreIdentifierValidator.requireZeroViolations(out, "WaveC8Sample.kt")
        assertContains(out, "import io.github.kotlinmania.rustlang.core.sync.atomic.Ordering.Relaxed")
        assertContains(out, "r: Read<*>")
        assertContains(out, "f.append(")
        assertContains(out, "Error.fromStaticMessage(SimpleMessage(kind = ErrorKind.OutOfMemory, message = \"oom\"))")
        assertContains(out, "public typealias CmpResult = Int")
        assertContains(out, "Pair(transmuteWithLayouts<PairArg, VectorUnsignedChar>(x")
        assertFalse(out.contains("Pair<VectorUnsignedChar, Int>>("), "Tuple transmute must target element type:\n$out")
    }

    @Test
    fun waveC9ResultNonErasingOkValueRawPtrSharedBorrowAtomicSliceCloneAndMutVec() {
        val rustSource =
            """
            use alloc::vec::Vec;
            use core::fmt::Write;
            use core::ptr::NonNull;
            use core::ptr::is_dangling;
            use crate::os::raw as raw_mod;

            pub struct Holder<T>(T);

            pub trait MyClone {
                fn my_clone(&self) -> Self;
            }

            impl<T: ?Sized> MyClone for *const T {
                fn my_clone(&self) -> *const T {
                    *self
                }
            }

            pub trait ArrayReader {
                fn read_bytes<const N: usize>(&mut self) -> [u8; N];
            }

            pub struct MyReader;

            impl core::io::Read for MyReader {
                fn read(&mut self, buf: &mut [u8]) -> core::io::Result<usize> {
                    let _ = buf.len();
                    Ok(0)
                }
            }

            pub struct MyWrap(pub i32);

            impl MyWrap {
                pub fn div(self, rhs: Self) -> Self {
                    MyWrap(self.0 / rhs.0)
                }
            }

            pub fn push_and_check(
                buf: &mut Vec<u8>,
                s: &str,
                flag: &core::sync::atomic::AtomicBool,
                ptr: NonNull<u8>,
                f: &mut core::fmt::Formatter,
                iter: &mut dyn Iterator<Item = Result<Holder<i32>, core::fmt::Error>>,
            ) -> i32 {
                let empty = String::new();
                let _n = empty.len() + s.len() + buf.len() + iter.len();
                let _b = flag.load(std::sync::atomic::Ordering::Acquire);
                let c: raw_mod::c_int = 0;
                let _cloned = ptr.clone();
                let _ = is_dangling(ptr.as_ptr());
                let _ = Write::write_fmt(f, format_args!("ok"));
                let opt: Option<u16> = Some(7);
                let Some(val_u16) = opt else { return 0; };
                while let Some(res) = iter.next() {
                    while let Ok(inner) = res {
                        let _h: Holder<i32> = inner;
                        break;
                    }
                    break;
                }
                let ch: u8 = b'a';
                let (len, max_len, out_ptr) = if s.is_empty() {
                    (0, 2, ptr.as_ptr())
                } else {
                    let len = s.len();
                    (len * 2, 4, ptr.as_ptr())
                };
                let _len_u16: u16 = len as _;
                let _ = (max_len, out_ptr);
                if ch == 97 { val_u16 as i32 } else { 0 }
            }
            """
        val out =
            translateRustSourceToKotlin(
                recognizeRust(rustSource.trimIndent()),
                headerContext =
                    FileHeaderContext(
                        packageName = "io.github.kotlinmania.rustlang.std.sample",
                        projectNamespace = "io.github.kotlinmania.rustlang.std",
                        crateNamespaces =
                            mapOf(
                                "core" to "io.github.kotlinmania.rustlang.core",
                                "alloc" to "io.github.kotlinmania.rustlang.alloc",
                                "std" to "io.github.kotlinmania.rustlang.std",
                            ),
                    ),
            )
        ZeroUnderscoreIdentifierValidator.requireZeroViolations(out, "WaveC9Sample.kt")
        assertContains(out, "import io.github.kotlinmania.rustlang.core.clone.clone")
        assertContains(out, "import io.github.kotlinmania.rustlang.core.ffi.primitives.CInt")
        assertContains(out, "import io.github.kotlinmania.rustlang.core.slice.len")
        assertContains(out, "import io.github.kotlinmania.rustlang.core.str.len")
        assertContains(out, "import io.github.kotlinmania.rustlang.core.sync.atomic.Ordering")
        assertContains(out, "import io.github.kotlinmania.rustlang.core.sync.atomic.load")
        assertContains(out, "buf: MutableList<UByte>")
        assertContains(out, "public fun ConstPtr.myClone(): ConstPtr {\n    return this\n}")
        assertFalse(out.contains("readNative((this).rawAddress)"), "Shared-borrow *self on raw ptr must not deref:\n$out")
        assertContains(out, "public fun readBytes(n: Int = 0)")
        assertContains(out, "public override fun read(buf: MutableList<UByte>)")
        assertContains(out, "public open operator fun div(rhs: MyWrap): MyWrap")
        assertContains(out, "val empty = \"\"")
        assertContains(out, "f.append(")
        assertContains(out, "Ordering.Acquire")
        assertContains(out, "val c: CInt = 0")
        assertFalse(out.contains("import io.github.kotlinmania.rustlang.std.os.raw"), "os.raw module alias import must be stripped:\n$out")
        assertContains(out, "isDangling((ptr.asPtr()).asConstPtr())")
        assertContains(out, "val ch: UByte = 97u.toUByte()")
        assertContains(out, "ch == 97u.toUByte()")
        assertContains(out, "len1.toUShort()")
        assertFalse(out.contains(" as Result.Ok<*, *>"), "Result.Ok must not erase payload via star cast:\n$out")
        assertFalse(out.contains(" as RustResult.Ok<*, *>"), "RustResult.Ok must not erase payload via star cast:\n$out")
    }

    @Test
    fun waveC10ConcreteAtomicOpsForwardingCharBoolCastTypeParamConstAndFmtLowering() {
        val atomicAndFmtSrc =
            """
            use core::fmt::{self, Display};
            use core::sync::atomic::{Atomic, Ordering};

            pub trait Float {
                const BITS: u32;
            }

            pub struct Counter {
                pub inner: Atomic<usize>,
                pub total: Atomic<u64>,
            }

            impl Counter {
                pub fn bump(&self, c: char, flag: bool) -> bool {
                    let fresh = AtomicU64::new(0);
                    self.total.store(0, Ordering::Relaxed);
                    let bits = c as u32 + (flag as usize) as u32;
                    self.total.load(Ordering::Relaxed) == 0 && fresh.load(Ordering::Relaxed) == 0 && bits > 0
                }
            }

            pub fn read_bits<F: Float>(a: F) -> u32 {
                F::BITS
            }

            pub struct Holder {
                pub val_u32: u32,
            }

            impl Display for Holder {
                fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
                    if self.val_u32 == 0 {
                        return "bad input".fmt(f);
                    }
                    f.debug_struct("Holder").field("val_u32", &self.val_u32).finish()
                }
            }
            """.trimIndent()
        val coreNs = "io.github.kotlinmania.rustlang.core"
        val out =
            renderSingleKotlinFile(
                atomicAndFmtSrc,
                FileHeaderContext(
                    portLintSourcePath = "probe/wave10.rs",
                    packageName = "$coreNs.probe",
                    projectGroup = "io.github.kotlinmania",
                    projectNamespace = coreNs,
                    crateNamespaces = mapOf("core" to coreNs),
                    scalarChar = false,
                    rawAbiMode = false,
                    isBatchCrateOutput = true,
                ),
            ).text
        ZeroUnderscoreIdentifierValidator.requireZeroViolations(out, "WaveC10Sample.kt")
        assertContains(out, "public var inner: AtomicUsize")
        assertContains(out, "public var total: AtomicU64")
        assertContains(out, "AtomicU64.new(0uL)")
        assertContains(out, "this.total.store(0uL, Ordering.Relaxed)")
        assertContains(out, "this.total.load(Ordering.Relaxed) == 0uL")
        assertContains(out, "c.code.toUInt()")
        assertContains(out, "(if (flag) 1 else 0)")
        assertContains(out, "public fun <F : Float> readBits(a: F): UInt = a.BITS")
        assertContains(out, "return run { f.append(\"bad input\"); Result.Ok<Unit, Error>(Unit) }")
        assertContains(out, "private class DebugStruct(")

        val arithOpsOut =
            renderSingleKotlinFile(
                """
                pub trait Add<Rhs = Self> {
                    type Output;
                    fn add(self, rhs: Rhs) -> Self::Output;
                }
                pub trait Sub<Rhs = Self> {
                    type Output;
                    fn sub(self, rhs: Rhs) -> Self::Output;
                }
                pub trait Mul<Rhs = Self> {
                    type Output;
                    fn mul(self, rhs: Rhs) -> Self::Output;
                }
                pub trait Neg {
                    type Output;
                    fn neg(self) -> Self::Output;
                }
                """.trimIndent(),
                FileHeaderContext(
                    portLintSourcePath = "ops/arith.rs",
                    packageName = "$coreNs.ops.arith",
                    projectGroup = "io.github.kotlinmania",
                    projectNamespace = coreNs,
                    crateNamespaces = mapOf("core" to coreNs),
                    rawAbiMode = false,
                ),
            ).text
        assertContains(arithOpsOut, "public operator fun plus(rhs: Rhs): Output = add(rhs)")
        assertContains(arithOpsOut, "public operator fun minus(rhs: Rhs): Output = sub(rhs)")
        assertContains(arithOpsOut, "public operator fun times(rhs: Rhs): Output = mul(rhs)")
        assertContains(arithOpsOut, "public operator fun unaryMinus(): Output = neg()")

        val bitOpsOut =
            renderSingleKotlinFile(
                """
                pub trait BitAnd<Rhs = Self> {
                    type Output;
                    fn bitand(self, rhs: Rhs) -> Self::Output;
                }
                pub trait BitOr<Rhs = Self> {
                    type Output;
                    fn bitor(self, rhs: Rhs) -> Self::Output;
                }
                pub trait BitXor<Rhs = Self> {
                    type Output;
                    fn bitxor(self, rhs: Rhs) -> Self::Output;
                }
                """.trimIndent(),
                FileHeaderContext(
                    portLintSourcePath = "ops/bit.rs",
                    packageName = "$coreNs.ops.bit",
                    projectGroup = "io.github.kotlinmania",
                    projectNamespace = coreNs,
                    crateNamespaces = mapOf("core" to coreNs),
                    rawAbiMode = false,
                ),
            ).text
        assertContains(bitOpsOut, "public infix fun and(rhs: Rhs): Output = bitand(rhs)")
        assertContains(bitOpsOut, "public infix fun or(rhs: Rhs): Output = bitor(rhs)")
        assertContains(bitOpsOut, "public infix fun xor(rhs: Rhs): Output = bitxor(rhs)")
    }

    @Test
    fun waveC11ManagedStructGateSlicePtrDistanceResourceCleanerAndAutoCloseableDrop() {
        val plainManagedSrc =
            """
            pub struct ManagedPair {
                pub x: i32,
                pub y: i32,
            }

            #[repr(transparent)]
            pub struct ManagedTransparent(u64);

            pub fn slice_span(whole: &[u8], sub: &[u8]) -> usize {
                (sub.as_ptr() as usize) - (whole.as_ptr() as usize)
            }

            pub fn slice_checked_span(whole: &[u8], sub: &[u8]) -> Option<usize> {
                (sub.as_ptr() as usize).checked_sub(whole.as_ptr() as usize)
            }
            """.trimIndent()
        val plainManagedOut = translateRustSourceToKotlin(recognizeRust(plainManagedSrc))
        ZeroUnderscoreIdentifierValidator.requireZeroViolations(plainManagedOut, "WaveC11Managed.kt")
        assertFalse(plainManagedOut.contains("fun typeLayout("), plainManagedOut)
        assertFalse(plainManagedOut.contains("fieldStorage"), plainManagedOut)
        assertFalse(plainManagedOut.contains(".asPtr()"), plainManagedOut)

        val ffiLayoutSrc =
            """
            #[repr(C)]
            pub struct NativeHeader {
                pub magic: u32,
                pub len: u32,
            }

            #[repr(transparent)]
            pub struct RawHandle(pub u64);

            unsafe extern "C" {
                fn take_header(ptr: *const NativeHeader) -> RawHandle;
            }
            """.trimIndent()
        val ffiLayoutOut = translateRustSourceToKotlin(recognizeRust(ffiLayoutSrc))
        ZeroUnderscoreIdentifierValidator.requireZeroViolations(ffiLayoutOut, "WaveC11FfiHeader.kt")
        assertContains(ffiLayoutOut, "fun typeLayout(")
        assertContains(ffiLayoutOut, "fieldStorage: MutPtr")

        val dropTraitSrc =
            """
            pub trait Drop {
                fn drop(&mut self);
            }
            """.trimIndent()
        val dropTraitOut =
            translateRustSourceToKotlin(
                recognizeRust(dropTraitSrc),
                headerContext =
                    FileHeaderContext(
                        packageName = "io.github.kotlinmania.rustlang.core.ops.drop",
                        projectNamespace = "io.github.kotlinmania.rustlang.core",
                        crateNamespaces = mapOf("core" to "io.github.kotlinmania.rustlang.core"),
                    ),
            )
        ZeroUnderscoreIdentifierValidator.requireZeroViolations(dropTraitOut, "WaveC11Drop.kt")
        assertContains(dropTraitOut, "public interface Drop : AutoCloseable")
        assertContains(dropTraitOut, "public override fun close(): Unit = drop()")

        val memFnSrc =
            """
            pub const fn drop<T>(_x: T) {}
            pub const fn forget<T>(_t: T) {}
            """.trimIndent()
        val memFnOut =
            translateRustSourceToKotlin(
                recognizeRust(memFnSrc),
                headerContext =
                    FileHeaderContext(
                        packageName = "io.github.kotlinmania.rustlang.core.mem",
                        projectNamespace = "io.github.kotlinmania.rustlang.core",
                        crateNamespaces = mapOf("core" to "io.github.kotlinmania.rustlang.core"),
                    ),
            )
        ZeroUnderscoreIdentifierValidator.requireZeroViolations(memFnOut, "WaveC11Mem.kt")
        assertContains(memFnOut, "if (x is AutoCloseable) x.close()")
        assertContains(memFnOut, "if (t is KmForgettableResource) t.disarmCleaner()")

        val ownedVecSrc =
            """
            pub struct RawVec<T> {
                pub cap: usize,
                marker: core::marker::PhantomData<T>,
            }

            pub struct MyVec<T> {
                pub buf: RawVec<T>,
                pub len: usize,
            }

            impl<T> Drop for MyVec<T> {
                fn drop(&mut self) {
                    self.len = 0;
                }
            }
            """.trimIndent()
        val ownedVecOut =
            translateRustSourceToKotlin(
                recognizeRust(ownedVecSrc),
                headerContext =
                    FileHeaderContext(
                        packageName = "io.github.kotlinmania.rustlang.alloc.vec",
                        projectNamespace = "io.github.kotlinmania.rustlang.alloc",
                        crateNamespaces =
                            mapOf(
                                "core" to "io.github.kotlinmania.rustlang.core",
                                "alloc" to "io.github.kotlinmania.rustlang.alloc",
                            ),
                    ),
            )
        ZeroUnderscoreIdentifierValidator.requireZeroViolations(ownedVecOut, "WaveC11MyVec.kt")
        assertContains(ownedVecOut, "public open class MyVec<T>")
        assertContains(ownedVecOut, ": AutoCloseable")
        assertContains(ownedVecOut, "override fun close() {\n        this.drop()\n    }")
        assertContains(ownedVecOut, "this.buf.close()")

        val expectBridge = CoreIntrinsicFfiArtifacts.renderKmCoreIntrinsicsExpectKotlin("demo.core")
        ZeroUnderscoreIdentifierValidator.requireZeroViolations(expectBridge, "KmCoreIntrinsicsExpect.kt")
        assertContains(expectBridge, "public interface KmForgettableResource : AutoCloseable")
        assertContains(expectBridge, "public fun disarmCleaner()")
        assertContains(expectBridge, "public expect class KmResourceCleaner internal constructor() : KmForgettableResource")
        assertContains(expectBridge, "public expect fun kmRegisterResourceCleaner(owner: Any, cleanup: () -> Unit): KmResourceCleaner")

        val nativeBridge = CoreIntrinsicFfiArtifacts.renderKmCoreNativeActualsKotlin("demo.core", "demo.cinterop")
        ZeroUnderscoreIdentifierValidator.requireZeroViolations(nativeBridge, "KmCoreNativeActuals.kt")
        assertContains(nativeBridge, "ExperimentalNativeApi::class")
        assertContains(nativeBridge, "kotlin.native.ref.createCleaner")
        assertContains(nativeBridge, "public actual class KmResourceCleaner")
        assertContains(nativeBridge, "public actual override fun disarmCleaner(): Unit = disarm()")

        val jvmBridge = CoreIntrinsicFfiArtifacts.renderKmCoreJvmActualsKotlin("demo.core", "DemoJni")
        ZeroUnderscoreIdentifierValidator.requireZeroViolations(jvmBridge, "KmCoreJvmActuals.kt")
        assertContains(jvmBridge, "java.lang.ref.PhantomReference")
        assertContains(jvmBridge, "java.util.concurrent.ConcurrentHashMap")
        assertContains(jvmBridge, "public actual override fun disarmCleaner(): Unit = disarm()")
        assertFalse(jvmBridge.contains("java.lang.ref.Cleaner"), jvmBridge)

        val androidBridge = CoreIntrinsicFfiArtifacts.renderKmCoreAndroidActualsKotlin("demo.core", "DemoJni")
        ZeroUnderscoreIdentifierValidator.requireZeroViolations(androidBridge, "KmCoreAndroidActuals.kt")
        assertContains(androidBridge, "java.lang.ref.PhantomReference")
        assertContains(androidBridge, "public actual override fun disarmCleaner(): Unit = disarm()")
        assertFalse(androidBridge.contains("java.lang.ref.Cleaner"), androidBridge)

        val jsBridge = CoreIntrinsicFfiArtifacts.renderKmCoreJsActualsKotlin("demo.core")
        ZeroUnderscoreIdentifierValidator.requireZeroViolations(jsBridge, "KmCoreJsActuals.kt")
        assertContains(jsBridge, "KmJsFinalizationRegistry")
        assertContains(jsBridge, "public actual class KmResourceCleaner")
        assertContains(jsBridge, "public actual override fun disarmCleaner(): Unit = disarm()")

        val wasmJsBridge = CoreIntrinsicFfiArtifacts.renderKmCoreWasmJsActualsKotlin("demo.core")
        ZeroUnderscoreIdentifierValidator.requireZeroViolations(wasmJsBridge, "KmCoreWasmJsActuals.kt")
        assertContains(wasmJsBridge, "FinalizationRegistry")
        assertContains(wasmJsBridge, "toJsReference()")
        assertContains(wasmJsBridge, "KmWasmCleanToken")
        assertContains(wasmJsBridge, "public actual override fun disarmCleaner(): Unit = disarm()")
        assertFalse(wasmJsBridge.contains("import kotlin.js.JsAny"), wasmJsBridge)

        val wasmWasiBridge = CoreIntrinsicFfiArtifacts.renderKmCoreWasmWasiActualsKotlin("demo.core")
        ZeroUnderscoreIdentifierValidator.requireZeroViolations(wasmWasiBridge, "KmCoreWasmWasiActuals.kt")
        assertContains(wasmWasiBridge, "KmWasmWasiCleanState")
        assertContains(wasmWasiBridge, "public actual class KmResourceCleaner")
        assertContains(wasmWasiBridge, "public actual override fun disarmCleaner(): Unit = disarm()")
    }
}
