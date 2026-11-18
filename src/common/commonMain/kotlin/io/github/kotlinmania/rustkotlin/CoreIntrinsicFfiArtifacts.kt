package io.github.kotlinmania.rustkotlin

/**
 * Generates the hand-legitimate compiler boundary artifacts for `core::intrinsics` and `core::arch`:
 * - Tier B: `km_core_intrinsics_inline.h` (`static inline __attribute__((always_inline))` C11 atomics,
 *   typed/volatile memory loads & stores, bulk memcpy/memmove/memset/memcmp, compiler bit/math builtins,
 *   aligned system allocator, and architecture-guarded NEON/AVX/Wasm SIMD wrappers).
 * - Tier C: `km_core_llvm_asm.h` + `km_core_llvm_aarch64.S` + `km_core_llvm_x86_64.S` (128-bit atomic CAS,
 *   widening 128-bit multiply `umulh`/`smulh`, CPU spin-loop `yield`/`pause`, `cpuid`/`xgetbv`, and
 *   `kmBlackBoxU64` register barrier).
 * - Multiplatform Kotlin bindings (`KmCoreIntrinsics.kt`, `KmCoreNativeActuals.kt`, `KmCoreJvmActuals.kt`,
 *   `KmCoreJsActuals.kt`, `KmCoreWasmJsActuals.kt`, `KmCoreWasmWasiActuals.kt`, `km_core_jni.c`, `km_core_napi.cpp`),
 *   plus `ConstPtr<T>`, `MutPtr<T>`, and `TypeLayout<T>` runtime descriptors.
 */
public object CoreIntrinsicFfiArtifacts {
    public fun renderConstPtrClassHeader(attachedMembers: String = ""): String =
        buildString {
            append("public class ConstPtr(\n")
            append("    public val rawAddress: Long,\n")
            append("    public val rawMeta: Long = 0L,\n")
            append(") {\n")
            if (attachedMembers.isNotBlank()) {
                append(attachedMembers.trimEnd()).append("\n\n")
            }
            append("    override fun equals(other: Any?): Boolean =\n")
            append("        other is ConstPtr && rawAddress == other.rawAddress && rawMeta == other.rawMeta\n\n")
            append("    override fun hashCode(): Int = 31 * rawAddress.hashCode() + rawMeta.hashCode()\n\n")
            append("    override fun toString(): String = \"0x\" + rawAddress.toULong().toString(16)\n")
            append("}")
        }

    public fun renderMutPtrClassHeader(attachedMembers: String = ""): String =
        buildString {
            append("public class MutPtr(\n")
            append("    public val rawAddress: Long,\n")
            append("    public val rawMeta: Long = 0L,\n")
            append(") {\n")
            if (attachedMembers.isNotBlank()) {
                append(attachedMembers.trimEnd()).append("\n\n")
            }
            append("    override fun equals(other: Any?): Boolean =\n")
            append("        other is MutPtr && rawAddress == other.rawAddress && rawMeta == other.rawMeta\n\n")
            append("    override fun hashCode(): Int = 31 * rawAddress.hashCode() + rawMeta.hashCode()\n\n")
            append("    public fun asConstPtr(): ConstPtr = ConstPtr(rawAddress, rawMeta)\n\n")
            append("    override fun toString(): String = \"0x\" + rawAddress.toULong().toString(16)\n")
            append("}")
        }

    private fun renderPrimitiveArrayPtrExtensions(): String =
        listOf(
            "BooleanArray" to false,
            "ByteArray" to false,
            "UByteArray" to true,
            "ShortArray" to false,
            "UShortArray" to true,
            "IntArray" to false,
            "UIntArray" to true,
            "LongArray" to false,
            "ULongArray" to true,
            "FloatArray" to false,
            "DoubleArray" to false,
        ).joinToString("\n\n") { (arr, optIn) ->
            val opt = if (optIn) "@OptIn(ExperimentalUnsignedTypes::class)\n" else ""
            "${opt}public fun $arr.asPtr(): ConstPtr = constPtrFromRustCast(this)\n\n${opt}public fun $arr.asMutPtr(): MutPtr = mutPtrFromRustCast(this)"
        }

    public fun renderTypeLayoutSupportKotlin(coreNamespace: String): String {
        val intrinsicsPkg = "$coreNamespace.intrinsics"
        val ptrPkg = "$coreNamespace.ptr"
        val intrinsicsImports =
            buildSet {
                addAll(
                    listOf(
                        "kmAlloc",
                        "kmAtomicCxchgU128Pair",
                        "kmCeilf32",
                        "kmCeilf64",
                        "kmCopy",
                        "kmCopyNonoverlapping",
                        "kmCopysignf32",
                        "kmCopysignf64",
                        "kmCosf32",
                        "kmCosf64",
                        "kmDealloc",
                        "kmDoubleToU128Unchecked",
                        "kmDoubleToU64Unchecked",
                        "kmExp2f32",
                        "kmExp2f64",
                        "kmExpf32",
                        "kmExpf64",
                        "kmFabsf32",
                        "kmFabsf64",
                        "kmFloatToU128Unchecked",
                        "kmFloatToU64Unchecked",
                        "kmFloorf32",
                        "kmFloorf64",
                        "kmFmaf32",
                        "kmFmaf64",
                        "kmI128DivRem",
                        "kmI128Shr",
                        "kmLoadF32",
                        "kmLoadF64",
                        "kmLog10f32",
                        "kmLog10f64",
                        "kmLog2f32",
                        "kmLog2f64",
                        "kmLogf32",
                        "kmLogf64",
                        "kmMaxnumf32",
                        "kmMaxnumf64",
                        "kmMinnumf32",
                        "kmMinnumf64",
                        "kmPowf32",
                        "kmPowf64",
                        "kmPowif32",
                        "kmPowif64",
                        "kmRoundTiesEvenf32",
                        "kmRoundTiesEvenf64",
                        "kmRoundf32",
                        "kmRoundf64",
                        "kmSinf32",
                        "kmSinf64",
                        "kmSqrtf32",
                        "kmSqrtf64",
                        "kmStoreF32",
                        "kmStoreF64",
                        "kmTruncf32",
                        "kmTruncf64",
                        "kmU128Add",
                        "kmU128DivRem",
                        "kmU128Mul",
                        "kmU128Shl",
                        "kmU128Shr",
                        "kmU128Sub",
                    ),
                )
                for (w in listOf("U8", "U16", "U32", "U64")) {
                    add("kmAtomicCxchg$w")
                    add("kmAtomicLoad$w")
                    add("kmAtomicRmw$w")
                    add("kmAtomicStore$w")
                    add("kmAtomicXchg$w")
                    add("kmLoad$w")
                    add("kmStore$w")
                    add("kmVolatileLoad$w")
                    add("kmVolatileStore$w")
                }
                for (w in listOf("U8", "U16", "U32", "U64", "U128")) {
                    add("kmCtpop$w")
                    add("kmCtlz$w")
                    add("kmCttz$w")
                    add("kmBitreverse$w")
                    add("kmRotateLeft$w")
                    add("kmRotateRight$w")
                    if (w != "U8") add("kmBswap$w")
                }
                for (prefix in listOf("kmAddWithOverflow", "kmSubWithOverflow", "kmMulWithOverflow", "kmSaturatingAdd", "kmSaturatingSub")) {
                    for (sign in listOf("I", "U")) {
                        for (bits in listOf("8", "16", "32", "64", "128")) {
                            add("$prefix$sign$bits")
                        }
                    }
                }
            }.sorted().joinToString("\n") { "import $intrinsicsPkg.$it" }
        return """
package $coreNamespace.mem

import $coreNamespace.F128
import $coreNamespace.F16
import $coreNamespace.I128
import $coreNamespace.U128
import $coreNamespace.cmp.Ordering
$intrinsicsImports
import $ptrPkg.ConstPtr
import $ptrPkg.MutPtr

public fun alignUpBytes(offset: Long, align: Long): Long {
    if (align <= 1L) return offset
    val rem = offset % align
    return if (rem == 0L) offset else offset + (align - rem)
}

public fun kmBytesLoadU8(buf: ByteArray, offset: Int): UByte = buf[offset].toUByte()

public fun kmBytesStoreU8(buf: ByteArray, offset: Int, value: UByte) {
    buf[offset] = value.toByte()
}

public fun kmBytesLoadU16Le(buf: ByteArray, offset: Int): UShort =
    ((buf[offset].toInt() and 0xFF) or ((buf[offset + 1].toInt() and 0xFF) shl 8)).toUShort()

public fun kmBytesStoreU16Le(buf: ByteArray, offset: Int, value: UShort) {
    val v = value.toInt()
    buf[offset] = v.toByte()
    buf[offset + 1] = (v ushr 8).toByte()
}

public fun kmBytesLoadU32Le(buf: ByteArray, offset: Int): UInt =
    (
        (buf[offset].toInt() and 0xFF) or
            ((buf[offset + 1].toInt() and 0xFF) shl 8) or
            ((buf[offset + 2].toInt() and 0xFF) shl 16) or
            ((buf[offset + 3].toInt() and 0xFF) shl 24)
    ).toUInt()

public fun kmBytesStoreU32Le(buf: ByteArray, offset: Int, value: UInt) {
    val v = value.toInt()
    buf[offset] = v.toByte()
    buf[offset + 1] = (v ushr 8).toByte()
    buf[offset + 2] = (v ushr 16).toByte()
    buf[offset + 3] = (v ushr 24).toByte()
}

public fun kmBytesLoadU64Le(buf: ByteArray, offset: Int): ULong =
    (
        (buf[offset].toLong() and 0xFFL) or
            ((buf[offset + 1].toLong() and 0xFFL) shl 8) or
            ((buf[offset + 2].toLong() and 0xFFL) shl 16) or
            ((buf[offset + 3].toLong() and 0xFFL) shl 24) or
            ((buf[offset + 4].toLong() and 0xFFL) shl 32) or
            ((buf[offset + 5].toLong() and 0xFFL) shl 40) or
            ((buf[offset + 6].toLong() and 0xFFL) shl 48) or
            ((buf[offset + 7].toLong() and 0xFFL) shl 56)
    ).toULong()

public fun kmBytesStoreU64Le(buf: ByteArray, offset: Int, value: ULong) {
    val v = value.toLong()
    buf[offset] = v.toByte()
    buf[offset + 1] = (v ushr 8).toByte()
    buf[offset + 2] = (v ushr 16).toByte()
    buf[offset + 3] = (v ushr 24).toByte()
    buf[offset + 4] = (v ushr 32).toByte()
    buf[offset + 5] = (v ushr 40).toByte()
    buf[offset + 6] = (v ushr 48).toByte()
    buf[offset + 7] = (v ushr 56).toByte()
}

public fun kmBytesLoadF32Le(buf: ByteArray, offset: Int): Float =
    Float.fromBits(kmBytesLoadU32Le(buf, offset).toInt())

public fun kmBytesStoreF32Le(buf: ByteArray, offset: Int, value: Float) {
    kmBytesStoreU32Le(buf, offset, value.toRawBits().toUInt())
}

public fun kmBytesLoadF64Le(buf: ByteArray, offset: Int): Double =
    Double.fromBits(kmBytesLoadU64Le(buf, offset).toLong())

public fun kmBytesStoreF64Le(buf: ByteArray, offset: Int, value: Double) {
    kmBytesStoreU64Le(buf, offset, value.toRawBits().toULong())
}

public interface TypeLayout<T> {
    public val sizeBytes: Long
    public val alignBytes: Long
    public val strideBytes: Long
        get() = alignUpBytes(sizeBytes, alignBytes)
    public val isSigned: Boolean
        get() = false

    public fun readNative(addr: Long): T

    public fun writeNative(addr: Long, value: T)

    public fun readBytes(buf: ByteArray, offset: Int): T {
        val bytes = maxOf(1L, sizeBytes)
        val align = maxOf(1L, alignBytes)
        val scratch = kmAlloc(bytes, align)
        val n = minOf(sizeBytes.toInt(), buf.size - offset)
        for (i in 0 until n) {
            kmStoreU8(scratch + i.toLong(), buf[offset + i].toUByte())
        }
        val out = readNative(scratch)
        kmDealloc(scratch, bytes, align)
        return out
    }

    public fun writeBytes(buf: ByteArray, offset: Int, value: T) {
        val bytes = maxOf(1L, sizeBytes)
        val align = maxOf(1L, alignBytes)
        val scratch = kmAlloc(bytes, align)
        writeNative(scratch, value)
        val n = minOf(sizeBytes.toInt(), buf.size - offset)
        for (i in 0 until n) {
            buf[offset + i] = kmLoadU8(scratch + i.toLong()).toByte()
        }
        kmDealloc(scratch, bytes, align)
    }

    public fun readVolatileNative(addr: Long): T = readNative(addr)

    public fun writeVolatileNative(addr: Long, value: T): Unit = writeNative(addr, value)

    public fun toBitsU64(value: T): ULong {
        val scratch = kmAlloc(8L, 8L)
        kmStoreU64(scratch, 0uL)
        writeNative(scratch, value)
        val bits = kmLoadU64(scratch)
        kmDealloc(scratch, 8L, 8L)
        return bits
    }

    public fun fromBitsU64(bits: ULong): T {
        val scratch = kmAlloc(8L, 8L)
        kmStoreU64(scratch, bits)
        val out = readNative(scratch)
        kmDealloc(scratch, 8L, 8L)
        return out
    }
}

public object PrimitiveLayouts {
    public val UNIT: TypeLayout<Unit> =
        object : TypeLayout<Unit> {
            override val sizeBytes: Long = 0L
            override val alignBytes: Long = 1L
            override fun readNative(addr: Long) {}
            override fun writeNative(addr: Long, value: Unit) {}
            override fun readBytes(buf: ByteArray, offset: Int) {}
            override fun writeBytes(buf: ByteArray, offset: Int, value: Unit) {}
            override fun toBitsU64(value: Unit): ULong = 0uL
            override fun fromBitsU64(bits: ULong) {}
        }

    public val BOOL: TypeLayout<Boolean> =
        object : TypeLayout<Boolean> {
            override val sizeBytes: Long = 1L
            override val alignBytes: Long = 1L
            override fun readNative(addr: Long): Boolean = kmLoadU8(addr) != 0u.toUByte()
            override fun writeNative(addr: Long, value: Boolean) {
                kmStoreU8(addr, if (value) 1u else 0u)
            }
            override fun readBytes(buf: ByteArray, offset: Int): Boolean = buf[offset] != 0.toByte()
            override fun writeBytes(buf: ByteArray, offset: Int, value: Boolean) {
                buf[offset] = if (value) 1 else 0
            }
            override fun readVolatileNative(addr: Long): Boolean = kmVolatileLoadU8(addr) != 0u.toUByte()
            override fun writeVolatileNative(addr: Long, value: Boolean): Unit = kmVolatileStoreU8(addr, if (value) 1u else 0u)
            override fun toBitsU64(value: Boolean): ULong = if (value) 1uL else 0uL
            override fun fromBitsU64(bits: ULong): Boolean = (bits and 0xFFuL) != 0uL
        }

    public val I8: TypeLayout<Byte> =
        object : TypeLayout<Byte> {
            override val sizeBytes: Long = 1L
            override val alignBytes: Long = 1L
            override val isSigned: Boolean = true
            override fun readNative(addr: Long): Byte = kmLoadU8(addr).toByte()
            override fun writeNative(addr: Long, value: Byte) {
                kmStoreU8(addr, value.toUByte())
            }
            override fun readBytes(buf: ByteArray, offset: Int): Byte = buf[offset]
            override fun writeBytes(buf: ByteArray, offset: Int, value: Byte) {
                buf[offset] = value
            }
            override fun readVolatileNative(addr: Long): Byte = kmVolatileLoadU8(addr).toByte()
            override fun writeVolatileNative(addr: Long, value: Byte): Unit = kmVolatileStoreU8(addr, value.toUByte())
            override fun toBitsU64(value: Byte): ULong = value.toUByte().toULong()
            override fun fromBitsU64(bits: ULong): Byte = bits.toUByte().toByte()
        }

    public val U8: TypeLayout<UByte> =
        object : TypeLayout<UByte> {
            override val sizeBytes: Long = 1L
            override val alignBytes: Long = 1L
            override fun readNative(addr: Long): UByte = kmLoadU8(addr)
            override fun writeNative(addr: Long, value: UByte) {
                kmStoreU8(addr, value)
            }
            override fun readBytes(buf: ByteArray, offset: Int): UByte = buf[offset].toUByte()
            override fun writeBytes(buf: ByteArray, offset: Int, value: UByte) {
                buf[offset] = value.toByte()
            }
            override fun readVolatileNative(addr: Long): UByte = kmVolatileLoadU8(addr)
            override fun writeVolatileNative(addr: Long, value: UByte): Unit = kmVolatileStoreU8(addr, value)
            override fun toBitsU64(value: UByte): ULong = value.toULong()
            override fun fromBitsU64(bits: ULong): UByte = bits.toUByte()
        }

    public val I16: TypeLayout<Short> =
        object : TypeLayout<Short> {
            override val sizeBytes: Long = 2L
            override val alignBytes: Long = 2L
            override val isSigned: Boolean = true
            override fun readNative(addr: Long): Short = kmLoadU16(addr).toShort()
            override fun writeNative(addr: Long, value: Short) {
                kmStoreU16(addr, value.toUShort())
            }
            override fun readBytes(buf: ByteArray, offset: Int): Short = kmBytesLoadU16Le(buf, offset).toShort()
            override fun writeBytes(buf: ByteArray, offset: Int, value: Short): Unit =
                kmBytesStoreU16Le(buf, offset, value.toUShort())
            override fun readVolatileNative(addr: Long): Short = kmVolatileLoadU16(addr).toShort()
            override fun writeVolatileNative(addr: Long, value: Short): Unit = kmVolatileStoreU16(addr, value.toUShort())
            override fun toBitsU64(value: Short): ULong = value.toUShort().toULong()
            override fun fromBitsU64(bits: ULong): Short = bits.toUShort().toShort()
        }

    public val U16: TypeLayout<UShort> =
        object : TypeLayout<UShort> {
            override val sizeBytes: Long = 2L
            override val alignBytes: Long = 2L
            override fun readNative(addr: Long): UShort = kmLoadU16(addr)
            override fun writeNative(addr: Long, value: UShort) {
                kmStoreU16(addr, value)
            }
            override fun readBytes(buf: ByteArray, offset: Int): UShort = kmBytesLoadU16Le(buf, offset)
            override fun writeBytes(buf: ByteArray, offset: Int, value: UShort): Unit =
                kmBytesStoreU16Le(buf, offset, value)
            override fun readVolatileNative(addr: Long): UShort = kmVolatileLoadU16(addr)
            override fun writeVolatileNative(addr: Long, value: UShort): Unit = kmVolatileStoreU16(addr, value)
            override fun toBitsU64(value: UShort): ULong = value.toULong()
            override fun fromBitsU64(bits: ULong): UShort = bits.toUShort()
        }

    public val I32: TypeLayout<Int> =
        object : TypeLayout<Int> {
            override val sizeBytes: Long = 4L
            override val alignBytes: Long = 4L
            override val isSigned: Boolean = true
            override fun readNative(addr: Long): Int = kmLoadU32(addr).toInt()
            override fun writeNative(addr: Long, value: Int) {
                kmStoreU32(addr, value.toUInt())
            }
            override fun readBytes(buf: ByteArray, offset: Int): Int = kmBytesLoadU32Le(buf, offset).toInt()
            override fun writeBytes(buf: ByteArray, offset: Int, value: Int): Unit =
                kmBytesStoreU32Le(buf, offset, value.toUInt())
            override fun readVolatileNative(addr: Long): Int = kmVolatileLoadU32(addr).toInt()
            override fun writeVolatileNative(addr: Long, value: Int): Unit = kmVolatileStoreU32(addr, value.toUInt())
            override fun toBitsU64(value: Int): ULong = value.toUInt().toULong()
            override fun fromBitsU64(bits: ULong): Int = bits.toUInt().toInt()
        }

    public val U32: TypeLayout<UInt> =
        object : TypeLayout<UInt> {
            override val sizeBytes: Long = 4L
            override val alignBytes: Long = 4L
            override fun readNative(addr: Long): UInt = kmLoadU32(addr)
            override fun writeNative(addr: Long, value: UInt) {
                kmStoreU32(addr, value)
            }
            override fun readBytes(buf: ByteArray, offset: Int): UInt = kmBytesLoadU32Le(buf, offset)
            override fun writeBytes(buf: ByteArray, offset: Int, value: UInt): Unit =
                kmBytesStoreU32Le(buf, offset, value)
            override fun readVolatileNative(addr: Long): UInt = kmVolatileLoadU32(addr)
            override fun writeVolatileNative(addr: Long, value: UInt): Unit = kmVolatileStoreU32(addr, value)
            override fun toBitsU64(value: UInt): ULong = value.toULong()
            override fun fromBitsU64(bits: ULong): UInt = bits.toUInt()
        }

    public val CHAR: TypeLayout<Char> =
        object : TypeLayout<Char> {
            override val sizeBytes: Long = 4L
            override val alignBytes: Long = 4L
            override fun readNative(addr: Long): Char = kmLoadU32(addr).toInt().toChar()
            override fun writeNative(addr: Long, value: Char) {
                kmStoreU32(addr, value.code.toUInt())
            }
            override fun readBytes(buf: ByteArray, offset: Int): Char = kmBytesLoadU32Le(buf, offset).toInt().toChar()
            override fun writeBytes(buf: ByteArray, offset: Int, value: Char): Unit =
                kmBytesStoreU32Le(buf, offset, value.code.toUInt())
            override fun readVolatileNative(addr: Long): Char = kmVolatileLoadU32(addr).toInt().toChar()
            override fun writeVolatileNative(addr: Long, value: Char): Unit = kmVolatileStoreU32(addr, value.code.toUInt())
            override fun toBitsU64(value: Char): ULong = value.code.toUInt().toULong()
            override fun fromBitsU64(bits: ULong): Char = bits.toUInt().toInt().toChar()
        }

    public val F32: TypeLayout<Float> =
        object : TypeLayout<Float> {
            override val sizeBytes: Long = 4L
            override val alignBytes: Long = 4L
            override val isSigned: Boolean = true
            override fun readNative(addr: Long): Float = kmLoadF32(addr)
            override fun writeNative(addr: Long, value: Float) {
                kmStoreF32(addr, value)
            }
            override fun readBytes(buf: ByteArray, offset: Int): Float = Float.fromBits(kmBytesLoadU32Le(buf, offset).toInt())
            override fun writeBytes(buf: ByteArray, offset: Int, value: Float): Unit =
                kmBytesStoreU32Le(buf, offset, value.toRawBits().toUInt())
            override fun readVolatileNative(addr: Long): Float = Float.fromBits(kmVolatileLoadU32(addr).toInt())
            override fun writeVolatileNative(addr: Long, value: Float): Unit = kmVolatileStoreU32(addr, value.toRawBits().toUInt())
            override fun toBitsU64(value: Float): ULong = value.toRawBits().toUInt().toULong()
            override fun fromBitsU64(bits: ULong): Float = Float.fromBits(bits.toUInt().toInt())
        }

    public val I64: TypeLayout<Long> =
        object : TypeLayout<Long> {
            override val sizeBytes: Long = 8L
            override val alignBytes: Long = 8L
            override val isSigned: Boolean = true
            override fun readNative(addr: Long): Long = kmLoadU64(addr).toLong()
            override fun writeNative(addr: Long, value: Long) {
                kmStoreU64(addr, value.toULong())
            }
            override fun readBytes(buf: ByteArray, offset: Int): Long = kmBytesLoadU64Le(buf, offset).toLong()
            override fun writeBytes(buf: ByteArray, offset: Int, value: Long): Unit =
                kmBytesStoreU64Le(buf, offset, value.toULong())
            override fun readVolatileNative(addr: Long): Long = kmVolatileLoadU64(addr).toLong()
            override fun writeVolatileNative(addr: Long, value: Long): Unit = kmVolatileStoreU64(addr, value.toULong())
            override fun toBitsU64(value: Long): ULong = value.toULong()
            override fun fromBitsU64(bits: ULong): Long = bits.toLong()
        }

    public val U64: TypeLayout<ULong> =
        object : TypeLayout<ULong> {
            override val sizeBytes: Long = 8L
            override val alignBytes: Long = 8L
            override fun readNative(addr: Long): ULong = kmLoadU64(addr)
            override fun writeNative(addr: Long, value: ULong) {
                kmStoreU64(addr, value)
            }
            override fun readBytes(buf: ByteArray, offset: Int): ULong = kmBytesLoadU64Le(buf, offset)
            override fun writeBytes(buf: ByteArray, offset: Int, value: ULong): Unit =
                kmBytesStoreU64Le(buf, offset, value)
            override fun readVolatileNative(addr: Long): ULong = kmVolatileLoadU64(addr)
            override fun writeVolatileNative(addr: Long, value: ULong): Unit = kmVolatileStoreU64(addr, value)
            override fun toBitsU64(value: ULong): ULong = value
            override fun fromBitsU64(bits: ULong): ULong = bits
        }

    public val F64: TypeLayout<Double> =
        object : TypeLayout<Double> {
            override val sizeBytes: Long = 8L
            override val alignBytes: Long = 8L
            override val isSigned: Boolean = true
            override fun readNative(addr: Long): Double = kmLoadF64(addr)
            override fun writeNative(addr: Long, value: Double) {
                kmStoreF64(addr, value)
            }
            override fun readBytes(buf: ByteArray, offset: Int): Double = Double.fromBits(kmBytesLoadU64Le(buf, offset).toLong())
            override fun writeBytes(buf: ByteArray, offset: Int, value: Double): Unit =
                kmBytesStoreU64Le(buf, offset, value.toRawBits().toULong())
            override fun readVolatileNative(addr: Long): Double = Double.fromBits(kmVolatileLoadU64(addr).toLong())
            override fun writeVolatileNative(addr: Long, value: Double): Unit = kmVolatileStoreU64(addr, value.toRawBits().toULong())
            override fun toBitsU64(value: Double): ULong = value.toRawBits().toULong()
            override fun fromBitsU64(bits: ULong): Double = Double.fromBits(bits.toLong())
        }

    public val F16_LAYOUT: TypeLayout<F16> =
        object : TypeLayout<F16> {
            override val sizeBytes: Long = 2L
            override val alignBytes: Long = 2L
            override val isSigned: Boolean = true
            override fun readNative(addr: Long): F16 = F16.fromBits(kmLoadU16(addr))
            override fun writeNative(addr: Long, value: F16) {
                kmStoreU16(addr, value.toBits())
            }
            override fun readBytes(buf: ByteArray, offset: Int): F16 = F16.fromBits(kmBytesLoadU16Le(buf, offset))
            override fun writeBytes(buf: ByteArray, offset: Int, value: F16): Unit =
                kmBytesStoreU16Le(buf, offset, value.toBits())
            override fun readVolatileNative(addr: Long): F16 = F16.fromBits(kmVolatileLoadU16(addr))
            override fun writeVolatileNative(addr: Long, value: F16): Unit = kmVolatileStoreU16(addr, value.toBits())
            override fun toBitsU64(value: F16): ULong = value.toBits().toULong()
            override fun fromBitsU64(bits: ULong): F16 = F16.fromBits(bits.toUShort())
        }

    public val F128_LAYOUT: TypeLayout<F128> = u128Layout(::F128, { it.high }, { it.low }, isSigned = true)

    public val ISIZE64: TypeLayout<Int> =
        object : TypeLayout<Int> {
            override val sizeBytes: Long = 8L
            override val alignBytes: Long = 8L
            override val isSigned: Boolean = true
            override fun readNative(addr: Long): Int = kmLoadU64(addr).toLong().toInt()
            override fun writeNative(addr: Long, value: Int) {
                kmStoreU64(addr, value.toLong().toULong())
            }
            override fun readBytes(buf: ByteArray, offset: Int): Int = kmBytesLoadU64Le(buf, offset).toLong().toInt()
            override fun writeBytes(buf: ByteArray, offset: Int, value: Int): Unit =
                kmBytesStoreU64Le(buf, offset, value.toLong().toULong())
            override fun readVolatileNative(addr: Long): Int = kmVolatileLoadU64(addr).toLong().toInt()
            override fun writeVolatileNative(addr: Long, value: Int): Unit = kmVolatileStoreU64(addr, value.toLong().toULong())
            override fun toBitsU64(value: Int): ULong = value.toLong().toULong()
            override fun fromBitsU64(bits: ULong): Int = bits.toLong().toInt()
        }

    public val USIZE64: TypeLayout<Int> =
        object : TypeLayout<Int> {
            override val sizeBytes: Long = 8L
            override val alignBytes: Long = 8L
            override fun readNative(addr: Long): Int = kmLoadU64(addr).toInt()
            override fun writeNative(addr: Long, value: Int) {
                kmStoreU64(addr, value.toUInt().toULong())
            }
            override fun readBytes(buf: ByteArray, offset: Int): Int = kmBytesLoadU64Le(buf, offset).toInt()
            override fun writeBytes(buf: ByteArray, offset: Int, value: Int): Unit =
                kmBytesStoreU64Le(buf, offset, value.toUInt().toULong())
            override fun readVolatileNative(addr: Long): Int = kmVolatileLoadU64(addr).toInt()
            override fun writeVolatileNative(addr: Long, value: Int): Unit = kmVolatileStoreU64(addr, value.toUInt().toULong())
            override fun toBitsU64(value: Int): ULong = value.toUInt().toULong()
            override fun fromBitsU64(bits: ULong): Int = bits.toInt()
        }

    public val ISIZE32: TypeLayout<Int> = I32
    public val USIZE32: TypeLayout<Int> =
        object : TypeLayout<Int> {
            override val sizeBytes: Long = 4L
            override val alignBytes: Long = 4L
            override val isSigned: Boolean = false
            override fun readNative(addr: Long): Int = I32.readNative(addr)
            override fun writeNative(addr: Long, value: Int): Unit = I32.writeNative(addr, value)
            override fun readBytes(buf: ByteArray, offset: Int): Int = I32.readBytes(buf, offset)
            override fun writeBytes(buf: ByteArray, offset: Int, value: Int): Unit = I32.writeBytes(buf, offset, value)
            override fun readVolatileNative(addr: Long): Int = I32.readVolatileNative(addr)
            override fun writeVolatileNative(addr: Long, value: Int): Unit = I32.writeVolatileNative(addr, value)
            override fun toBitsU64(value: Int): ULong = I32.toBitsU64(value)
            override fun fromBitsU64(bits: ULong): Int = I32.fromBitsU64(bits)
        }

    public fun constPtrLayout(pointerWidthBits: Long = 64L): TypeLayout<ConstPtr> {
        val bytes = pointerWidthBits / 8L
        return object : TypeLayout<ConstPtr> {
            override val sizeBytes: Long = bytes
            override val alignBytes: Long = bytes
            override fun readNative(addr: Long): ConstPtr {
                val raw = if (bytes == 8L) kmLoadU64(addr).toLong() else kmLoadU32(addr).toLong()
                return ConstPtr(raw)
            }
            override fun writeNative(addr: Long, value: ConstPtr) {
                if (bytes == 8L) {
                    kmStoreU64(addr, value.rawAddress.toULong())
                } else {
                    kmStoreU32(addr, value.rawAddress.toUInt())
                }
            }
            override fun readBytes(buf: ByteArray, offset: Int): ConstPtr {
                val raw = if (bytes == 8L) kmBytesLoadU64Le(buf, offset).toLong() else kmBytesLoadU32Le(buf, offset).toLong()
                return ConstPtr(raw)
            }
            override fun writeBytes(buf: ByteArray, offset: Int, value: ConstPtr) {
                if (bytes == 8L) {
                    kmBytesStoreU64Le(buf, offset, value.rawAddress.toULong())
                } else {
                    kmBytesStoreU32Le(buf, offset, value.rawAddress.toUInt())
                }
            }
            override fun toBitsU64(value: ConstPtr): ULong = value.rawAddress.toULong()
            override fun fromBitsU64(bits: ULong): ConstPtr = ConstPtr(bits.toLong())
        }
    }

    public fun mutPtrLayout(pointerWidthBits: Long = 64L): TypeLayout<MutPtr> {
        val bytes = pointerWidthBits / 8L
        return object : TypeLayout<MutPtr> {
            override val sizeBytes: Long = bytes
            override val alignBytes: Long = bytes
            override fun readNative(addr: Long): MutPtr {
                val raw = if (bytes == 8L) kmLoadU64(addr).toLong() else kmLoadU32(addr).toLong()
                return MutPtr(raw)
            }
            override fun writeNative(addr: Long, value: MutPtr) {
                if (bytes == 8L) {
                    kmStoreU64(addr, value.rawAddress.toULong())
                } else {
                    kmStoreU32(addr, value.rawAddress.toUInt())
                }
            }
            override fun readBytes(buf: ByteArray, offset: Int): MutPtr {
                val raw = if (bytes == 8L) kmBytesLoadU64Le(buf, offset).toLong() else kmBytesLoadU32Le(buf, offset).toLong()
                return MutPtr(raw)
            }
            override fun writeBytes(buf: ByteArray, offset: Int, value: MutPtr) {
                if (bytes == 8L) {
                    kmBytesStoreU64Le(buf, offset, value.rawAddress.toULong())
                } else {
                    kmBytesStoreU32Le(buf, offset, value.rawAddress.toUInt())
                }
            }
            override fun toBitsU64(value: MutPtr): ULong = value.rawAddress.toULong()
            override fun fromBitsU64(bits: ULong): MutPtr = MutPtr(bits.toLong())
        }
    }

    public fun <T> mutableReferenceLayout(
        innerLayout: TypeLayout<T>,
        pointerWidthBits: Long = 64L,
    ): TypeLayout<RustMutableReference<T>> {
        val bytes = pointerWidthBits / 8L
        return object : TypeLayout<RustMutableReference<T>> {
            override val sizeBytes: Long = bytes
            override val alignBytes: Long = bytes
            override fun readNative(addr: Long): RustMutableReference<T> {
                val raw = if (bytes == 8L) kmLoadU64(addr).toLong() else kmLoadU32(addr).toLong()
                return RustMutableReference(MutPtr(raw), innerLayout)
            }
            override fun writeNative(addr: Long, value: RustMutableReference<T>) {
                if (bytes == 8L) {
                    kmStoreU64(addr, value.pointer.rawAddress.toULong())
                } else {
                    kmStoreU32(addr, value.pointer.rawAddress.toUInt())
                }
            }
            override fun readBytes(buf: ByteArray, offset: Int): RustMutableReference<T> {
                val raw = if (bytes == 8L) kmBytesLoadU64Le(buf, offset).toLong() else kmBytesLoadU32Le(buf, offset).toLong()
                return RustMutableReference(MutPtr(raw), innerLayout)
            }
            override fun writeBytes(buf: ByteArray, offset: Int, value: RustMutableReference<T>) {
                if (bytes == 8L) {
                    kmBytesStoreU64Le(buf, offset, value.pointer.rawAddress.toULong())
                } else {
                    kmBytesStoreU32Le(buf, offset, value.pointer.rawAddress.toUInt())
                }
            }
            override fun toBitsU64(value: RustMutableReference<T>): ULong = value.pointer.rawAddress.toULong()
            override fun fromBitsU64(bits: ULong): RustMutableReference<T> =
                RustMutableReference(MutPtr(bits.toLong()), innerLayout)
        }
    }

    public fun <T> u128Layout(
        construct: (ULong, ULong) -> T,
        highOf: (T) -> ULong,
        lowOf: (T) -> ULong,
        isSigned: Boolean = false,
    ): TypeLayout<T> =
        object : TypeLayout<T> {
            override val sizeBytes: Long = 16L
            override val alignBytes: Long = 16L
            override val isSigned: Boolean = isSigned
            override fun readNative(addr: Long): T {
                val lo = kmLoadU64(addr)
                val hi = kmLoadU64(addr + 8L)
                return construct(hi, lo)
            }
            override fun writeNative(addr: Long, value: T) {
                kmStoreU64(addr, lowOf(value))
                kmStoreU64(addr + 8L, highOf(value))
            }
            override fun readBytes(buf: ByteArray, offset: Int): T {
                val lo = kmBytesLoadU64Le(buf, offset)
                val hi = kmBytesLoadU64Le(buf, offset + 8)
                return construct(hi, lo)
            }
            override fun writeBytes(buf: ByteArray, offset: Int, value: T) {
                kmBytesStoreU64Le(buf, offset, lowOf(value))
                kmBytesStoreU64Le(buf, offset + 8, highOf(value))
            }
        }

    public fun <T> i128Layout(
        construct: (Long, ULong) -> T,
        highOf: (T) -> Long,
        lowOf: (T) -> ULong,
    ): TypeLayout<T> =
        object : TypeLayout<T> {
            override val sizeBytes: Long = 16L
            override val alignBytes: Long = 16L
            override val isSigned: Boolean = true
            override fun readNative(addr: Long): T {
                val lo = kmLoadU64(addr)
                val hi = kmLoadU64(addr + 8L).toLong()
                return construct(hi, lo)
            }
            override fun writeNative(addr: Long, value: T) {
                kmStoreU64(addr, lowOf(value))
                kmStoreU64(addr + 8L, highOf(value).toULong())
            }
            override fun readBytes(buf: ByteArray, offset: Int): T {
                val lo = kmBytesLoadU64Le(buf, offset)
                val hi = kmBytesLoadU64Le(buf, offset + 8).toLong()
                return construct(hi, lo)
            }
            override fun writeBytes(buf: ByteArray, offset: Int, value: T) {
                kmBytesStoreU64Le(buf, offset, lowOf(value))
                kmBytesStoreU64Le(buf, offset + 8, highOf(value).toULong())
            }
        }
}

public fun <T> allocateNativeCell(
    value: T,
    layout: TypeLayout<T>,
): MutPtr {
    val bytes = maxOf(1L, layout.sizeBytes)
    val align = maxOf(1L, layout.alignBytes)
    val addr = kmAlloc(bytes, align)
    layout.writeNative(addr, value)
    return MutPtr(addr)
}

public class TransparentTypeLayout<T, F>
    private constructor(
        public val inner: TypeLayout<F>,
        public val project: (MutPtr, TypeLayout<F>) -> T,
        public val unwrap: (T) -> F,
        private val gcWrap: ((F) -> T)?,
    ) : TypeLayout<T> {
        public constructor(
            inner: TypeLayout<F>,
            project: (MutPtr, TypeLayout<F>) -> T,
            unwrap: (T) -> F,
        ) : this(
            inner = inner,
            project = project,
            unwrap = unwrap,
            gcWrap = null,
        )

        public constructor(
            inner: TypeLayout<F>,
            wrap: (F) -> T,
            unwrap: (T) -> F,
        ) : this(
            inner = inner,
            project = { ptr, layout -> wrap(layout.readNative(ptr.rawAddress)) },
            unwrap = unwrap,
            gcWrap = wrap,
        )

        override val sizeBytes: Long get() = inner.sizeBytes
        override val alignBytes: Long get() = inner.alignBytes
        override val isSigned: Boolean get() = inner.isSigned
        override fun readNative(addr: Long): T = project(MutPtr(addr), inner)
        override fun writeNative(addr: Long, value: T): Unit = inner.writeNative(addr, unwrap(value))
        override fun readVolatileNative(addr: Long): T = project(MutPtr(addr), inner)
        override fun writeVolatileNative(addr: Long, value: T): Unit = inner.writeVolatileNative(addr, unwrap(value))
        override fun readBytes(buf: ByteArray, offset: Int): T =
            if (gcWrap != null) gcWrap.invoke(inner.readBytes(buf, offset)) else super.readBytes(buf, offset)
        override fun writeBytes(buf: ByteArray, offset: Int, value: T): Unit =
            inner.writeBytes(buf, offset, unwrap(value))
        override fun toBitsU64(value: T): ULong = inner.toBitsU64(unwrap(value))
        override fun fromBitsU64(bits: ULong): T {
            if (gcWrap != null) return gcWrap.invoke(inner.fromBitsU64(bits))
            val cell = allocateNativeCell(inner.fromBitsU64(bits), inner)
            return project(cell, inner)
        }
    }

public class AlignedTypeLayout<T>(
    public val inner: TypeLayout<T>,
    override val alignBytes: Long,
) : TypeLayout<T> {
    override val sizeBytes: Long get() = alignUpBytes(inner.sizeBytes, alignBytes)
    override val isSigned: Boolean get() = inner.isSigned
    override fun readNative(addr: Long): T = inner.readNative(addr)
    override fun writeNative(addr: Long, value: T): Unit = inner.writeNative(addr, value)
    override fun readVolatileNative(addr: Long): T = inner.readVolatileNative(addr)
    override fun writeVolatileNative(addr: Long, value: T): Unit = inner.writeVolatileNative(addr, value)
    override fun readBytes(buf: ByteArray, offset: Int): T = inner.readBytes(buf, offset)
    override fun writeBytes(buf: ByteArray, offset: Int, value: T): Unit = inner.writeBytes(buf, offset, value)
    override fun toBitsU64(value: T): ULong = inner.toBitsU64(value)
    override fun fromBitsU64(bits: ULong): T = inner.fromBitsU64(bits)
}

public class FixedArrayTypeLayout<T>(
    public val elementLayout: TypeLayout<T>,
    public val length: Int,
) : TypeLayout<List<T>> {
    override val sizeBytes: Long = length.toLong() * elementLayout.strideBytes
    override val alignBytes: Long = maxOf(1L, elementLayout.alignBytes)

    override fun readNative(addr: Long): List<T> {
        val stride = elementLayout.strideBytes
        return List(length) { index ->
            elementLayout.readNative(addr + index.toLong() * stride)
        }
    }

    override fun writeNative(addr: Long, value: List<T>) {
        val stride = elementLayout.strideBytes
        val limit = minOf(length, value.size)
        for (index in 0 until limit) {
            elementLayout.writeNative(addr + index.toLong() * stride, value[index])
        }
    }

    override fun readBytes(buf: ByteArray, offset: Int): List<T> {
        val stride = elementLayout.strideBytes.toInt()
        return List(length) { index ->
            elementLayout.readBytes(buf, offset + index * stride)
        }
    }

    override fun writeBytes(buf: ByteArray, offset: Int, value: List<T>) {
        val stride = elementLayout.strideBytes.toInt()
        val limit = minOf(length, value.size)
        for (index in 0 until limit) {
            elementLayout.writeBytes(buf, offset + index * stride, value[index])
        }
    }
}

public fun <T> fixedListTypeLayout(
    elementLayout: TypeLayout<T>,
    length: Int,
): TypeLayout<List<T>> = FixedArrayTypeLayout(elementLayout, length)

public class UnresolvedTypeLayout<T>(
    private val typeDescription: String = "T",
) : TypeLayout<T> {
    override val sizeBytes: Long
        get() = throw UnsupportedOperationException("Unresolved TypeLayout<${'$'}typeDescription>: native size requires concrete layout")

    override val alignBytes: Long
        get() = throw UnsupportedOperationException("Unresolved TypeLayout<${'$'}typeDescription>: native alignment requires concrete layout")

    override fun readNative(addr: Long): T =
        throw UnsupportedOperationException(
            "Unresolved TypeLayout<${'$'}typeDescription>: cannot read native memory at 0x${'$'}{addr.toULong().toString(16)}",
        )

    override fun writeNative(
        addr: Long,
        value: T,
    ): Unit =
        throw UnsupportedOperationException(
            "Unresolved TypeLayout<${'$'}typeDescription>: cannot write native memory at 0x${'$'}{addr.toULong().toString(16)}",
        )
}

public class LinearSliceView<T>(
    public val pointer: ConstPtr,
    override val size: Int,
    public val elementLayout: TypeLayout<T>,
) : AbstractList<T>() {
    override fun get(index: Int): T {
        if (index < 0 || index >= size) {
            throw IndexOutOfBoundsException("Index ${'$'}index out of bounds for length ${'$'}size")
        }
        return elementLayout.readNative(pointer.rawAddress + index.toLong() * elementLayout.strideBytes)
    }

    public fun subView(
        fromIndex: Int,
        toIndex: Int,
    ): LinearSliceView<T> {
        if (fromIndex < 0 || toIndex > size || fromIndex > toIndex) {
            throw IndexOutOfBoundsException("Slice subView(${'$'}fromIndex, ${'$'}toIndex) out of bounds for length ${'$'}size")
        }
        val offsetPtr = ConstPtr(pointer.rawAddress + fromIndex.toLong() * elementLayout.strideBytes, pointer.rawMeta)
        return LinearSliceView(offsetPtr, toIndex - fromIndex, elementLayout)
    }

    override fun subList(
        fromIndex: Int,
        toIndex: Int,
    ): List<T> = subView(fromIndex, toIndex)
}

public class LinearMutSliceView<T>(
    public val pointer: MutPtr,
    override val size: Int,
    public val elementLayout: TypeLayout<T>,
) : AbstractMutableList<T>() {
    override fun get(index: Int): T {
        if (index < 0 || index >= size) {
            throw IndexOutOfBoundsException("Index ${'$'}index out of bounds for length ${'$'}size")
        }
        return elementLayout.readNative(pointer.rawAddress + index.toLong() * elementLayout.strideBytes)
    }

    override fun set(
        index: Int,
        element: T,
    ): T {
        if (index < 0 || index >= size) {
            throw IndexOutOfBoundsException("Index ${'$'}index out of bounds for length ${'$'}size")
        }
        val addr = pointer.rawAddress + index.toLong() * elementLayout.strideBytes
        val prev = elementLayout.readNative(addr)
        elementLayout.writeNative(addr, element)
        return prev
    }

    override fun add(
        index: Int,
        element: T,
    ): Unit = throw UnsupportedOperationException("Fixed-length Rust slice cannot grow")

    override fun removeAt(index: Int): T =
        throw UnsupportedOperationException("Fixed-length Rust slice cannot shrink")

    public fun subView(
        fromIndex: Int,
        toIndex: Int,
    ): LinearMutSliceView<T> {
        if (fromIndex < 0 || toIndex > size || fromIndex > toIndex) {
            throw IndexOutOfBoundsException("Slice subView(${'$'}fromIndex, ${'$'}toIndex) out of bounds for length ${'$'}size")
        }
        val offsetPtr = MutPtr(pointer.rawAddress + fromIndex.toLong() * elementLayout.strideBytes, pointer.rawMeta)
        return LinearMutSliceView(offsetPtr, toIndex - fromIndex, elementLayout)
    }

    override fun subList(
        fromIndex: Int,
        toIndex: Int,
    ): MutableList<T> = subView(fromIndex, toIndex)
}

public fun <T> linearSliceView(
    ptr: ConstPtr,
    len: Int,
    layout: TypeLayout<T>,
): List<T> = LinearSliceView(ptr, maxOf(0, len), layout)

public fun <T> linearSliceView(
    ptr: MutPtr,
    len: Int,
    layout: TypeLayout<T>,
): List<T> = LinearSliceView(ConstPtr(ptr.rawAddress, ptr.rawMeta), maxOf(0, len), layout)

public fun <T> linearMutSliceView(
    ptr: MutPtr,
    len: Int,
    layout: TypeLayout<T>,
): MutableList<T> = LinearMutSliceView(ptr, maxOf(0, len), layout)

public operator fun <T> List<T>.get(range: IntRange): List<T> {
    val start = range.first
    val endExclusive = range.last + 1
    return when (this) {
        is LinearSliceView<T> -> subView(start, endExclusive)
        is LinearMutSliceView<T> -> {
            if (start < 0 || endExclusive > size || start > endExclusive) {
                throw IndexOutOfBoundsException("Slice range (${'$'}start, ${'$'}endExclusive) out of bounds for length ${'$'}size")
            }
            val offsetPtr = ConstPtr(pointer.rawAddress + start.toLong() * elementLayout.strideBytes, pointer.rawMeta)
            LinearSliceView(offsetPtr, endExclusive - start, elementLayout)
        }
        else -> subList(start, endExclusive)
    }
}

public operator fun <T> MutableList<T>.get(range: IntRange): MutableList<T> {
    val start = range.first
    val endExclusive = range.last + 1
    return when (this) {
        is LinearMutSliceView<T> -> subView(start, endExclusive)
        else -> subList(start, endExclusive)
    }
}

public operator fun String.get(range: IntRange): String = substring(range)

public operator fun ByteArray.get(range: IntRange): ByteArray = sliceArray(range)

@OptIn(ExperimentalUnsignedTypes::class)
public operator fun UByteArray.get(range: IntRange): UByteArray = sliceArray(range)

${renderPrimitiveArrayPtrExtensions()}

public fun <T : Comparable<T>> kmCompareValues(
    a: T?,
    b: T?,
): Int = compareValues(a, b)

public fun kmCompareValues(
    a: ConstPtr,
    b: ConstPtr,
): Int = a.rawAddress.toULong().compareTo(b.rawAddress.toULong())

public fun kmCompareValues(
    a: MutPtr,
    b: MutPtr,
): Int = a.rawAddress.toULong().compareTo(b.rawAddress.toULong())

public fun kmCompareValues(
    a: U128,
    b: U128,
): Int = a.compareTo(b)

public fun kmCompareValues(
    a: I128,
    b: I128,
): Int = a.compareTo(b)

public fun kmCompareValues(
    a: F16,
    b: F16,
): Int = a.compareTo(b)

public fun kmCompareValues(
    a: F128,
    b: F128,
): Int = a.compareTo(b)

public fun kmCompareValues(
    a: Any?,
    b: Any?,
): Int {
    if (a == b) return 0
    if (a == null) return -1
    if (b == null) return 1
    return when (a) {
        is Byte -> a.compareTo(b as Byte)
        is UByte -> a.compareTo(b as UByte)
        is Short -> a.compareTo(b as Short)
        is UShort -> a.compareTo(b as UShort)
        is Int -> a.compareTo(b as Int)
        is UInt -> a.compareTo(b as UInt)
        is Long -> a.compareTo(b as Long)
        is ULong -> a.compareTo(b as ULong)
        is Float -> a.compareTo(b as Float)
        is Double -> a.compareTo(b as Double)
        is Char -> a.compareTo(b as Char)
        is Boolean -> a.compareTo(b as Boolean)
        is String -> a.compareTo(b as String)
        is F16 -> a.compareTo(b as F16)
        is F128 -> a.compareTo(b as F128)
        is U128 -> a.compareTo(b as U128)
        is I128 -> a.compareTo(b as I128)
        is ConstPtr -> a.rawAddress.toULong().compareTo((b as ConstPtr).rawAddress.toULong())
        is MutPtr -> a.rawAddress.toULong().compareTo((b as MutPtr).rawAddress.toULong())
        is Pair<*, *> -> {
            val rhs = b as Pair<*, *>
            val c0 = kmCompareValues(a.first, rhs.first)
            if (c0 != 0) c0 else kmCompareValues(a.second, rhs.second)
        }
        is Triple<*, *, *> -> {
            val rhs = b as Triple<*, *, *>
            val c0 = kmCompareValues(a.first, rhs.first)
            if (c0 != 0) {
                c0
            } else {
                val c1 = kmCompareValues(a.second, rhs.second)
                if (c1 != 0) c1 else kmCompareValues(a.third, rhs.third)
            }
        }
        is List<*> -> {
            val rhs = b as List<*>
            val minSize = minOf(a.size, rhs.size)
            for (idx in 0 until minSize) {
                val c = kmCompareValues(a[idx], rhs[idx])
                if (c != 0) return c
            }
            a.size.compareTo(rhs.size)
        }
        else -> {
            val leftName = a::class.simpleName ?: "Any"
            throw UnsupportedOperationException("Unsupported natural comparison for ${'$'}leftName")
        }
    }
}

public fun kmPartialCompareValues(
    a: Any?,
    b: Any?,
): Int? {
    if ((a is Float && a.isNaN()) || (b is Float && b.isNaN())) return null
    if ((a is Double && a.isNaN()) || (b is Double && b.isNaN())) return null
    if ((a is F16 && a.isNaN()) || (b is F16 && b.isNaN())) return null
    if ((a is F128 && a.isNaN()) || (b is F128 && b.isNaN())) return null
    return when {
        a is Pair<*, *> && b is Pair<*, *> -> {
            val c0 = kmPartialCompareValues(a.first, b.first) ?: return null
            if (c0 != 0) c0 else kmPartialCompareValues(a.second, b.second)
        }
        a is Triple<*, *, *> && b is Triple<*, *, *> -> {
            val c0 = kmPartialCompareValues(a.first, b.first) ?: return null
            if (c0 != 0) {
                c0
            } else {
                val c1 = kmPartialCompareValues(a.second, b.second) ?: return null
                if (c1 != 0) c1 else kmPartialCompareValues(a.third, b.third)
            }
        }
        a is List<*> && b is List<*> -> {
            val minSize = minOf(a.size, b.size)
            for (idx in 0 until minSize) {
                val c = kmPartialCompareValues(a[idx], b[idx]) ?: return null
                if (c != 0) return c
            }
            a.size.compareTo(b.size)
        }
        else -> kmCompareValues(a, b)
    }
}

public operator fun <T> List<T>.component6(): T = this[5]

public operator fun <T> List<T>.component7(): T = this[6]

public operator fun <T> List<T>.component8(): T = this[7]

public operator fun <T> List<T>.component9(): T = this[8]

public operator fun <T> List<T>.component10(): T = this[9]

public operator fun <T> List<T>.component11(): T = this[10]

public operator fun <T> List<T>.component12(): T = this[11]

public fun <T> addrOf(value: T): ConstPtr = constPtrFromRustCast(value)

public fun <T> addrOfMut(value: T): MutPtr = mutPtrFromRustCast(value)

public object PrimitiveArrayLayouts {
    private fun <A> arrayToBitsU64(
        layout: TypeLayout<A>,
        value: A,
    ): ULong {
        if (layout.sizeBytes in 0L..8L) {
            val buf = ByteArray(8)
            layout.writeBytes(buf, 0, value)
            return kmBytesLoadU64Le(buf, 0)
        }
        val scratch = kmAlloc(8L, 8L)
        kmStoreU64(scratch, 0uL)
        layout.writeNative(scratch, value)
        val bits = kmLoadU64(scratch)
        kmDealloc(scratch, 8L, 8L)
        return bits
    }

    private fun <A> arrayFromBitsU64(
        layout: TypeLayout<A>,
        bits: ULong,
    ): A {
        if (layout.sizeBytes in 0L..8L) {
            val buf = ByteArray(8)
            kmBytesStoreU64Le(buf, 0, bits)
            return layout.readBytes(buf, 0)
        }
        val scratch = kmAlloc(8L, 8L)
        kmStoreU64(scratch, bits)
        val out = layout.readNative(scratch)
        kmDealloc(scratch, 8L, 8L)
        return out
    }

    public fun booleanArray(length: Int): TypeLayout<BooleanArray> =
        object : TypeLayout<BooleanArray> {
            override val sizeBytes: Long = length.toLong()
            override val alignBytes: Long = 1L

            override fun readNative(addr: Long): BooleanArray =
                BooleanArray(length) { i -> kmLoadU8(addr + i.toLong()) != 0u.toUByte() }

            override fun writeNative(addr: Long, value: BooleanArray) {
                val n = minOf(length, value.size)
                for (i in 0 until n) {
                    kmStoreU8(addr + i.toLong(), if (value[i]) 1u else 0u)
                }
            }

            override fun readBytes(buf: ByteArray, offset: Int): BooleanArray =
                BooleanArray(length) { i -> buf[offset + i] != 0.toByte() }

            override fun writeBytes(buf: ByteArray, offset: Int, value: BooleanArray) {
                val n = minOf(length, value.size)
                for (i in 0 until n) {
                    buf[offset + i] = if (value[i]) 1 else 0
                }
            }

            override fun toBitsU64(value: BooleanArray): ULong = arrayToBitsU64(this, value)

            override fun fromBitsU64(bits: ULong): BooleanArray = arrayFromBitsU64(this, bits)
        }

    public fun byteArray(length: Int): TypeLayout<ByteArray> =
        object : TypeLayout<ByteArray> {
            override val sizeBytes: Long = length.toLong()
            override val alignBytes: Long = 1L

            override fun readNative(addr: Long): ByteArray =
                ByteArray(length) { i -> kmLoadU8(addr + i.toLong()).toByte() }

            override fun writeNative(addr: Long, value: ByteArray) {
                val n = minOf(length, value.size)
                for (i in 0 until n) {
                    kmStoreU8(addr + i.toLong(), value[i].toUByte())
                }
            }

            override fun readBytes(buf: ByteArray, offset: Int): ByteArray {
                val out = ByteArray(length)
                val n = minOf(length, maxOf(0, buf.size - offset))
                if (n > 0) {
                    buf.copyInto(out, destinationOffset = 0, startIndex = offset, endIndex = offset + n)
                }
                return out
            }

            override fun writeBytes(buf: ByteArray, offset: Int, value: ByteArray) {
                val n = minOf(length, value.size)
                if (n > 0) {
                    value.copyInto(buf, destinationOffset = offset, startIndex = 0, endIndex = n)
                }
            }

            override fun toBitsU64(value: ByteArray): ULong = arrayToBitsU64(this, value)

            override fun fromBitsU64(bits: ULong): ByteArray = arrayFromBitsU64(this, bits)
        }

    @OptIn(ExperimentalUnsignedTypes::class)
    public fun uByteArray(length: Int): TypeLayout<UByteArray> =
        object : TypeLayout<UByteArray> {
            override val sizeBytes: Long = length.toLong()
            override val alignBytes: Long = 1L

            override fun readNative(addr: Long): UByteArray =
                UByteArray(length) { i -> kmLoadU8(addr + i.toLong()) }

            override fun writeNative(addr: Long, value: UByteArray) {
                val n = minOf(length, value.size)
                for (i in 0 until n) {
                    kmStoreU8(addr + i.toLong(), value[i])
                }
            }

            override fun readBytes(buf: ByteArray, offset: Int): UByteArray {
                val out = ByteArray(length)
                val n = minOf(length, maxOf(0, buf.size - offset))
                if (n > 0) {
                    buf.copyInto(out, destinationOffset = 0, startIndex = offset, endIndex = offset + n)
                }
                return out.asUByteArray()
            }

            override fun writeBytes(buf: ByteArray, offset: Int, value: UByteArray) {
                val n = minOf(length, value.size)
                if (n > 0) {
                    value.asByteArray().copyInto(buf, destinationOffset = offset, startIndex = 0, endIndex = n)
                }
            }

            override fun toBitsU64(value: UByteArray): ULong = arrayToBitsU64(this, value)

            override fun fromBitsU64(bits: ULong): UByteArray = arrayFromBitsU64(this, bits)
        }

    public fun shortArray(length: Int): TypeLayout<ShortArray> =
        object : TypeLayout<ShortArray> {
            override val sizeBytes: Long = length.toLong() * 2L
            override val alignBytes: Long = 2L

            override fun readNative(addr: Long): ShortArray =
                ShortArray(length) { i -> kmLoadU16(addr + i.toLong() * 2L).toShort() }

            override fun writeNative(addr: Long, value: ShortArray) {
                val n = minOf(length, value.size)
                for (i in 0 until n) {
                    kmStoreU16(addr + i.toLong() * 2L, value[i].toUShort())
                }
            }

            override fun readBytes(buf: ByteArray, offset: Int): ShortArray =
                ShortArray(length) { i -> kmBytesLoadU16Le(buf, offset + i * 2).toShort() }

            override fun writeBytes(buf: ByteArray, offset: Int, value: ShortArray) {
                val n = minOf(length, value.size)
                for (i in 0 until n) {
                    kmBytesStoreU16Le(buf, offset + i * 2, value[i].toUShort())
                }
            }

            override fun toBitsU64(value: ShortArray): ULong = arrayToBitsU64(this, value)

            override fun fromBitsU64(bits: ULong): ShortArray = arrayFromBitsU64(this, bits)
        }

    @OptIn(ExperimentalUnsignedTypes::class)
    public fun uShortArray(length: Int): TypeLayout<UShortArray> =
        object : TypeLayout<UShortArray> {
            override val sizeBytes: Long = length.toLong() * 2L
            override val alignBytes: Long = 2L

            override fun readNative(addr: Long): UShortArray =
                UShortArray(length) { i -> kmLoadU16(addr + i.toLong() * 2L) }

            override fun writeNative(addr: Long, value: UShortArray) {
                val n = minOf(length, value.size)
                for (i in 0 until n) {
                    kmStoreU16(addr + i.toLong() * 2L, value[i])
                }
            }

            override fun readBytes(buf: ByteArray, offset: Int): UShortArray =
                UShortArray(length) { i -> kmBytesLoadU16Le(buf, offset + i * 2) }

            override fun writeBytes(buf: ByteArray, offset: Int, value: UShortArray) {
                val n = minOf(length, value.size)
                for (i in 0 until n) {
                    kmBytesStoreU16Le(buf, offset + i * 2, value[i])
                }
            }

            override fun toBitsU64(value: UShortArray): ULong = arrayToBitsU64(this, value)

            override fun fromBitsU64(bits: ULong): UShortArray = arrayFromBitsU64(this, bits)
        }

    public fun intArray(length: Int): TypeLayout<IntArray> =
        object : TypeLayout<IntArray> {
            override val sizeBytes: Long = length.toLong() * 4L
            override val alignBytes: Long = 4L

            override fun readNative(addr: Long): IntArray =
                IntArray(length) { i -> kmLoadU32(addr + i.toLong() * 4L).toInt() }

            override fun writeNative(addr: Long, value: IntArray) {
                val n = minOf(length, value.size)
                for (i in 0 until n) {
                    kmStoreU32(addr + i.toLong() * 4L, value[i].toUInt())
                }
            }

            override fun readBytes(buf: ByteArray, offset: Int): IntArray =
                IntArray(length) { i -> kmBytesLoadU32Le(buf, offset + i * 4).toInt() }

            override fun writeBytes(buf: ByteArray, offset: Int, value: IntArray) {
                val n = minOf(length, value.size)
                for (i in 0 until n) {
                    kmBytesStoreU32Le(buf, offset + i * 4, value[i].toUInt())
                }
            }

            override fun toBitsU64(value: IntArray): ULong = arrayToBitsU64(this, value)

            override fun fromBitsU64(bits: ULong): IntArray = arrayFromBitsU64(this, bits)
        }

    @OptIn(ExperimentalUnsignedTypes::class)
    public fun uIntArray(length: Int): TypeLayout<UIntArray> =
        object : TypeLayout<UIntArray> {
            override val sizeBytes: Long = length.toLong() * 4L
            override val alignBytes: Long = 4L

            override fun readNative(addr: Long): UIntArray =
                UIntArray(length) { i -> kmLoadU32(addr + i.toLong() * 4L) }

            override fun writeNative(addr: Long, value: UIntArray) {
                val n = minOf(length, value.size)
                for (i in 0 until n) {
                    kmStoreU32(addr + i.toLong() * 4L, value[i])
                }
            }

            override fun readBytes(buf: ByteArray, offset: Int): UIntArray =
                UIntArray(length) { i -> kmBytesLoadU32Le(buf, offset + i * 4) }

            override fun writeBytes(buf: ByteArray, offset: Int, value: UIntArray) {
                val n = minOf(length, value.size)
                for (i in 0 until n) {
                    kmBytesStoreU32Le(buf, offset + i * 4, value[i])
                }
            }

            override fun toBitsU64(value: UIntArray): ULong = arrayToBitsU64(this, value)

            override fun fromBitsU64(bits: ULong): UIntArray = arrayFromBitsU64(this, bits)
        }

    public fun longArray(length: Int): TypeLayout<LongArray> =
        object : TypeLayout<LongArray> {
            override val sizeBytes: Long = length.toLong() * 8L
            override val alignBytes: Long = 8L

            override fun readNative(addr: Long): LongArray =
                LongArray(length) { i -> kmLoadU64(addr + i.toLong() * 8L).toLong() }

            override fun writeNative(addr: Long, value: LongArray) {
                val n = minOf(length, value.size)
                for (i in 0 until n) {
                    kmStoreU64(addr + i.toLong() * 8L, value[i].toULong())
                }
            }

            override fun readBytes(buf: ByteArray, offset: Int): LongArray =
                LongArray(length) { i -> kmBytesLoadU64Le(buf, offset + i * 8).toLong() }

            override fun writeBytes(buf: ByteArray, offset: Int, value: LongArray) {
                val n = minOf(length, value.size)
                for (i in 0 until n) {
                    kmBytesStoreU64Le(buf, offset + i * 8, value[i].toULong())
                }
            }

            override fun toBitsU64(value: LongArray): ULong = arrayToBitsU64(this, value)

            override fun fromBitsU64(bits: ULong): LongArray = arrayFromBitsU64(this, bits)
        }

    @OptIn(ExperimentalUnsignedTypes::class)
    public fun uLongArray(length: Int): TypeLayout<ULongArray> =
        object : TypeLayout<ULongArray> {
            override val sizeBytes: Long = length.toLong() * 8L
            override val alignBytes: Long = 8L

            override fun readNative(addr: Long): ULongArray =
                ULongArray(length) { i -> kmLoadU64(addr + i.toLong() * 8L) }

            override fun writeNative(addr: Long, value: ULongArray) {
                val n = minOf(length, value.size)
                for (i in 0 until n) {
                    kmStoreU64(addr + i.toLong() * 8L, value[i])
                }
            }

            override fun readBytes(buf: ByteArray, offset: Int): ULongArray =
                ULongArray(length) { i -> kmBytesLoadU64Le(buf, offset + i * 8) }

            override fun writeBytes(buf: ByteArray, offset: Int, value: ULongArray) {
                val n = minOf(length, value.size)
                for (i in 0 until n) {
                    kmBytesStoreU64Le(buf, offset + i * 8, value[i])
                }
            }

            override fun toBitsU64(value: ULongArray): ULong = arrayToBitsU64(this, value)

            override fun fromBitsU64(bits: ULong): ULongArray = arrayFromBitsU64(this, bits)
        }

    public fun floatArray(length: Int): TypeLayout<FloatArray> =
        object : TypeLayout<FloatArray> {
            override val sizeBytes: Long = length.toLong() * 4L
            override val alignBytes: Long = 4L

            override fun readNative(addr: Long): FloatArray =
                FloatArray(length) { i -> kmLoadF32(addr + i.toLong() * 4L) }

            override fun writeNative(addr: Long, value: FloatArray) {
                val n = minOf(length, value.size)
                for (i in 0 until n) {
                    kmStoreF32(addr + i.toLong() * 4L, value[i])
                }
            }

            override fun readBytes(buf: ByteArray, offset: Int): FloatArray =
                FloatArray(length) { i -> kmBytesLoadF32Le(buf, offset + i * 4) }

            override fun writeBytes(buf: ByteArray, offset: Int, value: FloatArray) {
                val n = minOf(length, value.size)
                for (i in 0 until n) {
                    kmBytesStoreF32Le(buf, offset + i * 4, value[i])
                }
            }

            override fun toBitsU64(value: FloatArray): ULong = arrayToBitsU64(this, value)

            override fun fromBitsU64(bits: ULong): FloatArray = arrayFromBitsU64(this, bits)
        }

    public fun doubleArray(length: Int): TypeLayout<DoubleArray> =
        object : TypeLayout<DoubleArray> {
            override val sizeBytes: Long = length.toLong() * 8L
            override val alignBytes: Long = 8L

            override fun readNative(addr: Long): DoubleArray =
                DoubleArray(length) { i -> kmLoadF64(addr + i.toLong() * 8L) }

            override fun writeNative(addr: Long, value: DoubleArray) {
                val n = minOf(length, value.size)
                for (i in 0 until n) {
                    kmStoreF64(addr + i.toLong() * 8L, value[i])
                }
            }

            override fun readBytes(buf: ByteArray, offset: Int): DoubleArray =
                DoubleArray(length) { i -> kmBytesLoadF64Le(buf, offset + i * 8) }

            override fun writeBytes(buf: ByteArray, offset: Int, value: DoubleArray) {
                val n = minOf(length, value.size)
                for (i in 0 until n) {
                    kmBytesStoreF64Le(buf, offset + i * 8, value[i])
                }
            }

            override fun toBitsU64(value: DoubleArray): ULong = arrayToBitsU64(this, value)

            override fun fromBitsU64(bits: ULong): DoubleArray = arrayFromBitsU64(this, bits)
        }

    public fun usize64Array(length: Int): TypeLayout<IntArray> =
        object : TypeLayout<IntArray> {
            override val sizeBytes: Long = length.toLong() * 8L
            override val alignBytes: Long = 8L

            override fun readNative(addr: Long): IntArray =
                IntArray(length) { i -> kmLoadU64(addr + i.toLong() * 8L).toInt() }

            override fun writeNative(addr: Long, value: IntArray) {
                val n = minOf(length, value.size)
                for (i in 0 until n) {
                    kmStoreU64(addr + i.toLong() * 8L, value[i].toLong().toULong() and 0xFFFF_FFFFuL)
                }
            }

            override fun readBytes(buf: ByteArray, offset: Int): IntArray =
                IntArray(length) { i -> kmBytesLoadU64Le(buf, offset + i * 8).toInt() }

            override fun writeBytes(buf: ByteArray, offset: Int, value: IntArray) {
                val n = minOf(length, value.size)
                for (i in 0 until n) {
                    kmBytesStoreU64Le(buf, offset + i * 8, value[i].toLong().toULong() and 0xFFFF_FFFFuL)
                }
            }

            override fun toBitsU64(value: IntArray): ULong = arrayToBitsU64(this, value)

            override fun fromBitsU64(bits: ULong): IntArray = arrayFromBitsU64(this, bits)
        }

    public fun isize64Array(length: Int): TypeLayout<IntArray> =
        object : TypeLayout<IntArray> {
            override val sizeBytes: Long = length.toLong() * 8L
            override val alignBytes: Long = 8L

            override fun readNative(addr: Long): IntArray =
                IntArray(length) { i -> kmLoadU64(addr + i.toLong() * 8L).toInt() }

            override fun writeNative(addr: Long, value: IntArray) {
                val n = minOf(length, value.size)
                for (i in 0 until n) {
                    kmStoreU64(addr + i.toLong() * 8L, value[i].toLong().toULong())
                }
            }

            override fun readBytes(buf: ByteArray, offset: Int): IntArray =
                IntArray(length) { i -> kmBytesLoadU64Le(buf, offset + i * 8).toInt() }

            override fun writeBytes(buf: ByteArray, offset: Int, value: IntArray) {
                val n = minOf(length, value.size)
                for (i in 0 until n) {
                    kmBytesStoreU64Le(buf, offset + i * 8, value[i].toLong().toULong())
                }
            }

            override fun toBitsU64(value: IntArray): ULong = arrayToBitsU64(this, value)

            override fun fromBitsU64(bits: ULong): IntArray = arrayFromBitsU64(this, bits)
        }
}

private fun <T> allocNativeArrayFromList(
    values: List<*>,
    layout: TypeLayout<T>,
    cast: (Any?) -> T,
): MutPtr {
    val count = values.size.toLong()
    val stride = maxOf(1L, layout.strideBytes)
    val bytes = maxOf(1L, count * stride)
    val addr = kmAlloc(bytes, maxOf(1L, layout.alignBytes))
    for (index in values.indices) {
        layout.writeNative(addr + index.toLong() * stride, cast(values[index]))
    }
    return MutPtr(addr, count)
}

private fun nativePtrFromList(values: List<*>): MutPtr {
    if (values.isEmpty()) return MutPtr(16L, 0L)
    return when (values[0]) {
        is Boolean -> allocNativeArrayFromList(values, PrimitiveLayouts.BOOL) { it as Boolean }
        is Byte -> allocNativeArrayFromList(values, PrimitiveLayouts.I8) { it as Byte }
        is UByte -> allocNativeArrayFromList(values, PrimitiveLayouts.U8) { it as UByte }
        is Short -> allocNativeArrayFromList(values, PrimitiveLayouts.I16) { it as Short }
        is UShort -> allocNativeArrayFromList(values, PrimitiveLayouts.U16) { it as UShort }
        is Int -> allocNativeArrayFromList(values, PrimitiveLayouts.I32) { it as Int }
        is UInt -> allocNativeArrayFromList(values, PrimitiveLayouts.U32) { it as UInt }
        is Long -> allocNativeArrayFromList(values, PrimitiveLayouts.I64) { it as Long }
        is ULong -> allocNativeArrayFromList(values, PrimitiveLayouts.U64) { it as ULong }
        is Float -> allocNativeArrayFromList(values, PrimitiveLayouts.F32) { it as Float }
        is Double -> allocNativeArrayFromList(values, PrimitiveLayouts.F64) { it as Double }
        is Char -> allocNativeArrayFromList(values, PrimitiveLayouts.CHAR) { it as Char }
        is F16 -> allocNativeArrayFromList(values, PrimitiveLayouts.F16_LAYOUT) { it as F16 }
        is F128 -> allocNativeArrayFromList(values, PrimitiveLayouts.F128_LAYOUT) { it as F128 }
        is U128 -> allocNativeArrayFromList(
            values,
            PrimitiveLayouts.u128Layout({ hi, lo -> U128(hi, lo) }, { it.high }, { it.low }),
        ) { it as U128 }
        is I128 -> allocNativeArrayFromList(
            values,
            PrimitiveLayouts.i128Layout({ hi, lo -> I128(hi, lo) }, { it.high }, { it.low }),
        ) { it as I128 }
        is ConstPtr -> allocNativeArrayFromList(values, PrimitiveLayouts.constPtrLayout()) { it as ConstPtr }
        is MutPtr -> allocNativeArrayFromList(values, PrimitiveLayouts.mutPtrLayout()) { it as MutPtr }
        is RustMutableReference<*> -> {
            val layout = PrimitiveLayouts.mutPtrLayout()
            allocNativeArrayFromList(values, layout) { (it as RustMutableReference<*>).pointer }
        }
        else -> {
            val elemName = values[0]?.let { it::class.simpleName } ?: "null"
            throw UnsupportedOperationException("Cannot convert List<${'$'}elemName> to raw native pointer")
        }
    }
}

@OptIn(ExperimentalUnsignedTypes::class)
public fun mutPtrFromRustCast(value: Any?): MutPtr =
    when (value) {
        is ConstPtr -> MutPtr(value.rawAddress, value.rawMeta)
        is MutPtr -> MutPtr(value.rawAddress, value.rawMeta)
        is LinearSliceView<*> -> MutPtr(value.pointer.rawAddress, value.size.toLong())
        is LinearMutSliceView<*> -> MutPtr(value.pointer.rawAddress, value.size.toLong())
        is RustMutableReference<*> -> MutPtr(value.pointer.rawAddress, value.pointer.rawMeta)
        is AddressableLocal<*> -> MutPtr(value.rawAddress)
        is AddressableArrayLocal<*> -> MutPtr(value.rawAddress, value.length.toLong())
        is BooleanArray -> AddressableArrayLocal.fromBooleanArray(value).let { MutPtr(it.rawAddress, it.length.toLong()) }
        is ByteArray -> AddressableArrayLocal.fromByteArray(value).let { MutPtr(it.rawAddress, it.length.toLong()) }
        is UByteArray -> AddressableArrayLocal.fromUByteArray(value).let { MutPtr(it.rawAddress, it.length.toLong()) }
        is ShortArray -> AddressableArrayLocal.fromShortArray(value).let { MutPtr(it.rawAddress, it.length.toLong()) }
        is UShortArray -> AddressableArrayLocal.fromUShortArray(value).let { MutPtr(it.rawAddress, it.length.toLong()) }
        is IntArray -> AddressableArrayLocal.fromIntArray(value).let { MutPtr(it.rawAddress, it.length.toLong()) }
        is UIntArray -> AddressableArrayLocal.fromUIntArray(value).let { MutPtr(it.rawAddress, it.length.toLong()) }
        is LongArray -> AddressableArrayLocal.fromLongArray(value).let { MutPtr(it.rawAddress, it.length.toLong()) }
        is ULongArray -> AddressableArrayLocal.fromULongArray(value).let { MutPtr(it.rawAddress, it.length.toLong()) }
        is FloatArray -> AddressableArrayLocal.fromFloatArray(value).let { MutPtr(it.rawAddress, it.length.toLong()) }
        is DoubleArray -> AddressableArrayLocal.fromDoubleArray(value).let { MutPtr(it.rawAddress, it.length.toLong()) }
        is String -> value.encodeToByteArray().let { bytes ->
            AddressableArrayLocal.fromByteArray(bytes).let { MutPtr(it.rawAddress, it.length.toLong()) }
        }
        is List<*> -> nativePtrFromList(value)
        else -> throw UnsupportedOperationException(
            "Cannot convert ${'$'}{value?.let { it::class.simpleName } ?: "null"} to raw native pointer",
        )
    }

public fun constPtrFromRustCast(value: Any?): ConstPtr {
    val mutPtr = mutPtrFromRustCast(value)
    return ConstPtr(mutPtr.rawAddress, mutPtr.rawMeta)
}

public class AddressableLocal<T>(
    initialValue: T,
    public val layout: TypeLayout<T>,
) {
    public val rawAddress: Long =
        kmAlloc(maxOf(1L, layout.sizeBytes), maxOf(1L, layout.alignBytes)).also { addr ->
            layout.writeNative(addr, initialValue)
        }

    public operator fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): T =
        layout.readNative(rawAddress)

    public operator fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: T) {
        layout.writeNative(rawAddress, value)
    }

    public fun asConstPtr(): ConstPtr = ConstPtr(rawAddress)

    public fun asMutPtr(): MutPtr = MutPtr(rawAddress)
}

public class AddressableArrayLocal<A>(
    initialValue: A,
    public val layout: TypeLayout<A>,
    public val length: Int,
    public val elementStrideBytes: Long,
) {
    public val rawAddress: Long =
        kmAlloc(maxOf(1L, layout.sizeBytes), maxOf(1L, layout.alignBytes)).also { addr ->
            layout.writeNative(addr, initialValue)
        }

    public operator fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): A =
        layout.readNative(rawAddress)

    public operator fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: A) {
        layout.writeNative(rawAddress, value)
    }

    public fun asConstPtr(): ConstPtr = ConstPtr(rawAddress)

    public fun asMutPtr(): MutPtr = MutPtr(rawAddress)

    public fun elementConstPtr(index: Int = 0): ConstPtr =
        ConstPtr(rawAddress + index.toLong() * elementStrideBytes)

    public fun elementMutPtr(index: Int = 0): MutPtr =
        MutPtr(rawAddress + index.toLong() * elementStrideBytes)

    public companion object {
        public fun fromBooleanArray(values: BooleanArray): AddressableArrayLocal<BooleanArray> =
            AddressableArrayLocal(values, PrimitiveArrayLayouts.booleanArray(values.size), values.size, 1L)

        public fun fromByteArray(values: ByteArray): AddressableArrayLocal<ByteArray> =
            AddressableArrayLocal(values, PrimitiveArrayLayouts.byteArray(values.size), values.size, 1L)

        @OptIn(ExperimentalUnsignedTypes::class)
        public fun fromUByteArray(values: UByteArray): AddressableArrayLocal<UByteArray> =
            AddressableArrayLocal(values, PrimitiveArrayLayouts.uByteArray(values.size), values.size, 1L)

        public fun fromShortArray(values: ShortArray): AddressableArrayLocal<ShortArray> =
            AddressableArrayLocal(values, PrimitiveArrayLayouts.shortArray(values.size), values.size, 2L)

        @OptIn(ExperimentalUnsignedTypes::class)
        public fun fromUShortArray(values: UShortArray): AddressableArrayLocal<UShortArray> =
            AddressableArrayLocal(values, PrimitiveArrayLayouts.uShortArray(values.size), values.size, 2L)

        public fun fromIntArray(values: IntArray): AddressableArrayLocal<IntArray> =
            AddressableArrayLocal(values, PrimitiveArrayLayouts.intArray(values.size), values.size, 4L)

        public fun fromUsize64Array(values: IntArray): AddressableArrayLocal<IntArray> =
            AddressableArrayLocal(values, PrimitiveArrayLayouts.usize64Array(values.size), values.size, 8L)

        public fun fromIsize64Array(values: IntArray): AddressableArrayLocal<IntArray> =
            AddressableArrayLocal(values, PrimitiveArrayLayouts.isize64Array(values.size), values.size, 8L)

        @OptIn(ExperimentalUnsignedTypes::class)
        public fun fromUIntArray(values: UIntArray): AddressableArrayLocal<UIntArray> =
            AddressableArrayLocal(values, PrimitiveArrayLayouts.uIntArray(values.size), values.size, 4L)

        public fun fromLongArray(values: LongArray): AddressableArrayLocal<LongArray> =
            AddressableArrayLocal(values, PrimitiveArrayLayouts.longArray(values.size), values.size, 8L)

        @OptIn(ExperimentalUnsignedTypes::class)
        public fun fromULongArray(values: ULongArray): AddressableArrayLocal<ULongArray> =
            AddressableArrayLocal(values, PrimitiveArrayLayouts.uLongArray(values.size), values.size, 8L)

        public fun fromFloatArray(values: FloatArray): AddressableArrayLocal<FloatArray> =
            AddressableArrayLocal(values, PrimitiveArrayLayouts.floatArray(values.size), values.size, 4L)

        public fun fromDoubleArray(values: DoubleArray): AddressableArrayLocal<DoubleArray> =
            AddressableArrayLocal(values, PrimitiveArrayLayouts.doubleArray(values.size), values.size, 8L)

        public fun <E> fromList(
            values: List<E>,
            elementLayout: TypeLayout<E>,
        ): AddressableArrayLocal<List<E>> =
            AddressableArrayLocal(
                values,
                fixedListTypeLayout(elementLayout, values.size),
                values.size,
                elementLayout.strideBytes,
            )
    }
}

public class RustMutableReference<T>(
    public val pointer: MutPtr,
    public val layout: TypeLayout<T>,
) {
    public fun read(): T = layout.readNative(pointer.rawAddress)

    public fun write(value: T) {
        layout.writeNative(pointer.rawAddress, value)
    }

    public fun reborrow(): RustMutableReference<T> = RustMutableReference(pointer, layout)

    public fun asMutPtr(): MutPtr = pointer

    public fun asConstPtr(): ConstPtr = ConstPtr(pointer.rawAddress)
}

public fun <Src, Dst> transmuteWithLayouts(
    value: Src,
    srcLayout: TypeLayout<Src>,
    dstLayout: TypeLayout<Dst>,
): Dst {
    if (srcLayout.sizeBytes == dstLayout.sizeBytes && srcLayout.sizeBytes in 1L..8L) {
        return dstLayout.fromBitsU64(srcLayout.toBitsU64(value))
    }
    val bytes = maxOf(1L, maxOf(srcLayout.sizeBytes, dstLayout.sizeBytes))
    val align = maxOf(1L, maxOf(srcLayout.alignBytes, dstLayout.alignBytes))
    val scratch = kmAlloc(bytes, align)
    srcLayout.writeNative(scratch, value)
    val out = dstLayout.readNative(scratch)
    kmDealloc(scratch, bytes, align)
    return out
}

public fun <Src, Dst> transmute(
    value: Src,
    srcLayout: TypeLayout<Src> = UnresolvedTypeLayout(),
    dstLayout: TypeLayout<Dst> = UnresolvedTypeLayout(),
): Dst = transmuteWithLayouts(value, srcLayout, dstLayout)

public fun <Src, Dst> transmuteUnchecked(
    value: Src,
    srcLayout: TypeLayout<Src> = UnresolvedTypeLayout(),
    dstLayout: TypeLayout<Dst> = UnresolvedTypeLayout(),
): Dst = transmuteWithLayouts(value, srcLayout, dstLayout)

public fun <T> copyMemoryValues(
    src: ConstPtr,
    dst: MutPtr,
    count: Long,
    layout: TypeLayout<T>,
) {
    val bytes = count * layout.sizeBytes
    kmCopyNonoverlapping(dst.rawAddress, src.rawAddress, bytes)
}

public fun <T> copyOverlappingMemoryValues(
    src: ConstPtr,
    dst: MutPtr,
    count: Long,
    layout: TypeLayout<T>,
) {
    val bytes = count * layout.sizeBytes
    kmCopy(dst.rawAddress, src.rawAddress, bytes)
}

public fun <T> atomicLoadWithLayout(
    addr: Long,
    ord: Int,
    layout: TypeLayout<T>,
): T =
    when (layout.sizeBytes) {
        1L -> layout.fromBitsU64(kmAtomicLoadU8(addr, ord).toULong())
        2L -> layout.fromBitsU64(kmAtomicLoadU16(addr, ord).toULong())
        4L -> layout.fromBitsU64(kmAtomicLoadU32(addr, ord).toULong())
        8L -> layout.fromBitsU64(kmAtomicLoadU64(addr, ord))
        16L -> {
            val res = kmAtomicCxchgU128Pair(addr, 0uL, 0uL, 0uL, 0uL)
            val scratch = kmAlloc(16L, 16L)
            kmStoreU64(scratch, res.prevLo)
            kmStoreU64(scratch + 8L, res.prevHi)
            val out = layout.readNative(scratch)
            kmDealloc(scratch, 16L, 16L)
            out
        }
        else -> error("Unsupported atomic load width: " + layout.sizeBytes)
    }

public fun <T> atomicStoreWithLayout(
    addr: Long,
    value: T,
    ord: Int,
    layout: TypeLayout<T>,
) {
    when (layout.sizeBytes) {
        1L -> kmAtomicStoreU8(addr, layout.toBitsU64(value).toUByte(), ord)
        2L -> kmAtomicStoreU16(addr, layout.toBitsU64(value).toUShort(), ord)
        4L -> kmAtomicStoreU32(addr, layout.toBitsU64(value).toUInt(), ord)
        8L -> kmAtomicStoreU64(addr, layout.toBitsU64(value), ord)
        16L -> {
            val scratch = kmAlloc(16L, 16L)
            layout.writeNative(scratch, value)
            val desLo = kmLoadU64(scratch)
            val desHi = kmLoadU64(scratch + 8L)
            kmDealloc(scratch, 16L, 16L)
            var cur = kmAtomicCxchgU128Pair(addr, 0uL, 0uL, desLo, desHi)
            while (!cur.succeeded) {
                cur = kmAtomicCxchgU128Pair(addr, cur.prevLo, cur.prevHi, desLo, desHi)
            }
        }
        else -> error("Unsupported atomic store width: " + layout.sizeBytes)
    }
}

public fun <T> atomicXchgWithLayout(
    addr: Long,
    value: T,
    ord: Int,
    layout: TypeLayout<T>,
): T =
    when (layout.sizeBytes) {
        1L -> layout.fromBitsU64(kmAtomicXchgU8(addr, layout.toBitsU64(value).toUByte(), ord).toULong())
        2L -> layout.fromBitsU64(kmAtomicXchgU16(addr, layout.toBitsU64(value).toUShort(), ord).toULong())
        4L -> layout.fromBitsU64(kmAtomicXchgU32(addr, layout.toBitsU64(value).toUInt(), ord).toULong())
        8L -> layout.fromBitsU64(kmAtomicXchgU64(addr, layout.toBitsU64(value), ord))
        16L -> {
            val scratch = kmAlloc(16L, 16L)
            layout.writeNative(scratch, value)
            val desLo = kmLoadU64(scratch)
            val desHi = kmLoadU64(scratch + 8L)
            var cur = kmAtomicCxchgU128Pair(addr, 0uL, 0uL, desLo, desHi)
            while (!cur.succeeded) {
                cur = kmAtomicCxchgU128Pair(addr, cur.prevLo, cur.prevHi, desLo, desHi)
            }
            kmStoreU64(scratch, cur.prevLo)
            kmStoreU64(scratch + 8L, cur.prevHi)
            val out = layout.readNative(scratch)
            kmDealloc(scratch, 16L, 16L)
            out
        }
        else -> error("Unsupported atomic xchg width: " + layout.sizeBytes)
    }

public data class AtomicCxchgLayoutOutcome<T>(
    public val first: T,
    public val second: Boolean,
)

public fun <T> atomicCxchgWithLayout(
    addr: Long,
    expected: T,
    desired: T,
    succ: Int,
    fail: Int,
    layout: TypeLayout<T>,
): AtomicCxchgLayoutOutcome<T> =
    when (layout.sizeBytes) {
        1L -> {
            val res = kmAtomicCxchgU8(addr, layout.toBitsU64(expected).toUByte(), layout.toBitsU64(desired).toUByte(), succ, fail)
            AtomicCxchgLayoutOutcome(layout.fromBitsU64(res.previous.toULong()), res.succeeded)
        }
        2L -> {
            val res = kmAtomicCxchgU16(addr, layout.toBitsU64(expected).toUShort(), layout.toBitsU64(desired).toUShort(), succ, fail)
            AtomicCxchgLayoutOutcome(layout.fromBitsU64(res.previous.toULong()), res.succeeded)
        }
        4L -> {
            val res = kmAtomicCxchgU32(addr, layout.toBitsU64(expected).toUInt(), layout.toBitsU64(desired).toUInt(), succ, fail)
            AtomicCxchgLayoutOutcome(layout.fromBitsU64(res.previous.toULong()), res.succeeded)
        }
        8L -> {
            val res = kmAtomicCxchgU64(addr, layout.toBitsU64(expected), layout.toBitsU64(desired), succ, fail)
            AtomicCxchgLayoutOutcome(layout.fromBitsU64(res.previous), res.succeeded)
        }
        16L -> {
            val scratch = kmAlloc(32L, 16L)
            layout.writeNative(scratch, expected)
            layout.writeNative(scratch + 16L, desired)
            val expLo = kmLoadU64(scratch)
            val expHi = kmLoadU64(scratch + 8L)
            val desLo = kmLoadU64(scratch + 16L)
            val desHi = kmLoadU64(scratch + 24L)
            val res = kmAtomicCxchgU128Pair(addr, expLo, expHi, desLo, desHi)
            kmStoreU64(scratch, res.prevLo)
            kmStoreU64(scratch + 8L, res.prevHi)
            val out = layout.readNative(scratch)
            kmDealloc(scratch, 32L, 16L)
            AtomicCxchgLayoutOutcome(out, res.succeeded)
        }
        else -> error("Unsupported atomic cxchg width: " + layout.sizeBytes)
    }

public fun <T, U> atomicRmwWithLayout(
    addr: Long,
    operand: U,
    op: Int,
    ord: Int,
    dstLayout: TypeLayout<T>,
    operandLayout: TypeLayout<U>,
): T {
    val opBits = operandLayout.toBitsU64(operand)
    return when (dstLayout.sizeBytes) {
        1L -> dstLayout.fromBitsU64(kmAtomicRmwU8(addr, opBits.toUByte(), op, ord).toULong())
        2L -> dstLayout.fromBitsU64(kmAtomicRmwU16(addr, opBits.toUShort(), op, ord).toULong())
        4L -> dstLayout.fromBitsU64(kmAtomicRmwU32(addr, opBits.toUInt(), op, ord).toULong())
        8L -> dstLayout.fromBitsU64(kmAtomicRmwU64(addr, opBits, op, ord))
        else -> error("Unsupported atomic rmw width: " + dstLayout.sizeBytes)
    }
}

${CoreIntrinsicLayoutRuntimeSpecs.renderLayoutWithOperationsKotlin(coreNamespace)}""".trimStart()
    }

    public fun renderKmCoreDef(
        cinteropPackage: String,
        ffiModuleName: String = "kmcore",
    ): String = CoreIntrinsicCHeaders.renderKmCoreDef(cinteropPackage, ffiModuleName)

    public fun renderKmCoreInlineHeader(): String = CoreIntrinsicCHeaders.renderKmCoreInlineHeader()

    public fun renderKmCoreAsmHeader(): String = CoreIntrinsicCHeaders.renderKmCoreAsmHeader()

    public fun renderKmCoreAarch64Asm(): String = CoreIntrinsicCHeaders.renderKmCoreAarch64Asm()

    public fun renderKmCoreX8664Asm(): String = CoreIntrinsicCHeaders.renderKmCoreX8664Asm()

    public fun renderKmCoreWrapperC(): String = CoreIntrinsicCHeaders.renderKmCoreWrapperC()

    public fun renderKmCoreIntrinsicsExpectKotlin(coreNamespace: String): String =
        CoreIntrinsicJvmAndNativeBridges.renderKmCoreIntrinsicsExpectKotlin(coreNamespace)

    public fun renderKmCoreNativeActualsKotlin(
        coreNamespace: String,
        cinteropPackage: String,
    ): String = CoreIntrinsicJvmAndNativeBridges.renderKmCoreNativeActualsKotlin(coreNamespace, cinteropPackage)

    public fun renderKmCoreJniC(
        coreNamespace: String,
        jniObjectName: String = "KmCoreJniBridge",
    ): String = CoreIntrinsicJvmAndNativeBridges.renderKmCoreJniC(coreNamespace, jniObjectName)

    public fun renderKmCoreJvmActualsKotlin(
        coreNamespace: String,
        jniLibName: String = "kmcore_jni",
        projectNamespace: String = coreNamespace.substringBeforeLast('.'),
        jniObjectName: String = "KmCoreJniBridge",
    ): String = CoreIntrinsicJvmAndNativeBridges.renderKmCoreJvmActualsKotlin(coreNamespace, jniLibName, projectNamespace, jniObjectName)

    public fun renderKmCoreAndroidActualsKotlin(
        coreNamespace: String,
        jniLibName: String = "kmcore_jni",
        jniObjectName: String = "KmCoreJniBridge",
    ): String = CoreIntrinsicJvmAndNativeBridges.renderKmCoreAndroidActualsKotlin(coreNamespace, jniLibName, jniObjectName)

    public fun renderKmCoreLinearMemoryArenaKotlin(coreNamespace: String): String =
        CoreIntrinsicLinearMemoryArena.renderKmCoreLinearMemoryArenaKotlin(coreNamespace)

    public fun renderKmCoreNapiSharedCppHelpers(): String = CoreIntrinsicJsAndWasmBridges.renderKmCoreNapiSharedCppHelpers()

    public fun renderKmCoreNapiCpp(stemSnake: String = "km_core"): String = CoreIntrinsicJsAndWasmBridges.renderKmCoreNapiCpp(stemSnake)

    public fun renderKmCoreJsActualsKotlin(
        coreNamespace: String,
        ffiModName: String = "kmcore",
    ): String = CoreIntrinsicJsAndWasmBridges.renderKmCoreJsActualsKotlin(coreNamespace, ffiModName)

    public fun renderKmCoreWasmJsActualsKotlin(
        coreNamespace: String,
        ffiModName: String = "kmcore",
    ): String = CoreIntrinsicJsAndWasmBridges.renderKmCoreWasmJsActualsKotlin(coreNamespace, ffiModName)

    public fun renderKmCoreWasmWasiActualsKotlin(coreNamespace: String): String =
        CoreIntrinsicJsAndWasmBridges.renderKmCoreWasmWasiActualsKotlin(coreNamespace)

    public fun emitAllKmCoreCompanionFiles(
        coreNamespace: String,
        ffiModuleName: String = "kmcore",
        projectNamespace: String = coreNamespace.substringBeforeLast('.'),
    ): List<CompanionInteropFile> {
        val cinteropPkg = "$coreNamespace.intrinsics.cinterop"
        val jniLibName = "${ffiModuleName}_jni"
        return listOf(
            CompanionInteropFile(
                relativePath = "KmCoreIntrinsics.kt",
                kind = CompanionInteropKind.CommonKotlinSupport,
                content = renderKmCoreIntrinsicsExpectKotlin(coreNamespace),
                ffiModuleName = ffiModuleName,
            ),
            CompanionInteropFile(
                relativePath = "KmLinearMemoryArena.kt",
                kind = CompanionInteropKind.CommonKotlinSupport,
                content = renderKmCoreLinearMemoryArenaKotlin(coreNamespace),
                ffiModuleName = ffiModuleName,
            ),
            CompanionInteropFile(
                relativePath = "KmCore.def",
                kind = CompanionInteropKind.CInteropDef,
                content = renderKmCoreDef(cinteropPkg, ffiModuleName),
                ffiModuleName = ffiModuleName,
            ),
            CompanionInteropFile(
                relativePath = "km_core_intrinsics_inline.h",
                kind = CompanionInteropKind.CHeader,
                content = renderKmCoreInlineHeader(),
                ffiModuleName = ffiModuleName,
            ),
            CompanionInteropFile(
                relativePath = "km_core_llvm_asm.h",
                kind = CompanionInteropKind.CHeader,
                content = renderKmCoreAsmHeader(),
                ffiModuleName = ffiModuleName,
            ),
            CompanionInteropFile(
                relativePath = "km_core_llvm_aarch64.S",
                kind = CompanionInteropKind.AsmSource,
                content = renderKmCoreAarch64Asm(),
                ffiModuleName = ffiModuleName,
            ),
            CompanionInteropFile(
                relativePath = "km_core_llvm_x86_64.S",
                kind = CompanionInteropKind.AsmSource,
                content = renderKmCoreX8664Asm(),
                ffiModuleName = ffiModuleName,
            ),
            CompanionInteropFile(
                relativePath = "${ffiModuleName}_wrapper.c",
                kind = CompanionInteropKind.CSource,
                content = renderKmCoreWrapperC(),
                ffiModuleName = ffiModuleName,
            ),
            CompanionInteropFile(
                relativePath = "KmCoreNativeActuals.kt",
                kind = CompanionInteropKind.NativeKotlinActuals,
                content = renderKmCoreNativeActualsKotlin(coreNamespace, cinteropPkg),
                ffiModuleName = ffiModuleName,
            ),
            CompanionInteropFile(
                relativePath = "KmCoreJvmActuals.kt",
                kind = CompanionInteropKind.JvmKotlinActuals,
                content = renderKmCoreJvmActualsKotlin(coreNamespace, jniLibName, projectNamespace),
                ffiModuleName = ffiModuleName,
            ),
            CompanionInteropFile(
                relativePath = "KmCoreAndroidActuals.kt",
                kind = CompanionInteropKind.AndroidKotlinActuals,
                content = renderKmCoreAndroidActualsKotlin(coreNamespace, jniLibName),
                ffiModuleName = ffiModuleName,
            ),
            CompanionInteropFile(
                relativePath = "${ffiModuleName}_jni.c",
                kind = CompanionInteropKind.JniCSource,
                content = renderKmCoreJniC(coreNamespace),
                ffiModuleName = ffiModuleName,
            ),
            CompanionInteropFile(
                relativePath = "KmCoreJsActuals.kt",
                kind = CompanionInteropKind.JsKotlinActuals,
                content = renderKmCoreJsActualsKotlin(coreNamespace, ffiModuleName),
                ffiModuleName = ffiModuleName,
            ),
            CompanionInteropFile(
                relativePath = "${ffiModuleName}_bindings.cpp",
                kind = CompanionInteropKind.NapiCppSource,
                content = renderKmCoreNapiCpp(ffiModuleName),
                ffiModuleName = ffiModuleName,
            ),
            CompanionInteropFile(
                relativePath = "binding.gyp",
                kind = CompanionInteropKind.NapiDistFile,
                content = renderKmCoreBindingGyp(ffiModuleName),
                ffiModuleName = ffiModuleName,
            ),
            CompanionInteropFile(
                relativePath = "package.json",
                kind = CompanionInteropKind.NapiDistFile,
                content = renderKmCorePackageJson(ffiModuleName),
                ffiModuleName = ffiModuleName,
            ),
            CompanionInteropFile(
                relativePath = "package-lock.json",
                kind = CompanionInteropKind.NapiDistFile,
                content = renderKmCorePackageLockJson(ffiModuleName),
                ffiModuleName = ffiModuleName,
            ),
            CompanionInteropFile(
                relativePath = "index.js",
                kind = CompanionInteropKind.NapiDistFile,
                content = renderKmCoreIndexJs(ffiModuleName),
                ffiModuleName = ffiModuleName,
            ),
            CompanionInteropFile(
                relativePath = "KmCoreWasmJsActuals.kt",
                kind = CompanionInteropKind.WasmJsKotlinActuals,
                content = renderKmCoreWasmJsActualsKotlin(coreNamespace, ffiModuleName),
                ffiModuleName = ffiModuleName,
            ),
            CompanionInteropFile(
                relativePath = "KmCoreWasmWasiActuals.kt",
                kind = CompanionInteropKind.WasmWasiKotlinActuals,
                content = renderKmCoreWasmWasiActualsKotlin(coreNamespace),
                ffiModuleName = ffiModuleName,
            ),
        )
    }

    public fun renderKmCoreBindingGyp(ffiModuleName: String = "kmcore"): String =
        """
{
  "targets": [
    {
      "target_name": "${ffiModuleName}_native",
      "sources": [
        "../src/${ffiModuleName}_bindings.cpp"
      ],
      "include_dirs": [
        "<!@(node -p \"require('node-addon-api').include\")"
      ],
      "dependencies": [
        "<!(node -p \"require('node-addon-api').gyp\")"
      ],
      "cflags!": [ "-fno-exceptions" ],
      "cflags_cc!": [ "-fno-exceptions" ],
      "defines": [ "NAPI_DISABLE_CPP_EXCEPTIONS" ],
      "conditions": [
        [
          "OS=='linux'",
          {
            "libraries": [ "-lm" ]
          }
        ]
      ],
      "xcode_settings": {
        "GCC_ENABLE_CPP_EXCEPTIONS": "YES",
        "CLANG_CXX_LIBRARY": "libc++",
        "MACOSX_DEPLOYMENT_TARGET": "10.15"
      }
    }
  ]
}
""".trimStart()

    public fun renderKmCorePackageJson(ffiModuleName: String = "kmcore"): String =
        """
{
  "name": "@kotlinmania/$ffiModuleName-native-bindings",
  "version": "0.1.0",
  "description": "N-API C++ addon providing inline C/ASM core intrinsics for Kotlin/JS and Kotlin/WASM targets",
  "main": "index.js",
  "dependencies": {
    "node-addon-api": "^8.0.0"
  },
  "gypfile": false
}
""".trimStart()

    public fun renderKmCorePackageLockJson(ffiModuleName: String = "kmcore"): String =
        """
{
  "name": "@kotlinmania/$ffiModuleName-native-bindings",
  "version": "0.1.0",
  "lockfileVersion": 3,
  "requires": true,
  "packages": {
    "": {
      "name": "@kotlinmania/$ffiModuleName-native-bindings",
      "version": "0.1.0",
      "dependencies": {
        "node-addon-api": "^8.0.0"
      }
    },
    "node_modules/node-addon-api": {
      "version": "8.9.2",
      "resolved": "https://registry.npmjs.org/node-addon-api/-/node-addon-api-8.9.2.tgz",
      "integrity": "sha512-VijLXbi3UACN69I0JVXJsX4tjACjNoQDgv2gTF6sx2wWEi8tkSg2eX8p5gSIFi8z2+DL3oHmY6OyKce38SDolg==",
      "license": "MIT",
      "engines": {
        "node": "^18 || ^20 || >= 21"
      }
    }
  }
}
""".trimStart()

    public fun renderKmCoreIndexJs(ffiModuleName: String = "kmcore"): String =
        """
const candidates = [
    './build/Release/${ffiModuleName}_native.node',
    './build/Debug/${ffiModuleName}_native.node',
];
let loaded = null;
let lastErr = null;
for (const candidate of candidates) {
    try {
        loaded = require(candidate);
        break;
    } catch (err) {
        lastErr = err;
    }
}
if (!loaded) {
    throw new Error('Could not load ${ffiModuleName}_native addon: ' + (lastErr ? lastErr.message : 'not found'));
}
module.exports = loaded;
""".trimStart()
}
