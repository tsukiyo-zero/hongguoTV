# 应用内更新与发布

更新源为 GitCode 本仓库公开 Releases：`tsukiyo-zero/hongguoTV`。

电视先请求 `https://api.gitcode.com/api/v5/repos/tsukiyo-zero/hongguoTV/releases/latest?type=latest`，获取最新正式发行版标签，再读取该标签附件中的 `update.json`，比较内部版本号，并从 GitCode 根据清单的标签和附件名下载 APK。清单标签必须与接口返回标签一致。仅允许 HTTPS 和 GitCode 官方 API、站点及 file-cdn.gitcode.com 下载域名。此流程由 App 实现；最终覆盖安装由安卓系统执行。

## 新包名过渡

本轮 TV 更名为「果映 TV」，安装标识改为 `io.github.tsukiyo_zero.guoyingtv`，当前正式 0.1.7 / 48 仍是旧包名 `com.hongguotv.nativeapp`。新包名首次正式版需用户手动单独安装，不能依赖旧 App 的检查更新完成跨包名升级。本机数据与登录态不自动继承，账户需重新登录；当前设置没有备份恢复入口，尚不能把底层备份实现作为现成的用户迁移步骤。测试期间保持版本号不变。

新包的更新校验、打包与上传入口只接受新包名。统一入口读取历史版本时允许这两个已知包名以规划版本并保留旧签名基线，拒绝无关包名；首次过渡的自动更新说明会明确提示单独安装。新包名正式发布后继续同包名更新，不另开旧包名 APK 版本线；GitCode/GitHub 仓库及附件命名保持原协议。

## 发布流程

日常优先使用下方统一入口；本节分步骤流程及后面的打包／上传命令保留用于排查和单独操作。

1. 在 `app/build.gradle.kts` 调整 versionName，增加 versionCode。正式发布内部版本号必须超过上一正式版本；调试过程中不递增，使用文件名区分。允许跳过未公开的版本号，无需补发每个中间版本。环境变量可覆盖默认版本，打包后核对 APK 实际元数据。
2. 使用新包名 `io.github.tsukiyo_zero.guoyingtv` 和现有签名构建，检查最低系统、版本和签名。不要换用新 Debug 签名或将签名密钥提交到仓库。构建方法见 [README](README.md)。
3. 执行 `./scripts/build-kotlin.sh`，自动在 `outputs/releases/<tag>/` 生成配套 APK 和 `update.json`。清单从实际 APK 提取版本、包名、最低系统，并计算大小和 SHA256；环境变量覆盖后的版本也会正确读取。直接运行 Gradle 或 IDEA 的普通构建任务仍只生成 APK。
4. 在 GitCode 创建同仓库 Release。Target 选择实际打包所用的提交或分支；APK 若来自尚未合并的功能分支，不能误选 main。
5. 上传配套 APK 和 `update.json`，正式发布并设为 Latest。草稿和 Pre-release 不用于稳定在线更新。
6. 在较旧版本的设备上检查更新，核对下载与覆盖安装。推送代码、合并 main 或生成 Actions Artifact 不等于发布在线更新。

## 自动生成参数

默认从 APK 的三段版本号生成标签，例如 `0.0.8-search-input` 对应 `kotlin-v0.0.8`。发布时 GitCode 标签必须与清单一致。脚本不会自动增加版本号。

可以指定标签及 UTF-8 更新说明文件（最多 1500 字；不指定时说明为空）：

```sh
HONGGUOTV_RELEASE_TAG=kotlin-v0.0.8 \
HONGGUOTV_RELEASE_NOTES_FILE=/绝对路径/更新说明.txt \
./scripts/build-kotlin.sh
```

默认读取 Android SDK 的 aapt，也可用 `HONGGUOTV_AAPT` 指定工具路径。已有 APK 可以单独生成清单：

```sh
python3 scripts/prepare-kotlin-release.py \
  --apk /绝对路径/app-release.apk \
  --aapt "$ANDROID_HOME/build-tools/36.0.0/aapt" \
  --output outputs/releases
```

同一标签再次生成会覆盖该目录的 `update.json`；上传前确认附件名和清单一致。生成清单不代表验证了签名连续性，发布前仍需核对签名和已分发的最高 versionCode。

## 清单格式

以下只展示结构。版本、大小、SHA256 必须来自实际 APK，示例值不得直接发布。

```json
{
  "schema": 1,
  "packageName": "io.github.tsukiyo_zero.guoyingtv",
  "versionCode": 23,
  "versionName": "0.0.7-search-input",
  "minSdk": 23,
  "tag": "kotlin-v0.0.7",
  "apk": {
    "name": "hongguotv-kotlin-0.0.7-search-input-android6.apk",
    "size": 12345678,
    "sha256": "填写实际64位小写SHA256"
  },
  "notes": "面向用户的本次更新说明"
}
```

- versionName、versionCode 必须与 APK 内部版本完全一致，不能只改文件名或清单。
- tag 必须以 `kotlin-v` 开头，且与 Release 标签一致。
- APK 名称必须符合 `hongguotv-kotlin-版本标识-android6.apk`；仍兼容 `-android8.apk`。后缀表示最低系统，不是两条独立产品线。文件名和标签允许数字、英文字母、点号、加号和短横线。
- apk.name 与实际附件名一致；size 是字节数，sha256 是实际文件的 SHA256 小写十六进制值。
- 发布时同时提供对应源码或源码链接，保留 GPL 许可和第三方声明。

## 设备行为与边界

- 前台浏览时定期检查，播放时不打扰；默认自动检查下载，设置中可关闭或手动检查。
- 下载后核对大小、SHA256、实际包名、版本及签名，再调用系统安装器。
- 普通电视需要确认安装，并按系统要求允许此应用安装 APK，不能静默安装。
- Android 6/7 依次尝试 `ACTION_INSTALL_PACKAGE` 与 `ACTION_VIEW`，均无法启动时尝试打开系统安全设置。打开设置不代表已经授权，需用户检查「未知来源」并返回后手动重试；具体厂商 ROM 仍需实机验收。0.1.13 仅尝试 `ACTION_VIEW`，旧版入口失败时可能需手动覆盖安装一次同包名、同签名正式 APK，才能使用新版回退逻辑。
- 相同签名和包名允许覆盖升级并保留数据。签名不匹配时不要通过卸载旧版来强行升级，否则可能丢失本机记录和登录态。
- 仓库和 Release 必须允许电视匿名访问；App 不保存 GitCode 凭据，不支持从私有仓库下载。网络不可达不影响已有播放功能。
- 仍指向上游更新源的旧 APK，或只接受 minSdk 26 的早期 APK，可能需要手动覆盖一次本仓库的 Android 6+ 版本。

本仓库不自动发布 Release；发布由维护者明确执行。在线更新仅使用本仓库，不向上游更新仓库同步。

GitHub 可继续作为源码仓库及备用发布页面；同步 Git 代码不会同步 Release 附件。每次须在 GitCode 上传同一套 APK 和 update.json，并标记 Latest。旧版仍访问 GitHub，需通过旧渠道升级或手动覆盖安装一次新版本，之后才能使用 GitCode 更新。

## 统一发布入口

功能 PR 合入 main 并经用户验收后，日常只需一条命令。无需每次手动指定版本号，也无需人工逐一建版本分支、PR、标签或上传附件：

```sh
# 默认：读取两平台版本、标签／草稿及源码版本，只预演，不提交、构建或发布
python3 scripts/release-tv.py

# 用户明确授权正式发布后：自动执行完整流程
python3 scripts/release-tv.py --publish

# 有意指定版本，或提供人工整理的说明（可选）
python3 scripts/release-tv.py --version 0.2.0 --notes-file /绝对路径/更新说明.md --publish

# 中断后继续同一版本；以实际记录的版本替换示例
python3 scripts/release-tv.py --publish --resume 0.1.7
```

默认版本取源码与两平台正式版本中的最高语义版本，再递增补丁位；versionCode 超过源码及已发布清单的最大值。无清单的历史 Release 会读取实际 APK 元数据，包含历史多 APK 变体，不猜测或忽略内部版本。已有标签或草稿发生冲突时停止；两平台 Latest 不一致或存在未完成的本地任务时停止，使用原任务继续或先排查，不悄悄跳过一个版本。

默认从上一发布标签至当前提交的 TV app/core 路径下 `feat:`、`fix:` 提交标题生成去重说明。没有此类 TV 改动时停止，避免仅因工具或文档改动就发布 APK；确需发布维护版本时可显式提供已审阅的说明文件。发布说明长度仍受 1500 字限制。

`--publish` 要求从干净的 main 开始，先快进同步最新 main。脚本不会 stash、重置或删除用户修改。它自动建 `feature-release-<版本>`，提交中文版本说明并创建／合并发布准备 PR；构建及检查一次运行 `:core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleRelease`，不安装、不操作模拟器。API 23/26 由现有单元测试配置覆盖。与线上最高 versionCode 的正式 APK 核对签名，并检查新包 v1、包名、版本及 minSdk 23；验证后的树必须与合并源码完全相同，main 在检查期间变化会停止，不发布未检查的新改动。

新版本标签在 GitHub 和 GitCode 指向同一份源码，GitCode main 仅允许快进推送。上传、附件摘要校验和设为 Latest 复用 `publish-tv-release.py`；发布后匿名读取两平台清单并与本地比较。只操作 TV 版本标签，不触碰 Mac `mac-stable`。不会强制推送、重写旧标签、删除或覆盖旧草稿和附件。

任务记录和构建日志保存在 `outputs/release-jobs/<版本>/`，发布文件仍在 `outputs/releases/<标签>/`，均不提交。`--resume` 使用记录中的相同版本、源码及文件摘要；已完成且未改变的构建包复用，不重复构建。改变缓存文件、签名不一致或合并树发生变化均停止。版本文件写入阶段若出现不属于脚本预期内容的修改，会保留并停止，需要先检查。网络上传可沿用下层脚本的同内容跳过机制；双平台发布仍不是原子操作。

### 一次配置的本机环境

沿用已有 `~/.config/hongguotv/publish.json` 或 `GITHUB_TOKEN` / `GITCODE_TOKEN`，不要重复运行 `--configure`。统一入口预演也需读取认证列表，以识别 GitHub 草稿；不会将 Token 发给公共下载地址。

工具与签名可继续使用环境变量／工程私密 `keystore.properties`。也支持 `~/.config/hongguotv/release.json`，权限必须为 `0600`，用于一次保存本机路径及原签名引用；环境变量优先。允许键为 `JAVA_HOME`、`ANDROID_HOME`、`GRADLE_USER_HOME`、`HONGGUOTV_GRADLE`（Gradle 可执行路径）和现有四个签名变量 `HONGGUOTV_KEYSTORE`、`HONGGUOTV_KEY_ALIAS`、`HONGGUOTV_STORE_PASSWORD`、`HONGGUOTV_KEY_PASSWORD`。无需全部填写；SDK 还可读取 `kotlin-tv/local.properties` 的 sdk.dir，未指定 Gradle 时使用工程 wrapper。该私密配置不进入仓库或日志；不得生成新签名密钥代替原密钥。

### 验证范围

`python3 scripts/test-release-tv.py` 和 `python3 scripts/test-publish-tv-release.py` 为离线回归，不访问平台或上传。统一入口已于 0.1.7 / 48 完成旧包名的完整真实发布：版本规划、自动检查、原签名与 v1 校验、发布准备 PR、双平台标签、附件上传及 Latest 核验。新包名过渡目前仅通过自动检查，首次新包名正式发布仍需用户明确授权；本轮不为测试工具而发布 APK。

## 双平台附件上传脚本（独立使用）

脚本只发布 TV 的版本标签，不修改 Mac `mac-stable`，不自动升版本、建标签或合并 PR。先按上述流程构建、检查签名，推送对应标签到两平台并核对源码提交一致。GitCode 已关闭自动镜像同步，当前可直接推送 main 和新增版本标签；不要强制推送或改写旧标签。使用 Python 3.9+，无需安装 gh 或第三方 Python 包。

首次在自己的终端执行（不要将 Token 发到聊天或写进命令行）：

```sh
python3 scripts/publish-tv-release.py --configure
```

脚本依次隐藏输入 GitHub 与 GitCode Token，保存到 `~/.config/hongguotv/publish.json`，权限为 `0600`，不在仓库内。此文件为本机明文私密配置，不是加密钥匙串；不应同步或分享。也可通过 `GITHUB_TOKEN` / `GITCODE_TOKEN` 环境变量提供，环境变量优先。GitHub 使用仅授权本仓库、具有 Contents 读写权限的 Token；GitCode 使用具有本仓库发行版及附件读写权限的个人 Token。Token 需要由维护者自行在平台创建，脚本不会申请或扩大权限。

生成文件时指定更新说明，prepare 脚本会自动保存 APK、update.json 和release-notes.md 三个文件：

```sh
python3 scripts/prepare-kotlin-release.py \
  --apk kotlin-tv/app/build/outputs/apk/release/app-release.apk \
  --aapt "$ANDROID_HOME/build-tools/36.0.0/aapt" \
  --output outputs/releases \
  --tag kotlin-v0.1.4 \
  --notes-file kotlin-tv/RELEASE_NOTES.md
```

本地检查（默认模式，不读取凭据、不发起网络请求）：

```sh
python3 scripts/publish-tv-release.py --directory outputs/releases/kotlin-v0.1.4
```

明确发布到两个平台：

```sh
python3 scripts/publish-tv-release.py --directory outputs/releases/kotlin-v0.1.4 --publish
```

脚本校验清单与 APK 的大小、SHA256、包名、minSdk 和说明一致性，然后在 GitHub 建草稿、GitCode 建预发布，上传 APK、说明、最后上传清单。通过服务端 SHA256 或匿名下载重新计算摘要校验附件后，再将两边设为正式 Latest，并检查 Latest 标签。GitCode 没有此脚本使用的草稿 API，上传期间可见预发布；稳定更新不会选取预发布。

两平台发布不是原子操作，任一步失败时终端会非零退出，已上传文件会保留；可重跑同一命令继续。同名相同内容会跳过，不同内容会报错，脚本不删除或替换已有附件。若本标签已经正式上线，补附件也是可见操作；请勿把不同构建复用为同一个正式标签。签名连续性与 APK 内部版本检查仍按前述发布流程执行，单纯 SHA256 校验不代替这些检查。

接口依据：[GitHub Release API](https://docs.github.com/en/rest/releases/releases)、[GitHub 附件 API](https://docs.github.com/en/rest/releases/assets)、[GitCode 创建发行版](https://docs.gitcode.com/docs/apis/post-api-v-5-repos-owner-repo-releases/)、[GitCode 附件上传地址](https://docs.gitcode.com/docs/apis/get-api-v-5-repos-owner-repo-releases-tag-upload-url/)、[GitCode 更新发行版](https://docs.gitcode.com/docs/apis/patch-api-v-5-repos-owner-repo-releases-tag/)。真实写入需配置凭据后由维护者执行；离线测试不会触碰正式 Release。

发布附件使用英文文件名 `release-notes.md`，正文仍为中文，避免平台将全中文附件名归一化为 `default.md`。GitHub 草稿按标签查询可能不可见，脚本会从认证后的发行版列表按精确标签查找，支持中断续传。

### 已完成的实测

TV 0.1.4（versionCode 45）已完成两平台真实发布：草稿／预发布创建、三个附件上传与校验、转正式 Latest，以及中断后继续上传。GitHub 草稿读取和中文附件名问题已在此次实测中修正。当时 GitCode 镜像仓库不接受直接 Git 推送，需手动同步 GitHub 标签。自 0.1.6 发布准备起，维护者已关闭自动同步，实测可直接快进推送 main 及新增标签；两平台标签必须指向同一份已验证源码。脚本不会修改镜像设置。

这次平台 API 流程实测通过，不代替每次新版本的构建、签名与附件校验，也不代表电视端检查更新和覆盖安装已实测；电视安装仍由用户完成。

### 更新包签名兼容性修复（开发分支）

内置更新同时请求旧版证书收集标志与 Android 9+ 签名信息，兼容 Android 9/10 及部分电视系统解析 APK 文件时缺少签名字段的情况。优先比较当前签名，必要时读取旧签名字段；无法读取证书或签名不同仍阻止安装，并分别提示原因。包名、版本、最低系统版本及文件大小/SHA256 校验继续保留，尚待受影响电视实机验收。

已经受旧版校验影响的设备，需要从本仓库官方 Release 手动下载并覆盖安装包含修复的版本一次；无需卸载，保持同包名和原签名可保留本机数据。旧版不会因服务器更新而自动获得此修复。
