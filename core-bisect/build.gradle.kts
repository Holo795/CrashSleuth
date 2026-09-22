plugins {
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core-runner"))
}
