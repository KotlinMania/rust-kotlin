package io.github.kotlinmania.rustkotlin

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class GenericPointerLayoutTranslationTest {
    @Test
    fun genericMutabilityCastRetainsRawAddress() {
        val output =
            translateRustSourceToKotlin(
                recognizeRust("fn shared<T>(p: *mut T) -> *const T { p as *const T }"),
            )
        assertContains(output, "fun shared(p: MutPtr): ConstPtr")
        assertContains(output, "ConstPtr(p.rawAddress, p.rawMeta)")
        assertFalse(output.contains("org.rustlang.runtime"))
        assertFalse(output.contains("COpaquePointer"))
        ZeroUnderscoreIdentifierValidator.requireZeroViolations(output, "shared.kt")
    }

    @Test
    fun genericIdentityMethodCastRetainsRawAddress() {
        val output =
            translateRustSourceToKotlin(
                recognizeRust("fn identity<T>(p: *mut T) -> *mut T { p.cast::<T>() }"),
            )
        assertContains(output, "fun identity(p: MutPtr): MutPtr")
        assertFalse(output.contains("org.rustlang.runtime"))
        ZeroUnderscoreIdentifierValidator.requireZeroViolations(output, "identity.kt")
    }

    @Test
    fun inferredGenericIdentityMethodCastUsesReturnType() {
        val output =
            translateRustSourceToKotlin(
                recognizeRust("fn identity<T>(p: *mut T) -> *mut T { p.cast() }"),
            )
        assertContains(output, "fun identity(p: MutPtr): MutPtr")
        assertFalse(output.contains("org.rustlang.runtime"))
        ZeroUnderscoreIdentifierValidator.requireZeroViolations(output, "identityInferred.kt")
    }

    @Test
    fun genericPointerArithmeticThreadsRuntimeTypeLayoutEvidence() {
        val output =
            translateRustSourceToKotlin(
                recognizeRust("fn advance<T>(p: *mut T) -> *mut T { unsafe { p.add(1) } }"),
            )
        assertContains(output, "typeLayoutT: TypeLayout<T>")
        assertContains(output, "p: MutPtr")
        assertFalse(output.contains("org.rustlang.runtime"))
        ZeroUnderscoreIdentifierValidator.requireZeroViolations(output, "advance.kt")
    }
}
