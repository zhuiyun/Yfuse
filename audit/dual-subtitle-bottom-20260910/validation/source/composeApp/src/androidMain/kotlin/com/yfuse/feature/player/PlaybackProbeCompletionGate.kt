package com.yfuse.feature.player

import com.yfuse.core.playback.PlaybackProbeResult
import com.yfuse.core.playback.PlaybackProbeStatus
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Owned by the Compose effect caller; cancelled/busy work is eligible after playback settles. */
internal class PlaybackProbeCompletionGate {
    private var completedKey: String? = null

    fun needsProbe(key: String): Boolean = completedKey != key

    suspend fun run(
        key: String,
        probe: suspend () -> PlaybackProbeResult,
    ): PlaybackProbeResult? {
        if (!needsProbe(key)) return null
        val result = probe()
        currentCoroutineContext().ensureActive()
        if (result.status != PlaybackProbeStatus.TimedOut) completedKey = key
        return result
    }
}
