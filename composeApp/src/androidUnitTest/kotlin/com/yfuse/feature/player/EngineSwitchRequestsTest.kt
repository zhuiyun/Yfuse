package com.yfuse.feature.player

import com.yfuse.core.playback.PlaybackEngineSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class EngineSwitchRequestsTest {
    @Test
    fun repeating_a_rejected_adjustment_offers_a_new_decision_and_resets_its_value() {
        val requests = EngineSwitchRequests()
        val choices = PlayerViewerChoices(PlaybackEngineSelection.Auto)

        fun adjust(delay: Long): PendingEngineSwitch {
            choices.audioControls = choices.audioControls.copy(delayMs = delay)
            return requests.offer("episode", UnsupportedPlaybackSetting.AudioDelay, { error("Not selected") }) {
                UnsupportedPlaybackSetting.AudioDelay.reset(choices)
            }
        }
        val first = adjust(200L)
        requests.resolve(first, switch = false)
        assertEquals(0L, choices.audioControls.delayMs)
        val repeated = adjust(500L)
        assertNotSame(first, repeated)
        requests.resolve(repeated, switch = false)
        assertEquals(0L, choices.audioControls.delayMs)
    }

    @Test
    fun keeping_the_engine_resets_all_concurrently_restored_unsupported_settings() {
        val requests = EngineSwitchRequests()
        val choices = PlayerViewerChoices(PlaybackEngineSelection.Auto)
        choices.audioControls = choices.audioControls.copy(delayMs = 200L)
        choices.subtitleControls = choices.subtitleControls.copy(offsetMs = 1000L, scale = 1.5f)
        val settings =
            listOf(
                UnsupportedPlaybackSetting.AudioDelay,
                UnsupportedPlaybackSetting.SubtitleOffset,
                UnsupportedPlaybackSetting.SubtitleScale,
            )
        val offered =
            settings.map { setting ->
                requests.offer("episode", setting, { error("Not selected") }) { setting.reset(choices) }
            }
        offered.forEach { assertSame(offered.first(), it) }
        assertEquals(settings, offered.first().settings)
        requests.resolve(offered.first(), switch = false)
        assertEquals(0L, choices.audioControls.delayMs)
        assertEquals(0L, choices.subtitleControls.offsetMs)
        assertEquals(1f, choices.subtitleControls.scale)
    }

    @Test
    fun switching_uses_the_latest_callback_once_and_preserves_requested_values() {
        val requests = EngineSwitchRequests()
        var switches = 0
        var resets = 0
        val first =
            requests.offer(
                "episode",
                UnsupportedPlaybackSetting.AudioDelay,
                { error("Old callback") },
            ) { resets++ }
        val second = requests.offer("episode", UnsupportedPlaybackSetting.SubtitleOffset, { switches++ }) { resets++ }
        assertSame(first, second)
        requests.resolve(second, switch = true)
        requests.resolve(first, switch = false) // Native dialog also dispatches onDismiss.
        assertEquals(1, switches)
        assertEquals(0, resets)
    }

    @Test
    fun stale_dismissals_cannot_resolve_the_next_items_prompt() {
        val requests = EngineSwitchRequests()
        val reset = mutableListOf<String>()
        val old = requests.offer("one", UnsupportedPlaybackSetting.AudioDelay, {}) { reset += "one" }
        val next = requests.offer("two", UnsupportedPlaybackSetting.AudioDelay, {}) { reset += "two" }
        assertEquals(listOf("one"), reset)
        requests.resolve(old, switch = false)
        assertEquals(listOf("one"), reset)
        requests.resolve(next, switch = false)
        assertEquals(listOf("one", "two"), reset)
    }
}
