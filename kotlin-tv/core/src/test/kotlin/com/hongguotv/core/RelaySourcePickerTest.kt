// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelaySourcePickerTest {
    private fun rendition(quality: Int, codec: String) =
        Rendition(quality, codec, 1080, 1920, "https://example.invalid/$codec-$quality", null)

    @Test
    fun `bytevc2 优先于 ladder 其他档`() {
        val raw = listOf(rendition(720, "bytevc2"), rendition(1080, "bytevc1"))
        val ladder = listOf(rendition(1080, "bytevc1"))
        assertEquals("bytevc2", RelaySourcePicker.pick(raw, ladder)?.codec)
    }

    @Test
    fun `无 bytevc2 时回退 ladder 最高档`() {
        val ladder = listOf(rendition(540, "bytevc1"), rendition(1080, "bytevc1"))
        val picked = RelaySourcePicker.pick(emptyList(), ladder)
        assertEquals(1080, picked?.quality)
        assertEquals("bytevc1", picked?.codec)
    }

    @Test
    fun `两级全空返回 null`() {
        assertNull(RelaySourcePicker.pick(emptyList(), emptyList()))
        assertNull(
            RelaySourcePicker.pick(
                listOf(rendition(720, "bytevc2").copy(url = "")),
                listOf(rendition(1080, "bytevc1").copy(url = "")),
            ),
        )
    }

    @Test
    fun `codec 大小写与 bvc2 别名归一`() {
        val raw = listOf(rendition(720, "BVC2"))
        assertEquals("BVC2", RelaySourcePicker.pick(raw, emptyList())?.codec)
    }

    @Test
    fun `caps 缺字段视为仅 bytevc2`() {
        assertTrue(RelaySourcePicker.capsAllow(null, "bytevc2"))
        assertFalse(RelaySourcePicker.capsAllow(null, "bytevc1"))
        // 明确空 caps：ffmpeg 能力探测失败，什么都不支持。
        assertFalse(RelaySourcePicker.capsAllow(emptyList(), "bytevc2"))
        assertTrue(RelaySourcePicker.capsAllow(listOf("bytevc2", "bytevc1"), "ByteVC1"))
    }
}
