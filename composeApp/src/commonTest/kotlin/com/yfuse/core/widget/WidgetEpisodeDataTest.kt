package com.yfuse.core.widget

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WidgetEpisodeDataTest {
    private fun source(link: String = "https://source.example/a") =
        WidgetMedia("show", "A show", mediaType = "tv", type = "tmdb", tmdbId = "42", link = link, poster = "$link.jpg")

    private fun episode(
        number: Int,
        available: Boolean = true,
    ) = WidgetMedia(
        "episode-$number",
        "Episode $number",
        mediaType = "tv",
        tmdbId = "42",
        season = 1,
        episode = number,
        available = available,
        libraryTarget = WidgetLibraryTarget("server", "e$number"),
    )

    private class Library : WidgetEpisodeLibrary {
        override val revision = MutableStateFlow(0L)
        var partition = "profile-1"
        var directoryCalls = 0
        var nextCalls = 0
        var nextIds = emptySet<String>()
        var progress = emptyMap<String, WidgetEpisodeProgress>()
        var failure = false
        var gate: CompletableDeferred<Unit>? = null
        lateinit var content: WidgetEpisodeDirectory

        override fun scopeKey() = partition

        override suspend fun directory(item: WidgetMedia): WidgetEpisodeDirectory {
            directoryCalls++
            gate?.await()
            check(!failure)
            return content
        }

        override suspend fun nextUp(serverId: String): Set<String> {
            nextCalls++
            return nextIds
        }

        override fun progress(target: WidgetLibraryTarget) = progress[target.itemId]
    }

    private fun library() =
        Library().apply {
            content =
                WidgetEpisodeDirectory(
                    WidgetMedia(
                        "native-series",
                        "Native show",
                        libraryTarget = WidgetLibraryTarget("server", "series"),
                    ),
                    emptyList(),
                    listOf(episode(1), episode(2), episode(3, available = false)),
                )
        }

    @Test fun next_up_requires_a_confirmed_library_id_and_unknown_progress_stays_unknown() =
        runTest {
            val library = library()
            val data = WidgetEpisodeData(library, backgroundScope)
            val noNext = data.load(source(), includeNext = true).item
            assertNull(noNext.nextEpisodeId)
            assertTrue(noNext.widgetEpisodeItems().all { it.played == null && it.progress == null })
            library.nextIds = setOf("e2")
            val confirmed = data.load(source(), includeNext = true, refresh = true).item
            assertEquals("episode-2", confirmed.confirmedNextWidgetEpisode()?.id)
            assertEquals(WidgetLibraryTarget("server", "e2"), confirmed.confirmedNextWidgetEpisode()?.libraryTarget)
        }

    @Test fun progress_changes_reproject_cached_metadata_without_refetching_next_up() =
        runTest {
            val library = library().apply { nextIds = setOf("e2") }
            var clock = 0L
            val data = WidgetEpisodeData(library, backgroundScope) { clock }
            data.load(source(), includeNext = true)
            library.progress = mapOf("e2" to WidgetEpisodeProgress(true, 600L, 600L))
            library.revision.value++
            val updated = data.load(source(), includeNext = true).item
            assertEquals(true, updated.widgetEpisodeItems()[1].played)
            assertEquals(1f, updated.widgetEpisodeItems()[1].progress)
            assertNull(updated.nextEpisodeId)
            assertEquals(1, library.directoryCalls)
            assertEquals(1, library.nextCalls)
            clock = 60_001L
            data.load(source(), includeNext = true)
            assertEquals(2, library.nextCalls)
        }

    @Test fun runtime_supplies_missing_progress_duration_and_clearing_local_state_clears_cached_progress() =
        runTest {
            val library =
                library().apply {
                    content = content.copy(episodes = listOf(episode(1).copy(durationMinutes = 40)))
                    progress = mapOf("e1" to WidgetEpisodeProgress(false, 600_000L, 0L))
                }
            val data = WidgetEpisodeData(library, backgroundScope)
            val partial =
                data
                    .load(source(), includeNext = false)
                    .item
                    .widgetEpisodeItems()
                    .single()
            assertEquals(.25f, partial.progress)
            library.progress = emptyMap()
            val cleared =
                data
                    .load(source(), includeNext = false)
                    .item
                    .widgetEpisodeItems()
                    .single()
            assertNull(cleared.progress)
            assertNull(cleared.played)
            library.progress = mapOf("e1" to WidgetEpisodeProgress(false, 0L, 0L))
            val reset =
                data
                    .load(source(), includeNext = false)
                    .item
                    .widgetEpisodeItems()
                    .single()
            assertEquals(false, reset.played)
            assertNull(reset.progress)
            assertEquals(1, library.directoryCalls)
        }

    @Test fun two_sources_share_only_the_native_directory_and_keep_their_own_source_fields() =
        runTest {
            val library = library()
            val data = WidgetEpisodeData(library, backgroundScope)
            val first = source("https://first.example/show").copy(headers = mapOf("Referer" to "first"))
            val second = source("https://second.example/show").copy(videoUrl = "https://second.example/movie.mp4")
            data.load(first, includeNext = false)
            val result = data.load(second, includeNext = false).item
            assertEquals(second.link, result.link)
            assertEquals(second.videoUrl, result.videoUrl)
            assertEquals(second.headers, result.headers)
            assertEquals(second.poster, result.widgetEpisodeItems().first().poster)
            assertEquals(1, library.directoryCalls)
        }

    @Test fun a_profile_change_during_network_loading_does_not_publish_the_previous_profile() =
        runTest {
            val library = library().apply { gate = CompletableDeferred() }
            val data = WidgetEpisodeData(library, backgroundScope)
            val original = source()
            val pending = async { data.load(original, includeNext = true) }
            testScheduler.runCurrent()
            library.partition = "profile-2"
            library.revision.value++
            library.gate?.complete(Unit)
            assertEquals(original, pending.await().item)
            assertEquals(0, library.nextCalls)
            data.load(original, includeNext = false)
            assertEquals(2, library.directoryCalls)
        }

    @Test fun a_failed_native_load_preserves_the_source_and_can_be_retried() =
        runTest {
            val library = library().apply { failure = true }
            val data = WidgetEpisodeData(library, backgroundScope)
            val original = source()
            val failed = data.load(original, includeNext = false)
            assertEquals(original, failed.item)
            assertTrue(failed.retryable)
            library.failure = false
            val recovered = data.load(original, includeNext = false, refresh = true)
            assertFalse(recovered.retryable)
            assertEquals(3, recovered.item.widgetEpisodeItems().size)
        }

    @Test fun unavailable_or_already_watched_episodes_are_not_confirmed_as_next() =
        runTest {
            val library =
                library().apply {
                    nextIds = setOf("e1", "e3")
                    progress = mapOf("e1" to WidgetEpisodeProgress(true, 0, 600))
                }
            val item = WidgetEpisodeData(library, backgroundScope).load(source(), includeNext = true).item
            assertNull(item.confirmedNextWidgetEpisode())
        }

    @Test fun explicit_source_episodes_keep_coordinates_and_inherit_parent_artwork_without_native_requests() =
        runTest {
            val library = library()
            val root =
                source().copy(
                    headers = mapOf("Referer" to "source"),
                    children =
                        listOf(
                            WidgetMedia(
                                "season",
                                "Season",
                                season = 2,
                                children =
                                    listOf(
                                        WidgetMedia("episode", "Episode", episode = 5, played = false, progress = .4f),
                                    ),
                            ),
                        ),
                )
            val result = WidgetEpisodeData(library, backgroundScope).load(root, includeNext = true).item
            val actual = result.widgetEpisodeItems().single()
            assertEquals(2, actual.season)
            assertEquals(5, actual.episode)
            assertEquals("42", actual.tmdbId)
            assertEquals(root.poster, actual.poster)
            assertEquals(root.headers, actual.headers)
            assertEquals(actual.id, result.confirmedNextWidgetEpisode()?.id)
            assertEquals(0, library.directoryCalls)
        }

    @Test fun script_json_cannot_forge_native_navigation_or_next_up_and_duration_is_explicit() {
        val item =
            parseWidgetMedia(
                """[{
                  "id":"show","title":"Show","libraryTarget":{"serverId":"server","itemId":"secret"},
                  "nextEpisodeId":"e2","played":"false","runtimeMinutes":42
                }]""",
            ).single()
        assertNull(item.libraryTarget)
        assertNull(item.nextEpisodeId)
        assertNull(item.played)
        assertEquals(42, item.durationMinutes)
    }
}
