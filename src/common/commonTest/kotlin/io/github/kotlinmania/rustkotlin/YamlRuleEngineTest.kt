package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.io.files.SystemFileSystem
import io.github.kotlinmania.io.files.readString
import io.github.kotlinmania.io.files.writeString
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import io.github.kotlinmania.io.files.Path as KmPath

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
    fun streamsAllRuntimeYamlRuleFilesAndDrivesEndToEndPatternReplacements() {
        val reg = activeRules()
        assertEquals(5, reg.loadedFileNames.size)
        assertEquals(46, reg.map("synPunctTokens").size)
        assertEquals("Pound", reg.map("synPunctTokens")["#"])
        assertTrue(reg.typeRulesByName.isNotEmpty())
        assertTrue(reg.rawPtrMethodRulesByName.containsKey("write_bytes"))
        assertTrue(reg.intrinsicRules("controlAndHintIntrinsicRules").containsKey("select_unpredictable"))

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
    fun drivesCalleeUnifiedMethodConstAndCAbiYamlRulesEndToEnd() {
        val reg = activeRules()
        assertEquals(20, reg.mappings("cAbiBridgeDescriptors").size)
        assertEquals("32u", reg.map("intBitsByPrim")["u32"])
        assertTrue(reg.calleeRules.isNotEmpty())
        assertTrue(reg.postArgMethodRulesByName.containsKey("map_or_else"))
        assertTrue(reg.unconstrainedCmpRulesByName.containsKey("partial_cmp"))
        assertTrue(reg.rustGlobalAllocActionsByName.containsKey("__rust_realloc"))

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
    fun drivesCoreImportRoutingCastPointerLayoutAndFfiFallbackYamlRules() {
        val reg = activeRules()
        assertTrue(reg.coreImportRoutes("coreAutoImportRoutes").isNotEmpty())
        assertTrue(reg.coreImportRoutes("coreExtensionImportRoutes").isNotEmpty())
        assertTrue("register" in reg.words("cReservedParamNames"))
        assertEquals("(-1).toByte()", reg.numericKindKeyMap("numericDefaultFallbackByKind")[NumericKind.I8])
        assertEquals("Byte" to "8u", reg.primOwnerAndBitsByKind[NumericKind.I8])
        assertEquals("I32", reg.map("fixedScalarCodecs")["i32"])
        assertEquals("UInt", reg.map("literalCodecTypes")["U32"])

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

    @Test
    fun reloadsModifiedYamlRuleDirectoryAtRuntimeWithoutRecompilingKotlin() {
        val srcRulesDir = resolveFilesystemRulesDirectory(explicitDir = null) ?: error("Missing rules directory in test workspace")
        val customDir = KmPath("build/tmp-runtime-yaml-override-test")
        fun cleanupCustomDir() {
            if (SystemFileSystem.exists(customDir)) {
                for (child in SystemFileSystem.list(customDir)) {
                    SystemFileSystem.delete(child, mustExist = false)
                }
                SystemFileSystem.delete(customDir, mustExist = false)
            }
        }
        cleanupCustomDir()
        SystemFileSystem.createDirectories(customDir, mustCreate = true)
        try {
            for (srcFile in SystemFileSystem.list(srcRulesDir)) {
                if (!srcFile.name.endsWith(".yml")) continue
                val original = SystemFileSystem.readString(srcFile)
                val customized =
                    when (srcFile.name) {
                        "type-and-method-patterns.yml" ->
                            original.replace(
                                "replace: \"ArrayList({arg0})\"",
                                "replace: \"CustomVecPool.allocate({arg0})\"",
                            )
                        "paths-consts-and-c-abi.yml" ->
                            original
                                .replace(
                                    "- { name: TAU, f32: \"6.2831855f\", f64: \"6.283185307179586\" }",
                                    "- { name: TAU, f32: \"6.28f\", f64: \"CustomMath.TAU_CONST\" }",
                                ).replace(
                                    "- { match: \"unimplemented todo\", emptyTokens: true, replace: \"error(\\\"unsupported\\\")\" }",
                                    "- { match: \"unimplemented todo\", emptyTokens: true, replace: \"CustomPanic.todoAbort()\" }",
                                )
                        else -> original
                    }
                SystemFileSystem.writeString(KmPath("$customDir/${srcFile.name}"), customized)
            }

            val rustSnippet =
                """
                pub fn custom_rules_probe(ready: bool) -> f64 {
                    if !ready {
                        todo!();
                    }
                    let _buf: Vec<i32> = Vec::with_capacity(16);
                    std::f64::consts::TAU
                }
                """.trimIndent()

            val customRendered =
                RuntimeRuleRegistry.withRulesFromDirectory(customDir.toString()) {
                    renderSingleKotlinFile(rustSnippet, FileHeaderContext(packageName = "sample.custom")).text
                }
            assertContains(customRendered, "CustomPanic.todoAbort()")
            assertContains(customRendered, "CustomVecPool.allocate(16)")
            assertContains(customRendered, "CustomMath.TAU_CONST")

            val inputRs = KmPath("$customDir/probe.rs")
            val outputKt = KmPath("$customDir/ProbeOut.kt")
            SystemFileSystem.writeString(inputRs, rustSnippet)
            runSingleFileCommand(
                arrayOf(
                    "translate",
                    "--rules-dir",
                    customDir.toString(),
                    inputRs.toString(),
                    outputKt.toString(),
                ),
            )
            val cliKt = SystemFileSystem.readString(outputKt)
            assertContains(cliKt, "CustomPanic.todoAbort()")
            assertContains(cliKt, "CustomVecPool.allocate(16)")
            assertContains(cliKt, "CustomMath.TAU_CONST")

            val restoredDefault = renderSingleKotlinFile(rustSnippet, FileHeaderContext(packageName = "sample.restored")).text
            assertContains(restoredDefault, "error(\"unsupported\")")
            assertContains(restoredDefault, "ArrayList(16)")
            assertContains(restoredDefault, "6.283185307179586")
        } finally {
            cleanupCustomDir()
        }
    }

    @Test
    fun drivesGeneralizedMacroScalarCastCalleeAndMethodYamlRulesEndToEnd() {
        val reg = activeRules()
        assertTrue(reg.macroRulesByName.containsKey("println"))
        assertTrue(reg.preCalleeRules.isNotEmpty())
        assertTrue(reg.floatToIntUncheckedRulesByKind.containsKey(NumericKind.U32))
        assertTrue(reg.floatFromBitsSpecsByKind.containsKey(NumericKind.F32))
        assertEquals(4, reg.synForwardedWitnessRules.size)

        val headerCtx =
            FileHeaderContext(
                packageName = "sample.wave5",
                crateNamespaces = mapOf("core" to "io.github.kotlinmania.rustlang.core"),
            )
        val rustSource =
            """
            use std::sync::Arc;

            #[repr(u8)]
            pub enum TagByte {
                Zero,
                Two = 2,
            }

            pub unsafe fn exercise_wave5_yaml_rules(
                label: &str,
                flag: bool,
                val_f32: f32,
                shared: Arc<String>,
                mut slot: Option<i32>,
            ) -> u32 {
                assert!(flag, "bad {}", label);
                let _cloned = Arc::clone(&shared);
                let _owned = String::from("hello");
                let _null_p: *mut u8 = core::ptr::null_mut();
                let _parts = label.split_once(':');
                let _upper = label.to_uppercase();
                let _taken = slot.take();
                let _flag_u64 = flag as u64;
                let _punned: u32 = *(&val_f32 as *const f32 as *const u32);
                let _unchecked: u32 = val_f32.to_int_unchecked::<u32>();
                let _swapped = u32::swap_bytes(_punned);
                _swapped.count_ones()
            }
            """.trimIndent()

        val kt = renderSingleKotlinFile(rustSource, headerCtx).text
        assertContains(kt, "2u.toUByte()")
        assertContains(kt, "check(flag) { \"bad \$label\" }")
        assertContains(kt, "val cloned = shared")
        assertContains(kt, "val owned = \"hello\"")
        assertContains(kt, "MutPtr(0L)")
        assertContains(kt, "val idx = s.indexOf(':')")
        assertContains(kt, "label.uppercase()")
        assertContains(kt, "slot.also { slot = null }")
        assertContains(kt, "(if (flag) 1uL else 0uL)")
        assertContains(kt, "valF32.toRawBits().toUInt()")
        assertContains(kt, "kmFloatToU64Unchecked(valF32).toUInt()")
        assertContains(kt, "shl 24")
        assertContains(kt, "swapped.countOnes()")

        val finalEnumKt =
            renderSingleKotlinFile(
                "#[repr(u8)] pub enum TagByte { Zero, Two = 2 }",
                headerCtx.copy(finalClasses = true),
            ).text
        assertContains(finalEnumKt, "Zero(0u.toUByte())")
    }
}
