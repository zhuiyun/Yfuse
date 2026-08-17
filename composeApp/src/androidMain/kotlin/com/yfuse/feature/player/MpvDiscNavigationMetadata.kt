package com.yfuse.feature.player

import com.yfuse.core.playback.PlaybackDiscChapter
import com.yfuse.core.playback.PlaybackDiscNavigationState
import com.yfuse.core.playback.PlaybackDiscTitle
import dev.jdtech.mpv.MPVLib

/** Small property surface so disc metadata parsing can be unit-tested without a native mpv handle. */
internal interface MpvDiscPropertyReader {
    fun int(name: String): Int?

    fun double(name: String): Double?

    fun string(name: String): String?

    fun flag(name: String): Boolean?
}

/** Adapter over the bundled mpv JNI bridge. */
internal fun MPVLib.discPropertyReader(): MpvDiscPropertyReader =
    object : MpvDiscPropertyReader {
        override fun int(name: String): Int? = getPropertyInt(name)

        override fun double(name: String): Double? = getPropertyDouble(name)

        override fun string(name: String): String? = getPropertyString(name)

        override fun flag(name: String): Boolean? = getPropertyBoolean(name)
    }

/** Converts a chapter timestamp in seconds to a bounded millisecond position. */
internal fun mpvDiscTimeMs(seconds: Double?): Long? {
    val value = seconds?.takeIf { it.isFinite() && it >= 0.0 } ?: return null
    if (value > Long.MAX_VALUE / 1_000.0) return null
    return (value * 1_000.0).toLong()
}

/**
 * Reads mpv's rich edition/chapter lists while preserving count-only fallbacks.
 *
 * `edition-list` supplies authored title names/default flags when the demuxer has them; `chapter-list`
 * supplies chapter titles and start times. Missing list fields are expected on some discs/backends,
 * so the legacy `editions`/`chapters` counts remain the fallback rather than making navigation vanish.
 */
internal fun readMpvDiscNavigationMetadata(
    previous: PlaybackDiscNavigationState,
    properties: MpvDiscPropertyReader,
): PlaybackDiscNavigationState {
    val titleCount =
        (properties.int("edition-list/count")
            ?: properties.int("editions")
            ?: previous.effectiveTitleCount)
            .coerceAtLeast(0)
    val titles =
        List(titleCount) { index ->
            PlaybackDiscTitle(
                index = index,
                id = properties.int("edition-list/$index/id"),
                title = properties.string("edition-list/$index/title")?.trim()?.takeIf(String::isNotEmpty),
                isDefault = properties.flag("edition-list/$index/default") == true,
            )
        }
    val selectedTitle =
        selectedDiscIndex(
            requested = properties.int("current-edition"),
            fallback = previous.selectedTitleIndex,
            count = titleCount,
        )

    val chapterCount =
        (properties.int("chapter-list/count")
            ?: properties.int("chapters")
            ?: previous.effectiveChapterCount)
            .coerceAtLeast(0)
    val chapters =
        List(chapterCount) { index ->
            PlaybackDiscChapter(
                index = index,
                title = properties.string("chapter-list/$index/title")?.trim()?.takeIf(String::isNotEmpty),
                startMs = mpvDiscTimeMs(properties.double("chapter-list/$index/time")),
            )
        }
    val selectedChapter =
        selectedDiscIndex(
            requested = properties.int("chapter"),
            fallback = previous.selectedChapterIndex,
            count = chapterCount,
        )

    return previous.copy(
        titleCount = titleCount,
        selectedTitleIndex = selectedTitle,
        chapterCount = chapterCount,
        selectedChapterIndex = selectedChapter,
        titles = titles,
        chapters = chapters,
    )
}

private fun selectedDiscIndex(
    requested: Int?,
    fallback: Int,
    count: Int,
): Int {
    if (count <= 0) return 0
    val candidate = requested?.takeIf { it >= 0 } ?: fallback.coerceAtLeast(0)
    return candidate.coerceIn(0, count - 1)
}
