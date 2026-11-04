# relay 伴机转码实施计划

2026-11-04。前置：两轨 spec（../specs/2026-11-04-vvc-bytevc2-playback-two-track-design.md）+ 前置探针全绿（14.1x 实时转码，结论见仓库探针提交与记忆）。分支 `feature-relay-transcode`（已含探针代码：relay/ 模块、core 的 rawVideoModel 旁路与 Mp4SampleTable）。

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

1. 鉴权补齐：伴机首启生成 token 存文件，TV 设置页输入一次（防局域网滥用，spec 安全项）
2. 用户实机验收（自装 jar + TV debug 包）：播 bytevc2 锁定集、拖进度条、杀伴机进程看降级、关伴机看回退
3. 文档：README 伴机章节（ffmpeg 8 安装、启动命令、防火墙）、两轨 spec 状态回填
4. 发布：fat jar（独立版本号 v0.1.x，不占 TV versionCode），随源仓库 Release 附件分发

## 工程约束

- 检查：M1 `:relay:test`；M2 起加 `:core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`
- 提交：中文 Conventional Commits，Mimosa 拦 git 操作，用户终端提交；验收包按惯例区分文件名、不动正式版本号
- 风险与对策：ffmpeg stdin 喂帧背压（管道满时喂线程阻塞属正常流控，需独立于会话锁）；libx264 回退速度需实测（x86 Mac/PC 上 1x 余量待 M3 验证）；ExoPlayer 对事件型 HLS 的 seek 重载行为需 M2 实测调参
