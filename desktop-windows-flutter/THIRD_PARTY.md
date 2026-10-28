# 第三方组件与许可

果映 TV Windows 客户端（Flutter 重构版）以 **GPL-3.0-only** 发布。完整许可证见发行包内的
`LICENSE`；下列组件按各自许可证使用，发行包同时附带 `NOTICE.md`（TV 端与共享内核的说明）。

## 界面与播放

| 组件 | 版本 / 说明 | 许可证 |
| --- | --- | --- |
| Flutter / Dart | Flutter 3.47 桌面运行时与 `flutter_windows.dll` | BSD-3-Clause |
| Material Icons | Flutter 内置图标字体 | Apache-2.0 |
| media_kit / media_kit_video | Dart 播放控制与纹理渲染 | MIT |
| media_kit_libs_windows_video | 随包分发的 libmpv 与 FFmpeg 运行库 | mpv: GPL-2.0-or-later；FFmpeg: LGPL-2.1-or-later（构建配置可能含 GPL 组件） |
| window_manager | 原生窗口控制 | MIT |
| http | Dart HTTP 客户端 | BSD-3-Clause |
| win32 / ffi | DPAPI 与 FFI 绑定 | BSD-3-Clause |
| flutter_localizations | Material 中文本地化 | BSD-3-Clause |

libmpv / FFmpeg 的源码与许可证文本可从 <https://mpv.io> 与 <https://ffmpeg.org> 获取；
`media_kit_libs_windows_video` 的打包清单见 <https://github.com/media-kit/media-kit>。

## 本机服务（desktop-mac/backend + kotlin-tv/core）

| 组件 | 版本 | 许可证 |
| --- | --- | --- |
| 果映 TV 内核与桥接服务 | 本仓库 | GPL-3.0-only |
| Kotlin / kotlinx-coroutines | 2.1.x / 1.10.x | Apache-2.0 |
| OkHttp / Okio | 4.12.0 / 3.6.0 | Apache-2.0 |
| BouncyCastle | bcprov-jdk18on 1.79 | MIT |
| jsoup | 1.23.2 | MIT |
| org.json | 20240303 | Public Domain |
| JRE（jlink 精简） | Temurin 17 | GPL-2.0 WITH Classpath Exception |

## 其他

- 应用图标沿用 Flutter 模板默认图标，后续随正式发布替换。
- 所有剧集、封面、评论等内容版权归原权利人所有；客户端仅做本机解析与播放，不托管任何内容。
