plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}

// Hilt 2.58 is the last one that accepts AGP 8, and it ships a Kotlin metadata reader that only
// goes up to 2.3. Annotation processors use the one for the project's Kotlin version instead;
// without this, Hilt rejects classes compiled with 2.4. Remove once the app moves to AGP 9 and a
// recent Hilt.
subprojects {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin" && requested.name == "kotlin-metadata-jvm") {
                useVersion(libs.versions.kotlin.get())
            }
        }
    }
}
