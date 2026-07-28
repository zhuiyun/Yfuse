package com.yfuse.di

import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import com.russhwolf.settings.Settings
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.SearchHistory
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.ThemePreferences
import com.yfuse.core.data.TmdbRepository
import com.yfuse.core.network.createEmbyClient
import com.yfuse.core.network.createTmdbClient
import com.yfuse.core.network.LanDiscovery
import com.yfuse.core.network.createLanDiscovery
import com.yfuse.core.offline.OfflineMediaManager
import com.yfuse.core.offline.createOfflineMediaManager
import com.yfuse.core.sync.ServerSyncManager
import com.yfuse.core.cast.CastManager
import com.yfuse.core.cast.createCastManager
import org.koin.dsl.module

/** Root DI graph. [settings] is provided per platform (SharedPreferences on Android). */
fun appModule(settings: Settings) = module {
    single { settings }
    single { ServerRegistry(get()) }
    single { ThemePreferences(get()) }
    single { SearchHistory(get()) }
    single<LanDiscovery> { createLanDiscovery() }
    single<CastManager> { createCastManager() }
    single<OfflineMediaManager> { createOfflineMediaManager(get()) }
    single { createEmbyClient() }
    single { EmbyRepository(get()) }
    single { ServerSyncManager(get(), get(), get()) }
    // Own client (different host + bearer auth), built inline so Koin keeps a
    // single HttpClient binding.
    single { TmdbRepository(createTmdbClient()) }
    single<StoreFactory> { DefaultStoreFactory() }
}
