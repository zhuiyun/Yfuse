package com.yfuse.core.data

import com.russhwolf.settings.Settings
import com.yfuse.core.network.TheOtherDbQuery
import com.yfuse.core.network.TheOtherDbVersion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Cache contains provider data only. It never writes to SkipSegmentPreferences. */
class TheOtherDbRepository(
    private val settings: Settings,
    private val fetch: suspend (TheOtherDbQuery) -> List<TheOtherDbVersion>,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    @Serializable
    private data class Entry(
        val savedAt: Long,
        val versions: List<TheOtherDbVersion>,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val cache = linkedMapOf<String, Entry>()
    private val failures = mutableMapOf<String, Long>()
    private var loaded = false

    /** Null means unavailable; empty means a successful response with no records. */
    suspend fun versions(query: TheOtherDbQuery): List<TheOtherDbVersion>? =
        withContext(dispatcher) {
            mutex.withLock {
                if (!loaded) {
                    val saved = runCatching { settings.getStringOrNull(CACHE_KEY) }.getOrNull()
                    if (saved != null && saved.length <= MAX_CACHE_CHARS) {
                        runCatching { json.decodeFromString<Map<String, Entry>>(saved) }
                            .getOrNull()
                            ?.entries
                            ?.toList()
                            ?.takeLast(64)
                            ?.forEach { (key, value) -> cache[key] = value }
                    }
                    loaded = true
                }
                val key = query.cacheKey
                val now = nowMs()
                cache[key]?.let { entry ->
                    val ttl = if (entry.versions.any { it.markers.isNotEmpty() }) VALID_TTL else EMPTY_TTL
                    if (now - entry.savedAt in 0 until ttl) return@withLock entry.versions
                }
                if (failures[key]?.let { now - it in 0 until RETRY_DELAY } == true) return@withLock null
                val result =
                    try {
                        withTimeoutOrNull(10_000L) { fetch(query) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                if (result == null) {
                    failures[key] = now
                    if (failures.size > 64) failures.remove(failures.keys.first())
                    return@withLock null
                }
                failures.remove(key)
                cache.remove(key)
                cache[key] = Entry(now, result)
                while (cache.size > 64) cache.remove(cache.keys.first())
                // A full preference store must not stop playback or discard this successful result.
                runCatching {
                    var encoded = json.encodeToString(cache.toMap())
                    while (encoded.length > MAX_CACHE_CHARS && cache.isNotEmpty()) {
                        cache.remove(cache.keys.first())
                        encoded = json.encodeToString(cache.toMap())
                    }
                    settings.putString(CACHE_KEY, encoded)
                }
                result
            }
        }

    private companion object {
        const val CACHE_KEY = "player.skip.theotherdb.cache.v1"
        const val VALID_TTL = 7 * 24 * 60 * 60 * 1_000L
        const val EMPTY_TTL = 6 * 60 * 60 * 1_000L
        const val RETRY_DELAY = 60_000L
        const val MAX_CACHE_CHARS = 512_000
    }
}
