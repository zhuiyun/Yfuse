package com.yfuse.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.yfuse.core.data.PlaybackPreferences
import com.yfuse.core.designsystem.AmbientLight
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.rememberAmbientLight
import com.yfuse.core.designsystem.rememberDominantColor
import com.yfuse.core.designsystem.toneAmbientLight

internal data class PlayerAmbientBinding(
    val enabled: Boolean,
    val sampler: AmbientFrameSampler,
    val videoSize: IntSize,
    val light: State<AmbientLight>,
    val onContainerSize: (IntSize) -> Unit,
    val onChromeVisible: (Boolean) -> Unit,
)

/** Owns ambient sampling, route visibility and pressure fallback for one player composition. */
@Composable
internal fun rememberPlayerAmbient(
    playbackPreferences: PlaybackPreferences,
    engine: VideoEngine,
    currentItem: PlayerMediaItem?,
    state: PlaybackState,
    scaleMode: VideoScaleMode,
    inPictureInPicture: Boolean,
    ambientPowerLimited: Boolean,
): PlayerAmbientBinding {
    // 氛围光. Live frames are read only when the light is on, motion is not reduced, the source is
    // not DRM-protected and the picture is actually rendering; otherwise the letterbox takes a
    // still glow from this item's artwork, and switching the light off paints nothing at all.
    val ambientLightEnabled by playbackPreferences.ambientLight.collectAsState()
    val ambientSampler = remember { AmbientFrameSampler() }
    var ambientContainer by remember { mutableStateOf(IntSize.Zero) }
    var ambientChromeVisible by remember { mutableStateOf(false) }
    val ambientVideoSize =
        IntSize(
            state.diagnostics.videoWidth.takeIf { it > 0 } ?: currentItem?.activeVersion?.sourceWidth ?: 0,
            state.videoHeight.takeIf { it > 0 } ?: currentItem?.activeVersion?.sourceHeight ?: 0,
        )
    val ambientPictureSize = core2SurfaceSize(ambientContainer, ambientVideoSize, scaleMode)
    val ambientGuard = with(LocalDensity.current) { 1.dp.roundToPx() }
    val ambientForeground = rememberAmbientRouteVisible()
    val ambientNeeded =
        ambientLightEnabled &&
            ambientForeground &&
            !inPictureInPicture &&
            (ambientChromeVisible || ambientLightHasVisibleBars(ambientContainer, ambientPictureSize, ambientGuard))
    val ambientProtected =
        currentItem?.let { item ->
            item.drmConfiguration != null || item.activeVersion?.drmConfiguration != null
        } == true
    val ambientLive =
        ambientNeeded &&
            !ambientPowerLimited &&
            !LocalAccessibilityOptions.current.reduceMotion &&
            !ambientProtected &&
            !inPictureInPicture &&
            state.videoHeight > 0 &&
            state.diagnostics.effectiveVideoReadiness == PlaybackOutputReadiness.Rendering
    ambientSampler.Collect(
        active = ambientLive,
        playing = state.playing && !state.buffering,
        pausedPositionMs = state.positionMs,
        contentKey = listOf(engine, currentItem?.serverId, currentItem?.id, currentItem?.versionId),
    )
    val ambientSampled by ambientSampler.light.collectAsState()
    val ambientArtwork =
        rememberDominantColor(
            url = (currentItem?.stillUrl ?: currentItem?.posterUrl).takeIf { ambientNeeded },
            fallback = Color.Black,
        )
    val ambientFallback =
        remember(ambientArtwork, ambientPowerLimited) {
            val color = toneAmbientLight(ambientArtwork)
            AmbientLight.uniform(if (ambientPowerLimited) lerp(Color.Black, color, 0.5f) else color)
        }
    val ambientLight =
        rememberAmbientLight(
            when {
                !ambientLightEnabled -> null
                ambientLive && ambientSampled != null -> ambientSampled
                else -> ambientFallback
            },
            active = ambientNeeded,
            animate = !ambientPowerLimited,
        )

    return PlayerAmbientBinding(
        ambientLightEnabled,
        ambientSampler,
        ambientVideoSize,
        ambientLight,
        onContainerSize = { ambientContainer = it },
        onChromeVisible = { ambientChromeVisible = it },
    )
}
