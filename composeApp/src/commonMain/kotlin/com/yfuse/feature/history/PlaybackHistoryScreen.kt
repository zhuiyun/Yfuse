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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.app.floatingNavigationContentInset
import com.yfuse.app.systemNavigationContentInset
import com.yfuse.core.designsystem.ActionToast
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.LocalRouteVisible
import com.yfuse.core.designsystem.PageHint
import com.yfuse.core.designsystem.ScrollToTopOnReselect
import com.yfuse.core.designsystem.StatusBarIconStyle
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.model.MediaServerKind
import com.yfuse.core.network.EmbyImages
import com.yfuse.core.network.TmdbImages
import com.yfuse.feature.profile.SettingsPage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text

@Composable
fun PlaybackHistoryScreen(
    component: PlaybackHistoryComponent,
    rootTab: Boolean = false,
    contentEntries: (@Composable () -> Unit)? = null,
) {
    val personal by component.personal.state.collectAsState()
    val policy by component.personal.policy.collectAsState()
    val sessions by component.viewing.sessions.collectAsState()
    val servers by component.registry.data.collectAsState()
    val progressRevision by component.progressChanges.collectAsState()
    // Metadata enriches one card at a time. Reconcile deduplication only after the list settles,
    // so new provider aliases cannot remove or reshape rows halfway through a fling.
    val metadata by produceState(component.metadata.value, component) {
        combine(component.metadata, snapshotFlow { component.listState.isScrollInProgress }) { cache, scrolling ->
            cache to scrolling
        }.collectLatest { (cache, scrolling) ->
            if (!scrolling) {
                delay(120L)
                value = cache
            }
        }
    }
    val scopeToken = component.personal.scopeToken
    val rows =
        remember(personal.history, sessions, servers, policy, metadata, scopeToken, progressRevision) {
            val currentMetadata =
                metadata
                    .filterKeys { it.scopeToken == scopeToken }
                    .mapKeys { it.key.serverId to it.key.itemId }
            playbackHistoryRows(
                personal.history,
                sessions,
                servers.servers,
                policy,
                currentMetadata,
            ) { serverId, itemId ->
                component.progress?.stateForServerItem(serverId, itemId)
            }
        }
    val visible = LocalRouteVisible.current
    LaunchedEffect(component, scopeToken, rows, visible) {
        if (!visible) {
            component.updateVisibleRows(emptyList())
        } else {
            snapshotFlow {
                component.listState.layoutInfo.visibleItemsInfo
                    .mapNotNull { it.key as? String }
            }.distinctUntilChanged()
                .collect { keys -> component.updateVisibleRows(playbackHistoryLoadWindow(rows, keys)) }
        }
    }
    DisposableEffect(component) {
        onDispose { component.updateVisibleRows(emptyList()) }
    }
    var notice by remember { mutableStateOf<String?>(null) }
    StatusBarIconStyle(darkIcons = !LocalPalette.current.isDark)
    if (rootTab) ScrollToTopOnReselect(component.listState)
    Box(Modifier.fillMaxSize()) {
        SettingsPage(
            title = "播放记录",
            subtitle = "${personal.activeProfile.name} · ${rows.size} 部作品 · 最近观看优先",
            onBack = component.onBack,
            state = component.listState,
            showBackButton = !rootTab,
            bottomContentInset = if (rootTab) floatingNavigationContentInset() else systemNavigationContentInset(),
        ) {
            if (contentEntries != null) item(key = "watching-entries") { contentEntries() }
            if (rows.isEmpty()) {
                item(key = "history-empty") {
                    Box(Modifier.fillMaxWidth().padding(vertical = 72.dp), contentAlignment = Alignment.Center) {
                        PageHint("还没有播放记录，观看影片后会显示在这里")
                    }
                }
            }
            items(rows, key = { it.entry.identity }, contentType = { "history-card" }) { row ->
                val key = component.metadataKey(row)
                val rowMetadata =
                    remember(component, key) {
                        component.metadata.map { cache -> cache[key] to cache.containsKey(key) }.distinctUntilChanged()
                    }
                val loaded by rowMetadata.collectAsState(
                    component.metadata.value.let { it[key] to it.containsKey(key) },
                )
                val enriched =
                    remember(row, loaded) { row.copy(metadata = loaded.first, metadataLoaded = loaded.second) }
                PlaybackHistoryCard(
                    row = enriched,
                    posterState = component.posterState(row),
                    onRetry = { component.retryMetadata(row) },
                    onOpen = {
                        if (!component.open(row)) notice = "暂时无法打开详情，请先连接这条记录的来源服务器"
                    },
                )
            }
        }
        ActionToast(message = notice, onDismiss = { notice = null })
    }
}

@Composable
private fun PlaybackHistoryCard(
    row: PlaybackHistoryRow,
    posterState: PlaybackHistoryPosterState,
    onRetry: () -> Unit,
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
            PlaybackHistoryPoster(urls, posterState)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                row.title,
                style = AppTypography.body.strong,
                color = palette.text,
                maxLines = 2,
                minLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val metadata =
                listOfNotNull(
                    row.episodeLabel,
                    entry.media.year?.toString(),
                    row.server?.serverName,
                ).joinToString(" · ")
            Text(
                metadata.ifBlank { " " },
                style = AppTypography.caption.regular,
                color = palette.sub2,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val failed = row.metadataLoaded && row.metadata?.complete != true
            Text(
                row.overview
                    ?: if (failed) {
                        "简介暂时无法加载，点此重试"
                    } else if (!row.metadataLoaded && row.server != null && !entry.media.serverItemId.isNullOrBlank()) {
                        "简介加载中…"
                    } else {
                        "暂无简介"
                    },
                style = AppTypography.caption.regular,
                color = palette.sub2,
                maxLines = 2,
                minLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = if (failed) Modifier.pressable(onClickLabel = "重新加载简介和海报", onClick = onRetry) else Modifier,
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(AppShapes.track)
                    .background(palette.card3),
            ) {
                Box(Modifier.fillMaxWidth(row.progress).height(3.dp).background(LocalAccentColors.current.accent))
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
    val posterId =
        (artwork?.posterItemId ?: row.session?.posterItemId ?: media.serverItemId)?.takeIf(
            String::isNotBlank,
        )
    val poster =
        server
            ?.takeIf {
                posterId != null &&
                    (
                        it.kind != MediaServerKind.Plex ||
                            tag?.startsWith(
                                "plex:",
                            ) == true
                    )
            }?.let {
                EmbyImages.primary(
                    it.baseUrl,
                    posterId.orEmpty(),
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

private val HistoryDateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private fun historyWatchedAtLabel(epochMs: Long): String =
    if (epochMs <= 0) {
        "观看时间未知"
    } else {
        HistoryDateFormat.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
    }

/** Visible cards first, followed by just one neighbour on each side for prefetch. */
internal fun playbackHistoryLoadWindow(
    rows: List<PlaybackHistoryRow>,
    visibleKeys: List<String>,
): List<PlaybackHistoryRow> {
    val indices = rows.withIndex().associate { it.value.entry.identity to it.index }
    val visible = visibleKeys.mapNotNull(indices::get).distinct()
    if (visible.isEmpty()) return emptyList()
    val neighbours = listOf(visible.min() - 1, visible.max() + 1).filter { it in rows.indices && it !in visible }
    return (visible + neighbours).map(rows::get)
}
