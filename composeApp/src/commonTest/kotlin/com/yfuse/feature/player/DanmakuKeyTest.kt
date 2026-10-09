package com.yfuse.feature.player

import com.yfuse.core.data.DanmakuSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DanmakuKeyTest {
    @Test
    fun with_no_source_the_key_has_nothing_to_switch_and_opens_the_panel_instead() {
        // The bar opens the panel while this is false; a fresh install has no 弹幕来源 at all.
        assertFalse(DanmakuPanelState().configured)
        assertFalse(DanmakuPanelState(enabled = true).configured)
        val source = DanmakuSource(id = "dms-1", name = "弹幕源 1", url = "https://danmaku.example")
        assertTrue(DanmakuPanelState(sources = listOf(source)).configured)
        // A remembered choice that is gone falls back to the first source rather than to none.
        assertTrue(DanmakuPanelState(sources = listOf(source), activeSourceId = "dms-gone").configured)
    }

    @Test
    fun a_host_that_does_not_report_the_key_still_gets_a_switch() {
        var toggles = 0
        val actions = DanmakuPanelActions(onToggle = { toggles++ })
        actions.onKeyToggle()
        assertEquals(1, toggles)
        var keyTaps = 0
        DanmakuPanelActions(onToggle = { toggles++ }, onKeyToggle = { keyTaps++ }).onKeyToggle()
        assertEquals(1, toggles)
        assertEquals(1, keyTaps)
    }
}
