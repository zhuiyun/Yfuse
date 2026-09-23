package com.yfuse.feature.player

import android.content.Context
import android.os.PowerManager
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import com.yfuse.core.data.PlaybackNetworkClass
import com.yfuse.core.data.PlaybackPreferences
import com.yfuse.core.data.UserAgentPreferences
import com.yfuse.core.logging.AppLog
import com.yfuse.core.network.currentPlaybackNetworkClass
import com.yfuse.core2.android.AndroidPlaybackMemoryBudget
import com.yfuse.core2.android.PlaybackBufferKind
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
import com.yfuse.core.platform.AppBuildConfig as BuildConfig

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

    override fun preload(item: PlayerMediaItem): PlaybackSourcePreload = preload(item, 0L)

    override fun preload(
        item: PlayerMediaItem,
        startPositionMs: Long,
    ): PlaybackSourcePreload = preload(item, startPositionMs, null)

    override fun preload(
        item: PlayerMediaItem,
        startPositionMs: Long,
        tracks: com.yfuse.core.data.PlaybackTrackRequest.Tracks?,
    ): PlaybackSourcePreload {
        val source = item.persistentPlaybackCacheUrl() ?: run {
            logSourcePreloadSkipped("no_persistent_source")
            return noOpPlaybackSourcePreload()
        }
        if (playbackPreferences.videoCacheSize.value.bytes <= 0L) {
            logSourcePreloadSkipped("cache_disabled")
            return noOpPlaybackSourcePreload()
        }
        val networkClass = currentPlaybackNetworkClass()
        val powerSaveMode =
            (
                applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            )?.isPowerSaveMode == true
        if (networkClass == PlaybackNetworkClass.Unmetered &&
            !powerSaveMode &&
            (
                BuildConfig.YFUSE_NATIVE_ONLY_RUNTIME ||
                    shouldUseCore2Trial(
                        enabled = playbackPreferences.core2TrialEnabled.value,
                        engineSelection = playbackPreferences.engineSelection.value,
                        crashBlocked = false,
                    )
            )
        ) {
            return com.yfuse.core2.android.AndroidCurrentItemPreparation.preload(
                applicationContext,
                item,
                startPositionMs,
                userAgentPreferences.userAgent.value,
                playbackPreferences.videoCacheSize.value.bytes,
                initialTrackSelection = item.initialPlaybackTracks(playbackPreferences, tracks),
            )
        }
        if (
            !shouldWarmPlaybackCache(
                networkClass = networkClass,
                powerSaveMode = powerSaveMode,
                nativeOnlyRuntime = BuildConfig.YFUSE_NATIVE_ONLY_RUNTIME,
                core2OwnsPlayback =
                    shouldUseCore2Trial(
                        enabled = playbackPreferences.core2TrialEnabled.value,
                        engineSelection = playbackPreferences.engineSelection.value,
                        // Not known here. Treating a crash-blocked trial as owning playback only
                        // skips a warmup, which is the safe direction: the alternative downloads
                        // a prefix the active engine may never read.
                        crashBlocked = false,
                    ),
            )
        ) {
            logSourcePreloadSkipped(
                when {
                    networkClass != PlaybackNetworkClass.Unmetered -> "metered_or_offline"
                    powerSaveMode -> "power_save"
                    else -> "engine_does_not_use_cache"
                },
            )
            return noOpPlaybackSourcePreload()
        }

        val cacheKey = secureMediaCacheKeyForUrl(source)
        if (jobs[cacheKey]?.isActive == true) {
            logSourcePreloadSkipped("already_running")
            return noOpPlaybackSourcePreload()
        }
        val preloadBytes = playbackPreloadBytes(item.activeVersion?.sourceBitrateBps)
        val cancelled = AtomicBoolean(false)
        val writerRef = AtomicReference<CacheWriter?>()
        lateinit var job: Job
        job =
            scope.launch(start = CoroutineStart.LAZY) {
                val memory = AndroidPlaybackMemoryBudget.acquire(PlaybackBufferKind.Preload, 128L * 1024L)
                if (memory.limitBytes < 32L * 1024L) {
                    logSourcePreloadSkipped("memory_pressure")
                    memory.close()
                    return@launch
                }
                val cacheBytes = playbackPreferences.videoCacheSize.value.bytes
                val handle =
                    VideoCachePool.acquire(applicationContext, cacheBytes) ?: run {
                        logSourcePreloadSkipped("cache_unavailable")
                        memory.close()
                        return@launch
                    }
                try {
                    val httpFactory =
                        PlaybackHttpDataSource.factory(userAgentPreferences.userAgent.value)
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

                    val buffer = ByteArray(memory.limitBytes.coerceAtMost(128L * 1024L).toInt())
                    val writer =
                        CacheWriter(dataSource, dataSpec, buffer) { _, _, _ ->
                            AndroidPlaybackMemoryBudget.refreshPressure()
                            if (memory.limitBytes < buffer.size || cancelled.get()) writerRef.get()?.cancel()
                        }
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
                    memory.close()
                }
            }
        val existing = jobs.putIfAbsent(cacheKey, job)
        if (existing != null) {
            logSourcePreloadSkipped("already_running")
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

private fun logSourcePreloadSkipped(reason: String) {
    AppLog.info(
        category = "feature.player",
        event = "source_preload_skipped",
        message = "Optional source preload was skipped",
        attributes = mapOf("reason" to reason),
    )
}

internal fun shouldWarmPlaybackCache(
    networkClass: PlaybackNetworkClass,
    powerSaveMode: Boolean,
    nativeOnlyRuntime: Boolean = false,
    core2OwnsPlayback: Boolean = false,
): Boolean =
    networkClass == PlaybackNetworkClass.Unmetered &&
        !powerSaveMode &&
        // The Media3 SimpleCache is consumed by compatibility engines. YCore owns a separate
        // validated range cache, so whenever it is the engine that will actually play this item
        // the warmup contributes no bytes to the reader that runs - it only competes with startup
        // for the same signed URL.
        //
        // [nativeOnlyRuntime] is a build flag and was the only thing checked here, but the Core2
        // trial is on by default: an ordinary full build still hands playback to YCore, and this
        // warmup was downloading a prefix nothing would ever read, on the connection the first
        // frame was waiting for.
        !nativeOnlyRuntime &&
        !core2OwnsPlayback

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
