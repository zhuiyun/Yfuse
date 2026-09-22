package com.yfuse.app

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/** Keeps the supplied artwork intact, including its glow and light/dark ground. */
internal object SplashAurora : SplashChoreography {
    override val fadeStartMs = 1_080f
    override val durationMs = fadeStartMs + FADE_MS

    override fun wordmark(nowMs: Float): Float = smooth(span(nowMs, 320f, 400f))

    override fun DrawScope.drawMark(
        nowMs: Float,
        mark: ImageBitmap?,
    ) {
        if (mark == null) return
        val progress = smooth(span(nowMs, 0f, 620f))
        if (progress <= 0f) return
        val scaleFactor = lerp(0.9f, 1f, progress)
        val outline =
            Path().apply {
                addRoundRect(
                    RoundRect(0f, 0f, size.width, size.height, CornerRadius(size.minDimension * 0.22f)),
                )
            }
        withTransform({ scale(scaleFactor, scaleFactor, center) }) {
            clipPath(outline) {
                drawImage(
                    image = mark,
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                    alpha = progress,
                )
            }
        }
    }
}
