package com.yfuse.feature.player

import com.yfuse.core.model.PlayerEngine
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `engine_attached`/`engine_detached` (PlayerRoot.kt) used to log `engine=Exo implementation=yx7`
 * for a player that had not attached anything yet: PlaybackEngineSlot publishes the *target*
 * kind as soon as a switch starts (PlaybackEngineSlot.start), well before the placeholder
 * [PreparingVideoEngine] is replaced by a real engine, and `implementation` logged the attached
 * object's obfuscated class name outright.
 *
 * The [com.yfuse.core2.legacy.YPlayerVideoEngineAdapter] branch is unchanged by this fix and
 * needs a real [com.yfuse.core2.api.YPlayer] to construct, so it is left to that class's own
 * adapter tests; this file covers only the branches [engineAttachedLabel] adds/touches.
 */
class PlayerRootEngineLabelTest {
    private fun item(id: String) = PlayerMediaItem(id, "https://example.test/$id", "", id)

    private fun input() = PlaybackEngineInput(listOf(item("first")), PlaybackHandoverSnapshot(0, 0L, true, 1f))

    @Test
    fun the_placeholder_is_labelled_preparing_even_when_the_target_kind_is_known() {
        assertEquals(
            "Preparing",
            engineAttachedLabel(PreparingVideoEngine(input()), PlayerEngine.Exo),
        )
    }

    @Test
    fun any_other_attached_engine_is_labelled_by_the_selected_kind() {
        // A concrete, non-YPlayerVideoEngineAdapter engine: only its *type* matters here (it
        // must not be PreparingVideoEngine), so a thin delegate stands in for a whole real
        // backend such as Exo/mpv/MDK.
        val attached = object : VideoEngine by PreparingVideoEngine(input()) {}

        assertEquals(PlayerEngine.Mpv.name, engineAttachedLabel(attached, PlayerEngine.Mpv))
    }
}
