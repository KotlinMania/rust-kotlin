@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.io.files.Path
import io.github.kotlinmania.io.files.SystemFileSystem
import io.github.kotlinmania.io.files.resolve
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

private object MingwWindowsFileProbe : WindowsTargetFileProbe(
    attrDirectory = FILE_ATTRIBUTE_DIRECTORY,
    attrNormal = FILE_ATTRIBUTE_NORMAL,
    attrReadOnly = FILE_ATTRIBUTE_READONLY,
    createAlways = CREATE_ALWAYS,
    createNew = CREATE_NEW,
    desiredAccess = FILE_GENERIC_READ or FILE_GENERIC_WRITE,
    shareMode = FILE_SHARE_READ or FILE_SHARE_WRITE,
) {
    override fun queryRawAttributes(path: String): UInt? {
        val raw = memScoped { GetFileAttributesW(path.wcstr.ptr.toLong()) }
        return if (raw == INVALID_FILE_ATTRIBUTES) null else raw
    }

    override fun isReparsePoint(rawAttrs: UInt): Boolean =
        (rawAttrs and FILE_ATTRIBUTE_REPARSE_POINT.toUInt()) != 0u

    override fun resolveRealPath(kmPath: Path, kmExists: Boolean): String =
        if (AreFileApisANSI() != 0 && kmExists) {
            SystemFileSystem.resolve(kmPath).toString()
        } else {
            kmPath.toAbsolutePath().toString()
        }

    override fun resolveParentExpectedPrefix(kmPath: Path): String? {
        val parentDir = kmPath.parent
        return if (parentDir != null && SystemFileSystem.exists(parentDir)) {
            SystemFileSystem.resolve(parentDir).toString().replace('\\', '/')
        } else {
            null
        }
    }

    override fun writeExclusiveText(path: String, text: String) {
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
}

public actual fun probeTargetKotlinFile(path: String): TargetKotlinFileDetails =
    MingwWindowsFileProbe.probe(path)

public actual fun describeVirtualKotlinFile(
    path: String,
    fileName: String,
    isDirectory: Boolean,
    isWritable: Boolean,
    sizeBytes: Long,
): TargetKotlinFileDetails =
    MingwWindowsFileProbe.describeVirtual(path, fileName, isDirectory, isWritable, sizeBytes)

public actual fun writeNew(path: String, text: String): Unit =
    MingwWindowsFileProbe.writeNew(path, text)
