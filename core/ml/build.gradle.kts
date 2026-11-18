/*
 * On-device segmentation for generated masks (subject, background, sky).
 *
 * The ONNX Runtime it links is our own build, in prebuilt/: without telemetry, which the official
 * Android package turns on and which adds network permissions, and with only the operators these
 * models use. tools/build-onnxruntime.sh rebuilds it.
 */
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

// Model weights are downloaded, not committed. Parity tests run when this points at a folder holding them.
val testModels = providers.gradleProperty("imago.ml.models").orNull
val samplePhotos = rootProject.file("feature/editor/src/commonMain/composeResources/files/recipe_samples")

kotlin {
    jvmToolchain(17)
    androidTarget()
    jvm("desktop")
    sourceSets {
        commonMain.dependencies { api(project(":core:model")) }
        commonTest.dependencies { implementation(libs.junit4) }
        androidMain {
            kotlin.srcDir("src/jvmShared/kotlin")
            dependencies {
                implementation(files("prebuilt/android/onnxruntime-1.32.0-imago.jar"))
                implementation(libs.okhttp)
                implementation(libs.kotlinx.serialization.json)
            }
        }
        getByName("desktopMain") {
            kotlin.srcDir("src/jvmShared/kotlin")
            dependencies {
                implementation(files("prebuilt/desktop/onnxruntime-1.32.0-imago.jar"))
                implementation(libs.okhttp)
                implementation(libs.kotlinx.serialization.json)
            }
        }
        getByName("desktopTest") {
            resources.srcDir("src/testFixtures/resources")
            dependencies { implementation(libs.okhttp.mockwebserver) }
        }
        getByName("androidInstrumentedTest").dependencies {
            implementation(libs.junit4)
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.ext.junit)
        }
    }
}

android {
    namespace = "eu.studio742.imago.core.ml"
    compileSdk = 36
    defaultConfig {
        minSdk = 31
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["modelsAvailable"] = (testModels != null).toString()
    }
    sourceSets.getByName("main").jniLibs.srcDir("prebuilt/android/jni")
    sourceSets.getByName("androidTest").assets.srcDirs(
        listOfNotNull("src/testFixtures/resources", samplePhotos.path, testModels),
    )
    // Play Protect blocks a test APK left at the library's minSdk as an app for an old Android.
    testOptions { targetSdk = 36 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.named<Test>("desktopTest") {
    systemProperty("imago.ml.photos", samplePhotos.absolutePath)
    testModels?.let { systemProperty("imago.ml.models", it) }
}
