package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.io.files.SystemFileSystem
import io.github.kotlinmania.io.files.readBytes
import io.github.kotlinmania.io.files.writeString
import io.github.kotlinmania.io.files.Path as KmPath

public data class TargetKotlinFileDetails(
    public val path: String,
    public val fileName: String,
    public val resolvedRealPath: String?,
    public val exists: Boolean,
    public val isRegularFile: Boolean,
    public val isDirectory: Boolean,
    public val isSymbolicLink: Boolean,
    public val isReadable: Boolean,
    public val isWritable: Boolean,
    public val isReadOnly: Boolean,
    public val sizeBytes: Long,
    public val posixAccessMask: Int,
    public val posixOpenFlags: Int,
    public val win32FileAttributes: UInt,
    public val win32CreationDisposition: UInt,
    public val win32DesiredAccess: UInt,
    public val win32ShareMode: UInt,
)

public expect fun probeTargetKotlinFile(path: String): TargetKotlinFileDetails

public expect fun describeVirtualKotlinFile(
    path: String,
    fileName: String,
    isDirectory: Boolean,
    isWritable: Boolean,
    sizeBytes: Long,
): TargetKotlinFileDetails

public expect fun writeNew(path: String, text: String)

internal fun ensureParentDirectories(filePath: String) {
    val parent = KmPath(filePath).parent ?: return
    if (!SystemFileSystem.exists(parent)) {
        SystemFileSystem.createDirectories(parent, mustCreate = false)
    }
}

internal fun readInput(path: String): String {
    val details = probeTargetKotlinFile(path)
    val kmPath = KmPath(path)
    require(details.exists && SystemFileSystem.metadataOrNull(kmPath)?.isRegularFile == true && details.isReadable) {
        "cannot open $path"
    }
    val bytes = SystemFileSystem.readBytes(kmPath)
    return bytes.decodeToString(throwOnInvalidSequence = true)
}

internal fun writeOverwrite(path: String, text: String) {
    ensureParentDirectories(path)
    val details = probeTargetKotlinFile(path)
    require(!details.isSymbolicLink && !details.isDirectory && details.isWritable) {
        "cannot overwrite $path (symbolicLink=${details.isSymbolicLink}, directory=${details.isDirectory}, writable=${details.isWritable})"
    }
    SystemFileSystem.writeString(KmPath(path), text)
}

internal inline fun isCaseInsensitiveCanonicalMatch(
    parentCanonical: String?,
    requestedFileName: String,
    actualCanonicalPath: String,
    resolveCanonical: (String) -> String?,
): Boolean {
    if (parentCanonical == null) return false
    val cleanParent = parentCanonical.trimEnd('/')
    val actualParent = actualCanonicalPath.substringBeforeLast('/', "")
    val actualFileName = actualCanonicalPath.substringAfterLast('/')
    if (actualParent != cleanParent) return false
    if (!actualFileName.equals(requestedFileName, ignoreCase = true)) return false
    val probeName = distinctCaseVariant(actualFileName, requestedFileName) ?: return false
    return resolveCanonical("$cleanParent/$probeName") == actualCanonicalPath
}

internal fun distinctCaseVariant(actualFileName: String, requestedFileName: String): String? {
    for (idx in actualFileName.indices.reversed()) {
        val ch = actualFileName[idx]
        val toggled =
            when {
                ch in 'a'..'z' -> (ch.code - 32).toChar()
                ch in 'A'..'Z' -> (ch.code + 32).toChar()
                else -> null
            } ?: continue
        val candidate = actualFileName.substring(0, idx) + toggled + actualFileName.substring(idx + 1)
        if (candidate != actualFileName && candidate != requestedFileName) {
            return candidate
        }
    }
    return null
}
