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

    /** 仅保留站点内 IPv4（排除回环/链路本地/IPv6），供多网卡地址展示。 */
    fun filterSiteLocalV4(addresses: List<java.net.InetAddress>): List<String> =
        addresses
            .filterIsInstance<java.net.Inet4Address>()
            .filter { it.isSiteLocalAddress && !it.isLoopbackAddress && !it.isLinkLocalAddress }
            .map { it.hostAddress }
            .distinct()

    /** 本机全部可用局域网地址（多网卡场景全部列出，如 231 与 114）。 */
    fun allLanAddresses(): List<String> =
        runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .toList()
        }.getOrDefault(emptyList())
            .let(::filterSiteLocalV4)

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
     * ffmpeg 路径解析：捆绑（app 根下 app/ffmpeg/ffmpeg 或 app/ffmpeg 平铺文件，兼容两种
     * 打包布局；jpackage 拷贝可能丢失可执行位，探测前自愈）→ 显式指定（env/参数）→ 系统 PATH
     * → Homebrew 常见绝对位置（双击启动的 PATH 极简，bare name 会找不到）。全无返回 null。
     */
    fun resolveFfmpeg(
        explicit: String?,
        appContents: Path?,
        probe: (String) -> Boolean,
        homebrewCandidates: List<String> = listOf("/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg"),
    ): String? {
        if (appContents != null) {
            val appDir = appContents.resolve("app")
            for (candidate in listOf(appDir.resolve("ffmpeg/ffmpeg"), appDir.resolve("ffmpeg"))) {
                val normalized = candidate.normalize()
                if (normalized.startsWith(appContents) && Files.isRegularFile(normalized)) {
                    normalized.toFile().setExecutable(true) // 自愈：打包拷贝可能丢失 +x
                    if (probe(normalized.toString())) return normalized.toString()
                }
            }
        }
        if (!explicit.isNullOrBlank() && probe(explicit)) return explicit
        if (probe("ffmpeg")) return "ffmpeg"
        for (fallback in homebrewCandidates) {
            if (Files.exists(Path.of(fallback)) && probe(fallback)) return fallback
        }
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
