package com.yfuse.di

import com.yfuse.core.data.DanmakuPreferences
import com.yfuse.core.data.DanmakuRepository
import com.yfuse.core.data.PlaybackEventOutbox
import com.yfuse.core.data.PlaybackFailoverRequest
import com.yfuse.core.data.PlaybackPreferences
import com.yfuse.core.data.PlaybackTrackRequest
import com.yfuse.core.data.SkipSegmentPreferences
import com.yfuse.core.data.UserAgentPreferences
import com.yfuse.core.offline.OfflineMediaManager
import com.yfuse.core.offline.createOfflineMediaManager
import com.yfuse.core.playback.PlaybackDeviceCapabilitiesProvider
import com.yfuse.core.playback.PlaybackMediaProbeService
import com.yfuse.core.playback.PlaybackOfflineLicenseManager
import com.yfuse.core.playback.PlaybackQoeReporter
import com.yfuse.core.playback.PlaybackRuntimeEnvironmentProvider
import com.yfuse.core.playback.createPlaybackDeviceCapabilitiesProvider
import com.yfuse.core.playback.createPlaybackMediaProbeService
import com.yfuse.core.playback.createPlaybackOfflineLicenseManager
import com.yfuse.core.playback.createPlaybackRuntimeEnvironmentProvider
import com.yfuse.core.sync.ServerSyncManager
import com.yfuse.feature.player.PlaybackReportingCoordinator
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * Playing something: the player's preferences and one-shot requests, what this device can decode,
 * the playback reports owed to each server, danmaku, offline copies with their licences, and the
 * opt-in quality reports, which carry [appVersion].
 */
internal fun playbackModule(appVersion: String): Module =
    module {
        single { PlaybackPreferences(get()) }
        single { SkipSegmentPreferences(get()) }
        single { DanmakuPreferences(get()) }
        single { PlaybackFailoverRequest() }
        single { PlaybackTrackRequest() }
        single<PlaybackDeviceCapabilitiesProvider> { createPlaybackDeviceCapabilitiesProvider() }
        single<PlaybackMediaProbeService> { createPlaybackMediaProbeService() }
        single<PlaybackRuntimeEnvironmentProvider> { createPlaybackRuntimeEnvironmentProvider() }
        single<PlaybackOfflineLicenseManager> { createPlaybackOfflineLicenseManager(get()) }
        single { PlaybackEventOutbox(get()) }
        single {
            PlaybackReportingCoordinator(
                repository = get(),
                registry = get(),
                outbox = get(),
                activity = get(),
                progressSyncEnabled = get<ServerSyncManager>().syncProgress,
            )
        }
        single {
            PlaybackQoeReporter(
                settings = get(),
                preferences = get(),
                api = get(),
                appVersion = appVersion,
            )
        }
        single { DanmakuRepository(get(named("danmaku-http"))) }
        single<OfflineMediaManager> {
            val userAgent = get<UserAgentPreferences>()
            createOfflineMediaManager(get(), get(), get()) { userAgent.userAgent.value }
        }
    }
