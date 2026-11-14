import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.intellij.changelog)
    alias(libs.plugins.intellij.platform)
}

group = rootProject.group
version = rootProject.version
base { archivesName.set("rust-transpiler") }

intellijPlatform {
    projectName = "rust-transpiler"
    pluginConfiguration {
        version = providers.provider { project.version.toString() }
        changeNotes =
            providers.provider {
                with(changelog) {
                    renderItem(
                        (getOrNull(project.version.toString()) ?: getUnreleased())
                            .withHeader(false)
                            .withEmptySections(false),
                        Changelog.OutputType.HTML,
                    )
                }
            }
    }
}

repositories {
    intellijPlatform {
        defaultRepositories()
    }
}

changelog {
    path.set(
        rootProject.layout.projectDirectory
            .file("CHANGELOG.md")
            .asFile.canonicalPath,
    )
}

kotlin {
    jvmToolchain(providers.gradleProperty("jvm.toolchain").get().toInt())
}

dependencies {
    implementation(rootProject)
    implementation(libs.km.io)
    testImplementation(libs.junit)
    testImplementation(kotlin("test-junit"))
    intellijPlatform {
        intellijIdea("2025.3.6.1")
        testFramework(TestFrameworkType.Platform)
    }
}
