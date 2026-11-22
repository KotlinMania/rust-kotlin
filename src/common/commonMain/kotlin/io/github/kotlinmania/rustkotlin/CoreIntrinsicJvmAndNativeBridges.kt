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

${KmExpectBridgeEmitter.renderDeclarations()}

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

public interface KmForgettableResource : AutoCloseable {
    public fun disarmCleaner()
}

public expect class KmResourceCleaner internal constructor() : KmForgettableResource {
    public fun cleanNow()
    public fun disarm()
    public override fun disarmCleaner()
    public override fun close()
}

public expect fun kmRegisterResourceCleaner(owner: Any, cleanup: () -> Unit): KmResourceCleaner
""".trimStart()
    }

    public fun renderKmCoreNativeActualsKotlin(coreNamespace: String, cinteropPackage: String): String =
        """
@file:OptIn(ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package $coreNamespace.intrinsics

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value

${KmNativeBridgeEmitter(cinteropPackage).renderDeclarations()}

${CoreIntrinsicMathAndBitSpecs.renderNativeActuals(cinteropPackage).trimEnd()}

internal class KmNativeCleanState(
    private val cleanup: () -> Unit,
) {
    private val state = kotlin.concurrent.atomics.AtomicInt(0)

    fun disarm() {
        state.compareAndSet(0, 1)
    }

    fun clean() {
        if (state.compareAndSet(0, 2)) {
            cleanup()
        }
    }
}

public actual class KmResourceCleaner internal actual constructor() : KmForgettableResource {
    private var cleanState: KmNativeCleanState? = null
    private var nativeCleaner: kotlin.native.ref.Cleaner? = null

    internal fun attach(state: KmNativeCleanState, cleaner: kotlin.native.ref.Cleaner) {
        this.cleanState = state
        this.nativeCleaner = cleaner
    }

    public actual fun disarm() {
        if (nativeCleaner == null) return
        val target = cleanState
        nativeCleaner = null
        cleanState = null
        target?.disarm()
    }

    public actual fun cleanNow() {
        if (nativeCleaner == null) return
        val target = cleanState
        nativeCleaner = null
        cleanState = null
        target?.clean()
    }

    public actual override fun disarmCleaner(): Unit = disarm()

    public actual override fun close(): Unit = cleanNow()
}

public actual fun kmRegisterResourceCleaner(owner: Any, cleanup: () -> Unit): KmResourceCleaner {
    val state = KmNativeCleanState(cleanup)
    val cleaner = kotlin.native.ref.createCleaner(state) { it.clean() }
    return KmResourceCleaner().apply { attach(state, cleaner) }
}
""".trimStart()

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

${KmJniCBridgeEmitter.renderDeclarations()}

${CoreIntrinsicMathAndBitSpecs.renderJniCFunctions().trimEnd()}
""".trimStart()
    }

    private fun renderKmCoreSharedJvmBridgeMethods(jniObjectName: String): String {
        val emitter = KmJvmBridgeEmitter(jniObjectName)
        return """
${emitter.renderExternalDeclarations()}
${CoreIntrinsicMathAndBitSpecs.renderJvmExternalDeclarations().trimEnd()}
}

${emitter.renderDeclarations()}

${CoreIntrinsicMathAndBitSpecs.renderJvmActuals(jniObjectName).trimEnd()}

internal class KmJvmCleanRef(
    referent: Any,
    queue: java.lang.ref.ReferenceQueue<Any>,
    private val activeRefs: java.util.concurrent.ConcurrentHashMap<KmJvmCleanRef, Boolean>,
    private val cleanup: () -> Unit,
) : java.lang.ref.PhantomReference<Any>(referent, queue) {
    private val state = java.util.concurrent.atomic.AtomicInteger(0)

    fun disarm() {
        if (state.compareAndSet(0, 1)) {
            activeRefs.remove(this)
            clear()
        }
    }

    fun clean() {
        if (state.compareAndSet(0, 2)) {
            activeRefs.remove(this)
            clear()
            cleanup()
        }
    }
}

private object KmJvmCleanerRegistry {
    private val queue = java.lang.ref.ReferenceQueue<Any>()
    private val activeRefs = java.util.concurrent.ConcurrentHashMap<KmJvmCleanRef, Boolean>()
    private val started = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun ensureStarted() {
        if (started.compareAndSet(false, true)) {
            val worker =
                java.lang.Thread(
                    {
                        while (true) {
                            val ref = queue.remove()
                            if (ref is KmJvmCleanRef) {
                                ref.clean()
                            }
                        }
                    },
                    "km-resource-cleaner",
                )
            worker.isDaemon = true
            worker.start()
        }
    }

    fun register(owner: Any, cleanup: () -> Unit): KmResourceCleaner {
        ensureStarted()
        val ref = KmJvmCleanRef(owner, queue, activeRefs, cleanup)
        activeRefs[ref] = true
        return KmResourceCleaner().apply { attach(ref) }
    }
}

public actual class KmResourceCleaner internal actual constructor() : KmForgettableResource {
    private var cleanRef: KmJvmCleanRef? = null

    internal fun attach(ref: KmJvmCleanRef) {
        cleanRef = ref
    }

    public actual fun disarm() {
        val ref = cleanRef
        cleanRef = null
        ref?.disarm()
    }

    public actual fun cleanNow() {
        val ref = cleanRef
        cleanRef = null
        ref?.clean()
    }

    public actual override fun disarmCleaner(): Unit = disarm()

    public actual override fun close(): Unit = cleanNow()
}

public actual fun kmRegisterResourceCleaner(owner: Any, cleanup: () -> Unit): KmResourceCleaner =
    KmJvmCleanerRegistry.register(owner, cleanup)
""".trimEnd()
    }

    public fun renderKmCoreJvmActualsKotlin(
        coreNamespace: String,
        jniLibName: String = "kmcore_jni",
        projectNamespace: String = coreNamespace.substringBeforeLast('.'),
        jniObjectName: String = "KmCoreJniBridge",
    ): String {
        val resourcePrefix = projectNamespace.replace('.', '/')
        val loaderBody = buildJniResourceLoaderBody(jniObjectName, jniLibName, resourcePrefix).trimEnd()
        return """
@file:OptIn(ExperimentalUnsignedTypes::class)

package $coreNamespace.intrinsics

internal object $jniObjectName {
    init {
        loadNativeLibrary()
    }

$loaderBody

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
