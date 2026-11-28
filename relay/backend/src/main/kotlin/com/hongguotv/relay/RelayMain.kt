// SPDX-License-Identifier: GPL-3.0-only
// 果映伴机：局域网转码服务。TV 端硬解播不了（bytevc2 锁定集、单档 1080P 竖屏 bytevc1 等
// 供给形态）时把 stream info 交给本服务，本机完成 VVC/HEVC 软解 + H.264 转码并以 HLS 供
// TV 硬解播放。零登录态、密钥仅存内存。
package com.hongguotv.relay

import com.hongguotv.core.ContentRepository
import com.hongguotv.core.RelaySourcePicker
import okhttp3.OkHttpClient
import java.awt.Dialog
import java.awt.EventQueue
import java.awt.Frame
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import javax.swing.JOptionPane
import kotlin.concurrent.thread

const val VERSION = "1.1.0"

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
    // 自动升级：托盘菜单「检查更新」经此槽转发（checkUpdates 在 tray 之后定义）。
    val manualCheck = java.util.concurrent.atomic.AtomicReference<(() -> Unit)?>(null)
    val tray =
        RelayTray.install(
            autostartHome = if (osIsMac) home else null, // 自启 plist 为 macOS 专属
            appRoot = appRoot,
            onCopyAddress = { addresses.joinToString("\n") },
            onCopyToken = { relayToken },
            onShowWindow = { window?.showWindow() },
            onCheckUpdates = { manualCheck.get()?.invoke() },
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
    // caps：供给侧 codec_type 词表（vvc 解码器↔bytevc2，hevc 解码器↔bytevc1），TV 端据此
    // 判断该集能否交给本伴机（缺探测失败/无 ffmpeg → 空，TV 端回落错误面板）。
    val caps = ffmpegResolved?.let { detectCaps(it) } ?: emptyList()
    println("caps：${if (caps.isEmpty()) "无（ffmpeg 能力探测失败）" else caps.joinToString("、")}")
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
                caps,
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

    // 自动升级接线：启动 8 秒后静默检查，之后每 24 小时一次；失败静默（手动检查才提示）。
    // 清单基底可被环境变量 GUOYING_UPDATE_BASE 或系统属性 guoying.updateBase 覆盖（开发/演练）。
    val updateBase =
        System.getenv("GUOYING_UPDATE_BASE")?.takeIf { it.isNotBlank() }
            ?: System.getProperty("guoying.updateBase")
            ?: "https://github.com/WingkySky/hongguoTV/releases/download/relay-stable/"
    val updater = RelayUpdater(http, updateBase, VERSION, RelayLocations.updatesDir(home, System.getenv("APPDATA")))
    fun updateStatus(text: String, notify: Boolean = false, error: Boolean = false) {
        println(text)
        tray?.status(text, notify, error)
        window?.updateActivity(text)
    }

    /** 确认对话框（EDT）；headless 等异常场景按「下次再说」处理。 */
    fun confirmUpgrade(message: String): Boolean {
        var proceed = false
        runCatching {
            EventQueue.invokeAndWait {
                proceed =
                    JOptionPane.showOptionDialog(
                        null, message, "果映伴机升级",
                        JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null,
                        arrayOf("立即升级", "下次再说"), "立即升级",
                    ) == 0
            }
        }
        return proceed
    }

    fun reveal(file: Path) {
        runCatching { ProcessBuilder(RelayUpdater.revealCommand(file)).start() }
    }

    /** 现有 .app 包目录（appRoot=Contents，向上一层）；fat jar/非标准布局返回 null（不自动替换）。 */
    fun appBundleTarget(): Path? {
        if (!RelayUpdater.IS_WINDOWS) {
            val bundle = appRoot?.parent ?: return null
            return bundle.takeIf { it.fileName.toString().endsWith(".app") }
        }
        return null // Windows 走 msi 向导，无需包目录
    }

    fun checkUpdates(manual: Boolean) {
        thread(isDaemon = true, name = "update-check") {
            try {
                val manifest = updater.check() ?: run {
                    if (manual) updateStatus("已检查可用来源，未发现新版 · 当前 $VERSION")
                    return@thread
                }
                updateStatus("发现新版 ${manifest.version}，正在后台下载…", notify = true)
                val file = updater.download(manifest) { percent ->
                    updateStatus("正在下载 ${manifest.version} · $percent%")
                }
                updateStatus("${manifest.version} 已就绪，待确认升级", notify = true)
                val proceed =
                    confirmUpgrade(
                        "发现伴机新版 ${manifest.version}\n${manifest.notes.take(120)}\n\n" +
                            "立即升级将退出伴机、替换应用并自动重启（正在进行的转码会结束）。",
                    )
                if (!proceed) {
                    updateStatus("已下载 ${manifest.version}，可在菜单「检查更新」再次确认升级")
                    return@thread
                }
                tray?.setUpgrading(true)
                updateStatus("正在升级到 ${manifest.version}…")
                manager.shutdown()
                if (RelayUpdater.IS_WINDOWS) {
                    ProcessBuilder(RelayUpdater.winInstallCommand(file.toString())).start()
                    println("已启动安装向导，伴机退出（安装器接管）")
                } else {
                    val bundle = appBundleTarget()
                    if (bundle == null) {
                        reveal(file)
                        tray?.setUpgrading(false)
                        updateStatus(
                            "未能定位伴机安装位置（开发启动模式），请在打开的窗口中手动安装 ${manifest.version}",
                            error = true,
                        )
                        return@thread
                    }
                    val script = updater.updatesDir.resolve("install.sh")
                    script.toFile().writeText(RelayUpdater.macInstallScript(file.toString(), bundle.toString()))
                    val proc = ProcessBuilder("sh", script.toString()).start()
                    val quickFailed = proc.waitFor(4, TimeUnit.SECONDS) && proc.exitValue() != 0
                    if (quickFailed) {
                        tray?.setUpgrading(false)
                        reveal(file)
                        updateStatus(
                            "自动升级失败（安装脚本退出码 ${proc.exitValue()}），已保留安装包可手动安装",
                            error = true,
                        )
                        return@thread
                    }
                    println("自动升级脚本执行中，伴机退出")
                }
                kotlin.system.exitProcess(0)
            } catch (problem: Exception) {
                if (manual) updateStatus("检查更新失败：${problem.message?.take(80)}", error = true)
                else println("检查更新失败（静默）：${problem.message?.take(120)}")
            }
        }
    }
    manualCheck.set { checkUpdates(manual = true) }
    scheduler.scheduleAtFixedRate(
        { checkUpdates(manual = false) },
        8, 24 * 3600, TimeUnit.SECONDS,
    )

    selftest?.let { episode ->
        println("自测模式：解析 $episode …")
        val repo = ContentRepository()
        // 两级选档与 TV 端 relayOpen 同策略：bytevc2 优先，否则 ladder 最高档（新供给形态兜底）。
        val picked =
            RelaySourcePicker.pick(
                repo.rawVideoModel(episode),
                runCatching { repo.streamLadder(episode).renditions }.getOrDefault(emptyList()),
            )
        requireNotNull(picked) { "该集没有可转码的档位" }
        println(
            "所选档位：${picked.quality}P ${picked.codec}（${picked.width}x${picked.height}）",
        )
        val session = server.buildSession(picked.toStreamInfo(), picked.codec)
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

/** ffmpeg 解码器能力 → 供给侧 codec_type 词表子集（vvc↔bytevc2，hevc↔bytevc1）。 */
private fun detectCaps(ffmpeg: String): List<String> =
    runCatching {
        val process =
            ProcessBuilder(ffmpeg, "-hide_banner", "-decoders")
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        buildList {
            if (Regex("\\bvvc\\b").containsMatchIn(output)) add("bytevc2")
            if (Regex("\\bhevc\\b").containsMatchIn(output)) add("bytevc1")
        }
    }.getOrDefault(emptyList())

private fun registerMdns(port: Int): JmDNS {
    val jmdns = JmDNS.create(InetAddress.getLocalHost(), "guoying-relay")
    jmdns.registerService(
        ServiceInfo.create("_guoying-relay._tcp.local.", "guoying-relay", port, "果映伴机转码"),
    )
    return jmdns
}
