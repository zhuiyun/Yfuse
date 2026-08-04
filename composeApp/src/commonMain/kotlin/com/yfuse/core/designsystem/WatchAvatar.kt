package com.yfuse.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val watchAvatarEmoji = listOf("🍿", "🎬", "🌙", "🚀", "🐱", "🐼", "🦊", "✨")
private val watchAvatarColors = listOf(
    Color(0xFF7C4DFF) to Color(0xFFB388FF),
    Color(0xFFFF5252) to Color(0xFFFF8A80),
    Color(0xFF536DFE) to Color(0xFF82B1FF),
    Color(0xFF00BFA5) to Color(0xFF64FFDA),
    Color(0xFFFF6D00) to Color(0xFFFFAB40),
    Color(0xFF455A64) to Color(0xFF90A4AE),
    Color(0xFFD81B60) to Color(0xFFFF80AB),
    Color(0xFF6A1B9A) to Color(0xFFE040FB),
)

@Composable
fun WatchAvatar(
    avatarId: Int,
    size: Dp,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val index = avatarId.coerceIn(0, watchAvatarEmoji.lastIndex)
    val colors = watchAvatarColors[index]
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
