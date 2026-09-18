# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

影梭 (GoGoGo) — an Android mock-location app for Android 8.0+ (minSdk 27) that requires **no root**. It fakes positions by registering test location providers through the Android debug API, and provides an on-screen joystick for simulating movement. Licensed GPL-3.0-only.

This repo is a GPL-3.0 fork of 影梭 (GoGoGo), rebranded as **行屿** with package root `com.iterlocus.pathway`. Upstream branding (`影梭` / `GoGoGo` / `zcshou`) still appears in `README*.md`, `SECURITY.md` and the issue templates — that is intentional attribution, not leftover to clean up.

Pure **Java** (no Kotlin), single Gradle module `:app`. Package root is `com.iterlocus.pathway` (was `com.zcshou.gogogo`), with `service`, `joystick`, `database`, `utils` subpackages; activities and the Application class sit directly in the root package.

## Build & test

Gradle wrapper 8.13, AGP 8.12.1. **JDK 17 is required** (CI uses 17; compile target is Java 11).

```bash
./gradlew assembleDebug          # debug APK
./gradlew assembleRelease        # release APK (minified + resource-shrunk)
./gradlew build                  # full build: assemble + lint + unit tests
./gradlew test                   # unit tests
./gradlew :app:testDebugUnitTest --tests "com.iterlocus.pathway.ExampleUnitTest"   # single test
./gradlew connectedAndroidTest   # instrumented tests (requires device/emulator)
./gradlew clean
```

The only tests in the repo are the generated `ExampleUnitTest` / `ExampleInstrumentedTest` stubs.

APK output is renamed by `applicationVariants` in `app/build.gradle`: `Pathway_<versionName>_arm64-v8a_{debug,release}.apk`.

### JDK version

`JAVA_HOME` must point at **JDK 17–21**. Gradle 8.13 cannot run on JDK 25 (`Unsupported class file major version 69` when compiling the build script). If your default `JAVA_HOME` is newer, pin it per-invocation:

```bash
JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" ./gradlew assembleDebug
```

### Signing

Signing credentials are **not** committed. `app/build.gradle` reads them from `local.properties`:

```properties
RELEASE_STORE_FILE=keystore/release.jks   # relative to the repo root
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=...
RELEASE_KEY_PASSWORD=...
```

- If present, both `debug` and `release` are signed with that key — deliberate, so one Baidu AK (which binds to package name + signature SHA1) covers both build types.
- If absent, `debug` falls back to AGP's auto-generated `~/.android/debug.keystore` and `assembleRelease`/`bundleRelease` fail with an explicit message rather than silently emitting an unsigned APK.
- Because of that, CI's build check runs `assembleDebug lintDebug testDebugUnitTest`, not `build`. The release workflow restores the keystore from a `SIGNING_KEY` secret and writes the credentials into `local.properties`.
- **Changing the signing key invalidates the Baidu AK**, because the AK binds 包名 + 签名 SHA1. Re-register the new SHA1 at <https://lbsyun.baidu.com> and update *both* `MAPS_API_KEY` and `MAPS_SAFE_CODE` — the latter embeds `SHA1;packageName`, so it goes stale too. A mismatch produces no compile error; the map just silently fails to render at runtime.

Verify a built APK's signer with:

```bash
"$LOCALAPPDATA/Android/Sdk/build-tools/<ver>/apksigner.bat" verify --print-certs app/build/outputs/apk/release/*.apk
```

### Required local setup

`app/build.gradle` applies Google's `secrets-gradle-plugin`, which reads `MAPS_API_KEY` and `MAPS_SAFE_CODE` from `local.properties` and emits them as `BuildConfig` fields. **These are a hard compile dependency**: if they are absent the plugin omits the fields entirely and `compileDebugJavaWithJavac` fails with `符号: 变量 MAPS_API_KEY`. So `local.properties` needs `sdk.dir` plus both keys before anything compiles:

```properties
sdk.dir=...
MAPS_API_KEY=<baidu map ak>
MAPS_SAFE_CODE=<baidu map mcode>
```

(Verified: with only `sdk.dir` present, `:app:generateDebugBuildConfig` succeeds but produces a `BuildConfig` with no `MAPS_*` fields, and `:app:compileDebugJavaWithJavac` then fails.)

CI (`.github/workflows/build-check.yml`) writes these from repo secrets before building.

### Build constraints

- **arm64-v8a only** (`abiFilters`), `compileSdk = 32`, `targetSdk = 32`.
- `resourceConfigurations` is limited to `zh`, `zh-rCN`, `en`, `en-rUS` — add a new locale there if you add translations.
- Baidu native libs live in `app/libs/` (`BaiduLBS_Android.jar` + `arm64-v8a/*.so`); `app/proguard-rules.pro` keeps all `com.baidu.**` classes.
- `gradle.properties` enables configuration cache and `warning.mode=all`; `-Xlint:unchecked -Xlint:deprecation` are on for all JavaCompile tasks, so deprecation warnings are expected and noisy.

## Architecture

### Coordinate systems — the thing to get right first

The UI and Baidu MapView work exclusively in **BD09LL**. Baidu's `SDKInitializer.setCoordType(CoordType.BD09LL)` in `GoApplication` enforces this. However, `ServiceGo` calls `LocationManager.setTestProviderLocation()`, which expects **WGS84**, so `MainActivity.startGoLocation()`/`doGoLocation()` convert the marked map point via `MapUtils.bd2wgs()` before handing it to the service. History rows store both: `*_WGS84` columns feed the mock provider, `*_CUSTOM` columns feed the map. `MapUtils` has hand-rolled `wgs2bd09` / `bd09togcj02` / `gcj02towgs84` conversions — do not swap them or you get ~500m offsets.

### Entry flow

`WelcomeActivity` (launcher) → validates user agreement + privacy checkboxes, requests runtime permissions, checks network/GPS → starts `MainActivity`. `WelcomeActivity` extends `AppCompatActivity` directly because it draws a full-screen transparent status bar; every other activity extends `BaseActivity` and repaints the status bar in `onCreate` to undo that.

### MainActivity (the bulk of the UI, ~1300 lines)

Owns the map, marker placement, POI suggestion search, the search/location history lists, and the update checker. Notable points:

- `mMarkLatLngMap` is a **static** field, as is `showLocation(...)`; `HistoryActivity` calls the static method to push a selected history entry back onto the map before finishing.
- The floating action button is a three-state toggle driven by `doGoLocation()`: not started → start (with permission/gps/overlay/network prechecks); started with a marked point → `mServiceBinder.setPosition(...)` to teleport; started with no marked point → stop the service.
- Anything that touches `mServiceBinder`, the marker, or DBs must be checked for null / wrapped — this codebase is written defensively around null and try/catch throughout.
- History names come from the **Android SDK's `GeoCoder`** (`mHistoryGeoCoder`), not the Baidu *Web* API. The Web API path (`api.map.baidu.com/reverse_geocoding/v3/`) was removed because it requires a 服务端-type AK while this project's AK is Android-SDK type — calling it returns `status 240 "APP 服务被禁用"`. Consequence: **`BuildConfig.MAPS_SAFE_CODE` is now unreferenced**; only `MAPS_API_KEY` (used by `SDKInitializer.setApiKey`) is required.
- Do not store an API error `message` as a history location name — upstream did that, which is why a failing geocode showed up in the UI as "APP服务被禁用".
- `getLocationClientOption()` calls `locationOption.SetIgnoreCacheException(true)` — note the **capital S**. That is the real Baidu SDK signature (verify with `javap -classpath app/libs/BaiduLBS_Android.jar com.baidu.location.LocationClientOption`). Upstream's commit `161eb7c` mistook it for a typo, lowercased it, and broke `master`'s build. Do not "fix" it back.
- Update check and the feedback nav entry are gated by `AppConfig` (`UPDATE_CHECK_ENABLED` / `UPDATE_API_URL` / `FEEDBACK_URL`), all disabled after the rebrand — upstream's endpoints would have detected 影梭 releases and pushed 影梭 APKs to 行屿 users (different signature, so the install would fail anyway). Fill in your own repo URLs to re-enable; no call sites need changing.
- When enabled, the update check compares the release `name`/`tag_name` against the installed `versionName`, renders the release body as Markdown via Markwon, downloads asset[0] with `DownloadManager`, and installs via `FileProvider` (`ShareUtils.getUriFromFile`).

### ServiceGo (the actual mock-location engine)

A foreground service (notification with Show/Hide-joystick broadcast actions) that:

1. In `onCreate`, removes and re-adds `GPS_PROVIDER` and `NETWORK_PROVIDER` as test providers (`addTestProvider` signatures branch on `Build.VERSION.SDK_INT >= S` for `ProviderProperties` vs `Criteria` constants).
2. Runs a `HandlerThread` loop that re-pushes both providers every ~100ms with a fully-populated `Location` (accuracy, altitude, bearing, speed, elapsedRealtimeNanos, and a `satellites=7` extra) to look plausible.
3. Owns the `JoyStick` overlay and receives joystick movement deltas, converting meters to degrees with `1 deg ≈ 110.574 km` latitude and `111.320 * cos(lat) km` longitude.
4. Exposes `ServiceGoBinder.setPosition(lng, lat, alt)` for teleporting; `MainActivity` binds with `BIND_AUTO_CREATE` and starts with `startForegroundService`.
5. `onDestroy` tears down the providers, thread, receiver, and foreground notification.

### JoyStick

A custom overlay `View` added to the `WindowManager` (not part of any activity layout) with three modes: joystick, embedded map, and history list. It is created with the service `Context`, so **it must not hold activity references**. Speeds come from preferences (`setting_walk` / `setting_run` / `setting_bike`).

### Persistence

Two independent `SQLiteOpenHelper`s, `HistoryLocation.db` and `HistorySearch.db`, both `DB_VERSION = 1` with a destructive `onUpgrade` (drop + recreate). Save helpers are `static` and dedupe by deleting matching rows before inserting (location keyed on WGS84 lng/lat, search keyed on the query string). `HistoryActivity` can apply a random offset (meters → degrees, ±`setting_lat_max_offset` / `setting_lon_max_offset`) when a history entry is tapped.

### Preferences

`res/xml/preferences_main.xml` is the single source of preference keys; `WelcomeActivity` seeds defaults early with `PreferenceManager.setDefaultValues(..., false)`, and both `FragmentSettings` and the activities read via `PreferenceManager.getDefaultSharedPreferences`. Keys in use: `setting_joystick_type`, `setting_walk`, `setting_run`, `setting_bike`, `setting_altitude`, `setting_random_offset`, `setting_lat_max_offset`, `setting_lon_max_offset`, `setting_log_off`, `setting_history_expiration`, `setting_map_key`.

### Logging

`XLog` (initialized in `GoApplication`) writes to `<externalFilesDir>/Logs/Pathway.log` (the filename comes from the `APP_NAME` constant), console + file, 3-day retention, level `ALL`.

## Conventions

- Comments and commit messages are in Chinese; keep new ones consistent with the surrounding language.
- User-facing text belongs in `res/values/strings.xml` (`app_*` prefix for messages) — though a number of dialogs in `GoUtils` and `HistoryActivity` still hardcode Chinese literals.
- Static/instance fields and methods are grouped under banner comments like `/*===== 历史记录 相关 =====*/` — follow that layout when adding sections.
- Errors are logged with `XLog.e("COMPONENT: ERROR - method")` rather than rethrown; the app intentionally degrades instead of crashing.
- Wi-Fi left on causes the mocked position to snap back to the real one — `GoUtils.showDisableWifiDialog()` warns the user about this after each jump. This is a known limitation of the debug-API approach, not a bug to "fix".
- This app is explicitly not intended for cheating in games or campus-running apps (see the warnings in `README.md`).
