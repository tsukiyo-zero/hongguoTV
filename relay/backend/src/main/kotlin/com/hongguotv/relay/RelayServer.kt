// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.relay

import com.hongguotv.core.Mp4SampleTable
import com.hongguotv.core.StreamInfo
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import java.io.IOException
import java.net.InetSocketAddress
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors

/** 伴机 REST 服务：/health、/session 生命周期、HLS 分段与音频回环。 */
class RelayServer(
    port: Int,
    private val manager: SessionManager,
    private val publicBase: String,
    private val version: String,
    private val encoder: String,
    private val ffmpegOk: Boolean,
    private val ffmpegPath: String,
    private val sourceFactory: (StreamInfo) -> VideoSource,
) {
    private val server = HttpServer.create(InetSocketAddress(port), 16)
    private val listenPort: Int get() = server.address.port

    fun start() {
        server.executor = Executors.newFixedThreadPool(8)
        server.createContext("/") { handle(it) }
        server.start()
    }

    fun stop() = server.stop(0)

    /** 解析采样表并创建会话；selftest 与 REST 共用。 */
    fun buildSession(info: StreamInfo): Session {
        val id = UUID.randomUUID().toString().replace("-", "").take(12)
        val source = sourceFactory(info)
        source.prepare()
        val table =
            Mp4SampleTable.parse(source.readHeader())
                ?: throw IOException("视频轨采样表解析失败（非 progressive MP4？）")
        val audioSource = sourceFactory(info).also { it.prepare() }
        // 字节家流 stss 缺失：从带内 NAL 类型扫描真实随机访问点，供 seek 回退。
        val rapIndices =
            if (table.syncSamples.isEmpty()) TranscodeFeed.scanSyncSamples(source, table) else emptyList()
        println(
            "采样表：${table.samples.size} 样本，stss=${table.syncSamples.size} 项，" +
                "带内 RAP=${rapIndices.size} 个，fps=${TranscodeFeed.frameRate(table)}",
        )
        return manager.create(id) { dir ->
            Session(
                id, source, audioSource, table, rapIndices, dir,
                "http://127.0.0.1:$listenPort/a/$id/audio.mp4", ffmpegPath, encoder,
            )
        }
    }

    private fun handle(exchange: HttpExchange) {
        try {
            val path = exchange.requestURI.path.trimEnd('/')
            val method = exchange.requestMethod
            when {
                method == "GET" && path == "/health" -> health(exchange)
                method == "POST" && path == "/session" -> createSession(exchange)
                method == "POST" && Regex("/session/[A-Za-z0-9-]+/seek").matches(path) -> seek(exchange, path)
                method == "DELETE" && Regex("/session/[A-Za-z0-9-]+").matches(path) -> stopSession(exchange, path)
                method == "GET" && path.startsWith("/hls/") -> serveHls(exchange, path)
                method == "GET" && Regex("/a/[A-Za-z0-9-]+/audio\\.mp4").matches(path) -> serveAudio(exchange, path)
                else -> json(exchange, 404, JSONObject().put("error", "not found"))
            }
        } catch (problem: Throwable) {
            runCatching {
                json(
                    exchange, 500,
                    JSONObject().put("error", "${problem.javaClass.simpleName}: ${problem.message?.take(160)}"),
                )
            }
        } finally {
            exchange.close()
        }
    }

    private fun health(exchange: HttpExchange) {
        val current = manager.currentOrNull()
        val payload =
            JSONObject()
                .put("ok", true)
                .put("version", version)
                .put("encoder", encoder)
                .put("ffmpegOk", ffmpegOk)
                .put(
                    "session",
                    current?.let {
                        JSONObject()
                            .put("id", it.id)
                            .put("state", it.state.name)
                            .put("positionSec", it.positionUs / 1_000_000.0)
                    },
                )
        json(exchange, 200, payload)
    }

    private fun createSession(exchange: HttpExchange) {
        val body = JSONObject(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
        val url = body.optString("url")
        require(url.startsWith("http://") || url.startsWith("https://")) { "url 无效" }
        val key =
            body.optString("keyB64").takeIf(String::isNotBlank)
                ?.let { Base64.getDecoder().decode(it) }
        val quality = body.optString("quality").takeIf(String::isNotBlank) ?: "自动"
        val session = buildSession(StreamInfo(url, key, quality))
        session.lastAccessMs = System.currentTimeMillis()
        json(
            exchange, 200,
            JSONObject()
                .put("sessionId", session.id)
                .put("hlsUrl", "$publicBase/hls/${session.id}/index.m3u8"),
        )
    }

    private fun seek(exchange: HttpExchange, path: String) {
        val id = path.removePrefix("/session/").removeSuffix("/seek")
        val session = manager.find(id) ?: return json(exchange, 404, JSONObject().put("error", "会话不存在"))
        val body = JSONObject(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
        val offsetSec = body.optDouble("offsetSec", 0.0)
        require(offsetSec >= 0) { "offsetSec 无效" }
        if (!session.seek(offsetSec)) return json(exchange, 409, JSONObject().put("error", "会话已停止"))
        session.lastAccessMs = System.currentTimeMillis()
        json(exchange, 200, JSONObject().put("ok", true))
    }

    private fun stopSession(exchange: HttpExchange, path: String) {
        val id = path.removePrefix("/session/")
        json(exchange, 200, JSONObject().put("ok", manager.stop(id)))
    }

    private fun serveHls(exchange: HttpExchange, path: String) {
        val rest = path.removePrefix("/hls/")
        val slash = rest.indexOf('/')
        if (slash <= 0) return json(exchange, 404, JSONObject().put("error", "not found"))
        val id = rest.take(slash)
        val name = rest.substring(slash + 1)
        if (!Regex("[A-Za-z0-9_.-]+").matches(name)) return json(exchange, 404, JSONObject().put("error", "not found"))
        val session = manager.find(id) ?: return json(exchange, 404, JSONObject().put("error", "会话不存在"))
        val file = session.workDir.resolve(name).toFile()
        if (!file.isFile) return json(exchange, 404, JSONObject().put("error", "分段未生成"))
        session.lastAccessMs = System.currentTimeMillis()
        val bytes = file.readBytes()
        exchange.responseHeaders.set(
            "Content-Type",
            if (name.endsWith(".m3u8")) "application/vnd.apple.mpegurl" else "video/mp2t",
        )
        exchange.responseHeaders.set("Cache-Control", "no-store")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    /** 解密后整份 MP4 按 Range 服务，供 ffmpeg 第二输入 demux AAC。 */
    private fun serveAudio(exchange: HttpExchange, path: String) {
        val id = path.removePrefix("/a/").removeSuffix("/audio.mp4")
        val session = manager.find(id) ?: return json(exchange, 404, JSONObject().put("error", "会话不存在"))
        session.lastAccessMs = System.currentTimeMillis()
        val total = session.audioSource.total
        val range = exchange.requestHeaders.getFirst("Range")
        val match = range?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) }
        val start = match?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val end = (match?.groupValues?.get(2)?.toLongOrNull() ?: (total - 1)).coerceAtMost(total - 1)
        if ((range != null && match == null) || start > end || start >= total) {
            exchange.responseHeaders.set("Content-Range", "bytes */$total")
            exchange.sendResponseHeaders(416, -1)
            return
        }
        exchange.responseHeaders.set("Content-Type", "video/mp4")
        exchange.responseHeaders.set("Accept-Ranges", "bytes")
        exchange.responseHeaders.set("Cache-Control", "no-store")
        val count = end - start + 1
        if (range != null) exchange.responseHeaders.set("Content-Range", "bytes $start-$end/$total")
        exchange.responseHeaders.set("Content-Length", count.toString())
        if (exchange.requestMethod == "HEAD") {
            exchange.sendResponseHeaders(if (range != null) 206 else 200, -1)
            return
        }
        exchange.sendResponseHeaders(if (range != null) 206 else 200, count)
        val chunk = ByteArray(256 * 1024)
        var offset = start
        exchange.responseBody.use { out ->
            while (offset <= end) {
                val n = minOf(chunk.size.toLong(), end - offset + 1).toInt()
                out.write(session.audioSource.read(offset, n))
                offset += n
            }
        }
    }

    private fun json(exchange: HttpExchange, status: Int, payload: JSONObject) {
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
