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
        val u32WrapSuffix: String,
    ) {
        U8("U8", 8, false, "uint8_t", "UByte", "jbyte", "Byte", ".toUByte()"),
        U16("U16", 16, false, "uint16_t", "UShort", "jshort", "Short", ".toUShort()"),
        U32("U32", 32, false, "uint32_t", "UInt", "jint", "Int", ".toUInt()"),
        U64("U64", 64, false, "uint64_t", "ULong", "jlong", "Long", ".toULong()"),
        U128("U128", 128, false, "uint64_t", "U128ValueOutcome", "jlong", "Long", ""),
        I8("I8", 8, true, "int8_t", "Byte", "jbyte", "Byte", ".toByte()"),
        I16("I16", 16, true, "int16_t", "Short", "jshort", "Short", ".toShort()"),
        I32("I32", 32, true, "int32_t", "Int", "jint", "Int", ""),
        I64("I64", 64, true, "int64_t", "Long", "jlong", "Long", ""),
        I128("I128", 128, true, "int64_t", "I128ValueOutcome", "jlong", "Long", ""),
    }

    internal enum class OverflowOpKind(
        val stem: String,
        val builtinName: String,
    ) {
        ADD("Add", "__builtin_add_overflow"),
        SUB("Sub", "__builtin_sub_overflow"),
        MUL("Mul", "__builtin_mul_overflow"),
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

    private fun smallWidthCType(width: Int): String =
        when (width) {
            8 -> "uint8_t"
            16 -> "uint16_t"
            32 -> "uint32_t"
            64 -> "uint64_t"
            else -> "uint64_t"
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
#if defined(__SIZEOF_INT128__) && !defined(_MSC_VER)
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

    private fun renderUnaryBitC(spec: UnaryBitKind): String {
        val ct = smallWidthCType(spec.width)
        return when (spec) {
            UnaryBitKind.CTPOP_U8, UnaryBitKind.CTPOP_U16, UnaryBitKind.CTPOP_U32 ->
                "KM_ALWAYS_INLINE uint32_t ${spec.expectName}($ct v) { return (uint32_t)kmCtpopU64((uint64_t)v); }"
            UnaryBitKind.CTPOP_U128 ->
                "KM_ALWAYS_INLINE uint32_t kmCtpopU128(uint64_t lo, uint64_t hi) { return kmCtpopU64(lo) + kmCtpopU64(hi); }"
            UnaryBitKind.CTLZ_U8, UnaryBitKind.CTLZ_U16, UnaryBitKind.CTLZ_U32 -> {
                val clzBias = if (spec.width < 32) " - ${32 - spec.width}u" else ""
                val castV = if (spec.width < 32) "(unsigned int)v" else "v"
                """
KM_ALWAYS_INLINE uint32_t ${spec.expectName}($ct v) {
    if (v == 0u) return ${spec.width}u;
#if defined(__clang__) || defined(__GNUC__)
    return (uint32_t)__builtin_clz($castV)$clzBias;
#else
    return kmCtlzU64((uint64_t)v) - ${64 - spec.width}u;
#endif
}
                """.trim()
            }
            UnaryBitKind.CTLZ_U128 ->
                "KM_ALWAYS_INLINE uint32_t kmCtlzU128(uint64_t lo, uint64_t hi) { return hi != 0ULL ? kmCtlzU64(hi) : (64u + kmCtlzU64(lo)); }"
            UnaryBitKind.CTTZ_U8, UnaryBitKind.CTTZ_U16, UnaryBitKind.CTTZ_U32 -> {
                val castV = if (spec.width < 32) "(unsigned int)v" else "v"
                """
KM_ALWAYS_INLINE uint32_t ${spec.expectName}($ct v) {
    if (v == 0u) return ${spec.width}u;
#if defined(__clang__) || defined(__GNUC__)
    return (uint32_t)__builtin_ctz($castV);
#else
    return kmCttzU64((uint64_t)v);
#endif
}
                """.trim()
            }
            UnaryBitKind.CTTZ_U128 ->
                "KM_ALWAYS_INLINE uint32_t kmCttzU128(uint64_t lo, uint64_t hi) { return lo != 0ULL ? kmCttzU64(lo) : (64u + kmCttzU64(hi)); }"
            UnaryBitKind.BSWAP_U128 ->
                "KM_ALWAYS_INLINE void kmBswapU128Out(uint64_t lo, uint64_t hi, uint64_t* outLo, uint64_t* outHi) { *outLo = kmBswapU64(hi); *outHi = kmBswapU64(lo); }"
            UnaryBitKind.BITREVERSE_U8, UnaryBitKind.BITREVERSE_U16, UnaryBitKind.BITREVERSE_U32 ->
                """
KM_ALWAYS_INLINE $ct ${spec.expectName}($ct v) {
#if defined(__clang__)
    return __builtin_bitreverse${spec.width}(v);
#else
    return ($ct)(kmBitreverseU64((uint64_t)v) >> ${64 - spec.width});
#endif
}
                """.trim()
            UnaryBitKind.BITREVERSE_U128 ->
                "KM_ALWAYS_INLINE void kmBitreverseU128Out(uint64_t lo, uint64_t hi, uint64_t* outLo, uint64_t* outHi) { *outLo = kmBitreverseU64(hi); *outHi = kmBitreverseU64(lo); }"
        }
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
#if defined(__SIZEOF_INT128__) && !defined(_MSC_VER)
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
#if defined(__SIZEOF_INT128__) && !defined(_MSC_VER)
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

    private fun scalarTypeForUnsignedWidth(width: Int): KmScalarType =
        when (width) {
            8 -> KmScalarType.U8
            16 -> KmScalarType.U16
            32 -> KmScalarType.U32
            else -> KmScalarType.U64
        }

    private fun scalarTypeForIntWidth(w: IntWidthKind): KmScalarType =
        when (w) {
            IntWidthKind.U8 -> KmScalarType.U8
            IntWidthKind.U16 -> KmScalarType.U16
            IntWidthKind.U32 -> KmScalarType.U32
            IntWidthKind.U64 -> KmScalarType.U64
            IntWidthKind.I8 -> KmScalarType.I8
            IntWidthKind.I16 -> KmScalarType.I16
            IntWidthKind.I32 -> KmScalarType.I32
            IntWidthKind.I64 -> KmScalarType.I64
            IntWidthKind.U128, IntWidthKind.I128 -> error("128-bit uses U128Out2")
        }

    internal val MATH_AND_BIT_SPECS: List<KmBaseIntrinsicSpec> =
        buildList {
            for (spec in UnaryBitKind.entries) {
                val fn = spec.expectName
                val arenaFn = fn.removePrefix("km").replaceFirstChar { it.lowercase() }
                when {
                    spec.width == 128 && spec.returns128 ->
                        add(KmBaseIntrinsicSpec.U128Out2(fn, listOf("lo" to KmScalarType.U64, "hi" to KmScalarType.U64), "U128ValueOutcome", "KmLinearMemoryArena.$arenaFn(lo, hi)"))
                    spec.width == 128 ->
                        add(KmBaseIntrinsicSpec.Regular(fn, listOf("lo" to KmScalarType.U64, "hi" to KmScalarType.U64), KmScalarType.U32, "KmLinearMemoryArena.$arenaFn(lo, hi)"))
                    else -> {
                        val valTy = scalarTypeForUnsignedWidth(spec.width)
                        val retTy = if (spec.returnsUInt) KmScalarType.U32 else valTy
                        add(KmBaseIntrinsicSpec.Regular(fn, listOf("value" to valTy), retTy, "KmLinearMemoryArena.$arenaFn(value)"))
                    }
                }
            }
            for (spec in ROTATE_SPECS) {
                val fn = spec.expectName
                val arenaFn = fn.removePrefix("km").replaceFirstChar { it.lowercase() }
                if (spec.width == 128) {
                    add(
                        KmBaseIntrinsicSpec.U128Out2(
                            name = fn,
                            params = listOf("lo" to KmScalarType.U64, "hi" to KmScalarType.U64, "n" to KmScalarType.U32),
                            outcomeType = "U128ValueOutcome",
                            arenaExpr = "KmLinearMemoryArena.$arenaFn(lo, hi, n)",
                        ),
                    )
                } else {
                    val valTy = scalarTypeForUnsignedWidth(spec.width)
                    add(KmBaseIntrinsicSpec.Regular(fn, listOf("value" to valTy, "n" to KmScalarType.U32), valTy, "KmLinearMemoryArena.$arenaFn(value, n)"))
                }
            }
            for (op in OverflowOpKind.entries) {
                for (w in ALL_INT_WIDTHS) {
                    add(KmBaseIntrinsicSpec.WithOverflow(op.stem, w))
                }
            }
            for (op in listOf(OverflowOpKind.ADD, OverflowOpKind.SUB)) {
                for (w in ALL_INT_WIDTHS) {
                    val fn = "kmSaturating${op.stem}${w.widthTag}"
                    val arenaFn = fn.removePrefix("km").replaceFirstChar { it.lowercase() }
                    if (w.bits < 128) {
                        val st = scalarTypeForIntWidth(w)
                        add(KmBaseIntrinsicSpec.Regular(fn, listOf("a" to st, "b" to st), st, "KmLinearMemoryArena.$arenaFn(a, b)"))
                    } else {
                        add(
                            KmBaseIntrinsicSpec.U128Out2(
                                name = fn,
                                params = listOf("aLo" to KmScalarType.U64, "aHi" to KmScalarType.U64, "bLo" to KmScalarType.U64, "bHi" to KmScalarType.U64),
                                outcomeType = w.ktType,
                                arenaExpr = "KmLinearMemoryArena.$arenaFn(aLo, aHi, bLo, bHi)",
                            ),
                        )
                    }
                }
            }
            for ((fn, _) in U128_VALUE_BINOPS) {
                val arenaFn = fn.removePrefix("km").replaceFirstChar { it.lowercase() }
                add(
                    KmBaseIntrinsicSpec.U128Out2(
                        name = fn,
                        params = listOf("aLo" to KmScalarType.U64, "aHi" to KmScalarType.U64, "bLo" to KmScalarType.U64, "bHi" to KmScalarType.U64),
                        outcomeType = "U128ValueOutcome",
                        arenaExpr = "KmLinearMemoryArena.$arenaFn(aLo, aHi, bLo, bHi)",
                    ),
                )
            }
            for ((fn, outType) in U128_DIVREM_OPS) {
                add(KmBaseIntrinsicSpec.U128DivRem(fn, outType))
            }
            for ((fn, outType, _) in U128_SHIFT_OPS) {
                val arenaFn = fn.removePrefix("km").replaceFirstChar { it.lowercase() }
                add(
                    KmBaseIntrinsicSpec.U128Out2(
                        name = fn,
                        params = listOf("aLo" to KmScalarType.U64, "aHi" to KmScalarType.U64, "shift" to KmScalarType.U32),
                        outcomeType = outType,
                        arenaExpr = "KmLinearMemoryArena.$arenaFn(aLo, aHi, shift)",
                    ),
                )
            }
            for (spec in FLOAT_MATH_SPECS) {
                val fn = spec.expectName
                val arenaFn = fn.removePrefix("km").replaceFirstChar { it.lowercase() }
                val fTy = if (spec.isF64) KmScalarType.F64 else KmScalarType.F32
                when {
                    spec.arity == 1 ->
                        add(KmBaseIntrinsicSpec.Regular(fn, listOf("x" to fTy), fTy, "KmLinearMemoryArena.$arenaFn(x)"))
                    spec.secondIsInt ->
                        add(KmBaseIntrinsicSpec.Regular(fn, listOf("x" to fTy, "exp" to KmScalarType.I32), fTy, "KmLinearMemoryArena.$arenaFn(x, exp)"))
                    else ->
                        add(KmBaseIntrinsicSpec.Regular(fn, listOf("a" to fTy, "b" to fTy), fTy, "KmLinearMemoryArena.$arenaFn(a, b)"))
                }
            }
            add(KmBaseIntrinsicSpec.Regular("kmFloatToU64Unchecked", listOf("v" to KmScalarType.F32), KmScalarType.U64, "KmLinearMemoryArena.floatToU64Unchecked(v)"))
            add(KmBaseIntrinsicSpec.Regular("kmDoubleToU64Unchecked", listOf("v" to KmScalarType.F64), KmScalarType.U64, "KmLinearMemoryArena.doubleToU64Unchecked(v)"))
            add(KmBaseIntrinsicSpec.U128Out2("kmFloatToU128Unchecked", listOf("v" to KmScalarType.F32), "U128ValueOutcome", "KmLinearMemoryArena.floatToU128Unchecked(v)"))
            add(KmBaseIntrinsicSpec.U128Out2("kmDoubleToU128Unchecked", listOf("v" to KmScalarType.F64), "U128ValueOutcome", "KmLinearMemoryArena.doubleToU128Unchecked(v)"))
        }

    public fun renderExpectDeclarations(): String =
        KmExpectBridgeEmitter.renderDeclarations(MATH_AND_BIT_SPECS)

    public fun renderNativeActuals(cinteropPackage: String): String =
        KmNativeBridgeEmitter(cinteropPackage).renderDeclarations(MATH_AND_BIT_SPECS)

    public fun renderJniCFunctions(): String =
        KmJniCBridgeEmitter.renderDeclarations(MATH_AND_BIT_SPECS)

    public fun renderJvmExternalDeclarations(): String =
        KmJvmBridgeEmitter("").renderExternalDeclarations(MATH_AND_BIT_SPECS)

    public fun renderJvmActuals(jniObjectName: String): String =
        KmJvmBridgeEmitter(jniObjectName).renderDeclarations(MATH_AND_BIT_SPECS)

    public fun renderNapiRegistrations(): String =
        KmNapiCppBridgeEmitter.renderDeclarations(MATH_AND_BIT_SPECS)

    public fun renderWasmJsFunDeclarations(): String =
        KmJsOrWasmBridgeEmitter(isWasmJs = true).renderWasmJsFunDeclarations(MATH_AND_BIT_SPECS, includeScratchOutGetters = false)

    public fun renderJsActuals(): String =
        KmJsOrWasmBridgeEmitter(isWasmJs = false).renderDeclarations(MATH_AND_BIT_SPECS)

    public fun renderWasmJsActuals(): String =
        KmJsOrWasmBridgeEmitter(isWasmJs = true).renderDeclarations(MATH_AND_BIT_SPECS)

    public fun renderWasmWasiActuals(): String =
        KmWasmWasiBridgeEmitter.renderDeclarations(MATH_AND_BIT_SPECS)
}
