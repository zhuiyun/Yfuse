package com.yfuse.core.designsystem

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

object HeroPageIndicatorDefaults {
    val activeWidth = 16.dp
    val inactiveWidth = 6.dp
    val dotHeight = 6.dp
}

/**
 * Shared hero pagination with one 44dp tab target per visual dot.
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
    modifier: Modifier = Modifier,
) {
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val palette = LocalPalette.current
    Row(
        modifier = modifier.selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(pageCount.coerceAtLeast(0)) { index ->
            val active = index == selectedPage
            val width by animateDpAsState(
                targetValue = if (active) {
                    HeroPageIndicatorDefaults.activeWidth
                } else {
                    HeroPageIndicatorDefaults.inactiveWidth
                },
                animationSpec = Motion.settle(reduceMotion),
                label = "hero-page-indicator",
            )
            Box(
                Modifier
                    .pressable(
                        role = Role.Tab,
                        onClickLabel = "显示第 ${index + 1} 张",
                        onClick = { onPageSelected(index) },
                    )
                    .semantics {
                        selected = active
                        contentDescription = "第 ${index + 1} 张，共 $pageCount 张"
                    }
                    .touchTarget()
                    .width(width)
                    .height(HeroPageIndicatorDefaults.dotHeight)
                    .clip(AppShapes.track)
                    .background(palette.text.copy(alpha = if (active) 0.82f else 0.28f)),
            )
        }
    }
}
