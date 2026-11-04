// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.relay

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** 伴机文件系统定位：状态目录（令牌）、ffmpeg 解析、jpackage app 根目录。 */
internal object RelayLocations {
    private const val STATE_DIR_NAME = "GuoyingRelay"

    /**
     * 状态目录：macOS ~/Library/Application Support/GuoyingRelay；
     * Windows %APPDATA%/GuoyingRelay；其他系统回退 ~/.local/share/GuoyingRelay。
     * 路径一律 normalize + startsWith(home) 包含校验（安全扫描要求）。
     */
    fun stateDir(home: Path, appData: String?, osName: String = System.getProperty("os.name").orEmpty()): Path {
        val base =
            when {
                appData != null && osName.contains("Windows", true) -> Path.of(appData)
                osName.contains("Windows", true) -> home.resolve("AppData").resolve("Roaming")
                osName.contains("Mac", true) -> home.resolve("Library").resolve("Application Support")
                else -> home.resolve(".local").resolve("share")
            }
        val dir = base.resolve(STATE_DIR_NAME).normalize()
        require(dir.startsWith(home.normalize())) { "状态目录路径异常" }
        return dir
    }

    /** 旧版（cwd 相对）令牌文件位置；存在则返回用于迁移。 */
    fun legacyTokenFile(cwd: Path): Path? {
        val legacy = cwd.resolve(".guoying-relay").resolve("token").normalize()
        return legacy.takeIf { Files.exists(it) && legacy.startsWith(cwd.normalize()) }
    }

    /**
     * jpackage app 根目录（…/果映伴机.app/Contents）：
     * 优先 jpackage.app-dir 系统属性，回退从当前进程可执行路径向上找 *.app/Contents。
     */
    fun appContents(jpackageAppDir: String?, processCommand: String?): Path? {
        jpackageAppDir?.takeIf { it.isNotBlank() }?.let { return Path.of(it).normalize() }
        val command = processCommand ?: return null
        var dir = Path.of(command).toAbsolutePath().normalize()
        while (dir.parent != null) {
            dir = dir.parent
            if (dir.fileName?.toString() == "Contents") return dir
        }
        return null
    }

    /**
     * ffmpeg 路径解析：捆绑（app 根下 app/ffmpeg/）→ 显式指定（env/参数）→ 系统 PATH。
     * 返回 null 表示三处皆无，调用方进入错误状态。
     */
    fun resolveFfmpeg(explicit: String?, appContents: Path?, probe: (String) -> Boolean): String? {
        if (appContents != null) {
            val bundled = appContents.resolve("app").resolve("ffmpeg").resolve("ffmpeg").normalize()
            if (bundled.startsWith(appContents) && Files.exists(bundled)) return bundled.toString()
        }
        if (!explicit.isNullOrBlank() && probe(explicit)) return explicit
        if (probe("ffmpeg")) return "ffmpeg"
        return null
    }

    /** 迁移旧令牌：旧文件存在且新文件不存在时搬迁，返回是否执行了搬迁。 */
    fun migrateToken(legacyTokenFile: Path?, tokenFile: Path): Boolean {
        if (legacyTokenFile == null || Files.exists(tokenFile)) return false
        val value = runCatching { Files.readString(legacyTokenFile).trim() }.getOrNull().orEmpty()
        if (value.isEmpty()) return false
        Files.createDirectories(tokenFile.parent)
        Files.writeString(tokenFile, value)
        return true
    }

    /**
     * 读取或生成令牌（首启生成 8 位并持久化；存在旧 cwd 令牌则迁移）。
     * 返回 令牌 to 令牌文件路径；文件 I/O 收敛在本对象内以便统一路径校验。
     */
    fun ensureToken(home: Path, appData: String?, legacyCwd: Path): Pair<String, Path> {
        val stateDir = stateDir(home, appData)
        val tokenFile = stateDir.resolve("token").normalize()
        require(tokenFile.startsWith(stateDir)) { "令牌文件路径异常" }
        if (Files.exists(tokenFile)) {
            val existing = Files.readString(tokenFile).trim()
            require(existing.isNotEmpty()) { "令牌文件为空，请删除 $tokenFile 后重启伴机" }
            return existing to tokenFile
        }
        legacyTokenFile(legacyCwd)?.let { legacy ->
            runCatching { Files.readString(legacy).trim() }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { value ->
                    Files.createDirectories(stateDir)
                    Files.writeString(tokenFile, value)
                    return value to tokenFile
                }
        }
        val generated =
            java.security.SecureRandom()
                .run {
                    val bytes = ByteArray(4)
                    nextBytes(bytes)
                    bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
                }
        Files.createDirectories(stateDir)
        Files.writeString(tokenFile, generated)
        return generated to tokenFile
    }
}
