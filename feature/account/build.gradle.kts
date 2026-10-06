/*
 * The IMAGO account: the screens to sign in, create an account and manage devices, shared by both apps.
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
            implementation(project(":core:sync"))
            implementation(project(":core:designsystem"))
            implementation(compose.materialIconsExtended)
            implementation(libs.compose.backhandler)
            implementation(libs.jetbrains.lifecycle.viewmodel.compose)
            implementation(libs.jetbrains.lifecycle.runtime.compose)
            implementation(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            implementation(libs.hilt.android)
            implementation(libs.hilt.navigation.compose)
            implementation(libs.kotlinx.coroutines.android)
        }
    }
}


compose.resources {
    packageOfResClass = "eu.studio742.imago.feature.account.resources"
    publicResClass = false
}

android {
    namespace = "eu.studio742.imago.feature.account"
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
