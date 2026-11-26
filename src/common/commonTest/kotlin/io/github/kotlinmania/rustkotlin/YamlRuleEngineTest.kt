package io.github.kotlinmania.rustkotlin

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class YamlRuleEngineTest {
    @Test
    fun parsesCommentsQuotedPunctuationNestedSequencesAndBlockScalars() {
        val sampleYaml =
            """
            |# Leading section comment
            |punctTokens:
            |  "#": Pound   # inline comment after hash key
            |  "::": PathSep # inline comment after double-colon key
            |  "-": Minus
            |  'single''quote': EscapedSingle
            |
            |patternRules:
            |  - match: "Vec" # sequence item with inline comment
            |    minArity: 1
            |    imports: ["{coreNs}.vec.Vec"]
            |    replace: "List<{0}>"
            |  - { match: "Box Rc Arc", minArity: 1, replace: "{0}" }
            |
            |docSnippet: |-
            |  # Preserved hash inside literal block scalar
            |  second line
            """.trimMargin()

        val root = YamlDocumentParser.parse(sampleYaml, sourceName = "test-sample.yml")
        val punct = root.stringMap("punctTokens")
        assertEquals("Pound", punct["#"])
        assertEquals("PathSep", punct["::"])
        assertEquals("Minus", punct["-"])
        assertEquals("EscapedSingle", punct["single'quote"])

        val rules = root.mappingList("patternRules").map(DeclarativeTypePathPatternRule::fromYaml)
        assertEquals(2, rules.size)
        val recordedImports = mutableListOf<String>()
        val renderedVec =
            rules[0].tryMatchAndRender(
                rawName = "Vec",
                segmentIdents = listOf("Vec"),
                typeArgs = listOf("UByte"),
                declaredTypeNames = emptySet(),
                coreNs = "io.github.kotlinmania.rustlang.core",
                onImport = recordedImports::add,
            )
        assertEquals("List<UByte>", renderedVec)
        assertEquals(listOf("io.github.kotlinmania.rustlang.core.vec.Vec"), recordedImports)

        assertEquals(
            "# Preserved hash inside literal block scalar\nsecond line",
            root.string("docSnippet"),
        )
    }

    @Test
    fun rejectsMalformedYamlAndUnboundTemplatePlaceholdersFailFast() {
        val dupKeyErr =
            assertFailsWith<IllegalArgumentException> {
                YamlDocumentParser.parse("alpha: 1\nalpha: 2\n", sourceName = "dup.yml")
            }
        assertContains(dupKeyErr.message.orEmpty(), "dup.yml:2")

        val tabErr =
            assertFailsWith<IllegalArgumentException> {
                YamlDocumentParser.parse("items:\n\t- bad\n", sourceName = "tabs.yml")
            }
        assertContains(tabErr.message.orEmpty(), "tabs.yml:2")

        val template = YamlTemplate.compile("object {{ fun build() = {missingKey} }}")
        assertFailsWith<IllegalArgumentException> {
            template.render { null }
        }
    }

    @Test
    fun loadsAllBundledYamlCatalogsAndDrivesEndToEndPatternReplacements() {
        assertEquals(46, YamlRuleCatalogs.StdlibSynAndFfi.synPunctTokens.size)
        assertEquals("Pound", YamlRuleCatalogs.StdlibSynAndFfi.synPunctTokens["#"])
        assertTrue(YamlRuleCatalogs.TypeAndMethodPatterns.stdlibGenericTypeRules.isNotEmpty())
        assertTrue(YamlRuleCatalogs.TypeAndMethodPatterns.rawPtrMethodRules.containsKey("write_bytes"))
        assertTrue(YamlRuleCatalogs.CoreIntrinsics.controlAndHintRulesByName.containsKey("select_unpredictable"))

        val rustSource =
            """
            use std::collections::HashMap;
            use std::cell::OnceCell;
            use std::mem::MaybeUninit;

            pub unsafe fn exercise_yaml_rules(
                ptr: *mut u32,
                other: *mut u32,
                cond: bool,
                bits: f32,
            ) -> HashMap<String, Vec<u8>> {
                let cell: OnceCell<i32> = OnceCell::new();
                let _slot: MaybeUninit<u64> = MaybeUninit::uninit();
                if !ptr.is_null() {
                    ptr.write_bytes(0, 4);
                }
                let _diff = ptr.offset_from(other);
                let _picked = core::intrinsics::select_unpredictable(cond, 10i32, 20i32);
                let _raw: u32 = core::mem::transmute::<f32, u32>(bits);
                let _ = cell;
                HashMap::new()
            }
            """.trimIndent()

        val kt = renderSingleKotlinFile(rustSource, FileHeaderContext(packageName = "sample.rules")).text
        assertContains(kt, "MutableMap<String, List<UByte>>")
        assertContains(kt, "OnceCell<Int>")
        assertContains(kt, "MaybeUninit<ULong>")
        assertContains(kt, "((ptr).rawAddress == 0L)")
        assertContains(kt, "kmWriteBytes((ptr).rawAddress, (0).toUByte(), (4).toLong() * ")
        assertContains(kt, "(((ptr).rawAddress - (other).rawAddress) / ")
        assertContains(kt, "if (cond) 10 else 20")
        assertContains(kt, "(bits).toRawBits().toUInt()")
    }

    @Test
    fun drivesCalleePostArgMethodConstAndCAbiYamlCatalogsEndToEnd() {
        assertEquals(5, BundledYamlRuleSources.filesByName.size)
        assertEquals(20, YamlRuleCatalogs.PathsConstsAndCAbi.cAbiBridgeDescriptorNodes.size)
        assertEquals("32u", YamlRuleCatalogs.PathsConstsAndCAbi.intBitsByPrim["u32"])
        assertTrue(YamlRuleCatalogs.TypeAndMethodPatterns.calleePatternRules.isNotEmpty())
        assertTrue(YamlRuleCatalogs.TypeAndMethodPatterns.erasedOptionMethodRules.containsKey("map_or_else"))
        assertTrue(YamlRuleCatalogs.TypeAndMethodPatterns.unconstrainedCmpMethodRules.containsKey("partial_cmp"))
        assertTrue(YamlRuleCatalogs.CoreIntrinsics.rustGlobalAllocActionsByName.containsKey("__rust_realloc"))

        val rustSource =
            """
            use std::borrow::Cow;

            pub fn exercise_wave2_yaml_rules(
                text: &str,
                opt: Option<i32>,
            ) -> i32 {
                let mut items: Vec<i32> = Vec::with_capacity(4);
                let boxed = Box::new(7i32);
                items.push(boxed);
                let cow: Cow<str> = Cow::Borrowed(text);
                let stripped = cow.strip_prefix("pre_");
                std::mem::drop(stripped);
                let _tau = std::f64::consts::TAU;
                let _eps = f32::EPSILON;
                let _bits = u32::BITS;
                let mapped = opt.map_or_else(|| 0i32, |v| v + 1);
                std::cmp::min(mapped, items.len() as i32)
            }
            """.trimIndent()

        val kt = renderSingleKotlinFile(rustSource, FileHeaderContext(packageName = "sample.wave2")).text
        assertContains(kt, "ArrayList(4)")
        assertContains(kt, "val boxed = 7")
        assertContains(kt, "items.add(boxed)")
        assertContains(kt, "val cow: String = text")
        assertContains(kt, ".takeIf { it.startsWith(\"pre_\") }?.removePrefix(\"pre_\")")
        assertContains(kt, "(stripped).let { }")
        assertContains(kt, "6.283185307179586")
        assertContains(kt, "1.1920929E-7f")
        assertContains(kt, "32u")
        assertContains(kt, "opt?.let({ v -> v + 1 }) ?: 0")
        assertContains(kt, "minOf(mapped, items.size)")
    }

    @Test
    fun drivesCoreImportRoutingCastPointerLayoutAndFfiFallbackYamlCatalogs() {
        assertTrue(YamlRuleCatalogs.PathsConstsAndCAbi.coreAutoImportRoutes.isNotEmpty())
        assertTrue(YamlRuleCatalogs.PathsConstsAndCAbi.coreExtensionImportRoutes.isNotEmpty())
        assertTrue("register" in YamlRuleCatalogs.PathsConstsAndCAbi.cReservedParamNames)
        assertEquals("(-1).toByte()", YamlRuleCatalogs.PathsConstsAndCAbi.numericDefaultFallbackByKind[NumericKind.I8])
        assertEquals("Byte" to "8u", YamlRuleCatalogs.PathsConstsAndCAbi.primOwnerAndBitsByKind[NumericKind.I8])
        assertEquals("I32", YamlRuleCatalogs.TypeAndMethodPatterns.fixedScalarCodecs["i32"])
        assertEquals("UInt", YamlRuleCatalogs.TypeAndMethodPatterns.literalCodecTypes["U32"])

        val headerCtx =
            FileHeaderContext(
                packageName = "sample.wave3",
                crateNamespaces = mapOf("core" to "io.github.kotlinmania.rustlang.core"),
            )
        val rustSource =
            """
            pub unsafe fn exercise_wave3_yaml_rules(ptr: *mut i32, raw_len: usize) -> i64 {
                let mut local_val: i32 = 12;
                let addr: *mut i32 = &mut local_val;
                if !ptr.is_null() {
                    *ptr = *addr;
                }
                raw_len as i64
            }
            """.trimIndent()

        val kt = renderSingleKotlinFile(rustSource, headerCtx).text
        assertContains(kt, "import io.github.kotlinmania.rustlang.core.ptr.MutPtr")
        assertContains(kt, "import io.github.kotlinmania.rustlang.core.mem.PrimitiveLayouts")
        assertContains(kt, "AddressableLocal(12, PrimitiveLayouts.I32)")
        assertContains(kt, "return rawLen.toLong()")
    }
}
