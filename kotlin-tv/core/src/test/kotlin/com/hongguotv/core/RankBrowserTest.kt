package com.hongguotv.core

import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RankBrowserTest {
    private fun page(board: RankBoard, p: Int, total: Int = 2): String {
        val row =
            JSONObject()
                .put("seriesId", (100 + p).toString())
                .put("title", "剧$p")
                .put("rank", 20 + p)
                .put("heatText", "100万热度")
                .put("likeText", "2万点赞")
                .put("scoreText", "评分9.0")
                .put("tags", JSONArray().put(if (p == 1) "穿越" else "重生"))
                .put("episodeCount", 12)
        val payload =
            JSONObject()
                .put("isSuccess", true)
                .put("rankList", JSONArray().put(row))
                .put("pagination", JSONObject().put("pageNum", p).put("totalPages", total))
        val args = JSONArray().put(board.loader).put("content").put(payload)
        return "<script data-fn-name=\"r\" data-fn-args=\"${args.toString().replace("\"","&quot;")}\"></script>"
    }

    @Test
    fun readsInlineTopicsEvenWhenRenderedLabelsArePlainText() {
        val board = RankBoard.REAL
        val html = inlineRank(board, 2, board.key) +
            """<article aria-labelledby="rank-title-123"><h2 id="rank-title-123">测试作品</h2>
                <p class="pc-categories-demo">爱情 · 家庭 · 演员甲 · 演员乙</p></article>"""
        val parsed = ComicRank.parse(html, 2, board)
        val snapshot = RankSnapshot(board, parsed.items, parsed.ranking!!)
        assertEquals(setOf("爱情", "家庭"), snapshot.topics.toSet())
        assertEquals("123", snapshot.filtered("家庭").single().id)
        assertTrue(snapshot.filtered("演员甲").isEmpty())
        assertEquals(26, snapshot.ranking.positions["123"]?.rank)
        assertEquals(5, snapshot.ranking.totalPages)
        assertEquals("今日已更新", snapshot.ranking.updatedText)
    }

    @Test
    fun rejectsInlinePayloadForWrongPageOrBoard() {
        val board = RankBoard.REAL
        assertThrows(IOException::class.java) { ComicRank.parse(inlineRank(board, 2, board.key), 1, board) }
        assertThrows(IOException::class.java) { ComicRank.parse(inlineRank(board, 2, "comic"), 2, board) }
    }

    private fun inlineRank(board: RankBoard, page: Int, key: String): String =
        """<script>_ROUTER_DATA = {"loaderData":{"${board.loader}":{"rankKey":"$key","pageNum":$page,"updatedText":"今日已更新","content":{"isSuccess":true,"rankList":[{"seriesId":"123","title":"测试作品","rank":26,"tags":["爱情","家庭","演员甲 · 演员乙","真人剧","123"]}],"pagination":{"pageNum":$page,"totalPages":5}}}}};</script>"""

    @Test
    fun allVerifiedBoardsUseTheirOwnRoutesAndIncludeLaterPageTopics() {
        for (board in RankBoard.entries) {
            var requests = 0
            val http =
                OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        val request = chain.request()
                        requests++
                        assertEquals("/rank/${board.route}", request.url.encodedPath)
                        val p = request.url.queryParameter("page")!!.toInt()
                        Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(page(board, p).toResponseBody())
                            .build()
                    }
                    .build()
            val snapshot = ContentRepository(http).rankSnapshot(board)
            assertEquals(2, requests)
            assertEquals(listOf("穿越", "重生"), snapshot.topics)
            assertEquals("102", snapshot.filtered("重生").single().id)
            assertEquals(22, snapshot.ranking.positions["102"]?.rank)
            assertTrue(snapshot.ranking.positions["102"]!!.metrics.contains("2万点赞"))
            assertTrue(snapshot.filtered("穿").isEmpty())
            assertEquals(2, snapshot.filtered("").size)
        }
    }

    @Test
    fun wrongBoardNeverFallsBackToAnotherRanking() {
        assertThrows(IOException::class.java) {
            ComicRank.parse(page(RankBoard.REAL, 1), 1, RankBoard.AI)
        }
    }

    @Test
    fun incompleteSnapshotFailsInsteadOfSilentlyFilteringOnlyFirstPage() {
        val http =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    if (request.url.queryParameter("page") == "2") throw IOException("offline")
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(page(RankBoard.ALL, 1).toResponseBody())
                        .build()
                }
                .build()
        assertThrows(IOException::class.java) {
            ContentRepository(http).rankSnapshot(RankBoard.ALL)
        }
        val tooLarge =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(page(RankBoard.ALL, 1, 6).toResponseBody())
                        .build()
                }
                .build()
        assertThrows(IOException::class.java) {
            ContentRepository(tooLarge).rankSnapshot(RankBoard.ALL)
        }
    }

    @Test
    fun numericMetadataIsNotExposedAsATopic() {
        val snapshot =
            RankSnapshot(
                RankBoard.COMIC,
                listOf(Series("1", "剧", tags = "205 · 系统 · 穿越")),
                ComicRanking(emptyMap(), 1, ""),
            )
        assertFalse(snapshot.topics.contains("205"))
        assertTrue(snapshot.filtered("205").isEmpty())
        assertEquals("1", snapshot.filtered("系统").single().id)
    }

    @Test
    fun changingBoardResetsTopicPageAndFocusWithoutDiscardingOtherSnapshots() {
        val state = RankBrowserState()
        state.topic = "穿越"
        state.page = 3
        state.selectedId = "123"
        state.select(RankBoard.AI)
        assertEquals("", state.topic)
        assertEquals(1, state.page)
        assertEquals("", state.selectedId)
        state.page = 2
        state.filter("系统")
        assertEquals(1, state.page)
    }
}
