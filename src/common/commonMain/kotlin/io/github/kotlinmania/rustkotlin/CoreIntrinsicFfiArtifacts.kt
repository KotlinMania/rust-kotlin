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
            append("    override fun toString(): String = \"0x\" + rawAddress.toULong().toString(16)\n")
            append("}")
        }

    public fun renderTypeLayoutSupportKotlin(coreNamespace: String): String {
        val intrinsicsPkg = "$coreNamespace.intrinsics"
        val ptrPkg = "$coreNamespace.ptr"
        return """
package $coreNamespace.mem

import $coreNamespace.F128
import $coreNamespace.F16
import $intrinsicsPkg.kmAlloc
import $intrinsicsPkg.kmAtomicCxchgU128Pair
import $intrinsicsPkg.kmAtomicCxchgU16
import $intrinsicsPkg.kmAtomicCxchgU32
import $intrinsicsPkg.kmAtomicCxchgU64
import $intrinsicsPkg.kmAtomicCxchgU8
import $intrinsicsPkg.kmAtomicLoadU16
import $intrinsicsPkg.kmAtomicLoadU32
import $intrinsicsPkg.kmAtomicLoadU64
import $intrinsicsPkg.kmAtomicLoadU8
import $intrinsicsPkg.kmAtomicRmwU16
import $intrinsicsPkg.kmAtomicRmwU32
import $intrinsicsPkg.kmAtomicRmwU64
import $intrinsicsPkg.kmAtomicRmwU8
import $intrinsicsPkg.kmAtomicStoreU16
import $intrinsicsPkg.kmAtomicStoreU32
import $intrinsicsPkg.kmAtomicStoreU64
import $intrinsicsPkg.kmAtomicStoreU8
import $intrinsicsPkg.kmAtomicXchgU16
import $intrinsicsPkg.kmAtomicXchgU32
import $intrinsicsPkg.kmAtomicXchgU64
import $intrinsicsPkg.kmAtomicXchgU8
import $intrinsicsPkg.kmCopy
import $intrinsicsPkg.kmCopyNonoverlapping
import $intrinsicsPkg.kmDealloc
import $intrinsicsPkg.kmLoadF32
import $intrinsicsPkg.kmLoadF64
import $intrinsicsPkg.kmLoadU16
import $intrinsicsPkg.kmLoadU32
import $intrinsicsPkg.kmLoadU64
import $intrinsicsPkg.kmLoadU8
import $intrinsicsPkg.kmStoreF32
import $intrinsicsPkg.kmStoreF64
import $intrinsicsPkg.kmStoreU16
import $intrinsicsPkg.kmStoreU32
import $intrinsicsPkg.kmStoreU64
import $intrinsicsPkg.kmStoreU8
import $intrinsicsPkg.kmVolatileLoadU16
import $intrinsicsPkg.kmVolatileLoadU32
import $intrinsicsPkg.kmVolatileLoadU64
import $intrinsicsPkg.kmVolatileLoadU8
import $intrinsicsPkg.kmVolatileStoreU16
import $intrinsicsPkg.kmVolatileStoreU32
import $intrinsicsPkg.kmVolatileStoreU64
import $intrinsicsPkg.kmVolatileStoreU8
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
    public val isGcObjectSlot: Boolean
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
            override fun writeNative(addr: Long, value: Boolean): Unit = kmStoreU8(addr, if (value) 1u else 0u)
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
            override fun readNative(addr: Long): Byte = kmLoadU8(addr).toByte()
            override fun writeNative(addr: Long, value: Byte): Unit = kmStoreU8(addr, value.toUByte())
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
            override fun writeNative(addr: Long, value: UByte): Unit = kmStoreU8(addr, value)
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
            override fun readNative(addr: Long): Short = kmLoadU16(addr).toShort()
            override fun writeNative(addr: Long, value: Short): Unit = kmStoreU16(addr, value.toUShort())
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
            override fun writeNative(addr: Long, value: UShort): Unit = kmStoreU16(addr, value)
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
            override fun readNative(addr: Long): Int = kmLoadU32(addr).toInt()
            override fun writeNative(addr: Long, value: Int): Unit = kmStoreU32(addr, value.toUInt())
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
            override fun writeNative(addr: Long, value: UInt): Unit = kmStoreU32(addr, value)
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
            override fun writeNative(addr: Long, value: Char): Unit = kmStoreU32(addr, value.code.toUInt())
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
            override fun readNative(addr: Long): Float = kmLoadF32(addr)
            override fun writeNative(addr: Long, value: Float): Unit = kmStoreF32(addr, value)
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
            override fun readNative(addr: Long): Long = kmLoadU64(addr).toLong()
            override fun writeNative(addr: Long, value: Long): Unit = kmStoreU64(addr, value.toULong())
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
            override fun writeNative(addr: Long, value: ULong): Unit = kmStoreU64(addr, value)
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
            override fun readNative(addr: Long): Double = kmLoadF64(addr)
            override fun writeNative(addr: Long, value: Double): Unit = kmStoreF64(addr, value)
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
            override fun readNative(addr: Long): F16 = F16.fromBits(kmLoadU16(addr))
            override fun writeNative(addr: Long, value: F16): Unit = kmStoreU16(addr, value.toBits())
            override fun readBytes(buf: ByteArray, offset: Int): F16 = F16.fromBits(kmBytesLoadU16Le(buf, offset))
            override fun writeBytes(buf: ByteArray, offset: Int, value: F16): Unit =
                kmBytesStoreU16Le(buf, offset, value.toBits())
            override fun readVolatileNative(addr: Long): F16 = F16.fromBits(kmVolatileLoadU16(addr))
            override fun writeVolatileNative(addr: Long, value: F16): Unit = kmVolatileStoreU16(addr, value.toBits())
            override fun toBitsU64(value: F16): ULong = value.toBits().toULong()
            override fun fromBitsU64(bits: ULong): F16 = F16.fromBits(bits.toUShort())
        }

    public val F128_LAYOUT: TypeLayout<F128> = u128Layout(::F128, { it.high }, { it.low })

    public val ISIZE64: TypeLayout<Int> =
        object : TypeLayout<Int> {
            override val sizeBytes: Long = 8L
            override val alignBytes: Long = 8L
            override fun readNative(addr: Long): Int = kmLoadU64(addr).toLong().toInt()
            override fun writeNative(addr: Long, value: Int): Unit = kmStoreU64(addr, value.toLong().toULong())
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
            override fun writeNative(addr: Long, value: Int): Unit = kmStoreU64(addr, value.toUInt().toULong())
            override fun readBytes(buf: ByteArray, offset: Int): Int = kmBytesLoadU64Le(buf, offset).toInt()
            override fun writeBytes(buf: ByteArray, offset: Int, value: Int): Unit =
                kmBytesStoreU64Le(buf, offset, value.toUInt().toULong())
            override fun readVolatileNative(addr: Long): Int = kmVolatileLoadU64(addr).toInt()
            override fun writeVolatileNative(addr: Long, value: Int): Unit = kmVolatileStoreU64(addr, value.toUInt().toULong())
            override fun toBitsU64(value: Int): ULong = value.toUInt().toULong()
            override fun fromBitsU64(bits: ULong): Int = bits.toInt()
        }

    public val ISIZE32: TypeLayout<Int> = I32
    public val USIZE32: TypeLayout<Int> = I32

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
    ): TypeLayout<T> =
        object : TypeLayout<T> {
            override val sizeBytes: Long = 16L
            override val alignBytes: Long = 16L
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
            val cell = allocateNativeCell(inner.fromBitsU64(bits), inner)
            return project(cell, inner)
        }
    }

public class AlignedTypeLayout<T>(
    public val inner: TypeLayout<T>,
    override val alignBytes: Long,
) : TypeLayout<T> {
    override val sizeBytes: Long get() = alignUpBytes(inner.sizeBytes, alignBytes)
    override val isGcObjectSlot: Boolean get() = inner.isGcObjectSlot
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
    override val isGcObjectSlot: Boolean get() = elementLayout.isGcObjectSlot

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

private object ManagedSlotRegistry {
    private var nextHandleId: Long = 1L
    private val slotTable: MutableMap<Long, Any?> = linkedMapOf()
    private val freeIds: MutableList<Long> = mutableListOf()

    fun store(value: Any?): Long {
        val id = if (freeIds.isNotEmpty()) freeIds.removeAt(freeIds.size - 1) else nextHandleId++
        slotTable[id] = value
        return id
    }

    fun load(id: Long): Any? = slotTable[id]

    fun release(id: Long) {
        if (slotTable.containsKey(id)) {
            slotTable.remove(id)
            freeIds.add(id)
        }
    }
}

public class ObjectSlotLayout<T> : TypeLayout<T> {
    override val sizeBytes: Long = 8L
    override val alignBytes: Long = 8L
    override val isGcObjectSlot: Boolean = true

    @Suppress("UNCHECKED_CAST")
    override fun readNative(addr: Long): T {
        val handleId = kmLoadU64(addr).toLong()
        return ManagedSlotRegistry.load(handleId) as T
    }

    override fun writeNative(addr: Long, value: T) {
        val handleId = ManagedSlotRegistry.store(value)
        kmStoreU64(addr, handleId.toULong())
    }

    @Suppress("UNCHECKED_CAST")
    override fun readBytes(buf: ByteArray, offset: Int): T {
        val handleId = kmBytesLoadU64Le(buf, offset).toLong()
        return ManagedSlotRegistry.load(handleId) as T
    }

    override fun writeBytes(buf: ByteArray, offset: Int, value: T) {
        val handleId = ManagedSlotRegistry.store(value)
        kmBytesStoreU64Le(buf, offset, handleId.toULong())
    }
}

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

public fun mutPtrFromRustCast(value: Any?): MutPtr =
    when (value) {
        is ConstPtr -> MutPtr(value.rawAddress, value.rawMeta)
        is MutPtr -> MutPtr(value.rawAddress, value.rawMeta)
        is RustMutableReference<*> -> MutPtr(value.pointer.rawAddress, value.pointer.rawMeta)
        is AddressableLocal<*> -> MutPtr(value.rawAddress)
        is AddressableArrayLocal<*> -> MutPtr(value.rawAddress, value.length.toLong())
        else -> {
            val addr = kmAlloc(8L, 8L)
            val handleId = ManagedSlotRegistry.store(value)
            kmStoreU64(addr, handleId.toULong())
            val meta = if (value is Collection<*>) value.size.toLong() else 0L
            MutPtr(addr, meta)
        }
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

public fun <T> copyMemoryValues(
    src: ConstPtr,
    dst: MutPtr,
    count: Long,
    layout: TypeLayout<T>,
) {
    kmCopyNonoverlapping(dst.rawAddress, src.rawAddress, count * layout.sizeBytes)
}

public fun <T> copyOverlappingMemoryValues(
    src: ConstPtr,
    dst: MutPtr,
    count: Long,
    layout: TypeLayout<T>,
) {
    kmCopy(dst.rawAddress, src.rawAddress, count * layout.sizeBytes)
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
""".trimStart()
    }

    public fun renderKmCoreDef(
        cinteropPackage: String,
        ffiModuleName: String = "kmcore",
    ): String =
        """
package = $cinteropPackage
headers = km_core_intrinsics_inline.h km_core_llvm_asm.h
headerFilter = km_core_intrinsics_inline.h km_core_llvm_asm.h
compilerOpts = -O2 -Isrc/native/interop/cinterop
staticLibraries = ${ffiModuleName}_wrapper.a
""".trimStart()

    public fun renderKmCoreInlineHeader(): String =
        """
#ifndef KM_CORE_INTRINSICS_INLINE_H
#define KM_CORE_INTRINSICS_INLINE_H

#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>

#if !defined(__cplusplus)
#include <stdatomic.h>
#elif defined(_MSC_VER) && !defined(__clang__)
#include <atomic>
#endif

#if defined(_WIN32)
#include <malloc.h>
#endif

#if defined(__clang__) || defined(__GNUC__)
#define KM_ALWAYS_INLINE static inline __attribute__((always_inline))
#elif defined(_MSC_VER)
#define KM_ALWAYS_INLINE static __forceinline
#else
#define KM_ALWAYS_INLINE static inline
#endif

/* Aligned & unaligned scalar memory loads and stores */
KM_ALWAYS_INLINE uint8_t kmLoadU8(int64_t addr) {
    return *(const uint8_t*)(uintptr_t)addr;
}
KM_ALWAYS_INLINE uint16_t kmLoadU16(int64_t addr) {
    uint16_t v;
    memcpy(&v, (const void*)(uintptr_t)addr, 2);
    return v;
}
KM_ALWAYS_INLINE uint32_t kmLoadU32(int64_t addr) {
    uint32_t v;
    memcpy(&v, (const void*)(uintptr_t)addr, 4);
    return v;
}
KM_ALWAYS_INLINE uint64_t kmLoadU64(int64_t addr) {
    uint64_t v;
    memcpy(&v, (const void*)(uintptr_t)addr, 8);
    return v;
}
KM_ALWAYS_INLINE float kmLoadF32(int64_t addr) {
    float v;
    memcpy(&v, (const void*)(uintptr_t)addr, 4);
    return v;
}
KM_ALWAYS_INLINE double kmLoadF64(int64_t addr) {
    double v;
    memcpy(&v, (const void*)(uintptr_t)addr, 8);
    return v;
}

KM_ALWAYS_INLINE void kmStoreU8(int64_t addr, uint8_t val) {
    *(uint8_t*)(uintptr_t)addr = val;
}
KM_ALWAYS_INLINE void kmStoreU16(int64_t addr, uint16_t val) {
    memcpy((void*)(uintptr_t)addr, &val, 2);
}
KM_ALWAYS_INLINE void kmStoreU32(int64_t addr, uint32_t val) {
    memcpy((void*)(uintptr_t)addr, &val, 4);
}
KM_ALWAYS_INLINE void kmStoreU64(int64_t addr, uint64_t val) {
    memcpy((void*)(uintptr_t)addr, &val, 8);
}
KM_ALWAYS_INLINE void kmStoreF32(int64_t addr, float val) {
    memcpy((void*)(uintptr_t)addr, &val, 4);
}
KM_ALWAYS_INLINE void kmStoreF64(int64_t addr, double val) {
    memcpy((void*)(uintptr_t)addr, &val, 8);
}

/* Volatile loads and stores */
KM_ALWAYS_INLINE uint8_t kmVolatileLoadU8(int64_t addr) {
    return *(volatile const uint8_t*)(uintptr_t)addr;
}
KM_ALWAYS_INLINE uint16_t kmVolatileLoadU16(int64_t addr) {
    return *(volatile const uint16_t*)(uintptr_t)addr;
}
KM_ALWAYS_INLINE uint32_t kmVolatileLoadU32(int64_t addr) {
    return *(volatile const uint32_t*)(uintptr_t)addr;
}
KM_ALWAYS_INLINE uint64_t kmVolatileLoadU64(int64_t addr) {
    return *(volatile const uint64_t*)(uintptr_t)addr;
}

KM_ALWAYS_INLINE void kmVolatileStoreU8(int64_t addr, uint8_t val) {
    *(volatile uint8_t*)(uintptr_t)addr = val;
}
KM_ALWAYS_INLINE void kmVolatileStoreU16(int64_t addr, uint16_t val) {
    *(volatile uint16_t*)(uintptr_t)addr = val;
}
KM_ALWAYS_INLINE void kmVolatileStoreU32(int64_t addr, uint32_t val) {
    *(volatile uint32_t*)(uintptr_t)addr = val;
}
KM_ALWAYS_INLINE void kmVolatileStoreU64(int64_t addr, uint64_t val) {
    *(volatile uint64_t*)(uintptr_t)addr = val;
}

/* Bulk memory intrinsics */
KM_ALWAYS_INLINE void kmCopyNonoverlapping(int64_t dst, int64_t src, int64_t bytes) {
    if (bytes > 0) {
        memcpy((void*)(uintptr_t)dst, (const void*)(uintptr_t)src, (size_t)bytes);
    }
}
KM_ALWAYS_INLINE void kmCopy(int64_t dst, int64_t src, int64_t bytes) {
    if (bytes > 0) {
        memmove((void*)(uintptr_t)dst, (const void*)(uintptr_t)src, (size_t)bytes);
    }
}
KM_ALWAYS_INLINE void kmWriteBytes(int64_t dst, uint8_t val, int64_t bytes) {
    if (bytes > 0) {
        memset((void*)(uintptr_t)dst, (int)val, (size_t)bytes);
    }
}
KM_ALWAYS_INLINE int32_t kmCompareBytes(int64_t left, int64_t right, int64_t bytes) {
    if (bytes <= 0) return 0;
    return (int32_t)memcmp((const void*)(uintptr_t)left, (const void*)(uintptr_t)right, (size_t)bytes);
}

/* Aligned system allocator backing GlobalAlloc */
KM_ALWAYS_INLINE int64_t kmAlloc(int64_t size, int64_t align) {
    size_t sz = size <= 0 ? 1 : (size_t)size;
    size_t al = align <= 1 ? 1 : (size_t)align;
#if defined(_WIN32)
    return (int64_t)(uintptr_t)_aligned_malloc(sz, al < sizeof(void*) ? sizeof(void*) : al);
#else
    if (al <= sizeof(void*) * 2) {
        return (int64_t)(uintptr_t)malloc(sz);
    }
    void* out = NULL;
    if (posix_memalign(&out, al, sz) != 0) return 0;
    return (int64_t)(uintptr_t)out;
#endif
}

KM_ALWAYS_INLINE int64_t kmAllocZeroed(int64_t size, int64_t align) {
    size_t sz = size <= 0 ? 1 : (size_t)size;
    size_t al = align <= 1 ? 1 : (size_t)align;
#if defined(_WIN32)
    void* ptr = _aligned_malloc(sz, al < sizeof(void*) ? sizeof(void*) : al);
    if (ptr != NULL) memset(ptr, 0, sz);
    return (int64_t)(uintptr_t)ptr;
#else
    if (al <= sizeof(void*) * 2) {
        return (int64_t)(uintptr_t)calloc(1, sz);
    }
    void* out = NULL;
    if (posix_memalign(&out, al, sz) != 0) return 0;
    memset(out, 0, sz);
    return (int64_t)(uintptr_t)out;
#endif
}

KM_ALWAYS_INLINE int64_t kmRealloc(int64_t ptr, int64_t oldSize, int64_t align, int64_t newSize) {
    if (ptr == 0) return kmAlloc(newSize, align);
    size_t nsz = newSize <= 0 ? 1 : (size_t)newSize;
    size_t al = align <= 1 ? 1 : (size_t)align;
#if defined(_WIN32)
    (void)oldSize;
    return (int64_t)(uintptr_t)_aligned_realloc((void*)(uintptr_t)ptr, nsz, al < sizeof(void*) ? sizeof(void*) : al);
#else
    if (al <= sizeof(void*) * 2) {
        return (int64_t)(uintptr_t)realloc((void*)(uintptr_t)ptr, nsz);
    }
    int64_t fresh = kmAlloc(newSize, align);
    if (fresh != 0) {
        size_t copyLen = (size_t)oldSize < nsz ? (size_t)oldSize : nsz;
        if (copyLen > 0) memcpy((void*)(uintptr_t)fresh, (const void*)(uintptr_t)ptr, copyLen);
        free((void*)(uintptr_t)ptr);
    }
    return fresh;
#endif
}

KM_ALWAYS_INLINE void kmDealloc(int64_t ptr, int64_t size, int64_t align) {
    (void)size;
    (void)align;
    if (ptr == 0) return;
#if defined(_WIN32)
    _aligned_free((void*)(uintptr_t)ptr);
#else
    free((void*)(uintptr_t)ptr);
#endif
}

/* C11 / Clang-GCC __atomic_* operations with explicit Rust ordering constants:
 * 0 = Relaxed, 1 = Release, 2 = Acquire, 3 = AcqRel, 4 = SeqCst
 */
KM_ALWAYS_INLINE void kmAtomicFence(int32_t ord) {
#if defined(__clang__) || defined(__GNUC__)
    switch (ord) {
        case 0: __atomic_thread_fence(__ATOMIC_RELAXED); break;
        case 1: __atomic_thread_fence(__ATOMIC_RELEASE); break;
        case 2: __atomic_thread_fence(__ATOMIC_ACQUIRE); break;
        case 3: __atomic_thread_fence(__ATOMIC_ACQ_REL); break;
        default: __atomic_thread_fence(__ATOMIC_SEQ_CST); break;
    }
#elif !defined(__cplusplus)
    switch (ord) {
        case 0: atomic_thread_fence(memory_order_relaxed); break;
        case 1: atomic_thread_fence(memory_order_release); break;
        case 2: atomic_thread_fence(memory_order_acquire); break;
        case 3: atomic_thread_fence(memory_order_acq_rel); break;
        default: atomic_thread_fence(memory_order_seq_cst); break;
    }
#endif
}

KM_ALWAYS_INLINE void kmAtomicSignalFence(int32_t ord) {
#if defined(__clang__) || defined(__GNUC__)
    switch (ord) {
        case 0: __atomic_signal_fence(__ATOMIC_RELAXED); break;
        case 1: __atomic_signal_fence(__ATOMIC_RELEASE); break;
        case 2: __atomic_signal_fence(__ATOMIC_ACQUIRE); break;
        case 3: __atomic_signal_fence(__ATOMIC_ACQ_REL); break;
        default: __atomic_signal_fence(__ATOMIC_SEQ_CST); break;
    }
#elif !defined(__cplusplus)
    switch (ord) {
        case 0: atomic_signal_fence(memory_order_relaxed); break;
        case 1: atomic_signal_fence(memory_order_release); break;
        case 2: atomic_signal_fence(memory_order_acquire); break;
        case 3: atomic_signal_fence(memory_order_acq_rel); break;
        default: atomic_signal_fence(memory_order_seq_cst); break;
    }
#endif
}

#if defined(__cplusplus) && defined(_MSC_VER) && !defined(__clang__)
#define KM_DEF_ATOMIC_LOAD_STORE(Suffix, CType, SignedCType) \
KM_ALWAYS_INLINE CType kmAtomicLoad##Suffix(int64_t addr, int32_t ord) { \
    CType* p = (CType*)(uintptr_t)addr; \
    std::atomic_ref<CType> ref(*p); \
    switch (ord) { \
        case 0: return ref.load(std::memory_order_relaxed); \
        case 2: return ref.load(std::memory_order_acquire); \
        default: return ref.load(std::memory_order_seq_cst); \
    } \
} \
KM_ALWAYS_INLINE void kmAtomicStore##Suffix(int64_t addr, CType val, int32_t ord) { \
    CType* p = (CType*)(uintptr_t)addr; \
    std::atomic_ref<CType> ref(*p); \
    switch (ord) { \
        case 0: ref.store(val, std::memory_order_relaxed); break; \
        case 1: ref.store(val, std::memory_order_release); break; \
        default: ref.store(val, std::memory_order_seq_cst); break; \
    } \
} \
KM_ALWAYS_INLINE CType kmAtomicXchg##Suffix(int64_t addr, CType val, int32_t ord) { \
    CType* p = (CType*)(uintptr_t)addr; \
    std::atomic_ref<CType> ref(*p); \
    switch (ord) { \
        case 0: return ref.exchange(val, std::memory_order_relaxed); \
        case 1: return ref.exchange(val, std::memory_order_release); \
        case 2: return ref.exchange(val, std::memory_order_acquire); \
        case 3: return ref.exchange(val, std::memory_order_acq_rel); \
        default: return ref.exchange(val, std::memory_order_seq_cst); \
    } \
} \
KM_ALWAYS_INLINE CType kmAtomicCxchg##Suffix(int64_t addr, CType expected, CType desired, int32_t succ, int32_t fail, uint8_t* okOut) { \
    CType* p = (CType*)(uintptr_t)addr; \
    std::atomic_ref<CType> ref(*p); \
    std::memory_order sm = succ == 0 ? std::memory_order_relaxed : (succ == 1 ? std::memory_order_release : (succ == 2 ? std::memory_order_acquire : (succ == 3 ? std::memory_order_acq_rel : std::memory_order_seq_cst))); \
    std::memory_order fm = fail == 0 ? std::memory_order_relaxed : (fail == 2 ? std::memory_order_acquire : std::memory_order_seq_cst); \
    uint8_t ok = ref.compare_exchange_strong(expected, desired, sm, fm) ? 1 : 0; \
    if (okOut != NULL) *okOut = ok; \
    return expected; \
} \
KM_ALWAYS_INLINE CType kmAtomicRmw##Suffix(int64_t addr, CType val, int32_t op, int32_t ord) { \
    CType* p = (CType*)(uintptr_t)addr; \
    std::atomic_ref<CType> ref(*p); \
    std::memory_order model = ord == 0 ? std::memory_order_relaxed : (ord == 1 ? std::memory_order_release : (ord == 2 ? std::memory_order_acquire : (ord == 3 ? std::memory_order_acq_rel : std::memory_order_seq_cst))); \
    switch (op) { \
        case 1: return ref.fetch_add(val, model); \
        case 2: return ref.fetch_sub(val, model); \
        case 3: return ref.fetch_and(val, model); \
        case 5: return ref.fetch_or(val, model); \
        case 6: return ref.fetch_xor(val, model); \
        default: { \
            CType cur = ref.load(std::memory_order_relaxed); \
            for (;;) { \
                CType next = cur; \
                if (op == 4) next = (CType)(~(cur & val)); \
                else if (op == 7) next = ((SignedCType)cur > (SignedCType)val) ? cur : val; \
                else if (op == 8) next = ((SignedCType)cur < (SignedCType)val) ? cur : val; \
                else if (op == 9) next = (cur > val) ? cur : val; \
                else next = (cur < val) ? cur : val; \
                if (ref.compare_exchange_weak(cur, next, model, std::memory_order_relaxed)) return cur; \
            } \
        } \
    } \
}
#else
#define KM_DEF_ATOMIC_LOAD_STORE(Suffix, CType, SignedCType) \
KM_ALWAYS_INLINE CType kmAtomicLoad##Suffix(int64_t addr, int32_t ord) { \
    const CType* p = (const CType*)(uintptr_t)addr; \
    switch (ord) { \
        case 0: return __atomic_load_n(p, __ATOMIC_RELAXED); \
        case 2: return __atomic_load_n(p, __ATOMIC_ACQUIRE); \
        default: return __atomic_load_n(p, __ATOMIC_SEQ_CST); \
    } \
} \
KM_ALWAYS_INLINE void kmAtomicStore##Suffix(int64_t addr, CType val, int32_t ord) { \
    CType* p = (CType*)(uintptr_t)addr; \
    switch (ord) { \
        case 0: __atomic_store_n(p, val, __ATOMIC_RELAXED); break; \
        case 1: __atomic_store_n(p, val, __ATOMIC_RELEASE); break; \
        default: __atomic_store_n(p, val, __ATOMIC_SEQ_CST); break; \
    } \
} \
KM_ALWAYS_INLINE CType kmAtomicXchg##Suffix(int64_t addr, CType val, int32_t ord) { \
    CType* p = (CType*)(uintptr_t)addr; \
    switch (ord) { \
        case 0: return __atomic_exchange_n(p, val, __ATOMIC_RELAXED); \
        case 1: return __atomic_exchange_n(p, val, __ATOMIC_RELEASE); \
        case 2: return __atomic_exchange_n(p, val, __ATOMIC_ACQUIRE); \
        case 3: return __atomic_exchange_n(p, val, __ATOMIC_ACQ_REL); \
        default: return __atomic_exchange_n(p, val, __ATOMIC_SEQ_CST); \
    } \
} \
KM_ALWAYS_INLINE CType kmAtomicCxchg##Suffix(int64_t addr, CType expected, CType desired, int32_t succ, int32_t fail, uint8_t* okOut) { \
    CType* p = (CType*)(uintptr_t)addr; \
    int succModel = succ == 0 ? __ATOMIC_RELAXED : (succ == 1 ? __ATOMIC_RELEASE : (succ == 2 ? __ATOMIC_ACQUIRE : (succ == 3 ? __ATOMIC_ACQ_REL : __ATOMIC_SEQ_CST))); \
    int failModel = fail == 0 ? __ATOMIC_RELAXED : (fail == 2 ? __ATOMIC_ACQUIRE : __ATOMIC_SEQ_CST); \
    if (failModel > succModel) failModel = __ATOMIC_RELAXED; \
    uint8_t ok = __atomic_compare_exchange_n(p, &expected, desired, 0, succModel, failModel) ? 1 : 0; \
    if (okOut != NULL) *okOut = ok; \
    return expected; \
} \
KM_ALWAYS_INLINE CType kmAtomicRmw##Suffix(int64_t addr, CType val, int32_t op, int32_t ord) { \
    CType* p = (CType*)(uintptr_t)addr; \
    int model = ord == 0 ? __ATOMIC_RELAXED : (ord == 1 ? __ATOMIC_RELEASE : (ord == 2 ? __ATOMIC_ACQUIRE : (ord == 3 ? __ATOMIC_ACQ_REL : __ATOMIC_SEQ_CST))); \
    switch (op) { \
        case 1: return __atomic_fetch_add(p, val, model); \
        case 2: return __atomic_fetch_sub(p, val, model); \
        case 3: return __atomic_fetch_and(p, val, model); \
        case 4: return __atomic_fetch_nand(p, val, model); \
        case 5: return __atomic_fetch_or(p, val, model); \
        case 6: return __atomic_fetch_xor(p, val, model); \
        default: { \
            CType cur = __atomic_load_n(p, __ATOMIC_RELAXED); \
            for (;;) { \
                CType next = cur; \
                if (op == 7) next = ((SignedCType)cur > (SignedCType)val) ? cur : val; \
                else if (op == 8) next = ((SignedCType)cur < (SignedCType)val) ? cur : val; \
                else if (op == 9) next = (cur > val) ? cur : val; \
                else next = (cur < val) ? cur : val; \
                if (__atomic_compare_exchange_n(p, &cur, next, 1, model, __ATOMIC_RELAXED)) return cur; \
            } \
        } \
    } \
}
#endif

KM_DEF_ATOMIC_LOAD_STORE(U8, uint8_t, int8_t)
KM_DEF_ATOMIC_LOAD_STORE(U16, uint16_t, int16_t)
KM_DEF_ATOMIC_LOAD_STORE(U32, uint32_t, int32_t)
KM_DEF_ATOMIC_LOAD_STORE(U64, uint64_t, int64_t)

/* Bit, math, and trap compiler builtins */
KM_ALWAYS_INLINE uint32_t kmCtpopU64(uint64_t v) {
#if defined(__clang__) || defined(__GNUC__)
    return (uint32_t)__builtin_popcountll(v);
#else
    uint32_t c = 0;
    while (v) { v &= (v - 1); c++; }
    return c;
#endif
}

KM_ALWAYS_INLINE uint32_t kmCtlzU64(uint64_t v) {
    if (v == 0) return 64;
#if defined(__clang__) || defined(__GNUC__)
    return (uint32_t)__builtin_clzll(v);
#else
    uint32_t n = 0;
    for (int i = 63; i >= 0 && ((v >> i) & 1ULL) == 0; i--) n++;
    return n;
#endif
}

KM_ALWAYS_INLINE uint32_t kmCttzU64(uint64_t v) {
    if (v == 0) return 64;
#if defined(__clang__) || defined(__GNUC__)
    return (uint32_t)__builtin_ctzll(v);
#else
    uint32_t n = 0;
    while ((v & 1ULL) == 0) { v >>= 1; n++; }
    return n;
#endif
}

KM_ALWAYS_INLINE uint16_t kmBswapU16(uint16_t v) {
#if defined(__clang__) || defined(__GNUC__)
    return __builtin_bswap16(v);
#else
    return (uint16_t)((v << 8) | (v >> 8));
#endif
}

KM_ALWAYS_INLINE uint32_t kmBswapU32(uint32_t v) {
#if defined(__clang__) || defined(__GNUC__)
    return __builtin_bswap32(v);
#else
    return ((v & 0x000000FFu) << 24) | ((v & 0x0000FF00u) << 8) | ((v & 0x00FF0000u) >> 8) | ((v & 0xFF000000u) >> 24);
#endif
}

KM_ALWAYS_INLINE uint64_t kmBswapU64(uint64_t v) {
#if defined(__clang__) || defined(__GNUC__)
    return __builtin_bswap64(v);
#else
    return ((uint64_t)kmBswapU32((uint32_t)v) << 32) | (uint64_t)kmBswapU32((uint32_t)(v >> 32));
#endif
}

KM_ALWAYS_INLINE uint64_t kmBitreverseU64(uint64_t v) {
#if defined(__clang__)
    return __builtin_bitreverse64(v);
#else
    v = ((v >> 1) & 0x5555555555555555ULL) | ((v & 0x5555555555555555ULL) << 1);
    v = ((v >> 2) & 0x3333333333333333ULL) | ((v & 0x3333333333333333ULL) << 2);
    v = ((v >> 4) & 0x0F0F0F0F0F0F0F0FULL) | ((v & 0x0F0F0F0F0F0F0F0FULL) << 4);
    return kmBswapU64(v);
#endif
}

KM_ALWAYS_INLINE float kmFmaf32(float a, float b, float c) { return fmaf(a, b, c); }
KM_ALWAYS_INLINE double kmFmaf64(double a, double b, double c) { return fma(a, b, c); }
KM_ALWAYS_INLINE float kmSqrtf32(float x) { return sqrtf(x); }
KM_ALWAYS_INLINE double kmSqrtf64(double x) { return sqrt(x); }

KM_ALWAYS_INLINE void kmAbort(void) {
#if defined(__clang__) || defined(__GNUC__)
    __builtin_trap();
#else
    abort();
#endif
}

/* Architecture-guarded SIMD f32x4 / f64x2 fused kernels for core::arch */
#if defined(__aarch64__)
#include <arm_neon.h>
KM_ALWAYS_INLINE void kmSimdFmaF32x4(int64_t dstAddr, int64_t aAddr, int64_t bAddr, int64_t cAddr) {
    float32x4_t a = vld1q_f32((const float*)(uintptr_t)aAddr);
    float32x4_t b = vld1q_f32((const float*)(uintptr_t)bAddr);
    float32x4_t c = vld1q_f32((const float*)(uintptr_t)cAddr);
    vst1q_f32((float*)(uintptr_t)dstAddr, vfmaq_f32(c, a, b));
}
KM_ALWAYS_INLINE float kmSimdDotF32(int64_t aAddr, int64_t bAddr, int64_t len) {
    const float* a = (const float*)(uintptr_t)aAddr;
    const float* b = (const float*)(uintptr_t)bAddr;
    float32x4_t acc = vdupq_n_f32(0.0f);
    int64_t i = 0;
    for (; i + 4 <= len; i += 4) {
        acc = vfmaq_f32(acc, vld1q_f32(a + i), vld1q_f32(b + i));
    }
    float sum = vaddvq_f32(acc);
    for (; i < len; i++) sum = fmaf(a[i], b[i], sum);
    return sum;
}
#elif defined(__x86_64__) || defined(_M_X64)
#include <immintrin.h>
KM_ALWAYS_INLINE void kmSimdFmaF32x4(int64_t dstAddr, int64_t aAddr, int64_t bAddr, int64_t cAddr) {
    __m128 a = _mm_loadu_ps((const float*)(uintptr_t)aAddr);
    __m128 b = _mm_loadu_ps((const float*)(uintptr_t)bAddr);
    __m128 c = _mm_loadu_ps((const float*)(uintptr_t)cAddr);
    _mm_storeu_ps((float*)(uintptr_t)dstAddr, _mm_add_ps(_mm_mul_ps(a, b), c));
}
KM_ALWAYS_INLINE float kmSimdDotF32(int64_t aAddr, int64_t bAddr, int64_t len) {
    const float* a = (const float*)(uintptr_t)aAddr;
    const float* b = (const float*)(uintptr_t)bAddr;
    __m128 acc = _mm_setzero_ps();
    int64_t i = 0;
    for (; i + 4 <= len; i += 4) {
        acc = _mm_add_ps(acc, _mm_mul_ps(_mm_loadu_ps(a + i), _mm_loadu_ps(b + i)));
    }
    float lanes[4];
    _mm_storeu_ps(lanes, acc);
    float sum = lanes[0] + lanes[1] + lanes[2] + lanes[3];
    for (; i < len; i++) sum = fmaf(a[i], b[i], sum);
    return sum;
}
#else
KM_ALWAYS_INLINE void kmSimdFmaF32x4(int64_t dstAddr, int64_t aAddr, int64_t bAddr, int64_t cAddr) {
    const float* a = (const float*)(uintptr_t)aAddr;
    const float* b = (const float*)(uintptr_t)bAddr;
    const float* c = (const float*)(uintptr_t)cAddr;
    float* dst = (float*)(uintptr_t)dstAddr;
    for (int i = 0; i < 4; i++) dst[i] = fmaf(a[i], b[i], c[i]);
}
KM_ALWAYS_INLINE float kmSimdDotF32(int64_t aAddr, int64_t bAddr, int64_t len) {
    const float* a = (const float*)(uintptr_t)aAddr;
    const float* b = (const float*)(uintptr_t)bAddr;
    float sum = 0.0f;
    for (int64_t i = 0; i < len; i++) sum = fmaf(a[i], b[i], sum);
    return sum;
}
#endif

#endif /* KM_CORE_INTRINSICS_INLINE_H */
""".trimStart()

    public fun renderKmCoreAsmHeader(): String =
        """
#ifndef KM_CORE_LLVM_ASM_H
#define KM_CORE_LLVM_ASM_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

void kmSpinLoopHint(void);
uint64_t kmBlackBoxU64(uint64_t val);
uint64_t kmUmulhU64(uint64_t a, uint64_t b);
int64_t kmSmulhI64(int64_t a, int64_t b);
uint8_t kmAtomicCxchgU128(
    int64_t addr,
    uint64_t expLo,
    uint64_t expHi,
    uint64_t desLo,
    uint64_t desHi,
    uint64_t* outLo,
    uint64_t* outHi
);
void kmCpuidCount(
    uint32_t leaf,
    uint32_t subleaf,
    uint32_t* eaxOut,
    uint32_t* ebxOut,
    uint32_t* ecxOut,
    uint32_t* edxOut
);
uint64_t kmXgetbv(uint32_t xcr);

#ifdef __cplusplus
}
#endif

#endif /* KM_CORE_LLVM_ASM_H */
""".trimStart()

    public fun renderKmCoreAarch64Asm(): String =
        """
#if defined(__aarch64__) || defined(_M_ARM64)

#if defined(__APPLE__)
#define KM_SYM(name) _##name
#else
#define KM_SYM(name) name
#endif

.text
.p2align 2

.globl KM_SYM(kmSpinLoopHint)
KM_SYM(kmSpinLoopHint):
    yield
    ret

.globl KM_SYM(kmBlackBoxU64)
KM_SYM(kmBlackBoxU64):
    ret

.globl KM_SYM(kmUmulhU64)
KM_SYM(kmUmulhU64):
    umulh x0, x0, x1
    ret

.globl KM_SYM(kmSmulhI64)
KM_SYM(kmSmulhI64):
    smulh x0, x0, x1
    ret

/* uint8_t kmAtomicCxchgU128(addr=x0, expLo=x1, expHi=x2, desLo=x3, desHi=x4, outLo=x5, outHi=x6) */
.globl KM_SYM(kmAtomicCxchgU128)
KM_SYM(kmAtomicCxchgU128):
1:
    ldaxp x8, x9, [x0]
    cmp x8, x1
    ccmp x9, x2, #0, eq
    b.ne 2f
    stlxp w10, x3, x4, [x0]
    cbnz w10, 1b
    str x8, [x5]
    str x9, [x6]
    mov w0, #1
    ret
2:
    clrex
    str x8, [x5]
    str x9, [x6]
    mov w0, #0
    ret

.globl KM_SYM(kmCpuidCount)
KM_SYM(kmCpuidCount):
    str wzr, [x2]
    str wzr, [x3]
    str wzr, [x4]
    str wzr, [x5]
    ret

.globl KM_SYM(kmXgetbv)
KM_SYM(kmXgetbv):
    mov x0, #0
    ret

#if defined(__ELF__)
.section .note.GNU-stack,"",%progbits
#endif

#endif
""".trimStart()

    public fun renderKmCoreX8664Asm(): String =
        """
#if defined(__x86_64__) || defined(_M_X64)

#if defined(__APPLE__)
#define KM_SYM(name) _##name
#else
#define KM_SYM(name) name
#endif

.text
.p2align 4

.globl KM_SYM(kmSpinLoopHint)
KM_SYM(kmSpinLoopHint):
    pause
    retq

.globl KM_SYM(kmBlackBoxU64)
KM_SYM(kmBlackBoxU64):
#if defined(_WIN64)
    movq %rcx, %rax
#else
    movq %rdi, %rax
#endif
    retq

.globl KM_SYM(kmUmulhU64)
KM_SYM(kmUmulhU64):
#if defined(_WIN64)
    movq %rcx, %rax
    mulq %rdx
    movq %rdx, %rax
#else
    movq %rdi, %rax
    mulq %rsi
    movq %rdx, %rax
#endif
    retq

.globl KM_SYM(kmSmulhI64)
KM_SYM(kmSmulhI64):
#if defined(_WIN64)
    movq %rcx, %rax
    imulq %rdx
    movq %rdx, %rax
#else
    movq %rdi, %rax
    imulq %rsi
    movq %rdx, %rax
#endif
    retq

.globl KM_SYM(kmAtomicCxchgU128)
KM_SYM(kmAtomicCxchgU128):
    pushq %rbx
#if defined(_WIN64)
    movq %rcx, %r10
    movq %rdx, %rax
    movq %r8, %rdx
    movq %r9, %rbx
    movq 48(%rsp), %rcx
    lock cmpxchg16b (%r10)
    sete %r11b
    movq 56(%rsp), %r10
    movq %rax, (%r10)
    movq 64(%rsp), %r10
    movq %rdx, (%r10)
    movzbl %r11b, %eax
#else
    movq %rdi, %r10
    movq %rsi, %rax
    movq %rcx, %rbx
    movq %r8, %rcx
    lock cmpxchg16b (%r10)
    sete %r11b
    movq %rax, (%r9)
    movq 16(%rsp), %r10
    movq %rdx, (%r10)
    movzbl %r11b, %eax
#endif
    popq %rbx
    retq

.globl KM_SYM(kmCpuidCount)
KM_SYM(kmCpuidCount):
    pushq %rbx
#if defined(_WIN64)
    movl %ecx, %eax
    movl %edx, %ecx
    movq %r8, %r10
    movq %r9, %r11
    cpuid
    movl %eax, (%r10)
    movl %ebx, (%r11)
    movq 48(%rsp), %r10
    movl %ecx, (%r10)
    movq 56(%rsp), %r10
    movl %edx, (%r10)
#else
    movl %edi, %eax
    movq %rdx, %r10
    movq %rcx, %r11
    movl %esi, %ecx
    cpuid
    movl %eax, (%r10)
    movl %ebx, (%r11)
    movl %ecx, (%r8)
    movl %edx, (%r9)
#endif
    popq %rbx
    retq

.globl KM_SYM(kmXgetbv)
KM_SYM(kmXgetbv):
#if defined(_WIN64)
    movl %ecx, %ecx
#else
    movl %edi, %ecx
#endif
    xgetbv
    shlq $32, %rdx
    orq %rdx, %rax
    retq

#if defined(__ELF__)
.section .note.GNU-stack,"",@progbits
#endif

#endif
""".trimStart()

    public fun renderKmCoreWrapperC(): String =
        """
#include "km_core_intrinsics_inline.h"
#include "km_core_llvm_asm.h"

int32_t kmCoreWrapperAnchor(void) {
    return 1;
}
""".trimStart()

    public fun renderKmCoreIntrinsicsExpectKotlin(coreNamespace: String): String {
        val ptrPkg = "$coreNamespace.ptr"
        return """
package $coreNamespace.intrinsics

import $ptrPkg.ConstPtr

public data class AtomicCxchgU8Outcome(
    public val previous: UByte,
    public val succeeded: Boolean,
)

public data class AtomicCxchgU16Outcome(
    public val previous: UShort,
    public val succeeded: Boolean,
)

public data class AtomicCxchgU32Outcome(
    public val previous: UInt,
    public val succeeded: Boolean,
)

public data class AtomicCxchgU64Outcome(
    public val previous: ULong,
    public val succeeded: Boolean,
)

public data class AtomicCxchgU128Outcome(
    public val prevLo: ULong,
    public val prevHi: ULong,
    public val succeeded: Boolean,
)

public expect fun kmLoadU8(addr: Long): UByte
public expect fun kmLoadU16(addr: Long): UShort
public expect fun kmLoadU32(addr: Long): UInt
public expect fun kmLoadU64(addr: Long): ULong
public expect fun kmLoadF32(addr: Long): Float
public expect fun kmLoadF64(addr: Long): Double

public expect fun kmStoreU8(addr: Long, value: UByte)
public expect fun kmStoreU16(addr: Long, value: UShort)
public expect fun kmStoreU32(addr: Long, value: UInt)
public expect fun kmStoreU64(addr: Long, value: ULong)
public expect fun kmStoreF32(addr: Long, value: Float)
public expect fun kmStoreF64(addr: Long, value: Double)

public expect fun kmVolatileLoadU8(addr: Long): UByte
public expect fun kmVolatileLoadU16(addr: Long): UShort
public expect fun kmVolatileLoadU32(addr: Long): UInt
public expect fun kmVolatileLoadU64(addr: Long): ULong

public expect fun kmVolatileStoreU8(addr: Long, value: UByte)
public expect fun kmVolatileStoreU16(addr: Long, value: UShort)
public expect fun kmVolatileStoreU32(addr: Long, value: UInt)
public expect fun kmVolatileStoreU64(addr: Long, value: ULong)

public expect fun kmCopyNonoverlapping(dst: Long, src: Long, bytes: Long)
public expect fun kmCopy(dst: Long, src: Long, bytes: Long)
public expect fun kmWriteBytes(dst: Long, value: UByte, bytes: Long)
public expect fun kmCompareBytes(left: Long, right: Long, bytes: Long): Int

public expect fun kmAlloc(size: Long, align: Long): Long
public expect fun kmAllocZeroed(size: Long, align: Long): Long
public expect fun kmRealloc(ptr: Long, oldSize: Long, align: Long, newSize: Long): Long
public expect fun kmDealloc(ptr: Long, size: Long, align: Long)

public expect fun kmAtomicFence(ord: Int)
public expect fun kmAtomicSignalFence(ord: Int)
public expect fun kmAtomicLoadU8(addr: Long, ord: Int): UByte
public expect fun kmAtomicLoadU16(addr: Long, ord: Int): UShort
public expect fun kmAtomicLoadU32(addr: Long, ord: Int): UInt
public expect fun kmAtomicLoadU64(addr: Long, ord: Int): ULong
public expect fun kmAtomicStoreU8(addr: Long, value: UByte, ord: Int)
public expect fun kmAtomicStoreU16(addr: Long, value: UShort, ord: Int)
public expect fun kmAtomicStoreU32(addr: Long, value: UInt, ord: Int)
public expect fun kmAtomicStoreU64(addr: Long, value: ULong, ord: Int)
public expect fun kmAtomicXchgU8(addr: Long, value: UByte, ord: Int): UByte
public expect fun kmAtomicXchgU16(addr: Long, value: UShort, ord: Int): UShort
public expect fun kmAtomicXchgU32(addr: Long, value: UInt, ord: Int): UInt
public expect fun kmAtomicXchgU64(addr: Long, value: ULong, ord: Int): ULong
public expect fun kmAtomicCxchgU8(addr: Long, expected: UByte, desired: UByte, succ: Int, fail: Int): AtomicCxchgU8Outcome
public expect fun kmAtomicCxchgU16(addr: Long, expected: UShort, desired: UShort, succ: Int, fail: Int): AtomicCxchgU16Outcome
public expect fun kmAtomicCxchgU32(addr: Long, expected: UInt, desired: UInt, succ: Int, fail: Int): AtomicCxchgU32Outcome
public expect fun kmAtomicCxchgU64(addr: Long, expected: ULong, desired: ULong, succ: Int, fail: Int): AtomicCxchgU64Outcome
public expect fun kmAtomicRmwU8(addr: Long, value: UByte, op: Int, ord: Int): UByte
public expect fun kmAtomicRmwU16(addr: Long, value: UShort, op: Int, ord: Int): UShort
public expect fun kmAtomicRmwU32(addr: Long, value: UInt, op: Int, ord: Int): UInt
public expect fun kmAtomicRmwU64(addr: Long, value: ULong, op: Int, ord: Int): ULong

public expect fun kmCtpopU64(value: ULong): UInt
public expect fun kmCtlzU64(value: ULong): UInt
public expect fun kmCttzU64(value: ULong): UInt
public expect fun kmBswapU16(value: UShort): UShort
public expect fun kmBswapU32(value: UInt): UInt
public expect fun kmBswapU64(value: ULong): ULong
public expect fun kmBitreverseU64(value: ULong): ULong
public expect fun kmFmaf32(a: Float, b: Float, c: Float): Float
public expect fun kmFmaf64(a: Double, b: Double, c: Double): Double
public expect fun kmSqrtf32(x: Float): Float
public expect fun kmSqrtf64(x: Double): Double
public expect fun kmAbort(): Nothing

public expect fun kmSpinLoopHint()
public expect fun kmBlackBoxU64(value: ULong): ULong
public expect fun kmUmulhU64(a: ULong, b: ULong): ULong
public expect fun kmSmulhI64(a: Long, b: Long): Long
public expect fun kmAtomicCxchgU128Pair(
    addr: Long,
    expLo: ULong,
    expHi: ULong,
    desLo: ULong,
    desHi: ULong,
): AtomicCxchgU128Outcome
public expect fun kmSimdFmaF32x4(dstAddr: Long, aAddr: Long, bAddr: Long, cAddr: Long)
public expect fun kmSimdDotF32(aAddr: Long, bAddr: Long, len: Long): Float

private val internedCStrPool: MutableMap<String, Long> = linkedMapOf()
private val internedWideStrPool: MutableMap<String, Long> = linkedMapOf()

public fun kmInternCStr(literal: String): ConstPtr {
    val existing = internedCStrPool[literal]
    if (existing != null) return ConstPtr(existing)
    val bytes = literal.encodeToByteArray()
    val addr = kmAlloc((bytes.size + 1).toLong(), 1L)
    for (index in bytes.indices) {
        kmStoreU8(addr + index.toLong(), bytes[index].toUByte())
    }
    kmStoreU8(addr + bytes.size.toLong(), 0u)
    internedCStrPool[literal] = addr
    return ConstPtr(addr)
}

public fun kmInternWideStr(literal: String): ConstPtr {
    val existing = internedWideStrPool[literal]
    if (existing != null) return ConstPtr(existing)
    val addr = kmAlloc(((literal.length + 1) * 2).toLong(), 2L)
    for (index in literal.indices) {
        kmStoreU16(addr + (index * 2).toLong(), literal[index].code.toUShort())
    }
    kmStoreU16(addr + (literal.length * 2).toLong(), 0u)
    internedWideStrPool[literal] = addr
    return ConstPtr(addr)
}
""".trimStart()
    }

    public fun renderKmCoreNativeActualsKotlin(coreNamespace: String, cinteropPackage: String): String =
        """
@file:OptIn(ExperimentalForeignApi::class)

package $coreNamespace.intrinsics

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value

public actual inline fun kmLoadU8(addr: Long): UByte = $cinteropPackage.kmLoadU8(addr)
public actual inline fun kmLoadU16(addr: Long): UShort = $cinteropPackage.kmLoadU16(addr)
public actual inline fun kmLoadU32(addr: Long): UInt = $cinteropPackage.kmLoadU32(addr)
public actual inline fun kmLoadU64(addr: Long): ULong = $cinteropPackage.kmLoadU64(addr)
public actual inline fun kmLoadF32(addr: Long): Float = $cinteropPackage.kmLoadF32(addr)
public actual inline fun kmLoadF64(addr: Long): Double = $cinteropPackage.kmLoadF64(addr)

public actual inline fun kmStoreU8(addr: Long, value: UByte): Unit = $cinteropPackage.kmStoreU8(addr, value)
public actual inline fun kmStoreU16(addr: Long, value: UShort): Unit = $cinteropPackage.kmStoreU16(addr, value)
public actual inline fun kmStoreU32(addr: Long, value: UInt): Unit = $cinteropPackage.kmStoreU32(addr, value)
public actual inline fun kmStoreU64(addr: Long, value: ULong): Unit = $cinteropPackage.kmStoreU64(addr, value)
public actual inline fun kmStoreF32(addr: Long, value: Float): Unit = $cinteropPackage.kmStoreF32(addr, value)
public actual inline fun kmStoreF64(addr: Long, value: Double): Unit = $cinteropPackage.kmStoreF64(addr, value)

public actual inline fun kmVolatileLoadU8(addr: Long): UByte = $cinteropPackage.kmVolatileLoadU8(addr)
public actual inline fun kmVolatileLoadU16(addr: Long): UShort = $cinteropPackage.kmVolatileLoadU16(addr)
public actual inline fun kmVolatileLoadU32(addr: Long): UInt = $cinteropPackage.kmVolatileLoadU32(addr)
public actual inline fun kmVolatileLoadU64(addr: Long): ULong = $cinteropPackage.kmVolatileLoadU64(addr)

public actual inline fun kmVolatileStoreU8(addr: Long, value: UByte): Unit = $cinteropPackage.kmVolatileStoreU8(addr, value)
public actual inline fun kmVolatileStoreU16(addr: Long, value: UShort): Unit = $cinteropPackage.kmVolatileStoreU16(addr, value)
public actual inline fun kmVolatileStoreU32(addr: Long, value: UInt): Unit = $cinteropPackage.kmVolatileStoreU32(addr, value)
public actual inline fun kmVolatileStoreU64(addr: Long, value: ULong): Unit = $cinteropPackage.kmVolatileStoreU64(addr, value)

public actual inline fun kmCopyNonoverlapping(dst: Long, src: Long, bytes: Long): Unit =
    $cinteropPackage.kmCopyNonoverlapping(dst, src, bytes)
public actual inline fun kmCopy(dst: Long, src: Long, bytes: Long): Unit =
    $cinteropPackage.kmCopy(dst, src, bytes)
public actual inline fun kmWriteBytes(dst: Long, value: UByte, bytes: Long): Unit =
    $cinteropPackage.kmWriteBytes(dst, value, bytes)
public actual inline fun kmCompareBytes(left: Long, right: Long, bytes: Long): Int =
    $cinteropPackage.kmCompareBytes(left, right, bytes)

public actual inline fun kmAlloc(size: Long, align: Long): Long = $cinteropPackage.kmAlloc(size, align)
public actual inline fun kmAllocZeroed(size: Long, align: Long): Long = $cinteropPackage.kmAllocZeroed(size, align)
public actual inline fun kmRealloc(ptr: Long, oldSize: Long, align: Long, newSize: Long): Long =
    $cinteropPackage.kmRealloc(ptr, oldSize, align, newSize)
public actual inline fun kmDealloc(ptr: Long, size: Long, align: Long): Unit =
    $cinteropPackage.kmDealloc(ptr, size, align)

public actual inline fun kmAtomicFence(ord: Int): Unit = $cinteropPackage.kmAtomicFence(ord)
public actual inline fun kmAtomicSignalFence(ord: Int): Unit = $cinteropPackage.kmAtomicSignalFence(ord)
public actual inline fun kmAtomicLoadU8(addr: Long, ord: Int): UByte = $cinteropPackage.kmAtomicLoadU8(addr, ord)
public actual inline fun kmAtomicLoadU16(addr: Long, ord: Int): UShort = $cinteropPackage.kmAtomicLoadU16(addr, ord)
public actual inline fun kmAtomicLoadU32(addr: Long, ord: Int): UInt = $cinteropPackage.kmAtomicLoadU32(addr, ord)
public actual inline fun kmAtomicLoadU64(addr: Long, ord: Int): ULong = $cinteropPackage.kmAtomicLoadU64(addr, ord)
public actual inline fun kmAtomicStoreU8(addr: Long, value: UByte, ord: Int): Unit = $cinteropPackage.kmAtomicStoreU8(addr, value, ord)
public actual inline fun kmAtomicStoreU16(addr: Long, value: UShort, ord: Int): Unit = $cinteropPackage.kmAtomicStoreU16(addr, value, ord)
public actual inline fun kmAtomicStoreU32(addr: Long, value: UInt, ord: Int): Unit = $cinteropPackage.kmAtomicStoreU32(addr, value, ord)
public actual inline fun kmAtomicStoreU64(addr: Long, value: ULong, ord: Int): Unit = $cinteropPackage.kmAtomicStoreU64(addr, value, ord)
public actual inline fun kmAtomicXchgU8(addr: Long, value: UByte, ord: Int): UByte = $cinteropPackage.kmAtomicXchgU8(addr, value, ord)
public actual inline fun kmAtomicXchgU16(addr: Long, value: UShort, ord: Int): UShort = $cinteropPackage.kmAtomicXchgU16(addr, value, ord)
public actual inline fun kmAtomicXchgU32(addr: Long, value: UInt, ord: Int): UInt = $cinteropPackage.kmAtomicXchgU32(addr, value, ord)
public actual inline fun kmAtomicXchgU64(addr: Long, value: ULong, ord: Int): ULong = $cinteropPackage.kmAtomicXchgU64(addr, value, ord)

public actual fun kmAtomicCxchgU8(addr: Long, expected: UByte, desired: UByte, succ: Int, fail: Int): AtomicCxchgU8Outcome =
    memScoped {
        val ok = alloc<UByteVar>()
        val prev = $cinteropPackage.kmAtomicCxchgU8(addr, expected, desired, succ, fail, ok.ptr)
        AtomicCxchgU8Outcome(prev, ok.value != 0u.toUByte())
    }

public actual fun kmAtomicCxchgU16(addr: Long, expected: UShort, desired: UShort, succ: Int, fail: Int): AtomicCxchgU16Outcome =
    memScoped {
        val ok = alloc<UByteVar>()
        val prev = $cinteropPackage.kmAtomicCxchgU16(addr, expected, desired, succ, fail, ok.ptr)
        AtomicCxchgU16Outcome(prev, ok.value != 0u.toUByte())
    }

public actual fun kmAtomicCxchgU32(addr: Long, expected: UInt, desired: UInt, succ: Int, fail: Int): AtomicCxchgU32Outcome =
    memScoped {
        val ok = alloc<UByteVar>()
        val prev = $cinteropPackage.kmAtomicCxchgU32(addr, expected, desired, succ, fail, ok.ptr)
        AtomicCxchgU32Outcome(prev, ok.value != 0u.toUByte())
    }

public actual fun kmAtomicCxchgU64(addr: Long, expected: ULong, desired: ULong, succ: Int, fail: Int): AtomicCxchgU64Outcome =
    memScoped {
        val ok = alloc<UByteVar>()
        val prev = $cinteropPackage.kmAtomicCxchgU64(addr, expected, desired, succ, fail, ok.ptr)
        AtomicCxchgU64Outcome(prev, ok.value != 0u.toUByte())
    }

public actual inline fun kmAtomicRmwU8(addr: Long, value: UByte, op: Int, ord: Int): UByte = $cinteropPackage.kmAtomicRmwU8(addr, value, op, ord)
public actual inline fun kmAtomicRmwU16(addr: Long, value: UShort, op: Int, ord: Int): UShort = $cinteropPackage.kmAtomicRmwU16(addr, value, op, ord)
public actual inline fun kmAtomicRmwU32(addr: Long, value: UInt, op: Int, ord: Int): UInt = $cinteropPackage.kmAtomicRmwU32(addr, value, op, ord)
public actual inline fun kmAtomicRmwU64(addr: Long, value: ULong, op: Int, ord: Int): ULong = $cinteropPackage.kmAtomicRmwU64(addr, value, op, ord)

public actual inline fun kmCtpopU64(value: ULong): UInt = $cinteropPackage.kmCtpopU64(value)
public actual inline fun kmCtlzU64(value: ULong): UInt = $cinteropPackage.kmCtlzU64(value)
public actual inline fun kmCttzU64(value: ULong): UInt = $cinteropPackage.kmCttzU64(value)
public actual inline fun kmBswapU16(value: UShort): UShort = $cinteropPackage.kmBswapU16(value)
public actual inline fun kmBswapU32(value: UInt): UInt = $cinteropPackage.kmBswapU32(value)
public actual inline fun kmBswapU64(value: ULong): ULong = $cinteropPackage.kmBswapU64(value)
public actual inline fun kmBitreverseU64(value: ULong): ULong = $cinteropPackage.kmBitreverseU64(value)
public actual inline fun kmFmaf32(a: Float, b: Float, c: Float): Float = $cinteropPackage.kmFmaf32(a, b, c)
public actual inline fun kmFmaf64(a: Double, b: Double, c: Double): Double = $cinteropPackage.kmFmaf64(a, b, c)
public actual inline fun kmSqrtf32(x: Float): Float = $cinteropPackage.kmSqrtf32(x)
public actual inline fun kmSqrtf64(x: Double): Double = $cinteropPackage.kmSqrtf64(x)
public actual fun kmAbort(): Nothing {
    $cinteropPackage.kmAbort()
    error("Unreachable after kmAbort")
}

public actual inline fun kmSpinLoopHint(): Unit = $cinteropPackage.kmSpinLoopHint()
public actual inline fun kmBlackBoxU64(value: ULong): ULong = $cinteropPackage.kmBlackBoxU64(value)
public actual inline fun kmUmulhU64(a: ULong, b: ULong): ULong = $cinteropPackage.kmUmulhU64(a, b)
public actual inline fun kmSmulhI64(a: Long, b: Long): Long = $cinteropPackage.kmSmulhI64(a, b)
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

public actual inline fun kmSimdFmaF32x4(dstAddr: Long, aAddr: Long, bAddr: Long, cAddr: Long): Unit =
    $cinteropPackage.kmSimdFmaF32x4(dstAddr, aAddr, bAddr, cAddr)
public actual inline fun kmSimdDotF32(aAddr: Long, bAddr: Long, len: Long): Float =
    $cinteropPackage.kmSimdDotF32(aAddr, bAddr, len)
""".trimStart()

    private fun jniMangleSegment(segment: String): String =
        buildString(segment.length + 4) {
            for (ch in segment) {
                when {
                    ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' -> append(ch)
                    ch == '_' -> append("_1")
                    ch == ';' -> append("_2")
                    ch == '[' -> append("_3")
                    else -> append("_0").append(ch.code.toString(16).padStart(4, '0'))
                }
            }
        }

    public fun renderKmCoreJniC(
        coreNamespace: String,
        jniObjectName: String = "KmCoreJniBridge",
    ): String {
        val fullFq = "$coreNamespace.intrinsics.$jniObjectName"
        val cls = fullFq.split('.').joinToString("_") { jniMangleSegment(it) }
        return """
#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <jni.h>
#if __has_include("km_core_intrinsics_inline.h")
#include "km_core_intrinsics_inline.h"
#else
#include "../../../native/interop/cinterop/km_core_intrinsics_inline.h"
#endif
#if __has_include("km_core_llvm_asm.h")
#include "km_core_llvm_asm.h"
#else
#include "../../../native/interop/cinterop/km_core_llvm_asm.h"
#endif

#define KM_JNI_FN(Name) Java_${cls}_##Name

JNIEXPORT jbyte JNICALL KM_JNI_FN(kmLoadU8)(JNIEnv* env, jclass clazz, jlong addr) {
    (void)env; (void)clazz;
    return (jbyte)kmLoadU8((int64_t)addr);
}
JNIEXPORT jshort JNICALL KM_JNI_FN(kmLoadU16)(JNIEnv* env, jclass clazz, jlong addr) {
    (void)env; (void)clazz;
    return (jshort)kmLoadU16((int64_t)addr);
}
JNIEXPORT jint JNICALL KM_JNI_FN(kmLoadU32)(JNIEnv* env, jclass clazz, jlong addr) {
    (void)env; (void)clazz;
    return (jint)kmLoadU32((int64_t)addr);
}
JNIEXPORT jlong JNICALL KM_JNI_FN(kmLoadU64)(JNIEnv* env, jclass clazz, jlong addr) {
    (void)env; (void)clazz;
    return (jlong)kmLoadU64((int64_t)addr);
}
JNIEXPORT jfloat JNICALL KM_JNI_FN(kmLoadF32)(JNIEnv* env, jclass clazz, jlong addr) {
    (void)env; (void)clazz;
    return (jfloat)kmLoadF32((int64_t)addr);
}
JNIEXPORT jdouble JNICALL KM_JNI_FN(kmLoadF64)(JNIEnv* env, jclass clazz, jlong addr) {
    (void)env; (void)clazz;
    return (jdouble)kmLoadF64((int64_t)addr);
}

JNIEXPORT void JNICALL KM_JNI_FN(kmStoreU8)(JNIEnv* env, jclass clazz, jlong addr, jbyte value) {
    (void)env; (void)clazz;
    kmStoreU8((int64_t)addr, (uint8_t)value);
}
JNIEXPORT void JNICALL KM_JNI_FN(kmStoreU16)(JNIEnv* env, jclass clazz, jlong addr, jshort value) {
    (void)env; (void)clazz;
    kmStoreU16((int64_t)addr, (uint16_t)value);
}
JNIEXPORT void JNICALL KM_JNI_FN(kmStoreU32)(JNIEnv* env, jclass clazz, jlong addr, jint value) {
    (void)env; (void)clazz;
    kmStoreU32((int64_t)addr, (uint32_t)value);
}
JNIEXPORT void JNICALL KM_JNI_FN(kmStoreU64)(JNIEnv* env, jclass clazz, jlong addr, jlong value) {
    (void)env; (void)clazz;
    kmStoreU64((int64_t)addr, (uint64_t)value);
}
JNIEXPORT void JNICALL KM_JNI_FN(kmStoreF32)(JNIEnv* env, jclass clazz, jlong addr, jfloat value) {
    (void)env; (void)clazz;
    kmStoreF32((int64_t)addr, (float)value);
}
JNIEXPORT void JNICALL KM_JNI_FN(kmStoreF64)(JNIEnv* env, jclass clazz, jlong addr, jdouble value) {
    (void)env; (void)clazz;
    kmStoreF64((int64_t)addr, (double)value);
}

JNIEXPORT jbyte JNICALL KM_JNI_FN(kmVolatileLoadU8)(JNIEnv* env, jclass clazz, jlong addr) {
    (void)env; (void)clazz;
    return (jbyte)kmVolatileLoadU8((int64_t)addr);
}
JNIEXPORT jshort JNICALL KM_JNI_FN(kmVolatileLoadU16)(JNIEnv* env, jclass clazz, jlong addr) {
    (void)env; (void)clazz;
    return (jshort)kmVolatileLoadU16((int64_t)addr);
}
JNIEXPORT jint JNICALL KM_JNI_FN(kmVolatileLoadU32)(JNIEnv* env, jclass clazz, jlong addr) {
    (void)env; (void)clazz;
    return (jint)kmVolatileLoadU32((int64_t)addr);
}
JNIEXPORT jlong JNICALL KM_JNI_FN(kmVolatileLoadU64)(JNIEnv* env, jclass clazz, jlong addr) {
    (void)env; (void)clazz;
    return (jlong)kmVolatileLoadU64((int64_t)addr);
}

JNIEXPORT void JNICALL KM_JNI_FN(kmVolatileStoreU8)(JNIEnv* env, jclass clazz, jlong addr, jbyte value) {
    (void)env; (void)clazz;
    kmVolatileStoreU8((int64_t)addr, (uint8_t)value);
}
JNIEXPORT void JNICALL KM_JNI_FN(kmVolatileStoreU16)(JNIEnv* env, jclass clazz, jlong addr, jshort value) {
    (void)env; (void)clazz;
    kmVolatileStoreU16((int64_t)addr, (uint16_t)value);
}
JNIEXPORT void JNICALL KM_JNI_FN(kmVolatileStoreU32)(JNIEnv* env, jclass clazz, jlong addr, jint value) {
    (void)env; (void)clazz;
    kmVolatileStoreU32((int64_t)addr, (uint32_t)value);
}
JNIEXPORT void JNICALL KM_JNI_FN(kmVolatileStoreU64)(JNIEnv* env, jclass clazz, jlong addr, jlong value) {
    (void)env; (void)clazz;
    kmVolatileStoreU64((int64_t)addr, (uint64_t)value);
}

JNIEXPORT void JNICALL KM_JNI_FN(kmCopyNonoverlapping)(JNIEnv* env, jclass clazz, jlong dst, jlong src, jlong bytes) {
    (void)env; (void)clazz;
    kmCopyNonoverlapping((int64_t)dst, (int64_t)src, (int64_t)bytes);
}
JNIEXPORT void JNICALL KM_JNI_FN(kmCopy)(JNIEnv* env, jclass clazz, jlong dst, jlong src, jlong bytes) {
    (void)env; (void)clazz;
    kmCopy((int64_t)dst, (int64_t)src, (int64_t)bytes);
}
JNIEXPORT void JNICALL KM_JNI_FN(kmWriteBytes)(JNIEnv* env, jclass clazz, jlong dst, jbyte value, jlong bytes) {
    (void)env; (void)clazz;
    kmWriteBytes((int64_t)dst, (uint8_t)value, (int64_t)bytes);
}
JNIEXPORT jint JNICALL KM_JNI_FN(kmCompareBytes)(JNIEnv* env, jclass clazz, jlong left, jlong right, jlong bytes) {
    (void)env; (void)clazz;
    return (jint)kmCompareBytes((int64_t)left, (int64_t)right, (int64_t)bytes);
}

JNIEXPORT jlong JNICALL KM_JNI_FN(kmAlloc)(JNIEnv* env, jclass clazz, jlong size, jlong align) {
    (void)env; (void)clazz;
    return (jlong)kmAlloc((int64_t)size, (int64_t)align);
}
JNIEXPORT jlong JNICALL KM_JNI_FN(kmAllocZeroed)(JNIEnv* env, jclass clazz, jlong size, jlong align) {
    (void)env; (void)clazz;
    return (jlong)kmAllocZeroed((int64_t)size, (int64_t)align);
}
JNIEXPORT jlong JNICALL KM_JNI_FN(kmRealloc)(JNIEnv* env, jclass clazz, jlong ptr, jlong oldSize, jlong align, jlong newSize) {
    (void)env; (void)clazz;
    return (jlong)kmRealloc((int64_t)ptr, (int64_t)oldSize, (int64_t)align, (int64_t)newSize);
}
JNIEXPORT void JNICALL KM_JNI_FN(kmDealloc)(JNIEnv* env, jclass clazz, jlong ptr, jlong size, jlong align) {
    (void)env; (void)clazz;
    kmDealloc((int64_t)ptr, (int64_t)size, (int64_t)align);
}

JNIEXPORT void JNICALL KM_JNI_FN(kmAtomicFence)(JNIEnv* env, jclass clazz, jint ord) {
    (void)env; (void)clazz;
    kmAtomicFence((int32_t)ord);
}
JNIEXPORT void JNICALL KM_JNI_FN(kmAtomicSignalFence)(JNIEnv* env, jclass clazz, jint ord) {
    (void)env; (void)clazz;
    kmAtomicSignalFence((int32_t)ord);
}

JNIEXPORT jbyte JNICALL KM_JNI_FN(kmAtomicLoadU8)(JNIEnv* env, jclass clazz, jlong addr, jint ord) {
    (void)env; (void)clazz;
    return (jbyte)kmAtomicLoadU8((int64_t)addr, (int32_t)ord);
}
JNIEXPORT jshort JNICALL KM_JNI_FN(kmAtomicLoadU16)(JNIEnv* env, jclass clazz, jlong addr, jint ord) {
    (void)env; (void)clazz;
    return (jshort)kmAtomicLoadU16((int64_t)addr, (int32_t)ord);
}
JNIEXPORT jint JNICALL KM_JNI_FN(kmAtomicLoadU32)(JNIEnv* env, jclass clazz, jlong addr, jint ord) {
    (void)env; (void)clazz;
    return (jint)kmAtomicLoadU32((int64_t)addr, (int32_t)ord);
}
JNIEXPORT jlong JNICALL KM_JNI_FN(kmAtomicLoadU64)(JNIEnv* env, jclass clazz, jlong addr, jint ord) {
    (void)env; (void)clazz;
    return (jlong)kmAtomicLoadU64((int64_t)addr, (int32_t)ord);
}

JNIEXPORT void JNICALL KM_JNI_FN(kmAtomicStoreU8)(JNIEnv* env, jclass clazz, jlong addr, jbyte value, jint ord) {
    (void)env; (void)clazz;
    kmAtomicStoreU8((int64_t)addr, (uint8_t)value, (int32_t)ord);
}
JNIEXPORT void JNICALL KM_JNI_FN(kmAtomicStoreU16)(JNIEnv* env, jclass clazz, jlong addr, jshort value, jint ord) {
    (void)env; (void)clazz;
    kmAtomicStoreU16((int64_t)addr, (uint16_t)value, (int32_t)ord);
}
JNIEXPORT void JNICALL KM_JNI_FN(kmAtomicStoreU32)(JNIEnv* env, jclass clazz, jlong addr, jint value, jint ord) {
    (void)env; (void)clazz;
    kmAtomicStoreU32((int64_t)addr, (uint32_t)value, (int32_t)ord);
}
JNIEXPORT void JNICALL KM_JNI_FN(kmAtomicStoreU64)(JNIEnv* env, jclass clazz, jlong addr, jlong value, jint ord) {
    (void)env; (void)clazz;
    kmAtomicStoreU64((int64_t)addr, (uint64_t)value, (int32_t)ord);
}

JNIEXPORT jbyte JNICALL KM_JNI_FN(kmAtomicXchgU8)(JNIEnv* env, jclass clazz, jlong addr, jbyte value, jint ord) {
    (void)env; (void)clazz;
    return (jbyte)kmAtomicXchgU8((int64_t)addr, (uint8_t)value, (int32_t)ord);
}
JNIEXPORT jshort JNICALL KM_JNI_FN(kmAtomicXchgU16)(JNIEnv* env, jclass clazz, jlong addr, jshort value, jint ord) {
    (void)env; (void)clazz;
    return (jshort)kmAtomicXchgU16((int64_t)addr, (uint16_t)value, (int32_t)ord);
}
JNIEXPORT jint JNICALL KM_JNI_FN(kmAtomicXchgU32)(JNIEnv* env, jclass clazz, jlong addr, jint value, jint ord) {
    (void)env; (void)clazz;
    return (jint)kmAtomicXchgU32((int64_t)addr, (uint32_t)value, (int32_t)ord);
}
JNIEXPORT jlong JNICALL KM_JNI_FN(kmAtomicXchgU64)(JNIEnv* env, jclass clazz, jlong addr, jlong value, jint ord) {
    (void)env; (void)clazz;
    return (jlong)kmAtomicXchgU64((int64_t)addr, (uint64_t)value, (int32_t)ord);
}

JNIEXPORT jlong JNICALL KM_JNI_FN(kmAtomicCxchgU8Packed)(
    JNIEnv* env, jclass clazz, jlong addr, jbyte expected, jbyte desired, jint succ, jint fail
) {
    (void)env; (void)clazz;
    uint8_t ok = 0;
    uint8_t prev = kmAtomicCxchgU8((int64_t)addr, (uint8_t)expected, (uint8_t)desired, (int32_t)succ, (int32_t)fail, &ok);
    return ((jlong)(uint64_t)ok << 32) | (jlong)(uint64_t)prev;
}
JNIEXPORT jlong JNICALL KM_JNI_FN(kmAtomicCxchgU16Packed)(
    JNIEnv* env, jclass clazz, jlong addr, jshort expected, jshort desired, jint succ, jint fail
) {
    (void)env; (void)clazz;
    uint8_t ok = 0;
    uint16_t prev = kmAtomicCxchgU16((int64_t)addr, (uint16_t)expected, (uint16_t)desired, (int32_t)succ, (int32_t)fail, &ok);
    return ((jlong)(uint64_t)ok << 32) | (jlong)(uint64_t)prev;
}
JNIEXPORT jlong JNICALL KM_JNI_FN(kmAtomicCxchgU32Packed)(
    JNIEnv* env, jclass clazz, jlong addr, jint expected, jint desired, jint succ, jint fail
) {
    (void)env; (void)clazz;
    uint8_t ok = 0;
    uint32_t prev = kmAtomicCxchgU32((int64_t)addr, (uint32_t)expected, (uint32_t)desired, (int32_t)succ, (int32_t)fail, &ok);
    return ((jlong)(uint64_t)ok << 32) | (jlong)(uint64_t)prev;
}
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

JNIEXPORT jbyte JNICALL KM_JNI_FN(kmAtomicRmwU8)(JNIEnv* env, jclass clazz, jlong addr, jbyte value, jint op, jint ord) {
    (void)env; (void)clazz;
    return (jbyte)kmAtomicRmwU8((int64_t)addr, (uint8_t)value, (int32_t)op, (int32_t)ord);
}
JNIEXPORT jshort JNICALL KM_JNI_FN(kmAtomicRmwU16)(JNIEnv* env, jclass clazz, jlong addr, jshort value, jint op, jint ord) {
    (void)env; (void)clazz;
    return (jshort)kmAtomicRmwU16((int64_t)addr, (uint16_t)value, (int32_t)op, (int32_t)ord);
}
JNIEXPORT jint JNICALL KM_JNI_FN(kmAtomicRmwU32)(JNIEnv* env, jclass clazz, jlong addr, jint value, jint op, jint ord) {
    (void)env; (void)clazz;
    return (jint)kmAtomicRmwU32((int64_t)addr, (uint32_t)value, (int32_t)op, (int32_t)ord);
}
JNIEXPORT jlong JNICALL KM_JNI_FN(kmAtomicRmwU64)(JNIEnv* env, jclass clazz, jlong addr, jlong value, jint op, jint ord) {
    (void)env; (void)clazz;
    return (jlong)kmAtomicRmwU64((int64_t)addr, (uint64_t)value, (int32_t)op, (int32_t)ord);
}

JNIEXPORT jint JNICALL KM_JNI_FN(kmCtpopU64)(JNIEnv* env, jclass clazz, jlong value) {
    (void)env; (void)clazz;
    return (jint)kmCtpopU64((uint64_t)value);
}
JNIEXPORT jint JNICALL KM_JNI_FN(kmCtlzU64)(JNIEnv* env, jclass clazz, jlong value) {
    (void)env; (void)clazz;
    return (jint)kmCtlzU64((uint64_t)value);
}
JNIEXPORT jint JNICALL KM_JNI_FN(kmCttzU64)(JNIEnv* env, jclass clazz, jlong value) {
    (void)env; (void)clazz;
    return (jint)kmCttzU64((uint64_t)value);
}
JNIEXPORT jshort JNICALL KM_JNI_FN(kmBswapU16)(JNIEnv* env, jclass clazz, jshort value) {
    (void)env; (void)clazz;
    return (jshort)kmBswapU16((uint16_t)value);
}
JNIEXPORT jint JNICALL KM_JNI_FN(kmBswapU32)(JNIEnv* env, jclass clazz, jint value) {
    (void)env; (void)clazz;
    return (jint)kmBswapU32((uint32_t)value);
}
JNIEXPORT jlong JNICALL KM_JNI_FN(kmBswapU64)(JNIEnv* env, jclass clazz, jlong value) {
    (void)env; (void)clazz;
    return (jlong)kmBswapU64((uint64_t)value);
}
JNIEXPORT jlong JNICALL KM_JNI_FN(kmBitreverseU64)(JNIEnv* env, jclass clazz, jlong value) {
    (void)env; (void)clazz;
    return (jlong)kmBitreverseU64((uint64_t)value);
}
JNIEXPORT jfloat JNICALL KM_JNI_FN(kmFmaf32)(JNIEnv* env, jclass clazz, jfloat a, jfloat b, jfloat c) {
    (void)env; (void)clazz;
    return (jfloat)kmFmaf32((float)a, (float)b, (float)c);
}
JNIEXPORT jdouble JNICALL KM_JNI_FN(kmFmaf64)(JNIEnv* env, jclass clazz, jdouble a, jdouble b, jdouble c) {
    (void)env; (void)clazz;
    return (jdouble)kmFmaf64((double)a, (double)b, (double)c);
}
JNIEXPORT jfloat JNICALL KM_JNI_FN(kmSqrtf32)(JNIEnv* env, jclass clazz, jfloat x) {
    (void)env; (void)clazz;
    return (jfloat)kmSqrtf32((float)x);
}
JNIEXPORT jdouble JNICALL KM_JNI_FN(kmSqrtf64)(JNIEnv* env, jclass clazz, jdouble x) {
    (void)env; (void)clazz;
    return (jdouble)kmSqrtf64((double)x);
}
JNIEXPORT void JNICALL KM_JNI_FN(kmAbort)(JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
    kmAbort();
}

JNIEXPORT void JNICALL KM_JNI_FN(kmSpinLoopHint)(JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
    kmSpinLoopHint();
}
JNIEXPORT jlong JNICALL KM_JNI_FN(kmBlackBoxU64)(JNIEnv* env, jclass clazz, jlong value) {
    (void)env; (void)clazz;
    return (jlong)kmBlackBoxU64((uint64_t)value);
}
JNIEXPORT jlong JNICALL KM_JNI_FN(kmUmulhU64)(JNIEnv* env, jclass clazz, jlong a, jlong b) {
    (void)env; (void)clazz;
    return (jlong)kmUmulhU64((uint64_t)a, (uint64_t)b);
}
JNIEXPORT jlong JNICALL KM_JNI_FN(kmSmulhI64)(JNIEnv* env, jclass clazz, jlong a, jlong b) {
    (void)env; (void)clazz;
    return (jlong)kmSmulhI64((int64_t)a, (int64_t)b);
}
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
JNIEXPORT void JNICALL KM_JNI_FN(kmSimdFmaF32x4)(
    JNIEnv* env, jclass clazz, jlong dstAddr, jlong aAddr, jlong bAddr, jlong cAddr
) {
    (void)env; (void)clazz;
    kmSimdFmaF32x4((int64_t)dstAddr, (int64_t)aAddr, (int64_t)bAddr, (int64_t)cAddr);
}
JNIEXPORT jfloat JNICALL KM_JNI_FN(kmSimdDotF32)(
    JNIEnv* env, jclass clazz, jlong aAddr, jlong bAddr, jlong len
) {
    (void)env; (void)clazz;
    return (jfloat)kmSimdDotF32((int64_t)aAddr, (int64_t)bAddr, (int64_t)len);
}
""".trimStart()
    }

    private fun renderKmCoreSharedJvmBridgeMethods(jniObjectName: String): String =
        """
    @JvmStatic external fun kmLoadU8(addr: Long): Byte
    @JvmStatic external fun kmLoadU16(addr: Long): Short
    @JvmStatic external fun kmLoadU32(addr: Long): Int
    @JvmStatic external fun kmLoadU64(addr: Long): Long
    @JvmStatic external fun kmLoadF32(addr: Long): Float
    @JvmStatic external fun kmLoadF64(addr: Long): Double

    @JvmStatic external fun kmStoreU8(addr: Long, value: Byte)
    @JvmStatic external fun kmStoreU16(addr: Long, value: Short)
    @JvmStatic external fun kmStoreU32(addr: Long, value: Int)
    @JvmStatic external fun kmStoreU64(addr: Long, value: Long)
    @JvmStatic external fun kmStoreF32(addr: Long, value: Float)
    @JvmStatic external fun kmStoreF64(addr: Long, value: Double)

    @JvmStatic external fun kmVolatileLoadU8(addr: Long): Byte
    @JvmStatic external fun kmVolatileLoadU16(addr: Long): Short
    @JvmStatic external fun kmVolatileLoadU32(addr: Long): Int
    @JvmStatic external fun kmVolatileLoadU64(addr: Long): Long

    @JvmStatic external fun kmVolatileStoreU8(addr: Long, value: Byte)
    @JvmStatic external fun kmVolatileStoreU16(addr: Long, value: Short)
    @JvmStatic external fun kmVolatileStoreU32(addr: Long, value: Int)
    @JvmStatic external fun kmVolatileStoreU64(addr: Long, value: Long)

    @JvmStatic external fun kmCopyNonoverlapping(dst: Long, src: Long, bytes: Long)
    @JvmStatic external fun kmCopy(dst: Long, src: Long, bytes: Long)
    @JvmStatic external fun kmWriteBytes(dst: Long, value: Byte, bytes: Long)
    @JvmStatic external fun kmCompareBytes(left: Long, right: Long, bytes: Long): Int

    @JvmStatic external fun kmAlloc(size: Long, align: Long): Long
    @JvmStatic external fun kmAllocZeroed(size: Long, align: Long): Long
    @JvmStatic external fun kmRealloc(ptr: Long, oldSize: Long, align: Long, newSize: Long): Long
    @JvmStatic external fun kmDealloc(ptr: Long, size: Long, align: Long)

    @JvmStatic external fun kmAtomicFence(ord: Int)
    @JvmStatic external fun kmAtomicSignalFence(ord: Int)
    @JvmStatic external fun kmAtomicLoadU8(addr: Long, ord: Int): Byte
    @JvmStatic external fun kmAtomicLoadU16(addr: Long, ord: Int): Short
    @JvmStatic external fun kmAtomicLoadU32(addr: Long, ord: Int): Int
    @JvmStatic external fun kmAtomicLoadU64(addr: Long, ord: Int): Long
    @JvmStatic external fun kmAtomicStoreU8(addr: Long, value: Byte, ord: Int)
    @JvmStatic external fun kmAtomicStoreU16(addr: Long, value: Short, ord: Int)
    @JvmStatic external fun kmAtomicStoreU32(addr: Long, value: Int, ord: Int)
    @JvmStatic external fun kmAtomicStoreU64(addr: Long, value: Long, ord: Int)
    @JvmStatic external fun kmAtomicXchgU8(addr: Long, value: Byte, ord: Int): Byte
    @JvmStatic external fun kmAtomicXchgU16(addr: Long, value: Short, ord: Int): Short
    @JvmStatic external fun kmAtomicXchgU32(addr: Long, value: Int, ord: Int): Int
    @JvmStatic external fun kmAtomicXchgU64(addr: Long, value: Long, ord: Int): Long
    @JvmStatic external fun kmAtomicCxchgU8Packed(addr: Long, expected: Byte, desired: Byte, succ: Int, fail: Int): Long
    @JvmStatic external fun kmAtomicCxchgU16Packed(addr: Long, expected: Short, desired: Short, succ: Int, fail: Int): Long
    @JvmStatic external fun kmAtomicCxchgU32Packed(addr: Long, expected: Int, desired: Int, succ: Int, fail: Int): Long
    @JvmStatic external fun kmAtomicCxchgU64Out(addr: Long, expected: Long, desired: Long, succ: Int, fail: Int, outBuf: LongArray)
    @JvmStatic external fun kmAtomicRmwU8(addr: Long, value: Byte, op: Int, ord: Int): Byte
    @JvmStatic external fun kmAtomicRmwU16(addr: Long, value: Short, op: Int, ord: Int): Short
    @JvmStatic external fun kmAtomicRmwU32(addr: Long, value: Int, op: Int, ord: Int): Int
    @JvmStatic external fun kmAtomicRmwU64(addr: Long, value: Long, op: Int, ord: Int): Long

    @JvmStatic external fun kmCtpopU64(value: Long): Int
    @JvmStatic external fun kmCtlzU64(value: Long): Int
    @JvmStatic external fun kmCttzU64(value: Long): Int
    @JvmStatic external fun kmBswapU16(value: Short): Short
    @JvmStatic external fun kmBswapU32(value: Int): Int
    @JvmStatic external fun kmBswapU64(value: Long): Long
    @JvmStatic external fun kmBitreverseU64(value: Long): Long
    @JvmStatic external fun kmFmaf32(a: Float, b: Float, c: Float): Float
    @JvmStatic external fun kmFmaf64(a: Double, b: Double, c: Double): Double
    @JvmStatic external fun kmSqrtf32(x: Float): Float
    @JvmStatic external fun kmSqrtf64(x: Double): Double
    @JvmStatic external fun kmAbort()

    @JvmStatic external fun kmSpinLoopHint()
    @JvmStatic external fun kmBlackBoxU64(value: Long): Long
    @JvmStatic external fun kmUmulhU64(a: Long, b: Long): Long
    @JvmStatic external fun kmSmulhI64(a: Long, b: Long): Long
    @JvmStatic external fun kmAtomicCxchgU128Out(addr: Long, expLo: Long, expHi: Long, desLo: Long, desHi: Long, outBuf: LongArray)
    @JvmStatic external fun kmSimdFmaF32x4(dstAddr: Long, aAddr: Long, bAddr: Long, cAddr: Long)
    @JvmStatic external fun kmSimdDotF32(aAddr: Long, bAddr: Long, len: Long): Float
}

public actual fun kmLoadU8(addr: Long): UByte = $jniObjectName.kmLoadU8(addr).toUByte()
public actual fun kmLoadU16(addr: Long): UShort = $jniObjectName.kmLoadU16(addr).toUShort()
public actual fun kmLoadU32(addr: Long): UInt = $jniObjectName.kmLoadU32(addr).toUInt()
public actual fun kmLoadU64(addr: Long): ULong = $jniObjectName.kmLoadU64(addr).toULong()
public actual fun kmLoadF32(addr: Long): Float = $jniObjectName.kmLoadF32(addr)
public actual fun kmLoadF64(addr: Long): Double = $jniObjectName.kmLoadF64(addr)

public actual fun kmStoreU8(addr: Long, value: UByte): Unit = $jniObjectName.kmStoreU8(addr, value.toByte())
public actual fun kmStoreU16(addr: Long, value: UShort): Unit = $jniObjectName.kmStoreU16(addr, value.toShort())
public actual fun kmStoreU32(addr: Long, value: UInt): Unit = $jniObjectName.kmStoreU32(addr, value.toInt())
public actual fun kmStoreU64(addr: Long, value: ULong): Unit = $jniObjectName.kmStoreU64(addr, value.toLong())
public actual fun kmStoreF32(addr: Long, value: Float): Unit = $jniObjectName.kmStoreF32(addr, value)
public actual fun kmStoreF64(addr: Long, value: Double): Unit = $jniObjectName.kmStoreF64(addr, value)

public actual fun kmVolatileLoadU8(addr: Long): UByte = $jniObjectName.kmVolatileLoadU8(addr).toUByte()
public actual fun kmVolatileLoadU16(addr: Long): UShort = $jniObjectName.kmVolatileLoadU16(addr).toUShort()
public actual fun kmVolatileLoadU32(addr: Long): UInt = $jniObjectName.kmVolatileLoadU32(addr).toUInt()
public actual fun kmVolatileLoadU64(addr: Long): ULong = $jniObjectName.kmVolatileLoadU64(addr).toULong()

public actual fun kmVolatileStoreU8(addr: Long, value: UByte): Unit = $jniObjectName.kmVolatileStoreU8(addr, value.toByte())
public actual fun kmVolatileStoreU16(addr: Long, value: UShort): Unit = $jniObjectName.kmVolatileStoreU16(addr, value.toShort())
public actual fun kmVolatileStoreU32(addr: Long, value: UInt): Unit = $jniObjectName.kmVolatileStoreU32(addr, value.toInt())
public actual fun kmVolatileStoreU64(addr: Long, value: ULong): Unit = $jniObjectName.kmVolatileStoreU64(addr, value.toLong())

public actual fun kmCopyNonoverlapping(dst: Long, src: Long, bytes: Long): Unit =
    $jniObjectName.kmCopyNonoverlapping(dst, src, bytes)
public actual fun kmCopy(dst: Long, src: Long, bytes: Long): Unit =
    $jniObjectName.kmCopy(dst, src, bytes)
public actual fun kmWriteBytes(dst: Long, value: UByte, bytes: Long): Unit =
    $jniObjectName.kmWriteBytes(dst, value.toByte(), bytes)
public actual fun kmCompareBytes(left: Long, right: Long, bytes: Long): Int =
    $jniObjectName.kmCompareBytes(left, right, bytes)

public actual fun kmAlloc(size: Long, align: Long): Long = $jniObjectName.kmAlloc(size, align)
public actual fun kmAllocZeroed(size: Long, align: Long): Long = $jniObjectName.kmAllocZeroed(size, align)
public actual fun kmRealloc(ptr: Long, oldSize: Long, align: Long, newSize: Long): Long =
    $jniObjectName.kmRealloc(ptr, oldSize, align, newSize)
public actual fun kmDealloc(ptr: Long, size: Long, align: Long): Unit =
    $jniObjectName.kmDealloc(ptr, size, align)

public actual fun kmAtomicFence(ord: Int): Unit = $jniObjectName.kmAtomicFence(ord)
public actual fun kmAtomicSignalFence(ord: Int): Unit = $jniObjectName.kmAtomicSignalFence(ord)
public actual fun kmAtomicLoadU8(addr: Long, ord: Int): UByte = $jniObjectName.kmAtomicLoadU8(addr, ord).toUByte()
public actual fun kmAtomicLoadU16(addr: Long, ord: Int): UShort = $jniObjectName.kmAtomicLoadU16(addr, ord).toUShort()
public actual fun kmAtomicLoadU32(addr: Long, ord: Int): UInt = $jniObjectName.kmAtomicLoadU32(addr, ord).toUInt()
public actual fun kmAtomicLoadU64(addr: Long, ord: Int): ULong = $jniObjectName.kmAtomicLoadU64(addr, ord).toULong()
public actual fun kmAtomicStoreU8(addr: Long, value: UByte, ord: Int): Unit = $jniObjectName.kmAtomicStoreU8(addr, value.toByte(), ord)
public actual fun kmAtomicStoreU16(addr: Long, value: UShort, ord: Int): Unit = $jniObjectName.kmAtomicStoreU16(addr, value.toShort(), ord)
public actual fun kmAtomicStoreU32(addr: Long, value: UInt, ord: Int): Unit = $jniObjectName.kmAtomicStoreU32(addr, value.toInt(), ord)
public actual fun kmAtomicStoreU64(addr: Long, value: ULong, ord: Int): Unit = $jniObjectName.kmAtomicStoreU64(addr, value.toLong(), ord)
public actual fun kmAtomicXchgU8(addr: Long, value: UByte, ord: Int): UByte = $jniObjectName.kmAtomicXchgU8(addr, value.toByte(), ord).toUByte()
public actual fun kmAtomicXchgU16(addr: Long, value: UShort, ord: Int): UShort = $jniObjectName.kmAtomicXchgU16(addr, value.toShort(), ord).toUShort()
public actual fun kmAtomicXchgU32(addr: Long, value: UInt, ord: Int): UInt = $jniObjectName.kmAtomicXchgU32(addr, value.toInt(), ord).toUInt()
public actual fun kmAtomicXchgU64(addr: Long, value: ULong, ord: Int): ULong = $jniObjectName.kmAtomicXchgU64(addr, value.toLong(), ord).toULong()

public actual fun kmAtomicCxchgU8(addr: Long, expected: UByte, desired: UByte, succ: Int, fail: Int): AtomicCxchgU8Outcome {
    val packed = $jniObjectName.kmAtomicCxchgU8Packed(addr, expected.toByte(), desired.toByte(), succ, fail)
    return AtomicCxchgU8Outcome((packed and 0xFFL).toUByte(), (packed ushr 32) != 0L)
}
public actual fun kmAtomicCxchgU16(addr: Long, expected: UShort, desired: UShort, succ: Int, fail: Int): AtomicCxchgU16Outcome {
    val packed = $jniObjectName.kmAtomicCxchgU16Packed(addr, expected.toShort(), desired.toShort(), succ, fail)
    return AtomicCxchgU16Outcome((packed and 0xFFFFL).toUShort(), (packed ushr 32) != 0L)
}
public actual fun kmAtomicCxchgU32(addr: Long, expected: UInt, desired: UInt, succ: Int, fail: Int): AtomicCxchgU32Outcome {
    val packed = $jniObjectName.kmAtomicCxchgU32Packed(addr, expected.toInt(), desired.toInt(), succ, fail)
    return AtomicCxchgU32Outcome((packed and 0xFFFFFFFFL).toUInt(), (packed ushr 32) != 0L)
}
public actual fun kmAtomicCxchgU64(addr: Long, expected: ULong, desired: ULong, succ: Int, fail: Int): AtomicCxchgU64Outcome {
    val outBuf = LongArray(2)
    $jniObjectName.kmAtomicCxchgU64Out(addr, expected.toLong(), desired.toLong(), succ, fail, outBuf)
    return AtomicCxchgU64Outcome(outBuf[0].toULong(), outBuf[1] != 0L)
}

public actual fun kmAtomicRmwU8(addr: Long, value: UByte, op: Int, ord: Int): UByte =
    $jniObjectName.kmAtomicRmwU8(addr, value.toByte(), op, ord).toUByte()
public actual fun kmAtomicRmwU16(addr: Long, value: UShort, op: Int, ord: Int): UShort =
    $jniObjectName.kmAtomicRmwU16(addr, value.toShort(), op, ord).toUShort()
public actual fun kmAtomicRmwU32(addr: Long, value: UInt, op: Int, ord: Int): UInt =
    $jniObjectName.kmAtomicRmwU32(addr, value.toInt(), op, ord).toUInt()
public actual fun kmAtomicRmwU64(addr: Long, value: ULong, op: Int, ord: Int): ULong =
    $jniObjectName.kmAtomicRmwU64(addr, value.toLong(), op, ord).toULong()

public actual fun kmCtpopU64(value: ULong): UInt = $jniObjectName.kmCtpopU64(value.toLong()).toUInt()
public actual fun kmCtlzU64(value: ULong): UInt = $jniObjectName.kmCtlzU64(value.toLong()).toUInt()
public actual fun kmCttzU64(value: ULong): UInt = $jniObjectName.kmCttzU64(value.toLong()).toUInt()
public actual fun kmBswapU16(value: UShort): UShort = $jniObjectName.kmBswapU16(value.toShort()).toUShort()
public actual fun kmBswapU32(value: UInt): UInt = $jniObjectName.kmBswapU32(value.toInt()).toUInt()
public actual fun kmBswapU64(value: ULong): ULong = $jniObjectName.kmBswapU64(value.toLong()).toULong()
public actual fun kmBitreverseU64(value: ULong): ULong = $jniObjectName.kmBitreverseU64(value.toLong()).toULong()
public actual fun kmFmaf32(a: Float, b: Float, c: Float): Float = $jniObjectName.kmFmaf32(a, b, c)
public actual fun kmFmaf64(a: Double, b: Double, c: Double): Double = $jniObjectName.kmFmaf64(a, b, c)
public actual fun kmSqrtf32(x: Float): Float = $jniObjectName.kmSqrtf32(x)
public actual fun kmSqrtf64(x: Double): Double = $jniObjectName.kmSqrtf64(x)
public actual fun kmAbort(): Nothing {
    $jniObjectName.kmAbort()
    error("Unreachable after kmAbort")
}

public actual fun kmSpinLoopHint(): Unit = $jniObjectName.kmSpinLoopHint()
public actual fun kmBlackBoxU64(value: ULong): ULong = $jniObjectName.kmBlackBoxU64(value.toLong()).toULong()
public actual fun kmUmulhU64(a: ULong, b: ULong): ULong = $jniObjectName.kmUmulhU64(a.toLong(), b.toLong()).toULong()
public actual fun kmSmulhI64(a: Long, b: Long): Long = $jniObjectName.kmSmulhI64(a, b)
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
public actual fun kmSimdFmaF32x4(dstAddr: Long, aAddr: Long, bAddr: Long, cAddr: Long): Unit =
    $jniObjectName.kmSimdFmaF32x4(dstAddr, aAddr, bAddr, cAddr)
public actual fun kmSimdDotF32(aAddr: Long, bAddr: Long, len: Long): Float =
    $jniObjectName.kmSimdDotF32(aAddr, bAddr, len)
""".trimEnd()

    public fun renderKmCoreJvmActualsKotlin(
        coreNamespace: String,
        jniLibName: String = "kmcore_jni",
        projectNamespace: String = coreNamespace.substringBeforeLast('.'),
        jniObjectName: String = "KmCoreJniBridge",
    ): String {
        val resourcePrefix = projectNamespace.replace('.', '/')
        return """
@file:OptIn(ExperimentalUnsignedTypes::class)

package $coreNamespace.intrinsics

internal object $jniObjectName {
    init {
        loadNativeLibrary()
    }

    private fun loadNativeLibrary() {
        val osRaw = System.getProperty("os.name", "").lowercase()
        val archRaw = System.getProperty("os.arch", "").lowercase()
        val hostPlatform =
            when {
                osRaw.contains("win") -> "windows"
                osRaw.contains("mac") || osRaw.contains("darwin") -> "macos"
                else -> "linux"
            }
        val hostArch =
            when {
                archRaw == "aarch64" || archRaw == "arm64" -> "aarch64"
                archRaw == "amd64" || archRaw == "x86_64" -> "x86_64"
                else -> archRaw
            }
        val libFileName =
            when (hostPlatform) {
                "windows" -> "$jniLibName.dll"
                "macos" -> "lib$jniLibName.dylib"
                else -> "lib$jniLibName.so"
            }
        val pathSep = System.getProperty("path.separator", ":")
        val libPathProp = System.getProperty("java.library.path", "")
        for (dirEntry in libPathProp.split(pathSep)) {
            if (dirEntry.isEmpty()) continue
            val candidate = java.io.File(dirEntry, libFileName)
            if (candidate.isFile) {
                System.load(candidate.absolutePath)
                return
            }
        }
        val resourcePath = "$resourcePrefix/native/${'$'}hostPlatform-${'$'}hostArch/${'$'}libFileName"
        val stream = $jniObjectName::class.java.classLoader?.getResourceAsStream(resourcePath)
        if (stream != null) {
            val suffix = "." + libFileName.substringAfterLast('.')
            val extracted = java.nio.file.Files.createTempFile("$jniLibName-", suffix).toFile()
            extracted.deleteOnExit()
            stream.use { input ->
                extracted.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            System.load(extracted.absolutePath)
            return
        }
        System.loadLibrary("$jniLibName")
    }

${renderKmCoreSharedJvmBridgeMethods(jniObjectName)}
""".trimStart() + "\n"
    }

    public fun renderKmCoreAndroidActualsKotlin(
        coreNamespace: String,
        jniLibName: String = "kmcore_jni",
        jniObjectName: String = "KmCoreJniBridge",
    ): String =
        """
@file:OptIn(ExperimentalUnsignedTypes::class)

package $coreNamespace.intrinsics

internal object $jniObjectName {
    init {
        System.loadLibrary("$jniLibName")
    }

${renderKmCoreSharedJvmBridgeMethods(jniObjectName)}
""".trimStart() + "\n"

    public fun renderKmCoreLinearMemoryArenaKotlin(coreNamespace: String): String =
        """
package $coreNamespace.intrinsics

import kotlin.math.sqrt

internal object KmLinearMemoryArena {
    private const val ARENA_BASE_ADDR: Long = 65536L
    private const val INITIAL_CAPACITY_BYTES: Int = 1048576

    private var memory: ByteArray = ByteArray(INITIAL_CAPACITY_BYTES)
    private var bumpOffset: Int = ARENA_BASE_ADDR.toInt()
    private val allocBucketSizes: MutableMap<Long, Int> = hashMapOf()
    private val freeLists: MutableMap<Int, ArrayDeque<Long>> = hashMapOf()

    private fun ensureCapacity(requiredEnd: Int) {
        if (requiredEnd <= memory.size) return
        var newCap = memory.size
        while (newCap < requiredEnd) {
            newCap = newCap shl 1
        }
        memory = memory.copyOf(newCap)
    }

    private fun roundUpBucket(size: Long, align: Long): Int {
        val minReq = maxOf(1L, maxOf(size, align)).coerceAtMost(Int.MAX_VALUE.toLong() / 2L).toInt()
        var bucket = 8
        while (bucket < minReq) {
            bucket = bucket shl 1
        }
        return bucket
    }

    fun alloc(size: Long, align: Long): Long {
        val bucket = roundUpBucket(size, align)
        val freeQueue = freeLists[bucket]
        if (freeQueue != null && freeQueue.isNotEmpty()) {
            val reused = freeQueue.removeLast()
            allocBucketSizes[reused] = bucket
            return reused
        }
        val alignInt = maxOf(1L, align).toInt()
        val mask = alignInt - 1
        val alignedStart =
            if ((alignInt and mask) == 0) {
                (bumpOffset + mask) and mask.inv()
            } else {
                ((bumpOffset + alignInt - 1) / alignInt) * alignInt
            }
        val end = alignedStart + bucket
        ensureCapacity(end)
        bumpOffset = end
        val addr = alignedStart.toLong()
        allocBucketSizes[addr] = bucket
        return addr
    }

    fun allocZeroed(size: Long, align: Long): Long {
        val addr = alloc(size, align)
        val len = maxOf(0L, size).toInt()
        if (len > 0) {
            val start = addr.toInt()
            memory.fill(0, start, start + len)
        }
        return addr
    }

    fun realloc(ptr: Long, oldSize: Long, align: Long, newSize: Long): Long {
        if (ptr == 0L) return alloc(newSize, align)
        val existingBucket = allocBucketSizes[ptr]
        val reqNew = maxOf(1L, newSize).toInt()
        val alignInt = maxOf(1L, align).toInt()
        if (existingBucket != null && reqNew <= existingBucket && (ptr % alignInt.toLong()) == 0L) {
            return ptr
        }
        val fresh = alloc(newSize, align)
        val copyLen = minOf(maxOf(0L, oldSize), maxOf(0L, newSize)).toInt()
        if (copyLen > 0) {
            memory.copyInto(memory, fresh.toInt(), ptr.toInt(), ptr.toInt() + copyLen)
        }
        dealloc(ptr, oldSize, align)
        return fresh
    }

    fun dealloc(ptr: Long, size: Long, align: Long) {
        if (ptr < ARENA_BASE_ADDR) return
        val bucket = allocBucketSizes.remove(ptr) ?: roundUpBucket(size, align)
        freeLists.getOrPut(bucket) { ArrayDeque() }.addLast(ptr)
    }

    fun loadU8(addr: Long): UByte = memory[addr.toInt()].toUByte()

    fun loadU16(addr: Long): UShort {
        val idx = addr.toInt()
        val b0 = memory[idx].toInt() and 0xFF
        val b1 = memory[idx + 1].toInt() and 0xFF
        return (b0 or (b1 shl 8)).toUShort()
    }

    fun loadU32(addr: Long): UInt {
        val idx = addr.toInt()
        val b0 = memory[idx].toUInt() and 0xFFu
        val b1 = memory[idx + 1].toUInt() and 0xFFu
        val b2 = memory[idx + 2].toUInt() and 0xFFu
        val b3 = memory[idx + 3].toUInt() and 0xFFu
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    fun loadU64(addr: Long): ULong {
        val lo = loadU32(addr).toULong()
        val hi = loadU32(addr + 4L).toULong()
        return lo or (hi shl 32)
    }

    fun loadF32(addr: Long): Float = Float.fromBits(loadU32(addr).toInt())

    fun loadF64(addr: Long): Double = Double.fromBits(loadU64(addr).toLong())

    fun storeU8(addr: Long, value: UByte) {
        memory[addr.toInt()] = value.toByte()
    }

    fun storeU16(addr: Long, value: UShort) {
        val idx = addr.toInt()
        val v = value.toInt()
        memory[idx] = (v and 0xFF).toByte()
        memory[idx + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    fun storeU32(addr: Long, value: UInt) {
        val idx = addr.toInt()
        memory[idx] = (value and 0xFFu).toByte()
        memory[idx + 1] = ((value shr 8) and 0xFFu).toByte()
        memory[idx + 2] = ((value shr 16) and 0xFFu).toByte()
        memory[idx + 3] = ((value shr 24) and 0xFFu).toByte()
    }

    fun storeU64(addr: Long, value: ULong) {
        storeU32(addr, (value and 0xFFFFFFFFuL).toUInt())
        storeU32(addr + 4L, (value shr 32).toUInt())
    }

    fun storeF32(addr: Long, value: Float) {
        storeU32(addr, value.toRawBits().toUInt())
    }

    fun storeF64(addr: Long, value: Double) {
        storeU64(addr, value.toRawBits().toULong())
    }

    fun copyNonoverlapping(dst: Long, src: Long, bytes: Long) {
        val len = bytes.toInt()
        if (len <= 0) return
        memory.copyInto(memory, dst.toInt(), src.toInt(), src.toInt() + len)
    }

    fun copy(dst: Long, src: Long, bytes: Long) {
        val len = bytes.toInt()
        if (len <= 0 || dst == src) return
        memory.copyInto(memory, dst.toInt(), src.toInt(), src.toInt() + len)
    }

    fun writeBytes(dst: Long, value: UByte, bytes: Long) {
        val len = bytes.toInt()
        if (len <= 0) return
        val start = dst.toInt()
        memory.fill(value.toByte(), start, start + len)
    }

    fun compareBytes(left: Long, right: Long, bytes: Long): Int {
        val len = bytes.toInt()
        if (len <= 0 || left == right) return 0
        val lBase = left.toInt()
        val rBase = right.toInt()
        for (i in 0 until len) {
            val lb = memory[lBase + i].toInt() and 0xFF
            val rb = memory[rBase + i].toInt() and 0xFF
            if (lb != rb) return lb - rb
        }
        return 0
    }

    fun atomicXchgU8(addr: Long, value: UByte): UByte {
        val prev = loadU8(addr)
        storeU8(addr, value)
        return prev
    }

    fun atomicXchgU16(addr: Long, value: UShort): UShort {
        val prev = loadU16(addr)
        storeU16(addr, value)
        return prev
    }

    fun atomicXchgU32(addr: Long, value: UInt): UInt {
        val prev = loadU32(addr)
        storeU32(addr, value)
        return prev
    }

    fun atomicXchgU64(addr: Long, value: ULong): ULong {
        val prev = loadU64(addr)
        storeU64(addr, value)
        return prev
    }

    fun atomicCxchgU8(addr: Long, expected: UByte, desired: UByte): AtomicCxchgU8Outcome {
        val cur = loadU8(addr)
        return if (cur == expected) {
            storeU8(addr, desired)
            AtomicCxchgU8Outcome(cur, true)
        } else {
            AtomicCxchgU8Outcome(cur, false)
        }
    }

    fun atomicCxchgU16(addr: Long, expected: UShort, desired: UShort): AtomicCxchgU16Outcome {
        val cur = loadU16(addr)
        return if (cur == expected) {
            storeU16(addr, desired)
            AtomicCxchgU16Outcome(cur, true)
        } else {
            AtomicCxchgU16Outcome(cur, false)
        }
    }

    fun atomicCxchgU32(addr: Long, expected: UInt, desired: UInt): AtomicCxchgU32Outcome {
        val cur = loadU32(addr)
        return if (cur == expected) {
            storeU32(addr, desired)
            AtomicCxchgU32Outcome(cur, true)
        } else {
            AtomicCxchgU32Outcome(cur, false)
        }
    }

    fun atomicCxchgU64(addr: Long, expected: ULong, desired: ULong): AtomicCxchgU64Outcome {
        val cur = loadU64(addr)
        return if (cur == expected) {
            storeU64(addr, desired)
            AtomicCxchgU64Outcome(cur, true)
        } else {
            AtomicCxchgU64Outcome(cur, false)
        }
    }

    fun atomicCxchgU128Pair(addr: Long, expLo: ULong, expHi: ULong, desLo: ULong, desHi: ULong): AtomicCxchgU128Outcome {
        val curLo = loadU64(addr)
        val curHi = loadU64(addr + 8L)
        return if (curLo == expLo && curHi == expHi) {
            storeU64(addr, desLo)
            storeU64(addr + 8L, desHi)
            AtomicCxchgU128Outcome(curLo, curHi, true)
        } else {
            AtomicCxchgU128Outcome(curLo, curHi, false)
        }
    }

    fun atomicRmwU8(addr: Long, value: UByte, op: Int): UByte {
        val cur = loadU8(addr)
        val next =
            when (op) {
                1 -> (cur + value).toUByte()
                2 -> (cur - value).toUByte()
                3 -> cur and value
                4 -> (cur and value).inv()
                5 -> cur or value
                6 -> cur xor value
                7 -> if (cur.toByte() > value.toByte()) cur else value
                8 -> if (cur.toByte() < value.toByte()) cur else value
                9 -> if (cur > value) cur else value
                else -> if (cur < value) cur else value
            }
        storeU8(addr, next)
        return cur
    }

    fun atomicRmwU16(addr: Long, value: UShort, op: Int): UShort {
        val cur = loadU16(addr)
        val next =
            when (op) {
                1 -> (cur + value).toUShort()
                2 -> (cur - value).toUShort()
                3 -> cur and value
                4 -> (cur and value).inv()
                5 -> cur or value
                6 -> cur xor value
                7 -> if (cur.toShort() > value.toShort()) cur else value
                8 -> if (cur.toShort() < value.toShort()) cur else value
                9 -> if (cur > value) cur else value
                else -> if (cur < value) cur else value
            }
        storeU16(addr, next)
        return cur
    }

    fun atomicRmwU32(addr: Long, value: UInt, op: Int): UInt {
        val cur = loadU32(addr)
        val next =
            when (op) {
                1 -> cur + value
                2 -> cur - value
                3 -> cur and value
                4 -> (cur and value).inv()
                5 -> cur or value
                6 -> cur xor value
                7 -> if (cur.toInt() > value.toInt()) cur else value
                8 -> if (cur.toInt() < value.toInt()) cur else value
                9 -> if (cur > value) cur else value
                else -> if (cur < value) cur else value
            }
        storeU32(addr, next)
        return cur
    }

    fun atomicRmwU64(addr: Long, value: ULong, op: Int): ULong {
        val cur = loadU64(addr)
        val next =
            when (op) {
                1 -> cur + value
                2 -> cur - value
                3 -> cur and value
                4 -> (cur and value).inv()
                5 -> cur or value
                6 -> cur xor value
                7 -> if (cur.toLong() > value.toLong()) cur else value
                8 -> if (cur.toLong() < value.toLong()) cur else value
                9 -> if (cur > value) cur else value
                else -> if (cur < value) cur else value
            }
        storeU64(addr, next)
        return cur
    }

    fun ctpopU64(value: ULong): UInt = value.countOneBits().toUInt()
    fun ctlzU64(value: ULong): UInt = value.countLeadingZeroBits().toUInt()
    fun cttzU64(value: ULong): UInt = value.countTrailingZeroBits().toUInt()
    fun bswapU16(value: UShort): UShort = (((value.toInt() and 0xFF) shl 8) or ((value.toInt() ushr 8) and 0xFF)).toUShort()
    fun bswapU32(value: UInt): UInt =
        ((value and 0x000000FFu) shl 24) or
            ((value and 0x0000FF00u) shl 8) or
            ((value and 0x00FF0000u) shr 8) or
            ((value and 0xFF000000u) shr 24)
    fun bswapU64(value: ULong): ULong =
        (bswapU32((value and 0xFFFFFFFFuL).toUInt()).toULong() shl 32) or
            bswapU32((value shr 32).toUInt()).toULong()
    fun bitreverseU64(value: ULong): ULong {
        var v = value
        v = ((v shr 1) and 0x5555555555555555uL) or ((v and 0x5555555555555555uL) shl 1)
        v = ((v shr 2) and 0x3333333333333333uL) or ((v and 0x3333333333333333uL) shl 2)
        v = ((v shr 4) and 0x0F0F0F0F0F0F0F0FuL) or ((v and 0x0F0F0F0F0F0F0F0FuL) shl 4)
        return bswapU64(v)
    }
    fun fmaf32(a: Float, b: Float, c: Float): Float = (a.toDouble() * b.toDouble() + c.toDouble()).toFloat()
    fun fmaf64(a: Double, b: Double, c: Double): Double = a * b + c
    fun sqrtf32(x: Float): Float = sqrt(x)
    fun sqrtf64(x: Double): Double = sqrt(x)

    fun umulhU64(a: ULong, b: ULong): ULong {
        val aLo = a and 0xFFFFFFFFuL
        val aHi = a shr 32
        val bLo = b and 0xFFFFFFFFuL
        val bHi = b shr 32
        val p0 = aLo * bLo
        val p1 = aLo * bHi
        val p2 = aHi * bLo
        val p3 = aHi * bHi
        val mid = (p0 shr 32) + (p1 and 0xFFFFFFFFuL) + (p2 and 0xFFFFFFFFuL)
        return p3 + (p1 shr 32) + (p2 shr 32) + (mid shr 32)
    }

    fun smulhI64(a: Long, b: Long): Long {
        var hi = umulhU64(a.toULong(), b.toULong())
        if (a < 0L) hi -= b.toULong()
        if (b < 0L) hi -= a.toULong()
        return hi.toLong()
    }

    fun simdFmaF32x4(dstAddr: Long, aAddr: Long, bAddr: Long, cAddr: Long) {
        for (lane in 0 until 4) {
            val off = lane.toLong() * 4L
            storeF32(dstAddr + off, fmaf32(loadF32(aAddr + off), loadF32(bAddr + off), loadF32(cAddr + off)))
        }
    }

    fun simdDotF32(aAddr: Long, bAddr: Long, len: Long): Float {
        var sum = 0.0f
        var i = 0L
        while (i < len) {
            val off = i * 4L
            sum = fmaf32(loadF32(aAddr + off), loadF32(bAddr + off), sum)
            i += 1L
        }
        return sum
    }
}
""".trimStart()

    public fun renderKmCoreNapiSharedCppHelpers(): String =
        """
static thread_local int32_t g_kmCoreOutScratch[4] = { 0, 0, 0, 0 };

static inline int32_t kmCoreNapiArgI32(const Napi::Value& v) {
    return v.As<Napi::Number>().Int32Value();
}

static inline uint32_t kmCoreNapiArgU32(const Napi::Value& v) {
    return static_cast<uint32_t>(v.As<Napi::Number>().Int32Value());
}

static inline uint64_t kmCoreNapiArgU64Pair(const Napi::CallbackInfo& info, size_t loIdx) {
    uint32_t lo = static_cast<uint32_t>(info[loIdx].As<Napi::Number>().Int32Value());
    uint32_t hi = static_cast<uint32_t>(info[loIdx + 1].As<Napi::Number>().Int32Value());
    return (static_cast<uint64_t>(hi) << 32) | static_cast<uint64_t>(lo);
}

static inline int64_t kmCoreNapiArgI64Pair(const Napi::CallbackInfo& info, size_t loIdx) {
    return static_cast<int64_t>(kmCoreNapiArgU64Pair(info, loIdx));
}

static inline void kmCoreStoreU64Scratch(int scratchIndex, uint64_t value) {
    g_kmCoreOutScratch[scratchIndex] = static_cast<int32_t>(static_cast<uint32_t>(value & 0xFFFFFFFFULL));
    g_kmCoreOutScratch[scratchIndex + 1] = static_cast<int32_t>(static_cast<uint32_t>(value >> 32));
}

static inline Napi::Value kmCoreReturnU64LoHi(const Napi::CallbackInfo& info, uint64_t value) {
    kmCoreStoreU64Scratch(0, value);
    return Napi::Number::New(info.Env(), g_kmCoreOutScratch[0]);
}

static inline Napi::Value kmCoreReturnI64LoHi(const Napi::CallbackInfo& info, int64_t value) {
    return kmCoreReturnU64LoHi(info, static_cast<uint64_t>(value));
}

static inline void kmCoreCpuSpinHint(void) {
#if defined(__aarch64__)
    __asm__ __volatile__("yield" ::: "memory");
#elif defined(__x86_64__) || defined(_M_X64)
#if defined(_MSC_VER)
    _mm_pause();
#else
    __asm__ __volatile__("pause" ::: "memory");
#endif
#endif
}

static inline uint64_t kmCoreCpuBlackBoxU64(uint64_t v) {
#if defined(__clang__) || defined(__GNUC__)
    __asm__ __volatile__("" : "+r"(v) :: "memory");
    return v;
#else
    volatile uint64_t sink = v;
    return sink;
#endif
}

static inline uint64_t kmCoreCpuUmulhU64(uint64_t a, uint64_t b) {
#if defined(__SIZEOF_INT128__)
    return static_cast<uint64_t>((static_cast<unsigned __int128>(a) * static_cast<unsigned __int128>(b)) >> 64);
#elif defined(_MSC_VER) && defined(_M_X64)
    uint64_t hi = 0;
    (void)_umul128(a, b, &hi);
    return hi;
#else
    uint64_t aLo = a & 0xFFFFFFFFULL, aHi = a >> 32;
    uint64_t bLo = b & 0xFFFFFFFFULL, bHi = b >> 32;
    uint64_t p0 = aLo * bLo, p1 = aLo * bHi, p2 = aHi * bLo, p3 = aHi * bHi;
    uint64_t mid = (p0 >> 32) + (p1 & 0xFFFFFFFFULL) + (p2 & 0xFFFFFFFFULL);
    return p3 + (p1 >> 32) + (p2 >> 32) + (mid >> 32);
#endif
}

static inline int64_t kmCoreCpuSmulhI64(int64_t a, int64_t b) {
#if defined(__SIZEOF_INT128__)
    return static_cast<int64_t>((static_cast<__int128>(a) * static_cast<__int128>(b)) >> 64);
#elif defined(_MSC_VER) && defined(_M_X64)
    int64_t hi = 0;
    (void)_mul128(a, b, &hi);
    return hi;
#else
    uint64_t hi = kmCoreCpuUmulhU64(static_cast<uint64_t>(a), static_cast<uint64_t>(b));
    if (a < 0) hi -= static_cast<uint64_t>(b);
    if (b < 0) hi -= static_cast<uint64_t>(a);
    return static_cast<int64_t>(hi);
#endif
}

static inline uint8_t kmCoreCpuAtomicCxchgU128(
    int64_t addr, uint64_t expLo, uint64_t expHi, uint64_t desLo, uint64_t desHi, uint64_t* outLo, uint64_t* outHi
) {
#if defined(_MSC_VER) && defined(_M_X64)
    int64_t cmp[2] = { static_cast<int64_t>(expLo), static_cast<int64_t>(expHi) };
    unsigned char ok = _InterlockedCompareExchange128(
        reinterpret_cast<volatile int64_t*>(static_cast<uintptr_t>(addr)),
        static_cast<int64_t>(desHi),
        static_cast<int64_t>(desLo),
        cmp
    );
    *outLo = static_cast<uint64_t>(cmp[0]);
    *outHi = static_cast<uint64_t>(cmp[1]);
    return ok ? 1 : 0;
#elif defined(__aarch64__) && (defined(__clang__) || defined(__GNUC__))
    uint64_t curLo = 0, curHi = 0;
    uint32_t status = 0;
    void* ptr = reinterpret_cast<void*>(static_cast<uintptr_t>(addr));
    for (;;) {
        __asm__ __volatile__("ldaxp %0, %1, [%2]" : "=&r"(curLo), "=&r"(curHi) : "r"(ptr) : "memory");
        if (curLo != expLo || curHi != expHi) {
            __asm__ __volatile__("clrex" ::: "memory");
            *outLo = curLo;
            *outHi = curHi;
            return 0;
        }
        __asm__ __volatile__("stlxp %w0, %1, %2, [%3]" : "=&r"(status) : "r"(desLo), "r"(desHi), "r"(ptr) : "memory");
        if (status == 0) {
            *outLo = expLo;
            *outHi = expHi;
            return 1;
        }
    }
#elif defined(__x86_64__) && (defined(__clang__) || defined(__GNUC__))
    uint8_t ok = 0;
    uint64_t prevLo = expLo;
    uint64_t prevHi = expHi;
    __uint128_t* memPtr = reinterpret_cast<__uint128_t*>(static_cast<uintptr_t>(addr));
    __asm__ __volatile__(
        "lock cmpxchg16b %1\n\t"
        "sete %0"
        : "=q"(ok), "+m"(*memPtr), "+a"(prevLo), "+d"(prevHi)
        : "b"(desLo), "c"(desHi)
        : "cc", "memory"
    );
    *outLo = prevLo;
    *outHi = prevHi;
    return ok;
#else
    uint64_t* p = reinterpret_cast<uint64_t*>(static_cast<uintptr_t>(addr));
    *outLo = p[0];
    *outHi = p[1];
    if (p[0] == expLo && p[1] == expHi) {
        p[0] = desLo;
        p[1] = desHi;
        return 1;
    }
    return 0;
#endif
}

static void kmRegisterCoreNapiV2Object(Napi::Env env) {
    Napi::Object global = env.Global();
    if (global.Has("kmCoreNapiV2") && global.Get("kmCoreNapiV2").IsObject()) {
        return;
    }
    Napi::Object coreObj = Napi::Object::New(env);
    coreObj.Set("kmOut0", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(info.Env(), g_kmCoreOutScratch[0]);
    }));
    coreObj.Set("kmOut1", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(info.Env(), g_kmCoreOutScratch[1]);
    }));
    coreObj.Set("kmOut2", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(info.Env(), g_kmCoreOutScratch[2]);
    }));
    coreObj.Set("kmOut3", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(info.Env(), g_kmCoreOutScratch[3]);
    }));

    coreObj.Set("kmLoadU8", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(info.Env(), static_cast<int32_t>(kmLoadU8(kmCoreNapiArgI64Pair(info, 0))));
    }));
    coreObj.Set("kmLoadU16", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(info.Env(), static_cast<int32_t>(kmLoadU16(kmCoreNapiArgI64Pair(info, 0))));
    }));
    coreObj.Set("kmLoadU32", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(info.Env(), static_cast<int32_t>(kmLoadU32(kmCoreNapiArgI64Pair(info, 0))));
    }));
    coreObj.Set("kmLoadU64", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return kmCoreReturnU64LoHi(info, kmLoadU64(kmCoreNapiArgI64Pair(info, 0)));
    }));
    coreObj.Set("kmLoadF32", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(info.Env(), static_cast<double>(kmLoadF32(kmCoreNapiArgI64Pair(info, 0))));
    }));
    coreObj.Set("kmLoadF64", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(info.Env(), kmLoadF64(kmCoreNapiArgI64Pair(info, 0)));
    }));

    coreObj.Set("kmStoreU8", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        kmStoreU8(kmCoreNapiArgI64Pair(info, 0), static_cast<uint8_t>(kmCoreNapiArgU32(info[2])));
        return info.Env().Undefined();
    }));
    coreObj.Set("kmStoreU16", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        kmStoreU16(kmCoreNapiArgI64Pair(info, 0), static_cast<uint16_t>(kmCoreNapiArgU32(info[2])));
        return info.Env().Undefined();
    }));
    coreObj.Set("kmStoreU32", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        kmStoreU32(kmCoreNapiArgI64Pair(info, 0), kmCoreNapiArgU32(info[2]));
        return info.Env().Undefined();
    }));
    coreObj.Set("kmStoreU64", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        kmStoreU64(kmCoreNapiArgI64Pair(info, 0), kmCoreNapiArgU64Pair(info, 2));
        return info.Env().Undefined();
    }));
    coreObj.Set("kmStoreF32", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        kmStoreF32(kmCoreNapiArgI64Pair(info, 0), static_cast<float>(info[2].As<Napi::Number>().DoubleValue()));
        return info.Env().Undefined();
    }));
    coreObj.Set("kmStoreF64", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        kmStoreF64(kmCoreNapiArgI64Pair(info, 0), info[2].As<Napi::Number>().DoubleValue());
        return info.Env().Undefined();
    }));

    coreObj.Set("kmVolatileLoadU8", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(info.Env(), static_cast<int32_t>(kmVolatileLoadU8(kmCoreNapiArgI64Pair(info, 0))));
    }));
    coreObj.Set("kmVolatileLoadU16", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(info.Env(), static_cast<int32_t>(kmVolatileLoadU16(kmCoreNapiArgI64Pair(info, 0))));
    }));
    coreObj.Set("kmVolatileLoadU32", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(info.Env(), static_cast<int32_t>(kmVolatileLoadU32(kmCoreNapiArgI64Pair(info, 0))));
    }));
    coreObj.Set("kmVolatileLoadU64", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return kmCoreReturnU64LoHi(info, kmVolatileLoadU64(kmCoreNapiArgI64Pair(info, 0)));
    }));

    coreObj.Set("kmVolatileStoreU8", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        kmVolatileStoreU8(kmCoreNapiArgI64Pair(info, 0), static_cast<uint8_t>(kmCoreNapiArgU32(info[2])));
        return info.Env().Undefined();
    }));
    coreObj.Set("kmVolatileStoreU16", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        kmVolatileStoreU16(kmCoreNapiArgI64Pair(info, 0), static_cast<uint16_t>(kmCoreNapiArgU32(info[2])));
        return info.Env().Undefined();
    }));
    coreObj.Set("kmVolatileStoreU32", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        kmVolatileStoreU32(kmCoreNapiArgI64Pair(info, 0), kmCoreNapiArgU32(info[2]));
        return info.Env().Undefined();
    }));
    coreObj.Set("kmVolatileStoreU64", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        kmVolatileStoreU64(kmCoreNapiArgI64Pair(info, 0), kmCoreNapiArgU64Pair(info, 2));
        return info.Env().Undefined();
    }));

    coreObj.Set("kmCopyNonoverlapping", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        kmCopyNonoverlapping(kmCoreNapiArgI64Pair(info, 0), kmCoreNapiArgI64Pair(info, 2), kmCoreNapiArgI64Pair(info, 4));
        return info.Env().Undefined();
    }));
    coreObj.Set("kmCopy", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        kmCopy(kmCoreNapiArgI64Pair(info, 0), kmCoreNapiArgI64Pair(info, 2), kmCoreNapiArgI64Pair(info, 4));
        return info.Env().Undefined();
    }));
    coreObj.Set("kmWriteBytes", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        kmWriteBytes(kmCoreNapiArgI64Pair(info, 0), static_cast<uint8_t>(kmCoreNapiArgU32(info[2])), kmCoreNapiArgI64Pair(info, 3));
        return info.Env().Undefined();
    }));
    coreObj.Set("kmCompareBytes", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(
            info.Env(),
            kmCompareBytes(kmCoreNapiArgI64Pair(info, 0), kmCoreNapiArgI64Pair(info, 2), kmCoreNapiArgI64Pair(info, 4))
        );
    }));

    coreObj.Set("kmAlloc", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return kmCoreReturnI64LoHi(info, kmAlloc(kmCoreNapiArgI64Pair(info, 0), kmCoreNapiArgI64Pair(info, 2)));
    }));
    coreObj.Set("kmAllocZeroed", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return kmCoreReturnI64LoHi(info, kmAllocZeroed(kmCoreNapiArgI64Pair(info, 0), kmCoreNapiArgI64Pair(info, 2)));
    }));
    coreObj.Set("kmRealloc", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return kmCoreReturnI64LoHi(
            info,
            kmRealloc(
                kmCoreNapiArgI64Pair(info, 0),
                kmCoreNapiArgI64Pair(info, 2),
                kmCoreNapiArgI64Pair(info, 4),
                kmCoreNapiArgI64Pair(info, 6)
            )
        );
    }));
    coreObj.Set("kmDealloc", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        kmDealloc(kmCoreNapiArgI64Pair(info, 0), kmCoreNapiArgI64Pair(info, 2), kmCoreNapiArgI64Pair(info, 4));
        return info.Env().Undefined();
    }));

    coreObj.Set("kmAtomicFence", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        kmAtomicFence(kmCoreNapiArgI32(info[0]));
        return info.Env().Undefined();
    }));
    coreObj.Set("kmAtomicSignalFence", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        kmAtomicSignalFence(kmCoreNapiArgI32(info[0]));
        return info.Env().Undefined();
    }));

    coreObj.Set("kmAtomicLoadU8", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(info.Env(), static_cast<int32_t>(kmAtomicLoadU8(kmCoreNapiArgI64Pair(info, 0), kmCoreNapiArgI32(info[2]))));
    }));
    coreObj.Set("kmAtomicLoadU16", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(info.Env(), static_cast<int32_t>(kmAtomicLoadU16(kmCoreNapiArgI64Pair(info, 0), kmCoreNapiArgI32(info[2]))));
    }));
    coreObj.Set("kmAtomicLoadU32", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(info.Env(), static_cast<int32_t>(kmAtomicLoadU32(kmCoreNapiArgI64Pair(info, 0), kmCoreNapiArgI32(info[2]))));
    }));
    coreObj.Set("kmAtomicLoadU64", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return kmCoreReturnU64LoHi(info, kmAtomicLoadU64(kmCoreNapiArgI64Pair(info, 0), kmCoreNapiArgI32(info[2])));
    }));

    coreObj.Set("kmAtomicStoreU8", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        kmAtomicStoreU8(kmCoreNapiArgI64Pair(info, 0), static_cast<uint8_t>(kmCoreNapiArgU32(info[2])), kmCoreNapiArgI32(info[3]));
        return info.Env().Undefined();
    }));
    coreObj.Set("kmAtomicStoreU16", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        kmAtomicStoreU16(kmCoreNapiArgI64Pair(info, 0), static_cast<uint16_t>(kmCoreNapiArgU32(info[2])), kmCoreNapiArgI32(info[3]));
        return info.Env().Undefined();
    }));
    coreObj.Set("kmAtomicStoreU32", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        kmAtomicStoreU32(kmCoreNapiArgI64Pair(info, 0), kmCoreNapiArgU32(info[2]), kmCoreNapiArgI32(info[3]));
        return info.Env().Undefined();
    }));
    coreObj.Set("kmAtomicStoreU64", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        kmAtomicStoreU64(kmCoreNapiArgI64Pair(info, 0), kmCoreNapiArgU64Pair(info, 2), kmCoreNapiArgI32(info[4]));
        return info.Env().Undefined();
    }));

    coreObj.Set("kmAtomicXchgU8", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(
            info.Env(),
            static_cast<int32_t>(kmAtomicXchgU8(kmCoreNapiArgI64Pair(info, 0), static_cast<uint8_t>(kmCoreNapiArgU32(info[2])), kmCoreNapiArgI32(info[3])))
        );
    }));
    coreObj.Set("kmAtomicXchgU16", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(
            info.Env(),
            static_cast<int32_t>(kmAtomicXchgU16(kmCoreNapiArgI64Pair(info, 0), static_cast<uint16_t>(kmCoreNapiArgU32(info[2])), kmCoreNapiArgI32(info[3])))
        );
    }));
    coreObj.Set("kmAtomicXchgU32", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(
            info.Env(),
            static_cast<int32_t>(kmAtomicXchgU32(kmCoreNapiArgI64Pair(info, 0), kmCoreNapiArgU32(info[2]), kmCoreNapiArgI32(info[3])))
        );
    }));
    coreObj.Set("kmAtomicXchgU64", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return kmCoreReturnU64LoHi(
            info,
            kmAtomicXchgU64(kmCoreNapiArgI64Pair(info, 0), kmCoreNapiArgU64Pair(info, 2), kmCoreNapiArgI32(info[4]))
        );
    }));

    coreObj.Set("kmAtomicCxchgU8", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        uint8_t ok = 0;
        uint8_t prev = kmAtomicCxchgU8(
            kmCoreNapiArgI64Pair(info, 0),
            static_cast<uint8_t>(kmCoreNapiArgU32(info[2])),
            static_cast<uint8_t>(kmCoreNapiArgU32(info[3])),
            kmCoreNapiArgI32(info[4]),
            kmCoreNapiArgI32(info[5]),
            &ok
        );
        return Napi::Number::New(info.Env(), static_cast<int32_t>((static_cast<uint32_t>(ok) << 8) | static_cast<uint32_t>(prev)));
    }));
    coreObj.Set("kmAtomicCxchgU16", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        uint8_t ok = 0;
        uint16_t prev = kmAtomicCxchgU16(
            kmCoreNapiArgI64Pair(info, 0),
            static_cast<uint16_t>(kmCoreNapiArgU32(info[2])),
            static_cast<uint16_t>(kmCoreNapiArgU32(info[3])),
            kmCoreNapiArgI32(info[4]),
            kmCoreNapiArgI32(info[5]),
            &ok
        );
        return Napi::Number::New(info.Env(), static_cast<int32_t>((static_cast<uint32_t>(ok) << 16) | static_cast<uint32_t>(prev)));
    }));
    coreObj.Set("kmAtomicCxchgU32", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        uint8_t ok = 0;
        uint32_t prev = kmAtomicCxchgU32(
            kmCoreNapiArgI64Pair(info, 0),
            kmCoreNapiArgU32(info[2]),
            kmCoreNapiArgU32(info[3]),
            kmCoreNapiArgI32(info[4]),
            kmCoreNapiArgI32(info[5]),
            &ok
        );
        g_kmCoreOutScratch[0] = static_cast<int32_t>(prev);
        return Napi::Number::New(info.Env(), static_cast<int32_t>(ok));
    }));
    coreObj.Set("kmAtomicCxchgU64", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        uint8_t ok = 0;
        uint64_t prev = kmAtomicCxchgU64(
            kmCoreNapiArgI64Pair(info, 0),
            kmCoreNapiArgU64Pair(info, 2),
            kmCoreNapiArgU64Pair(info, 4),
            kmCoreNapiArgI32(info[6]),
            kmCoreNapiArgI32(info[7]),
            &ok
        );
        kmCoreStoreU64Scratch(0, prev);
        return Napi::Number::New(info.Env(), static_cast<int32_t>(ok));
    }));

    coreObj.Set("kmAtomicRmwU8", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(
            info.Env(),
            static_cast<int32_t>(kmAtomicRmwU8(kmCoreNapiArgI64Pair(info, 0), static_cast<uint8_t>(kmCoreNapiArgU32(info[2])), kmCoreNapiArgI32(info[3]), kmCoreNapiArgI32(info[4])))
        );
    }));
    coreObj.Set("kmAtomicRmwU16", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(
            info.Env(),
            static_cast<int32_t>(kmAtomicRmwU16(kmCoreNapiArgI64Pair(info, 0), static_cast<uint16_t>(kmCoreNapiArgU32(info[2])), kmCoreNapiArgI32(info[3]), kmCoreNapiArgI32(info[4])))
        );
    }));
    coreObj.Set("kmAtomicRmwU32", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(
            info.Env(),
            static_cast<int32_t>(kmAtomicRmwU32(kmCoreNapiArgI64Pair(info, 0), kmCoreNapiArgU32(info[2]), kmCoreNapiArgI32(info[3]), kmCoreNapiArgI32(info[4])))
        );
    }));
    coreObj.Set("kmAtomicRmwU64", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return kmCoreReturnU64LoHi(
            info,
            kmAtomicRmwU64(kmCoreNapiArgI64Pair(info, 0), kmCoreNapiArgU64Pair(info, 2), kmCoreNapiArgI32(info[4]), kmCoreNapiArgI32(info[5]))
        );
    }));

    coreObj.Set("kmCtpopU64", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(info.Env(), static_cast<int32_t>(kmCtpopU64(kmCoreNapiArgU64Pair(info, 0))));
    }));
    coreObj.Set("kmCtlzU64", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(info.Env(), static_cast<int32_t>(kmCtlzU64(kmCoreNapiArgU64Pair(info, 0))));
    }));
    coreObj.Set("kmCttzU64", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(info.Env(), static_cast<int32_t>(kmCttzU64(kmCoreNapiArgU64Pair(info, 0))));
    }));
    coreObj.Set("kmBswapU16", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(info.Env(), static_cast<int32_t>(kmBswapU16(static_cast<uint16_t>(kmCoreNapiArgU32(info[0])))));
    }));
    coreObj.Set("kmBswapU32", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(info.Env(), static_cast<int32_t>(kmBswapU32(kmCoreNapiArgU32(info[0]))));
    }));
    coreObj.Set("kmBswapU64", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return kmCoreReturnU64LoHi(info, kmBswapU64(kmCoreNapiArgU64Pair(info, 0)));
    }));
    coreObj.Set("kmBitreverseU64", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return kmCoreReturnU64LoHi(info, kmBitreverseU64(kmCoreNapiArgU64Pair(info, 0)));
    }));

    coreObj.Set("kmFmaf32", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(
            info.Env(),
            static_cast<double>(kmFmaf32(
                static_cast<float>(info[0].As<Napi::Number>().DoubleValue()),
                static_cast<float>(info[1].As<Napi::Number>().DoubleValue()),
                static_cast<float>(info[2].As<Napi::Number>().DoubleValue())
            ))
        );
    }));
    coreObj.Set("kmFmaf64", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(
            info.Env(),
            kmFmaf64(
                info[0].As<Napi::Number>().DoubleValue(),
                info[1].As<Napi::Number>().DoubleValue(),
                info[2].As<Napi::Number>().DoubleValue()
            )
        );
    }));
    coreObj.Set("kmSqrtf32", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(info.Env(), static_cast<double>(kmSqrtf32(static_cast<float>(info[0].As<Napi::Number>().DoubleValue()))));
    }));
    coreObj.Set("kmSqrtf64", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(info.Env(), kmSqrtf64(info[0].As<Napi::Number>().DoubleValue()));
    }));
    coreObj.Set("kmAbort", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        (void)info;
        kmAbort();
        return info.Env().Undefined();
    }));
    coreObj.Set("kmSpinLoopHint", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        kmCoreCpuSpinHint();
        return info.Env().Undefined();
    }));
    coreObj.Set("kmBlackBoxU64", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return kmCoreReturnU64LoHi(info, kmCoreCpuBlackBoxU64(kmCoreNapiArgU64Pair(info, 0)));
    }));
    coreObj.Set("kmUmulhU64", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return kmCoreReturnU64LoHi(info, kmCoreCpuUmulhU64(kmCoreNapiArgU64Pair(info, 0), kmCoreNapiArgU64Pair(info, 2)));
    }));
    coreObj.Set("kmSmulhI64", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return kmCoreReturnI64LoHi(info, kmCoreCpuSmulhI64(kmCoreNapiArgI64Pair(info, 0), kmCoreNapiArgI64Pair(info, 2)));
    }));
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
    coreObj.Set("kmSimdFmaF32x4", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        kmSimdFmaF32x4(
            kmCoreNapiArgI64Pair(info, 0),
            kmCoreNapiArgI64Pair(info, 2),
            kmCoreNapiArgI64Pair(info, 4),
            kmCoreNapiArgI64Pair(info, 6)
        );
        return info.Env().Undefined();
    }));
    coreObj.Set("kmSimdDotF32", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
        return Napi::Number::New(
            info.Env(),
            static_cast<double>(kmSimdDotF32(
                kmCoreNapiArgI64Pair(info, 0),
                kmCoreNapiArgI64Pair(info, 2),
                kmCoreNapiArgI64Pair(info, 4)
            ))
        );
    }));
    global.Set("kmCoreNapiV2", coreObj);
}
""".trimStart()

    public fun renderKmCoreNapiCpp(stemSnake: String = "km_core"): String =
        """
#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <napi.h>
#include <cstdint>
#include <cstddef>
#include <cstring>
#if defined(_MSC_VER)
#include <intrin.h>
#elif defined(__x86_64__)
#include <immintrin.h>
#endif
#if __has_include("km_core_intrinsics_inline.h")
#include "km_core_intrinsics_inline.h"
#elif __has_include("../../../native/interop/cinterop/km_core_intrinsics_inline.h")
#include "../../../native/interop/cinterop/km_core_intrinsics_inline.h"
#endif

struct KmNapiRegistrarNode {
    void (*register_fn)(Napi::Env, Napi::Object);
    KmNapiRegistrarNode* next;
};
#if defined(__GNUC__) || defined(__clang__)
__attribute__((visibility("hidden")))
#endif
extern KmNapiRegistrarNode* km_napi_registrar_head;

namespace {

${renderKmCoreNapiSharedCppHelpers()}

static void register_${stemSnake}_napi(Napi::Env env, Napi::Object exports) {
    kmRegisterCoreNapiV2Object(env);
    Napi::Value coreVal = env.Global().Get("kmCoreNapiV2");
    if (coreVal.IsObject()) {
        Napi::Object coreObj = coreVal.As<Napi::Object>();
        Napi::Array keys = coreObj.GetPropertyNames();
        for (uint32_t i = 0; i < keys.Length(); i++) {
            Napi::Value k = keys.Get(i);
            exports.Set(k, coreObj.Get(k));
        }
    }
}

static KmNapiRegistrarNode registrar_node = { &register_${stemSnake}_napi, nullptr };
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
""".trimStart()

    private fun renderNodeKmCoreLoaderJsBody(ffiModName: String): String =
        "if (typeof globalThis !== 'undefined' && typeof globalThis.kmCoreNapiV2 !== 'undefined') return globalThis.kmCoreNapiV2; " +
            "if (typeof process === 'undefined' || !process.versions || !process.versions.node) { " +
            "if (typeof globalThis !== 'undefined') globalThis.kmCoreNapiV2 = null; return null; } " +
            "var mod = process.getBuiltinModule ? process.getBuiltinModule('module') : (process.mainModule && process.mainModule.constructor); " +
            "var req = (mod && mod.createRequire) ? mod.createRequire((process.cwd ? process.cwd() : '.') + '/package.json') : (process.mainModule ? process.mainModule.require : null); " +
            "if (!req) { if (typeof globalThis !== 'undefined') globalThis.kmCoreNapiV2 = null; return null; } " +
            "var fs = req('fs'); " +
            "var path = req('path'); " +
            "var cwd = process.cwd ? process.cwd() : '.'; " +
            "var dir = cwd; " +
            "for (var d = 0; d < 8; d++) { " +
            "var directCands = [ " +
            "path.resolve(dir, 'node_modules/@kotlinmania/$ffiModName-native-bindings/index.js'), " +
            "path.resolve(dir, 'build/js/node_modules/@kotlinmania/$ffiModName-native-bindings/index.js'), " +
            "path.resolve(dir, 'build/wasm/node_modules/@kotlinmania/$ffiModName-native-bindings/index.js'), " +
            "path.resolve(dir, 'src/js/node-$ffiModName/dist/build/Release/${ffiModName}_native.node'), " +
            "path.resolve(dir, 'src/js/node-$ffiModName/build/Release/${ffiModName}_native.node') " +
            "]; " +
            "for (var i = 0; i < directCands.length; i++) { " +
            "if (fs.existsSync(directCands[i])) { req(directCands[i]); if (globalThis.kmCoreNapiV2) return globalThis.kmCoreNapiV2; } " +
            "} " +
            "var scopeDirs = [ " +
            "path.resolve(dir, 'node_modules/@kotlinmania'), " +
            "path.resolve(dir, 'build/js/node_modules/@kotlinmania'), " +
            "path.resolve(dir, 'build/wasm/node_modules/@kotlinmania') " +
            "]; " +
            "for (var s = 0; s < scopeDirs.length; s++) { " +
            "if (fs.existsSync(scopeDirs[s])) { " +
            "var entries = fs.readdirSync(scopeDirs[s]); " +
            "for (var e = 0; e < entries.length; e++) { " +
            "if (entries[e].endsWith('-native-bindings')) { " +
            "var pkgEntry = path.resolve(scopeDirs[s], entries[e], 'index.js'); " +
            "if (fs.existsSync(pkgEntry)) { req(pkgEntry); if (globalThis.kmCoreNapiV2) return globalThis.kmCoreNapiV2; } " +
            "} " +
            "} " +
            "} " +
            "} " +
            "var parent = path.dirname(dir); " +
            "if (parent === dir) break; " +
            "dir = parent; " +
            "} " +
            "if (typeof globalThis !== 'undefined') globalThis.kmCoreNapiV2 = null; " +
            "return null;"

    public fun renderKmCoreJsActualsKotlin(
        coreNamespace: String,
        ffiModName: String = "kmcore",
    ): String {
        val loaderBody = renderNodeKmCoreLoaderJsBody(ffiModName)
        return """
@file:OptIn(ExperimentalUnsignedTypes::class)

package $coreNamespace.intrinsics

private val kmCoreNapiAddon: dynamic =
    js("(function() { $loaderBody })()")

private fun lo32(v: Long): Int = v.toInt()
private fun hi32(v: Long): Int = (v ushr 32).toInt()
private fun uLo32(v: ULong): Int = v.toInt()
private fun uHi32(v: ULong): Int = (v shr 32).toInt()
private fun packI64(lo: Int, hi: Int): Long = (hi.toLong() shl 32) or (lo.toLong() and 0xFFFF_FFFFL)
private fun packU64(lo: Int, hi: Int): ULong =
    ((hi.toLong().toULong() and 0xFFFF_FFFFuL) shl 32) or (lo.toLong().toULong() and 0xFFFF_FFFFuL)

public actual fun kmLoadU8(addr: Long): UByte =
    if (kmCoreNapiAddon != null) (kmCoreNapiAddon.kmLoadU8(lo32(addr), hi32(addr)) as Int).toUByte() else KmLinearMemoryArena.loadU8(addr)
public actual fun kmLoadU16(addr: Long): UShort =
    if (kmCoreNapiAddon != null) (kmCoreNapiAddon.kmLoadU16(lo32(addr), hi32(addr)) as Int).toUShort() else KmLinearMemoryArena.loadU16(addr)
public actual fun kmLoadU32(addr: Long): UInt =
    if (kmCoreNapiAddon != null) (kmCoreNapiAddon.kmLoadU32(lo32(addr), hi32(addr)) as Int).toUInt() else KmLinearMemoryArena.loadU32(addr)
public actual fun kmLoadU64(addr: Long): ULong =
    if (kmCoreNapiAddon != null) {
        val lo = kmCoreNapiAddon.kmLoadU64(lo32(addr), hi32(addr)) as Int
        packU64(lo, kmCoreNapiAddon.kmOut1() as Int)
    } else {
        KmLinearMemoryArena.loadU64(addr)
    }
public actual fun kmLoadF32(addr: Long): Float =
    if (kmCoreNapiAddon != null) (kmCoreNapiAddon.kmLoadF32(lo32(addr), hi32(addr)) as Double).toFloat() else KmLinearMemoryArena.loadF32(addr)
public actual fun kmLoadF64(addr: Long): Double =
    if (kmCoreNapiAddon != null) (kmCoreNapiAddon.kmLoadF64(lo32(addr), hi32(addr)) as Double) else KmLinearMemoryArena.loadF64(addr)

public actual fun kmStoreU8(addr: Long, value: UByte) {
    if (kmCoreNapiAddon != null) kmCoreNapiAddon.kmStoreU8(lo32(addr), hi32(addr), value.toInt()) else KmLinearMemoryArena.storeU8(addr, value)
}
public actual fun kmStoreU16(addr: Long, value: UShort) {
    if (kmCoreNapiAddon != null) kmCoreNapiAddon.kmStoreU16(lo32(addr), hi32(addr), value.toInt()) else KmLinearMemoryArena.storeU16(addr, value)
}
public actual fun kmStoreU32(addr: Long, value: UInt) {
    if (kmCoreNapiAddon != null) kmCoreNapiAddon.kmStoreU32(lo32(addr), hi32(addr), value.toInt()) else KmLinearMemoryArena.storeU32(addr, value)
}
public actual fun kmStoreU64(addr: Long, value: ULong) {
    if (kmCoreNapiAddon != null) kmCoreNapiAddon.kmStoreU64(lo32(addr), hi32(addr), uLo32(value), uHi32(value)) else KmLinearMemoryArena.storeU64(addr, value)
}
public actual fun kmStoreF32(addr: Long, value: Float) {
    if (kmCoreNapiAddon != null) kmCoreNapiAddon.kmStoreF32(lo32(addr), hi32(addr), value.toDouble()) else KmLinearMemoryArena.storeF32(addr, value)
}
public actual fun kmStoreF64(addr: Long, value: Double) {
    if (kmCoreNapiAddon != null) kmCoreNapiAddon.kmStoreF64(lo32(addr), hi32(addr), value) else KmLinearMemoryArena.storeF64(addr, value)
}

public actual fun kmVolatileLoadU8(addr: Long): UByte =
    if (kmCoreNapiAddon != null) (kmCoreNapiAddon.kmVolatileLoadU8(lo32(addr), hi32(addr)) as Int).toUByte() else KmLinearMemoryArena.loadU8(addr)
public actual fun kmVolatileLoadU16(addr: Long): UShort =
    if (kmCoreNapiAddon != null) (kmCoreNapiAddon.kmVolatileLoadU16(lo32(addr), hi32(addr)) as Int).toUShort() else KmLinearMemoryArena.loadU16(addr)
public actual fun kmVolatileLoadU32(addr: Long): UInt =
    if (kmCoreNapiAddon != null) (kmCoreNapiAddon.kmVolatileLoadU32(lo32(addr), hi32(addr)) as Int).toUInt() else KmLinearMemoryArena.loadU32(addr)
public actual fun kmVolatileLoadU64(addr: Long): ULong =
    if (kmCoreNapiAddon != null) {
        val lo = kmCoreNapiAddon.kmVolatileLoadU64(lo32(addr), hi32(addr)) as Int
        packU64(lo, kmCoreNapiAddon.kmOut1() as Int)
    } else {
        KmLinearMemoryArena.loadU64(addr)
    }

public actual fun kmVolatileStoreU8(addr: Long, value: UByte) {
    if (kmCoreNapiAddon != null) kmCoreNapiAddon.kmVolatileStoreU8(lo32(addr), hi32(addr), value.toInt()) else KmLinearMemoryArena.storeU8(addr, value)
}
public actual fun kmVolatileStoreU16(addr: Long, value: UShort) {
    if (kmCoreNapiAddon != null) kmCoreNapiAddon.kmVolatileStoreU16(lo32(addr), hi32(addr), value.toInt()) else KmLinearMemoryArena.storeU16(addr, value)
}
public actual fun kmVolatileStoreU32(addr: Long, value: UInt) {
    if (kmCoreNapiAddon != null) kmCoreNapiAddon.kmVolatileStoreU32(lo32(addr), hi32(addr), value.toInt()) else KmLinearMemoryArena.storeU32(addr, value)
}
public actual fun kmVolatileStoreU64(addr: Long, value: ULong) {
    if (kmCoreNapiAddon != null) kmCoreNapiAddon.kmVolatileStoreU64(lo32(addr), hi32(addr), uLo32(value), uHi32(value)) else KmLinearMemoryArena.storeU64(addr, value)
}

public actual fun kmCopyNonoverlapping(dst: Long, src: Long, bytes: Long) {
    if (kmCoreNapiAddon != null) {
        kmCoreNapiAddon.kmCopyNonoverlapping(lo32(dst), hi32(dst), lo32(src), hi32(src), lo32(bytes), hi32(bytes))
    } else {
        KmLinearMemoryArena.copyNonoverlapping(dst, src, bytes)
    }
}
public actual fun kmCopy(dst: Long, src: Long, bytes: Long) {
    if (kmCoreNapiAddon != null) {
        kmCoreNapiAddon.kmCopy(lo32(dst), hi32(dst), lo32(src), hi32(src), lo32(bytes), hi32(bytes))
    } else {
        KmLinearMemoryArena.copy(dst, src, bytes)
    }
}
public actual fun kmWriteBytes(dst: Long, value: UByte, bytes: Long) {
    if (kmCoreNapiAddon != null) {
        kmCoreNapiAddon.kmWriteBytes(lo32(dst), hi32(dst), value.toInt(), lo32(bytes), hi32(bytes))
    } else {
        KmLinearMemoryArena.writeBytes(dst, value, bytes)
    }
}
public actual fun kmCompareBytes(left: Long, right: Long, bytes: Long): Int =
    if (kmCoreNapiAddon != null) {
        kmCoreNapiAddon.kmCompareBytes(lo32(left), hi32(left), lo32(right), hi32(right), lo32(bytes), hi32(bytes)) as Int
    } else {
        KmLinearMemoryArena.compareBytes(left, right, bytes)
    }

public actual fun kmAlloc(size: Long, align: Long): Long =
    if (kmCoreNapiAddon != null) {
        val lo = kmCoreNapiAddon.kmAlloc(lo32(size), hi32(size), lo32(align), hi32(align)) as Int
        packI64(lo, kmCoreNapiAddon.kmOut1() as Int)
    } else {
        KmLinearMemoryArena.alloc(size, align)
    }
public actual fun kmAllocZeroed(size: Long, align: Long): Long =
    if (kmCoreNapiAddon != null) {
        val lo = kmCoreNapiAddon.kmAllocZeroed(lo32(size), hi32(size), lo32(align), hi32(align)) as Int
        packI64(lo, kmCoreNapiAddon.kmOut1() as Int)
    } else {
        KmLinearMemoryArena.allocZeroed(size, align)
    }
public actual fun kmRealloc(ptr: Long, oldSize: Long, align: Long, newSize: Long): Long =
    if (kmCoreNapiAddon != null) {
        val lo =
            kmCoreNapiAddon.kmRealloc(
                lo32(ptr),
                hi32(ptr),
                lo32(oldSize),
                hi32(oldSize),
                lo32(align),
                hi32(align),
                lo32(newSize),
                hi32(newSize),
            ) as Int
        packI64(lo, kmCoreNapiAddon.kmOut1() as Int)
    } else {
        KmLinearMemoryArena.realloc(ptr, oldSize, align, newSize)
    }
public actual fun kmDealloc(ptr: Long, size: Long, align: Long) {
    if (kmCoreNapiAddon != null) {
        kmCoreNapiAddon.kmDealloc(lo32(ptr), hi32(ptr), lo32(size), hi32(size), lo32(align), hi32(align))
    } else {
        KmLinearMemoryArena.dealloc(ptr, size, align)
    }
}

public actual fun kmAtomicFence(ord: Int) {
    if (kmCoreNapiAddon != null) kmCoreNapiAddon.kmAtomicFence(ord)
}
public actual fun kmAtomicSignalFence(ord: Int) {
    if (kmCoreNapiAddon != null) kmCoreNapiAddon.kmAtomicSignalFence(ord)
}
public actual fun kmAtomicLoadU8(addr: Long, ord: Int): UByte =
    if (kmCoreNapiAddon != null) (kmCoreNapiAddon.kmAtomicLoadU8(lo32(addr), hi32(addr), ord) as Int).toUByte() else KmLinearMemoryArena.loadU8(addr)
public actual fun kmAtomicLoadU16(addr: Long, ord: Int): UShort =
    if (kmCoreNapiAddon != null) (kmCoreNapiAddon.kmAtomicLoadU16(lo32(addr), hi32(addr), ord) as Int).toUShort() else KmLinearMemoryArena.loadU16(addr)
public actual fun kmAtomicLoadU32(addr: Long, ord: Int): UInt =
    if (kmCoreNapiAddon != null) (kmCoreNapiAddon.kmAtomicLoadU32(lo32(addr), hi32(addr), ord) as Int).toUInt() else KmLinearMemoryArena.loadU32(addr)
public actual fun kmAtomicLoadU64(addr: Long, ord: Int): ULong =
    if (kmCoreNapiAddon != null) {
        val lo = kmCoreNapiAddon.kmAtomicLoadU64(lo32(addr), hi32(addr), ord) as Int
        packU64(lo, kmCoreNapiAddon.kmOut1() as Int)
    } else {
        KmLinearMemoryArena.loadU64(addr)
    }

public actual fun kmAtomicStoreU8(addr: Long, value: UByte, ord: Int) {
    if (kmCoreNapiAddon != null) kmCoreNapiAddon.kmAtomicStoreU8(lo32(addr), hi32(addr), value.toInt(), ord) else KmLinearMemoryArena.storeU8(addr, value)
}
public actual fun kmAtomicStoreU16(addr: Long, value: UShort, ord: Int) {
    if (kmCoreNapiAddon != null) kmCoreNapiAddon.kmAtomicStoreU16(lo32(addr), hi32(addr), value.toInt(), ord) else KmLinearMemoryArena.storeU16(addr, value)
}
public actual fun kmAtomicStoreU32(addr: Long, value: UInt, ord: Int) {
    if (kmCoreNapiAddon != null) kmCoreNapiAddon.kmAtomicStoreU32(lo32(addr), hi32(addr), value.toInt(), ord) else KmLinearMemoryArena.storeU32(addr, value)
}
public actual fun kmAtomicStoreU64(addr: Long, value: ULong, ord: Int) {
    if (kmCoreNapiAddon != null) kmCoreNapiAddon.kmAtomicStoreU64(lo32(addr), hi32(addr), uLo32(value), uHi32(value), ord) else KmLinearMemoryArena.storeU64(addr, value)
}

public actual fun kmAtomicXchgU8(addr: Long, value: UByte, ord: Int): UByte =
    if (kmCoreNapiAddon != null) (kmCoreNapiAddon.kmAtomicXchgU8(lo32(addr), hi32(addr), value.toInt(), ord) as Int).toUByte() else KmLinearMemoryArena.atomicXchgU8(addr, value)
public actual fun kmAtomicXchgU16(addr: Long, value: UShort, ord: Int): UShort =
    if (kmCoreNapiAddon != null) (kmCoreNapiAddon.kmAtomicXchgU16(lo32(addr), hi32(addr), value.toInt(), ord) as Int).toUShort() else KmLinearMemoryArena.atomicXchgU16(addr, value)
public actual fun kmAtomicXchgU32(addr: Long, value: UInt, ord: Int): UInt =
    if (kmCoreNapiAddon != null) (kmCoreNapiAddon.kmAtomicXchgU32(lo32(addr), hi32(addr), value.toInt(), ord) as Int).toUInt() else KmLinearMemoryArena.atomicXchgU32(addr, value)
public actual fun kmAtomicXchgU64(addr: Long, value: ULong, ord: Int): ULong =
    if (kmCoreNapiAddon != null) {
        val lo = kmCoreNapiAddon.kmAtomicXchgU64(lo32(addr), hi32(addr), uLo32(value), uHi32(value), ord) as Int
        packU64(lo, kmCoreNapiAddon.kmOut1() as Int)
    } else {
        KmLinearMemoryArena.atomicXchgU64(addr, value)
    }

public actual fun kmAtomicCxchgU8(addr: Long, expected: UByte, desired: UByte, succ: Int, fail: Int): AtomicCxchgU8Outcome =
    if (kmCoreNapiAddon != null) {
        val packed = kmCoreNapiAddon.kmAtomicCxchgU8(lo32(addr), hi32(addr), expected.toInt(), desired.toInt(), succ, fail) as Int
        AtomicCxchgU8Outcome((packed and 0xFF).toUByte(), (packed ushr 8) != 0)
    } else {
        KmLinearMemoryArena.atomicCxchgU8(addr, expected, desired)
    }

public actual fun kmAtomicCxchgU16(addr: Long, expected: UShort, desired: UShort, succ: Int, fail: Int): AtomicCxchgU16Outcome =
    if (kmCoreNapiAddon != null) {
        val packed = kmCoreNapiAddon.kmAtomicCxchgU16(lo32(addr), hi32(addr), expected.toInt(), desired.toInt(), succ, fail) as Int
        AtomicCxchgU16Outcome((packed and 0xFFFF).toUShort(), (packed ushr 16) != 0)
    } else {
        KmLinearMemoryArena.atomicCxchgU16(addr, expected, desired)
    }

public actual fun kmAtomicCxchgU32(addr: Long, expected: UInt, desired: UInt, succ: Int, fail: Int): AtomicCxchgU32Outcome =
    if (kmCoreNapiAddon != null) {
        val ok = (kmCoreNapiAddon.kmAtomicCxchgU32(lo32(addr), hi32(addr), expected.toInt(), desired.toInt(), succ, fail) as Int) != 0
        AtomicCxchgU32Outcome((kmCoreNapiAddon.kmOut0() as Int).toUInt(), ok)
    } else {
        KmLinearMemoryArena.atomicCxchgU32(addr, expected, desired)
    }

public actual fun kmAtomicCxchgU64(addr: Long, expected: ULong, desired: ULong, succ: Int, fail: Int): AtomicCxchgU64Outcome =
    if (kmCoreNapiAddon != null) {
        val ok =
            (
                kmCoreNapiAddon.kmAtomicCxchgU64(
                    lo32(addr),
                    hi32(addr),
                    uLo32(expected),
                    uHi32(expected),
                    uLo32(desired),
                    uHi32(desired),
                    succ,
                    fail,
                ) as Int
            ) != 0
        AtomicCxchgU64Outcome(packU64(kmCoreNapiAddon.kmOut0() as Int, kmCoreNapiAddon.kmOut1() as Int), ok)
    } else {
        KmLinearMemoryArena.atomicCxchgU64(addr, expected, desired)
    }

public actual fun kmAtomicRmwU8(addr: Long, value: UByte, op: Int, ord: Int): UByte =
    if (kmCoreNapiAddon != null) (kmCoreNapiAddon.kmAtomicRmwU8(lo32(addr), hi32(addr), value.toInt(), op, ord) as Int).toUByte() else KmLinearMemoryArena.atomicRmwU8(addr, value, op)
public actual fun kmAtomicRmwU16(addr: Long, value: UShort, op: Int, ord: Int): UShort =
    if (kmCoreNapiAddon != null) (kmCoreNapiAddon.kmAtomicRmwU16(lo32(addr), hi32(addr), value.toInt(), op, ord) as Int).toUShort() else KmLinearMemoryArena.atomicRmwU16(addr, value, op)
public actual fun kmAtomicRmwU32(addr: Long, value: UInt, op: Int, ord: Int): UInt =
    if (kmCoreNapiAddon != null) (kmCoreNapiAddon.kmAtomicRmwU32(lo32(addr), hi32(addr), value.toInt(), op, ord) as Int).toUInt() else KmLinearMemoryArena.atomicRmwU32(addr, value, op)
public actual fun kmAtomicRmwU64(addr: Long, value: ULong, op: Int, ord: Int): ULong =
    if (kmCoreNapiAddon != null) {
        val lo = kmCoreNapiAddon.kmAtomicRmwU64(lo32(addr), hi32(addr), uLo32(value), uHi32(value), op, ord) as Int
        packU64(lo, kmCoreNapiAddon.kmOut1() as Int)
    } else {
        KmLinearMemoryArena.atomicRmwU64(addr, value, op)
    }

public actual fun kmCtpopU64(value: ULong): UInt =
    if (kmCoreNapiAddon != null) (kmCoreNapiAddon.kmCtpopU64(uLo32(value), uHi32(value)) as Int).toUInt() else KmLinearMemoryArena.ctpopU64(value)
public actual fun kmCtlzU64(value: ULong): UInt =
    if (kmCoreNapiAddon != null) (kmCoreNapiAddon.kmCtlzU64(uLo32(value), uHi32(value)) as Int).toUInt() else KmLinearMemoryArena.ctlzU64(value)
public actual fun kmCttzU64(value: ULong): UInt =
    if (kmCoreNapiAddon != null) (kmCoreNapiAddon.kmCttzU64(uLo32(value), uHi32(value)) as Int).toUInt() else KmLinearMemoryArena.cttzU64(value)
public actual fun kmBswapU16(value: UShort): UShort =
    if (kmCoreNapiAddon != null) (kmCoreNapiAddon.kmBswapU16(value.toInt()) as Int).toUShort() else KmLinearMemoryArena.bswapU16(value)
public actual fun kmBswapU32(value: UInt): UInt =
    if (kmCoreNapiAddon != null) (kmCoreNapiAddon.kmBswapU32(value.toInt()) as Int).toUInt() else KmLinearMemoryArena.bswapU32(value)
public actual fun kmBswapU64(value: ULong): ULong =
    if (kmCoreNapiAddon != null) {
        val lo = kmCoreNapiAddon.kmBswapU64(uLo32(value), uHi32(value)) as Int
        packU64(lo, kmCoreNapiAddon.kmOut1() as Int)
    } else {
        KmLinearMemoryArena.bswapU64(value)
    }
public actual fun kmBitreverseU64(value: ULong): ULong =
    if (kmCoreNapiAddon != null) {
        val lo = kmCoreNapiAddon.kmBitreverseU64(uLo32(value), uHi32(value)) as Int
        packU64(lo, kmCoreNapiAddon.kmOut1() as Int)
    } else {
        KmLinearMemoryArena.bitreverseU64(value)
    }
public actual fun kmFmaf32(a: Float, b: Float, c: Float): Float =
    if (kmCoreNapiAddon != null) (kmCoreNapiAddon.kmFmaf32(a.toDouble(), b.toDouble(), c.toDouble()) as Double).toFloat() else KmLinearMemoryArena.fmaf32(a, b, c)
public actual fun kmFmaf64(a: Double, b: Double, c: Double): Double =
    if (kmCoreNapiAddon != null) (kmCoreNapiAddon.kmFmaf64(a, b, c) as Double) else KmLinearMemoryArena.fmaf64(a, b, c)
public actual fun kmSqrtf32(x: Float): Float =
    if (kmCoreNapiAddon != null) (kmCoreNapiAddon.kmSqrtf32(x.toDouble()) as Double).toFloat() else KmLinearMemoryArena.sqrtf32(x)
public actual fun kmSqrtf64(x: Double): Double =
    if (kmCoreNapiAddon != null) (kmCoreNapiAddon.kmSqrtf64(x) as Double) else KmLinearMemoryArena.sqrtf64(x)
public actual fun kmAbort(): Nothing {
    if (kmCoreNapiAddon != null) kmCoreNapiAddon.kmAbort()
    error("Rust intrinsic abort triggered")
}

public actual fun kmSpinLoopHint() {
    if (kmCoreNapiAddon != null) kmCoreNapiAddon.kmSpinLoopHint()
}
public actual fun kmBlackBoxU64(value: ULong): ULong =
    if (kmCoreNapiAddon != null) {
        val lo = kmCoreNapiAddon.kmBlackBoxU64(uLo32(value), uHi32(value)) as Int
        packU64(lo, kmCoreNapiAddon.kmOut1() as Int)
    } else {
        value
    }
public actual fun kmUmulhU64(a: ULong, b: ULong): ULong =
    if (kmCoreNapiAddon != null) {
        val lo = kmCoreNapiAddon.kmUmulhU64(uLo32(a), uHi32(a), uLo32(b), uHi32(b)) as Int
        packU64(lo, kmCoreNapiAddon.kmOut1() as Int)
    } else {
        KmLinearMemoryArena.umulhU64(a, b)
    }
public actual fun kmSmulhI64(a: Long, b: Long): Long =
    if (kmCoreNapiAddon != null) {
        val lo = kmCoreNapiAddon.kmSmulhI64(lo32(a), hi32(a), lo32(b), hi32(b)) as Int
        packI64(lo, kmCoreNapiAddon.kmOut1() as Int)
    } else {
        KmLinearMemoryArena.smulhI64(a, b)
    }
public actual fun kmAtomicCxchgU128Pair(
    addr: Long,
    expLo: ULong,
    expHi: ULong,
    desLo: ULong,
    desHi: ULong,
): AtomicCxchgU128Outcome =
    if (kmCoreNapiAddon != null) {
        val ok =
            (
                kmCoreNapiAddon.kmAtomicCxchgU128Pair(
                    lo32(addr),
                    hi32(addr),
                    uLo32(expLo),
                    uHi32(expLo),
                    uLo32(expHi),
                    uHi32(expHi),
                    uLo32(desLo),
                    uHi32(desLo),
                    uLo32(desHi),
                    uHi32(desHi),
                ) as Int
            ) != 0
        AtomicCxchgU128Outcome(
            packU64(kmCoreNapiAddon.kmOut0() as Int, kmCoreNapiAddon.kmOut1() as Int),
            packU64(kmCoreNapiAddon.kmOut2() as Int, kmCoreNapiAddon.kmOut3() as Int),
            ok,
        )
    } else {
        KmLinearMemoryArena.atomicCxchgU128Pair(addr, expLo, expHi, desLo, desHi)
    }

public actual fun kmSimdFmaF32x4(dstAddr: Long, aAddr: Long, bAddr: Long, cAddr: Long) {
    if (kmCoreNapiAddon != null) {
        kmCoreNapiAddon.kmSimdFmaF32x4(lo32(dstAddr), hi32(dstAddr), lo32(aAddr), hi32(aAddr), lo32(bAddr), hi32(bAddr), lo32(cAddr), hi32(cAddr))
    } else {
        KmLinearMemoryArena.simdFmaF32x4(dstAddr, aAddr, bAddr, cAddr)
    }
}
public actual fun kmSimdDotF32(aAddr: Long, bAddr: Long, len: Long): Float =
    if (kmCoreNapiAddon != null) {
        (kmCoreNapiAddon.kmSimdDotF32(lo32(aAddr), hi32(aAddr), lo32(bAddr), hi32(bAddr), lo32(len), hi32(len)) as Double).toFloat()
    } else {
        KmLinearMemoryArena.simdDotF32(aAddr, bAddr, len)
    }
""".trimStart()
    }

    public fun renderKmCoreWasmJsActualsKotlin(
        coreNamespace: String,
        ffiModName: String = "kmcore",
    ): String {
        val loaderBody = renderNodeKmCoreLoaderJsBody(ffiModName)
        return """
@file:OptIn(ExperimentalUnsignedTypes::class, ExperimentalWasmJsInterop::class)

package $coreNamespace.intrinsics

@JsFun("() => { var valObj = (function() { $loaderBody })(); return valObj !== null && typeof valObj !== 'undefined'; }")
private external fun initKmCoreNapiAddon(): Boolean

private val kmCoreNapiAvailable: Boolean = initKmCoreNapiAddon()

private fun lo32(v: Long): Int = v.toInt()
private fun hi32(v: Long): Int = (v ushr 32).toInt()
private fun uLo32(v: ULong): Int = v.toInt()
private fun uHi32(v: ULong): Int = (v shr 32).toInt()
private fun packI64(lo: Int, hi: Int): Long = (hi.toLong() shl 32) or (lo.toLong() and 0xFFFF_FFFFL)
private fun packU64(lo: Int, hi: Int): ULong =
    ((hi.toLong().toULong() and 0xFFFF_FFFFuL) shl 32) or (lo.toLong().toULong() and 0xFFFF_FFFFuL)

@JsFun("() => globalThis.kmCoreNapiV2.kmOut0()")
private external fun wasmKmOut0(): Int

@JsFun("() => globalThis.kmCoreNapiV2.kmOut1()")
private external fun wasmKmOut1(): Int

@JsFun("() => globalThis.kmCoreNapiV2.kmOut2()")
private external fun wasmKmOut2(): Int

@JsFun("() => globalThis.kmCoreNapiV2.kmOut3()")
private external fun wasmKmOut3(): Int

@JsFun("(lo, hi) => globalThis.kmCoreNapiV2.kmLoadU8(lo, hi)")
private external fun wasmKmLoadU8(lo: Int, hi: Int): Int

@JsFun("(lo, hi) => globalThis.kmCoreNapiV2.kmLoadU16(lo, hi)")
private external fun wasmKmLoadU16(lo: Int, hi: Int): Int

@JsFun("(lo, hi) => globalThis.kmCoreNapiV2.kmLoadU32(lo, hi)")
private external fun wasmKmLoadU32(lo: Int, hi: Int): Int

@JsFun("(lo, hi) => globalThis.kmCoreNapiV2.kmLoadU64(lo, hi)")
private external fun wasmKmLoadU64(lo: Int, hi: Int): Int

@JsFun("(lo, hi) => globalThis.kmCoreNapiV2.kmLoadF32(lo, hi)")
private external fun wasmKmLoadF32(lo: Int, hi: Int): Double

@JsFun("(lo, hi) => globalThis.kmCoreNapiV2.kmLoadF64(lo, hi)")
private external fun wasmKmLoadF64(lo: Int, hi: Int): Double

@JsFun("(lo, hi, v) => globalThis.kmCoreNapiV2.kmStoreU8(lo, hi, v)")
private external fun wasmKmStoreU8(lo: Int, hi: Int, v: Int)

@JsFun("(lo, hi, v) => globalThis.kmCoreNapiV2.kmStoreU16(lo, hi, v)")
private external fun wasmKmStoreU16(lo: Int, hi: Int, v: Int)

@JsFun("(lo, hi, v) => globalThis.kmCoreNapiV2.kmStoreU32(lo, hi, v)")
private external fun wasmKmStoreU32(lo: Int, hi: Int, v: Int)

@JsFun("(lo, hi, vLo, vHi) => globalThis.kmCoreNapiV2.kmStoreU64(lo, hi, vLo, vHi)")
private external fun wasmKmStoreU64(lo: Int, hi: Int, vLo: Int, vHi: Int)

@JsFun("(lo, hi, v) => globalThis.kmCoreNapiV2.kmStoreF32(lo, hi, v)")
private external fun wasmKmStoreF32(lo: Int, hi: Int, v: Double)

@JsFun("(lo, hi, v) => globalThis.kmCoreNapiV2.kmStoreF64(lo, hi, v)")
private external fun wasmKmStoreF64(lo: Int, hi: Int, v: Double)

@JsFun("(lo, hi) => globalThis.kmCoreNapiV2.kmVolatileLoadU8(lo, hi)")
private external fun wasmKmVolatileLoadU8(lo: Int, hi: Int): Int

@JsFun("(lo, hi) => globalThis.kmCoreNapiV2.kmVolatileLoadU16(lo, hi)")
private external fun wasmKmVolatileLoadU16(lo: Int, hi: Int): Int

@JsFun("(lo, hi) => globalThis.kmCoreNapiV2.kmVolatileLoadU32(lo, hi)")
private external fun wasmKmVolatileLoadU32(lo: Int, hi: Int): Int

@JsFun("(lo, hi) => globalThis.kmCoreNapiV2.kmVolatileLoadU64(lo, hi)")
private external fun wasmKmVolatileLoadU64(lo: Int, hi: Int): Int

@JsFun("(lo, hi, v) => globalThis.kmCoreNapiV2.kmVolatileStoreU8(lo, hi, v)")
private external fun wasmKmVolatileStoreU8(lo: Int, hi: Int, v: Int)

@JsFun("(lo, hi, v) => globalThis.kmCoreNapiV2.kmVolatileStoreU16(lo, hi, v)")
private external fun wasmKmVolatileStoreU16(lo: Int, hi: Int, v: Int)

@JsFun("(lo, hi, v) => globalThis.kmCoreNapiV2.kmVolatileStoreU32(lo, hi, v)")
private external fun wasmKmVolatileStoreU32(lo: Int, hi: Int, v: Int)

@JsFun("(lo, hi, vLo, vHi) => globalThis.kmCoreNapiV2.kmVolatileStoreU64(lo, hi, vLo, vHi)")
private external fun wasmKmVolatileStoreU64(lo: Int, hi: Int, vLo: Int, vHi: Int)

@JsFun("(dLo, dHi, sLo, sHi, bLo, bHi) => globalThis.kmCoreNapiV2.kmCopyNonoverlapping(dLo, dHi, sLo, sHi, bLo, bHi)")
private external fun wasmKmCopyNonoverlapping(dLo: Int, dHi: Int, sLo: Int, sHi: Int, bLo: Int, bHi: Int)

@JsFun("(dLo, dHi, sLo, sHi, bLo, bHi) => globalThis.kmCoreNapiV2.kmCopy(dLo, dHi, sLo, sHi, bLo, bHi)")
private external fun wasmKmCopy(dLo: Int, dHi: Int, sLo: Int, sHi: Int, bLo: Int, bHi: Int)

@JsFun("(dLo, dHi, v, bLo, bHi) => globalThis.kmCoreNapiV2.kmWriteBytes(dLo, dHi, v, bLo, bHi)")
private external fun wasmKmWriteBytes(dLo: Int, dHi: Int, v: Int, bLo: Int, bHi: Int)

@JsFun("(lLo, lHi, rLo, rHi, bLo, bHi) => globalThis.kmCoreNapiV2.kmCompareBytes(lLo, lHi, rLo, rHi, bLo, bHi)")
private external fun wasmKmCompareBytes(lLo: Int, lHi: Int, rLo: Int, rHi: Int, bLo: Int, bHi: Int): Int

@JsFun("(sLo, sHi, aLo, aHi) => globalThis.kmCoreNapiV2.kmAlloc(sLo, sHi, aLo, aHi)")
private external fun wasmKmAlloc(sLo: Int, sHi: Int, aLo: Int, aHi: Int): Int

@JsFun("(sLo, sHi, aLo, aHi) => globalThis.kmCoreNapiV2.kmAllocZeroed(sLo, sHi, aLo, aHi)")
private external fun wasmKmAllocZeroed(sLo: Int, sHi: Int, aLo: Int, aHi: Int): Int

@JsFun("(pLo, pHi, oLo, oHi, aLo, aHi, nLo, nHi) => globalThis.kmCoreNapiV2.kmRealloc(pLo, pHi, oLo, oHi, aLo, aHi, nLo, nHi)")
private external fun wasmKmRealloc(pLo: Int, pHi: Int, oLo: Int, oHi: Int, aLo: Int, aHi: Int, nLo: Int, nHi: Int): Int

@JsFun("(pLo, pHi, sLo, sHi, aLo, aHi) => globalThis.kmCoreNapiV2.kmDealloc(pLo, pHi, sLo, sHi, aLo, aHi)")
private external fun wasmKmDealloc(pLo: Int, pHi: Int, sLo: Int, sHi: Int, aLo: Int, aHi: Int)

@JsFun("(p0) => globalThis.kmCoreNapiV2.kmAtomicFence(p0)")
private external fun wasmKmAtomicFence(p0: Int)

@JsFun("(p0) => globalThis.kmCoreNapiV2.kmAtomicSignalFence(p0)")
private external fun wasmKmAtomicSignalFence(p0: Int)

@JsFun("(lo, hi, ord) => globalThis.kmCoreNapiV2.kmAtomicLoadU8(lo, hi, ord)")
private external fun wasmKmAtomicLoadU8(lo: Int, hi: Int, ord: Int): Int

@JsFun("(lo, hi, ord) => globalThis.kmCoreNapiV2.kmAtomicLoadU16(lo, hi, ord)")
private external fun wasmKmAtomicLoadU16(lo: Int, hi: Int, ord: Int): Int

@JsFun("(lo, hi, ord) => globalThis.kmCoreNapiV2.kmAtomicLoadU32(lo, hi, ord)")
private external fun wasmKmAtomicLoadU32(lo: Int, hi: Int, ord: Int): Int

@JsFun("(lo, hi, ord) => globalThis.kmCoreNapiV2.kmAtomicLoadU64(lo, hi, ord)")
private external fun wasmKmAtomicLoadU64(lo: Int, hi: Int, ord: Int): Int

@JsFun("(lo, hi, v, ord) => globalThis.kmCoreNapiV2.kmAtomicStoreU8(lo, hi, v, ord)")
private external fun wasmKmAtomicStoreU8(lo: Int, hi: Int, v: Int, ord: Int)

@JsFun("(lo, hi, v, ord) => globalThis.kmCoreNapiV2.kmAtomicStoreU16(lo, hi, v, ord)")
private external fun wasmKmAtomicStoreU16(lo: Int, hi: Int, v: Int, ord: Int)

@JsFun("(lo, hi, v, ord) => globalThis.kmCoreNapiV2.kmAtomicStoreU32(lo, hi, v, ord)")
private external fun wasmKmAtomicStoreU32(lo: Int, hi: Int, v: Int, ord: Int)

@JsFun("(lo, hi, vLo, vHi, ord) => globalThis.kmCoreNapiV2.kmAtomicStoreU64(lo, hi, vLo, vHi, ord)")
private external fun wasmKmAtomicStoreU64(lo: Int, hi: Int, vLo: Int, vHi: Int, ord: Int)

@JsFun("(lo, hi, v, ord) => globalThis.kmCoreNapiV2.kmAtomicXchgU8(lo, hi, v, ord)")
private external fun wasmKmAtomicXchgU8(lo: Int, hi: Int, v: Int, ord: Int): Int

@JsFun("(lo, hi, v, ord) => globalThis.kmCoreNapiV2.kmAtomicXchgU16(lo, hi, v, ord)")
private external fun wasmKmAtomicXchgU16(lo: Int, hi: Int, v: Int, ord: Int): Int

@JsFun("(lo, hi, v, ord) => globalThis.kmCoreNapiV2.kmAtomicXchgU32(lo, hi, v, ord)")
private external fun wasmKmAtomicXchgU32(lo: Int, hi: Int, v: Int, ord: Int): Int

@JsFun("(lo, hi, vLo, vHi, ord) => globalThis.kmCoreNapiV2.kmAtomicXchgU64(lo, hi, vLo, vHi, ord)")
private external fun wasmKmAtomicXchgU64(lo: Int, hi: Int, vLo: Int, vHi: Int, ord: Int): Int

@JsFun("(lo, hi, exp, des, succ, fail) => globalThis.kmCoreNapiV2.kmAtomicCxchgU8(lo, hi, exp, des, succ, fail)")
private external fun wasmKmAtomicCxchgU8(lo: Int, hi: Int, exp: Int, des: Int, succ: Int, fail: Int): Int

@JsFun("(lo, hi, exp, des, succ, fail) => globalThis.kmCoreNapiV2.kmAtomicCxchgU16(lo, hi, exp, des, succ, fail)")
private external fun wasmKmAtomicCxchgU16(lo: Int, hi: Int, exp: Int, des: Int, succ: Int, fail: Int): Int

@JsFun("(lo, hi, exp, des, succ, fail) => globalThis.kmCoreNapiV2.kmAtomicCxchgU32(lo, hi, exp, des, succ, fail)")
private external fun wasmKmAtomicCxchgU32(lo: Int, hi: Int, exp: Int, des: Int, succ: Int, fail: Int): Int

@JsFun("(lo, hi, expLo, expHi, desLo, desHi, succ, fail) => globalThis.kmCoreNapiV2.kmAtomicCxchgU64(lo, hi, expLo, expHi, desLo, desHi, succ, fail)")
private external fun wasmKmAtomicCxchgU64(
    lo: Int,
    hi: Int,
    expLo: Int,
    expHi: Int,
    desLo: Int,
    desHi: Int,
    succ: Int,
    fail: Int,
): Int

@JsFun("(lo, hi, v, op, ord) => globalThis.kmCoreNapiV2.kmAtomicRmwU8(lo, hi, v, op, ord)")
private external fun wasmKmAtomicRmwU8(lo: Int, hi: Int, v: Int, op: Int, ord: Int): Int

@JsFun("(lo, hi, v, op, ord) => globalThis.kmCoreNapiV2.kmAtomicRmwU16(lo, hi, v, op, ord)")
private external fun wasmKmAtomicRmwU16(lo: Int, hi: Int, v: Int, op: Int, ord: Int): Int

@JsFun("(lo, hi, v, op, ord) => globalThis.kmCoreNapiV2.kmAtomicRmwU32(lo, hi, v, op, ord)")
private external fun wasmKmAtomicRmwU32(lo: Int, hi: Int, v: Int, op: Int, ord: Int): Int

@JsFun("(lo, hi, vLo, vHi, op, ord) => globalThis.kmCoreNapiV2.kmAtomicRmwU64(lo, hi, vLo, vHi, op, ord)")
private external fun wasmKmAtomicRmwU64(lo: Int, hi: Int, vLo: Int, vHi: Int, op: Int, ord: Int): Int

@JsFun("(lo, hi) => globalThis.kmCoreNapiV2.kmCtpopU64(lo, hi)")
private external fun wasmKmCtpopU64(lo: Int, hi: Int): Int

@JsFun("(lo, hi) => globalThis.kmCoreNapiV2.kmCtlzU64(lo, hi)")
private external fun wasmKmCtlzU64(lo: Int, hi: Int): Int

@JsFun("(lo, hi) => globalThis.kmCoreNapiV2.kmCttzU64(lo, hi)")
private external fun wasmKmCttzU64(lo: Int, hi: Int): Int

@JsFun("(p0) => globalThis.kmCoreNapiV2.kmBswapU16(p0)")
private external fun wasmKmBswapU16(p0: Int): Int

@JsFun("(p0) => globalThis.kmCoreNapiV2.kmBswapU32(p0)")
private external fun wasmKmBswapU32(p0: Int): Int

@JsFun("(lo, hi) => globalThis.kmCoreNapiV2.kmBswapU64(lo, hi)")
private external fun wasmKmBswapU64(lo: Int, hi: Int): Int

@JsFun("(lo, hi) => globalThis.kmCoreNapiV2.kmBitreverseU64(lo, hi)")
private external fun wasmKmBitreverseU64(lo: Int, hi: Int): Int

@JsFun("(p0, p1, p2) => globalThis.kmCoreNapiV2.kmFmaf32(p0, p1, p2)")
private external fun wasmKmFmaf32(p0: Double, p1: Double, p2: Double): Double

@JsFun("(p0, p1, p2) => globalThis.kmCoreNapiV2.kmFmaf64(p0, p1, p2)")
private external fun wasmKmFmaf64(p0: Double, p1: Double, p2: Double): Double

@JsFun("(p0) => globalThis.kmCoreNapiV2.kmSqrtf32(p0)")
private external fun wasmKmSqrtf32(p0: Double): Double

@JsFun("(p0) => globalThis.kmCoreNapiV2.kmSqrtf64(p0)")
private external fun wasmKmSqrtf64(p0: Double): Double

@JsFun("() => globalThis.kmCoreNapiV2.kmAbort()")
private external fun wasmKmAbort()

@JsFun("() => globalThis.kmCoreNapiV2.kmSpinLoopHint()")
private external fun wasmKmSpinLoopHint()

@JsFun("(lo, hi) => globalThis.kmCoreNapiV2.kmBlackBoxU64(lo, hi)")
private external fun wasmKmBlackBoxU64(lo: Int, hi: Int): Int

@JsFun("(aLo, aHi, bLo, bHi) => globalThis.kmCoreNapiV2.kmUmulhU64(aLo, aHi, bLo, bHi)")
private external fun wasmKmUmulhU64(aLo: Int, aHi: Int, bLo: Int, bHi: Int): Int

@JsFun("(aLo, aHi, bLo, bHi) => globalThis.kmCoreNapiV2.kmSmulhI64(aLo, aHi, bLo, bHi)")
private external fun wasmKmSmulhI64(aLo: Int, aHi: Int, bLo: Int, bHi: Int): Int

@JsFun("(addrLo, addrHi, expLoLo, expLoHi, expHiLo, expHiHi, desLoLo, desLoHi, desHiLo, desHiHi) => globalThis.kmCoreNapiV2.kmAtomicCxchgU128Pair(addrLo, addrHi, expLoLo, expLoHi, expHiLo, expHiHi, desLoLo, desLoHi, desHiLo, desHiHi)")
private external fun wasmKmAtomicCxchgU128Pair(
    addrLo: Int,
    addrHi: Int,
    expLoLo: Int,
    expLoHi: Int,
    expHiLo: Int,
    expHiHi: Int,
    desLoLo: Int,
    desLoHi: Int,
    desHiLo: Int,
    desHiHi: Int,
): Int

@JsFun("(dLo, dHi, aLo, aHi, bLo, bHi, cLo, cHi) => globalThis.kmCoreNapiV2.kmSimdFmaF32x4(dLo, dHi, aLo, aHi, bLo, bHi, cLo, cHi)")
private external fun wasmKmSimdFmaF32x4(dLo: Int, dHi: Int, aLo: Int, aHi: Int, bLo: Int, bHi: Int, cLo: Int, cHi: Int)

@JsFun("(aLo, aHi, bLo, bHi, lLo, lHi) => globalThis.kmCoreNapiV2.kmSimdDotF32(aLo, aHi, bLo, bHi, lLo, lHi)")
private external fun wasmKmSimdDotF32(aLo: Int, aHi: Int, bLo: Int, bHi: Int, lLo: Int, lHi: Int): Double

public actual fun kmLoadU8(addr: Long): UByte =
    if (kmCoreNapiAvailable) wasmKmLoadU8(lo32(addr), hi32(addr)).toUByte() else KmLinearMemoryArena.loadU8(addr)
public actual fun kmLoadU16(addr: Long): UShort =
    if (kmCoreNapiAvailable) wasmKmLoadU16(lo32(addr), hi32(addr)).toUShort() else KmLinearMemoryArena.loadU16(addr)
public actual fun kmLoadU32(addr: Long): UInt =
    if (kmCoreNapiAvailable) wasmKmLoadU32(lo32(addr), hi32(addr)).toUInt() else KmLinearMemoryArena.loadU32(addr)
public actual fun kmLoadU64(addr: Long): ULong =
    if (kmCoreNapiAvailable) {
        val lo = wasmKmLoadU64(lo32(addr), hi32(addr))
        packU64(lo, wasmKmOut1())
    } else {
        KmLinearMemoryArena.loadU64(addr)
    }
public actual fun kmLoadF32(addr: Long): Float =
    if (kmCoreNapiAvailable) wasmKmLoadF32(lo32(addr), hi32(addr)).toFloat() else KmLinearMemoryArena.loadF32(addr)
public actual fun kmLoadF64(addr: Long): Double =
    if (kmCoreNapiAvailable) wasmKmLoadF64(lo32(addr), hi32(addr)) else KmLinearMemoryArena.loadF64(addr)

public actual fun kmStoreU8(addr: Long, value: UByte) {
    if (kmCoreNapiAvailable) wasmKmStoreU8(lo32(addr), hi32(addr), value.toInt()) else KmLinearMemoryArena.storeU8(addr, value)
}
public actual fun kmStoreU16(addr: Long, value: UShort) {
    if (kmCoreNapiAvailable) wasmKmStoreU16(lo32(addr), hi32(addr), value.toInt()) else KmLinearMemoryArena.storeU16(addr, value)
}
public actual fun kmStoreU32(addr: Long, value: UInt) {
    if (kmCoreNapiAvailable) wasmKmStoreU32(lo32(addr), hi32(addr), value.toInt()) else KmLinearMemoryArena.storeU32(addr, value)
}
public actual fun kmStoreU64(addr: Long, value: ULong) {
    if (kmCoreNapiAvailable) wasmKmStoreU64(lo32(addr), hi32(addr), uLo32(value), uHi32(value)) else KmLinearMemoryArena.storeU64(addr, value)
}
public actual fun kmStoreF32(addr: Long, value: Float) {
    if (kmCoreNapiAvailable) wasmKmStoreF32(lo32(addr), hi32(addr), value.toDouble()) else KmLinearMemoryArena.storeF32(addr, value)
}
public actual fun kmStoreF64(addr: Long, value: Double) {
    if (kmCoreNapiAvailable) wasmKmStoreF64(lo32(addr), hi32(addr), value) else KmLinearMemoryArena.storeF64(addr, value)
}

public actual fun kmVolatileLoadU8(addr: Long): UByte =
    if (kmCoreNapiAvailable) wasmKmVolatileLoadU8(lo32(addr), hi32(addr)).toUByte() else KmLinearMemoryArena.loadU8(addr)
public actual fun kmVolatileLoadU16(addr: Long): UShort =
    if (kmCoreNapiAvailable) wasmKmVolatileLoadU16(lo32(addr), hi32(addr)).toUShort() else KmLinearMemoryArena.loadU16(addr)
public actual fun kmVolatileLoadU32(addr: Long): UInt =
    if (kmCoreNapiAvailable) wasmKmVolatileLoadU32(lo32(addr), hi32(addr)).toUInt() else KmLinearMemoryArena.loadU32(addr)
public actual fun kmVolatileLoadU64(addr: Long): ULong =
    if (kmCoreNapiAvailable) {
        val lo = wasmKmVolatileLoadU64(lo32(addr), hi32(addr))
        packU64(lo, wasmKmOut1())
    } else {
        KmLinearMemoryArena.loadU64(addr)
    }

public actual fun kmVolatileStoreU8(addr: Long, value: UByte) {
    if (kmCoreNapiAvailable) wasmKmVolatileStoreU8(lo32(addr), hi32(addr), value.toInt()) else KmLinearMemoryArena.storeU8(addr, value)
}
public actual fun kmVolatileStoreU16(addr: Long, value: UShort) {
    if (kmCoreNapiAvailable) wasmKmVolatileStoreU16(lo32(addr), hi32(addr), value.toInt()) else KmLinearMemoryArena.storeU16(addr, value)
}
public actual fun kmVolatileStoreU32(addr: Long, value: UInt) {
    if (kmCoreNapiAvailable) wasmKmVolatileStoreU32(lo32(addr), hi32(addr), value.toInt()) else KmLinearMemoryArena.storeU32(addr, value)
}
public actual fun kmVolatileStoreU64(addr: Long, value: ULong) {
    if (kmCoreNapiAvailable) wasmKmVolatileStoreU64(lo32(addr), hi32(addr), uLo32(value), uHi32(value)) else KmLinearMemoryArena.storeU64(addr, value)
}

public actual fun kmCopyNonoverlapping(dst: Long, src: Long, bytes: Long) {
    if (kmCoreNapiAvailable) wasmKmCopyNonoverlapping(lo32(dst), hi32(dst), lo32(src), hi32(src), lo32(bytes), hi32(bytes)) else KmLinearMemoryArena.copyNonoverlapping(dst, src, bytes)
}
public actual fun kmCopy(dst: Long, src: Long, bytes: Long) {
    if (kmCoreNapiAvailable) wasmKmCopy(lo32(dst), hi32(dst), lo32(src), hi32(src), lo32(bytes), hi32(bytes)) else KmLinearMemoryArena.copy(dst, src, bytes)
}
public actual fun kmWriteBytes(dst: Long, value: UByte, bytes: Long) {
    if (kmCoreNapiAvailable) wasmKmWriteBytes(lo32(dst), hi32(dst), value.toInt(), lo32(bytes), hi32(bytes)) else KmLinearMemoryArena.writeBytes(dst, value, bytes)
}
public actual fun kmCompareBytes(left: Long, right: Long, bytes: Long): Int =
    if (kmCoreNapiAvailable) wasmKmCompareBytes(lo32(left), hi32(left), lo32(right), hi32(right), lo32(bytes), hi32(bytes)) else KmLinearMemoryArena.compareBytes(left, right, bytes)

public actual fun kmAlloc(size: Long, align: Long): Long =
    if (kmCoreNapiAvailable) {
        val lo = wasmKmAlloc(lo32(size), hi32(size), lo32(align), hi32(align))
        packI64(lo, wasmKmOut1())
    } else {
        KmLinearMemoryArena.alloc(size, align)
    }
public actual fun kmAllocZeroed(size: Long, align: Long): Long =
    if (kmCoreNapiAvailable) {
        val lo = wasmKmAllocZeroed(lo32(size), hi32(size), lo32(align), hi32(align))
        packI64(lo, wasmKmOut1())
    } else {
        KmLinearMemoryArena.allocZeroed(size, align)
    }
public actual fun kmRealloc(ptr: Long, oldSize: Long, align: Long, newSize: Long): Long =
    if (kmCoreNapiAvailable) {
        val lo = wasmKmRealloc(lo32(ptr), hi32(ptr), lo32(oldSize), hi32(oldSize), lo32(align), hi32(align), lo32(newSize), hi32(newSize))
        packI64(lo, wasmKmOut1())
    } else {
        KmLinearMemoryArena.realloc(ptr, oldSize, align, newSize)
    }
public actual fun kmDealloc(ptr: Long, size: Long, align: Long) {
    if (kmCoreNapiAvailable) wasmKmDealloc(lo32(ptr), hi32(ptr), lo32(size), hi32(size), lo32(align), hi32(align)) else KmLinearMemoryArena.dealloc(ptr, size, align)
}

public actual fun kmAtomicFence(ord: Int) {
    if (kmCoreNapiAvailable) wasmKmAtomicFence(ord)
}
public actual fun kmAtomicSignalFence(ord: Int) {
    if (kmCoreNapiAvailable) wasmKmAtomicSignalFence(ord)
}
public actual fun kmAtomicLoadU8(addr: Long, ord: Int): UByte =
    if (kmCoreNapiAvailable) wasmKmAtomicLoadU8(lo32(addr), hi32(addr), ord).toUByte() else KmLinearMemoryArena.loadU8(addr)
public actual fun kmAtomicLoadU16(addr: Long, ord: Int): UShort =
    if (kmCoreNapiAvailable) wasmKmAtomicLoadU16(lo32(addr), hi32(addr), ord).toUShort() else KmLinearMemoryArena.loadU16(addr)
public actual fun kmAtomicLoadU32(addr: Long, ord: Int): UInt =
    if (kmCoreNapiAvailable) wasmKmAtomicLoadU32(lo32(addr), hi32(addr), ord).toUInt() else KmLinearMemoryArena.loadU32(addr)
public actual fun kmAtomicLoadU64(addr: Long, ord: Int): ULong =
    if (kmCoreNapiAvailable) {
        val lo = wasmKmAtomicLoadU64(lo32(addr), hi32(addr), ord)
        packU64(lo, wasmKmOut1())
    } else {
        KmLinearMemoryArena.loadU64(addr)
    }

public actual fun kmAtomicStoreU8(addr: Long, value: UByte, ord: Int) {
    if (kmCoreNapiAvailable) wasmKmAtomicStoreU8(lo32(addr), hi32(addr), value.toInt(), ord) else KmLinearMemoryArena.storeU8(addr, value)
}
public actual fun kmAtomicStoreU16(addr: Long, value: UShort, ord: Int) {
    if (kmCoreNapiAvailable) wasmKmAtomicStoreU16(lo32(addr), hi32(addr), value.toInt(), ord) else KmLinearMemoryArena.storeU16(addr, value)
}
public actual fun kmAtomicStoreU32(addr: Long, value: UInt, ord: Int) {
    if (kmCoreNapiAvailable) wasmKmAtomicStoreU32(lo32(addr), hi32(addr), value.toInt(), ord) else KmLinearMemoryArena.storeU32(addr, value)
}
public actual fun kmAtomicStoreU64(addr: Long, value: ULong, ord: Int) {
    if (kmCoreNapiAvailable) wasmKmAtomicStoreU64(lo32(addr), hi32(addr), uLo32(value), uHi32(value), ord) else KmLinearMemoryArena.storeU64(addr, value)
}

public actual fun kmAtomicXchgU8(addr: Long, value: UByte, ord: Int): UByte =
    if (kmCoreNapiAvailable) wasmKmAtomicXchgU8(lo32(addr), hi32(addr), value.toInt(), ord).toUByte() else KmLinearMemoryArena.atomicXchgU8(addr, value)
public actual fun kmAtomicXchgU16(addr: Long, value: UShort, ord: Int): UShort =
    if (kmCoreNapiAvailable) wasmKmAtomicXchgU16(lo32(addr), hi32(addr), value.toInt(), ord).toUShort() else KmLinearMemoryArena.atomicXchgU16(addr, value)
public actual fun kmAtomicXchgU32(addr: Long, value: UInt, ord: Int): UInt =
    if (kmCoreNapiAvailable) wasmKmAtomicXchgU32(lo32(addr), hi32(addr), value.toInt(), ord).toUInt() else KmLinearMemoryArena.atomicXchgU32(addr, value)
public actual fun kmAtomicXchgU64(addr: Long, value: ULong, ord: Int): ULong =
    if (kmCoreNapiAvailable) {
        val lo = wasmKmAtomicXchgU64(lo32(addr), hi32(addr), uLo32(value), uHi32(value), ord)
        packU64(lo, wasmKmOut1())
    } else {
        KmLinearMemoryArena.atomicXchgU64(addr, value)
    }

public actual fun kmAtomicCxchgU8(addr: Long, expected: UByte, desired: UByte, succ: Int, fail: Int): AtomicCxchgU8Outcome =
    if (kmCoreNapiAvailable) {
        val packed = wasmKmAtomicCxchgU8(lo32(addr), hi32(addr), expected.toInt(), desired.toInt(), succ, fail)
        AtomicCxchgU8Outcome((packed and 0xFF).toUByte(), (packed ushr 8) != 0)
    } else {
        KmLinearMemoryArena.atomicCxchgU8(addr, expected, desired)
    }

public actual fun kmAtomicCxchgU16(addr: Long, expected: UShort, desired: UShort, succ: Int, fail: Int): AtomicCxchgU16Outcome =
    if (kmCoreNapiAvailable) {
        val packed = wasmKmAtomicCxchgU16(lo32(addr), hi32(addr), expected.toInt(), desired.toInt(), succ, fail)
        AtomicCxchgU16Outcome((packed and 0xFFFF).toUShort(), (packed ushr 16) != 0)
    } else {
        KmLinearMemoryArena.atomicCxchgU16(addr, expected, desired)
    }

public actual fun kmAtomicCxchgU32(addr: Long, expected: UInt, desired: UInt, succ: Int, fail: Int): AtomicCxchgU32Outcome =
    if (kmCoreNapiAvailable) {
        val ok = wasmKmAtomicCxchgU32(lo32(addr), hi32(addr), expected.toInt(), desired.toInt(), succ, fail) != 0
        AtomicCxchgU32Outcome(wasmKmOut0().toUInt(), ok)
    } else {
        KmLinearMemoryArena.atomicCxchgU32(addr, expected, desired)
    }

public actual fun kmAtomicCxchgU64(addr: Long, expected: ULong, desired: ULong, succ: Int, fail: Int): AtomicCxchgU64Outcome =
    if (kmCoreNapiAvailable) {
        val ok = wasmKmAtomicCxchgU64(lo32(addr), hi32(addr), uLo32(expected), uHi32(expected), uLo32(desired), uHi32(desired), succ, fail) != 0
        AtomicCxchgU64Outcome(packU64(wasmKmOut0(), wasmKmOut1()), ok)
    } else {
        KmLinearMemoryArena.atomicCxchgU64(addr, expected, desired)
    }

public actual fun kmAtomicRmwU8(addr: Long, value: UByte, op: Int, ord: Int): UByte =
    if (kmCoreNapiAvailable) wasmKmAtomicRmwU8(lo32(addr), hi32(addr), value.toInt(), op, ord).toUByte() else KmLinearMemoryArena.atomicRmwU8(addr, value, op)
public actual fun kmAtomicRmwU16(addr: Long, value: UShort, op: Int, ord: Int): UShort =
    if (kmCoreNapiAvailable) wasmKmAtomicRmwU16(lo32(addr), hi32(addr), value.toInt(), op, ord).toUShort() else KmLinearMemoryArena.atomicRmwU16(addr, value, op)
public actual fun kmAtomicRmwU32(addr: Long, value: UInt, op: Int, ord: Int): UInt =
    if (kmCoreNapiAvailable) wasmKmAtomicRmwU32(lo32(addr), hi32(addr), value.toInt(), op, ord).toUInt() else KmLinearMemoryArena.atomicRmwU32(addr, value, op)
public actual fun kmAtomicRmwU64(addr: Long, value: ULong, op: Int, ord: Int): ULong =
    if (kmCoreNapiAvailable) {
        val lo = wasmKmAtomicRmwU64(lo32(addr), hi32(addr), uLo32(value), uHi32(value), op, ord)
        packU64(lo, wasmKmOut1())
    } else {
        KmLinearMemoryArena.atomicRmwU64(addr, value, op)
    }

public actual fun kmCtpopU64(value: ULong): UInt =
    if (kmCoreNapiAvailable) wasmKmCtpopU64(uLo32(value), uHi32(value)).toUInt() else KmLinearMemoryArena.ctpopU64(value)
public actual fun kmCtlzU64(value: ULong): UInt =
    if (kmCoreNapiAvailable) wasmKmCtlzU64(uLo32(value), uHi32(value)).toUInt() else KmLinearMemoryArena.ctlzU64(value)
public actual fun kmCttzU64(value: ULong): UInt =
    if (kmCoreNapiAvailable) wasmKmCttzU64(uLo32(value), uHi32(value)).toUInt() else KmLinearMemoryArena.cttzU64(value)
public actual fun kmBswapU16(value: UShort): UShort =
    if (kmCoreNapiAvailable) wasmKmBswapU16(value.toInt()).toUShort() else KmLinearMemoryArena.bswapU16(value)
public actual fun kmBswapU32(value: UInt): UInt =
    if (kmCoreNapiAvailable) wasmKmBswapU32(value.toInt()).toUInt() else KmLinearMemoryArena.bswapU32(value)
public actual fun kmBswapU64(value: ULong): ULong =
    if (kmCoreNapiAvailable) {
        val lo = wasmKmBswapU64(uLo32(value), uHi32(value))
        packU64(lo, wasmKmOut1())
    } else {
        KmLinearMemoryArena.bswapU64(value)
    }
public actual fun kmBitreverseU64(value: ULong): ULong =
    if (kmCoreNapiAvailable) {
        val lo = wasmKmBitreverseU64(uLo32(value), uHi32(value))
        packU64(lo, wasmKmOut1())
    } else {
        KmLinearMemoryArena.bitreverseU64(value)
    }
public actual fun kmFmaf32(a: Float, b: Float, c: Float): Float =
    if (kmCoreNapiAvailable) wasmKmFmaf32(a.toDouble(), b.toDouble(), c.toDouble()).toFloat() else KmLinearMemoryArena.fmaf32(a, b, c)
public actual fun kmFmaf64(a: Double, b: Double, c: Double): Double =
    if (kmCoreNapiAvailable) wasmKmFmaf64(a, b, c) else KmLinearMemoryArena.fmaf64(a, b, c)
public actual fun kmSqrtf32(x: Float): Float =
    if (kmCoreNapiAvailable) wasmKmSqrtf32(x.toDouble()).toFloat() else KmLinearMemoryArena.sqrtf32(x)
public actual fun kmSqrtf64(x: Double): Double =
    if (kmCoreNapiAvailable) wasmKmSqrtf64(x) else KmLinearMemoryArena.sqrtf64(x)
public actual fun kmAbort(): Nothing {
    if (kmCoreNapiAvailable) wasmKmAbort()
    error("Rust intrinsic abort triggered")
}

public actual fun kmSpinLoopHint() {
    if (kmCoreNapiAvailable) wasmKmSpinLoopHint()
}
public actual fun kmBlackBoxU64(value: ULong): ULong =
    if (kmCoreNapiAvailable) {
        val lo = wasmKmBlackBoxU64(uLo32(value), uHi32(value))
        packU64(lo, wasmKmOut1())
    } else {
        value
    }
public actual fun kmUmulhU64(a: ULong, b: ULong): ULong =
    if (kmCoreNapiAvailable) {
        val lo = wasmKmUmulhU64(uLo32(a), uHi32(a), uLo32(b), uHi32(b))
        packU64(lo, wasmKmOut1())
    } else {
        KmLinearMemoryArena.umulhU64(a, b)
    }
public actual fun kmSmulhI64(a: Long, b: Long): Long =
    if (kmCoreNapiAvailable) {
        val lo = wasmKmSmulhI64(lo32(a), hi32(a), lo32(b), hi32(b))
        packI64(lo, wasmKmOut1())
    } else {
        KmLinearMemoryArena.smulhI64(a, b)
    }
public actual fun kmAtomicCxchgU128Pair(
    addr: Long,
    expLo: ULong,
    expHi: ULong,
    desLo: ULong,
    desHi: ULong,
): AtomicCxchgU128Outcome =
    if (kmCoreNapiAvailable) {
        val ok =
            wasmKmAtomicCxchgU128Pair(
                lo32(addr),
                hi32(addr),
                uLo32(expLo),
                uHi32(expLo),
                uLo32(expHi),
                uHi32(expHi),
                uLo32(desLo),
                uHi32(desLo),
                uLo32(desHi),
                uHi32(desHi),
            ) != 0
        AtomicCxchgU128Outcome(
            packU64(wasmKmOut0(), wasmKmOut1()),
            packU64(wasmKmOut2(), wasmKmOut3()),
            ok,
        )
    } else {
        KmLinearMemoryArena.atomicCxchgU128Pair(addr, expLo, expHi, desLo, desHi)
    }

public actual fun kmSimdFmaF32x4(dstAddr: Long, aAddr: Long, bAddr: Long, cAddr: Long) {
    if (kmCoreNapiAvailable) wasmKmSimdFmaF32x4(lo32(dstAddr), hi32(dstAddr), lo32(aAddr), hi32(aAddr), lo32(bAddr), hi32(bAddr), lo32(cAddr), hi32(cAddr)) else KmLinearMemoryArena.simdFmaF32x4(dstAddr, aAddr, bAddr, cAddr)
}
public actual fun kmSimdDotF32(aAddr: Long, bAddr: Long, len: Long): Float =
    if (kmCoreNapiAvailable) wasmKmSimdDotF32(lo32(aAddr), hi32(aAddr), lo32(bAddr), hi32(bAddr), lo32(len), hi32(len)).toFloat() else KmLinearMemoryArena.simdDotF32(aAddr, bAddr, len)
""".trimStart()
    }

    public fun renderKmCoreWasmWasiActualsKotlin(coreNamespace: String): String =
        """
package $coreNamespace.intrinsics

public actual fun kmLoadU8(addr: Long): UByte = KmLinearMemoryArena.loadU8(addr)
public actual fun kmLoadU16(addr: Long): UShort = KmLinearMemoryArena.loadU16(addr)
public actual fun kmLoadU32(addr: Long): UInt = KmLinearMemoryArena.loadU32(addr)
public actual fun kmLoadU64(addr: Long): ULong = KmLinearMemoryArena.loadU64(addr)
public actual fun kmLoadF32(addr: Long): Float = KmLinearMemoryArena.loadF32(addr)
public actual fun kmLoadF64(addr: Long): Double = KmLinearMemoryArena.loadF64(addr)

public actual fun kmStoreU8(addr: Long, value: UByte): Unit = KmLinearMemoryArena.storeU8(addr, value)
public actual fun kmStoreU16(addr: Long, value: UShort): Unit = KmLinearMemoryArena.storeU16(addr, value)
public actual fun kmStoreU32(addr: Long, value: UInt): Unit = KmLinearMemoryArena.storeU32(addr, value)
public actual fun kmStoreU64(addr: Long, value: ULong): Unit = KmLinearMemoryArena.storeU64(addr, value)
public actual fun kmStoreF32(addr: Long, value: Float): Unit = KmLinearMemoryArena.storeF32(addr, value)
public actual fun kmStoreF64(addr: Long, value: Double): Unit = KmLinearMemoryArena.storeF64(addr, value)

public actual fun kmVolatileLoadU8(addr: Long): UByte = KmLinearMemoryArena.loadU8(addr)
public actual fun kmVolatileLoadU16(addr: Long): UShort = KmLinearMemoryArena.loadU16(addr)
public actual fun kmVolatileLoadU32(addr: Long): UInt = KmLinearMemoryArena.loadU32(addr)
public actual fun kmVolatileLoadU64(addr: Long): ULong = KmLinearMemoryArena.loadU64(addr)

public actual fun kmVolatileStoreU8(addr: Long, value: UByte): Unit = KmLinearMemoryArena.storeU8(addr, value)
public actual fun kmVolatileStoreU16(addr: Long, value: UShort): Unit = KmLinearMemoryArena.storeU16(addr, value)
public actual fun kmVolatileStoreU32(addr: Long, value: UInt): Unit = KmLinearMemoryArena.storeU32(addr, value)
public actual fun kmVolatileStoreU64(addr: Long, value: ULong): Unit = KmLinearMemoryArena.storeU64(addr, value)

public actual fun kmCopyNonoverlapping(dst: Long, src: Long, bytes: Long): Unit =
    KmLinearMemoryArena.copyNonoverlapping(dst, src, bytes)
public actual fun kmCopy(dst: Long, src: Long, bytes: Long): Unit =
    KmLinearMemoryArena.copy(dst, src, bytes)
public actual fun kmWriteBytes(dst: Long, value: UByte, bytes: Long): Unit =
    KmLinearMemoryArena.writeBytes(dst, value, bytes)
public actual fun kmCompareBytes(left: Long, right: Long, bytes: Long): Int =
    KmLinearMemoryArena.compareBytes(left, right, bytes)

public actual fun kmAlloc(size: Long, align: Long): Long = KmLinearMemoryArena.alloc(size, align)
public actual fun kmAllocZeroed(size: Long, align: Long): Long = KmLinearMemoryArena.allocZeroed(size, align)
public actual fun kmRealloc(ptr: Long, oldSize: Long, align: Long, newSize: Long): Long =
    KmLinearMemoryArena.realloc(ptr, oldSize, align, newSize)
public actual fun kmDealloc(ptr: Long, size: Long, align: Long): Unit = KmLinearMemoryArena.dealloc(ptr, size, align)

public actual fun kmAtomicFence(ord: Int) {}
public actual fun kmAtomicSignalFence(ord: Int) {}
public actual fun kmAtomicLoadU8(addr: Long, ord: Int): UByte = KmLinearMemoryArena.loadU8(addr)
public actual fun kmAtomicLoadU16(addr: Long, ord: Int): UShort = KmLinearMemoryArena.loadU16(addr)
public actual fun kmAtomicLoadU32(addr: Long, ord: Int): UInt = KmLinearMemoryArena.loadU32(addr)
public actual fun kmAtomicLoadU64(addr: Long, ord: Int): ULong = KmLinearMemoryArena.loadU64(addr)
public actual fun kmAtomicStoreU8(addr: Long, value: UByte, ord: Int): Unit = KmLinearMemoryArena.storeU8(addr, value)
public actual fun kmAtomicStoreU16(addr: Long, value: UShort, ord: Int): Unit = KmLinearMemoryArena.storeU16(addr, value)
public actual fun kmAtomicStoreU32(addr: Long, value: UInt, ord: Int): Unit = KmLinearMemoryArena.storeU32(addr, value)
public actual fun kmAtomicStoreU64(addr: Long, value: ULong, ord: Int): Unit = KmLinearMemoryArena.storeU64(addr, value)
public actual fun kmAtomicXchgU8(addr: Long, value: UByte, ord: Int): UByte = KmLinearMemoryArena.atomicXchgU8(addr, value)
public actual fun kmAtomicXchgU16(addr: Long, value: UShort, ord: Int): UShort = KmLinearMemoryArena.atomicXchgU16(addr, value)
public actual fun kmAtomicXchgU32(addr: Long, value: UInt, ord: Int): UInt = KmLinearMemoryArena.atomicXchgU32(addr, value)
public actual fun kmAtomicXchgU64(addr: Long, value: ULong, ord: Int): ULong = KmLinearMemoryArena.atomicXchgU64(addr, value)
public actual fun kmAtomicCxchgU8(addr: Long, expected: UByte, desired: UByte, succ: Int, fail: Int): AtomicCxchgU8Outcome =
    KmLinearMemoryArena.atomicCxchgU8(addr, expected, desired)
public actual fun kmAtomicCxchgU16(addr: Long, expected: UShort, desired: UShort, succ: Int, fail: Int): AtomicCxchgU16Outcome =
    KmLinearMemoryArena.atomicCxchgU16(addr, expected, desired)
public actual fun kmAtomicCxchgU32(addr: Long, expected: UInt, desired: UInt, succ: Int, fail: Int): AtomicCxchgU32Outcome =
    KmLinearMemoryArena.atomicCxchgU32(addr, expected, desired)
public actual fun kmAtomicCxchgU64(addr: Long, expected: ULong, desired: ULong, succ: Int, fail: Int): AtomicCxchgU64Outcome =
    KmLinearMemoryArena.atomicCxchgU64(addr, expected, desired)
public actual fun kmAtomicRmwU8(addr: Long, value: UByte, op: Int, ord: Int): UByte =
    KmLinearMemoryArena.atomicRmwU8(addr, value, op)
public actual fun kmAtomicRmwU16(addr: Long, value: UShort, op: Int, ord: Int): UShort =
    KmLinearMemoryArena.atomicRmwU16(addr, value, op)
public actual fun kmAtomicRmwU32(addr: Long, value: UInt, op: Int, ord: Int): UInt =
    KmLinearMemoryArena.atomicRmwU32(addr, value, op)
public actual fun kmAtomicRmwU64(addr: Long, value: ULong, op: Int, ord: Int): ULong =
    KmLinearMemoryArena.atomicRmwU64(addr, value, op)

public actual fun kmCtpopU64(value: ULong): UInt = KmLinearMemoryArena.ctpopU64(value)
public actual fun kmCtlzU64(value: ULong): UInt = KmLinearMemoryArena.ctlzU64(value)
public actual fun kmCttzU64(value: ULong): UInt = KmLinearMemoryArena.cttzU64(value)
public actual fun kmBswapU16(value: UShort): UShort = KmLinearMemoryArena.bswapU16(value)
public actual fun kmBswapU32(value: UInt): UInt = KmLinearMemoryArena.bswapU32(value)
public actual fun kmBswapU64(value: ULong): ULong = KmLinearMemoryArena.bswapU64(value)
public actual fun kmBitreverseU64(value: ULong): ULong = KmLinearMemoryArena.bitreverseU64(value)
public actual fun kmFmaf32(a: Float, b: Float, c: Float): Float = KmLinearMemoryArena.fmaf32(a, b, c)
public actual fun kmFmaf64(a: Double, b: Double, c: Double): Double = KmLinearMemoryArena.fmaf64(a, b, c)
public actual fun kmSqrtf32(x: Float): Float = KmLinearMemoryArena.sqrtf32(x)
public actual fun kmSqrtf64(x: Double): Double = KmLinearMemoryArena.sqrtf64(x)
public actual fun kmAbort(): Nothing = error("Rust intrinsic abort triggered")

public actual fun kmSpinLoopHint() {}
public actual fun kmBlackBoxU64(value: ULong): ULong = value
public actual fun kmUmulhU64(a: ULong, b: ULong): ULong = KmLinearMemoryArena.umulhU64(a, b)
public actual fun kmSmulhI64(a: Long, b: Long): Long = KmLinearMemoryArena.smulhI64(a, b)
public actual fun kmAtomicCxchgU128Pair(
    addr: Long,
    expLo: ULong,
    expHi: ULong,
    desLo: ULong,
    desHi: ULong,
): AtomicCxchgU128Outcome = KmLinearMemoryArena.atomicCxchgU128Pair(addr, expLo, expHi, desLo, desHi)
public actual fun kmSimdFmaF32x4(dstAddr: Long, aAddr: Long, bAddr: Long, cAddr: Long): Unit =
    KmLinearMemoryArena.simdFmaF32x4(dstAddr, aAddr, bAddr, cAddr)
public actual fun kmSimdDotF32(aAddr: Long, bAddr: Long, len: Long): Float =
    KmLinearMemoryArena.simdDotF32(aAddr, bAddr, len)
""".trimStart()

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
