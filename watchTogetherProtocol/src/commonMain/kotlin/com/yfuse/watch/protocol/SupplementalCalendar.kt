package com.yfuse.watch.protocol

import kotlinx.serialization.Serializable

/** Supplementary provider rows, deliberately separate from verified official schedules. */
@Serializable
data class SupplementalCalendarEntry(
    val tmdbId: Int,
    val title: String,
    val kind: String,
    val seasonNumber: Int = 0,
    val episodeNumber: Int = 0,
    val episodeTitle: String? = null,
    val posterPath: String? = null,
    val airDate: String,
    val releaseAtUtc: String? = null,
    val network: String? = null,
    val provider: String = "RE0",
    val updatedAt: String,
) {
    val coordinate: String
        get() = if (kind == "movie") "movie:$tmdbId" else "tv:$tmdbId:$seasonNumber:$episodeNumber"

    fun hasValidShape(): Boolean =
        tmdbId > 0 &&
            title.isNotBlank() &&
            title.length <= 120 &&
            provider == "RE0" &&
            (
                (kind == "tv" && seasonNumber > 0 && episodeNumber > 0) ||
                    (kind == "movie" && seasonNumber == 0 && episodeNumber == 0)
            ) &&
            airDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) &&
            updatedAt.length in 10..64 &&
            (releaseAtUtc == null || releaseAtUtc.length in 20..40 && releaseAtUtc.endsWith("Z")) &&
            (posterPath == null || posterPath.matches(Regex("/[A-Za-z0-9_-]+\\.(jpg|png|webp)"))) &&
            (episodeTitle == null || episodeTitle.length <= 200) &&
            (network == null || network.length <= 40)
}
