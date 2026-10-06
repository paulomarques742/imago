# IMAGO

A non-destructive photo editor for your [Immich](https://immich.app) library and for the photos on
your device. Edits are stored as recipes; the original is never changed. All image processing
happens on the device.

- **Android** app (Android 12 or later), in `app/`.
- **Windows** app built with Compose Desktop, in `desktop/`.
- Most of the interface and logic is shared Kotlin Multiplatform code in `core/`, `feature/` and
  `shared/`.
- Optional multi-device sync of recipes, presets and compositions through Supabase. The schema and
  its tests are in `backend/`.

Immich v2.6 or later is required. The Immich OpenAPI specifications the client is checked against
are pinned in `open-api/`.

## Building

Requirements: JDK 17 and the Android SDK with API 36.

```sh
./gradlew :app:assembleDebug      # Android debug APK
./gradlew testDebugUnitTest       # Android unit tests
./gradlew :desktop:run            # Windows app
./gradlew :desktop:test           # desktop tests
cd backend/tests && npm install && npm test   # Supabase migration tests (PGlite, no Docker)
```

The app builds and runs without any keys: sync is simply unavailable. To build with sync against
your own Supabase project, apply the migrations in `backend/` to it and copy
`local.properties.example` to `local.properties` with your project's URL and **publishable**
key. Never put the secret key there: it bypasses row-level security, and anything in
`local.properties` ends up in the app.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## Licence and trademark

IMAGO is licensed under the [GNU Affero General Public License version 3](LICENSE), with an
additional permission to link with the purchase libraries of app stores. The copyright notice and
that permission are in [NOTICE](NOTICE).

The sample photographs used by the recipe previews are by Paulo Marques, under
[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/): use them freely, crediting the author.

The name IMAGO and its logo are not covered by the licence: a fork must use its own name and icon
before it is distributed. See [TRADEMARKS.md](TRADEMARKS.md).
