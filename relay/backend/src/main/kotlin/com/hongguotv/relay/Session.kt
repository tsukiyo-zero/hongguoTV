// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.relay

import com.hongguotv.core.Mp4SampleTable
import java.io.BufferedOutputStream
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * 单个转码会话：解密取流 → Annex-B → ffmpeg stdin 管道 → HLS（H.264 + AAC copy）。
 * 状态机 RUNNING → COMPLETED/FAILED；seek = 按新起点重启转码进程；stop = 终结并清理。
 * 世代计数用于让旧喂帧/守望线程在新一轮启动后静默退出，避免误报失败。
 */
class Session(
    val id: String,
    val source: VideoSource,
    /** 音频回环服务专用取流通道：与喂帧线程物理隔离，避免共享取流状态的并发污染。 */
    val audioSource: VideoSource,
    private val table: Mp4SampleTable.Table,
    /** 带内扫描的真实随机访问点下标（stss 缺失时由 buildSession 扫描得出）。 */
    private val rapIndices: List<Int>,
    val workDir: Path,
    private val audioUrl: String,
    private val ffmpegPath: String,
    private val encoder: String,
) {
    enum class State { RUNNING, COMPLETED, FAILED, STOPPED }

    @Volatile var state = State.RUNNING
        private set
    @Volatile var failReason: String? = null
        private set
    @Volatile var positionUs = 0L
        private set
    @Volatile var startIdx = 0
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
        workDir.toFile().deleteRecursively()
        workDir.toFile().mkdirs()
        val idx = TranscodeFeed.startIndex(table, (offsetSec * 1_000_000).toLong(), rapIndices)
        startIdx = idx
        positionUs = table.samples.getOrNull(idx)?.dts ?: 0L
        val index = indexFile.toString()
        val command =
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
        val proc =
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(workDir.resolve("ffmpeg.log").toFile()))
                .start()
        process = proc
        state = State.RUNNING
        failReason = null
        val gen = generation.incrementAndGet()
        feedThread =
            thread(isDaemon = true, name = "feed-$id") { feed(proc, idx, gen) }
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
