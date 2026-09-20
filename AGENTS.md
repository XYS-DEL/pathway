# Agent Instructions

## Build and Test

- This is a Java-only Android project using Gradle 8.13 and AGP 8.12.1. Use JDK 17-21; JDK 25 cannot run this build.
- Before compiling locally, `local.properties` must contain `sdk.dir` and `MAPS_API_KEY`; the key is emitted by the secrets plugin and is required by `GoApplication`. `MAPS_SAFE_CODE` is currently unused. Never commit `local.properties` or signing credentials.
- Useful commands (use `gradlew.bat` on Windows):
  - `./gradlew assembleDebug` builds the arm64 debug APK.
  - `./gradlew assembleRelease` requires `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, and `RELEASE_KEY_PASSWORD` in `local.properties`.
  - `./gradlew lintDebug testDebugUnitTest` runs focused static analysis and JVM tests.
  - `./gradlew :app:testDebugUnitTest --tests "com.iterlocus.pathway.RoutePlayerTest"` runs one app test class; use `:nfc:test` for the standalone NFC module tests.
  - `./gradlew connectedAndroidTest` requires a connected Android device or emulator.
- App output is arm64-v8a only and is renamed `Pathway_<versionName>_arm64-v8a_{debug,release}.apk`.

## Modules and Boundaries

- `:app` is the Android application, namespace/application id `com.iterlocus.pathway`; `WelcomeActivity` is the launcher, `MainActivity` owns the primary map UI, and `ServiceGo` is the foreground mock-location engine.
- `:nfc` is a reusable, resource-free Android library with no third-party dependencies and minSdk 21. External code may use only `com.acooldog.nfc`; do not reference `com.acooldog.nfc.internal`.
- The NFC capability reads card data, then route simulation uses public `com.acooldog.nfc.NfcSender` to send a targeted pseudo-NDEF event; do not reference `com.acooldog.nfc.internal` directly.
- `RouteGeometry`, `RouteNameValidator`, and `RoutePlayer` are Android-free logic intended for JVM unit tests. `RouteDrawActivity` and `RouteSimulationActivity` are not exported; every Activity is intentionally portrait-locked in the manifest.

## Runtime Invariants

- Baidu map/UI coordinates are BD09. Android test providers and `RoutePlayer` use WGS84. Convert exactly at the boundary with `MapUtils.bd2wgs`/`wgs2bd09`; do not add a second conversion.
- Coordinate arrays and `MapUtils` arguments are `{longitude, latitude}` even though Baidu `LatLng` constructors take `(latitude, longitude)`.
- `RoutePlayer` accepts WGS84 `double[][]`; route points loaded from `RouteConfig` are BD09 and must be converted once when building a simulation row. Route playback runs in `ServiceGo`, not an Activity.
- `ServiceGo.setPosition()` stops route playback. While a route object exists (playing, paused, or arrived), joystick input and notification-triggered showing must remain disabled; ending a route keeps the overlay hidden.
- `RouteConfig` rejects duplicate route names; do not change it to delete-then-insert. History databases intentionally deduplicate, but routes are protected from silent replacement.
- Keep `LocationClientOption.SetIgnoreCacheException(true)` with the capital `S`; that is the Baidu SDK method name.

## UI and NFC Traps

- AppCompat `SearchView` options such as `iconifiedByDefault` and `queryHint` use the `app:` namespace and are also configured in Java. Set `app:queryBackground="@null"` or its built-in background can draw a second mismatched panel.
- With an `<include>` that supplies width and height, put gravity and margins on the `<include>` tag; the included root's layout params are ignored.
- Forward `MapView.onResume()`, `onPause()`, and `onDestroy()` in map activities. Layout/color changes require device/emulator screenshot verification; tests and lint cannot validate map composition or overlays.
- Do not install builds, run ADB, operate a device/emulator, or perform screenshots yourself. The user performs all real-device verification and reports the result.
- `NfcCardActivity` must keep the default launch mode because NFC foreground dispatch depends on it. `MainActivity` is the separate `singleInstance` activity; do not copy that mode to NFC.

For the fuller architecture rationale and known limitations, read `CLAUDE.md` before changing coordinate conversion, route simulation, database error handling, or service/activity binding.
