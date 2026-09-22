from edit import read, write, replace
B = 'composeApp/src/'
A = B + 'androidMain/kotlin/com/yfuse/core2/android/'
C = B + 'commonMain/kotlin/com/yfuse/'

replace(C+'core2/api/YPlayer.kt', '    fun selectItem(index: Int)\n', '''    fun selectItem(index: Int)

    /** UI-resolved credits boundary; null uses natural duration. This never initiates a skip. */
    fun setNextItemPreparation(itemId: String, transitionPositionMs: Long?, enabled: Boolean) = Unit
''')

p=C+'feature/player/PlayerSkipCoordinator.kt'
replace(p,'    val actions: SkipSegmentActions,\n', '    val actions: SkipSegmentActions,\n    val nextItemBoundaryMs: Long?,\n')
replace(p,'    val activeSegment =\n        remember(', '    val segments =\n        remember(')
replace(p,'''        }.firstOrNull { segment ->
            segment.contains(playbackState.positionMs, playbackState.durationMs)
        }
    val skipSegment''', '''        }
    val activeSegment = segments.firstOrNull { it.contains(playbackState.positionMs, playbackState.durationMs) }
    val skipSegment''')
replace(p,'    return PlayerSkipController(\n', '''    return PlayerSkipController(
        nextItemBoundaryMs = nextItemCreditsBoundary(
            segments = segments,
            durationMs = playbackState.durationMs,
            mode = mode,
            cancelled = settled.value == (currentItem?.id to PlaybackSegmentType.Credits),
            watchGuest = watchGuest,
        ),
''')
text=read(p)
text=text.replace('import com.yfuse.core.model.PlaybackSegmentType','import com.yfuse.core.model.PlaybackSegment\nimport com.yfuse.core.model.PlaybackSegmentType')
text+='''
/** Manual credits buttons can be pressed at entry; do not wait for the automatic countdown. */
internal fun nextItemCreditsBoundary(
    segments: List<PlaybackSegment>,
    durationMs: Long,
    mode: SkipMode,
    cancelled: Boolean,
    watchGuest: Boolean,
): Long? =
    if (mode == SkipMode.Off || cancelled || watchGuest) null
    else segments.filter { it.type == PlaybackSegmentType.Credits && it.startMs > 0L &&
        (durationMs <= 0L || it.startMs < durationMs) }.minOfOrNull { it.startMs }
'''
write(p,text)
p=B+'androidMain/kotlin/com/yfuse/feature/player/PlayerRoot.kt'
replace(p,'        // 详情页 picked a 音轨 / 字幕 before this opened;', '''        LaunchedEffect(player, currentItem?.id, skip.nextItemBoundaryMs, autoNext, watchState.connected, watchState.canControl) {
            currentItem?.id?.let { id ->
                player.setNextItemPreparation(
                    itemId = id,
                    transitionPositionMs = skip.nextItemBoundaryMs,
                    enabled = autoNext && !(watchState.connected && !watchState.canControl),
                )
            }
        }
        // 详情页 picked a 音轨 / 字幕 before this opened;''')

replace(A+'AndroidCore2MediaProbe.kt','    fun closePreparedEnhancedDemux() = enhancedProbe.closePreparedDemux()\n', '''    fun closePreparedEnhancedDemux() = enhancedProbe.closePreparedDemux()

    fun adoptPreparedSources(item: YMediaItem, prepared: AndroidNextItemSources): Boolean {
        var adopted = false
        prepared.extractor.take(item)?.let {
            platformProbe.returnPreparedExtractor(item, it)
            adopted = true
        }
        prepared.enhanced.take(item)?.let {
            enhancedProbe.returnPreparedDemux(item, it)
            adopted = true
        }
        prepared.close()
        return adopted
    }
''')
replace(A+'AndroidEnhancedMediaProbe.kt','    fun closePreparedDemux() = preparedDemux.close()\n', '''    fun closePreparedDemux() = preparedDemux.close()

    fun returnPreparedDemux(item: YMediaItem, source: AndroidPreparedEnhancedDemux) = preparedDemux.offer(item, source)
''')

write(A+'AndroidNextItemPreparation.kt','''package com.yfuse.core2.android

import android.content.Context
import android.os.PowerManager
import com.yfuse.core.data.PlaybackNetworkClass
import com.yfuse.core.logging.AppLog
import com.yfuse.core.network.currentPlaybackNetworkClass
import com.yfuse.core2.api.YMediaItem
import com.yfuse.core2.api.YPlaybackPhase
import com.yfuse.core2.api.YPlayerState
import com.yfuse.core2.network.YSourceProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.util.concurrent.atomic.AtomicLong

internal data class NextItemPreparationBoundary(val itemId: String, val positionMs: Long?, val enabled: Boolean)

/** Slots expire independently of UI polling and transfer each resource to exactly one reader. */
internal class AndroidNextItemSources : AutoCloseable {
    val extractor = AndroidPreparedMediaSlot<YPlatformExtractorSource> { it.release() }
    val enhanced = AndroidPreparedMediaSlot<AndroidPreparedEnhancedDemux> { it.close() }
    override fun close() {
        extractor.close()
        enhanced.close()
    }
}

internal fun nextItemRemainingMs(state: YPlayerState, boundaryMs: Long?): Long? {
    val end = listOfNotNull(boundaryMs?.takeIf { it > 0 }, state.durationMs.takeIf { it > 0 }).minOrNull() ?: return null
    if (!state.speed.isFinite() || state.speed <= 0f) return null
    return ((end - state.positionMs).coerceAtLeast(0).toDouble() / state.speed).toLong()
}

internal fun nextItemPlaybackHealthy(state: YPlayerState): Boolean =
    state.phase == YPlaybackPhase.Ready && state.playing && !state.buffering &&
        state.speed.isFinite() && state.speed > 0f &&
        (state.bufferedPositionMs - state.positionMs).coerceAtLeast(0L).toDouble() / state.speed >= 5_000L

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
): Boolean {
    var healthyMs = 0L
    while (true) {
        val current = state() ?: return false
        if (current.phase == YPlaybackPhase.Ended || current.phase == YPlaybackPhase.Failed) return false
        AndroidPlaybackMemoryBudget.refreshPressure()
        val ready = allowed() && nextItemPlaybackHealthy(current)
        healthyMs = if (ready) healthyMs + 250L else 0L
        if (healthyMs >= 1_000L && (nextItemRemainingMs(current, boundary()) ?: Long.MAX_VALUE) <= windowMs) return true
        delay(250L)
    }
}

/** A watchdog cancels real reads on pressure, stalls, pause, cancellation or the shared deadline. */
internal suspend fun <T> speculativeNextItemWork(allowed: () -> Boolean, block: (AndroidProbeBudget) -> T): T =
    coroutineScope {
        val budget = AndroidProbeBudget(timeoutMs = 15_000L)
        val guard = launch {
            try {
                while (true) {
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
internal fun warmNextItemBytes(context: Context, item: YMediaItem, budget: AndroidProbeBudget): Long {
    if (item.cacheIdentity == null || item.cacheMaximumBytes <= 0L) return 0L
    val protocol = when {
        item.uri.startsWith("https://", true) -> YSourceProtocol.Https
        item.uri.startsWith("http://", true) -> YSourceProtocol.Http
        else -> return 0L
    }
    val networkBytes = AtomicLong()
    val memory = AndroidPlaybackMemoryBudget.acquire(PlaybackBufferKind.Preload, 2L * 1024 * 1024)
    if (memory.limitBytes < 512L * 1024) { memory.close(); return 0L }
    val source = AndroidTransportMediaDataSource(
        uri = item.uri,
        protocol = protocol,
        headers = item.headers,
        credentials = item.transportCredentials,
        createTransport = { AndroidHttpMediaTransport(followSafeRedirects = true, allowCrossProtocolRedirects = true) },
        cacheDirectory = context.cacheDir,
        cacheIdentity = item.cacheIdentity,
        cacheMaximumBytes = item.cacheMaximumBytes,
        memoryLeaseOverride = memory,
        allowsSpeculativeWork = { false },
        rangeReadBudgetMs = 10_000L,
        onNetworkSample = { bytes, _ -> networkBytes.addAndGet(bytes) },
    )
    return source.use {
        val cancellation = budget.onCancel(source::cancelReads)
        try {
            val buffer = ByteArray(64 * 1024)
            fun readRange(start: Long, length: Long): Long {
                var read = 0L
                while (read < length) {
                    budget.ensureActive()
                    val count = source.readAt(start + read, buffer, 0, minOf(buffer.size.toLong(), length - read).toInt())
                    if (count <= 0) break
                    read += count
                }
                return read
            }
            val limit = minOf(nextItemPreloadBytes(item.sourceHints?.bitrateBitsPerSecond ?: 0), item.cacheMaximumBytes)
            var read = readRange(0L, limit)
            // Tail indices (e.g. MP4 moov / Matroska cues) use the same validated sparse cache.
            val length = source.size
            if (length > limit) read += readRange(maxOf(limit, length - 512L * 1024), minOf(512L * 1024, length - limit))
            AppLog.info(category = "player.core2", event = "next_item_bytes_warmed",
                message = "YCore next-item ranges available to the playback reader",
                attributes = mapOf("readBytes" to read.toString(), "networkBytes" to networkBytes.get().toString()))
            read
        } finally { cancellation.close() }
    }
}
''')
