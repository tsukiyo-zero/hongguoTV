// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import com.hongguotv.core.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 26])
class FeedStartupCacheTest {
    private val page = CatalogPage(listOf(Series("101", "缓存作品")), true)

    @Test
    fun sourcesAreSeparateAndAccountChangeOrExpiryCannotReuseOldStarter() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        var now = 1000L
        val cache = FeedStartupCache(controller.get()) { now }
        try {
            cache.save("test-account-a", "recommend", page)
            assertNotNull(cache.load("test-account-a", "recommend"))
            assertNull(cache.load("test-account-a", DramaCategory.COMIC.route))
            now += 600001
            assertNull(cache.load("test-account-a", "recommend"))
            cache.save("test-account-a", "recommend", page)
            assertNull(cache.load("test-account-b", "recommend"))
            assertNull(cache.load("test-account-a", "recommend"))
            cache.save("test-account-a", "recommend", page)
            controller
                .get()
                .getSharedPreferences(
                    "native-feed-startup-v1",
                    android.content.Context.MODE_PRIVATE,
                )
                .edit()
                .putInt("recommend", 42)
                .apply()
            assertNull(cache.load("test-account-a", "recommend"))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun verifiedFirstEpisodeSurvivesBackendRecreationWithoutFeedOrDetailRequest() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val cache = FeedStartupCache(controller.get()) { 1000L }
        cache.save("guest", "recommend", page)
        val paths = java.util.concurrent.CopyOnWriteArrayList<String>()
        val ranges = java.util.concurrent.CopyOnWriteArrayList<String>()
        val file = ByteArray(1024 * 1024)
        java.nio.ByteBuffer.wrap(file)
            .putInt(8)
            .put("ftyp".toByteArray())
            .putInt(8)
            .put("moov".toByteArray())
        fun transport() =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val req = chain.request()
                    // 播放模型先读 video_tab 新接口；本用例只统计旧接口与视频分段请求。
                    if (req.url.encodedPath.startsWith("/reading/bookapi/video_tab/"))
                        return@addInterceptor Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("test")
                            .body("""{"code":100103,"message":"PARAM_INVALID","data":null}""".toResponseBody())
                            .build()
                    paths += req.url.encodedPath
                    val builder =
                        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).message("test")
                    if (req.header("Range") != null) {
                        val header = req.header("Range")!!
                        ranges += header
                        val limits = header.removePrefix("bytes=").split('-').map(String::toInt)
                        builder
                            .code(206)
                            .header("Content-Range", "bytes ${limits[0]}-${limits[1]}/${file.size}")
                            .body(file.copyOfRange(limits[0], limits[1] + 1).toResponseBody())
                            .build()
                    } else {
                        val json =
                            when (req.url.encodedPath) {
                                "/detail" ->
                                    """<script>_ROUTER_DATA={"loaderData":{"detail_page":{"seriesDetail":{"series_id":"101","series_title":"缓存作品","vid_list":["10101","10102"]}}}};</script>"""
                                "/novel/player/multi_video_model/v1/" ->
                                    """{"data":{"10101":{"video_model":{"video_list":[{"main_url":"https://example.test/video","video_meta":{"definition":"720p","codec_type":"h264","vwidth":720,"vheight":1280},"encrypt_info":{"encrypt":false}}]}}}}"""
                                else -> error("Unexpected request")
                            }
                        builder.code(200).body(json.toResponseBody()).build()
                    }
                }
                .build()
        try {
            repeat(2) {
                val backend =
                    PublicFeedBackend(
                        720,
                        http = transport(),
                        startupCache = cache,
                        playbackSession = testPlaybackDeviceSession(),
                    )
                try {
                    val result = backend.recommendations(1, DramaFeedState().recommendationCursor)
                    backend.trial(result.items.single()).use { clip ->
                        assertEquals("10101", clip.episodeId)
                        assertEquals(2, clip.episodeCount)
                        assertEquals(32 * 1024, clip.video.cachedBytes(0))
                    }
                } finally {
                    backend.close()
                }
            }
            assertEquals(1, paths.count { it == "/detail" })
            assertEquals(2, paths.count { it == "/novel/player/multi_video_model/v1/" })
            assertEquals(listOf("bytes=0-262143", "bytes=0-262143"), ranges)
            assertEquals(5, paths.size)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
