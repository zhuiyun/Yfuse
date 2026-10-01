package com.yfuse.feature.player

import com.yfuse.core.data.PlayerGestureSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayerGestureHelpTest {
    private fun List<Pair<String, String>>.row(gesture: String): String? = firstOrNull { it.first == gesture }?.second

    @Test
    fun defaults_describe_the_player_as_it_always_was() {
        val rows = pictureGestureHelpRows(PlayerGestureSettings())
        assertEquals("快退 / 快进 10 秒，随后同侧每点一下再加一步；也可拖动进度条", rows.row("双击左侧 / 右侧"))
        assertTrue(rows.row("长按中间")!!.startsWith("临时 2 倍速"))
        assertTrue(rows.row("左半屏上下滑")!!.startsWith("调节亮度"))
        assertTrue(rows.row("右半屏上下滑")!!.startsWith("调节音量"))
    }

    @Test
    fun rows_follow_the_gesture_settings() {
        val rows =
            pictureGestureHelpRows(
                PlayerGestureSettings(
                    doubleTapSeekSeconds = 30,
                    centerHoldSpeedBoost = false,
                    swapBrightnessVolume = true,
                ),
            )
        assertEquals("快退 / 快进 30 秒，随后同侧每点一下再加一步；也可拖动进度条", rows.row("双击左侧 / 右侧"))
        assertEquals(null, rows.row("长按中间"))
        assertTrue(rows.row("左半屏上下滑")!!.startsWith("调节音量"))
        assertTrue(rows.row("右半屏上下滑")!!.startsWith("调节亮度"))
    }

    @Test
    fun the_lock_key_follows_its_setting() {
        assertTrue(pictureGestureHelpRows(PlayerGestureSettings()).row("左侧锁键")!!.contains("点按"))
        val longPress = pictureGestureHelpRows(PlayerGestureSettings(unlockByLongPress = true))
        assertTrue(longPress.row("左侧锁键")!!.contains("长按锁键解锁"))
    }

    @Test
    fun a_double_tap_that_only_pauses_is_described_as_one_gesture() {
        val rows = pictureGestureHelpRows(PlayerGestureSettings(doubleTapPausesAnywhere = true))
        assertEquals(null, rows.row("双击左侧 / 右侧"))
        assertEquals("播放或暂停；也可使用底部播放按钮", rows.row("双击画面"))
    }

    @Test
    fun keys_that_do_something_else_when_held_say_so() {
        val rows = keyHelpRows()
        assertEquals("开关弹幕 / 打开弹幕设置", rows.row("点按 / 长按弹幕键"))
        assertTrue(rows.row("点按 / 长按画面键")!!.contains("拉伸填满"))
        assertTrue(rows.row("长按后退 10 秒")!!.startsWith("没听清"))
    }

    @Test
    fun only_the_middle_is_held_so_no_long_press_is_offered_on_the_sides() {
        val holds = pictureGestureHelpRows(PlayerGestureSettings()).map { it.first }.filter { it.startsWith("长按") }
        assertEquals(listOf("长按中间"), holds)
        val boostOff = pictureGestureHelpRows(PlayerGestureSettings(centerHoldSpeedBoost = false))
        assertTrue(boostOff.none { it.first.startsWith("长按") })
    }

    @Test
    fun keyboard_rows_share_the_double_tap_step() {
        val rows = keyboardHelpRows(PlayerGestureSettings(doubleTapSeekSeconds = 15))
        assertEquals("快退 / 快进 15 秒，与双击步长相同", rows.row("J / L"))
        assertEquals("播放或暂停", rows.row("空格 / K"))
    }
}
