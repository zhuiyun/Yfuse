package com.yfuse.feature.servers

import com.yfuse.core.data.ServerManagementSnapshot
import com.yfuse.core.model.SavedServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ServerManagementControllerTest {
    private val a = server("a")
    private val b = server("b")
    private val snapshot = ServerManagementSnapshot(emptyList(), emptyList(), true, true)

    @Test
    fun closed_or_replaced_panel_rejects_noncooperative_late_loads() =
        runTest {
            val first = CompletableDeferred<Unit>()
            val controller =
                ServerManagementController(backgroundScope, { if (it == "a") a else b }) {
                    if (it.id == "a") withContext(NonCancellable) { first.await() }
                    Result.success(snapshot)
                }
            controller.open("a")
            runCurrent()
            controller.close()
            controller.open("b")
            runCurrent()
            assertEquals("b", assertIs<ServerManagementUiState.Ready>(controller.state.value).serverId)
            first.complete(Unit)
            runCurrent()
            assertEquals("b", assertIs<ServerManagementUiState.Ready>(controller.state.value).serverId)
            controller.close()
            assertIs<ServerManagementUiState.Idle>(controller.state.value)
        }

    @Test
    fun reopening_same_server_rejects_old_generation_and_foreign_object_callbacks() =
        runTest {
            val controller = ServerManagementController(backgroundScope, { a }) { Result.success(snapshot) }
            controller.open("a")
            runCurrent()
            val old = assertIs<ServerManagementUiState.Ready>(controller.state.value)
            controller.open("a")
            runCurrent()
            val current = assertIs<ServerManagementUiState.Ready>(controller.state.value)
            var calls = 0
            val action: suspend (SavedServer) -> Result<ServerManagementActionResult> = {
                calls++
                Result.success(ServerManagementActionResult("done"))
            }
            controller.submit("a", old.sessionId, "old", { true }, action)
            controller.submit("b", current.sessionId, "foreign", { true }, action)
            controller.submit("a", current.sessionId, "unknown-object", { false }, action)
            runCurrent()
            assertEquals(0, calls)
            assertEquals(current, controller.state.value)
            assertIs<ServerManagementUiState.Idle>(current.forServer("b"))
        }

    @Test
    fun submitted_action_keeps_original_target_and_cannot_replace_new_panel_feedback() =
        runTest {
            val pending = CompletableDeferred<Unit>()
            var currentA = a
            val controller =
                ServerManagementController(backgroundScope, { if (it == "a") currentA else b }) {
                    Result.success(snapshot)
                }
            controller.open("a")
            runCurrent()
            val ready = assertIs<ServerManagementUiState.Ready>(controller.state.value)
            currentA = a.copy(baseUrl = "https://backup.example")
            var accepted: SavedServer? = null
            var completed = false
            controller.submit("a", ready.sessionId, "task:1", { true }) {
                accepted = it
                pending.await()
                completed = true
                Result.success(ServerManagementActionResult("A completed"))
            }
            runCurrent()
            assertEquals(currentA, accepted)
            controller.open("b")
            runCurrent()
            pending.complete(Unit)
            runCurrent()
            assertTrue(completed)
            val latest = assertIs<ServerManagementUiState.Ready>(controller.state.value)
            assertEquals("b", latest.serverId)
            assertEquals(null, latest.message)
        }

    @Test
    fun busy_action_and_replaced_credentials_cannot_submit_again() =
        runTest {
            var live = a
            val pending = CompletableDeferred<Unit>()
            val controller = ServerManagementController(backgroundScope, { live }) { Result.success(snapshot) }
            controller.open("a")
            runCurrent()
            val ready = assertIs<ServerManagementUiState.Ready>(controller.state.value)
            var calls = 0
            val action: suspend (SavedServer) -> Result<ServerManagementActionResult> = {
                calls++
                pending.await()
                Result.success(ServerManagementActionResult("done"))
            }
            controller.submit("a", ready.sessionId, "first", { true }, action)
            controller.submit("a", ready.sessionId, "second", { true }, action)
            runCurrent()
            assertEquals(1, calls)
            pending.complete(Unit)
            runCurrent()
            live = a.copy(accessToken = "new-account")
            controller.submit("a", ready.sessionId, "stale-account", { true }, action)
            runCurrent()
            assertEquals(1, calls)
            assertIs<ServerManagementUiState.Idle>(controller.state.value)
            assertFalse(live.sameManagementAccount(a))
        }

    @Test
    fun a_matching_user_switch_reloads_the_new_identity() =
        runTest {
            val servers = mutableMapOf("a" to a, "b" to b)
            val controller = ServerManagementController(backgroundScope, servers::get) { Result.success(snapshot) }
            controller.open("a")
            runCurrent()
            val ready = assertIs<ServerManagementUiState.Ready>(controller.state.value)
            controller.submit("a", ready.sessionId, "home:b", { true }) {
                Result.success(ServerManagementActionResult("changed", "b"))
            }
            runCurrent()
            assertEquals("b", assertIs<ServerManagementUiState.Ready>(controller.state.value).serverId)
        }

    @Test
    fun completed_user_switch_does_not_reopen_a_closed_panel() =
        runTest {
            val pending = CompletableDeferred<Unit>()
            val controller =
                ServerManagementController(backgroundScope, { if (it == "a") a else b }) {
                    Result.success(snapshot)
                }
            controller.open("a")
            runCurrent()
            val ready = assertIs<ServerManagementUiState.Ready>(controller.state.value)
            var committed = false
            controller.submit("a", ready.sessionId, "home:b", { true }) {
                pending.await()
                committed = true
                Result.success(ServerManagementActionResult("changed", "b"))
            }
            runCurrent()
            controller.close()
            pending.complete(Unit)
            runCurrent()
            assertTrue(committed)
            assertIs<ServerManagementUiState.Idle>(controller.state.value)
        }

    @Test
    fun closing_cancels_a_pending_read_and_a_late_error_stays_closed() =
        runTest {
            val pending = CompletableDeferred<Unit>()
            val controller =
                ServerManagementController(backgroundScope, { a }) {
                    withContext(NonCancellable) { pending.await() }
                    Result.failure(IllegalStateException("late error"))
                }
            controller.open("a")
            runCurrent()
            controller.close()
            pending.complete(Unit)
            runCurrent()
            assertIs<ServerManagementUiState.Idle>(controller.state.value)
        }

    private fun server(id: String) = SavedServer(id, "https://$id.example", id, "user", "user", "token")
}
