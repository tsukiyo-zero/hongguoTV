// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.windows

import org.json.JSONArray
import org.json.JSONObject

/** Wire models of the loopback bridge shared with the Mac client. */
data class Series(
    val id: String,
    val title: String,
    val cover: String = "",
    val description: String = "",
    val badge: String = "",
    val tags: String = "",
) {
    fun toJson() =
        JSONObject()
            .put("id", id)
            .put("title", title)
            .put("cover", cover)
            .put("description", description)
            .put("badge", badge)
            .put("tags", tags)

    companion object {
        fun from(value: JSONObject) =
            Series(
                id = value.optString("id"),
                title = value.optString("title"),
                cover = value.optString("cover"),
                description = value.optString("description"),
                badge = value.optString("badge"),
                tags = value.optString("tags"),
            )
    }
}

data class Detail(val series: Series, val episodes: List<String>)

data class Topic(val id: String, val label: String)

data class RankPosition(val rank: Int?, val heat: String)

data class RelatedEntry(val series: Series, val season: String, val upcoming: Boolean)

data class PlayResult(val url: String, val quality: String)

data class CatalogResult(
    val items: List<Series>,
    val hasMore: Boolean,
    val topics: List<Topic> = emptyList(),
    val positions: Map<String, RankPosition> = emptyMap(),
)

data class Comment(
    val id: String,
    val author: String,
    val avatar: String,
    val content: String,
    val created: Long,
    val likes: Long,
    val rating: Double,
)

data class CommentList(val total: Long, val items: List<Comment>)

data class Social(
    val seriesID: String,
    val episodeID: String,
    val reviews: CommentList,
    val comments: CommentList,
    val rating: Double,
    val ratingCount: Long,
    val sourceURL: String,
    val limited: Boolean,
)

data class CommentPage(
    val seriesID: String,
    val episodeID: String,
    val total: Long,
    val items: List<Comment>,
    val nextCursor: String,
    val hasMore: Boolean,
)

data class AccountInfo(
    val loggedIn: Boolean,
    val id: String?,
    val nickname: String?,
    val credentials: String?,
) {
    companion object {
        fun from(value: JSONObject) =
            AccountInfo(
                loggedIn = value.optBoolean("loggedIn"),
                id = value.optString("id").takeIf { it.isNotEmpty() },
                nickname = value.optString("nickname").takeIf { it.isNotEmpty() },
                credentials = value.optString("credentials").takeIf { it.isNotEmpty() },
            )
    }
}

data class CloudEntry(
    val series: Series,
    val episodeID: String? = null,
    val episodeIndex: Int? = null,
    val position: Double = 0.0,
    val duration: Double = 0.0,
    val online: Boolean? = null,
    val publishText: String? = null,
)

data class TimedComment(val id: String, val text: String, val timeMS: Long)

data class TimedPage(
    val seriesID: String,
    val episodeID: String,
    val startMS: Long,
    val nextMS: Long,
    val items: List<TimedComment>,
)

// ---- JSON helpers -------------------------------------------------------------------------

internal fun JSONArray.objects(): List<JSONObject> =
    (0 until length()).mapNotNull { optJSONObject(it) }

internal fun JSONArray.series(): List<Series> = objects().map(Series::from).filter { it.id.isNotEmpty() }

internal fun JSONArray.strings(): List<String> = (0 until length()).mapNotNull { optString(it) }

internal fun JSONObject.seriesList(): List<Series> = optJSONArray("items")?.series().orEmpty()

internal fun JSONObject.topics(): List<Topic> =
    optJSONArray("topics").orEmpty().objects().mapNotNull { value ->
        val id = value.optString("id")
        if (id.isEmpty()) null else Topic(id, value.optString("label", id))
    }

internal fun JSONArray?.orEmpty(): JSONArray = this ?: JSONArray()

internal fun JSONObject.toDetail(): Detail =
    Detail(
        series = Series.from(getJSONObject("series")),
        episodes = optJSONArray("episodes").strings(),
    )

internal fun JSONObject.toComments(): List<Comment> =
    optJSONArray("items").orEmpty().objects().mapNotNull { row ->
        val id = row.optString("id")
        val content = row.optString("content")
        if (id.isEmpty()) null
        else
            Comment(
                id = id,
                author = row.optString("author"),
                avatar = row.optString("avatar"),
                content = content,
                created = row.optLong("created", 0L),
                likes = row.optLong("likes", 0L),
                rating = row.optDouble("rating", 0.0),
            )
    }

internal fun JSONObject.toSocial(): Social {
    fun list(key: String): CommentList {
        val value = optJSONObject(key) ?: return CommentList(0, emptyList())
        return CommentList(value.optLong("total", 0), value.toComments())
    }
    return Social(
        seriesID = optString("seriesID"),
        episodeID = optString("episodeID"),
        reviews = list("reviews"),
        comments = list("comments"),
        rating = optDouble("rating", 0.0),
        ratingCount = optLong("ratingCount", 0),
        sourceURL = optString("sourceURL"),
        limited = optBoolean("limited", true),
    )
}

internal fun JSONObject.toCommentPage(): CommentPage =
    CommentPage(
        seriesID = optString("seriesID"),
        episodeID = optString("episodeID"),
        total = optLong("total", 0),
        items = toComments(),
        nextCursor = optString("nextCursor"),
        hasMore = optBoolean("hasMore"),
    )

internal fun JSONObject.toCloudEntries(): List<CloudEntry> =
    optJSONArray("items").orEmpty().objects().mapNotNull { value ->
        val series = value.optJSONObject("series") ?: return@mapNotNull null
        CloudEntry(
            series = Series.from(series),
            episodeID = value.optString("episodeID").takeIf { it.isNotEmpty() },
            episodeIndex = if (value.has("episodeIndex")) value.optInt("episodeIndex") else null,
            position = value.optDouble("position", 0.0),
            duration = value.optDouble("duration", 0.0),
            online = if (value.has("online")) value.optBoolean("online") else null,
            publishText = value.optString("publishText").takeIf { it.isNotEmpty() },
        )
    }

internal fun JSONObject.toTimedPage(): TimedPage =
    TimedPage(
        seriesID = optString("seriesID"),
        episodeID = optString("episodeID"),
        startMS = optLong("startMS"),
        nextMS = optLong("nextMS"),
        items =
            optJSONArray("items").orEmpty().objects().mapNotNull { row ->
                val id = row.optString("id")
                val text = row.optString("text")
                if (id.isEmpty() || text.isEmpty()) null
                else TimedComment(id, text, row.optLong("timeMS"))
            },
    )
