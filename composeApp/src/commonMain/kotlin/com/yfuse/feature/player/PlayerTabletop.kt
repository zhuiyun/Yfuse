package com.yfuse.feature.player

/**
 * 折叠屏桌面模式: a phone standing half-open on a table plays above its hinge and keeps its
 * controls below it, so nothing the thumb needs sits on the half facing up at the ceiling.
 *
 * All values are window pixels.
 */
internal class TabletopSplit(
    /** The picture fills the window's width down to here: the top of the hinge. */
    val pictureBottomPx: Int,
    /** The controls start here: the bottom of the hinge. */
    val controlsTopPx: Int,
)

/** The hinge must sit near the middle; a fold near an edge leaves one half too small to use. */
private const val TABLETOP_HINGE_MIN_FRACTION = 0.3f
private const val TABLETOP_HINGE_MAX_FRACTION = 0.7f

/** The split for a horizontal hinge from [hingeTopPx] to [hingeBottomPx], or null when it should not split. */
internal fun tabletopSplit(
    hingeTopPx: Int,
    hingeBottomPx: Int,
    windowHeightPx: Int,
): TabletopSplit? {
    if (windowHeightPx <= 0 || hingeTopPx <= 0 || hingeBottomPx < hingeTopPx || hingeBottomPx >= windowHeightPx) {
        return null
    }
    val middle = (hingeTopPx + hingeBottomPx) / 2f / windowHeightPx
    if (middle !in TABLETOP_HINGE_MIN_FRACTION..TABLETOP_HINGE_MAX_FRACTION) return null
    return TabletopSplit(pictureBottomPx = hingeTopPx, controlsTopPx = hingeBottomPx)
}
