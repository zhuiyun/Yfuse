package com.yfuse.core.data

import com.yfuse.core.model.SavedServer
import kotlin.time.TimeSource

/** Failed availability lookups cool down by account/endpoint, never by display name. */
internal class SourceLookupCooldown(
    private val cooldownMs: Long = 30_000L,
    private val nowMs: () -> Long =
        TimeSource.Monotonic.markNow().let { start -> { start.elapsedNow().inWholeMilliseconds } },
) {
    private val until = LinkedHashMap<SavedServer, Long>()

    @Synchronized
    fun blocked(server: SavedServer): Boolean {
        val deadline = until[server] ?: return false
        if (nowMs() < deadline) return true
        until.remove(server)
        return false
    }

    @Synchronized
    fun record(server: SavedServer, reachable: Boolean) {
        until.remove(server)
        if (!reachable) until[server] = nowMs() + cooldownMs
        while (until.size > 32) until.remove(until.keys.first())
    }
}
