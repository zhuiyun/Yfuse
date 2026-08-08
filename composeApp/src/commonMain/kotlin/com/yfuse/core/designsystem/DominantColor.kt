package com.yfuse.core.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * Extracts a representative colour from the image at [url], used to tint the
 * detail screen. Returns [fallback] until (or unless) extraction succeeds.
 */
@Composable
expect fun rememberDominantColor(url: String?, fallback: Color): Color

/**
 * [rememberDominantColor], eased into place.
 *
 * The artwork accent reaches a long way — the library hero's content wash, the detail
 * page's play key and section rules, the TMDB sheet — and it changed as a hard cut,
 * because the raw value is a plain [Color] with nothing interpolating it. Every carousel
 * slide repainted half the page in one frame.
 */
@Composable
fun rememberAnimatedDominantColor(
    url: String?,
    fallback: Color,
    durationMillis: Int = Motion.ACCENT,
): Color {
    val extracted = rememberDominantColor(url, fallback)
    // [rememberDominantColor] snaps back to [fallback] the moment the URL changes and only
    // reports a real colour once Palette has run. Easing to that reset would take the page
    // through the fallback on the way to the new artwork — a blue flash between every two
    // slides — so the last real colour is held until the next one lands.
    var target by remember(fallback) { mutableStateOf(extracted) }
    LaunchedEffect(extracted, fallback) {
        if (extracted != fallback) target = extracted
    }
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val eased by animateColorAsState(
        targetValue = target,
        animationSpec = tween(
            durationMillis = if (reduceMotion) 0 else durationMillis,
            easing = Motion.Curve,
        ),
        label = "dominantColor",
    )
    return eased
}
