package com.yfuse.feature.player

import com.yfuse.core.model.PlayerEngine
import kotlin.test.Test
import kotlin.test.assertEquals

/** Runtime diagnostics and selected engine identities remain distinct while attaching. */
class PlaybackEngineBindingLabelTest {
    @Test
    fun retained_native_preference_does_not_relabel_an_explicit_senplayer_session() {
        for (diagnostics in listOf(PlaybackDiagnostics(), PlaybackDiagnostics(engine = "SenPlayer"))) {
            assertEquals(
                "SenPlayer",
                engineBindingLabel(diagnostics, PlayerEngine.SenPlayer, nativeOnly = true),
            )
        }
    }

    @Test
    fun a_core2_binding_is_labelled_natively_even_when_another_engine_was_selected() {
        assertEquals(
            YCORE2_NATIVE_ENGINE_LABEL,
            engineBindingLabel(
                diagnostics = PlaybackDiagnostics(engine = YCORE2_ENGINE_DIAGNOSTIC_NAME),
                selectedEngine = PlayerEngine.YCore,
                nativeOnly = false,
            ),
        )
    }

    @Test
    fun native_only_keeps_its_label_before_the_engine_reports_itself() {
        assertEquals(
            YCORE2_NATIVE_ENGINE_LABEL,
            engineBindingLabel(
                diagnostics = PlaybackDiagnostics(),
                selectedEngine = PlayerEngine.YCore,
                nativeOnly = true,
            ),
        )
    }

    @Test
    fun another_engine_keeps_the_name_it_reports() {
        assertEquals(
            "SenPlayer",
            engineBindingLabel(
                diagnostics = PlaybackDiagnostics(engine = "SenPlayer"),
                selectedEngine = PlayerEngine.SenPlayer,
                nativeOnly = false,
            ),
        )
    }

    @Test
    fun an_engine_that_has_not_reported_yet_falls_back_to_the_selection() {
        assertEquals(
            PlayerEngine.SenPlayer.name,
            engineBindingLabel(
                diagnostics = PlaybackDiagnostics(),
                selectedEngine = PlayerEngine.SenPlayer,
                nativeOnly = false,
            ),
        )
    }
}
