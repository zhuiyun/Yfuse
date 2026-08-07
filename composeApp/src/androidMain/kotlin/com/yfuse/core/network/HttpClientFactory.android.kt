package com.yfuse.core.network

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp

/**
 * Uses OkHttp's Android TLS stack so certificate-chain and hostname checks stay platform aware.
 *
 * Keep the engine's SSL socket factory, trust manager, and hostname verifier unconfigured: their
 * OkHttp defaults honor Android's Network Security Configuration and reject untrusted peers.
 */
actual fun embyHttpEngine(): HttpClientEngine = OkHttp.create()
