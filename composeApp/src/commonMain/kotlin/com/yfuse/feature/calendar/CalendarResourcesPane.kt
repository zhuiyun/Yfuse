package com.yfuse.feature.calendar

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.data.missingCount
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.PageHint
import com.yfuse.core.designsystem.motionItem
import com.yfuse.core.designsystem.motionItems
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.model.CalendarDay
import com.yfuse.core.model.CalendarEntry
import com.yfuse.core.model.LibraryStatus
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text
import com.yfuse.core.designsystem.flatGlass as glass

private data class CalendarResourceSummary(
    val title: String,
    val entry: CalendarEntry,
    val episodeCount: Int,
    val availableCount: Int,
    val missingCount: Int,
    val servers: List<String>,
    val qualities: List<String>,
)

@Composable
internal fun CalendarResourcesPane(
    days: List<CalendarDay>,
    component: CalendarComponent,
    bottomContentInset: androidx.compose.ui.unit.Dp,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    var enrichedDays by remember(days) { mutableStateOf(days) }
    var enriching by remember(days) { mutableStateOf(days.isNotEmpty()) }
    var enrichmentError by remember(days) { mutableStateOf<String?>(null) }
    LaunchedEffect(days) {
        if (days.isEmpty()) return@LaunchedEffect
        component
            .enrichResourceDetails(days)
            .onSuccess { enrichedDays = it }
            .onFailure { enrichmentError = it.message ?: "资源画质读取失败" }
        enriching = false
    }
    val summaries =
        remember(enrichedDays) {
            enrichedDays
                .flatMap(CalendarDay::entries)
                .groupBy { it.episode.showTmdbId }
                .values
                .map { entries ->
                    val representative =
                        entries.maxBy { entry ->
                            entry.sources.size * 10 +
                                if (entry.openItemId != null) {
                                    5
                                } else {
                                    0 +
                                        if (entry.inLibrary) 1 else 0
                                }
                        }
                    CalendarResourceSummary(
                        title = representative.episode.showTitle,
                        entry = representative,
                        episodeCount = entries.size,
                        availableCount =
                            entries.mapNotNull(CalendarEntry::libraryEpisodeCount).maxOrNull()
                                ?: entries.count {
                                    it.status in
                                        setOf(
                                            LibraryStatus.Available,
                                            LibraryStatus.InProgress,
                                            LibraryStatus.Watched,
                                        )
                                },
                        missingCount = entries.count { it.status == LibraryStatus.Missing },
                        servers = entries.flatMap(CalendarEntry::serverNames).distinct(),
                        qualities = entries.flatMap(CalendarEntry::qualityTags).distinct(),
                    )
                }.sortedBy(CalendarResourceSummary::title)
        }

    if (summaries.isEmpty()) {
        // The summary is built from the schedule, so reading it again is what can fill this.
        PageHint(
            "暂无可汇总的追剧资源",
            Modifier.fillMaxWidth().padding(top = 40.dp),
            actionLabel = "刷新",
            onAction = { component.store.accept(CalendarIntent.Refresh) },
        )
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding =
            PaddingValues(
                start = Dimens.pageHorizontal,
                end = Dimens.pageHorizontal,
                bottom = bottomContentInset,
            ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        motionItem {
            Column(Modifier.padding(vertical = 4.dp)) {
                Text("${summaries.size} 部剧集的资源", style = AppTypography.section.strong, color = palette.text)
                Text(
                    "${summaries.sumOf { it.availableCount }} 集已入库 · ${summaries.sumOf { it.missingCount }} 集待补齐",
                    style = AppTypography.caption.regular,
                    color = palette.sub2,
                )
            }
        }
        if (enriching || enrichmentError != null) {
            motionItem {
                Text(
                    if (enriching) "正在读取片源画质…" else enrichmentError.orEmpty(),
                    style = AppTypography.caption.medium,
                    color = if (enrichmentError == null) palette.sub2 else palette.error,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }
        motionItems(summaries, key = { it.entry.episode.showTmdbId }) { summary ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .glass(AppShapes.card, palette.card2, palette.border)
                    .then(
                        summary.entry.openItemId?.let { itemId ->
                            Modifier.pressable {
                                component.onOpenItem(summary.entry.serverId, itemId)
                            }
                        } ?: Modifier,
                    ).padding(13.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(34.dp).glass(AppShapes.thumb, accent.container, palette.border),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(AppIcons.Series, null, tint = accent.accent, modifier = Modifier.size(18.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        summary.title,
                        style = AppTypography.body.strong,
                        color = palette.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${summary.episodeCount} 集排期 · 已入库 ${summary.availableCount} 集" +
                            if (summary.missingCount > 0) " · ${summary.missingCount} 集待入库" else "",
                        style = AppTypography.caption.regular,
                        color = palette.sub2,
                    )
                    val sources = (summary.servers + summary.qualities).take(5).joinToString(" · ")
                    if (sources.isNotBlank()) {
                        Spacer(Modifier.height(5.dp))
                        Text(
                            sources,
                            style = AppTypography.caption.medium,
                            color = palette.sub,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                summary.entry.openItemId?.let {
                    Icon(
                        AppIcons.ChevronRight,
                        contentDescription = null,
                        tint = palette.sub2,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}
