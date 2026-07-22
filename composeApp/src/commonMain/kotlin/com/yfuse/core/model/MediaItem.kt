package com.yfuse.core.model

/** A single browsable/playable media item (movie, series, episode). */
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
)

/** One horizontal row on the home screen (e.g. "电影-国产电影 · 最新"). */
data class HomeRow(
    val libraryId: String,
    val title: String,
    val items: List<MediaItem>,
)

/** Aggregated content for the media library home screen. */
data class HomeContent(
    val featured: List<MediaItem> = emptyList(),
    val resume: List<MediaItem> = emptyList(),
    val rows: List<HomeRow> = emptyList(),
) {
    val isEmpty: Boolean get() = featured.isEmpty() && resume.isEmpty() && rows.isEmpty()
}
