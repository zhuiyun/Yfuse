package com.yfuse.core.filesource

import com.yfuse.core.data.TmdbSearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileSourceLibraryTest {
    private val source = FileSource("fs" + "1".repeat(24), FileSourceKind.WebDav, "NAS", "https://nas.local")
    private val credentials = FileSourceCredentials("alice", "pw")
    private val searches = mutableListOf<String>()
    private val listed = mutableListOf<String>()

    private val tree =
        mapOf(
            "" to listOf(dir("电影"), dir("剧集"), dir(".@__thumb"), file("readme.txt")),
            "电影" to listOf(dir("流浪地球 (2019)"), file("The.Matrix.1999.1080p.BluRay.mkv"), dir("Extras")),
            "电影/流浪地球 (2019)" to listOf(file("流浪地球.2019.mkv"), file("流浪地球.2019.chs.srt")),
            "电影/Extras" to listOf(file("Making.of.mkv")),
            "剧集" to listOf(dir("狂飙")),
            "剧集/狂飙" to listOf(file("第01集.mp4"), file("第02集.mp4"), file("不知道是什么.mp4")),
        )

    private val catalog =
        mapOf(
            "流浪地球" to hit(535167, "流浪地球", 2019),
            "The Matrix" to hit(603, "黑客帝国", 1999, original = "The Matrix"),
            "狂飙" to hit(215803, "狂飙", 2023, mediaType = TMDB_TV),
        )

    private val client =
        object : FileSourceClient {
            override suspend fun list(
                source: FileSource,
                credentials: FileSourceCredentials,
                path: List<String>,
            ): List<FileSourceEntry> {
                val key = path.joinToString("/")
                listed += key
                return tree[key] ?: throw FileSourceException(FileSourceFailure.NotFound)
            }

            override suspend fun cacheSubtitle(
                source: FileSource,
                credentials: FileSourceCredentials,
                path: List<String>,
                entry: FileSourceEntry,
            ): String = error("not used")
        }

    private fun scanner(tmdbDown: Boolean = false) =
        FileSourceScanner(
            client = client,
            matcher =
                TmdbTitleMatcher(
                    search = { query, mediaType, _ ->
                        searches += "$mediaType:$query"
                        if (tmdbDown) {
                            Result.failure(IllegalStateException("offline"))
                        } else {
                            Result.success(listOfNotNull(catalog[query]?.takeIf { it.mediaType == mediaType }))
                        }
                    },
                    alternativeTitles = { _, _ -> Result.success(emptyList()) },
                ),
            nowEpochMs = { 1_000L },
        )

    @Test
    fun a_scan_names_films_and_shows_and_asks_once_per_title() =
        runTest {
            val library = scanner().scan(source, credentials, previous = null)

            assertEquals(setOf("movie:535167", "movie:603", "tv:215803"), library.titles.map { it.key }.toSet())
            val episodes = library.filesOf("tv:215803")
            assertEquals(listOf(1, 2), episodes.map { it.episode })
            assertEquals(listOf("剧集", "狂飙", "第01集.mp4"), episodes.first().path)
            assertEquals(listOf("电影", "流浪地球 (2019)", "流浪地球.2019.mkv"), library.filesOf("movie:535167").single().path)
            // The two episodes share one question; the odd file in the show's folder is its own.
            assertEquals(1, searches.count { it == "tv:狂飙" })
            assertEquals(1, library.unmatchedCount)
            assertFalse(library.partial)
            assertFalse(".@__thumb" in listed, "hidden folders are not read")
            assertFalse("电影/Extras" in listed, "extras are not read")
            assertEquals(1_000L, library.scannedAtEpochMs)
        }

    @Test
    fun a_rescan_asks_tmdb_only_about_what_is_new() =
        runTest {
            val first = scanner().scan(source, credentials, previous = null)
            searches.clear()

            val second = scanner().scan(source, credentials, previous = first)

            assertEquals(first.titles.map { it.key }.toSet(), second.titles.map { it.key }.toSet())
            assertTrue(searches.none { it.contains("流浪地球") || it.contains("Matrix") || it.contains("狂飙") }, "$searches")
        }

    @Test
    fun an_unreachable_tmdb_fails_the_scan_instead_of_emptying_the_library() =
        runTest {
            assertFailsWith<TmdbUnavailableException> {
                scanner(
                    tmdbDown = true,
                ).scan(source, credentials, previous = null)
            }
        }

    @Test
    fun a_share_that_will_not_list_fails_the_scan() =
        runTest {
            val broken =
                FileSource("fs" + "2".repeat(24), FileSourceKind.WebDav, "NAS", "https://nas.local", listOf("gone"))
            val failing =
                FileSourceScanner(
                    client =
                        object : FileSourceClient by client {
                            override suspend fun list(
                                source: FileSource,
                                credentials: FileSourceCredentials,
                                path: List<String>,
                            ): List<FileSourceEntry> =
                                throw FileSourceException(FileSourceFailure.Unauthorized, status = 401)
                        },
                    matcher =
                        TmdbTitleMatcher(
                            { _, _, _ -> Result.success(emptyList()) },
                            { _, _ -> Result.success(emptyList()) },
                        ),
                )

            val error = assertFailsWith<FileSourceException> { failing.scan(broken, credentials, previous = null) }

            assertEquals(FileSourceFailure.Unauthorized, error.failure)
        }

    @Test
    fun the_library_survives_a_restart_and_forgets_a_removed_share() =
        runTest {
            val storage = MemoryStorage()
            val store = FileSourceLibraryStore(storage, Dispatchers.Unconfined)
            val library =
                FileSourceLibrary(
                    scannedAtEpochMs = 5L,
                    titles = listOf(FileSourceTitle(603, TMDB_MOVIE, "黑客帝国", year = 1999)),
                    files = listOf(FileSourceLibraryFile(listOf("m.mkv"), "movie:603")),
                )

            store.save(source.id, library)
            val reopened = FileSourceLibraryStore(storage, Dispatchers.Unconfined)
            reopened.ensureLoaded()

            assertEquals(library, reopened.libraries.value[source.id])
            reopened.remove(source.id)
            assertTrue(
                FileSourceLibraryStore(
                    storage,
                    Dispatchers.Unconfined,
                ).also { it.ensureLoaded() }.libraries.value.isEmpty(),
            )
        }

    @Test
    fun an_unreadable_library_reads_as_none_rather_than_failing() =
        runTest {
            val store = FileSourceLibraryStore(MemoryStorage("{not json"), Dispatchers.Unconfined)

            store.ensureLoaded()

            assertTrue(store.libraries.value.isEmpty())
        }

    private class MemoryStorage(
        var text: String? = null,
    ) : FileSourceLibraryStorage {
        override fun read(): String? = text

        override fun write(text: String) {
            this.text = text
        }
    }

    private fun dir(name: String) = FileSourceEntry(name, directory = true)

    private fun file(name: String) = FileSourceEntry(name, directory = false, sizeBytes = 1_000L)

    private fun hit(
        id: Int,
        title: String,
        year: Int,
        original: String = title,
        mediaType: String = TMDB_MOVIE,
    ) = TmdbSearchResult(id, mediaType, title, original, year, "/$id.jpg", null, null, 8.0, 100, 10.0)
}
