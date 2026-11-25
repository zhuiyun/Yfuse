package com.yfuse.feature.player

import com.yfuse.core.model.PlayerEngine
import kotlin.test.Test
import kotlin.test.assertEquals

/** Runtime diagnostics and selected engine identities remain distinct while attaching. */
class PlaybackEngineBindingLabelTest {
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
}
