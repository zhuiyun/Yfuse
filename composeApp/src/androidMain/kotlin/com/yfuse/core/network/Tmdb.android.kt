package com.yfuse.core.network

import com.yfuse.core.util.androidAppContext
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.Cache
import java.io.File
import com.yfuse.core.platform.AppBuildConfig as BuildConfig

actual fun tmdbToken(): String = BuildConfig.TMDB_TOKEN

actual fun tmdbHttpEngine(): HttpClientEngine = tmdbHttpEngine { tmdbResponseCache }

/**
 * [responseCache] is read when OkHttp builds its client for the first request, on a network thread,
 * so the cache directory is never touched by whoever constructs the engine - the main thread at launch.
 */
internal fun tmdbHttpEngine(responseCache: () -> Cache?): HttpClientEngine =
    OkHttp.create {
        config {
            dispatcher(embyRequestDispatcher())
            connectionPool(sharedOriginConnectionPool)
            responseCache()?.let { cache(it) }
        }
    }

// One per process: OkHttp forbids two caches on one directory, and the engine builds a client per
// distinct timeout configuration.
private val tmdbResponseCache: Cache? by lazy {
    androidAppContext?.let { context -> Cache(File(context.cacheDir, TMDB_CACHE_DIRECTORY), TMDB_CACHE_MAX_BYTES) }
}

private const val TMDB_CACHE_DIRECTORY = "http-tmdb"
private const val TMDB_CACHE_MAX_BYTES = 20L * 1024L * 1024L
