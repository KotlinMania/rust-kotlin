package io.github.kotlinmania.rustkotlin

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class CoreOptionTranslationTest {
    @Test
    fun upstreamOptionDeclarationRetainsTaggedSelfType() {
        // Actual enum and as_ref implementation from the canonical core source:
        // rust_lang/library/core/src/option.rs, lines 590-608 and 743-748.
        // This checks translation, not a simulated runtime implementation.
        val rustSource =
            """
            /// The `Option` type. See [the module level documentation](self) for more.
            #[doc(search_unbox)]
            #[derive(Copy, Debug, Hash)]
            #[derive_const(Eq)]
            #[rustc_diagnostic_item = "Option"]
            #[lang = "Option"]
            #[stable(feature = "rust1", since = "1.0.0")]
            #[allow(clippy::derived_hash_with_manual_eq)] // PartialEq is manually implemented equivalently
            pub enum Option<T> {
                /// No value.
                #[lang = "None"]
                #[stable(feature = "rust1", since = "1.0.0")]
                None,
                /// Some value of type `T`.
                #[lang = "Some"]
                #[stable(feature = "rust1", since = "1.0.0")]
                Some(#[stable(feature = "rust1", since = "1.0.0")] T),
            }

            impl<T> Option<T> {
                pub const fn is_some(&self) -> bool {
                    matches!(*self, Some(_))
                }

                pub const fn is_none(&self) -> bool {
                    !self.is_some()
                }

                pub const fn as_ref(&self) -> Option<&T> {
                    match *self {
                        Some(ref x) => Some(x),
                        None => None,
                    }
                }
            }
            """.trimIndent()
        val output = translateRustSourceToKotlin(recognizeRust(rustSource))
        assertContains(output, "sealed class Option<T>")
        assertContains(output, "fun asRef(): Option<T>")
        assertFalse(output.contains("fun asRef(): T?"))
        assertContains(output, "Some(x)")
        assertFalse(output.contains("this != null"))
        assertFalse(output.contains("!this != null"))
        assertContains(output, "this.isSome()")
    }
}
