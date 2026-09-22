package com.yfuse.feature.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.yfuse.core.model.PlaybackMethod
import com.yfuse.core.network.mediaCacheKeyForUrl
import java.io.Closeable
import java.net.URI

/** Prevents authenticated playback URLs from being written to Media3's cache index. */
internal fun secureMediaCacheKeyForUrl(url: String): String = mediaCacheKeyForUrl(url)

@UnstableApi
internal object SecureMediaCacheKeyFactory : CacheKeyFactory {
    override fun buildCacheKey(dataSpec: DataSpec): String =
        secureMediaCacheKeyForUrl(dataSpec.key ?: dataSpec.uri.toString())
}

/**
 * Returns the original remote file that may enter the persistent sparse cache.
 *
 * Runtime fallbacks intentionally do not mutate this policy: server HLS/progressive transcodes,
 * adaptive manifests, DRM, optical-disc sources and local files always bypass the disk cache.
 */
internal fun PlayerMediaItem.persistentPlaybackCacheUrl(
    usingServerTranscode: Boolean = startsWithServerTranscode(),
): String? {
    val version = activeVersion
    val source = url.trim()
    if (
        usingServerTranscode ||
        playMethod == PlaybackMethod.Transcode ||
        version?.playMethod == PlaybackMethod.Transcode ||
        drmConfiguration != null ||
        version?.drmConfiguration != null ||
        version?.discSource == true ||
        !shouldProxyMpvNetworkUrl(source) ||
        source.isPersistentPlaybackManifestUrl()
    ) {
        return null
    }
    val container = version?.container?.trim()?.lowercase()
    if (container in PERSISTENT_CACHE_ADAPTIVE_CONTAINERS) return null
    return source
}

internal fun String.isPersistentPlaybackManifestUrl(): Boolean {
    val source = trim()
    val path =
        runCatching { URI(source).path }
            .getOrNull()
            ?.lowercase()
            ?: source.substringBefore('?').lowercase()
    return path.endsWith(".m3u8") || path.endsWith(".mpd")
}

private val PERSISTENT_CACHE_ADAPTIVE_CONTAINERS = setOf("hls", "m3u8", "dash", "mpd")

/**
 * Selects the shared cache only for exact original-file URLs. Media3 may build child requests for
 * manifests and fallbacks, so applying CacheDataSource to the whole factory would cache streams
 * that policy explicitly excludes.
 */
@UnstableApi
internal class SelectivePlaybackCacheDataSourceFactory(
    private val cachedFactory: DataSource.Factory,
    private val upstreamFactory: DataSource.Factory,
    private val shouldCacheUrl: (String) -> Boolean,
) : DataSource.Factory {
    override fun createDataSource(): DataSource =
        SelectivePlaybackCacheDataSource(
            cachedFactory = cachedFactory,
            upstreamFactory = upstreamFactory,
            shouldCacheUrl = shouldCacheUrl,
        )
}

@UnstableApi
private class SelectivePlaybackCacheDataSource(
    private val cachedFactory: DataSource.Factory,
    private val upstreamFactory: DataSource.Factory,
    private val shouldCacheUrl: (String) -> Boolean,
) : DataSource {
    private val transferListeners = mutableListOf<TransferListener>()
    private var delegate: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        transferListeners += transferListener
    }

    override fun open(dataSpec: DataSpec): Long {
        check(delegate == null) { "DataSource is already open" }
        val selectedFactory =
            if (shouldCacheUrl(dataSpec.uri.toString())) cachedFactory else upstreamFactory
        return selectedFactory
            .createDataSource()
            .also { selected ->
                transferListeners.forEach(selected::addTransferListener)
                delegate = selected
            }.open(dataSpec)
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int = checkNotNull(delegate) { "DataSource is not open" }.read(buffer, offset, length)

    override fun getUri(): Uri? = delegate?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders.orEmpty()

    override fun close() {
        val selected = delegate ?: return
        delegate = null
        selected.close()
    }
}

/**
 * Shares one Media3 cache between player rebuilds.
 *
 * SimpleCache exclusively locks its directory, so constructing one per Exo engine fails during
 * version/decoder handovers. Reference counting also lets a changed size take effect on the next
 * player once the previous engine has released the directory.
 */
@UnstableApi
internal object VideoCachePool {
    private var cache: SimpleCache? = null
    private var configuredBytes: Long = -1L
    private var references = 0

    @Synchronized
    fun acquire(
        context: Context,
        maxBytes: Long,
    ): Handle? {
        if (maxBytes <= 0L) return null
        if (cache == null || (configuredBytes != maxBytes && references == 0)) {
            cache?.release()
            cache =
                SimpleCache(
                    context.cacheDir.resolve("video_cache_v2"),
                    LeastRecentlyUsedCacheEvictor(maxBytes),
                    StandaloneDatabaseProvider(context.applicationContext),
                )
            configuredBytes = maxBytes
        }
        references++
        return Handle(requireNotNull(cache), ::releaseReference)
    }

    /**
     * Removes transient playback entries without touching the offline-download directory.
     *
     * When no player owns the shared instance, open the cache with a no-op evictor only for this
     * operation so entries left by previous processes are cleared as well.
     */
    @Synchronized
    fun clear(context: Context): Long {
        val sharedCache = cache
        val target =
            sharedCache
                ?: SimpleCache(
                    context.cacheDir.resolve("video_cache_v2"),
                    NoOpCacheEvictor(),
                    StandaloneDatabaseProvider(context.applicationContext),
                )
        return try {
            val before = target.cacheSpace
            target.keys.toList().forEach { key ->
                runCatching { target.removeResource(key) }
            }
            (before - target.cacheSpace).coerceAtLeast(0L)
        } finally {
            if (sharedCache == null) target.release()
        }
    }

    /** Returns current transient playback cache usage without changing its configured limit. */
    @Synchronized
    fun usage(context: Context): Long {
        val sharedCache = cache
        val target =
            sharedCache
                ?: SimpleCache(
                    context.cacheDir.resolve("video_cache_v2"),
                    NoOpCacheEvictor(),
                    StandaloneDatabaseProvider(context.applicationContext),
                )
        return try {
            target.cacheSpace
        } finally {
            if (sharedCache == null) target.release()
        }
    }

    @Synchronized
    private fun releaseReference() {
        references = (references - 1).coerceAtLeast(0)
    }

    class Handle internal constructor(
        val cache: SimpleCache,
        private val onClose: () -> Unit,
    ) : Closeable {
        private var closed = false

        override fun close() {
            if (closed) return
            closed = true
            onClose()
        }
    }
}
