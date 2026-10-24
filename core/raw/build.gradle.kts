plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

kotlin {
    jvmToolchain(17)
    androidTarget()
    jvm("desktop")
    sourceSets {
        commonTest.dependencies { implementation(libs.junit4) }
        getByName("desktopTest").kotlin.srcDir("src/testFixtures/kotlin")
    }
}

android {
    namespace = "eu.studio742.imago.core.raw"
    compileSdk = 36
    ndkVersion = "29.0.14206865"
    defaultConfig {
        minSdk = 31
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_static" } }
    }
    externalNativeBuild { cmake { path = file("src/native/CMakeLists.txt"); version = "3.22.1" } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Windows builds use the installed C++ build tools; the path can be overridden on other hosts.
val cmakeExecutable = providers.gradleProperty("imago.cmake").orElse(provider {
    val bundled = File(System.getenv("ProgramFiles(x86)") ?: "", "Microsoft Visual Studio/2022/BuildTools/Common7/IDE/CommonExtensions/Microsoft/CMake/CMake/bin/cmake.exe")
    if (bundled.isFile) bundled.absolutePath else "cmake"
})
val nativeBuild = layout.buildDirectory.dir("native/desktop")
val configureDesktopRaw by tasks.registering(Exec::class) {
    inputs.dir("src/native")
    inputs.dir("vendor")
    outputs.file(nativeBuild.map { it.file("CMakeCache.txt") })
    environment("JAVA_HOME", javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) }.get().metadata.installationPath.asFile.absolutePath)
    commandLine(cmakeExecutable.get(), "-S", file("src/native").absolutePath, "-B", nativeBuild.get().asFile.absolutePath, "-G", "Visual Studio 17 2022", "-A", "x64")
}
val buildDesktopRaw by tasks.registering(Exec::class) {
    dependsOn(configureDesktopRaw)
    inputs.dir("src/native")
    inputs.dir("vendor")
    outputs.file(nativeBuild.map { it.file("Release/imago_raw.dll") })
    commandLine(cmakeExecutable.get(), "--build", nativeBuild.get().asFile.absolutePath, "--config", "Release", "--parallel", "4")
}
androidComponents.onVariants { variant ->
    variant.sources.assets?.addStaticSourceDirectory("vendor")
}

val desktopNativeResources by tasks.registering(Sync::class) {
    dependsOn(buildDesktopRaw)
    from(nativeBuild.map { it.file("Release/imago_raw.dll") }) { into("native/windows-x64") }
    from("vendor") { into("licences/raw") }
    into(layout.buildDirectory.dir("generated/nativeResources"))
}
kotlin.sourceSets.getByName("desktopMain").resources.srcDir(desktopNativeResources)

