package com.yfuse.di

import com.russhwolf.settings.Settings
import com.yfuse.core.data.CalendarLocalStore
import com.yfuse.core.data.DiagnosticPreferences
import com.yfuse.core.data.NoOpCalendarLocalStore
import org.koin.dsl.module

/**
 * Root DI graph. [settings] and [appVersion] are supplied by the platform so common network
 * code reports the version embedded in the installed package rather than a duplicated constant.
 *
 * The bindings live in one module per feature area, the `*Module.kt` files beside this one, and
 * each is handed only the platform parameters it uses. No type and qualifier is bound by two of
 * them (AppModuleGraphTest loads them with overrides forbidden), so their order here decides nothing.
 */
fun appModule(
    settings: Settings,
    appVersion: String,
    diagnosticPreferences: DiagnosticPreferences = DiagnosticPreferences(settings),
    calendarLocalStore: CalendarLocalStore = NoOpCalendarLocalStore,
    feedCacheSettings: () -> Settings = { settings },
) = module {
    includes(
        coreModule(settings, diagnosticPreferences),
        networkModule(appVersion),
        mediaServerModule(feedCacheSettings),
        playbackModule(appVersion),
        accountModule(),
        fileSourceModule(),
        discoveryModule(feedCacheSettings),
        calendarModule(calendarLocalStore),
        watchTogetherModule(),
        handoffModule(),
        uiModule(),
    )
}
