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
    LaunchedEffect(selectedIndex, reduceMotion) {
        if (selectedIndex < 0) return@LaunchedEffect
        val targetIndex = selectedIndex.toFloat()
        val targetLeft = tabPillTargetLeft(targetIndex)
        val targetRight = tabPillTargetRight(targetIndex)
        if (reduceMotion) {
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
