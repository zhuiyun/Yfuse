package com.yfuse.feature.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.glass
import com.yfuse.core.widget.WidgetMedia
import java.time.LocalDate
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text

/** The rail owns the click target; these layouts only arrange artwork and readable copy. */
@Composable
internal fun WidgetSpotlightCard(
    item: WidgetMedia,
    metadata: Boolean,
) {
    Box(Modifier.fillMaxSize()) {
        Artwork(item, Modifier.fillMaxSize().clip(AppShapes.card))
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(12.dp)
                .fillMaxWidth()
                .editorialGlass(overArtwork = true)
                .padding(12.dp),
        ) {
            MediaCopy(item, metadata = metadata, dense = true)
        }
    }
}

@Composable
internal fun WidgetTicketCard(
    item: WidgetMedia,
    metadata: Boolean,
    index: Int,
) {
    val accent = LocalAccentColors.current.accent
    Row(
        Modifier.fillMaxSize().editorialGlass().padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(item, Modifier.width(80.dp).fillMaxHeight().clip(AppShapes.thumb), poster = true)
        Canvas(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 6.dp)) {
            drawLine(
                color = accent.copy(alpha = .18f),
                start = Offset(size.width / 2f, 0f),
                end = Offset(size.width / 2f, size.height),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 4.dp.toPx())),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                (index + 1).toString().padStart(2, '0'),
                color = accent,
                style = AppTypography.section.strong,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            MediaCopy(item, metadata = metadata, dense = true)
        }
    }
}

@Composable
internal fun WidgetSplitCard(
    item: WidgetMedia,
    metadata: Boolean,
) {
    Row(
        Modifier.fillMaxSize().editorialGlass().padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Artwork(item, Modifier.weight(.42f).fillMaxHeight().clip(AppShapes.thumb), poster = true)
        Column(Modifier.weight(.58f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Artwork(item, Modifier.fillMaxWidth().weight(1f).clip(AppShapes.thumb))
            MediaCopy(item, Modifier.padding(bottom = 2.dp), metadata = metadata, dense = true)
        }
    }
}

@Composable
internal fun WidgetCaptionPoster(
    item: WidgetMedia,
    metadata: Boolean,
) {
    Box(Modifier.fillMaxSize()) {
        Artwork(item, Modifier.fillMaxSize().clip(AppShapes.card), poster = true)
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(10.dp)
                .fillMaxWidth()
                .editorialGlass(overArtwork = true)
                .padding(10.dp),
        ) {
            MediaCopy(item, metadata = metadata, dense = true)
        }
    }
}

@Composable
internal fun WidgetDateCard(
    item: WidgetMedia,
    metadata: Boolean,
) {
    val date = remember(item.date) { runCatching { LocalDate.parse(item.date.trim().take(10)) }.getOrNull() }
    val dateLabel = item.date.trim().ifBlank { item.year.take(4) }
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current.accent
    Column(
        Modifier.fillMaxSize().editorialGlass().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (metadata && dateLabel.isNotBlank()) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (date != null) {
                    Text(
                        date.dayOfMonth.toString().padStart(2, '0'),
                        color = accent,
                        style = AppTypography.display.medium,
                        maxLines = 1,
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "${date.monthValue} 月",
                            color = palette.text,
                            style = AppTypography.body.medium,
                            maxLines = 1,
                        )
                        Text(
                            date.year.toString(),
                            color = palette.sub2,
                            style = AppTypography.caption.regular,
                            maxLines = 1,
                        )
                    }
                } else {
                    Text(
                        dateLabel,
                        Modifier.weight(1f),
                        color = accent,
                        style = AppTypography.section.medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(AppIcons.WatchCalendar, contentDescription = null, modifier = Modifier.size(20.dp), tint = accent)
            }
        }
        Artwork(item, Modifier.fillMaxWidth().weight(1f).clip(AppShapes.thumb))
        MediaCopy(
            if (metadata && dateLabel.isNotBlank()) item.copy(year = "") else item,
            metadata = metadata,
            dense = true,
        )
    }
}

@Composable
internal fun WidgetCompactRow(
    item: WidgetMedia,
    metadata: Boolean,
) {
    Row(
        Modifier.fillMaxSize().editorialGlass().padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(item, Modifier.width(56.dp).height(84.dp).clip(AppShapes.thumb), poster = true)
        MediaCopy(item, Modifier.weight(1f), metadata = metadata, dense = true)
        Icon(
            AppIcons.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = LocalAccentColors.current.accent,
        )
    }
}

/** A denser glass tint protects text contrast over artwork without a dark scrim. */
@Composable
private fun Modifier.editorialGlass(overArtwork: Boolean = false): Modifier {
    val palette = LocalPalette.current
    return glass(
        shape = AppShapes.card,
        fill = if (overArtwork) palette.glassStrong.copy(alpha = .92f) else palette.card,
        border = palette.border,
    )
}
