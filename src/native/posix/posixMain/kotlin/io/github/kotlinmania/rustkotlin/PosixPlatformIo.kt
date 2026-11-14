package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.io.files.SystemFileSystem
import io.github.kotlinmania.libc.unix.access
import io.github.kotlinmania.libc.unix.bsd.O_CREAT
import io.github.kotlinmania.libc.unix.bsd.O_EXCL
import io.github.kotlinmania.libc.unix.bsd.O_NOFOLLOW
import io.github.kotlinmania.libc.unix.bsd.O_TRUNC
import io.github.kotlinmania.libc.unix.bsd.O_WRONLY
import io.github.kotlinmania.libc.unix.bsd.apple.F_OK
import io.github.kotlinmania.libc.unix.bsd.apple.R_OK
import io.github.kotlinmania.libc.unix.bsd.apple.W_OK
import io.github.kotlinmania.libc.unix.fclose
import io.github.kotlinmania.libc.unix.fopen
import io.github.kotlinmania.libc.unix.free
import io.github.kotlinmania.libc.unix.fwrite
import io.github.kotlinmania.libc.unix.readlink
import io.github.kotlinmania.libc.unix.realpath
import io.github.kotlinmania.rustlang.alloc.alloc.alloc
import io.github.kotlinmania.rustlang.alloc.alloc.dealloc
import io.github.kotlinmania.rustlang.core.alloc.Layout
import io.github.kotlinmania.rustlang.core.ffi.CStr
import io.github.kotlinmania.rustlang.core.ptr.ConstPtr
import io.github.kotlinmania.rustlang.core.ptr.MutPtr
import io.github.kotlinmania.io.files.Path as KmPath

private fun posixAccess(
    path: String,
    mode: Int,
): Boolean = withCString(path) { pathPtr -> access(pathPtr, mode) == 0 }

private fun resolvePosixRealPathOrNull(path: String): String? {
    val resolved = withCString(path) { pathPtr -> realpath(pathPtr, MutPtr(0L)) }
    if (resolved.isNull()) return null
    try {
        return CStr.fromPtr(resolved.castConst()).toStringLossy()
    } finally {
        free(resolved)
    }
}

private fun isPosixPathSymbolicLink(path: String): Boolean {
    val layout = Layout.fromSizeAlignUnchecked(1, 1)
    val buf = alloc(layout)
    try {
        return withCString(path) { pathPtr ->
            readlink(pathPtr, buf, 1uL) >= 0
        }
    } finally {
        dealloc(buf, layout)
    }
}

public actual fun probeTargetKotlinFile(path: String): TargetKotlinFileDetails {
    val normalizedPath = path.replace('\\', '/')
    val fileName = normalizedPath.substringAfterLast('/')
    val kmPath = KmPath(path)
    val kmMetadata = SystemFileSystem.metadataOrNull(kmPath)
    val kmExists = SystemFileSystem.exists(kmPath)

    val posixFOk = posixAccess(path, F_OK)
    val posixROk = posixAccess(path, R_OK)
    val posixWOk = posixAccess(path, W_OK)

    val exists = kmExists || posixFOk
    val resolvedRealPath = if (exists) resolvePosixRealPathOrNull(path) else null
    val isSymbolicLink = isPosixPathSymbolicLink(path)

    val isDirectory = !isSymbolicLink && (kmMetadata?.isDirectory == true)
    val isRegularFile = !isSymbolicLink && (kmMetadata?.isRegularFile == true)
    val isReadable = exists && posixROk
    val isWritable =
        if (!exists) {
            val parentPath = kmPath.parent?.toString()
            if (parentPath != null) posixAccess(parentPath, W_OK) else true
        } else {
            posixWOk
        }
    val isReadOnly = exists && !isWritable
    val sizeBytes = if (kmMetadata != null && kmMetadata.size >= 0L) kmMetadata.size else 0L

    val posixAccessMask =
        (if (exists) F_OK else 0) or
            (if (isReadable) R_OK else 0) or
            (if (exists && isWritable) W_OK else 0)
    val posixOpenFlags =
        if (exists) {
            O_WRONLY or O_CREAT or O_TRUNC or O_NOFOLLOW
        } else {
            O_WRONLY or O_CREAT or O_EXCL or O_NOFOLLOW
        }

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
        posixAccessMask = posixAccessMask,
        posixOpenFlags = posixOpenFlags,
        win32FileAttributes = 0u,
        win32CreationDisposition = 0u,
        win32DesiredAccess = 0u,
        win32ShareMode = 0u,
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
    val posixAccessMask = F_OK or R_OK or (if (isWritable) W_OK else 0)
    val posixOpenFlags = O_WRONLY or O_CREAT or O_TRUNC or O_NOFOLLOW
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
        posixAccessMask = posixAccessMask,
        posixOpenFlags = posixOpenFlags,
        win32FileAttributes = 0u,
        win32CreationDisposition = 0u,
        win32DesiredAccess = 0u,
        win32ShareMode = 0u,
    )
}

public actual fun writeNew(path: String, text: String) {
    ensureParentDirectories(path)
    val details = probeTargetKotlinFile(path)
    require(!details.exists && !details.isSymbolicLink) {
        "cannot exclusively create $path (posixFlags=0x${details.posixOpenFlags.toString(16)})"
    }
    val file =
        withCString(path) { pathPtr ->
            withCString("wx") { modePtr ->
                fopen(pathPtr, modePtr)
            }
        }
    if (file.isNull()) error("cannot exclusively create $path")
    var complete = false
    try {
        val bytes = text.encodeToByteArray()
        if (bytes.isNotEmpty()) {
            withUtf8Buffer(bytes) { bufPtr ->
                var offset = 0
                while (offset < bytes.size) {
                    val count =
                        fwrite(
                            ConstPtr(bufPtr.rawAddress + offset.toLong()),
                            1uL,
                            (bytes.size - offset).toULong(),
                            file,
                        )
                    check(count > 0uL && count <= (bytes.size - offset).toULong()) {
                        "output may be partial at $path"
                    }
                    offset += count.toInt()
                }
            }
        }
        complete = true
    } finally {
        val result = fclose(file)
        check(result == 0 || !complete) { "failed to close output $path" }
    }
}
