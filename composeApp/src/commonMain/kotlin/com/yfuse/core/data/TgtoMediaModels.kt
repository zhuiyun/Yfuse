package com.yfuse.core.data

import com.yfuse.core.model.TmdbItem
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val DEFAULT_TGTO_ENDPOINT = "http://47.112.219.60:12366"
const val DEFAULT_TGTO_USERNAME = "zhuiyun"

val DEFAULT_123_CHANNELS =
    listOf(
        "https://t.me/regeng123",
        "https://t.me/tuoxiede123",
        "https://t.me/x123panfxme",
        "https://t.me/wei_123share",
        "https://t.me/wei_123_share",
    )

@Serializable
data class TgtoEnvelope<T>(
    val success: Boolean = false,
    val data: T? = null,
    val message: String? = null,
    val error: String? = null,
    val code: String? = null,
)

@Serializable
data class TgtoLoginRequest(
    val username: String,
    val password: String,
)

@Serializable
data class TgtoMediaItem(
    val id: String = "",
    @SerialName("entity_key") val entityKey: String = "",
    @SerialName("entity_type") val entityType: String = "movie",
    @SerialName("external_id") val externalId: String = "",
    @SerialName("tmdb_id") val tmdbId: Int? = null,
    @SerialName("media_type") val mediaType: String = "movie",
    val title: String = "",
    @SerialName("original_title") val originalTitle: String = "",
    val overview: String = "",
    @SerialName("poster_url") val posterUrl: String = "",
    @SerialName("backdrop_url") val backdropUrl: String = "",
    val year: String = "",
    @SerialName("release_date") val releaseDate: String = "",
    val score: Double? = null,
    val rank: Int? = null,
    val source: String = "",
    val provider: String = "",
    @SerialName("provider_label") val providerLabel: String = "",
    val genres: List<String> = emptyList(),
    val tagline: String = "",
    val runtime: Int? = null,
    val status: String = "",
    @SerialName("vote_count") val voteCount: Int? = null,
    @SerialName("number_of_episodes") val numberOfEpisodes: Int? = null,
    @SerialName("calendar_date") val calendarDate: String = "",
    @SerialName("calendar_kind") val calendarKind: String = "",
    @SerialName("calendar_bucket") val calendarBucket: String = "",
    @SerialName("first_aired") val firstAired: String = "",
    val network: String = "",
    @SerialName("calendar_episode") val calendarEpisode: TgtoCalendarEpisode? = null,
) {
    val normalizedMediaType: String
        get() = if (mediaType == "tv" || entityType == "tv" || entityType == "series") "tv" else "movie"

    fun toTmdbItem(): TmdbItem? {
        val resolvedId = tmdbId ?: externalId.toIntOrNull() ?: return null
        return TmdbItem(
            id = resolvedId,
            title = title,
            overview = overview.takeIf(String::isNotBlank),
            posterPath = posterUrl.tmdbImagePath(),
            backdropPath = backdropUrl.tmdbImagePath(),
            year = year.takeIf(String::isNotBlank),
            mediaType = normalizedMediaType,
            rating = score,
            releaseDate = releaseDate.takeIf(String::isNotBlank),
        )
    }
}

@Serializable
data class TgtoCalendarEpisode(
    @SerialName("season_number") val seasonNumber: Int? = null,
    @SerialName("episode_number") val episodeNumber: Int? = null,
    val name: String = "",
    @SerialName("air_date") val airDate: String = "",
    @SerialName("episode_type") val episodeType: String = "",
)

@Serializable
data class TgtoMediaPage(
    val items: List<TgtoMediaItem> = emptyList(),
    val page: Int = 1,
    @SerialName("total_pages") val totalPages: Int = 1,
    @SerialName("total_items") val totalItems: Int = 0,
    @SerialName("has_next_page") val hasNextPage: Boolean = false,
    @SerialName("feed_status") val feedStatus: String = "",
    @SerialName("feed_block_reason") val feedBlockReason: String = "",
    @SerialName("configuration_message") val configurationMessage: String = "",
    @SerialName("is_stale") val isStale: Boolean = false,
    @SerialName("start_date") val startDate: String = "",
    val timezone: String = "Asia/Shanghai",
)

@Serializable
data class TgtoEmbyCardTarget(
    val key: String,
    @SerialName("media_type") val mediaType: String,
    @SerialName("tmdb_id") val tmdbId: String,
    val title: String,
    @SerialName("original_title") val originalTitle: String = "",
    val year: String = "",
    @SerialName("series_status") val seriesStatus: String = "",
    @SerialName("number_of_episodes") val numberOfEpisodes: Int = 0,
)

@Serializable
data class TgtoEmbyCardsRequest(
    val items: List<TgtoEmbyCardTarget>,
)

@Serializable
data class TgtoEmbyCardStatus(
    val state: String = "loading",
    val libraryStatus: String = "",
    val availableCount: Int = 0,
    val missingCount: Int = 0,
    val displayLabel: String = "",
)

@Serializable
data class TgtoEmbyCardEntry(
    val key: String = "",
    val result: TgtoEmbyCardStatus = TgtoEmbyCardStatus(),
)

@Serializable
data class TgtoEmbyCardsResult(
    val configured: Boolean = false,
    val items: List<TgtoEmbyCardEntry> = emptyList(),
)

fun TgtoMediaItem.toEmbyCardTarget(): TgtoEmbyCardTarget? {
    val resolvedTmdbId = tmdbId ?: return null
    return TgtoEmbyCardTarget(
        key = "$normalizedMediaType:$resolvedTmdbId",
        mediaType = normalizedMediaType,
        tmdbId = resolvedTmdbId.toString(),
        title = title,
        originalTitle = originalTitle,
        year = year,
        seriesStatus = status,
        numberOfEpisodes = numberOfEpisodes ?: 0,
    )
}

@Serializable
data class TgtoTarget(
    val configured: Boolean = false,
    @SerialName("folder_id") val folderId: String = "",
    @SerialName("folder_name") val folderName: String = "",
)

@Serializable
data class TgtoDirectoryItem(
    val id: String,
    val name: String = "",
    @SerialName("parent_id") val parentId: String = "0",
    val provider: String = "123",
)

@Serializable
data class TgtoDirectoryListing(
    val success: Boolean = false,
    val count: Int = 0,
    val items: List<TgtoDirectoryItem> = emptyList(),
    @SerialName("parent_id") val parentId: String = "0",
    val provider: String = "123",
    val error: String? = null,
    val message: String? = null,
)

@Serializable
data class TgtoEmbySettings(
    val enabled: Boolean = true,
    val configured: Boolean = false,
    @SerialName("server_url") val serverUrl: String = "",
    @SerialName("api_key_configured") val apiKeyConfigured: Boolean = false,
    val message: String = "",
)

@Serializable
data class TgtoSettings(
    @SerialName("tg_resource_channels") val tgResourceChannels: Map<String, List<String>> = emptyMap(),
    @SerialName("media_transfer_targets") val mediaTransferTargets: Map<String, TgtoTarget> = emptyMap(),
    @SerialName("media_emby") val mediaEmby: TgtoEmbySettings = TgtoEmbySettings(),
    @SerialName("target_provider") val targetProvider: String = "123",
    val resolutions: List<String> = emptyList(),
    val qualities: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
)

@Serializable
data class TgtoSettingsUpdate(
    @SerialName("tg_resource_channels") val tgResourceChannels: Map<String, List<String>>,
    @SerialName("media_transfer_targets") val mediaTransferTargets: Map<String, TgtoTarget>,
    @SerialName("media_emby") val mediaEmby: TgtoEmbyUpdate,
)

@Serializable
data class TgtoEmbyUpdate(
    val enabled: Boolean,
    @SerialName("server_url") val serverUrl: String,
    @SerialName("api_key") val apiKey: String,
)

@Serializable
data class TgtoEmbyTestRequest(
    @SerialName("media_emby") val mediaEmby: TgtoEmbyUpdate,
)

@Serializable
data class TgtoEmbyTestResult(
    val total: Int = 0,
)

@Serializable
data class TgtoResourceSearchRequest(
    val title: String,
    val aliases: List<String>,
    @SerialName("tmdb_id") val tmdbId: Int?,
    @SerialName("media_type") val mediaType: String,
    val year: String?,
    val season: Int?,
    val episode: Int?,
    val provider: String = "123",
    val sources: List<String> = listOf("tg"),
    val preferences: TgtoResourcePreferences,
)

@Serializable
data class TgtoResourcePreferences(
    @SerialName("target_provider") val targetProvider: String = "123",
    val resolutions: List<String> = emptyList(),
    val qualities: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    @SerialName("tg_resource_channels") val tgResourceChannels: Map<String, List<String>>,
)

@Serializable
data class TgtoResourceSearchResult(
    val items: List<TgtoResourceItem> = emptyList(),
    val errors: List<TgtoResourceError> = emptyList(),
)

@Serializable
data class TgtoResourceError(
    val source: String = "",
    val code: String = "",
    val error: String = "",
    val message: String = "",
)

@Serializable
data class TgtoResourceItem(
    @SerialName("item_key") val itemKey: String,
    val source: String = "tg",
    val provider: String = "123",
    @SerialName("provider_label") val providerLabel: String = "123",
    val title: String = "",
    val slug: String = "",
    @SerialName("share_url") val shareUrl: String = "",
    @SerialName("resource_url") val resourceUrl: String = "",
    @SerialName("message_url") val messageUrl: String = "",
    @SerialName("channel_title") val channelTitle: String = "",
    val sharer: String = "",
    val size: String = "",
    val resolution: String = "",
    val quality: String = "",
    @SerialName("resource_spec_tags") val resourceSpecTags: List<String> = emptyList(),
    @SerialName("subtitle_languages") val subtitleLanguages: List<String> = emptyList(),
    @SerialName("subtitle_types") val subtitleTypes: List<String> = emptyList(),
    @SerialName("technical_tags") val technicalTags: List<String> = emptyList(),
    @SerialName("published_at") val publishedAt: String = "",
    val episode: TgtoResourceEpisode = TgtoResourceEpisode(),
)

@Serializable
data class TgtoResourceEpisode(
    @SerialName("season_num") val seasonNumber: Int? = null,
    @SerialName("episode_num") val episodeNumber: Int? = null,
    @SerialName("end_episode_num") val endEpisodeNumber: Int? = null,
    @SerialName("total_episode_num") val totalEpisodeNumber: Int? = null,
    @SerialName("is_complete") val isComplete: Boolean = false,
    @SerialName("is_updated") val isUpdated: Boolean = false,
)

private fun String.tmdbImagePath(): String? {
    if (isBlank()) return null
    val marker = "/t/p/"
    val markerIndex = indexOf(marker)
    if (markerIndex < 0) return takeIf { startsWith('/') }
    val afterSize = substring(markerIndex + marker.length).substringAfter('/')
    return "/$afterSize"
}
