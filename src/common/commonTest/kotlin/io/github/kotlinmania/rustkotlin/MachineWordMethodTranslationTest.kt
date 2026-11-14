package io.github.kotlinmania.rustkotlin

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class MachineWordMethodTranslationTest {
    @Test
    fun originalThreadMethodsKeepTheSameMachineWordWidthAsTheirFields() {
        // Original Thread declaration and constructor from thread_local 1.1.9.
        val source =
            """
            pub(crate) struct Thread {
                /// The thread ID obtained from the thread ID manager.
                pub(crate) id: usize,
                /// The bucket this thread's local storage will be in.
                pub(crate) bucket: usize,
                /// The size of the bucket this thread's local storage will be in.
                pub(crate) bucket_size: usize,
                /// The index into the bucket this thread's local storage is in.
                pub(crate) index: usize,
            }
            impl Thread {
                pub(crate) fn new(id: usize) -> Self {
                    let bucket = usize::from(POINTER_WIDTH) - ((id + 1).leading_zeros() as usize) - 1;
                    let bucket_size = 1 << bucket;
                    let index = id - (bucket_size - 1);
            
                    Self {
                        id,
                        bucket,
                        bucket_size,
                        index,
                    }
                }
            }
            """.trimIndent()
        val header = FileHeaderContext(rawAbiMode = true, cfgTargetProfile = CfgTargetProfile(pointerWidth = 64))
        val output = translateRustSourceToKotlin(recognizeRust(source), header)
        assertContains(output, "id: ULong")
        assertContains(output, "fun new(id: ULong)")
        assertFalse(output.contains("fun new(id: Int)"))
    }
}
