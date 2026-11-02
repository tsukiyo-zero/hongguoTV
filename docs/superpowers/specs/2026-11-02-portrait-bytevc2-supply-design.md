# 竖屏 bytevc2 供给按集锁定：试播自愈迭代、确定性报错与试播后移换集

2026-11-02。前置：TV 0.1.15（PR #46）把播放模型切到官方现行 `GET /reading/bookapi/video_tab/multi_video_model/v1/` 后，用户实测竖屏视频无法播放：全集报「没有电视可播放的清晰度」且无降档按钮、自动恢复空转 8 次；试播报「暂时无法加载 · 该作品暂时没有可试播的剧集」。

## 探针实测（2026-11-02，Probe.kt 新增 --tab-matrix / --tab-fallback / --tab-episodes / --tab-ua）

- **编码供给按「集」锁定两类**，与请求参数无关：
  - **bytevc1 类**：360P–1080P 五档全 bytevc1。竖屏 360×640 / 480×854 / 540×960 均 ≤1088 可解，自动降档后正常播放。
  - **bytevc2 类**：低档（360P–720P）全 bytevc2 + bytevc1 1080P。bytevc2 被 Android 提取器过滤（读不出视频轨）后仅剩 1080P（1080×1920），竖屏高度超出解码上限（约 1088、宽高独立校验）→ 降无可降。
- **同剧内交错分布**（7 集样本 3 集 bytevc2）：《一念沉沦，霍总心动了》EP1 bytevc2、EP2–EP4 bytevc1；《王妈在顾家当保姆后全家只会搞笑了》EP1/EP2 bytevc1、EP3 bytevc2。
- **请求侧无解**：12 种参数变体（`use_os_player`、`need_mp4_align`、`need_all_video_definition`、`device_level` 0/1/3、`source` 1/2、`video_id_type` 1、`from_video_id`）无一改变供给；模型内 `fallback_api` 遗留端点返回与主响应完全一致的档位；全新设备身份、旧版本 UA（65532 / 62117）均无影响；旧批量路径 bookapi 前缀 404。
- 横屏不受影响（bytevc2 集走 1080P bytevc1 = 1920×1080，恰在能力内）；Mac 客户端无 1088 限制，不受影响。
- 「自动重试 8 次」来自 `RecoveryBudget`（core `Watching.kt`，3 快 + 5 慢共 8 档）对确定性失败的无效重试：`MainActivity.kt:2927` 把无可解码档当普通 `IOException` 抛出，被判为可自动恢复。

结论：bytevc2 供给的集在电视竖屏上当前无解（客户端无法要求服务端改发编码）；能做的是把可播集用起来、把不可播集说清楚、不空转。

## 设计（全部位于 kotlin-tv/app 与 core/Probe.kt；core 选流与 Mac 行为不变）

### 1. 确定性无解异常

`PlaybackSources.kt` 新增 `NoPlayableQualityException`。`openStream` 在「`ladder.renditions` 非空但 `playbackOrder(maxQuality, supportsRendition)` 为空」时抛出（现状静默返回 null，调用方只能给误导性文案）：

- 所有档被 `supportsRendition`（设备能力/经验表）拒绝 → 抛该异常，属确定性失败。
- 有档通过能力但全部超出 `maxQuality` 画质上限 → 维持现状语义（上限内提示/兼容资源），不抛该异常。

调用方适配：

- `MainActivity` 播放路径（2927/3229 两处 null-coalesce）：收到该异常显示「本集低档为 VVC（bytevc2）编码，电视无法解码，可尝试其他集数」，不再使用通用「没有电视可播放的清晰度」。
- `FeedPlayback.open`：透传该异常，试播面板显示同样文案；「该作品暂时没有可试播的剧集」仅保留给真正无分集的场景。

### 2. 停止确定性空转

`MainActivity.playerFailure`：失败原因为 `NoPlayableQualityException` 时不进入 `scheduleRecovery`（跳过 8 档恢复预算），立即出面板；「立即重试」「返回选集」按钮保留（服务端转码推进后 bytevc2 供给可能翻转为 bytevc1，不拉黑名单）；不显示「尝试 NP」按钮（`nextLowerQuality` 本就返回 null）。试播面板同样不自动重试。

### 3. 试播自愈迭代化（"自动修复直到可看"）

`HomeFeedScreen.selfHealTrial` 由每剧一次改为**最多 3 轮迭代**：

- 每轮条件：解码类错误（沿用现有错误码区间）+ `learnFailure` 学到**新键**（用 `CodecCapabilities.learnedCount()` 增量判定，避免同一键反复重载）+ 未达上限。
- 每轮动作：`learnFailure` + `loadCurrent` 重载（复用既有缓存清理语义）。
- 效果：新设备首播 bytevc1 集 1080P→（720P）→540P 两三轮收敛可播，覆盖经验表为空/部分命中的场景；bytevc2 集学完 1080P 键后，重载即落入第 1 块的确定性报错，快速终止。

### 4. 试播后移换集兜底

触发：试播打开第一集得到 `NoPlayableQualityException`。

- 取 detail 分集列表，顺序探 EP2、EP3（每集一次 `streamLadder` 请求，用与 `openStream` 相同的能力判定），选中首个含可解码档的集作为试播集。
- `TrialClip` 携带实际试播集序号（默认 1，不破坏现有构造与测试替身），`HomeFeedScreen` 文案相应显示「第一集暂不支持电视播放 · 试播第 N 集 · 共 M 集」；序号 1 时维持「第一集试播 · 共 M 集」。
- EP1–EP3 连续三集均无可解码档 → 显示「该剧暂无电视可解码的清晰度」。
- 预取/缓存路径（`prepare(detail)`、`trial(series)`）走同一兜底入口，保证缓存与预取的 clip 均可播。
- 不做黑名单：每次试播现查，供给翻转后自然恢复。换集兜底最多引入 2 次额外请求，仅发生在失败路径。

## 错误处理

- 换集探测中网络/接口异常沿用 `streamLadder` 既有语义，中止兜底并显示明确错误，不无限探测。
- 自愈与换集都只发生在失败路径，正常路径请求数不变。

## 测试

- app 单测：
  - 自愈迭代判定：新键继续 / 旧键停止 / 达上限停止。
  - `openStream`：bytevc2-only 阶梯 + 能力全拒 → `NoPlayableQualityException`；画质上限截断不抛该异常。
  - 试播兜底：EP1 不可解、EP2 可解 → 选中 EP2 且 `TrialClip` 标注序号、文案正确；EP1–EP3 全不可解 → 明确错误。
  - `playerFailure`：`NoPlayableQualityException` 不进恢复预算。
- 检查命令：`:core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` 全绿。
- 真机验收（用户执行）：试播《一念沉沦，霍总心动了》（EP1 bytevc2）自动落到 EP2/EP3 且文案标注；全集播放 bytevc2 集立即出明确文案、无 8 次空转；bytevc1 剧集竖屏播放回归正常（自动落 540P/480P）。

## README 同步

kotlin-tv/README.md 的限制章节：补充「部分集数低档为 bytevc2（VVC）编码，电视竖屏无法解码，试播会自动后移到最近可播集并标注；全集播放该类集数会提示尝试其他集数」。旧接口迁移章节补充按集锁定的探针结论。

## 已知限制

- bytevc2 集在电视竖屏上暂无可行解码方案（VVC 硬解需新型芯片，软解超出电视盒算力）；当前设计是供给不可改变前提下的最优体验。
- 试播兜底会以非第一集内容作为试播画面，文案已明示。
- 供给翻转无通知：bytevc2 集何时变为 bytevc1 由服务端转码进度决定，客户端每次现查。
