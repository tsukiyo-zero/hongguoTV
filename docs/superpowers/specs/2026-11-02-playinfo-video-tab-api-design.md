# 播放模型接口切换到官方现行 video_tab 路径

2026-11-02 现场排查（用户反馈 0.1.14 在 VIDAA_TV / Android 14 上报「播放信息接口返回空响应」，同时官方 App 可正常播放）。

## 现象与实测

- 客户端 `POST /novel/player/multi_video_model/v1/` 在 `api5-normal-sinfonlineb`、`api5-normal-hl`、`api5-normal-lf`、`api5-normal-lq`、`api5-normal`、`api3-normal-lf`、`api3-normal-hl`、`api3-normal-lq` 八个域名上全部返回 `HTTP 200` + `Content-Length: 0`。
- 同一时刻、同一身份下下列变体同样为空：无 query / 无 body / GET / POST / 无 Cookie / 完整签名头（`x-gorgon`、`x-ladon`、`x-argus`、`x-khronos`、`x-helios`、`x-medusa`、`x-ss-stub`）/ 带 `x-tt-token` / 带 `ttwid` / `device_level` 1-3 / 官方 camelCase 与 snake_case 两种 body / 浏览器 UA 与 `curl`。
- 网关与路由正常：不存在的子路径返回 `404 Page not found`；同 host 的 `/reading/bookapi/*` 正常返回 JSON（如 `100103 PARAM_INVALID`）。
- 官方 APK（`com.phoenix.read` 6.5.5.32，89 MB，18 个 dex）里 `seriessdk.com.dragon.read.saas.rpc.a.a` 仍保留 `POST /novel/player/*`，但 `com.dragon.read.rpc.rpc.a` 另有 `GET /reading/bookapi/video_tab/multi_video_model/v:version/`，请求体为 `GetVideoModelRequest`（`@SerializedName` 为 snake_case：`video_id`、`video_id_type`、`device_level`、`need_all_video_definition`、`need_mp4_align`、`use_os_player`、`video_platform`、`source`、`from_video_id`）。
- 按该结构请求 `GET /reading/bookapi/video_tab/multi_video_model/v1/`：`code 0`、`data.video_model_data.<video_id>.video_model`、五档 `video_list`（`bytevc2` 360P-720P + `bytevc1` 1080P），`spade_a` 密钥齐全，端到端 `streamLadder` 成功。缺任一参数则 `100103 PARAM_INVALID`。
- 该接口不接受逗号分隔的多集（只回第一个 id），与旧批量接口不同。

结论：旧的 `/novel/player/multi_video_model/v1/` 已对该类请求停止供给（静默空响应），官方客户端现行走 `video_tab` 路径；这是接口迁移，不是网络、身份、签名、UA、版本或设备问题。

## 方案

`ContentRepository.streamLadder` 读取顺序：

1. 先 `tabVideoModel(id, identity)`：`GET /reading/bookapi/video_tab/multi_video_model/v1/`，带完整 snake_case 参数，只试主域名一次。解析 `data.video_model_data[id].video_model`（对象或字符串），要求 `video_list` 非空，否则视为失败返回 null。
2. 失败时回退既有旧接口流程（换路重试、空响应恢复、身份轮换、后两集预取）保持不变，避免把新接口的可用性问题传导成播放失败。

设计取舍：

- 新接口只试一次：失败后旧路径仍有三域名换路重试，重复换路没有收益，且能让最坏情况只多一次请求。
- 新接口成功时不写入 `upcomingPlaybackModels`：该接口一次只接受单集，批量预取继续由旧路径负责；连续切集会各发一次新接口请求。
- 新接口失败不抛 `EmptyPlaybackResponseException`，空响应语义仍由旧路径定义，诊断 detail 与恢复预算判据不变。

## 已验证

- `:core:test`（含新增两条：新接口命中后不再打旧接口；新接口 `100103` 时回退旧接口）、`:app:testDebugUnitTest` 全绿。
- 真实服务端：`streamLadder` 返回 1 档（过滤 `bytevc2` 后仅剩 `bytevc1` 1080P），`stream` 选出 1080P 并带 16 字节密钥。
- 既有空响应/轮换/预取用例通过调整 fixture（新接口统一回 `100103`）保持原语义。

## 已知限制

- 新接口固定给 `bytevc2` 低档 + `bytevc1` 1080P，`device_level` 与 `video_platform` 不影响供给；现有「过滤 bytevc2」策略下只剩 1080P 一档，低端解码器（竖屏 1080x1920 超出能力）可能仍需降档，属既有 bytevc2 问题，另行处理。
- 新接口不支持多集批量，连续切集比旧批量接口多一次请求。
- `video_platform` 仅接受 `1024`（Mixed），其它值返回 `100103`。
