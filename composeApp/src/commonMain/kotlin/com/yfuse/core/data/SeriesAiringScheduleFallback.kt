package com.yfuse.core.data

import com.yfuse.core.model.AiringEpisode
import com.yfuse.core.util.currentEpochMillis
import com.yfuse.core.util.isoEpochDay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Exact series lookups fill gaps in discovery; only provider-published dates are returned. */
class SeriesAiringScheduleFallback(
    private val fetch: suspend (Int, String) -> Result<List<AiringEpisode>>,
    private val nowEpochMs: () -> Long = ::currentEpochMillis,
) {
    private data class Cached(
        val result: Result<List<AiringEpisode>>,
        val refreshAt: Long,
    )

    private val mutex = Mutex()
    private val requests = Semaphore(3)
    private val cache = linkedMapOf<Int, Cached>()
    private val pending = mutableMapOf<Int, CompletableDeferred<Result<List<AiringEpisode>>>>()

    suspend fun series(
        tmdbId: Int,
        title: String,
        forceRefresh: Boolean = false,
    ): Result<List<AiringEpisode>> {
        require(tmdbId > 0)
        var owner = false
        val request =
            mutex.withLock {
                cache[tmdbId]?.takeIf { !forceRefresh && nowEpochMs() < it.refreshAt }?.let { return it.result }
                pending.getOrPut(tmdbId) {
                    owner = true
                    CompletableDeferred()
                }
            }
        if (!owner) return request.await()
        try {
            val fetched =
                requests
                    .withPermit {
                        withTimeoutOrNull(6_000L) { fetch(tmdbId, title) }
                            ?: Result.failure(IllegalStateException("该剧播出日期查询超时"))
                    }.map { rows ->
                        rows.filter { row ->
                            row.showTmdbId == tmdbId &&
                                !row.isMovie &&
                                row.seasonNumber > 0 &&
                                row.episodeNumber > 0 &&
                                isoEpochDay(row.airDate) != null
                        }
                    }
            val result =
                mutex.withLock {
                    val result =
                        if (fetched.isFailure) {
                            cache[tmdbId]?.result?.takeIf {
                                it.isSuccess
                            } ?: fetched
                        } else {
                            fetched
                        }
                    cache[tmdbId] = Cached(result, nowEpochMs() + if (fetched.isSuccess) 20 * 60_000L else 60_000L)
                    while (cache.size > 128) cache.remove(cache.keys.first())
                    result
                }
            request.complete(result)
            return result
        } catch (cancelled: CancellationException) {
            request.completeExceptionally(cancelled)
            throw cancelled
        } catch (error: Throwable) {
            val failure = Result.failure<List<AiringEpisode>>(error)
            request.complete(failure)
            return failure
        } finally {
            withContext(NonCancellable) { mutex.withLock { if (pending[tmdbId] === request) pending.remove(tmdbId) } }
        }
    }
}
