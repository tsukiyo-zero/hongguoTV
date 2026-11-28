# 伴机兜底扩展（单档 1080P 竖屏 bytevc1）实施计划

2026-11-28。前置：设计 spec（../specs/2026-11-28-relay-portrait-bytevc1-design.md）。分支 `feature-relay-portrait-bytevc1`（基于 main `e510388`）。背景调查证据（供给形态实测、容器字节、电视故障链）记录在 spec 头部与当日会话，不在计划内重复。

> **状态（2026-11-28）**：M0 已由用户提交（f969e75）；M1/M2/M3 代码与文档全部完成——四任务 + `:relay:test` 全绿；selftest 实流冒烟 EP1 整集 88.8s 约 20s 转完（≈4.4x 实时，progress 追加式读取修正后位置/速度正常）；验收包 `outputs/debug/hongguotv-tv-0.1.41-relay-portrait-bytevc1-debug.apk`（0.1.41/82 未动）。待用户实机验收 + M1/M2/M3 提交与 PR。

## M0 分支与探针落库（用户终端提交，Mimosa 约定）

1. `git switch -c feature-relay-portrait-bytevc1`（未提交改动随分支携带；`.gitignore` 为用户既有改动，不包含在以下任何提交）。
2. 提交①（探针+spec，一笔）：`kotlin-tv/core/src/main/kotlin/com/hongguotv/core/Probe.kt`（`--search-ids`、`--ladder-compat`）+ `kotlin-tv/core/src/main/kotlin/com/hongguotv/core/ContentRepository.kt`（`rawVideoModel(compat)` / `tabVideoModel(compat)`，仅诊断路径）+ 本 spec + 本计划。
   - 标题：`feat: 探针支持搜索ID定位与兼容参数对照，记录单档1080P竖屏bytevc1调查结论`
   - 正文说明：诊断用途、默认关闭（compat=false）、调查结论摘要（无新编码器，供给形态变化）。

## M1 core 选档器 + 伴机输入分支

1. **core：`RelaySourcePicker`**（新文件，纯函数，app 与 relay 共用——relay/backend 已依赖 core）：
   - `pick(raw: List<Rendition>, ladder: List<Rendition>): Rendition?`——bytevc2/bvc2 优先取最高档，否则 ladder 最高档（ladder 本身已无 bytevc2，防御性再过滤），全空返回 null；
   - `capsSupport(caps: JSONArray?, codec: String): Boolean`——null/缺字段视为 `["bytevc2"]`。
   - `:core:test` 单测。
2. **relay：Session 输入模式分派**（Session.kt，现管道 57-93 行区）：
   - `/session` 请求体新增可选 `codec` 字段（RelayServer.kt 会话创建分支解析；缺省 `bytevc2`）；
   - `codec=bytevc2` → 现行 VVC 管道逐字节不动；
   - 其他（`bytevc1` 及未知标准容器）→ 文件模式：VideoSource 顺序读解密流写 `workDir/input.mp4`（512MB 上限，超限 failReason=「源体积超限」）；完成后本地 `Mp4SampleTable` 定码率（`videoBitrate(table)`）；命令 `ffmpeg -ss <offset> -i input.mp4 -map 0:v:0 -map 0:a:0? -c:v <encoder> -b:v <码率> -g 50 -c:a copy -f hls ...`（无第二音频输入、无 `-f vvc`/`-r`）；`-progress <file>` 由 watch 线程解析速度，保持 `/health` 的 `session.speed` 语义；seek=换 `-ss` 重启；stop/workDir 清理覆盖 input.mp4。
3. **relay：/health caps**（RelayServer.kt health JSON）：启动时 `ffmpeg -hide_banner -decoders` 输出一次探测（grep vvc/hevc decoder 行）映射为 `["bytevc2","bytevc1"]` 子集，结果缓存。
4. **relay：`--selftest <集id>` 两级化**（RelayMain.kt:169 现单级过滤）：改用 `RelaySourcePicker.pick(rawVideoModel(id), streamLadder(id).renditions)`，打印所选 `codec` 与档位；据此走对应输入模式起临时会话。
5. **版本**：`relay/gradle.properties` `relayVersion=1.0.0` → `1.1.0`。
6. **单测**：SessionTest 扩展（文件模式命令形状含 `-ss`/`-i input.mp4`、无 `-f vvc`；超限 failReason；caps 解析含老客户端缺字段语义）；Windows runner 跳过行为沿用现状。
7. **冒烟**：`./kotlin-tv/gradlew -p relay :relay:run --args="--selftest 7702706037836499993"`（《两块五》EP1 实流）→ 打印 hlsUrl → IINA 人工确认画面正确（agent 侧仅 curl/health 断言，画面判断归用户）。

## M2 TV 端接入

1. **relayOpen 两级化**（MainActivity.kt:3733-3762）：health caps 读取 → `RelaySourcePicker.pick` → 所选档 `codec !in caps` → `RelayException("伴机版本过旧，请升级伴机后重试")`（不起会话）；两级全空 → `RelayException("伴机没有可转码的档位")`；`createSession` 带 `codec`。
2. **RelayClient.createSession**（RelayClient.kt:102）：加 `codec: String` 参数，JSON 体内新增字段（老伴机忽略未知字段，向后兼容）。
3. **文案清理**（PlaybackSources.kt:51）：移除已不可达的 `"本集没有 bytevc2 档位"` 条目；新文案均以「伴机」开头，命中既有前缀 catch-all，无需新增条目。
4. **单测**：RelayClient codec 字段序列化；caps 判定走 core picker 测试，app 侧补 relayOpen 协作路径能测的纯逻辑。
5. **本地四任务**：`:core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` + `:relay:test` 全绿（AGENTS 约定，推送前跑 CI 同款）。
6. **debug 验收包**：版本号不动（0.1.41/82），文件名按惯例带分支标识，交用户实机验收（BRAVIA ATV3 + Mac 伴机，播《两块五》EP1）。

## M3 文档 + PR

1. 主 README 播放节、relay/README.md（caps/codec 字段、文件模式）、播放限制相关表述（「伴机仅 bytevc2」旧说法）同步。
2. 用户终端提交 M1/M2/M3 各笔（中文 Conventional Commits）+ push（fork）→ 源仓库开 PR → 用户验收后合并。
3. 合并后人工挂伴机 1.1.0 dmg/msi 到 Release（CI 只产制品不发布，AGENTS 约定）；TV 正式发版走 `release-tv.py` 统一入口，另行触发。

## 工程约束

- 提交：中文 Conventional Commits；Mimosa 拦 git commit → 我改文件、用户终端提交（沿用既有约定）。
- 版本：TV versionName/versionCode 不动（0.1.41/82），debug 包文件名区分；伴机 1.1.0。
- 不触碰：bytevc2 VVC 管道、兼容模型请求路径、空响应/身份轮换机制、触发时机（noPlayableQuality / exo 3000..4999）。
- 成功标准：`--selftest` EP1 出可播 HLS；全部单测与四任务绿；用户实机验收伴机起播《两块五》且老剧（bytevc2 形态）行为不回归。
