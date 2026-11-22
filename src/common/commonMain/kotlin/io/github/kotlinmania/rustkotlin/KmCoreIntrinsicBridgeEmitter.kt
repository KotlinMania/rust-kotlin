package io.github.kotlinmania.rustkotlin

/**
 * Canonical scalar types used across the `km*` core intrinsic boundary, carrying
 * Kotlin, JVM/JNI, C, and JS/Wasm/N-API marshalling metadata in one place.
 */
internal enum class KmScalarType(
    val ktType: String,
    val jvmKtType: String,
    val jniCType: String,
    val cType: String,
    val napiSlots: Int = 1,
) {
    VOID("Unit", "Unit", "void", "void", napiSlots = 0),
    NEVER("Nothing", "Unit", "void", "void", napiSlots = 0),
    I64("Long", "Long", "jlong", "int64_t", napiSlots = 2),
    U64("ULong", "Long", "jlong", "uint64_t", napiSlots = 2),
    I32("Int", "Int", "jint", "int32_t"),
    U32("UInt", "Int", "jint", "uint32_t"),
    U16("UShort", "Short", "jshort", "uint16_t"),
    U8("UByte", "Byte", "jbyte", "uint8_t"),
    F32("Float", "Float", "jfloat", "float"),
    F64("Double", "Double", "jdouble", "double"),
    ;

    fun toJvmArg(expr: String): String =
        when (this) {
            U64 -> "$expr.toLong()"
            U32 -> "$expr.toInt()"
            U16 -> "$expr.toShort()"
            U8 -> "$expr.toByte()"
            else -> expr
        }

    fun fromJvmRet(expr: String): String =
        when (this) {
            U64 -> "$expr.toULong()"
            U32 -> "$expr.toUInt()"
            U16 -> "$expr.toUShort()"
            U8 -> "$expr.toUByte()"
            else -> expr
        }

    fun napiExtractArg(slotIdx: Int): String =
        when (this) {
            I64 -> "kmCoreNapiArgI64Pair(info, $slotIdx)"
            U64 -> "kmCoreNapiArgU64Pair(info, $slotIdx)"
            I32 -> "kmCoreNapiArgI32(info[$slotIdx])"
            U32 -> "kmCoreNapiArgU32(info[$slotIdx])"
            U16 -> "static_cast<uint16_t>(kmCoreNapiArgU32(info[$slotIdx]))"
            U8 -> "static_cast<uint8_t>(kmCoreNapiArgU32(info[$slotIdx]))"
            F32 -> "static_cast<float>(info[$slotIdx].As<Napi::Number>().DoubleValue())"
            F64 -> "info[$slotIdx].As<Napi::Number>().DoubleValue()"
            VOID, NEVER -> error("Void/never cannot be an N-API argument")
        }

    fun jsPassArgs(paramName: String): String =
        when (this) {
            I64 -> "lo32($paramName), hi32($paramName)"
            U64 -> "uLo32($paramName), uHi32($paramName)"
            U32, U16, U8 -> "$paramName.toInt()"
            F32 -> "$paramName.toDouble()"
            I32, F64 -> paramName
            VOID, NEVER -> error("Void/never cannot be a JS argument")
        }

    fun wasmExtParams(paramName: String): List<Pair<String, String>> =
        when (this) {
            I64, U64 -> listOf("${paramName}Lo" to "Int", "${paramName}Hi" to "Int")
            F32, F64 -> listOf(paramName to "Double")
            I32, U32, U16, U8 -> listOf(paramName to "Int")
            VOID, NEVER -> emptyList()
        }

    val wasmExtRetType: String?
        get() =
            when (this) {
                VOID, NEVER -> null
                F32, F64 -> "Double"
                else -> "Int"
            }
}

internal sealed class KmBaseIntrinsicSpec {
    data class Regular(
        val name: String,
        val params: List<Pair<String, KmScalarType>>,
        val ret: KmScalarType,
        val arenaExpr: String?,
        val napiCallee: String = name,
    ) : KmBaseIntrinsicSpec()

    data class AtomicCxchg(
        val bits: Int,
        val valueType: KmScalarType,
    ) : KmBaseIntrinsicSpec() {
        val name: String = "kmAtomicCxchgU$bits"
        val outcomeName: String = "AtomicCxchgU${bits}Outcome"
    }

    data object AtomicCxchgU128 : KmBaseIntrinsicSpec()
}

internal val KM_BASE_INTRINSIC_SPECS: List<KmBaseIntrinsicSpec> =
    buildList {
        val memTypes =
            listOf(
                "U8" to KmScalarType.U8,
                "U16" to KmScalarType.U16,
                "U32" to KmScalarType.U32,
                "U64" to KmScalarType.U64,
                "F32" to KmScalarType.F32,
                "F64" to KmScalarType.F64,
            )
        val intMemTypes = memTypes.take(4)

        for ((tag, ty) in memTypes) {
            add(KmBaseIntrinsicSpec.Regular("kmLoad$tag", listOf("addr" to KmScalarType.I64), ty, "KmLinearMemoryArena.load$tag(addr)"))
        }
        for ((tag, ty) in memTypes) {
            add(KmBaseIntrinsicSpec.Regular("kmStore$tag", listOf("addr" to KmScalarType.I64, "value" to ty), KmScalarType.VOID, "KmLinearMemoryArena.store$tag(addr, value)"))
        }
        for ((tag, ty) in intMemTypes) {
            add(KmBaseIntrinsicSpec.Regular("kmVolatileLoad$tag", listOf("addr" to KmScalarType.I64), ty, "KmLinearMemoryArena.load$tag(addr)"))
        }
        for ((tag, ty) in intMemTypes) {
            add(KmBaseIntrinsicSpec.Regular("kmVolatileStore$tag", listOf("addr" to KmScalarType.I64, "value" to ty), KmScalarType.VOID, "KmLinearMemoryArena.store$tag(addr, value)"))
        }

        add(KmBaseIntrinsicSpec.Regular("kmCopyNonoverlapping", listOf("dst" to KmScalarType.I64, "src" to KmScalarType.I64, "bytes" to KmScalarType.I64), KmScalarType.VOID, "KmLinearMemoryArena.copyNonoverlapping(dst, src, bytes)"))
        add(KmBaseIntrinsicSpec.Regular("kmCopy", listOf("dst" to KmScalarType.I64, "src" to KmScalarType.I64, "bytes" to KmScalarType.I64), KmScalarType.VOID, "KmLinearMemoryArena.copy(dst, src, bytes)"))
        add(KmBaseIntrinsicSpec.Regular("kmWriteBytes", listOf("dst" to KmScalarType.I64, "value" to KmScalarType.U8, "bytes" to KmScalarType.I64), KmScalarType.VOID, "KmLinearMemoryArena.writeBytes(dst, value, bytes)"))
        add(KmBaseIntrinsicSpec.Regular("kmCompareBytes", listOf("left" to KmScalarType.I64, "right" to KmScalarType.I64, "bytes" to KmScalarType.I64), KmScalarType.I32, "KmLinearMemoryArena.compareBytes(left, right, bytes)"))

        add(KmBaseIntrinsicSpec.Regular("kmAlloc", listOf("size" to KmScalarType.I64, "align" to KmScalarType.I64), KmScalarType.I64, "KmLinearMemoryArena.alloc(size, align)"))
        add(KmBaseIntrinsicSpec.Regular("kmAllocZeroed", listOf("size" to KmScalarType.I64, "align" to KmScalarType.I64), KmScalarType.I64, "KmLinearMemoryArena.allocZeroed(size, align)"))
        add(KmBaseIntrinsicSpec.Regular("kmRealloc", listOf("ptr" to KmScalarType.I64, "oldSize" to KmScalarType.I64, "align" to KmScalarType.I64, "newSize" to KmScalarType.I64), KmScalarType.I64, "KmLinearMemoryArena.realloc(ptr, oldSize, align, newSize)"))
        add(KmBaseIntrinsicSpec.Regular("kmDealloc", listOf("ptr" to KmScalarType.I64, "size" to KmScalarType.I64, "align" to KmScalarType.I64), KmScalarType.VOID, "KmLinearMemoryArena.dealloc(ptr, size, align)"))

        add(KmBaseIntrinsicSpec.Regular("kmAtomicFence", listOf("ord" to KmScalarType.I32), KmScalarType.VOID, null))
        add(KmBaseIntrinsicSpec.Regular("kmAtomicSignalFence", listOf("ord" to KmScalarType.I32), KmScalarType.VOID, null))
        for ((tag, ty) in intMemTypes) {
            add(KmBaseIntrinsicSpec.Regular("kmAtomicLoad$tag", listOf("addr" to KmScalarType.I64, "ord" to KmScalarType.I32), ty, "KmLinearMemoryArena.load$tag(addr)"))
        }
        for ((tag, ty) in intMemTypes) {
            add(KmBaseIntrinsicSpec.Regular("kmAtomicStore$tag", listOf("addr" to KmScalarType.I64, "value" to ty, "ord" to KmScalarType.I32), KmScalarType.VOID, "KmLinearMemoryArena.store$tag(addr, value)"))
        }
        for ((tag, ty) in intMemTypes) {
            add(KmBaseIntrinsicSpec.Regular("kmAtomicXchg$tag", listOf("addr" to KmScalarType.I64, "value" to ty, "ord" to KmScalarType.I32), ty, "KmLinearMemoryArena.atomicXchg$tag(addr, value)"))
        }
        add(KmBaseIntrinsicSpec.AtomicCxchg(8, KmScalarType.U8))
        add(KmBaseIntrinsicSpec.AtomicCxchg(16, KmScalarType.U16))
        add(KmBaseIntrinsicSpec.AtomicCxchg(32, KmScalarType.U32))
        add(KmBaseIntrinsicSpec.AtomicCxchg(64, KmScalarType.U64))
        for ((tag, ty) in intMemTypes) {
            add(KmBaseIntrinsicSpec.Regular("kmAtomicRmw$tag", listOf("addr" to KmScalarType.I64, "value" to ty, "op" to KmScalarType.I32, "ord" to KmScalarType.I32), ty, "KmLinearMemoryArena.atomicRmw$tag(addr, value, op)"))
        }

        add(KmBaseIntrinsicSpec.Regular("kmCtpopU64", listOf("value" to KmScalarType.U64), KmScalarType.U32, "KmLinearMemoryArena.ctpopU64(value)"))
        add(KmBaseIntrinsicSpec.Regular("kmCtlzU64", listOf("value" to KmScalarType.U64), KmScalarType.U32, "KmLinearMemoryArena.ctlzU64(value)"))
        add(KmBaseIntrinsicSpec.Regular("kmCttzU64", listOf("value" to KmScalarType.U64), KmScalarType.U32, "KmLinearMemoryArena.cttzU64(value)"))
        add(KmBaseIntrinsicSpec.Regular("kmBswapU16", listOf("value" to KmScalarType.U16), KmScalarType.U16, "KmLinearMemoryArena.bswapU16(value)"))
        add(KmBaseIntrinsicSpec.Regular("kmBswapU32", listOf("value" to KmScalarType.U32), KmScalarType.U32, "KmLinearMemoryArena.bswapU32(value)"))
        add(KmBaseIntrinsicSpec.Regular("kmBswapU64", listOf("value" to KmScalarType.U64), KmScalarType.U64, "KmLinearMemoryArena.bswapU64(value)"))
        add(KmBaseIntrinsicSpec.Regular("kmBitreverseU64", listOf("value" to KmScalarType.U64), KmScalarType.U64, "KmLinearMemoryArena.bitreverseU64(value)"))
        add(KmBaseIntrinsicSpec.Regular("kmFmaf32", listOf("a" to KmScalarType.F32, "b" to KmScalarType.F32, "c" to KmScalarType.F32), KmScalarType.F32, "KmLinearMemoryArena.fmaf32(a, b, c)"))
        add(KmBaseIntrinsicSpec.Regular("kmFmaf64", listOf("a" to KmScalarType.F64, "b" to KmScalarType.F64, "c" to KmScalarType.F64), KmScalarType.F64, "KmLinearMemoryArena.fmaf64(a, b, c)"))
        add(KmBaseIntrinsicSpec.Regular("kmSqrtf32", listOf("x" to KmScalarType.F32), KmScalarType.F32, "KmLinearMemoryArena.sqrtf32(x)"))
        add(KmBaseIntrinsicSpec.Regular("kmSqrtf64", listOf("x" to KmScalarType.F64), KmScalarType.F64, "KmLinearMemoryArena.sqrtf64(x)"))
        add(KmBaseIntrinsicSpec.Regular("kmAbort", emptyList(), KmScalarType.NEVER, null))

        add(KmBaseIntrinsicSpec.Regular("kmSpinLoopHint", emptyList(), KmScalarType.VOID, null, napiCallee = "kmCoreCpuSpinHint"))
        add(KmBaseIntrinsicSpec.Regular("kmBlackBoxU64", listOf("value" to KmScalarType.U64), KmScalarType.U64, "value", napiCallee = "kmCoreCpuBlackBoxU64"))
        add(KmBaseIntrinsicSpec.Regular("kmUmulhU64", listOf("a" to KmScalarType.U64, "b" to KmScalarType.U64), KmScalarType.U64, "KmLinearMemoryArena.umulhU64(a, b)", napiCallee = "kmCoreCpuUmulhU64"))
        add(KmBaseIntrinsicSpec.Regular("kmSmulhI64", listOf("a" to KmScalarType.I64, "b" to KmScalarType.I64), KmScalarType.I64, "KmLinearMemoryArena.smulhI64(a, b)", napiCallee = "kmCoreCpuSmulhI64"))
        add(KmBaseIntrinsicSpec.AtomicCxchgU128)
        add(KmBaseIntrinsicSpec.Regular("kmSimdFmaF32x4", listOf("dstAddr" to KmScalarType.I64, "aAddr" to KmScalarType.I64, "bAddr" to KmScalarType.I64, "cAddr" to KmScalarType.I64), KmScalarType.VOID, "KmLinearMemoryArena.simdFmaF32x4(dstAddr, aAddr, bAddr, cAddr)"))
        add(KmBaseIntrinsicSpec.Regular("kmSimdDotF32", listOf("aAddr" to KmScalarType.I64, "bAddr" to KmScalarType.I64, "len" to KmScalarType.I64), KmScalarType.F32, "KmLinearMemoryArena.simdDotF32(aAddr, bAddr, len)"))
    }

/**
 * Extensible base class for emitting all 69 base `km*` intrinsics across `expect`,
 * Kotlin/Native `cinterop`, JVM/Android JNI Kotlin + C, Node N-API C++, JS, WasmJS, and WasmWASI.
 */
internal abstract class KmCoreIntrinsicBridgeEmitter {
    protected abstract fun emitRegular(out: StringBuilder, spec: KmBaseIntrinsicSpec.Regular)

    protected abstract fun emitAtomicCxchg(out: StringBuilder, spec: KmBaseIntrinsicSpec.AtomicCxchg)

    protected abstract fun emitAtomicCxchgU128(out: StringBuilder)

    fun renderDeclarations(specs: List<KmBaseIntrinsicSpec> = KM_BASE_INTRINSIC_SPECS): String =
        buildString {
            for (spec in specs) {
                when (spec) {
                    is KmBaseIntrinsicSpec.Regular -> emitRegular(this, spec)
                    is KmBaseIntrinsicSpec.AtomicCxchg -> emitAtomicCxchg(this, spec)
                    KmBaseIntrinsicSpec.AtomicCxchgU128 -> emitAtomicCxchgU128(this)
                }
            }
        }.trimEnd()

    protected fun formatKotlinParamList(params: List<Pair<String, KmScalarType>>): String =
        params.joinToString(", ") { (n, t) -> "$n: ${t.ktType}" }

    protected fun formatKotlinReturnSuffix(ret: KmScalarType, includeUnit: Boolean = false): String =
        if (ret == KmScalarType.VOID && !includeUnit) "" else ": ${ret.ktType}"
}

internal object KmExpectBridgeEmitter : KmCoreIntrinsicBridgeEmitter() {
    override fun emitRegular(out: StringBuilder, spec: KmBaseIntrinsicSpec.Regular) {
        out.appendLine("public expect fun ${spec.name}(${formatKotlinParamList(spec.params)})${formatKotlinReturnSuffix(spec.ret)}")
    }

    override fun emitAtomicCxchg(out: StringBuilder, spec: KmBaseIntrinsicSpec.AtomicCxchg) {
        val vt = spec.valueType.ktType
        out.appendLine("public expect fun ${spec.name}(addr: Long, expected: $vt, desired: $vt, succ: Int, fail: Int): ${spec.outcomeName}")
    }

    override fun emitAtomicCxchgU128(out: StringBuilder) {
        out.appendLine(
            "public expect fun kmAtomicCxchgU128Pair(addr: Long, expLo: ULong, expHi: ULong, desLo: ULong, desHi: ULong): AtomicCxchgU128Outcome",
        )
    }
}

internal class KmNativeBridgeEmitter(
    private val cinteropPackage: String,
) : KmCoreIntrinsicBridgeEmitter() {
    override fun emitRegular(out: StringBuilder, spec: KmBaseIntrinsicSpec.Regular) {
        val args = spec.params.joinToString(", ") { it.first }
        if (spec.ret == KmScalarType.NEVER) {
            out.appendLine("public actual fun ${spec.name}(${formatKotlinParamList(spec.params)}): Nothing {")
            out.appendLine("    $cinteropPackage.${spec.name}($args)")
            out.appendLine("    error(\"Unreachable after ${spec.name}\")")
            out.appendLine("}")
        } else {
            out.appendLine(
                "public actual fun ${spec.name}(${formatKotlinParamList(spec.params)})${formatKotlinReturnSuffix(spec.ret, includeUnit = true)} = $cinteropPackage.${spec.name}($args)",
            )
        }
    }

    override fun emitAtomicCxchg(out: StringBuilder, spec: KmBaseIntrinsicSpec.AtomicCxchg) {
        val vt = spec.valueType.ktType
        out.appendLine(
            """
public actual fun ${spec.name}(addr: Long, expected: $vt, desired: $vt, succ: Int, fail: Int): ${spec.outcomeName} =
    memScoped {
        val ok = alloc<UByteVar>()
        val prev = $cinteropPackage.${spec.name}(addr, expected, desired, succ, fail, ok.ptr)
        ${spec.outcomeName}(prev, ok.value != 0u.toUByte())
    }
            """.trim(),
        )
    }

    override fun emitAtomicCxchgU128(out: StringBuilder) {
        out.appendLine(
            """
public actual fun kmAtomicCxchgU128Pair(
    addr: Long,
    expLo: ULong,
    expHi: ULong,
    desLo: ULong,
    desHi: ULong,
): AtomicCxchgU128Outcome =
    memScoped {
        val outLo = alloc<ULongVar>()
        val outHi = alloc<ULongVar>()
        val ok = $cinteropPackage.kmAtomicCxchgU128(addr, expLo, expHi, desLo, desHi, outLo.ptr, outHi.ptr)
        AtomicCxchgU128Outcome(outLo.value, outHi.value, ok != 0u.toUByte())
    }
            """.trim(),
        )
    }
}

internal class KmJvmBridgeEmitter(
    private val jniObjectName: String,
) : KmCoreIntrinsicBridgeEmitter() {
    fun renderExternalDeclarations(specs: List<KmBaseIntrinsicSpec> = KM_BASE_INTRINSIC_SPECS): String =
        buildString {
            for (spec in specs) {
                when (spec) {
                    is KmBaseIntrinsicSpec.Regular -> {
                        val params = spec.params.joinToString(", ") { (n, t) -> "$n: ${t.jvmKtType}" }
                        val ret = if (spec.ret == KmScalarType.VOID || spec.ret == KmScalarType.NEVER) "" else ": ${spec.ret.jvmKtType}"
                        appendLine("    @JvmStatic external fun ${spec.name}($params)$ret")
                    }
                    is KmBaseIntrinsicSpec.AtomicCxchg -> {
                        val jvt = spec.valueType.jvmKtType
                        if (spec.bits < 64) {
                            appendLine("    @JvmStatic external fun ${spec.name}Packed(addr: Long, expected: $jvt, desired: $jvt, succ: Int, fail: Int): Long")
                        } else {
                            appendLine("    @JvmStatic external fun ${spec.name}Out(addr: Long, expected: Long, desired: Long, succ: Int, fail: Int, outBuf: LongArray)")
                        }
                    }
                    KmBaseIntrinsicSpec.AtomicCxchgU128 ->
                        appendLine("    @JvmStatic external fun kmAtomicCxchgU128Out(addr: Long, expLo: Long, expHi: Long, desLo: Long, desHi: Long, outBuf: LongArray)")
                }
            }
        }.trimEnd()

    override fun emitRegular(out: StringBuilder, spec: KmBaseIntrinsicSpec.Regular) {
        val callArgs = spec.params.joinToString(", ") { (n, t) -> t.toJvmArg(n) }
        val rawCall = "$jniObjectName.${spec.name}($callArgs)"
        if (spec.ret == KmScalarType.NEVER) {
            out.appendLine("public actual fun ${spec.name}(${formatKotlinParamList(spec.params)}): Nothing {")
            out.appendLine("    $rawCall")
            out.appendLine("    error(\"Unreachable after ${spec.name}\")")
            out.appendLine("}")
        } else {
            out.appendLine(
                "public actual fun ${spec.name}(${formatKotlinParamList(spec.params)})${formatKotlinReturnSuffix(spec.ret, includeUnit = true)} = ${spec.ret.fromJvmRet(rawCall)}",
            )
        }
    }

    override fun emitAtomicCxchg(out: StringBuilder, spec: KmBaseIntrinsicSpec.AtomicCxchg) {
        val vt = spec.valueType.ktType
        if (spec.bits < 64) {
            val mask =
                when (spec.bits) {
                    8 -> "0xFFL"
                    16 -> "0xFFFFL"
                    else -> "0xFFFFFFFFL"
                }
            val expArg = spec.valueType.toJvmArg("expected")
            val desArg = spec.valueType.toJvmArg("desired")
            out.appendLine(
                """
public actual fun ${spec.name}(addr: Long, expected: $vt, desired: $vt, succ: Int, fail: Int): ${spec.outcomeName} {
    val packed = $jniObjectName.${spec.name}Packed(addr, $expArg, $desArg, succ, fail)
    return ${spec.outcomeName}((packed and $mask).to$vt(), (packed ushr 32) != 0L)
}
                """.trim(),
            )
        } else {
            out.appendLine(
                """
public actual fun kmAtomicCxchgU64(addr: Long, expected: ULong, desired: ULong, succ: Int, fail: Int): AtomicCxchgU64Outcome {
    val outBuf = LongArray(2)
    $jniObjectName.kmAtomicCxchgU64Out(addr, expected.toLong(), desired.toLong(), succ, fail, outBuf)
    return AtomicCxchgU64Outcome(outBuf[0].toULong(), outBuf[1] != 0L)
}
                """.trim(),
            )
        }
    }

    override fun emitAtomicCxchgU128(out: StringBuilder) {
        out.appendLine(
            """
public actual fun kmAtomicCxchgU128Pair(
    addr: Long,
    expLo: ULong,
    expHi: ULong,
    desLo: ULong,
    desHi: ULong,
): AtomicCxchgU128Outcome {
    val outBuf = LongArray(3)
    $jniObjectName.kmAtomicCxchgU128Out(addr, expLo.toLong(), expHi.toLong(), desLo.toLong(), desHi.toLong(), outBuf)
    return AtomicCxchgU128Outcome(outBuf[0].toULong(), outBuf[1].toULong(), outBuf[2] != 0L)
}
            """.trim(),
        )
    }
}

internal object KmJniCBridgeEmitter : KmCoreIntrinsicBridgeEmitter() {
    override fun emitRegular(out: StringBuilder, spec: KmBaseIntrinsicSpec.Regular) {
        val jniParams =
            buildList {
                add("JNIEnv* env")
                add("jclass clazz")
                for ((n, t) in spec.params) add("${t.jniCType} $n")
            }.joinToString(", ")
        val cArgs = spec.params.joinToString(", ") { (n, t) -> "(${t.cType})$n" }
        out.appendLine("JNIEXPORT ${spec.ret.jniCType} JNICALL KM_JNI_FN(${spec.name})($jniParams) {")
        out.appendLine("    (void)env; (void)clazz;")
        if (spec.ret == KmScalarType.VOID || spec.ret == KmScalarType.NEVER) {
            out.appendLine("    ${spec.name}($cArgs);")
        } else {
            out.appendLine("    return (${spec.ret.jniCType})${spec.name}($cArgs);")
        }
        out.appendLine("}")
    }

    override fun emitAtomicCxchg(out: StringBuilder, spec: KmBaseIntrinsicSpec.AtomicCxchg) {
        val jvt = spec.valueType.jniCType
        val ct = spec.valueType.cType
        if (spec.bits < 64) {
            out.appendLine(
                """
JNIEXPORT jlong JNICALL KM_JNI_FN(${spec.name}Packed)(
    JNIEnv* env, jclass clazz, jlong addr, $jvt expected, $jvt desired, jint succ, jint fail
) {
    (void)env; (void)clazz;
    uint8_t ok = 0;
    $ct prev = ${spec.name}((int64_t)addr, ($ct)expected, ($ct)desired, (int32_t)succ, (int32_t)fail, &ok);
    return ((jlong)(uint64_t)ok << 32) | (jlong)(uint64_t)prev;
}
                """.trim(),
            )
        } else {
            out.appendLine(
                """
JNIEXPORT void JNICALL KM_JNI_FN(kmAtomicCxchgU64Out)(
    JNIEnv* env, jclass clazz, jlong addr, jlong expected, jlong desired, jint succ, jint fail, jlongArray outBuf
) {
    (void)clazz;
    uint8_t ok = 0;
    uint64_t prev = kmAtomicCxchgU64((int64_t)addr, (uint64_t)expected, (uint64_t)desired, (int32_t)succ, (int32_t)fail, &ok);
    jlong buf[2];
    buf[0] = (jlong)prev;
    buf[1] = (jlong)(uint64_t)ok;
    (*env)->SetLongArrayRegion(env, outBuf, 0, 2, buf);
}
                """.trim(),
            )
        }
    }

    override fun emitAtomicCxchgU128(out: StringBuilder) {
        out.appendLine(
            """
JNIEXPORT void JNICALL KM_JNI_FN(kmAtomicCxchgU128Out)(
    JNIEnv* env, jclass clazz, jlong addr, jlong expLo, jlong expHi, jlong desLo, jlong desHi, jlongArray outBuf
) {
    (void)clazz;
    uint64_t prevLo = 0;
    uint64_t prevHi = 0;
    uint8_t ok = kmAtomicCxchgU128(
        (int64_t)addr,
        (uint64_t)expLo,
        (uint64_t)expHi,
        (uint64_t)desLo,
        (uint64_t)desHi,
        &prevLo,
        &prevHi
    );
    jlong buf[3];
    buf[0] = (jlong)prevLo;
    buf[1] = (jlong)prevHi;
    buf[2] = (jlong)(uint64_t)ok;
    (*env)->SetLongArrayRegion(env, outBuf, 0, 3, buf);
}
            """.trim(),
        )
    }
}

internal object KmNapiCppBridgeEmitter : KmCoreIntrinsicBridgeEmitter() {
    override fun emitRegular(out: StringBuilder, spec: KmBaseIntrinsicSpec.Regular) {
        var slot = 0
        val argExprs =
            spec.params.map { (_, t) ->
                val expr = t.napiExtractArg(slot)
                slot += t.napiSlots
                expr
            }
        val callExpr = "${spec.napiCallee}(${argExprs.joinToString(", ")})"
        val body =
            when (spec.ret) {
                KmScalarType.VOID -> "$callExpr;\n        return info.Env().Undefined();"
                KmScalarType.NEVER -> "(void)info;\n        $callExpr;\n        return info.Env().Undefined();"
                KmScalarType.I64 -> "return kmCoreReturnI64LoHi(info, $callExpr);"
                KmScalarType.U64 -> "return kmCoreReturnU64LoHi(info, $callExpr);"
                KmScalarType.I32 -> "return Napi::Number::New(info.Env(), $callExpr);"
                KmScalarType.U32, KmScalarType.U16, KmScalarType.U8 ->
                    "return Napi::Number::New(info.Env(), static_cast<int32_t>($callExpr));"
                KmScalarType.F32 -> "return Napi::Number::New(info.Env(), static_cast<double>($callExpr));"
                KmScalarType.F64 -> "return Napi::Number::New(info.Env(), $callExpr);"
            }
        out.appendLine("    coreObj.Set(\"${spec.name}\", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {")
        out.appendLine("        $body")
        out.appendLine("    }));")
    }

    override fun emitAtomicCxchg(out: StringBuilder, spec: KmBaseIntrinsicSpec.AtomicCxchg) {
        val ct = spec.valueType.cType
        when (spec.bits) {
            8, 16 -> {
                val exp = spec.valueType.napiExtractArg(2)
                val des = spec.valueType.napiExtractArg(3)
                out.appendLine(
                    """
    coreObj.Set("${spec.name}", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        uint8_t ok = 0;
        $ct prev = ${spec.name}(kmCoreNapiArgI64Pair(info, 0), $exp, $des, kmCoreNapiArgI32(info[4]), kmCoreNapiArgI32(info[5]), &ok);
        return Napi::Number::New(info.Env(), static_cast<int32_t>((static_cast<uint32_t>(ok) << ${spec.bits}) | static_cast<uint32_t>(prev)));
    }));
                    """.trimIndent(),
                )
            }
            32 ->
                out.appendLine(
                    """
    coreObj.Set("kmAtomicCxchgU32", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        uint8_t ok = 0;
        uint32_t prev = kmAtomicCxchgU32(kmCoreNapiArgI64Pair(info, 0), kmCoreNapiArgU32(info[2]), kmCoreNapiArgU32(info[3]), kmCoreNapiArgI32(info[4]), kmCoreNapiArgI32(info[5]), &ok);
        g_kmCoreOutScratch[0] = static_cast<int32_t>(prev);
        return Napi::Number::New(info.Env(), static_cast<int32_t>(ok));
    }));
                    """.trimIndent(),
                )
            else ->
                out.appendLine(
                    """
    coreObj.Set("kmAtomicCxchgU64", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        uint8_t ok = 0;
        uint64_t prev = kmAtomicCxchgU64(kmCoreNapiArgI64Pair(info, 0), kmCoreNapiArgU64Pair(info, 2), kmCoreNapiArgU64Pair(info, 4), kmCoreNapiArgI32(info[6]), kmCoreNapiArgI32(info[7]), &ok);
        kmCoreStoreU64Scratch(0, prev);
        return Napi::Number::New(info.Env(), static_cast<int32_t>(ok));
    }));
                    """.trimIndent(),
                )
        }
    }

    override fun emitAtomicCxchgU128(out: StringBuilder) {
        out.appendLine(
            """
    coreObj.Set("kmAtomicCxchgU128Pair", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        uint64_t outLo = 0, outHi = 0;
        uint8_t ok = kmCoreCpuAtomicCxchgU128(
            kmCoreNapiArgI64Pair(info, 0),
            kmCoreNapiArgU64Pair(info, 2),
            kmCoreNapiArgU64Pair(info, 4),
            kmCoreNapiArgU64Pair(info, 6),
            kmCoreNapiArgU64Pair(info, 8),
            &outLo,
            &outHi
        );
        kmCoreStoreU64Scratch(0, outLo);
        kmCoreStoreU64Scratch(2, outHi);
        return Napi::Number::New(info.Env(), static_cast<int32_t>(ok));
    }));
            """.trimIndent(),
        )
    }
}

internal class KmJsOrWasmBridgeEmitter(
    private val isWasmJs: Boolean,
) : KmCoreIntrinsicBridgeEmitter() {
    private val guard: String = if (isWasmJs) "kmCoreNapiAvailable" else "kmCoreNapiAddon != null"

    private fun outScratch(idx: Int): String =
        if (isWasmJs) "wasmKmOut$idx()" else "(kmCoreNapiAddon.kmOut$idx() as Int)"

    private fun invokeVoid(fn: String, args: String): String =
        if (isWasmJs) "wasmKm${fn.removePrefix("km")}($args)" else "kmCoreNapiAddon.$fn($args)"

    private fun invokeInt(fn: String, args: String): String =
        if (isWasmJs) "wasmKm${fn.removePrefix("km")}($args)" else "(kmCoreNapiAddon.$fn($args) as Int)"

    private fun invokeDouble(fn: String, args: String): String =
        if (isWasmJs) "wasmKm${fn.removePrefix("km")}($args)" else "(kmCoreNapiAddon.$fn($args) as Double)"

    fun renderWasmJsFunDeclarations(specs: List<KmBaseIntrinsicSpec> = KM_BASE_INTRINSIC_SPECS): String =
        buildString {
            for (idx in 0..7) {
                appendLine("@JsFun(\"() => globalThis.kmCoreNapiV2.kmOut$idx()\")")
                appendLine("private external fun wasmKmOut$idx(): Int")
            }
            for (spec in specs) {
                when (spec) {
                    is KmBaseIntrinsicSpec.Regular -> {
                        val extParams = spec.params.flatMap { (n, t) -> t.wasmExtParams(n) }
                        appendWasmJsFun(spec.name, extParams, spec.ret.wasmExtRetType)
                    }
                    is KmBaseIntrinsicSpec.AtomicCxchg -> {
                        val extParams =
                            buildList {
                                addAll(KmScalarType.I64.wasmExtParams("addr"))
                                addAll(spec.valueType.wasmExtParams("exp"))
                                addAll(spec.valueType.wasmExtParams("des"))
                                add("succ" to "Int")
                                add("fail" to "Int")
                            }
                        appendWasmJsFun(spec.name, extParams, "Int")
                    }
                    KmBaseIntrinsicSpec.AtomicCxchgU128 -> {
                        val extParams =
                            buildList {
                                addAll(KmScalarType.I64.wasmExtParams("addr"))
                                addAll(KmScalarType.U64.wasmExtParams("expLo"))
                                addAll(KmScalarType.U64.wasmExtParams("expHi"))
                                addAll(KmScalarType.U64.wasmExtParams("desLo"))
                                addAll(KmScalarType.U64.wasmExtParams("desHi"))
                            }
                        appendWasmJsFun("kmAtomicCxchgU128Pair", extParams, "Int")
                    }
                }
            }
        }.trimEnd()

    private fun StringBuilder.appendWasmJsFun(
        name: String,
        extParams: List<Pair<String, String>>,
        retType: String?,
    ) {
        val jsNames = extParams.joinToString(", ") { it.first }
        val ktParams = extParams.joinToString(", ") { (n, t) -> "$n: $t" }
        val retSuffix = if (retType == null) "" else ": $retType"
        appendLine("@JsFun(\"($jsNames) => globalThis.kmCoreNapiV2.$name($jsNames)\")")
        appendLine("private external fun wasmKm${name.removePrefix("km")}($ktParams)$retSuffix")
    }

    override fun emitRegular(out: StringBuilder, spec: KmBaseIntrinsicSpec.Regular) {
        val passArgs = spec.params.joinToString(", ") { (n, t) -> t.jsPassArgs(n) }
        val sig = "public actual fun ${spec.name}(${formatKotlinParamList(spec.params)})${formatKotlinReturnSuffix(spec.ret)}"
        when (spec.ret) {
            KmScalarType.NEVER -> {
                out.appendLine("$sig {")
                out.appendLine("    if ($guard) ${invokeVoid(spec.name, passArgs)}")
                out.appendLine("    error(\"Rust intrinsic abort triggered\")")
                out.appendLine("}")
            }
            KmScalarType.VOID -> {
                out.appendLine("$sig {")
                if (spec.arenaExpr == null) {
                    out.appendLine("    if ($guard) ${invokeVoid(spec.name, passArgs)}")
                } else {
                    out.appendLine("    if ($guard) ${invokeVoid(spec.name, passArgs)} else ${spec.arenaExpr}")
                }
                out.appendLine("}")
            }
            KmScalarType.I64, KmScalarType.U64 -> {
                val packFn = if (spec.ret == KmScalarType.I64) "packI64" else "packU64"
                out.appendLine("$sig =")
                out.appendLine("    if ($guard) {")
                out.appendLine("        val lo = ${invokeInt(spec.name, passArgs)}")
                out.appendLine("        $packFn(lo, ${outScratch(1)})")
                out.appendLine("    } else {")
                out.appendLine("        ${spec.arenaExpr}")
                out.appendLine("    }")
            }
            KmScalarType.I32 ->
                out.appendLine("$sig = if ($guard) ${invokeInt(spec.name, passArgs)} else ${spec.arenaExpr}")
            KmScalarType.U32 ->
                out.appendLine("$sig = if ($guard) ${invokeInt(spec.name, passArgs)}.toUInt() else ${spec.arenaExpr}")
            KmScalarType.U16 ->
                out.appendLine("$sig = if ($guard) ${invokeInt(spec.name, passArgs)}.toUShort() else ${spec.arenaExpr}")
            KmScalarType.U8 ->
                out.appendLine("$sig = if ($guard) ${invokeInt(spec.name, passArgs)}.toUByte() else ${spec.arenaExpr}")
            KmScalarType.F32 ->
                out.appendLine("$sig = if ($guard) ${invokeDouble(spec.name, passArgs)}.toFloat() else ${spec.arenaExpr}")
            KmScalarType.F64 ->
                out.appendLine("$sig = if ($guard) ${invokeDouble(spec.name, passArgs)} else ${spec.arenaExpr}")
        }
    }

    override fun emitAtomicCxchg(out: StringBuilder, spec: KmBaseIntrinsicSpec.AtomicCxchg) {
        val vt = spec.valueType.ktType
        val expArgs = spec.valueType.jsPassArgs("expected")
        val desArgs = spec.valueType.jsPassArgs("desired")
        val callArgs = "lo32(addr), hi32(addr), $expArgs, $desArgs, succ, fail"
        val arenaCall = "KmLinearMemoryArena.atomicCxchgU${spec.bits}(addr, expected, desired)"
        when (spec.bits) {
            8, 16 -> {
                val mask = if (spec.bits == 8) "0xFF" else "0xFFFF"
                out.appendLine(
                    """
public actual fun ${spec.name}(addr: Long, expected: $vt, desired: $vt, succ: Int, fail: Int): ${spec.outcomeName} =
    if ($guard) {
        val packed = ${invokeInt(spec.name, callArgs)}
        ${spec.outcomeName}((packed and $mask).to$vt(), (packed ushr ${spec.bits}) != 0)
    } else {
        $arenaCall
    }
                    """.trim(),
                )
            }
            32 ->
                out.appendLine(
                    """
public actual fun kmAtomicCxchgU32(addr: Long, expected: UInt, desired: UInt, succ: Int, fail: Int): AtomicCxchgU32Outcome =
    if ($guard) {
        val ok = ${invokeInt("kmAtomicCxchgU32", callArgs)} != 0
        AtomicCxchgU32Outcome(${outScratch(0)}.toUInt(), ok)
    } else {
        $arenaCall
    }
                    """.trim(),
                )
            else ->
                out.appendLine(
                    """
public actual fun kmAtomicCxchgU64(addr: Long, expected: ULong, desired: ULong, succ: Int, fail: Int): AtomicCxchgU64Outcome =
    if ($guard) {
        val ok = ${invokeInt("kmAtomicCxchgU64", callArgs)} != 0
        AtomicCxchgU64Outcome(packU64(${outScratch(0)}, ${outScratch(1)}), ok)
    } else {
        $arenaCall
    }
                    """.trim(),
                )
        }
    }

    override fun emitAtomicCxchgU128(out: StringBuilder) {
        val callArgs =
            "lo32(addr), hi32(addr), uLo32(expLo), uHi32(expLo), uLo32(expHi), uHi32(expHi), uLo32(desLo), uHi32(desLo), uLo32(desHi), uHi32(desHi)"
        out.appendLine(
            """
public actual fun kmAtomicCxchgU128Pair(
    addr: Long,
    expLo: ULong,
    expHi: ULong,
    desLo: ULong,
    desHi: ULong,
): AtomicCxchgU128Outcome =
    if ($guard) {
        val ok = ${invokeInt("kmAtomicCxchgU128Pair", callArgs)} != 0
        AtomicCxchgU128Outcome(
            packU64(${outScratch(0)}, ${outScratch(1)}),
            packU64(${outScratch(2)}, ${outScratch(3)}),
            ok,
        )
    } else {
        KmLinearMemoryArena.atomicCxchgU128Pair(addr, expLo, expHi, desLo, desHi)
    }
            """.trim(),
        )
    }
}

internal object KmWasmWasiBridgeEmitter : KmCoreIntrinsicBridgeEmitter() {
    override fun emitRegular(out: StringBuilder, spec: KmBaseIntrinsicSpec.Regular) {
        when (spec.ret) {
            KmScalarType.NEVER ->
                out.appendLine("public actual fun ${spec.name}(${formatKotlinParamList(spec.params)}): Nothing = error(\"Rust intrinsic abort triggered\")")
            KmScalarType.VOID ->
                if (spec.arenaExpr == null) {
                    out.appendLine("public actual fun ${spec.name}(${formatKotlinParamList(spec.params)}) {}")
                } else {
                    out.appendLine("public actual fun ${spec.name}(${formatKotlinParamList(spec.params)}): Unit = ${spec.arenaExpr}")
                }
            else ->
                out.appendLine("public actual fun ${spec.name}(${formatKotlinParamList(spec.params)}): ${spec.ret.ktType} = ${spec.arenaExpr}")
        }
    }

    override fun emitAtomicCxchg(out: StringBuilder, spec: KmBaseIntrinsicSpec.AtomicCxchg) {
        val vt = spec.valueType.ktType
        out.appendLine(
            "public actual fun ${spec.name}(addr: Long, expected: $vt, desired: $vt, succ: Int, fail: Int): ${spec.outcomeName} = KmLinearMemoryArena.atomicCxchgU${spec.bits}(addr, expected, desired)",
        )
    }

    override fun emitAtomicCxchgU128(out: StringBuilder) {
        out.appendLine(
            "public actual fun kmAtomicCxchgU128Pair(addr: Long, expLo: ULong, expHi: ULong, desLo: ULong, desHi: ULong): AtomicCxchgU128Outcome = KmLinearMemoryArena.atomicCxchgU128Pair(addr, expLo, expHi, desLo, desHi)",
        )
    }
}
