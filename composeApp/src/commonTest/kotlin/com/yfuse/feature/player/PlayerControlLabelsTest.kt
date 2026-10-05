package com.yfuse.feature.player

import com.yfuse.core.cast.CastCapabilities
import com.yfuse.core.cast.CastCapability
import com.yfuse.core.cast.CastDevice
import com.yfuse.core.cast.CastState
import com.yfuse.core.playback.PlaybackDiscChapter
import com.yfuse.core.playback.PlaybackDiscNavigationState
import com.yfuse.core.playback.PlaybackDiscTitle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayerControlLabelsTest {
    private val livingRoom = CastDevice(id = "tv", name = "客厅电视")

    @Test
    fun a_disc_title_is_named_by_its_edition_or_else_by_its_number() {
        val disc =
            PlaybackDiscNavigationState(
                titleCount = 3,
                titles = listOf(PlaybackDiscTitle(index = 1, title = "导演剪辑版")),
            )
        assertEquals("标题 1", discTitleToast(disc, 0))
        assertEquals("导演剪辑版", discTitleToast(disc, 1))
        assertEquals("标题 5", discTitleToast(disc, 4))
    }

    @Test
    fun a_chapter_toast_adds_the_authored_name_only_when_there_is_one() {
        val disc =
            PlaybackDiscNavigationState(
                chapterCount = 3,
                chapters =
                    listOf(
                        PlaybackDiscChapter(index = 1, title = " 序幕 "),
                        PlaybackDiscChapter(index = 2, title = " "),
                    ),
            )
        assertEquals("第 1 章", discChapterToast(disc, 0))
        assertEquals("第 2 章 · 序幕", discChapterToast(disc, 1))
        assertEquals("第 3 章", discChapterToast(disc, 2))
    }

    @Test
    fun the_danmaku_key_says_off_at_once_and_on_with_what_loading_came_to() {
        assertEquals("弹幕已关闭", danmakuKeyToast(enabled = false))
        // Off is off, whatever the last load left behind.
        assertEquals("弹幕已关闭", danmakuKeyToast(enabled = false, count = 120, error = "弹幕接口不存在（404）"))
        assertEquals("弹幕已开启 · 已匹配 120 条", danmakuKeyToast(enabled = true, count = 120))
    }

    @Test
    fun the_danmaku_key_says_why_no_comments_show() {
        // No episode for this one: 搜索弹幕, behind the held key, puts that right.
        assertEquals(
            "弹幕已开启，但没有匹配到这一集，按住弹幕键可手动搜索",
            danmakuKeyToast(enabled = true, error = "没有匹配到弹幕，可用搜索手动选择", unmatched = true),
        )
        assertEquals(
            "弹幕已开启，但加载失败：弹幕接口连接失败，请检查地址和网络",
            danmakuKeyToast(enabled = true, error = "弹幕接口连接失败，请检查地址和网络"),
        )
        assertEquals("弹幕已开启，这一集还没有弹幕", danmakuKeyToast(enabled = true))
    }

    @Test
    fun the_cast_position_waits_for_the_receiver_and_then_shows_where_it_is() {
        assertNull(castPositionLabel(CastState(positionMs = 5_000L, positionConfirmed = true)))
        assertEquals(
            "等待接收端确认",
            castPositionLabel(CastState(activeDevice = livingRoom, positionMs = 5_000L)),
        )
        assertEquals(
            "00:01:05",
            castPositionLabel(CastState(activeDevice = livingRoom, positionMs = 65_000L, positionConfirmed = true)),
        )
        assertEquals(
            "00:01:05 / 01:30:00",
            castPositionLabel(
                CastState(
                    activeDevice = livingRoom,
                    positionMs = 65_000L,
                    positionConfirmed = true,
                    durationMs = 5_400_000L,
                ),
            ),
        )
    }

    @Test
    fun the_cast_capabilities_name_each_one_the_receiver_reported() {
        assertNull(castCapabilitiesLabel(CastState()))
        val cast =
            CastState(
                activeDevice = livingRoom,
                capabilities =
                    CastCapabilities(
                        playPause = CastCapability.Supported,
                        seek = CastCapability.Supported,
                        volume = CastCapability.Unsupported,
                        dolbyVision = CastCapability.Unsupported,
                    ),
            )
        assertEquals(
            "播放 支持 · 跳转 支持 · 音量 不支持 · 轨道 未知 · 队列 未知 · DV 不支持 · Atmos 未知",
            castCapabilitiesLabel(cast),
        )
    }
}
