package com.yfuse.di

import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import com.russhwolf.settings.Settings
import com.yfuse.core.data.DanmakuPreferences
import com.yfuse.core.data.DanmakuRepository
import com.yfuse.core.data.AiringCalendarRepository
import com.yfuse.core.data.AiringScheduleCache
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.LibraryCache
import com.yfuse.core.data.PlaybackRecoveryStore
import com.yfuse.core.data.PlaybackTrackRequest
import com.yfuse.core.data.SearchHistory
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.SkipSegmentPreferences
import com.yfuse.core.data.ThemePreferences
import com.yfuse.core.data.UserAgentPreferences
import com.yfuse.core.data.WatchTogetherPreferences
import com.yfuse.core.data.TmdbRepository
import com.yfuse.core.network.createEmbyClient
import com.yfuse.core.network.createDanmakuClient
import com.yfuse.core.network.createTmdbClient
import com.yfuse.core.network.LanDiscovery
import com.yfuse.core.network.createLanDiscovery
import com.yfuse.core.offline.OfflineMediaManager
import com.yfuse.core.offline.createOfflineMediaManager
import com.yfuse.core.sync.ServerSyncManager
import com.yfuse.core.sync.WatchTogetherClient
import com.yfuse.feature.watch.WatchInviteResolver
import com.yfuse.core.cast.CastManager
import com.yfuse.core.cast.createCastManager
import org.koin.dsl.module

/** Root DI graph. [settings] is provided per platform (SharedPreferences on Android). */
fun appModule(settings: Settings) = module {
    single { settings }
    single { ServerRegistry(get()) }
    single { ThemePreferences(get()) }
    single { PlaybackRecoveryStore(get()) }
    single { UserAgentPreferences(get()) }
    single { WatchTogetherPreferences(get()) }
    single { DanmakuPreferences(get()) }
    single { SkipSegmentPreferences(get()) }
    single { PlaybackTrackRequest() }
    single { LibraryCache(get()) }
    single { SearchHistory(get()) }
    single<LanDiscovery> { createLanDiscovery() }
    single<CastManager> { createCastManager() }
    single<OfflineMediaManager> { createOfflineMediaManager(get()) }
    single {
        val userAgent = get<UserAgentPreferences>()
        createEmbyClient(customUserAgent = { userAgent.userAgent.value })
    }
    single { EmbyRepository(get()) }
    single { AiringScheduleCache(get()) }
    single {
        AiringCalendarRepository(
            tmdb = get(),
            emby = get(),
            registry = get(),
            scheduleCache = get(),
        )
    }
    single { DanmakuRepository(createDanmakuClient()) }
    single { ServerSyncManager(get(), get(), get()) }
    single { WatchTogetherClient(get()) }
    single { WatchInviteResolver(get(), get()) }
    // Own client (different host + bearer auth), built inline so Koin keeps a
    // single HttpClient binding.
    single { TmdbRepository(createTmdbClient()) }
    single<StoreFactory> { DefaultStoreFactory() }
}
