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
import com.yfuse.app.choreography
import com.yfuse.app.markResource
import kotlinx.coroutines.delay

@Composable
actual fun SplashPreview(
    variant: SplashAnimation,
    playing: Boolean,
    modifier: Modifier,
) {
    val choreography = remember(variant) { variant.choreography }
    val clock = remember(variant) { Animatable(0f) }

    LaunchedEffect(variant, playing) {
        if (!playing) {
            // Park on the resolved mark rather than an empty frame.
            clock.snapTo(choreography.fadeStartMs)
            return@LaunchedEffect
        }
        while (true) {
            clock.snapTo(0f)
            clock.animateTo(
                targetValue = choreography.fadeStartMs,
                animationSpec = tween(choreography.fadeStartMs.toInt(), easing = LinearEasing),
            )
            // Hold the finished mark so the loop reads as a cycle, not a stutter.
            delay(LoopHoldMs)
        }
    }

    // The clock is read inside the draw lambda, so looping this in a settings list costs
    // recomposition nothing.
    val mark = variant.markResource()?.let { ImageBitmap.imageResource(it) }
    Canvas(modifier) { with(choreography) { drawMark(clock.value, mark) } }
}

private const val LoopHoldMs = 900L
