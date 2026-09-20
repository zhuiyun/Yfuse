package com.yfuse.core.data

import com.russhwolf.settings.MapSettings
import com.yfuse.core.data.dto.PlexMediaContainerDto
import com.yfuse.core.data.dto.PlexMediaDto
import com.yfuse.core.data.dto.PlexMetadataDto
import com.yfuse.core.data.dto.PlexPartDto
import com.yfuse.core.data.dto.PlexResponseDto
import com.yfuse.core.data.dto.PlexStreamDto
import com.yfuse.core.model.MediaServerKind
import com.yfuse.core.model.SavedServer
import com.yfuse.core.sync.playback.PlaybackSyncStore
import com.yfuse.feature.json
import com.yfuse.feature.testRepo
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SeriesPlaybackResolutionTest {
    @Test
    fun plex_directory_reuses_real_tracks_for_fresh_negotiations() = assertPlexResolution(complete = true)

    @Test
    fun plex_summary_tracks_require_episode_metadata_before_negotiation() = assertPlexResolution(complete = false)

    private fun assertPlexResolution(complete: Boolean) =
        runTest {
            val server =
                SavedServer("plex", "http://plex:32400", "Plex", "user", "User", "private", kind = MediaServerKind.Plex)
            val progress = PlaybackSyncStore(MapSettings()) { 1_000L }
            progress.seedServerProgressIfAbsent(server.id, "e2", positionMs = 12_000L, played = false)
            val selected = plexEpisode("e2")
            val paths = mutableListOf<String>()
            val repo =
                testRepo(progressProjection = PlaybackProgressProjection(progress)) { request ->
                    paths += request.url.encodedPath
                    assertEquals("private", request.headers["X-Plex-Token"])
                    val rows =
                        when (request.url.encodedPath) {
                            "/library/metadata/series/allLeaves" -> {
                                assertEquals("1", request.url.parameters["includeMedia"])
                                val row =
                                    if (complete) {
                                        selected
                                    } else {
                                        selected.copy(
                                            Media =
                                                selected.Media.map { media ->
                                                    media.copy(Part = media.Part.map { it.copy(Stream = emptyList()) })
                                                },
                                        )
                                    }
                                listOf(plexEpisode("e1").copy(viewOffset = 990_000L), row)
                            }
                            "/library/metadata/e2" -> listOf(selected)
                            else -> error("unexpected request")
                        }
                    json(Json.encodeToString(PlexResponseDto(PlexMediaContainerDto(Metadata = rows))))
                }
            val resolution = repo.resolveSeriesPlayback(server, "series").getOrThrow()
            assertEquals("e2", resolution.target.itemId)
            assertEquals(120_000_000L, resolution.target.startPositionTicks)
            assertEquals(
                2,
                resolution.detail.versions
                    .single()
                    .audioTracks.size,
            )
            assertEquals(
                1,
                resolution.detail.versions
                    .single()
                    .subtitleTracks.size,
            )
            assertTrue(
                resolution.detail.versions
                    .single()
                    .isDolbyVision,
            )
            val first = repo.playbackInfo(server, "e2", playSessionId = "fresh-1").getOrThrow()
            val second = repo.playbackInfo(server, "e2", playSessionId = "fresh-2").getOrThrow()
            assertEquals("fresh-1", first.PlaySessionId)
            assertEquals("fresh-2", second.PlaySessionId)
            assertTrue(
                first.MediaSources
                    .single()
                    .DirectStreamUrl
                    .orEmpty()
                    .contains("X-Plex-Token=private"),
            )
            assertEquals(
                listOf("/library/metadata/series/allLeaves") +
                    if (complete) emptyList() else listOf("/library/metadata/e2"),
                paths,
            )
        }

    private fun plexEpisode(id: String): PlexMetadataDto =
        PlexMetadataDto(
            ratingKey = id,
            type = "episode",
            title = "Episode",
            grandparentRatingKey = "series",
            duration = 3_600_000L,
            Media =
                listOf(
                    PlexMediaDto(
                        id = 100L,
                        container = "mkv",
                        width = 3840,
                        height = 2160,
                        videoCodec = "hevc",
                        audioCodec = "aac",
                        audioChannels = 2,
                        Part =
                            listOf(
                                PlexPartDto(
                                    id = 101L,
                                    key = "/library/parts/101/file.mkv",
                                    file = "/media/file.mkv",
                                    Stream =
                                        listOf(
                                            PlexStreamDto(
                                                index = 0,
                                                streamType = 1,
                                                codec = "hevc",
                                                width = 3840,
                                                height = 2160,
                                                doviProfile = 8,
                                            ),
                                            PlexStreamDto(
                                                index = 1,
                                                streamType = 2,
                                                codec = "aac",
                                                channels = 2,
                                                samplingRate = 48000,
                                            ),
                                            PlexStreamDto(
                                                index = 2,
                                                streamType = 2,
                                                codec = "aac",
                                                channels = 6,
                                                samplingRate = 48000,
                                            ),
                                            PlexStreamDto(
                                                index = 3,
                                                streamType = 3,
                                                codec = "srt",
                                                languageCode = "zho",
                                            ),
                                        ),
                                ),
                            ),
                    ),
                ),
        )
}
