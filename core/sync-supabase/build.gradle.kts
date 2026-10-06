plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "eu.studio742.imago.core.sync.supabase"
    compileSdk = 36

    defaultConfig { minSdk = 31 }
    testOptions { unitTests.isIncludeAndroidResources = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// The Android side of the Supabase backend: where the session is stored. The client lives in
// :shared:sync-protocol, shared with desktop. Only `:app` wires it to the rest, through Hilt.
dependencies {
    implementation(project(":core:sync"))
    api(project(":shared:sync-protocol"))
    implementation(libs.okhttp)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit4)
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
