package com.yfuse.feature.servers

import com.yfuse.core.model.SavedServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ServerStatsRefreshTest {
    private val original = SavedServer("a", "https://lan.example", "A", "user", "user", "token")

    @Test
    fun post_probe_stats_use_latest_route_without_rewriting_the_original_health_failure() =
        runTest {
            val health = mapOf("a" to Result.failure<Unit>(IllegalStateException("LAN failed")))
            val latest = original.copy(baseUrl = "https://wan.example")
            val fetched = mutableListOf<String>()
            val recorded = mutableListOf<Pair<String, Int>>()
            val results =
                refreshCurrentServerStats(listOf(original.id), { latest }, {
                    fetched += it.baseUrl
                    Result.success(12)
                }) { id, counts -> recorded += id to counts }
            assertEquals(listOf(latest.baseUrl), fetched)
            assertEquals(listOf("a" to 12), recorded)
            assertEquals(
                ServerRefreshResult.PartialFailure,
                summarizeServerRefresh(listOf("a"), health, results).result,
            )
            assertTrue(health.getValue("a").isFailure)
        }

    @Test
    fun deletion_or_account_replacement_cannot_reintroduce_old_statistics() =
        runTest {
            var live: SavedServer? = original
            var records = 0
            val result =
                refreshCurrentServerStats(listOf("a"), { live }, {
                    live = original.copy(accessToken = "replacement")
                    Result.success(12)
                }) { _, _ -> records++ }
            assertTrue(result.getValue("a").isFailure)
            live = null
            val missing =
                refreshCurrentServerStats<Int>(listOf("a"), { live }, {
                    error("Deleted servers must not be fetched")
                }) { _, _ -> records++ }
            assertTrue(missing.getValue("a").isFailure)
            assertEquals(0, records)
        }

    @Test
    fun cancellation_from_repository_is_not_a_success_or_a_cached_statistic() =
        runTest {
            assertFailsWith<CancellationException> {
                refreshCurrentServerStats<Int>(listOf("a"), { original }, {
                    Result.failure(CancellationException("cancelled"))
                }) { _, _ -> error("No result should be stored") }
            }
        }
}
