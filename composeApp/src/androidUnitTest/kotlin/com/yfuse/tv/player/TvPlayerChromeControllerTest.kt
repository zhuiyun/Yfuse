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
        controller.publishUiState(
            layer = TvPlayerChromeLayer.Hidden,
            panel = null,
            controlsHaveFocus = false,
        )

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

    @Test
    fun without_an_attached_surface_the_controller_never_claims_visible_chrome() {
        val controller = TvPlayerChromeController()

        // A media key on the preparation screen still asks for the controls, which do not exist there.
        controller.showControls()
        assertFalse(controller.state.value.attached)
        assertEquals(TvPlayerChromeLayer.Hidden, controller.state.value.layer)
        assertFalse(controller.state.value.hasDismissibleLayer)

        controller.publishUiState(
            layer = TvPlayerChromeLayer.Panel,
            panel = TvPlayerChromePanel.Settings,
            controlsHaveFocus = true,
        )
        assertTrue(controller.state.value.attached)
        controller.updateSeekPreview(12_000L)
        controller.publishSkipPrompt(true)

        controller.detach()
        assertFalse(controller.state.value.attached)
        assertFalse(controller.state.value.skipPrompt)
        assertEquals(TvPlayerChromeLayer.Hidden, controller.state.value.layer)
        assertNull(controller.state.value.panel)
        assertFalse(controller.state.value.controlsHaveFocus)
        // The remote owns its seek preview; it ends with the key's release, not with the surface.
        assertEquals(12_000L, controller.state.value.seekTargetMs)
    }
}
