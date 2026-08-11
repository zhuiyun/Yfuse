package com.yfuse.core.designsystem

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

object HeroPageIndicatorDefaults {
    val activeWidth = 16.dp
    val inactiveWidth = 6.dp
    val dotHeight = 6.dp
    val activeColor = Color.White.copy(alpha = 0.92f)
    val inactiveColor = Color.White.copy(alpha = 0.42f)
}

/**
 * Shared hero pagination with one 44dp tab target per visual dot.
 *
 * When [autoPlayRunning] and [onToggleAutoPlay] are both supplied, a separately labelled
 * pause/resume control is shown. That gives moving carousel content a persistent way to stop
 * and restart without making the tiny visual dots carry two different actions.
 */
@Composable
fun HeroPageIndicator(
    pageCount: Int,
    selectedPage: Int,
    onPageSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    autoPlayRunning: Boolean? = null,
    onToggleAutoPlay: (() -> Unit)? = null,
) {
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
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
                        .background(
                            if (active) {
                                HeroPageIndicatorDefaults.activeColor
                            } else {
                                HeroPageIndicatorDefaults.inactiveColor
                            },
                        ),
                )
            }
        }

        if (autoPlayRunning != null && onToggleAutoPlay != null) {
            val description = if (autoPlayRunning) "暂停自动轮播" else "继续自动轮播"
            Icon(
                imageVector = if (autoPlayRunning) AppIcons.Pause else AppIcons.Play,
                contentDescription = description,
                tint = Color.White,
                modifier = Modifier
                    .pressable(onClickLabel = description, onClick = onToggleAutoPlay)
                    .touchTarget()
                    .clip(AppShapes.pill)
                    .background(Color.White.copy(alpha = 0.14f))
                    .padding(12.dp)
                    .size(20.dp),
            )
        }
    }
}
