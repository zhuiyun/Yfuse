package com.yfuse.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OverlayHeader

/** A permanent, accessible explanation of the picture-level gestures and their alternatives. */
@Composable
internal fun PlayerGestureHelpOverlay(onDismiss: () -> Unit) {
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
            rows =
                listOf(
                    "单击画面" to "显示或隐藏控制层",
                    "双击左侧 / 右侧" to "快退 / 快进 10 秒；也可拖动进度条",
                    "双击中间" to "播放或暂停；也可使用底部播放按钮",
                    "长按左半屏 / 右半屏" to "连续快退 / 快进；松手确认位置",
                    "横向滑动" to "预览并定位；也可使用可调进度条",
                    "左半屏上下滑" to "调节亮度；也可使用系统亮度设置",
                    "右半屏上下滑" to "调节音量；也可使用音量键或音量滑杆",
                ),
        )
        GestureHelpSection(
            title = "辅助操作",
            rows =
                listOf(
                    "键盘方向键" to "聚焦进度条或音量滑杆后逐级调节",
                    "返回键" to "先关闭当前面板，再退出播放器",
                ),
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