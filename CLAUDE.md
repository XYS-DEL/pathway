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
- 除 `:app` 外还有 `:nfc` 库模块（见 Architecture 一节）。它的 `minSdkVersion 21` 低于 app 的 27 —— **有意保留**，库的 minSdk 低于宿主完全合法，且该模块的设计目标是可整体复制到别的工程。`nfc/build/` 已被 `.gitignore` 里裸写的 `build` 通配规则覆盖。
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

Three `SQLiteOpenHelper`s: `HistoryLocation.db` and `HistorySearch.db` (the original two), plus `RouteConfig.db` for drawn routes. All are `DB_VERSION = 1` with a destructive `onUpgrade` (drop + recreate). Save helpers are `static`. The two **history** helpers dedupe by deleting matching rows before inserting (location keyed on WGS84 lng/lat, search keyed on the query string); **`DataBaseRoute` deliberately does the opposite** — it refuses a duplicate name and returns `-1` and never overwrites, because a hand-drawn route is expensive to reproduce and silently replacing it would be data loss. Its `COLLATE NOCASE UNIQUE` column is the enforcement, not a hint to delete-then-insert. `HistoryActivity` can apply a random offset (meters → degrees, ±`setting_lat_max_offset` / `setting_lon_max_offset`) when a history entry is tapped.

**DDL error handling is the one deliberate exception to "log and degrade".** Runtime data paths (`query`/`insert`/encode/decode) must log via `XLog.e` and return a safe value rather than throw — that is the project-wide convention. `onCreate`/`onUpgrade` instead **log and rethrow**: a malformed `CREATE TABLE` is a programming error, not a runtime read/write failure, and swallowing it leaves a table-less database whose every later save fails silently and permanently. `DataBaseRoute` implements this shape; the two older helpers still have unguarded `onCreate`/`onUpgrade`, so the `database` package currently carries two idioms — follow `DataBaseRoute`.

### Preferences

`res/xml/preferences_main.xml` is the single source of preference keys; `WelcomeActivity` seeds defaults early with `PreferenceManager.setDefaultValues(..., false)`, and both `FragmentSettings` and the activities read via `PreferenceManager.getDefaultSharedPreferences`. Keys in use: `setting_joystick_type`, `setting_walk`, `setting_run`, `setting_bike`, `setting_altitude`, `setting_random_offset`, `setting_lat_max_offset`, `setting_lon_max_offset`, `setting_log_off`, `setting_history_expiration`, `setting_map_key`.

### Logging

`XLog` (initialized in `GoApplication`) writes to `<externalFilesDir>/Logs/Pathway.log` (the filename comes from the `APP_NAME` constant), console + file, 3-day retention, level `ALL`.

### 绘制路线

`RouteDrawActivity` 在百度地图上画路线：点绘制（点击落点）与线绘制（按住拖动采样）两种模式，
可闭合（两种模式都支持，点数不足 2 时拒绝），可按总长等距密化（「密化」按钮弹框输入**加点数 N**，
总长等分 N+1 段），保存进 `RouteConfig.db` 的 `RouteConfig` 表。

- **`RouteGeometry` 与 `RouteNameValidator` 是纯逻辑**，不依赖 Android，可在普通 JVM 单元测试里跑。
  几何计算刻意收 `int` 像素而不是 `android.graphics.Point`——碰 Android 类会抛 "not mocked"。
- **存的坐标是 BD09**（地图原生）。后续线路模拟调用时必须逐个 `MapUtils.bd2wgs()` 转 WGS84
  再喂给 `ServiceGo`，因为 `setTestProviderLocation` 要的是 WGS84。注意 `MapUtils` 入参是
  **(经度, 纬度)**，而 `LatLng` 构造是 **(纬度, 经度)**。
- **同名路线拒绝写入**，不覆盖、不自动改名；名称先过 `RouteNameValidator`（trim、1–32 字符、
  禁控制字符、禁 `/\:*?"<>|`）。数据库列另有 `COLLATE NOCASE UNIQUE` 兜底。
- 地图手势与绘制手势靠工具面板的「锁定地图」互斥：锁定时 `setAllGesturesEnabled(false)`
  且绘制层消费触摸，解锁后相反。
- `RouteDrawActivity` 含 `MapView`，必须转发 `onResume`/`onPause`/`onDestroy`；
  且保持默认启动模式。
- **`RouteDrawActivity` 锁竖屏**（manifest 的 `screenOrientation="portrait"`）：它持有 `MapView`、
  GL 覆盖层与一次性定位客户端，旋转会重建 Activity，把用户正在画的那条路线连同撤销栈一起丢掉。
  代价是画不了横屏——若日后要放开，应先做点集持久化，而不是直接删掉这行。

### NFC 位置卡

`:nfc` 是独立的 Gradle 库模块（`com.android.library`，namespace `com.acooldog.nfc`），零第三方依赖、无资源、无 manifest 声明，设计目标是可整体复制到别的工程。它提供读卡（`NfcReaderSession`）、伪造贴卡派发（`NfcSender`）、配置持久化（`NfcConfigStore`）。

`NfcCardActivity` 读卡后**只显示 URL 与包名，不做任何解析**。四个按钮里「模拟nfc」把三个原始值（URL / 包名 / source）交给 `RouteSimulationActivity`——那是个占位界面，解析坐标、坐标系换算、自定义路线、选路线、开始模拟都是它的后续工作。设计文档见 `docs/superpowers/specs/2026-09-19-nfc-card-design.md`。

两条硬约束：

- **不要接入 `NfcSender`。** 它构造 `ACTION_NDEF_DISCOVERED` 并 `setPackage(目标包名)`，让某个 App 收到与真实贴卡无法区分的广播。本项目明确不使用这个能力。卡片上的包名字段只用于显示、保存、共享，不参与任何派发。
- **`NfcCardActivity` 必须保持默认启动模式。** NFC 前台调度依赖它；模块 README 明确要求接收标签的 Activity 不能设为 `singleInstance`（`MainActivity` 是 `singleInstance`，别照抄）。

只用 `com.acooldog.nfc` 包下的公开 API，`com.acooldog.nfc.internal` 不得直接引用。

`SavedNfcConfig.isComplete()` 要求名称、URL、包名三者齐全，缺任何一个都会被 `saveSavedConfig` **静默丢弃**（不报错、不保存），所以保存弹框必须显式要求填包名并说明原因。

## Conventions

- Comments and commit messages are in Chinese; keep new ones consistent with the surrounding language.
- User-facing text belongs in `res/values/strings.xml` (`app_*` prefix for messages) — though a number of dialogs in `GoUtils` and `HistoryActivity` still hardcode Chinese literals.
- Static/instance fields and methods are grouped under banner comments like `/*===== 历史记录 相关 =====*/` — follow that layout when adding sections.
- Errors are logged with `XLog.e("COMPONENT: ERROR - method")` rather than rethrown; the app intentionally degrades instead of crashing.
- Wi-Fi left on causes the mocked position to snap back to the real one — `GoUtils.showDisableWifiDialog()` warns the user about this after each jump. This is a known limitation of the debug-API approach, not a bug to "fix".
- This app is explicitly not intended for cheating in games or campus-running apps (see the warnings in `README.md`).
