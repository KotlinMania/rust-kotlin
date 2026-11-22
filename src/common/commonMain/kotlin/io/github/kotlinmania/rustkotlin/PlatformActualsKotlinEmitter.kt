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
 * (`JvmActuals.kt`, `AndroidActuals.kt`, `JsActuals.kt`, `WasmJsActuals.kt`, `NativeActuals.kt`).
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

    protected open val unbridgeableThrowsAddonError: Boolean = false

    protected open val stringifyVariadicArgs: Boolean = false

    protected open val addonAvailabilityGuard: String? = null

    protected open fun initializeImports() = Unit

    protected abstract fun renderBridgeableStatic(st: ForeignStaticInteropSpec): String

    protected open fun onBeforeDedupBridgeableCallable(entry: CallableInteropEntry) = Unit

    protected abstract fun renderBridgeableCallable(entry: CallableInteropEntry): String

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
                    collector.renderStaticActualProperty(st)
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
                } else if (unbridgeableThrowsAddonError) {
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
        }

        prependPreambleBlocks(actualBlocks)
        return collector.finalizeActualsKotlinFile(sb, actualBlocks, imports, pkgName)
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
