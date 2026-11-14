@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.io.files.SystemFileSystem
import io.github.kotlinmania.io.files.toAbsolutePath
import io.github.kotlinmania.windowssys.windows.win32.foundation.CloseHandle
import io.github.kotlinmania.windowssys.windows.win32.foundation.INVALID_HANDLE_VALUE
import io.github.kotlinmania.windowssys.windows.win32.storage.filesystem.AreFileApisANSI
import io.github.kotlinmania.windowssys.windows.win32.storage.filesystem.CREATE_ALWAYS
import io.github.kotlinmania.windowssys.windows.win32.storage.filesystem.CREATE_NEW
import io.github.kotlinmania.windowssys.windows.win32.storage.filesystem.CreateFileW
import io.github.kotlinmania.windowssys.windows.win32.storage.filesystem.FILE_ATTRIBUTE_DIRECTORY
import io.github.kotlinmania.windowssys.windows.win32.storage.filesystem.FILE_ATTRIBUTE_NORMAL
import io.github.kotlinmania.windowssys.windows.win32.storage.filesystem.FILE_ATTRIBUTE_READONLY
import io.github.kotlinmania.windowssys.windows.win32.storage.filesystem.FILE_GENERIC_READ
import io.github.kotlinmania.windowssys.windows.win32.storage.filesystem.FILE_GENERIC_WRITE
import io.github.kotlinmania.windowssys.windows.win32.storage.filesystem.FILE_SHARE_READ
import io.github.kotlinmania.windowssys.windows.win32.storage.filesystem.FILE_SHARE_WRITE
import io.github.kotlinmania.windowssys.windows.win32.storage.filesystem.GetFileAttributesW
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toLong
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.cinterop.wcstr
import platform.windows.FILE_ATTRIBUTE_REPARSE_POINT
import platform.windows.INVALID_FILE_ATTRIBUTES
import platform.windows.WriteFile
import io.github.kotlinmania.io.files.Path as KmPath

public actual fun probeTargetKotlinFile(path: String): TargetKotlinFileDetails {
    val normalizedPath = path.replace('\\', '/')
    val fileName = normalizedPath.substringAfterLast('/')
    val kmPath = KmPath(path)
    val kmMetadata = SystemFileSystem.metadataOrNull(kmPath)
    val kmExists = SystemFileSystem.exists(kmPath)

    val rawAttrs =
        memScoped {
            GetFileAttributesW(path.wcstr.ptr.toLong())
        }
    val hasAttrs = rawAttrs != INVALID_FILE_ATTRIBUTES
    val exists = kmExists || hasAttrs

    val ansiActive = AreFileApisANSI() != 0
    val resolvedRealPath =
        if (!exists) {
            null
        } else if (ansiActive && kmExists) {
            SystemFileSystem.resolve(kmPath).toString()
        } else {
            kmPath.toAbsolutePath().toString()
        }

    val isReparsePoint = hasAttrs && (rawAttrs and FILE_ATTRIBUTE_REPARSE_POINT.toUInt()) != 0u
    val isSymbolicLink =
        if (!exists) {
            false
        } else if (isReparsePoint) {
            true
        } else {
            val parentDir = kmPath.parent
            val parentResolved =
                if (parentDir != null && SystemFileSystem.exists(parentDir)) {
                    SystemFileSystem.resolve(parentDir).toString().replace('\\', '/')
                } else {
                    null
                }
            val expectedResolved = if (parentResolved != null) "${parentResolved.trimEnd('/')}/$fileName" else null
            val actualResolved = resolvedRealPath?.replace('\\', '/')
            actualResolved != null && expectedResolved != null && !actualResolved.equals(expectedResolved, ignoreCase = true)
        }

    val attrDirectory = hasAttrs && (rawAttrs and FILE_ATTRIBUTE_DIRECTORY) != 0u
    val attrReadOnly = hasAttrs && (rawAttrs and FILE_ATTRIBUTE_READONLY) != 0u
    val isDirectory = !isSymbolicLink && (attrDirectory || kmMetadata?.isDirectory == true)
    val isRegularFile = !isSymbolicLink && !isDirectory && exists
    val isReadable = exists
    val isWritable =
        if (!exists) {
            val parentDir = kmPath.parent
            parentDir == null || SystemFileSystem.exists(parentDir)
        } else {
            !attrReadOnly
        }
    val isReadOnly = exists && !isWritable
    val sizeBytes = if (kmMetadata != null && kmMetadata.size >= 0L) kmMetadata.size else 0L

    val win32FileAttributes =
        if (hasAttrs) {
            rawAttrs
        } else {
            (if (isDirectory) FILE_ATTRIBUTE_DIRECTORY else FILE_ATTRIBUTE_NORMAL) or
                (if (isReadOnly) FILE_ATTRIBUTE_READONLY else 0u)
        }
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

public actual fun describeVirtualKotlinFile(
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

public actual fun writeNew(path: String, text: String) {
    ensureParentDirectories(path)
    val details = probeTargetKotlinFile(path)
    require(!details.exists && !details.isSymbolicLink) {
        "cannot exclusively create $path (win32Disposition=${details.win32CreationDisposition})"
    }
    val handle =
        memScoped {
            CreateFileW(
                lpfilename = path.wcstr.ptr.toLong(),
                dwdesiredaccess = FILE_GENERIC_WRITE,
                dwsharemode = 0u,
                lpsecurityattributes = 0L,
                dwcreationdisposition = CREATE_NEW,
                dwflagsandattributes = FILE_ATTRIBUTE_NORMAL,
                htemplatefile = 0L,
            )
        }
    check(handle != INVALID_HANDLE_VALUE && handle != 0L) {
        "cannot exclusively create $path"
    }
    var complete = false
    try {
        val bytes = text.encodeToByteArray()
        if (bytes.isNotEmpty()) {
            bytes.usePinned { pinned ->
                memScoped {
                    val written = alloc<UIntVar>()
                    var offset = 0
                    while (offset < bytes.size) {
                        val remaining = (bytes.size - offset).toUInt()
                        written.value = 0u
                        val ok = WriteFile(handle.toCPointer(), pinned.addressOf(offset), remaining, written.ptr, null)
                        val count = written.value.toInt()
                        check(ok != 0 && count > 0 && count <= bytes.size - offset) {
                            "output may be partial at $path"
                        }
                        offset += count
                    }
                }
            }
        }
        complete = true
    } finally {
        val closed = CloseHandle(handle)
        check(closed != 0 || !complete) { "failed to close output $path" }
    }
}
