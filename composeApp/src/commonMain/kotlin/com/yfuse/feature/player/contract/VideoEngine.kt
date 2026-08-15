package com.yfuse.feature.player

import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

/** A selectable audio/subtitle/video track exposed by an engine. */
data class EngineTrack(
    val id: String,
    val label: String,
    val language: String?,
    val selected: Boolean,
    val codec: String? = null,
) {
    val requiresStyledRenderer: Boolean
        get() =
            codec?.substringAfterLast('/')?.lowercase() in
                setOf("ass", "ssa", "pgs", "pgssub", "dvdsub", "dvbsub")

    companion object {
        /** Passed to [VideoEngine.selectSubtitleTrack] to turn subtitles off. */
        const val OFF = "off"
    }
}

data class PlaybackDiagnostics(
    val engine: String = "",
    val decoder: String = "等待视频轨道",
    val videoCodec: String = "未知",
    val playMethod: String = "直播放",
    /** The user's persisted intent, distinct from the method the server actually chose. */
    val requestedQuality: String = "自动",
    val videoWidth: Int = 0,
    val dynamicRange: String = "",
    val audioFormat: String = "",
    /** Decoder + rendered-frame evidence; unlike [dynamicRange], this is not source metadata. */
    val videoOutput: String = "等待首帧",
    /** The AudioTrack/mpv output format, distinguishing encoded passthrough from decoded PCM. */
    val audioOutput: String = "等待音频输出",
    /** Current display/audio-route capability snapshot, kept separate from active output. */
    val deviceOutputCapabilities: String = "未探测",
    /** YCore route selected before backend construction: direct/native/GPU/server. */
    val plannedRenderPath: String = "",
    /** Human-readable explanation when content or device facts override the preferred backend. */
    val planningReason: String? = null,
    /** Runtime first-frame, rebuffer and dropped-frame assessment for the active engine binding. */
    val playbackHealth: String = "采集中",
    /** Local device-cost estimate; server energy use is deliberately outside this label. */
    val powerProfile: String = "待规划",
    /** Battery saver and thermal pressure that may temporarily override the requested mode. */
    val resourcePressure: String = "正常",
    /** Fast metadata or bounded platform extractor used for the active route. */
    val mediaProbe: String = "服务端元数据",
    /** Privacy-safe rolling benchmark for this capability signature and device. */
    val performanceBaseline: String = "尚无完整样本",
    val startupTimeMs: Long = 0L,
    /** Why playback is not using the original direct-play path. */
    val fallbackReason: String? = null,
    val bitrateBitsPerSecond: Long = 0L,
    val frameRate: Float = 0f,
    val droppedFrames: Int = 0,
    val bufferedDurationMs: Long = 0L,
    val bufferEvents: Int = 0,
    val networkBitsPerSecond: Long = 0L,
)

enum class VideoScaleMode(
    val label: String,
) {
    Fit("适应"),
    Fill("裁剪填满"),
    Stretch("拉伸填满"),
    ;

    fun next(): VideoScaleMode = entries[(ordinal + 1) % entries.size]
}

/** Everything the glass control layer needs to render, engine-agnostic. */
data class PlaybackState(
    val playing: Boolean = false,
    val buffering: Boolean = true,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    /** Absolute media position reached by the active engine's forward buffer. */
    val bufferedPositionMs: Long = 0L,
    val speed: Float = 1f,
    /** Decoded picture height, for the "1080P" badge; 0 until the first frame. */
    val videoHeight: Int = 0,
    val currentIndex: Int = 0,
    val itemCount: Int = 1,
    val audioTracks: List<EngineTrack> = emptyList(),
    val subtitleTracks: List<EngineTrack> = emptyList(),
    val error: String? = null,
    /** True after the current item reaches its natural end. */
    val ended: Boolean = false,
    /**
     * True while the current entry is coming from the server's transcoded stream rather
     * than its original file. Lives here, rather than on one engine, because every engine
     * can end up transcoding and the control layer shows the same badge either way.
     */
    val transcoding: Boolean = false,
    /** True once nothing further is left to fall back to for the current entry. */
    val fallbacksExhausted: Boolean = false,
    /**
     * True when retrying another URL, decoder, or physical file cannot repair the failure.
     *
     * Authentication and access-policy responses apply to every playback URL on the same
     * server. Cycling through three engines and every version only repeats the rejected
     * request and can replace the useful "重新登录/检查访问策略" message with a generic
     * decoder error. Manual retry is still available and clears this flag.
     */
    val automaticFallbackBlocked: Boolean = false,
    val diagnostics: PlaybackDiagnostics = PlaybackDiagnostics(),
) {
    val hasNext: Boolean get() = currentIndex + 1 < itemCount
    val hasPrevious: Boolean get() = currentIndex > 0

    /** Milliseconds left in the current entry; 0 while the duration is unknown. */
    val remainingMs: Long get() = (durationMs - positionMs).coerceAtLeast(0L)
}

/** Converts an engine's forward-buffer duration into a safe absolute media position. */
internal fun bufferedEndPositionMs(
    positionMs: Long,
    durationMs: Long,
    bufferedDurationMs: Long,
): Long {
    val position = positionMs.coerceAtLeast(0L)
    val ahead = bufferedDurationMs.coerceAtLeast(0L)
    val end = if (ahead > Long.MAX_VALUE - position) Long.MAX_VALUE else position + ahead
    return if (durationMs > 0L) end.coerceAtMost(durationMs) else end
}

/**
 * Playback backend contract. ExoPlayer and libmpv each implement it so the
 * control layer — progress, play/pause, seek, speed, track pickers, episode
 * navigation — is written once and works with whichever engine is active.
 */
interface VideoEngine {
    val state: StateFlow<PlaybackState>

    /**
     * Whether this engine has been asked to play, even if it is not rendering yet.
     *
     * [PlaybackState.playing] deliberately describes actual playback and therefore becomes
     * false while an engine is preparing or buffering. A watch-together room needs the
     * requested state instead: buffering on the host must not be broadcast as a pause.
     * Engines that can expose that distinction override this property.
     */
    val playbackRequested: Boolean get() = state.value.playing

    fun play()

    fun pause()

    fun seekTo(positionMs: Long)

    fun setSpeed(speed: Float)

    fun selectAudioTrack(id: String)

    /** [EngineTrack.OFF] disables subtitles. */
    fun selectSubtitleTrack(id: String)

    /** True only when the backend really renders a second subtitle track. */
    val supportsSecondarySubtitleTrack: Boolean get() = false

    /** [EngineTrack.OFF] disables the secondary subtitle. Returns false when unsupported. */
    fun selectSecondarySubtitleTrack(id: String): Boolean = false

    /** Positive values delay subtitles; negative values show them earlier. */
    fun setSubtitleOffsetMs(offsetMs: Long): Boolean = offsetMs == 0L

    /** Relative subtitle text size. Engines that cannot style return false. */
    fun setSubtitleScale(scale: Float): Boolean = scale == 1f

    /** Relative subtitle luminance, primarily useful when HDR makes white captions dazzling. */
    fun setSubtitleBrightness(brightness: Float): Boolean = brightness == 1f

    /** Temporarily prevents automatic queue advance after the current entry finishes. */
    fun setPauseAtEndOfCurrentItem(enabled: Boolean) = Unit

    /** Jumps to another entry in the queue — next/previous and the episode list. */
    fun selectItem(index: Int)

    /** Current position, for handing over when switching engines. */
    fun currentPositionMs(): Long

    /** Clears a recoverable playback error and retries the current entry. */
    fun retry()

    /**
     * Reloads the current entry from the server's transcoded stream, returning false when
     * there is nothing left to fall back to — no transcode URL, or one already in use.
     *
     * Every engine implements this rather than only ExoPlayer. A file the device cannot
     * decode is the single most common way playback fails, and on the native engines it
     * used to be a dead end: no automatic retry and, because the manual 转码播放 control was
     * gated on the engine being ExoPlayer, no way to ask for one either.
     */
    fun switchToTranscode(reason: String? = null): Boolean = false

    /**
     * Adds entries to the end of the queue without disturbing what is playing.
     *
     * Returns false when this engine cannot, leaving the caller to rebuild it — which
     * restarts the current entry at its current position. A series queue is re-listed from
     * the server every couple of minutes while it plays, so a show that publishes an episode
     * mid-episode used to interrupt the episode being watched to make room for it.
     */
    fun appendItems(items: List<PlayerMediaItem>): Boolean = false

    fun release()
}

internal fun subtitleBrightnessByte(brightness: Float): Int =
    (brightness.coerceIn(MIN_SUBTITLE_BRIGHTNESS, 1f) * 255f).roundToInt()

internal fun subtitleBrightnessRgba(brightness: Float): String {
    val channel = subtitleBrightnessByte(brightness).toString(16).padStart(2, '0')
    return "0x$channel$channel${channel}ff"
}

internal fun subtitleBrightnessMpvColor(brightness: Float): String {
    val channel = subtitleBrightnessByte(brightness).toString(16).padStart(2, '0')
    return "#ff$channel$channel$channel"
}

internal const val MIN_SUBTITLE_BRIGHTNESS = 0.35f
