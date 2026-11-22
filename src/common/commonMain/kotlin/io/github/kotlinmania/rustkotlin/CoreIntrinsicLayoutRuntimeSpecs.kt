package io.github.kotlinmania.rustkotlin

internal object CoreIntrinsicLayoutRuntimeSpecs {
    private data class UnaryCountSpec(
        val fnName: String,
        val kmStem: String,
    )

    private data class RotateWithLayoutSpec(
        val fnName: String,
        val kmStem: String,
    )

    private data class WrappingArithSpec(
        val fnName: String,
        val op: String,
        val u128Callee: String,
    )

    private data class SignedWidthArithSpec(
        val fnName: String,
        val kmPrefix: String,
    )

    private val UNARY_COUNT_SPECS: List<UnaryCountSpec> =
        listOf(
            UnaryCountSpec("ctpopWithLayout", "kmCtpop"),
            UnaryCountSpec("ctlzWithLayout", "kmCtlz"),
            UnaryCountSpec("cttzWithLayout", "kmCttz"),
        )

    private val ROTATE_WITH_LAYOUT_SPECS: List<RotateWithLayoutSpec> =
        listOf(
            RotateWithLayoutSpec("rotateLeftWithLayout", "kmRotateLeft"),
            RotateWithLayoutSpec("rotateRightWithLayout", "kmRotateRight"),
        )

    private val WRAPPING_ARITH_SPECS: List<WrappingArithSpec> =
        listOf(
            WrappingArithSpec("wrappingAddWithLayout", "+", "kmU128Add"),
            WrappingArithSpec("wrappingSubWithLayout", "-", "kmU128Sub"),
            WrappingArithSpec("wrappingMulWithLayout", "*", "kmU128Mul"),
        )

    private val SATURATING_ARITH_SPECS: List<SignedWidthArithSpec> =
        listOf(
            SignedWidthArithSpec("saturatingAddWithLayout", "kmSaturatingAdd"),
            SignedWidthArithSpec("saturatingSubWithLayout", "kmSaturatingSub"),
        )

    private val OVERFLOW_ARITH_SPECS: List<SignedWidthArithSpec> =
        listOf(
            SignedWidthArithSpec("addWithOverflowWithLayout", "kmAddWithOverflow"),
            SignedWidthArithSpec("subWithOverflowWithLayout", "kmSubWithOverflow"),
            SignedWidthArithSpec("mulWithOverflowWithLayout", "kmMulWithOverflow"),
        )

    private fun renderUnaryCountFunctions(): String =
        buildString {
            for (spec in UNARY_COUNT_SPECS) {
                append(
                    """
public fun <T> ${spec.fnName}(
    x: T,
    layout: TypeLayout<T>,
): UInt =
    when (layout.sizeBytes) {
        1L -> ${spec.kmStem}U8(layout.toBitsU64(x).toUByte())
        2L -> ${spec.kmStem}U16(layout.toBitsU64(x).toUShort())
        4L -> ${spec.kmStem}U32(layout.toBitsU64(x).toUInt())
        8L -> ${spec.kmStem}U64(layout.toBitsU64(x))
        16L -> {
            val (lo, hi) = layoutRead128Pair(layout, x)
            ${spec.kmStem}U128(lo, hi)
        }
        else -> error("Unsupported layout sizeBytes=${'$'}{layout.sizeBytes} for ${spec.fnName}")
    }

                    """.trimStart(),
                )
            }
        }

    private fun renderBswapAndBitreverseFunctions(): String =
        """
public fun <T> bswapWithLayout(
    x: T,
    layout: TypeLayout<T>,
): T =
    when (layout.sizeBytes) {
        1L -> x
        2L -> layout.fromBitsU64(kmBswapU16(layout.toBitsU64(x).toUShort()).toULong())
        4L -> layout.fromBitsU64(kmBswapU32(layout.toBitsU64(x).toUInt()).toULong())
        8L -> layout.fromBitsU64(kmBswapU64(layout.toBitsU64(x)))
        16L -> {
            val (lo, hi) = layoutRead128Pair(layout, x)
            val out = kmBswapU128(lo, hi)
            layoutFrom128Pair(layout, out.lo, out.hi)
        }
        else -> error("Unsupported layout sizeBytes=${'$'}{layout.sizeBytes} for bswapWithLayout")
    }

public fun <T> bitreverseWithLayout(
    x: T,
    layout: TypeLayout<T>,
): T =
    when (layout.sizeBytes) {
        1L -> layout.fromBitsU64(kmBitreverseU8(layout.toBitsU64(x).toUByte()).toULong())
        2L -> layout.fromBitsU64(kmBitreverseU16(layout.toBitsU64(x).toUShort()).toULong())
        4L -> layout.fromBitsU64(kmBitreverseU32(layout.toBitsU64(x).toUInt()).toULong())
        8L -> layout.fromBitsU64(kmBitreverseU64(layout.toBitsU64(x)))
        16L -> {
            val (lo, hi) = layoutRead128Pair(layout, x)
            val out = kmBitreverseU128(lo, hi)
            layoutFrom128Pair(layout, out.lo, out.hi)
        }
        else -> error("Unsupported layout sizeBytes=${'$'}{layout.sizeBytes} for bitreverseWithLayout")
    }
""".trimStart()

    private fun renderRotateFunctions(): String =
        buildString {
            for (spec in ROTATE_WITH_LAYOUT_SPECS) {
                append(
                    """
public fun <T> ${spec.fnName}(
    x: T,
    shift: UInt,
    layout: TypeLayout<T>,
): T =
    when (layout.sizeBytes) {
        1L -> layout.fromBitsU64(${spec.kmStem}U8(layout.toBitsU64(x).toUByte(), shift).toULong())
        2L -> layout.fromBitsU64(${spec.kmStem}U16(layout.toBitsU64(x).toUShort(), shift).toULong())
        4L -> layout.fromBitsU64(${spec.kmStem}U32(layout.toBitsU64(x).toUInt(), shift).toULong())
        8L -> layout.fromBitsU64(${spec.kmStem}U64(layout.toBitsU64(x), shift))
        16L -> {
            val (lo, hi) = layoutRead128Pair(layout, x)
            val out = ${spec.kmStem}U128(lo, hi, shift)
            layoutFrom128Pair(layout, out.lo, out.hi)
        }
        else -> error("Unsupported layout sizeBytes=${'$'}{layout.sizeBytes} for ${spec.fnName}")
    }

                    """.trimStart(),
                )
            }
        }

    private fun renderWrappingFunctions(): String =
        buildString {
            for (spec in WRAPPING_ARITH_SPECS) {
                append(
                    """
public fun <T> ${spec.fnName}(
    a: T,
    b: T,
    layout: TypeLayout<T>,
): T =
    when (layout.sizeBytes) {
        1L -> layout.fromBitsU64((layout.toBitsU64(a) ${spec.op} layout.toBitsU64(b)) and 0xFFuL)
        2L -> layout.fromBitsU64((layout.toBitsU64(a) ${spec.op} layout.toBitsU64(b)) and 0xFFFFuL)
        4L -> layout.fromBitsU64((layout.toBitsU64(a) ${spec.op} layout.toBitsU64(b)) and 0xFFFFFFFFuL)
        8L -> layout.fromBitsU64(layout.toBitsU64(a) ${spec.op} layout.toBitsU64(b))
        16L -> {
            val (aLo, aHi) = layoutRead128Pair(layout, a)
            val (bLo, bHi) = layoutRead128Pair(layout, b)
            val out = ${spec.u128Callee}(aLo, aHi, bLo, bHi)
            layoutFrom128Pair(layout, out.lo, out.hi)
        }
        else -> error("Unsupported layout sizeBytes=${'$'}{layout.sizeBytes} for ${spec.fnName}")
    }

                    """.trimStart(),
                )
            }
        }

    private fun renderSaturatingFunctions(): String =
        buildString {
            for (spec in SATURATING_ARITH_SPECS) {
                val p = spec.kmPrefix
                append(
                    """
public fun <T> ${spec.fnName}(
    a: T,
    b: T,
    layout: TypeLayout<T>,
): T {
    if (layout.sizeBytes == 16L) {
        val (aLo, aHi) = layoutRead128Pair(layout, a)
        val (bLo, bHi) = layoutRead128Pair(layout, b)
        return if (layout.isSigned) {
            val out = ${p}I128(aLo, aHi, bLo, bHi)
            layoutFrom128Pair(layout, out.lo, out.hi)
        } else {
            val out = ${p}U128(aLo, aHi, bLo, bHi)
            layoutFrom128Pair(layout, out.lo, out.hi)
        }
    }
    val aBits = layout.toBitsU64(a)
    val bBits = layout.toBitsU64(b)
    val res =
        when (layout.sizeBytes) {
            1L -> if (layout.isSigned) ${p}I8(aBits.toByte(), bBits.toByte()).toUByte().toULong() else ${p}U8(aBits.toUByte(), bBits.toUByte()).toULong()
            2L -> if (layout.isSigned) ${p}I16(aBits.toShort(), bBits.toShort()).toUShort().toULong() else ${p}U16(aBits.toUShort(), bBits.toUShort()).toULong()
            4L -> if (layout.isSigned) ${p}I32(aBits.toInt(), bBits.toInt()).toUInt().toULong() else ${p}U32(aBits.toUInt(), bBits.toUInt()).toULong()
            8L -> if (layout.isSigned) ${p}I64(aBits.toLong(), bBits.toLong()).toULong() else ${p}U64(aBits, bBits)
            else -> error("Unsupported layout sizeBytes=${'$'}{layout.sizeBytes} for ${spec.fnName}")
        }
    return layout.fromBitsU64(res)
}

                    """.trimStart(),
                )
            }
        }

    private fun renderOverflowFunctions(): String =
        buildString {
            for (spec in OVERFLOW_ARITH_SPECS) {
                val p = spec.kmPrefix
                append(
                    """
public fun <T> ${spec.fnName}(
    a: T,
    b: T,
    layout: TypeLayout<T>,
): Pair<T, Boolean> {
    if (layout.sizeBytes == 16L) {
        val (aLo, aHi) = layoutRead128Pair(layout, a)
        val (bLo, bHi) = layoutRead128Pair(layout, b)
        return if (layout.isSigned) {
            val out = ${p}I128(aLo, aHi, bLo, bHi)
            Pair(layoutFrom128Pair(layout, out.lo, out.hi), out.overflowed)
        } else {
            val out = ${p}U128(aLo, aHi, bLo, bHi)
            Pair(layoutFrom128Pair(layout, out.lo, out.hi), out.overflowed)
        }
    }
    val aBits = layout.toBitsU64(a)
    val bBits = layout.toBitsU64(b)
    return when (layout.sizeBytes) {
        1L ->
            if (layout.isSigned) {
                val out = ${p}I8(aBits.toByte(), bBits.toByte())
                Pair(layout.fromBitsU64(out.value.toUByte().toULong()), out.overflowed)
            } else {
                val out = ${p}U8(aBits.toUByte(), bBits.toUByte())
                Pair(layout.fromBitsU64(out.value.toULong()), out.overflowed)
            }
        2L ->
            if (layout.isSigned) {
                val out = ${p}I16(aBits.toShort(), bBits.toShort())
                Pair(layout.fromBitsU64(out.value.toUShort().toULong()), out.overflowed)
            } else {
                val out = ${p}U16(aBits.toUShort(), bBits.toUShort())
                Pair(layout.fromBitsU64(out.value.toULong()), out.overflowed)
            }
        4L ->
            if (layout.isSigned) {
                val out = ${p}I32(aBits.toInt(), bBits.toInt())
                Pair(layout.fromBitsU64(out.value.toUInt().toULong()), out.overflowed)
            } else {
                val out = ${p}U32(aBits.toUInt(), bBits.toUInt())
                Pair(layout.fromBitsU64(out.value.toULong()), out.overflowed)
            }
        8L ->
            if (layout.isSigned) {
                val out = ${p}I64(aBits.toLong(), bBits.toLong())
                Pair(layout.fromBitsU64(out.value.toULong()), out.overflowed)
            } else {
                val out = ${p}U64(aBits, bBits)
                Pair(layout.fromBitsU64(out.value), out.overflowed)
            }
        else -> error("Unsupported layout sizeBytes=${'$'}{layout.sizeBytes} for ${spec.fnName}")
    }
}

                    """.trimStart(),
                )
            }
        }

    private fun renderUncheckedDivAndRemFunctions(): String {
        val ops =
            listOf(
                Triple("uncheckedDivWithLayout", "/", "q"),
                Triple("uncheckedRemWithLayout", "%", "r"),
            )
        return buildString {
            for ((fnName, op, fieldStem) in ops) {
                append(
                    """
public fun <T> $fnName(
    a: T,
    b: T,
    layout: TypeLayout<T>,
): T {
    if (layout.sizeBytes == 16L) {
        val (aLo, aHi) = layoutRead128Pair(layout, a)
        val (bLo, bHi) = layoutRead128Pair(layout, b)
        if (bLo == 0uL && bHi == 0uL) throw ArithmeticException("/ by zero")
        return if (layout.isSigned) {
            val out = kmI128DivRem(aLo, aHi, bLo, bHi)
            layoutFrom128Pair(layout, out.${fieldStem}Lo, out.${fieldStem}Hi)
        } else {
            val out = kmU128DivRem(aLo, aHi, bLo, bHi)
            layoutFrom128Pair(layout, out.${fieldStem}Lo, out.${fieldStem}Hi)
        }
    }
    val aBits = layout.toBitsU64(a)
    val bBits = layout.toBitsU64(b)
    val res =
        when (layout.sizeBytes) {
            1L -> if (layout.isSigned) (aBits.toByte().toInt() $op bBits.toByte().toInt()).toUByte().toULong() else (aBits.toUByte() $op bBits.toUByte()).toULong()
            2L -> if (layout.isSigned) (aBits.toShort().toInt() $op bBits.toShort().toInt()).toUShort().toULong() else (aBits.toUShort() $op bBits.toUShort()).toULong()
            4L -> if (layout.isSigned) (aBits.toInt() $op bBits.toInt()).toUInt().toULong() else (aBits.toUInt() $op bBits.toUInt()).toULong()
            8L -> if (layout.isSigned) (aBits.toLong() $op bBits.toLong()).toULong() else aBits $op bBits
            else -> error("Unsupported layout sizeBytes=${'$'}{layout.sizeBytes} for $fnName")
        }
    return layout.fromBitsU64(res)
}

                    """.trimStart(),
                )
            }
            append(
                """
public fun <T> exactDivWithLayout(
    a: T,
    b: T,
    layout: TypeLayout<T>,
): T = uncheckedDivWithLayout(a, b, layout)

                """.trimStart(),
            )
        }
    }

    private fun renderUncheckedShiftFunctions(): String =
        """
private fun <U> shiftAmountFromLayout(
    shift: U,
    shiftLayout: TypeLayout<U>,
): UInt =
    if (shiftLayout.sizeBytes == 16L) {
        layoutRead128Pair(shiftLayout, shift).first.toUInt()
    } else {
        shiftLayout.toBitsU64(shift).toUInt()
    }

public fun <T> uncheckedShlWithLayout(
    x: T,
    shift: UInt,
    layout: TypeLayout<T>,
): T {
    val s = shift.toInt()
    return when (layout.sizeBytes) {
        1L -> layout.fromBitsU64(((layout.toBitsU64(x).toUInt() shl s) and 0xFFu).toULong())
        2L -> layout.fromBitsU64(((layout.toBitsU64(x).toUInt() shl s) and 0xFFFFu).toULong())
        4L -> layout.fromBitsU64((layout.toBitsU64(x).toUInt() shl s).toULong())
        8L -> layout.fromBitsU64(layout.toBitsU64(x) shl s)
        16L -> {
            val (lo, hi) = layoutRead128Pair(layout, x)
            val out = kmU128Shl(lo, hi, shift)
            layoutFrom128Pair(layout, out.lo, out.hi)
        }
        else -> error("Unsupported layout sizeBytes=${'$'}{layout.sizeBytes} for uncheckedShlWithLayout")
    }
}

public fun <T, U> uncheckedShlWithLayout(
    x: T,
    shift: U,
    layout: TypeLayout<T>,
    shiftLayout: TypeLayout<U>,
): T = uncheckedShlWithLayout(x, shiftAmountFromLayout(shift, shiftLayout), layout)

public fun <T> uncheckedShrWithLayout(
    x: T,
    shift: UInt,
    layout: TypeLayout<T>,
): T {
    if (layout.sizeBytes == 16L) {
        val (lo, hi) = layoutRead128Pair(layout, x)
        val (rLo, rHi) =
            if (layout.isSigned) {
                kmI128Shr(lo, hi, shift).let { it.lo to it.hi }
            } else {
                kmU128Shr(lo, hi, shift).let { it.lo to it.hi }
            }
        return layoutFrom128Pair(layout, rLo, rHi)
    }
    val s = shift.toInt()
    val bits = layout.toBitsU64(x)
    val res =
        when (layout.sizeBytes) {
            1L -> if (layout.isSigned) (bits.toByte().toInt() shr s).toUByte().toULong() else ((bits.toUInt() and 0xFFu) shr s).toULong()
            2L -> if (layout.isSigned) (bits.toShort().toInt() shr s).toUShort().toULong() else ((bits.toUInt() and 0xFFFFu) shr s).toULong()
            4L -> if (layout.isSigned) (bits.toInt() shr s).toUInt().toULong() else (bits.toUInt() shr s).toULong()
            8L -> if (layout.isSigned) (bits.toLong() shr s).toULong() else bits shr s
            else -> error("Unsupported layout sizeBytes=${'$'}{layout.sizeBytes} for uncheckedShrWithLayout")
        }
    return layout.fromBitsU64(res)
}

public fun <T, U> uncheckedShrWithLayout(
    x: T,
    shift: U,
    layout: TypeLayout<T>,
    shiftLayout: TypeLayout<U>,
): T = uncheckedShrWithLayout(x, shiftAmountFromLayout(shift, shiftLayout), layout)
""".trimStart()

    private fun renderDisjointBitOrAndThreeWayCompareFunctions(): String =
        """
public fun <T> disjointBitorWithLayout(
    a: T,
    b: T,
    layout: TypeLayout<T>,
): T =
    when (layout.sizeBytes) {
        1L, 2L, 4L, 8L -> layout.fromBitsU64(layout.toBitsU64(a) or layout.toBitsU64(b))
        16L -> {
            val (aLo, aHi) = layoutRead128Pair(layout, a)
            val (bLo, bHi) = layoutRead128Pair(layout, b)
            layoutFrom128Pair(layout, aLo or bLo, aHi or bHi)
        }
        else -> error("Unsupported layout sizeBytes=${'$'}{layout.sizeBytes} for disjointBitorWithLayout")
    }

public fun <T> threeWayCompareWithLayout(
    lhs: T,
    rhs: T,
    layout: TypeLayout<T>,
): Ordering {
    val cmp =
        when (layout.sizeBytes) {
            1L -> {
                val lBits = layout.toBitsU64(lhs)
                val rBits = layout.toBitsU64(rhs)
                if (layout.isSigned) {
                    lBits.toByte().compareTo(rBits.toByte())
                } else {
                    lBits.toUByte().compareTo(rBits.toUByte())
                }
            }
            2L -> {
                val lBits = layout.toBitsU64(lhs)
                val rBits = layout.toBitsU64(rhs)
                if (layout.isSigned) {
                    lBits.toShort().compareTo(rBits.toShort())
                } else {
                    lBits.toUShort().compareTo(rBits.toUShort())
                }
            }
            4L -> {
                val lBits = layout.toBitsU64(lhs)
                val rBits = layout.toBitsU64(rhs)
                if (layout.isSigned) {
                    lBits.toInt().compareTo(rBits.toInt())
                } else {
                    lBits.toUInt().compareTo(rBits.toUInt())
                }
            }
            8L -> {
                val lBits = layout.toBitsU64(lhs)
                val rBits = layout.toBitsU64(rhs)
                if (layout.isSigned) {
                    lBits.toLong().compareTo(rBits.toLong())
                } else {
                    lBits.compareTo(rBits)
                }
            }
            16L -> {
                val (lhsLo, lhsHi) = layoutRead128Pair(layout, lhs)
                val (rhsLo, rhsHi) = layoutRead128Pair(layout, rhs)
                val hiCmp =
                    if (layout.isSigned) {
                        lhsHi.toLong().compareTo(rhsHi.toLong())
                    } else {
                        lhsHi.compareTo(rhsHi)
                    }
                if (hiCmp != 0) hiCmp else lhsLo.compareTo(rhsLo)
            }
            else -> error("Unsupported layout sizeBytes=${'$'}{layout.sizeBytes} for threeWayCompareWithLayout")
        }
    return when {
        cmp < 0 -> Ordering.Less
        cmp > 0 -> Ordering.Greater
        else -> Ordering.Equal
    }
}

public fun <T> rawEqWithLayout(
    a: T,
    b: T,
    layout: TypeLayout<T>,
): Boolean {
    val sz = layout.sizeBytes.toInt()
    if (sz <= 0) return true
    val bufA = ByteArray(sz)
    val bufB = ByteArray(sz)
    layout.writeBytes(bufA, 0, a)
    layout.writeBytes(bufB, 0, b)
    return bufA.contentEquals(bufB)
}
""".trimStart()

    private fun renderFloatWithLayoutFunctions(): String {
        val unaryPairs =
            listOf(
                "sqrt" to "kmSqrtf32" to "kmSqrtf64",
                "sin" to "kmSinf32" to "kmSinf64",
                "cos" to "kmCosf32" to "kmCosf64",
                "exp" to "kmExpf32" to "kmExpf64",
                "exp2" to "kmExp2f32" to "kmExp2f64",
                "log" to "kmLogf32" to "kmLogf64",
                "log2" to "kmLog2f32" to "kmLog2f64",
                "log10" to "kmLog10f32" to "kmLog10f64",
                "trunc" to "kmTruncf32" to "kmTruncf64",
                "round" to "kmRoundf32" to "kmRoundf64",
                "round_ties_even" to "kmRoundTiesEvenf32" to "kmRoundTiesEvenf64",
                "ceil" to "kmCeilf32" to "kmCeilf64",
                "floor" to "kmFloorf32" to "kmFloorf64",
                "fabs" to "kmFabsf32" to "kmFabsf64",
            )
        val unaryF32Arms =
            unaryPairs.joinToString("\n") { (opAndF32, _) ->
                val (op, f32Fn) = opAndF32
                "        \"$op\" -> $f32Fn(v)"
            }
        val unaryF64Arms =
            unaryPairs.joinToString("\n") { (opAndF32, f64Fn) ->
                val (op, _) = opAndF32
                "        \"$op\" -> $f64Fn(v)"
            }
        return """
private fun evalFloatUnary32(
    op: String,
    v: Float,
): Float =
    when (op) {
$unaryF32Arms
        else -> error("Unsupported float unary intrinsic '${'$'}op' for f32")
    }

private fun evalFloatUnary64(
    op: String,
    v: Double,
): Double =
    when (op) {
$unaryF64Arms
        else -> error("Unsupported float unary intrinsic '${'$'}op' for f64")
    }

private fun <T> decodeLayoutFloat32(layout: TypeLayout<T>, v: T): Float =
    if (layout.sizeBytes == 2L) {
        F16(layout.toBitsU64(v).toUShort()).toFloat()
    } else {
        Float.fromBits(layout.toBitsU64(v).toUInt().toInt())
    }

private fun <T> encodeLayoutFloat32(layout: TypeLayout<T>, res: Float): T =
    if (layout.sizeBytes == 2L) {
        layout.fromBitsU64(F16.fromFloat(res).bits.toULong())
    } else {
        layout.fromBitsU64(res.toRawBits().toUInt().toULong())
    }

private fun <T> decodeLayoutFloat64(layout: TypeLayout<T>, v: T): Double =
    if (layout.sizeBytes == 8L) {
        Double.fromBits(layout.toBitsU64(v).toLong())
    } else {
        val (lo, hi) = layoutRead128Pair(layout, v)
        F128(hi, lo).toDouble()
    }

private fun <T> encodeLayoutFloat64(layout: TypeLayout<T>, res: Double): T =
    if (layout.sizeBytes == 8L) {
        layout.fromBitsU64(res.toRawBits().toULong())
    } else {
        val f128 = F128.fromDouble(res)
        layoutFrom128Pair(layout, f128.low, f128.high)
    }

private inline fun <T> mapFloatLayoutUnary(
    fnName: String,
    x: T,
    layout: TypeLayout<T>,
    eval32: (Float) -> Float,
    eval64: (Double) -> Double,
): T =
    when (layout.sizeBytes) {
        2L, 4L -> encodeLayoutFloat32(layout, eval32(decodeLayoutFloat32(layout, x)))
        8L, 16L -> encodeLayoutFloat64(layout, eval64(decodeLayoutFloat64(layout, x)))
        else -> error("Unsupported layout sizeBytes=${'$'}{layout.sizeBytes} for ${'$'}fnName")
    }

public fun <T> floatUnaryWithLayout(
    op: String,
    x: T,
    layout: TypeLayout<T>,
): T = mapFloatLayoutUnary("floatUnaryWithLayout", x, layout, { evalFloatUnary32(op, it) }, { evalFloatUnary64(op, it) })

private fun evalFloatBinary32(
    op: String,
    a: Float,
    b: Float,
): Float =
    when (op) {
        "powf" -> kmPowf32(a, b)
        "copysign" -> kmCopysignf32(a, b)
        "minnum" -> kmMinnumf32(a, b)
        "maxnum" -> kmMaxnumf32(a, b)
        "minimum" ->
            if (a.isNaN()) a
            else if (b.isNaN()) b
            else if (a == 0.0f && b == 0.0f && (a.toRawBits() < 0 || b.toRawBits() < 0)) -0.0f
            else kmMinnumf32(a, b)
        "maximum" ->
            if (a.isNaN()) a
            else if (b.isNaN()) b
            else if (a == 0.0f && b == 0.0f && (a.toRawBits() >= 0 || b.toRawBits() >= 0)) +0.0f
            else kmMaxnumf32(a, b)
        "fadd" -> a + b
        "fsub" -> a - b
        "fmul" -> a * b
        "fdiv" -> a / b
        "frem" -> a % b
        else -> error("Unsupported float binary intrinsic '${'$'}op' for f32")
    }

private fun evalFloatBinary64(
    op: String,
    a: Double,
    b: Double,
): Double =
    when (op) {
        "powf" -> kmPowf64(a, b)
        "copysign" -> kmCopysignf64(a, b)
        "minnum" -> kmMinnumf64(a, b)
        "maxnum" -> kmMaxnumf64(a, b)
        "minimum" ->
            if (a.isNaN()) a
            else if (b.isNaN()) b
            else if (a == 0.0 && b == 0.0 && (a.toRawBits() < 0L || b.toRawBits() < 0L)) -0.0
            else kmMinnumf64(a, b)
        "maximum" ->
            if (a.isNaN()) a
            else if (b.isNaN()) b
            else if (a == 0.0 && b == 0.0 && (a.toRawBits() >= 0L || b.toRawBits() >= 0L)) +0.0
            else kmMaxnumf64(a, b)
        "fadd" -> a + b
        "fsub" -> a - b
        "fmul" -> a * b
        "fdiv" -> a / b
        "frem" -> a % b
        else -> error("Unsupported float binary intrinsic '${'$'}op' for f64")
    }

public fun <T> floatBinaryWithLayout(
    op: String,
    a: T,
    b: T,
    layout: TypeLayout<T>,
): T =
    when (layout.sizeBytes) {
        2L, 4L -> encodeLayoutFloat32(layout, evalFloatBinary32(op, decodeLayoutFloat32(layout, a), decodeLayoutFloat32(layout, b)))
        8L, 16L -> encodeLayoutFloat64(layout, evalFloatBinary64(op, decodeLayoutFloat64(layout, a), decodeLayoutFloat64(layout, b)))
        else -> error("Unsupported layout sizeBytes=${'$'}{layout.sizeBytes} for floatBinaryWithLayout")
    }

public fun <T> floatPowiWithLayout(
    a: T,
    x: Int,
    layout: TypeLayout<T>,
): T = mapFloatLayoutUnary("floatPowiWithLayout", a, layout, { kmPowif32(it, x) }, { kmPowif64(it, x) })

public fun <T> floatFmaWithLayout(
    a: T,
    b: T,
    c: T,
    layout: TypeLayout<T>,
): T =
    when (layout.sizeBytes) {
        2L, 4L -> encodeLayoutFloat32(layout, kmFmaf32(decodeLayoutFloat32(layout, a), decodeLayoutFloat32(layout, b), decodeLayoutFloat32(layout, c)))
        8L, 16L -> encodeLayoutFloat64(layout, kmFmaf64(decodeLayoutFloat64(layout, a), decodeLayoutFloat64(layout, b), decodeLayoutFloat64(layout, c)))
        else -> error("Unsupported layout sizeBytes=${'$'}{layout.sizeBytes} for floatFmaWithLayout")
    }

public fun <F, I> floatToIntUncheckedWithLayout(
    value: F,
    floatLayout: TypeLayout<F>,
    intLayout: TypeLayout<I>,
): I {
    val isF32 = floatLayout.sizeBytes <= 4L
    val f32 =
        when (floatLayout.sizeBytes) {
            2L -> F16(floatLayout.toBitsU64(value).toUShort()).toFloat()
            4L -> Float.fromBits(floatLayout.toBitsU64(value).toUInt().toInt())
            else -> 0.0f
        }
    val f64 =
        when (floatLayout.sizeBytes) {
            8L -> Double.fromBits(floatLayout.toBitsU64(value).toLong())
            16L -> {
                val (lo, hi) = layoutRead128Pair(floatLayout, value)
                F128(hi, lo).toDouble()
            }
            else -> f32.toDouble()
        }
    return if (intLayout.sizeBytes == 16L) {
        val isNeg = if (isF32) f32 < 0.0f else f64 < 0.0
        val out =
            if (isF32) {
                kmFloatToU128Unchecked(if (intLayout.isSigned && isNeg) -f32 else f32)
            } else {
                kmDoubleToU128Unchecked(if (intLayout.isSigned && isNeg) -f64 else f64)
            }
        if (intLayout.isSigned && isNeg) {
            val lo = 0uL - out.lo
            val hi = 0uL - out.hi - (if (lo != 0uL) 1uL else 0uL)
            layoutFrom128Pair(intLayout, lo, hi)
        } else {
            layoutFrom128Pair(intLayout, out.lo, out.hi)
        }
    } else {
        val bits =
            if (isF32) {
                if (intLayout.isSigned && f32 < 0.0f) 0uL - kmFloatToU64Unchecked(-f32) else kmFloatToU64Unchecked(f32)
            } else {
                if (intLayout.isSigned && f64 < 0.0) 0uL - kmDoubleToU64Unchecked(-f64) else kmDoubleToU64Unchecked(f64)
            }
        intLayout.fromBitsU64(bits)
    }
}
""".trimStart()
    }

    public fun renderLayoutWithOperationsKotlin(coreNamespace: String): String {
        require(coreNamespace.isNotBlank()) { "coreNamespace must not be blank" }
        return buildString {
            append(
                """
private fun <T> layoutRead128Pair(
    layout: TypeLayout<T>,
    value: T,
): Pair<ULong, ULong> {
    val buf = ByteArray(16)
    layout.writeBytes(buf, 0, value)
    return Pair(kmBytesLoadU64Le(buf, 0), kmBytesLoadU64Le(buf, 8))
}

private fun <T> layoutFrom128Pair(
    layout: TypeLayout<T>,
    lo: ULong,
    hi: ULong,
): T {
    val buf = ByteArray(16)
    kmBytesStoreU64Le(buf, 0, lo)
    kmBytesStoreU64Le(buf, 8, hi)
    return layout.readBytes(buf, 0)
}

                """.trimStart(),
            )
            append(renderUnaryCountFunctions())
            append(renderBswapAndBitreverseFunctions())
            append(renderRotateFunctions())
            append(renderWrappingFunctions())
            append(renderSaturatingFunctions())
            append(renderOverflowFunctions())
            append(renderUncheckedDivAndRemFunctions())
            append(renderUncheckedShiftFunctions())
            append(renderDisjointBitOrAndThreeWayCompareFunctions())
            append(renderFloatWithLayoutFunctions())
        }
    }
}
