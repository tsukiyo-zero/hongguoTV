# 伴机 Windows msi 构建实施计划

2026-11-05。前置：拆分设计 spec（../specs/2026-11-05-relay-windows-build-design.md，提交 4d2cdca）。Windows 实现历史在 `87f02de`…`6118d30`，摘除提交 `20b9f70`（msiexec 静默安装挂起待排）。分支 `feature-relay-windows-build`（基于 origin/feature-relay-transcode 的 20b9f70）。

## M1 恢复 Windows 构建 + 本地 L1

1. **revert 摘除提交**：`git revert --no-commit 20b9f70` → 用户终端提交。恢复 relay-build.yml 双平台 matrix 与 build.gradle.kts 的 Windows bundleFfmpeg/jpackageCompanion 分支；平台无关产品代码不受影响。
2. **WiX 免安装**：下载官方 `wix314-binaries.zip` → 解压到仓库同级 `relay-tools/wix314/`（不入库不进 CI）；GitHub releases 下载，必要时 export https_proxy。
3. **L1 构建**（Git Bash，PATH 注入 WiX bin）：
   `./kotlin-tv/gradlew -p relay -Prelay.jdk="$JAVA_HOME" -PrelayVersion=1.0.0 :relay:test :relay:jpackageCompanion`
   产出 `relay/backend/build/jpackage/GuoyingRelay-1.0.0.msi`。
   - 已知坑（承自 macOS 侧记录）：jpackage 版本号不能 0 开头（1.0.0 无碍）；GyanD 7.1.1 走 GitHub 版本化直链，必要时代理；ffmpeg sha256 校验对象是解压后二进制而非 zip。
   - SessionTest 在 Windows 跳过属预期（b7450aa：假 ffmpeg 脚本为 POSIX 方案，os 判定跳过）。

## M2 冒烟脚本 + L2 本地冒烟（推送前硬门槛）

1. **scripts/smoke-relay-windows.sh**（与 CI 冒烟同源）：`msiexec /a <msi> /qn TARGETDIR=<临时目录>` 管理员解包（主路径，绕开 Windows Installer 安装服务流程）→ 递归定位 GuoyingRelay.exe → `--port 46599` 启动 → `curl /health` 断言 `ffmpegOk=true` → taskkill 收尾 + 清理；`--install` 可选分支做带 UI 交互安装（人工确认真实安装/卸载，不进 CI）。
2. **用户管理员 Git Bash 跑 L2**，结果贴回。
3. （并行可选项，不阻塞主线）msiexec 静默安装 + `/lv*x` 日志 + 超时尝试，拿到挂起日志记入本 plan 附录，定位 CI 挂起根因。

## M3 CI 触发 + 文档 + 清理 + 推送

1. relay-build.yml push 自测触发加回，分支指向 `feature-relay-windows-build`（20b9f70 曾移除；paths 保持 `relay/**` + workflow 自身）。
2. relay/README.md 补 Windows 节：构建方式（WiX 免安装 + gradle 命令）、安装/运行、与 macOS 差异及已知限制（msiexec 静默安装挂起未解，冒烟走 /a 解包）。
3. 删除仓库根目录 4 个未跟踪 JVM 崩溃日志（hs_err_pid\*.log、replay_pid\*.log），不入库。
4. 全部通过后 `git push -u origin feature-relay-windows-build` → CI 双平台 matrix 出制品（挂本仓库 Actions 运行页，不进 Releases）。

## 工程约束

- 提交：中文 Conventional Commits；Mimosa 拦 git commit → **我改文件、用户终端提交**（沿用既有约定）；推送视情况由用户执行。
- 检查：`:relay:test` 即可（不涉 :core/:app，无 Android 构建需求）。
- 版本与制品：不动 `relayVersion=1.0.0`；调试期不改版本号，制品不入 Release。
- 成功标准：L1 退出码 0 且 MSI 存在；L2 health 断言通过且清理干净；推送后 CI 双平台绿、Windows 制品可下载。
