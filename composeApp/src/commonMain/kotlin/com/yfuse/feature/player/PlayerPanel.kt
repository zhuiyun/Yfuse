package com.yfuse.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.PlayerTokens
import com.yfuse.core.designsystem.Shadows
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.shadow

/**
 * One geometry for every panel the player opens.
 *
 * There used to be three. 设置 was a 248dp card floating 84dp off the bottom-right corner,
 * 搜索弹幕 and 房间聊天 were full-height 340dp drawers on the right edge — so the same
 * gesture, from the same row of chips, produced a panel of a different width, in a
 * different place, with a different corner radius, depending on which chip was tapped. The
 * settings panel also capped its own list at 210dp and scrolled inside that, which on the
 * 音轨 tab meant scrolling a short window inside a screen that was mostly empty picture.
 *
 * The drawer geometry won because it is the one that survives a long list, and because the
 * right edge is where the chips that open these already are.
 */
internal val PlayerPanelWidth = 340.dp

/** Rounded on the leading edge only; the panel is attached to the screen's right edge. */
internal val PlayerPanelShape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp)

private val PlayerPanelBorder = Color.White.copy(alpha = 0.24f)

/**
 * The shared shell: a dismiss catcher over the picture, and the drawer itself.
 *
 * [dim] is the one deliberate difference between them. A list of choices about the picture
 * should not dim the picture it is describing, while a panel being *typed* into — 搜索弹幕,
 * 房间聊天 — has taken the screen over and says so.
 */
@Composable
internal fun PlayerSidePanel(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dim: Boolean = false,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .then(if (dim) Modifier.background(Color.Black.copy(alpha = 0.4f)) else Modifier)
            .noRippleClickable(onDismiss),
    )
    Column(
        modifier
            .fillMaxHeight()
            .width(PlayerPanelWidth)
            .shadow(Shadows.playerSheet, PlayerPanelShape)
            .glass(
                shape = PlayerPanelShape,
                fill = PlayerTokens.drawerFillLandscape,
                border = PlayerPanelBorder,
            )
            // Taps inside the panel must not reach the catcher behind it.
            .noRippleClickable { }
            .imePadding()
            .padding(horizontal = 14.dp, vertical = 16.dp),
        verticalArrangement = verticalArrangement,
        content = content,
    )
}
