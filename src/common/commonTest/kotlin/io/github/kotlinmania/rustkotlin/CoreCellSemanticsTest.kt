package io.github.kotlinmania.rustkotlin

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class CoreCellSemanticsTest {
    @Test
    fun coreCellMarkerImplDoesNotSuppressAnyDestructor() {
        // Canonical library/core/src/cell.rs uses ManuallyDrop only here.
        val source =
            """
            use crate::mem::{self, ManuallyDrop};
            #[marker]
            pub unsafe trait CloneFromCell: Clone {}
            unsafe impl<T: CloneFromCell> CloneFromCell for ManuallyDrop<T> {}
            pub struct UnsafeCell<T> { value: T }
            impl<T> UnsafeCell<T> {
                pub const fn new(value: T) -> UnsafeCell<T> { UnsafeCell { value } }
                pub const fn into_inner(self) -> T { self.value }
            }
            """.trimIndent()
        val output = renderSingleKotlinFile(source).text
        assertContains(output, "class UnsafeCell<T>")
        assertContains(output, "fun intoInner(): T")
    }

    @Test
    fun importedManuallyDropStillRejectsActualSuppression() {
        val source =
            """
            use crate::mem::ManuallyDrop;
            pub struct Suppressed<T> { value: ManuallyDrop<T> }
            """.trimIndent()
        val failure = assertFailsWith<IllegalArgumentException> { renderSingleKotlinFile(source) }
        assertContains(failure.message.orEmpty(), "ManuallyDrop")
    }

    @Test
    fun emptyNonMarkerImplDoesNotBypassSuppressionGuard() {
        val source =
            """
            use crate::mem::ManuallyDrop;
            pub trait NotAMarker {}
            impl<T> NotAMarker for ManuallyDrop<T> {}
            """.trimIndent()
        val failure = assertFailsWith<IllegalArgumentException> { renderSingleKotlinFile(source) }
        assertContains(failure.message.orEmpty(), "ManuallyDrop")
    }

    @Test
    fun markerWithMembersDoesNotBypassSuppressionGuard() {
        val source =
            """
            use crate::mem::ManuallyDrop;
            #[marker]
            pub trait HasMembers { fn run(&self); }
            impl<T> HasMembers for ManuallyDrop<T> {}
            """.trimIndent()
        val failure = assertFailsWith<IllegalArgumentException> { renderSingleKotlinFile(source) }
        assertContains(failure.message.orEmpty(), "ManuallyDrop")
    }
}
