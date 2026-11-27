package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.syn.Item
import io.github.kotlinmania.syn.Lit
import io.github.kotlinmania.syn.parseFile
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KotlinTokenEmitterAndOopArchitectureTest {
    @Test
    fun kotlinTokenEmitterAndSubclassSpacingRulesEmitStructuredDeclarations() {
        val decl =
            KotlinDeclarationEmitter()
                .kdoc(listOf("Computes scaled offset."))
                .keyword("public")
                .keyword("fun")
                .ident("computeOffset")
                .delimited("(", ")") { params ->
                    params
                        .ident("index")
                        .punct(':')
                        .ident("Int")
                        .punct(',')
                        .ident("stride")
                        .punct(':')
                        .ident("Int")
                }.punct(':')
                .ident("Int")
                .punct('{')
                .newline()
                .indented {
                    keyword("return")
                        .ident("index")
                        .operator("*")
                        .ident("stride")
                        .newline()
                }.punct('}')
                .render()

        assertEquals(
            """
            |/**
            | * Computes scaled offset.
            | */
            |public fun computeOffset(index: Int, stride: Int): Int {
            |    return index * stride
            |}
            """.trimMargin(),
            decl,
        )

        class PaddedCallTokenEmitter : KotlinTokenEmitter() {
            override fun spawnInlineChild(): KotlinTokenEmitter = PaddedCallTokenEmitter()

            override fun needsSpaceBetween(prev: KotlinEmitToken, next: KotlinEmitToken): Boolean {
                if (prev is KotlinEmitToken.Ident && next is KotlinEmitToken.Delimited && next.open == "(") {
                    return true
                }
                return super.needsSpaceBetween(prev, next)
            }
        }

        val customDsl =
            PaddedCallTokenEmitter()
                .ident("dslInvoke")
                .delimited("(", ")", padInside = true) { inner ->
                    inner.literal("42")
                }.render()
        assertEquals("dslInvoke ( 42 )", customDsl)

        val docOut =
            KotlinFileDocumentEmitter(
                FileHeaderContext(
                    packageName = "io.github.kotlinmania.demo",
                    portLintSourcePath = "src/sample.rs",
                    portLintDirective = "source",
                ),
            ).emitPortLintHeaderIfPresent()
                .emitUnsignedOptInIfNeeded(needed = true)
                .emitPackageHeaderIfPresent()
                .emitImportBlock(listOf("import kotlin.math.max"))
                .emitFileDocComments(listOf("Module overview."))
                .emitBodyItemsAndTrailingComments(
                    bodyItems = listOf("public val answer: Int = max(40, 42)"),
                    renderedTrailingComments = "// End of module\n",
                )

        assertContains(docOut, "// port-lint: source src/sample.rs\n@file:OptIn(ExperimentalUnsignedTypes::class)\n\n")
        assertContains(docOut, "package io.github.kotlinmania.demo\n\nimport kotlin.math.max\n\n// Module overview.\n\n")
        assertContains(docOut, "public val answer: Int = max(40, 42)\n\n// End of module\n")
    }

    @Test
    fun kotlinTokenIndenterPreservesMultilineRawStringInteriorsWhileIndentingCode() {
        val rawQuote = "\"\"\""
        val unindentedTest =
            buildString {
                append("@kotlin.test.Test\n")
                append("public fun checkMultilinePayload() {\n")
                append("    val expected = ")
                append(rawQuote)
                append("\nfirstRawLine\n  secondRawLine\n")
                append(rawQuote)
                append("\n")
                append("    kotlin.test.assertTrue(expected.isNotEmpty())\n")
                append("}")
            }

        val suite =
            KotlinDeclarationEmitter()
                .emitSuiteClass(
                    visibility = "public",
                    className = "RawStringSuite",
                    memberDeclarations = listOf(unindentedTest),
                ).render()

        assertContains(suite, "    @kotlin.test.Test\n    public fun checkMultilinePayload() {")
        assertContains(suite, "\nfirstRawLine\n  secondRawLine\n$rawQuote")
    }

    @Test
    fun rustTokenStreamEmitterAndCommentSegmentEmitterSupportSubclassExtension() {
        class CustomMacroTokenEmitter(
            trees: List<io.github.kotlinmania.procmacro2.TokenTree>,
        ) : RustTokenStreamKotlinEmitter(trees) {
            override fun spawnChild(
                childTrees: List<io.github.kotlinmania.procmacro2.TokenTree>,
            ): RustTokenStreamKotlinEmitter = CustomMacroTokenEmitter(childTrees)

            override fun tryConsumeSpecialAt(idx: Int, out: KotlinTokenEmitter): Int? {
                if (cleanIdentAt(idx) == "custom_op" && punctCharAt(idx + 1) == '!') {
                    val grp = groupAt(idx + 2)
                    if (grp != null) {
                        out.macroCall(callee = "customOpDsl", open = "(", close = ")") { inner ->
                            CustomMacroTokenEmitter(grp.stream().toList()).emitInto(inner)
                        }
                        return idx + 3
                    }
                }
                return super.tryConsumeSpecialAt(idx, out)
            }
        }

        val lexed = lexRustTokenTrees("custom_op!(alpha_val + 1) + custom_op!(beta_val)")
        val renderedTokens = CustomMacroTokenEmitter(lexed).render()
        assertEquals("customOpDsl(alphaVal + 1) + customOpDsl(betaVal)", renderedTokens)

        val commentLine = "See [`std::vec::Vec`] and /* inner_note */ with #[derive(Clone)] on `my_field`"
        val segments = CommentSegmentTokenizer(commentLine).tokenize(parseMarkdownSpans = true)
        assertTrue(segments.any { it is CommentSegmentToken.BacktickLink })
        assertTrue(segments.any { it is CommentSegmentToken.InlineBlockComment })
        assertTrue(segments.any { it is CommentSegmentToken.InlineRustAttr })
        assertTrue(segments.any { it is CommentSegmentToken.InlineCode })

        class UppercaseCodeCommentEmitter : MarkdownCommentEmitter() {
            override fun emitSegment(token: CommentSegmentToken, out: StringBuilder) {
                if (token is CommentSegmentToken.InlineCode) {
                    out.append("`").append(token.code.uppercase()).append("`")
                } else {
                    super.emitSegment(token, out)
                }
            }
        }

        val customRendered = UppercaseCodeCommentEmitter().emitLine(commentLine)
        assertContains(customRendered, "[std.vec.Vec]")
        assertContains(customRendered, "// innerNote")
        assertContains(customRendered, "@derive(Clone)")
        assertContains(customRendered, "`MY_FIELD`")
    }

    @Test
    fun kotlinRenderedTypeAndCalleeShapeTokenizeNestedGenericsAndFunctionTypes() {
        val nestedType = "pkg.Outer<Inner<A, B>>.Entry<C, Map<String, Int>>?"
        val parsed = nestedType.asKotlinRenderedType()!!
        assertTrue(parsed.isNullable)
        assertEquals("pkg.Outer<Inner<A, B>>.Entry", parsed.headPath)
        assertEquals("Entry", parsed.simpleName)
        assertEquals("Entry", nestedType.simpleKotlinTypeName())
        assertEquals("pkg.Outer<Inner<A, B>>.Entry", nestedType.headKotlinTypePath())
        assertEquals(listOf("C", "Map<String, Int>"), parsed.typeArgs)

        val fnType = "((List<Int>) -> Pair<String, Long>)?"
        val parsedFn = fnType.asKotlinRenderedType()!!
        assertTrue(parsedFn.isNullable)
        assertEquals("(List<Int>) -> Pair<String, Long>", fnType.headKotlinTypePath())
        assertEquals("", fnType.simpleKotlinTypeName())
        assertEquals("Pair<String, Long>", parsedFn.callableReturnType)

        val callee = KotlinCalleeShape.parse("pkg.Container<Alpha, Beta>.create<Gamma, Pair<Int, String>>")
        assertEquals("pkg.Container<Alpha, Beta>.create", callee.basePath)
        assertEquals("pkg.Container.create", callee.erasedBasePath)
        assertEquals("pkg.Container<Alpha, Beta>", callee.ownerPath)
        assertEquals("create", callee.memberName)
        assertEquals(listOf("Gamma", "Pair<Int, String>"), callee.typeArgs)
        assertEquals("<Gamma, Pair<Int, String>>", callee.typeArgsSuffix)

        val simdParsed = SimdVectorIdentLexer.parse("u32x8")!!
        assertEquals('u', simdParsed.kindChar)
        assertEquals("32", simdParsed.widthToken)
        assertEquals(8, simdParsed.lanes)
        assertNull(SimdVectorIdentLexer.parse("u32x0"))
        assertNull(SimdVectorIdentLexer.parse("notSimd"))
    }

    @Test
    fun tomlTokenizerAndManifestEditorPreserveCommentsAndHandleQuotedBrackets() {
        val sampleToml =
            """
            |# Leading comment with [ignored] brackets
            |[versions] # inline header comment
            |kotlin = "2.4.20"
            |
            |[libraries]
            |demo = { module = "io.example:demo-[bracket]", version.ref = "kotlin" }
            |
            |[bundles] # bundle header comment
            |sample-commonMain = ["demo"] # keep trailing comment
            |
            """.trimMargin()

        val tokens = TomlTokenizer.tokenize(sampleToml)
        assertEquals(sampleToml, tokens.joinToString("") { it.rawText })

        val updated = ensureRustLangInLibsVersionsToml(sampleToml, version = "0.2.0")
        assertContains(updated, """rustLang = "0.2.0"""")
        assertContains(
            updated,
            """rust-lang-kotlin = { module = "io.github.kotlinmania:rust-lang-kotlin", version.ref = "rustLang" }""",
        )
        assertContains(updated, """sample-commonMain = ["demo", "rust-lang-kotlin"] # keep trailing comment""")
        assertContains(updated, "rust-lang-commonMain = [")
    }

    @Test
    fun rustLiteralEmitterAndItemEmitterRegistryAllowCleanOopExtension() {
        val rustSrc =
            """
            pub const MAGIC_NUM: u32 = 7;
            pub fn read_magic() -> u32 {
                MAGIC_NUM
            }
            """.trimIndent()

        val parsedFile = parseFile(rustSrc).getOrThrow()
        val customConstRule =
            object : TypedRustItemEmissionRule<Item.Const>() {
                override fun castOrNull(item: Item): Item.Const? = item as? Item.Const

                override fun emitTyped(item: Item.Const, ctx: ItemEmissionContext): String =
                    KotlinDeclarationEmitter(baseIndent = ctx.indent)
                        .annotation(name = "CustomConstTag")
                        .newline()
                        .keyword("public")
                        .keyword("const")
                        .keyword("val")
                        .ident(rustConstIdentToKotlin(item.ident.toString()))
                        .punct(':')
                        .ident("UInt")
                        .operator("=")
                        .literal("777u")
                        .render()
            }

        val customRegistry = RustItemEmitterRegistry.Default.withPrependedRule(customConstRule)
        val emitter =
            KotlinJ2KEmitter(
                source = rustSrc,
                originalFile = parsedFile,
                file = parsedFile,
                trivia = SourceTrivia.collect(rustSrc, parsedFile.toTokenStream()),
                macroDefs = emptyMap(),
                macroWrapperSpansByTargetOffset = emptyMap(),
                headerContext = FileHeaderContext(packageName = "io.github.kotlinmania.custom"),
                itemEmitterRegistry = customRegistry,
            )

        val emittedFile = emitter.emitFile()
        assertContains(emittedFile, "@CustomConstTag\npublic const val MAGIC_NUM: UInt = 777u")
        assertContains(emittedFile, "public fun readMagic(): UInt")

        val customHexBoolRule =
            object : RustLiteralEmissionRule() {
                override fun tryEmit(lit: Lit, ctx: RustLiteralKotlinEmitter): String? =
                    if (lit is Lit.Bool) "/*bool*/${lit.value.value()}" else null
            }
        val customLitEmitter =
            RustLiteralKotlinEmitter(
                emitter = emitter,
                rules = listOf(customHexBoolRule) + RustLiteralKotlinEmitter.DEFAULT_RULES,
            )
        val boolExprFile = parseFile("pub const FLAG: bool = true;").getOrThrow()
        val boolConst = boolExprFile.items.single() as Item.Const
        val boolLit = (boolConst.expr as io.github.kotlinmania.syn.Expr.Lit).lit
        assertEquals("/*bool*/true", customLitEmitter.emitLiteral(boolLit))
    }
}
