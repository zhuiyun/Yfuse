package com.yfuse.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

object HeroPageIndicatorDefaults {
    val activeWidth = 26.dp
    val inactiveWidth = 10.dp
    val dotHeight = 3.dp
}

/**
 * Shared hero pagination with one fixed 48dp tab target per visual dot.
 *
 * The dots used to be fixed white because they sat on the darkest part of a scrim. They now
 * sit in the strip where the artwork has already dissolved into the page — see
 * [Modifier.fadeIntoPage] — so their ink is the page's, not the artwork's.
 *
 * There is no pause control any more. It existed to undo an auto-advance that every
 * interaction already suspends on its own, and it was a permanently visible second state
 * bolted onto the smallest cluster of controls on the page.
 */
@Composable
fun HeroPageIndicator(
    pageCount: Int,
    selectedPage: Int,
    onPageSelected: (Int) -> Unit,
    pageOffset: Float = 0f,
    modifier: Modifier = Modifier,
    onArtwork: Boolean = false,
    pageOffsetProvider: (() -> Float)? = null,
) {
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val palette = LocalPalette.current
    Row(
        modifier = modifier.selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(pageCount.coerceAtLeast(0)) { index ->
            val active = index == selectedPage
            Box(
                Modifier
                    .pressable(
                        role = Role.Tab,
                        onClickLabel = "显示第 ${index + 1} 张",
                        onClick = { onPageSelected(index) },
                    ).semantics {
                        selected = active
                        contentDescription = "第 ${index + 1} 张，共 $pageCount 张"
                    }.size(MinTouchTarget)
                    .drawBehind {
                        // Stable touch/layout slots; fractional pager movement invalidates drawing only.
                        val offset = if (reduceMotion) 0f else pageOffsetProvider?.invoke() ?: pageOffset
                        val weight = carouselIndicatorWeight(index, selectedPage, offset, pageCount)
                        val width =
                            (
                                HeroPageIndicatorDefaults.inactiveWidth +
                                    (HeroPageIndicatorDefaults.activeWidth - HeroPageIndicatorDefaults.inactiveWidth) *
                                    weight
                            ).toPx()
                        val height = HeroPageIndicatorDefaults.dotHeight.toPx()
                        val color =
                            if (onArtwork) {
                                Color.White.copy(alpha = 0.34f + 0.60f * weight)
                            } else {
                                palette.text.copy(alpha = 0.28f + 0.54f * weight)
                            }
                        drawRoundRect(
                            color = color,
                            topLeft = Offset((size.width - width) / 2f, (size.height - height) / 2f),
                            size = Size(width, height),
                            cornerRadius = CornerRadius(height / 2f),
                        )
                    },
            )
        }
    }
}
