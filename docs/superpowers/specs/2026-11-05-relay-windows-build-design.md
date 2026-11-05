# 果映伴机 Windows 构建拆分分支设计

> **状态（2026-11-05）**：设计已与用户确认，实施在新分支 `feature-relay-windows-build` 上进行。前置：Windows msi 构建已自 `feature-relay-transcode` 用收官提交 `20b9f70` 摘出（该 PR 聚焦 macOS 制品），拆分原因是 **msiexec 静默安装挂起待排**；被摘除的双平台 workflow 与 Windows 打包实现完整保留在 git 历史（`87f02de`…`6118d30`）中。

2026-11-05。本设计规划 Windows 伴机构建（msi 制品）的独立实施：恢复被摘除的实现、解决 msiexec 冒烟挂起、在推送 GitHub 前完成一次完整的本地构建与冒烟验证。

## 目标与范围

**目标**：在新分支上恢复 Windows msi 构建，产出与 macOS 同管线的 `GuoyingRelay-1.0.0.msi`，本地跑通「测试 + ffmpeg 捆绑 + jpackage msi + 冒烟」后推送 GitHub，CI 在新分支自动出制品。

**范围外**：

- 正式发布（制品挂本仓库 Actions 运行页，不进 Releases，不动版本号，维持 `relayVersion=1.0.0`）
- `feature-relay-transcode` 的任何后续改动（该分支等源仓库合并，保持收官状态）
- macOS 侧行为变更（平台无关产品代码——状态目录、binaryName——已在 `20b9f70` 保留，本分支不动）

## 分支与恢复方式

- 基于 `origin/feature-relay-transcode`（`20b9f70`）切出 `feature-relay-windows-build`；不基于 `6118d30`，避免与源仓库已合并的摘除提交形成分叉历史。
- 首个实施提交 = `git revert 20b9f70`：干净恢复双平台 workflow 与 Windows 打包逻辑。该提交仅改 `.github/workflows/relay-build.yml` 与 `relay/backend/build.gradle.kts` 两个文件，revert 无冲突风险；提交说明写明「自 20b9f70 恢复 Windows 构建，转入独立分支实施」。

## msiexec 挂起处理（核心问题）

CI 上 `msiexec /i <msi> /qn` 静默安装曾挂起，根因未定位。三个候选手段，可组合：

- **a（冒烟主路径，推荐）`msiexec /a` 管理员解包替代静默安装**：不触发 Windows Installer 安装服务流程，绕开未知挂起点；解包出完整程序目录后直接启动其中 exe，做 `/health` 断言（`ffmpegOk=true`）。覆盖 ffmpeg 捆绑、启动器、健康接口；代价是「真实安装流程」覆盖弱一档。
- **b（定位与兜底）静默安装加超时与日志**：PowerShell `Start-Process msiexec -ArgumentList '/i',<msi>,'/qn','/norestart','/lv*x',<log> -Wait`，外层设超时；挂起时凭日志定位根因。若 b 本地能稳定通过，CI 冒烟可升级回完整静默安装。
- **c（本地补充）带 UI 交互安装一次**：本地人工确认真实安装/卸载流程，仅在本地执行，不进 CI。

决策规则：先在本地验证 a；a 通过则以 a 进 CI，b 作为可选项并行尝试（拿到挂起日志即记录到本 spec 附录，根因明确前不强求 CI 用 b）。

## 本地构建与 WiX 获取

- choco 本机不可用 → WiX 改用官方 `wix314-binaries.zip` 免安装方案：下载到仓库外独立目录解压（如与仓库同级的 `relay-tools/wix314/`，不入库不进 CI），构建时临时注入 PATH（`candle`/`light` 可被 jpackage 发现即可）；下载与解压失败即中止，不落半成品。
- GyanD 7.1.1 ffmpeg zip 由 `bundleFfmpeg` 任务自动下载并 sha256 校验，无需人工干预。
- 构建命令与 CI 一致（Git Bash）：`./kotlin-tv/gradlew -p relay -Prelay.jdk=<JDK17> -PrelayVersion=1.0.0 :relay:test :relay:jpackageCompanion`，本机 JDK 17（Temurin 17.0.21）已就绪。

## 推送前冒烟门槛（硬门槛）

- **L1（构建，免管理员，可自动执行）**：上述 gradle 命令退出码 0，`relay/backend/build/jpackage/GuoyingRelay-1.0.0.msi` 存在。
- **L2（冒烟，需管理员，用户执行）**：运行与 CI 同一份冒烟脚本（`scripts/smoke-relay-windows.sh`，新增）：方案 a 的 `/a` 解包 → 启动 exe → `curl /health` 断言 `ffmpegOk=true` → taskkill 收尾；可选追加一次 c 的交互安装确认。结果由用户贴回。
- L1+L2 均通过后，才允许推送 GitHub。

## CI 与推送

- 新提交把 `relay-build.yml` 的 push 自测触发加回，指向 `feature-relay-windows-build`（`20b9f70` 曾移除 push 触发）；paths 过滤保持 `relay/**` 与 workflow 文件自身。
- `git push -u origin feature-relay-windows-build` 后，CI 双平台 matrix 自动跑（macOS dmg + Windows msi），制品挂本仓库 Actions 运行页。

## 文档同步与清理

- `relay/README.md` 补 Windows 节：构建方式（含 WiX 免安装获取）、安装/运行说明、与 macOS 的差异及已知限制；仅描述已实现行为。
- 删除仓库根目录 4 个未跟踪 JVM 崩溃日志（`hs_err_pid*.log`、`replay_pid*.log`），不入库。

## 成功标准与风险

**成功标准**：L1 构建零失败；L2 health 冒烟通过且进程/解包目录清理干净；推送后 CI 双平台绿、Windows 制品可下载。

**风险**：

- `msiexec /a` 解包行为偏差（极少见）→ 落回方案 b，本地即可发现；
- 恢复实现后 macOS 路径回归 → revert 只触碰 workflow 与打包脚本，且 `:relay:test` 与 CI macOS 侧同时验证；
- WiX zip 下载失败 → 构建即失败无副作用，可换源或手动放置。
