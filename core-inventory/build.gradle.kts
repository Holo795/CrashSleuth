plugins {
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core-model"))
    implementation(libs.tomlj)
    implementation(libs.snakeyaml)
}
