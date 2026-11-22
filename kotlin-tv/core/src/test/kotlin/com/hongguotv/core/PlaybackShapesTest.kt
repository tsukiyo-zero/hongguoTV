// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 形态记忆：顺序、恢复与成功回调。 */
class PlaybackShapesTest {
    @After
    fun reset() {
        PlaybackShapeMemory.restore(null)
        PlaybackShapeMemory.onChange = null
    }

    @Test
    fun defaultOrderFollowsEnumOrder() {
        PlaybackShapeMemory.restore(null)
        assertEquals(
            listOf(PlaybackShape.GET_VIDEO_TAB, PlaybackShape.POST_PLAYER, PlaybackShape.POST_V1),
            PlaybackShapeMemory.order(),
        )
    }

    @Test
    fun preferredShapeMovesToFront() {
        PlaybackShapeMemory.restore(PlaybackShape.POST_PLAYER)
        val order = PlaybackShapeMemory.order()
        assertEquals(PlaybackShape.POST_PLAYER, order.first())
        assertEquals(3, order.size)
    }

    @Test
    fun successUpdatesPreferenceAndNotifiesOnce() {
        var changes = 0
        PlaybackShapeMemory.onChange = { changes++ }
        PlaybackShapeMemory.success(PlaybackShape.POST_V1)
        PlaybackShapeMemory.success(PlaybackShape.POST_V1)
        assertEquals(1, changes)
        assertEquals(PlaybackShape.POST_V1, PlaybackShapeMemory.current())
    }

    @Test
    fun repeatedFailureClearsPreference() {
        PlaybackShapeMemory.restore(PlaybackShape.POST_PLAYER)
        PlaybackShapeMemory.failure(PlaybackShape.POST_PLAYER)
        assertEquals(PlaybackShape.POST_PLAYER, PlaybackShapeMemory.current())
        PlaybackShapeMemory.failure(PlaybackShape.POST_PLAYER)
        assertNull(PlaybackShapeMemory.current())
    }

    @Test
    fun successResetsFailureCounters() {
        PlaybackShapeMemory.restore(PlaybackShape.POST_PLAYER)
        PlaybackShapeMemory.failure(PlaybackShape.POST_PLAYER)
        PlaybackShapeMemory.success(PlaybackShape.POST_PLAYER)
        PlaybackShapeMemory.failure(PlaybackShape.POST_PLAYER)
        // 成功一次后计数清零，单次失败不应清空记忆。
        assertEquals(PlaybackShape.POST_PLAYER, PlaybackShapeMemory.current())
    }

    @Test
    fun parseRoundTripsLabels() {
        PlaybackShape.entries.forEach { assertEquals(it, PlaybackShape.parse(it.label)) }
        assertNull(PlaybackShape.parse("unknown"))
        assertNull(PlaybackShape.parse(null))
    }
}
