package com.yfuse.feature.player

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yfuse.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class PlayerDragReleaseInstrumentedTest {
    @Test
    fun pictureDragIncludesReleaseMotionAndCancellationDoesNotCommit() {
        val laidOut = CountDownLatch(1)
        val committed = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val commits = AtomicInteger()
        val total = AtomicInteger()
        var width = 0f
        var y = 0f
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .onGloballyPositioned {
                                val bounds = it.boundsInWindow()
                                width = bounds.width
                                y = bounds.center.y
                                laidOut.countDown()
                            }.pointerInput(Unit) {
                                detectPlayerDragGestures(
                                    canStart = { true },
                                    onDragStart = { total.set(0) },
                                    onDrag = { _, delta -> total.addAndGet(delta.x.toInt()) },
                                    onDragEnd = {
                                        commits.incrementAndGet()
                                        committed.countDown()
                                    },
                                    onDragCancel = { cancelled.countDown() },
                                )
                            },
                    )
                }
            }
            assertTrue(laidOut.await(10, TimeUnit.SECONDS))

            fun drag(lastAction: Int) {
                val started = SystemClock.uptimeMillis()
                val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                for ((action, fraction) in listOf(
                    MotionEvent.ACTION_DOWN to .2f,
                    MotionEvent.ACTION_MOVE to .4f,
                    lastAction to .8f,
                )) {
                    val event = MotionEvent.obtain(started, SystemClock.uptimeMillis(), action, width * fraction, y, 0)
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    try {
                        assertTrue(automation.injectInputEvent(event, true))
                    } finally {
                        event.recycle()
                    }
                    SystemClock.sleep(30)
                }
            }
            drag(MotionEvent.ACTION_CANCEL)
            assertTrue(cancelled.await(5, TimeUnit.SECONDS))
            assertEquals(0, commits.get())
            drag(MotionEvent.ACTION_UP)
            assertTrue(committed.await(5, TimeUnit.SECONDS))
            assertEquals(1, commits.get())
            assertTrue("Release-only travel was lost", total.get() > width * .45f)
        }
    }
}
