import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":core-app"))
    // -Pdesktop.target=windows_x64 (linux_x64, macos_arm64...) builds for another system than this one.
    implementation(
        when (val target = findProperty("desktop.target")?.toString()) {
            null -> compose.desktop.currentOs
            "windows_x64" -> compose.desktop.windows_x64
            "linux_x64" -> compose.desktop.linux_x64
            "linux_arm64" -> compose.desktop.linux_arm64
            "macos_x64" -> compose.desktop.macos_x64
            "macos_arm64" -> compose.desktop.macos_arm64
            else -> error("unknown desktop.target $target")
        },
    )
    implementation(libs.material3)
    implementation(libs.coroutines.swing)
}

compose.desktop {
    application {
        mainClass = "dev.holo795.crashsleuth.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "CrashSleuth"
            packageVersion = "1.0.0"
            description = "Find out why Minecraft crashes, and who is to blame."
            vendor = "Holo795"
            copyright = "MIT License"
            // jdk.crypto.ec: HTTPS downloads (Modrinth, Mojang) need elliptic-curve TLS, which a trimmed runtime lacks.
            modules("java.instrument", "java.net.http", "java.management", "jdk.unsupported", "jdk.crypto.ec", "jdk.zipfs")
            macOS {
                bundleID = "dev.holo795.crashsleuth"
                iconFile.set(project.file("icons/icon.icns"))
            }
            windows {
                menuGroup = "CrashSleuth"
                perUserInstall = true
                upgradeUuid = "6f1e3f7a-2c1b-4d2e-9b7a-0c5d1e8f4a21"
                iconFile.set(project.file("icons/icon.ico"))
            }
            linux { iconFile.set(project.file("icons/icon.png")) }
        }
    }
}

// The app's jars in one folder, to run it with any Java 21 (every jar of lib on the class path, main class
// dev.holo795.crashsleuth.desktop.MainKt): how the app is tried on another system than the build machine.
tasks.register("portable") {
    dependsOn(tasks.named("jar"))
    val runtime = configurations.runtimeClasspath
    val jar = tasks.named<Jar>("jar").flatMap { it.archiveFile }
    val target = layout.buildDirectory.dir("portable/lib")
    doLast {
        val dir = target.get().asFile.apply { deleteRecursively(); mkdirs() }
        // Two libraries can share a file name (runtime-desktop of JetBrains and of AndroidX): the group keeps both.
        runtime.get().incoming.artifacts.artifacts.forEach { artifact ->
            val group = (artifact.id.componentIdentifier as? org.gradle.api.artifacts.component.ModuleComponentIdentifier)?.group
            artifact.file.copyTo(dir.resolve(listOfNotNull(group, artifact.file.name).joinToString("-")), overwrite = true)
        }
        jar.get().asFile.copyTo(dir.resolve(jar.get().asFile.name), overwrite = true)
    }
}
