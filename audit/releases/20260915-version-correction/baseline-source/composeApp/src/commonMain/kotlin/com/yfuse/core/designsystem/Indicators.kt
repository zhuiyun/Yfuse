package com.yfuse.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import kotlin.math.abs
import kotlin.math.floor

object HeroPageIndicatorDefaults {
    val activeWidth = 26.dp
    val inactiveWidth = 10.dp
    val dotHeight = 3.dp
}

/**
 * How much pager offset still counts as "the pager is moving". Anything above this and the page
 * change is part of a travel the offset is already drawing; at rest it is a jump.
 */
private const val CAROUSEL_DOT_IN_FLIGHT = 0.05f

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
    // Which dot is the selected one, as a float the row can be halfway between.
    //
    // The pager's own offset is continuous while the pager is moving, and that carried the whole
    // animation — but a tap on a distant dot is fulfilled by `animateScrollToPage`, which closes
    // most of the distance by snapping and only animates the tail. The row answered that with a
    // cut: one dot at full width, another instantly narrow, nothing in between.
    val currentOffset = rememberUpdatedState(pageOffsetProvider)
    val currentPageOffset = rememberUpdatedState(pageOffset)
    val drawnPage = remember { Animatable(selectedPage.toFloat()) }
    LaunchedEffect(selectedPage, pageCount, reduceMotion) {
        val live = if (reduceMotion) 0f else currentOffset.value?.invoke() ?: currentPageOffset.value
        val target = selectedPage.toFloat()
        val gap = abs(target - drawnPage.value)
        // A drag, or a scroll that is already in flight, delivers every page in between and the
        // offset is what draws it: following that exactly is the whole of the animation, and a
        // spring on top would be fighting the finger. Only a page that arrives with nothing in
        // flight is a jump, and going the long way round the ends of the row is a wrap, not a
        // journey across it.
        if (reduceMotion || abs(live) > CAROUSEL_DOT_IN_FLIGHT || gap <= 1f || gap >= pageCount - 1f) {
            drawnPage.snapTo(target)
        } else {
            drawnPage.animateTo(target, Motion.settle<Float>())
        }
    }
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
                        // One source of truth at a time. While the tap's spring is running it owns
                        // the whole position: `animateScrollToPage` snaps most of the way and then
                        // animates the tail, so its offset comes back to life partway through and
                        // adding the two counted the same travel twice — the dot overshot its
                        // neighbour and came back. Otherwise the pager's own offset is the
                        // animation, and `selectedPage` is where it started.
                        val position = if (drawnPage.isRunning) drawnPage.value else selectedPage + offset
                        val page = floor(position).toInt()
                        val weight = carouselIndicatorWeight(index, page, position - page, pageCount)
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
