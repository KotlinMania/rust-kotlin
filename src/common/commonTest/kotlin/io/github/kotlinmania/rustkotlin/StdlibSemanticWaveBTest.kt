package io.github.kotlinmania.rustkotlin

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class StdlibSemanticWaveBTest {
    @Test
    fun tupleFunctionParametersBindDestructuredLocals() {
        val source =
            """
            pub struct CharEscapeDebug;

            impl CharEscapeDebug {
                pub fn call(&self, (c,): (char,)) -> usize {
                    c as usize
                }
            }

            pub fn sum_pair((a, b): (u32, u32)) -> u32 {
                a + b
            }
            """.trimIndent()
        val output = translateRustSourceToKotlin(recognizeRust(source))
        assertContains(output, "fun call(tupleArg0: Char)")
        assertContains(output, "val c = tupleArg0")
        assertContains(output, "fun sumPair(tupleArg0: Pair<UInt, UInt>)")
        assertContains(output, "val (a, b) = tupleArg0")
    }

    @Test
    fun transparentStructWithAssocTypeFieldKeepsAssocUnqualified() {
        val source =
            """
            pub unsafe trait ZeroablePrimitive {
                type NonZeroInner;
            }

            #[repr(transparent)]
            pub struct NonZero<T: ZeroablePrimitive>(T::NonZeroInner);
            """.trimIndent()
        val output =
            translateRustSourceToKotlin(
                recognizeRust(source),
                headerContext = FileHeaderContext(packageName = "io.github.kotlinmania.rustlang.core.num.nonzero"),
            )
        assertContains(output, "public open class NonZero<T : ZeroablePrimitive<NonZeroInner>, NonZeroInner>")
        assertContains(output, "public val fieldStorage: MutPtr")
        assertContains(output, "public var field0: NonZeroInner")
        assertFalse(output.contains("T.NonZeroInner"), "Unexpected qualified assoc projection:\n$output")
    }

    @Test
    fun traitInheritingImplicitCloneOmitsUnusedSelfParameter() {
        val source =
            """
            pub unsafe trait Allocator {}

            pub unsafe trait AllocatorClone: Allocator + Clone {}
            """.trimIndent()
        val output = translateRustSourceToKotlin(recognizeRust(source))
        assertContains(output, "interface AllocatorClone : Allocator")
        assertFalse(output.contains("AllocatorClone<T>"), "Unexpected unused <T> on AllocatorClone:\n$output")
    }

    @Test
    fun externRustAllocShimsConvertAlignmentViaAsUsize() {
        val source =
            """
            pub struct Alignment;
            impl Alignment {
                pub const fn as_usize(self) -> usize { 1 }
            }

            pub struct NonNull<T>(*mut T);

            unsafe extern "Rust" {
                fn __rust_alloc(size: usize, align: Alignment) -> *mut u8;
                fn __rust_dealloc(ptr: NonNull<u8>, size: usize, align: Alignment);
                fn __rust_realloc(ptr: NonNull<u8>, old_size: usize, align: Alignment, new_size: usize) -> *mut u8;
                fn __rust_alloc_zeroed(size: usize, align: Alignment) -> *mut u8;
            }
            """.trimIndent()
        val output =
            translateRustSourceToKotlin(
                recognizeRust(source),
                headerContext = FileHeaderContext(packageName = "io.github.kotlinmania.rustlang.alloc.alloc"),
            )
        assertContains(output, "kmAlloc(size.toLong(), align.asUsize().toLong())")
        assertContains(output, "kmDealloc(ptr.rawAddress, size.toLong(), align.asUsize().toLong())")
        assertContains(output, "kmRealloc(ptr.rawAddress, oldSize.toLong(), align.asUsize().toLong(), newSize.toLong())")
        assertContains(output, "kmAllocZeroed(size.toLong(), align.asUsize().toLong())")
    }

    @Test
    fun okOrUnderTaggedCoreResultEmitsExplicitTypeArguments() {
        val source =
            """
            pub struct AllocError;
            pub struct Ptr;
            impl Ptr {
                pub fn new() -> Option<Ptr> { None }
            }

            pub fn try_alloc() -> Result<Ptr, AllocError> {
                Ptr::new().ok_or(AllocError)
            }
            """.trimIndent()
        val output =
            translateRustSourceToKotlin(
                recognizeRust(source),
                headerContext =
                    FileHeaderContext(
                        packageName = "io.github.kotlinmania.rustlang.alloc.alloc",
                        crateNamespaces = mapOf("core" to "io.github.kotlinmania.rustlang.core"),
                    ),
            )
        assertContains(output, "Result.Ok<Ptr, AllocError>(it)")
        assertContains(output, "Result.Err<Ptr, AllocError>(AllocError())")
    }

    @Test
    fun stdlibWildcardReexportEmitsWhenExternalAbiHasPackage() {
        val source =
            """
            pub use core::alloc::*;

            pub fn use_alloc<A: AllocatorClone>(_a: A) {}
            """.trimIndent()
        val output =
            translateRustSourceToKotlin(
                recognizeRust(source),
                headerContext =
                    FileHeaderContext(
                        packageName = "io.github.kotlinmania.rustlang.alloc.alloc",
                        crateNamespaces = mapOf("core" to "io.github.kotlinmania.rustlang.core"),
                        externalCrateSymbolPackages =
                            mapOf(
                                "core" to
                                    mapOf(
                                        "AllocatorClone" to listOf("io.github.kotlinmania.rustlang.core.alloc"),
                                    ),
                            ),
                    ),
            )
        assertContains(output, "import io.github.kotlinmania.rustlang.core.alloc.*")
    }

    @Test
    fun inheritedAssocTypesPropagateFromFnOnceThroughFnAndDerefMut() {
        val source =
            """
            pub trait Tuple {}

            pub trait FnOnce<Args: Tuple> {
                type Output;
                fn call_once(self, args: Args) -> Self::Output;
            }

            pub trait FnMut<Args: Tuple>: FnOnce<Args> {
                fn call_mut(&mut self, args: Args) -> Self::Output;
            }

            pub trait Fn<Args: Tuple>: FnMut<Args> {
                fn call(&self, args: Args) -> Self::Output;
            }

            pub trait Deref {
                type Target;
                fn deref(&self) -> &Self::Target;
            }

            pub trait DerefMut: Deref {
                fn deref_mut(&mut self) -> &mut Self::Target;
            }
            """.trimIndent()
        val output =
            translateRustSourceToKotlin(
                recognizeRust(source),
                headerContext =
                    FileHeaderContext(
                        packageName = "io.github.kotlinmania.rustlang.core.ops.function",
                        crateNamespaces = mapOf("core" to "io.github.kotlinmania.rustlang.core"),
                    ),
            )
        assertContains(output, "public interface FnOnce<Args, out Output>")
        assertContains(output, "public interface FnMut<Args, out Output> : FnOnce<Args, Output>")
        assertContains(output, "public interface Fn<Args, out Output> : FnMut<Args, Output>")
        assertContains(output, "public interface Deref<out Target>")
        assertContains(output, "public interface DerefMut<Target> : Deref<Target>")
        assertFalse(output.contains("Args : Tuple"), "Unexpected Tuple bound:\n$output")
    }

    @Test
    fun fmtExponentAndPointerTraitsAreImplicitSupertraits() {
        val source =
            """
            pub struct F16b(u16);
            impl core::fmt::LowerExp for F16b {}
            impl core::fmt::UpperExp for F16b {}
            impl fmt::Pointer for F16b {}
            """.trimIndent()
        val output =
            translateRustSourceToKotlin(
                recognizeRust(source),
                headerContext =
                    FileHeaderContext(
                        packageName = "io.github.kotlinmania.rustlang.core.num.bfloat",
                        crateNamespaces = mapOf("core" to "io.github.kotlinmania.rustlang.core"),
                    ),
            )
        assertFalse(output.contains("LowerExp"), "Unexpected fmt.LowerExp supertrait:\n$output")
        assertFalse(output.contains("UpperExp"), "Unexpected fmt.UpperExp supertrait:\n$output")
        assertFalse(output.contains("fmt.Pointer"), "Unexpected fmt.Pointer supertrait:\n$output")
    }

    @Test
    fun splitTestModuleRootChunksAssignDistinctTestSuiteClassNamesWithoutPortLint() {
        val fnCount = 505
        val fns =
            (0 until fnCount).joinToString("\n") { idx ->
                "#[test] fn check_$idx() { assert_eq!($idx, $idx); }"
            }
        val source = "pub struct Io { pub val: u32 }\n$fns"
        val chunks =
            translateModuleRootIntoFocusedChunks(
                source = source,
                modulePascalName = "Io",
                headerContext =
                    FileHeaderContext(
                        portLintSourcePath = "tests/io/mod.rs",
                        packageName = "io.github.kotlinmania.rustlang.alloc.tests.io",
                        portLintDirective = "tests",
                        testClassName = "Io",
                    ),
            )
        val fnChunks = chunks.filter { it.fileName.startsWith("IoFunctions") }
        assertContains(chunks.map { it.fileName }.joinToString(","), "IoFunctions.kt")
        assertContains(chunks.map { it.fileName }.joinToString(","), "IoFunctions2.kt")
        val chunk1 = fnChunks.first { it.fileName == "IoFunctions.kt" }.text
        val chunk2 = fnChunks.first { it.fileName == "IoFunctions2.kt" }.text
        assertContains(chunk1, "public class IoFunctionsTests")
        assertContains(chunk2, "public class IoFunctions2Tests")
        for (chunk in chunks) {
            assertFalse(chunk.text.contains("// port-lint:"), "Unexpected port-lint header in ${chunk.fileName}")
        }
    }
}
