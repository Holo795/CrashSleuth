plugins {
    application
}

dependencies {
    implementation(project(":core-bisect"))
    implementation(libs.clikt)
}

application {
    mainClass.set("dev.holo795.crashsleuth.cli.MainKt")
    applicationName = "crashsleuth"
}
