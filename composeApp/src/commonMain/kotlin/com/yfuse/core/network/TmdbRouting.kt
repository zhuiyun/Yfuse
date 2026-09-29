package com.yfuse.core.network

import com.yfuse.core.logging.AppLog
import io.ktor.client.call.HttpClientCall
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.encodedPath
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.IOException
import kotlin.concurrent.Volatile

/**
 * What TMDB requests need from the signed-in Yfuse account, handed over as a flow and functions so
 * this layer never depends on the account one, which already depends on it.
 */
class TmdbAccountAccess(
    /** The account server's TMDB proxy, `<account origin>/api/v1/tmdb`. */
    val proxyBase: String,
    /** Whether a session is restored and signed in; asking for a token is worth it only then. */
    val sessionAvailable: StateFlow<Boolean>,
    /** The session's access token, renewed ahead of expiry; null without a session. */
    val accessToken: suspend () -> String?,
    /** A new access token after the server refused the current one; null without a session. */
    val refreshAccessToken: suspend () -> String?,
)

/** How one TMDB request is authorised. */
internal enum class TmdbRoute {
    /** Through the account server with the account's bearer; the TMDB token stays on the server. */
    Proxy,

    /** Straight to TMDB with the token built into this APK. */
    Direct,

    /** Neither is possible: the request fails the way an unreachable TMDB does. */
    Unavailable,
}

/**
 * The proxy whenever an account session exists: it is the path that keeps working once release
 * builds stop carrying a token. The built-in token when there is no session, or while the proxy is
 * cooling down after saying it cannot serve. With neither, the account is still asked, because at
 * launch its session may not be restored yet ([TmdbRequestRouter] decides how long to wait).
 */
internal fun chooseTmdbRoute(
    proxyConfigured: Boolean,
    sessionAvailable: Boolean,
    hasBuiltInToken: Boolean,
    proxyCoolingDown: Boolean,
): TmdbRoute =
    when {
        proxyConfigured && sessionAvailable && !(hasBuiltInToken && proxyCoolingDown) -> TmdbRoute.Proxy
        hasBuiltInToken -> TmdbRoute.Direct
        proxyConfigured -> TmdbRoute.Proxy
        else -> TmdbRoute.Unavailable
    }

/** What an error answer from the proxy means for a build that also carries a token. */
internal sealed interface TmdbProxyVerdict {
    /** TMDB's own answer relayed by the proxy: a missing title is missing on either path. */
    data object Keep : TmdbProxyVerdict

    /** Repeat this request directly; a positive [cooldownMs] also skips the proxy for that long. */
    data class RetryDirect(
        val cooldownMs: Long,
    ) : TmdbProxyVerdict
}

/**
 * A 404 the proxy did not mark as TMDB's is an account server without the route yet, and a 503
 * one that has no TMDB token or no capacity: both say nothing about the next request either, so
 * the proxy is skipped for a while. A 429 is skipped for as long as it asks. Anything else (the
 * allowlist declining a read, TMDB unreachable from the server) is retried directly just this once.
 */
internal fun tmdbProxyVerdict(
    status: Int,
    relayedByProxy: Boolean,
    retryAfterSeconds: Long?,
): TmdbProxyVerdict =
    when {
        relayedByProxy -> TmdbProxyVerdict.Keep
        status == 404 || status == 503 -> TmdbProxyVerdict.RetryDirect(PROXY_COOLDOWN_MS)
        status == 429 ->
            TmdbProxyVerdict.RetryDirect(
                (retryAfterSeconds ?: DEFAULT_RETRY_AFTER_SECONDS).coerceIn(1L, MAX_RETRY_AFTER_SECONDS) * 1_000L,
            )
        else -> TmdbProxyVerdict.RetryDirect(0L)
    }

/** No account session and no built-in token. Callers treat it as TMDB being unreachable. */
internal class TmdbUnavailableException :
    IOException("TMDB has no route: no Yfuse account session and no built-in token")

/**
 * Sends each TMDB request the way [chooseTmdbRoute] picks, then repairs what can be repaired: one
 * token renewal when the account server refuses the session, and a direct retry when the proxy
 * cannot serve and this build still has a token. Requests for any other host are passed through
 * untouched, and no request ever carries both credentials or the wrong one to either server.
 */
internal class TmdbRequestRouter(
    private val builtInToken: () -> String,
    private val account: TmdbAccountAccess?,
    private val nowEpochMs: () -> Long,
) {
    private val tokenRenewal = Mutex()

    /** Until then a build without a token waits for the session restore instead of giving up. */
    private val restoreGraceEndsAt = nowEpochMs() + SESSION_RESTORE_GRACE_MS

    @Volatile
    private var proxyCoolingUntil = 0L

    /** The token that the last renewal could not replace; requests refused with it stop asking. */
    @Volatile
    private var unrenewableToken: String? = null

    @Volatile
    private var lastRoute: TmdbRoute? = null

    @Volatile
    private var lastFallbackLoggedAt: Long? = null

    suspend fun send(
        request: HttpRequestBuilder,
        execute: suspend (HttpRequestBuilder) -> HttpClientCall,
    ): HttpClientCall {
        val original = request.url.build()
        val tmdbPath = original.tmdbApiPath() ?: return execute(request)
        request.headers.remove(HttpHeaders.Authorization)
        val direct = builtInToken().trim()
        val route =
            chooseTmdbRoute(
                proxyConfigured = account != null,
                sessionAvailable = account?.sessionAvailable?.value == true,
                hasBuiltInToken = direct.isNotEmpty(),
                proxyCoolingDown = nowEpochMs() < proxyCoolingUntil,
            )
        val accountToken = if (route == TmdbRoute.Proxy) account?.let { sessionToken(it, direct) } else null
        return when {
            accountToken != null -> {
                note(TmdbRoute.Proxy)
                viaProxy(request, original, tmdbPath, accountToken, direct, execute)
            }
            direct.isNotEmpty() -> {
                note(TmdbRoute.Direct)
                directly(request, original, direct, execute)
            }
            else -> {
                note(TmdbRoute.Unavailable)
                throw TmdbUnavailableException()
            }
        }
    }

    /**
     * The account restores its session in the background at launch, and the home page asks for its
     * shelves before that finishes. A build with a token simply goes direct meanwhile; one without
     * would fail the first refresh, so it waits for the restore, but only while launch is recent:
     * after that a missing session means signed out, and waiting would only delay the empty state.
     */
    private suspend fun sessionToken(
        access: TmdbAccountAccess,
        direct: String,
    ): String? {
        resolve(access.accessToken)?.let { return it }
        val remainingMs = restoreGraceEndsAt - nowEpochMs()
        if (direct.isNotEmpty() || remainingMs <= 0L || access.sessionAvailable.value) return null
        withTimeoutOrNull(remainingMs) { access.sessionAvailable.first { it } } ?: return null
        return resolve(access.accessToken)
    }

    private suspend fun viaProxy(
        request: HttpRequestBuilder,
        original: Url,
        tmdbPath: String,
        token: String,
        direct: String,
        execute: suspend (HttpRequestBuilder) -> HttpClientCall,
    ): HttpClientCall {
        val access = checkNotNull(account)
        var outcome = tryProxy(execute, request.towardsProxy(access.proxyBase, tmdbPath, token))
        if (outcome.response.status.isSuccess()) return outcome.keep()
        if (outcome.response.status == HttpStatusCode.Unauthorized) {
            renewToken(access, rejected = token)?.let { renewed ->
                outcome = tryProxy(execute, request.towardsProxy(access.proxyBase, tmdbPath, renewed))
                if (outcome.response.status.isSuccess()) return outcome.keep()
            }
        }
        val response = outcome.response
        val verdict =
            tmdbProxyVerdict(
                status = response.status.value,
                relayedByProxy = response.headers[TMDB_PROXY_CACHE_HEADER] != null,
                retryAfterSeconds = response.headers[HttpHeaders.RetryAfter]?.trim()?.toLongOrNull(),
            )
        if (direct.isEmpty() || verdict !is TmdbProxyVerdict.RetryDirect) return outcome.keep()
        if (verdict.cooldownMs > 0L) startCooldown(verdict.cooldownMs, response.status.value)
        noteFallback(response.status.value)
        return directly(request, original, direct, execute)
    }

    private suspend fun directly(
        request: HttpRequestBuilder,
        original: Url,
        token: String,
        execute: suspend (HttpRequestBuilder) -> HttpClientCall,
    ): HttpClientCall {
        request.url.pointAt(original.protocol, original.host, original.specifiedPort, original.encodedPath)
        request.headers.remove(HttpHeaders.Authorization)
        request.headers.append(HttpHeaders.Authorization, "Bearer $token")
        return execute(request)
    }

    private suspend fun renewToken(
        access: TmdbAccountAccess,
        rejected: String,
    ): String? =
        tokenRenewal.withLock {
            // Requests refused together queue here: the first renews, the rest find its new token.
            if (rejected == unrenewableToken) return@withLock null
            val renewed = resolve(access.accessToken)?.takeIf { it != rejected } ?: resolve(access.refreshAccessToken)
            if (renewed == null) unrenewableToken = rejected
            renewed
        }

    private fun startCooldown(
        cooldownMs: Long,
        status: Int,
    ) {
        val until = nowEpochMs() + cooldownMs
        if (until <= proxyCoolingUntil) return
        proxyCoolingUntil = until
        AppLog.info(
            category = "tmdb",
            event = "proxy_cooldown_started",
            message = "The account server cannot proxy TMDB; the built-in token is used meanwhile",
            attributes = mapOf("status" to status.toString(), "cooldownMs" to cooldownMs.toString()),
        )
    }

    /** At most once a minute: a lagging allowlist would otherwise log every shelf of every refresh. */
    private fun noteFallback(status: Int) {
        val now = nowEpochMs()
        lastFallbackLoggedAt?.let { if (now - it in 0 until FALLBACK_LOG_INTERVAL_MS) return }
        lastFallbackLoggedAt = now
        AppLog.info(
            category = "tmdb",
            event = "proxy_fallback",
            message = "The TMDB proxy declined a request; it was retried with the built-in token",
            attributes = mapOf("status" to status.toString()),
        )
    }

    /** Once per change of route rather than once per request. */
    private fun note(route: TmdbRoute) {
        if (lastRoute == route) return
        lastRoute = route
        if (route == TmdbRoute.Unavailable) {
            AppLog.warning(
                category = "tmdb",
                event = "route_unavailable",
                message = "TMDB needs a signed-in Yfuse account or a built-in token",
            )
        } else {
            AppLog.info(
                category = "tmdb",
                event = "route_changed",
                message = "TMDB requests changed route",
                attributes = mapOf("route" to route.name.lowercase()),
            )
        }
    }
}

/** One proxied attempt: its call, or the exception the response validator raised for its status. */
private class ProxyAttempt(
    private val call: HttpClientCall?,
    private val refusal: ResponseException?,
) {
    val response: HttpResponse get() = call?.response ?: checkNotNull(refusal).response

    fun keep(): HttpClientCall = call ?: throw checkNotNull(refusal)
}

private suspend fun tryProxy(
    execute: suspend (HttpRequestBuilder) -> HttpClientCall,
    request: HttpRequestBuilder,
): ProxyAttempt =
    try {
        ProxyAttempt(execute(request), null)
    } catch (refusal: ResponseException) {
        ProxyAttempt(null, refusal)
    }

/** The account layer's failures are its own to report; here they only mean "no account token". */
private suspend fun resolve(provider: suspend () -> String?): String? =
    withTimeoutOrNull(ACCOUNT_TOKEN_WAIT_MS) {
        try {
            provider()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }?.takeIf(String::isNotBlank)

private fun HttpRequestBuilder.towardsProxy(
    proxyBase: String,
    tmdbPath: String,
    token: String,
): HttpRequestBuilder =
    apply {
        val proxy = Url(proxyBase)
        url.pointAt(proxy.protocol, proxy.host, proxy.specifiedPort, proxy.encodedPath.trimEnd('/') + tmdbPath)
        headers.remove(HttpHeaders.Authorization)
        headers.append(HttpHeaders.Authorization, "Bearer $token")
    }

/** Moves a request between TMDB and the proxy; its query stays exactly as the caller built it. */
private fun URLBuilder.pointAt(
    protocol: URLProtocol,
    host: String,
    port: Int,
    encodedPath: String,
) {
    this.protocol = protocol
    this.host = host
    this.port = port
    this.encodedPath = encodedPath
}

/** The path below `/3` of a TMDB API URL, or null for any other destination. */
private fun Url.tmdbApiPath(): String? {
    val base = TMDB_API_BASE_URL
    if (protocol != base.protocol || !host.equals(base.host, ignoreCase = true)) return null
    if (specifiedPort != 0 && specifiedPort != base.protocol.defaultPort) return null
    val prefix = base.encodedPath.trimEnd('/') + "/"
    return encodedPath.takeIf { it.startsWith(prefix) }?.substring(prefix.length - 1)
}

private val TMDB_API_BASE_URL = Url(TMDB_BASE)

/**
 * Set by the account server on TMDB's own answers (`TmdbProxyRoutes.kt`); its absence on a 404
 * is what tells an account server without the proxy route from a title TMDB does not have.
 */
private const val TMDB_PROXY_CACHE_HEADER = "X-Yfuse-Tmdb-Cache"

/**
 * Long enough that a server without the route costs one extra request every few minutes, short
 * enough that a deployment or a restored token is picked up without restarting the app.
 */
private const val PROXY_COOLDOWN_MS = 2 * 60_000L
private const val DEFAULT_RETRY_AFTER_SECONDS = 30L
private const val MAX_RETRY_AFTER_SECONDS = 120L
private const val FALLBACK_LOG_INTERVAL_MS = 60_000L

/**
 * At launch the account restore holds its lock for one refresh round trip; a sync upload can hold
 * it longer. Past this the request goes direct when it can, and fails like a timeout when not.
 */
private const val ACCOUNT_TOKEN_WAIT_MS = 10_000L

/** A restore is one refresh request; the account page's own restore budget is fifteen seconds. */
private const val SESSION_RESTORE_GRACE_MS = 10_000L
