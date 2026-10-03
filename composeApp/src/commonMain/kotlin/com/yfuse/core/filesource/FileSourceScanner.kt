package com.yfuse.core.filesource

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
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
        onProgress: (FileSourceScanProgress) -> Unit = {},
    ): FileSourceLibrary {
        val walk = walk(source, credentials, onProgress)
        // Thousands of names through a few dozen patterns: not on the thread that draws the tab.
        val named =
            withContext(parseDispatcher) {
                val parser = MediaPathParser()
                walk.videos.mapNotNull { video -> parser.parse(video.path)?.let { video to it } }
            }
        val groups = named.groupBy { (_, parsed) -> parsed.lookupKey() }
        val matched = match(groups, previous, walk, onProgress)
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
        )
    }

    private data class Video(
        val path: List<String>,
        val sizeBytes: Long?,
    )

    private class Walk(
        val videos: List<Video>,
        val partial: Boolean,
        val foldersRead: Int,
    )

    /** Level by level, so a share too large to read whole still yields its top folders. */
    private suspend fun walk(
        source: FileSource,
        credentials: FileSourceCredentials,
        onProgress: (FileSourceScanProgress) -> Unit,
    ): Walk {
        val videos = mutableListOf<Video>()
        var frontier = listOf(emptyList<String>())
        var foldersRead = 0
        var partial = false
        var depth = 0
        val permits = Semaphore(LIST_CONCURRENCY)
        while (frontier.isNotEmpty()) {
            val listings =
                coroutineScope {
                    frontier
                        .map { path ->
                            async { permits.withPermit { path to listOrNull(source, credentials, path) } }
                        }.awaitAll()
                }
            val next = mutableListOf<List<String>>()
            listings.forEach { (path, entries) ->
                foldersRead++
                if (entries == null) {
                    partial = true
                    return@forEach
                }
                entries.filterNot { isHiddenFileSourceName(it.name) }.forEach { entry ->
                    when {
                        entry.directory && !isLibraryNoiseFolder(entry.name) -> next += path + entry.name
                        entry.isVideo -> videos += Video(path + entry.name, entry.sizeBytes)
                    }
                }
            }
            onProgress(FileSourceScanProgress(foldersRead = foldersRead, videosFound = videos.size))
            depth++
            val room = MAX_FOLDERS - foldersRead
            if (next.isNotEmpty() &&
                (depth > MAX_DEPTH || room < next.size || videos.size >= MAX_VIDEOS)
            ) {
                partial = true
            }
            frontier =
                if (depth > MAX_DEPTH || videos.size >= MAX_VIDEOS) emptyList() else next.take(room.coerceAtLeast(0))
        }
        return Walk(videos.take(MAX_VIDEOS), partial || videos.size > MAX_VIDEOS, foldersRead)
    }

    /** The root has to list — it is the share — but one unreadable folder only leaves a gap. */
    private suspend fun listOrNull(
        source: FileSource,
        credentials: FileSourceCredentials,
        path: List<String>,
    ): List<FileSourceEntry>? =
        try {
            client.list(source, credentials, path)
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
        groups: Map<String, List<Pair<Video, ParsedMediaName>>>,
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
        val permits = Semaphore(MATCH_CONCURRENCY)
        val report = {
            onProgress(FileSourceScanProgress(walk.foldersRead, walk.videos.size, lookups.size, lookedUp))
        }
        report()
        coroutineScope {
            lookups
                .map { (key, parsed) ->
                    async {
                        val outcome = permits.withPermit { matcher.match(parsed) }
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
