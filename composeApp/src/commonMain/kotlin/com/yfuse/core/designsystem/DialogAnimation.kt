package com.yfuse.core.designsystem

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import kotlin.math.PI
import kotlin.math.sin

enum class DialogAnimation(
    val label: String,
    val description: String,
    val enterMillis: Int,
    val exitMillis: Int,
) {
    Lift("柔和浮起", "轻轻上浮，柔和展开", 360, 240),
    Axis("中轴展开", "从中央细缝向上下展开", 380, 260),
    Slide("底部升起", "从屏幕底部滑入，关闭时滑回", 420, 280),
    Touch("触点展开", "从点击位置展开，关闭时收回", 420, 300),
    Spring("柔性回弹", "轻微拉伸，带细腻回弹", 480, 280),
    Perspective("轻透视展开", "轻微倾斜翻起，回到正面", 440, 300),
    Hologram("全息扫描", "扫描光线经过，逐行展开玻璃面板", 480, 320),
    Fold("空间折叠", "两侧屏幕围绕中央折线展开", 460, 300),
    Energy("能量边框", "四角光线勾勒边框，内部随后展开", 500, 320),
    Layers("分层悬浮", "玻璃、标题与内容从不同深度归位", 480, 300),
    Portal("光圈展开", "从点击位置扩张光圈，收回时逆向闭合", 480, 320),
    Reconstruct("数字重构", "横向切片错位进入，快速拼成完整面板", 460, 300),
    Magnetic("磁悬停泊", "沿短弧线滑入，轻微倾斜后平稳归位", 440, 280),
    Liquid("液态成形", "圆润水滴舒展成玻璃面板，文字保持原比例", 500, 320),
    Blinds("光栅百叶", "竖向光栅依次翻开，关闭时逐条闭合", 480, 320),
    Assemble("四角汇聚", "四块玻璃从四角靠拢，接缝平稳闭合", 480, 300),
    Radar("雷达扫掠", "旋转光束揭开面板，反向扫掠收起", 500, 340),
}

val LocalDialogAnimation = staticCompositionLocalOf { DialogAnimation.Lift }

internal class DialogMotionHost {
    var origin = Offset.Zero
    var height = 0f
    var touch: Offset? = null
}

internal val LocalDialogMotionHost = staticCompositionLocalOf { DialogMotionHost() }

/** Observe without consuming: clicks, drags and scrolling keep their existing handlers. */
internal fun Modifier.trackDialogOrigin(host: DialogMotionHost): Modifier =
    onGloballyPositioned {
        host.origin = it.positionInWindow()
        host.height = it.size.height.toFloat()
    }.onPreviewKeyEvent {
        host.touch = null
        false
    }.pointerInput(host) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            host.touch = host.origin + down.position
        }
    }

internal data class DialogMotionFrame(
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val offsetY: Float = 0f,
    val rotationX: Float = 0f,
    val insetX: Float = 0f,
    val insetY: Float = 0f,
    val offsetX: Float = 0f,
    val rotationZ: Float = 0f,
)

internal fun dialogMotionFrame(
    animation: DialogAnimation,
    progress: Float,
): DialogMotionFrame {
    val p = progress.coerceIn(0f, 1f)
    if (p == 1f) return DialogMotionFrame()
    val hidden = 1f - p
    return when (animation) {
        DialogAnimation.Lift ->
            DialogMotionFrame(
                1f - 0.08f * hidden,
                1f - 0.08f * hidden,
                24f * hidden,
                insetX =
                    0.22f * hidden,
                insetY = 0.5f * hidden,
            )
        DialogAnimation.Axis -> DialogMotionFrame(scaleX = 1f - 0.04f * hidden, insetY = 0.5f * hidden)
        DialogAnimation.Slide -> DialogMotionFrame(offsetY = hidden)
        DialogAnimation.Touch -> DialogMotionFrame(scaleX = p, scaleY = p)
        DialogAnimation.Spring -> {
            val bounce = sin(PI * p).toFloat()
            DialogMotionFrame(
                1f - 0.22f * hidden + 0.1f * bounce,
                1f - 0.1f * hidden + 0.04f * bounce,
                16f * hidden - 7f * bounce,
                insetX = 0.1f * hidden,
                insetY = 0.5f * hidden * hidden,
            )
        }
        DialogAnimation.Perspective ->
            DialogMotionFrame(
                1f - 0.1f * hidden,
                1f - 0.1f * hidden,
                28f * hidden,
                -10f * hidden,
                0.08f * hidden,
                0.5f * hidden,
            )
        DialogAnimation.Layers ->
            DialogMotionFrame(
                1f - 0.06f * hidden,
                1f - 0.06f * hidden,
                12f * hidden,
                insetY = 0.5f * (1f - (p / 0.4f).coerceIn(0f, 1f)),
            )
        DialogAnimation.Magnetic ->
            DialogMotionFrame(
                scaleX = 1f - 0.05f * hidden,
                scaleY = 1f - 0.05f * hidden,
                offsetY = 26f * hidden,
                insetY = 0.5f * (1f - (p / 0.5f).coerceIn(0f, 1f)),
                offsetX = 28f * hidden * hidden - 8f * sin(PI * p).toFloat(),
                rotationZ = -3f * hidden,
            )
        DialogAnimation.Hologram, DialogAnimation.Fold, DialogAnimation.Energy,
        DialogAnimation.Portal, DialogAnimation.Reconstruct, DialogAnimation.Liquid,
        DialogAnimation.Blinds, DialogAnimation.Assemble, DialogAnimation.Radar,
        -> DialogMotionFrame()
    }
}

private class DialogPanelPosition {
    var origin = Offset.Zero
}

/** Layout stays fixed; only the material layer moves. No panel/text opacity animation. */
@Composable
internal fun Modifier.dialogMotion(
    animation: DialogAnimation,
    progress: () -> Float,
): Modifier {
    val host = LocalDialogMotionHost.current
    val anchor = remember { host.touch }
    val position = remember { DialogPanelPosition() }
    val glow = LocalAccentColors.current.accent
    return onGloballyPositioned { position.origin = it.positionInWindow() }
        .graphicsLayer {
            val frame = dialogMotionFrame(animation, progress())
            transformOrigin =
                when {
                    animation == DialogAnimation.Touch && anchor != null ->
                        TransformOrigin(
                            (anchor.x - position.origin.x) / size.width.coerceAtLeast(1f),
                            (anchor.y - position.origin.y) / size.height.coerceAtLeast(1f),
                        )
                    animation == DialogAnimation.Perspective -> TransformOrigin(0.5f, 0.85f)
                    else -> TransformOrigin.Center
                }
            scaleX = frame.scaleX
            scaleY = frame.scaleY
            translationY =
                if (animation == DialogAnimation.Slide) {
                    (host.origin.y + host.height - position.origin.y + size.height).coerceAtLeast(size.height) *
                        frame.offsetY
                } else {
                    frame.offsetY * density
                }
            rotationX = frame.rotationX
            translationX = frame.offsetX * density
            rotationZ = frame.rotationZ
            cameraDistance = 1200f * density
        }.drawWithContent {
            val entered = progress().coerceIn(0f, 1f)
            // Both endpoints bypass effects, including when reduced motion snaps to them.
            if (entered <= 0f) return@drawWithContent
            if (entered >= 1f) {
                drawContent()
                return@drawWithContent
            }
            if (drawSciFiDialog(animation, entered, anchor?.minus(position.origin), glow)) {
                return@drawWithContent
            }
            val frame = dialogMotionFrame(animation, progress())
            clipRect(
                left = size.width * frame.insetX,
                top = size.height * frame.insetY,
                right = size.width * (1f - frame.insetX),
                bottom = size.height * (1f - frame.insetY),
            ) { this@drawWithContent.drawContent() }
        }
}
