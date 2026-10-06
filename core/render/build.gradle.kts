/*
 * The IMAGO image engine, shared by Android and desktop.
 *
 * commonMain has the whole CPU pipeline — parameters, geometry, masks, effects, the shader text — on
 * top of a PixelSurface. androidMain adds what belongs to the phone: the OpenGL ES preview renderer,
 * the Bitmap adapter and the Coil transformation.
 */
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvmToolchain(17)
    androidTarget()
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            implementation(project(":core:immich"))
            implementation(libs.kotlinx.coroutines.core)
            api(libs.coil3.compose)
            api(libs.coil3.network.core)
        }
        commonTest.dependencies {
            implementation(libs.junit4)
        }
        getByName("desktopTest").dependencies {
            // Skia's native library, which in a Compose app comes with the window; desktop is Windows.
            implementation("org.jetbrains.skiko:skiko-awt-runtime-windows-x64:0.9.4.2")
        }
        androidMain.dependencies {
            implementation(project.dependencies.platform(libs.compose.bom))
            implementation(libs.compose.ui)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.kotlinx.coroutines.android)
        }
    }
}

android {
    namespace = "eu.studio742.imago.core.render"
    compileSdk = 36

    defaultConfig { minSdk = 31 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
