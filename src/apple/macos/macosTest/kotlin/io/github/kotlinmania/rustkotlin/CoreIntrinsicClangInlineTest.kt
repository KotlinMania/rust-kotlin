package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.io.files.SystemFileSystem
import io.github.kotlinmania.io.files.resolve
import io.github.kotlinmania.io.files.writeString
import io.github.kotlinmania.libc.unix.getpid
import io.github.kotlinmania.libc.unix.system
import io.github.kotlinmania.rand.randomLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import io.github.kotlinmania.io.files.Path as KmPath

class CoreIntrinsicClangInlineTest {
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

    private fun runChecked(cmd: String, logFile: KmPath, label: String) {
        val rc = withCString("$cmd > \"$logFile\" 2>&1") { system(it) }
        if (rc != 0) {
            val out = if (fs.exists(logFile)) readInput(logFile.toString()) else "<empty>"
            error("$label failed (rc=$rc):\n$cmd\n$out")
        }
    }

    @Test
    fun inlineHeaderInlinesAtO2AndAarch64AssemblyExecutes128BitCasMulhAndNeonSimd() {
        val scratch = KmPath("build/test-scratch/kmcore-clang-${getpid()}-${randomLong().toULong()}")
        fs.createDirectories(scratch, mustCreate = false)
        val dir = fs.resolve(scratch)
        try {
            val companions =
                CoreIntrinsicFfiArtifacts
                    .emitAllKmCoreCompanionFiles("io.github.kotlinmania.rustlang.core")
                    .associateBy { it.relativePath }
            val companionNames =
                listOf(
                    "km_core_intrinsics_inline.h",
                    "km_core_llvm_asm.h",
                    "km_core_llvm_aarch64.S",
                    "kmcore_wrapper.c",
                )
            for (name in companionNames) {
                fs.writeString(dir.resolve(name), companions.getValue(name).content)
            }

            val probeC = dir.resolve("probe.c")
            fs.writeString(
                probeC,
                """
                #include <stdio.h>
                #include "km_core_intrinsics_inline.h"
                #include "km_core_llvm_asm.h"

                __attribute__((noinline))
                uint64_t hot_inline_probe(int64_t p) {
                    kmStoreU64(p, 0x0102030405060708ULL);
                    uint64_t v = kmLoadU64(p);
                    return v + (uint64_t)kmCtpopU64(v);
                }

                int main(void) {
                    int64_t buf = kmAlloc(64, 16);
                    if (buf == 0) return 2;
                    uint64_t hot = hot_inline_probe(buf);
                    if (hot != 0x0102030405060708ULL + 13ULL) return 3;

                    /* 128-bit CAS in km_core_llvm_aarch64.S */
                    kmStoreU64(buf, 11ULL);
                    kmStoreU64(buf + 8, 22ULL);
                    uint64_t out_lo = 0, out_hi = 0;
                    int ok1 = kmAtomicCxchgU128(buf, 11ULL, 22ULL, 33ULL, 44ULL, &out_lo, &out_hi);
                    if (ok1 != 1 || out_lo != 11ULL || out_hi != 22ULL) return 4;
                    if (kmLoadU64(buf) != 33ULL || kmLoadU64(buf + 8) != 44ULL) return 5;
                    int ok2 = kmAtomicCxchgU128(buf, 11ULL, 22ULL, 99ULL, 99ULL, &out_lo, &out_hi);
                    if (ok2 != 0 || out_lo != 33ULL || out_hi != 44ULL) return 6;

                    /* 128-bit widening multiply */
                    if (kmUmulhU64(0xFFFFFFFFFFFFFFFFULL, 2ULL) != 1ULL) return 7;
                    if (kmSmulhI64(-2LL, 2LL) != -1LL) return 8;

                    /* NEON dot product */
                    float a[4] = {1.0f, 2.0f, 3.0f, 4.0f};
                    float b[4] = {2.0f, 3.0f, 4.0f, 5.0f};
                    float dot = kmSimdDotF32((int64_t)(uintptr_t)a, (int64_t)(uintptr_t)b, 4);
                    if (dot != 40.0f) return 9;

                    kmSpinLoopHint();
                    uint64_t bb = kmBlackBoxU64(77ULL);
                    kmDealloc(buf, 64, 16);
                    printf("OK:%llu\n", (unsigned long long)bb);
                    return 0;
                }
                """.trimIndent(),
            )

            val probeObj = dir.resolve("probe.o")
            val asmObj = dir.resolve("asm.o")
            val wrapperObj = dir.resolve("wrapper.o")
            val probeExe = dir.resolve("probe_exe")
            val disasmLog = dir.resolve("disasm.txt")
            val runOut = dir.resolve("run_out.txt")

            runChecked(
                "/usr/bin/clang -O2 -Wall -Wextra -Werror -I\"$dir\" -c \"$probeC\" -o \"$probeObj\"",
                dir.resolve("clang_probe.log"),
                "clang -O2 probe.c",
            )
            runChecked(
                "/usr/bin/clang -O2 -Wall -Wextra -Werror -I\"$dir\" -c \"${dir.resolve("kmcore_wrapper.c")}\" -o \"$wrapperObj\"",
                dir.resolve("clang_wrapper.log"),
                "clang -O2 kmcore_wrapper.c",
            )
            runChecked(
                "/usr/bin/clang -O2 -Wall -Wextra -Werror -c \"${dir.resolve("km_core_llvm_aarch64.S")}\" -o \"$asmObj\"",
                dir.resolve("clang_asm.log"),
                "clang assemble km_core_llvm_aarch64.S",
            )
            runChecked(
                "/usr/bin/clang \"$probeObj\" \"$wrapperObj\" \"$asmObj\" -lm -o \"$probeExe\"",
                dir.resolve("clang_link.log"),
                "clang link probe_exe",
            )
            runChecked("\"$probeExe\"", runOut, "execute probe_exe")
            assertEquals("OK:77", readInput(runOut.toString()).trim())

            runChecked("/usr/bin/otool -tvV \"$probeObj\"", disasmLog, "otool -tvV probe.o")
            val disasm = readInput(disasmLog.toString())
            assertTrue(disasm.contains("_hot_inline_probe:"), "Expected _hot_inline_probe symbol in disassembly")
            assertFalse(disasm.contains("bl\t_kmLoadU64"), "kmLoadU64 must inline with zero branch calls:\n$disasm")
            assertFalse(disasm.contains("bl\t_kmStoreU64"), "kmStoreU64 must inline with zero branch calls:\n$disasm")
            assertFalse(disasm.contains("bl\t_kmCtpopU64"), "kmCtpopU64 must inline with zero branch calls:\n$disasm")
        } finally {
            deleteRecursively(dir)
        }
    }
}
