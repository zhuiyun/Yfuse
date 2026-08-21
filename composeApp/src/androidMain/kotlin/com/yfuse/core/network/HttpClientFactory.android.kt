package com.yfuse.core.network

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.Dispatcher

/**
 * Uses OkHttp's Android TLS stack so certificate-chain and hostname checks stay platform aware.
 *
 * Keep the engine's SSL socket factory, trust manager, and hostname verifier unconfigured: their
 * OkHttp defaults honor Android's Network Security Configuration and reject untrusted peers.
 */
internal const val EMBY_MAX_CONCURRENT_REQUESTS = 8
internal const val EMBY_MAX_CONCURRENT_REQUESTS_PER_HOST = 4

internal fun embyRequestDispatcher(): Dispatcher =
    Dispatcher().apply {
        maxRequests = EMBY_MAX_CONCURRENT_REQUESTS
        maxRequestsPerHost = EMBY_MAX_CONCURRENT_REQUESTS_PER_HOST
    }

actual fun embyHttpEngine(): HttpClientEngine =
    OkHttp.create {
        config { dispatcher(embyRequestDispatcher()) }
    }
