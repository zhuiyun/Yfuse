package com.yfuse.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.DialogAnimation
import com.yfuse.core.designsystem.DialogMotionHost
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalDialogAnimation
import com.yfuse.core.designsystem.LocalDialogAnimationLab
import com.yfuse.core.designsystem.LocalDialogMotionHost
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OverlayButton
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.OverlayOptionRow
import com.yfuse.core.designsystem.dialogPosterSource
import com.yfuse.core.designsystem.motionItemsIndexed
import com.yfuse.core.designsystem.overlayDismiss
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.ThemeText as Text

/**
 * The three styles the app ships with. 柔和浮起 is the one polished way in; 触点展开 and 底部升起 are
 * kept because they answer questions the default cannot — where the dialog came from, and a panel
 * that belongs to the bottom edge.
 */
private val ShippedDialogAnimations =
    listOf(
        DialogAnimation.Lift,
        DialogAnimation.Touch,
        DialogAnimation.Slide,
    )

@Composable
internal fun DialogAnimationSheet(
    selected: DialogAnimation,
    onSelect: (DialogAnimation) -> Unit,
    onDismiss: () -> Unit,
) {
    var preview by remember { mutableStateOf<DialogAnimation?>(null) }
    val previewOrigin = remember { DialogMotionHost() }
    // 43 styles were how the one we kept got found; offering all of them is not the same thing as
    // having built them. The lab switch puts the whole set back for motion review.
    val lab = LocalDialogAnimationLab.current
    val styles = if (lab) DialogAnimation.entries else ShippedDialogAnimations
    // A selection made in the lab is left exactly where it is — the app still renders it. The sheet
    // only declines to point at a row it is not showing.
    val highlighted = if (selected in styles) selected else DialogAnimation.Lift
    GlassDialog(onDismiss = onDismiss, scrollable = false) {
        val host = LocalDialogMotionHost.current
        val openPreview = {
            previewOrigin.touch = host.touch
            previewOrigin.poster = host.poster
            preview = highlighted
        }
        OverlayHeader(
            "弹窗动画",
            "${styles.size} 款风格，选择后立即保存，点击预览查看显示与隐藏效果",
            onClose = onDismiss,
        )
        if (LocalAccessibilityOptions.current.reduceMotion) {
            Text("已开启“减少动画”，当前预览与实际弹窗均直接显示。", color = LocalPalette.current.sub, style = AppTypography.caption.regular)
        }
        LazyColumn(Modifier.weight(1f, fill = false)) {
            motionItemsIndexed(styles, key = { _, animation -> animation.name }) { index, animation ->
                if (index == 0 ||
                    animation == DialogAnimation.Hologram ||
                    animation == DialogAnimation.Magnetic ||
                    animation == DialogAnimation.Ribbon ||
                    animation == DialogAnimation.Bloom ||
                    animation == DialogAnimation.PosterMorph ||
                    animation == DialogAnimation.PaperPlane ||
                    animation == DialogAnimation.Envelope
                ) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        when {
                            index == 0 -> "基础动效"
                            animation == DialogAnimation.Hologram -> "科幻动效"
                            animation == DialogAnimation.Ribbon -> "流动与韵律"
                            animation == DialogAnimation.Bloom -> "轻巧与趣味"
                            animation == DialogAnimation.PosterMorph -> "交互与细节"
                            animation == DialogAnimation.PaperPlane -> "趣味小物"
                            animation == DialogAnimation.Envelope -> "奇想动效"
                            else -> "材质与空间"
                        },
                        color = LocalPalette.current.sub,
                        style = AppTypography.caption.strong,
                    )
                }
                Spacer(Modifier.height(8.dp))
                OverlayOptionRow(
                    label = animation.label,
                    description = animation.description,
                    selected = highlighted == animation,
                    onClick = { onSelect(animation) },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        if (highlighted == DialogAnimation.PosterMorph) {
            val accent = LocalAccentColors.current
            CompositionLocalProvider(LocalDialogAnimation provides highlighted) {
                Box(
                    Modifier
                        .size(70.dp, 92.dp)
                        .dialogPosterSource()
                        .clip(GlassShapes.poster)
                        .background(Brush.verticalGradient(listOf(accent.accent, accent.container)))
                        .pressable(onClick = openPreview),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("海报\n预览", color = accent.onAccent, style = AppTypography.caption.strong)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        OverlayButton("预览：${highlighted.label}", onClick = openPreview)
        OverlayButton("完成", onClick = overlayDismiss(onDismiss))
    }
    preview?.let { animation ->
        CompositionLocalProvider(
            LocalDialogAnimation provides animation,
            LocalDialogMotionHost provides previewOrigin,
        ) {
            GlassDialog(onDismiss = { preview = null }) {
                OverlayHeader(animation.label, animation.description, onClose = { preview = null })
                Text("点击下方按钮、空白区域或返回键，查看隐藏动画。", color = LocalPalette.current.text, style = AppTypography.body.regular)
                if (animation == DialogAnimation.MagneticDrag) {
                    Text(
                        "向下轻拉顶部短条会回弹，拉远或快速下滑会关闭。",
                        color = LocalPalette.current.sub,
                        style = AppTypography.caption.regular,
                    )
                }
                if (animation == DialogAnimation.Cascade) {
                    Spacer(Modifier.height(12.dp))
                    OverlayOptionRow("第一个选项", true, {})
                    Spacer(Modifier.height(8.dp))
                    OverlayOptionRow("第二个选项", false, {})
                }
                Spacer(Modifier.height(18.dp))
                OverlayButton("关闭预览", onClick = overlayDismiss { preview = null })
            }
        }
    }
}
