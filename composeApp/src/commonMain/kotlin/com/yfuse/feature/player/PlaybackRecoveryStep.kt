package com.yfuse.feature.player

import com.yfuse.core.model.PlayerEngine

/**
 * What playback tries once every stream the current engine offered has failed, in the order that
 * loses the least: another engine on the same file, then another version of the item on the same
 * server, then the item on another server.
 *
 * Engines and versions are tried only for a failure the backend could plausibly be behind
 * (`PlaybackFailureKind.allowsBackendFallback`): a network failure retried on another decoder would
 * only fail again, and blame that decoder for it. A server is tried whatever the failure, since
 * another server is another route, another file and another transcoder altogether.
 *
 * The caller records the failure before asking, so [engineOrder] and [enginesTried] already reflect
 * a decoder that record has just ruled out.
 */
internal sealed interface PlaybackRecoveryStep {
    data class Engine(
        val engine: PlayerEngine,
    ) : PlaybackRecoveryStep

    data class Version(
        val versionId: String,
    ) : PlaybackRecoveryStep

    data class Server(
        val candidate: PlayerMediaItem,
        val serverId: String,
    ) : PlaybackRecoveryStep

    /** Nothing left to try; the failure stands on screen. */
    data object Exhausted : PlaybackRecoveryStep
}

internal fun nextPlaybackRecoveryStep(
    engineOrder: List<PlayerEngine>,
    enginesTried: Set<PlayerEngine>,
    backendFallbackEligible: Boolean,
    /** The next untried version of the current item on its server, if it has one. */
    nextVersionId: String?,
    /** The same item on the other servers, most preferred first. */
    serverCandidates: List<PlayerMediaItem>,
    serversTried: Set<String>,
): PlaybackRecoveryStep {
    if (backendFallbackEligible) {
        engineOrder.firstOrNull { it !in enginesTried }?.let { return PlaybackRecoveryStep.Engine(it) }
        nextVersionId?.let { return PlaybackRecoveryStep.Version(it) }
    }
    val server = serverCandidates.firstOrNull { it.serverId != null && it.serverId !in serversTried }
    val serverId = server?.serverId ?: return PlaybackRecoveryStep.Exhausted
    return PlaybackRecoveryStep.Server(server, serverId)
}
