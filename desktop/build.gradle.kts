import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":core-app"))
    implementation(compose.desktop.currentOs)
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
