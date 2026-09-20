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

**界面层（2026-09-19 改版）**：顶部搜索栏（百度 `SuggestionSearch`，选中只平移地图、不落标记、不写历史）、左侧可折叠的悬浮片工具条、搜索栏右下方的图层切换。三块背景 drawable 是 `bg_tool_chip`（含禁用态与按压态）、`bg_tool_chip_toggle`（可选中片，选中态填充主题色）、`bg_tool_chip_accent`（主操作片），文字色用 `res/color/chip_text` 与 `res/color/chip_text_toggle`。

三个容易重踩的坑：

- **AppCompat 的 `SearchView` 只认 app 命名空间的 `iconifiedByDefault` / `queryHint`。** 写成 `android:` 前缀会被**静默忽略**——不报错、不警告，渲染出来是一个折叠的放大镜图标且提示文字不出现。展开与提示必须在 Java 里设（`setIconifiedByDefault(false)` + `onActionViewExpanded()` + `setQueryHint(...)`，`MainActivity` 就是这么做的）。注意仓库里其它 `SearchView` 用的是**框架**的 `android.widget.SearchView`，那里 `android:queryHint` 是对的——别照抄。
- **`SearchView` 自带 `queryBackground` 底板会画在我们设的 `android:background` 之上**，产生两个形状不匹配的白块。必须用 `app:queryBackground="@null"` 关掉。
- **`<include>` 标签同时带 `layout_width` 与 `layout_height` 时，布局参数完全取自标签**，被包含根声明的 `layout_gravity` / `layout_marginStart` 不会被读取（`LayoutInflater.parseInclude`）。位置属性要写在 `<include>` 上。

**不做真背景模糊**：百度地图渲染在 `SurfaceView` 上（`MapView` 内部 `MapSurfaceView` → `ah`(RenderSurfaceView) → `android.view.SurfaceView`），其像素由系统合成器在独立图层绘制、不经过 View 绘制树，`RenderEffect` 糊不到它；`Window.setBackgroundBlurRadius` 糊的是窗口背后而地图在本窗口内部，同样不通。悬浮片是半透明白 + 圆角 + 描边 + elevation 做出来的观感。

**界面改动必须用截图验收。** 编译、lint、单元测试都覆盖不到布局与配色；本项目已有一次实例——终审读了字节码与 AAR 资源后判定 `SearchView` 底板"顶多是一道浅缝"，实际截图显示是一大块形状不匹配的白块。`RouteDrawActivity` 未导出，`adb` 拉不起来，需人工打开后 `adb exec-out screencap -p` 截图。
- **`RouteDrawActivity` 锁竖屏**（manifest 的 `screenOrientation="portrait"`）：它持有 `MapView`、
  GL 覆盖层与一次性定位客户端，旋转会重建 Activity，把用户正在画的那条路线连同撤销栈一起丢掉。
  代价是画不了横屏——若日后要放开，应先做点集持久化，而不是直接删掉这行。

### 路线模拟

侧滑菜单「模拟路线」→ `RouteSimulationActivity`（`exported="false"`）：选一条已保存的路线、选速度档位、
开始 / 结束模拟。移动本身由 `ServiceGo` 的 10Hz 定位循环推进，引擎是纯逻辑的 `RoutePlayer`
（23 个 JVM 单元测试）。

**引擎放在 `ServiceGo` 而不是 Activity 里**，因为用户按下开始之后一定会切走到目标 App——任何建立在
Activity 上的定时器都会在后台被冻结，位置就不动了，那样这个功能没有意义。服务本来就有一个 10Hz 循环和
一个可变的「当前位置」单元格，摇杆的移动本质就是改它，所以推送代码（`setLocationGPS()` /
`setLocationNetwork()`）一行都没改；`advanceRoute()` 只是同一个单元格的另一个写者。

- **`RoutePlayer` 只认 WGS84 的 `double[][]`**（每个元素 `{经度, 纬度}`）。刻意不收 `LatLng`——那个类型
  在本项目里既装 BD09 也装别的，不携带坐标系信息。**BD09→WGS84 只在 `buildRow()` 建列表时做一次**，
  之后全程 WGS84，因为 `setTestProviderLocation` 要的就是 WGS84。
- **`dt` 取 `SystemClock.elapsedRealtime()` 的差值并夹在 `MAX_TICK_SECONDS`（1s）。** 不要写死 0.1：
  `Thread.sleep(100)` 会漂，几公里的路线上累积误差肉眼可见；上限是防 doze / GC 长暂停之后一次跳出几百米。
  取时钟、更新 `mLastTickMs` 基准刻意放在 `try` **外面**——基准必须无条件每 tick 前移，否则某 tick
  一抛异常基准就停在过去，此后每次 `dt` 都吃满上限。
- **`startRoute()` 的两条拒绝是承重的，不是防御性装饰。** 点数不足 2 与总长为 0 是两种不同的坏输入：
  两个**重合**的点能过第一条而总长仍为 0。`RoutePlayer.isFinished()` 对零长路线返回 true
  （`!mClosed && mDistance >= mTotalDistance`，即 `0 >= 0`）：零长的**开环**路线一开始就是「已到达」，
  `advanceRoute()` 每 tick 在开头早退，位置永远不动而通知与界面写着「已到达终点」；零长的**闭合**路线
  反过来——永远不推进也永远不结束。两条之间还有一道逐行验形（null 行 / 长度不足 / 非有限值），非有限值
  会让第二条失效（`NaN <= 0d` 为 false），于是被当成合法路线收下。
- **手动干预会终止播放**：`setPosition()` 第一件事就是 `stopRoute()`。但「位置只有一个写者」是**被收窄的，
  不是绝对的**：已经进入回写阶段的那个 tick 靠回写前的一次复检（`mRoutePlayer != player`）被丢弃，把窗口
  从一次 `advance() + getPosition()` 缩到相邻几条指令；彻底的单写者要把所有写者（含摇杆那一路）都并到
  定位线程，是另一个量级的改动，没做。推论：**`JoyStick.setCurrentPosition` 绝不能每 tick 调**——它会
  `mBaiduMap.clear()` 并 `animateMapStatus`，10Hz 下地图会被拖着抖。位置回写只写单元格，地图同步只在
  开始 / 结束 / 到达时做。
- **摇杆禁用分两层，缺一不可。** 权威层是 `ServiceGo` 的两个 listener 回调（播放期间一律忽略摇杆输入）；
  `JoyStick.setInputEnabled` 加它自己四个入口（方向收口 `processDirection`、窗口拖拽的 `onTouch`、内置地图
  落点、历史列表选点）的守卫，是让「禁用」在观感上成立。界面层入口分散，单靠它拦不干净；单靠服务层则是
  一个看得见、拖得动却毫无反应的控件。
- **判据是 `isRoutePlaying()`（`player != null && !isFinished()`），不是 `mRoutePlayer != null`。**
  到达终点后路线**刻意不自动结束**（用户此刻「站」在终点，位置不再被推进），字面的 null 判断会留下一个
  全不透明、拖不动的摇杆。
- **需要「执行时重新求值」的状态不要传值。** `refreshJoyStickInputEnabled()` 与 `updateNotification()`
  投递的是会重读当前状态的任务，而不是调用点算好的布尔值 / 文案。传值版本有一条可达的陈旧写入竞态：
  定位线程算出「该恢复」的同时主线程刚好开了新路线，后到的那次会写回旧结论。
- **闭合路线无限循环，且 `getDistanceCovered()` 每圈归零**，所以进度显示必须带 `getLapCount()`，
  否则用户看到的是「没走完就跳回 0」。
- **进度靠轮询（500ms）不注册回调**：不会在 Activity 销毁时泄漏监听器，系统重建后自然接上。
  `RouteProgress.getRouteName()` 是重建后恢复界面状态的唯一来源；但**没有路线在跑时压根没有快照**，
  所以选中行还经 `onSaveInstanceState` 存索引。存索引成立的前提是 `queryAll` 的排序确定
  （`db.query(...)` **第 7 个参数**是 `CREATED_AT DESC`；该列是秒级的，同一秒创建的两条并列时 SQLite
  不保证相对顺序）。**若改了那个排序，恢复必须改成按名字查找**，否则旋转后会静默选中另一条路线。
- **列表行的选中态键在 `state_activated`**（`ListView` 对非 `Checkable` 的行调 `setActivated`），所以键在
  `state_checked` 的 `bg_tool_chip_toggle` / `chip_text_toggle` **不能**复用到行上——状态键对不上是
  **静默失败**：编得过、lint 过，选中就是没有视觉变化。行用 `bg_route_row` / `route_row_text`。
- **`bindService` 配 `BIND_AUTO_CREATE` 会创建服务**，而 `ServiceGo.onCreate` 会装 test provider 并起前台
  通知——光打开模拟界面就把模拟位置服务启动了，显然不是用户要的。所以界面只在 `ServiceGo.isAlive()` 时才在
  `onResume` 里绑定；服务没活着就不可能有路线在跑。`startSimulation()` 则无条件 `startForegroundService`，
  并把**路线首点**作为 extras 传进去：`onStartCommand` 用它初始化位置单元格，`startRoute()` 再用它同步摇杆
  内置地图；不传就先落到 `DEFAULT_LAT/DEFAULT_LNG`（36.66, 117.03）再被路线第一帧覆盖，界面会闪一下那个
  坐标。绑定是异步的，`startRoute` 由 `onServiceConnected` 补发（`mPendingStart`）。

**已知缺陷（没修，别当成能用）**：`MainActivity.isMockServStart` 同时是显示标志和「MainActivity 绑定过服务」
的代理（它守着两处 `unbindService`），而 `mServiceBinder` 只在 MainActivity 自己的连接里赋值。从模拟界面
启动路线再回到主界面时它仍是 false：FAB 图标停在 `ic_position`（「未启动」）而服务其实活着、路线正在跑。
此时在地图上点一个标记点、连按两次 FAB 就会落到 `stopGoLocation()` → `stopService`——第一次按走的是
`startGoLocation()`（它同样不知道路线在跑：会再 bind 一次、把位置瞬移到标记点、把 `isMockServStart` 置 true），
第二次按因为标记点已被清掉就进了停止分支，**静默停掉正在跑的路线和整个 mock 位置服务**。正确修法是让
「是否绑定」与「服务是否存活」分开记、FAB 状态从服务反推，没做。同类记账问题：
`RouteSimulationActivity.mBound` 只在 `onServiceConnected` 里置 true，而 `bindService` 有两处发出点
（`onResume` 与 `startSimulation` 的补发路径），连接没落地的那次绑定不会被 `unbindService` 释放。

**这个界面至今没有在任何设备上渲染过**（`exported="false"`，本机没有 arm64 设备或模拟器镜像，APK 只有
arm64-v8a）：行选中态的观感、速度档位片、空态与禁用态、按钮对比度、大字号下档位行是否被裁，**全部未经
视觉验收**。本项目已有一次同类教训（见上一节：纯读代码判定「顶多是一道浅缝」，截图是一大块白块）。
这是开放项，不是已完成。

**待办**：NFC 卡片 URL 的解析（现在只把收到的 URL / 包名 / source 原样显示供核对）；路线的编辑与删除；
播放期间的实时轨迹回放；速度自由输入。

### NFC 位置卡

`:nfc` 是独立的 Gradle 库模块（`com.android.library`，namespace `com.acooldog.nfc`），零第三方依赖、无资源、无 manifest 声明，设计目标是可整体复制到别的工程。它提供读卡（`NfcReaderSession`）、伪造贴卡派发（`NfcSender`）、配置持久化（`NfcConfigStore`）。

`NfcCardActivity` 读卡后**只显示 URL 与包名，不做任何解析**。四个按钮里「模拟nfc」把三个原始值（URL / 包名 / source）交给 `RouteSimulationActivity`——那个界面已经做完了选路线、档位与开始 / 结束模拟（见「路线模拟」一节），但它**不解析这三个值**，只原样显示供核对；解析坐标仍是后续工作。设计文档见 `docs/superpowers/specs/2026-09-19-nfc-card-design.md`。

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
