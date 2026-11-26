package com.yfuse.core.data

import com.russhwolf.settings.Settings
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.CarouselBatch
import com.yfuse.core.model.HOME_CAROUSEL_SIZE
import com.yfuse.core.util.isoEpochDay
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The displayed cards are kept with their IDs so a trimmed feed cache can still restore the reel. */
internal class CarouselBatchCache<T>(
    private val settings: () -> Settings,
    itemSerializer: KSerializer<T>,
) {
    @Serializable
    private data class Entry<T>(
        val owner: String?,
        val batch: CarouselBatch<T>,
    )

    private val serializer = Entry.serializer(itemSerializer)
    private val json = Json { ignoreUnknownKeys = true }

    fun read(key: String, owner: String? = null): CarouselBatch<T>? =
        access("read") {
            val raw = settings().getStringOrNull(key) ?: return@access null
            check(raw.length <= MAX_CHARS)
            val entry = json.decodeFromString(serializer, raw)
            entry.batch.takeIf {
                entry.owner == owner && it.items.size <= HOME_CAROUSEL_SIZE && isoEpochDay(it.date) != null
            }
        }

    fun write(key: String, batch: CarouselBatch<T>, owner: String? = null) {
        access("write") {
            val encoded = json.encodeToString(serializer, Entry(owner, batch))
            check(encoded.length <= MAX_CHARS)
            settings().putString(key, encoded)
        }
    }

    private inline fun <R> access(operation: String, block: () -> R): R? =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AppLog.warning(
                category = "carousel",
                event = "cache_${operation}_failed",
                message = "Carousel batch cache is unavailable",
                throwable = error,
            )
            null
        }

    private companion object {
        const val MAX_CHARS = 96_000
    }
}
