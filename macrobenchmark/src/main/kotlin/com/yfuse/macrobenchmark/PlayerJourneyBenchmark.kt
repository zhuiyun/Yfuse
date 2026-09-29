package com.yfuse.macrobenchmark

import android.content.res.Resources
import android.graphics.Point
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The player window: entering and leaving it, and scrubbing into the filmstrip. Needs a signed-in server. */
@LargeTest
@RunWith(AndroidJUnit4::class)
class PlayerJourneyBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun enterExit() =
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(BaselineProfileMode.Disable, warmupIterations = 3),
            iterations = 5,
            // A fresh process each time also ends any picture-in-picture window the last one left.
            setupBlock = {
                killProcess()
                openLibraryGrid()
                openFirstDetail()
            },
            measureBlock = { playerEnterExitJourney() },
        )

    @Test
    fun scrubIntoFilmstrip() =
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(BaselineProfileMode.Disable, warmupIterations = 3),
            iterations = 5,
            setupBlock = {
                killProcess()
                openLibraryGrid()
                openFirstDetail()
                enterPlayer()
            },
            measureBlock = { playerScrubJourney() },
        )
}

/** The picture itself: present from the moment the player can be seen until it has left. */
private val PLAYER_SURFACE = By.desc("播放画面")

/** The seek bar: Compose reports a progress range that accepts SetProgress as a SeekBar. */
private val SEEK_BAR = By.clazz("android.widget.SeekBar")

/** Above the bar by more than the 84 dp at which a drag turns into the filmstrip. */
private const val FILMSTRIP_LIFT_DP = 130f

internal fun MacrobenchmarkScope.enterPlayer() {
    detailPlayKey().click()
    assertTrue("播放器 did not open", device.wait(Until.hasObject(PLAYER_SURFACE), SERVER_CONTENT_TIMEOUT_MS))
}

/**
 * Four times from 详情: 播放, then back. Nothing waits for the UI to go idle while a video plays;
 * the picture's own appearance and removal are the only marks.
 */
internal fun MacrobenchmarkScope.playerEnterExitJourney() {
    repeat(4) {
        enterPlayer()
        device.pressBack()
        assertTrue("播放器 did not close", device.wait(Until.gone(PLAYER_SURFACE), SERVER_CONTENT_TIMEOUT_MS))
        assertTrue("详情 did not come back", device.wait(Until.hasObject(DETAIL_PLAY), SERVER_CONTENT_TIMEOUT_MS))
    }
}

/**
 * Three scrubs: along the bar far enough to start the drag, up past the filmstrip line, then
 * across and back. The strip itself appears where the server has trickplay for the title; without
 * it the same gesture is the fine-scrub tier.
 */
internal fun MacrobenchmarkScope.playerScrubJourney() {
    val lift = (FILMSTRIP_LIFT_DP * Resources.getSystem().displayMetrics.density).toInt()
    repeat(3) {
        val bar = seekBar().visibleBounds
        val y = bar.centerY()
        val start = bar.left + bar.width() / 2
        val slop = bar.width() / 12
        device.swipe(
            arrayOf(
                Point(start, y),
                Point(start + slop, y),
                Point(start + slop, y - lift),
                Point(bar.right - bar.width() / 6, y - lift),
                Point(bar.left + bar.width() / 6, y - lift),
                Point(start, y - lift),
            ),
            20,
        )
    }
}

/** The seek bar, bringing the controls back with a tap on the picture when they have hidden. */
private fun MacrobenchmarkScope.seekBar(): UiObject2 {
    device.findObject(SEEK_BAR)?.let { return it }
    (device.findObject(PLAYER_SURFACE) ?: error("The player is not on screen")).click()
    return device.wait(Until.findObject(SEEK_BAR), SERVER_CONTENT_TIMEOUT_MS) ?: error("The seek bar never showed")
}
