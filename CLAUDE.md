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

- 「海拔高度」是一个设置项，点击后的同一对话框同时提供手动输入与「获取当前海拔」，不要拆成第二个 Preference。获取功能不能复用 `MainActivity` 的定位结果（主页明确 `setIsNeedAltitude(false)`）：它独立请求 GNSS 海拔，收 3 个有效样本取中位数并按 0.1 米回填；用户确认后才保存。模拟位置服务运行时必须拒绝采样，避免读回伪造海拔。0.1 米是存储分辨率，不代表手机 GNSS 的真实垂直精度。
- 历史位置随机偏移是经度/纬度两轴独立均匀采样，所以范围是矩形而非圆。米转经度必须除以 `111320 * cos(latitude)`，纬度除以 `110574`；设置键 `setting_lon_max_offset` 对应经度、`setting_lat_max_offset` 对应纬度。

### Welcome flow

`WelcomeActivity` 只在首次成功进入应用前显示协议与「进入应用」按钮，并在同一个私有 SharedPreferences 文件记录 `KEY_HAS_ENTERED_APP`。后续启动隐藏控件，固定展示启动图 1.5 秒后进入主页；若定位权限被系统撤销则重新申请，授权后自动继续。首次进入仍保留协议、网络与 GPS 预检，后续启动不再被网络/GPS 状态卡在启动页。

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

**界面改动必须由用户实机验收。** 编译、lint、单元测试都覆盖不到布局与配色；代理不得自行安装 APK、运行 ADB、操作设备/模拟器或截图。用户完成实机测试并反馈结果。本项目已有一次实例——终审读了字节码与 AAR 资源后判定 `SearchView` 底板"顶多是一道浅缝"，实际截图显示是一大块形状不匹配的白块。
- **所有 Activity 均锁竖屏**（manifest 的 `screenOrientation="portrait"`）。`RouteDrawActivity` 尤其不能旋转：它持有 `MapView`、
  GL 覆盖层与一次性定位客户端，旋转会重建 Activity，把用户正在画的那条路线连同撤销栈一起丢掉。
  整个 App 当前不支持横屏；若日后要放开绘制页，应先做点集持久化，而不是直接删掉这行。

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
- **`startRoute()` 的两条拒绝各有各的不可省之处，但都不是「防御性装饰」这个笼统说法能覆盖的。**
  单就「拒绝」而言，「点数 < 2」那一半是被第二条吞掉的：0 点或 1 点的数组在 `RoutePlayer` 里算出的总长
  必为 0（少于 2 个点根本成不了段），第二条同样拦得住。第一条真正不可省的是 **`wgsPoints == null` 的
  短路**——少了它，null 输入会当场或在那道逐行验形上抛 NPE（`.length` / for-each 解引用），而
  `startRoute` 没有 try/catch，异常会逃到主线程，违反「记日志后降级」的约定。反过来第二条也不能被第一条
  替代：两个**重合**的点长度是 2，过得了第一条，而总长仍是 0。`RoutePlayer.isFinished()` 对零长路线返回 true
  （`!mClosed && mDistance >= mTotalDistance`，即 `0 >= 0`）：零长的**开环**路线一开始就是「已到达」，
  `advanceRoute()` 每 tick 在开头早退，位置永远不动而通知与界面写着「已到达终点」；零长的**闭合**路线
  反过来——永远不推进也永远不结束。两条之间还有一道逐行验形（null 行 / 长度不足 / 非有限值），它挡的是
  非有限的**输入**；总长那条则写成 `!(x > 0d)` 而不是 `x <= 0d`——**有限**输入也能算出 NaN 总长
  （两个极大坐标会让 Haversine 的差值溢出成 ±Inf，`Math.sin(±Inf)` 即 NaN），而 `NaN <= 0d` 为 false。
- **手动干预会终止播放**：`setPosition()` 第一件事就是 `stopRoute()`。但「位置只有一个写者」是**被收窄的，
  不是绝对的**：已经进入回写阶段的那个 tick 靠回写前的一次复检（`mRoutePlayer != player`）被丢弃，把窗口
  从一次 `advance() + getPosition()` 缩到相邻几条指令；彻底的单写者要把所有写者（含摇杆那一路）都并到
  定位线程，是另一个量级的改动，没做。推论：**`JoyStick.setCurrentPosition` 绝不能每 tick 调**——它会
  `mBaiduMap.clear()` 并 `animateMapStatus`，10Hz 下地图会被拖着抖。位置回写只写单元格，地图同步只在
  这几个时点做：启动服务的 `onStartCommand`、瞬移 `setPosition()`、开始 `startRoute()`、结束
  `stopRoute()`、到达 `onRouteFinished()`。
- **摇杆禁用分两层，缺一不可。** 权威层是 `ServiceGo` 的两个 listener 回调（播放期间一律忽略摇杆输入）；
  `JoyStick.setInputEnabled` 加它自己四个入口（方向收口 `processDirection`、窗口拖拽的 `onTouch`、内置地图
  落点、历史列表选点）的守卫，是让「禁用」在观感上成立。界面层入口分散，单靠它拦不干净；单靠服务层则是
  一个看得见、拖得动却毫无反应的控件。
- **路线会话期间整个悬浮窗收起（`mJoyStick.hide()`），不只是变淡。** 判据是
  `mRoutePlayer != null`，因此播放、暂停、到达终点等待结束三种状态都禁用并隐藏摇杆；通知栏的「显示摇杆」
  也必须拒绝。`startRoute()` 与 `stopRoute()` 都把 `mJoyStickDesiredVisible` 置 false，所以结束路线不会自动
  弹出摇杆。上面的输入双层守卫仍保留，用来挡住已经进入处理流程的触摸事件。
- **暂停由服务持有，不由 Activity 定时器模拟。** `pauseRoute()` 保存当前速度后把 `RoutePlayer` 速度置 0，
  `resumeRoute()` 恢复；暂停时切换档位只更新待恢复速度，不能隐式继续。`RouteProgress.isPaused()` 是服务传出的
  显式状态，不能靠速度为 0 推断，因为档位速度允许用户自由输入。
- **路线随机偏移是连续漂移，不是逐 tick 白噪声。** `SmoothRouteOffset` 在配置的东西/南北半径形成的椭圆内
  均匀选择目标，用 smoothstep 过渡并将额外漂移速度限制在约 0.35 m/s；实例与整次路线会话同寿命，闭合路线
  换圈绝不重置，因此起终点接缝不会跳。暂停时冻结偏移但仍应用当前值，继续后从原状态接着走。偏移加在
  `RoutePlayer` 输出的 WGS84 上，之后直接写 mock provider。
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
- **列表行的选中态键在 `state_activated`，而这个激活态是适配器自己在 `getView` 里
  `row.setActivated(position == mSelectedIndex)` 设上的**（回收复用，所以两个方向都要显式设）。`ListView`
  那条自动激活路径只在 `setItemChecked` / `setChoiceMode` 系列被调用时才走，本界面从未调用它们（全仓
  grep 无命中）——**别因为看到「ListView 会自动置激活态」就省掉这行 `setActivated`**。键在 `state_checked`
  的 `bg_tool_chip_toggle` / `chip_text_toggle` **不能**复用到行上：状态键对不上是**静默失败**，编得过、
  lint 过，选中就是没有视觉变化。行用 `bg_route_row` / `route_row_text`。
- **界面是「地图 + 可折叠面板」上下分栏**：上面 `MapView` 画出选中那条路线的 `Polyline` 和当前位置的
  `Marker`，下面是控制面板；面板可折叠，折叠后只剩把手那一行，**状态文本钉在把手上**，所以播放中收起也
  还看得见进度。折叠态与选中行一起进 `onSaveInstanceState`，用于系统重建恢复；本界面同样锁竖屏。
  `MapView` 完整转发 `onResume` / `onPause` / `onDestroy`，顺序与 `RouteDrawActivity` 一致。
- **两份点集各用各的，都不要再加换算。** `RouteConfig.getPoints()` 是 **BD09**，只用来画线、框相机
  （百度地图原生就是 BD09），闭合路线要把首点补到末尾才画得出回程那一段；喂给 `startRoute()` 的是
  `RouteRow.wgsPoints`（WGS84）。当前位置标记挂在**既有的 500ms 轮询**里（不新增定时器），位置来自
  `ServiceGoBinder.getCurrentPosition()`（WGS84），画到地图前走一次 `MapUtils.wgs2bd09`——
  这是本界面唯一一处坐标换算。**相机只在选中路线时框一次，轮询里绝不动**，否则用户没法自己拖地图；
  所有点重合时 `LatLngBounds` 退化成一点，改用一个固定近景级别。选路线是 `AlertDialog` +
  `setAdapter(mAdapter, …)`，弹窗里的行与列表**是同一份渲染**，没有第二套。
- **点「开始模拟」时若卡片带了 URL，成功开启路线之后再跳过去**。带包名时通过公开的
  `NfcSender` 发送 URI Record + AAR 的 `ACTION_NDEF_DISCOVERED`，失败后由模块回退到指定包名和通用的
  `ACTION_VIEW`；无包名时才直接走普通 `ACTION_VIEW`。**顺序是刻意的、失败不回滚**：目标应用打不开时
  模拟**已经跑起来了**，那才是有用的结果。没有卡片信息（从侧滑菜单进来）就走原来的纯模拟路径。
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
`startGoLocation()`（它同样不知道路线在跑：会再 bind 一次，并把位置单元格写成那个标记点、把
`isMockServStart` 置 true；那次写入约 100ms 后就被 `advanceRoute()` 用路线位置覆盖，位置不会真停在
标记点上），
第二次按因为标记点已被清掉就进了停止分支：服务停掉、模拟位置终止，Snackbar 会提示「模拟位置已终止」，
但**正在跑的那条路线是被一并杀掉的，这一步没有任何提示**。正确修法是让
「是否绑定」与「服务是否存活」分开记、FAB 状态从服务反推，没做。同类记账问题：
`RouteSimulationActivity.mBound` 只在 `onServiceConnected` 里置 true，而 `bindService` 有两处发出点
（`onResume` 与 `startSimulation` 的补发路径），连接没落地的那次绑定不会被 `unbindService` 释放。

**这个界面至今没有在任何设备上渲染过**（`exported="false"`，本机没有 arm64 设备或模拟器镜像，APK 只有
arm64-v8a）：行选中态的观感、速度档位片、空态与禁用态、按钮对比度、大字号下档位行是否被裁，**全部未经
视觉验收**。本项目已有一次同类教训（见上一节：纯读代码判定「顶多是一道浅缝」，截图是一大块白块）。
这是开放项，不是已完成。

**待办**：NFC 卡片 URL 的解析（现在只把收到的 URL / 包名 / source 原样显示供核对）；路线的编辑与删除；
播放期间的实时轨迹回放；速度自由输入。

### 读取 NFC

`:nfc` 是独立的 Gradle 库模块（`com.android.library`，namespace `com.acooldog.nfc`），零第三方依赖、无资源、无 manifest 声明，设计目标是可整体复制到别的工程。它提供读卡（`NfcReaderSession`）、伪造贴卡派发（`NfcSender`）、配置持久化（`NfcConfigStore`）。

`NfcCardActivity` 读卡后**只显示 URL 与包名，不做任何解析**。四个按钮里「模拟nfc」把三个原始值（URL / 包名 / source）交给 `RouteSimulationActivity`；模拟页也可通过「导入NFC配置」直接选择 `NfcConfigStore.getSavedConfigs()` 中的配置，无需先进入读卡页。成功开始路线后，有包名就通过 `NfcSender` 发伪 NDEF 事件，无包名才走普通 URL 跳转；从 URL 里解析坐标仍是后续工作。路线运行期间导入按钮禁用，因为切换载荷不会重新发送本次已经发出的 NFC。设计文档见 `docs/superpowers/specs/2026-09-19-nfc-card-design.md`。

两条硬约束：

- **路线模拟必须通过公开的 `NfcSender` 发卡。** 它构造带 URI Record 和 AAR 的 `ACTION_NDEF_DISCOVERED` 并 `setPackage(目标包名)`，目标 App 才能按真实贴卡事件处理。只允许引用 `com.acooldog.nfc` 公开 API，不要直接引用内部 dispatcher。
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
