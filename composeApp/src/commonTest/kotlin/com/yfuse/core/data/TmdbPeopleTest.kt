package com.yfuse.core.data

import com.yfuse.feature.json
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TmdbPeopleTest {
    @Test
    fun a_person_is_read_with_credits_and_translations_in_one_request() =
        runTest {
            var seen: HttpRequestData? = null
            val repo =
                repo { request ->
                    seen = request
                    json(PERSON)
                }

            val detail = repo.person(1337).getOrThrow()

            val request = requireNotNull(seen)
            assertEquals("/3/person/1337", request.url.encodedPath)
            assertEquals("combined_credits,translations", request.url.parameters["append_to_response"])
            assertEquals("zh-CN", request.url.parameters["language"])
            assertEquals("梁朝伟", detail.person.name)
            // The primary name, then the translations, then the aliases — each once.
            assertEquals(listOf("梁朝伟", "Tony Leung Chiu-wai", "梁朝偉", "Tony Leung"), detail.names)
            assertEquals("1962-06-27", detail.birthday)
            assertNull(detail.deathday)
            assertEquals("香港", detail.placeOfBirth)
        }

    @Test
    fun an_empty_chinese_biography_falls_back_to_a_translation() =
        runTest {
            val repo = repo { json(PERSON) }

            val detail = repo.person(1337).getOrThrow()

            assertEquals("English biography.", detail.biography)
        }

    @Test
    fun credits_keep_the_work_and_leave_the_diary_out() =
        runTest {
            val repo = repo { json(PERSON) }

            val credits = repo.person(1337).getOrThrow().credits

            // 花样年华 once though credited twice; the talk show, the self appearance, the adult
            // title, the credit without a type and the one without a name are gone.
            assertEquals(listOf("movie:843", "tv:500"), credits.map { "${it.mediaType}:${it.id}" })
            assertEquals("2000", credits.first().year)
            assertEquals("2000-09-29", credits.first().releaseDate)
            assertEquals(1200, credits.first().voteCount)
            assertEquals("2004", credits.last().year)
        }

    @Test
    fun a_director_brings_the_films_they_directed() =
        runTest {
            val repo =
                repo {
                    json(
                        """{"id":12453,"name":"王家卫","known_for_department":"Directing",""" +
                            """"combined_credits":{"cast":[],"crew":[""" +
                            """{"id":843,"media_type":"movie","title":"花样年华","department":"Directing"},""" +
                            """{"id":11104,"media_type":"movie","title":"重庆森林","department":"Writing"}]}}""",
                    )
                }

            val credits = repo.person(12453).getOrThrow().credits

            assertEquals(listOf(843), credits.map { it.id })
        }

    @Test
    fun a_person_search_keeps_names_and_leaves_adult_entries_out() =
        runTest {
            var seen: HttpRequestData? = null
            val repo =
                repo { request ->
                    seen = request
                    json(
                        """{"results":[{"id":1,"name":"梁朝伟","profile_path":"/a.jpg"},""" +
                            """{"id":2,"name":"","original_name":"Tony Leung"},""" +
                            """{"id":3,"name":"梁朝伟","adult":true},{"id":4,"name":"  "}]}""",
                    )
                }

            val people = repo.searchPeople("梁朝伟").getOrThrow()

            assertEquals("/3/search/person", requireNotNull(seen).url.encodedPath)
            assertEquals("梁朝伟", seen?.url?.parameters?.get("query"))
            assertEquals("false", seen?.url?.parameters?.get("include_adult"))
            assertEquals(listOf(1, 2), people.map { it.id })
            assertEquals(listOf("梁朝伟", "Tony Leung"), people.map { it.name })
        }

    @Test
    fun a_failed_request_is_a_failed_result() =
        runTest {
            val repo = repo { respond(content = "", status = HttpStatusCode.Unauthorized) }

            assertTrue(repo.person(1).isFailure)
            assertTrue(repo.searchPeople("梁朝伟").isFailure)
        }

    private fun TestScope.repo(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): TmdbRepository =
        TmdbRepository(
            HttpClient(
                MockEngine(
                    MockEngineConfig().apply {
                        dispatcher = StandardTestDispatcher(testScheduler)
                        addHandler(handler)
                    },
                ),
            ) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true })
                }
            },
        )

    private companion object {
        val PERSON =
            """{"id":1337,"name":"梁朝伟","also_known_as":["梁朝偉"," Tony Leung ","梁朝伟"],""" +
                """"biography":"","birthday":"1962-06-27","deathday":null,"place_of_birth":" 香港 ",""" +
                """"known_for_department":"Acting","profile_path":"/face.jpg",""" +
                """"combined_credits":{"cast":[""" +
                """{"id":843,"media_type":"movie","title":"花样年华","release_date":"2000-09-29",""" +
                """"vote_count":1200,"poster_path":"/p.jpg","character":"周慕云"},""" +
                """{"id":843,"media_type":"movie","title":"花样年华","character":"周慕云 (voice)"},""" +
                """{"id":500,"media_type":"tv","name":"剧集","first_air_date":"2004-01-01","character":"阿伟"},""" +
                """{"id":600,"media_type":"tv","name":"访谈","genre_ids":[10767],"character":"嘉宾"},""" +
                """{"id":700,"media_type":"movie","title":"纪录","character":"Himself"},""" +
                """{"id":800,"media_type":"movie","title":"成人","adult":true,"character":"某人"},""" +
                """{"id":900,"title":"无类型","character":"某人"},""" +
                """{"id":901,"media_type":"movie","title":" ","character":"某人"}],"crew":[]},""" +
                """"translations":{"translations":[""" +
                """{"iso_639_1":"zh","iso_3166_1":"CN","data":{"name":"","biography":""}},""" +
                """{"iso_639_1":"en","iso_3166_1":"US","data":{"name":"Tony Leung Chiu-wai",""" +
                """"biography":"English biography."}}]}}"""
    }
}
