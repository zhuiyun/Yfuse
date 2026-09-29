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
                                            if (key.sample == ArtworkColorSample.Dominant) {
                                                ArtworkPlaceholderColors.record(key.url, value)
                                            }
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

/**
 * 图片渐进加载 for Emby and Plex, which send no BlurHash: the dominant colour already worked out
 * for a picture — by a hero, a detail page, the player — kept by the picture rather than by its
 * URL, so a card asking for the same artwork at another size can wash its skeleton in it.
 *
 * Read while a tile composes, so a plain lock rather than the cache's mutex; and colours only,
 * like the cache itself — Coil still owns every pixel.
 */
internal object ArtworkPlaceholderColors {
    private const val MAX_ENTRIES = 256
    private val lock = Any()
    private val colors = linkedMapOf<String, Int>()

    fun record(
        url: String,
        argb: Int,
    ) {
        val identity = artworkIdentity(url)
        synchronized(lock) {
            colors.remove(identity)
            colors[identity] = argb
            while (colors.size > MAX_ENTRIES) colors.remove(colors.keys.first())
        }
    }

    /** The colour remembered for the first of [urls] that has one. */
    fun peek(urls: List<String>): Int? {
        if (urls.isEmpty()) return null
        val identities = urls.map(::artworkIdentity)
        return synchronized(lock) { identities.firstNotNullOfOrNull { colors[it] } }
    }
}

/**
 * The picture a URL names, whatever size, quality, encoding or credentials it is asked for with:
 * an Emby or Jellyfin image path with its `tag`, a Plex transcode with the `url` it transcodes, a
 * TMDB file by name whichever size directory and host serve it.
 */
internal fun artworkIdentity(url: String): String {
    val queryStart = url.indexOf('?')
    val path = if (queryStart < 0) url else url.substring(0, queryStart)
    TmdbSizeDirectory.find(path)?.let { return "tmdb:" + path.substring(it.range.last + 1) }
    if (queryStart < 0) return path
    val kept =
        url
            .substring(queryStart + 1)
            .split('&')
            .filter { it.isNotEmpty() && it.substringBefore('=').lowercase() !in RenditionParameters }
            .sorted()
    return if (kept.isEmpty()) path else kept.joinToString("&", prefix = "$path?")
}

/** Query parameters that choose how a picture is served rather than which picture it is. */
private val RenditionParameters =
    setOf(
        "maxwidth",
        "maxheight",
        "width",
        "height",
        "fillwidth",
        "fillheight",
        "quality",
        "format",
        "minsize",
        "upscale",
        "api_key",
        "apikey",
        "x-plex-token",
        "x-emby-token",
    )

/** TMDB's size directory, `/t/p/w500`, between either image host and the file name. */
private val TmdbSizeDirectory = Regex("(tmdb|themoviedb)\\.org/t/p/[^/]+")
