package com.yfuse.core.network

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import java.util.concurrent.TimeUnit

/**
 * Uses OkHttp's Android TLS stack so certificate-chain and hostname checks stay platform aware.
 *
 * Keep the engine's SSL socket factory, trust manager, and hostname verifier unconfigured: their
 * OkHttp defaults honor Android's Network Security Configuration and reject untrusted peers.
 */
internal const val EMBY_MAX_CONCURRENT_REQUESTS = 8
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
            dispatcher(embyRequestDispatcher())
            connectionPool(sharedOriginConnectionPool)
        }
    }
