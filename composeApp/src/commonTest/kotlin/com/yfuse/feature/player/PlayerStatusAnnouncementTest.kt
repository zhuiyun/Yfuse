package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PlayerStatusAnnouncementTest {
    @Test
    fun the_skip_countdown_is_announced_without_the_seconds_it_shows() {
        val announcement = skipCountdownAnnouncement("跳过片头")
        assertEquals("即将自动跳过片头", announcement)
        assertEquals("3 秒后跳过片头 · 点击取消", skipCountdownLabel("跳过片头", 3))
        assertFalse(announcement.any(Char::isDigit))
    }

    @Test
    fun a_status_line_without_figures_is_drawn_as_it_is_announced() {
        assertEquals("正在准备画面", PlaybackStatusLine("正在准备画面").text)
    }
}
