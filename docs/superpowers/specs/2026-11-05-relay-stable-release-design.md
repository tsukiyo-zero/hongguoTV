# 伴机包固定 Release 发布机制设计

日期：2026-11-05
状态：已与用户确认的设计
关联：PR #62（macOS 伴机，已合并）、PR #64（Windows 伴机，已合并）、issue #63（进展同步）

## 背景与问题

伴机包（GuoyingRelay dmg/msi）当前由 `.github/workflows/relay-build.yml` 构建并上传为 Actions artifact，存在三个问题：

1. CI 不发布：artifact 只存在于 Actions 运行页，且有 90 天有效期，过期自动删除
2. 沉没风险：果映 TV 持续发版占据 Latest，伴机包若挂在某次 release 附件会随翻页沉入过往 release
3. 无固化入口：relay/README.md 自述「制品目前由本仓库 Actions 构建页产出，尚未挂正式 Release」，普通用户拿不到稳定下载地址

## 目标

- 伴机包有永不失效的固定下载入口，随迭代持续更新，不沉没
- 发布动作零手工、零凭据配置
- README 固化链接直达

## 已确认的关键决策

| 决策点 | 结论 | 理由 |
|---|---|---|
| 发布位置 | 贡献者 fork `WingkySky/hongguoTV` 的固定 `relay-stable` Release | 用户无上游 GitCode 仓库发布权；mac-stable 的 GitCode 模式不可复制；fork Releases 页面干净、直观 |
| 执行方式 | CI 自动发布（构建冒烟通过后发布） | fork Actions 内置 GITHUB_TOKEN 天然有本仓库 Releases 写权限，零 secret、零泄漏面 |
| README 链接形态 | 指向 Release 页面（非文件直达） | 附件保留 `GuoyingRelay-<版本>.<ext>` 版本化命名，页面直观可见当前版本；页面 URL 永不变化 |
| 否决项 | 上游 GitCode relay-stable（无权限）；自建 GitCode 仓库（需配 token、与上游生态分离、README 写个人地址接受度更差）；手工上传（易忘）；文件直达链接（版本化文件名会变，需每次改 README） | — |

## 设计

### 1. 发布流水线（改造 .github/workflows/relay-build.yml）

- 构建矩阵不变：macos-14（arm64）+ windows-latest（x64），构建 + 冒烟 + 上传 artifact，现有步骤不动
- 新增发布 job：
  - `needs: build`，两平台构建冒烟全部成功后运行
  - 步骤：checkout → 下载两个平台的 artifact → `gh release create relay-stable`（首次，自动建 tag）或 `gh release upload relay-stable --clobber` 上传 dmg/msi → 删除该 Release 下旧版本附件（保持页面只有当前一版两平台文件）→ 更新 Release 说明
  - 凭据：`GITHUB_TOKEN`（workflow 顶层声明 `permissions: contents: write`）
- 并发控制：`concurrency: group: relay-build, cancel-in-progress: true`，防并发 push 竞争
- 触发：
  - push 分支列表改为 `[main, feature-relay-stable-release]`，paths 过滤保留（`relay/**` + workflow 自身）
  - `workflow_dispatch` 保留（含 version 输入）
  - `pull_request` 触发待 workflow 进入上游默认分支后生效（现有注释保留）

### 2. Release 生命周期（固定 tag relay-stable）

- 该 Release 永远只保留当前一版的两平台附件：`GuoyingRelay-<版本>.dmg` + `GuoyingRelay-<版本>.msi`
- 版本号取 `relay/gradle.properties` 的 `relayVersion`，每次正式迭代递增（当前 1.0.0）
- Release 说明由 CI 生成：当前版本号、发布日期；格式固定，面向用户一眼可见
- 不做自动更新清单（mac-update.json 类似物）：伴机 App 内自更新是 PR #62 已知后续项，本设计不扩展

### 3. README 固化链接

- `relay/README.md`：「普通用户（推荐）」下载章节改为固定链接 `https://github.com/WingkySky/hongguoTV/releases/tag/relay-stable`（Release 页面，进页面点附件下载），删除「尚未挂正式 Release」旧说明，改为描述固定 Release 机制（每次迭代覆盖，附件文件名含版本号）
- 主 `README.md`：伴机章节同步加同一链接
- 如实标注：README 写的是贡献者 fork 的 Release 地址，进上游 README 需维护者在 PR 验收时接受

### 4. 实施分支与 PR

- 从最新 `main`（含 #64 合并 ff15321）拉新分支 `feature-relay-stable-release`
- 改动（workflow 发布 job + 两处 README）在新分支实施，向上游 `tsukiyo-zero/hongguoTV` 提新 PR，走用户验收
- 先同步 fork main 与上游一致，再拉分支

### 5. 边界与错误处理

- 仓库 Settings → Actions 的 workflow 写权限需为 "Read and write"（个人仓库默认开启，实施时验证一次；若为只读需改设置）
- 发布 job 失败不影响构建结论（构建冒烟红即红）；发布失败可在 Actions 页手动重跑该 job
- `gh release create` 与 `gh release upload --clobber` 幂等：重复运行不产生重复附件，同版本名附件直接覆盖
- 删除旧附件仅删除 relay-stable Release 内的 `GuoyingRelay-*` 前缀文件，不触碰其它 release

### 6. 验收

- push 到 `feature-relay-stable-release` 触发完整链路：构建 → 冒烟 → fork 的 relay-stable Release 出现当前版本两平台附件 → 旧附件被清理 → Release 说明正确
- README 链接在浏览器可达，能下载附件
- 用户手工下载安装一次做最终验收
