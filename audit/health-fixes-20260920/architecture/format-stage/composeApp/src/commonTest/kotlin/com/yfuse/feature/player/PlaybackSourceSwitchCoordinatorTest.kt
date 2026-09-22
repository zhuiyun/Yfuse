package com.yfuse.feature.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PlaybackSourceSwitchCoordinatorTest {
    private val original = PlaybackSourceSwitchContext(1, 2, 3, 0, "episode", "server", "session", Any())

    @Test
    fun delayed_cleanup_cannot_replace_a_changed_playback_request() =
        runTest {
            val replacements =
                listOf(
                    original.copy(queueRevision = 2),
                    original.copy(itemIndex = 1),
                    original.copy(itemId = "next-episode"),
                    original.copy(serverId = "other-server"),
                    original.copy(playSessionId = "new-session"),
                    original.copy(engineGeneration = 3),
                    original.copy(runtimeSessionGeneration = 4),
                    original.copy(engineIdentity = Any()),
                )
            for (replacement in replacements) {
                val coordinator = PlaybackSourceSwitchCoordinator()
                var current = original
                val request = coordinator.begin(current)
                val encoderStopped = CompletableDeferred<Boolean>()
                val pending = async { coordinator.prepare(request, { current }) { encoderStopped.await() } }
                runCurrent()
                assertFalse(pending.isCompleted)

                current = replacement
                encoderStopped.complete(true)

                assertEquals(PlaybackSourceSwitchPreparation.Superseded, pending.await(), replacement.toString())
            }
        }

    @Test
    fun queue_edit_or_leave_and_return_invalidates_even_an_identical_media_identity() =
        runTest {
            val coordinator = PlaybackSourceSwitchCoordinator()
            val request = coordinator.begin(original)
            val encoderStopped = CompletableDeferred<Boolean>()
            val pending = async { coordinator.prepare(request, { original }) { encoderStopped.await() } }
            runCurrent()

            coordinator.invalidate()
            encoderStopped.complete(true)

            assertEquals(PlaybackSourceSwitchPreparation.Superseded, pending.await())
        }

    @Test
    fun observed_same_engine_media_round_trip_does_not_revive_an_old_switch() =
        runTest {
            val coordinator = PlaybackSourceSwitchCoordinator()
            val first = Triple(0, 1L, 1L)
            coordinator.observePlayback(original.engineIdentity, first)
            val request = coordinator.begin(original)
            val encoderStopped = CompletableDeferred<Boolean>()
            val pending = async { coordinator.prepare(request, { original }) { encoderStopped.await() } }
            runCurrent()

            coordinator.observePlayback(original.engineIdentity, Triple(1, 2L, 2L))
            coordinator.observePlayback(original.engineIdentity, first)
            encoderStopped.complete(true)

            assertEquals(PlaybackSourceSwitchPreparation.Superseded, pending.await())
        }

    @Test
    fun unchanged_engine_progress_does_not_cancel_a_pending_source_switch() =
        runTest {
            val coordinator = PlaybackSourceSwitchCoordinator()
            val playbackGeneration = Triple(0, 1L, 1L)
            coordinator.observePlayback(original.engineIdentity, playbackGeneration)
            val request = coordinator.begin(original)
            assertEquals(
                PlaybackSourceSwitchPreparation.Ready,
                coordinator.prepare(request, { original }) {
                    coordinator.observePlayback(original.engineIdentity, playbackGeneration)
                    true
                },
            )
        }

    @Test
    fun a_new_version_or_server_switch_supersedes_an_older_switch_of_either_kind() =
        runTest {
            val coordinator = PlaybackSourceSwitchCoordinator()
            val first = coordinator.begin(original)
            val firstEncoderStopped = CompletableDeferred<Boolean>()
            val pending = async { coordinator.prepare(first, { original }) { firstEncoderStopped.await() } }
            runCurrent()

            val second = coordinator.begin(original)
            assertEquals(PlaybackSourceSwitchPreparation.Ready, coordinator.prepare(second, { original }) { true })
            firstEncoderStopped.complete(true)

            assertEquals(PlaybackSourceSwitchPreparation.Superseded, pending.await())
        }

    @Test
    fun unchanged_playback_requires_successful_cleanup_before_the_switch_is_ready() =
        runTest {
            val coordinator = PlaybackSourceSwitchCoordinator()
            val request = coordinator.begin(original)
            val encoderStopped = CompletableDeferred<Boolean>()
            val pending = async { coordinator.prepare(request, { original }) { encoderStopped.await() } }
            runCurrent()
            assertFalse(pending.isCompleted)
            encoderStopped.complete(false)
            assertEquals(PlaybackSourceSwitchPreparation.CleanupRejected, pending.await())
        }

    @Test
    fun cancellation_does_not_publish_a_preparation_result() =
        runTest {
            val coordinator = PlaybackSourceSwitchCoordinator()
            val request = coordinator.begin(original)
            val encoderStopped = CompletableDeferred<Boolean>()
            var result: PlaybackSourceSwitchPreparation? = null
            val pending = async { result = coordinator.prepare(request, { original }) { encoderStopped.await() } }
            runCurrent()
            pending.cancel()
            encoderStopped.complete(true)
            pending.join()
            assertEquals(null, result)
        }
}
