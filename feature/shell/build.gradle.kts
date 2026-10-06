/*
 * The app's shell: where it is and what that shows. It is the same body on the phone and on the
 * computer; each app only composes ImagoApp inside its own window.
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
            api(project(":feature:library"))
            api(project(":feature:detail"))
            api(project(":feature:editor"))
            api(project(":feature:composer"))
            api(project(":feature:account"))
            implementation(project(":core:data"))
            implementation(project(":core:designsystem"))
            implementation(libs.jetbrains.lifecycle.viewmodel.compose)
            implementation(libs.jetbrains.lifecycle.runtime.compose)
            implementation(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            implementation(libs.hilt.android)
            implementation(libs.hilt.navigation.compose)
        }
    }
}

android {
    namespace = "eu.studio742.imago.feature.shell"
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
