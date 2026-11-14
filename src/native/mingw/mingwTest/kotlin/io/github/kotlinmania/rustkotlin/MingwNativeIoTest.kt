package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.io.files.SystemFileSystem
import io.github.kotlinmania.io.files.resolve
import io.github.kotlinmania.rand.randomLong
import io.github.kotlinmania.windowssys.windows.win32.storage.filesystem.CREATE_ALWAYS
import io.github.kotlinmania.windowssys.windows.win32.storage.filesystem.CREATE_NEW
import io.github.kotlinmania.windowssys.windows.win32.storage.filesystem.FILE_ATTRIBUTE_DIRECTORY
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.github.kotlinmania.io.files.Path as KmPath

class MingwNativeIoTest {
    private val scratchDir = KmPath("build/test-scratch/mingw-io")

    private fun prepareScratchDir(): KmPath {
        SystemFileSystem.createDirectories(scratchDir, mustCreate = false)
        return SystemFileSystem.resolve(scratchDir)
    }

    @Test
    fun probeTargetKotlinFileReportsLowLevelWin32Details() {
        val base = prepareScratchDir()
        val suffix = randomLong().toULong().toString()
        val target = base.resolve("rust-kotlin-probe-$suffix.kt").toString()
        val initialContent = "package sample\npublic fun initial(): Int = 1\n"
        val updatedContent = "package sample\npublic fun updated(): Int = 2\n"
        try {
            val missingDetails = probeTargetKotlinFile(target)
            assertFalse(missingDetails.exists)
            assertNull(missingDetails.resolvedRealPath)
            assertEquals(CREATE_NEW, missingDetails.win32CreationDisposition)

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
            assertEquals(CREATE_ALWAYS, existingDetails.win32CreationDisposition)
            assertEquals(0u, existingDetails.win32FileAttributes and FILE_ATTRIBUTE_DIRECTORY)

            writeOverwrite(target, updatedContent)
            assertEquals(updatedContent, readInput(target))
        } finally {
            SystemFileSystem.delete(KmPath(target), mustExist = false)
        }
    }
}
