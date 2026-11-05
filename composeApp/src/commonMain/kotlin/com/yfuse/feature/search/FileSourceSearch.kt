package com.yfuse.feature.search

import com.yfuse.core.filesource.FileSource
import com.yfuse.core.filesource.FileSourceLibrary
import com.yfuse.core.filesource.FileSourceLibraryFile
import com.yfuse.core.filesource.FileSourceProgress
import com.yfuse.core.filesource.FileSourceTitle
import com.yfuse.core.filesource.fileSourceItemId
import com.yfuse.core.filesource.scanVersion

internal data class FileSourceSearchHit(
    val source: FileSource,
    val file: FileSourceLibraryFile,
    val title: FileSourceTitle?,
) {
    val id: String get() = fileSourceItemId(source.id, file.path)
    val name: String get() = title?.title ?: file.path.lastOrNull().orEmpty()
}

internal data class FileSourceSearchPage(
    val hits: List<FileSourceSearchHit> = emptyList(),
    val matches: Int = 0,
    val unindexedSources: Int = 0,
    val partialSources: Int = 0,
    val unsupportedFilter: Boolean = false,
)

/** Searches the saved index only. A hit never embeds a resolved URL or a stored credential. */
internal fun searchFileSourceIndex(
    state: SearchState,
    sources: List<FileSource>,
    libraries: Map<String, FileSourceLibrary>,
    progress: Map<String, FileSourceProgress>,
    selectedSource: String? = null,
    limit: Int = 500,
): FileSourceSearchPage {
    val selected = sources.filter { selectedSource == null || it.id == selectedSource }
    val validLibraries =
        selected
            .mapNotNull { source ->
                libraries[source.id]
                    ?.takeIf {
                        it.sourceVersion == source.scanVersion() || (it.sourceVersion == null && source.revision == 0L)
                    }?.let { source.id to it }
            }.toMap()
    val unsupported =
        state.serverId != null ||
            state.libraryId != null ||
            state.genre != null ||
            state.person != null ||
            state.playlistName != null
    val page =
        FileSourceSearchPage(
            unindexedSources = selected.count { validLibraries[it.id] == null },
            partialSources = selected.count { validLibraries[it.id]?.partial == true },
            unsupportedFilter = unsupported,
        )
    val query = state.query.trim()
    if (query.isEmpty() || unsupported) return page
    val terms = query.lowercase().split(Regex("\\s+")).filter(String::isNotEmpty)
    val hits = mutableListOf<FileSourceSearchHit>()
    val order =
        when (state.sort) {
            SearchSort.Name -> compareBy<FileSourceSearchHit> { it.name.lowercase() }
            SearchSort.YearNewest -> compareByDescending<FileSourceSearchHit> { it.title?.year ?: 0 }
            SearchSort.Rating -> compareByDescending<FileSourceSearchHit> { it.title?.rating ?: 0.0 }
            SearchSort.RecentlyAdded -> null // Scan time is not file creation time.
            SearchSort.Relevance ->
                compareByDescending<FileSourceSearchHit> {
                    it.name.equals(
                        query,
                        ignoreCase = true,
                    )
                }
        }?.thenBy { it.source.id }?.thenBy { it.file.path.joinToString("/") }
    val pageLimit = limit.coerceIn(1, 500)
    var matches = 0
    for (source in selected) {
        val library = validLibraries[source.id] ?: continue
        val titles = library.titles.associateBy { it.key }
        for (file in library.files) {
            if (file.path.isEmpty()) continue
            val title = titles[file.titleKey]
            if (state.type == SearchType.Movie && title?.isSeries != false) continue
            if (state.type == SearchType.Series && title?.isSeries != true) continue
            if (state.year != null && title?.year != state.year) continue
            val watched = progress[fileSourceItemId(source.id, file.path)]
            if (state.watchStatus == SearchWatchStatus.Played && watched?.watched != true) continue
            if (state.watchStatus == SearchWatchStatus.Unplayed && watched?.watched == true) continue
            if (state.watchStatus == SearchWatchStatus.Resumable && (watched?.resumePositionMs ?: 0L) == 0L) continue
            val haystack =
                listOfNotNull(
                    title?.title,
                    title?.originalTitle,
                    file.path.joinToString("/"),
                ).joinToString(" ")
            if (terms.any { !haystack.contains(it, ignoreCase = true) }) continue
            matches++
            // The visible page is bounded; there is no unbounded allocation for a large NAS.
            val hit = FileSourceSearchHit(source, file, title)
            if (order == null) {
                if (hits.size < pageLimit) hits += hit
            } else {
                val found = hits.binarySearch(hit, order)
                val insertion = if (found < 0) -found - 1 else found
                if (insertion < pageLimit) {
                    hits.add(insertion, hit)
                    if (hits.size > pageLimit) hits.removeAt(hits.lastIndex)
                }
            }
        }
    }
    return page.copy(hits = hits, matches = matches)
}
