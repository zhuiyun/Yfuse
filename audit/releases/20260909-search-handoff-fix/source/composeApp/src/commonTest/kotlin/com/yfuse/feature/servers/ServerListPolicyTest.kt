package com.yfuse.feature.servers

import com.yfuse.core.data.LatencySeverity
import com.yfuse.core.data.ServerHealth
import com.yfuse.core.data.ServerHealthStatus
import com.yfuse.core.data.latencySeverity
import com.yfuse.core.designsystem.Semantic
import com.yfuse.core.model.SavedServer
import com.yfuse.core.model.ServerRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerListPolicyTest {
    private val alice = server("a", "Alice")
    private val bob = server("b", "Bob")
    private val charlie = server("c", "Alice")

    @Test
    fun onlineAndLatencySortingKeepMissingMeasurementsLast() {
        val health =
            mapOf(
                alice.id to ServerHealth(ServerHealthStatus.Degraded, latencyMs = 90),
                bob.id to ServerHealth(ServerHealthStatus.Healthy, latencyMs = 180),
            )

        assertEquals(
            listOf("b", "a", "c"),
            filterAndSortServers(
                listOf(alice, bob, charlie),
                health,
                emptyMap(),
                ServerListFilter(ServerSortOrder.Online),
            ).map { it.id },
        )
        assertEquals(
            listOf("a", "b", "c"),
            filterAndSortServers(
                listOf(alice, bob, charlie),
                health,
                emptyMap(),
                ServerListFilter(ServerSortOrder.Latency),
            ).map { it.id },
        )
    }

    @Test
    fun accountFilterAndRecentSortCompose() {
        assertEquals(
            listOf("c", "a"),
            filterAndSortServers(
                listOf(alice, bob, charlie),
                emptyMap(),
                mapOf("a" to 1L, "c" to 9L),
                ServerListFilter(ServerSortOrder.Recent, account = "Alice"),
            ).map { it.id },
        )
    }

    @Test
    fun transportDiagnosisExplainsHttpsAndConfirmedLan() {
        val server =
            alice.copy(
                routes =
                    listOf(
                        ServerRoute(ServerRoute.PRIMARY_ID, "主线路", "https://a.example"),
                        ServerRoute("lan", "内网", "http://192.168.1.8:8096"),
                    ),
                localCleartextConfirmed = true,
            )

        assertEquals(
            listOf(TransportDiagnosticSeverity.Secure, TransportDiagnosticSeverity.LocalCleartext),
            diagnoseServerTransport(server).map { it.severity },
        )
    }

    @Test
    fun reachabilityAndLatencySeverityAreIndependent() {
        assertTrue(
            com.yfuse.core.data
                .RouteHealth(
                    ServerHealthStatus.Healthy,
                    latencyMs = 1_329,
                ).reachable,
        )
        assertEquals(LatencySeverity.Stable, latencySeverity(399))
        assertEquals(LatencySeverity.Slow, latencySeverity(400))
        assertEquals(LatencySeverity.Slow, latencySeverity(1_199))
        assertEquals(LatencySeverity.Unstable, latencySeverity(1_200))
        assertEquals(LatencySeverity.Unstable, latencySeverity(1_329))
        assertEquals(
            "不稳定 · 1329 ms",
            latencyLabel(ServerHealth(ServerHealthStatus.Healthy, latencyMs = 1_329)),
        )
        assertEquals(
            "在线",
            connectionLabel(ServerHealth(ServerHealthStatus.Healthy, latencyMs = 1_329)),
        )
        assertEquals(Semantic.Error, latencySeverityColor(LatencySeverity.Unstable))
        assertTrue(latencySeverityColor(LatencySeverity.Unstable) != Semantic.Success)
    }

    @Test
    fun latencySeverityFilterComposesWithAccountFilter() {
        val health =
            mapOf(
                alice.id to ServerHealth(ServerHealthStatus.Healthy, latencyMs = 120),
                bob.id to ServerHealth(ServerHealthStatus.Healthy, latencyMs = 700),
                charlie.id to ServerHealth(ServerHealthStatus.Healthy, latencyMs = 1_329),
            )

        assertEquals(
            listOf("c"),
            filterAndSortServers(
                listOf(alice, bob, charlie),
                health,
                emptyMap(),
                ServerListFilter(
                    sort = ServerSortOrder.Latency,
                    account = "Alice",
                    latency = ServerLatencyFilter.Unstable,
                ),
            ).map { it.id },
        )
        assertEquals(
            listOf("b"),
            filterAndSortServers(
                listOf(alice, bob, charlie),
                health,
                emptyMap(),
                ServerListFilter(latency = ServerLatencyFilter.Slow),
            ).map { it.id },
        )
    }

    private fun server(
        id: String,
        user: String,
    ) = SavedServer(
        id = id,
        baseUrl = "https://$id.example",
        serverName = id.uppercase(),
        userId = "user-$id",
        userName = user,
        accessToken = "token-$id",
    )
}
