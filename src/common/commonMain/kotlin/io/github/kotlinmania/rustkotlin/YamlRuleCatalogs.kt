package io.github.kotlinmania.rustkotlin

/**
 * Strongly typed, pre-indexed rule catalogs loaded via [YamlDocumentParser] from the commented
 * `.yml` files under `src/common/commonMain/resources/io/github/kotlinmania/rustkotlin/rules/`.
 */
internal object YamlRuleCatalogs {
    private fun parseCatalog(fileName: String): YamlNode.Mapping {
        val raw =
            BundledYamlRuleSources.filesByName[fileName]
                ?: throw IllegalArgumentException("Unknown bundled YAML rule catalog: $fileName")
        return YamlDocumentParser.parse(raw, sourceName = fileName)
    }

    private fun indexIntrinsicRules(list: List<YamlNode.Mapping>): Map<String, DeclarativeIntrinsicPatternRule> {
        val out = LinkedHashMap<String, DeclarativeIntrinsicPatternRule>()
        for (node in list) {
            val rule = DeclarativeIntrinsicPatternRule.fromYaml(node)
            for (name in rule.names) out[name] = rule
        }
        return out
    }

    private fun indexMethodRulesByReceiver(
        rules: List<DeclarativeMethodPatternRule>,
        receiverGuard: String,
    ): Map<String, DeclarativeMethodPatternRule> {
        val out = LinkedHashMap<String, DeclarativeMethodPatternRule>()
        for (rule in rules) {
            if (rule.receiverGuard == receiverGuard) {
                for (method in rule.methods) out[method] = rule
            }
        }
        return out
    }

    object NamingAndComments {
        private val root: YamlNode.Mapping = parseCatalog("naming-and-comments.yml")

        val primitiveAndBuiltinTypes: Map<String, String> = root.stringMap("primitiveAndBuiltinTypes")
        val kotlinHardKeywordEscapes: Map<String, String> = root.stringMap("kotlinHardKeywordEscapes")
        val valueStubWordReplacements: Map<String, String> = root.stringMap("valueStubWordReplacements")
        val constStubWordReplacements: Map<String, String> = root.stringMap("constStubWordReplacements")
        val rawAbiFieldRenames: Map<String, String> = root.stringMap("rawAbiFieldRenames")
        val defaultTypeInitializers: Map<String, String> = root.groupedTargetMap("defaultTypeInitializers")
        val prefixDefaultTypeInitializers: List<Pair<String, String>> =
            root.stringMap("prefixDefaultTypeInitializers").entries.map { it.key to it.value }

        val verbatimFenceLangs: Set<String> = root.wordSet("verbatimFenceLangs")
        val nonCompilingRustdocFenceTags: Set<String> = root.wordSet("nonCompilingRustdocFenceTags")
        val compilerOnlyDocExampleAttrs: Set<String> = root.wordSet("compilerOnlyDocExampleAttrs")
        val rustdocRefTargetRoots: Set<String> = root.wordSet("rustdocRefTargetRoots")
        val commentForbiddenStubWords: Set<String> = root.wordSet("commentForbiddenStubWords")
        val commentCommonVocab: Map<String, String> = root.stringMap("commentCommonVocab")
        val commentCodeIdents: Map<String, String> = commentCommonVocab + root.stringMap("commentCodeOnlyIdents")
        val commentProseLifetimeWords: Map<String, String> = root.stringMap("commentProseLifetimeWords")
        val knownRustProseMacros: Set<String> = root.wordSet("knownRustProseMacros")
        val topLevelItemKeywords: Set<String> = root.wordSet("topLevelItemKeywords")
    }

    object StdlibSynAndFfi {
        val root: YamlNode.Mapping = parseCatalog("stdlib-syn-and-ffi.yml")

        val primitiveArrayTypes: Set<String> = root.wordSet("primitiveArrayTypes")
        val ignoredUseRoots: Set<String> = root.wordSet("ignoredUseRoots")
        val stdlibCrateRoots: Set<String> = root.wordSet("stdlibCrateRoots")
        val stdlibMacroOrPrimitiveModules: Set<String> = root.wordSet("stdlibMacroOrPrimitiveModules")
        val flatKotlinmaniaCrates: Set<String> = root.wordSet("flatKotlinmaniaCrates")
        val stdlibTypePathPrefixes: Set<String> = root.wordSet("stdlibTypePathPrefixes")
        val ignoredPathPrefixes: Set<String> = root.wordSet("ignoredPathPrefixes")
        val stdlibPreludeReexportModules: Set<String> = root.wordSet("stdlibPreludeReexportModules")
        val ignoredTraitBounds: Set<String> = root.wordSet("ignoredTraitBounds")
        val fmtTraitNames: Set<String> = root.wordSet("fmtTraitNames")
        val rustStdImplicitSupertraits: Set<String> = fmtTraitNames + root.wordSet("rustStdImplicitSupertraitsExtra")
        val stdlibTraitSubmodules: Set<String> = root.wordSet("stdlibTraitSubmodules")
        val stdlibExplicitSupertraits: Set<String> = rustStdImplicitSupertraits + root.wordSet("stdlibExplicitSupertraitsExtra")
        val stdlibErasedOrBuiltinImports: Set<String> = rustStdImplicitSupertraits + root.wordSet("stdlibErasedOrBuiltinImportsExtra")
        val knownSelfParameterizedTraits: Set<String> = root.wordSet("knownSelfParameterizedTraits")
        val kotlinCollectionInterfaces: Set<String> = root.wordSet("kotlinCollectionInterfaces")
        val mutatingVecMethods: Set<String> = root.wordSet("mutatingVecMethods")
        val knownOptionReturningMethods: Set<String> = root.wordSet("knownOptionReturningMethods")
        val kotlinJvmValueTypes: Set<String> = root.wordSet("kotlinJvmValueTypes")
        val kotlinStdlibShadowedImports: Set<String> = kotlinJvmValueTypes + root.wordSet("kotlinStdlibShadowedImportsExtra")
        val forwardDeserializeKeys: Set<String> = root.wordSet("forwardDeserializeKeys")
        val primitiveTypeNames: Set<String> = root.wordSet("primitiveTypeNames")
        val ignoredQuoteKeywords: Set<String> = root.wordSet("ignoredQuoteKeywordsExtra") + primitiveTypeNames
        val synKeywordTokens: Set<String> = root.wordSet("synKeywordTokens")
        val synPunctTokens: Map<String, String> = root.stringMap("synPunctTokens")

        val sizedTypePropertyNames: Set<String> = root.wordSet("sizedTypePropertyNames")
        val allocSingleDefaultGlobalTypes: Set<String> = root.wordSet("allocSingleDefaultGlobalTypes")
        val allocIntoIterModuleHints: Set<String> = root.wordSet("allocIntoIterModuleHints")
        val stdlibConstPtrFirstArgCalls: Set<String> = root.wordSet("stdlibConstPtrFirstArgCalls")
        val stdlibConstPtrTwoArgCalls: Set<String> = root.wordSet("stdlibConstPtrTwoArgCalls")
        val stdlibConstPtrFirstArgMethods: Set<String> = root.wordSet("stdlibConstPtrFirstArgMethods")
        val nonZeroByRawPrim: Map<String, String> = root.stringMap("nonZeroByRawPrim")
        val nonZeroByKtType: Map<String, String> = root.stringMap("nonZeroByKtType")
        val atomicSpecNodes: List<YamlNode.Mapping> = root.mappingList("atomicSpecs")

        val scannerScalarDescriptorNodes: List<YamlNode.Mapping> = root.mappingList("scannerScalarDescriptors")
        val scannerRustToScalarKind: Map<String, String> = root.stringMap("scannerRustToScalarKind")
        val scannerCAliasToScalarKind: Map<String, String> = root.stringMap("scannerCAliasToScalarKind")

        val windowsOnlyHeaders: Set<String> = root.wordSet("windowsOnlyHeaders")
        val portableStdHeaders: Set<String> = root.wordSet("portableStdHeaders")
        val commonPosixHeaders: Set<String> = root.wordSet("commonPosixHeaders")
        val linuxOnlySymbols: Set<String> = root.wordSet("linuxOnlySymbols")
        val bsdAppleOnlySymbols: Set<String> = root.wordSet("bsdAppleOnlySymbols")
        val posixNonWin32Symbols: Set<String> = root.wordSet("posixNonWin32Symbols")
        val directCSymbolArity: Map<String, Int> = root.intKeyedWordGroups("directCSymbolArity")
        val cSymbolHeaderRuleNodes: List<YamlNode.Mapping> = root.mappingList("cSymbolHeaderRules")
    }

    object TypeAndMethodPatterns {
        private val root: YamlNode.Mapping = parseCatalog("type-and-method-patterns.yml")

        val stdlibGenericTypeRules: List<DeclarativeTypePathPatternRule> =
            root.mappingList("stdlibGenericTypeRules").map(DeclarativeTypePathPatternRule::fromYaml)
        val builtinScalarTypes: Map<String, String> = root.stringMap("builtinScalarTypes")
        val libcSelfContainedPtrWidth64Types: Map<String, String> = root.groupedTargetMap("libcSelfContainedPtrWidth64Types")
        val libcSelfContainedPtrWidth32Types: Map<String, String> = root.groupedTargetMap("libcSelfContainedPtrWidth32Types")
        val libcSelfContainedFixedScalarTypes: Map<String, String> = root.groupedTargetMap("libcSelfContainedFixedScalarTypes")

        val primitiveLayoutsByKtType: Map<String, String> = root.stringMap("primitiveLayoutsByKtType")
        val integerBitCountMethods: Map<String, String> = root.stringMap("integerBitCountMethods")
        val integerUnaryOpMethods: Map<String, String> = root.stringMap("integerUnaryCoreMethods") + integerBitCountMethods
        val integerShiftOrPowMethods: Map<String, String> = root.stringMap("integerShiftOrPowMethods")
        val integerBinaryOpMethods: Map<String, String> = root.stringMap("integerBinaryOpMethods")
        val signedOnlyIntegerMethods: Set<String> = root.wordSet("signedOnlyIntegerMethods")
        val endian128Methods: Map<String, String> = root.stringMap("endian128Methods")
        val standaloneCheckedI32Ops: Map<String, String> = root.stringMap("standaloneCheckedI32Ops")
        val floatStdlibBoolPredicates: Map<String, String> = root.stringMap("floatStdlibBoolPredicates")
        val floatCoreBoolPredicates: Map<String, String> = root.stringMap("floatCoreBoolPredicates")
        val floatCoreSelfMethods: Map<String, String> = root.stringMap("floatCoreSelfMethods")
        val floatKmUnaryBases: Map<String, String> = root.stringMap("floatKmUnaryBases")
        val floatLibmUnaryOps: Set<String> = root.wordSet("floatLibmUnaryOps")
        val floatLibmBinaryOps: Set<String> = root.wordSet("floatLibmBinaryOps")
        val floatKmBinaryBases: Map<String, String> = root.stringMap("floatKmBinaryBases")
        val floatAdditionalSameTypeReturnMethods: Set<String> = root.wordSet("floatAdditionalSameTypeReturnMethods")

        private val allMethodRules: List<DeclarativeMethodPatternRule> =
            root.mappingList("methodCallPatternRules").map(DeclarativeMethodPatternRule::fromYaml)

        val rawPtrMethodRules: Map<String, DeclarativeMethodPatternRule> = indexMethodRulesByReceiver(allMethodRules, "rawPtr")
        val fmtOrQueueMethodRules: Map<String, DeclarativeMethodPatternRule> = indexMethodRulesByReceiver(allMethodRules, "fmtOrQueue")
        val dequeIterMethodRules: Map<String, DeclarativeMethodPatternRule> = indexMethodRulesByReceiver(allMethodRules, "dequeIter")
        val vecMethodRules: Map<String, DeclarativeMethodPatternRule> = indexMethodRulesByReceiver(allMethodRules, "vec")
    }

    object CoreIntrinsics {
        private val root: YamlNode.Mapping = parseCatalog("core-intrinsics.yml")

        val stdlibCoreFfiSymbols: Map<String, String> = root.stringMap("stdlibCoreFfiSymbols")
        val firstTypeArgLayoutIntrinsics: Set<String> = root.wordSet("firstTypeArgLayoutIntrinsics")
        val twoTypeArgLayoutIntrinsics: Set<String> = root.wordSet("twoTypeArgLayoutIntrinsics")
        val knownStdlibLayoutFunctions: Set<String> = root.wordSet("knownStdlibLayoutFunctions")
        val pointedLoadReturnIntrinsics: Set<String> = root.wordSet("pointedLoadReturnIntrinsics")
        val firstArgReturnIntrinsics: Set<String> = root.wordSet("firstArgReturnIntrinsics")
        val usizeReturnIntrinsics: Set<String> = root.wordSet("usizeReturnIntrinsics")
        val trivialDropScalars: Set<String> = root.wordSet("trivialDropScalars")

        val controlAndHintRulesByName: Map<String, DeclarativeIntrinsicPatternRule> =
            indexIntrinsicRules(root.mappingList("controlAndHintIntrinsicRules"))
        val layoutDeclRulesByName: Map<String, DeclarativeIntrinsicPatternRule> =
            indexIntrinsicRules(root.mappingList("layoutSynthesizedDeclRules"))
        val pointerDeclRulesByName: Map<String, DeclarativeIntrinsicPatternRule> =
            indexIntrinsicRules(root.mappingList("pointerSynthesizedDeclRules"))
        val legacyOrderingCodes: Map<String, String> = root.stringMap("legacyOrderingCodes")
        val atomicRmwOpCodes: Map<String, Int> =
            root.stringMap("atomicRmwOpCodes").mapValues { (k, v) ->
                v.toIntOrNull() ?: throw IllegalArgumentException("Invalid atomicRmwOpCodes[$k]: '$v'")
            }
        val atomicNonRmwNames: Set<String> = root.wordSet("atomicNonRmwNames")
        val atomicRulesByName: Map<String, DeclarativeIntrinsicPatternRule> =
            indexIntrinsicRules(root.mappingList("atomicSynthesizedDeclRules"))

        val fastScalarTransmuteTemplates: Map<String, YamlTemplate> =
            root.stringMap("fastScalarTransmuteTemplates").mapValues { (_, pattern) -> YamlTemplate.compile(pattern) }

        val intIntrinsicRuleNodes: List<YamlNode.Mapping> = root.mappingList("intIntrinsicRules")

        val genericFloatUnaryKm: Map<String, Pair<String, String>> =
            root.stringMap("genericFloatUnaryKmStems").mapValues { (_, stem) -> "${stem}f32" to "${stem}f64" }

        val suffixedFloatUnaryKm: Map<String, String> =
            buildMap {
                for ((op, fns) in genericFloatUnaryKm) {
                    if (op != "round_ties_even") {
                        put("${op}f32", fns.first)
                        put("${op}f64", fns.second)
                    }
                }
                putAll(root.stringMap("suffixedFloatUnaryExtras"))
            }

        val suffixedFloatBinaryKm: Map<String, String> = root.stringMap("suffixedFloatBinaryKm")
        val suffixedFloatTernaryKm: Map<String, String> = root.stringMap("suffixedFloatTernaryKm")
        val genericFloatBinaryOpNames: Map<String, String> = root.stringMap("genericFloatBinaryOpNames")
    }
}
