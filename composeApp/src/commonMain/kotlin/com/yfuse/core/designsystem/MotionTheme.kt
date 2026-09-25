package com.yfuse.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The app's motion language, chosen in one place (外观与辅助 → 动效主题) instead of touch point by
 * touch point.
 *
 * 经典 is every motion as designed, with the individual choices below it honoured. 静息 keeps only
 * fades and short moves: presses answer with a tint rather than a squeeze, pages and dialogs fade
 * in quickly over a few dp, lists arrive together instead of row by row, and there are no particles,
 * waves or bounces. It suits a television, an older phone, or anyone who finds the rest busy.
 *
 * Neither is 减少动画, which still takes motion away entirely wherever it is on. Persisted by name,
 * so new themes are appended and never renamed.
 */
enum class MotionTheme(
    val label: String,
    val description: String,
) {
    Classic("经典", "全部动效，按下方各项设置播放"),
    Calm("静息", "只保留淡入淡出与小幅位移：按压不缩放，无粒子、潮涌与回弹"),
}

val LocalMotionTheme = staticCompositionLocalOf { MotionTheme.Classic }

/** Whether the calm motion theme is on — see [MotionTheme.Calm]. */
@Composable
@ReadOnlyComposable
fun calmMotion(): Boolean = LocalMotionTheme.current == MotionTheme.Calm

/** How much shorter 静息 makes the transitions it keeps. */
const val CALM_DURATION_SCALE = 0.6f

/** The one loading study 静息 uses: it breathes in place and travels nowhere. */
val CalmLoadingAnimation = LoadingAnimation.BeadBreath
