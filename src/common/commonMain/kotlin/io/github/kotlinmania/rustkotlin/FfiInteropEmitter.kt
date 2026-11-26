package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.procmacro2.Delimiter
import io.github.kotlinmania.procmacro2.TokenStream
import io.github.kotlinmania.syn.Attribute
import io.github.kotlinmania.syn.Macro
import io.github.kotlinmania.syn.Meta
import io.github.kotlinmania.syn.PathArguments
import io.github.kotlinmania.syn.PointerMutability
import io.github.kotlinmania.syn.SynType
import io.github.kotlinmania.syn.parse2

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
) {
    fun isDirectStringBridge(noStringConvEnabled: Boolean): Boolean =
        bridgeKind == CAbiBridgeKind.CONST_CSTR &&
            !noStringConvEnabled &&
            (kotlinType == "String?" || kotlinType == "String")
}

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

    fun asSetterParamSpec(): InteropParamSpec =
        InteropParamSpec(kotlinName = "value", cName = "value", kotlinType = kotlinType, bridgeKind = bridgeKind)
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
    val kotlinScalarType: String? = null,
    val isPointerAddress: Boolean = false,
)

internal data class BridgeReturnConversionProfile(
    val subIntBase: String,
    val i64Base: String,
    val u64Base: String,
    val ptrLongExpr: String,
    val ptrLongReceiverExpr: String = ptrLongExpr,
    val boolExpr: String,
    val f32Expr: String,
    val f64Expr: String,
    val signedSubIntKeepsRaw: Boolean = false,
    val nativeTypedIntegrals: Boolean = false,
    val callbackConvertsNonLongNumeric: Boolean = false,
    val pointerImport: String? = null,
    val fallbackNonScalar: String,
)

private data class TargetCWidthProfile(
    val ptrSigned: CAbiBridgeKind,
    val ptrUnsigned: CAbiBridgeKind,
    val cLongSigned: CAbiBridgeKind,
    val cLongUnsigned: CAbiBridgeKind,
) {
    companion object {
        fun from(cfgProfile: CfgTargetProfile): TargetCWidthProfile {
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
            return TargetCWidthProfile(ptrSigned, ptrUnsigned, cLongSigned, cLongUnsigned)
        }
    }
}

internal val C_ABI_BRIDGE_DESCRIPTORS: Map<CAbiBridgeKind, CAbiBridgeDescriptor> =
    mapOf(
        CAbiBridgeKind.VOID to CAbiBridgeDescriptor("void", "", wasmWasiScalarType = "Unit", wasmJsType = "Unit", jniKotlinType = "Unit", jniCType = "void", kotlinScalarType = "Unit"),
        CAbiBridgeKind.NEVER to CAbiBridgeDescriptor("void", "", wasmWasiScalarType = "Unit", wasmJsType = "Unit", jniKotlinType = "Unit", jniCType = "void", kotlinScalarType = "Unit"),
        CAbiBridgeKind.BOOL to CAbiBridgeDescriptor("bool", "false", wasmWasiScalarType = "Int", wasmJsType = "Boolean", jniKotlinType = "Boolean", jniCType = "jboolean", kotlinScalarType = "Boolean"),
        CAbiBridgeKind.I8 to CAbiBridgeDescriptor("int8_t", "-1", NumericKind.I8, "toByte", "Int", "Int", "Byte", "jbyte", "Byte"),
        CAbiBridgeKind.U8 to CAbiBridgeDescriptor("uint8_t", "0", NumericKind.U8, "toUByte", "Int", "Int", "Byte", "jbyte", "UByte"),
        CAbiBridgeKind.I16 to CAbiBridgeDescriptor("int16_t", "-1", NumericKind.I16, "toShort", "Int", "Int", "Short", "jshort", "Short"),
        CAbiBridgeKind.U16 to CAbiBridgeDescriptor("uint16_t", "0", NumericKind.U16, "toUShort", "Int", "Int", "Short", "jshort", "UShort"),
        CAbiBridgeKind.I32 to CAbiBridgeDescriptor("int32_t", "-1", NumericKind.I32, null, "Int", "Int", "Int", "jint", "Int"),
        CAbiBridgeKind.U32 to CAbiBridgeDescriptor("uint32_t", "0", NumericKind.U32, "toUInt", "Int", "Int", "Int", "jint", "UInt"),
        CAbiBridgeKind.I64 to CAbiBridgeDescriptor("int64_t", "-1", NumericKind.I64, null, "Long", null, "Long", "jlong", "Long"),
        CAbiBridgeKind.U64 to CAbiBridgeDescriptor("uint64_t", "0", NumericKind.U64, "toULong", "Long", null, "Long", "jlong", "ULong"),
        CAbiBridgeKind.F32 to CAbiBridgeDescriptor("float", "0.0f", NumericKind.F32, null, "Float", "Double", "Float", "jfloat", "Float"),
        CAbiBridgeKind.F64 to CAbiBridgeDescriptor("double", "0.0", NumericKind.F64, null, "Double", "Double", "Double", "jdouble", "Double"),
        CAbiBridgeKind.CONST_CSTR to CAbiBridgeDescriptor("const char*", "NULL", jniKotlinType = "Long", jniCType = "jlong", isPointerAddress = true),
        CAbiBridgeKind.MUT_CSTR to CAbiBridgeDescriptor("char*", "NULL", jniKotlinType = "Long", jniCType = "jlong", isPointerAddress = true),
        CAbiBridgeKind.OPAQUE_PTR to CAbiBridgeDescriptor("void*", "NULL", jniKotlinType = "Long", jniCType = "jlong", isPointerAddress = true),
        CAbiBridgeKind.FILE_PTR to CAbiBridgeDescriptor("void*", "NULL", jniKotlinType = "Long", jniCType = "jlong", isPointerAddress = true),
        CAbiBridgeKind.DIR_PTR to CAbiBridgeDescriptor("void*", "NULL", jniKotlinType = "Long", jniCType = "jlong", isPointerAddress = true),
        CAbiBridgeKind.CALLBACK_PTR to CAbiBridgeDescriptor("void*", "NULL", jniKotlinType = "Long", jniCType = "jlong", isPointerAddress = true),
        CAbiBridgeKind.BY_VALUE_OBJECT to CAbiBridgeDescriptor("void*", "NULL", wasmJsType = "Unit", jniKotlinType = "Long", jniCType = "jlong"),
    )

private val NUMERIC_KIND_TO_BRIDGE_KIND: Map<NumericKind, CAbiBridgeKind> =
    C_ABI_BRIDGE_DESCRIPTORS.entries
        .mapNotNull { (kind, desc) -> desc.numericKind?.let { it to kind } }
        .toMap()

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

internal fun cDefaultFailureLiteral(kind: CAbiBridgeKind): String =
    bridgeDescriptorFor(kind).cFailureLiteral

internal fun requiresNoStringConversion(params: List<InteropParamSpec>): Boolean =
    params.any { it.bridgeKind == CAbiBridgeKind.MUT_CSTR } ||
        params.count { it.bridgeKind == CAbiBridgeKind.CONST_CSTR && (it.kotlinType == "String?" || it.kotlinType == "String") } > 1 ||
        params.any { it.bridgeKind == CAbiBridgeKind.CONST_CSTR && it.kotlinType != "String?" && it.kotlinType != "String" }

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

internal fun formatKotlinActualParamsSig(params: List<InteropParamSpec>, isVariadic: Boolean): String {
    val baseParams = params.map { "${it.kotlinName}: ${it.kotlinType}" }
    return (if (isVariadic) baseParams + "vararg variadicArgs: Long" else baseParams).joinToString(", ")
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
    val paramsSig = formatKotlinActualParamsSig(params, isVariadic)
    val retSuffix = if (returnBridgeKind == CAbiBridgeKind.VOID) "" else ": $kotlinReturnType"
    if (addonAvailabilityGuard != null) {
        val addonCallBlock =
            "    if ($addonAvailabilityGuard) {\n" +
                "        $rawCall\n" +
                "    } else {\n" +
                "        throw UnsupportedOperationException(\"Native FFI addon is not available\")\n" +
                "    }"
        return when {
            returnBridgeKind == CAbiBridgeKind.VOID ->
                "${visibilityPrefix}actual fun $kotlinName($paramsSig) {\n$addonCallBlock\n}"
            returnBridgeKind == CAbiBridgeKind.NEVER || kotlinReturnType == "Nothing" ->
                "${visibilityPrefix}actual fun $kotlinName($paramsSig): Nothing {\n$addonCallBlock\n    error(\"Unreachable noreturn C function $kotlinName\")\n}"
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
    val prefixes =
        listOf(
            "",
            "build/node-ffi/",
            "build/node-ffi/release/",
            "build/node-ffi/debug/",
            "src/js/node-$ffiModName/build/Release/",
            "src/js/node-$ffiModName/build/Debug/",
            "src/js/node-$ffiModName/dist/build/Release/",
            "src/js/node-$ffiModName/dist/build/Debug/",
        )
    return binNames
        .flatMap { b -> prefixes.map { "$it$b" } }
        .joinToString(", ", prefix = "[", postfix = "]") { "'$it'" }
}

internal fun buildNodeNapiLoaderJsBody(
    ffiModName: String,
    returnBooleanStatus: Boolean,
): String {
    val relsJs = napiRelativeCandidatesJsArray(ffiModName)
    val globalProp = "globalThis.__km_napi_$ffiModName"
    val pkgSpec = "@kotlinmania/$ffiModName-native-bindings"
    val cachedGuard =
        if (returnBooleanStatus) {
            "if (typeof $globalProp !== 'undefined') return $globalProp !== null; "
        } else {
            "if (typeof globalThis !== 'undefined' && typeof $globalProp !== 'undefined') return $globalProp; "
        }
    val failAndCache = if (returnBooleanStatus) "{ $globalProp = null; return false; }" else "return null;"
    val finalFail =
        if (returnBooleanStatus) {
            "$globalProp = null; return false; "
        } else {
            "if (typeof globalThis !== 'undefined') $globalProp = null; return null; "
        }

    fun storeLoaded(localName: String, loadExpr: String): String =
        if (returnBooleanStatus) {
            "$globalProp = $loadExpr; return true;"
        } else {
            "var $localName = $loadExpr; if (typeof globalThis !== 'undefined') $globalProp = $localName; return $localName;"
        }

    return buildString {
        append(cachedGuard)
        append("if (typeof process === 'undefined' || !process.versions || !process.versions.node) $failAndCache ")
        append(
            "var mod = process.getBuiltinModule ? process.getBuiltinModule('module') : (process.mainModule && process.mainModule.constructor); " +
                "var req = (mod && mod.createRequire) ? mod.createRequire((process.cwd ? process.cwd() : '.') + '/package.json') : (process.mainModule ? process.mainModule.require : null); ",
        )
        append("if (!req) $failAndCache ")
        append("try { ${storeLoaded("pkg", "req('$pkgSpec')")} } catch (_) {} ")
        append(
            "var fs = req('fs'); var path = req('path'); var dir = process.cwd ? process.cwd() : '.'; var rels = $relsJs; " +
                "for (var d = 0; d < 8; d++) { for (var i = 0; i < rels.length; i++) { var c = path.resolve(dir, rels[i]); ",
        )
        append("if (fs.existsSync(c)) { ${storeLoaded("loaded", "req(c)")} } ")
        append("} var parent = path.dirname(dir); if (parent === dir) break; dir = parent; } ")
        append(finalFail)
    }
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

    internal fun declaredModuleSymbolNames(): Set<String> =
        buildSet {
            addAll(localDeclaredTypeNames)
            addAll(headerContext.moduleDeclaredTypeNames)
            for (fn in foreignFunctions) add(fn.kotlinName)
            for (fn in inlineFunctions) add(fn.kotlinName)
            for (st in foreignStatics) add(st.kotlinName)
        }

    private fun decodePointerAliasSpec(encoded: String?): Pair<Boolean, String>? {
        if (encoded == null) return null
        val sep = encoded.indexOf(':')
        if (sep <= 0) return null
        return (encoded.substring(0, sep) == "mut") to encoded.substring(sep + 1)
    }

    private fun currentSubpackageKey(): String? =
        headerContext
            .currentRelativeSourceDir
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
        val stringSubpkgs = headerContext.crateSymbolSubpackages["String"]
        return subpkg != null && stringSubpkgs?.contains(subpkg) == true
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
        return decodePointerAliasSpec(headerContext.cratePointerTypeAliases[simple])
            ?: decodePointerAliasSpec(headerContext.cratePointerTypeAliases[mappedKt])
    }

    private fun isScopedStructType(renderedKotlinType: String): Boolean {
        val clean = renderedKotlinType.removeSuffix("?").trim()
        val simple = clean.substringAfterLast('.')
        val projNs = headerContext.projectNamespace?.trim()?.removeSuffix(".")
        if ('.' in clean && !clean.startsWith("kotlin.")) {
            val relKey = if (!projNs.isNullOrEmpty() && clean.startsWith("$projNs.")) clean.removePrefix("$projNs.") else clean
            if (headerContext.crateTypeAliasDefaults[relKey] == "$simple()") return true
        }
        val curRel = headerContext.currentRelativeSourceDir?.replace('/', '.')?.takeIf { it.isNotEmpty() }
        val curSub = currentSubpackageKey()
        return listOfNotNull(curSub, curRel).distinct().any { scopePrefix ->
            headerContext.crateTypeAliasDefaults["$scopePrefix.$simple"] == "$simple()"
        }
    }

    private fun classifyScopedAbiBridge(synType: SynType?, renderedKotlinType: String): CAbiBridgeKind {
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

    fun classifyParamAbiBridge(synType: SynType?, renderedKotlinType: String): CAbiBridgeKind =
        classifyScopedAbiBridge(synType, renderedKotlinType)

    fun classifyReturnAbiBridge(synType: SynType?, renderedKotlinType: String): CAbiBridgeKind =
        classifyScopedAbiBridge(synType, renderedKotlinType)

    fun hasInteropContent(): Boolean =
        preambles.isNotEmpty() || foreignFunctions.isNotEmpty() || foreignStatics.isNotEmpty() || inlineFunctions.isNotEmpty()

    fun recordImportLines(lines: List<String>) {
        for (line in lines) {
            val trimmed = line.trim().removePrefix("import ").trim()
            if (trimmed.isNotEmpty()) recordedImportLines += trimmed
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
                for (part in RustTokenCursor.splitMacroArguments(meta.tokens.clone()).drop(1)) {
                    val partCursor = RustTokenCursor(part)
                    val linkGroup = partCursor.groupAt(1, Delimiter.Parenthesis)
                    if (partCursor.size == 2 && partCursor.identAt(0) == "link" && linkGroup != null) {
                        extractLinkLibraryNamesFromLinkArgs(linkGroup.stream(), modLibs)
                    }
                }
            }
        }
        for (libName in modLibs) {
            if (libName != "c") linkLibraries += libName
        }
        return modLibs.firstOrNull { it !in UMBRELLA_LINK_LIBRARIES }
    }

    fun recordTopLevelInlineMacro(mac: Macro): Boolean {
        val lastSeg = mac.path.segments.last() ?: return false
        val macroName = lastSeg.ident.toString()
        if (macroName != "cpp" && macroName != "c" && macroName != "inline_c") return false
        if (macroName == "cpp") usesCppLanguage = true
        val cursor = RustTokenCursor(mac.tokens.clone().toList())
        val rawChunk =
            (if (cursor.size == 1) cursor.extractGroupInnerSource(source, 0, Delimiter.Brace) else null)
                ?.trim()
                ?: cursor.extractSliceSource(source).trim()
        if (rawChunk.isNotEmpty()) {
            preambles += rawChunk
            if (looksLikeCppCode(rawChunk)) usesCppLanguage = true
        }
        return true
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
        foreignFunctions +=
            ForeignFnInteropSpec(
                visibilityPrefix = visibilityPrefix,
                kotlinName = kotlinName,
                cSymbolName = linkName,
                wrapperSymbolName = allocateUniqueWrapperSymbol(kotlinName),
                params = params.mapIndexed { idx, p -> p.copy(cName = sanitizeCParamIdentifier(p.cName, idx)) },
                kotlinReturnType = kotlinReturnType,
                cReturnType = cHeaderTypeForBridge(retBridge),
                returnBridgeKind = retBridge,
                isVariadic = isVariadic,
                returnDefaultExpr = returnDefaultExpr,
                dllName = dllName,
                linkLibrary = linkLibrary,
            )
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
        foreignStatics +=
            ForeignStaticInteropSpec(
                visibilityPrefix = visibilityPrefix,
                kotlinName = kotlinName,
                cSymbolName = linkName,
                getterSymbolName = allocateUniqueWrapperSymbol("get_$kotlinName"),
                setterSymbolName = allocateUniqueWrapperSymbol("set_$kotlinName"),
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
        val parsed = InlineCMacroTokenParser(source, mac.tokens).parse(isCpp = macroName == "cpp") ?: return null
        if (parsed.isCpp || looksLikeCppCode(parsed.rawBody)) {
            usesCppLanguage = true
        }
        val idx = inlineCounter++
        val basePrefix = enclosingFnKotlinName?.takeIf { it.isNotEmpty() } ?: "inlineBlock"
        val suffixTag = if (parsed.isCpp) "Cpp" else "C"
        val helperKtName = if (idx == 0) "${basePrefix}$suffixTag" else "${basePrefix}$suffixTag$idx"
        val wrapperSymbol = "${cSymbolPrefixCamel}Inline${idx}${toPascalCaseIdentifierPart(basePrefix)}"
        usedWrapperSymbolNames += wrapperSymbol

        fun resolveInlineBridgeTriple(
            rustTy: SynType?,
            explicitC: String?,
            isParam: Boolean,
            defaultKt: String,
            defaultBridge: CAbiBridgeKind,
        ): Triple<String, CAbiBridgeKind, String> {
            val cleanC = explicitC?.trim()
            val ktTy =
                when {
                    rustTy != null -> renderRustType(rustTy, isParam)
                    cleanC != null -> inferKotlinTypeFromCType(cleanC, headerContext.rawAbiMode, activeCfgProfile)
                    else -> defaultKt
                }
            val bridge =
                when {
                    rustTy != null -> if (isParam) classifyParamAbiBridge(rustTy, ktTy) else classifyReturnAbiBridge(rustTy, ktTy)
                    cleanC != null -> classifyExplicitCTypeBridge(cleanC, activeCfgProfile)
                    else -> defaultBridge
                }
            val finalC = cleanC?.takeIf { it.isNotEmpty() } ?: cHeaderTypeForBridge(bridge)
            return Triple(ktTy, bridge, finalC)
        }

        val paramSpecs =
            parsed.captureArgs.mapIndexed { pIdx, cap ->
                val ktParamName = rustValueIdentToKotlin(cap.rawRustIdent).ifEmpty { "arg$pIdx" }
                val cParamName = sanitizeCParamIdentifier(cap.rawRustIdent.removePrefix("r#").ifEmpty { "arg$pIdx" }, pIdx)
                val (ktType, bridge, finalCType) =
                    resolveInlineBridgeTriple(cap.rustType, cap.explicitCType, true, "Long", CAbiBridgeKind.I64)
                InteropParamSpec(ktParamName, cParamName, ktType, finalCType, bridge)
            }

        val (ktReturnType, retBridge, finalCRetType) =
            resolveInlineBridgeTriple(parsed.returnRustType, parsed.explicitCReturnType, false, "Unit", CAbiBridgeKind.VOID)

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

        val callArgs = parsed.captureArgs.joinToString(", ") { resolveLocalExprName(it.rawRustIdent.removePrefix("r#")) }
        return "$helperKtName($callArgs)"
    }

    fun renderInlineExpectDeclarations(): List<String> {
        val pending = inlineFunctions.subList(renderedInlineExpectCount, inlineFunctions.size)
        renderedInlineExpectCount = inlineFunctions.size
        return pending.map { fn ->
            val paramsStr = formatKotlinActualParamsSig(fn.params, isVariadic = false)
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
        val pkgName = headerContext.packageName
        val cinteropPkg = if (!pkgName.isNullOrBlank()) "$pkgName.cinterop" else "ffi.cinterop"
        val jniObjectName = "${cinteropStemPascal}JniBridge"
        val jniLibName = "${ffiModName}_jni"

        fun file(path: String, kind: CompanionInteropKind, content: String): CompanionInteropFile =
            CompanionInteropFile(relativePath = path, kind = kind, content = content, ffiModuleName = ffiModName)

        return listOf(
            file(
                "$cinteropStemPascal.def",
                CompanionInteropKind.CInteropDef,
                buildDefFileContent(cinteropPkg, headerFileName, cinteropStemSnake),
            ),
            file(headerFileName, CompanionInteropKind.CHeader, buildCHeaderContent(cinteropStemSnake)),
            file(
                "${cinteropStemSnake}_wrapper.$sourceExt",
                if (usesCppLanguage) CompanionInteropKind.CppSource else CompanionInteropKind.CSource,
                buildCOrCppSourceContent(headerFileName),
            ),
            file(
                "${localStemPascal}NativeActuals.kt",
                CompanionInteropKind.NativeKotlinActuals,
                buildNativeActualsKotlinContent(pkgName, cinteropPkg),
            ),
            file(
                "${localStemPascal}JvmActuals.kt",
                CompanionInteropKind.JvmKotlinActuals,
                buildJvmOrAndroidActualsKotlinContent(pkgName, jniObjectName, jniLibName, includeJarResourceLoader = true),
            ),
            file(
                "${localStemPascal}AndroidActuals.kt",
                CompanionInteropKind.AndroidKotlinActuals,
                buildJvmOrAndroidActualsKotlinContent(pkgName, jniObjectName, jniLibName, includeJarResourceLoader = false),
            ),
            file("${cSymbolPrefix}_jni.c", CompanionInteropKind.JniCSource, buildJniCSourceContent(pkgName, jniObjectName)),
            file(
                "${localStemPascal}JsActuals.kt",
                CompanionInteropKind.JsKotlinActuals,
                buildJsActualsKotlinContent(pkgName, cinteropStemPascal, ffiModName),
            ),
            file("${cSymbolPrefix}_bindings.cpp", CompanionInteropKind.NapiCppSource, buildNapiCppSourceContent(cinteropStemSnake)),
            file(
                "${localStemPascal}WasmJsActuals.kt",
                CompanionInteropKind.WasmJsKotlinActuals,
                buildWasmJsActualsKotlinContent(pkgName, cinteropStemPascal, ffiModName),
            ),
            file(
                "${localStemPascal}WasmWasiActuals.kt",
                CompanionInteropKind.WasmWasiKotlinActuals,
                buildWasmWasiActualsKotlinContent(pkgName),
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
        return preambles.any { containsWholeIdentifier(it, fn.cSymbolName) } || isDirectCallSafeSymbol(fn)
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

    internal fun renderBridgeReturnConversion(
        rawCall: String,
        kotlinReturnType: String,
        returnBridgeKind: CAbiBridgeKind,
        returnDefaultExpr: String,
        pkgName: String?,
        importsOut: MutableSet<String>,
        profile: BridgeReturnConversionProfile,
    ): String {
        val retNonNullNumeric = if (!kotlinReturnType.endsWith("?")) resolveNumericKind(kotlinReturnType) else null
        val isRawPointerReturn = headerContext.rawAbiMode || kotlinReturnType == "Long" || retNonNullNumeric != null
        val desc = bridgeDescriptorFor(returnBridgeKind)
        return when (returnBridgeKind) {
            CAbiBridgeKind.VOID -> rawCall
            CAbiBridgeKind.BOOL -> profile.boolExpr
            CAbiBridgeKind.F32 -> profile.f32Expr
            CAbiBridgeKind.F64 -> profile.f64Expr
            CAbiBridgeKind.I8, CAbiBridgeKind.U8,
            CAbiBridgeKind.I16, CAbiBridgeKind.U16,
            CAbiBridgeKind.I32, CAbiBridgeKind.U32,
            -> {
                val targetKind = desc.numericKind!!
                if (retNonNullNumeric != null && retNonNullNumeric != targetKind) {
                    "${profile.subIntBase}.${conversionMethodForNumericKind(retNonNullNumeric)}()"
                } else if (profile.nativeTypedIntegrals) {
                    profile.subIntBase
                } else {
                    val isUnsigned =
                        returnBridgeKind == CAbiBridgeKind.U8 ||
                            returnBridgeKind == CAbiBridgeKind.U16 ||
                            returnBridgeKind == CAbiBridgeKind.U32
                    val conv = if (profile.signedSubIntKeepsRaw && !isUnsigned) null else desc.defaultReturnConv
                    if (conv == null) profile.subIntBase else "${profile.subIntBase}.$conv()"
                }
            }
            CAbiBridgeKind.I64 ->
                if (retNonNullNumeric != null && retNonNullNumeric != NumericKind.I64) {
                    "${profile.i64Base}.${conversionMethodForNumericKind(retNonNullNumeric)}()"
                } else {
                    profile.i64Base
                }
            CAbiBridgeKind.U64 ->
                if (retNonNullNumeric != null && retNonNullNumeric != NumericKind.U64) {
                    "${profile.u64Base}.${conversionMethodForNumericKind(retNonNullNumeric)}()"
                } else if (!profile.nativeTypedIntegrals && profile.u64Base == rawCall) {
                    "$rawCall.toULong()"
                } else {
                    profile.u64Base
                }
            CAbiBridgeKind.CALLBACK_PTR ->
                if (isRawPointerReturn) {
                    profile.pointerImport?.let { importsOut += it }
                    renderRawPointerNumericReturn(profile, kotlinReturnType, retNonNullNumeric, profile.callbackConvertsNonLongNumeric)
                } else {
                    returnDefaultExpr
                }
            CAbiBridgeKind.CONST_CSTR,
            CAbiBridgeKind.MUT_CSTR,
            CAbiBridgeKind.OPAQUE_PTR,
            CAbiBridgeKind.FILE_PTR,
            CAbiBridgeKind.DIR_PTR,
            -> {
                profile.pointerImport?.let { importsOut += it }
                if (isRawPointerReturn) {
                    renderRawPointerNumericReturn(profile, kotlinReturnType, retNonNullNumeric, allowNonLongConversion = true)
                } else {
                    wrapLongAddressForPointerReturn(
                        addrExpr = profile.ptrLongExpr,
                        kotlinReturnType = kotlinReturnType,
                        returnBridgeKind = returnBridgeKind,
                        returnDefaultExpr = returnDefaultExpr,
                        pkgName = pkgName,
                        importsOut = importsOut,
                    )
                }
            }
            else -> profile.fallbackNonScalar
        }
    }

    private fun renderRawPointerNumericReturn(
        profile: BridgeReturnConversionProfile,
        kotlinReturnType: String,
        retNonNullNumeric: NumericKind?,
        allowNonLongConversion: Boolean,
    ): String =
        if (!allowNonLongConversion ||
            retNonNullNumeric == NumericKind.I64 ||
            kotlinReturnType == "Long" ||
            retNonNullNumeric == null
        ) {
            profile.ptrLongExpr
        } else {
            "${profile.ptrLongReceiverExpr}.${conversionMethodForNumericKind(retNonNullNumeric)}()"
        }

    internal fun addRecordedImportsForBody(
        bodyText: String,
        pkgName: String?,
        importsOut: MutableSet<String>,
    ) {
        val usedIdents = extractIdentifierTokens(bodyText)
        val localDeclaredNames = declaredModuleSymbolNames()
        val explicitlyImportedNames =
            importsOut
                .filterNot { it.endsWith(".*") }
                .mapTo(linkedSetOf(), ::extractImportLocalName)
        for (imp in recordedImportLines) {
            if (imp.endsWith(".*")) continue
            val simpleName = extractImportLocalName(imp)
            if (simpleName in usedIdents && simpleName !in localDeclaredNames) {
                val aliasSep = imp.indexOf(" as ")
                val impPkg = if (aliasSep > 0) imp.substring(0, aliasSep).trim().substringBeforeLast('.') else imp.substringBeforeLast('.')
                if (impPkg != pkgName) {
                    importsOut += imp
                }
                explicitlyImportedNames += simpleName
            }
        }
        val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
        val allocNs = CoreIntrinsicLowering.resolveAllocNamespace(headerContext)
        val extCoreSymbols = headerContext.externalCrateSymbolPackages["core"]
        val cVoidSub = headerContext.crateSymbolSubpackages["CVoid"]?.firstOrNull()
        val rawProjNs = headerContext.projectNamespace
        val projNs = rawProjNs?.trim()?.removeSuffix(".").orEmpty()
        val cVoidPkg =
            when {
                cVoidSub != null && projNs.isNotEmpty() -> if (cVoidSub.isEmpty()) projNs else "$projNs.$cVoidSub"
                else -> {
                    val extCVoid = extCoreSymbols?.get("CVoid")
                    extCVoid?.filter { ".ffi." in it || it.endsWith(".ffi") }?.minByOrNull { it.length }
                        ?: extCVoid?.minByOrNull { it.length }
                        ?: "$coreNs.ffi"
                }
            }
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
                if (imp.endsWith(".*") && imp.removeSuffix(".*") != pkgName) {
                    importsOut += imp
                }
            }
        }
        val rawIdents = extractIdentifierTokens(bodyText, includeBuiltins = true)
        importsOut.retainAll { existing ->
            existing.endsWith(".*") ||
                existing == "kotlinx.cinterop.ExperimentalForeignApi" ||
                extractImportLocalName(existing) in rawIdents
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
        val paramsSig = formatKotlinActualParamsSig(params, isVariadic)
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
        val paramsSig = formatKotlinActualParamsSig(params, isVariadic)
        if (returnBridgeKind == CAbiBridgeKind.VOID) {
            return "${visibilityPrefix}actual fun $kotlinName($paramsSig) {\n" +
                "    throw UnsupportedOperationException(\"$message\")\n" +
                "}"
        }
        return "${visibilityPrefix}actual fun $kotlinName($paramsSig): $kotlinReturnType =\n" +
            "    throw UnsupportedOperationException(\"$message\")"
    }

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
        return if (paramKind == targetKind) param.kotlinName else "${param.kotlinName}.$convMethod()"
    }

    internal fun renderPointerParamToLong(param: InteropParamSpec): String {
        val ktType = param.kotlinType
        val nonNullNumeric = if (!ktType.endsWith("?")) resolveNumericKind(ktType) else null
        if (headerContext.rawAbiMode || ktType == "Long" || nonNullNumeric != null) {
            return if (ktType == "Long" || nonNullNumeric == NumericKind.I64) param.kotlinName else "${param.kotlinName}.toLong()"
        }
        if (param.bridgeKind == CAbiBridgeKind.CALLBACK_PTR) return "0L"
        return "${param.kotlinName}.rawAddress"
    }

    companion object {
        private val POINTEE_STOP_IDENTS = setOf("c_char", "FILE", "DIR", "c_void")

        private fun unwrapTypeAliases(
            ty: SynType,
            resolveTypeAliasTarget: (String) -> SynType?,
            stopIdents: Set<String> = emptySet(),
        ): SynType {
            var cur = unwrapSynType(ty)
            var depth = 0
            while (depth < 8 && cur is SynType.Path && cur.qself == null) {
                val lastSeg = cur.path.segments.last() ?: break
                if (lastSeg.arguments !is PathArguments.None) break
                val rawIdent = lastSeg.cleanIdent()
                if (rawIdent in stopIdents) break
                val target = resolveTypeAliasTarget(rawIdent) ?: break
                cur = unwrapSynType(target)
                depth++
            }
            return cur
        }

        private fun pointeeBridgeKind(isMut: Boolean, pointeeIdent: String?): CAbiBridgeKind =
            when (pointeeIdent) {
                "c_char" -> if (isMut) CAbiBridgeKind.MUT_CSTR else CAbiBridgeKind.CONST_CSTR
                "FILE" -> CAbiBridgeKind.FILE_PTR
                "DIR" -> CAbiBridgeKind.DIR_PTR
                else -> CAbiBridgeKind.OPAQUE_PTR
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
            val unwrapped = unwrapTypeAliases(synType, resolveTypeAliasTarget)
            if (unwrapped is SynType.Tuple && unwrapped.elems.isEmpty()) return CAbiBridgeKind.VOID
            if (renderedKotlinType == "Unit" && resolveNumericKind(renderedKotlinType) == null && unwrapped == synType) {
                return CAbiBridgeKind.VOID
            }
            if (unwrapped is SynType.Never || renderedKotlinType == "Nothing") return CAbiBridgeKind.NEVER
            if (unwrapped is SynType.FnPtr) return CAbiBridgeKind.CALLBACK_PTR
            if (unwrapped is SynType.Ptr) {
                val elem = unwrapTypeAliases(unwrapped.elem, resolveTypeAliasTarget, POINTEE_STOP_IDENTS)
                val elemIdent = elem.unqualifiedPath()?.lastCleanIdent()
                return pointeeBridgeKind(unwrapped.mutability is PointerMutability.Mut, elemIdent)
            }
            var unwrappedPathIdent: String? = null
            if (unwrapped is SynType.Path && unwrapped.qself == null) {
                val lastSeg = unwrapped.path.segments.last()
                val pathIdent = lastSeg?.cleanIdent()
                unwrappedPathIdent = pathIdent
                if (pathIdent == "Option") {
                    val firstTypeArg = lastSeg.arguments.firstAngleTypeArg()
                    if (firstTypeArg != null && unwrapTypeAliases(firstTypeArg, resolveTypeAliasTarget) is SynType.FnPtr) {
                        return CAbiBridgeKind.CALLBACK_PTR
                    }
                }
                val ptrAlias = (pathIdent?.let(resolvePointerAlias)) ?: resolvePointerAlias(renderedKotlinType)
                if (ptrAlias != null) return pointeeBridgeKind(ptrAlias.first, ptrAlias.second)
            }
            if (!renderedKotlinType.endsWith("?")) {
                val numericKind = resolveNumericKind(renderedKotlinType) ?: unwrappedPathIdent?.let(resolveNumericKind)
                val mappedBridge = numericKind?.let(NUMERIC_KIND_TO_BRIDGE_KIND::get)
                if (mappedBridge != null) return mappedBridge
            }
            val widths = TargetCWidthProfile.from(cfgProfile)
            val simpleType = renderedKotlinType.substringAfterLast('.')
            if (isCPointerWidthSignedTypeName(simpleType)) return widths.ptrSigned
            if (isCPointerWidthUnsignedTypeName(simpleType)) return widths.ptrUnsigned
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
                "CLong", "c_long" -> widths.cLongSigned
                "CUlong", "c_ulong" -> widths.cLongUnsigned
                "Long", "CLonglong", "c_longlong", "OffT", "off_t", "TimeT", "time_t", "Int64T", "int64_t" -> CAbiBridgeKind.I64
                "ULong", "CUlonglong", "c_ulonglong", "Uint64T", "uint64_t" -> CAbiBridgeKind.U64
                "Float", "CFloat", "c_float" -> CAbiBridgeKind.F32
                "Double", "CDouble", "c_double" -> CAbiBridgeKind.F64
                else ->
                    when {
                        simpleType == "ConstPtr" || simpleType.startsWith("ConstPtr<") ||
                            simpleType == "MutPtr" || simpleType.startsWith("MutPtr<") ||
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

        private val IGNORED_C_TYPE_QUALIFIERS = setOf("const", "volatile", "restrict", "__restrict", "struct", "enum")

        private fun classifyExplicitCTypeBridge(
            cType: String,
            cfgProfile: CfgTargetProfile = TranslationSession.cfgTargetProfile,
        ): CAbiBridgeKind {
            val cursor = RustTokenCursor(lexRustTokenTrees(cType))
            val hasPointer = (0 until cursor.size).any { cursor.punctCharAt(it) == '*' }
            val allIdents = (0 until cursor.size).mapNotNull(cursor::identAt)
            val hasConst = "const" in allIdents
            val coreIdents = allIdents.filter { it !in IGNORED_C_TYPE_QUALIFIERS }
            if (hasPointer) {
                return when (coreIdents) {
                    listOf("char") -> if (hasConst) CAbiBridgeKind.CONST_CSTR else CAbiBridgeKind.MUT_CSTR
                    listOf("FILE") -> CAbiBridgeKind.FILE_PTR
                    listOf("DIR") -> CAbiBridgeKind.DIR_PTR
                    else -> CAbiBridgeKind.OPAQUE_PTR
                }
            }
            val widths = TargetCWidthProfile.from(cfgProfile)
            val ptrSigned32Or64 = if (cfgProfile.pointerWidth == 64) CAbiBridgeKind.I64 else CAbiBridgeKind.I32
            val ptrUnsigned32Or64 = if (cfgProfile.pointerWidth == 64) CAbiBridgeKind.U64 else CAbiBridgeKind.U32
            val isUnsigned = "unsigned" in coreIdents
            val longCount = coreIdents.count { it == "long" }
            return when {
                coreIdents == listOf("void") -> CAbiBridgeKind.VOID
                coreIdents == listOf("bool") || coreIdents == listOf("_Bool") -> CAbiBridgeKind.BOOL
                coreIdents == listOf("int8_t") || coreIdents == listOf("signed", "char") || coreIdents == listOf("char") ->
                    CAbiBridgeKind.I8
                coreIdents == listOf("uint8_t") || coreIdents == listOf("unsigned", "char") -> CAbiBridgeKind.U8
                coreIdents == listOf("int16_t") || (!isUnsigned && "short" in coreIdents) -> CAbiBridgeKind.I16
                coreIdents == listOf("uint16_t") || (isUnsigned && "short" in coreIdents) -> CAbiBridgeKind.U16
                coreIdents == listOf("uint32_t") || (isUnsigned && longCount == 0) -> CAbiBridgeKind.U32
                longCount == 1 -> if (isUnsigned) widths.cLongUnsigned else widths.cLongSigned
                longCount >= 2 -> if (isUnsigned) CAbiBridgeKind.U64 else CAbiBridgeKind.I64
                coreIdents == listOf("ssize_t") || coreIdents == listOf("intptr_t") || coreIdents == listOf("ptrdiff_t") ->
                    ptrSigned32Or64
                coreIdents == listOf("size_t") || coreIdents == listOf("uintptr_t") -> ptrUnsigned32Or64
                coreIdents == listOf("int64_t") -> CAbiBridgeKind.I64
                coreIdents == listOf("uint64_t") -> CAbiBridgeKind.U64
                coreIdents == listOf("float") -> CAbiBridgeKind.F32
                coreIdents == listOf("double") -> CAbiBridgeKind.F64
                else -> CAbiBridgeKind.I32
            }
        }

        private fun inferKotlinTypeFromCType(
            cType: String,
            rawAbiMode: Boolean = false,
            cfgProfile: CfgTargetProfile = TranslationSession.cfgTargetProfile,
        ): String {
            val kind = classifyExplicitCTypeBridge(cType, cfgProfile)
            bridgeDescriptorFor(kind).kotlinScalarType?.let { return it }
            if (rawAbiMode) return "Long"
            return when {
                kind == CAbiBridgeKind.CONST_CSTR -> "ConstPtr"
                kind == CAbiBridgeKind.MUT_CSTR -> "MutPtr"
                RustTokenCursor(lexRustTokenTrees(cType)).identAt(0) == "const" -> "ConstPtr"
                else -> "MutPtr"
            }
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

        internal fun extractLinkNameAttribute(attrs: List<Attribute>): String? =
            extractStringMetaAttribute(attrs, "link_name")
    }
}

private fun extractImportLocalName(imp: String): String {
    val aliasSep = imp.indexOf(" as ")
    return if (aliasSep > 0) imp.substring(aliasSep + 4).trim() else imp.substringAfterLast('.')
}

internal val UMBRELLA_LINK_LIBRARIES =
    wordSet("c m dl pthread rt System gcc gcc_s stdc++ c++ msvcrt ucrtbase libcmt legacy_stdio_definitions")

internal fun extractLinkLibraryNamesFromLinkArgs(
    tokens: TokenStream,
    out: MutableSet<String>,
) {
    for (part in RustTokenCursor.splitMacroArguments(tokens.clone())) {
        val cursor = RustTokenCursor(part)
        if (cursor.size >= 3 && cursor.identAt(0) == "name" && cursor.punctCharAt(1) == '=') {
            val libName = cursor.stringLiteralAt(2)?.trim().orEmpty()
            if (libName.isNotEmpty()) out += libName
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
    return if (sym.endsWith("64") && !sym.endsWith("base64") && sym.length > 2 && sym[sym.length - 3].isLetter()) {
        sym.removeSuffix("64")
    } else {
        null
    }
}

internal fun sanitizeLinkLibraryHandleIdent(libName: String): String {
    val raw = buildString { for (ch in libName) append(if (ch.isLetterOrDigit()) ch else '_') }
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
    androidLp64BaseSymbolFallback(fn)?.let { androidBaseSym ->
        val baseEsc = escapeCString(androidBaseSym)
        sb.append("#if defined(__ANDROID__) && defined(__LP64__)\n")
        sb.append("        if (km_fn_ptr == $nullLit) {\n")
        sb.append("            km_fn_ptr = ${castFnPtr("dlsym(RTLD_DEFAULT, \"$baseEsc\")")};\n")
        sb.append("        }\n")
        sb.append("#endif\n")
    }
    val linkLib = fn.linkLibrary ?: return
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
    for (tok in KotlinSourceTokenizer.tokenize(source)) {
        if (tok !is KotlinLexToken.LineComment) continue
        val body = tok.rawText.drop(2).trimStart { it == '!' || it == '/' || it == '*' || it.isWhitespace() }
        if (!body.startsWith("Header:")) continue
        val rest = body.removePrefix("Header:").trimStart { it.isWhitespace() || it == '`' }
        val hdr = rest.takeWhile { !it.isWhitespace() && it != '`' && it != '*' }
        if (hdr.endsWith(".h") || hdr.endsWith(".hpp")) out += hdr
    }
}

private val CPP_STDLIB_INCLUDE_HEADERS = setOf("iostream", "vector", "string")
private val CPP_CAST_KEYWORDS = setOf("reinterpret_cast", "static_cast")

private class CHostPreambleTokenScanner(
    source: String,
) : KotlinTokenWalker(source) {
    fun collectIncludedHeaders(out: MutableSet<String>) {
        var idx = 0
        while (idx < tokens.size) {
            if (punctAt(idx) == '#' && identTextAt(skipHorizontalWhitespace(idx + 1)) == "include") {
                val targetIdx = skipHorizontalWhitespace(skipHorizontalWhitespace(idx + 1) + 1)
                when {
                    punctAt(targetIdx) == '<' -> {
                        var closeIdx = targetIdx + 1
                        while (closeIdx < tokens.size && tokens[closeIdx] !is KotlinLexToken.Newline && punctAt(closeIdx) != '>') {
                            closeIdx++
                        }
                        if (punctAt(closeIdx) == '>' && closeIdx > targetIdx + 1) {
                            val hdr = source.substring(tokens[targetIdx].end, tokens[closeIdx].start).trim()
                            if (hdr.isNotEmpty()) out += hdr
                            idx = closeIdx + 1
                            continue
                        }
                    }
                    tokens.getOrNull(targetIdx) is KotlinLexToken.StringOpen -> {
                        var closeIdx = targetIdx + 1
                        while (closeIdx < tokens.size && tokens[closeIdx] !is KotlinLexToken.StringClose) closeIdx++
                        if (closeIdx < tokens.size && closeIdx > targetIdx) {
                            val hdr = source.substring(tokens[targetIdx].end, tokens[closeIdx].start).trim()
                            if (hdr.isNotEmpty()) out += hdr
                            idx = closeIdx + 1
                            continue
                        }
                    }
                }
            }
            idx++
        }
    }

    fun hasCppConstructs(): Boolean {
        val headers = linkedSetOf<String>()
        collectIncludedHeaders(headers)
        if (headers.any { it in CPP_STDLIB_INCLUDE_HEADERS }) return true
        for (idx in tokens.indices) {
            when (identTextAt(idx)) {
                "std" -> if (punctAt(idx + 1) == ':' && punctAt(idx + 2) == ':') return true
                "namespace" -> if (identTextAt(nextNonTriviaIndex(idx + 1)) != null) return true
                in CPP_CAST_KEYWORDS -> if (punctAt(nextNonTriviaIndex(idx + 1)) == '<') return true
            }
        }
        return false
    }
}

internal fun extractIncludedHeadersFromPreambles(preambles: List<String>): Set<String> {
    val found = linkedSetOf<String>()
    for (chunk in preambles) {
        CHostPreambleTokenScanner(chunk).collectIncludedHeaders(found)
    }
    return found
}

internal fun formatIncludeTarget(hdr: String): String =
    if (hdr.startsWith('<') || hdr.startsWith('"')) hdr else "<$hdr>"

internal fun looksLikeCppCode(code: String): Boolean =
    CHostPreambleTokenScanner(code).hasCppConstructs()

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
        val relSnake =
            pkg
                .removePrefix("$projNs.")
                .split('.')
                .filter { it.isNotEmpty() }
                .joinToString("_") { sanitizeCSymbolPart(it) }
        if (relSnake.isNotEmpty()) {
            return if (stem.isNotEmpty() && relSnake != stem && !relSnake.endsWith("_$stem")) "${relSnake}_$stem" else relSnake
        }
    }
    return stem
}

internal fun computeFfiModuleNameLower(headerContext: FileHeaderContext): String {
    val ffiOverride =
        headerContext
            .projectFfiName
            ?.trim()
            ?.lowercase()
            ?.filter { it.isLetterOrDigit() || it == '_' }
            ?.takeIf { it.isNotEmpty() }
    if (ffiOverride != null) return ffiOverride
    return sanitizeCSymbolPart(resolveNamespaceTailSegment(headerContext)).replace("_", "")
}

internal fun computeCSymbolPrefix(headerContext: FileHeaderContext): String {
    val stem = computeCInteropStemSnake(headerContext)
    val cleanNs = sanitizeCSymbolPart(resolveNamespaceTailSegment(headerContext))
    return if (cleanNs == stem) cleanNs else "${cleanNs}_$stem"
}

private fun resolveNamespaceTailSegment(headerContext: FileHeaderContext): String =
    headerContext.projectNamespace?.substringAfterLast('.')?.takeIf { it.isNotEmpty() }
        ?: headerContext.packageName?.substringAfterLast('.')?.takeIf { it.isNotEmpty() }
        ?: "ffi"

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
        if (ch.isLetterOrDigit()) {
            sb.append(if (capitalizeNext) ch.uppercaseChar() else ch)
            capitalizeNext = false
        } else {
            capitalizeNext = true
        }
    }
    val res = sb.toString()
    if (res.isEmpty()) return "Fn"
    return if (res.first().isDigit()) "N$res" else res
}

internal fun toCamelCaseIdentifierPart(raw: String): String =
    toPascalCaseIdentifierPart(raw).replaceFirstChar { it.lowercaseChar() }

internal fun sanitizeCParamIdentifier(raw: String, index: Int): String {
    val clean = toCamelCaseIdentifierPart(raw)
    return if (clean in C_RESERVED_PARAM_NAMES) "arg${index}${toPascalCaseIdentifierPart(clean)}" else clean
}

private val C_RESERVED_PARAM_NAMES: Set<String> =
    wordSet(
        "auto break case char const continue default do double else enum extern float for goto if",
        "inline int long register restrict return short signed sizeof static struct switch typedef union",
        "unsigned void volatile while class namespace template typename public private protected virtual operator",
        "new delete this friend using try catch throw bool true false alignas alignof constexpr decltype",
        "noexcept nullptr static_assert thread_local typeid dynamic_cast static_cast reinterpret_cast const_cast",
        "explicit mutable export wchar_t char8_t char16_t char32_t concept requires co_await co_return co_yield",
        "and or not xor bitand bitor compl and_eq or_eq xor_eq not_eq near far huge pascal cdecl stdcall fastcall",
        "interface small hyper environ errno stdin stdout stderr stat time read write open close accept bind",
        "connect listen recv send socket select poll fork pipe dup dup2 link unlink chmod chown",
        "lseek mkdir rmdir rename remove system abort exit free malloc calloc realloc div abs labs llabs",
        "sin cos tan exp log pow sqrt ceil floor index rindex bcopy bzero bcmp",
    )

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
    """
    private fun loadNativeLibrary() {
        val osRaw = System.getProperty("os.name", "").lowercase()
        val archRaw = System.getProperty("os.arch", "").lowercase()
        val hostPlatform =
            when {
                osRaw.contains("win") -> "windows"
                osRaw.contains("mac") || osRaw.contains("darwin") -> "macos"
                else -> "linux"
            }
        val hostArch =
            when {
                archRaw == "aarch64" || archRaw == "arm64" -> "aarch64"
                archRaw == "amd64" || archRaw == "x86_64" -> "x86_64"
                else -> archRaw
            }
        val libFileName =
            when (hostPlatform) {
                "windows" -> "$jniLibName.dll"
                "macos" -> "lib$jniLibName.dylib"
                else -> "lib$jniLibName.so"
            }
        val pathSep = System.getProperty("path.separator", ":")
        val libPathProp = System.getProperty("java.library.path", "")
        for (dirEntry in libPathProp.split(pathSep)) {
            if (dirEntry.isEmpty()) continue
            val candidate = java.io.File(dirEntry, libFileName)
            if (candidate.isFile) {
                System.load(candidate.absolutePath)
                return
            }
        }
        val resourcePath = "$resourcePrefix/native/${'$'}hostPlatform-${'$'}hostArch/${'$'}libFileName"
        val stream = $jniObjectName::class.java.classLoader?.getResourceAsStream(resourcePath)
        if (stream != null) {
            val suffix = "." + libFileName.substringAfterLast('.')
            val extracted = java.nio.file.Files.createTempFile("$jniLibName-", suffix).toFile()
            extracted.deleteOnExit()
            stream.use { input ->
                extracted.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            System.load(extracted.absolutePath)
            return
        }
        System.loadLibrary("$jniLibName")
    }
    """.trimIndent().prependIndent("    ") + "\n\n"

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
        NumericKind.F16, NumericKind.F32 -> "toFloat"
        NumericKind.F64, NumericKind.F128 -> "toDouble"
        NumericKind.I128, NumericKind.U128 -> "toLong"
    }

private fun defaultKotlinFallbackForNumericKind(numKind: NumericKind): String =
    when (numKind) {
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

internal fun defaultKotlinFallbackForBridge(
    kind: CAbiBridgeKind,
    kotlinType: String,
    resolveNumericKind: (String) -> NumericKind? = { null },
): String {
    resolveNumericKind(kotlinType)?.let { return defaultKotlinFallbackForNumericKind(it) }
    bridgeDescriptorFor(kind).numericKind?.let { return defaultKotlinFallbackForNumericKind(it) }
    return when (kind) {
        CAbiBridgeKind.VOID -> "Unit"
        CAbiBridgeKind.NEVER -> "error(\"unreachable\")"
        CAbiBridgeKind.BOOL -> "false"
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
        CAbiBridgeKind.CALLBACK_PTR -> if (kotlinType.endsWith("?")) "null" else "$kotlinType { }"
        CAbiBridgeKind.BY_VALUE_OBJECT -> if (kotlinType.endsWith("?")) "null" else "$kotlinType()"
        else -> "0"
    }
}

internal fun extractIdentifierTokens(
    expr: String,
    includeBuiltins: Boolean = false,
): Set<String> =
    KotlinTokenWalker(expr).collectFilteredIdentifiers(if (includeBuiltins) emptySet() else BUILTIN_KOTLIN_IDENTS)

internal val BUILTIN_KOTLIN_IDENTS: Set<String> =
    wordSet(
        "null true false Unit Nothing Boolean Byte UByte Short UShort Int UInt Long ULong Float Double",
        "String ZERO toByte toUByte toShort toUShort toInt toUInt toLong toULong toFloat toDouble",
        "error emptyArray intArrayOf longArrayOf byteArrayOf shortArrayOf floatArrayOf doubleArrayOf",
        "ubyteArrayOf ushortArrayOf uintArrayOf ulongArrayOf booleanArrayOf",
    )

/**
 * Token-cursor parser for `c!` / `cpp!` inline FFI blocks (`[mut x: T as "CType", ...] -> Ret as "CRet" { ... }`).
 */
private class InlineCMacroTokenParser(
    private val source: String,
    stream: TokenStream,
) : RustTokenCursor(stream.clone().toList()) {
    fun parse(isCpp: Boolean): ParsedInlineCMacro? {
        if (size == 0) return null
        var cursor = if (identAt(0) == "unsafe") 1 else 0
        val captureArgs = mutableListOf<InlineCaptureArg>()
        groupAt(cursor, Delimiter.Bracket)?.let { bracketGroup ->
            for (part in splitMacroArguments(bracketGroup.stream())) {
                parseCaptureArg(RustTokenCursor(part))?.let { captureArgs += it }
            }
            cursor++
        }

        var returnRustType: SynType? = null
        var explicitCReturnType: String? = null
        if (punctCharAt(cursor) == '-' && punctCharAt(cursor + 1) == '>') {
            cursor += 2
            val bodyGroupIdx = (cursor until size).lastOrNull { groupAt(it, Delimiter.Brace) != null } ?: return null
            val asIdx = (cursor until bodyGroupIdx).firstOrNull { identAt(it) == "as" }
            val rustTyEnd = asIdx ?: bodyGroupIdx
            if (cursor < rustTyEnd) {
                returnRustType = parse2(SynType.Companion::parse, TokenStream.fromTokenTrees(trees.subList(cursor, rustTyEnd))).getOrNull()
            }
            if (asIdx != null && asIdx + 1 < bodyGroupIdx) {
                explicitCReturnType = stringLiteralAt(asIdx + 1)
            }
            cursor = bodyGroupIdx
        }

        if (cursor >= size) return null
        val rawBody = extractGroupInnerSource(source, cursor, Delimiter.Brace) ?: extractSliceSource(source, cursor, size)
        return ParsedInlineCMacro(
            captureArgs = captureArgs,
            returnRustType = returnRustType,
            explicitCReturnType = explicitCReturnType,
            rawBody = rawBody,
            isCpp = isCpp,
        )
    }

    private fun parseCaptureArg(argCursor: RustTokenCursor): InlineCaptureArg? {
        if (argCursor.size == 0) return null
        var idx = 0
        val isMut = argCursor.identAt(idx) == "mut"
        if (isMut) idx++
        val rawIdent = argCursor.identAt(idx) ?: return null
        idx++

        val asIdx = (idx until argCursor.size).firstOrNull { argCursor.identAt(it) == "as" }
        val typeEnd = asIdx ?: argCursor.size
        val rustType =
            if (idx < typeEnd && argCursor.punctCharAt(idx) == ':' && idx + 1 < typeEnd) {
                parse2(SynType.Companion::parse, TokenStream.fromTokenTrees(argCursor.trees.subList(idx + 1, typeEnd))).getOrNull()
            } else {
                null
            }
        val explicitCType = if (asIdx != null) argCursor.stringLiteralAt(asIdx + 1) else null
        return InlineCaptureArg(rawRustIdent = rawIdent, isMut = isMut, rustType = rustType, explicitCType = explicitCType)
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
