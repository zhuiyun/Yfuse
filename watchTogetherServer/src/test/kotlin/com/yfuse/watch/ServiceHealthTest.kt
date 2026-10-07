package com.yfuse.watch

import com.yfuse.watch.account.AccountBackend
import com.yfuse.watch.account.AccountExecutionPolicy
import com.yfuse.watch.account.AccountWorkExecutor
import com.yfuse.watch.migration.MigrationRelayBackend
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServiceHealthTest {
    @Test
    fun healthy_dependencies_report_ok() =
        runTest {
            val accountBackend = AccountBackend.inMemory()
            val migrationBackend = MigrationRelayBackend.inMemory()
            val migrationExecutor =
                AccountWorkExecutor(
                    AccountExecutionPolicy(workerThreads = 1, maxConcurrentOperations = 1),
                )
            try {
                val health = serviceHealth(accountBackend, migrationBackend, migrationExecutor)
                assertTrue(health.healthy)
                assertEquals("ok", health.checks["accountDatabase"])
                assertEquals("ok", health.checks["accountExecutor"])
                assertEquals("ok", health.checks["migrationDatabase"])
                assertEquals("ok", health.checks["migrationExecutor"])
            } finally {
                accountBackend.close()
                migrationBackend.close()
                migrationExecutor.close()
            }
        }

    @Test
    fun closed_account_backend_is_degraded() =
        runTest {
            val accountBackend = AccountBackend.inMemory()
            val migrationBackend = MigrationRelayBackend.inMemory()
            val migrationExecutor = AccountWorkExecutor()
            accountBackend.close()
            try {
                val health = serviceHealth(accountBackend, migrationBackend, migrationExecutor)
                assertFalse(health.healthy)
                assertEquals("unavailable", health.checks["accountDatabase"])
            } finally {
                migrationBackend.close()
                migrationExecutor.close()
            }
        }

    @Test
    fun closed_migration_database_is_degraded() =
        runTest {
            val accountBackend = AccountBackend.inMemory()
            val migrationBackend = MigrationRelayBackend.inMemory()
            val migrationExecutor = AccountWorkExecutor()
            migrationBackend.close()
            try {
                val health = serviceHealth(accountBackend, migrationBackend, migrationExecutor)
                assertFalse(health.healthy)
                assertEquals("unavailable", health.checks["migrationDatabase"])
                assertEquals("ok", health.checks["migrationExecutor"])
            } finally {
                accountBackend.close()
                migrationExecutor.close()
            }
        }

    @Test
    fun closed_migration_executor_is_degraded() =
        runTest {
            val accountBackend = AccountBackend.inMemory()
            val migrationBackend = MigrationRelayBackend.inMemory()
            val migrationExecutor = AccountWorkExecutor()
            migrationExecutor.close()
            try {
                val health = serviceHealth(accountBackend, migrationBackend, migrationExecutor)
                assertFalse(health.healthy)
                assertEquals("unavailable", health.checks["migrationExecutor"])
            } finally {
                accountBackend.close()
                migrationBackend.close()
            }
        }
}

class ServiceHealthCacheTest {
    private val healthy = ServiceHealthResponse(status = "ok", checks = mapOf("accountDatabase" to "ok"))

    @Test
    fun one_probe_serves_every_caller_until_it_expires() =
        runTest {
            var nowMs = 0L
            val cache = ServiceHealthCache(ttlMs = 3_000L, monotonicMs = { nowMs })
            var probes = 0
            val probe: suspend () -> ServiceHealthResponse = {
                probes++
                healthy
            }
            repeat(5) { assertTrue(cache.get(probe).healthy) }
            assertEquals(1, probes)
            nowMs = 2_999L
            cache.get(probe)
            assertEquals(1, probes)
            nowMs = 3_000L
            cache.get(probe)
            assertEquals(2, probes)
        }

    @Test
    fun concurrent_callers_share_the_probe_in_flight() =
        runTest {
            val cache = ServiceHealthCache(ttlMs = 3_000L, monotonicMs = { 0L })
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var probes = 0
            val callers =
                List(8) {
                    async {
                        cache.get {
                            probes++
                            started.complete(Unit)
                            release.await()
                            healthy
                        }
                    }
                }
            started.await()
            release.complete(Unit)
            assertTrue(callers.awaitAll().all { it.healthy })
            assertEquals(1, probes)
        }
}
