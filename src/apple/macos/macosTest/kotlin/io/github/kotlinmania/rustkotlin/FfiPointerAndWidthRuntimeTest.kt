package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.io.files.SystemFileSystem
import io.github.kotlinmania.io.files.resolve
import io.github.kotlinmania.io.files.writeString
import io.github.kotlinmania.libc.unix.getenv
import io.github.kotlinmania.libc.unix.getpid
import io.github.kotlinmania.libc.unix.system
import io.github.kotlinmania.rand.randomLong
import io.github.kotlinmania.rustlang.core.ffi.CStr
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import io.github.kotlinmania.io.files.Path as KmPath

class FfiPointerAndWidthRuntimeTest {
    private val fs = SystemFileSystem

    private fun deleteRecursively(target: KmPath) {
        val meta = fs.metadataOrNull(target) ?: return
        if (meta.isDirectory) {
            for (child in fs.list(target)) {
                deleteRecursively(child)
            }
        }
        fs.delete(target, mustExist = false)
    }

    private fun readEnvVar(name: String): String? {
        val ptr = withCString(name) { getenv(it) }
        if (ptr.isNull()) return null
        return CStr.fromPtr(ptr.castConst()).toStringLossy().takeIf { it.isNotBlank() }
    }

    private fun locateKonanBinDir(): KmPath {
        val explicitDataDir = readEnvVar("KONAN_DATA_DIR")
        val homeDir = readEnvVar("HOME")
        val konanRoot =
            when {
                explicitDataDir != null -> KmPath(explicitDataDir)
                homeDir != null -> KmPath("$homeDir/.konan")
                else -> error("Neither KONAN_DATA_DIR nor HOME is set")
            }
        val candidates =
            fs
                .list(konanRoot)
                .filter { entry ->
                    fs.metadataOrNull(entry)?.isDirectory == true &&
                        entry.name.startsWith("kotlin-native-prebuilt-macos-aarch64-")
                }.sortedBy { it.name }
        check(candidates.isNotEmpty()) { "No kotlin-native-prebuilt-macos-aarch64-* found in $konanRoot" }
        val preferred = candidates.lastOrNull { it.name.endsWith("-2.4.21") } ?: candidates.last()
        return preferred.resolve("bin")
    }

    private fun runShellChecked(cmd: String, logFile: KmPath, stepLabel: String) {
        val wrapped = "$cmd > \"$logFile\" 2>&1"
        val rc = withCString(wrapped) { system(it) }
        if (rc != 0) {
            val logOutput = if (fs.exists(logFile)) readInput(logFile.toString()) else "<no log output>"
            error("$stepLabel failed (rc=$rc):\nCommand: $cmd\nOutput:\n$logOutput")
        }
    }

    @Test
    fun generatedFfiCompilesAndPreservesCallerOwnedMutablePointersNullsAnd64BitSizeWidthAtRuntime() {
        val konanBin = locateKonanBinDir()
        val cinteropBin = konanBin.resolve("cinterop").toString()
        val konancBin = konanBin.resolve("konanc").toString()

        val scratchBase = KmPath("build/test-scratch/ffi-runtime-${getpid()}-${randomLong().toULong()}")
        fs.createDirectories(scratchBase, mustCreate = false)
        val rootDir = fs.resolve(scratchBase)

        try {
            verifyEndToEndPipeline(rootDir.resolve("raw"), cinteropBin, konancBin, rawAbi = true)
            verifyEndToEndPipeline(rootDir.resolve("mapped"), cinteropBin, konancBin, rawAbi = false)
        } finally {
            deleteRecursively(rootDir)
        }
    }

    private fun verifyEndToEndPipeline(
        workDir: KmPath,
        cinteropBin: String,
        konancBin: String,
        rawAbi: Boolean,
    ) {
        val rustDir = workDir.resolve("rust")
        val projDir = workDir.resolve("proj")
        val commonRtDir = projDir.resolve("src/common/commonMain/kotlin/io/github/kotlinmania/libc/rt")
        val cinteropDir = projDir.resolve("src/native/interop/cinterop")
        val nativeRtDir = projDir.resolve("src/native/nativeMain/kotlin/io/github/kotlinmania/libc/rt")
        fs.createDirectories(rustDir, mustCreate = false)
        fs.createDirectories(commonRtDir, mustCreate = false)
        fs.createDirectories(cinteropDir, mustCreate = false)
        fs.createDirectories(nativeRtDir, mustCreate = false)

        val rustFile = rustDir.resolve("ffi_rt.rs")
        val rustSource =
            """
            //! Header: `stdlib.h`
            //! Header: `string.h`
            c! {{
                static char* test_nullable_mut_str(int non_null) {
                    if (!non_null) return NULL;
                    return strdup("owned");
                }
                static const char* test_nullable_const_str(int non_null) {
                    return non_null ? "static_const" : NULL;
                }
                static size_t test_echo_count(size_t n) {
                    return n;
                }
            }}

            pub type c_char = i8;
            pub type c_void = u8;
            pub type size_t = usize;
            pub type count_alias_t = size_t;

            extern "C" {
                pub fn strdup(cs: *const c_char) -> *mut c_char;
                pub fn strndup(cs: *const c_char, n: count_alias_t) -> *mut c_char;
                pub fn free(p: *mut c_void);
                pub fn test_nullable_mut_str(non_null: i32) -> *mut c_char;
                pub fn test_nullable_const_str(non_null: i32) -> *const c_char;
                pub fn test_echo_count(n: count_alias_t) -> count_alias_t;
            }
            """.trimIndent()
        fs.writeString(rustFile, rustSource)

        val commonKtPath =
            projDir
                .resolve("src/common/commonMain/kotlin/io/github/kotlinmania/libc/rt/FfiRt.kt")
                .toString()
        val cliArgs =
            buildList {
                add("translate")
                add("--force")
                if (rawAbi) add("--raw-abi")
                add("--source-root")
                add(rustDir.toString())
                add("--target-pointer-width")
                add("64")
                add("--target-arch")
                add("aarch64")
                add("--target-os")
                add("macos")
                add("-n")
                add("io.github.kotlinmania.libc")
                add(rustFile.toString())
                add(commonKtPath)
            }.toTypedArray()
        val cliReport = mutableListOf<String>()
        runSingleFileCommand(cliArgs, report = { cliReport += it })

        val headerFile = cinteropDir.resolve("ffi_rt_wrapper.h")
        assertTrue(fs.exists(headerFile), "Missing $headerFile; CLI output:\n${cliReport.joinToString("\n")}")
        val cSourceFile = cinteropDir.resolve("ffi_rt_wrapper.c")
        val defFile = cinteropDir.resolve("FfiRt.def")
        val nativeActualsFile =
            projDir.resolve(
                "src/native/nativeMain/kotlin/io/github/kotlinmania/libc/rt/FfiRtNativeActuals.kt",
            )

        val headerText = readInput(headerFile.toString())
        val nativeActualsText = readInput(nativeActualsFile.toString())
        assertContains(headerText, "char* libcFfiRtStrdup(const char* cs);")
        assertContains(headerText, "char* libcFfiRtStrndup(const char* cs, uint64_t n);")
        assertFalse(nativeActualsText.contains("toKString"))
        assertFalse(nativeActualsText.contains("COpaquePointer"))

        // 1. Compile generated C wrapper with strict clang warnings-as-errors and archive static library
        val objFile = cinteropDir.resolve("ffi_rt_wrapper.o")
        val archiveFile = cinteropDir.resolve("ffi_rt_wrapper.a")
        runShellChecked(
            "/usr/bin/clang -Wall -Wextra -Werror -I\"$cinteropDir\" -c \"$cSourceFile\" -o \"$objFile\"",
            workDir.resolve("clang.log"),
            "clang compile wrapper ($rawAbi)",
        )
        runShellChecked(
            "/usr/bin/ar rcs \"$archiveFile\" \"$objFile\"",
            workDir.resolve("ar.log"),
            "ar archive wrapper ($rawAbi)",
        )

        // 2. Run Kotlin/Native cinterop on generated .def + header + static archive
        val klibBase = workDir.resolve("ffi_rt_cinterop")
        val klibFile = workDir.resolve("ffi_rt_cinterop.klib")
        runShellChecked(
            "\"$cinteropBin\" -def \"$defFile\" -compiler-option \"-I$cinteropDir\" -libraryPath \"$cinteropDir\" -target macos_arm64 -o \"$klibBase\"",
            workDir.resolve("cinterop.log"),
            "cinterop ($rawAbi)",
        )
        assertTrue(fs.exists(klibFile), "Expected cinterop klib at $klibFile")

        // 3. Compile generated commonMain + nativeMain actuals + runtime verification driver via konanc -Werror
        val resultOutFile = workDir.resolve("runtime_result.txt")
        val commonSupportFile = workDir.resolve("CommonSupport.kt")
        fs.writeString(
            commonSupportFile,
            """
            package io.github.kotlinmania.rustlang.core.ptr

            public class ConstPtr(
                public val rawAddress: Long,
                public val rawMeta: Long = 0L,
            ) {
                public fun isNull(): Boolean = rawAddress == 0L
            }

            public class MutPtr(
                public val rawAddress: Long,
                public val rawMeta: Long = 0L,
            ) {
                public fun isNull(): Boolean = rawAddress == 0L
            }
            """.trimIndent(),
        )

        val driverFile = workDir.resolve("DriverMain.kt")
        val driverCode =
            if (rawAbi) {
                """
                @file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

                import io.github.kotlinmania.libc.rt.free
                import io.github.kotlinmania.libc.rt.strdup
                import io.github.kotlinmania.libc.rt.strndup
                import io.github.kotlinmania.libc.rt.test_echo_count
                import io.github.kotlinmania.libc.rt.test_nullable_const_str
                import io.github.kotlinmania.libc.rt.test_nullable_mut_str
                import kotlinx.cinterop.ByteVar
                import kotlinx.cinterop.cstr
                import kotlinx.cinterop.memScoped
                import kotlinx.cinterop.set
                import kotlinx.cinterop.toCPointer
                import kotlinx.cinterop.toKString
                import kotlinx.cinterop.toLong
                import platform.posix.fclose
                import platform.posix.fopen
                import platform.posix.fputs

                fun main() {
                    memScoped {
                        val srcPtr = "kotlin".cstr.getPointer(this).toLong()
                        val dupPtr: Long = strdup(srcPtr)
                        check(dupPtr != 0L) { "strdup returned null" }
                        check(dupPtr != srcPtr) { "strdup aliased input pointer" }
                        val cptr = checkNotNull(dupPtr.toCPointer<ByteVar>())
                        cptr[0] = 'K'.code.toByte()
                        val mutated = cptr.toKString()
                        check(mutated == "Kotlin") { "Expected mutated 'Kotlin', got '${'$'}mutated'" }
                        free(dupPtr)

                        check(test_nullable_mut_str(0) == 0L) { "Expected 0L for NULL *mut c_char" }
                        val ownedPtr = test_nullable_mut_str(1)
                        check(ownedPtr != 0L) { "Expected non-zero *mut c_char" }
                        check(ownedPtr.toCPointer<ByteVar>()?.toKString() == "owned")
                        free(ownedPtr)

                        check(test_nullable_const_str(0) == 0L) { "Expected 0L for NULL *const c_char" }
                        val constPtr = test_nullable_const_str(1)
                        check(constPtr != 0L && constPtr.toCPointer<ByteVar>()?.toKString() == "static_const")

                        // (1uL shl 32) + 2uL == 4_294_967_298uL: low 32 bits are 2, so narrowing to int32_t/Int yields "ko"
                        val wideN: ULong = (1uL shl 32) + 2uL
                        val wideDupPtr: Long = strndup(srcPtr, wideN)
                        check(wideDupPtr != 0L) { "strndup returned null" }
                        val wideStr = wideDupPtr.toCPointer<ByteVar>()?.toKString()
                        check(wideStr == "kotlin") { "Width narrowed! Expected 'kotlin', got '${'$'}wideStr'" }
                        free(wideDupPtr)

                        val echoed: ULong = test_echo_count((1uL shl 33) + 7uL)
                        check(echoed == 8_589_934_599uL) { "Expected 8589934599uL, got ${'$'}echoed" }

                        val outFp = checkNotNull(fopen("${resultOutFile.toString().replace("\\", "\\\\")}", "w"))
                        fputs("OK:raw:${'$'}mutated:${'$'}wideStr:${'$'}echoed\n", outFp)
                        fclose(outFp)
                    }
                }
                """.trimIndent()
            } else {
                """
                @file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

                import io.github.kotlinmania.libc.rt.free
                import io.github.kotlinmania.libc.rt.strdup
                import io.github.kotlinmania.libc.rt.strndup
                import io.github.kotlinmania.libc.rt.testEchoCount
                import io.github.kotlinmania.libc.rt.testNullableConstStr
                import io.github.kotlinmania.libc.rt.testNullableMutStr
                import io.github.kotlinmania.rustlang.core.ptr.ConstPtr
                import io.github.kotlinmania.rustlang.core.ptr.MutPtr
                import kotlinx.cinterop.ByteVar
                import kotlinx.cinterop.cstr
                import kotlinx.cinterop.memScoped
                import kotlinx.cinterop.set
                import kotlinx.cinterop.toCPointer
                import kotlinx.cinterop.toKString
                import kotlinx.cinterop.toLong
                import platform.posix.fclose
                import platform.posix.fopen
                import platform.posix.fputs

                fun main() {
                    memScoped {
                        val srcAddr = "kotlin".cstr.getPointer(this).toLong()
                        val dupPtr: MutPtr = strdup(ConstPtr(srcAddr))
                        check(!dupPtr.isNull() && dupPtr.rawAddress != srcAddr) { "strdup returned null or aliased" }
                        val cptr = checkNotNull(dupPtr.rawAddress.toCPointer<ByteVar>())
                        cptr[0] = 'K'.code.toByte()
                        val mutated = cptr.toKString()
                        check(mutated == "Kotlin") { "Expected mutated 'Kotlin', got '${'$'}mutated'" }
                        free(MutPtr(dupPtr.rawAddress))

                        check(testNullableMutStr(0).isNull()) { "Expected 0L null for NULL *mut c_char" }
                        val ownedPtr = testNullableMutStr(1)
                        check(!ownedPtr.isNull() && ownedPtr.rawAddress.toCPointer<ByteVar>()?.toKString() == "owned")
                        free(MutPtr(ownedPtr.rawAddress))

                        check(testNullableConstStr(0).isNull()) { "Expected 0L null for NULL *const c_char" }
                        val constPtr = testNullableConstStr(1)
                        check(!constPtr.isNull() && constPtr.rawAddress.toCPointer<ByteVar>()?.toKString() == "static_const")

                        val wideN: ULong = (1uL shl 32) + 2uL
                        val wideDupPtr: MutPtr = strndup(ConstPtr(srcAddr), wideN)
                        check(!wideDupPtr.isNull()) { "strndup returned null" }
                        val wideStr = wideDupPtr.rawAddress.toCPointer<ByteVar>()?.toKString()
                        check(wideStr == "kotlin") { "Width narrowed! Expected 'kotlin', got '${'$'}wideStr'" }
                        free(MutPtr(wideDupPtr.rawAddress))

                        val echoed: ULong = testEchoCount((1uL shl 33) + 7uL)
                        check(echoed == 8_589_934_599uL) { "Expected 8589934599uL, got ${'$'}echoed" }

                        val outFp = checkNotNull(fopen("${resultOutFile.toString().replace("\\", "\\\\")}", "w"))
                        fputs("OK:mapped:${'$'}mutated:${'$'}wideStr:${'$'}echoed\n", outFp)
                        fclose(outFp)
                    }
                }
                """.trimIndent()
            }
        fs.writeString(driverFile, driverCode)

        val exeBase = workDir.resolve("ffi_rt_runner")
        val exeBin = workDir.resolve("ffi_rt_runner.kexe")
        val konancCmd =
            buildString {
                append('"').append(konancBin).append('"')
                append(" -Werror -Xreport-all-warnings -target macos_arm64 -produce program")
                append(" -Xmulti-platform")
                append(" -Xfragments=commonMain,nativeMain")
                append(" -Xfragment-refines=nativeMain:commonMain")
                append(" -Xfragment-sources=\"commonMain:$commonSupportFile\"")
                append(" -Xfragment-sources=\"commonMain:$commonKtPath\"")
                append(" -Xfragment-sources=\"nativeMain:$nativeActualsFile\"")
                append(" -Xfragment-sources=\"nativeMain:$driverFile\"")
                append(" \"$commonSupportFile\" \"$commonKtPath\" \"$nativeActualsFile\" \"$driverFile\"")
                append(" -l \"$klibFile\"")
                append(" -o \"$exeBase\"")
            }
        runShellChecked(konancCmd, workDir.resolve("konanc.log"), "konanc ($rawAbi)")
        val runnableExe = if (fs.exists(exeBin)) exeBin else exeBase
        assertTrue(fs.exists(runnableExe), "Expected compiled native binary at $runnableExe")

        // 4. Execute the compiled native binary and verify runtime output
        runShellChecked("\"$runnableExe\"", workDir.resolve("run.log"), "execute runtime binary ($rawAbi)")
        val resultLine = readInput(resultOutFile.toString()).trim()
        val expectedMode = if (rawAbi) "raw" else "mapped"
        assertEquals("OK:$expectedMode:Kotlin:kotlin:8589934599", resultLine)
    }
}
