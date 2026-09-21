<p align="center">
<img src="./docs/images/LOGO.png" height="80"/>
</p>

<div align="center">

**Pathway (行屿)**

A no-root mock location app for Android 8.0+

[![license](https://img.shields.io/badge/license-GPL--3.0--only-blue.svg)](./LICENSE)

[English](./README.en.md) · [中文](./README.md)

</div>

## About

Pathway mocks the device location without root by using Android’s mock-location / test-provider APIs together with Baidu Map and location SDKs. It provides map-based teleport, a floating joystick, route drawing and playback, and NFC card reading that can hand payloads into route simulation.

- Application id: `com.iterlocus.pathway`
- ABI: **arm64-v8a only**
- Language: pure Java
- Modules: `:app` (the app) and `:nfc` (reusable NFC library, public API under `com.acooldog.nfc`)

Map/UI coordinates are **BD09**; mock providers and the route engine use **WGS84**. Convert only at the boundary.

## Features

- Mock location via test GPS / Network providers in a foreground service
- Map pick, POI search, manual coordinates (BD09 / GPS), history
- Floating joystick with configurable walk / run / ride speeds
- Route drawing (point / freehand, closed loops, densify) and local route library
- Route simulation with pause / resume / finish; closed routes loop; optional smooth random offset
- NFC: read URL / package raw fields, save configs; after a route starts, send a targeted pseudo-NDEF via public `NfcSender`
- Update check / feedback / contact are driven by `AppConfig` (GitHub `XYS-DEL/pathway`: latest release, Issues, mailto + repo chooser)

## Build

Requirements: **JDK 17–21**, Gradle wrapper 8.13, AGP 8.12.1. JDK 25 is not supported.

Create `local.properties` (gitignored) with at least:

```properties
sdk.dir=...
MAPS_API_KEY=<Baidu Android SDK AK bound to package + signing SHA1>
```

Optional release signing keys: `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`.

```bash
./gradlew assembleDebug
./gradlew assembleDebug lintDebug testDebugUnitTest
```

APK output: `Pathway_<versionName>_arm64-v8a_{debug,release}.apk`.

Full Chinese documentation (usage, FAQ, module notes): [README.md](./README.md). Architecture notes: [CLAUDE.md](./CLAUDE.md). Agent/dev conventions: [AGENTS.md](./AGENTS.md).

## History and license

Pathway’s early development used the open-source project [影梭 / GoGoGo](https://github.com/ZCShou/GoGoGo) as a base. This repository has since evolved independently (package id, signing, route simulation, NFC, docs). Product documentation describes **Pathway**.

Licensed **[GPL-3.0-only](./LICENSE)**. Original copyright **© ZCShou**. Redistributions must keep GPL-3.0, ship complete source, and retain the original copyright notices and license text.

---

<div align="center">

GPL-3.0-only · Pathway (行屿) · evolving from an open implementation © ZCShou

</div>
