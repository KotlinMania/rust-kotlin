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

internal data class PlatformHeaderSpec(
    val header: String,
    val guardCondition: String? = null,
    val windowsFallbackHeaders: List<String> = emptyList(),
)

internal data class CAbiBridgeDescriptor(
    val cType: String,
    val cFailureLiteral: String,
    val numericKind: NumericKind? = null,
    val defaultReturnConv: String? = null,
    val wasmWasiScalarType: String? = null,
    val wasmJsType: String? = null,
    val jniKotlinType: String,
    val jniCType: String,
    val isPointerAddress: Boolean = false,
)

internal val C_ABI_BRIDGE_DESCRIPTORS: Map<CAbiBridgeKind, CAbiBridgeDescriptor> =
    mapOf(
        CAbiBridgeKind.VOID to CAbiBridgeDescriptor("void", "", wasmWasiScalarType = "Unit", wasmJsType = "Unit", jniKotlinType = "Unit", jniCType = "void"),
        CAbiBridgeKind.NEVER to CAbiBridgeDescriptor("void", "", wasmWasiScalarType = "Unit", wasmJsType = "Unit", jniKotlinType = "Unit", jniCType = "void"),
        CAbiBridgeKind.BOOL to CAbiBridgeDescriptor("bool", "false", wasmWasiScalarType = "Int", wasmJsType = "Boolean", jniKotlinType = "Boolean", jniCType = "jboolean"),
        CAbiBridgeKind.I8 to CAbiBridgeDescriptor("int8_t", "-1", NumericKind.I8, "toByte", "Int", "Int", "Byte", "jbyte"),
        CAbiBridgeKind.U8 to CAbiBridgeDescriptor("uint8_t", "0", NumericKind.U8, "toUByte", "Int", "Int", "Byte", "jbyte"),
        CAbiBridgeKind.I16 to CAbiBridgeDescriptor("int16_t", "-1", NumericKind.I16, "toShort", "Int", "Int", "Short", "jshort"),
        CAbiBridgeKind.U16 to CAbiBridgeDescriptor("uint16_t", "0", NumericKind.U16, "toUShort", "Int", "Int", "Short", "jshort"),
        CAbiBridgeKind.I32 to CAbiBridgeDescriptor("int32_t", "-1", NumericKind.I32, null, "Int", "Int", "Int", "jint"),
        CAbiBridgeKind.U32 to CAbiBridgeDescriptor("uint32_t", "0", NumericKind.U32, "toUInt", "Int", "Int", "Int", "jint"),
        CAbiBridgeKind.I64 to CAbiBridgeDescriptor("int64_t", "-1", NumericKind.I64, null, "Long", null, "Long", "jlong"),
        CAbiBridgeKind.U64 to CAbiBridgeDescriptor("uint64_t", "0", NumericKind.U64, "toULong", "Long", null, "Long", "jlong"),
        CAbiBridgeKind.F32 to CAbiBridgeDescriptor("float", "0.0f", NumericKind.F32, null, "Float", "Double", "Float", "jfloat"),
        CAbiBridgeKind.F64 to CAbiBridgeDescriptor("double", "0.0", NumericKind.F64, null, "Double", "Double", "Double", "jdouble"),
        CAbiBridgeKind.CONST_CSTR to CAbiBridgeDescriptor("const char*", "NULL", jniKotlinType = "Long", jniCType = "jlong", isPointerAddress = true),
        CAbiBridgeKind.MUT_CSTR to CAbiBridgeDescriptor("char*", "NULL", jniKotlinType = "Long", jniCType = "jlong", isPointerAddress = true),
        CAbiBridgeKind.OPAQUE_PTR to CAbiBridgeDescriptor("void*", "NULL", jniKotlinType = "Long", jniCType = "jlong", isPointerAddress = true),
        CAbiBridgeKind.FILE_PTR to CAbiBridgeDescriptor("void*", "NULL", jniKotlinType = "Long", jniCType = "jlong", isPointerAddress = true),
        CAbiBridgeKind.DIR_PTR to CAbiBridgeDescriptor("void*", "NULL", jniKotlinType = "Long", jniCType = "jlong", isPointerAddress = true),
        CAbiBridgeKind.CALLBACK_PTR to CAbiBridgeDescriptor("void*", "NULL", jniKotlinType = "Long", jniCType = "jlong", isPointerAddress = true),
        CAbiBridgeKind.BY_VALUE_OBJECT to CAbiBridgeDescriptor("void*", "NULL", wasmJsType = "Unit", jniKotlinType = "Long", jniCType = "jlong"),
    )

internal fun bridgeDescriptorFor(kind: CAbiBridgeKind): CAbiBridgeDescriptor =
    C_ABI_BRIDGE_DESCRIPTORS.getValue(kind)

internal fun cHeaderTypeForBridge(kind: CAbiBridgeKind): String =
    bridgeDescriptorFor(kind).cType

internal fun cHeaderTypeForParam(param: InteropParamSpec): String =
    cHeaderTypeForBridge(param.bridgeKind)

internal fun cHeaderTypeForReturn(fn: ForeignFnInteropSpec): String =
    cHeaderTypeForBridge(fn.returnBridgeKind)

internal fun cHeaderTypeForInlineReturn(fn: InlineCFunctionSpec): String =
    cHeaderTypeForBridge(fn.returnBridgeKind)

internal fun cTypeForBridge(kind: CAbiBridgeKind): String =
    bridgeDescriptorFor(kind).cType

internal fun cDefaultFailureLiteral(kind: CAbiBridgeKind): String =
    bridgeDescriptorFor(kind).cFailureLiteral

internal fun requiresNoStringConversion(params: List<InteropParamSpec>): Boolean =
    params.any { it.bridgeKind == CAbiBridgeKind.MUT_CSTR } ||
        params.count {
            it.bridgeKind == CAbiBridgeKind.CONST_CSTR &&
                (it.kotlinType == "String?" || it.kotlinType == "String")
        } > 1 ||
        params.any {
            it.bridgeKind == CAbiBridgeKind.CONST_CSTR &&
                it.kotlinType != "String?" && it.kotlinType != "String"
        }

internal fun requiresNoStringConversionForInline(params: List<InteropParamSpec>): Boolean =
    requiresNoStringConversion(params) ||
        params.any { it.bridgeKind == CAbiBridgeKind.MUT_CSTR }

internal fun appendVariadicKotlinArgs(
    baseArgs: List<String>,
    stringifyArgs: Boolean,
): List<String> =
    baseArgs +
        listOf("variadicArgs.size.coerceAtMost(4)") +
        (0 until 4).map { idx ->
            val raw = "if (variadicArgs.size > $idx) variadicArgs[$idx] else 0L"
            if (stringifyArgs) "($raw).toString()" else raw
        }

internal fun renderActualFunctionDecl(
    visibilityPrefix: String,
    kotlinName: String,
    params: List<InteropParamSpec>,
    kotlinReturnType: String,
    returnBridgeKind: CAbiBridgeKind,
    isVariadic: Boolean,
    rawCall: String,
    bodyExpr: String,
    addonAvailabilityGuard: String? = null,
): String {
    val baseParams = params.map { "${it.kotlinName}: ${it.kotlinType}" }
    val paramsSig =
        (if (isVariadic) baseParams + "vararg variadicArgs: Long" else baseParams)
            .joinToString(", ")
    val retSuffix = if (returnBridgeKind == CAbiBridgeKind.VOID) "" else ": $kotlinReturnType"
    if (addonAvailabilityGuard != null) {
        return when {
            returnBridgeKind == CAbiBridgeKind.VOID ->
                "${visibilityPrefix}actual fun $kotlinName($paramsSig) {\n" +
                    "    if ($addonAvailabilityGuard) {\n" +
                    "        $rawCall\n" +
                    "    } else {\n" +
                    "        throw UnsupportedOperationException(\"Native FFI addon is not available\")\n" +
                    "    }\n" +
                    "}"
            returnBridgeKind == CAbiBridgeKind.NEVER || kotlinReturnType == "Nothing" ->
                "${visibilityPrefix}actual fun $kotlinName($paramsSig): Nothing {\n" +
                    "    if ($addonAvailabilityGuard) {\n" +
                    "        $rawCall\n" +
                    "    } else {\n" +
                    "        throw UnsupportedOperationException(\"Native FFI addon is not available\")\n" +
                    "    }\n" +
                    "    error(\"Unreachable noreturn C function $kotlinName\")\n" +
                    "}"
            else ->
                "${visibilityPrefix}actual fun $kotlinName($paramsSig)$retSuffix =\n" +
                    "    if ($addonAvailabilityGuard) $bodyExpr else throw UnsupportedOperationException(\"Native FFI addon is not available\")"
        }
    }
    return when {
        returnBridgeKind == CAbiBridgeKind.VOID ->
            "${visibilityPrefix}actual fun $kotlinName($paramsSig) {\n    $bodyExpr\n}"
        returnBridgeKind == CAbiBridgeKind.NEVER ->
            "${visibilityPrefix}actual fun $kotlinName($paramsSig): Nothing {\n    $rawCall\n    error(\"Unreachable noreturn C function $kotlinName\")\n}"
        else ->
            "${visibilityPrefix}actual fun $kotlinName($paramsSig)$retSuffix =\n    $bodyExpr"
    }
}

internal fun napiRelativeCandidatesJsArray(ffiModName: String): String {
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

internal fun buildNodeNapiLoaderJsBody(
    ffiModName: String,
    returnBooleanStatus: Boolean,
): String {
    val relsJs = napiRelativeCandidatesJsArray(ffiModName)
    val globalProp = "globalThis.__km_napi_$ffiModName"
    val sb = StringBuilder()
    if (returnBooleanStatus) {
        sb.append("if (typeof $globalProp !== 'undefined') return $globalProp !== null; ")
        sb.append("if (typeof process === 'undefined' || !process.versions || !process.versions.node) { $globalProp = null; return false; } ")
    } else {
        sb.append("if (typeof globalThis !== 'undefined' && typeof $globalProp !== 'undefined') return $globalProp; ")
        sb.append("if (typeof process === 'undefined' || !process.versions || !process.versions.node) return null; ")
    }
    sb.append(
        "var mod = process.getBuiltinModule ? process.getBuiltinModule('module') : (process.mainModule && process.mainModule.constructor); " +
            "var req = (mod && mod.createRequire) ? mod.createRequire((process.cwd ? process.cwd() : '.') + '/package.json') : (process.mainModule ? process.mainModule.require : null); ",
    )
    if (returnBooleanStatus) {
        sb.append("if (!req) { $globalProp = null; return false; } ")
        sb.append("try { $globalProp = req('@kotlinmania/$ffiModName-native-bindings'); return true; } catch (_) {} ")
    } else {
        sb.append("if (!req) return null; ")
        sb.append("try { var pkg = req('@kotlinmania/$ffiModName-native-bindings'); if (typeof globalThis !== 'undefined') $globalProp = pkg; return pkg; } catch (_) {} ")
    }
    sb.append(
        "var fs = req('fs'); " +
            "var path = req('path'); " +
            "var dir = process.cwd ? process.cwd() : '.'; " +
            "var rels = $relsJs; " +
            "for (var d = 0; d < 8; d++) { " +
            "for (var i = 0; i < rels.length; i++) { " +
            "var c = path.resolve(dir, rels[i]); ",
    )
    if (returnBooleanStatus) {
        sb.append("if (fs.existsSync(c)) { $globalProp = req(c); return true; } ")
    } else {
        sb.append("if (fs.existsSync(c)) { var loaded = req(c); if (typeof globalThis !== 'undefined') $globalProp = loaded; return loaded; } ")
    }
    sb.append("} var parent = path.dirname(dir); if (parent === dir) break; dir = parent; } ")
    if (returnBooleanStatus) {
        sb.append("$globalProp = null; return false; ")
    } else {
        sb.append("if (typeof globalThis !== 'undefined') $globalProp = null; return null; ")
    }
    return sb.toString()
}

internal class FfiInteropCollector(
    private val source: String,
    internal val headerContext: FileHeaderContext,
    internal val resolveNumericKind: (String) -> NumericKind? = { null },
    private val resolveTypeAliasTarget: (String) -> SynType? = { null },
) {
    internal val preambles = mutableListOf<String>()
    internal val foreignFunctions = mutableListOf<ForeignFnInteropSpec>()
    internal val foreignStatics = mutableListOf<ForeignStaticInteropSpec>()
    internal val inlineFunctions = mutableListOf<InlineCFunctionSpec>()
    private val linkLibraries = linkedSetOf<String>()
    internal val explicitHeaders = linkedSetOf<String>()
    private val recordedImportLines = linkedSetOf<String>()
    internal val localDeclaredTypeNames = linkedSetOf<String>()
    private val usedWrapperSymbolNames = linkedSetOf<String>()
    internal var usesCppLanguage = false
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

    internal fun shadowsKotlinString(pkgName: String?): Boolean {
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

    internal fun lookupCrossFilePointerAlias(ident: String): Pair<Boolean, String>? {
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

    internal fun isBridgeableInCWrapper(fn: ForeignFnInteropSpec): Boolean =
        (!fn.isVariadic || fn.params.isNotEmpty()) &&
            fn.returnBridgeKind != CAbiBridgeKind.BY_VALUE_OBJECT &&
            fn.params.none { it.bridgeKind == CAbiBridgeKind.BY_VALUE_OBJECT }

    internal fun isBridgeableStaticInCWrapper(st: ForeignStaticInteropSpec): Boolean =
        st.bridgeKind != CAbiBridgeKind.BY_VALUE_OBJECT &&
            st.bridgeKind != CAbiBridgeKind.VOID &&
            st.bridgeKind != CAbiBridgeKind.NEVER

    internal fun isBridgeableInlineInCWrapper(fn: InlineCFunctionSpec): Boolean =
        fn.returnBridgeKind != CAbiBridgeKind.BY_VALUE_OBJECT &&
            fn.params.none { it.bridgeKind == CAbiBridgeKind.BY_VALUE_OBJECT }

    internal fun canCallDirectlyInCWrapper(fn: ForeignFnInteropSpec): Boolean {
        if (!isBridgeableInCWrapper(fn) || fn.dllName != null || fn.isVariadic) return false
        if (isAtexitForeignFn(fn)) return false
        if (fn.returnBridgeKind == CAbiBridgeKind.CALLBACK_PTR || fn.params.any { it.bridgeKind == CAbiBridgeKind.CALLBACK_PTR }) {
            return false
        }
        if (preambles.any { containsCIdentifier(it, fn.cSymbolName) }) return true
        return isDirectCallSafeSymbol(fn)
    }

    internal fun finalizeActualsKotlinFile(
        sb: StringBuilder,
        actualBlocks: List<String>,
        imports: MutableSet<String>,
        pkgName: String?,
        leadingComment: String? = null,
    ): String {
        val bodyJoined = PhantomRawPointerTypeStripper.strip(actualBlocks.joinToString("\n\n"))
        addRecordedImportsForBody(bodyJoined, pkgName, imports)
        if (imports.isNotEmpty()) {
            for (imp in sortImportsForKtlint(imports)) {
                sb.append("import ").append(imp).append('\n')
            }
            sb.append('\n')
        }
        if (leadingComment != null) {
            sb.append(leadingComment).append('\n')
        }
        sb.append(bodyJoined)
        if (actualBlocks.isNotEmpty()) {
            sb.append('\n')
        }
        return sb.toString()
    }

    internal fun renderLongOrScalarBridgeReturnConversion(
        rawCall: String,
        kotlinReturnType: String,
        returnBridgeKind: CAbiBridgeKind,
        returnDefaultExpr: String,
        pkgName: String?,
        importsOut: MutableSet<String>,
        subIntBase: String,
        i64Base: String,
        u64Base: String,
        ptrLongExpr: String,
        boolExpr: String,
        f32Expr: String,
        f64Expr: String,
        signedSubIntKeepsRaw: Boolean,
        fallbackNonScalar: String,
    ): String {
        val retNonNullNumeric = if (!kotlinReturnType.endsWith("?")) resolveNumericKind(kotlinReturnType) else null
        val isRawPointerReturn = headerContext.rawAbiMode || kotlinReturnType == "Long" || retNonNullNumeric != null
        val desc = bridgeDescriptorFor(returnBridgeKind)
        return when (returnBridgeKind) {
            CAbiBridgeKind.VOID -> rawCall
            CAbiBridgeKind.BOOL -> boolExpr
            CAbiBridgeKind.F32 -> f32Expr
            CAbiBridgeKind.F64 -> f64Expr
            CAbiBridgeKind.I8, CAbiBridgeKind.U8,
            CAbiBridgeKind.I16, CAbiBridgeKind.U16,
            CAbiBridgeKind.I32, CAbiBridgeKind.U32,
            -> {
                val targetKind = desc.numericKind!!
                if (retNonNullNumeric != null && retNonNullNumeric != targetKind) {
                    "$subIntBase.${conversionMethodForNumericKind(retNonNullNumeric)}()"
                } else {
                    val isUnsigned =
                        returnBridgeKind == CAbiBridgeKind.U8 ||
                            returnBridgeKind == CAbiBridgeKind.U16 ||
                            returnBridgeKind == CAbiBridgeKind.U32
                    val conv =
                        if (signedSubIntKeepsRaw && !isUnsigned) {
                            null
                        } else {
                            desc.defaultReturnConv
                        }
                    if (conv == null) subIntBase else "$subIntBase.$conv()"
                }
            }
            CAbiBridgeKind.I64 ->
                if (retNonNullNumeric != null && retNonNullNumeric != NumericKind.I64) {
                    "$i64Base.${conversionMethodForNumericKind(retNonNullNumeric)}()"
                } else {
                    i64Base
                }
            CAbiBridgeKind.U64 ->
                if (retNonNullNumeric != null && retNonNullNumeric != NumericKind.U64) {
                    "$u64Base.${conversionMethodForNumericKind(retNonNullNumeric)}()"
                } else {
                    if (u64Base == rawCall) "$rawCall.toULong()" else u64Base
                }
            CAbiBridgeKind.CALLBACK_PTR ->
                if (isRawPointerReturn) ptrLongExpr else returnDefaultExpr
            CAbiBridgeKind.CONST_CSTR,
            CAbiBridgeKind.MUT_CSTR,
            CAbiBridgeKind.OPAQUE_PTR,
            CAbiBridgeKind.FILE_PTR,
            CAbiBridgeKind.DIR_PTR,
            -> {
                if (isRawPointerReturn) {
                    if (retNonNullNumeric == NumericKind.I64 || kotlinReturnType == "Long" || retNonNullNumeric == null) {
                        ptrLongExpr
                    } else {
                        "$ptrLongExpr.${conversionMethodForNumericKind(retNonNullNumeric)}()"
                    }
                } else {
                    wrapLongAddressForPointerReturn(
                        addrExpr = ptrLongExpr,
                        kotlinReturnType = kotlinReturnType,
                        returnBridgeKind = returnBridgeKind,
                        returnDefaultExpr = returnDefaultExpr,
                        pkgName = pkgName,
                        importsOut = importsOut,
                    )
                }
            }
            else -> fallbackNonScalar
        }
    }

    internal fun addRecordedImportsForBody(
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
        val allocNs = CoreIntrinsicLowering.resolveAllocNamespace(headerContext)
        val cVoidPkg =
            headerContext.crateSymbolSubpackages["CVoid"]
                ?.firstOrNull()
                ?.let { sub ->
                    val projNs =
                        headerContext.projectNamespace
                            ?.trim()
                            ?.removeSuffix(".")
                            .orEmpty()
                    when {
                        projNs.isEmpty() -> null
                        sub.isEmpty() -> projNs
                        else -> "$projNs.$sub"
                    }
                }
                ?: headerContext.externalCrateSymbolPackages["core"]
                    ?.get("CVoid")
                    ?.filter { ".ffi." in it || it.endsWith(".ffi") }
                    ?.minByOrNull { it.length }
                ?: headerContext.externalCrateSymbolPackages["core"]
                    ?.get("CVoid")
                    ?.minByOrNull { it.length }
                ?: "$coreNs.ffi"
        val ffiPairs =
            buildList {
                add("ConstPtr" to "$coreNs.ptr")
                add("MutPtr" to "$coreNs.ptr")
                add("CVoid" to cVoidPkg)
                if (!headerContext.rawAbiMode) {
                    for (ffiSym in CoreIntrinsicLowering.STDLIB_CORE_FFI_SYMBOLS.values.distinct()) {
                        if (ffiSym != "CVoid") {
                            add(ffiSym to CoreIntrinsicLowering.defaultStdlibFfiSymbolPackage(ffiSym, coreNs, allocNs))
                        }
                    }
                    add("CString" to CoreIntrinsicLowering.defaultStdlibFfiSymbolPackage("CString", coreNs, allocNs))
                }
            }
        for ((sym, targetPkg) in ffiPairs) {
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

    internal fun renderStaticActualProperty(st: ForeignStaticInteropSpec): String =
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

    internal fun renderFallbackActualFunction(
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

    internal fun renderUnsupportedActualFunction(
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

    internal fun wrapLongAddressForPointerReturn(
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

    internal fun renderScalarParamArg(
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

    internal fun renderPointerParamToLong(param: InteropParamSpec): String {
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

    companion object {
        private val POINTEE_STOP_IDENTS = setOf("c_char", "FILE", "DIR", "c_void")

        private fun unwrapTypeAliases(
            ty: SynType,
            resolveTypeAliasTarget: (String) -> SynType?,
            stopIdents: Set<String> = emptySet(),
        ): SynType {
            var cur = unwrapType(ty)
            var depth = 0
            while (depth < 8 && cur is SynType.Path && cur.qself == null) {
                val lastSeg = cur.path.segments.last() ?: break
                if (lastSeg.arguments !is PathArguments.None) break
                val rawIdent = lastSeg.ident.toString().removePrefix("r#")
                if (rawIdent in stopIdents) break
                val target = resolveTypeAliasTarget(rawIdent) ?: break
                cur = unwrapType(target)
                depth++
            }
            return cur
        }

        private fun unwrapOuterAliases(
            ty: SynType,
            resolveTypeAliasTarget: (String) -> SynType?,
        ): SynType = unwrapTypeAliases(ty, resolveTypeAliasTarget)

        private fun unwrapPointeeAliases(
            ty: SynType,
            resolveTypeAliasTarget: (String) -> SynType?,
        ): SynType = unwrapTypeAliases(ty, resolveTypeAliasTarget, POINTEE_STOP_IDENTS)

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
                    NumericKind.I128, NumericKind.U128, NumericKind.F16, NumericKind.F128, null -> {}
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
            if (!fromProj.isNullOrEmpty() && (fromProj == "libc" || fromProj.endsWith(".libc"))) {
                return fromProj
            }
            return null
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
    }
}
internal val UMBRELLA_LINK_LIBRARIES =
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

internal fun extractLinkLibraryNamesFromLinkArgs(
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

internal fun isAtexitForeignFn(fn: ForeignFnInteropSpec): Boolean =
    fn.dllName == null &&
        !fn.isVariadic &&
        fn.cSymbolName == "atexit" &&
        fn.params.size == 1 &&
        fn.returnBridgeKind == CAbiBridgeKind.I32 &&
        (fn.params[0].bridgeKind == CAbiBridgeKind.CALLBACK_PTR || fn.params[0].bridgeKind == CAbiBridgeKind.OPAQUE_PTR)

internal fun androidLp64BaseSymbolFallback(fn: ForeignFnInteropSpec): String? {
    val sym = fn.cSymbolName
    if (sym.endsWith("64") && !sym.endsWith("base64") && sym.length > 2 && sym[sym.length - 3].isLetter()) {
        return sym.removeSuffix("64")
    }
    return null
}

internal fun sanitizeLinkLibraryHandleIdent(libName: String): String {
    val raw =
        buildString {
            for (ch in libName) {
                append(if (ch.isLetterOrDigit()) ch else '_')
            }
        }
    return if (raw.isEmpty() || raw[0].isDigit()) "lib_$raw" else raw
}

internal fun appendPosixDynamicLookupFallbacks(
    sb: StringBuilder,
    fn: ForeignFnInteropSpec,
    isCpp: Boolean = false,
) {
    val nullLit = if (isCpp) "nullptr" else "NULL"
    fun castFnPtr(expr: String): String =
        if (isCpp) "reinterpret_cast<km_fn_t>($expr)" else "(km_fn_t)$expr"

    val symEsc = escapeCString(fn.cSymbolName)
    val androidBaseSym = androidLp64BaseSymbolFallback(fn)
    if (androidBaseSym != null) {
        val baseEsc = escapeCString(androidBaseSym)
        sb.append("#if defined(__ANDROID__) && defined(__LP64__)\n")
        sb.append("        if (km_fn_ptr == $nullLit) {\n")
        sb.append("            km_fn_ptr = ${castFnPtr("dlsym(RTLD_DEFAULT, \"$baseEsc\")")};\n")
        sb.append("        }\n")
        sb.append("#endif\n")
    }
    val linkLib = fn.linkLibrary
    if (linkLib != null) {
        val libIdent = sanitizeLinkLibraryHandleIdent(linkLib)
        val libEsc = escapeCString(linkLib)
        fun appendLibDlopen(guard: String, dlTarget: String) {
            sb.append("$guard\n")
            sb.append("        if (km_fn_ptr == $nullLit) {\n")
            sb.append("            static void* km_h_$libIdent = $nullLit;\n")
            sb.append("            static bool km_h_${libIdent}_init = false;\n")
            sb.append("            if (!km_h_${libIdent}_init) {\n")
            sb.append("                km_h_${libIdent}_init = true;\n")
            sb.append("                km_h_$libIdent = dlopen(\"$dlTarget\", RTLD_LAZY | RTLD_LOCAL);\n")
            sb.append("            }\n")
            sb.append("            if (km_h_$libIdent != $nullLit) km_fn_ptr = ${castFnPtr("dlsym(km_h_$libIdent, \"$symEsc\")")};\n")
            sb.append("        }\n")
        }
        appendLibDlopen("#ifdef __APPLE__", "/usr/lib/lib$libEsc.dylib")
        appendLibDlopen("#elif defined(__linux__) && !defined(__ANDROID__)", "lib$libEsc.so")
        sb.append("#endif\n")
    }
}

internal fun cFailureReturnExpr(
    fn: ForeignFnInteropSpec,
    isCpp: Boolean = false,
): String {
    val expr =
        if (fn.returnBridgeKind == CAbiBridgeKind.I32 &&
            fn.cSymbolName.startsWith("pthread_") &&
            fn.cSymbolName != "pthread_equal"
        ) {
            "ENOSYS"
        } else {
            cDefaultFailureLiteral(fn.returnBridgeKind)
        }
    return if (isCpp && expr == "NULL") "nullptr" else expr
}

internal fun extractHeaderCommentsFromSource(source: String, out: MutableSet<String>) {
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

internal fun extractIncludedHeadersFromPreambles(preambles: List<String>): Set<String> {
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

internal fun formatIncludeTarget(hdr: String): String =
    if (hdr.startsWith('<') || hdr.startsWith('"')) hdr else "<$hdr>"

internal fun looksLikeCppCode(code: String): Boolean =
    code.contains("std::") ||
        code.contains("#include <iostream>") ||
        code.contains("#include <vector>") ||
        code.contains("#include <string>") ||
        code.contains("namespace ") ||
        code.contains("reinterpret_cast<") ||
        code.contains("static_cast<")

internal fun computeStemSnake(headerContext: FileHeaderContext): String {
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

internal fun computeCInteropStemSnake(headerContext: FileHeaderContext): String {
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

internal fun computeFfiModuleNameLower(headerContext: FileHeaderContext): String {
    val ffiOverride =
        headerContext.projectFfiName
            ?.trim()
            ?.lowercase()
            ?.filter { it.isLetterOrDigit() || it == '_' }
            ?.takeIf { it.isNotEmpty() }
    if (ffiOverride != null) return ffiOverride
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

internal fun computeCSymbolPrefix(headerContext: FileHeaderContext): String {
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

internal fun toPascalCaseIdentifierPart(raw: String): String {
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

internal fun toCamelCaseIdentifierPart(raw: String): String {
    val pascal = toPascalCaseIdentifierPart(raw)
    return pascal.replaceFirstChar { it.lowercaseChar() }
}

internal fun sanitizeCParamIdentifier(raw: String, index: Int): String {
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

internal fun jniMangleSegment(segment: String): String =
    buildString(segment.length + 4) {
        for (ch in segment) {
            when {
                ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' -> append(ch)
                ch == '_' -> append("_1")
                ch == ';' -> append("_2")
                ch == '[' -> append("_3")
                else -> append("_0").append(ch.code.toString(16).padStart(4, '0'))
            }
        }
    }

internal fun buildJniResourceLoaderBody(
    jniObjectName: String,
    jniLibName: String,
    resourcePrefix: String,
): String =
    buildString {
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

internal fun escapeCString(raw: String): String =
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

internal fun conversionMethodForNumericKind(kind: NumericKind): String =
    when (kind) {
        NumericKind.I8 -> "toByte"
        NumericKind.U8 -> "toUByte"
        NumericKind.I16 -> "toShort"
        NumericKind.U16 -> "toUShort"
        NumericKind.I32 -> "toInt"
        NumericKind.U32 -> "toUInt"
        NumericKind.I64 -> "toLong"
        NumericKind.U64 -> "toULong"
        NumericKind.F16 -> "toFloat"
        NumericKind.F32 -> "toFloat"
        NumericKind.F64 -> "toDouble"
        NumericKind.F128 -> "toDouble"
        NumericKind.I128, NumericKind.U128 -> "toLong"
    }

internal fun defaultKotlinFallbackForBridge(
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
            NumericKind.F16 -> "F16.ZERO"
            NumericKind.F32 -> "0.0f"
            NumericKind.F64 -> "0.0"
            NumericKind.F128 -> "F128.ZERO"
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

internal fun extractIdentifierTokens(
    expr: String,
    includeBuiltins: Boolean = false,
): Set<String> {
    val out = linkedSetOf<String>()
    for (token in KotlinSourceTokenizer.tokenize(expr)) {
        val tok =
            when (token) {
                is KotlinLexToken.Ident -> token.rawText
                is KotlinLexToken.BacktickIdent -> token.ident
                is KotlinLexToken.StringIdentTemplate -> token.ident
                else -> continue
            }
        if (tok.isNotEmpty() && !tok.first().isDigit() && (includeBuiltins || tok !in BUILTIN_KOTLIN_IDENTS)) {
            out += tok
        }
    }
    return out
}

internal val BUILTIN_KOTLIN_IDENTS: Set<String> =
    (
        "null true false Unit Nothing Boolean Byte UByte Short UShort Int UInt Long ULong Float Double " +
            "String ZERO toByte toUByte toShort toUShort toInt toUInt toLong " +
            "toULong toFloat toDouble error emptyArray intArrayOf longArrayOf byteArrayOf shortArrayOf " +
            "floatArrayOf doubleArrayOf ubyteArrayOf ushortArrayOf uintArrayOf ulongArrayOf booleanArrayOf"
    ).split(' ').toSet()

internal fun containsCIdentifier(code: String, ident: String): Boolean {
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

internal fun parseInlineCMacro(
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
        val parts = RustTokenCursor.splitMacroArguments(bracketGroup.value.stream())
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
