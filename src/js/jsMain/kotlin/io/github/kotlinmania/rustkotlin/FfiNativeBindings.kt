package io.github.kotlinmania.rustkotlin

@JsModule("@kotlinmania/ffi-native-bindings")
public external interface FfiNativeBindings {
    public fun getpid(): Int
}
