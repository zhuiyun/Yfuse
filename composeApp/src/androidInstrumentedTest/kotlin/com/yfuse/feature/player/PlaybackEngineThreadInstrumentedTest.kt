package com.yfuse.feature.player

import android.net.Uri
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yfuse.core2.android.AndroidCore2PlayerFactory
import com.yfuse.core2.android.AndroidSerializedPlayerRelease
import com.yfuse.core2.android.AndroidSurfaceVideoOutput
import com.yfuse.core2.android.GeneratedAvcAacTestMedia
import com.yfuse.core2.android.toCore2MediaItems
import com.yfuse.core2.api.YPlayerOpenRequest
import com.yfuse.core2.legacy.YPlayerVideoEngineAdapter
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
class PlaybackEngineThreadInstrumentedTest {
    @Test
    fun slot_controls_and_serialized_retirement_work_with_the_ycore_factory(): Unit =
        runBlocking {
            requireFullReleaseTestPackage()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            // A worker owner must not move the player or its ticker off the application looper.
            val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val scope = owner.playbackEngineScope()
            assertSame(owner.coroutineContext[Job], scope.coroutineContext[Job])
            val media = GeneratedAvcAacTestMedia.create(context.cacheDir)
            val host = PlaybackReleaseTestHost.open()
            var created: YPlayerVideoEngineAdapter? = null
            var slot: PlaybackEngineSlot? = null
            val retirements =
                PlaybackEngineRetirements(scope, { engine ->
                    assertEquals(Looper.getMainLooper(), Looper.myLooper())
                    ((engine as YPlayerVideoEngineAdapter).player as AndroidSerializedPlayerRelease).releaseAndJoin()
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
                            val player =
                                AndroidCore2PlayerFactory(context).create(
                                    YPlayerOpenRequest(
                                        items = pending.items.toCore2MediaItems("Yfuse-thread-regression"),
                                        startIndex = pending.handover.itemIndex,
                                        startPositionMs = pending.handover.positionMs,
                                        autoPlay = pending.handover.playbackRequested,
                                        autoNext = false,
                                    ),
                                )
                            player.prepare()
                            YPlayerVideoEngineAdapter(player).also { created = it }
                        },
                    )
                }
                val binding =
                    withTimeout(10_000L) {
                        activeSlot.binding.first {
                            it.engine is YPlayerVideoEngineAdapter ||
                                it.engine.state.value.error != null
                        }
                    }
                assertNull("YCore construction/prepare failed", binding.engine.state.value.error)
                val engine = binding.engine as YPlayerVideoEngineAdapter
                host.onMain {
                    assertEquals(Looper.getMainLooper(), Looper.myLooper())
                    assertTrue(engine.player.setVideoOutput(AndroidSurfaceVideoOutput(host.view.holder.surface)))
                    engine.setSpeed(1.25f)
                    engine.play()
                }
                // Real YCore decode must produce verified output before testing pause, seek and retirement.
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
                assertTrue((engine.player as AndroidSerializedPlayerRelease).releaseCompleted)
            } finally {
                withContext(Dispatchers.Main.immediate) {
                    slot?.close()
                    (created?.player as? AndroidSerializedPlayerRelease)?.releaseAndJoin()
                }
                owner.cancel()
                host.close()
                media.delete()
            }
        }
}
