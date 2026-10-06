/*
 * The sync protocol shared by both apps, Android and desktop. Pure Kotlin, without Android: the
 * backend contract, the Supabase client, library identity, the account logic and the choice of the
 * active address.
 *
 * Both builds include this module by path; the plugins come from each one's build and the versions
 * from the gradle/libs.versions.toml catalogue, which desktop also reads. A change here reaches both
 * apps on the next build.
 */
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Each build compiles this in its own folder, so Android and desktop do not fight over the outputs.
layout.buildDirectory.set(rootProject.layout.buildDirectory.dir("shared/sync-protocol"))

kotlin { jvmToolchain(17) }

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.okhttp)
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.supabase.postgrest)
    implementation(libs.supabase.realtime)
    implementation(libs.supabase.functions)
    // The Ktor engine on OkHttp, so there are not two HTTP stacks. The client is an instance of
    // its own, separate from the Immich one.
    implementation(libs.ktor.client.okhttp)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
