package com.yfuse.feature.history

import androidx.compose.foundation.background
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
import com.yfuse.core.designsystem.flatGlass
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.sharedMediaArtwork
import com.yfuse.core.designsystem.sharedMediaOnClick
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

/** Full-width immersive horizontal card with right artwork backdrop and embedded pill progress bar. */
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
    val surfaceBase = if (palette.isDark) HeroInk else palette.background
    val overlayInk = if (palette.isDark) Color.White else palette.text
    val overlaySub = if (palette.isDark) Color.White.copy(alpha = 0.78f) else palette.sub

    Box(
        Modifier
            .padding(horizontal = Dimens.pageHorizontal)
            .fillMaxWidth()
            .height(112.dp)
            .glass(AppShapes.card, palette.card2, palette.border)
            .pressable(
                onClickLabel = "查看${row.title}的详情",
                focusShape = AppShapes.card,
                onClick = sharedMediaOnClick(sharedKey, posterShape, onOpen),
            ),
    ) {
        // Soft full-bleed backdrop on the right side fading smoothly into the left metadata column.
        Box(
            Modifier
                .fillMaxWidth(0.68f)
                .fillMaxHeight()
                .align(Alignment.CenterEnd),
        ) {
            PlaybackHistoryPoster(urls, posterState)
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            0f to surfaceBase,
                            0.42f to surfaceBase.copy(alpha = 0.72f),
                            1f to surfaceBase.copy(alpha = 0.16f),
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
                    .size(width = 62.dp, height = 92.dp)
                    .sharedMediaArtwork(sharedKey, posterShape, urls)
                    .clip(posterShape)
                    .background(palette.card3),
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
                    style = AppTypography.body.strong.copy(shadow = HeroTextShadow.takeIf { palette.isDark }),
                    color = overlayInk,
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
                        color = overlaySub,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // Embedded pill progress bar with inline time readout (inspired by reference 00:16-00:18).
                val progressText =
                    if (entry.completed) {
                        "已看完"
                    } else {
                        val pos = historyPositionLabel(entry.positionMs)
                        if (row.durationMs > 0L) "$pos / ${historyPositionLabel(row.durationMs)}" else pos
                    }
                Box(
                    Modifier
                        .fillMaxWidth(0.76f)
                        .height(22.dp)
                        .clip(CircleShape)
                        .background(
                            if (palette.isDark) {
                                Color.White.copy(alpha = 0.14f)
                            } else {
                                Color.Black.copy(alpha = 0.08f)
                            },
                        ),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(row.progress.coerceIn(0.06f, 1f))
                            .background(accent.copy(alpha = 0.78f)),
                    )
                    Text(
                        progressText,
                        modifier = Modifier.padding(horizontal = 10.dp),
                        style = AppTypography.caption.medium,
                        color = Color.White,
                        maxLines = 1,
                    )
                }
            }
            if (onContinue != null) {
                Box(
                    Modifier
                        .size(36.dp)
                        .flatGlass(CircleShape, palette.glassStrong, palette.border)
                        .pressable(
                            onClickLabel = if (entry.completed) "重新播放" else "继续播放",
                            focusShape = CircleShape,
                            onClick = onContinue,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(AppIcons.Play, null, tint = accent, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

/** Compact 16:9 landscape grid tile for the Grid view mode (inspired by reference 00:11-00:15). */
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
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(16f / 10f)
            .glass(shape, palette.card2, palette.border)
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
                        0.45f to Color.Transparent,
                        1f to HeroInk.copy(alpha = 0.84f),
                    ),
                ),
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    Modifier
                        .size(width = 18.dp, height = 3.dp)
                        .clip(AppShapes.track)
                        .background(Color.White.copy(alpha = 0.25f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(row.progress.coerceIn(0.08f, 1f))
                            .background(accent),
                    )
                }
                Text(
                    meta,
                    style = AppTypography.caption.regular,
                    color = Color.White.copy(alpha = 0.84f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
