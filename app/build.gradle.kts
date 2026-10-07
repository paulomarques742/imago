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
 * which are public by design; the secret key never goes into the APK. Without them the debug build
 * still compiles and works without an account; the release refuses (see `whenReady` at the end).
 */
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
fun buildSetting(name: String): String =
    (localProperties.getProperty(name) ?: providers.environmentVariable(name).orNull ?: "").trim()

/**
 * The version comes from `imago.version` in gradle.properties, shared with the Windows app, and the
 * versionCode is derived from it: a hand-kept counter is the one thing a release forgets to bump,
 * and Play refuses an upload whose code is not higher than the last one. 0.10.0 shipped with the
 * code 12, before this rule; every derived code is far above it.
 */
val imagoVersion: String = providers.gradleProperty("imago.version").get()
val imagoVersionCode: Int = run {
    val parts = Regex("""(\d+)\.(\d+)\.(\d+)""").matchEntire(imagoVersion)?.destructured?.toList()?.map(String::toInt)
        ?: throw GradleException("imago.version must be MAJOR.MINOR.PATCH, not \"$imagoVersion\"")
    val (major, minor, patch) = parts
    if (minor > 99 || patch > 99) throw GradleException("imago.version $imagoVersion: MINOR and PATCH go up to 99")
    major * 10_000 + minor * 100 + patch
}

android {
    namespace = "eu.studio742.imago"
    compileSdk = 36

    defaultConfig {
        applicationId = "eu.studio742.imago"
        minSdk = 31
        targetSdk = 36
        versionCode = imagoVersionCode
        versionName = imagoVersion

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
            // The release only talks to the prod project; without it configured, `whenReady` refuses it.
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
    // Without the prod project the release used to compile anyway and ship an app without accounts,
    // with no warning at all. The secret key is refused by its prefix: it would go into the APK, and
    // anyone could take it from there.
    if (packagesRelease) {
        val url = buildSetting("SUPABASE_RELEASE_URL")
        val key = buildSetting("SUPABASE_RELEASE_PUBLISHABLE_KEY")
        val problems = buildList {
            if (url.isEmpty()) add("SUPABASE_RELEASE_URL is missing")
            else if (!Regex("^https://[a-z0-9-]+\\.supabase\\.co/?$").matches(url)) {
                add("SUPABASE_RELEASE_URL is not https://<project>.supabase.co")
            }
            if (key.isEmpty()) add("SUPABASE_RELEASE_PUBLISHABLE_KEY is missing")
            else if (key.startsWith("sb_secret_")) add("SUPABASE_RELEASE_PUBLISHABLE_KEY is a secret key")
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "The release has no production Supabase project: ${problems.joinToString("; ")}. " +
                    "Set them in local.properties or in the environment."
            )
        }
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
