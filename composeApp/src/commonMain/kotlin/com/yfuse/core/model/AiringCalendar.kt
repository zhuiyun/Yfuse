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
 * Whether a dated row is an episode or a film opening.
 *
 * The calendar was episodes only, which left it answering half the question it is opened
 * for. A film has one date and no coordinate, so it needs a row shape of its own rather
 * than being forced into 第 0 季第 0 集.
 */
enum class AiringKind { Episode, Movie }

/** Authority behind a date, kept explicit so the UI never labels an estimate as official. */
enum class AiringScheduleAuthority { Tmdb, Estimated, Official }

/** A compact, user-verifiable record of why an official/estimated date was accepted. */
@Serializable
data class AiringScheduleEvidence(
    val type: String,
    val publisher: String,
    val sourceUrl: String,
    val capturedAt: String,
    val contentHash: String,
    val extractionMethod: String,
)

/** Access tier named by the publishing platform. */
enum class AiringAccessTier { Unknown, Free, Member, SviP }

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
    /** Defaulted so the stored schedule of an older build still decodes as episodes. */
    val kind: AiringKind = AiringKind.Episode,
    /** Defaulted fields keep schedules cached by older builds readable. */
    val scheduleAuthority: AiringScheduleAuthority = AiringScheduleAuthority.Tmdb,
    /** Local wall-clock time published by the platform, `HH:mm`; null when unknown. */
    val airTime: String? = null,
    /** IANA zone for [airTime], never inferred from the phone's current zone. */
    val timeZoneId: String? = null,
    val platforms: List<String> = emptyList(),
    val accessTier: AiringAccessTier = AiringAccessTier.Unknown,
    /** Human-verifiable provenance for official corrections. */
    val sourceUrl: String? = null,
    val scheduleRevision: String? = null,
    val scheduleUpdatedAt: String? = null,
    /** 0..100 confidence assigned by the server-side evidence gate. */
    val scheduleConfidence: Int? = null,
    /** Raw source material remains server-side; this is the auditable summary. */
    val scheduleEvidence: List<AiringScheduleEvidence> = emptyList(),
) {
    val isMovie: Boolean get() = kind == AiringKind.Movie

    /**
     * The cross-server coordinate for this row, in the form `EmbyRepository`'s
     * `findByMediaKey` already resolves — the same one watch-together uses to match an
     * episode across two people's servers. A film has no coordinate beyond its own id.
     */
    val mediaKey: String
        get() =
            if (isMovie) {
                "tmdb-movie:$showTmdbId"
            } else {
                "tmdb:$showTmdbId/s${seasonNumber}e$episodeNumber"
            }

    /** `第 3 集` — TMDB episode titles are often absent or a bare "第 3 集" repeat. */
    val episodeLabel: String
        get() =
            if (isMovie) {
                "电影上映"
            } else {
                listOfNotNull(
                    "第 $episodeNumber 集".takeIf { seasonNumber <= 1 }
                        ?: "S$seasonNumber E$episodeNumber",
                    episodeTitle?.takeIf { it.isNotBlank() && it != "第 $episodeNumber 集" },
                ).joinToString(" · ")
            }
}

/**
 * What the user's own library can do about an episode that has a broadcast date.
 *
 * [Missing] is the state the whole feature exists for: it aired, and the server does not
 * have it yet. Nothing else in the app can say that — Emby only knows about files that
 * exist, so to it a missing episode is indistinguishable from one that was never made.
 */
enum class LibraryStatus {
    /** Its broadcast or release date has not arrived. */
    Unaired,

    /** Broadcast, but no copy on the server. */
    Missing,

    /** On the server, not yet watched. */
    Available,

    /** On the server with a non-zero resume position. */
    InProgress,

    /** On the server and watched. */
    Watched,

    /** No server to ask, or the lookup failed. The row still shows the broadcast. */
    Unknown,
}

enum class CalendarDataIssue { NoServer, LibraryLookupFailed, IdentityUnmatched }

/** One server's concrete copy of a scheduled title. External schedules never create this. */
data class CalendarSource(
    val serverId: String,
    val serverName: String,
    val itemId: String? = null,
    val seriesItemId: String? = null,
    val status: LibraryStatus,
    val playedPercentage: Double? = null,
    val qualityTags: List<String> = emptyList(),
    /** Authenticated Emby fallback when the external schedule has no poster. */
    val posterUrl: String? = null,
    /** Actual files returned by Emby, independent of the visible schedule window. */
    val libraryEpisodeCount: Int? = null,
    /** Highest positive episode coordinate currently present in Emby. */
    val highestEpisodeNumber: Int? = null,
)

/** One episode as the calendar shows it: the broadcast, plus what this library has. */
data class CalendarEntry(
    val episode: AiringEpisode,
    val status: LibraryStatus,
    /** Set when the episode is on the server, so the row can open or play it. */
    val itemId: String? = null,
    val serverId: String? = null,
    /**
     * The *show* on the server, when the library holds it at all.
     *
     * Set even for episodes the library is missing, and that is the point of it. Without
     * this, a 未入库 row is a dead end — the app knows perfectly well which series it
     * belongs to and can open it, and "追剧" means the shows you already follow, which is
     * the only thing this field can distinguish them by.
     */
    val seriesItemId: String? = null,
    /** All matching servers, merged into this one schedule row. */
    val sources: List<CalendarSource> = emptyList(),
    /** Explicit user subscription, independent of whether a server currently holds the show. */
    val followed: Boolean = false,
    /**
     * A discovery row with no matching series in any connected library.
     *
     * This is not a missing download. Keeping it explicit prevents unrelated popular titles
     * from inflating the user's “未入库” count while still allowing the discovery calendar
     * to show their broadcast dates.
     */
    val discoveryOnly: Boolean = false,
    /** Why a row is Unknown; kept separate so transport failures never masquerade as “未入库”. */
    val dataIssue: CalendarDataIssue? = null,
) {
    /** True for a show this library follows, whether or not it has tonight's episode. */
    val inLibrary: Boolean get() = seriesItemId != null || sources.any { it.seriesItemId != null }

    /** What tapping the row opens: the episode when there is one, else the show. */
    val openItemId: String? get() = itemId ?: seriesItemId

    val serverNames: List<String>
        get() = sources.map { it.serverName }.filter(String::isNotBlank).distinct()

    private val selectedSource: CalendarSource?
        get() =
            sources.firstOrNull { source ->
                source.serverId == serverId &&
                    (
                        itemId != null &&
                            source.itemId == itemId ||
                            itemId == null &&
                            seriesItemId != null &&
                            source.seriesItemId == seriesItemId
                    )
            }

    val qualityTags: List<String>
        get() = selectedSource?.qualityTags.orEmpty().ifEmpty { sources.flatMap { it.qualityTags }.distinct() }

    val posterUrls: List<String>
        get() = sources.mapNotNull { it.posterUrl }.distinct()

    val libraryEpisodeCount: Int?
        get() = sources.mapNotNull { it.libraryEpisodeCount }.maxOrNull()

    val highestLibraryEpisodeNumber: Int?
        get() = sources.mapNotNull { it.highestEpisodeNumber }.maxOrNull()

    /** Progress follows the same source chosen for opening; another server's 100% must not
     * turn an in-progress source into a contradictory full progress bar. */
    val playedPercentage: Double?
        get() = selectedSource?.playedPercentage ?: sources.mapNotNull { it.playedPercentage }.maxOrNull()
}

/** A day's broadcasts, which is the unit the calendar is laid out in. */
data class CalendarDay(
    /** ISO-8601 `YYYY-MM-DD`. */
    val date: String,
    val entries: List<CalendarEntry>,
)
