package com.yfuse.feature.player

import com.yfuse.core.model.PlayerEngine
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The exported report labelled the engine from `nativeOnly`, which requires the engine choice to be
 * Auto. A session the user pinned to Exo that YCore 2.0 then bound therefore reported
 * `engine.selected=Exo` beside `engine.actual=YCore 2.0`, contradicting its own `engine_attached`
 * lines and sending a reader looking for a fallback that never happened.
 */
class PlaybackEngineBindingLabelTest {
    @Test
    fun a_core2_binding_is_labelled_natively_even_when_another_engine_was_selected() {
        assertEquals(
            YCORE2_NATIVE_ENGINE_LABEL,
            engineBindingLabel(
                diagnostics = PlaybackDiagnostics(engine = YCORE2_ENGINE_DIAGNOSTIC_NAME),
                selectedEngine = PlayerEngine.Exo,
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
                selectedEngine = PlayerEngine.Exo,
                nativeOnly = true,
            ),
        )
    }

    @Test
    fun another_engine_keeps_the_name_it_reports() {
        assertEquals(
            "Exo",
            engineBindingLabel(
                diagnostics = PlaybackDiagnostics(engine = "Exo"),
                selectedEngine = PlayerEngine.Exo,
                nativeOnly = false,
            ),
        )
    }

    @Test
    fun an_engine_that_has_not_reported_yet_falls_back_to_the_selection() {
        assertEquals(
            PlayerEngine.Mpv.name,
            engineBindingLabel(
                diagnostics = PlaybackDiagnostics(),
                selectedEngine = PlayerEngine.Mpv,
                nativeOnly = false,
            ),
        )
    }
}
