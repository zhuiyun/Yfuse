package com.yfuse.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * Yfuse 标识 —— 设计文件「Yfuse Logo 重做」方案 **4c「缎带绕环 · 播放头居中」**。
 *
 * 缎带绕成一圈但留一道缺口，播放头居中。缺口让环可以旋转成缓冲动画，静态时又是稳定的
 * 圆形标志（见 [com.yfuse.app] 的开屏动画 5b）。
 *
 * 几何全部取自设计稿的 200×200 画布：环 `r=72`、`stroke-width=26`、圆头端点、
 * `stroke-dasharray:336 117` 配 `rotate(-142)`——换算成一段从 -142° 起、扫过 267.36°
 * 的弧，缺口落在左侧。播放头是 `M-24,-29 L28,0 L-24,29 Z` 平移到 (104,100)，再用
 * 14 宽的圆角描边把三个尖角抹圆。
 */
private const val VIEWPORT = 200f
private const val CENTER = 100f
/** Give the mark a little more breathing room inside splash and in-app canvases. */
private const val ContentScale = 0.90f

/** 缎带环。 */
private const val RingRadius = 72f
private const val RingStroke = 26f
private const val RingStart = -142f
private const val RingSweep = 267.36f

/** 播放头：三角本体 + 抹圆用的描边。 */
private const val HeadStroke = 14f
private val HeadPoints = listOf(80f to 71f, 132f to 100f, 80f to 129f)

/** 含圆头端点在内，标识实际占据的半径（用于渐变对角线）。 */
private const val InkRadius = RingRadius + RingStroke / 2f

/**
 * 浅色系配色。设计稿原色是 `#FF7A3D → #FF4F86 → #B23BF5`；这里整体提亮一档，
 * 既落在用户要的浅色系里，又能在纯白开屏和浅色启动器底板上保持可读。
 */
object LogoPalette {
    val Ribbon = listOf(
        Color(0xFFFF9A5C),
        Color(0xFFFF7FA8),
        Color(0xFFBC8DF2),
    )

    /** 播放头取渐变中段，和设计稿一致（那里是 `#FF4F86`，渐变的中间色）。 */
    val Head = Color(0xFFFF7FA8)

    /** 单色态——反白场景（深色开屏、单色图标）用。 */
    val Mono = Color(0xFFFFFFFF)
}

// ---------------------------------------------------------------- 绘制原语

/**
 * 缎带的三段渐变，沿标识外接方框的左下 → 右上对角线铺开
 * （设计稿的 `linearGradient x1=0 y1=1 x2=1 y2=0`）。
 */
fun DrawScope.yfuseRibbonBrush(colors: List<Color> = LogoPalette.Ribbon): Brush {
    val u = size.minDimension / VIEWPORT * ContentScale
    val cx = size.width / 2f
    val cy = size.height / 2f
    return Brush.linearGradient(
        colors = colors,
        start = Offset(cx - InkRadius * u, cy + InkRadius * u),
        end = Offset(cx + InkRadius * u, cy - InkRadius * u),
    )
}

/** 留缺口的缎带环。旋转与缩放交给调用方的 `graphicsLayer`，这样开屏能单独驱动它。 */
fun DrawScope.drawYfuseRing(brush: Brush) {
    val u = size.minDimension / VIEWPORT * ContentScale
    val r = RingRadius * u
    val cx = size.width / 2f
    val cy = size.height / 2f
    drawArc(
        brush = brush,
        startAngle = RingStart,
        sweepAngle = RingSweep,
        useCenter = false,
        topLeft = Offset(cx - r, cy - r),
        size = Size(r * 2f, r * 2f),
        style = Stroke(width = RingStroke * u, cap = StrokeCap.Round),
    )
}

/** 居中播放头。先填充再描边，描边只为把三个角抹圆。 */
fun DrawScope.drawYfusePlayhead(color: Color) {
    val u = size.minDimension / VIEWPORT * ContentScale
    val cx = size.width / 2f
    val cy = size.height / 2f
    val path = Path().apply {
        HeadPoints.forEachIndexed { index, (x, y) ->
            val px = cx + (x - CENTER) * u
            val py = cy + (y - CENTER) * u
            if (index == 0) moveTo(px, py) else lineTo(px, py)
        }
        close()
    }
    drawPath(path, color)
    drawPath(path, color, style = Stroke(width = HeadStroke * u, join = StrokeJoin.Round))
}

// ---------------------------------------------------------------- 组合件

/** 完整标识：缎带环 + 居中播放头。尺寸由调用方给定，内容按短边等比铺满。 */
@Composable
fun YfuseMark(
    modifier: Modifier = Modifier,
    ribbon: List<Color> = LogoPalette.Ribbon,
    head: Color = LogoPalette.Head,
) {
    Canvas(modifier) {
        drawYfuseRing(yfuseRibbonBrush(ribbon))
        drawYfusePlayhead(head)
    }
}

/** 只画环——开屏的旋进动画和底部缓冲指示都用它。 */
@Composable
fun YfuseRing(
    modifier: Modifier = Modifier,
    ribbon: List<Color> = LogoPalette.Ribbon,
) {
    Canvas(modifier) { drawYfuseRing(yfuseRibbonBrush(ribbon)) }
}

/** 只画播放头——开屏里它比环晚半拍弹出。 */
@Composable
fun YfusePlayhead(
    modifier: Modifier = Modifier,
    color: Color = LogoPalette.Head,
) {
    Canvas(modifier) { drawYfusePlayhead(color) }
}
