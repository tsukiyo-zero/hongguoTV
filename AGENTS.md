# Repository workflow

- 后续 Git 提交标题和提交说明使用中文，清楚描述具体修改内容及必要原因；代码标识符、命令和版本号可保留原文。
- 提交标题使用 Conventional Commits 类型前缀，例如 `feat:`、`fix:`、`docs:`、`test:`，前缀后的描述及提交正文继续使用中文。

- The stable branch is `main`. Keep existing upstream history and GPL notices.
- The user prefers flat branch names without grouping prefixes. Use `feature-...` and `fix-...`; `android6-support` records the completed compatibility work.
- `main` supports Android 6+ (minSdk 23), including Android 8+. The user explicitly authorized merging the verified Android 6 compatibility feature as the common baseline. Keep a single APK/version line; check both API 23 and API 26 when changing native compatibility.
- For subsequent features and fixes, branch from the latest `main` using `feature-<short-description>` or `fix-<short-description>`. Implement and validate there, then use a pull request to merge into `main`; do not directly push new feature work to `main` unless the user explicitly authorizes it.
- The initial migration of the existing working features into this repository's `main` is authorized directly by the user.
- The active Android client is `kotlin-tv/`. Keep historical React Native sources unless the task specifically concerns their removal.
- Keep README.md in sync whenever adding, changing, or removing user-facing features. Update platform-specific README and usage/limitations documents when relevant; describe only implemented behavior and distinguish unverified or unavailable capabilities. Include documentation updates in the same change.
- Run relevant checks. Native changes normally require `:core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`; documentation-only changes do not need Android builds.
- APKs, build output, account sessions, phone numbers, verification codes, passwords, and signing keys must not be committed. Credentials must not appear in logs or documentation.
- 用户已授权将 TV 客户端更名为「果映 TV」，applicationId 从 `com.hongguotv.nativeapp` 改为 `io.github.tsukiyo_zero.guoyingtv`。新包是独立应用，旧版不能覆盖升级，本机数据与登录态不自动迁移；保留旧包历史发布记录和签名基线。后续保持新包名和原签名连续性。
- Preserve package identity and signing continuity for updates. Increase `versionCode` for each formal release; keep versionName/versionCode unchanged during debugging and distinguish debug APKs by filename. Current TV release is 0.1.20 (versionCode 61); increase versions only for the next formal release.
- App updates use only `tsukiyo-zero/hongguoTV` Releases. A source push or CI artifact is not a release. Follow `kotlin-tv/AUTO_UPDATES.md`; do not reintroduce the upstream cross-repository publishing workflow.
- 用户无需每次指定 TV 版本。发布时使用统一入口 `python3 scripts/release-tv.py --publish`，自动核对两平台正式版本、实际 versionCode、已有标签和草稿；默认递增补丁版本，versionCode 超过源码及已发布包的最大值。默认无参数仅预演，只有用户明确要求正式发布时才使用 `--publish`，不为脚本内部步骤重复要求确认。
- 功能仍先走 PR 和用户验收；统一入口会自动准备版本提交／PR、合并、一次构建检查、校验原签名与 v1、同步两平台源码标签，并调用现有上传脚本发布和验证 Latest。若有中断任务，使用 `--publish --resume <版本>` 继续，不另分配版本、不覆盖旧标签或附件。脚本创建 PR 后读取 `outputs/release-jobs/<版本>/state.json` 中的 `prUrl` 并附加到当前聊天。

- The user prefers to install APKs and perform manual emulator/TV tests themselves, then send screenshots. Run automated checks and package locally; do not operate the emulator or install test builds unless explicitly requested again.

- The native Mac client lives in `desktop-mac/`. The user authorized merging the tested Mac client into `main`; continue future Mac development on the long-lived `feature-mac-client` branch, retaining it after PR merges. Sync from `main` as needed. TV remains the priority; Mac has independent version/build numbers and a fixed `mac-stable` GitCode Release with `mac-update.json`, never marked Latest.
