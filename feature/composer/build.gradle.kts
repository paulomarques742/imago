/*
 * The composer: the stage, the elements, the page model and export. Shared by both apps; what changes
 * per platform — where export runs and how a video is seen — is in ComposerPlatform.
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
            implementation(project(":core:model"))
            implementation(project(":core:composition"))
            implementation(project(":core:data"))
            implementation(project(":core:render"))
            implementation(project(":core:designsystem"))
            implementation(project(":feature:editor"))
            implementation(project(":feature:library"))
            implementation(compose.materialIconsExtended)
            // The fonts the app ships with, read by the stage and by both exporters.
            implementation(compose.components.resources)
            implementation(libs.compose.backhandler)
            implementation(libs.jetbrains.lifecycle.viewmodel.compose)
            implementation(libs.jetbrains.lifecycle.runtime.compose)
            implementation(libs.androidx.paging.compose)
            implementation(libs.coil3.compose)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.junit4)
        }
        androidMain.dependencies {
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.activity.compose)
            implementation(libs.hilt.android)
            implementation(libs.hilt.navigation.compose)
            implementation(libs.media3.exoplayer)
            implementation(libs.media3.ui)
            implementation(libs.media3.transformer)
            implementation(libs.androidx.work.runtime.ktx)
            implementation(libs.kotlinx.coroutines.android)
        }
        getByName("desktopTest").dependencies {
            // Skia's native library, which in a Compose app comes with the window; desktop is Windows.
            implementation("org.jetbrains.skiko:skiko-awt-runtime-windows-x64:0.9.4.2")
            implementation(libs.kotlinx.coroutines.test)
        }
        getByName("desktopMain").dependencies {
            implementation(project(":core:immich"))
            implementation(libs.okhttp)
        }
    }
}

compose.resources {
    packageOfResClass = "eu.studio742.imago.feature.composer.resources"
    publicResClass = false
}

android {
    namespace = "eu.studio742.imago.feature.composer"
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
