package com.yfuse.core.model

/** A cast/crew member. */
data class Person(
    val id: String,
    val name: String,
    val role: String?,
    val primaryImageTag: String?,
)

/** Full detail for a single media item (movie or series). */
data class MediaDetail(
    val id: String,
    val title: String,
    val type: String,
    /** Set for episodes; used to load the series' episode list and cast. */
    val seriesId: String?,
    val overview: String?,
    val year: Int?,
    val genres: List<String>,
    val runtimeMinutes: Int?,
    val officialRating: String?,
    val communityRating: Double?,
    val posterItemId: String,
    val posterTag: String?,
    val backdropItemId: String,
    val backdropTag: String?,
    val resumePositionTicks: Long?,
    val people: List<Person>,
)

/** A concrete, playable target resolved from a detail item. */
data class PlayTarget(val itemId: String, val startPositionTicks: Long)
