package com.yfuse.tv.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TvPlayerChromeControllerTest {
    @Test
    fun ui_truth_and_remote_seek_preview_share_one_state_model() {
        val controller = TvPlayerChromeController()

        controller.showControls()
        assertEquals(TvPlayerChromeLayer.Controls, controller.state.value.layer)

        controller.publishUiState(
            layer = TvPlayerChromeLayer.Panel,
            panel = TvPlayerChromePanel.Settings,
            controlsHaveFocus = true,
        )
        controller.updateSeekPreview(42_000L)
        assertEquals(TvPlayerChromeLayer.Panel, controller.state.value.layer)
        assertEquals(TvPlayerChromePanel.Settings, controller.state.value.panel)
        assertTrue(controller.state.value.controlsHaveFocus)
        assertTrue(controller.state.value.seeking)
        assertEquals(42_000L, controller.state.value.seekTargetMs)

        controller.finishSeekPreview()
        assertFalse(controller.state.value.seeking)
        assertNull(controller.state.value.seekTargetMs)

        controller.hideControls()
        assertEquals(TvPlayerChromeLayer.Hidden, controller.state.value.layer)
        assertNull(controller.state.value.panel)
        assertFalse(controller.state.value.controlsHaveFocus)
    }
}
