package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.rustlang.core.ptr.ConstPtr
import io.github.kotlinmania.rustlang.core.ptr.MutPtr
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class OpaquePointerSupportTest {
    @Test
    fun supportImportsCoreAddressCarriersWithoutInliningDefinitions() {
        val rustSource =
            """
            pub fn forward_ptrs(src: *const u8, dst: *mut u8) -> (*const u8, *mut u8) {
                (src, dst)
            }
            """.trimIndent()
        val kt =
            renderSingleKotlinFile(
                rustSource,
                FileHeaderContext(packageName = "io.github.kotlinmania.sample"),
            ).text
        assertContains(kt, "import io.github.kotlinmania.rustlang.core.ptr.ConstPtr")
        assertContains(kt, "import io.github.kotlinmania.rustlang.core.ptr.MutPtr")
        assertFalse(kt.contains("class ConstPtr("))
        assertFalse(kt.contains("class MutPtr("))
        assertFalse(kt.contains("COpaquePointer"))
        ZeroUnderscoreIdentifierValidator.requireZeroViolations(kt, "ForwardPtrs.kt")

        val constPtr = ConstPtr(0x1234L)
        val mutPtr = MutPtr(0x5678L)
        assertEquals(0x1234L, constPtr.rawAddress)
        assertEquals(0x5678L, mutPtr.rawAddress)
    }
}
