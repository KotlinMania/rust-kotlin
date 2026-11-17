package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.io.files.SystemFileSystem
import io.github.kotlinmania.io.files.resolve
import io.github.kotlinmania.rand.randomLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.github.kotlinmania.io.files.Path as KmPath

class JvmNativeIoTest {
    private val scratchDir = KmPath("build/test-scratch/jvm-io")

    private fun prepareScratchDir(): KmPath {
        SystemFileSystem.createDirectories(scratchDir, mustCreate = false)
        return SystemFileSystem.resolve(scratchDir)
    }

    @Test
    fun probeTargetKotlinFileAndExclusiveWriteRoundTrip() {
        val base = prepareScratchDir()
        val suffix = randomLong().toULong().toString()
        val target = base.resolve("rust-kotlin-jvm-probe-$suffix.kt").toString()
        val initialContent = "package sample\npublic fun initial(): Int = 1\n"
        val updatedContent = "package sample\npublic fun updated(): Int = 2\n"
        try {
            val missingDetails = probeTargetKotlinFile(target)
            assertFalse(missingDetails.exists)
            assertNull(missingDetails.resolvedRealPath)
            if (isJvmWindowsHost()) {
                assertTrue(missingDetails.win32CreationDisposition != 0u)
            } else {
                assertTrue(missingDetails.posixOpenFlags != 0)
            }

            writeNew(target, initialContent)
            assertFailsWith<Exception> {
                writeNew(target, updatedContent)
            }

            val existingDetails = probeTargetKotlinFile(target)
            assertTrue(existingDetails.exists)
            assertTrue(existingDetails.isRegularFile)
            assertFalse(existingDetails.isDirectory)
            assertFalse(existingDetails.isSymbolicLink)
            assertTrue(existingDetails.isReadable)
            assertTrue(existingDetails.isWritable)
            assertFalse(existingDetails.isReadOnly)
            assertNotNull(existingDetails.resolvedRealPath)
            assertEquals(initialContent.encodeToByteArray().size.toLong(), existingDetails.sizeBytes)

            writeOverwrite(target, updatedContent)
            assertEquals(updatedContent, readInput(target))
        } finally {
            SystemFileSystem.delete(KmPath(target), mustExist = false)
        }
    }

    @Test
    fun probeTargetKotlinFileHandlesCaseInsensitivePathsWithoutFalseSymlink() {
        val base = prepareScratchDir()
        val suffix = randomLong().toULong().toString()
        val upperTarget = base.resolve("JvmCaseProbe$suffix.kt").toString()
        val lowerAlias = base.resolve("jvmcaseprobe$suffix.kt").toString()
        val initialContent = "package sample\npublic fun initialCase(): Int = 1\n"
        val updatedContent = "package sample\npublic fun updatedCase(): Int = 2\n"
        try {
            writeNew(upperTarget, initialContent)
            val lowerDetails = probeTargetKotlinFile(lowerAlias)
            assertFalse(lowerDetails.isSymbolicLink)
            if (lowerDetails.exists) {
                assertTrue(lowerDetails.isRegularFile)
                writeOverwrite(lowerAlias, updatedContent)
                assertEquals(updatedContent, readInput(upperTarget))
            }
        } finally {
            SystemFileSystem.delete(KmPath(upperTarget), mustExist = false)
            SystemFileSystem.delete(KmPath(lowerAlias), mustExist = false)
        }
    }

    @Test
    fun discoverExternalCrateAbiFromDiskFindsMultiCrateSymbolsAndInvalidatesOnNewFiles() {
        val base = prepareScratchDir()
        val suffix = randomLong().toULong().toString()
        val repoRoot = base.resolve("multi-crate-$suffix")
        val coreRoot = repoRoot.resolve("src/common/commonMain/kotlin/io/github/kotlinmania/rustlang/core")
        val maybeUninitDir = coreRoot.resolve("mem/maybeuninit")
        val peekableDir = coreRoot.resolve("iter/adapters/peekable")
        val nonNullDir = coreRoot.resolve("ptr/nonnull")
        val ioDir = coreRoot.resolve("io")
        val allocBufferDir =
            repoRoot.resolve("src/common/commonMain/kotlin/io/github/kotlinmania/rustlang/alloc/io/buffered/bufreader/buffer")
        val rustKotlinPropsPath = repoRoot.resolve("rustkotlin.properties").toString()
        val maybeUninitPath = maybeUninitDir.resolve("MaybeUninit.kt").toString()
        val peekablePath = peekableDir.resolve("Peekable.kt").toString()
        val nonNullPath = nonNullDir.resolve("NonNull.kt").toString()
        val errorPath = ioDir.resolve("Error.kt").toString()
        val borrowedBufPath = ioDir.resolve("BorrowedBuf.kt").toString()
        val anchorPath = allocBufferDir.resolve("Buffer.kt").toString()
        val crateNamespaces = mapOf("core" to "io.github.kotlinmania.rustlang.core")
        try {
            listOf(maybeUninitDir, peekableDir, nonNullDir, ioDir, allocBufferDir).forEach { dir ->
                SystemFileSystem.createDirectories(dir, mustCreate = false)
            }
            writeOverwrite(
                rustKotlinPropsPath,
                "project.namespace=io.github.kotlinmania.rustlang\ncrate.core=io.github.kotlinmania.rustlang.core\n",
            )
            writeOverwrite(
                maybeUninitPath,
                "package io.github.kotlinmania.rustlang.core.mem.maybeuninit\npublic class MaybeUninit<T>\n",
            )
            writeOverwrite(
                peekablePath,
                "package io.github.kotlinmania.rustlang.core.iter.adapters.peekable\npublic class Peekable<I>\n",
            )
            writeOverwrite(
                nonNullPath,
                "package io.github.kotlinmania.rustlang.core.ptr.nonnull\npublic class NonNull<T>\n",
            )
            writeOverwrite(
                errorPath,
                "package io.github.kotlinmania.rustlang.core.io\npublic class ErrorKind\n",
            )
            writeOverwrite(
                anchorPath,
                "package io.github.kotlinmania.rustlang.alloc.io.buffered.bufreader.buffer\npublic class Buffer\n",
            )

            val firstScan = discoverExternalCrateAbiFromDisk(anchorPath, crateNamespaces)
            val firstCore = firstScan.symbolPackages["core"].orEmpty()
            assertEquals(
                listOf("io.github.kotlinmania.rustlang.core.mem.maybeuninit"),
                firstCore["MaybeUninit"],
            )
            assertEquals(
                listOf("io.github.kotlinmania.rustlang.core.iter.adapters.peekable"),
                firstCore["Peekable"],
            )
            assertEquals(
                listOf("io.github.kotlinmania.rustlang.core.ptr.nonnull"),
                firstCore["NonNull"],
            )
            assertEquals(
                listOf("io.github.kotlinmania.rustlang.core.io"),
                firstCore["ErrorKind"],
            )
            assertNull(firstCore["BorrowedBuf"])

            writeOverwrite(
                borrowedBufPath,
                "package io.github.kotlinmania.rustlang.core.io\npublic class BorrowedBuf\n",
            )

            val secondScan = discoverExternalCrateAbiFromDisk(anchorPath, crateNamespaces)
            val secondCore = secondScan.symbolPackages["core"].orEmpty()
            assertEquals(
                listOf("io.github.kotlinmania.rustlang.core.io"),
                secondCore["BorrowedBuf"],
            )
        } finally {
            listOf(
                borrowedBufPath,
                errorPath,
                nonNullPath,
                peekablePath,
                maybeUninitPath,
                anchorPath,
                rustKotlinPropsPath,
            ).forEach { path ->
                SystemFileSystem.delete(KmPath(path), mustExist = false)
            }
        }
    }
}
