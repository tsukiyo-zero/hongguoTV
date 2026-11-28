# 伴机自动升级（relay-update.json + 半自动一键安装）

> **状态（2026-11-28）**：已实施——RelayUpdater（清单/下载/sha256/复用/清理）、托盘「检查更新」与升级锁定、RelayMain 接线（8s+24h 检查、确认对话框、mac 孤儿脚本快速失败窗口、win msi 向导、回退指引）、`scripts/relay-update-manifest.py`、文档全部落地。`:relay:test` 全绿；安装脚本真机演练通过（假 dmg+假 .app 真实替换，演练中修正两处：ditto 用 `--rsrc` 而非 `-R`、trap EXIT 兜底卸载防挂载泄漏）。待用户验收与提交。实施记录见 2026-11-28-relay-auto-update-plan.md。

2026-11-28。动机：伴机升级目前靠用户手动重装（下载 dmg/msi → 手动替换/跑安装器），非极客用户不会动。TV 端与 desktop-mac 端均有成熟先例：TV AppUpdater（检查→自动下载→强校验→确认安装）与 desktop-mac（固定 Release 上 `mac-update.json` 清单：version/size/sha256/notes，下载后校验、展示包+指引）。伴机对两者取中：发现即静默下载+强校验，确认后一键安装。

自举限制（如实说明）：现网 1.0.0 老伴机没有检查更新能力，本次 1.0.0→1.1.0 仍需手动安装一次；自动升级自 1.1.0 起生效，真实端到端路径在下一次发版（1.1.1）自然验证。

## 目标（验收标准）

1.1.0 伴机启动后台静默检查 relay-stable 清单；发现新版即后台下载并 sha256/size 双校验；确认后 macOS 一键替换 .app 并自动重启，Windows 启动 msi 向导；失败回退「展示已校验安装包+文字指引」。

## 1. 更新源与清单

- 清单 URL 基底：`https://github.com/WingkySky/hongguoTV/releases/download/relay-stable/`，清单文件名固定 `relay-update.json`（release 资产直链：静态、可 pin、无 API/鉴权/频控问题）。基底可用系统属性 `-Dguoying.updateBase` 覆盖（对齐既有 `-Dguoying.debugEs` 调试口先例），供本地 fixture 演练。
- 清单 schema（对齐 desktop-mac MacManifest 先例，字段在伴机内校验，缺一拒用）：
  - `version`：三段版本号字符串；
  - `notes`：更新说明文本；
  - `mac` / `win` 平台块：`fileName`、`size`（字节数）、`sha256`（64 位十六进制）。
- 平台块缺失按该平台「不可自动更新」处理（只提示不下载）；`version` 非法时整份清单拒用。

## 2. 检查策略与版本比较

- 时机：启动后台 8 秒后静默检查 + 托盘菜单「检查更新」手动 + 每 24 小时定时检查；非手动检查失败完全静默（手动失败才提示「检查失败，请重试」）。
- 版本三段数字比较（`1.10.0 > 1.9.0`；缺段按 0；非数字段按 0）；相同/更旧不提示。
- 「下次再说」：本进程不再弹确认，下次启动重新提示（TV 同款每启动周期一次）。

## 3. 下载与校验

- 落盘 `stateDir/updates/`（`RelayLocations.updatesDir(home, appData)`，沿既有 normalize+startsWith 校验规范）；文件名取清单 `fileName`（带版本号，天然区分版本）。
- size + sha256 双校验，失败即删（下次检查重下）；下载进度百分比写状态窗口与托盘状态行。
- 同一时刻仅一个下载任务；已校验的包按版本保留，同版本重试不重复下载；检查到新版本落地后清理 updates/ 内更旧版本的包，避免状态目录无限增长。

## 4. 安装执行（分平台）

- **macOS**：`JOptionPane` 确认「升级到 x.y.z？（将退出伴机并替换应用）」→ 确认后停会话（manager.shutdown）→ 起孤儿 `sh` 脚本：`sleep 1` → `hdiutil attach` → `ditto -R` 覆盖现有 .app（目标=进程可执行路径向上定位的 .app 包目录；`RelayLocations.appRoot` 已能定位 Contents，向上取一层即 .app；fat jar 开发启动无 appRoot 时**不提供自动替换**，直接回退指引）→ `xattr -dr com.apple.quarantine`（去隔离属性，避免重启再弹 Gatekeeper）→ `hdiutil detach` → `open` 重启伴机 → 本进程退出。
- **Windows**：确认对话框 → 停会话 → `ProcessBuilder("msiexec", "/i", <msi 路径>)` 启动**图形向导**（不静默、不等待，规避 msiexec /qn 挂起史）→ 本进程退出让安装器接管。
- **失败回退**：替换脚本失败/用户取消时，已校验安装包留在 updates/，回退「在 Finder/资源管理器展示 + 文字指引」（对齐 desktop-mac 先例）。
- 「下次再说」确认对话框带「立即升级／下次再说」两键；升级确认后置位升级中标记，退出前的菜单项除「退出」外禁用。

## 5. UX 与接线

- 托盘菜单新增「检查更新」项（RelayTray.install 加回调，nullable tray 场景 headless 纯后台仍可自动检查+提示走状态窗口与对话框）。
- 状态文本通道复用现有 `RelayTray.status`/`RelayWindow.update`（正在检查／正在下载 x%／新版已就绪待确认／升级中）。
- 状态窗口活动行追加升级事件（复用 activitySink）。

## 6. 发版流程配套

- 新增 `scripts/relay-update-manifest.py <版本> <dmg> <msi>`：计算两平台 size/sha256 生成 `relay-update.json`（仅生成不上传，符合「CI 只产制品不发布、人工挂载」约束）；输出到 stdout 或指定路径。
- 发版操作清单：制品构建 → 生成清单 → 四件（dmg/msi/json）人工上传 relay-stable 覆盖更新；`relay/README.md` 与主 README 伴机段同步说明。

## 7. 测试与验收

- 单测（relay 模块）：清单解析与字段校验（坏 sha256 格式/缺字段/缺平台块/version 非法）、版本三段比较、下载+sha256/size 校验（本地 HttpServer fixture + 本地文件 sha256 预计算）、更新脚本内容断言（dry 字符串检查，不真跑覆盖）、状态目录 updates/ 路径校验。
- 全链路演练：本地 HttpServer 放「版本 9.9.9 假清单 + 真 1.1.0 dmg」→ `-Dguoying.updateBase` 指向 → 观察 检查→下载→校验→确认对话框→一键替换→重启 全链路（假版本号不影响机制验证）。
- 真实端到端自举验证发生在下一次发版（1.1.1），届时按本 spec 验收。
- 检查套件：`:relay:test` 全绿（纯 relay 模块改动，无 :core/:app 触碰）。

## 8. 边界与不做

- 不做增量更新/灰度/强制升级/「永久跳过此版本」；不动 TV 端与 desktop-mac 更新器；不引入镜像/多源切换；Windows 保持「CI 冒烟 + 实验性」既有基线，无真机验收。
