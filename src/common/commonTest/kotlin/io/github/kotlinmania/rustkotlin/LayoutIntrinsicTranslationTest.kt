package io.github.kotlinmania.rustkotlin

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class LayoutIntrinsicTranslationTest {
    @Test fun upstreamLayoutArrayEdgeCasesPreservesGenericSizeQuery() {
        // rust_lang/library/coretests/tests/alloc.rs @ 34228cf7f5bdd08d320dedf008b8995dd3e743e2
        val rustSource =
            """
            #[test]
            fn layout_array_edge_cases() {
                for_type::<i64>();
                for_type::<[i32; 0b10101]>();
                for_type::<[u8; 0b1010101]>();

                // Make sure ZSTs don't lead to divide-by-zero
                assert_eq!(Layout::array::<()>(usize::MAX).unwrap(), Layout::from_size_align(0, 1).unwrap());

                fn for_type<T>() {
                    const MAX_SIZE: usize = isize::MAX as usize;

                    let edge = (MAX_SIZE + 1) / size_of::<T>();
                    let low = edge.saturating_sub(10);
                    let high = edge.saturating_add(10);
                    assert!(Layout::array::<T>(low).is_ok());
                    assert!(Layout::array::<T>(high).is_err());
                    for n in low..=high {
                        assert_eq!(Layout::array::<T>(n).is_ok(), n * size_of::<T>() <= MAX_SIZE);
                    }
                }
            }
            """.trimIndent()
        val output = translateRustSourceToKotlin(recognizeRust(rustSource))
        assertContains(output, "typeLayoutT: TypeLayout<T>")
        assertContains(output, "sizeOf<T>(typeLayoutT = typeLayoutT)")
        assertFalse(output.contains(" / 8"))
        assertFalse(output.contains("n * 8"))
        ZeroUnderscoreIdentifierValidator.requireZeroViolations(output, "LayoutIntrinsic.kt")
    }
}
