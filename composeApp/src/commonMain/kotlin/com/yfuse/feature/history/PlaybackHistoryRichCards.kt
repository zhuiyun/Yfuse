package com.yfuse.feature.history

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.HeroInk
import com.yfuse.core.designsystem.HeroTextShadow
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.MediaSharedElementKey
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.pressableTarget
import com.yfuse.core.designsystem.sharedMediaArtwork
import com.yfuse.core.designsystem.sharedMediaOnClick
import kotlin.math.roundToInt
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text

internal enum class HistoryTimeBucket(
    val label: String,
) {
    Today("今天"),
    ThisWeek("本周"),
    Earlier("更早"),
}

internal fun groupHistoryRowsByRecency(
    rows: List<PlaybackHistoryRow>,
    nowEpochMs: Long = System.currentTimeMillis(),
): List<Pair<HistoryTimeBucket, List<PlaybackHistoryRow>>> {
    if (rows.isEmpty()) return emptyList()
    val dayMs = 86_400_000L
    val weekMs = dayMs * 7L
    val buckets = linkedMapOf<HistoryTimeBucket, MutableList<PlaybackHistoryRow>>()
    rows.forEach { row ->
        val age = (nowEpochMs - row.entry.watchedAtEpochMs).coerceAtLeast(0L)
        val bucket =
            when {
                row.entry.watchedAtEpochMs <= 0L -> HistoryTimeBucket.Earlier
                age < dayMs -> HistoryTimeBucket.Today
                age < weekMs -> HistoryTimeBucket.ThisWeek
                else -> HistoryTimeBucket.Earlier
            }
        buckets.getOrPut(bucket) { mutableListOf() }.add(row)
    }
    return buckets.entries.map { it.key to it.value }
}

/**
 * High-readability horizontal playback history card.
 *
 * Uses a clean composited surface with a restrained atmospheric backdrop hint on the far right,
 * keeping title, metadata, progress bar, and time readout sharp in both light and dark themes.
 */
@Composable
internal fun PlaybackHistoryRichRowCard(
    row: PlaybackHistoryRow,
    posterState: PlaybackHistoryPosterState,
    sharedKey: MediaSharedElementKey?,
    onContinue: (() -> Unit)?,
    onOpen: () -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current.accent
    val entry = row.entry
    val urls = remember(row) { playbackHistoryPosterUrls(row) }
    val posterShape = AppShapes.thumb
    val cardSurface = palette.card.compositeOver(palette.background)
    val cardBorder = palette.text.copy(alpha = if (palette.isDark) 0.12f else 0.08f)
    val progressFraction = if (entry.completed) 1f else row.progress.coerceIn(0f, 1f)
    val progressPercent = (progressFraction * 100f).roundToInt().coerceIn(0, 100)
    val progressFillColor = if (entry.completed) palette.success else accent
    val trackColor = palette.text.copy(alpha = if (palette.isDark) 0.14f else 0.09f)

    Box(
        Modifier
            .padding(horizontal = Dimens.pageHorizontal)
            .fillMaxWidth()
            .height(114.dp)
            .clip(AppShapes.card)
            .background(cardSurface)
            .border(0.75.dp, cardBorder, AppShapes.card)
            .pressable(
                onClickLabel = "查看${row.title}的详情",
                focusShape = AppShapes.card,
                onClick = sharedMediaOnClick(sharedKey, posterShape, onOpen),
            ),
    ) {
        // Subtle atmospheric echo confined to the right edge so text always sits on clean surface.
        Box(
            Modifier
                .fillMaxWidth(0.42f)
                .fillMaxHeight()
                .align(Alignment.CenterEnd),
        ) {
            PlaybackHistoryPoster(urls, posterState)
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            0f to cardSurface,
                            0.28f to cardSurface.copy(alpha = if (palette.isDark) 0.94f else 0.96f),
                            0.62f to cardSurface.copy(alpha = if (palette.isDark) 0.78f else 0.86f),
                            1f to cardSurface.copy(alpha = if (palette.isDark) 0.56f else 0.74f),
                        ),
                    ),
            )
        }
        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(width = 64.dp, height = 94.dp)
                    .sharedMediaArtwork(sharedKey, posterShape, urls)
                    .clip(posterShape)
                    .background(palette.card3)
                    .border(0.5.dp, palette.text.copy(alpha = if (palette.isDark) 0.14f else 0.08f), posterShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(AppIcons.Play, null, tint = palette.sub2, modifier = Modifier.size(20.dp))
                PlaybackHistoryPoster(urls, posterState)
            }
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    row.title,
                    style = AppTypography.body.strong,
                    color = palette.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle =
                    listOfNotNull(
                        row.server?.serverName,
                        row.episodeLabel,
                        entry.media.year?.toString(),
                    ).joinToString(" · ")
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        style = AppTypography.caption.regular,
                        color = palette.sub,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val progressText =
                    if (entry.completed) {
                        "已看完"
                    } else {
                        val pos = historyPositionLabel(entry.positionMs)
                        if (row.durationMs > 0L) "$pos / ${historyPositionLabel(row.durationMs)}" else pos
                    }
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(trackColor),
                    ) {
                        if (progressFraction > 0f) {
                            Box(
                                Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(progressFraction.coerceIn(0.03f, 1f))
                                    .clip(CircleShape)
                                    .background(progressFillColor),
                            )
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            progressText,
                            style = AppTypography.caption.medium,
                            color = if (entry.completed) palette.success else palette.sub,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (!entry.completed && row.durationMs > 0L) {
                            Text(
                                "$progressPercent%",
                                style = AppTypography.caption.medium,
                                color = palette.sub2,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            if (onContinue != null) {
                val playFill = accent.copy(alpha = if (palette.isDark) 0.22f else 0.12f)
                val playBorder = accent.copy(alpha = if (palette.isDark) 0.36f else 0.24f)
                Box(
                    Modifier
                        .pressableTarget(
                            shape = CircleShape,
                            onClickLabel = if (entry.completed) "重新播放${row.title}" else "继续播放${row.title}",
                            onClick = onContinue,
                        ).size(38.dp)
                        .clip(CircleShape)
                        .background(playFill.compositeOver(cardSurface))
                        .border(0.75.dp, playBorder, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(AppIcons.Play, null, tint = accent, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

/** Compact 16:10 landscape grid tile for the Grid view mode. */
@Composable
internal fun PlaybackHistoryGridTile(
    row: PlaybackHistoryRow,
    posterState: PlaybackHistoryPosterState,
    sharedKey: MediaSharedElementKey?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current.accent
    val entry = row.entry
    val urls = remember(row) { playbackHistoryPosterUrls(row) }
    val shape = AppShapes.card
    val cardBorder = palette.text.copy(alpha = if (palette.isDark) 0.14f else 0.08f)
    val progressFraction = if (entry.completed) 1f else row.progress.coerceIn(0f, 1f)
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(16f / 10f)
            .clip(shape)
            .background(palette.card3)
            .border(0.75.dp, cardBorder, shape)
            .pressable(
                onClickLabel = "查看${row.title}的详情",
                focusShape = shape,
                onClick = sharedMediaOnClick(sharedKey, shape, onOpen),
            ),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .sharedMediaArtwork(sharedKey, shape, urls),
        ) {
            PlaybackHistoryPoster(urls, posterState)
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.36f to Color.Transparent,
                        0.62f to HeroInk.copy(alpha = 0.42f),
                        0.84f to HeroInk.copy(alpha = 0.78f),
                        1f to HeroInk.copy(alpha = 0.90f),
                    ),
                ),
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                row.title,
                style = AppTypography.body.strong.copy(shadow = HeroTextShadow),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val meta =
                listOfNotNull(
                    if (entry.completed) "已看完" else historyPositionLabel(entry.positionMs),
                    row.episodeLabel,
                ).joinToString(" · ")
            Text(
                meta,
                style = AppTypography.caption.medium,
                color = Color.White.copy(alpha = 0.88f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.24f)),
            ) {
                if (progressFraction > 0f) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progressFraction.coerceIn(0.06f, 1f))
                            .clip(CircleShape)
                            .background(if (entry.completed) palette.success else accent),
                    )
                }
            }
        }
    }
}
