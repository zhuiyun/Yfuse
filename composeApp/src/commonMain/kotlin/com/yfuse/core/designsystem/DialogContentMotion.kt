package com.yfuse.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.dp

/**
 * The running entrance a dialog's own content reads, whatever plate it arrived on.
 *
 * The plate's geometry belongs to the style — see the sci-fi apertures — but the header,
 * the options and the actions inside it are the same parts in every dialog, so their
 * stagger lives here rather than beside any one style's drawing code.
 */
@Stable
internal class DialogContentMotion(
    val animation: DialogAnimation,
    val progress: () -> Float,
)

internal val LocalDialogContentMotion = staticCompositionLocalOf<DialogContentMotion?> { null }

internal enum class DialogElementRole { Header, Option, Action }

private class DialogElementPosition {
    var top = 0f
    var parentHeight = 1f
}

/** Delays are bounded, so the bottom of a long list never holds the whole dialog open. */
@Composable
internal fun Modifier.dialogElementMotion(role: DialogElementRole): Modifier {
    val motion = LocalDialogContentMotion.current ?: return this
    if (motion.animation != DialogAnimation.Cascade) return this
    val position = remember { DialogElementPosition() }
    val delay =
        remember(role) {
            {
                when (role) {
                    DialogElementRole.Header -> 0.02f
                    DialogElementRole.Option -> 0.08f + 0.12f * (position.top / position.parentHeight).coerceIn(0f, 1f)
                    DialogElementRole.Action -> 0.2f
                }
            }
        }
    return onGloballyPositioned {
        position.top = it.positionInParent().y
        position.parentHeight = (it.parentLayoutCoordinates?.size?.height ?: it.size.height).toFloat().coerceAtLeast(1f)
    }.graphicsLayer {
        val entered = dialogStage(motion.progress(), delay())
        val bodyShift = 10.dp.toPx() * (1f - dialogStage(motion.progress(), 0.1f))
        translationY = (if (role == DialogElementRole.Header) 8.dp else 14.dp).toPx() * (1f - entered) - bodyShift
    }.drawWithContent {
        val entered = dialogStage(motion.progress(), delay())
        if (entered >= 1f) {
            drawContent()
        } else if (entered > 0f) {
            clipRect(bottom = size.height * entered) { this@drawWithContent.drawContent() }
        }
    }.graphicsLayer()
}
