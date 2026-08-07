package com.yfuse

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.intercept.Interceptor
import coil3.memory.MemoryCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import com.russhwolf.settings.SharedPreferencesSettings
import com.yfuse.core.logging.DiagnosticLogStore
import com.yfuse.core.logging.AppLog
import com.yfuse.core.network.imageCacheKeyForUrl
import com.yfuse.core.util.imageCacheContext
import com.yfuse.core.offline.offlineApplicationContext
import com.yfuse.di.appModule
import okio.Path.Companion.toOkioPath
import org.koin.core.context.startKoin

class YfuseApp : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
        DiagnosticLogStore.initialize(this)
        AppLog.info("app", "initializing", "Initializing application dependencies")
        imageCacheContext = this
        offlineApplicationContext = this
        val prefs = getSharedPreferences("yfuse", MODE_PRIVATE)
        // Before anything can reach the network: every Emby request carries the device id,
        // and it has to be the same one across launches for sessions to be reapable.
        initializeDeviceId(prefs)
        val settings = SharedPreferencesSettings(prefs)
        startKoin {
            modules(appModule(settings, BuildConfig.VERSION_NAME))
        }
    }

    // Keep decoded images hot in memory and original responses on disk. This is
    // shared by every poster/backdrop and can be cleared from Profile.
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(
                    Interceptor { chain ->
                        val request = chain.request
                        val requestUrl = request.data as? String
                        val safeCacheKey = requestUrl?.let(::imageCacheKeyForUrl)
                        if (requestUrl != null && safeCacheKey != requestUrl) {
                            // Only the cache identity changes. request.data remains the original
                            // authenticated URL used by KtorNetworkFetcher.
                            val safeRequest = request.newBuilder()
                                .memoryCacheKey(safeCacheKey)
                                .diskCacheKey(safeCacheKey)
                                .build()
                            chain.withRequest(safeRequest).proceed()
                        } else {
                            chain.proceed()
                        }
                    },
                )
                add(
                    KtorNetworkFetcherFactory(
                        httpClient = {
                            io.ktor.client.HttpClient {
                                // Many Emby deployments sit behind an Nginx reverse proxy that
                                // gates `/Items/{id}/Images/...` by User-Agent; the default
                                // `Ktor/x.x` UA gets a 403 and images silently fail to load
                                // even though the URL and api_key are correct. Mirror the
                                // app's stock Emby UA so the proxy lets the request through.
                                install(io.ktor.client.plugins.HttpTimeout) {
                                    requestTimeoutMillis = 15_000
                                    connectTimeoutMillis = 10_000
                                    socketTimeoutMillis = 15_000
                                }
                                install(io.ktor.client.plugins.UserAgent) {
                                    agent = com.yfuse.core.network.DEFAULT_EMBY_USER_AGENT
                                }
                                expectSuccess = false
                            }
                        },
                    ),
                )
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, percent = 0.20)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(256L * 1024L * 1024L)
                    .build()
            }
            .crossfade(true)
            .build()
}
