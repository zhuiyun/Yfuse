package com.yfuse.feature.detail

import app.cash.turbine.test
import com.arkivanov.mvikotlin.extensions.coroutines.labels
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import com.russhwolf.settings.MapSettings
import com.yfuse.core.data.PlaybackTrackRequest
import com.yfuse.core.model.SavedServer
import com.yfuse.core.sync.ServerSyncManager
import com.yfuse.feature.json
import com.yfuse.feature.testRegistry
import com.yfuse.feature.testRepo
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.coroutines.CoroutineContext

class DetailStoreTest {
    private lateinit var testPlaybackTrackRequest: PlaybackTrackRequest
    private lateinit var testSyncManager: ServerSyncManager
    @BeforeTest
    fun setUp() {
        val syncRegistry = testRegistry()
        val syncRepo = testRepo { json("{}") }
        testPlaybackTrackRequest = PlaybackTrackRequest()
        testSyncManager = ServerSyncManager(syncRepo, syncRegistry, MapSettings())
        startKoin {
            modules(
                module {
                    single { testPlaybackTrackRequest }
                    single { testSyncManager }
                },
            )
        }
    }

    @AfterTest
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun version_selects_first_and_selected_version_plays() {
        runTest {
            val store = movieStore()
            store.states.first { it.playTarget?.id == "m1" && it.sources.size == 2 }

            store.labels.test {
                store.accept(DetailIntent.SelectVersion("v2"))
                assertEquals("v2", store.state.selectedVersionId)
                expectNoEvents()

                store.accept(DetailIntent.SelectVersion("v2"))
                assertEquals(
                    DetailLabel.Play("one", "m1", 40_000_000L, "v2"),
                    awaitItem(),
                )
                cancelAndConsumeRemainingEvents()
            }
            store.dispose()
        }
    }

    @Test
    fun resource_selection_updates_main_play_target_before_playing() {
        runTest {
            val store = movieStore()
            store.states.first { it.playTarget?.id == "m1" && it.sources.size == 2 }

            store.labels.test(timeout = 10.seconds) {
                store.accept(DetailIntent.SelectSource("two", "m2"))
                store.states.first { !it.selectionLoading && it.playTarget?.id == "m2" }

                assertEquals("two", store.state.playServer?.id)
                assertEquals("two", store.state.server?.id)
                assertEquals("m2", store.state.detail?.id)
                assertEquals("m2", store.state.playSourceDetail?.id)
                assertEquals("w1", store.state.selectedVersionId)
                assertEquals(90_000_000L, store.state.playPositionTicks)
                expectNoEvents()

                store.accept(DetailIntent.SelectSource("two", "m2"))
                assertEquals(
                    DetailLabel.Play("two", "m2", 90_000_000L, "w1"),
                    awaitItem(),
                )
                cancelAndConsumeRemainingEvents()
            }
            store.dispose()
        }
    }

    @Test
    fun quality_sorting_does_not_replace_the_current_server_as_the_default_selection() = runTest {
        val lowQualityCurrent = MOVIE_ONE
            .replace("Height\":2160", "Height\":480")
            .replace("Height\":1080", "Height\":360")
        val store = movieStore(movieOneBody = lowQualityCurrent)
        store.states.first { it.playTarget?.id == "m1" && it.sources.size == 2 }

        assertEquals("two", store.state.sources.bestSourcesFirst().first().serverId)
        assertEquals("one", store.state.selectedSourceServerId)
        assertEquals("m1", store.state.selectedSourceItemId)
        assertEquals("one", store.state.playServer?.id)
        store.dispose()
    }

    @Test
    fun resource_selection_retries_transient_connection_failures_then_succeeds() = runTest {
        val attempts = AtomicInteger()
        val store = movieStore(
            m2DetailFailure = {
                IOException("server closed the connection")
                    .takeIf { attempts.incrementAndGet() < 3 }
            },
        )
        store.states.first { it.playTarget?.id == "m1" && it.sources.size == 2 }

        store.accept(DetailIntent.SelectSource("two", "m2"))
        store.states.first { !it.selectionLoading && it.playTarget?.id == "m2" }

        assertEquals(3, attempts.get())
        assertEquals("two", store.state.playServer?.id)
        assertNull(store.state.actionMessage)
        store.dispose()
    }

    @Test
    fun resource_selection_stops_after_bounded_network_retries() = runTest {
        val attempts = AtomicInteger()
        val store = movieStore(
            m2DetailFailure = {
                attempts.incrementAndGet()
                IOException("server closed the connection")
            },
        )
        store.states.first { it.playTarget?.id == "m1" && it.sources.size == 2 }

        store.accept(DetailIntent.SelectSource("two", "m2"))
        store.states.first { !it.selectionLoading && it.sourceFailure != null }

        assertEquals(3, attempts.get())
        assertEquals("one", store.state.selectedSourceServerId)
        assertEquals("m1", store.state.selectedSourceItemId)
        assertNull(store.state.actionMessage)
        assertEquals(SourceSelectionFailure.NetworkUnavailable, store.state.sourceFailure)
        assertEquals(
            "资源服务器暂时无法连接，已保留当前播放版本",
            store.state.sourceFailure?.toDetailMessage(),
        )
        store.dispose()
    }

    @Test
    fun resource_selection_does_not_retry_a_cloudflare_access_block() = runTest {
        val attempts = AtomicInteger()
        val store = movieStore(
            m2DetailFailure = {
                attempts.incrementAndGet()
                null
            },
            m2CloudflareBlocked = true,
        )
        store.states.first { it.playTarget?.id == "m1" && it.sources.size == 2 }

        store.accept(DetailIntent.SelectSource("two", "m2"))
        store.states.first { !it.selectionLoading && it.sourceFailure != null }

        assertEquals(1, attempts.get())
        assertNull(store.state.actionMessage)
        assertEquals(
            SourceSelectionFailure.AccessDenied(provider = "Cloudflare"),
            store.state.sourceFailure,
        )
        assertEquals(
            "访问被 Cloudflare 拦截，请更换网络或联系服务器管理员",
            store.state.sourceFailure?.toDetailMessage(),
        )
        store.dispose()
    }

    @Test
    fun tapping_a_pending_resource_again_queues_play_after_resolution() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = movieStore(
            beforeM2Detail = {
                started.complete(Unit)
                release.await()
            },
        )
        store.states.first { it.playTarget?.id == "m1" && it.sources.size == 2 }

        store.labels.test {
            store.accept(DetailIntent.SelectSource("two", "m2"))
            started.await()
            store.accept(DetailIntent.SelectSource("two", "m2"))
            if (store.state.actionMessage == null) {
                store.states.first {
                    it.actionMessage == "正在切换资源，完成后将自动播放"
                }
            }
            assertEquals("正在切换资源，完成后将自动播放", store.state.actionMessage)

            release.complete(Unit)
            assertEquals(
                DetailLabel.Play("two", "m2", 90_000_000L, "w1"),
                awaitItem(),
            )
            cancelAndConsumeRemainingEvents()
        }
        store.dispose()
    }

    @Test
    fun newer_failed_resource_switch_keeps_the_last_committed_source() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val firstCancelled = CompletableDeferred<Unit>()
        var thirdAttempts = 0
        val store = movieStore(
            includeThirdSource = true,
            beforeM2Detail = {
                firstStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    firstCancelled.complete(Unit)
                }
            },
            m3DetailFailure = {
                thirdAttempts++
                IOException("third server disconnected")
            },
        )
        store.states.first { it.playTarget?.id == "m1" && it.sources.size == 3 }

        store.accept(DetailIntent.SelectSource("two", "m2"))
        firstStarted.await()
        store.accept(DetailIntent.SelectSource("three", "m3"))
        firstCancelled.await()
        store.states.first { !it.selectionLoading && it.sourceFailure != null }

        assertEquals(3, thirdAttempts)
        assertEquals(SourceSelectionFailure.NetworkUnavailable, store.state.sourceFailure)
        assertEquals("one", store.state.selectedSourceServerId)
        assertEquals("m1", store.state.selectedSourceItemId)
        assertEquals("one", store.state.playServer?.id)
        assertEquals("m1", store.state.playTarget?.id)
        store.dispose()
    }

    @Test
    fun stalled_resource_switch_has_a_wall_clock_deadline() = runTest {
        val started = CompletableDeferred<Unit>()
        val store = movieStore(
            beforeM2Detail = {
                started.complete(Unit)
                awaitCancellation()
            },
            sourceSelectionTimeoutMs = 50L,
        )
        store.states.first { it.playTarget?.id == "m1" && it.sources.size == 2 }

        store.accept(DetailIntent.SelectSource("two", "m2"))
        started.await()
        store.states.first { !it.selectionLoading && it.sourceFailure != null }

        assertEquals(SourceSelectionFailure.Timeout, store.state.sourceFailure)
        assertEquals(
            "资源切换等待超时，请检查网络后再试",
            store.state.sourceFailure?.toDetailMessage(),
        )
        assertEquals("m1", store.state.playTarget?.id)
        store.dispose()
    }

    @Test
    fun changing_version_clears_track_choice_missing_from_new_file() {
        runTest {
            val store = movieStore()
            store.states.first { it.playTarget?.id == "m1" }

            store.accept(DetailIntent.SelectAudioLanguage("英语"))
            store.accept(DetailIntent.SelectVersion("v2"))

            assertEquals("v2", store.state.selectedVersionId)
            assertNull(store.state.preferredAudioLanguage)
            store.dispose()
        }
    }

    @Test
    fun episode_selects_first_and_selected_episode_plays_its_version() {
        runTest {
            val store = seriesStore()
            if (store.state.playTarget?.id != "e1" || store.state.episodes.size != 2) {
                store.states.first { it.playTarget?.id == "e1" && it.episodes.size == 2 }
            }

            store.labels.test {
                store.accept(DetailIntent.SelectEpisode("e2", 20_000_000L))
                if (store.state.selectionLoading || store.state.playTarget?.id != "e2") {
                    store.states.first { !it.selectionLoading && it.playTarget?.id == "e2" }
                }

                assertEquals("ev2", store.state.selectedVersionId)
                assertEquals(20_000_000L, store.state.playPositionTicks)
                expectNoEvents()

                store.accept(DetailIntent.SelectEpisode("e2", 20_000_000L))
                assertEquals(
                    DetailLabel.Play("one", "e2", 20_000_000L, "ev2"),
                    awaitItem(),
                )
                cancelAndConsumeRemainingEvents()
            }
            store.dispose()
        }
    }

    @Test
    fun play_while_episode_is_resolving_waits_for_and_plays_the_new_episode() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = seriesStore(
            beforeEpisodeTwoDetail = {
                started.complete(Unit)
                release.await()
            },
        )
        store.states.first { it.playTarget?.id == "e1" && it.episodes.size == 2 }

        store.labels.test {
            store.accept(DetailIntent.SelectEpisode("e2", 20_000_000L))
            started.await()
            store.accept(DetailIntent.Play)
            expectNoEvents()

            release.complete(Unit)
            assertEquals(
                DetailLabel.Play("one", "e2", 20_000_000L, "ev2"),
                awaitItem(),
            )
            assertEquals("e2", store.state.playTarget?.id)
            cancelAndConsumeRemainingEvents()
        }
        store.dispose()
    }

    @Test
    fun favorite_state_survives_switching_to_another_episode() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = seriesStore(
            beforeEpisodeTwoDetail = {
                started.complete(Unit)
                release.await()
            },
        )
        store.states.first { it.playTarget?.id == "e1" && it.episodes.size == 2 }

        store.accept(DetailIntent.SelectEpisode("e2", 20_000_000L))
        started.await()
        store.accept(DetailIntent.ToggleFavorite)
        store.states.first { it.detail?.isFavorite == true }
        release.complete(Unit)
        store.states.first { !it.selectionLoading && it.playTarget?.id == "e2" }

        assertTrue(store.state.detail?.isFavorite == true)
        assertTrue(store.state.playSourceDetail?.isFavorite == true)
        store.dispose()
    }

    @Test
    fun player_selection_syncs_episode_and_version_together() {
        runTest {
            val store = seriesStore()
            if (store.state.playTarget?.id != "e1" || store.state.episodes.size != 2) {
                store.states.first { it.playTarget?.id == "e1" && it.episodes.size == 2 }
            }

            store.accept(
                DetailIntent.SyncPlaybackSelection(
                    serverId = "one",
                    itemId = "e2",
                    versionId = "ev2b",
                ),
            )
            if (
                store.state.selectionLoading ||
                store.state.playTarget?.id != "e2" ||
                store.state.selectedVersionId != "ev2b"
            ) {
                store.states.first {
                    !it.selectionLoading &&
                        it.playTarget?.id == "e2" &&
                        it.selectedVersionId == "ev2b"
                }
            }

            assertEquals("e2", store.state.selectedEpisodeId)
            assertEquals("ev2b", store.state.selectedVersionId)
            store.dispose()
        }
    }

    @Test
    fun cross_server_player_selection_commits_its_exact_episode_and_version() = runTest {
        val store = seriesStore(
            includeSecondSource = true,
            secondEpisodesBody = """{"Items":[$ALT_EPISODE_ONE,$ALT_EPISODE_NINE]}""",
        )
        store.states.first {
            it.playTarget?.id == "e1" && it.episodes.size == 2 && it.sources.size == 2
        }

        store.accept(
            DetailIntent.SyncPlaybackSelection(
                serverId = "two",
                itemId = "ae9",
                versionId = "aev9",
            ),
        )
        store.states.first {
            !it.selectionLoading &&
                it.playServer?.id == "two" &&
                it.playTarget?.id == "ae9"
        }

        assertEquals("two", store.state.server?.id)
        assertEquals("s2", store.state.detail?.id)
        assertEquals("s2", store.state.playSourceDetail?.id)
        assertEquals("ae9", store.state.selectedEpisodeId)
        assertEquals("aev9", store.state.selectedVersionId)
        assertEquals(listOf("ae1", "ae9"), store.state.episodes.map { it.id })
        store.dispose()
    }

    @Test
    fun server_switch_during_episode_resolution_keeps_the_newly_selected_coordinate() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = seriesStore(
            includeSecondSource = true,
            beforeEpisodeTwoDetail = {
                started.complete(Unit)
                release.await()
            },
            secondEpisodesBody = """{"Items":[$ALT_EPISODE_ONE,$ALT_EPISODE_TWO]}""",
        )
        store.states.first {
            it.playTarget?.id == "e1" && it.episodes.size == 2 && it.sources.size == 2
        }

        store.accept(DetailIntent.SelectEpisode("e2", 20_000_000L))
        started.await()
        store.accept(DetailIntent.SelectSource("two", "s2"))
        store.states.first { !it.selectionLoading && it.playTarget?.id == "ae2" }
        release.complete(Unit)

        assertEquals("two", store.state.playServer?.id)
        assertEquals("ae2", store.state.selectedEpisodeId)
        assertEquals(1, store.state.playTarget?.seasonNumber)
        assertEquals(2, store.state.playTarget?.episodeNumber)
        store.dispose()
    }

    @Test
    fun stale_episode_response_cannot_commit_while_server_switch_is_resolving() = runTest {
        val oldEpisodeStarted = CompletableDeferred<Unit>()
        val releaseOldEpisode = CompletableDeferred<Unit>()
        val newServerStarted = CompletableDeferred<Unit>()
        val releaseNewServer = CompletableDeferred<Unit>()
        var switchingServer = false
        val store = seriesStore(
            includeSecondSource = true,
            beforeEpisodeTwoDetail = {
                oldEpisodeStarted.complete(Unit)
                releaseOldEpisode.await()
            },
            beforeSecondEpisodes = {
                if (switchingServer) {
                    newServerStarted.complete(Unit)
                    releaseNewServer.await()
                }
            },
            secondEpisodesBody = """{"Items":[$ALT_EPISODE_ONE,$ALT_EPISODE_TWO]}""",
        )
        store.states.first {
            it.playTarget?.id == "e1" && it.episodes.size == 2 && it.sources.size == 2
        }

        store.accept(DetailIntent.SelectEpisode("e2", 20_000_000L))
        oldEpisodeStarted.await()
        switchingServer = true
        store.accept(DetailIntent.SelectSource("two", "s2"))
        newServerStarted.await()

        // The old request finishes first. It must not make the page look ready or replace
        // the committed target while the explicitly chosen server is still resolving.
        releaseOldEpisode.complete(Unit)
        runCurrent()
        assertTrue(store.state.selectionLoading)
        assertEquals("one", store.state.playServer?.id)
        assertEquals("e1", store.state.playTarget?.id)

        releaseNewServer.complete(Unit)
        store.states.first { !it.selectionLoading && it.playTarget?.id == "ae2" }
        assertEquals("two", store.state.playServer?.id)
        assertEquals("ae2", store.state.selectedEpisodeId)
        store.dispose()
    }

    @Test
    fun episode_tap_during_server_switch_restarts_resolution_and_plays_new_coordinate() = runTest {
        val firstServerResolutionStarted = CompletableDeferred<Unit>()
        val releaseFirstResolution = CompletableDeferred<Unit>()
        var switchingServer = false
        var serverResolutionCalls = 0
        val store = seriesStore(
            includeSecondSource = true,
            beforeSecondEpisodes = {
                if (switchingServer) {
                    serverResolutionCalls++
                    if (serverResolutionCalls == 1) {
                        firstServerResolutionStarted.complete(Unit)
                        releaseFirstResolution.await()
                    }
                }
            },
            secondEpisodesBody = """{"Items":[$ALT_EPISODE_ONE,$ALT_EPISODE_TWO]}""",
        )
        store.states.first {
            it.playTarget?.id == "e1" && it.episodes.size == 2 && it.sources.size == 2
        }

        store.labels.test {
            switchingServer = true
            store.accept(DetailIntent.SelectSource("two", "s2"))
            firstServerResolutionStarted.await()

            // The source request started for episode 1. Choosing episode 2 must restart it
            // with the new coordinate; the following play tap waits for that exact target.
            store.accept(DetailIntent.SelectEpisode("e2", 20_000_000L))
            store.accept(DetailIntent.Play)
            releaseFirstResolution.complete(Unit)

            assertEquals(
                DetailLabel.Play("two", "ae2", 0L, "aev2"),
                awaitItem(),
            )
            assertTrue(serverResolutionCalls >= 2)
            assertEquals("two", store.state.playServer?.id)
            assertEquals("ae2", store.state.selectedEpisodeId)
            cancelAndConsumeRemainingEvents()
        }
        store.dispose()
    }

    @Test
    fun cross_server_episode_resolution_retries_and_keeps_the_same_coordinate() = runTest {
        var selectingSecond = false
        var resolvedSecondEpisode = false
        var selectionEpisodeAttempts = 0
        var secondNextUpCalls = 0
        val store = seriesStore(
            includeSecondSource = true,
            beforeSecondEpisodes = {
                if (selectingSecond && !resolvedSecondEpisode) {
                    selectionEpisodeAttempts++
                    if (selectionEpisodeAttempts == 1) {
                        throw IOException("temporary episode catalog failure")
                    }
                }
            },
            onSecondEpisodeDetail = { resolvedSecondEpisode = true },
            onSecondNextUp = { secondNextUpCalls++ },
        )
        store.states.first {
            it.playTarget?.id == "e1" && it.episodes.size == 2 && it.sources.size == 2
        }

        selectingSecond = true
        store.accept(DetailIntent.SelectSource("two", "s2"))
        store.states.first { !it.selectionLoading && it.playTarget?.id == "ae1" }

        assertEquals(2, selectionEpisodeAttempts)
        assertEquals(0, secondNextUpCalls)
        assertEquals(1, store.state.playTarget?.seasonNumber)
        assertEquals(1, store.state.playTarget?.episodeNumber)
        assertEquals("two", store.state.playServer?.id)
        store.dispose()
    }

    @Test
    fun source_without_the_current_episode_does_not_fall_back_to_next_up() = runTest {
        var secondNextUpCalls = 0
        val store = seriesStore(
            includeSecondSource = true,
            secondEpisodesBody = """{"Items":[$ALT_EPISODE_NINE]}""",
            onSecondNextUp = { secondNextUpCalls++ },
        )
        store.states.first {
            it.playTarget?.id == "e1" && it.episodes.size == 2 && it.sources.size == 2
        }

        store.accept(DetailIntent.SelectSource("two", "s2"))
        store.states.first { !it.selectionLoading && it.sourceFailure != null }

        assertEquals(0, secondNextUpCalls)
        assertEquals(
            SourceSelectionFailure.EpisodeMissing(season = 1, episode = 1),
            store.state.sourceFailure,
        )
        assertEquals("one", store.state.selectedSourceServerId)
        assertEquals("s1", store.state.selectedSourceItemId)
        assertEquals("e1", store.state.playTarget?.id)
        assertEquals(
            "该资源没有第 1 季第 1 集，请选择其他播放版本",
            store.state.sourceFailure?.toDetailMessage(),
        )
        store.dispose()
    }

    @Test
    fun season_episode_load_retries_transient_failures() = runTest {
        val attempts = AtomicInteger()
        val store = seriesStore(
            seasonTwoEpisodesFailure = {
                IOException("temporary catalog failure")
                    .takeIf { attempts.incrementAndGet() < 3 }
            },
            mainContext = UnconfinedTestDispatcher(testScheduler),
        )
        store.states.first { it.playTarget?.id == "e1" && it.episodes.size == 2 }

        store.accept(DetailIntent.SelectSeason("season2"))
        store.states.first {
            !it.episodesLoading && !it.selectionLoading && it.playTarget?.id == "e3"
        }

        assertEquals(3, attempts.get())
        assertEquals("season2", store.state.selectedSeasonId)
        assertEquals(listOf("e3"), store.state.episodes.map { it.id })
        store.dispose()
    }

    @Test
    fun exhausted_season_load_keeps_the_previous_episode_directory() = runTest {
        val attempts = AtomicInteger()
        val store = seriesStore(
            seasonTwoEpisodesFailure = {
                attempts.incrementAndGet()
                IOException("catalog unavailable")
            },
            mainContext = UnconfinedTestDispatcher(testScheduler),
        )
        store.states.first { it.playTarget?.id == "e1" && it.episodes.size == 2 }

        store.accept(DetailIntent.SelectSeason("season2"))
        store.states.first { !it.episodesLoading && it.actionMessage != null }

        assertEquals(3, attempts.get())
        assertEquals("season1", store.state.selectedSeasonId)
        assertEquals(listOf("e1", "e2"), store.state.episodes.map { it.id })
        assertTrue(store.state.actionMessage?.isNotBlank() == true)
        store.dispose()
    }

    private fun movieStore(
        m2DetailFailure: (() -> Throwable?)? = null,
        m2CloudflareBlocked: Boolean = false,
        beforeM2Detail: suspend () -> Unit = {},
        includeThirdSource: Boolean = false,
        m3DetailFailure: (() -> Throwable?)? = null,
        sourceSelectionTimeoutMs: Long = 45_000L,
        movieOneBody: String = MOVIE_ONE,
    ): com.arkivanov.mvikotlin.core.store.Store<
        DetailIntent,
        DetailState,
        DetailLabel,
    > {
        val registry = testRegistry().apply {
            addOrUpdate(SavedServer("one", "http://one", "主库", "u", "user", "tok1"))
            addOrUpdate(SavedServer("two", "http://two", "备库", "u", "user", "tok2"))
            if (includeThirdSource) {
                addOrUpdate(
                    SavedServer("three", "http://three", "第三库", "u", "user", "tok3"),
                )
            }
        }
        val repo = testRepo { request ->
            val host = request.url.host
            val path = request.url.encodedPath
            when {
                path.endsWith("/Items/m1") -> json(movieOneBody)
                path.endsWith("/Items/m2") -> {
                    beforeM2Detail()
                    m2DetailFailure?.invoke()?.let { throw it }
                    if (m2CloudflareBlocked) {
                        respond(
                            content = "<!doctype html><title>Cloudflare</title>" +
                                "<p>Sorry, you have been blocked</p>",
                            status = HttpStatusCode.Forbidden,
                            headers = headersOf(HttpHeaders.ContentType, "text/html"),
                        )
                    } else {
                        json(MOVIE_TWO)
                    }
                }
                path.endsWith("/Items/m3") -> {
                    m3DetailFailure?.invoke()?.let { throw it }
                    json(MOVIE_THREE)
                }
                path.endsWith("/Similar") -> json("""{"Items":[]}""")
                path.endsWith("/Items") -> json(
                    if (host == "one") {
                        """{"Items":[$movieOneBody]}"""
                    } else if (host == "two") {
                        """{"Items":[$MOVIE_TWO]}"""
                    } else {
                        """{"Items":[$MOVIE_THREE]}"""
                    },
                )
                else -> json("{}")
            }
        }
        return DetailStoreFactory(
            DefaultStoreFactory(),
            repo,
            registry,
            itemId = "m1",
            serverId = "one",
            sourceSelectionTimeoutMs = sourceSelectionTimeoutMs,
            mainContext = Dispatchers.Unconfined,
            playbackTrackRequest = testPlaybackTrackRequest,
            syncManager = testSyncManager,
        ).create()
    }

    private fun seriesStore(
        includeSecondSource: Boolean = false,
        beforeEpisodeTwoDetail: suspend () -> Unit = {},
        beforeSecondEpisodes: suspend () -> Unit = {},
        secondEpisodesBody: String = """{"Items":[$ALT_EPISODE_ONE]}""",
        onSecondEpisodeDetail: () -> Unit = {},
        onSecondNextUp: () -> Unit = {},
        seasonTwoEpisodesFailure: (() -> Throwable?)? = null,
        mainContext: CoroutineContext = Dispatchers.Unconfined,
    ): com.arkivanov.mvikotlin.core.store.Store<
        DetailIntent,
        DetailState,
        DetailLabel,
    > {
        val registry = testRegistry().apply {
            addOrUpdate(SavedServer("one", "http://one", "主库", "u", "user", "tok1"))
            if (includeSecondSource) {
                addOrUpdate(SavedServer("two", "http://two", "备库", "u", "user", "tok2"))
            }
        }
        val repo = testRepo { request ->
            val host = request.url.host
            val path = request.url.encodedPath
            when {
                path.endsWith("/Items/s1") -> json(SERIES)
                path.endsWith("/Items/s2") -> json(SERIES_TWO)
                path.endsWith("/Items/e1") -> json(EPISODE_ONE)
                path.endsWith("/Items/e2") -> {
                    beforeEpisodeTwoDetail()
                    json(EPISODE_TWO)
                }
                path.endsWith("/Items/e3") -> json(EPISODE_THREE)
                path.endsWith("/Items/ae1") -> {
                    onSecondEpisodeDetail()
                    json(ALT_EPISODE_ONE)
                }
                path.endsWith("/Items/ae2") -> json(ALT_EPISODE_TWO)
                path.endsWith("/Items/ae9") -> json(ALT_EPISODE_NINE)
                path.endsWith("/Shows/NextUp") -> {
                    if (host == "two") {
                        onSecondNextUp()
                        json("""{"Items":[$ALT_EPISODE_NINE]}""")
                    } else {
                        json(
                            """{"Items":[{"Id":"e1","Name":"第一集","Type":"Episode",""" +
                                """"UserData":{"PlaybackPositionTicks":10000000}}]}""",
                        )
                    }
                }
                path.endsWith("/Shows/s1/Seasons") -> json(
                    if (seasonTwoEpisodesFailure == null) {
                        """{"Items":[{"Id":"season1","Name":"第 1 季","IndexNumber":1}]}"""
                    } else {
                        """{"Items":[{"Id":"season1","Name":"第 1 季","IndexNumber":1},""" +
                            """{"Id":"season2","Name":"第 2 季","IndexNumber":2}]}"""
                    },
                )
                path.endsWith("/Shows/s2/Seasons") -> json(
                    """{"Items":[{"Id":"aseason1","Name":"第 1 季","IndexNumber":1}]}""",
                )
                path.endsWith("/Shows/s1/Episodes") -> {
                    if (request.url.parameters["SeasonId"] == "season2") {
                        seasonTwoEpisodesFailure?.invoke()?.let { throw it }
                        json("""{"Items":[$EPISODE_THREE]}""")
                    } else {
                        json("""{"Items":[$EPISODE_ONE,$EPISODE_TWO]}""")
                    }
                }
                path.endsWith("/Shows/s2/Episodes") -> {
                    beforeSecondEpisodes()
                    json(secondEpisodesBody)
                }
                path.endsWith("/Similar") -> json("""{"Items":[]}""")
                path.endsWith("/Items") -> json(
                    if (host == "two") {
                        """{"Items":[$SERIES_TWO]}"""
                    } else {
                        """{"Items":[$SERIES]}"""
                    },
                )
                else -> json("{}")
            }
        }
        return DetailStoreFactory(
            DefaultStoreFactory(),
            repo,
            registry,
            itemId = "s1",
            serverId = "one",
            mainContext = mainContext,
            playbackTrackRequest = testPlaybackTrackRequest,
            syncManager = testSyncManager,
        ).create()
    }

    private companion object {
        const val MOVIE_ONE = """{"Id":"m1","Name":"电影","Type":"Movie",""" +
            """"UserData":{"PlaybackPositionTicks":40000000},"MediaSources":[""" +
            """{"Id":"v1","Name":"原盘","MediaStreams":[""" +
            """{"Type":"Video","Height":2160},{"Type":"Audio","Language":"eng"}]},""" +
            """{"Id":"v2","Name":"压制","MediaStreams":[""" +
            """{"Type":"Video","Height":1080},{"Type":"Audio","Language":"chi"}]}]}"""

        const val MOVIE_TWO = """{"Id":"m2","Name":"电影","Type":"Movie",""" +
            """"UserData":{"PlaybackPositionTicks":90000000},"MediaSources":[""" +
            """{"Id":"w1","Name":"备库版本","MediaStreams":[""" +
            """{"Type":"Video","Height":720},{"Type":"Audio","Language":"chi"}]}]}"""

        const val MOVIE_THREE = """{"Id":"m3","Name":"电影","Type":"Movie",""" +
            """"UserData":{"PlaybackPositionTicks":0},"MediaSources":[""" +
            """{"Id":"x1","Name":"第三库版本","MediaStreams":[""" +
            """{"Type":"Video","Height":1080},{"Type":"Audio","Language":"chi"}]}]}"""

        const val SERIES =
            """{"Id":"s1","Name":"剧集","Type":"Series","ProviderIds":{"Tmdb":"1"}}"""

        const val SERIES_TWO =
            """{"Id":"s2","Name":"剧集","Type":"Series","ProviderIds":{"Tmdb":"1"}}"""

        const val EPISODE_ONE = """{"Id":"e1","Name":"第一集","Type":"Episode",""" +
            """"SeriesId":"s1","SeriesName":"剧集","ParentIndexNumber":1,""" +
            """"IndexNumber":1,"SeasonId":"season1",""" +
            """"UserData":{"PlaybackPositionTicks":10000000},"MediaSources":[""" +
            """{"Id":"ev1","Name":"第一集版本","MediaStreams":[""" +
            """{"Type":"Video","Height":1080}]}]}"""

        const val EPISODE_TWO = """{"Id":"e2","Name":"第二集","Type":"Episode",""" +
            """"SeriesId":"s1","SeriesName":"剧集","ParentIndexNumber":1,""" +
            """"IndexNumber":2,"SeasonId":"season1",""" +
            """"UserData":{"PlaybackPositionTicks":20000000},"MediaSources":[""" +
            """{"Id":"ev2","Name":"第二集版本","MediaStreams":[""" +
            """{"Type":"Video","Height":2160}]},{"Id":"ev2b","Name":"第二集压制版",""" +
            """"MediaStreams":[{"Type":"Video","Height":1080}]}]}"""

        const val EPISODE_THREE = """{"Id":"e3","Name":"第三集","Type":"Episode",""" +
            """"SeriesId":"s1","SeriesName":"剧集","ParentIndexNumber":2,""" +
            """"IndexNumber":1,"SeasonId":"season2","MediaSources":[""" +
            """{"Id":"ev3","Name":"第三集版本","MediaStreams":[""" +
            """{"Type":"Video","Height":1080}]}]}"""

        const val ALT_EPISODE_ONE = """{"Id":"ae1","Name":"第一集","Type":"Episode",""" +
            """"SeriesId":"s2","SeriesName":"剧集","ParentIndexNumber":1,""" +
            """"IndexNumber":1,"SeasonId":"aseason1",""" +
            """"UserData":{"PlaybackPositionTicks":30000000},"MediaSources":[""" +
            """{"Id":"aev1","Name":"备库第一集","MediaStreams":[""" +
            """{"Type":"Video","Height":1080}]}]}"""

        const val ALT_EPISODE_NINE = """{"Id":"ae9","Name":"第九集","Type":"Episode",""" +
            """"SeriesId":"s2","SeriesName":"剧集","ParentIndexNumber":1,""" +
            """"IndexNumber":9,"SeasonId":"aseason1","MediaSources":[""" +
            """{"Id":"aev9","Name":"备库第九集","MediaStreams":[""" +
            """{"Type":"Video","Height":720}]}]}"""

        const val ALT_EPISODE_TWO = """{"Id":"ae2","Name":"第二集","Type":"Episode",""" +
            """"SeriesId":"s2","SeriesName":"剧集","ParentIndexNumber":1,""" +
            """"IndexNumber":2,"SeasonId":"aseason1","MediaSources":[""" +
            """{"Id":"aev2","Name":"备库第二集","MediaStreams":[""" +
            """{"Type":"Video","Height":1080}]}]}"""
    }
}
