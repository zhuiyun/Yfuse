package com.yfuse.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.yfuse.core.data.ThemePreferences
import com.yfuse.core.model.PlayerEngine
import org.koin.core.context.GlobalContext

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
        val engine = runCatching {
            GlobalContext.get().get<ThemePreferences>().engine.value
        }.getOrDefault(PlayerEngine.Exo)
        context.startActivity(
            PlayerActivity.intent(context, items, startIndex, startPositionMs, engine),
        )
        onLaunched()
    }
}
