package com.yfuse.core.data

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AsyncServerSessionStartupTest {
    @Test
    fun startup_does_not_block_caller_and_starts_services_once_after_restore() =
        runTest {
            var restored = false
            var services = 0
            val startup =
                AsyncServerSessionStartup(this, { restored = true }, {
                    check(restored)
                    services++
                }, StandardTestDispatcher(testScheduler))
            startup.start()
            startup.start()
            assertEquals(false, restored)
            assertEquals(SessionStartupPhase.Restoring, startup.phase.value)
            advanceUntilIdle()
            assertEquals(SessionStartupPhase.Ready, startup.phase.value)
            startup.start()
            advanceUntilIdle()
            assertEquals(1, services)
        }

    @Test
    fun locked_credentials_preserve_the_gate_and_retry_without_starting_services() =
        runTest {
            var unavailable = true
            var services = 0
            val startup =
                AsyncServerSessionStartup(this, {
                    if (unavailable) throw ServerSessionRestoreException(IllegalStateException("locked"))
                }, { services++ }, StandardTestDispatcher(testScheduler))
            startup.start()
            advanceUntilIdle()
            assertEquals(SessionStartupPhase.NeedsRetry, startup.phase.value)
            assertEquals(0, services)
            unavailable = false
            startup.start()
            advanceUntilIdle()
            assertEquals(SessionStartupPhase.Ready, startup.phase.value)
            assertEquals(1, services)
        }
}
