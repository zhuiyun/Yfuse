package com.yfuse.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.ThemeText as Text

private val watchAvatarEmoji = listOf("🍿", "🎬", "🌙", "🚀", "🐱", "🐼", "🦊", "✨")

@Composable
fun WatchAvatar(
    avatarId: Int,
    size: Dp,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val index = avatarId.coerceIn(0, watchAvatarEmoji.lastIndex)
    val colors = WatchAvatarTints[index]
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(colors.first, colors.second)))
            .then(
                if (selected) {
                    Modifier.border(2.dp, Color.White.copy(alpha = 0.92f), CircleShape)
                } else {
                    Modifier.border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(watchAvatarEmoji[index], style = sc(size.value * 0.46f, 600))
    }
}
