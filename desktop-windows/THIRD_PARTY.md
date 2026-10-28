# 第三方组件

果映 TV Windows 客户端以 GPL-3.0-only 发布。便携 ZIP 会附带以下组件的二进制：

| 组件 | 版本 | 许可证 | 用途 |
| --- | --- | --- | --- |
| Compose Multiplatform for Desktop | 1.8.2 | Apache-2.0 | 桌面 UI |
| Kotlin / kotlinx.coroutines | 2.1.20 / 1.10.2 | Apache-2.0 | 语言与协程 |
| OkHttp / Okio | 4.12.0 / 3.x | Apache-2.0 | 回环 HTTP 客户端 |
| org.json | 20240303 | JSON License | JSON 解析 |
| vlcj | 4.12.1 | GPL-3.0-or-later | libVLC 的 Java 绑定 |
| JNA / JNA Platform | 5.14.0 | LGPL-2.1-or-later / Apache-2.0 | 本机调用（DPAPI、VLC 加载） |
| libVLC（VLC media player） | 3.0.x x64 | LGPL-2.1-or-later（`libvlc`/`libvlccore`），插件含 GPL 组件 | 视频解码与渲染 |
| Bouncy Castle | 1.79 | MIT | 播放解密（TV 共用内核） |
| jsoup | 1.23.2 | MIT | 页面解析（TV 共用内核） |

## 分发注意

- `vlc/` 目录来自 VideoLAN 官方 Windows 64 位构建。分发时保留其 `COPYING` 与
  `COPYING.LIB`，并按 LGPL 要求提供对应源码的获取方式
  （<https://www.videolan.org/vlc/download-sources.html>）。
- vlcj 以 GPL-3.0 授权，本项目同样以 GPL-3.0-only 发布，二者兼容。
- 修改 libVLC 本身时，须按 LGPL 提供修改后的库与可重新链接的方式；本项目未修改 libVLC。
