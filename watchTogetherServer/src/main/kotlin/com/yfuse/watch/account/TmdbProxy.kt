package com.yfuse.watch.account

import com.yfuse.watch.ServerLog
import com.yfuse.watch.boundedStringBodyHandler
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/** One TMDB answer. [contentType] decides whether a 200 is data worth passing on and keeping. */
internal data class TmdbUpstreamResponse(
    val status: Int,
    val body: String,
    val contentType: String? = null,
    val retryAfterSeconds: Long? = null,
)

internal fun interface TmdbUpstream {
    /** GETs `https://api.themoviedb.org/3` + [pathAndQuery], authorised by [bearer]. */
    suspend fun get(
        pathAndQuery: String,
        bearer: String,
    ): TmdbUpstreamResponse
}

internal sealed interface TmdbProxyResult {
    /** TMDB's own answer, fresh or cached. Its status is kept, so a missing title stays a 404. */
    data class Relayed(
        val status: Int,
        val body: String,
        val fromCache: Boolean,
    ) : TmdbProxyResult

    /** The proxy's own answer, in the account API's error shape. */
    data class Refused(
        val status: HttpStatusCode,
        val code: String,
        val message: String,
        val retryAfterSeconds: Long? = null,
    ) : TmdbProxyResult
}

/**
 * Read-only TMDB for signed-in Yfuse accounts, so the read token lives on this server instead of
 * in every APK.
 *
 * Only [tmdbProxyRequest]'s allowlist is forwarded. Each account has its own budget on top of the
 * per-IP one the route applies before authentication, successful answers are reused for a time
 * that suits their kind, and a TMDB 429 pauses every miss until its Retry-After has passed rather
 * than letting each account spend the shared token's allowance again. The token is never logged
 * and never returned: only TMDB's body, or a fixed error, goes back.
 */
internal class TmdbProxy(
    /** The read token calendar ingestion already uses; blank keeps the proxy answering 503. */
    token: String? = System.getenv("TMDB_TOKEN"),
    private val upstream: TmdbUpstream = JdkTmdbUpstream(),
    private val accountLimiter: AccountRateLimiter = AccountRateLimiter(TMDB_ACCOUNT_RATE_POLICY),
    private val cache: TmdbResponseCache = TmdbResponseCache(),
    private val now: () -> Long = System::currentTimeMillis,
    maxConcurrentUpstream: Int = MAX_CONCURRENT_UPSTREAM,
    private val upstreamQueueWaitMs: Long = UPSTREAM_QUEUE_WAIT_MS,
) {
    private val token = token.orEmpty().trim()
    private val upstreamPermits = Semaphore(maxConcurrentUpstream)
    private val unconfiguredLogged = AtomicBoolean(false)

    @Volatile
    private var upstreamPausedUntil = 0L

    suspend fun fetch(
        account: AuthenticatedAccount,
        path: List<String>,
        query: Parameters,
    ): TmdbProxyResult {
        when (val decision = accountLimiter.check(account.userId, AccountRateLimitBucket.TmdbProxy)) {
            RateLimitDecision.Allowed -> Unit
            is RateLimitDecision.Limited -> return rateLimited(decision.retryAfterSeconds)
        }
        if (token.isEmpty()) {
            if (unconfiguredLogged.compareAndSet(false, true)) ServerLog.warn("tmdb_proxy_unconfigured")
            return TmdbProxyResult.Refused(HttpStatusCode.ServiceUnavailable, "tmdb_unconfigured", "服务器尚未配置 TMDB")
        }
        val request =
            tmdbProxyRequest(path, query)
                ?: return TmdbProxyResult.Refused(HttpStatusCode.Forbidden, "tmdb_request_not_allowed", "不支持的 TMDB 请求")
        cache.get(request.pathAndQuery, now())?.let { return TmdbProxyResult.Relayed(200, it, fromCache = true) }
        val pausedForMs = upstreamPausedUntil - now()
        if (pausedForMs > 0L) return rateLimited((pausedForMs + 999L) / 1_000L)
        // A permit caps how many TMDB bodies (up to MAX_UPSTREAM_BODY_BYTES each) are in memory at once.
        withTimeoutOrNull(upstreamQueueWaitMs) { upstreamPermits.acquire() }
            ?: return TmdbProxyResult.Refused(
                HttpStatusCode.ServiceUnavailable,
                "tmdb_busy",
                "TMDB 请求较多，请稍后再试",
                retryAfterSeconds = BUSY_RETRY_AFTER_SECONDS,
            )
        return try {
            forward(request)
        } finally {
            upstreamPermits.release()
        }
    }

    private suspend fun forward(request: TmdbProxyRequest): TmdbProxyResult {
        val response =
            try {
                upstream.get(request.pathAndQuery, token)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // The type is enough to tell a timeout from a reset or an oversized body.
                ServerLog.warn(
                    "tmdb_proxy_upstream_failed",
                    "endpoint" to request.label,
                    "reason" to failure::class.simpleName,
                )
                return unavailable()
            }
        val json = response.contentType?.trim()?.startsWith("application/json", ignoreCase = true) == true
        return when {
            response.status == 200 && json -> {
                cache.put(request.pathAndQuery, response.body, now() + request.ttlMs, now())
                TmdbProxyResult.Relayed(200, response.body, fromCache = false)
            }
            response.status in RELAYED_CLIENT_ERRORS && json ->
                TmdbProxyResult.Relayed(response.status, response.body, fromCache = false)
            response.status == 429 -> {
                val seconds =
                    (response.retryAfterSeconds ?: DEFAULT_UPSTREAM_RETRY_AFTER_SECONDS)
                        .coerceIn(1L, MAX_UPSTREAM_PAUSE_SECONDS)
                upstreamPausedUntil = maxOf(upstreamPausedUntil, now() + seconds * 1_000L)
                ServerLog.warn(
                    "tmdb_proxy_upstream_limited",
                    "endpoint" to request.label,
                    "retryAfterSeconds" to seconds,
                )
                rateLimited(seconds)
            }
            else -> {
                // 401/403 mean TMDB refused this server's token. Passing either on would read to
                // the app as its own session failing and send it round a pointless account refresh.
                ServerLog.warn("tmdb_proxy_upstream_failed", "endpoint" to request.label, "status" to response.status)
                unavailable()
            }
        }
    }

    private fun rateLimited(retryAfterSeconds: Long) =
        TmdbProxyResult.Refused(
            HttpStatusCode.TooManyRequests,
            "tmdb_rate_limited",
            "TMDB 请求过多，请稍后再试",
            retryAfterSeconds = retryAfterSeconds.coerceAtLeast(1L),
        )

    private fun unavailable() =
        TmdbProxyResult.Refused(HttpStatusCode.BadGateway, "tmdb_unavailable", "TMDB 暂时不可用，请稍后再试")

    companion object {
        /**
         * Per account, across all of its devices. A cold start with the calendar is about eighty
         * reads, none of them cached by the app, so this is two such bursts a minute.
         */
        val TMDB_ACCOUNT_RATE_POLICY = AccountRateLimitPolicy(tmdbProxyAttemptsPerWindow = 240)

        /** TMDB's missing title, bad parameter and invalid page answers are real answers too. */
        private val RELAYED_CLIENT_ERRORS = setOf(400, 404, 422)
        private const val MAX_CONCURRENT_UPSTREAM = 8
        private const val UPSTREAM_QUEUE_WAIT_MS = 8_000L
        private const val BUSY_RETRY_AFTER_SECONDS = 2L
        private const val DEFAULT_UPSTREAM_RETRY_AFTER_SECONDS = 10L
        private const val MAX_UPSTREAM_PAUSE_SECONDS = 120L
    }
}

/**
 * Bounded LRU of successful TMDB answers.
 *
 * Weight is the UTF-16 size of key and body, the most a String of them can occupy, so the bound
 * holds whatever mix of scripts the answers contain. One oversized season is forwarded but not
 * kept: it would push out hundreds of chart pages.
 */
internal class TmdbResponseCache(
    private val maxEntries: Int = MAX_CACHE_ENTRIES,
    private val maxWeight: Long = MAX_CACHE_WEIGHT,
    private val maxEntryWeight: Long = MAX_CACHEABLE_ENTRY_WEIGHT,
) {
    private class Entry(
        val body: String,
        val expiresAt: Long,
        val weight: Long,
    )

    private val entries = LinkedHashMap<String, Entry>(64, 0.75f, true)
    private var weight = 0L

    init {
        require(maxEntries > 0 && maxWeight > 0L && maxEntryWeight in 1L..maxWeight)
    }

    fun get(
        key: String,
        now: Long,
    ): String? =
        synchronized(entries) {
            val entry = entries[key] ?: return null
            if (now >= entry.expiresAt) {
                remove(key)
                return null
            }
            entry.body
        }

    fun put(
        key: String,
        body: String,
        expiresAt: Long,
        now: Long,
    ) {
        val entryWeight = (key.length + body.length) * 2L + ENTRY_OVERHEAD_BYTES
        synchronized(entries) {
            remove(key)
            if (entryWeight > maxEntryWeight || expiresAt <= now) return
            entries[key] = Entry(body, expiresAt, entryWeight)
            weight += entryWeight
            if (entries.size <= maxEntries && weight <= maxWeight) return
            // Expired answers go first; only then the least recently used live ones.
            entries.entries.removeAll { (_, entry) ->
                (now >= entry.expiresAt).also { expired -> if (expired) weight -= entry.weight }
            }
            val eldest = entries.entries.iterator()
            while ((entries.size > maxEntries || weight > maxWeight) && eldest.hasNext()) {
                weight -= eldest.next().value.weight
                eldest.remove()
            }
        }
    }

    internal fun size(): Int = synchronized(entries) { entries.size }

    internal fun weight(): Long = synchronized(entries) { weight }

    private fun remove(key: String) {
        entries.remove(key)?.let { weight -= it.weight }
    }

    private companion object {
        const val MAX_CACHE_ENTRIES = 2_048

        /** About a tenth of the service's 256 MiB heap. */
        const val MAX_CACHE_WEIGHT = 24L * 1024 * 1024
        const val MAX_CACHEABLE_ENTRY_WEIGHT = 1024L * 1024
        const val ENTRY_OVERHEAD_BYTES = 256L
    }
}

/** Fixed destination, no redirects, bounded body and deadlines; never logs the request. */
internal class JdkTmdbUpstream(
    private val base: String = TMDB_API_BASE,
) : TmdbUpstream {
    // Built on first use: most test applications install the route and never send a request.
    private val http by lazy {
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
    }

    override suspend fun get(
        pathAndQuery: String,
        bearer: String,
    ): TmdbUpstreamResponse =
        runInterruptible(Dispatchers.IO) {
            val request =
                HttpRequest
                    .newBuilder(URI(base + pathAndQuery))
                    .timeout(Duration.ofSeconds(12))
                    .header("Authorization", "Bearer $bearer")
                    .header("Accept", "application/json")
                    .header("User-Agent", "Yfuse/TmdbProxy")
                    .GET()
                    .build()
            val response = http.send(request, boundedStringBodyHandler(MAX_UPSTREAM_BODY_BYTES))
            val headers = response.headers()
            TmdbUpstreamResponse(
                status = response.statusCode(),
                body = response.body(),
                contentType = headers.firstValue("Content-Type").orElse(null),
                retryAfterSeconds =
                    headers
                        .firstValue("Retry-After")
                        .orElse(null)
                        ?.trim()
                        ?.toLongOrNull(),
            )
        }

    private companion object {
        const val TMDB_API_BASE = "https://api.themoviedb.org/3"

        /** A long-running show's season with every guest star is the largest read in the allowlist. */
        const val MAX_UPSTREAM_BODY_BYTES = 4 * 1024 * 1024
    }
}
