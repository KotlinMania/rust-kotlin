package io.github.kotlinmania.rustkotlin

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.components.BorderLayoutPanel
import javax.swing.JButton
import javax.swing.JPanel

class MyToolWindowFactory :
    ToolWindowFactory,
    DumbAware {
    override fun shouldBeAvailable(project: Project): Boolean = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val myToolWindow = MyToolWindow(project)
        val content = ContentFactory.getInstance().createContent(myToolWindow.getContent(), null, false)
        toolWindow.contentManager.addContent(content)
    }

    class MyToolWindow(
        private val project: Project,
    ) {
        private val content =
            BorderLayoutPanel(8, 8).apply {
                val topBar =
                    JPanel().apply {
                        add(JBLabel(MyMessageBundle.message("toolwindow.MyToolWindow.header.label")))
                    }

                val rustInput =
                    JBTextArea(12, 40).apply {
                        text = "pub fn add(a: i32, b: i32) -> i32 {\n    a + b\n}\n"
                    }
                val kotlinOutput =
                    JBTextArea(12, 40).apply {
                        isEditable = false
                    }

                val choosePropsButton =
                    JButton(MyMessageBundle.message("toolwindow.MyToolWindow.choose.props.button")).apply {
                        addActionListener {
                            val service = project.getService(RustTranspilerService::class.java)
                            service.promptForRustKotlinPropertiesFile()
                        }
                    }
                topBar.add(choosePropsButton)

                val transpileButton =
                    JButton(MyMessageBundle.message("toolwindow.MyToolWindow.transpile.button")).apply {
                        addActionListener {
                            val service = project.getService(RustTranspilerService::class.java)
                            val activeRustFile =
                                FileEditorManager
                                    .getInstance(project)
                                    .selectedFiles
                                    .firstOrNull { service.isTranspilableRustFile(it) }
                            val rustKotlinProps = service.ensureRustKotlinPropertiesConfigured(activeRustFile) ?: return@addActionListener
                            kotlinOutput.text =
                                service.transpileSource(
                                    source = rustInput.text,
                                    sourceFilePath = activeRustFile?.path ?: "src/example.rs",
                                    rustKotlinProperties = rustKotlinProps,
                                )
                        }
                    }
                topBar.add(transpileButton)

                val editorsPanel =
                    BorderLayoutPanel(8, 8).apply {
                        addToTop(JBScrollPane(rustInput))
                        addToCenter(JBScrollPane(kotlinOutput))
                    }

                addToTop(topBar)
                addToCenter(editorsPanel)
            }

        fun getContent(): JBPanel<*> = content
    }
}
