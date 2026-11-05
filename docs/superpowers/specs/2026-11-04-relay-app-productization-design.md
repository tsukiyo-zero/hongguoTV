# 果映伴机产品化（macOS 菜单栏 App 与分发）设计

> **状态（2026-11-05）**：macOS 部分已实施完成并实机验收——GuoyingRelay-1.0.0.dmg（jpackage 全自包含：JRE+fatJar+捆绑 ffmpeg sha256 校验；菜单栏壳：状态窗口/多地址显示/复制/开机自启/退出；活动反馈：电视接入实时可见）。Windows 阶段二未启动（无验证环境，用户明确暂缓）。实施与排障记录见 2026-11-04-relay-transcode-plan.md。

2026-11-04。前置：伴机链路已全部验收（relay/ 模块 fat jar、令牌鉴权、mDNS 自动发现、扫码填地址令牌，见 2026-11-04-relay-transcode-plan.md）。本设计把「需要 JDK/ffmpeg/终端知识」的伴机变成**非极客可直接安装使用**的 macOS 制品；Windows 为阶段二（同管线换参数，另行细化）。

## 目标用户旅程（验收标准）

1. 从 GitHub Release 下载 `果映伴机.dmg` → 拖进「应用程序」
2. 双击 .app → 菜单栏出现图标 → 状态显示「运行中 · 局域网地址」
3. 电视端播放 bytevc2 锁定集 → 播放设置 → 伴机代解码 → 「搜索伴机」自动发现 → 扫码填令牌（8 位，App 菜单里可复制）→ 起播
4. 全程无需终端、无需安装 Java/Homebrew/ffmpeg

## 1. 制品形态

- `果映伴机.app`：jpackage app-image（再打 dmg）。内含 **JRE（jpackage 默认 runtime）+ fat jar + 捆绑 ffmpeg/ffprobe 二进制**
- 体积预估 ~100MB；独立版本号 0.2.0 起（`VERSION` 常量递进，不占 TV versionCode）
- App 名称/图标：`果映伴机`；图标用现有 TV icon 缩放的菜单栏模板图（暗色菜单栏适配，Template image）

## 2. 菜单栏壳（java.awt.SystemTray，纯 JVM）

菜单项（AWT PopupMenu）：

- 状态行（禁用态文本）：`运行中 · http://<LAN>:46521` 或错误原因（ffmpeg 缺失/端口占用/令牌文件异常）
- 复制地址（写入系统剪贴板，气泡提示「已复制」）
- 复制令牌（同上）
- 开机自启 开/关（勾选切换）：写 `~/Library/LaunchAgents/com.guoying.relay.plist`（标准 plist，Program 指向 app 内启动脚本/二进制）+ `launchctl load/unload`；默认关
- 退出（stop 全部会话 + 退出进程）

行为要点：

- TrayIcon.displayMessage 用于状态变化通知（启动成功/失败、端口被占）
- App 双击语义：单实例——启动时探测 46521 端口，已有实例则本进程气泡提示「伴机已在运行」后退出（脚本场景的「结束旧实例」逻辑保留在 .command 里，两者互补）
- AWT 菜单在主线程（macOS 需要 `-Dapple.awt.UIElement=true` 隐藏 Dock 图标，仅菜单栏驻留）

## 3. ffmpeg 捆绑与许可

- Gradle 新增 `bundleFfmpeg` 任务：从固定 URL 下载预编译 ffmpeg/ffprobe 7+（macOS：evermeet.cx 静态单文件版），**sha256 写死在仓库校验**，产出放入 jpackage 输入资源目录；下载失败即构建失败（可换源重试）
- 运行时 ffmpegPath 解析顺序：**app 捆绑路径 → GUOYING_FFMPEG 环境变量 → 系统 PATH**；捆绑路径按 jpackage 实际产物布局定位（如 `Contents/app/ffmpeg/`，实现时确认），一律 normalize+startsWith 校验（Mimosa 教训）；捆绑缺失时自动落到 PATH 并在菜单栏状态行提示「正在使用系统 ffmpeg」
- 许可合规：app 资源附 `LICENSE-ffmpeg.txt`（GPL 源码获取指引）与 README 声明；本项目 GPL-3.0 与 ffmpeg 兼容

## 4. 配置/令牌路径迁移

- 状态目录迁移到 **`~/Library/Application Support/GuoyingRelay/`**（token 持久化；Windows 阶段二用 %APPDATA%）
- 启动时 best-effort 迁移：旧 cwd `.guoying-relay/token` 存在且新路径无 token → 搬迁；否则新生成
- 路径构造一律 normalize + startsWith 包含校验（Mimosa 扫描器要求）
- 令牌因此可能重新生成 → App 状态行提示「令牌已更新，电视端需重新填写」；电视端扫码填令牌流程已就绪

## 5. 构建管线与分发

- Gradle：`:relay:bundleFfmpeg`（下载+校验+落位）→ 既有 `:relay:fatJar` → `:relay:jpackage`（调用 JDK 自带 jpackage，--type app-image，--input 含 ffmpeg 资源）→ 脚本打 dmg
- 本地一键：`scripts/build-relay-macos.sh`（串联上述 + 打开输出目录）
- 分发：随 TV 正式 Release 附件（同一发布页标注「macOS 伴机」，Latest 仍指向 TV APK，符合 AGENTS.md 发布约束）；README/relay/README.md 更新下载指引
- 里程碑内不做 App 内自更新

## 6. Windows（CI 构建管线，2026-11-05 设计定稿）

- `.github/workflows/relay-build.yml`：`workflow_dispatch` 手动触发（输入版本号）——**CI 只产制品不发布**（符合 AGENTS.md：CI 产物≠Release，人工挂载），matrix = `macos-14`(arm64) + `windows-latest`(x64)
- Windows ffmpeg 捆绑：**GyanD/codexffmpeg 7.1.1 版本化 release**（GitHub 直链不可变、sha256 可固定，essentials build 含 VVC 解码器）→ 解出 ffmpeg.exe → 校验 → 落位；哈希固化进 relay/ffmpeg-hashes.properties（zip 解压与哈希不依赖平台，本地可预先算好）
- 打包：jpackage `--type msi`（WiX：runner 预装检测，缺失 `choco install wixtoolset` 兜底）→ `GuoyingRelay-<版本>.msi`；失败回退 app-image+zip
- **CI 冒烟**：runner 直接启动产物 → `curl /health` 断言 `ffmpegOk=true`（Windows runner PATH 无 ffmpeg，天然模拟用户环境）；产物 upload-artifact，人工挂 Release
- 代码侧：bundleFfmpeg 增 Windows 分支、jpackageDmg 重构为跨平台 jpackageCompanion（dmg/msi）、RelayLocations 适配 ffmpeg.exe 后缀与 Windows 布局、版本号单一来源（VERSION 常量）
- 边界：MSI 未签名（SmartScreen 警告需文档注明，签名需证书范围外）；**Windows 真机播放未验证**——CI 冒烟是出厂底线，MSI 标注「实验性」待有 Windows 机器验证后转正；Local 验证方式=zip 解压算哈希（不依赖平台）

## 7. 测试与验收

- 单测：ffmpegPath 解析器（捆绑/env/PATH 三级）、状态目录迁移（旧→新）、LaunchAgents plist 内容
- 实机验收（macOS）：dmg 安装 → 双击 → 菜单栏状态 → 电视全链路（自动发现→扫码填令牌→起播）→ 重启电脑验证开机自启（若开启）→ 退出/重启 App 令牌不变
- 合规检查：ffmpeg LICENSE 文件在包内、dmg 内无凭据/无私有 IP

## 8. 范围外

Windows 详细设计（阶段二）、App 内自更新、Linux/NAS 分发、多伴机组网。

## 风险

- evermeet.cx 下载源不稳 → sha256 校验失败即构建失败，可换源重试；必要时自建 ffmpeg 构建
- AWT SystemTray 在 macOS 新版本的兼容性 → 已知可用（JDK 17 支持）；若遇渲染问题，回退方案是方案 B（Swift 壳），仅换外壳不动协议
- jpackage runtime 体积偏大 → 可后续 jlink 精简，非本阶段目标
