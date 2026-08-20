package com.yfuse.feature.home

import com.arkivanov.decompose.ComponentContext
import com.yfuse.core.data.TgtoEmbyCardStatus
import com.yfuse.core.data.TgtoMediaItem
import com.yfuse.core.data.TgtoMediaPage
import com.yfuse.core.data.TgtoMediaPreferences
import com.yfuse.core.data.TgtoMediaRepository
import com.yfuse.core.data.TgtoSettings
import com.yfuse.core.util.componentScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class MediaHubSection(
    val label: String,
) {
    Rankings("榜单"),
    Explore("探索"),
    Calendar("追剧日历"),
}

data class MediaHubState(
    val configured: Boolean = false,
    val section: MediaHubSection = MediaHubSection.Rankings,
    val items: List<TgtoMediaItem> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val page: Int = 1,
    val hasNextPage: Boolean = false,
    val rankingProvider: String = "netflix",
    val mediaType: String = "all",
    val exploreSource: String = "tmdb",
    val searchQuery: String = "",
    val calendarKind: String = "all",
    val selectedDate: String = "",
    val timezone: String = "Asia/Shanghai",
    val stale: Boolean = false,
    val embyConfigured: Boolean = false,
    val embyStatuses: Map<String, TgtoEmbyCardStatus> = emptyMap(),
)

class MediaHubComponent(
    componentContext: ComponentContext,
    private val media: TgtoMediaRepository,
    private val preferences: TgtoMediaPreferences,
    private val onOpenItem: (TgtoMediaItem) -> Unit,
    val onOpenSettings: () -> Unit,
) : ComponentContext by componentContext {
    private val scope = componentScope(lifecycle)
    private val _state = MutableStateFlow(MediaHubState())
    val state: StateFlow<MediaHubState> = _state.asStateFlow()
    private var requestVersion = 0
    private var cachedSettings: TgtoSettings? = null

    init {
        scope.launch {
            preferences.connection.collectLatest { connection ->
                _state.update { it.copy(configured = connection.hasPassword) }
                cachedSettings = null
                if (connection.hasPassword) loadCurrent(resetPage = true)
            }
        }
    }

    fun selectSection(section: MediaHubSection) {
        if (_state.value.section == section) return
        _state.update {
            it.copy(
                section = section,
                mediaType = if (section == MediaHubSection.Explore && it.mediaType == "all") "movie" else it.mediaType,
                notice = null,
            )
        }
        scope.launch { loadCurrent(resetPage = true) }
    }

    fun selectRankingProvider(provider: String) {
        if (_state.value.rankingProvider == provider) return
        _state.update { it.copy(rankingProvider = provider) }
        scope.launch { loadCurrent(resetPage = true) }
    }

    fun selectMediaType(mediaType: String) {
        if (_state.value.mediaType == mediaType) return
        _state.update { it.copy(mediaType = mediaType) }
        scope.launch { loadCurrent(resetPage = true) }
    }

    fun selectExploreSource(source: String) {
        if (_state.value.exploreSource == source) return
        _state.update { it.copy(exploreSource = source, searchQuery = "") }
        scope.launch { loadCurrent(resetPage = true) }
    }

    fun setSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query) }
    }

    fun submitSearch() {
        scope.launch { loadCurrent(resetPage = true) }
    }

    fun selectCalendarKind(kind: String) {
        _state.update { current ->
            val dates =
                current.items
                    .filter {
                        kind == "all" || it.calendarKind == kind
                    }.map { it.calendarDate }
                    .filter(String::isNotBlank)
            current.copy(calendarKind = kind, selectedDate = dates.firstOrNull().orEmpty())
        }
    }

    fun selectDate(date: String) {
        _state.update { it.copy(selectedDate = date) }
    }

    fun previousPage() {
        if (_state.value.page <= 1) return
        _state.update { it.copy(page = it.page - 1) }
        scope.launch { loadCurrent(resetPage = false) }
    }

    fun nextPage() {
        if (!_state.value.hasNextPage) return
        _state.update { it.copy(page = it.page + 1) }
        scope.launch { loadCurrent(resetPage = false) }
    }

    fun refresh() {
        cachedSettings = null
        scope.launch { loadCurrent(resetPage = false) }
    }

    fun openItem(item: TgtoMediaItem) = onOpenItem(item)

    private suspend fun loadCurrent(resetPage: Boolean) {
        if (!preferences.connection.value.hasPassword) {
            _state.update { it.copy(configured = false, loading = false, items = emptyList()) }
            return
        }
        val version = ++requestVersion
        if (resetPage) _state.update { it.copy(page = 1) }
        val snapshot = _state.value
        _state.update { it.copy(loading = true, error = null, notice = null, embyStatuses = emptyMap()) }
        val result =
            when (snapshot.section) {
                MediaHubSection.Rankings ->
                    media.rankings(
                        snapshot.rankingProvider,
                        snapshot.mediaType,
                        page = if (resetPage) 1 else snapshot.page,
                    )
                MediaHubSection.Explore ->
                    if (snapshot.exploreSource == "anilist" || snapshot.exploreSource == "bangumi") {
                        media.anime(snapshot.exploreSource, snapshot.searchQuery, if (resetPage) 1 else snapshot.page)
                    } else if (snapshot.searchQuery.isBlank()) {
                        media.discover(
                            snapshot.exploreSource,
                            if (snapshot.mediaType == "all") "movie" else snapshot.mediaType,
                            if (resetPage) 1 else snapshot.page,
                        )
                    } else {
                        media.search(
                            snapshot.searchQuery,
                            if (snapshot.mediaType == "all") "movie" else snapshot.mediaType,
                            if (resetPage) 1 else snapshot.page,
                        )
                    }
                MediaHubSection.Calendar -> media.calendar()
            }
        if (version != requestVersion) return
        _state.update { current ->
            result.fold(
                onSuccess = { page -> current.withPage(page, resetPage) },
                onFailure = { current.copy(loading = false, error = it.userMessage(), items = emptyList()) },
            )
        }
        loadSettings().onSuccess { settings ->
            if (version == requestVersion) {
                updateSettings(settings)
                loadEmbyStatuses(version)
            }
        }
    }

    private fun MediaHubState.withPage(
        pageData: TgtoMediaPage,
        resetPage: Boolean,
    ): MediaHubState {
        val activePage = if (resetPage) 1 else page
        val firstDate =
            pageData.items
                .filter { calendarKind == "all" || it.calendarKind == calendarKind }
                .map { it.calendarDate }
                .firstOrNull(String::isNotBlank)
                .orEmpty()
        return copy(
            items = pageData.items,
            loading = false,
            page = pageData.page.takeIf { it > 0 } ?: activePage,
            hasNextPage = pageData.hasNextPage || pageData.totalPages > activePage,
            stale = pageData.isStale,
            notice = pageData.configurationMessage.takeIf(String::isNotBlank),
            selectedDate = if (section == MediaHubSection.Calendar) firstDate else selectedDate,
            timezone = pageData.timezone,
            embyStatuses = emptyMap(),
        )
    }

    private suspend fun loadSettings(): Result<TgtoSettings> {
        cachedSettings?.let { return Result.success(it) }
        return media.settings().onSuccess { settings ->
            cachedSettings = settings
            updateSettings(settings)
        }
    }

    private suspend fun loadEmbyStatuses(version: Int) {
        val snapshot = _state.value
        if (!snapshot.embyConfigured || snapshot.items.isEmpty()) return
        media.embyCards(snapshot.items).onSuccess { result ->
            if (version != requestVersion) return@onSuccess
            _state.update {
                it.copy(
                    embyConfigured = result.configured,
                    embyStatuses = result.items.associate { entry -> entry.key to entry.result },
                )
            }
        }
    }

    private fun updateSettings(settings: TgtoSettings) {
        _state.update {
            it.copy(
                embyConfigured = settings.mediaEmby.configured && settings.mediaEmby.enabled,
            )
        }
    }
}

private fun Throwable.userMessage(): String = message?.takeIf(String::isNotBlank) ?: "请求失败，请稍后重试"
