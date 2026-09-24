package com.yfuse.core2.android

import android.content.Context
import android.net.ConnectivityManager
import android.os.PowerManager
import com.yfuse.core.data.PlaybackNetworkClass
import com.yfuse.core.data.SourcePreheatMode
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

internal data class NextItemPreparationBoundary(
    val itemId: String,
    val positionMs: Long?,
    val enabled: Boolean,
    val allowMeteredNetwork: Boolean = false,
    val nextIntroEndMs: Long? = null,
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

/** Next-episode work follows 起播预热: Wi-Fi always, mobile data only when allowed and not under Data Saver. */
internal fun nextItemNetworkAllowed(
    context: Context,
    allowMeteredNetwork: Boolean = false,
): Boolean =
    nextItemNetworkClassAllowed(currentPlaybackNetworkClass(), dataSaverEnabled(context), allowMeteredNetwork) &&
        context.getSystemService(PowerManager::class.java)?.isPowerSaveMode != true &&
        AndroidPlaybackMemoryBudget.allowsSpeculativeWork

/**
 * [nextItemNetworkAllowed] costs four system-service calls, and the router asks it on every child
 * state update while a next-item preparation exists - several times a second. The answer is reused
 * for a short window instead; a network change still reaches the router within that window.
 */
internal class NextItemNetworkGate(
    private val windowMs: Long = NEXT_ITEM_NETWORK_GATE_WINDOW_MS,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val evaluate: (allowMeteredNetwork: Boolean) -> Boolean,
) {
    private var evaluatedAtMs = 0L
    private var evaluatedForMetered: Boolean? = null
    private var verdict = false

    @Synchronized
    fun allowed(allowMeteredNetwork: Boolean): Boolean {
        val now = nowMs()
        if (evaluatedForMetered != allowMeteredNetwork || now - evaluatedAtMs >= windowMs) {
            verdict = evaluate(allowMeteredNetwork)
            evaluatedForMetered = allowMeteredNetwork
            evaluatedAtMs = now
        }
        return verdict
    }
}

private const val NEXT_ITEM_NETWORK_GATE_WINDOW_MS = 2_000L

internal fun nextItemNetworkClassAllowed(
    networkClass: PlaybackNetworkClass,
    dataSaverEnabled: Boolean,
    allowMeteredNetwork: Boolean,
): Boolean =
    if (dataSaverEnabled) {
        false
    } else {
        when (networkClass) {
            PlaybackNetworkClass.Unmetered -> true
            PlaybackNetworkClass.Metered -> allowMeteredNetwork
            PlaybackNetworkClass.Offline, PlaybackNetworkClass.Unknown -> false
        }
    }

/**
 * The title on screen follows the user's [SourcePreheatMode]. Its probe reads the head and index
 * bytes playback fetches first anyway, and playback adopts the prepared extractor; skipping it cost
 * 2-7 s of probing after every tap on mobile data. Data Saver and power saving always opt out.
 */
internal fun currentItemNetworkAllowed(
    context: Context,
    mode: SourcePreheatMode,
): Boolean =
    currentItemNetworkClassAllowed(currentPlaybackNetworkClass(), dataSaverEnabled(context), mode) &&
        context.getSystemService(PowerManager::class.java)?.isPowerSaveMode != true &&
        AndroidPlaybackMemoryBudget.allowsSpeculativeWork

internal fun currentItemNetworkClassAllowed(
    networkClass: PlaybackNetworkClass,
    dataSaverEnabled: Boolean,
    mode: SourcePreheatMode,
): Boolean =
    mode != SourcePreheatMode.Off &&
        nextItemNetworkClassAllowed(networkClass, dataSaverEnabled, mode == SourcePreheatMode.WifiAndMobile)

internal fun dataSaverEnabled(context: Context): Boolean =
    context.getSystemService(ConnectivityManager::class.java)?.restrictBackgroundStatus ==
        ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED

/** Position is media time, window is wall time. Credits changes and seeks are read every poll. */
internal suspend fun awaitNextItemBoundary(
    windowMs: Long,
    boundary: () -> Long?,
    state: () -> YPlayerState?,
    allowed: () -> Boolean,
    stableMs: Long = 1_000L,
): Boolean {
    var healthyMs = 0L
    while (currentCoroutineContext().isActive) {
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
    return false
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
                    while (isActive) {
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

/**
 * Reads the first sample after the next item's intro through the extractor playback will adopt.
 * With 跳过片头 on, the next episode opens at 0 and then jumps to the intro end; warming only the
 * opening left that jump waiting on a fresh remote range. The extractor locates the keyframe (no
 * byte offset is estimated) and is returned to the start, where playback begins.
 *
 * Returns false when the read did not finish cleanly; the caller then drops the extractor, because
 * one interrupted mid-read must not be handed to playback. Normal opening remains available.
 */
internal suspend fun warmNextItemIntroEnd(
    extractor: YPlatformExtractorSource,
    introEndMs: Long,
    allowed: () -> Boolean,
): Boolean {
    val track = extractor.findFirstTrack("video/") ?: extractor.findFirstTrack("audio/") ?: return true
    val targetUs = introEndMs.coerceIn(0L, Long.MAX_VALUE / 1_000L) * 1_000L
    val warmed =
        try {
            speculativeNextItemWork(allowed) { budget ->
                val cancellation = budget.onCancel(extractor::cancelPendingRead)
                try {
                    extractor.selectTrack(track)
                    extractor.seekTo(targetUs)
                    extractor.readSample(ByteBuffer.allocateDirect(2 * 1024 * 1024))
                    extractor.seekTo(0L)
                    extractor.unselectTrack(track)
                    budget.ensureActive()
                    true
                } finally {
                    cancellation.close()
                }
            }
        } catch (cancelled: CancellationException) {
            if (!currentCoroutineContext().isActive) throw cancelled
            false
        } catch (_: Exception) {
            false
        }
    AppLog.info(
        category = "player.core2",
        event = if (warmed) "next_item_intro_end_warmed" else "next_item_intro_end_skipped",
        message = "YCore prepared the position after the next item's intro",
        attributes = mapOf("introEndMs" to introEndMs.toString()),
    )
    return warmed
}

/**
 * Uses the exact YCore range-cache identity/block geometry; stops extra reads when the network changes.
 * Its transport shares the item's redirect memory, so the probe and the player that follow reuse the
 * target this warm-up resolved instead of paying the redirect chain again (and the reverse).
 */
internal fun warmNextItemBytes(
    cacheDirectory: File,
    item: YMediaItem,
    budget: AndroidProbeBudget,
    createTransport: () -> YMediaTransport = { sharedRouteHttpMediaTransport(item.uri) },
    maximumStartupBytes: Long = Long.MAX_VALUE,
    currentItem: Boolean = false,
    shouldContinue: () -> Boolean = { true },
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
                while (read < length && shouldContinue()) {
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
            val limit =
                minOf(
                    nextItemPreloadBytes(item.sourceHints?.bitrateBitsPerSecond ?: 0),
                    item.cacheMaximumBytes,
                    maximumStartupBytes,
                )
            var read = readRange(0L, limit)
            // Tail indices (e.g. MP4 moov / Matroska cues) use the same validated sparse cache.
            val length = if (shouldContinue()) source.size else -1L
            if (shouldContinue() && length > limit) {
                read += readRange(maxOf(limit, length - 512L * 1024), minOf(512L * 1024, length - limit))
            }
            val persisted = source.awaitCacheWrites(minOf(1_000L, budget.remainingMs()))
            AppLog.info(
                category = "player.core2",
                event = if (currentItem) "current_item_bytes_warmed" else "next_item_bytes_warmed",
                message = "YCore prepared ranges available to the playback reader",
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
