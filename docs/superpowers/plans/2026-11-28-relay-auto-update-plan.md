# 伴机自动升级实施计划

2026-11-28。前置：设计 spec（../specs/2026-11-28-relay-auto-update-design.md）。分支沿用 `feature-relay-portrait-bytevc1`（伴机 1.1.0 一次发布同时带 bytevc1 文件模式与自动升级）。上一功能（bytevc1 兜底）代码已完成待提交/验收，本计划改动叠加其上，PR 一并提交（用户已同意暂不推）。

> **状态（2026-11-28）**：M1/M2/M3 全部完成。`:relay:test` 全绿；清单脚本冒烟通过（生成+verify 闭环）；安装脚本真机演练通过（临时假 dmg/假 .app 真实替换成功，演练修正 ditto `--rsrc` 与 trap EXIT 兜底卸载两处缺陷，演练产物已清理）。待用户提交+验收；真实端到端自升级待 1.1.1 发版自然验证。

## M1 伴机更新器（relay 模块）

1. **RelayLocations.updatesDir(home, appData)**：`stateDir/updates/`，normalize+startsWith 校验沿既有规范；不自动创建，使用前 mkdirs。
2. **RelayUpdater（新文件）**，职责四块：
   - 清单：`check()` 拉取 `<base>/relay-update.json`（基底默认 relay-stable 直链，`-Dguoying.updateBase` 可覆盖），org.json 解析 + 字段校验（version 三段、平台块 fileName/size/sha256 格式，sha256 须匹配 `^[a-f0-9]{64}$`）；平台块缺失=该平台不可自动更新；
   - 版本比较：三段数字（缺段/非数字段按 0），`isNewer(current, candidate)`；
   - 下载：OkHttp 流式下载到 `updatesDir/<fileName>`，size+sha256 双校验失败删文件抛错；进度回调（已收字节/总字节→百分比）回状态窗口；updates/ 内同版本已校验包直接复用（按「文件存在且 size 匹配且 sha256 匹配」判定，sha256 校验本地文件即足够，不重复下载）；新版本包落地后清理更旧版本包；
   - 安装脚本生成：`macInstallScript(dmgPath, appBundlePath)` 生成孤儿 sh 脚本文本（attach→ditto→xattr→detach→open），纯函数便于断言；`winInstallCommand(msiPath)` 返回 msiexec 向导命令。
3. **RelayMain 接线**：启动后台 8s 静默检查 + 24h 定时（并入现有 scheduler）；菜单「检查更新」回调→手动检查（失败可提示）；发现新版→状态文本→后台下载（进度）→完成后 JOptionPane「立即升级／下次再说」（AWT 事件线程）→确认：停会话→生成脚本落 updates/（mac：`sh <script>` 后台起；win：msiexec 向导）→本进程退出；无 appRoot（fat jar）→跳过自动替换直接回退指引。
4. **RelayTray**：`install` 增 `onCheckUpdates` 回调与「检查更新」菜单项；升级中置位后除「退出」外菜单禁用（新增 `setUpgrading(true)` 方法）。
5. **单测（RelayUpdaterTest）**：清单校验族（坏 sha256/缺字段/缺平台块/version 非法）、版本比较族（1.10.0>1.9.0/缺段/相等）、下载校验族（本地 HttpServer：size 不符/sha256 不符/成功；同版本复用不重复下载）、脚本内容断言（attach/ditto/xattr/open 关键字、路径含空格加引号）。

## M2 发版配套与文档

1. `scripts/relay-update-manifest.py <版本> <dmg> <msi>`：hashlib 计算 size/sha256，输出 relay-update.json（stdout 或 `-o` 路径）；`--verify <json>` 反向校验模式；仅本地生成，人工上传。
2. relay/README.md「行为说明/发版」补自动升级节：清单发布步骤 + 用户侧行为描述；主 README 伴机段补一句「伴机自动检查更新并一键升级」。

## M3 全链路演练与检查

1. 本地 HttpServer fixture（临时 Python 或 relay 测试内嵌）放「版本 9.9.9 清单 + 真 1.1.0 dmg」→ `-Dguoying.updateBase=http://127.0.0.1:<port>/` 启动伴机 → 验证 检查→下载→校验→确认框→（确认）脚本执行→重启后为新包。演练只验机制；**真实端到端自举待 1.1.1 发版时自然验证**（spec §7 已注明）。
2. `:relay:test` 全绿；AGENTS 检查套件四任务不涉（无 :core/:app 改动）。

## 工程约束

- 提交：中文 Conventional Commits，Mimosa 拦 git commit → 我改文件、用户终端提交；改动叠加在 bytevc1 兜底功能之上，**提交顺序**：先 bytevc1 两笔（见上一交付说明）→ 本功能一笔（`feat: 伴机自动检查更新并支持一键升级`，含 M1+M2+M3 文档）。
- 版本：relayVersion/VERSION 维持 1.1.0（自动升级属 1.1.0 功能集）；TV versionCode 不动。
- 成功标准：`:relay:test` 全绿；演练全链路（含真替换与重启）通过；文档与发版清单脚本就位。
