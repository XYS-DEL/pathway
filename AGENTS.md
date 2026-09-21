# Agent Instructions

## Build and Test

- Pure **Java** (no Kotlin). Gradle wrapper **8.13**, AGP **8.12.1**. Compile target is Java 11; **JDK 17–21** is required to run Gradle. **JDK 25 fails** (`Unsupported class file major version 69`). Default `JAVA_HOME` may point at 25 — pin per invocation, e.g. `JAVA_HOME="C:\Program Files\Java\jdk-21.0.10"`.
- Before compiling, `local.properties` must contain `sdk.dir` and `MAPS_API_KEY` (secrets-gradle-plugin emits them as `BuildConfig`; missing key fields make `compileDebugJavaWithJavac` fail). **`MAPS_SAFE_CODE` is unreferenced in Java** — only `MAPS_API_KEY` is required to compile. Never commit `local.properties` or signing credentials.
- Useful commands (use `gradlew.bat` on Windows):
  - `./gradlew assembleDebug` — arm64 debug APK.
  - `./gradlew assembleRelease` — requires `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD` in `local.properties`; fails explicitly if missing (no silent unsigned release).
  - `./gradlew assembleDebug lintDebug testDebugUnitTest` — what CI runs (`.github/workflows/build-check.yml`); do not use full `build` for the same check (release needs signing).
  - `./gradlew :app:testDebugUnitTest --tests "com.iterlocus.pathway.RoutePlayerTest"` — one app test class; `./gradlew :nfc:testDebugUnitTest` for the NFC module.
  - `./gradlew connectedAndroidTest` needs a device/emulator (arm64-v8a only; no x86 images).
- JVM unit tests **do exist** for route/offset logic (`RoutePlayer`, `RouteGeometry`, `RouteNameValidator`, `SmoothRouteOffset`, `SmoothValueOffset`, `LocationOffset`, `RouteProgress`, …) plus `:nfc` parser tests. Prefer those over inventing Android instrumented tests for pure logic.
- App output is arm64-v8a only, renamed `Pathway_<versionName>_arm64-v8a_{debug,release}.apk`.
- Baidu SDK jars/so live in `app/libs/`. `secrets { ignoreList.add("RELEASE_.*") }` keeps signing passwords out of `BuildConfig` — do not remove that ignore.
- Changing the signing key invalidates the Baidu AK (bound to package + SHA1). Wrong AK → **no compile error**; map simply fails to render.

## Modules and Boundaries

- `:app` — Android application, namespace/applicationId `com.iterlocus.pathway`. `WelcomeActivity` is the launcher; `MainActivity` owns the primary map UI; `ServiceGo` is the foreground mock-location engine; `GoApplication` inits Baidu + XLog with `CoordType.BD09LL`.
- `:nfc` — reusable, resource-free Android library (namespace `com.acooldog.nfc`, minSdk **21**, zero third-party deps, designed to be copied into other projects). External code may use only `com.acooldog.nfc`; **do not reference `com.acooldog.nfc.internal`**.
- Route simulation sends a targeted pseudo-NDEF via public `com.acooldog.nfc.NfcSender` after a route starts — not via internal dispatcher classes.
- `RouteGeometry`, `RouteNameValidator`, `RoutePlayer`, `SmoothRouteOffset`, `RouteProgress` are Android-free JVM logic. `RouteDrawActivity` / `RouteSimulationActivity` / `RouteHistoryActivity` / `NfcCardActivity` are not exported. Every Activity is portrait-locked.
- Package layout under `app`: activities + `AppConfig`/`GoApplication` in the root package; subpackages `service`, `joystick`, `database`, `utils`.
- Upstream branding (`影梭` / `GoGoGo` / `zcshou`) is kept only in license/attribution sections (and some issue templates) — intentional GPL attribution. Product-facing docs lead with 行屿/Pathway; do not rebuild README around “diff vs upstream”.

## Runtime Invariants

- **Baidu map/UI coordinates are BD09.** Test providers and `RoutePlayer` use **WGS84**. Convert exactly once at the boundary with `MapUtils.bd2wgs` / `MapUtils.wgs2bd09`; never add a second conversion (~500 m drift if you swap or double-convert).
- Coordinate arrays and `MapUtils` arguments are `{longitude, latitude}` even though Baidu `LatLng` constructors take `(latitude, longitude)`.
- `RoutePlayer` accepts WGS84 `double[][]` only. Points loaded from `RouteConfig`/`DataBaseRoute` are **BD09**; convert once when building a simulation row (`RouteSimulationActivity.buildRow`). Playback runs in `ServiceGo`, not an Activity.
- Route random offset: one `SmoothRouteOffset` per route session. Continuous drift in WGS84 meter space; freezes while paused; must **not** reset when a closed route starts a new lap.
- `ServiceGo.setPosition()` stops route playback. While a route object exists (playing/paused/arrived), joystick input and notification “show joystick” stay disabled; ending a route keeps the overlay hidden.
- `RouteConfig` / `DataBaseRoute` reject duplicate route names (`COLLATE NOCASE UNIQUE`). Do **not** change to delete-then-insert. History DBs intentionally dedupe; routes are protected from silent replacement.
- Keep `LocationClientOption.SetIgnoreCacheException(true)` — capital `S` is the real Baidu SDK method. Upstream lowercased it and broke the build.
- Runtime data paths: log via `XLog.e` and degrade (return safe value). DB `onCreate`/`onUpgrade` DDL errors log **and rethrow** (follow `DataBaseRoute`, not the older history helpers).

## UI and NFC Traps

- AppCompat `SearchView` options such as `iconifiedByDefault` / `queryHint` use the **`app:`** namespace (and are also set in Java). Set `app:queryBackground="@null"` or the built-in background draws a second mismatched panel. Framework `android.widget.SearchView` elsewhere in the repo still uses `android:queryHint`.
- When an `<include>` supplies `layout_width`/`layout_height`, gravity and margins must be on the **`<include>` tag**; the included root’s layout params are ignored.
- Map activities must forward `MapView.onResume()` / `onPause()` / `onDestroy()`. Layout/color/map-overlay changes **cannot** be validated by lint/unit tests — the user verifies on a real device.
- Do **not** install builds, run ADB, operate a device/emulator, or take screenshots yourself. The user performs all real-device verification.
- `NfcCardActivity` must keep the **default** launch mode (NFC foreground dispatch). `MainActivity` is `singleInstance`; do not copy that mode to NFC.
- `SavedNfcConfig.isComplete()` requires name + URL + package; incomplete configs are silently dropped by `saveSavedConfig`.
- Comments and commit messages are Chinese — keep new ones consistent. User-facing strings go in `res/values/strings.xml` (`app_*` prefix) where that pattern already exists.

For architecture rationale, known defects, and route-simulation details, read `CLAUDE.md` before changing coordinate conversion, route simulation, database error handling, or service/activity binding.
