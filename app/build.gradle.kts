import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/**
 * Machine settings — the Supabase keys and the signing key — read from `local.properties` (outside
 * git) or from environment variables. From Supabase only the URL and the publishable key go in,
 * which are public by design; the secret key never goes into the APK. Without them the app still
 * compiles and works without an account.
 */
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
fun buildSetting(name: String): String =
    (localProperties.getProperty(name) ?: providers.environmentVariable(name).orNull ?: "").trim()

android {
    namespace = "eu.studio742.imago"
    compileSdk = 36

    defaultConfig {
        applicationId = "eu.studio742.imago"
        minSdk = 31
        targetSdk = 36
        versionCode = 12
        versionName = "0.10.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Debug builds use the dev project.
        buildConfigField("String", "SUPABASE_URL", "\"${buildSetting("SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"${buildSetting("SUPABASE_PUBLISHABLE_KEY")}\"")
    }

    // The Play Store upload key. It lives outside the repository, and only whoever ships to the
    // store needs it: without it everything still compiles, and the `whenReady` further down
    // refuses an unsigned release instead of letting it out silently.
    signingConfigs {
        create("release") {
            val keystore = buildSetting("RELEASE_STORE_FILE").takeIf { it.isNotEmpty() }?.let(::file)
            if (keystore?.exists() == true) {
                storeFile = keystore
                storePassword = buildSetting("RELEASE_STORE_PASSWORD")
                keyAlias = buildSetting("RELEASE_KEY_ALIAS")
                keyPassword = buildSetting("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // The release only talks to the prod project; without it configured, it ships without accounts.
            buildConfigField("String", "SUPABASE_URL", "\"${buildSetting("SUPABASE_RELEASE_URL")}\"")
            buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"${buildSetting("SUPABASE_RELEASE_PUBLISHABLE_KEY")}\"")
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}

/*
 * An AAB without the upload key is rejected by the console, and one signed with the debug key is
 * worse: it installs, looks fine, and only fails on delivery. The check sits in the task graph, so
 * whoever only builds debug never sees it.
 */
gradle.taskGraph.whenReady {
    val packagesRelease = allTasks.any { it.project == project && it.name in setOf("bundleRelease", "assembleRelease") }
    if (packagesRelease && android.signingConfigs.getByName("release").storeFile == null) {
        throw GradleException(
            "The release has no signing key. Set RELEASE_STORE_FILE, RELEASE_STORE_PASSWORD, " +
                "RELEASE_KEY_ALIAS and RELEASE_KEY_PASSWORD in local.properties or in the environment."
        )
    }
}

dependencies {
    implementation(libs.okhttp)
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))
    implementation(project(":feature:shell"))
    implementation(project(":feature:library"))
    implementation(project(":feature:detail"))
    implementation(project(":feature:editor"))
    implementation(project(":feature:composer"))
    implementation(project(":feature:account"))
    implementation(project(":core:sync"))
    implementation(project(":core:sync-supabase"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.coil3.compose)
    implementation(libs.coil3.network.okhttp)
    ksp(libs.hilt.compiler)
    debugImplementation(libs.compose.ui.tooling)
}
