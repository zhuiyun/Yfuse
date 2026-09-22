package com.yfuse.core.designsystem

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

internal enum class ArtworkColorSample { Dominant, PageFade }

internal data class ArtworkColorKey(
    val url: String,
    val sample: ArtworkColorSample,
    val aspectRatio: Float = 0f,
    val fadeFraction: Float = 0.25f,
    val algorithmVersion: Int = 2,
)

internal fun artworkPageColorKey(
    url: String,
    aspectRatio: Float,
    fadeFraction: Float,
): ArtworkColorKey =
    ArtworkColorKey(
        url,
        ArtworkColorSample.PageFade,
        aspectRatio.takeIf { it.isFinite() && it > 0f } ?: 0f,
        fadeFraction.takeIf(Float::isFinite)?.coerceIn(0.02f, 1f) ?: 0.25f,
    )

/** Stores only derived ARGB values. Coil remains the sole owner of image memory and disk caching. */
internal class ArtworkColorCache(
    private val scope: CoroutineScope,
    private val maxEntries: Int = 128,
    maxConcurrent: Int = 2,
) {
    private class Flight(
        val token: Any,
        val result: Deferred<Int?>,
        var subscribers: Int = 0,
    )

    private val mutex = Mutex()
    private val permits = Semaphore(maxConcurrent)
    private val colors = linkedMapOf<ArtworkColorKey, Int>()
    private val flights = mutableMapOf<ArtworkColorKey, Flight>()

    init {
        require(maxEntries > 0)
    }

    suspend fun get(
        key: ArtworkColorKey,
        extract: suspend () -> Int?,
    ): Int? {
        val flight =
            mutex.withLock {
                colors.remove(key)?.let {
                    colors[key] = it
                    return it
                }
                flights
                    .getOrPut(key) {
                        val token = Any()
                        val work =
                            scope.async(start = CoroutineStart.LAZY) {
                                val value = permits.withPermit { extract() }
                                currentCoroutineContext().ensureActive()
                                if (value != null) {
                                    mutex.withLock {
                                        currentCoroutineContext().ensureActive()
                                        if (flights[key]?.token === token) {
                                            colors[key] = value
                                            while (colors.size > maxEntries) colors.remove(colors.keys.first())
                                        }
                                    }
                                }
                                value
                            }
                        Flight(token, work)
                    }.also { it.subscribers++ }
            }
        try {
            flight.result.start()
            return flight.result.await()
        } finally {
            // Cancellation must release the subscription even though its caller is already inactive.
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                mutex.withLock {
                    flight.subscribers--
                    if (flight.subscribers == 0) {
                        if (flights[key] === flight) flights.remove(key)
                        flight.result.cancel()
                    }
                }
            }
        }
    }
}
