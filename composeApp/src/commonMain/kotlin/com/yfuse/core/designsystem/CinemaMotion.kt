package com.yfuse.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Finite UI-only effects. Nothing here owns a player, a Surface or an input event. */
object CinemaMotion {
    const val PLAY = 260
    const val CONFIRM = 360
    const val HERO = 460
    const val COLOR = 400
    const val REORDER = 280
    const val ITEM_ENTER = 180
    const val ITEM_EXIT = 140
    const val EPISODE = 280
    const val REVEAL = 180
    const val FOCUS = 220
    const val FOCUS_DWELL = 180L
    const val POSTER_PAGE = 360
    const val CHOICE = 220
    const val STATUS = 280
    const val SCAN = 1200
    const val GEAR = 180
}

internal fun cinemaMotionAllowed(
    theme: MotionTheme,
    reduceMotion: Boolean,
    budget: MotionBudget,
    visible: Boolean,
): Boolean = theme == MotionTheme.Cinema && !reduceMotion && !budget.reduced && visible

@Composable
@ReadOnlyComposable
fun cinemaMotion(): Boolean =
    cinemaMotionAllowed(
        LocalMotionTheme.current,
        LocalAccessibilityOptions.current.reduceMotion,
        LocalMotionBudget.current,
        LocalRouteVisible.current,
    )

internal fun cinemaCaptionOffset(
    progress: Float,
    stage: Int,
): Float {
    val step = stage.coerceIn(0, 2)
    val fraction = ((progress.coerceIn(0f, 1f) * CinemaMotion.HERO - step * 60f) / 320f).coerceIn(0f, 1f)
    return (1f - Motion.Curve.transform(fraction)) * (12f + step * 3f)
}

internal fun cinemaArtworkVisual(offset: Float): CarouselArtworkVisual =
    CarouselArtworkVisual(scale = 1.10f, translationFraction = offset.coerceIn(-1f, 1f) * 0.04f)

/** Six short curved paths end at the glyph; no random clock and no persistent particles. */
internal fun cinemaOrbitPoint(
    progress: Float,
    index: Int,
    radius: Float,
): Offset {
    val fraction = progress.coerceIn(0f, 1f)
    val angle = index * PI / 3.0 + fraction * PI * 0.75
    val distance = radius.coerceAtLeast(0f) * (1f - fraction)
    return Offset(cos(angle).toFloat() * distance, sin(angle).toFloat() * distance)
}

internal fun cinemaConfirmationScale(progress: Float): Float {
    val fraction = progress.coerceIn(0f, 1f)
    return 1f - 0.13f * sin(fraction * 2f * PI.toFloat()) * (1f - fraction)
}
