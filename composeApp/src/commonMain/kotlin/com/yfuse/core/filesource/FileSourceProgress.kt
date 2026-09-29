package com.yfuse.core.filesource

import com.russhwolf.settings.Settings
import com.yfuse.core.logging.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** How far into one file the viewer got, kept on this device only. */
@Serializable
data class FileSourceProgress(
    @SerialName("p") val positionMs: Long,
    @SerialName("d") val durationMs: Long = 0L,
    /** Reached the end once. A later rewatch keeps it and records its own position beside it. */
    @SerialName("w") val watched: Boolean = false,
    @SerialName("t") val updatedAtEpochMs: Long = 0L,
) {
    /** 0f..1f for a progress bar; null while the duration is unknown or nothing is resumable. */
    val fraction: Float?
        get() =
            if (durationMs <= 0L || resumePositionMs == 0L) {
                null
            } else {
                (positionMs.toDouble() / durationMs).toFloat().coerceIn(0f, 1f)
            }

    /** Where the next launch starts: 0 for a file barely begun or already finished. */
    val resumePositionMs: Long
        get() =
            if (positionMs < MIN_RESUME_POSITION_MS || (durationMs > 0L && isNearEnd(positionMs, durationMs))) {
                0L
            } else {
                positionMs
            }
}

/**
 * Resume points for 文件来源 files, keyed by their playback item id.
 *
 * A share has no server to hold progress, so this device does. One settings document holds the
 * whole map — at most [MAX_ENTRIES], oldest dropped first — and [record] writes it through only
 * when [persistNow] says so: the player reports twice a second, and rewriting the map on every
 * report would rewrite the app's settings file with it.
 */
class FileSourceProgressStore(
    private val settings: Settings,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), FileSourceProgress.serializer())
    private val lock = Any()
    private val _progress = MutableStateFlow(load())
    val progress: StateFlow<Map<String, FileSourceProgress>> = _progress.asStateFlow()
    private var unsaved = false

    fun get(key: String): FileSourceProgress? = _progress.value[key]

    /**
     * Records a position. Past [WATCHED_PERCENT] of the duration the file counts as watched and
     * its resume point is cleared, as a media server would.
     */
    fun record(
        key: String,
        positionMs: Long,
        durationMs: Long,
        persistNow: Boolean,
    ) {
        if (positionMs < 0L) return
        synchronized(lock) {
            val previous = _progress.value[key]
            val finished = durationMs > 0L && isNearEnd(positionMs, durationMs)
            val entry =
                FileSourceProgress(
                    positionMs = if (finished) 0L else positionMs,
                    durationMs = durationMs.takeIf { it > 0L } ?: previous?.durationMs ?: 0L,
                    watched = finished || previous?.watched == true,
                    updatedAtEpochMs = nowEpochMs(),
                )
            if (entry == previous) return
            _progress.value = (_progress.value + (key to entry)).bounded()
            unsaved = true
            if (persistNow) persistLocked()
        }
    }

    /** The player reached the natural end of [key]. */
    fun markWatched(
        key: String,
        durationMs: Long,
    ) {
        synchronized(lock) {
            val previous = _progress.value[key]
            _progress.value =
                (
                    _progress.value +
                        (
                            key to
                                FileSourceProgress(
                                    positionMs = 0L,
                                    durationMs = durationMs.takeIf { it > 0L } ?: previous?.durationMs ?: 0L,
                                    watched = true,
                                    updatedAtEpochMs = nowEpochMs(),
                                )
                        )
                ).bounded()
            unsaved = true
            persistLocked()
        }
    }

    /** 标为未看: forgets both the resume point and the watched mark. */
    fun clear(key: String) {
        synchronized(lock) {
            if (key !in _progress.value) return
            _progress.value = _progress.value - key
            unsaved = true
            persistLocked()
        }
    }

    /** Drops every file of a removed source; their ids share its prefix. */
    fun removeSource(sourceId: String) {
        val prefix = fileSourceItemIdPrefix(sourceId)
        synchronized(lock) {
            val kept = _progress.value.filterKeys { !it.startsWith(prefix) }
            if (kept.size == _progress.value.size) return
            _progress.value = kept
            unsaved = true
            persistLocked()
        }
    }

    /** Writes whatever [record] has held back. Cheap when nothing is pending. */
    fun flush() {
        synchronized(lock) { if (unsaved) persistLocked() }
    }

    private fun persistLocked() {
        runCatching { settings.putString(SETTINGS_KEY, json.encodeToString(serializer, _progress.value)) }
            .onSuccess { unsaved = false }
            .onFailure { error ->
                AppLog.warning(
                    category = LOG_CATEGORY,
                    event = "progress_persist_failed",
                    message = "File source resume points could not be saved",
                    attributes = mapOf("exception" to error::class.simpleName.orEmpty()),
                )
            }
    }

    private fun load(): Map<String, FileSourceProgress> {
        val raw = settings.getStringOrNull(SETTINGS_KEY) ?: return emptyMap()
        return runCatching { json.decodeFromString(serializer, raw) }
            .getOrElse {
                AppLog.warning(
                    category = LOG_CATEGORY,
                    event = "progress_unreadable",
                    message = "Saved file source resume points could not be read",
                )
                emptyMap()
            }.filterKeys(::isFileSourceItemId)
            .bounded()
    }

    private fun Map<String, FileSourceProgress>.bounded(): Map<String, FileSourceProgress> =
        if (size <= MAX_ENTRIES) {
            this
        } else {
            entries
                .sortedByDescending { it.value.updatedAtEpochMs }
                .take(MAX_ENTRIES)
                .associate { it.key to it.value }
        }

    private companion object {
        const val SETTINGS_KEY = "filesources.progress.v1"
        const val MAX_ENTRIES = 2_000
    }
}

/**
 * Turns the player's twice-a-second reports into [FileSourceProgressStore] writes.
 *
 * Every report updates the in-memory point, so a flush on leaving always has the latest one; the
 * settings document is rewritten only every [PERSIST_INTERVAL_MS] of wall time while playing, and
 * at once when playback pauses — the moment someone is most likely to walk away.
 */
class FileSourceProgressRecorder(
    private val store: FileSourceProgressStore,
    private val nowElapsedMs: () -> Long,
) {
    private var lastPersistAtMs = Long.MIN_VALUE
    private var lastPlaying: Boolean? = null
    private var lastKey: String? = null

    fun onPosition(
        key: String,
        positionMs: Long,
        durationMs: Long,
        playing: Boolean,
    ) {
        val now = nowElapsedMs()
        val keyChanged = key != lastKey
        val paused = lastPlaying == true && !playing
        val due = lastPersistAtMs == Long.MIN_VALUE || now - lastPersistAtMs >= PERSIST_INTERVAL_MS
        // A new file flushes the previous one's last point before recording its own.
        if (keyChanged) store.flush()
        val persistNow = paused || due
        store.record(key, positionMs, durationMs, persistNow = persistNow)
        if (persistNow) lastPersistAtMs = now
        lastPlaying = playing
        lastKey = key
    }

    fun onEnded(
        key: String,
        durationMs: Long,
    ) {
        store.markWatched(key, durationMs)
        lastPersistAtMs = nowElapsedMs()
    }

    fun flush() = store.flush()

    private companion object {
        const val PERSIST_INTERVAL_MS = 30_000L
    }
}

/**
 * The id of the playback item for [path] in [sourceId]: `filesource:<source>:<hash>`.
 *
 * Deterministic, so a file's resume point outlives the queue it was played from; hashed, so no
 * path — which names a user's folders — ends up in an id that diagnostics may print.
 */
fun fileSourceItemId(
    sourceId: String,
    path: List<String>,
): String = fileSourceItemIdPrefix(sourceId) + pathHash(path)

fun isFileSourceItemId(id: String): Boolean = id.startsWith(FILE_SOURCE_ITEM_PREFIX) && id.count { it == ':' } == 2

/** The source an item id belongs to, or null for any other id. */
fun fileSourceIdOf(itemId: String): String? =
    itemId
        .takeIf(::isFileSourceItemId)
        ?.removePrefix(FILE_SOURCE_ITEM_PREFIX)
        ?.substringBefore(':')

private fun fileSourceItemIdPrefix(sourceId: String): String = "$FILE_SOURCE_ITEM_PREFIX$sourceId:"

/** 64-bit FNV-1a over the segments; a separator no name can contain keeps `a/bc` apart from `ab/c`. */
private fun pathHash(path: List<String>): String {
    var hash = FNV_OFFSET_BASIS
    path.joinToString("\u0000").encodeToByteArray().forEach { byte ->
        hash = hash xor (byte.toLong() and 0xFF)
        hash *= FNV_PRIME
    }
    return hash.toULong().toString(16).padStart(16, '0')
}

private fun isNearEnd(
    positionMs: Long,
    durationMs: Long,
): Boolean = positionMs >= durationMs * WATCHED_PERCENT / 100

private const val FILE_SOURCE_ITEM_PREFIX = "filesource:"
private const val FNV_OFFSET_BASIS = -0x340d631b7bdddcdbL
private const val FNV_PRIME = 0x100000001b3L

/** Emby's own default for 已播放. */
private const val WATCHED_PERCENT = 90L

/** Below this a launch starts from the top; a minute of a film is not worth a resume prompt. */
private const val MIN_RESUME_POSITION_MS = 30_000L
