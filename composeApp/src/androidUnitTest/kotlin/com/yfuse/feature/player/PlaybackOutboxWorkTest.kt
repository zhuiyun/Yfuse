package com.yfuse.feature.player

import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.WorkRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaybackOutboxWorkTest {
    @Test
    fun requestWaitsForConnectivityAndUsesExponentialBackoff() {
        val request = playbackOutboxFlushRequest()

        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, request.workSpec.backoffPolicy)
        assertEquals(WorkRequest.MIN_BACKOFF_MILLIS, request.workSpec.backoffDelayDuration)
        assertTrue(PLAYBACK_OUTBOX_WORK_NAME in request.tags)
    }

    @Test
    fun coalescesFrequentWakesIntoOneStableWorkChain() {
        assertEquals(ExistingWorkPolicy.KEEP, PLAYBACK_OUTBOX_WORK_POLICY)
        assertTrue(PLAYBACK_OUTBOX_WORK_NAME.startsWith("yfuse.playback.outbox.flush"))
    }
}
