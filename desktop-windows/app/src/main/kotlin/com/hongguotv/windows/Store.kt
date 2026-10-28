// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.windows

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sun.jna.platform.win32.Crypt32Util
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import org.json.JSONArray
import org.json.JSONObject

object AppDirs {
    val data: File by lazy {
        val base = System.getenv("APPDATA")?.takeIf { it.isNotBlank() } ?: System.getProperty("user.home")
        File(base, "GuoyingTV").apply { mkdirs() }
    }
}

object AppInfo {
    val version: String by lazy {
        runCatching {
            javaClass.getResourceAsStream("/guoyingtv.properties")?.use { stream ->
                Properties().apply { load(stream) }.getProperty("version")
            }
        }.getOrNull().orEmpty().ifBlank { "0.1.0" }
    }
}

data class WatchRecord(
    val series: Series,
    val episodeID: String,
    val episodeIndex: Int,
    val position: Double,
    val duration: Double,
    val completed: Boolean,
    val updatedAt: Long,
) {
    val label: String
        get() {
            val seconds = position.coerceAtLeast(0.0).toInt()
            return "第 ${episodeIndex + 1} 集 · " +
                if (completed) "已看完" else "%02d:%02d".format(seconds / 60, seconds % 60)
        }

    fun toJson() =
        JSONObject()
            .put("series", series.toJson())
            .put("episodeID", episodeID)
            .put("episodeIndex", episodeIndex)
            .put("position", position)
            .put("duration", duration)
            .put("completed", completed)
            .put("updatedAt", updatedAt)

    companion object {
        fun from(value: JSONObject) =
            WatchRecord(
                series = Series.from(value.getJSONObject("series")),
                episodeID = value.optString("episodeID"),
                episodeIndex = value.optInt("episodeIndex"),
                position = value.optDouble("position", 0.0),
                duration = value.optDouble("duration", 0.0),
                completed = value.optBoolean("completed"),
                updatedAt = value.optLong("updatedAt"),
            )
    }
}

/**
 * Local favorites and watch history. The Mac client keeps the same schema in
 * `library.json`, so both desktop clients stay conceptually in sync even though the
 * files live in different per-user directories.
 */
class LocalLibrary(private val file: File = File(AppDirs.data, "library.json")) {
    var favorites by mutableStateOf<List<Series>>(emptyList())
        private set
    var history by mutableStateOf<List<WatchRecord>>(emptyList())
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private var writable = true

    init {
        load()
    }

    private fun load() {
        if (!file.isFile) return
        try {
            val data = file.readBytes()
            if (data.size > 8 * 1024 * 1024) throw IllegalStateException("文件过大")
            val root = JSONObject(String(data, Charsets.UTF_8))
            if (root.optInt("schema", 0) != 1) throw IllegalStateException("版本不兼容")
            favorites = root.optJSONArray("favorites").orEmpty().series()
            history =
                root.optJSONArray("history").orEmpty().objects().mapNotNull {
                    runCatching { WatchRecord.from(it) }.getOrNull()
                }
        } catch (_: Exception) {
            writable = false
            error = "本机记录读取失败，已保留原文件；请备份后检查。"
        }
    }

    fun record(value: WatchRecord) {
        if (!value.position.isFinite() || !value.duration.isFinite()) return
        if (value.position < 0 || value.duration < 0 || value.episodeIndex < 0) return
        if (value.episodeID.isEmpty()) return
        history = (listOf(value) + history.filterNot { it.series.id == value.series.id }).take(500)
        save()
    }

    fun toggleFavorite(series: Series) {
        favorites =
            if (favorites.any { it.id == series.id }) favorites.filterNot { it.id == series.id }
            else (listOf(series) + favorites.filterNot { it.id == series.id }).take(2000)
        save()
    }

    fun removeHistory(id: String) {
        history = history.filterNot { it.series.id == id }
        save()
    }

    fun isFavorite(id: String) = favorites.any { it.id == id }

    fun recordFor(id: String) = history.firstOrNull { it.series.id == id }

    private fun save() {
        if (!writable) return
        try {
            val root =
                JSONObject()
                    .put("schema", 1)
                    .put("favorites", JSONArray(favorites.map { it.toJson() }))
                    .put("history", JSONArray(history.map { it.toJson() }))
            writeAtomically(file, root.toString().toByteArray(Charsets.UTF_8))
            error = null
        } catch (_: Exception) {
            error = "本机记录保存失败，请检查磁盘空间。"
        }
    }
}

class Preferences(private val file: File = File(AppDirs.data, "settings.json")) {
    var quality by mutableStateOf(1080)
        private set
    var speed by mutableStateOf(1f)
        private set
    var autoNext by mutableStateOf(true)
        private set
    var vlcPath by mutableStateOf("")
        private set

    init {
        load()
    }

    private fun load() {
        if (!file.isFile) return
        runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            quality = root.optInt("quality", 1080).takeIf { it in setOf(480, 720, 1080) } ?: 1080
            speed = root.optDouble("speed", 1.0).toFloat().takeIf { it in SPEEDS } ?: 1f
            autoNext = root.optBoolean("autoNext", true)
            vlcPath = root.optString("vlcPath")
        }
    }

    fun updateQuality(value: Int) {
        if (value !in setOf(480, 720, 1080)) return
        quality = value
        save()
    }

    fun updateSpeed(value: Float) {
        if (value !in SPEEDS) return
        speed = value
        save()
    }

    fun updateAutoNext(value: Boolean) {
        autoNext = value
        save()
    }

    fun updateVlcPath(value: String) {
        vlcPath = value.trim()
        save()
    }

    private fun save() {
        runCatching {
            val root =
                JSONObject()
                    .put("quality", quality)
                    .put("speed", speed.toDouble())
                    .put("autoNext", autoNext)
                    .put("vlcPath", vlcPath)
            writeAtomically(file, root.toString().toByteArray(Charsets.UTF_8))
        }
    }

    companion object {
        val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
    }
}

/**
 * Windows Credential-equivalent for the QR session: DPAPI encrypts the bridge credentials
 * with the current Windows user, so the file alone is useless on another account or PC.
 */
object SecretStore {
    private val file = File(AppDirs.data, "session.bin")

    fun load(): String? {
        if (!file.isFile) return null
        val protected = file.readBytes()
        if (protected.isEmpty()) return null
        return String(Crypt32Util.cryptUnprotectData(protected), Charsets.UTF_8)
    }

    fun save(value: String) {
        writeAtomically(file, Crypt32Util.cryptProtectData(value.toByteArray(Charsets.UTF_8)))
    }

    fun delete() {
        runCatching { file.delete() }
    }
}

private fun writeAtomically(file: File, bytes: ByteArray) {
    file.parentFile?.mkdirs()
    val temporary = File(file.parentFile, file.name + ".tmp")
    temporary.writeBytes(bytes)
    try {
        Files.move(
            temporary.toPath(),
            file.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    } catch (_: Exception) {
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
