package io.github.kotlinmania.rustkotlin

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.ReadonlyStatusHandler
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import io.github.kotlinmania.io.IOException

private const val RUSTKOTLIN_PROPERTIES_PATH_KEY = "io.github.kotlinmania.rust-transpiler.rustKotlinPropertiesPath"

private enum class BatchOverwriteDecision {
    OVERWRITE_ONE,
    OVERWRITE_ALL,
    SKIP_ONE,
    CANCEL_BATCH,
}

@Service(Service.Level.PROJECT)
class RustTranspilerService(
    private val project: Project,
) {
    var overwriteConfirmationHandler: ((TargetKotlinFileDetails) -> Boolean)? = null

    fun getConfiguredRustKotlinPropertiesFile(): VirtualFile? {
        val savedPath =
            PropertiesComponent
                .getInstance(project)
                .getValue(RUSTKOTLIN_PROPERTIES_PATH_KEY)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return null
        return LocalFileSystem.getInstance().findFileByPath(savedPath)?.takeIf { it.isValid && !it.isDirectory }
    }

    fun setConfiguredRustKotlinPropertiesPath(path: String) {
        PropertiesComponent.getInstance(project).setValue(RUSTKOTLIN_PROPERTIES_PATH_KEY, path)
    }

    fun promptForRustKotlinPropertiesFile(initialContextFile: VirtualFile? = null): VirtualFile? {
        val descriptor =
            FileChooserDescriptorFactory
                .createSingleFileDescriptor("properties")
                .withTitle(MyMessageBundle.message("chooser.rustkotlin.properties.title"))
                .withDescription(MyMessageBundle.message("chooser.rustkotlin.properties.description"))
                .withFileFilter { file ->
                    file.name.equals("rustkotlin.properties", ignoreCase = true) ||
                        file.extension.equals("properties", ignoreCase = true)
                }
        val initialSelection =
            getConfiguredRustKotlinPropertiesFile()
                ?: findNearestRustKotlinPropertiesVirtualFile(initialContextFile)
                ?: project.guessProjectDir()?.findChild("rustkotlin.properties")
                ?: project.guessProjectDir()

        val chosen = FileChooser.chooseFile(descriptor, project, initialSelection) ?: return null
        setConfiguredRustKotlinPropertiesPath(chosen.path)
        return chosen
    }

    fun ensureRustKotlinPropertiesConfigured(initialContextFile: VirtualFile? = null): RustKotlinProperties? {
        val existingFile = getConfiguredRustKotlinPropertiesFile()
        val targetFile =
            if (existingFile != null) {
                existingFile
            } else if (ApplicationManager.getApplication().isUnitTestMode) {
                findNearestRustKotlinPropertiesVirtualFile(initialContextFile)
                    ?: project.guessProjectDir()?.findChild("rustkotlin.properties")
            } else {
                promptForRustKotlinPropertiesFile(initialContextFile)
            }
        return targetFile?.let { loadRustKotlinProperties(it) }
    }

    fun loadRustKotlinProperties(file: VirtualFile): RustKotlinProperties {
        val text = VfsUtilCore.loadText(file)
        return parseRustKotlinProperties(text)
    }

    fun transpileSource(
        source: String,
        sourceFilePath: String? = null,
        targetFilePath: String? = null,
        rustKotlinProperties: RustKotlinProperties? = ensureRustKotlinPropertiesConfigured(),
        packageProperties: FolderPackageProperties? = null,
    ): String {
        val inferred =
            inferFileHeaderContext(
                inputPath = sourceFilePath,
                outputPath = targetFilePath,
                rustKotlinProperties = rustKotlinProperties,
                packageProperties = packageProperties,
            )
        val contextVirtualFile =
            sourceFilePath
                ?.let { LocalFileSystem.getInstance().findFileByPath(it) }
                ?.takeIf { it.isValid && !it.isDirectory }
        val macroSources =
            if (inferred.crateMacroSources.isNotEmpty() || contextVirtualFile == null) {
                inferred.crateMacroSources
            } else {
                discoverCrateMacroSourcesForVirtualFile(contextVirtualFile)
            }
        val flatModules =
            if (inferred.crateFlatFileModules.isNotEmpty() || contextVirtualFile == null) {
                inferred.crateFlatFileModules
            } else {
                discoverCrateFlatFileModulesForVirtualFile(contextVirtualFile)
            }
        val headerContext =
            FileHeaderContext(
                portLintSourcePath = inferred.portLintSourcePath ?: sourceFilePath,
                packageName = inferred.packageName ?: rustKotlinProperties?.projectNamespace,
                projectGroup = inferred.projectGroup ?: rustKotlinProperties?.projectGroup,
                projectNamespace = inferred.projectNamespace ?: rustKotlinProperties?.projectNamespace,
                crateNamespaces = inferred.crateNamespaces,
                crateMacroSources = macroSources,
                crateFlatFileModules = flatModules,
            )
        return renderSingleKotlinFile(source = source, headerContext = headerContext).text
    }

    fun isTranspilableRustFile(file: VirtualFile): Boolean {
        if (file.isDirectory) return false
        return isTranspilableRustFileName(file.name)
    }

    fun hasTranspilableRustFiles(files: Array<out VirtualFile>): Boolean {
        for (file in files) {
            if (isTranspilableRustFile(file)) return true
            if (file.isDirectory && collectTranspilableRustFiles(arrayOf(file)).isNotEmpty()) {
                return true
            }
        }
        return false
    }

    fun collectTranspilableRustFiles(files: Array<out VirtualFile>): List<VirtualFile> {
        val collectedByPath = linkedMapOf<String, VirtualFile>()
        for (root in files) {
            if (isTranspilableRustFile(root)) {
                collectedByPath[root.path] = root
            } else if (root.isDirectory) {
                VfsUtilCore.visitChildrenRecursively(
                    root,
                    object : VirtualFileVisitor<Unit>() {
                        override fun visitFile(file: VirtualFile): Boolean {
                            if (isTranspilableRustFile(file)) {
                                collectedByPath[file.path] = file
                            }
                            return true
                        }
                    },
                )
                if (root.fileSystem is LocalFileSystem) {
                    val localFs = LocalFileSystem.getInstance()
                    for (entryPath in collectTranspilableRustFilePaths(listOf(root.path))) {
                        if (entryPath !in collectedByPath) {
                            val vf =
                                localFs.findFileByPath(entryPath)
                                    ?: localFs.refreshAndFindFileByPath(entryPath)
                            if (vf != null && isTranspilableRustFile(vf)) {
                                collectedByPath[vf.path] = vf
                            }
                        }
                    }
                }
            }
        }
        return collectedByPath.values.sortedBy { it.path }
    }

    fun computeTargetKotlinFileName(rustFileName: String): String =
        io.github.kotlinmania.rustkotlin
            .computeTargetKotlinFileName(rustFileName)

    fun inspectTargetKotlinFile(parent: VirtualFile, kotlinFileName: String): TargetKotlinFileDetails {
        val targetPath = "${parent.path.trimEnd('/')}/$kotlinFileName"
        val existingVirtualFile = parent.findChild(kotlinFileName)
        val diskProbe = probeTargetKotlinFile(targetPath)
        if (diskProbe.exists || existingVirtualFile == null) {
            return diskProbe
        }
        return describeVirtualKotlinFile(
            path = targetPath,
            fileName = kotlinFileName,
            isDirectory = existingVirtualFile.isDirectory,
            isWritable = existingVirtualFile.isWritable,
            sizeBytes = existingVirtualFile.length,
        )
    }

    fun confirmOverwriteTargetKotlinFile(targetDetails: TargetKotlinFileDetails): Boolean {
        overwriteConfirmationHandler?.let { return it(targetDetails) }
        val accessStatus =
            if (targetDetails.isReadOnly) {
                MyMessageBundle.message("dialog.overwrite.kotlin.readonly")
            } else {
                MyMessageBundle.message("dialog.overwrite.kotlin.writable")
            }
        val displayPath = targetDetails.resolvedRealPath ?: targetDetails.path
        return MessageDialogBuilder
            .yesNo(
                MyMessageBundle.message("dialog.overwrite.kotlin.title"),
                MyMessageBundle.message(
                    "dialog.overwrite.kotlin.message",
                    targetDetails.fileName,
                    targetDetails.sizeBytes,
                    accessStatus,
                    displayPath,
                ),
            ).yesText(MyMessageBundle.message("dialog.overwrite.kotlin.overwrite.button"))
            .noText(MyMessageBundle.message("dialog.overwrite.kotlin.cancel.button"))
            .icon(Messages.getWarningIcon())
            .ask(project)
    }

    private fun promptBatchOverwriteDecision(targetDetails: TargetKotlinFileDetails): BatchOverwriteDecision {
        val handler = overwriteConfirmationHandler
        if (handler != null) {
            return if (handler(targetDetails)) {
                BatchOverwriteDecision.OVERWRITE_ONE
            } else {
                BatchOverwriteDecision.SKIP_ONE
            }
        }
        val accessStatus =
            if (targetDetails.isReadOnly) {
                MyMessageBundle.message("dialog.overwrite.kotlin.readonly")
            } else {
                MyMessageBundle.message("dialog.overwrite.kotlin.writable")
            }
        val displayPath = targetDetails.resolvedRealPath ?: targetDetails.path
        val options =
            arrayOf(
                MyMessageBundle.message("dialog.overwrite.kotlin.overwrite.button"),
                MyMessageBundle.message("dialog.overwrite.kotlin.overwrite.all.button"),
                MyMessageBundle.message("dialog.overwrite.kotlin.skip.button"),
                MyMessageBundle.message("dialog.overwrite.kotlin.cancel.button"),
            )
        val choice =
            Messages.showDialog(
                project,
                MyMessageBundle.message(
                    "dialog.overwrite.kotlin.message",
                    targetDetails.fileName,
                    targetDetails.sizeBytes,
                    accessStatus,
                    displayPath,
                ),
                MyMessageBundle.message("dialog.overwrite.kotlin.title"),
                options,
                0,
                Messages.getWarningIcon(),
            )
        return when (choice) {
            0 -> BatchOverwriteDecision.OVERWRITE_ONE
            1 -> BatchOverwriteDecision.OVERWRITE_ALL
            2 -> BatchOverwriteDecision.SKIP_ONE
            else -> BatchOverwriteDecision.CANCEL_BATCH
        }
    }

    fun transpileDirectoryRecursively(
        directory: VirtualFile,
        rustKotlinProperties: RustKotlinProperties? = ensureRustKotlinPropertiesConfigured(directory),
    ): List<VirtualFile> {
        require(directory.isDirectory) {
            "Expected a directory for recursive translation: ${directory.path}"
        }
        return transpileVirtualFiles(arrayOf(directory), rustKotlinProperties)
    }

    fun transpileVirtualFiles(
        selectedFiles: Array<out VirtualFile>,
        rustKotlinProperties: RustKotlinProperties? = null,
    ): List<VirtualFile> {
        val rustFiles = collectTranspilableRustFiles(selectedFiles)
        if (rustFiles.isEmpty()) return emptyList()
        val resolvedRustKotlinProperties = rustKotlinProperties ?: ensureRustKotlinPropertiesConfigured(rustFiles.first())
        if (rustFiles.size == 1) {
            return listOfNotNull(transpileVirtualFile(rustFiles.single(), resolvedRustKotlinProperties))
        }

        val generatedFiles = mutableListOf<VirtualFile>()
        var overwriteAll = false
        var cancelledBatch = false

        for (rustFile in rustFiles) {
            if (cancelledBatch) break
            val generated =
                transpileVirtualFile(
                    rustFile = rustFile,
                    rustKotlinProperties = resolvedRustKotlinProperties,
                    confirmOverwrite = { details ->
                        if (overwriteAll) {
                            true
                        } else {
                            when (promptBatchOverwriteDecision(details)) {
                                BatchOverwriteDecision.OVERWRITE_ONE -> true
                                BatchOverwriteDecision.OVERWRITE_ALL -> {
                                    overwriteAll = true
                                    true
                                }
                                BatchOverwriteDecision.SKIP_ONE -> false
                                BatchOverwriteDecision.CANCEL_BATCH -> {
                                    cancelledBatch = true
                                    false
                                }
                            }
                        }
                    },
                )
            if (generated != null) {
                generatedFiles += generated
            }
        }
        return generatedFiles
    }

    fun transpileVirtualFile(
        rustFile: VirtualFile,
        rustKotlinProperties: RustKotlinProperties? = ensureRustKotlinPropertiesConfigured(rustFile),
        confirmOverwrite: (TargetKotlinFileDetails) -> Boolean = ::confirmOverwriteTargetKotlinFile,
    ): VirtualFile? {
        require(isTranspilableRustFile(rustFile)) {
            "Not a transpilable Rust source file: ${rustFile.path}"
        }
        val parent =
            requireNotNull(rustFile.parent) {
                "Rust file has no parent directory: ${rustFile.path}"
            }
        val kotlinFileName = computeTargetKotlinFileName(rustFile.name)
        val targetDetails = inspectTargetKotlinFile(parent, kotlinFileName)
        require(!targetDetails.isDirectory && !targetDetails.isSymbolicLink) {
            "Target path cannot be a directory or symbolic link: ${targetDetails.path}"
        }
        if (targetDetails.exists) {
            if (!confirmOverwrite(targetDetails)) {
                return null
            }
            parent.findChild(kotlinFileName)?.let { existingVirtualFile ->
                if (!existingVirtualFile.isWritable) {
                    val status =
                        ReadonlyStatusHandler
                            .getInstance(project)
                            .ensureFilesWritable(listOf(existingVirtualFile))
                    if (status.hasReadonlyFiles()) {
                        return null
                    }
                }
            }
        }

        val targetPath = targetDetails.path
        val rustSource = VfsUtilCore.loadText(rustFile)
        val packageProperties = resolvePackagePropertiesForVirtualFile(rustFile)
        val inferredHeader =
            inferFileHeaderContext(
                inputPath = rustFile.path,
                outputPath = targetPath,
                rustKotlinProperties = rustKotlinProperties,
                packageProperties = packageProperties,
            )
        val vfsMacroSources =
            inferredHeader.crateMacroSources.ifEmpty {
                discoverCrateMacroSourcesForVirtualFile(rustFile)
            }
        val vfsFlatFileModules =
            inferredHeader.crateFlatFileModules.ifEmpty {
                discoverCrateFlatFileModulesForVirtualFile(rustFile)
            }
        val headerContext =
            if (vfsMacroSources === inferredHeader.crateMacroSources &&
                vfsFlatFileModules === inferredHeader.crateFlatFileModules
            ) {
                inferredHeader
            } else {
                inferredHeader.copy(
                    crateMacroSources = vfsMacroSources,
                    crateFlatFileModules = vfsFlatFileModules,
                )
            }
        val rendered = renderSingleKotlinFile(rustSource, headerContext)
        val kotlinSource = rendered.text

        return WriteCommandAction
            .writeCommandAction(project)
            .withName(MyMessageBundle.message("command.convert.rust.to.kotlin"))
            .compute<VirtualFile, IOException> {
                val targetFile = parent.findOrCreateChildData(this, kotlinFileName)
                VfsUtil.saveText(targetFile, kotlinSource)
                for (companion in rendered.companionFiles) {
                    val companionFile = parent.findOrCreateChildData(this, companion.relativePath)
                    VfsUtil.saveText(companionFile, companion.content)
                }
                if (emittedKotlinImportsRustLang(kotlinSource)) {
                    ensureProjectVersionCatalogHasRustLang(headerContext.projectNamespace)
                }
                targetFile
            }
    }

    private fun ensureProjectVersionCatalogHasRustLang(projectNamespace: String?) {
        val projectRoot = project.guessProjectDir() ?: return
        val catalogFile =
            projectRoot
                .findChild("gradle")
                ?.takeIf { it.isDirectory }
                ?.findChild("libs.versions.toml")
                ?.takeIf { it.isValid && !it.isDirectory && it.isWritable }
                ?: return
        val propsFile =
            projectRoot
                .findChild("gradle.properties")
                ?.takeIf { it.isValid && !it.isDirectory }
        val existingProps = propsFile?.let(VfsUtilCore::loadText)
        if (isRustLangSelfProject(projectNamespace, existingProps)) return
        var targetBundle: String? = null
        if (propsFile != null && propsFile.isWritable && existingProps != null) {
            val (updatedProps, bundleName) = ensureRustLangInGradleProperties(existingProps)
            targetBundle = bundleName
            if (updatedProps != existingProps) {
                VfsUtil.saveText(propsFile, updatedProps)
            }
        }
        val existingToml = VfsUtilCore.loadText(catalogFile)
        val updatedToml = ensureRustLangInLibsVersionsToml(existingToml, commonMainBundleName = targetBundle)
        if (updatedToml != existingToml) {
            VfsUtil.saveText(catalogFile, updatedToml)
        }
    }

    fun resolvePackagePropertiesForVirtualFile(startFile: VirtualFile?): FolderPackageProperties? =
        VirtualFileTreeNavigator.resolveLayeredPackageProperties(startFile)

    private fun discoverCrateFlatFileModulesForVirtualFile(rustFile: VirtualFile): List<String> {
        val inputDir = rustFile.parent ?: return emptyList()
        val (crateSrcRoot, _) = VirtualFileTreeNavigator.findCrateSrcRootAndAncestors(inputDir)
        return VirtualFileTreeNavigator.discoverFlatFileModulesInRoot(crateSrcRoot).toList()
    }

    private fun discoverCrateMacroSourcesForVirtualFile(rustFile: VirtualFile): List<String> =
        VirtualFileTreeNavigator.discoverMacroSourcesForFile(rustFile)

    private fun findNearestRustKotlinPropertiesVirtualFile(startFile: VirtualFile?): VirtualFile? =
        VirtualFileTreeNavigator.findNearestRustKotlinPropertiesNode(startFile)
}

private object VirtualFileTreeNavigator : CrateFileTreeNavigator<VirtualFile>() {
    override fun parentOf(node: VirtualFile): VirtualFile? = node.parent

    override fun childNamed(dir: VirtualFile, name: String): VirtualFile? = dir.findChild(name)

    override fun childrenOf(dir: VirtualFile): List<VirtualFile> = dir.children.toList()

    override fun nameOf(node: VirtualFile): String = node.name

    override fun pathKey(node: VirtualFile): String = node.path

    override fun isDirectory(node: VirtualFile): Boolean = node.isValid && node.isDirectory

    override fun isRegularFile(node: VirtualFile): Boolean = node.isValid && !node.isDirectory

    override fun canonicalize(node: VirtualFile): VirtualFile = node

    override fun readText(file: VirtualFile): String = VfsUtilCore.loadText(file)
}
