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
    override val fadeStartMs = durationMs - FADE_MS

    override fun DrawScope.drawMark(
        nowMs: Float,
        mark: ImageBitmap?,
    ) {
        mark ?: return
        withSheen(span(nowMs, SHEEN_START_MS, SHEEN_MS)) {
            drawStreak(easeOutExpo(span(nowMs, STREAK_START_MS, STREAK_MS)))
            drawUnfoldingMark(
                mark = mark,
                unfold = easeOutBack(span(nowMs, 0f, UNFOLD_MS)),
                alpha = easeOutCubic(span(nowMs, 0f, FADE_IN_MS)),
            )
        }
    }

    override fun wordmark(nowMs: Float) = easeOutCubic(span(nowMs, WORDMARK_START_MS, WORDMARK_MS))
}

private const val FADE_IN_MS = 170f
private const val UNFOLD_MS = 680f
private const val STREAK_START_MS = 210f
private const val STREAK_MS = 310f
private const val SHEEN_START_MS = 620f
private const val SHEEN_MS = 520f
private const val WORDMARK_START_MS = 575f
private const val WORDMARK_MS = 365f
