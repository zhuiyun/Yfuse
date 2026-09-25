package com.yfuse.core.designsystem

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yfuse.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class HeroLiftInstrumentedTest {
    // The detail page's title sheet: lifted further than it is tall, so its list slot is 0px and
    // every line of it sits above that slot, over the artwork. Recorded into the slot's own layer,
    // the text met an empty clip and was never drawn — 片名, 年份, 评分 and 类型 went missing.
    @Test(timeout = 30_000)
    fun text_lifted_out_of_an_empty_lazy_slot_is_still_drawn() {
        val title = AtomicReference(Rect.Zero)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    LazyColumn(Modifier.fillMaxSize().background(Color.Black)) {
                        item { Box(Modifier.fillMaxWidth().height(400.dp)) }
                        item {
                            Column(Modifier.fillMaxWidth().liftOverHero(240.dp)) {
                                BasicText(
                                    text = "片名 Title",
                                    style = TextStyle(color = Color.White, fontSize = 40.sp),
                                    modifier =
                                        Modifier.onGloballyPositioned { coordinates ->
                                            title.set(Rect(coordinates.positionInWindow(), coordinates.size.toSize()))
                                        },
                                )
                            }
                        }
                    }
                }
            }
            waitUntil("The lifted title was never laid out") { title.get().height > 0f }
            SystemClock.sleep(300)
            val shot = capture(scenario, title.get())
            try {
                assertTrue("The lifted title drew no text", brightShare(shot) > 0.02)
            } finally {
                shot.recycle()
            }
        }
    }

    private fun brightShare(bitmap: Bitmap): Double {
        var bright = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                if (android.graphics.Color.red(pixel) > 200 &&
                    android.graphics.Color.green(pixel) > 200 &&
                    android.graphics.Color.blue(pixel) > 200
                ) {
                    bright++
                }
            }
        }
        return bright.toDouble() / (bitmap.width * bitmap.height)
    }

    private fun capture(
        scenario: ActivityScenario<MainActivity>,
        bounds: Rect,
    ): Bitmap {
        val rect =
            android.graphics.Rect(
                bounds.left.toInt(),
                bounds.top.toInt(),
                bounds.right.toInt(),
                bounds.bottom.toInt(),
            )
        val bitmap = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
        val done = CountDownLatch(1)
        var result = -1
        scenario.onActivity { activity ->
            PixelCopy.request(activity.window, rect, bitmap, {
                result = it
                done.countDown()
            }, Handler(Looper.getMainLooper()))
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(PixelCopy.SUCCESS, result)
        return bitmap
    }

    private fun waitUntil(
        message: String,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(10)
        }
        assertTrue(message, condition())
    }
}
