package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.io.files.SystemFileSystem
import io.github.kotlinmania.io.files.toAbsolutePath
import io.github.kotlinmania.io.files.writeString
import io.github.kotlinmania.windowssys.windows.win32.storage.filesystem.CREATE_ALWAYS
import io.github.kotlinmania.windowssys.windows.win32.storage.filesystem.CREATE_NEW
import io.github.kotlinmania.windowssys.windows.win32.storage.filesystem.FILE_ATTRIBUTE_DIRECTORY
import io.github.kotlinmania.windowssys.windows.win32.storage.filesystem.FILE_ATTRIBUTE_NORMAL
import io.github.kotlinmania.windowssys.windows.win32.storage.filesystem.FILE_ATTRIBUTE_READONLY
import io.github.kotlinmania.windowssys.windows.win32.storage.filesystem.FILE_GENERIC_READ
import io.github.kotlinmania.windowssys.windows.win32.storage.filesystem.FILE_GENERIC_WRITE
import io.github.kotlinmania.windowssys.windows.win32.storage.filesystem.FILE_SHARE_READ
import io.github.kotlinmania.windowssys.windows.win32.storage.filesystem.FILE_SHARE_WRITE
import io.github.kotlinmania.io.files.Path as KmPath

internal fun probeTargetKotlinFileJvmWindows(path: String): TargetKotlinFileDetails {
    val normalizedPath = path.replace('\\', '/')
    val fileName = normalizedPath.substringAfterLast('/')
    val kmPath = KmPath(path)
    val kmMetadata = SystemFileSystem.metadataOrNull(kmPath)
    val exists = SystemFileSystem.exists(kmPath)

    val resolvedRealPath =
        if (!exists) {
            null
        } else {
            SystemFileSystem.resolve(kmPath).toString()
        }

    val isSymbolicLink =
        if (!exists) {
            false
        } else {
            val parentDir = kmPath.parent
            val parentResolved =
                if (parentDir != null && SystemFileSystem.exists(parentDir)) {
                    SystemFileSystem.resolve(parentDir).toString().replace('\\', '/')
                } else {
                    kmPath
                        .toAbsolutePath()
                        .parent
                        ?.toString()
                        ?.replace('\\', '/')
                }
            val expectedResolved = if (parentResolved != null) "${parentResolved.trimEnd('/')}/$fileName" else null
            val actualResolved = resolvedRealPath?.replace('\\', '/')
            actualResolved != null && expectedResolved != null && !actualResolved.equals(expectedResolved, ignoreCase = true)
        }

    val isDirectory = !isSymbolicLink && (kmMetadata?.isDirectory == true)
    val isRegularFile = !isSymbolicLink && (kmMetadata?.isRegularFile == true)
    val isReadable = exists && kmMetadata != null
    val isWritable =
        if (!exists) {
            val parentDir = kmPath.parent
            parentDir == null || SystemFileSystem.exists(parentDir)
        } else {
            kmMetadata != null
        }
    val isReadOnly = exists && !isWritable
    val sizeBytes = if (kmMetadata != null && kmMetadata.size >= 0L) kmMetadata.size else 0L

    val win32FileAttributes =
        (if (isDirectory) FILE_ATTRIBUTE_DIRECTORY else FILE_ATTRIBUTE_NORMAL) or
            (if (isReadOnly) FILE_ATTRIBUTE_READONLY else 0u)
    val win32CreationDisposition = if (exists) CREATE_ALWAYS else CREATE_NEW

    return TargetKotlinFileDetails(
        path = path,
        fileName = fileName,
        resolvedRealPath = resolvedRealPath,
        exists = exists,
        isRegularFile = isRegularFile,
        isDirectory = isDirectory,
        isSymbolicLink = isSymbolicLink,
        isReadable = isReadable,
        isWritable = isWritable,
        isReadOnly = isReadOnly,
        sizeBytes = sizeBytes,
        posixAccessMask = 0,
        posixOpenFlags = 0,
        win32FileAttributes = win32FileAttributes,
        win32CreationDisposition = win32CreationDisposition,
        win32DesiredAccess = FILE_GENERIC_READ or FILE_GENERIC_WRITE,
        win32ShareMode = FILE_SHARE_READ or FILE_SHARE_WRITE,
    )
}

internal fun describeVirtualKotlinFileJvmWindows(
    path: String,
    fileName: String,
    isDirectory: Boolean,
    isWritable: Boolean,
    sizeBytes: Long,
): TargetKotlinFileDetails {
    val isReadOnly = !isWritable
    val win32FileAttributes =
        (if (isDirectory) FILE_ATTRIBUTE_DIRECTORY else FILE_ATTRIBUTE_NORMAL) or
            (if (isReadOnly) FILE_ATTRIBUTE_READONLY else 0u)
    return TargetKotlinFileDetails(
        path = path,
        fileName = fileName,
        resolvedRealPath = path,
        exists = true,
        isRegularFile = !isDirectory,
        isDirectory = isDirectory,
        isSymbolicLink = false,
        isReadable = true,
        isWritable = isWritable,
        isReadOnly = isReadOnly,
        sizeBytes = sizeBytes,
        posixAccessMask = 0,
        posixOpenFlags = 0,
        win32FileAttributes = win32FileAttributes,
        win32CreationDisposition = CREATE_ALWAYS,
        win32DesiredAccess = FILE_GENERIC_READ or FILE_GENERIC_WRITE,
        win32ShareMode = FILE_SHARE_READ or FILE_SHARE_WRITE,
    )
}

internal fun writeNewJvmWindows(path: String, text: String) {
    ensureParentDirectories(path)
    val details = probeTargetKotlinFileJvmWindows(path)
    require(!details.exists && !details.isSymbolicLink) {
        "cannot exclusively create $path (win32Disposition=${details.win32CreationDisposition})"
    }
    SystemFileSystem.writeString(KmPath(path), text)
}
