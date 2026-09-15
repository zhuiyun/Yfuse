package com.yfuse.feature.player

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
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
import com.yfuse.core.designsystem.AMBIENT_LIGHT_SAMPLE_MS
import com.yfuse.core.designsystem.AmbientInset
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
    /**
     * The unprojected timeline. [state] is the runtime projection, whose `positionMs` is clamped
     * to 0/1 for routing — seeking while paused therefore never changed the sampler's key and the
     * still picture kept its pre-seek colour. Only the paused clock is read from here, so a
     * playing timeline still does not tick this composition.
     */
    livePlayback: State<PlaybackState>,
    scaleMode: VideoScaleMode,
    inPictureInPicture: Boolean,
    ambientPowerLimited: Boolean,
): PlayerAmbientBinding {
    // 氛围光. Live frames are read only when the light is on, motion is not reduced, the source is
    // not DRM-protected and the picture is actually rendering; otherwise the letterbox takes a
    // still glow from this item's artwork, and switching the light off paints nothing at all.
    val ambientLightEnabled by playbackPreferences.ambientLight.collectAsState()
    val ambientSampler = remember { AmbientFrameSampler() }
    val ambientSampled by ambientSampler.light.collectAsState()
    val ambientUnreadable by ambientSampler.unreadable.collectAsState()
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
    val ambientVisible =
        ambientLightEnabled &&
            ambientForeground &&
            !inPictureInPicture
    val ambientNeeded =
        ambientVisible &&
            (
                ambientChromeVisible ||
                    ambientLightHasVisibleBars(
                        ambientContainer,
                        ambientPictureSize,
                        ambientGuard,
                        ambientSampled?.inset ?: AmbientInset.None,
                    )
            )
    val ambientProtected =
        currentItem?.let { item ->
            item.drmConfiguration != null || item.activeVersion?.drmConfiguration != null
        } == true
    // Android 14+ tone-maps Surface readback into the destination sRGB bitmap. Let these
    // devices try HDR too; unreadable vendor outputs still use the normal failure fallback.
    val ambientOutputSupported =
        ambientOutputSupportsLiveSampling(
            state.diagnostics.outputEvidence.dynamicRangeOutputMode,
            Build.VERSION.SDK_INT,
        )
    val ambientLive =
        ambientVisible &&
            !ambientPowerLimited &&
            !LocalAccessibilityOptions.current.reduceMotion &&
            !ambientProtected &&
            ambientOutputSupported &&
            state.videoHeight > 0 &&
            state.diagnostics.effectiveVideoReadiness == PlaybackOutputReadiness.Rendering
    val ambientPlaying = state.playing && !state.buffering
    val ambientPausedPositionMs by remember(livePlayback) {
        derivedStateOf {
            val live = livePlayback.value
            if (live.playing && !live.buffering) 0L else live.positionMs
        }
    }
    ambientSampler.Collect(
        active = ambientLive,
        playing = ambientPlaying,
        pausedPositionMs = ambientPausedPositionMs,
        contentKey = listOf(engine, currentItem?.serverId, currentItem?.id, currentItem?.versionId),
        // Layout alone cannot reveal encoded bars. Keep a slow discovery read when the chrome
        // is hidden; a detected bar restores normal sampling without needing another tap.
        minimumIntervalMs = if (ambientNeeded) AMBIENT_LIGHT_SAMPLE_MS else AMBIENT_LIGHT_DISCOVERY_MS,
    )
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
                // The first read is at most a sample interval away: stay dark for it rather than
                // lighting the artwork colour and then crossing over to the picture's own.
                ambientLive && !ambientUnreadable -> AmbientLight.Off
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
