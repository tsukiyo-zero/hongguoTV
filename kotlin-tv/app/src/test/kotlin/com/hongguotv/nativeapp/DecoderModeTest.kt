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
    fun storesAndReadsBack() {
        DecoderMode.write(prefs, DecoderMode.SOFTWARE)
        assertEquals(DecoderMode.SOFTWARE, DecoderMode.read(prefs))
        DecoderMode.write(prefs, DecoderMode.HARDWARE)
        assertEquals(DecoderMode.HARDWARE, DecoderMode.read(prefs))
    }
}
