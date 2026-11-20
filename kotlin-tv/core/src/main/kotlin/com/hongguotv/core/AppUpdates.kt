// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

// 应用更新数据与校验规则；Android 下载和安装交互由 app 模块负责。

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class AppUpdate(
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val tag: String,
    val fileName: String,
    val size: Long,
    val sha256: String,
    val notes: String,
    /** 该清单来源平台的 releases 根地址；GitCode 限流时会换成 GitHub。 */
    val releasesBase: String = RELEASES,
) {
    fun newerThan(installed: Long, sdk: Int) = versionCode > installed && minSdk <= sdk

    val downloadUrl
        get() = "$releasesBase/download/$tag/$fileName"

    companion object {
        const val REPOSITORY = "tsukiyo-zero/hongguoTV"
        const val RELEASES = "https://gitcode.com/$REPOSITORY/releases"
        const val LATEST_API =
            "https://api.gitcode.com/api/v5/repos/$REPOSITORY/releases/latest?type=latest"

        /** GitCode 的 API 会按账号限流（实测出现过 429），这时改用 GitHub 的发行版接口。 */
        const val LATEST_API_GITHUB =
            "https://api.github.com/repos/$REPOSITORY/releases/latest"

        const val GITHUB_RELEASES = "https://github.com/$REPOSITORY/releases"

        fun latestTag(json: String): String {
            val release = JSONObject(json)
            require(!release.optBoolean("prerelease", false)) { "不能使用预发布版本" }
            val tag = release.getString("tag_name")
            require(tag.matches(Regex("kotlin-v[0-9][0-9A-Za-z.+-]{0,80}")))
            val assets = release.getJSONArray("assets")
            require(
                (0 until assets.length()).any {
                    assets.getJSONObject(it).optString("name") == "update.json"
                }
            ) {
                "发行版缺少更新清单"
            }
            return tag
        }

        const val MAX_APK_BYTES = 80L * 1024 * 1024

        fun parse(json: String): AppUpdate {
            val o = JSONObject(json)
            require(
                o.getInt("schema") == 1 &&
                    o.getString("packageName") == "io.github.tsukiyo_zero.guoyingtv"
            ) {
                "更新信息不匹配"
            }
            val apk = o.getJSONObject("apk")
            val update =
                AppUpdate(
                    o.getLong("versionCode"),
                    o.getString("versionName"),
                    o.getInt("minSdk"),
                    o.getString("tag"),
                    apk.getString("name"),
                    apk.getLong("size"),
                    apk.getString("sha256").lowercase(),
                    o.optString("notes").take(1500),
                )
            require(update.versionCode in 1..Int.MAX_VALUE.toLong() && update.minSdk in 23..100)
            require(update.versionName.matches(Regex("[0-9][0-9A-Za-z.+-]{0,63}")))
            require(update.tag.matches(Regex("kotlin-v[0-9][0-9A-Za-z.+-]{0,80}")))
            require(
                update.fileName.matches(
                    Regex("hongguotv-kotlin-[0-9][0-9A-Za-z.+-]{0,80}-android(?:6|8)\\.apk")
                )
            )
            require(update.size in 1..MAX_APK_BYTES && update.sha256.matches(Regex("[0-9a-f]{64}")))
            return update
        }
    }
}

/** Streaming validation also protects a cached APK from partial downloads and stale metadata. */
object UpdateFiles {
    fun verify(file: File, update: AppUpdate): Boolean {
        if (!file.isFile || file.length() != update.size) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(32768)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == update.sha256
    }

    fun receive(
        input: InputStream,
        destination: File,
        update: AppUpdate,
        progress: (Int) -> Unit = {},
    ) {
        val partial = File(destination.parentFile, destination.name + ".part")
        destination.parentFile?.mkdirs()
        try {
            partial.outputStream().use { output ->
                val buffer = ByteArray(32768)
                var total = 0L
                var last = -1
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw IOException("下载已取消")
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > update.size) throw IOException("安装包长度异常")
                    output.write(buffer, 0, n)
                    val percent = (total * 100 / update.size).toInt()
                    if (percent != last) {
                        last = percent
                        progress(percent)
                    }
                }
                output.fd.sync()
            }
            if (!verify(partial, update)) throw IOException("安装包校验未通过，请重新下载")
            if (!partial.renameTo(destination)) throw IOException("无法保存安装包")
        } finally {
            partial.delete()
        }
    }
}

class AppUpdateRepository : java.io.Closeable {
    private val http =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.MINUTES)
            .addNetworkInterceptor { chain ->
                val url = chain.request().url
                // Follow GitCode's CDN redirects, never cleartext or arbitrary manifest-provided
                // hosts.
                if (
                    url.scheme != "https" ||
                        url.host !in
                            setOf(
                                "api.gitcode.com",
                                "gitcode.com",
                                "file-cdn.gitcode.com",
                                "api.github.com",
                                "github.com",
                                "objects.githubusercontent.com",
                            )
                )
                    throw IOException("更新下载地址不受支持")
                chain.proceed(chain.request())
            }
            .build()

    private fun request(url: String) =
        Request.Builder()
            .url(url)
            .header("User-Agent", "HongguoTV-Updater")
            .header("Cache-Control", "no-cache")
            .build()

    private fun readMetadata(url: String): String {
        val call = http.newCall(request(url))
        call.timeout().timeout(25, TimeUnit.SECONDS)
        return call.execute().use { response ->
            if (!response.isSuccessful)
                throw IOException(if (response.code == 404) "尚无可用的自动更新版本" else "无法检查更新，请稍后重试")
            response.body?.byteStream()?.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (out.size() + n > 65536) throw IOException("更新信息过大")
                    out.write(buffer, 0, n)
                }
                out.toString("UTF-8")
            } ?: throw IOException("更新信息为空")
        }
    }

    fun latest(): AppUpdate {
        // GitCode API 会因账号级限流返回 429；此时回退到 GitHub 的同一份发行版清单，
        // 两个平台发布的是同一套 tag 与 update.json。
        val primary = runCatching { readMetadata(AppUpdate.LATEST_API) }
        val (release, releasesBase) =
            primary.fold(
                onSuccess = { it to AppUpdate.RELEASES },
                onFailure = {
                    readMetadata(AppUpdate.LATEST_API_GITHUB) to AppUpdate.GITHUB_RELEASES
                },
            )
        val tag =
            try {
                AppUpdate.latestTag(release)
            } catch (_: Exception) {
                throw IOException("最新发行版信息格式不正确")
            }
        val metadata = readMetadata("$releasesBase/download/$tag/update.json")
        if (
            runCatching { JSONObject(metadata).optString("packageName") }.getOrDefault("") ==
                "com.hongguotv.nativeapp"
        )
            throw IOException("当前发行版仍为旧包名，果映 TV 新包名正式更新尚未发布")
        return try {
            AppUpdate.parse(metadata)
                .copy(releasesBase = releasesBase)
                .also { require(it.tag == tag) { "清单标签不匹配" } }
        } catch (_: Exception) {
            throw IOException("更新信息格式不正确")
        }
    }

    fun download(update: AppUpdate, file: File, progress: (Int) -> Unit) {
        if (UpdateFiles.verify(file, update)) return
        http.newCall(request(update.downloadUrl)).execute().use { response ->
            if (!response.isSuccessful) throw IOException("安装包下载失败，请稍后重试")
            val body = response.body ?: throw IOException("安装包为空")
            if (body.contentLength() >= 0 && body.contentLength() != update.size)
                throw IOException("安装包长度异常")
            body.byteStream().use { UpdateFiles.receive(it, file, update, progress) }
        }
    }

    fun cancel() = http.dispatcher.cancelAll()

    override fun close() {
        cancel()
        http.connectionPool.evictAll()
        http.dispatcher.executorService.shutdown()
    }
}
