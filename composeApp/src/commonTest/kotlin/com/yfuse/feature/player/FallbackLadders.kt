package com.yfuse.feature.player

import com.yfuse.core.model.PlaybackMethod
import com.yfuse.core.model.PlayerEngine
import com.yfuse.core.playback.PlaybackRuntimeFaultKind

/** An engine's per-entry stream sets, reduced to the entry being played. */
internal data class StreamSets(
    val transcoded: Boolean,
    val progressive: Boolean = false,
    val pending: Boolean = false,
) {
    val rung: PlaybackStreamRung
        get() = PlaybackFallbackLadder.streamRung(transcoded, progressive, pending)

    companion object {
        val original = StreamSets(transcoded = false)
        val transcode = StreamSets(transcoded = true)
        val pendingMp4 = StreamSets(transcoded = true, pending = true)
        val mp4 = StreamSets(transcoded = true, progressive = true)
    }
}

/** The fallback decisions of the engines and PlayerRoot, as each caller asks them. */
internal interface FallbackLadders {
    val name: String

    fun exoNext(
        sets: StreamSets,
        item: PlayerMediaItem?,
        reason: String?,
    ): PlaybackStreamStep

    fun exoProgressive(
        sets: StreamSets,
        item: PlayerMediaItem?,
    ): PlaybackStreamStep

    fun exoAfterTransportFailure(
        sets: StreamSets,
        item: PlayerMediaItem?,
    ): PlaybackStreamStep

    fun mpvNext(
        sets: StreamSets,
        item: PlayerMediaItem?,
        reason: String?,
    ): PlaybackStreamStep

    fun mdkNext(
        sets: StreamSets,
        item: PlayerMediaItem?,
        reason: String?,
    ): PlaybackStreamStep

    val transientRetryLimit: Int

    fun transientRetryDelayMs(attempt: Int): Long

    fun runtimeFault(
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
    ): PlaybackRuntimeFaultStep

    fun restoresRecoveryBudget(
        positionMs: Long,
        lastRecoveryPositionMs: Long,
    ): Boolean
}

/** [PlaybackFallbackLadder], asked the way the engines and PlayerRoot ask it. */
internal object ConvergedFallbackLadders : FallbackLadders {
    override val name = "PlaybackFallbackLadder"

    override fun exoNext(
        sets: StreamSets,
        item: PlayerMediaItem?,
        reason: String?,
    ) = PlaybackFallbackLadder.nextExoStreamStep(sets.rung, item, reason)

    override fun exoProgressive(
        sets: StreamSets,
        item: PlayerMediaItem?,
    ) = PlaybackFallbackLadder.progressiveStreamStep(sets.rung, item)

    override fun exoAfterTransportFailure(
        sets: StreamSets,
        item: PlayerMediaItem?,
    ) = PlaybackFallbackLadder.exoStreamStepAfterTransportFailure(sets.rung, item)

    // mpv and MDK give up on a missing entry before they ask the ladder.
    override fun mpvNext(
        sets: StreamSets,
        item: PlayerMediaItem?,
        reason: String?,
    ) = item?.let { PlaybackFallbackLadder.nextStreamStep(sets.rung, it, reason) } ?: PlaybackStreamStep.Exhausted

    override fun mdkNext(
        sets: StreamSets,
        item: PlayerMediaItem?,
        reason: String?,
    ) = item?.let { PlaybackFallbackLadder.nextStreamStep(sets.rung, it, reason) } ?: PlaybackStreamStep.Exhausted

    override val transientRetryLimit = PlaybackFallbackLadder.TRANSIENT_RETRY_LIMIT

    override fun transientRetryDelayMs(attempt: Int) = PlaybackFallbackLadder.transientRetryDelayMs(attempt)

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
    ) = PlaybackFallbackLadder.nextRuntimeFaultStep(
        fault = fault,
        transportReopens = longBufferRecoveryAttempts,
        nativeOnly = core2NativeOnlyActive,
        nativeRestarts = nativeOnlyRecoveryAttempts,
        inCore2Trial = core2Adapter && !core2DisabledForSession,
        engineOrder = engineOrder,
        enginesTried = enginesTried + buildKind,
        serverTranscodeAvailable = hasServerTranscode && !transcoding,
    )

    override fun restoresRecoveryBudget(
        positionMs: Long,
        lastRecoveryPositionMs: Long,
    ) = PlaybackFallbackLadder.restoresRecoveryBudget(positionMs, lastRecoveryPositionMs)
}

/**
 * The implementations every table runs against. The tables were written against a transcription
 * of the engines' and PlayerRoot's own code as well, and passed on both before the callers moved.
 */
internal val fallbackLadders: List<FallbackLadders> = listOf(ConvergedFallbackLadders)

/** Runs [check] against each implementation, naming the one that disagrees. */
internal fun forEachFallbackLadder(check: (FallbackLadders) -> Unit) {
    for (ladder in fallbackLadders) {
        try {
            check(ladder)
        } catch (failure: AssertionError) {
            throw AssertionError("${ladder.name}: ${failure.message}", failure)
        }
    }
}

/** A queue entry with one version, its stream URLs and its server approval spelled out. */
internal fun ladderItem(
    hls: String = "hls",
    mp4: String = "mp4",
    approved: Boolean = true,
    playMethod: PlaybackMethod = PlaybackMethod.DirectPlay,
    dolbyVision: Boolean = false,
    dolbyAtmos: Boolean = false,
    disc: Boolean = false,
    versionApproved: Boolean = false,
    serverId: String? = "s1",
): PlayerMediaItem {
    val version =
        PlayerMediaVersion(
            id = "v1",
            label = "v1",
            detail = "",
            url = "direct",
            transcodeUrl = hls,
            fallbackTranscodeUrl = mp4,
            discSource = disc,
            dolbyVision = dolbyVision,
            dolbyAtmos = dolbyAtmos,
            playMethod = playMethod,
            serverTranscodeSupported = versionApproved,
        )
    return PlayerMediaItem(
        id = "item",
        url = "direct",
        transcodeUrl = hls,
        title = "第 1 集",
        fallbackTranscodeUrl = mp4,
        serverId = serverId,
        versions = listOf(version),
        versionId = version.id,
        playMethod = playMethod,
        serverTranscodeSupported = approved,
    )
}
