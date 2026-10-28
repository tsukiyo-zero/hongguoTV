// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.windows

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Covers are fetched with plain ImageIO. The feed returns HEIC renditions, which the JDK
 * cannot decode, so the same object is also requested from the JPEG template on the
 * public image host; the app never needs a native image codec.
 */
object Images {
    private val http =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

    private val cache =
        object : LinkedHashMap<String, ImageBitmap>(96, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>) = size > 200
        }

    private val failed = HashSet<String>()
    private val jpegTemplate = Regex("^https://(p\\d+)[a-z-]*\\.fqnovelpic\\.com/novel-pic/([0-9a-fA-F]+)~.*$")

    fun fromBase64(value: String): ImageBitmap? =
        runCatching {
            val bytes = java.util.Base64.getDecoder().decode(value)
            ImageIO.read(ByteArrayInputStream(bytes))?.toComposeImageBitmap()
        }.getOrNull()

    fun jpegFallback(url: String): String? {
        val match = jpegTemplate.find(url) ?: return null
        return "https://${match.groupValues[1]}-novel.byteimg.com/novel-pic/${match.groupValues[2]}~tplv-shrink:640:0.image"
    }

    suspend fun load(url: String): ImageBitmap? {
        if (url.isBlank()) return null
        synchronized(cache) { cache[url] }?.let { return it }
        if (synchronized(failed) { url in failed }) return null
        val candidates =
            if (url.contains(".heic", ignoreCase = true)) listOfNotNull(jpegFallback(url), url)
            else listOf(url)
        for (candidate in candidates) {
            val bitmap =
                withContext(Dispatchers.IO) {
                    runCatching {
                        val request =
                            Request.Builder()
                                .url(candidate)
                                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) GuoyingTV")
                                .build()
                        http.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) return@use null
                            val bytes = response.body?.bytes() ?: return@use null
                            if (bytes.size > 16 * 1024 * 1024) return@use null
                            ImageIO.read(ByteArrayInputStream(bytes))?.toComposeImageBitmap()
                        }
                    }.getOrNull()
                }
            if (bitmap != null) {
                synchronized(cache) { cache[url] = bitmap }
                return bitmap
            }
        }
        synchronized(failed) {
            failed += url
            if (failed.size > 500) failed.clear()
        }
        return null
    }
}
