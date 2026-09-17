package com.yfuse.feature.profile

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.DialogAnimation
import com.yfuse.core.designsystem.DialogMotionHost
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalDialogAnimation
import com.yfuse.core.designsystem.LocalDialogMotionHost
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OverlayButton
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.OverlayOptionRow
import com.yfuse.core.designsystem.OverlayOptionSpacing
import com.yfuse.core.designsystem.overlayDismiss
import com.yfuse.core.designsystem.ThemeText as Text

/** 弹窗动画 — the five shipped styles, each selectable and previewable in place. */
@Composable
internal fun DialogAnimationSheet(
    selected: DialogAnimation,
    onSelect: (DialogAnimation) -> Unit,
    onDismiss: () -> Unit,
) {
    var preview by remember { mutableStateOf<DialogAnimation?>(null) }
    val previewOrigin = remember { DialogMotionHost() }
    val styles = DialogAnimation.entries
    GlassDialog(onDismiss = onDismiss) {
        val host = LocalDialogMotionHost.current
        val openPreview = {
            previewOrigin.touch = host.touch
            preview = selected
        }
        OverlayHeader(
            "弹窗动画",
            "${styles.size} 款风格，选择后立即保存，点击预览查看显示与隐藏效果",
            onClose = onDismiss,
        )
        if (LocalAccessibilityOptions.current.reduceMotion) {
            Text(
                "已开启“减少动画”，当前预览与实际弹窗均直接显示。",
                color = LocalPalette.current.sub,
                style = AppTypography.caption.regular,
            )
            Spacer(Modifier.height(OverlayOptionSpacing))
        }
        styles.forEach { animation ->
            OverlayOptionRow(
                label = animation.label,
                description = animation.description,
                selected = selected == animation,
                onClick = { onSelect(animation) },
            )
            Spacer(Modifier.height(OverlayOptionSpacing))
        }
        Spacer(Modifier.height(Dimens.space.xs))
        OverlayButton("预览：${selected.label}", onClick = openPreview)
        Spacer(Modifier.height(OverlayOptionSpacing))
        OverlayButton("完成", onClick = overlayDismiss(onDismiss))
    }
    preview?.let { animation ->
        CompositionLocalProvider(
            LocalDialogAnimation provides animation,
            LocalDialogMotionHost provides previewOrigin,
        ) {
            GlassDialog(onDismiss = { preview = null }) {
                OverlayHeader(animation.label, animation.description, onClose = { preview = null })
                Text(
                    "点击下方按钮、空白区域或返回键，查看隐藏动画。",
                    color = LocalPalette.current.text,
                    style = AppTypography.body.regular,
                )
                if (animation == DialogAnimation.MagneticDrag) {
                    Text(
                        "向下轻拉顶部短条会回弹，拉远或快速下滑会关闭。",
                        color = LocalPalette.current.sub,
                        style = AppTypography.caption.regular,
                    )
                }
                if (animation == DialogAnimation.Cascade) {
                    Spacer(Modifier.height(Dimens.space.md))
                    OverlayOptionRow("第一个选项", true, {})
                    Spacer(Modifier.height(OverlayOptionSpacing))
                    OverlayOptionRow("第二个选项", false, {})
                }
                Spacer(Modifier.height(Dimens.space.lg))
                OverlayButton("关闭预览", onClick = overlayDismiss { preview = null })
            }
        }
    }
}
