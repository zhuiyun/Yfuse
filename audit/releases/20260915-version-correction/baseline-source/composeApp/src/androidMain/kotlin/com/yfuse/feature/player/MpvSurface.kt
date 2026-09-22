package com.yfuse.feature.player

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import com.yfuse.core.data.PlaybackPreferences
import org.koin.core.context.GlobalContext

/**
 * Render target for [MpvVideoEngine]. mpv draws straight into the Surface. The optional HDMV plane is
 * composited above it only when the custom libbluray runtime reports an active authored menu.
 */
@Composable
fun MpvSurface(
    engine: MpvVideoEngine,
    modifier: Modifier = Modifier,
    /** 氛围光 reads this surface; mpv letterboxes inside it, so the sampler is given the picture rect. */
    ambientSampler: AmbientFrameSampler? = null,
    /** Drawn above the surface and below the stacked captions. */
    ambientLayer: @Composable () -> Unit = {},
    /**
     * mpv places captions in the black borders by default; while the light fills them, captions
     * are kept inside the picture so the layer above the surface cannot cover them.
     */
    subtitlesInsidePicture: Boolean = false,
    subtitleControls: SubtitleControlState = SubtitleControlState(),
) {
    val playbackPreferences = remember { GlobalContext.get().get<PlaybackPreferences>() }
    LaunchedEffect(engine, subtitlesInsidePicture) { engine.setSubtitleUseMargins(!subtitlesInsidePicture) }
    val frameRatePreference by playbackPreferences.frameRateMatch.collectAsState()
    // Only the reported content frame rate belongs to this surface. Reading the whole
    // PlaybackState re-ran the AndroidView update — and the caption stack under it — on every
    // position tick, which is several times a second for the entire film.
    val playbackState = engine.state.collectAsState()
    val contentFrameRate by remember(playbackState) { derivedStateOf { playbackState.value.diagnostics.frameRate } }
    val subtitles by engine.subtitleText.collectAsState()
    val surfaceState = remember { mutableStateOf<Surface?>(null) }
    val frameRateMode = frameRatePreference.toPlayerMode()
    var layoutSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(
        surfaceState.value,
        frameRateMode,
        contentFrameRate,
    ) {
        val surface = surfaceState.value ?: return@LaunchedEffect
        val fps = contentFrameRate
        if (frameRateMode == FrameRateMatchMode.Disabled || fps <= 0f) {
            clearSurfaceFrameRate(surface)
        } else {
            requestSurfaceFrameRate(
                surface = surface,
                mode = frameRateMode,
                contentFrameRate = fps,
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            surfaceState.value?.let(::clearSurfaceFrameRate)
        }
    }

    Box(
        modifier =
            modifier.onSizeChanged { size ->
                layoutSize = size
            },
    ) {
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    // The window owns screen-awake policy; see PlayerActivity.applyScreenOnPolicy.
                    holder.addCallback(
                        object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                surfaceState.value = holder.surface
                                engine.attach(holder.surface)
                            }

                            override fun surfaceChanged(
                                holder: SurfaceHolder,
                                format: Int,
                                width: Int,
                                height: Int,
                            ) {
                                surfaceState.value = holder.surface
                                engine.resize(width, height)
                            }

                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                clearSurfaceFrameRate(holder.surface)
                                surfaceState.value = null
                                engine.detach()
                            }
                        },
                    )
                    ambientSampler?.attach(this, letterboxed = true)
                }
            },
            onRelease = { view -> ambientSampler?.detach(view) },
            modifier = Modifier.fillMaxSize(),
        )
        ambientLayer()

        if (subtitles.stacked) {
            val appearance = subtitleControls.appearance.withBrightness(subtitleControls.brightness)
            BottomSubtitleStack(
                subtitleControls.position,
                primary = { BottomSubtitleText(subtitles.primary, subtitleControls.scale, appearance) },
                secondary = { BottomSubtitleText(subtitles.secondary, subtitleControls.secondaryScale, appearance) },
            )
        }
        DiscNavigationOverlay(engine = engine, layoutSize = layoutSize)
    }
}
