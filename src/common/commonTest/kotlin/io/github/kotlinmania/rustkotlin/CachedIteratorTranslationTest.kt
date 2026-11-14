package io.github.kotlinmania.rustkotlin

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class CachedIteratorTranslationTest {
    @Test
    fun upstreamCachedIteratorDoesNotShadowItsCanonicalInnerType() {
        // Original declarations and owned IntoIterator implementation from
        // threadlocal-kotlin/tmp/thread_local-1.1.9/src/cached.rs.
        val source =
            """
            use super::{IntoIter, IterMut, ThreadLocal};

            pub struct CachedThreadLocal<T: Send> {
                inner: ThreadLocal<T>,
            }

            impl<T: Send> IntoIterator for CachedThreadLocal<T> {
                type Item = T;
                type IntoIter = CachedIntoIter<T>;

                fn into_iter(self) -> CachedIntoIter<T> {
                    CachedIntoIter {
                        inner: self.inner.into_iter(),
                    }
                }
            }

            pub struct CachedIntoIter<T: Send> {
                inner: IntoIter<T>,
            }
            """.trimIndent()
        val output = translateRustSourceToKotlin(recognizeRust(source))
        assertContains(output, "inner: IntoIter<T>")
        assertFalse(output.contains("typealias IntoIter"))
    }
}
