package com.yfuse.feature.detail

import com.arkivanov.mvikotlin.core.store.Reducer
import com.yfuse.core.data.PlaybackTrackRequest

internal object DetailReducer : Reducer<DetailState, DetailMsg> {
    override fun DetailState.reduce(msg: DetailMsg): DetailState =
        when (msg) {
            DetailMsg.Loading -> copy(loading = true, error = null)
            is DetailMsg.Refreshed ->
                if (server?.id == msg.server.id && detail?.id == msg.detail.id) {
                    copy(
                        // Cast is loaded independently; a lean metadata refresh must not erase it.
                        detail = msg.detail.copy(people = msg.detail.people.ifEmpty { detail.people }),
                        loading = false,
                        error = null,
                    )
                } else {
                    this
                }
            is DetailMsg.PeopleLoaded ->
                if (server?.id == msg.serverId && detail?.id == msg.itemId) {
                    copy(
                        detail = detail.copy(people = msg.people),
                        playSourceDetail =
                            playSourceDetail?.let {
                                if (playServer?.id == msg.serverId &&
                                    it.id == msg.itemId
                                ) {
                                    it.copy(people = msg.people)
                                } else {
                                    it
                                }
                            },
                    )
                } else {
                    this
                }
            is DetailMsg.Loaded ->
                copy(
                    loading = false,
                    detail = msg.detail,
                    server = msg.server,
                    playServer = msg.server,
                    playSourceDetail = msg.detail,
                    selectionLoading = true,
                    watchLater = false,
                    watchLaterLoading = true,
                    watchLaterMutating = false,
                    selectedVersionId =
                        msg.detail.versions
                            .firstOrNull()
                            ?.id,
                )
            is DetailMsg.Failed ->
                copy(
                    loading = false,
                    resolvingPlay = false,
                    selectionLoading = false,
                    error = msg.message,
                )
            is DetailMsg.Resolving -> copy(resolvingPlay = msg.value)
            is DetailMsg.SelectionLoading ->
                copy(
                    selectionLoading = msg.value,
                    sourceFailure = if (msg.value) null else sourceFailure,
                )
            is DetailMsg.VersionSelected -> withSelectedVersion(msg.versionId)
            is DetailMsg.EpisodeSelected ->
                copy(
                    selectedEpisodeId = msg.itemId,
                    actionMessage = null,
                )
            is DetailMsg.SeasonsLoaded -> copy(seasons = msg.seasons, selectedSeasonId = msg.selected)
            DetailMsg.EpisodesLoading -> copy(episodesLoading = true)
            DetailMsg.EpisodesLoadingFinished -> copy(episodesLoading = false)
            is DetailMsg.EpisodesLoaded -> copy(episodesLoading = false, episodes = msg.episodes)
            DetailMsg.SourcesLoading -> copy(sourcesLoading = true, sourcesError = null)
            is DetailMsg.SourcesFailed -> copy(sourcesLoading = false, sourcesError = msg.message)
            is DetailMsg.SourcesLoaded -> {
                val selected = msg.sources.firstOrNull { it.isCurrent && it.itemId != null }
                copy(
                    sources = msg.sources,
                    sourcesLoading = !msg.complete,
                    sourcesError = null,
                    selectedSourceServerId = selectedSourceServerId ?: selected?.serverId,
                    selectedSourceItemId = selectedSourceItemId ?: selected?.itemId,
                )
            }
            is DetailMsg.RelatedLoaded -> copy(related = msg.items)
            is DetailMsg.FavoriteChanged ->
                if (
                    server?.id == msg.serverId && detail?.id == msg.itemId
                ) {
                    copy(
                        detail = detail.copy(isFavorite = msg.value),
                        playSourceDetail =
                            playSourceDetail?.let { source ->
                                if (source.id == msg.itemId) source.copy(isFavorite = msg.value) else source
                            },
                        actionMessage = null,
                    )
                } else {
                    this
                }
            is DetailMsg.PlayedChanged ->
                if (
                    server?.id == msg.serverId && detail?.id == msg.itemId
                ) {
                    copy(
                        detail = detail.copy(played = msg.value),
                        playSourceDetail =
                            playSourceDetail?.let { source ->
                                if (source.id == msg.itemId) source.copy(played = msg.value) else source
                            },
                        // Either way the server drops the resume point, so 继续播放 goes too.
                        playPositionTicks = if (playTarget?.id == msg.itemId) 0L else playPositionTicks,
                        actionMessage = null,
                    )
                } else {
                    this
                }
            DetailMsg.ProgressManagerOpened ->
                copy(
                    progressManagerOpen = true,
                    progressSelection = emptySet(),
                    progressSaving = false,
                )
            DetailMsg.ProgressManagerClosed ->
                if (progressSaving) this else copy(progressManagerOpen = false, progressSelection = emptySet())
            is DetailMsg.ProgressSelectionChanged -> copy(progressSelection = msg.episodeIds)
            is DetailMsg.ProgressSaving ->
                copy(
                    progressSaving = msg.value,
                    progressCompleted = if (msg.value) msg.completed else 0,
                    progressTotal = if (msg.value) msg.total else 0,
                )
            is DetailMsg.EpisodesProgressChanged ->
                withEpisodeProgress(msg.episodeIds, msg.played).copy(
                    progressManagerOpen = false,
                    progressSelection = emptySet(),
                    progressSaving = false,
                    actionMessage = msg.message,
                )
            is DetailMsg.SeriesProgressChanged ->
                if (server?.id == msg.serverId && detail?.id == msg.itemId) {
                    // 播放's target is one of the series' episodes even when its season is not listed.
                    withEpisodeProgress(
                        episodes.mapTo(mutableSetOf()) { it.id } + listOfNotNull(playTarget?.id),
                        msg.played,
                    ).copy(actionMessage = msg.message)
                } else {
                    this
                }
            is DetailMsg.WatchLaterChanged ->
                if (
                    server?.id == msg.serverId && detail?.id == msg.itemId
                ) {
                    copy(
                        watchLater = msg.value,
                        actionMessage = null,
                    )
                } else {
                    this
                }
            is DetailMsg.WatchLaterLoading ->
                if (
                    server?.id == msg.serverId && detail?.id == msg.itemId
                ) {
                    copy(watchLaterLoading = msg.value)
                } else {
                    this
                }
            is DetailMsg.WatchLaterMutating ->
                if (
                    server?.id == msg.serverId && detail?.id == msg.itemId
                ) {
                    copy(watchLaterMutating = msg.value)
                } else {
                    this
                }
            is DetailMsg.ActionMessage -> copy(actionMessage = msg.value)
            is DetailMsg.SourceFailure -> copy(sourceFailure = msg.value, selectionLoading = false)
            is DetailMsg.AudioLanguageSelected ->
                copy(
                    preferredAudioLanguage = msg.language,
                    preferredAudioOrdinal = msg.ordinal.takeIf { msg.language != null },
                )
            is DetailMsg.SubtitleLanguageSelected ->
                copy(
                    preferredSubtitleLanguage = msg.language,
                    preferredSubtitleOrdinal =
                        msg.ordinal.takeIf {
                            msg.language != null && msg.language != PlaybackTrackRequest.SUBTITLES_OFF
                        },
                )
            DetailMsg.OrganizationLoading ->
                copy(
                    organizationLoading = true,
                    organizationError = null,
                )
            is DetailMsg.OrganizationLoaded ->
                copy(
                    organizationContainers = msg.containers,
                    organizationLoading = false,
                    organizationError = null,
                )
            is DetailMsg.OrganizationLoadFailed ->
                copy(
                    organizationLoading = false,
                    organizationError = msg.message,
                )
            is DetailMsg.OrganizationAdding ->
                copy(
                    addingContainerIds = addingContainerIds + msg.containerId,
                    organizationError = null,
                )
            is DetailMsg.OrganizationAdded ->
                copy(
                    addingContainerIds = addingContainerIds - msg.containerId,
                    addedContainerIds = addedContainerIds + msg.containerId,
                    organizationError = null,
                )
            is DetailMsg.OrganizationAddFailed ->
                copy(
                    addingContainerIds = addingContainerIds - msg.containerId,
                    organizationError = msg.message,
                )
            is DetailMsg.PlaybackSelectionLoaded -> {
                // Episode resolution captures sourceDetail before it suspends. Favorite/played
                // mutations can commit while that request is in flight, so never replace the
                // same committed source with the stale captured copy when the response returns.
                val visibleSource =
                    detail?.takeIf {
                        server?.id == msg.server.id && it.id == msg.sourceDetail.id
                    }
                val committedSource =
                    playSourceDetail?.takeIf {
                        playServer?.id == msg.server.id && it.id == msg.sourceDetail.id
                    }
                val retainedSource = visibleSource ?: committedSource
                val sourceChanged = retainedSource == null
                val resolvedSource = retainedSource ?: msg.sourceDetail
                val organizationSourceChanged =
                    server?.id != msg.server.id || detail?.id != resolvedSource.id
                val versionId =
                    msg.preferredVersionId
                        ?.takeIf { preferred ->
                            msg.target.versions.any { it.id == preferred }
                        }
                        ?: msg.target.versions
                            .firstOrNull()
                            ?.id
                copy(
                    detail = resolvedSource,
                    server = msg.server,
                    playServer = msg.server,
                    playSourceDetail = resolvedSource,
                    playTarget = msg.target,
                    playPositionTicks = msg.positionTicks,
                    selectedSourceServerId = msg.server.id,
                    selectedSourceItemId = msg.sourceDetail.id,
                    selectedEpisodeId = msg.target.id.takeIf { msg.target.type == "Episode" },
                    seasons = msg.seasons ?: seasons,
                    selectedSeasonId =
                        if (msg.seasons != null) {
                            msg.selectedSeasonId
                        } else {
                            selectedSeasonId
                        },
                    organizationContainers =
                        if (organizationSourceChanged) {
                            emptyList()
                        } else {
                            organizationContainers
                        },
                    organizationLoading = if (organizationSourceChanged) false else organizationLoading,
                    organizationError = if (organizationSourceChanged) null else organizationError,
                    addingContainerIds = if (organizationSourceChanged) emptySet() else addingContainerIds,
                    addedContainerIds = if (organizationSourceChanged) emptySet() else addedContainerIds,
                    watchLater = if (organizationSourceChanged) false else watchLater,
                    watchLaterLoading = if (organizationSourceChanged) true else watchLaterLoading,
                    watchLaterMutating = if (organizationSourceChanged) false else watchLaterMutating,
                    episodes = msg.episodes ?: episodes,
                    episodesLoading = false,
                    selectionLoading = false,
                    related = if (sourceChanged) emptyList() else related,
                    actionMessage = null,
                    sourceFailure = null,
                ).withSelectedVersion(versionId)
            }
        }
}

/**
 * Marking an episode played or unplayed also drops its resume point on the server. The rail
 * shows that at once, and so does 播放 when it would open one of these episodes: 继续播放 and 从头
 * used to stay behind, offering to resume a position that no longer existed.
 */
private fun DetailState.withEpisodeProgress(
    episodeIds: Set<String>,
    played: Boolean,
): DetailState =
    copy(
        episodes =
            episodes.map { episode ->
                if (episode.id in episodeIds) {
                    episode.copy(
                        played = played,
                        playedPercentage = null,
                        resumePositionTicks = null,
                    )
                } else {
                    episode
                }
            },
        playPositionTicks = if (playTarget?.id?.let(episodeIds::contains) == true) 0L else playPositionTicks,
    )

private fun DetailState.withSelectedVersion(versionId: String?): DetailState {
    val version = playTarget?.versions?.firstOrNull { it.id == versionId }
    val audioLanguage =
        preferredAudioLanguage?.takeIf { selected ->
            version?.audioTracks?.any { it.language.equals(selected, ignoreCase = true) } == true
        }
    val subtitleLanguage =
        preferredSubtitleLanguage?.takeIf { selected ->
            selected == PlaybackTrackRequest.SUBTITLES_OFF ||
                version?.subtitleTracks?.any { it.language.equals(selected, ignoreCase = true) } == true
        }
    return copy(
        selectedVersionId = version?.id,
        preferredAudioLanguage = audioLanguage,
        preferredSubtitleLanguage = subtitleLanguage,
        preferredAudioOrdinal =
            preferredAudioOrdinal.keptFor(audioLanguage, version?.audioTracks.orEmpty().map { it.language }),
        preferredSubtitleOrdinal =
            preferredSubtitleOrdinal.keptFor(subtitleLanguage, version?.subtitleTracks.orEmpty().map { it.language }),
    )
}

/** A pick among one language's tracks survives a change of file only if the new file has that many. */
private fun Int?.keptFor(
    language: String?,
    languages: List<String?>,
): Int? =
    this?.takeIf { ordinal ->
        language != null && languages.count { it.equals(language, ignoreCase = true) } > ordinal
    }
