package io.github.kotlinmania.rustkotlin

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class OpaquePointerSupportTest {
    @Test
    fun supportPreservesOnlyExplicitAddressCarriers() {
        // Raw pointers live in core::ptr as non-nullable address carriers (0L == null).
        val constOut = CoreIntrinsicFfiArtifacts.renderConstPtrClassHeader()
        val mutOut = CoreIntrinsicFfiArtifacts.renderMutPtrClassHeader()
        assertContains(constOut, "public class ConstPtr(")
        assertContains(constOut, "public val rawAddress: Long,")
        assertContains(mutOut, "public class MutPtr(")
        assertContains(mutOut, "public val rawAddress: Long,")
        assertFalse(constOut.contains("Any.value"))
        assertFalse(constOut.contains("COpaquePointer"))
        assertFalse(mutOut.contains("Any.value"))
        assertFalse(mutOut.contains("COpaquePointer"))
        ZeroUnderscoreIdentifierValidator.requireZeroViolations(constOut, "ConstPtr.kt")
        ZeroUnderscoreIdentifierValidator.requireZeroViolations(mutOut, "MutPtr.kt")
    }
}
