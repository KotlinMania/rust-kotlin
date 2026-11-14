package io.github.kotlinmania.rustkotlin

private fun hasUnpairedSurrogate(value: String): Boolean {
    var i = 0
    while (i < value.length) {
        val c = value[i]
        if (c.isHighSurrogate()) {
            if (i + 1 < value.length && value[i + 1].isLowSurrogate()) {
                i += 2
                continue
            }
            return true
        }
        if (c.isLowSurrogate()) {
            return true
        }
        i++
    }
    return false
}

private fun kotlinCharLiteral(c: Char): String =
    when (c) {
        '\'' -> "'\\''"
        '\\' -> "'\\\\'"
        else -> if (c.code in 32..126) "'$c'" else "'\\u" + c.code.toString(16).padStart(4, '0') + "'"
    }

/**
 * Encodes an already decoded Syn string value as a Kotlin source expression.
 *
 * If the string contains an unpaired UTF-16 surrogate (`\uD800`..`\uDFFF`), emits
 * `charArrayOf(...).concatToString()` (never a constant `String` literal or `'c'.toString()`,
 * which Kotlin's `IrInterpreter` constant-folds back into an `IrConst<String>`) so Kotlin/JS
 * incremental klib serialization does not corrupt lone surrogates into `'?'` via UTF-8 Protobuf strings.
 */
internal fun kotlinString(value: String): String {
    if (hasUnpairedSurrogate(value)) {
        return value.asSequence().joinToString(
            prefix = "charArrayOf(",
            postfix = ").concatToString()",
        ) { kotlinCharLiteral(it) }
    }
    return buildString {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '$' -> append("\\$")
                else -> if (c.code in 32..126) append(c) else append("\\u" + c.code.toString(16).padStart(4, '0'))
            }
        }
        append('"')
    }
}
