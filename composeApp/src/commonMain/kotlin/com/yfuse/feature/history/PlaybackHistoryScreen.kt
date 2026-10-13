package com.yfuse.feature.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.ActionToast
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.FallbackImage
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.PageHint
import com.yfuse.core.designsystem.StatusBarIconStyle
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.model.MediaServerKind
import com.yfuse.core.network.EmbyImages
import com.yfuse.core.network.TmdbImages
import com.yfuse.feature.profile.SettingsPage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text

@Composable
fun PlaybackHistoryScreen(component: PlaybackHistoryComponent) {
    val personal by component.personal.state.collectAsState()
    val policy by component.personal.policy.collectAsState()
    val sessions by component.viewing.sessions.collectAsState()
    val servers by component.registry.data.collectAsState()
    val metadata by component.metadata.collectAsState()
    val scopeToken = component.personal.scopeToken
    val rows =
        remember(personal.history, sessions, servers, policy, metadata, scopeToken) {
            val currentMetadata =
                metadata
                    .filterKeys { it.scopeToken == scopeToken }
                    .mapKeys { it.key.serverId to it.key.itemId }
            playbackHistoryRows(personal.history, sessions, servers.servers, policy, currentMetadata)
        }
    var notice by remember { mutableStateOf<String?>(null) }
    StatusBarIconStyle(darkIcons = !LocalPalette.current.isDark)
    Box(Modifier.fillMaxSize()) {
        SettingsPage(
            title = "播放记录",
            subtitle = "${personal.activeProfile.name} · ${rows.size} 部作品 · 最近观看优先",
            onBack = component.onBack,
            state = component.listState,
        ) {
            if (rows.isEmpty()) {
                item(key = "history-empty") {
                    Box(Modifier.fillMaxWidth().padding(vertical = 72.dp), contentAlignment = Alignment.Center) {
                        PageHint("还没有播放记录，观看影片后会显示在这里")
                    }
                }
            }
            items(rows, key = { it.entry.identity }, contentType = { "history-card" }) { row ->
                LaunchedEffect(component, scopeToken, row.entry.media.serverId, row.entry.media.serverItemId) {
                    component.loadMetadata(row)
                }
                PlaybackHistoryCard(row) {
                    if (!component.open(row)) notice = "暂时无法打开详情，请先连接这条记录的来源服务器"
                }
            }
        }
        ActionToast(message = notice, onDismiss = { notice = null })
    }
}

@Composable
private fun PlaybackHistoryCard(
    row: PlaybackHistoryRow,
    onOpen: () -> Unit,
) {
    val palette = LocalPalette.current
    val entry = row.entry
    Row(
        Modifier
            .padding(horizontal = Dimens.pageHorizontal)
            .fillMaxWidth()
            .glass(AppShapes.card, palette.card2, palette.border)
            .pressable(onClickLabel = "查看${row.title}的详情", focusShape = AppShapes.card, onClick = onOpen)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val urls = remember(row) { playbackHistoryPosterUrls(row) }
        // Clip the container before the placeholder or image is drawn, including its first frame.
        Box(
            Modifier.size(width = 72.dp, height = 108.dp).clip(AppShapes.card).background(palette.card3),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AppIcons.Play, null, tint = palette.sub2, modifier = Modifier.size(24.dp))
            FallbackImage(urls, null, Modifier.fillMaxSize())
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                row.title,
                style = AppTypography.body.strong,
                color = palette.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val metadata =
                listOfNotNull(
                    row.episodeLabel,
                    entry.media.year?.toString(),
                    row.server?.serverName,
                ).joinToString(" · ")
            if (metadata.isNotBlank()) {
                Text(
                    metadata,
                    style = AppTypography.caption.regular,
                    color = palette.sub2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                row.overview
                    ?: if (!row.metadataLoaded && row.server != null && !entry.media.serverItemId.isNullOrBlank()) {
                        "简介加载中…"
                    } else {
                        "暂无简介"
                    },
                style = AppTypography.caption.regular,
                color = palette.sub2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (entry.completed) {
                    "已看完"
                } else {
                    "看到 ${historyPositionLabel(entry.positionMs)}" +
                        if (row.durationMs > 0) " / ${historyPositionLabel(row.durationMs)}" else ""
                },
                style = AppTypography.caption.regular,
                color = palette.sub,
            )
            if (row.durationMs > 0 || entry.completed) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(AppShapes.track)
                        .background(palette.card3),
                ) {
                    Box(Modifier.fillMaxWidth(row.progress).height(3.dp).background(LocalAccentColors.current.accent))
                }
            }
            Text(
                remember(entry.watchedAtEpochMs) { historyWatchedAtLabel(entry.watchedAtEpochMs) },
                style = AppTypography.caption.regular,
                color = palette.sub2,
            )
        }
    }
}

internal fun playbackHistoryPosterUrls(row: PlaybackHistoryRow): List<String?> {
    val media = row.entry.media
    val server = row.server
    val artwork = row.metadata?.series ?: row.metadata?.item
    val tag = artwork?.posterTag ?: row.session?.posterTag
    val poster =
        server?.takeIf { it.kind != MediaServerKind.Plex || tag?.startsWith("plex:") == true }?.let {
            EmbyImages.primary(
                it.baseUrl,
                artwork?.posterItemId ?: row.session?.posterItemId ?: media.serverItemId.orEmpty(),
                tag,
                240,
                it.accessToken,
            )
        }
    return listOf(poster, TmdbImages.poster(media.posterPath, "w185"), TmdbImages.media(media.posterPath, "w185"))
}

internal fun historyPositionLabel(positionMs: Long): String {
    val seconds = positionMs.coerceAtLeast(0L) / 1_000
    val tail = (seconds % 60).toString().padStart(2, '0')
    return if (seconds >= 3_600) {
        "${seconds / 3_600}:${(seconds / 60 % 60).toString().padStart(2, '0')}:$tail"
    } else {
        "${seconds / 60}:$tail"
    }
}

private fun historyWatchedAtLabel(epochMs: Long): String =
    if (epochMs <= 0) {
        "观看时间未知"
    } else {
        DateTimeFormatter
            .ofPattern(
                "yyyy-MM-dd HH:mm",
            ).format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
    }
