package com.yfuse.feature.player

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.DarkPalette
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.ThemeText as Text

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
