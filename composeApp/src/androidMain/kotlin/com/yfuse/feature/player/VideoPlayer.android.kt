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
        val preferences = runCatching {
            GlobalContext.get().get<ThemePreferences>()
        }.getOrNull()
        context.startActivity(
            PlayerActivity.intent(
                context = context,
                items = items,
                startIndex = startIndex,
                startPositionMs = startPositionMs,
                engine = preferences?.engine?.value ?: PlayerEngine.Exo,
                decoder = preferences?.decoder?.value ?: com.yfuse.core.model.DecoderMode.Hardware,
                autoNext = preferences?.autoNext?.value ?: true,
                quality = preferences?.quality?.value ?: com.yfuse.core.model.PlaybackQuality.Auto,
            ).addFlags(
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK,
            ),
        )
        onLaunched()
    }
}
