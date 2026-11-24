package io.github.kotlinmania.rustkotlin

/**
 * Unified callable entry across both `extern "C"` foreign functions and inline `c!` / `cpp!` blocks.
 */
internal data class CallableInteropEntry(
    val visibilityPrefix: String,
    val kotlinName: String,
    val wrapperSymbolName: String,
    val params: List<InteropParamSpec>,
    val kotlinReturnType: String,
    val returnBridgeKind: CAbiBridgeKind,
    val returnDefaultExpr: String,
    val noStringConvEnabled: Boolean,
    val isVariadicCall: Boolean,
    val isVariadicFallback: Boolean,
    val isBridgeable: Boolean,
)

/**
 * Extensible base class for emitting platform-specific `actual` Kotlin source files
 * (`NativeActuals.kt`, `JvmActuals.kt`, `AndroidActuals.kt`, `JsActuals.kt`, `WasmJsActuals.kt`, `WasmWasiActuals.kt`).
 *
 * Centralizes header generation, `foreignStatics` / `foreignFunctions` / `inlineFunctions`
 * deduplication, static `actual val`/`var` property formatting, `actual fun` wrapper formatting,
 * preamble injection, and `PhantomRawPointerTypeStripper` + import sorting finalization.
 */
internal abstract class PlatformActualsKotlinEmitter(
    protected val collector: FfiInteropCollector,
    protected val pkgName: String?,
) {
    protected val imports: LinkedHashSet<String> = linkedSetOf()
    protected val actualBlocks: MutableList<String> = mutableListOf()
    protected val emittedActuals: LinkedHashSet<String> = linkedSetOf()
    protected val ktStringType: String = if (collector.shadowsKotlinString(pkgName)) "kotlin.String" else "String"

    protected abstract val fileOptIns: List<String>

    protected open val leadingComment: String? = null

    protected open val unbridgeableThrowsAddonError: Boolean = false

    protected open val stringifyVariadicArgs: Boolean = false

    protected open val addonAvailabilityGuard: String? = null

    protected open fun initializeImports() = Unit

    protected abstract fun renderBridgeableStatic(st: ForeignStaticInteropSpec): String

    protected open fun renderUnbridgeableStatic(st: ForeignStaticInteropSpec): String =
        collector.renderStaticActualProperty(st)

    protected open fun onBeforeDedupBridgeableCallable(entry: CallableInteropEntry) = Unit

    protected abstract fun renderBridgeableCallable(entry: CallableInteropEntry): String

    protected open fun renderUnbridgeableCallable(entry: CallableInteropEntry): String =
        if (unbridgeableThrowsAddonError) {
            collector.renderUnsupportedActualFunction(
                visibilityPrefix = entry.visibilityPrefix,
                kotlinName = entry.kotlinName,
                params = entry.params,
                kotlinReturnType = entry.kotlinReturnType,
                returnBridgeKind = entry.returnBridgeKind,
                isVariadic = entry.isVariadicFallback,
                message = "Native FFI addon is not available",
            )
        } else {
            collector.renderFallbackActualFunction(
                visibilityPrefix = entry.visibilityPrefix,
                kotlinName = entry.kotlinName,
                params = entry.params,
                kotlinReturnType = entry.kotlinReturnType,
                returnBridgeKind = entry.returnBridgeKind,
                returnDefaultExpr = entry.returnDefaultExpr,
                isVariadic = entry.isVariadicFallback,
            )
        }

    protected open fun prependPreambleBlocks(blocks: MutableList<String>) = Unit

    fun emit(): String {
        val sb = StringBuilder()
        if (fileOptIns.isNotEmpty()) {
            sb.append("@file:OptIn(").append(fileOptIns.joinToString(", ")).append(")\n\n")
        }
        if (!pkgName.isNullOrBlank()) {
            sb.append("package ").append(pkgName).append("\n\n")
        }
        initializeImports()

        for (st in collector.foreignStatics) {
            if (!emittedActuals.add("val:${st.kotlinName}")) continue
            actualBlocks +=
                if (collector.isBridgeableStaticInCWrapper(st)) {
                    renderBridgeableStatic(st)
                } else {
                    renderUnbridgeableStatic(st)
                }
        }

        for (entry in collectAllCallableEntries()) {
            if (entry.isBridgeable) {
                onBeforeDedupBridgeableCallable(entry)
            }
            if (!emittedActuals.add("fn:${entry.kotlinName}:${entry.params.size}")) continue
            actualBlocks +=
                if (entry.isBridgeable) {
                    renderBridgeableCallable(entry)
                } else {
                    renderUnbridgeableCallable(entry)
                }
        }

        prependPreambleBlocks(actualBlocks)
        return collector.finalizeActualsKotlinFile(
            sb = sb,
            actualBlocks = actualBlocks,
            imports = imports,
            pkgName = pkgName,
            leadingComment = leadingComment,
        )
    }

    private fun collectAllCallableEntries(): List<CallableInteropEntry> =
        buildList(collector.foreignFunctions.size + collector.inlineFunctions.size) {
            for (fn in collector.foreignFunctions) {
                add(
                    CallableInteropEntry(
                        visibilityPrefix = fn.visibilityPrefix,
                        kotlinName = fn.kotlinName,
                        wrapperSymbolName = fn.wrapperSymbolName,
                        params = fn.params,
                        kotlinReturnType = fn.kotlinReturnType,
                        returnBridgeKind = fn.returnBridgeKind,
                        returnDefaultExpr = fn.returnDefaultExpr,
                        noStringConvEnabled = requiresNoStringConversion(fn.params),
                        isVariadicCall = fn.isVariadic,
                        isVariadicFallback = fn.isVariadic,
                        isBridgeable = collector.isBridgeableInCWrapper(fn),
                    ),
                )
            }
            for (fn in collector.inlineFunctions) {
                add(
                    CallableInteropEntry(
                        visibilityPrefix = fn.visibilityPrefix,
                        kotlinName = fn.kotlinName,
                        wrapperSymbolName = fn.wrapperSymbolName,
                        params = fn.params,
                        kotlinReturnType = fn.kotlinReturnType,
                        returnBridgeKind = fn.returnBridgeKind,
                        returnDefaultExpr = fn.returnDefaultExpr,
                        noStringConvEnabled = requiresNoStringConversionForInline(fn.params),
                        isVariadicCall = false,
                        isVariadicFallback = fn.isVariadic,
                        isBridgeable = collector.isBridgeableInlineInCWrapper(fn),
                    ),
                )
            }
        }

    protected fun renderStaticPropertyDecl(
        st: ForeignStaticInteropSpec,
        convertedGetExpr: String,
        setCallExpr: String? = null,
    ): String {
        val guard = addonAvailabilityGuard
        val getExpr =
            if (guard != null) {
                "if ($guard) $convertedGetExpr else throw UnsupportedOperationException(\"Native FFI addon is not available\")"
            } else {
                convertedGetExpr
            }
        if (!st.isMutable) {
            return "${st.visibilityPrefix}actual val ${st.kotlinName}: ${st.kotlinType}\n" +
                "    get() = $getExpr"
        }
        val setterStmt = setCallExpr ?: error("Mutable static ${st.kotlinName} requires setCallExpr")
        return if (guard != null) {
            "${st.visibilityPrefix}actual var ${st.kotlinName}: ${st.kotlinType}\n" +
                "    get() = $getExpr\n" +
                "    set(value) {\n" +
                "        if ($guard) {\n" +
                "            $setterStmt\n" +
                "        } else {\n" +
                "            throw UnsupportedOperationException(\"Native FFI addon is not available\")\n" +
                "        }\n" +
                "    }"
        } else {
            "${st.visibilityPrefix}actual var ${st.kotlinName}: ${st.kotlinType}\n" +
                "    get() = $getExpr\n" +
                "    set(value) {\n" +
                "        $setterStmt\n" +
                "    }"
        }
    }

    protected fun renderStandardBridgeableCallable(
        entry: CallableInteropEntry,
        calleeExpr: String,
        convertParam: (InteropParamSpec, Boolean) -> String,
        convertReturn: (String, CallableInteropEntry) -> String,
    ): String {
        val baseArgs = entry.params.map { convertParam(it, entry.noStringConvEnabled) }
        val allArgs =
            if (entry.isVariadicCall) {
                appendVariadicKotlinArgs(baseArgs, stringifyArgs = stringifyVariadicArgs)
            } else {
                baseArgs
            }
        val rawCall = "$calleeExpr(${allArgs.joinToString(", ")})"
        val bodyExpr = convertReturn(rawCall, entry)
        return renderActualFunctionDecl(
            visibilityPrefix = entry.visibilityPrefix,
            kotlinName = entry.kotlinName,
            params = entry.params,
            kotlinReturnType = entry.kotlinReturnType,
            returnBridgeKind = entry.returnBridgeKind,
            isVariadic = entry.isVariadicCall,
            rawCall = rawCall,
            bodyExpr = bodyExpr,
            addonAvailabilityGuard = addonAvailabilityGuard,
        )
    }
}

/**
 * Extensible base class for emitting native host C/C++ bridge files
 * (`*_wrapper.c`, `*_jni.c`, and `*_bindings.cpp` N-API addons).
 *
 * Centralizes `#ifdef _WIN32` / `<dlfcn.h>` dynamic symbol headers, preamble emission,
 * deduplicated static/foreign/inline symbol iteration, Win32 DLL `LoadLibraryA` / `GetProcAddress`
 * and POSIX `dlsym` symbol resolution, variadic call dispatch, and Android `atexit` guards.
 */
internal abstract class NativeHostBridgeSourceEmitter(
    protected val collector: FfiInteropCollector,
    protected val isCpp: Boolean,
) {
    protected val nullLit: String = if (isCpp) "nullptr" else "NULL"

    protected open val directReturnOnDispatch: Boolean = false

    protected open val defaultWindowsDllOrder: Pair<String, String> = "ucrtbase.dll" to "msvcrt.dll"

    protected open fun staticGetterDedupKey(st: ForeignStaticInteropSpec): String = st.getterWrapperSymbolName

    protected open fun staticSetterDedupKey(st: ForeignStaticInteropSpec): String = st.setterWrapperSymbolName

    protected abstract fun emitFileHeader(sb: StringBuilder)

    protected abstract fun renderStaticGetter(st: ForeignStaticInteropSpec): String

    protected abstract fun renderStaticSetter(st: ForeignStaticInteropSpec): String

    protected abstract fun renderForeignFunction(fn: ForeignFnInteropSpec): String

    protected abstract fun renderInlineFunction(fn: InlineCFunctionSpec): String

    protected open fun emitFileFooter(
        sb: StringBuilder,
        emittedSymbols: Set<String>,
    ) = Unit

    fun emit(): String {
        val sb = StringBuilder()
        emitFileHeader(sb)
        val emitted = linkedSetOf<String>()
        for (st in collector.foreignStatics) {
            if (!collector.isBridgeableStaticInCWrapper(st)) continue
            if (emitted.add(staticGetterDedupKey(st))) {
                sb.append(renderStaticGetter(st)).append('\n')
            }
            if (st.isMutable && emitted.add(staticSetterDedupKey(st))) {
                sb.append(renderStaticSetter(st)).append('\n')
            }
        }
        for (fn in collector.foreignFunctions) {
            if (!collector.isBridgeableInCWrapper(fn)) continue
            if (!emitted.add(fn.wrapperSymbolName)) continue
            sb.append(renderForeignFunction(fn)).append('\n')
        }
        for (fn in collector.inlineFunctions) {
            if (!collector.isBridgeableInlineInCWrapper(fn)) continue
            if (!emitted.add(fn.wrapperSymbolName)) continue
            sb.append(renderInlineFunction(fn)).append('\n')
        }
        emitFileFooter(sb, emitted)
        return sb.toString()
    }

    protected fun appendWin32OrDlfcnDynamicHeaders(sb: StringBuilder) {
        sb.append("#ifdef _WIN32\n")
        sb.append("#ifndef WIN32_LEAN_AND_MEAN\n#define WIN32_LEAN_AND_MEAN\n#endif\n")
        sb.append("#include <windows.h>\n")
        sb.append("#else\n")
        sb.append("#include <dlfcn.h>\n")
        sb.append("#ifndef RTLD_DEFAULT\n#define RTLD_DEFAULT ((void*)0)\n#endif\n")
        sb.append("#endif\n")
    }

    protected fun appendPreambles(sb: StringBuilder) {
        if (collector.preambles.isNotEmpty()) {
            for (chunk in collector.preambles) {
                sb.append(chunk).append("\n\n")
            }
        }
    }

    protected fun formatCFunctionParamTypes(fn: ForeignFnInteropSpec): String {
        val cParamTypesOnly = fn.params.joinToString(", ") { cHeaderTypeForParam(it) }
        return when {
            fn.isVariadic -> "$cParamTypesOnly, ..."
            fn.params.isEmpty() -> "void"
            else -> cParamTypesOnly
        }
    }

    protected fun hasNonVoidReturn(returnBridgeKind: CAbiBridgeKind): Boolean =
        returnBridgeKind != CAbiBridgeKind.VOID && returnBridgeKind != CAbiBridgeKind.NEVER

    protected fun appendAtexitGuardPrefix(
        sb: StringBuilder,
        nullCheckCond: String,
        invalidReturnStmt: String,
        androidReturnBlock: String,
    ) {
        sb.append("    if ($nullCheckCond) {\n")
        sb.append("        errno = EINVAL;\n")
        sb.append("        $invalidReturnStmt\n")
        sb.append("    }\n")
        sb.append("#ifdef __ANDROID__\n")
        sb.append(androidReturnBlock)
        sb.append("#else\n")
    }

    protected fun appendDynamicSymbolLookup(
        sb: StringBuilder,
        ptrVarName: String,
        ptrTypeDecl: String,
        castTargetType: String,
        cSymbolName: String,
        extraPosixFallback: ((StringBuilder) -> Unit)? = null,
    ) {
        val symEsc = escapeCString(cSymbolName)
        val windowsDlls = defaultWindowsDllOrder
        fun winCast(expr: String): String =
            if (isCpp) {
                "reinterpret_cast<$castTargetType>(reinterpret_cast<void*>($expr))"
            } else {
                "($castTargetType)(void*)$expr"
            }
        fun dlsymCast(expr: String): String =
            if (isCpp) "reinterpret_cast<$castTargetType>($expr)" else "($castTargetType)$expr"

        val firstVar = if (windowsDlls.first.startsWith("msvcrt")) "km_h_msvcrt" else "km_h_ucrt"
        val secondVar = if (windowsDlls.second.startsWith("msvcrt")) "km_h_msvcrt" else "km_h_ucrt"

        sb.append("    static $ptrTypeDecl $ptrVarName = $nullLit;\n")
        sb.append("#ifdef _WIN32\n")
        sb.append("    if ($ptrVarName == $nullLit) {\n")
        sb.append("        HMODULE $firstVar = GetModuleHandleA(\"${windowsDlls.first}\");\n")
        sb.append("        if ($firstVar == $nullLit) $firstVar = LoadLibraryA(\"${windowsDlls.first}\");\n")
        sb.append("        if ($firstVar != $nullLit) $ptrVarName = ${winCast("GetProcAddress($firstVar, \"$symEsc\")")};\n")
        if (!cSymbolName.startsWith("_")) {
            sb.append("        if ($ptrVarName == $nullLit && $firstVar != $nullLit) $ptrVarName = ${winCast("GetProcAddress($firstVar, \"_$symEsc\")")};\n")
        }
        sb.append("        if ($ptrVarName == $nullLit) {\n")
        sb.append("            HMODULE $secondVar = GetModuleHandleA(\"${windowsDlls.second}\");\n")
        sb.append("            if ($secondVar == $nullLit) $secondVar = LoadLibraryA(\"${windowsDlls.second}\");\n")
        sb.append("            if ($secondVar != $nullLit) $ptrVarName = ${winCast("GetProcAddress($secondVar, \"$symEsc\")")};\n")
        if (!cSymbolName.startsWith("_")) {
            sb.append("            if ($ptrVarName == $nullLit && $secondVar != $nullLit) $ptrVarName = ${winCast("GetProcAddress($secondVar, \"_$symEsc\")")};\n")
        }
        sb.append("        }\n")
        sb.append("    }\n")
        sb.append("#else\n")
        sb.append("    if ($ptrVarName == $nullLit) {\n")
        sb.append("        $ptrVarName = ${dlsymCast("dlsym(RTLD_DEFAULT, \"$symEsc\")")};\n")
        extraPosixFallback?.invoke(sb)
        sb.append("    }\n")
        sb.append("#endif\n")
    }

    protected fun appendDynamicOrWin32DllDispatch(
        sb: StringBuilder,
        fn: ForeignFnInteropSpec,
        cRet: String,
        cParamTypes: String,
        callArgs: String,
        hasReturn: Boolean,
        unusedParams: List<InteropParamSpec> = fn.params,
    ) {
        val symEsc = escapeCString(fn.cSymbolName)
        val failExpr = cFailureReturnExpr(fn, isCpp = isCpp)
        if (fn.dllName != null) {
            val procCast =
                if (isCpp) {
                    "reinterpret_cast<km_fn_t>(reinterpret_cast<void*>(GetProcAddress(km_h_mod, \"$symEsc\")))"
                } else {
                    "(km_fn_t)(void*)GetProcAddress(km_h_mod, \"$symEsc\")"
                }
            sb.append("#ifdef _WIN32\n")
            sb.append("    typedef $cRet (${if (fn.isVariadic) "" else "__stdcall "}*km_fn_t)($cParamTypes);\n")
            sb.append("    static km_fn_t km_fn_ptr = $nullLit;\n")
            sb.append("    if (km_fn_ptr == $nullLit) {\n")
            sb.append("        HMODULE km_h_mod = LoadLibraryA(\"${escapeCString(fn.dllName)}\");\n")
            sb.append("        if (km_h_mod != $nullLit) km_fn_ptr = $procCast;\n")
            sb.append("    }\n")
            appendDynamicFunctionCall(sb, fn, cRet, callArgs, hasReturn, failExpr)
            sb.append("#else\n")
            for (p in unusedParams) {
                sb.append("    (void)${p.cName};\n")
            }
            if (fn.isVariadic) {
                sb.append("    (void)km_vac; (void)km_va0; (void)km_va1; (void)km_va2; (void)km_va3;\n")
            }
            sb.append("    errno = ENOSYS;\n")
            if (hasReturn) {
                if (directReturnOnDispatch) {
                    sb.append("    return $failExpr;\n")
                } else {
                    sb.append("    $cRet km_res = $failExpr;\n")
                }
            }
            sb.append("#endif\n")
        } else {
            sb.append("    typedef $cRet (*km_fn_t)($cParamTypes);\n")
            appendDynamicSymbolLookup(
                sb = sb,
                ptrVarName = "km_fn_ptr",
                ptrTypeDecl = "km_fn_t",
                castTargetType = "km_fn_t",
                cSymbolName = fn.cSymbolName,
                extraPosixFallback = { appendPosixDynamicLookupFallbacks(it, fn, isCpp = isCpp) },
            )
            appendDynamicFunctionCall(sb, fn, cRet, callArgs, hasReturn, failExpr)
        }
    }

    private fun appendDynamicFunctionCall(
        sb: StringBuilder,
        fn: ForeignFnInteropSpec,
        cRet: String,
        callArgs: String,
        hasReturn: Boolean,
        failExpr: String,
    ) {
        fun castIntPtr(va: String): String =
            if (isCpp) "static_cast<intptr_t>($va)" else "(intptr_t)$va"

        if (directReturnOnDispatch) {
            sb.append("    if (km_fn_ptr != $nullLit) {\n")
            if (fn.isVariadic) {
                val vaPrefix = if (callArgs.isEmpty()) "" else "$callArgs, "
                val retKw = if (hasReturn) "return " else ""
                val brk = if (hasReturn) "" else " break;"
                sb.append("        switch (km_vac) {\n")
                sb.append("            case 0: ${retKw}km_fn_ptr($callArgs);$brk\n")
                sb.append("            case 1: ${retKw}km_fn_ptr($vaPrefix${castIntPtr("km_va0")});$brk\n")
                sb.append("            case 2: ${retKw}km_fn_ptr($vaPrefix${castIntPtr("km_va0")}, ${castIntPtr("km_va1")});$brk\n")
                sb.append("            case 3: ${retKw}km_fn_ptr($vaPrefix${castIntPtr("km_va0")}, ${castIntPtr("km_va1")}, ${castIntPtr("km_va2")});$brk\n")
                sb.append("            default: ${retKw}km_fn_ptr($vaPrefix${castIntPtr("km_va0")}, ${castIntPtr("km_va1")}, ${castIntPtr("km_va2")}, ${castIntPtr("km_va3")});$brk\n")
                sb.append("        }\n")
                if (!hasReturn) {
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
            return
        }

        if (!fn.isVariadic) {
            if (hasReturn) {
                sb.append("    $cRet km_res;\n")
                sb.append("    if (km_fn_ptr != $nullLit) {\n")
                sb.append("        km_res = km_fn_ptr($callArgs);\n")
                sb.append("    } else {\n")
                sb.append("        errno = ENOSYS;\n")
                sb.append("        km_res = $failExpr;\n")
                sb.append("    }\n")
            } else {
                sb.append("    if (km_fn_ptr != $nullLit) {\n")
                sb.append("        km_fn_ptr($callArgs);\n")
                sb.append("    } else {\n")
                sb.append("        errno = ENOSYS;\n")
                sb.append("    }\n")
            }
            return
        }

        val prefix = if (callArgs.isEmpty()) "" else "$callArgs, "
        val va1 = castIntPtr("km_va0")
        val va2 = "$va1, ${castIntPtr("km_va1")}"
        val va3 = "$va2, ${castIntPtr("km_va2")}"
        val va4 = "$va3, ${castIntPtr("km_va3")}"
        val lhs = if (hasReturn) "km_res = " else ""
        if (hasReturn) {
            sb.append("    $cRet km_res = $failExpr;\n")
        }
        sb.append("    if (km_fn_ptr != $nullLit) {\n")
        sb.append("        switch (km_vac) {\n")
        sb.append("            case 0: ${lhs}km_fn_ptr($callArgs); break;\n")
        sb.append("            case 1: ${lhs}km_fn_ptr($prefix$va1); break;\n")
        sb.append("            case 2: ${lhs}km_fn_ptr($prefix$va2); break;\n")
        sb.append("            case 3: ${lhs}km_fn_ptr($prefix$va3); break;\n")
        sb.append("            default: ${lhs}km_fn_ptr($prefix$va4); break;\n")
        sb.append("        }\n")
        sb.append("    } else {\n")
        sb.append("        errno = ENOSYS;\n")
        sb.append("    }\n")
    }
}

internal fun renderNapiRegistrarHeader(): String =
    """
struct KmNapiRegistrarNode {
    void (*register_fn)(Napi::Env, Napi::Object);
    KmNapiRegistrarNode* next;
};
#if defined(__GNUC__) || defined(__clang__)
__attribute__((visibility("hidden")))
#endif
extern KmNapiRegistrarNode* km_napi_registrar_head;
    """.trimIndent()

internal fun renderNapiRegistrarFooter(registerFnName: String): String =
    """
static KmNapiRegistrarNode registrar_node = { &$registerFnName, nullptr };
struct KmNapiAutoRegister {
    KmNapiAutoRegister() {
        registrar_node.next = km_napi_registrar_head;
        km_napi_registrar_head = &registrar_node;
    }
};
static KmNapiAutoRegister auto_register_instance;

} // namespace

#ifdef KM_NAPI_STANDALONE_MODULE
#if defined(__GNUC__) || defined(__clang__)
__attribute__((visibility("hidden")))
#endif
KmNapiRegistrarNode* km_napi_registrar_head = nullptr;
static Napi::Object KmNapiStandaloneInit(Napi::Env env, Napi::Object exports) {
    for (KmNapiRegistrarNode* cur = km_napi_registrar_head; cur != nullptr; cur = cur->next) {
        cur->register_fn(env, exports);
    }
    return exports;
}
NODE_API_MODULE(NODE_GYP_MODULE_NAME, KmNapiStandaloneInit)
#endif
    """.trimIndent()
