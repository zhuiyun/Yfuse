package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals

class TransportStatusTextTest {
    @Test
    fun local_playback_reads_as_it_always_did() {
        assertEquals("正在播放", transportStatusText(PlaybackState(playing = true, buffering = false), false, null))
        assertEquals("已暂停", transportStatusText(PlaybackState(playing = false, buffering = false), false, null))
        assertEquals("正在缓冲", transportStatusText(PlaybackState(playing = true, buffering = true), false, null))
        assertEquals("播放完成", transportStatusText(PlaybackState(ended = true, buffering = false), false, null))
        assertEquals("播放失败，可返回播放器重试", transportStatusText(PlaybackState(error = "解码失败"), false, null))
    }

    @Test
    fun a_cast_says_which_screen_the_film_is_on() {
        assertEquals(
            "正在播放 · 投屏到 客厅电视",
            transportStatusText(PlaybackState(playing = true, buffering = false), true, " 客厅电视 "),
        )
        assertEquals(
            "已暂停 · 正在投屏",
            transportStatusText(PlaybackState(playing = false, buffering = false), true, "  "),
        )
        assertEquals("正在缓冲 · 正在投屏", transportStatusText(PlaybackState(buffering = true), true, null))
    }

    @Test
    fun a_device_name_alone_never_marks_local_playback_as_a_cast() {
        assertEquals(
            "正在播放",
            transportStatusText(PlaybackState(playing = true, buffering = false), false, "客厅电视"),
        )
    }
}
