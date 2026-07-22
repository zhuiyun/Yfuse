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
    val people: List<Person>,
)
