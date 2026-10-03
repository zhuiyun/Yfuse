package com.yfuse.di

import com.russhwolf.settings.Settings
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.LibraryCache
import com.yfuse.core.data.MetadataEditorService
import com.yfuse.core.data.PlaybackAudioPassthrough
import com.yfuse.core.data.PlaybackPreferences
import com.yfuse.core.data.ServerActivityStore
import com.yfuse.core.data.ServerHealthMonitor
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.ServerStatsStore
import com.yfuse.core.data.SmartPlaylistStore
import com.yfuse.core.security.createSecureStore
import com.yfuse.feature.servers.EmbyQuickConnectGateway
import com.yfuse.feature.servers.QuickConnectGateway
import kotlinx.coroutines.Dispatchers
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The saved media servers and the repository that speaks to Emby, Jellyfin and Plex, with what the
 * app keeps per server: activity, stats, health, smart playlists and the last library page, which
 * goes to [feedCacheSettings], the feed caches' own store.
 */
internal fun mediaServerModule(feedCacheSettings: () -> Settings): Module =
    module {
        single {
            val persistedSettings = get<Settings>()
            ServerRegistry(
                settings = persistedSettings,
                secureStore =
                    createSecureStore(
                        settings = persistedSettings,
                        namespace = "emby.server-sessions",
                    ),
                crypto = get(),
                personal = get(),
                // Keystore encryption of session tokens stays off the UI thread that commits them.
                persistDispatcher = Dispatchers.Default,
            )
        }
        single { ServerActivityStore(get()) }
        single { ServerStatsStore(get()) }
        single {
            val playbackPreferences = get<PlaybackPreferences>()
            EmbyRepository(
                client = get(),
                capabilitiesProvider = get(),
                audioPassthroughEnabled = {
                    playbackPreferences.audioPassthrough.value ==
                        PlaybackAudioPassthrough.Compatible
                },
                progressProjection = get(),
            )
        }
        single { ServerHealthMonitor(get(), get()) }
        single<QuickConnectGateway> { EmbyQuickConnectGateway(get()) }
        single { LibraryCache(get(), storage = feedCacheSettings) }
        single { SmartPlaylistStore(get()) }
        single { MetadataEditorService(get()) }
    }
