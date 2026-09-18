<p align="center">
<img src="./docs/images/LOGO.png" height="80"/>
</p>

<div align="center">

Pathway (行屿) - A mock location app without root on Android 8.0+.

[![license](https://img.shields.io/badge/license-GPL--3.0--only-blue.svg)](./LICENSE)
</div>

## About this repository

**Pathway** is a fork of [影梭 / GoGoGo](https://github.com/ZCShou/GoGoGo), modified from the upstream codebase.

Changes relative to upstream:

1. Package renamed from `com.zcshou.gogogo` to `com.iterlocus.pathway`
2. Signing keys are no longer committed — credentials live in `local.properties` and Gradle signs the release build directly
3. The update check and feedback entries are disabled by default; see `AppConfig`
4. Fixed a method-name error (`SetIgnoreCacheException`) that made upstream `master` fail to compile

Upstream project: <https://github.com/ZCShou/GoGoGo>

## License and attribution

This project is licensed **GPL-3.0-only**. Original copyright © ZCShou.

Under GPL-3.0, any distribution of this repository or a derivative must remain open source under GPL-3.0, ship the complete source, and retain the original copyright notices and license text. **Do not distribute it as closed source.**

## Reference

[中文说明请见 README.md](./README.md)

## License

GPL-3.0-only © ZCShou
