package com.yfuse.feature.player

import com.yfuse.core.model.PlaybackQuality
import com.yfuse.core.playback.PlaybackDiscMenuCommand
import com.yfuse.core.playback.PlaybackDiscNavigationState
import com.yfuse.core.playback.PlaybackFailureKind
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

/**
 * Whether a backend is actually putting frames or samples out, as the backend itself knows it.
 *
 * YCore's silent-failure detection used to read this off the [PlaybackDiagnostics.videoOutput]
 * and [PlaybackDiagnostics.audioOutput] labels by substring, which made a decision that hands
 * playback to another backend depend on the exact wording of a string written for a human.
 * Backends that genuinely cannot answer report [Unknown] rather than being read as ready
 * because their sentence happens not to contain a particular word.
 */
enum class PlaybackOutputReadiness {
    /** The backend cannot report this, so no missing-output conclusion may be drawn from it. */
    Unknown,

    /** Expected, but nothing has come out yet. */
    Waiting,

    /** Verified output. */
    Rendering,

    /** The sink was torn down; not an error, but not output either. */
    Released,

    ;

    /** False for [Unknown], the only value that carries no evidence either way. */
    val verifiable: Boolean get() = this != Unknown
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
    /** [videoOutput]'s machine-readable half. Policy reads this; the label is for the panel. */
    val videoReadiness: PlaybackOutputReadiness = PlaybackOutputReadiness.Waiting,
    /** [audioOutput]'s machine-readable half. Policy reads this; the label is for the panel. */
    val audioReadiness: PlaybackOutputReadiness = PlaybackOutputReadiness.Waiting,
    /**
     * Dolby Vision is on screen: a frame has been rendered, in that range, on a display chain
     * that declared support. Reported by the backend that rendered it — a badge is a claim
     * made to the viewer, so it is worth as much as the evidence behind it.
     */
    val dolbyVisionOutput: Boolean = false,
    /** A Dolby object-audio bitstream is leaving the device, rather than being decoded to PCM. */
    val dolbyAtmosOutput: Boolean = false,
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
    /** Video presentation timestamp minus the active playback clock; null when unavailable. */
    val avSyncOffsetMs: Long? = null,
    /** Backend clock pair used for [avSyncOffsetMs], or an explicit unavailability reason. */
    val avSyncMeasurement: String = "当前内核不可测",
    val networkRecoveryAttempts: Int = 0,
    val networkRecoverySuccesses: Int = 0,
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
    /** DVD/Blu-ray title, chapter and menu state; empty for ordinary files. */
    val discNavigation: PlaybackDiscNavigationState = PlaybackDiscNavigationState(),
    val error: String? = null,
    /**
     * What kind of failure [error] describes, as the backend knew it.
     *
     * [error] is a sentence for the viewer. YCore's learning store needs the category, and it
     * used to recover one by substring-matching that sentence against a list of lowercase
     * English keywords — while the engines write Chinese. Five of the seven messages the
     * engines actually emit came back Unknown, including 网络连接多次失败, and because
     * `allowsBackendFallback` excludes only Network, Authorization and Drm, an Unknown network
     * failure both switched backend and recorded an engine-scoped penalty that blacklisted a
     * healthy decoder for a week — the opposite of what the architecture document promises.
     *
     * Null means the backend genuinely could not categorise it, and only then is the message
     * fallen back on.
     */
    val errorKind: PlaybackFailureKind? = null,
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

    /** Applies an in-manifest video ceiling without reloading. False requests a caller rebuild. */
    fun setQualityCeiling(quality: PlaybackQuality): Boolean = false

    fun selectAudioTrack(id: String)

    /** [EngineTrack.OFF] disables subtitles. */
    fun selectSubtitleTrack(id: String)

    /** Selects a native optical-disc title. Returns false when the backend cannot navigate it. */
    fun selectDiscTitle(index: Int): Boolean = false

    /** Selects a chapter inside the active title. */
    fun selectDiscChapter(index: Int): Boolean = false

    /** Sends a DVD/Blu-ray menu command to the native navigation backend. */
    fun sendDiscMenuCommand(command: PlaybackDiscMenuCommand): Boolean = false

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

    /** Re-establishes the source at a position captured before connectivity was lost. */
    fun retryFrom(positionMs: Long) {
        seekTo(positionMs.coerceAtLeast(0L))
        retry()
    }

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
