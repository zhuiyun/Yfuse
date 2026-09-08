package com.yfuse.feature.player

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import com.yfuse.core.playback.PlaybackOptimizationMode
import com.yfuse.core.playback.PlaybackStartupConditions
import com.yfuse.core.playback.playbackStartupThresholdMs

internal fun playbackMemoryBudgetBytes(context: Context): Long {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val heapBytes = (manager?.memoryClass ?: 128).toLong() * 1024L * 1024L
    return (heapBytes / 4L).coerceIn(16L * 1024L * 1024L, 192L * 1024L * 1024L)
}

/** The default load control still owns rebuffering, loading limits and live playback. */
@UnstableApi
internal class AdaptiveStartupLoadControl(
    private val delegate: LoadControl,
    private val mode: PlaybackOptimizationMode,
    private val evidence: StartupTransferEvidence,
    private val currentItem: () -> PlayerMediaItem?,
) : LoadControl {
    // Kotlin interface delegation does not forward Java default methods. Media3's lifecycle,
    // back buffer and loading callbacks must reach the same DefaultLoadControl owner explicitly.
    override fun onPrepared(playerId: PlayerId) = delegate.onPrepared(playerId)

    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection?>,
    ) = delegate.onTracksSelected(parameters, trackGroups, trackSelections)

    override fun onStopped(playerId: PlayerId) = delegate.onStopped(playerId)

    override fun onReleased(playerId: PlayerId) = delegate.onReleased(playerId)

    override fun getAllocator(playerId: PlayerId): Allocator = delegate.getAllocator(playerId)

    override fun getBackBufferDurationUs(playerId: PlayerId): Long = delegate.getBackBufferDurationUs(playerId)

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean =
        delegate.retainBackBufferFromKeyframe(playerId)

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean =
        delegate.shouldContinueLoading(parameters)

    override fun shouldContinuePreloading(
        playerId: PlayerId,
        timeline: Timeline,
        mediaPeriodId: MediaPeriodId,
        bufferedDurationUs: Long,
    ): Boolean = delegate.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        if (delegate.shouldStartPlayback(parameters)) return true
        if (parameters.rebuffering || parameters.targetLiveOffsetUs != C.TIME_UNSET) return false
        val item = currentItem() ?: return false
        val thresholdMs =
            playbackStartupThresholdMs(
                mode,
                evidence.snapshot().copy(
                    remote = item.url.isRemotePlaybackSource(),
                    mediaBitrateBitsPerSecond = item.activeVersion?.sourceBitrateBps?.toLong() ?: 0L,
                    speed = parameters.playbackSpeed,
                ),
            )
        return parameters.bufferedDurationUs / parameters.playbackSpeed >= thresholdMs * 1_000L
    }
}

internal fun String.isRemotePlaybackSource(): Boolean =
    substringBefore(':').lowercase() !in setOf("file", "content", "android.resource")

/** Uses actual network/cache reads; no disk access or guessed Wi-Fi speed on the playback thread. */
@UnstableApi
internal class StartupTransferEvidence(
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
) : TransferListener {
    private var startedAtMs = -1L
    private var lastNetworkProgressMs = -1L
    private var lastCacheProgressMs = -1L
    private var networkBytes = 0L
    private var cachedBytes = 0L

    @Synchronized
    fun reset() {
        startedAtMs = -1L
        lastNetworkProgressMs = -1L
        lastCacheProgressMs = -1L
        networkBytes = 0L
        cachedBytes = 0L
    }

    override fun onTransferInitializing(
        source: DataSource,
        dataSpec: DataSpec,
        isNetwork: Boolean,
    ) = Unit

    override fun onTransferStart(
        source: DataSource,
        dataSpec: DataSpec,
        isNetwork: Boolean,
    ) = Unit

    override fun onBytesTransferred(
        source: DataSource,
        dataSpec: DataSpec,
        isNetwork: Boolean,
        bytesTransferred: Int,
    ) {
        recordBytes(isNetwork, bytesTransferred)
    }

    @Synchronized
    internal fun recordBytes(
        isNetwork: Boolean,
        bytesTransferred: Int,
    ) {
        if (bytesTransferred <= 0) return
        val now = nowMs()
        if (startedAtMs < 0L || now - startedAtMs > WINDOW_MS) {
            startedAtMs = now
            networkBytes = 0L
            cachedBytes = 0L
            lastNetworkProgressMs = -1L
            lastCacheProgressMs = -1L
        }
        if (isNetwork) {
            networkBytes += bytesTransferred
            lastNetworkProgressMs = now
        } else {
            cachedBytes += bytesTransferred
            lastCacheProgressMs = now
        }
    }

    override fun onTransferEnd(
        source: DataSource,
        dataSpec: DataSpec,
        isNetwork: Boolean,
    ) = Unit

    @Synchronized
    fun snapshot(): PlaybackStartupConditions {
        val now = nowMs()
        val duration = if (startedAtMs >= 0L) (now - startedAtMs).coerceAtLeast(0L) else 0L
        val networkFresh = lastNetworkProgressMs >= 0L && now - lastNetworkProgressMs <= NETWORK_FRESHNESS_MS
        val cacheFresh = lastCacheProgressMs >= 0L && now - lastCacheProgressMs <= WINDOW_MS
        return PlaybackStartupConditions(
            remote = true,
            measuredThroughputBitsPerSecond =
                if (networkFresh && duration > 0L) (networkBytes.toDouble() * 8_000.0 / duration).toLong() else 0L,
            measuredNetworkBytes = if (networkFresh) networkBytes else 0L,
            measurementDurationMs = if (networkFresh) duration else 0L,
            cachedBytesRead = if (cacheFresh) cachedBytes else 0L,
        )
    }
}

private const val WINDOW_MS = 2_000L
private const val NETWORK_FRESHNESS_MS = 500L
