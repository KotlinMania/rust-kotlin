package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.io.Source
import io.github.kotlinmania.io.files.Path
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
import io.github.kotlinmania.libc.unix.getenv
import io.github.kotlinmania.libc.unix.readlink
import io.github.kotlinmania.libc.unix.realpath
import io.github.kotlinmania.rustlang.alloc.alloc.alloc
import io.github.kotlinmania.rustlang.alloc.alloc.dealloc
import io.github.kotlinmania.rustlang.alloc.ffi.cstr.toStringLossy
import io.github.kotlinmania.rustlang.core.alloc.layout.Layout
import io.github.kotlinmania.rustlang.core.ffi.cstr.CStr
import io.github.kotlinmania.rustlang.core.ptr.ConstPtr
import io.github.kotlinmania.rustlang.core.ptr.MutPtr
import io.github.kotlinmania.rustlang.core.ptr.mutptr.castConst
import io.github.kotlinmania.rustlang.core.ptr.mutptr.isNull

private object NativePosixFileProbe : PosixTargetFileProbe(
    flagFOk = F_OK,
    flagROk = R_OK,
    flagWOk = W_OK,
    openCreateTruncFlags = O_WRONLY or O_CREAT or O_TRUNC or O_NOFOLLOW,
    openCreateExclFlags = O_WRONLY or O_CREAT or O_EXCL or O_NOFOLLOW,
) {
    override fun checkAccess(path: String, mode: Int): Boolean =
        withCString(path) { pathPtr -> access(pathPtr, mode) == 0 }

    override fun resolveRealPath(path: String): String? =
        resolvePosixRealPathOrNull(path)

    override fun isSymbolicLink(
        path: String,
        kmPath: Path,
        fileName: String,
        exists: Boolean,
        resolvedRealPath: String?,
    ): Boolean {
        val layout = Layout.fromSizeAlignUnchecked(1, 1)
        val buf = alloc(layout)
        try {
            return withCString(path) { pathPtr -> readlink(pathPtr, buf, 1uL) >= 0 }
        } finally {
            dealloc(buf, layout)
        }
    }

    override fun writeExclusiveText(path: String, text: String) {
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
}

public actual fun probeTargetKotlinFile(path: String): TargetKotlinFileDetails =
    NativePosixFileProbe.probe(path)

public actual fun describeVirtualKotlinFile(
    path: String,
    fileName: String,
    isDirectory: Boolean,
    isWritable: Boolean,
    sizeBytes: Long,
): TargetKotlinFileDetails =
    NativePosixFileProbe.describeVirtual(path, fileName, isDirectory, isWritable, sizeBytes)

public actual fun writeNew(path: String, text: String): Unit =
    NativePosixFileProbe.writeNew(path, text)

internal expect fun resolvePosixRawExecutablePath(): String?

internal fun resolvePosixRealPathOrNull(path: String): String? {
    val resolved = withCString(path) { pathPtr -> realpath(pathPtr, MutPtr(0L)) }
    if (resolved.isNull()) return null
    try {
        return CStr.fromPtr(resolved.castConst()).toStringLossy()
    } finally {
        free(resolved)
    }
}

internal actual fun readPlatformEnvironmentVariable(name: String): String? =
    withCString(name) { namePtr ->
        val raw = getenv(namePtr)
        if (raw.isNull()) {
            null
        } else {
            val text = CStr.fromPtr(raw.castConst()).toStringLossy().trim()
            text.ifEmpty { null }
        }
    }

internal actual fun resolvePlatformExecutablePath(): String? {
    val rawExe = resolvePosixRawExecutablePath() ?: readPlatformEnvironmentVariable("_") ?: return null
    return resolvePosixRealPathOrNull(rawExe) ?: rawExe
}

internal actual fun listPlatformPackagedRuleFiles(resourceDirPath: String): List<String> = emptyList()

internal actual fun openPlatformPackagedRuleSource(resourcePath: String): Source? = null
