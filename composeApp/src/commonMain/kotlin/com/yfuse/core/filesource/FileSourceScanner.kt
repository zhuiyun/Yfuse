package com.yfuse.core.filesource

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** How far a scan has got, for the share's card while it runs. */
data class FileSourceScanProgress(
    val foldersRead: Int = 0,
    val videosFound: Int = 0,
    /** Distinct titles to look up once the folders are read; zero while still reading. */
    val titlesToMatch: Int = 0,
    val titlesLookedUp: Int = 0,
) {
    val matching: Boolean get() = titlesToMatch > 0
}

/** TMDB could not be reached at all, so nothing could be named; the old library stands. */
class TmdbUnavailableException(
    cause: Throwable,
) : Exception("TMDB unavailable", cause)

/**
 * Turns a share into its 片库: reads its folders, names each video from its path, and looks each
 * title up on TMDB once.
 *
 * Bounded everywhere, since a share can be a whole NAS: [MAX_DEPTH] folders deep, [MAX_FOLDERS]
 * folders and [MAX_VIDEOS] videos, a few requests at a time. Files that share a name — a season's
 * episodes — are one lookup, and a title an earlier scan already matched is not looked up again,
 * so a rescan asks TMDB only about what is new.
 */
class FileSourceScanner(
    private val client: FileSourceClient,
    private val matcher: TmdbTitleMatcher,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
    private val parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    suspend fun scan(
        source: FileSource,
        credentials: FileSourceCredentials,
        previous: FileSourceLibrary?,
        onCheckpoint: suspend (FileSourceScanCheckpoint) -> Unit = {},
        onProgress: (FileSourceScanProgress) -> Unit = {},
    ): FileSourceLibrary {
        val compatiblePrevious =
            previous?.takeIf {
                it.sourceVersion == source.scanVersion() ||
                    (it.sourceVersion == null && source.revision == 0L)
            }
        val walk = walk(source, credentials, previous?.checkpoint, onCheckpoint, onProgress)
        // Thousands of names through a few dozen patterns: not on the thread that draws the tab.
        val named =
            withContext(parseDispatcher) {
                val parser = MediaPathParser()
                walk.videos.mapNotNull { video -> parser.parse(video.path)?.let { video to it } }
            }
        val groups = named.groupBy { (_, parsed) -> parsed.lookupKey() }
        val matched = match(groups, compatiblePrevious, walk, onProgress)
        val titles = matched.values.filterNotNull().distinctBy { it.key }
        val files =
            groups.flatMap { (key, members) ->
                val title = matched[key] ?: return@flatMap emptyList()
                members.map { (video, parsed) ->
                    FileSourceLibraryFile(
                        path = video.path,
                        titleKey = title.key,
                        season = parsed.season.takeIf { title.isSeries },
                        episode = parsed.episode.takeIf { title.isSeries },
                        sizeBytes = video.sizeBytes,
                    )
                }
            }
        return FileSourceLibrary(
            scannedAtEpochMs = nowEpochMs(),
            titles = titles,
            files = files,
            unmatchedCount = named.size - files.size,
            partial = walk.partial || matched.size < groups.size,
            sourceVersion = source.scanVersion(),
        ).preservingUnreadFiles(compatiblePrevious)
    }

    private class Walk(
        val videos: List<FileSourceScanVideo>,
        val partial: Boolean,
        val foldersRead: Int,
    )

    /** Level by level, so a share too large to read whole still yields its top folders. */
    private suspend fun walk(
        source: FileSource,
        credentials: FileSourceCredentials,
        checkpoint: FileSourceScanCheckpoint?,
        onCheckpoint: suspend (FileSourceScanCheckpoint) -> Unit,
        onProgress: (FileSourceScanProgress) -> Unit,
    ): Walk {
        val saved = checkpoint?.takeIf { it.sourceVersion == source.scanVersion() }
        val videos =
            saved
                ?.videos
                .orEmpty()
                .take(MAX_VIDEOS)
                .toMutableList()
        var foldersRead = (saved?.foldersRead ?: 0).coerceIn(0, MAX_FOLDERS)
        val frontier =
            ArrayDeque(
                (saved?.pending ?: listOf(FileSourceScanFolder(emptyList(), 0))).take(
                    MAX_FOLDERS - foldersRead,
                ),
            )
        var partial = saved?.partial ?: false
        while (frontier.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val batch = List(minOf(LIST_CONCURRENCY, frontier.size)) { frontier.removeFirst() }
            val listings =
                coroutineScope {
                    batch
                        .map { folder ->
                            async { folder to listOrNull(source, credentials, folder.path) }
                        }.awaitAll()
                }
            listings.forEachIndexed { batchIndex, (folder, listing) ->
                foldersRead++
                if (listing == null) {
                    partial = true
                    return@forEachIndexed
                }
                partial = partial || listing.truncated || listing.entries.size > MAX_FILE_SOURCE_LISTING_ENTRIES
                listing.entries.take(MAX_FILE_SOURCE_LISTING_ENTRIES).forEach entryLoop@{ entry ->
                    if (isHiddenFileSourceName(entry.name)) return@entryLoop
                    when {
                        entry.directory && !isLibraryNoiseFolder(entry.name) -> {
                            if (folder.depth < MAX_DEPTH &&
                                foldersRead + frontier.size + batch.size - batchIndex - 1 < MAX_FOLDERS
                            ) {
                                frontier.addLast(FileSourceScanFolder(folder.path + entry.name, folder.depth + 1))
                            } else {
                                partial = true
                            }
                        }
                        entry.isVideo -> {
                            if (videos.size <
                                MAX_VIDEOS
                            ) {
                                videos += FileSourceScanVideo(folder.path + entry.name, entry.sizeBytes)
                            } else {
                                partial = true
                            }
                        }
                    }
                }
            }
            onProgress(FileSourceScanProgress(foldersRead = foldersRead, videosFound = videos.size))
            if (videos.size >= MAX_VIDEOS && frontier.isNotEmpty()) {
                partial = true
                frontier.clear()
            }
            onCheckpoint(
                FileSourceScanCheckpoint(
                    source.scanVersion(),
                    frontier.toList(),
                    videos.toList(),
                    foldersRead,
                    partial,
                ),
            )
        }
        return Walk(videos, partial, foldersRead)
    }

    /** The root has to list — it is the share — but one unreadable folder only leaves a gap. */
    private suspend fun listOrNull(
        source: FileSource,
        credentials: FileSourceCredentials,
        path: List<String>,
    ): FileSourceListing? =
        try {
            client.listBounded(source, credentials, path, MAX_FILE_SOURCE_LISTING_ENTRIES)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (path.isEmpty()) throw error
            null
        }

    /**
     * One lookup per group, the answer shared by every file in it; null for a group TMDB could
     * not name. A group missing from the result was never answered — TMDB was unreachable — and
     * leaves the library partial. With no answer at all, the scan fails instead.
     */
    private suspend fun match(
        groups: Map<String, List<Pair<FileSourceScanVideo, ParsedMediaName>>>,
        previous: FileSourceLibrary?,
        walk: Walk,
        onProgress: (FileSourceScanProgress) -> Unit,
    ): Map<String, FileSourceTitle?> {
        val known = previous?.files?.associateBy { it.path }.orEmpty()
        val results = mutableMapOf<String, FileSourceTitle?>()
        val pending = mutableListOf<Pair<String, ParsedMediaName>>()
        groups.forEach { (key, members) ->
            val reused =
                members.firstNotNullOfOrNull { (video, _) ->
                    known[video.path]?.titleKey?.let { previous?.title(it) }
                }
            if (reused != null) results[key] = reused else pending += key to members.first().second
        }
        val lookups = pending.take(MAX_LOOKUPS)
        val lock = Mutex()
        var lookedUp = 0
        var answered = 0
        var unavailable = 0
        var lastError: Throwable? = null
        val report = {
            onProgress(FileSourceScanProgress(walk.foldersRead, walk.videos.size, lookups.size, lookedUp))
        }
        report()
        lookups.chunked(MATCH_CONCURRENCY).forEach { chunk ->
            coroutineScope {
                chunk
                    .map { (key, parsed) ->
                        async {
                            val outcome = matcher.match(parsed)
                            lock.withLock {
                                lookedUp++
                                when (outcome) {
                                    is TitleMatch.Found -> results[key] = outcome.title
                                    TitleMatch.NotFound -> results[key] = null
                                    is TitleMatch.Unavailable -> {
                                        unavailable++
                                        lastError = outcome.error
                                    }
                                }
                                if (outcome !is TitleMatch.Unavailable) answered++
                                // A missing token or no network fails every lookup alike; a few in a
                                // row with nothing answered is enough to know, and to stop asking.
                                if (outcome is TitleMatch.Unavailable &&
                                    answered == 0 &&
                                    unavailable >= UNAVAILABLE_BEFORE_GIVING_UP
                                ) {
                                    throw TmdbUnavailableException(outcome.error)
                                }
                                report()
                            }
                        }
                    }.awaitAll()
            }
        }
        lastError?.takeIf { answered == 0 }?.let { throw TmdbUnavailableException(it) }
        return results
    }
}

/** Files that would ask TMDB the same question: a season's episodes, a film's two parts. */
private fun ParsedMediaName.lookupKey(): String =
    listOf(
        kind.name,
        titles.joinToString("/") { it.normalizedTitle() },
        year?.takeIf { kind == ParsedMediaKind.Movie }?.toString().orEmpty(),
        tmdbId?.toString().orEmpty(),
    ).joinToString("|")

private const val MAX_DEPTH = 6
private const val MAX_FOLDERS = 600
private const val MAX_VIDEOS = 5_000
private const val MAX_LOOKUPS = 800
private const val LIST_CONCURRENCY = 4
private const val MATCH_CONCURRENCY = 3
private const val UNAVAILABLE_BEFORE_GIVING_UP = 3
