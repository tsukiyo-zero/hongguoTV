// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException
import java.util.zip.GZIPInputStream
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MobilePlaybackTest {
    private val device = mapOf("device_id" to "123456", "iid" to "654321", "cdid" to "synthetic")
    private val signer = MobilePlaybackSigner { url, _, _ -> MobileSignedRequest(url, emptyMap()) }
    private fun model(typed: Boolean, encoded: Boolean, id: String = "123"): String {
        val model = JSONObject("""{"video_list":[{"main_url":"https://example.test/video"}]}""")
        val entries = JSONObject().put(id, JSONObject().put("video_model", if (encoded) model.toString() else model))
        return JSONObject().put("code", 0).put("data", if (typed) JSONObject().put("1004", entries) else entries).toString()
    }
    private fun http(body: String, inspect: (okhttp3.Request) -> Unit = {}) = OkHttpClient.Builder()
        .addInterceptor { chain ->
            inspect(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body.toResponseBody()).build()
        }.build()

    @Test fun exactEpisodeSelectionSupportsBothMapsAndEncodedModels() {
        for (typed in listOf(false, true)) for (encoded in listOf(false, true)) {
            assertEquals(1, MobilePlayback.model("123", device, http(model(typed, encoded)), signer, 1000)
                .getJSONObject("video_model").getJSONArray("video_list").length())
        }
        assertThrows(IOException::class.java) {
            MobilePlayback.model("123", device, http(model(false, false, "456")), signer, 1000)
        }
    }

    @Test fun signedCompressedBytesAndIdentityAreExactlyWhatIsSent() {
        var signed: ByteArray? = null
        var target: String? = null
        val signing = MobilePlaybackSigner { url, bytes, identity ->
            assertEquals(device, identity)
            signed = bytes.copyOf(); target = url
            MobileSignedRequest(url, mapOf("x-medusa" to "synthetic"))
        }
        MobilePlayback.model("123", device, http(model(false, false)) { request ->
            val bytes = Buffer().apply { request.body!!.writeTo(this) }.readByteArray()
            assertArrayEquals(signed, bytes)
            assertEquals(target, request.url.toString())
            assertEquals("/novel/player/multi_video_model/player/v1", request.url.encodedPath)
            assertEquals("123456", request.url.queryParameter("device_id"))
            assertEquals("gzip", request.header("Content-Encoding"))
            assertEquals("synthetic", request.header("x-medusa"))
            assertNull(request.header("Cookie"))
            assertEquals(digest("MD5", bytes).hex().uppercase(), request.header("x-ss-stub"))
            val payload = JSONObject(GZIPInputStream(bytes.inputStream()).reader().readText())
            assertEquals(1, payload.getJSONObject("biz_param").getInt("device_level"))
            assertEquals("123", payload.getJSONObject("mixed_video_id_map").getJSONArray("1004").getString(0))
        }, signing, 1000)
    }

    @Test fun businessFailureEmptyOversizedAndMalformedBodiesRemainFailures() {
        for (body in listOf("{\"code\":110001}", "bad json", " ".repeat(2*1024*1024+1))) {
            assertThrows(IOException::class.java) { MobilePlayback.model("123", device, http(body), signer, 1000) }
        }
        assertThrows(EmptyPlaybackResponseException::class.java) {
            MobilePlayback.model("123", device, http(""), signer, 1000)
        }
    }

    @Test fun cancellationDuringSigningCannotSendRequest() {
        val scope = RequestScope()
        assertThrows(IOException::class.java) {
            scope.run { MobilePlayback.model("123", device, http(model(false, false)) { fail("已取消仍发送") },
                MobilePlaybackSigner { url, _, _ -> scope.cancel(); MobileSignedRequest(url, emptyMap()) }, 1000) }
        }
    }

    @Test fun backgroundPreparationNeverRegistersAnIdentity() {
        val session = PlaybackDeviceSession { throw AssertionError("后台触发注册") }
        assertThrows(IOException::class.java) {
            ContentRepository(http = http(model(false, false)), playbackSession = session)
                .streamLadder("123", speculative = true)
        }
    }

    @Test fun emptyForegroundRetriesOnlyOnceAndRespectsRecoveryCooldown() {
        var registrations = 0
        var requests = 0
        val session = PlaybackDeviceSession {
            registrations++
            DeviceRegister.Registered("333", "444")
        }
        val repo = ContentRepository(http = http("") { requests++ }, clock = { 1000 }, playbackSession = session)
        repeat(2) { assertThrows(EmptyPlaybackResponseException::class.java) { repo.streamLadder("123") } }
        assertEquals(2, registrations)
        assertEquals(3, requests)
    }
}
