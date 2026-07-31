package com.yfuse.core.model

/** A season of a series. */
data class Season(
    val id: String,
    val name: String,
    val indexNumber: Int?,
    val posterTag: String?,
)

/** A single episode inside a season. */
data class Episode(
    val id: String,
    val name: String,
    val indexNumber: Int?,
    val seasonNumber: Int?,
    val seasonId: String?,
    val overview: String?,
    val runtimeMinutes: Int?,
    val primaryTag: String?,
    val playedPercentage: Double?,
    /**
     * Emby's own watched flag. Not derivable from [playedPercentage]: finishing an episode
     * clears the resume percentage, so a watched episode and an untouched one both report
     * null there.
     */
    val played: Boolean = false,
    val resumePositionTicks: Long?,
    val playbackSegments: List<PlaybackSegment> = emptyList(),
    val providerIds: Map<String, String> = emptyMap(),
)
