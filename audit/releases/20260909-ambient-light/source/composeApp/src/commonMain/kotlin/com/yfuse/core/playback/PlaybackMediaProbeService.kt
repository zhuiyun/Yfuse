package com.yfuse.core.playback

/** Where the facts in a [PlaybackMediaProbe] came from. */
enum class PlaybackProbeDepth(
    val label: String,
) {
    ServerMetadata("服务端元数据"),
    PlatformExtractor("本机深度探测"),
    NativeFfmpeg("FFmpeg 深度探测"),
}

enum class PlaybackProbeStatus(
    val label: String,
) {
    Complete("探测完成"),
    Skipped("无需探测"),
    TimedOut("探测超时"),
    Unsupported("平台不支持探测"),
    Failed("探测失败"),
}

/**
 * Transient input for a deep media inspection.
 *
 * The URI can contain an Emby access token, so this type deliberately redacts [toString] and must
 * never be persisted or attached to diagnostic logs.
 */
class PlaybackProbeRequest(
    val uri: String,
    val baseline: PlaybackMediaProbe,
    val customUserAgent: String = "",
    val timeoutMs: Long = DEFAULT_MEDIA_PROBE_TIMEOUT_MS,
) {
    override fun toString(): String =
        "PlaybackProbeRequest(container=${baseline.normalizedContainer.ifEmpty { "UNKNOWN" }}, uri=<redacted>)"
}

data class PlaybackProbeResult(
    val status: PlaybackProbeStatus,
    val probe: PlaybackMediaProbe,
    val elapsedMs: Long = 0L,
    val trackCount: Int = 0,
    /** Stable, credential-free explanation suitable for diagnostics. */
    val detail: String = status.label,
) {
    val diagnosticLabel: String
        get() =
            buildString {
                append(detail)
                if (status == PlaybackProbeStatus.Complete) {
                    append(" · ")
                    append(trackCount)
                    append(" 轨")
                    append(" · ")
                    append(elapsedMs)
                    append("ms")
                }
            }

    companion object {
        fun metadataOnly(
            probe: PlaybackMediaProbe,
            detail: String = PlaybackProbeDepth.ServerMetadata.label,
        ) = PlaybackProbeResult(
            status = PlaybackProbeStatus.Skipped,
            probe = probe,
            detail = detail,
        )
    }
}

fun interface PlaybackMediaProbeService {
    /** Performs bounded, read-only inspection and never exposes the request URI in its result. */
    suspend fun probe(request: PlaybackProbeRequest): PlaybackProbeResult
}

internal expect fun createPlaybackMediaProbeService(): PlaybackMediaProbeService

internal const val DEFAULT_MEDIA_PROBE_TIMEOUT_MS = 4_000L
