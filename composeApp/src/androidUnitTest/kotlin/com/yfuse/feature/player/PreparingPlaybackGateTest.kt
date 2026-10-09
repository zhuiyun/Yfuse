package com.yfuse.feature.player

import com.russhwolf.settings.MapSettings
import com.yfuse.core.account.AccountAccessTokenSource
import com.yfuse.core.data.WatchTogetherPreferences
import com.yfuse.core.sync.WatchTogetherClient
import com.yfuse.core2.legacy.LegacyYPlayerAdapter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun explicit_pause_through_the_gate_cancels_focus_recovery() {
        val focus = PlayerAudioFocusState()
        val request = focus.beginRequest()
        focus.lost(request, transient = true, playbackRequested = true)
        val engine = IntentEngine(playing = false, requested = false)
        val gate = gate(LegacyYPlayerAdapter(engine), onPauseRequested = focus::cancelResume)

        assertTrue(gate.pause())
        assertFalse(focus.gained(request, canResume = true))
        assertFalse(engine.playbackRequested)
    }

    @Test
    fun toggle_to_pause_cancels_focus_recovery_while_output_is_buffering() {
        val focus = PlayerAudioFocusState()
        val request = focus.beginRequest()
        focus.lost(request, transient = true, playbackRequested = true)
        val engine = preparing(requested = true)
        val gate = gate(LegacyYPlayerAdapter(engine), onPauseRequested = focus::cancelResume)

        assertTrue(gate.togglePlayPause())
        assertFalse(focus.gained(request, canResume = true))
        assertFalse(engine.playbackRequested)
    }

    @Test
    fun denied_play_from_a_paused_waiting_engine_keeps_the_intent_paused() {
        val focus = PlayerAudioFocusState()
        val request = focus.beginRequest()
        focus.lost(request, transient = true, playbackRequested = true)
        val engine = preparing(requested = false)
        val player = LegacyYPlayerAdapter(engine)
        var attempts = 0
        val gate =
            gate(
                player,
                onPauseRequested = focus::cancelResume,
                onPlayRequested = {
                    attempts++
                    false
                },
            )

        assertFalse(gate.togglePlayPause())
        assertEquals(1, attempts)
        assertFalse(player.playbackRequested)
        assertFalse(engine.snapshot().handover.playbackRequested)
        assertFalse(player.state.value.playing)
        assertFalse(focus.gained(request, canResume = true))
    }

    @Test
    fun focus_can_be_granted_on_a_later_tap_and_pause_never_requires_admission() {
        val engine = preparing(requested = false)
        var allowed = false
        var attempts = 0
        val gate =
            gate(LegacyYPlayerAdapter(engine), onPlayRequested = {
                attempts++
                allowed
            })

        assertFalse(gate.play())
        assertFalse(engine.playbackRequested)
        allowed = true
        assertTrue(gate.togglePlayPause())
        assertTrue(engine.playbackRequested)
        allowed = false
        assertTrue(gate.togglePlayPause())
        assertFalse(engine.playbackRequested)
        assertEquals(2, attempts)
    }

    @Test
    fun denied_retry_and_episode_selection_do_not_dispatch_or_preserve_an_old_play_intent() {
        val engine = IntentEngine(playing = false, requested = true)
        val gate = gate(LegacyYPlayerAdapter(engine), onPlayRequested = { false })

        assertFalse(gate.retry())
        assertFalse(engine.playbackRequested)
        assertEquals(0, engine.retries)
        engine.play()
        assertFalse(gate.selectItem(0))
        assertFalse(engine.playbackRequested)
        assertEquals(0, engine.selections)
    }

    @Test
    fun admitted_retry_and_episode_selection_reach_the_engine() {
        val engine = IntentEngine(playing = false, requested = false)
        var attempts = 0
        val gate =
            gate(LegacyYPlayerAdapter(engine), onPlayRequested = {
                attempts++
                true
            })

        assertTrue(gate.retry())
        assertTrue(gate.selectItem(0))
        assertEquals(2, attempts)
        assertEquals(1, engine.retries)
        assertEquals(1, engine.selections)
        assertTrue(engine.playbackRequested)
    }

    private fun preparing(requested: Boolean) =
        PreparingVideoEngine(
            PlaybackEngineInput(listOf(item), PlaybackHandoverSnapshot(0, 25L, requested, 1f)),
        )

    private fun gate(
        player: LegacyYPlayerAdapter,
        onPauseRequested: () -> Unit = {},
        onPlayRequested: () -> Boolean = { true },
    ) = WatchGatedPlayback(
        watchTogether =
            WatchTogetherClient(
                preferences = WatchTogetherPreferences(MapSettings()),
                accountTokens = AccountAccessTokenSource("https://other.example"),
            ),
        items = { listOf(item) },
        player = { player },
        onPauseRequested = onPauseRequested,
        onPlayRequested = onPlayRequested,
    )

    private inner class IntentEngine(
        playing: Boolean,
        requested: Boolean,
    ) : VideoEngine by preparing(requested) {
        val presentation = MutableStateFlow(PlaybackState(playing = playing))
        override val state = presentation
        override var playbackRequested = requested
            private set
        var retries = 0
            private set
        var selections = 0
            private set

        override fun retry() {
            retries++
            play()
        }

        override fun selectItem(index: Int) {
            selections++
            play()
        }

        override fun play() {
            playbackRequested = true
        }

        override fun pause() {
            playbackRequested = false
        }
    }
}
