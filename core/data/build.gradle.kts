/*
 * The IMAGO data layer, shared by Android and desktop.
 *
 * commonMain: the Room database, the DAOs, the repositories and the library configuration.
 * androidMain: what belongs to the phone — the gallery (MediaStore), the encrypted preferences, the
 * network signals, the migrations from old versions and the Hilt module. Desktop assembles its own
 * pieces by hand.
 */
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

kotlin {
    jvmToolchain(17)
    androidTarget()
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            api(project(":core:composition"))
            implementation(project(":shared:sync-protocol"))
            implementation(project(":core:immich"))
            api(libs.androidx.room.runtime)
            api(libs.androidx.paging.common)
            implementation(libs.androidx.room.paging)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.okhttp)
            api(libs.javax.inject)
        }
        androidMain.dependencies {
            implementation(libs.androidx.paging.runtime)
            implementation(libs.androidx.room.ktx)
            implementation(libs.androidx.security.crypto)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.hilt.android)
        }
        getByName("androidUnitTest").dependencies {
            implementation(libs.junit4)
            implementation("org.robolectric:robolectric:4.14.1")
            implementation(libs.kotlinx.coroutines.test)
        }
        getByName("desktopMain").dependencies {
            implementation(libs.androidx.sqlite.bundled)
            implementation("org.jetbrains.compose.runtime:runtime:1.8.2")
            // Windows DPAPI for the configuration, and the EXIF of the photos in the folders.
            implementation("net.java.dev.jna:jna-platform:5.17.0")
            implementation("com.drewnoakes:metadata-extractor:2.19.0")
            // The configuration starts on the main dispatcher; on desktop that is the Swing UI one.
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
        }
        getByName("desktopTest").dependencies {
            implementation(libs.junit4)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

android {
    namespace = "eu.studio742.imago.core.data"
    compileSdk = 36

    defaultConfig { minSdk = 31 }
    testOptions { unitTests.isIncludeAndroidResources = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

ksp {
    arg("room.schemaLocation", file("schemas").path)
}

dependencies {
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspAndroid", libs.hilt.compiler)
    add("kspDesktop", libs.androidx.room.compiler)
}
