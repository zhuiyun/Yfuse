package com.yfuse.tv

import android.app.Application
import android.content.ComponentCallbacks2
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.intercept.Interceptor
import coil3.memory.MemoryCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import com.russhwolf.settings.SharedPreferencesSettings
import com.yfuse.BuildConfig
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
import com.yfuse.feature.player.AndroidNativeCrashMonitor
import com.yfuse.feature.player.AndroidPlaybackSourcePreloader
import com.yfuse.feature.player.PlaybackDiagnosticReportRegistry
import com.yfuse.feature.player.PlaybackRemotePolicyRegistry
import com.yfuse.feature.player.PlaybackReportingCoordinator
import com.yfuse.feature.player.PlaybackSourcePreloader
import com.yfuse.feature.player.notifyPlaybackAppBackground
import com.yfuse.initializeDeviceId
import com.yfuse.tv.integration.CastConnectReceiverBridge
import com.yfuse.tv.integration.TvContinueWatchingRuntime
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okio.Path.Companion.toOkioPath
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.dsl.module

/**
 * TV-safe application composition root.
 *
 * It starts the same server, account, playback and progress graph consumed by the phone, but does
 * not register AppUpdateManager, QR camera flows, exact-alarm reminders, or phone icon switching.
 */
class TvApplication :
    Application(),
    SingletonImageLoader.Factory {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var graph: TvApplicationGraph
        private set

    override fun onCreate() {
        super.onCreate()
        imageCacheContext = this
        androidAppContext = this
        offlineApplicationContext = this
        initializeCastApplicationContext(this)

        val preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
        initializeDeviceId(preferences)
        val settings = SharedPreferencesSettings(preferences)
        val diagnostics = DiagnosticPreferences(settings)
        SafeLogcatOutputGate.initialize(diagnostics)
        DiagnosticLogStore.initialize(this)
        AndroidNativeCrashMonitor.initialize(this)
        PlaybackRemotePolicyRegistry.initialize(this)
        PlaybackDiagnosticReportRegistry.initialize(this)

        val koinApplication =
            startKoin {
                modules(
                    appModule(
                        settings = settings,
                        appVersion = BuildConfig.VERSION_NAME,
                        diagnosticPreferences = diagnostics,
                        calendarLocalStore = AndroidCalendarLocalStore(this@TvApplication),
                    ),
                    module {
                        single<PlaybackSourcePreloader> {
                            AndroidPlaybackSourcePreloader(
                                context = this@TvApplication,
                                playbackPreferences = get(),
                                userAgentPreferences = get(),
                            )
                        }
                    },
                )
            }
        graph = TvApplicationGraph(koinApplication.koin)

        koinApplication.koin.get<AccountRepository>().start()
        koinApplication.koin.get<PlaybackSyncManager>().start()
        koinApplication.koin.get<PlaybackReportingCoordinator>().flushPending()
        TvContinueWatchingRuntime.refresh(this)
        applicationScope.launch {
            graph.serverRegistry.data
                .map { state ->
                    state.servers.map { server ->
                        Triple(server.kind, server.id, server.userId)
                    }
                }.distinctUntilChanged()
                .drop(1)
                .collect {
                    // Removal, logout, profile switch and server-id migration all reconcile the
                    // old system rows immediately; the worker itself owns the atomic deletion.
                    TvContinueWatchingRuntime.refresh(this@TvApplication)
                }
        }

        // Cast's official guidance requires process lifecycle ownership when an app has more than
        // one Activity. This keeps the receiver alive while control passes between browsing and
        // PlayerActivity, and stops it only after the whole TV app leaves the foreground.
        CastConnectReceiverBridge.initialize(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    graph.setAppForeground(true)
                    CastConnectReceiverBridge.start()
                }

                override fun onStop(owner: LifecycleOwner) {
                    graph.setAppForeground(false)
                    CastConnectReceiverBridge.stop()
                }
            },
        )
        AppLog.info(
            category = "tv.app",
            event = "initialized",
            message = "Android TV application graph initialized",
            attributes = mapOf("packageProfile" to BuildConfig.YFUSE_PACKAGE_PROFILE),
        )
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            notifyPlaybackAppBackground()
        }
    }

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
                            chain
                                .withRequest(
                                    request
                                        .newBuilder()
                                        .diskCacheKey(safeCacheKey)
                                        .build(),
                                ).proceed()
                        } else {
                            chain.proceed()
                        }
                    },
                )
                add(
                    KtorNetworkFetcherFactory(
                        httpClient = {
                            val userAgent = GlobalContext.get().get<UserAgentPreferences>()
                            HttpClient {
                                install(HttpTimeout) {
                                    requestTimeoutMillis = 15_000
                                    connectTimeoutMillis = 10_000
                                    socketTimeoutMillis = 15_000
                                }
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
                    .directory(cacheDir.resolve("tv_image_cache_v1").toOkioPath())
                    .maxSizeBytes(256L * 1024L * 1024L)
                    .build()
            }.build()

    private companion object {
        const val PREFERENCES_NAME = "yfuse"
    }
}
