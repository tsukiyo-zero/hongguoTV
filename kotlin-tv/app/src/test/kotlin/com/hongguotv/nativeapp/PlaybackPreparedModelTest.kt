// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.*
import org.junit.Test
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 26])
class PlaybackPreparedModelTest {
    @Test
    fun videoHeaderFailureKeepsPreparedModelForForegroundButNotAnotherRetry() {
        var models = 0
        var headers = 0
        val http = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.url.host == "media.example.invalid") {
                headers++
                throw java.io.IOException("测试视频头暂不可用")
            }
            models++
            okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("fixture")
                .body("""{"code":0,"data":{"video_model_data":{"123":{"video_model":{"video_list":[{"main_url":"https://media.example.invalid/video","video_meta":{"definition":"720p","codec_type":"h264","vwidth":720,"vheight":1280},"encrypt_info":{"encrypt":false}}]}}}}}"""
                    .toResponseBody()).build()
        }.build()
        val repository = com.hongguotv.core.ContentRepository(http,
            playbackSession = com.hongguotv.core.PlaybackDeviceSession { com.hongguotv.core.DeviceRegister.Registered("333", "444") })
        for (speculative in listOf(true, true, false)) {
            assertThrows(java.io.IOException::class.java) {
                openStream(http, repository, "123", 720, { true }, speculative = speculative) { true }
            }
        }
        assertEquals(1, models)
        assertEquals(3, headers)
        assertThrows(java.io.IOException::class.java) {
            openStream(http, repository, "123", 720, { true }) { true }
        }
        assertEquals(2, models)
        assertEquals(4, headers)
    }

}
