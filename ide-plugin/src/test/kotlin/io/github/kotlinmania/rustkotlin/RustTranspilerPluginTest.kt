package io.github.kotlinmania.rustkotlin

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.kotlinmania.io.files.SystemPathSeparator

class RustTranspilerPluginTest : BasePlatformTestCase() {
    override fun tearDown() {
        try {
            project.getService(RustTranspilerService::class.java)?.overwriteConfirmationHandler = null
        } finally {
            super.tearDown()
        }
    }

    fun testActionRegisteredInPluginXml() {
        val action =
            ActionManager
                .getInstance()
                .getAction("io.github.kotlinmania.rust-transpiler.ConvertRustToKotlin")
        assertNotNull("ConvertRustToKotlinAction should be registered in plugin.xml", action)
        assertTrue(action is ConvertRustToKotlinAction)
    }

    fun testServiceRejectsModuleRootFilesAndComputesTargetName() {
        val service = project.getService(RustTranspilerService::class.java)
        assertNotNull(service)
        assertEquals("TokenStream.kt", service.computeTargetKotlinFileName("token_stream.rs"))

        val libFile = myFixture.tempDirFixture.createFile("src/lib.rs", "pub mod token_stream;\n")
        val modFile = myFixture.tempDirFixture.createFile("src/sub/mod.rs", "pub mod inner;\n")
        val rustFile =
            myFixture.tempDirFixture.createFile(
                "src/token_stream.rs",
                "pub fn answer() -> i32 {\n    42\n}\n",
            )

        assertFalse(service.isTranspilableRustFile(libFile))
        assertFalse(service.isTranspilableRustFile(modFile))
        assertTrue(service.isTranspilableRustFile(rustFile))
    }

    fun testConvertRustToKotlinActionGeneratesSiblingKtFile() {
        val rustFile =
            myFixture.tempDirFixture.createFile(
                "src/kotlin/io/github/kotlinmania/sample/math_ops.rs",
                "pub fn add(a: i32, b: i32) -> i32 {\n    a + b\n}\n",
            )

        val action = ConvertRustToKotlinAction()
        val dataContext =
            SimpleDataContext
                .builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(CommonDataKeys.VIRTUAL_FILE_ARRAY, arrayOf(rustFile))
                .build()
        val presentation = Presentation()
        val event =
            AnActionEvent.createEvent(
                dataContext,
                presentation,
                ActionPlaces.PROJECT_VIEW_POPUP,
                ActionUiKind.POPUP,
                null,
            )

        action.update(event)
        assertTrue("Action should be enabled and visible for math_ops.rs", presentation.isEnabledAndVisible)

        action.actionPerformed(event)

        val generatedKt = rustFile.parent.findChild("MathOps.kt")
        assertNotNull("Expected sibling MathOps.kt to be created", generatedKt)
        val kotlinText = VfsUtilCore.loadText(generatedKt!!)
        assertTrue(kotlinText.contains("package io.github.kotlinmania.sample"))
        assertTrue(kotlinText.contains("public fun add(a: Int, b: Int): Int"))
    }

    fun testExistingKotlinFileRequiresOverwriteConfirmationAndRespectsCancel() {
        val existingContent = "// Existing handcrafted Kotlin file\npackage io.github.kotlinmania.sample\n"
        val existingKt =
            myFixture.tempDirFixture.createFile(
                "src/kotlin/io/github/kotlinmania/sample/MathOps.kt",
                existingContent,
            )
        val rustFile =
            myFixture.tempDirFixture.createFile(
                "src/kotlin/io/github/kotlinmania/sample/math_ops.rs",
                "pub fn add(a: i32, b: i32) -> i32 {\n    a + b\n}\n",
            )

        val service = project.getService(RustTranspilerService::class.java)
        var capturedDetails: TargetKotlinFileDetails? = null
        service.overwriteConfirmationHandler = { details ->
            capturedDetails = details
            false
        }

        val resultWhenCancelled = service.transpileVirtualFile(rustFile)
        assertNull("Expected null when user declines overwriting existing .kt file", resultWhenCancelled)
        val details = checkNotNull(capturedDetails) { "Expected overwrite confirmation handler to be invoked" }
        assertEquals("MathOps.kt", details.fileName)
        assertTrue(details.exists)
        assertTrue(details.isRegularFile)
        assertTrue(details.isWritable)
        assertFalse(details.isReadOnly)
        assertEquals(existingContent.encodeToByteArray().size.toLong(), details.sizeBytes)
        if (SystemPathSeparator == '\\') {
            assertTrue(details.win32CreationDisposition != 0u)
            assertTrue(details.win32FileAttributes != 0u)
        } else {
            assertTrue(details.posixOpenFlags != 0)
            assertTrue(details.posixAccessMask != 0)
        }
        assertEquals(existingContent, VfsUtilCore.loadText(existingKt))

        service.overwriteConfirmationHandler = { details ->
            capturedDetails = details
            true
        }
        val resultWhenConfirmed = service.transpileVirtualFile(rustFile)
        assertNotNull("Expected VirtualFile when user confirms overwriting existing .kt file", resultWhenConfirmed)
        val updatedText = VfsUtilCore.loadText(existingKt)
        assertTrue(updatedText.contains("public fun add(a: Int, b: Int): Int"))
    }

    fun testGradlePropertiesConfiguresPackageAndImportsWithoutHardcoding() {
        val gradlePropsFile =
            myFixture.tempDirFixture.createFile(
                "gradle.properties",
                """
                project.group=org.example.customgroup
                project.namespace=org.example.customgroup.mycrate
                """.trimIndent(),
            )
        val rustFile =
            myFixture.tempDirFixture.createFile(
                "tmp/mycrate/src/arbitrary.rs",
                """
                mod impl_arbitrary {
                    use arbitrary::{Arbitrary, Result, Unstructured};
                    pub fn check() -> i32 { 1 }
                }
                """.trimIndent(),
            )

        val service = project.getService(RustTranspilerService::class.java)
        val props = service.loadGradleProperties(gradlePropsFile)
        val generatedKt = checkNotNull(service.transpileVirtualFile(rustFile, props))
        val kotlinText = VfsUtilCore.loadText(generatedKt)

        assertTrue(kotlinText.contains("package org.example.customgroup.mycrate"))
        assertTrue(kotlinText.contains("import org.example.customgroup.arbitrary.Arbitrary"))
        assertFalse(kotlinText.contains("io.github.kotlinmania"))
    }

    fun testPerFolderPackagePropertiesOverridesGradleProperties() {
        val gradlePropsFile =
            myFixture.tempDirFixture.createFile(
                "gradle.properties",
                """
                project.group=io.github.kotlinmania
                project.namespace=io.github.kotlinmania.indexmap
                """.trimIndent(),
            )
        myFixture.tempDirFixture.createFile(
            "tmp/indexmap/src/map/package.properties",
            """
            package=io.github.kotlinmania.indexmap.custommap
            crate.arbitrary=org.custom.arbitrary
            """.trimIndent(),
        )
        val rustFile =
            myFixture.tempDirFixture.createFile(
                "tmp/indexmap/src/map/slice.rs",
                """
                use arbitrary::Arbitrary;
                pub fn check() -> i32 { 1 }
                """.trimIndent(),
            )

        val service = project.getService(RustTranspilerService::class.java)
        val props = service.loadGradleProperties(gradlePropsFile)
        val generatedKt = checkNotNull(service.transpileVirtualFile(rustFile, props))
        val kotlinText = VfsUtilCore.loadText(generatedKt)

        assertTrue(kotlinText.contains("package io.github.kotlinmania.indexmap.custommap"))
        assertTrue(kotlinText.contains("import org.custom.arbitrary.Arbitrary"))
    }

    fun testRightClickDirectoryRecursivelyTranslatesRustFilesToNeighboringKtFiles() {
        myFixture.tempDirFixture.createFile(
            "gradle.properties",
            """
            project.group=io.github.kotlinmania
            project.namespace=io.github.kotlinmania.serdejson
            """.trimIndent(),
        )
        val libRs =
            myFixture.tempDirFixture.createFile(
                "tmp/serde_json/src/lib.rs",
                "pub mod error;\npub mod io;\npub mod lexical;\n",
            )
        val errorRs =
            myFixture.tempDirFixture.createFile(
                "tmp/serde_json/src/error.rs",
                "pub enum Category {\n    Io,\n    Syntax,\n}\n",
            )
        val ioModRs =
            myFixture.tempDirFixture.createFile(
                "tmp/serde_json/src/io/mod.rs",
                "pub mod core;\n",
            )
        val ioCoreRs =
            myFixture.tempDirFixture.createFile(
                "tmp/serde_json/src/io/core.rs",
                "pub struct Error;\nimpl Error {\n    pub(crate) fn new() -> Error {\n        Error\n    }\n}\n",
            )
        val lexicalNumRs =
            myFixture.tempDirFixture.createFile(
                "tmp/serde_json/src/lexical/num.rs",
                """
                pub trait AsPrimitive {
                    fn as_u32(self) -> u32;
                }
                macro_rules! as_primitive_impl {
                    (${'$'}(${'$'}ty:ident)*) => {
                        ${'$'}(
                            impl AsPrimitive for ${'$'}ty {
                                fn as_u32(self) -> u32 {
                                    self as u32
                                }
                            }
                        )*
                    };
                }
                as_primitive_impl! { u32 u64 }
                """.trimIndent(),
            )

        val srcDir = libRs.parent
        assertTrue(srcDir.isDirectory)

        val action = ConvertRustToKotlinAction()
        val dataContext =
            SimpleDataContext
                .builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(CommonDataKeys.VIRTUAL_FILE_ARRAY, arrayOf(srcDir))
                .build()
        val presentation = Presentation()
        val event =
            AnActionEvent.createEvent(
                dataContext,
                presentation,
                ActionPlaces.PROJECT_VIEW_POPUP,
                ActionUiKind.POPUP,
                null,
            )

        action.update(event)
        assertTrue("Action should be enabled for directory containing .rs files", presentation.isEnabledAndVisible)
        assertEquals("Convert Rust Directory to Kotlin Files", presentation.text)

        action.actionPerformed(event)

        assertNull("lib.rs must never produce Lib.kt", srcDir.findChild("Lib.kt"))
        assertNull("io/mod.rs must never produce Mod.kt", ioModRs.parent.findChild("Mod.kt"))

        val errorKt = checkNotNull(errorRs.parent.findChild("Error.kt"))
        val errorText = VfsUtilCore.loadText(errorKt)
        assertTrue(errorText.contains("// port-lint: source error.rs"))
        assertTrue(errorText.contains("package io.github.kotlinmania.serdejson"))
        assertTrue(errorText.contains("public sealed class Category"))
        assertTrue(errorText.contains("public data object Io : Category()"))
        assertTrue(errorText.contains("public data object Syntax : Category()"))

        val ioCoreKt = checkNotNull(ioCoreRs.parent.findChild("Core.kt"))
        val ioCoreText = VfsUtilCore.loadText(ioCoreKt)
        assertTrue(ioCoreText.contains("// port-lint: source io/core.rs"))
        assertTrue(ioCoreText.contains("package io.github.kotlinmania.serdejson.io"))
        assertTrue(ioCoreText.contains("return Error()"))

        val lexicalNumKt = checkNotNull(lexicalNumRs.parent.findChild("Num.kt"))
        val lexicalNumText = VfsUtilCore.loadText(lexicalNumKt)
        assertTrue(lexicalNumText.contains("// port-lint: source lexical/num.rs"))
        assertTrue(lexicalNumText.contains("package io.github.kotlinmania.serdejson.lexical"))
        assertTrue(lexicalNumText.contains("public fun UInt.asU32(): UInt"))
        assertTrue(lexicalNumText.contains("public fun ULong.asU32(): UInt"))
    }

    fun testCrateLevelMacroUseDiscoveryExpandsLibcStructsInVirtualDirectory() {
        myFixture.tempDirFixture.createFile(
            "gradle.properties",
            """
            project.group=io.github.kotlinmania
            project.name=libc-kotlin
            project.namespace=io.github.kotlinmania.libc
            """.trimIndent(),
        )
        val libRs =
            myFixture.tempDirFixture.createFile(
                "tmp/libc/src/lib.rs",
                """
                #[macro_use]
                mod macros;
                pub mod unix;
                """.trimIndent(),
            )
        val macrosRs =
            myFixture.tempDirFixture.createFile(
                "tmp/libc/src/macros.rs",
                """
                macro_rules! s {
                    (${'$'}(
                        ${'$'}(#[${'$'}attr:meta])*
                        ${'$'}pub:vis ${'$'}t:ident ${'$'}i:ident { ${'$'}(${'$'}field:tt)* }
                    )*) => (${'$'}(
                        s!(it: ${'$'}(#[${'$'}attr])* ${'$'}pub ${'$'}t ${'$'}i { ${'$'}(${'$'}field)* });
                    )*);
                    (it: ${'$'}(#[${'$'}attr:meta])* ${'$'}pub:vis struct ${'$'}i:ident { ${'$'}(${'$'}field:tt)* }) => (
                        #[repr(C)]
                        ${'$'}(#[${'$'}attr])*
                        ${'$'}pub struct ${'$'}i { ${'$'}(${'$'}field)* }
                    );
                }
                """.trimIndent(),
            )
        val schedRs =
            myFixture.tempDirFixture.createFile(
                "tmp/libc/src/unix/sched.rs",
                """
                use crate::off_t;
                use crate::prelude::*;

                s! {
                    pub struct sched_param {
                        pub sched_priority: i32,
                        pub sched_offset: off_t,
                    }

                    // Not actually used, IPC calls just return ENOSYS
                    pub struct ipc_perm {
                        pub seq: u16,
                    }
                }
                """.trimIndent(),
            )

        val transpiler = project.getService(RustTranspilerService::class.java)
        transpiler.overwriteConfirmationHandler = { true }
        val generated = transpiler.transpileDirectoryRecursively(schedRs.parent)

        assertNull("lib.rs must never produce Lib.kt", libRs.parent.findChild("Lib.kt"))
        assertNull("transpiling unix/ alone must not write into parent src/", libRs.parent.findChild("Macros.kt"))
        val macrosKt = checkNotNull(transpiler.transpileVirtualFile(macrosRs))
        val macrosText = VfsUtilCore.loadText(macrosKt)
        assertTrue(macrosText.contains("// port-lint: source macros.rs"))
        assertTrue(macrosText.contains("const val S_TEMPLATE: String"))
        assertTrue(macrosText.contains("internal fun s("))
        assertTrue(macrosText.contains("when {"))
        val schedKt = checkNotNull(schedRs.parent.findChild("Sched.kt"))
        assertTrue(generated.contains(schedKt))
        val schedText = VfsUtilCore.loadText(schedKt)
        assertTrue(schedText.contains("// port-lint: source unix/sched.rs"))
        assertTrue(schedText.contains("package io.github.kotlinmania.libc.unix"))
        assertTrue(schedText.contains("import io.github.kotlinmania.libc.*"))
        assertTrue(schedText.contains("import io.github.kotlinmania.libc.OffT"))
        assertFalse(schedText.contains("libc.prelude"))
        assertTrue(schedText.contains("public open class SchedParam(public var schedPriority: Int, public var schedOffset: OffT)"))
        assertTrue(schedText.contains("// Not actually used, IPC calls just return ENOSYS\npublic open class IpcPerm(public var seq: UShort)"))
    }

    fun testInlineCAndExternCRustFileEmitsCompanionCInteropFilesInVirtualDirectory() {
        myFixture.tempDirFixture.createFile(
            "gradle.properties",
            """
            project.group=io.github.kotlinmania
            project.name=libc-kotlin
            project.namespace=io.github.kotlinmania.libc
            """.trimIndent(),
        )
        val stdlibRs =
            myFixture.tempDirFixture.createFile(
                "tmp/libc/src/new/qurt/stdlib.rs",
                """
                // Header: `stdlib.h`
                extern "C" {
                    pub fn abs(i: i32) -> i32;
                    pub fn abort() -> !;
                }
                """.trimIndent(),
            )

        val transpiler = project.getService(RustTranspilerService::class.java)
        val generatedKt = checkNotNull(transpiler.transpileVirtualFile(stdlibRs))
        val ktText = VfsUtilCore.loadText(generatedKt)
        assertTrue(ktText.contains("public expect fun abs(i: Int): Int"))
        assertTrue(ktText.contains("public expect fun abort(): Nothing"))

        val defFile = checkNotNull(stdlibRs.parent.findChild("Stdlib.def"))
        val headerFile = checkNotNull(stdlibRs.parent.findChild("stdlib_wrapper.h"))
        val cSourceFile = checkNotNull(stdlibRs.parent.findChild("stdlib_wrapper.c"))
        val actualsFile = checkNotNull(stdlibRs.parent.findChild("StdlibNativeActuals.kt"))

        assertTrue(VfsUtilCore.loadText(defFile).contains("headers = stdlib_wrapper.h"))
        assertTrue(VfsUtilCore.loadText(headerFile).contains("int32_t libcStdlibAbs(int32_t i);"))
        assertTrue(VfsUtilCore.loadText(headerFile).contains("void libcStdlibAbort(void);"))
        assertTrue(VfsUtilCore.loadText(cSourceFile).contains("#include <stdlib.h>"))
        assertTrue(VfsUtilCore.loadText(actualsFile).contains("public actual fun abort(): Nothing"))
    }
}
