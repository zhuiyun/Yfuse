package com.yfuse.core.data

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class CalendarLookupBudgetTest {
    @Test
    fun queued_servers_get_their_full_network_budget() =
        runTest {
            val permits = Semaphore(1, acquiredPermits = 1)
            val lookup =
                async {
                    calendarLibraryLookup(permits, 8_000L) {
                        delay(7_000L)
                        "ready"
                    }
                }
            advanceTimeBy(16_000L)
            assertFalse(lookup.isCompleted)
            permits.release()
            assertEquals("ready", lookup.await())
            assertEquals(1, permits.availablePermits)
        }

    @Test
    fun slow_network_times_out_and_releases_admission() =
        runTest {
            val permits = Semaphore(1)
            assertNull(
                calendarLibraryLookup(permits, 8_000L) {
                    delay(9_000L)
                    "late"
                },
            )
            assertEquals(1, permits.availablePermits)
        }

    @Test
    fun leaving_the_page_cancels_queued_work_without_starting_it() =
        runTest {
            val permits = Semaphore(1, acquiredPermits = 1)
            var called = false
            val lookup = async { calendarLibraryLookup(permits, 8_000L) { called = true } }
            runCurrent()
            lookup.cancel()
            lookup.join()
            permits.release()
            assertFalse(called)
            assertEquals(1, permits.availablePermits)
        }
}
