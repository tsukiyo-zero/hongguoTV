// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.windows

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Accent = Color(0xFFFF7A1A)
private val Background = Color(0xFF101216)
private val SurfaceDark = Color(0xFF171A20)
private val SurfaceRaised = Color(0xFF21252D)

@Composable
fun GuoyingTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme =
            darkColorScheme(
                primary = Accent,
                onPrimary = Color.Black,
                secondary = Color(0xFFFFB27A),
                background = Background,
                onBackground = Color(0xFFEDEFF3),
                surface = SurfaceDark,
                onSurface = Color(0xFFEDEFF3),
                surfaceVariant = SurfaceRaised,
                onSurfaceVariant = Color(0xFFA9B0BC),
                error = Color(0xFFFF6B6B),
            ),
        content = content,
    )
}

@Composable
fun Cover(url: String, title: String, modifier: Modifier = Modifier) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, url) {
        value = Images.load(url)
    }
    Box(
        modifier =
            modifier.background(
                Brush.verticalGradient(listOf(Color(0xFF2A2F39), Color(0xFF14171C))),
            ),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                text = title.take(1),
                color = Color.White.copy(alpha = 0.22f),
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
fun Avatar(url: String, name: String) {
    Box(Modifier.size(32.dp).clip(CircleShape)) {
        if (url.isBlank()) {
            Box(Modifier.fillMaxSize().background(SurfaceRaised), contentAlignment = Alignment.Center) {
                Text(name.take(1).ifBlank { "红" }, style = MaterialTheme.typography.labelMedium)
            }
        } else {
            Cover(url, name, Modifier.fillMaxSize())
        }
    }
}

@Composable
fun SeriesCard(
    state: AppState,
    series: Series,
    cloud: CloudEntry? = null,
    rank: RankPosition? = null,
    onOpen: () -> Unit,
) {
    val record = state.library.recordFor(series.id)
    val favorite = state.library.isFavorite(series.id)
    Column(
        Modifier
            .width(168.dp)
            .clickable(enabled = !state.busy) { onOpen() },
    ) {
        Box(Modifier.fillMaxWidth().height(224.dp).clip(RoundedCornerShape(10.dp))) {
            Cover(series.cover, series.title, Modifier.fillMaxSize())
            if (rank?.rank != null) {
                Surface(
                    color = Color.Black.copy(alpha = 0.65f),
                    shape = RoundedCornerShape(bottomEnd = 8.dp),
                    modifier = Modifier.align(Alignment.TopEnd),
                ) {
                    Text(
                        "第 ${rank.rank} 名",
                        color = Accent,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
            IconButton(
                onClick = { state.library.toggleFavorite(series) },
                modifier = Modifier.align(Alignment.TopStart).size(34.dp),
            ) {
                Icon(
                    imageVector = if (favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = if (favorite) "取消本机收藏" else "加入本机收藏",
                    tint = if (favorite) Accent else Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            series.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            series.badge,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        when {
            cloud?.online == false && cloud.publishText != null ->
                Text(
                    cloud.publishText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            cloud?.episodeIndex != null ->
                Text(
                    "账号看到第 ${cloud.episodeIndex + 1} 集",
                    style = MaterialTheme.typography.bodySmall,
                    color = Accent,
                    maxLines = 1,
                )
            record != null ->
                Text(
                    record.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = Accent,
                    maxLines = 1,
                )
        }
        if (rank?.heat?.isNotBlank() == true) {
            Text(
                rank.heat,
                style = MaterialTheme.typography.bodySmall,
                color = Accent,
                maxLines = 1,
            )
        }
    }
}

@Composable
fun ChipRow(
    options: List<Pair<String, String>>,
    selected: String,
    enabled: Boolean = true,
    onSelect: (String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = selected == value,
                onClick = { if (enabled) onSelect(value) },
                enabled = enabled,
                label = { Text(label) },
                colors =
                    FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Accent.copy(alpha = 0.22f),
                        selectedLabelColor = Accent,
                    ),
            )
        }
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    detail: String,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Accent, modifier = Modifier.size(46.dp))
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

@Composable
fun SectionTitle(title: String, subtitle: String? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
fun LinkText(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(text) }
}

fun formatTime(seconds: Double): String {
    if (!seconds.isFinite() || seconds <= 0) return "00:00"
    val total = seconds.toLong()
    val hours = total / 3600
    return if (hours > 0) "%d:%02d:%02d".format(hours, (total % 3600) / 60, total % 60)
    else "%02d:%02d".format(total / 60, total % 60)
}
