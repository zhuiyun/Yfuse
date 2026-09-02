package com.yfuse.tv

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.yfuse.app.AppDependencies
import com.yfuse.app.RootComponent
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.SearchHistory
import com.yfuse.core.data.ServerHealthMonitor
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.ThemePreferences
import com.yfuse.core.data.TmdbRepository
import com.yfuse.core.sync.ServerSyncManager
import com.yfuse.feature.player.PlaybackSourcePreloader
import org.koin.core.Koin

/** TV facade over the process Koin container; only TV-safe lifecycle operations are exposed. */
class TvApplicationGraph internal constructor(
    private val koin: Koin,
) {
    val serverRegistry: ServerRegistry
        get() = koin.get()

    fun createRootComponent(componentContext: ComponentContext): RootComponent {
        val themePreferences = koin.get<ThemePreferences>()
        return RootComponent(
            componentContext = componentContext,
            storeFactory = koin.get<StoreFactory>(),
            repo = koin.get<EmbyRepository>(),
            tmdb = koin.get<TmdbRepository>(),
            registry = koin.get<ServerRegistry>(),
            themePreferences = themePreferences,
            searchHistory = koin.get<SearchHistory>(),
            syncManager = koin.get<ServerSyncManager>(),
            dependencies =
                AppDependencies(
                    calendarRepository = koin.get(),
                    calendarIdentityResolver = koin.get(),
                    calendarFollowStore = koin.get(),
                    tmdbHomeCache = koin.get(),
                    tgtoMedia = koin.get(),
                    tgtoMediaPreferences = koin.get(),
                    offlineMediaManager = koin.get(),
                    playbackTrackRequest = koin.get(),
                    serverSyncManager = koin.get(),
                    watchTogether = koin.get(),
                    watchTogetherPreferences = koin.get(),
                    inviteResolver = koin.get(),
                    playbackSourcePreloader = runCatching { koin.get<PlaybackSourcePreloader>() }.getOrNull(),
                    playbackReportingCoordinator = koin.get(),
                    playbackPreferences = koin.get(),
                    playbackFailoverRequest = koin.get(),
                    userAgentPreferences = koin.get(),
                    danmakuPreferences = koin.get(),
                    skipSegmentPreferences = koin.get(),
                    libraryCache = koin.get(),
                    lanDiscovery = koin.get(),
                    account = koin.get(),
                    serverHealthMonitor = koin.get(),
                    serverActivity = koin.get(),
                    serverStats = koin.get(),
                    serverRegistry = koin.get(),
                ),
        )
    }

    fun setAppForeground(foreground: Boolean) {
        koin.get<ServerHealthMonitor>().setAppForeground(foreground)
        koin.get<ServerSyncManager>().setAppForeground(foreground)
    }
}
