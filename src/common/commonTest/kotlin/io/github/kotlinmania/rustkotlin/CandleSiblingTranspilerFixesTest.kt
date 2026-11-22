package io.github.kotlinmania.rustkotlin

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CandleSiblingTranspilerFixesTest {
    @Test
    fun tupleDestructuringTypeAscriptionEmitsComponentAnnotationsOnly() {
        val rust =
            """
            pub fn split_bits(x: f64) -> (i32, u32) {
                let (hx, lx): (i32, u32) = ((x.to_bits() >> 32) as i32, x.to_bits() as u32);
                (hx, lx)
            }
            """.trimIndent()
        val kt = translateRustSourceToKotlin(recognizeRust(rust))
        assertContains(kt, "val (hx: Int, lx: UInt) =")
        assertFalse(kt.contains("val (hx, lx):"), "Destructuring declarations must never have outer ': Pair<...>' annotation:\n$kt")
        ZeroUnderscoreIdentifierValidator.requireZeroViolations(kt, "tupleDestructuringTypeAscription")
    }

    @Test
    fun reifiedOnlyPrefixesTopLevelTypeParameterSlot() {
        val rust =
            """
            pub trait Zero: Sized {
                fn zero() -> Self;
            }

            pub fn zero<T: Zero>() -> T {
                Zero::zero()
            }
            """.trimIndent()
        val kt = translateRustSourceToKotlin(recognizeRust(rust))
        assertContains(kt, "<reified T : Zero<T>>")
        assertFalse(kt.contains("Zero<reified "), "reified must never be injected inside nested generic bounds:\n$kt")
        ZeroUnderscoreIdentifierValidator.requireZeroViolations(kt, "reifiedNestedBound")
    }

    @Test
    fun whereClauseOmittedWhenBoundedTypeNotDeclaredOnMethod() {
        val rust =
            """
            use core::ops::Add;

            pub trait FloatConst {
                fn E() -> Self;
                fn TAU() -> Self
                where
                    Self: Sized + Add<Self, Output = Self>,
                {
                    Self::E() + Self::E()
                }
            }

            pub struct F8(pub u8);

            impl Add for F8 {
                type Output = F8;
                fn add(self, rhs: F8) -> F8 {
                    F8(self.0.wrapping_add(rhs.0))
                }
            }

            impl FloatConst for F8 {
                fn E() -> Self {
                    F8(1)
                }
            }
            """.trimIndent()
        val kt = translateRustSourceToKotlin(recognizeRust(rust))
        assertFalse(kt.contains("fun tau(): T where"), "Non-generic trait method must not emit outer where T : ...:\n$kt")
        assertFalse(kt.contains("where F8 :"), "Concrete impl method must not emit where F8 : ...:\n$kt")
        ZeroUnderscoreIdentifierValidator.requireZeroViolations(kt, "whereClauseOmitted")
    }

    @Test
    fun lowercaseImportedRustStructResolvesToCapitalizedKotlinType() {
        val rust =
            """
            use half::f16;

            pub fn convert_half(bits: u16) -> f16 {
                f16::from_bits(bits)
            }
            """.trimIndent()
        val kt =
            translateRustSourceToKotlin(
                recognizeRust(rust),
                FileHeaderContext(
                    packageName = "io.github.kotlinmania.float8",
                    projectGroup = "io.github.kotlinmania",
                    projectNamespace = "io.github.kotlinmania.float8",
                    crateNamespaces = mapOf("half" to "io.github.kotlinmania.half"),
                ),
            )
        assertContains(kt, "import io.github.kotlinmania.half.F16")
        assertContains(kt, "F16.fromBits(bits)")
        assertFalse(kt.contains("io.github.kotlinmania.f16"), "Must not treat imported f16 type as external crate module:\n$kt")
        ZeroUnderscoreIdentifierValidator.requireZeroViolations(kt, "lowercaseImportedStruct")
    }

    @Test
    fun finalStructOmitsDefaultsForFunctionTypesAndTypeParameters() {
        val rust =
            """
            pub trait ParallelIterator {
                type Item;
            }

            pub struct FoldChunksWith<I: ParallelIterator, U, F> {
                pub base: I,
                pub item: U,
                pub fold_op: F,
            }

            pub struct FnHolder<T> {
                pub callback: fn(T) -> bool,
            }
            """.trimIndent()
        val kt =
            translateRustSourceToKotlin(
                recognizeRust(rust),
                FileHeaderContext(finalClasses = true),
            )
        assertFalse(kt.contains("= I()"), "Type parameter field must not emit '= I()' default:\n$kt")
        assertFalse(kt.contains("= U()"), "Type parameter field must not emit '= U()' default:\n$kt")
        assertFalse(kt.contains("-> Boolean()"), "Function-typed field must not emit '-> Boolean()' default:\n$kt")
        ZeroUnderscoreIdentifierValidator.requireZeroViolations(kt, "finalStructDefaults")
    }

    @Test
    fun docExamplesUsingInt128DoNotInlineSynthesizedRuntimePreamble() {
        val rust =
            """
            /// Converts `u128`:
            ///
            /// ```rust
            /// let x: u128 = 42;
            /// assert_eq!(x, 42);
            /// ```
            pub fn noop() {}
            """.trimIndent()
        val kt = translateRustSourceToKotlin(recognizeRust(rust))
        assertFalse(kt.contains("* public class U128("), "KDoc code examples must never inline synthesized U128 class definition:\n$kt")
        ZeroUnderscoreIdentifierValidator.requireZeroViolations(kt, "docExampleU128")
    }

    @Test
    fun externalRustLangCoreImportsCanonicalInt128RustAnyAndExtendedFloatsWithoutSynthesis() {
        val rust =
            """
            use core::any::{Any, TypeId};

            pub fn probe_core_types(
                wide_u: u128,
                wide_i: i128,
                half_v: f16,
                quad_v: f128,
                dyn_val: &dyn Any,
            ) -> bool {
                let _ = (wide_u, wide_i, half_v, quad_v);
                dyn_val.type_id() == TypeId::of::<i32>()
            }
            """.trimIndent()
        val kt =
            translateRustSourceToKotlin(
                recognizeRust(rust),
                FileHeaderContext(
                    packageName = "io.github.kotlinmania.libm.math",
                    projectGroup = "io.github.kotlinmania",
                    projectNamespace = "io.github.kotlinmania.libm",
                    crateNamespaces = mapOf("core" to "io.github.kotlinmania.rustlang.core"),
                    isBatchCrateOutput = true,
                ),
            )
        assertFalse(kt.contains("class U128("), "Must never inline synthesized U128 class definition:\n$kt")
        assertFalse(kt.contains("class RustAny("), "Must never inline synthesized RustAny class definition:\n$kt")
        assertContains(kt, "import io.github.kotlinmania.rustlang.core.F128")
        assertContains(kt, "import io.github.kotlinmania.rustlang.core.F16")
        assertContains(kt, "import io.github.kotlinmania.rustlang.core.I128")
        assertContains(kt, "import io.github.kotlinmania.rustlang.core.RustAny")
        assertContains(kt, "import io.github.kotlinmania.rustlang.core.RustTypeId")
        assertContains(kt, "import io.github.kotlinmania.rustlang.core.U128")
        assertFalse(kt.contains("import io.github.kotlinmania.libm.U128"), "Must not import per-crate U128:\n$kt")
        assertFalse(kt.contains("import io.github.kotlinmania.libm.RustAny"), "Must not import per-crate RustAny:\n$kt")
        ZeroUnderscoreIdentifierValidator.requireZeroViolations(kt, "externalRustLangCoreTypes")
    }

    @Test
    fun mutableSliceForLoopRewritesRhsAndLhsToIndexedElementAccessAndLowersByteSwap() {
        val rust =
            """
            pub fn swap_in_place(dst: &mut [u32], scale: u32) {
                for n in dst {
                    *n = n.to_be().wrapping_add(scale);
                }
            }

            pub fn inc_ref_mut_loop(buf: &mut [u16]) {
                for v in &mut *buf {
                    *v = (*v).swap_bytes();
                }
            }
            """.trimIndent()
        val kt = translateRustSourceToKotlin(recognizeRust(rust))
        assertFalse(kt.contains("for (n in dst)"), "Mutable slice loop must use indexed iteration:\n$kt")
        assertContains(kt, "dst[idx] =")
        assertFalse(kt.contains(".toBe()"), "Primitive UInt.to_be() must lower to bitwise shifts:\n$kt")
        assertFalse(kt.contains(".swapBytes()"), "Primitive UShort.swap_bytes() must lower to bitwise shifts:\n$kt")
        assertContains(kt, "shl 24")
        ZeroUnderscoreIdentifierValidator.requireZeroViolations(kt, "mutableSliceForLoop")
    }

    @Test
    fun crossFileTypeParamWitnessInfersArityAndReturnTypesInsteadOfUnaryErasedAny() {
        val rust =
            """
            pub fn read_pair<T: ByteOrder>(buf: &[u8]) -> Result<u16, String> {
                Ok(T::read_u16(buf))
            }

            pub fn dot_accum<T: VecOps>(lhs: &[T], rhs: &[T], len: usize) -> T {
                let mut sum = T::zero();
                T::vec_dot(lhs.as_ptr(), rhs.as_ptr(), &mut sum, len);
                sum
            }
            """.trimIndent()
        val kt = translateRustSourceToKotlin(recognizeRust(rust))
        assertContains(kt, "readU16TFn: (List<UByte>) -> UShort")
        assertContains(kt, "zeroTFn: () -> T")
        assertContains(kt, "vecDotTFn: (ConstPtr, ConstPtr, T, Int) -> Unit")
        assertFalse(kt.contains("(Any?) -> Any?"), "Must not fall back to unary (Any?) -> Any?:\n$kt")
        ZeroUnderscoreIdentifierValidator.requireZeroViolations(kt, "crossFileTypeParamWitness")
    }

    @Test
    fun mutableSubsliceBorrowMutatesUnderlyingListOrArrayInPlace() {
        val rust =
            """
            pub fn fill_window(dst: &mut [u32]) {
                dst[0] = 9;
            }

            pub fn caller_untyped() -> u32 {
                let mut utmp = [0u32; 4];
                fill_window(&mut utmp[1..3]);
                utmp[1]
            }

            pub fn caller_typed_array() -> u32 {
                let mut arr: [u32; 4] = [0; 4];
                ByteOrder::read_u32_into(&[0u8; 8], &mut arr[0..2]);
                arr[0]
            }

            pub fn caller_vec() -> u32 {
                let mut v = vec![0u32; 4];
                fill_window(&mut v[1..3]);
                v[1]
            }
            """.trimIndent()
        val kt =
            translateRustSourceToKotlin(
                recognizeRust(rust),
                FileHeaderContext(finalClasses = true),
            )
        assertContains(kt, "var utmp = UIntArray(4) { 0u }")
        assertContains(kt, "utmp.slice(1 until 3).toMutableList()")
        assertContains(kt, "utmp[(1) + subIdx0] = subSlice0[subIdx0]")
        assertContains(kt, "v.subList(1, 3)")
        assertContains(kt, "arr.sliceArray(0 until 2)")
        assertContains(kt, ".copyInto(arr, destinationOffset = 0)")
        ZeroUnderscoreIdentifierValidator.requireZeroViolations(kt, "mutableSubsliceBorrow")
    }

    @Test
    fun primitiveUfcsMinMaxAndDisabledCfgBlockStatementsLowerCleanly() {
        val rust =
            """
            pub trait ElemMin {
                fn elem_min(self, other: Self) -> Self;
            }

            impl ElemMin for f32 {
                fn elem_min(self, other: Self) -> Self {
                    #[cfg(feature = "disabled_alt")]
                    use bogus_crate::MissingSymbol;
                    Self::min(self, other)
                }
            }

            impl ElemMin for u8 {
                fn elem_min(self, other: Self) -> Self {
                    Self::min(self, other)
                }
            }
            """.trimIndent()
        val kt =
            translateRustSourceToKotlin(
                recognizeRust(rust),
                FileHeaderContext(
                    cfgTargetProfile = CfgTargetProfile(enabledFeatures = emptySet()),
                ),
            )
        assertFalse(kt.contains("Float.min("), "Must not emit non-existent Float.Companion.min:\n$kt")
        assertFalse(kt.contains("UByte.min("), "Must not emit non-existent UByte.Companion.min:\n$kt")
        assertFalse(kt.contains("MissingSymbol"), "Disabled #[cfg] block statement must be skipped:\n$kt")
        assertContains(kt, "minOf(")
        ZeroUnderscoreIdentifierValidator.requireZeroViolations(kt, "primitiveUfcsMinMax")
    }

    @Test
    fun shadowedFloatTraitLowersF32AndSelfMethodCleanly() {
        val rust =
            """
            pub trait Float {
                fn zero() -> Self;
                fn abs_zero(self) -> Self {
                    Self::zero()
                }
            }

            pub fn decode_bits(bits: u32) -> f32 {
                f32::from_bits(bits)
            }
            """.trimIndent()
        val kt = translateRustSourceToKotlin(recognizeRust(rust))
        assertContains(kt, "public fun decodeBits(bits: UInt): kotlin.Float")
        assertContains(kt, "kotlin.Float.fromBits(bits.toInt())")
        assertContains(kt, "this.zero()")
        assertFalse(kt.contains("Float.Companion.fromBits("), "Must use kotlin.Float.fromBits:\n$kt")
        ZeroUnderscoreIdentifierValidator.requireZeroViolations(kt, "shadowedFloatTrait")
    }

    @Test
    fun concreteNumericMethodsRouteToCoreWhileGenericTraitMethodsDispatchCleanly() {
        val rust =
            """
            pub trait CheckedAdd: Sized {
                fn checked_add(&self, v: &Self) -> Option<Self>;
            }

            pub fn concrete_ops(a: u32, b: u32) -> u32 {
                let c = a.checked_add(b).unwrap_or(0);
                c + a.count_ones()
            }

            pub fn generic_ops<T: CheckedAdd>(a: T, b: T) -> Option<T> {
                a.checked_add(&b)
            }
            """.trimIndent()
        val kt =
            translateRustSourceToKotlin(
                recognizeRust(rust),
                FileHeaderContext(
                    crateNamespaces = mapOf("core" to "io.github.kotlinmania.rustlang.core"),
                ),
            )
        assertContains(kt, "a.checkedAdd(b) ?: 0u")
        assertContains(kt, "a.countOnes()")
        assertContains(kt, "return a.checkedAdd(b)")
        ZeroUnderscoreIdentifierValidator.requireZeroViolations(kt, "concreteVsGenericCheckedAdd")
    }

    @Test
    fun zeroVariantTraitImplEnumEmitsObjectWhileBareZeroVariantEmitsOpaqueClass() {
        val rust =
            """
            pub trait ByteOrder {
                fn read_u16(buf: &[u8]) -> u16;
            }

            pub enum BigEndian {}

            impl ByteOrder for BigEndian {
                fn read_u16(buf: &[u8]) -> u16 {
                    buf[0] as u16
                }
            }

            pub enum OpaqueHandle {}
            """.trimIndent()
        val kt = translateRustSourceToKotlin(recognizeRust(rust))
        assertContains(kt, "public object BigEndian : ByteOrder")
        assertContains(kt, "public override fun readU16(")
        assertFalse(kt.contains("open fun readU16("), "Object impl method must not be open:\n$kt")
        assertContains(kt, "class OpaqueHandle")
        ZeroUnderscoreIdentifierValidator.requireZeroViolations(kt, "zeroVariantEnumObject")
    }

    @Test
    fun splitModuleRootInlineTestsEmitCrateSuiteFileWithoutLibRsPortLintAndWrapTestClass() {
        val libRs =
            """
            pub fn answer() -> u32 {
                42
            }

            #[cfg(test)]
            mod test {
                use super::answer;

                #[test]
                fn works() {
                    assert_eq!(answer(), 42);
                }
            }
            """.trimIndent()
        val files = mapOf("src/lib.rs" to libRs)
        val written = linkedMapOf<String, String>()
        runSingleFileCommand(
            args =
                arrayOf(
                    "-r",
                    "-f",
                    "--split-module-roots",
                    "--source-root",
                    "src",
                    "-n",
                    "io.github.kotlinmania.samplecrate",
                    "-o",
                    "out/main",
                    "--test-output",
                    "out/test",
                    "src",
                ),
            read = { path -> files[path] ?: error("unexpected read: $path") },
            write = { path, text -> written[path] = text },
            writeForce = { path, text -> written[path] = text },
            exists = { it in written },
            isDirectory = { it == "src" || it == "out/main" || it == "out/test" },
            collectRustFiles = { listOf("src/lib.rs") },
            report = {},
            resolveRustKotlinProperties = { null },
            resolvePackageProperties = { null },
            resolveCrateMacroSources = { emptyList() },
            resolveCrateFlatFileModules = { _, _ -> emptySet() },
            resolveCrateRawAbiMetadata = { _, _, _, _ -> CrateRawAbiMetadata() },
        )
        assertFalse(written.keys.any { it.endsWith("/LibInlineTests.kt") || it == "out/test/LibInlineTests.kt" }, "Must not emit LibInlineTests.kt: ${written.keys}")
        val inlineTestText = written["out/test/SamplecrateInlineTests.kt"]
        assertTrue(inlineTestText != null, "Expected out/test/SamplecrateInlineTests.kt in ${written.keys}")
        assertFalse(inlineTestText.contains("port-lint: tests lib.rs"), "Must not emit port-lint for lib.rs:\n$inlineTestText")
        assertFalse(inlineTestText.contains("port-lint: source lib.rs"), "Must not emit port-lint for lib.rs:\n$inlineTestText")
        assertContains(inlineTestText, "public class SamplecrateInlineTests {")
        assertContains(inlineTestText, "@kotlin.test.Test")
        assertContains(inlineTestText, "public fun works()")
        ZeroUnderscoreIdentifierValidator.requireZeroViolations(inlineTestText, "splitModuleRootInlineTests")
    }

    @Test
    fun batchARolloutCoreImportsUnusedAddressableRefCastPunningMacroTestAndQuickcheckOmission() {
        val productRust =
            """
            use core::mem::size_of;
            use core::ptr;

            pub fn encode_f32_and_read(src: &[f32], dst: &mut [u8]) -> u64 {
                let mut scratch: [u8; 4] = [0; 4];
                let n: f32 = src[0];
                let bits: u32 = unsafe { *(n as *const f32 as *const u32) };
                unsafe {
                    ptr::copy_nonoverlapping(&bits as *const u32 as *const u8, scratch.as_mut_ptr(), 4);
                    ptr::copy_nonoverlapping(scratch.as_ptr(), dst.as_mut_ptr(), 4);
                }
                let mut out: u64 = 0;
                unsafe {
                    ptr::copy_nonoverlapping(dst.as_ptr(), &mut out as *mut u64 as *mut u8, size_of::<u64>());
                }
                out
            }
            """.trimIndent()
        val productKt =
            translateRustSourceToKotlin(
                recognizeRust(productRust),
                FileHeaderContext(
                    packageName = "io.github.kotlinmania.sample",
                    projectGroup = "io.github.kotlinmania",
                    projectNamespace = "io.github.kotlinmania.sample",
                    crateNamespaces =
                        mapOf(
                            "core" to "io.github.kotlinmania.rustlang.core",
                            "alloc" to "io.github.kotlinmania.rustlang.alloc",
                        ),
                ),
            )
        assertContains(productKt, "import io.github.kotlinmania.rustlang.core.mem.sizeOf")
        assertContains(productKt, "import io.github.kotlinmania.rustlang.core.slice.asPtr")
        assertContains(productKt, "import io.github.kotlinmania.rustlang.core.slice.asMutPtr")
        assertContains(productKt, "toRawBits().toUInt()")
        assertFalse(productKt.contains("var scratch by "), "Unused delegated scratch local must be omitted:\n$productKt")
        assertFalse(productKt.contains("var bits by "), "Unused delegated bits local must be omitted:\n$productKt")
        assertContains(productKt, "val out by ")
        ZeroUnderscoreIdentifierValidator.requireZeroViolations(productKt, "batchAProductFixes")

        val inlineRust =
            """
            pub trait Order {
                fn read_u16(buf: &[u8]) -> u16;
                fn write_i128_into(src: &[i128], dst: &mut [u8]) {
                    let _ = (src, dst);
                }
            }

            pub enum LittleOrder {}
            pub type NativeOrder = LittleOrder;

            macro_rules! impl_order {
                (${'$'}ty:ident) => {
                    impl Order for ${'$'}ty {
                        fn read_u16(buf: &[u8]) -> u16 {
                            (buf[0] as u16) | ((buf[1] as u16) << 8)
                        }
                    }
                };
            }

            impl_order!(LittleOrder);

            #[cfg(test)]
            mod test {
                use super::{LittleOrder, NativeOrder, Order};

                struct Wi128<T>(pub T);

                impl quickcheck::Arbitrary for Wi128<u128> {}

                fn rand_bytes() -> u32 {
                    rand::random()
                }

                fn qc_sized<F: Fn(u32) -> bool>(f: F) {
                    let _ = (f, rand_bytes());
                }

                mod prop_order {
                    use super::qc_sized;

                    #[test]
                    fn prop_u16() {
                        qc_sized(|x| x == x);
                    }
                }

                #[test]
                fn direct_order_roundtrip() {
                    let mut bytes = [0; 32];
                    let numbers = [0, 1];
                    NativeOrder::write_i128_into(&numbers, &mut bytes);
                    let got = LittleOrder::read_u16(&[1, 2]);
                    assert_eq!(got, 0x0201);
                }
            }
            """.trimIndent()
        val inlineTranslation =
            renderInlineRustTests(
                inlineRust,
                FileHeaderContext(
                    packageName = "io.github.kotlinmania.sample",
                    projectGroup = "io.github.kotlinmania",
                    projectNamespace = "io.github.kotlinmania.sample",
                    crateNamespaces =
                        mapOf(
                            "core" to "io.github.kotlinmania.rustlang.core",
                            "alloc" to "io.github.kotlinmania.rustlang.alloc",
                        ),
                ),
            )
        assertTrue(inlineTranslation != null, "Expected inline tests to be emitted")
        val inlineKt = inlineTranslation.text
        assertContains(inlineKt, "MutableList(32) { 0u.toUByte() }")
        assertContains(inlineKt, "listOf(I128(0x0L, 0x0uL), I128(0x0L, 0x1uL))")
        assertContains(inlineKt, "listOf(1u.toUByte(), 2u.toUByte())")
        assertContains(inlineKt, "0x0201u.toUShort()")
        assertContains(inlineKt, "public fun directOrderRoundtrip()")
        assertFalse(inlineKt.contains("FnCallback"), "Omitted quickcheck helpers must not synthesize SAMs:\n$inlineKt")
        assertFalse(inlineKt.contains("Wi128"), "Arbitrary-only wrapper Wi128 must be omitted transitively:\n$inlineKt")
        assertFalse(inlineKt.contains("qcSized"), "qc_sized helper must be omitted:\n$inlineKt")
        assertFalse(inlineKt.contains("randBytes"), "rand_bytes helper must be omitted:\n$inlineKt")
        ZeroUnderscoreIdentifierValidator.requireZeroViolations(inlineKt, "batchAInlineFixes")
    }
}
