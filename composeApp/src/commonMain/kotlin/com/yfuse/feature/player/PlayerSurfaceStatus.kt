package com.yfuse.feature.player

/**
 * What the continuity overlay says while the picture is prepared: a network recovery resuming, the
 * next episode being joined — another item than the one opened, in its first seconds — a link too
 * slow for the source, or plain preparation.
 */
internal fun playbackContinuityLine(
    live: PlaybackState,
    networkRecoveryPending: Boolean,
    startIndex: Int,
): PlaybackStatusLine =
    when {
        networkRecoveryPending -> PlaybackStatusLine("网络已恢复，正在续播")
        live.currentIndex != startIndex && live.positionMs < 3_000L -> PlaybackStatusLine("正在衔接下一集")
        else ->
            networkShortfallMessage(
                live.diagnostics.networkBitsPerSecond,
                live.diagnostics.bitrateBitsPerSecond,
            )?.let { PlaybackStatusLine("网速低于片源码率", it) } ?: PlaybackStatusLine("正在准备画面")
    }

/** What the chip over a picture that is rebuffering says, with how much is already buffered. */
internal fun playbackStatusChipLine(
    diagnostics: PlaybackDiagnostics,
    networkRecoveryPending: Boolean,
): PlaybackStatusLine {
    val bufferedSeconds =
        maxOf(
            diagnostics.bufferedDurationMs,
            diagnostics.sourceBufferedMs,
        ) / 1_000
    return when {
        networkRecoveryPending -> PlaybackStatusLine("网络已恢复，正在续播")
        networkCannotCarrySource(diagnostics.networkBitsPerSecond, diagnostics.bitrateBitsPerSecond) ->
            PlaybackStatusLine("网络速度不足", "网络速度不足 · 已缓冲 $bufferedSeconds 秒")
        else -> PlaybackStatusLine("正在重新缓冲", "正在重新缓冲 · 已缓冲 $bufferedSeconds 秒")
    }
}

/** The fitted video rectangle a transition lands in; the whole surface when the picture fills it. */
internal fun transitionAspectRatio(
    scaleMode: VideoScaleMode,
    state: PlaybackState,
): Float? =
    if (scaleMode == VideoScaleMode.Fit && state.videoHeight > 0) {
        state.diagnostics.videoWidth.toFloat() / state.videoHeight
    } else {
        null
    }
