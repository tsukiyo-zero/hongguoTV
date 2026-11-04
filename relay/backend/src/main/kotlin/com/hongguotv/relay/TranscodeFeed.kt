// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.relay

import com.hongguotv.core.Mp4SampleTable
import java.io.OutputStream

/** 纯转码逻辑：长度前缀 NALU → Annex-B、seek 起点定位、码率档位。 */
object TranscodeFeed {
    private val START_CODE = byteArrayOf(0, 0, 0, 1)

    /** 单个解密样本（4 字节长度前缀 NALU 序列）转 Annex-B 追加到 out。 */
    fun toAnnexB(sample: ByteArray, out: OutputStream) {
        var cursor = 0
        while (cursor + 4 <= sample.size) {
            val nal = ((sample[cursor].toInt() and 255) shl 24) or
                ((sample[cursor + 1].toInt() and 255) shl 16) or
                ((sample[cursor + 2].toInt() and 255) shl 8) or
                (sample[cursor + 3].toInt() and 255)
            if (nal <= 0 || cursor + 4 + nal > sample.size) break
            out.write(START_CODE)
            out.write(sample, cursor + 4, nal)
            cursor += 4 + nal
        }
    }

    /** VVC 随机访问 NAL 类型：IDR_W_RADL(7)、IDR_N_LP(8)、CRA(10)、STSA_R(5)。 */
    private val RAP_TYPES = setOf(5, 7, 8, 10)

    /**
     * stss 缺失时从样本数据推导真实 RAP 表：逐样本读首 NALU 的 2 字节头判类型。
     * 字节家流的同步信息不在 stss 而在带内 NAL 类型上，全样本聚合读一遍代价 ≈ 单次顺序拉流。
     */
    fun scanSyncSamples(source: VideoSource, table: Mp4SampleTable.Table): List<Int> {
        val raps = ArrayList<Int>()
        var index = 0
        val samples = table.samples
        while (index < samples.size) {
            val start = samples[index].offset
            var last = index
            while (
                last < samples.size &&
                    samples[last].offset + samples[last].size - start <= 1_000_000
            )
                last++
            if (last == index) last = index + 1
            val spanEnd = samples[last - 1].offset + samples[last - 1].size
            val blob = source.read(start, (spanEnd - start).toInt())
            for (i in index until last) {
                val base = (samples[i].offset - start).toInt()
                // 样本首 NALU：4 字节长度前缀 + 2 字节 NAL 头；type 占第二字节高 5 位。
                if (base + 6 <= blob.size) {
                    val type = (blob[base + 5].toInt() shr 3) and 0x1F
                    if (type in RAP_TYPES) raps += i
                }
            }
            index = last
        }
        return raps
    }

    /**
     * 从样本表 dts 步进取真实帧率（分数字符串），供 `-r` 输入选项修正裸 VVC 流的时间戳
     * （ffmpeg 对裸 VVC 默认按 SPS/25fps 排时间戳，与容器真实帧率不符时音画全错）。
     */
    fun frameRate(table: Mp4SampleTable.Table): String {
        val samples = table.samples
        if (samples.size < 2) return "25"
        val delta = samples[1].dts - samples[0].dts
        if (delta <= 0) return "25"
        return "${1_000_000}/${delta}"
    }

    /**
     * seek 目标（微秒）→ 起始喂帧样本下标：首个 dts ≥ 目标的样本，回退到最近随机访问点。
     * 优先 stss；缺失时用带内扫描的 rapIndices；两者皆缺才按「全部同步」退化。
     */
    fun startIndex(table: Mp4SampleTable.Table, offsetUs: Long, rapIndices: List<Int>): Int {
        val samples = table.samples
        if (samples.isEmpty()) return 0
        var idx = samples.indexOfFirst { it.dts >= offsetUs }
        if (idx < 0) idx = samples.size - 1
        val sync = if (table.syncSamples.isNotEmpty()) table.syncSamples else rapIndices
        if (sync.isEmpty()) return idx
        var lo = 0
        var hi = sync.size - 1
        var best = sync[0]
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (sync[mid] <= idx) {
                best = sync[mid]
                lo = mid + 1
            } else hi = mid - 1
        }
        return best
    }

    /** 分辨率 → H.264 目标码率（竖屏短剧经验档）。 */
    fun videoBitrate(table: Mp4SampleTable.Table): String = when {
        maxOf(table.width, table.height) >= 1900 -> "6M"
        maxOf(table.width, table.height) >= 1200 -> "3M"
        maxOf(table.width, table.height) >= 1000 -> "2M"
        else -> "1M"
    }
}
