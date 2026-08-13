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
 * The app's icon set: one geometry, one weight, one set of ends.
 *
 * It did not start that way. The glyphs were transcribed from the spec's SVGs one at a
 * time, and each carried whatever its source happened to specify, so the set drifted in
 * three directions at once:
 *
 *  * **Ends.** [strokeVector]'s defaults were butt caps and mitre joins, so every icon that
 *    did not name its own — 锁, 字幕, 音量, 心形, 书签, 星 — was drawn with squared-off ends
 *    inside an interface made entirely of rounded glass. The shackle on 锁 looked snipped.
 *  * **Weight.** Four stroke widths were in play: 1.9 for the tab bar, 1.8 for the set,
 *    2.0 for 下载, 2.2 for 聊天's dots. Side by side in one row — the player's chip strip
 *    does exactly that — the difference reads as some icons being bolder than others.
 *  * **Size.** No shared optical box. 云 ran the full 24 units edge to edge while 播放 sat
 *    in 14, so at a common `size(13.dp)` one was half again as big as the other.
 *
 * The system now, and what a new icon has to follow:
 *
 *  * **24×24 viewport, [BOX] optical square.** Everything is drawn inside x,y ∈ [3, 21].
 *    Icons need not fill it — a chevron shouldn't — but nothing may exceed it.
 *  * **[STROKE] everywhere**, round caps, round joins. These are the defaults, so an icon
 *    gets them by saying nothing.
 *  * **Solid glyphs are [SOLID_BOX]**, one unit inside the outline box, because a filled
 *    shape reads heavier than an outlined one of equal size — and they carry a hairline
 *    round-joined outline of their own so their corners match the rounded set around them.
 */
object AppIcons {
    // ------------------------------------------------------------ navigation

    /** Navigation glyphs use a larger optical box and heavier rounded edge for glass ink. */
    val Home =
        strokeVector("home", width = TAB_STROKE) {
            moveTo(2.9f, 11.3f)
            lineTo(12f, 3.5f)
            lineTo(21.1f, 11.3f)
        }.andPath(width = TAB_STROKE) {
            moveTo(5.2f, 9.7f)
            verticalLineTo(20.5f)
            horizontalLineTo(18.8f)
            verticalLineTo(9.7f)
        }.andPath(width = TAB_STROKE) {
            moveTo(9.3f, 20.5f)
            verticalLineTo(14.1f)
            horizontalLineTo(14.7f)
            verticalLineTo(20.5f)
        }.build()

    val Grid =
        strokeVector("grid", width = TAB_STROKE) {
            roundRect(2.9f, 2.9f, 8.1f, 8.1f, 2.3f)
            roundRect(13f, 2.9f, 8.1f, 8.1f, 2.3f)
            roundRect(2.9f, 13f, 8.1f, 8.1f, 2.3f)
            roundRect(13f, 13f, 8.1f, 8.1f, 2.3f)
        }.build()

    val SearchTab =
        search(
            name = "search-tab",
            width = TAB_STROKE,
            radius = 7.2f,
            center = 10.2f,
            handleEnd = 21f,
        )

    val User =
        strokeVector("user", width = TAB_STROKE) {
            circle(12f, 7.7f, 3.9f)
        }.andPath(width = TAB_STROKE) {
            moveTo(3.8f, 20.6f)
            curveToRelative(1.05f, -4.65f, 4.45f, -6.75f, 8.2f, -6.75f)
            reflectiveCurveToRelative(7.15f, 2.1f, 8.2f, 6.75f)
        }.build()

    /** Two stacked units with their status lamps. */
    val Server =
        strokeVector("server", width = TAB_STROKE) {
            roundRect(2.9f, 3.4f, 18.2f, 6.8f, 2.2f)
            roundRect(2.9f, 13.8f, 18.2f, 6.8f, 2.2f)
        }.andDots(7.1f to 6.8f, 7.1f to 17.2f).build()

    // ------------------------------------------------------------ transport

    val Play =
        solidVector("play") {
            moveTo(7.4f, 5.4f)
            lineTo(19.4f, 12f)
            lineTo(7.4f, 18.6f)
            close()
        }

    val Pause =
        solidVector("pause") {
            roundRect(7.3f, 5.2f, 3.6f, 13.6f, 1.4f)
            roundRect(13.1f, 5.2f, 3.6f, 13.6f, 1.4f)
        }

    /** Two heads pointing back, inside the solid box rather than off the viewport edge. */
    val Rewind =
        solidVector("rewind") {
            moveTo(11.7f, 5.6f)
            verticalLineToRelative(12.8f)
            lineTo(3.9f, 12f)
            close()
            moveTo(20.1f, 5.6f)
            verticalLineToRelative(12.8f)
            lineTo(12.3f, 12f)
            close()
        }

    /** Mirror of [Rewind] across x = 12. */
    val Forward =
        solidVector("forward") {
            moveTo(12.3f, 5.6f)
            verticalLineToRelative(12.8f)
            lineTo(20.1f, 12f)
            close()
            moveTo(3.9f, 5.6f)
            verticalLineToRelative(12.8f)
            lineTo(11.7f, 12f)
            close()
        }

    val Previous =
        solidVector("previous") {
            roundRect(4.4f, 5.6f, 2.6f, 12.8f, 1.2f)
            moveTo(19.4f, 5.6f)
            verticalLineToRelative(12.8f)
            lineTo(8.6f, 12f)
            close()
        }

    val Next =
        solidVector("next") {
            roundRect(17f, 5.6f, 2.6f, 12.8f, 1.2f)
            moveTo(4.6f, 5.6f)
            verticalLineToRelative(12.8f)
            lineTo(15.4f, 12f)
            close()
        }

    // ------------------------------------------------------------ player chrome

    val Lock =
        strokeVector("lock") {
            roundRect(4.6f, 10.6f, 14.8f, 9.4f, 2.4f)
        }.andPath {
            moveTo(8f, 10.6f)
            verticalLineTo(7.6f)
            arcToRelative(4f, 4f, 0f, isMoreThanHalf = false, isPositiveArc = true, 8f, 0f)
            verticalLineTo(10.6f)
        }.build()

    val Unlock =
        strokeVector("unlock") {
            roundRect(4.6f, 10.6f, 14.8f, 9.4f, 2.4f)
        }.andPath {
            moveTo(8f, 10.6f)
            verticalLineTo(7.6f)
            arcToRelative(4f, 4f, 0f, isMoreThanHalf = false, isPositiveArc = true, 7.4f, -2.1f)
        }.build()

    val Menu =
        strokeVector("menu") {
            moveTo(4f, 6.6f)
            horizontalLineTo(20f)
            moveTo(4f, 12f)
            horizontalLineTo(20f)
            moveTo(4f, 17.4f)
            horizontalLineTo(14f)
        }.build()

    val Expand =
        strokeVector("expand") {
            moveTo(9.2f, 4.2f)
            horizontalLineTo(4.2f)
            verticalLineTo(9.2f)
            moveTo(14.8f, 4.2f)
            horizontalLineTo(19.8f)
            verticalLineTo(9.2f)
            moveTo(9.2f, 19.8f)
            horizontalLineTo(4.2f)
            verticalLineTo(14.8f)
            moveTo(14.8f, 19.8f)
            horizontalLineTo(19.8f)
            verticalLineTo(14.8f)
        }.build()

    val Collapse =
        strokeVector("collapse") {
            moveTo(4.2f, 9.2f)
            horizontalLineTo(9.2f)
            verticalLineTo(4.2f)
            moveTo(19.8f, 9.2f)
            horizontalLineTo(14.8f)
            verticalLineTo(4.2f)
            moveTo(4.2f, 14.8f)
            horizontalLineTo(9.2f)
            verticalLineTo(19.8f)
            moveTo(19.8f, 14.8f)
            horizontalLineTo(14.8f)
            verticalLineTo(19.8f)
        }.build()

    val Subtitle =
        strokeVector("subtitle") {
            roundRect(3.2f, 5.8f, 17.6f, 12.4f, 2.8f)
        }.andPath {
            moveTo(6.8f, 14.4f)
            horizontalLineToRelative(3f)
            moveTo(6.8f, 10.2f)
            horizontalLineToRelative(6f)
            moveTo(13.4f, 14.4f)
            horizontalLineToRelative(3.8f)
        }.build()

    val Danmaku =
        strokeVector("danmaku") {
            roundRect(3.2f, 4.6f, 17.6f, 12.8f, 3.2f)
            moveTo(8f, 17.4f)
            lineTo(6.2f, 20.4f)
            lineTo(11.8f, 17.4f)
        }.andPath {
            moveTo(7f, 9.6f)
            horizontalLineTo(17f)
            moveTo(7f, 13.4f)
            horizontalLineTo(14f)
        }.build()

    /** Room chat — the same bubble as 弹幕, saying nothing rather than carrying lines. */
    val Chat =
        strokeVector("chat") {
            roundRect(3.2f, 4.6f, 17.6f, 12.8f, 3.2f)
            moveTo(8f, 17.4f)
            lineTo(6.2f, 20.4f)
            lineTo(11.8f, 17.4f)
        }.andDots(8f to 11f, 12f to 11f, 16f to 11f).build()

    val Volume =
        strokeVector("volume") {
            moveTo(3.8f, 9.8f)
            verticalLineToRelative(4.4f)
            horizontalLineToRelative(3.9f)
            lineToRelative(4.9f, 3.9f)
            verticalLineTo(5.9f)
            lineTo(7.7f, 9.8f)
            close()
        }.andPath {
            moveTo(16.3f, 9.1f)
            arcToRelative(4f, 4f, 0f, isMoreThanHalf = false, isPositiveArc = true, 0f, 5.8f)
        }.build()

    val Cast =
        strokeVector("cast") {
            moveTo(6.6f, 16.2f)
            horizontalLineTo(4.8f)
            arcToRelative(1.6f, 1.6f, 0f, isMoreThanHalf = false, isPositiveArc = true, -1.6f, -1.6f)
            verticalLineTo(6.4f)
            arcToRelative(1.6f, 1.6f, 0f, isMoreThanHalf = false, isPositiveArc = true, 1.6f, -1.6f)
            horizontalLineTo(19.2f)
            arcToRelative(1.6f, 1.6f, 0f, isMoreThanHalf = false, isPositiveArc = true, 1.6f, 1.6f)
            verticalLineTo(14.6f)
            arcToRelative(1.6f, 1.6f, 0f, isMoreThanHalf = false, isPositiveArc = true, -1.6f, 1.6f)
            horizontalLineTo(17.4f)
            moveTo(12f, 13.4f)
            lineTo(16.4f, 19.8f)
            horizontalLineTo(7.6f)
            close()
        }.build()

    val PictureInPicture =
        strokeVector("picture-in-picture") {
            roundRect(3.2f, 5.2f, 17.6f, 13.6f, 2.6f)
            roundRect(12.2f, 11.4f, 6.6f, 5f, 1.4f)
        }.build()

    // ------------------------------------------------------------ controls

    val More = dotVector("more", 5.9f to 12f, 12f to 12f, 18.1f to 12f)

    /** Pencil — 编辑服务器, and anything else that renames rather than replaces. */
    val Edit =
        strokeVector("edit") {
            moveTo(14.6f, 5.2f)
            lineTo(18.8f, 9.4f)
            lineTo(8.2f, 20f)
            lineTo(4f, 20f)
            verticalLineTo(15.8f)
            close()
        }.andPath {
            // Where the shaft ends and the tip begins.
            moveTo(13.2f, 6.6f)
            lineTo(17.4f, 10.8f)
        }.build()

    val ChevronLeft =
        strokeVector("chevron-left") {
            moveTo(14.8f, 5.2f)
            lineTo(8.4f, 12f)
            lineTo(14.8f, 18.8f)
        }.build()

    val ChevronDown =
        strokeVector("chevron-down") {
            moveTo(5.2f, 9.2f)
            lineTo(12f, 15.6f)
            lineTo(18.8f, 9.2f)
        }.build()

    val ChevronRight =
        strokeVector("chevron-right") {
            moveTo(9.2f, 5.2f)
            lineTo(15.6f, 12f)
            lineTo(9.2f, 18.8f)
        }.build()

    val Check =
        strokeVector("check") {
            moveTo(4.6f, 12.6f)
            lineTo(9.7f, 17.7f)
            lineTo(19.4f, 6.9f)
        }.build()

    val Close =
        strokeVector("close") {
            moveTo(5.6f, 5.6f)
            lineTo(18.4f, 18.4f)
            moveTo(18.4f, 5.6f)
            lineTo(5.6f, 18.4f)
        }.build()

    val Search = search("search")

    val Add =
        strokeVector("add") {
            moveTo(12f, 4.8f)
            verticalLineTo(19.2f)
            moveTo(4.8f, 12f)
            horizontalLineTo(19.2f)
        }.build()

    /**
     * ↻ — 追剧日历's manual re-check.
     *
     * Three quarters of a circle with an arrowhead on the open end, which is the shape
     * everyone reads as "do that again".
     */
    val Refresh =
        strokeVector("refresh") {
            moveTo(19.2f, 8.8f)
            arcToRelative(7.6f, 7.6f, 0f, isMoreThanHalf = true, isPositiveArc = false, 1.4f, 4.2f)
            moveTo(19.2f, 4.2f)
            verticalLineTo(8.8f)
            horizontalLineTo(14.6f)
        }.build()

    val Download =
        strokeVector("download") {
            moveTo(12f, 4.4f)
            verticalLineTo(14.8f)
            moveTo(12f, 14.8f)
            lineTo(7.7f, 10.5f)
            moveTo(12f, 14.8f)
            lineTo(16.3f, 10.5f)
            moveTo(4.8f, 19.2f)
            horizontalLineTo(19.2f)
        }.build()

    /**
     * 片源 — the detail page marks each reachable server with a cloud.
     *
     * Redrawn on the shared box. The transcribed original was built on an r=8 lobe that put
     * the shape from x=1 to x=23, half again the width of everything it sat beside.
     */
    val Cloud =
        strokeVector("cloud") {
            moveTo(17.2f, 11.2f)
            horizontalLineToRelative(-1.05f)
            arcToRelative(6.2f, 6.2f, 0f, isMoreThanHalf = true, isPositiveArc = false, -6f, 7.75f)
            horizontalLineToRelative(7.05f)
            arcToRelative(3.9f, 3.9f, 0f, isMoreThanHalf = false, isPositiveArc = false, 0f, -7.75f)
            close()
        }.build()

    /** 详情 — the button supplies the glass circle, so the glyph must not supply another. */
    val Info =
        strokeVector("info") {
            moveTo(12f, 10.2f)
            verticalLineTo(18.2f)
        }.andDots(12f to 5.8f)
            .build()

    // ------------------------------------------------- state glyphs (收藏 / 稍后 / 评分)

    /** 收藏 — outline; [HeartFilled] is the same contour filled. */
    val Heart = strokeVector("heart") { heart() }.build()
    val HeartFilled = solidVector("heart-filled") { heart() }

    /** 稍后观看 — outline bookmark; [BookmarkFilled] marks the queued state. */
    val Bookmark = strokeVector("bookmark") { bookmark() }.build()
    val BookmarkFilled = solidVector("bookmark-filled") { bookmark() }

    /** 评分 — the star that precedes a community rating. */
    val Star = strokeVector("star") { star() }.build()
    val StarFilled = solidVector("star-filled") { star() }

    // ------------------------------------------------------------ library counts

    /**
     * 电影 — a film body with its two perforation strips.
     *
     * Drawn as one rounded rect plus four sprocket holes rather than the usual clapperboard:
     * at the 12dp the server cards use it, a clapper's angled bar collapses into a smudge,
     * while holes stay legible because they are the widest features on the glyph.
     */
    val Movie =
        strokeVector("movie") {
            roundRect(2.9f, 5.2f, 18.2f, 13.6f, 2.4f)
        }.andPath {
            moveTo(7.4f, 5.2f)
            verticalLineTo(18.8f)
        }.andPath {
            moveTo(16.6f, 5.2f)
            verticalLineTo(18.8f)
        }.andDots(5.1f to 8.6f, 5.1f to 15.4f, 18.9f to 8.6f, 18.9f to 15.4f)
            .build()

    /** 剧集 — a screen on a stand, the counterpart to [Movie]. */
    val Series =
        strokeVector("series") {
            roundRect(2.9f, 6.6f, 18.2f, 12.2f, 2.4f)
        }.andPath {
            moveTo(8.2f, 2.9f)
            lineTo(12f, 6.6f)
            lineTo(15.8f, 2.9f)
        }.build()

    // ------------------------------------------------------------ navigation
    //
    // The bar's own family, drawn apart from the general set.
    //
    // The tabs used to borrow generic glyphs — a house, four squares, a person — which say
    // "an app" rather than "this app", and at the bar's 30dp they were four outlines of
    // roughly equal weight with nothing to tell them apart at a glance. Each of these has one
    // feature that is unmistakable in peripheral vision: a triangle inside a screen, the
    // offset edge of a stack, a status lamp, a shoulder. They share [TAB_STROKE], the same
    // optical box, and the same corner radius, so they still read as one set.

    /** 首页 — a screen with a play mark: what the app is for, not where you live. */
    val TabHome =
        strokeVector("tab-home", width = TAB_STROKE) {
            roundRect(2.6f, 4.4f, 18.8f, 13.4f, 3.1f)
        }.andPath(width = TAB_STROKE) {
            moveTo(8.4f, 20.9f)
            horizontalLineTo(15.6f)
        }.andPath(width = TAB_STROKE) {
            // A triangle with its own rounded joins, set slightly right of centre so its optical
            // mass lands on the middle of the screen rather than its geometric centre.
            moveTo(10.3f, 8.1f)
            lineTo(15.1f, 11.1f)
            lineTo(10.3f, 14.1f)
            close()
        }.build()

    /** 库 — a stack of posters seen from the front, the top one offset. */
    val TabLibrary =
        strokeVector("tab-library", width = TAB_STROKE) {
            roundRect(3.1f, 6.5f, 11.4f, 14.2f, 2.4f)
        }.andPath(width = TAB_STROKE) {
            // Two receding edges rather than two more rectangles: at 30dp a second full outline
            // closes up into a blob, while a pair of arcs reads as depth.
            moveTo(17f, 8.4f)
            verticalLineTo(18.8f)
        }.andPath(width = TAB_STROKE) {
            moveTo(20.4f, 10.6f)
            verticalLineTo(16.6f)
        }.build()

    /** 服务器 — one unit with its lamp lit and the signal it is answering on. */
    val TabServers =
        strokeVector("tab-servers", width = TAB_STROKE) {
            roundRect(2.7f, 9.4f, 18.6f, 8.4f, 2.6f)
        }.andPath(width = TAB_STROKE) {
            // The arc above is what separates this from a plain box: a machine that is reachable,
            // rather than a machine.
            moveTo(7.1f, 6.2f)
            curveToRelative(2.9f, -2.6f, 6.9f, -2.6f, 9.8f, 0f)
        }.andPath(width = TAB_STROKE) {
            moveTo(6.6f, 20.6f)
            horizontalLineTo(17.4f)
        }.andDots(6.6f to 13.6f)
            .build()

    /** 我的 — head and shoulders, with the shoulder line left open. */
    val TabProfile =
        strokeVector("tab-profile", width = TAB_STROKE) {
            circle(12f, 8.2f, 3.7f)
        }.andPath(width = TAB_STROKE) {
            moveTo(4.6f, 20.4f)
            curveToRelative(0.9f, -4.3f, 3.9f, -6.3f, 7.4f, -6.3f)
            reflectiveCurveToRelative(6.5f, 2f, 7.4f, 6.3f)
        }.build()
}

// ---------------------------------------------------------------- the system

private const val VIEWPORT = 24f

/** The optical square every glyph is drawn inside: x, y ∈ [3, 21]. */
private const val BOX = 18f

/** One weight for the whole set. */
private const val STROKE = 1.8f

/** Fine rounded edge for the enlarged bottom-navigation glass glyphs. */
private const val TAB_STROKE = 1.6f

/**
 * Solid glyphs sit a unit inside [BOX]: equal areas of ink and outline do not read as
 * equal, and a filled square always looks larger than a drawn one.
 */
private const val SOLID_BOX = 16f

/**
 * The hairline that rounds a solid glyph's corners.
 *
 * A filled path has no joins to round, so a play triangle came to three needle points in
 * an interface with no sharp corner anywhere else. Stroking the same path in the same
 * colour, round-joined, blunts them — and the geometry above is drawn [SOFTEN] / 2 small
 * to pay for the width the stroke adds back.
 */
private const val SOFTEN = 1f

/** A dot's radius, wherever the set needs one. */
private const val DOT = 0.85f

/** Accumulates sub-paths so one icon can mix stroked and filled parts. */
private class VectorParts(
    val name: String,
    val builder: ImageVector.Builder,
)

/**
 * A stroked glyph. Round caps and round joins are the default, not an option a call site
 * has to remember — forgetting them is exactly how the set drifted.
 */
private fun strokeVector(
    name: String,
    width: Float = STROKE,
    cap: StrokeCap = StrokeCap.Round,
    join: StrokeJoin = StrokeJoin.Round,
    block: PathBuilder.() -> Unit,
): VectorParts = VectorParts(name, newBuilder(name)).andPath(width, cap, join, block)

private fun VectorParts.andPath(
    width: Float = STROKE,
    cap: StrokeCap = StrokeCap.Round,
    join: StrokeJoin = StrokeJoin.Round,
    block: PathBuilder.() -> Unit,
): VectorParts =
    apply {
        builder.path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = width,
            strokeLineCap = cap,
            strokeLineJoin = join,
            pathBuilder = block,
        )
    }

/** Filled dots at [centres], all at [DOT]. */
private fun VectorParts.andDots(vararg centres: Pair<Float, Float>): VectorParts =
    apply {
        builder.path(fill = SolidColor(Color.Black)) {
            centres.forEach { (x, y) -> circle(x, y, DOT) }
        }
    }

private fun VectorParts.build(): ImageVector = builder.build()

/** A solid glyph with its corners rounded — see [SOFTEN]. */
private fun solidVector(
    name: String,
    block: PathBuilder.() -> Unit,
): ImageVector =
    newBuilder(name)
        .path(
            fill = SolidColor(Color.Black),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = SOFTEN,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        ).build()

private fun dotVector(
    name: String,
    vararg centres: Pair<Float, Float>,
): ImageVector =
    newBuilder(name)
        .path(fill = SolidColor(Color.Black)) {
            centres.forEach { (x, y) -> circle(x, y, 1.65f) }
        }.build()

/** 搜索 — one drawing, used by both the tab bar and the pages. */
private fun search(
    name: String,
    width: Float = STROKE,
    radius: Float = 6.4f,
    center: Float = 10.6f,
    handleEnd: Float = 20.1f,
) = strokeVector(name, width = width) {
    circle(center, center, radius)
}.andPath(width = width) {
    moveTo(handleEnd, handleEnd)
    lineTo(center + radius * 0.74f, center + radius * 0.74f)
}.build()

private fun newBuilder(name: String) =
    ImageVector.Builder(
        name = name,
        defaultWidth = VIEWPORT.dp,
        defaultHeight = VIEWPORT.dp,
        viewportWidth = VIEWPORT,
        viewportHeight = VIEWPORT,
    )

/** SVG `<circle>` as two half arcs. */
private fun PathBuilder.circle(
    cx: Float,
    cy: Float,
    r: Float,
) {
    moveTo(cx - r, cy)
    arcToRelative(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, 2 * r, 0f)
    arcToRelative(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, -2 * r, 0f)
    close()
}

/** One closed heart contour, drawn either stroked or filled. */
private fun PathBuilder.heart() {
    moveTo(12f, 20f)
    lineToRelative(-1.24f, -1.13f)
    curveTo(6.15f, 14.83f, 3.3f, 12.2f, 3.3f, 8.98f)
    curveTo(3.3f, 6.36f, 5.37f, 4.29f, 7.99f, 4.29f)
    curveToRelative(1.48f, 0f, 2.9f, 0.69f, 3.77f, 1.78f)
    curveTo(12.63f, 4.98f, 14.05f, 4.29f, 15.53f, 4.29f)
    curveToRelative(2.62f, 0f, 4.69f, 2.07f, 4.69f, 4.69f)
    curveToRelative(0f, 3.22f, -2.85f, 5.85f, -7.22f, 9.82f)
    close()
}

/** One closed bookmark contour, drawn either stroked or filled. */
private fun PathBuilder.bookmark() {
    moveTo(7.8f, 4.2f)
    horizontalLineTo(16.2f)
    arcToRelative(1.4f, 1.4f, 0f, isMoreThanHalf = false, isPositiveArc = true, 1.4f, 1.4f)
    verticalLineTo(19.8f)
    lineTo(12f, 16.3f)
    lineTo(6.4f, 19.8f)
    verticalLineTo(5.6f)
    arcToRelative(1.4f, 1.4f, 0f, isMoreThanHalf = false, isPositiveArc = true, 1.4f, -1.4f)
    close()
}

/** One closed five-point star, drawn either stroked or filled. */
private fun PathBuilder.star() {
    moveTo(12f, 4.2f)
    lineToRelative(2.45f, 4.99f)
    lineToRelative(5.46f, 0.8f)
    lineToRelative(-3.95f, 3.86f)
    lineToRelative(0.94f, 5.46f)
    lineTo(12f, 16.73f)
    lineToRelative(-4.9f, 2.59f)
    lineToRelative(0.94f, -5.46f)
    lineTo(4.09f, 9.99f)
    lineToRelative(5.46f, -0.8f)
    close()
}

/** SVG `<rect rx>`. */
private fun PathBuilder.roundRect(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    r: Float,
) {
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
