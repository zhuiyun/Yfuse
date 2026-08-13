package com.yfuse.feature.player

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.yfuse.core.data.PlaybackPreferences
import com.yfuse.core.data.ThemePreferences
import com.yfuse.core.data.resolveNetworkAwareQuality
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.PlayerEngine
import com.yfuse.core.network.currentPlaybackNetworkClass
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
        val preferencesResult =
            runCatching {
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
        val playbackPreferences =
            runCatching {
                GlobalContext.get().get<PlaybackPreferences>()
            }.getOrNull()
        val serverId = items.getOrNull(startIndex)?.serverId
        val preferredQuality =
            serverId
                ?.let { playbackPreferences?.rememberedQuality(it) }
                ?: preferences?.quality?.value
                ?: com.yfuse.core.model.PlaybackQuality.Auto
        val launchQuality =
            playbackPreferences?.let { policy ->
                resolveNetworkAwareQuality(
                    preferred = preferredQuality,
                    networkType = currentPlaybackNetworkClass(),
                    wifiCap = policy.wifiQualityCap.value,
                    cellularCap = policy.cellularQualityCap.value,
                    qualityLocked = policy.qualityLocked.value,
                )
            } ?: preferredQuality
        var pendingLaunch: Intent? = null
        runCatching {
            PlayerActivity
                .intent(
                    context = context,
                    items = items,
                    startIndex = startIndex,
                    startPositionMs = startPositionMs,
                    // External .srt sidecars are mounted by the Media3 engine. Native
                    // engines do not share Media3's merged subtitle source, so an offline
                    // item with a selected sidecar must launch Exo regardless of the normal
                    // streaming-engine preference.
                    engine =
                        offlineSubtitlePlaybackEngine(
                            preferred = preferences?.engine?.value ?: PlayerEngine.Exo,
                            items = items,
                        ),
                    decoder = preferences?.decoder?.value ?: com.yfuse.core.model.DecoderMode.Hardware,
                    autoNext = preferences?.autoNext?.value ?: true,
                    quality = launchQuality,
                ).also { launchIntent ->
                    pendingLaunch = launchIntent
                    context.startActivity(launchIntent)
                }
        }.onSuccess {
            AppLog.info(
                category = "feature.player",
                event = "activity_launched",
                message = "Player activity launched",
                attributes = mapOf("itemCount" to items.size.toString()),
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
): PlayerEngine = if (items.any { !it.externalSubtitleUri.isNullOrBlank() }) PlayerEngine.Exo else preferred
