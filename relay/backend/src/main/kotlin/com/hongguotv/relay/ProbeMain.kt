// SPDX-License-Identifier: GPL-3.0-only
// relay 探针：验证「解析 bytevc2 档 → core 取流解密 → 按视频轨样本表重组 Annex-B → FFmpeg 可转码」。
// 仅自用诊断，不进正式发布物；不落任何凭据，密钥仅存于进程内存。
package com.hongguotv.relay

import com.hongguotv.core.ContentRepository
import com.hongguotv.core.Mp4SampleTable
import com.hongguotv.core.RemoteVideo
import java.io.FileOutputStream
import java.util.Base64

fun main(args: Array<String>) {
    // --json <集id>：仅打印最高 bytevc2 档的取流信息（url/keyB64/quality），供联调 /session 用。
    if (args.firstOrNull() == "--json") {
        val episode = args.getOrNull(1) ?: error("用法：ProbeMain --json <集id>")
        val repo = ContentRepository()
        val pick =
            repo.rawVideoModel(episode).filter { it.codec in setOf("bytevc2", "bvc2") }
                .maxByOrNull { it.quality } ?: error("该集没有 bytevc2 档位")
        val keyB64 = pick.key?.let { Base64.getEncoder().encodeToString(it) }
        println(
            org.json.JSONObject()
                .put("url", pick.url)
                .put("keyB64", keyB64 ?: "")
                .put("quality", if (pick.quality > 0) "${pick.quality}P" else "自动")
                .put("width", pick.width ?: 0)
                .put("height", pick.height ?: 0),
        )
        return
    }
    val episode = args.firstOrNull() ?: error("用法：ProbeMain <集id> [输出文件] [档位]")
    val output = args.getOrNull(1) ?: "/tmp/relay-smoke/probe-es.annexb.vvc"
    val wantQuality = args.getOrNull(2)?.removeSuffix("P")?.toIntOrNull()
    val repo = ContentRepository()
    println("解析播放模型 $episode …")
    val renditions =
        repo.rawVideoModel(episode).filter { it.codec in setOf("bytevc2", "bvc2") }.sortedByDescending { it.quality }
    require(renditions.isNotEmpty()) { "该集没有 bytevc2 档位" }
    println("bytevc2 档位：" + renditions.joinToString("、") { "${it.quality}P(${it.width}x${it.height})" })
    val pick = renditions.firstOrNull { wantQuality == null || it.quality == wantQuality } ?: renditions.first()
    val remote = RemoteVideo(repo.http, pick.toStreamInfo())
    remote.prepare(256 * 1024)
    val table = Mp4SampleTable.parse(readHeader(remote)) ?: error("视频轨采样表解析失败")
    println("视频轨 ${table.fourcc} ${table.width}x${table.height}，样本 ${table.samples.size} 个，total=${remote.total}")

    val started = System.currentTimeMillis()
    FileOutputStream(output).use { out ->
        val startCode = byteArrayOf(0, 0, 0, 1)
        var index = 0
        while (index < table.samples.size) {
            // 聚合 ≤1MB 的连续样本为一次读，减少 range 请求数。
            val start = table.samples[index].offset
            var last = index
            while (
                last < table.samples.size &&
                    table.samples[last].offset + table.samples[last].size - start <= 1_000_000
            )
                last++
            if (last == index) last = index + 1
            val spanEnd = table.samples[last - 1].offset + table.samples[last - 1].size
            val blob = readSpan(remote, start, (spanEnd - start).toInt())
            for (i in index until last) {
                val sample = table.samples[i]
                val base = (sample.offset - start).toInt()
                var cursor = base
                val finish = base + sample.size
                while (cursor + 4 <= finish) {
                    val nalSize = u32(blob, cursor)
                    if (nalSize <= 0 || cursor + 4 + nalSize > finish) break
                    out.write(startCode)
                    out.write(blob, cursor + 4, nalSize)
                    cursor += 4 + nalSize
                }
            }
            index = last
        }
    }
    val seconds = (System.currentTimeMillis() - started) / 1000.0
    println("Annex-B ES 已写出：$output（全样本重组耗时 ${"%.1f".format(seconds)}s，供 ffmpeg -f vvc 冒烟）")
}

private fun readHeader(remote: RemoteVideo): ByteArray {
    var bytes = remote.read(0, minOf(1 shl 20, remote.total.toInt()))
    if (bytes.size < 16 || String(bytes, 4, 4, Charsets.US_ASCII) != "ftyp")
        throw IllegalStateException("容器异常：缺少 ftyp")
    val ftypSize = u32(bytes, 0).toInt()
    val moovSize = u32(bytes, ftypSize + 4).toInt()
    val needed = ftypSize + moovSize
    while (bytes.size < needed) {
        val more = remote.read(bytes.size.toLong(), minOf(1 shl 20, needed - bytes.size))
        if (more.isEmpty()) break
        bytes += more
    }
    return bytes
}

private fun readSpan(remote: RemoteVideo, start: Long, length: Int): ByteArray {
    if (length <= 1 shl 20) return remote.read(start, length)
    var bytes = ByteArray(0)
    var position = start
    while (position < start + length) {
        val take = minOf(1 shl 20, (start + length - position).toInt())
        bytes += remote.read(position, take)
        position += take
    }
    return bytes
}

private fun u32(data: ByteArray, at: Int): Int =
    ((data[at].toInt() and 255) shl 24) or ((data[at + 1].toInt() and 255) shl 16) or
        ((data[at + 2].toInt() and 255) shl 8) or (data[at + 3].toInt() and 255)
