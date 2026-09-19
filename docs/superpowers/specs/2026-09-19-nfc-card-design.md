# 行屿 NFC 位置卡 — 设计

- 日期：2026-09-19
- 状态：设计已确认，待实现
- 涉及模块：`:app`、新增依赖 `:nfc`

## 目标

贴一张自制的 NFC 标签，把卡片上的 URL 与包名读进行屿的界面并显示出来，然后通过按钮把这两个原始值交接给「模拟路线」界面。

**NFC 界面的职责到「跳进去」为止。** 解析坐标、换算坐标系、选路线、自定义路线、开始模拟——全部属于 `RouteSimulationActivity`，后续单独开发，本次不碰。

## 非目标

**不做解析。** NFC 界面不解析 URL、不提取坐标、不做任何坐标系换算。它读到的 URL 是什么就原样显示、原样传递。判断这张卡有没有用、能用在哪，是模拟路线界面的事。

**不做伪造贴卡派发。** 引入的 `com.acooldog.nfc` 模块里有 `NfcSender`，它能构造 `ACTION_NDEF_DISCOVERED` 并 `setPackage(目标包名)`，让某个 App 收到一次与真实贴卡无法区分的广播。**本功能不接入它。**「模拟nfc」走的是行屿自己的界面流转，不向任何第三方 App 派发任何 Intent。卡片上的包名字段只做显示、保存与共享，不参与任何派发。

同时不做：往实体标签写数据（模块无此能力，本次也不补）；模拟路线功能本身。

## 界面

### `NfcCardActivity`（新增，独立 Activity）

不设 `launchMode`，即默认的 standard 模式。模块 README 明确要求接收标签的 Activity 不能用 `singleInstance`；app 的 `<application>` 与 `MainActivity` 都设了 `singleInstance`，本 Activity 必须避开。入口在侧滑菜单。

```
NFC 状态：● 就绪 ／ ○ 未开启 [去设置] ／ ✕ 本机无 NFC 硬件

URL      https://example.com/goto?lat=39.915&lng=116.404
包名     卡片未携带时显示占位文案

[ 模拟nfc ]  [ 保存配置 ]  [ 载入本地配置 ]  [ 共享nfc ]
```

状态取自 `NfcReaderSession.getStatus()`。`DISABLED` 时「去设置」跳 `Settings.ACTION_NFC_SETTINGS`。

URL 与包名两行只读展示，不做编辑（本次不需要手工输入；若日后要，再加输入框）。

### 按钮行为

| 按钮 | 行为 |
|---|---|
| 模拟nfc | 只要读到了非空内容即可点。把 URL、包名、来源原样交给 `RouteSimulationActivity`。内容为空时置灰 |
| 保存配置 | 弹框填名称。**模块约束**：`SavedNfcConfig.isComplete()` 要求名称、URL、包名三者齐全，缺一即被 `saveSavedConfig` 静默丢弃。因此包名为空时弹框一并要求填写，并在弹框内写明原因 |
| 载入本地配置 | `NfcConfigStore.getSavedConfigs()` 列表弹窗，选中后回填 URL 与包名；列表为空时提示 |
| 共享nfc | 复用 app 已有的 `ShareUtils.shareText()`，内容为 URL 与包名两行纯文本 |

### `RouteSimulationActivity`（新增，占位）

不含任何业务逻辑。界面列出收到的全部参数，加一行「模拟路线功能开发中」，用于验证交接的字段正确，避免功能做完才发现字段没接上。

## 交接契约

`RouteSimulationActivity` 接收以下 extras（常量定义在该 Activity 内，供后续开发引用）：

| key | 类型 | 说明 |
|---|---|---|
| `EXTRA_CARD_URL` | String | 卡片原始 URL，未做任何加工 |
| `EXTRA_CARD_PACKAGE` | String | 卡片携带的包名，可能为空 |
| `EXTRA_SOURCE` | String | 来源标记，由模块填入。本流程中为 `tag`（`NfcReaderSession` 读到标签时补的默认值）；模块另有 `manual`（`NfcConfigStore` 的默认值）。这是自由字符串，不要按穷举来写判断 |

**传递的是原始值，不是解析结果。** 模拟路线界面拿到 URL 后如何解释（坐标放在哪个查询参数、用哪套坐标系），由它自行决定。

### 给模拟路线界面的参考

以下不是本次的实现内容，只是记录当前约定，供后续开发时对齐：

- 卡片 URL 建议形如 `https://任意域名/任意路径?lat=39.915&lng=116.404&name=公司&coord=wgs84`，坐标放在查询参数里
- **坐标系默认应为 WGS84**（GPS 通用标准）。若卡片由百度地图写入（BD09），需在 URL 里显式标注，否则会偏移约 500 米
- 换算注意：`MapUtils` 的入参顺序统一是 **(经度, 纬度)**，返回数组也是 `{经度, 纬度}`；而百度 SDK 的 `LatLng` 构造是 **(纬度, 经度)**。本仓库最容易写反的就是这里

## 文件清单

**新增**

- `app/src/main/java/com/iterlocus/pathway/NfcCardActivity.java`
- `app/src/main/java/com/iterlocus/pathway/RouteSimulationActivity.java`
- `app/src/main/res/layout/activity_nfc_card.xml`
- `app/src/main/res/layout/activity_route_simulation.xml`

**修改**

- `settings.gradle` — `include ':nfc'`
- `app/build.gradle` — `implementation project(':nfc')`
- `app/src/main/AndroidManifest.xml` — `NFC` 权限、`uses-feature android.hardware.nfc required=false`、两个 Activity
- `app/src/main/res/menu/menu_nav.xml` — 侧滑新增入口，id 沿用现有命名风格（如 `nav_nfc_card`）
- `app/src/main/java/com/iterlocus/pathway/MainActivity.java` — 处理该菜单项
- `app/src/main/res/values/strings.xml` — 新增文案，沿用 `app_*` 前缀约定

**入库**

`nfc/` 目前未被 git 跟踪，需一并加入。`nfc/build/` 那 561K 构建产物已被 `.gitignore` 第 18 行的 `build` 通配规则覆盖，无需新增规则；`git add nfc/` 只会纳入 18 个源码与配置文件。

## 依赖与约束

- `:nfc` 是 `com.android.library`，namespace `com.acooldog.nfc`，零第三方依赖、无资源、无 manifest 声明
- 其 `minSdkVersion 21` 低于 app 的 27。**不改**，保留模块的可移植性；库的 minSdk 低于宿主是合法且常见的
- 模块 `compileSdk 32` 与 app 一致，无需调整
- 仅使用 `com.acooldog.nfc` 包下的公开 API；`com.acooldog.nfc.internal` 不得直接引用
- `NfcConfigStore` 首次构造会尝试从 `simulation_prefs_store` 迁移老数据。行屿从不存在该文件，迁移逻辑空跑一次并打标记，无副作用

## 错误处理

沿用项目既有风格：出错记 `XLog.e("NFC: ERROR - <method>")` 并降级，不抛异常、不崩溃。

| 情形 | 处理 |
|---|---|
| 无 NFC 硬件 | 状态显示；「模拟nfc」置灰，其余按钮仍可用（可载入本地配置后共享） |
| NFC 未开启 | 状态显示 + 「去设置」入口 |
| 读到空标签 | 不改动界面，提示「卡片内容为空」 |
| 内容为空时点「模拟nfc」 | 置灰，不可点 |
| 保存时三者不全 | 弹框内就地提示，不静默丢弃 |

## 测试

本次**不含单元测试**：NFC 界面是纯 UI 与模块调用的胶水层，没有可独立验证的纯逻辑（解析已移出范围）。项目现有的 JUnit 与两个生成的空壳测试保持不变。

需装机验证，我无法代劳：

- 无 NFC 的手机 / 模拟器上，界面是否正常显示「本机无 NFC 硬件」而不是崩溃
- NFC 关闭时状态与「去设置」是否正确
- 贴卡能否读到内容，URL 与包名是否正确
- 四个按钮流转是否正常（保存后在「载入本地配置」里能否看到）
- 读到的内容传给模拟路线界面后，占位页显示的字段是否正确

我这边能验证的只有**编译通过**与**未使用模块 internal 包**。

## 待办（不在本次范围）

- 模拟路线功能本身：解析 URL 取坐标、坐标系换算、自定义路线、选路线、开始模拟
- 写卡能力（把当前位置写进空白标签）
- 若日后需要手工输入 URL，再给两行加输入框
