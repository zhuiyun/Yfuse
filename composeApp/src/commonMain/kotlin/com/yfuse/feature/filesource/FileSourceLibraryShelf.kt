package com.yfuse.feature.filesource

import com.yfuse.core.data.CrossServerMediaHit
import com.yfuse.core.filesource.FileSource
import com.yfuse.core.filesource.FileSourceClient
import com.yfuse.core.filesource.FileSourceException
import com.yfuse.core.filesource.FileSourceFailure
import com.yfuse.core.filesource.FileSourceLibrary
import com.yfuse.core.filesource.FileSourceLibraryFile
import com.yfuse.core.filesource.FileSourceLibraryStore
import com.yfuse.core.filesource.FileSourceProgress
import com.yfuse.core.filesource.FileSourceProgressStore
import com.yfuse.core.filesource.FileSourceRegistry
import com.yfuse.core.filesource.FileSourceTitle
import com.yfuse.core.filesource.LOG_CATEGORY
import com.yfuse.core.filesource.fileSourceItemId
import com.yfuse.core.filesource.planFileSourceQueue
import com.yfuse.core.filesource.userMessage
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.MediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A 文件来源 copy in the cross-server library carries this, and its source id, as its server id. */
internal const val FILE_SOURCE_HIT_PREFIX = "filesource:"

/** The share a cross-server hit comes from, or null for a server's own copy. */
val CrossServerMediaHit.fileSourceId: String?
    get() = serverId.takeIf { it.startsWith(FILE_SOURCE_HIT_PREFIX) }?.removePrefix(FILE_SOURCE_HIT_PREFIX)

/** One file of a title on one share, as the title's sheet lists it. */
data class FileSourceTitleFile(
    val source: FileSource,
    val file: FileSourceLibraryFile,
) {
    val itemId: String get() = fileSourceItemId(source.id, file.path)
}

/**
 * What 全部服务器 needs of 文件来源: every share's matched titles as cross-server hits, the files
 * behind one of them, and what to hand the player for one.
 *
 * A share's copy of a title is identified by its TMDB id, the same provider id a server's copy
 * carries, so `aggregateCrossServerMedia` puts a film held by a server and by the NAS on one card.
 */
class FileSourceLibraryShelf(
    private val registry: FileSourceRegistry,
    private val libraryStore: FileSourceLibraryStore,
    private val progressStore: FileSourceProgressStore,
    private val client: FileSourceClient,
) {
    val sources: StateFlow<List<FileSource>> = registry.sources
    val libraries: StateFlow<Map<String, FileSourceLibrary>> = libraryStore.libraries
    val progress: StateFlow<Map<String, FileSourceProgress>> = progressStore.progress

    /** [itemType] is `Movie`, `Series` or null for both, as 全部服务器's filter names them. */
    suspend fun hits(
        itemType: String?,
        unplayedOnly: Boolean,
    ): List<CrossServerMediaHit> {
        libraryStore.ensureLoaded()
        return fileSourceLibraryHits(sources.value, libraries.value, progress.value, itemType, unplayedOnly)
    }

    fun title(hit: CrossServerMediaHit): FileSourceTitle? {
        val sourceId = hit.fileSourceId ?: return null
        return libraries.value[sourceId]?.title(hit.item.id)
    }

    /** Every file of the title behind [hits] on every share that holds it, share by share. */
    fun files(hits: List<CrossServerMediaHit>): List<FileSourceTitleFile> =
        hits.flatMap { hit ->
            val source = hit.fileSourceId?.let(registry::source) ?: return@flatMap emptyList()
            libraries.value[source.id]
                ?.filesOf(hit.item.id)
                .orEmpty()
                .map { FileSourceTitleFile(source, it) }
        }

    /**
     * The queue for one file: its folder read again — sidecars and neighbours may have changed
     * since the scan — and planned exactly as tapping it in the share's browser would.
     */
    suspend fun prepare(
        file: FileSourceTitleFile,
        fromStart: Boolean,
    ): FileSourceLaunch {
        val source = file.source
        val credentials = registry.credentials(source.id) ?: throw FileSourceException(FileSourceFailure.NotFound)
        val folder = file.file.path.dropLast(1)
        val entries = client.list(source, credentials, folder)
        val entry =
            entries.firstOrNull { !it.directory && it.name == file.file.path.last() }
                ?: throw FileSourceException(FileSourceFailure.NotFound)
        val plan = planFileSourceQueue(source.id, folder, entries, entry)
        val launch = prepareFileSourceLaunch(source, credentials, plan, client, progress.value)
        return if (fromStart) launch.copy(startPositionMs = 0L) else launch
    }
}

/**
 * Plays files from 全部服务器: one preparation at a time, and the queue it produced until the
 * screen has handed it to the player.
 */
class FileSourceTitlePlayer(
    private val shelf: FileSourceLibraryShelf,
    private val scope: CoroutineScope,
) {
    private val _preparing = MutableStateFlow<FileSourceTitleFile?>(null)
    val preparing: StateFlow<FileSourceTitleFile?> = _preparing.asStateFlow()

    private val _launch = MutableStateFlow<FileSourceLaunch?>(null)
    val launch: StateFlow<FileSourceLaunch?> = _launch.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private var job: Job? = null

    fun play(
        file: FileSourceTitleFile,
        fromStart: Boolean = false,
    ) {
        job?.cancel()
        _preparing.value = file
        job =
            scope.launch {
                try {
                    _launch.value = shelf.prepare(file, fromStart)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    AppLog.warning(
                        category = LOG_CATEGORY,
                        event = "library_play_failed",
                        message = "A file source library title could not be prepared",
                        attributes =
                            mapOf(
                                "sourceId" to file.source.id,
                                "failure" to ((error as? FileSourceException)?.failure?.name ?: "Unknown"),
                            ),
                    )
                    _notice.value =
                        (error as? FileSourceException)?.failure?.userMessage(file.source.kind)
                            ?: "无法播放「${file.source.name}」里的这个文件"
                } finally {
                    if (_preparing.value == file) _preparing.value = null
                }
            }
    }

    fun consumeLaunch() {
        _launch.value = null
    }

    fun showNotice(message: String) {
        _notice.value = message
    }

    fun dismissNotice() {
        _notice.value = null
    }
}

/**
 * Every share's matched titles as hits for 全部服务器, one per title per share. A title counts as
 * played when every one of its files on that share was watched to the end on this device.
 */
internal fun fileSourceLibraryHits(
    sources: List<FileSource>,
    libraries: Map<String, FileSourceLibrary>,
    progress: Map<String, FileSourceProgress>,
    itemType: String?,
    unplayedOnly: Boolean,
): List<CrossServerMediaHit> =
    sources.flatMap { source ->
        val library = libraries[source.id] ?: return@flatMap emptyList()
        library.titles.mapNotNull { title ->
            val type = if (title.isSeries) SERIES_TYPE else MOVIE_TYPE
            val files = library.filesOf(title.key)
            val played =
                files.isNotEmpty() && files.all { progress[fileSourceItemId(source.id, it.path)]?.watched == true }
            when {
                files.isEmpty() -> null
                itemType != null && type != itemType -> null
                unplayedOnly && played -> null
                else ->
                    CrossServerMediaHit(
                        FILE_SOURCE_HIT_PREFIX + source.id,
                        source.name,
                        title.toMediaItem(type, played),
                    )
            }
        }
    }

private fun FileSourceTitle.toMediaItem(
    type: String,
    played: Boolean,
): MediaItem =
    MediaItem(
        id = key,
        title = title,
        subtitle = null,
        type = type,
        posterItemId = key,
        posterTag = null,
        backdropItemId = null,
        backdropTag = null,
        playedPercentage = null,
        year = year,
        communityRating = rating,
        // The same provider id a server's copy of the title carries: that is what merges them.
        providerIds = mapOf("Tmdb" to tmdbId.toString()),
        played = played,
    )

private const val MOVIE_TYPE = "Movie"
private const val SERIES_TYPE = "Series"
