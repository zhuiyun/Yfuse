package com.yfuse.feature.calendar

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import com.arkivanov.mvikotlin.extensions.coroutines.coroutineBootstrapper
import com.yfuse.core.data.AiringCalendarRepository
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.CalendarDay
import com.yfuse.core.model.ShowOrigin
import com.yfuse.core.network.toUserMessage
import com.yfuse.core.util.currentIsoDate
import kotlinx.coroutines.launch

/**
 * Which slice of the calendar is on screen.
 *
 * 国产 and 国外 are separated because they are watched differently — a domestic drama posts
 * daily and a foreign one weekly — and because a combined list ordered by popularity buries
 * whichever of the two the user came for.
 */
enum class CalendarFilter(val label: String) {
    All("全部"),
    Domestic("国产"),
    Foreign("国外"),
    ;

    fun accepts(origin: ShowOrigin): Boolean = when (this) {
        All -> true
        Domestic -> origin == ShowOrigin.Domestic
        Foreign -> origin == ShowOrigin.Foreign
    }
}

data class CalendarState(
    val loading: Boolean = true,
    val days: List<CalendarDay> = emptyList(),
    val filter: CalendarFilter = CalendarFilter.All,
    val today: String = currentIsoDate(),
    val error: String? = null,
) {
    /** The days the current filter leaves, with days it empties dropped entirely. */
    val visibleDays: List<CalendarDay>
        get() = if (filter == CalendarFilter.All) {
            days
        } else {
            days.mapNotNull { day ->
                day.entries.filter { filter.accepts(it.episode.origin) }
                    .takeIf { it.isNotEmpty() }
                    ?.let { day.copy(entries = it) }
            }
        }
}

sealed interface CalendarIntent {
    data object Refresh : CalendarIntent
    data class SelectFilter(val filter: CalendarFilter) : CalendarIntent
}

private sealed interface Action {
    data object Load : Action
}

private sealed interface Msg {
    data object Loading : Msg
    data class Loaded(val days: List<CalendarDay>) : Msg
    data class Failed(val message: String) : Msg
    data class FilterChanged(val filter: CalendarFilter) : Msg
}

class CalendarStoreFactory(
    private val storeFactory: StoreFactory,
    private val repository: AiringCalendarRepository,
) {
    fun create(): Store<CalendarIntent, CalendarState, Nothing> =
        storeFactory.create(
            name = "CalendarStore",
            initialState = CalendarState(),
            bootstrapper = coroutineBootstrapper<Action> { dispatch(Action.Load) },
            executorFactory = ::ExecutorImpl,
            reducer = ReducerImpl,
        )

    private inner class ExecutorImpl :
        CoroutineExecutor<CalendarIntent, Action, CalendarState, Msg, Nothing>() {

        override fun executeAction(action: Action) {
            when (action) {
                Action.Load -> load()
            }
        }

        override fun executeIntent(intent: CalendarIntent) {
            when (intent) {
                CalendarIntent.Refresh -> load()
                is CalendarIntent.SelectFilter -> dispatch(Msg.FilterChanged(intent.filter))
            }
        }

        private fun load() {
            dispatch(Msg.Loading)
            scope.launch {
                repository.calendar()
                    .onSuccess { dispatch(Msg.Loaded(it)) }
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
        override fun CalendarState.reduce(msg: Msg): CalendarState = when (msg) {
            Msg.Loading -> copy(loading = true, error = null)
            is Msg.Loaded -> copy(
                loading = false,
                days = msg.days,
                error = null,
                // Recomputed on every load: the app can outlive midnight, and a stale
                // "today" would mark the wrong row and misjudge what has aired.
                today = currentIsoDate(),
            )
            is Msg.Failed -> copy(loading = false, error = msg.message)
            is Msg.FilterChanged -> copy(filter = msg.filter)
        }
    }
}
