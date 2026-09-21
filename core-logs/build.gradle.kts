dependencies {
    api(project(":core-model"))
}

tasks.test {
    // Real logs produced by the lab (lab/corpus) are replayed as regression tests.
    systemProperty("crashsleuth.corpus", rootProject.file("lab/corpus").absolutePath)
}
