@file:OptIn(ExperimentalLayoutApi::class)

package com.yfuse.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.yfuse.core.model.EPISODE_RANGE_SIZE
import com.yfuse.core.model.episodeRangeIndex
import com.yfuse.core.model.episodeRangeLabel
import com.yfuse.core.model.episodeRanges
import kotlin.math.roundToInt
import com.yfuse.core.designsystem.ThemeText as Text

/** One number in the 选集 grid. */
@Immutable
data class EpisodeNumberCell(
    val key: String,
    /** What the cell reads: the episode's number, or its position where it has none. */
    val number: Int,
    val watched: Boolean,
    /** How much of a started episode was watched, 0..1; null when not started. */
    val progress: Float? = null,
    val current: Boolean = false,
    val downloaded: Boolean = false,
)

/** The grid's colours: over the picture in the player, or on a page's own palette. */
@Immutable
data class EpisodeGridColors(
    val text: Color,
    val watchedText: Color,
    val cellFill: Color,
    val accent: Color,
    val onAccent: Color,
) {
    companion object {
        /** Over the picture: white on dark glass, whatever the theme. */
        @Composable
        fun overPicture(): EpisodeGridColors {
            val accent = rememberAccentColorsForSurface(dark = true)
            return EpisodeGridColors(
                text = Color.White,
                watchedText = Color.White.copy(alpha = 0.42f),
                cellFill = PlayerTokens.chipFill,
                accent = accent.accent,
                onAccent = accent.onAccent,
            )
        }

        /** On a page: the theme's text and card colours. */
        @Composable
        fun themed(): EpisodeGridColors {
            val palette = LocalPalette.current
            val accent = LocalAccentColors.current
            return EpisodeGridColors(
                text = palette.text,
                watchedText = palette.sub2,
                cellFill = palette.card2,
                accent = accent.accent,
                onAccent = accent.onAccent,
            )
        }
    }
}

/**
 * 选集: a long season picked by number. Tabs of thirty run along the top — 1-30, 31-60 — with
 * 倒序 at the end, and the tab's numbers wrap below: the current episode filled with the accent,
 * watched ones faded, a started one underlined as far as it got, a downloaded one dotted. It
 * opens on the tab holding the current episode, so 第 150 集 of a 短剧 is one tap away instead
 * of a long fling through stills.
 *
 * @param focusCurrent the current episode takes focus as the grid opens — for a remote, whose
 *   first press would otherwise start wherever focus search happens to begin.
 */
@Composable
fun EpisodeNumberGrid(
    cells: List<EpisodeNumberCell>,
    onPick: (Int) -> Unit,
    colors: EpisodeGridColors,
    modifier: Modifier = Modifier,
    rangeSize: Int = EPISODE_RANGE_SIZE,
    focusCurrent: Boolean = false,
) {
    val ranges = remember(cells.size, rangeSize) { episodeRanges(cells.size, rangeSize) }
    if (ranges.isEmpty()) return
    val currentPosition = cells.indexOfFirst { it.current }.coerceAtLeast(0)
    var reversed by remember { mutableStateOf(false) }
    var selectedRange by remember(ranges.size, currentPosition) {
        mutableIntStateOf(episodeRangeIndex(currentPosition, rangeSize).coerceIn(0, ranges.lastIndex))
    }
    val range = ranges[selectedRange.coerceIn(0, ranges.lastIndex)]
    val currentFocus = remember { FocusRequester() }
    if (focusCurrent) {
        LaunchedEffect(Unit) {
            // The cell is placed in the first layout pass; ask after it.
            withFrameNanos { }
            runCatching { currentFocus.requestFocus() }
        }
    }
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (ranges.size > 1) {
                Row(
                    Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val order = if (reversed) ranges.indices.reversed() else ranges.indices
                    order.forEach { index ->
                        RangeTab(
                            label = episodeRangeLabel(ranges[index]) { cells[it].number },
                            selected = index == selectedRange,
                            colors = colors,
                            onClick = { selectedRange = index },
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
            } else {
                Spacer(Modifier.weight(1f))
            }
            RangeTab(
                label = if (reversed) "倒序" else "正序",
                selected = false,
                colors = colors,
                onClick = { reversed = !reversed },
                clickLabel = if (reversed) "改为正序" else "改为倒序",
                role = Role.Button,
            )
        }
        Spacer(Modifier.height(6.dp))
        // Each 40dp cell sits in a 48dp touch slot, which already leaves 8dp between rows.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val positions = if (reversed) range.reversed() else range.toList()
            positions.forEach { position ->
                NumberCell(
                    cell = cells[position],
                    colors = colors,
                    focusRequester = currentFocus.takeIf { position == currentPosition },
                    onClick = { onPick(position) },
                )
            }
        }
    }
}

@Composable
private fun RangeTab(
    label: String,
    selected: Boolean,
    colors: EpisodeGridColors,
    onClick: () -> Unit,
    clickLabel: String = "查看第 $label 集",
    role: Role = Role.Tab,
) {
    // The pill is about 30dp tall in a 48dp slot: the press and the focus ring follow the pill.
    val focusShape = remember { TouchTargetFocusShape(AppShapes.chip) }
    Text(
        label,
        style = AppTypography.caption.strong,
        color = if (selected) colors.onAccent else colors.text,
        maxLines = 1,
        modifier =
            Modifier
                .pressable(
                    haptic = HapticSignal.Select,
                    role = role,
                    focusShape = focusShape,
                    onClickLabel = clickLabel,
                    onClick = onClick,
                ).semantics { this.selected = selected }
                .touchTarget(focus = focusShape)
                .clip(AppShapes.chip)
                .background(if (selected) colors.accent else colors.cellFill)
                .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun NumberCell(
    cell: EpisodeNumberCell,
    colors: EpisodeGridColors,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    val started = cell.progress?.takeIf { !cell.watched && !cell.current && it > 0f }
    val state =
        buildList {
            when {
                cell.current -> add("正在播放")
                cell.watched -> add("已看完")
                started != null -> add("已看 ${(started * 100).roundToInt()}%")
            }
            if (cell.downloaded) add("已下载")
        }.joinToString("，")
    val focusShape = remember { TouchTargetFocusShape(AppShapes.thumb) }
    Box(
        Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .pressable(onClickLabel = "播放第 ${cell.number} 集", focusShape = focusShape, onClick = onClick)
            .semantics { if (state.isNotEmpty()) stateDescription = state }
            .touchTarget(focus = focusShape)
            .size(width = 52.dp, height = 40.dp)
            .clip(AppShapes.thumb)
            .background(if (cell.current) colors.accent else colors.cellFill),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            cell.number.toString(),
            style = if (cell.current) AppTypography.body.strong else AppTypography.body.medium,
            color =
                when {
                    cell.current -> colors.onAccent
                    cell.watched -> colors.watchedText
                    else -> colors.text
                },
            maxLines = 1,
        )
        if (started != null) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(started.coerceIn(0f, 1f))
                    .height(2.dp)
                    .background(colors.accent),
            )
        }
        if (cell.downloaded) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(if (cell.current) colors.onAccent else colors.accent),
            )
        }
    }
}
