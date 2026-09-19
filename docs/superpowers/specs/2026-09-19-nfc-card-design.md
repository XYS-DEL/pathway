# 行屿 NFC 位置卡 — 设计

- 日期：2026-09-19
- 状态：设计已确认，待实现
- 涉及模块：`:app`、新增依赖 `:nfc`

## 目标

贴一张自制的 NFC 标签，把卡片上的 URL 读进来并解析出坐标，通过界面按钮把这条信息流转到「模拟路线」功能。后者本次只做占位。

## 非目标

**不做伪造贴卡派发。** 引入的 `com.acooldog.nfc` 模块里有 `NfcSender`，它能构造 `ACTION_NDEF_DISCOVERED` 并 `setPackage(目标包名)`，让某个 App 收到一次与真实贴卡无法区分的广播。**本功能不接入它。**「模拟nfc」走的是行屿自己的界面流转，不向任何第三方 App 派发任何 Intent。

不使用 `NfcSender` 的直接后果：

- 卡片上的**包名字段不参与任何派发逻辑**，只做显示、保存与共享
- 界面上的按钮不叫「发送」而叫「模拟nfc」，因为它进入的是行屿自己的界面

同时不做：往实体标签写数据（模块无此能力，本次也不补）；「模拟路线」功能本身。

## 卡片格式

不约定具体域名与路径，**宽松识别**：任意 URL，坐标放在查询参数里。

```
https://任意域名/任意路径?lat=39.915&lng=116.404&name=公司&coord=wgs84
```

| 语义 | 可接受的参数名（大小写不敏感） | 必需 |
|---|---|---|
| 纬度 | `lat`、`latitude` | 是 |
| 经度 | `lng`、`lon`、`long`、`longitude` | 是 |
| 名称 | `name`、`title`、`label` | 否 |
| 坐标系 | `coord`、`cs`，取 `wgs84` / `bd09` / `gcj02` | 否 |

**坐标系默认 `wgs84`**（GPS 通用标准）。卡片若由百度地图写入（BD09），必须在 URL 里显式带 `coord=bd09`，否则会偏移约 500 米。

解析失败（无查询串、缺经纬度、数值非法、超出经纬度范围）不视为错误：界面照常显示 URL 与包名，仅禁用「模拟nfc」并提示原因。

## 坐标系处理

行屿内部有两套坐标（详见 CLAUDE.md）：地图图层用 BD09LL，`ServiceGo` 的模拟 provider 用 WGS84。

- 卡片坐标为 WGS84 → provider 入参直接用；地图坐标用 `MapUtils.wgs2bd09(lng, lat)` → `{bdLng, bdLat}`
- 卡片坐标为 BD09 → 地图坐标直接用；provider 入参用 `MapUtils.bd2wgs(lng, lat)` → `{wgsLng, wgsLat}`
- 卡片坐标为 GCJ02 → 先 `MapUtils.gcj02towgs84(lng, lat)` 得到 WGS84，再 `MapUtils.wgs2bd09` 得到 BD09。**`MapUtils` 没有 gcj02 → bd09 的直达方法，必须走这两步**

**入参顺序陷阱**：`MapUtils` 全部方法的入参顺序是 **(经度, 纬度)**，返回数组也是 `{经度, 纬度}`；而百度 SDK 的 `LatLng` 构造是 **(纬度, 经度)**。本仓库最容易写反的就是这里，`LocationCardTest` 要专门覆盖。

换算统一在 `LocationCard` 内完成，对调用方只暴露 `getLatWgs84()` / `getLngWgs84()` 与 `getLatBd09()` / `getLngBd09()` 两组取值，避免调用方各自记坐标系的账。

## 界面

### `NfcCardActivity`（新增，独立 Activity）

不设 `launchMode`，即默认的 standard 模式。模块 README 明确要求接收标签的 Activity 不能用 `singleInstance`；app 的 `<application>` 与 `MainActivity` 都设了 `singleInstance`，本 Activity 必须避开。入口在侧滑菜单。

```
NFC 状态：● 就绪 ／ ○ 未开启 [去设置] ／ ✕ 本机无 NFC 硬件

URL      https://example.com/goto?lat=39.915&lng=116.404
包名     （卡片未携带时显示占位文案）
坐标     39.915000, 116.404000  (WGS84)   名称：公司

[ 模拟nfc ]  [ 保存配置 ]  [ 载入本地配置 ]  [ 共享nfc ]
```

状态用 `NfcReaderSession.getStatus()`。`DISABLED` 时「去设置」跳 `Settings.ACTION_NFC_SETTINGS`。

### 按钮行为

| 按钮 | 行为 |
|---|---|
| 模拟nfc | 解析出坐标才可点。带 url / 包名 / 经纬度 / 名称 / 坐标系跳 `RouteSimulationActivity`。无坐标时置灰，点击提示具体原因（缺经纬度 / 数值非法 / 超出范围） |
| 保存配置 | 弹框填名称。包名沿用模块约束：`SavedNfcConfig.isComplete()` 要求名称、URL、包名三者齐全，缺一即被 `saveSavedConfig` 静默丢弃，因此弹框在包名为空时一并要求填写，并在弹框内写明原因 |
| 载入本地配置 | `NfcConfigStore.getSavedConfigs()` 列表弹窗，选中后回填 URL 与包名；列表为空时提示 |
| 共享nfc | 复用 app 已有的 `ShareUtils.shareText()`，内容为 URL 与包名两行纯文本 |

### `RouteSimulationActivity`（新增，占位）

不含任何业务逻辑。界面列出收到的全部参数，加一行「模拟路线功能开发中」，用于验证上游传参正确，避免功能做完才发现字段没接上。

## 传参契约

`RouteSimulationActivity` 接收以下 extras（常量定义在该 Activity 内，供后续开发引用）：

| key | 类型 | 说明 |
|---|---|---|
| `EXTRA_CARD_URL` | String | 卡片原始 URL |
| `EXTRA_CARD_PACKAGE` | String | 卡片携带的包名，可能为空 |
| `EXTRA_LAT` | double | 纬度 |
| `EXTRA_LNG` | double | 经度 |
| `EXTRA_NAME` | String | 卡片上的名称，可能为空 |
| `EXTRA_COORD_TYPE` | String | `wgs84` / `bd09` / `gcj02` |

经纬度按卡片**原始**坐标系传递，不在跳转前换算，由「模拟路线」自行决定用哪套。`LocationCard` 同时提供两套换算结果。

## 文件清单

**新增**

- `app/src/main/java/com/iterlocus/pathway/LocationCard.java` — URL 解析与坐标换算，纯逻辑
- `app/src/test/java/com/iterlocus/pathway/LocationCardTest.java` — 单元测试
- `app/src/main/java/com/iterlocus/pathway/NfcCardActivity.java`
- `app/src/main/java/com/iterlocus/pathway/RouteSimulationActivity.java`
- `app/src/main/res/layout/activity_nfc_card.xml`
- `app/src/main/res/layout/activity_route_simulation.xml`

**修改**

- `settings.gradle` — `include ':nfc'`
- `app/build.gradle` — `implementation project(':nfc')`
- `app/src/main/AndroidManifest.xml` — `NFC` 权限、`uses-feature android.hardware.nfc required=false`、两个 Activity
- `app/src/main/res/menu/menu_nav.xml` — 侧滑新增入口（沿用现有 id 命名，如 `nav_nfc_card`）
- `app/src/main/java/com/iterlocus/pathway/MainActivity.java` — 处理该菜单项
- `app/src/main/res/values/strings.xml` — 新增文案，沿用 `app_*` 前缀约定

## 依赖与约束

- `:nfc` 是 `com.android.library`，namespace `com.acooldog.nfc`，零第三方依赖、无资源、无 manifest 声明
- 其 `minSdkVersion 21` 低于 app 的 27。**不改**，保留模块的可移植性；库的 minSdk 低于宿主是合法且常见的
- 模块的 `compileSdk 32` 与 app 一致，无需调整
- `nfc/build/` 已被 `.gitignore` 第 18 行的 `build` 通配规则覆盖，无需新增忽略规则
- 模块公开 API 仅 `com.acooldog.nfc` 包下的类；`com.acooldog.nfc.internal` 不得直接引用
- `NfcConfigStore` 首次构造会尝试从 `simulation_prefs_store` 迁移老数据。行屿从不存在该文件，迁移逻辑会空跑一次并打标记，无副作用

## 错误处理

沿用项目既有风格：出错记 `XLog.e("NFC: ERROR - <method>")` 并降级，不抛异常、不崩溃。

| 情形 | 处理 |
|---|---|
| 无 NFC 硬件 | 状态显示 + 四个按钮中「模拟nfc」置灰，其余仍可手工输入后使用 |
| NFC 未开启 | 状态显示 + 「去设置」入口 |
| 读到空标签 | 不改动界面，提示「卡片内容为空」 |
| URL 无坐标 | 照常显示，禁用「模拟nfc」并说明原因 |
| 保存时三者不全 | 弹框内就地提示，不静默丢弃 |

## 测试

`LocationCard` 是本次唯一的纯逻辑，用单元测试覆盖：

- 各参数名的不同大小写组合
- 缺经度 / 缺纬度 / 两者都缺
- 数值非法（非数字、空值）
- 超出经纬度范围
- `coord=bd09` / `coord=gcj02` / 未指定 / 非法值
- 无查询串的 URL
- 非 URL 字符串
- 名称参数的三种别名

这是仓库里第一个真实的单元测试（现有两个是生成器产生的空壳）。

其余需装机验证，我无法代劳：贴卡能否读到、读到内容是否正确、四个按钮流转是否正常、NFC 关闭时状态显示是否正确。

## 待办（不在本次范围）

- 「模拟路线」功能本身
- 写卡能力（把当前位置写进空白标签）
- 卡片格式若日后需要扩展（如携带海拔、速度），在 `LocationCard` 内集中处理
