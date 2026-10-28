# 果映 TV Windows 客户端

Windows 10/11 x64 上的独立桌面客户端，与 TV、Mac 共用同一套内容内核。界面使用 Compose
Desktop，播放使用 libVLC（vlcj），本机服务由现有的 Mac/Windows 共用 bridge 提供。

## 功能

- 首页推荐试播：首集试播、上一部 / 下一部、观看全集后进入正式观看并记录进度。
- 搜索、找剧（漫剧 / 真人剧 / AI 剧 + 题材筛选）、热播榜（综合 / 分类 + 题材）。
- 详情页：选集、清晰度（480P/720P/1080P）、倍速、自动下一集、相关作品。
- 评论与剧评：本集评论分页加载、整剧剧评节选、筛选 / 排序、打开官方播放页。
- 本机收藏与本机观看记录（`%APPDATA%\GuoyingTV\library.json`）。
- 红果账号扫码登录：账号历史、收藏、已上线 / 待上线预约；观看进度定期上传。
- 登录态使用 Windows DPAPI 绑定当前用户加密保存（`session.bin`），不写入明文。

## 架构

```
app (Compose Desktop + vlcj)
   │  HTTP，仅 127.0.0.1 + 随机路径令牌
   ▼
bridge 子进程（复用 desktop-mac/backend 的 Main.kt）
   │
   ▼
core（与 TV 共用的解析/解密内核）+ 远端点播 Range 代理
```

- UI 进程不加载播放协议与账号实现，只通过回环接口取数据；bridge 拒绝 `Origin` 头，
  且只接受带随机令牌的路径。
- 播放地址是 bridge 本机的 `/video?id=...`，由 bridge 解密并支持 Range，VLC 不需要
  直接访问远端。
- 与 Mac 客户端使用同一份 bridge 源码，协议改动会同时影响两端。

## 构建与运行

前置：JDK 17、Windows 10/11 x64、VLC 3 的 64 位运行库（播放必需）。

开发运行：

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17..."
.\kotlin-tv\gradlew.bat -p desktop-windows :app:run
```

打包便携 ZIP（自带 JRE、bridge jar 与 VLC）：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\build-windows.ps1 -VlcDir "C:\Program Files\VideoLAN\VLC"
# 或指定 VLC 压缩包
powershell -ExecutionPolicy Bypass -File scripts\build-windows.ps1 -VlcZip .\vlc-3.0.x-win64.zip
```

产物：`outputs/windows/GuoyingTV-windows-x64-<版本>.zip`。解压后运行 `GuoyingTV.cmd`
（会为本次进程设置 VLC 路径），或直接运行 `GuoyingTV.exe`（客户端也会在自身目录下查找
`vlc\libvlc.dll`）。

打包脚本要求提供 64 位 VLC 3：本机已安装时自动查找默认安装目录，也可用 `-VlcDir` /
`-VlcZip` 指定。脚本内容为纯 ASCII，以兼容 Windows PowerShell 5.1 的编码处理。

自行编译运行但没有安装 VLC 时，可在“我的 → 播放器”里填写 VLC 目录后点“应用”。

### 打包实现备注

- `jpackage` 生成的运行时使用 `--strip-native-commands`，不含 `runtime\bin\java.exe`；Gradle 的
  `addBridgeJavaLauncher` 会从同一 JDK 复制回该启动器，bridge 子进程依赖它。
- bridge 的 jar 由 `stageBridge` 同步到 `build\app-resources\common\bridge`，Compose 打包时复制为
  安装目录下的 `app\resources\bridge`；`prepareAppResources` 已显式依赖它。
- 包名、版本、描述等 jpackage 元数据保持 ASCII：`jpackage` 用系统代码页解析参数文件，中文会导致
  `Input length = 1`。

## 本机数据

| 文件 | 说明 |
| --- | --- |
| `%APPDATA%\GuoyingTV\library.json` | 本机收藏与观看记录（schema 1，与 Mac 一致） |
| `%APPDATA%\GuoyingTV\settings.json` | 清晰度、倍速、自动下一集、VLC 目录 |
| `%APPDATA%\GuoyingTV\session.bin` | DPAPI 加密的登录态 |
| `%APPDATA%\GuoyingTV\bridge.log` | bridge 子进程的标准错误，便于排查启动问题 |

删除该目录即可清除全部本机数据。账号凭据不会写进日志或安装目录。

## 已知限制

- 弹幕的**画面叠加**尚未实现（libVLC 使用独立的重型视频表面，Compose 无法直接在其上绘制）；
  评论与剧评可正常使用。后续如实现，需要单独的分层窗口或回调渲染方案。
- 暂未接入自动更新；新版本以新的便携 ZIP 分发，请手动替换目录。
- 评论发送 / 回复、点赞、账号设置等写操作不在本客户端范围内，赞数只读。
- 仅提供 Windows 10/11 x64，不包含 arm64 与安装包（MSI/EXE installer）。
- 播放依赖系统具备 H.264/HEVC 解码能力；VLC 自带解码器，通常无需额外安装系统扩展。

## 许可

源代码 GPL-3.0-only。第三方组件与许可证见 `THIRD_PARTY.md`。分发便携 ZIP 时请一并保留
`LICENSE`、`NOTICE.md`、`THIRD_PARTY.md` 以及 VLC 自带的 `COPYING` / `COPYING.LIB`。
