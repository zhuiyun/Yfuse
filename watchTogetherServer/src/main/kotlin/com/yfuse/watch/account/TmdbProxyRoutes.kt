package com.yfuse.watch.account

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * Marks TMDB's own answers. The app tells a missing title (404 with this header) from an account
 * server that has no proxy route yet (404 without it) by this alone.
 */
internal const val TMDB_PROXY_CACHE_HEADER = "X-Yfuse-Tmdb-Cache"

internal fun Route.tmdbProxyRoutes(
    backend: AccountBackend,
    limiter: AccountRateLimiter,
    proxy: TmdbProxy = TmdbProxy(),
) {
    get("/api/v1/tmdb/{path...}") {
        call.handleAccountEndpoint(limiter, AccountRateLimitBucket.TmdbProxy) {
            val path = call.parameters.getAll("path").orEmpty()
            val query = call.request.queryParameters
            // No Authorization at all is a signed-out read; a bad or expired bearer stays a 401, so
            // a signed-in app still renews its session instead of quietly reading anonymously.
            val result =
                if (call.request.headers[HttpHeaders.Authorization] == null) {
                    proxy.fetchAnonymous(call.clientIdentity(), path, query)
                        ?: proxy.fetch(backend.validateAccessToken(call.requireBearerToken()), path, query)
                } else {
                    proxy.fetch(backend.validateAccessToken(call.requireBearerToken()), path, query)
                }
            when (result) {
                is TmdbProxyResult.Relayed -> {
                    call.response.headers.append(TMDB_PROXY_CACHE_HEADER, if (result.fromCache) "hit" else "miss")
                    call.respondText(result.body, ContentType.Application.Json, HttpStatusCode.fromValue(result.status))
                }
                is TmdbProxyResult.Refused -> {
                    val retryAfter = result.retryAfterSeconds
                    if (retryAfter != null) call.response.headers.append(HttpHeaders.RetryAfter, retryAfter.toString())
                    call.respondLimitedJson(ApiErrorResponse(ApiError(result.code, result.message)), result.status)
                }
            }
        }
    }
}

/** The address the per-IP limiter already resolved; a bad forwarding header never gets this far. */
private fun ApplicationCall.clientIdentity(): String =
    when (
        val resolved =
            resolveAccountClientIdentity(request.origin.remoteHost, request.headers.getAll("X-Forwarded-For"))
    ) {
        is ClientIdentityResolution.Resolved -> resolved.value
        ClientIdentityResolution.InvalidForwardedFor -> "unknown"
    }
