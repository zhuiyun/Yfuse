package com.yfuse.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.DarkPalette
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.ThemeText as Text

/**
 * The end of an item that did not roll on into the next one: 下一集 when there is one, 重播, 返回.
 * Keys at the same size and in the same place as 继续播放, because it is the same question — what
 * happens if I touch this — asked one moment later; 下一集 leads when there is one.
 */
@Composable
internal fun PlayerEndedKeys(
    hasNext: Boolean,
    /** False for a guest whose room is driven by its host: the keys stay, dimmed and inert. */
    enabled: Boolean,
    onNext: () -> Unit,
    onReplay: () -> Unit,
    onBack: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        if (hasNext) {
            CircleControl(
                icon = AppIcons.Next,
                description = "下一集",
                size = CenterKeySize,
                iconSize = CenterKeyIconSize,
                enabled = enabled,
                filled = true,
                onClick = onNext,
            )
        }
        CircleControl(
            icon = AppIcons.Refresh,
            description = "重播",
            size = CenterKeySize,
            iconSize = CenterKeyIconSize,
            enabled = enabled,
            filled = !hasNext,
            onClick = onReplay,
        )
        CircleControl(
            icon = AppIcons.Close,
            description = "返回",
            size = CenterKeySize,
            iconSize = CenterKeyIconSize,
            onClick = onBack,
        )
    }
}

/**
 * The standing explanation for why the transport is dimmed in a room, and the only place the
 * reconnect state surfaces during playback: the room stays live and the controls stay in place, so a
 * dropped socket reads as "catching up", not as the room vanishing. A tap opens the chat.
 */
@Composable
internal fun WatchRoomNote(
    reconnecting: Boolean,
    isHost: Boolean,
    participantCount: Int,
    onOpenChat: () -> Unit,
) {
    val roomNote =
        when {
            reconnecting -> "一起看 · 重连中… · 聊天"
            !isHost -> "一起看 · 房主控制播放 · 聊天"
            else -> "一起看 · 你是房主 · $participantCount 人 · 聊天"
        }
    Text(
        roomNote,
        style = AppTypography.caption.medium,
        color = if (reconnecting) DarkPalette.onErrorContainer else Color.White.copy(alpha = 0.92f),
        modifier =
            Modifier
                .glass(
                    shape = GlassShapes.chip,
                    fill = if (reconnecting) DarkPalette.errorContainer else Color.Black.copy(alpha = 0.52f),
                    border = Color.White.copy(alpha = 0.24f),
                ).noRippleClickable(onOpenChat)
                .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}
