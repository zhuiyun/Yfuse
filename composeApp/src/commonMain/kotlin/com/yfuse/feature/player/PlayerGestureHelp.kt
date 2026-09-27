package com.yfuse.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yfuse.core.data.PlayerGestureSettings
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.ThemeText as Text

/**
 * 画面手势's rows, true to the current 手势 settings: the step a double tap takes, whether the
 * middle holds a speed at all, and which side of the picture adjusts what.
 */
internal fun pictureGestureHelpRows(gestures: PlayerGestureSettings): List<Pair<String, String>> {
    val brightness = "调节亮度；也可使用系统亮度设置"
    val volume = "调节音量；也可使用音量键或音量滑杆"
    return buildList {
        add("单击画面" to "显示或隐藏控制层")
        add("双击左侧 / 右侧" to "快退 / 快进 ${gestures.doubleTapSeekMs / 1_000L} 秒；也可拖动进度条")
        add("双击中间" to "播放或暂停；也可使用底部播放按钮")
        add("长按左侧 / 右侧" to "连续快退 / 快进；松手确认位置")
        if (gestures.centerHoldSpeedBoost) {
            add("长按中间" to "临时 2 倍速，左右滑动切换 1.5× / 2× / 3×，松手恢复；也可使用播放速度按钮")
        }
        add("横向滑动" to "预览并定位；也可使用可调进度条")
        add("双指捏合" to "张开裁剪填满，捏合恢复适应；也可使用顶部画面按钮")
        add("左半屏上下滑" to if (gestures.swapBrightnessVolume) volume else brightness)
        add("右半屏上下滑" to if (gestures.swapBrightnessVolume) brightness else volume)
    }
}

/** 键盘快捷键, for a tablet or Chromebook with a keyboard; J / L share the double-tap step. */
internal fun keyboardHelpRows(gestures: PlayerGestureSettings): List<Pair<String, String>> =
    listOf(
        "空格 / K" to "播放或暂停",
        "J / L" to "快退 / 快进 ${gestures.doubleTapSeekMs / 1_000L} 秒，与双击步长相同",
        "← / →" to "快退 / 快进 5 秒；焦点在进度条上时按进度条的步长",
        "F" to "在适应和裁剪填满之间切换",
        "M" to "静音，再按一次恢复音量",
        ", / ." to "暂停时按缩略图逐格前后查看",
    )

/** A permanent, accessible explanation of the picture-level gestures and their alternatives. */
@Composable
internal fun PlayerGestureHelpOverlay(
    onDismiss: () -> Unit,
    gestures: PlayerGestureSettings = PlayerGestureSettings(),
) {
    GlassDialog(
        onDismiss = onDismiss,
        modifier = Modifier.semantics { paneTitle = "播放器手势说明" },
    ) {
        OverlayHeader(
            title = "手势说明",
            subtitle = "不使用手势也能完成所有常用播放操作",
            onClose = onDismiss,
        )
        GestureHelpSection(
            title = "画面手势",
            rows = pictureGestureHelpRows(gestures),
        )
        GestureHelpSection(
            title = "辅助操作",
            rows =
                listOf(
                    "长按后退 10 秒" to "没听清：倒回 10 秒并临时打开字幕，播回原处后恢复",
                    "键盘方向键" to "聚焦进度条或音量滑杆后逐级调节",
                    "返回键" to "先关闭当前面板，再退出播放器",
                ),
        )
        GestureHelpSection(
            title = "键盘快捷键",
            rows = keyboardHelpRows(gestures),
        )
    }
}

@Composable
private fun GestureHelpSection(
    title: String,
    rows: List<Pair<String, String>>,
) {
    val palette = LocalPalette.current
    Text(
        title,
        style = AppTypography.body.strong,
        color = palette.text,
        modifier =
            Modifier
                .padding(top = 8.dp, bottom = 6.dp)
                .semantics { heading() },
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { (gesture, result) ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    gesture,
                    style = AppTypography.caption.strong,
                    color = palette.text,
                    modifier = Modifier.weight(0.36f),
                )
                Text(
                    result,
                    style = AppTypography.caption.regular,
                    color = palette.sub2,
                    modifier = Modifier.weight(0.64f),
                )
            }
        }
    }
}
