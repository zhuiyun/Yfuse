package com.yfuse.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun PlayerLauncher(
    items: List<PlayerMediaItem>,
    startIndex: Int,
    startPositionMs: Long,
    onLaunched: () -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(items, startIndex) {
        if (items.isEmpty()) return@LaunchedEffect
        context.startActivity(PlayerActivity.intent(context, items, startIndex, startPositionMs))
        onLaunched()
    }
}
