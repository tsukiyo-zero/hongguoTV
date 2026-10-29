# 果映 TV Windows 客户端（Flutter 重构版）

Windows 10/11 x64 桌面客户端，使用 Flutter + Material 3 重写界面，播放改用
[media_kit](https://github.com/media-kit/media-kit)（libmpv），内容解析、登录与解密继续复用
`desktop-mac/backend` 的本机回环服务。

> 当前处于验收阶段：源码与构建产物可用，正式发布仍以 `desktop-windows/` 的 Compose 版为准，
> 待人工验收通过后再切换。

## 功能

- 首页推荐：漫剧 / 真人剧 / AI 剧筛选与「换一批」，卡片直接进详情。
- 搜索、找剧（类型 + 题材）、热播榜（综合 / 漫剧 / 真人剧 / AI 剧）。
- 详情页：简介、选集、相关作品、本集评论与整剧剧评。
- 播放器：480P/720P/1080P、0.5×–2× 倍速、自动下一集、断点续播、全屏、选集侧栏。
- 弹幕画面叠加：按时间轴从接口分页加载，可在播放器里开关（Compose 版因 libVLC 独立视频表面无法实现）。
- 本机收藏与观看记录，保存在 `%APPDATA%\GuoyingTV\library.json`，与 Compose / macOS 版共用同一份数据。
- 红果扫码登录：账户历史、收藏、已上线 / 待上线预约；观看进度自动上传。登录态用 Windows DPAPI
  绑定当前用户加密保存为 `session.bin`。
- 浅色 / 深色 / 跟随系统三种主题，配色与「红果鉴」一致。

## 使用

1. 解压 ZIP，运行 `guoyingtv_windows.exe`。
2. 首次启动会解压播放组件并启动本机服务，出现「正在启动本机服务…」时请稍候。
3. 数据目录：`%APPDATA%\GuoyingTV`；本机服务日志：`bridge.log`（设置页可一键打开）。
4. 播放器快捷键：`Space` 播放 / 暂停、`←` / `→` 快退 / 快进 10 秒、`F` 全屏、`Esc` 退出全屏、`Ctrl+F` 聚焦搜索框。

无需安装 Java、VLC 或 .NET 运行时：ZIP 内自带精简 JRE（jlink）与 libmpv。

## 构建

前置条件：

- Windows 10/11 x64。
- JDK 17（需要 `jlink`；仅用于编译桥接服务与裁剪运行时）。
- Flutter 3.47 及以上，并安装 Visual Studio 2022 的「使用 C++ 的桌面开发」工作负载。
- 仓库内已包含 Gradle Wrapper（`kotlin-tv/gradlew.bat`）。

```powershell
powershell -ExecutionPolicy Bypass -File scripts\build-windows-flutter.ps1
```

脚本会：

1. `gradlew -p desktop-windows :bridge:installDist` 编译 `bridge.jar` 与依赖；
2. `flutter build windows --release` 生成 `build\windows\x64\runner\Release`；
3. 用 `jlink` 生成约 27 MB 的精简 JRE（`java.base`、`jdk.httpserver`、`jdk.crypto.ec` 等）；
4. 组装 `runtime\`、`bridge\`、许可文档并压缩为
   `outputs\windows-flutter\GuoyingTV-windows-flutter-x64-<版本>.zip`。

不需要 VLC：旧 Compose 版的 `vlc\` 目录（约 136 MB）在 Flutter 版中已被 libmpv 取代。

应用图标已提交在 `windows/runner/resources/app_icon.ico`（16–256 像素共 7 个尺寸，取自
`kotlin-tv/artwork/guoying-icon-c3.png`），构建时写入 exe 资源。

## 开发

```powershell
cd desktop-windows-flutter
flutter pub get
flutter analyze
flutter test
flutter run -d windows
```

`flutter run` 会从仓库向上查找 `desktop-mac/backend/build/install/bridge/lib` 或 `bridge\`；
也可以先运行 `gradlew -p desktop-windows :bridge:installDist`，或用环境变量
`GUOYING_BRIDGE_DIR` 指定桥接 jar 目录。直接启动时需要 JDK 17 的 `java.exe`（`JAVA_HOME`）。

### 自动化测试

- `flutter test` 默认只跑离线单元测试：数据模型与 `library.json` 字段兼容、封面 HEIC 回退地址、
  DPAPI 加密与解密往返（真实调用 `CryptProtectData`，使用临时目录，不碰本机登录态）。
- `test/live_bridge_test.dart` 是可选的本机联调测试，会真正启动 bridge 并依次请求首页、详情、
  相关作品、找剧、榜单、评论、弹幕与播放地址，同时校验视频端点支持 Range。需要先构建 bridge：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
$env:GUOYING_BRIDGE_DIR = 'desktop-mac\backend\build\install\bridge\lib'
$env:GUOYING_LIVE_BRIDGE = '1'
flutter test test/live_bridge_test.dart
```

未设置 `GUOYING_LIVE_BRIDGE=1` 时该用例会被跳过，因此 CI 与日常 `flutter test` 不受影响。

## 数据与约定

- `library.json`：schema 1，包含收藏与观看记录。
- `settings.json`：清晰度、倍速、自动下一集，以及新增的 `themeMode`。
- `session.bin`：DPAPI 加密的登录凭据，同一文件在别的 Windows 用户或电脑上无法解密。
- 桥接服务只监听 `127.0.0.1` 的随机端口，并要求启动时生成的随机路径令牌；请求带 `Origin`
  头会被拒绝，避免被浏览器页面调用。

## 已知限制

- 仅支持 Windows x64。
- 账户片单为只读；收藏与预约的写操作仍在移动端完成。播放进度上传已接入。
- 弹幕为分页按需拉取，暂停后继续播放会沿用已加载区间；网络较差时弹幕可能缺席，但不会影响视频播放。
- 与 Compose 版共用数据目录，可来回切换；但两版不要同时运行，以避免争用播放端口与桥接端口。
