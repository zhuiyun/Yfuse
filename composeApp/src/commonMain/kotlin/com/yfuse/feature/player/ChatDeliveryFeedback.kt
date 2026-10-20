package com.yfuse.feature.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.DarkPalette
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.MotionSwap
import com.yfuse.core.designsystem.ThemeText
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.core.sync.ChatDeliveryState
import com.yfuse.core.sync.WatchChatMessage
import kotlinx.coroutines.delay

internal fun <T> chatUnreadCount(
    keys: List<T>,
    lastRead: T?,
): Int {
    val index = keys.indexOf(lastRead)
    return (if (index < 0) keys.size else keys.lastIndex - index).coerceAtLeast(0)
}

@Composable
internal fun ChatDeliveryFeedback(
    message: WatchChatMessage,
    onRetry: (String) -> Unit,
) {
    val ownsSlot =
        remember(message.clientMessageId) { message.isMine && message.deliveryState != ChatDeliveryState.Sent }
    if (!ownsSlot) return
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(message.deliveryState) {
        visible = true
        if (message.deliveryState == ChatDeliveryState.Sent) {
            delay(800)
            visible = false
        }
    }
    val still = LocalAccessibilityOptions.current.reduceMotion
    val alpha by animateFloatAsState(if (visible) 1f else 0f, Motion.tween(if (still) 0 else Motion.QUICK))
    Box(
        Modifier.height(48.dp).then(if (visible) Modifier else Modifier.clearAndSetSemantics {}).graphicsLayer {
            this.alpha =
                alpha
        },
    ) {
        MotionSwap(message.deliveryState) { state ->
            ThemeText(
                when (state) {
                    ChatDeliveryState.Pending -> "发送中…"
                    ChatDeliveryState.Failed -> "发送失败 · 点击重试"
                    ChatDeliveryState.Sent -> "✓ 已发送"
                },
                style = AppTypography.caption.medium,
                color = if (state == ChatDeliveryState.Failed) DarkPalette.error else Color.White.copy(alpha = 0.55f),
                modifier =
                    Modifier
                        .pressable(
                            enabled = state == ChatDeliveryState.Failed && message.deliveryState == state && visible,
                            onClickLabel = "重试发送",
                            onClick = { message.clientMessageId?.let(onRetry) },
                        ).touchTarget()
                        .padding(horizontal = 8.dp),
            )
        }
    }
}
