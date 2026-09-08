package com.yfuse.core.designsystem

import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewParent
import android.view.Window
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yfuse.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil

/** Device-local diagnostics, not a hardware-independent FPS pass/fail threshold. */
@RunWith(AndroidJUnit4::class)
class DialogMotionQualityInstrumentedTest {
    @Test
    fun warmed_dialog_windows_report_frame_costs() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val names =
            arguments.getString("motionStyles")
                ?: "Lift,Slide,Touch,PosterMorph,Reconstruct,Mosaic"
        val styles = names.split(',').map { DialogAnimation.valueOf(it.trim()) }
        val repetitions = (arguments.getString("motionRepeats")?.toIntOrNull() ?: 3).coerceIn(1, 10)
        val measured = AtomicBoolean(false)
        val counter = FrameCostCollector(measured)
        val shown = mutableStateOf(false)
        val style = mutableStateOf(styles.first())
        val close = AtomicReference<(() -> Unit)?>(null)
        val openBounds = AtomicReference<Rect?>(null)
        val exits = AtomicInteger()
        val reports = JSONArray()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        YfuseTheme(dark = true, dialogAnimation = style.value) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .trackDialogOrigin(LocalDialogMotionHost.current)
                                    .background(Color(0xFF182A38)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("动效帧耗时检查", color = Color.White)
                                    Spacer(Modifier.height(20.dp))
                                    Box(
                                        Modifier
                                            .size(96.dp, 132.dp)
                                            .dialogPosterSource()
                                            .onGloballyPositioned { openBounds.set(it.boundsInWindow()) }
                                            .pressable(onClick = { shown.value = true })
                                            .background(
                                                Brush.verticalGradient(listOf(Color(0xFF458AB0), Color(0xFFCA9977))),
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) { Text("打开预览", color = Color.White) }
                                }
                                if (shown.value) {
                                    GlassDialog(onDismiss = {
                                        shown.value = false
                                        exits.incrementAndGet()
                                    }) {
                                        ObserveDialogFrames(counter)
                                        val motionHost = LocalDialogMotionHost.current
                                        val dismiss = overlayDismiss { error("Missing overlay dismiss") }
                                        SideEffect {
                                            if (style.value == DialogAnimation.PosterMorph) {
                                                check(motionHost.poster?.let { it.active && it.recorded } == true) {
                                                    "The benchmark must exercise a live poster source"
                                                }
                                            }
                                            if (style.value == DialogAnimation.Touch) check(motionHost.touch != null)
                                            close.set(dismiss)
                                        }
                                        DisposableEffect(Unit) { onDispose { close.set(null) } }
                                        OverlayHeader(style.value.label, "同一内容、同一设备，记录真实弹窗 Window 帧耗时")
                                        repeat(5) { index ->
                                            OverlayOptionRow("清晰内容 ${index + 1}", index == 0, {})
                                            Spacer(Modifier.height(8.dp))
                                        }
                                        OverlayButton("完成", onClick = dismiss)
                                    }
                                }
                            }
                        }
                    }
                }
                waitUntil { openBounds.get() != null }
                var expectedExits = 0
                // Warm every style before collecting. Each style uses the same payload and tap source.
                for (pass in 0..repetitions) {
                    for (animation in styles) {
                        scenario.onActivity { style.value = animation }
                        instrumentation.waitForIdleSync()
                        SystemClock.sleep(100)
                        counter.reset()
                        measured.set(pass > 0)
                        val rect = requireNotNull(openBounds.get())
                        injectTap(rect.center.x, rect.center.y)
                        waitUntil { close.get() != null }
                        SystemClock.sleep(animation.enterMillis + 100L)
                        scenario.onActivity { requireNotNull(close.get()).invoke() }
                        expectedExits++
                        waitUntil { exits.get() == expectedExits }
                        SystemClock.sleep(80)
                        measured.set(false)
                        if (pass > 0) {
                            val report = counter.report(animation.name, pass)
                            assertTrue("No dialog frame metrics collected: $report", report.getInt("frames") >= 8)
                            reports.put(report)
                        }
                    }
                }
            }
        } finally {
            counter.close()
        }
        val label = (arguments.getString("motionRun") ?: "current").replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val result = JSONObject().put("run", label).put("samples", reports)
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "motion-metrics-$label.json")
        output.writeText(result.toString(2))
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "motionMetrics=$result\n") })
    }

    @Test
    fun new_styles_capture_midpoints_and_readable_rest_in_both_themes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val styles =
            listOf("PaperPlane", "WindChime", "InstantPhoto", "Zipper", "Ticket")
                .map { DialogAnimation.valueOf(it) }
        val style = mutableStateOf(styles.first())
        val progress = mutableFloatStateOf(1f)
        val dark = mutableStateOf(true)
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "motion-visuals")
        check(output.mkdirs() || output.isDirectory)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    YfuseTheme(dark = dark.value, dialogAnimation = style.value) {
                        Box(
                            Modifier.fillMaxSize().background(if (dark.value) Color(0xFF13232D) else Color(0xFFE9F1F5)),
                            contentAlignment = Alignment.Center,
                        ) {
                            CompositionLocalProvider(
                                LocalDialogContentMotion provides
                                    DialogContentMotion(style.value) { progress.floatValue },
                                LocalMutedGlass provides true,
                            ) {
                                Column(
                                    Modifier
                                        .width(320.dp)
                                        .dialogMotion(style.value, progress = { progress.floatValue })
                                        .mutedGlassPanel(samplePage = false)
                                        .dialogInteriorMotion(style.value) { progress.floatValue }
                                        .padding(20.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    OverlayHeader(style.value.label, style.value.description)
                                    Text("细腻入场，清晰停留。", color = LocalPalette.current.text)
                                    Text("动画结束后保留完整正文、选项和按钮。", color = LocalPalette.current.sub)
                                    OverlayOptionRow("轻盈节奏", true, {})
                                    OverlayOptionRow("趣味细节", false, {})
                                    OverlayButton("继续浏览", onClick = {})
                                }
                            }
                        }
                    }
                }
            }
            for (isDark in listOf(true, false)) {
                for (animation in styles) {
                    for (fraction in listOf(0.45f, 1f)) {
                        scenario.onActivity {
                            dark.value = isDark
                            style.value = animation
                            progress.floatValue = fraction
                        }
                        instrumentation.waitForIdleSync()
                        SystemClock.sleep(140)
                        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                        val themeName = if (isDark) "dark" else "light"
                        val filename = "${animation.name}-$themeName-${(fraction * 100).toInt()}.png"
                        File(
                            output,
                            filename,
                        ).outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        screenshot.recycle()
                    }
                }
            }
        }
        assertTrue("Expected midpoint and rest frames in both themes", output.listFiles().orEmpty().size >= 20)
    }

    private fun injectTap(
        x: Float,
        y: Float,
    ) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
        down.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            assertTrue(automation.injectInputEvent(down, true))
        } finally {
            down.recycle()
        }
        SystemClock.sleep(40)
        val up = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0)
        up.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            assertTrue(automation.injectInputEvent(up, true))
        } finally {
            up.recycle()
        }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(10)
        }
        assertTrue("Animation did not complete", condition())
    }
}

@Composable
private fun ObserveDialogFrames(collector: FrameCostCollector) {
    var parent: ViewParent? = LocalView.current.parent
    var dialogWindow: Window? = null
    while (parent != null) {
        if (parent is DialogWindowProvider) {
            dialogWindow = parent.window
            break
        }
        parent = parent.parent
    }
    val window = checkNotNull(dialogWindow) { "Frame metrics must observe the dialog Window" }
    DisposableEffect(window, collector) {
        collector.attach(window)
        onDispose { collector.detach(window) }
    }
}

private class FrameCostCollector(
    private val measured: AtomicBoolean,
) {
    private val thread = HandlerThread("Yfuse-Motion-Metrics").apply { start() }
    private val handler = Handler(thread.looper)
    private val samples = mutableListOf<Long>()
    private val firstFrames = mutableListOf<Long>()
    private var droppedReports = 0
    private var budgetNs = 16_666_667L
    private val activeWindow = AtomicReference<Window?>(null)
    private val listener =
        Window.OnFrameMetricsAvailableListener { source, metrics, dropped ->
            if (measured.get() && activeWindow.get() === source) {
                synchronized(this) {
                    val total = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
                    if (total > 0L) {
                        if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) ==
                            1L
                        ) {
                            firstFrames.add(total)
                        } else {
                            samples.add(total)
                        }
                    }
                    droppedReports += dropped
                }
            }
        }

    fun attach(window: Window) {
        val refresh = window.windowManager.defaultDisplay.refreshRate
        if (refresh.isFinite() && refresh > 0f) budgetNs = (1_000_000_000.0 / refresh).toLong()
        activeWindow.set(window)
        window.addOnFrameMetricsAvailableListener(listener, handler)
    }

    fun detach(window: Window) {
        activeWindow.compareAndSet(window, null)
        window.removeOnFrameMetricsAvailableListener(listener)
    }

    @Synchronized
    fun reset() {
        samples.clear()
        firstFrames.clear()
        droppedReports = 0
    }

    @Synchronized
    fun report(
        style: String,
        pass: Int,
    ): JSONObject {
        val sorted = samples.sorted()
        return JSONObject()
            .put("style", style)
            .put("pass", pass)
            .put("frames", sorted.size)
            .put("frameBudgetMs", budgetNs / 1_000_000.0)
            .put("p50Ms", percentile(sorted, 0.50))
            .put("p95Ms", percentile(sorted, 0.95))
            .put("overBudgetFrames", sorted.count { it > budgetNs })
            .put("overTwoBudgetsFrames", sorted.count { it > budgetNs * 2 })
            .put("firstDrawFrames", firstFrames.size)
            .put("firstDrawP95Ms", percentile(firstFrames.sorted(), 0.95))
            .put("droppedMetricReports", droppedReports)
    }

    private fun percentile(
        sorted: List<Long>,
        fraction: Double,
    ): Double =
        if (sorted.isEmpty()) 0.0 else sorted[(ceil(sorted.size * fraction).toInt() - 1).coerceAtLeast(0)] / 1_000_000.0

    fun close() {
        thread.quitSafely()
        thread.join(2_000L)
    }
}
