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

/** Serializable by the data layer without leaking Settings into the playback policy package. */
data class PlaybackFailureRecord(
    val signature: String,
    val engine: PlayerEngine,
    val count: Int,
    val lastFailureEpochMs: Long,
)

/**
 * Bounded device quirk learner with a cooling period.
 *
 * Keys contain only codec/capability facts. URLs, item ids and account data never enter this
 * store. The owner may persist [snapshot] through [onChanged]; stale quirks expire automatically
 * so an OS, driver or backend upgrade gets another chance without requiring app data deletion.
 */
class PlaybackFailureMemory(
    private val failureThreshold: Int = 2,
    private val maxSignatures: Int = 32,
    private val failureTtlMs: Long = DEFAULT_FAILURE_TTL_MS,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
    initialRecords: List<PlaybackFailureRecord> = emptyList(),
    private val onChanged: ((List<PlaybackFailureRecord>) -> Unit)? = null,
) {
    private data class FailureState(
        var count: Int,
        var lastFailureEpochMs: Long,
    )

    private val failures = linkedMapOf<String, MutableMap<PlayerEngine, FailureState>>()

    init {
        require(failureThreshold > 0)
        require(maxSignatures > 0)
        require(failureTtlMs > 0L)
        initialRecords.forEach { record ->
            val signature = record.signature.normalizedSignature() ?: return@forEach
            if (
                record.engine !in PlayerEngine.selectable ||
                record.count <= 0 ||
                record.lastFailureEpochMs <= 0L
            ) {
                return@forEach
            }
            failures
                .getOrPut(signature) { mutableMapOf() }[record.engine] =
                FailureState(
                    count = record.count.coerceAtMost(MAX_FAILURE_COUNT),
                    lastFailureEpochMs = record.lastFailureEpochMs,
                )
        }
        trimToBound()
        pruneExpired(notify = false)
    }

    fun record(
        signature: String,
        engine: PlayerEngine,
        kind: PlaybackFailureKind,
    ) {
        if (!kind.penalizesEngine) return
        val key = signature.normalizedSignature() ?: return
        pruneExpired(notify = false)
        val engineFailures = failures.remove(key) ?: mutableMapOf()
        val current = engineFailures[engine]
        engineFailures[engine] =
            FailureState(
                count = ((current?.count ?: 0) + 1).coerceAtMost(MAX_FAILURE_COUNT),
                lastFailureEpochMs = nowEpochMs().coerceAtLeast(1L),
            )
        failures[key] = engineFailures
        trimToBound()
        notifyChanged()
    }

    fun recordSuccess(
        signature: String,
        engine: PlayerEngine,
    ) {
        val key = signature.normalizedSignature() ?: return
        val engineFailures = failures[key] ?: return
        engineFailures.remove(engine)
        if (engineFailures.isEmpty()) failures.remove(key)
        notifyChanged()
    }

    fun excludedEngines(signature: String): Set<PlayerEngine> {
        pruneExpired(notify = true)
        val key = signature.normalizedSignature() ?: return emptySet()
        return failures[key]
            .orEmpty()
            .filterValues { it.count >= failureThreshold }
            .keys
    }

    fun snapshot(): List<PlaybackFailureRecord> {
        pruneExpired(notify = true)
        return currentSnapshot()
    }

    internal fun failureCount(
        signature: String,
        engine: PlayerEngine,
    ): Int {
        pruneExpired(notify = true)
        val key = signature.normalizedSignature() ?: return 0
        return failures[key]?.get(engine)?.count ?: 0
    }

    private fun trimToBound() {
        while (failures.size > maxSignatures) failures.remove(failures.keys.first())
    }

    private fun pruneExpired(notify: Boolean) {
        val now = nowEpochMs().coerceAtLeast(1L)
        var changed = false
        failures.entries.removeAll { (_, engineFailures) ->
            val removed =
                engineFailures.entries.removeAll { (_, state) ->
                    val age = (now - state.lastFailureEpochMs).coerceAtLeast(0L)
                    age >= failureTtlMs
                }
            changed = changed || removed
            engineFailures.isEmpty()
        }
        if (changed && notify) notifyChanged()
    }

    private fun notifyChanged() {
        onChanged?.invoke(currentSnapshot())
    }

    private fun currentSnapshot(): List<PlaybackFailureRecord> =
        failures.flatMap { (signature, engineFailures) ->
            engineFailures.map { (engine, state) ->
                PlaybackFailureRecord(
                    signature = signature,
                    engine = engine,
                    count = state.count,
                    lastFailureEpochMs = state.lastFailureEpochMs,
                )
            }
        }
}

private fun String.normalizedSignature(): String? = trim().take(MAX_FAILURE_SIGNATURE_CHARS).takeIf(String::isNotEmpty)

internal const val DEFAULT_FAILURE_TTL_MS = 7L * 24L * 60L * 60L * 1_000L
private const val MAX_FAILURE_COUNT = 100
private const val MAX_FAILURE_SIGNATURE_CHARS = 256

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
