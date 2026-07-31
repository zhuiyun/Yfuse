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
    /** Set for episodes, for naming the series apart from this episode's own title. */
    val seriesName: String? = null,
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
    /** Every backdrop the item has, for the 艺术图 strip. Includes [backdropTag]. */
    val backdropTags: List<String> = emptyList(),
    /** When the file was added to the library, `YYYY-MM-DD`. */
    val dateCreated: String? = null,
    val resumePositionTicks: Long?,
    val people: List<Person>,
    /** Primary media source on the server this detail came from. */
    val source: SourceInfo? = null,
    /**
     * Every file the server holds for this title, in the order it reports them. More than
     * one means the library has several cuts or encodes and the user gets to pick; the
     * first is what plays until they do.
     */
    val versions: List<MediaVersion> = emptyList(),
    val isFavorite: Boolean = false,
    val played: Boolean = false,
    /** External IDs make cross-server matching independent of translated titles. */
    val providerIds: Map<String, String> = emptyMap(),
    /** Intro and credits markers supplied by Emby chapter metadata. */
    val playbackSegments: List<PlaybackSegment> = emptyList(),
)

/** A concrete, playable target resolved from a detail item. */
data class PlayTarget(val itemId: String, val startPositionTicks: Long)
