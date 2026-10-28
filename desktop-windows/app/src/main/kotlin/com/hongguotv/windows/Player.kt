// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.windows

import java.io.File
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent

/** Locates the VLC runtime that ships next to the executable (or a local install). */
object VlcRuntime {
    @Volatile var directory: File? = null
        private set
    @Volatile var error: String? = null
        private set

    private var configuredPath: String? = null

    @Synchronized
    fun prepare(configured: String): Boolean {
        val directory = directory
        if (directory != null && configured == configuredPath) return true
        configuredPath = configured
        val found = locate(configured)
        if (found == null) {
            error =
                "未找到 VLC 运行库。请重新解压完整客户端，或在设置中指定包含 libvlc.dll 的 VLC 目录。"
            this.directory = null
            return false
        }
        // JNA resolves libvlc.dll from this path; libvlc then loads its plugins from
        // <dir>/plugins (also passed explicitly below).
        System.setProperty("jna.library.path", found.absolutePath)
        this.directory = found
        error = null
        return true
    }

    fun options(): Array<String> {
        val plugins = directory?.let { File(it, "plugins") }
        val values = mutableListOf("--no-video-title-show")
        if (plugins != null && plugins.isDirectory) values += "--plugin-path=${plugins.absolutePath}"
        return values.toTypedArray()
    }

    private fun locate(configured: String): File? {
        val candidates =
            buildList {
                configured.takeIf { it.isNotBlank() }?.let { add(File(it)) }
                System.getenv("VLC_HOME")?.takeIf { it.isNotBlank() }?.let { add(File(it)) }
                Bridge.appRoot?.let { add(File(it, "vlc")) }
                add(File(System.getProperty("user.dir"), "vlc"))
                val programFiles = System.getenv("ProgramFiles")
                val programFilesX86 = System.getenv("ProgramFiles(x86)")
                if (programFiles != null) add(File(programFiles, "VideoLAN/VLC"))
                if (programFilesX86 != null) add(File(programFilesX86, "VideoLAN/VLC"))
            }
        return candidates.firstOrNull { dir ->
            File(dir, "libvlc.dll").isFile && File(dir, "libvlccore.dll").isFile
        }
    }
}

/**
 * One VLC media player for the window. libVLC renders through a heavyweight surface, so
 * Compose controls live below the video instead of on top of it.
 */
class PlaybackController(
    private val preferences: Preferences,
    private val onFinished: () -> Unit,
    private val onError: (String) -> Unit,
) {
    private var component: EmbeddedMediaPlayerComponent? = null
    private var failure: String? = null

    val errorMessage: String? get() = failure

    @Synchronized
    fun ensure(): EmbeddedMediaPlayerComponent? {
        component?.let { return it }
        failure?.let { return null }
        if (!VlcRuntime.prepare(preferences.vlcPath)) {
            failure = VlcRuntime.error
            return null
        }
        return try {
            val created = EmbeddedMediaPlayerComponent(*VlcRuntime.options())
            created.mediaPlayer().events().addMediaPlayerEventListener(
                object : MediaPlayerEventAdapter() {
                    override fun finished(mediaPlayer: MediaPlayer) = onFinished()

                    override fun error(mediaPlayer: MediaPlayer) =
                        onError("此视频暂时无法播放，请尝试其他集数或剧集。")
                },
            )
            created.mediaPlayer().audio().setVolume(100)
            component = created
            created
        } catch (problem: Throwable) {
            failure = "播放器初始化失败：${problem.message ?: "请检查 VLC 运行库"}"
            null
        }
    }

    fun retryWith(vlcPath: String) {
        preferences.updateVlcPath(vlcPath)
        synchronized(this) {
            component?.release()
            component = null
            failure = null
            VlcRuntime.prepare("")
        }
        ensure()
    }

    fun play(url: String, startSeconds: Double, rate: Float) {
        val player = ensure()?.mediaPlayer() ?: return
        val options = if (startSeconds > 1.0) arrayOf(":start-time=${startSeconds.toInt()}") else emptyArray()
        player.media().play(url, *options)
        player.controls().setRate(rate)
        player.audio().setVolume(100)
    }

    fun toggle() {
        val player = component?.mediaPlayer() ?: return
        if (player.status().isPlaying()) player.controls().pause() else player.controls().play()
    }

    fun pause() {
        component?.mediaPlayer()?.controls()?.pause()
    }

    fun setRate(value: Float) {
        component?.mediaPlayer()?.controls()?.setRate(value)
    }

    fun seek(seconds: Double) {
        if (seconds.isFinite() && seconds >= 0) {
            component?.mediaPlayer()?.controls()?.setTime((seconds * 1000).toLong())
        }
    }

    fun skip(delta: Double) {
        val player = component?.mediaPlayer() ?: return
        val now = player.status().time() / 1000.0
        seek((now + delta).coerceAtLeast(0.0))
    }

    val timeSeconds: Double
        get() = component?.mediaPlayer()?.status()?.time()?.div(1000.0) ?: 0.0

    val durationSeconds: Double
        get() = component?.mediaPlayer()?.status()?.length()?.div(1000.0) ?: 0.0

    val isPlaying: Boolean
        get() = component?.mediaPlayer()?.status()?.isPlaying() == true

    fun stop() {
        component?.mediaPlayer()?.controls()?.stop()
    }

    @Synchronized
    fun release() {
        component?.release()
        component = null
    }
}
