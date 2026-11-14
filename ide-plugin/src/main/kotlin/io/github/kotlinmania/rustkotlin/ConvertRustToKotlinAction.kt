package io.github.kotlinmania.rustkotlin

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement

class ConvertRustToKotlinAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val files = extractSelectedVirtualFiles(e)
        if (project == null || files.isEmpty()) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val service = project.getService(RustTranspilerService::class.java)
        val enabled = service.hasTranspilableRustFiles(files)
        e.presentation.isEnabledAndVisible = enabled
        if (enabled) {
            e.presentation.text =
                if (files.any { it.isDirectory }) {
                    MyMessageBundle.message("command.convert.rust.directory.to.kotlin")
                } else {
                    MyMessageBundle.message("command.convert.rust.to.kotlin")
                }
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val selectedFiles = extractSelectedVirtualFiles(e)
        if (selectedFiles.isEmpty()) return
        val service = project.getService(RustTranspilerService::class.java)
        val rustFiles = service.collectTranspilableRustFiles(selectedFiles)
        if (rustFiles.isEmpty()) return

        val gradleProperties = service.ensureGradlePropertiesConfigured(rustFiles.first())
        if (gradleProperties == null && !ApplicationManager.getApplication().isUnitTestMode) {
            return
        }

        val generatedKotlinFiles = service.transpileVirtualFiles(selectedFiles, gradleProperties)
        val editorManager = FileEditorManager.getInstance(project)
        generatedKotlinFiles.lastOrNull()?.let { lastFile ->
            editorManager.openFile(lastFile, true)
        }
    }

    private fun extractSelectedVirtualFiles(e: AnActionEvent): Array<VirtualFile> {
        val array = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        if (!array.isNullOrEmpty()) return array

        val single = e.getData(CommonDataKeys.VIRTUAL_FILE)
        if (single != null) return arrayOf(single)

        val psiElement: PsiElement? = e.getData(CommonDataKeys.PSI_ELEMENT)
        if (psiElement is PsiDirectory) {
            return arrayOf(psiElement.virtualFile)
        }
        val psiFile = psiElement?.containingFile?.virtualFile
        if (psiFile != null) return arrayOf(psiFile)

        val ideView = e.getData(LangDataKeys.IDE_VIEW)
        if (ideView != null) {
            val dirs = ideView.directories.mapNotNull { it.virtualFile }
            if (dirs.isNotEmpty()) return dirs.toTypedArray()
        }

        return emptyArray()
    }
}
