package com.yfuse.core.sync

import com.yfuse.backend.BackendAccess
import com.yfuse.backend.BackendUnavailableException
import com.yfuse.core.remote.BackendRemoteRelayConnector
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BackendWebSocketBoundaryTest {
    @Test
    fun disabledWatchDoesNotCreateEngineResolveTokenOrEnterSession() =
        runTest {
            var engines = 0
            var tokens = 0
            var sessions = 0
            val connector =
                KtorWatchRelayConnector(
                    engineFactory = {
                        engines++
                        error("A disabled backend must not construct its network engine")
                    },
                    backendAccess = BackendAccess(enabled = false),
                )
            assertFailsWith<BackendUnavailableException> {
                connector.connect(
                    url = "wss://account.example/watch",
                    accessToken = {
                        tokens++
                        "access-secret"
                    },
                ) { sessions++ }
            }
            assertEquals(0, engines)
            assertEquals(0, tokens)
            assertEquals(0, sessions)
        }

    @Test
    fun disabledRemoteDoesNotCreateEngineOrEnterSession() =
        runTest {
            var engines = 0
            var sessions = 0
            val connector =
                BackendRemoteRelayConnector(
                    engineFactory = {
                        engines++
                        error("A disabled backend must not construct its network engine")
                    },
                    backendAccess = BackendAccess(enabled = false),
                )
            assertFailsWith<BackendUnavailableException> {
                connector.connect("wss://account.example/watch", "access-secret") { sessions++ }
            }
            assertEquals(0, engines)
            assertEquals(0, sessions)
        }

    @Test
    fun watchWithoutAccountTokenDoesNotConstructANetworkEngine() =
        runTest {
            var engines = 0
            val connector =
                KtorWatchRelayConnector(
                    engineFactory = {
                        engines++
                        error("Missing credentials must not construct a network engine")
                    },
                    backendAccess = BackendAccess(enabled = true),
                )
            assertFailsWith<AccountRequiredForWatchException> {
                connector.connect("wss://account.example/watch", accessToken = { null }) {
                    error("An unauthenticated session must not open")
                }
            }
            assertEquals(0, engines)
        }
}
