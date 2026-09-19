# 行屿 绘制路线 — 设计

- 日期：2026-09-19
- 状态：设计已确认，待实现
- 相关：`docs/superpowers/specs/2026-09-19-nfc-card-design.md`（NFC 位置卡，提供 URL 交接）

## 目标

在百度地图上手动绘制一条路线，支持点绘制与线绘制两种模式、线路闭合、按总长等距插入 N 个点，并把结果保存为本地配置，供后续的「线路模拟」功能调用。

**这是绘制，不是模拟。** 开始模拟、沿路线移动、速度控制等都不在本次范围内。

## 非目标

- 不做线路模拟（沿路线移动、驱动 `ServiceGo`）
- 不做从已保存路线中「选一条」的界面（属于模拟侧）
- 不做地图底图之外的地理搜索、POI 选点

## 界面

新增 `RouteDrawActivity`。地图占满屏幕，右侧是可收起的工具面板；面板收起后只留一个浮动按钮，方便全屏绘制。

```
┌──────────────────────────┬────────────┐
│                          │ 工具    [×]│
│                          │            │
│      百度地图             │ 模式       │
│   + 路线绘制层            │ ○点  ●线   │
│   (自定义 View 覆盖)      │            │
│                          │ ☑ 锁定地图 │
│                          │            │
│                          │ [ 密化   ] │
│                          │ [ 撤销上一步]│
│                          │ [ 清空   ] │
│                          │            │
│                          │ [ 完成绘制 ]│
└──────────────────────────┴────────────┘
```

「密化」是按钮，点击后弹出输入框填加点数 N——不常驻输入框，避免占地方和拖拽时误触。

### 锁定地图

地图自带的拖动/缩放手势会与绘制手势冲突，必须有隔离手段。

- **锁定**：`mBaiduMap.getUiSettings().setAllGesturesEnabled(false)`，触摸事件全部交给绘制层
- **解锁**：恢复地图手势，绘制层不再接收触摸，只负责显示

默认进入时为锁定状态。

## 绘制层

新增 `RouteDrawOverlayView`，一个透明自定义 `View` 盖在 `MapView` 之上（触摸处理参考项目里 `RockerView` 的写法）：

- 用 `mBaiduMap.getProjection()` 做屏幕坐标 ↔ 经纬度互转
- 监听 `BaiduMap.OnMapStatusChangeListener`，地图状态一变就 `invalidate()`，保证线和顶点跟着地图走
- 用 `Canvas` 自己画：路线折线、每个顶点的圆点、起始点的高亮圈、线绘制时跟随手指的橡皮筋预览

不用百度地图的 `Polyline` 覆盖物：折线本身它画得了，但顶点手柄、闭合指示、橡皮筋仍要自定义层，两套混用反而更难维护。统一自己画，坐标全部经投影换算。

## 两种绘制模式

| | 点绘制 | 线绘制 |
|---|---|---|
| 手势 | 点击落一个顶点，相邻顶点自动连成直线段 | 按住拖动，轨迹采样成一串密集点 |
| 采样 | 每次 `ACTION_UP`（且未构成闭合）落一个点 | `ACTION_MOVE` 中过滤：与上一个点距离小于 **2 米**则丢弃 |
| 闭合 | **点击初始点**（命中半径 **24dp**） | **松手时落点靠近起点**（同样 24dp） |
| 闭合前提 | 已有 **≥2 个点**，否则点击初始点只当作再落一个点 | 已有 **≥2 个点** |
| 典型点数 | 几十 | 几百到几千 |

线绘制的 2 米过滤是为了避免一次拖动产生上千个近乎重合的点——那些点对模拟毫无意义，只会拖慢渲染。

## 闭合

闭合后 `closed = true`，渲染时补上「末点 → 首点」那一段。

- 点绘制：点击初始点触发。**不足 2 个点时拒绝闭合**（2 个点是下限，此时闭合得到 A→B→A；1 个点无从闭合）
- 线绘制：松手时落点落在起点命中半径内触发

两种模式都支持闭合，行为一致。

## 密化（按总长等距）

面板点「密化」→ 弹框输入**加点数 N**。

**N 的定义是「新增点的数量」，不是分段数。** 总长等分为 N+1 段，在 N 个等分位置各插一个点：

> 路线总长 L、N = 3 → 在 L/4、2L/4、3L/4 三处各插入一个点，共新增 3 个点。

步骤：

1. 按测地距离（haversine）算出整条路线的总长
2. 在 N 个等分位置计算新点坐标（落在哪一段上就按该段线性插值）
3. **原有顶点全部保留**，与新点合并后按弧长排序

N 越大路径越平滑。密化是可撤销操作，效果不满意可以撤销后换一个 N 重来。保存时存的是当前点集。

边界：N ≤ 0 或无点时不做任何事；总长为 0（所有点重合）时不做任何事，提示用户。

## 撤销与清空

- **撤销上一步**：维护一个点集快照栈。每次「落点」「完成一次拖绘」「闭合」「密化」各压一次快照，撤销弹出栈顶恢复。栈空时按钮置灰。
- **清空**：清空点集与闭合标记，同时清空撤销栈（不可撤销，弹框二次确认）。

## 保存

「完成绘制」→ 弹框填名称 → 保存。

- **至少 2 个点才能保存**，不足时提示并拒绝（与闭合下限一致，1 个点的路线没有意义）
- **同名路线不允许覆盖。** 检测到重名时拒绝写入并提示「该名称已存在，请换一个」。不覆盖、不生成副本。理由：路线是花了工夫画的，静默覆盖等于丢数据；而自动改名（如 `路线(2)`）会让用户分不清哪条是哪条

### 名称校验

弹框确认前先校验，任何一条不过就就地提示、不写库：

| 规则 | 说明 |
|---|---|
| 先 `trim()` | 去掉首尾空白后再判定，避免 `"路线"` 与 `"路线 "` 被当成两条 |
| 长度 1–32 | trim 后计。空串拒绝；超长拒绝（32 是显示与后续导出的舒适上限） |
| 禁止控制字符 | 含换行 `\n`、回车 `\r`、制表符 `\t`，以及 `0x00`–`0x1F`、`0x7F` |
| 禁止以下符号 | `/ \ : * ? " < > \|` —— 这些在后续若要导出成文件名时会惹麻烦，提前挡掉 |
| 其余允许 | 中英文、数字、空格、下划线、连字符、括号、点号等 |

重名判定用 `trim` 后**大小写不敏感**比较（与 `nfc` 模块 `SavedNfcConfig` 的 `equalsIgnoreCase` 惯例一致），数据库列上加 `COLLATE NOCASE` 的 `UNIQUE` 约束作为兜底。

校验逻辑放在 `RouteGeometry` 之外的独立小类 `RouteNameValidator`（纯逻辑，可单元测试），不要散在 Activity 里。

## 数据模型与存储

```java
RouteConfig {
    String name;
    boolean closed;
    List<LatLng> points;   // BD09
    long createdAt;        // 秒
}
```

新增 `DataBaseRoute`（`SQLiteOpenHelper`，沿用 `DataBaseHistoryLocation` 的写法）：

```sql
CREATE TABLE RouteConfig (
  DB_COLUMN_ID         INTEGER PRIMARY KEY AUTOINCREMENT,
  DB_COLUMN_NAME       TEXT    NOT NULL COLLATE NOCASE UNIQUE,
  DB_COLUMN_CLOSED     INTEGER NOT NULL,
  DB_COLUMN_POINTS     TEXT    NOT NULL,
  DB_COLUMN_CREATED_AT BIGINT  NOT NULL
)
```

`COLLATE NOCASE UNIQUE` 让重名在数据库层也拦得住，与代码里的 `equalsIgnoreCase` 判定保持一致。

点集序列化为 JSON 数组存进一个 TEXT 列。路线点数不定，拆成行既难查也没意义。

`DB_VERSION = 1`，`onUpgrade` 沿用项目现有惯例（drop 后重建）。

### ⚠️ 坐标系：存 BD09

**存的是 BD09（百度地图原生坐标系）**，因为你是在地图上画的，存下来就是你看到的。

**后续线路模拟调用时必须转换**：`ServiceGo` 走的是 `LocationManager.setTestProviderLocation()`，它要 **WGS84**。所以模拟侧读出行点后要逐个 `MapUtils.bd2wgs(lng, lat)` 再喂给服务。

**`MapUtils` 的入参顺序是 (经度, 纬度)，返回数组也是 `{经度, 纬度}`；而百度 SDK 的 `LatLng` 构造是 (纬度, 经度)。** 本项目已经因为坐标系踩过坑，这里是第二个高风险点——`RouteGeometry` 必须用单元测试钉死。

## 文件清单

**新增**

- `app/src/main/java/com/iterlocus/pathway/RouteDrawActivity.java`
- `app/src/main/java/com/iterlocus/pathway/RouteDrawOverlayView.java` — 绘制层
- `app/src/main/java/com/iterlocus/pathway/RouteConfig.java` — 数据模型
- `app/src/main/java/com/iterlocus/pathway/RouteGeometry.java` — 纯逻辑：弧长、密化、闭合判定、采样过滤
- `app/src/test/java/com/iterlocus/pathway/RouteGeometryTest.java` — 单元测试
- `app/src/main/java/com/iterlocus/pathway/RouteNameValidator.java` — 纯逻辑：名称合规校验
- `app/src/test/java/com/iterlocus/pathway/RouteNameValidatorTest.java` — 单元测试
- `app/src/main/java/com/iterlocus/pathway/database/DataBaseRoute.java`
- `app/src/main/res/layout/activity_route_draw.xml`
- `app/src/main/res/layout/route_draw_tools.xml` — 工具面板

**修改**

- `app/src/main/res/menu/menu_nav.xml` — 侧滑新增「绘制路线」（id `nav_route_draw`）
- `app/src/main/java/com/iterlocus/pathway/MainActivity.java` — 处理该菜单项
- `app/src/main/AndroidManifest.xml` — 注册 `RouteDrawActivity`
- `app/src/main/res/values/strings.xml` — 新增文案，沿用 `app_*` 前缀

`RouteDrawActivity` 沿用 `BaseActivity`（需要手动填状态栏，见 CLAUDE.md 的说明）。它含 `MapView`，必须完整转发 `onResume` / `onPause` / `onDestroy`，与 `MainActivity` 一致。

## 错误处理

沿用项目风格：出错记 `XLog.e("ROUTE: ERROR - <method>")` 并降级，不抛异常、不崩溃。

| 情形 | 处理 |
|---|---|
| 保存时点数 < 2 | 提示并拒绝 |
| 名称不合规（空、超长、含控制字符或禁用符号） | 就地提示具体原因并拒绝 |
| 名称重名 | 提示「该名称已存在，请换一个」并拒绝，**不覆盖** |
| 写库时撞上 UNIQUE 约束（并发兜底） | 按重名处理，提示换名 |
| 密化时无点或总长为 0 | 不做任何事，提示用户 |
| 密化输入非法（空、非数字、≤0） | 提示并拒绝，不关闭弹框以外的任何状态 |
| 点绘制下点数 < 2 时点初始点 | 不闭合，当作再落一个点 |
| 撤销栈为空 | 按钮置灰 |
| 数据库读写失败 | 记日志，界面继续可用 |

## 测试

**`RouteGeometry` 是本次唯一的纯逻辑，采用先写测试再写实现。** 覆盖：

- 弧长：单段、多段累加、含跨纬度、零长度
- 密化：插入点数是否恰为 N、是否按弧长排序、原顶点是否全部保留、N=1、N 大于点数的极端情况、总长为 0
- 闭合判定：命中半径内/外、**点数不足 2 时拒绝**、边界值（恰好等于半径）
- 线绘制采样过滤：距离小于阈值被丢弃、大于阈值被保留
- 坐标顺序：专门写一组用例盯住 `(经度, 纬度)` 与 `LatLng(纬度, 经度)` 的差异，防止写反

**`RouteNameValidator` 同样是纯逻辑，一并先写测试。** 覆盖：

- 空串、纯空白、超长（32 / 33 边界）
- 含 `\n`、`\r`、`\t`、`0x00` 等控制字符
- 含 `/ \ : * ? " < > |` 各一个用例
- 正常中英文名、含空格与括号的名、恰好 32 字符
- `trim` 生效：`"  路线  "` 与 `"路线"` 视为同名

这是仓库里第一批真实单元测试（`nfc` 模块另有一个 `NfcPayloadParserTest`）。

装机验证只能你来：手势是否跟手、锁定地图是否生效、闭合是否准确、密化后路线是否合理、保存后能否读出。

## 待办（不在本次范围）

- 线路模拟：读 `RouteConfig` 表、选路线、把 BD09 转 WGS84 后驱动 `ServiceGo`
- 从 NFC 位置卡进入模拟路线的那条链路（`RouteSimulationActivity` 目前是占位）
- 路线的编辑与删除界面（本次只有保存和覆盖）
