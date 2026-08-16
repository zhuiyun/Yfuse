package com.yfuse.feature.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlaybackQuality
import com.yfuse.core.model.PlayerEngine
import com.yfuse.core.playback.PlaybackOptimizationMode
import kotlinx.coroutines.CoroutineScope

/** Android engine construction boundary; callers depend only on [VideoEngine]. */
@OptIn(UnstableApi::class)
internal fun createVideoEngine(
    kind: PlayerEngine,
    context: Context,
    items: List<PlayerMediaItem>,
    startIndex: Int,
    startPositionMs: Long,
    startPlaybackRequested: Boolean,
    startSpeed: Float,
    decoderMode: DecoderMode,
    optimizationMode: PlaybackOptimizationMode,
    autoNext: Boolean,
    quality: PlaybackQuality,
    customUserAgent: String,
    videoCacheBytes: Long,
    scope: CoroutineScope,
    stopEncoding: suspend (String) -> Boolean,
): VideoEngine =
    when (kind) {
        PlayerEngine.Mdk ->
            MdkVideoEngine(
                items = items,
                startIndex = startIndex,
                startPositionMs = startPositionMs,
                startPlaybackRequested = startPlaybackRequested,
                startSpeed = startSpeed,
                decoderMode = decoderMode,
                autoNext = autoNext,
                quality = quality,
                customUserAgent = customUserAgent,
                scope = scope,
                stopEncoding = stopEncoding,
            )

        PlayerEngine.Mpv ->
            MpvVideoEngine(
                context = context,
                items = items,
                startIndex = startIndex,
                startPositionMs = startPositionMs,
                startPlaybackRequested = startPlaybackRequested,
                startSpeed = startSpeed,
                decoderMode = decoderMode,
                autoNext = autoNext,
                quality = quality,
                customUserAgent = customUserAgent,
                scope = scope,
                stopEncoding = stopEncoding,
            )

        else ->
            ExoVideoEngine(
                context = context,
                items = items,
                startIndex = startIndex,
                startPositionMs = startPositionMs,
                startPlaybackRequested = startPlaybackRequested,
                startSpeed = startSpeed,
                scope = scope,
                decoderMode = decoderMode,
                optimizationMode = optimizationMode,
                autoNext = autoNext,
                quality = quality,
                customUserAgent = customUserAgent,
                videoCacheBytes = videoCacheBytes,
                stopEncoding = stopEncoding,
            )
    }
