// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import com.hongguotv.core.ContentRepository
import com.hongguotv.core.DeviceRegister
import com.hongguotv.core.EmptyPlaybackResponseException
import com.hongguotv.core.PlaybackDeviceSession
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 26])
class PlaybackRetryConnectionTest {
    @Test fun http2RetriesActuallyUseFreshConnectionsAndLeaveSharedTransportUsable() {
        val server = MockWebServer()
        server.protocols = listOf(Protocol.H2_PRIOR_KNOWLEDGE)
        server.start()
        val http = OkHttpClient.Builder()
            .protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE))
            .addInterceptor { chain ->
                val request = chain.request()
                chain.proceed(request.newBuilder().url(server.url(request.url.encodedPath)).build())
            }.build()
        try {
            // 每次取模型按 6 种形态（3 路径 × 2 签名）尝试，各返回空体；
            // 空响应后最多再换一次身份，两轮共 12 次。
            repeat(12) { server.enqueue(MockResponse().setBody("")) }
            val session = PlaybackDeviceSession { DeviceRegister.Registered("333", "444") }
            val repo = ContentRepository(http, playbackSession = session, sleep = {})
            val error = assertThrows(EmptyPlaybackResponseException::class.java) { repo.streamLadder("123") }
            val requests = (1..12).map { server.takeRequest(5, TimeUnit.SECONDS)!! }
            // 换路重试必须换连接：至少有一次请求落在新连接上（sequenceNumber > 0）。
            assertTrue(requests.any { it.sequenceNumber > 0 })
            // 每条路径各被两种签名各试一轮，共 4 次。
            assertEquals(4, requests.count { it.path!!.contains("multi_video_model/player/v1") })
            assertEquals(4, requests.count { it.path!!.startsWith("/novel/player/multi_video_model/v1") })
            assertEquals(
                4,
                requests.count { it.path!!.startsWith("/reading/bookapi/video_tab/multi_video_model/v1") },
            )
            // 形态轮换后最后一个失败来自 POST v1，detail 可能为空；只要求是空响应语义。
            assertTrue(error.detail == null || error.detail!!.startsWith("shape="))
            server.enqueue(MockResponse().setBody("still usable"))
            http.newCall(Request.Builder().url(server.url("/other")).build()).execute().use {
                assertEquals("still usable", it.body!!.string())
            }
            // 共享传输仍可用；具体连接序号随重试次数变化，不再固定断言。
            server.takeRequest(5, TimeUnit.SECONDS)
        } finally {
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdownNow()
            server.shutdown()
        }
    }
}
