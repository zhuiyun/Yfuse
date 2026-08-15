package com.yfuse.core.playback

import com.yfuse.core.model.PlayerEngine

/** Error domains drive recovery; only backend-local failures may blacklist a backend. */
enum class PlaybackFailureKind {
    Authorization,
    Network,
    Container,
    Decoder,
    Renderer,
    AudioSink,
    Unknown,
    ;

    val penalizesEngine: Boolean
        get() = this in setOf(Container, Decoder, Renderer, AudioSink)

    /** Transport and account failures apply to every local backend for the same server URL. */
    val allowsBackendFallback: Boolean
        get() = this != Network && this != Authorization
}

fun classifyPlaybackFailure(
    message: String?,
    automaticFallbackBlocked: Boolean = false,
): PlaybackFailureKind {
    if (automaticFallbackBlocked) return PlaybackFailureKind.Authorization
    val text = message?.trim()?.lowercase().orEmpty()
    if (text.isEmpty()) return PlaybackFailureKind.Unknown
    return when {
        AUTH_FAILURES.any(text::contains) -> PlaybackFailureKind.Authorization
        NETWORK_FAILURES.any(text::contains) || HTTP_5XX.containsMatchIn(text) ->
            PlaybackFailureKind.Network
        AUDIO_FAILURES.any(text::contains) -> PlaybackFailureKind.AudioSink
        RENDER_FAILURES.any(text::contains) -> PlaybackFailureKind.Renderer
        DECODER_FAILURES.any(text::contains) -> PlaybackFailureKind.Decoder
        CONTAINER_FAILURES.any(text::contains) -> PlaybackFailureKind.Container
        else -> PlaybackFailureKind.Unknown
    }
}

/**
 * Small process-local device quirk learner.
 *
 * Keys contain only codec/capability facts. URLs, item ids and account data never enter this
 * store. A future persisted implementation can keep this API and add OS/SoC identity outside the
 * media signature.
 */
class PlaybackFailureMemory(
    private val failureThreshold: Int = 2,
    private val maxSignatures: Int = 32,
) {
    private val failures = linkedMapOf<String, MutableMap<PlayerEngine, Int>>()

    init {
        require(failureThreshold > 0)
        require(maxSignatures > 0)
    }

    fun record(
        signature: String,
        engine: PlayerEngine,
        kind: PlaybackFailureKind,
    ) {
        if (!kind.penalizesEngine || signature.isBlank()) return
        val engineFailures = failures.getOrPut(signature) { mutableMapOf() }
        engineFailures[engine] = (engineFailures[engine] ?: 0) + 1
        while (failures.size > maxSignatures) {
            failures.remove(failures.keys.first())
        }
    }

    fun recordSuccess(
        signature: String,
        engine: PlayerEngine,
    ) {
        val engineFailures = failures[signature] ?: return
        engineFailures.remove(engine)
        if (engineFailures.isEmpty()) failures.remove(signature)
    }

    fun excludedEngines(signature: String): Set<PlayerEngine> =
        failures[signature]
            .orEmpty()
            .filterValues { it >= failureThreshold }
            .keys

    internal fun failureCount(
        signature: String,
        engine: PlayerEngine,
    ): Int = failures[signature]?.get(engine) ?: 0
}

private val HTTP_5XX = Regex("(?:http|status|response)[^0-9]{0,12}5\\d\\d")

private val AUTH_FAILURES =
    listOf("401", "403", "unauthorized", "forbidden", "invalid token", "token expired")

private val NETWORK_FAILURES =
    listOf(
        "timeout",
        "timed out",
        "network",
        "connection reset",
        "connection refused",
        "unable to resolve host",
        "broken pipe",
        "socket",
    )

private val CONTAINER_FAILURES =
    listOf("extractor", "demux", "container", "unrecognized input", "invalid data found")

private val DECODER_FAILURES =
    listOf("decoder", "decode", "mediacodec", "codec failed", "hwdec", "unsupported profile")

private val RENDER_FAILURES =
    listOf("renderer", "render", "surface", "gpu", "egl", "vulkan", "first frame")

private val AUDIO_FAILURES =
    listOf("audiotrack", "audio track", "audio sink", "passthrough", "audio output")
