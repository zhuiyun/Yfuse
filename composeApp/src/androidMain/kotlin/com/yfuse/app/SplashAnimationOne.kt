package com.yfuse.app

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * 「折带展开」 — 「Yfuse 水火闪屏动画」 B, at launch speed.
 *
 * 速度线冲入 → 折带绕轴展开 → 渐变线拉出. The design loops in 3.6s with an exit fade
 * built into the tail; a launch plays it once and the shell owns the hand-off, so only
 * the entrance is kept and its beats are scaled into the 1.2s budget. The proportions are
 * the design's: the streak lands at 43% of the entrance, the ribbon squares up at 80%,
 * and the rule finishes last, on the final frame.
 */
internal object SplashOne : SplashChoreography {
    override val durationMs = 1_200f
    override val fadeStartMs = durationMs - FadeMs

    override fun DrawScope.drawMark(
        nowMs: Float,
        mark: ImageBitmap?,
    ) {
        mark ?: return
        withSheen(span(nowMs, SheenStartMs, SheenMs)) {
            drawStreak(easeOutExpo(span(nowMs, StreakStartMs, StreakMs)))
            drawUnfoldingMark(
                mark = mark,
                unfold = easeOutBack(span(nowMs, 0f, UnfoldMs)),
                alpha = easeOutCubic(span(nowMs, 0f, FadeInMs)),
            )
        }
    }

    override fun wordmark(nowMs: Float) = easeOutCubic(span(nowMs, WordmarkStartMs, WordmarkMs))
}

private const val FadeInMs = 170f
private const val UnfoldMs = 680f
private const val StreakStartMs = 210f
private const val StreakMs = 310f
private const val SheenStartMs = 620f
private const val SheenMs = 520f
private const val WordmarkStartMs = 575f
private const val WordmarkMs = 365f
