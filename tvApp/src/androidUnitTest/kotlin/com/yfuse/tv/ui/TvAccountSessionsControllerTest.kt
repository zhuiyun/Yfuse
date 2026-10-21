package com.yfuse.tv.ui

import com.yfuse.core.account.AccountDeviceSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvAccountSessionsControllerTest {
    private val local = AccountDeviceSession("local", "TV", 0, 0, true)
    private val remote = AccountDeviceSession("remote", "Phone", 0, 0, false)

    private inner class Fixture(
        scope: TestScope,
    ) {
        var sessions = listOf(local, remote)
        var loadCalls = 0
        var revokeCalls = 0
        var bulkCalls = 0
        var failLoad = false
        var failRevoke = false
        var gate: CompletableDeferred<Unit>? = null
        val controller =
            TvAccountSessionsController(
                scope.backgroundScope,
                loadSessions = {
                    loadCalls++
                    if (failLoad) Result.failure(IllegalStateException("offline")) else Result.success(sessions)
                },
                revokeSession = { id ->
                    revokeCalls++
                    gate?.await()
                    if (failRevoke) {
                        Result.failure(IllegalStateException("offline"))
                    } else {
                        sessions = sessions.filterNot { it.id == id }
                        Result.success(Unit)
                    }
                },
                revokeOthers = {
                    bulkCalls++
                    gate?.await()
                    sessions = sessions.filter(AccountDeviceSession::current)
                    Result.success(Unit)
                },
            )
    }

    @Test
    fun failedLoadCanBeRetriedAndDoesNotMasqueradeAsEmptySuccess() =
        runTest {
            val f = Fixture(this)
            f.failLoad = true
            f.controller.refresh()
            runCurrent()
            assertTrue(f.controller.state.value.loadFailed)
            assertFalse(f.controller.state.value.loaded)
            assertFalse(f.controller.state.value.busy)
            f.failLoad = false
            f.controller.refresh()
            runCurrent()
            assertTrue(f.controller.state.value.loaded)
            assertFalse(f.controller.state.value.loadFailed)
            assertEquals(listOf(local, remote), f.controller.state.value.sessions)
        }

    @Test
    fun repeatedPressesAreReservedSynchronouslyIncludingRefreshAndBulk() =
        runTest {
            val f = Fixture(this)
            f.controller.refresh()
            runCurrent()
            f.gate = CompletableDeferred()
            assertTrue(f.controller.revoke("remote"))
            assertFalse(f.controller.revoke("remote"))
            assertFalse(f.controller.revoke("local"))
            f.controller.requestRevokeOthers()
            f.controller.refresh()
            assertFalse(f.controller.state.value.confirmOthers)
            runCurrent()
            assertEquals(1, f.revokeCalls)
            assertEquals(1, f.loadCalls)
            f.gate!!.complete(Unit)
            runCurrent()
            assertEquals(listOf(local), f.controller.state.value.sessions)
            assertFalse(f.controller.state.value.busy)
            assertEquals(2, f.loadCalls)
        }

    @Test
    fun bulkRequiresConfirmationAndCancelNeverRevokes() =
        runTest {
            val f = Fixture(this)
            f.controller.refresh()
            runCurrent()
            assertFalse(f.controller.confirmRevokeOthers())
            f.controller.requestRevokeOthers()
            runCurrent()
            assertEquals(0, f.bulkCalls)
            f.controller.dismissConfirmation()
            assertFalse(f.controller.confirmRevokeOthers())
            f.controller.requestRevokeOthers()
            assertTrue(f.controller.confirmRevokeOthers())
            assertFalse(f.controller.confirmRevokeOthers())
            runCurrent()
            assertEquals(1, f.bulkCalls)
            assertEquals(listOf(local), f.controller.state.value.sessions)
            assertFalse(f.controller.state.value.busy)
        }

    @Test
    fun revokeFailureRetainsTheRowAndCanBeRetried() =
        runTest {
            val f = Fixture(this)
            f.controller.refresh()
            runCurrent()
            f.failRevoke = true
            assertTrue(f.controller.revoke("remote"))
            runCurrent()
            assertEquals(listOf(local, remote), f.controller.state.value.sessions)
            assertFalse(f.controller.state.value.busy)
            assertTrue(
                f.controller.state.value.status!!
                    .startsWith("撤销失败"),
            )
            f.failRevoke = false
            assertTrue(f.controller.revoke("remote"))
            runCurrent()
            assertEquals(listOf(local), f.controller.state.value.sessions)
        }

    @Test
    fun refreshFailureRetainsPreviouslyLoadedRowsAndDuplicateIdsAreCollapsed() =
        runTest {
            val f = Fixture(this)
            f.sessions = listOf(local, remote, remote)
            f.controller.refresh()
            runCurrent()
            assertEquals(listOf(local, remote), f.controller.state.value.sessions)
            f.failLoad = true
            f.controller.refresh()
            runCurrent()
            assertEquals(listOf(local, remote), f.controller.state.value.sessions)
            assertTrue(f.controller.state.value.loadFailed)
        }
}
