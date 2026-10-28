// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.windows

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

class CommentsController(
    private val fetchSocial: suspend (String, String) -> Social,
    private val fetchPage: suspend (String, String, String) -> CommentPage,
) {
    var social by mutableStateOf<Social?>(null)
        private set
    var socialLoading by mutableStateOf(false)
        private set
    var socialError by mutableStateOf<String?>(null)
        private set
    var items by mutableStateOf<List<Comment>>(emptyList())
        private set
    var total by mutableStateOf(0L)
        private set
    var pageLoading by mutableStateOf(false)
        private set
    var pageError by mutableStateOf<String?>(null)
        private set
    var hasMore by mutableStateOf(false)
        private set
    var loaded by mutableStateOf(false)
        private set

    private var cursor = ""
    private var key = ""

    fun clear() {
        key = ""
        cursor = ""
        social = null
        socialError = null
        socialLoading = false
        items = emptyList()
        total = 0
        pageError = null
        pageLoading = false
        hasMore = false
        loaded = false
    }

    suspend fun load(series: String, episode: String, force: Boolean = false) {
        val next = "$series/$episode"
        if (!force && key == next && (loaded || pageLoading)) return
        clear()
        key = next
        socialLoading = true
        pageLoading = true
        try {
            val value = fetchSocial(series, episode)
            if (key == next) {
                social = value
                socialError = null
            }
        } catch (_: Exception) {
            if (key == next) socialError = "整剧剧评暂时无法读取。"
        } finally {
            if (key == next) socialLoading = false
        }
        loadPage(series, episode, "")
    }

    suspend fun more(series: String, episode: String) {
        if (pageLoading || !hasMore) return
        pageLoading = true
        loadPage(series, episode, cursor)
    }

    private suspend fun loadPage(series: String, episode: String, from: String) {
        val next = "$series/$episode"
        try {
            val page = fetchPage(series, episode, from)
            if (key != next) return
            items =
                if (from.isEmpty()) page.items
                else {
                    val known = items.map { it.id }.toSet()
                    items + page.items.filter { it.id !in known }
                }
            total = page.total
            cursor = page.nextCursor
            hasMore = page.hasMore
            pageError = null
            loaded = true
        } catch (_: Exception) {
            if (key == next) pageError = "评论暂时无法读取，请稍后重试。"
        } finally {
            if (key == next) pageLoading = false
        }
    }
}

private val commentDate = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())

private fun commentTime(created: Long): String? {
    if (created <= 0) return null
    val millis = if (created > 1_000_000_000_000L) created else created * 1000
    return runCatching { commentDate.format(Instant.ofEpochMilli(millis)) }.getOrNull()
}

@Composable
fun CommentsPanel(state: AppState, modifier: Modifier = Modifier) {
    val controller = state.comments
    val series = state.detail?.series
    val episodes = state.detail?.episodes
    val index = state.episode
    val episodeID = index?.let { episodes?.getOrNull(it) }
    var tab by remember { mutableStateOf(0) }
    var filter by remember { mutableStateOf("") }
    var order by remember { mutableStateOf("原顺序") }
    var refresh by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(series?.id, episodeID, refresh) {
        if (series != null && episodeID != null) {
            controller.load(series.id, episodeID, force = refresh > 0)
        }
    }

    val currentItems = if (tab == 0) controller.items else controller.social?.reviews?.items.orEmpty()
    val visible =
        remember(currentItems, filter, order) {
            val filtered =
                currentItems.filter {
                    filter.isBlank() ||
                        it.content.contains(filter, ignoreCase = true) ||
                        it.author.contains(filter, ignoreCase = true)
                }
            when (order) {
                "赞数" -> filtered.sortedByDescending { it.likes }
                "时间" -> filtered.sortedByDescending { it.created }
                else -> filtered
            }
        }
    val loading = if (tab == 0) controller.pageLoading else controller.socialLoading
    val error = if (tab == 0) controller.pageError else controller.socialError
    val loaded = if (tab == 0) controller.loaded else controller.social != null

    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("评论与剧评", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = { refresh += 1 },
                enabled = episodeID != null,
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新评论")
            }
        }
        if (series != null && index != null) {
            Text(
                series.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
            )
            Text(
                "第 ${index + 1} 集 · 共 ${episodes?.size ?: 0} 集",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("本集评论") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("整剧剧评") })
        }

        when {
            episodeID == null ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("选择集数后可查看评论", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            !loaded && loading ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(26.dp))
                }
            !loaded && error != null ->
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(error, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { refresh += 1 }) { Text("重新读取") }
                }
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (tab == 0) "已加载 ${controller.items.size} 条 / 共 ${controller.total} 条"
                        else "${currentItems.size} 条节选 / 共 ${controller.social?.reviews?.total ?: 0} 条",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    if (tab == 1) {
                        val rating = controller.social?.rating ?: 0.0
                        if (rating > 0) Text("%.1f 分".format(rating), color = Accent)
                    }
                }
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    label = { Text("筛选已加载的评论") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                ChipRow(
                    options = listOf("原顺序" to "原顺序", "赞数" to "赞数", "时间" to "时间"),
                    selected = order,
                    onSelect = { order = it },
                )
                if (visible.isEmpty()) {
                    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                        Text(
                            if (filter.isBlank()) "暂无可显示的评论" else "没有匹配的评论",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(visible, key = { it.id }) { comment -> CommentRow(comment) }
                        if (tab == 0) {
                            item {
                                if (controller.pageLoading) {
                                    Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                    }
                                } else if (controller.pageError != null) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            controller.pageError.orEmpty(),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        TextButton(
                                            onClick = {
                                                scope.launch {
                                                    series?.let { controller.more(it.id, episodeID) }
                                                }
                                            },
                                        ) { Text("重试") }
                                    }
                                } else if (controller.hasMore) {
                                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                        Button(
                                            onClick = {
                                                scope.launch {
                                                    series?.let { controller.more(it.id, episodeID) }
                                                }
                                            },
                                        ) { Text("加载更多评论") }
                                    }
                                } else {
                                    Text(
                                        "已加载到末尾",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                Text(
                    if (tab == 0) "筛选和排序仅作用于已加载内容；发送、回复尚未接入，赞数只读。"
                    else "整剧剧评仅显示官网节选。发送、回复尚未接入，赞数只读。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val source = controller.social?.sourceURL.orEmpty()
                if (source.isNotBlank() && source.startsWith("https://hongguoduanju.com/")) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = {
                                runCatching { Desktop.getDesktop().browse(URI(source)) }
                            },
                        ) { Text("打开官方播放页") }
                        TextButton(
                            onClick = {
                                runCatching {
                                    Toolkit.getDefaultToolkit().systemClipboard.setContents(
                                        StringSelection(source),
                                        null,
                                    )
                                }
                            },
                        ) { Text("复制页面链接") }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommentRow(comment: Comment) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(comment.avatar, comment.author)
            Spacer(Modifier.width(8.dp))
            Text(
                comment.author.ifBlank { "红果用户" },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.Filled.ThumbUp,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(3.dp))
            Text(
                comment.likes.toString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(comment.content, style = MaterialTheme.typography.bodyMedium)
        commentTime(comment.created)?.let {
            Spacer(Modifier.height(2.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(10.dp))
        HorizontalDivider()
    }
}
