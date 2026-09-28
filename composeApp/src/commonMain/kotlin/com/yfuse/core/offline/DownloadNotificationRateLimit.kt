package com.yfuse.core.offline

/** Progress is limited to one update a second; starting, pausing or finishing is shown immediately. */
internal class DownloadNotificationRateLimit {
    private var lastPostAt: Long? = null
    private var states = emptyMap<String, DownloadStatus>()

    fun shouldPost(
        items: List<OfflineMedia>,
        elapsedMs: Long,
    ): Boolean {
        val next = items.associate { it.id to it.status }
        val last = lastPostAt
        if (last != null && next == states && elapsedMs >= last && elapsedMs - last < 1_000L) return false
        lastPostAt = elapsedMs
        states = next
        return true
    }
}
