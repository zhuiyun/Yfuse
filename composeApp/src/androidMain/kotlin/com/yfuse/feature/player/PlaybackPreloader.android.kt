package com.yfuse.feature.player

import android.content.Context
import android.os.PowerManager
import com.yfuse.core.data.PlaybackNetworkClass
import com.yfuse.core.data.PlaybackPreferences
import com.yfuse.core.data.SourcePreheatMode
import com.yfuse.core.data.UserAgentPreferences
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.PlayerEngine
import com.yfuse.core.network.currentPlaybackNetworkClass
import com.yfuse.core2.android.currentItemNetworkClassAllowed
import com.yfuse.core2.android.dataSaverEnabled

/** Prepares only the source reader actually consumed by YCore; SenPlayer is never prestarted. */
internal class AndroidPlaybackSourcePreloader(
    context: Context,
    private val playbackPreferences: PlaybackPreferences,
    private val userAgentPreferences: UserAgentPreferences,
) : PlaybackSourcePreloader {
    private val applicationContext = context.applicationContext

    override fun preload(item: PlayerMediaItem): PlaybackSourcePreload = preload(item, 0L)

    override fun preload(
        item: PlayerMediaItem,
        startPositionMs: Long,
    ): PlaybackSourcePreload = preload(item, startPositionMs, null)

    override fun preload(
        item: PlayerMediaItem,
        startPositionMs: Long,
        tracks: com.yfuse.core.data.PlaybackTrackRequest.Tracks?,
    ): PlaybackSourcePreload = preload(item, startPositionMs, tracks, PREPARED_SOURCE_MIN_HOLD_MS)

    override fun preloadNext(
        item: PlayerMediaItem,
        holdMs: Long,
    ): PlaybackSourcePreload = preload(item, 0L, null, holdMs)

    private fun preload(
        item: PlayerMediaItem,
        startPositionMs: Long,
        tracks: com.yfuse.core.data.PlaybackTrackRequest.Tracks?,
        holdMs: Long,
    ): PlaybackSourcePreload {
        if (playbackPreferences.videoCacheSize.value.bytes <= 0L) {
            logSourcePreloadSkipped("cache_disabled")
            return noOpPlaybackSourcePreload()
        }
        val preheatMode = playbackPreferences.sourcePreheat.value
        if (preheatMode == SourcePreheatMode.Off) {
            logSourcePreloadSkipped("setting_off")
            return noOpPlaybackSourcePreload()
        }
        val networkClass = currentPlaybackNetworkClass()
        val powerSaveMode =
            (
                applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            )?.isPowerSaveMode == true
        val selected = playbackPreferences.engineSelection.value.lockedEngine ?: PlayerEngine.YCore
        if (!shouldPrepareYCoreSource(
                selected,
                networkClass,
                preheatMode,
                dataSaverEnabled(applicationContext),
                powerSaveMode,
            )
        ) {
            logSourcePreloadSkipped("engine_network_or_power_policy")
            return noOpPlaybackSourcePreload()
        }
        return com.yfuse.core2.android.AndroidCurrentItemPreparation.preload(
            applicationContext,
            item,
            startPositionMs,
            userAgentPreferences.userAgent.value,
            playbackPreferences.videoCacheSize.value.bytes,
            mode = preheatMode,
            initialTrackSelection = item.initialPlaybackTracks(playbackPreferences, tracks),
            holdMs = holdMs,
        )
    }
}

private fun logSourcePreloadSkipped(reason: String) {
    AppLog.info(
        category = "feature.player",
        event = "source_preload_skipped",
        message = "Optional source preload was skipped",
        attributes = mapOf("reason" to reason),
    )
}

internal fun shouldPrepareYCoreSource(
    engine: PlayerEngine,
    networkClass: PlaybackNetworkClass,
    mode: SourcePreheatMode,
    dataSaver: Boolean,
    powerSaveMode: Boolean,
): Boolean =
    engine == PlayerEngine.YCore &&
        !powerSaveMode &&
        currentItemNetworkClassAllowed(networkClass, dataSaver, mode)
