package com.yfuse.core.performance

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yfuse.core.data.PlaybackPreferences
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import org.koin.core.context.GlobalContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/** Window draws per wall-clock second, including the diagnostic text itself, not display Hz. */
data class PageFrameRateSample(
    val fps: Int? = null,
    val lowActivity: Boolean = false,
    val incomplete: Boolean = false,
    val unavailable: Boolean = false,
) {
    val label: String
        get() {
            if (unavailable) return "页面 FPS 不可用"
            if (fps == null) return "页面 FPS 待测"
            val rate = if (lowActivity) "<2" else fps.toString()
            return if (incomplete) {
                "页面 ~$rate FPS · 丢报/含统计层"
            } else {
                "页面 $rate FPS · 含统计层"
            }
        }
}

/**
 * Call only while the preference is enabled. Neither a frame listener nor a timer survives
 * leaving RESUMED, and disposing this composable releases its dedicated callback thread.
 */
@Composable
fun rememberPageFrameRate(): State<PageFrameRateSample> {
    val context = LocalContext.current
    val activity = remember(context) { context.frameRateActivity() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val sample = remember(activity) { mutableStateOf(PageFrameRateSample(unavailable = activity == null)) }
    DisposableEffect(activity, lifecycle) {
        var monitor: PageFrameRateMonitor? = null

        fun stop() {
            monitor?.close()
            monitor = null
        }

        fun start() {
            if (monitor != null || activity == null || activity.isDestroyed) return
            sample.value = PageFrameRateSample()
            monitor =
                runCatching {
                    PageFrameRateMonitor(activity.window) { update ->
                        // Stable values do not redraw the HUD or its hosting page.
                        if (sample.value != update) sample.value = update
                    }
                }.getOrElse {
                    sample.value = PageFrameRateSample(unavailable = true)
                    null
                }
        }

        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> start()
                    Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> stop()
                    else -> Unit
                }
            }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) start()
        onDispose {
            lifecycle.removeObserver(observer)
            stop()
        }
    }
    return sample
}

/** Non-interactive app-wide HUD; explicit colours also work outside the app's theme provider. */
@Composable
fun PageFrameRateOverlay(modifier: Modifier = Modifier) {
    val preferences = remember { GlobalContext.get().get<PlaybackPreferences>() }
    val enabled by preferences.showFrameRate.collectAsState()
    if (!enabled) return
    val sample by rememberPageFrameRate()
    Box(modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
        BasicText(
            text = sample.label,
            style = AppTypography.caption.medium.copy(color = Color.White),
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .background(Color.Black.copy(alpha = 0.76f), AppShapes.thumb)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

private fun Context.frameRateActivity(): Activity? =
    generateSequence(this) { context ->
        (context as? ContextWrapper)?.baseContext?.takeUnless { it === context }
    }.take(16)
        .filterIsInstance<Activity>()
        .firstOrNull()

/** All counters live on [callbackHandler]; the main thread only receives one compact sample. */
private class PageFrameRateMonitor(
    private val window: Window,
    private val onSample: (PageFrameRateSample) -> Unit,
) {
    private val active = AtomicBoolean(true)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val callbackThread = HandlerThread("yfuse-page-fps").apply { start() }
    private val callbackHandler = Handler(callbackThread.looper)
    private val startedNanos = System.nanoTime()
    private var windowStartNanos = SystemClock.elapsedRealtimeNanos()
    private var frames = 0L
    private var incomplete = false
    private val listener =
        Window.OnFrameMetricsAvailableListener { _, metrics, droppedReports ->
            if (active.get() && metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP) >= startedNanos) {
                frames++
                // Dropped reports describe missing telemetry, not a known number of displayed
                // frames. Never add them to the measured draw count.
                incomplete = incomplete || droppedReports > 0
            }
        }
    private val publish =
        object : Runnable {
            override fun run() {
                if (!active.get()) return
                val now = SystemClock.elapsedRealtimeNanos()
                val seconds = (now - windowStartNanos).coerceAtLeast(1L) / 1_000_000_000.0
                val measured = frames / seconds
                // A HUD update can itself draw one frame. Keeping idle/one-frame windows in
                // one stable band prevents its own text from oscillating between 0 and 1 FPS.
                // Higher rates still include the HUD, which is stated explicitly in the label.
                val lowActivity = measured < 2.0
                val update =
                    PageFrameRateSample(
                        fps = if (lowActivity) 0 else measured.roundToInt(),
                        lowActivity = lowActivity,
                        incomplete = incomplete,
                    )
                frames = 0L
                incomplete = false
                windowStartNanos = now
                mainHandler.post {
                    if (active.get()) onSample(update)
                }
                callbackHandler.postDelayed(this, SAMPLE_INTERVAL_MS)
            }
        }

    init {
        try {
            window.addOnFrameMetricsAvailableListener(listener, callbackHandler)
            callbackHandler.postDelayed(publish, SAMPLE_INTERVAL_MS)
        } catch (error: Exception) {
            active.set(false)
            callbackThread.quitSafely()
            throw error
        }
    }

    fun close() {
        if (!active.getAndSet(false)) return
        try {
            window.removeOnFrameMetricsAvailableListener(listener)
        } finally {
            callbackHandler.removeCallbacksAndMessages(null)
            callbackThread.quitSafely()
        }
    }

    private companion object {
        const val SAMPLE_INTERVAL_MS = 1_000L
    }
}
