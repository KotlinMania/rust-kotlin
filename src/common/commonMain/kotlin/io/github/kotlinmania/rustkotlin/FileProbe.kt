package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.io.files.FileMetadata
import io.github.kotlinmania.io.files.SystemFileSystem
import io.github.kotlinmania.io.files.readBytes
import io.github.kotlinmania.io.files.resolve
import io.github.kotlinmania.io.files.toAbsolutePath
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

internal abstract class TargetFileProbe {
    abstract fun probe(path: String): TargetKotlinFileDetails

    abstract fun describeVirtual(
        path: String,
        fileName: String,
        isDirectory: Boolean,
        isWritable: Boolean,
        sizeBytes: Long,
    ): TargetKotlinFileDetails

    protected abstract fun exclusiveFailureSuffix(details: TargetKotlinFileDetails): String

    protected open fun writeExclusiveText(path: String, text: String) {
        SystemFileSystem.writeString(KmPath(path), text)
    }

    fun writeNew(path: String, text: String) {
        ensureParentDirectories(path)
        val details = probe(path)
        require(!details.exists && !details.isSymbolicLink) {
            "cannot exclusively create $path (${exclusiveFailureSuffix(details)})"
        }
        writeExclusiveText(path, text)
    }

    protected fun metadataSizeOrZero(metadata: FileMetadata?): Long =
        if (metadata != null && metadata.size >= 0L) metadata.size else 0L
}

internal abstract class PosixTargetFileProbe(
    private val flagFOk: Int,
    private val flagROk: Int,
    private val flagWOk: Int,
    private val openCreateTruncFlags: Int,
    private val openCreateExclFlags: Int,
) : TargetFileProbe() {
    protected abstract fun checkAccess(path: String, mode: Int): Boolean

    protected abstract fun resolveRealPath(path: String): String?

    protected open fun isSymbolicLink(
        path: String,
        kmPath: KmPath,
        fileName: String,
        exists: Boolean,
        resolvedRealPath: String?,
    ): Boolean {
        if (!exists) return false
        val parentReal = kmPath.parent?.let { resolveRealPath(it.toString()) }
        val expectedRealInParent = if (parentReal != null) "${parentReal.trimEnd('/')}/$fileName" else null
        return resolvedRealPath != null &&
            expectedRealInParent != null &&
            resolvedRealPath != expectedRealInParent &&
            !isCaseInsensitiveCanonicalMatch(parentReal, fileName, resolvedRealPath, ::resolveRealPath)
    }

    override fun probe(path: String): TargetKotlinFileDetails {
        val fileName = path.replace('\\', '/').substringAfterLast('/')
        val kmPath = KmPath(path)
        val kmMetadata = SystemFileSystem.metadataOrNull(kmPath)
        val exists = SystemFileSystem.exists(kmPath) || checkAccess(path, flagFOk)
        val posixROk = checkAccess(path, flagROk)
        val posixWOk = checkAccess(path, flagWOk)
        val resolvedRealPath = if (exists) resolveRealPath(path) else null
        val symbolicLink = isSymbolicLink(path, kmPath, fileName, exists, resolvedRealPath)
        val isDirectory = !symbolicLink && (kmMetadata?.isDirectory == true)
        val isRegularFile = !symbolicLink && (kmMetadata?.isRegularFile == true)
        val isReadable = exists && posixROk
        val isWritable =
            if (!exists) {
                val parentPath = kmPath.parent?.toString()
                if (parentPath != null) checkAccess(parentPath, flagWOk) else true
            } else {
                posixWOk
            }
        val posixAccessMask =
            (if (exists) flagFOk else 0) or
                (if (isReadable) flagROk else 0) or
                (if (exists && isWritable) flagWOk else 0)
        return TargetKotlinFileDetails(
            path = path,
            fileName = fileName,
            resolvedRealPath = resolvedRealPath,
            exists = exists,
            isRegularFile = isRegularFile,
            isDirectory = isDirectory,
            isSymbolicLink = symbolicLink,
            isReadable = isReadable,
            isWritable = isWritable,
            isReadOnly = exists && !isWritable,
            sizeBytes = metadataSizeOrZero(kmMetadata),
            posixAccessMask = posixAccessMask,
            posixOpenFlags = if (exists) openCreateTruncFlags else openCreateExclFlags,
            win32FileAttributes = 0u,
            win32CreationDisposition = 0u,
            win32DesiredAccess = 0u,
            win32ShareMode = 0u,
        )
    }

    override fun describeVirtual(
        path: String,
        fileName: String,
        isDirectory: Boolean,
        isWritable: Boolean,
        sizeBytes: Long,
    ): TargetKotlinFileDetails =
        TargetKotlinFileDetails(
            path = path,
            fileName = fileName,
            resolvedRealPath = path,
            exists = true,
            isRegularFile = !isDirectory,
            isDirectory = isDirectory,
            isSymbolicLink = false,
            isReadable = true,
            isWritable = isWritable,
            isReadOnly = !isWritable,
            sizeBytes = sizeBytes,
            posixAccessMask = flagFOk or flagROk or (if (isWritable) flagWOk else 0),
            posixOpenFlags = openCreateTruncFlags,
            win32FileAttributes = 0u,
            win32CreationDisposition = 0u,
            win32DesiredAccess = 0u,
            win32ShareMode = 0u,
        )

    override fun exclusiveFailureSuffix(details: TargetKotlinFileDetails): String =
        "posixFlags=0x${details.posixOpenFlags.toString(16)}"
}

internal open class WindowsTargetFileProbe(
    private val attrDirectory: UInt,
    private val attrNormal: UInt,
    private val attrReadOnly: UInt,
    private val createAlways: UInt,
    private val createNew: UInt,
    private val desiredAccess: UInt,
    private val shareMode: UInt,
) : TargetFileProbe() {
    protected open fun queryRawAttributes(path: String): UInt? = null

    protected open fun isReparsePoint(rawAttrs: UInt): Boolean = false

    protected open fun resolveRealPath(kmPath: KmPath, kmExists: Boolean): String =
        SystemFileSystem.resolve(kmPath).toString()

    protected open fun resolveParentExpectedPrefix(kmPath: KmPath): String? {
        val parentDir = kmPath.parent
        return if (parentDir != null && SystemFileSystem.exists(parentDir)) {
            SystemFileSystem.resolve(parentDir).toString().replace('\\', '/')
        } else {
            kmPath
                .toAbsolutePath()
                .parent
                ?.toString()
                ?.replace('\\', '/')
        }
    }

    override fun probe(path: String): TargetKotlinFileDetails {
        val fileName = path.replace('\\', '/').substringAfterLast('/')
        val kmPath = KmPath(path)
        val kmMetadata = SystemFileSystem.metadataOrNull(kmPath)
        val kmExists = SystemFileSystem.exists(kmPath)
        val rawAttrs = queryRawAttributes(path)
        val exists = kmExists || rawAttrs != null
        val resolvedRealPath = if (!exists) null else resolveRealPath(kmPath, kmExists)
        val symbolicLink =
            if (!exists) {
                false
            } else if (rawAttrs != null && isReparsePoint(rawAttrs)) {
                true
            } else {
                val parentResolved = resolveParentExpectedPrefix(kmPath)
                val expectedResolved = if (parentResolved != null) "${parentResolved.trimEnd('/')}/$fileName" else null
                val actualResolved = resolvedRealPath?.replace('\\', '/')
                actualResolved != null && expectedResolved != null && !actualResolved.equals(expectedResolved, ignoreCase = true)
            }
        val attrIsDir = rawAttrs != null && (rawAttrs and attrDirectory) != 0u
        val isDirectory = !symbolicLink && (attrIsDir || kmMetadata?.isDirectory == true)
        val isRegularFile =
            if (rawAttrs != null) {
                !symbolicLink && !isDirectory && exists
            } else {
                !symbolicLink && (kmMetadata?.isRegularFile == true)
            }
        val isReadable = if (rawAttrs != null) exists else exists && kmMetadata != null
        val isWritable =
            if (!exists) {
                val parentDir = kmPath.parent
                parentDir == null || SystemFileSystem.exists(parentDir)
            } else if (rawAttrs != null) {
                (rawAttrs and attrReadOnly) == 0u
            } else {
                kmMetadata != null
            }
        val isReadOnly = exists && !isWritable
        val win32FileAttributes =
            rawAttrs
                ?: (
                    (if (isDirectory) attrDirectory else attrNormal) or
                        (if (isReadOnly) attrReadOnly else 0u)
                )
        return TargetKotlinFileDetails(
            path = path,
            fileName = fileName,
            resolvedRealPath = resolvedRealPath,
            exists = exists,
            isRegularFile = isRegularFile,
            isDirectory = isDirectory,
            isSymbolicLink = symbolicLink,
            isReadable = isReadable,
            isWritable = isWritable,
            isReadOnly = isReadOnly,
            sizeBytes = metadataSizeOrZero(kmMetadata),
            posixAccessMask = 0,
            posixOpenFlags = 0,
            win32FileAttributes = win32FileAttributes,
            win32CreationDisposition = if (exists) createAlways else createNew,
            win32DesiredAccess = desiredAccess,
            win32ShareMode = shareMode,
        )
    }

    override fun describeVirtual(
        path: String,
        fileName: String,
        isDirectory: Boolean,
        isWritable: Boolean,
        sizeBytes: Long,
    ): TargetKotlinFileDetails {
        val isReadOnly = !isWritable
        val win32FileAttributes =
            (if (isDirectory) attrDirectory else attrNormal) or
                (if (isReadOnly) attrReadOnly else 0u)
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
            win32CreationDisposition = createAlways,
            win32DesiredAccess = desiredAccess,
            win32ShareMode = shareMode,
        )
    }

    override fun exclusiveFailureSuffix(details: TargetKotlinFileDetails): String =
        "win32Disposition=${details.win32CreationDisposition}"
}

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
