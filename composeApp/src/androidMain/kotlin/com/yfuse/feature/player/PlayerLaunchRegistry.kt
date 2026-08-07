package com.yfuse.feature.player

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlaybackQuality
import com.yfuse.core.model.PlayerEngine
import java.util.UUID

/**
 * Everything the dedicated player needs at launch.
 *
 * The queue deliberately stays in-process. Authenticated stream URLs must never be copied
 * into an Activity intent: a large series can exceed Binder's transaction limit, and the
 * system is also free to retain an intent while recreating a task.
 */
internal data class PlayerLaunchRequest(
    val items: List<PlayerMediaItem>,
    val startIndex: Int,
    val startPositionMs: Long,
    val engine: PlayerEngine,
    val decoder: DecoderMode,
    val autoNext: Boolean,
    val quality: PlaybackQuality,
) {
    companion object {
        const val MAX_QUEUE_ITEMS = 10_000

        fun create(
            items: List<PlayerMediaItem>,
            startIndex: Int,
            startPositionMs: Long,
            engine: PlayerEngine,
            decoder: DecoderMode,
            autoNext: Boolean,
            quality: PlaybackQuality,
        ): PlayerLaunchRequest {
            require(items.isNotEmpty()) { "A player launch requires at least one item" }
            require(items.size <= MAX_QUEUE_ITEMS) { "Player queue exceeds the in-process limit" }
            val snapshot = items.toList()
            return PlayerLaunchRequest(
                items = snapshot,
                startIndex = startIndex.coerceIn(snapshot.indices),
                startPositionMs = startPositionMs.coerceAtLeast(0L),
                engine = engine,
                decoder = decoder,
                autoNext = autoNext,
                quality = quality,
            )
        }
    }
}

/** Non-secret, bounded context for explaining an expired process-local launch. */
internal data class PlayerLaunchFallback(
    val itemId: String,
    val serverId: String?,
    val title: String,
) {
    companion object {
        const val MAX_ITEM_ID_CHARS = 256
        const val MAX_SERVER_ID_CHARS = 128
        const val MAX_TITLE_CHARS = 256

        fun from(request: PlayerLaunchRequest): PlayerLaunchFallback? {
            val item = request.items.getOrNull(request.startIndex) ?: return null
            val itemId = item.id.takeIf {
                it.isNotBlank() && it.length <= MAX_ITEM_ID_CHARS
            } ?: return null
            return PlayerLaunchFallback(
                itemId = itemId,
                serverId = item.serverId?.takeIf { it.length <= MAX_SERVER_ID_CHARS },
                title = item.title.take(MAX_TITLE_CHARS),
            )
        }
    }
}

/**
 * The complete and intentionally tiny payload written to [Intent].
 *
 * [estimatedParcelBytes] is conservative rather than exact. It counts four bytes per UTF-16
 * code unit plus per-entry and Intent overhead, keeping the production payload below a fixed
 * ceiling without depending on a device-specific Parcel implementation.
 */
internal data class PlayerLaunchIntentPayload(
    val launchId: String,
    val fallback: PlayerLaunchFallback?,
) {
    init {
        require(launchId.isNotBlank() && launchId.length <= MAX_LAUNCH_ID_CHARS)
    }

    val stringExtras: Map<String, String>
        get() = buildMap {
            put(EXTRA_LAUNCH_ID, launchId)
            fallback?.let { value ->
                put(EXTRA_FALLBACK_ITEM_ID, value.itemId)
                value.serverId?.let { put(EXTRA_FALLBACK_SERVER_ID, it) }
                put(EXTRA_FALLBACK_TITLE, value.title)
            }
        }

    val estimatedParcelBytes: Int
        get() = INTENT_BASE_OVERHEAD_BYTES + stringExtras.entries.sumOf { (key, value) ->
            EXTRA_ENTRY_OVERHEAD_BYTES + (key.length + value.length) * BYTES_PER_CHAR_BOUND
        }

    fun writeTo(intent: Intent) {
        check(estimatedParcelBytes <= MAX_ESTIMATED_PARCEL_BYTES) {
            "Player launch intent exceeded its fixed parcel budget"
        }
        stringExtras.forEach(intent::putExtra)
    }

    companion object {
        const val MAX_LAUNCH_ID_CHARS = 36
        const val MAX_ESTIMATED_PARCEL_BYTES = 16 * 1024

        private const val EXTRA_LAUNCH_ID = "yfuse.player.launchId"
        private const val EXTRA_FALLBACK_ITEM_ID = "yfuse.player.fallback.itemId"
        private const val EXTRA_FALLBACK_SERVER_ID = "yfuse.player.fallback.serverId"
        private const val EXTRA_FALLBACK_TITLE = "yfuse.player.fallback.title"
        private const val INTENT_BASE_OVERHEAD_BYTES = 4 * 1024
        private const val EXTRA_ENTRY_OVERHEAD_BYTES = 128
        private const val BYTES_PER_CHAR_BOUND = 4

        fun create(request: PlayerLaunchRequest, launchId: String): PlayerLaunchIntentPayload =
            PlayerLaunchIntentPayload(
                launchId = launchId,
                fallback = PlayerLaunchFallback.from(request),
            )

        fun readFrom(intent: Intent): PlayerLaunchIntentPayload? {
            val launchId = intent.getStringExtra(EXTRA_LAUNCH_ID)
                ?.takeIf { it.isNotBlank() && it.length <= MAX_LAUNCH_ID_CHARS }
                ?: return null
            val itemId = intent.getStringExtra(EXTRA_FALLBACK_ITEM_ID)
                ?.takeIf { it.isNotBlank() && it.length <= PlayerLaunchFallback.MAX_ITEM_ID_CHARS }
            val serverId = intent.getStringExtra(EXTRA_FALLBACK_SERVER_ID)
                ?.takeIf { it.length <= PlayerLaunchFallback.MAX_SERVER_ID_CHARS }
            val title = intent.getStringExtra(EXTRA_FALLBACK_TITLE)
                ?.take(PlayerLaunchFallback.MAX_TITLE_CHARS)
                .orEmpty()
            return PlayerLaunchIntentPayload(
                launchId = launchId,
                fallback = itemId?.let {
                    PlayerLaunchFallback(
                        itemId = it,
                        serverId = serverId,
                        title = title,
                    )
                },
            )
        }
    }
}

/** Thread-safe backing store, separated from the singleton so expiry is deterministic in tests. */
internal class PlayerLaunchRegistryStore(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
    private val tokenFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private data class Entry(
        val request: PlayerLaunchRequest,
        val registeredAtMs: Long,
    )

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>()

    init {
        require(maxEntries > 0)
        require(ttlMs > 0L)
    }

    fun register(request: PlayerLaunchRequest): String = synchronized(lock) {
        val now = elapsedRealtimeMs()
        removeExpired(now)
        while (entries.size >= maxEntries) {
            entries.remove(entries.keys.first())
        }
        val token = generateUniqueToken()
        entries[token] = Entry(request, now)
        token
    }

    fun consume(token: String?): PlayerLaunchRequest? = synchronized(lock) {
        removeExpired(elapsedRealtimeMs())
        token?.let(entries::remove)?.request
    }

    fun discard(token: String?) = synchronized(lock) {
        token?.let(entries::remove)
        Unit
    }

    internal fun entryCount(): Int = synchronized(lock) {
        removeExpired(elapsedRealtimeMs())
        entries.size
    }

    private fun generateUniqueToken(): String {
        repeat(MAX_TOKEN_ATTEMPTS) {
            val candidate = tokenFactory()
            require(candidate.isNotBlank() && candidate.length <= PlayerLaunchIntentPayload.MAX_LAUNCH_ID_CHARS) {
                "Player launch token must be bounded"
            }
            if (candidate !in entries) return candidate
        }
        error("Could not allocate a unique player launch token")
    }

    private fun removeExpired(nowMs: Long) {
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            if (nowMs >= entry.registeredAtMs && nowMs - entry.registeredAtMs >= ttlMs) {
                iterator.remove()
            }
        }
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 4
        const val DEFAULT_TTL_MS = 2 * 60_000L
        private const val MAX_TOKEN_ATTEMPTS = 8
    }
}

/** Adds eager token-only expiry scheduling around the bounded backing store. */
internal class PlayerLaunchRegistryController(
    private val store: PlayerLaunchRegistryStore,
    private val ttlMs: Long = PlayerLaunchRegistryStore.DEFAULT_TTL_MS,
    private val scheduleDiscard: (
        token: String,
        delayMs: Long,
        discard: (String?) -> Unit,
    ) -> Unit,
) {
    init {
        require(ttlMs > 0L)
    }

    fun register(request: PlayerLaunchRequest): String {
        val token = store.register(request)
        try {
            // The delayed work captures only this bounded token and a store method reference.
            // It never extends the lifetime of the potentially large playback request itself.
            scheduleDiscard(token, ttlMs, store::discard)
        } catch (error: Throwable) {
            store.discard(token)
            throw error
        }
        return token
    }

    fun consume(token: String?): PlayerLaunchRequest? = store.consume(token)

    fun discard(token: String?) = store.discard(token)
}

internal object PlayerLaunchRegistry {
    private val expiryHandler = Handler(Looper.getMainLooper())
    private val controller = PlayerLaunchRegistryController(
        store = PlayerLaunchRegistryStore(),
        scheduleDiscard = { token, delayMs, discard ->
            expiryHandler.postDelayed({ discard(token) }, delayMs)
        },
    )

    fun register(request: PlayerLaunchRequest): String = controller.register(request)

    fun consume(token: String?): PlayerLaunchRequest? = controller.consume(token)

    fun discard(token: String?) = controller.discard(token)
}

internal sealed interface PlayerLaunchResolution {
    data class Ready(val request: PlayerLaunchRequest) : PlayerLaunchResolution

    data class Expired(val fallback: PlayerLaunchFallback?) : PlayerLaunchResolution
}

/** Shared by the Activity and unit tests so malformed or expired launches always fail closed. */
internal fun resolvePlayerLaunch(
    retained: PlayerLaunchRequest?,
    payload: PlayerLaunchIntentPayload?,
    consume: (String?) -> PlayerLaunchRequest? = PlayerLaunchRegistry::consume,
): PlayerLaunchResolution {
    retained?.let { return PlayerLaunchResolution.Ready(it) }
    val consumed = payload?.let { consume(it.launchId) }
    return if (consumed != null) {
        PlayerLaunchResolution.Ready(consumed)
    } else {
        PlayerLaunchResolution.Expired(payload?.fallback)
    }
}

/** A fresh intent must never let the currently retained request shadow its one-shot token. */
internal fun resolveFreshPlayerLaunch(
    payload: PlayerLaunchIntentPayload,
    consume: (String?) -> PlayerLaunchRequest? = PlayerLaunchRegistry::consume,
): PlayerLaunchResolution = resolvePlayerLaunch(
    retained = null,
    payload = payload,
    consume = consume,
)

/** Retains the already-consumed request through configuration recreation, never process death. */
internal class PlayerLaunchViewModel : ViewModel() {
    var request: PlayerLaunchRequest? = null
    var resume: Pair<Int, Long>? = null
}
