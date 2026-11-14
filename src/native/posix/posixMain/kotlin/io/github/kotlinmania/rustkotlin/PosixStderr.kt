package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.libc.new.common.posix.unistd.STDERR_FILENO
import io.github.kotlinmania.libc.unix.write
import io.github.kotlinmania.rustlang.core.ptr.ConstPtr

internal actual fun writeStderrBytes(
    ptr: ConstPtr,
    length: Int,
): Int = write(STDERR_FILENO, ptr, length.toULong()).toInt()
