package com.yfuse.core.data

import com.yfuse.core.network.EmbyError
import com.yfuse.core.network.EmbyErrorException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EmbyApiCallTest {
    @Test
    fun a_failure_answered_from_a_cooldown_stays_marked_through_nested_boundaries() =
        runTest {
            val inner =
                embyApiCall<Unit>("inner") {
                    throw EmbyErrorException(EmbyError.Unauthorized, fromCooldown = true)
                }
            val outer = embyApiCall("outer") { inner.getOrThrow() }

            val error = assertIs<EmbyErrorException>(outer.exceptionOrNull())
            assertEquals(EmbyError.Unauthorized, error.error)
            assertTrue(error.fromCooldown)
            val fresh = embyApiCall<Unit>("fresh") { throw EmbyErrorException(EmbyError.Unauthorized) }
            assertFalse(assertIs<EmbyErrorException>(fresh.exceptionOrNull()).fromCooldown)
        }

    @Test
    fun work_called_on_the_ui_thread_moves_to_a_worker_and_other_work_stays_put() =
        runTest {
            val caller = coroutineContext[ContinuationInterceptor]

            val fromUi = offUiThread(onUiThread = true) { currentCoroutineContext()[ContinuationInterceptor] }
            val fromWorker = offUiThread(onUiThread = false) { currentCoroutineContext()[ContinuationInterceptor] }

            assertEquals(Dispatchers.Default, fromUi)
            assertEquals(caller, fromWorker)
        }
}
