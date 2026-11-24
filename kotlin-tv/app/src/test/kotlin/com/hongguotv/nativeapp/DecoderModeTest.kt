// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    @Config(sdk = [23, 26])
    fun decodeFailuresAreRememberedOnlyForTheModeThatWasPlaying() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val playback = context.getSharedPreferences("native-playback-v1", 0)
        val stored = context.getSharedPreferences("native-decoder-lessons-v1", 0)
        val storeField = CodecCapabilities::class.java.getDeclaredField("prefs").apply { isAccessible = true }
        fun reload() {
            storeField.set(CodecCapabilities, null)
            CodecCapabilities.init(context)
        }
        stored.edit().putStringSet("failures", setOf("test-codec")).commit()
        reload()
        try {
            DecoderMode.write(playback, DecoderMode.AUTO)
            assertTrue(CodecCapabilities.supports("test-codec", 1920, 1080))
            CodecCapabilities.learnFailure("test-codec", 1920, 1080, DecoderMode.HARDWARE)

            assertTrue(CodecCapabilities.supports("test-codec", 1920, 1080))
            DecoderMode.write(playback, DecoderMode.SOFTWARE)
            assertTrue(CodecCapabilities.supports("test-codec", 1920, 1080))
            DecoderMode.write(playback, DecoderMode.HARDWARE)
            assertEquals(setOf("test-codec|1920|1080"), stored.getStringSet("failures-HARDWARE", null))
            reload()
            assertFalse(CodecCapabilities.supports("test-codec", 1920, 1080))
            assertTrue(CodecCapabilities.supports("test-codec", 1280, 720))
            CodecCapabilities.resetLearned()
            assertTrue(CodecCapabilities.supports("test-codec", 1920, 1080))
        } finally {
            CodecCapabilities.resetLearned()
            storeField.set(CodecCapabilities, null)
        }
    }

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
    fun hardwareModeSkipsCodecsWithoutHardwareDecoder() {
        // 强制硬解 + 无硬解 → 跳过该编码（回落到 H.264）
        assertTrue(DecoderMode.shouldSkipForHardware(DecoderMode.HARDWARE, hasHardwareDecoder = false))
        // 强制硬解 + 有硬解 → 保留
        assertTrue(!DecoderMode.shouldSkipForHardware(DecoderMode.HARDWARE, hasHardwareDecoder = true))
        // 自动 / 强制软解 不受影响
        assertTrue(!DecoderMode.shouldSkipForHardware(DecoderMode.AUTO, hasHardwareDecoder = false))
        assertTrue(!DecoderMode.shouldSkipForHardware(DecoderMode.SOFTWARE, hasHardwareDecoder = false))
    }

    @Test
    fun storesAndReadsBack() {
        DecoderMode.write(prefs, DecoderMode.SOFTWARE)
        assertEquals(DecoderMode.SOFTWARE, DecoderMode.read(prefs))
        DecoderMode.write(prefs, DecoderMode.HARDWARE)
        assertEquals(DecoderMode.HARDWARE, DecoderMode.read(prefs))
    }
}
