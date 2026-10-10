package com.yfuse.app

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

internal object SplashCloud : SplashChoreography {
    override val fadeStartMs = 1100f
    override val durationMs = fadeStartMs + FADE_MS

    override fun DrawScope.drawMark(
        nowMs: Float,
        mark: ImageBitmap?,
    ) {
        if (mark == null) return
        val settled = easeOutCubic(span(nowMs, 80f, 850f))
        val width = size.minDimension * 0.78f
        val height = width * mark.height / mark.width
        scale(lerp(0.9f, 1f, settled), pivot = center) {
            drawImage(
                mark,
                dstOffset =
                    IntOffset(
                        ((size.width - width) / 2).toInt(),
                        (
                            (size.height - height) / 2 +
                                (1 - settled) * width * 0.12f
                        ).toInt(),
                    ),
                dstSize = IntSize(width.toInt(), height.toInt()),
                alpha = settled,
            )
        }
    }

    override fun wordmark(nowMs: Float): Float = easeOutCubic(span(nowMs, 420f, 480f))
}
