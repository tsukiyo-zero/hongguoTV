package com.hongguotv.nativeapp

import org.junit.Assert.*
import org.junit.Test

class PlaybackSourcesTest {
    @Test
    fun failureSummaryKeepsTheRealCauseWithoutLeakingAddressesOrResponseText() {
        val empty = com.hongguotv.core.EmptyPlaybackResponseException()
        assertEquals("播放信息接口返回空响应", playbackFailureSummary(empty))
        assertEquals(
            "播放信息请求失败（HTTP 503）",
            playbackFailureSummary(com.hongguotv.core.ContentHttpException(503)),
        )
        assertEquals("视频分段提前断开", playbackFailureSummary(java.io.EOFException("fixture")))
        val unknown =
            playbackFailureSummary(java.io.IOException("https://example.com/video?token=test-only"))
        assertFalse(unknown.contains("https://"))
        assertFalse(unknown.contains("token"))
    }

    @Test
    fun knownPrefixCategoriesSurfaceTheirFullMessageInsteadOfTheFallback() {
        assertEquals(
            "没有电视可播放的清晰度，请稍后再试或选择其他剧集",
            playbackFailureSummary(java.io.IOException("没有电视可播放的清晰度，请稍后再试或选择其他剧集")),
        )
        assertEquals(
            "视频服务器不支持分段读取（HTTP 416）",
            playbackFailureSummary(java.io.IOException("视频服务器不支持分段读取（HTTP 416）")),
        )
        assertEquals("播放数据读取失败", playbackFailureSummary(java.io.IOException("某种全新的错误")))
    }

    @Test
    fun diagnosticDetailPassesEmptyResponseMetadataThrough() {
        assertEquals(
            "空响应x4 host=b,hl,lf,b",
            diagnosticDetail(com.hongguotv.core.EmptyPlaybackResponseException("空响应x4 host=b,hl,lf,b")),
        )
    }

    @Test
    fun diagnosticDetailIsNullWhenTheChainHasNothingStructured() {
        assertNull(diagnosticDetail(java.io.IOException("网络请求超时")))
    }

    @Test
    fun diagnosticDetailCapsLengthAtSixtyChars() {
        val detail = diagnosticDetail(com.hongguotv.core.EmptyPlaybackResponseException("x".repeat(200)))
        assertEquals(60, detail!!.length)
    }

    @Test
    fun manualRetryOffersTheLowestLadderTierBelowAttempted() {
        val ladder =
            listOf(
                com.hongguotv.core.Rendition(1080, "h264", 1080, 1920, "u1", null),
                com.hongguotv.core.Rendition(720, "h264", 720, 1280, "u2", null),
                com.hongguotv.core.Rendition(540, "h264", 540, 960, "u3", null),
                com.hongguotv.core.Rendition(480, "h264", 480, 854, "u4", null),
                com.hongguotv.core.Rendition(360, "h264", 360, 640, "u5", null),
            )
        assertEquals(360, nextLowerQuality(1080, ladder))
        assertEquals(360, nextLowerQuality(720, ladder))
        assertNull(nextLowerQuality(360, ladder))
        assertNull(nextLowerQuality(1080, ladder.take(1))) // 阶梯只剩当前档，不给无效按钮
        assertNull(nextLowerQuality(1080, emptyList()))
        assertNull(nextLowerQuality(0, ladder)) // 未知已尝试档位
        // 未知档位（quality=0）不作为降档目标
        val withUnknown =
            listOf(
                com.hongguotv.core.Rendition(0, "h264", 0, 0, "u0", null),
                com.hongguotv.core.Rendition(480, "h264", 480, 854, "u4", null),
            )
        assertEquals(480, nextLowerQuality(1080, withUnknown))
    }

    @Test
    fun autoDowngradeNeedsBudgetLowerTierAndKnownQuality() {
        val tiers =
            listOf(
                com.hongguotv.core.Rendition(1080, "h264", 1080, 1920, "u1", null),
                com.hongguotv.core.Rendition(480, "h264", 480, 854, "u2", null),
            )
        assertTrue(shouldAutoDowngrade(0, 2, tiers, 1080))
        assertFalse(shouldAutoDowngrade(2, 2, tiers, 1080)) // budget exhausted
        assertFalse(shouldAutoDowngrade(0, 2, tiers, 480)) // nothing lower
        assertFalse(shouldAutoDowngrade(0, 2, emptyList(), 1080))
        assertFalse(shouldAutoDowngrade(0, 2, tiers, 0)) // unknown attempted quality
    }
}
