package io.github.kotlinmania.rustkotlin

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class StructLayoutComputerTest {
    @Test
    fun reprCNaturalAlignmentAndTrailingPaddingAreComputedDeterministically() {
        val rustSource =
            """
            #[repr(C)]
            pub struct Header {
                pub tag: u8,
                pub count: u32,
                pub flags: u16,
            }
            """.trimIndent()
        val kt = renderSingleKotlinFile(rustSource).text
        assertContains(kt, "override val sizeBytes: Long = 12L")
        assertContains(kt, "override val alignBytes: Long = 4L")
        assertContains(kt, "PrimitiveLayouts.U8).readNative(addr)")
        assertContains(kt, "PrimitiveLayouts.U32).readNative(addr + 4L)")
        assertContains(kt, "PrimitiveLayouts.U16).readNative(addr + 8L)")
        ZeroUnderscoreIdentifierValidator.requireZeroViolations(kt, "Header.kt")
    }

    @Test
    fun reprPackedEliminatesInterFieldAndTrailingPadding() {
        val rustSource =
            """
            #[repr(C, packed)]
            pub struct PackedHeader {
                pub tag: u8,
                pub count: u32,
                pub flags: u16,
            }
            """.trimIndent()
        val kt = renderSingleKotlinFile(rustSource).text
        assertContains(kt, "override val sizeBytes: Long = 7L")
        assertContains(kt, "override val alignBytes: Long = 1L")
        assertContains(kt, "PrimitiveLayouts.U32).readNative(addr + 1L)")
        assertContains(kt, "PrimitiveLayouts.U16).readNative(addr + 5L)")
        ZeroUnderscoreIdentifierValidator.requireZeroViolations(kt, "PackedHeader.kt")
    }

    @Test
    fun reprAlignAndNestedArrayAndPointerFieldsComputeExactOffsets() {
        val rustSource =
            """
            #[repr(C, align(16))]
            pub struct AlignedBlock {
                pub lanes: [u16; 3],
                pub next: *const u8,
            }
            """.trimIndent()
        val kt = renderSingleKotlinFile(rustSource).text
        assertEquals(16L, StructLayoutComputer.alignUp(16L, 16L))
        assertContains(kt, "override val sizeBytes: Long = 16L")
        assertContains(kt, "override val alignBytes: Long = 16L")
        assertContains(kt, "constPtrLayout(64)).readNative(addr + 8L)")
        ZeroUnderscoreIdentifierValidator.requireZeroViolations(kt, "AlignedBlock.kt")
    }

    @Test
    fun genericStructCarriesRuntimeTypeLayoutDictionaryParameter() {
        val rustSource =
            """
            #[repr(C)]
            pub struct TaggedSlot<T> {
                pub tag: u8,
                pub item: T,
            }
            """.trimIndent()
        val kt = renderSingleKotlinFile(rustSource).text
        assertContains(
            kt,
            "public fun <T> typeLayout(typeLayoutT: io.github.kotlinmania.rustlang.core.mem.TypeLayout<T>):",
        )
        assertContains(kt, "alignUpBytes(")
        assertContains(kt, "(typeLayoutT).readNative(")
        ZeroUnderscoreIdentifierValidator.requireZeroViolations(kt, "TaggedSlot.kt")
    }
}
