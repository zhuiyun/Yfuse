package com.yfuse.core.model

import kotlinx.serialization.Serializable

/**
 * Where a show comes from, which is the axis the calendar balances.
 *
 * TMDB's global charts are dominated by English-language releases; asking for domestic
 * shows separately and merging is the only way a 国产剧 appears at all — the same split
 * `TmdbRepository.home()` already makes for the home tab.
 */
enum class ShowOrigin { Domestic, Foreign }

/**
 * One episode with a broadcast date, and what this library has to say about it.
 *
 * The date is TMDB's `air_date`: the **origin country's** broadcast date. A Thursday US
 * episode is a Friday in China, and the calendar says so rather than silently shifting
 * dates it cannot shift correctly — TMDB publishes no broadcast time, only a date, so any
 * timezone conversion would be a guess dressed up as a fact.
 */
@Serializable
data class AiringEpisode(
    val showTmdbId: Int,
    val showTitle: String,
    val posterPath: String?,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val episodeTitle: String?,
    /** ISO-8601 `YYYY-MM-DD`, in the origin country's calendar. */
    val airDate: String,
    val origin: ShowOrigin,
) {
    /**
     * The cross-server coordinate for this episode, in the form `EmbyRepository`'s
     * `findByMediaKey` already resolves — the same one watch-together uses to match an
     * episode across two people's servers.
     */
    val mediaKey: String get() = "tmdb:$showTmdbId/s${seasonNumber}e$episodeNumber"

    /** `第 3 集` — TMDB episode titles are often absent or a bare "第 3 集" repeat. */
    val episodeLabel: String
        get() = listOfNotNull(
            "第 $episodeNumber 集".takeIf { seasonNumber <= 1 }
                ?: "S$seasonNumber E$episodeNumber",
            episodeTitle?.takeIf { it.isNotBlank() && it != "第 $episodeNumber 集" },
        ).joinToString(" · ")
}

/**
 * What the user's own library can do about an episode that has a broadcast date.
 *
 * [Missing] is the state the whole feature exists for: it aired, and the server does not
 * have it yet. Nothing else in the app can say that — Emby only knows about files that
 * exist, so to it a missing episode is indistinguishable from one that was never made.
 */
enum class LibraryStatus {
    /** Its broadcast date has not arrived. */
    Unaired,

    /** Broadcast, but no copy on the server. */
    Missing,

    /** On the server, not yet watched. */
    Available,

    /** On the server and watched. */
    Watched,

    /** No server to ask, or the lookup failed. The row still shows the broadcast. */
    Unknown,
}

/** One episode as the calendar shows it: the broadcast, plus what this library has. */
data class CalendarEntry(
    val episode: AiringEpisode,
    val status: LibraryStatus,
    /** Set when the episode is on the server, so the row can open or play it. */
    val itemId: String? = null,
    val serverId: String? = null,
)

/** A day's broadcasts, which is the unit the calendar is laid out in. */
data class CalendarDay(
    /** ISO-8601 `YYYY-MM-DD`. */
    val date: String,
    val entries: List<CalendarEntry>,
)
