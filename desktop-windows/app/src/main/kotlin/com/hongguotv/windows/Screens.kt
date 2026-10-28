// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.windows

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun HomeScreen(state: AppState) {
    val detail = state.detail
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("发现下一部好剧", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(18.dp))
            ChipRow(
                options = listOf("ALL" to "推荐", "COMIC" to "漫剧", "REAL" to "真人剧"),
                selected = state.homeCategory,
                enabled = !state.busy,
                onSelect = { state.selectHomeCategory(it) },
            )
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { state.loadHome() }, enabled = !state.busy) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("换一批")
            }
        }
        if (detail != null && state.showDetail) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                VideoFrame(state, Modifier.weight(1f).fillMaxHeight())
                Column(
                    Modifier
                        .width(300.dp)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("首集试播", color = Accent, style = MaterialTheme.typography.labelLarge)
                    Text(
                        detail.series.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        detail.series.badge,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        detail.series.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Button(
                        onClick = { state.watchFull() },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.PlayCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("观看全集 · 共 ${detail.episodes.size} 集")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { state.library.toggleFavorite(detail.series) }) {
                            Icon(Icons.Filled.Star, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (state.library.isFavorite(detail.series.id)) "取消收藏" else "收藏")
                        }
                        OutlinedButton(
                            onClick = { state.showComments = true },
                            enabled = state.episode != null,
                        ) { Text("评论") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { state.moveRecommendation(-1) },
                            enabled = !state.busy,
                        ) { Text("上一部") }
                        OutlinedButton(
                            onClick = { state.moveRecommendation(1) },
                            enabled = !state.busy,
                        ) { Text("下一部") }
                    }
                    Text(
                        "试播不写入观看历史；观看全集后开始记录进度。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                if (state.showComments) {
                    CommentsPanel(state, Modifier.width(420.dp).fillMaxHeight())
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("本批推荐", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.width(12.dp))
                Text(
                    "共 ${state.catalogRows.size} 部 · 看完可换一批",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyRow(Modifier.height(40.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.catalogRows, key = { it.id }) { series ->
                    val selected = series.id == detail.series.id
                    Surface(
                        color = if (selected) Accent.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.clickable(enabled = !state.busy) {
                            state.open(series)
                        },
                    ) {
                        Text(
                            series.title,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            color = if (selected) Accent else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        } else {
            EmptyState(
                icon = Icons.Filled.PlayCircle,
                title = if (state.busy) "正在准备试播…" else "看看下一部好故事",
                detail = "点“换一批”获取推荐，或到搜索、找剧和榜单里挑选。",
            ) {
                Button(onClick = { state.loadHome() }, enabled = !state.busy) { Text("开始刷剧") }
            }
        }
    }
}

@Composable
fun LibraryScreen(state: AppState) {
    val section = state.section
    val items =
        when (section) {
            "本机收藏" -> state.library.favorites
            "本机历史" -> state.library.history.map { it.series }
            else -> state.catalogRows
        }
    val cloudByID = remember(state.cloudRows) { state.cloudRows.associateBy { it.series.id } }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionTitle(
            title = section,
            subtitle =
                when (section) {
                    "搜索" -> "${items.size} 部剧"
                    "找剧", "榜单" -> "共 ${items.size} 部 · 可按类型和题材筛选"
                    "本机收藏" -> "${items.size} 部本机收藏"
                    "本机历史" -> "${items.size} 条本机观看记录"
                    else -> "${items.size} 部剧"
                },
            trailing = {
                when (section) {
                    "找剧" -> OutlinedButton(onClick = { state.loadCatalog(reset = true) }, enabled = !state.busy) {
                        Text("刷新")
                    }
                    "榜单" -> OutlinedButton(onClick = { state.loadRank() }, enabled = !state.busy) {
                        Text("刷新")
                    }
                }
            },
        )
        when (section) {
            "找剧" ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChipRow(
                        options = listOf("COMIC" to "漫剧", "REAL" to "真人剧", "AI" to "AI 剧"),
                        selected = state.category,
                        enabled = !state.busy,
                        onSelect = { state.selectCategory(it) },
                    )
                    TopicChips(state)
                }
            "榜单" ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChipRow(
                        options =
                            listOf(
                                "ALL" to "综合",
                                "COMIC" to "漫剧",
                                "REAL" to "真人剧",
                                "AI" to "AI 剧",
                            ),
                        selected = state.board,
                        enabled = !state.busy,
                        onSelect = { state.selectBoard(it) },
                    )
                    TopicChips(state)
                }
            "搜索" -> Unit
            else -> Unit
        }
        Box(Modifier.weight(1f)) {
            when {
                items.isEmpty() && (state.busy) ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    }
                items.isEmpty() && section == "搜索" ->
                    EmptyState(
                        icon = Icons.Filled.Search,
                        title = if (state.query.isBlank()) "找一部想看的剧" else "暂无搜索结果",
                        detail = "在窗口右上角输入剧名，按回车搜索。",
                    )
                items.isEmpty() && section == "本机收藏" ->
                    EmptyState(
                        icon = Icons.Filled.Star,
                        title = "还没有本机收藏",
                        detail = "在剧集卡片或详情页点星标即可加入本机收藏。",
                    )
                items.isEmpty() && section == "本机历史" ->
                    EmptyState(
                        icon = Icons.Filled.Movie,
                        title = "还没有本机观看记录",
                        detail = "正式观看后会自动记录进度；试播不会写入历史。",
                    )
                items.isEmpty() ->
                    EmptyState(
                        icon = Icons.Filled.CloudOff,
                        title = "暂无内容",
                        detail = "可以刷新，或换一个类型和题材。",
                    )
                else ->
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(168.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalArrangement = Arrangement.spacedBy(22.dp),
                    ) {
                        items(items, key = { it.id }) { series ->
                            EventCard(state, series, cloudByID[series.id])
                        }
                        if (section == "搜索" && state.hasMore) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                                    OutlinedButton(
                                        onClick = { state.search(more = true) },
                                        enabled = !state.busy,
                                    ) { Text("加载更多") }
                                }
                            }
                        }
                        if (section == "找剧" && state.catalogMore) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                                    OutlinedButton(
                                        onClick = { state.loadCatalog(reset = false) },
                                        enabled = !state.busy,
                                    ) { Text("加载更多") }
                                }
                            }
                        }
                    }
            }
        }
    }
}

@Composable
private fun TopicChips(state: AppState) {
    if (state.topics.isEmpty()) return
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            FilterChipProxy("", "全部题材", state.topic, !state.busy) { state.selectTopic(it) }
        }
        items(state.topics, key = { it.id }) { topic ->
            FilterChipProxy(topic.id, topic.label, state.topic, !state.busy) { state.selectTopic(it) }
        }
    }
}

@Composable
private fun FilterChipProxy(
    value: String,
    label: String,
    selected: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    ChipRow(listOf(value to label), selected, enabled, onSelect)
}

@Composable
private fun EventCard(state: AppState, series: Series, cloud: CloudEntry?) {
    val rank =
        if (state.section == "榜单") state.rankPositions[series.id] else null
    SeriesCard(state, series, cloud, rank) {
        if (state.section == "本机收藏" || state.section == "本机历史" || state.section == "搜索" || state.section == "找剧" || state.section == "榜单") {
            state.open(series)
        }
    }
}

@Composable
fun DetailScreen(state: AppState) {
    val detail = state.detail ?: return
    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        detail.series.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        detail.series.tags.ifBlank { detail.series.badge },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.preview) {
                    OutlinedButton(onClick = { state.watchFull() }, enabled = !state.busy) { Text("观看全集") }
                } else {
                    OutlinedButton(
                        onClick = { state.play((state.episode ?: -1) + 1) },
                        enabled = !state.busy && (state.episode ?: -1) + 1 < detail.episodes.size,
                    ) { Text("下一集") }
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { state.backToLibrary() }) { Text("返回片单") }
            }
            VideoFrame(state, Modifier.fillMaxWidth().aspectRatio(16f / 9f + 0.16f))
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = { state.resume() }, enabled = !state.busy) { Text(state.resumeLabel) }
                OutlinedButton(
                    onClick = { state.library.toggleFavorite(detail.series) },
                ) {
                    Icon(Icons.Filled.Star, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (state.library.isFavorite(detail.series.id)) "取消本机收藏" else "加入本机收藏")
                }
                OutlinedButton(
                    onClick = { state.showComments = !state.showComments },
                    enabled = state.episode != null,
                ) { Text(if (state.showComments) "隐藏评论" else "评论与剧评") }
                if (state.account?.loggedIn == true) {
                    OutlinedButton(onClick = { state.cloudFavorite(detail.series, true) }) {
                        Text("加入账户收藏")
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "自动下一集",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ChipRow(
                    options = listOf("on" to "开", "off" to "关"),
                    selected = if (state.preferences.autoNext) "on" else "off",
                    onSelect = { state.preferences.updateAutoNext(it == "on") },
                )
            }
            Text(
                detail.series.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${if (state.preview) "首集试播 · 选集进入正式观看" else "选集"} · 共 ${detail.episodes.size} 集",
                style = MaterialTheme.typography.titleSmall,
            )
            LazyVerticalGrid(
                columns = GridCells.Adaptive(58.dp),
                modifier = Modifier.fillMaxWidth().height(150.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(detail.episodes.size) { index ->
                    val active = state.episode == index
                    Surface(
                        color =
                            if (active) Accent
                            else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        modifier =
                            Modifier
                                .height(38.dp)
                                .clickable(enabled = !state.busy) { state.play(index) },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                "${index + 1}",
                                color = if (active) androidx.compose.ui.graphics.Color.Black else MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
            if (state.related.isNotEmpty()) {
                Text("相关作品", style = MaterialTheme.typography.titleSmall)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.related, key = { it.series.id }) { work ->
                        Column(
                            Modifier
                                .width(150.dp)
                                .clickable(
                                    enabled =
                                        !state.busy &&
                                            (!work.upcoming || state.account?.loggedIn == true),
                                ) {
                                    if (work.upcoming) state.reserve(work) else state.open(work.series)
                                },
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Cover(
                                work.series.cover,
                                work.series.title,
                                Modifier.fillMaxWidth().height(196.dp).clip(RoundedCornerShape(10.dp)),
                            )
                            Text(
                                work.series.title,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 2,
                                minLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                if (work.upcoming) "待上线 · 预约" else work.season,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            } else if (state.relatedMessage.isNotEmpty()) {
                Text(
                    state.relatedMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (state.showComments) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.width(430.dp).fillMaxHeight(),
            ) {
                CommentsPanel(state)
            }
        }
    }
}

@Composable
fun AboutScreen(state: AppState) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("关于果映 TV", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "Windows 桌面客户端 ${AppInfo.version}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "本客户端复用 TV 端的同一套解析内核，通过仅监听 127.0.0.1 的本机服务读取内容；" +
                "账号凭据只保存在本机，并用 Windows DPAPI 绑定当前用户加密。",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("播放器", style = MaterialTheme.typography.titleMedium)
        Text(
            "使用 libVLC 播放，客户端安装包会自带 VLC 运行库（LGPLv2.1+）。" +
                "若自行编译运行，请安装 VLC 或在“我的”里指定 VLC 目录。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("暂未支持", style = MaterialTheme.typography.titleMedium)
        Text(
            "弹幕画面叠加、自动更新、账号预约与评论发布暂未接入；评论与剧评可只读浏览。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("项目与许可", style = MaterialTheme.typography.titleMedium)
        Text(
            "源码以 GPL-3.0-only 发布，播放器组件 vlcj 为 GPL-3.0，libVLC 为 LGPLv2.1+。" +
                "请遵守对应许可证并在分发时保留声明。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = {
                    runCatching {
                        java.awt.Desktop.getDesktop().browse(java.net.URI("https://github.com/tsukiyo-zero/hongguoTV"))
                    }
                },
            ) { Text("打开项目主页") }
            OutlinedButton(onClick = { state.navigate("我的") }) { Text("返回我的") }
        }
    }
}
