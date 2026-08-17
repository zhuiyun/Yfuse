package com.yfuse.feature.player

/**
 * Source-aware startup watchdog. Large optical images and ProRes/MOV need substantially more time to
 * probe than a normal local MP4, while a genuinely stalled backend must still leave the fallback
 * chain. Times are deliberately data only so unit tests never depend on Android clocks/coroutines.
 */
internal data class MpvFileLoadWatchdogPolicy(
    val graceMs: Long,
    val stallMs: Long,
    val hardLimitMs: Long,
    val pollMs: Long = 1_000L,
) {
    init {
        require(graceMs > 0L)
        require(stallMs > 0L)
        require(hardLimitMs >= graceMs)
        require(pollMs > 0L)
    }
}

internal enum class MpvFileLoadWatchdogDecision {
    Ignore,
    Wait,
    StallTimeout,
    HardTimeout,
}

internal fun mpvFileLoadWatchdogPolicy(
    url: String,
    container: String?,
    discSource: Boolean,
    sourceVideoCodec: String?,
): MpvFileLoadWatchdogPolicy {
    val normalizedUrl = url.trim().lowercase()
    val normalizedContainer = container?.trim()?.uppercase().orEmpty()
    val normalizedCodec = sourceVideoCodec?.trim()?.lowercase().orEmpty()
    val optical =
        discSource ||
            normalizedUrl.startsWith(YFUSE_REMOTE_BLURAY_PREFIX) ||
            normalizedUrl.startsWith(YFUSE_BDMV_PREFIX) ||
            normalizedContainer in setOf("ISO", "BDMV", "BLURAY", "BLU-RAY", "BD")
    val heavyMov = normalizedContainer == "MOV" || normalizedCodec.startsWith("prores")
    val remote = normalizedUrl.startsWith("http://") || normalizedUrl.startsWith("https://")

    return when {
        optical ->
            MpvFileLoadWatchdogPolicy(
                graceMs = 60_000L,
                stallMs = 30_000L,
                hardLimitMs = 180_000L,
            )
        heavyMov ->
            MpvFileLoadWatchdogPolicy(
                graceMs = 45_000L,
                stallMs = 25_000L,
                hardLimitMs = 120_000L,
            )
        remote ->
            MpvFileLoadWatchdogPolicy(
                graceMs = 30_000L,
                stallMs = 15_000L,
                hardLimitMs = 90_000L,
            )
        else ->
            MpvFileLoadWatchdogPolicy(
                graceMs = 15_000L,
                stallMs = 10_000L,
                hardLimitMs = 45_000L,
            )
    }
}

/**
 * Evaluates one watchdog poll.
 *
 * [lastProgressMs] is updated by mpv load/cache activity. A heartbeat can keep a slow source alive
 * after its grace period, but never beyond [MpvFileLoadWatchdogPolicy.hardLimitMs].
 */
internal fun evaluateMpvFileLoadWatchdog(
    attempt: Long,
    activeAttempt: Long,
    released: Boolean,
    buffering: Boolean,
    startedAtMs: Long,
    lastProgressMs: Long,
    nowMs: Long,
    policy: MpvFileLoadWatchdogPolicy,
): MpvFileLoadWatchdogDecision {
    if (attempt != activeAttempt || released || !buffering) return MpvFileLoadWatchdogDecision.Ignore
    if (startedAtMs < 0L || lastProgressMs < 0L || nowMs < startedAtMs) {
        return MpvFileLoadWatchdogDecision.Wait
    }
    val elapsed = nowMs - startedAtMs
    if (elapsed >= policy.hardLimitMs) return MpvFileLoadWatchdogDecision.HardTimeout
    if (elapsed < policy.graceMs) return MpvFileLoadWatchdogDecision.Wait

    val stalledFor = (nowMs - lastProgressMs).coerceAtLeast(0L)
    return if (stalledFor >= policy.stallMs) {
        MpvFileLoadWatchdogDecision.StallTimeout
    } else {
        MpvFileLoadWatchdogDecision.Wait
    }
}
