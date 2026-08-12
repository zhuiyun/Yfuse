package com.yfuse.core.data

import com.russhwolf.settings.Settings
import com.yfuse.core.logging.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * When each saved server was last watched from, so the 服务器 grid can say 「3 小时前」
 * rather than only whether the machine answers a ping.
 *
 * It is recorded locally rather than read back from Emby. `LastPlayedDate` on the server is
 * the account's history across every device, which answers a different question — the card
 * is about this phone's own habit, and it has to be right for a server that is offline at
 * the moment the grid is drawn, which is exactly when a remote lookup cannot run.
 *
 * Timestamps only: nothing here identifies what was watched.
 */
class ServerActivityStore(
    private val settings: Settings,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
) {

    private companion object {
        const val KEY = "servers.activity"
        /** Enough for any plausible registry; bounded so a stale file cannot grow forever. */
        const val MAX_ENTRIES = 100
    }

    @Serializable
    private data class Persisted(val watched: Map<String, Long> = emptyMap())

    private val json = Json { ignoreUnknownKeys = true }

    private val _lastWatched = MutableStateFlow(load())

    /** Server id → epoch milliseconds of the most recent playback started on this device. */
    val lastWatched: StateFlow<Map<String, Long>> = _lastWatched.asStateFlow()

    fun lastWatchedAt(serverId: String): Long? = _lastWatched.value[serverId]

    /** Called when playback actually starts against [serverId]. */
    fun recordWatch(serverId: String, atEpochMs: Long = nowEpochMs()) {
        if (serverId.isBlank() || atEpochMs <= 0L) return
        // A clock that has jumped backwards must not make an older session look like the
        // newest one; the card would then count down instead of up.
        val existing = _lastWatched.value[serverId]
        if (existing != null && existing >= atEpochMs) return
        commit(_lastWatched.value + (serverId to atEpochMs))
    }

    /** Drops entries for servers that are no longer saved. */
    fun retainOnly(serverIds: Set<String>) {
        val kept = _lastWatched.value.filterKeys { it in serverIds }
        if (kept.size != _lastWatched.value.size) commit(kept)
    }

    private fun commit(value: Map<String, Long>) {
        val bounded = if (value.size <= MAX_ENTRIES) {
            value
        } else {
            value.entries.sortedByDescending { it.value }.take(MAX_ENTRIES).associate { it.toPair() }
        }
        _lastWatched.value = bounded
        runCatching {
            settings.putString(KEY, json.encodeToString(Persisted.serializer(), Persisted(bounded)))
        }.onFailure { error ->
            AppLog.warning(
                category = "server.activity",
                event = "persist_failed",
                message = "Server watch timestamps could not be saved",
                throwable = error,
            )
        }
    }

    private fun load(): Map<String, Long> {
        val raw = settings.getStringOrNull(KEY) ?: return emptyMap()
        return runCatching {
            json.decodeFromString(Persisted.serializer(), raw).watched.filterValues { it > 0L }
        }.getOrDefault(emptyMap())
    }
}

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS
private const val DAY_MS = 24 * HOUR_MS
private const val MONTH_MS = 30 * DAY_MS
private const val YEAR_MS = 365 * DAY_MS

/**
 * 「上次观看」 as an age rather than a date — the card is answering "how long since I was
 * last here", and a timestamp makes the reader do that subtraction themselves.
 *
 * Kept free of clock and locale so the boundaries are testable. A future timestamp — a
 * device whose clock was corrected backwards after a session — reads as 刚刚 rather than as
 * a negative age.
 */
fun formatWatchedAgo(lastWatchedAtEpochMs: Long?, nowEpochMs: Long): String {
    val at = lastWatchedAtEpochMs?.takeIf { it > 0L } ?: return "从未观看"
    val age = nowEpochMs - at
    return when {
        age < MINUTE_MS -> "刚刚看过"
        age < HOUR_MS -> "${age / MINUTE_MS} 分钟前"
        age < DAY_MS -> "${age / HOUR_MS} 小时前"
        age < MONTH_MS -> "${age / DAY_MS} 天前"
        age < YEAR_MS -> "${age / MONTH_MS} 个月前"
        else -> "${age / YEAR_MS} 年前"
    }
}
