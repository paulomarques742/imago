# AGENTS.md

Instructions for coding agents working in this repository. Read
[CONTRIBUTING.md](CONTRIBUTING.md) first: its conventions apply to agents too.

## Layout

All packages live under `eu.studio742.imago`, which is also the Android `applicationId`.

| Module | What it is |
|---|---|
| `:app` | Android app |
| `:desktop` | Windows app (Compose Desktop); there is no separate `desktop/gradlew` |
| `:core:*` | Shared model, rendering, Immich client, data, design system, compositions and sync |
| `:feature:*` | Shared screens: library, detail, editor, composer, account and the app shell |
| `:shared:sync-protocol` | Pure Kotlin sync protocol, shared by both apps |
| `backend/` | Supabase migrations, Edge Functions and their tests |
| `open-api/` | Pinned Immich OpenAPI specifications |

## Commands

```sh
./gradlew testDebugUnitTest      # Android unit tests
./gradlew :app:assembleDebug     # Android debug APK
./gradlew lintDebug              # Android lint
./gradlew :desktop:run           # Windows app
./gradlew :desktop:test          # desktop tests
cd backend/tests && npm test     # Supabase migration tests (PGlite, no Docker)
```

The build needs JDK 17. `gradle.properties` asks for a 3 GB heap, which a 32-bit JVM cannot
reserve.

## Pinned versions

Kotlin 2.4, AGP 8.13, Gradle 8.14.3, `compileSdk`/`targetSdk` 36. Hilt stays on 2.58 because
2.59 and later require AGP 9, and so the root `build.gradle.kts` forces `kotlin-metadata-jvm` to
the project's Kotlin version. Read the comment next to that pin before changing it.

## Rules

- Keys come only from `local.properties` or the environment. Only the Supabase URL and the
  publishable key go into the app; the secret key never does.
- Do not invent URLs, versions or numbers. Where information is missing, say so.
- When cloning a tool from Lightroom, implement the complete version, not an approximation.
