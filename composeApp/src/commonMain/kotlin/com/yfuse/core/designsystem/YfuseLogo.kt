package com.yfuse.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect

/**
 * Yfuse 标识 —— 设计文件「Yfuse Logo 重做」的**定稿 9「单半弧」**。
 *
 * 两条半弧同心：上半是火（暖），下半是水（冷），左右各留一道 24° 等宽缺口——「汇流」。
 * 播放头压在中线上，上下各取火/水的端色，做光学居中（向右偏 6，见 [HeadCenterX]）。
 *
 * 几何取自设计稿的 200×200 画布：`r=70`、`stroke-width=24`、圆头端点，火弧
 * `M31.5,85.4 A70,70 0 0 1 168.5,85.4`、水弧 `M168.5,114.6 A70,70 0 0 1 31.5,114.6`——
 * 换算成圆心 (100,100)、上下各从水平线向内 12° 收口的两段 156° 弧。播放头是
 * `M-24,-30 L31,0 L-24,30 Z` 平移到 (106,100)，13 宽圆角描边抹圆三个尖角，再沿
 * y=100 上下分色。
 *
 * **深浅色适配：** 标志本体两个主题**共用一套色**——火弧最亮处 `#FFB524`、水弧最深处
 * `#0A3BB5`，在 `#F7F8FB` 与 `#05070C` 上都能立住，所以不按主题换色（设计稿 9a/9b
 * 就是同一份 mark 分别放在深底和浅底上）。需要跟主题走的是**外壳与陪衬**：图标壳统一
 * `#05070C`（[LogoPalette.Shell]），闪屏底色与字标由 [com.yfuse.app] 的两套 skin 决定。
 */
private const val VIEWPORT = 200f
private const val CENTER = 100f

/** 标志占壳内 82%：ink 直径 164 / 200。留白交给调用方的尺寸。 */
private const val ContentScale = 1.0f

/** 双半弧。 */
private const val RingRadius = 70f
private const val RingStroke = 24f

/** 左右缺口各 24°，所以每条弧从水平线向内收 12°、扫过 156°。 */
private const val ArcGapHalf = 12f
const val YfuseArcSweep = 180f - ArcGapHalf * 2f
private const val FireStart = -180f + ArcGapHalf
private const val WaterStart = ArcGapHalf

/** 播放头：三角本体 + 抹圆用的描边；向右偏 6 做光学居中。 */
private const val HeadStroke = 13f
private const val HeadCenterX = 106f
private val HeadPoints = listOf(-24f to -30f, 31f to 0f, -24f to 30f)

/** 含圆头端点在内，标识实际占据的半径。 */
private const val InkRadius = RingRadius + RingStroke / 2f

/**
 * 渐变对角线的三个端点分量。SVG 里 `gradientUnits` 默认是 objectBoundingBox，
 * 所以 `x1=.1 y1=1 x2=.9 y2=0` 量的是**那一条弧自己**的外接框（半个 200 画布），
 * 不是整张画布——按整高铺会让弧走不到 `#FFB524` / `#0A3BB5` 那一端。
 */
private const val GradHalfX = 64.4f
private const val GradNearY = 2.5f
private const val GradFarY = 82f

/** 16px 以下的降级版：去掉播放头，只留加粗的双弧。 */
private const val SmallRingRadius = 64f
private const val SmallRingStroke = 30f

object LogoPalette {
    /** `g9Fire` — 左下 → 右上。 */
    val Fire = listOf(
        Color(0xFFE5290B),
        Color(0xFFFF6A16),
        Color(0xFFFFB524),
    )

    /** `g9Water` — 右上 → 左下。 */
    val Water = listOf(
        Color(0xFF5BE3FF),
        Color(0xFF14A9F0),
        Color(0xFF0A3BB5),
    )

    /** 播放头上半取火的端色，下半取水的端色。 */
    val HeadFire = Color(0xFFFF4A0F)
    val HeadWater = Color(0xFF0B49BE)

    /** 图标壳 —— 深浅色统一。 */
    val Shell = Color(0xFF05070C)

    /** 单色态：反白场景（深底图标、通知小图标）用。 */
    val Mono = Color(0xFFFFFFFF)
}

// ---------------------------------------------------------------- 绘制原语

private fun DrawScope.unit(): Float = size.minDimension / VIEWPORT * ContentScale

/** 火弧渐变 `x1=.1 y1=1 x2=.9 y2=0`，沿上半弧外接框的左下 → 右上铺开。 */
fun DrawScope.yfuseFireBrush(colors: List<Color> = LogoPalette.Fire): Brush {
    val u = unit()
    val cx = size.width / 2f
    val cy = size.height / 2f
    return Brush.linearGradient(
        colors = colors,
        start = Offset(cx - GradHalfX * u, cy - GradNearY * u),
        end = Offset(cx + GradHalfX * u, cy - GradFarY * u),
    )
}

/** 水弧渐变 `x1=.9 y1=0 x2=.1 y2=1`——下半弧外接框的右上 → 左下，与火弧对称。 */
fun DrawScope.yfuseWaterBrush(colors: List<Color> = LogoPalette.Water): Brush {
    val u = unit()
    val cx = size.width / 2f
    val cy = size.height / 2f
    return Brush.linearGradient(
        colors = colors,
        start = Offset(cx + GradHalfX * u, cy + GradNearY * u),
        end = Offset(cx - GradHalfX * u, cy + GradFarY * u),
    )
}

/**
 * 一条半弧。[sweep] 小于 [YfuseArcSweep] 时弧只画到一半——闪屏靠它做「沿路径生长」，
 * 圆头端点会跟着笔尖走。
 */
private fun DrawScope.drawArcStroke(
    brush: Brush,
    startAngle: Float,
    sweep: Float,
    radius: Float = RingRadius,
    stroke: Float = RingStroke,
) {
    if (sweep <= 0f) return
    val u = unit()
    val r = radius * u
    val cx = size.width / 2f
    val cy = size.height / 2f
    drawArc(
        brush = brush,
        startAngle = startAngle,
        sweepAngle = sweep,
        useCenter = false,
        topLeft = Offset(cx - r, cy - r),
        size = Size(r * 2f, r * 2f),
        style = Stroke(width = stroke * u, cap = StrokeCap.Round),
    )
}

/** 火弧（上半）——自左向右生长。 */
fun DrawScope.drawYfuseFireArc(brush: Brush, sweep: Float = YfuseArcSweep) =
    drawArcStroke(brush, FireStart, sweep)

/** 水弧（下半）——自右向左生长。 */
fun DrawScope.drawYfuseWaterArc(brush: Brush, sweep: Float = YfuseArcSweep) =
    drawArcStroke(brush, WaterStart, sweep)

private fun DrawScope.headPath(): Path {
    val u = unit()
    val cx = size.width / 2f + (HeadCenterX - CENTER) * u
    val cy = size.height / 2f
    return Path().apply {
        HeadPoints.forEachIndexed { index, (dx, dy) ->
            val px = cx + dx * u
            val py = cy + dy * u
            if (index == 0) moveTo(px, py) else lineTo(px, py)
        }
        close()
    }
}

/**
 * 播放头。先填充再描边——描边只为把三个角抹圆；随后沿中线上下分色，火色压上、水色压下。
 */
fun DrawScope.drawYfusePlayhead(
    fire: Color = LogoPalette.HeadFire,
    water: Color = LogoPalette.HeadWater,
) {
    val path = headPath()
    val u = unit()
    val stroke = Stroke(width = HeadStroke * u, join = StrokeJoin.Round)
    val mid = size.height / 2f
    clipRect(top = 0f, bottom = mid, clipOp = ClipOp.Intersect) {
        drawPath(path, fire)
        drawPath(path, fire, style = stroke)
    }
    clipRect(top = mid, bottom = size.height, clipOp = ClipOp.Intersect) {
        drawPath(path, water)
        drawPath(path, water, style = stroke)
    }
}

/** 单色播放头——反白与单色图标用。 */
fun DrawScope.drawYfusePlayheadMono(color: Color) {
    val path = headPath()
    drawPath(path, color)
    drawPath(path, color, style = Stroke(width = HeadStroke * unit(), join = StrokeJoin.Round))
}

// ---------------------------------------------------------------- 组合件

/** 完整标识：火弧 + 水弧 + 分色播放头。尺寸由调用方给定，内容按短边等比铺满。 */
@Composable
fun YfuseMark(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawYfuseFireArc(yfuseFireBrush())
        drawYfuseWaterArc(yfuseWaterBrush())
        drawYfusePlayhead()
    }
}

/**
 * 16px 以下的降级版：弧加粗到 30、半径收到 64，去掉播放头——那么小的三角只会糊成一团。
 */
@Composable
fun YfuseMarkSmall(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawArcStroke(
            yfuseFireBrush(),
            FireStart,
            YfuseArcSweep,
            SmallRingRadius,
            SmallRingStroke,
        )
        drawArcStroke(
            yfuseWaterBrush(),
            WaterStart,
            YfuseArcSweep,
            SmallRingRadius,
            SmallRingStroke,
        )
    }
}

/** 单色标识——黑白底通吃，直接给一个墨色即可。 */
@Composable
fun YfuseMarkMono(modifier: Modifier = Modifier, color: Color = LogoPalette.Mono) {
    Canvas(modifier) {
        val brush = Brush.linearGradient(listOf(color, color))
        drawArcStroke(brush, FireStart, YfuseArcSweep)
        drawArcStroke(brush, WaterStart, YfuseArcSweep)
        drawYfusePlayheadMono(color)
    }
}
