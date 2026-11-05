package com.yfuse.feature.search

import com.yfuse.core.filesource.FileSource
import com.yfuse.core.filesource.FileSourceKind
import com.yfuse.core.filesource.FileSourceLibrary
import com.yfuse.core.filesource.FileSourceLibraryFile
import com.yfuse.core.filesource.FileSourceProgress
import com.yfuse.core.filesource.FileSourceTitle
import com.yfuse.core.filesource.fileSourceItemId
import com.yfuse.core.filesource.scanVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileSourceSearchTest {
    private val nas = FileSource("nas", FileSourceKind.Smb, "NAS", "smb://example.invalid")
    private val cloud = FileSource("cloud", FileSourceKind.Pan123, "Cloud", "https://example.invalid")
    private val title = FileSourceTitle(603, "movie", "黑客帝国", "The Matrix", 1999)
    private val file = FileSourceLibraryFile(listOf("Movies", "Matrix.1999.mkv"), title.key)
    private val library = FileSourceLibrary(0, listOf(title), listOf(file))

    @Test
    fun matchesOriginalTitleAndFilenameButKeepsSourceIdentitiesSeparate() {
        val page =
            searchFileSourceIndex(
                SearchState(query = "matrix 1999"),
                listOf(nas, cloud),
                mapOf(nas.id to library, cloud.id to library),
                emptyMap(),
            )
        assertEquals(2, page.matches)
        assertEquals(
            2,
            page.hits
                .map { it.id }
                .distinct()
                .size,
        )
        assertEquals(
            listOf(cloud.id),
            searchFileSourceIndex(
                SearchState(query = "黑客"),
                listOf(nas, cloud),
                mapOf(nas.id to library, cloud.id to library),
                emptyMap(),
                cloud.id,
            ).hits.map { it.source.id },
        )
    }

    @Test
    fun removedAndEditedSourcesCannotExposeOldIndex() {
        assertTrue(
            searchFileSourceIndex(
                SearchState(query = "matrix"),
                emptyList(),
                mapOf(nas.id to library),
                emptyMap(),
            ).hits.isEmpty(),
        )
        val edited = nas.copy(revision = 1)
        val stale =
            searchFileSourceIndex(
                SearchState(query = "matrix"),
                listOf(edited),
                mapOf(nas.id to library.copy(sourceVersion = nas.scanVersion())),
                emptyMap(),
            )
        assertTrue(stale.hits.isEmpty())
        assertEquals(1, stale.unindexedSources)
    }

    @Test
    fun watchedAndYearFiltersUseFileProgressWithoutServerIdentity() {
        val progress = mapOf(fileSourceItemId(nas.id, file.path) to FileSourceProgress(0, watched = true))
        val state = SearchState(query = "matrix", watchStatus = SearchWatchStatus.Played, year = 1999)
        assertEquals(1, searchFileSourceIndex(state, listOf(nas), mapOf(nas.id to library), progress).matches)
        assertEquals(
            0,
            searchFileSourceIndex(state.copy(year = 2000), listOf(nas), mapOf(nas.id to library), progress).matches,
        )
        assertEquals(
            0,
            searchFileSourceIndex(
                state.copy(watchStatus = SearchWatchStatus.Resumable),
                listOf(nas),
                mapOf(nas.id to library),
                progress,
            ).matches,
        )
    }

    @Test
    fun boundedPageStillRanksAllMatches() {
        val files = (9 downTo 0).map { file.copy(path = listOf("Matrix.$it.mkv")) }
        val page =
            searchFileSourceIndex(
                SearchState(query = "matrix", sort = SearchSort.Name),
                listOf(nas),
                mapOf(nas.id to library.copy(files = files)),
                emptyMap(),
                limit = 2,
            )
        assertEquals(10, page.matches)
        assertEquals(listOf("Matrix.0.mkv", "Matrix.1.mkv"), page.hits.map { it.file.path.last() })
    }

    @Test
    fun reportsIncompleteIndexAndDoesNotInventServerFacets() {
        val page =
            searchFileSourceIndex(
                SearchState(query = "matrix", genre = "Action"),
                listOf(nas, cloud),
                mapOf(nas.id to library.copy(partial = true)),
                emptyMap(),
            )
        assertTrue(page.unsupportedFilter)
        assertTrue(page.hits.isEmpty())
        assertEquals(1, page.partialSources)
        assertEquals(1, page.unindexedSources)
    }
}
