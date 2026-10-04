package com.yfuse.core.model

/**
 * How a series plays: as a 短剧 — upright picture, 选集 by number, swipe between episodes, a
 * light next-up — or as an ordinary series. [Auto] reads it from the episode on screen; the
 * viewer can settle it per series, or per folder of videos, in the player's settings, where it is
 * remembered with the series' other playback choices. Settled, it decides the orientation and the
 * swipe whatever the picture.
 */
enum class ShortDramaMode(
    val label: String,
) {
    Auto("自动"),
    On("短剧"),
    Off("普通剧集"),
    ;

    /** Whether the short-drama presentation applies, given what [Auto] would have detected. */
    fun resolve(detected: Boolean): Boolean =
        when (this) {
            Auto -> detected
            On -> true
            Off -> false
        }

    companion object {
        fun fromStorage(value: String?): ShortDramaMode = entries.firstOrNull { it.name == value } ?: Auto
    }
}

/** Episodes under five minutes — Jellyfin's default MinResumeDurationSeconds — count as short. */
const val SHORT_EPISODE_MAX_RUNTIME_MS = 300_000L

/**
 * Whether a picture coded [width]×[height] stands upright once [rotationDegrees] is applied:
 * a 1920×1080 file tagged rotate=90 is a portrait picture. Null when either side is unknown.
 */
fun isPortraitPicture(
    width: Int?,
    height: Int?,
    rotationDegrees: Int? = null,
): Boolean? {
    val codedWidth = width?.takeIf { it > 0 } ?: return null
    val codedHeight = height?.takeIf { it > 0 } ?: return null
    val quarterTurn = (rotationDegrees ?: 0).mod(180) == 90
    return if (quarterTurn) codedWidth > codedHeight else codedHeight > codedWidth
}

/** Whether an item of [runtimeMs] is a short episode; null when its length is unknown. */
fun isShortRuntime(runtimeMs: Long?): Boolean? =
    runtimeMs?.takeIf { it > 0L }?.let { it < SHORT_EPISODE_MAX_RUNTIME_MS }

/**
 * Whether a run of episodes reads as a 短剧: most of those whose picture is known stand upright.
 * Length alone does not decide it — a series of five-minute landscape shorts is not one.
 */
fun looksLikeShortDrama(portraitPictures: List<Boolean?>): Boolean {
    val known = portraitPictures.filterNotNull()
    return known.isNotEmpty() && known.count { it } * 2 > known.size
}
