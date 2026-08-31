package com.yfuse.feature.detail

import com.arkivanov.mvikotlin.core.store.Reducer
import com.yfuse.core.data.PlaybackTrackRequest

internal object DetailReducer : Reducer<DetailState, DetailMsg> {
    override fun DetailState.reduce(msg: DetailMsg): DetailState =
        when (msg) {
            DetailMsg.Loading -> copy(loading = true, error = null)
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
            is DetailMsg.SourcesLoaded -> {
                val selected = msg.sources.firstOrNull { it.isCurrent && it.itemId != null }
                copy(
                    sources = msg.sources,
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
            is DetailMsg.ProgressSaving -> copy(progressSaving = msg.value)
            is DetailMsg.EpisodesProgressChanged ->
                copy(
                    episodes =
                        episodes.map { episode ->
                            if (episode.id in msg.episodeIds) {
                                episode.copy(
                                    played = msg.played,
                                    playedPercentage = null,
                                    resumePositionTicks = null,
                                )
                            } else {
                                episode
                            }
                        },
                    progressManagerOpen = false,
                    progressSelection = emptySet(),
                    progressSaving = false,
                    actionMessage = msg.message,
                )
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
            is DetailMsg.AudioLanguageSelected -> copy(preferredAudioLanguage = msg.language)
            is DetailMsg.SubtitleLanguageSelected -> copy(preferredSubtitleLanguage = msg.language)
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
    )
}
