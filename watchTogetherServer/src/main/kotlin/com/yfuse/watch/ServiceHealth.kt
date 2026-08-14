package com.yfuse.watch

import com.yfuse.watch.account.AccountBackend
import com.yfuse.watch.account.AccountProblem
import com.yfuse.watch.account.AccountServiceException
import com.yfuse.watch.account.AccountWorkExecutor
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
        try {
            migrationRelayWorkExecutor.execute { true }
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Throwable) {
            false
        }
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

/** Account routes register the exact backend used by production requests. */
internal fun Route.registerAccountHealthDependency(accountBackend: AccountBackend) {
    application.attributes.put(HEALTH_ACCOUNT_BACKEND, accountBackend)
}

/**
 * Migration routes are configured immediately after account routes in [watchTogetherModule].
 * Installing here keeps the large application module untouched while still intercepting /health
 * before the legacy `respondText("ok")` handler is reached.
 */
internal fun Route.installServiceHealthEndpoint(migrationRelayWorkExecutor: AccountWorkExecutor) {
    val app = application
    app.attributes.put(HEALTH_MIGRATION_EXECUTOR, migrationRelayWorkExecutor)
    if (app.attributes.getOrNull(HEALTH_HANDLER_INSTALLED) == true) return
    app.attributes.put(HEALTH_HANDLER_INSTALLED, true)
    app.installHealthInterceptor()
}

private fun Application.installHealthInterceptor() {
    intercept(ApplicationCallPipeline.Call) {
        if (call.request.httpMethod != HttpMethod.Get || call.request.path() != "/health") {
            return@intercept
        }
        val accountBackend = attributes.getOrNull(HEALTH_ACCOUNT_BACKEND)
        val migrationExecutor = attributes.getOrNull(HEALTH_MIGRATION_EXECUTOR)
        val health =
            if (accountBackend != null && migrationExecutor != null) {
                serviceHealth(accountBackend, migrationExecutor)
            } else {
                ServiceHealthResponse(
                    status = "degraded",
                    checks =
                        linkedMapOf(
                            "accountDatabase" to if (accountBackend == null) "unavailable" else "ok",
                            "accountExecutor" to if (accountBackend == null) "unavailable" else "ok",
                            "migrationExecutor" to if (migrationExecutor == null) "unavailable" else "ok",
                        ),
                )
            }
        call.response.headers.append(HttpHeaders.CacheControl, "no-store, max-age=0")
        call.response.headers.append(HttpHeaders.Pragma, "no-cache")
        call.respondText(
            text = healthJson.encodeToString(health),
            contentType = ContentType.Application.Json,
            status = if (health.healthy) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
        )
        finish()
    }
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
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Throwable) {
        false
    }

private val HEALTH_ACCOUNT_BACKEND =
    AttributeKey<AccountBackend>("yfuse.health.accountBackend")
private val HEALTH_MIGRATION_EXECUTOR =
    AttributeKey<AccountWorkExecutor>("yfuse.health.migrationExecutor")
private val HEALTH_HANDLER_INSTALLED =
    AttributeKey<Boolean>("yfuse.health.handlerInstalled")
private val healthJson = Json { encodeDefaults = true }

private const val HEALTH_PROBE_ACCESS_TOKEN =
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
