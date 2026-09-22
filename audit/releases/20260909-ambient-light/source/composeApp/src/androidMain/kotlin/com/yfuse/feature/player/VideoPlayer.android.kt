package com.yfuse.feature.player

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.arkivanov.mvikotlin.core.store.Store
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.ThemePreferences
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.PlayerEngine
import org.koin.core.context.GlobalContext

@Composable
actual fun PendingPlayerLauncher(
    store: Store<PlayerIntent, PlayerState, Nothing>,
    startPlaybackRequested: Boolean,
    onStoreTransferred: () -> Unit,
    onLaunched: () -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(store, startPlaybackRequested) {
        var launchIntent: android.content.Intent? = null
        runCatching {
            PlayerActivity
                .pendingIntent(
                    context = context,
                    store = store,
                    startPlaybackRequested = startPlaybackRequested,
                ).also { createdIntent ->
                    launchIntent = createdIntent
                    context.startActivity(createdIntent)
                }
        }.onSuccess {
            onStoreTransferred()
            AppLog.info(
                category = "feature.player",
                event = "activity_launched",
                message = "Player activity launched before playback preparation",
            )
            onLaunched()
        }.onFailure {
            launchIntent?.let(PlayerActivity::discardPendingLaunch)
            AppLog.error(
                category = "feature.player",
                event = "activity_launch_failed",
                message = "Failed to launch player activity",
                throwable = it,
            )
        }
    }
}

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
        val preferences = runCatching { koin.get<ThemePreferences>() }.getOrNull()
        var launchIntent: Intent? = null
        runCatching {
            PlayerActivity
                .intent(
                    context = context,
                    items = preparedItems,
                    startIndex = startIndex,
                    startPositionMs = startPositionMs,
                    engine =
                        offlineSubtitlePlaybackEngine(
                            preferred = preferences?.engine?.value ?: PlayerEngine.Exo,
                            items = preparedItems,
                        ),
                    decoder = preferences?.decoder?.value ?: com.yfuse.core.model.DecoderMode.Hardware,
                    autoNext = preferences?.autoNext?.value ?: true,
                    startPlaybackRequested = startPlaybackRequested,
                ).also { createdIntent ->
                    launchIntent = createdIntent
                    context.startActivity(createdIntent)
                }
        }.onSuccess {
            AppLog.info(
                category = "feature.player",
                event = "activity_launched",
                message = "Player activity launched",
                attributes = mapOf("itemCount" to preparedItems.size.toString()),
            )
            onLaunched()
        }.onFailure {
            launchIntent?.let(PlayerActivity::discardLaunch)
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
