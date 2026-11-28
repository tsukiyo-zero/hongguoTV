// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.relay

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * 伴机自动升级：relay-stable 固定 Release 上的 relay-update.json 清单 → 静默下载 →
 * size+sha256 双校验 → 确认后分平台安装（mac=孤儿脚本替换 .app 并重启；win=msi 向导）。
 * 清单/下载/校验/脚本生成均为纯逻辑便于单测；进程退出与接线在 RelayMain。
 */
internal class RelayUpdater(
    private val http: OkHttpClient,
    private val base: String,
    private val currentVersion: String,
    /** 升级包下载/脚本目录；安装脚本由此落盘（RelayMain 执行）。 */
    val updatesDir: Path,
    private val platform: String = if (IS_WINDOWS) "win" else "mac",
) {
    /** 平台资产三元组；清单里缺对应平台块=该平台不可自动更新。 */
    data class PlatformAsset(val fileName: String, val size: Long, val sha256: String)

    data class Manifest(val version: String, val notes: String, val mac: PlatformAsset?, val win: PlatformAsset?)

    /** 本平台资产；平台标识默认按宿主 OS（构造参数可覆盖，供测试）。 */
    private fun Manifest.assetForPlatform(): PlatformAsset? =
        when (platform) {
            "win" -> win
            else -> mac
        }

    /** 拉取并解析清单；返回 null=检查正常但无更新版本（含清单缺本平台块：不可自动更新，跳过）。 */
    fun check(): Manifest? {
        val manifest = parseManifest(fetchText(listUrl()))
        if (manifest.version == currentVersion || !isNewer(currentVersion, manifest.version)) return null
        if (manifest.assetForPlatform() == null) {
            println("自动升级：清单未包含本平台（$platform）资产，跳过（可到下载页手动获取 ${manifest.version}）")
            return null
        }
        return manifest
    }

    private fun listUrl(): String =
        (if (base.endsWith("/")) base else "$base/") + "relay-update.json"

    private fun fetchText(url: String): String =
        http.newCall(Request.Builder().url(url).header("Accept", "application/json").get().build())
            .execute()
            .use { response ->
                if (!response.isSuccessful) throw IOException("清单 HTTP ${response.code}")
                response.body?.string()?.takeIf { it.isNotBlank() } ?: throw IOException("清单空响应")
            }

    /**
     * 下载平台资产到 updates/ 并双校验；返回已校验文件路径。同版本已校验包直接复用。
     * 校验失败删除半成品抛错（下次检查重下）。onProgress 参数为 0-100 百分比。
     */
    fun download(manifest: Manifest, onProgress: (Int) -> Unit = {}): Path {
        val asset = manifest.assetForPlatform() ?: throw IOException("清单未包含本平台资产")
        updatesDir.toFile().mkdirs()
        val target = updatesDir.resolve(asset.fileName).normalize()
        require(target.startsWith(updatesDir)) { "安装包路径异常" }
        val existing = target.toFile()
        if (existing.isFile && matches(existing, asset)) {
            cleanupOld(asset.fileName)
            return target
        }
        val partial = updatesDir.resolve(asset.fileName + ".part").normalize()
        val digest = MessageDigest.getInstance("SHA-256")
        var received = 0L
        try {
            http.newCall(Request.Builder().url(downloadUrl(asset.fileName)).get().build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("安装包 HTTP ${response.code}")
                val source = response.body?.byteStream() ?: throw IOException("安装包空响应")
                Files.newOutputStream(partial).use { out ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val n = source.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        received += n
                        if (received > asset.size) throw IOException("安装包超出清单声明大小")
                        val percent = (received * 100 / asset.size).toInt().coerceIn(0, 100)
                        onProgress(percent)
                    }
                }
            }
            val file = partial.toFile()
            if (file.length() != asset.size) throw IOException("安装包大小与清单不符（${file.length()}≠${asset.size}）")
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            if (hex != asset.sha256.lowercase()) throw IOException("安装包 sha256 校验未通过")
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING)
            cleanupOld(asset.fileName)
            return target
        } catch (problem: IOException) {
            partial.toFile().delete()
            throw problem
        }
    }

    private fun downloadUrl(fileName: String): String {
        require(fileName.isNotBlank() && !fileName.contains('/') && !fileName.contains('\\')) {
            "清单文件名非法：$fileName"
        }
        return (if (base.endsWith("/")) base else "$base/") + fileName
    }

    /** 已有包复用判定：size 与 sha256（本地重算）都匹配。 */
    private fun matches(file: File, asset: PlatformAsset): Boolean {
        if (file.length() != asset.size) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == asset.sha256.lowercase()
    }

    /** 新版本包落地后清理更旧版本的安装包与半成品（保留清单文件与当前包）。 */
    private fun cleanupOld(currentFileName: String) {
        updatesDir.toFile().listFiles()?.forEach { file ->
            val name = file.name
            val isInstaller = name.endsWith(".dmg") || name.endsWith(".msi")
            if ((isInstaller && name != currentFileName) || name.endsWith(".part")) file.deleteRecursively()
        }
    }

    companion object {
        val IS_WINDOWS: Boolean = System.getProperty("os.name").orEmpty().contains("Windows", true)

        /** 清单解析与字段校验；缺一拒用（IllegalStateException，消息中文可直出）。 */
        fun parseManifest(text: String): Manifest {
            val root =
                runCatching { JSONObject(text) }.getOrElse { throw IllegalStateException("清单非合法 JSON") }
            val version = root.optString("version")
            if (!Regex("\\d+(\\.\\d+){0,2}").matches(version))
                throw IllegalStateException("清单版本号非法：$version")
            fun asset(key: String): PlatformAsset? {
                val block = root.optJSONObject(key) ?: return null
                val fileName = block.optString("fileName")
                require(fileName.isNotBlank() && !fileName.contains('/') && !fileName.contains('\\')) {
                    "清单 $key.fileName 非法"
                }
                val size = block.optLong("size")
                require(size > 0) { "清单 $key.size 非法" }
                val sha = block.optString("sha256").lowercase()
                require(Regex("[a-f0-9]{64}").matches(sha)) { "清单 $key.sha256 非法" }
                return PlatformAsset(fileName, size, sha)
            }
            return Manifest(version, root.optString("notes"), asset("mac"), asset("win"))
        }

        /** 三段版本号比较；缺段/非数字段按 0。candidate 严格大于 current 才算更新。 */
        fun isNewer(current: String, candidate: String): Boolean {
            fun parts(value: String) = value.split('.').map { it.toLongOrNull() ?: 0L }
            val a = parts(current)
            val b = parts(candidate)
            for (index in 0 until maxOf(a.size, b.size)) {
                val left = a.getOrElse(index) { 0L }
                val right = b.getOrElse(index) { 0L }
                if (left != right) return right > left
            }
            return false
        }

        /**
         * macOS 一键替换脚本：挂载 dmg → 覆盖现有 .app → 去隔离属性 → 卸载 → 重启伴机。
         * 单引号路径经 [shellQuote] 转义；shell 变量用普通字符串的 \$ 转义输出字面量
         * （原始字符串不支持反斜杠转义，\$ 会被当模板插值）；set -e 保证任一步失败即非零
         * 退出（调用方快速失败窗口捕捉）。
         */
        fun macInstallScript(dmgPath: String, appBundlePath: String): String {
            val dmg = shellQuote(dmgPath)
            val target = shellQuote(appBundlePath)
            return listOf(
                "set -e",
                "sleep 1",
                "MNT=\$(mktemp -d)",
                "trap 'hdiutil detach \"\$MNT\" -quiet || true' EXIT",
                "hdiutil attach -nobrowse -quiet -mountpoint \"\$MNT\" $dmg",
                "APP_SRC=\$(find \"\$MNT\" -maxdepth 2 -name '*.app' | head -1)",
                "if [ -z \"\$APP_SRC\" ]; then echo \"dmg 内未找到 .app\"; exit 3; fi",
                "rm -rf $target",
                "ditto --rsrc \"\$APP_SRC\" $target",
                "xattr -dr com.apple.quarantine $target 2>/dev/null || true",
                "open $target",
            ).joinToString("\n") + "\n"
        }

        /** Windows 安装命令：msiexec 图形向导（不静默不等待，规避 /qn 挂起史）。 */
        fun winInstallCommand(msiPath: String): List<String> = listOf("msiexec", "/i", msiPath)

        /** Finder/资源管理器定位安装包（失败回退路径）。 */
        fun revealCommand(file: Path): List<String> =
            if (IS_WINDOWS) listOf("explorer", "/select,", file.toString()) else listOf("open", "-R", file.toString())

        private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    }
}
