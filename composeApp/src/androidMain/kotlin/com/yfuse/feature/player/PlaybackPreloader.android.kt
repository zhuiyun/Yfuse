package com.yfuse.feature.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import com.yfuse.core.data.PlaybackPreferences
import com.yfuse.core.data.UserAgentPreferences
import com.yfuse.core.data.ThemePreferences
import com.yfuse.core.model.PlayerEngine
import com.yfuse.core.logging.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Warms the beginning of a direct-play file into the same SimpleCache ExoPlayer uses.
 *
 * Only the direct URL is touched. Starting an HLS/progressive transcode speculatively would wake
 * server-side ffmpeg for something the user may never play and can leave unnecessary encodes
 * running. 16 MiB is enough to cover ExoPlayer's initial time buffer for ordinary sources while
 * remaining bounded for a detail page the user only browses.
 */
@OptIn(UnstableApi::class)
internal class AndroidPlaybackSourcePreloader(
    context: Context,
    private val playbackPreferences: PlaybackPreferences,
    private val userAgentPreferences: UserAgentPreferences,
    private val themePreferences: ThemePreferences,
) : PlaybackSourcePreloader {

    private val applicationContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()

    override fun preload(url: String) {
        val source = url.trim()
        if (source.isEmpty()) return
        // Only ExoPlayer consumes VideoCachePool. Warming 16 MiB for MPV/MDK performs the
        // network work twice and cannot make their native demuxers start any faster.
        if (themePreferences.engine.value != PlayerEngine.Exo) return
        // Cache-off is an explicit user choice. Avoid even opening the stream, and also avoid
        // retaining one completed bookkeeping Job per browsed detail page.
        if (playbackPreferences.videoCacheSize.value.bytes <= 0L) return
        if (jobs[source]?.isActive == true) return

        val job = scope.launch {
            val cacheBytes = playbackPreferences.videoCacheSize.value.bytes
            val handle = VideoCachePool.acquire(applicationContext, cacheBytes) ?: return@launch
            try {
                val httpFactory = DefaultHttpDataSource.Factory()
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
                val dataSource = CacheDataSource.Factory()
                    .setCache(handle.cache)
                    .setCacheKeyFactory(SecureMediaCacheKeyFactory)
                    .setUpstreamDataSourceFactory(upstream)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                    .createDataSource()
                val dataSpec = DataSpec.Builder()
                    .setUri(source)
                    .setPosition(0L)
                    .setLength(PRELOAD_BYTES)
                    .build()

                CacheWriter(dataSource, dataSpec, null, null).cache()
                AppLog.info(
                    category = "feature.player",
                    event = "source_preloaded",
                    message = "Playback source prefix warmed into Media3 cache",
                    attributes = mapOf("bytes" to PRELOAD_BYTES.toString()),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                // Preload is opportunistic. The normal player path remains authoritative and
                // will surface a useful playback/network error if the source is actually bad.
                AppLog.warning(
                    category = "feature.player",
                    event = "source_preload_failed",
                    message = "Playback source warmup failed; normal playback will continue",
                    throwable = throwable,
                )
            } finally {
                handle.close()
                jobs.remove(source)
            }
        }
        jobs[source] = job
    }

    private companion object {
        const val PRELOAD_BYTES = 16L * 1024L * 1024L
    }
}
