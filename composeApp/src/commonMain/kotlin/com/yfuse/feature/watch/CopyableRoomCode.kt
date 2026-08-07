package com.yfuse.feature.watch

import androidx.compose.foundation.combinedClickable
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
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.mr
import com.yfuse.core.designsystem.sc
import com.yfuse.core.util.rememberShareHandler
import kotlinx.coroutines.delay

/** A consistent room-code affordance used anywhere the full code is presented. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun CopyableRoomCode(
    roomCode: String,
    modifier: Modifier = Modifier,
    style: TextStyle = sc(24f, 800),
    color: Color = Brand.Primary,
) {
    val palette = LocalPalette.current
    val shareHandler = rememberShareHandler()
    var copied by remember(roomCode) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (!copied) return@LaunchedEffect
        delay(1_600L)
        copied = false
    }

    Column(
        modifier = modifier
            .combinedClickable(
                onClick = {},
                onLongClickLabel = "复制房间码",
                onLongClick = {
                    shareHandler.copyRoomCode(roomCode)
                    copied = true
                },
            )
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = roomCode,
            style = style,
            color = color,
            textAlign = TextAlign.Center,
        )
        Text(
            text = if (copied) "已复制房间码" else "长按复制房间码",
            style = mr(9f, 500),
            color = if (copied) Brand.Primary else palette.sub2,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}
