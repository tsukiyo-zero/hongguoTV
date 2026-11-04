// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.relay

import com.hongguotv.core.RemoteVideo
import okhttp3.OkHttpClient
import java.io.Closeable

/** 会话用的解密取流抽象：真实实现包 RemoteVideo，测试注入假源。 */
interface VideoSource : Closeable {
    val total: Long

    /** 预热（建立连接/解密预备）。 */
    fun prepare()

    /** 拉取 ftyp+moov 完整文件头，供采样表解析。 */
    fun readHeader(): ByteArray

    fun read(start: Long, length: Int): ByteArray
}

class RemoteVideoSource(http: OkHttpClient, info: com.hongguotv.core.StreamInfo) : VideoSource {
    private val video = RemoteVideo(http, info)

    override val total: Long
        get() = video.total

    override fun prepare() {
        video.prepare(256 * 1024)
    }

    override fun readHeader(): ByteArray {
        var bytes = read(0, minOf(1 shl 20, total.toInt()))
        if (bytes.size < 16 || String(bytes, 4, 4, Charsets.US_ASCII) != "ftyp")
            throw IllegalStateException("容器异常：缺少 ftyp")
        val ftypSize = u32(bytes, 0)
        val moovSize = u32(bytes, ftypSize + 4)
        val needed = ftypSize + moovSize
        while (bytes.size < needed) {
            val more = read(bytes.size.toLong(), minOf(1 shl 20, (needed - bytes.size).toInt()))
            if (more.isEmpty()) break
            bytes += more
        }
        return bytes
    }

    override fun read(start: Long, length: Int): ByteArray {
        if (length <= SPAN) return video.read(start, length)
        var bytes = ByteArray(0)
        var position = start
        while (position < start + length) {
            val take = minOf(SPAN, (start + length - position).toInt())
            bytes += video.read(position, take)
            position += take
        }
        return bytes
    }

    override fun close() = video.close()

    private companion object {
        const val SPAN = 1 shl 20
    }
}

private fun u32(data: ByteArray, at: Int): Int =
    ((data[at].toInt() and 255) shl 24) or ((data[at + 1].toInt() and 255) shl 16) or
        ((data[at + 2].toInt() and 255) shl 8) or (data[at + 3].toInt() and 255)
