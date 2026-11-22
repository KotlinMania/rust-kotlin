package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.rustlang.alloc.alloc.alloc
import io.github.kotlinmania.rustlang.alloc.alloc.dealloc
import io.github.kotlinmania.rustlang.alloc.ffi.cstr.CString
import io.github.kotlinmania.rustlang.core.alloc.layout.Layout
import io.github.kotlinmania.rustlang.core.intrinsics.kmStoreU8
import io.github.kotlinmania.rustlang.core.ptr.ConstPtr
import io.github.kotlinmania.rustlang.core.ptr.mutptr.castConst

internal inline fun <R> withCString(
    text: String,
    block: (ConstPtr) -> R,
): R = CString.new(text).unwrap().use { cstr -> block(cstr.asPtr()) }

internal inline fun <R> withUtf8Buffer(
    bytes: ByteArray,
    block: (ConstPtr) -> R,
): R {
    check(bytes.isNotEmpty()) { "withUtf8Buffer requires non-empty ByteArray" }
    val layout = Layout.fromSizeAlignUnchecked(bytes.size, 1)
    val buf = alloc(layout)
    try {
        for (index in bytes.indices) {
            kmStoreU8(buf.rawAddress + index.toLong(), bytes[index].toUByte())
        }
        return block(buf.castConst())
    } finally {
        dealloc(buf, layout)
    }
}

internal expect fun writeStderrBytes(
    ptr: ConstPtr,
    length: Int,
): Int
