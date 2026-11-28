package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.io.Source
import io.github.kotlinmania.io.asSource
import io.github.kotlinmania.io.buffered
import io.github.kotlinmania.io.files.SystemFileSystem
import io.github.kotlinmania.io.files.SystemPathSeparator
import java.io.File
import java.net.URI
import java.net.URL
import java.util.jar.JarFile
import io.github.kotlinmania.io.files.Path as KmPath

private object JvmRuleResourceAnchor

internal fun isJvmWindowsHost(): Boolean = SystemPathSeparator == '\\'

private fun activeJvmFileProbe(): TargetFileProbe =
    if (isJvmWindowsHost()) JvmWindowsFileProbe else JvmPosixFileProbe

public actual fun probeTargetKotlinFile(path: String): TargetKotlinFileDetails =
    activeJvmFileProbe().probe(path)

public actual fun describeVirtualKotlinFile(
    path: String,
    fileName: String,
    isDirectory: Boolean,
    isWritable: Boolean,
    sizeBytes: Long,
): TargetKotlinFileDetails =
    activeJvmFileProbe().describeVirtual(path, fileName, isDirectory, isWritable, sizeBytes)

public actual fun writeNew(path: String, text: String): Unit =
    activeJvmFileProbe().writeNew(path, text)

internal actual fun readPlatformEnvironmentVariable(name: String): String? {
    val value = System.getenv(name)?.trim() ?: return null
    return value.ifEmpty { null }
}

internal actual fun resolvePlatformExecutablePath(): String? = null

private fun collectRuleYmlEntriesFromJar(
    jarFile: File,
    cleanDir: String,
    sink: MutableSet<String>,
) {
    if (!jarFile.isFile) return
    JarFile(jarFile).use { jar ->
        val entries = jar.entries()
        while (entries.hasMoreElements()) {
            val entryName = entries.nextElement().name
            if (entryName.startsWith(cleanDir) && entryName.endsWith(".yml")) {
                val rel = entryName.removePrefix(cleanDir)
                if ('/' !in rel && rel.isNotEmpty()) {
                    sink += rel
                }
            }
        }
    }
}

private fun collectRuleYmlEntriesFromDirectory(
    dirFile: File,
    sink: MutableSet<String>,
) {
    val dirPath = KmPath(dirFile.path)
    if (SystemFileSystem.metadataOrNull(dirPath)?.isDirectory != true) return
    SystemFileSystem
        .list(dirPath)
        .map { it.name }
        .filter { it.endsWith(".yml") }
        .forEach(sink::add)
}

private fun inspectResourceUrl(
    url: URL,
    cleanDir: String,
    sink: MutableSet<String>,
) {
    when (url.protocol) {
        "jar" -> {
            val rawJarSpec = url.path.substringBefore("!")
            if (rawJarSpec.isNotEmpty()) {
                val jarFile =
                    if (rawJarSpec.startsWith("file:")) {
                        File(URI(rawJarSpec.replace(" ", "%20")))
                    } else {
                        File(rawJarSpec)
                    }
                collectRuleYmlEntriesFromJar(jarFile, cleanDir, sink)
            }
        }
        "file" -> {
            val file = File(URI(url.toString().replace(" ", "%20")))
            if (file.name.endsWith(".jar")) {
                collectRuleYmlEntriesFromJar(file, cleanDir, sink)
            } else {
                collectRuleYmlEntriesFromDirectory(file, sink)
            }
        }
    }
}

internal actual fun listPlatformPackagedRuleFiles(resourceDirPath: String): List<String> {
    val noSlashDir = resourceDirPath.trim('/')
    val cleanDir = "$noSlashDir/"
    val anchorClass = JvmRuleResourceAnchor::class.java
    val loader = anchorClass.classLoader ?: return emptyList()
    val results = linkedSetOf<String>()

    anchorClass.getResource("JvmRuleResourceAnchor.class")?.let { anchorUrl ->
        if (anchorUrl.protocol == "jar") {
            inspectResourceUrl(anchorUrl, cleanDir, results)
        }
    }
    anchorClass.protectionDomain?.codeSource?.location?.let { codeLoc ->
        if (codeLoc.protocol == "file" && codeLoc.path.endsWith(".jar")) {
            inspectResourceUrl(codeLoc, cleanDir, results)
        }
    }
    for (query in listOf(noSlashDir, cleanDir)) {
        val urls = loader.getResources(query)
        while (urls.hasMoreElements()) {
            inspectResourceUrl(urls.nextElement(), cleanDir, results)
        }
    }
    return results.sorted()
}

internal actual fun openPlatformPackagedRuleSource(resourcePath: String): Source? {
    val stream =
        JvmRuleResourceAnchor::class.java.classLoader
            ?.getResourceAsStream(resourcePath.trimStart('/'))
            ?: return null
    return stream.asSource().buffered()
}
