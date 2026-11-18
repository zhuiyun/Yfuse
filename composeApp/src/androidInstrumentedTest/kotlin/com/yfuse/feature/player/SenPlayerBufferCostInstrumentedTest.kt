package com.yfuse.feature.player

import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlayerEngine
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SenPlayerBufferCostInstrumentedTest {
    @Test
    fun measure_video_buffers_on_one_unchanged_screen() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Requires the synthetic cloud clock fixture", args.getString("senBufferStudy") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = requireNotNull(context.getExternalFilesDir(null))
        val fixture = File(directory, "buffer-clock.mp4")
        assertTrue(fixture.isFile)
        val windows = JSONArray()
        for (sourceSize in listOf(false, true, true, false)) {
            val item = externalPlaybackItem(Uri.fromFile(fixture).toString(), "合成功耗样本", "video/mp4")
            val intent =
                PlayerActivity.intent(
                    context,
                    listOf(item),
                    startIndex = 0,
                    startPositionMs = 5_000,
                    engine = PlayerEngine.SenPlayer,
                    decoder = DecoderMode.Auto,
                    autoNext = false,
                )
            ActivityScenario.launch<PlayerActivity>(intent).use { scenario ->
                val host = waitForHost(scenario)
                val width = if (sourceSize) 640 else 1280
                val height = if (sourceSize) 360 else 720
                scenario.onActivity {
                    if (sourceSize) {
                        host.video.holder.setFixedSize(width, height)
                    } else {
                        host.video.holder.setSizeFromLayout()
                    }
                }
                val deadline = SystemClock.elapsedRealtime() + 20_000L
                while (SystemClock.elapsedRealtime() < deadline) {
                    var ready = false
                    scenario.onActivity {
                        val frame = host.video.holder.surfaceFrame
                        ready = frame.width() == width && frame.height() == height
                    }
                    if (ready) break
                    SystemClock.sleep(100)
                }
                val row = JSONObject()

                fun checkGeometry() {
                    scenario.onActivity {
                        assertEquals(1280, it.window.decorView.width)
                        assertEquals(720, it.window.decorView.height)
                        for (view in listOf(host.video, host.subtitles)) {
                            assertEquals(1280, view.width)
                            assertEquals(720, view.height)
                        }
                        val videoFrame = host.video.holder.surfaceFrame
                        val subtitleFrame = host.subtitles.holder.surfaceFrame
                        assertEquals(width, videoFrame.width())
                        assertEquals(height, videoFrame.height())
                        assertEquals(1280, subtitleFrame.width())
                        assertEquals(720, subtitleFrame.height())
                    }
                }
                checkGeometry()
                val first = awaitClock(host.video)
                SystemClock.sleep(4_000)
                val before = awaitClock(host.video, first)
                val start = SystemClock.elapsedRealtime()
                val cpuStart = Process.getElapsedCpuTime()
                SystemClock.sleep(30_000)
                val cpu = Process.getElapsedCpuTime() - cpuStart
                val elapsed = SystemClock.elapsedRealtime() - start
                val after = awaitClock(host.video, before)
                checkGeometry()
                row.put("videoBufferWidth", width).put("videoBufferHeight", height)
                row.put("subtitleAndLayoutWidth", 1280).put("subtitleAndLayoutHeight", 720)
                row.put("firstPictureSecond", before).put("lastPictureSecond", after)
                row.put("elapsedMs", elapsed)
                row.put("appCpuMs", cpu)
                row.put("percentOfOneCore", 100.0 * cpu / elapsed)
                windows.put(row)
                File(directory, "sen-buffer-study.json").writeText(windows.toString(2))
                assertTrue("Picture clock must advance throughout the window: $row", after - before in 28..33)
            }
        }
    }

    private fun waitForHost(scenario: ActivityScenario<PlayerActivity>): SenPlayerSurfaceHost {
        val deadline = SystemClock.elapsedRealtime() + 45_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var host: SenPlayerSurfaceHost? = null
            scenario.onActivity { host = findHost(it.window.decorView)?.takeIf { v -> v.ready && v.width == 1280 } }
            host?.let { return it }
            SystemClock.sleep(100)
        }
        error("Expected an active landscape SenPlayer video and subtitle surface")
    }

    private fun findHost(view: View): SenPlayerSurfaceHost? {
        if (view is SenPlayerSurfaceHost) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) findHost(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun awaitClock(
        video: SurfaceView,
        previous: Int = -1,
    ): Int {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val clock = pictureClock(video)
            if (clock != null && clock > previous) return clock
            SystemClock.sleep(120)
        }
        error("No independent advancing video clock after $previous")
    }

    private fun pictureClock(video: SurfaceView): Int? {
        val done = CountDownLatch(1)
        val bitmap = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888)
        var result = PixelCopy.ERROR_UNKNOWN
        PixelCopy.request(
            video,
            bitmap,
            {
                result = it
                done.countDown()
            },
            Handler(Looper.getMainLooper()),
        )
        check(done.await(5, TimeUnit.SECONDS)) { "Timed out copying a SenPlayer picture" }
        return try {
            fun red(x: Int) = bitmap.getPixel(x, 9) shr 16 and 255
            if (result != PixelCopy.SUCCESS || red(4) < 180 || red(12) > 65) return null
            var clock = 0
            for (bit in 0 until 7) {
                val value = red(20 + 8 * bit)
                if (value in 66..179) return null
                if (value >= 180) clock = clock or (1 shl bit)
            }
            clock
        } finally {
            bitmap.recycle()
        }
    }
}
