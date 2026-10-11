package com.yfuse.di

import com.yfuse.core.data.AiringCalendarRepository
import com.yfuse.core.data.CalendarFollowStore
import com.yfuse.core.data.CalendarIdentityResolver
import com.yfuse.core.data.CalendarLocalStore
import com.yfuse.core.data.OfficialAiringScheduleCatalog
import com.yfuse.core.data.SeriesAiringScheduleFallback
import com.yfuse.core.data.TmdbRepository
import com.yfuse.core.data.TmdbSeriesIdentityCandidate
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * 追剧日历: the airing schedules the account server publishes, the series followed, and
 * [calendarLocalStore], the platform's local copy of the calendar.
 */
internal fun calendarModule(calendarLocalStore: CalendarLocalStore): Module =
    module {
        single<CalendarLocalStore> { calendarLocalStore }
        single { CalendarFollowStore(get(), personal = get()) }
        single { OfficialAiringScheduleCatalog(get<com.yfuse.backend.CalendarBackendApi>(), get()) }
        single {
            val tmdb = get<TmdbRepository>()
            CalendarIdentityResolver(get(), get(), search = { title, year ->
                tmdb.searchTitles(title, mediaType = "tv", year = year).map { hits ->
                    hits.map { hit ->
                        TmdbSeriesIdentityCandidate(hit.id, hit.title, hit.year, hit.posterPath, hit.popularity)
                    }
                }
            })
        }
        single {
            val tmdb = get<TmdbRepository>()
            SeriesAiringScheduleFallback(fetch = { id, title -> tmdb.seriesAiringCalendar(id, title) })
        }
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
                supplementalSchedules = get(),
            )
        }
    }
