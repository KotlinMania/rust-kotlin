package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.io.files.SystemPathSeparator

internal fun isJvmWindowsHost(): Boolean =
    SystemPathSeparator == '\\'

public actual fun probeTargetKotlinFile(path: String): TargetKotlinFileDetails =
    if (isJvmWindowsHost()) {
        probeTargetKotlinFileJvmWindows(path)
    } else {
        probeTargetKotlinFileJvmPosix(path)
    }

public actual fun describeVirtualKotlinFile(
    path: String,
    fileName: String,
    isDirectory: Boolean,
    isWritable: Boolean,
    sizeBytes: Long,
): TargetKotlinFileDetails =
    if (isJvmWindowsHost()) {
        describeVirtualKotlinFileJvmWindows(path, fileName, isDirectory, isWritable, sizeBytes)
    } else {
        describeVirtualKotlinFileJvmPosix(path, fileName, isDirectory, isWritable, sizeBytes)
    }

public actual fun writeNew(path: String, text: String) {
    if (isJvmWindowsHost()) {
        writeNewJvmWindows(path, text)
    } else {
        writeNewJvmPosix(path, text)
    }
}
