plugins {
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core-model"))
    implementation(libs.tomlj)
    implementation(libs.snakeyaml)
    implementation(libs.asm)
    implementation("org.ow2.asm:asm-tree:${libs.versions.asm.get()}")
}
