package com.yfuse.app

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * 「水火交接」 — 「Yfuse 水火闪屏动画」 A, kept as the alternative to 折带展开.
 *
 * 标志弹入 → 光缝扫过 → 字标浮起. Where B is lateral — everything arrives from the left
 * and squares up — A is frontal: the mark pops out of nothing at the centre and a bright
 * seam runs across the line where water meets fire. Same 1.2s budget, same beats in the
 * same order, so switching variants in settings never changes how long a launch takes.
 */
internal object SplashTwo : SplashChoreography {
    override val durationMs = 1_200f
    override val fadeStartMs = durationMs - FadeMs

    override fun DrawScope.drawMark(
        nowMs: Float,
        mark: ImageBitmap?,
    ) {
        mark ?: return
        drawWaterFireBloom(bell(span(nowMs, BloomStartMs, BloomMs)))
        val pop = span(nowMs, 0f, PopMs)
        drawCentredMark(
            mark = mark,
            // `yfPop` overshoots to 1.05 and settles — easeOutBack is that curve.
            scale = lerp(0.72f, 1f, easeOutBack(pop)),
            alpha = easeOutCubic(span(nowMs, 0f, FadeInMs)),
        )
        drawSeam(span(nowMs, SeamStartMs, SeamMs))
    }

    override fun wordmark(nowMs: Float) = easeOutCubic(span(nowMs, WordmarkStartMs, WordmarkMs))
}

private const val FadeInMs = 150f
private const val PopMs = 520f
private const val BloomStartMs = 60f
private const val BloomMs = 780f
private const val SeamStartMs = 380f
private const val SeamMs = 420f
private const val WordmarkStartMs = 575f
private const val WordmarkMs = 365f
