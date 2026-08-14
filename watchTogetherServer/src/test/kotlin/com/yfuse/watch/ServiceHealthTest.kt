package com.yfuse.watch

import com.yfuse.watch.account.AccountBackend
import com.yfuse.watch.account.AccountExecutionPolicy
import com.yfuse.watch.account.AccountWorkExecutor
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServiceHealthTest {
    @Test
    fun healthy_dependencies_report_ok() = runTest {
        val accountBackend = AccountBackend.inMemory()
        val migrationExecutor =
            AccountWorkExecutor(
                AccountExecutionPolicy(workerThreads = 1, maxConcurrentOperations = 1),
            )
        try {
            val health = serviceHealth(accountBackend, migrationExecutor)
            assertTrue(health.healthy)
            assertEquals("ok", health.checks["accountDatabase"])
            assertEquals("ok", health.checks["accountExecutor"])
            assertEquals("ok", health.checks["migrationExecutor"])
        } finally {
            accountBackend.close()
            migrationExecutor.close()
        }
    }

    @Test
    fun closed_account_backend_is_degraded() = runTest {
        val accountBackend = AccountBackend.inMemory()
        val migrationExecutor = AccountWorkExecutor()
        accountBackend.close()
        try {
            val health = serviceHealth(accountBackend, migrationExecutor)
            assertFalse(health.healthy)
            assertEquals("unavailable", health.checks["accountDatabase"])
        } finally {
            migrationExecutor.close()
        }
    }

    @Test
    fun closed_migration_executor_is_degraded() = runTest {
        val accountBackend = AccountBackend.inMemory()
        val migrationExecutor = AccountWorkExecutor()
        migrationExecutor.close()
        try {
            val health = serviceHealth(accountBackend, migrationExecutor)
            assertFalse(health.healthy)
            assertEquals("unavailable", health.checks["migrationExecutor"])
        } finally {
            accountBackend.close()
        }
    }
}
