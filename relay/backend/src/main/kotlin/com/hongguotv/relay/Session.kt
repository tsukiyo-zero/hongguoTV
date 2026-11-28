// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.relay

import com.hongguotv.core.Mp4SampleTable
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * 单个转码会话：解密取流 → HLS（H.264 + AAC copy）。
 * 两种输入模式按供给侧 codec 分派：
 * - bytevc2/bvc2（VVC）：样本表 → Annex-B → ffmpeg stdin 管道（-f vvc），音频走 audioUrl 回环；
 * - 其他（bytevc1=标准 hvc1 MP4）：解密整文件缓存 input.mp4 → ffmpeg 输入级 -ss 直读 demux，
 *   无需样本表重组/NAL 分派/显式帧率，seek=换 -ss 重启（短剧全集体积小，缓存成本可忽略）。
 * 状态机 RUNNING → COMPLETED/FAILED；seek = 按新起点重启转码进程；stop = 终结并清理。
 * 世代计数用于让旧喂帧/守望线程在新一轮启动后静默退出，避免误报失败。
 */
class Session(
    val id: String,
    val source: VideoSource,
    /** 音频回环服务专用取流通道：与喂帧线程物理隔离，避免共享取流状态的并发污染。文件模式不使用。 */
    val audioSource: VideoSource,
    private val table: Mp4SampleTable.Table,
    /** 带内扫描的真实随机访问点下标（stss 缺失时由 buildSession 扫描得出）。文件模式不使用。 */
    private val rapIndices: List<Int>,
    val workDir: Path,
    private val audioUrl: String,
    private val ffmpegPath: String,
    private val encoder: String,
    /** 供给侧编码标签（video_meta.codec_type），决定输入模式。 */
    val codec: String = "bytevc2",
    /** 文件模式缓存上限；默认 [MAX_INPUT_BYTES]，测试注入小值以快速验证超限语义。 */
    private val inputLimitBytes: Long = MAX_INPUT_BYTES,
) {
    enum class State { RUNNING, COMPLETED, FAILED, STOPPED }

    private val fileMode = codec.lowercase() !in setOf("bytevc2", "bvc2")
    private val inputFile = workDir.resolve(INPUT_FILE)
    private val progressFile = workDir.resolve("progress.txt")

    @Volatile var state = State.RUNNING
        private set
    @Volatile var failReason: String? = null
        private set
    @Volatile var positionUs = 0L
        private set
    @Volatile var startIdx = 0
        private set
    @Volatile var startedAtMs = 0L
        private set
    @Volatile var lastAccessMs = System.currentTimeMillis()

    private val generation = AtomicLong(0)
    private val stopping = AtomicBoolean(false)
    private var process: Process? = null
    private var feedThread: Thread? = null

    val alive: Boolean get() = state == State.RUNNING
    val indexFile: Path = workDir.resolve("index.m3u8")

    init {
        start(0.0)
    }

    /** 启动/重启转码；offsetSec 为点播进度目标（微秒换算见 TranscodeFeed.startIndex）。 */
    @Synchronized
    fun start(offsetSec: Double) {
        stopProcess()
        if (fileMode) {
            // 首次起播在会话创建线程上同步下载（与 VVC 路径的 readHeader/RAP 扫描同为秒级阻塞）；
            // seek 直接复用缓存文件，此处即时返回。
            if (!ensureInput()) {
                state = State.FAILED
                failReason = "源体积超限（>${inputLimitBytes / (1 shl 20)}MB），伴机不缓存该集"
                return
            }
            // 保留 input.mp4（seek 复用），仅清理上一轮 HLS/日志/进度产物。
            workDir.toFile().listFiles()
                ?.filter { it.name != INPUT_FILE }
                ?.forEach { it.deleteRecursively() }
        } else {
            workDir.toFile().deleteRecursively()
            workDir.toFile().mkdirs()
        }
        startedAtMs = System.currentTimeMillis()
        val index = indexFile.toString()
        val command =
            if (fileMode) {
                listOf(
                    ffmpegPath, "-hide_banner", "-loglevel", "error", "-nostdin",
                    "-ss", offsetSec.toString(),
                    "-i", inputFile.toString(),
                    "-map", "0:v:0", "-map", "0:a:0?",
                    "-c:v", encoder, "-b:v", TranscodeFeed.videoBitrate(table), "-g", "50",
                    "-c:a", "copy",
                    "-progress", progressFile.toString(),
                    "-f", "hls", "-hls_time", "2", "-hls_list_size", "0",
                    "-hls_playlist_type", "event",
                    "-hls_segment_filename", workDir.resolve("seg%05d.ts").toString(),
                    index,
                )
            } else {
                val idx = TranscodeFeed.startIndex(table, (offsetSec * 1_000_000).toLong(), rapIndices)
                startIdx = idx
                positionUs = table.samples.getOrNull(idx)?.dts ?: 0L
                listOf(
                    ffmpegPath, "-hide_banner", "-loglevel", "error", "-nostdin",
                    "-f", "vvc", "-r", TranscodeFeed.frameRate(table), "-i", "pipe:0",
                    "-ignore_unknown", "-i", audioUrl,
                    "-map", "0:v", "-map", "1:a",
                    "-c:v", encoder, "-b:v", TranscodeFeed.videoBitrate(table), "-g", "50",
                    "-c:a", "copy",
                    "-f", "hls", "-hls_time", "2", "-hls_list_size", "0",
                    "-hls_playlist_type", "event",
                    "-hls_segment_filename", workDir.resolve("seg%05d.ts").toString(),
                    index,
                )
            }
        val proc =
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(workDir.resolve("ffmpeg.log").toFile()))
                .start()
        process = proc
        state = State.RUNNING
        failReason = null
        val gen = generation.incrementAndGet()
        if (fileMode) {
            // 无喂帧线程；进度由 watch 从 -progress 产物解析（out_time_us）。
            positionUs = (offsetSec * 1_000_000).toLong()
        } else {
            feedThread =
                thread(isDaemon = true, name = "feed-$id") { feed(proc, startIdx, gen) }
        }
        thread(isDaemon = true, name = "watch-$id") { watch(proc, gen) }
    }

    /** seek = 按新起点重启转码。 */
    @Synchronized
    fun seek(offsetSec: Double): Boolean {
        if (stopping.get() || state == State.STOPPED) return false
        start(offsetSec)
        return true
    }

    @Synchronized
    fun stop() {
        stopping.set(true)
        state = State.STOPPED
        stopProcess()
        workDir.toFile().deleteRecursively()
    }

    private fun feed(proc: Process, startIndex: Int, gen: Long) {
        val samples = table.samples
        // 调试开关：-Dguoying.debugEs=<路径> 时镜像喂入的 Annex-B，用于核对喂帧完整性。
        val debugEs = System.getProperty("guoying.debugEs")?.let { java.io.FileOutputStream(it) }
        try {
            proc.outputStream.use { raw ->
                val stdin = BufferedOutputStream(raw, 1 shl 20)
                var index = startIndex
                while (index < samples.size && !stopping.get()) {
                    // 聚合 ≤1MB 的连续样本为一次读，减少 range 请求数。
                    val start = samples[index].offset
                    var last = index
                    while (
                        last < samples.size &&
                            samples[last].offset + samples[last].size - start <= 1_000_000
                    )
                        last++
                    if (last == index) last = index + 1
                    val spanEnd = samples[last - 1].offset + samples[last - 1].size
                    val blob = source.read(start, (spanEnd - start).toInt())
                    for (i in index until last) {
                        if (stopping.get()) return
                        val sample = samples[i]
                        val base = (sample.offset - start).toInt()
                        val chunk = blob.copyOfRange(base, base + sample.size)
                        TranscodeFeed.toAnnexB(chunk, stdin)
                        if (debugEs != null) TranscodeFeed.toAnnexB(chunk, debugEs)
                        positionUs = sample.cts
                    }
                    if (debugEs != null) debugEs.flush()
                    stdin.flush()
                    index = last
                }
            }
        } catch (problem: IOException) {
            if (generation.get() != gen || stopping.get()) return
            synchronized(this) {
                if (state != State.RUNNING) return
                state = State.FAILED
                failReason = "喂帧失败：${problem.message?.take(120)}"
            }
            runCatching { proc.destroyForcibly() }
        } finally {
            debugEs?.close()
        }
    }

    private fun watch(proc: Process, gen: Long) {
        // 文件模式：轮询 -progress 产物维持 positionUs（/health speed 的数据源）；文件缺失/未生成时静默。
        // 注意 -progress 产物为追加式（含全部历史 out_time_us 行），必须取最后一个而非第一个。
        while (fileMode && proc.isAlive && !stopping.get()) {
            runCatching {
                progressFile.toFile().takeIf(File::isFile)?.readText()?.let { text ->
                    OUT_TIME.findAll(text).lastOrNull()?.groupValues?.get(1)?.toLongOrNull()
                        ?.let { positionUs = it }
                }
            }
            Thread.sleep(400)
        }
        val code = runCatching { proc.waitFor() }.getOrDefault(-1)
        if (generation.get() != gen || stopping.get()) return
        synchronized(this) {
            if (state != State.RUNNING) return
            if (code == 0) {
                state = State.COMPLETED
            } else {
                state = State.FAILED
                failReason = "ffmpeg 退出码 $code（详见会话目录 ffmpeg.log）"
            }
        }
    }

    /**
     * 确保 input.mp4 在位：长度与 source.total 一致即复用；否则顺序拉全量解密流落盘。
     * 超过 [MAX_INPUT_BYTES] 中止返回 false（会话按 FAILED 处理，不抛异常打断创建链路）。
     */
    private fun ensureInput(): Boolean {
        workDir.toFile().mkdirs()
        val target = inputFile.toFile()
        if (target.isFile && target.length() == source.total) return true
        try {
            var over = false
            java.io.FileOutputStream(target).use { out ->
                var offset = 0L
                while (offset < source.total) {
                    val n = minOf(1 shl 20, (source.total - offset).toInt())
                    val blob = source.read(offset, n)
                    if (blob.isEmpty()) break
                    out.write(blob)
                    offset += blob.size
                    if (offset > inputLimitBytes) {
                        over = true
                        break
                    }
                }
            }
            if (over) {
                target.delete()
                return false
            }
            return target.length() == source.total
        } catch (problem: IOException) {
            target.delete()
            throw problem
        }
    }

    companion object {
        /** 文件模式缓存名；start/stop 与目录清理按此豁免（seek 复用）。 */
        const val INPUT_FILE = "input.mp4"

        /** 整文件缓存上限：短剧全集普遍 8–30MB，512MB 已远超正常形态，防异常大文件塞满磁盘。 */
        const val MAX_INPUT_BYTES: Long = 512L * (1 shl 20)

        /** ffmpeg -progress 产物中的已转码时长（微秒）。 */
        private val OUT_TIME = Regex("out_time_us=(\\d+)")
    }

    private fun stopProcess() {
        val proc = process ?: return
        process = null
        runCatching { proc.outputStream.close() }
        runCatching { proc.destroyForcibly() }
        runCatching { proc.waitFor(2, TimeUnit.SECONDS) }
        feedThread?.let { current -> runCatching { current.join(2_000) } }
        feedThread = null
    }
}
