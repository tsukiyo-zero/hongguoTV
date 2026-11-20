// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 解码方式偏好的循环与偏好读写；过滤器本身依赖设备解码器，由实机验收。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23])
class DecoderModeTest {
    private val prefs =
        org.robolectric.RuntimeEnvironment.getApplication()
            .getSharedPreferences("decoder-mode-test", 0)

    @Test
    fun cyclesThroughThreeModes() {
        assertEquals(DecoderMode.HARDWARE, DecoderMode.AUTO.next())
        assertEquals(DecoderMode.SOFTWARE, DecoderMode.HARDWARE.next())
        assertEquals(DecoderMode.AUTO, DecoderMode.SOFTWARE.next())
    }

    @Test
    fun labelsAreDistinct() {
        val labels = DecoderMode.entries.map { it.label }
        assertEquals(labels.size, labels.toSet().size)
        assertTrue(labels.contains("自动"))
    }

    @Test
    fun defaultsToAutoWhenNothingStored() {
        assertEquals(DecoderMode.AUTO, DecoderMode.read(prefs))
    }

    @Test
    fun unknownStoredValueFallsBackToAuto() {
        prefs.edit().putString("decoder-mode", "BROKEN").commit()
        assertEquals(DecoderMode.AUTO, DecoderMode.read(prefs))
    }

    @Test
    fun describesHardwareAndSoftwareDecoders() {
        assertEquals(
            "解码 HEVC · 硬解 · c2.qti.hevc.decoder",
            DecoderMode.describe("c2.qti.hevc.decoder", DecoderMode.AUTO),
        )
        assertEquals(
            "解码 HEVC · 软解 · c2.android.hevc.decoder",
            DecoderMode.describe("c2.android.hevc.decoder", DecoderMode.AUTO),
        )
        assertEquals(
            "解码 H.264 · 硬解 · OMX.qcom.video.decoder.avc",
            DecoderMode.describe("OMX.qcom.video.decoder.avc", DecoderMode.HARDWARE),
        )
    }

    @Test
    fun warnsWhenActualDecoderContradictsMode() {
        assertTrue(
            DecoderMode.describe("c2.android.hevc.decoder", DecoderMode.HARDWARE)
                .endsWith("（与所选模式不一致）")
        )
        assertTrue(
            DecoderMode.describe("c2.qti.hevc.decoder", DecoderMode.SOFTWARE)
                .endsWith("（与所选模式不一致）")
        )
    }

    @Test
    fun withoutPlaybackShowsPendingHint() {
        assertEquals("解码 自动 · 起播后显示实际解码器", DecoderMode.describe(null, DecoderMode.AUTO))
    }

    @Test
    fun storesAndReadsBack() {
        DecoderMode.write(prefs, DecoderMode.SOFTWARE)
        assertEquals(DecoderMode.SOFTWARE, DecoderMode.read(prefs))
        DecoderMode.write(prefs, DecoderMode.HARDWARE)
        assertEquals(DecoderMode.HARDWARE, DecoderMode.read(prefs))
    }
}
