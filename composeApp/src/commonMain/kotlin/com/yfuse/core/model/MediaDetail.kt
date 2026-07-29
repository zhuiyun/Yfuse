package com.yfuse.core.model

/** A cast/crew member. */
data class Person(
    val id: String,
    val name: String,
    val role: String?,
    val primaryImageTag: String?,
)

/**
 * One server's copy of an item, as shown in 跨服务器片源对比.
 * [quality] / [size] / [bitrate] are preformatted for display.
 */
data class SourceInfo(
    val quality: String,
    val size: String?,
    val bitrate: String?,
) {
    /** `4K HDR · 42.3 GB · 68 Mbps` */
    val summary: String get() = listOfNotNull(quality, size, bitrate).joinToString(" · ")
}

/** A server's availability of a given title. */
data class ServerSource(
    val serverId: String,
    val serverName: String,
    val isCurrent: Boolean,
    /** Null when the server does not have this title. */
    val source: SourceInfo?,
    val reachable: Boolean,
    /** Concrete item on that server, used to switch the playback origin. */
    val itemId: String? = null,
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
    /** Primary media source on the server this detail came from. */
    val source: SourceInfo? = null,
    val isFavorite: Boolean = false,
    val played: Boolean = false,
    /** External IDs make cross-server matching independent of translated titles. */
    val providerIds: Map<String, String> = emptyMap(),
)

/** A concrete, playable target resolved from a detail item. */
data class PlayTarget(val itemId: String, val startPositionTicks: Long)
