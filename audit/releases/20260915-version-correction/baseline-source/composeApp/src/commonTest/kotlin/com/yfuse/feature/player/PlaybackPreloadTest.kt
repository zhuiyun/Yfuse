package com.yfuse.feature.player

import com.arkivanov.mvikotlin.core.rx.Disposable
import com.arkivanov.mvikotlin.core.rx.Observer
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PlaybackPreloadTest {
    @Test
    fun prepared_queue_can_be_claimed_by_only_one_player_launch() {
        val key =
            PlaybackPreloadKey(
                serverId = "server",
                itemId = "episode",
                startPositionTicks = 42L,
                mediaSourceId = "source",
            )
        val prepared = FakePreparedStore()
        PreparedPlaybackRegistry.register(key, prepared)

        assertSame(prepared, PreparedPlaybackRegistry.claim(key))
        assertNull(PreparedPlaybackRegistry.claim(key))
        assertFalse(PreparedPlaybackRegistry.owns(key, prepared))
        // Ownership moved to the first player; detail cleanup must not dispose it underneath
        // that launch after the registry entry has been consumed.
        assertFalse(PreparedPlaybackRegistry.removeIfOwned(key, prepared))
    }

    @Test
    fun next_episode_is_preloaded_only_inside_the_final_90_seconds() =
        runTest {
            val preloader = RecordingPreloader()
            val reporter =
                PlaybackProgressReporter(
                    items =
                        listOf(
                            PlayerMediaItem("e1", "direct-1", "hls-1", "第一集"),
                            PlayerMediaItem("e2", "direct-2", "hls-2", "第二集"),
                        ),
                    sink = NoopSink,
                    // The reporter owns a long-lived command actor. Run it as background work so
                    // runTest cancels the actor after the synchronous preload assertions complete.
                    scope = backgroundScope,
                    sourcePreloader = preloader,
                )

            reporter.update(
                PlaybackState(
                    playing = true,
                    currentIndex = 0,
                    itemCount = 2,
                    positionMs = 480_000L,
                    durationMs = 600_000L,
                ),
            )
            assertTrue(preloader.urls.isEmpty())

            reporter.update(
                PlaybackState(
                    playing = true,
                    currentIndex = 0,
                    itemCount = 2,
                    positionMs = 511_000L,
                    durationMs = 600_000L,
                ),
            )
            assertEquals(listOf("direct-2"), preloader.urls)

            // The 500 ms player ticker may call update many times in the window. One source should
            // still be warmed only once.
            reporter.update(
                PlaybackState(
                    playing = true,
                    currentIndex = 0,
                    itemCount = 2,
                    positionMs = 540_000L,
                    durationMs = 600_000L,
                ),
            )
            assertEquals(listOf("direct-2"), preloader.urls)
        }

    @Test
    fun final_queue_item_has_nothing_to_preload() =
        runTest {
            val preloader = RecordingPreloader()
            val reporter =
                PlaybackProgressReporter(
                    items = listOf(PlayerMediaItem("e1", "direct-1", "hls-1", "第一集")),
                    sink = NoopSink,
                    scope = backgroundScope,
                    sourcePreloader = preloader,
                )

            reporter.update(
                PlaybackState(
                    playing = true,
                    currentIndex = 0,
                    itemCount = 1,
                    positionMs = 550_000L,
                    durationMs = 600_000L,
                ),
            )

            assertTrue(preloader.urls.isEmpty())
        }

    @Test
    fun a_transcoded_disc_is_never_preloaded() =
        runTest {
            val preloader = RecordingPreloader()
            val discVersion =
                PlayerMediaVersion(
                    id = "disc",
                    label = "ISO",
                    detail = "ISO",
                    url = "hls-disc",
                    transcodeUrl = "hls-disc",
                    fallbackTranscodeUrl = "mp4-disc",
                    discSource = true,
                    playMethod = com.yfuse.core.model.PlaybackMethod.Transcode,
                )
            val reporter =
                PlaybackProgressReporter(
                    items =
                        listOf(
                            PlayerMediaItem("e1", "direct-1", "hls-1", "第一集"),
                            PlayerMediaItem(
                                id = "disc-item",
                                url = discVersion.url,
                                transcodeUrl = discVersion.transcodeUrl,
                                title = "原盘",
                                fallbackTranscodeUrl = discVersion.fallbackTranscodeUrl,
                                versions = listOf(discVersion),
                                versionId = discVersion.id,
                                playMethod = com.yfuse.core.model.PlaybackMethod.Transcode,
                            ),
                        ),
                    sink = NoopSink,
                    scope = backgroundScope,
                    sourcePreloader = preloader,
                )

            reporter.update(
                PlaybackState(
                    playing = true,
                    currentIndex = 0,
                    positionMs = 20_000L,
                    durationMs = 100_000L,
                ),
            )

            assertTrue(preloader.urls.isEmpty())
        }

    private class RecordingPreloader : PlaybackSourcePreloader {
        val urls = mutableListOf<String>()

        override fun preload(item: PlayerMediaItem): PlaybackSourcePreload {
            urls += item.url
            return noOpPlaybackSourcePreload()
        }
    }

    private class FakePreparedStore : PreparedPlayerStore {
        override val state = PlayerState()
        override var isDisposed: Boolean = false
            private set

        override fun states(observer: Observer<PlayerState>): Disposable {
            observer.onNext(state)
            return TestDisposable()
        }

        override fun labels(observer: Observer<Nothing>): Disposable = TestDisposable()

        override fun accept(intent: PlayerIntent) = Unit

        override fun init() = Unit

        override fun dispose() {
            isDisposed = true
        }
    }

    private class TestDisposable : Disposable {
        override var isDisposed: Boolean = false
            private set

        override fun dispose() {
            isDisposed = true
        }
    }

    private object NoopSink : PlaybackEventSink {
        override suspend fun started(
            itemId: String,
            sessionId: String,
            positionTicks: Long,
            isPaused: Boolean,
        ) = Unit

        override suspend fun progress(
            itemId: String,
            sessionId: String,
            positionTicks: Long,
            isPaused: Boolean,
        ) = Unit

        override suspend fun stopped(
            itemId: String,
            sessionId: String,
            positionTicks: Long,
            isPaused: Boolean,
        ) = Unit
    }
}
