plugins {
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core-bisect"))
}

// The version the app tells you about itself, written at build time (-Pversion=1.2.3 on a release).
val writeVersion by tasks.registering {
    val version = project.version.toString()
    val out = layout.buildDirectory.dir("generated/version")
    inputs.property("version", version)
    outputs.dir(out)
    doLast {
        val file = out.get().asFile.resolve("crashsleuth/version.txt")
        file.parentFile.mkdirs()
        file.writeText(version)
    }
}

sourceSets.main { resources.srcDir(writeVersion) }
