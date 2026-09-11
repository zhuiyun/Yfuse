package com.yfuse.core2.android

import android.content.Context
import android.os.PowerManager
import com.yfuse.core.data.PlaybackNetworkClass
import com.yfuse.core.logging.AppLog
import com.yfuse.core.network.currentPlaybackNetworkClass
import com.yfuse.core2.api.YMediaItem
import com.yfuse.core2.api.YPlaybackPhase
import com.yfuse.core2.api.YPlayerState
import com.yfuse.core2.network.YMediaTransport
import com.yfuse.core2.network.YSourceProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.util.concurrent.atomic.AtomicLong

internal data class NextItemPreparationBoundary(
    val itemId: String,
    val positionMs: Long?,
    val enabled: Boolean,
)

/** Slots expire independently of UI polling and transfer each resource to exactly one reader. */
internal class AndroidNextItemSources : AutoCloseable {
    val extractor = AndroidPreparedMediaSlot<YPlatformExtractorSource> { it.release() }
    val enhanced = AndroidPreparedMediaSlot<AndroidPreparedEnhancedDemux> { it.close() }

    override fun close() {
        extractor.close()
        enhanced.close()
    }
}

internal fun nextItemRemainingMs(
    state: YPlayerState,
    boundaryMs: Long?,
): Long? {
    val end =
        listOfNotNull(boundaryMs?.takeIf { it > 0 }, state.durationMs.takeIf { it > 0 }).minOrNull() ?: return null
    if (!state.speed.isFinite() || state.speed <= 0f) return null
    return ((end - state.positionMs).coerceAtLeast(0).toDouble() / state.speed).toLong()
}

internal fun nextItemPlaybackHealthy(state: YPlayerState): Boolean =
    state.phase == YPlaybackPhase.Ready &&
        state.playing &&
        !state.buffering &&
        state.speed.isFinite() &&
        state.speed > 0f &&
        (state.bufferedPositionMs - state.positionMs).coerceAtLeast(0L).toDouble() / state.speed >=
        minOf(15_000L, nextItemRemainingMs(state, null)?.coerceAtLeast(1_000L) ?: 15_000L)

internal fun nextItemPreloadBytes(bitrate: Long): Long =
    (if (bitrate > 0L) bitrate.coerceAtMost(128_000_000L) / 8L * 6L else 4L * 1024 * 1024)
        .coerceIn(2L * 1024 * 1024, 12L * 1024 * 1024)

internal fun nextItemNetworkAllowed(context: Context): Boolean =
    currentPlaybackNetworkClass() == PlaybackNetworkClass.Unmetered &&
        context.getSystemService(PowerManager::class.java)?.isPowerSaveMode != true &&
        AndroidPlaybackMemoryBudget.allowsSpeculativeWork

/** Position is media time, window is wall time. Credits changes and seeks are read every poll. */
internal suspend fun awaitNextItemBoundary(
    windowMs: Long,
    boundary: () -> Long?,
    state: () -> YPlayerState?,
    allowed: () -> Boolean,
    stableMs: Long = 1_000L,
): Boolean {
    var healthyMs = 0L
    while (true) {
        val current = state() ?: return false
        if (current.phase == YPlaybackPhase.Ended || current.phase == YPlaybackPhase.Failed) return false
        val remaining = nextItemRemainingMs(current, boundary())
        if (remaining == null || remaining > windowMs + 5_000L) {
            healthyMs = 0L
            delay(5_000L)
            continue
        }
        AndroidPlaybackMemoryBudget.refreshPressure()
        val ready = allowed() && nextItemPlaybackHealthy(current)
        healthyMs = if (ready) healthyMs + 250L else 0L
        if (healthyMs >= stableMs &&
            (nextItemRemainingMs(current, boundary()) ?: Long.MAX_VALUE) <= windowMs
        ) {
            return true
        }
        delay(250L)
    }
}

/** A watchdog cancels real reads on pressure, stalls, pause, cancellation or the shared deadline. */
internal suspend fun <T> speculativeNextItemWork(
    allowed: () -> Boolean,
    block: (AndroidProbeBudget) -> T,
): T =
    coroutineScope {
        val budget = AndroidProbeBudget(timeoutMs = 15_000L)
        val guard =
            launch {
                try {
                    while (true) {
                        AndroidPlaybackMemoryBudget.refreshPressure()
                        if (!allowed()) budget.cancel("next_item_yield")
                        delay(250L)
                    }
                } finally {
                    budget.cancel("next_item_cancelled")
                }
            }
        try {
            budget.ensureActive()
            runInterruptible(Dispatchers.IO) { block(budget) }.also { budget.ensureActive() }
        } catch (cancelled: CancellationException) {
            budget.cancel("next_item_cancelled")
            throw cancelled
        } finally {
            budget.close()
            guard.cancel()
        }
    }

/** Uses the exact YCore range-cache identity/block geometry; never warms the compatibility cache. */
internal fun warmNextItemBytes(
    cacheDirectory: File,
    item: YMediaItem,
    budget: AndroidProbeBudget,
    createTransport: () -> YMediaTransport = {
        AndroidHttpMediaTransport(followSafeRedirects = true, allowCrossProtocolRedirects = true)
    },
    acquireMemory: () -> PlaybackMemoryLease = {
        AndroidPlaybackMemoryBudget.acquire(PlaybackBufferKind.Preload, 8L * 1024 * 1024)
    },
): Long {
    if (item.cacheIdentity == null || item.cacheMaximumBytes <= 0L) return 0L
    val protocol =
        when {
            item.uri.startsWith("https://", true) -> YSourceProtocol.Https
            item.uri.startsWith("http://", true) -> YSourceProtocol.Http
            else -> return 0L
        }
    val networkBytes = AtomicLong()
    val memory = acquireMemory()
    if (memory.limitBytes < 8L * 1024 * 1024) {
        memory.close()
        return 0L
    }
    val source =
        AndroidTransportMediaDataSource(
            uri = item.uri,
            protocol = protocol,
            headers = item.headers,
            credentials = item.transportCredentials,
            createTransport = createTransport,
            cacheDirectory = cacheDirectory,
            cacheIdentity = item.cacheIdentity,
            cacheMaximumBytes = item.cacheMaximumBytes,
            memoryLeaseOverride = memory,
            allowsSpeculativeWork = { false },
            rangeReadBudgetMs = 10_000L,
            persistReadBlocks = true,
            onNetworkSample = { bytes, _ -> networkBytes.addAndGet(bytes) },
        )
    return source.use {
        val cancellation = budget.onCancel(source::cancelReads)
        try {
            val buffer = ByteArray(64 * 1024)

            fun readRange(
                start: Long,
                length: Long,
            ): Long {
                var read = 0L
                while (read < length) {
                    budget.ensureActive()
                    val count =
                        source.readAt(
                            start + read,
                            buffer,
                            0,
                            minOf(buffer.size.toLong(), length - read).toInt(),
                        )
                    if (count <= 0) break
                    read += count
                }
                return read
            }
            val limit = minOf(nextItemPreloadBytes(item.sourceHints?.bitrateBitsPerSecond ?: 0), item.cacheMaximumBytes)
            var read = readRange(0L, limit)
            // Tail indices (e.g. MP4 moov / Matroska cues) use the same validated sparse cache.
            val length = source.size
            if (length >
                limit
            ) {
                read += readRange(maxOf(limit, length - 512L * 1024), minOf(512L * 1024, length - limit))
            }
            val persisted = source.awaitCacheWrites(minOf(1_000L, budget.remainingMs()))
            AppLog.info(
                category = "player.core2",
                event = "next_item_bytes_warmed",
                message = "YCore next-item ranges available to the playback reader",
                attributes =
                    mapOf(
                        "readBytes" to read.toString(),
                        "networkBytes" to networkBytes.get().toString(),
                        "writesDrained" to persisted.toString(),
                    ),
            )
            read
        } finally {
            cancellation.close()
        }
    }
}

internal fun nextItemSourceEligible(item: YMediaItem): Boolean {
    if (!item.allowNextItemPreparation || item.disc != null || item.drmConfiguration != null) return false
    val path =
        item.uri
            .substringBefore('?')
            .substringBefore('#')
            .lowercase()
    val mime = item.mimeType.orEmpty().lowercase()
    return !path.endsWith(".m3u8") &&
        !path.endsWith(".mpd") &&
        !mime.contains("mpegurl") &&
        !mime.contains("dash+xml")
}
