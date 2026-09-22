package com.yfuse.core2.android

import com.yfuse.core2.api.YMediaItem
import com.yfuse.core2.demux.YDemuxOpenResult
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** A full FFmpeg open, with its authenticated proxy, exclusively transferred to one player. */
internal class AndroidPreparedEnhancedDemux(
    val demuxer: AndroidFfmpegDemuxer,
    val openResult: YDemuxOpenResult,
    val proxy: AndroidYCoreHttpProxy?,
) : AutoCloseable {
    override fun close() {
        try {
            demuxer.close()
        } finally {
            proxy?.close()
        }
    }
}

/** Exact authorization matching and expiry prevent speculative opens leaking across sources. */
internal class AndroidPreparedMediaSlot<T>(
    private val expiryMillis: Long = 30_000L,
    private val release: (T) -> Unit,
) {
    private val lock = Any()
    private var item: YMediaItem? = null
    private var prepared: T? = null
    private var expiry: ScheduledFuture<*>? = null

    fun offer(
        item: YMediaItem,
        value: T,
    ) {
        exchange(item, value)?.let { runCatching { release(it) } }
    }

    /** Lightweight ownership swap; the caller releases the old resource after leaving its lock. */
    fun exchange(
        item: YMediaItem,
        value: T,
    ): T? =
        synchronized(lock) {
            val previous = prepared
            expiry?.cancel(false)
            this.item = item
            prepared = value
            expiry = releaser.schedule({ expire(value) }, expiryMillis, TimeUnit.MILLISECONDS)
            previous
        }

    fun take(item: YMediaItem): T? =
        synchronized(lock) {
            if (this.item?.matchesPreparedSource(item) != true) return@synchronized null
            expiry?.cancel(false)
            expiry = null
            this.item = null
            prepared.also { prepared = null }
        }

    private fun expire(expected: T) {
        val previous =
            synchronized(lock) {
                if (prepared !== expected) return
                prepared = null
                item = null
                expiry = null
                expected
            }
        runCatching { release(previous) }
    }

    fun close() {
        val previous =
            synchronized(lock) {
                expiry?.cancel(false)
                expiry = null
                item = null
                prepared.also { prepared = null }
            }
        previous?.let { runCatching { release(it) } }
    }

    private companion object {
        val releaser =
            ScheduledThreadPoolExecutor(1) { task ->
                Thread(task, "YCore-PreparedRelease").apply { isDaemon = true }
            }.apply { removeOnCancelPolicy = true }
    }
}

internal fun YMediaItem.matchesPreparedSource(other: YMediaItem): Boolean =
    id == other.id &&
        uri == other.uri &&
        headers == other.headers &&
        transportCredentials == other.transportCredentials &&
        cacheIdentity == other.cacheIdentity &&
        cacheMaximumBytes == other.cacheMaximumBytes &&
        drmConfiguration == other.drmConfiguration
