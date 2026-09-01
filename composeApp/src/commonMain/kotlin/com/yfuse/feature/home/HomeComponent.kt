package com.yfuse.feature.home

import androidx.compose.foundation.lazy.LazyListState
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.labels
import com.yfuse.core.data.AiringCalendarRepository
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.TmdbHomeCache
import com.yfuse.core.data.TmdbRepository
import com.yfuse.core.model.CalendarDay
import com.yfuse.core.model.CalendarEntry
import com.yfuse.core.model.TmdbItem
import com.yfuse.core.sync.ServerSyncManager
import com.yfuse.core.util.componentScope
import com.yfuse.feature.calendar.loadCalendarWithDeadline
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class HomeComponent(
    componentContext: ComponentContext,
    storeFactory: StoreFactory,
    tmdb: TmdbRepository,
    private val emby: EmbyRepository,
    private val registry: ServerRegistry,
    cache: TmdbHomeCache,
    syncManager: ServerSyncManager,
    private val calendarRepository: AiringCalendarRepository,
    private val onOpenEmbyItem: (String, String) -> Unit,
    private val onPlayEmbyItem: (String, String) -> Unit,
    private val onOpenTmdbItem: (TmdbItem, String?) -> Unit,
    val onOpenSearch: () -> Unit,
    val onOpenLibrary: () -> Unit,
    val onOpenProfile: () -> Unit,
    val onOpenCalendar: () -> Unit,
    private val initialCalendarLoad: Boolean = true,
) : ComponentContext by componentContext {
    private val scope = componentScope(lifecycle)
    private var calendarJob: Job? = null
    private var calendarOpenJob: Job? = null
    private val _calendar = MutableStateFlow(HomeCalendarState())
    val calendar: StateFlow<HomeCalendarState> = _calendar.asStateFlow()

    // The component remains on the Decompose back stack while detail covers it. Keep the
    // actual state object alive so the first frame on return is already at the old viewport;
    // restoring an index after recomposition briefly painted the hero and caused a flash.
    internal val listState = LazyListState()

    val store =
        HomeStoreFactory(
            storeFactory = storeFactory,
            tmdb = tmdb,
            emby = emby,
            registry = registry,
            cache = cache,
            syncManager = syncManager,
        ).create()

    init {
        if (initialCalendarLoad) refreshCalendar()
        registry.data
            .map { data -> data.servers.map { it.id } }
            .distinctUntilChanged()
            .drop(1)
            .onEach { refreshCalendar() }
            .launchIn(scope)
        store.labels
            .onEach { label ->
                when (label) {
                    is HomeLabel.OpenEmbyItem -> onOpenEmbyItem(label.serverId, label.itemId)
                    is HomeLabel.OpenTmdbItem -> onOpenTmdbItem(label.item, label.embyItemId)
                    is HomeLabel.PlayEmbyItem -> onPlayEmbyItem(label.serverId, label.itemId)
                }
            }.launchIn(scope)
        lifecycle.doOnDestroy(store::dispose)
    }

    fun refreshCalendar(forceRefresh: Boolean = false) {
        calendarJob?.cancel()
        _calendar.update { it.copy(loading = true, error = null) }
        calendarJob =
            scope.launch {
                loadCalendarWithDeadline {
                    // The home card only needs tracked/active shows. Global TMDB discovery
                    // belongs to the calendar screen and must not compete with the rest of
                    // the home feed during cold start.
                    calendarRepository.homeCalendar(
                        forceRefresh = forceRefresh,
                        onPreview = { preview ->
                            if (preview.isNotEmpty()) {
                                _calendar.value = HomeCalendarState(days = preview, loading = false)
                            }
                        },
                    )
                }.onSuccess { _calendar.value = HomeCalendarState(days = it, loading = false) }
                    .onFailure { error ->
                        _calendar.update { current ->
                            current.copy(
                                loading = false,
                                error = (error.message ?: "追剧日历加载失败").takeIf { current.days.isEmpty() },
                            )
                        }
                    }
            }
    }

    fun openCalendarEntry(entry: CalendarEntry) {
        val activeServerIds = registry.data.value.servers.mapTo(mutableSetOf()) { it.id }
        entry.directCalendarOpenTarget(activeServerIds)?.let { target ->
            onOpenEmbyItem(target.serverId, target.itemId)
            return
        }
        store.state.calendarOpenTarget(entry)?.let { target ->
            onOpenEmbyItem(target.serverId, target.itemId)
            return
        }

        entry.tmdbCalendarDetailItem()?.let { item ->
            calendarOpenJob?.cancel()
            onOpenTmdbItem(item, null)
            return
        }

        // A previous slow server lookup must never make subsequent card taps inert.
        calendarOpenJob?.cancel()
        calendarOpenJob =
            scope.launch {
                withTimeoutOrNull(CALENDAR_OPEN_RESOLVE_TIMEOUT_MS) {
                    resolveCalendarOpenTarget(entry)
                }?.let { target ->
                    onOpenEmbyItem(target.serverId, target.itemId)
                    return@launch
                }
                onOpenCalendar()
            }
    }

    /**
     * The official schedule can finish before the home rows and their Emby identities.
     * Resolve once more at click time so an already-ingested show remains directly openable.
     */
    private suspend fun resolveCalendarOpenTarget(entry: CalendarEntry): HomeCalendarOpenTarget? =
        coroutineScope {
            val tmdbId = entry.episode.showTmdbId.takeIf { it > 0 }
            val mediaType = if (entry.episode.isMovie) "movie" else "tv"
            val normalizedTitle = entry.episode.showTitle.normalizeCalendarTitle()
            registry.data.value.servers
                .map { server ->
                    async {
                        val exact =
                            tmdbId?.let { id ->
                                emby.findByTmdbId(server, id, mediaType).getOrNull()
                            }
                        val matched =
                            exact ?: emby.search(server, entry.episode.showTitle)
                                .getOrDefault(emptyList())
                                .firstOrNull { candidate ->
                                    val typeMatches =
                                        if (entry.episode.isMovie) {
                                            candidate.type == "Movie"
                                        } else {
                                            candidate.type == "Series" || candidate.type == "Episode"
                                        }
                                    typeMatches && candidate.title.normalizeCalendarTitle() == normalizedTitle
                                }
                        matched?.let { item ->
                            HomeCalendarOpenTarget(
                                serverId = server.id,
                                itemId =
                                    if (item.type == "Episode") {
                                        item.posterItemId.takeIf(String::isNotBlank) ?: item.id
                                    } else {
                                        item.id
                                    },
                            )
                        }
                    }
                }.awaitAll()
                .firstOrNull { it != null }
        }
}

internal data class HomeCalendarOpenTarget(
    val serverId: String,
    val itemId: String,
)

/** A persisted calendar identity is safe only while its owning server is still active. */
internal fun CalendarEntry.directCalendarOpenTarget(activeServerIds: Set<String>): HomeCalendarOpenTarget? {
    val targetItemId = openItemId ?: return null
    val targetServerId =
        serverId?.takeIf(activeServerIds::contains)
            ?: sources
                .firstOrNull { source ->
                    source.serverId in activeServerIds &&
                        (source.itemId == targetItemId || source.seriesItemId == targetItemId)
                }?.serverId
            ?: return null
    return HomeCalendarOpenTarget(targetServerId, targetItemId)
}

/** TMDB-backed calendar cards always have an immediate, network-independent detail route. */
internal fun CalendarEntry.tmdbCalendarDetailItem(): TmdbItem? {
    val tmdbId = episode.showTmdbId.takeIf { it > 0 } ?: return null
    return TmdbItem(
        id = tmdbId,
        title = episode.showTitle,
        overview = null,
        posterPath = episode.posterPath,
        backdropPath = null,
        year = episode.airDate.take(4),
        mediaType = if (episode.isMovie) "movie" else "tv",
        rating = null,
    )
}

private fun String.normalizeCalendarTitle(): String = lowercase().filter(Char::isLetterOrDigit)

/** Resolves an official-only calendar row against media already present on the home screen. */
internal fun HomeState.calendarOpenTarget(entry: CalendarEntry): HomeCalendarOpenTarget? {
    val expectedType = if (entry.episode.isMovie) "Movie" else "Series"
    val candidates =
        (
            resume +
                favorites +
                nextUp +
                libraryContent.flatMap { source ->
                    source.content.rows.flatMap { row ->
                        row.items.map { HomeResumeEntry(it, source.server) }
                    }
                }
        ).distinctBy { it.server.id to it.item.id }
    fun HomeResumeEntry.matchesType(): Boolean =
        if (expectedType == "Movie") {
            item.type == "Movie"
        } else {
            item.type == "Series" || item.type == "Episode"
        }
    val tmdbId = entry.episode.showTmdbId.takeIf { it > 0 }?.toString()
    val exact =
        tmdbId?.let { id ->
            candidates.firstOrNull { candidate ->
                candidate.matchesType() &&
                    candidate.item.providerIds.entries.any { provider ->
                        provider.key.equals("tmdb", ignoreCase = true) && provider.value == id
                    }
            }
        }
    val normalizedTitle = entry.episode.showTitle.normalizeCalendarTitle()
    val matched =
        exact ?: candidates.firstOrNull { candidate ->
            candidate.matchesType() &&
                candidate.item.title.normalizeCalendarTitle() == normalizedTitle
        } ?: return null
    // Home rows contain episodes for resume/next-up; their poster owner is the series item.
    val targetItemId = if (matched.item.type == "Episode") matched.item.posterItemId else matched.item.id
    return HomeCalendarOpenTarget(matched.server.id, targetItemId)
}

data class HomeCalendarState(
    val days: List<CalendarDay> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

private const val CALENDAR_OPEN_RESOLVE_TIMEOUT_MS = 3_000L
