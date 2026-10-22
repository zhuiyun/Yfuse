package com.yfuse.feature.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.FallbackImage
import com.yfuse.core.designsystem.LiftAnchor
import com.yfuse.core.designsystem.LiftMenu
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.liftAnchor
import com.yfuse.core.designsystem.liftable
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.core.network.TmdbImages
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text

@Composable
internal fun AccordionCalendarEntry(
    display: CalendarDisplayEntry,
    weekStats: CalendarWeekStats?,
    expanded: Boolean,
    extraCount: Int,
    showChevron: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    liftMenu: (() -> LiftMenu)? = null,
) {
    val entry = display.entry
    val palette = LocalPalette.current
    val posterUrls =
        listOf(
            TmdbImages.poster(entry.episode.posterPath, width = "w185"),
            TmdbImages.media(entry.episode.posterPath, width = "w185"),
        ) + entry.posterUrls
    val artwork = remember { LiftAnchor() }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .defaultMinSize(minHeight = if (expanded) 88.dp else 78.dp)
            .glass(AppShapes.card, palette.card2, palette.border)
            .liftable(
                menu = liftMenu?.let { build -> { build().withArtwork(posterUrls.filterNotNull()) } },
                anchor = artwork,
                onOpen = onOpen,
            ).pressable(
                onClickLabel =
                    if (expanded) {
                        "打开${entry.episode.showTitle}播出日历"
                    } else {
                        "展开${entry.episode.showTitle}"
                    },
                onClick = onOpen,
            ).padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FallbackImage(
            urls = posterUrls,
            contentDescription = null,
            modifier =
                Modifier
                    .liftAnchor(artwork)
                    .width(if (expanded) 50.dp else 44.dp)
                    .height(if (expanded) 70.dp else 62.dp)
                    .clip(AppShapes.thumb)
                    .background(palette.card),
        )
        Column(
            Modifier
                .weight(1f)
                .padding(bottom = 2.dp),
        ) {
            Text(
                entry.episode.showTitle,
                style = AppTypography.body.strong,
                color = palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                buildString {
                    append(display.episodeLabel)
                    if (extraCount > 0) append(" · 另 $extraCount 部")
                },
                style = AppTypography.caption.regular,
                color = palette.sub,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (expanded) {
                Spacer(Modifier.height(3.dp))
                Text(
                    display.statusLine ?: broadcastStateLabel(entry),
                    style = AppTypography.caption.regular,
                    color = palette.sub2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(if (expanded) 5.dp else 8.dp))
            CalendarEpisodeSegments(
                stats = weekStats,
                playedPercentage = entry.playedPercentage,
            )
        }
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                airTimeLabel(entry.episode)?.let { airTime ->
                    Text(
                        airTime,
                        style = AppTypography.caption.medium,
                        color = palette.sub,
                    )
                }
                if (showChevron) {
                    Icon(
                        AppIcons.ChevronDown,
                        contentDescription = if (expanded) "收起" else "展开",
                        tint = palette.sub2,
                        modifier =
                            Modifier
                                .pressable(onClickLabel = if (expanded) "收起" else "展开", onClick = onToggle)
                                .touchTarget()
                                .size(16.dp),
                    )
                }
            }
            StatusBadge(entry)
        }
    }
}

@Composable
private fun CalendarEpisodeSegments(
    stats: CalendarWeekStats?,
    playedPercentage: Double?,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val segmentCount = 6
    val filled =
        playedPercentage
            ?.takeIf { it > 0.0 }
            ?.let { ((it / 100.0) * segmentCount).toInt().coerceAtLeast(1) }
            ?: stats
                ?.takeIf { it.scheduled > 0 }
                ?.let { ((it.available.toFloat() / it.scheduled) * segmentCount).toInt() }
            ?: 0
    val clampedFilled = filled.coerceIn(0, segmentCount)
    if (clampedFilled == 0) return
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        repeat(segmentCount) { index ->
            Box(
                Modifier
                    .weight(1f)
                    .height(3.dp)
                    .clip(CircleShape)
                    .background(if (index < clampedFilled) accent.accent else palette.border),
            )
        }
    }
}
