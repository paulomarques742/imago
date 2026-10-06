/*
 * The domain model, in pure Kotlin: compiled by Android and by desktop. The plugins come from each
 * app's build and the versions from the shared catalogue.
 */
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Each build compiles this in its own folder, so Android and desktop do not fight over the outputs.
layout.buildDirectory.set(rootProject.layout.buildDirectory.dir("core/model"))

kotlin { jvmToolchain(17) }

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit4)
}
