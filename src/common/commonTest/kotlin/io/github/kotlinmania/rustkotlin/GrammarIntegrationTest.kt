package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.syn.Item
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GrammarIntegrationTest {
    @Test fun libraryLexerAndParserHandleProtectedNestedSyntax() {
        val parsed = recognizeRust("/* { /* ] */ } */ fn probe() { let x = r###\"} ) ] /*\"###; }")
        assertEquals(1, parsed.file.items.size)
        assertTrue(parsed.tokens.clone().count() > 0)
        assertEquals("probe", (parsed.file.items.single() as Item.Fn).sig.ident.toString())
        assertFailsWith<Exception> { recognizeRust("fn f() { (] }") }
    }

    @Test fun parserFailureRetainsExistingLibrarySourceLocation() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                recognizeRust("pub fn f() {}\nnot_an_item\n")
            }
        assertContains(error.message.orEmpty(), "Syn parser at 2:")
    }
}
