package com.yfuse.feature.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlaybackQuality
import com.yfuse.core.model.PlayerEngine
import kotlinx.coroutines.CoroutineScope

/** Android engine construction boundary; callers depend only on [VideoEngine]. */
@OptIn(UnstableApi::class)
internal fun createVideoEngine(
    kind: PlayerEngine,
    context: Context,
    items: List<PlayerMediaItem>,
    startIndex: Int,
    startPositionMs: Long,
    decoderMode: DecoderMode,
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
                scope = scope,
                decoderMode = decoderMode,
                autoNext = autoNext,
                quality = quality,
                customUserAgent = customUserAgent,
                videoCacheBytes = videoCacheBytes,
                stopEncoding = stopEncoding,
            )
    }
