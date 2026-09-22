package com.yfuse.core2.android

import android.content.Context
import com.yfuse.core.logging.AppLog
import com.yfuse.core2.api.YMediaItem
import com.yfuse.feature.player.PlaybackSourcePreload
import com.yfuse.feature.player.PlayerMediaItem
import com.yfuse.feature.player.noOpPlaybackSourcePreload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

internal class PreparedCurrentItem(
    val positionMs: Long,
    val probe: YCore2ProbeResult.Success,
    val extractor: YPlatformExtractorSource,
) : AutoCloseable {
    override fun close() = extractor.release()
}

/** One selected title only. Reads YCore's real cache and never creates a decoder or transcode. */
internal object AndroidCurrentItemPreparation {
    private class Entry(
        val item: YMediaItem,
    ) {
        val slot = AndroidPreparedMediaSlot<PreparedCurrentItem> { it.close() }
        var accepting = true
        var job: Job? = null
    }

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var current: Entry? = null

    fun preload(
        context: Context,
        media: PlayerMediaItem,
        positionMs: Long,
        userAgent: String,
        cacheBytes: Long,
        initialTrackSelection: com.yfuse.core2.api.YInitialTrackSelection? = null,
    ): PlaybackSourcePreload {
        val item =
            listOf(media)
                .toCore2MediaItems(userAgent, cacheBytes)
                .single()
                .copy(initialTrackSelection = initialTrackSelection?.orNull())
        if (!nextItemSourceEligible(item) || !nextItemNetworkAllowed(context)) return noOpPlaybackSourcePreload()
        val entry = Entry(item)
        val previous =
            synchronized(lock) {
                current.also {
                    current = entry
                    it?.accepting = false
                }
            }
        previous?.job?.cancel()
        previous?.let(::closeAsync)
        entry.job =
            scope.launch {
                var prepared: PreparedCurrentItem? = null
                try {
                    delay(500L)
                    val probe = AndroidCore2MediaProbe(context)
                    var extracted: YPlatformExtractorSource? = null
                    try {
                        val result =
                            speculativeNextItemWork(
                                allowed = { synchronized(lock) { entry.accepting } && nextItemNetworkAllowed(context) },
                            ) { budget ->
                                if (positionMs ==
                                    0L
                                ) {
                                    warmNextItemBytes(
                                        context.cacheDir,
                                        item,
                                        budget,
                                        maximumStartupBytes = 2L * 1024 * 1024,
                                        currentItem = true,
                                    )
                                }
                                val facts =
                                    probe.probe(item, budget) as? YCore2ProbeResult.Success
                                        ?: return@speculativeNextItemWork null
                                val source = probe.takePreparedExtractor(item) ?: return@speculativeNextItemWork null
                                extracted = source
                                val cancellation = budget.onCancel(source::cancelPendingRead)
                                try {
                                    // Let the extractor locate the preceding keyframe; never estimate byte offsets.
                                    val track = source.findFirstTrack("video/") ?: source.findFirstTrack("audio/")
                                    if (track != null) {
                                        source.selectTrack(track)
                                        source.seekTo(
                                            positionMs.coerceAtLeast(0L).coerceAtMost(Long.MAX_VALUE / 1000L) * 1000L,
                                        )
                                        source.readSample(ByteBuffer.allocateDirect(2 * 1024 * 1024))
                                        source.unselectTrack(track)
                                    }
                                    budget.ensureActive()
                                    PreparedCurrentItem(positionMs, facts, source)
                                } finally {
                                    cancellation.close()
                                }
                            }
                        // The speculative budget has closed before ownership can transfer to playback.
                        prepared = result
                        if (result != null) extracted = null
                    } finally {
                        extracted?.release()
                        probe.closePreparedExtractor()
                    }
                    synchronized(lock) {
                        if (current === entry && entry.accepting) {
                            prepared?.let {
                                entry.slot.offer(item, it)
                                AppLog.info(
                                    "player.core2",
                                    "current_item_prepared",
                                    "Current source and keyframe are prepared",
                                )
                            }
                            prepared = null
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    AppLog.info(
                        "player.core2",
                        "current_item_preparation_skipped",
                        "Optional source preparation stopped",
                        attributes = mapOf("reason" to failure.javaClass.simpleName),
                    )
                    // Preparation is optional; the normal open still has full recovery policy.
                } finally {
                    prepared?.close()
                }
            }
        return object : PlaybackSourcePreload {
            override fun cancel() {
                synchronized(lock) {
                    entry.accepting = false
                    if (current === entry) current = null
                }
                entry.job?.cancel()
                closeAsync(entry)
            }

            override fun handoff() {
                synchronized(lock) { entry.accepting = false }
                entry.job?.cancel()
                // A completed slot retains its own 30-second lease until playback claims it.
            }
        }
    }

    private fun closeAsync(entry: Entry) {
        // Detail-page cancellation runs on Main; vendor extractor cleanup must not block navigation.
        scope.launch { entry.slot.close() }
    }

    fun claim(
        item: YMediaItem,
        positionMs: Long,
    ): PreparedCurrentItem? {
        val entry =
            synchronized(lock) {
                current?.also {
                    current = null
                    it.accepting = false
                }
            } ?: return null
        entry.job?.cancel()
        if (!entry.item.matchesPreparedSource(item) || entry.item.sourceHints != item.sourceHints) {
            entry.slot.close()
            return null
        }
        val result =
            entry.slot.take(item) ?: run {
                entry.slot.close()
                return null
            }
        if (result.positionMs == positionMs) {
            AppLog.info("player.core2", "current_item_preparation_reused", "Playback adopted the prepared source")
            return result
        }
        result.close()
        return null
    }
}
