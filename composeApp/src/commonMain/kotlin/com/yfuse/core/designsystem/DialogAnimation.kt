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
import androidx.compose.ui.unit.dp

/**
 * How a dialog arrives and leaves.
 *
 * Five, down from forty-three. The ones that stayed each answer a different question — a
 * quiet lift for the everyday sheet, a slide for anything that reads as a drawer, a touch
 * origin for menus that belong to a control, a drag handle for panels that are pushed away,
 * and a content relay for long option lists — and none of them needs a mask, a shader or a
 * second draw pass. A name persisted from before the cut falls back to [Lift]; see
 * `ThemePreferences`.
 *
 * Entries only ever append: the choice persists by name.
 */
enum class DialogAnimation(
    val label: String,
    val description: String,
    val enterMillis: Int,
    val exitMillis: Int,
) {
    Lift("柔和浮起", "轻轻上浮，柔和展开", Motion.Dialog.ENTER_QUICK, Motion.Dialog.EXIT_QUICK),
    Slide("底部升起", "从屏幕底部滑入，关闭时滑回", Motion.Dialog.ENTER_EXTENDED, Motion.Dialog.EXIT_EMPHASIZED),
    Touch("触点展开", "从点击位置展开，关闭时收回", Motion.Dialog.ENTER_EXTENDED, Motion.Dialog.EXIT_EXTENDED),
    MagneticDrag("磁吸归位", "向下拖动弹窗，轻拉回弹，拉远后顺势关闭", Motion.Dialog.ENTER_STANDARD, Motion.Dialog.EXIT_QUICK),
    Cascade("内容接力", "面板先落定，标题、选项与按钮依次轻盈入场", Motion.Dialog.ENTER_EXTENDED, Motion.Dialog.EXIT_STANDARD),
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

/** Normalized progress shared by the staged dialog masks and transforms. */
internal fun dialogStage(
    progress: Float,
    start: Float,
    end: Float,
): Float = ((progress - start) / (end - start)).coerceIn(0f, 1f)

/** [dialogStage] with the end pinned at 1: how far into its own window a delayed part is. */
internal fun dialogStage(
    progress: Float,
    delay: Float,
): Float = ((progress - delay) / (1f - delay)).coerceIn(0f, 1f)

internal data class DialogMotionFrame(
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val offsetY: Float = 0f,
    val insetX: Float = 0f,
    val insetY: Float = 0f,
)

private val RestingDialogMotionFrame = DialogMotionFrame()

/** Layer properties and the reveal mask share one immutable geometry result for each progress. */
internal class DialogMotionFrameCache(
    private val animation: DialogAnimation,
) {
    private var lastProgress = Float.NaN
    private var lastFrame = RestingDialogMotionFrame

    fun frame(progress: Float): DialogMotionFrame {
        val p = progress.coerceIn(0f, 1f)
        if (p != lastProgress) {
            lastProgress = p
            lastFrame = dialogMotionFrame(animation, p)
        }
        return lastFrame
    }
}

internal fun dialogMotionFrame(
    animation: DialogAnimation,
    progress: Float,
): DialogMotionFrame {
    val p = progress.coerceIn(0f, 1f)
    if (p == 1f) return RestingDialogMotionFrame
    val hidden = 1f - p
    return when (animation) {
        DialogAnimation.Lift, DialogAnimation.Cascade ->
            DialogMotionFrame(
                scaleX = 1f - 0.08f * hidden,
                scaleY = 1f - 0.08f * hidden,
                offsetY = 24f * hidden,
                insetX = 0.22f * hidden,
                insetY = 0.5f * hidden,
            )
        DialogAnimation.Slide -> DialogMotionFrame(offsetY = hidden)
        DialogAnimation.Touch -> DialogMotionFrame(scaleX = p, scaleY = p)
        DialogAnimation.MagneticDrag -> DialogMotionFrame(offsetY = 24f * hidden, insetY = 0.5f * hidden * hidden)
    }
}

private class DialogPanelPosition {
    var origin = Offset.Zero
}

/** Layout stays fixed; only the material layer moves. No panel/text opacity animation. */
@Composable
internal fun Modifier.dialogMotion(
    animation: DialogAnimation,
    drag: DialogDragState? = null,
    progress: () -> Float,
): Modifier {
    val host = LocalDialogMotionHost.current
    val anchor = remember { host.touch }
    val position = remember { DialogPanelPosition() }
    val frames = remember(animation) { DialogMotionFrameCache(animation) }
    val layerOnly = animation == DialogAnimation.Slide || animation == DialogAnimation.Touch
    // A panel pushed away leaves along the finger, not along its style. Composing the two put the
    // flight on top of an exit that was already travelling — 底部升起 went the distance twice.
    // 磁吸归位 is the one style authored around this gesture, so its own exit is the one that stays.
    val neutralDragExit = animation != DialogAnimation.MagneticDrag
    val transformed =
        onGloballyPositioned { position.origin = it.positionInWindow() }
            .graphicsLayer {
                val entered = progress().coerceIn(0f, 1f)
                val flung = drag != null && drag.dismissedByDrag && neutralDragExit
                val frame = if (flung) RestingDialogMotionFrame else frames.frame(entered)
                // Binary endpoint visibility requires no translucent offscreen layer during motion.
                // The flight is the exception: it is a fade, so that the panel is gone before the
                // travel would have to be long enough to clear the screen on its own.
                alpha =
                    when {
                        flung -> entered
                        layerOnly && entered <= 0f -> 0f
                        else -> 1f
                    }
                transformOrigin =
                    if (animation == DialogAnimation.Touch && anchor != null) {
                        TransformOrigin(
                            (anchor.x - position.origin.x) / size.width.coerceAtLeast(1f),
                            (anchor.y - position.origin.y) / size.height.coerceAtLeast(1f),
                        )
                    } else {
                        TransformOrigin.Center
                    }
                scaleX = frame.scaleX
                scaleY = frame.scaleY
                translationY =
                    if (animation == DialogAnimation.Slide && !flung) {
                        (host.origin.y + host.height - position.origin.y + size.height).coerceAtLeast(size.height) *
                            frame.offsetY
                    } else {
                        frame.offsetY * density
                    }
                if (drag != null) {
                    translationY += drag.offset
                    if (drag.dismissedByDrag) {
                        translationY +=
                            if (flung) {
                                size.height * 0.3f * (1f - entered)
                            } else {
                                (host.height + size.height) * (1f - entered)
                            }
                    }
                    val stretch = (drag.offset / size.height.coerceAtLeast(1f)).coerceIn(0f, 1f)
                    scaleX *= 1f - 0.035f * stretch
                    scaleY *= 1f + 0.015f * stretch
                }
            }
    // A pure layer transform reuses its recorded content without invalidating a draw modifier.
    if (layerOnly) return transformed
    return transformed
        .drawWithContent {
            val entered = progress().coerceIn(0f, 1f)
            // Both endpoints bypass effects, including when reduced motion snaps to them.
            if (entered <= 0f) return@drawWithContent
            // A panel on its way out under the finger draws whole.
            if (entered >= 1f || (drag != null && drag.dismissedByDrag && neutralDragExit)) {
                drawContent()
                return@drawWithContent
            }
            val frame = frames.frame(entered)
            clipRect(
                left = size.width * frame.insetX,
                top = size.height * frame.insetY,
                right = size.width * (1f - frame.insetX),
                bottom = size.height * (1f - frame.insetY),
            ) { this@drawWithContent.drawContent() }
        }.graphicsLayer()
}

/** 内容接力 lifts the whole interior a little behind the plate; every other style leaves it be. */
internal fun Modifier.dialogInteriorMotion(
    animation: DialogAnimation,
    progress: () -> Float,
): Modifier {
    if (animation != DialogAnimation.Cascade) return this
    return graphicsLayer { translationY = 10.dp.toPx() * (1f - dialogStage(progress(), 0.1f)) }
}
