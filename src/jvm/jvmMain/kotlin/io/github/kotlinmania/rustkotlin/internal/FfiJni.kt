package io.github.kotlinmania.rustkotlin.internal

internal object FfiJni {
    init {
        System.loadLibrary("ffi_jni")
    }

    @JvmStatic
    external fun getpid(): Int
}
