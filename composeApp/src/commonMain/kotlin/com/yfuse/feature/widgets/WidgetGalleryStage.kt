package com.yfuse.feature.widgets

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.widget.WidgetMedia
import com.yfuse.core.designsystem.ThemeText as Text

/** Keep spatial artwork undistorted; use wide gutters for selectable neighbouring films. */
@Composable
internal fun WidgetGalleryStage(
    artworkWidth: Dp,
    height: Dp,
    items: List<WidgetMedia>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
        val sideWidth = (maxWidth - artworkWidth) / 2
        if (sideWidth >= 124.dp) {
            val captionHeight = galleryCopyHeight(false)
            val posterWidth = minOf(176.dp, sideWidth - 24.dp, (height - captionHeight - 8.dp) * .667f)
            if (posterWidth >= 80.dp) {
                for (offset in listOf(-1, 1)) {
                    val index = selectedIndex + offset
                    items.getOrNull(index)?.let { item ->
                        Box(
                            Modifier
                                .align(
                                    if (offset <
                                        0
                                    ) {
                                        Alignment.CenterStart
                                    } else {
                                        Alignment.CenterEnd
                                    },
                                ).width(sideWidth),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                Modifier.width(posterWidth).pressable(
                                    label = item.title,
                                    onClickLabel = "选中影片",
                                    onClick = { onSelect(index) },
                                ),
                            ) {
                                Artwork(
                                    item,
                                    Modifier.fillMaxWidth().height(posterWidth / .667f).clip(AppShapes.card),
                                    poster = true,
                                )
                                Text(
                                    item.title,
                                    Modifier.fillMaxWidth().padding(top = 8.dp),
                                    style = AppTypography.body.medium,
                                    color = LocalPalette.current.sub,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }
        }
        Box(Modifier.width(artworkWidth).height(height)) { content() }
    }
}
