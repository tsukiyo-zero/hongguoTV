# 弹幕严格零重叠几何排程实施计划

2026-11-28。前置：设计 spec（../specs/2026-11-28-danmaku-zero-overlap-design.md）。分支 `fix-danmaku-overlap`（基于 main `e510388`）。几何推导与方案对比记录在 spec，不在计划内重复。

## M1 排程核心重写 + 单测

1. **`DanmakuLayout.kt` 重写**（kotlin-tv/app/src/main/kotlin/com/hongguotv/nativeapp/）：
   - 新增 `ScheduleResult(scheduled, droppedCount)`；`assign` 新签名带 `screenWidthPx` 与 `widthOf: (String) -> Float`，返回 `ScheduleResult`；
   - 新增 `earliestSafeEntryMs(prevStart, prevW, nextW, S, T, gap)`（统一公式 + 120ms 呼吸间隔，ceil 防截断）；
   - 删除 `MIN_GAP_MS` 与 travel/3 释放近似；`MAX_DELAY_MS` 3000→2000；超时丢弃不再钳制强插；
   - 合并逻辑（五秒窗口锚定首次出现时间、id 去重）与 `visibleLanes`/`laneCountFor` 保持不动。
2. **`DanmakuLayoutTest.kt` 重写**：零重叠不变式（绘制公式两端点精确判定）、洪峰丢弃、几何间隔、快长追慢短、`earliestSafeEntryMs` 直测、合并宽度实测、合并锚定/merge=false/空输入/laneCountFor/visibleLanes 回归。旧行为断言（全量排程、MIN_GAP、300ms 窗口）按新语义废除。

## M2 TvSocial 接线 + 档位

1. `BulletView.scheduleRows`：width≤0 不排程；`assign` 传画布宽与 `paint::measureText`（与绘制同一 Paint）；记录 `lastDropped`。
2. `BulletView.diagnostics()` 增加防重叠丢弃计数。
3. 设置档位：密度 `listOf(2, 4, 0, 12)` 加「极少」标签；精确行数 `listOf(0, 2, 4, 6, 8, 10, 12)`。
4. `onDraw` 坐标公式与绘制逻辑零改动。

## M3 文档 + 验证 + 交付

1. 主 README.md 弹幕特性句、kotlin-tv/README.md 弹幕节（顺延/重叠表述、密度档位、追加修复说明段）、kotlin-tv/SOCIAL.md（调度描述、行数选项、追加修复说明段）同步。
2. 本地检查：`./kotlin-tv/gradlew -p kotlin-tv -Porg.gradle.java.installations.paths="$HOME/tools/jdk-17.0.20.1+1/Contents/Home" :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` 全绿。
3. debug 验收包：版本号不动（0.1.41/82），文件名带分支标识，交用户实机验收（密集弹幕不叠、2 行档位、诊断丢弃数）。
4. 用户终端提交（中文 Conventional Commits，Mimosa 约定）：①docs 设计+计划；②fix 排程核心+接线+测试；③docs README/SOCIAL 同步。push 后开 PR 到 main。
5. 用户验收合并后，正式发版另行走 `release-tv.py` 统一入口（不属本计划）。

## M4 迭代 2：实机反馈调优（2026-11-28）

1. DanmakuLayout：`adaptiveDelayMs`（2000+200×lanes≤4000）替代固定 MAX_DELAY_MS（`assign` 的 `maxDelayMs<0` 走自适应）；`laneCountFor` 行高对齐渲染（新增 `lineGapPx` 参数、maxLanes 14）；`ENTRY_GAP_MS` 80。
2. TvSocial：`laneCountFor` 传 `dp(12)`；设置底部加取舍提示行。
3. 测试：新增 `delayCapScalesWithLanes`；burst/dense 顺延断言随自适应放宽；`laneCountFollowsScreenHeight` 改精确断言并补 13 行用例。
4. 文档：README/SOCIAL 顺延描述改「2–4 秒自适应」+ 迭代段；spec/plan 补迭代 2 记录。
5. 全量检查绿后追加提交到 fix-danmaku-overlap（PR #152 自动更新），临时 .debug 后缀重打包装机复测，装后还原。

## 工程约束

- 提交：中文 Conventional Commits；Mimosa 拦 git commit → 我改文件、用户终端提交（沿用既有约定）。
- 版本：TV versionName/versionCode 不动（0.1.41/82），debug 包文件名区分。
- 不触碰：弹幕读取/签名/分页链路（TimedComments/CommentTransport）、绘制坐标公式、区域/字号/速度换算、PR #151（伴机分支）任何文件。
- 成功标准：单测与 lint 全绿；用户实机验收密集弹幕不叠、2 行档位生效、诊断丢弃数合理。
