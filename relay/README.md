# 果映伴机（guoying-relay）

局域网转码伴机：解决电视硬解播不了的剧集。两类输入按供给侧编码自动分派：

- **bytevc2（VVC/H.266）锁定集**：取流解密 → 样本表重组 Annex-B → VVC 软解 → 转 H.264 → HLS（现行管道）
- **单档 1080P 竖屏 bytevc1（标准 hvc1 HEVC，2026-11 起新剧供给形态）**：解密整集缓存 → ffmpeg 直读转 H.264 → HLS（文件模式，短剧全集体积小，缓存成本可忽略）

电视端果映 TV 遇到这类剧集时，把取流信息交给同一局域网内你自己的电脑，转出 H.264 后电视用硬件解码直接播放。

- 伴机**零登录态、零取源业务逻辑**：电视传来的取流信息与密钥仅存于内存，会话结束即清
- 仅监听局域网；接口带令牌鉴权（首启自动生成，防其他设备滥用）
- 需要 **ffmpeg 7+**（含原生 VVC 解码器；`brew install ffmpeg`）；启动时探测 VVC/HEVC 解码能力并经 `/health caps` 上报，电视端据此决定是否发起兜底

## 启动（macOS）

**普通用户（推荐）**：从[固定 Release 页](https://github.com/WingkySky/hongguoTV/releases/tag/relay-stable)下载 `GuoyingRelay-<版本>.dmg` → 拖进「应用程序」→ 双击 `果映伴机`（首次打开需右键 → 打开，或在系统设置允许）。菜单栏出现图标即运行中：

- 菜单显示运行状态与局域网地址；**复制地址 / 复制令牌** 一键拷贝
- **开机自启** 开关（LaunchAgents，默认关）
- 令牌持久化在 `~/Library/Application Support/GuoyingRelay/token`，重启不变

构建（本仓库）：

```sh
scripts/build-relay-macos.sh
# = ffmpeg 下载+sha256 校验 + fatJar + jpackage dmg（需 JDK 17；下载走 osxexperts，需代理时 export https_proxy）
```

**开发调试**：`scripts/run-relay.command`（终端直跑 fat jar，依赖系统 PATH 里的 ffmpeg 7+；令牌存启动目录 `.guoying-relay/token`，与 .app 的状态目录相互独立）。

启动日志/菜单栏会打印两样东西：

- **局域网地址**（如 `http://192.168.6.114:46521`）
- **伴机令牌**（8 位，App 形态持久化在 `~/Library/Application Support/GuoyingRelay/token`）

## 启动（Windows）

**普通用户**：从[固定 Release 页](https://github.com/WingkySky/hongguoTV/releases/tag/relay-stable)获取 `GuoyingRelay-<版本>.msi` → 双击安装（有选择安装目录的向导页）→ 开始菜单「果映伴机」文件夹或桌面快捷方式启动。运行后**驻留任务栏右下角托盘**（Win11 默认收在「^」溢出区，可拖出常显），启动时以气泡通知提示地址，不弹常驻窗口：

- 托盘菜单与 macOS 菜单栏一致（状态/复制地址/复制令牌/退出），「打开状态窗口」可随时唤出地址与令牌面板；「开机自启」暂为 macOS 专属（LaunchAgents），Windows 菜单不显示该项
- 令牌持久化在 `%APPDATA%\GuoyingRelay\token`，重装不变
- 安装包由 CI 自动发布到上述固定 Release 页，每次迭代覆盖更新（页面永远只有当前一版的两平台附件，文件名含版本号）

**构建（本仓库，Git Bash）**：需 JDK 17 与 WiX 3.x（jpackage 出 msi 依赖 `candle`/`light`；可下载官方 `wix314-binaries.zip` 免安装解压后注入 PATH）：

```sh
export PATH="/path/to/wix314:$PATH"
./kotlin-tv/gradlew -p relay -Prelay.jdk="$JAVA_HOME" -PrelayVersion=1.0.0 :relay:test :relay:jpackageCompanion
# = GyanD ffmpeg 7.1.1 下载+sha256 校验 + fatJar + jpackage msi
# 产物：relay/backend/build/jpackage/GuoyingRelay-<版本>.msi
```

慢网技巧：GyanD zip（约 90MB）直连 GitHub 慢时，可手动下载后放到 `relay/backend/build/ffmpeg-bundle/ffmpeg-7.1.1-essentials_build.zip`，任务检测到文件存在会跳过下载，直接解压并做 sha256 校验（字节不符即构建失败，完整性有兜底）。

**冒烟**（管理员 Git Bash）：`scripts/smoke-relay-windows.sh`（msiexec /a 管理员解包 → 启动 → `/health` 断言 ffmpegOk=true → 清理；加 `--install` 追加带 UI 交互安装确认）。已知限制：CI 上 `msiexec /qn` 静默安装曾挂起、根因未定位，冒烟与 CI 均走 /a 解包路径。

## 电视端配置（一次性）

1. 播放器「更多 → 播放设置 → 伴机代解码」
2. 开关打开，填入伴机地址与令牌；或点「搜索伴机」自动发现（mDNS）、用「扫码输入」在手机上填
3. 保存后播放硬解播不了的锁定集（bytevc2 锁定集或单档 1080P 竖屏 bytevc1 剧集）即可，无需再干预

## 行为说明

- 仅「电视硬解播不了的集」走伴机；其他剧集播放行为完全不变，伴机不在线或版本过旧（不支持所选编码）时回落原有错误提示
- 转码档位：bytevc2 集跟随其最高档（多为 720P 竖屏），bytevc1 集取 ladder 最高档（如 1080P 竖屏）；H.264 目标码率 5M/8M 按源分辨率
- 拖进度条：HLS 播放列表实时增长（实测 bytevc1 文件模式整集 88.8s 约 20s 转完 ≈4.4x 实时），seek 到未转码区间会短暂等待
- 同时仅 1 个会话；10 分钟无人拉流自动回收
- 电视端持续监测转码速度，低于实时会提示「伴机性能不足」

## 自动升级

- 启动后台静默检查 + 每 24 小时一次 + 托盘菜单「检查更新」手动触发；清单取自固定 Release 的 `relay-update.json`（version/notes + 两平台 fileName/size/sha256）
- 发现新版即后台下载到 `updates/`（状态目录下），size+sha256 双校验；完成后弹「立即升级／下次再说」
- macOS 确认后自动替换应用并重启（退出 → 挂载 dmg → 覆盖 .app → 重启）；Windows 确认后启动 msi 安装向导（用户点两下完成）
- 脚本失败或开发模式（非 .app 启动）时回退：在 Finder 展示已校验安装包 + 文字指引；「下次再说」下次启动重新提示
- 发版配套：`python3 scripts/relay-update-manifest.py <版本> <dmg> <msi> > relay-update.json`（本地生成，`--verify` 可反向核对），人工将 dmg/msi/json 四件上传 relay-stable 覆盖更新
- 已知限制：1.0.0 老伴机无检查更新能力，升级到 1.1.0 需手动一次；真实端到端自升级路径自 1.1.1 发版起生效

## 安全模型

- 令牌仅防局域网内其他设备**借用算力**；伴机不持有任何账号凭据
- TV ↔ 伴机为局域网明文 HTTP（令牌随头传输）；视频密钥由电视端解析并随会话传入，仅存伴机内存
- 计划收紧方向：伴机侧 TLS（自签证书）+ 固定局域网域名后恢复 NSC 白名单严格模式

## REST 接口（自用协议，随版本演进）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/health` | 版本、ffmpeg 能力（`caps`：可转码编码族，如 `["bytevc2","bytevc1"]`）、会话状态与实时转码倍率 |
| POST | `/session` | 建会话（body: url/keyB64/quality/codec，codec 缺省按 bytevc2），返回 sessionId+hlsUrl |
| POST | `/session/{id}/seek` | 按新偏移重启转码 |
| DELETE | `/session/{id}` | 结束会话并清理 |
| GET | `/hls/{id}/index.m3u8` 及分段 | 播放列表与转码输出 |
| GET | `/a/{id}/audio.mp4` | 解密后音频轨（VVC 管道 ffmpeg 第二输入；文件模式不使用） |
