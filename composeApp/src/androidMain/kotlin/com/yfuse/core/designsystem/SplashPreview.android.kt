package com.yfuse.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.imageResource
import com.yfuse.app.markResource
import com.yfuse.app.rememberSplashChoreography

@Composable
actual fun SplashPreview(
    variant: SplashAnimation,
    playing: Boolean,
    modifier: Modifier,
) {
    val choreography = rememberSplashChoreography(variant)
    val clock = remember(variant) { Animatable(0f) }
    val visible = LocalRouteVisible.current
    // Held wherever the launch it stands for is: under 减少动画, which carries the system's
    // 移除动画 too, and under 静息.
    val still = LocalAccessibilityOptions.current.reduceMotion || calmMotion() || platformAnimationsDisabled()

    LaunchedEffect(variant, playing, visible, still) {
        if (!playing || !visible || still) {
            // Park on the resolved mark rather than an empty frame.
            clock.snapTo(choreography.fadeStartMs)
            return@LaunchedEffect
        }
        clock.snapTo(0f)
        clock.animateTo(
            targetValue = choreography.fadeStartMs,
            animationSpec = tween(choreography.fadeStartMs.toInt(), easing = LinearEasing),
        )
    }

    // Advance only the draw phase; there is no idle loop once the mark has resolved.
    val mark = variant.markResource()?.let { ImageBitmap.imageResource(it) }
    val lightCount = rememberPhaseLightCount(playing && visible && !still, enhancedOnly = true)
    Canvas(modifier) {
        choreography.background?.let { drawRect(it) }
        with(choreography) { drawMark(clock.value, mark) }
        if (choreography.showWordmark) {
            drawPhaseLight(
                androidx.compose.ui.geometry.Rect(
                    size.width * 0.2f,
                    size.height * 0.2f,
                    size.width * 0.8f,
                    size.height * 0.8f,
                ),
                (clock.value / choreography.fadeStartMs).coerceIn(0f, 1f),
                lightCount,
                androidx.compose.ui.graphics.Color.White,
            )
        }
    }
}
