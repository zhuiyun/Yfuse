package com.yfuse.di

import com.yfuse.core.data.AiringCalendarRepository
import com.yfuse.core.data.CalendarFollowStore
import com.yfuse.core.data.CalendarIdentityResolver
import com.yfuse.core.data.CalendarLocalStore
import com.yfuse.core.data.OfficialAiringScheduleCatalog
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * 追剧日历: the airing schedules the account server publishes, the series followed, and
 * [calendarLocalStore], the platform's local copy of the calendar.
 */
internal fun calendarModule(calendarLocalStore: CalendarLocalStore): Module =
    module {
        single<CalendarLocalStore> { calendarLocalStore }
        single { CalendarFollowStore(get(), personal = get()) }
        single { OfficialAiringScheduleCatalog(get(named("account-http")), get()) }
        single { CalendarIdentityResolver(get(), get()) }
        single {
            AiringCalendarRepository(
                emby = get(),
                registry = get(),
                officialSchedules = get(),
                identityResolver = get(),
                followStore = get(),
                localStore = get(),
                // Skips servers the monitor has marked as needing re-login or backing off.
                serverHealth = get(),
            )
        }
    }
