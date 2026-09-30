package com.yfuse.feature.player

import com.yfuse.core.model.PlayerEngine
import com.yfuse.core.playback.PlaybackFailureKind
import com.yfuse.core.playback.PlaybackRuntimeFaultKind

/**
 * What playback tries when it fails, in the order it tries it, how many times, and what it
 * remembers between attempts. The engines and PlayerRoot carry the steps out (reload a stream,
 * rebuild an engine, switch a server); this object says which step comes next, so the order and
 * the limits are written down once rather than once per engine.
 *
 * **Inside an engine**, for the entry it is playing:
 * 1. Exo repeats the same request after a transient network failure, [TRANSIENT_RETRY_LIMIT] times
 *    per stream, waiting [transientRetryDelayMs] first; mpv rebinds a lost native window in place,
 *    [SURFACE_REBIND_LIMIT] times per file. Neither changes what is played.
 * 2. The stream ladder: the original file, the server's HLS transcode, its progressive MP4
 *    ([PlaybackStreamRung]). Leaving the original file needs the server's approval and, for a
 *    local Dolby title, the viewer's own request ([PlayerMediaItem.allowsServerTranscodeFallback]),
 *    whichever way the step is taken. mpv and MDK step with [nextStreamStep]. Exo steps with
 *    [nextExoStreamStep], also after a transport failure it no longer retries, because it enters
 *    the MP4 only through [progressiveStreamStep], which it also takes directly for a malformed
 *    manifest.
 * 3. Nothing left: the engine reports `fallbacksExhausted` with a typed failure kind, which hands
 *    the failure to PlayerRoot.
 *
 * Memory: each engine keeps the rung per queue entry (its transcoded, progressive and pending
 * index sets, which follow their entries when the queue changes), so one bad episode does not
 * transcode the rest of the season. Exo counts retries per server, item and stream, and forgets
 * them once the entry is ready.
 *
 * **In PlayerRoot**, once an engine has exhausted its streams (the source ladder,
 * [nextPlaybackRecoveryStep]):
 * 4. Another engine on the same file ([nextUntriedEngine]), in the order of a plan made after the
 *    failure was recorded in [com.yfuse.core.playback.PlaybackFailureMemory], so a decoder that
 *    record has just ruled out is already gone from it.
 * 5. Another version of the item on the same server, best first
 *    ([PlayerMediaItem.nextFallbackVersionId]).
 * 6. The item on another server ([nextServerCandidate]).
 *
 * Engines and versions are tried only for a failure a backend could be behind
 * ([PlaybackFailureKind.allowsBackendFallback]); a server is tried whatever the failure. Memory:
 * the engines tried (per item, server and version), the versions tried (per item and server; a
 * viewer's own choice starts a new budget, [updatedVersionAttempts]) and the servers tried (per
 * item).
 *
 * **In PlayerRoot**, for a silent fault YCore's runtime detector found, in Auto engine selection
 * and never while a cast session owns playback (the runtime-fault ladder, [nextRuntimeFaultStep]):
 * a. reopen the transport where playback stands, for a starved source, [TRANSPORT_REOPEN_LIMIT]
 *    times;
 * b. YCore Native only: restart its local pipeline in place, [NATIVE_RESTART_LIMIT] times, and
 *    nothing after that, because Native-only never falls back to Legacy or the server;
 * c. leave the YCore 2.0 trial for the selected Legacy engine;
 * d. the next engine of the plan (step 4's rule), then the server transcode.
 *
 * Both budgets are earned back only by [BUDGET_RESTORING_PROGRESS_MS] of progress past the position
 * the last recovery reopened ([restoresRecoveryBudget]): a title that fails at a fixed position
 * plays for a moment after every reopen, and resetting on that moment looped forever.
 *
 * Separate on purpose: YCore's own route ladder
 * ([com.yfuse.core2.recovery.YPlaybackRecoveryPolicy]: Tunnel, then Direct, Enhanced and software,
 * with one same-route retry) runs inside one YCore session and changes how the same stream is
 * demuxed, decoded and drawn, under fail-closed rules for protected content that none of these
 * steps share; the core2 compatibility executor (AndroidMpvCore2FallbackFactory) only runs the
 * routes it picks. PlayerStore's pre-playback failover, which opens the item on another server when
 * its details cannot be fetched, runs before any engine exists.
 */
internal object PlaybackFallbackLadder {
    /** Same-request retries of one stream after a transient network failure (Exo). */
    const val TRANSIENT_RETRY_LIMIT = 2

    /** In-place rebinds of a lost native window per file before the failure counts (mpv). */
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
     * One step down the stream ladder for [item] standing on [rung] (mpv, MDK). [reason] is why the
     * step is asked for; a viewer's own request (用户手动…) is the only thing that takes a local
     * Dolby title off its original file.
     */
    fun nextStreamStep(
        rung: PlaybackStreamRung,
        item: PlayerMediaItem?,
        reason: String?,
    ): PlaybackStreamStep =
        when (rung) {
            PlaybackStreamRung.Progressive -> PlaybackStreamStep.Exhausted
            PlaybackStreamRung.ProgressivePending -> PlaybackStreamStep.InProgress
            PlaybackStreamRung.Transcode -> item.progressiveOrExhausted()
            PlaybackStreamRung.Original ->
                when {
                    item == null || !item.allowsServerTranscodeFallback(reason) -> PlaybackStreamStep.Exhausted
                    item.transcodeUrl.isNotEmpty() -> PlaybackStreamStep.Transcode
                    // No HLS stream: the MP4 is the next rung.
                    else -> item.progressiveOrExhausted()
                }
        }

    /**
     * Exo's step down the ladder: [nextStreamStep], except that Exo reaches the MP4 only through
     * [progressiveStreamStep]. The two differ in one case, a viewer's own transcode request for a
     * local Dolby original without an HLS stream: mpv and MDK go to the MP4, Exo refuses it and
     * keeps playing the original.
     */
    fun nextExoStreamStep(
        rung: PlaybackStreamRung,
        item: PlayerMediaItem?,
        reason: String?,
    ): PlaybackStreamStep =
        when (val step = nextStreamStep(rung, item, reason)) {
            PlaybackStreamStep.Progressive -> progressiveStreamStep(rung, item)
            else -> step
        }

    /**
     * Straight to the progressive MP4, Exo's way onto that rung: taken for a manifest no HLS retry
     * can fix, and in place of [nextStreamStep]'s own MP4 answer. Off the original file it needs the
     * server's approval like every other step: a server that refused transcoding refuses the MP4
     * too, so asking for it only delayed the failure the source ladder answers. It asks without the
     * viewer's reason, so a local Dolby original stays off the MP4 even on the viewer's request.
     */
    fun progressiveStreamStep(
        rung: PlaybackStreamRung,
        item: PlayerMediaItem?,
    ): PlaybackStreamStep =
        when (rung) {
            PlaybackStreamRung.Progressive -> PlaybackStreamStep.Exhausted
            PlaybackStreamRung.ProgressivePending -> PlaybackStreamStep.InProgress
            PlaybackStreamRung.Transcode -> item.progressiveOrExhausted()
            PlaybackStreamRung.Original ->
                if (item == null || !item.allowsServerTranscodeFallback(reason = null)) {
                    PlaybackStreamStep.Exhausted
                } else {
                    item.progressiveOrExhausted()
                }
        }

    /** The first engine of [engineOrder] not yet tried for this file. */
    fun nextUntriedEngine(
        engineOrder: List<PlayerEngine>,
        tried: Set<PlayerEngine>,
    ): PlayerEngine? = engineOrder.firstOrNull { it !in tried }

    /** The first copy of the item on a server not yet tried; a copy without a server id is no route. */
    fun nextServerCandidate(
        candidates: List<PlayerMediaItem>,
        tried: Set<String>,
    ): PlayerMediaItem? = candidates.firstOrNull { it.serverId != null && it.serverId !in tried }

    /**
     * PlayerRoot's answer to a silent runtime [fault], cheapest first. [enginesTried] includes the
     * engine that faulted; [serverTranscodeAvailable] means the item has a server stream that is
     * not already playing.
     */
    fun nextRuntimeFaultStep(
        fault: PlaybackRuntimeFaultKind,
        transportReopens: Int,
        nativeOnly: Boolean,
        nativeRestarts: Int,
        inCore2Trial: Boolean,
        engineOrder: List<PlayerEngine>,
        enginesTried: Set<PlayerEngine>,
        serverTranscodeAvailable: Boolean,
    ): PlaybackRuntimeFaultStep {
        if (fault.failureKind == PlaybackFailureKind.Network && transportReopens < TRANSPORT_REOPEN_LIMIT) {
            return PlaybackRuntimeFaultStep.ReopenTransport
        }
        if (nativeOnly) {
            return if (nativeRestarts < NATIVE_RESTART_LIMIT) {
                PlaybackRuntimeFaultStep.RestartNativePipeline
            } else {
                PlaybackRuntimeFaultStep.NativeRestartsSpent
            }
        }
        if (inCore2Trial) return PlaybackRuntimeFaultStep.LeaveCore2Trial
        nextUntriedEngine(engineOrder, enginesTried)?.let { return PlaybackRuntimeFaultStep.Engine(it) }
        return if (serverTranscodeAvailable) {
            PlaybackRuntimeFaultStep.ServerTranscode
        } else {
            PlaybackRuntimeFaultStep.Exhausted
        }
    }

    /** Whether playback at [positionMs] has earned the runtime-fault budgets back. */
    fun restoresRecoveryBudget(
        positionMs: Long,
        lastRecoveryPositionMs: Long,
    ): Boolean = positionMs >= lastRecoveryPositionMs + BUDGET_RESTORING_PROGRESS_MS

    private fun PlayerMediaItem?.progressiveOrExhausted(): PlaybackStreamStep =
        if (this == null || fallbackTranscodeUrl.isEmpty()) {
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

    /** YCore Native has spent its restarts; the fault stands, with no Legacy or server fallback. */
    data object NativeRestartsSpent : PlaybackRuntimeFaultStep

    /** Leave the YCore 2.0 trial for the selected Legacy engine. */
    data object LeaveCore2Trial : PlaybackRuntimeFaultStep

    /** The next engine of the plan. */
    data class Engine(
        val engine: PlayerEngine,
    ) : PlaybackRuntimeFaultStep

    /** No engine left: ask the server to transcode. */
    data object ServerTranscode : PlaybackRuntimeFaultStep

    /** Nothing left to try. */
    data object Exhausted : PlaybackRuntimeFaultStep
}

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
        PlaybackFallbackLadder
            .nextUntriedEngine(engineOrder, enginesTried)
            ?.let { return PlaybackRecoveryStep.Engine(it) }
        nextVersionId?.let { return PlaybackRecoveryStep.Version(it) }
    }
    val server = PlaybackFallbackLadder.nextServerCandidate(serverCandidates, serversTried)
    val serverId = server?.serverId ?: return PlaybackRecoveryStep.Exhausted
    return PlaybackRecoveryStep.Server(server, serverId)
}

/** Best remaining physical file after every engine rejected the selected version. */
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
