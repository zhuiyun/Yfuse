package com.yfuse.feature.player

import androidx.media3.exoplayer.ExoPlayer
import com.yfuse.core.model.DecoderMode
import com.yfuse.core2.render.YRenderedFrameRateSampler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import sun.misc.Unsafe
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Runs production scheduleRetry/pause. Only Android player construction/output is replaced. */
@OptIn(ExperimentalCoroutinesApi::class)
class ExoRecoveryIntentTest {
    @Test
    fun retry_without_user_pause_prepares_and_resumes() =
        runTest {
            val fixture = fixture(this)
            fixture.schedule()
            advanceUntilIdle()
            assertEquals(1, fixture.prepares)
            assertTrue(fixture.playWhenReady)
        }

    @Test
    fun user_pause_during_transient_backoff_must_not_be_overridden() =
        runTest {
            val fixture = fixture(this)
            fixture.schedule()
            runCurrent()
            fixture.engine.pause()
            assertFalse(fixture.playWhenReady)
            advanceUntilIdle()
            assertEquals(1, fixture.prepares)
            assertFalse(fixture.playWhenReady, "Automatic recovery must retain the user's explicit pause")
        }

    @Test
    fun a_new_play_request_during_backoff_is_preserved() =
        runTest {
            val fixture = fixture(this)
            fixture.schedule()
            runCurrent()
            fixture.engine.pause()
            fixture.engine.play()
            advanceUntilIdle()
            assertEquals(1, fixture.prepares)
            assertTrue(fixture.playWhenReady)
        }

    @Test
    fun manual_retry_cancels_old_cleanup_and_scheduled_retry_but_still_plays() =
        runTest {
            val fixture = fixture(this)
            val old = fixture.pending.start(0)
            val cleanup = backgroundScope.launch { awaitCancellation() }
            fixture.installCleanup(cleanup)
            fixture.schedule()
            runCurrent()
            fixture.engine.pause()
            fixture.engine.retry()
            advanceUntilIdle()
            assertTrue(cleanup.isCancelled)
            assertFalse(fixture.pending.settle(old, stillCurrent = true))
            assertEquals(1, fixture.prepares, "The old delayed retry must not prepare a second time")
            assertTrue(fixture.playWhenReady)
        }

    @Test
    fun selecting_the_same_index_cancels_the_previous_recovery_generation() =
        runTest {
            val fixture = fixture(this)
            val old = fixture.pending.start(0)
            val cleanup = backgroundScope.launch { awaitCancellation() }
            fixture.installCleanup(cleanup)
            fixture.schedule()
            runCurrent()
            fixture.engine.selectItem(0)
            advanceUntilIdle()
            assertTrue(cleanup.isCancelled)
            assertFalse(fixture.pending.settle(old, stillCurrent = true))
            assertEquals(0, fixture.prepares)
            assertTrue(fixture.playWhenReady)
        }

    @Test
    fun internal_audio_fallback_pause_preserves_intent_until_an_explicit_user_pause() =
        runTest {
            val fixture = fixture(this)
            val method = ExoVideoEngine::class.java.getDeclaredMethod("pauseForRecovery").apply { isAccessible = true }
            method.invoke(fixture.engine)
            assertFalse(fixture.playWhenReady)
            assertTrue(fixture.engine.playbackRequested)
            fixture.engine.pause()
            assertFalse(fixture.engine.playbackRequested)
        }

    private fun fixture(scope: kotlinx.coroutines.CoroutineScope): Fixture {
        val unsafe =
            Unsafe::class.java
                .getDeclaredField("theUnsafe")
                .apply { isAccessible = true }
                .get(null) as Unsafe
        // Android's host-test stub leaves Build strings null. Media3 initializes constants
        // from these when its ExoPlayer interface is loaded; supply inert host metadata.
        val stubBuildFields =
            android.os.Build::class.java.fields
                .filter {
                    it.type == String::class.java &&
                        java.lang.reflect.Modifier
                            .isStatic(it.modifiers)
                }.filter { it.get(null) == null }
        try {
            stubBuildFields.forEach {
                unsafe.putObject(unsafe.staticFieldBase(it), unsafe.staticFieldOffset(it), "test-host")
            }
            Class.forName(ExoPlayer::class.java.name, true, ExoPlayer::class.java.classLoader)
        } finally {
            stubBuildFields.forEach {
                unsafe.putObject(unsafe.staticFieldBase(it), unsafe.staticFieldOffset(it), null)
            }
        }
        val engine = unsafe.allocateInstance(ExoVideoEngine::class.java) as ExoVideoEngine
        val fixture = Fixture(engine)
        val player =
            Proxy.newProxyInstance(
                ExoPlayer::class.java.classLoader,
                arrayOf(ExoPlayer::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "getCurrentMediaItemIndex" -> 0
                    "getPlayWhenReady" -> fixture.playWhenReady
                    "getPlaybackState" -> androidx.media3.common.Player.STATE_READY
                    "setPlayWhenReady" -> {
                        fixture.playWhenReady = args!![0] as Boolean
                        null
                    }
                    "pause" -> {
                        fixture.playWhenReady = false
                        null
                    }
                    "play" -> {
                        fixture.playWhenReady = true
                        null
                    }
                    "seekToDefaultPosition" -> null
                    "prepare" -> {
                        fixture.prepares++
                        null
                    }
                    "toString" -> "AuditExoPlayer"
                    else -> error("Unexpected native boundary call: ${method.name}")
                }
            } as ExoPlayer

        fun field(
            name: String,
            value: Any,
        ) {
            ExoVideoEngine::class.java
                .getDeclaredField(name)
                .apply { isAccessible = true }
                .set(engine, value)
        }
        field("player", player)
        field("scope", scope)
        field("items", mutableListOf(PlayerMediaItem("audit", "https://example.invalid/video.mp4", "", "Audit")))
        field("_state", MutableStateFlow(PlaybackState()))
        field("decoderMode", DecoderMode.Auto)
        field("retryCounts", mutableMapOf<Triple<String, String, String>, Int>())
        field("failureHistory", mutableMapOf<Int, MutableList<String>>())
        field("progressiveTranscodeIndices", mutableSetOf<Int>())
        field("transcodedIndices", mutableSetOf<Int>())
        field("pendingProgressiveSwitches", fixture.pending)
        field("audioProgress", PlaybackAudioProgress())
        field("renderedFrameRateSampler", YRenderedFrameRateSampler())
        return fixture
    }

    private class Fixture(
        val engine: ExoVideoEngine,
    ) {
        val pending = PendingProgressiveSwitches()
        var playWhenReady = true
        var prepares = 0

        fun installCleanup(job: Job) {
            ExoVideoEngine::class.java
                .getDeclaredField("fallbackJob")
                .apply { isAccessible = true }
                .set(engine, job)
        }

        fun schedule() {
            val method =
                ExoVideoEngine::class.java
                    .getDeclaredMethod(
                        "scheduleRetry",
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        String::class.java,
                    ).apply { isAccessible = true }
            assertEquals(true, method.invoke(engine, 0, 2, "audit transient failure"))
        }
    }
}
