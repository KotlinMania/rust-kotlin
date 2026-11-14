package io.github.kotlinmania.rustkotlin

import kotlin.test.Test
import kotlin.test.assertEquals

class KotlinStringsTest {
    @Test fun decodedLiteralsAreEncodedAsKotlinValues() {
        assertEquals("\"a\\\"\\\\\\$\\u000a\\ud83d\\ude42\"", kotlinString("a\"\\\$\n🙂"))
    }

    @Test fun unpairedSurrogatesAvoidConstantStringLiterals() {
        assertEquals("charArrayOf('\\ud800').concatToString()", kotlinString(charArrayOf('\ud800').concatToString()))
        assertEquals("charArrayOf('\\udc00').concatToString()", kotlinString(charArrayOf('\udc00').concatToString()))
        assertEquals(
            "charArrayOf('a', '\\ud800').concatToString()",
            kotlinString(charArrayOf('a', '\ud800').concatToString()),
        )
    }
}
