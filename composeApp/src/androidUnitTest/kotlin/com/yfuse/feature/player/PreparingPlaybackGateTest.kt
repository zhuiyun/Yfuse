package com.yfuse.feature.player

import com.russhwolf.settings.MapSettings
import com.yfuse.core.account.AccountAccessTokenSource
import com.yfuse.core.data.WatchTogetherPreferences
import com.yfuse.core.sync.WatchTogetherClient
import com.yfuse.core2.legacy.LegacyYPlayerAdapter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PreparingPlaybackGateTest {
    private val item = PlayerMediaItem("movie", "https://example.test/movie", "", "movie")

    @Test
    fun waiting_engine_can_pause_and_resume_through_the_real_adapter_and_gate() {
        val engine = preparing(requested = true)
        val player = LegacyYPlayerAdapter(engine)
        val gate = gate(player)
        assertFalse(player.state.value.playing)
        assertTrue(player.playbackRequested)
        assertTrue(gate.togglePlayPause())
        assertFalse(engine.snapshot().handover.playbackRequested)
        assertTrue(gate.togglePlayPause())
        assertTrue(engine.snapshot().handover.playbackRequested)
        assertFalse(player.state.value.playing, "A play request is not proof of output during retirement")
    }

    @Test
    fun paused_engine_requests_play_and_playing_engine_requests_pause() {
        val engine = IntentEngine(playing = false, requested = false)
        val gate = gate(LegacyYPlayerAdapter(engine))
        gate.togglePlayPause()
        assertTrue(engine.playbackRequested)
        engine.presentation.value = engine.presentation.value.copy(playing = true)
        gate.togglePlayPause()
        assertFalse(engine.playbackRequested)
    }

    @Test
    fun a_second_click_reverses_pending_pause_even_if_output_has_not_stopped_yet() {
        val engine = IntentEngine(playing = true, requested = false)
        val gate = gate(LegacyYPlayerAdapter(engine))
        gate.togglePlayPause()
        assertTrue(engine.playbackRequested)
        gate.togglePlayPause()
        assertFalse(engine.playbackRequested)
    }

    @Test
    fun ended_engine_can_request_play_again() {
        val engine = IntentEngine(playing = false, requested = false)
        engine.presentation.value = engine.presentation.value.copy(ended = true)
        val gate = gate(LegacyYPlayerAdapter(engine))
        gate.togglePlayPause()
        assertTrue(engine.playbackRequested)
    }

    private fun preparing(requested: Boolean) =
        PreparingVideoEngine(
            PlaybackEngineInput(listOf(item), PlaybackHandoverSnapshot(0, 25L, requested, 1f)),
        )

    private fun gate(player: LegacyYPlayerAdapter) =
        WatchGatedPlayback(
            watchTogether =
                WatchTogetherClient(
                    preferences = WatchTogetherPreferences(MapSettings()),
                    accountTokens = AccountAccessTokenSource("https://other.example"),
                ),
            items = { listOf(item) },
            player = { player },
        )

    private inner class IntentEngine(
        playing: Boolean,
        requested: Boolean,
    ) : VideoEngine by preparing(requested) {
        val presentation = MutableStateFlow(PlaybackState(playing = playing))
        override val state = presentation
        override var playbackRequested = requested
            private set

        override fun play() {
            playbackRequested = true
        }

        override fun pause() {
            playbackRequested = false
        }
    }
}
