package com.yfuse.feature.player

/**
 * 捏合填充: how much wider than where they started the fingers have to spread before the picture
 * fills. Small enough to be a flick of the thumb, large enough that two fingers resting on the
 * glass never cross it by drifting.
 */
internal const val PINCH_FILL_ZOOM = 1.15f

/** ...and how much closer together before it fits again: the same ratio, the other way. */
internal const val PINCH_FIT_ZOOM = 1f / PINCH_FILL_ZOOM

/**
 * The fill a pinch asks for, or null while it has not asked for anything: spreading asks for
 * 裁剪填满, pinching in for 适应. [zoom] is the spread now over the spread it is measured from.
 * A picture already filled — 拉伸填满 counts — does not fill further, nor a fitted one fit.
 */
internal fun pinchFillTarget(
    filled: Boolean,
    zoom: Float,
): Boolean? =
    when {
        !zoom.isFinite() || zoom <= 0f -> null
        !filled && zoom >= PINCH_FILL_ZOOM -> true
        filled && zoom <= PINCH_FIT_ZOOM -> false
        else -> null
    }

/**
 * One two-finger gesture on the picture, followed by the distance between the fingers.
 *
 * Each change re-anchors on the spread where it happened, so pinching back within the same
 * gesture undoes it — the picture follows the fingers rather than the first thing they did.
 */
internal class PinchFillTracker(
    startFilled: Boolean,
    startSpan: Float,
) {
    private var filled = startFilled
    private var anchorSpan = startSpan

    /** The new fill when [span] crosses into it, null otherwise. */
    fun follow(span: Float): Boolean? {
        if (!(anchorSpan > 0f)) {
            anchorSpan = span
            return null
        }
        val target = pinchFillTarget(filled, span / anchorSpan) ?: return null
        filled = target
        anchorSpan = span
        return target
    }
}

/** What the HUD says once a pinch has changed the picture: the mode's own name, as 画面 does. */
internal fun pinchFillMessage(filled: Boolean): String =
    "画面：${if (filled) VideoScaleMode.Fill.label else VideoScaleMode.Fit.label}"
