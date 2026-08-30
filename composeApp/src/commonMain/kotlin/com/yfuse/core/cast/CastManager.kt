package com.yfuse.core.cast

import kotlinx.coroutines.flow.StateFlow

data class CastDevice(
    val id: String,
    val name: String,
)

enum class CastPlaybackStatus(
    val label: String,
) {
    Idle("空闲"),
    Connecting("连接中"),
    Buffering("缓冲中"),
    Playing("播放中"),
    Paused("已暂停"),
    Ended("已结束"),
    Disconnected("已断开"),
    Error("错误"),
}

/** A nullable Boolean is too easy for UI code to accidentally render as false. */
enum class CastCapability(
    val label: String,
) {
    Supported("支持"),
    Unsupported("不支持"),
    Unknown("未知"),
}

data class CastCapabilities(
    /** True only after the Yfuse custom receiver answered for the current session revision. */
    val receiverConfirmed: Boolean = false,
    val playPause: CastCapability = CastCapability.Unknown,
    val seek: CastCapability = CastCapability.Unknown,
    val stop: CastCapability = CastCapability.Unknown,
    val volume: CastCapability = CastCapability.Unknown,
    val trackSelection: CastCapability = CastCapability.Unknown,
    val queue: CastCapability = CastCapability.Unknown,
    /** Receiver + connected display capability, never inferred from the sender device. */
    val dolbyVision: CastCapability = CastCapability.Unknown,
    /** Receiver audio passthrough capability, never inferred from source metadata. */
    val dolbyAtmos: CastCapability = CastCapability.Unknown,
    /** Result of the receiver's canDisplayType check for the requested original representation. */
    val requestedMedia: CastCapability = CastCapability.Unknown,
)

/** Source facts sent to a capable receiver before choosing original media over the safe fallback. */
data class CastMediaProfile(
    val contentType: String? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val frameRate: Double? = null,
    val dolbyVision: Boolean = false,
    val dolbyAtmos: Boolean = false,
)

data class CastQueueEntry(
    val mediaUrl: String,
    val title: String,
    val fallbackMediaUrl: String? = null,
    val mediaProfile: CastMediaProfile = CastMediaProfile(),
)

enum class CastTrackKind { Audio, Subtitle }

data class CastTrack(
    val id: Long,
    val kind: CastTrackKind,
    val label: String,
    val language: String? = null,
    val selected: Boolean = false,
)

/** Current-load output receipt emitted by the receiver after playback actually reaches PLAYING. */
data class CastOutputEvidence(
    val sessionRevision: Long = 0L,
    val receiverConfirmed: Boolean = false,
    val playbackConfirmed: Boolean = false,
    val dolbyVisionOutput: Boolean = false,
    val dolbyAtmosOutput: Boolean = false,
    val detail: String = "接收端未回执",
)

enum class CastTermination {
    UserStop,
    Unexpected,
}

data class CastState(
    val devices: List<CastDevice> = emptyList(),
    val discovering: Boolean = false,
    /** Increments for every load attempt, so a second disconnect is independently handled. */
    val sessionRevision: Long = 0L,
    val status: CastPlaybackStatus = CastPlaybackStatus.Idle,
    val activeDevice: CastDevice? = null,
    /** True only after a receiver acknowledged a usable media session. */
    val sessionConfirmed: Boolean = false,
    val positionMs: Long = 0L,
    val positionConfirmed: Boolean = false,
    val durationMs: Long = 0L,
    val queueSize: Int = 0,
    val currentQueueIndex: Int = 0,
    val tracks: List<CastTrack> = emptyList(),
    /** Receiver volume, 0f..1f; null until a receiver confirms it. */
    val volume: Float? = null,
    val capabilities: CastCapabilities = CastCapabilities(),
    val outputEvidence: CastOutputEvidence = CastOutputEvidence(),
    /** Last confirmed transport intent, retained through buffering/disconnection. */
    val lastRemoteWasPlaying: Boolean = false,
    val termination: CastTermination? = null,
    val error: String? = null,
) {
    val activeDeviceId: String? get() = activeDevice?.id

    val hasActiveSession: Boolean
        get() =
            activeDevice != null &&
                sessionConfirmed &&
                termination == null &&
                status != CastPlaybackStatus.Idle &&
                status != CastPlaybackStatus.Disconnected
}

interface CastManager {
    val state: StateFlow<CastState>

    suspend fun discover()

    /** Returns only after the receiver accepted the load and exposed a usable session. */
    suspend fun play(
        deviceId: String,
        mediaUrl: String,
        title: String,
        positionMs: Long = 0L,
        fallbackMediaUrl: String? = null,
        mediaProfile: CastMediaProfile = CastMediaProfile(),
        queue: List<CastQueueEntry> = emptyList(),
        queueIndex: Int = 0,
    ): Boolean

    suspend fun resume(): Boolean

    suspend fun pause(): Boolean

    suspend fun seekTo(positionMs: Long): Boolean

    suspend fun setVolume(volume: Float): Boolean

    suspend fun selectTrack(
        kind: CastTrackKind,
        language: String?,
        label: String,
        enabled: Boolean = true,
    ): Boolean

    suspend fun queueNext(): Boolean

    suspend fun queuePrevious(): Boolean

    /** True only when the receiver acknowledged stop (or no session remained). */
    suspend fun stop(): Boolean
}

internal fun CastState.connectingTo(
    device: CastDevice,
    positionMs: Long,
): CastState =
    copy(
        status = CastPlaybackStatus.Connecting,
        sessionRevision = sessionRevision + 1L,
        activeDevice = device,
        sessionConfirmed = false,
        positionMs = positionMs.coerceAtLeast(0L),
        positionConfirmed = false,
        durationMs = 0L,
        queueSize = 0,
        currentQueueIndex = 0,
        tracks = emptyList(),
        capabilities = CastCapabilities(),
        outputEvidence = CastOutputEvidence(sessionRevision = sessionRevision + 1L),
        lastRemoteWasPlaying = false,
        termination = null,
        error = null,
    )

internal fun CastState.remoteUpdate(
    status: CastPlaybackStatus,
    positionMs: Long? = null,
    durationMs: Long? = null,
    volume: Float? = null,
    capabilities: CastCapabilities? = null,
    queueSize: Int? = null,
    currentQueueIndex: Int? = null,
    tracks: List<CastTrack>? = null,
): CastState =
    copy(
        status = status,
        sessionConfirmed = true,
        positionMs = positionMs?.coerceAtLeast(0L) ?: this.positionMs,
        positionConfirmed = positionMs != null || positionConfirmed,
        durationMs = durationMs?.coerceAtLeast(0L) ?: this.durationMs,
        volume = volume?.coerceIn(0f, 1f) ?: this.volume,
        capabilities = capabilities ?: this.capabilities,
        queueSize = queueSize?.coerceAtLeast(0) ?: this.queueSize,
        currentQueueIndex =
            currentQueueIndex?.coerceIn(0, ((queueSize ?: this.queueSize) - 1).coerceAtLeast(0))
                ?: this.currentQueueIndex,
        tracks = tracks ?: this.tracks,
        lastRemoteWasPlaying =
            when (status) {
                CastPlaybackStatus.Playing -> true
                CastPlaybackStatus.Paused, CastPlaybackStatus.Ended -> false
                else -> lastRemoteWasPlaying
            },
        termination = null,
        error = null,
    )

internal fun CastState.commandFailed(message: String): CastState =
    copy(
        status = CastPlaybackStatus.Error,
        termination = null,
        error = message,
    )

/** Ignores delayed custom-channel messages from an older Cast load attempt. */
internal fun CastState.withReceiverCapabilities(
    revision: Long,
    dolbyVision: CastCapability,
    dolbyAtmos: CastCapability,
    requestedMedia: CastCapability,
    trackSelection: CastCapability = CastCapability.Unknown,
    queue: CastCapability = CastCapability.Unknown,
): CastState {
    if (revision != sessionRevision || termination != null) return this
    return copy(
        capabilities =
            capabilities.copy(
                receiverConfirmed = true,
                dolbyVision = dolbyVision,
                dolbyAtmos = dolbyAtmos,
                requestedMedia = requestedMedia,
                trackSelection = trackSelection,
                queue = queue,
            ),
    )
}

/** A positive Dolby badge is accepted only from PLAYING evidence for this exact load revision. */
internal fun CastState.withReceiverOutputReceipt(
    revision: Long,
    playbackConfirmed: Boolean,
    dolbyVisionOutput: Boolean,
    dolbyAtmosOutput: Boolean,
    detail: String,
): CastState {
    if (revision != sessionRevision || termination != null) return this
    return copy(
        outputEvidence =
            CastOutputEvidence(
                sessionRevision = revision,
                receiverConfirmed = true,
                playbackConfirmed = playbackConfirmed,
                dolbyVisionOutput = playbackConfirmed && dolbyVisionOutput,
                dolbyAtmosOutput = playbackConfirmed && dolbyAtmosOutput,
                detail = detail,
            ),
    )
}

internal fun CastState.unexpectedDisconnect(message: String): CastState =
    copy(
        status = CastPlaybackStatus.Disconnected,
        capabilities = CastCapabilities(),
        tracks = emptyList(),
        outputEvidence = CastOutputEvidence(sessionRevision = sessionRevision),
        termination = CastTermination.Unexpected,
        error = message,
    )

internal fun CastState.userStopped(): CastState =
    copy(
        status = CastPlaybackStatus.Idle,
        activeDevice = null,
        sessionConfirmed = false,
        capabilities = CastCapabilities(),
        queueSize = 0,
        currentQueueIndex = 0,
        tracks = emptyList(),
        outputEvidence = CastOutputEvidence(sessionRevision = sessionRevision),
        termination = CastTermination.UserStop,
        error = null,
    )

data class CastRecoveryDecision(
    val positionMs: Long,
    val resumePlayback: Boolean,
)

/** Pure policy used by PlayerActivity; an explicit Stop can never look like a disconnect. */
fun castRecoveryDecision(
    state: CastState,
    fallbackPositionMs: Long = 0L,
): CastRecoveryDecision? {
    if (state.termination != CastTermination.Unexpected) return null
    return CastRecoveryDecision(
        positionMs =
            if (state.positionConfirmed) {
                state.positionMs
            } else {
                fallbackPositionMs
            }.coerceAtLeast(0L),
        resumePlayback = state.lastRemoteWasPlaying,
    )
}

/** DLNA REL_TIME parser. Invalid/NOT_IMPLEMENTED values stay unknown instead of becoming zero. */
fun parseDlnaTimeMillis(value: String?): Long? {
    val text = value?.trim()?.takeIf { it.isNotEmpty() && it != "NOT_IMPLEMENTED" } ?: return null
    val parts = text.split(':')
    if (parts.size != 3) return null
    val hours = parts[0].toLongOrNull() ?: return null
    val minutes = parts[1].toLongOrNull()?.takeIf { it in 0..59 } ?: return null
    val seconds = parts[2].toDoubleOrNull()?.takeIf { it >= 0.0 && it < 60.0 } ?: return null
    return ((hours * 3_600L + minutes * 60L) * 1_000L + seconds * 1_000.0).toLong()
}

fun formatDlnaTime(positionMs: Long): String {
    val totalSeconds = positionMs.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = totalSeconds % 3_600L / 60L
    val seconds = totalSeconds % 60L
    return hours.toString().padStart(2, '0') + ":" +
        minutes.toString().padStart(2, '0') + ":" +
        seconds.toString().padStart(2, '0')
}

internal fun castMediaUrlError(url: String): String? =
    when {
        url.isBlank() -> "没有可用的投屏地址"
        !(url.startsWith("http://", true) || url.startsWith("https://", true)) ->
            "投屏地址必须使用 HTTP 或 HTTPS"
        url.any(Char::isWhitespace) -> "投屏地址无效"
        else -> null
    }

expect fun createCastManager(): CastManager
