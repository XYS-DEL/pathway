<p align="center">
<img src="./docs/images/LOGO.png" height="80"/>
</p>

<div align="center">

**行屿（Pathway）**

面向 Android 8.0+ 的无需 ROOT 虚拟定位工具

[![license](https://img.shields.io/badge/license-GPL--3.0--only-blue.svg)](./LICENSE)

[中文](./README.md) · [English](./README.en.md)

</div>

## 简介

行屿基于 Android 调试定位 API 与百度地图/定位 SDK，可在不获取 ROOT 权限的前提下修改本机模拟位置，并支持摇杆移动、路线绘制与沿路线自动行进。

地图与坐标输入使用百度 **BD09** 坐标系；系统测试定位提供器与路线引擎使用 **WGS84**。两套坐标系在边界处只换算一次，请勿在业务层重复转换。

包名与应用 ID 为 `com.iterlocus.pathway`。当前产物仅提供 **arm64-v8a**。

## 警告

此类工具常见于校园运动类应用的作弊讨论。请明确：

1. **行屿不支持任何校园运动类 APP 的作弊行为**
2. **开发者不赞同任何形式的校园运动作弊**
3. 本软件**仅供学习 Android 开发与定位调试技术**，滥用后果自负

## 功能

### 虚拟定位

- 地图选点、POI 搜索、历史记录回放
- 手动输入坐标（BD09 / GPS）
- 前台服务持续推送 GPS / Network 测试定位
- 悬浮摇杆控制移动，可配置步行 / 跑步 / 骑行速度
- 可选海拔高度（手动输入，或在关闭模拟后采集 GNSS）
- 历史位置与搜索记录本地存储，可配置保留天数

### 路线

- **绘制路线**：点绘制 / 线绘制，支持闭合、撤销、清空
- **均匀密化**：按总长等分插入 N 个点（总长等分 N+1 段）
- **历史路线**：本地保存、编辑、删除；同名拒绝覆盖
- **模拟路线**：选择已保存路线与速度档位后开始行进
  - 引擎运行在前台服务 `ServiceGo` 中，切到其他应用后仍继续
  - 支持暂停 / 继续 / 结束；闭合路线可无限循环并统计圈数
  - 可选平滑随机偏移（连续漂移，不是逐帧白噪声）
  - 路线进行中会禁用摇杆；瞬移会结束当前路线

### NFC 位置卡

- 读取 NFC 标签中的 URL / 包名等原始字段（**当前不做 URL 坐标解析**）
- 配置本地保存与导入
- 开始路线模拟成功后，可通过 `:nfc` 模块的公开 API 发送定向伪 NDEF 事件（含包名时优先 `ACTION_NDEF_DISCOVERED` + AAR，失败再降级 `ACTION_VIEW`）
- 无 NFC 硬件时功能不可用；接收标签的界面保持默认启动模式

### 其他

- 设置：摇杆类型、速度、海拔、随机偏移、日志开关等
- 侧滑菜单：历史记录、读取 NFC、绘制路线、历史路线、模拟路线、设置、开发人员选项
- 侧滑菜单「更多」
  - **检测更新**：对照 GitHub Releases（`XYS-DEL/pathway`），启动时静默检查；手动检查会提示结果
  - **问题反馈**：打开仓库 GitHub Issues
  - **联系作者**：弹窗二选一——发送邮件（`3322794490@qq.com`）或打开 GitHub 仓库
- 渠道地址集中在 `AppConfig.java`，改仓库或邮箱时只改这一处

界面改动（地图覆盖层、配色、布局）需在真机验收；单元测试与 lint 无法覆盖这些路径。

## 使用

1. 获取并安装 arm64-v8a 的 APK
2. 首次启动：阅读并同意用户协议与隐私政策，授予定位等权限
3. 在系统开发者选项中，将「选择模拟位置信息应用」设为行屿
4. 打开定位与网络（Wi-Fi 开启时模拟位置可能被真实定位拉回，属调试 API 限制）
5. 在地图上点选位置，点击启动按钮开始模拟；再次点击可瞬移或停止
6. 需要路线时：侧滑菜单 →「绘制路线」保存 →「模拟路线」选择并开始
7. 需要 NFC 时：侧滑菜单 →「读取NFC」；也可在模拟界面导入已保存配置

## 构建

| 项目 | 要求 |
| --- | --- |
| 语言 | 纯 Java（无 Kotlin），编译目标 Java 11 |
| Gradle / AGP | Wrapper 8.13 / Android Gradle Plugin 8.12.1 |
| JDK | **17–21**（Gradle 8.13 无法运行在 JDK 25 上） |
| 模块 | `:app`（应用）、`:nfc`（可复用 NFC 库） |
| ABI | 仅 `arm64-v8a` |
| SDK | minSdk 27（`:nfc` 为 21）、compileSdk / targetSdk 32 |

`local.properties`（已 gitignore）至少需要：

```properties
sdk.dir=...
MAPS_API_KEY=你的百度地图 Android SDK AK
```

可选：发布签名（无则 `assembleRelease` 会明确失败，debug 可回退系统 debug keystore）：

```properties
RELEASE_STORE_FILE=keystore/release.jks
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=...
RELEASE_KEY_PASSWORD=...
```

说明：

- 百度 AK 按「包名 + 签名 SHA1」绑定。请用 `com.iterlocus.pathway` 与你自己的 keystore SHA1 申请，并勾选**地图 SDK 与定位 SDK**。填错或签名变更时**不会编译失败**，只会地图空白。
- `MAPS_SAFE_CODE` 在当前 Java 代码中无引用，编译不依赖它。
- 签名凭据不得写入版本库；`secrets-gradle-plugin` 已将 `RELEASE_.*` 排除在 `BuildConfig` 之外。
- Windows 下请用 `gradlew.bat`；若默认 `JAVA_HOME` 是 JDK 25，可临时指定，例如 `JAVA_HOME="C:\Program Files\Java\jdk-21.0.10"`。

常用命令：

```bash
./gradlew assembleDebug
./gradlew assembleRelease
./gradlew assembleDebug lintDebug testDebugUnitTest   # 与 CI 一致
./gradlew :app:testDebugUnitTest --tests "com.iterlocus.pathway.RoutePlayerTest"
./gradlew :nfc:testDebugUnitTest
```

产物文件名形如：`Pathway_<versionName>_arm64-v8a_{debug,release}.apk`。

### 仓库结构（简）

| 路径 | 说明 |
| --- | --- |
| `app/` | 主应用 `com.iterlocus.pathway` |
| `nfc/` | 可独立复制的 NFC 库 `com.acooldog.nfc`（公开 API 见 [nfc/README.md](./nfc/README.md)） |
| `keystore/` | 本地签名文件目录（不进版本库） |
| `docs/` | 图片与设计记录 |
| `AGENTS.md` | 仓库工作约定（构建命令、坐标系、模块边界等） |
| `CLAUDE.md` | 架构说明与实现注意事项 |

路线几何、路线播放引擎、名称校验等逻辑与 Android 解耦，可在 JVM 单元测试中验证。

## FAQ

**为何最低支持 Android 8.0（API 27）？**  
调试定位相关实现按较新系统版本适配与验证；更旧版本未测试，故不承诺支持。

**定位偶尔飘回真实位置？**  
调试 API 的固有限制。手机同时开启基站 / Wi-Fi 等定位方式时更容易出现。可在设置中关闭 Wi-Fi 后重试（应用内会提示）。

**是否支持鸿蒙？**  
非本仓库重点测试环境，目前不考虑适配。

**为何在部分腾讯系应用中定位不生效？**  
应用可能使用自定义定位通道，不受系统 mock provider 影响。

**编译时报 Java / class file 版本错误？**  
`JAVA_HOME` 过新。Gradle 8.13 请使用 JDK 17–21。

**地图不显示？**  
检查 `MAPS_API_KEY` 是否与包名 `com.iterlocus.pathway` 及当前签名 SHA1 匹配，以及是否开通了地图与定位服务。

**路线模拟进度在后台停住？**  
请确认模拟服务仍在前台运行；系统省电策略或手动停止服务都会中断。行屿将播放引擎放在前台服务中，正是为了避免 Activity 在后台被冻结。

**NFC 卡片会解析出坐标吗？**  
当前版本只读取并展示原始 URL / 包名，不做坐标解析；解析能力按卡片格式单独演进。

## 文档

- 架构与实现细节：[CLAUDE.md](./CLAUDE.md)
- 开发者 / Agent 约定：[AGENTS.md](./AGENTS.md)
- NFC 库接入说明：[nfc/README.md](./nfc/README.md)
- 功能设计记录：`docs/superpowers/specs/`
- 安全漏洞报告：[SECURITY.md](./SECURITY.md)

## 贡献

1. Fork 本仓库
2. 在特性分支修改
3. 提交 PR，并附上必要的真机验证说明（涉及 UI / 地图 / 定位时）

请保持纯 Java 风格与既有包边界；`:nfc` 仅通过 `com.acooldog.nfc` 公开 API 使用，不要依赖 `internal` 包。

## 历史沿革与许可

**行屿**在开发过程中曾以开源项目 [影梭 (GoGoGo)](https://github.com/ZCShou/GoGoGo) 为基础进行二次开发，核心无 ROOT 模拟定位方案与部分历史实现来源于该项目及其社区。此后本仓库在包名、构建签名、路线模拟、NFC 等方向持续独立演进，文档与产品叙述以**行屿**为准。

本项目遵循 **[GPL-3.0-only](./LICENSE)**。原始版权归 **© ZCShou** 所有；行屿在保留上述版权声明与许可文本的前提下进行修改与分发。

依据 GPL-3.0：任何分发本仓库或其衍生版本的行为，都必须继续以 GPL-3.0 开源并提供完整源码，同时保留原始版权声明与许可文本。**请勿闭源分发。**

---

<div align="center">

GPL-3.0-only · 行屿 (Pathway) · 基于 © ZCShou 的开源实现持续演进

</div>
