package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.io.files.SystemFileSystem
import io.github.kotlinmania.io.files.resolve
import io.github.kotlinmania.io.files.writeString
import io.github.kotlinmania.libc.unix.bsd.O_CREAT
import io.github.kotlinmania.libc.unix.bsd.O_EXCL
import io.github.kotlinmania.libc.unix.bsd.O_NOFOLLOW
import io.github.kotlinmania.libc.unix.bsd.O_TRUNC
import io.github.kotlinmania.libc.unix.bsd.O_WRONLY
import io.github.kotlinmania.libc.unix.getpid
import io.github.kotlinmania.libc.unix.symlink
import io.github.kotlinmania.libc.unix.unlink
import io.github.kotlinmania.rand.randomLong
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.github.kotlinmania.io.files.Path as KmPath

class PosixNativeIoTest {
    private val scratchDir = KmPath("build/test-scratch/posix-io")

    private fun prepareScratchDir(): KmPath {
        SystemFileSystem.createDirectories(scratchDir, mustCreate = false)
        return SystemFileSystem.resolve(scratchDir)
    }

    private fun createFixture(path: String, text: String) {
        SystemFileSystem.writeString(KmPath(path), text)
    }

    private fun deletePathIfExists(path: String) {
        withCString(path) { unlink(it) }
        SystemFileSystem.delete(KmPath(path), mustExist = false)
    }

    private fun createSymlink(target: String, linkPath: String) {
        val rc =
            withCString(target) { targetPtr ->
                withCString(linkPath) { linkPtr ->
                    symlink(targetPtr, linkPtr)
                }
            }
        check(rc == 0) {
            "symlink failed creating symlink $linkPath -> $target"
        }
    }

    @Test
    fun symlinkOutputAliasCannotOverwriteTheInput() {
        val base = prepareScratchDir()
        val suffix = "${getpid()}-${randomLong().toULong()}"
        val input = base.resolve("rust-kotlin-input-$suffix.rs").toString()
        val output = base.resolve("rust-kotlin-output-$suffix.kt").toString()
        val alias = base.resolve("rust-kotlin-alias-$suffix.kt").toString()
        val source = "pub fn choose(flag: bool) -> bool { !flag }"
        try {
            createFixture(input, source)
            runSingleFileCommand(arrayOf("translate", input, output), report = {})
            assertContains(readInput(output), "fun choose")
            assertEquals(source, readInput(input))
            createSymlink(input, alias)
            assertFailsWith<Exception> {
                runSingleFileCommand(arrayOf("translate", input, alias), report = {})
            }
            assertEquals(source, readInput(input))
            assertEquals(source, readInput(alias))
        } finally {
            deletePathIfExists(alias)
            deletePathIfExists(output)
            deletePathIfExists(input)
        }
    }

    @Test
    fun probeTargetKotlinFileReportsLowLevelPosixDetails() {
        val base = prepareScratchDir()
        val suffix = "${getpid()}-${randomLong().toULong()}"
        val target = base.resolve("rust-kotlin-probe-$suffix.kt").toString()
        val initialContent = "package sample\npublic fun initial(): Int = 1\n"
        val updatedContent = "package sample\npublic fun updated(): Int = 2\n"
        try {
            val missingDetails = probeTargetKotlinFile(target)
            assertFalse(missingDetails.exists)
            assertNull(missingDetails.resolvedRealPath)
            assertEquals(O_WRONLY or O_CREAT or O_EXCL or O_NOFOLLOW, missingDetails.posixOpenFlags)

            writeNew(target, initialContent)
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
            assertEquals(O_WRONLY or O_CREAT or O_TRUNC or O_NOFOLLOW, existingDetails.posixOpenFlags)

            writeOverwrite(target, updatedContent)
            assertEquals(updatedContent, readInput(target))
        } finally {
            deletePathIfExists(target)
        }
    }

    @Test
    fun probeTargetKotlinFileDoesNotMisclassifyCaseMismatchedPathAsSymlink() {
        val base = prepareScratchDir()
        val suffix = "${getpid()}-${randomLong().toULong()}"
        val upperTarget = base.resolve("CaseProbe$suffix.kt").toString()
        val lowerAlias = base.resolve("caseprobe$suffix.kt").toString()
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
            deletePathIfExists(upperTarget)
            deletePathIfExists(lowerAlias)
        }
    }
}
