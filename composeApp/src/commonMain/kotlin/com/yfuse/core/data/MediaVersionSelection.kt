package com.yfuse.core.data

import com.yfuse.core.model.MediaVersion
import com.yfuse.core.model.compareMediaVersionsBestFirst

/** User intent for choosing one physical file when an item exposes several media sources. */
enum class MediaVersionPreference(
    val storageValue: String,
) {
    HdrFirst("hdr_first"),
    DolbyVisionFirst("dolby_vision_first"),
    HighestQuality("highest_quality"),
    ;

    companion object {
        fun fromStorage(value: String?): MediaVersionPreference =
            entries.firstOrNull { preference ->
                preference.storageValue.equals(value, ignoreCase = true) ||
                    preference.name.equals(value, ignoreCase = true)
            } ?: HdrFirst
    }
}

/**
 * Selects one physical version without ever depending on Emby/Jellyfin response order.
 *
 * An explicit version id always wins for the episode on which the user tapped a version. For
 * automatic choices, dynamic-range intent is applied before the ordinary deterministic quality
 * comparison. This makes `HDR first` mean HDR even when a Dolby copy was ingested earlier and is
 * returned as the first `MediaSource`.
 */
fun List<MediaVersion>.preferredVersion(
    preference: MediaVersionPreference,
    explicitVersionId: String? = null,
): MediaVersion? {
    explicitVersionId
        ?.takeIf(String::isNotBlank)
        ?.let { requested -> firstOrNull { it.id == requested } }
        ?.let { return it }
    return minWithOrNull { left, right ->
        compareValues(preference.rangeRank(left), preference.rangeRank(right)).nonZero()
            ?: compareDirectPlayback(left, right).nonZero()
            ?: compareMediaVersionsBestFirst(left, right).nonZero()
            ?: left.name.lowercase().compareTo(right.name.lowercase()).nonZero()
            ?: left.id.compareTo(right.id)
    }
}

private fun MediaVersionPreference.rangeRank(version: MediaVersion): Int =
    when (this) {
        MediaVersionPreference.HdrFirst ->
            when {
                version.isNonDolbyHdr -> 0
                version.isDolbyVision -> 1
                else -> 2
            }
        MediaVersionPreference.DolbyVisionFirst ->
            when {
                version.isDolbyVision -> 0
                version.isNonDolbyHdr -> 1
                else -> 2
            }
        MediaVersionPreference.HighestQuality -> 0
    }

private val MediaVersion.isNonDolbyHdr: Boolean
    get() =
        !isDolbyVision &&
            listOf(videoRange, video?.videoRange, video?.displayTitle, video?.profile)
                .any { value ->
                    val normalized = value.orEmpty().uppercase().replace(" ", "")
                    "HDR" in normalized || "HLG" in normalized
                }

/** Prefer a server-approved direct path inside the requested dynamic-range class. */
private fun compareDirectPlayback(
    left: MediaVersion,
    right: MediaVersion,
): Int = directPlaybackRank(left).compareTo(directPlaybackRank(right))

private fun directPlaybackRank(version: MediaVersion): Int =
    when {
        version.supportsDirectPlay == true -> 0
        version.supportsDirectStream == true -> 1
        version.supportsDirectPlay == null && version.supportsDirectStream == null -> 2
        else -> 3
    }

private fun Int.nonZero(): Int? = takeIf { it != 0 }
