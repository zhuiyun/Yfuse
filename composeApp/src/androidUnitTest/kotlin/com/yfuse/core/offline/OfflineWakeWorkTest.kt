package com.yfuse.core.offline

import androidx.work.BackoffPolicy
import androidx.work.NetworkType
import androidx.work.WorkRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OfflineWakeWorkTest {
    @Test
    fun wifiOnlyRequestRequiresUnmeteredNetwork() {
        val request = offlineWakeRequest(wifiOnly = true, initialDelayMs = 42_000L)

        assertEquals(NetworkType.UNMETERED, request.workSpec.constraints.requiredNetworkType)
        assertEquals(42_000L, request.workSpec.initialDelay)
        assertTrue(OFFLINE_WAKE_WORK_NAME in request.tags)
    }

    @Test
    fun unrestrictedRequestStillRequiresConnectivity() {
        val request = offlineWakeRequest(wifiOnly = false, initialDelayMs = -1L)

        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertEquals(0L, request.workSpec.initialDelay)
        assertEquals(BackoffPolicy.EXPONENTIAL, request.workSpec.backoffPolicy)
        assertEquals(WorkRequest.MIN_BACKOFF_MILLIS, request.workSpec.backoffDelayDuration)
    }
}
