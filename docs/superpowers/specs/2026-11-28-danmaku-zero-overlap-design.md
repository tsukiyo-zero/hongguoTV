# 弹幕严格零重叠几何排程设计

2026-11-28。分支 `fix-danmaku-overlap`（基于 main `e510388`）。

## 背景与问题

用户实测：弹幕密集时多条互相堆叠，叠在一起完全不可读。根源在 `DanmakuLayout.assign`（kotlin-tv/app/src/main/kotlin/com/hongguotv/nativeapp/DanmakuLayout.kt）是纯时间调度，不感知弹幕宽度：

1. 绘制公式 `x = width − (width + 文本宽) × 进度 / travel`（TvSocial.BulletView.onDraw）使滚动速度与文本宽度成正比——长弹幕快、短弹幕慢，同轨道后发的长弹幕会追上前发的短弹幕；
2. 轨道释放用「走过 travel/3 即空闲」近似，长弹幕尾段被后条压上；
3. 拥挤时最多顺延 3 秒后强行进入已占用轨道（MAX_DELAY_MS 钳制），洪峰期整体堆叠。

历史演变：更早版本「轨道占用即丢弃」，一集 259 条丢九成以上，因此演变为允许重叠的现行策略；本设计在不回到大面积丢弃的前提下消除重叠。

## 决策（用户拍板）

- 兜底策略：**严格零重叠**——几何排程保证同轨道任意两条弹幕在屏期间横向区间永不相交；塞不下时丢弃，不强插。
- 顺延上限 3 秒收紧为 2 秒（弹幕时间锚定，等太久失去语境）。
- 丢弃计数接入「弹幕排查」诊断，实测可验证丢弃率。
- 新增 2 行档位（现有最低 4 行）。

## 方案对比

- **A. 精确几何排程（选定）**：调度感知每条弹幕像素宽度与真实速度，逐轨道精确判定入轨时刻。纯函数可单测；取代 1/3 行程与 400ms 近似后容量反而提升。
- B. 调参缓解：保留时间近似、加大间隔。速度差追尾是几何必然，治标不治本，弃。
- C. 全区间仿真：每轨道维护完整区间表逐对相交检测。已证明「同轨道只需检查末条」即可保证无重叠（后条要与更早条重叠必先穿过前条），O(n²) 复杂度白付，弃。

## 几何排程规则

绘制坐标为文本左缘：`x_L(t) = S − (S+W)(t−t₀)/T`，`x_R(t) = x_L(t)+W`（S=画布宽，T=travel）。

同轨道相邻两条 A（先入轨，tA, WA）、B（后，tB, WB），令 `D(t) = x_R,A(t) − x_L,B(t)`（线性于 t），重叠 ⇔ 存在 t 使 `0 ≤ D(t) ≤ WA+WB`：

- **WB ≤ WA**（后条不快于前条）：D 单调不增，安全 ⇔ `tB−tA > WA·T/(S+WA)`（前条完全进入屏幕后再入轨）；
- **WB > WA**（后条更快）：初始重叠 ⇔ `Δ ≤ WA·T/(S+WA)`；追尾重叠 ⇔ `Δ < WB·T/(S+WB)`（追上时刻早于前条完全出屏时刻 tA+T）。因 `f(W)=W·T/(S+W)` 单调增，追尾条件为约束主项。

**统一入轨条件：`tB ≥ tA + max(WA,WB)·T/(S+max(WA,WB))`**——即「两条中较宽者完全进入屏幕所需的时间」必须已被前条消耗。工程上再加 `ENTRY_GAP_MS=120ms` 视觉呼吸间隔，settle 向上取整防截断重叠。

每轨道只需记录末条 (入轨时刻, 宽度)：入轨判定 O(1)，整体 O(n·lanes)。

## 组件设计

### DanmakuLayout（唯一排程核心，纯函数）

- `assign(items, lanes, travelMs, screenWidthPx, widthOf: (String) -> Float, merge = true, maxDelayMs = 2000, entryGapMs = 120): ScheduleResult`
- `ScheduleResult(scheduled: List<ScheduledDanmaku>, droppedCount: Int)`
- 流程：id 去重 → 五秒同文本合并（锚定首次出现时间；×N 按合并后文本实测宽度）→ 逐条选「最早可行入轨时刻」最小的轨道 → 超过 `timeMs+maxDelayMs` 丢弃（droppedCount++），绝不强插。
- `earliestSafeEntryMs(prevStart, prevW, nextW, S, T, gap)` 公开为 internal 供单测直测。
- 删除 `MIN_GAP_MS` 与 travel/3 释放近似；`laneCountFor`/`visibleLanes`（区域高度+字号→轨道数）保持不变。
- `MAX_DELAY_MS` 3000 → 2000。

### TvSocial.BulletView（渲染侧，onDraw 坐标公式一行不改）

- `scheduleRows`：画布已布局（width>0）时以同一 `Paint.measureText` 作 widthOf、画布宽作 S 调 `assign`；记录 `lastDropped`；`onSizeChanged` 重排保留。
- `diagnostics()` 增加丢弃计数：`合并排程 N 条，防重叠丢弃 M 条，当前时间范围内 V 条`。

### 设置档位（TvSocial settings）

- 密度：`2(极少) / 4(少) / 0(标准自动) / 12(多)`；
- 精确行数：`0(自动) / 2 / 4 / 6 / 8 / 10 / 12`。

## 边界

- 超宽弹幕（接近满屏宽）入轨条件天然苛刻，会被较多顺延/丢弃——零重叠的物理必然，诊断计数可观测。
- 画布未布局（width≤0）不排程，待 `onSizeChanged` 再排。
- widthOf 对合并文本实测（×N 更宽，参与几何）。
- 空输入、lanes≤0、travel≤0、S≤0 防御性返回空结果。

## 测试

- **零重叠不变式（核心）**：宽窄混杂密集场景排程后，用绘制公式对同轨道两两做精确区间相交判定（D(t) 线性，取可见窗口两端点值判定与 `[0, WA+WB]` 相交），断言无重叠；
- 洪峰丢弃：同刻 60 条 × 2 轨道 → `scheduled+dropped == 60`、starts ≤ t+2000、无重叠、dropped>0；
- 几何间隔：1 轨道顺序入轨 `Δ ≥ ceil(max(W)·T/(S+max(W))) + gap`；
- 快长追慢短场景：后发长弹幕必须等足安全窗口，不追尾；
- `earliestSafeEntryMs` 公式直测（宽者决定、双向对称、同宽）；
- 合并宽度实测收到「文本 ×N」、合并锚定首次时间、merge=false、laneCountFor/visibleLanes、空输入等回归保留或适配新签名。

## 文档同步

- 主 README 弹幕特性句补「同轨道严格防重叠」；
- kotlin-tv/README.md 弹幕节：「顺延三秒/极高密度允许重叠」改为零重叠 + 2 秒 + 丢弃计数；密度档位补「极少（2 行）」并追加本次修复说明段；
- kotlin-tv/SOCIAL.md：第 7 段调度描述重写、第 9 段行数选项、第 29 段密度档位，并追加本次修复说明段。

## 工程约束

- 提交：中文 Conventional Commits；Mimosa 拦 git commit → 我改文件、用户终端提交。
- 版本：TV versionName/versionCode 不动（0.1.41/82），debug 包文件名区分。
- 验证：`:app:testDebugUnitTest :app:assembleDebug :app:lintDebug`（纯 app Kotlin 改动，core 未动）。
- 不触碰：弹幕读取/签名/分页链路（TimedComments/CommentTransport）、绘制坐标公式、区域/字号/速度换算。
- 成功标准：全部单测与 lint 绿；用户实机验收密集弹幕不叠、2 行档位生效、诊断丢弃数合理。
