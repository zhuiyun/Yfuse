package com.yfuse.feature.home

import androidx.compose.foundation.lazy.LazyListState
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.labels
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.AiringCalendarRepository
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.TmdbHomeCache
import com.yfuse.core.data.TmdbRepository
import com.yfuse.core.model.TmdbItem
import com.yfuse.core.model.CalendarDay
import com.yfuse.core.model.CalendarEntry
import com.yfuse.core.sync.ServerSyncManager
import com.yfuse.core.util.componentScope
import com.yfuse.feature.calendar.loadCalendarWithDeadline
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update

class HomeComponent(
    componentContext: ComponentContext,
    storeFactory: StoreFactory,
    tmdb: TmdbRepository,
    emby: EmbyRepository,
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
) : ComponentContext by componentContext {
    private val scope = componentScope(lifecycle)
    private var calendarJob: Job? = null
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
        refreshCalendar()
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
                    calendarRepository.homeCalendar(forceRefresh = forceRefresh)
                }.onSuccess { _calendar.value = HomeCalendarState(days = it, loading = false) }
                    .onFailure { error ->
                        _calendar.update { it.copy(loading = false, error = error.message ?: "追剧日历加载失败") }
                    }
            }
    }

    fun openCalendarEntry(entry: CalendarEntry) {
        val itemId = entry.openItemId
        if (itemId != null) {
            val targetServerId =
                entry.serverId
                    ?: entry.sources.firstOrNull { it.itemId == itemId || it.seriesItemId == itemId }?.serverId
                    ?: entry.sources.firstOrNull()?.serverId
                    ?: registry.defaultServer?.id
            if (targetServerId != null) {
                onOpenEmbyItem(targetServerId, itemId)
                return
            }
        }
        onOpenTmdbItem(
            TmdbItem(
                id = entry.episode.showTmdbId,
                title = entry.episode.showTitle,
                overview = null,
                posterPath = entry.episode.posterPath,
                backdropPath = null,
                year = entry.episode.airDate.take(4),
                mediaType = if (entry.episode.isMovie) "movie" else "tv",
                rating = null,
            ),
            null,
        )
    }
}

data class HomeCalendarState(
    val days: List<CalendarDay> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)
