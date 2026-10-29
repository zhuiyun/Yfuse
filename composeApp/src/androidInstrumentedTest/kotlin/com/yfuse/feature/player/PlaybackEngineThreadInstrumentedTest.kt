package com.yfuse.feature.player

import android.net.Uri
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yfuse.core.model.DecoderMode
import com.yfuse.core.playback.PlaybackOptimizationMode
import com.yfuse.core2.android.GeneratedAvcAacTestMedia
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class PlaybackEngineThreadInstrumentedTest {
    @Test
    fun slot_construction_ticker_controls_and_release_use_the_media3_application_thread(): Unit =
        runBlocking {
            requireFullReleaseTestPackage()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            // A worker owner must not move the player or its ticker off the application looper.
            val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val scope = owner.playbackEngineScope()
            assertSame(owner.coroutineContext[Job], scope.coroutineContext[Job])
            val media = GeneratedAvcAacTestMedia.create(context.cacheDir)
            val host = PlaybackReleaseTestHost.open()
            var created: ExoVideoEngine? = null
            var slot: PlaybackEngineSlot? = null
            val retirements =
                PlaybackEngineRetirements(scope, { engine ->
                    assertEquals(Looper.getMainLooper(), Looper.myLooper())
                    (engine as ExoVideoEngine).releaseAndJoin()
                })
            try {
                val input =
                    PlaybackEngineInput(
                        listOf(PlayerMediaItem("thread-test", Uri.fromFile(media).toString(), "", "Thread regression")),
                        PlaybackHandoverSnapshot(0, 0L, false, 1f),
                    )
                val activeSlot = PlaybackEngineSlot(input, scope, retirements).also { slot = it }
                host.onMain {
                    activeSlot.request(
                        PlaybackEngineRequest(input) { pending, _ ->
                            assertEquals(Looper.getMainLooper(), Looper.myLooper())
                            ExoVideoEngine(
                                context,
                                pending.items,
                                pending.handover.itemIndex,
                                pending.handover.positionMs,
                                pending.handover.playbackRequested,
                                pending.handover.speed,
                                scope,
                                DecoderMode.Hardware,
                                PlaybackOptimizationMode.Balanced,
                                false,
                                "Yfuse-thread-regression",
                                0L,
                            ).also { created = it }
                        },
                    )
                }
                val binding =
                    withTimeout(10_000L) {
                        activeSlot.binding.first { it.engine is ExoVideoEngine || it.engine.state.value.error != null }
                    }
                assertNull("Media3 construction/prepare failed", binding.engine.state.value.error)
                val engine = binding.engine as ExoVideoEngine
                host.onMain {
                    assertEquals(Looper.getMainLooper(), engine.player.applicationLooper)
                    engine.player.setVideoSurfaceView(host.view)
                    engine.setSpeed(1.25f)
                    engine.play()
                }
                // Position is published by the engine-owned ticker, which also calls Media3 APIs.
                withTimeout(20_000L) {
                    engine.state.first {
                        check(it.error == null) { "Generated playback failed: ${it.error}" }
                        it.positionMs >= 300L &&
                            it.diagnostics.effectiveVideoReadiness == PlaybackOutputReadiness.Rendering
                    }
                }
                host.onMain {
                    engine.pause()
                    engine.seekTo(0L)
                    engine.setSpeed(1f)
                    activeSlot.close()
                }
                withTimeout(10_000L) { retirements.await() }
                assertTrue(engine.releaseCompleted)
            } finally {
                withContext(Dispatchers.Main.immediate) {
                    slot?.close()
                    created?.releaseAndJoin()
                }
                owner.cancel()
                host.close()
                media.delete()
            }
        }
}
