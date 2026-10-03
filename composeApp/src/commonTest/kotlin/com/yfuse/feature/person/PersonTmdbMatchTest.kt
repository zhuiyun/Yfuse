package com.yfuse.feature.person

import com.yfuse.core.model.MediaItem
import com.yfuse.core.model.TmdbItem
import com.yfuse.core.model.TmdbPerson
import com.yfuse.core.model.TmdbPersonDetail
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PersonTmdbMatchTest {
    private val library = setOf("movie:843", "tv:500")

    @Test
    fun an_id_the_server_stored_is_the_person_without_a_search() =
        runTest {
            var searched = false
            val match =
                resolveTmdbPerson(
                    name = "梁朝伟",
                    tmdbId = 1337,
                    libraryKeys = emptySet(),
                    search = {
                        searched = true
                        emptyList()
                    },
                    details = { id -> record(id, "梁朝伟") },
                )

            assertEquals(1337, assertIs<TmdbPersonMatch.ById>(match).detail.person.id)
            assertTrue(!searched)
        }

    @Test
    fun a_stored_id_whose_record_cannot_be_read_shows_nothing() =
        runTest {
            val match = resolveTmdbPerson("梁朝伟", 1337, library, search = { error("no search") }, details = { null })

            assertEquals("record_unavailable", assertIs<TmdbPersonMatch.None>(match).reason)
            assertNull(match.detail)
        }

    @Test
    fun a_name_counts_only_when_tmdb_credits_them_on_a_title_the_library_holds() =
        runTest {
            val records =
                mapOf(
                    1 to record(1, "梁朝伟", credits = listOf(movie(1))),
                    2 to record(2, "Tony Leung", aliases = listOf("梁朝伟"), credits = listOf(movie(843), movie(9))),
                    3 to record(3, "梁朝偉", credits = listOf(movie(843))),
                )

            val match =
                resolveTmdbPerson(
                    name = " 梁朝伟 ",
                    tmdbId = null,
                    libraryKeys = library,
                    search = { records.values.map { it.person } },
                    details = records::get,
                )

            val byName = assertIs<TmdbPersonMatch.ByName>(match)
            assertEquals(2, byName.detail.person.id)
            assertEquals(1, byName.overlap)
        }

    @Test
    fun two_people_who_both_qualify_are_neither_shown() =
        runTest {
            val records =
                mapOf(
                    1 to record(1, "Tony Leung", credits = listOf(movie(843))),
                    2 to record(2, "Tony Leung", credits = listOf(tv(500))),
                )

            val match =
                resolveTmdbPerson("Tony Leung", null, library, { records.values.map { it.person } }, records::get)

            assertEquals("ambiguous", assertIs<TmdbPersonMatch.None>(match).reason)
        }

    @Test
    fun a_name_with_nothing_to_corroborate_it_is_not_trusted() =
        runTest {
            val noLibrary =
                resolveTmdbPerson("梁朝伟", null, emptySet(), { error("no search") }, { error("no record") })
            val noCandidates = resolveTmdbPerson("梁朝伟", null, library, { emptyList() }, { null })
            val noOverlap =
                resolveTmdbPerson(
                    "梁朝伟",
                    null,
                    library,
                    { listOf(TmdbPerson(1, "梁朝伟", null, null)) },
                    { record(it, "梁朝伟", credits = listOf(movie(1))) },
                )
            val noName = resolveTmdbPerson("  ", null, library, { error("no search") }, { null })

            assertEquals("no_library_titles", assertIs<TmdbPersonMatch.None>(noLibrary).reason)
            assertEquals("no_candidates", assertIs<TmdbPersonMatch.None>(noCandidates).reason)
            assertEquals("uncorroborated", assertIs<TmdbPersonMatch.None>(noOverlap).reason)
            assertEquals("no_name", assertIs<TmdbPersonMatch.None>(noName).reason)
        }

    @Test
    fun names_match_however_they_were_typed_but_not_across_spellings() {
        assertEquals(personNameKey("Tony Leung"), personNameKey("  tony   LEUNG "))
        assertEquals(personNameKey("Ｔｏｎｙ　Ｌｅｕｎｇ"), personNameKey("Tony Leung"))
        assertEquals(personNameKey("约瑟夫·高登-莱维特"), personNameKey("约瑟夫・高登-莱维特"))
        assertEquals(personNameKey("约瑟夫·高登-莱维特"), personNameKey("约瑟夫•高登-莱维特"))
        assertTrue(personNameKey("梁朝伟") != personNameKey("梁朝偉"))
    }

    @Test
    fun other_works_leave_the_library_and_blank_cards_out_best_known_first() {
        val detail =
            record(
                1,
                "梁朝伟",
                credits =
                    listOf(
                        movie(843, votes = 5000),
                        movie(1, votes = 10),
                        movie(2, votes = 900),
                        movie(3, votes = 99_999, poster = null),
                        tv(4, votes = 900, popularity = 50.0),
                    ),
            )

        val works = otherWorks(detail, library)

        assertEquals(listOf("tv:4", "movie:2", "movie:1"), works.map { it.tmdbKey() })
        assertEquals(listOf("tv:4"), otherWorks(detail, library, limit = 1).map { it.tmdbKey() })
    }

    @Test
    fun library_titles_are_keyed_by_their_tmdb_ids() {
        val keys =
            libraryTmdbKeys(
                listOf(
                    item("a", "Movie", mapOf("Tmdb" to "843")),
                    item("b", "Series", mapOf("tmdb" to " 500 ")),
                    item("c", "Episode", mapOf("Tmdb" to "7")),
                    item("d", "Movie", mapOf("Imdb" to "tt1")),
                    item("e", "Movie", mapOf("Tmdb" to "not a number")),
                ),
            )

        assertEquals(setOf("movie:843", "tv:500"), keys)
    }

    private fun record(
        id: Int,
        name: String,
        aliases: List<String> = emptyList(),
        credits: List<TmdbItem> = emptyList(),
    ) = TmdbPersonDetail(
        person = TmdbPerson(id = id, name = name, role = null, profilePath = null),
        names = listOf(name) + aliases,
        credits = credits,
    )

    private fun movie(
        id: Int,
        votes: Int = 0,
        poster: String? = "/p.jpg",
    ) = TmdbItem(id, "电影 $id", null, poster, null, null, "movie", null, voteCount = votes)

    private fun tv(
        id: Int,
        votes: Int = 0,
        popularity: Double = 0.0,
    ) = TmdbItem(id, "剧集 $id", null, "/p.jpg", null, null, "tv", null, voteCount = votes, popularity = popularity)

    private fun item(
        id: String,
        type: String,
        providerIds: Map<String, String>,
    ) = MediaItem(
        id = id,
        title = id,
        subtitle = null,
        type = type,
        posterItemId = id,
        posterTag = null,
        backdropItemId = null,
        backdropTag = null,
        playedPercentage = null,
        providerIds = providerIds,
    )
}
