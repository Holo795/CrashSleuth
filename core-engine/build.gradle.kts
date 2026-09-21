dependencies {
    api(project(":core-logs"))
    api(project(":core-inventory"))
}

tasks.test {
    // Real logs and inventories produced by the lab (lab/corpus) are replayed as regression tests.
    val corpus = rootProject.file("lab/corpus")
    systemProperty("crashsleuth.corpus", corpus.absolutePath)
    inputs.dir(corpus).withPropertyName("corpus")
}
