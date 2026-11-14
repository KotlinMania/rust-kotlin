package io.github.kotlinmania.rustkotlin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ZeroUnderscoreIdentifierValidatorTest {
    @Test
    fun allowsCamelPascalScreamingSnakeNumericSeparatorsAndStringLiterals() {
        val validKotlin =
            """
            // Comment with __rustFieldLayout and foo_bar_baz
            /* Nested /* __block_comment */ ignored */
            package io.github.kotlinmania.sample

            import kotlin.wasm.WasmImport

            public const val MAX_BUFFER_SIZE: Int = 1_048_576
            public const val MASK_64: ULong = 0xFFFF_FFFF_FFFF_FFFFuL

            @WasmImport("km_core", "km_load_u32")
            private external fun wasmKmLoadU32(addr: Long): Int

            public fun computeTotal(pairList: List<Pair<Int, Int>>): Int {
                val msg = "raw_c_symbol_name = ${'$'}{MAX_BUFFER_SIZE}"
                val rawBlock = ${"\"\"\""}
                    __c_macro_ident_inside_raw_string
                ${"\"\"\""}
                var acc = 0
                for ((_, rightValue) in pairList) {
                    acc += rightValue + msg.length + rawBlock.length
                }
                return acc
            }
            """.trimIndent()
        val violations = ZeroUnderscoreIdentifierValidator.findViolations(validKotlin)
        assertTrue(violations.isEmpty(), "Expected zero violations, got: $violations")
    }

    @Test
    fun rejectsAllNonScreamingSnakeUnderscoreForms() {
        val invalidKotlin =
            """
            public const val BAD__DOUBLE: Int = 1
            public const val _LEADING: Int = 2
            public const val TRAILING_: Int = 3
            public fun __span(): Int {
                val _b = 4
                val __rustTryResult = 5
                val snake_case = 6
                val msg = "interpolated ${'$'}__rustValue and ${'$'}{_ffi_static_errno}"
                val escaped = `backtick_ident`
                return _b + __rustTryResult + snake_case + msg.length + escaped
            }
            """.trimIndent()
        val violations = ZeroUnderscoreIdentifierValidator.findViolations(invalidKotlin).map { it.identifier }.distinct()
        assertEquals(
            listOf(
                "BAD__DOUBLE",
                "_LEADING",
                "TRAILING_",
                "__span",
                "_b",
                "__rustTryResult",
                "snake_case",
                "__rustValue",
                "_ffi_static_errno",
                "backtick_ident",
            ),
            violations,
        )
    }
}
