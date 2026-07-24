package com.yfuse.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The spec's icon set: linear icons on a 24×24 viewBox, `stroke=currentColor`,
 * transcribed from the spec's `icons` folder. Tab-bar glyphs come from the inline
 * SVG instead, which draws the same shapes at `stroke-width:1.9`.
 */
object AppIcons {

    // ------------------------------------------------------------ tab bar (1.9)

    val Home = strokeVector("home", width = 1.9f, join = StrokeJoin.Round) {
        // M4 11.2 12 4l8 7.2
        moveTo(4f, 11.2f); lineTo(12f, 4f); lineToRelative(8f, 7.2f)
    }.andPath(width = 1.9f, join = StrokeJoin.Round) {
        // M6 10v9.5h12V10
        moveTo(6f, 10f); verticalLineToRelative(9.5f); horizontalLineTo(18f); verticalLineTo(10f)
    }.andPath(width = 1.9f, join = StrokeJoin.Round, cap = StrokeCap.Round) {
        // M10 19.5v-5.5h4v5.5
        moveTo(10f, 19.5f); verticalLineToRelative(-5.5f); horizontalLineToRelative(4f); verticalLineToRelative(5.5f)
    }.build()

    val Grid = strokeVector("grid", width = 1.9f) {
        roundRect(4f, 4f, 7f, 7f, 1.6f)
        roundRect(13f, 4f, 7f, 7f, 1.6f)
        roundRect(4f, 13f, 7f, 7f, 1.6f)
        roundRect(13f, 13f, 7f, 7f, 1.6f)
    }.build()

    val SearchTab = strokeVector("search-tab", width = 1.9f, cap = StrokeCap.Round) {
        circle(10.5f, 10.5f, 6.5f)
    }.andPath(width = 1.9f, cap = StrokeCap.Round) {
        moveTo(20f, 20f); lineToRelative(-4.8f, -4.8f)
    }.build()

    val User = strokeVector("user", width = 1.9f, cap = StrokeCap.Round) {
        circle(12f, 8f, 3.6f)
    }.andPath(width = 1.9f, cap = StrokeCap.Round) {
        // M4.8 20c1-4.2 4-6.2 7.2-6.2s6.2 2 7.2 6.2
        moveTo(4.8f, 20f)
        curveToRelative(1f, -4.2f, 4f, -6.2f, 7.2f, -6.2f)
        reflectiveCurveToRelative(6.2f, 2f, 7.2f, 6.2f)
    }.build()

    // ------------------------------------------------------------ icon set (1.8)

    /** Filled triangle — `M6 4.5v15l14-7.5-14-7.5z`. */
    val Play = fillVector("play") {
        moveTo(6f, 4.5f); verticalLineToRelative(15f); lineToRelative(14f, -7.5f); lineToRelative(-14f, -7.5f); close()
    }

    val Pause = fillVector("pause") {
        roundRect(5f, 4f, 5f, 16f, 1.5f)
        roundRect(14f, 4f, 5f, 16f, 1.5f)
    }

    /** `M12 5v14l-9-7z` + `M22 5v14l-9-7z` */
    val Rewind = fillVector("rewind") {
        moveTo(12f, 5f); verticalLineToRelative(14f); lineToRelative(-9f, -7f); close()
        moveTo(22f, 5f); verticalLineToRelative(14f); lineToRelative(-9f, -7f); close()
    }

    /** Mirror of [Rewind] across x = 12. */
    val Forward = fillVector("forward") {
        moveTo(12f, 5f); verticalLineToRelative(14f); lineToRelative(9f, -7f); close()
        moveTo(2f, 5f); verticalLineToRelative(14f); lineToRelative(9f, -7f); close()
    }

    val Lock = strokeVector("lock") {
        roundRect(5f, 11f, 14f, 9f, 2f)
    }.andPath {
        // M8 11V7.5a4 4 0 0 1 8 0V11
        moveTo(8f, 11f); verticalLineTo(7.5f)
        arcToRelative(4f, 4f, 0f, isMoreThanHalf = false, isPositiveArc = true, 8f, 0f)
        verticalLineTo(11f)
    }.build()

    val Unlock = strokeVector("unlock") {
        roundRect(5f, 11f, 14f, 9f, 2f)
    }.andPath {
        // M8 11V7.5a4 4 0 0 1 7.4-2.1
        moveTo(8f, 11f); verticalLineTo(7.5f)
        arcToRelative(4f, 4f, 0f, isMoreThanHalf = false, isPositiveArc = true, 7.4f, -2.1f)
    }.build()

    /** `M4 6h16M4 12h16M4 18h10` */
    val Menu = strokeVector("menu", cap = StrokeCap.Round) {
        moveTo(4f, 6f); horizontalLineToRelative(16f)
        moveTo(4f, 12f); horizontalLineToRelative(16f)
        moveTo(4f, 18f); horizontalLineToRelative(10f)
    }.build()

    /** `M9 4H4v5M15 4h5v5M9 20H4v-5M15 20h5v-5` */
    val Expand = strokeVector("expand", cap = StrokeCap.Round, join = StrokeJoin.Round) {
        moveTo(9f, 4f); horizontalLineTo(4f); verticalLineToRelative(5f)
        moveTo(15f, 4f); horizontalLineToRelative(5f); verticalLineToRelative(5f)
        moveTo(9f, 20f); horizontalLineTo(4f); verticalLineToRelative(-5f)
        moveTo(15f, 20f); horizontalLineToRelative(5f); verticalLineToRelative(-5f)
    }.build()

    /** `M4 9h5V4M20 9h-5V4M4 15h5v5M20 15h-5v5` */
    val Collapse = strokeVector("collapse", cap = StrokeCap.Round, join = StrokeJoin.Round) {
        moveTo(4f, 9f); horizontalLineToRelative(5f); verticalLineTo(4f)
        moveTo(20f, 9f); horizontalLineToRelative(-5f); verticalLineTo(4f)
        moveTo(4f, 15f); horizontalLineToRelative(5f); verticalLineToRelative(5f)
        moveTo(20f, 15f); horizontalLineToRelative(-5f); verticalLineToRelative(5f)
    }.build()

    val Subtitle = strokeVector("subtitle") {
        roundRect(3f, 6f, 18f, 12f, 2.5f)
    }.andPath(cap = StrokeCap.Round) {
        moveTo(7f, 14f); horizontalLineToRelative(3f)
        moveTo(7f, 10f); horizontalLineToRelative(6f)
        moveTo(13.5f, 14f); horizontalLineToRelative(3.5f)
    }.build()

    val Volume = strokeVector("volume", join = StrokeJoin.Round) {
        // M4 10v4h4l5 4V6L8 10H4z
        moveTo(4f, 10f); verticalLineToRelative(4f); horizontalLineToRelative(4f)
        lineToRelative(5f, 4f); verticalLineTo(6f); lineTo(8f, 10f); horizontalLineTo(4f); close()
    }.andPath(cap = StrokeCap.Round) {
        // M16.5 9a4 4 0 0 1 0 6
        moveTo(16.5f, 9f)
        arcToRelative(4f, 4f, 0f, isMoreThanHalf = false, isPositiveArc = true, 0f, 6f)
    }.build()

    /** `M15 5l-7 7 7 7` */
    val ChevronLeft = strokeVector("chevron-left", cap = StrokeCap.Round, join = StrokeJoin.Round) {
        moveTo(15f, 5f); lineToRelative(-7f, 7f); lineToRelative(7f, 7f)
    }.build()

    /** `M5 9l7 7 7-7` */
    val ChevronDown = strokeVector("chevron-down", cap = StrokeCap.Round, join = StrokeJoin.Round) {
        moveTo(5f, 9f); lineToRelative(7f, 7f); lineToRelative(7f, -7f)
    }.build()

    /** Rotated [ChevronDown]: `M9 5l7 7-7 7`. */
    val ChevronRight = strokeVector("chevron-right", cap = StrokeCap.Round, join = StrokeJoin.Round) {
        moveTo(9f, 5f); lineToRelative(7f, 7f); lineToRelative(-7f, 7f)
    }.build()

    /** `M4 12.5l5.5 5.5L20 6.5` */
    val Check = strokeVector("check", cap = StrokeCap.Round, join = StrokeJoin.Round) {
        moveTo(4f, 12.5f); lineToRelative(5.5f, 5.5f); lineTo(20f, 6.5f)
    }.build()

    /** `M5 5l14 14M19 5L5 19` */
    val Close = strokeVector("close", cap = StrokeCap.Round) {
        moveTo(5f, 5f); lineToRelative(14f, 14f)
        moveTo(19f, 5f); lineTo(5f, 19f)
    }.build()

    val Search = strokeVector("search") {
        circle(10.5f, 10.5f, 6.5f)
    }.andPath(cap = StrokeCap.Round) {
        moveTo(20f, 20f); lineToRelative(-4.8f, -4.8f)
    }.build()

    /** `M12 5v14M5 12h14` */
    val Add = strokeVector("add", cap = StrokeCap.Round) {
        moveTo(12f, 5f); verticalLineToRelative(14f)
        moveTo(5f, 12f); horizontalLineToRelative(14f)
    }.build()
}

// ---------------------------------------------------------------- vector plumbing

private const val VIEWPORT = 24f

/** Accumulates stroked sub-paths so one icon can mix caps and joins. */
private class VectorParts(
    val name: String,
    val builder: ImageVector.Builder,
)

private fun strokeVector(
    name: String,
    width: Float = 1.8f,
    cap: StrokeCap = StrokeCap.Butt,
    join: StrokeJoin = StrokeJoin.Miter,
    block: PathBuilder.() -> Unit,
): VectorParts =
    VectorParts(name, newBuilder(name)).andPath(width, cap, join, block)

private fun VectorParts.andPath(
    width: Float = 1.8f,
    cap: StrokeCap = StrokeCap.Butt,
    join: StrokeJoin = StrokeJoin.Miter,
    block: PathBuilder.() -> Unit,
): VectorParts = apply {
    builder.path(
        fill = null,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = width,
        strokeLineCap = cap,
        strokeLineJoin = join,
        pathBuilder = block,
    )
}

private fun VectorParts.build(): ImageVector = builder.build()

private fun fillVector(name: String, block: PathBuilder.() -> Unit): ImageVector =
    newBuilder(name)
        .path(fill = SolidColor(Color.Black), pathBuilder = block)
        .build()

private fun newBuilder(name: String) = ImageVector.Builder(
    name = name,
    defaultWidth = VIEWPORT.dp,
    defaultHeight = VIEWPORT.dp,
    viewportWidth = VIEWPORT,
    viewportHeight = VIEWPORT,
)

/** SVG `<circle>` as two half arcs. */
private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
    moveTo(cx - r, cy)
    arcToRelative(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, 2 * r, 0f)
    arcToRelative(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, -2 * r, 0f)
    close()
}

/** SVG `<rect rx>`. */
private fun PathBuilder.roundRect(x: Float, y: Float, w: Float, h: Float, r: Float) {
    moveTo(x + r, y)
    horizontalLineTo(x + w - r)
    arcToRelative(r, r, 0f, isMoreThanHalf = false, isPositiveArc = true, r, r)
    verticalLineTo(y + h - r)
    arcToRelative(r, r, 0f, isMoreThanHalf = false, isPositiveArc = true, -r, r)
    horizontalLineTo(x + r)
    arcToRelative(r, r, 0f, isMoreThanHalf = false, isPositiveArc = true, -r, -r)
    verticalLineTo(y + r)
    arcToRelative(r, r, 0f, isMoreThanHalf = false, isPositiveArc = true, r, -r)
    close()
}
