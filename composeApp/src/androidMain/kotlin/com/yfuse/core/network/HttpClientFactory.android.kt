package com.yfuse.core.network

import com.yfuse.app.ProductSession
import com.yfuse.core.logging.AppLog
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Connection
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.EventListener
import okhttp3.Response
import org.koin.core.context.GlobalContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Whether the app left the foreground at any point at or after [sinceEpochMs].
 *
 * A backgrounded process can be frozen outright - no code runs at all until it thaws - so a
 * request that "ran" 41-86 s can really be a few real seconds plus a long frozen gap that only
 * ends once the app returns to the foreground. Sampling the foreground flag once, at either end
 * of the measured interval, misses exactly that case: both ends can read as foreground while
 * everything in between was not. A transition timestamp does not.
 */
internal fun appLeftForegroundSince(
    sinceEpochMs: Long,
    currentlyForeground: Boolean,
    lastTransitionEpochMs: Long,
): Boolean = !currentlyForeground || lastTransitionEpochMs >= sinceEpochMs

/**
 * Re-times [ProductSession.foreground] transitions in epoch milliseconds so a measured request
 * interval can ask [appLeftForegroundSince]. `drop(1)` skips the replay of the flow's current
 * value on subscription - that is not a transition, and counting it as one would flag the very
 * first request timed after the app starts regardless of when it actually ran.
 */
private object EmbyRequestForegroundTimeline {
    @Volatile private var currentlyForeground = true

    @Volatile private var lastTransitionEpochMs = 0L
    private var started = false

    @Synchronized
    private fun ensureStarted() {
        if (started) return
        started = true
        // No session yet (e.g. a very early request during startup) leaves the timeline at its
        // default - foreground, no known transition - which never flags a sample as backgrounded.
        runCatching {
            val session = GlobalContext.get().get<ProductSession>()
            currentlyForeground = session.foreground.value
            session.scope.launch {
                session.foreground.drop(1).collect { value ->
                    currentlyForeground = value
                    lastTransitionEpochMs = System.currentTimeMillis()
                }
            }
        }
    }

    fun leftForegroundSince(sinceEpochMs: Long): Boolean {
        ensureStarted()
        return appLeftForegroundSince(sinceEpochMs, currentlyForeground, lastTransitionEpochMs)
    }
}

/**
 * Uses OkHttp's Android TLS stack so certificate-chain and hostname checks stay platform aware.
 *
 * Keep the engine's SSL socket factory, trust manager, and hostname verifier unconfigured: their
 * OkHttp defaults honor Android's Network Security Configuration and reject untrusted peers.
 *
 * The per-host cap is what protects each server. The global cap only decides whether one server's
 * slow calls can hold another server's slots: at 8, a cold start with several timing-out or
 * rejected servers ran 8 calls with 12 queued, and the default server's detail request waited
 * 1.5-4 s before reaching the network. Room for six hosts keeps those servers in their own lanes.
 */
internal const val EMBY_MAX_CONCURRENT_REQUESTS = 24
internal const val EMBY_MAX_CONCURRENT_REQUESTS_PER_HOST = 4

/**
 * One pool of live connections for API traffic and for YCore's media byte ranges.
 *
 * Both talk to the same origin, and the API gets there first: negotiating playback capabilities
 * completes a DNS lookup, a TCP connect and a TLS handshake seconds before the first byte range is
 * asked for. Pooling them separately threw that away and made the media transport repeat all three
 * while the user waited on a black screen. Neither client configures its SSL socket factory,
 * hostname verifier or proxy, so both describe the same OkHttp address and can share a connection.
 *
 * The pool holds more idle connections than OkHttp's default because playback runs several
 * concurrent range prefetches alongside ordinary API calls against a single host.
 */
internal val sharedOriginConnectionPool: ConnectionPool by lazy {
    ConnectionPool(
        maxIdleConnections = MAX_IDLE_ORIGIN_CONNECTIONS,
        keepAliveDuration = ORIGIN_CONNECTION_KEEP_ALIVE_MINUTES,
        timeUnit = TimeUnit.MINUTES,
    )
}

private const val MAX_IDLE_ORIGIN_CONNECTIONS = 8
private const val ORIGIN_CONNECTION_KEEP_ALIVE_MINUTES = 5L

internal fun embyRequestDispatcher(): Dispatcher =
    Dispatcher().apply {
        maxRequests = EMBY_MAX_CONCURRENT_REQUESTS
        maxRequestsPerHost = EMBY_MAX_CONCURRENT_REQUESTS_PER_HOST
    }

actual fun embyHttpEngine(): HttpClientEngine =
    OkHttp.create {
        config {
            val apiDispatcher = embyRequestDispatcher()
            dispatcher(apiDispatcher)
            connectionPool(sharedOriginConnectionPool)
            eventListenerFactory(EventListener.Factory { call -> EmbyApiRequestTiming(call, apiDispatcher) })
        }
    }

/** Only anonymous timing and request class reach diagnostics; never a URL, path or credential. */
private class EmbyApiRequestTiming(
    call: Call,
    private val dispatcher: Dispatcher,
) : EventListener() {
    @Volatile private var startedNs = System.nanoTime()

    @Volatile private var startedEpochMs = System.currentTimeMillis()
    private val group =
        call.request().url.encodedPath.let { path ->
            when {
                path.endsWith("/PlaybackInfo", ignoreCase = true) -> "playback_info"
                path.contains(
                    "/Shows/",
                    ignoreCase = true,
                ) &&
                    path.endsWith("/Episodes", ignoreCase = true) -> "episodes"
                path.contains("/Items/", ignoreCase = true) -> "item_detail"
                else -> "other"
            }
        }

    @Volatile private var queuedAtStart = 0

    @Volatile private var runningAtStart = 0
    private val finished = AtomicBoolean(false)

    @Volatile private var firstNetworkNs = 0L

    @Volatile private var connectionNs = 0L

    @Volatile private var requestHeadersNs = 0L

    @Volatile private var responseHeadersNs = 0L

    @Volatile private var responseBodyNs = 0L

    @Volatile private var responseBytes = -1L

    @Volatile private var statusCode = 0

    override fun callStart(call: Call) {
        startedNs = System.nanoTime()
        startedEpochMs = System.currentTimeMillis()
        queuedAtStart = dispatcher.queuedCallsCount()
        runningAtStart = dispatcher.runningCallsCount()
    }

    override fun dnsStart(
        call: Call,
        domainName: String,
    ) = markNetworkStart()

    override fun connectStart(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
    ) = markNetworkStart()

    override fun connectionAcquired(
        call: Call,
        connection: Connection,
    ) {
        markNetworkStart()
        connectionNs = System.nanoTime()
    }

    override fun requestHeadersStart(call: Call) {
        markNetworkStart()
        requestHeadersNs = System.nanoTime()
    }

    override fun responseHeadersEnd(
        call: Call,
        response: Response,
    ) {
        responseHeadersNs = System.nanoTime()
        statusCode = response.code
    }

    override fun responseBodyEnd(
        call: Call,
        byteCount: Long,
    ) {
        responseBodyNs = System.nanoTime()
        responseBytes = byteCount
    }

    override fun callEnd(call: Call) = finish("completed")

    override fun callFailed(
        call: Call,
        ioe: IOException,
    ) = finish("failed")

    private fun markNetworkStart() {
        if (firstNetworkNs == 0L) firstNetworkNs = System.nanoTime()
    }

    private fun finish(outcome: String) {
        if (!finished.compareAndSet(false, true)) return
        val endedNs = System.nanoTime()
        val totalMs = (endedNs - startedNs) / 1_000_000L
        if (totalMs < 500L && group == "other" && outcome == "completed") return

        fun elapsed(
            from: Long,
            to: Long,
        ): String = if (from > 0L && to >= from) ((to - from) / 1_000_000L).toString() else "unavailable"
        AppLog.info(
            "network.emby",
            "api_request_timing",
            "Emby API request timing",
            mapOf(
                "group" to group,
                "outcome" to outcome,
                "status" to statusCode.takeIf { it > 0 }?.toString().orEmpty(),
                "totalMs" to totalMs.toString(),
                // Includes OkHttp dispatch scheduling; it is not a pure queue-wait measurement.
                "beforeNetworkMs" to elapsed(startedNs, firstNetworkNs),
                "connectionMs" to elapsed(firstNetworkNs, connectionNs),
                "responseHeadersMs" to elapsed(requestHeadersNs, responseHeadersNs),
                "responseBodyMs" to elapsed(responseHeadersNs, responseBodyNs),
                "responseBytes" to responseBytes.takeIf { it >= 0L }?.toString().orEmpty(),
                "queuedAtStart" to queuedAtStart.toString(),
                "runningAtStart" to runningAtStart.toString(),
                "queuedAtEnd" to dispatcher.queuedCallsCount().toString(),
                // A backgrounded, frozen process can hold this call open for minutes without it
                // being a stall; readers should not average it in with ones that ran live.
                "appBackgrounded" to EmbyRequestForegroundTimeline.leftForegroundSince(startedEpochMs).toString(),
            ),
        )
    }
}
