package com.yfuse.feature.player

import com.yfuse.core.cast.CastPlaybackStatus
import com.yfuse.core.cast.CastState

/** Projects confirmed receiver facts into the existing engine-neutral control state. */
internal fun PlaybackState.withRemoteCast(
    cast: CastState,
    playMethod: String,
): PlaybackState =
    copy(
        playing =
            when (cast.status) {
                CastPlaybackStatus.Playing -> true
                CastPlaybackStatus.Error -> cast.lastRemoteWasPlaying
                else -> false
            },
        buffering =
            cast.status == CastPlaybackStatus.Connecting ||
                cast.status == CastPlaybackStatus.Buffering,
        positionMs = cast.positionMs.takeIf { cast.positionConfirmed } ?: positionMs,
        durationMs = cast.durationMs.takeIf { it > 0L } ?: durationMs,
        bufferedPositionMs = cast.positionMs.takeIf { cast.positionConfirmed } ?: bufferedPositionMs,
        videoHeight = 0,
        // Receiver command errors are actionable in the Cast panel, but are not a fatal
        // decoder/load error for the local engine and must not trigger its full-screen retry UI.
        error = null,
        ended = cast.status == CastPlaybackStatus.Ended,
        diagnostics =
            diagnostics.copy(
                engine = listOfNotNull("远程投屏", cast.activeDevice?.name).joinToString(" · "),
                decoder = "接收端未报告",
                videoCodec = "未知",
                playMethod = playMethod,
                videoWidth = 0,
                dynamicRange = "",
                audioFormat = "",
                bitrateBitsPerSecond = 0L,
                frameRate = 0f,
                droppedFrames = 0,
                bufferedDurationMs = 0L,
                networkBitsPerSecond = 0L,
            ),
    )
