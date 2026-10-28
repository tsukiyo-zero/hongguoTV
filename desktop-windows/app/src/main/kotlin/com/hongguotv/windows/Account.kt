// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.windows

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun AccountPanel(state: AppState) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val account = state.account
            if (account?.loggedIn == true) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Person, contentDescription = null, tint = Accent)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        account.nickname ?: "红果用户",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = { state.logout() }) { Text("退出") }
                }
            } else {
                val qr = state.qr
                if (qr != null) {
                    Image(
                        bitmap = qr,
                        contentDescription = "红果登录二维码",
                        modifier =
                            Modifier
                                .size(220.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White)
                                .padding(8.dp),
                    )
                    OutlinedButton(onClick = { state.cancelLogin() }) { Text("取消扫码") }
                } else {
                    Button(onClick = { state.beginLogin() }, enabled = !state.accountBusy) {
                        Text("红果扫码登录")
                    }
                    if (state.accountBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    }
                }
            }
            Text(
                state.accountMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun MyScreen(state: AppState) {
    var vlcPath by remember { mutableStateOf(state.preferences.vlcPath) }
    var vlcMessage by remember { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("我的", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        AccountPanel(state)

        Text("本机片单", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Tile(
                title = "本机收藏",
                subtitle = "${state.library.favorites.size} 部作品",
                icon = Icons.Filled.Star,
            ) { state.navigate("本机收藏") }
            Tile(
                title = "本机历史",
                subtitle = "${state.library.history.size} 条记录",
                icon = Icons.Filled.Schedule,
            ) { state.navigate("本机历史") }
        }
        state.library.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Text("红果账号", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Tile(
                title = "账户历史",
                subtitle = if (state.account == null) "登录后查看" else "查看账号片单",
                icon = Icons.Filled.History,
            ) { state.navigate(if (state.account == null) "账户登录" else "账户历史") }
            Tile(
                title = "账户收藏",
                subtitle = if (state.account == null) "登录后查看" else "查看账号片单",
                icon = Icons.Filled.Star,
            ) { state.navigate(if (state.account == null) "账户登录" else "账户收藏") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Tile(
                title = "已上线预约",
                subtitle = if (state.account == null) "登录后查看" else "查看已上线作品",
                icon = Icons.Filled.CalendarMonth,
            ) { state.navigate(if (state.account == null) "账户登录" else "已上线预约") }
            Tile(
                title = "待上线预约",
                subtitle = if (state.account == null) "登录后查看" else "查看待上线作品",
                icon = Icons.Filled.CalendarMonth,
            ) { state.navigate(if (state.account == null) "账户登录" else "待上线预约") }
        }

        Text("播放设置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("默认清晰度", modifier = Modifier.width(110.dp))
            ChipRow(
                options = listOf("480" to "480P", "720" to "720P", "1080" to "1080P"),
                selected = state.preferences.quality.toString(),
                onSelect = { state.changeQuality(it.toInt()) },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("播放倍速", modifier = Modifier.width(110.dp))
            ChipRow(
                options =
                    Preferences.SPEEDS.map {
                        val label = if (it == it.toInt().toFloat()) "${it.toInt()}×" else "${it}×"
                        it.toString() to label
                    },
                selected = state.preferences.speed.toString(),
                onSelect = { state.changeSpeed(it.toFloat()) },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("自动下一集", modifier = Modifier.width(110.dp))
            ChipRow(
                options = listOf("on" to "开", "off" to "关"),
                selected = if (state.preferences.autoNext) "on" else "off",
                onSelect = { state.preferences.updateAutoNext(it == "on") },
            )
        }

        Text("播放器", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "客户端会优先使用自带的 VLC；自行编译运行时，可在此指定 VLC 目录。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = vlcPath,
                onValueChange = { vlcPath = it },
                label = { Text("VLC 目录（含 libvlc.dll）") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            Button(
                onClick = {
                    state.player.retryWith(vlcPath)
                    vlcMessage =
                        VlcRuntime.directory?.absolutePath
                            ?: (VlcRuntime.error ?: "VLC 初始化失败")
                },
            ) { Text("应用") }
        }
        Text(
            vlcMessage.ifBlank {
                VlcRuntime.directory?.absolutePath ?: "尚未检测到 VLC 运行库"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text("关于", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "果映 TV Windows ${AppInfo.version}",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(onClick = { state.navigate("关于") }) { Text("许可与说明") }
        Text(
            "本机记录目录：${AppDirs.data.absolutePath}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Tile(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        shape = RoundedCornerShape(12.dp),
        onClick = onClick,
        modifier = Modifier.width(240.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = Accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun LoginScreen(state: AppState) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("红果账号", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "扫码登录后可查看账号历史、收藏和预约，并在正式观看时同步进度。" +
                "登录态使用 Windows DPAPI 绑定当前用户加密保存。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AccountPanel(state)
    }
}

@Composable
fun CloudScreen(state: AppState) {
    val type = state.cloudType
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionTitle(
            title = type,
            subtitle = "红果账号片单 · 本机记录独立保留",
            trailing = {
                if (state.account != null) {
                    OutlinedButton(onClick = { state.loadCloud() }, enabled = !state.cloudLoading) {
                        Text("刷新")
                    }
                }
            },
        )
        Box(Modifier.weight(1f)) {
            when {
                state.account == null ->
                    EmptyState(
                        icon = Icons.Filled.Person,
                        title = "登录后查看账户片单",
                        detail = "账户历史、收藏与预约会随登录账号切换。",
                    ) { Button(onClick = { state.navigate("账户登录") }) { Text("扫码登录") } }
                state.cloudLoading ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    }
                state.cloudError != null ->
                    EmptyState(
                        icon = Icons.Filled.Person,
                        title = "账户片单读取失败",
                        detail = state.cloudError.orEmpty(),
                    ) { Button(onClick = { state.loadCloud() }) { Text("重新读取") } }
                state.cloudRows.isEmpty() ->
                    EmptyState(
                        icon = Icons.Filled.Person,
                        title = if (state.cloudLoaded) "账号暂无此类记录" else "准备读取账户片单",
                        detail = "可以刷新，或切换其他账户片单。",
                    ) { Button(onClick = { state.loadCloud() }) { Text("读取") } }
                else ->
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(168.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalArrangement = Arrangement.spacedBy(22.dp),
                    ) {
                        items(state.cloudRows, key = { it.series.id }) { entry ->
                            SeriesCard(state, entry.series, entry, null) {
                                state.openFromCloud(entry)
                            }
                        }
                    }
            }
        }
        Text(
            "账号片单只读；收藏、预约与进度同步在“详情”或“我的”里操作。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
