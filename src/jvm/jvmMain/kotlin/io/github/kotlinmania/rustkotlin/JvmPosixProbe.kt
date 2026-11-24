package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.libc.unix.access
import io.github.kotlinmania.libc.unix.bsd.O_CREAT
import io.github.kotlinmania.libc.unix.bsd.O_EXCL
import io.github.kotlinmania.libc.unix.bsd.O_NOFOLLOW
import io.github.kotlinmania.libc.unix.bsd.O_TRUNC
import io.github.kotlinmania.libc.unix.bsd.O_WRONLY
import io.github.kotlinmania.libc.unix.bsd.apple.F_OK
import io.github.kotlinmania.libc.unix.bsd.apple.R_OK
import io.github.kotlinmania.libc.unix.bsd.apple.W_OK
import io.github.kotlinmania.libc.unix.free
import io.github.kotlinmania.libc.unix.realpath
import io.github.kotlinmania.rustlang.alloc.ffi.cstr.CString
import io.github.kotlinmania.rustlang.alloc.ffi.cstr.toStringLossy
import io.github.kotlinmania.rustlang.core.ffi.cstr.CStr
import io.github.kotlinmania.rustlang.core.ptr.ConstPtr
import io.github.kotlinmania.rustlang.core.ptr.MutPtr
import io.github.kotlinmania.rustlang.core.ptr.mutptr.castConst
import io.github.kotlinmania.rustlang.core.ptr.mutptr.isNull

private inline fun <R> withJvmCString(
    text: String,
    block: (ConstPtr) -> R,
): R = CString.new(text).unwrap().use { cstr -> block(cstr.asPtr()) }

internal object JvmPosixFileProbe : PosixTargetFileProbe(
    flagFOk = F_OK,
    flagROk = R_OK,
    flagWOk = W_OK,
    openCreateTruncFlags = O_WRONLY or O_CREAT or O_TRUNC or O_NOFOLLOW,
    openCreateExclFlags = O_WRONLY or O_CREAT or O_EXCL or O_NOFOLLOW,
) {
    override fun checkAccess(path: String, mode: Int): Boolean =
        withJvmCString(path) { pathPtr -> access(pathPtr, mode) == 0 }

    override fun resolveRealPath(path: String): String? {
        val resolved = withJvmCString(path) { pathPtr -> realpath(pathPtr, MutPtr(0L)) }
        if (resolved.isNull()) return null
        try {
            return CStr.fromPtr(resolved.castConst()).toStringLossy()
        } finally {
            free(resolved)
        }
    }
}
