package com.yfuse.core.playback

/** Lower cost immediately; require sustained relief before rebuilding a higher-cost pipeline. */
internal class PlaybackOptimizationRecovery(
    private val recoveryWindowMs: Long = 30_000L,
) {
    private var applied: ResolvedPlaybackOptimization? = null
    private var recoveryStartedMs: Long? = null

    init {
        require(recoveryWindowMs >= 0L)
    }

    fun update(
        target: ResolvedPlaybackOptimization,
        nowMs: Long,
    ): ResolvedPlaybackOptimization {
        val previous = applied
        if (target.mode == PlaybackOptimizationMode.PowerSaver ||
            previous?.mode != PlaybackOptimizationMode.PowerSaver ||
            previous.reason == null
        ) {
            recoveryStartedMs = null
            applied = target
            return target
        }
        val started = recoveryStartedMs?.takeIf { it <= nowMs } ?: nowMs.also { recoveryStartedMs = it }
        if (nowMs - started >= recoveryWindowMs) {
            recoveryStartedMs = null
            applied = target
            return target
        }
        return previous.copy(reason = "资源压力已缓解，稳定后恢复播放模式")
    }

    fun remainingMs(nowMs: Long): Long =
        recoveryStartedMs?.let { (recoveryWindowMs - (nowMs - it)).coerceIn(0L, recoveryWindowMs) } ?: 0L
}
