package com.yfuse.feature.player

import com.yfuse.core2.android.AndroidSerializedPlayerRelease
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerEngineHandoverTest {
    @Test
    fun a_synchronously_released_engine_keeps_the_single_turn_swap() =
        runTest {
            val handover = PlayerEngineHandover(backgroundScope)
            val engine = FakeEngine()
            val host = host(engine, handover)
            val commits = mutableListOf<String>()

            handover.rebuild(host, "switch") { commits += "switch" }

            assertEquals(listOf("switch"), commits)
            // Compose forgets the host in the same frame; releasing earlier would change nothing.
            assertFalse(host.retired)
            assertEquals(0, engine.releaseCalls)
        }

    @Test
    fun an_engine_that_releases_on_another_thread_is_retired_before_the_keys_change() =
        runTest {
            val handover = PlayerEngineHandover(backgroundScope)
            val engine = FakeSerializedEngine()
            val host = host(engine, handover)
            val commits = mutableListOf<String>()

            handover.rebuild(host, "core2_failure") { commits += "first" }
            handover.rebuild(host, "core2_failure") { commits += "second" }
            runCurrent()

            assertTrue(host.retired)
            assertEquals(1, engine.releaseCalls)
            assertTrue(handover.inFlight)
            assertEquals(emptyList(), commits)

            engine.teardown.complete(Unit)
            runCurrent()

            // Both requests land in one turn, in order, so Compose still builds a single engine.
            assertEquals(listOf("first", "second"), commits)
            assertFalse(handover.inFlight)
        }

    @Test
    fun a_teardown_that_never_confirms_does_not_strand_the_viewer() =
        runTest {
            val handover = PlayerEngineHandover(backgroundScope)
            val engine = FakeSerializedEngine(failsToConfirm = true)
            val commits = mutableListOf<String>()

            handover.rebuild(host(engine, handover), "switch") { commits += "switch" }
            runCurrent()

            assertEquals(listOf("switch"), commits)
        }

    @Test
    fun an_engine_forgotten_outside_a_request_still_gates_the_next_rebuild() =
        runTest {
            val handover = PlayerEngineHandover(backgroundScope)
            val forgotten = FakeSerializedEngine()
            host(forgotten, handover).onForgotten()
            val commits = mutableListOf<String>()

            handover.rebuild(host(FakeEngine(), handover), "version_switch") { commits += "version" }
            runCurrent()
            assertEquals(emptyList(), commits)

            forgotten.teardown.complete(Unit)
            runCurrent()
            assertEquals(listOf("version"), commits)
        }

    @Test
    fun an_abandoned_composition_releases_the_engine_it_built_exactly_once() =
        runTest {
            val engine = FakeEngine()
            val host = host(engine, PlayerEngineHandover(backgroundScope))

            host.onAbandoned()
            host.onForgotten()

            assertTrue(host.retired)
            assertEquals(1, engine.releaseCalls)
        }

    private fun host(
        engine: VideoEngine,
        handover: PlayerEngineHandover,
    ) = PlayerEngineHost(engine, handover, disarmNativeCrashMonitor = {})
}

private open class FakeEngine : VideoEngine {
    override val state: StateFlow<PlaybackState> = MutableStateFlow(PlaybackState())
    var releaseCalls = 0

    override fun play() = Unit

    override fun pause() = Unit

    override fun seekTo(positionMs: Long) = Unit

    override fun setSpeed(speed: Float) = Unit

    override fun selectAudioTrack(id: String) = Unit

    override fun selectSubtitleTrack(id: String) = Unit

    override fun selectItem(index: Int) = Unit

    override fun currentPositionMs(): Long = 0L

    override fun retry() = Unit

    override fun release() {
        releaseCalls++
    }
}

private class FakeSerializedEngine(
    private val failsToConfirm: Boolean = false,
) : FakeEngine(),
    AndroidSerializedPlayerRelease {
    val teardown = CompletableDeferred<Unit>()

    override val releaseCompleted: Boolean get() = teardown.isCompleted

    override suspend fun releaseAndJoin() {
        check(!failsToConfirm) { "decoder did not finish releasing" }
        teardown.await()
    }
}
