package com.yfuse.core.designsystem

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import kotlin.math.PI
import kotlin.math.sin

val LocalPulseSweepEnabled = staticCompositionLocalOf { true }

/** A bounded highlight on the existing material; no extra blur surface or layout pass. */
internal fun DrawScope.drawMotionSweep(
    rect: Rect,
    color: Color,
    progress: Float,
    alpha: Float = 1f,
) {
    if (progress <= 0f || progress >= 1f || rect.width <= 0f) return
    val envelope = sin(progress * PI).toFloat() * alpha
    val band = rect.width * 0.55f
    val center = rect.left - band + (rect.width + 2f * band) * progress
    val path = Path().apply { addRoundRect(RoundRect(rect, rect.height / 2f, rect.height / 2f)) }
    clipPath(path) {
        drawRect(
            brush =
                Brush.linearGradient(
                    listOf(Color.Transparent, color.copy(alpha = 0.23f * envelope), Color.Transparent),
                    start = Offset(center - band, rect.bottom),
                    end = Offset(center + band, rect.top),
                ),
            topLeft = rect.topLeft,
            size = rect.size,
        )
    }
}
