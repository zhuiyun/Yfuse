package com.yfuse.core.filesource

import com.yfuse.core.logging.AppLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** One matched file of a share: where it is, which title it is, and which episode. */
@Serializable
data class FileSourceLibraryFile(
    @SerialName("p") val path: List<String>,
    /** [FileSourceTitle.key]. */
    @SerialName("t") val titleKey: String,
    @SerialName("s") val season: Int? = null,
    @SerialName("e") val episode: Int? = null,
    @SerialName("b") val sizeBytes: Long? = null,
)

/** A share's 片库 as its last scan left it. */
@Serializable
data class FileSourceLibrary(
    @SerialName("at") val scannedAtEpochMs: Long,
    @SerialName("titles") val titles: List<FileSourceTitle> = emptyList(),
    @SerialName("files") val files: List<FileSourceLibraryFile> = emptyList(),
    /** Videos the scan read but could not name, for the count on the share's card. */
    @SerialName("unmatched") val unmatchedCount: Int = 0,
    /** The scan stopped at its limits, or lost TMDB, before everything was read and matched. */
    @SerialName("partial") val partial: Boolean = false,
) {
    fun title(key: String): FileSourceTitle? = titles.firstOrNull { it.key == key }

    /** A title's files in watching order: season, then episode, then name. */
    fun filesOf(key: String): List<FileSourceLibraryFile> =
        files
            .filter { it.titleKey == key }
            .sortedWith(
                compareBy<FileSourceLibraryFile>({ it.season ?: 0 }, { it.episode ?: 0 })
                    .thenComparator { left, right -> compareNatural(left.path.last(), right.path.last()) },
            )
}

/** Where every share's 片库 is kept: one JSON document, read once and written after each scan. */
interface FileSourceLibraryStorage {
    fun read(): String?

    fun write(text: String)
}

expect fun createFileSourceLibraryStorage(): FileSourceLibraryStorage

/**
 * Every share's 片库, keyed by source id.
 *
 * A file of its own rather than settings: a large share's library runs to hundreds of kilobytes,
 * and the settings document is parsed on every cold start whether or not a library is ever
 * shown. It is read the first time something asks — [ensureLoaded] — and written whole when a
 * scan finishes, which is rare enough that a partial update would buy nothing.
 */
class FileSourceLibraryStore(
    private val storage: FileSourceLibraryStorage,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), FileSourceLibrary.serializer())
    private val mutex = Mutex()
    private var loaded = false
    private val _libraries = MutableStateFlow<Map<String, FileSourceLibrary>>(emptyMap())
    val libraries: StateFlow<Map<String, FileSourceLibrary>> = _libraries.asStateFlow()

    suspend fun ensureLoaded() {
        mutex.withLock {
            if (loaded) return
            _libraries.value = withContext(ioDispatcher) { read() }
            loaded = true
        }
    }

    suspend fun save(
        sourceId: String,
        library: FileSourceLibrary,
    ) = update { it + (sourceId to library) }

    suspend fun remove(sourceId: String) = update { it - sourceId }

    private suspend fun update(transform: (Map<String, FileSourceLibrary>) -> Map<String, FileSourceLibrary>) {
        ensureLoaded()
        mutex.withLock {
            val next = transform(_libraries.value)
            if (next == _libraries.value) return
            withContext(ioDispatcher) {
                runCatching { storage.write(json.encodeToString(serializer, next)) }
                    .onFailure { error ->
                        AppLog.warning(
                            category = LOG_CATEGORY,
                            event = "library_persist_failed",
                            message = "A file source library could not be saved",
                            attributes = mapOf("exception" to error::class.simpleName.orEmpty()),
                        )
                    }
            }
            // Shown even when the write failed: the scan's result is right for this run.
            _libraries.update { next }
        }
    }

    private fun read(): Map<String, FileSourceLibrary> {
        val raw = runCatching { storage.read() }.getOrNull() ?: return emptyMap()
        return runCatching { json.decodeFromString(serializer, raw) }
            .getOrElse {
                AppLog.warning(
                    category = LOG_CATEGORY,
                    event = "library_unreadable",
                    message = "The saved file source library could not be read",
                )
                emptyMap()
            }
    }
}
