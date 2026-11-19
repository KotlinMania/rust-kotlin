package io.github.kotlinmania.rustkotlin

/**
 * DRY specification table and 6-layer code generator for width-exact bit
 * intrinsics, overflowing/saturating integer arithmetic, C-backed 128-bit
 * integer operations, libm float intrinsics, and float-to-unsigned unchecked casts.
 *
 * All six FFI layers (`km_core_intrinsics_inline.h`, `expect`, Native `cinterop`,
 * JNI C / JVM / Android, `KmLinearMemoryArena`, N-API C++ / JS / WasmJS / WasmWasi)
 * are generated from the same canonical descriptors so signatures and ABI layouts
 * stay synchronized by construction.
 */
internal object CoreIntrinsicMathAndBitSpecs {
    internal enum class IntWidthKind(
        val widthTag: String,
        val bits: Int,
        val signed: Boolean,
        val cType: String,
        val ktType: String,
        val jniType: String,
        val jniField: String,
        val ktMaskSuffix: String,
        val u32WrapSuffix: String,
    ) {
        U8("U8", 8, false, "uint8_t", "UByte", "jbyte", "Byte", "0xFF", ".toUByte()"),
        U16("U16", 16, false, "uint16_t", "UShort", "jshort", "Short", "0xFFFF", ".toUShort()"),
        U32("U32", 32, false, "uint32_t", "UInt", "jint", "Int", "", ".toUInt()"),
        U64("U64", 64, false, "uint64_t", "ULong", "jlong", "Long", "", ".toULong()"),
        U128("U128", 128, false, "uint64_t", "U128ValueOutcome", "jlong", "Long", "", ""),
        I8("I8", 8, true, "int8_t", "Byte", "jbyte", "Byte", "0xFF", ".toByte()"),
        I16("I16", 16, true, "int16_t", "Short", "jshort", "Short", "0xFFFF", ".toShort()"),
        I32("I32", 32, true, "int32_t", "Int", "jint", "Int", "", ""),
        I64("I64", 64, true, "int64_t", "Long", "jlong", "Long", "", ""),
        I128("I128", 128, true, "int64_t", "I128ValueOutcome", "jlong", "Long", "", ""),
    }

    internal enum class OverflowOpKind(
        val stem: String,
        val opSymbol: String,
        val builtinName: String,
    ) {
        ADD("Add", "+", "__builtin_add_overflow"),
        SUB("Sub", "-", "__builtin_sub_overflow"),
        MUL("Mul", "*", "__builtin_mul_overflow"),
    }

    internal enum class UnaryBitKind(
        val expectName: String,
        val width: Int,
        val returns128: Boolean,
        val returnsUInt: Boolean,
    ) {
        CTPOP_U8("kmCtpopU8", 8, false, true),
        CTPOP_U16("kmCtpopU16", 16, false, true),
        CTPOP_U32("kmCtpopU32", 32, false, true),
        CTPOP_U128("kmCtpopU128", 128, false, true),
        CTLZ_U8("kmCtlzU8", 8, false, true),
        CTLZ_U16("kmCtlzU16", 16, false, true),
        CTLZ_U32("kmCtlzU32", 32, false, true),
        CTLZ_U128("kmCtlzU128", 128, false, true),
        CTTZ_U8("kmCttzU8", 8, false, true),
        CTTZ_U16("kmCttzU16", 16, false, true),
        CTTZ_U32("kmCttzU32", 32, false, true),
        CTTZ_U128("kmCttzU128", 128, false, true),
        BSWAP_U128("kmBswapU128", 128, true, false),
        BITREVERSE_U8("kmBitreverseU8", 8, false, false),
        BITREVERSE_U16("kmBitreverseU16", 16, false, false),
        BITREVERSE_U32("kmBitreverseU32", 32, false, false),
        BITREVERSE_U128("kmBitreverseU128", 128, true, false),
    }

    internal data class RotateBitSpec(
        val expectName: String,
        val isLeft: Boolean,
        val width: Int,
    )

    internal data class FloatMathSpec(
        val expectName: String,
        val cCallee: String,
        val isF64: Boolean,
        val arity: Int,
        val secondIsInt: Boolean = false,
    )

    private val ALL_INT_WIDTHS: List<IntWidthKind> = IntWidthKind.entries
    private val BIT_ROTATE_WIDTHS: List<Int> = listOf(8, 16, 32, 64, 128)

    private val ROTATE_SPECS: List<RotateBitSpec> =
        buildList {
            for (isLeft in listOf(true, false)) {
                val dir = if (isLeft) "RotateLeft" else "RotateRight"
                for (w in BIT_ROTATE_WIDTHS) {
                    add(RotateBitSpec("km${dir}U$w", isLeft, w))
                }
            }
        }

    private val FLOAT_MATH_SPECS: List<FloatMathSpec> =
        listOf(
            FloatMathSpec("kmSinf32", "sinf", isF64 = false, arity = 1),
            FloatMathSpec("kmSinf64", "sin", isF64 = true, arity = 1),
            FloatMathSpec("kmCosf32", "cosf", isF64 = false, arity = 1),
            FloatMathSpec("kmCosf64", "cos", isF64 = true, arity = 1),
            FloatMathSpec("kmExpf32", "expf", isF64 = false, arity = 1),
            FloatMathSpec("kmExpf64", "exp", isF64 = true, arity = 1),
            FloatMathSpec("kmExp2f32", "exp2f", isF64 = false, arity = 1),
            FloatMathSpec("kmExp2f64", "exp2", isF64 = true, arity = 1),
            FloatMathSpec("kmLogf32", "logf", isF64 = false, arity = 1),
            FloatMathSpec("kmLogf64", "log", isF64 = true, arity = 1),
            FloatMathSpec("kmLog2f32", "log2f", isF64 = false, arity = 1),
            FloatMathSpec("kmLog2f64", "log2", isF64 = true, arity = 1),
            FloatMathSpec("kmLog10f32", "log10f", isF64 = false, arity = 1),
            FloatMathSpec("kmLog10f64", "log10", isF64 = true, arity = 1),
            FloatMathSpec("kmTruncf32", "truncf", isF64 = false, arity = 1),
            FloatMathSpec("kmTruncf64", "trunc", isF64 = true, arity = 1),
            FloatMathSpec("kmRoundf32", "roundf", isF64 = false, arity = 1),
            FloatMathSpec("kmRoundf64", "round", isF64 = true, arity = 1),
            FloatMathSpec("kmRoundTiesEvenf32", "nearbyintf", isF64 = false, arity = 1),
            FloatMathSpec("kmRoundTiesEvenf64", "nearbyint", isF64 = true, arity = 1),
            FloatMathSpec("kmCeilf32", "ceilf", isF64 = false, arity = 1),
            FloatMathSpec("kmCeilf64", "ceil", isF64 = true, arity = 1),
            FloatMathSpec("kmFloorf32", "floorf", isF64 = false, arity = 1),
            FloatMathSpec("kmFloorf64", "floor", isF64 = true, arity = 1),
            FloatMathSpec("kmFabsf32", "fabsf", isF64 = false, arity = 1),
            FloatMathSpec("kmFabsf64", "fabs", isF64 = true, arity = 1),
            FloatMathSpec("kmPowf32", "powf", isF64 = false, arity = 2),
            FloatMathSpec("kmPowf64", "pow", isF64 = true, arity = 2),
            FloatMathSpec("kmPowif32", "kmPowif32", isF64 = false, arity = 2, secondIsInt = true),
            FloatMathSpec("kmPowif64", "kmPowif64", isF64 = true, arity = 2, secondIsInt = true),
            FloatMathSpec("kmCopysignf32", "copysignf", isF64 = false, arity = 2),
            FloatMathSpec("kmCopysignf64", "copysign", isF64 = true, arity = 2),
            FloatMathSpec("kmMinnumf32", "fminf", isF64 = false, arity = 2),
            FloatMathSpec("kmMinnumf64", "fmin", isF64 = true, arity = 2),
            FloatMathSpec("kmMaxnumf32", "fmaxf", isF64 = false, arity = 2),
            FloatMathSpec("kmMaxnumf64", "fmax", isF64 = true, arity = 2),
        )

    private val U128_VALUE_BINOPS: List<Pair<String, String>> =
        listOf(
            "kmU128Add" to "add",
            "kmU128Sub" to "sub",
            "kmU128Mul" to "mul",
        )

    private val U128_DIVREM_OPS: List<Pair<String, String>> =
        listOf(
            "kmU128DivRem" to "U128DivRemOutcome",
            "kmI128DivRem" to "I128DivRemOutcome",
        )

    private val U128_SHIFT_OPS: List<Triple<String, String, String>> =
        listOf(
            Triple("kmU128Shl", "U128ValueOutcome", "shl"),
            Triple("kmU128Shr", "U128ValueOutcome", "ushr"),
            Triple("kmI128Shr", "I128ValueOutcome", "ashr"),
        )

    private fun IntWidthKind.cUnsignedType(): String =
        when (bits) {
            8 -> "uint8_t"
            16 -> "uint16_t"
            32 -> "uint32_t"
            64 -> "uint64_t"
            else -> "uint64_t"
        }

    private fun smallWidthKtReturnFromU32(width: Int): String =
        when (width) {
            8 -> "UByte"
            16 -> "UShort"
            32 -> "UInt"
            64 -> "ULong"
            else -> "U128ValueOutcome"
        }

    private fun smallWidthCType(width: Int): String =
        when (width) {
            8 -> "uint8_t"
            16 -> "uint16_t"
            32 -> "uint32_t"
            64 -> "uint64_t"
            else -> "uint64_t"
        }

    private fun smallWidthJniType(width: Int): String =
        when (width) {
            8 -> "jbyte"
            16 -> "jshort"
            32 -> "jint"
            else -> "jlong"
        }

    private fun smallWidthJvmType(width: Int): String =
        when (width) {
            8 -> "Byte"
            16 -> "Short"
            32 -> "Int"
            else -> "Long"
        }

    public fun renderOutcomeDataClasses(): String =
        buildString {
            for (w in ALL_INT_WIDTHS) {
                if (w.bits < 128) {
                    appendLine(
                        """
public data class Overflow${w.widthTag}Outcome(
    public val value: ${w.ktType},
    public val overflowed: Boolean,
)
                        """.trim(),
                    )
                    appendLine()
                } else {
                    appendLine(
                        """
public data class Overflow${w.widthTag}Outcome(
    public val lo: ULong,
    public val hi: ULong,
    public val overflowed: Boolean,
)
                        """.trim(),
                    )
                    appendLine()
                }
            }
            appendLine(
                """
public data class U128ValueOutcome(
    public val lo: ULong,
    public val hi: ULong,
)

public data class I128ValueOutcome(
    public val lo: ULong,
    public val hi: ULong,
)

public data class U128DivRemOutcome(
    public val qLo: ULong,
    public val qHi: ULong,
    public val rLo: ULong,
    public val rHi: ULong,
)

public data class I128DivRemOutcome(
    public val qLo: ULong,
    public val qHi: ULong,
    public val rLo: ULong,
    public val rHi: ULong,
)
                """.trim(),
            )
        }

    public fun renderInlineCHelpersAndFunctions(): String =
        buildString {
            appendLine(renderPortable128AndOverflowHelpersC())
            appendLine("/* Width-exact bit intrinsics */")
            for (spec in UnaryBitKind.entries) {
                appendLine(renderUnaryBitC(spec))
            }
            for (spec in ROTATE_SPECS) {
                appendLine(renderRotateBitC(spec))
            }
            appendLine("/* Overflowing and saturating integer arithmetic intrinsics */")
            for (op in OverflowOpKind.entries) {
                for (w in ALL_INT_WIDTHS) {
                    appendLine(renderOverflowArithC(op, w))
                }
            }
            for (op in listOf(OverflowOpKind.ADD, OverflowOpKind.SUB)) {
                for (w in ALL_INT_WIDTHS) {
                    appendLine(renderSaturatingArithC(op, w))
                }
            }
            appendLine("/* C-backed 128-bit arithmetic and float math intrinsics */")
            appendLine(renderU128AndFloatC())
        }

    private fun renderPortable128AndOverflowHelpersC(): String =
        """
/* Portable helpers for overflowing 32/64/128-bit and C-backed 128-bit arithmetic */
KM_ALWAYS_INLINE uint64_t kmPackOverflow32(uint32_t valueBits, uint8_t overflowed) {
    return (uint64_t)valueBits | ((uint64_t)(overflowed != 0u ? 1u : 0u) << 32);
}

KM_ALWAYS_INLINE uint64_t kmAddU64Carry(uint64_t a, uint64_t b, uint64_t* outSum) {
    uint64_t s = a + b;
    *outSum = s;
    return s < a ? 1ULL : 0ULL;
}

KM_ALWAYS_INLINE void kmMulU64Wide(uint64_t a, uint64_t b, uint64_t* outLo, uint64_t* outHi) {
#if defined(__SIZEOF_INT128__)
    __uint128_t prod = (__uint128_t)a * (__uint128_t)b;
    *outLo = (uint64_t)prod;
    *outHi = (uint64_t)(prod >> 64);
#else
    uint64_t aLo = a & 0xFFFFFFFFULL;
    uint64_t aHi = a >> 32;
    uint64_t bLo = b & 0xFFFFFFFFULL;
    uint64_t bHi = b >> 32;
    uint64_t p00 = aLo * bLo;
    uint64_t p01 = aLo * bHi;
    uint64_t p10 = aHi * bLo;
    uint64_t p11 = aHi * bHi;
    uint64_t mid = (p00 >> 32) + (p01 & 0xFFFFFFFFULL) + (p10 & 0xFFFFFFFFULL);
    *outLo = (p00 & 0xFFFFFFFFULL) | ((mid & 0xFFFFFFFFULL) << 32);
    *outHi = p11 + (p01 >> 32) + (p10 >> 32) + (mid >> 32);
#endif
}

KM_ALWAYS_INLINE void kmU128NegateRaw(uint64_t lo, uint64_t hi, uint64_t* outLo, uint64_t* outHi) {
    uint64_t invLo = ~lo;
    uint64_t invHi = ~hi;
    uint64_t resLo = invLo + 1ULL;
    uint64_t carry = (resLo == 0ULL) ? 1ULL : 0ULL;
    *outLo = resLo;
    *outHi = invHi + carry;
}

KM_ALWAYS_INLINE void kmU128DivRemRaw(
    uint64_t nLo,
    uint64_t nHi,
    uint64_t dLo,
    uint64_t dHi,
    uint64_t* outQLo,
    uint64_t* outQHi,
    uint64_t* outRLo,
    uint64_t* outRHi
) {
    if (dLo == 0ULL && dHi == 0ULL) {
        *outQLo = 0ULL;
        *outQHi = 0ULL;
        *outRLo = 0ULL;
        *outRHi = 0ULL;
        return;
    }
#if defined(__SIZEOF_INT128__)
    __uint128_t num = ((__uint128_t)nHi << 64) | (__uint128_t)nLo;
    __uint128_t den = ((__uint128_t)dHi << 64) | (__uint128_t)dLo;
    __uint128_t q = num / den;
    __uint128_t r = num % den;
    *outQLo = (uint64_t)q;
    *outQHi = (uint64_t)(q >> 64);
    *outRLo = (uint64_t)r;
    *outRHi = (uint64_t)(r >> 64);
#else
    uint64_t qLo = 0ULL, qHi = 0ULL, rLo = 0ULL, rHi = 0ULL;
    for (int bit = 127; bit >= 0; bit--) {
        uint64_t rCarry = rLo >> 63;
        rHi = (rHi << 1) | rCarry;
        uint64_t nBit = (bit >= 64) ? ((nHi >> (bit - 64)) & 1ULL) : ((nLo >> bit) & 1ULL);
        rLo = (rLo << 1) | nBit;
        if (rHi > dHi || (rHi == dHi && rLo >= dLo)) {
            uint64_t borrow = (rLo < dLo) ? 1ULL : 0ULL;
            rLo -= dLo;
            rHi -= (dHi + borrow);
            if (bit >= 64) {
                qHi |= (1ULL << (bit - 64));
            } else {
                qLo |= (1ULL << bit);
            }
        }
    }
    *outQLo = qLo;
    *outQHi = qHi;
    *outRLo = rLo;
    *outRHi = rHi;
#endif
}

KM_ALWAYS_INLINE void kmI128DivRemRaw(
    uint64_t nLo,
    uint64_t nHi,
    uint64_t dLo,
    uint64_t dHi,
    uint64_t* outQLo,
    uint64_t* outQHi,
    uint64_t* outRLo,
    uint64_t* outRHi
) {
    if (dLo == 0ULL && dHi == 0ULL) {
        *outQLo = 0ULL;
        *outQHi = 0ULL;
        *outRLo = 0ULL;
        *outRHi = 0ULL;
        return;
    }
    if (nHi == 0x8000000000000000ULL && nLo == 0ULL && dHi == 0xFFFFFFFFFFFFFFFFULL && dLo == 0xFFFFFFFFFFFFFFFFULL) {
        *outQLo = 0ULL;
        *outQHi = 0x8000000000000000ULL;
        *outRLo = 0ULL;
        *outRHi = 0ULL;
        return;
    }
    int nNeg = (nHi >> 63) != 0ULL;
    int dNeg = (dHi >> 63) != 0ULL;
    uint64_t aLo = nLo, aHi = nHi;
    uint64_t bLo = dLo, bHi = dHi;
    if (nNeg) kmU128NegateRaw(nLo, nHi, &aLo, &aHi);
    if (dNeg) kmU128NegateRaw(dLo, dHi, &bLo, &bHi);
    uint64_t uqLo = 0ULL, uqHi = 0ULL, urLo = 0ULL, urHi = 0ULL;
    kmU128DivRemRaw(aLo, aHi, bLo, bHi, &uqLo, &uqHi, &urLo, &urHi);
    if (nNeg != dNeg) {
        kmU128NegateRaw(uqLo, uqHi, outQLo, outQHi);
    } else {
        *outQLo = uqLo;
        *outQHi = uqHi;
    }
    if (nNeg) {
        kmU128NegateRaw(urLo, urHi, outRLo, outRHi);
    } else {
        *outRLo = urLo;
        *outRHi = urHi;
    }
}
        """.trim()

    private fun renderUnaryBitC(spec: UnaryBitKind): String =
        when (spec) {
            UnaryBitKind.CTPOP_U8 ->
                """
KM_ALWAYS_INLINE uint32_t kmCtpopU8(uint8_t v) {
    return (uint32_t)kmCtpopU64((uint64_t)v);
}
                """.trim()
            UnaryBitKind.CTPOP_U16 ->
                """
KM_ALWAYS_INLINE uint32_t kmCtpopU16(uint16_t v) {
    return (uint32_t)kmCtpopU64((uint64_t)v);
}
                """.trim()
            UnaryBitKind.CTPOP_U32 ->
                """
KM_ALWAYS_INLINE uint32_t kmCtpopU32(uint32_t v) {
    return (uint32_t)kmCtpopU64((uint64_t)v);
}
                """.trim()
            UnaryBitKind.CTPOP_U128 ->
                """
KM_ALWAYS_INLINE uint32_t kmCtpopU128(uint64_t lo, uint64_t hi) {
    return kmCtpopU64(lo) + kmCtpopU64(hi);
}
                """.trim()
            UnaryBitKind.CTLZ_U8 ->
                """
KM_ALWAYS_INLINE uint32_t kmCtlzU8(uint8_t v) {
    if (v == 0u) return 8u;
#if defined(__clang__) || defined(__GNUC__)
    return (uint32_t)__builtin_clz((unsigned int)v) - 24u;
#else
    return kmCtlzU64((uint64_t)v) - 56u;
#endif
}
                """.trim()
            UnaryBitKind.CTLZ_U16 ->
                """
KM_ALWAYS_INLINE uint32_t kmCtlzU16(uint16_t v) {
    if (v == 0u) return 16u;
#if defined(__clang__) || defined(__GNUC__)
    return (uint32_t)__builtin_clz((unsigned int)v) - 16u;
#else
    return kmCtlzU64((uint64_t)v) - 48u;
#endif
}
                """.trim()
            UnaryBitKind.CTLZ_U32 ->
                """
KM_ALWAYS_INLINE uint32_t kmCtlzU32(uint32_t v) {
    if (v == 0u) return 32u;
#if defined(__clang__) || defined(__GNUC__)
    return (uint32_t)__builtin_clz(v);
#else
    return kmCtlzU64((uint64_t)v) - 32u;
#endif
}
                """.trim()
            UnaryBitKind.CTLZ_U128 ->
                """
KM_ALWAYS_INLINE uint32_t kmCtlzU128(uint64_t lo, uint64_t hi) {
    return hi != 0ULL ? kmCtlzU64(hi) : (64u + kmCtlzU64(lo));
}
                """.trim()
            UnaryBitKind.CTTZ_U8 ->
                """
KM_ALWAYS_INLINE uint32_t kmCttzU8(uint8_t v) {
    if (v == 0u) return 8u;
#if defined(__clang__) || defined(__GNUC__)
    return (uint32_t)__builtin_ctz((unsigned int)v);
#else
    return kmCttzU64((uint64_t)v);
#endif
}
                """.trim()
            UnaryBitKind.CTTZ_U16 ->
                """
KM_ALWAYS_INLINE uint32_t kmCttzU16(uint16_t v) {
    if (v == 0u) return 16u;
#if defined(__clang__) || defined(__GNUC__)
    return (uint32_t)__builtin_ctz((unsigned int)v);
#else
    return kmCttzU64((uint64_t)v);
#endif
}
                """.trim()
            UnaryBitKind.CTTZ_U32 ->
                """
KM_ALWAYS_INLINE uint32_t kmCttzU32(uint32_t v) {
    if (v == 0u) return 32u;
#if defined(__clang__) || defined(__GNUC__)
    return (uint32_t)__builtin_ctz(v);
#else
    return kmCttzU64((uint64_t)v);
#endif
}
                """.trim()
            UnaryBitKind.CTTZ_U128 ->
                """
KM_ALWAYS_INLINE uint32_t kmCttzU128(uint64_t lo, uint64_t hi) {
    return lo != 0ULL ? kmCttzU64(lo) : (64u + kmCttzU64(hi));
}
                """.trim()
            UnaryBitKind.BSWAP_U128 ->
                """
KM_ALWAYS_INLINE void kmBswapU128Out(uint64_t lo, uint64_t hi, uint64_t* outLo, uint64_t* outHi) {
    *outLo = kmBswapU64(hi);
    *outHi = kmBswapU64(lo);
}
                """.trim()
            UnaryBitKind.BITREVERSE_U8 ->
                """
KM_ALWAYS_INLINE uint8_t kmBitreverseU8(uint8_t v) {
#if defined(__clang__)
    return __builtin_bitreverse8(v);
#else
    return (uint8_t)(kmBitreverseU64((uint64_t)v) >> 56);
#endif
}
                """.trim()
            UnaryBitKind.BITREVERSE_U16 ->
                """
KM_ALWAYS_INLINE uint16_t kmBitreverseU16(uint16_t v) {
#if defined(__clang__)
    return __builtin_bitreverse16(v);
#else
    return (uint16_t)(kmBitreverseU64((uint64_t)v) >> 48);
#endif
}
                """.trim()
            UnaryBitKind.BITREVERSE_U32 ->
                """
KM_ALWAYS_INLINE uint32_t kmBitreverseU32(uint32_t v) {
#if defined(__clang__)
    return __builtin_bitreverse32(v);
#else
    return (uint32_t)(kmBitreverseU64((uint64_t)v) >> 32);
#endif
}
                """.trim()
            UnaryBitKind.BITREVERSE_U128 ->
                """
KM_ALWAYS_INLINE void kmBitreverseU128Out(uint64_t lo, uint64_t hi, uint64_t* outLo, uint64_t* outHi) {
    *outLo = kmBitreverseU64(hi);
    *outHi = kmBitreverseU64(lo);
}
                """.trim()
        }

    private fun renderRotateBitC(spec: RotateBitSpec): String {
        val fn = spec.expectName
        if (spec.width == 128) {
            return if (spec.isLeft) {
                """
KM_ALWAYS_INLINE void ${fn}Out(uint64_t lo, uint64_t hi, uint32_t n, uint64_t* outLo, uint64_t* outHi) {
    uint32_t s = n & 127u;
    if (s == 0u) { *outLo = lo; *outHi = hi; return; }
    if (s >= 64u) {
        uint32_t k = s - 64u;
        if (k == 0u) { *outLo = hi; *outHi = lo; }
        else { *outLo = (hi << k) | (lo >> (64u - k)); *outHi = (lo << k) | (hi >> (64u - k)); }
        return;
    }
    *outLo = (lo << s) | (hi >> (64u - s));
    *outHi = (hi << s) | (lo >> (64u - s));
}
                """.trim()
            } else {
                """
KM_ALWAYS_INLINE void ${fn}Out(uint64_t lo, uint64_t hi, uint32_t n, uint64_t* outLo, uint64_t* outHi) {
    uint32_t leftShift = (128u - (n & 127u)) & 127u;
    kmRotateLeftU128Out(lo, hi, leftShift, outLo, outHi);
}
                """.trim()
            }
        }
        val cType = smallWidthCType(spec.width)
        val mask = spec.width - 1
        val widen = if (spec.width < 32) "uint32_t" else cType
        val mainExpr =
            if (spec.isLeft) {
                "($widen)((($widen)v << s) | (($widen)v >> (${spec.width}u - s)))"
            } else {
                "($widen)((($widen)v >> s) | (($widen)v << (${spec.width}u - s)))"
            }
        return """
KM_ALWAYS_INLINE $cType $fn($cType v, uint32_t n) {
    uint32_t s = n & ${mask}u;
    if (s == 0u) return v;
    return ($cType)$mainExpr;
}
        """.trim()
    }

    private fun renderOverflowArithC(op: OverflowOpKind, w: IntWidthKind): String {
        val fn = "km${op.stem}WithOverflow${w.widthTag}"
        if (w.bits < 64) {
            val ct = w.cType
            val uct = w.cUnsignedType()
            val portableBody =
                when (op) {
                    OverflowOpKind.ADD ->
                        if (!w.signed) {
                            """
                            $uct res = ($uct)((unsigned int)a + (unsigned int)b);
                            uint8_t ovf = res < a ? 1u : 0u;
                            return kmPackOverflow32((uint32_t)res, ovf);
                            """.trimIndent()
                        } else {
                            """
                            uint32_t ua = (uint32_t)($uct)a;
                            uint32_t ub = (uint32_t)($uct)b;
                            uint32_t ures = ua + ub;
                            $uct truncRes = ($uct)ures;
                            uint32_t rBits = (uint32_t)truncRes;
                            uint32_t ovf = (((~(ua ^ ub)) & (ua ^ rBits)) >> ${w.bits - 1}) & 1u;
                            return kmPackOverflow32(rBits, (uint8_t)ovf);
                            """.trimIndent()
                        }
                    OverflowOpKind.SUB ->
                        if (!w.signed) {
                            """
                            $uct res = ($uct)((unsigned int)a - (unsigned int)b);
                            uint8_t ovf = a < b ? 1u : 0u;
                            return kmPackOverflow32((uint32_t)res, ovf);
                            """.trimIndent()
                        } else {
                            """
                            uint32_t ua = (uint32_t)($uct)a;
                            uint32_t ub = (uint32_t)($uct)b;
                            uint32_t ures = ua - ub;
                            $uct truncRes = ($uct)ures;
                            uint32_t rBits = (uint32_t)truncRes;
                            uint32_t ovf = (((ua ^ ub) & (ua ^ rBits)) >> ${w.bits - 1}) & 1u;
                            return kmPackOverflow32(rBits, (uint8_t)ovf);
                            """.trimIndent()
                        }
                    OverflowOpKind.MUL -> {
                        val minExpr =
                            when (w.bits) {
                                8 -> "INT8_MIN"
                                16 -> "INT16_MIN"
                                else -> "INT32_MIN"
                            }
                        val maxExpr =
                            when (w.bits) {
                                8 -> "INT8_MAX"
                                16 -> "INT16_MAX"
                                else -> "INT32_MAX"
                            }
                        val uMaxExpr =
                            when (w.bits) {
                                8 -> "UINT8_MAX"
                                16 -> "UINT16_MAX"
                                else -> "UINT32_MAX"
                            }
                        if (!w.signed) {
                            """
                            uint64_t wide = (uint64_t)a * (uint64_t)b;
                            $uct res = ($uct)wide;
                            uint8_t ovf = wide > (uint64_t)$uMaxExpr ? 1u : 0u;
                            return kmPackOverflow32((uint32_t)res, ovf);
                            """.trimIndent()
                        } else {
                            """
                            int64_t wide = (int64_t)a * (int64_t)b;
                            $uct res = ($uct)(uint64_t)wide;
                            uint8_t ovf = (wide < (int64_t)$minExpr || wide > (int64_t)$maxExpr) ? 1u : 0u;
                            return kmPackOverflow32((uint32_t)res, ovf);
                            """.trimIndent()
                        }
                    }
                }
            return """
KM_ALWAYS_INLINE uint64_t ${fn}Packed($ct a, $ct b) {
#if defined(__clang__) || defined(__GNUC__)
    $ct res = 0;
    uint8_t ovf = ${op.builtinName}(a, b, &res) ? 1u : 0u;
    return kmPackOverflow32((uint32_t)($uct)res, ovf);
#else
$portableBody
#endif
}
            """.trim()
        }
        if (w.bits == 64) {
            val ct = w.cType
            val portable64 =
                when (op) {
                    OverflowOpKind.ADD ->
                        if (!w.signed) {
                            "uint64_t res = a + b; *outVal = res; return res < a ? 1u : 0u;"
                        } else {
                            "uint64_t ua = (uint64_t)a; uint64_t ub = (uint64_t)b; uint64_t ur = ua + ub; *outVal = (int64_t)ur; return (uint8_t)((((~(ua ^ ub)) & (ua ^ ur)) >> 63) & 1ULL);"
                        }
                    OverflowOpKind.SUB ->
                        if (!w.signed) {
                            "uint64_t res = a - b; *outVal = res; return a < b ? 1u : 0u;"
                        } else {
                            "uint64_t ua = (uint64_t)a; uint64_t ub = (uint64_t)b; uint64_t ur = ua - ub; *outVal = (int64_t)ur; return (uint8_t)((((ua ^ ub) & (ua ^ ur)) >> 63) & 1ULL);"
                        }
                    OverflowOpKind.MUL ->
                        if (!w.signed) {
                            "uint64_t lo = 0ULL, hi = 0ULL; kmMulU64Wide(a, b, &lo, &hi); *outVal = lo; return hi != 0ULL ? 1u : 0u;"
                        } else {
                            """
                            uint64_t ua = (uint64_t)a;
                            uint64_t ub = (uint64_t)b;
                            int sA = a < 0;
                            int sB = b < 0;
                            uint64_t magA = sA ? (0ULL - ua) : ua;
                            uint64_t magB = sB ? (0ULL - ub) : ub;
                            uint64_t lo = 0ULL, hi = 0ULL;
                            kmMulU64Wide(magA, magB, &lo, &hi);
                            int neg = sA != sB;
                            uint64_t ur = neg ? (0ULL - lo) : lo;
                            *outVal = (int64_t)ur;
                            if (hi != 0ULL) return 1u;
                            if (neg) return lo > 0x8000000000000000ULL ? 1u : 0u;
                            return (lo >> 63) != 0ULL ? 1u : 0u;
                            """.trimIndent()
                        }
                }
            return """
KM_ALWAYS_INLINE uint8_t ${fn}Out($ct a, $ct b, $ct* outVal) {
#if defined(__clang__) || defined(__GNUC__)
    return ${op.builtinName}(a, b, outVal) ? 1u : 0u;
#else
    $portable64
#endif
}
            """.trim()
        }
        // 128-bit
        return when (op) {
            OverflowOpKind.ADD ->
                if (!w.signed) {
                    """
KM_ALWAYS_INLINE uint8_t ${fn}Out(uint64_t aLo, uint64_t aHi, uint64_t bLo, uint64_t bHi, uint64_t* outLo, uint64_t* outHi) {
    uint64_t sLo = 0ULL, sHi = 0ULL;
    uint64_t cLo = kmAddU64Carry(aLo, bLo, &sLo);
    uint64_t cHi1 = kmAddU64Carry(aHi, bHi, &sHi);
    uint64_t sHi2 = 0ULL;
    uint64_t cHi2 = kmAddU64Carry(sHi, cLo, &sHi2);
    *outLo = sLo;
    *outHi = sHi2;
    return (cHi1 | cHi2) != 0ULL ? 1u : 0u;
}
                    """.trim()
                } else {
                    """
KM_ALWAYS_INLINE uint8_t ${fn}Out(uint64_t aLo, uint64_t aHi, uint64_t bLo, uint64_t bHi, uint64_t* outLo, uint64_t* outHi) {
    uint64_t rLo = 0ULL, rHi = 0ULL;
    kmAddWithOverflowU128Out(aLo, aHi, bLo, bHi, &rLo, &rHi);
    *outLo = rLo;
    *outHi = rHi;
    return (uint8_t)((((~(aHi ^ bHi)) & (aHi ^ rHi)) >> 63) & 1ULL);
}
                    """.trim()
                }
            OverflowOpKind.SUB ->
                if (!w.signed) {
                    """
KM_ALWAYS_INLINE uint8_t ${fn}Out(uint64_t aLo, uint64_t aHi, uint64_t bLo, uint64_t bHi, uint64_t* outLo, uint64_t* outHi) {
    uint64_t borrow = aLo < bLo ? 1ULL : 0ULL;
    *outLo = aLo - bLo;
    *outHi = aHi - bHi - borrow;
    return (aHi < bHi || (aHi == bHi && aLo < bLo)) ? 1u : 0u;
}
                    """.trim()
                } else {
                    """
KM_ALWAYS_INLINE uint8_t ${fn}Out(uint64_t aLo, uint64_t aHi, uint64_t bLo, uint64_t bHi, uint64_t* outLo, uint64_t* outHi) {
    uint64_t rLo = 0ULL, rHi = 0ULL;
    kmSubWithOverflowU128Out(aLo, aHi, bLo, bHi, &rLo, &rHi);
    *outLo = rLo;
    *outHi = rHi;
    return (uint8_t)((((aHi ^ bHi) & (aHi ^ rHi)) >> 63) & 1ULL);
}
                    """.trim()
                }
            OverflowOpKind.MUL ->
                if (!w.signed) {
                    """
KM_ALWAYS_INLINE uint8_t ${fn}Out(uint64_t aLo, uint64_t aHi, uint64_t bLo, uint64_t bHi, uint64_t* outLo, uint64_t* outHi) {
    uint64_t p00Lo = 0ULL, p00Hi = 0ULL;
    uint64_t p01Lo = 0ULL, p01Hi = 0ULL;
    uint64_t p10Lo = 0ULL, p10Hi = 0ULL;
    kmMulU64Wide(aLo, bLo, &p00Lo, &p00Hi);
    kmMulU64Wide(aLo, bHi, &p01Lo, &p01Hi);
    kmMulU64Wide(aHi, bLo, &p10Lo, &p10Hi);
    uint64_t mid1 = 0ULL;
    uint64_t c1 = kmAddU64Carry(p00Hi, p01Lo, &mid1);
    uint64_t mid2 = 0ULL;
    uint64_t c2 = kmAddU64Carry(mid1, p10Lo, &mid2);
    *outLo = p00Lo;
    *outHi = mid2;
    uint8_t highNonZero = (p01Hi != 0ULL || p10Hi != 0ULL || c1 != 0ULL || c2 != 0ULL || (aHi != 0ULL && bHi != 0ULL)) ? 1u : 0u;
    return highNonZero;
}
                    """.trim()
                } else {
                    """
KM_ALWAYS_INLINE uint8_t ${fn}Out(uint64_t aLo, uint64_t aHi, uint64_t bLo, uint64_t bHi, uint64_t* outLo, uint64_t* outHi) {
    int sA = (aHi >> 63) != 0ULL;
    int sB = (bHi >> 63) != 0ULL;
    uint64_t mALo = aLo, mAHi = aHi, mBLo = bLo, mBHi = bHi;
    if (sA) kmU128NegateRaw(aLo, aHi, &mALo, &mAHi);
    if (sB) kmU128NegateRaw(bLo, bHi, &mBLo, &mBHi);
    uint64_t pLo = 0ULL, pHi = 0ULL;
    uint8_t hiNonZero = kmMulWithOverflowU128Out(mALo, mAHi, mBLo, mBHi, &pLo, &pHi);
    int neg = sA != sB;
    if (neg) {
        kmU128NegateRaw(pLo, pHi, outLo, outHi);
        if (hiNonZero != 0u) return 1u;
        return (pHi > 0x8000000000000000ULL || (pHi == 0x8000000000000000ULL && pLo != 0ULL)) ? 1u : 0u;
    } else {
        *outLo = pLo;
        *outHi = pHi;
        if (hiNonZero != 0u) return 1u;
        return (pHi >> 63) != 0ULL ? 1u : 0u;
    }
}
                    """.trim()
                }
        }
    }

    private fun renderSaturatingArithC(op: OverflowOpKind, w: IntWidthKind): String {
        val fn = "kmSaturating${op.stem}${w.widthTag}"
        val ovfFn = "km${op.stem}WithOverflow${w.widthTag}"
        if (w.bits < 64) {
            val ct = w.cType
            val uct = w.cUnsignedType()
            val umax =
                when (w.bits) {
                    8 -> "UINT8_MAX"
                    16 -> "UINT16_MAX"
                    else -> "UINT32_MAX"
                }
            val smin =
                when (w.bits) {
                    8 -> "INT8_MIN"
                    16 -> "INT16_MIN"
                    else -> "INT32_MIN"
                }
            val smax =
                when (w.bits) {
                    8 -> "INT8_MAX"
                    16 -> "INT16_MAX"
                    else -> "INT32_MAX"
                }
            val satBody =
                if (!w.signed) {
                    if (op == OverflowOpKind.ADD) {
                        "if (ovf != 0u) return ($ct)$umax;"
                    } else {
                        "if (ovf != 0u) return ($ct)0u;"
                    }
                } else {
                    "if (ovf != 0u) return ($ct)((($uct)res >> ${w.bits - 1}) != 0u ? ($ct)$smax : ($ct)$smin);"
                }
            return """
KM_ALWAYS_INLINE $ct $fn($ct a, $ct b) {
    uint64_t packed = ${ovfFn}Packed(a, b);
    uint8_t ovf = (uint8_t)(packed >> 32);
    $uct resBits = ($uct)(packed & 0xFFFFFFFFULL);
    $ct res = ($ct)resBits;
    $satBody
    return res;
}
            """.trim()
        }
        if (w.bits == 64) {
            val ct = w.cType
            val satBody =
                if (!w.signed) {
                    if (op == OverflowOpKind.ADD) {
                        "if (ovf != 0u) return UINT64_MAX;"
                    } else {
                        "if (ovf != 0u) return 0ULL;"
                    }
                } else {
                    "if (ovf != 0u) return ((uint64_t)res >> 63) != 0ULL ? INT64_MAX : INT64_MIN;"
                }
            return """
KM_ALWAYS_INLINE $ct $fn($ct a, $ct b) {
    $ct res = 0;
    uint8_t ovf = ${ovfFn}Out(a, b, &res);
    $satBody
    return res;
}
            """.trim()
        }
        // 128-bit
        val sat128 =
            if (!w.signed) {
                if (op == OverflowOpKind.ADD) {
                    "if (ovf != 0u) { *outLo = 0xFFFFFFFFFFFFFFFFULL; *outHi = 0xFFFFFFFFFFFFFFFFULL; return; }"
                } else {
                    "if (ovf != 0u) { *outLo = 0ULL; *outHi = 0ULL; return; }"
                }
            } else {
                """
                if (ovf != 0u) {
                    if ((rHi >> 63) != 0ULL) { *outLo = 0xFFFFFFFFFFFFFFFFULL; *outHi = 0x7FFFFFFFFFFFFFFFULL; }
                    else { *outLo = 0ULL; *outHi = 0x8000000000000000ULL; }
                    return;
                }
                """.trimIndent()
            }
        return """
KM_ALWAYS_INLINE void ${fn}Out(uint64_t aLo, uint64_t aHi, uint64_t bLo, uint64_t bHi, uint64_t* outLo, uint64_t* outHi) {
    uint64_t rLo = 0ULL, rHi = 0ULL;
    uint8_t ovf = ${ovfFn}Out(aLo, aHi, bLo, bHi, &rLo, &rHi);
    $sat128
    *outLo = rLo;
    *outHi = rHi;
}
        """.trim()
    }

    private fun renderU128AndFloatC(): String =
        buildString {
            appendLine(
                """
KM_ALWAYS_INLINE void kmU128AddOut(uint64_t aLo, uint64_t aHi, uint64_t bLo, uint64_t bHi, uint64_t* outLo, uint64_t* outHi) {
    kmAddWithOverflowU128Out(aLo, aHi, bLo, bHi, outLo, outHi);
}

KM_ALWAYS_INLINE void kmU128SubOut(uint64_t aLo, uint64_t aHi, uint64_t bLo, uint64_t bHi, uint64_t* outLo, uint64_t* outHi) {
    kmSubWithOverflowU128Out(aLo, aHi, bLo, bHi, outLo, outHi);
}

KM_ALWAYS_INLINE void kmU128MulOut(uint64_t aLo, uint64_t aHi, uint64_t bLo, uint64_t bHi, uint64_t* outLo, uint64_t* outHi) {
    kmMulWithOverflowU128Out(aLo, aHi, bLo, bHi, outLo, outHi);
}

KM_ALWAYS_INLINE void kmU128DivRemOut(
    uint64_t aLo, uint64_t aHi, uint64_t bLo, uint64_t bHi,
    uint64_t* outQLo, uint64_t* outQHi, uint64_t* outRLo, uint64_t* outRHi
) {
    kmU128DivRemRaw(aLo, aHi, bLo, bHi, outQLo, outQHi, outRLo, outRHi);
}

KM_ALWAYS_INLINE void kmI128DivRemOut(
    uint64_t aLo, uint64_t aHi, uint64_t bLo, uint64_t bHi,
    uint64_t* outQLo, uint64_t* outQHi, uint64_t* outRLo, uint64_t* outRHi
) {
    kmI128DivRemRaw(aLo, aHi, bLo, bHi, outQLo, outQHi, outRLo, outRHi);
}

KM_ALWAYS_INLINE void kmU128ShlOut(uint64_t aLo, uint64_t aHi, uint32_t shift, uint64_t* outLo, uint64_t* outHi) {
    uint32_t s = shift & 127u;
    if (s == 0u) { *outLo = aLo; *outHi = aHi; return; }
    if (s == 64u) { *outLo = 0ULL; *outHi = aLo; return; }
    if (s > 64u) { *outLo = 0ULL; *outHi = aLo << (s - 64u); return; }
    *outLo = aLo << s;
    *outHi = (aHi << s) | (aLo >> (64u - s));
}

KM_ALWAYS_INLINE void kmU128ShrOut(uint64_t aLo, uint64_t aHi, uint32_t shift, uint64_t* outLo, uint64_t* outHi) {
    uint32_t s = shift & 127u;
    if (s == 0u) { *outLo = aLo; *outHi = aHi; return; }
    if (s == 64u) { *outLo = aHi; *outHi = 0ULL; return; }
    if (s > 64u) { *outLo = aHi >> (s - 64u); *outHi = 0ULL; return; }
    *outLo = (aLo >> s) | (aHi << (64u - s));
    *outHi = aHi >> s;
}

KM_ALWAYS_INLINE void kmI128ShrOut(uint64_t aLo, uint64_t aHi, uint32_t shift, uint64_t* outLo, uint64_t* outHi) {
    uint32_t s = shift & 127u;
    uint64_t signFill = ((aHi >> 63) != 0ULL) ? 0xFFFFFFFFFFFFFFFFULL : 0ULL;
    if (s == 0u) { *outLo = aLo; *outHi = aHi; return; }
    if (s == 64u) { *outLo = aHi; *outHi = signFill; return; }
    if (s > 64u) {
        uint32_t k = s - 64u;
        *outLo = (aHi >> k) | (signFill << (64u - k));
        *outHi = signFill;
        return;
    }
    *outLo = (aLo >> s) | (aHi << (64u - s));
    *outHi = (aHi >> s) | (signFill << (64u - s));
}

KM_ALWAYS_INLINE float kmPowif32(float x, int32_t exp) {
    if (exp == 0) return 1.0f;
    uint32_t e = (exp < 0) ? (uint32_t)(0u - (uint32_t)exp) : (uint32_t)exp;
    float base = (exp < 0) ? (1.0f / x) : x;
    float acc = 1.0f;
    while (e != 0u) {
        if ((e & 1u) != 0u) acc *= base;
        e >>= 1;
        if (e != 0u) base *= base;
    }
    return acc;
}

KM_ALWAYS_INLINE double kmPowif64(double x, int32_t exp) {
    if (exp == 0) return 1.0;
    uint32_t e = (exp < 0) ? (uint32_t)(0u - (uint32_t)exp) : (uint32_t)exp;
    double base = (exp < 0) ? (1.0 / x) : x;
    double acc = 1.0;
    while (e != 0u) {
        if ((e & 1u) != 0u) acc *= base;
        e >>= 1;
        if (e != 0u) base *= base;
    }
    return acc;
}
                """.trim(),
            )
            for (spec in FLOAT_MATH_SPECS) {
                if (spec.cCallee == spec.expectName) continue
                val fTy = if (spec.isF64) "double" else "float"
                if (spec.arity == 1) {
                    appendLine("KM_ALWAYS_INLINE $fTy ${spec.expectName}($fTy x) { return ${spec.cCallee}(x); }")
                } else {
                    appendLine("KM_ALWAYS_INLINE $fTy ${spec.expectName}($fTy a, $fTy b) { return ${spec.cCallee}(a, b); }")
                }
            }
            appendLine(
                """
KM_ALWAYS_INLINE uint64_t kmFloatToU64Unchecked(float v) {
    return (v >= 0.0f) ? (uint64_t)v : 0ULL;
}

KM_ALWAYS_INLINE uint64_t kmDoubleToU64Unchecked(double v) {
    return (v >= 0.0) ? (uint64_t)v : 0ULL;
}

KM_ALWAYS_INLINE void kmFloatToU128UncheckedOut(float v, uint64_t* outLo, uint64_t* outHi) {
    if (!(v >= 0.0f)) { *outLo = 0ULL; *outHi = 0ULL; return; }
#if defined(__SIZEOF_INT128__)
    __uint128_t r = (__uint128_t)v;
    *outLo = (uint64_t)r;
    *outHi = (uint64_t)(r >> 64);
#else
    double hiScale = 18446744073709551616.0;
    double dv = (double)v;
    uint64_t hi = (dv >= hiScale) ? (uint64_t)(dv / hiScale) : 0ULL;
    double rem = dv - (double)hi * hiScale;
    uint64_t lo = (rem >= 0.0) ? (uint64_t)rem : 0ULL;
    *outLo = lo;
    *outHi = hi;
#endif
}

KM_ALWAYS_INLINE void kmDoubleToU128UncheckedOut(double v, uint64_t* outLo, uint64_t* outHi) {
    if (!(v >= 0.0)) { *outLo = 0ULL; *outHi = 0ULL; return; }
#if defined(__SIZEOF_INT128__)
    __uint128_t r = (__uint128_t)v;
    *outLo = (uint64_t)r;
    *outHi = (uint64_t)(r >> 64);
#else
    double hiScale = 18446744073709551616.0;
    uint64_t hi = (v >= hiScale) ? (uint64_t)(v / hiScale) : 0ULL;
    double rem = v - (double)hi * hiScale;
    uint64_t lo = (rem >= 0.0) ? (uint64_t)rem : 0ULL;
    *outLo = lo;
    *outHi = hi;
#endif
}
                """.trim(),
            )
        }

    public fun renderExpectDeclarations(): String =
        buildString {
            for (spec in UnaryBitKind.entries) {
                val sig =
                    when {
                        spec.width == 128 && spec.returns128 -> "(lo: ULong, hi: ULong): U128ValueOutcome"
                        spec.width == 128 -> "(lo: ULong, hi: ULong): UInt"
                        spec.returnsUInt -> "(value: ${smallWidthKtReturnFromU32(spec.width)}): UInt"
                        else -> "(value: ${smallWidthKtReturnFromU32(spec.width)}): ${smallWidthKtReturnFromU32(spec.width)}"
                    }
                appendLine("public expect fun ${spec.expectName}$sig")
            }
            for (spec in ROTATE_SPECS) {
                if (spec.width == 128) {
                    appendLine("public expect fun ${spec.expectName}(lo: ULong, hi: ULong, n: UInt): U128ValueOutcome")
                } else {
                    val kt = smallWidthKtReturnFromU32(spec.width)
                    appendLine("public expect fun ${spec.expectName}(value: $kt, n: UInt): $kt")
                }
            }
            for (op in OverflowOpKind.entries) {
                for (w in ALL_INT_WIDTHS) {
                    val fn = "km${op.stem}WithOverflow${w.widthTag}"
                    if (w.bits < 128) {
                        appendLine("public expect fun $fn(a: ${w.ktType}, b: ${w.ktType}): Overflow${w.widthTag}Outcome")
                    } else {
                        appendLine("public expect fun $fn(aLo: ULong, aHi: ULong, bLo: ULong, bHi: ULong): Overflow${w.widthTag}Outcome")
                    }
                }
            }
            for (op in listOf(OverflowOpKind.ADD, OverflowOpKind.SUB)) {
                for (w in ALL_INT_WIDTHS) {
                    val fn = "kmSaturating${op.stem}${w.widthTag}"
                    if (w.bits < 128) {
                        appendLine("public expect fun $fn(a: ${w.ktType}, b: ${w.ktType}): ${w.ktType}")
                    } else {
                        appendLine("public expect fun $fn(aLo: ULong, aHi: ULong, bLo: ULong, bHi: ULong): ${w.ktType}")
                    }
                }
            }
            for ((fn, _) in U128_VALUE_BINOPS) {
                appendLine("public expect fun $fn(aLo: ULong, aHi: ULong, bLo: ULong, bHi: ULong): U128ValueOutcome")
            }
            for ((fn, outType) in U128_DIVREM_OPS) {
                appendLine("public expect fun $fn(aLo: ULong, aHi: ULong, bLo: ULong, bHi: ULong): $outType")
            }
            for ((fn, outType, _) in U128_SHIFT_OPS) {
                appendLine("public expect fun $fn(aLo: ULong, aHi: ULong, shift: UInt): $outType")
            }
            for (spec in FLOAT_MATH_SPECS) {
                val fKt = if (spec.isF64) "Double" else "Float"
                val sig =
                    when {
                        spec.arity == 1 -> "(x: $fKt): $fKt"
                        spec.secondIsInt -> "(x: $fKt, exp: Int): $fKt"
                        else -> "(a: $fKt, b: $fKt): $fKt"
                    }
                appendLine("public expect fun ${spec.expectName}$sig")
            }
            appendLine(
                """
public expect fun kmFloatToU64Unchecked(v: Float): ULong
public expect fun kmDoubleToU64Unchecked(v: Double): ULong
public expect fun kmFloatToU128Unchecked(v: Float): U128ValueOutcome
public expect fun kmDoubleToU128Unchecked(v: Double): U128ValueOutcome
                """.trim(),
            )
        }

    public fun renderNativeActuals(cinteropPackage: String): String =
        buildString {
            for (spec in UnaryBitKind.entries) {
                when {
                    spec.width == 128 && spec.returns128 ->
                        appendLine(renderNativeU128OutActual("${spec.expectName}(lo: ULong, hi: ULong): U128ValueOutcome", "$cinteropPackage.${spec.expectName}Out(lo, hi, outLo.ptr, outHi.ptr)", "U128ValueOutcome"))
                    spec.width == 128 ->
                        appendLine("public actual fun ${spec.expectName}(lo: ULong, hi: ULong): UInt = $cinteropPackage.${spec.expectName}(lo, hi)")
                    spec.returnsUInt -> {
                        val kt = smallWidthKtReturnFromU32(spec.width)
                        appendLine("public actual fun ${spec.expectName}(value: $kt): UInt = $cinteropPackage.${spec.expectName}(value)")
                    }
                    else -> {
                        val kt = smallWidthKtReturnFromU32(spec.width)
                        appendLine("public actual fun ${spec.expectName}(value: $kt): $kt = $cinteropPackage.${spec.expectName}(value)")
                    }
                }
            }
            for (spec in ROTATE_SPECS) {
                if (spec.width == 128) {
                    appendLine(renderNativeU128OutActual("${spec.expectName}(lo: ULong, hi: ULong, n: UInt): U128ValueOutcome", "$cinteropPackage.${spec.expectName}Out(lo, hi, n, outLo.ptr, outHi.ptr)", "U128ValueOutcome"))
                } else {
                    val kt = smallWidthKtReturnFromU32(spec.width)
                    appendLine("public actual fun ${spec.expectName}(value: $kt, n: UInt): $kt = $cinteropPackage.${spec.expectName}(value, n)")
                }
            }
            for (op in OverflowOpKind.entries) {
                for (w in ALL_INT_WIDTHS) {
                    val fn = "km${op.stem}WithOverflow${w.widthTag}"
                    when {
                        w.bits < 64 -> {
                            val unwrap =
                                when (w.widthTag) {
                                    "U8" -> "packed.toUByte()"
                                    "U16" -> "packed.toUShort()"
                                    "U32" -> "packed.toUInt()"
                                    "I8" -> "packed.toByte()"
                                    "I16" -> "packed.toShort()"
                                    else -> "packed.toInt()"
                                }
                            appendLine(
                                """
public actual fun $fn(a: ${w.ktType}, b: ${w.ktType}): Overflow${w.widthTag}Outcome {
    val packed = $cinteropPackage.${fn}Packed(a, b)
    return Overflow${w.widthTag}Outcome($unwrap, (packed shr 32) != 0uL)
}
                                """.trim(),
                            )
                        }
                        w.bits == 64 -> {
                            val varType = if (w.signed) "LongVar" else "ULongVar"
                            appendLine(
                                """
public actual fun $fn(a: ${w.ktType}, b: ${w.ktType}): Overflow${w.widthTag}Outcome =
    memScoped {
        val outVal = alloc<$varType>()
        val ovf = $cinteropPackage.${fn}Out(a, b, outVal.ptr)
        Overflow${w.widthTag}Outcome(outVal.value, ovf != 0u.toUByte())
    }
                                """.trim(),
                            )
                        }
                        else -> {
                            appendLine(
                                """
public actual fun $fn(aLo: ULong, aHi: ULong, bLo: ULong, bHi: ULong): Overflow${w.widthTag}Outcome =
    memScoped {
        val outLo = alloc<ULongVar>()
        val outHi = alloc<ULongVar>()
        val ovf = $cinteropPackage.${fn}Out(aLo, aHi, bLo, bHi, outLo.ptr, outHi.ptr)
        Overflow${w.widthTag}Outcome(outLo.value, outHi.value, ovf != 0u.toUByte())
    }
                                """.trim(),
                            )
                        }
                    }
                }
            }
            for (op in listOf(OverflowOpKind.ADD, OverflowOpKind.SUB)) {
                for (w in ALL_INT_WIDTHS) {
                    val fn = "kmSaturating${op.stem}${w.widthTag}"
                    if (w.bits < 128) {
                        appendLine("public actual fun $fn(a: ${w.ktType}, b: ${w.ktType}): ${w.ktType} = $cinteropPackage.$fn(a, b)")
                    } else {
                        appendLine(renderNativeU128OutActual("$fn(aLo: ULong, aHi: ULong, bLo: ULong, bHi: ULong): ${w.ktType}", "$cinteropPackage.${fn}Out(aLo, aHi, bLo, bHi, outLo.ptr, outHi.ptr)", w.ktType))
                    }
                }
            }
            for ((fn, _) in U128_VALUE_BINOPS) {
                appendLine(renderNativeU128OutActual("$fn(aLo: ULong, aHi: ULong, bLo: ULong, bHi: ULong): U128ValueOutcome", "$cinteropPackage.${fn}Out(aLo, aHi, bLo, bHi, outLo.ptr, outHi.ptr)", "U128ValueOutcome"))
            }
            for ((fn, outType) in U128_DIVREM_OPS) {
                appendLine(
                    """
public actual fun $fn(aLo: ULong, aHi: ULong, bLo: ULong, bHi: ULong): $outType =
    memScoped {
        val qLo = alloc<ULongVar>()
        val qHi = alloc<ULongVar>()
        val rLo = alloc<ULongVar>()
        val rHi = alloc<ULongVar>()
        $cinteropPackage.${fn}Out(aLo, aHi, bLo, bHi, qLo.ptr, qHi.ptr, rLo.ptr, rHi.ptr)
        $outType(qLo.value, qHi.value, rLo.value, rHi.value)
    }
                    """.trim(),
                )
            }
            for ((fn, outType, _) in U128_SHIFT_OPS) {
                appendLine(renderNativeU128OutActual("$fn(aLo: ULong, aHi: ULong, shift: UInt): $outType", "$cinteropPackage.${fn}Out(aLo, aHi, shift, outLo.ptr, outHi.ptr)", outType))
            }
            for (spec in FLOAT_MATH_SPECS) {
                val fKt = if (spec.isF64) "Double" else "Float"
                when {
                    spec.arity == 1 ->
                        appendLine("public actual fun ${spec.expectName}(x: $fKt): $fKt = $cinteropPackage.${spec.expectName}(x)")
                    spec.secondIsInt ->
                        appendLine("public actual fun ${spec.expectName}(x: $fKt, exp: Int): $fKt = $cinteropPackage.${spec.expectName}(x, exp)")
                    else ->
                        appendLine("public actual fun ${spec.expectName}(a: $fKt, b: $fKt): $fKt = $cinteropPackage.${spec.expectName}(a, b)")
                }
            }
            appendLine(
                """
public actual fun kmFloatToU64Unchecked(v: Float): ULong = $cinteropPackage.kmFloatToU64Unchecked(v)
public actual fun kmDoubleToU64Unchecked(v: Double): ULong = $cinteropPackage.kmDoubleToU64Unchecked(v)
${renderNativeU128OutActual("kmFloatToU128Unchecked(v: Float): U128ValueOutcome", "$cinteropPackage.kmFloatToU128UncheckedOut(v, outLo.ptr, outHi.ptr)", "U128ValueOutcome")}
${renderNativeU128OutActual("kmDoubleToU128Unchecked(v: Double): U128ValueOutcome", "$cinteropPackage.kmDoubleToU128UncheckedOut(v, outLo.ptr, outHi.ptr)", "U128ValueOutcome")}
                """.trim(),
            )
        }

    private fun renderNativeU128OutActual(sig: String, call: String, retType: String): String =
        """
public actual fun $sig =
    memScoped {
        val outLo = alloc<ULongVar>()
        val outHi = alloc<ULongVar>()
        $call
        $retType(outLo.value, outHi.value)
    }
        """.trim()

    public fun renderJniCFunctions(): String =
        buildString {
            for (spec in UnaryBitKind.entries) {
                when {
                    spec.width == 128 && spec.returns128 ->
                        appendLine(renderJniU128PairOut("${spec.expectName}Out", "jlong lo, jlong hi", "${spec.expectName}Out((uint64_t)lo, (uint64_t)hi, &outLo, &outHi)"))
                    spec.width == 128 ->
                        appendLine(
                            """
JNIEXPORT jint JNICALL KM_JNI_FN(${spec.expectName})(JNIEnv* env, jclass clazz, jlong lo, jlong hi) {
    (void)env; (void)clazz;
    return (jint)${spec.expectName}((uint64_t)lo, (uint64_t)hi);
}
                            """.trim(),
                        )
                    else -> {
                        val jt = smallWidthJniType(spec.width)
                        val ct = smallWidthCType(spec.width)
                        val retJ = if (spec.returnsUInt) "jint" else jt
                        appendLine(
                            """
JNIEXPORT $retJ JNICALL KM_JNI_FN(${spec.expectName})(JNIEnv* env, jclass clazz, $jt value) {
    (void)env; (void)clazz;
    return ($retJ)${spec.expectName}(($ct)value);
}
                            """.trim(),
                        )
                    }
                }
            }
            for (spec in ROTATE_SPECS) {
                if (spec.width == 128) {
                    appendLine(renderJniU128PairOut("${spec.expectName}Out", "jlong lo, jlong hi, jint n", "${spec.expectName}Out((uint64_t)lo, (uint64_t)hi, (uint32_t)n, &outLo, &outHi)"))
                } else {
                    val jt = smallWidthJniType(spec.width)
                    val ct = smallWidthCType(spec.width)
                    appendLine(
                        """
JNIEXPORT $jt JNICALL KM_JNI_FN(${spec.expectName})(JNIEnv* env, jclass clazz, $jt value, jint n) {
    (void)env; (void)clazz;
    return ($jt)${spec.expectName}(($ct)value, (uint32_t)n);
}
                        """.trim(),
                    )
                }
            }
            for (op in OverflowOpKind.entries) {
                for (w in ALL_INT_WIDTHS) {
                    val fn = "km${op.stem}WithOverflow${w.widthTag}"
                    when {
                        w.bits < 64 ->
                            appendLine(
                                """
JNIEXPORT jlong JNICALL KM_JNI_FN(${fn}Packed)(JNIEnv* env, jclass clazz, ${w.jniType} a, ${w.jniType} b) {
    (void)env; (void)clazz;
    return (jlong)${fn}Packed((${w.cType})a, (${w.cType})b);
}
                                """.trim(),
                            )
                        w.bits == 64 ->
                            appendLine(
                                """
JNIEXPORT void JNICALL KM_JNI_FN(${fn}Out)(JNIEnv* env, jclass clazz, jlong a, jlong b, jlongArray outBuf) {
    (void)clazz;
    ${w.cType} res = 0;
    uint8_t ovf = ${fn}Out((${w.cType})a, (${w.cType})b, &res);
    jlong buf[2];
    buf[0] = (jlong)res;
    buf[1] = (jlong)(uint64_t)ovf;
    (*env)->SetLongArrayRegion(env, outBuf, 0, 2, buf);
}
                                """.trim(),
                            )
                        else ->
                            appendLine(
                                """
JNIEXPORT void JNICALL KM_JNI_FN(${fn}Out)(JNIEnv* env, jclass clazz, jlong aLo, jlong aHi, jlong bLo, jlong bHi, jlongArray outBuf) {
    (void)clazz;
    uint64_t outLo = 0ULL, outHi = 0ULL;
    uint8_t ovf = ${fn}Out((uint64_t)aLo, (uint64_t)aHi, (uint64_t)bLo, (uint64_t)bHi, &outLo, &outHi);
    jlong buf[3];
    buf[0] = (jlong)outLo;
    buf[1] = (jlong)outHi;
    buf[2] = (jlong)(uint64_t)ovf;
    (*env)->SetLongArrayRegion(env, outBuf, 0, 3, buf);
}
                                """.trim(),
                            )
                    }
                }
            }
            for (op in listOf(OverflowOpKind.ADD, OverflowOpKind.SUB)) {
                for (w in ALL_INT_WIDTHS) {
                    val fn = "kmSaturating${op.stem}${w.widthTag}"
                    if (w.bits < 128) {
                        appendLine(
                            """
JNIEXPORT ${w.jniType} JNICALL KM_JNI_FN($fn)(JNIEnv* env, jclass clazz, ${w.jniType} a, ${w.jniType} b) {
    (void)env; (void)clazz;
    return (${w.jniType})$fn((${w.cType})a, (${w.cType})b);
}
                            """.trim(),
                        )
                    } else {
                        appendLine(renderJniU128PairOut("${fn}Out", "jlong aLo, jlong aHi, jlong bLo, jlong bHi", "${fn}Out((uint64_t)aLo, (uint64_t)aHi, (uint64_t)bLo, (uint64_t)bHi, &outLo, &outHi)"))
                    }
                }
            }
            for ((fn, _) in U128_VALUE_BINOPS) {
                appendLine(renderJniU128PairOut("${fn}Out", "jlong aLo, jlong aHi, jlong bLo, jlong bHi", "${fn}Out((uint64_t)aLo, (uint64_t)aHi, (uint64_t)bLo, (uint64_t)bHi, &outLo, &outHi)"))
            }
            for ((fn, _) in U128_DIVREM_OPS) {
                appendLine(
                    """
JNIEXPORT void JNICALL KM_JNI_FN(${fn}Out)(JNIEnv* env, jclass clazz, jlong aLo, jlong aHi, jlong bLo, jlong bHi, jlongArray outBuf) {
    (void)clazz;
    uint64_t qLo = 0ULL, qHi = 0ULL, rLo = 0ULL, rHi = 0ULL;
    ${fn}Out((uint64_t)aLo, (uint64_t)aHi, (uint64_t)bLo, (uint64_t)bHi, &qLo, &qHi, &rLo, &rHi);
    jlong buf[4];
    buf[0] = (jlong)qLo;
    buf[1] = (jlong)qHi;
    buf[2] = (jlong)rLo;
    buf[3] = (jlong)rHi;
    (*env)->SetLongArrayRegion(env, outBuf, 0, 4, buf);
}
                    """.trim(),
                )
            }
            for ((fn, _, _) in U128_SHIFT_OPS) {
                appendLine(renderJniU128PairOut("${fn}Out", "jlong aLo, jlong aHi, jint shift", "${fn}Out((uint64_t)aLo, (uint64_t)aHi, (uint32_t)shift, &outLo, &outHi)"))
            }
            for (spec in FLOAT_MATH_SPECS) {
                val jt = if (spec.isF64) "jdouble" else "jfloat"
                val ct = if (spec.isF64) "double" else "float"
                when {
                    spec.arity == 1 ->
                        appendLine(
                            """
JNIEXPORT $jt JNICALL KM_JNI_FN(${spec.expectName})(JNIEnv* env, jclass clazz, $jt x) {
    (void)env; (void)clazz;
    return ($jt)${spec.expectName}(($ct)x);
}
                            """.trim(),
                        )
                    spec.secondIsInt ->
                        appendLine(
                            """
JNIEXPORT $jt JNICALL KM_JNI_FN(${spec.expectName})(JNIEnv* env, jclass clazz, $jt x, jint exp) {
    (void)env; (void)clazz;
    return ($jt)${spec.expectName}(($ct)x, (int32_t)exp);
}
                            """.trim(),
                        )
                    else ->
                        appendLine(
                            """
JNIEXPORT $jt JNICALL KM_JNI_FN(${spec.expectName})(JNIEnv* env, jclass clazz, $jt a, $jt b) {
    (void)env; (void)clazz;
    return ($jt)${spec.expectName}(($ct)a, ($ct)b);
}
                            """.trim(),
                        )
                }
            }
            appendLine(
                """
JNIEXPORT jlong JNICALL KM_JNI_FN(kmFloatToU64Unchecked)(JNIEnv* env, jclass clazz, jfloat v) {
    (void)env; (void)clazz;
    return (jlong)kmFloatToU64Unchecked((float)v);
}
JNIEXPORT jlong JNICALL KM_JNI_FN(kmDoubleToU64Unchecked)(JNIEnv* env, jclass clazz, jdouble v) {
    (void)env; (void)clazz;
    return (jlong)kmDoubleToU64Unchecked((double)v);
}
${renderJniU128PairOut("kmFloatToU128UncheckedOut", "jfloat v", "kmFloatToU128UncheckedOut((float)v, &outLo, &outHi)")}
${renderJniU128PairOut("kmDoubleToU128UncheckedOut", "jdouble v", "kmDoubleToU128UncheckedOut((double)v, &outLo, &outHi)")}
                """.trim(),
            )
        }

    private fun renderJniU128PairOut(jniName: String, params: String, cCall: String): String =
        """
JNIEXPORT void JNICALL KM_JNI_FN($jniName)(JNIEnv* env, jclass clazz, $params, jlongArray outBuf) {
    (void)clazz;
    uint64_t outLo = 0ULL, outHi = 0ULL;
    $cCall;
    jlong buf[2];
    buf[0] = (jlong)outLo;
    buf[1] = (jlong)outHi;
    (*env)->SetLongArrayRegion(env, outBuf, 0, 2, buf);
}
        """.trim()

    public fun renderJvmExternalDeclarations(): String =
        buildString {
            for (spec in UnaryBitKind.entries) {
                when {
                    spec.width == 128 && spec.returns128 ->
                        appendLine("    @JvmStatic external fun ${spec.expectName}Out(lo: Long, hi: Long, outBuf: LongArray)")
                    spec.width == 128 ->
                        appendLine("    @JvmStatic external fun ${spec.expectName}(lo: Long, hi: Long): Int")
                    else -> {
                        val jt = smallWidthJvmType(spec.width)
                        val ret = if (spec.returnsUInt) "Int" else jt
                        appendLine("    @JvmStatic external fun ${spec.expectName}(value: $jt): $ret")
                    }
                }
            }
            for (spec in ROTATE_SPECS) {
                if (spec.width == 128) {
                    appendLine("    @JvmStatic external fun ${spec.expectName}Out(lo: Long, hi: Long, n: Int, outBuf: LongArray)")
                } else {
                    val jt = smallWidthJvmType(spec.width)
                    appendLine("    @JvmStatic external fun ${spec.expectName}(value: $jt, n: Int): $jt")
                }
            }
            for (op in OverflowOpKind.entries) {
                for (w in ALL_INT_WIDTHS) {
                    val fn = "km${op.stem}WithOverflow${w.widthTag}"
                    when {
                        w.bits < 64 -> appendLine("    @JvmStatic external fun ${fn}Packed(a: ${w.jniField}, b: ${w.jniField}): Long")
                        w.bits == 64 -> appendLine("    @JvmStatic external fun ${fn}Out(a: Long, b: Long, outBuf: LongArray)")
                        else -> appendLine("    @JvmStatic external fun ${fn}Out(aLo: Long, aHi: Long, bLo: Long, bHi: Long, outBuf: LongArray)")
                    }
                }
            }
            for (op in listOf(OverflowOpKind.ADD, OverflowOpKind.SUB)) {
                for (w in ALL_INT_WIDTHS) {
                    val fn = "kmSaturating${op.stem}${w.widthTag}"
                    if (w.bits < 128) {
                        appendLine("    @JvmStatic external fun $fn(a: ${w.jniField}, b: ${w.jniField}): ${w.jniField}")
                    } else {
                        appendLine("    @JvmStatic external fun ${fn}Out(aLo: Long, aHi: Long, bLo: Long, bHi: Long, outBuf: LongArray)")
                    }
                }
            }
            for ((fn, _) in U128_VALUE_BINOPS) {
                appendLine("    @JvmStatic external fun ${fn}Out(aLo: Long, aHi: Long, bLo: Long, bHi: Long, outBuf: LongArray)")
            }
            for ((fn, _) in U128_DIVREM_OPS) {
                appendLine("    @JvmStatic external fun ${fn}Out(aLo: Long, aHi: Long, bLo: Long, bHi: Long, outBuf: LongArray)")
            }
            for ((fn, _, _) in U128_SHIFT_OPS) {
                appendLine("    @JvmStatic external fun ${fn}Out(aLo: Long, aHi: Long, shift: Int, outBuf: LongArray)")
            }
            for (spec in FLOAT_MATH_SPECS) {
                val kt = if (spec.isF64) "Double" else "Float"
                when {
                    spec.arity == 1 -> appendLine("    @JvmStatic external fun ${spec.expectName}(x: $kt): $kt")
                    spec.secondIsInt -> appendLine("    @JvmStatic external fun ${spec.expectName}(x: $kt, exp: Int): $kt")
                    else -> appendLine("    @JvmStatic external fun ${spec.expectName}(a: $kt, b: $kt): $kt")
                }
            }
            appendLine(
                """
    @JvmStatic external fun kmFloatToU64Unchecked(v: Float): Long
    @JvmStatic external fun kmDoubleToU64Unchecked(v: Double): Long
    @JvmStatic external fun kmFloatToU128UncheckedOut(v: Float, outBuf: LongArray)
    @JvmStatic external fun kmDoubleToU128UncheckedOut(v: Double, outBuf: LongArray)
                """.trimEnd(),
            )
        }

    public fun renderJvmActuals(jniObjectName: String): String =
        buildString {
            for (spec in UnaryBitKind.entries) {
                when {
                    spec.width == 128 && spec.returns128 ->
                        appendLine(renderJvmOut2("${spec.expectName}(lo: ULong, hi: ULong): U128ValueOutcome", "$jniObjectName.${spec.expectName}Out(lo.toLong(), hi.toLong(), outBuf)", "U128ValueOutcome"))
                    spec.width == 128 ->
                        appendLine("public actual fun ${spec.expectName}(lo: ULong, hi: ULong): UInt = $jniObjectName.${spec.expectName}(lo.toLong(), hi.toLong()).toUInt()")
                    else -> {
                        val kt = smallWidthKtReturnFromU32(spec.width)
                        val argConv = jvmToUnsignedArgConv(spec.width, "value")
                        if (spec.returnsUInt) {
                            appendLine("public actual fun ${spec.expectName}(value: $kt): UInt = $jniObjectName.${spec.expectName}($argConv).toUInt()")
                        } else {
                            val retConv = jvmToUnsignedRetConv(spec.width)
                            appendLine("public actual fun ${spec.expectName}(value: $kt): $kt = $jniObjectName.${spec.expectName}($argConv)$retConv")
                        }
                    }
                }
            }
            for (spec in ROTATE_SPECS) {
                if (spec.width == 128) {
                    appendLine(renderJvmOut2("${spec.expectName}(lo: ULong, hi: ULong, n: UInt): U128ValueOutcome", "$jniObjectName.${spec.expectName}Out(lo.toLong(), hi.toLong(), n.toInt(), outBuf)", "U128ValueOutcome"))
                } else {
                    val kt = smallWidthKtReturnFromU32(spec.width)
                    val argConv = jvmToUnsignedArgConv(spec.width, "value")
                    val retConv = jvmToUnsignedRetConv(spec.width)
                    appendLine("public actual fun ${spec.expectName}(value: $kt, n: UInt): $kt = $jniObjectName.${spec.expectName}($argConv, n.toInt())$retConv")
                }
            }
            for (op in OverflowOpKind.entries) {
                for (w in ALL_INT_WIDTHS) {
                    val fn = "km${op.stem}WithOverflow${w.widthTag}"
                    when {
                        w.bits < 64 -> {
                            val aExpr = if (w.signed) "a" else "a.to${w.jniField}()"
                            val bExpr = if (w.signed) "b" else "b.to${w.jniField}()"
                            val vConv =
                                when (w.widthTag) {
                                    "U8" -> "packed.toUByte()"
                                    "U16" -> "packed.toUShort()"
                                    "U32" -> "packed.toUInt()"
                                    "I8" -> "packed.toByte()"
                                    "I16" -> "packed.toShort()"
                                    else -> "packed.toInt()"
                                }
                            appendLine(
                                """
public actual fun $fn(a: ${w.ktType}, b: ${w.ktType}): Overflow${w.widthTag}Outcome {
    val packed = $jniObjectName.${fn}Packed($aExpr, $bExpr)
    return Overflow${w.widthTag}Outcome($vConv, (packed ushr 32) != 0L)
}
                                """.trim(),
                            )
                        }
                        w.bits == 64 -> {
                            val aExpr = if (w.signed) "a" else "a.toLong()"
                            val bExpr = if (w.signed) "b" else "b.toLong()"
                            val outConv = if (w.signed) "outBuf[0]" else "outBuf[0].toULong()"
                            appendLine(
                                """
public actual fun $fn(a: ${w.ktType}, b: ${w.ktType}): Overflow${w.widthTag}Outcome {
    val outBuf = LongArray(2)
    $jniObjectName.${fn}Out($aExpr, $bExpr, outBuf)
    return Overflow${w.widthTag}Outcome($outConv, outBuf[1] != 0L)
}
                                """.trim(),
                            )
                        }
                        else -> {
                            appendLine(
                                """
public actual fun $fn(aLo: ULong, aHi: ULong, bLo: ULong, bHi: ULong): Overflow${w.widthTag}Outcome {
    val outBuf = LongArray(3)
    $jniObjectName.${fn}Out(aLo.toLong(), aHi.toLong(), bLo.toLong(), bHi.toLong(), outBuf)
    return Overflow${w.widthTag}Outcome(outBuf[0].toULong(), outBuf[1].toULong(), outBuf[2] != 0L)
}
                                """.trim(),
                            )
                        }
                    }
                }
            }
            for (op in listOf(OverflowOpKind.ADD, OverflowOpKind.SUB)) {
                for (w in ALL_INT_WIDTHS) {
                    val fn = "kmSaturating${op.stem}${w.widthTag}"
                    if (w.bits < 128) {
                        val aExpr = if (w.signed) "a" else "a.to${w.jniField}()"
                        val bExpr = if (w.signed) "b" else "b.to${w.jniField}()"
                        val retConv = if (w.signed) "" else ".to${w.ktType}()"
                        appendLine("public actual fun $fn(a: ${w.ktType}, b: ${w.ktType}): ${w.ktType} = $jniObjectName.$fn($aExpr, $bExpr)$retConv")
                    } else {
                        appendLine(renderJvmOut2("$fn(aLo: ULong, aHi: ULong, bLo: ULong, bHi: ULong): ${w.ktType}", "$jniObjectName.${fn}Out(aLo.toLong(), aHi.toLong(), bLo.toLong(), bHi.toLong(), outBuf)", w.ktType))
                    }
                }
            }
            for ((fn, _) in U128_VALUE_BINOPS) {
                appendLine(renderJvmOut2("$fn(aLo: ULong, aHi: ULong, bLo: ULong, bHi: ULong): U128ValueOutcome", "$jniObjectName.${fn}Out(aLo.toLong(), aHi.toLong(), bLo.toLong(), bHi.toLong(), outBuf)", "U128ValueOutcome"))
            }
            for ((fn, outType) in U128_DIVREM_OPS) {
                appendLine(
                    """
public actual fun $fn(aLo: ULong, aHi: ULong, bLo: ULong, bHi: ULong): $outType {
    val outBuf = LongArray(4)
    $jniObjectName.${fn}Out(aLo.toLong(), aHi.toLong(), bLo.toLong(), bHi.toLong(), outBuf)
    return $outType(outBuf[0].toULong(), outBuf[1].toULong(), outBuf[2].toULong(), outBuf[3].toULong())
}
                    """.trim(),
                )
            }
            for ((fn, outType, _) in U128_SHIFT_OPS) {
                appendLine(renderJvmOut2("$fn(aLo: ULong, aHi: ULong, shift: UInt): $outType", "$jniObjectName.${fn}Out(aLo.toLong(), aHi.toLong(), shift.toInt(), outBuf)", outType))
            }
            for (spec in FLOAT_MATH_SPECS) {
                val kt = if (spec.isF64) "Double" else "Float"
                when {
                    spec.arity == 1 ->
                        appendLine("public actual fun ${spec.expectName}(x: $kt): $kt = $jniObjectName.${spec.expectName}(x)")
                    spec.secondIsInt ->
                        appendLine("public actual fun ${spec.expectName}(x: $kt, exp: Int): $kt = $jniObjectName.${spec.expectName}(x, exp)")
                    else ->
                        appendLine("public actual fun ${spec.expectName}(a: $kt, b: $kt): $kt = $jniObjectName.${spec.expectName}(a, b)")
                }
            }
            appendLine(
                """
public actual fun kmFloatToU64Unchecked(v: Float): ULong = $jniObjectName.kmFloatToU64Unchecked(v).toULong()
public actual fun kmDoubleToU64Unchecked(v: Double): ULong = $jniObjectName.kmDoubleToU64Unchecked(v).toULong()
${renderJvmOut2("kmFloatToU128Unchecked(v: Float): U128ValueOutcome", "$jniObjectName.kmFloatToU128UncheckedOut(v, outBuf)", "U128ValueOutcome")}
${renderJvmOut2("kmDoubleToU128Unchecked(v: Double): U128ValueOutcome", "$jniObjectName.kmDoubleToU128UncheckedOut(v, outBuf)", "U128ValueOutcome")}
                """.trim(),
            )
        }

    private fun renderJvmOut2(sig: String, call: String, retType: String): String =
        """
public actual fun $sig {
    val outBuf = LongArray(2)
    $call
    return $retType(outBuf[0].toULong(), outBuf[1].toULong())
}
        """.trim()

    private fun jvmToUnsignedArgConv(width: Int, v: String): String =
        when (width) {
            8 -> "$v.toByte()"
            16 -> "$v.toShort()"
            32 -> "$v.toInt()"
            else -> "$v.toLong()"
        }

    private fun jvmToUnsignedRetConv(width: Int): String =
        when (width) {
            8 -> ".toUByte()"
            16 -> ".toUShort()"
            32 -> ".toUInt()"
            else -> ".toULong()"
        }

    public fun renderNapiRegistrations(): String =
        buildString {
            for (spec in UnaryBitKind.entries) {
                val fn = spec.expectName
                when {
                    spec.width == 128 && spec.returns128 ->
                        appendLine(renderNapiU128Pair(fn, "${fn}Out(kmCoreNapiArgU64Pair(info, 0), kmCoreNapiArgU64Pair(info, 2), &outLo, &outHi)"))
                    spec.width == 128 ->
                        appendLine(
                            """
    coreObj.Set("$fn", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(info.Env(), static_cast<int32_t>($fn(kmCoreNapiArgU64Pair(info, 0), kmCoreNapiArgU64Pair(info, 2))));
    }));
                            """.trim(),
                        )
                    else -> {
                        val ct = smallWidthCType(spec.width)
                        appendLine(
                            """
    coreObj.Set("$fn", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(info.Env(), static_cast<int32_t>($fn(static_cast<$ct>(kmCoreNapiArgU32(info[0])))));
    }));
                            """.trim(),
                        )
                    }
                }
            }
            for (spec in ROTATE_SPECS) {
                val fn = spec.expectName
                if (spec.width == 128) {
                    appendLine(renderNapiU128Pair(fn, "${fn}Out(kmCoreNapiArgU64Pair(info, 0), kmCoreNapiArgU64Pair(info, 2), kmCoreNapiArgU32(info[4]), &outLo, &outHi)"))
                } else {
                    val ct = smallWidthCType(spec.width)
                    appendLine(
                        """
    coreObj.Set("$fn", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(info.Env(), static_cast<int32_t>($fn(static_cast<$ct>(kmCoreNapiArgU32(info[0])), kmCoreNapiArgU32(info[1]))));
    }));
                        """.trim(),
                    )
                }
            }
            for (op in OverflowOpKind.entries) {
                for (w in ALL_INT_WIDTHS) {
                    val fn = "km${op.stem}WithOverflow${w.widthTag}"
                    when {
                        w.bits < 64 -> {
                            val arg0 =
                                if (w.signed) {
                                    "static_cast<${w.cType}>(kmCoreNapiArgI32(info[0]))"
                                } else {
                                    "static_cast<${w.cType}>(kmCoreNapiArgU32(info[0]))"
                                }
                            val arg1 =
                                if (w.signed) {
                                    "static_cast<${w.cType}>(kmCoreNapiArgI32(info[1]))"
                                } else {
                                    "static_cast<${w.cType}>(kmCoreNapiArgU32(info[1]))"
                                }
                            appendLine(
                                """
    coreObj.Set("$fn", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return kmCoreReturnU64LoHi(info, ${fn}Packed($arg0, $arg1));
    }));
                                """.trim(),
                            )
                        }
                        w.bits == 64 -> {
                            val argFn = if (w.signed) "kmCoreNapiArgI64Pair" else "kmCoreNapiArgU64Pair"
                            appendLine(
                                """
    coreObj.Set("$fn", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        ${w.cType} res = 0;
        uint8_t ovf = ${fn}Out($argFn(info, 0), $argFn(info, 2), &res);
        kmCoreStoreU64Scratch(0, static_cast<uint64_t>(res));
        return Napi::Number::New(info.Env(), static_cast<int32_t>(ovf));
    }));
                                """.trim(),
                            )
                        }
                        else -> {
                            appendLine(
                                """
    coreObj.Set("$fn", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        uint64_t outLo = 0ULL, outHi = 0ULL;
        uint8_t ovf = ${fn}Out(kmCoreNapiArgU64Pair(info, 0), kmCoreNapiArgU64Pair(info, 2), kmCoreNapiArgU64Pair(info, 4), kmCoreNapiArgU64Pair(info, 6), &outLo, &outHi);
        kmCoreStoreU64Scratch(0, outLo);
        kmCoreStoreU64Scratch(2, outHi);
        return Napi::Number::New(info.Env(), static_cast<int32_t>(ovf));
    }));
                                """.trim(),
                            )
                        }
                    }
                }
            }
            for (op in listOf(OverflowOpKind.ADD, OverflowOpKind.SUB)) {
                for (w in ALL_INT_WIDTHS) {
                    val fn = "kmSaturating${op.stem}${w.widthTag}"
                    when {
                        w.bits < 64 -> {
                            val argFn = if (w.signed) "kmCoreNapiArgI32" else "kmCoreNapiArgU32"
                            appendLine(
                                """
    coreObj.Set("$fn", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(info.Env(), static_cast<int32_t>($fn(static_cast<${w.cType}>($argFn(info[0])), static_cast<${w.cType}>($argFn(info[1])))));
    }));
                                """.trim(),
                            )
                        }
                        w.bits == 64 -> {
                            val argFn = if (w.signed) "kmCoreNapiArgI64Pair" else "kmCoreNapiArgU64Pair"
                            val retFn = if (w.signed) "kmCoreReturnI64LoHi" else "kmCoreReturnU64LoHi"
                            appendLine(
                                """
    coreObj.Set("$fn", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return $retFn(info, $fn($argFn(info, 0), $argFn(info, 2)));
    }));
                                """.trim(),
                            )
                        }
                        else ->
                            appendLine(renderNapiU128Pair(fn, "${fn}Out(kmCoreNapiArgU64Pair(info, 0), kmCoreNapiArgU64Pair(info, 2), kmCoreNapiArgU64Pair(info, 4), kmCoreNapiArgU64Pair(info, 6), &outLo, &outHi)"))
                    }
                }
            }
            for ((fn, _) in U128_VALUE_BINOPS) {
                appendLine(renderNapiU128Pair(fn, "${fn}Out(kmCoreNapiArgU64Pair(info, 0), kmCoreNapiArgU64Pair(info, 2), kmCoreNapiArgU64Pair(info, 4), kmCoreNapiArgU64Pair(info, 6), &outLo, &outHi)"))
            }
            for ((fn, _) in U128_DIVREM_OPS) {
                appendLine(
                    """
    coreObj.Set("$fn", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        uint64_t qLo = 0ULL, qHi = 0ULL, rLo = 0ULL, rHi = 0ULL;
        ${fn}Out(kmCoreNapiArgU64Pair(info, 0), kmCoreNapiArgU64Pair(info, 2), kmCoreNapiArgU64Pair(info, 4), kmCoreNapiArgU64Pair(info, 6), &qLo, &qHi, &rLo, &rHi);
        kmCoreStoreU64Scratch(0, qLo);
        kmCoreStoreU64Scratch(2, qHi);
        kmCoreStoreU64Scratch(4, rLo);
        kmCoreStoreU64Scratch(6, rHi);
        return Napi::Number::New(info.Env(), g_kmCoreOutScratch[0]);
    }));
                    """.trim(),
                )
            }
            for ((fn, _, _) in U128_SHIFT_OPS) {
                appendLine(renderNapiU128Pair(fn, "${fn}Out(kmCoreNapiArgU64Pair(info, 0), kmCoreNapiArgU64Pair(info, 2), kmCoreNapiArgU32(info[4]), &outLo, &outHi)"))
            }
            for (spec in FLOAT_MATH_SPECS) {
                val fn = spec.expectName
                when {
                    spec.arity == 1 ->
                        if (spec.isF64) {
                            appendLine(
                                """
    coreObj.Set("$fn", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(info.Env(), $fn(info[0].As<Napi::Number>().DoubleValue()));
    }));
                                """.trim(),
                            )
                        } else {
                            appendLine(
                                """
    coreObj.Set("$fn", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(info.Env(), static_cast<double>($fn(static_cast<float>(info[0].As<Napi::Number>().DoubleValue()))));
    }));
                                """.trim(),
                            )
                        }
                    spec.secondIsInt ->
                        if (spec.isF64) {
                            appendLine(
                                """
    coreObj.Set("$fn", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(info.Env(), $fn(info[0].As<Napi::Number>().DoubleValue(), kmCoreNapiArgI32(info[1])));
    }));
                                """.trim(),
                            )
                        } else {
                            appendLine(
                                """
    coreObj.Set("$fn", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(info.Env(), static_cast<double>($fn(static_cast<float>(info[0].As<Napi::Number>().DoubleValue()), kmCoreNapiArgI32(info[1]))));
    }));
                                """.trim(),
                            )
                        }
                    else ->
                        if (spec.isF64) {
                            appendLine(
                                """
    coreObj.Set("$fn", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(info.Env(), $fn(info[0].As<Napi::Number>().DoubleValue(), info[1].As<Napi::Number>().DoubleValue()));
    }));
                                """.trim(),
                            )
                        } else {
                            appendLine(
                                """
    coreObj.Set("$fn", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(info.Env(), static_cast<double>($fn(static_cast<float>(info[0].As<Napi::Number>().DoubleValue()), static_cast<float>(info[1].As<Napi::Number>().DoubleValue()))));
    }));
                                """.trim(),
                            )
                        }
                }
            }
            appendLine(
                """
    coreObj.Set("kmFloatToU64Unchecked", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return kmCoreReturnU64LoHi(info, kmFloatToU64Unchecked(static_cast<float>(info[0].As<Napi::Number>().DoubleValue())));
    }));
    coreObj.Set("kmDoubleToU64Unchecked", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return kmCoreReturnU64LoHi(info, kmDoubleToU64Unchecked(info[0].As<Napi::Number>().DoubleValue()));
    }));
${renderNapiU128Pair("kmFloatToU128Unchecked", "kmFloatToU128UncheckedOut(static_cast<float>(info[0].As<Napi::Number>().DoubleValue()), &outLo, &outHi)")}
${renderNapiU128Pair("kmDoubleToU128Unchecked", "kmDoubleToU128UncheckedOut(info[0].As<Napi::Number>().DoubleValue(), &outLo, &outHi)")}
                """.trim(),
            )
        }

    private fun renderNapiU128Pair(jsName: String, cCall: String): String =
        """
    coreObj.Set("$jsName", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        uint64_t outLo = 0ULL, outHi = 0ULL;
        $cCall;
        kmCoreStoreU64Scratch(0, outLo);
        kmCoreStoreU64Scratch(2, outHi);
        return Napi::Number::New(info.Env(), g_kmCoreOutScratch[0]);
    }));
        """.trim()

    public fun renderJsActuals(): String = renderJsOrWasmActuals(isWasmJs = false)

    public fun renderWasmJsActuals(): String = renderJsOrWasmActuals(isWasmJs = true)

    public fun renderWasmJsFunDeclarations(): String =
        buildString {
            for (spec in UnaryBitKind.entries) {
                val fn = spec.expectName
                if (spec.width == 128) {
                    appendLine("@JsFun(\"(lo0, hi0, lo1, hi1) => globalThis.kmCoreNapiV2.$fn(lo0, hi0, lo1, hi1)\")")
                    appendLine("private external fun wasmKm${fn.removePrefix("km")}(lo0: Int, hi0: Int, lo1: Int, hi1: Int): Int")
                } else {
                    appendLine("@JsFun(\"(v) => globalThis.kmCoreNapiV2.$fn(v)\")")
                    appendLine("private external fun wasmKm${fn.removePrefix("km")}(v: Int): Int")
                }
            }
            for (spec in ROTATE_SPECS) {
                val fn = spec.expectName
                if (spec.width == 128) {
                    appendLine("@JsFun(\"(lo0, hi0, lo1, hi1, n) => globalThis.kmCoreNapiV2.$fn(lo0, hi0, lo1, hi1, n)\")")
                    appendLine("private external fun wasmKm${fn.removePrefix("km")}(lo0: Int, hi0: Int, lo1: Int, hi1: Int, n: Int): Int")
                } else if (spec.width == 64) {
                    appendLine("@JsFun(\"(lo, hi, n) => globalThis.kmCoreNapiV2.$fn(lo, hi, n)\")")
                    appendLine("private external fun wasmKm${fn.removePrefix("km")}(lo: Int, hi: Int, n: Int): Int")
                } else {
                    appendLine("@JsFun(\"(v, n) => globalThis.kmCoreNapiV2.$fn(v, n)\")")
                    appendLine("private external fun wasmKm${fn.removePrefix("km")}(v: Int, n: Int): Int")
                }
            }
            for (op in OverflowOpKind.entries) {
                for (w in ALL_INT_WIDTHS) {
                    val fn = "km${op.stem}WithOverflow${w.widthTag}"
                    when {
                        w.bits < 64 -> {
                            appendLine("@JsFun(\"(a, b) => globalThis.kmCoreNapiV2.$fn(a, b)\")")
                            appendLine("private external fun wasmKm${fn.removePrefix("km")}(a: Int, b: Int): Int")
                        }
                        w.bits == 64 -> {
                            appendLine("@JsFun(\"(aLo, aHi, bLo, bHi) => globalThis.kmCoreNapiV2.$fn(aLo, aHi, bLo, bHi)\")")
                            appendLine("private external fun wasmKm${fn.removePrefix("km")}(aLo: Int, aHi: Int, bLo: Int, bHi: Int): Int")
                        }
                        else -> {
                            appendLine("@JsFun(\"(a0, a1, a2, a3, b0, b1, b2, b3) => globalThis.kmCoreNapiV2.$fn(a0, a1, a2, a3, b0, b1, b2, b3)\")")
                            appendLine("private external fun wasmKm${fn.removePrefix("km")}(a0: Int, a1: Int, a2: Int, a3: Int, b0: Int, b1: Int, b2: Int, b3: Int): Int")
                        }
                    }
                }
            }
            for (op in listOf(OverflowOpKind.ADD, OverflowOpKind.SUB)) {
                for (w in ALL_INT_WIDTHS) {
                    val fn = "kmSaturating${op.stem}${w.widthTag}"
                    when {
                        w.bits < 64 -> {
                            appendLine("@JsFun(\"(a, b) => globalThis.kmCoreNapiV2.$fn(a, b)\")")
                            appendLine("private external fun wasmKm${fn.removePrefix("km")}(a: Int, b: Int): Int")
                        }
                        w.bits == 64 -> {
                            appendLine("@JsFun(\"(aLo, aHi, bLo, bHi) => globalThis.kmCoreNapiV2.$fn(aLo, aHi, bLo, bHi)\")")
                            appendLine("private external fun wasmKm${fn.removePrefix("km")}(aLo: Int, aHi: Int, bLo: Int, bHi: Int): Int")
                        }
                        else -> {
                            appendLine("@JsFun(\"(a0, a1, a2, a3, b0, b1, b2, b3) => globalThis.kmCoreNapiV2.$fn(a0, a1, a2, a3, b0, b1, b2, b3)\")")
                            appendLine("private external fun wasmKm${fn.removePrefix("km")}(a0: Int, a1: Int, a2: Int, a3: Int, b0: Int, b1: Int, b2: Int, b3: Int): Int")
                        }
                    }
                }
            }
            for ((fn, _) in U128_VALUE_BINOPS) {
                appendLine("@JsFun(\"(a0, a1, a2, a3, b0, b1, b2, b3) => globalThis.kmCoreNapiV2.$fn(a0, a1, a2, a3, b0, b1, b2, b3)\")")
                appendLine("private external fun wasmKm${fn.removePrefix("km")}(a0: Int, a1: Int, a2: Int, a3: Int, b0: Int, b1: Int, b2: Int, b3: Int): Int")
            }
            for ((fn, _) in U128_DIVREM_OPS) {
                appendLine("@JsFun(\"(a0, a1, a2, a3, b0, b1, b2, b3) => globalThis.kmCoreNapiV2.$fn(a0, a1, a2, a3, b0, b1, b2, b3)\")")
                appendLine("private external fun wasmKm${fn.removePrefix("km")}(a0: Int, a1: Int, a2: Int, a3: Int, b0: Int, b1: Int, b2: Int, b3: Int): Int")
            }
            for ((fn, _, _) in U128_SHIFT_OPS) {
                appendLine("@JsFun(\"(lo0, hi0, lo1, hi1, s) => globalThis.kmCoreNapiV2.$fn(lo0, hi0, lo1, hi1, s)\")")
                appendLine("private external fun wasmKm${fn.removePrefix("km")}(lo0: Int, hi0: Int, lo1: Int, hi1: Int, s: Int): Int")
            }
            for (spec in FLOAT_MATH_SPECS) {
                val fn = spec.expectName
                when {
                    spec.arity == 1 -> {
                        appendLine("@JsFun(\"(x) => globalThis.kmCoreNapiV2.$fn(x)\")")
                        appendLine("private external fun wasmKm${fn.removePrefix("km")}(x: Double): Double")
                    }
                    spec.secondIsInt -> {
                        appendLine("@JsFun(\"(x, e) => globalThis.kmCoreNapiV2.$fn(x, e)\")")
                        appendLine("private external fun wasmKm${fn.removePrefix("km")}(x: Double, e: Int): Double")
                    }
                    else -> {
                        appendLine("@JsFun(\"(a, b) => globalThis.kmCoreNapiV2.$fn(a, b)\")")
                        appendLine("private external fun wasmKm${fn.removePrefix("km")}(a: Double, b: Double): Double")
                    }
                }
            }
            appendLine(
                """
@JsFun("(v) => globalThis.kmCoreNapiV2.kmFloatToU64Unchecked(v)")
private external fun wasmKmFloatToU64Unchecked(v: Double): Int
@JsFun("(v) => globalThis.kmCoreNapiV2.kmDoubleToU64Unchecked(v)")
private external fun wasmKmDoubleToU64Unchecked(v: Double): Int
@JsFun("(v) => globalThis.kmCoreNapiV2.kmFloatToU128Unchecked(v)")
private external fun wasmKmFloatToU128Unchecked(v: Double): Int
@JsFun("(v) => globalThis.kmCoreNapiV2.kmDoubleToU128Unchecked(v)")
private external fun wasmKmDoubleToU128Unchecked(v: Double): Int
                """.trim(),
            )
        }

    private fun renderJsOrWasmActuals(isWasmJs: Boolean): String =
        buildString {
            val guard = if (isWasmJs) "kmCoreNapiAvailable" else "kmCoreNapiAddon != null"
            fun out(idx: Int): String = if (isWasmJs) "wasmKmOut$idx()" else "(kmCoreNapiAddon.kmOut$idx() as Int)"
            fun jsIntCall(fn: String, args: String): String =
                if (isWasmJs) "wasmKm${fn.removePrefix("km")}($args)" else "(kmCoreNapiAddon.$fn($args) as Int)"
            fun jsDoubleCall(fn: String, args: String): String =
                if (isWasmJs) "wasmKm${fn.removePrefix("km")}($args)" else "(kmCoreNapiAddon.$fn($args) as Double)"

            for (spec in UnaryBitKind.entries) {
                val fn = spec.expectName
                val arenaFn = fn.removePrefix("km").replaceFirstChar { it.lowercase() }
                when {
                    spec.width == 128 && spec.returns128 ->
                        appendLine(
                            """
public actual fun $fn(lo: ULong, hi: ULong): U128ValueOutcome =
    if ($guard) {
        val rLo0 = ${jsIntCall(fn, "uLo32(lo), uHi32(lo), uLo32(hi), uHi32(hi)")}
        U128ValueOutcome(packU64(rLo0, ${out(1)}), packU64(${out(2)}, ${out(3)}))
    } else {
        KmLinearMemoryArena.$arenaFn(lo, hi)
    }
                            """.trim(),
                        )
                    spec.width == 128 ->
                        appendLine(
                            """
public actual fun $fn(lo: ULong, hi: ULong): UInt =
    if ($guard) ${jsIntCall(fn, "uLo32(lo), uHi32(lo), uLo32(hi), uHi32(hi)")}.toUInt() else KmLinearMemoryArena.$arenaFn(lo, hi)
                            """.trim(),
                        )
                    else -> {
                        val kt = smallWidthKtReturnFromU32(spec.width)
                        val conv = if (spec.returnsUInt) ".toUInt()" else jvmToUnsignedRetConv(spec.width)
                        val retKt = if (spec.returnsUInt) "UInt" else kt
                        appendLine(
                            """
public actual fun $fn(value: $kt): $retKt =
    if ($guard) ${jsIntCall(fn, "value.toInt()")}$conv else KmLinearMemoryArena.$arenaFn(value)
                            """.trim(),
                        )
                    }
                }
            }
            for (spec in ROTATE_SPECS) {
                val fn = spec.expectName
                val arenaFn = fn.removePrefix("km").replaceFirstChar { it.lowercase() }
                when (spec.width) {
                    128 ->
                        appendLine(
                            """
public actual fun $fn(lo: ULong, hi: ULong, n: UInt): U128ValueOutcome =
    if ($guard) {
        val rLo0 = ${jsIntCall(fn, "uLo32(lo), uHi32(lo), uLo32(hi), uHi32(hi), n.toInt()")}
        U128ValueOutcome(packU64(rLo0, ${out(1)}), packU64(${out(2)}, ${out(3)}))
    } else {
        KmLinearMemoryArena.$arenaFn(lo, hi, n)
    }
                            """.trim(),
                        )
                    64 ->
                        appendLine(
                            """
public actual fun $fn(value: ULong, n: UInt): ULong =
    if ($guard) {
        val lo = ${jsIntCall(fn, "uLo32(value), uHi32(value), n.toInt()")}
        packU64(lo, ${out(1)})
    } else {
        KmLinearMemoryArena.$arenaFn(value, n)
    }
                            """.trim(),
                        )
                    else -> {
                        val kt = smallWidthKtReturnFromU32(spec.width)
                        val retConv = jvmToUnsignedRetConv(spec.width)
                        appendLine(
                            """
public actual fun $fn(value: $kt, n: UInt): $kt =
    if ($guard) ${jsIntCall(fn, "value.toInt(), n.toInt()")}$retConv else KmLinearMemoryArena.$arenaFn(value, n)
                            """.trim(),
                        )
                    }
                }
            }
            for (op in OverflowOpKind.entries) {
                for (w in ALL_INT_WIDTHS) {
                    val fn = "km${op.stem}WithOverflow${w.widthTag}"
                    val arenaFn = fn.removePrefix("km").replaceFirstChar { it.lowercase() }
                    when {
                        w.bits < 64 -> {
                            val aArg = if (w.bits == 32 && w.signed) "a" else "a.toInt()"
                            val bArg = if (w.bits == 32 && w.signed) "b" else "b.toInt()"
                            val vConv = if (w.bits == 32 && w.signed) "v" else "v${w.u32WrapSuffix}"
                            appendLine(
                                """
public actual fun $fn(a: ${w.ktType}, b: ${w.ktType}): Overflow${w.widthTag}Outcome =
    if ($guard) {
        val v = ${jsIntCall(fn, "$aArg, $bArg")}
        Overflow${w.widthTag}Outcome($vConv, ${out(1)} != 0)
    } else {
        KmLinearMemoryArena.$arenaFn(a, b)
    }
                                """.trim(),
                            )
                        }
                        w.bits == 64 -> {
                            val loFn = if (w.signed) "lo32" else "uLo32"
                            val hiFn = if (w.signed) "hi32" else "uHi32"
                            val packFn = if (w.signed) "packI64" else "packU64"
                            appendLine(
                                """
public actual fun $fn(a: ${w.ktType}, b: ${w.ktType}): Overflow${w.widthTag}Outcome =
    if ($guard) {
        val ovf = ${jsIntCall(fn, "$loFn(a), $hiFn(a), $loFn(b), $hiFn(b)")} != 0
        Overflow${w.widthTag}Outcome($packFn(${out(0)}, ${out(1)}), ovf)
    } else {
        KmLinearMemoryArena.$arenaFn(a, b)
    }
                                """.trim(),
                            )
                        }
                        else -> {
                            appendLine(
                                """
public actual fun $fn(aLo: ULong, aHi: ULong, bLo: ULong, bHi: ULong): Overflow${w.widthTag}Outcome =
    if ($guard) {
        val ovf = ${jsIntCall(fn, "uLo32(aLo), uHi32(aLo), uLo32(aHi), uHi32(aHi), uLo32(bLo), uHi32(bLo), uLo32(bHi), uHi32(bHi)")} != 0
        Overflow${w.widthTag}Outcome(packU64(${out(0)}, ${out(1)}), packU64(${out(2)}, ${out(3)}), ovf)
    } else {
        KmLinearMemoryArena.$arenaFn(aLo, aHi, bLo, bHi)
    }
                                """.trim(),
                            )
                        }
                    }
                }
            }
            for (op in listOf(OverflowOpKind.ADD, OverflowOpKind.SUB)) {
                for (w in ALL_INT_WIDTHS) {
                    val fn = "kmSaturating${op.stem}${w.widthTag}"
                    val arenaFn = fn.removePrefix("km").replaceFirstChar { it.lowercase() }
                    when {
                        w.bits < 64 -> {
                            val aArg = if (w.bits == 32 && w.signed) "a" else "a.toInt()"
                            val bArg = if (w.bits == 32 && w.signed) "b" else "b.toInt()"
                            val rConv = if (w.bits == 32 && w.signed) "" else w.u32WrapSuffix
                            appendLine(
                                """
public actual fun $fn(a: ${w.ktType}, b: ${w.ktType}): ${w.ktType} =
    if ($guard) ${jsIntCall(fn, "$aArg, $bArg")}$rConv else KmLinearMemoryArena.$arenaFn(a, b)
                                """.trim(),
                            )
                        }
                        w.bits == 64 -> {
                            val loFn = if (w.signed) "lo32" else "uLo32"
                            val hiFn = if (w.signed) "hi32" else "uHi32"
                            val packFn = if (w.signed) "packI64" else "packU64"
                            appendLine(
                                """
public actual fun $fn(a: ${w.ktType}, b: ${w.ktType}): ${w.ktType} =
    if ($guard) {
        val lo = ${jsIntCall(fn, "$loFn(a), $hiFn(a), $loFn(b), $hiFn(b)")}
        $packFn(lo, ${out(1)})
    } else {
        KmLinearMemoryArena.$arenaFn(a, b)
    }
                                """.trim(),
                            )
                        }
                        else ->
                            appendLine(
                                """
public actual fun $fn(aLo: ULong, aHi: ULong, bLo: ULong, bHi: ULong): ${w.ktType} =
    if ($guard) {
        val rLo0 = ${jsIntCall(fn, "uLo32(aLo), uHi32(aLo), uLo32(aHi), uHi32(aHi), uLo32(bLo), uHi32(bLo), uLo32(bHi), uHi32(bHi)")}
        ${w.ktType}(packU64(rLo0, ${out(1)}), packU64(${out(2)}, ${out(3)}))
    } else {
        KmLinearMemoryArena.$arenaFn(aLo, aHi, bLo, bHi)
    }
                                """.trim(),
                            )
                    }
                }
            }
            for ((fn, _) in U128_VALUE_BINOPS) {
                val arenaFn = fn.removePrefix("km").replaceFirstChar { it.lowercase() }
                appendLine(
                    """
public actual fun $fn(aLo: ULong, aHi: ULong, bLo: ULong, bHi: ULong): U128ValueOutcome =
    if ($guard) {
        val rLo0 = ${jsIntCall(fn, "uLo32(aLo), uHi32(aLo), uLo32(aHi), uHi32(aHi), uLo32(bLo), uHi32(bLo), uLo32(bHi), uHi32(bHi)")}
        U128ValueOutcome(packU64(rLo0, ${out(1)}), packU64(${out(2)}, ${out(3)}))
    } else {
        KmLinearMemoryArena.$arenaFn(aLo, aHi, bLo, bHi)
    }
                    """.trim(),
                )
            }
            for ((fn, outType) in U128_DIVREM_OPS) {
                val arenaFn = fn.removePrefix("km").replaceFirstChar { it.lowercase() }
                appendLine(
                    """
public actual fun $fn(aLo: ULong, aHi: ULong, bLo: ULong, bHi: ULong): $outType =
    if ($guard) {
        val qLo0 = ${jsIntCall(fn, "uLo32(aLo), uHi32(aLo), uLo32(aHi), uHi32(aHi), uLo32(bLo), uHi32(bLo), uLo32(bHi), uHi32(bHi)")}
        $outType(packU64(qLo0, ${out(1)}), packU64(${out(2)}, ${out(3)}), packU64(${out(4)}, ${out(5)}), packU64(${out(6)}, ${out(7)}))
    } else {
        KmLinearMemoryArena.$arenaFn(aLo, aHi, bLo, bHi)
    }
                    """.trim(),
                )
            }
            for ((fn, outType, _) in U128_SHIFT_OPS) {
                val arenaFn = fn.removePrefix("km").replaceFirstChar { it.lowercase() }
                appendLine(
                    """
public actual fun $fn(aLo: ULong, aHi: ULong, shift: UInt): $outType =
    if ($guard) {
        val rLo0 = ${jsIntCall(fn, "uLo32(aLo), uHi32(aLo), uLo32(aHi), uHi32(aHi), shift.toInt()")}
        $outType(packU64(rLo0, ${out(1)}), packU64(${out(2)}, ${out(3)}))
    } else {
        KmLinearMemoryArena.$arenaFn(aLo, aHi, shift)
    }
                    """.trim(),
                )
            }
            for (spec in FLOAT_MATH_SPECS) {
                val fn = spec.expectName
                val arenaFn = fn.removePrefix("km").replaceFirstChar { it.lowercase() }
                val kt = if (spec.isF64) "Double" else "Float"
                val retCast = if (spec.isF64) "" else ".toFloat()"
                val argSuffix = if (spec.isF64) "" else ".toDouble()"
                when {
                    spec.arity == 1 ->
                        appendLine(
                            """
public actual fun $fn(x: $kt): $kt =
    if ($guard) ${jsDoubleCall(fn, "x$argSuffix")}$retCast else KmLinearMemoryArena.$arenaFn(x)
                            """.trim(),
                        )
                    spec.secondIsInt ->
                        appendLine(
                            """
public actual fun $fn(x: $kt, exp: Int): $kt =
    if ($guard) ${jsDoubleCall(fn, "x$argSuffix, exp")}$retCast else KmLinearMemoryArena.$arenaFn(x, exp)
                            """.trim(),
                        )
                    else ->
                        appendLine(
                            """
public actual fun $fn(a: $kt, b: $kt): $kt =
    if ($guard) ${jsDoubleCall(fn, "a$argSuffix, b$argSuffix")}$retCast else KmLinearMemoryArena.$arenaFn(a, b)
                            """.trim(),
                        )
                }
            }
            appendLine(
                """
public actual fun kmFloatToU64Unchecked(v: Float): ULong =
    if ($guard) {
        val lo = ${jsIntCall("kmFloatToU64Unchecked", "v.toDouble()")}
        packU64(lo, ${out(1)})
    } else {
        KmLinearMemoryArena.floatToU64Unchecked(v)
    }
public actual fun kmDoubleToU64Unchecked(v: Double): ULong =
    if ($guard) {
        val lo = ${jsIntCall("kmDoubleToU64Unchecked", "v")}
        packU64(lo, ${out(1)})
    } else {
        KmLinearMemoryArena.doubleToU64Unchecked(v)
    }
public actual fun kmFloatToU128Unchecked(v: Float): U128ValueOutcome =
    if ($guard) {
        val rLo0 = ${jsIntCall("kmFloatToU128Unchecked", "v.toDouble()")}
        U128ValueOutcome(packU64(rLo0, ${out(1)}), packU64(${out(2)}, ${out(3)}))
    } else {
        KmLinearMemoryArena.floatToU128Unchecked(v)
    }
public actual fun kmDoubleToU128Unchecked(v: Double): U128ValueOutcome =
    if ($guard) {
        val rLo0 = ${jsIntCall("kmDoubleToU128Unchecked", "v")}
        U128ValueOutcome(packU64(rLo0, ${out(1)}), packU64(${out(2)}, ${out(3)}))
    } else {
        KmLinearMemoryArena.doubleToU128Unchecked(v)
    }
                """.trim(),
            )
        }

    public fun renderWasmWasiActuals(): String =
        buildString {
            for (spec in UnaryBitKind.entries) {
                val fn = spec.expectName
                val arenaFn = fn.removePrefix("km").replaceFirstChar { it.lowercase() }
                when {
                    spec.width == 128 && spec.returns128 ->
                        appendLine("public actual fun $fn(lo: ULong, hi: ULong): U128ValueOutcome = KmLinearMemoryArena.$arenaFn(lo, hi)")
                    spec.width == 128 ->
                        appendLine("public actual fun $fn(lo: ULong, hi: ULong): UInt = KmLinearMemoryArena.$arenaFn(lo, hi)")
                    else -> {
                        val kt = smallWidthKtReturnFromU32(spec.width)
                        val retKt = if (spec.returnsUInt) "UInt" else kt
                        appendLine("public actual fun $fn(value: $kt): $retKt = KmLinearMemoryArena.$arenaFn(value)")
                    }
                }
            }
            for (spec in ROTATE_SPECS) {
                val fn = spec.expectName
                val arenaFn = fn.removePrefix("km").replaceFirstChar { it.lowercase() }
                if (spec.width == 128) {
                    appendLine("public actual fun $fn(lo: ULong, hi: ULong, n: UInt): U128ValueOutcome = KmLinearMemoryArena.$arenaFn(lo, hi, n)")
                } else {
                    val kt = smallWidthKtReturnFromU32(spec.width)
                    appendLine("public actual fun $fn(value: $kt, n: UInt): $kt = KmLinearMemoryArena.$arenaFn(value, n)")
                }
            }
            for (op in OverflowOpKind.entries) {
                for (w in ALL_INT_WIDTHS) {
                    val fn = "km${op.stem}WithOverflow${w.widthTag}"
                    val arenaFn = fn.removePrefix("km").replaceFirstChar { it.lowercase() }
                    if (w.bits < 128) {
                        appendLine("public actual fun $fn(a: ${w.ktType}, b: ${w.ktType}): Overflow${w.widthTag}Outcome = KmLinearMemoryArena.$arenaFn(a, b)")
                    } else {
                        appendLine("public actual fun $fn(aLo: ULong, aHi: ULong, bLo: ULong, bHi: ULong): Overflow${w.widthTag}Outcome = KmLinearMemoryArena.$arenaFn(aLo, aHi, bLo, bHi)")
                    }
                }
            }
            for (op in listOf(OverflowOpKind.ADD, OverflowOpKind.SUB)) {
                for (w in ALL_INT_WIDTHS) {
                    val fn = "kmSaturating${op.stem}${w.widthTag}"
                    val arenaFn = fn.removePrefix("km").replaceFirstChar { it.lowercase() }
                    if (w.bits < 128) {
                        appendLine("public actual fun $fn(a: ${w.ktType}, b: ${w.ktType}): ${w.ktType} = KmLinearMemoryArena.$arenaFn(a, b)")
                    } else {
                        appendLine("public actual fun $fn(aLo: ULong, aHi: ULong, bLo: ULong, bHi: ULong): ${w.ktType} = KmLinearMemoryArena.$arenaFn(aLo, aHi, bLo, bHi)")
                    }
                }
            }
            for ((fn, _) in U128_VALUE_BINOPS) {
                val arenaFn = fn.removePrefix("km").replaceFirstChar { it.lowercase() }
                appendLine("public actual fun $fn(aLo: ULong, aHi: ULong, bLo: ULong, bHi: ULong): U128ValueOutcome = KmLinearMemoryArena.$arenaFn(aLo, aHi, bLo, bHi)")
            }
            for ((fn, outType) in U128_DIVREM_OPS) {
                val arenaFn = fn.removePrefix("km").replaceFirstChar { it.lowercase() }
                appendLine("public actual fun $fn(aLo: ULong, aHi: ULong, bLo: ULong, bHi: ULong): $outType = KmLinearMemoryArena.$arenaFn(aLo, aHi, bLo, bHi)")
            }
            for ((fn, outType, _) in U128_SHIFT_OPS) {
                val arenaFn = fn.removePrefix("km").replaceFirstChar { it.lowercase() }
                appendLine("public actual fun $fn(aLo: ULong, aHi: ULong, shift: UInt): $outType = KmLinearMemoryArena.$arenaFn(aLo, aHi, shift)")
            }
            for (spec in FLOAT_MATH_SPECS) {
                val fn = spec.expectName
                val arenaFn = fn.removePrefix("km").replaceFirstChar { it.lowercase() }
                val kt = if (spec.isF64) "Double" else "Float"
                when {
                    spec.arity == 1 -> appendLine("public actual fun $fn(x: $kt): $kt = KmLinearMemoryArena.$arenaFn(x)")
                    spec.secondIsInt -> appendLine("public actual fun $fn(x: $kt, exp: Int): $kt = KmLinearMemoryArena.$arenaFn(x, exp)")
                    else -> appendLine("public actual fun $fn(a: $kt, b: $kt): $kt = KmLinearMemoryArena.$arenaFn(a, b)")
                }
            }
            appendLine(
                """
public actual fun kmFloatToU64Unchecked(v: Float): ULong = KmLinearMemoryArena.floatToU64Unchecked(v)
public actual fun kmDoubleToU64Unchecked(v: Double): ULong = KmLinearMemoryArena.doubleToU64Unchecked(v)
public actual fun kmFloatToU128Unchecked(v: Float): U128ValueOutcome = KmLinearMemoryArena.floatToU128Unchecked(v)
public actual fun kmDoubleToU128Unchecked(v: Double): U128ValueOutcome = KmLinearMemoryArena.doubleToU128Unchecked(v)
                """.trim(),
            )
        }
}
