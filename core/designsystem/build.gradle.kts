/*
 * The IMAGO design system, shared by Android and desktop (Compose Multiplatform).
 *
 * Almost everything lives in commonMain. Only what reads the system — the reduce-motion preference
 * and the window measurement — has a version per platform.
 */
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(17)
    androidTarget()
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            api(compose.runtime)
            api(compose.foundation)
            api(compose.ui)
            api(compose.material3)
            // The app's texts: each interface module has its own in composeResources, and this
            // module has the common ones and the app language (i18n/). `api` gives everyone stringResource.
            api(compose.components.resources)
            api(project(":core:model"))
            implementation(libs.kotlinx.coroutines.core)
        }
        // The focused adjustment mode is drawn with the same composition on Android and on desktop;
        // it is tested here, where a Compose scene can be rendered to an image without any device
        // at hand.
        getByName("desktopTest").dependencies {
            implementation(libs.junit4)
            implementation(compose.desktop.currentOs)
            // The sliders' gestures, played with a finger and a mouse.
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
        }
        getByName("desktopMain").dependencies {
            // The Windows file and folder dialogs (IFileOpenDialog), through Native File Dialog.
            implementation("org.lwjgl:lwjgl:3.3.6")
            implementation("org.lwjgl:lwjgl-nfd:3.3.6")
            runtimeOnly("org.lwjgl:lwjgl:3.3.6:natives-windows")
            runtimeOnly("org.lwjgl:lwjgl-nfd:3.3.6:natives-windows")
        }
    }
}

compose.resources {
    packageOfResClass = "eu.studio742.imago.core.designsystem.resources"
    publicResClass = true
}

android {
    namespace = "eu.studio742.imago.core.designsystem"
    compileSdk = 36

    defaultConfig { minSdk = 31 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
