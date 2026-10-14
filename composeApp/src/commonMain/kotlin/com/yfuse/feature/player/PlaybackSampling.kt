package com.yfuse.feature.player

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull

/** One consumer owns sampling; conflated events wake it without parallel JNI snapshots. */
internal suspend fun samplePlaybackUntilCancelled(
    signals: ReceiveChannel<Unit>,
    intervalMs: () -> Long,
    sample: () -> Unit,
) {
    while (currentCoroutineContext().isActive) {
        sample()
        // Coalesce native event bursts, including events produced by sample() itself.
        delay(100L)
        val received =
            withTimeoutOrNull((intervalMs() - 100L).coerceAtLeast(1L)) {
                signals.receiveCatching()
            }
        if (received?.isClosed == true) return
    }
}
