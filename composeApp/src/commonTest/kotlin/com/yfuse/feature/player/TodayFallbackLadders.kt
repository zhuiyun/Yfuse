package com.yfuse.feature.player

import com.yfuse.core.model.PlayerEngine
import com.yfuse.core.playback.PlaybackFailureKind
import com.yfuse.core.playback.PlaybackRuntimeFaultKind

/**
 * The fallback decisions as the engines and PlayerRoot make them today, transcribed branch for
 * branch so the characterization tests can run against them on the JVM (the originals live in
 * Android classes). Index-set membership becomes [StreamSets]; each function answers with the step
 * the original goes on to execute. Deleted once the callers use [PlaybackFallbackLadder].
 */
internal object TodayFallbackLadders : FallbackLadders {
    override val name = "today's engines and PlayerRoot"

    /** ExoVideoEngine.switchToTranscode. */
    override fun exoNext(
        sets: StreamSets,
        item: PlayerMediaItem?,
        reason: String?,
    ): PlaybackStreamStep {
        if (sets.transcoded) return exoProgressive(sets, item)
        if (item == null) return PlaybackStreamStep.Exhausted
        if (!item.allowsServerTranscodeFallback(reason)) return PlaybackStreamStep.Exhausted
        if (item.transcodeUrl.isEmpty()) return exoProgressive(sets, item)
        return PlaybackStreamStep.Transcode
    }

    /** ExoVideoEngine.switchToProgressiveTranscode. */
    override fun exoProgressive(
        sets: StreamSets,
        item: PlayerMediaItem?,
    ): PlaybackStreamStep {
        if (sets.progressive) return PlaybackStreamStep.Exhausted
        if (sets.pending) return PlaybackStreamStep.InProgress
        if (item == null) return PlaybackStreamStep.Exhausted
        if (item.requiresLocalDolbyPipeline && !sets.transcoded) return PlaybackStreamStep.Exhausted
        if (item.fallbackTranscodeUrl.isEmpty()) return PlaybackStreamStep.Exhausted
        return PlaybackStreamStep.Progressive
    }

    /** ExoVideoEngine.advanceFallback: switchToTranscode() || switchToProgressiveTranscode(). */
    override fun exoAfterTransportFailure(
        sets: StreamSets,
        item: PlayerMediaItem?,
    ): PlaybackStreamStep =
        exoNext(sets, item, reason = null).takeUnless { it == PlaybackStreamStep.Exhausted }
            ?: exoProgressive(sets, item)

    /** MpvVideoEngine.switchToTranscode. */
    override fun mpvNext(
        sets: StreamSets,
        item: PlayerMediaItem?,
        reason: String?,
    ): PlaybackStreamStep {
        item ?: return PlaybackStreamStep.Exhausted
        if (!sets.transcoded && !item.allowsServerTranscodeFallback(reason)) return PlaybackStreamStep.Exhausted
        val progressive =
            if (item.fallbackTranscodeUrl.isEmpty()) PlaybackStreamStep.Exhausted else PlaybackStreamStep.Progressive
        return when {
            sets.progressive -> PlaybackStreamStep.Exhausted
            sets.pending -> PlaybackStreamStep.InProgress
            sets.transcoded -> progressive
            item.transcodeUrl.isEmpty() -> progressive
            else -> PlaybackStreamStep.Transcode
        }
    }

    /** MdkVideoEngine.switchToTranscode, after its released check. */
    override fun mdkNext(
        sets: StreamSets,
        item: PlayerMediaItem?,
        reason: String?,
    ): PlaybackStreamStep {
        item ?: return PlaybackStreamStep.Exhausted
        if (!sets.transcoded && !item.allowsServerTranscodeFallback(reason)) return PlaybackStreamStep.Exhausted
        val progressive =
            when {
                sets.progressive -> return PlaybackStreamStep.Exhausted
                sets.pending -> return PlaybackStreamStep.InProgress
                sets.transcoded -> true
                item.transcodeUrl.isEmpty() -> true
                else -> false
            }
        if (progressive && item.fallbackTranscodeUrl.isEmpty()) return PlaybackStreamStep.Exhausted
        return if (progressive) PlaybackStreamStep.Progressive else PlaybackStreamStep.Transcode
    }

    /** ExoVideoEngine.scheduleRetry's budget (TRANSIENT_RETRY_LIMIT) and its waits. */
    override val transientRetryLimit = 2

    override fun transientRetryDelayMs(attempt: Int): Long = if (attempt == 1) 500L else 1_500L

    /** PlayerRuntimeFaultRecovery's effect, after its fault/Auto/cast early returns. */
    override fun runtimeFault(
        fault: PlaybackRuntimeFaultKind,
        longBufferRecoveryAttempts: Int,
        core2NativeOnlyActive: Boolean,
        nativeOnlyRecoveryAttempts: Int,
        core2Adapter: Boolean,
        core2DisabledForSession: Boolean,
        engineOrder: List<PlayerEngine>,
        enginesTried: Set<PlayerEngine>,
        buildKind: PlayerEngine,
        hasServerTranscode: Boolean,
        transcoding: Boolean,
    ): PlaybackRuntimeFaultStep {
        if (fault.failureKind == PlaybackFailureKind.Network && longBufferRecoveryAttempts < 2) {
            return PlaybackRuntimeFaultStep.ReopenTransport
        }
        if (core2NativeOnlyActive) {
            if (nativeOnlyRecoveryAttempts < 2) return PlaybackRuntimeFaultStep.RestartNativePipeline
            return PlaybackRuntimeFaultStep.NativeRestartsSpent
        }
        if (core2Adapter && !core2DisabledForSession) return PlaybackRuntimeFaultStep.LeaveCore2Trial
        val tried = enginesTried + buildKind
        val nextEngine = engineOrder.firstOrNull { it !in tried }
        return if (nextEngine != null) {
            PlaybackRuntimeFaultStep.Engine(nextEngine)
        } else if (hasServerTranscode && !transcoding) {
            PlaybackRuntimeFaultStep.ServerTranscode
        } else {
            PlaybackRuntimeFaultStep.Exhausted
        }
    }

    /** PlayerRuntimeFaultRecovery's budget reset (RECOVERY_BUDGET_RESET_PROGRESS_MS). */
    override fun restoresRecoveryBudget(
        positionMs: Long,
        lastRecoveryPositionMs: Long,
    ): Boolean = positionMs >= lastRecoveryPositionMs + 30_000L
}
