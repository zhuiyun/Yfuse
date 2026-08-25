package com.yfuse.feature.calendar

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.yfuse.core.data.AiringCalendarRepository
import com.yfuse.core.data.CalendarFollowStore
import com.yfuse.core.util.componentScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

class CalendarComponent(
    componentContext: ComponentContext,
    storeFactory: StoreFactory,
    private val repository: AiringCalendarRepository,
    followStore: CalendarFollowStore,
    val onBack: () -> Unit,
    /** Opens an episode the library already has. Absent for everything else. */
    val onOpenItem: (serverId: String?, itemId: String) -> Unit,
) : ComponentContext by componentContext {
    private val scope = componentScope(lifecycle)
    val store = CalendarStoreFactory(storeFactory, repository).create()

    fun diagnosticReport(days: List<com.yfuse.core.model.CalendarDay>): String = repository.diagnosticReport(days)

    init {
        // Detail remains a separate route. When a follow or reminder is changed there,
        // refresh the still-alive calendar immediately instead of requiring a reopen.
        scope.launch {
            followStore.followed.drop(1).collect {
                store.accept(CalendarIntent.Refresh)
            }
        }
        lifecycle.doOnDestroy(store::dispose)
    }
}
