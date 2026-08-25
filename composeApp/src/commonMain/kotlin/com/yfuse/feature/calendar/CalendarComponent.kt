package com.yfuse.feature.calendar

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.yfuse.core.data.AiringCalendarRepository

class CalendarComponent(
    componentContext: ComponentContext,
    storeFactory: StoreFactory,
    private val repository: AiringCalendarRepository,
    val onBack: () -> Unit,
    /** Opens an episode the library already has. Absent for everything else. */
    val onOpenItem: (serverId: String?, itemId: String) -> Unit,
) : ComponentContext by componentContext {
    val store = CalendarStoreFactory(storeFactory, repository).create()

    fun diagnosticReport(days: List<com.yfuse.core.model.CalendarDay>): String = repository.diagnosticReport(days)

    init {
        lifecycle.doOnDestroy(store::dispose)
    }
}
