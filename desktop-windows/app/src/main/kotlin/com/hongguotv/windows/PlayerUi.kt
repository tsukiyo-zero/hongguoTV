// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.windows

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.awt.SwingPanel

/**
 * libVLC draws into a heavyweight surface, so Compose cannot paint on top of it.
 * Controls therefore sit directly below the surface, never over it.
 */
@Composable
fun VideoSurface(state: AppState, modifier: Modifier = Modifier) {
    val controller = state.player
    val component = remember { controller.ensure() }
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        if (component != null) {
            SwingPanel(
                factory = { component },
                background = Color.Black,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(24.dp),
            ) {
                Text(
                    controller.errorMessage ?: "播放器未就绪",
                    color = Color(0xFFFFB27A),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "安装 VLC 后重试，或在“我的”中指定 VLC 目录。",
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = { state.player.retryWith(state.preferences.vlcPath) }) {
                    Text("重新检测 VLC")
                }
            }
        }
    }
}

@Composable
fun PlayerBar(state: AppState, modifier: Modifier = Modifier) {
    val duration = state.playerDuration
    val max = if (duration.isFinite() && duration > 1) duration.toFloat() else 1f
    val current = if (state.scrubbing) state.scrubPosition else state.playerTime
    Row(
        modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        IconButton(onClick = { state.togglePlayback() }) {
            Icon(
                imageVector = if (state.playerPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (state.playerPlaying) "暂停" else "播放",
                tint = Color.White,
            )
        }
        IconButton(onClick = { state.skipPlayback(-10.0) }) {
            Icon(Icons.Filled.Replay10, contentDescription = "后退 10 秒", tint = Color.White)
        }
        IconButton(onClick = { state.skipPlayback(10.0) }) {
            Icon(Icons.Filled.Forward10, contentDescription = "前进 10 秒", tint = Color.White)
        }
        Text(
            formatTime(current),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.75f),
            modifier = Modifier.width(58.dp),
        )
        Slider(
            value = current.toFloat().coerceIn(0f, max),
            onValueChange = {
                state.scrubbing = true
                state.scrubPosition = it.toDouble()
            },
            onValueChangeFinished = {
                state.seekTo(state.scrubPosition)
                state.scrubbing = false
            },
            valueRange = 0f..max,
            modifier = Modifier.weight(1f),
        )
        Text(
            formatTime(duration),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.75f),
            modifier = Modifier.width(58.dp),
        )
        SpeedMenu(state)
        QualityMenu(state)
        if (state.actualQuality.isNotBlank() && state.actualQuality != "${state.preferences.quality}P") {
            Text(
                state.actualQuality,
                style = MaterialTheme.typography.labelSmall,
                color = Accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SpeedMenu(state: AppState) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text("${trimSpeed(state.preferences.speed)}×", color = Color.White)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Preferences.SPEEDS.forEach { value ->
                DropdownMenuItem(
                    text = { Text("${trimSpeed(value)}×") },
                    onClick = {
                        open = false
                        state.changeSpeed(value)
                    },
                )
            }
        }
    }
}

@Composable
private fun QualityMenu(state: AppState) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text("${state.preferences.quality}P", color = Color.White)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(480, 720, 1080).forEach { value ->
                DropdownMenuItem(
                    text = { Text("${value}P") },
                    onClick = {
                        open = false
                        state.changeQuality(value)
                    },
                )
            }
        }
    }
}

private fun trimSpeed(value: Float): String =
    if (value == value.toInt().toFloat()) value.toInt().toString() else value.toString()

@Composable
fun VideoFrame(state: AppState, modifier: Modifier = Modifier) {
    Column(modifier) {
        VideoSurface(state, Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(10.dp)))
        Spacer(Modifier.height(4.dp))
        PlayerBar(state)
    }
}
