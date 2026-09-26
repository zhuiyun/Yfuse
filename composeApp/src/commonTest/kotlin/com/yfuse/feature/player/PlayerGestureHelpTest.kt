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
        assertEquals("快退 / 快进 10 秒；也可拖动进度条", rows.row("双击左侧 / 右侧"))
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
        assertEquals("快退 / 快进 30 秒；也可拖动进度条", rows.row("双击左侧 / 右侧"))
        assertEquals(null, rows.row("长按中间"))
        assertTrue(rows.row("左半屏上下滑")!!.startsWith("调节音量"))
        assertTrue(rows.row("右半屏上下滑")!!.startsWith("调节亮度"))
    }

    @Test
    fun keyboard_rows_share_the_double_tap_step() {
        val rows = keyboardHelpRows(PlayerGestureSettings(doubleTapSeekSeconds = 15))
        assertEquals("快退 / 快进 15 秒，与双击步长相同", rows.row("J / L"))
        assertEquals("播放或暂停", rows.row("空格 / K"))
    }
}
