package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.rustlang.core.ptr.ConstPtr
import kotlin.system.exitProcess

private fun reportFatalErrorAndExit(message: String): Nothing {
    val bytes = message.encodeToByteArray()
    if (bytes.isNotEmpty()) {
        withUtf8Buffer(bytes) { ptr ->
            var offset = 0
            while (offset < bytes.size) {
                val written = writeStderrBytes(ConstPtr(ptr.rawAddress + offset.toLong()), bytes.size - offset)
                if (written <= 0) break
                offset += written
            }
        }
    }
    exitProcess(2)
}

/** Native CLI entry point: deterministic Rust-to-Kotlin translator. */
public fun main(args: Array<String>) {
    try {
        runSingleFileCommand(args)
    } catch (error: Exception) {
        reportFatalErrorAndExit("rust-kotlin: ${error.message}\n")
    } finally {
        TranslationSession.clear()
    }
}
