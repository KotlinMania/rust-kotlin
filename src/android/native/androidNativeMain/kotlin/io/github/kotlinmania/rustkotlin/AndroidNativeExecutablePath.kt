package io.github.kotlinmania.rustkotlin

internal actual fun resolvePosixRawExecutablePath(): String? =
    resolvePosixRealPathOrNull("/proc/self/exe")
