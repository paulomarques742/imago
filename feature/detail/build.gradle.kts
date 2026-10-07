/*
 * The detail of a photo or video: the zoomable page, the strip, EXIF, favourite, delete, share and
 * save to the device. Shared by both apps; the video player and sharing change per platform.
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
            implementation(project(":core:model"))
            implementation(project(":core:data"))
            implementation(project(":core:designsystem"))
            implementation(project(":core:render"))
            // Saving the edited version renders the recipe at full resolution, and where the copy goes
            // on each platform is already the editor's export.
            implementation(project(":feature:editor"))
            // "Add to album" is the library's sheet, the same one its selection opens.
            implementation(project(":feature:library"))
            implementation(compose.materialIconsExtended)
            implementation(libs.compose.backhandler)
            implementation(libs.jetbrains.lifecycle.viewmodel.compose)
            implementation(libs.jetbrains.lifecycle.runtime.compose)
            implementation(libs.coil3.compose)
            implementation(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            implementation(libs.androidx.core.ktx)
            implementation(libs.hilt.android)
            implementation(libs.hilt.navigation.compose)
            implementation(libs.media3.exoplayer)
            implementation(libs.media3.ui)
            implementation(libs.kotlinx.coroutines.android)
        }
        getByName("desktopMain").dependencies {
            implementation(project(":core:immich"))
            implementation(libs.okhttp)
        }
        commonTest.dependencies {
            implementation(libs.junit4)
        }
    }
}


compose.resources {
    packageOfResClass = "eu.studio742.imago.feature.detail.resources"
    publicResClass = false
}

android {
    namespace = "eu.studio742.imago.feature.detail"
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
