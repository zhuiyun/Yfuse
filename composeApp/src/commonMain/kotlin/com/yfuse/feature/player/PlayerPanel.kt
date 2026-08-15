package com.yfuse.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.PlayerTokens
import com.yfuse.core.designsystem.Shadows
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.shadow

/** Long-form typed/searchable panels keep a stable right-edge drawer geometry. */
internal val PlayerPanelWidth = 340.dp

/** Compact floating settings popover. Search, chat, and episode lists keep their drawers. */
internal val PlayerPopupWidth = 420.dp

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

/**
 * The function-menu shell used by playback, tracks, picture, danmaku, cast, and advanced.
 *
 * It deliberately does not fill the right edge. These are short, reversible choices, so a
 * compact floating surface keeps the picture readable and makes the relationship to the
 * bottom controls clearer. Long-form search/chat panels continue to use [PlayerSidePanel].
 */
@Composable
internal fun PlayerPopupPanel(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .noRippleClickable(onDismiss),
    )
    Column(
        modifier
            .width(PlayerPopupWidth)
            .heightIn(max = if (compact) 210.dp else 470.dp)
            .shadow(Shadows.playerSheet, AppShapes.sheet)
            .glass(
                shape = AppShapes.sheet,
                fill = PlayerTokens.drawerFillLandscape.copy(alpha = 0.94f),
                border = Color.White.copy(alpha = 0.16f),
            )
            // Taps inside the popup must not reach the dismiss catcher behind it.
            .noRippleClickable { }
            .imePadding()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.Top,
        content = content,
    )
}
