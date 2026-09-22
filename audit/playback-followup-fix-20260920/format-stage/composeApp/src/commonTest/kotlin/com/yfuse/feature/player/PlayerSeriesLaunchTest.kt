package com.yfuse.feature.player

import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import com.russhwolf.settings.MapSettings
import com.yfuse.core.data.PlaybackProgressProjection
import com.yfuse.core.data.dto.BaseItemDto
import com.yfuse.core.data.dto.ItemsResponseDto
import com.yfuse.core.data.dto.MediaSourceDto
import com.yfuse.core.data.dto.MediaStreamDto
import com.yfuse.core.data.dto.UserDataDto
import com.yfuse.core.model.SavedServer
import com.yfuse.core.sync.playback.PlaybackSyncStore
import com.yfuse.feature.json
import com.yfuse.feature.testRegistry
import com.yfuse.feature.testRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerSeriesLaunchTest {
    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun matched_series_reuses_directory_sources_and_local_progress_before_negotiation() =
        assertSeriesStartup(knownSeries = true, completeDirectory = true)

    @Test
    fun unknown_series_checks_identity_then_reuses_directory_sources() =
        assertSeriesStartup(knownSeries = false, completeDirectory = true)

    @Test
    fun incomplete_directory_source_keeps_episode_detail_fallback_before_negotiation() =
        assertSeriesStartup(knownSeries = true, completeDirectory = false)

    @Test
    fun directory_video_summary_does_not_hide_audio_tracks() =
        assertSeriesStartup(knownSeries = true, completeDirectory = false, videoOnly = true)

    private fun assertSeriesStartup(
        knownSeries: Boolean,
        completeDirectory: Boolean,
        videoOnly: Boolean = false,
    ) = runBlocking {
        withTimeout(5_000L) {
            val server = SavedServer("id", "http://host:8096", "server", "u1", "user", "tok")
            val registry = testRegistry().apply { addOrUpdate(server) }
            val progress = PlaybackSyncStore(MapSettings()) { 1_000L }
            progress.seedServerProgressIfAbsent(server.id, "e1", positionMs = 0L, played = true)
            progress.seedServerProgressIfAbsent(server.id, "e2", positionMs = 25_000L, played = false)
            val episode = playbackEpisode()
            val paths = mutableListOf<String>()
            var startupPaths = emptyList<String>()
            val repo =
                testRepo(progressProjection = PlaybackProgressProjection(progress)) { request ->
                    val path = request.url.encodedPath
                    paths += path
                    assertEquals("tok", request.headers["X-Emby-Token"])
                    when {
                        path.endsWith("/Items/e2/PlaybackInfo") -> {
                            startupPaths = paths.toList()
                            json("""{"MediaSources":[],"PlaySessionId":"episode-session"}""")
                        }
                        path.endsWith("/Shows/series/Episodes") -> {
                            assertTrue(
                                request.url.parameters["Fields"]
                                    .orEmpty()
                                    .contains("MediaSources"),
                            )
                            val row =
                                if (completeDirectory) {
                                    episode
                                } else {
                                    // A summary of one version must not hide the tracks of the other.
                                    episode.copy(
                                        MediaSources =
                                            episode.MediaSources.orEmpty().mapIndexed { index, source ->
                                                if (index == 1) {
                                                    source.copy(
                                                        MediaStreams =
                                                            source.MediaStreams?.filter {
                                                                videoOnly &&
                                                                    it.Type == "Video"
                                                            },
                                                    )
                                                } else {
                                                    source
                                                }
                                            },
                                    )
                                }
                            json(
                                Json.encodeToString(
                                    ItemsResponseDto(
                                        Items =
                                            listOf(
                                                episode.copy(
                                                    Id = "e1",
                                                    UserData = UserDataDto(PlaybackPositionTicks = 990_000_000L),
                                                ),
                                                row,
                                            ),
                                    ),
                                ),
                            )
                        }
                        path.endsWith("/Items/e2") -> json(Json.encodeToString(episode))
                        path.endsWith("/Items/series") -> json("""{"Id":"series","Name":"Show","Type":"Series"}""")
                        else -> error("unexpected request $path")
                    }
                }
            val store =
                PlayerStoreFactory(
                    DefaultStoreFactory(),
                    repo,
                    registry,
                    itemId = "series",
                    startPositionTicks = 0L,
                    isSeriesLaunch = knownSeries,
                ).create()
            try {
                val state = store.states.first { !it.loading }
                assertEquals(null, state.error)
                assertEquals("e2", state.items[state.startIndex].id)
                assertEquals(25_000L, state.startPositionMs)
                val versions = state.items[state.startIndex].versions
                assertEquals(listOf("sdr", "dolby"), versions.map { it.id })
                assertTrue(versions.last().dolbyVision)
                assertEquals(2, versions.last().audioTrackCount)
                assertEquals(listOf("aac", "aac"), versions.last().sourceAudioCodecs)
                assertEquals(
                    buildList {
                        if (!knownSeries) add("/Users/u1/Items/series")
                        add("/Shows/series/Episodes")
                        if (!completeDirectory) add("/Users/u1/Items/e2")
                        add("/Items/e2/PlaybackInfo")
                    },
                    startupPaths,
                )
                store.states.first { !it.enrichmentPending }
                assertEquals(
                    if (completeDirectory) 1 else 2,
                    paths.count { it.endsWith("/Shows/series/Episodes") },
                )
            } finally {
                store.dispose()
            }
        }
    }

    @Test
    fun matched_empty_series_never_negotiates_the_parent_as_a_video() =
        runBlocking {
            withTimeout(5_000L) {
                val server = SavedServer("id", "http://host:8096", "server", "u1", "user", "tok")
                val registry = testRegistry().apply { addOrUpdate(server) }
                val paths = mutableListOf<String>()
                val repo =
                    testRepo { request ->
                        paths += request.url.encodedPath
                        json("""{"Items":[]}""")
                    }
                val store =
                    PlayerStoreFactory(
                        DefaultStoreFactory(),
                        repo,
                        registry,
                        itemId = "series",
                        startPositionTicks = 0L,
                        isSeriesLaunch = true,
                    ).create()
                try {
                    val state = store.states.first { !it.loading }
                    assertTrue(state.error != null)
                    assertEquals(listOf("/Shows/series/Episodes"), paths)
                } finally {
                    store.dispose()
                }
            }
        }

    private fun playbackEpisode(): BaseItemDto =
        BaseItemDto(
            Id = "e2",
            Name = "Second episode",
            Type = "Episode",
            SeriesId = "series",
            SeriesName = "Show",
            IndexNumber = 2,
            ParentIndexNumber = 1,
            RunTimeTicks = 36_000_000_000L,
            ProviderIds = mapOf("Tmdb" to "episode-2"),
            MediaSources =
                listOf("sdr", "dolby").map { id ->
                    MediaSourceDto(
                        Id = id,
                        Container = "mkv",
                        Path = "/media/$id.mkv",
                        MediaStreams =
                            listOf(
                                MediaStreamDto(
                                    Index = 0,
                                    Type = "Video",
                                    Codec = "hevc",
                                    Width = 3840,
                                    Height = 2160,
                                    DvProfile = if (id == "dolby") 8 else null,
                                ),
                                MediaStreamDto(
                                    Index = 1,
                                    Type = "Audio",
                                    Codec = "aac",
                                    Language = "eng",
                                    Channels = 2,
                                    SampleRate = 48000,
                                ),
                                MediaStreamDto(
                                    Index = 2,
                                    Type = "Audio",
                                    Codec = "aac",
                                    Language = "zho",
                                    Channels = 6,
                                    SampleRate = 48000,
                                ),
                                MediaStreamDto(Index = 3, Type = "Subtitle", Codec = "srt", Language = "zho"),
                            ),
                    )
                },
        )

    @Test
    fun series_is_resolved_to_episode_before_playback_info() =
        runBlocking {
            withTimeout(5_000L) {
                val registry =
                    testRegistry().apply {
                        addOrUpdate(SavedServer("id", "http://host:8096", "server", "u1", "user", "tok"))
                    }
                val requestedPaths = mutableListOf<String>()
                val repo =
                    testRepo { request ->
                        val path = request.url.encodedPath
                        requestedPaths += path
                        when {
                            path.endsWith("/Shows/NextUp") ->
                                json(
                                    """{"Items":[{"Id":"e4","Name":"第四集","Type":"Episode",""" +
                                        """"SeriesId":"series","IndexNumber":4,"ParentIndexNumber":1,""" +
                                        """"UserData":{"PlaybackPositionTicks":33000000}}]}""",
                                )
                            path.endsWith("/Items/e4/PlaybackInfo") ->
                                json("""{"MediaSources":[],"PlaySessionId":"session-e4"}""")
                            path.contains("/Shows/series/Episodes") ->
                                json(
                                    """{"Items":[{"Id":"e4","Name":"第四集","Type":"Episode",""" +
                                        """"SeriesId":"series","IndexNumber":4,"ParentIndexNumber":1,""" +
                                        """"UserData":{"PlaybackPositionTicks":33000000}}]}""",
                                )
                            path.endsWith("/Items/e4") ->
                                json(
                                    """{"Id":"e4","Name":"第四集","Type":"Episode","SeriesId":"series",""" +
                                        """"SeriesName":"某剧","IndexNumber":4,"ParentIndexNumber":1,""" +
                                        """"UserData":{"PlaybackPositionTicks":33000000}}""",
                                )
                            path.endsWith("/Items/series") ->
                                json("""{"Id":"series","Name":"某剧","Type":"Series"}""")
                            else -> json("{}")
                        }
                    }
                val store =
                    PlayerStoreFactory(
                        DefaultStoreFactory(),
                        repo,
                        registry,
                        itemId = "series",
                        startPositionTicks = 0L,
                    ).create()

                try {
                    val state = store.states.first { !it.loading && !it.enrichmentPending }

                    assertEquals(listOf("e4"), state.items.map { it.id })
                    assertEquals(0L, state.startPositionMs)
                    assertFalse(requestedPaths.any { it.endsWith("/Shows/NextUp") })
                    assertTrue(requestedPaths.any { it.endsWith("/Items/e4/PlaybackInfo") })
                    assertFalse(requestedPaths.any { it.endsWith("/Items/series/PlaybackInfo") })
                } finally {
                    store.dispose()
                }
            }
        }
}
