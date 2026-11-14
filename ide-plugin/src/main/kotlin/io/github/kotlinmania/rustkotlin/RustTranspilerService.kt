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

private const val GRADLE_PROPERTIES_PATH_KEY = "io.github.kotlinmania.rust-transpiler.gradlePropertiesPath"

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

    fun getConfiguredGradlePropertiesFile(): VirtualFile? {
        val savedPath =
            PropertiesComponent
                .getInstance(project)
                .getValue(GRADLE_PROPERTIES_PATH_KEY)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return null
        return LocalFileSystem.getInstance().findFileByPath(savedPath)?.takeIf { it.isValid && !it.isDirectory }
    }

    fun setConfiguredGradlePropertiesPath(path: String) {
        PropertiesComponent.getInstance(project).setValue(GRADLE_PROPERTIES_PATH_KEY, path)
    }

    fun promptForGradlePropertiesFile(initialContextFile: VirtualFile? = null): VirtualFile? {
        val descriptor =
            FileChooserDescriptorFactory
                .createSingleFileDescriptor("properties")
                .withTitle(MyMessageBundle.message("chooser.gradle.properties.title"))
                .withDescription(MyMessageBundle.message("chooser.gradle.properties.description"))
                .withFileFilter { file ->
                    file.name.equals("gradle.properties", ignoreCase = true) ||
                        file.extension.equals("properties", ignoreCase = true)
                }
        val initialSelection =
            getConfiguredGradlePropertiesFile()
                ?: findNearestGradlePropertiesVirtualFile(initialContextFile)
                ?: project.guessProjectDir()?.findChild("gradle.properties")
                ?: project.guessProjectDir()

        val chosen = FileChooser.chooseFile(descriptor, project, initialSelection) ?: return null
        setConfiguredGradlePropertiesPath(chosen.path)
        return chosen
    }

    fun ensureGradlePropertiesConfigured(initialContextFile: VirtualFile? = null): GradleProjectProperties? {
        val existingFile = getConfiguredGradlePropertiesFile()
        val targetFile =
            if (existingFile != null) {
                existingFile
            } else if (ApplicationManager.getApplication().isUnitTestMode) {
                findNearestGradlePropertiesVirtualFile(initialContextFile)
                    ?: project.guessProjectDir()?.findChild("gradle.properties")
            } else {
                promptForGradlePropertiesFile(initialContextFile)
            }
        return targetFile?.let { loadGradleProperties(it) }
    }

    fun loadGradleProperties(file: VirtualFile): GradleProjectProperties {
        val text = VfsUtilCore.loadText(file)
        return parseGradleProperties(text)
    }

    fun transpileSource(
        source: String,
        sourceFilePath: String? = null,
        targetFilePath: String? = null,
        gradleProperties: GradleProjectProperties? = ensureGradlePropertiesConfigured(),
        packageProperties: FolderPackageProperties? = null,
    ): String {
        val inferred =
            inferFileHeaderContext(
                inputPath = sourceFilePath,
                outputPath = targetFilePath,
                gradleProperties = gradleProperties,
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
                packageName = inferred.packageName ?: gradleProperties?.projectNamespace,
                projectGroup = inferred.projectGroup ?: gradleProperties?.projectGroup,
                projectNamespace = inferred.projectNamespace ?: gradleProperties?.projectNamespace,
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
        gradleProperties: GradleProjectProperties? = ensureGradlePropertiesConfigured(directory),
    ): List<VirtualFile> {
        require(directory.isDirectory) {
            "Expected a directory for recursive translation: ${directory.path}"
        }
        return transpileVirtualFiles(arrayOf(directory), gradleProperties)
    }

    fun transpileVirtualFiles(
        selectedFiles: Array<out VirtualFile>,
        gradleProperties: GradleProjectProperties? = null,
    ): List<VirtualFile> {
        val rustFiles = collectTranspilableRustFiles(selectedFiles)
        if (rustFiles.isEmpty()) return emptyList()
        val resolvedGradleProperties = gradleProperties ?: ensureGradlePropertiesConfigured(rustFiles.first())
        if (rustFiles.size == 1) {
            return listOfNotNull(transpileVirtualFile(rustFiles.single(), resolvedGradleProperties))
        }

        val generatedFiles = mutableListOf<VirtualFile>()
        var overwriteAll = false
        var cancelledBatch = false

        for (rustFile in rustFiles) {
            if (cancelledBatch) break
            val generated =
                transpileVirtualFile(
                    rustFile = rustFile,
                    gradleProperties = resolvedGradleProperties,
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
        gradleProperties: GradleProjectProperties? = ensureGradlePropertiesConfigured(rustFile),
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
                gradleProperties = gradleProperties,
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
                targetFile
            }
    }

    fun resolvePackagePropertiesForVirtualFile(startFile: VirtualFile?): FolderPackageProperties? {
        var current = if (startFile?.isDirectory == true) startFile else startFile?.parent
        val traversedDirs = mutableListOf<String>()
        val foundLayers = mutableListOf<Pair<FolderPackageProperties, List<String>>>()

        while (current != null) {
            val pkgCandidate = current.findChild("package.properties")
            if (pkgCandidate != null && !pkgCandidate.isDirectory) {
                val parsed = parsePackageProperties(VfsUtilCore.loadText(pkgCandidate))
                foundLayers += parsed to traversedDirs.reversed()
            }
            val gradleCandidate = current.findChild("gradle.properties")
            if (gradleCandidate != null && !gradleCandidate.isDirectory) {
                break
            }
            val dirName = current.name
            if (dirName.isNotEmpty()) {
                traversedDirs += dirName
            }
            current = current.parent
        }

        if (foundLayers.isEmpty()) return null

        var resolvedPackage: String? = null
        var resolvedGroup: String? = null
        var resolvedNamespace: String? = null
        val mergedCrateNamespaces = linkedMapOf<String, String>()

        for ((layer, relativeSubdirs) in foundLayers.asReversed()) {
            layer.projectGroup?.let { resolvedGroup = it }
            layer.projectNamespace?.let { resolvedNamespace = it }
            mergedCrateNamespaces.putAll(layer.crateNamespaces)
            val layerPkg = layer.packageName
            if (layerPkg != null) {
                val basePkg = layerPkg.trim().removeSuffix(".")
                val subSuffix =
                    relativeSubdirs
                        .map { rustModuleIdentToKotlin(it) }
                        .filter { it.isNotEmpty() }
                        .joinToString(".")
                resolvedPackage = if (subSuffix.isEmpty()) basePkg else "$basePkg.$subSuffix"
            }
        }

        return FolderPackageProperties(
            packageName = resolvedPackage,
            projectGroup = resolvedGroup,
            projectNamespace = resolvedNamespace,
            crateNamespaces = mergedCrateNamespaces,
        )
    }

    private fun discoverCrateFlatFileModulesForVirtualFile(rustFile: VirtualFile): List<String> {
        val inputDir = rustFile.parent ?: return emptyList()
        var cur: VirtualFile? = inputDir
        var crateSrcRoot: VirtualFile = inputDir
        var depth = 0
        while (cur != null && depth < 12) {
            val libChild = cur.findChild("lib.rs")
            if ((libChild != null && !libChild.isDirectory) || cur.name == "src") {
                crateSrcRoot = cur
                break
            }
            val cargoChild = cur.findChild("Cargo.toml")
            if (cargoChild != null && !cargoChild.isDirectory) {
                break
            }
            cur = cur.parent
            depth++
        }
        val flatModules = mutableListOf<String>()
        for (child in crateSrcRoot.children) {
            if (child.isDirectory || !child.isValid) continue
            val fileName = child.name
            if (!fileName.endsWith(".rs")) continue
            val stem = fileName.removeSuffix(".rs")
            if (stem == "lib" || stem == "mod" || stem == "main" || stem == "build") continue
            val siblingDir = crateSrcRoot.findChild(stem)
            if (siblingDir == null || !siblingDir.isDirectory) {
                flatModules += stem
            }
        }
        val preludeDir = crateSrcRoot.findChild("prelude")
        if (preludeDir == null || !preludeDir.isDirectory) {
            flatModules += "prelude"
        }
        return flatModules.distinct()
    }

    private fun discoverCrateMacroSourcesForVirtualFile(rustFile: VirtualFile): List<String> {
        val inputDir = rustFile.parent ?: return emptyList()
        val dirsUpward = mutableListOf<VirtualFile>()
        var cur: VirtualFile? = inputDir
        var crateSrcRoot: VirtualFile = inputDir
        var depth = 0
        while (cur != null && depth < 12) {
            dirsUpward.add(cur)
            val libChild = cur.findChild("lib.rs")
            if ((libChild != null && !libChild.isDirectory) || cur.name == "src") {
                crateSrcRoot = cur
                break
            }
            val cargoChild = cur.findChild("Cargo.toml")
            if (cargoChild != null && !cargoChild.isDirectory) {
                break
            }
            cur = cur.parent
            depth++
        }
        val rootIdx = dirsUpward.indexOf(crateSrcRoot)
        val ancestorDirs =
            if (rootIdx >= 0) {
                dirsUpward.take(rootIdx + 1).reversed()
            } else {
                listOf(inputDir)
            }

        val visitedPaths = linkedSetOf(rustFile.path)
        val sources = mutableListOf<String>()

        fun addVirtualMacroFile(candidate: VirtualFile?, followMacroUse: Boolean) {
            if (candidate == null || candidate.isDirectory || !candidate.isValid) return
            if (!visitedPaths.add(candidate.path)) return
            val text = VfsUtilCore.loadText(candidate)
            if (text.contains("macro_rules")) {
                sources.add(text)
            }
            if (followMacroUse && text.contains("macro_use")) {
                val baseDir = candidate.parent
                if (baseDir != null) {
                    for (modName in extractMacroUseModuleNames(text)) {
                        addVirtualMacroFile(baseDir.findChild("$modName.rs"), followMacroUse = true)
                        addVirtualMacroFile(baseDir.findChild(modName)?.findChild("mod.rs"), followMacroUse = true)
                    }
                }
            }
        }

        addVirtualMacroFile(crateSrcRoot.findChild("lib.rs"), followMacroUse = true)
        addVirtualMacroFile(crateSrcRoot.findChild("macros.rs"), followMacroUse = true)
        for (dir in ancestorDirs) {
            addVirtualMacroFile(dir.findChild("mod.rs"), followMacroUse = true)
        }
        return sources
    }

    private fun findNearestGradlePropertiesVirtualFile(startFile: VirtualFile?): VirtualFile? {
        var current = if (startFile?.isDirectory == true) startFile else startFile?.parent
        while (current != null) {
            val candidate = current.findChild("gradle.properties")
            if (candidate != null && !candidate.isDirectory) {
                return candidate
            }
            current = current.parent
        }
        return null
    }
}
