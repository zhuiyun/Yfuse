package com.yfuse.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.yfuse.core.data.ThemePreferences
import com.yfuse.core.logging.AppLog
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
        PlaybackSelection.update(items.getOrNull(startIndex))
        val preferencesResult = runCatching {
            GlobalContext.get().get<ThemePreferences>()
        }.onFailure {
            AppLog.warning(
                category = "feature.player",
                event = "preferences_unavailable",
                message = "Player preferences unavailable; defaults will be used",
                throwable = it,
            )
        }
        val preferences = preferencesResult.getOrNull()
        runCatching {
            context.startActivity(
                PlayerActivity.intent(
                    context = context,
                    items = items,
                    startIndex = startIndex,
                    startPositionMs = startPositionMs,
                    engine = preferences?.engine?.value ?: PlayerEngine.Exo,
                    decoder = preferences?.decoder?.value ?: com.yfuse.core.model.DecoderMode.Hardware,
                    autoNext = preferences?.autoNext?.value ?: true,
                    // Quality switching is intentionally disabled: these servers
                    // cannot sustain per-session resolution transcoding.
                    quality = com.yfuse.core.model.PlaybackQuality.Auto,
                ).addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK,
                ),
            )
        }.onSuccess {
            AppLog.info(
                category = "feature.player",
                event = "activity_launched",
                message = "Player activity launched",
                attributes = mapOf("itemCount" to items.size.toString()),
            )
            onLaunched()
        }.onFailure {
            AppLog.error(
                category = "feature.player",
                event = "activity_launch_failed",
                message = "Failed to launch player activity",
                throwable = it,
            )
        }
    }
}
