// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.relay

import com.hongguotv.core.Mp4SampleTable
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

class TranscodeFeedTest {
    @Test
    fun `长度前缀 NALU 转 AnnexB`() {
        val input = byteArrayOf(0, 0, 0, 3, 10, 20, 30, 0, 0, 0, 1, 40)
        val out = ByteArrayOutputStream()
        TranscodeFeed.toAnnexB(input, out)
        assertEquals(
            byteArrayOf(0, 0, 0, 1, 10, 20, 30, 0, 0, 0, 1, 40).toList(),
            out.toByteArray().toList(),
        )
    }

    @Test
    fun `坏前缀安全截断不抛异常`() {
        val input = byteArrayOf(0, 0, 0, 3, 1, 2, 3, 0, 0x0f, 0, 0x7f)
        val out = ByteArrayOutputStream()
        TranscodeFeed.toAnnexB(input, out)
        assertEquals(7, out.size())
    }

    private fun table(sync: List<Int>): Mp4SampleTable.Table =
        Mp4SampleTable.Table(
            "bvc2", 720, 1280, 10_000,
            (0 until 10).map { Mp4SampleTable.Sample(it * 28L, 28, it * 100_000L, it * 100_000L) },
            sync,
        )

    @Test
    fun `seek 定位回退最近同步样本`() {
        assertEquals(5, TranscodeFeed.startIndex(table(listOf(0, 5)), 500_000, emptyList()))
        assertEquals(5, TranscodeFeed.startIndex(table(listOf(0, 5)), 550_000, emptyList()))
        assertEquals(0, TranscodeFeed.startIndex(table(listOf(0, 5)), 10_000, emptyList()))
        // 超出末尾：落在最后一个同步样本（不得从非同步样本起喂）
        assertEquals(5, TranscodeFeed.startIndex(table(listOf(0, 5)), 5_000_000, emptyList()))
    }

    @Test
    fun `stss 缺失时用带内扫描的 RAP 表`() {
        // dts 步进 100ms：550ms 首个 ≥ 目标的样本是 idx6；RAP 表 [0,5] → 回退 5
        assertEquals(5, TranscodeFeed.startIndex(table(emptyList()), 550_000, listOf(0, 5)))
        assertEquals(0, TranscodeFeed.startIndex(table(emptyList()), -1, emptyList()))
        // 双缺失才按全同步退化
        assertEquals(6, TranscodeFeed.startIndex(table(emptyList()), 550_000, emptyList()))
    }

    @Test
    fun `码率档位随分辨率`() {
        assertEquals("1M", TranscodeFeed.videoBitrate(table(emptyList()).let {
            Mp4SampleTable.Table(it.fourcc, 360, 640, it.timescale, it.samples, it.syncSamples)
        }))
        assertEquals("3M", TranscodeFeed.videoBitrate(table(emptyList())))
        assertEquals("6M", TranscodeFeed.videoBitrate(table(emptyList()).let {
            Mp4SampleTable.Table(it.fourcc, 1080, 1920, it.timescale, it.samples, it.syncSamples)
        }))
    }

    /** 按样本表构造带指定首 NAL 类型的假源字节。 */
    private class FixtureSource(private val data: ByteArray) : VideoSource {
        override val total = data.size.toLong()
        override fun prepare() {}
        override fun readHeader() = data
        override fun read(start: Long, length: Int): ByteArray =
            data.copyOfRange(start.toInt(), minOf(data.size, start.toInt() + length))
        override fun close() {}
    }

    @Test
    fun `带内扫描识别 IDR 与 CRA 随机访问点`() {
        // 4 个样本，各 16 字节：4 字节长度前缀(=8) + NAL 头 2 字节 + 4 字节载荷；类型 0,7,0,10
        val types = intArrayOf(0, 7, 0, 10)
        val data = ByteArray(types.size * 16)
        types.forEachIndexed { i, type ->
            val at = i * 16
            data[at] = 0; data[at + 1] = 0; data[at + 2] = 0; data[at + 3] = 8
            data[at + 5] = (type shl 3).toByte()
        }
        val fixture =
            Mp4SampleTable.Table(
                "bvc2", 720, 1280, 10_000,
                (0 until 4).map { Mp4SampleTable.Sample(it * 16L, 16, it * 100_000L, it * 100_000L) },
                emptyList(),
            )
        assertEquals(listOf(1, 3), TranscodeFeed.scanSyncSamples(FixtureSource(data), fixture))
    }
}
