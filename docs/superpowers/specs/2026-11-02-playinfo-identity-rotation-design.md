# 播放信息空响应的身份轮换自愈

日期：2026-11-02
前置：docs/superpowers/specs/2026-10-31-playinfo-empty-recovery-design.md（0.1.13 换路重试 + 两段式恢复已发布）；docs/superpowers/specs/2026-11-01-slow-recovery-visibility-design.md（附录「后续方向」立项本设计）。
分支：fix-playinfo-identity-rotation（从 main=b6e018d 拉出，与 fix-slow-recovery-visibility 零文件重叠，可独立合入）。

## 背景

2026-11-01 21:00–21:47 服务端空响应事件（详见 11-01 规格附录）实测结论：

1. 空响应事件**按设备身份选择性屏蔽**：被屏蔽身份在全部三个域名、池化与新建连接下 100% 空，贯穿事件全程（实测身份 A 被屏蔽 ≥45 分钟，2310/2310 发全空）；未屏蔽身份同刻完全正常；受影响子集随事件推进扩大。
2. 0.1.13 的换 host + 强制新连接对被屏蔽身份**完全无效**（推翻 10-31「与身份无关」结论——当时失败身份几分钟内解封，观察窗口太短）。
3. **全新身份三次即时逃逸成功**（事件中 A'、B/C 及事件后终检）。
4. 对被屏蔽用户，现有 8 次恢复预算（约 6 分钟）必然耗尽出终局文案，只能等事件结束手动重试——与全部真实用户反馈吻合。

用户决策（2026-11-02）：连续两轮换路全空后轮换身份；每连续失败期至多轮换一次；新身份仍失败回退现有慢段。

## 目标行为

1. streamLadder 的空响应重试在**连续两整轮换路全空**（8 发跨 3 域名 + 新连接全空，约 25 秒）后、下一轮开始前，重新注册一次匿名设备身份；该轮及后续轮用新身份重试。被屏蔽用户的恢复时间从「预算耗尽（约 6 分钟出终局）」变为约 25–35 秒。
2. **同一连续失败期至多轮换一次**：任何一次成功（拿到有效播放信息）即复位计数与标记；轮换尝试本身失败（注册接口故障）也不再重试，保留原身份走慢段。
3. 轮换后仍全空 → 照旧进入上层 RecoveryBudget 慢段（8 次预算零改动），兜底理论上的全局窗口场景。
4. 注册流程零新增：沿用 PlaybackDeviceSession 既有注册 API 与校验，不做指纹伪造；账号登录态零接触（注册本就走无 Cookie 独立 transport）。
5. 诊断 detail 携带轮换标记（`rotated=1`），用户截图即可看出「已换身份仍失败」。
6. 试播与正式播放共用 streamLadder，自动同时覆盖。

## 组件与改动（全部在 core，app 零改动）

- **PlaybackDeviceSession**：
  - `cdid` 改 `var`；抽出私有 `toProfile(device)` 供 `profile()` 与轮换共用；抽出注册结果校验。
  - 新增 `@Synchronized internal fun rotate(http: OkHttpClient): Boolean`：立即重新注册一次；成功则替换内存身份（cdid 换新 = 全新身份）并返回 true；注册失败/中断/结果非法则**保留原身份**返回 false。中断时恢复中断标志。
- **ContentRepository**：
  - 构造参数末尾新增 `private val sleep: (Long) -> Unit = Thread::sleep`（与既有 clock 注入风格一致），重试循环的 `Thread.sleep` 改经它调用——轮换测试无需真实退避等待。
  - 新增身份自愈状态（专用锁对象保护，prefetch/试播/正式播放可能并发）：`emptyPlaybackRounds`（连续整轮空响应计数）、`emptyPlaybackRotated`（本失败期是否已轮换）。
  - streamLadder 在取 identity 之前判定：计数 ≥2 且未轮换 → `playbackSession.rotate(http)`，无论成败置 rotated 标记。
  - 循环耗尽且为空响应：计数 +1，detail 追加 ` rotated=1`（本失败期已轮换时）；**成功：计数与标记全部复位**；其他 IOException（超时/5xx）不改计数不复位。

## 错误处理

- rotate 内部吞掉注册异常返回 false（恢复流程不为轮换引入新失败路径）；仅当轮换成功后续轮仍空时，detail 带标记，最终行为与现状一致。
- MemoryCookies 无需清理：playinfo 请求显式携带 Profile Cookie 头，OkHttp 有显式 Cookie 头时不再叠加罐内 Cookie；新身份的 store-region 等随注册结果更新。
- 取消/中断语义不变：rotate 收到中断仅放弃轮换并恢复标志位。
- 并发：prefetch 与恢复同时计数时，锁保证状态一致；轮换触发天然幂等（rotated 标记）。

## 测试

- core 单测（免真实等待，sleep 注入为空操作）：
  - `PlaybackDeviceSession`：rotate 成功 → register 计数 +1、`profile()` 不再注册、身份与 cdid 变化；rotate 失败 → 保留原身份、后续 `profile()` 不再注册；初始未注册时 rotate 独立可用。
  - streamLadder 集成：连续三轮全空 → 第 3 轮恰好轮换一次（register 共 2 次）、第 1/2 轮 detail 无标记、第 3 轮起 detail 带 `rotated=1`、第 4 轮不再轮换；中途成功复位后再次两轮全空可再次轮换。
- 全套：`:core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`（app 无改动，跑全套为回归保障）。
- 真机验收（用户执行）：断网 + 代理拦空响应制造屏蔽场景，观察第 3 次尝试起恢复、诊断行 `rotated=1`、终局与恢复成功行为不变。

## 风险与回退

- 服务端观感：每连续失败期每用户至多 1 次注册，走官方流程，可忽略；不做指纹对抗。
- 全局短窗口场景多花一次注册（约 1–2 秒）。
- 回退 = revert 单个 PR；debug 期间 versionName/versionCode 不变，正式发版走统一发布入口。

## 不做的事

- 不改 RecoveryBudget、慢段节奏与恢复文案（11-01 规格已定）。
- 不做身份跨进程持久化迁移（现状：进程内存缓存，重启自然重注册——这也是「重启后恢复」的组成部分）。
- 不动账号登录态、不做指纹伪造或对抗、不给试播单独开路径。
- 不为非空响应错误（超时/5xx/HTTP 错误）轮换——身份屏蔽的实测特征是「全空」，其他故障换路重试已覆盖。
