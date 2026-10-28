// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.windows

import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {
    @Test
    fun detailKeepsEpisodesAndSeries() {
        val detail =
            JSONObject(
                """
                {"series":{"id":"7686","title":"双生","cover":"c","description":"d","badge":"全2集","tags":"古装"},
                 "episodes":["1001","1002"]}
                """.trimIndent(),
            ).toDetail()
        assertEquals("7686", detail.series.id)
        assertEquals("古装", detail.series.tags)
        assertEquals(listOf("1001", "1002"), detail.episodes)
    }

    @Test
    fun socialParsesReviewsAndComments() {
        val social =
            JSONObject(
                """
                {"seriesID":"700","episodeID":"900","rating":8.5,"ratingCount":12,
                 "sourceURL":"https://hongguoduanju.com/player/700/900","limited":true,
                 "reviews":{"total":1,"items":[{"id":"r1","author":"甲","avatar":"","content":"好看","created":1700000000,"likes":3,"rating":9}]},
                 "comments":{"total":0,"items":[]}}
                """.trimIndent(),
            ).toSocial()
        assertEquals("700", social.seriesID)
        assertEquals(1, social.reviews.items.size)
        assertEquals("好看", social.reviews.items[0].content)
        assertTrue(social.comments.items.isEmpty())
        assertEquals(8.5, social.rating, 0.0)
    }

    @Test
    fun commentPageKeepsCursorOnlyWhenMore() {
        val page =
            JSONObject(
                """
                {"seriesID":"700","episodeID":"900","total":42,"hasMore":true,
                 "nextCursor":"{\"session_id\":\"s\",\"offset\":20}",
                 "items":[{"id":"c1","author":"乙","avatar":"","content":"继续","created":0,"likes":1}]}
                """.trimIndent(),
            ).toCommentPage()
        assertEquals(42, page.total)
        assertTrue(page.hasMore)
        assertTrue(page.nextCursor.contains("offset"))
        assertEquals("c1", page.items.single().id)
    }

    @Test
    fun cloudEntriesParseHistoryAndPendingReservations() {
        val entries =
            JSONObject(
                """
                {"items":[
                  {"series":{"id":"1","title":"已看"},"episodeID":"e1","episodeIndex":2,"position":12.5,"duration":90.0},
                  {"series":{"id":"2","title":"待上线"},"online":false,"publishText":"10 月 30 日上线"}
                ]}
                """.trimIndent(),
            ).toCloudEntries()
        assertEquals(2, entries.size)
        assertEquals(2, entries[0].episodeIndex)
        assertEquals(false, entries[1].online)
        assertNull(entries[1].episodeID)
    }

    @Test
    fun timedPageParsesDanmakuItems() {
        val page =
            JSONObject(
                """
                {"seriesID":"1","episodeID":"2","startMS":0,"nextMS":30000,
                 "items":[{"id":"d1","text":"前方高能","timeMS":1200}]}
                """.trimIndent(),
            ).toTimedPage()
        assertEquals(30000, page.nextMS)
        assertEquals("前方高能", page.items.single().text)
    }
}

class ImagesTest {
    @Test
    fun heicCoverFallsBackToPublicJpegTemplate() {
        val heic =
            "https://p3-reading-sign.fqnovelpic.com/novel-pic/c37eb63db868db08eb95e0d8bacd0129" +
                "~tplv-81nmtwyey9-superreso-aifit:400:0.heic?lk3s=64477e16&x-expires=1&x-signature=a"
        assertEquals(
            "https://p3-novel.byteimg.com/novel-pic/c37eb63db868db08eb95e0d8bacd0129~tplv-shrink:640:0.image",
            Images.jpegFallback(heic),
        )
    }

    @Test
    fun jpegCoverNeedsNoRewrite() {
        assertNull(Images.jpegFallback("https://p3-novel.byteimg.com/novel-pic/abc~tplv-shrink:640:0.image"))
    }
}

class LocalLibraryTest {
    private fun tempLibrary(): Pair<LocalLibrary, File> {
        val file = File(System.getProperty("java.io.tmpdir"), "guoying-${UUID.randomUUID()}.json")
        return LocalLibrary(file) to file
    }

    private val series = Series("1", "测试剧", "cover", "desc", "全2集", "古装")

    @Test
    fun favoritesRoundTripThroughDisk() {
        val (library, file) = tempLibrary()
        try {
            library.toggleFavorite(series)
            assertTrue(library.isFavorite("1"))
            val reloaded = LocalLibrary(file)
            assertEquals(listOf(series), reloaded.favorites)
            reloaded.toggleFavorite(series)
            assertFalse(LocalLibrary(file).isFavorite("1"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun historyKeepsNewestRecordPerSeries() {
        val (library, file) = tempLibrary()
        try {
            library.record(WatchRecord(series, "e1", 0, 10.0, 100.0, false, 1L))
            library.record(WatchRecord(series, "e2", 1, 20.0, 100.0, false, 2L))
            val saved = LocalLibrary(file).recordFor("1")
            assertEquals("e2", saved?.episodeID)
            assertEquals(1, LocalLibrary(file).history.size)
        } finally {
            file.delete()
        }
    }

    @Test
    fun invalidRecordIsIgnored() {
        val (library, file) = tempLibrary()
        try {
            library.record(WatchRecord(series, "", 0, 1.0, 2.0, false, 1L))
            library.record(WatchRecord(series, "e1", 0, Double.NaN, 2.0, false, 1L))
            assertTrue(library.history.isEmpty())
        } finally {
            file.delete()
        }
    }
}

class FormatTest {
    @Test
    fun timeFormattingHandlesHoursAndInvalidValues() {
        assertEquals("00:00", formatTime(0.0))
        assertEquals("00:00", formatTime(Double.NaN))
        assertEquals("01:05", formatTime(65.0))
        assertEquals("1:01:05", formatTime(3665.0))
    }

    @Test
    fun versionResourceIsPresent() {
        assertTrue(AppInfo.version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")))
    }
}
