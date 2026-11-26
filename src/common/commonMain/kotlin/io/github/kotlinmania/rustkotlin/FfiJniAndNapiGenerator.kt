package io.github.kotlinmania.rustkotlin

internal fun jniKotlinTypeForParam(
    param: InteropParamSpec,
    noStringConvEnabled: Boolean,
    ktStringType: String = "String",
): String =
    if (param.isDirectStringBridge(noStringConvEnabled)) "$ktStringType?" else bridgeDescriptorFor(param.bridgeKind).jniKotlinType

internal fun jniKotlinTypeForReturn(returnBridgeKind: CAbiBridgeKind): String =
    bridgeDescriptorFor(returnBridgeKind).jniKotlinType

internal fun jniCTypeForParam(
    param: InteropParamSpec,
    noStringConvEnabled: Boolean,
): String =
    if (param.isDirectStringBridge(noStringConvEnabled)) "jstring" else bridgeDescriptorFor(param.bridgeKind).jniCType

internal fun jniCTypeForReturn(returnBridgeKind: CAbiBridgeKind): String =
    bridgeDescriptorFor(returnBridgeKind).jniCType

private fun jniReturnCastExpr(bridgeKind: CAbiBridgeKind, jniRet: String): String =
    if (bridgeDescriptorFor(bridgeKind).isPointerAddress) "(jlong)(uintptr_t)km_res" else "($jniRet)km_res"

private fun jniPointerOrValueCast(bridgeKind: CAbiBridgeKind, expr: String, cType: String): String =
    when {
        bridgeKind == CAbiBridgeKind.CONST_CSTR -> "(const char*)(uintptr_t)$expr"
        bridgeKind == CAbiBridgeKind.MUT_CSTR -> "(char*)(uintptr_t)$expr"
        bridgeDescriptorFor(bridgeKind).isPointerAddress -> "(void*)(uintptr_t)$expr"
        else -> "($cType)$expr"
    }

private fun appendJniStringParamSetupAndCallArgs(
    sb: StringBuilder,
    params: List<InteropParamSpec>,
    noStrConv: Boolean,
): Pair<List<InteropParamSpec>, String> {
    val stringParams = params.filter { it.isDirectStringBridge(noStrConv) }
    for (sp in stringParams) {
        sb.append("    const char* ${sp.cName}_utf = (${sp.cName} != NULL) ? (*km_env)->GetStringUTFChars(km_env, ${sp.cName}, NULL) : NULL;\n")
    }
    val callArgs =
        params.joinToString(", ") { p ->
            if (p.isDirectStringBridge(noStrConv)) {
                "${p.cName}_utf"
            } else {
                jniPointerOrValueCast(p.bridgeKind, p.cName, cHeaderTypeForParam(p))
            }
        }
    return stringParams to callArgs
}

private fun appendJniStringParamReleases(sb: StringBuilder, stringParams: List<InteropParamSpec>) {
    for (sp in stringParams) {
        sb.append("    if (${sp.cName} != NULL && ${sp.cName}_utf != NULL) (*km_env)->ReleaseStringUTFChars(km_env, ${sp.cName}, ${sp.cName}_utf);\n")
    }
}

internal fun FfiInteropCollector.renderJvmParamConversion(
    param: InteropParamSpec,
    noStringConvEnabled: Boolean,
): String =
    when {
        param.isDirectStringBridge(noStringConvEnabled) -> param.kotlinName
        bridgeDescriptorFor(param.bridgeKind).isPointerAddress -> renderPointerParamToLong(param)
        param.bridgeKind == CAbiBridgeKind.I8 || param.bridgeKind == CAbiBridgeKind.U8 -> renderScalarParamArg(param, NumericKind.I8, "toByte")
        param.bridgeKind == CAbiBridgeKind.I16 || param.bridgeKind == CAbiBridgeKind.U16 -> renderScalarParamArg(param, NumericKind.I16, "toShort")
        param.bridgeKind == CAbiBridgeKind.I32 || param.bridgeKind == CAbiBridgeKind.U32 -> renderScalarParamArg(param, NumericKind.I32, "toInt")
        param.bridgeKind == CAbiBridgeKind.I64 || param.bridgeKind == CAbiBridgeKind.U64 -> renderScalarParamArg(param, NumericKind.I64, "toLong")
        param.bridgeKind == CAbiBridgeKind.F32 -> renderScalarParamArg(param, NumericKind.F32, "toFloat")
        param.bridgeKind == CAbiBridgeKind.F64 -> renderScalarParamArg(param, NumericKind.F64, "toDouble")
        else -> param.kotlinName
    }

internal fun FfiInteropCollector.renderJvmReturnConversion(
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
                i64Base = rawCall,
                u64Base = rawCall,
                ptrLongExpr = rawCall,
                boolExpr = rawCall,
                f32Expr = rawCall,
                f64Expr = rawCall,
                signedSubIntKeepsRaw = true,
                fallbackNonScalar = rawCall,
            ),
    )

internal fun FfiInteropCollector.buildJvmOrAndroidActualsKotlinContent(
    pkgName: String?,
    jniObjectName: String,
    jniLibName: String,
    includeJarResourceLoader: Boolean = true,
): String =
    JvmOrAndroidActualsEmitter(this, pkgName, jniObjectName, jniLibName, includeJarResourceLoader).emit()

private class JvmOrAndroidActualsEmitter(
    collector: FfiInteropCollector,
    pkgName: String?,
    private val jniObjectName: String,
    private val jniLibName: String,
    private val includeJarResourceLoader: Boolean,
) : PlatformActualsKotlinEmitter(collector, pkgName) {
    private val jniExternalDecls = mutableListOf<String>()
    private val emittedJniWrappers = linkedSetOf<String>()

    override val fileOptIns: List<String> = listOf("ExperimentalUnsignedTypes::class")

    override fun convertParamArg(param: InteropParamSpec, noStringConvEnabled: Boolean): String =
        collector.renderJvmParamConversion(param, noStringConvEnabled)

    override fun convertReturnExpr(
        rawCall: String,
        kotlinReturnType: String,
        returnBridgeKind: CAbiBridgeKind,
        returnDefaultExpr: String,
    ): String =
        collector.renderJvmReturnConversion(rawCall, kotlinReturnType, returnBridgeKind, returnDefaultExpr, pkgName, imports)

    private fun registerJniExternal(symbol: String, paramsSig: String, retType: String) {
        if (emittedJniWrappers.add(symbol)) {
            val retSuffix = if (retType == "Unit") "" else ": $retType"
            jniExternalDecls += "    @JvmStatic\n    external fun $symbol($paramsSig)$retSuffix"
        }
    }

    override fun renderBridgeableStatic(st: ForeignStaticInteropSpec): String {
        registerJniExternal(st.getterWrapperSymbolName, "", jniKotlinTypeForReturn(st.bridgeKind))
        if (st.isMutable) {
            val jniValType = jniKotlinTypeForParam(st.asSetterParamSpec(), noStringConvEnabled = true, ktStringType = ktStringType)
            registerJniExternal(st.setterWrapperSymbolName, "value: $jniValType", "Unit")
        }
        return renderStandardStaticProperty(
            st = st,
            getterCallee = "$jniObjectName.${st.getterWrapperSymbolName}",
            setterCallee = "$jniObjectName.${st.setterWrapperSymbolName}",
        )
    }

    override fun onBeforeDedupBridgeableCallable(entry: CallableInteropEntry) {
        val baseJniParams = entry.params.map { "${it.kotlinName}: ${jniKotlinTypeForParam(it, entry.noStringConvEnabled, ktStringType)}" }
        val allJniParams =
            if (entry.isVariadicCall) {
                baseJniParams + listOf("kmVac: Int", "kmVa0: Long", "kmVa1: Long", "kmVa2: Long", "kmVa3: Long")
            } else {
                baseJniParams
            }
        registerJniExternal(entry.wrapperSymbolName, allJniParams.joinToString(", "), jniKotlinTypeForReturn(entry.returnBridgeKind))
    }

    override fun renderBridgeableCallable(entry: CallableInteropEntry): String =
        renderStandardCallable(entry, "$jniObjectName.${entry.wrapperSymbolName}")

    override fun prependPreambleBlocks(blocks: MutableList<String>) {
        if (jniExternalDecls.isEmpty()) return
        val rawProjNs = collector.headerContext.projectNamespace
        val projNs = rawProjNs?.trim()?.removeSuffix(".")?.takeIf { it.isNotEmpty() } ?: pkgName.orEmpty()
        val resourcePrefix = projNs.replace('.', '/')
        val useResourceLoader = includeJarResourceLoader && resourcePrefix.isNotEmpty()
        val jniObjBlock =
            buildString {
                append("private object $jniObjectName {\n    init {\n")
                append(if (useResourceLoader) "        loadNativeLibrary()\n" else "        System.loadLibrary(\"$jniLibName\")\n")
                append("    }\n\n")
                if (useResourceLoader) append(buildJniResourceLoaderBody(jniObjectName, jniLibName, resourcePrefix))
                append(jniExternalDecls.joinToString("\n\n")).append("\n}")
            }
        blocks.add(0, jniObjBlock)
    }
}

internal fun FfiInteropCollector.buildJniCSourceContent(
    pkgName: String?,
    jniObjectName: String,
): String = JniHostSourceEmitter(this, pkgName, jniObjectName).emit()

private const val JNI_FALLBACK_HEADER_BLOCK =
    """#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <stdbool.h>
#include <stdint.h>
#if defined(__has_include)
#  if __has_include(<jni.h>)
#    include <jni.h>
#    define KM_HAS_JNI_H 1
#  endif
#endif
#ifndef KM_HAS_JNI_H
typedef uint8_t jboolean;
typedef int8_t jbyte;
typedef uint16_t jchar;
typedef int16_t jshort;
typedef int32_t jint;
typedef int64_t jlong;
typedef float jfloat;
typedef double jdouble;
typedef jint jsize;
typedef void* jobject;
typedef jobject jclass;
typedef jobject jstring;
typedef jobject jthrowable;
typedef jobject jarray;
typedef jarray jbyteArray;
struct JNINativeInterface_;
typedef const struct JNINativeInterface_* JNIEnv;
struct JNINativeInterface_ {
    const char* (*GetStringUTFChars)(JNIEnv*, jstring, jboolean*);
    void (*ReleaseStringUTFChars)(JNIEnv*, jstring, const char*);
};
#ifdef _WIN32
#  define JNIEXPORT __declspec(dllexport)
#  define JNICALL __stdcall
#else
#  define JNIEXPORT __attribute__((visibility("default")))
#  define JNICALL
#endif
#endif
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <errno.h>
"""

private class JniHostSourceEmitter(
    collector: FfiInteropCollector,
    pkgName: String?,
    jniObjectName: String,
) : NativeHostBridgeSourceEmitter(collector, isCpp = false) {
    private val jniClassMangled: String =
        (if (pkgName.isNullOrBlank()) jniObjectName else "$pkgName.$jniObjectName")
            .split('.')
            .joinToString("_", transform = ::jniMangleSegment)

    private fun jniSymbolFor(wrapperSym: String): String =
        "Java_${jniClassMangled}_${jniMangleSegment(wrapperSym)}"

    private fun formatJniParamsSig(params: List<InteropParamSpec>, noStrConv: Boolean, isVariadic: Boolean = false): String =
        buildList {
            add("JNIEnv* km_env")
            add("jclass km_clazz")
            for (p in params) add("${jniCTypeForParam(p, noStrConv)} ${p.cName}")
            if (isVariadic) addAll(listOf("jint km_vac", "jlong km_va0", "jlong km_va1", "jlong km_va2", "jlong km_va3"))
        }.joinToString(", ")

    override fun emitFileHeader(sb: StringBuilder) {
        sb.append(JNI_FALLBACK_HEADER_BLOCK)
        appendWin32OrDlfcnDynamicHeaders(sb)
        sb.append('\n')
        if (!collector.usesCppLanguage) appendPreambles(sb)
    }

    private fun beginJniFunction(
        jniRet: String,
        wrapperSym: String,
        paramsSig: String = "JNIEnv* km_env, jclass km_clazz",
        prefixText: String = "",
    ): StringBuilder =
        StringBuilder(prefixText).apply {
            append("JNIEXPORT $jniRet JNICALL ${jniSymbolFor(wrapperSym)}($paramsSig) {\n")
            append("    (void)km_env;\n    (void)km_clazz;\n")
        }

    override fun renderStaticGetter(st: ForeignStaticInteropSpec): String {
        val jniRet = jniCTypeForReturn(st.bridgeKind)
        val sb = beginJniFunction(jniRet, st.getterWrapperSymbolName)
        val cRet = appendStaticSymbolPtrLookup(sb, st)
        sb.append("    $cRet km_res = (km_sym_ptr != NULL) ? *km_sym_ptr : ${cDefaultFailureLiteral(st.bridgeKind)};\n")
        sb.append("    return ${jniReturnCastExpr(st.bridgeKind, jniRet)};\n}\n")
        return sb.toString()
    }

    override fun renderStaticSetter(st: ForeignStaticInteropSpec): String {
        val jniValType = jniCTypeForParam(st.asSetterParamSpec(), noStringConvEnabled = true)
        val sb = beginJniFunction("void", st.setterWrapperSymbolName, "JNIEnv* km_env, jclass km_clazz, $jniValType value")
        val cRet = appendStaticSymbolPtrLookup(sb, st)
        sb.append("    if (km_sym_ptr != NULL) {\n        *km_sym_ptr = ${jniPointerOrValueCast(st.bridgeKind, "value", cRet)};\n    }\n}\n")
        return sb.toString()
    }

    override fun renderForeignFunction(fn: ForeignFnInteropSpec): String {
        val noStrConv = requiresNoStringConversion(fn.params)
        val jniRet = jniCTypeForReturn(fn.returnBridgeKind)
        val hasReturn = hasNonVoidReturn(fn.returnBridgeKind)
        val sb = beginJniFunction(jniRet, fn.wrapperSymbolName, formatJniParamsSig(fn.params, noStrConv, fn.isVariadic))

        val (stringParams, callArgs) = appendJniStringParamSetupAndCallArgs(sb, fn.params, noStrConv)
        val isAtexit = isAtexitForeignFn(fn)
        if (isAtexit) {
            val cbName = fn.params[0].cName
            appendAtexitGuardPrefix(
                sb = sb,
                nullCheckCond = "(void*)(uintptr_t)$cbName == NULL",
                invalidReturnStmt = "return (jint)-1;",
                androidReturnBlock = "    return (jint)atexit((void (*)(void))(uintptr_t)$cbName);\n",
            )
        }
        appendDynamicOrWin32DllDispatch(
            sb = sb,
            fn = fn,
            cRet = cHeaderTypeForReturn(fn),
            cParamTypes = formatCFunctionParamTypes(fn),
            callArgs = callArgs,
            hasReturn = hasReturn,
            unusedParams = fn.params.filter { it !in stringParams },
        )
        appendJniStringParamReleases(sb, stringParams)
        if (hasReturn) sb.append("    return ${jniReturnCastExpr(fn.returnBridgeKind, jniRet)};\n")
        if (isAtexit) sb.append("#endif\n")
        sb.append("}\n")
        return sb.toString()
    }

    override fun renderInlineFunction(fn: InlineCFunctionSpec): String {
        val inlineNoStrConv = requiresNoStringConversion(fn.params)
        val jniRet = jniCTypeForReturn(fn.returnBridgeKind)
        val hasReturn = hasNonVoidReturn(fn.returnBridgeKind)
        val helperPrefix = if (!collector.usesCppLanguage && !fn.isCpp) "static ${renderInlineWrapperCFunction(fn)}\n" else ""
        val sb = beginJniFunction(jniRet, fn.wrapperSymbolName, formatJniParamsSig(fn.params, inlineNoStrConv), helperPrefix)
        if (collector.usesCppLanguage || fn.isCpp) {
            for (p in fn.params) sb.append("    (void)${p.cName};\n")
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
            val (stringParams, callArgs) = appendJniStringParamSetupAndCallArgs(sb, fn.params, inlineNoStrConv)
            if (hasReturn) {
                sb.append("    ${cHeaderTypeForInlineReturn(fn)} km_res = ${fn.wrapperSymbolName}($callArgs);\n")
            } else {
                sb.append("    ${fn.wrapperSymbolName}($callArgs);\n")
            }
            appendJniStringParamReleases(sb, stringParams)
            if (hasReturn) sb.append("    return ${jniReturnCastExpr(fn.returnBridgeKind, jniRet)};\n")
        }
        sb.append("}\n")
        return sb.toString()
    }
}

internal fun FfiInteropCollector.buildJsActualsKotlinContent(
    pkgName: String?,
    stemPascal: String,
    ffiModName: String,
): String = JsActualsEmitter(this, pkgName, stemPascal, ffiModName).emit()

private class JsActualsEmitter(
    collector: FfiInteropCollector,
    pkgName: String?,
    stemPascal: String,
    private val ffiModName: String,
) : PlatformActualsKotlinEmitter(collector, pkgName) {
    private val addonPropName = "${stemPascal.replaceFirstChar { it.lowercase() }}NapiAddon"
    private var usesAddon = false

    override val fileOptIns: List<String> = listOf("ExperimentalUnsignedTypes::class")
    override val unbridgeableThrowsAddonError: Boolean = true
    override val stringifyVariadicArgs: Boolean = true
    override val addonAvailabilityGuard: String = "$addonPropName != null"

    override fun convertParamArg(param: InteropParamSpec, noStringConvEnabled: Boolean): String =
        collector.renderJsOrWasmParamConversion(param, noStringConvEnabled)

    override fun convertReturnExpr(
        rawCall: String,
        kotlinReturnType: String,
        returnBridgeKind: CAbiBridgeKind,
        returnDefaultExpr: String,
    ): String =
        collector.renderJsReturnConversion(rawCall, kotlinReturnType, returnBridgeKind, returnDefaultExpr, pkgName, imports)

    override fun renderBridgeableStatic(st: ForeignStaticInteropSpec): String {
        usesAddon = true
        return renderStandardStaticProperty(
            st = st,
            getterCallee = "$addonPropName.${st.getterWrapperSymbolName}",
            setterCallee = "$addonPropName.${st.setterWrapperSymbolName}",
        )
    }

    override fun renderBridgeableCallable(entry: CallableInteropEntry): String {
        usesAddon = true
        return renderStandardCallable(entry, "$addonPropName.${entry.wrapperSymbolName}")
    }

    override fun prependPreambleBlocks(blocks: MutableList<String>) {
        if (!usesAddon) return
        val jsExpr = "(function() { ${buildNodeNapiLoaderJsBody(ffiModName, returnBooleanStatus = false)}})()"
        blocks.add(0, "private val $addonPropName: dynamic =\n    js(\"$jsExpr\")")
    }
}

internal fun FfiInteropCollector.renderJsOrWasmParamConversion(
    param: InteropParamSpec,
    noStringConvEnabled: Boolean,
): String =
    when {
        param.isDirectStringBridge(noStringConvEnabled) -> param.kotlinName
        bridgeDescriptorFor(param.bridgeKind).isPointerAddress -> "${renderPointerParamToLong(param)}.toString()"
        param.bridgeKind == CAbiBridgeKind.I64 || param.bridgeKind == CAbiBridgeKind.U64 -> "${param.kotlinName}.toString()"
        param.bridgeKind in listOf(CAbiBridgeKind.I8, CAbiBridgeKind.U8, CAbiBridgeKind.I16, CAbiBridgeKind.U16, CAbiBridgeKind.I32, CAbiBridgeKind.U32) ->
            renderScalarParamArg(param, NumericKind.I32, "toInt")
        param.bridgeKind == CAbiBridgeKind.F32 || param.bridgeKind == CAbiBridgeKind.F64 ->
            renderScalarParamArg(param, NumericKind.F64, "toDouble")
        else -> param.kotlinName
    }

internal fun FfiInteropCollector.renderJsReturnConversion(
    rawCall: String,
    kotlinReturnType: String,
    returnBridgeKind: CAbiBridgeKind,
    returnDefaultExpr: String,
    pkgName: String?,
    importsOut: MutableSet<String>,
): String {
    val ktString = if (shadowsKotlinString(pkgName)) "kotlin.String" else "String"
    val longStrExpr = "($rawCall as $ktString).toLong()"
    return renderBridgeReturnConversion(
        rawCall = rawCall,
        kotlinReturnType = kotlinReturnType,
        returnBridgeKind = returnBridgeKind,
        returnDefaultExpr = returnDefaultExpr,
        pkgName = pkgName,
        importsOut = importsOut,
        profile =
            BridgeReturnConversionProfile(
                subIntBase = "($rawCall as Int)",
                i64Base = longStrExpr,
                u64Base = "($rawCall as $ktString).toULong()",
                ptrLongExpr = longStrExpr,
                boolExpr = "($rawCall as Boolean)",
                f32Expr = "($rawCall as Double).toFloat()",
                f64Expr = "($rawCall as Double)",
                fallbackNonScalar = returnDefaultExpr,
            ),
    )
}

internal fun FfiInteropCollector.buildNapiCppSourceContent(stemSnake: String): String =
    NapiCppHostSourceEmitter(this, stemSnake).emit()

private const val NAPI_ARG_HELPERS_CPP =
    """static int64_t km_napi_arg_i64(const Napi::Value& v) {
    if (v.IsNull() || v.IsUndefined()) return 0;
    if (v.IsString()) {
        std::string s = v.As<Napi::String>().Utf8Value();
        return static_cast<int64_t>(std::strtoll(s.c_str(), nullptr, 10));
    }
    if (v.IsNumber()) return v.As<Napi::Number>().Int64Value();
    return 0;
}

static uint64_t km_napi_arg_u64(const Napi::Value& v) {
    if (v.IsNull() || v.IsUndefined()) return 0;
    if (v.IsString()) {
        std::string s = v.As<Napi::String>().Utf8Value();
        return static_cast<uint64_t>(std::strtoull(s.c_str(), nullptr, 10));
    }
    if (v.IsNumber()) return static_cast<uint64_t>(v.As<Napi::Number>().Int64Value());
    return 0;
}

"""

private class NapiCppHostSourceEmitter(
    collector: FfiInteropCollector,
    private val stemSnake: String,
) : NativeHostBridgeSourceEmitter(collector, isCpp = true) {
    override fun emitFileHeader(sb: StringBuilder) {
        sb.append("#ifndef _GNU_SOURCE\n#define _GNU_SOURCE\n#endif\n")
        sb.append("#include <napi.h>\n#include <cstdint>\n#include <cstddef>\n#include <cstdlib>\n#include <cstring>\n#include <cerrno>\n#include <string>\n")
        appendWin32OrDlfcnDynamicHeaders(sb)
        sb.append('\n')
        appendPreambles(sb)
        sb.append(renderNapiRegistrarHeader()).append("\n\nnamespace {\n\n")
        sb.append(NAPI_ARG_HELPERS_CPP)
    }

    private fun beginNapiFunction(wrapperSym: String, prefixText: String = ""): StringBuilder =
        StringBuilder(prefixText).apply {
            append("static Napi::Value ${wrapperSym}_napi(const Napi::CallbackInfo& km_info) {\n")
            append("    Napi::Env km_env = km_info.Env();\n")
        }

    override fun renderStaticGetter(st: ForeignStaticInteropSpec): String {
        val sb = beginNapiFunction(st.getterWrapperSymbolName)
        val cRet = appendStaticSymbolPtrLookup(sb, st)
        sb.append("    $cRet km_res = (km_sym_ptr != nullptr) ? *km_sym_ptr : ${cDefaultFailureLiteral(st.bridgeKind)};\n")
        sb.append(renderNapiReturnStatement(st.bridgeKind)).append("}\n")
        return sb.toString()
    }

    override fun renderStaticSetter(st: ForeignStaticInteropSpec): String {
        val cRet = cHeaderTypeForBridge(st.bridgeKind)
        val sb = beginNapiFunction(st.setterWrapperSymbolName)
        sb.append(renderNapiValueExtraction(st.bridgeKind, "value", 0, cRet))
        appendStaticSymbolPtrLookup(sb, st)
        sb.append("    if (km_sym_ptr != nullptr) {\n        *km_sym_ptr = value;\n    }\n")
        sb.append("    return km_env.Undefined();\n}\n")
        return sb.toString()
    }

    override fun renderForeignFunction(fn: ForeignFnInteropSpec): String {
        val noStrConv = requiresNoStringConversion(fn.params)
        val hasReturn = hasNonVoidReturn(fn.returnBridgeKind)
        val sb = beginNapiFunction(fn.wrapperSymbolName)
        appendNapiParamExtractions(sb, fn.params, noStrConv)
        if (fn.isVariadic) {
            val baseLen = fn.params.size
            sb.append("    int32_t km_vac = km_info.Length() > $baseLen ? km_info[$baseLen].ToNumber().Int32Value() : 0;\n")
            for (i in 0 until 4) {
                sb.append("    int64_t km_va$i = km_info.Length() > ${baseLen + 1 + i} ? km_napi_arg_i64(km_info[${baseLen + 1 + i}]) : 0;\n")
            }
        }

        val isAtexit = isAtexitForeignFn(fn)
        if (isAtexit) {
            val cbName = fn.params[0].cName
            appendAtexitGuardPrefix(
                sb = sb,
                nullCheckCond = "$cbName == nullptr",
                invalidReturnStmt = "return Napi::Number::New(km_env, static_cast<int32_t>(-1));",
                androidReturnBlock =
                    "    int32_t km_res = static_cast<int32_t>(std::atexit(reinterpret_cast<void (*)()>(reinterpret_cast<uintptr_t>($cbName))));\n" +
                        "    return Napi::Number::New(km_env, static_cast<int32_t>(km_res));\n",
            )
        }
        appendDynamicOrWin32DllDispatch(
            sb = sb,
            fn = fn,
            cRet = cHeaderTypeForReturn(fn),
            cParamTypes = formatCFunctionParamTypes(fn),
            callArgs = fn.params.joinToString(", ") { it.cName },
            hasReturn = hasReturn,
        )
        sb.append(renderNapiReturnStatement(fn.returnBridgeKind))
        if (isAtexit) sb.append("#endif\n")
        sb.append("}\n")
        return sb.toString()
    }

    override fun renderInlineFunction(fn: InlineCFunctionSpec): String {
        val inlineNoStrConv = requiresNoStringConversion(fn.params)
        val hasReturn = hasNonVoidReturn(fn.returnBridgeKind)
        val sb = beginNapiFunction(fn.wrapperSymbolName, "static ${renderInlineWrapperCFunction(fn)}\n")
        appendNapiParamExtractions(sb, fn.params, inlineNoStrConv)
        val callExpr = "${fn.wrapperSymbolName}(${fn.params.joinToString(", ") { it.cName }})"
        sb.append(if (hasReturn) "    ${cHeaderTypeForInlineReturn(fn)} km_res = $callExpr;\n" else "    $callExpr;\n")
        sb.append(renderNapiReturnStatement(fn.returnBridgeKind)).append("}\n")
        return sb.toString()
    }

    override fun emitFileFooter(sb: StringBuilder, emittedSymbols: Set<String>) {
        val registerFnName = "register_${stemSnake}_napi"
        sb.append("static void $registerFnName(Napi::Env env, Napi::Object exports) {\n")
        if (emittedSymbols.isEmpty()) {
            sb.append("    (void)env;\n    (void)exports;\n")
        } else {
            for (sym in emittedSymbols) {
                sb.append("    exports.Set(\"$sym\", Napi::Function::New(env, ${sym}_napi));\n")
            }
        }
        sb.append("}\n\n").append(renderNapiRegistrarFooter(registerFnName)).append('\n')
    }

    private fun renderNapiValueExtraction(
        bridgeKind: CAbiBridgeKind,
        varName: String,
        argIdx: Int,
        cDeclType: String,
    ): String {
        val desc = bridgeDescriptorFor(bridgeKind)
        val u64Expr = "km_info.Length() > $argIdx ? km_napi_arg_u64(km_info[$argIdx]) : 0"
        return when {
            desc.isPointerAddress ->
                "    ${desc.cType} $varName = reinterpret_cast<${desc.cType}>(static_cast<uintptr_t>($u64Expr));\n"
            bridgeKind == CAbiBridgeKind.I64 ->
                "    int64_t $varName = km_info.Length() > $argIdx ? km_napi_arg_i64(km_info[$argIdx]) : 0;\n"
            bridgeKind == CAbiBridgeKind.U64 ->
                "    uint64_t $varName = $u64Expr;\n"
            bridgeKind == CAbiBridgeKind.BOOL ->
                "    bool $varName = km_info.Length() > $argIdx ? km_info[$argIdx].ToBoolean().Value() : false;\n"
            bridgeKind == CAbiBridgeKind.F32 ->
                "    float $varName = km_info.Length() > $argIdx ? km_info[$argIdx].ToNumber().FloatValue() : 0.0f;\n"
            bridgeKind == CAbiBridgeKind.F64 ->
                "    double $varName = km_info.Length() > $argIdx ? km_info[$argIdx].ToNumber().DoubleValue() : 0.0;\n"
            else ->
                "    $cDeclType $varName = static_cast<$cDeclType>(km_info.Length() > $argIdx ? km_info[$argIdx].ToNumber().Int32Value() : 0);\n"
        }
    }

    private fun appendNapiParamExtractions(
        sb: StringBuilder,
        params: List<InteropParamSpec>,
        noStrConv: Boolean,
    ) {
        for ((idx, p) in params.withIndex()) {
            if (p.isDirectStringBridge(noStrConv)) {
                sb.append("    bool ${p.cName}_null = (km_info.Length() <= $idx || km_info[$idx].IsNull() || km_info[$idx].IsUndefined());\n")
                sb.append("    std::string ${p.cName}_str = ${p.cName}_null ? std::string() : km_info[$idx].ToString().Utf8Value();\n")
                sb.append("    const char* ${p.cName} = ${p.cName}_null ? nullptr : ${p.cName}_str.c_str();\n")
            } else {
                sb.append(renderNapiValueExtraction(p.bridgeKind, p.cName, idx, cHeaderTypeForParam(p)))
            }
        }
    }
}

internal fun renderNapiReturnStatement(returnBridgeKind: CAbiBridgeKind): String =
    when {
        returnBridgeKind == CAbiBridgeKind.VOID ||
            returnBridgeKind == CAbiBridgeKind.NEVER ||
            returnBridgeKind == CAbiBridgeKind.BY_VALUE_OBJECT ->
            "    return km_env.Undefined();\n"
        returnBridgeKind == CAbiBridgeKind.BOOL ->
            "    return Napi::Boolean::New(km_env, km_res);\n"
        returnBridgeKind in listOf(CAbiBridgeKind.I8, CAbiBridgeKind.U8, CAbiBridgeKind.I16, CAbiBridgeKind.U16, CAbiBridgeKind.I32, CAbiBridgeKind.U32) ->
            "    return Napi::Number::New(km_env, static_cast<int32_t>(km_res));\n"
        returnBridgeKind == CAbiBridgeKind.I64 ->
            "    return Napi::String::New(km_env, std::to_string(static_cast<int64_t>(km_res)));\n"
        returnBridgeKind == CAbiBridgeKind.U64 ->
            "    return Napi::String::New(km_env, std::to_string(static_cast<uint64_t>(km_res)));\n"
        returnBridgeKind == CAbiBridgeKind.F32 || returnBridgeKind == CAbiBridgeKind.F64 ->
            "    return Napi::Number::New(km_env, static_cast<double>(km_res));\n"
        bridgeDescriptorFor(returnBridgeKind).isPointerAddress ->
            "    return Napi::String::New(km_env, std::to_string(static_cast<int64_t>(reinterpret_cast<uintptr_t>(km_res))));\n"
        else -> "    return km_env.Undefined();\n"
    }
