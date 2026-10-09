/*
 * The app's shell: where it is and what that shows. It is the same body on the phone and on the
 * computer; each app only composes ImagoApp inside its own window.
 */
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/**
 * The app version, from `imago.version` in gradle.properties, as a constant the shared code reads on
 * both platforms. Android's BuildConfig only exists in the app module, and the desktop has none.
 */
val generateAppVersion by tasks.registering {
    val version = providers.gradleProperty("imago.version")
    val output = layout.buildDirectory.dir("generated/appVersion/kotlin")
    inputs.property("version", version)
    outputs.dir(output)
    doLast {
        val file = output.get().file("eu/studio742/imago/feature/shell/AppVersion.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            listOf(
                "package eu.studio742.imago.feature.shell",
                "",
                "/** The installed version, MAJOR.MINOR.PATCH. Generated from imago.version. */",
                "internal const val APP_VERSION = \"${version.get()}\"",
                "",
            ).joinToString("\n"),
        )
    }
}

kotlin {
    jvmToolchain(17)
    androidTarget()
    jvm("desktop")

    sourceSets {
        commonMain {
            kotlin.srcDir(generateAppVersion)
        }
        commonMain.dependencies {
            // This module's texts, in composeResources.
            implementation(compose.components.resources)
            implementation(compose.materialIconsExtended)
            api(project(":feature:library"))
            api(project(":feature:detail"))
            api(project(":feature:editor"))
            api(project(":feature:composer"))
            api(project(":feature:account"))
            implementation(project(":core:data"))
            implementation(project(":core:designsystem"))
            implementation(libs.jetbrains.lifecycle.viewmodel.compose)
            implementation(libs.jetbrains.lifecycle.runtime.compose)
            implementation(libs.kotlinx.coroutines.core)
        }
        getByName("desktopTest").dependencies {
            implementation(libs.junit4)
            // The news cards are drawn in the tests, as the app draws them: Skia's native library,
            // which in a Compose app comes with the window, and the UI test runner.
            implementation("org.jetbrains.skiko:skiko-awt-runtime-windows-x64:0.9.4.2")
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
        }
        androidMain.dependencies {
            implementation(libs.hilt.android)
            implementation(libs.hilt.navigation.compose)
        }
    }
}

compose.resources {
    packageOfResClass = "eu.studio742.imago.feature.shell.resources"
    publicResClass = false
}

android {
    namespace = "eu.studio742.imago.feature.shell"
    compileSdk = 36

    defaultConfig { minSdk = 31 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    add("kspAndroid", libs.hilt.compiler)
}
