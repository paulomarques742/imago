import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.Properties

/*
 * The IMAGO app for Windows: the window, the imago:// protocol, a single instance and this
 * computer's account. The screens are the ones shared with Android (feature/shell).
 */
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

group = "eu.studio742.imago"
// The same version as the Android app, from gradle.properties.
version = providers.gradleProperty("imago.version").get()
kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":feature:shell"))
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:sync"))
    implementation(project(":shared:sync-protocol"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.jetbrains.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
    implementation(libs.okhttp)
    // The image loader needs the network fetcher: Immich thumbnails come over HTTP.
    implementation(libs.coil3.compose)
    implementation(libs.coil3.network.okhttp)
    implementation("net.java.dev.jna:jna-platform:5.17.0")
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.kotlinx.coroutines.test)
}

/**
 * The account's Supabase project, read from local.properties (the same as Android's, outside git)
 * or from environment variables, and put into the app's resources. Only the URL and the
 * publishable key, which are public by design; the secret key never goes into the app. Without
 * them the app works without an account.
 */
val supabaseResources = layout.buildDirectory.dir("generated/supabase")
val generateSupabaseSettings by tasks.registering {
    val localProperties = rootProject.file("local.properties")
    inputs.files(listOfNotNull(localProperties.takeIf { it.exists() }))
    val output = supabaseResources
    outputs.dir(output)
    doLast {
        val properties = Properties().apply { localProperties.takeIf { it.exists() }?.inputStream()?.use(::load) }
        fun setting(name: String) = (properties.getProperty(name) ?: System.getenv(name) ?: "").trim()
        val file = output.get().file("imago-supabase.properties").asFile
        file.parentFile.mkdirs()
        file.writeText("url=${setting("SUPABASE_URL")}\npublishableKey=${setting("SUPABASE_PUBLISHABLE_KEY")}\n")
    }
}
sourceSets.main { resources.srcDir(generateSupabaseSettings) }

compose.desktop {
    application {
        mainClass = "eu.studio742.imago.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "IMAGO"
            packageVersion = project.version.toString()
            description = "IMAGO — Develop every image."
            vendor = "742 Studio"
            modules("java.sql", "java.naming", "jdk.crypto.ec", "java.management")
            windows {
                menuGroup = "IMAGO"
                shortcut = true
                upgradeUuid = "1f67ebdd-3b16-46ab-9c9f-81d0d20f7458"
            }
        }
    }
}
tasks.test { testLogging { events("failed", "skipped") } }
