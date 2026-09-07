package com.yfuse.feature.profile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.DialogAnimation
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalDialogAnimation
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OverlayButton
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.OverlayOptionRow
import com.yfuse.core.designsystem.overlayDismiss

@Composable
internal fun DialogAnimationSheet(
    selected: DialogAnimation,
    onSelect: (DialogAnimation) -> Unit,
    onDismiss: () -> Unit,
) {
    var preview by remember { mutableStateOf<DialogAnimation?>(null) }
    GlassDialog(onDismiss = onDismiss, scrollable = false) {
        OverlayHeader("弹窗动画", "选择后立即保存，点击预览查看显示与隐藏效果", onClose = onDismiss)
        if (LocalAccessibilityOptions.current.reduceMotion) {
            Text("已开启“减少动画”，当前预览与实际弹窗均直接显示。", color = LocalPalette.current.sub, style = AppTypography.caption.regular)
        }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            DialogAnimation.entries.forEachIndexed { index, animation ->
                if (index == 0 || animation == DialogAnimation.Hologram) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (index == 0) "基础动效" else "科幻动效",
                        color = LocalPalette.current.sub,
                        style = AppTypography.caption.strong,
                    )
                }
                Spacer(Modifier.height(8.dp))
                OverlayOptionRow(
                    label = animation.label,
                    description = animation.description,
                    selected = selected == animation,
                    onClick = { onSelect(animation) },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        OverlayButton("预览：${selected.label}", onClick = { preview = selected })
        OverlayButton("完成", onClick = overlayDismiss(onDismiss))
    }
    preview?.let { animation ->
        CompositionLocalProvider(LocalDialogAnimation provides animation) {
            GlassDialog(onDismiss = { preview = null }) {
                OverlayHeader(animation.label, animation.description, onClose = { preview = null })
                Text("点击下方按钮、空白区域或返回键，查看隐藏动画。", color = LocalPalette.current.text, style = AppTypography.body.regular)
                Spacer(Modifier.height(18.dp))
                OverlayButton("关闭预览", onClick = overlayDismiss { preview = null })
            }
        }
    }
}
