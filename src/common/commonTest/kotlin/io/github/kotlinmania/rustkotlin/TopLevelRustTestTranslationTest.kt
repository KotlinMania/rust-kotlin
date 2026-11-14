package io.github.kotlinmania.rustkotlin

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class TopLevelRustTestTranslationTest {
    @Test
    fun originalThreadDescriptorTestStaysOutOfProductSource() {
        // Original top-level test from thread_local 1.1.9/src/thread_id.rs.
        val source =
            """
            #[test]
            fn test_thread() {
                let thread = Thread::new(0);
                assert_eq!(thread.id, 0);
                assert_eq!(thread.bucket, 0);
                assert_eq!(thread.bucket_size, 1);
                assert_eq!(thread.index, 0);
            
                let thread = Thread::new(1);
                assert_eq!(thread.id, 1);
                assert_eq!(thread.bucket, 1);
                assert_eq!(thread.bucket_size, 2);
                assert_eq!(thread.index, 0);
            
                let thread = Thread::new(2);
                assert_eq!(thread.id, 2);
                assert_eq!(thread.bucket, 1);
                assert_eq!(thread.bucket_size, 2);
                assert_eq!(thread.index, 1);
            
                let thread = Thread::new(3);
                assert_eq!(thread.id, 3);
                assert_eq!(thread.bucket, 2);
                assert_eq!(thread.bucket_size, 4);
                assert_eq!(thread.index, 0);
            
                let thread = Thread::new(19);
                assert_eq!(thread.id, 19);
                assert_eq!(thread.bucket, 4);
                assert_eq!(thread.bucket_size, 16);
                assert_eq!(thread.index, 4);
            }
            """.trimIndent()
        val header =
            FileHeaderContext(
                cfgTargetProfile = CfgTargetProfile(disabledCfgFlags = setOf("test")),
            )
        val product = translateRustSourceToKotlin(recognizeRust(source), header)
        assertFalse(product.contains("fun testThread"))
        val tests = assertNotNull(renderInlineRustTests(source, header))
        assertContains(tests.text, "fun testThread")
        assertContains(tests.text, "@kotlin.test.Test")
    }
}
