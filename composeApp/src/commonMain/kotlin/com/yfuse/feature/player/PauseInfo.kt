package com.yfuse.feature.player

/**
 * 暂停信息层 waits this long into a settled pause before it appears. Long enough that a pause to
 * answer the door is not decorated; short enough to be there when someone looks back.
 */
internal const val PAUSE_INFO_DELAY_MS = 3_000L

/** Enough names to say who is in it, few enough to read at a glance. */
internal const val PAUSE_INFO_CAST_NAMES = 3

/**
 * Whether the pause has settled enough for the info layer: paused for real — not a stall, not the
 * end, not a failure — with the chrome out of the way, nothing open over the picture and the
 * screen unlocked. The chrome being up is the viewer already looking at something else.
 */
internal fun pauseInfoEligible(
    playing: Boolean,
    buffering: Boolean,
    ended: Boolean,
    failed: Boolean,
    controlsVisible: Boolean,
    overlayOpen: Boolean,
    locked: Boolean,
): Boolean =
    !playing &&
        !buffering &&
        !ended &&
        !failed &&
        !controlsVisible &&
        !overlayOpen &&
        !locked

/** The layer's lines, top to bottom; each is null when there is nothing honest to put in it. */
internal data class PauseInfo(
    val chapter: String?,
    val cast: String?,
    val endsAt: String?,
)

/**
 * 暂停信息层's content: the chapter the pause is in, the first few names in the cast, and when the
 * rest ends at the current speed. Null when none of the three has anything to say — a layer with
 * nothing in it is only a dimmer picture.
 */
internal fun pauseInfo(
    chapterName: String?,
    castNames: List<String>,
    endsAt: String?,
): PauseInfo? {
    val chapter = chapterName?.trim()?.takeIf(String::isNotEmpty)
    val cast =
        castNames
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .take(PAUSE_INFO_CAST_NAMES)
            .takeIf { it.isNotEmpty() }
            ?.joinToString("、", prefix = "主演 ")
    val ending = endsAt?.takeIf(String::isNotBlank)
    if (chapter == null && cast == null && ending == null) return null
    return PauseInfo(chapter = chapter, cast = cast, endsAt = ending)
}
