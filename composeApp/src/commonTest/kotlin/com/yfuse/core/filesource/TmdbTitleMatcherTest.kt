package com.yfuse.core.filesource

import com.yfuse.core.data.TmdbSearchResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TmdbTitleMatcherTest {
    private val searches = mutableListOf<String>()
    private val lookups = mutableListOf<Int>()

    private fun matcher(
        alternatives: Map<Int, List<String>> = emptyMap(),
        failing: Boolean = false,
        answer: (query: String, mediaType: String, year: Int?) -> List<TmdbSearchResult>,
    ) = TmdbTitleMatcher(
        search = { query, mediaType, year ->
            searches += "$mediaType:$query:${year ?: "-"}"
            if (failing) {
                Result.failure(
                    IllegalStateException("offline"),
                )
            } else {
                Result.success(answer(query, mediaType, year))
            }
        },
        alternativeTitles = { _, id ->
            lookups += id
            Result.success(alternatives[id].orEmpty())
        },
    )

    @Test
    fun a_chinese_title_and_year_match_without_asking_for_other_names() =
        runTest {
            val matcher = matcher { _, _, _ -> listOf(hit(535167, "流浪地球", "流浪地球", 2019)) }

            val match = matcher.match(ParsedMediaName(listOf("流浪地球"), year = 2019))

            assertEquals(535167, assertIs<TitleMatch.Found>(match).title.tmdbId)
            assertEquals(listOf("movie:流浪地球:2019"), searches)
            assertTrue(lookups.isEmpty())
        }

    @Test
    fun an_english_file_of_a_chinese_film_is_confirmed_by_its_other_names() =
        runTest {
            val matcher =
                matcher(alternatives = mapOf(535167 to listOf("The Wandering Earth", "Liu Lang Di Qiu"))) { _, _, _ ->
                    listOf(hit(535167, "流浪地球", "流浪地球", 2019))
                }

            val match = matcher.match(ParsedMediaName(listOf("The Wandering Earth"), year = 2019))

            assertEquals("流浪地球", assertIs<TitleMatch.Found>(match).title.title)
            assertEquals(listOf(535167), lookups)
        }

    @Test
    fun tmdbs_first_hit_alone_is_not_taken_on_trust() =
        runTest {
            val matcher =
                matcher(alternatives = mapOf(1 to listOf("Something Else"))) { _, mediaType, _ ->
                    if (mediaType == TMDB_MOVIE) listOf(hit(1, "某部电影", "Some Film", 2019)) else emptyList()
                }

            assertEquals(TitleMatch.NotFound, matcher.match(ParsedMediaName(listOf("我的收藏"), year = 2019)))
        }

    @Test
    fun the_same_name_from_another_decade_is_not_the_file() =
        runTest {
            val matcher =
                matcher { _, _, year ->
                    if (year ==
                        null
                    ) {
                        listOf(hit(841, "沙丘", "Dune", 1984))
                    } else {
                        emptyList()
                    }
                }

            val match = matcher.match(ParsedMediaName(listOf("Dune"), year = 2021))

            assertEquals(TitleMatch.NotFound, match)
            assertEquals(listOf("movie:Dune:2021", "movie:Dune:-"), searches.take(2))
            assertTrue(lookups.isEmpty(), "a hit whose year conflicts is not confirmed either")
        }

    @Test
    fun traditional_characters_match_the_simplified_title() =
        runTest {
            val matcher =
                matcher { _, mediaType, _ ->
                    if (mediaType == TMDB_TV) listOf(hit(209867, "葬送的芙莉莲", "葬送のフリーレン", 2023, TMDB_TV)) else emptyList()
                }

            val match = matcher.match(ParsedMediaName(listOf("葬送的芙莉蓮"), kind = ParsedMediaKind.Episode, episode = 5))

            assertEquals(209867, assertIs<TitleMatch.Found>(match).title.tmdbId)
            assertTrue(assertIs<TitleMatch.Found>(match).title.isSeries)
        }

    @Test
    fun the_season_whose_year_agrees_wins_over_a_remake_of_the_same_name() =
        runTest {
            val matcher =
                matcher { _, _, _ ->
                    listOf(hit(108545, "三体", "3 Body Problem", 2024, TMDB_TV), hit(204541, "三体", "三体", 2023, TMDB_TV))
                }

            val match =
                matcher.match(
                    ParsedMediaName(listOf("三体"), year = 2023, kind = ParsedMediaKind.Episode, episode = 3),
                )

            assertEquals(204541, assertIs<TitleMatch.Found>(match).title.tmdbId)
        }

    @Test
    fun a_year_that_is_part_of_the_title_is_not_held_against_it() =
        runTest {
            val matcher = matcher { _, _, _ -> listOf(hit(64010, "请回答1988", "응답하라 1988", 2015, TMDB_TV)) }

            val match =
                matcher.match(
                    ParsedMediaName(
                        listOf("Reply", "请回答1988"),
                        year = 1988,
                        kind = ParsedMediaKind.Episode,
                        episode = 1,
                    ),
                )

            assertEquals(64010, assertIs<TitleMatch.Found>(match).title.tmdbId)
            assertEquals("tv:请回答1988:-", searches.first(), "the Chinese candidate is asked first")
        }

    @Test
    fun a_tmdb_tag_picks_its_title_out_of_the_results() =
        runTest {
            val matcher =
                matcher {
                        _,
                        _,
                        _,
                    ->
                    listOf(hit(1, "奥本海默", "Oppenheimer", 1965), hit(872585, "奥本海默", "Oppenheimer", 2023))
                }

            val match = matcher.match(ParsedMediaName(listOf("Oppenheimer"), tmdbId = 872585))

            assertEquals(872585, assertIs<TitleMatch.Found>(match).title.tmdbId)
        }

    @Test
    fun an_unreachable_tmdb_is_reported_rather_than_read_as_no_match() =
        runTest {
            val matcher = matcher(failing = true) { _, _, _ -> emptyList() }

            assertIs<TitleMatch.Unavailable>(matcher.match(ParsedMediaName(listOf("Dune"), year = 2021)))
        }

    @Test
    fun a_film_filed_as_an_episode_is_found_as_a_film_only_when_it_is_certain() =
        runTest {
            val matcher =
                matcher { _, mediaType, _ ->
                    if (mediaType == TMDB_MOVIE) listOf(hit(129, "千与千寻", "千と千尋の神隠し", 2001)) else emptyList()
                }

            val match = matcher.match(ParsedMediaName(listOf("千与千寻"), kind = ParsedMediaKind.Episode, episode = 1))

            assertEquals(129, assertIs<TitleMatch.Found>(match).title.tmdbId)
            assertEquals(listOf("tv:千与千寻:-", "movie:千与千寻:-"), searches)
        }

    @Test
    fun names_are_scored_on_what_they_share() {
        assertEquals(1.0, titleSimilarity("Mr Robot", "Mr. Robot"))
        assertTrue(titleSimilarity("Star Wars Episode IV A New Hope", "Star Wars") >= 0.75)
        assertTrue(titleSimilarity("葬送的芙莉蓮", "葬送的芙莉莲") >= 0.8)
        assertTrue(titleSimilarity("英雄", "英雄本色") < 1.0)
        assertTrue(titleSimilarity("Hello", "Goodbye") < 0.3)
    }

    private fun hit(
        id: Int,
        title: String,
        original: String,
        year: Int,
        mediaType: String = TMDB_MOVIE,
    ) = TmdbSearchResult(
        id = id,
        mediaType = mediaType,
        title = title,
        originalTitle = original,
        year = year,
        posterPath = "/$id.jpg",
        backdropPath = null,
        overview = null,
        rating = 7.5,
        voteCount = 100,
        popularity = 10.0,
    )
}
