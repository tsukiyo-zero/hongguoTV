// SPDX-License-Identifier: GPL-3.0-only
// 果映伴机：局域网转码服务。TV 端遇 bytevc2 锁定集时把 stream info 交给本服务，
// 本机完成 VVC 软解 + H.264 转码并以 HLS 供 TV 硬解播放。零登录态、密钥仅存内存。
package com.hongguotv.relay

import com.hongguotv.core.ContentRepository
import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

const val VERSION = "0.1.0-mvp"

private const val IDLE_TIMEOUT_MS = 10 * 60_000L

fun main(args: Array<String>) {
    var ffmpegPath = System.getenv("GUOYING_FFMPEG") ?: "ffmpeg"
    var port = 46521
    var selftest: String? = null
    var index = 0
    while (index < args.size) {
        when (args[index]) {
            "--ffmpeg" -> { ffmpegPath = args[index + 1]; index += 2 }
            "--port" -> { port = args[index + 1].toInt(); index += 2 }
            "--selftest" -> { selftest = args[index + 1]; index += 2 }
            "--debugEs" -> { System.setProperty("guoying.debugEs", args[index + 1]); index += 2 }
            else -> error("未知参数 ${args[index]}（支持 --ffmpeg/--port/--selftest/--debugEs）")
        }
    }
    val ffmpegOk = hasVvcDecoder(ffmpegPath)
    val encoder = detectEncoder(ffmpegPath)
    require(ffmpegOk) { "ffmpeg 缺少 VVC 解码器（需 ffmpeg 7+，本机检查 -h decoder=vvc 失败）" }

    val workRoot = Paths.get(System.getProperty("java.io.tmpdir"), "guoying-relay")
    workRoot.toFile().mkdirs()
    workRoot.toFile().listFiles()?.forEach { it.deleteRecursively() } // 清上次残留

    val http =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    val publicBase = "http://${lanIp()}:$port"
    val manager = SessionManager(workRoot)
    val server =
        RelayServer(port, manager, publicBase, VERSION, encoder, ffmpegOk, ffmpegPath) { info ->
            RemoteVideoSource(http, info)
        }
    server.start()
    val jmdns = runCatching { registerMdns(port) }.getOrNull()

    val scheduler =
        Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "relay-idle").apply { isDaemon = true }
        }
    scheduler.scheduleAtFixedRate(
        { manager.sweepIdle(System.currentTimeMillis(), IDLE_TIMEOUT_MS) },
        60, 60, TimeUnit.SECONDS,
    )
    Runtime.getRuntime().addShutdownHook(
        Thread {
            manager.shutdown()
            server.stop()
            jmdns?.let { runCatching { it.unregisterAllServices(); it.close() } }
        },
    )

    selftest?.let { episode ->
        println("自测模式：解析 $episode …")
        val renditions =
            ContentRepository().rawVideoModel(episode)
                .filter { it.codec in setOf("bytevc2", "bvc2") }
                .sortedByDescending { it.quality }
        require(renditions.isNotEmpty()) { "该集没有 bytevc2 档位" }
        println("bytevc2 档位：" + renditions.joinToString("、") { "${it.quality}P(${it.width}x${it.height})" })
        val session = server.buildSession(renditions.first().toStreamInfo())
        println("自测会话 ${session.id} 已启动：${publicBase}/hls/${session.id}/index.m3u8")
    }

    println("果映伴机 $VERSION 已启动：$publicBase（encoder=$encoder）")
    println("mDNS：${if (jmdns != null) "_guoying-relay._tcp 已注册" else "注册失败（TV 端可手动填 IP）"}")
    println("Ctrl-C 退出并清理会话")
    Thread.currentThread().join()
}

private fun hasVvcDecoder(ffmpeg: String): Boolean =
    runCatching {
        ProcessBuilder(ffmpeg, "-hide_banner", "-h", "decoder=vvc").start().waitFor() == 0
    }.getOrDefault(false)

private fun detectEncoder(ffmpeg: String): String {
    val videoToolbox =
        runCatching {
            ProcessBuilder(
                ffmpeg, "-hide_banner", "-loglevel", "error",
                "-f", "lavfi", "-i", "color=black:s=64x64:d=0.2",
                "-c:v", "h264_videotoolbox", "-f", "null", "-",
            ).start().waitFor() == 0
        }.getOrDefault(false)
    return if (videoToolbox) "h264_videotoolbox" else "libx264"
}

private fun lanIp(): String =
    runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }?.hostAddress
    }.getOrNull() ?: "127.0.0.1"

private fun registerMdns(port: Int): JmDNS {
    val jmdns = JmDNS.create(InetAddress.getLocalHost(), "guoying-relay")
    jmdns.registerService(
        ServiceInfo.create("_guoying-relay._tcp.local.", "guoying-relay", port, "果映伴机转码"),
    )
    return jmdns
}
