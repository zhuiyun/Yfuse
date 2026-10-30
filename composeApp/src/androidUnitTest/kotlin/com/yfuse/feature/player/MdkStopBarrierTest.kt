package com.yfuse.feature.player

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MdkStopBarrierTest {
    @Test fun waits_for_native_stop_acknowledgement_before_allowing_submission() =
        runTest {
            var calls = 0
            assertTrue(
                awaitMdkStopped({ ++calls >= 3 }),
            )
            assertEquals(3, calls)
            assertEquals(20L, testScheduler.currentTime)
        }

    @Test fun a_stalled_native_stop_has_a_bounded_deadline() =
        runTest {
            assertFalse(awaitMdkStopped({ false }, timeoutMs = 50L))
            assertEquals(50L, testScheduler.currentTime)
        }

    @Test fun cancelling_an_obsolete_load_stops_native_waits() =
        runTest {
            var calls = 0
            var submitted = false
            val job =
                launch {
                    if (awaitMdkStopped({
                            calls++
                            false
                        })
                    ) {
                        submitted = true
                    }
                }
            runCurrent()
            job.cancel()
            runCurrent()
            val atCancel = calls
            advanceTimeBy(5000L)
            runCurrent()
            assertEquals(atCancel, calls)
            assertFalse(submitted)
        }
}
