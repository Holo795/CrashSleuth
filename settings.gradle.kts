plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // A few AndroidX artifacts used by Compose Multiplatform live there.
        google()
    }
}

rootProject.name = "crashsleuth"

include("core-model", "core-logs", "core-inventory", "core-engine", "core-runner", "core-bisect", "core-app", "cli", "desktop")
