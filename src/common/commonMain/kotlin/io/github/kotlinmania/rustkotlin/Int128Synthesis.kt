package io.github.kotlinmania.rustkotlin

internal data class Parsed128Literal(
    val high: ULong,
    val low: ULong,
    val exceeds64BitUnsigned: Boolean,
)

internal fun parseUnsigned128LiteralDigits(rawDigitsInput: String): Parsed128Literal? {
    val clean = rawDigitsInput.replace("_", "").trim()
    if (clean.isEmpty()) return null
    val (radix, digits) =
        when {
            clean.startsWith("0x") || clean.startsWith("0X") -> 16uL to clean.substring(2)
            clean.startsWith("0b") || clean.startsWith("0B") -> 2uL to clean.substring(2)
            clean.startsWith("0o") || clean.startsWith("0O") -> 8uL to clean.substring(2)
            else -> 10uL to clean
        }
    if (digits.isEmpty()) return null
    var hi = 0uL
    var lo = 0uL
    for (ch in digits) {
        val digitVal: ULong =
            when {
                ch in '0'..'9' -> (ch - '0').toULong()
                ch in 'a'..'f' -> (10 + (ch - 'a')).toULong()
                ch in 'A'..'F' -> (10 + (ch - 'A')).toULong()
                else -> return null
            }
        if (digitVal >= radix) return null
        val loLow = lo and 0xFFFF_FFFFuL
        val loHigh = lo shr 32
        val hiLow = hi and 0xFFFF_FFFFuL
        val hiHigh = hi shr 32

        val p0 = loLow * radix + digitVal
        val p1 = loHigh * radix + (p0 shr 32)
        val p2 = hiLow * radix + (p1 shr 32)
        val p3 = hiHigh * radix + (p2 shr 32)
        if ((p3 shr 32) != 0uL) return null

        lo = (p1 shl 32) or (p0 and 0xFFFF_FFFFuL)
        hi = (p3 shl 32) or (p2 and 0xFFFF_FFFFuL)
    }
    return Parsed128Literal(high = hi, low = lo, exceeds64BitUnsigned = hi != 0uL)
}

internal fun formatU128ConstructorCall(high: ULong, low: ULong): String {
    val hiHex = "0x${high.toString(16)}uL"
    val loHex = "0x${low.toString(16)}uL"
    return "U128($hiHex, $loHex)"
}

internal fun formatI128ConstructorCall(high: ULong, low: ULong): String {
    val hiSigned = high.toLong()
    val hiLit =
        if (hiSigned == Long.MIN_VALUE) {
            "Long.MIN_VALUE"
        } else if (hiSigned < 0L) {
            "-0x${(-hiSigned).toULong().toString(16)}L"
        } else {
            "0x${high.toString(16)}L"
        }
    val loHex = "0x${low.toString(16)}uL"
    return "I128($hiLit, $loHex)"
}
