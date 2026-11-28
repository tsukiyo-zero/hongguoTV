# 伴机兜底扩展：接管「单档 1080P 竖屏 bytevc1」新供给形态

> **状态（2026-11-28）**：设计定稿，待实施。分支 `feature-relay-portrait-bytevc1`。

2026-11-28。前置调查结论（当日实网探针 + 电视 logcat，证据链见下）：官方**没有**新编码器。变化在**供给形态**：《两块五的工钱，一条命的代价》（series `7702704387797306430`，96 集）EP1–EP3 阶梯**仅一档** `1080P bytevc1 1080×1920`，容器为标准 `hvc1`+`hvcC`（muxer 签名 `ByteVC1-v1.9.4.4-ot`）。该形态同时打穿两条既有兜底：

1. **硬解**：BRAVIA ATV3 竖屏 1088 上限，`1080x1920` 被能力学习全拒（`Skipped 1080P bytevc1: coded 1080x1920 beyond decoder capability`），无低档可降；
2. **伴机**：`relayOpen` 选档硬性要求 `codec in {bytevc2, bvc2}`（MainActivity.kt），新形态无 bytevc2 档 → 「本集没有 bytevc2 档位，伴机无用武之地」。

用户实测：`强制软解` 模式（软件 HEVC 解码器，无 1088 限制）可正常播放——流本身可解，只是硬解播不了、伴机又接不住。

## 目标（验收标准）

伴机开启时，对「无 bytevc2 档、但存在硬解播不了的档」的集（当前形态=单档 1080P 竖屏 bytevc1），伴机兜底能起播 H.264 HLS；老伴机/老剧集行为不变；无伴机或伴机过旧时错误文案如实。

## 1. 选档策略（TV 端，`relayOpen`）

现状：`rawVideoModel(episodeId)` 过滤 `codec in {bytevc2, bvc2}` 取最高档（MainActivity.kt:3742-3746）。

改为两级（抽出纯函数 `RelaySourcePicker` 便于单测）：

1. **首选 bytevc2/bvc2 最高档**（`rawVideoModel`，现行路径不动）——老形态剧集与现行 VVC 管道行为完全不变；
2. 无 bytevc2 时，**取 `streamLadder(episodeId)` 最高档**（`codec` 非 bytevc2/bvc2、url 可用）。用多形态+重试+轮换的真实路径而非再发一次单发请求：2026-11-28 实测 GET 单发（`rawVideoModel`）空响应窗口下 3 个全新身份全空，而 `streamLadder` 同窗口三发三中；
3. 两级均无货 → 报错文案改「伴机没有可转码的档位」（`PlaybackSources.kt` 已知文案列表同步）。

不做能力过滤：能进入伴机兜底本身说明正常路径已失败（能力全拒或解码类错误码 3000..4999），取最高档即可。

## 2. 伴机输入分支（relay 端，按 codec 分派）

`/session` 请求体新增可选 `codec` 字段（缺省按 `bytevc2`，老 TV 客户端对老伴机零影响）：

- **`bytevc2`** → 现行 VVC 管道原样保留：样本表→Annex-B→`ffmpeg -f vvc -r <帧率> -i pipe:0`，音频 `-i audioUrl` copy（Session.kt 现状）。
- **其他（bytevc1=标准 hvc1 MP4）** → 新「**整文件缓存**」分支：
  - VideoSource 顺序读解密流写入 `workDir/input.mp4`（实测 EP1 全集 8.9MB≈507kbps，局域网秒级）；超 512MB 中止并报「源体积超限」（对齐竞品整集下载上限量级，防异常大文件塞满磁盘）；
  - `ffmpeg -ss <offset> -i input.mp4 -map 0:v:0 -map 0:a:0? -c:v <encoder> -b:v <码率> -g 50 -c:a copy → HLS`。文件可 seek：无样本表重组、无 NAL 类型分派、无显式 `-r`（mov demuxer 自读 mdhd）；音频直接取自缓存文件（`0:a:0?` 可选映射，个别档无音轨时不致命），不再需要第二输入；
  - 缓存完成后本地解析 `Mp4SampleTable`（纯 box 解析，零网络）复用 `videoBitrate(table)` 定码率档位；运行中速度经 `ffmpeg -progress <file>` 由 watch 线程读取，保持 `/health` 的 `session.speed` 语义（码率静态、速度动态，两者不冲突）。

比 bytevc2 管道简单一个量级，且短剧单集体积小，整集缓存成本可忽略。

## 3. 版本兼容（/health caps）

- `/health` 新增 `caps: ["bytevc2", "bytevc1", ...]`——按 ffmpeg 实际解码器能力生成，取值直接用供给侧 `codec_type` 词表（vvc 解码器↔`bytevc2`，hevc 解码器↔`bytevc1`）。
- **新 TV + 老伴机**（无 `caps` 字段）→ 视为仅 `["bytevc2"]`：选档落在 bytevc1 时**不发起会话**，面板文案「伴机版本过旧，请升级伴机后重试」——避免盲目起会话把 HEVC 喂给 `-f vvc` 必败。
- 伴机 `relayVersion` 1.0.0 → 1.1.0。TV 本期只出 debug 验收包（版本号不动）；正式发版走 `release-tv.py` 统一入口（不在本设计范围内自动执行）。

## 4. UX 与文案

- `relayOpen` 失败族文案更新：无档 → 「伴机没有可转码的档位」；版本不匹配 → 「伴机版本过旧，请升级伴机后重试」；其余（不可达/占用/缺 ffmpeg）不变。
- 起播成功沿用现有「伴机转码 1080P · 1080×1920」质量标签（`RelayPlayback`）。

## 5. 测试与验收

- 单测：
  - `RelaySourcePicker`：bytevc2 优先／回退 streamLadder 最高档／两级全空／caps 版本判定（老伴机 bytevc1 不发起）；
  - Session 文件模式：假 ffmpeg 脚本断言 `-ss`/`-i input.mp4` 命令形状、caps 字段、`-progress` 解析（沿用既有 SessionTest 假 ffmpeg 方案）；
  - `RelayClient` codec 字段序列化。
- 探针冒烟：`RelayMain --selftest <集id>` 选档逻辑同步两级化（打印所选 codec）→ 用《两块五》EP1（`7702706037836499993`）实流出 HLS，IINA 打开人工确认画面正确。
- 本地检查四任务（`:core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`）+ `:relay:test` 全绿后交用户实机验收（BRAVIA ATV3 + Mac 伴机）。

## 6. 文档同步

- 主 README 播放节：伴机兜底不再要求 bytevc2（描述实际行为）；
- relay/README.md：caps/codec 字段与文件模式说明；
- 播放限制文档如涉及「伴机仅 bytevc2」表述一并更新。

## 边界与不做

- 不动 bytevc2 管道、不动兼容模型请求（issue #56 形态）与既有触发时机（noPlayableQuality / 3000..4999）；
- 端内软解自动兜底（方向 B）本期不做，留作后续；
- `codec_type=0+force_codec=h264` 对该形态的翻转能力当日不可测（GET 形态处空响应窗口，可用形态为 POST），不依赖、不阻塞；
- 既有空响应波动、身份轮换机制不变。
