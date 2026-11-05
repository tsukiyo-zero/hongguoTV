// SPDX-License-Identifier: GPL-3.0-only
// 果映伴机：局域网转码服务。TV 端遇 bytevc2 锁定集时把 stream info 交给本服务，
// 本机完成 VVC 软解 + H.264 转码并以 HLS 供 TV 硬解播放。零登录态、密钥仅存内存。
package com.hongguotv.relay

import com.hongguotv.core.ContentRepository
import okhttp3.OkHttpClient
import java.awt.Dialog
import java.awt.Frame
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URI
import java.nio.file.Paths
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import javax.swing.JOptionPane

const val VERSION = "1.0.0"

private const val IDLE_TIMEOUT_MS = 10 * 60_000L

fun main(args: Array<String>) {
    // 注：不再隐藏 Dock 图标——非极客用户需要「看得见应用在跑」；退出/状态走菜单栏与窗口。

    var ffmpegExplicit: String? = System.getenv("GUOYING_FFMPEG")
    var port = 46521
    var selftest: String? = null
    var index = 0
    while (index < args.size) {
        when (args[index]) {
            "--ffmpeg" -> { ffmpegExplicit = args[index + 1]; index += 2 }
            "--port" -> { port = args[index + 1].toInt(); index += 2 }
            "--selftest" -> { selftest = args[index + 1]; index += 2 }
            "--debugEs" -> { System.setProperty("guoying.debugEs", args[index + 1]); index += 2 }
            "--launchd" -> index += 1 // 开机自启启动：行为与普通启动一致，仅作标记
            else -> error("未知参数 ${args[index]}（支持 --ffmpeg/--port/--selftest/--debugEs/--launchd）")
        }
    }

    // 单实例：已有伴机在跑则唤醒其状态窗口并提示后退出（.command 脚本负责「结束旧的」）。
    val osIsMac = System.getProperty("os.name").orEmpty().contains("Mac", true)
    val osIsWindows = System.getProperty("os.name").orEmpty().contains("Windows", true)
    val binaryName = if (osIsWindows) "ffmpeg.exe" else "ffmpeg"
    if (isAlreadyRunning(port)) {
        runCatching {
            val connection = URI("http://127.0.0.1:$port/show").toURL().openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 800
            connection.readTimeout = 800
            connection.responseCode
            connection.disconnect()
        }
        val message =
            if (osIsWindows) "果映伴机已在任务栏右下角托盘运行，已为你唤出状态窗口（屏幕上的「果映伴机」窗口）。"
            else "果映伴机已在菜单栏运行，已为你唤出状态窗口（屏幕上的「果映伴机」窗口）。"
        println(message)
        showMacDialog(message)
        return
    }

    // 令牌：读取/生成/旧目录迁移全部收敛在 RelayLocations（路径校验在同一函数内可证）。
    val home = Paths.get(System.getProperty("user.home"))
    val (relayToken, tokenFile) =
        RelayLocations.ensureToken(home, System.getenv("APPDATA"), Paths.get("").toAbsolutePath())

    val addresses = RelayLocations.allLanAddresses()
    val publicBase = "http://${addresses.firstOrNull() ?: "127.0.0.1"}:$port"
    val workRoot = Paths.get(System.getProperty("java.io.tmpdir"), "guoying-relay")
    workRoot.toFile().mkdirs()
    workRoot.toFile().listFiles()?.forEach { it.deleteRecursively() } // 清上次残留

    val http =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    val manager = SessionManager(workRoot)

    val appRoot = RelayLocations.appRoot(
        System.getProperty("jpackage.app-dir"),
        ProcessHandle.current().info().command().orElse(null),
    )
    val window = RelayWindow.install()
    val tray =
        RelayTray.install(
            autostartHome = if (osIsMac) home else null, // 自启 plist 为 macOS 专属
            appRoot = appRoot,
            onCopyAddress = { addresses.joinToString("\n") },
            onCopyToken = { relayToken },
            onShowWindow = { window?.showWindow() },
            onAutostartChanged = { enabled ->
                println("开机自启：${if (enabled) "开" else "关"}")
            },
            onExit = {
                manager.shutdown()
                kotlin.system.exitProcess(0)
            },
        )

    // ffmpeg 解析：app 捆绑 → 显式指定/env → 系统 PATH；均无则带错误状态运行（TV 端会回落）。
    val ffmpegResolved =
        RelayLocations.resolveFfmpeg(ffmpegExplicit, appRoot, binaryName, probe = { candidate ->
            runCatching {
                ProcessBuilder(candidate, "-hide_banner", "-h", "decoder=vvc").start().waitFor() == 0
            }.getOrDefault(false)
        })
    val encoder = ffmpegResolved?.let { detectEncoder(it) } ?: ""
    if (ffmpegResolved == null) {
        val message = "未找到带 VVC 解码器的 ffmpeg（需 7+）。已尝试捆绑目录、GUOYING_FFMPEG 与系统 PATH。"
        tray?.status("错误：缺少 ffmpeg", notify = true, error = true)
        window?.update("错误：缺少 ffmpeg", addresses, relayToken)
        window?.showWindow()
        println(message)
        showMacDialog(message)
        // 继续运行：/health 报 ffmpegOk=false，TV 端会自动回落；修复 ffmpeg 后重启伴机即可。
    } else {
        println("ffmpeg：$ffmpegResolved（encoder=$encoder）")
    }

    val server =
        try {
            RelayServer(
                port, manager, publicBase, VERSION, encoder,
                ffmpegResolved != null,
                ffmpegResolved ?: ffmpegExplicit ?: "ffmpeg",
                relayToken,
                { line -> println(line); window?.updateActivity(line) },
            ) { info ->
                RemoteVideoSource(http, info)
            }
        } catch (problem: java.net.BindException) {
            val message = "端口 $port 已被占用：很可能已有一个伴机实例在运行。"
            tray?.status(message, notify = true, error = true)
            println(message)
            showMacDialog(message)
            return
        }
    server.start()
    server.setOnShow {
        window?.showWindow()
        println("状态窗口已唤出（/show）")
    }
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

    tray?.status(
        if (osIsWindows) "运行中 · $publicBase（驻留右下角托盘，Win11 默认收在「^」溢出区，可拖出常显）"
        else "运行中 · $publicBase",
        notify = true,
    )
    window?.update("运行中", addresses, relayToken)
    if (osIsMac) window?.showWindow() // macOS 保留启动弹窗（已验收）；Windows 驻留以托盘为锚，状态窗口从托盘菜单按需打开（AWT Frame 在 Windows 必占任务栏）
    println("果映伴机 $VERSION 已启动：$publicBase（encoder=$encoder）")
    println("伴机令牌：$relayToken（TV 端播放设置需填入同一令牌；文件 $tokenFile）")
    println("mDNS：${if (jmdns != null) "_guoying-relay._tcp 已注册" else "注册失败（TV 端可手动填 IP）"}")
    println("Ctrl-C 退出并清理会话")
    Thread.currentThread().join()
}

private fun isAlreadyRunning(port: Int): Boolean =
    runCatching {
        val connection = URI("http://127.0.0.1:$port/health").toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 600
        connection.readTimeout = 600
        val ok = connection.responseCode == 200
        connection.disconnect()
        ok
    }.getOrDefault(false)

/** .app 双击场景的错误可见化：弹一个系统对话框（终端场景则看日志）。 */
private fun showMacDialog(message: String) {
    runCatching {
        JOptionPane.showMessageDialog(null, message, "果映伴机", JOptionPane.ERROR_MESSAGE)
    }
}

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

private fun registerMdns(port: Int): JmDNS {
    val jmdns = JmDNS.create(InetAddress.getLocalHost(), "guoying-relay")
    jmdns.registerService(
        ServiceInfo.create("_guoying-relay._tcp.local.", "guoying-relay", port, "果映伴机转码"),
    )
    return jmdns
}
