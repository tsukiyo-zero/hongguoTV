# relay 伴机转码实施计划

2026-11-04。前置：两轨 spec（../specs/2026-11-04-vvc-bytevc2-playback-two-track-design.md）+ 前置探针全绿（14.1x 实时转码，结论见仓库探针提交与记忆）。分支 `feature-relay-transcode`（已含探针代码：relay/ 模块、core 的 rawVideoModel 旁路与 Mp4SampleTable）。

> **M1 状态（2026-11-04 深夜）：已完成并真机验证。** 关键发现两条已固化进实现与记忆：①字节家裸 VVC 流真实 30fps（dts 步进 33333µs），ffmpeg 裸 VVC 默认按 25fps 打戳导致慢放与音画错位，必须以 `-r <样本表 dts 步进>` 显式修正；②该流 stss 存在（34×5s GOP，RAP 类型为 RADL_R），带内 NAL 扫描（IDR/CRA/STSA_R）仅作 stss 缺失后备。验证：首次转码 141.165s 与音频对齐、seek(60s) 产出 81.4s、单测 10 绿、:core:test 绿。剩余见 M2/M3。

> **M2 状态（2026-11-04）：TV 端接入代码完成，单测/lint/构建全绿，待实机验收。** 实现：RelayClient+RelayStore（app 模块，手动 IP 兜底；NSD/mDNS 发现延后至 M3）、`playerFailure` 面板前伴机兜底（覆盖阶梯耗尽与硬解失败两路径，`relayAttempted` 防循环）、`startRelaySource`（HlsMediaSource 复用现有进度/UI 机制）、专用 relay 监听器（ENDED 自动连播走 playEpisode 常规流）、播放设置新增「伴机代解码」项（开关+地址+连接测试）、换集/退出 `relayStopAsync` 清理会话。验收包 `outputs/debug/hongguotv-tv-0.1.19-relay-m2-debug.apk`。seek 映射 MVP 依赖 event playlist 原生增长（14x 转码追帧），`/seek` 接线视验收结果决定是否需要。依赖新增 media3-exoplayer-hls 与 mockwebserver(test)。

## 总体形态

TV（bytevc2 锁定集兜底）→ 伴机服务（Mac/PC，JVM jar + ffmpeg 8）→ 局域网 HLS（H.264+AAC）→ TV 现有 ExoPlayer 硬解播放。伴机零登录态、零取源业务逻辑；TV 传 stream info（URL+密钥），密钥仅存伴机内存。

## M1 伴机服务 MVP（relay/backend）

1. **RelayService**（com.sun.net.httpserver，绑定 0.0.0.0，端口 46521 固定 + mDNS 注册 `_guoying-relay._tcp`）：
   - `GET /health` → `{version, ffmpegOk(vvc 解码器自检), busy}`
   - `POST /session` → body=`{url, key, quality}`（StreamInfo 三字段）→ 建会话 → `{sessionId, hlsUrl}`
   - `POST /session/{id}/seek` → `{offsetSec}` → 重启转码
   - `DELETE /session/{id}` → 清理
2. **Session 状态机**（单活跃会话，占用时新请求 409）：
   - 视频管线：RemoteVideo.prepare → Mp4SampleTable.parse → 按样本表聚合读解密 → 长度前缀→Annex-B → **ffmpeg stdin 管道**（喂帧线程；探针代码直接演化）
   - 音频管线：同一 ffmpeg 进程第二输入 `-i http://127.0.0.1:<loopback>/audio.mp4`（伴机自建 loopback 服务解密后的 MP4，仅 demux AAC，`-c:a copy`）——注意用 `-map 1:a -map 0:v` 选择
   - 输出：`-f hls -hls_time 2 -hls_list_size 0`（事件型 playlist，写临时目录）
   - 编码：优先 `h264_videotoolbox`（macOS），探测失败回退 `libx264 -preset veryfast`；分辨率跟随源（竖屏保持竖屏）
3. **seek**：杀 ffmpeg → 按 `offsetSec × timescale` 在 samples 的 dts/cts 定位 → 回退最近 syncSamples（stss 缺失则回退到样本 0）→ 重启喂帧 → playlist 重置（TV 端重新加载）
4. **生命周期**：stdin 喂完 = 转码完成态；空闲超时（无拉流 10 分钟）自动回收；进程退出全量清理临时目录
5. **错误处理**：取流 403/过期 → 会话报错态，TV 端重新解析后重开会话；ffmpeg 崩溃 → 同上；`/health` 的 ffmpegOk=false 时 TV 不使用伴机
6. **测试**：会话状态机/seek 定位/超时回收用假 ffmpeg 脚本单测；真 ffmpeg 集成冒烟（样本集 7701336636495252504 → 出 HLS → ffprobe 校验 h264+aac）

## M2 TV 端接入（kotlin-tv/app）

1. **RelayClient**（OkHttp）：`discover()`（NsdManager mDNS + 设置页手动 IP 兜底，缓存已配对地址）、`health/session/seek/stop`，全部带超时与降级返回
2. **决策点**：现有 bytevc2 兜底判定处（NoPlayableQuality/探测路径）→ 伴机 health 可用 → `POST /session`（传当集 stream info）→ ExoPlayer 播 hlsUrl；任何一步失败 → 现有兜底 UX（行为向后兼容）
3. **seek 映射**：ExoPlayer seek 目标超出已转码区间 → `/seek` → playlist 重载；音频轨由 HLS 自带（copy），不走双轨
4. **UI/降级**：加载态文案「正在由伴机转码…」；播放中伴机失联 → toast + 回兜底 UX；设置页新增「伴机」项（开关 + IP 手填 + 连接测试）
5. **测试**：RelayClient mock WebServer 单测；决策点单测（bytevc2+伴机在线/离线两分支）

## M3 验收与发布

> **M3 进行中（2026-11-04 深夜）**：已落地——①自动发现：TV 端 RelayDiscovery（NsdManager 扫 `_guoying-relay._tcp`，串行 resolve，5s 超时），伴机侧 jmdns 广播早已就绪，伴机设置对话框新增「搜索伴机」；②扫码填地址：PhoneSearchServer 文案参数化（core，默认文案不变、既有断言不受影响），`TvTools.phoneInput` 增加 inputTitle/pageTitle/pagePrompt/inputPlaceholder 参数（submit 挪至参数末尾），伴机设置对话框新增「扫码输入」（复用二维码+手机网页表单链路）；③画质：码率档位 5M/8M（VVC→H.264 代际损失对齐）；④/health session 增 speed 实时倍率（自适应数据基础）。验收排障实录：CLEARTEXT 策略拦截（NSC base-config 已放行，症状曾伪装成「伴机不可达」）、索尼视频硬件平面冻结（弹幕正常但视频区显示波纹，screencap 证明合成器渲染正常，断电重启恢复，非应用问题）。待做：token 鉴权、性能/网络自适应（speed<1 提示与降档）、README+fat jar 发布。

1. 鉴权补齐：伴机首启生成 token 存文件，TV 设置页输入一次（防局域网滥用，spec 安全项）
2. 用户实机验收（自装 jar + TV debug 包）：播 bytevc2 锁定集、拖进度条、杀伴机进程看降级、关伴机看回退
3. 文档：README 伴机章节（ffmpeg 8 安装、启动命令、防火墙）、两轨 spec 状态回填
4. 发布：fat jar（独立版本号 v0.1.x，不占 TV versionCode），随源仓库 Release 附件分发

## 工程约束

- 检查：M1 `:relay:test`；M2 起加 `:core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`
- 提交：中文 Conventional Commits，Mimosa 拦 git 操作，用户终端提交；验收包按惯例区分文件名、不动正式版本号
- 风险与对策：ffmpeg stdin 喂帧背压（管道满时喂线程阻塞属正常流控，需独立于会话锁）；libx264 回退速度需实测（x86 Mac/PC 上 1x 余量待 M3 验证）；ExoPlayer 对事件型 HLS 的 seek 重载行为需 M2 实测调参
