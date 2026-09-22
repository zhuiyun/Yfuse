package com.yfuse.feature.player

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yfuse.core.designsystem.AmbientInset
import com.yfuse.core.designsystem.AmbientLight
import com.yfuse.core.designsystem.LocalRouteVisible
import com.yfuse.core.designsystem.ambientLightDiffers
import com.yfuse.core.designsystem.ambientLightFalloffBrushes
import com.yfuse.core.designsystem.ambientLightFromPixels
import com.yfuse.core.designsystem.drawAmbientLight
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

/**
 * Reads the playing picture for 氛围光.
 *
 * Every engine renders into a bare [SurfaceView] and Compose never sees a decoded frame, so the
 * only in-process way to know what colour the picture is right now is [PixelCopy] — a GPU
 * read-back of the surface, scaled straight into a 32×18 bitmap. Reads are at least 500ms
 * apart and slow down on stable pictures; actual copy latency depends on the output mode.
 *
 * The copy fails on secure (DRM) surfaces and on some HDR/tunnelled outputs. After three misses
 * in a row [light] goes null and the caller falls back to the artwork colour, while this keeps
 * retrying slowly in case the output changes. The surface host attaches its view here; a host
 * whose engine letterboxes inside its own surface also sets [picture] so the black bars inside
 * the surface are not what gets sampled.
 */
class AmbientFrameSampler {
    private val _light = MutableStateFlow<AmbientLight?>(null)
    val light: StateFlow<AmbientLight?> = _light.asStateFlow()

    private val _unreadable = MutableStateFlow(false)

    /**
     * True once three reads in a row have failed: the surface is protected, or its output cannot
     * be copied. A null [light] without this is only the first read still on its way.
     */
    val unreadable: StateFlow<Boolean> = _unreadable.asStateFlow()

    private var view: SurfaceView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val copies = AmbientCopyQueue()
    private val policy = AmbientSamplingPolicy()
    private var contentIdentity: Any? = null
    private var hasContentIdentity = false
    private var revision by mutableIntStateOf(0)
    private var letterboxed = false
    private val surfaceCallback =
        object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = invalidate(clear = false)

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int,
            ) = invalidate(clear = false)

            override fun surfaceDestroyed(holder: SurfaceHolder) = invalidate(clear = true)
        }
    private val layoutListener =
        View.OnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            if (l != oldL || t != oldT || r != oldR || b != oldB) invalidate(clear = false)
        }

    /**
     * Discards any copy in flight and reads again at once. Only losing the source [clear]s the
     * published colour: a resize or a new picture rectangle keeps the last light until the next
     * read lands, so changing the scale mode does not dip through the artwork glow and back.
     */
    private fun invalidate(clear: Boolean) {
        revision++
        policy.reset()
        _unreadable.value = false
        if (clear) publish(null)
    }

    /** Where the picture sits inside the attached view, in view pixels. Null when the view is the picture. */
    var picture: IntRect? = null
        set(value) {
            if (field == value) return
            field = value
            invalidate(clear = false)
        }

    fun attach(
        surfaceView: SurfaceView,
        letterboxed: Boolean = false,
    ) {
        if (view === surfaceView && this.letterboxed == letterboxed) return
        view?.let(::detach)
        view = surfaceView
        this.letterboxed = letterboxed
        surfaceView.holder.addCallback(surfaceCallback)
        surfaceView.addOnLayoutChangeListener(layoutListener)
        invalidate(clear = true)
    }

    fun detach(surfaceView: SurfaceView) {
        if (view !== surfaceView) return
        surfaceView.holder.removeCallback(surfaceCallback)
        surfaceView.removeOnLayoutChangeListener(layoutListener)
        view = null
        invalidate(clear = true)
    }

    /** One read; null when the surface cannot be read right now. Main thread. */
    suspend fun sample(): AmbientLight? {
        val target = view ?: return null
        if (target.width <= 0 || target.height <= 0 || !target.holder.surface.isValid) return null
        val requestRevision = revision
        val frame = target.holder.surfaceFrame
        val source =
            ambientCopyRect(
                letterboxed,
                picture,
                IntSize(target.width, target.height),
                IntSize(frame.width(), frame.height()),
            )?.let { Rect(it.left, it.top, it.right, it.bottom) }
        return copies
            .copy(
                create = { Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888) },
                request = { bitmap, complete ->
                    if (requestRevision != revision || target !== view || !target.holder.surface.isValid) {
                        throw IllegalArgumentException("Ambient source changed before copy")
                    }
                    PixelCopy.request(target, source, bitmap, { complete(it == PixelCopy.SUCCESS) }, handler)
                },
                read = { bitmap ->
                    if (requestRevision != revision || target !== view) {
                        null
                    } else {
                        val pixels = IntArray(WIDTH * HEIGHT)
                        bitmap.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
                        ambientLightFromPixels(pixels, WIDTH, HEIGHT)
                    }
                },
                release = Bitmap::recycle,
            ).takeIf { requestRevision == revision && target === view }
    }

    /** A new item or a disabled light starts from nothing: no colour, no failure history. */
    private fun forget() {
        policy.reset()
        _unreadable.value = false
        publish(null)
    }

    private fun publish(next: AmbientLight?): Boolean {
        val current = _light.value
        val changed = if (next == null) current != null else current == null || ambientLightDiffers(current, next)
        if (changed) _light.value = next
        return changed
    }

    /**
     * Paused playback takes one good read and stops. The policy and copy lane survive seeks,
     * so cancelling an effect neither floods copies nor erases a valid colour on pause/resume.
     */
    @Composable
    fun Collect(
        active: Boolean,
        playing: Boolean,
        /** Changes while paused (a seek) take one more read; ignored while playing. */
        pausedPositionMs: Long,
        /** An item/version or engine switch invalidates a completed paused sample too. */
        contentKey: Any?,
    ) {
        val foreground = rememberAmbientRouteVisible()
        val enabled = active && foreground
        LaunchedEffect(this, enabled, playing, contentKey, revision, if (playing) 0L else pausedPositionMs) {
            if (!hasContentIdentity || contentIdentity != contentKey) {
                contentIdentity = contentKey
                hasContentIdentity = true
                forget()
            }
            if (!enabled) {
                forget()
                return@LaunchedEffect
            }
            var urgent = true
            while (isActive) {
                delay(policy.waitMs(SystemClock.elapsedRealtime(), urgent))
                urgent = false
                policy.started(SystemClock.elapsedRealtime())
                val read = sample()
                if (read != null) {
                    _unreadable.value = false
                    policy.succeeded(changed = publish(read))
                    if (!playing) break
                } else if (policy.failed()) {
                    // Three misses: the caller takes the artwork glow while this keeps probing slowly.
                    _unreadable.value = true
                    publish(null)
                }
            }
        }
    }

    private companion object {
        // The copy scales with linear filtering and no mipmaps, so a tiny target is a few stray
        // pixels rather than an average. 96×54 is still ~5k pixels to add up twice a second.
        const val WIDTH = 96
        const val HEIGHT = 54
    }
}

/** Both sampling and the paced colour animation stop when the player is no longer visible. */
@Composable
internal fun rememberAmbientRouteVisible(): Boolean {
    val lifecycleOwner = LocalLifecycleOwner.current
    var lifecycleState by remember(lifecycleOwner) { mutableStateOf(lifecycleOwner.lifecycle.currentState) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ -> lifecycleState = lifecycleOwner.lifecycle.currentState }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return LocalRouteVisible.current && lifecycleState.isAtLeast(Lifecycle.State.STARTED)
}

/**
 * Paints [light] into the letterbox around the picture. Sits above the surface hosts so the
 * bars an engine draws inside its own surface are covered too; the picture rectangle itself is
 * clipped out, grown by a 1dp guard, so light never lands on the frame. Only this draw node
 * observes the light's frame clock.
 */
@Composable
internal fun AmbientLightLayer(
    light: State<AmbientLight>,
    sampler: AmbientFrameSampler,
    videoSize: IntSize,
    scaleMode: VideoScaleMode,
    modifier: Modifier = Modifier,
) {
    var container by remember { mutableStateOf(IntSize.Zero) }
    val picture = core2SurfaceSize(container, videoSize, scaleMode)
    val left = (container.width - picture.width) / 2
    val top = (container.height - picture.height) / 2
    LaunchedEffect(sampler, picture, left, top) {
        sampler.picture =
            if (picture == IntSize.Zero) null else IntRect(left, top, left + picture.width, top + picture.height)
    }
    val guard = with(LocalDensity.current) { 1.dp.toPx() }
    Box(
        modifier
            .onSizeChanged { container = it }
            .drawWithCache {
                val rect =
                    androidx.compose.ui.geometry.Rect(
                        left.toFloat(),
                        top.toFloat(),
                        (left + picture.width).toFloat(),
                        (top + picture.height).toFloat(),
                    )
                val falloffs = ambientLightFalloffBrushes(rect, size)
                // Bars baked into the frame move the lit edge inward; their geometry is cached per inset.
                var insetKey = AmbientInset.None
                var insetRect = rect
                var insetFalloffs = falloffs
                onDrawBehind {
                    val current = light.value
                    val inset = current.inset
                    val layoutBars = ambientLightHasVisibleBars(container, picture, guard.roundToInt())
                    if (!layoutBars && inset.isZero) return@onDrawBehind
                    val content: androidx.compose.ui.geometry.Rect
                    val fades: List<androidx.compose.ui.graphics.Brush>
                    if (inset.isZero) {
                        content = rect
                        fades = falloffs
                    } else {
                        if (inset != insetKey) {
                            insetKey = inset
                            insetRect =
                                androidx.compose.ui.geometry.Rect(
                                    rect.left + rect.width * inset.left,
                                    rect.top + rect.height * inset.top,
                                    rect.right - rect.width * inset.right,
                                    rect.bottom - rect.height * inset.bottom,
                                )
                            insetFalloffs = ambientLightFalloffBrushes(insetRect, size)
                        }
                        content = insetRect
                        fades = insetFalloffs
                    }
                    drawAmbientLight(current, content, size, guard.roundToInt().toFloat(), fades)
                }
            },
    )
}
