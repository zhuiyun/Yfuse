package com.yfuse.feature.player

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.ThemePreferences
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.PlayerEngine
import org.koin.core.context.GlobalContext

@Composable
actual fun PlayerLauncher(
    items: List<PlayerMediaItem>,
    startIndex: Int,
    startPositionMs: Long,
    startPlaybackRequested: Boolean,
    onLaunched: () -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(items, startIndex, startPositionMs, startPlaybackRequested) {
        if (items.isEmpty()) return@LaunchedEffect
        val koin = GlobalContext.get()
        val serverRegistry = runCatching { koin.get<ServerRegistry>() }.getOrNull()
        val localPrepared = prepareNativeLocalBluRayRoute(items, startIndex, context)
        val preparedItems = prepareNativeRemoteBluRayRoutes(localPrepared, startIndex, serverRegistry)
        PlaybackSelection.update(preparedItems.getOrNull(startIndex))
        val preferencesResult =
            runCatching {
                koin.get<ThemePreferences>()
            }.onFailure {
                AppLog.warning(
                    category = "feature.player",
                    event = "preferences_unavailable",
                    message = "Player preferences unavailable; defaults will be used",
                    throwable = it,
                )
            }
        val preferences = preferencesResult.getOrNull()
        var pendingLaunch: Intent? = null
        runCatching {
            PlayerActivity
                .intent(
                    context = context,
                    items = preparedItems,
                    startIndex = startIndex,
                    startPositionMs = startPositionMs,
                    // Keep the selected backend authoritative. Exo mounts the sidecar through
                    // Media3 and mpv mounts it after FILE_LOADED; a manual engine lock must never
                    // be rewritten merely because an external subtitle was selected.
                    engine =
                        offlineSubtitlePlaybackEngine(
                            preferred = preferences?.engine?.value ?: PlayerEngine.Exo,
                            items = preparedItems,
                        ),
                    decoder = preferences?.decoder?.value ?: com.yfuse.core.model.DecoderMode.Hardware,
                    autoNext = preferences?.autoNext?.value ?: true,
                    startPlaybackRequested = startPlaybackRequested,
                ).also { launchIntent ->
                    pendingLaunch = launchIntent
                    context.startActivity(launchIntent)
                }
        }.onSuccess {
            AppLog.info(
                category = "feature.player",
                event = "activity_launched",
                message = "Player activity launched",
                attributes =
                    mapOf(
                        "itemCount" to preparedItems.size.toString(),
                        "nativeDisc" to
                            preparedItems
                                .getOrNull(startIndex)
                                ?.url
                                ?.isYfuseNativeBluRayRoute()
                                .toString(),
                    ),
            )
            onLaunched()
        }.onFailure {
            pendingLaunch?.let(PlayerActivity::discardLaunch)
            AppLog.error(
                category = "feature.player",
                event = "activity_launch_failed",
                message = "Failed to launch player activity",
                throwable = it,
            )
        }
    }
}

internal fun offlineSubtitlePlaybackEngine(
    preferred: PlayerEngine,
    items: List<PlayerMediaItem>,
): PlayerEngine = preferred
