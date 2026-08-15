package com.yfuse.core.data

import com.yfuse.core.data.dto.BaseItemDto
import com.yfuse.core.data.dto.ItemsResponseDto
import com.yfuse.core.data.dto.MediaSourceDto
import com.yfuse.core.data.dto.toSourceInfo
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.SavedServer
import com.yfuse.core.model.ServerSource
import com.yfuse.core.model.SourceInfo
import com.yfuse.core.model.compareSourceInfoBestFirst
import com.yfuse.core.network.EmbyError
import com.yfuse.core.network.EmbyErrorException
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.io.IOException

private const val SOURCE_DISCOVERY_MAX_ATTEMPTS = 3
private const val SOURCE_DISCOVERY_RETRY_DELAY_MS = 250L

/** Every server can list several files; resource comparison represents its best one. */
private fun List<MediaSourceDto>?.bestSourceInfo(): SourceInfo? =
    orEmpty()
        .mapNotNull { it.toSourceInfo() }
        .minWithOrNull(Comparator(::compareSourceInfoBestFirst))

private sealed interface ComparableSourceResult {
    /** The title/episode exists; null only means its stream metadata was omitted. */
    data class Found(
        val source: SourceInfo?,
    ) : ComparableSourceResult

    /** The title exists on this server, but the requested series coordinate does not. */
    data object MissingEpisode : ComparableSourceResult
}

/** Retry only failures that a second connection can plausibly repair. */
private suspend fun <T> discoverSourceWithRetry(block: suspend () -> T): Result<T> {
    var attempt = 1
    while (true) {
        val result = runCatching { block() }
        result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        val error = result.exceptionOrNull()
        if (
            error == null ||
            attempt >= SOURCE_DISCOVERY_MAX_ATTEMPTS ||
            !error.isTransientSourceDiscoveryFailure()
        ) {
            return result
        }
        delay(SOURCE_DISCOVERY_RETRY_DELAY_MS * attempt)
        attempt++
    }
}

private fun Throwable.isTransientSourceDiscoveryFailure(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        when (current) {
            is EmbyErrorException ->
                when (val error = current.error) {
                    EmbyError.Network -> return true
                    is EmbyError.Server -> return error.code in 500..599
                    else -> return false
                }
            is ResponseException -> return current.response.status.value in 500..599
            is IOException -> return true
        }
        current = current.cause
    }
    return false
}

internal class EmbySourceService(
    private val client: HttpClient,
    private val detailService: EmbyDetailService,
) {
    /**
     * 跨服务器片源对比: looks the title up on every saved server and reports which
     * ones carry it, with the best source's specs. Per-server failures degrade to
     * "unreachable" rather than failing the whole comparison.
     */
    suspend fun compareSources(
        servers: List<SavedServer>,
        currentServerId: String?,
        title: String,
        tmdbId: Int? = null,
        mediaType: String? = null,
        year: Int? = null,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
    ): List<ServerSource> =
        coroutineScope {
            servers
                .map { server ->
                    async {
                        val lookup =
                            discoverSourceWithRetry {
                                suspend fun query(providerMatch: Boolean): ItemsResponseDto =
                                    client
                                        .get("${server.baseUrl}/Users/${server.userId}/Items") {
                                            header("X-Emby-Token", server.accessToken)
                                            parameter("Recursive", true)
                                            parameter(
                                                "IncludeItemTypes",
                                                when (mediaType) {
                                                    "tv" -> "Series"
                                                    "movie" -> "Movie"
                                                    else -> "Movie,Series"
                                                },
                                            )
                                            if (providerMatch && tmdbId != null) {
                                                parameter("AnyProviderIdEquals", "tmdb.$tmdbId")
                                            } else {
                                                parameter("SearchTerm", title)
                                            }
                                            parameter("Fields", "MediaSources,ProductionYear,ProviderIds")
                                            parameter("Limit", 5)
                                        }.body()
                                val providerItems =
                                    if (tmdbId != null) {
                                        query(providerMatch = true).Items
                                    } else {
                                        emptyList()
                                    }
                                val candidates =
                                    providerItems.ifEmpty {
                                        query(providerMatch = false).Items
                                    }
                                candidates.firstOrNull { candidate ->
                                    val titleMatches = candidate.Name.equals(title, ignoreCase = true)
                                    val yearMatches = year == null || candidate.ProductionYear == year
                                    val typeMatches =
                                        when (mediaType) {
                                            "tv" -> candidate.Type == "Series"
                                            "movie" -> candidate.Type == "Movie"
                                            else -> true
                                        }
                                    titleMatches && yearMatches && typeMatches
                                } ?: candidates.firstOrNull()
                            }
                        lookup.onFailure {
                            AppLog.warning(
                                category = "emby",
                                event = "source_lookup_failed",
                                message = "Cross-server source lookup failed",
                                throwable = it,
                                attributes = mapOf("serverId" to server.id),
                            )
                        }
                        val item = lookup.getOrNull()
                        val comparable =
                            item?.let {
                                discoverSourceWithRetry {
                                    fetchComparableSource(
                                        server = server,
                                        item = it,
                                        seasonNumber = seasonNumber,
                                        episodeNumber = episodeNumber,
                                    )
                                }.onFailure { error ->
                                    AppLog.warning(
                                        category = "emby",
                                        event = "source_metadata_degraded",
                                        message = "Cross-server source metadata lookup failed",
                                        throwable = error,
                                        attributes = mapOf("serverId" to server.id),
                                    )
                                }
                            }
                        val source =
                            when (val result = comparable?.getOrNull()) {
                                ComparableSourceResult.MissingEpisode -> null
                                is ComparableSourceResult.Found ->
                                    result.source
                                        // A matching item is still a resource when this server withholds
                                        // only its stream metadata.
                                        ?: SourceInfo("已有资源", null, null)
                                null ->
                                    if (item != null) {
                                        // A failed metadata request says nothing about availability. Keep
                                        // the item selectable so a later user-initiated resolve can retry.
                                        SourceInfo("已有资源", null, null)
                                    } else {
                                        null
                                    }
                            }
                        ServerSource(
                            serverId = server.id,
                            serverName = server.serverName,
                            isCurrent = server.id == currentServerId,
                            itemId = item?.Id,
                            source = source,
                            reachable = lookup.isSuccess,
                        )
                    }
                }.awaitAll()
        }

    /**
     * Search results frequently omit MediaSources. Fetch the concrete item again;
     * for a series, resolve a playable episode because the Series object itself
     * never owns a video stream.
     */
    private suspend fun fetchComparableSource(
        server: SavedServer,
        item: BaseItemDto,
        seasonNumber: Int?,
        episodeNumber: Int?,
    ): ComparableSourceResult {
        if (item.Type != "Series") {
            item.MediaSources.bestSourceInfo()?.let {
                return ComparableSourceResult.Found(it)
            }
        }

        val playable =
            if (item.Type == "Series") {
                if (seasonNumber != null && episodeNumber != null) {
                    fetchEpisodeAtCoordinate(server, item.Id, seasonNumber, episodeNumber)
                        ?: return ComparableSourceResult.MissingEpisode
                } else {
                    detailService.fetchNextUp(server, item.Id) ?: detailService.fetchFirstEpisode(server, item.Id)
                }
            } else {
                item
            } ?: return ComparableSourceResult.Found(null)

        playable.MediaSources.bestSourceInfo()?.let {
            return ComparableSourceResult.Found(it)
        }

        val full: BaseItemDto =
            client
                .get("${server.baseUrl}/Users/${server.userId}/Items/${playable.Id}") {
                    header("X-Emby-Token", server.accessToken)
                    parameter("Fields", "MediaSources")
                }.body()
        return ComparableSourceResult.Found(full.MediaSources.bestSourceInfo())
    }

    /** Find the same episode on another server; never substitute that account's NextUp. */
    private suspend fun fetchEpisodeAtCoordinate(
        server: SavedServer,
        seriesId: String,
        seasonNumber: Int,
        episodeNumber: Int,
    ): BaseItemDto? {
        val dto: ItemsResponseDto =
            client
                .get("${server.baseUrl}/Shows/$seriesId/Episodes") {
                    header("X-Emby-Token", server.accessToken)
                    parameter("UserId", server.userId)
                    parameter("Season", seasonNumber)
                    parameter("Fields", "MediaSources,MediaStreams")
                    parameter("Limit", 10_000)
                }.body()
        return dto.Items.firstOrNull { episode ->
            episode.ParentIndexNumber == seasonNumber && episode.IndexNumber == episodeNumber
        }
    }
}
