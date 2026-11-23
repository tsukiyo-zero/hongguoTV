// SPDX-License-Identifier: GPL-3.0-only
// Adapted from N3urda/hongguoTV 6672b55; GPL-3.0-only.
package com.hongguotv.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** 签名同时返回最终地址，确保请求票据选择的摘要分支与实际请求一致。 */
internal data class MobileSignedRequest(val url: String, val headers: Map<String, String>)

internal fun interface MobilePlaybackSigner {
    fun sign(url: String, body: ByteArray, device: Map<String, String>): MobileSignedRequest
}

internal object MobilePlayback {
    private val v5Signer = Signer(mobileV5 = true)
    val signer = MobilePlaybackSigner { url, body, _ ->
        val parsed = url.toHttpUrl()
        val values = parsed.queryParameterNames.associateWith { parsed.queryParameter(it)!! }
        val now = values.getValue("_rticket").toLong()
        val request = v5Signer.signPlayback(parsed.newBuilder().query(null).build().toString(), values, body, now)
        MobileSignedRequest(request.url, request.headers)
    }

    /**
     * 形态「GET video_tab」：与 [model] 共用同一套 v5 签名与 7.4.5.32 参数，
     * 只是改成 GET + 查询参数，响应结构为 `data.video_model_data[id].video_model`。
     * 平台 11-23 起只放行这一条，旧的 Argus 签名 GET 会被静默丢弃。
     */
    fun getVideoTab(
        id: String,
        device: Map<String, String>,
        http: OkHttpClient,
        signer: MobilePlaybackSigner,
        now: Long,
    ): JSONObject {
        require(id.matches(Regex("[0-9]{1,30}")))
        RequestScope.ensureActive()
        val query =
            linkedMapOf(
                "aid" to "8662", "app_name" to "novelread",
                "version_code" to "74532", "version_name" to "7.4.5.32",
                "channel" to "vivo_8662_64", "device_platform" to "android",
                "device_type" to "PGT-AN10", "os_version" to "12",
                "update_version_code" to "74532",
                "iid" to device.getValue("iid"), "cdid" to device.getValue("cdid"),
                "device_id" to device.getValue("device_id"), "_rticket" to now.toString(),
                "video_id" to id, "video_id_type" to "0", "device_level" to "1",
                "need_all_video_definition" to "1", "need_mp4_align" to "0",
                "use_os_player" to "0", "video_platform" to "1024",
                "source" to "0", "from_video_id" to "",
            )
        val url =
            "https://api5-normal-sinfonlineb.fqnovel.com/reading/bookapi/video_tab/multi_video_model/v1"
                .toHttpUrl()
                .newBuilder()
                .apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }
                .build()
        val signed = signer.sign(url.toString(), ByteArray(0), device)
        val request =
            Request.Builder()
                .url(signed.url)
                .header(
                    "User-Agent",
                    "com.phoenix.read/74532 (Linux; U; Android 12; zh_CN_#Hans; PGT-AN10; Build/V417IR;tt-ok/3.12.13.20)",
                )
                .header("Accept", "application/json")
                .apply { signed.headers.forEach { (k, v) -> header(k, v) } }
                .get()
        val transport = http.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
        return RequestScope.execute(transport.newCall(request.build())) { response ->
            if (!response.isSuccessful) throw IOException("播放接口 HTTP ${response.code}")
            val text = response.body?.string().orEmpty()
            if (text.isBlank()) throw EmptyPlaybackResponseException("shape=get")
            val root =
                runCatching { JSONObject(text) }.getOrElse { throw IOException("播放信息格式异常") }
            val code = root.optInt("code", root.optInt("Code", -1))
            if (code != 0) throw IOException("播放接口暂不可用（错误码 $code）")
            val data = root.optJSONObject("data") ?: throw IOException("播放信息为空")
            val rows = data.optJSONObject("video_model_data") ?: data
            val row =
                rows.optJSONObject(id)
                    ?: rows.keys().asSequence().mapNotNull { rows.optJSONObject(it) }.firstOrNull()
                    ?: throw IOException("播放信息为空")
            val raw = row.opt("video_model") ?: throw IOException("播放信息没有可用资源")
            val model =
                when (raw) {
                    is JSONObject -> raw
                    is String -> runCatching { JSONObject(raw) }.getOrNull()
                    else -> null
                } ?: throw IOException("播放信息没有可用资源")
            if ((model.optJSONArray("video_list")?.length() ?: 0) == 0 && !model.has("fallback_api"))
                throw IOException("播放信息没有可用资源")
            JSONObject().put("video_model", model)
        }
    }

    fun model(id: String, device: Map<String, String>, http: OkHttpClient,
               signer: MobilePlaybackSigner, now: Long): JSONObject {
        require(id.matches(Regex("[0-9]{1,30}")))
        RequestScope.ensureActive()
        val query = linkedMapOf("aid" to "8662", "app_name" to "novelread",
            "version_code" to "74532", "version_name" to "7.4.5.32",
            "channel" to "vivo_8662_64", "device_platform" to "android",
            "device_type" to "PGT-AN10", "os_version" to "12", "update_version_code" to "74532",
            "iid" to device.getValue("iid"), "cdid" to device.getValue("cdid"),
            "device_id" to device.getValue("device_id"), "_rticket" to now.toString())
        // 2026-11-22：v1 路径被关，`:dr_scene=player` 变体重新可用（与 11-20 恰好相反）。
        // 平台在这两者之间来回切换，保持两条路径轮换探测比写死一条更稳。
        val url =
            "https://api5-normal-sinfonlinea.fqnovel.com/novel/player/multi_video_model/player/v1"
            .toHttpUrl().newBuilder().apply { query.forEach { (k,v) -> addQueryParameter(k,v) } }.build()
        // 电视使用系统解码器，以等级 1 协商兼容资源；仍需按返回的实际编码过滤。
        val payload = JSONObject("""{"biz_param":{"detail_page_version":0,"device_level":1,"disable_digg_stat":false,"disable_video_relate_book":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"mixed_video_id_map":{"1004":["$id"]}}""")
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val bytes = output.toByteArray()
        val request = Request.Builder().url(url)
            .header("User-Agent", "com.phoenix.read/73932 (Linux; U; Android 12; zh_CN_#Hans; PGT-AN10; Build/V417IR;tt-ok/3.12.13.20)")
            .header("Accept", "application/json").header("Content-Encoding", "gzip")
            .header("x-ss-stub", MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02X".format(it) })
        val signed = signer.sign(url.toString(), bytes, device)
        request.url(signed.url)
        signed.headers.forEach { (k,v) -> request.header(k,v) }
        RequestScope.ensureActive()
        request.post(bytes.toRequestBody("application/json; charset=utf-8".toMediaType()))
        val transport = http.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
        return RequestScope.execute(transport.newCall(request.build())) { response ->
            if (!response.isSuccessful) throw IOException("播放接口 HTTP ${response.code}")
            val body = response.body ?: throw IOException("播放信息格式异常")
            val limit = 2 * 1024 * 1024L
            if (body.contentLength() > limit) throw IOException("播放信息过大")
            val source = body.source()
            source.request(limit + 1)
            if (source.buffer.size > limit) throw IOException("播放信息过大")
            val text = source.readUtf8()
            if (text.isBlank()) throw EmptyPlaybackResponseException("stage=mobile-model")
            val root = try { JSONObject(text) } catch (_: Exception) {
                throw IOException("播放信息格式异常")
            }
            val code = root.optInt("Code", root.optInt("code", 0))
            if (code != 0) throw IOException("播放接口暂不可用（错误码 $code）")
            val data = root.optJSONObject("data") ?: throw IOException("播放信息为空")
            val entries = data.optJSONObject("1004") ?: data
            val raw = entries.optJSONObject(id)?.opt("video_model")
            val model = when (raw) {
                is JSONObject -> raw
                is String -> runCatching { JSONObject(raw) }.getOrNull()
                else -> null
            } ?: throw IOException("播放信息未包含指定剧集")
            if ((model.optJSONArray("video_list")?.length() ?: 0) == 0 && !model.has("fallback_api"))
                throw IOException("播放信息没有可用资源")
            JSONObject().put("video_model", model)
        }
    }
}
