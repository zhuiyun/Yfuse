package com.yfuse.feature.detail

import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.model.Episode
import com.yfuse.core.model.MediaDetail
import com.yfuse.core.model.MediaServerKind
import com.yfuse.core.model.SavedServer

internal class DetailPlaybackResolver(
    private val repo: EmbyRepository,
) {
    private val seriesCatalogLoader = SeriesCatalogLoader(repo)

    /** The series this detail belongs to, if any. */
    fun seriesIdOf(detail: MediaDetail): String? =
        when (detail.type) {
            "Series" -> detail.id
            "Episode" -> detail.seriesId
            else -> null
        }

    /**
     * Resolves only the concrete item required to start playback. Season and episode
     * lists are useful detail-page enrichment, but a slow catalog endpoint must not
     * keep the play button spinning after its target is already known.
     */
    suspend fun resolveInitialPlaybackSelection(
        server: SavedServer,
        sourceDetail: MediaDetail,
    ): Result<ResolvedPlaybackSelection> =
        cancellableResult {
            if (sourceDetail.type != "Series") {
                val targetDetail =
                    if (server.kind == MediaServerKind.Emby) {
                        repo.playbackItemDetail(server, sourceDetail.id).getOrThrow()
                    } else {
                        // Plex ignores the lightweight-field flag and already returned its
                        // playback metadata with the first request.
                        sourceDetail
                    }
                return@cancellableResult ResolvedPlaybackSelection(
                    server = server,
                    sourceDetail = sourceDetail,
                    target = targetDetail,
                    positionTicks =
                        targetDetail.resumePositionTicks
                            ?: sourceDetail.resumePositionTicks
                            ?: 0L,
                )
            }

            repo.cachedResumeEpisode(server, sourceDetail.id)?.let { target ->
                val playableTarget =
                    if (target.versions.isNotEmpty()) {
                        target
                    } else {
                        repo.playbackItemDetail(server, target.id).getOrThrow()
                    }
                return@cancellableResult ResolvedPlaybackSelection(
                    server,
                    sourceDetail,
                    playableTarget,
                    target.resumePositionTicks ?: playableTarget.resumePositionTicks ?: 0L,
                )
            }
            val resolution = repo.resolveDetailPlayTarget(server, sourceDetail).getOrThrow()
            val targetDetail =
                repo
                    .playbackItemDetail(server, resolution.target.itemId)
                    .getOrThrow()
            ResolvedPlaybackSelection(
                server = server,
                sourceDetail = sourceDetail,
                target = targetDetail,
                positionTicks = resolution.target.startPositionTicks,
                catalogEpisodes = resolution.episodes,
            )
        }

    suspend fun resolvePlaybackSelection(
        server: SavedServer,
        sourceDetail: MediaDetail,
        preferredEpisode: EpisodeCoordinate?,
        preferredPlaybackItemId: String? = null,
    ): Result<ResolvedPlaybackSelection> =
        cancellableResult {
            if (sourceDetail.type != "Series") {
                val catalog =
                    seriesIdOf(sourceDetail)?.let { seriesId ->
                        loadSeriesCatalog(server, seriesId, sourceDetail, allEpisodes = null)
                    }
                return@cancellableResult ResolvedPlaybackSelection(
                    server = server,
                    sourceDetail = sourceDetail,
                    target = sourceDetail,
                    positionTicks = sourceDetail.resumePositionTicks ?: 0L,
                    seasons = catalog?.seasons.orEmpty(),
                    selectedSeasonId = catalog?.selectedSeasonId,
                    episodes = catalog?.episodes.orEmpty(),
                )
            }

            if (preferredPlaybackItemId != null) {
                val targetDetail =
                    repo
                        .itemDetail(
                            server,
                            preferredPlaybackItemId,
                            includeInheritedPeople = false,
                        ).getOrThrow()
                if (targetDetail.type != "Episode" || targetDetail.seriesId != sourceDetail.id) {
                    throw EpisodeUnavailableException(
                        seasonNumber = targetDetail.seasonNumber,
                        episodeNumber = targetDetail.episodeNumber,
                    )
                }
                val catalog =
                    loadSeriesCatalog(
                        server = server,
                        seriesId = sourceDetail.id,
                        target = targetDetail,
                        allEpisodes = null,
                    )
                return@cancellableResult ResolvedPlaybackSelection(
                    server = server,
                    sourceDetail = sourceDetail,
                    target = targetDetail,
                    positionTicks = targetDetail.resumePositionTicks ?: 0L,
                    seasons = catalog.seasons,
                    selectedSeasonId = catalog.selectedSeasonId,
                    episodes = catalog.episodes,
                )
            }

            var allEpisodes =
                preferredEpisode?.let {
                    // Failure is not the same as "this server lacks the episode". Treating both
                    // as an empty list silently selected NextUp and bypassed the retry policy.
                    repo
                        .episodes(
                            server = server,
                            seriesId = sourceDetail.id,
                            seasonId = null,
                            includeMediaSources = true,
                        ).getOrThrow()
                }
            val resolvedTarget =
                if (preferredEpisode != null) {
                    val matchedEpisode =
                        allEpisodes?.firstOrNull { episode ->
                            episode.seasonNumber == preferredEpisode.seasonNumber &&
                                episode.indexNumber == preferredEpisode.episodeNumber
                        } ?: throw EpisodeUnavailableException(
                            seasonNumber = preferredEpisode.seasonNumber,
                            episodeNumber = preferredEpisode.episodeNumber,
                        )
                    com.yfuse.core.model.PlayTarget(
                        matchedEpisode.id,
                        matchedEpisode.resumePositionTicks ?: 0L,
                    )
                } else {
                    val resolution = repo.resolvePlayTargetWithEpisodes(server, sourceDetail).getOrThrow()
                    allEpisodes = resolution.episodes
                    resolution.target
                }
            val targetDetail =
                repo
                    .itemDetail(
                        server,
                        resolvedTarget.itemId,
                        includeInheritedPeople = false,
                    ).getOrThrow()
            val catalog =
                loadSeriesCatalog(
                    server = server,
                    seriesId = sourceDetail.id,
                    target = targetDetail,
                    allEpisodes = allEpisodes,
                )
            ResolvedPlaybackSelection(
                server = server,
                sourceDetail = sourceDetail,
                target = targetDetail,
                positionTicks = resolvedTarget.startPositionTicks,
                seasons = catalog.seasons,
                selectedSeasonId = catalog.selectedSeasonId,
                episodes = catalog.episodes,
            )
        }

    suspend fun loadSeriesCatalog(
        server: SavedServer,
        seriesId: String,
        target: MediaDetail,
        allEpisodes: List<Episode>?,
    ): SeriesCatalog = seriesCatalogLoader.load(server, seriesId, target, allEpisodes)
}
