package com.yfuse.feature.player

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.yfuse.core.network.mediaCacheKeyForUrl
import java.io.Closeable

/** Prevents authenticated playback URLs from being written to Media3's cache index. */
internal fun secureMediaCacheKeyForUrl(url: String): String = mediaCacheKeyForUrl(url)

@UnstableApi
internal object SecureMediaCacheKeyFactory : CacheKeyFactory {
    override fun buildCacheKey(dataSpec: DataSpec): String =
        secureMediaCacheKeyForUrl(dataSpec.key ?: dataSpec.uri.toString())
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
