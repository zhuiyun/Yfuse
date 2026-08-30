package com.yfuse.core.model

import kotlinx.serialization.Serializable

/**
 * A single browsable/playable media item (movie, series, episode).
 *
 * Serializable because [HomeContent] is cached to disk between launches (see
 * `LibraryCache`); nothing here is sent anywhere.
 */
@Serializable
data class MediaItem(
    val id: String,
    val title: String,
    val subtitle: String?,
    val type: String,
    // Which item + tag to pull the poster (Primary) image from. For episodes this
    // is the series, so we show a proper 2:3 poster instead of an episode still.
    val posterItemId: String,
    val posterTag: String?,
    val backdropItemId: String?,
    val backdropTag: String?,
    /** Resume progress 0..100, when the item is in "continue watching". */
    val playedPercentage: Double?,
    /** Exact Emby resume position; defaults keep older cached home payloads readable. */
    val resumePositionTicks: Long? = null,
    /** Account-wide ISO timestamp returned by Emby's UserData. */
    val lastPlayedDate: String? = null,
    /** Synopsis, when the endpoint was asked for it (search results). */
    val overview: String? = null,
    /** Production year shown below poster cards. */
    val year: Int? = null,
    /** Runtime exposed by Emby's RunTimeTicks field. */
    val runtimeMinutes: Int? = null,
    /** Community score shown by compact recommendation cards. */
    val communityRating: Double? = null,
    /** External IDs such as TMDB, used to match recommendations precisely. */
    val providerIds: Map<String, String> = emptyMap(),
    /** User-specific Emby state returned through UserData. */
    val isFavorite: Boolean = false,
    val played: Boolean = false,
    /**
     * Membership id returned by `/Playlists/{id}/Items`.
     *
     * Emby removes a playlist row by this id rather than by the media [id]. The UI still
     * de-duplicates repeated memberships by [id], while retaining this value for removal.
     */
    val playlistItemId: String? = null,
)

/** The two server-owned organization containers surfaced by the library home screen. */
@Serializable
enum class MediaContainerKind {
    BoxSet,
    Playlist,
}

/** A real Emby BoxSet or Playlist. No synthetic local collection is represented here. */
@Serializable
data class MediaContainer(
    val id: String,
    val title: String,
    val kind: MediaContainerKind,
    /** Pins navigation and writes to the server from which this container was read. */
    val serverId: String,
    val posterTag: String? = null,
    val itemCount: Int? = null,
    /** False for rule-driven Plex smart containers, whose URI update would replace the rules. */
    val editable: Boolean = true,
)

/** One horizontal row on the home screen (e.g. "电影-国产电影 · 最新"). */
@Serializable
data class HomeRow(
    val libraryId: String,
    val title: String,
    val items: List<MediaItem>,
    /** Size of the whole library, not just the loaded page — shown on the chip. */
    val totalCount: Int = items.size,
)

/**
 * How the 「查看更多」 grid orders a library.
 *
 * Both fields go straight into Emby's `SortBy` / `SortOrder`, because ordering a library
 * is the server's job: the grid used to fetch a fixed first page by name and reorder it
 * on the device, which made 「最近添加」 and 「年份」 order only whatever that page held.
 */
enum class LibrarySort(
    val sortBy: String,
    val descending: Boolean,
) {
    RecentlyAdded(sortBy = "DateCreated", descending = true),
    Name(sortBy = "SortName", descending = false),

    /**
     * `PremiereDate` breaks the ties: a library where most items share a production year
     * otherwise comes back in whatever order the database happened to hold them, and that
     * order is not stable between pages — which shows up as duplicated or skipped items
     * while scrolling.
     */
    Year(sortBy = "ProductionYear,PremiereDate", descending = true),
    Rating(sortBy = "CommunityRating", descending = true),
}

/** Resolution and source-format facets supported by Emby's item query. */
enum class LibraryResolution(
    val label: String,
    val isHd: Boolean?,
    val minWidth: Int? = null,
    val videoType: String? = null,
    val extendedVideoType: String? = null,
) {
    All(label = "全部", isHd = null),
    FourK(label = "4K", isHd = true, minWidth = 2_560),
    DolbyVision(label = "杜比视界", isHd = true, extendedVideoType = "DolbyVision"),
    BluRay(label = "蓝光", isHd = true, videoType = "Bluray"),
    Hd(label = "高清", isHd = true),
    Sd(label = "标清", isHd = false),
}

/** One page of a library listing, plus the size of the whole matching set. */
data class LibraryPage(
    val items: List<MediaItem> = emptyList(),
    /** Size of the full result, independent of the page — drives 「N 部」 and paging. */
    val totalCount: Int = 0,
    /** Offset this page was requested at, so a late response can be placed or dropped. */
    val startIndex: Int = 0,
)

/** A page of BoxSet or Playlist containers, used by the container directory grid. */
data class MediaContainerPage(
    val containers: List<MediaContainer> = emptyList(),
    val totalCount: Int = 0,
    val startIndex: Int = 0,
)

/** Exact server-wide title counts; null on [HomeContent] means the count request failed. */
@Serializable
data class LibraryCounts(
    val movieCount: Int,
    val seriesCount: Int,
)

/** Aggregated content for the media library home screen. */
@Serializable
data class HomeContent(
    val featured: List<MediaItem> = emptyList(),
    val resume: List<MediaItem> = emptyList(),
    val rows: List<HomeRow> = emptyList(),
    val counts: LibraryCounts? = null,
    /** Defaults preserve pre-collections cache payloads. */
    val collections: List<MediaContainer> = emptyList(),
    /** Defaults preserve pre-playlists cache payloads. */
    val playlists: List<MediaContainer> = emptyList(),
) {
    val isEmpty: Boolean get() =
        featured.isEmpty() &&
            resume.isEmpty() &&
            rows.isEmpty() &&
            collections.isEmpty() &&
            playlists.isEmpty()
}
