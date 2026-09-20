package com.yfuse.core.data

import com.yfuse.core.logging.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.time.TimeSource

/** Short-lived metadata only: never cache a negotiated session or a transcode URL here. */
internal class PlaybackMetadataCache<K, V>(
    private val ttlMs: Long = 15_000L,
    private val capacity: Int = 16,
    private val nowMs: () -> Long = monotonicMillis(),
) {
    private class Entry<V> {
        val result = CompletableDeferred<Result<V>>()
        var completedAtMs: Long? = null
    }

    private val lock = Any()
    private val entries = LinkedHashMap<K, Entry<V>>()

    suspend fun get(
        key: K,
        reuse: Boolean = true,
        load: suspend () -> V,
    ): V {
        while (true) {
            var owner = false
            val entry =
                synchronized(lock) {
                    val previous = entries[key]
                    val fresh = previous?.completedAtMs?.let { nowMs() - it in 0 until ttlMs } ?: true
                    if (reuse && previous != null && fresh) {
                        previous
                    } else {
                        owner = true
                        Entry<V>().also {
                            entries.remove(key)
                            entries[key] = it
                            while (entries.size > capacity) entries.remove(entries.keys.first())
                        }
                    }
                }
            if (owner) {
                val result =
                    try {
                        Result.success(load())
                    } catch (error: Throwable) {
                        Result.failure(error)
                    }
                synchronized(lock) {
                    entry.completedAtMs = nowMs()
                    if (result.isFailure && entries[key] === entry) entries.remove(key)
                }
                entry.result.complete(result)
                return result.getOrThrow()
            }
            val result = entry.result.await()
            if (result.isSuccess) {
                AppLog.info(
                    "feature.player",
                    "playback_metadata_reused",
                    "Reused recent source metadata",
                )
            }
            // A cancelled detail-page owner must not cancel an unrelated launching player.
            if (result.exceptionOrNull() is CancellationException) {
                currentCoroutineContext().ensureActive()
                continue
            }
            return result.getOrThrow()
        }
    }

    fun invalidate(predicate: (K) -> Boolean) =
        synchronized(lock) {
            entries.keys.filter(predicate).forEach(entries::remove)
        }
}

private fun monotonicMillis(): () -> Long {
    val origin = TimeSource.Monotonic.markNow()
    return { origin.elapsedNow().inWholeMilliseconds }
}
