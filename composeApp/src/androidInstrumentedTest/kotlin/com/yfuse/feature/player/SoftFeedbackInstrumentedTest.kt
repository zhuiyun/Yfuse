package com.yfuse.feature.player

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewTreeObserver
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yfuse.MainActivity
import com.yfuse.core.designsystem.AccessibilityOptions
import com.yfuse.core.designsystem.YfuseTheme
import com.yfuse.feature.detail.DetailActionDock
import com.yfuse.feature.detail.DetailPlayButtonHeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class SoftFeedbackInstrumentedTest {
    private lateinit var currentActivity: MainActivity

    @Test(timeout = 45_000L)
    fun seek_cancel_never_commits_and_release_commits_once_with_or_without_motion() {
        val fraction = mutableFloatStateOf(0.25f)
        val buffered = mutableFloatStateOf(0.8f)
        var reduced by mutableStateOf(false)
        val bounds = AtomicReference<Rect>()
        val preview = AtomicReference(0f)
        val committed = AtomicReference(0f)
        val commits = AtomicInteger()
        val cancels = AtomicInteger()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                currentActivity = activity
                activity.setContent {
                    YfuseTheme(dark = true, accessibility = AccessibilityOptions(reduceMotion = reduced)) {
                        Box(
                            Modifier.fillMaxSize().background(Color(0xFF151725)).safeDrawingPadding(),
                            contentAlignment = Alignment.Center,
                        ) {
                            StandardSeekBar(
                                fraction = fraction,
                                bufferedFraction = buffered,
                                positionMs = { (fraction.floatValue * 120_000).toLong() },
                                durationMs = 120_000,
                                progressMarkers = emptyList(),
                                accent = { Color(0xFF90B6ED) },
                                onScrubTo = preview::set,
                                onCommit = {
                                    committed.set(it)
                                    commits.incrementAndGet()
                                    fraction.floatValue = it
                                },
                                onCancel = { cancels.incrementAndGet() },
                                modifier =
                                    Modifier
                                        .width(
                                            300.dp,
                                        ).onGloballyPositioned { bounds.set(it.boundsInWindow()) },
                            )
                        }
                    }
                }
            }
            repeat(3) { frame() }
            val track = requireNotNull(bounds.get())
            for (reduceMotion in listOf(false, true)) {
                scenario.onActivity { reduced = reduceMotion }
                frame()
                val before = commits.get()
                val time = SystemClock.uptimeMillis()
                touch(time, MotionEvent.ACTION_DOWN, track, 0.25f)
                touch(time, MotionEvent.ACTION_MOVE, track, 0.6f)
                repeat(8) { frame() }
                assertEquals(0.6f, preview.get(), 0.015f)
                if (!reduceMotion) capture("seek-pressed.png")
                touch(time, MotionEvent.ACTION_CANCEL, track, 0.6f)
                frame()
                assertEquals(before, commits.get())

                val next = SystemClock.uptimeMillis()
                touch(next, MotionEvent.ACTION_DOWN, track, 0.3f)
                touch(next, MotionEvent.ACTION_MOVE, track, 0.75f)
                touch(next, MotionEvent.ACTION_UP, track, 0.75f)
                repeat(3) { frame() }
                assertEquals(before + 1, commits.get())
                assertEquals(0.75f, committed.get(), 0.015f)
                assertEquals(track, bounds.get())
            }
            assertEquals(2, cancels.get())
            capture("seek-reduced.png")
        }
    }

    @Test(timeout = 45_000L)
    fun menu_cancel_and_rapid_selection_keep_a_single_action_per_click() {
        var selection by mutableStateOf(0)
        val clicks = AtomicInteger()
        val bounds = AtomicReference<Rect>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                currentActivity = activity
                activity.setContent {
                    YfuseTheme(dark = true) {
                        Box(
                            Modifier.fillMaxSize().background(Color(0xFF151725)).safeDrawingPadding(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(Modifier.width(300.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OptionRow("简体中文", selected = selection == 0, onClick = { selection = 0 })
                                Box(Modifier.onGloballyPositioned { bounds.set(it.boundsInWindow()) }) {
                                    OptionRow("English", selected = selection == 1, onClick = {
                                        selection = 1
                                        clicks.incrementAndGet()
                                    })
                                }
                            }
                        }
                    }
                }
            }
            repeat(3) { frame() }
            val row = requireNotNull(bounds.get())
            val canceled = SystemClock.uptimeMillis()
            touch(canceled, MotionEvent.ACTION_DOWN, row, 0.5f)
            repeat(8) { frame() }
            capture("menu-pressed.png")
            touch(canceled, MotionEvent.ACTION_CANCEL, row, 0.5f)
            assertEquals(0, clicks.get())
            repeat(4) {
                val time = SystemClock.uptimeMillis()
                touch(time, MotionEvent.ACTION_DOWN, row, 0.5f)
                touch(time, MotionEvent.ACTION_UP, row, 0.5f)
                frame()
            }
            assertEquals(4, clicks.get())
            assertEquals(1, selection)
            repeat(20) { frame() }
            capture("menu-selected.png")
        }
    }

    @Test(timeout = 45_000L)
    fun compound_play_surface_preserves_separate_actions_and_disabled_state() {
        val plays = AtomicInteger()
        val restarts = AtomicInteger()
        val bounds = AtomicReference<Rect>()
        var resolving by mutableStateOf(false)
        var rowHeight = 0f
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                currentActivity = activity
                rowHeight = DetailPlayButtonHeight.value * activity.resources.displayMetrics.density
                activity.setContent {
                    YfuseTheme(dark = false) {
                        Box(
                            Modifier.fillMaxSize().background(Color(0xFFF1F3F8)).safeDrawingPadding(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(Modifier.width(320.dp).onGloballyPositioned { bounds.set(it.boundsInWindow()) }) {
                                DetailActionDock(
                                    accent = Color(0xFF7295C6),
                                    label = "继续播放",
                                    detailLine = "S1 E4",
                                    resumeTimeLabel = "12:34",
                                    resolving = resolving,
                                    favorite = false,
                                    watchLater = false,
                                    watchLaterMutating = false,
                                    canPlayFromStart = true,
                                    onPlay = { plays.incrementAndGet() },
                                    onPlayFromStart = { restarts.incrementAndGet() },
                                    onFavorite = {},
                                    onWatchLater = {},
                                )
                            }
                        }
                    }
                }
            }
            repeat(3) { frame() }
            val dock = requireNotNull(bounds.get())
            val row = Rect(dock.left, dock.top, dock.right, dock.top + rowHeight)
            val down = SystemClock.uptimeMillis()
            touch(down, MotionEvent.ACTION_DOWN, row, 0.35f)
            repeat(8) { frame() }
            capture("play-pressed-light.png")
            touch(down, MotionEvent.ACTION_CANCEL, row, 0.35f)
            assertEquals(0, plays.get())
            for (position in listOf(0.35f, 0.9f)) {
                val time = SystemClock.uptimeMillis()
                touch(time, MotionEvent.ACTION_DOWN, row, position)
                touch(time, MotionEvent.ACTION_UP, row, position)
                frame()
            }
            assertEquals(1, plays.get())
            assertEquals(1, restarts.get())
            onMain { resolving = true }
            repeat(3) { frame() }
            for (position in listOf(0.35f, 0.9f)) {
                val time = SystemClock.uptimeMillis()
                touch(time, MotionEvent.ACTION_DOWN, row, position)
                touch(time, MotionEvent.ACTION_UP, row, position)
            }
            assertEquals(1, plays.get())
            assertEquals(1, restarts.get())
            onMain { resolving = false }
            repeat(20) { frame() }
        }
    }

    private fun touch(
        downTime: Long,
        action: Int,
        bounds: Rect,
        fraction: Float,
    ) {
        onMain { activity ->
            val event =
                MotionEvent.obtain(
                    downTime,
                    SystemClock.uptimeMillis(),
                    action,
                    bounds.left + bounds.width * fraction,
                    bounds.center.y,
                    0,
                )
            try {
                activity.window.decorView.dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
        }
    }

    private fun frame() {
        val drawn = CountDownLatch(1)
        var listener: ViewTreeObserver.OnDrawListener? = null
        onMain { activity ->
            val decor = activity.window.decorView
            listener = ViewTreeObserver.OnDrawListener { decor.post { drawn.countDown() } }
            decor.viewTreeObserver.addOnDrawListener(listener)
            decor.postInvalidateOnAnimation()
        }
        try {
            assertTrue("No rendered frame", drawn.await(5, TimeUnit.SECONDS))
        } finally {
            onMain {
                it.window.decorView.viewTreeObserver
                    .removeOnDrawListener(listener)
            }
        }
    }

    private fun onMain(action: (MainActivity) -> Unit) {
        // A resolving button deliberately keeps drawing; waiting for global idleness can
        // wait forever. Run the event on the UI thread, then use the bounded frame fence.
        InstrumentationRegistry.getInstrumentation().runOnMainSync { action(currentActivity) }
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "soft-feedback")
        output.mkdirs()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(output, name).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }
}
