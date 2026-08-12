package com.yfuse.di

import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import com.russhwolf.settings.Settings
import com.yfuse.core.account.AccountApi
import com.yfuse.core.account.AccountRepository
import com.yfuse.core.account.createAccountClient
import com.yfuse.core.data.DanmakuPreferences
import com.yfuse.core.data.DanmakuRepository
import com.yfuse.core.data.DiagnosticPreferences
import com.yfuse.core.data.AiringCalendarRepository
import com.yfuse.core.data.AiringScheduleCache
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.LibraryCache
import com.yfuse.core.data.PlaybackRecoveryStore
import com.yfuse.core.data.PlaybackEventOutbox
import com.yfuse.core.data.PlaybackPreferences
import com.yfuse.core.data.PlaybackFailoverRequest
import com.yfuse.core.data.PlaybackTrackRequest
import com.yfuse.core.data.SearchHistory
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.ServerActivityStore
import com.yfuse.core.data.ServerHealthMonitor
import com.yfuse.core.data.SkipSegmentPreferences
import com.yfuse.core.data.ThemePreferences
import com.yfuse.core.data.TmdbHomeCache
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
import com.yfuse.core.security.SecureStore
import com.yfuse.core.security.VaultCrypto
import com.yfuse.core.security.createSecureStore
import com.yfuse.feature.watch.WatchInviteResolver
import com.yfuse.feature.player.PlaybackReportingCoordinator
import com.yfuse.core.cast.CastManager
import com.yfuse.core.cast.createCastManager
import org.koin.dsl.module
import kotlinx.coroutines.Dispatchers

/**
 * Root DI graph. [settings] and [appVersion] are supplied by the platform so common network
 * code reports the version embedded in the installed package rather than a duplicated constant.
 */
fun appModule(
    settings: Settings,
    appVersion: String,
    diagnosticPreferences: DiagnosticPreferences = DiagnosticPreferences(settings),
) = module {
    single { settings }
    single { diagnosticPreferences }
    single { VaultCrypto() }
    single {
        val persistedSettings = get<Settings>()
        ServerRegistry(
            settings = persistedSettings,
            secureStore = createSecureStore(
                settings = persistedSettings,
                namespace = "emby.server-sessions",
            ),
            crypto = get(),
        )
    }
    single { ThemePreferences(get()) }
    single { PlaybackPreferences(get()) }
    single { PlaybackFailoverRequest() }
    single { PlaybackRecoveryStore(get()) }
    single { PlaybackEventOutbox(get()) }
    single { ServerActivityStore(get()) }
    single { UserAgentPreferences(get()) }
    single { WatchTogetherPreferences(get()) }
    single { DanmakuPreferences(get()) }
    single { SkipSegmentPreferences(get()) }
    single { PlaybackTrackRequest() }
    single { LibraryCache(get()) }
    single { TmdbHomeCache(get()) }
    single { SearchHistory(get()) }
    single<LanDiscovery> { createLanDiscovery() }
    single<CastManager> { createCastManager() }
    single<OfflineMediaManager> { createOfflineMediaManager(get(), get()) }
    single {
        val userAgent = get<UserAgentPreferences>()
        createEmbyClient(
            customUserAgent = { userAgent.userAgent.value },
            appVersion = appVersion,
        )
    }
    single { EmbyRepository(get()) }
    single { PlaybackReportingCoordinator(get(), get(), get(), get()) }
    single { ServerHealthMonitor(get(), get()) }
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
    single<SecureStore> { createSecureStore(get(), namespace = "account") }
    single { AccountApi(createAccountClient()) }
    single {
        AccountRepository(
            api = get(),
            secureStore = get(),
            crypto = get(),
            registry = get(),
            theme = get(),
            watch = get(),
            danmaku = get(),
            skip = get(),
            serverSync = get(),
            mutationDispatcher = Dispatchers.Main.immediate,
        )
    }
    // Own client (different host + bearer auth), built inline so Koin keeps a
    // single HttpClient binding.
    single { TmdbRepository(createTmdbClient()) }
    single<StoreFactory> { DefaultStoreFactory() }
}
