package com.yfuse.core.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Long enough to read a short Chinese sentence, short enough not to sit in the way. */
private const val TOAST_MS = 2_600L

/**
 * 一次性提示 — 「已加入收藏」, 「媒体库中没有此资源」 and the rest.
 *
 * These used to be an ordinary item inside the page's own scrolling list, which meant a
 * confirmation for a button press somewhere else on screen *pushed the whole page down*
 * and then let it snap back. They also never went away on their own: the text sat there
 * until some other action happened to replace it.
 *
 * Floating it over the content fixes both. It arrives from below, holds for [TOAST_MS] and
 * asks to be cleared. Under 减弱动态效果 it appears and leaves without the slide.
 */
@Composable
fun BoxScope.ActionToast(
    message: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Brand.Primary,
) {
    // The exit animation outlives the state that caused it, so the last text is kept to
    // draw during the fade — otherwise the toast blanks a frame before it leaves.
    var lastMessage by remember { mutableStateOf(message.orEmpty()) }
    /**
     * Bumped every time a message is posted, and part of the timer's key.
     *
     * Keying the countdown on the text alone meant the same message twice in a row was one
     * toast: 「已加入收藏」 posted while the first 「已加入收藏」 was still on screen did not
     * restart the effect, so the second confirmation inherited whatever was left of the
     * first one's four seconds and could vanish almost immediately.
     */
    var posting by remember { mutableIntStateOf(0) }
    LaunchedEffect(message) {
        if (message != null) posting++
    }
    LaunchedEffect(posting) {
        val current = message ?: return@LaunchedEffect
        lastMessage = current
        delay(TOAST_MS)
        onDismiss()
    }

    val duration = if (LocalAccessibilityOptions.current.reduceMotion) 0 else Motion.TAB
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn(tween(duration, easing = Motion.Curve)) +
            slideInVertically(tween(duration, easing = Motion.Curve)) { it / 2 },
        exit = fadeOut(tween(duration, easing = Motion.Curve)) +
            slideOutVertically(tween(duration, easing = Motion.Curve)) { it / 2 },
        modifier = modifier.align(Alignment.BottomCenter),
    ) {
        Text(
            lastMessage,
            style = sc(11.5f, 650),
            color = accent,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(horizontal = Dimens.pageHorizontal)
                // A notice the user has already read should go when they say so, not when
                // its timer says so. It sits over the bottom of the page — the busiest part
                // of the screen — so waiting out the full 2.6s to reach what is underneath
                // was the one thing it could get wrong.
                .pressable(onClickLabel = "关闭提示", onClick = onDismiss)
                .shadow(Shadows.tabBar, GlassShapes.chip)
                .solidGlass(
                    shape = GlassShapes.chip,
                    fill = accent.copy(alpha = 0.10f),
                    border = accent.copy(alpha = 0.24f),
                )
                .padding(horizontal = 16.dp, vertical = 11.dp),
        )
    }
}
