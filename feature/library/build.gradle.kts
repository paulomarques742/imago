/*
 * The library: the grid, the albums, the search, the date ruler, the picker and the libraries'
 * Settings. Shared by both apps; what changes per platform is in LibraryPlatform.
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
            implementation(compose.materialIconsExtended)
            implementation(libs.compose.backhandler)
            implementation(libs.jetbrains.lifecycle.viewmodel.compose)
            implementation(libs.jetbrains.lifecycle.runtime.compose)
            implementation(libs.androidx.paging.compose)
            implementation(libs.coil3.compose)
            implementation(libs.kotlinx.coroutines.core)
        }
        getByName("desktopTest").dependencies {
            implementation(libs.junit4)
            // Tests only: the key's permission list is checked against the generated contract.
            implementation(project(":core:immich"))
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.hilt.android)
            implementation(libs.hilt.navigation.compose)
            // Uploading what other apps share runs outside the app (SharedUploadWorker).
            implementation(libs.androidx.work.runtime.ktx)
            implementation(libs.kotlinx.coroutines.android)
        }
    }
}


compose.resources {
    packageOfResClass = "eu.studio742.imago.feature.library.resources"
    publicResClass = false
}

android {
    namespace = "eu.studio742.imago.feature.library"
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
