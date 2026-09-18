<p align="center">
<img src="./docs/images/LOGO.png" height="80"/>
</p>

<div align="center">

行屿 - 用于 Android 8.0+ 的无需 ROOT 权限的虚拟定位 APP

[![license](https://img.shields.io/badge/license-GPL--3.0--only-blue.svg)](./LICENSE)
</div>

## 关于本仓库

&emsp;&emsp;**行屿**是 [影梭 (GoGoGo)](https://github.com/ZCShou/GoGoGo) 的二次开发分支，在上游代码基础上修改而来。

本仓库相对上游的改动：

1. 包名由 `com.zcshou.gogogo` 改为 `com.iterlocus.pathway`，应用名改为「行屿」
2. 签名密钥不再进版本库（改由 `local.properties` 配置），发布打包由 Gradle 直接签名
3. 更新检查与意见反馈入口默认关闭，填入本仓库地址后启用（见 `AppConfig`）
4. 修复了上游 `master` 中导致无法编译的 `SetIgnoreCacheException` 方法名错误

上游项目地址：<https://github.com/ZCShou/GoGoGo>

### 许可证与署名

&emsp;&emsp;本项目遵循 **GPL-3.0-only**，原始版权归 © ZCShou 所有。

&emsp;&emsp;**开源 ≠ 白嫖。** 依据 GPL-3.0，任何分发本仓库或其衍生版本的行为，都必须继续以 GPL-3.0 开源并提供完整源码，同时保留原始版权声明与许可文本。**请勿闭源分发，也不要只加广告而不开源。** GPL 的法律效力在国内已有诸多诉讼案例，请自行权衡。

## 简介

&emsp;&emsp;行屿是一个基于 Android 调试 API + 百度地图及定位 SDK 实现的安卓定位修改工具，并且同时实现了一个可以自由控制移动的摇杆。使用行屿，不需要 ROOT 权限就可以随意修改自己的当前位置以及模拟移动。

## 警告

&emsp;&emsp;**有很多人将此类工具用在校园运动类 APP（包括但不限于闪动校园、TakeTwo、运动世界校园等）中作弊，在此提醒：**

1. **行屿不支持任何校园运动类 APP 的作弊行为**
2. **行屿开发者也不赞同采用任何形式在校园运动中作弊**

## 背景

&emsp;&emsp;本项目的全部核心实现来自上游影梭。上游作者在玩一款 VR 游戏「一起来捉妖」时，为了省事而研究出无需 ROOT 的定位修改方案，并将研究结果开源出来方便大家一起学习。上游原话如下（重要的事情说三遍！否则后果自负）：

1. 该 APP 仅仅是为了学习 Android + 百度地图的实现方法，请勿用于游戏作弊！
2. 该 APP 仅仅是为了学习 Android + 百度地图的实现方法，请勿用于游戏作弊！
3. 该 APP 仅仅是为了学习 Android + 百度地图的实现方法，请勿用于游戏作弊！

## 功能

1. 定位修改
2. 摇杆控制移动
3. 历史记录
4. 位置搜索
5. 直接输入坐标

## 截图

![joystick.jpg](./docs/images/joystick.jpg)
![search_history.jpg](./docs/images/search_history.jpg)
![map.jpg](./docs/images/map.jpg)

> 以上截图来自上游项目，尚未替换。

## 用法

1. 下载 APK 直接安装
2. 启动行屿，赋予相关权限
3. 单击地图位置，然后点击启动按钮

## 构建

&emsp;&emsp;需要 JDK 17–21（Gradle 8.13 不支持 JDK 25）。签名凭据与百度地图 AK 在 `local.properties` 中配置，详见 [CLAUDE.md](./CLAUDE.md)。

```bash
./gradlew assembleDebug
```

## 文档

&emsp;&emsp;上游开发过程中遇到的一些问题记录在作者的博客中，参见：<https://blog.csdn.net/zcshoucsdn/category_10559121.html>

&emsp;&emsp;如果有疑问可以直接搜索 ISSUE 或者在上面直接提交问题。

## 参考

&emsp;&emsp;上游作者在编写影梭的过程中，参考了很多网友分享的技术文章、示例代码等。包括但不限于以下列出的几个：

1. <https://github.com/Hilaver/MockGPS>
2. <https://github.com/bxxfighting/together-go>
3. <https://github.com/P72B/Mocklation>

## FAQ

Q：为何不支持 Android 8.0 以下版本？

A：因为上游作者手里没有机器无法进行适配。

Q：为何定位不是很稳定，偶尔会飘回真实位置？

A：这是由于实现原理导致的，Android 调试 API 固有的问题。确切地说，应该是由于手机本身还开启了其他定位方式（例如基站定位、wifi 定位等）导致的。

Q：是否支持鸿蒙系统？

A：经过测试，可以在鸿蒙系统上正常运行。

Q：为何在微信等腾讯系应用上定位不起作用？

A：建议去问一下腾讯。

Q：编译时 java 报错？

A：Gradle 使用的 java 版本与 Android Studio 使用的不一致。Gradle 默认会在环境变量中搜索 `JAVA_HOME` 来确定 Java 位置。注意 Gradle 8.13 不支持 JDK 25，请使用 JDK 17–21。

Q：地图不显示 / 定位无效？

A：百度地图 AK 按「包名 + 签名 SHA1」绑定校验。请用 `com.iterlocus.pathway` 与你自己的 keystore SHA1 申请 AK，并填入 `local.properties` 的 `MAPS_API_KEY` / `MAPS_SAFE_CODE`。

## 如何贡献

1. FORK -> PR
2. 加入开发，共同完善

## 许可证

GPL-3.0-only © ZCShou

本项目为影梭 (GoGoGo) 的二次开发分支，原始版权归 ZCShou 所有，遵循 GPL-3.0-only 许可。
