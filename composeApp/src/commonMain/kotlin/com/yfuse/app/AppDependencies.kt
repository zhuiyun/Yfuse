package com.yfuse.app

import com.yfuse.core.account.AccountRepository
import com.yfuse.core.data.AiringCalendarRepository
import com.yfuse.core.data.CalendarFollowStore
import com.yfuse.core.data.CalendarIdentityResolver
import com.yfuse.core.data.DanmakuPreferences
import com.yfuse.core.data.LibraryCache
import com.yfuse.core.data.PlaybackFailoverRequest
import com.yfuse.core.data.PlaybackPreferences
import com.yfuse.core.data.PlaybackRecoveryStore
import com.yfuse.core.data.PlaybackTrackRequest
import com.yfuse.core.data.ServerActivityStore
import com.yfuse.core.data.ServerHealthMonitor
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.ServerStatsStore
import com.yfuse.core.data.SkipSegmentPreferences
import com.yfuse.core.data.TgtoMediaPreferences
import com.yfuse.core.data.TgtoMediaRepository
import com.yfuse.core.data.TmdbHomeCache
import com.yfuse.core.data.UserAgentPreferences
import com.yfuse.core.data.WatchTogetherPreferences
import com.yfuse.core.network.LanDiscovery
import com.yfuse.core.offline.OfflineMediaManager
import com.yfuse.core.sync.ServerSyncManager
import com.yfuse.core.sync.WatchTogetherClient
import com.yfuse.feature.player.PlaybackReportingCoordinator
import com.yfuse.feature.player.PlaybackSourcePreloader
import com.yfuse.feature.watch.WatchInviteResolver

/** Process-scoped services resolved once at the Android composition root. */
data class AppDependencies(
    val calendarRepository: AiringCalendarRepository,
    val calendarIdentityResolver: CalendarIdentityResolver,
    val calendarFollowStore: CalendarFollowStore,
    val tmdbHomeCache: TmdbHomeCache,
    val tgtoMedia: TgtoMediaRepository,
    val tgtoMediaPreferences: TgtoMediaPreferences,
    val offlineMediaManager: OfflineMediaManager,
    val playbackTrackRequest: PlaybackTrackRequest,
    val serverSyncManager: ServerSyncManager,
    val watchTogether: WatchTogetherClient,
    val watchTogetherPreferences: WatchTogetherPreferences,
    val inviteResolver: WatchInviteResolver,
    val playbackSourcePreloader: PlaybackSourcePreloader?,
    val playbackRecovery: PlaybackRecoveryStore,
    val playbackReportingCoordinator: PlaybackReportingCoordinator,
    val playbackPreferences: PlaybackPreferences,
    val playbackFailoverRequest: PlaybackFailoverRequest,
    val userAgentPreferences: UserAgentPreferences,
    val danmakuPreferences: DanmakuPreferences,
    val skipSegmentPreferences: SkipSegmentPreferences,
    val libraryCache: LibraryCache,
    val lanDiscovery: LanDiscovery,
    val account: AccountRepository,
    val serverHealthMonitor: ServerHealthMonitor,
    val serverActivity: ServerActivityStore,
    val serverStats: ServerStatsStore,
    val serverRegistry: ServerRegistry,
)
