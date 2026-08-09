package com.yfuse

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.intercept.Interceptor
import coil3.memory.MemoryCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import com.russhwolf.settings.SharedPreferencesSettings
import com.yfuse.core.data.DiagnosticPreferences
import com.yfuse.core.account.AccountRepository
import com.yfuse.core.logging.DiagnosticLogStore
import com.yfuse.core.logging.AppLog
import com.yfuse.core.logging.SafeLogcatOutputGate
import com.yfuse.core.network.imageCacheKeyForUrl
import com.yfuse.core.util.imageCacheContext
import com.yfuse.core.offline.offlineApplicationContext
import com.yfuse.di.appModule
import com.yfuse.feature.player.AndroidPlaybackSourcePreloader
import com.yfuse.feature.player.PlaybackSourcePreloader
import com.yfuse.update.AppUpdateManager
import okio.Path.Companion.toOkioPath
import org.koin.core.context.startKoin
import org.koin.dsl.module

class YfuseApp : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
        imageCacheContext = this
        offlineApplicationContext = this
        val prefs = getSharedPreferences("yfuse", MODE_PRIVATE)
        // Before anything can reach the network: every Emby request carries the device id,
        // and it has to be the same one across launches for sessions to be reapable.
        initializeDeviceId(prefs)
        val settings = SharedPreferencesSettings(prefs)
        val diagnosticPreferences = DiagnosticPreferences(settings)
        SafeLogcatOutputGate.initialize(diagnosticPreferences)
        DiagnosticLogStore.initialize(this)
        AppLog.info("app", "initializing", "Initializing application dependencies")
        val koinApplication = startKoin {
            modules(
                appModule(
                    settings = settings,
                    appVersion = BuildConfig.VERSION_NAME,
                    diagnosticPreferences = diagnosticPreferences,
                ),
                module {
                    // Application-scoped so an update download survives the activity that
                    // started it, and so UpdateDownloadService can reach the same instance.
                    single { AppUpdateManager(this@YfuseApp, settings) }
                    // Detail pages prepare playback before the tap. The Android implementation
                    // warms direct-play bytes into the same Media3 cache used by ExoPlayer.
                    single<PlaybackSourcePreloader> {
                        AndroidPlaybackSourcePreloader(
                            context = this@YfuseApp,
                            playbackPreferences = get(),
                            userAgentPreferences = get(),
                        )
                    }
                },
            )
        }
        koinApplication.koin.get<AccountRepository>().start()
        // Built eagerly: it restores an interrupted download and starts watching the
        // foreground, both of which have to happen before the first screen appears.
        koinApplication.koin.get<AppUpdateManager>()
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
                            // Keep the authenticated URL as Coil's automatic in-memory identity,
                            // so different sessions cannot share one decoded entry. Only the disk
                            // identity is sanitized; request.data still reaches Ktor unchanged.
                            val safeRequest = request.newBuilder()
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
            // Deliberately no `crossfade`. 图片渐进加载 (§3.1) is owned by [FallbackImage],
            // which fades, unblurs and unscales as one movement and knows to skip all three
            // for a memory-cache hit. Coil's own crossfade ran underneath that as a second,
            // shorter fade on a different clock, and fired on cached images too.
            .build()
}
