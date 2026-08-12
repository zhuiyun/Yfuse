package com.yfuse.core.data

import com.russhwolf.settings.Settings
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.LibraryCounts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A server's title totals and when they were last read from it. */
@Serializable
data class ServerStats(
    val movieCount: Int,
    val seriesCount: Int,
    val updatedAtEpochMs: Long,
)

/**
 * Movie and series totals per saved server, cached across launches.
 *
 * The figures come from one `/Items/Counts` request, but the grid draws before any of the
 * twelve servers has answered — and an offline server will never answer. Persisting the last
 * known numbers means a card shows what the library held the last time it was reachable
 * rather than a dash, which is the honest answer to "what is on this server" even when the
 * machine is currently down.
 */
class ServerStatsStore(
    private val settings: Settings,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
) {

    private companion object {
        const val KEY = "servers.stats"
        const val MAX_ENTRIES = 100
    }

    @Serializable
    private data class Persisted(val stats: Map<String, ServerStats> = emptyMap())

    private val json = Json { ignoreUnknownKeys = true }

    private val _stats = MutableStateFlow(load())

    /** Server id → its last known totals. */
    val stats: StateFlow<Map<String, ServerStats>> = _stats.asStateFlow()

    fun statsFor(serverId: String): ServerStats? = _stats.value[serverId]

    fun record(serverId: String, counts: LibraryCounts, atEpochMs: Long = nowEpochMs()) {
        if (serverId.isBlank()) return
        val entry = ServerStats(
            movieCount = counts.movieCount.coerceAtLeast(0),
            seriesCount = counts.seriesCount.coerceAtLeast(0),
            updatedAtEpochMs = atEpochMs,
        )
        if (_stats.value[serverId] == entry) return
        commit(_stats.value + (serverId to entry))
    }

    /** Drops entries for servers that are no longer saved. */
    fun retainOnly(serverIds: Set<String>) {
        val kept = _stats.value.filterKeys { it in serverIds }
        if (kept.size != _stats.value.size) commit(kept)
    }

    private fun commit(value: Map<String, ServerStats>) {
        val bounded = if (value.size <= MAX_ENTRIES) {
            value
        } else {
            value.entries
                .sortedByDescending { it.value.updatedAtEpochMs }
                .take(MAX_ENTRIES)
                .associate { it.toPair() }
        }
        _stats.value = bounded
        runCatching {
            settings.putString(KEY, json.encodeToString(Persisted.serializer(), Persisted(bounded)))
        }.onFailure { error ->
            AppLog.warning(
                category = "server.stats",
                event = "persist_failed",
                message = "Server library totals could not be saved",
                throwable = error,
            )
        }
    }

    private fun load(): Map<String, ServerStats> {
        val raw = settings.getStringOrNull(KEY) ?: return emptyMap()
        return runCatching {
            json.decodeFromString(Persisted.serializer(), raw).stats
                .filterValues { it.movieCount >= 0 && it.seriesCount >= 0 }
        }.getOrDefault(emptyMap())
    }
}

/**
 * The figure itself, unabbreviated — five digits fit, and rounding 40788 to 41k throws away
 * the only thing that distinguishes one large library from another. Beyond six digits, which
 * no real library reaches, it degrades rather than wrapping the card.
 */
fun formatServerCount(value: Int?): String = when {
    value == null || value < 0 -> "--"
    value < 1_000_000 -> value.toString()
    else -> "${value / 1_000_000}M+"
}
