package com.yfuse.feature.player

import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import com.yfuse.core.data.PlaybackFailoverPlan
import com.yfuse.core.data.PlaybackFailoverRequest
import com.yfuse.core.model.MediaVersion
import com.yfuse.core.model.PlaybackMethod
import com.yfuse.core.model.PlaybackQuality
import com.yfuse.core.model.SavedServer
import com.yfuse.feature.json
import com.yfuse.feature.testRegistry
import com.yfuse.feature.testRepo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerStoreTest {
    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun retry_after_initial_load_failure_enters_loading_and_recovers() =
        runTest {
            val registry = testRegistry()
            val allowSuccessfulLoad = CompletableDeferred<Unit>()
            val successfulLoadStarted = CompletableDeferred<Unit>()
            var requestCount = 0
            val repo =
                testRepo { request ->
                    requestCount += 1
                    successfulLoadStarted.complete(Unit)
                    allowSuccessfulLoad.await()
                    when {
                        request.url.encodedPath.endsWith("/PlaybackInfo") ->
                            json("""{"MediaSources":[],"PlaySessionId":"session-retry"}""")
                        request.url.encodedPath.endsWith("/Items/movie") ->
                            json("""{"Id":"movie","Name":"恢复播放","Type":"Movie"}""")
                        else -> json("{}")
                    }
                }
            val store =
                PlayerStoreFactory(
                    DefaultStoreFactory(),
                    repo,
                    registry,
                    itemId = "movie",
                    startPositionTicks = 12_340_000L,
                ).create()

            val failed = store.states.first { !it.loading }
            assertEquals("没有可用的服务器", failed.error)
            assertTrue(failed.items.isEmpty())
            assertEquals(0, requestCount, "A missing server should fail before making HTTP calls")

            registry.addOrUpdate(
                SavedServer("id", "http://host:8096", "server", "u1", "user", "tok"),
            )
            store.accept(PlayerIntent.Retry)

            // Loading is dispatched before the request coroutine reaches the mock engine. Wait for
            // the observable request boundary so this assertion is independent of dispatcher speed.
            successfulLoadStarted.await()
            val retrying = store.state
            assertNull(retrying.error)
            assertTrue(retrying.items.isEmpty())
            assertEquals(1, requestCount, "Retry should start exactly one queue rebuild")

            // A second press while the first retry is in flight must not start a parallel load.
            store.accept(PlayerIntent.Retry)
            assertEquals(1, requestCount)

            allowSuccessfulLoad.complete(Unit)
            val recovered = store.states.first { !it.loading && it.error == null }

            assertEquals(listOf("movie"), recovered.items.map { it.id })
            assertEquals("恢复播放", recovered.items.single().title)
            assertEquals(1_234L, recovered.startPositionMs)
            assertTrue(requestCount >= 2)
            store.dispose()
        }

    @Test
    fun episode_loads_series_queue_and_resume_position() =
        runTest {
            val registry =
                testRegistry().apply {
                    addOrUpdate(SavedServer("id", "http://host:8096", "server", "u1", "user", "tok"))
                }
            val repo =
                testRepo { request ->
                    when {
                        request.url.encodedPath.endsWith("/PlaybackInfo") ->
                            json("""{"MediaSources":[],"PlaySessionId":"session-e2"}""")
                        request.url.encodedPath.contains("/Shows/s1/Episodes") ->
                            json(
                                """{"Items":[{"Id":"e1","Name":"开场","Type":"Episode",""" +
                                    """"IndexNumber":1,"ParentIndexNumber":2},""" +
                                    """{"Id":"e2","Name":"转折","Type":"Episode","IndexNumber":2,"ParentIndexNumber":2}]}""",
                            )
                        request.url.encodedPath.endsWith("/Items/s1") ->
                            json(
                                """{"Id":"s1","Name":"某剧","Type":"Series",""" +
                                    """"ImageTags":{"Primary":"series-poster"}}""",
                            )
                        else ->
                            json(
                                """{"Id":"e2","Name":"转折","Type":"Episode","SeriesId":"s1","SeriesName":"某剧"}""",
                            )
                    }
                }
            val store =
                PlayerStoreFactory(
                    DefaultStoreFactory(),
                    repo,
                    registry,
                    itemId = "e2",
                    startPositionTicks = 25_000_000L,
                ).create()

            val state = store.states.first { !it.loading }

            assertEquals(listOf("e1", "e2"), state.items.map { it.id })
            assertEquals(listOf(2, 2), state.items.map { it.seasonNumber })
            assertEquals(listOf(1, 2), state.items.map { it.episodeNumber })
            assertTrue(
                state.items.all {
                    it.posterUrl ==
                        "http://host:8096/Items/s1/Images/Primary?tag=series-poster&" +
                        "maxHeight=360&quality=85&format=webp&api_key=tok"
                },
            )
            assertEquals(1, state.startIndex)
            assertEquals(2_500L, state.startPositionMs)
            store.dispose()
        }

    @Test
    fun smart_failover_candidates_are_resolved_bounded_and_non_recursive() =
        runBlocking {
            val registry =
                testRegistry().apply {
                    addOrUpdate(SavedServer("primary", "http://primary", "primary", "u1", "user", "tok"))
                    (1..4).forEach { index ->
                        addOrUpdate(
                            SavedServer(
                                "fallback-$index",
                                "http://fallback-$index",
                                "fallback-$index",
                                "u1",
                                "user",
                                "tok",
                            ),
                        )
                    }
                }
            val repo =
                testRepo { request ->
                    val host = request.url.host
                    val suffix = host.substringAfterLast('-').takeIf { host.startsWith("fallback-") }
                    when {
                        request.url.encodedPath.endsWith("/PlaybackInfo") ->
                            json(
                                """{"MediaSources":[{"Id":"source-${suffix ?: "primary"}"}],""" +
                                    """"PlaySessionId":"session-${suffix ?: "primary"}"}""",
                            )
                        request.url.encodedPath.contains("/Users/u1/Items") &&
                            request.url.parameters["AnyProviderIdEquals"] != null ->
                            json(
                                """{"Items":[{"Id":"movie-${suffix ?: "primary"}","Name":"电影",""" +
                                    """"Type":"Movie","ProviderIds":{"Tmdb":"603"}}]}""",
                            )
                        request.url.encodedPath.endsWith("/Items/movie") ->
                            json(
                                """{"Id":"movie","Name":"电影","Type":"Movie",""" +
                                    """"ProviderIds":{"Tmdb":"603"}}""",
                            )
                        request.url.encodedPath.contains("/Items/movie-") ->
                            json(
                                """{"Id":"movie-$suffix","Name":"电影-$suffix","Type":"Movie",""" +
                                    """"ProviderIds":{"Tmdb":"603"},"MediaSources":[{"Id":"source-$suffix"}]}""",
                            )
                        else -> json("{}")
                    }
                }
            val request =
                PlaybackFailoverRequest().apply {
                    set(
                        PlaybackFailoverPlan(
                            itemId = "movie",
                            mediaKey = "tmdb:603",
                            fallbackServerIds =
                                listOf(
                                    "fallback-1",
                                    "fallback-2",
                                    "fallback-2",
                                    "fallback-3",
                                    "fallback-4",
                                ),
                        ),
                    )
                }
            val store =
                PlayerStoreFactory(
                    DefaultStoreFactory(),
                    repo,
                    registry,
                    itemId = "movie",
                    startPositionTicks = 0L,
                    serverId = "primary",
                    failoverRequest = request,
                ).create()

            val item =
                store.states
                    .first { !it.loading }
                    .items
                    .single()

            assertEquals(
                listOf("fallback-1", "fallback-2", "fallback-3"),
                item.serverFallbacks.map { it.serverId },
            )
            assertTrue(item.serverFallbacks.all { it.serverFallbacks.isEmpty() })
            assertEquals("fallback-2", item.nextServerFallback(setOf("primary", "fallback-1"))?.serverId)
            assertNull(
                item.nextServerFallback(
                    setOf("primary", "fallback-1", "fallback-2", "fallback-3"),
                ),
            )
            store.dispose()
        }

    @Test
    fun sibling_episode_transcodes_use_its_real_media_source_id() =
        runTest {
            val registry =
                testRegistry().apply {
                    addOrUpdate(SavedServer("id", "http://host:8096", "server", "u1", "user", "tok"))
                }
            val repo =
                testRepo { request ->
                    when {
                        request.url.encodedPath.endsWith("/PlaybackInfo") ->
                            json(
                                """{"MediaSources":[],"PlaySessionId":"session-e1"}""",
                            )
                        request.url.encodedPath.contains("/Shows/s1/Episodes") ->
                            json(
                                """
                                {"Items":[
                                    {"Id":"e1","Name":"一","Type":"Episode","IndexNumber":1,"ParentIndexNumber":1,
                                     "MediaSources":[{"Id":"source-e1","Container":"mkv","MediaStreams":[{"Type":"Video","Width":1920,"Height":1080}]}]},
                                    {"Id":"e2","Name":"二","Type":"Episode","IndexNumber":2,"ParentIndexNumber":1,
                                     "MediaSources":[{"Id":"source-e2","Container":"mkv","MediaStreams":[{"Type":"Video","Width":3840,"Height":2160}]}]}
                                ]}
                                """.trimIndent(),
                            )
                        request.url.encodedPath.endsWith("/Items/e1") ->
                            json(
                                """{"Id":"e1","Name":"一","Type":"Episode","SeriesId":"s1","SeriesName":"剧",
                       "MediaSources":[{"Id":"source-e1","Container":"mkv","MediaStreams":[{"Type":"Video","Width":1920,"Height":1080}]}]}""",
                            )
                        else -> json("""{"Id":"s1","Name":"剧","Type":"Series"}""")
                    }
                }
            val store =
                PlayerStoreFactory(
                    DefaultStoreFactory(),
                    repo,
                    registry,
                    itemId = "e1",
                    startPositionTicks = 0L,
                ).create()

            val state = store.states.first { !it.loading }
            val sibling = state.items.single { it.id == "e2" }

            assertTrue("MediaSourceId=source-e2" in sibling.transcodeUrl, sibling.transcodeUrl)
            assertTrue(
                "MediaSourceId=source-e2" in sibling.fallbackTranscodeUrl,
                sibling.fallbackTranscodeUrl,
            )
            assertFalse("MediaSourceId=e2&" in sibling.transcodeUrl, sibling.transcodeUrl)
            assertEquals("source-e2", sibling.versionId)
            store.dispose()
        }

    @Test
    fun playback_info_cannot_erase_iso_metadata_or_route_it_to_direct_stream() =
        runTest {
            val registry =
                testRegistry().apply {
                    addOrUpdate(SavedServer("id", "http://host:8096", "server", "u1", "user", "tok"))
                }
            val repo =
                testRepo { request ->
                    when {
                        request.url.encodedPath.endsWith("/PlaybackInfo") ->
                            json(
                                """
                                {
                                  "MediaSources":[{
                                    "Id":"disc-source",
                                    "SupportsDirectPlay":false,
                                    "SupportsDirectStream":true,
                                    "SupportsTranscoding":true,
                                    "DirectStreamUrl":"/Videos/movie/stream?static=true"
                                  }],
                                  "PlaySessionId":"session-disc"
                                }
                                """.trimIndent(),
                            )
                        else ->
                            json(
                                """
                                {
                                  "Id":"movie",
                                  "Name":"原盘电影",
                                  "Type":"Movie",
                                  "MediaSources":[{
                                    "Id":"disc-source",
                                    "Container":"iso",
                                    "VideoType":"Iso",
                                    "Path":"/media/movie.iso",
                                    "Size":193273528320
                                  }]
                                }
                                """.trimIndent(),
                            )
                    }
                }
            val store =
                PlayerStoreFactory(
                    DefaultStoreFactory(),
                    repo,
                    registry,
                    itemId = "movie",
                    startPositionTicks = 0L,
                ).create()

            val item =
                store.states
                    .first { !it.loading }
                    .items
                    .single()

            assertTrue(item.activeVersion?.discSource == true)
            assertEquals(PlaybackMethod.Transcode, item.playMethod)
            assertTrue("/Videos/movie/master.m3u8" in item.url, item.url)
            assertFalse("static=true" in item.url, item.url)
            assertFalse(item.canPreloadSource)
            store.dispose()
        }

    @Test
    fun a_declared_iso_ignores_a_raw_looking_negotiated_direct_stream_url() {
        val version =
            MediaVersion(
                id = "disc-source",
                name = "ISO",
                container = "iso",
                sizeBytes = 193_273_528_320L,
                bitrateBps = null,
                videoCodec = null,
                videoHeight = null,
                videoRange = null,
                videoType = "Iso",
                supportsDirectPlay = false,
                supportsDirectStream = true,
                supportsTranscoding = true,
                directStreamUrl = "/Videos/movie/stream?static=true",
            )

        val selected =
            listOf(version)
                .toPlayerMediaVersions(
                    baseUrl = "http://host:8096",
                    itemId = "movie",
                    token = "tok",
                    negotiatedPlaySessionId = "session-disc",
                ).single()

        assertTrue(selected.discSource)
        assertEquals(PlaybackMethod.Transcode, selected.playMethod)
        assertTrue("/Videos/movie/master.m3u8" in selected.url, selected.url)
        assertFalse("static=true" in selected.url, selected.url)
    }

    @Test
    fun display_metadata_does_not_change_playback_sources() {
        val original =
            listOf(
                PlayerMediaItem(
                    id = "e1",
                    url = "direct/e1",
                    transcodeUrl = "hls/e1",
                    fallbackTranscodeUrl = "progressive/e1",
                    title = "旧标题",
                    progress = 0.1f,
                ),
            )
        val refreshed =
            original.map {
                it.copy(title = "新标题", stillUrl = "still/e1", progress = 0.7f)
            }

        assertTrue(original.hasSamePlaybackSourcesAs(refreshed))
    }

    @Test
    fun source_or_queue_order_change_requires_engine_refresh() {
        val first = PlayerMediaItem("e1", "direct/e1", "hls/e1", "第一集")
        val second = PlayerMediaItem("e2", "direct/e2", "hls/e2", "第二集")

        assertFalse(listOf(first, second).hasSamePlaybackSourcesAs(listOf(second, first)))
        assertFalse(
            listOf(first).hasSamePlaybackSourcesAs(
                listOf(first.copy(url = "direct/e1-new")),
            ),
        )
    }

    /**
     * A play session is minted per queue build, so the same file can be addressed through two
     * different session ids. That is not a source change and must not restart the engine.
     */
    @Test
    fun a_different_play_session_for_the_same_file_is_not_a_source_change() {
        val original =
            listOf(
                PlayerMediaItem(
                    id = "e1",
                    url = "direct/e1?PlaySessionId=yfuse-aaa&DeviceId=d",
                    transcodeUrl = "hls/e1?PlaySessionId=yfuse-aaa",
                    fallbackTranscodeUrl = "progressive/e1?PlaySessionId=yfuse-aaa",
                    title = "第一集",
                    playSessionId = "yfuse-aaa",
                ),
            )
        val rebuilt =
            listOf(
                original.single().copy(
                    url = "direct/e1?PlaySessionId=yfuse-bbb&DeviceId=d",
                    transcodeUrl = "hls/e1?PlaySessionId=yfuse-bbb",
                    fallbackTranscodeUrl = "progressive/e1?PlaySessionId=yfuse-bbb",
                    playSessionId = "yfuse-bbb",
                ),
            )

        assertTrue(original.hasSamePlaybackSourcesAs(rebuilt))
    }

    @Test
    fun switching_version_moves_urls_and_the_reporting_session_together() {
        val alternate =
            PlayerMediaVersion(
                id = "alternate",
                label = "1080p",
                detail = "",
                url = "direct/alternate",
                transcodeUrl = "hls/alternate",
                fallbackTranscodeUrl = "progressive/alternate",
                playSessionId = "session-alternate",
            )
        val item =
            PlayerMediaItem(
                id = "movie",
                url = "direct/original",
                transcodeUrl = "hls/original",
                fallbackTranscodeUrl = "progressive/original",
                title = "电影",
                versions = listOf(alternate),
                playSessionId = "session-original",
            )

        val switched = item.withVersion("alternate")

        assertEquals("direct/alternate", switched.url)
        assertEquals("hls/alternate", switched.transcodeUrl)
        assertEquals("progressive/alternate", switched.fallbackTranscodeUrl)
        assertEquals("session-alternate", switched.playSessionId)
    }

    @Test
    fun switching_physical_version_drops_revision_specific_trickplay_tiles() {
        val original =
            PlayerMediaVersion(
                id = "original",
                label = "4K",
                detail = "",
                url = "direct/original",
                transcodeUrl = "hls/original",
                fallbackTranscodeUrl = "progressive/original",
            )
        val alternate =
            original.copy(
                id = "alternate",
                label = "1080p",
                url = "direct/alternate",
            )
        val item =
            PlayerMediaItem(
                id = "episode",
                url = original.url,
                transcodeUrl = original.transcodeUrl,
                title = "第一集",
                versions = listOf(original, alternate),
                versionId = original.id,
                trickplay =
                    TrickplayStoryboard("tiles/original/{index}.jpg", 320, 180, 10, 10, 10_000L, 100),
            )

        assertNull(item.withVersion(alternate).trickplay)
        assertEquals(item.trickplay, item.withVersion(original).trickplay)
    }

    @Test
    fun manual_quality_caps_active_and_alternate_transcodes_without_touching_identity() {
        val alternate =
            PlayerMediaVersion(
                id = "alternate",
                label = "4K",
                detail = "",
                url = "https://host/direct-alt?api_key=token",
                transcodeUrl = "https://host/hls-alt?PlaySessionId=alt-session",
                fallbackTranscodeUrl = "https://host/mp4-alt?PlaySessionId=alt-session",
                playSessionId = "alt-session",
            )
        val item =
            PlayerMediaItem(
                id = "movie",
                url = "https://host/direct?api_key=token",
                transcodeUrl = "https://host/hls?PlaySessionId=session",
                fallbackTranscodeUrl = "https://host/mp4?PlaySessionId=session",
                title = "电影",
                versions = listOf(alternate),
                playSessionId = "session",
            )

        val capped = item.withPlaybackQuality(PlaybackQuality.Sd)

        assertEquals(item.url, capped.url)
        assertEquals("session", capped.playSessionId)
        listOf(
            capped.transcodeUrl,
            capped.fallbackTranscodeUrl,
            capped.versions.single().transcodeUrl,
            capped.versions.single().fallbackTranscodeUrl,
        ).forEach { url ->
            assertTrue("MaxWidth=854" in url, url)
            assertTrue("VideoBitrate=2000000" in url, url)
        }
        assertTrue("PlaySessionId=session" in capped.transcodeUrl)
        assertTrue("PlaySessionId=alt-session" in capped.versions.single().transcodeUrl)
    }

    @Test
    fun reopening_a_version_rotates_the_session_in_every_url() {
        val original =
            PlayerMediaVersion(
                id = "source-a",
                label = "4K",
                detail = "",
                url = "http://host/Videos/movie/stream?MediaSourceId=source-a&PlaySessionId=old-a",
                transcodeUrl =
                    "http://host/Videos/movie/master.m3u8?PlaySessionId=old-a&MediaSourceId=source-a",
                fallbackTranscodeUrl =
                    "http://host/Videos/movie/stream.mp4?MediaSourceId=source-a&PlaySessionId=old-a",
                playSessionId = "old-a",
            )

        val refreshed = original.withFreshPlaySession()

        assertTrue(refreshed.playSessionId.isNotBlank())
        assertFalse(refreshed.playSessionId == original.playSessionId)
        listOf(refreshed.url, refreshed.transcodeUrl, refreshed.fallbackTranscodeUrl)
            .forEach { url ->
                assertTrue("PlaySessionId=${refreshed.playSessionId}" in url, url)
                assertFalse("PlaySessionId=old-a" in url, url)
                assertTrue("MediaSourceId=source-a" in url, url)
            }
    }

    @Test
    fun a_newly_published_episode_is_reported_as_an_append() {
        val first = PlayerMediaItem("e1", "direct/e1", "hls/e1", "第一集")
        val second = PlayerMediaItem("e2", "direct/e2", "hls/e2", "第二集")
        val third = PlayerMediaItem("e3", "direct/e3", "hls/e3", "第三集")

        assertEquals(
            listOf(third),
            listOf(first, second).appendedBy(listOf(first, second, third)),
        )
    }

    @Test
    fun anything_other_than_an_append_is_not_absorbable() {
        val first = PlayerMediaItem("e1", "direct/e1", "hls/e1", "第一集")
        val second = PlayerMediaItem("e2", "direct/e2", "hls/e2", "第二集")
        val third = PlayerMediaItem("e3", "direct/e3", "hls/e3", "第三集")

        // Unchanged, shorter, reordered, and an entry replaced under the same position all
        // leave the engine's positional playlist wrong; only a tail extension does not.
        assertNull(listOf(first, second).appendedBy(listOf(first, second)))
        assertNull(listOf(first, second).appendedBy(listOf(first)))
        assertNull(listOf(first, second).appendedBy(listOf(second, first, third)))
        assertNull(
            listOf(first, second).appendedBy(
                listOf(first, second.copy(url = "direct/e2-new"), third),
            ),
        )
    }

    @Test
    fun scrub_position_is_clamped_to_media_duration() {
        assertEquals(0L, scrubPositionMs(-0.5f, 100_000L))
        assertEquals(25_000L, scrubPositionMs(0.25f, 100_000L))
        assertEquals(100_000L, scrubPositionMs(1.5f, 100_000L))
        assertEquals(0L, scrubPositionMs(0.5f, -1L))
    }
}
