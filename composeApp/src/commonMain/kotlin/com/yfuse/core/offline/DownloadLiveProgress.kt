package com.yfuse.core.offline

import kotlin.math.roundToInt

/** How one stretch of the Android 16 live-update bar for downloads is drawn. */
enum class DownloadLiveState { Done, Downloading, Waiting, Failed }

data class DownloadLiveSegment(
    /** In [DOWNLOAD_LIVE_UNITS] per episode. */
    val length: Int,
    val state: DownloadLiveState,
)

/**
 * A download batch as a segmented bar, 按集分段: one stretch per episode, finished ones first,
 * then those under way, then those still waiting, the way the bar fills.
 */
data class DownloadLiveProgress(
    val segments: List<DownloadLiveSegment>,
    /** How far the bar is filled, in the segments' units. */
    val progress: Int,
    val done: Int,
    val total: Int,
    /** 0..100 across the batch; by bytes when every size is known, else by episodes. */
    val percent: Int,
    val downloading: List<OfflineMedia>,
    val paused: Boolean,
) {
    val max: Int get() = segments.sumOf { it.length }
}

/** An episode's length on the bar: enough for a downloading one to show its own progress. */
const val DOWNLOAD_LIVE_UNITS = 100

/** Android 16 draws at most ten segments; a longer list is folded into one plain bar. */
const val DOWNLOAD_LIVE_MAX_SEGMENTS = 10

/**
 * The bar for [batch], or null when it holds nothing. More episodes than Android will draw are
 * folded, one stretch per state, rather than left for the system to flatten into a single bar.
 */
fun downloadLiveProgress(batch: List<OfflineMedia>): DownloadLiveProgress? {
    if (batch.isEmpty()) return null
    val done = batch.count { it.status == DownloadStatus.Completed }
    val downloading = batch.filter { it.status == DownloadStatus.Downloading }
    val failed = batch.count { it.status == DownloadStatus.Failed }
    val waiting = batch.size - done - downloading.size - failed
    val states =
        List(done) { DownloadLiveState.Done } +
            List(downloading.size) { DownloadLiveState.Downloading } +
            List(waiting) { DownloadLiveState.Waiting } +
            List(failed) { DownloadLiveState.Failed }
    val segments =
        if (states.size <= DOWNLOAD_LIVE_MAX_SEGMENTS) {
            states.map { DownloadLiveSegment(DOWNLOAD_LIVE_UNITS, it) }
        } else {
            states
                .groupBy { it }
                .map { (state, run) -> DownloadLiveSegment(run.size * DOWNLOAD_LIVE_UNITS, state) }
        }
    val progress =
        done * DOWNLOAD_LIVE_UNITS +
            downloading.sumOf { (it.progress * DOWNLOAD_LIVE_UNITS).roundToInt().coerceIn(0, DOWNLOAD_LIVE_UNITS) }
    val byEpisode = progress * 100 / (batch.size * DOWNLOAD_LIVE_UNITS)
    val percent =
        batch
            .takeIf { items -> items.all { it.totalBytes > 0L } }
            ?.let { items ->
                val total = items.sumOf { it.totalBytes.toDouble() }
                val received =
                    items.sumOf {
                        if (it.status == DownloadStatus.Completed) {
                            it.totalBytes.toDouble()
                        } else {
                            it.downloadedBytes.coerceIn(0L, it.totalBytes).toDouble()
                        }
                    }
                (received / total * 100).toInt()
            } ?: byEpisode
    return DownloadLiveProgress(
        segments = segments,
        progress = progress,
        done = done,
        total = batch.size,
        percent = percent.coerceIn(0, 100),
        downloading = downloading,
        paused =
            downloading.isEmpty() &&
                batch.none { it.status == DownloadStatus.Queued || it.status == DownloadStatus.WaitingForWifi },
    )
}

/**
 * Which downloads one live update is about, in the order they were first seen: everything that
 * has waited or run since the queue last went idle, finished ones included, so 3 / 8 集 does not
 * start counting again from 1 / 5 as episodes complete. Starts over once nothing is left to do,
 * and forgets downloads that were removed.
 */
fun nextDownloadLiveBatch(
    members: List<String>,
    items: List<OfflineMedia>,
): List<String> {
    val pending = items.filter { it.status != DownloadStatus.Completed }
    if (pending.isEmpty()) return emptyList()
    val present = items.mapTo(HashSet()) { it.id }
    val kept = members.filter { it in present }
    return kept + pending.map { it.id }.filterNot { it in kept }
}
