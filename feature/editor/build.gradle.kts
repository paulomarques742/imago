/*
 * The photo editor and the recipe library, shared by both apps. What changes per platform — the
 * preview, the export and where the copy goes — is in EditorPlatform and in core/render (PhotoCanvas).
 */
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

kotlin {
    jvmToolchain(17)
    androidTarget()
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            // This module's texts, in composeResources.
            implementation(compose.components.resources)
            implementation(project(":feature:library"))
            implementation(project(":core:model"))
            implementation(project(":core:data"))
            implementation(project(":core:immich"))
            implementation(project(":core:render"))
            implementation(project(":core:designsystem"))
            implementation(compose.materialIconsExtended)
            implementation(libs.compose.backhandler)
            implementation(libs.jetbrains.lifecycle.viewmodel.compose)
            implementation(libs.jetbrains.lifecycle.runtime.compose)
            implementation(libs.coil3.compose)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.junit4)
        }
        getByName("desktopTest").dependencies {
            // Skia's native library, which in a Compose app comes with the window; desktop is Windows.
            implementation("org.jetbrains.skiko:skiko-awt-runtime-windows-x64:0.9.4.2")
            implementation(libs.kotlinx.coroutines.test)
            // What the panel shows and where, asked through semantics: a category off the screen
            // is a node outside the root.
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
        }
        androidMain.dependencies {
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.activity.compose)
            implementation(libs.hilt.android)
            implementation(libs.hilt.navigation.compose)
            implementation(libs.kotlinx.coroutines.android)
        }
    }
}


compose.resources {
    packageOfResClass = "eu.studio742.imago.feature.editor.resources"
    publicResClass = false
}

android {
    namespace = "eu.studio742.imago.feature.editor"
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
