package com.yfuse.app

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import com.yfuse.core.designsystem.Motion
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

internal class DefaultTabMotion(
    val left: State<Float>,
    val right: State<Float>,
)

/** Created only by the standard path; switching styles starts at the current selection. */
@Composable
internal fun rememberDefaultTabMotion(
    selectedIndex: Int,
    reduceMotion: Boolean,
): DefaultTabMotion {
    val initialIndex = selectedIndex.coerceAtLeast(0).toFloat()
    val indicatorLeft = remember { Animatable(tabPillTargetLeft(initialIndex)) }
    val indicatorRight = remember { Animatable(tabPillTargetRight(initialIndex)) }
    val previousIndex = remember { intArrayOf(selectedIndex) }
    LaunchedEffect(selectedIndex, reduceMotion) {
        // Back from 搜索, where the pill was hidden: it appears at the new tab — the bar fades it
        // in — instead of crossing the whole bar from wherever it was left.
        val reappearing = previousIndex[0] < 0
        previousIndex[0] = selectedIndex
        if (selectedIndex < 0) return@LaunchedEffect
        val targetIndex = selectedIndex.toFloat()
        val targetLeft = tabPillTargetLeft(targetIndex)
        val targetRight = tabPillTargetRight(targetIndex)
        if (reduceMotion || reappearing) {
            indicatorLeft.snapTo(targetLeft)
            indicatorRight.snapTo(targetRight)
        } else {
            val currentCenter = (indicatorLeft.value + indicatorRight.value) / 2f
            val movingRight = targetIndex + 0.5f >= currentCenter
            coroutineScope {
                launch {
                    indicatorLeft.animateTo(
                        targetValue = targetLeft,
                        animationSpec =
                            if (movingRight) {
                                Motion.tabIndicatorTrailing()
                            } else {
                                Motion.tabIndicatorLeading()
                            },
                    )
                }
                launch {
                    indicatorRight.animateTo(
                        targetValue = targetRight,
                        animationSpec =
                            if (movingRight) {
                                Motion.tabIndicatorLeading()
                            } else {
                                Motion.tabIndicatorTrailing()
                            },
                    )
                }
            }
        }
    }

    return remember { DefaultTabMotion(indicatorLeft.asState(), indicatorRight.asState()) }
}
