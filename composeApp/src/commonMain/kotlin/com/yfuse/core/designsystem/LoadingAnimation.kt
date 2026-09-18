package com.yfuse.core.designsystem

import androidx.compose.runtime.staticCompositionLocalOf
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Stable names are persisted; labels correspond to the approved loading studies. */
enum class LoadingAnimation(
    val label: String,
    val description: String,
    internal val periodMillis: Int,
) {
    Orbit("星轨呼吸", "青蓝到紫粉的彗尾，环绕微光核心", 3300),
    Fusion("水火相融", "冷暖渐变双滴，相伴旋转与舒展", 2800),
    Ribbon("折光风车", "四片彩色折带，收拢、旋转、展开", 6400),
    Ripple("静水涟漪", "珠彩核心与缓缓扩散的极光圆环", 2800),
    Spectrum("光谱律动", "青绿、蓝紫、粉橙五束光依次起伏", 1250),
    Beads("轻盈三点", "三颗渐变光点，轻巧地错峰浮动", 1650),
    BeadWave("三点轻跃", "B1 · 彩色小圆点依次微弹，柔软轻盈", 1800),
    BeadBreath("三点呼吸", "B2 · 原位轻轻缩放，依次亮起", 2400),
    BeadRelay("三点接力", "B3 · 三颗彩点轮流跃过彼此", 3000),
}

val LocalLoadingAnimation = staticCompositionLocalOf { LoadingAnimation.Orbit }

internal data class LoadingDotFrame(
    val x: Float,
    val y: Float,
    val scaleX: Float = 1f,
    val scaleY: Float = scaleX,
    val alpha: Float = 1f,
)

/** Geometry in a 56 × 56 design space; also supplies a readable reduced-motion frame. */
internal fun loadingDotFrame(
    animation: LoadingAnimation,
    phase: Float,
    index: Int,
    moving: Boolean = true,
): LoadingDotFrame {
    val restingX = 28f + (index - 1) * 16f
    if (!moving) return LoadingDotFrame(restingX, 28f)
    val offset =
        when (animation) {
            LoadingAnimation.BeadWave -> index * 0.1f
            LoadingAnimation.BeadBreath -> index * (0.32f / 2.4f)
            LoadingAnimation.BeadRelay -> index / 3f
            else -> -index * (0.16f / 1.65f)
        }
    val p = loadingPhase(phase + offset)
    return when (animation) {
        LoadingAnimation.BeadBreath -> {
            val breath = (1f - cos(p * 2f * PI.toFloat())) / 2f
            LoadingDotFrame(restingX, 28f, scaleX = 0.7f + 0.43f * breath, alpha = 0.45f + 0.55f * breath)
        }
        LoadingAnimation.BeadRelay -> {
            if (p < 2f / 3f) {
                LoadingDotFrame(12f + p * 48f, 28f)
            } else {
                val arc = (p - 2f / 3f) * 3f * PI.toFloat()
                LoadingDotFrame(28f + 16f * cos(arc), 28f - 12f * sin(arc))
            }
        }
        else -> {
            val travel = if (p < 0.6f) sin(p / 0.6f * PI.toFloat()).let { it * it } else 0f
            if (animation == LoadingAnimation.BeadWave) {
                LoadingDotFrame(
                    restingX,
                    30f - 8f * travel,
                    scaleX = 1.02f - 0.06f * travel,
                    scaleY = 0.96f + 0.08f * travel,
                    alpha = 0.72f + 0.28f * travel,
                )
            } else {
                LoadingDotFrame(
                    restingX,
                    30f - 7f * travel,
                    scaleX = 0.82f + 0.18f * travel,
                    alpha =
                        0.38f + 0.62f * travel,
                )
            }
        }
    }
}

internal fun loadingPhase(value: Float): Float = ((value % 1f) + 1f) % 1f
