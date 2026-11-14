package io.github.kotlinmania.rustkotlin

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class CoreResultTranslationTest {
    @Test
    fun originalCoreResultRetainsItsErrorTypeAndTaggedVariants() {
        // Original enum, is_ok and as_ref from rust_lang/library/core/src/result.rs.
        val source =
            """
            pub enum Result<T, E> {
                /// Contains the success value
                #[lang = "Ok"]
                #[stable(feature = "rust1", since = "1.0.0")]
                Ok(#[stable(feature = "rust1", since = "1.0.0")] T),
            
                /// Contains the error value
                #[lang = "Err"]
                #[stable(feature = "rust1", since = "1.0.0")]
                Err(#[stable(feature = "rust1", since = "1.0.0")] E),
            }
            
            impl<T, E> Result<T, E> {
                pub const fn is_ok(&self) -> bool {
                    matches!(*self, Ok(_))
                }
            
                pub const fn as_ref(&self) -> Result<&T, &E> {
                    match *self {
                        Ok(ref x) => Ok(x),
                        Err(ref x) => Err(x),
                    }
                }
            }
            """.trimIndent()
        val output = translateRustSourceToKotlin(recognizeRust(source))
        assertContains(output, "sealed class Result<T, E>")
        assertContains(output, "fun asRef(): Result<T, E>")
        assertFalse(output.contains("Result.success"))
        assertFalse(output.contains("Result.failure"))
        assertFalse(output.contains(".isSuccess"))
    }
}
