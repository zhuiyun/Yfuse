package com.yfuse.feature.player

import com.yfuse.core.model.PlayerEngine
import com.yfuse.core.playback.PlaybackFailureKind
import com.yfuse.core.playback.PlaybackRuntimeFaultKind

/**
 * Bounded recovery within the selected engine and approved source alternatives.
 * Neither decoder faults nor transport failures implicitly opt into another engine or its license.
 * YCore's internal decoder/renderer route recovery remains in core2.
 */
internal object PlaybackFallbackLadder {
    /** Same-request retry budget for a transient network failure. */
    const val TRANSIENT_RETRY_LIMIT = 2

    /** In-place native window rebind budget before a failure counts. */
    const val SURFACE_REBIND_LIMIT = 2

    /** Transport reopens for a source that starved for too long. */
    const val TRANSPORT_REOPEN_LIMIT = 2

    /** In-place restarts of YCore Native's local pipeline for a silent output fault. */
    const val NATIVE_RESTART_LIMIT = 2

    /** Progress past the last recovery position that earns the runtime-fault budgets back. */
    const val BUDGET_RESTORING_PROGRESS_MS = 30_000L

    /** The wait before transient retry [attempt], counted from 1: a quick first try, then a slower one. */
    fun transientRetryDelayMs(attempt: Int): Long = if (attempt == 1) 500L else 1_500L

    /**
     * The rung an entry stands on, from an engine's per-entry sets. An engine adds an entry to its
     * transcoded set whenever it adds it to the other two, so the later rung wins.
     */
    fun streamRung(
        transcoded: Boolean,
        progressive: Boolean,
        progressivePending: Boolean,
    ): PlaybackStreamRung =
        when {
            progressive -> PlaybackStreamRung.Progressive
            progressivePending -> PlaybackStreamRung.ProgressivePending
            transcoded -> PlaybackStreamRung.Transcode
            else -> PlaybackStreamRung.Original
        }

    /**
     * One step down the approved stream ladder for [item] standing on [rung]. [viewerRequested]
     * marks the viewer's own request (转码播放), the only thing that takes a local Dolby title off
     * its original file.
     */
    fun nextStreamStep(
        rung: PlaybackStreamRung,
        item: PlayerMediaItem?,
        viewerRequested: Boolean,
    ): PlaybackStreamStep =
        when (rung) {
            PlaybackStreamRung.Progressive -> PlaybackStreamStep.Exhausted
            PlaybackStreamRung.ProgressivePending -> PlaybackStreamStep.InProgress
            PlaybackStreamRung.Transcode -> item.progressiveOrExhausted()
            PlaybackStreamRung.Original ->
                when {
                    item == null || !item.allowsServerTranscodeFallback(viewerRequested) -> PlaybackStreamStep.Exhausted
                    item.transcodeUrl.isNotBlank() -> PlaybackStreamStep.Transcode
                    // No HLS stream: the MP4 is the next rung.
                    else -> item.progressiveOrExhausted()
                }
        }

    /** A direct progressive fallback still requires server and viewer policy approval. */
    fun progressiveStreamStep(
        rung: PlaybackStreamRung,
        item: PlayerMediaItem?,
    ): PlaybackStreamStep =
        when (rung) {
            PlaybackStreamRung.Progressive -> PlaybackStreamStep.Exhausted
            PlaybackStreamRung.ProgressivePending -> PlaybackStreamStep.InProgress
            PlaybackStreamRung.Transcode -> item.progressiveOrExhausted()
            PlaybackStreamRung.Original ->
                if (item == null || !item.allowsServerTranscodeFallback(viewerRequested = false)) {
                    PlaybackStreamStep.Exhausted
                } else {
                    item.progressiveOrExhausted()
                }
        }

    /** The first copy of the item on a server not yet tried; a copy without a server id is no route. */
    fun nextServerCandidate(
        candidates: List<PlayerMediaItem>,
        tried: Set<String>,
    ): PlayerMediaItem? = candidates.firstOrNull { it.serverId != null && it.serverId !in tried }

    /** Reopen starving transport, then restart YCore, then an approved server stream. */
    fun nextRuntimeFaultStep(
        fault: PlaybackRuntimeFaultKind,
        transportReopens: Int,
        nativeRestarts: Int,
        engine: PlayerEngine,
        serverTranscodeAvailable: Boolean,
    ): PlaybackRuntimeFaultStep {
        if (fault.failureKind == PlaybackFailureKind.Network && transportReopens < TRANSPORT_REOPEN_LIMIT) {
            return PlaybackRuntimeFaultStep.ReopenTransport
        }
        if (engine == PlayerEngine.YCore && nativeRestarts < NATIVE_RESTART_LIMIT) {
            return PlaybackRuntimeFaultStep.RestartNativePipeline
        }
        return when {
            serverTranscodeAvailable -> PlaybackRuntimeFaultStep.ServerTranscode
            engine == PlayerEngine.YCore -> PlaybackRuntimeFaultStep.NativeRestartsSpent
            else -> PlaybackRuntimeFaultStep.Exhausted
        }
    }

    /** Whether playback at [positionMs] has earned the runtime-fault budgets back. */
    fun restoresRecoveryBudget(
        positionMs: Long,
        lastRecoveryPositionMs: Long,
    ): Boolean =
        positionMs >= 0L &&
            lastRecoveryPositionMs >= 0L &&
            positionMs >= lastRecoveryPositionMs &&
            positionMs - lastRecoveryPositionMs >= BUDGET_RESTORING_PROGRESS_MS

    /**
     * The MP4 if the entry has one. A blank URL counts as none here and in the HLS step, as in
     * [PlayerMediaItem.allowsServerTranscodeFallback]: a step must not pick a stream that check did
     * not count.
     */
    private fun PlayerMediaItem?.progressiveOrExhausted(): PlaybackStreamStep =
        if (this == null || fallbackTranscodeUrl.isBlank()) {
            PlaybackStreamStep.Exhausted
        } else {
            PlaybackStreamStep.Progressive
        }
}

/** Which stream of an entry an engine plays, in ladder order. */
internal enum class PlaybackStreamRung {
    /** The original file, played directly or streamed. */
    Original,

    /** The server's HLS transcode. */
    Transcode,

    /** Leaving the HLS transcode: its encoder is being stopped before the MP4 request may start. */
    ProgressivePending,

    /** The server's progressive MP4 transcode, the last rung. */
    Progressive,
}

/** What one step down the stream ladder asks the engine to do. */
internal enum class PlaybackStreamStep {
    /** Reload the entry from the server's HLS transcode. */
    Transcode,

    /** Stop the HLS encoder, then reload the entry from the progressive MP4. */
    Progressive,

    /** A step onto the MP4 is already under way: nothing more to do, and it counts as moving on. */
    InProgress,

    /** Nothing left: the engine reports the failure and PlayerRoot's source ladder takes over. */
    Exhausted,
}

/** PlayerRoot's answer to a silent runtime fault; see [PlaybackFallbackLadder.nextRuntimeFaultStep]. */
internal sealed interface PlaybackRuntimeFaultStep {
    /** Reopen the transport where playback stands; the source starved, nothing failed to decode. */
    data object ReopenTransport : PlaybackRuntimeFaultStep

    /** Restart YCore Native's local pipeline in place. */
    data object RestartNativePipeline : PlaybackRuntimeFaultStep

    /** YCore has spent its restarts and no approved server stream remains. */
    data object NativeRestartsSpent : PlaybackRuntimeFaultStep

    /** Native attempts are spent: ask the approved server to transcode. */
    data object ServerTranscode : PlaybackRuntimeFaultStep

    /** Nothing left to try. */
    data object Exhausted : PlaybackRuntimeFaultStep
}

/** After the selected engine fails, try another file version and then another server. */
internal sealed interface PlaybackRecoveryStep {
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
    backendFallbackEligible: Boolean,
    /** The next untried version of the current item on its server, if it has one. */
    nextVersionId: String?,
    /** The same item on the other servers, most preferred first. */
    serverCandidates: List<PlayerMediaItem>,
    serversTried: Set<String>,
): PlaybackRecoveryStep {
    if (backendFallbackEligible) {
        nextVersionId?.let { return PlaybackRecoveryStep.Version(it) }
    }
    val server = PlaybackFallbackLadder.nextServerCandidate(serverCandidates, serversTried)
    val serverId = server?.serverId ?: return PlaybackRecoveryStep.Exhausted
    return PlaybackRecoveryStep.Server(server, serverId)
}

/** Best remaining physical file after the selected engine rejected the current version. */
internal fun PlayerMediaItem.nextFallbackVersionId(tried: Set<String>): String? =
    versions
        .sortedWith(
            compareByDescending<PlayerMediaVersion> { it.sourceWidth ?: 0 }
                .thenByDescending { it.sourceBitrateBps ?: 0 },
        ).firstOrNull { it.id !in tried }
        ?.id

/** Manual selection starts a new recovery budget; automatic recovery preserves history. */
internal fun updatedVersionAttempts(
    tried: Set<String>,
    selected: String,
    automaticRecovery: Boolean,
): Set<String> = if (automaticRecovery) tried + selected else setOf(selected)
