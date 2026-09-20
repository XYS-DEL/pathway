# 行屿 路线模拟 — 设计

- 日期：2026-09-20
- 状态：设计已确认，待实现
- 相关：
  - `docs/superpowers/specs/2026-09-19-route-draw-design.md`（绘制路线，产出 `RouteConfig` 表）
  - `docs/superpowers/specs/2026-09-19-nfc-card-design.md`（NFC 位置卡，`RouteSimulationActivity` 的现有入口）

## 目标

把 `RouteSimulationActivity` 从占位界面做成真功能：**从已保存的路线里选一条，选定速度档位，开始模拟**，位置沿折线连续移动，用户切到别的 App 后仍持续移动。

## 非目标

- **不解析 NFC 卡片。** 卡片传过来的 URL / 包名 / source 继续原样显示。等卡片上的格式确定后单独做
- 不做曲线平滑（用户画的就是折线，不做贝塞尔/样条拟合）
- 不做路线的编辑与删除
- 不在界面上绘制实时轨迹回放
- 不做路线导出

## 架构：移动引擎放在 `ServiceGo` 里

**这是本次唯一载荷性的决定。**

本 App 的用途是**骗另一个 App**——用户按下「开始模拟」之后，一定会切走到目标 App。任何建立在 Activity 上的定时器都会在 `onPause` / 锁屏后被冻结或降频，路线当场停住。对 mock 定位软件来说这是致命缺陷。

而 `ServiceGo` 已经具备全部条件：

- 前台服务，系统不会随意回收
- `HandlerThread` 循环，每 ~100ms 一轮
- 一个「当前位置」单元格：`mCurLat` / `mCurLng` / `mCurAlt` / `mCurBea` / `mSpeed`
- 双 provider 推送（`setLocationGPS` / `setLocationNetwork`）

**摇杆的「移动」本质上就是按时间修改这个单元格**（`onMoveInfo` 里累加增量）。路线模拟沿用完全相同的机制：每 tick 改这个单元格。因此**现有的推送代码一行都不用改**——`setLocationGPS` / `setLocationNetwork` 对位置是怎么来的毫无感知。

> **被拒绝的方案：**在 Activity 里开 `Handler` 定时调 `mServiceBinder.setPosition()`。除了上面那条致命伤，它还会与摇杆打架——`setPosition()` 内部做 `removeMessages` + 重发 + 同步摇杆地图，10Hz 这样调用是错的。

## 新增：`RoutePlayer`（纯逻辑）

不依赖 `android.*`，可在普通 JVM 单元测试里运行，与 `RouteGeometry` 同一种风格。

```java
public final class RoutePlayer {
    RoutePlayer(double[][] wgsPoints, boolean closed, double speedMetersPerSecond)

    void     advance(double dtSeconds);      // 推进；越界自己夹住
    double[] getPosition();                  // {经度, 纬度}，WGS84；无点时返回 null
    double   getBearing();                   // 当前线段航向，度，0–360
    double   getDistanceCovered();           // 米
    double   getTotalDistance();             // 米
    int      getLapCount();                  // 闭合路线已完成的圈数
    boolean  isFinished();
    void     setSpeed(double mps);           // 播放中可改；0 表示暂停
}
```

### 算法

- **构造时预计算累积距离** `cum[]`，一次 O(n)。`closed` 为真时额外加上「末点 → 首点」那一段
- `advance` 用**单调前进的游标**定位当前线段，摊还 O(1)，不分配对象。距离单调不减，不需要二分查找
- 段内位置在经纬度上**线性插值**。几百米尺度下误差远小于 1cm；`线绘制` 的点本来就只有 2 米间隔，`点绘制` 的点也远达不到需要测地线插值的尺度
- 航向取当前线段的 initial bearing（标准公式）。`Location.setBearing()` 会被目标 App 读取，值得算对
- 越界一律自己夹住，不依赖调用方检查
- **开环**到达终点 → `isFinished()` 返回 true，位置停在终点
- **闭合**路线永远不 finished，到末尾回绕到 0 继续

### ⚠️ 坐标系：`RoutePlayer` 只认 WGS84

入参是 `double[][]`，每个元素是 `{经度, 纬度}` 的 **WGS84**——正是 `MapUtils.bd2wgs()` 的返回形状。

**刻意不收 `LatLng`。** 两个理由：

1. `LatLng` 在这套代码里既被用来装 BD09（地图层）也被用来装别的，**类型本身不携带坐标系信息**。用 `double[]` 至少能让「这是服务边界、是 WGS84」这件事在签名上看得见
2. 这个项目最大的坑就是坐标系混用（CLAUDE.md 专门有一节），减少一处歧义就少一处 bug

**用 `double[][]` 而不是 `List<double[]>`**：`RouteGeometry` 要新增的 `totalLengthMeters(double[][], boolean)` 若写成 `List<double[]>`，会与现有的 `totalLengthMeters(List<LatLng>, boolean)` **擦除后签名相同而无法重载**（编译期 name clash）。统一用数组，整条链路（界面 → binder → 引擎 → 几何）都是 `double[][]`，顺带避开这个坑。

**BD09 → WGS84 的转换在载入列表时一次性做完**（`RouteSimulationActivity` 逐个 `MapUtils.bd2wgs(p.longitude, p.latitude)`），结果随行保存、复用；不是每 tick 做，也不是选中时才做。

### 几何计算复用 `RouteGeometry`，不复制

`RouteGeometry` 现有 `distanceMeters(LatLng, LatLng)` 与 `totalLengthMeters(List<LatLng>, boolean)`。`RoutePlayer` 需要 `double` 版本。

**做法**：`RouteGeometry` 新增两个标量函数

```java
public static double distanceMeters(double lng1, double lat1, double lng2, double lat2)
public static double initialBearingDegrees(double lng1, double lat1, double lng2, double lat2)
```

并把现有的 `distanceMeters(LatLng, LatLng)` 改为**委托**给新的 double 版本，haversine 的算式只留一份。

**不给 `RouteGeometry` 加 `totalLengthMeters` 的 double 版本。** 折线总长由 `RoutePlayer` 在构造时算出来——它本来就要建累积距离数组，`mCumulative[段数]` 就是总长，白拿。给 `RouteGeometry` 再加一个 double 版只会多一份重复的循环，或者让 `List<LatLng>` 版本为了委托而多分配一堆中间数组换回同样的结果。

**因此列表里显示的总长也由 `RoutePlayer` 算**：载入时对每条路线构造一个速度为 0 的实例，读 `getTotalDistance()`。这不是绕路——规格要求列表总长与引擎一致，那就用引擎本身去算，是最强的「一致」。

不要在两处各写一份 haversine——审查规则把逐字重复的逻辑块视为缺陷。

### 总长会在两个界面之间差一点，这是预期的

绘制界面显示的总长是在 **BD09** 点集上算的；模拟界面显示的总长是在换算后的 **WGS84** 点集上算的。BD09 对 WGS84 是非线性偏移，两条折线的长度差在千分之几量级（1 km 的路线差一两米）。

**以 WGS84 那份为准**——它是真实地球上的距离。两个数字不完全相等是正常的，不要在实现里去「对齐」它们。

## `RouteProgress`（跨服务边界的状态快照）

```java
public final class RouteProgress {
    String  getRouteName();          // 正在跑的路线名
    boolean isFinished();            // true = 已到达终点
    double  getSpeed();              // m/s
    double  getDistanceCovered();    // 本圈已走，米
    double  getTotalDistance();      // 米
    int     getLapCount();           // 闭合路线已完成的圈数；开环恒为 0
}
```

不可变。由 binder 返回给界面。同一进程，不需要 `Parcelable`。

**三种状态由「是否为 null」加 `isFinished()` 表达**，不额外加 `isRunning()`：

| 返回值 | 含义 |
|---|---|
| `null` | 没有路线在跑 |
| 非 null，`isFinished() == false` | 模拟进行中 |
| 非 null，`isFinished() == true` | 已到达终点，等待用户结束 |

**`getRouteName()` 是必需的，不是装饰。** 界面靠它恢复状态：系统重建（旋转、进程被回收后回到前台）时，Activity 手上没有任何「我刚跑了哪条」的记忆，只有轮询回来的这个快照。没有它，重建后列表里没有一行是选中的，但按钮却写着「结束路线」——自相矛盾。有了它，`onResume` 第一轮轮询就能把选中行、档位、按钮文案全部对回去。

这也是选择轮询而非回调注册的意义所在：服务是唯一事实源，界面任何时刻都能从零重建出正确状态。

**`getLapCount()` 是为了让闭合路线的进度读数不说谎。** 闭合路线每绕完一圈 `getDistanceCovered()` 就回零重来，界面上表现为「已走 2.1km / 2.1km」突然跳回「已走 0m」——用户会当它是 bug。有了圈数，界面可以写成「第 3 圈 · 已走 200m / 2.1km」，归零就有了明确的解释。开环路线恒为 0，界面据此不显示圈数。

## `ServiceGo` 改动

### 每 tick 推进

在 `handleMessage` 的循环里，`Thread.sleep(100)` 之后、推送之前：

```java
long now = SystemClock.elapsedRealtime();
double dt = Math.min((now - mLastTickMs) / 1000.0, MAX_TICK_SECONDS);
mLastTickMs = now;

if (mRoutePlayer != null && !mRoutePlayer.isFinished()) {
    mRoutePlayer.advance(dt);
    mCurLng = ...;   // 写回同一个单元格
    mCurLat = ...;
    mCurBea = (float) mRoutePlayer.getBearing();
    mSpeed  = mRoutePlayer.getSpeed();
    if (mRoutePlayer.isFinished()) { /* 到达处理，见下 */ }
}
```

**`dt` 必须用真实时钟差，不能写死 0.1。** `Thread.sleep(100)` 会漂，几公里的路线上累积误差肉眼可见。

**`MAX_TICK_SECONDS = 1.0`**：夹住单次 dt。doze、GC 长暂停、调试器断点之后，不夹的话会一次跳出几百米——那是一条不真实的瞬移轨迹。

### Binder 新增三个方法

```java
public boolean       startRoute(String routeName, double[][] wgsPoints,
                                boolean closed, double speedMps)
public void          stopRoute()
public RouteProgress getRouteProgress()   // 未播放时返回 null
```

`startRoute` 对点数 < 2 的入参返回 `false`，不抛。`routeName` 由服务持有并原样放进 `RouteProgress`——`RoutePlayer` 不需要它，路线名是界面概念，不是引擎概念。

### 手动干预一律终止播放

`setPosition(double lng, double lat, double alt)`（主界面瞬移）在改单元格之前先 `stopRoute()`。

理由：播放中瞬移是自相矛盾的状态——两个写入者抢同一个单元格，位置来源不明。终止播放让「谁在控制位置」始终只有一个答案。

### 服务存活标志

```java
private static volatile boolean sAlive = false;   // onCreate 置 true，onDestroy 置 false
public  static boolean isAlive()
```

供 `MainActivity` 对账（见下）。静态状态跨 Activity 协调在本项目有先例（`MainActivity.mMarkLatLngMap` 是静态的，`showLocation()` 是静态方法）。

### 通知

现有通知有「显示摇杆 / 隐藏摇杆」两个动作（`SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW` / `_HIDE`）。

新增：

- 动作 `SERVICE_GO_NOTE_ACTION_ROUTE_STOP = "StopRoute"`，文案**「结束路线」**，只在播放期间出现
- 播放期间正文改为「正在模拟路线」
- 到达终点后正文改为「已到达终点」

**刻意不叫「停止模拟」**——那会被读成停掉整个 mock 服务。结束路线之后服务继续在终点维持位置模拟，这是对的：用户此刻正「站」在终点，位置仍需要被 mock。

实现上抽一个 `updateNotification()`，重建通知后 `NotificationManager.notify(SERVICE_GO_NOTE_ID, ...)` 覆盖。

## 摇杆：禁用，但**不能**每 tick 同步它

用户已确认：**播放期间禁用摇杆**（而非摇杆接管并终止播放）。

### 禁用方式

`JoyStick` 新增 `setInputEnabled(boolean)`：

- 忽略输入（移动、内置地图与历史列表触发的 `onPositionInfo`、以及窗口拖动）
- 整体 `alpha` 降到 ~0.4

**刻意不叫 `setEnabled`。** `JoyStick extends View`，`View.setEnabled(boolean)` 已存在且语义不同（它还牵动框架的 clickable/drawable 状态刷新）。覆写框架方法会让人误以为调用的是 `View` 的那一个，是容易看错的一处。

**禁用在两层落实：**

| 层 | 位置 | 作用 |
|---|---|---|
| 权威层 | `ServiceGo` 的两个 listener 回调（`onMoveInfo` / `onPositionInfo`）开头各加 `if (mRoutePlayer != null) return;` | 无论输入从摇杆哪个子视图来都拦得住，绕不过去 |
| 界面层 | `JoyStick.setInputEnabled` + `processDirection` / 两处 `onPositionInfo` 调用点 / `JoyStickOnTouchListener` 的守卫 | 让「禁用」在观感和手感上都成立，而不是默默无反应 |

只做界面层不够——摇杆有三个子窗口（摇杆 / 地图 / 历史），入口分散；只做权威层则会得到一个「能拖、能点、就是位置不动」的摇杆，比明确灰掉更糟。

**降透明是必须的，不是打磨项。** 本项目已经吃过一次这个亏——终审 Ruling UI-4：一个 `setEnabled(false)` 但外观不变的按钮，用户看到能点却没反应，判定为回归。一个看起来正常、拖了没反应的摇杆是同一个错误。

### 为什么不每 tick 调 `setCurrentPosition()`

`JoyStick.setCurrentPosition(lng, lat, alt)` 内部是：

```
MapUtils.wgs2bd09() → mCurMapLngLat = ... → resetBaiduMap()
resetBaiduMap() → mBaiduMap.clear() + setMyLocationData() + animateMapStatus()
```

即：**清空整个地图 + 重设定位点 + 启动一次地图动画**。从 `HandlerThread` 以 10Hz 调用它是双重错误——太重，且百度地图操作应在主线程。

**做法**：播放开始与结束时**各同步一次**，播放期间不同步。

已知取舍：摇杆内置地图在播放期间停在路线起点。摇杆此刻是灰的，灰态本身已经说明它不可用，因此不会造成「它显示的位置是真的」这种误导。

播放结束（用户结束 / 到达终点）时摇杆恢复可用并同步到当时位置。

## 服务生命周期与跨界面状态对账

模拟界面**自己负责启动服务**。不能要求用户先去主界面标记一个点再开始——为了跑一条路线而随便点个点是荒谬的前置动作。

### 开始模拟的完整流程

1. 预检，全部复用 `GoUtils`，与 `MainActivity.doGoLocation()` 同一套：

   | 检查 | 失败时 |
   |---|---|
   | `GoUtils.isNetworkAvailable(this)` | Toast 提示 |
   | `GoUtils.isGpsOpened(this)` | `showEnableGpsDialog` |
   | `Settings.canDrawOverlays(...)`（悬浮窗） | `showEnableFloatWindowDialog` |
   | `GoUtils.isAllowMockLocation(this)`（模拟位置） | `showEnableMockLocationDialog` |

2. `startForegroundService(intent)`，**把路线的第一个点作为 extras 传进去**（`MainActivity.LNG_MSG_ID` / `LAT_MSG_ID` / `ALT_MSG_ID`）

   这一步不能省：`ServiceGo.onStartCommand` 会用 extras 初始化单元格（缺省值是 `DEFAULT_LAT 36.667662` / `DEFAULT_LNG 117.027707`）。不传的话，服务启动瞬间会把位置扔到那个默认坐标，然后才被路线第一帧覆盖——一次可见的瞬移。

3. `bindService(..., BIND_AUTO_CREATE)`

4. `startRoute(...)`

5. `GoUtils.isWifiEnabled(this)` → `GoUtils.showDisableWifiDialog(this)`

   WiFi 开着会让被 mock 的位置弹回真实位置（本项目的已知限制，见 CLAUDE.md）。每次瞬移后都会警告，开始模拟同样要警告。

### 与 `MainActivity` 对账

`MainActivity.isMockServStart` 是 Activity 自己的字段，在 `startGoLocation()` / `stopGoLocation()` 里维护。模拟界面独立启动服务后，这个字段是错的——回到主界面，FAB 显示「未启动」，但服务实际在跑并在移动。

**修法**：`MainActivity.onResume()` 里用 `ServiceGo.isAlive()` 对账：

```java
isMockServStart = ServiceGo.isAlive();
```

顺带修掉一个既有的潜伏问题：服务被系统杀死后，这个字段本来就是陈旧的。

## 界面

`RouteSimulationActivity` 重做。**这一版界面不需要地图**（不做实时轨迹回放），因此也没有 `MapView` 的生命周期转发负担。

```
┌────────────────────────────────────┐
│ 状态：未开始 / 模拟中 1.2km 已走 340m│
├────────────────────────────────────┤
│ 路线                                │
│ ┌────────────────────────────────┐ │
│ │ 操场外圈        128 点  闭合 2.1km│ │  ← 选中高亮
│ │ 上班路线         46 点  开环 5.3km│ │
│ │ 小区绕圈         12 点  闭合  800m│ │
│ └────────────────────────────────┘ │
├────────────────────────────────────┤
│ 速度： [步行] [跑步] [骑行]          │
├────────────────────────────────────┤
│         [   开始模拟   ]            │
├────────────────────────────────────┤
│ NFC: EXTRA_CARD_URL = ...           │
│      EXTRA_CARD_PACKAGE = ...       │
│      EXTRA_SOURCE = ...             │
└────────────────────────────────────┘
```

- 路线列表来自 `DataBaseRoute.queryAll(db)`，已能直接返回 `List<RouteConfig>`
- 每行显示名称、点数、总长、闭合/开环
- 列表为空时提示「还没有路线，先去绘制一条」
- **总长按 WGS84 计算**（与引擎一致，见上文说明）
- 档位三选一，复用 `setting_walk`(1.2) / `setting_run`(3.6) / `setting_bike`(10.0) m/s，与摇杆同一套数字
- **档位在开始前选，播放中改也允许**（改的是同一个字段，下一帧生效）
- 主按钮二态：未播放时「开始模拟」，播放中「结束路线」
- 状态条在闭合路线播放中写成「第 N 圈 · 本圈 200m / 2.1km」——`getLapCount()` 见上文；开环路线不显示圈数

### 进度显示用轮询，不用回调注册

每 **500ms** 轮询一次 `getRouteProgress()`，`onResume` 起、`onPause` 停。

理由：轮询不会在 Activity 销毁时泄漏监听器，系统重建后自然接上，服务始终是唯一事实源。注册回调还要处理「Activity 没了但服务还在跑」的解绑问题，纯属自找麻烦。

## 到达处理

用户已确认：**开环路线走到终点后停住并提示**。

但到达那一刻用户多半在**别的 App 里**，界面上提示什么都没人看。因此：

- 前台通知正文改为「已到达终点」（不管在前台还是后台都可靠）
- 「结束路线」动作保留，直到用户主动结束
- 摇杆恢复可用
- 界面若开着，状态条同步显示「已到达终点」

## 文件清单

**新增**

- `app/src/main/java/com/iterlocus/pathway/RoutePlayer.java` — 纯逻辑引擎
- `app/src/main/java/com/iterlocus/pathway/RouteProgress.java` — 状态快照
- `app/src/test/java/com/iterlocus/pathway/RoutePlayerTest.java` — 单元测试
- `app/src/main/res/layout/route_sim_route_item.xml` — 路线列表行
- `app/src/main/res/drawable/bg_route_row.xml` — 列表行选中态背景（键 `state_activated`）
- `app/src/main/res/color/route_row_text.xml` — 列表行文字色（键 `state_activated`）

**修改**

- `app/src/main/java/com/iterlocus/pathway/service/ServiceGo.java` — 路线模式、tick、binder API、存活标志、通知
- `app/src/main/java/com/iterlocus/pathway/joystick/JoyStick.java` — `setEnabled(boolean)`
- `app/src/main/java/com/iterlocus/pathway/RouteGeometry.java` — `double` 版距离 + 航向；现有 `LatLng` 版改为委托
- `app/src/main/java/com/iterlocus/pathway/RouteSimulationActivity.java` — 重做
- `app/src/main/res/layout/activity_route_simulation.xml` — 重做
- `app/src/main/java/com/iterlocus/pathway/MainActivity.java` — 菜单项处理 + `onResume` 对账
- `app/src/main/res/menu/menu_nav.xml` — 侧滑新增 `nav_route_sim`（挨着「绘制路线」）
- `app/src/main/res/values/strings.xml` — 新增文案，`app_*` 前缀

**复用**

- 档位三选一复用界面改版确立的模式：`RadioGroup` + `RadioButton` 配 `android:button="@null"`，背景 `bg_tool_chip_toggle`、文字色 `res/color/chip_text_toggle`。绘制界面的模式选择就是这么做的，观感一致

**新增的选中态资源（原计划复用，复核后不可行）**

列表行的选中态**不能**复用 `bg_tool_chip_toggle`：那个 selector 的键是 `state_checked`，只有 `Checkable`（`RadioButton` / `CheckBox` / `CheckedTextView`）会置上；而 `ListView` 的行是普通 `LinearLayout`，`setItemChecked` 对它调的是 `setActivated`，置的是 `state_activated`——键对不上，选中不会有任何视觉变化。

因此新增：

- `app/src/main/res/drawable/bg_route_row.xml` — 键 `state_activated`：选中为主题色实底，未选中半透明白，圆角与描边沿用悬浮片的语言
- `app/src/main/res/color/route_row_text.xml` — 键 `state_activated`：选中白字，未选中深色

`View.setActivated` 会经 `ViewGroup.dispatchSetActivated` **传播到子视图**，所以行内两个 `TextView` 用同一个文字色 selector 就能跟着变，不需要在 `getView` 里手动逐个设色。

配色与 `bg_tool_chip_toggle` / `chip_text_toggle` 保持一致（选中 = 主题色实底 + 白字，4.54:1 过 WCAG AA）。

**注册与启动模式**：`RouteSimulationActivity` 已在 manifest 中注册。它承载 NFC 前台调度，**必须保持默认启动模式**（`nfc` 模块 README 明确要求接收标签的 Activity 不能是 `singleInstance`）。`MainActivity` 是 `singleInstance`，别照抄。

**图标**：`menu_nav.xml` 目前「NFC 位置卡」与「绘制路线」共用 `ic_menu_nfc`。新增项继续复用它，还是补一个专用图标，属于打磨范畴——**本次复用，不新增图标**。

## 错误处理

沿用项目风格：出错记 `XLog.e("ROUTE: ERROR - <method>")` 并降级，不抛异常、不崩溃。

| 情形 | 处理 |
|---|---|
| 库中一条路线都没有 | 提示「还没有路线，先去绘制一条」，开始按钮置灰 |
| 未选中路线就点开始 | 提示先选一条，不启动 |
| 选中的路线点数 < 2 | 拒绝开始并提示（`startRoute` 返回 false） |
| 播放中想换一条路线 | 主按钮此刻是「结束路线」，**不能直接切**。先结束再开始。不做「热切换」——那会让「位置现在归谁控制」多出一种中间状态 |
| 服务绑定失败 / `mServiceBinder == null` | 判空，提示而不是崩（沿用本项目惯例） |
| 预检未通过 | 走对应的 `GoUtils` 对话框，不启动 |
| `dt` 异常大（doze / GC 暂停后） | 夹到 `MAX_TICK_SECONDS` |
| 路线点全重合（总长为 0） | 拒绝开始并提示，避免除零与原地空转 |
| 到达终点 | 停住、通知改文案、摇杆恢复 |
| 播放中用户在通知栏点「结束路线」 | 等同于界面上的结束 |
| 播放中主界面执行瞬移 | `setPosition` 内部先终止播放 |
| 数据库读取失败 | 记日志，列表按空处理 |

## 测试

**`RoutePlayer` 与新增的 `RouteGeometry` 函数是纯逻辑，采用先写测试再写实现。** 覆盖：

`RoutePlayer`：

- 直线推进：给定速度与 dt，走过的距离与位置正确
- 跨段插值：距离落在第 k 段内时，位置在该段上按比例
- 闭合回绕：走到总长之后回到起点继续，且永不 `isFinished`
- 开环终点：到达总长后 `isFinished()` 为 true，位置停在末点
- 越界夹紧：`advance` 传入超过剩余距离的值不会跑过头
- 圈数计数：闭合路线每绕完一圈 `getLapCount()` 加一；开环路线恒为 0；单次 `advance` 跨过一整圈以上时计数正确
- 航向：正东 / 正北 / 正南等基本方向的取值
- `dt` 为 0、速度为 0 时不移动
- 单点 / 空列表：构造不抛，`getTotalDistance()` 为 0
- `setSpeed` 之后推进速度随之改变

`RouteGeometry` 新增部分：

- `distanceMeters(double...)` 与 `distanceMeters(LatLng, LatLng)` 结果一致（委托正确）
- `initialBearingDegrees` 在正东/正北/正西/正南四个方向上的取值
- 经度跨越 180° 子午线时不产生反向航向

**服务与界面按本项目既有方式验收**：编译 + lint + 单元测试 + **真机截图**。编译与 lint 覆盖不到布局与配色，本项目已有一次实例（终审读字节码判定 SearchView 底板「顶多是一道浅缝」，截图显示是一大块白块）。

**必须真机验证的一条**：按下开始模拟后**切到别的 App**（或锁屏），位置是否继续移动。这是整个架构选择的立足点——如果它不成立，把引擎放进服务就失去了全部意义。

装机验证只能由你完成：选路线是否顺手、档位切换是否生效、摇杆在播放期间是否确实不可拖、切后台是否继续走、到达终点是否停住并提示、通知栏「结束路线」是否管用、**旋转屏幕后选中行与按钮状态是否对得回去**（这是验证 `getRouteName()` 那套恢复逻辑的唯一办法）。

## 待办（不在本次范围）

- **解析 NFC 卡片 URL**：卡片上的格式待定，确定后单独做（可能是百度/高德分享链，也可能是自定义 scheme）
- 路线的编辑与删除界面
- 播放期间在界面上绘制实时轨迹回放
- 速度自由输入（本次只有三档预设）
