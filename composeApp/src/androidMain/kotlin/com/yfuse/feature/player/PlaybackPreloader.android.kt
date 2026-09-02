package com.yfuse.feature.player

import android.content.Context
import android.os.PowerManager
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import com.yfuse.BuildConfig
import com.yfuse.core.data.PlaybackNetworkClass
import com.yfuse.core.data.PlaybackPreferences
import com.yfuse.core.data.UserAgentPreferences
import com.yfuse.core.logging.AppLog
import com.yfuse.core.network.currentPlaybackNetworkClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Warms the beginning of a direct-play file into the SimpleCache shared by every engine.
 *
 * Only the direct URL is touched. Starting an HLS/progressive transcode speculatively would wake
 * server-side ffmpeg for something the user may never play and can leave unnecessary encodes
 * running. The bounded prefix follows source bitrate, and warmup is skipped on metered networks or
 * while battery saver is active.
 */
@OptIn(UnstableApi::class)
internal class AndroidPlaybackSourcePreloader(
    context: Context,
    private val playbackPreferences: PlaybackPreferences,
    private val userAgentPreferences: UserAgentPreferences,
) : PlaybackSourcePreloader {
    private val applicationContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()

    override fun preload(item: PlayerMediaItem): PlaybackSourcePreload {
        val source = item.persistentPlaybackCacheUrl() ?: return noOpPlaybackSourcePreload()
        if (playbackPreferences.videoCacheSize.value.bytes <= 0L) {
            return noOpPlaybackSourcePreload()
        }
        val networkClass = currentPlaybackNetworkClass()
        val powerSaveMode =
            (
                applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            )?.isPowerSaveMode == true
        if (
            !shouldWarmPlaybackCache(
                networkClass = networkClass,
                powerSaveMode = powerSaveMode,
                nativeOnlyRuntime = BuildConfig.YFUSE_NATIVE_ONLY_RUNTIME,
            )
        ) {
            return noOpPlaybackSourcePreload()
        }

        val cacheKey = secureMediaCacheKeyForUrl(source)
        if (jobs[cacheKey]?.isActive == true) return noOpPlaybackSourcePreload()
        val preloadBytes = playbackPreloadBytes(item.activeVersion?.sourceBitrateBps)
        val cancelled = AtomicBoolean(false)
        val writerRef = AtomicReference<CacheWriter?>()
        lateinit var job: Job
        job =
            scope.launch(start = CoroutineStart.LAZY) {
                val cacheBytes = playbackPreferences.videoCacheSize.value.bytes
                val handle = VideoCachePool.acquire(applicationContext, cacheBytes) ?: return@launch
                try {
                    val httpFactory =
                        DefaultHttpDataSource
                            .Factory()
                            .setAllowCrossProtocolRedirects(true)
                            .setConnectTimeoutMs(20_000)
                            .setReadTimeoutMs(20_000)
                            .apply {
                                userAgentPreferences.userAgent.value
                                    .trim()
                                    .takeIf(String::isNotEmpty)
                                    ?.let { value ->
                                        setDefaultRequestProperties(mapOf("User-Agent" to value))
                                    }
                            }
                    val upstream = DefaultDataSource.Factory(applicationContext, httpFactory)
                    val dataSource =
                        CacheDataSource
                            .Factory()
                            .setCache(handle.cache)
                            .setCacheKeyFactory(SecureMediaCacheKeyFactory)
                            .setUpstreamDataSourceFactory(upstream)
                            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                            .createDataSource()
                    val dataSpec =
                        DataSpec
                            .Builder()
                            .setUri(source)
                            .setPosition(0L)
                            .setLength(preloadBytes)
                            .build()

                    val writer = CacheWriter(dataSource, dataSpec, null, null)
                    writerRef.set(writer)
                    try {
                        writer.cache()
                    } finally {
                        writerRef.compareAndSet(writer, null)
                    }
                    if (cancelled.get()) return@launch
                    AppLog.info(
                        category = "feature.player",
                        event = "source_preloaded",
                        message = "Playback source prefix warmed into Media3 cache",
                        attributes =
                            mapOf(
                                "bytes" to preloadBytes.toString(),
                                "network" to networkClass.name,
                            ),
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (throwable: Throwable) {
                    AppLog.warning(
                        category = "feature.player",
                        event = "source_preload_failed",
                        message = "Playback source warmup failed; normal playback will continue",
                        throwable = throwable,
                    )
                } finally {
                    handle.close()
                }
            }
        val existing = jobs.putIfAbsent(cacheKey, job)
        if (existing != null) {
            job.cancel()
            return noOpPlaybackSourcePreload()
        }
        job.invokeOnCompletion { jobs.remove(cacheKey, job) }
        job.start()
        return PlaybackSourcePreload {
            cancelled.set(true)
            writerRef.getAndSet(null)?.cancel()
            if (jobs.remove(cacheKey, job)) job.cancel()
        }
    }
}

internal fun shouldWarmPlaybackCache(
    networkClass: PlaybackNetworkClass,
    powerSaveMode: Boolean,
    nativeOnlyRuntime: Boolean = false,
): Boolean =
    networkClass == PlaybackNetworkClass.Unmetered &&
        !powerSaveMode &&
        // The Media3 SimpleCache is consumed by compatibility engines. Native-only YCore owns a
        // separate validated range cache, so this warmup would merely compete with startup for the
        // same signed URL without contributing any bytes to the active reader.
        !nativeOnlyRuntime

internal fun playbackPreloadBytes(sourceBitrateBps: Int?): Long {
    val bytesForStartup =
        sourceBitrateBps
            ?.takeIf { it > 0 }
            ?.toLong()
            ?.times(PRELOAD_TARGET_SECONDS)
            ?.div(8L)
            ?: DEFAULT_PRELOAD_BYTES
    return bytesForStartup.coerceIn(MIN_PRELOAD_BYTES, MAX_PRELOAD_BYTES)
}

private const val PRELOAD_TARGET_SECONDS = 8L
private const val MIN_PRELOAD_BYTES = 4L * 1024L * 1024L
private const val DEFAULT_PRELOAD_BYTES = 8L * 1024L * 1024L
private const val MAX_PRELOAD_BYTES = 16L * 1024L * 1024L
