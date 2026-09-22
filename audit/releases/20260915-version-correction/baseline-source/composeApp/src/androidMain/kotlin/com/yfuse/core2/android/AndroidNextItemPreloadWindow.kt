package com.yfuse.core2.android

import com.yfuse.core2.api.YPlaybackPhase
import com.yfuse.core2.api.YPlayerState
import kotlinx.coroutines.delay

/**
 * Optional metadata traffic must never win over the current episode. There is deliberately no
 * timeout that grants permission on a slow link: the normal next-item open remains available.
 * A null state means the owning child was replaced or released, not permission to preload.
 */
internal suspend fun awaitCore2NextItemPreloadWindow(currentState: () -> YPlayerState?): Boolean {
    delay(NEXT_ITEM_PRELOAD_DELAY_MS)
    var elapsedMs = 0L
    var healthySinceMs: Long? = null
    while (true) {
        AndroidPlaybackMemoryBudget.refreshPressure()
        val state = currentState() ?: return false
        if (state.phase == YPlaybackPhase.Ended || state.phase == YPlaybackPhase.Failed) return false
        val bufferedAheadMs = (state.bufferedPositionMs - state.positionMs).coerceAtLeast(0L)
        val healthy =
            AndroidPlaybackMemoryBudget.allowsSpeculativeWork &&
                state.phase == YPlaybackPhase.Ready &&
                state.playing &&
                !state.buffering &&
                state.speed.isFinite() &&
                state.speed > 0f &&
                bufferedAheadMs.toDouble() / state.speed >= NEXT_ITEM_PRELOAD_MIN_BUFFER_AHEAD_MS
        if (healthy) {
            val sinceMs = healthySinceMs ?: elapsedMs.also { healthySinceMs = it }
            if (elapsedMs - sinceMs >= NEXT_ITEM_PRELOAD_STABLE_MS) return true
        } else {
            healthySinceMs = null
        }
        delay(NEXT_ITEM_PRELOAD_POLL_MS)
        elapsedMs += NEXT_ITEM_PRELOAD_POLL_MS
    }
}

private const val NEXT_ITEM_PRELOAD_DELAY_MS = 15_000L
private const val NEXT_ITEM_PRELOAD_POLL_MS = 1_000L
private const val NEXT_ITEM_PRELOAD_STABLE_MS = 5_000L
private const val NEXT_ITEM_PRELOAD_MIN_BUFFER_AHEAD_MS = 15_000L
