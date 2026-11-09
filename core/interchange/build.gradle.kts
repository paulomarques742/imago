/*
 * Recipes in and out of the app as files. Pure Kotlin, compiled by Android and by desktop: what a
 * file holds and what importing it does are the same on both, and only reaching the file differs.
 */
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":core:model"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit4)
}
