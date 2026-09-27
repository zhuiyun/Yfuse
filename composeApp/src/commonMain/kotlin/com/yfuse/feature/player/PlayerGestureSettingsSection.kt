package com.yfuse.feature.player

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.yfuse.core.data.PlaybackPreferences
import com.yfuse.core.data.PlayerGestureSettings
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.ThemeText as Text

/** 中间长按's two answers, in the order the panel lists them. */
internal val CENTER_HOLD_CHOICES = listOf("临时倍速", "关闭")

/**
 * 手势 in 播放设置: 双击步长, what a held middle does, and which edge adjusts what.
 *
 * Written straight to [preferences], which the controls read back through the player, so a change
 * applies to the next gesture without leaving the panel — the picture behind it is where to try it.
 */
@Composable
internal fun PlayerGestureSettingsSection(preferences: PlaybackPreferences) {
    val gestures by preferences.gestureSettings.collectAsState()
    val steps = PlayerGestureSettings.DOUBLE_TAP_SEEK_CHOICES
    GroupLabel("手势")
    GestureSettingCaption("双击步长 · 双击左右两侧和键盘 J / L")
    CompactChoiceGrid(
        options = steps.map { "$it 秒" },
        selectedIndex = steps.indexOf(gestures.doubleTapSeekSeconds),
        columns = steps.size,
        onSelect = { index ->
            preferences.setGestureSettings(gestures.copy(doubleTapSeekSeconds = steps[index]))
        },
    )
    GestureSettingCaption("中间长按")
    CompactChoiceGrid(
        options = CENTER_HOLD_CHOICES,
        selectedIndex = if (gestures.centerHoldSpeedBoost) 0 else 1,
        columns = CENTER_HOLD_CHOICES.size,
        onSelect = { index ->
            preferences.setGestureSettings(gestures.copy(centerHoldSpeedBoost = index == 0))
        },
    )
    PopupToggleHeader(
        label = "亮度与音量左右互换",
        checked = gestures.swapBrightnessVolume,
        onToggle = {
            preferences.setGestureSettings(gestures.copy(swapBrightnessVolume = !gestures.swapBrightnessVolume))
        },
    )
    Text(
        if (gestures.swapBrightnessVolume) "左侧上下滑调音量，右侧调亮度" else "左侧上下滑调亮度，右侧调音量",
        style = AppTypography.caption.regular,
        color = Color.White.copy(alpha = 0.6f),
    )
}

@Composable
private fun GestureSettingCaption(text: String) {
    Text(
        text,
        style = AppTypography.caption.regular,
        color = Color.White.copy(alpha = 0.6f),
        modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
    )
}
