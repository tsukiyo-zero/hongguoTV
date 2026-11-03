package com.hongguotv.core

import java.io.IOException
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RecommendationFeedTest {
    private fun response(id: String = "101", tab: Int = 16, wrapped: Boolean = false): String {
        val row =
            JSONObject()
                .put("series_id", id)
                .put("series_title", "推荐作品$id")
                .put("episode_cnt", 80)
                .put("series_intro", "简介")
                .put("series_cover", "https://example.com/cover.jpg")
        val video = if (wrapped) JSONObject().put("video_detail", row) else row
        val cell =
            JSONObject()
                .put(
                    "cell_data",
                    JSONArray()
                        .put(JSONObject().put("video_data", JSONArray().put(video).put(video))),
                )
        val item =
            JSONObject()
                .put("tab_type", tab)
                .put("cell_data", JSONArray().put(cell))
                .put("session_id", "test-page-session")
                .put("has_more", false)
                .put("bottom_unlimited", true)
                .put("next_offset", 0)
        return JSONObject()
            .put("code", 0)
            .put("data", JSONObject().put("tab_item", JSONArray().put(item)))
            .toString()
    }

    @Test
    fun parsesBothCardShapesAndDoesNotMistakeHasMoreFalseForFeedEnd() {
        for (wrapped in listOf(false, true)) {
            val data = JSONObject(response(wrapped = wrapped))
            if (wrapped) {
                val tab = data.getJSONObject("data").getJSONArray("tab_item").getJSONObject(0)
                tab.remove("has_more")
                tab.remove("bottom_unlimited")
            }
            val page = RecommendationFeed.parse(data).first
            assertEquals(listOf("101"), page.items.map { it.id })
            assertTrue(page.hasMore)
            assertEquals("共 80 集", page.items.single().badge)
        }
    }

    @Test
    fun firstEpisodeHintRequiresExplicitFirstEpisodeMetadata() {
        val data = JSONObject(response())
        val row =
            data
                .getJSONObject("data")
                .getJSONArray("tab_item")
                .getJSONObject(0)
                .getJSONArray("cell_data")
                .getJSONObject(0)
                .getJSONArray("cell_data")
                .getJSONObject(0)
                .getJSONArray("video_data")
                .getJSONObject(0)
        row.put("vid", "999") // Recommended clip is not proof of episode one.
        assertTrue(RecommendationFeed.parse(data).first.trialHints.isEmpty())
        row.put("first_vid", "10101")
        val hint = RecommendationFeed.parse(data).first.trialHints.getValue("101")
        assertEquals("10101", hint.episodeId)
        assertEquals(80, hint.episodeCount)
        row.remove("first_vid")
        row.put(
            "video_list",
            JSONArray()
                .put(JSONObject().put("vid_index", 2).put("vid", "10102"))
                .put(JSONObject().put("vid_index", 1).put("vid", "10101")),
        )
        assertEquals(
            "10101",
            RecommendationFeed.parse(data).first.trialHints.getValue("101").episodeId,
        )
    }

    @Test
    fun paginationRefreshAndCancelledResultRetryKeepServerCursor() {
        val requests = mutableListOf<Request>()
        val http =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    requests += chain.request()
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(response((101 + requests.size).toString()).toResponseBody())
                        .build()
                }
                .build()
        val cursor = RecommendationCursor()
        val feed = RecommendationFeed(http, cursor = cursor)
        feed.page(1)
        val second = feed.page(2)
        assertSame(second, RecommendationFeed(http, cursor = cursor).page(2))
        assertEquals(2, requests.size)
        RecommendationFeed(http, cursor = cursor.nextBatch()).page(1)
        assertEquals(listOf("0", "1", "2"), requests.map { it.url.queryParameter("offset") })
        assertNull(requests[0].header("Cookie"))
        assertNull(requests[0].url.queryParameter("device_id"))
        assertEquals("test-page-session", requests[1].url.queryParameter("session_id"))
        assertEquals("2", requests[2].url.queryParameter("client_req_type"))
        assertThrows(IOException::class.java) { feed.page(4) }
    }

    @Test
    fun repeatedBatchesAreSkippedWithinBoundedRetry() {
        var calls = 0
        val http =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    calls++
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(response(if (calls < 3) "101" else "102").toResponseBody())
                        .build()
                }
                .build()
        val feed = RecommendationFeed(http)
        assertEquals("101", feed.page(1).items.single().id)
        assertEquals("102", feed.page(2).items.single().id)
        assertEquals(3, calls)
        assertThrows(IOException::class.java) { feed.page(3) }
        assertEquals(6, calls)
    }

    @Test
    fun accountIdentityIsSentOnlyToOfficialFeedAndGuestCursorIsSeparate() {
        var requested: Request? = null
        val http =
            OkHttpClient.Builder()
                .followRedirects(false)
                .addInterceptor { chain ->
                    requested = chain.request()
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(response().toResponseBody())
                        .build()
                }
                .build()
        val account = AccountCredentials("sessionid=test-only", "test-device", "test-install")
        RecommendationFeed(http, account).page(1)
        assertEquals("api5-normal-sinfonlinea.fqnovel.com", requested!!.url.host)
        assertEquals(account.cookie, requested!!.header("Cookie"))
        assertEquals("test-device", requested!!.url.queryParameter("device_id"))
        RecommendationFeed(http).page(1)
        assertNull(requested!!.header("Cookie"))
        assertNull(requested!!.url.queryParameter("session_id"))
    }

    @Test
    fun categoryRecommendationsSendGenreWithoutFixedSortAndKeepCursor() {
        for (category in listOf(DramaCategory.COMIC, DramaCategory.REAL)) {
            val requests = mutableListOf<Request>()
            val payloads = mutableListOf<JSONObject>()
            val http =
                OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        val req = chain.request()
                        requests += req
                        val buffer = okio.Buffer()
                        req.body!!.writeTo(buffer)
                        val payload = JSONObject(buffer.readUtf8())
                        payloads += payload
                        val row =
                            JSONObject()
                                .put(
                                    "series_id",
                                    if (payload.optInt("offset") == 0) "201" else "202",
                                )
                                .put("series_title", "分类推荐作品")
                                .put("episode_cnt", 40)
                        val data =
                            JSONObject()
                                .put("video_data", JSONArray().put(row))
                                .put("has_more", true)
                                .put("next_offset", payload.optInt("offset") + 24)
                                .put("session_id", "test-category-session")
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(
                                JSONObject()
                                    .put("code", 0)
                                    .put("data", data)
                                    .toString()
                                    .toResponseBody()
                            )
                            .build()
                    }
                    .build()
            val cursor = RecommendationCursor()
            val feed = RecommendationFeed(http, cursor = cursor, category = category)
            assertEquals("201", feed.page(1).items.single().id)
            assertEquals("202", feed.page(2).items.single().id)
            val genre = if (category == DramaCategory.COMIC) "comic_series" else "short_play"
            assertEquals(
                genre,
                payloads[0].getJSONObject("select_items").getJSONArray("genre").getString(0),
            )
            assertEquals(0, payloads[0].getJSONObject("select_items").getJSONArray("sort").length())
            assertEquals(24, payloads[1].getInt("offset"))
            assertEquals("201", payloads[1].getString("filter_ids"))
            assertEquals("test-category-session", payloads[1].getString("session_id"))
            assertEquals("POST", requests[0].method)
            assertEquals("/reading/distribution/category/landpage/v/", requests[0].url.encodedPath)
            assertNotNull(requests[0].header("X-Argus"))
        }
    }

    @Test
    fun eachHomepageSourceRetainsItsOwnRefreshCursor() {
        val state = DramaFeedState()
        state.recommendationCursor.offset = 2
        state.select(DramaCategory.COMIC)
        assertEquals(0, state.recommendationCursor.offset)
        state.recommendationCursor.offset = 24
        state.select(DramaCategory.REAL)
        assertEquals(0, state.recommendationCursor.offset)
        state.recommendationCursor.offset = 48
        state.select(DramaCategory.COMIC)
        assertEquals(24, state.recommendationCursor.offset)
        state.recommend()
        assertEquals(2, state.recommendationCursor.offset)
        state.refresh(resetRecommendations = true)
        state.select(DramaCategory.REAL)
        assertEquals(0, state.recommendationCursor.offset)
    }

    @Test
    fun rejectsInvalidResponsesInsteadOfSilentlyFallingBackToCategories() {
        assertThrows(IOException::class.java) {
            RecommendationFeed.parse(JSONObject(response(tab = 8)))
        }
        assertThrows(IOException::class.java) {
            RecommendationFeed.parse(JSONObject().put("code", 1))
        }
        assertThrows(IOException::class.java) {
            RecommendationFeed.parse(JSONObject(response(id = "invalid")))
        }
        val value = JSONObject(response())
        value
            .getJSONObject("data")
            .getJSONArray("tab_item")
            .getJSONObject(0)
            .put("session_id", "bad\nsession")
        assertThrows(IOException::class.java) { RecommendationFeed.parse(value) }
    }

    @Test
    fun stateRefreshMovesToNextBatchAndAccountResetDropsSession() {
        val state = DramaFeedState()
        assertTrue(state.recommendation)
        state.recommendationCursor.session = "test-page-session"
        state.recommendationCursor.offset = 3
        state.refresh()
        assertEquals(3, state.recommendationCursor.offset)
        state.select(DramaCategory.REAL)
        assertFalse(state.recommendation)
        state.recommend()
        assertTrue(state.recommendation)
        state.refresh(resetRecommendations = true)
        assertEquals(0, state.recommendationCursor.offset)
        assertEquals("", state.recommendationCursor.session)
    }

    @Test
    fun oversizedPersonalizedFeedResponseIsAccepted() {
        // The signed-in mall tab returns multi-megabyte payloads; the old 2 MiB account
        // reader rejected them with "账号接口响应过大".
        val payload = response() + " ".repeat(3 * 1024 * 1024)
        val http =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(payload.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build()
        val page = RecommendationFeed(http).page(1)
        assertEquals(listOf("101"), page.items.map { it.id })
    }

    /** 服务端判定旧分页会话不合法（如 110）时，丢弃 session 从头拉一次，而不是持续报错。 */
    @Test
    fun stalePagingSessionIsDroppedAndRetriedFromTheStart() {
        val requests = mutableListOf<Request>()
        val http =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    requests += request
                    val body =
                        if (requests.size == 1) """{"code":110,"data":null}"""
                        else response("301")
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody())
                        .build()
                }
                .build()
        val cursor = RecommendationCursor()
        cursor.session = "stale-session"
        cursor.offset = 3
        val page = RecommendationFeed(http, cursor = cursor).page(1)
        assertEquals(1, page.items.size)
        assertEquals(2, requests.size)
        assertEquals("stale-session", requests[0].url.queryParameter("session_id"))
        assertNull(requests[1].url.queryParameter("session_id"))
        assertEquals("0", requests[1].url.queryParameter("offset"))
        assertEquals("1", requests[1].url.queryParameter("client_req_type"))
    }
}
