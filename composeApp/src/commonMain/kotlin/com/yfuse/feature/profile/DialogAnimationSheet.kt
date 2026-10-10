package com.yfuse.feature.profile

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.CALM_DURATION_SCALE
import com.yfuse.core.designsystem.DialogAnimation
import com.yfuse.core.designsystem.DialogContentMotion
import com.yfuse.core.designsystem.DialogElementRole
import com.yfuse.core.designsystem.DialogMotionHost
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalDialogAnimation
import com.yfuse.core.designsystem.LocalDialogContentMotion
import com.yfuse.core.designsystem.LocalDialogMotionHost
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.LocalRouteVisible
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.OverlayButton
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.OverlayOptionRow
import com.yfuse.core.designsystem.calmMotion
import com.yfuse.core.designsystem.dialogElementMotion
import com.yfuse.core.designsystem.dialogHeaderMotion
import com.yfuse.core.designsystem.dialogInteriorMotion
import com.yfuse.core.designsystem.dialogMotion
import com.yfuse.core.designsystem.dialogPosterSource
import com.yfuse.core.designsystem.mutedGlassPanel
import com.yfuse.core.designsystem.overlayDismiss
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.trackDialogOrigin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.yfuse.core.designsystem.ThemeText as Text

@Composable
internal fun DialogAnimationSheet(
    selected: DialogAnimation,
    onSelect: (DialogAnimation) -> Unit,
    onDismiss: () -> Unit,
) {
    var preview by remember { mutableStateOf<DialogAnimation?>(null) }
    val previewOrigin = remember { DialogMotionHost() }
    val styles = DialogAnimation.entries
    val highlighted = selected
    GlassDialog(onDismiss = onDismiss) {
        val host = LocalDialogMotionHost.current
        val openPreview = {
            previewOrigin.touch = host.recentTouch
            previewOrigin.poster = host.poster
            preview = highlighted
        }
        OverlayHeader(
            "弹窗动画",
            "${styles.size} 款风格，左右滑动卡片，点击选择并立即保存",
            onClose = onDismiss,
        )
        if (LocalAccessibilityOptions.current.reduceMotion) {
            Text("已开启“减少动画”，当前预览与实际弹窗均直接显示。", color = LocalPalette.current.sub, style = AppTypography.caption.regular)
        }
        Spacer(Modifier.height(12.dp))
        AnimationCardList(
            options = styles,
            selected = highlighted,
            key = { "dialog-animation-${it.name}" },
            label = { it.label },
            description = { it.description },
            category = { it.categoryLabel() },
            onSelect = onSelect,
        ) { animation, active ->
            DialogAnimationCardPreview(animation, active && preview == null)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "${highlighted.label} · ${highlighted.description}",
            color = LocalPalette.current.sub,
            style = AppTypography.caption.regular,
        )
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

private fun DialogAnimation.categoryLabel(): String =
    when {
        ordinal < DialogAnimation.Hologram.ordinal -> "基础动效"
        ordinal < DialogAnimation.Magnetic.ordinal -> "科幻动效"
        ordinal < DialogAnimation.Ribbon.ordinal -> "材质与空间"
        ordinal < DialogAnimation.Bloom.ordinal -> "流动与韵律"
        ordinal < DialogAnimation.PosterMorph.ordinal -> "轻巧与趣味"
        ordinal < DialogAnimation.PaperPlane.ordinal -> "交互与细节"
        ordinal < DialogAnimation.Envelope.ordinal -> "趣味小物"
        else -> "奇想动效"
    }

/** Uses the real dialog geometry, masks and content timing inside a clipped miniature stage. */
@Composable
private fun DialogAnimationCardPreview(
    animation: DialogAnimation,
    active: Boolean,
) {
    val moving = active && LocalRouteVisible.current && !LocalAccessibilityOptions.current.reduceMotion
    val durationScale = if (calmMotion()) CALM_DURATION_SCALE else 1f
    val progress = remember(animation) { Animatable(1f) }
    val frame = remember(progress) { { progress.value } }
    val origin = remember { DialogMotionHost() }
    val contentMotion = remember(animation, frame) { DialogContentMotion(animation, frame) }
    LaunchedEffect(animation, moving, durationScale) {
        if (!moving) {
            progress.snapTo(1f)
            return@LaunchedEffect
        }
        progress.snapTo(0f)
        while (isActive) {
            progress.animateTo(
                1f,
                tween((animation.enterMillis * durationScale).toInt(), easing = Motion.Dialog.EnterCurve),
            )
            delay(900)
            progress.animateTo(
                0f,
                tween((animation.exitMillis * durationScale).toInt(), easing = Motion.Dialog.ExitCurve),
            )
            delay(300)
        }
    }
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    CompositionLocalProvider(
        LocalDialogMotionHost provides origin,
        LocalDialogContentMotion provides contentMotion,
    ) {
        Box(Modifier.size(132.dp, 108.dp).trackDialogOrigin(origin), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .size(104.dp, 76.dp)
                    .dialogMotion(animation, progress = frame)
                    .mutedGlassPanel(AppShapes.thumb, samplePage = false)
                    .dialogInteriorMotion(animation, frame)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    Modifier
                        .dialogHeaderMotion()
                        .fillMaxWidth(0.6f)
                        .height(6.dp)
                        .clip(AppShapes.pill)
                        .background(accent.accent),
                )
                Box(
                    Modifier
                        .dialogElementMotion(DialogElementRole.Option)
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(AppShapes.pill)
                        .background(palette.sub.copy(alpha = 0.4f)),
                )
                Box(
                    Modifier
                        .dialogElementMotion(DialogElementRole.Option)
                        .fillMaxWidth(0.8f)
                        .height(5.dp)
                        .clip(AppShapes.pill)
                        .background(palette.sub.copy(alpha = 0.25f)),
                )
                Box(
                    Modifier
                        .dialogElementMotion(DialogElementRole.Action)
                        .fillMaxWidth(0.45f)
                        .height(10.dp)
                        .clip(AppShapes.pill)
                        .background(accent.container),
                )
            }
        }
    }
}
