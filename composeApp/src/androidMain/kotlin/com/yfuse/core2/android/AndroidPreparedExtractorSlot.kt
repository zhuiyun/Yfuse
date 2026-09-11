package com.yfuse.core2.android

import com.yfuse.core2.api.YMediaItem
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Single-use handoff, bound to the exact source and authorization context, never persisted. */
internal class AndroidPreparedExtractorSlot {
    private val lock = Any()
    private var item: YMediaItem? = null
    private var source: YPlatformExtractorSource? = null
    private var expiry: ScheduledFuture<*>? = null

    fun offer(
        item: YMediaItem,
        source: YPlatformExtractorSource,
    ) {
        exchange(item, source)?.let { runCatching(it::release) }
    }

    /** Lightweight ownership swap; callers dispose the old source outside all cancellation locks. */
    fun exchange(
        item: YMediaItem,
        source: YPlatformExtractorSource,
    ): YPlatformExtractorSource? =
        synchronized(lock) {
            val previous = this.source
            expiry?.cancel(false)
            this.item = item
            this.source = source
            expiry = releaser.schedule({ expire(source) }, 30L, TimeUnit.SECONDS)
            previous
        }

    fun take(item: YMediaItem): YPlatformExtractorSource? =
        synchronized(lock) {
            val stored = this.item ?: return@synchronized null
            if (stored.id != item.id ||
                stored.uri != item.uri ||
                stored.headers != item.headers ||
                stored.transportCredentials != item.transportCredentials ||
                stored.cacheIdentity != item.cacheIdentity ||
                stored.cacheMaximumBytes != item.cacheMaximumBytes ||
                stored.drmConfiguration != item.drmConfiguration
            ) {
                return@synchronized null
            }
            expiry?.cancel(false)
            expiry = null
            this.item = null
            source.also { source = null }
        }

    private fun expire(expected: YPlatformExtractorSource) {
        val expired =
            synchronized(lock) {
                if (source !== expected) return
                source = null
                item = null
                expiry = null
                expected
            }
        runCatching(expired::release)
    }

    fun close() {
        val remaining =
            synchronized(lock) {
                expiry?.cancel(false)
                expiry = null
                item = null
                source.also { source = null }
            }
        remaining?.let { runCatching(it::release) }
    }

    private companion object {
        val releaser =
            ScheduledThreadPoolExecutor(1) { runnable ->
                Thread(runnable, "YCore-ProbeRelease").apply { isDaemon = true }
            }.apply { removeOnCancelPolicy = true }
    }
}
