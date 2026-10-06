/*
 * The model and geometry of compositions, in pure Kotlin: compiled by Android and by desktop.
 */
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

layout.buildDirectory.set(rootProject.layout.buildDirectory.dir("core/composition"))

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":core:model"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit4)
}
