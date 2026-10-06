pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ImmichRoom"

include(
    ":app",
    ":core:model",
    ":core:render",
    ":core:immich",
    ":core:data",
    ":core:designsystem",
    ":core:composition",
    ":core:sync",
    ":core:sync-supabase",
    ":feature:library",
    ":feature:detail",
    ":feature:editor",
    ":feature:composer",
    ":feature:account",
    ":feature:shell",
    // Kotlin puro, partilhado com a app desktop.
    ":shared:sync-protocol",
    // The IMAGO app for Windows (Compose Desktop). Run it with `gradlew :desktop:run`.
    ":desktop",
)
