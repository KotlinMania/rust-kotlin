package io.github.kotlinmania.rustkotlin

import platform.Foundation.NSBundle

internal actual fun resolvePosixRawExecutablePath(): String? =
    NSBundle.mainBundle.executablePath
