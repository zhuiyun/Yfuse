package com.yfuse.watch

import com.yfuse.watch.account.AccountBackend
import com.yfuse.watch.account.AccountExecutionPolicy
import com.yfuse.watch.account.AccountWorkExecutor
import com.yfuse.watch.migration.MigrationRelayBackend
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
