package io.github.kotlinmania.rustkotlin

internal fun FfiInteropCollector.buildDefFileContent(
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
            .filter { isBridgeableInlineInCWrapper(it) && requiresNoStringConversion(it.params) }
            .map { it.wrapperSymbolName }
    val pointerStringFns = (foreignPtrFns + inlinePtrFns).distinct()
    if (pointerStringFns.isNotEmpty()) {
        sb.append("noStringConversion = ").append(pointerStringFns.joinToString(" ")).append('\n')
    }
    return sb.toString()
}

private fun formatCParamsDecl(params: List<InteropParamSpec>, isVariadic: Boolean = false): String {
    val fixedParams = params.map { "${cHeaderTypeForParam(it)} ${it.cName}" }
    val allParams =
        if (isVariadic) {
            fixedParams + listOf("int32_t km_vac", "int64_t km_va0", "int64_t km_va1", "int64_t km_va2", "int64_t km_va3")
        } else {
            fixedParams
        }
    return if (allParams.isEmpty()) "void" else allParams.joinToString(", ")
}

internal fun FfiInteropCollector.buildCHeaderContent(stemSnake: String): String {
    val guard = "${cSymbolPrefix.uppercase()}_${stemSnake.uppercase()}_WRAPPER_H"
    val sb = StringBuilder()
    sb.append("#ifndef $guard\n#define $guard\n\n")
    sb.append("#include <stdbool.h>\n#include <stddef.h>\n#include <stdint.h>\n\n")
    sb.append("#ifdef __cplusplus\nextern \"C\" {\n#endif\n\n")

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
        if (isBridgeableInCWrapper(fn) && emittedPrototypes.add(fn.wrapperSymbolName)) {
            sb.append("${cHeaderTypeForReturn(fn)} ${fn.wrapperSymbolName}(${formatCParamsDecl(fn.params, fn.isVariadic)});\n")
        }
    }
    for (fn in inlineFunctions) {
        if (isBridgeableInlineInCWrapper(fn) && emittedPrototypes.add(fn.wrapperSymbolName)) {
            sb.append("${cHeaderTypeForInlineReturn(fn)} ${fn.wrapperSymbolName}(${formatCParamsDecl(fn.params)});\n")
        }
    }

    sb.append("\n#ifdef __cplusplus\n}\n#endif\n\n#endif /* $guard */\n")
    return sb.toString()
}

internal fun FfiInteropCollector.buildCOrCppSourceContent(headerFileName: String): String =
    CWrapperHostSourceEmitter(this, headerFileName).emit()

private class CWrapperHostSourceEmitter(
    collector: FfiInteropCollector,
    private val headerFileName: String,
) : NativeHostBridgeSourceEmitter(collector, isCpp = false) {
    override val directReturnOnDispatch: Boolean = true
    override val defaultWindowsDllOrder: Pair<String, String> = "msvcrt.dll" to "ucrtbase.dll"

    override fun staticGetterDedupKey(st: ForeignStaticInteropSpec): String = st.getterSymbolName

    override fun staticSetterDedupKey(st: ForeignStaticInteropSpec): String = st.setterSymbolName

    override fun emitFileHeader(sb: StringBuilder) {
        val needsDynamicLookup =
            collector.foreignFunctions.any { collector.isBridgeableInCWrapper(it) && !collector.canCallDirectlyInCWrapper(it) } ||
                collector.foreignStatics.any(collector::isBridgeableStaticInCWrapper)
        if (needsDynamicLookup) {
            sb.append("#ifndef _GNU_SOURCE\n#define _GNU_SOURCE\n#endif\n")
        }
        sb.append("#include \"$headerFileName\"\n#include <errno.h>\n")

        val directForeignFunctions = collector.foreignFunctions.filter(collector::canCallDirectlyInCWrapper)
        val preambleIncludes = extractIncludedHeadersFromPreambles(collector.preambles)
        val inferredSpecs = inferRequiredHeaders(directForeignFunctions, collector.inlineFunctions, collector.explicitHeaders, preambleIncludes)

        val unguardedHeaders = linkedSetOf<String>()
        val guardedGroups = linkedMapOf<String, MutableSet<String>>()
        val winFallbackGroups = linkedSetOf<String>()

        if (collector.foreignFunctions.any { collector.isBridgeableInCWrapper(it) && isAtexitForeignFn(it) }) {
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
            if (hdr !in preambleIncludes) sb.append("#include ${formatIncludeTarget(hdr)}\n")
        }
        if (winFallbackGroups.isNotEmpty()) {
            sb.append("#ifdef _WIN32\n")
            for (hdr in winFallbackGroups) {
                if (hdr !in preambleIncludes) sb.append("#include ${formatIncludeTarget(hdr)}\n")
            }
            sb.append("#endif\n")
        }
        for ((cond, hdrs) in guardedGroups) {
            val filtered = hdrs.filter { it !in preambleIncludes }
            if (filtered.isEmpty()) continue
            sb.append("#if $cond\n")
            for (hdr in filtered) sb.append("#include ${formatIncludeTarget(hdr)}\n")
            sb.append("#endif\n")
        }
        if (needsDynamicLookup) appendWin32OrDlfcnDynamicHeaders(sb)
        sb.append('\n')
        appendPreambles(sb)
        if (collector.usesCppLanguage) sb.append("extern \"C\" {\n\n")
    }

    override fun renderStaticGetter(st: ForeignStaticInteropSpec): String {
        val sb = StringBuilder()
        val cType = cHeaderTypeForBridge(st.bridgeKind)
        sb.append("$cType ${st.getterSymbolName}(void) {\n")
        appendStaticSymbolPtrLookup(sb, st)
        sb.append("    if (km_sym_ptr != NULL) {\n        return *km_sym_ptr;\n    }\n")
        sb.append("    return ${cDefaultFailureLiteral(st.bridgeKind)};\n}\n")
        return sb.toString()
    }

    override fun renderStaticSetter(st: ForeignStaticInteropSpec): String {
        val sb = StringBuilder()
        val cType = cHeaderTypeForBridge(st.bridgeKind)
        sb.append("void ${st.setterSymbolName}($cType km_val) {\n")
        appendStaticSymbolPtrLookup(sb, st)
        sb.append("    if (km_sym_ptr != NULL) {\n        *km_sym_ptr = km_val;\n    }\n}\n")
        return sb.toString()
    }

    override fun renderForeignFunction(fn: ForeignFnInteropSpec): String =
        if (collector.canCallDirectlyInCWrapper(fn)) {
            renderDirectForeignWrapperCFunction(fn)
        } else {
            renderDynamicForeignWrapperCFunction(fn)
        }

    private fun renderDirectForeignWrapperCFunction(fn: ForeignFnInteropSpec): String {
        val sb = StringBuilder()
        val retType = cHeaderTypeForReturn(fn)
        sb.append("$retType ${fn.wrapperSymbolName}(${formatCParamsDecl(fn.params)}) {\n")

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

        if (symGuard != null) sb.append("#if $symGuard\n")
        when (fn.returnBridgeKind) {
            CAbiBridgeKind.VOID, CAbiBridgeKind.NEVER -> sb.append("    $callExpr;\n")
            else -> sb.append("    return ($retType)$callExpr;\n")
        }
        if (symGuard != null) {
            sb.append("#else\n")
            for (param in fn.params) sb.append("    (void)${param.cName};\n")
            sb.append("    errno = ENOSYS;\n")
            if (hasNonVoidReturn(fn.returnBridgeKind)) sb.append("    return ${cFailureReturnExpr(fn)};\n")
            sb.append("#endif\n")
        }
        sb.append("}\n")
        return sb.toString()
    }

    private fun renderDynamicForeignWrapperCFunction(fn: ForeignFnInteropSpec): String {
        val sb = StringBuilder()
        val retType = cHeaderTypeForReturn(fn)
        val isAtexit = isAtexitForeignFn(fn)

        sb.append("$retType ${fn.wrapperSymbolName}(${formatCParamsDecl(fn.params, fn.isVariadic)}) {\n")
        if (isAtexit) {
            val cbName = fn.params[0].cName
            appendAtexitGuardPrefix(
                sb = sb,
                nullCheckCond = "$cbName == NULL",
                invalidReturnStmt = "return -1;",
                androidReturnBlock = "    return (int32_t)atexit((void (*)(void))(uintptr_t)$cbName);\n",
            )
        }
        appendDynamicOrWin32DllDispatch(
            sb = sb,
            fn = fn,
            cRet = retType,
            cParamTypes = formatCFunctionParamTypes(fn),
            callArgs = fn.params.joinToString(", ") { it.cName },
            hasReturn = hasNonVoidReturn(fn.returnBridgeKind),
        )
        if (isAtexit) sb.append("#endif\n")
        sb.append("}\n")
        return sb.toString()
    }

    override fun renderInlineFunction(fn: InlineCFunctionSpec): String =
        renderInlineWrapperCFunction(fn)

    override fun emitFileFooter(sb: StringBuilder, emittedSymbols: Set<String>) {
        if (collector.usesCppLanguage) sb.append("} /* extern \"C\" */\n")
    }
}

internal fun renderInlineWrapperCFunction(fn: InlineCFunctionSpec): String {
    val sb = StringBuilder()
    sb.append("${cHeaderTypeForInlineReturn(fn)} ${fn.wrapperSymbolName}(${formatCParamsDecl(fn.params)}) {\n")
    for (param in fn.params) {
        if (!containsWholeIdentifier(fn.rawBody, param.cName)) {
            sb.append("    (void)${param.cName};\n")
        }
    }
    val bodyTrimmed = fn.rawBody.trim()
    if (bodyTrimmed.isNotEmpty()) {
        for (line in bodyTrimmed.lines()) {
            sb.append(if (line.isBlank()) "\n" else "    ${line.trimEnd()}\n")
        }
    }
    sb.append("}\n")
    return sb.toString()
}

internal fun FfiInteropCollector.buildNativeActualsKotlinContent(
    pkgName: String?,
    cinteropPkg: String,
): String = NativeActualsEmitter(this, pkgName, cinteropPkg).emit()

private class NativeActualsEmitter(
    collector: FfiInteropCollector,
    pkgName: String?,
    private val cinteropPkg: String,
) : PlatformActualsKotlinEmitter(collector, pkgName) {
    override val fileOptIns: List<String> = listOf("ExperimentalForeignApi::class", "ExperimentalUnsignedTypes::class")

    override fun initializeImports() {
        imports += "kotlinx.cinterop.ExperimentalForeignApi"
    }

    override fun convertParamArg(param: InteropParamSpec, noStringConvEnabled: Boolean): String =
        collector.renderNativeParamConversion(param, noStringConvEnabled, imports)

    override fun convertReturnExpr(
        rawCall: String,
        kotlinReturnType: String,
        returnBridgeKind: CAbiBridgeKind,
        returnDefaultExpr: String,
    ): String =
        collector.renderNativeReturnConversion(rawCall, kotlinReturnType, returnBridgeKind, returnDefaultExpr, pkgName, imports)

    private fun resolveCinteropCallee(wrapperSym: String): Pair<String, String> =
        if (wrapperSym in declaredSymbolsInModule) {
            val alias = "cinterop${toPascalCaseIdentifierPart(wrapperSym)}"
            alias to "$cinteropPkg.$wrapperSym as $alias"
        } else {
            wrapperSym to "$cinteropPkg.$wrapperSym"
        }

    override fun renderBridgeableStatic(st: ForeignStaticInteropSpec): String {
        val (getterCallee, getterImport) = resolveCinteropCallee(st.getterWrapperSymbolName)
        val setterPair = if (st.isMutable) resolveCinteropCallee(st.setterWrapperSymbolName) else null
        val rendered = renderStandardStaticProperty(st, getterCallee, setterPair?.first)
        val renderedTokens = extractIdentifierTokens(rendered)
        if (getterCallee in renderedTokens) imports += getterImport
        if (setterPair != null && setterPair.first in renderedTokens) imports += setterPair.second
        return rendered
    }

    override fun renderBridgeableCallable(entry: CallableInteropEntry): String {
        val (calleeIdent, calleeImport) = resolveCinteropCallee(entry.wrapperSymbolName)
        val rendered = renderStandardCallable(entry, calleeIdent)
        if (calleeIdent in extractIdentifierTokens(rendered)) imports += calleeImport
        return rendered
    }
}

internal fun wasmWasiScalarType(kind: CAbiBridgeKind): String? =
    bridgeDescriptorFor(kind).wasmWasiScalarType

internal fun FfiInteropCollector.renderWasmWasiScalarArgument(param: InteropParamSpec): String =
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

internal fun FfiInteropCollector.renderWasmWasiScalarReturn(
    rawCall: String,
    kotlinType: String,
    kind: CAbiBridgeKind,
): String {
    if (kind == CAbiBridgeKind.BOOL) return "($rawCall != 0)"
    if (kind == CAbiBridgeKind.VOID || kind == CAbiBridgeKind.NEVER) return rawCall
    val numericKind = resolveNumericKind(kotlinType)
    val conversion =
        if (numericKind != null) {
            conversionMethodForNumericKind(numericKind)
        } else {
            bridgeDescriptorFor(kind).defaultReturnConv
                ?: when (kind) {
                    CAbiBridgeKind.I8 -> "toByte"
                    CAbiBridgeKind.I16 -> "toShort"
                    else -> null
                }
        }
    return if (conversion == null) rawCall else "$rawCall.$conversion()"
}

internal fun FfiInteropCollector.buildWasmWasiActualsKotlinContent(pkgName: String?): String =
    WasmWasiActualsEmitter(this, pkgName).emit()

private class WasmWasiActualsEmitter(
    collector: FfiInteropCollector,
    pkgName: String?,
) : PlatformActualsKotlinEmitter(collector, pkgName) {
    private val module: String = computeFfiModuleNameLower(collector.headerContext)
    private val emittedImports = linkedSetOf<String>()

    override val fileOptIns: List<String> = listOf("ExperimentalUnsignedTypes::class", "ExperimentalWasmInterop::class")
    override val leadingComment: String =
        "// The host must provide the C companion wrapper exports in import module $module."

    private fun importFunction(
        symbol: String,
        params: List<InteropParamSpec>,
        returnKind: CAbiBridgeKind,
    ): String {
        val name = "wasi${toPascalCaseIdentifierPart(symbol)}"
        if (emittedImports.add(symbol)) {
            val paramsSig = params.joinToString(", ") { "${it.kotlinName}: ${wasmWasiScalarType(it.bridgeKind)}" }
            actualBlocks +=
                "@kotlin.wasm.WasmImport(\"$module\", \"$symbol\")\n" +
                "private external fun $name($paramsSig): ${wasmWasiScalarType(returnKind)}"
        }
        return name
    }

    private fun renderWasmWasiStatic(st: ForeignStaticInteropSpec): String {
        val isScalar = wasmWasiScalarType(st.bridgeKind) !in listOf(null, "Unit")
        val unsupportedExpr =
            "throw UnsupportedOperationException(\"WasmWasi foreign static ${st.cSymbolName} requires a host memory/aggregate ABI adapter\")"
        val getter =
            if (isScalar) {
                val callee = importFunction(st.getterSymbolName, emptyList(), st.bridgeKind)
                collector.renderWasmWasiScalarReturn("$callee()", st.kotlinType, st.bridgeKind)
            } else {
                unsupportedExpr
            }
        val setter =
            when {
                !st.isMutable -> null
                isScalar -> {
                    val param = st.asSetterParamSpec()
                    val callee = importFunction(st.setterSymbolName, listOf(param), CAbiBridgeKind.VOID)
                    "$callee(${collector.renderWasmWasiScalarArgument(param)})"
                }
                else -> unsupportedExpr
            }
        return renderStaticPropertyDecl(st, getter, setter)
    }

    private fun renderWasmWasiCallable(entry: CallableInteropEntry): String {
        if (entry.isVariadicFallback ||
            wasmWasiScalarType(entry.returnBridgeKind) == null ||
            entry.params.any { wasmWasiScalarType(it.bridgeKind) in listOf(null, "Unit") }
        ) {
            return collector.renderUnsupportedActualFunction(
                entry.visibilityPrefix,
                entry.kotlinName,
                entry.params,
                entry.kotlinReturnType,
                entry.returnBridgeKind,
                entry.isVariadicFallback,
                "WasmWasi FFI requires a host memory/callback/aggregate or variadic ABI adapter for ${entry.wrapperSymbolName}",
            )
        }
        val callee = importFunction(entry.wrapperSymbolName, entry.params, entry.returnBridgeKind)
        val signature = formatKotlinActualParamsSig(entry.params, isVariadic = false)
        val call = "$callee(${entry.params.joinToString(", ") { collector.renderWasmWasiScalarArgument(it) }})"
        return if (entry.returnBridgeKind == CAbiBridgeKind.NEVER) {
            "${entry.visibilityPrefix}actual fun ${entry.kotlinName}($signature): Nothing {\n" +
                "    $call\n    error(\"C noreturn function ${entry.wrapperSymbolName} returned\")\n}"
        } else {
            val result = collector.renderWasmWasiScalarReturn(call, entry.kotlinReturnType, entry.returnBridgeKind)
            "${entry.visibilityPrefix}actual fun ${entry.kotlinName}($signature): ${entry.kotlinReturnType} =\n    $result"
        }
    }

    override fun renderBridgeableStatic(st: ForeignStaticInteropSpec): String = renderWasmWasiStatic(st)

    override fun renderUnbridgeableStatic(st: ForeignStaticInteropSpec): String = renderWasmWasiStatic(st)

    override fun renderBridgeableCallable(entry: CallableInteropEntry): String = renderWasmWasiCallable(entry)

    override fun renderUnbridgeableCallable(entry: CallableInteropEntry): String = renderWasmWasiCallable(entry)
}

internal fun wasmJsInteropTypeForParam(
    param: InteropParamSpec,
    noStringConvEnabled: Boolean,
    ktStringType: String = "String",
): String =
    when {
        param.isDirectStringBridge(noStringConvEnabled) -> "$ktStringType?"
        param.bridgeKind == CAbiBridgeKind.BY_VALUE_OBJECT -> ktStringType
        else -> bridgeDescriptorFor(param.bridgeKind).wasmJsType ?: ktStringType
    }

internal fun wasmJsInteropTypeForReturn(
    returnBridgeKind: CAbiBridgeKind,
    ktStringType: String = "String",
): String =
    bridgeDescriptorFor(returnBridgeKind).wasmJsType ?: ktStringType

internal fun renderWasmJsExternalDecl(
    wrapperSymbolName: String,
    paramTypes: List<String>,
    returnType: String,
    ffiModName: String,
): String {
    val jsParamNames = paramTypes.indices.joinToString(", ") { "p$it" }
    val ktParams = paramTypes.mapIndexed { idx, t -> "p$idx: $t" }.joinToString(", ")
    val retSuffix = if (returnType == "Unit") "" else ": $returnType"
    val wasmFnName = "wasm${toPascalCaseIdentifierPart(wrapperSymbolName)}"
    return "@JsFun(\"($jsParamNames) => globalThis.__km_napi_$ffiModName.$wrapperSymbolName($jsParamNames)\")\n" +
        "private external fun $wasmFnName($ktParams)$retSuffix"
}

internal fun FfiInteropCollector.buildWasmJsActualsKotlinContent(
    pkgName: String?,
    stemPascal: String,
    ffiModName: String,
): String = WasmJsActualsEmitter(this, pkgName, stemPascal, ffiModName).emit()

private class WasmJsActualsEmitter(
    collector: FfiInteropCollector,
    pkgName: String?,
    private val stemPascal: String,
    private val ffiModName: String,
) : PlatformActualsKotlinEmitter(collector, pkgName) {
    private val availPropName = "${stemPascal.replaceFirstChar { it.lowercase() }}NapiAvailable"
    private val externalDecls = mutableListOf<String>()
    private val emittedExternals = linkedSetOf<String>()
    private var usesAddon = false

    override val fileOptIns: List<String> = listOf("ExperimentalUnsignedTypes::class", "ExperimentalWasmJsInterop::class")
    override val unbridgeableThrowsAddonError: Boolean = true
    override val stringifyVariadicArgs: Boolean = true
    override val addonAvailabilityGuard: String = availPropName

    override fun convertParamArg(param: InteropParamSpec, noStringConvEnabled: Boolean): String =
        collector.renderJsOrWasmParamConversion(param, noStringConvEnabled)

    override fun convertReturnExpr(
        rawCall: String,
        kotlinReturnType: String,
        returnBridgeKind: CAbiBridgeKind,
        returnDefaultExpr: String,
    ): String =
        collector.renderWasmJsReturnConversion(rawCall, kotlinReturnType, returnBridgeKind, returnDefaultExpr, pkgName, imports)

    private fun registerExternalIfNeeded(wrapperSymbol: String, paramTypes: List<String>, returnType: String) {
        if (emittedExternals.add(wrapperSymbol)) {
            externalDecls += renderWasmJsExternalDecl(wrapperSymbol, paramTypes, returnType, ffiModName)
        }
    }

    override fun renderBridgeableStatic(st: ForeignStaticInteropSpec): String {
        usesAddon = true
        registerExternalIfNeeded(st.getterWrapperSymbolName, emptyList(), wasmJsInteropTypeForReturn(st.bridgeKind, ktStringType))
        if (st.isMutable) {
            val setterType = wasmJsInteropTypeForParam(st.asSetterParamSpec(), noStringConvEnabled = true, ktStringType = ktStringType)
            registerExternalIfNeeded(st.setterWrapperSymbolName, listOf(setterType), "Unit")
        }
        return renderStandardStaticProperty(
            st = st,
            getterCallee = "wasm${toPascalCaseIdentifierPart(st.getterWrapperSymbolName)}",
            setterCallee = "wasm${toPascalCaseIdentifierPart(st.setterWrapperSymbolName)}",
        )
    }

    override fun onBeforeDedupBridgeableCallable(entry: CallableInteropEntry) {
        val baseParamTypes = entry.params.map { wasmJsInteropTypeForParam(it, entry.noStringConvEnabled, ktStringType) }
        val allParamTypes =
            if (entry.isVariadicCall) {
                baseParamTypes + listOf("Int", ktStringType, ktStringType, ktStringType, ktStringType)
            } else {
                baseParamTypes
            }
        registerExternalIfNeeded(entry.wrapperSymbolName, allParamTypes, wasmJsInteropTypeForReturn(entry.returnBridgeKind, ktStringType))
    }

    override fun renderBridgeableCallable(entry: CallableInteropEntry): String {
        usesAddon = true
        return renderStandardCallable(entry, "wasm${toPascalCaseIdentifierPart(entry.wrapperSymbolName)}")
    }

    override fun prependPreambleBlocks(blocks: MutableList<String>) {
        if (!usesAddon) return
        val initJs = "() => { ${buildNodeNapiLoaderJsBody(ffiModName, returnBooleanStatus = true)}}"
        blocks.add(
            0,
            "@JsFun(\"$initJs\")\n" +
                "private external fun init${stemPascal}NapiAddon(): Boolean\n\n" +
                "private val $availPropName: Boolean = init${stemPascal}NapiAddon()\n\n" +
                externalDecls.joinToString("\n\n"),
        )
    }
}

internal fun FfiInteropCollector.renderWasmJsReturnConversion(
    rawCall: String,
    kotlinReturnType: String,
    returnBridgeKind: CAbiBridgeKind,
    returnDefaultExpr: String,
    pkgName: String?,
    importsOut: MutableSet<String>,
): String =
    renderBridgeReturnConversion(
        rawCall = rawCall,
        kotlinReturnType = kotlinReturnType,
        returnBridgeKind = returnBridgeKind,
        returnDefaultExpr = returnDefaultExpr,
        pkgName = pkgName,
        importsOut = importsOut,
        profile =
            BridgeReturnConversionProfile(
                subIntBase = rawCall,
                i64Base = "$rawCall.toLong()",
                u64Base = "$rawCall.toULong()",
                ptrLongExpr = "$rawCall.toLong()",
                boolExpr = rawCall,
                f32Expr = "$rawCall.toFloat()",
                f64Expr = rawCall,
                fallbackNonScalar = returnDefaultExpr,
            ),
    )

internal fun FfiInteropCollector.renderPointerParamToCPointer(
    param: InteropParamSpec,
    importsOut: MutableSet<String>,
): String {
    importsOut += "kotlinx.cinterop.ByteVar"
    importsOut += "kotlinx.cinterop.toCPointer"
    val longAddr = renderPointerParamToLong(param)
    return "$longAddr.toCPointer<ByteVar>()"
}

internal fun FfiInteropCollector.renderNativeParamConversion(
    param: InteropParamSpec,
    noStringConvEnabled: Boolean,
    importsOut: MutableSet<String>,
): String {
    val desc = bridgeDescriptorFor(param.bridgeKind)
    if (param.bridgeKind == CAbiBridgeKind.CONST_CSTR) {
        return when {
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
    if (desc.isPointerAddress) return renderPointerParamToCPointer(param, importsOut)
    val nk = desc.numericKind
    if (nk != null && nk.isIntegerNumericKind()) {
        return renderScalarParamArg(param, nk, conversionMethodForNumericKind(nk))
    }
    return param.kotlinName
}

internal fun FfiInteropCollector.renderNativeReturnConversion(
    rawCall: String,
    kotlinReturnType: String,
    returnBridgeKind: CAbiBridgeKind,
    returnDefaultExpr: String,
    pkgName: String?,
    importsOut: MutableSet<String>,
): String {
    val ptrLong = "$rawCall?.toLong() ?: 0L"
    return renderBridgeReturnConversion(
        rawCall = rawCall,
        kotlinReturnType = kotlinReturnType,
        returnBridgeKind = returnBridgeKind,
        returnDefaultExpr = returnDefaultExpr,
        pkgName = pkgName,
        importsOut = importsOut,
        profile =
            BridgeReturnConversionProfile(
                subIntBase = rawCall,
                i64Base = rawCall,
                u64Base = rawCall,
                ptrLongExpr = ptrLong,
                ptrLongReceiverExpr = "($ptrLong)",
                boolExpr = rawCall,
                f32Expr = rawCall,
                f64Expr = rawCall,
                nativeTypedIntegrals = true,
                callbackConvertsNonLongNumeric = true,
                pointerImport = "kotlinx.cinterop.toLong",
                fallbackNonScalar = rawCall,
            ),
    )
}

internal fun inferRequiredHeaders(
    foreignFunctions: List<ForeignFnInteropSpec>,
    inlineFunctions: List<InlineCFunctionSpec>,
    explicitHeaders: Set<String>,
    preambleIncludes: Set<String>,
): List<PlatformHeaderSpec> {
    val specs = mutableListOf<PlatformHeaderSpec>()
    explicitHeaders.mapTo(specs, ::classifyHeaderGuard)
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
            if (containsWholeIdentifier(fn.rawBody, sym)) specs.addAll(hdrList)
        }
    }
    return specs.filter { it.header !in preambleIncludes }.distinct()
}

private const val BSD_APPLE_GUARD =
    "defined(__APPLE__) || defined(__FreeBSD__) || defined(__NetBSD__) || defined(__OpenBSD__)"

private fun classifyHeaderGuard(header: String): PlatformHeaderSpec {
    val target = formatIncludeTarget(header)
    return when {
        header in PORTABLE_STD_HEADERS -> PlatformHeaderSpec(header)
        header.startsWith("linux/") || header == "sys/epoll.h" || header == "sys/eventfd.h" || header == "sys/signalfd.h" ->
            PlatformHeaderSpec(header, guardCondition = "defined(__linux__)")
        header == "sys/event.h" || header.startsWith("mach/") ->
            PlatformHeaderSpec(header, guardCondition = BSD_APPLE_GUARD)
        header == "windows.h" || header == "winsock2.h" || header == "ws2tcpip.h" || header == "io.h" || header == "direct.h" ->
            PlatformHeaderSpec(header, guardCondition = "defined(_WIN32)")
        header in COMMON_POSIX_HEADERS ->
            PlatformHeaderSpec(header, guardCondition = "!defined(_WIN32)")
        else ->
            PlatformHeaderSpec(header, guardCondition = "!defined(_WIN32) && defined(__has_include) && __has_include($target)")
    }
}

internal fun symbolPlatformGuard(cSymbol: String): String? =
    when (cSymbol) {
        in LINUX_ONLY_SYMBOLS -> "defined(__linux__)"
        in BSD_APPLE_ONLY_SYMBOLS -> BSD_APPLE_GUARD
        in POSIX_NON_WIN32_SYMBOLS -> "!defined(_WIN32)"
        else -> null
    }

private fun headersForCSymbol(symbol: String): List<PlatformHeaderSpec> =
    C_SYMBOL_HEADERS[symbol].orEmpty()

private val PORTABLE_STD_HEADERS: Set<String> =
    wordSet(
        "assert.h ctype.h errno.h float.h inttypes.h limits.h locale.h math.h setjmp.h signal.h",
        "stdarg.h stdbool.h stddef.h stdint.h stdio.h stdlib.h string.h time.h wchar.h wctype.h",
        "fcntl.h sys/types.h sys/stat.h",
    )

private val COMMON_POSIX_HEADERS: Set<String> =
    wordSet(
        "unistd.h sys/mman.h sys/socket.h netinet/in.h arpa/inet.h netdb.h dlfcn.h pthread.h",
        "semaphore.h poll.h sys/ioctl.h sys/wait.h dirent.h sched.h strings.h utime.h sys/time.h",
        "sys/resource.h sys/uio.h sys/un.h sys/utsname.h termios.h pwd.h grp.h syslog.h fnmatch.h glob.h",
    )

private val LINUX_ONLY_SYMBOLS: Set<String> =
    wordSet("epoll_create epoll_create1 epoll_ctl epoll_wait eventfd signalfd timerfd_create timerfd_settime timerfd_gettime memfd_create prctl")

private val BSD_APPLE_ONLY_SYMBOLS: Set<String> = wordSet("kqueue kevent")

private val POSIX_NON_WIN32_SYMBOLS: Set<String> =
    wordSet(
        "fork pipe link symlink readlink chown fchown getuid geteuid getgid getegid setenv unsetenv",
        "mmap munmap mprotect msync mlock munlock dlopen dlsym dlclose dlerror pthread_self",
        "pthread_equal pthread_detach pthread_join pthread_kill sem_init sem_destroy sem_wait",
        "sem_trywait sem_post poll ioctl wait waitpid opendir readdir closedir rewinddir chmod",
        "fchmod lstat mkfifo mknod sysconf sleep usleep strndup aligned_alloc fcntl openat fsync",
        "ftruncate truncate kill socketpair",
    )

internal fun isDirectCallSafeSymbol(fn: ForeignFnInteropSpec): Boolean =
    fn.dllName == null && DIRECT_C_SYMBOL_ARITY[fn.cSymbolName] == fn.params.size

private val DIRECT_C_SYMBOL_ARITY: Map<String, Int> =
    buildMap {
        fun reg(arity: Int, names: String) {
            for (name in names.split(' ')) if (name.isNotEmpty()) put(name, arity)
        }
        reg(0, "abort rand getchar tmpfile clock localeconv getpid geteuid getuid getegid getgid dlerror kqueue")
        reg(
            1,
            "malloc free exit atexit getenv abs llabs atoi atol atoll atof srand fclose fflush fgetc " +
                "getc putchar puts ftell rewind clearerr feof ferror perror remove strlen strerror strdup " +
                "isalnum isalpha isblank iscntrl isdigit isgraph islower isprint ispunct isspace isupper isxdigit " +
                "tolower toupper sin cos tan asin acos atan exp log log10 sqrt ceil floor fabs round trunc time " +
                "mktime gmtime localtime asctime ctime raise umask close dup pipe chdir unlink rmdir isatty sleep " +
                "usleep fsync sysconf unsetenv dlclose wait opendir readdir closedir rewinddir epoll_create epoll_create1",
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
        fun register(headers: List<PlatformHeaderSpec>, symbols: String) {
            for (sym in symbols.split(' ')) if (sym.isNotEmpty()) put(sym, headers)
        }
        register(
            listOf(PlatformHeaderSpec("stdlib.h")),
            "malloc calloc realloc free aligned_alloc abort exit atexit getenv system bsearch qsort " +
                "abs labs llabs div ldiv lldiv atoi atol atoll atof strtod strtof strtol strtoll strtoul strtoull rand srand",
        )
        register(
            listOf(PlatformHeaderSpec("stdio.h")),
            "fopen freopen fclose fflush setbuf setvbuf fread fwrite fgetc fgets fputc fputs " +
                "getc getchar putchar puts ungetc fseek ftell rewind clearerr feof ferror perror remove rename tmpfile tmpnam snprintf",
        )
        register(
            listOf(PlatformHeaderSpec("string.h")),
            "memcpy memmove memset memcmp memchr strcpy strncpy strcat strncat strcmp strncmp " +
                "strcoll strxfrm strchr strrchr strcspn strspn strpbrk strstr strtok strlen strnlen strerror strdup strndup",
        )
        register(
            listOf(PlatformHeaderSpec("ctype.h")),
            "isalnum isalpha isblank iscntrl isdigit isgraph islower isprint ispunct isspace isupper isxdigit tolower toupper",
        )
        register(
            listOf(PlatformHeaderSpec("math.h")),
            "sin cos tan asin acos atan atan2 exp log log10 pow sqrt ceil floor fabs fmod round trunc",
        )
        register(listOf(PlatformHeaderSpec("time.h")), "clock time difftime mktime strftime gmtime localtime asctime ctime")
        register(listOf(PlatformHeaderSpec("signal.h")), "signal raise kill")
        register(listOf(PlatformHeaderSpec("locale.h")), "setlocale localeconv")
        register(listOf(PlatformHeaderSpec("fcntl.h")), "open creat fcntl openat")
        register(
            listOf(PlatformHeaderSpec("sys/types.h"), PlatformHeaderSpec("sys/stat.h")),
            "stat fstat lstat mkdir chmod fchmod umask mkfifo mknod",
        )
        fun registerPosix(header: String, symbols: String, winFallbacks: List<String> = emptyList()) {
            register(listOf(PlatformHeaderSpec(header, "!defined(_WIN32)", winFallbacks)), symbols)
        }
        registerPosix(
            "unistd.h",
            "read write close lseek dup dup2 pipe chdir getcwd unlink rmdir access isatty getpid " +
                "sleep usleep fsync ftruncate truncate link symlink readlink chown fchown geteuid getuid getegid getgid fork sysconf",
            listOf("io.h", "process.h", "direct.h"),
        )
        register(listOf(PlatformHeaderSpec("stdlib.h"), PlatformHeaderSpec("unistd.h", "!defined(_WIN32)")), "setenv unsetenv")
        registerPosix("sys/mman.h", "mmap munmap mprotect msync mlock munlock")
        registerPosix(
            "sys/socket.h",
            "socket bind listen accept connect send recv sendto recvfrom setsockopt getsockopt shutdown getsockname getpeername socketpair",
            listOf("winsock2.h", "ws2tcpip.h"),
        )
        registerPosix("dlfcn.h", "dlopen dlsym dlclose dlerror")
        registerPosix("pthread.h", "pthread_create pthread_join pthread_detach pthread_self pthread_equal pthread_kill")
        registerPosix("semaphore.h", "sem_init sem_destroy sem_wait sem_trywait sem_post")
        registerPosix("poll.h", "poll")
        registerPosix("sys/ioctl.h", "ioctl")
        registerPosix("sys/wait.h", "wait waitpid")
        registerPosix("dirent.h", "opendir readdir closedir rewinddir")
        register(listOf(PlatformHeaderSpec("sys/epoll.h", "defined(__linux__)")), "epoll_create epoll_create1 epoll_ctl epoll_wait")
        register(listOf(PlatformHeaderSpec("sys/event.h", BSD_APPLE_GUARD)), "kqueue kevent")
    }
