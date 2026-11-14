package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.procmacro2.Delimiter
import io.github.kotlinmania.procmacro2.TokenStream
import io.github.kotlinmania.procmacro2.TokenTree
import io.github.kotlinmania.syn.Attribute
import io.github.kotlinmania.syn.Expr
import io.github.kotlinmania.syn.Lit
import io.github.kotlinmania.syn.Macro
import io.github.kotlinmania.syn.Meta
import io.github.kotlinmania.syn.PathArguments
import io.github.kotlinmania.syn.PointerMutability
import io.github.kotlinmania.syn.SynType

public sealed class CompanionInteropKind {
    public data object CInteropDef : CompanionInteropKind()

    public data object CHeader : CompanionInteropKind()

    public data object CSource : CompanionInteropKind()

    public data object CppSource : CompanionInteropKind()

    public data object AsmSource : CompanionInteropKind()

    public data object CommonKotlinSupport : CompanionInteropKind()

    public data object NativeKotlinActuals : CompanionInteropKind()

    public data object JvmKotlinActuals : CompanionInteropKind()

    public data object AndroidKotlinActuals : CompanionInteropKind()

    public data object JniCSource : CompanionInteropKind()

    public data object JsKotlinActuals : CompanionInteropKind()

    public data object NapiCppSource : CompanionInteropKind()

    public data object NapiDistFile : CompanionInteropKind()

    public data object WasmJsKotlinActuals : CompanionInteropKind()

    public data object WasmWasiKotlinActuals : CompanionInteropKind()
}

public data class CompanionInteropFile(
    public val relativePath: String,
    public val kind: CompanionInteropKind,
    public val content: String,
    public val ffiModuleName: String = "ffi",
)

internal enum class CAbiBridgeKind {
    VOID,
    NEVER,
    BOOL,
    I8,
    U8,
    I16,
    U16,
    I32,
    U32,
    I64,
    U64,
    F32,
    F64,
    CONST_CSTR,
    MUT_CSTR,
    OPAQUE_PTR,
    FILE_PTR,
    DIR_PTR,
    CALLBACK_PTR,
    BY_VALUE_OBJECT,
}

internal data class InteropParamSpec(
    val kotlinName: String,
    val cName: String,
    val kotlinType: String,
    val cType: String = "",
    val bridgeKind: CAbiBridgeKind,
)

internal data class ForeignFnInteropSpec(
    val visibilityPrefix: String,
    val kotlinName: String,
    val cSymbolName: String,
    val wrapperSymbolName: String,
    val params: List<InteropParamSpec>,
    val kotlinReturnType: String,
    val cReturnType: String,
    val returnBridgeKind: CAbiBridgeKind,
    val isVariadic: Boolean,
    val returnDefaultExpr: String = "0",
    val dllName: String? = null,
    val linkLibrary: String? = null,
)

internal data class ForeignStaticInteropSpec(
    val visibilityPrefix: String,
    val kotlinName: String,
    val cSymbolName: String,
    val getterSymbolName: String,
    val setterSymbolName: String,
    val kotlinType: String,
    val bridgeKind: CAbiBridgeKind,
    val isMutable: Boolean,
    val defaultExpr: String,
) {
    val getterWrapperSymbolName: String get() = getterSymbolName
    val setterWrapperSymbolName: String get() = setterSymbolName
}

internal data class InlineCFunctionSpec(
    val visibilityPrefix: String,
    val kotlinName: String,
    val wrapperSymbolName: String,
    val params: List<InteropParamSpec>,
    val kotlinReturnType: String,
    val cReturnType: String,
    val returnBridgeKind: CAbiBridgeKind,
    val rawBody: String,
    val isCpp: Boolean,
    val returnDefaultExpr: String = "0",
    val isVariadic: Boolean = false,
)

internal data class ParsedInlineCMacro(
    val captureArgs: List<InlineCaptureArg>,
    val returnRustType: SynType?,
    val explicitCReturnType: String?,
    val rawBody: String,
    val isCpp: Boolean,
)

internal data class InlineCaptureArg(
    val rawRustIdent: String,
    val isMut: Boolean,
    val rustType: SynType?,
    val explicitCType: String?,
)

private data class PlatformHeaderSpec(
    val header: String,
    val guardCondition: String? = null,
    val windowsFallbackHeaders: List<String> = emptyList(),
)

internal class FfiInteropCollector(
    private val source: String,
    private val headerContext: FileHeaderContext,
    private val resolveNumericKind: (String) -> NumericKind? = { null },
    private val resolveTypeAliasTarget: (String) -> SynType? = { null },
) {
    private val preambles = mutableListOf<String>()
    private val foreignFunctions = mutableListOf<ForeignFnInteropSpec>()
    private val foreignStatics = mutableListOf<ForeignStaticInteropSpec>()
    private val inlineFunctions = mutableListOf<InlineCFunctionSpec>()
    private val linkLibraries = linkedSetOf<String>()
    private val explicitHeaders = linkedSetOf<String>()
    private val recordedImportLines = linkedSetOf<String>()
    private val localDeclaredTypeNames = linkedSetOf<String>()
    private val usedWrapperSymbolNames = linkedSetOf<String>()
    private var usesCppLanguage = false
    private var inlineCounter = 0
    private var renderedInlineExpectCount = 0

    init {
        extractHeaderCommentsFromSource(source, explicitHeaders)
    }

    val cSymbolPrefix: String = computeCSymbolPrefix(headerContext)
    val cSymbolPrefixCamel: String = toCamelCaseIdentifierPart(cSymbolPrefix)

    private val activeCfgProfile: CfgTargetProfile
        get() = headerContext.cfgTargetProfile ?: TranslationSession.cfgTargetProfile

    private fun decodePointerAliasSpec(encoded: String?): Pair<Boolean, String>? {
        if (encoded == null) return null
        val sep = encoded.indexOf(':')
        if (sep <= 0) return null
        val isMut = encoded.substring(0, sep) == "mut"
        val pointee = encoded.substring(sep + 1)
        return isMut to pointee
    }

    private fun currentSubpackageKey(): String? =
        headerContext.currentRelativeSourceDir
            ?.split('/')
            ?.filter { it.isNotEmpty() }
            ?.joinToString(".") { rustModuleIdentToKotlin(it) }
            ?.takeIf { it.isNotEmpty() }

    private fun shadowsKotlinString(pkgName: String?): Boolean {
        if ("String" in localDeclaredTypeNames) return true
        if (recordedImportLines.any { it.endsWith(".String") && !it.startsWith("kotlin.") }) return true
        val projNs = headerContext.projectNamespace?.trim()?.removeSuffix(".")
        val subpkg =
            when {
                pkgName == null || projNs.isNullOrEmpty() -> currentSubpackageKey()
                pkgName == projNs -> ""
                pkgName.startsWith("$projNs.") -> pkgName.removePrefix("$projNs.")
                else -> currentSubpackageKey()
            }
        return subpkg != null && headerContext.crateSymbolSubpackages["String"]?.contains(subpkg) == true
    }

    private fun lookupCrossFilePointerAlias(ident: String): Pair<Boolean, String>? {
        val simple = ident.substringAfterLast('.').removeSuffix("?").removePrefix("r#")
        decodePointerAliasSpec(headerContext.cratePointerTypeAliases[ident])?.let { return it }
        val mappedKt = KotlinNamingPolicy.Default.toTypeIdentifier(simple, allowRawAbi = headerContext.rawAbiMode)
        val curRel = headerContext.currentRelativeSourceDir?.replace('/', '.')?.takeIf { it.isNotEmpty() }
        val curSub = currentSubpackageKey()
        for (scopePrefix in listOfNotNull(curSub, curRel).distinct()) {
            decodePointerAliasSpec(headerContext.cratePointerTypeAliases["$scopePrefix.$simple"])?.let { return it }
            decodePointerAliasSpec(headerContext.cratePointerTypeAliases["$scopePrefix.$mappedKt"])?.let { return it }
            if ("$scopePrefix.$simple" in headerContext.crateNumericTypeKinds ||
                "$scopePrefix.$mappedKt" in headerContext.crateNumericTypeKinds
            ) {
                return null
            }
        }
        decodePointerAliasSpec(headerContext.cratePointerTypeAliases[simple])?.let { return it }
        decodePointerAliasSpec(headerContext.cratePointerTypeAliases[mappedKt])?.let { return it }
        return null
    }

    private fun isScopedStructType(renderedKotlinType: String): Boolean {
        val clean = renderedKotlinType.removeSuffix("?").trim()
        val simple = clean.substringAfterLast('.')
        val projNs = headerContext.projectNamespace?.trim()?.removeSuffix(".")
        if ('.' in clean && !clean.startsWith("kotlin.")) {
            val relKey = if (!projNs.isNullOrEmpty() && clean.startsWith("$projNs.")) clean.removePrefix("$projNs.") else clean
            if (headerContext.crateTypeAliasDefaults[relKey] == "$simple()") {
                return true
            }
        }
        val curRel = headerContext.currentRelativeSourceDir?.replace('/', '.')?.takeIf { it.isNotEmpty() }
        val curSub = currentSubpackageKey()
        for (scopePrefix in listOfNotNull(curSub, curRel).distinct()) {
            if (headerContext.crateTypeAliasDefaults["$scopePrefix.$simple"] == "$simple()") {
                return true
            }
        }
        return false
    }

    fun classifyParamAbiBridge(synType: SynType?, renderedKotlinType: String): CAbiBridgeKind {
        if (!renderedKotlinType.endsWith("?") && isScopedStructType(renderedKotlinType)) {
            return CAbiBridgeKind.BY_VALUE_OBJECT
        }
        return classifyAbiBridge(
            synType = synType,
            renderedKotlinType = renderedKotlinType,
            cfgProfile = activeCfgProfile,
            resolveNumericKind = resolveNumericKind,
            resolveTypeAliasTarget = resolveTypeAliasTarget,
            resolvePointerAlias = ::lookupCrossFilePointerAlias,
        )
    }

    fun classifyReturnAbiBridge(synType: SynType?, renderedKotlinType: String): CAbiBridgeKind {
        if (!renderedKotlinType.endsWith("?") && isScopedStructType(renderedKotlinType)) {
            return CAbiBridgeKind.BY_VALUE_OBJECT
        }
        return classifyAbiBridge(
            synType = synType,
            renderedKotlinType = renderedKotlinType,
            cfgProfile = activeCfgProfile,
            resolveNumericKind = resolveNumericKind,
            resolveTypeAliasTarget = resolveTypeAliasTarget,
            resolvePointerAlias = ::lookupCrossFilePointerAlias,
        )
    }

    fun hasInteropContent(): Boolean =
        preambles.isNotEmpty() || foreignFunctions.isNotEmpty() || foreignStatics.isNotEmpty() || inlineFunctions.isNotEmpty()

    fun recordImportLines(lines: List<String>) {
        for (line in lines) {
            val trimmed = line.trim().removePrefix("import ").trim()
            if (trimmed.isNotEmpty()) {
                recordedImportLines += trimmed
            }
        }
    }

    fun recordLocalTypeNames(names: Collection<String>) {
        localDeclaredTypeNames.addAll(names)
    }

    fun recordForeignModAttributes(attrs: List<Attribute>): String? {
        val modLibs = linkedSetOf<String>()
        for (attr in attrs) {
            val meta = attr.meta
            if (meta is Meta.List && meta.path.isIdent("link")) {
                extractLinkLibraryNamesFromLinkArgs(meta.tokens, modLibs)
            } else if (meta is Meta.List && meta.path.isIdent("cfg_attr")) {
                val cfgParts = MacroRulesFileExpander.splitMacroArguments(meta.tokens.clone())
                for (part in cfgParts.drop(1)) {
                    if (part.size == 2 &&
                        (part[0] as? TokenTree.Ident)?.value?.toString() == "link" &&
                        part[1] is TokenTree.Group &&
                        (part[1] as TokenTree.Group).value.delimiter() == Delimiter.Parenthesis
                    ) {
                        extractLinkLibraryNamesFromLinkArgs((part[1] as TokenTree.Group).value.stream(), modLibs)
                    }
                }
            }
        }
        for (libName in modLibs) {
            if (libName != "c") {
                linkLibraries += libName
            }
        }
        return modLibs.firstOrNull { it !in UMBRELLA_LINK_LIBRARIES }
    }

    fun recordTopLevelInlineMacro(mac: Macro): Boolean {
        val lastSeg = mac.path.segments.last() ?: return false
        val macroName = lastSeg.ident.toString()
        if (macroName != "cpp" && macroName != "c" && macroName != "inline_c") return false
        if (macroName == "cpp") {
            usesCppLanguage = true
        }
        val trees = mac.tokens.clone().toList()
        val rawChunk =
            if (trees.size == 1 && trees[0] is TokenTree.Group && (trees[0] as TokenTree.Group).value.delimiter() == Delimiter.Brace) {
                extractGroupInnerSource(source, trees[0] as TokenTree.Group)
            } else {
                extractStreamSource(source, trees)
            }.trim()
        if (rawChunk.isNotEmpty()) {
            preambles += rawChunk
            if (looksLikeCppCode(rawChunk)) {
                usesCppLanguage = true
            }
        }
        return true
    }

    fun recordForeignFunction(
        visibilityPrefix: String,
        kotlinName: String,
        rawRustName: String,
        attrs: List<Attribute>,
        params: List<InteropParamSpec>,
        kotlinReturnType: String,
        returnSynType: SynType?,
        isVariadic: Boolean,
        returnDefaultExpr: String = defaultKotlinFallbackForBridge(classifyReturnAbiBridge(returnSynType, kotlinReturnType), kotlinReturnType),
        dllName: String? = null,
        linkLibrary: String? = null,
    ) {
        val linkName = extractLinkNameAttribute(attrs) ?: rawRustName.removePrefix("r#")
        val retBridge = classifyReturnAbiBridge(returnSynType, kotlinReturnType)
        val cRet = cTypeForBridge(retBridge)
        val sanitizedParams =
            params.mapIndexed { idx, p ->
                p.copy(cName = sanitizeCParamIdentifier(p.cName, idx))
            }
        val baseWrapperName = "${cSymbolPrefixCamel}${toPascalCaseIdentifierPart(kotlinName)}"
        var wrapperName = baseWrapperName
        var dupIdx = 2
        while (!usedWrapperSymbolNames.add(wrapperName)) {
            wrapperName = "${baseWrapperName}$dupIdx"
            dupIdx++
        }
        foreignFunctions +=
            ForeignFnInteropSpec(
                visibilityPrefix = visibilityPrefix,
                kotlinName = kotlinName,
                cSymbolName = linkName,
                wrapperSymbolName = wrapperName,
                params = sanitizedParams,
                kotlinReturnType = kotlinReturnType,
                cReturnType = cRet,
                returnBridgeKind = retBridge,
                isVariadic = isVariadic,
                returnDefaultExpr = returnDefaultExpr,
                dllName = dllName,
                linkLibrary = linkLibrary,
            )
    }

    private fun allocateUniqueWrapperSymbol(suffix: String): String {
        val baseWrapperName = "${cSymbolPrefixCamel}${toPascalCaseIdentifierPart(suffix)}"
        var wrapperName = baseWrapperName
        var dupIdx = 2
        while (!usedWrapperSymbolNames.add(wrapperName)) {
            wrapperName = "${baseWrapperName}$dupIdx"
            dupIdx++
        }
        return wrapperName
    }

    fun recordForeignStatic(
        visibilityPrefix: String,
        kotlinName: String,
        rawRustName: String,
        attrs: List<Attribute>,
        synType: SynType? = null,
        kotlinType: String,
        isMutable: Boolean,
        defaultExpr: String,
    ) {
        val linkName = extractLinkNameAttribute(attrs) ?: rawRustName.removePrefix("r#")
        val rawBridge = classifyReturnAbiBridge(synType, kotlinType)
        val bridgeKind =
            if ((rawBridge == CAbiBridgeKind.CONST_CSTR || rawBridge == CAbiBridgeKind.MUT_CSTR) &&
                kotlinType != "String?" && kotlinType != "String"
            ) {
                CAbiBridgeKind.OPAQUE_PTR
            } else {
                rawBridge
            }
        val getterName = allocateUniqueWrapperSymbol("get_$kotlinName")
        val setterName = allocateUniqueWrapperSymbol("set_$kotlinName")
        foreignStatics +=
            ForeignStaticInteropSpec(
                visibilityPrefix = visibilityPrefix,
                kotlinName = kotlinName,
                cSymbolName = linkName,
                getterSymbolName = getterName,
                setterSymbolName = setterName,
                kotlinType = kotlinType,
                bridgeKind = bridgeKind,
                isMutable = isMutable,
                defaultExpr = defaultExpr,
            )
    }

    fun recordInlineExpressionMacro(
        mac: Macro,
        enclosingFnKotlinName: String?,
        renderRustType: (SynType, Boolean) -> String,
        resolveLocalExprName: (String) -> String,
    ): String? {
        val lastSeg = mac.path.segments.last() ?: return null
        val macroName = lastSeg.ident.toString()
        if (macroName != "cpp" && macroName != "c") return null
        val parsed = parseInlineCMacro(source, mac.tokens.clone(), isCpp = macroName == "cpp") ?: return null
        if (parsed.isCpp || looksLikeCppCode(parsed.rawBody)) {
            usesCppLanguage = true
        }
        val idx = inlineCounter++
        val basePrefix = enclosingFnKotlinName?.takeIf { it.isNotEmpty() } ?: "inlineBlock"
        val suffixTag = if (parsed.isCpp) "Cpp" else "C"
        val helperKtName = if (idx == 0) "${basePrefix}$suffixTag" else "${basePrefix}$suffixTag$idx"
        val wrapperSymbol = "${cSymbolPrefixCamel}Inline${idx}${toPascalCaseIdentifierPart(basePrefix)}"
        usedWrapperSymbolNames += wrapperSymbol

        val paramSpecs =
            parsed.captureArgs.mapIndexed { pIdx, cap ->
                val ktParamName = rustValueIdentToKotlin(cap.rawRustIdent).ifEmpty { "arg$pIdx" }
                val cParamName = sanitizeCParamIdentifier(cap.rawRustIdent.removePrefix("r#").ifEmpty { "arg$pIdx" }, pIdx)
                val explicitC = cap.explicitCType?.trim()
                val ktType =
                    when {
                        cap.rustType != null -> renderRustType(cap.rustType, true)
                        explicitC != null ->
                            inferKotlinTypeFromCType(
                                cType = explicitC,
                                rawAbiMode = headerContext.rawAbiMode,
                                cfgProfile = activeCfgProfile,
                            )
                        else -> "Long"
                    }
                val bridge =
                    when {
                        cap.rustType != null -> classifyParamAbiBridge(cap.rustType, ktType)
                        explicitC != null -> classifyExplicitCTypeBridge(explicitC, activeCfgProfile)
                        else -> CAbiBridgeKind.I64
                    }
                val finalCType = explicitC?.takeIf { it.isNotEmpty() } ?: cTypeForBridge(bridge)
                InteropParamSpec(
                    kotlinName = ktParamName,
                    cName = cParamName,
                    kotlinType = ktType,
                    cType = finalCType,
                    bridgeKind = bridge,
                )
            }

        val ktReturnType =
            when {
                parsed.returnRustType != null -> renderRustType(parsed.returnRustType, false)
                parsed.explicitCReturnType != null ->
                    inferKotlinTypeFromCType(
                        cType = parsed.explicitCReturnType,
                        rawAbiMode = headerContext.rawAbiMode,
                        cfgProfile = activeCfgProfile,
                    )
                else -> "Unit"
            }
        val retBridge =
            when {
                parsed.returnRustType != null -> classifyReturnAbiBridge(parsed.returnRustType, ktReturnType)
                parsed.explicitCReturnType != null -> classifyExplicitCTypeBridge(parsed.explicitCReturnType, activeCfgProfile)
                else -> CAbiBridgeKind.VOID
            }
        val finalCRetType = parsed.explicitCReturnType?.takeIf { it.isNotEmpty() } ?: cTypeForBridge(retBridge)

        inlineFunctions +=
            InlineCFunctionSpec(
                visibilityPrefix = "internal ",
                kotlinName = helperKtName,
                wrapperSymbolName = wrapperSymbol,
                params = paramSpecs,
                kotlinReturnType = ktReturnType,
                cReturnType = finalCRetType,
                returnBridgeKind = retBridge,
                rawBody = parsed.rawBody,
                isCpp = parsed.isCpp,
                returnDefaultExpr = defaultKotlinFallbackForBridge(retBridge, ktReturnType),
            )

        val callArgs = parsed.captureArgs.map { cap -> resolveLocalExprName(cap.rawRustIdent.removePrefix("r#")) }
        return "$helperKtName(${callArgs.joinToString(", ")})"
    }

    fun renderInlineExpectDeclarations(): List<String> {
        val pending = inlineFunctions.subList(renderedInlineExpectCount, inlineFunctions.size)
        renderedInlineExpectCount = inlineFunctions.size
        return pending.map { fn ->
            val paramsStr = fn.params.joinToString(", ") { "${it.kotlinName}: ${it.kotlinType}" }
            val retSuffix = if (fn.returnBridgeKind == CAbiBridgeKind.VOID) "" else ": ${fn.kotlinReturnType}"
            "${fn.visibilityPrefix}expect fun ${fn.kotlinName}($paramsStr)$retSuffix"
        }
    }

    fun emitCompanionFiles(): List<CompanionInteropFile> {
        if (!hasInteropContent()) return emptyList()

        val localStemSnake = computeStemSnake(headerContext)
        val localStemPascal = KotlinNamingPolicy.Default.toTypeIdentifier(localStemSnake, allowRawAbi = false)
        val cinteropStemSnake = computeCInteropStemSnake(headerContext)
        val cinteropStemPascal = KotlinNamingPolicy.Default.toTypeIdentifier(cinteropStemSnake, allowRawAbi = false)
        val ffiModName = computeFfiModuleNameLower(headerContext)

        val headerFileName = "${cinteropStemSnake}_wrapper.h"
        val sourceExt = if (usesCppLanguage) "cpp" else "c"
        val sourceFileName = "${cinteropStemSnake}_wrapper.$sourceExt"
        val defFileName = "$cinteropStemPascal.def"
        val nativeActualsFileName = "${localStemPascal}NativeActuals.kt"
        val jvmActualsFileName = "${localStemPascal}JvmActuals.kt"
        val androidActualsFileName = "${localStemPascal}AndroidActuals.kt"
        val jniFileName = "${cSymbolPrefix}_jni.c"
        val jsActualsFileName = "${localStemPascal}JsActuals.kt"
        val napiFileName = "${cSymbolPrefix}_bindings.cpp"
        val wasmJsActualsFileName = "${localStemPascal}WasmJsActuals.kt"
        val wasmWasiActualsFileName = "${localStemPascal}WasmWasiActuals.kt"

        val pkgName = headerContext.packageName
        val cinteropPkg = if (!pkgName.isNullOrBlank()) "$pkgName.cinterop" else "ffi.cinterop"
        val jniObjectName = "${cinteropStemPascal}JniBridge"
        val jniLibName = "${ffiModName}_jni"

        val defContent = buildDefFileContent(cinteropPkg, headerFileName, cinteropStemSnake)
        val headerContent = buildCHeaderContent(cinteropStemSnake)
        val cSourceContent = buildCOrCppSourceContent(headerFileName)
        val nativeActualsContent = buildNativeActualsKotlinContent(pkgName, cinteropPkg)
        val jvmActualsContent = buildJvmOrAndroidActualsKotlinContent(pkgName, jniObjectName, jniLibName, includeJarResourceLoader = true)
        val androidActualsContent = buildJvmOrAndroidActualsKotlinContent(pkgName, jniObjectName, jniLibName, includeJarResourceLoader = false)
        val jniCContent = buildJniCSourceContent(pkgName, jniObjectName)
        val jsActualsContent = buildJsActualsKotlinContent(pkgName, cinteropStemPascal, ffiModName)
        val napiCppContent = buildNapiCppSourceContent(cinteropStemSnake)
        val wasmJsActualsContent = buildWasmJsActualsKotlinContent(pkgName, cinteropStemPascal, ffiModName)
        val wasmWasiActualsContent = buildWasmWasiActualsKotlinContent(pkgName)

        return listOf(
            CompanionInteropFile(
                relativePath = defFileName,
                kind = CompanionInteropKind.CInteropDef,
                content = defContent,
                ffiModuleName = ffiModName,
            ),
            CompanionInteropFile(
                relativePath = headerFileName,
                kind = CompanionInteropKind.CHeader,
                content = headerContent,
                ffiModuleName = ffiModName,
            ),
            CompanionInteropFile(
                relativePath = sourceFileName,
                kind = if (usesCppLanguage) CompanionInteropKind.CppSource else CompanionInteropKind.CSource,
                content = cSourceContent,
                ffiModuleName = ffiModName,
            ),
            CompanionInteropFile(
                relativePath = nativeActualsFileName,
                kind = CompanionInteropKind.NativeKotlinActuals,
                content = nativeActualsContent,
                ffiModuleName = ffiModName,
            ),
            CompanionInteropFile(
                relativePath = jvmActualsFileName,
                kind = CompanionInteropKind.JvmKotlinActuals,
                content = jvmActualsContent,
                ffiModuleName = ffiModName,
            ),
            CompanionInteropFile(
                relativePath = androidActualsFileName,
                kind = CompanionInteropKind.AndroidKotlinActuals,
                content = androidActualsContent,
                ffiModuleName = ffiModName,
            ),
            CompanionInteropFile(
                relativePath = jniFileName,
                kind = CompanionInteropKind.JniCSource,
                content = jniCContent,
                ffiModuleName = ffiModName,
            ),
            CompanionInteropFile(
                relativePath = jsActualsFileName,
                kind = CompanionInteropKind.JsKotlinActuals,
                content = jsActualsContent,
                ffiModuleName = ffiModName,
            ),
            CompanionInteropFile(
                relativePath = napiFileName,
                kind = CompanionInteropKind.NapiCppSource,
                content = napiCppContent,
                ffiModuleName = ffiModName,
            ),
            CompanionInteropFile(
                relativePath = wasmJsActualsFileName,
                kind = CompanionInteropKind.WasmJsKotlinActuals,
                content = wasmJsActualsContent,
                ffiModuleName = ffiModName,
            ),
            CompanionInteropFile(
                relativePath = wasmWasiActualsFileName,
                kind = CompanionInteropKind.WasmWasiKotlinActuals,
                content = wasmWasiActualsContent,
                ffiModuleName = ffiModName,
            ),
        )
    }

    private fun isBridgeableInCWrapper(fn: ForeignFnInteropSpec): Boolean =
        (!fn.isVariadic || fn.params.isNotEmpty()) &&
            fn.returnBridgeKind != CAbiBridgeKind.BY_VALUE_OBJECT &&
            fn.params.none { it.bridgeKind == CAbiBridgeKind.BY_VALUE_OBJECT }

    private fun isBridgeableStaticInCWrapper(st: ForeignStaticInteropSpec): Boolean =
        st.bridgeKind != CAbiBridgeKind.BY_VALUE_OBJECT &&
            st.bridgeKind != CAbiBridgeKind.VOID &&
            st.bridgeKind != CAbiBridgeKind.NEVER

    private fun isBridgeableInlineInCWrapper(fn: InlineCFunctionSpec): Boolean =
        fn.returnBridgeKind != CAbiBridgeKind.BY_VALUE_OBJECT &&
            fn.params.none { it.bridgeKind == CAbiBridgeKind.BY_VALUE_OBJECT }

    private fun canCallDirectlyInCWrapper(fn: ForeignFnInteropSpec): Boolean {
        if (!isBridgeableInCWrapper(fn) || fn.dllName != null || fn.isVariadic) return false
        if (isAtexitForeignFn(fn)) return false
        if (fn.returnBridgeKind == CAbiBridgeKind.CALLBACK_PTR || fn.params.any { it.bridgeKind == CAbiBridgeKind.CALLBACK_PTR }) {
            return false
        }
        if (preambles.any { containsCIdentifier(it, fn.cSymbolName) }) return true
        return isDirectCallSafeSymbol(fn)
    }

    private fun buildDefFileContent(
        cinteropPkg: String,
        headerFileName: String,
        stemSnake: String,
    ): String {
        val sb = StringBuilder()
        sb.append("package = ").append(cinteropPkg).append('\n')
        sb.append("headers = ").append(headerFileName).append('\n')
        sb.append("headerFilter = ").append(headerFileName).append('\n')
        sb.append("compilerOpts = -Isrc/native/interop/cinterop\n")
        sb.append("staticLibraries = ").append(stemSnake).append("_wrapper.a\n")
        val directForeignFunctions = foreignFunctions.filter(::canCallDirectlyInCWrapper)
        val preambleIncludes = extractIncludedHeadersFromPreambles(preambles)
        val inferredSpecs = inferRequiredHeaders(directForeignFunctions, inlineFunctions, explicitHeaders, preambleIncludes)
        if (inferredSpecs.any { it.windowsFallbackHeaders.isNotEmpty() }) {
            sb.append("linkerOpts.mingw = -lws2_32\n")
        }
        val foreignPtrFns =
            foreignFunctions
                .filter { isBridgeableInCWrapper(it) && requiresNoStringConversion(it.params) }
                .map { it.wrapperSymbolName }
        val inlinePtrFns =
            inlineFunctions
                .filter { fn ->
                    isBridgeableInlineInCWrapper(fn) &&
                        (requiresNoStringConversion(fn.params) || fn.params.any { it.bridgeKind == CAbiBridgeKind.MUT_CSTR })
                }.map { it.wrapperSymbolName }
        val pointerStringFns = (foreignPtrFns + inlinePtrFns).distinct()
        if (pointerStringFns.isNotEmpty()) {
            sb.append("noStringConversion = ").append(pointerStringFns.joinToString(" ")).append('\n')
        }
        return sb.toString()
    }

    private fun buildCHeaderContent(stemSnake: String): String {
        val guard = "${cSymbolPrefix.uppercase()}_${stemSnake.uppercase()}_WRAPPER_H"
        val sb = StringBuilder()
        sb.append("#ifndef ").append(guard).append('\n')
        sb.append("#define ").append(guard).append("\n\n")
        sb.append("#include <stdbool.h>\n")
        sb.append("#include <stddef.h>\n")
        sb.append("#include <stdint.h>\n\n")
        sb.append("#ifdef __cplusplus\n")
        sb.append("extern \"C\" {\n")
        sb.append("#endif\n\n")

        val emittedPrototypes = linkedSetOf<String>()
        for (st in foreignStatics) {
            if (!isBridgeableStaticInCWrapper(st)) continue
            val stCType = cHeaderTypeForBridge(st.bridgeKind)
            if (emittedPrototypes.add(st.getterSymbolName)) {
                sb.append("$stCType ${st.getterSymbolName}(void);\n")
            }
            if (st.isMutable && emittedPrototypes.add(st.setterSymbolName)) {
                sb.append("void ${st.setterSymbolName}($stCType km_val);\n")
            }
        }
        for (fn in foreignFunctions) {
            if (!isBridgeableInCWrapper(fn)) continue
            if (!emittedPrototypes.add(fn.wrapperSymbolName)) continue
            val fixedParams = fn.params.map { "${cHeaderTypeForParam(it)} ${it.cName}" }
            val allParams =
                if (fn.isVariadic) {
                    fixedParams + listOf("int32_t km_vac", "int64_t km_va0", "int64_t km_va1", "int64_t km_va2", "int64_t km_va3")
                } else {
                    fixedParams
                }
            val paramsText = if (allParams.isEmpty()) "void" else allParams.joinToString(", ")
            sb.append("${cHeaderTypeForReturn(fn)} ${fn.wrapperSymbolName}($paramsText);\n")
        }
        for (fn in inlineFunctions) {
            if (!isBridgeableInlineInCWrapper(fn)) continue
            if (!emittedPrototypes.add(fn.wrapperSymbolName)) continue
            val paramsText =
                if (fn.params.isEmpty()) {
                    "void"
                } else {
                    fn.params.joinToString(", ") { "${cHeaderTypeForParam(it)} ${it.cName}" }
                }
            sb.append("${cHeaderTypeForInlineReturn(fn)} ${fn.wrapperSymbolName}($paramsText);\n")
        }

        sb.append("\n#ifdef __cplusplus\n")
        sb.append("}\n")
        sb.append("#endif\n\n")
        sb.append("#endif /* ").append(guard).append(" */\n")
        return sb.toString()
    }

    private fun buildCOrCppSourceContent(headerFileName: String): String {
        val sb = StringBuilder()
        val needsDynamicLookup =
            foreignFunctions.any { isBridgeableInCWrapper(it) && !canCallDirectlyInCWrapper(it) } ||
                foreignStatics.any(::isBridgeableStaticInCWrapper)
        if (needsDynamicLookup) {
            sb.append("#ifndef _GNU_SOURCE\n#define _GNU_SOURCE\n#endif\n")
        }
        sb.append("#include \"").append(headerFileName).append("\"\n")
        sb.append("#include <errno.h>\n")

        val directForeignFunctions = foreignFunctions.filter(::canCallDirectlyInCWrapper)
        val preambleIncludes = extractIncludedHeadersFromPreambles(preambles)
        val inferredSpecs = inferRequiredHeaders(directForeignFunctions, inlineFunctions, explicitHeaders, preambleIncludes)

        val unguardedHeaders = linkedSetOf<String>()
        val guardedGroups = linkedMapOf<String, MutableSet<String>>()
        val winFallbackGroups = linkedSetOf<String>()

        if (foreignFunctions.any { isBridgeableInCWrapper(it) && isAtexitForeignFn(it) }) {
            unguardedHeaders += "stdlib.h"
        }

        for (spec in inferredSpecs) {
            if (spec.guardCondition == null) {
                unguardedHeaders += spec.header
            } else {
                guardedGroups.getOrPut(spec.guardCondition) { linkedSetOf() } += spec.header
                winFallbackGroups.addAll(spec.windowsFallbackHeaders)
            }
        }

        for (hdr in unguardedHeaders) {
            if (hdr !in preambleIncludes) {
                sb.append("#include ").append(formatIncludeTarget(hdr)).append('\n')
            }
        }
        if (winFallbackGroups.isNotEmpty()) {
            sb.append("#ifdef _WIN32\n")
            for (hdr in winFallbackGroups) {
                if (hdr !in preambleIncludes) {
                    sb.append("#include ").append(formatIncludeTarget(hdr)).append('\n')
                }
            }
            sb.append("#endif\n")
        }
        for ((cond, hdrs) in guardedGroups) {
            val filtered = hdrs.filter { it !in preambleIncludes }
            if (filtered.isEmpty()) continue
            sb.append("#if ").append(cond).append('\n')
            for (hdr in filtered) {
                sb.append("#include ").append(formatIncludeTarget(hdr)).append('\n')
            }
            sb.append("#endif\n")
        }
        if (needsDynamicLookup) {
            sb.append("#ifdef _WIN32\n")
            sb.append("#ifndef WIN32_LEAN_AND_MEAN\n#define WIN32_LEAN_AND_MEAN\n#endif\n")
            sb.append("#include <windows.h>\n")
            sb.append("#else\n")
            sb.append("#include <dlfcn.h>\n")
            sb.append("#ifndef RTLD_DEFAULT\n#define RTLD_DEFAULT ((void*)0)\n#endif\n")
            sb.append("#endif\n")
        }
        sb.append('\n')

        if (preambles.isNotEmpty()) {
            for (chunk in preambles) {
                sb.append(chunk).append("\n\n")
            }
        }

        if (usesCppLanguage) {
            sb.append("extern \"C\" {\n\n")
        }

        val emittedImpls = linkedSetOf<String>()
        for (st in foreignStatics) {
            if (!isBridgeableStaticInCWrapper(st)) continue
            if (emittedImpls.add(st.getterSymbolName)) {
                sb.append(renderDynamicForeignStaticGetterCFunction(st)).append('\n')
            }
            if (st.isMutable && emittedImpls.add(st.setterSymbolName)) {
                sb.append(renderDynamicForeignStaticSetterCFunction(st)).append('\n')
            }
        }
        for (fn in foreignFunctions) {
            if (!isBridgeableInCWrapper(fn)) continue
            if (!emittedImpls.add(fn.wrapperSymbolName)) continue
            if (canCallDirectlyInCWrapper(fn)) {
                sb.append(renderForeignWrapperCFunction(fn)).append('\n')
            } else {
                sb.append(renderDynamicForeignWrapperCFunction(fn)).append('\n')
            }
        }
        for (fn in inlineFunctions) {
            if (!isBridgeableInlineInCWrapper(fn)) continue
            if (!emittedImpls.add(fn.wrapperSymbolName)) continue
            sb.append(renderInlineWrapperCFunction(fn)).append('\n')
        }

        if (usesCppLanguage) {
            sb.append("} /* extern \"C\" */\n")
        }
        return sb.toString()
    }

    private fun renderDynamicForeignStaticGetterCFunction(st: ForeignStaticInteropSpec): String {
        val sb = StringBuilder()
        val cType = cHeaderTypeForBridge(st.bridgeKind)
        val defaultLit = cDefaultFailureLiteral(st.bridgeKind)
        val symEsc = escapeCString(st.cSymbolName)
        sb.append("$cType ${st.getterSymbolName}(void) {\n")
        sb.append("    static $cType* km_sym_ptr = NULL;\n")
        sb.append("#ifdef _WIN32\n")
        sb.append("    if (km_sym_ptr == NULL) {\n")
        sb.append("        HMODULE km_h_msvcrt = GetModuleHandleA(\"msvcrt.dll\");\n")
        sb.append("        if (km_h_msvcrt == NULL) km_h_msvcrt = LoadLibraryA(\"msvcrt.dll\");\n")
        sb.append("        if (km_h_msvcrt != NULL) km_sym_ptr = ($cType*)(void*)GetProcAddress(km_h_msvcrt, \"$symEsc\");\n")
        if (!st.cSymbolName.startsWith("_")) {
            sb.append("        if (km_sym_ptr == NULL && km_h_msvcrt != NULL) km_sym_ptr = ($cType*)(void*)GetProcAddress(km_h_msvcrt, \"_$symEsc\");\n")
        }
        sb.append("        if (km_sym_ptr == NULL) {\n")
        sb.append("            HMODULE km_h_ucrt = GetModuleHandleA(\"ucrtbase.dll\");\n")
        sb.append("            if (km_h_ucrt == NULL) km_h_ucrt = LoadLibraryA(\"ucrtbase.dll\");\n")
        sb.append("            if (km_h_ucrt != NULL) km_sym_ptr = ($cType*)(void*)GetProcAddress(km_h_ucrt, \"$symEsc\");\n")
        if (!st.cSymbolName.startsWith("_")) {
            sb.append("            if (km_sym_ptr == NULL && km_h_ucrt != NULL) km_sym_ptr = ($cType*)(void*)GetProcAddress(km_h_ucrt, \"_$symEsc\");\n")
        }
        sb.append("        }\n")
        sb.append("    }\n")
        sb.append("#else\n")
        sb.append("    if (km_sym_ptr == NULL) {\n")
        sb.append("        km_sym_ptr = ($cType*)dlsym(RTLD_DEFAULT, \"$symEsc\");\n")
        sb.append("    }\n")
        sb.append("#endif\n")
        sb.append("    if (km_sym_ptr != NULL) {\n")
        sb.append("        return *km_sym_ptr;\n")
        sb.append("    }\n")
        sb.append("    return $defaultLit;\n")
        sb.append("}\n")
        return sb.toString()
    }

    private fun renderDynamicForeignStaticSetterCFunction(st: ForeignStaticInteropSpec): String {
        val sb = StringBuilder()
        val cType = cHeaderTypeForBridge(st.bridgeKind)
        val symEsc = escapeCString(st.cSymbolName)
        sb.append("void ${st.setterSymbolName}($cType km_val) {\n")
        sb.append("    static $cType* km_sym_ptr = NULL;\n")
        sb.append("#ifdef _WIN32\n")
        sb.append("    if (km_sym_ptr == NULL) {\n")
        sb.append("        HMODULE km_h_msvcrt = GetModuleHandleA(\"msvcrt.dll\");\n")
        sb.append("        if (km_h_msvcrt == NULL) km_h_msvcrt = LoadLibraryA(\"msvcrt.dll\");\n")
        sb.append("        if (km_h_msvcrt != NULL) km_sym_ptr = ($cType*)(void*)GetProcAddress(km_h_msvcrt, \"$symEsc\");\n")
        if (!st.cSymbolName.startsWith("_")) {
            sb.append("        if (km_sym_ptr == NULL && km_h_msvcrt != NULL) km_sym_ptr = ($cType*)(void*)GetProcAddress(km_h_msvcrt, \"_$symEsc\");\n")
        }
        sb.append("        if (km_sym_ptr == NULL) {\n")
        sb.append("            HMODULE km_h_ucrt = GetModuleHandleA(\"ucrtbase.dll\");\n")
        sb.append("            if (km_h_ucrt == NULL) km_h_ucrt = LoadLibraryA(\"ucrtbase.dll\");\n")
        sb.append("            if (km_h_ucrt != NULL) km_sym_ptr = ($cType*)(void*)GetProcAddress(km_h_ucrt, \"$symEsc\");\n")
        if (!st.cSymbolName.startsWith("_")) {
            sb.append("            if (km_sym_ptr == NULL && km_h_ucrt != NULL) km_sym_ptr = ($cType*)(void*)GetProcAddress(km_h_ucrt, \"_$symEsc\");\n")
        }
        sb.append("        }\n")
        sb.append("    }\n")
        sb.append("#else\n")
        sb.append("    if (km_sym_ptr == NULL) {\n")
        sb.append("        km_sym_ptr = ($cType*)dlsym(RTLD_DEFAULT, \"$symEsc\");\n")
        sb.append("    }\n")
        sb.append("#endif\n")
        sb.append("    if (km_sym_ptr != NULL) {\n")
        sb.append("        *km_sym_ptr = km_val;\n")
        sb.append("    }\n")
        sb.append("}\n")
        return sb.toString()
    }

    private fun renderForeignWrapperCFunction(fn: ForeignFnInteropSpec): String {
        val sb = StringBuilder()
        val retType = cHeaderTypeForReturn(fn)
        val paramsText =
            if (fn.params.isEmpty()) {
                "void"
            } else {
                fn.params.joinToString(", ") { "${cHeaderTypeForParam(it)} ${it.cName}" }
            }
        sb.append("$retType ${fn.wrapperSymbolName}($paramsText) {\n")

        val symGuard = symbolPlatformGuard(fn.cSymbolName)
        val callArgs =
            fn.params.joinToString(", ") { param ->
                when (param.bridgeKind) {
                    CAbiBridgeKind.FILE_PTR -> "(FILE*)${param.cName}"
                    CAbiBridgeKind.DIR_PTR -> "(DIR*)${param.cName}"
                    else -> param.cName
                }
            }
        val callExpr = "${fn.cSymbolName}($callArgs)"

        if (symGuard != null) {
            sb.append("#if ").append(symGuard).append('\n')
        }
        when (fn.returnBridgeKind) {
            CAbiBridgeKind.VOID -> sb.append("    ").append(callExpr).append(";\n")
            CAbiBridgeKind.NEVER -> {
                sb.append("    ").append(callExpr).append(";\n")
            }
            else -> sb.append("    return ($retType)$callExpr;\n")
        }
        if (symGuard != null) {
            sb.append("#else\n")
            for (param in fn.params) {
                sb.append("    (void)").append(param.cName).append(";\n")
            }
            sb.append("    errno = ENOSYS;\n")
            if (fn.returnBridgeKind != CAbiBridgeKind.VOID && fn.returnBridgeKind != CAbiBridgeKind.NEVER) {
                sb.append("    return ").append(cFailureReturnExpr(fn)).append(";\n")
            }
            sb.append("#endif\n")
        }
        sb.append("}\n")
        return sb.toString()
    }

    private fun renderDynamicForeignWrapperCFunction(fn: ForeignFnInteropSpec): String {
        val sb = StringBuilder()
        val retType = cHeaderTypeForReturn(fn)
        val fixedParams = fn.params.map { "${cHeaderTypeForParam(it)} ${it.cName}" }
        val allParams =
            if (fn.isVariadic) {
                fixedParams + listOf("int32_t km_vac", "int64_t km_va0", "int64_t km_va1", "int64_t km_va2", "int64_t km_va3")
            } else {
                fixedParams
            }
        val paramsText = if (allParams.isEmpty()) "void" else allParams.joinToString(", ")
        val paramTypesOnly =
            if (fn.params.isEmpty()) {
                "void"
            } else if (fn.isVariadic) {
                fn.params.joinToString(", ") { cHeaderTypeForParam(it) } + ", ..."
            } else {
                fn.params.joinToString(", ") { cHeaderTypeForParam(it) }
            }
        val callArgs = fn.params.joinToString(", ") { it.cName }
        val failExpr = cFailureReturnExpr(fn)
        val hasReturn = fn.returnBridgeKind != CAbiBridgeKind.VOID && fn.returnBridgeKind != CAbiBridgeKind.NEVER
        val isAtexit = isAtexitForeignFn(fn)

        sb.append("$retType ${fn.wrapperSymbolName}($paramsText) {\n")
        if (isAtexit) {
            val cbName = fn.params[0].cName
            sb.append("    if ($cbName == NULL) {\n")
            sb.append("        errno = EINVAL;\n")
            sb.append("        return -1;\n")
            sb.append("    }\n")
            sb.append("#ifdef __ANDROID__\n")
            sb.append("    return (int32_t)atexit((void (*)(void))(uintptr_t)$cbName);\n")
            sb.append("#else\n")
        }
        if (fn.dllName != null) {
            sb.append("#ifdef _WIN32\n")
            val stdcallAttr = if (fn.isVariadic) "" else "__stdcall "
            sb.append("    typedef $retType ($stdcallAttr*km_fn_t)($paramTypesOnly);\n")
            sb.append("    static km_fn_t km_fn_ptr = NULL;\n")
            sb.append("    if (km_fn_ptr == NULL) {\n")
            sb.append("        HMODULE km_h_mod = LoadLibraryA(\"${escapeCString(fn.dllName)}\");\n")
            sb.append("        if (km_h_mod != NULL) {\n")
            sb.append("            km_fn_ptr = (km_fn_t)(void*)GetProcAddress(km_h_mod, \"${escapeCString(fn.cSymbolName)}\");\n")
            sb.append("        }\n")
            sb.append("    }\n")
            sb.append("    if (km_fn_ptr != NULL) {\n")
            if (fn.isVariadic) {
                if (hasReturn) {
                    sb.append("        switch (km_vac) {\n")
                    sb.append("            case 0: return km_fn_ptr($callArgs);\n")
                    sb.append("            case 1: return km_fn_ptr($callArgs, (intptr_t)km_va0);\n")
                    sb.append("            case 2: return km_fn_ptr($callArgs, (intptr_t)km_va0, (intptr_t)km_va1);\n")
                    sb.append("            case 3: return km_fn_ptr($callArgs, (intptr_t)km_va0, (intptr_t)km_va1, (intptr_t)km_va2);\n")
                    sb.append("            default: return km_fn_ptr($callArgs, (intptr_t)km_va0, (intptr_t)km_va1, (intptr_t)km_va2, (intptr_t)km_va3);\n")
                    sb.append("        }\n")
                } else {
                    sb.append("        switch (km_vac) {\n")
                    sb.append("            case 0: km_fn_ptr($callArgs); break;\n")
                    sb.append("            case 1: km_fn_ptr($callArgs, (intptr_t)km_va0); break;\n")
                    sb.append("            case 2: km_fn_ptr($callArgs, (intptr_t)km_va0, (intptr_t)km_va1); break;\n")
                    sb.append("            case 3: km_fn_ptr($callArgs, (intptr_t)km_va0, (intptr_t)km_va1, (intptr_t)km_va2); break;\n")
                    sb.append("            default: km_fn_ptr($callArgs, (intptr_t)km_va0, (intptr_t)km_va1, (intptr_t)km_va2, (intptr_t)km_va3); break;\n")
                    sb.append("        }\n")
                    sb.append("        return;\n")
                }
            } else if (hasReturn) {
                sb.append("        return km_fn_ptr($callArgs);\n")
            } else {
                sb.append("        km_fn_ptr($callArgs);\n")
                sb.append("        return;\n")
            }
            sb.append("    }\n")
            sb.append("    errno = ENOSYS;\n")
            if (hasReturn) {
                sb.append("    return $failExpr;\n")
            }
            sb.append("#else\n")
            for (param in fn.params) {
                sb.append("    (void)").append(param.cName).append(";\n")
            }
            if (fn.isVariadic) {
                sb.append("    (void)km_vac;\n    (void)km_va0;\n    (void)km_va1;\n    (void)km_va2;\n    (void)km_va3;\n")
            }
            sb.append("    errno = ENOSYS;\n")
            if (hasReturn) {
                sb.append("    return $failExpr;\n")
            }
            sb.append("#endif\n")
        } else {
            sb.append("    typedef $retType (*km_fn_t)($paramTypesOnly);\n")
            sb.append("    static km_fn_t km_fn_ptr = NULL;\n")
            sb.append("#ifdef _WIN32\n")
            sb.append("    if (km_fn_ptr == NULL) {\n")
            sb.append("        HMODULE km_h_msvcrt = GetModuleHandleA(\"msvcrt.dll\");\n")
            sb.append("        if (km_h_msvcrt == NULL) km_h_msvcrt = LoadLibraryA(\"msvcrt.dll\");\n")
            sb.append("        if (km_h_msvcrt != NULL) km_fn_ptr = (km_fn_t)(void*)GetProcAddress(km_h_msvcrt, \"${escapeCString(fn.cSymbolName)}\");\n")
            if (!fn.cSymbolName.startsWith("_")) {
                sb.append("        if (km_fn_ptr == NULL && km_h_msvcrt != NULL) km_fn_ptr = (km_fn_t)(void*)GetProcAddress(km_h_msvcrt, \"_${escapeCString(fn.cSymbolName)}\");\n")
            }
            sb.append("        if (km_fn_ptr == NULL) {\n")
            sb.append("            HMODULE km_h_ucrt = GetModuleHandleA(\"ucrtbase.dll\");\n")
            sb.append("            if (km_h_ucrt == NULL) km_h_ucrt = LoadLibraryA(\"ucrtbase.dll\");\n")
            sb.append("            if (km_h_ucrt != NULL) km_fn_ptr = (km_fn_t)(void*)GetProcAddress(km_h_ucrt, \"${escapeCString(fn.cSymbolName)}\");\n")
            if (!fn.cSymbolName.startsWith("_")) {
                sb.append("            if (km_fn_ptr == NULL && km_h_ucrt != NULL) km_fn_ptr = (km_fn_t)(void*)GetProcAddress(km_h_ucrt, \"_${escapeCString(fn.cSymbolName)}\");\n")
            }
            sb.append("        }\n")
            sb.append("    }\n")
            sb.append("#else\n")
            sb.append("    if (km_fn_ptr == NULL) {\n")
            sb.append("        km_fn_ptr = (km_fn_t)dlsym(RTLD_DEFAULT, \"${escapeCString(fn.cSymbolName)}\");\n")
            appendCPosixDynamicLookupFallbacks(sb, fn)
            sb.append("    }\n")
            sb.append("#endif\n")
            sb.append("    if (km_fn_ptr != NULL) {\n")
            if (fn.isVariadic) {
                if (hasReturn) {
                    sb.append("        switch (km_vac) {\n")
                    sb.append("            case 0: return km_fn_ptr($callArgs);\n")
                    sb.append("            case 1: return km_fn_ptr($callArgs, (intptr_t)km_va0);\n")
                    sb.append("            case 2: return km_fn_ptr($callArgs, (intptr_t)km_va0, (intptr_t)km_va1);\n")
                    sb.append("            case 3: return km_fn_ptr($callArgs, (intptr_t)km_va0, (intptr_t)km_va1, (intptr_t)km_va2);\n")
                    sb.append("            default: return km_fn_ptr($callArgs, (intptr_t)km_va0, (intptr_t)km_va1, (intptr_t)km_va2, (intptr_t)km_va3);\n")
                    sb.append("        }\n")
                } else {
                    sb.append("        switch (km_vac) {\n")
                    sb.append("            case 0: km_fn_ptr($callArgs); break;\n")
                    sb.append("            case 1: km_fn_ptr($callArgs, (intptr_t)km_va0); break;\n")
                    sb.append("            case 2: km_fn_ptr($callArgs, (intptr_t)km_va0, (intptr_t)km_va1); break;\n")
                    sb.append("            case 3: km_fn_ptr($callArgs, (intptr_t)km_va0, (intptr_t)km_va1, (intptr_t)km_va2); break;\n")
                    sb.append("            default: km_fn_ptr($callArgs, (intptr_t)km_va0, (intptr_t)km_va1, (intptr_t)km_va2, (intptr_t)km_va3); break;\n")
                    sb.append("        }\n")
                    sb.append("        return;\n")
                }
            } else if (hasReturn) {
                sb.append("        return km_fn_ptr($callArgs);\n")
            } else {
                sb.append("        km_fn_ptr($callArgs);\n")
                sb.append("        return;\n")
            }
            sb.append("    }\n")
            sb.append("    errno = ENOSYS;\n")
            if (hasReturn) {
                sb.append("    return $failExpr;\n")
            }
        }
        if (isAtexit) {
            sb.append("#endif\n")
        }
        sb.append("}\n")
        return sb.toString()
    }

    private fun renderInlineWrapperCFunction(fn: InlineCFunctionSpec): String {
        val sb = StringBuilder()
        val retType = cHeaderTypeForInlineReturn(fn)
        val paramsText =
            if (fn.params.isEmpty()) {
                "void"
            } else {
                fn.params.joinToString(", ") { "${cHeaderTypeForParam(it)} ${it.cName}" }
            }
        sb.append("$retType ${fn.wrapperSymbolName}($paramsText) {\n")
        for (param in fn.params) {
            if (!containsCIdentifier(fn.rawBody, param.cName)) {
                sb.append("    (void)").append(param.cName).append(";\n")
            }
        }
        val bodyTrimmed = fn.rawBody.trim()
        if (bodyTrimmed.isNotEmpty()) {
            for (line in bodyTrimmed.lines()) {
                if (line.isBlank()) {
                    sb.append('\n')
                } else {
                    sb.append("    ").append(line.trimEnd()).append('\n')
                }
            }
        }
        sb.append("}\n")
        return sb.toString()
    }

    private fun buildNativeActualsKotlinContent(
        pkgName: String?,
        cinteropPkg: String,
    ): String {
        val sb = StringBuilder()
        sb.append("@file:OptIn(ExperimentalForeignApi::class, ExperimentalUnsignedTypes::class)\n\n")
        if (!pkgName.isNullOrBlank()) {
            sb.append("package ").append(pkgName).append("\n\n")
        }
        val imports = linkedSetOf("kotlinx.cinterop.ExperimentalForeignApi")

        val actualBlocks = mutableListOf<String>()
        val emittedActuals = linkedSetOf<String>()
        val declaredInFile =
            buildSet {
                addAll(localDeclaredTypeNames)
                addAll(headerContext.moduleDeclaredTypeNames)
                for (fn in foreignFunctions) add(fn.kotlinName)
                for (fn in inlineFunctions) add(fn.kotlinName)
                for (st in foreignStatics) add(st.kotlinName)
            }

        fun resolveCinteropCallee(wrapperSym: String): Pair<String, String> =
            if (wrapperSym in declaredInFile) {
                val alias = "cinterop${toPascalCaseIdentifierPart(wrapperSym)}"
                alias to "$cinteropPkg.$wrapperSym as $alias"
            } else {
                wrapperSym to "$cinteropPkg.$wrapperSym"
            }

        for (st in foreignStatics) {
            if (!emittedActuals.add("val:${st.kotlinName}")) continue
            actualBlocks +=
                if (isBridgeableStaticInCWrapper(st)) {
                    val (getterCallee, getterImport) = resolveCinteropCallee(st.getterWrapperSymbolName)
                    val setterPair = if (st.isMutable) resolveCinteropCallee(st.setterWrapperSymbolName) else null
                    val rendered =
                        renderNativeStaticActualProperty(
                            st = st,
                            getterCallee = getterCallee,
                            setterCallee = setterPair?.first,
                            pkgName = pkgName,
                            importsOut = imports,
                        )
                    val renderedTokens = extractIdentifierTokens(rendered)
                    if (getterCallee in renderedTokens) {
                        imports += getterImport
                    }
                    if (setterPair != null && setterPair.first in renderedTokens) {
                        imports += setterPair.second
                    }
                    rendered
                } else {
                    renderStaticActualProperty(st)
                }
        }
        for (fn in foreignFunctions) {
            if (!emittedActuals.add("fn:${fn.kotlinName}:${fn.params.size}")) continue
            actualBlocks +=
                if (isBridgeableInCWrapper(fn)) {
                    val (calleeIdent, calleeImport) = resolveCinteropCallee(fn.wrapperSymbolName)
                    val rendered =
                        renderNativeActualFunction(
                            visibilityPrefix = fn.visibilityPrefix,
                            kotlinName = fn.kotlinName,
                            cinteropCallee = calleeIdent,
                            params = fn.params,
                            kotlinReturnType = fn.kotlinReturnType,
                            returnBridgeKind = fn.returnBridgeKind,
                            returnDefaultExpr = fn.returnDefaultExpr,
                            noStringConvEnabled = requiresNoStringConversion(fn.params),
                            isVariadic = fn.isVariadic,
                            pkgName = pkgName,
                            importsOut = imports,
                        )
                    if (calleeIdent in extractIdentifierTokens(rendered)) {
                        imports += calleeImport
                    }
                    rendered
                } else {
                    renderFallbackActualFunction(
                        visibilityPrefix = fn.visibilityPrefix,
                        kotlinName = fn.kotlinName,
                        params = fn.params,
                        kotlinReturnType = fn.kotlinReturnType,
                        returnBridgeKind = fn.returnBridgeKind,
                        returnDefaultExpr = fn.returnDefaultExpr,
                        isVariadic = fn.isVariadic,
                    )
                }
        }
        for (fn in inlineFunctions) {
            if (!emittedActuals.add("fn:${fn.kotlinName}:${fn.params.size}")) continue
            val inlineNoStringConv =
                requiresNoStringConversion(fn.params) ||
                    fn.params.any { it.bridgeKind == CAbiBridgeKind.MUT_CSTR }
            actualBlocks +=
                if (isBridgeableInlineInCWrapper(fn)) {
                    val (calleeIdent, calleeImport) = resolveCinteropCallee(fn.wrapperSymbolName)
                    val rendered =
                        renderNativeActualFunction(
                            visibilityPrefix = fn.visibilityPrefix,
                            kotlinName = fn.kotlinName,
                            cinteropCallee = calleeIdent,
                            params = fn.params,
                            kotlinReturnType = fn.kotlinReturnType,
                            returnBridgeKind = fn.returnBridgeKind,
                            returnDefaultExpr = fn.returnDefaultExpr,
                            noStringConvEnabled = inlineNoStringConv,
                            isVariadic = false,
                            pkgName = pkgName,
                            importsOut = imports,
                        )
                    if (calleeIdent in extractIdentifierTokens(rendered)) {
                        imports += calleeImport
                    }
                    rendered
                } else {
                    renderFallbackActualFunction(
                        visibilityPrefix = fn.visibilityPrefix,
                        kotlinName = fn.kotlinName,
                        params = fn.params,
                        kotlinReturnType = fn.kotlinReturnType,
                        returnBridgeKind = fn.returnBridgeKind,
                        returnDefaultExpr = fn.returnDefaultExpr,
                        isVariadic = fn.isVariadic,
                    )
                }
        }

        val bodyJoined = PhantomRawPointerTypeStripper.strip(actualBlocks.joinToString("\n\n"))
        addRecordedImportsForBody(bodyJoined, pkgName, imports)

        if (imports.isNotEmpty()) {
            for (imp in sortImportsForKtlint(imports)) {
                sb.append("import ").append(imp).append('\n')
            }
            sb.append('\n')
        }

        sb.append(bodyJoined)
        if (actualBlocks.isNotEmpty()) {
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun addRecordedImportsForBody(
        bodyText: String,
        pkgName: String?,
        importsOut: MutableSet<String>,
    ) {
        val usedIdents = extractIdentifierTokens(bodyText)
        val localDeclaredNames =
            buildSet {
                addAll(localDeclaredTypeNames)
                addAll(headerContext.moduleDeclaredTypeNames)
                for (fn in foreignFunctions) add(fn.kotlinName)
                for (fn in inlineFunctions) add(fn.kotlinName)
                for (st in foreignStatics) add(st.kotlinName)
            }
        val explicitlyImportedNames =
            linkedSetOf<String>().apply {
                for (existing in importsOut) {
                    if (!existing.endsWith(".*")) {
                        val aliasSep = existing.indexOf(" as ")
                        add(
                            if (aliasSep > 0) {
                                existing.substring(aliasSep + 4).trim()
                            } else {
                                existing.substringAfterLast('.')
                            },
                        )
                    }
                }
            }
        for (imp in recordedImportLines) {
            if (imp.endsWith(".*")) continue
            val aliasSep = imp.indexOf(" as ")
            val simpleName =
                if (aliasSep > 0) {
                    imp.substring(aliasSep + 4).trim()
                } else {
                    imp.substringAfterLast('.')
                }
            if (simpleName in usedIdents && simpleName !in localDeclaredNames) {
                val impPkg = if (aliasSep > 0) imp.substring(0, aliasSep).trim().substringBeforeLast('.') else imp.substringBeforeLast('.')
                if (impPkg != pkgName) {
                    importsOut += imp
                    explicitlyImportedNames += simpleName
                } else {
                    explicitlyImportedNames += simpleName
                }
            }
        }
        val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
        for ((sym, targetPkg) in listOf(
            "ConstPtr" to "$coreNs.ptr",
            "MutPtr" to "$coreNs.ptr",
            "CVoid" to "$coreNs.ffi",
        )) {
            if (targetPkg != pkgName && sym in usedIdents && sym !in localDeclaredNames && sym !in explicitlyImportedNames) {
                importsOut += "$targetPkg.$sym"
                explicitlyImportedNames += sym
            }
        }
        val hasUnresolvedTypeIdent =
            usedIdents.any { ident ->
                ident !in BUILTIN_KOTLIN_IDENTS &&
                    ident !in localDeclaredNames &&
                    ident !in explicitlyImportedNames &&
                    (ident.first().isUpperCase() || ident.endsWith("_t") || ident.startsWith("c_") || ident.startsWith("__"))
            }
        if (hasUnresolvedTypeIdent) {
            for (imp in recordedImportLines) {
                if (imp.endsWith(".*")) {
                    val starPkg = imp.removeSuffix(".*")
                    if (starPkg != pkgName) {
                        importsOut += imp
                    }
                }
            }
        }
        val rawIdents = extractIdentifierTokens(bodyText, includeBuiltins = true)
        importsOut.retainAll { existing ->
            if (existing.endsWith(".*") || existing == "kotlinx.cinterop.ExperimentalForeignApi") {
                true
            } else {
                val aliasSep = existing.indexOf(" as ")
                val localName =
                    if (aliasSep > 0) {
                        existing.substring(aliasSep + 4).trim()
                    } else {
                        existing.substringAfterLast('.')
                    }
                localName in rawIdents
            }
        }
    }

    private fun renderStaticActualProperty(st: ForeignStaticInteropSpec): String =
        if (st.isMutable) {
            val backingName = "ffiStatic${toPascalCaseIdentifierPart(st.kotlinName)}"
            "private var $backingName: ${st.kotlinType} = ${st.defaultExpr}\n" +
                "${st.visibilityPrefix}actual var ${st.kotlinName}: ${st.kotlinType}\n" +
                "    get() = $backingName\n" +
                "    set(value) {\n" +
                "        $backingName = value\n" +
                "    }"
        } else {
            "${st.visibilityPrefix}actual val ${st.kotlinName}: ${st.kotlinType}\n" +
                "    get() = ${st.defaultExpr}"
        }

    private fun renderNativeStaticActualProperty(
        st: ForeignStaticInteropSpec,
        getterCallee: String,
        setterCallee: String?,
        pkgName: String?,
        importsOut: MutableSet<String>,
    ): String {
        val getCall = "$getterCallee()"
        val getBody = renderNativeReturnConversion(getCall, st.kotlinType, st.bridgeKind, st.defaultExpr, pkgName, importsOut)
        return if (st.isMutable) {
            val valParam = InteropParamSpec(kotlinName = "value", kotlinType = st.kotlinType, cName = "value", bridgeKind = st.bridgeKind)
            val setArg = renderNativeParamConversion(valParam, noStringConvEnabled = true, importsOut = importsOut)
            "${st.visibilityPrefix}actual var ${st.kotlinName}: ${st.kotlinType}\n" +
                "    get() = $getBody\n" +
                "    set(value) {\n" +
                "        ${setterCallee ?: st.setterWrapperSymbolName}($setArg)\n" +
                "    }"
        } else {
            "${st.visibilityPrefix}actual val ${st.kotlinName}: ${st.kotlinType}\n" +
                "    get() = $getBody"
        }
    }

    private fun renderFallbackActualFunction(
        visibilityPrefix: String,
        kotlinName: String,
        params: List<InteropParamSpec>,
        kotlinReturnType: String,
        returnBridgeKind: CAbiBridgeKind,
        returnDefaultExpr: String,
        isVariadic: Boolean = false,
    ): String {
        val baseParams = params.map { "${it.kotlinName}: ${it.kotlinType}" }
        val paramsSig =
            (if (isVariadic) baseParams + "vararg variadicArgs: Long" else baseParams)
                .joinToString(", ")
        val retSuffix = if (returnBridgeKind == CAbiBridgeKind.VOID) "" else ": $kotlinReturnType"
        return when {
            returnBridgeKind == CAbiBridgeKind.VOID ->
                "${visibilityPrefix}actual fun $kotlinName($paramsSig) {\n}"
            returnBridgeKind == CAbiBridgeKind.NEVER || kotlinReturnType == "Nothing" ->
                "${visibilityPrefix}actual fun $kotlinName($paramsSig): Nothing =\n" +
                    "    error(\"Unreachable noreturn C function $kotlinName\")"
            else ->
                "${visibilityPrefix}actual fun $kotlinName($paramsSig)$retSuffix =\n" +
                    "    $returnDefaultExpr"
        }
    }

    private fun renderUnsupportedActualFunction(
        visibilityPrefix: String,
        kotlinName: String,
        params: List<InteropParamSpec>,
        kotlinReturnType: String,
        returnBridgeKind: CAbiBridgeKind,
        isVariadic: Boolean = false,
        message: String = "Native FFI is not available on WasmWasi",
    ): String {
        val baseParams = params.map { "${it.kotlinName}: ${it.kotlinType}" }
        val paramsSig =
            (if (isVariadic) baseParams + "vararg variadicArgs: Long" else baseParams)
                .joinToString(", ")
        if (returnBridgeKind == CAbiBridgeKind.VOID) {
            return "${visibilityPrefix}actual fun $kotlinName($paramsSig) {\n" +
                "    throw UnsupportedOperationException(\"$message\")\n" +
                "}"
        }
        return "${visibilityPrefix}actual fun $kotlinName($paramsSig): $kotlinReturnType =\n" +
            "    throw UnsupportedOperationException(\"$message\")"
    }

    // WasmImport has no Kotlin adapters. Narrow C integers and bool are i32,
    // while i64/f32/f64 retain their Wasm value types. Reference-valued C ABI
    // bridges require a host memory/handle adapter and must not be passed as
    // Kotlin references or fabricated numeric addresses.
    private fun wasmWasiScalarType(kind: CAbiBridgeKind): String? =
        when (kind) {
            CAbiBridgeKind.VOID, CAbiBridgeKind.NEVER -> "Unit"
            CAbiBridgeKind.BOOL,
            CAbiBridgeKind.I8, CAbiBridgeKind.U8,
            CAbiBridgeKind.I16, CAbiBridgeKind.U16,
            CAbiBridgeKind.I32, CAbiBridgeKind.U32,
            -> "Int"
            CAbiBridgeKind.I64, CAbiBridgeKind.U64 -> "Long"
            CAbiBridgeKind.F32 -> "Float"
            CAbiBridgeKind.F64 -> "Double"
            else -> null
        }

    private fun renderWasmWasiScalarArgument(param: InteropParamSpec): String =
        when (param.bridgeKind) {
            CAbiBridgeKind.BOOL -> "if (${param.kotlinName}) 1 else 0"
            CAbiBridgeKind.I8, CAbiBridgeKind.U8,
            CAbiBridgeKind.I16, CAbiBridgeKind.U16,
            CAbiBridgeKind.I32, CAbiBridgeKind.U32,
            -> renderScalarParamArg(param, NumericKind.I32, "toInt")
            CAbiBridgeKind.I64, CAbiBridgeKind.U64 -> renderScalarParamArg(param, NumericKind.I64, "toLong")
            CAbiBridgeKind.F32 -> renderScalarParamArg(param, NumericKind.F32, "toFloat")
            CAbiBridgeKind.F64 -> renderScalarParamArg(param, NumericKind.F64, "toDouble")
            else -> error("Not a scalar Wasm ABI parameter: ${param.bridgeKind}")
        }

    private fun renderWasmWasiScalarReturn(rawCall: String, kotlinType: String, kind: CAbiBridgeKind): String {
        if (kind == CAbiBridgeKind.BOOL) return "($rawCall != 0)"
        if (kind == CAbiBridgeKind.VOID || kind == CAbiBridgeKind.NEVER) return rawCall
        val numericKind = resolveNumericKind(kotlinType)
        val conversion =
            if (numericKind != null) {
                conversionMethodForNumericKind(numericKind)
            } else {
                when (kind) {
                    CAbiBridgeKind.I8 -> "toByte"
                    CAbiBridgeKind.U8 -> "toUByte"
                    CAbiBridgeKind.I16 -> "toShort"
                    CAbiBridgeKind.U16 -> "toUShort"
                    CAbiBridgeKind.U32 -> "toUInt"
                    CAbiBridgeKind.U64 -> "toULong"
                    else -> null
                }
            }
        return if (conversion == null) rawCall else "$rawCall.$conversion()"
    }

    private fun buildWasmWasiActualsKotlinContent(pkgName: String?): String {
        val sb = StringBuilder()
        sb.append("@file:OptIn(ExperimentalUnsignedTypes::class, ExperimentalWasmInterop::class)\n\n")
        if (!pkgName.isNullOrBlank()) {
            sb.append("package ").append(pkgName).append("\n\n")
        }
        val imports = linkedSetOf<String>()
        val actualBlocks = mutableListOf<String>()
        val emittedActuals = linkedSetOf<String>()
        val emittedImports = linkedSetOf<String>()
        val module = computeFfiModuleNameLower(headerContext)

        fun importFunction(symbol: String, params: List<InteropParamSpec>, returnKind: CAbiBridgeKind): String {
            val name = "wasi${toPascalCaseIdentifierPart(symbol)}"
            if (emittedImports.add(symbol)) {
                val paramsSig = params.joinToString(", ") { "${it.kotlinName}: ${wasmWasiScalarType(it.bridgeKind)}" }
                actualBlocks +=
                    "@kotlin.wasm.WasmImport(\"$module\", \"$symbol\")\n" +
                    "private external fun $name($paramsSig): ${wasmWasiScalarType(returnKind)}"
            }
            return name
        }

        fun scalarFunction(
            visibility: String,
            name: String,
            symbol: String,
            params: List<InteropParamSpec>,
            returnType: String,
            returnKind: CAbiBridgeKind,
            variadic: Boolean,
        ): String {
            // The C variadic ABI needs a target-specific argument area, not a
            // Kotlin vararg array. Do not silently truncate or reinterpret it.
            if (variadic ||
                wasmWasiScalarType(returnKind) == null ||
                params.any { wasmWasiScalarType(it.bridgeKind) in listOf(null, "Unit") }
            ) {
                return renderUnsupportedActualFunction(
                    visibility,
                    name,
                    params,
                    returnType,
                    returnKind,
                    variadic,
                    "WasmWasi FFI requires a host memory/callback/aggregate or variadic ABI adapter for $symbol",
                )
            }
            val callee = importFunction(symbol, params, returnKind)
            val signature = params.joinToString(", ") { "${it.kotlinName}: ${it.kotlinType}" }
            val call = "$callee(${params.joinToString(", ", transform = ::renderWasmWasiScalarArgument)})"
            return if (returnKind == CAbiBridgeKind.NEVER) {
                "${visibility}actual fun $name($signature): Nothing {\n" +
                    "    $call\n    error(\"C noreturn function $symbol returned\")\n}"
            } else {
                val result = renderWasmWasiScalarReturn(call, returnType, returnKind)
                "${visibility}actual fun $name($signature): $returnType =\n    $result"
            }
        }

        for (st in foreignStatics) {
            if (!emittedActuals.add("val:${st.kotlinName}")) continue
            val getter =
                if (wasmWasiScalarType(st.bridgeKind) !in listOf(null, "Unit")) {
                    val callee = importFunction(st.getterSymbolName, emptyList(), st.bridgeKind)
                    renderWasmWasiScalarReturn("$callee()", st.kotlinType, st.bridgeKind)
                } else {
                    "throw UnsupportedOperationException(\"WasmWasi foreign static ${st.cSymbolName} requires a host memory/aggregate ABI adapter\")"
                }
            val declaration = "${st.visibilityPrefix}actual ${if (st.isMutable) "var" else "val"} ${st.kotlinName}: ${st.kotlinType}\n    get() = $getter"
            if (st.isMutable) {
                val setter =
                    if (wasmWasiScalarType(st.bridgeKind) !in listOf(null, "Unit")) {
                        val param = InteropParamSpec("value", "value", st.kotlinType, bridgeKind = st.bridgeKind)
                        val callee = importFunction(st.setterSymbolName, listOf(param), CAbiBridgeKind.VOID)
                        "$callee(${renderWasmWasiScalarArgument(param)})"
                    } else {
                        "throw UnsupportedOperationException(\"WasmWasi foreign static ${st.cSymbolName} requires a host memory/aggregate ABI adapter\")"
                    }
                actualBlocks += "$declaration\n    set(value) {\n        $setter\n    }"
            } else {
                actualBlocks += declaration
            }
        }
        for (fn in foreignFunctions) {
            if (!emittedActuals.add("fn:${fn.kotlinName}:${fn.params.size}")) continue
            actualBlocks +=
                scalarFunction(
                    fn.visibilityPrefix,
                    fn.kotlinName,
                    fn.wrapperSymbolName,
                    fn.params,
                    fn.kotlinReturnType,
                    fn.returnBridgeKind,
                    fn.isVariadic,
                )
        }
        for (fn in inlineFunctions) {
            if (!emittedActuals.add("fn:${fn.kotlinName}:${fn.params.size}")) continue
            actualBlocks +=
                scalarFunction(
                    fn.visibilityPrefix,
                    fn.kotlinName,
                    fn.wrapperSymbolName,
                    fn.params,
                    fn.kotlinReturnType,
                    fn.returnBridgeKind,
                    fn.isVariadic,
                )
        }

        val bodyJoined = PhantomRawPointerTypeStripper.strip(actualBlocks.joinToString("\n\n"))
        addRecordedImportsForBody(bodyJoined, pkgName, imports)
        if (imports.isNotEmpty()) {
            for (imp in sortImportsForKtlint(imports)) {
                sb.append("import ").append(imp).append('\n')
            }
            sb.append('\n')
        }
        sb.append("// The host must provide the C companion wrapper exports in import module ").append(module).append(".\n")
        sb.append(bodyJoined)
        if (actualBlocks.isNotEmpty()) {
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun wasmJsInteropTypeForParam(
        param: InteropParamSpec,
        noStringConvEnabled: Boolean,
        ktStringType: String = "String",
    ): String =
        when (param.bridgeKind) {
            CAbiBridgeKind.VOID, CAbiBridgeKind.NEVER -> "Unit"
            CAbiBridgeKind.BOOL -> "Boolean"
            CAbiBridgeKind.I8, CAbiBridgeKind.U8,
            CAbiBridgeKind.I16, CAbiBridgeKind.U16,
            CAbiBridgeKind.I32, CAbiBridgeKind.U32,
            -> "Int"
            CAbiBridgeKind.I64, CAbiBridgeKind.U64 -> ktStringType
            CAbiBridgeKind.F32, CAbiBridgeKind.F64 -> "Double"
            CAbiBridgeKind.CONST_CSTR ->
                if (!noStringConvEnabled && (param.kotlinType == "String?" || param.kotlinType == "String")) {
                    "$ktStringType?"
                } else {
                    ktStringType
                }
            CAbiBridgeKind.MUT_CSTR,
            CAbiBridgeKind.OPAQUE_PTR,
            CAbiBridgeKind.FILE_PTR,
            CAbiBridgeKind.DIR_PTR,
            CAbiBridgeKind.CALLBACK_PTR,
            CAbiBridgeKind.BY_VALUE_OBJECT,
            -> ktStringType
        }

    private fun wasmJsInteropTypeForReturn(
        returnBridgeKind: CAbiBridgeKind,
        ktStringType: String = "String",
    ): String =
        when (returnBridgeKind) {
            CAbiBridgeKind.VOID, CAbiBridgeKind.NEVER, CAbiBridgeKind.BY_VALUE_OBJECT -> "Unit"
            CAbiBridgeKind.BOOL -> "Boolean"
            CAbiBridgeKind.I8, CAbiBridgeKind.U8,
            CAbiBridgeKind.I16, CAbiBridgeKind.U16,
            CAbiBridgeKind.I32, CAbiBridgeKind.U32,
            -> "Int"
            CAbiBridgeKind.I64, CAbiBridgeKind.U64 -> ktStringType
            CAbiBridgeKind.F32, CAbiBridgeKind.F64 -> "Double"
            CAbiBridgeKind.CONST_CSTR,
            CAbiBridgeKind.MUT_CSTR,
            CAbiBridgeKind.OPAQUE_PTR,
            CAbiBridgeKind.FILE_PTR,
            CAbiBridgeKind.DIR_PTR,
            CAbiBridgeKind.CALLBACK_PTR,
            -> ktStringType
        }

    private fun renderWasmJsExternalDecl(
        wrapperSymbolName: String,
        paramTypes: List<String>,
        returnType: String,
        ffiModName: String,
    ): String {
        val jsParamNames = paramTypes.indices.map { "p$it" }.joinToString(", ")
        val ktParams = paramTypes.mapIndexed { idx, t -> "p$idx: $t" }.joinToString(", ")
        val retSuffix = if (returnType == "Unit") "" else ": $returnType"
        val wasmFnName = "wasm${toPascalCaseIdentifierPart(wrapperSymbolName)}"
        return "@JsFun(\"($jsParamNames) => globalThis.__km_napi_$ffiModName.$wrapperSymbolName($jsParamNames)\")\n" +
            "private external fun $wasmFnName($ktParams)$retSuffix"
    }

    private fun buildWasmJsActualsKotlinContent(
        pkgName: String?,
        stemPascal: String,
        ffiModName: String,
    ): String {
        val sb = StringBuilder()
        sb.append("@file:OptIn(ExperimentalUnsignedTypes::class, ExperimentalWasmJsInterop::class)\n\n")
        if (!pkgName.isNullOrBlank()) {
            sb.append("package ").append(pkgName).append("\n\n")
        }
        val imports = linkedSetOf<String>()
        val actualBlocks = mutableListOf<String>()
        val externalDecls = mutableListOf<String>()
        val emittedExternals = linkedSetOf<String>()
        val emittedActuals = linkedSetOf<String>()
        val stemCamel = stemPascal.replaceFirstChar { it.lowercase() }
        val availPropName = "${stemCamel}NapiAvailable"
        val ktString = if (shadowsKotlinString(pkgName)) "kotlin.String" else "String"
        var usesAddon = false

        for (st in foreignStatics) {
            if (!emittedActuals.add("val:${st.kotlinName}")) continue
            if (isBridgeableStaticInCWrapper(st)) {
                usesAddon = true
                if (emittedExternals.add(st.getterWrapperSymbolName)) {
                    externalDecls +=
                        renderWasmJsExternalDecl(
                            wrapperSymbolName = st.getterWrapperSymbolName,
                            paramTypes = emptyList(),
                            returnType = wasmJsInteropTypeForReturn(st.bridgeKind, ktString),
                            ffiModName = ffiModName,
                        )
                }
                if (st.isMutable && emittedExternals.add(st.setterWrapperSymbolName)) {
                    val valParam = InteropParamSpec(kotlinName = "value", kotlinType = st.kotlinType, cName = "value", bridgeKind = st.bridgeKind)
                    externalDecls +=
                        renderWasmJsExternalDecl(
                            wrapperSymbolName = st.setterWrapperSymbolName,
                            paramTypes = listOf(wasmJsInteropTypeForParam(valParam, noStringConvEnabled = true, ktStringType = ktString)),
                            returnType = "Unit",
                            ffiModName = ffiModName,
                        )
                }
                actualBlocks += renderWasmJsStaticActualProperty(st, availPropName, pkgName, imports)
            } else {
                actualBlocks += renderStaticActualProperty(st)
            }
        }
        for (fn in foreignFunctions) {
            val noStrConv = requiresNoStringConversion(fn.params)
            if (isBridgeableInCWrapper(fn) && emittedExternals.add(fn.wrapperSymbolName)) {
                val baseParamTypes = fn.params.map { wasmJsInteropTypeForParam(it, noStrConv, ktString) }
                val allParamTypes =
                    if (fn.isVariadic) {
                        baseParamTypes + listOf("Int", ktString, ktString, ktString, ktString)
                    } else {
                        baseParamTypes
                    }
                externalDecls +=
                    renderWasmJsExternalDecl(
                        wrapperSymbolName = fn.wrapperSymbolName,
                        paramTypes = allParamTypes,
                        returnType = wasmJsInteropTypeForReturn(fn.returnBridgeKind, ktString),
                        ffiModName = ffiModName,
                    )
            }
            if (!emittedActuals.add("fn:${fn.kotlinName}:${fn.params.size}")) continue
            if (isBridgeableInCWrapper(fn)) {
                usesAddon = true
                actualBlocks +=
                    renderWasmJsActualFunction(
                        visibilityPrefix = fn.visibilityPrefix,
                        kotlinName = fn.kotlinName,
                        availPropName = availPropName,
                        wrapperSymbolName = fn.wrapperSymbolName,
                        params = fn.params,
                        kotlinReturnType = fn.kotlinReturnType,
                        returnBridgeKind = fn.returnBridgeKind,
                        returnDefaultExpr = fn.returnDefaultExpr,
                        noStringConvEnabled = noStrConv,
                        isVariadic = fn.isVariadic,
                        pkgName = pkgName,
                        importsOut = imports,
                    )
            } else {
                actualBlocks +=
                    renderUnsupportedActualFunction(
                        visibilityPrefix = fn.visibilityPrefix,
                        kotlinName = fn.kotlinName,
                        params = fn.params,
                        kotlinReturnType = fn.kotlinReturnType,
                        returnBridgeKind = fn.returnBridgeKind,
                        isVariadic = fn.isVariadic,
                        message = "Native FFI addon is not available",
                    )
            }
        }
        for (fn in inlineFunctions) {
            val inlineNoStrConv =
                requiresNoStringConversion(fn.params) ||
                    fn.params.any { it.bridgeKind == CAbiBridgeKind.MUT_CSTR }
            if (isBridgeableInlineInCWrapper(fn) && emittedExternals.add(fn.wrapperSymbolName)) {
                val baseParamTypes = fn.params.map { wasmJsInteropTypeForParam(it, inlineNoStrConv, ktString) }
                externalDecls +=
                    renderWasmJsExternalDecl(
                        wrapperSymbolName = fn.wrapperSymbolName,
                        paramTypes = baseParamTypes,
                        returnType = wasmJsInteropTypeForReturn(fn.returnBridgeKind, ktString),
                        ffiModName = ffiModName,
                    )
            }
            if (!emittedActuals.add("fn:${fn.kotlinName}:${fn.params.size}")) continue
            if (isBridgeableInlineInCWrapper(fn)) {
                usesAddon = true
                actualBlocks +=
                    renderWasmJsActualFunction(
                        visibilityPrefix = fn.visibilityPrefix,
                        kotlinName = fn.kotlinName,
                        availPropName = availPropName,
                        wrapperSymbolName = fn.wrapperSymbolName,
                        params = fn.params,
                        kotlinReturnType = fn.kotlinReturnType,
                        returnBridgeKind = fn.returnBridgeKind,
                        returnDefaultExpr = fn.returnDefaultExpr,
                        noStringConvEnabled = inlineNoStrConv,
                        isVariadic = false,
                        pkgName = pkgName,
                        importsOut = imports,
                    )
            } else {
                actualBlocks +=
                    renderUnsupportedActualFunction(
                        visibilityPrefix = fn.visibilityPrefix,
                        kotlinName = fn.kotlinName,
                        params = fn.params,
                        kotlinReturnType = fn.kotlinReturnType,
                        returnBridgeKind = fn.returnBridgeKind,
                        isVariadic = fn.isVariadic,
                        message = "Native FFI addon is not available",
                    )
            }
        }

        if (usesAddon) {
            val relsJs = napiRelativeCandidatesJsArray(ffiModName)
            val initJs =
                "() => { " +
                    "if (typeof globalThis.__km_napi_$ffiModName !== 'undefined') return globalThis.__km_napi_$ffiModName !== null; " +
                    "if (typeof process === 'undefined' || !process.versions || !process.versions.node) { globalThis.__km_napi_$ffiModName = null; return false; } " +
                    "var mod = process.getBuiltinModule ? process.getBuiltinModule('module') : (process.mainModule && process.mainModule.constructor); " +
                    "var req = (mod && mod.createRequire) ? mod.createRequire((process.cwd ? process.cwd() : '.') + '/package.json') : (process.mainModule ? process.mainModule.require : null); " +
                    "if (!req) { globalThis.__km_napi_$ffiModName = null; return false; } " +
                    "try { globalThis.__km_napi_$ffiModName = req('@kotlinmania/$ffiModName-native-bindings'); return true; } catch (_) {} " +
                    "var fs = req('fs'); " +
                    "var path = req('path'); " +
                    "var dir = process.cwd ? process.cwd() : '.'; " +
                    "var rels = $relsJs; " +
                    "for (var d = 0; d < 8; d++) { " +
                    "for (var i = 0; i < rels.length; i++) { " +
                    "var c = path.resolve(dir, rels[i]); " +
                    "if (fs.existsSync(c)) { globalThis.__km_napi_$ffiModName = req(c); return true; } " +
                    "} " +
                    "var parent = path.dirname(dir); " +
                    "if (parent === dir) break; " +
                    "dir = parent; " +
                    "} " +
                    "globalThis.__km_napi_$ffiModName = null; " +
                    "return false; " +
                    "}"
            val loaderBlock =
                "@JsFun(\"$initJs\")\n" +
                    "private external fun init${stemPascal}NapiAddon(): Boolean\n\n" +
                    "private val $availPropName: Boolean = init${stemPascal}NapiAddon()\n\n" +
                    externalDecls.joinToString("\n\n")
            actualBlocks.add(0, loaderBlock)
        }

        val bodyJoined = PhantomRawPointerTypeStripper.strip(actualBlocks.joinToString("\n\n"))
        addRecordedImportsForBody(bodyJoined, pkgName, imports)

        if (imports.isNotEmpty()) {
            for (imp in sortImportsForKtlint(imports)) {
                sb.append("import ").append(imp).append('\n')
            }
            sb.append('\n')
        }
        sb.append(bodyJoined)
        if (actualBlocks.isNotEmpty()) {
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun renderWasmJsStaticActualProperty(
        st: ForeignStaticInteropSpec,
        availPropName: String,
        pkgName: String?,
        importsOut: MutableSet<String>,
    ): String {
        val rawGet = "wasm${toPascalCaseIdentifierPart(st.getterWrapperSymbolName)}()"
        val convertedGet = renderWasmJsReturnConversion(rawGet, st.kotlinType, st.bridgeKind, st.defaultExpr, pkgName, importsOut)
        val getExpr = "if ($availPropName) $convertedGet else throw UnsupportedOperationException(\"Native FFI addon is not available\")"
        return if (st.isMutable) {
            val valParam = InteropParamSpec(kotlinName = "value", kotlinType = st.kotlinType, cName = "value", bridgeKind = st.bridgeKind)
            val setArg = renderJsOrWasmParamConversion(valParam, noStringConvEnabled = true)
            "${st.visibilityPrefix}actual var ${st.kotlinName}: ${st.kotlinType}\n" +
                "    get() = $getExpr\n" +
                "    set(value) {\n" +
                "        if ($availPropName) {\n" +
                "            wasm${toPascalCaseIdentifierPart(st.setterWrapperSymbolName)}($setArg)\n" +
                "        } else {\n" +
                "            throw UnsupportedOperationException(\"Native FFI addon is not available\")\n" +
                "        }\n" +
                "    }"
        } else {
            "${st.visibilityPrefix}actual val ${st.kotlinName}: ${st.kotlinType}\n" +
                "    get() = $getExpr"
        }
    }

    private fun wrapLongAddressForPointerReturn(
        addrExpr: String,
        kotlinReturnType: String,
        returnBridgeKind: CAbiBridgeKind,
        returnDefaultExpr: String,
        pkgName: String?,
        importsOut: MutableSet<String>,
    ): String {
        val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
        val ptrPkg = "$coreNs.ptr"
        val simpleRet = kotlinReturnType.substringAfterLast('.')
        val isConstPtr =
            when {
                simpleRet.startsWith("ConstPtr") -> true
                simpleRet.startsWith("MutPtr") -> false
                returnDefaultExpr.trim().startsWith("ConstPtr(") -> true
                returnDefaultExpr.trim().startsWith("MutPtr(") -> false
                returnBridgeKind == CAbiBridgeKind.CONST_CSTR -> true
                returnBridgeKind == CAbiBridgeKind.MUT_CSTR -> false
                else -> lookupCrossFilePointerAlias(kotlinReturnType)?.first == false
            }
        val wrapperClass = if (isConstPtr) "ConstPtr" else "MutPtr"
        if (pkgName != ptrPkg && wrapperClass !in localDeclaredTypeNames) {
            importsOut += "$ptrPkg.$wrapperClass"
        }
        return "$wrapperClass($addrExpr)"
    }

    private fun renderWasmJsReturnConversion(
        rawCall: String,
        kotlinReturnType: String,
        returnBridgeKind: CAbiBridgeKind,
        returnDefaultExpr: String,
        pkgName: String?,
        importsOut: MutableSet<String>,
    ): String {
        val retNonNullNumeric = if (!kotlinReturnType.endsWith("?")) resolveNumericKind(kotlinReturnType) else null
        val isRawPointerReturn = headerContext.rawAbiMode || kotlinReturnType == "Long" || retNonNullNumeric != null
        return when (returnBridgeKind) {
            CAbiBridgeKind.VOID -> rawCall
            CAbiBridgeKind.BOOL -> rawCall
            CAbiBridgeKind.I8 ->
                if (retNonNullNumeric != null && retNonNullNumeric != NumericKind.I8) {
                    "$rawCall.${conversionMethodForNumericKind(retNonNullNumeric)}()"
                } else {
                    "$rawCall.toByte()"
                }
            CAbiBridgeKind.U8 ->
                if (retNonNullNumeric != null && retNonNullNumeric != NumericKind.U8) {
                    "$rawCall.${conversionMethodForNumericKind(retNonNullNumeric)}()"
                } else {
                    "$rawCall.toUByte()"
                }
            CAbiBridgeKind.I16 ->
                if (retNonNullNumeric != null && retNonNullNumeric != NumericKind.I16) {
                    "$rawCall.${conversionMethodForNumericKind(retNonNullNumeric)}()"
                } else {
                    "$rawCall.toShort()"
                }
            CAbiBridgeKind.U16 ->
                if (retNonNullNumeric != null && retNonNullNumeric != NumericKind.U16) {
                    "$rawCall.${conversionMethodForNumericKind(retNonNullNumeric)}()"
                } else {
                    "$rawCall.toUShort()"
                }
            CAbiBridgeKind.I32 ->
                if (retNonNullNumeric != null && retNonNullNumeric != NumericKind.I32) {
                    "$rawCall.${conversionMethodForNumericKind(retNonNullNumeric)}()"
                } else {
                    rawCall
                }
            CAbiBridgeKind.U32 ->
                if (retNonNullNumeric != null && retNonNullNumeric != NumericKind.U32) {
                    "$rawCall.${conversionMethodForNumericKind(retNonNullNumeric)}()"
                } else {
                    "$rawCall.toUInt()"
                }
            CAbiBridgeKind.I64 ->
                if (retNonNullNumeric != null && retNonNullNumeric != NumericKind.I64) {
                    "$rawCall.toLong().${conversionMethodForNumericKind(retNonNullNumeric)}()"
                } else {
                    "$rawCall.toLong()"
                }
            CAbiBridgeKind.U64 ->
                if (retNonNullNumeric != null && retNonNullNumeric != NumericKind.U64) {
                    "$rawCall.toULong().${conversionMethodForNumericKind(retNonNullNumeric)}()"
                } else {
                    "$rawCall.toULong()"
                }
            CAbiBridgeKind.F32 -> "$rawCall.toFloat()"
            CAbiBridgeKind.F64 -> rawCall
            CAbiBridgeKind.CALLBACK_PTR ->
                if (isRawPointerReturn) "$rawCall.toLong()" else returnDefaultExpr
            CAbiBridgeKind.CONST_CSTR,
            CAbiBridgeKind.MUT_CSTR,
            CAbiBridgeKind.OPAQUE_PTR,
            CAbiBridgeKind.FILE_PTR,
            CAbiBridgeKind.DIR_PTR,
            -> {
                if (isRawPointerReturn) {
                    if (retNonNullNumeric == NumericKind.I64 || kotlinReturnType == "Long" || retNonNullNumeric == null) {
                        "$rawCall.toLong()"
                    } else {
                        "$rawCall.toLong().${conversionMethodForNumericKind(retNonNullNumeric)}()"
                    }
                } else {
                    wrapLongAddressForPointerReturn(
                        addrExpr = "$rawCall.toLong()",
                        kotlinReturnType = kotlinReturnType,
                        returnBridgeKind = returnBridgeKind,
                        returnDefaultExpr = returnDefaultExpr,
                        pkgName = pkgName,
                        importsOut = importsOut,
                    )
                }
            }
            else -> returnDefaultExpr
        }
    }

    private fun renderWasmJsActualFunction(
        visibilityPrefix: String,
        kotlinName: String,
        availPropName: String,
        wrapperSymbolName: String,
        params: List<InteropParamSpec>,
        kotlinReturnType: String,
        returnBridgeKind: CAbiBridgeKind,
        returnDefaultExpr: String,
        noStringConvEnabled: Boolean,
        isVariadic: Boolean,
        pkgName: String?,
        importsOut: MutableSet<String>,
    ): String {
        val baseParams = params.map { "${it.kotlinName}: ${it.kotlinType}" }
        val paramsSig =
            (if (isVariadic) baseParams + "vararg variadicArgs: Long" else baseParams)
                .joinToString(", ")
        val retSuffix = if (returnBridgeKind == CAbiBridgeKind.VOID) "" else ": $kotlinReturnType"
        val baseArgs =
            params.map { param ->
                renderJsOrWasmParamConversion(param, noStringConvEnabled)
            }
        val allArgs =
            if (isVariadic) {
                baseArgs +
                    listOf(
                        "variadicArgs.size.coerceAtMost(4)",
                        "(if (variadicArgs.size > 0) variadicArgs[0] else 0L).toString()",
                        "(if (variadicArgs.size > 1) variadicArgs[1] else 0L).toString()",
                        "(if (variadicArgs.size > 2) variadicArgs[2] else 0L).toString()",
                        "(if (variadicArgs.size > 3) variadicArgs[3] else 0L).toString()",
                    )
            } else {
                baseArgs
            }
        val rawCall = "wasm${toPascalCaseIdentifierPart(wrapperSymbolName)}(${allArgs.joinToString(", ")})"
        val convertedCall =
            renderWasmJsReturnConversion(
                rawCall = rawCall,
                kotlinReturnType = kotlinReturnType,
                returnBridgeKind = returnBridgeKind,
                returnDefaultExpr = returnDefaultExpr,
                pkgName = pkgName,
                importsOut = importsOut,
            )
        return when {
            returnBridgeKind == CAbiBridgeKind.VOID ->
                "${visibilityPrefix}actual fun $kotlinName($paramsSig) {\n" +
                    "    if ($availPropName) {\n" +
                    "        $rawCall\n" +
                    "    } else {\n" +
                    "        throw UnsupportedOperationException(\"Native FFI addon is not available\")\n" +
                    "    }\n" +
                    "}"
            returnBridgeKind == CAbiBridgeKind.NEVER || kotlinReturnType == "Nothing" ->
                "${visibilityPrefix}actual fun $kotlinName($paramsSig): Nothing {\n" +
                    "    if ($availPropName) {\n" +
                    "        $rawCall\n" +
                    "    } else {\n" +
                    "        throw UnsupportedOperationException(\"Native FFI addon is not available\")\n" +
                    "    }\n" +
                    "    error(\"Unreachable noreturn C function $kotlinName\")\n" +
                    "}"
            else ->
                "${visibilityPrefix}actual fun $kotlinName($paramsSig)$retSuffix =\n" +
                    "    if ($availPropName) $convertedCall else throw UnsupportedOperationException(\"Native FFI addon is not available\")"
        }
    }

    private fun renderPointerParamToCPointer(
        param: InteropParamSpec,
        importsOut: MutableSet<String>,
    ): String {
        val ktType = param.kotlinType
        val nonNullNumeric = if (!ktType.endsWith("?")) resolveNumericKind(ktType) else null
        importsOut += "kotlinx.cinterop.ByteVar"
        importsOut += "kotlinx.cinterop.toCPointer"
        if (headerContext.rawAbiMode || ktType == "Long" || nonNullNumeric != null) {
            return if (ktType == "Long" || nonNullNumeric == NumericKind.I64) {
                "${param.kotlinName}.toCPointer<ByteVar>()"
            } else {
                "${param.kotlinName}.toLong().toCPointer<ByteVar>()"
            }
        }
        if (param.bridgeKind == CAbiBridgeKind.CALLBACK_PTR) {
            return "0L.toCPointer<ByteVar>()"
        }
        return "${param.kotlinName}.rawAddress.toCPointer<ByteVar>()"
    }

    private fun renderScalarParamArg(
        param: InteropParamSpec,
        targetKind: NumericKind,
        convMethod: String,
    ): String {
        val paramKind = if (!param.kotlinType.endsWith("?")) resolveNumericKind(param.kotlinType) else null
        return if (paramKind == targetKind) {
            param.kotlinName
        } else {
            "${param.kotlinName}.$convMethod()"
        }
    }

    private fun renderNativeParamConversion(
        param: InteropParamSpec,
        noStringConvEnabled: Boolean,
        importsOut: MutableSet<String>,
    ): String =
        when (param.bridgeKind) {
            CAbiBridgeKind.CONST_CSTR -> {
                when {
                    !noStringConvEnabled -> param.kotlinName
                    param.kotlinType == "String?" -> {
                        importsOut += "kotlinx.cinterop.cstr"
                        "${param.kotlinName}?.cstr"
                    }
                    param.kotlinType == "String" -> {
                        importsOut += "kotlinx.cinterop.cstr"
                        "${param.kotlinName}.cstr"
                    }
                    else -> renderPointerParamToCPointer(param, importsOut)
                }
            }
            CAbiBridgeKind.MUT_CSTR,
            CAbiBridgeKind.OPAQUE_PTR,
            CAbiBridgeKind.FILE_PTR,
            CAbiBridgeKind.DIR_PTR,
            CAbiBridgeKind.CALLBACK_PTR,
            -> renderPointerParamToCPointer(param, importsOut)
            CAbiBridgeKind.I8 -> renderScalarParamArg(param, NumericKind.I8, "toByte")
            CAbiBridgeKind.U8 -> renderScalarParamArg(param, NumericKind.U8, "toUByte")
            CAbiBridgeKind.I16 -> renderScalarParamArg(param, NumericKind.I16, "toShort")
            CAbiBridgeKind.U16 -> renderScalarParamArg(param, NumericKind.U16, "toUShort")
            CAbiBridgeKind.I32 -> renderScalarParamArg(param, NumericKind.I32, "toInt")
            CAbiBridgeKind.U32 -> renderScalarParamArg(param, NumericKind.U32, "toUInt")
            CAbiBridgeKind.I64 -> renderScalarParamArg(param, NumericKind.I64, "toLong")
            CAbiBridgeKind.U64 -> renderScalarParamArg(param, NumericKind.U64, "toULong")
            else -> param.kotlinName
        }

    private fun renderNativeReturnConversion(
        rawCall: String,
        kotlinReturnType: String,
        returnBridgeKind: CAbiBridgeKind,
        returnDefaultExpr: String,
        pkgName: String?,
        importsOut: MutableSet<String>,
    ): String {
        val retNonNullNumeric = if (!kotlinReturnType.endsWith("?")) resolveNumericKind(kotlinReturnType) else null
        val isRawPointerReturn = headerContext.rawAbiMode || kotlinReturnType == "Long" || retNonNullNumeric != null
        return when (returnBridgeKind) {
            CAbiBridgeKind.VOID -> rawCall
            CAbiBridgeKind.CALLBACK_PTR ->
                if (isRawPointerReturn) {
                    importsOut += "kotlinx.cinterop.toLong"
                    if (retNonNullNumeric == NumericKind.I64 || kotlinReturnType == "Long" || retNonNullNumeric == null) {
                        "$rawCall?.toLong() ?: 0L"
                    } else {
                        "($rawCall?.toLong() ?: 0L).${conversionMethodForNumericKind(retNonNullNumeric)}()"
                    }
                } else {
                    returnDefaultExpr
                }
            CAbiBridgeKind.CONST_CSTR,
            CAbiBridgeKind.MUT_CSTR,
            CAbiBridgeKind.OPAQUE_PTR,
            CAbiBridgeKind.FILE_PTR,
            CAbiBridgeKind.DIR_PTR,
            -> {
                importsOut += "kotlinx.cinterop.toLong"
                if (isRawPointerReturn) {
                    if (retNonNullNumeric == NumericKind.I64 || kotlinReturnType == "Long" || retNonNullNumeric == null) {
                        "$rawCall?.toLong() ?: 0L"
                    } else {
                        "($rawCall?.toLong() ?: 0L).${conversionMethodForNumericKind(retNonNullNumeric)}()"
                    }
                } else {
                    wrapLongAddressForPointerReturn(
                        addrExpr = "$rawCall?.toLong() ?: 0L",
                        kotlinReturnType = kotlinReturnType,
                        returnBridgeKind = returnBridgeKind,
                        returnDefaultExpr = returnDefaultExpr,
                        pkgName = pkgName,
                        importsOut = importsOut,
                    )
                }
            }
            CAbiBridgeKind.I8 ->
                if (retNonNullNumeric != null && retNonNullNumeric != NumericKind.I8) {
                    "$rawCall.${conversionMethodForNumericKind(retNonNullNumeric)}()"
                } else {
                    rawCall
                }
            CAbiBridgeKind.U8 ->
                if (retNonNullNumeric != null && retNonNullNumeric != NumericKind.U8) {
                    "$rawCall.${conversionMethodForNumericKind(retNonNullNumeric)}()"
                } else {
                    rawCall
                }
            CAbiBridgeKind.I16 ->
                if (retNonNullNumeric != null && retNonNullNumeric != NumericKind.I16) {
                    "$rawCall.${conversionMethodForNumericKind(retNonNullNumeric)}()"
                } else {
                    rawCall
                }
            CAbiBridgeKind.U16 ->
                if (retNonNullNumeric != null && retNonNullNumeric != NumericKind.U16) {
                    "$rawCall.${conversionMethodForNumericKind(retNonNullNumeric)}()"
                } else {
                    rawCall
                }
            CAbiBridgeKind.I32 ->
                if (retNonNullNumeric != null && retNonNullNumeric != NumericKind.I32) {
                    "$rawCall.${conversionMethodForNumericKind(retNonNullNumeric)}()"
                } else {
                    rawCall
                }
            CAbiBridgeKind.U32 ->
                if (retNonNullNumeric != null && retNonNullNumeric != NumericKind.U32) {
                    "$rawCall.${conversionMethodForNumericKind(retNonNullNumeric)}()"
                } else {
                    rawCall
                }
            CAbiBridgeKind.I64 ->
                if (retNonNullNumeric != null && retNonNullNumeric != NumericKind.I64) {
                    "$rawCall.${conversionMethodForNumericKind(retNonNullNumeric)}()"
                } else {
                    rawCall
                }
            CAbiBridgeKind.U64 ->
                if (retNonNullNumeric != null && retNonNullNumeric != NumericKind.U64) {
                    "$rawCall.${conversionMethodForNumericKind(retNonNullNumeric)}()"
                } else {
                    rawCall
                }
            else -> rawCall
        }
    }

    private fun renderNativeActualFunction(
        visibilityPrefix: String,
        kotlinName: String,
        cinteropCallee: String,
        params: List<InteropParamSpec>,
        kotlinReturnType: String,
        returnBridgeKind: CAbiBridgeKind,
        returnDefaultExpr: String,
        noStringConvEnabled: Boolean,
        isVariadic: Boolean,
        pkgName: String?,
        importsOut: MutableSet<String>,
    ): String {
        val baseParams = params.map { "${it.kotlinName}: ${it.kotlinType}" }
        val paramsSig =
            (if (isVariadic) baseParams + "vararg variadicArgs: Long" else baseParams)
                .joinToString(", ")
        val retSuffix = if (returnBridgeKind == CAbiBridgeKind.VOID) "" else ": $kotlinReturnType"
        val baseArgs =
            params.map { param ->
                renderNativeParamConversion(param, noStringConvEnabled, importsOut)
            }
        val allArgs =
            if (isVariadic) {
                baseArgs +
                    listOf(
                        "variadicArgs.size.coerceAtMost(4)",
                        "if (variadicArgs.size > 0) variadicArgs[0] else 0L",
                        "if (variadicArgs.size > 1) variadicArgs[1] else 0L",
                        "if (variadicArgs.size > 2) variadicArgs[2] else 0L",
                        "if (variadicArgs.size > 3) variadicArgs[3] else 0L",
                    )
            } else {
                baseArgs
            }
        val rawCall = "$cinteropCallee(${allArgs.joinToString(", ")})"
        val bodyExpr = renderNativeReturnConversion(rawCall, kotlinReturnType, returnBridgeKind, returnDefaultExpr, pkgName, importsOut)
        return when {
            returnBridgeKind == CAbiBridgeKind.VOID ->
                "${visibilityPrefix}actual fun $kotlinName($paramsSig) {\n    $bodyExpr\n}"
            returnBridgeKind == CAbiBridgeKind.NEVER ->
                "${visibilityPrefix}actual fun $kotlinName($paramsSig): Nothing {\n    $rawCall\n    error(\"Unreachable noreturn C function $kotlinName\")\n}"
            else ->
                "${visibilityPrefix}actual fun $kotlinName($paramsSig)$retSuffix =\n    $bodyExpr"
        }
    }

    private fun jniKotlinTypeForParam(
        param: InteropParamSpec,
        noStringConvEnabled: Boolean,
        ktStringType: String = "String",
    ): String =
        when (param.bridgeKind) {
            CAbiBridgeKind.VOID, CAbiBridgeKind.NEVER -> "Unit"
            CAbiBridgeKind.BOOL -> "Boolean"
            CAbiBridgeKind.I8, CAbiBridgeKind.U8 -> "Byte"
            CAbiBridgeKind.I16, CAbiBridgeKind.U16 -> "Short"
            CAbiBridgeKind.I32, CAbiBridgeKind.U32 -> "Int"
            CAbiBridgeKind.I64, CAbiBridgeKind.U64 -> "Long"
            CAbiBridgeKind.F32 -> "Float"
            CAbiBridgeKind.F64 -> "Double"
            CAbiBridgeKind.CONST_CSTR ->
                if (!noStringConvEnabled && (param.kotlinType == "String?" || param.kotlinType == "String")) {
                    "$ktStringType?"
                } else {
                    "Long"
                }
            CAbiBridgeKind.MUT_CSTR,
            CAbiBridgeKind.OPAQUE_PTR,
            CAbiBridgeKind.FILE_PTR,
            CAbiBridgeKind.DIR_PTR,
            CAbiBridgeKind.CALLBACK_PTR,
            CAbiBridgeKind.BY_VALUE_OBJECT,
            -> "Long"
        }

    private fun jniKotlinTypeForReturn(returnBridgeKind: CAbiBridgeKind): String =
        when (returnBridgeKind) {
            CAbiBridgeKind.VOID, CAbiBridgeKind.NEVER -> "Unit"
            CAbiBridgeKind.BOOL -> "Boolean"
            CAbiBridgeKind.I8, CAbiBridgeKind.U8 -> "Byte"
            CAbiBridgeKind.I16, CAbiBridgeKind.U16 -> "Short"
            CAbiBridgeKind.I32, CAbiBridgeKind.U32 -> "Int"
            CAbiBridgeKind.I64, CAbiBridgeKind.U64 -> "Long"
            CAbiBridgeKind.F32 -> "Float"
            CAbiBridgeKind.F64 -> "Double"
            CAbiBridgeKind.CONST_CSTR,
            CAbiBridgeKind.MUT_CSTR,
            CAbiBridgeKind.OPAQUE_PTR,
            CAbiBridgeKind.FILE_PTR,
            CAbiBridgeKind.DIR_PTR,
            CAbiBridgeKind.CALLBACK_PTR,
            CAbiBridgeKind.BY_VALUE_OBJECT,
            -> "Long"
        }

    private fun renderPointerParamToLong(param: InteropParamSpec): String {
        val ktType = param.kotlinType
        val nonNullNumeric = if (!ktType.endsWith("?")) resolveNumericKind(ktType) else null
        if (headerContext.rawAbiMode || ktType == "Long" || nonNullNumeric != null) {
            return if (ktType == "Long" || nonNullNumeric == NumericKind.I64) {
                param.kotlinName
            } else {
                "${param.kotlinName}.toLong()"
            }
        }
        if (param.bridgeKind == CAbiBridgeKind.CALLBACK_PTR) {
            return "0L"
        }
        return "${param.kotlinName}.rawAddress"
    }

    private fun renderJvmParamConversion(
        param: InteropParamSpec,
        noStringConvEnabled: Boolean,
    ): String =
        when (param.bridgeKind) {
            CAbiBridgeKind.CONST_CSTR -> {
                if (!noStringConvEnabled && (param.kotlinType == "String?" || param.kotlinType == "String")) {
                    param.kotlinName
                } else {
                    renderPointerParamToLong(param)
                }
            }
            CAbiBridgeKind.MUT_CSTR,
            CAbiBridgeKind.OPAQUE_PTR,
            CAbiBridgeKind.FILE_PTR,
            CAbiBridgeKind.DIR_PTR,
            CAbiBridgeKind.CALLBACK_PTR,
            -> renderPointerParamToLong(param)
            CAbiBridgeKind.I8, CAbiBridgeKind.U8 -> renderScalarParamArg(param, NumericKind.I8, "toByte")
            CAbiBridgeKind.I16, CAbiBridgeKind.U16 -> renderScalarParamArg(param, NumericKind.I16, "toShort")
            CAbiBridgeKind.I32, CAbiBridgeKind.U32 -> renderScalarParamArg(param, NumericKind.I32, "toInt")
            CAbiBridgeKind.I64, CAbiBridgeKind.U64 -> renderScalarParamArg(param, NumericKind.I64, "toLong")
            CAbiBridgeKind.F32 -> renderScalarParamArg(param, NumericKind.F32, "toFloat")
            CAbiBridgeKind.F64 -> renderScalarParamArg(param, NumericKind.F64, "toDouble")
            else -> param.kotlinName
        }

    private fun renderJvmReturnConversion(
        rawCall: String,
        kotlinReturnType: String,
        returnBridgeKind: CAbiBridgeKind,
        returnDefaultExpr: String,
        pkgName: String?,
        importsOut: MutableSet<String>,
    ): String {
        val retNonNullNumeric = if (!kotlinReturnType.endsWith("?")) resolveNumericKind(kotlinReturnType) else null
        val isRawPointerReturn = headerContext.rawAbiMode || kotlinReturnType == "Long" || retNonNullNumeric != null
        return when (returnBridgeKind) {
            CAbiBridgeKind.VOID -> rawCall
            CAbiBridgeKind.CALLBACK_PTR ->
                if (isRawPointerReturn) rawCall else returnDefaultExpr
            CAbiBridgeKind.CONST_CSTR,
            CAbiBridgeKind.MUT_CSTR,
            CAbiBridgeKind.OPAQUE_PTR,
            CAbiBridgeKind.FILE_PTR,
            CAbiBridgeKind.DIR_PTR,
            -> {
                if (isRawPointerReturn) {
                    if (retNonNullNumeric == NumericKind.I64 || kotlinReturnType == "Long" || retNonNullNumeric == null) {
                        rawCall
                    } else {
                        "$rawCall.${conversionMethodForNumericKind(retNonNullNumeric)}()"
                    }
                } else {
                    wrapLongAddressForPointerReturn(
                        addrExpr = rawCall,
                        kotlinReturnType = kotlinReturnType,
                        returnBridgeKind = returnBridgeKind,
                        returnDefaultExpr = returnDefaultExpr,
                        pkgName = pkgName,
                        importsOut = importsOut,
                    )
                }
            }
            CAbiBridgeKind.I8 ->
                if (retNonNullNumeric != null && retNonNullNumeric != NumericKind.I8) {
                    "$rawCall.${conversionMethodForNumericKind(retNonNullNumeric)}()"
                } else {
                    rawCall
                }
            CAbiBridgeKind.U8 ->
                if (retNonNullNumeric != null && retNonNullNumeric != NumericKind.U8) {
                    "$rawCall.${conversionMethodForNumericKind(retNonNullNumeric)}()"
                } else {
                    "$rawCall.toUByte()"
                }
            CAbiBridgeKind.I16 ->
                if (retNonNullNumeric != null && retNonNullNumeric != NumericKind.I16) {
                    "$rawCall.${conversionMethodForNumericKind(retNonNullNumeric)}()"
                } else {
                    rawCall
                }
            CAbiBridgeKind.U16 ->
                if (retNonNullNumeric != null && retNonNullNumeric != NumericKind.U16) {
                    "$rawCall.${conversionMethodForNumericKind(retNonNullNumeric)}()"
                } else {
                    "$rawCall.toUShort()"
                }
            CAbiBridgeKind.I32 ->
                if (retNonNullNumeric != null && retNonNullNumeric != NumericKind.I32) {
                    "$rawCall.${conversionMethodForNumericKind(retNonNullNumeric)}()"
                } else {
                    rawCall
                }
            CAbiBridgeKind.U32 ->
                if (retNonNullNumeric != null && retNonNullNumeric != NumericKind.U32) {
                    "$rawCall.${conversionMethodForNumericKind(retNonNullNumeric)}()"
                } else {
                    "$rawCall.toUInt()"
                }
            CAbiBridgeKind.I64 ->
                if (retNonNullNumeric != null && retNonNullNumeric != NumericKind.I64) {
                    "$rawCall.${conversionMethodForNumericKind(retNonNullNumeric)}()"
                } else {
                    rawCall
                }
            CAbiBridgeKind.U64 ->
                if (retNonNullNumeric != null && retNonNullNumeric != NumericKind.U64) {
                    "$rawCall.${conversionMethodForNumericKind(retNonNullNumeric)}()"
                } else {
                    "$rawCall.toULong()"
                }
            else -> rawCall
        }
    }

    private fun renderJvmStaticActualProperty(
        st: ForeignStaticInteropSpec,
        jniObjectName: String,
        pkgName: String?,
        importsOut: MutableSet<String>,
    ): String {
        val rawGet = "$jniObjectName.${st.getterWrapperSymbolName}()"
        val getBody = renderJvmReturnConversion(rawGet, st.kotlinType, st.bridgeKind, st.defaultExpr, pkgName, importsOut)
        return if (st.isMutable) {
            val valParam = InteropParamSpec(kotlinName = "value", kotlinType = st.kotlinType, cName = "value", bridgeKind = st.bridgeKind)
            val setArg = renderJvmParamConversion(valParam, noStringConvEnabled = true)
            "${st.visibilityPrefix}actual var ${st.kotlinName}: ${st.kotlinType}\n" +
                "    get() = $getBody\n" +
                "    set(value) {\n" +
                "        $jniObjectName.${st.setterWrapperSymbolName}($setArg)\n" +
                "    }"
        } else {
            "${st.visibilityPrefix}actual val ${st.kotlinName}: ${st.kotlinType}\n" +
                "    get() = $getBody"
        }
    }

    private fun renderJvmActualFunction(
        visibilityPrefix: String,
        kotlinName: String,
        jniCallee: String,
        params: List<InteropParamSpec>,
        kotlinReturnType: String,
        returnBridgeKind: CAbiBridgeKind,
        returnDefaultExpr: String,
        noStringConvEnabled: Boolean,
        isVariadic: Boolean,
        pkgName: String?,
        importsOut: MutableSet<String>,
    ): String {
        val baseParams = params.map { "${it.kotlinName}: ${it.kotlinType}" }
        val paramsSig =
            (if (isVariadic) baseParams + "vararg variadicArgs: Long" else baseParams)
                .joinToString(", ")
        val retSuffix = if (returnBridgeKind == CAbiBridgeKind.VOID) "" else ": $kotlinReturnType"
        val baseArgs =
            params.map { param ->
                renderJvmParamConversion(param, noStringConvEnabled)
            }
        val allArgs =
            if (isVariadic) {
                baseArgs +
                    listOf(
                        "variadicArgs.size.coerceAtMost(4)",
                        "if (variadicArgs.size > 0) variadicArgs[0] else 0L",
                        "if (variadicArgs.size > 1) variadicArgs[1] else 0L",
                        "if (variadicArgs.size > 2) variadicArgs[2] else 0L",
                        "if (variadicArgs.size > 3) variadicArgs[3] else 0L",
                    )
            } else {
                baseArgs
            }
        val rawCall = "$jniCallee(${allArgs.joinToString(", ")})"
        val bodyExpr =
            renderJvmReturnConversion(
                rawCall = rawCall,
                kotlinReturnType = kotlinReturnType,
                returnBridgeKind = returnBridgeKind,
                returnDefaultExpr = returnDefaultExpr,
                pkgName = pkgName,
                importsOut = importsOut,
            )
        return when {
            returnBridgeKind == CAbiBridgeKind.VOID ->
                "${visibilityPrefix}actual fun $kotlinName($paramsSig) {\n    $bodyExpr\n}"
            returnBridgeKind == CAbiBridgeKind.NEVER ->
                "${visibilityPrefix}actual fun $kotlinName($paramsSig): Nothing {\n    $rawCall\n    error(\"Unreachable noreturn C function $kotlinName\")\n}"
            else ->
                "${visibilityPrefix}actual fun $kotlinName($paramsSig)$retSuffix =\n    $bodyExpr"
        }
    }

    private fun buildJvmOrAndroidActualsKotlinContent(
        pkgName: String?,
        jniObjectName: String,
        jniLibName: String,
        includeJarResourceLoader: Boolean = true,
    ): String {
        val sb = StringBuilder()
        sb.append("@file:OptIn(ExperimentalUnsignedTypes::class)\n\n")
        if (!pkgName.isNullOrBlank()) {
            sb.append("package ").append(pkgName).append("\n\n")
        }
        val imports = linkedSetOf<String>()
        val actualBlocks = mutableListOf<String>()
        val jniExternalDecls = mutableListOf<String>()
        val emittedJniWrappers = linkedSetOf<String>()
        val emittedActuals = linkedSetOf<String>()
        val ktString = if (shadowsKotlinString(pkgName)) "kotlin.String" else "String"

        for (st in foreignStatics) {
            if (!emittedActuals.add("val:${st.kotlinName}")) continue
            if (isBridgeableStaticInCWrapper(st)) {
                if (emittedJniWrappers.add(st.getterWrapperSymbolName)) {
                    val jniRet = jniKotlinTypeForReturn(st.bridgeKind)
                    jniExternalDecls += "    @JvmStatic\n    external fun ${st.getterWrapperSymbolName}(): $jniRet"
                }
                if (st.isMutable && emittedJniWrappers.add(st.setterWrapperSymbolName)) {
                    val valParam = InteropParamSpec(kotlinName = "value", kotlinType = st.kotlinType, cName = "value", bridgeKind = st.bridgeKind)
                    val jniValType = jniKotlinTypeForParam(valParam, noStringConvEnabled = true, ktStringType = ktString)
                    jniExternalDecls += "    @JvmStatic\n    external fun ${st.setterWrapperSymbolName}(value: $jniValType)"
                }
                actualBlocks += renderJvmStaticActualProperty(st, jniObjectName, pkgName, imports)
            } else {
                actualBlocks += renderStaticActualProperty(st)
            }
        }
        for (fn in foreignFunctions) {
            val noStrConv = requiresNoStringConversion(fn.params)
            if (isBridgeableInCWrapper(fn) && emittedJniWrappers.add(fn.wrapperSymbolName)) {
                val baseJniParams = fn.params.map { "${it.kotlinName}: ${jniKotlinTypeForParam(it, noStrConv, ktString)}" }
                val allJniParams =
                    if (fn.isVariadic) {
                        baseJniParams + listOf("kmVac: Int", "kmVa0: Long", "kmVa1: Long", "kmVa2: Long", "kmVa3: Long")
                    } else {
                        baseJniParams
                    }
                val jniParams = allJniParams.joinToString(", ")
                val jniRet = jniKotlinTypeForReturn(fn.returnBridgeKind)
                val jniRetSuffix = if (jniRet == "Unit") "" else ": $jniRet"
                jniExternalDecls += "    @JvmStatic\n    external fun ${fn.wrapperSymbolName}($jniParams)$jniRetSuffix"
            }
            if (!emittedActuals.add("fn:${fn.kotlinName}:${fn.params.size}")) continue
            actualBlocks +=
                if (isBridgeableInCWrapper(fn)) {
                    renderJvmActualFunction(
                        visibilityPrefix = fn.visibilityPrefix,
                        kotlinName = fn.kotlinName,
                        jniCallee = "$jniObjectName.${fn.wrapperSymbolName}",
                        params = fn.params,
                        kotlinReturnType = fn.kotlinReturnType,
                        returnBridgeKind = fn.returnBridgeKind,
                        returnDefaultExpr = fn.returnDefaultExpr,
                        noStringConvEnabled = noStrConv,
                        isVariadic = fn.isVariadic,
                        pkgName = pkgName,
                        importsOut = imports,
                    )
                } else {
                    renderFallbackActualFunction(
                        visibilityPrefix = fn.visibilityPrefix,
                        kotlinName = fn.kotlinName,
                        params = fn.params,
                        kotlinReturnType = fn.kotlinReturnType,
                        returnBridgeKind = fn.returnBridgeKind,
                        returnDefaultExpr = fn.returnDefaultExpr,
                        isVariadic = fn.isVariadic,
                    )
                }
        }
        for (fn in inlineFunctions) {
            val inlineNoStrConv =
                requiresNoStringConversion(fn.params) ||
                    fn.params.any { it.bridgeKind == CAbiBridgeKind.MUT_CSTR }
            if (isBridgeableInlineInCWrapper(fn) && emittedJniWrappers.add(fn.wrapperSymbolName)) {
                val jniParams = fn.params.joinToString(", ") { "${it.kotlinName}: ${jniKotlinTypeForParam(it, inlineNoStrConv, ktString)}" }
                val jniRet = jniKotlinTypeForReturn(fn.returnBridgeKind)
                val jniRetSuffix = if (jniRet == "Unit") "" else ": $jniRet"
                jniExternalDecls += "    @JvmStatic\n    external fun ${fn.wrapperSymbolName}($jniParams)$jniRetSuffix"
            }
            if (!emittedActuals.add("fn:${fn.kotlinName}:${fn.params.size}")) continue
            actualBlocks +=
                if (isBridgeableInlineInCWrapper(fn)) {
                    renderJvmActualFunction(
                        visibilityPrefix = fn.visibilityPrefix,
                        kotlinName = fn.kotlinName,
                        jniCallee = "$jniObjectName.${fn.wrapperSymbolName}",
                        params = fn.params,
                        kotlinReturnType = fn.kotlinReturnType,
                        returnBridgeKind = fn.returnBridgeKind,
                        returnDefaultExpr = fn.returnDefaultExpr,
                        noStringConvEnabled = inlineNoStrConv,
                        isVariadic = false,
                        pkgName = pkgName,
                        importsOut = imports,
                    )
                } else {
                    renderFallbackActualFunction(
                        visibilityPrefix = fn.visibilityPrefix,
                        kotlinName = fn.kotlinName,
                        params = fn.params,
                        kotlinReturnType = fn.kotlinReturnType,
                        returnBridgeKind = fn.returnBridgeKind,
                        returnDefaultExpr = fn.returnDefaultExpr,
                        isVariadic = fn.isVariadic,
                    )
                }
        }

        if (jniExternalDecls.isNotEmpty()) {
            val projNs = (
                headerContext.projectNamespace
                    ?.trim()
                    ?.removeSuffix(".")
                    ?.takeIf { it.isNotEmpty() } ?: pkgName.orEmpty()
            )
            val resourcePrefix = projNs.replace('.', '/')
            val jniObjBlock =
                buildString {
                    append("private object ").append(jniObjectName).append(" {\n")
                    append("    init {\n")
                    if (includeJarResourceLoader && resourcePrefix.isNotEmpty()) {
                        append("        loadNativeLibrary()\n")
                    } else {
                        append("        System.loadLibrary(\"").append(jniLibName).append("\")\n")
                    }
                    append("    }\n\n")
                    if (includeJarResourceLoader && resourcePrefix.isNotEmpty()) {
                        append("    private fun loadNativeLibrary() {\n")
                        append("        val osRaw = System.getProperty(\"os.name\", \"\").lowercase()\n")
                        append("        val archRaw = System.getProperty(\"os.arch\", \"\").lowercase()\n")
                        append("        val hostPlatform =\n")
                        append("            when {\n")
                        append("                osRaw.contains(\"win\") -> \"windows\"\n")
                        append("                osRaw.contains(\"mac\") || osRaw.contains(\"darwin\") -> \"macos\"\n")
                        append("                else -> \"linux\"\n")
                        append("            }\n")
                        append("        val hostArch =\n")
                        append("            when {\n")
                        append("                archRaw == \"aarch64\" || archRaw == \"arm64\" -> \"aarch64\"\n")
                        append("                archRaw == \"amd64\" || archRaw == \"x86_64\" -> \"x86_64\"\n")
                        append("                else -> archRaw\n")
                        append("            }\n")
                        append("        val libFileName =\n")
                        append("            when (hostPlatform) {\n")
                        append("                \"windows\" -> \"").append(jniLibName).append(".dll\"\n")
                        append("                \"macos\" -> \"lib").append(jniLibName).append(".dylib\"\n")
                        append("                else -> \"lib").append(jniLibName).append(".so\"\n")
                        append("            }\n")
                        append("        val pathSep = System.getProperty(\"path.separator\", \":\")\n")
                        append("        val libPathProp = System.getProperty(\"java.library.path\", \"\")\n")
                        append("        for (dirEntry in libPathProp.split(pathSep)) {\n")
                        append("            if (dirEntry.isEmpty()) continue\n")
                        append("            val candidate = java.io.File(dirEntry, libFileName)\n")
                        append("            if (candidate.isFile) {\n")
                        append("                System.load(candidate.absolutePath)\n")
                        append("                return\n")
                        append("            }\n")
                        append("        }\n")
                        append("        val resourcePath = \"").append(resourcePrefix).append("/native/${'$'}hostPlatform-${'$'}hostArch/${'$'}libFileName\"\n")
                        append("        val stream = ").append(jniObjectName).append("::class.java.classLoader?.getResourceAsStream(resourcePath)\n")
                        append("        if (stream != null) {\n")
                        append("            val suffix = \".\" + libFileName.substringAfterLast('.')\n")
                        append("            val extracted = java.nio.file.Files.createTempFile(\"").append(jniLibName).append("-\", suffix).toFile()\n")
                        append("            extracted.deleteOnExit()\n")
                        append("            stream.use { input ->\n")
                        append("                extracted.outputStream().use { output ->\n")
                        append("                    input.copyTo(output)\n")
                        append("                }\n")
                        append("            }\n")
                        append("            System.load(extracted.absolutePath)\n")
                        append("            return\n")
                        append("        }\n")
                        append("        System.loadLibrary(\"").append(jniLibName).append("\")\n")
                        append("    }\n\n")
                    }
                    append(jniExternalDecls.joinToString("\n\n")).append('\n')
                    append("}")
                }
            actualBlocks.add(0, jniObjBlock)
        }

        val bodyJoined = PhantomRawPointerTypeStripper.strip(actualBlocks.joinToString("\n\n"))
        addRecordedImportsForBody(bodyJoined, pkgName, imports)

        if (imports.isNotEmpty()) {
            for (imp in sortImportsForKtlint(imports)) {
                sb.append("import ").append(imp).append('\n')
            }
            sb.append('\n')
        }
        sb.append(bodyJoined)
        if (actualBlocks.isNotEmpty()) {
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun jniCTypeForParam(param: InteropParamSpec, noStringConvEnabled: Boolean): String =
        when (param.bridgeKind) {
            CAbiBridgeKind.VOID, CAbiBridgeKind.NEVER -> "void"
            CAbiBridgeKind.BOOL -> "jboolean"
            CAbiBridgeKind.I8, CAbiBridgeKind.U8 -> "jbyte"
            CAbiBridgeKind.I16, CAbiBridgeKind.U16 -> "jshort"
            CAbiBridgeKind.I32, CAbiBridgeKind.U32 -> "jint"
            CAbiBridgeKind.I64, CAbiBridgeKind.U64 -> "jlong"
            CAbiBridgeKind.F32 -> "jfloat"
            CAbiBridgeKind.F64 -> "jdouble"
            CAbiBridgeKind.CONST_CSTR ->
                if (!noStringConvEnabled && (param.kotlinType == "String?" || param.kotlinType == "String")) {
                    "jstring"
                } else {
                    "jlong"
                }
            CAbiBridgeKind.MUT_CSTR,
            CAbiBridgeKind.OPAQUE_PTR,
            CAbiBridgeKind.FILE_PTR,
            CAbiBridgeKind.DIR_PTR,
            CAbiBridgeKind.CALLBACK_PTR,
            CAbiBridgeKind.BY_VALUE_OBJECT,
            -> "jlong"
        }

    private fun jniCTypeForReturn(returnBridgeKind: CAbiBridgeKind): String =
        when (returnBridgeKind) {
            CAbiBridgeKind.VOID, CAbiBridgeKind.NEVER -> "void"
            CAbiBridgeKind.BOOL -> "jboolean"
            CAbiBridgeKind.I8, CAbiBridgeKind.U8 -> "jbyte"
            CAbiBridgeKind.I16, CAbiBridgeKind.U16 -> "jshort"
            CAbiBridgeKind.I32, CAbiBridgeKind.U32 -> "jint"
            CAbiBridgeKind.I64, CAbiBridgeKind.U64 -> "jlong"
            CAbiBridgeKind.F32 -> "jfloat"
            CAbiBridgeKind.F64 -> "jdouble"
            CAbiBridgeKind.CONST_CSTR,
            CAbiBridgeKind.MUT_CSTR,
            CAbiBridgeKind.OPAQUE_PTR,
            CAbiBridgeKind.FILE_PTR,
            CAbiBridgeKind.DIR_PTR,
            CAbiBridgeKind.CALLBACK_PTR,
            CAbiBridgeKind.BY_VALUE_OBJECT,
            -> "jlong"
        }

    private fun buildJniCSourceContent(
        pkgName: String?,
        jniObjectName: String,
    ): String {
        val sb = StringBuilder()
        sb.append("#ifndef _GNU_SOURCE\n#define _GNU_SOURCE\n#endif\n")
        sb.append("#include <stdbool.h>\n")
        sb.append("#include <stdint.h>\n")
        sb.append("#if defined(__has_include)\n")
        sb.append("#  if __has_include(<jni.h>)\n")
        sb.append("#    include <jni.h>\n")
        sb.append("#    define KM_HAS_JNI_H 1\n")
        sb.append("#  endif\n")
        sb.append("#endif\n")
        sb.append("#ifndef KM_HAS_JNI_H\n")
        sb.append("typedef uint8_t jboolean;\n")
        sb.append("typedef int8_t jbyte;\n")
        sb.append("typedef uint16_t jchar;\n")
        sb.append("typedef int16_t jshort;\n")
        sb.append("typedef int32_t jint;\n")
        sb.append("typedef int64_t jlong;\n")
        sb.append("typedef float jfloat;\n")
        sb.append("typedef double jdouble;\n")
        sb.append("typedef jint jsize;\n")
        sb.append("typedef void* jobject;\n")
        sb.append("typedef jobject jclass;\n")
        sb.append("typedef jobject jstring;\n")
        sb.append("typedef jobject jthrowable;\n")
        sb.append("typedef jobject jarray;\n")
        sb.append("typedef jarray jbyteArray;\n")
        sb.append("struct JNINativeInterface_;\n")
        sb.append("typedef const struct JNINativeInterface_* JNIEnv;\n")
        sb.append("struct JNINativeInterface_ {\n")
        sb.append("    const char* (*GetStringUTFChars)(JNIEnv*, jstring, jboolean*);\n")
        sb.append("    void (*ReleaseStringUTFChars)(JNIEnv*, jstring, const char*);\n")
        sb.append("};\n")
        sb.append("#ifdef _WIN32\n")
        sb.append("#  define JNIEXPORT __declspec(dllexport)\n")
        sb.append("#  define JNICALL __stdcall\n")
        sb.append("#else\n")
        sb.append("#  define JNIEXPORT __attribute__((visibility(\"default\")))\n")
        sb.append("#  define JNICALL\n")
        sb.append("#endif\n")
        sb.append("#endif\n")
        sb.append("#include <stddef.h>\n")
        sb.append("#include <stdint.h>\n")
        sb.append("#include <stdlib.h>\n")
        sb.append("#include <errno.h>\n")
        sb.append("#ifdef _WIN32\n")
        sb.append("#ifndef WIN32_LEAN_AND_MEAN\n#define WIN32_LEAN_AND_MEAN\n#endif\n")
        sb.append("#include <windows.h>\n")
        sb.append("#else\n")
        sb.append("#include <dlfcn.h>\n")
        sb.append("#ifndef RTLD_DEFAULT\n#define RTLD_DEFAULT ((void*)0)\n#endif\n")
        sb.append("#endif\n\n")

        if (preambles.isNotEmpty() && !usesCppLanguage) {
            for (chunk in preambles) {
                sb.append(chunk).append("\n\n")
            }
        }

        val fullClassFqName = if (pkgName.isNullOrBlank()) jniObjectName else "$pkgName.$jniObjectName"
        val jniClassMangled = fullClassFqName.split('.').joinToString("_") { jniMangleSegment(it) }
        val emittedJni = linkedSetOf<String>()

        for (st in foreignStatics) {
            if (!isBridgeableStaticInCWrapper(st)) continue
            if (emittedJni.add(st.getterWrapperSymbolName)) {
                sb.append(renderJniStaticGetterFunction(st, jniClassMangled)).append('\n')
            }
            if (st.isMutable && emittedJni.add(st.setterWrapperSymbolName)) {
                sb.append(renderJniStaticSetterFunction(st, jniClassMangled)).append('\n')
            }
        }
        for (fn in foreignFunctions) {
            if (!isBridgeableInCWrapper(fn)) continue
            if (!emittedJni.add(fn.wrapperSymbolName)) continue
            sb.append(renderJniForeignFunction(fn, jniClassMangled)).append('\n')
        }
        for (fn in inlineFunctions) {
            if (!isBridgeableInlineInCWrapper(fn)) continue
            if (!emittedJni.add(fn.wrapperSymbolName)) continue
            sb.append(renderJniInlineFunction(fn, jniClassMangled)).append('\n')
        }
        return sb.toString()
    }

    private fun renderJniStaticGetterFunction(
        st: ForeignStaticInteropSpec,
        jniClassMangled: String,
    ): String {
        val sb = StringBuilder()
        val jniRet = jniCTypeForReturn(st.bridgeKind)
        val jniSymbol = "Java_${jniClassMangled}_${jniMangleSegment(st.getterWrapperSymbolName)}"
        val cRet = cHeaderTypeForBridge(st.bridgeKind)
        val symEsc = escapeCString(st.cSymbolName)
        val defaultLit = cDefaultFailureLiteral(st.bridgeKind)

        sb.append("JNIEXPORT $jniRet JNICALL $jniSymbol(JNIEnv* km_env, jclass km_clazz) {\n")
        sb.append("    (void)km_env;\n")
        sb.append("    (void)km_clazz;\n")
        sb.append("    static $cRet* km_sym_ptr = NULL;\n")
        sb.append("#ifdef _WIN32\n")
        sb.append("    if (km_sym_ptr == NULL) {\n")
        sb.append("        HMODULE km_h_ucrt = GetModuleHandleA(\"ucrtbase.dll\");\n")
        sb.append("        if (km_h_ucrt == NULL) km_h_ucrt = LoadLibraryA(\"ucrtbase.dll\");\n")
        sb.append("        if (km_h_ucrt != NULL) km_sym_ptr = ($cRet*)(void*)GetProcAddress(km_h_ucrt, \"$symEsc\");\n")
        if (!st.cSymbolName.startsWith("_")) {
            sb.append("        if (km_sym_ptr == NULL && km_h_ucrt != NULL) km_sym_ptr = ($cRet*)(void*)GetProcAddress(km_h_ucrt, \"_$symEsc\");\n")
        }
        sb.append("        if (km_sym_ptr == NULL) {\n")
        sb.append("            HMODULE km_h_msvcrt = GetModuleHandleA(\"msvcrt.dll\");\n")
        sb.append("            if (km_h_msvcrt == NULL) km_h_msvcrt = LoadLibraryA(\"msvcrt.dll\");\n")
        sb.append("            if (km_h_msvcrt != NULL) km_sym_ptr = ($cRet*)(void*)GetProcAddress(km_h_msvcrt, \"$symEsc\");\n")
        if (!st.cSymbolName.startsWith("_")) {
            sb.append("            if (km_sym_ptr == NULL && km_h_msvcrt != NULL) km_sym_ptr = ($cRet*)(void*)GetProcAddress(km_h_msvcrt, \"_$symEsc\");\n")
        }
        sb.append("        }\n")
        sb.append("    }\n")
        sb.append("#else\n")
        sb.append("    if (km_sym_ptr == NULL) {\n")
        sb.append("        km_sym_ptr = ($cRet*)dlsym(RTLD_DEFAULT, \"$symEsc\");\n")
        sb.append("    }\n")
        sb.append("#endif\n")
        sb.append("    $cRet km_res = (km_sym_ptr != NULL) ? *km_sym_ptr : $defaultLit;\n")
        val castExpr =
            when (st.bridgeKind) {
                CAbiBridgeKind.CONST_CSTR,
                CAbiBridgeKind.MUT_CSTR,
                CAbiBridgeKind.OPAQUE_PTR,
                CAbiBridgeKind.FILE_PTR,
                CAbiBridgeKind.DIR_PTR,
                CAbiBridgeKind.CALLBACK_PTR,
                -> "(jlong)(uintptr_t)km_res"
                else -> "($jniRet)km_res"
            }
        sb.append("    return $castExpr;\n")
        sb.append("}\n")
        return sb.toString()
    }

    private fun renderJniStaticSetterFunction(
        st: ForeignStaticInteropSpec,
        jniClassMangled: String,
    ): String {
        val sb = StringBuilder()
        val valParam = InteropParamSpec(kotlinName = "value", kotlinType = st.kotlinType, cName = "value", bridgeKind = st.bridgeKind)
        val jniValType = jniCTypeForParam(valParam, noStringConvEnabled = true)
        val jniSymbol = "Java_${jniClassMangled}_${jniMangleSegment(st.setterWrapperSymbolName)}"
        val cRet = cHeaderTypeForBridge(st.bridgeKind)
        val symEsc = escapeCString(st.cSymbolName)
        val castVal =
            when (st.bridgeKind) {
                CAbiBridgeKind.CONST_CSTR -> "(const char*)(uintptr_t)value"
                CAbiBridgeKind.MUT_CSTR -> "(char*)(uintptr_t)value"
                CAbiBridgeKind.OPAQUE_PTR,
                CAbiBridgeKind.FILE_PTR,
                CAbiBridgeKind.DIR_PTR,
                CAbiBridgeKind.CALLBACK_PTR,
                -> "(void*)(uintptr_t)value"
                else -> "($cRet)value"
            }

        sb.append("JNIEXPORT void JNICALL $jniSymbol(JNIEnv* km_env, jclass km_clazz, $jniValType value) {\n")
        sb.append("    (void)km_env;\n")
        sb.append("    (void)km_clazz;\n")
        sb.append("    static $cRet* km_sym_ptr = NULL;\n")
        sb.append("#ifdef _WIN32\n")
        sb.append("    if (km_sym_ptr == NULL) {\n")
        sb.append("        HMODULE km_h_ucrt = GetModuleHandleA(\"ucrtbase.dll\");\n")
        sb.append("        if (km_h_ucrt == NULL) km_h_ucrt = LoadLibraryA(\"ucrtbase.dll\");\n")
        sb.append("        if (km_h_ucrt != NULL) km_sym_ptr = ($cRet*)(void*)GetProcAddress(km_h_ucrt, \"$symEsc\");\n")
        if (!st.cSymbolName.startsWith("_")) {
            sb.append("        if (km_sym_ptr == NULL && km_h_ucrt != NULL) km_sym_ptr = ($cRet*)(void*)GetProcAddress(km_h_ucrt, \"_$symEsc\");\n")
        }
        sb.append("        if (km_sym_ptr == NULL) {\n")
        sb.append("            HMODULE km_h_msvcrt = GetModuleHandleA(\"msvcrt.dll\");\n")
        sb.append("            if (km_h_msvcrt == NULL) km_h_msvcrt = LoadLibraryA(\"msvcrt.dll\");\n")
        sb.append("            if (km_h_msvcrt != NULL) km_sym_ptr = ($cRet*)(void*)GetProcAddress(km_h_msvcrt, \"$symEsc\");\n")
        if (!st.cSymbolName.startsWith("_")) {
            sb.append("            if (km_sym_ptr == NULL && km_h_msvcrt != NULL) km_sym_ptr = ($cRet*)(void*)GetProcAddress(km_h_msvcrt, \"_$symEsc\");\n")
        }
        sb.append("        }\n")
        sb.append("    }\n")
        sb.append("#else\n")
        sb.append("    if (km_sym_ptr == NULL) {\n")
        sb.append("        km_sym_ptr = ($cRet*)dlsym(RTLD_DEFAULT, \"$symEsc\");\n")
        sb.append("    }\n")
        sb.append("#endif\n")
        sb.append("    if (km_sym_ptr != NULL) {\n")
        sb.append("        *km_sym_ptr = $castVal;\n")
        sb.append("    }\n")
        sb.append("}\n")
        return sb.toString()
    }

    private fun renderJniForeignFunction(
        fn: ForeignFnInteropSpec,
        jniClassMangled: String,
    ): String {
        val sb = StringBuilder()
        val noStrConv = requiresNoStringConversion(fn.params)
        val jniRet = jniCTypeForReturn(fn.returnBridgeKind)
        val jniSymbol = "Java_${jniClassMangled}_${jniMangleSegment(fn.wrapperSymbolName)}"
        val jniParams =
            buildList {
                add("JNIEnv* km_env")
                add("jclass km_clazz")
                for (p in fn.params) {
                    add("${jniCTypeForParam(p, noStrConv)} ${p.cName}")
                }
                if (fn.isVariadic) {
                    add("jint km_vac")
                    add("jlong km_va0")
                    add("jlong km_va1")
                    add("jlong km_va2")
                    add("jlong km_va3")
                }
            }.joinToString(", ")

        val cRet = cHeaderTypeForReturn(fn)
        val cParamTypesOnly = fn.params.joinToString(", ") { cHeaderTypeForParam(it) }
        val cParamTypes =
            when {
                fn.isVariadic -> "$cParamTypesOnly, ..."
                fn.params.isEmpty() -> "void"
                else -> cParamTypesOnly
            }
        val hasReturn = fn.returnBridgeKind != CAbiBridgeKind.VOID && fn.returnBridgeKind != CAbiBridgeKind.NEVER

        sb.append("JNIEXPORT $jniRet JNICALL $jniSymbol($jniParams) {\n")
        sb.append("    (void)km_env;\n")
        sb.append("    (void)km_clazz;\n")

        val stringParams =
            fn.params.filter { p ->
                p.bridgeKind == CAbiBridgeKind.CONST_CSTR &&
                    !noStrConv &&
                    (p.kotlinType == "String?" || p.kotlinType == "String")
            }
        for (sp in stringParams) {
            sb.append("    const char* ${sp.cName}_utf = (${sp.cName} != NULL) ? (*km_env)->GetStringUTFChars(km_env, ${sp.cName}, NULL) : NULL;\n")
        }

        val callArgs =
            fn.params.joinToString(", ") { p ->
                val isJStr =
                    p.bridgeKind == CAbiBridgeKind.CONST_CSTR &&
                        !noStrConv &&
                        (p.kotlinType == "String?" || p.kotlinType == "String")
                when {
                    isJStr -> "${p.cName}_utf"
                    p.bridgeKind == CAbiBridgeKind.CONST_CSTR -> "(const char*)(uintptr_t)${p.cName}"
                    p.bridgeKind == CAbiBridgeKind.MUT_CSTR -> "(char*)(uintptr_t)${p.cName}"
                    p.bridgeKind == CAbiBridgeKind.OPAQUE_PTR ||
                        p.bridgeKind == CAbiBridgeKind.FILE_PTR ||
                        p.bridgeKind == CAbiBridgeKind.DIR_PTR ||
                        p.bridgeKind == CAbiBridgeKind.CALLBACK_PTR -> "(void*)(uintptr_t)${p.cName}"
                    else -> "(${cHeaderTypeForParam(p)})${p.cName}"
                }
            }

        val symEsc = escapeCString(fn.cSymbolName)
        val isAtexit = isAtexitForeignFn(fn)
        if (isAtexit) {
            val cbName = fn.params[0].cName
            sb.append("    if ((void*)(uintptr_t)$cbName == NULL) {\n")
            sb.append("        errno = EINVAL;\n")
            sb.append("        return (jint)-1;\n")
            sb.append("    }\n")
            sb.append("#ifdef __ANDROID__\n")
            sb.append("    return (jint)atexit((void (*)(void))(uintptr_t)$cbName);\n")
            sb.append("#else\n")
        }
        if (fn.dllName != null) {
            sb.append("#ifdef _WIN32\n")
            sb.append("    typedef $cRet (${if (fn.isVariadic) "" else "__stdcall "}*km_fn_t)($cParamTypes);\n")
            sb.append("    static km_fn_t km_fn_ptr = NULL;\n")
            sb.append("    if (km_fn_ptr == NULL) {\n")
            sb.append("        HMODULE km_h_mod = LoadLibraryA(\"${escapeCString(fn.dllName)}\");\n")
            sb.append("        if (km_h_mod != NULL) km_fn_ptr = (km_fn_t)(void*)GetProcAddress(km_h_mod, \"$symEsc\");\n")
            sb.append("    }\n")
            appendJniFunctionDispatch(sb, fn, cRet, callArgs, hasReturn)
            sb.append("#else\n")
            for (p in fn.params) {
                if (p !in stringParams) sb.append("    (void)${p.cName};\n")
            }
            if (fn.isVariadic) {
                sb.append("    (void)km_vac; (void)km_va0; (void)km_va1; (void)km_va2; (void)km_va3;\n")
            }
            sb.append("    errno = ENOSYS;\n")
            if (hasReturn) {
                sb.append("    $cRet km_res = ${cFailureReturnExpr(fn)};\n")
            }
            sb.append("#endif\n")
        } else {
            sb.append("    typedef $cRet (*km_fn_t)($cParamTypes);\n")
            sb.append("    static km_fn_t km_fn_ptr = NULL;\n")
            sb.append("#ifdef _WIN32\n")
            sb.append("    if (km_fn_ptr == NULL) {\n")
            sb.append("        HMODULE km_h_ucrt = GetModuleHandleA(\"ucrtbase.dll\");\n")
            sb.append("        if (km_h_ucrt == NULL) km_h_ucrt = LoadLibraryA(\"ucrtbase.dll\");\n")
            sb.append("        if (km_h_ucrt != NULL) km_fn_ptr = (km_fn_t)(void*)GetProcAddress(km_h_ucrt, \"$symEsc\");\n")
            if (!fn.cSymbolName.startsWith("_")) {
                sb.append("        if (km_fn_ptr == NULL && km_h_ucrt != NULL) km_fn_ptr = (km_fn_t)(void*)GetProcAddress(km_h_ucrt, \"_$symEsc\");\n")
            }
            sb.append("        if (km_fn_ptr == NULL) {\n")
            sb.append("            HMODULE km_h_msvcrt = GetModuleHandleA(\"msvcrt.dll\");\n")
            sb.append("            if (km_h_msvcrt == NULL) km_h_msvcrt = LoadLibraryA(\"msvcrt.dll\");\n")
            sb.append("            if (km_h_msvcrt != NULL) km_fn_ptr = (km_fn_t)(void*)GetProcAddress(km_h_msvcrt, \"$symEsc\");\n")
            if (!fn.cSymbolName.startsWith("_")) {
                sb.append("            if (km_fn_ptr == NULL && km_h_msvcrt != NULL) km_fn_ptr = (km_fn_t)(void*)GetProcAddress(km_h_msvcrt, \"_$symEsc\");\n")
            }
            sb.append("        }\n")
            sb.append("    }\n")
            sb.append("#else\n")
            sb.append("    if (km_fn_ptr == NULL) {\n")
            sb.append("        km_fn_ptr = (km_fn_t)dlsym(RTLD_DEFAULT, \"$symEsc\");\n")
            appendCPosixDynamicLookupFallbacks(sb, fn)
            sb.append("    }\n")
            sb.append("#endif\n")
            appendJniFunctionDispatch(sb, fn, cRet, callArgs, hasReturn)
        }

        for (sp in stringParams) {
            sb.append("    if (${sp.cName} != NULL && ${sp.cName}_utf != NULL) (*km_env)->ReleaseStringUTFChars(km_env, ${sp.cName}, ${sp.cName}_utf);\n")
        }
        if (hasReturn) {
            val castExpr =
                when (fn.returnBridgeKind) {
                    CAbiBridgeKind.CONST_CSTR,
                    CAbiBridgeKind.MUT_CSTR,
                    CAbiBridgeKind.OPAQUE_PTR,
                    CAbiBridgeKind.FILE_PTR,
                    CAbiBridgeKind.DIR_PTR,
                    CAbiBridgeKind.CALLBACK_PTR,
                    -> "(jlong)(uintptr_t)km_res"
                    else -> "($jniRet)km_res"
                }
            sb.append("    return $castExpr;\n")
        }
        if (isAtexit) {
            sb.append("#endif\n")
        }
        sb.append("}\n")
        return sb.toString()
    }

    private fun appendJniFunctionDispatch(
        sb: StringBuilder,
        fn: ForeignFnInteropSpec,
        cRet: String,
        callArgs: String,
        hasReturn: Boolean,
    ) {
        val failExpr = cFailureReturnExpr(fn)
        if (!fn.isVariadic) {
            if (hasReturn) {
                sb.append("    $cRet km_res;\n")
                sb.append("    if (km_fn_ptr != NULL) {\n")
                sb.append("        km_res = km_fn_ptr($callArgs);\n")
                sb.append("    } else {\n")
                sb.append("        errno = ENOSYS;\n")
                sb.append("        km_res = $failExpr;\n")
                sb.append("    }\n")
            } else {
                sb.append("    if (km_fn_ptr != NULL) {\n")
                sb.append("        km_fn_ptr($callArgs);\n")
                sb.append("    } else {\n")
                sb.append("        errno = ENOSYS;\n")
                sb.append("    }\n")
            }
        } else {
            val prefix = if (callArgs.isEmpty()) "" else "$callArgs, "
            if (hasReturn) {
                sb.append("    $cRet km_res = $failExpr;\n")
                sb.append("    if (km_fn_ptr != NULL) {\n")
                sb.append("        switch (km_vac) {\n")
                sb.append("            case 0: km_res = km_fn_ptr($callArgs); break;\n")
                sb.append("            case 1: km_res = km_fn_ptr($prefix(intptr_t)km_va0); break;\n")
                sb.append("            case 2: km_res = km_fn_ptr($prefix(intptr_t)km_va0, (intptr_t)km_va1); break;\n")
                sb.append("            case 3: km_res = km_fn_ptr($prefix(intptr_t)km_va0, (intptr_t)km_va1, (intptr_t)km_va2); break;\n")
                sb.append("            default: km_res = km_fn_ptr($prefix(intptr_t)km_va0, (intptr_t)km_va1, (intptr_t)km_va2, (intptr_t)km_va3); break;\n")
                sb.append("        }\n")
                sb.append("    } else {\n")
                sb.append("        errno = ENOSYS;\n")
                sb.append("    }\n")
            } else {
                sb.append("    if (km_fn_ptr != NULL) {\n")
                sb.append("        switch (km_vac) {\n")
                sb.append("            case 0: km_fn_ptr($callArgs); break;\n")
                sb.append("            case 1: km_fn_ptr($prefix(intptr_t)km_va0); break;\n")
                sb.append("            case 2: km_fn_ptr($prefix(intptr_t)km_va0, (intptr_t)km_va1); break;\n")
                sb.append("            case 3: km_fn_ptr($prefix(intptr_t)km_va0, (intptr_t)km_va1, (intptr_t)km_va2); break;\n")
                sb.append("            default: km_fn_ptr($prefix(intptr_t)km_va0, (intptr_t)km_va1, (intptr_t)km_va2, (intptr_t)km_va3); break;\n")
                sb.append("        }\n")
                sb.append("    } else {\n")
                sb.append("        errno = ENOSYS;\n")
                sb.append("    }\n")
            }
        }
    }

    private fun renderJniInlineFunction(
        fn: InlineCFunctionSpec,
        jniClassMangled: String,
    ): String {
        val sb = StringBuilder()
        val inlineNoStrConv =
            requiresNoStringConversion(fn.params) ||
                fn.params.any { it.bridgeKind == CAbiBridgeKind.MUT_CSTR }
        val jniRet = jniCTypeForReturn(fn.returnBridgeKind)
        val jniSymbol = "Java_${jniClassMangled}_${jniMangleSegment(fn.wrapperSymbolName)}"
        val jniParams =
            buildList {
                add("JNIEnv* km_env")
                add("jclass km_clazz")
                for (p in fn.params) {
                    add("${jniCTypeForParam(p, inlineNoStrConv)} ${p.cName}")
                }
            }.joinToString(", ")

        val hasReturn = fn.returnBridgeKind != CAbiBridgeKind.VOID && fn.returnBridgeKind != CAbiBridgeKind.NEVER
        if (!usesCppLanguage && !fn.isCpp) {
            sb.append("static ").append(renderInlineWrapperCFunction(fn)).append('\n')
        }
        sb.append("JNIEXPORT $jniRet JNICALL $jniSymbol($jniParams) {\n")
        sb.append("    (void)km_env;\n")
        sb.append("    (void)km_clazz;\n")
        if (usesCppLanguage || fn.isCpp) {
            for (p in fn.params) {
                sb.append("    (void)${p.cName};\n")
            }
            if (hasReturn) {
                val defJni =
                    when (fn.returnBridgeKind) {
                        CAbiBridgeKind.F32 -> "0.0f"
                        CAbiBridgeKind.F64 -> "0.0"
                        else -> "0"
                    }
                sb.append("    return ($jniRet)$defJni;\n")
            }
        } else {
            val stringParams =
                fn.params.filter { p ->
                    p.bridgeKind == CAbiBridgeKind.CONST_CSTR &&
                        !inlineNoStrConv &&
                        (p.kotlinType == "String?" || p.kotlinType == "String")
                }
            for (sp in stringParams) {
                sb.append("    const char* ${sp.cName}_utf = (${sp.cName} != NULL) ? (*km_env)->GetStringUTFChars(km_env, ${sp.cName}, NULL) : NULL;\n")
            }
            val callArgs =
                fn.params.joinToString(", ") { p ->
                    val isJStr =
                        p.bridgeKind == CAbiBridgeKind.CONST_CSTR &&
                            !inlineNoStrConv &&
                            (p.kotlinType == "String?" || p.kotlinType == "String")
                    when {
                        isJStr -> "${p.cName}_utf"
                        p.bridgeKind == CAbiBridgeKind.CONST_CSTR -> "(const char*)(uintptr_t)${p.cName}"
                        p.bridgeKind == CAbiBridgeKind.MUT_CSTR -> "(char*)(uintptr_t)${p.cName}"
                        p.bridgeKind == CAbiBridgeKind.OPAQUE_PTR ||
                            p.bridgeKind == CAbiBridgeKind.FILE_PTR ||
                            p.bridgeKind == CAbiBridgeKind.DIR_PTR ||
                            p.bridgeKind == CAbiBridgeKind.CALLBACK_PTR -> "(void*)(uintptr_t)${p.cName}"
                        else -> "(${cHeaderTypeForParam(p)})${p.cName}"
                    }
                }
            if (hasReturn) {
                sb.append("    ${cHeaderTypeForInlineReturn(fn)} km_res = ${fn.wrapperSymbolName}($callArgs);\n")
            } else {
                sb.append("    ${fn.wrapperSymbolName}($callArgs);\n")
            }
            for (sp in stringParams) {
                sb.append("    if (${sp.cName} != NULL && ${sp.cName}_utf != NULL) (*km_env)->ReleaseStringUTFChars(km_env, ${sp.cName}, ${sp.cName}_utf);\n")
            }
            if (hasReturn) {
                val castExpr =
                    when (fn.returnBridgeKind) {
                        CAbiBridgeKind.CONST_CSTR,
                        CAbiBridgeKind.MUT_CSTR,
                        CAbiBridgeKind.OPAQUE_PTR,
                        CAbiBridgeKind.FILE_PTR,
                        CAbiBridgeKind.DIR_PTR,
                        CAbiBridgeKind.CALLBACK_PTR,
                        -> "(jlong)(uintptr_t)km_res"
                        else -> "($jniRet)km_res"
                    }
                sb.append("    return $castExpr;\n")
            }
        }
        sb.append("}\n")
        return sb.toString()
    }

    private fun buildJsActualsKotlinContent(
        pkgName: String?,
        stemPascal: String,
        ffiModName: String,
    ): String {
        val sb = StringBuilder()
        sb.append("@file:OptIn(ExperimentalUnsignedTypes::class)\n\n")
        if (!pkgName.isNullOrBlank()) {
            sb.append("package ").append(pkgName).append("\n\n")
        }
        val imports = linkedSetOf<String>()
        val actualBlocks = mutableListOf<String>()
        val emittedActuals = linkedSetOf<String>()
        val addonPropName = "${stemPascal.replaceFirstChar { it.lowercase() }}NapiAddon"
        var usesAddon = false

        for (st in foreignStatics) {
            if (!emittedActuals.add("val:${st.kotlinName}")) continue
            if (isBridgeableStaticInCWrapper(st)) {
                usesAddon = true
                actualBlocks += renderJsStaticActualProperty(st, addonPropName, pkgName, imports)
            } else {
                actualBlocks += renderStaticActualProperty(st)
            }
        }
        for (fn in foreignFunctions) {
            if (!emittedActuals.add("fn:${fn.kotlinName}:${fn.params.size}")) continue
            if (isBridgeableInCWrapper(fn)) {
                usesAddon = true
                actualBlocks +=
                    renderJsActualFunction(
                        visibilityPrefix = fn.visibilityPrefix,
                        kotlinName = fn.kotlinName,
                        addonPropName = addonPropName,
                        wrapperSymbolName = fn.wrapperSymbolName,
                        params = fn.params,
                        kotlinReturnType = fn.kotlinReturnType,
                        returnBridgeKind = fn.returnBridgeKind,
                        returnDefaultExpr = fn.returnDefaultExpr,
                        noStringConvEnabled = requiresNoStringConversion(fn.params),
                        isVariadic = fn.isVariadic,
                        pkgName = pkgName,
                        importsOut = imports,
                    )
            } else {
                actualBlocks +=
                    renderUnsupportedActualFunction(
                        visibilityPrefix = fn.visibilityPrefix,
                        kotlinName = fn.kotlinName,
                        params = fn.params,
                        kotlinReturnType = fn.kotlinReturnType,
                        returnBridgeKind = fn.returnBridgeKind,
                        isVariadic = fn.isVariadic,
                        message = "Native FFI addon is not available",
                    )
            }
        }
        for (fn in inlineFunctions) {
            if (!emittedActuals.add("fn:${fn.kotlinName}:${fn.params.size}")) continue
            val inlineNoStrConv =
                requiresNoStringConversion(fn.params) ||
                    fn.params.any { it.bridgeKind == CAbiBridgeKind.MUT_CSTR }
            if (isBridgeableInlineInCWrapper(fn)) {
                usesAddon = true
                actualBlocks +=
                    renderJsActualFunction(
                        visibilityPrefix = fn.visibilityPrefix,
                        kotlinName = fn.kotlinName,
                        addonPropName = addonPropName,
                        wrapperSymbolName = fn.wrapperSymbolName,
                        params = fn.params,
                        kotlinReturnType = fn.kotlinReturnType,
                        returnBridgeKind = fn.returnBridgeKind,
                        returnDefaultExpr = fn.returnDefaultExpr,
                        noStringConvEnabled = inlineNoStrConv,
                        isVariadic = false,
                        pkgName = pkgName,
                        importsOut = imports,
                    )
            } else {
                actualBlocks +=
                    renderUnsupportedActualFunction(
                        visibilityPrefix = fn.visibilityPrefix,
                        kotlinName = fn.kotlinName,
                        params = fn.params,
                        kotlinReturnType = fn.kotlinReturnType,
                        returnBridgeKind = fn.returnBridgeKind,
                        isVariadic = fn.isVariadic,
                        message = "Native FFI addon is not available",
                    )
            }
        }

        if (usesAddon) {
            val relsJs = napiRelativeCandidatesJsArray(ffiModName)
            val jsExpr =
                "(function() { " +
                    "if (typeof globalThis !== 'undefined' && typeof globalThis.__km_napi_$ffiModName !== 'undefined') return globalThis.__km_napi_$ffiModName; " +
                    "if (typeof process === 'undefined' || !process.versions || !process.versions.node) return null; " +
                    "var mod = process.getBuiltinModule ? process.getBuiltinModule('module') : (process.mainModule && process.mainModule.constructor); " +
                    "var req = (mod && mod.createRequire) ? mod.createRequire((process.cwd ? process.cwd() : '.') + '/package.json') : (process.mainModule ? process.mainModule.require : null); " +
                    "if (!req) return null; " +
                    "try { var pkg = req('@kotlinmania/$ffiModName-native-bindings'); if (typeof globalThis !== 'undefined') globalThis.__km_napi_$ffiModName = pkg; return pkg; } catch (_) {} " +
                    "var fs = req('fs'); " +
                    "var path = req('path'); " +
                    "var dir = process.cwd ? process.cwd() : '.'; " +
                    "var rels = $relsJs; " +
                    "for (var d = 0; d < 8; d++) { " +
                    "for (var i = 0; i < rels.length; i++) { " +
                    "var c = path.resolve(dir, rels[i]); " +
                    "if (fs.existsSync(c)) { var loaded = req(c); if (typeof globalThis !== 'undefined') globalThis.__km_napi_$ffiModName = loaded; return loaded; } " +
                    "} " +
                    "var parent = path.dirname(dir); " +
                    "if (parent === dir) break; " +
                    "dir = parent; " +
                    "} " +
                    "if (typeof globalThis !== 'undefined') globalThis.__km_napi_$ffiModName = null; " +
                    "return null; " +
                    "})()"
            val loaderBlock = "private val $addonPropName: dynamic =\n    js(\"$jsExpr\")"
            actualBlocks.add(0, loaderBlock)
        }

        val bodyJoined = PhantomRawPointerTypeStripper.strip(actualBlocks.joinToString("\n\n"))
        addRecordedImportsForBody(bodyJoined, pkgName, imports)

        if (imports.isNotEmpty()) {
            for (imp in sortImportsForKtlint(imports)) {
                sb.append("import ").append(imp).append('\n')
            }
            sb.append('\n')
        }
        sb.append(bodyJoined)
        if (actualBlocks.isNotEmpty()) {
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun napiRelativeCandidatesJsArray(ffiModName: String): String {
        val binNames =
            buildList {
                add("${ffiModName}_native.node")
                if (ffiModName.startsWith("lib") && ffiModName.length > 3) {
                    add("${ffiModName.substring(3)}_native.node")
                }
            }
        val rels =
            buildList {
                for (b in binNames) {
                    add(b)
                    add("build/node-ffi/$b")
                    add("build/node-ffi/release/$b")
                    add("build/node-ffi/debug/$b")
                    add("src/js/node-$ffiModName/build/Release/$b")
                    add("src/js/node-$ffiModName/build/Debug/$b")
                    add("src/js/node-$ffiModName/dist/build/Release/$b")
                    add("src/js/node-$ffiModName/dist/build/Debug/$b")
                }
            }
        return rels.joinToString(", ", prefix = "[", postfix = "]") { "'$it'" }
    }

    private fun renderJsOrWasmParamConversion(
        param: InteropParamSpec,
        noStringConvEnabled: Boolean,
    ): String =
        when (param.bridgeKind) {
            CAbiBridgeKind.CONST_CSTR ->
                if (!noStringConvEnabled && (param.kotlinType == "String?" || param.kotlinType == "String")) {
                    param.kotlinName
                } else {
                    "${renderPointerParamToLong(param)}.toString()"
                }
            CAbiBridgeKind.MUT_CSTR,
            CAbiBridgeKind.OPAQUE_PTR,
            CAbiBridgeKind.FILE_PTR,
            CAbiBridgeKind.DIR_PTR,
            CAbiBridgeKind.CALLBACK_PTR,
            -> "${renderPointerParamToLong(param)}.toString()"
            CAbiBridgeKind.I64, CAbiBridgeKind.U64 -> "${param.kotlinName}.toString()"
            CAbiBridgeKind.I8, CAbiBridgeKind.U8,
            CAbiBridgeKind.I16, CAbiBridgeKind.U16,
            CAbiBridgeKind.I32, CAbiBridgeKind.U32,
            -> renderScalarParamArg(param, NumericKind.I32, "toInt")
            CAbiBridgeKind.F32, CAbiBridgeKind.F64 -> renderScalarParamArg(param, NumericKind.F64, "toDouble")
            else -> param.kotlinName
        }

    private fun renderJsReturnConversion(
        rawCall: String,
        kotlinReturnType: String,
        returnBridgeKind: CAbiBridgeKind,
        returnDefaultExpr: String,
        pkgName: String?,
        importsOut: MutableSet<String>,
    ): String {
        val retNonNullNumeric = if (!kotlinReturnType.endsWith("?")) resolveNumericKind(kotlinReturnType) else null
        val isRawPointerReturn = headerContext.rawAbiMode || kotlinReturnType == "Long" || retNonNullNumeric != null
        val ktString = if (shadowsKotlinString(pkgName)) "kotlin.String" else "String"
        return when (returnBridgeKind) {
            CAbiBridgeKind.VOID -> rawCall
            CAbiBridgeKind.BOOL -> "($rawCall as Boolean)"
            CAbiBridgeKind.I8 ->
                if (retNonNullNumeric != null && retNonNullNumeric != NumericKind.I8) {
                    "($rawCall as Int).${conversionMethodForNumericKind(retNonNullNumeric)}()"
                } else {
                    "($rawCall as Int).toByte()"
                }
            CAbiBridgeKind.U8 ->
                if (retNonNullNumeric != null && retNonNullNumeric != NumericKind.U8) {
                    "($rawCall as Int).${conversionMethodForNumericKind(retNonNullNumeric)}()"
                } else {
                    "($rawCall as Int).toUByte()"
                }
            CAbiBridgeKind.I16 ->
                if (retNonNullNumeric != null && retNonNullNumeric != NumericKind.I16) {
                    "($rawCall as Int).${conversionMethodForNumericKind(retNonNullNumeric)}()"
                } else {
                    "($rawCall as Int).toShort()"
                }
            CAbiBridgeKind.U16 ->
                if (retNonNullNumeric != null && retNonNullNumeric != NumericKind.U16) {
                    "($rawCall as Int).${conversionMethodForNumericKind(retNonNullNumeric)}()"
                } else {
                    "($rawCall as Int).toUShort()"
                }
            CAbiBridgeKind.I32 ->
                if (retNonNullNumeric != null && retNonNullNumeric != NumericKind.I32) {
                    "($rawCall as Int).${conversionMethodForNumericKind(retNonNullNumeric)}()"
                } else {
                    "($rawCall as Int)"
                }
            CAbiBridgeKind.U32 ->
                if (retNonNullNumeric != null && retNonNullNumeric != NumericKind.U32) {
                    "($rawCall as Int).${conversionMethodForNumericKind(retNonNullNumeric)}()"
                } else {
                    "($rawCall as Int).toUInt()"
                }
            CAbiBridgeKind.I64 ->
                if (retNonNullNumeric != null && retNonNullNumeric != NumericKind.I64) {
                    "($rawCall as $ktString).toLong().${conversionMethodForNumericKind(retNonNullNumeric)}()"
                } else {
                    "($rawCall as $ktString).toLong()"
                }
            CAbiBridgeKind.U64 ->
                if (retNonNullNumeric != null && retNonNullNumeric != NumericKind.U64) {
                    "($rawCall as $ktString).toULong().${conversionMethodForNumericKind(retNonNullNumeric)}()"
                } else {
                    "($rawCall as $ktString).toULong()"
                }
            CAbiBridgeKind.F32 -> "($rawCall as Double).toFloat()"
            CAbiBridgeKind.F64 -> "($rawCall as Double)"
            CAbiBridgeKind.CALLBACK_PTR ->
                if (isRawPointerReturn) "($rawCall as $ktString).toLong()" else returnDefaultExpr
            CAbiBridgeKind.CONST_CSTR,
            CAbiBridgeKind.MUT_CSTR,
            CAbiBridgeKind.OPAQUE_PTR,
            CAbiBridgeKind.FILE_PTR,
            CAbiBridgeKind.DIR_PTR,
            -> {
                if (isRawPointerReturn) {
                    if (retNonNullNumeric == NumericKind.I64 || kotlinReturnType == "Long" || retNonNullNumeric == null) {
                        "($rawCall as $ktString).toLong()"
                    } else {
                        "($rawCall as $ktString).toLong().${conversionMethodForNumericKind(retNonNullNumeric)}()"
                    }
                } else {
                    wrapLongAddressForPointerReturn(
                        addrExpr = "($rawCall as $ktString).toLong()",
                        kotlinReturnType = kotlinReturnType,
                        returnBridgeKind = returnBridgeKind,
                        returnDefaultExpr = returnDefaultExpr,
                        pkgName = pkgName,
                        importsOut = importsOut,
                    )
                }
            }
            else -> returnDefaultExpr
        }
    }

    private fun renderJsStaticActualProperty(
        st: ForeignStaticInteropSpec,
        addonPropName: String,
        pkgName: String?,
        importsOut: MutableSet<String>,
    ): String {
        val rawGet = "$addonPropName.${st.getterWrapperSymbolName}()"
        val convertedGet = renderJsReturnConversion(rawGet, st.kotlinType, st.bridgeKind, st.defaultExpr, pkgName, importsOut)
        val getExpr = "if ($addonPropName != null) $convertedGet else throw UnsupportedOperationException(\"Native FFI addon is not available\")"
        return if (st.isMutable) {
            val valParam = InteropParamSpec(kotlinName = "value", kotlinType = st.kotlinType, cName = "value", bridgeKind = st.bridgeKind)
            val setArg = renderJsOrWasmParamConversion(valParam, noStringConvEnabled = true)
            "${st.visibilityPrefix}actual var ${st.kotlinName}: ${st.kotlinType}\n" +
                "    get() = $getExpr\n" +
                "    set(value) {\n" +
                "        if ($addonPropName != null) {\n" +
                "            $addonPropName.${st.setterWrapperSymbolName}($setArg)\n" +
                "        } else {\n" +
                "            throw UnsupportedOperationException(\"Native FFI addon is not available\")\n" +
                "        }\n" +
                "    }"
        } else {
            "${st.visibilityPrefix}actual val ${st.kotlinName}: ${st.kotlinType}\n" +
                "    get() = $getExpr"
        }
    }

    private fun renderJsActualFunction(
        visibilityPrefix: String,
        kotlinName: String,
        addonPropName: String,
        wrapperSymbolName: String,
        params: List<InteropParamSpec>,
        kotlinReturnType: String,
        returnBridgeKind: CAbiBridgeKind,
        returnDefaultExpr: String,
        noStringConvEnabled: Boolean,
        isVariadic: Boolean,
        pkgName: String?,
        importsOut: MutableSet<String>,
    ): String {
        val baseParams = params.map { "${it.kotlinName}: ${it.kotlinType}" }
        val paramsSig =
            (if (isVariadic) baseParams + "vararg variadicArgs: Long" else baseParams)
                .joinToString(", ")
        val retSuffix = if (returnBridgeKind == CAbiBridgeKind.VOID) "" else ": $kotlinReturnType"
        val baseArgs =
            params.map { param ->
                renderJsOrWasmParamConversion(param, noStringConvEnabled)
            }
        val allArgs =
            if (isVariadic) {
                baseArgs +
                    listOf(
                        "variadicArgs.size.coerceAtMost(4)",
                        "(if (variadicArgs.size > 0) variadicArgs[0] else 0L).toString()",
                        "(if (variadicArgs.size > 1) variadicArgs[1] else 0L).toString()",
                        "(if (variadicArgs.size > 2) variadicArgs[2] else 0L).toString()",
                        "(if (variadicArgs.size > 3) variadicArgs[3] else 0L).toString()",
                    )
            } else {
                baseArgs
            }
        val rawCall = "$addonPropName.$wrapperSymbolName(${allArgs.joinToString(", ")})"
        val convertedCall =
            renderJsReturnConversion(
                rawCall = rawCall,
                kotlinReturnType = kotlinReturnType,
                returnBridgeKind = returnBridgeKind,
                returnDefaultExpr = returnDefaultExpr,
                pkgName = pkgName,
                importsOut = importsOut,
            )
        return when {
            returnBridgeKind == CAbiBridgeKind.VOID ->
                "${visibilityPrefix}actual fun $kotlinName($paramsSig) {\n" +
                    "    if ($addonPropName != null) {\n" +
                    "        $rawCall\n" +
                    "    } else {\n" +
                    "        throw UnsupportedOperationException(\"Native FFI addon is not available\")\n" +
                    "    }\n" +
                    "}"
            returnBridgeKind == CAbiBridgeKind.NEVER || kotlinReturnType == "Nothing" ->
                "${visibilityPrefix}actual fun $kotlinName($paramsSig): Nothing {\n" +
                    "    if ($addonPropName != null) {\n" +
                    "        $rawCall\n" +
                    "    } else {\n" +
                    "        throw UnsupportedOperationException(\"Native FFI addon is not available\")\n" +
                    "    }\n" +
                    "    error(\"Unreachable noreturn C function $kotlinName\")\n" +
                    "}"
            else ->
                "${visibilityPrefix}actual fun $kotlinName($paramsSig)$retSuffix =\n" +
                    "    if ($addonPropName != null) $convertedCall else throw UnsupportedOperationException(\"Native FFI addon is not available\")"
        }
    }

    private fun buildNapiCppSourceContent(stemSnake: String): String {
        val sb = StringBuilder()
        sb.append("#ifndef _GNU_SOURCE\n#define _GNU_SOURCE\n#endif\n")
        sb.append("#include <napi.h>\n")
        sb.append("#include <cstdint>\n")
        sb.append("#include <cstddef>\n")
        sb.append("#include <cstdlib>\n")
        sb.append("#include <cstring>\n")
        sb.append("#include <cerrno>\n")
        sb.append("#include <string>\n")
        sb.append("#ifdef _WIN32\n")
        sb.append("#ifndef WIN32_LEAN_AND_MEAN\n#define WIN32_LEAN_AND_MEAN\n#endif\n")
        sb.append("#include <windows.h>\n")
        sb.append("#else\n")
        sb.append("#include <dlfcn.h>\n")
        sb.append("#ifndef RTLD_DEFAULT\n#define RTLD_DEFAULT ((void*)0)\n#endif\n")
        sb.append("#endif\n\n")

        if (preambles.isNotEmpty()) {
            for (chunk in preambles) {
                sb.append(chunk).append("\n\n")
            }
        }

        sb.append("struct KmNapiRegistrarNode {\n")
        sb.append("    void (*register_fn)(Napi::Env, Napi::Object);\n")
        sb.append("    KmNapiRegistrarNode* next;\n")
        sb.append("};\n")
        sb.append("#if defined(__GNUC__) || defined(__clang__)\n")
        sb.append("__attribute__((visibility(\"hidden\")))\n")
        sb.append("#endif\n")
        sb.append("extern KmNapiRegistrarNode* km_napi_registrar_head;\n\n")

        sb.append("namespace {\n\n")
        sb.append("static int64_t km_napi_arg_i64(const Napi::Value& v) {\n")
        sb.append("    if (v.IsNull() || v.IsUndefined()) return 0;\n")
        sb.append("    if (v.IsString()) {\n")
        sb.append("        std::string s = v.As<Napi::String>().Utf8Value();\n")
        sb.append("        return static_cast<int64_t>(std::strtoll(s.c_str(), nullptr, 10));\n")
        sb.append("    }\n")
        sb.append("    if (v.IsNumber()) return v.As<Napi::Number>().Int64Value();\n")
        sb.append("    return 0;\n")
        sb.append("}\n\n")
        sb.append("static uint64_t km_napi_arg_u64(const Napi::Value& v) {\n")
        sb.append("    if (v.IsNull() || v.IsUndefined()) return 0;\n")
        sb.append("    if (v.IsString()) {\n")
        sb.append("        std::string s = v.As<Napi::String>().Utf8Value();\n")
        sb.append("        return static_cast<uint64_t>(std::strtoull(s.c_str(), nullptr, 10));\n")
        sb.append("    }\n")
        sb.append("    if (v.IsNumber()) return static_cast<uint64_t>(v.As<Napi::Number>().Int64Value());\n")
        sb.append("    return 0;\n")
        sb.append("}\n\n")

        val emittedNapi = linkedSetOf<String>()
        for (st in foreignStatics) {
            if (!isBridgeableStaticInCWrapper(st)) continue
            if (emittedNapi.add(st.getterWrapperSymbolName)) {
                sb.append(renderNapiStaticGetterFunction(st)).append('\n')
            }
            if (st.isMutable && emittedNapi.add(st.setterWrapperSymbolName)) {
                sb.append(renderNapiStaticSetterFunction(st)).append('\n')
            }
        }
        for (fn in foreignFunctions) {
            if (!isBridgeableInCWrapper(fn)) continue
            if (!emittedNapi.add(fn.wrapperSymbolName)) continue
            sb.append(renderNapiForeignFunction(fn)).append('\n')
        }
        for (fn in inlineFunctions) {
            if (!isBridgeableInlineInCWrapper(fn)) continue
            if (!emittedNapi.add(fn.wrapperSymbolName)) continue
            sb.append(renderNapiInlineFunction(fn)).append('\n')
        }

        val registerFnName = "register_${stemSnake}_napi"
        sb.append("static void $registerFnName(Napi::Env env, Napi::Object exports) {\n")
        if (emittedNapi.isEmpty()) {
            sb.append("    (void)env;\n    (void)exports;\n")
        } else {
            for (sym in emittedNapi) {
                sb.append("    exports.Set(\"$sym\", Napi::Function::New(env, ${sym}_napi));\n")
            }
        }
        sb.append("}\n\n")
        sb.append("static KmNapiRegistrarNode registrar_node = { &$registerFnName, nullptr };\n")
        sb.append("struct KmNapiAutoRegister {\n")
        sb.append("    KmNapiAutoRegister() {\n")
        sb.append("        registrar_node.next = km_napi_registrar_head;\n")
        sb.append("        km_napi_registrar_head = &registrar_node;\n")
        sb.append("    }\n")
        sb.append("};\n")
        sb.append("static KmNapiAutoRegister auto_register_instance;\n\n")
        sb.append("} // namespace\n\n")
        sb.append("#ifdef KM_NAPI_STANDALONE_MODULE\n")
        sb.append("#if defined(__GNUC__) || defined(__clang__)\n")
        sb.append("__attribute__((visibility(\"hidden\")))\n")
        sb.append("#endif\n")
        sb.append("KmNapiRegistrarNode* km_napi_registrar_head = nullptr;\n")
        sb.append("static Napi::Object KmNapiStandaloneInit(Napi::Env env, Napi::Object exports) {\n")
        sb.append("    for (KmNapiRegistrarNode* cur = km_napi_registrar_head; cur != nullptr; cur = cur->next) {\n")
        sb.append("        cur->register_fn(env, exports);\n")
        sb.append("    }\n")
        sb.append("    return exports;\n")
        sb.append("}\n")
        sb.append("NODE_API_MODULE(NODE_GYP_MODULE_NAME, KmNapiStandaloneInit)\n")
        sb.append("#endif\n")
        return sb.toString()
    }

    private fun renderNapiStaticGetterFunction(st: ForeignStaticInteropSpec): String {
        val sb = StringBuilder()
        val cRet = cHeaderTypeForBridge(st.bridgeKind)
        val symEsc = escapeCString(st.cSymbolName)
        val defaultLit = cDefaultFailureLiteral(st.bridgeKind)

        sb.append("static Napi::Value ${st.getterWrapperSymbolName}_napi(const Napi::CallbackInfo& km_info) {\n")
        sb.append("    Napi::Env km_env = km_info.Env();\n")
        sb.append("    static $cRet* km_sym_ptr = nullptr;\n")
        sb.append("#ifdef _WIN32\n")
        sb.append("    if (km_sym_ptr == nullptr) {\n")
        sb.append("        HMODULE km_h_ucrt = GetModuleHandleA(\"ucrtbase.dll\");\n")
        sb.append("        if (km_h_ucrt == nullptr) km_h_ucrt = LoadLibraryA(\"ucrtbase.dll\");\n")
        sb.append("        if (km_h_ucrt != nullptr) km_sym_ptr = reinterpret_cast<$cRet*>(reinterpret_cast<void*>(GetProcAddress(km_h_ucrt, \"$symEsc\")));\n")
        if (!st.cSymbolName.startsWith("_")) {
            sb.append("        if (km_sym_ptr == nullptr && km_h_ucrt != nullptr) km_sym_ptr = reinterpret_cast<$cRet*>(reinterpret_cast<void*>(GetProcAddress(km_h_ucrt, \"_$symEsc\")));\n")
        }
        sb.append("        if (km_sym_ptr == nullptr) {\n")
        sb.append("            HMODULE km_h_msvcrt = GetModuleHandleA(\"msvcrt.dll\");\n")
        sb.append("            if (km_h_msvcrt == nullptr) km_h_msvcrt = LoadLibraryA(\"msvcrt.dll\");\n")
        sb.append("            if (km_h_msvcrt != nullptr) km_sym_ptr = reinterpret_cast<$cRet*>(reinterpret_cast<void*>(GetProcAddress(km_h_msvcrt, \"$symEsc\")));\n")
        if (!st.cSymbolName.startsWith("_")) {
            sb.append("            if (km_sym_ptr == nullptr && km_h_msvcrt != nullptr) km_sym_ptr = reinterpret_cast<$cRet*>(reinterpret_cast<void*>(GetProcAddress(km_h_msvcrt, \"_$symEsc\")));\n")
        }
        sb.append("        }\n")
        sb.append("    }\n")
        sb.append("#else\n")
        sb.append("    if (km_sym_ptr == nullptr) {\n")
        sb.append("        km_sym_ptr = reinterpret_cast<$cRet*>(dlsym(RTLD_DEFAULT, \"$symEsc\"));\n")
        sb.append("    }\n")
        sb.append("#endif\n")
        sb.append("    $cRet km_res = (km_sym_ptr != nullptr) ? *km_sym_ptr : $defaultLit;\n")
        sb.append(renderNapiReturnStatement(st.bridgeKind))
        sb.append("}\n")
        return sb.toString()
    }

    private fun renderNapiStaticSetterFunction(st: ForeignStaticInteropSpec): String {
        val sb = StringBuilder()
        val cRet = cHeaderTypeForBridge(st.bridgeKind)
        val symEsc = escapeCString(st.cSymbolName)

        sb.append("static Napi::Value ${st.setterWrapperSymbolName}_napi(const Napi::CallbackInfo& km_info) {\n")
        sb.append("    Napi::Env km_env = km_info.Env();\n")
        when (st.bridgeKind) {
            CAbiBridgeKind.CONST_CSTR ->
                sb.append("    const char* value = reinterpret_cast<const char*>(static_cast<uintptr_t>(km_info.Length() > 0 ? km_napi_arg_u64(km_info[0]) : 0));\n")
            CAbiBridgeKind.MUT_CSTR ->
                sb.append("    char* value = reinterpret_cast<char*>(static_cast<uintptr_t>(km_info.Length() > 0 ? km_napi_arg_u64(km_info[0]) : 0));\n")
            CAbiBridgeKind.OPAQUE_PTR,
            CAbiBridgeKind.FILE_PTR,
            CAbiBridgeKind.DIR_PTR,
            CAbiBridgeKind.CALLBACK_PTR,
            ->
                sb.append("    void* value = reinterpret_cast<void*>(static_cast<uintptr_t>(km_info.Length() > 0 ? km_napi_arg_u64(km_info[0]) : 0));\n")
            CAbiBridgeKind.I64 ->
                sb.append("    int64_t value = km_info.Length() > 0 ? km_napi_arg_i64(km_info[0]) : 0;\n")
            CAbiBridgeKind.U64 ->
                sb.append("    uint64_t value = km_info.Length() > 0 ? km_napi_arg_u64(km_info[0]) : 0;\n")
            CAbiBridgeKind.BOOL ->
                sb.append("    bool value = km_info.Length() > 0 ? km_info[0].ToBoolean().Value() : false;\n")
            CAbiBridgeKind.F32 ->
                sb.append("    float value = km_info.Length() > 0 ? km_info[0].ToNumber().FloatValue() : 0.0f;\n")
            CAbiBridgeKind.F64 ->
                sb.append("    double value = km_info.Length() > 0 ? km_info[0].ToNumber().DoubleValue() : 0.0;\n")
            else ->
                sb.append("    $cRet value = static_cast<$cRet>(km_info.Length() > 0 ? km_info[0].ToNumber().Int32Value() : 0);\n")
        }
        sb.append("    static $cRet* km_sym_ptr = nullptr;\n")
        sb.append("#ifdef _WIN32\n")
        sb.append("    if (km_sym_ptr == nullptr) {\n")
        sb.append("        HMODULE km_h_ucrt = GetModuleHandleA(\"ucrtbase.dll\");\n")
        sb.append("        if (km_h_ucrt == nullptr) km_h_ucrt = LoadLibraryA(\"ucrtbase.dll\");\n")
        sb.append("        if (km_h_ucrt != nullptr) km_sym_ptr = reinterpret_cast<$cRet*>(reinterpret_cast<void*>(GetProcAddress(km_h_ucrt, \"$symEsc\")));\n")
        if (!st.cSymbolName.startsWith("_")) {
            sb.append("        if (km_sym_ptr == nullptr && km_h_ucrt != nullptr) km_sym_ptr = reinterpret_cast<$cRet*>(reinterpret_cast<void*>(GetProcAddress(km_h_ucrt, \"_$symEsc\")));\n")
        }
        sb.append("        if (km_sym_ptr == nullptr) {\n")
        sb.append("            HMODULE km_h_msvcrt = GetModuleHandleA(\"msvcrt.dll\");\n")
        sb.append("            if (km_h_msvcrt == nullptr) km_h_msvcrt = LoadLibraryA(\"msvcrt.dll\");\n")
        sb.append("            if (km_h_msvcrt != nullptr) km_sym_ptr = reinterpret_cast<$cRet*>(reinterpret_cast<void*>(GetProcAddress(km_h_msvcrt, \"$symEsc\")));\n")
        if (!st.cSymbolName.startsWith("_")) {
            sb.append("            if (km_sym_ptr == nullptr && km_h_msvcrt != nullptr) km_sym_ptr = reinterpret_cast<$cRet*>(reinterpret_cast<void*>(GetProcAddress(km_h_msvcrt, \"_$symEsc\")));\n")
        }
        sb.append("        }\n")
        sb.append("    }\n")
        sb.append("#else\n")
        sb.append("    if (km_sym_ptr == nullptr) {\n")
        sb.append("        km_sym_ptr = reinterpret_cast<$cRet*>(dlsym(RTLD_DEFAULT, \"$symEsc\"));\n")
        sb.append("    }\n")
        sb.append("#endif\n")
        sb.append("    if (km_sym_ptr != nullptr) {\n")
        sb.append("        *km_sym_ptr = value;\n")
        sb.append("    }\n")
        sb.append("    return km_env.Undefined();\n")
        sb.append("}\n")
        return sb.toString()
    }

    private fun renderNapiForeignFunction(fn: ForeignFnInteropSpec): String {
        val sb = StringBuilder()
        val noStrConv = requiresNoStringConversion(fn.params)
        val cRet = cHeaderTypeForReturn(fn)
        val cParamTypesOnly = fn.params.joinToString(", ") { cHeaderTypeForParam(it) }
        val cParamTypes =
            when {
                fn.isVariadic -> "$cParamTypesOnly, ..."
                fn.params.isEmpty() -> "void"
                else -> cParamTypesOnly
            }
        val hasReturn = fn.returnBridgeKind != CAbiBridgeKind.VOID && fn.returnBridgeKind != CAbiBridgeKind.NEVER

        sb.append("static Napi::Value ${fn.wrapperSymbolName}_napi(const Napi::CallbackInfo& km_info) {\n")
        sb.append("    Napi::Env km_env = km_info.Env();\n")
        for ((idx, p) in fn.params.withIndex()) {
            val isStr =
                p.bridgeKind == CAbiBridgeKind.CONST_CSTR &&
                    !noStrConv &&
                    (p.kotlinType == "String?" || p.kotlinType == "String")
            when {
                isStr -> {
                    sb.append("    bool ${p.cName}_null = (km_info.Length() <= $idx || km_info[$idx].IsNull() || km_info[$idx].IsUndefined());\n")
                    sb.append("    std::string ${p.cName}_str = ${p.cName}_null ? std::string() : km_info[$idx].ToString().Utf8Value();\n")
                    sb.append("    const char* ${p.cName} = ${p.cName}_null ? nullptr : ${p.cName}_str.c_str();\n")
                }
                p.bridgeKind == CAbiBridgeKind.CONST_CSTR ->
                    sb.append("    const char* ${p.cName} = reinterpret_cast<const char*>(static_cast<uintptr_t>(km_info.Length() > $idx ? km_napi_arg_u64(km_info[$idx]) : 0));\n")
                p.bridgeKind == CAbiBridgeKind.MUT_CSTR ->
                    sb.append("    char* ${p.cName} = reinterpret_cast<char*>(static_cast<uintptr_t>(km_info.Length() > $idx ? km_napi_arg_u64(km_info[$idx]) : 0));\n")
                p.bridgeKind == CAbiBridgeKind.OPAQUE_PTR ||
                    p.bridgeKind == CAbiBridgeKind.FILE_PTR ||
                    p.bridgeKind == CAbiBridgeKind.DIR_PTR ||
                    p.bridgeKind == CAbiBridgeKind.CALLBACK_PTR ->
                    sb.append("    void* ${p.cName} = reinterpret_cast<void*>(static_cast<uintptr_t>(km_info.Length() > $idx ? km_napi_arg_u64(km_info[$idx]) : 0));\n")
                p.bridgeKind == CAbiBridgeKind.I64 ->
                    sb.append("    int64_t ${p.cName} = km_info.Length() > $idx ? km_napi_arg_i64(km_info[$idx]) : 0;\n")
                p.bridgeKind == CAbiBridgeKind.U64 ->
                    sb.append("    uint64_t ${p.cName} = km_info.Length() > $idx ? km_napi_arg_u64(km_info[$idx]) : 0;\n")
                p.bridgeKind == CAbiBridgeKind.BOOL ->
                    sb.append("    bool ${p.cName} = km_info.Length() > $idx ? km_info[$idx].ToBoolean().Value() : false;\n")
                p.bridgeKind == CAbiBridgeKind.F32 ->
                    sb.append("    float ${p.cName} = km_info.Length() > $idx ? km_info[$idx].ToNumber().FloatValue() : 0.0f;\n")
                p.bridgeKind == CAbiBridgeKind.F64 ->
                    sb.append("    double ${p.cName} = km_info.Length() > $idx ? km_info[$idx].ToNumber().DoubleValue() : 0.0;\n")
                else ->
                    sb.append("    ${cHeaderTypeForParam(p)} ${p.cName} = static_cast<${cHeaderTypeForParam(p)}>(km_info.Length() > $idx ? km_info[$idx].ToNumber().Int32Value() : 0);\n")
            }
        }
        if (fn.isVariadic) {
            val baseLen = fn.params.size
            sb.append("    int32_t km_vac = km_info.Length() > $baseLen ? km_info[$baseLen].ToNumber().Int32Value() : 0;\n")
            sb.append("    int64_t km_va0 = km_info.Length() > ${baseLen + 1} ? km_napi_arg_i64(km_info[${baseLen + 1}]) : 0;\n")
            sb.append("    int64_t km_va1 = km_info.Length() > ${baseLen + 2} ? km_napi_arg_i64(km_info[${baseLen + 2}]) : 0;\n")
            sb.append("    int64_t km_va2 = km_info.Length() > ${baseLen + 3} ? km_napi_arg_i64(km_info[${baseLen + 3}]) : 0;\n")
            sb.append("    int64_t km_va3 = km_info.Length() > ${baseLen + 4} ? km_napi_arg_i64(km_info[${baseLen + 4}]) : 0;\n")
        }

        val callArgs = fn.params.joinToString(", ") { it.cName }
        val symEsc = escapeCString(fn.cSymbolName)
        val isAtexit = isAtexitForeignFn(fn)
        if (isAtexit) {
            val cbName = fn.params[0].cName
            sb.append("    if ($cbName == nullptr) {\n")
            sb.append("        errno = EINVAL;\n")
            sb.append("        return Napi::Number::New(km_env, static_cast<int32_t>(-1));\n")
            sb.append("    }\n")
            sb.append("#ifdef __ANDROID__\n")
            sb.append("    int32_t km_res = static_cast<int32_t>(std::atexit(reinterpret_cast<void (*)()>(reinterpret_cast<uintptr_t>($cbName))));\n")
            sb.append("    return Napi::Number::New(km_env, static_cast<int32_t>(km_res));\n")
            sb.append("#else\n")
        }
        if (fn.dllName != null) {
            sb.append("#ifdef _WIN32\n")
            sb.append("    typedef $cRet (${if (fn.isVariadic) "" else "__stdcall "}*km_fn_t)($cParamTypes);\n")
            sb.append("    static km_fn_t km_fn_ptr = nullptr;\n")
            sb.append("    if (km_fn_ptr == nullptr) {\n")
            sb.append("        HMODULE km_h_mod = LoadLibraryA(\"${escapeCString(fn.dllName)}\");\n")
            sb.append("        if (km_h_mod != nullptr) km_fn_ptr = reinterpret_cast<km_fn_t>(reinterpret_cast<void*>(GetProcAddress(km_h_mod, \"$symEsc\")));\n")
            sb.append("    }\n")
            appendNapiFunctionDispatch(sb, fn, cRet, callArgs, hasReturn)
            sb.append("#else\n")
            for (p in fn.params) {
                sb.append("    (void)${p.cName};\n")
            }
            if (fn.isVariadic) {
                sb.append("    (void)km_vac; (void)km_va0; (void)km_va1; (void)km_va2; (void)km_va3;\n")
            }
            sb.append("    errno = ENOSYS;\n")
            if (hasReturn) {
                sb.append("    $cRet km_res = ${cppFailureReturnExpr(fn)};\n")
            }
            sb.append("#endif\n")
        } else {
            sb.append("    typedef $cRet (*km_fn_t)($cParamTypes);\n")
            sb.append("    static km_fn_t km_fn_ptr = nullptr;\n")
            sb.append("#ifdef _WIN32\n")
            sb.append("    if (km_fn_ptr == nullptr) {\n")
            sb.append("        HMODULE km_h_ucrt = GetModuleHandleA(\"ucrtbase.dll\");\n")
            sb.append("        if (km_h_ucrt == nullptr) km_h_ucrt = LoadLibraryA(\"ucrtbase.dll\");\n")
            sb.append("        if (km_h_ucrt != nullptr) km_fn_ptr = reinterpret_cast<km_fn_t>(reinterpret_cast<void*>(GetProcAddress(km_h_ucrt, \"$symEsc\")));\n")
            if (!fn.cSymbolName.startsWith("_")) {
                sb.append("        if (km_fn_ptr == nullptr && km_h_ucrt != nullptr) km_fn_ptr = reinterpret_cast<km_fn_t>(reinterpret_cast<void*>(GetProcAddress(km_h_ucrt, \"_$symEsc\")));\n")
            }
            sb.append("        if (km_fn_ptr == nullptr) {\n")
            sb.append("            HMODULE km_h_msvcrt = GetModuleHandleA(\"msvcrt.dll\");\n")
            sb.append("            if (km_h_msvcrt == nullptr) km_h_msvcrt = LoadLibraryA(\"msvcrt.dll\");\n")
            sb.append("            if (km_h_msvcrt != nullptr) km_fn_ptr = reinterpret_cast<km_fn_t>(reinterpret_cast<void*>(GetProcAddress(km_h_msvcrt, \"$symEsc\")));\n")
            if (!fn.cSymbolName.startsWith("_")) {
                sb.append("            if (km_fn_ptr == nullptr && km_h_msvcrt != nullptr) km_fn_ptr = reinterpret_cast<km_fn_t>(reinterpret_cast<void*>(GetProcAddress(km_h_msvcrt, \"_$symEsc\")));\n")
            }
            sb.append("        }\n")
            sb.append("    }\n")
            sb.append("#else\n")
            sb.append("    if (km_fn_ptr == nullptr) {\n")
            sb.append("        km_fn_ptr = reinterpret_cast<km_fn_t>(dlsym(RTLD_DEFAULT, \"$symEsc\"));\n")
            appendCppPosixDynamicLookupFallbacks(sb, fn)
            sb.append("    }\n")
            sb.append("#endif\n")
            appendNapiFunctionDispatch(sb, fn, cRet, callArgs, hasReturn)
        }

        sb.append(renderNapiReturnStatement(fn.returnBridgeKind))
        if (isAtexit) {
            sb.append("#endif\n")
        }
        sb.append("}\n")
        return sb.toString()
    }

    private fun appendNapiFunctionDispatch(
        sb: StringBuilder,
        fn: ForeignFnInteropSpec,
        cRet: String,
        callArgs: String,
        hasReturn: Boolean,
    ) {
        val failExpr = cppFailureReturnExpr(fn)
        if (!fn.isVariadic) {
            if (hasReturn) {
                sb.append("    $cRet km_res;\n")
                sb.append("    if (km_fn_ptr != nullptr) {\n")
                sb.append("        km_res = km_fn_ptr($callArgs);\n")
                sb.append("    } else {\n")
                sb.append("        errno = ENOSYS;\n")
                sb.append("        km_res = $failExpr;\n")
                sb.append("    }\n")
            } else {
                sb.append("    if (km_fn_ptr != nullptr) {\n")
                sb.append("        km_fn_ptr($callArgs);\n")
                sb.append("    } else {\n")
                sb.append("        errno = ENOSYS;\n")
                sb.append("    }\n")
            }
        } else {
            val prefix = if (callArgs.isEmpty()) "" else "$callArgs, "
            if (hasReturn) {
                sb.append("    $cRet km_res = $failExpr;\n")
                sb.append("    if (km_fn_ptr != nullptr) {\n")
                sb.append("        switch (km_vac) {\n")
                sb.append("            case 0: km_res = km_fn_ptr($callArgs); break;\n")
                sb.append("            case 1: km_res = km_fn_ptr(${prefix}static_cast<intptr_t>(km_va0)); break;\n")
                sb.append("            case 2: km_res = km_fn_ptr(${prefix}static_cast<intptr_t>(km_va0), static_cast<intptr_t>(km_va1)); break;\n")
                sb.append("            case 3: km_res = km_fn_ptr(${prefix}static_cast<intptr_t>(km_va0), static_cast<intptr_t>(km_va1), static_cast<intptr_t>(km_va2)); break;\n")
                sb.append("            default: km_res = km_fn_ptr(${prefix}static_cast<intptr_t>(km_va0), static_cast<intptr_t>(km_va1), static_cast<intptr_t>(km_va2), static_cast<intptr_t>(km_va3)); break;\n")
                sb.append("        }\n")
                sb.append("    } else {\n")
                sb.append("        errno = ENOSYS;\n")
                sb.append("    }\n")
            } else {
                sb.append("    if (km_fn_ptr != nullptr) {\n")
                sb.append("        switch (km_vac) {\n")
                sb.append("            case 0: km_fn_ptr($callArgs); break;\n")
                sb.append("            case 1: km_fn_ptr(${prefix}static_cast<intptr_t>(km_va0)); break;\n")
                sb.append("            case 2: km_fn_ptr(${prefix}static_cast<intptr_t>(km_va0), static_cast<intptr_t>(km_va1)); break;\n")
                sb.append("            case 3: km_fn_ptr(${prefix}static_cast<intptr_t>(km_va0), static_cast<intptr_t>(km_va1), static_cast<intptr_t>(km_va2)); break;\n")
                sb.append("            default: km_fn_ptr(${prefix}static_cast<intptr_t>(km_va0), static_cast<intptr_t>(km_va1), static_cast<intptr_t>(km_va2), static_cast<intptr_t>(km_va3)); break;\n")
                sb.append("        }\n")
                sb.append("    } else {\n")
                sb.append("        errno = ENOSYS;\n")
                sb.append("    }\n")
            }
        }
    }

    private fun renderNapiInlineFunction(fn: InlineCFunctionSpec): String {
        val sb = StringBuilder()
        val inlineNoStrConv =
            requiresNoStringConversion(fn.params) ||
                fn.params.any { it.bridgeKind == CAbiBridgeKind.MUT_CSTR }
        val hasReturn = fn.returnBridgeKind != CAbiBridgeKind.VOID && fn.returnBridgeKind != CAbiBridgeKind.NEVER
        sb.append("static ").append(renderInlineWrapperCFunction(fn)).append('\n')
        sb.append("static Napi::Value ${fn.wrapperSymbolName}_napi(const Napi::CallbackInfo& km_info) {\n")
        sb.append("    Napi::Env km_env = km_info.Env();\n")
        for ((idx, p) in fn.params.withIndex()) {
            val isStr =
                p.bridgeKind == CAbiBridgeKind.CONST_CSTR &&
                    !inlineNoStrConv &&
                    (p.kotlinType == "String?" || p.kotlinType == "String")
            when {
                isStr -> {
                    sb.append("    bool ${p.cName}_null = (km_info.Length() <= $idx || km_info[$idx].IsNull() || km_info[$idx].IsUndefined());\n")
                    sb.append("    std::string ${p.cName}_str = ${p.cName}_null ? std::string() : km_info[$idx].ToString().Utf8Value();\n")
                    sb.append("    const char* ${p.cName} = ${p.cName}_null ? nullptr : ${p.cName}_str.c_str();\n")
                }
                p.bridgeKind == CAbiBridgeKind.CONST_CSTR ->
                    sb.append("    const char* ${p.cName} = reinterpret_cast<const char*>(static_cast<uintptr_t>(km_info.Length() > $idx ? km_napi_arg_u64(km_info[$idx]) : 0));\n")
                p.bridgeKind == CAbiBridgeKind.MUT_CSTR ->
                    sb.append("    char* ${p.cName} = reinterpret_cast<char*>(static_cast<uintptr_t>(km_info.Length() > $idx ? km_napi_arg_u64(km_info[$idx]) : 0));\n")
                p.bridgeKind == CAbiBridgeKind.OPAQUE_PTR ||
                    p.bridgeKind == CAbiBridgeKind.FILE_PTR ||
                    p.bridgeKind == CAbiBridgeKind.DIR_PTR ||
                    p.bridgeKind == CAbiBridgeKind.CALLBACK_PTR ->
                    sb.append("    void* ${p.cName} = reinterpret_cast<void*>(static_cast<uintptr_t>(km_info.Length() > $idx ? km_napi_arg_u64(km_info[$idx]) : 0));\n")
                p.bridgeKind == CAbiBridgeKind.I64 ->
                    sb.append("    int64_t ${p.cName} = km_info.Length() > $idx ? km_napi_arg_i64(km_info[$idx]) : 0;\n")
                p.bridgeKind == CAbiBridgeKind.U64 ->
                    sb.append("    uint64_t ${p.cName} = km_info.Length() > $idx ? km_napi_arg_u64(km_info[$idx]) : 0;\n")
                p.bridgeKind == CAbiBridgeKind.BOOL ->
                    sb.append("    bool ${p.cName} = km_info.Length() > $idx ? km_info[$idx].ToBoolean().Value() : false;\n")
                p.bridgeKind == CAbiBridgeKind.F32 ->
                    sb.append("    float ${p.cName} = km_info.Length() > $idx ? km_info[$idx].ToNumber().FloatValue() : 0.0f;\n")
                p.bridgeKind == CAbiBridgeKind.F64 ->
                    sb.append("    double ${p.cName} = km_info.Length() > $idx ? km_info[$idx].ToNumber().DoubleValue() : 0.0;\n")
                else ->
                    sb.append("    ${cHeaderTypeForParam(p)} ${p.cName} = static_cast<${cHeaderTypeForParam(p)}>(km_info.Length() > $idx ? km_info[$idx].ToNumber().Int32Value() : 0);\n")
            }
        }
        val callArgs = fn.params.joinToString(", ") { it.cName }
        if (hasReturn) {
            sb.append("    ${cHeaderTypeForInlineReturn(fn)} km_res = ${fn.wrapperSymbolName}($callArgs);\n")
        } else {
            sb.append("    ${fn.wrapperSymbolName}($callArgs);\n")
        }
        sb.append(renderNapiReturnStatement(fn.returnBridgeKind))
        sb.append("}\n")
        return sb.toString()
    }

    private fun renderNapiReturnStatement(returnBridgeKind: CAbiBridgeKind): String =
        when (returnBridgeKind) {
            CAbiBridgeKind.VOID, CAbiBridgeKind.NEVER -> "    return km_env.Undefined();\n"
            CAbiBridgeKind.BOOL -> "    return Napi::Boolean::New(km_env, km_res);\n"
            CAbiBridgeKind.I8, CAbiBridgeKind.U8,
            CAbiBridgeKind.I16, CAbiBridgeKind.U16,
            CAbiBridgeKind.I32, CAbiBridgeKind.U32,
            -> "    return Napi::Number::New(km_env, static_cast<int32_t>(km_res));\n"
            CAbiBridgeKind.I64 -> "    return Napi::String::New(km_env, std::to_string(static_cast<int64_t>(km_res)));\n"
            CAbiBridgeKind.U64 -> "    return Napi::String::New(km_env, std::to_string(static_cast<uint64_t>(km_res)));\n"
            CAbiBridgeKind.F32, CAbiBridgeKind.F64 -> "    return Napi::Number::New(km_env, static_cast<double>(km_res));\n"
            CAbiBridgeKind.CONST_CSTR,
            CAbiBridgeKind.MUT_CSTR,
            CAbiBridgeKind.OPAQUE_PTR,
            CAbiBridgeKind.FILE_PTR,
            CAbiBridgeKind.DIR_PTR,
            CAbiBridgeKind.CALLBACK_PTR,
            -> "    return Napi::String::New(km_env, std::to_string(static_cast<int64_t>(reinterpret_cast<uintptr_t>(km_res))));\n"
            CAbiBridgeKind.BY_VALUE_OBJECT -> "    return km_env.Undefined();\n"
        }

    private fun cHeaderTypeForBridge(kind: CAbiBridgeKind): String =
        when (kind) {
            CAbiBridgeKind.FILE_PTR, CAbiBridgeKind.DIR_PTR, CAbiBridgeKind.OPAQUE_PTR, CAbiBridgeKind.CALLBACK_PTR -> "void*"
            else -> cTypeForBridge(kind)
        }

    private fun cHeaderTypeForParam(param: InteropParamSpec): String =
        cHeaderTypeForBridge(param.bridgeKind)

    private fun cHeaderTypeForReturn(fn: ForeignFnInteropSpec): String =
        cHeaderTypeForBridge(fn.returnBridgeKind)

    private fun cHeaderTypeForInlineReturn(fn: InlineCFunctionSpec): String =
        cHeaderTypeForBridge(fn.returnBridgeKind)

    companion object {
        private val POINTEE_STOP_IDENTS = setOf("c_char", "FILE", "DIR", "c_void")

        private fun requiresNoStringConversion(params: List<InteropParamSpec>): Boolean =
            params.any { param ->
                param.bridgeKind == CAbiBridgeKind.CONST_CSTR &&
                    param.kotlinType != "String?" &&
                    param.kotlinType != "String"
            }

        private fun unwrapOuterAliases(
            ty: SynType,
            resolveTypeAliasTarget: (String) -> SynType?,
        ): SynType {
            var cur = unwrapType(ty)
            var depth = 0
            while (depth < 8 && cur is SynType.Path && cur.qself == null) {
                val lastSeg = cur.path.segments.last() ?: break
                if (lastSeg.arguments !is PathArguments.None) break
                val rawIdent = lastSeg.ident.toString().removePrefix("r#")
                val target = resolveTypeAliasTarget(rawIdent) ?: break
                cur = unwrapType(target)
                depth++
            }
            return cur
        }

        private fun unwrapPointeeAliases(
            ty: SynType,
            resolveTypeAliasTarget: (String) -> SynType?,
        ): SynType {
            var cur = unwrapType(ty)
            var depth = 0
            while (depth < 8 && cur is SynType.Path && cur.qself == null) {
                val lastSeg = cur.path.segments.last() ?: break
                if (lastSeg.arguments !is PathArguments.None) break
                val rawIdent = lastSeg.ident.toString().removePrefix("r#")
                if (rawIdent in POINTEE_STOP_IDENTS) break
                val target = resolveTypeAliasTarget(rawIdent) ?: break
                cur = unwrapType(target)
                depth++
            }
            return cur
        }

        internal fun classifyAbiBridge(
            synType: SynType?,
            renderedKotlinType: String,
            cfgProfile: CfgTargetProfile = TranslationSession.cfgTargetProfile,
            resolveNumericKind: (String) -> NumericKind? = { null },
            resolveTypeAliasTarget: (String) -> SynType? = { null },
            resolvePointerAlias: (String) -> Pair<Boolean, String>? = { null },
        ): CAbiBridgeKind {
            if (synType == null) return CAbiBridgeKind.VOID
            val unwrapped = unwrapOuterAliases(synType, resolveTypeAliasTarget)
            if (unwrapped is SynType.Tuple && unwrapped.elems.isEmpty()) return CAbiBridgeKind.VOID
            if (renderedKotlinType == "Unit" && resolveNumericKind(renderedKotlinType) == null && unwrapped == synType) {
                return CAbiBridgeKind.VOID
            }
            if (unwrapped is SynType.Never || renderedKotlinType == "Nothing") return CAbiBridgeKind.NEVER
            if (unwrapped is SynType.FnPtr) return CAbiBridgeKind.CALLBACK_PTR
            if (unwrapped is SynType.Ptr) {
                val elem = unwrapPointeeAliases(unwrapped.elem, resolveTypeAliasTarget)
                val elemPath = (elem as? SynType.Path)?.takeIf { it.qself == null }?.path
                val elemSeg = elemPath?.segments?.last()
                val elemIdent = elemSeg?.ident?.toString()?.removePrefix("r#")
                return when (elemIdent) {
                    "c_char" -> {
                        if (unwrapped.mutability is PointerMutability.Mut) {
                            CAbiBridgeKind.MUT_CSTR
                        } else {
                            CAbiBridgeKind.CONST_CSTR
                        }
                    }
                    "FILE" -> CAbiBridgeKind.FILE_PTR
                    "DIR" -> CAbiBridgeKind.DIR_PTR
                    else -> CAbiBridgeKind.OPAQUE_PTR
                }
            }
            if (unwrapped is SynType.Path && unwrapped.qself == null) {
                val lastSeg = unwrapped.path.segments.last()
                val pathIdent = lastSeg?.ident?.toString()?.removePrefix("r#")
                if (pathIdent == "Option") {
                    val args = (lastSeg.arguments as? PathArguments.AngleBracketed)?.args?.toList()
                    val firstTypeArg =
                        args
                            ?.mapNotNull { (it as? io.github.kotlinmania.syn.GenericArgument.TypeArg)?.type }
                            ?.firstOrNull()
                    if (firstTypeArg != null) {
                        val innerUnwrapped = unwrapOuterAliases(firstTypeArg, resolveTypeAliasTarget)
                        if (innerUnwrapped is SynType.FnPtr) {
                            return CAbiBridgeKind.CALLBACK_PTR
                        }
                    }
                }
                val ptrAlias =
                    (pathIdent?.let(resolvePointerAlias))
                        ?: resolvePointerAlias(renderedKotlinType)
                if (ptrAlias != null) {
                    val (isMut, pointeeIdent) = ptrAlias
                    return when (pointeeIdent) {
                        "c_char" -> if (isMut) CAbiBridgeKind.MUT_CSTR else CAbiBridgeKind.CONST_CSTR
                        "FILE" -> CAbiBridgeKind.FILE_PTR
                        "DIR" -> CAbiBridgeKind.DIR_PTR
                        else -> CAbiBridgeKind.OPAQUE_PTR
                    }
                }
            }
            val unwrappedPathIdent =
                (unwrapped as? SynType.Path)
                    ?.takeIf { it.qself == null }
                    ?.path
                    ?.segments
                    ?.last()
                    ?.ident
                    ?.toString()
                    ?.removePrefix("r#")
            if (!renderedKotlinType.endsWith("?")) {
                val numericKind =
                    resolveNumericKind(renderedKotlinType)
                        ?: unwrappedPathIdent?.let(resolveNumericKind)
                when (numericKind) {
                    NumericKind.I8 -> return CAbiBridgeKind.I8
                    NumericKind.U8 -> return CAbiBridgeKind.U8
                    NumericKind.I16 -> return CAbiBridgeKind.I16
                    NumericKind.U16 -> return CAbiBridgeKind.U16
                    NumericKind.I32 -> return CAbiBridgeKind.I32
                    NumericKind.U32 -> return CAbiBridgeKind.U32
                    NumericKind.I64 -> return CAbiBridgeKind.I64
                    NumericKind.U64 -> return CAbiBridgeKind.U64
                    NumericKind.F32 -> return CAbiBridgeKind.F32
                    NumericKind.F64 -> return CAbiBridgeKind.F64
                    NumericKind.I128, NumericKind.U128, null -> {}
                }
            }
            val ptrWidth = cfgProfile.pointerWidth
            val isWindows =
                cfgProfile.targetOs == "windows" ||
                    cfgProfile.targetFamilies?.contains("windows") == true
            val ptrSigned =
                when (ptrWidth) {
                    64 -> CAbiBridgeKind.I64
                    16 -> CAbiBridgeKind.I16
                    else -> CAbiBridgeKind.I32
                }
            val ptrUnsigned =
                when (ptrWidth) {
                    64 -> CAbiBridgeKind.U64
                    16 -> CAbiBridgeKind.U16
                    else -> CAbiBridgeKind.U32
                }
            val cLongSigned = if (ptrWidth == 64 && !isWindows) CAbiBridgeKind.I64 else CAbiBridgeKind.I32
            val cLongUnsigned = if (ptrWidth == 64 && !isWindows) CAbiBridgeKind.U64 else CAbiBridgeKind.U32
            val simpleType = renderedKotlinType.substringAfterLast('.')
            if (isCPointerWidthSignedTypeName(simpleType)) return ptrSigned
            if (isCPointerWidthUnsignedTypeName(simpleType)) return ptrUnsigned
            return when (simpleType) {
                "Boolean" -> CAbiBridgeKind.BOOL
                "Byte", "CChar", "c_char", "CSchar", "c_schar", "Int8T", "int8_t" -> CAbiBridgeKind.I8
                "UByte", "CUchar", "c_uchar", "Uint8T", "uint8_t" -> CAbiBridgeKind.U8
                "Short", "CShort", "c_short", "Int16T", "int16_t" -> CAbiBridgeKind.I16
                "UShort", "CUshort", "c_ushort", "Uint16T", "uint16_t", "SaFamilyT", "sa_family_t", "InPortT", "in_port_t" ->
                    CAbiBridgeKind.U16
                "Int", "CInt", "c_int", "Int32T", "int32_t", "PidT", "pid_t", "ClockidT", "clockid_t", "WcharT", "wchar_t" ->
                    CAbiBridgeKind.I32
                "UInt", "CUint", "c_uint", "Uint32T", "uint32_t", "UidT", "uid_t", "GidT", "gid_t",
                "ModeT", "mode_t", "SocklenT", "socklen_t", "InAddrT", "in_addr_t",
                -> CAbiBridgeKind.U32
                "CLong", "c_long" -> cLongSigned
                "CUlong", "c_ulong" -> cLongUnsigned
                "Long", "CLonglong", "c_longlong", "OffT", "off_t", "TimeT", "time_t", "Int64T", "int64_t" -> CAbiBridgeKind.I64
                "ULong", "CUlonglong", "c_ulonglong", "Uint64T", "uint64_t" -> CAbiBridgeKind.U64
                "Float", "CFloat", "c_float" -> CAbiBridgeKind.F32
                "Double", "CDouble", "c_double" -> CAbiBridgeKind.F64
                else ->
                    when {
                        simpleType == "ConstPtr" || simpleType.startsWith("ConstPtr<") ||
                            simpleType == "MutPtr" || simpleType.startsWith("MutPtr<") -> CAbiBridgeKind.OPAQUE_PTR
                        renderedKotlinType.endsWith("?") -> CAbiBridgeKind.OPAQUE_PTR
                        simpleType.startsWith("Cfn") ||
                            simpleType.endsWith("Callback") ||
                            simpleType.endsWith("Handler") ||
                            simpleType.endsWith("Routine") ||
                            simpleType.endsWith("Proc") -> CAbiBridgeKind.CALLBACK_PTR
                        else -> CAbiBridgeKind.BY_VALUE_OBJECT
                    }
            }
        }

        private fun classifyExplicitCTypeBridge(
            cType: String,
            cfgProfile: CfgTargetProfile = TranslationSession.cfgTargetProfile,
        ): CAbiBridgeKind {
            val norm = cType.replace(" ", "")
            val ptrWidth = cfgProfile.pointerWidth
            val isWindows =
                cfgProfile.targetOs == "windows" ||
                    cfgProfile.targetFamilies?.contains("windows") == true
            val ptrSigned = if (ptrWidth == 64) CAbiBridgeKind.I64 else CAbiBridgeKind.I32
            val ptrUnsigned = if (ptrWidth == 64) CAbiBridgeKind.U64 else CAbiBridgeKind.U32
            val cLongSigned = if (ptrWidth == 64 && !isWindows) CAbiBridgeKind.I64 else CAbiBridgeKind.I32
            val cLongUnsigned = if (ptrWidth == 64 && !isWindows) CAbiBridgeKind.U64 else CAbiBridgeKind.U32
            return when {
                norm == "void" -> CAbiBridgeKind.VOID
                norm == "bool" || norm == "_Bool" -> CAbiBridgeKind.BOOL
                norm == "constchar*" -> CAbiBridgeKind.CONST_CSTR
                norm == "char*" -> CAbiBridgeKind.MUT_CSTR
                norm == "FILE*" || norm == "constFILE*" -> CAbiBridgeKind.FILE_PTR
                norm == "DIR*" || norm == "constDIR*" -> CAbiBridgeKind.DIR_PTR
                norm.endsWith("*") -> CAbiBridgeKind.OPAQUE_PTR
                norm == "int8_t" || norm == "signedchar" || norm == "char" -> CAbiBridgeKind.I8
                norm == "uint8_t" || norm == "unsignedchar" -> CAbiBridgeKind.U8
                norm == "int16_t" || norm == "short" -> CAbiBridgeKind.I16
                norm == "uint16_t" || norm == "unsignedshort" -> CAbiBridgeKind.U16
                norm == "uint32_t" || norm == "unsignedint" || norm == "unsigned" -> CAbiBridgeKind.U32
                norm == "long" -> cLongSigned
                norm == "unsignedlong" -> cLongUnsigned
                norm == "ssize_t" || norm == "intptr_t" || norm == "ptrdiff_t" -> ptrSigned
                norm == "size_t" || norm == "uintptr_t" -> ptrUnsigned
                norm == "int64_t" || norm == "longlong" -> CAbiBridgeKind.I64
                norm == "uint64_t" || norm == "unsignedlonglong" -> CAbiBridgeKind.U64
                norm == "float" -> CAbiBridgeKind.F32
                norm == "double" -> CAbiBridgeKind.F64
                else -> CAbiBridgeKind.I32
            }
        }

        private fun inferKotlinTypeFromCType(
            cType: String,
            rawAbiMode: Boolean = false,
            cfgProfile: CfgTargetProfile = TranslationSession.cfgTargetProfile,
        ): String =
            when (classifyExplicitCTypeBridge(cType, cfgProfile)) {
                CAbiBridgeKind.VOID, CAbiBridgeKind.NEVER -> "Unit"
                CAbiBridgeKind.BOOL -> "Boolean"
                CAbiBridgeKind.I8 -> "Byte"
                CAbiBridgeKind.U8 -> "UByte"
                CAbiBridgeKind.I16 -> "Short"
                CAbiBridgeKind.U16 -> "UShort"
                CAbiBridgeKind.I32 -> "Int"
                CAbiBridgeKind.U32 -> "UInt"
                CAbiBridgeKind.I64 -> "Long"
                CAbiBridgeKind.U64 -> "ULong"
                CAbiBridgeKind.F32 -> "Float"
                CAbiBridgeKind.F64 -> "Double"
                CAbiBridgeKind.CONST_CSTR -> if (rawAbiMode) "Long" else "ConstPtr"
                CAbiBridgeKind.MUT_CSTR -> if (rawAbiMode) "Long" else "MutPtr"
                CAbiBridgeKind.FILE_PTR,
                CAbiBridgeKind.DIR_PTR,
                CAbiBridgeKind.OPAQUE_PTR,
                CAbiBridgeKind.CALLBACK_PTR,
                CAbiBridgeKind.BY_VALUE_OBJECT,
                ->
                    if (rawAbiMode) {
                        "Long"
                    } else if (cType.replace(" ", "").startsWith("const")) {
                        "ConstPtr"
                    } else {
                        "MutPtr"
                    }
            }

        private fun cTypeForBridge(kind: CAbiBridgeKind): String =
            when (kind) {
                CAbiBridgeKind.VOID, CAbiBridgeKind.NEVER -> "void"
                CAbiBridgeKind.BOOL -> "bool"
                CAbiBridgeKind.I8 -> "int8_t"
                CAbiBridgeKind.U8 -> "uint8_t"
                CAbiBridgeKind.I16 -> "int16_t"
                CAbiBridgeKind.U16 -> "uint16_t"
                CAbiBridgeKind.I32 -> "int32_t"
                CAbiBridgeKind.U32 -> "uint32_t"
                CAbiBridgeKind.I64 -> "int64_t"
                CAbiBridgeKind.U64 -> "uint64_t"
                CAbiBridgeKind.F32 -> "float"
                CAbiBridgeKind.F64 -> "double"
                CAbiBridgeKind.CONST_CSTR -> "const char*"
                CAbiBridgeKind.MUT_CSTR -> "char*"
                CAbiBridgeKind.OPAQUE_PTR,
                CAbiBridgeKind.FILE_PTR,
                CAbiBridgeKind.DIR_PTR,
                CAbiBridgeKind.CALLBACK_PTR,
                CAbiBridgeKind.BY_VALUE_OBJECT,
                -> "void*"
            }

        private val UMBRELLA_LINK_LIBRARIES =
            setOf(
                "c",
                "m",
                "dl",
                "pthread",
                "rt",
                "System",
                "gcc",
                "gcc_s",
                "stdc++",
                "c++",
                "msvcrt",
                "ucrtbase",
                "libcmt",
                "legacy_stdio_definitions",
            )

        private fun extractLinkLibraryNamesFromLinkArgs(
            tokens: TokenStream,
            out: MutableSet<String>,
        ) {
            for (part in MacroRulesFileExpander.splitMacroArguments(tokens.clone())) {
                if (part.size >= 3 &&
                    (part[0] as? TokenTree.Ident)?.value?.toString() == "name" &&
                    (part[1] as? TokenTree.Punct)?.value?.asChar() == '=' &&
                    part[2] is TokenTree.Literal
                ) {
                    val lit = Lit.new((part[2] as TokenTree.Literal).value)
                    if (lit is Lit.Str) {
                        val libName = lit.value.value().trim()
                        if (libName.isNotEmpty()) {
                            out += libName
                        }
                    }
                }
            }
        }

        private fun isAtexitForeignFn(fn: ForeignFnInteropSpec): Boolean =
            fn.dllName == null &&
                !fn.isVariadic &&
                fn.cSymbolName == "atexit" &&
                fn.params.size == 1 &&
                fn.returnBridgeKind == CAbiBridgeKind.I32 &&
                (fn.params[0].bridgeKind == CAbiBridgeKind.CALLBACK_PTR || fn.params[0].bridgeKind == CAbiBridgeKind.OPAQUE_PTR)

        private fun androidLp64BaseSymbolFallback(fn: ForeignFnInteropSpec): String? {
            val sym = fn.cSymbolName
            if (sym.endsWith("64") && !sym.endsWith("base64") && sym.length > 2 && sym[sym.length - 3].isLetter()) {
                return sym.removeSuffix("64")
            }
            return null
        }

        private fun sanitizeLinkLibraryHandleIdent(libName: String): String {
            val raw =
                buildString {
                    for (ch in libName) {
                        append(if (ch.isLetterOrDigit()) ch else '_')
                    }
                }
            return if (raw.isEmpty() || raw[0].isDigit()) "lib_$raw" else raw
        }

        private fun appendCPosixDynamicLookupFallbacks(
            sb: StringBuilder,
            fn: ForeignFnInteropSpec,
        ) {
            val symEsc = escapeCString(fn.cSymbolName)
            val androidBaseSym = androidLp64BaseSymbolFallback(fn)
            if (androidBaseSym != null) {
                val baseEsc = escapeCString(androidBaseSym)
                sb.append("#if defined(__ANDROID__) && defined(__LP64__)\n")
                sb.append("        if (km_fn_ptr == NULL) {\n")
                sb.append("            km_fn_ptr = (km_fn_t)dlsym(RTLD_DEFAULT, \"$baseEsc\");\n")
                sb.append("        }\n")
                sb.append("#endif\n")
            }
            val linkLib = fn.linkLibrary
            if (linkLib != null) {
                val libIdent = sanitizeLinkLibraryHandleIdent(linkLib)
                val libEsc = escapeCString(linkLib)
                sb.append("#ifdef __APPLE__\n")
                sb.append("        if (km_fn_ptr == NULL) {\n")
                sb.append("            static void* km_h_$libIdent = NULL;\n")
                sb.append("            static bool km_h_${libIdent}_init = false;\n")
                sb.append("            if (!km_h_${libIdent}_init) {\n")
                sb.append("                km_h_${libIdent}_init = true;\n")
                sb.append("                km_h_$libIdent = dlopen(\"/usr/lib/lib$libEsc.dylib\", RTLD_LAZY | RTLD_LOCAL);\n")
                sb.append("            }\n")
                sb.append("            if (km_h_$libIdent != NULL) km_fn_ptr = (km_fn_t)dlsym(km_h_$libIdent, \"$symEsc\");\n")
                sb.append("        }\n")
                sb.append("#elif defined(__linux__) && !defined(__ANDROID__)\n")
                sb.append("        if (km_fn_ptr == NULL) {\n")
                sb.append("            static void* km_h_$libIdent = NULL;\n")
                sb.append("            static bool km_h_${libIdent}_init = false;\n")
                sb.append("            if (!km_h_${libIdent}_init) {\n")
                sb.append("                km_h_${libIdent}_init = true;\n")
                sb.append("                km_h_$libIdent = dlopen(\"lib$libEsc.so\", RTLD_LAZY | RTLD_LOCAL);\n")
                sb.append("            }\n")
                sb.append("            if (km_h_$libIdent != NULL) km_fn_ptr = (km_fn_t)dlsym(km_h_$libIdent, \"$symEsc\");\n")
                sb.append("        }\n")
                sb.append("#endif\n")
            }
        }

        private fun appendCppPosixDynamicLookupFallbacks(
            sb: StringBuilder,
            fn: ForeignFnInteropSpec,
        ) {
            val symEsc = escapeCString(fn.cSymbolName)
            val androidBaseSym = androidLp64BaseSymbolFallback(fn)
            if (androidBaseSym != null) {
                val baseEsc = escapeCString(androidBaseSym)
                sb.append("#if defined(__ANDROID__) && defined(__LP64__)\n")
                sb.append("        if (km_fn_ptr == nullptr) {\n")
                sb.append("            km_fn_ptr = reinterpret_cast<km_fn_t>(dlsym(RTLD_DEFAULT, \"$baseEsc\"));\n")
                sb.append("        }\n")
                sb.append("#endif\n")
            }
            val linkLib = fn.linkLibrary
            if (linkLib != null) {
                val libIdent = sanitizeLinkLibraryHandleIdent(linkLib)
                val libEsc = escapeCString(linkLib)
                sb.append("#ifdef __APPLE__\n")
                sb.append("        if (km_fn_ptr == nullptr) {\n")
                sb.append("            static void* km_h_$libIdent = nullptr;\n")
                sb.append("            static bool km_h_${libIdent}_init = false;\n")
                sb.append("            if (!km_h_${libIdent}_init) {\n")
                sb.append("                km_h_${libIdent}_init = true;\n")
                sb.append("                km_h_$libIdent = dlopen(\"/usr/lib/lib$libEsc.dylib\", RTLD_LAZY | RTLD_LOCAL);\n")
                sb.append("            }\n")
                sb.append("            if (km_h_$libIdent != nullptr) km_fn_ptr = reinterpret_cast<km_fn_t>(dlsym(km_h_$libIdent, \"$symEsc\"));\n")
                sb.append("        }\n")
                sb.append("#elif defined(__linux__) && !defined(__ANDROID__)\n")
                sb.append("        if (km_fn_ptr == nullptr) {\n")
                sb.append("            static void* km_h_$libIdent = nullptr;\n")
                sb.append("            static bool km_h_${libIdent}_init = false;\n")
                sb.append("            if (!km_h_${libIdent}_init) {\n")
                sb.append("                km_h_${libIdent}_init = true;\n")
                sb.append("                km_h_$libIdent = dlopen(\"lib$libEsc.so\", RTLD_LAZY | RTLD_LOCAL);\n")
                sb.append("            }\n")
                sb.append("            if (km_h_$libIdent != nullptr) km_fn_ptr = reinterpret_cast<km_fn_t>(dlsym(km_h_$libIdent, \"$symEsc\"));\n")
                sb.append("        }\n")
                sb.append("#endif\n")
            }
        }

        private fun cFailureReturnExpr(fn: ForeignFnInteropSpec): String {
            if (fn.returnBridgeKind == CAbiBridgeKind.I32 &&
                fn.cSymbolName.startsWith("pthread_") &&
                fn.cSymbolName != "pthread_equal"
            ) {
                return "ENOSYS"
            }
            return cDefaultFailureLiteral(fn.returnBridgeKind)
        }

        private fun cppFailureReturnExpr(fn: ForeignFnInteropSpec): String {
            val expr = cFailureReturnExpr(fn)
            return if (expr == "NULL") "nullptr" else expr
        }

        private fun cDefaultFailureLiteral(kind: CAbiBridgeKind): String =
            when (kind) {
                CAbiBridgeKind.VOID, CAbiBridgeKind.NEVER -> ""
                CAbiBridgeKind.BOOL -> "false"
                CAbiBridgeKind.U8, CAbiBridgeKind.U16, CAbiBridgeKind.U32, CAbiBridgeKind.U64 -> "0"
                CAbiBridgeKind.F32 -> "0.0f"
                CAbiBridgeKind.F64 -> "0.0"
                CAbiBridgeKind.CONST_CSTR,
                CAbiBridgeKind.MUT_CSTR,
                CAbiBridgeKind.OPAQUE_PTR,
                CAbiBridgeKind.FILE_PTR,
                CAbiBridgeKind.DIR_PTR,
                CAbiBridgeKind.CALLBACK_PTR,
                CAbiBridgeKind.BY_VALUE_OBJECT,
                -> "NULL"
                CAbiBridgeKind.I8, CAbiBridgeKind.I16, CAbiBridgeKind.I32, CAbiBridgeKind.I64 -> "-1"
            }

        private fun unwrapType(ty: SynType): SynType =
            when (ty) {
                is SynType.Group -> unwrapType(ty.elem)
                is SynType.Paren -> unwrapType(ty.elem)
                else -> ty
            }

        internal fun resolveLibcNamespace(headerContext: FileHeaderContext): String? {
            val fromCrate = headerContext.crateNamespaces["libc"]?.trim()?.removeSuffix(".")
            if (!fromCrate.isNullOrEmpty()) return fromCrate
            val fromProj = headerContext.projectNamespace?.trim()?.removeSuffix(".")
            return fromProj?.takeIf { it.isNotEmpty() }
        }

        internal fun extractLinkNameAttribute(attrs: List<Attribute>): String? {
            for (attr in attrs) {
                val meta = attr.meta
                if (meta is Meta.NameValue && meta.path.isIdent("link_name")) {
                    val expr = meta.value
                    if (expr is Expr.Lit && expr.lit is Lit.Str) {
                        return (expr.lit as Lit.Str).value.value()
                    }
                }
            }
            return null
        }

        private fun extractHeaderCommentsFromSource(source: String, out: MutableSet<String>) {
            for (line in source.lineSequence()) {
                val trimmed = line.trimStart()
                if (!trimmed.startsWith("//")) continue
                var idx = 2
                while (idx < trimmed.length && (trimmed[idx] == '!' || trimmed[idx] == '/' || trimmed[idx] == '*' || trimmed[idx].isWhitespace())) {
                    idx++
                }
                if (!trimmed.regionMatches(idx, "Header:", 0, 7)) continue
                idx += 7
                while (idx < trimmed.length && (trimmed[idx].isWhitespace() || trimmed[idx] == '`')) {
                    idx++
                }
                val start = idx
                while (idx < trimmed.length && !trimmed[idx].isWhitespace() && trimmed[idx] != '`' && trimmed[idx] != '*') {
                    idx++
                }
                val hdr = trimmed.substring(start, idx).trim()
                if (hdr.endsWith(".h") || hdr.endsWith(".hpp")) {
                    out += hdr
                }
            }
        }

        private fun extractIncludedHeadersFromPreambles(preambles: List<String>): Set<String> {
            val found = linkedSetOf<String>()
            for (chunk in preambles) {
                for (line in chunk.lineSequence()) {
                    val trimmed = line.trimStart()
                    if (!trimmed.startsWith('#')) continue
                    val afterHash = trimmed.substring(1).trimStart()
                    if (!afterHash.startsWith("include")) continue
                    val afterInclude = afterHash.substring(7).trimStart()
                    if (afterInclude.isEmpty()) continue
                    val open = afterInclude[0]
                    val close =
                        when (open) {
                            '<' -> '>'
                            '"' -> '"'
                            else -> continue
                        }
                    val closeIdx = afterInclude.indexOf(close, startIndex = 1)
                    if (closeIdx > 1) {
                        found += afterInclude.substring(1, closeIdx)
                    }
                }
            }
            return found
        }

        private fun formatIncludeTarget(hdr: String): String =
            if (hdr.startsWith('<') || hdr.startsWith('"')) hdr else "<$hdr>"

        private fun looksLikeCppCode(code: String): Boolean =
            code.contains("std::") ||
                code.contains("#include <iostream>") ||
                code.contains("#include <vector>") ||
                code.contains("#include <string>") ||
                code.contains("namespace ") ||
                code.contains("reinterpret_cast<") ||
                code.contains("static_cast<")

        private fun computeStemSnake(headerContext: FileHeaderContext): String {
            val rawRel = headerContext.portLintSourcePath
            if (!rawRel.isNullOrBlank()) {
                val fileName = rawRel.replace('\\', '/').substringAfterLast('/')
                val stem = fileName.removeSuffix(".rs").removeSuffix(".RS")
                if (stem != "mod" && stem != "lib") {
                    val clean = sanitizeCSymbolPart(stem)
                    if (clean.isNotEmpty()) return clean
                }
            }
            val pkgTail = headerContext.packageName?.substringAfterLast('.')?.takeIf { it.isNotEmpty() }
            return sanitizeCSymbolPart(pkgTail ?: "ffi")
        }

        private fun computeCInteropStemSnake(headerContext: FileHeaderContext): String {
            val stem = computeStemSnake(headerContext)
            if (headerContext.currentRelativeSourceDir.isNullOrEmpty() && !headerContext.portLintSourcePath.isNullOrBlank()) {
                return stem
            }
            val projNs = headerContext.projectNamespace?.trim()?.removeSuffix(".")
            val pkg = headerContext.packageName?.trim()?.removeSuffix(".")
            if (!projNs.isNullOrEmpty() && !pkg.isNullOrEmpty() && pkg.startsWith("$projNs.")) {
                val rel = pkg.removePrefix("$projNs.")
                val relSnake =
                    rel
                        .split('.')
                        .filter { it.isNotEmpty() }
                        .joinToString("_") { sanitizeCSymbolPart(it) }
                if (relSnake.isNotEmpty()) {
                    return if (stem.isNotEmpty() && relSnake != stem && !relSnake.endsWith("_$stem")) {
                        "${relSnake}_$stem"
                    } else {
                        relSnake
                    }
                }
            }
            return stem
        }

        private fun computeFfiModuleNameLower(headerContext: FileHeaderContext): String {
            val nsSegment =
                headerContext.projectNamespace
                    ?.substringAfterLast('.')
                    ?.takeIf { it.isNotEmpty() }
                    ?: headerContext.packageName
                        ?.substringAfterLast('.')
                        ?.takeIf { it.isNotEmpty() }
                    ?: "ffi"
            return sanitizeCSymbolPart(nsSegment).replace("_", "")
        }

        private fun computeCSymbolPrefix(headerContext: FileHeaderContext): String {
            val nsSegment =
                headerContext.projectNamespace
                    ?.substringAfterLast('.')
                    ?.takeIf { it.isNotEmpty() }
                    ?: headerContext.packageName
                        ?.substringAfterLast('.')
                        ?.takeIf { it.isNotEmpty() }
                    ?: "ffi"
            val stem = computeCInteropStemSnake(headerContext)
            val cleanNs = sanitizeCSymbolPart(nsSegment)
            return if (cleanNs == stem) cleanNs else "${cleanNs}_$stem"
        }

        private fun sanitizeCSymbolPart(raw: String): String {
            val sb = StringBuilder(raw.length + 4)
            for ((idx, ch) in raw.withIndex()) {
                when {
                    ch.isUpperCase() -> {
                        if (idx > 0 && raw[idx - 1].isLowerCase()) sb.append('_')
                        sb.append(ch.lowercaseChar())
                    }
                    ch.isLowerCase() || ch.isDigit() -> sb.append(ch)
                    ch == '_' || ch == '-' -> if (sb.isNotEmpty() && sb.last() != '_') sb.append('_')
                }
            }
            val trimmed = sb.toString().trim('_')
            if (trimmed.isEmpty()) return "fn"
            return if (trimmed.first().isDigit()) "n_$trimmed" else trimmed
        }

        private fun toPascalCaseIdentifierPart(raw: String): String {
            val sb = StringBuilder(raw.length)
            var capitalizeNext = true
            for (ch in raw) {
                when {
                    ch.isLetterOrDigit() -> {
                        if (capitalizeNext) {
                            sb.append(ch.uppercaseChar())
                            capitalizeNext = false
                        } else {
                            sb.append(ch)
                        }
                    }
                    else -> capitalizeNext = true
                }
            }
            val res = sb.toString()
            if (res.isEmpty()) return "Fn"
            return if (res.first().isDigit()) "N$res" else res
        }

        private fun toCamelCaseIdentifierPart(raw: String): String {
            val pascal = toPascalCaseIdentifierPart(raw)
            return pascal.replaceFirstChar { it.lowercaseChar() }
        }

        private fun sanitizeCParamIdentifier(raw: String, index: Int): String {
            val clean = toCamelCaseIdentifierPart(raw)
            return if (clean in C_RESERVED_PARAM_NAMES) {
                "arg${index}${toPascalCaseIdentifierPart(clean)}"
            } else {
                clean
            }
        }

        private val C_RESERVED_PARAM_NAMES: Set<String> =
            (
                "auto break case char const continue default do double else enum extern float for goto if " +
                    "inline int long register restrict return short signed sizeof static struct switch typedef union " +
                    "unsigned void volatile while class namespace template typename public private protected virtual operator " +
                    "new delete this friend using try catch throw bool true false alignas alignof constexpr decltype " +
                    "noexcept nullptr static_assert thread_local typeid dynamic_cast static_cast reinterpret_cast const_cast " +
                    "explicit mutable export wchar_t char8_t char16_t char32_t concept requires co_await co_return co_yield " +
                    "and or not xor bitand bitor compl and_eq or_eq xor_eq not_eq near far huge pascal cdecl stdcall fastcall " +
                    "interface small hyper environ errno stdin stdout stderr stat time read write open close accept bind " +
                    "connect listen recv send socket select poll fork pipe dup dup2 link unlink chmod chown " +
                    "lseek mkdir rmdir rename remove system abort exit free malloc calloc realloc div abs labs llabs " +
                    "sin cos tan exp log pow sqrt ceil floor index rindex bcopy bzero bcmp"
            ).split(' ').toSet()

        private fun jniMangleSegment(segment: String): String {
            val sb = StringBuilder(segment.length + 8)
            for (ch in segment) {
                when (ch) {
                    '_' -> sb.append("_1")
                    ';' -> sb.append("_2")
                    '[' -> sb.append("_3")
                    else -> sb.append(ch)
                }
            }
            return sb.toString()
        }

        private fun escapeCString(raw: String): String =
            buildString(raw.length + 4) {
                for (ch in raw) {
                    when (ch) {
                        '\\' -> append("\\\\")
                        '"' -> append("\\\"")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> append(ch)
                    }
                }
            }

        private fun conversionMethodForNumericKind(kind: NumericKind): String =
            when (kind) {
                NumericKind.I8 -> "toByte"
                NumericKind.U8 -> "toUByte"
                NumericKind.I16 -> "toShort"
                NumericKind.U16 -> "toUShort"
                NumericKind.I32 -> "toInt"
                NumericKind.U32 -> "toUInt"
                NumericKind.I64 -> "toLong"
                NumericKind.U64 -> "toULong"
                NumericKind.F32 -> "toFloat"
                NumericKind.F64 -> "toDouble"
                NumericKind.I128, NumericKind.U128 -> "toLong"
            }

        private fun defaultKotlinFallbackForBridge(
            kind: CAbiBridgeKind,
            kotlinType: String,
            resolveNumericKind: (String) -> NumericKind? = { null },
        ): String {
            val numKind = resolveNumericKind(kotlinType)
            if (numKind != null) {
                return when (numKind) {
                    NumericKind.I8 -> "(-1).toByte()"
                    NumericKind.U8 -> "0u.toUByte()"
                    NumericKind.I16 -> "(-1).toShort()"
                    NumericKind.U16 -> "0u.toUShort()"
                    NumericKind.I32 -> "-1"
                    NumericKind.U32 -> "0u"
                    NumericKind.I64 -> "-1L"
                    NumericKind.U64 -> "0uL"
                    NumericKind.F32 -> "0.0f"
                    NumericKind.F64 -> "0.0"
                    NumericKind.I128 -> "I128.ZERO"
                    NumericKind.U128 -> "U128.ZERO"
                }
            }
            return when (kind) {
                CAbiBridgeKind.VOID -> "Unit"
                CAbiBridgeKind.NEVER -> "error(\"unreachable\")"
                CAbiBridgeKind.BOOL -> "false"
                CAbiBridgeKind.I8 -> "(-1).toByte()"
                CAbiBridgeKind.U8 -> "0u.toUByte()"
                CAbiBridgeKind.I16 -> "(-1).toShort()"
                CAbiBridgeKind.U16 -> "0u.toUShort()"
                CAbiBridgeKind.I32 -> "-1"
                CAbiBridgeKind.U32 -> "0u"
                CAbiBridgeKind.I64 -> "-1L"
                CAbiBridgeKind.U64 -> "0uL"
                CAbiBridgeKind.F32 -> "0.0f"
                CAbiBridgeKind.F64 -> "0.0"
                CAbiBridgeKind.CONST_CSTR -> if (kotlinType == "Long") "0L" else "ConstPtr(0L)"
                CAbiBridgeKind.MUT_CSTR -> if (kotlinType == "Long") "0L" else "MutPtr(0L)"
                CAbiBridgeKind.OPAQUE_PTR,
                CAbiBridgeKind.FILE_PTR,
                CAbiBridgeKind.DIR_PTR,
                ->
                    when {
                        kotlinType == "Long" -> "0L"
                        kotlinType.substringAfterLast('.').startsWith("ConstPtr") -> "ConstPtr(0L)"
                        else -> "MutPtr(0L)"
                    }
                CAbiBridgeKind.CALLBACK_PTR ->
                    if (kotlinType.endsWith("?")) "null" else "$kotlinType { }"
                CAbiBridgeKind.BY_VALUE_OBJECT ->
                    if (kotlinType.endsWith("?")) "null" else "$kotlinType()"
            }
        }

        private fun extractIdentifierTokens(
            expr: String,
            includeBuiltins: Boolean = false,
        ): Set<String> {
            val out = linkedSetOf<String>()
            val sb = StringBuilder()
            for (ch in expr) {
                if (ch.isLetterOrDigit() || ch == '_') {
                    sb.append(ch)
                } else if (sb.isNotEmpty()) {
                    val tok = sb.toString()
                    sb.clear()
                    if (!tok.first().isDigit() && (includeBuiltins || tok !in BUILTIN_KOTLIN_IDENTS)) {
                        out += tok
                    }
                }
            }
            if (sb.isNotEmpty()) {
                val tok = sb.toString()
                if (!tok.first().isDigit() && (includeBuiltins || tok !in BUILTIN_KOTLIN_IDENTS)) {
                    out += tok
                }
            }
            return out
        }

        private val BUILTIN_KOTLIN_IDENTS: Set<String> =
            (
                "null true false Unit Nothing Boolean Byte UByte Short UShort Int UInt Long ULong Float Double " +
                    "String ZERO toByte toUByte toShort toUShort toInt toUInt toLong " +
                    "toULong toFloat toDouble error emptyArray intArrayOf longArrayOf byteArrayOf shortArrayOf " +
                    "floatArrayOf doubleArrayOf ubyteArrayOf ushortArrayOf uintArrayOf ulongArrayOf booleanArrayOf"
            ).split(' ').toSet()

        private fun containsCIdentifier(code: String, ident: String): Boolean {
            if (ident.isEmpty()) return false
            var idx = 0
            while (true) {
                val pos = code.indexOf(ident, idx)
                if (pos < 0) return false
                val beforeOk = pos == 0 || (!code[pos - 1].isLetterOrDigit() && code[pos - 1] != '_')
                val afterPos = pos + ident.length
                val afterOk = afterPos >= code.length || (!code[afterPos].isLetterOrDigit() && code[afterPos] != '_')
                if (beforeOk && afterOk) return true
                idx = pos + 1
            }
        }

        private fun extractGroupInnerSource(source: String, group: TokenTree.Group): String {
            val openEnd = group.value.spanOpen().endExclusiveOffset()
            val closeStart = group.value.spanClose().startOffset()
            if (openEnd >= 0 && closeStart >= openEnd && closeStart <= source.length) {
                return source.substring(openEnd, closeStart)
            }
            return group.value.stream().toString()
        }

        private fun extractStreamSource(source: String, trees: List<TokenTree>): String {
            if (trees.isEmpty()) return ""
            val start = tokenStartOffset(trees.first())
            val end = tokenEndOffset(trees.last())
            if (start >= 0 && end >= start && end <= source.length) {
                return source.substring(start, end)
            }
            return TokenStream.fromTokenTrees(trees).toString()
        }

        private fun tokenStartOffset(tt: TokenTree): Int =
            when (tt) {
                is TokenTree.Group -> tt.value.spanOpen().startOffset()
                is TokenTree.Ident -> tt.value.span().startOffset()
                is TokenTree.Punct -> tt.value.span().startOffset()
                is TokenTree.Literal -> tt.value.span().startOffset()
            }

        private fun tokenEndOffset(tt: TokenTree): Int =
            when (tt) {
                is TokenTree.Group -> tt.value.spanClose().endExclusiveOffset()
                is TokenTree.Ident -> tt.value.span().endExclusiveOffset()
                is TokenTree.Punct -> tt.value.span().endExclusiveOffset()
                is TokenTree.Literal -> tt.value.span().endExclusiveOffset()
            }

        private fun parseInlineCMacro(
            source: String,
            tokens: TokenStream,
            isCpp: Boolean,
        ): ParsedInlineCMacro? {
            val list = tokens.clone().toList()
            if (list.isEmpty()) return null
            var cursor = 0
            if (list[cursor] is TokenTree.Ident && (list[cursor] as TokenTree.Ident).value.toString() == "unsafe") {
                cursor++
            }
            val captureArgs = mutableListOf<InlineCaptureArg>()
            if (cursor < list.size &&
                list[cursor] is TokenTree.Group &&
                (list[cursor] as TokenTree.Group).value.delimiter() == Delimiter.Bracket
            ) {
                val bracketGroup = list[cursor] as TokenTree.Group
                val parts = MacroRulesFileExpander.splitMacroArguments(bracketGroup.value.stream())
                for (part in parts) {
                    parseSingleCaptureArg(part)?.let { captureArgs += it }
                }
                cursor++
            }

            var returnRustType: SynType? = null
            var explicitCReturnType: String? = null
            if (cursor + 1 < list.size &&
                (list[cursor] as? TokenTree.Punct)?.value?.asChar() == '-' &&
                (list[cursor + 1] as? TokenTree.Punct)?.value?.asChar() == '>'
            ) {
                cursor += 2
                val bodyGroupIdx =
                    (cursor until list.size).lastOrNull { idx ->
                        list[idx] is TokenTree.Group && (list[idx] as TokenTree.Group).value.delimiter() == Delimiter.Brace
                    } ?: return null
                val headerSlice = list.subList(cursor, bodyGroupIdx)
                val asIdx =
                    headerSlice.indexOfFirst { tt ->
                        tt is TokenTree.Ident && tt.value.toString() == "as"
                    }
                val rustTyTokens = if (asIdx >= 0) headerSlice.subList(0, asIdx) else headerSlice
                if (rustTyTokens.isNotEmpty()) {
                    returnRustType =
                        io.github.kotlinmania.syn
                            .parse2(SynType.Companion::parse, TokenStream.fromTokenTrees(rustTyTokens))
                            .getOrNull()
                }
                if (asIdx >= 0 && asIdx + 1 < headerSlice.size) {
                    val cLit = headerSlice[asIdx + 1] as? TokenTree.Literal
                    if (cLit != null) {
                        val parsedLit = Lit.new(cLit.value)
                        if (parsedLit is Lit.Str) {
                            explicitCReturnType = parsedLit.value.value()
                        }
                    }
                }
                cursor = bodyGroupIdx
            }

            if (cursor >= list.size) return null
            val lastTree = list[cursor]
            val rawBody =
                if (lastTree is TokenTree.Group && lastTree.value.delimiter() == Delimiter.Brace) {
                    extractGroupInnerSource(source, lastTree)
                } else {
                    extractStreamSource(source, list.subList(cursor, list.size))
                }
            return ParsedInlineCMacro(
                captureArgs = captureArgs,
                returnRustType = returnRustType,
                explicitCReturnType = explicitCReturnType,
                rawBody = rawBody,
                isCpp = isCpp,
            )
        }

        private fun parseSingleCaptureArg(tokens: List<TokenTree>): InlineCaptureArg? {
            if (tokens.isEmpty()) return null
            var idx = 0
            var isMut = false
            if (tokens[idx] is TokenTree.Ident && (tokens[idx] as TokenTree.Ident).value.toString() == "mut") {
                isMut = true
                idx++
            }
            if (idx >= tokens.size) return null
            val identTree = tokens[idx] as? TokenTree.Ident ?: return null
            val rawIdent = identTree.value.toString()
            idx++

            var rustType: SynType? = null
            var explicitCType: String? = null
            val asIdx =
                (idx until tokens.size).firstOrNull { i ->
                    tokens[i] is TokenTree.Ident && (tokens[i] as TokenTree.Ident).value.toString() == "as"
                }
            val typeEnd = asIdx ?: tokens.size
            if (idx < typeEnd && (tokens[idx] as? TokenTree.Punct)?.value?.asChar() == ':') {
                val tySlice = tokens.subList(idx + 1, typeEnd)
                if (tySlice.isNotEmpty()) {
                    rustType =
                        io.github.kotlinmania.syn
                            .parse2(SynType.Companion::parse, TokenStream.fromTokenTrees(tySlice))
                            .getOrNull()
                }
            }
            if (asIdx != null && asIdx + 1 < tokens.size) {
                val litTree = tokens[asIdx + 1] as? TokenTree.Literal
                if (litTree != null) {
                    val lit = Lit.new(litTree.value)
                    if (lit is Lit.Str) {
                        explicitCType = lit.value.value()
                    }
                }
            }
            return InlineCaptureArg(
                rawRustIdent = rawIdent,
                isMut = isMut,
                rustType = rustType,
                explicitCType = explicitCType,
            )
        }

        private fun inferRequiredHeaders(
            foreignFunctions: List<ForeignFnInteropSpec>,
            inlineFunctions: List<InlineCFunctionSpec>,
            explicitHeaders: Set<String>,
            preambleIncludes: Set<String>,
        ): List<PlatformHeaderSpec> {
            val specs = mutableListOf<PlatformHeaderSpec>()
            for (hdr in explicitHeaders) {
                specs += classifyHeaderGuard(hdr)
            }
            for (fn in foreignFunctions) {
                specs.addAll(headersForCSymbol(fn.cSymbolName))
                if (fn.returnBridgeKind == CAbiBridgeKind.FILE_PTR || fn.params.any { it.bridgeKind == CAbiBridgeKind.FILE_PTR }) {
                    specs += PlatformHeaderSpec("stdio.h")
                }
                if (fn.returnBridgeKind == CAbiBridgeKind.DIR_PTR || fn.params.any { it.bridgeKind == CAbiBridgeKind.DIR_PTR }) {
                    specs += PlatformHeaderSpec("dirent.h", guardCondition = "!defined(_WIN32)")
                }
            }
            for (fn in inlineFunctions) {
                for ((sym, hdrList) in C_SYMBOL_HEADERS) {
                    if (containsCIdentifier(fn.rawBody, sym)) {
                        specs.addAll(hdrList)
                    }
                }
            }
            return specs.filter { it.header !in preambleIncludes }.distinct()
        }

        private fun classifyHeaderGuard(header: String): PlatformHeaderSpec {
            val target = formatIncludeTarget(header)
            return when {
                header in PORTABLE_STD_HEADERS -> PlatformHeaderSpec(header)
                header.startsWith("linux/") || header == "sys/epoll.h" || header == "sys/eventfd.h" || header == "sys/signalfd.h" ->
                    PlatformHeaderSpec(header, guardCondition = "defined(__linux__)")
                header == "sys/event.h" || header.startsWith("mach/") ->
                    PlatformHeaderSpec(
                        header,
                        guardCondition = "defined(__APPLE__) || defined(__FreeBSD__) || defined(__NetBSD__) || defined(__OpenBSD__)",
                    )
                header == "windows.h" || header == "winsock2.h" || header == "ws2tcpip.h" || header == "io.h" || header == "direct.h" ->
                    PlatformHeaderSpec(header, guardCondition = "defined(_WIN32)")
                header in COMMON_POSIX_HEADERS ->
                    PlatformHeaderSpec(header, guardCondition = "!defined(_WIN32)")
                else ->
                    PlatformHeaderSpec(header, guardCondition = "!defined(_WIN32) && defined(__has_include) && __has_include($target)")
            }
        }

        private fun symbolPlatformGuard(cSymbol: String): String? =
            when {
                cSymbol in LINUX_ONLY_SYMBOLS -> "defined(__linux__)"
                cSymbol in BSD_APPLE_ONLY_SYMBOLS ->
                    "defined(__APPLE__) || defined(__FreeBSD__) || defined(__NetBSD__) || defined(__OpenBSD__)"
                cSymbol in POSIX_NON_WIN32_SYMBOLS -> "!defined(_WIN32)"
                else -> null
            }

        private fun headersForCSymbol(symbol: String): List<PlatformHeaderSpec> =
            C_SYMBOL_HEADERS[symbol].orEmpty()

        private val PORTABLE_STD_HEADERS =
            setOf(
                "assert.h",
                "ctype.h",
                "errno.h",
                "float.h",
                "inttypes.h",
                "limits.h",
                "locale.h",
                "math.h",
                "setjmp.h",
                "signal.h",
                "stdarg.h",
                "stdbool.h",
                "stddef.h",
                "stdint.h",
                "stdio.h",
                "stdlib.h",
                "string.h",
                "time.h",
                "wchar.h",
                "wctype.h",
                "fcntl.h",
                "sys/types.h",
                "sys/stat.h",
            )

        private val COMMON_POSIX_HEADERS =
            setOf(
                "unistd.h",
                "sys/mman.h",
                "sys/socket.h",
                "netinet/in.h",
                "arpa/inet.h",
                "netdb.h",
                "dlfcn.h",
                "pthread.h",
                "semaphore.h",
                "poll.h",
                "sys/ioctl.h",
                "sys/wait.h",
                "dirent.h",
                "sched.h",
                "strings.h",
                "utime.h",
                "sys/time.h",
                "sys/resource.h",
                "sys/uio.h",
                "sys/un.h",
                "sys/utsname.h",
                "termios.h",
                "pwd.h",
                "grp.h",
                "syslog.h",
                "fnmatch.h",
                "glob.h",
            )

        private val LINUX_ONLY_SYMBOLS =
            setOf(
                "epoll_create",
                "epoll_create1",
                "epoll_ctl",
                "epoll_wait",
                "eventfd",
                "signalfd",
                "timerfd_create",
                "timerfd_settime",
                "timerfd_gettime",
                "memfd_create",
                "prctl",
            )

        private val BSD_APPLE_ONLY_SYMBOLS =
            setOf(
                "kqueue",
                "kevent",
            )

        private val POSIX_NON_WIN32_SYMBOLS =
            setOf(
                "fork",
                "pipe",
                "link",
                "symlink",
                "readlink",
                "chown",
                "fchown",
                "getuid",
                "geteuid",
                "getgid",
                "getegid",
                "setenv",
                "unsetenv",
                "mmap",
                "munmap",
                "mprotect",
                "msync",
                "mlock",
                "munlock",
                "dlopen",
                "dlsym",
                "dlclose",
                "dlerror",
                "pthread_self",
                "pthread_equal",
                "pthread_detach",
                "pthread_join",
                "pthread_kill",
                "sem_init",
                "sem_destroy",
                "sem_wait",
                "sem_trywait",
                "sem_post",
                "poll",
                "ioctl",
                "wait",
                "waitpid",
                "opendir",
                "readdir",
                "closedir",
                "rewinddir",
                "chmod",
                "fchmod",
                "lstat",
                "mkfifo",
                "mknod",
                "sysconf",
                "sleep",
                "usleep",
                "strndup",
                "aligned_alloc",
                "fcntl",
                "openat",
                "fsync",
                "ftruncate",
                "truncate",
                "kill",
                "socketpair",
            )

        private fun isDirectCallSafeSymbol(fn: ForeignFnInteropSpec): Boolean {
            if (fn.dllName != null) return false
            val expectedArity = DIRECT_C_SYMBOL_ARITY[fn.cSymbolName] ?: return false
            return fn.params.size == expectedArity
        }

        private val DIRECT_C_SYMBOL_ARITY: Map<String, Int> =
            buildMap {
                fun reg(arity: Int, names: String) {
                    for (name in names.split(' ')) {
                        if (name.isNotEmpty()) put(name, arity)
                    }
                }
                reg(0, "abort rand getchar tmpfile clock localeconv getpid geteuid getuid getegid getgid dlerror kqueue")
                reg(
                    1,
                    "malloc free exit atexit getenv abs llabs atoi atol atoll atof srand fclose fflush fgetc " +
                        "getc putchar puts ftell rewind clearerr feof ferror perror remove strlen strerror strdup " +
                        "isalnum isalpha isblank iscntrl isdigit isgraph islower isprint ispunct isspace isupper isxdigit " +
                        "tolower toupper sin cos tan asin acos atan exp log log10 sqrt ceil floor fabs round trunc time " +
                        "mktime gmtime localtime asctime ctime raise umask close dup pipe chdir unlink rmdir isatty sleep " +
                        "usleep fsync sysconf unsetenv dlclose " +
                        "wait opendir readdir closedir rewinddir epoll_create epoll_create1",
                )
                reg(
                    2,
                    "calloc realloc strtod strtof fopen setbuf fputc fputs ungetc rename strcpy strcat " +
                        "strcmp strcoll strchr strrchr strcspn strspn strpbrk strstr strtok strnlen strndup atan2 pow " +
                        "fmod difftime kill setlocale creat stat fstat lstat chmod fchmod mkfifo dup2 getcwd " +
                        "access ftruncate truncate link symlink munmap mlock munlock listen shutdown dlopen dlsym",
                )
                reg(
                    3,
                    "strtol strtoll strtoul strtoull freopen fgets fseek memcpy memmove memset memcmp memchr strncpy " +
                        "strncat strncmp strxfrm mknod read write lseek readlink chown fchown setenv mprotect msync " +
                        "socket bind accept connect getsockname getpeername poll waitpid",
                )
                reg(4, "qsort setvbuf fread fwrite strftime send recv socketpair epoll_ctl epoll_wait")
                reg(5, "bsearch setsockopt getsockopt")
                reg(6, "mmap sendto recvfrom kevent")
            }

        private val C_SYMBOL_HEADERS: Map<String, List<PlatformHeaderSpec>> =
            buildMap {
                fun register(headers: List<PlatformHeaderSpec>, vararg symbols: String) {
                    for (sym in symbols) {
                        put(sym, headers)
                    }
                }
                register(
                    listOf(PlatformHeaderSpec("stdlib.h")),
                    "malloc",
                    "calloc",
                    "realloc",
                    "free",
                    "aligned_alloc",
                    "abort",
                    "exit",
                    "atexit",
                    "getenv",
                    "system",
                    "bsearch",
                    "qsort",
                    "abs",
                    "labs",
                    "llabs",
                    "div",
                    "ldiv",
                    "lldiv",
                    "atoi",
                    "atol",
                    "atoll",
                    "atof",
                    "strtod",
                    "strtof",
                    "strtol",
                    "strtoll",
                    "strtoul",
                    "strtoull",
                    "rand",
                    "srand",
                )
                register(
                    listOf(PlatformHeaderSpec("stdio.h")),
                    "fopen",
                    "freopen",
                    "fclose",
                    "fflush",
                    "setbuf",
                    "setvbuf",
                    "fread",
                    "fwrite",
                    "fgetc",
                    "fgets",
                    "fputc",
                    "fputs",
                    "getc",
                    "getchar",
                    "putchar",
                    "puts",
                    "ungetc",
                    "fseek",
                    "ftell",
                    "rewind",
                    "clearerr",
                    "feof",
                    "ferror",
                    "perror",
                    "remove",
                    "rename",
                    "tmpfile",
                    "tmpnam",
                    "snprintf",
                )
                register(
                    listOf(PlatformHeaderSpec("string.h")),
                    "memcpy",
                    "memmove",
                    "memset",
                    "memcmp",
                    "memchr",
                    "strcpy",
                    "strncpy",
                    "strcat",
                    "strncat",
                    "strcmp",
                    "strncmp",
                    "strcoll",
                    "strxfrm",
                    "strchr",
                    "strrchr",
                    "strcspn",
                    "strspn",
                    "strpbrk",
                    "strstr",
                    "strtok",
                    "strlen",
                    "strnlen",
                    "strerror",
                    "strdup",
                    "strndup",
                )
                register(
                    listOf(PlatformHeaderSpec("ctype.h")),
                    "isalnum",
                    "isalpha",
                    "isblank",
                    "iscntrl",
                    "isdigit",
                    "isgraph",
                    "islower",
                    "isprint",
                    "ispunct",
                    "isspace",
                    "isupper",
                    "isxdigit",
                    "tolower",
                    "toupper",
                )
                register(
                    listOf(PlatformHeaderSpec("math.h")),
                    "sin",
                    "cos",
                    "tan",
                    "asin",
                    "acos",
                    "atan",
                    "atan2",
                    "exp",
                    "log",
                    "log10",
                    "pow",
                    "sqrt",
                    "ceil",
                    "floor",
                    "fabs",
                    "fmod",
                    "round",
                    "trunc",
                )
                register(
                    listOf(PlatformHeaderSpec("time.h")),
                    "clock",
                    "time",
                    "difftime",
                    "mktime",
                    "strftime",
                    "gmtime",
                    "localtime",
                    "asctime",
                    "ctime",
                )
                register(listOf(PlatformHeaderSpec("signal.h")), "signal", "raise", "kill")
                register(listOf(PlatformHeaderSpec("locale.h")), "setlocale", "localeconv")
                register(listOf(PlatformHeaderSpec("fcntl.h")), "open", "creat", "fcntl", "openat")
                register(
                    listOf(PlatformHeaderSpec("sys/types.h"), PlatformHeaderSpec("sys/stat.h")),
                    "stat",
                    "fstat",
                    "lstat",
                    "mkdir",
                    "chmod",
                    "fchmod",
                    "umask",
                    "mkfifo",
                    "mknod",
                )
                register(
                    listOf(
                        PlatformHeaderSpec(
                            header = "unistd.h",
                            guardCondition = "!defined(_WIN32)",
                            windowsFallbackHeaders = listOf("io.h", "process.h", "direct.h"),
                        ),
                    ),
                    "read",
                    "write",
                    "close",
                    "lseek",
                    "dup",
                    "dup2",
                    "pipe",
                    "chdir",
                    "getcwd",
                    "unlink",
                    "rmdir",
                    "access",
                    "isatty",
                    "getpid",
                    "sleep",
                    "usleep",
                    "fsync",
                    "ftruncate",
                    "truncate",
                    "link",
                    "symlink",
                    "readlink",
                    "chown",
                    "fchown",
                    "geteuid",
                    "getuid",
                    "getegid",
                    "getgid",
                    "fork",
                    "sysconf",
                )
                register(
                    listOf(PlatformHeaderSpec("stdlib.h"), PlatformHeaderSpec("unistd.h", guardCondition = "!defined(_WIN32)")),
                    "setenv",
                    "unsetenv",
                )
                register(
                    listOf(PlatformHeaderSpec("sys/mman.h", guardCondition = "!defined(_WIN32)")),
                    "mmap",
                    "munmap",
                    "mprotect",
                    "msync",
                    "mlock",
                    "munlock",
                )
                register(
                    listOf(
                        PlatformHeaderSpec(
                            header = "sys/socket.h",
                            guardCondition = "!defined(_WIN32)",
                            windowsFallbackHeaders = listOf("winsock2.h", "ws2tcpip.h"),
                        ),
                    ),
                    "socket",
                    "bind",
                    "listen",
                    "accept",
                    "connect",
                    "send",
                    "recv",
                    "sendto",
                    "recvfrom",
                    "setsockopt",
                    "getsockopt",
                    "shutdown",
                    "getsockname",
                    "getpeername",
                    "socketpair",
                )
                register(
                    listOf(PlatformHeaderSpec("dlfcn.h", guardCondition = "!defined(_WIN32)")),
                    "dlopen",
                    "dlsym",
                    "dlclose",
                    "dlerror",
                )
                register(
                    listOf(PlatformHeaderSpec("pthread.h", guardCondition = "!defined(_WIN32)")),
                    "pthread_create",
                    "pthread_join",
                    "pthread_detach",
                    "pthread_self",
                    "pthread_equal",
                    "pthread_kill",
                )
                register(
                    listOf(PlatformHeaderSpec("semaphore.h", guardCondition = "!defined(_WIN32)")),
                    "sem_init",
                    "sem_destroy",
                    "sem_wait",
                    "sem_trywait",
                    "sem_post",
                )
                register(listOf(PlatformHeaderSpec("poll.h", guardCondition = "!defined(_WIN32)")), "poll")
                register(listOf(PlatformHeaderSpec("sys/ioctl.h", guardCondition = "!defined(_WIN32)")), "ioctl")
                register(listOf(PlatformHeaderSpec("sys/wait.h", guardCondition = "!defined(_WIN32)")), "wait", "waitpid")
                register(
                    listOf(PlatformHeaderSpec("dirent.h", guardCondition = "!defined(_WIN32)")),
                    "opendir",
                    "readdir",
                    "closedir",
                    "rewinddir",
                )
                register(
                    listOf(PlatformHeaderSpec("sys/epoll.h", guardCondition = "defined(__linux__)")),
                    "epoll_create",
                    "epoll_create1",
                    "epoll_ctl",
                    "epoll_wait",
                )
                register(
                    listOf(
                        PlatformHeaderSpec(
                            "sys/event.h",
                            guardCondition = "defined(__APPLE__) || defined(__FreeBSD__) || defined(__NetBSD__) || defined(__OpenBSD__)",
                        ),
                    ),
                    "kqueue",
                    "kevent",
                )
            }
    }
}

internal fun ktlintImportSortBucket(rawImportSpec: String): Int {
    val clean = rawImportSpec.removePrefix("import ").trim().replace("`", "")
    return when {
        " as " in clean -> 4
        clean.startsWith("java.") -> 1
        clean.startsWith("javax.") -> 2
        clean.startsWith("kotlin.") -> 3
        else -> 0
    }
}

internal fun sortImportsForKtlint(imports: Collection<String>): List<String> =
    imports.sortedWith(
        compareBy<String>(
            { ktlintImportSortBucket(it) },
            { it.removePrefix("import ").trim().replace("`", "") },
        ),
    )
