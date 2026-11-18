package com.yfuse.feature.player

import android.graphics.Bitmap
import android.graphics.Rect
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
        val transport = args.getString("senBufferTransport", "file")
        val url =
            when (transport) {
                "file" -> Uri.fromFile(fixture).toString()
                "http" -> "http://127.0.0.1:18389/buffer-clock.mp4"
                else -> error("Unknown clock fixture transport")
            }
        val windows = JSONArray()
        for ((index, sourceSize) in listOf(false, true, true, false).withIndex()) {
            val item = externalPlaybackItem(url, "合成功耗样本", "video/mp4")
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
                val evidence = File(directory, "buffer-$transport-$index")
                val initial = awaitClock(host.video, File("${evidence.path}-before"))
                val playingBeforeChange = awaitClock(host.video, File("${evidence.path}-before"), initial.second)
                val width = if (sourceSize) 640 else 1280
                val height = if (sourceSize) 360 else 720
                scenario.onActivity {
                    host.video.holder.setSizeFromLayout()
                    host.video.pivotX = 0f
                    host.video.pivotY = 0f
                    host.video.scaleX = host.width.toFloat() / width
                    host.video.scaleY = host.height.toFloat() / height
                    host.video.layoutParams =
                        host.video.layoutParams.apply {
                            this.width = width
                            this.height = height
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
                        val geometry = geometry(host, it.window.decorView)
                        row.put("geometry", geometry)
                        File("${evidence.path}-geometry.json").writeText(geometry.toString(2))
                        assertEquals(1280, geometry.getInt("windowWidth"))
                        assertEquals(720, geometry.getInt("windowHeight"))
                        assertEquals(1280, geometry.getInt("parentWidth"))
                        assertEquals(720, geometry.getInt("parentHeight"))
                        assertEquals(width, geometry.getInt("videoLayoutWidth"))
                        assertEquals(height, geometry.getInt("videoLayoutHeight"))
                        assertEquals(width, geometry.getInt("videoBufferWidth"))
                        assertEquals(height, geometry.getInt("videoBufferHeight"))
                        assertEquals(1280, geometry.getInt("subtitleLayoutWidth"))
                        assertEquals(720, geometry.getInt("subtitleLayoutHeight"))
                        assertEquals(1280, geometry.getInt("subtitleBufferWidth"))
                        assertEquals(720, geometry.getInt("subtitleBufferHeight"))
                        assertEquals(geometry.getString("parentBounds"), geometry.getString("videoDisplayBounds"))
                        assertEquals(geometry.getString("parentBounds"), geometry.getString("subtitleDisplayBounds"))
                    }
                }
                checkGeometry()
                val first = awaitClock(host.video, evidence)
                SystemClock.sleep(4_000)
                val before = awaitClock(host.video, evidence, first.second)
                val start = SystemClock.elapsedRealtime()
                val cpuStart = Process.getElapsedCpuTime()
                SystemClock.sleep(30_000)
                val cpu = Process.getElapsedCpuTime() - cpuStart
                val elapsed = SystemClock.elapsedRealtime() - start
                val after = awaitClock(host.video, evidence, before.second)
                checkGeometry()
                capturePictures(host.video, evidence)
                row.put("videoBufferWidth", width).put("videoBufferHeight", height)
                row.put("transport", transport)
                row.put("advancingBeforeResizeSecond", playingBeforeChange.second)
                row.put("firstPictureSecond", before.second).put("lastPictureSecond", after.second)
                row.put("firstPictureSource", before.observer).put("lastPictureSource", after.observer)
                row.put("elapsedMs", elapsed)
                row.put("appCpuMs", cpu)
                row.put("percentOfOneCore", 100.0 * cpu / elapsed)
                windows.put(row)
                File(directory, "sen-buffer-$transport.json").writeText(windows.toString(2))
                assertTrue(
                    "Picture clock must advance throughout the window: $row",
                    after.second - before.second in 28..33,
                )
            }
        }
    }

    private fun geometry(
        host: SenPlayerSurfaceHost,
        window: View,
    ): JSONObject {
        fun bounds(view: View): String = Rect().also { view.getGlobalVisibleRect(it) }.flattenToString()
        val videoFrame = host.video.holder.surfaceFrame
        val subtitleFrame = host.subtitles.holder.surfaceFrame
        return JSONObject().apply {
            put("windowWidth", window.width).put("windowHeight", window.height)
            put("parentWidth", host.width).put("parentHeight", host.height)
            put("parentBounds", bounds(host))
            put("videoLayoutWidth", host.video.width).put("videoLayoutHeight", host.video.height)
            put("videoBufferWidth", videoFrame.width()).put("videoBufferHeight", videoFrame.height())
            put("videoScaleX", host.video.scaleX.toDouble()).put("videoScaleY", host.video.scaleY.toDouble())
            put("videoDisplayBounds", bounds(host.video))
            put("subtitleLayoutWidth", host.subtitles.width).put("subtitleLayoutHeight", host.subtitles.height)
            put("subtitleBufferWidth", subtitleFrame.width()).put("subtitleBufferHeight", subtitleFrame.height())
            put("subtitleDisplayBounds", bounds(host.subtitles))
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

    private data class Picture(
        val second: Int,
        val observer: String,
    )

    private fun awaitClock(
        video: SurfaceView,
        evidence: File,
        previous: Int = -1,
    ): Picture {
        val deadline = SystemClock.elapsedRealtime() + 45_000
        var lastCopyCode = PixelCopy.ERROR_UNKNOWN
        val bitmap = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888)
        try {
            while (SystemClock.elapsedRealtime() < deadline) {
                lastCopyCode = copyVideo(video, bitmap)
                if (lastCopyCode == PixelCopy.SUCCESS) {
                    readClock(bitmap)?.takeIf { it > previous }?.let { return Picture(it, "video-surface") }
                }
                val display = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                if (display != null) {
                    try {
                        if (display.width == 1280 && display.height == 720) {
                            readClock(display)?.takeIf { it > previous }?.let { return Picture(it, "display") }
                        }
                    } finally {
                        display.recycle()
                    }
                }
                SystemClock.sleep(200)
            }
            capturePictures(video, evidence)
        } finally {
            bitmap.recycle()
        }
        error("No independent picture clock after $previous (last PixelCopy code $lastCopyCode)")
    }

    private fun capturePictures(
        video: SurfaceView,
        evidence: File,
    ) {
        val bitmap = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888)
        try {
            if (copyVideo(video, bitmap) == PixelCopy.SUCCESS) savePicture(bitmap, File("${evidence.path}-surface.png"))
        } finally {
            bitmap.recycle()
        }
        val display = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        try {
            savePicture(display, File("${evidence.path}-display.png"))
        } finally {
            display.recycle()
        }
    }

    private fun copyVideo(
        video: SurfaceView,
        bitmap: Bitmap,
    ): Int {
        val done = CountDownLatch(1)
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
        return result
    }

    private fun readClock(bitmap: Bitmap): Int? {
        fun white(
            x: Int,
            y: Int,
        ): Boolean? {
            val colour = bitmap.getPixel(x * bitmap.width / 160, y * bitmap.height / 90)
            val r = colour shr 16 and 255
            val g = colour shr 8 and 255
            val b = colour and 255
            return when {
                minOf(r, g, b) >= 180 -> true
                maxOf(r, g, b) <= 65 -> false
                else -> null
            }
        }

        fun row(y: Int): Int? {
            if (white(4, y) != true || white(12, y) != false) return null
            var second = 0
            for (bit in 0 until 7) {
                val bright = white(20 + 8 * bit, y) ?: return null
                if (bright) second = second or (1 shl bit)
            }
            return second
        }

        val second = row(8) ?: return null
        return second.takeIf { it < 96 && it == row(10) }
    }

    private fun savePicture(
        bitmap: Bitmap,
        file: File,
    ) {
        file.outputStream().use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
    }
}
