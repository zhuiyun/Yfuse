package com.yfuse.tv.integration

import androidx.work.BackoffPolicy
import androidx.work.WorkRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TvContinueWatchingWorkTest {
    @Test
    fun immediatePublicationUsesStableUniquePolicyAndExponentialBackoff() {
        val request = tvContinueWatchingWorkRequest()

        assertEquals(androidx.work.ExistingWorkPolicy.KEEP, TV_CONTINUE_WATCHING_WORK_POLICY)
        assertEquals(BackoffPolicy.EXPONENTIAL, request.workSpec.backoffPolicy)
        assertEquals(WorkRequest.MIN_BACKOFF_MILLIS, request.workSpec.backoffDelayDuration)
        assertTrue(TV_CONTINUE_WATCHING_WORK_NAME in request.tags)
        assertEquals(TvContinueWatchingSyncWorker::class.qualifiedName, request.workSpec.workerClassName)
    }
}
