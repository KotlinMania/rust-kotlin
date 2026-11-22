package io.github.kotlinmania.rustkotlin

internal fun jniKotlinTypeForParam(
    param: InteropParamSpec,
    noStringConvEnabled: Boolean,
    ktStringType: String = "String",
): String =
    if (param.bridgeKind == CAbiBridgeKind.CONST_CSTR &&
        !noStringConvEnabled &&
        (param.kotlinType == "String?" || param.kotlinType == "String")
    ) {
        "$ktStringType?"
    } else {
        bridgeDescriptorFor(param.bridgeKind).jniKotlinType
    }

internal fun jniKotlinTypeForReturn(returnBridgeKind: CAbiBridgeKind): String =
    bridgeDescriptorFor(returnBridgeKind).jniKotlinType

internal fun jniCTypeForParam(
    param: InteropParamSpec,
    noStringConvEnabled: Boolean,
): String =
    if (param.bridgeKind == CAbiBridgeKind.CONST_CSTR &&
        !noStringConvEnabled &&
        (param.kotlinType == "String?" || param.kotlinType == "String")
    ) {
        "jstring"
    } else {
        bridgeDescriptorFor(param.bridgeKind).jniCType
    }

internal fun jniCTypeForReturn(returnBridgeKind: CAbiBridgeKind): String =
    bridgeDescriptorFor(returnBridgeKind).jniCType

private fun jniReturnCastExpr(
    bridgeKind: CAbiBridgeKind,
    jniRet: String,
): String =
    if (bridgeDescriptorFor(bridgeKind).isPointerAddress) {
        "(jlong)(uintptr_t)km_res"
    } else {
        "($jniRet)km_res"
    }

private fun appendJniStringParamSetupAndCallArgs(
    sb: StringBuilder,
    params: List<InteropParamSpec>,
    noStrConv: Boolean,
): Pair<List<InteropParamSpec>, String> {
    val stringParams =
        params.filter { p ->
            p.bridgeKind == CAbiBridgeKind.CONST_CSTR &&
                !noStrConv &&
                (p.kotlinType == "String?" || p.kotlinType == "String")
        }
    for (sp in stringParams) {
        sb.append("    const char* ${sp.cName}_utf = (${sp.cName} != NULL) ? (*km_env)->GetStringUTFChars(km_env, ${sp.cName}, NULL) : NULL;\n")
    }
    val callArgs =
        params.joinToString(", ") { p ->
            val isJStr =
                p.bridgeKind == CAbiBridgeKind.CONST_CSTR &&
                    !noStrConv &&
                    (p.kotlinType == "String?" || p.kotlinType == "String")
            when {
                isJStr -> "${p.cName}_utf"
                p.bridgeKind == CAbiBridgeKind.CONST_CSTR -> "(const char*)(uintptr_t)${p.cName}"
                p.bridgeKind == CAbiBridgeKind.MUT_CSTR -> "(char*)(uintptr_t)${p.cName}"
                bridgeDescriptorFor(p.bridgeKind).isPointerAddress -> "(void*)(uintptr_t)${p.cName}"
                else -> "(${cHeaderTypeForParam(p)})${p.cName}"
            }
        }
    return stringParams to callArgs
}

internal fun FfiInteropCollector.renderJvmParamConversion(
    param: InteropParamSpec,
    noStringConvEnabled: Boolean,
): String =
    when (param.bridgeKind) {
        CAbiBridgeKind.CONST_CSTR ->
            if (!noStringConvEnabled && (param.kotlinType == "String?" || param.kotlinType == "String")) {
                param.kotlinName
            } else {
                renderPointerParamToLong(param)
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

internal fun FfiInteropCollector.renderJvmReturnConversion(
    rawCall: String,
    kotlinReturnType: String,
    returnBridgeKind: CAbiBridgeKind,
    returnDefaultExpr: String,
    pkgName: String?,
    importsOut: MutableSet<String>,
): String =
    renderLongOrScalarBridgeReturnConversion(
        rawCall = rawCall,
        kotlinReturnType = kotlinReturnType,
        returnBridgeKind = returnBridgeKind,
        returnDefaultExpr = returnDefaultExpr,
        pkgName = pkgName,
        importsOut = importsOut,
        subIntBase = rawCall,
        i64Base = rawCall,
        u64Base = rawCall,
        ptrLongExpr = rawCall,
        boolExpr = rawCall,
        f32Expr = rawCall,
        f64Expr = rawCall,
        signedSubIntKeepsRaw = true,
        fallbackNonScalar = rawCall,
    )

internal fun FfiInteropCollector.renderJvmStaticActualProperty(
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

internal fun FfiInteropCollector.renderJvmActualFunction(
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
    val baseArgs = params.map { renderJvmParamConversion(it, noStringConvEnabled) }
    val allArgs = if (isVariadic) appendVariadicKotlinArgs(baseArgs, stringifyArgs = false) else baseArgs
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
    return renderActualFunctionDecl(
        visibilityPrefix = visibilityPrefix,
        kotlinName = kotlinName,
        params = params,
        kotlinReturnType = kotlinReturnType,
        returnBridgeKind = returnBridgeKind,
        isVariadic = isVariadic,
        rawCall = rawCall,
        bodyExpr = bodyExpr,
    )
}

internal fun FfiInteropCollector.buildJvmOrAndroidActualsKotlinContent(
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

    fun processJniCallable(
        visibilityPrefix: String,
        kotlinName: String,
        wrapperSymbolName: String,
        params: List<InteropParamSpec>,
        kotlinReturnType: String,
        returnBridgeKind: CAbiBridgeKind,
        returnDefaultExpr: String,
        noStrConv: Boolean,
        isVariadicExternal: Boolean,
        isVariadicFallback: Boolean,
        isBridgeable: Boolean,
    ) {
        if (isBridgeable && emittedJniWrappers.add(wrapperSymbolName)) {
            val baseJniParams = params.map { "${it.kotlinName}: ${jniKotlinTypeForParam(it, noStrConv, ktString)}" }
            val allJniParams =
                if (isVariadicExternal) {
                    baseJniParams + listOf("kmVac: Int", "kmVa0: Long", "kmVa1: Long", "kmVa2: Long", "kmVa3: Long")
                } else {
                    baseJniParams
                }
            val jniRet = jniKotlinTypeForReturn(returnBridgeKind)
            val jniRetSuffix = if (jniRet == "Unit") "" else ": $jniRet"
            jniExternalDecls += "    @JvmStatic\n    external fun $wrapperSymbolName(${allJniParams.joinToString(", ")})$jniRetSuffix"
        }
        if (!emittedActuals.add("fn:$kotlinName:${params.size}")) return
        actualBlocks +=
            if (isBridgeable) {
                renderJvmActualFunction(
                    visibilityPrefix = visibilityPrefix,
                    kotlinName = kotlinName,
                    jniCallee = "$jniObjectName.$wrapperSymbolName",
                    params = params,
                    kotlinReturnType = kotlinReturnType,
                    returnBridgeKind = returnBridgeKind,
                    returnDefaultExpr = returnDefaultExpr,
                    noStringConvEnabled = noStrConv,
                    isVariadic = isVariadicExternal,
                    pkgName = pkgName,
                    importsOut = imports,
                )
            } else {
                renderFallbackActualFunction(
                    visibilityPrefix = visibilityPrefix,
                    kotlinName = kotlinName,
                    params = params,
                    kotlinReturnType = kotlinReturnType,
                    returnBridgeKind = returnBridgeKind,
                    returnDefaultExpr = returnDefaultExpr,
                    isVariadic = isVariadicFallback,
                )
            }
    }

    for (fn in foreignFunctions) {
        processJniCallable(
            visibilityPrefix = fn.visibilityPrefix,
            kotlinName = fn.kotlinName,
            wrapperSymbolName = fn.wrapperSymbolName,
            params = fn.params,
            kotlinReturnType = fn.kotlinReturnType,
            returnBridgeKind = fn.returnBridgeKind,
            returnDefaultExpr = fn.returnDefaultExpr,
            noStrConv = requiresNoStringConversion(fn.params),
            isVariadicExternal = fn.isVariadic,
            isVariadicFallback = fn.isVariadic,
            isBridgeable = isBridgeableInCWrapper(fn),
        )
    }
    for (fn in inlineFunctions) {
        processJniCallable(
            visibilityPrefix = fn.visibilityPrefix,
            kotlinName = fn.kotlinName,
            wrapperSymbolName = fn.wrapperSymbolName,
            params = fn.params,
            kotlinReturnType = fn.kotlinReturnType,
            returnBridgeKind = fn.returnBridgeKind,
            returnDefaultExpr = fn.returnDefaultExpr,
            noStrConv = requiresNoStringConversionForInline(fn.params),
            isVariadicExternal = false,
            isVariadicFallback = fn.isVariadic,
            isBridgeable = isBridgeableInlineInCWrapper(fn),
        )
    }

    if (jniExternalDecls.isNotEmpty()) {
        val projNs =
            headerContext.projectNamespace
                ?.trim()
                ?.removeSuffix(".")
                ?.takeIf { it.isNotEmpty() } ?: pkgName.orEmpty()
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
                    append(buildJniResourceLoaderBody(jniObjectName, jniLibName, resourcePrefix))
                }
                append(jniExternalDecls.joinToString("\n\n")).append('\n')
                append("}")
            }
        actualBlocks.add(0, jniObjBlock)
    }

    return finalizeActualsKotlinFile(sb, actualBlocks, imports, pkgName)
}

internal fun FfiInteropCollector.buildJniCSourceContent(
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

internal fun renderJniStaticGetterFunction(
    st: ForeignStaticInteropSpec,
    jniClassMangled: String,
): String {
    val sb = StringBuilder()
    val jniRet = jniCTypeForReturn(st.bridgeKind)
    val jniSymbol = "Java_${jniClassMangled}_${jniMangleSegment(st.getterWrapperSymbolName)}"
    val cRet = cHeaderTypeForBridge(st.bridgeKind)
    val defaultLit = cDefaultFailureLiteral(st.bridgeKind)

    sb.append("JNIEXPORT $jniRet JNICALL $jniSymbol(JNIEnv* km_env, jclass km_clazz) {\n")
    sb.append("    (void)km_env;\n")
    sb.append("    (void)km_clazz;\n")
    appendDynamicSymbolLookupBlock(
        sb = sb,
        ptrVarName = "km_sym_ptr",
        ptrTypeDecl = "$cRet*",
        castTargetType = "$cRet*",
        cSymbolName = st.cSymbolName,
        isCpp = false,
    )
    sb.append("    $cRet km_res = (km_sym_ptr != NULL) ? *km_sym_ptr : $defaultLit;\n")
    sb.append("    return ${jniReturnCastExpr(st.bridgeKind, jniRet)};\n")
    sb.append("}\n")
    return sb.toString()
}

internal fun renderJniStaticSetterFunction(
    st: ForeignStaticInteropSpec,
    jniClassMangled: String,
): String {
    val sb = StringBuilder()
    val valParam = InteropParamSpec(kotlinName = "value", kotlinType = st.kotlinType, cName = "value", bridgeKind = st.bridgeKind)
    val jniValType = jniCTypeForParam(valParam, noStringConvEnabled = true)
    val jniSymbol = "Java_${jniClassMangled}_${jniMangleSegment(st.setterWrapperSymbolName)}"
    val cRet = cHeaderTypeForBridge(st.bridgeKind)
    val castVal =
        when {
            st.bridgeKind == CAbiBridgeKind.CONST_CSTR -> "(const char*)(uintptr_t)value"
            st.bridgeKind == CAbiBridgeKind.MUT_CSTR -> "(char*)(uintptr_t)value"
            bridgeDescriptorFor(st.bridgeKind).isPointerAddress -> "(void*)(uintptr_t)value"
            else -> "($cRet)value"
        }

    sb.append("JNIEXPORT void JNICALL $jniSymbol(JNIEnv* km_env, jclass km_clazz, $jniValType value) {\n")
    sb.append("    (void)km_env;\n")
    sb.append("    (void)km_clazz;\n")
    appendDynamicSymbolLookupBlock(
        sb = sb,
        ptrVarName = "km_sym_ptr",
        ptrTypeDecl = "$cRet*",
        castTargetType = "$cRet*",
        cSymbolName = st.cSymbolName,
        isCpp = false,
    )
    sb.append("    if (km_sym_ptr != NULL) {\n")
    sb.append("        *km_sym_ptr = $castVal;\n")
    sb.append("    }\n")
    sb.append("}\n")
    return sb.toString()
}

internal fun renderJniForeignFunction(
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

    val (stringParams, callArgs) = appendJniStringParamSetupAndCallArgs(sb, fn.params, noStrConv)
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
        appendDynamicFunctionDispatch(sb, fn, cRet, callArgs, hasReturn, isCpp = false)
        sb.append("#else\n")
        for (p in fn.params) {
            if (p !in stringParams) sb.append("    (void)${p.cName};\n")
        }
        if (fn.isVariadic) {
            sb.append("    (void)km_vac; (void)km_va0; (void)km_va1; (void)km_va2; (void)km_va3;\n")
        }
        sb.append("    errno = ENOSYS;\n")
        if (hasReturn) {
            sb.append("    $cRet km_res = ${cFailureReturnExpr(fn, isCpp = false)};\n")
        }
        sb.append("#endif\n")
    } else {
        sb.append("    typedef $cRet (*km_fn_t)($cParamTypes);\n")
        appendDynamicSymbolLookupBlock(
            sb = sb,
            ptrVarName = "km_fn_ptr",
            ptrTypeDecl = "km_fn_t",
            castTargetType = "km_fn_t",
            cSymbolName = fn.cSymbolName,
            isCpp = false,
            extraPosixFallback = { appendPosixDynamicLookupFallbacks(it, fn, isCpp = false) },
        )
        appendDynamicFunctionDispatch(sb, fn, cRet, callArgs, hasReturn, isCpp = false)
    }

    for (sp in stringParams) {
        sb.append("    if (${sp.cName} != NULL && ${sp.cName}_utf != NULL) (*km_env)->ReleaseStringUTFChars(km_env, ${sp.cName}, ${sp.cName}_utf);\n")
    }
    if (hasReturn) {
        sb.append("    return ${jniReturnCastExpr(fn.returnBridgeKind, jniRet)};\n")
    }
    if (isAtexit) {
        sb.append("#endif\n")
    }
    sb.append("}\n")
    return sb.toString()
}

internal fun FfiInteropCollector.renderJniInlineFunction(
    fn: InlineCFunctionSpec,
    jniClassMangled: String,
): String {
    val sb = StringBuilder()
    val inlineNoStrConv = requiresNoStringConversionForInline(fn.params)
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
        val (stringParams, callArgs) = appendJniStringParamSetupAndCallArgs(sb, fn.params, inlineNoStrConv)
        if (hasReturn) {
            sb.append("    ${cHeaderTypeForInlineReturn(fn)} km_res = ${fn.wrapperSymbolName}($callArgs);\n")
        } else {
            sb.append("    ${fn.wrapperSymbolName}($callArgs);\n")
        }
        for (sp in stringParams) {
            sb.append("    if (${sp.cName} != NULL && ${sp.cName}_utf != NULL) (*km_env)->ReleaseStringUTFChars(km_env, ${sp.cName}, ${sp.cName}_utf);\n")
        }
        if (hasReturn) {
            sb.append("    return ${jniReturnCastExpr(fn.returnBridgeKind, jniRet)};\n")
        }
    }
    sb.append("}\n")
    return sb.toString()
}

internal fun FfiInteropCollector.buildJsActualsKotlinContent(
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
        val inlineNoStrConv = requiresNoStringConversionForInline(fn.params)
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
        val jsExpr = "(function() { ${buildNodeNapiLoaderJsBody(ffiModName, returnBooleanStatus = false)}})()"
        actualBlocks.add(0, "private val $addonPropName: dynamic =\n    js(\"$jsExpr\")")
    }

    return finalizeActualsKotlinFile(sb, actualBlocks, imports, pkgName)
}

internal fun FfiInteropCollector.renderJsOrWasmParamConversion(
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
    return renderLongOrScalarBridgeReturnConversion(
        rawCall = rawCall,
        kotlinReturnType = kotlinReturnType,
        returnBridgeKind = returnBridgeKind,
        returnDefaultExpr = returnDefaultExpr,
        pkgName = pkgName,
        importsOut = importsOut,
        subIntBase = "($rawCall as Int)",
        i64Base = longStrExpr,
        u64Base = "($rawCall as $ktString).toULong()",
        ptrLongExpr = longStrExpr,
        boolExpr = "($rawCall as Boolean)",
        f32Expr = "($rawCall as Double).toFloat()",
        f64Expr = "($rawCall as Double)",
        signedSubIntKeepsRaw = false,
        fallbackNonScalar = returnDefaultExpr,
    )
}

internal fun FfiInteropCollector.renderJsStaticActualProperty(
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

internal fun FfiInteropCollector.renderJsActualFunction(
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
    val baseArgs = params.map { renderJsOrWasmParamConversion(it, noStringConvEnabled) }
    val allArgs = if (isVariadic) appendVariadicKotlinArgs(baseArgs, stringifyArgs = true) else baseArgs
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
    return renderActualFunctionDecl(
        visibilityPrefix = visibilityPrefix,
        kotlinName = kotlinName,
        params = params,
        kotlinReturnType = kotlinReturnType,
        returnBridgeKind = returnBridgeKind,
        isVariadic = isVariadic,
        rawCall = rawCall,
        bodyExpr = convertedCall,
        addonAvailabilityGuard = "$addonPropName != null",
    )
}

internal fun FfiInteropCollector.buildNapiCppSourceContent(stemSnake: String): String {
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

private fun renderNapiValueExtraction(
    bridgeKind: CAbiBridgeKind,
    varName: String,
    argIdx: Int,
    cDeclType: String,
): String =
    when (bridgeKind) {
        CAbiBridgeKind.CONST_CSTR ->
            "    const char* $varName = reinterpret_cast<const char*>(static_cast<uintptr_t>(km_info.Length() > $argIdx ? km_napi_arg_u64(km_info[$argIdx]) : 0));\n"
        CAbiBridgeKind.MUT_CSTR ->
            "    char* $varName = reinterpret_cast<char*>(static_cast<uintptr_t>(km_info.Length() > $argIdx ? km_napi_arg_u64(km_info[$argIdx]) : 0));\n"
        CAbiBridgeKind.OPAQUE_PTR,
        CAbiBridgeKind.FILE_PTR,
        CAbiBridgeKind.DIR_PTR,
        CAbiBridgeKind.CALLBACK_PTR,
        ->
            "    void* $varName = reinterpret_cast<void*>(static_cast<uintptr_t>(km_info.Length() > $argIdx ? km_napi_arg_u64(km_info[$argIdx]) : 0));\n"
        CAbiBridgeKind.I64 ->
            "    int64_t $varName = km_info.Length() > $argIdx ? km_napi_arg_i64(km_info[$argIdx]) : 0;\n"
        CAbiBridgeKind.U64 ->
            "    uint64_t $varName = km_info.Length() > $argIdx ? km_napi_arg_u64(km_info[$argIdx]) : 0;\n"
        CAbiBridgeKind.BOOL ->
            "    bool $varName = km_info.Length() > $argIdx ? km_info[$argIdx].ToBoolean().Value() : false;\n"
        CAbiBridgeKind.F32 ->
            "    float $varName = km_info.Length() > $argIdx ? km_info[$argIdx].ToNumber().FloatValue() : 0.0f;\n"
        CAbiBridgeKind.F64 ->
            "    double $varName = km_info.Length() > $argIdx ? km_info[$argIdx].ToNumber().DoubleValue() : 0.0;\n"
        else ->
            "    $cDeclType $varName = static_cast<$cDeclType>(km_info.Length() > $argIdx ? km_info[$argIdx].ToNumber().Int32Value() : 0);\n"
    }

private fun appendNapiParamExtractions(
    sb: StringBuilder,
    params: List<InteropParamSpec>,
    noStrConv: Boolean,
) {
    for ((idx, p) in params.withIndex()) {
        val isStr =
            p.bridgeKind == CAbiBridgeKind.CONST_CSTR &&
                !noStrConv &&
                (p.kotlinType == "String?" || p.kotlinType == "String")
        if (isStr) {
            sb.append("    bool ${p.cName}_null = (km_info.Length() <= $idx || km_info[$idx].IsNull() || km_info[$idx].IsUndefined());\n")
            sb.append("    std::string ${p.cName}_str = ${p.cName}_null ? std::string() : km_info[$idx].ToString().Utf8Value();\n")
            sb.append("    const char* ${p.cName} = ${p.cName}_null ? nullptr : ${p.cName}_str.c_str();\n")
        } else {
            sb.append(renderNapiValueExtraction(p.bridgeKind, p.cName, idx, cHeaderTypeForParam(p)))
        }
    }
}

internal fun renderNapiStaticGetterFunction(st: ForeignStaticInteropSpec): String {
    val sb = StringBuilder()
    val cRet = cHeaderTypeForBridge(st.bridgeKind)
    val defaultLit = cDefaultFailureLiteral(st.bridgeKind)

    sb.append("static Napi::Value ${st.getterWrapperSymbolName}_napi(const Napi::CallbackInfo& km_info) {\n")
    sb.append("    Napi::Env km_env = km_info.Env();\n")
    appendDynamicSymbolLookupBlock(
        sb = sb,
        ptrVarName = "km_sym_ptr",
        ptrTypeDecl = "$cRet*",
        castTargetType = "$cRet*",
        cSymbolName = st.cSymbolName,
        isCpp = true,
    )
    sb.append("    $cRet km_res = (km_sym_ptr != nullptr) ? *km_sym_ptr : $defaultLit;\n")
    sb.append(renderNapiReturnStatement(st.bridgeKind))
    sb.append("}\n")
    return sb.toString()
}

internal fun renderNapiStaticSetterFunction(st: ForeignStaticInteropSpec): String {
    val sb = StringBuilder()
    val cRet = cHeaderTypeForBridge(st.bridgeKind)

    sb.append("static Napi::Value ${st.setterWrapperSymbolName}_napi(const Napi::CallbackInfo& km_info) {\n")
    sb.append("    Napi::Env km_env = km_info.Env();\n")
    sb.append(renderNapiValueExtraction(st.bridgeKind, "value", 0, cRet))
    appendDynamicSymbolLookupBlock(
        sb = sb,
        ptrVarName = "km_sym_ptr",
        ptrTypeDecl = "$cRet*",
        castTargetType = "$cRet*",
        cSymbolName = st.cSymbolName,
        isCpp = true,
    )
    sb.append("    if (km_sym_ptr != nullptr) {\n")
    sb.append("        *km_sym_ptr = value;\n")
    sb.append("    }\n")
    sb.append("    return km_env.Undefined();\n")
    sb.append("}\n")
    return sb.toString()
}

internal fun renderNapiForeignFunction(fn: ForeignFnInteropSpec): String {
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
    appendNapiParamExtractions(sb, fn.params, noStrConv)
    if (fn.isVariadic) {
        val baseLen = fn.params.size
        sb.append("    int32_t km_vac = km_info.Length() > $baseLen ? km_info[$baseLen].ToNumber().Int32Value() : 0;\n")
        for (i in 0 until 4) {
            sb.append("    int64_t km_va$i = km_info.Length() > ${baseLen + 1 + i} ? km_napi_arg_i64(km_info[${baseLen + 1 + i}]) : 0;\n")
        }
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
        appendDynamicFunctionDispatch(sb, fn, cRet, callArgs, hasReturn, isCpp = true)
        sb.append("#else\n")
        for (p in fn.params) {
            sb.append("    (void)${p.cName};\n")
        }
        if (fn.isVariadic) {
            sb.append("    (void)km_vac; (void)km_va0; (void)km_va1; (void)km_va2; (void)km_va3;\n")
        }
        sb.append("    errno = ENOSYS;\n")
        if (hasReturn) {
            sb.append("    $cRet km_res = ${cFailureReturnExpr(fn, isCpp = true)};\n")
        }
        sb.append("#endif\n")
    } else {
        sb.append("    typedef $cRet (*km_fn_t)($cParamTypes);\n")
        appendDynamicSymbolLookupBlock(
            sb = sb,
            ptrVarName = "km_fn_ptr",
            ptrTypeDecl = "km_fn_t",
            castTargetType = "km_fn_t",
            cSymbolName = fn.cSymbolName,
            isCpp = true,
            extraPosixFallback = { appendPosixDynamicLookupFallbacks(it, fn, isCpp = true) },
        )
        appendDynamicFunctionDispatch(sb, fn, cRet, callArgs, hasReturn, isCpp = true)
    }

    sb.append(renderNapiReturnStatement(fn.returnBridgeKind))
    if (isAtexit) {
        sb.append("#endif\n")
    }
    sb.append("}\n")
    return sb.toString()
}

internal fun FfiInteropCollector.renderNapiInlineFunction(fn: InlineCFunctionSpec): String {
    val sb = StringBuilder()
    val inlineNoStrConv = requiresNoStringConversionForInline(fn.params)
    val hasReturn = fn.returnBridgeKind != CAbiBridgeKind.VOID && fn.returnBridgeKind != CAbiBridgeKind.NEVER
    sb.append("static ").append(renderInlineWrapperCFunction(fn)).append('\n')
    sb.append("static Napi::Value ${fn.wrapperSymbolName}_napi(const Napi::CallbackInfo& km_info) {\n")
    sb.append("    Napi::Env km_env = km_info.Env();\n")
    appendNapiParamExtractions(sb, fn.params, inlineNoStrConv)
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

internal fun renderNapiReturnStatement(returnBridgeKind: CAbiBridgeKind): String =
    when (returnBridgeKind) {
        CAbiBridgeKind.VOID, CAbiBridgeKind.NEVER, CAbiBridgeKind.BY_VALUE_OBJECT ->
            "    return km_env.Undefined();\n"
        CAbiBridgeKind.BOOL ->
            "    return Napi::Boolean::New(km_env, km_res);\n"
        CAbiBridgeKind.I8, CAbiBridgeKind.U8,
        CAbiBridgeKind.I16, CAbiBridgeKind.U16,
        CAbiBridgeKind.I32, CAbiBridgeKind.U32,
        -> "    return Napi::Number::New(km_env, static_cast<int32_t>(km_res));\n"
        CAbiBridgeKind.I64 ->
            "    return Napi::String::New(km_env, std::to_string(static_cast<int64_t>(km_res)));\n"
        CAbiBridgeKind.U64 ->
            "    return Napi::String::New(km_env, std::to_string(static_cast<uint64_t>(km_res)));\n"
        CAbiBridgeKind.F32, CAbiBridgeKind.F64 ->
            "    return Napi::Number::New(km_env, static_cast<double>(km_res));\n"
        CAbiBridgeKind.CONST_CSTR,
        CAbiBridgeKind.MUT_CSTR,
        CAbiBridgeKind.OPAQUE_PTR,
        CAbiBridgeKind.FILE_PTR,
        CAbiBridgeKind.DIR_PTR,
        CAbiBridgeKind.CALLBACK_PTR,
        -> "    return Napi::String::New(km_env, std::to_string(static_cast<int64_t>(reinterpret_cast<uintptr_t>(km_res))));\n"
    }
