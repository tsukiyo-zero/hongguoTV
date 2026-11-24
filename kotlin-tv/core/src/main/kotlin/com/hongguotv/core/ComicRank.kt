// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/** Read the website's serialized data only; never execute its scripts. */
internal object ComicRank {
    fun parse(html: String, page: Int, board: RankBoard = RankBoard.COMIC): CatalogPage {
        try {
            val route =
                runCatching { ContentRepository.extractRouter(html) }.getOrNull()
                    ?.optJSONObject("loaderData")
                    ?.optJSONObject(board.loader)
            val updatedText = route?.str("updatedText").orEmpty()
            val inline = route?.optJSONObject("content")?.takeIf { it.has("isSuccess") }
            if (inline != null) {
                if (route.str("rankKey") != board.key || route.optInt("pageNum") != page)
                    throw IOException("剧集分页信息异常")
                return parsePayload(inline, page, updatedText)
            }

            val document = Jsoup.parse(html)
            for (tag in document.select("script[data-fn-name][data-fn-args]")) {
                val name = tag.attr("data-fn-name")
                if (name != "r" && name != "mergeLoaderData") continue
                val args = JSONArray(tag.attr("data-fn-args"))
                if (args.optString(0) != board.loader) continue
                val raw =
                    if (name == "r" && args.optString(1) == "content") args.opt(2)
                    else if (name == "mergeLoaderData") {
                        args
                            .optJSONArray(1)
                            ?.objects()
                            ?.firstOrNull {
                                it.str("key") == "content" && it.str("routerDataFnName") == "p"
                            }
                            ?.optJSONArray("routerDataFnArgs")
                            ?.opt(0)
                    } else null
                val payload =
                    when (raw) {
                        is JSONObject -> raw
                        is String -> JSONObject(raw)
                        else -> continue
                    }
                return parsePayload(payload, page, updatedText)
            }
            return renderedPage(document, route, page, board)
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("剧集榜单格式已变化", e)
        }
    }

    private fun parsePayload(payload: JSONObject, page: Int, updatedText: String): CatalogPage {
        if (!payload.optBoolean("isSuccess")) throw IOException("剧集榜单暂不可用")
        val rows = payload.optJSONArray("rankList") ?: throw IOException("剧集榜单格式已变化")
        val pagination =
            payload.optJSONObject("pagination") ?: throw IOException("剧集分页信息缺失")
        val totalPages = pagination.optInt("totalPages", 0)
        if (totalPages < 1 || pagination.optInt("pageNum", page) != page)
            throw IOException("剧集分页信息异常")
        val positions = linkedMapOf<String, RankPosition>()
        val items =
            rows
                .objects()
                .map { row ->
                    val count =
                        row.optInt(
                            "episodeCount",
                            row.optJSONArray("episodeVids")?.length() ?: 0,
                        )
                    val tags = row.optJSONArray("tags") ?: JSONArray()
                    val id = row.str("seriesId").ifEmpty { row.str("id") }
                    if (id !in positions)
                        positions[id] =
                            RankPosition(
                                row.optInt("rank").takeIf { it > 0 },
                                row.str("heatText"),
                                listOf(
                                        row.str("scoreText"),
                                        row.str("favoriteText"),
                                        row.str("likeText"),
                                    )
                                    .filter(String::isNotBlank)
                                    .joinToString(" · "),
                            )
                    Series(
                        id,
                        row.str("title"),
                        cleanUrl(row.str("cover")),
                        row.str("description"),
                        if (count > 0) "全 $count 集" else row.str("heatText"),
                        rankTopics((0 until minOf(tags.length(), 5)).mapNotNull { tags.opt(it) as? String }),
                    )
                }
                .filter { it.id.matches(Regex("[0-9]{1,30}")) && it.title.isNotBlank() }
                .distinctBy { it.id }
        return CatalogPage(
            items,
            items.isNotEmpty() && page < minOf(totalPages, 100),
            ComicRanking(
                positions.filterKeys { id -> items.any { it.id == id } },
                minOf(totalPages, 100),
                updatedText,
            ),
        )
    }

    // 结构化标签里用中点拼接演员或角色；只保留独立题材，避免把人名拆成筛选项。
    private fun rankTopics(tags: List<String>) =
        tags.map(String::trim)
            .filter { it.isNotBlank() && '·' !in it && it !in setOf("真人剧", "漫剧", "AI剧", "AI 剧") }
            .distinct()
            .joinToString(" · ")

    private fun renderedPage(
        document: Document,
        route: JSONObject?,
        page: Int,
        board: RankBoard,
    ): CatalogPage {
        // Some responses contain rendered cards while the deferred JSON is still unresolved.
        if (route == null) throw IOException("页面没有剧集榜单数据")
        if (route.str("rankKey") != board.key || route.optInt("pageNum") != page)
            throw IOException("剧集分页信息异常")
        val positions = linkedMapOf<String, RankPosition>()
        val items =
            document
                .select("article[aria-labelledby^=rank-title-]")
                .mapNotNull { article ->
                    val heading = article.attr("aria-labelledby")
                    val id = heading.removePrefix("rank-title-")
                    val title = article.getElementById(heading)?.text().orEmpty()
                    if (!id.matches(Regex("[0-9]{1,30}")) || title.isBlank()) return@mapNotNull null
                    if (id !in positions)
                        positions[id] =
                            RankPosition(
                                article
                                    .selectFirst("[class*=badge-number-]")
                                    ?.text()
                                    ?.toIntOrNull()
                                    ?.takeIf { it > 0 },
                                article.selectFirst("p[class*=metrics-]")?.text().orEmpty(),
                            )
                    Series(
                        id,
                        title,
                        article.selectFirst("img[src]")?.attr("src").orEmpty(),
                        article.selectFirst("p[class*=description-]")?.text().orEmpty(),
                        article.selectFirst("p[class*=metrics-]")?.text().orEmpty(),
                        rankTopics(article.select("p[class*=categories-] > span").take(5).map { it.text() }),
                    )
                }
                .distinctBy { it.id }
        if (items.isEmpty()) throw IOException("页面没有剧集榜单数据")
        val paging = document.selectFirst("nav[aria-label=榜单分页]") ?: throw IOException("剧集分页信息缺失")
        if (paging.selectFirst("[aria-current=page]")?.text()?.toIntOrNull() != page)
            throw IOException("剧集分页信息异常")
        val lastPage =
            maxOf(
                page,
                paging
                    .select("a[href]")
                    .mapNotNull {
                        it.attr("href")
                            .substringAfter("?page=", "")
                            .substringBefore('&')
                            .toIntOrNull()
                    }
                    .maxOrNull() ?: page,
            )
        return CatalogPage(
            items,
            page < minOf(lastPage, 100),
            ComicRanking(positions, minOf(lastPage, 100), route.str("updatedText")),
        )
    }
}
