package com.yfuse.core.migration

import com.yfuse.core.network.embyRequestDispatcher
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.ConnectionPool
import okhttp3.Dispatcher

/**
 * Ktor closes an OkHttp engine by evicting its pool and stopping its dispatcher. A page-owned
 * migration client must therefore never use the application-wide API/media connection pool.
 * Leave TLS configuration untouched so Android's trust and hostname checks remain in force.
 */
internal actual fun migrationRelayHttpEngine(): HttpClientEngine =
    migrationRelayHttpEngine(ConnectionPool(), embyRequestDispatcher())

/** The caller supplies dedicated resources; closing the engine takes ownership of their cleanup. */
internal fun migrationRelayHttpEngine(
    connectionPool: ConnectionPool,
    dispatcher: Dispatcher,
): HttpClientEngine =
    OkHttp.create {
        config {
            dispatcher(dispatcher)
            connectionPool(connectionPool)
        }
    }
