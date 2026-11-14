package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.libc.windows.gnu.STDERR_FILENO
import io.github.kotlinmania.libc.windows.write
import io.github.kotlinmania.rustlang.core.ptr.ConstPtr

internal actual fun writeStderrBytes(
    ptr: ConstPtr,
    length: Int,
): Int = write(STDERR_FILENO, ptr, length.toUInt())
