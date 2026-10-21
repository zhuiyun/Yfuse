package com.yfuse.feature.player

import com.yfuse.core.model.DecoderMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MdkDeferredControlsTest {
    @Test
    fun commands_before_surface_attach_retain_the_latest_seek_and_play_intent() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine =
            MdkVideoEngine(
                items = listOf(PlayerMediaItem("item", "https://example.test/video.mp4", "", "Video")),
                startIndex = 0,
                startPositionMs = 12_000L,
                startPlaybackRequested = false,
                startSpeed = 1f,
                decoderMode = DecoderMode.Auto,
                autoNext = false,
                customUserAgent = "test",
                scope = scope,
                context = null,
            )
        try {
            val pending =
                MdkVideoEngine::class.java
                    .getDeclaredField("pendingSeek")
                    .apply {
                        isAccessible = true
                    }.get(engine) as MdkPendingSeek
            assertEquals(12_000L, pending.positionMs)
            engine.seekTo(45_000L)
            engine.setSpeed(1.5f)
            engine.play()
            assertTrue(engine.playbackRequested)
            engine.pause()
            assertFalse(engine.playbackRequested)
            assertEquals(45_000L, engine.state.value.positionMs)
            assertEquals(45_000L, pending.positionMs)
            assertEquals(1.5f, engine.state.value.speed)
            engine.seekTo(0L)
            assertEquals(0L, pending.positionMs)
        } finally {
            engine.release()
            scope.cancel()
        }
        assertTrue(engine.releaseCompleted)
    }
}
