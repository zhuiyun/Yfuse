package com.yfuse.feature.detail

import com.yfuse.core.data.TmdbRepository
import com.yfuse.core.model.Episode
import com.yfuse.core.model.TmdbItem
import com.yfuse.core.sync.parseEpisodeWatchKey
import com.yfuse.core.sync.playback.PlaybackSyncStore
import com.yfuse.core.widget.WidgetMedia
import com.yfuse.core.widget.WidgetPlaybackRequest
import com.yfuse.core.widget.WidgetRepository
import com.yfuse.core.widget.WidgetSourceContext
import com.yfuse.core.widget.hasStreamResolver
import com.yfuse.core.widget.openWidgetVideo
import com.yfuse.core.widget.playbackIdentity
import com.yfuse.core.widget.requiresWidgetDetailLookup
import com.yfuse.core.widget.widgetResumePositionMs
import com.yfuse.feature.home.TmdbInfoComponent
import com.yfuse.feature.widgets.widgetFailureMessage
import com.yfuse.feature.widgets.widgetPlaybackRoutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal data class CatalogResources(
    val routes: List<WidgetMedia> = emptyList(),
    val selectedId: String? = null,
    /** These URLs came from the explicit play request, not a catalog preview. */
    val resolved: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val metadataError: String? = null,
    val season: Int = 1,
    val episode: Int = 1,
    val needsEpisodeInput: Boolean = false,
)

/** Adapts catalog data and component resources to the existing DetailStore and DetailScreen. */
class DetailCatalog(
    private val info: TmdbInfoComponent,
    val context: WidgetSourceContext,
    private val tmdb: TmdbRepository,
    private val widgets: WidgetRepository,
    private val progress: PlaybackSyncStore?,
    private val launch: (WidgetMedia, WidgetPlaybackRequest) -> Unit = ::openWidgetVideo,
) {
    private var scope: CoroutineScope? = null
    private var emit: ((DetailMsg) -> Unit)? = null
    private var current: (() -> DetailState)? = null
    private val mutableResources = MutableStateFlow(CatalogResources())
    internal val resources = mutableResources.asStateFlow()
    private var media = context.media
    private var episodes = emptyList<Episode>()
    private var season = context.media.season ?: 1
    private var episode = context.media.episode ?: 1
    private var episodesLoading = false
    private var episodeError: String? = null
    private var widgetSelected = true
    private var userSelectedTarget = false
    private var openedLibrary = false
    private var catalogKey: Pair<Int, List<Int>>? = null
    private var episodeJob: Job? = null
    private var resourceJob: Job? = null
    private var resourceGeneration = 0

    /** Keep the content identifier when a resolved stream has a different route identifier. */
    private var resourceInput: WidgetMedia? = null
    private var pendingPlay: Boolean? = null
    private var restoredPlayed = false
    private var interactionRevision = 0
    private var cachedScope = progress?.scopeToken
    private val libraryProgress = mutableMapOf<Pair<Int?, Int?>, com.yfuse.core.sync.playback.PlaybackStateRecord>()

    internal fun initialState(): DetailState = snapshot()

    internal fun attach(
        owner: CoroutineScope,
        state: () -> DetailState,
        dispatch: (DetailMsg) -> Unit,
        selectLibrary: (String, String) -> Unit,
    ) {
        scope = owner
        current = state
        emit = dispatch
        owner.launch {
            info.state.collect { data ->
                val key = data.detail.item.id to data.detail.catalogSeasons().mapNotNull { it.indexNumber }
                if (catalogKey != key) {
                    catalogKey = key
                    if (!userSelectedTarget && current?.invoke()?.server == null) restoreCoordinate()
                    if (!mutableResources.value.loading) loadEpisodes()
                }
                update()
                if (!openedLibrary && !userSelectedTarget) {
                    data.sources.firstOrNull { it.reachable && !it.itemId.isNullOrBlank() }?.let {
                        openedLibrary = true
                        widgetSelected = false
                        selectLibrary(it.serverId, requireNotNull(it.itemId))
                    }
                }
            }
        }
        progress?.let { local -> owner.launch { local.changes.collect { update() } } }
        owner.launch { loadSourceDetail() }
    }

    private fun restoreCoordinate() {
        if (userSelectedTarget) return
        val identity = context.playbackIdentity(info.state.value.detail.item)
        val latest =
            identity.aliases
                .flatMap { progress?.statesForSeries(it).orEmpty() }
                .maxByOrNull { it.lastPlayedAtEpochMs } ?: return
        val coordinate =
            (listOf(latest.mediaKey) + latest.aliases).firstNotNullOfOrNull(::parseEpisodeWatchKey) ?: return
        restoredPlayed = latest.played
        season = coordinate.seasonNumber
        episode = coordinate.episodeNumber
    }

    private fun loadEpisodes() {
        episodeJob?.cancel()
        val item = info.state.value.detail.item
        if (item.id <= 0 || item.mediaType !in setOf("tv", "series")) {
            episodes = sourceEpisodes()
            prepareResources()
            return
        }
        val revision = interactionRevision
        episodesLoading = true
        update()
        episodeJob =
            scope?.launch {
                tmdb.detailEpisodes(item.id, season).fold(
                    onSuccess = { loaded ->
                        episodeError = null
                        episodes = loaded
                        if (restoredPlayed && !userSelectedTarget) {
                            loaded
                                .firstOrNull {
                                    (it.indexNumber ?: 0) > episode &&
                                        record(item, it.seasonNumber, it.indexNumber)?.played != true
                                }?.indexNumber
                                ?.let {
                                    episode =
                                        it
                                }
                            restoredPlayed = false
                        }
                        if (!userSelectedTarget &&
                            record(item, season, episode) == null &&
                            loaded.none { it.indexNumber == episode } &&
                            loaded.isNotEmpty()
                        ) {
                            episode = loaded.first().indexNumber ?: 1
                        }
                    },
                    onFailure = {
                        episodes = sourceEpisodes()
                        episodeError = "剧集暂时无法加载"
                    },
                )
                episodesLoading = false
                update()
                if (revision == interactionRevision) prepareResources()
            }
    }

    private suspend fun loadSourceDetail() {
        if (!media.requiresWidgetDetailLookup()) return
        val revision = interactionRevision
        try {
            val loaded = widgets.detail(context.widgetId, context.media)
            media = loaded.copy(children = loaded.children.ifEmpty { context.media.children })
            if (episodes.isEmpty() && !episodesLoading) episodes = sourceEpisodes()
            if (revision == interactionRevision) prepareResources()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mutableResources.value = mutableResources.value.copy(error = widgetFailureMessage(e.message.orEmpty()))
        }
    }

    internal fun handle(
        intent: DetailIntent,
        state: DetailState,
    ): Boolean {
        if (intent is DetailIntent.SelectSource) {
            widgetSelected = false
            return false
        }
        if (intent == DetailIntent.RetrySources && state.server == null) {
            info.retrySources()
            prepareResources()
            return true
        }
        if (intent == DetailIntent.Retry && state.server == null) {
            info.retryDetail()
            loadEpisodes()
            return true
        }
        if (!state.catalogPlayback || (state.selectionLoading && !widgetSelected)) return false
        return when (intent) {
            DetailIntent.Play, DetailIntent.PlayFromStart -> {
                play(intent == DetailIntent.PlayFromStart)
                true
            }
            is DetailIntent.SelectSeason -> {
                season = intent.seasonId.toIntOrNull() ?: return true
                interactionRevision++
                userSelectedTarget = true
                episodes = emptyList()
                pendingPlay = null
                prepareResources()
                loadEpisodes()
                true
            }
            is DetailIntent.SelectEpisode -> {
                val selected = episodes.firstOrNull { it.id == intent.episodeId } ?: return true
                if (episode == selected.indexNumber) {
                    play(false)
                } else {
                    episode = selected.indexNumber ?: 1
                    interactionRevision++
                    userSelectedTarget = true
                    pendingPlay = null
                    update()
                    prepareResources()
                }
                true
            }
            is DetailIntent.MarkEpisodes -> {
                markEpisodes(intent.episodeIds, intent.played)
                true
            }
            is DetailIntent.ApplyEpisodeProgress -> {
                markEpisodes(state.progressSelection, intent.action == EpisodeProgressAction.MarkWatched)
                emit?.invoke(DetailMsg.ProgressManagerClosed)
                true
            }
            else -> false
        }
    }

    private fun markEpisodes(
        ids: Set<String>,
        watched: Boolean,
    ) {
        val item = info.state.value.detail.item
        episodes.filter { it.id in ids }.forEach { target ->
            val key = context.playbackIdentity(item, target.seasonNumber, target.indexNumber)
            progress?.markManual(key.mediaKey, key.aliases, watched)
        }
        update()
    }

    /** Library playback and component playback share the episode selected on this page. */
    internal fun syncLibrarySelection(state: DetailState) {
        if (state.catalogPlayback || state.selectionLoading) return
        val target = state.playTarget ?: return
        val local = progress
        val server = state.playServer
        if (local != null && server != null) {
            if (cachedScope != local.scopeToken) {
                cachedScope = local.scopeToken
                libraryProgress.clear()
            }
            state.localProgressForItem(local, server.id, target.id)?.let { record ->
                libraryProgress[target.seasonNumber to target.episodeNumber] = record
            }
        }
        val nextSeason = target.seasonNumber ?: return
        val nextEpisode = target.episodeNumber ?: return
        if (season == nextSeason && episode == nextEpisode) return
        val seasonChanged = season != nextSeason
        season = nextSeason
        episode = nextEpisode
        prepareResources()
        if (seasonChanged) loadEpisodes()
    }

    internal fun selectRoute(routeId: String) {
        val route = mutableResources.value.routes.firstOrNull { it.id == routeId } ?: return
        userSelectedTarget = true
        interactionRevision++
        val wasSelected = current?.invoke()?.catalogPlayback == true
        val old = mutableResources.value.selectedId
        widgetSelected = true
        mutableResources.value = mutableResources.value.copy(selectedId = route.id)
        update()
        if (old == route.id && wasSelected) play(false)
    }

    internal fun selectCoordinate(
        seasonNumber: Int,
        episodeNumber: Int,
    ) {
        if (seasonNumber < 0 || episodeNumber <= 0) return
        widgetSelected = true
        userSelectedTarget = true
        interactionRevision++
        season = seasonNumber
        episode = episodeNumber
        episodes = emptyList()
        pendingPlay = null
        prepareResources()
        loadEpisodes()
    }

    internal fun retryResources() = play(false)

    internal fun retryMetadata() {
        info.retryDetail()
        loadEpisodes()
    }

    private fun sourceEpisodes(): List<Episode> =
        media
            .widgetPlaybackRoutes()
            .filter { it.episode != null && (it.season ?: 1) == season }
            .distinctBy { it.episode }
            .sortedBy { it.episode }
            .map { route ->
                Episode(
                    id = context.playbackIdentity(info.state.value.detail.item, season, route.episode).mediaKey,
                    name = route.title,
                    indexNumber = route.episode,
                    seasonNumber = season,
                    seasonId = season.toString(),
                    overview = route.description.takeIf(String::isNotBlank),
                    runtimeMinutes = route.durationMinutes,
                    primaryTag = route.backdrop.takeIf(String::isNotBlank),
                    playedPercentage = null,
                    resumePositionTicks = null,
                )
            }

    private fun availableSeasons() =
        info.state.value.detail.catalogSeasons().ifEmpty {
            media.widgetPlaybackRoutes().mapNotNull { it.season }.distinct().sorted().map {
                com.yfuse.core.model
                    .Season(it.toString(), "第 $it 季", it, null)
            }
        }

    private fun prepareResources() {
        resourceJob?.cancel()
        resourceGeneration++
        resourceInput = null
        pendingPlay = null
        val selected = media.copy(season = season, episode = episode)
        val choices =
            selected
                .widgetPlaybackRoutes()
                .filter { route ->
                    (route.season == null || route.season == season) &&
                        (route.episode == null || route.episode == episode)
                }.ifEmpty {
                    if (context.hasStreamResolver(widgets.database.value)) listOf(selected) else emptyList()
                }
        mutableResources.value = CatalogResources(choices, choices.firstOrNull()?.id)
        update()
    }

    private fun identity() = context.playbackIdentity(info.state.value.detail.item, season, episode)

    private fun record(
        item: TmdbItem,
        seasonNumber: Int?,
        episodeNumber: Int?,
    ): com.yfuse.core.sync.playback.PlaybackStateRecord? {
        if (cachedScope != progress?.scopeToken) {
            cachedScope = progress?.scopeToken
            libraryProgress.clear()
        }
        val identity = context.playbackIdentity(item, seasonNumber, episodeNumber)
        val latest = progress?.find(identity.mediaKey, identity.aliases)?.document?.state
        val coordinate = if (identity.seriesKey != null) seasonNumber to episodeNumber else null to null
        val library = libraryProgress[coordinate]
        return if (latest != null && library != null) {
            com.yfuse.core.sync.playback.PlaybackConflictResolver
                .chooseState(latest, library)
        } else {
            latest ?: library
        }
    }

    private fun snapshot(): DetailState {
        val data = info.state.value
        val detail = data.detail.catalogDetail(context)
        val item = data.detail.item
        val selected = episodes.firstOrNull { it.indexNumber == episode }
        val history = record(item, season, episode)
        val series = detail.type == "Series"
        val target =
            detail.copy(
                id = identity().mediaKey,
                type = if (series) "Episode" else "Movie",
                title = selected?.name ?: detail.title,
                seriesId = detail.id.takeIf { series },
                seriesName = detail.title.takeIf { series },
                seasonNumber = season.takeIf { series },
                episodeNumber = episode.takeIf { series },
                runtimeMinutes = selected?.runtimeMinutes ?: detail.runtimeMinutes,
                played = history?.played == true,
                resumePositionTicks = history.widgetResumePositionMs().catalogTicks(),
            )
        return DetailState(
            detail = detail,
            catalogPlayback = widgetSelected,
            playTarget = target,
            playSourceDetail = detail,
            playPositionTicks = history.widgetResumePositionMs().catalogTicks(),
            seasons = availableSeasons(),
            selectedSeasonId = season.toString(),
            listedSeasonId = season.toString(),
            episodes = episodes.map { it.withCatalogProgress(record(item, it.seasonNumber, it.indexNumber)) },
            episodesLoading = episodesLoading,
            selectedEpisodeId = selected?.id,
            sources = data.sources,
            sourcesLoading = data.sourcesLoading,
            sourcesError = data.sourcesError,
            resolvingPlay = pendingPlay != null,
        )
    }

    private fun update() {
        mutableResources.value =
            mutableResources.value.copy(
                season = season,
                episode = episode,
                metadataError = episodeError ?: info.state.value.detailError,
                needsEpisodeInput =
                    info.state.value.detail.item.mediaType in setOf("tv", "series") &&
                        episodes.isEmpty() &&
                        !episodesLoading &&
                        !info.state.value.loading,
            )
        emit?.invoke(DetailMsg.CatalogUpdated(snapshot()))
    }

    private fun resolveResources(
        selected: WidgetMedia,
        fromStart: Boolean,
    ) {
        resourceJob?.cancel()
        val generation = ++resourceGeneration
        val preferredId = mutableResources.value.selectedId.takeIf { resourceInput != null }
        resourceInput = selected
        val enriched =
            context.copy(
                media =
                    context.media.copy(
                        tmdbId =
                            info.state.value.detail.item.id
                                .takeIf { it > 0 }
                                ?.toString() ?: context.media.tmdbId,
                    ),
            )
        val launchIdentity = identity()
        val requestedSeason = season
        val requestedEpisode = episode
        val series = info.state.value.detail.item.mediaType in setOf("tv", "series")
        pendingPlay = fromStart
        resourceJob =
            scope?.launch {
                mutableResources.value = CatalogResources(loading = true)
                try {
                    val resolved =
                        widgets
                            .playback(
                                enriched,
                                selected,
                            ).widgetPlaybackRoutes()
                            .filter {
                                it.videoUrl.isNotBlank() &&
                                    (
                                        !series ||
                                            (
                                                (it.season == null || it.season == requestedSeason) &&
                                                    (it.episode == null || it.episode == requestedEpisode)
                                            )
                                    )
                            }
                    require(resolved.isNotEmpty()) {
                        if (series) "组件未返回第 $requestedSeason 季第 $requestedEpisode 集的播放地址" else "组件未返回播放地址"
                    }
                    if (generation != resourceGeneration) return@launch
                    val preferred = resolved.firstOrNull { it.id == preferredId }
                    val chosen = preferred ?: resolved.singleOrNull()
                    mutableResources.value =
                        CatalogResources(resolved, (preferred ?: resolved.first()).id, resolved = true)
                    val startFromBeginning = pendingPlay ?: fromStart
                    pendingPlay = null
                    if (chosen != null && launchIdentity.aliases.any { it in identity().aliases }) {
                        launchRoute(chosen, startFromBeginning)
                    } else {
                        emit?.invoke(DetailMsg.ActionMessage("请选择播放来源"))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (generation ==
                        resourceGeneration
                    ) {
                        mutableResources.value =
                            CatalogResources(error = widgetFailureMessage(e.message.orEmpty()))
                        pendingPlay = null
                    }
                } finally {
                    if (generation == resourceGeneration) update()
                }
            }
    }

    private fun play(fromStart: Boolean) {
        interactionRevision++
        userSelectedTarget = true
        val resources = mutableResources.value
        if (resources.loading) {
            pendingPlay = fromStart
            update()
            return
        }
        val route = resources.routes.firstOrNull { it.id == resources.selectedId } ?: resources.routes.firstOrNull()
        // The repository waits for restored widget metadata, then gives a declared stream
        // resolver precedence over catalog URLs. Never bypass that policy from this screen.
        if (route == null || route.videoUrl.isBlank() || !resources.resolved) {
            resolveResources(resourceInput ?: route ?: media.copy(season = season, episode = episode), fromStart)
            update()
            return
        }
        launchRoute(route, fromStart)
    }

    private fun launchRoute(
        route: WidgetMedia,
        fromStart: Boolean,
    ) {
        val resources = mutableResources.value
        val position =
            if (fromStart) {
                0L
            } else {
                record(
                    info.state.value.detail.item,
                    season,
                    episode,
                ).widgetResumePositionMs()
            }
        try {
            if (fromStart) progress?.markRestarted(identity().mediaKey, identity().aliases)
            launch(
                route.copy(
                    poster =
                        route.poster.ifBlank {
                            com.yfuse.core.network.TmdbImages
                                .poster(info.state.value.detail.item.posterPath)
                                ?: media.poster
                        },
                    backdrop =
                        route.backdrop.ifBlank {
                            com.yfuse.core.network.TmdbImages
                                .backdrop(info.state.value.detail.item.backdropPath)
                                ?: media.backdrop
                        },
                ),
                WidgetPlaybackRequest(
                    identity(),
                    position,
                    context.copy(
                        media =
                            context.media.copy(
                                tmdbId =
                                    identity().tmdbId?.toString() ?: context.media.tmdbId,
                            ),
                    ),
                ),
            )
            // A later play is a new resolution, using resourceInput's catalog ID and current
            // coordinates. Selecting among this request's resolved routes does not re-resolve.
            mutableResources.value = resources.copy(resolved = false)
        } catch (e: Exception) {
            mutableResources.value = resources.copy(error = widgetFailureMessage(e.message.orEmpty()))
        }
        update()
    }
}
