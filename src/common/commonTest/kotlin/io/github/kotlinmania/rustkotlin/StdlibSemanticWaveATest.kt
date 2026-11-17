package io.github.kotlinmania.rustkotlin

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class StdlibSemanticWaveATest {
    private val stdlibHeader =
        FileHeaderContext(
            packageName = "io.github.kotlinmania.rustlang.alloc.alloc",
            crateNamespaces =
                mapOf(
                    "core" to "io.github.kotlinmania.rustlang.core",
                    "alloc" to "io.github.kotlinmania.rustlang.alloc",
                ),
        )

    @Test fun taggedResultPreservesSmartCastsAndExplicitTypeArgs() {
        val source =
            """
            use core::alloc::AllocError;
            use core::ptr::NonNull;

            pub fn check_try(p: *mut u8) -> Result<NonNull<u8>, AllocError> {
                let nn = NonNull::new(p).ok_or(AllocError)?;
                Ok(nn)
            }

            pub fn check_match(res: Result<i32, AllocError>) -> Result<i32, AllocError> {
                let val_out = match res {
                    Ok(v) => v,
                    Err(e) => return Err(e),
                };
                if let Ok(inner) = res {
                    let _ = inner;
                }
                let Ok(else_val) = res else {
                    return Err(AllocError);
                };
                let _ = (val_out, else_val);
                Ok(1)
            }
            """.trimIndent()

        val kt = renderSingleKotlinFile(source, stdlibHeader).text
        assertContains(kt, "is Result.Ok<*, *> ->")
        assertContains(kt, "is Result.Err<*, *> ->")
        assertFalse(kt.contains("as Result.Ok<*, *>"), "Typed tagged Result smart-casts must not use erased casts:\n$kt")
        assertContains(kt, "Result.Ok<NonNull, AllocError>(")
        assertContains(kt, "Result.Err<NonNull, AllocError>(")
        assertContains(kt, "Result.Ok<Int, AllocError>(")
        assertContains(kt, "Result.Err<Int, AllocError>(")
    }

    @Test fun shadowedLocalResultPreservesFmtResultAndAlias() {
        val source =
            """
            use core::fmt;
            use core::alloc::AllocError;

            pub type Result<T> = core::result::Result<T, AllocError>;
            pub type FmtAlias = fmt::Result;

            pub fn take_fmt_result(res: fmt::Result) -> bool {
                res.is_ok()
            }
            """.trimIndent()

        val kt = renderSingleKotlinFile(source, stdlibHeader).text
        assertContains(kt, "public typealias Result<T> = io.github.kotlinmania.rustlang.core.result.Result<T, AllocError>")
        assertContains(kt, "public typealias FmtAlias = io.github.kotlinmania.rustlang.core.result.Result<Unit, Error>")
        assertContains(kt, "res: io.github.kotlinmania.rustlang.core.result.Result<Unit, Error>")
        assertFalse(kt.contains("public typealias Result<T> = Result<"), "Self-referential Result typealias must stay qualified:\n$kt")
    }

    @Test fun sizedTypePropertiesAndDefaultUnresolvedLayouts() {
        val source =
            """
            #[repr(transparent)]
            pub struct TransWrap<T>(pub *const T);

            pub struct Boxed<T> {
                pub item: T,
            }

            pub trait Maker {
                fn make(&self) -> usize;
            }

            impl<T> Maker for Boxed<T> {
                fn make(&self) -> usize {
                    if T::IS_ZST {
                        0
                    } else {
                        <T as SizedTypeProperties>::SIZE
                    }
                }
            }

            pub fn max_len<T>() -> usize {
                T::MAX_SLICE_LEN
            }
            """.trimIndent()

        val kt =
            renderSingleKotlinFile(
                source,
                FileHeaderContext(
                    packageName = "io.github.kotlinmania.rustlang.core.alloc",
                    crateNamespaces = mapOf("core" to "io.github.kotlinmania.rustlang.core"),
                ),
            ).text
        assertContains(kt, "typeLayoutT: TypeLayout<T> = UnresolvedTypeLayout()")
        assertContains(kt, "= UnresolvedTypeLayout()")
        assertFalse(Regex("""override fun make\([^)]*= UnresolvedTypeLayout\(\)""").containsMatchIn(kt), "Override methods must not declare default parameter values:\n$kt")
    }

    @Test fun defaultTypeArgsNonZeroAlignmentAndMutToConstPtrCoercion() {
        val source =
            """
            use core::mem::Alignment;
            use core::num::NonZero;
            use core::ops::ControlFlow;
            use core::ptr::{self, NonNull};

            pub struct PairWithDefault<T, U = i32>(pub T, pub U);

            pub unsafe fn copy_nonnull(src: NonNull<u8>, dst: NonNull<u8>, count: usize) {
                let src_ptr = src.as_ptr();
                let dst_ptr = dst.as_ptr();
                unsafe {
                    ptr::copy_nonoverlapping(src_ptr, dst_ptr, count);
                    dst_ptr.copy_from(src_ptr, count);
                }
            }

            pub fn make_nonzero(x: u32) -> Option<NonZero<u32>> {
                NonZero::new(x)
            }

            pub fn defaults_and_alloc(rv: RawVec<u8>, cf: ControlFlow<i32>, p: PairWithDefault<bool>) {
                let _ = (rv, cf, p);
            }

            pub unsafe fn call_alloc(size: usize) -> *mut u8 {
                let align = Alignment::new_unchecked(8);
                unsafe { crate::alloc::__rust_alloc(size, align) }
            }
            """.trimIndent()

        val kt = renderSingleKotlinFile(source, stdlibHeader).text
        assertContains(kt, "PairWithDefault<Boolean, Int>")
        assertContains(kt, "RawVec<UByte, Global>")
        assertContains(kt, "ControlFlow<Int, Unit>")
        assertContains(kt, "NonZero<UInt, NonZeroU32Inner>")
        assertContains(kt, "NonZero.new<NonZeroU32Inner, UInt>(")
        assertContains(kt, "copyNonoverlapping((srcPtr).asConstPtr(), dstPtr, count, typeLayoutT = PrimitiveLayouts.U8)")
        assertContains(kt, "dstPtr.copyFrom((srcPtr).asConstPtr(), count)")
        assertContains(kt, ".asUsize().toLong()")
    }

    @Test fun implConstGenericScopeDoesNotLeakToSubsequentItem() {
        val source =
            """
            pub struct ArrHolder<const N: usize>([u8; N]);

            impl<const N: usize> ArrHolder<N> {
                pub fn cap(&self) -> usize {
                    N
                }
            }

            pub struct Plain {
                pub count: usize,
            }
            """.trimIndent()

        val kt = renderSingleKotlinFile(source).text
        assertContains(kt, "public open class Plain(")
        assertFalse(kt.contains("class Plain<const"), "Const generic N from prior impl must not leak into Plain:\n$kt")
    }
}
