package com.yfuse.feature.watch

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.core.util.rememberShareHandler
import kotlinx.coroutines.delay

/** A consistent room-code affordance used anywhere the full code is presented. */
@Composable
fun CopyableRoomCode(
    roomCode: String,
    modifier: Modifier = Modifier,
    style: TextStyle = AppTypography.display.strong,
    color: Color? = null,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val resolvedColor = color ?: accent.accent
    val shareHandler = rememberShareHandler()
    var copied by remember(roomCode) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (!copied) return@LaunchedEffect
        delay(1_600L)
        copied = false
    }
    val copyCode = {
        shareHandler.copyRoomCode(roomCode)
        copied = true
    }

    Column(
        modifier = modifier
            .pressable(
                onClickLabel = "复制房间码",
                onLongClickLabel = "复制房间码",
                onLongClick = copyCode,
                onClick = copyCode,
            )
            .touchTarget()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = roomCode,
            style = style,
            color = resolvedColor,
            textAlign = TextAlign.Center,
        )
        Text(
            text = if (copied) "已复制房间码" else "点击或长按复制房间码",
            style = AppTypography.caption.medium,
            color = if (copied) accent.accent else palette.sub2,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}
