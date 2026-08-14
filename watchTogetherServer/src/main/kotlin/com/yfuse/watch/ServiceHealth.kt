package com.yfuse.watch

import com.yfuse.watch.account.AccountBackend
import com.yfuse.watch.account.AccountProblem
import com.yfuse.watch.account.AccountServiceException
import com.yfuse.watch.account.AccountWorkExecutor
import kotlinx.serialization.Serializable

@Serializable
internal data class ServiceHealthResponse(
    val status: String,
    val checks: Map<String, String>,
) {
    val healthy: Boolean get() = status == "ok"
}

/**
 * Readiness, not a decorative liveness echo. The account probe traverses the account executor
 * and SQLite store using a syntactically valid token that is expected to be absent. The migration
 * probe executes real work on its bounded executor so saturation/shutdown is visible to orchestration.
 */
internal suspend fun serviceHealth(
    accountBackend: AccountBackend,
    migrationRelayWorkExecutor: AccountWorkExecutor,
): ServiceHealthResponse {
    val accountReady = probeAccountPersistence(accountBackend)
    val migrationExecutorReady =
        runCatching {
            migrationRelayWorkExecutor.execute { true }
        }.getOrDefault(false)
    val checks =
        linkedMapOf(
            "accountDatabase" to if (accountReady) "ok" else "unavailable",
            "accountExecutor" to if (accountReady) "ok" else "unavailable",
            "migrationExecutor" to if (migrationExecutorReady) "ok" else "unavailable",
        )
    return ServiceHealthResponse(
        status = if (accountReady && migrationExecutorReady) "ok" else "degraded",
        checks = checks,
    )
}

private suspend fun probeAccountPersistence(accountBackend: AccountBackend): Boolean =
    try {
        // Reaching either an account or Unauthorized proves the executor accepted work and
        // the store completed its token lookup. This token is canonical base64url length for
        // the session token format and intentionally has no corresponding session.
        accountBackend.validateAccessToken(HEALTH_PROBE_ACCESS_TOKEN)
        true
    } catch (failure: AccountServiceException) {
        failure.problem == AccountProblem.Unauthorized
    } catch (_: Throwable) {
        false
    }

private const val HEALTH_PROBE_ACCESS_TOKEN =
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
