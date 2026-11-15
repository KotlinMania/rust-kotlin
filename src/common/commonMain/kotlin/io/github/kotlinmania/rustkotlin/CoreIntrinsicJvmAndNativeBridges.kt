package io.github.kotlinmania.rustkotlin

internal object CoreIntrinsicJvmAndNativeBridges {
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

${CoreIntrinsicMathAndBitSpecs.renderOutcomeDataClasses().trimEnd()}

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

${CoreIntrinsicMathAndBitSpecs.renderExpectDeclarations().trimEnd()}

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
import kotlinx.cinterop.LongVar
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

${CoreIntrinsicMathAndBitSpecs.renderNativeActuals(cinteropPackage).trimEnd()}
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

${CoreIntrinsicMathAndBitSpecs.renderJniCFunctions().trimEnd()}
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
${CoreIntrinsicMathAndBitSpecs.renderJvmExternalDeclarations().trimEnd()}
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

${CoreIntrinsicMathAndBitSpecs.renderJvmActuals(jniObjectName).trimEnd()}
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
}
