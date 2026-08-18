package com.yfuse.feature.player

import android.graphics.Bitmap
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
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
) {
    val playbackPreferences = remember { GlobalContext.get().get<PlaybackPreferences>() }
    val frameRatePreference by playbackPreferences.frameRateMatch.collectAsState()
    val playbackState by engine.state.collectAsState()
    val nativeMenuSession by NativeRemoteBluRaySessionRegistry.activeSession.collectAsState()
    val bdmvAngleBinding by NativeLocalBdmvAngleRegistry.binding.collectAsState()
    val overlayFrame by NativeRemoteBluRaySessionRegistry.overlay.collectAsState()
    // Native menu/angle events bump this revision through the backend listener/rebinding path.
    val discNavigationRevision by ActiveDiscNavigation.revision.collectAsState()
    val surfaceState = remember { mutableStateOf<Surface?>(null) }
    val frameRateMode = frameRatePreference.toPlayerMode()
    var layoutSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(
        surfaceState.value,
        frameRateMode,
        playbackState.diagnostics.frameRate,
    ) {
        val surface = surfaceState.value ?: return@LaunchedEffect
        val fps = playbackState.diagnostics.frameRate
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

    DisposableEffect(engine, nativeMenuSession, bdmvAngleBinding) {
        val engineBackend = VideoEngineDiscNavigationBackend(engine)
        val backend =
            nativeMenuSession?.let { session ->
                val opticalSession =
                    if (bdmvAngleBinding != null) {
                        BdmvAngleHdmvSession(session) { NativeLocalBdmvAngleRegistry.binding.value }
                    } else {
                        session
                    }
                CompositeDiscNavigationBackend(
                    engineBackend = engineBackend,
                    menuBackend = HdmvDiscNavigationBackend(opticalSession),
                )
            } ?: engineBackend
        ActiveDiscNavigation.bind(owner = engine, backend = backend)
        onDispose {
            ActiveDiscNavigation.unbind(engine)
        }
    }

    @Suppress("UNUSED_VARIABLE")
    val observedDiscNavigationRevision = discNavigationRevision
    val menuActive = ActiveDiscNavigation.menuActive
    DiscNavigationPlatformInputEffect(menuActive = menuActive)

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
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (menuActive && overlayFrame != null) {
            val frame = requireNotNull(overlayFrame)
            val bitmap =
                remember(frame) {
                    Bitmap.createBitmap(
                        frame.argb,
                        frame.width,
                        frame.height,
                        Bitmap.Config.ARGB_8888,
                    ).asImageBitmap()
                }
            Image(
                bitmap = bitmap,
                contentDescription = "Blu-ray 菜单",
                contentScale = ContentScale.Fit,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .pointerInput(frame.width, frame.height, layoutSize) {
                            detectTapGestures { position ->
                                mapHdmvOverlayPoint(
                                    position = position,
                                    viewport = layoutSize,
                                    overlayWidth = frame.width,
                                    overlayHeight = frame.height,
                                )?.let { point ->
                                    ActiveDiscNavigation.routeActiveMenuPoint(
                                        x = point.first,
                                        y = point.second,
                                        activate = true,
                                    )
                                }
                            }
                        },
            )
        }
    }
}

/** Maps ContentScale.Fit coordinates back into the authored HDMV overlay plane. */
internal fun mapHdmvOverlayPoint(
    position: Offset,
    viewport: IntSize,
    overlayWidth: Int,
    overlayHeight: Int,
): Pair<Int, Int>? {
    if (
        viewport.width <= 0 || viewport.height <= 0 ||
        overlayWidth <= 0 || overlayHeight <= 0 ||
        !position.x.isFinite() || !position.y.isFinite()
    ) {
        return null
    }
    val scale =
        minOf(
            viewport.width.toFloat() / overlayWidth.toFloat(),
            viewport.height.toFloat() / overlayHeight.toFloat(),
        )
    if (!scale.isFinite() || scale <= 0f) return null
    val renderedWidth = overlayWidth * scale
    val renderedHeight = overlayHeight * scale
    val left = (viewport.width - renderedWidth) / 2f
    val top = (viewport.height - renderedHeight) / 2f
    if (
        position.x < left || position.y < top ||
        position.x >= left + renderedWidth || position.y >= top + renderedHeight
    ) {
        return null
    }
    val x = ((position.x - left) / scale).toInt().coerceIn(0, overlayWidth - 1)
    val y = ((position.y - top) / scale).toInt().coerceIn(0, overlayHeight - 1)
    return x to y
}
