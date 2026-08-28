package com.yfuse.feature.calendar

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import com.arkivanov.mvikotlin.extensions.coroutines.coroutineBootstrapper
import com.yfuse.core.data.AiringCalendarRepository
import com.yfuse.core.data.CalendarFollowStore
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.CalendarDay
import com.yfuse.core.model.CalendarEntry
import com.yfuse.core.model.ShowOrigin
import com.yfuse.core.network.toUserMessage
import com.yfuse.core.util.currentIsoDate
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

internal const val CALENDAR_LOAD_TIMEOUT_MS = 45_000L

internal class CalendarLoadTimeoutException : Exception("日历加载超时，请检查网络后重试")

/**
 * Bounds the whole fan-out, not just each individual TMDB or Emby request.
 *
 * A cold calendar load can issue several waves of requests. Per-request HTTP timeouts do not
 * prevent those waves from keeping the screen in its initial loading state for minutes.
 */
internal suspend fun loadCalendarWithDeadline(
    timeoutMillis: Long = CALENDAR_LOAD_TIMEOUT_MS,
    loader: suspend () -> Result<List<CalendarDay>>,
): Result<List<CalendarDay>> =
    try {
        withTimeout(timeoutMillis) { loader() }
    } catch (_: TimeoutCancellationException) {
        Result.failure(CalendarLoadTimeoutException())
    }

/**
 * Which slice of the calendar is on screen.
 *
 * 我的 is the one this screen is named after. The schedule comes from a popularity chart,
 * so out of the box the list is mostly shows the reader has never watched — useful for
 * finding something new, useless for the thing 追剧 means. Filtering to the shows the
 * library already holds turns the same data into "what's out tonight for the shows I
 * follow", which is the question that made anyone open a calendar.
 *
 * 国产 and 国外 are separated because they are watched differently — a domestic drama posts
 * daily and a foreign one weekly — and because a combined list ordered by popularity buries
 * whichever of the two the user came for.
 */
enum class CalendarSection(
    val label: String,
) {
    Schedule("日历"),
    Tracking("追剧"),
    Resources("资源"),
    Settings("设置"),
}

enum class CalendarContentFilter(
    val label: String,
) {
    All("全部内容"),
    Series("剧集"),
    Movies("电影"),
}

enum class CalendarFilter(
    val label: String,
) {
    Today("今天"),
    Upcoming("即将更新"),
    Missing("待入库"),
    Unwatched("待观看"),
    Mine("正在追"),
    All("全部"),
    Domestic("国产"),
    Foreign("国外"),
    ;

    fun accepts(entry: CalendarEntry): Boolean =
        when (this) {
            Today, All -> true
            Upcoming -> entry.status == com.yfuse.core.model.LibraryStatus.Unaired
            Missing ->
                entry.status == com.yfuse.core.model.LibraryStatus.Missing &&
                    (entry.followed || entry.inLibrary)
            Unwatched ->
                entry.inLibrary &&
                    entry.status in
                    setOf(
                        com.yfuse.core.model.LibraryStatus.Available,
                        com.yfuse.core.model.LibraryStatus.InProgress,
                    )
            Mine -> entry.followed || entry.inLibrary
            Domestic -> entry.episode.origin == ShowOrigin.Domestic
            Foreign -> entry.episode.origin == ShowOrigin.Foreign
        }
}

data class CalendarState(
    val loading: Boolean = true,
    val days: List<CalendarDay> = emptyList(),
    /** Last fully resolved server result, retained while a refresh is in flight. */
    val confirmedDays: List<CalendarDay> = emptyList(),
    val section: CalendarSection = CalendarSection.Schedule,
    val filter: CalendarFilter = CalendarFilter.All,
    val platform: String? = null,
    val contentFilter: CalendarContentFilter = CalendarContentFilter.All,
    val today: String = currentIsoDate(),
    val error: String? = null,
) {
    /** The days the current filter leaves, with days it empties dropped entirely. */
    val visibleDays: List<CalendarDay>
        get() =
            days.mapNotNull { day ->
                val dateAccepted =
                    when (filter) {
                        CalendarFilter.Today -> day.date == today
                        CalendarFilter.Upcoming -> day.date >= today
                        else -> true
                    }
                if (!dateAccepted) return@mapNotNull null
                day.entries
                    .asSequence()
                    .filter(filter::accepts)
                    .filter { entry ->
                        platform == null ||
                            entry.episode.platforms.any {
                                it.equals(platform, ignoreCase = true)
                            }
                    }.filter { entry ->
                        when (contentFilter) {
                            CalendarContentFilter.All -> true
                            CalendarContentFilter.Series -> !entry.episode.isMovie
                            CalendarContentFilter.Movies -> entry.episode.isMovie
                        }
                    }.toList()
                    .takeIf { it.isNotEmpty() }
                    ?.let { day.copy(entries = it) }
            }

    /**
     * Where today sits in [visibleDays], or the first day after it when today has no
     * broadcasts — which is most days once 我的 is on.
     *
     * The list runs oldest-first and starts a week back, so without this it opens on last
     * Tuesday. Landing on today and letting the reader scroll *up* into the past keeps
     * "what have I missed" one gesture away while answering "what's on now" immediately.
     */
    val availablePlatforms: List<String>
        get() =
            days
                .flatMap { day -> day.entries.flatMap { it.episode.platforms } }
                .map { it.trim() }
                .filter(String::isNotBlank)
                .distinct()
                .sorted()

    val todayIndex: Int
        get() =
            visibleDays.indexOfFirst { it.date >= today }.takeIf { it >= 0 }
                ?: (visibleDays.size - 1).coerceAtLeast(0)

    /** True once the schedule has arrived but the filter leaves nothing — 我的, usually. */
    val filteredToNothing: Boolean get() = days.isNotEmpty() && visibleDays.isEmpty()
}

/** A verified/cached schedule is useful immediately; library-state enrichment may finish later. */
internal fun CalendarState.withCalendarPreview(preview: List<CalendarDay>): CalendarState =
    copy(
        loading = false,
        days = mergeCalendarPreview(if (confirmedDays.isEmpty()) days else confirmedDays, preview),
        error = null,
        today = currentIsoDate(),
    )

/** A later schedule preview may add titles, but it must never downgrade resolved inventory. */
internal fun mergeCalendarPreview(
    current: List<CalendarDay>,
    incoming: List<CalendarDay>,
): List<CalendarDay> {
    if (current.isEmpty()) return incoming
    if (incoming.isEmpty()) return current
    val resolvedByMediaKey =
        current
            .flatMap(CalendarDay::entries)
            .filterNot(CalendarEntry::availabilityStale)
            .associateBy { it.episode.mediaKey }
    return (incoming + current)
        .flatMap(CalendarDay::entries)
        .distinctBy { it.episode.mediaKey }
        .map { candidate ->
            if (candidate.availabilityStale) {
                resolvedByMediaKey[candidate.episode.mediaKey] ?: candidate
            } else {
                candidate
            }
        }.groupBy { it.episode.airDate }
        .toSortedMap()
        .map { (date, entries) -> CalendarDay(date, entries) }
}

/** A background enrichment timeout must not turn an already visible schedule into an error. */
internal fun CalendarState.withCalendarLoadFailure(message: String): CalendarState {
    val retained = confirmedDays.ifEmpty { days }
    return copy(
        loading = false,
        days = retained,
        error = message.takeIf { retained.isEmpty() },
    )
}

sealed interface CalendarIntent {
    /** User-requested refresh: bypasses both schedule and library identity caches. */
    data object Refresh : CalendarIntent

    /** Lifecycle/data invalidation refresh: reuses valid caches and only reloads stale data. */
    data object Reload : CalendarIntent

    /** Applies a single-series refresh without reopening global discovery and every server. */
    data class ApplySeriesRefresh(
        val tmdbId: Int,
        val days: List<CalendarDay>,
    ) : CalendarIntent

    data class SelectSection(
        val section: CalendarSection,
    ) : CalendarIntent

    data class SelectPlatform(
        val platform: String?,
    ) : CalendarIntent

    data class SelectContent(
        val content: CalendarContentFilter,
    ) : CalendarIntent

    data class SelectFilter(
        val filter: CalendarFilter,
    ) : CalendarIntent
}

private sealed interface Action {
    data object Load : Action
}

private sealed interface Msg {
    data object Loading : Msg

    data class Loaded(
        val days: List<CalendarDay>,
    ) : Msg

    data class PreviewLoaded(
        val days: List<CalendarDay>,
    ) : Msg

    data class Failed(
        val message: String,
    ) : Msg

    data class FilterChanged(
        val filter: CalendarFilter,
    ) : Msg

    data class SectionChanged(
        val section: CalendarSection,
    ) : Msg

    data class PlatformChanged(
        val platform: String?,
    ) : Msg

    data class ContentChanged(
        val content: CalendarContentFilter,
    ) : Msg

    data class SeriesRefreshed(
        val tmdbId: Int,
        val days: List<CalendarDay>,
    ) : Msg
}

class CalendarStoreFactory(
    private val storeFactory: StoreFactory,
    private val repository: AiringCalendarRepository,
    private val preferences: CalendarFollowStore? = null,
) {
    fun create(): Store<CalendarIntent, CalendarState, Nothing> =
        storeFactory.create(
            name = "CalendarStore",
            initialState =
                CalendarState(
                    platform = preferences?.savedPlatformFilter(),
                    contentFilter =
                        CalendarContentFilter.entries.firstOrNull {
                            it.name == preferences?.savedContentFilter()
                        } ?: CalendarContentFilter.All,
                ),
            bootstrapper = coroutineBootstrapper<Action> { dispatch(Action.Load) },
            executorFactory = ::ExecutorImpl,
            reducer = ReducerImpl,
        )

    private inner class ExecutorImpl :
        CoroutineExecutor<CalendarIntent, Action, CalendarState, Msg, Nothing>() {
        private var loadJob: Job? = null

        override fun executeAction(action: Action) {
            when (action) {
                Action.Load -> load(forceRefresh = false)
            }
        }

        override fun executeIntent(intent: CalendarIntent) {
            when (intent) {
                CalendarIntent.Refresh -> load(forceRefresh = true)
                CalendarIntent.Reload -> load(forceRefresh = false)
                is CalendarIntent.ApplySeriesRefresh -> {
                    loadJob?.cancel()
                    dispatch(Msg.SeriesRefreshed(intent.tmdbId, intent.days))
                }
                is CalendarIntent.SelectSection -> dispatch(Msg.SectionChanged(intent.section))
                is CalendarIntent.SelectPlatform -> {
                    preferences?.savePlatformFilter(intent.platform)
                    dispatch(Msg.PlatformChanged(intent.platform))
                }
                is CalendarIntent.SelectContent -> {
                    preferences?.saveContentFilter(intent.content.name)
                    dispatch(Msg.ContentChanged(intent.content))
                }
                is CalendarIntent.SelectFilter -> dispatch(Msg.FilterChanged(intent.filter))
            }
        }

        private fun load(forceRefresh: Boolean) {
            loadJob?.cancel()
            dispatch(Msg.Loading)
            loadJob =
                scope.launch {
                    loadCalendarWithDeadline {
                        repository.calendar(
                            forceRefresh = forceRefresh,
                            onPreview = { preview -> dispatch(Msg.PreviewLoaded(preview)) },
                        )
                    }.onSuccess { dispatch(Msg.Loaded(it)) }
                        .onFailure {
                            AppLog.warning(
                                category = "feature.calendar",
                                event = "load_failed",
                                message = "Airing calendar failed to load",
                                throwable = it,
                            )
                            dispatch(Msg.Failed(it.toUserMessage("日历加载失败")))
                        }
                }
        }
    }

    private object ReducerImpl : Reducer<CalendarState, Msg> {
        override fun CalendarState.reduce(msg: Msg): CalendarState =
            when (msg) {
                Msg.Loading -> copy(loading = true, error = null)
                is Msg.PreviewLoaded -> withCalendarPreview(msg.days)
                is Msg.Loaded ->
                    copy(
                        loading = false,
                        days = msg.days,
                        confirmedDays = msg.days,
                        error = null,
                        // Recomputed on every load: the app can outlive midnight, and a stale
                        // "today" would mark the wrong row and misjudge what has aired.
                        today = currentIsoDate(),
                    )
                is Msg.Failed -> withCalendarLoadFailure(msg.message)
                is Msg.FilterChanged -> copy(filter = msg.filter)
                is Msg.SectionChanged -> copy(section = msg.section)
                is Msg.PlatformChanged -> copy(platform = msg.platform)
                is Msg.ContentChanged -> copy(contentFilter = msg.content)
                is Msg.SeriesRefreshed -> {
                    val merged = mergeTrackedSeriesCalendar(days, msg.days, msg.tmdbId)
                    copy(
                        loading = false,
                        days = merged,
                        confirmedDays = mergeTrackedSeriesCalendar(confirmedDays, msg.days, msg.tmdbId),
                        error = null,
                    )
                }
            }
    }
}

internal fun mergeTrackedSeriesCalendar(
    current: List<CalendarDay>,
    refreshed: List<CalendarDay>,
    tmdbId: Int,
): List<CalendarDay> =
    (
        current
            .flatMap(CalendarDay::entries)
            .filterNot { it.episode.showTmdbId == tmdbId } +
            refreshed
                .flatMap(CalendarDay::entries)
                .filter { it.episode.showTmdbId == tmdbId }
    ).groupBy { it.episode.airDate }
        .toSortedMap()
        .map { (date, entries) -> CalendarDay(date, entries) }
