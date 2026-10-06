/*
 * The IMAGO account and the link between libraries and the backend, shared by Android and desktop.
 * The engine and the account only talk to `SyncBackend`: this module does not know Supabase.
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
            api(project(":core:data"))
            api(project(":shared:sync-protocol"))
            implementation(project(":core:model"))
            implementation(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
        }
        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.hilt.android)
            implementation(libs.androidx.work.runtime.ktx)
            implementation(libs.androidx.lifecycle.process)
        }
        getByName("desktopTest").dependencies {
            implementation(libs.junit4)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.androidx.sqlite.bundled)
        }
        getByName("androidUnitTest").dependencies {
            implementation(libs.junit4)
            implementation("org.robolectric:robolectric:4.14.1")
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.okhttp)
            implementation(project(":core:immich"))
            implementation(libs.androidx.room.runtime)
        }
    }
}

android {
    namespace = "eu.studio742.imago.core.sync"
    compileSdk = 36

    defaultConfig { minSdk = 31 }
    testOptions { unitTests.isIncludeAndroidResources = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    add("kspAndroid", libs.hilt.compiler)
}
