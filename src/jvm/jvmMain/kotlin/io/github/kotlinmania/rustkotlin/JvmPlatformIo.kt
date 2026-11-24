package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.io.files.SystemPathSeparator

internal fun isJvmWindowsHost(): Boolean = SystemPathSeparator == '\\'

private fun activeJvmFileProbe(): TargetFileProbe =
    if (isJvmWindowsHost()) JvmWindowsFileProbe else JvmPosixFileProbe

public actual fun probeTargetKotlinFile(path: String): TargetKotlinFileDetails =
    activeJvmFileProbe().probe(path)

public actual fun describeVirtualKotlinFile(
    path: String,
    fileName: String,
    isDirectory: Boolean,
    isWritable: Boolean,
    sizeBytes: Long,
): TargetKotlinFileDetails =
    activeJvmFileProbe().describeVirtual(path, fileName, isDirectory, isWritable, sizeBytes)

public actual fun writeNew(path: String, text: String): Unit =
    activeJvmFileProbe().writeNew(path, text)
