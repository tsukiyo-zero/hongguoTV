// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.windows

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay

fun main() = application {
    val state = remember { AppState() }
    LaunchedEffect(Unit) { state.start() }
    Window(
        onCloseRequest = {
            state.shutdown()
            exitApplication()
        },
        title = "果映 TV",
        state = rememberWindowState(width = 1120.dp, height = 720.dp),
    ) {
        LaunchedEffect(Unit) { window.minimumSize = java.awt.Dimension(960, 620) }
        GuoyingTheme { Root(state) }
    }
}

@Composable
private fun Root(state: AppState) {
    // VLC state is polled; nothing here touches libVLC from a player callback thread.
    LaunchedEffect(state.showDetail, state.episode, state.section) {
        while (true) {
            if (state.showDetail && state.episode != null) state.refreshPlayerState()
            delay(500)
        }
    }
    LaunchedEffect(state.showDetail, state.episode, state.section) {
        while (true) {
            delay(5000)
            if (state.showDetail && state.episode != null) state.saveProgress()
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Row(Modifier.fillMaxSize()) {
            Sidebar(state)
            Box(Modifier.width(1.dp).fillMaxHeight().background(Color(0xFF262A32)))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                TopBar(state)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        state.fatal != null -> FatalScreen(state)
                        state.section == "首页" -> HomeScreen(state)
                        state.showDetail -> DetailScreen(state)
                        state.section == "我的" -> MyScreen(state)
                        state.section == "关于" -> AboutScreen(state)
                        state.section == "账户登录" -> LoginScreen(state)
                        state.section in AppState.ACCOUNT_SECTIONS -> CloudScreen(state)
                        else -> LibraryScreen(state)
                    }
                }
                StatusBar(state)
            }
        }
    }
}

private data class NavEntry(val label: String, val icon: ImageVector)

private val discoveryNav =
    listOf(
        NavEntry("首页", Icons.Filled.Home),
        NavEntry("搜索", Icons.Filled.Search),
        NavEntry("找剧", Icons.Filled.GridView),
        NavEntry("榜单", Icons.Filled.BarChart),
        NavEntry("我的", Icons.Filled.Person),
    )

private val localNav = listOf(NavEntry("本机收藏", Icons.Filled.Star), NavEntry("本机历史", Icons.Filled.History))

private val accountNav =
    listOf(
        NavEntry("账户历史", Icons.Filled.History),
        NavEntry("账户收藏", Icons.Filled.Star),
        NavEntry("已上线预约", Icons.Filled.PlayCircle),
        NavEntry("待上线预约", Icons.Filled.CalendarMonth),
        NavEntry("账户登录", Icons.AutoMirrored.Filled.Login),
    )

@Composable
private fun Sidebar(state: AppState) {
    Column(
        Modifier
            .width(200.dp)
            .fillMaxHeight()
            .padding(vertical = 16.dp),
    ) {
        Column(Modifier.padding(horizontal = 18.dp)) {
            Text("果映 TV", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "Windows",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(18.dp))
        NavGroup("发现", discoveryNav, state)
        Spacer(Modifier.height(10.dp))
        NavGroup("本机片单", localNav, state)
        Spacer(Modifier.height(10.dp))
        NavGroup("红果账号", accountNav, state)
        Spacer(Modifier.weight(1f))
        NavItem(NavEntry("关于", Icons.Filled.Info), state)
        Text(
            "v${AppInfo.version}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun NavGroup(title: String, entries: List<NavEntry>, state: AppState) {
    Text(
        title,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
    )
    entries.forEach { entry -> NavItem(entry, state) }
}

@Composable
private fun NavItem(entry: NavEntry, state: AppState) {
    val selected = state.section == entry.label
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Accent.copy(alpha = 0.16f) else Color.Transparent)
            .clickable { state.navigate(entry.label) }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            entry.icon,
            contentDescription = null,
            tint = if (selected) Accent else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            entry.label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) Accent else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun TopBar(state: AppState) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(62.dp)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val title =
            when {
                state.showDetail && state.section != "首页" -> state.detail?.series?.title ?: "播放"
                else -> state.section
            }
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.weight(1f))
        OutlinedTextField(
            value = state.query,
            onValueChange = { state.query = it },
            placeholder = { Text("搜索全部剧集") },
            singleLine = true,
            modifier =
                Modifier
                    .width(320.dp)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                            state.search()
                            true
                        } else {
                            false
                        }
                    },
        )
        Spacer(Modifier.width(10.dp))
        Button(onClick = { state.search() }, enabled = !state.busy) { Text("搜索") }
        Spacer(Modifier.width(16.dp))
        if (state.busy) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
        }
        val account = state.account
        if (account?.loggedIn == true) {
            Icon(Icons.Filled.Person, contentDescription = null, tint = Accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(5.dp))
            Text(
                account.nickname ?: "已登录",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusBar(state: AppState) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(34.dp)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            state.library.error ?: state.status,
            style = MaterialTheme.typography.bodySmall,
            color =
                if (state.library.error != null) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
        if (state.preferences.autoNext) {
            Text(
                "自动下一集",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FatalScreen(state: AppState) {
    Column(
        Modifier.fillMaxSize().padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("客户端启动失败", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(10.dp))
        Text(
            state.fatal.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(18.dp))
        Button(onClick = { state.start() }) { Text("重试") }
    }
}
