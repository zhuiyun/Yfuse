package com.yfuse

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.SharedPreferences
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.intercept.Interceptor
import coil3.memory.MemoryCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import com.russhwolf.settings.SharedPreferencesSettings
import com.yfuse.core.account.AccountRepository
import com.yfuse.core.cast.initializeCastApplicationContext
import com.yfuse.core.data.AndroidCalendarLocalStore
import com.yfuse.core.data.DiagnosticPreferences
import com.yfuse.core.data.UserAgentPreferences
import com.yfuse.core.logging.AppLog
import com.yfuse.core.logging.DiagnosticLogStore
import com.yfuse.core.logging.SafeLogcatOutputGate
import com.yfuse.core.network.imageCacheKeyForUrl
import com.yfuse.core.offline.offlineApplicationContext
import com.yfuse.core.sync.playback.PlaybackSyncManager
import com.yfuse.core.util.androidAppContext
import com.yfuse.core.util.imageCacheContext
import com.yfuse.di.appModule
import com.yfuse.feature.calendar.scheduleCalendarReminderWork
import com.yfuse.feature.calendar.scheduleCalendarSyncWork
import com.yfuse.feature.player.AndroidNativeCrashMonitor
import com.yfuse.feature.player.AndroidPlaybackSourcePreloader
import com.yfuse.feature.player.PlaybackDiagnosticReportRegistry
import com.yfuse.feature.player.PlaybackReportingCoordinator
import com.yfuse.feature.player.PlaybackSourcePreloader
import com.yfuse.feature.player.notifyPlaybackAppBackground
import com.yfuse.update.AppUpdateManager
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okio.Path.Companion.toOkioPath
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.dsl.module

class YfuseApp :
    Application(),
    SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        imageCacheContext = this
        androidAppContext = this
        offlineApplicationContext = this
        initializeCastApplicationContext(this)
        val prefs = getSharedPreferences("yfuse", MODE_PRIVATE)
        clearLegacyCredentialCaches(prefs)
        // Before anything can reach the network: every Emby request carries the device id,
        // and it has to be the same one across launches for sessions to be reapable.
        initializeDeviceId(prefs)
        val settings = SharedPreferencesSettings(prefs)
        val diagnosticPreferences = DiagnosticPreferences(settings)
        SafeLogcatOutputGate.initialize(diagnosticPreferences)
        DiagnosticLogStore.initialize(this)
        // Native tombstones are available only after the dead process restarts. Consume the
        // previous exit before constructing any new playback backend.
        AndroidNativeCrashMonitor.initialize(this)
        PlaybackDiagnosticReportRegistry.initialize(this)
        AppLog.info("app", "initializing", "Initializing application dependencies")
        val koinApplication =
            startKoin {
                modules(
                    appModule(
                        settings = settings,
                        appVersion = BuildConfig.VERSION_NAME,
                        diagnosticPreferences = diagnosticPreferences,
                        calendarLocalStore = AndroidCalendarLocalStore(this@YfuseApp),
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
        // Local playback state is always available; cloud work begins automatically once the
        // account repository restores an authenticated session.
        koinApplication.koin.get<PlaybackSyncManager>().start()
        // Restores reporting work even when the previous process died after persisting an event.
        // Each server lane also keeps its foreground fast-path while WorkManager waits for a
        // connected network and survives this process being stopped again.
        koinApplication.koin.get<PlaybackReportingCoordinator>().flushPending()
        scheduleCalendarReminderWork(this)
        scheduleCalendarSyncWork(this)
        // Built eagerly: it restores an interrupted download and starts watching the
        // foreground, both of which have to happen before the first screen appears.
        koinApplication.koin.get<AppUpdateManager>()
    }

    /** Persist the newest sampled position before Android backgrounds the whole UI. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            notifyPlaybackAppBackground()
        }
    }

    // Keep decoded images hot in memory and original responses on disk. This is
    // shared by every poster/backdrop and can be cleared from Profile.
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader
            .Builder(context)
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
                            val safeRequest =
                                request
                                    .newBuilder()
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
                            // Resolved here rather than captured at build time: Coil constructs
                            // this client once per process, and the UA can change at any point
                            // from 设置 afterwards.
                            val userAgent = GlobalContext.get().get<UserAgentPreferences>()
                            HttpClient {
                                install(HttpTimeout) {
                                    requestTimeoutMillis = 15_000
                                    connectTimeoutMillis = 10_000
                                    socketTimeoutMillis = 15_000
                                }
                                // Many Emby deployments sit behind an Nginx reverse proxy that
                                // gates `/Items/{id}/Images/...` by User-Agent; the default
                                // `Ktor/x.x` UA gets a 403 and images silently fail to load
                                // even though the URL and api_key are correct.
                                //
                                // This has to be the *same* UA the API client sends. A user who
                                // set a custom one did it to get past exactly such a proxy, and
                                // images 403ing while every API call succeeds is the hardest
                                // shape of that failure to diagnose. Read per request, the way
                                // `createEmbyClient` does, so a change applies without a restart.
                                defaultRequest {
                                    header(HttpHeaders.UserAgent, userAgent.userAgent.value)
                                }
                                expectSuccess = false
                            }
                        },
                    ),
                )
            }.memoryCache {
                MemoryCache
                    .Builder()
                    .maxSizePercent(context, percent = 0.20)
                    .build()
            }.diskCache {
                DiskCache
                    .Builder()
                    .directory(cacheDir.resolve("image_cache_v2").toOkioPath())
                    .maxSizeBytes(256L * 1024L * 1024L)
                    .build()
            }
            // Deliberately no `crossfade`. 图片渐进加载 (§3.1) is owned by [FallbackImage],
            // which fades, unblurs and unscales as one movement and knows to skip all three
            // for a memory-cache hit. Coil's own crossfade ran underneath that as a second,
            // shorter fade on a different clock, and fired on cached images too.
            .build()

    /** v1 cache indexes used authenticated URLs, or were not isolated between Emby accounts. */
    private fun clearLegacyCredentialCaches(prefs: SharedPreferences) {
        if (prefs.getBoolean(KEY_LEGACY_CREDENTIAL_CACHES_CLEARED, false)) return
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val imageCleared = cacheDir.resolve("image_cache").deleteRecursively()
            val videoCleared = cacheDir.resolve("video_cache").deleteRecursively()
            if (imageCleared && videoCleared) {
                prefs.edit().putBoolean(KEY_LEGACY_CREDENTIAL_CACHES_CLEARED, true).apply()
            }
        }
    }

    private companion object {
        const val KEY_LEGACY_CREDENTIAL_CACHES_CLEARED =
            "security.legacy_credential_caches_cleared_v2"
    }
}
