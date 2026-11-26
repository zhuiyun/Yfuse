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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
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
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class HeroLiftInstrumentedTest {
    @Test(timeout = 30_000)
    fun carousel_artwork_and_frame_dissolve_into_the_same_page_pixels() {
        val hero = AtomicReference(Rect.Zero)
        val framed = mutableStateOf(false)
        val background = mutableStateOf(Color(0xFFB76232))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    Box(Modifier.fillMaxSize().background(background.value).padding(top = 24.dp)) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(280.dp)
                                .onGloballyPositioned { hero.set(Rect(it.positionInWindow(), it.size.toSize())) },
                        ) {
                            val hasFrame = framed.value
                            LivingPosterArtworkPlane(livingPosterArtwork(400.dp, 280.dp, hasFrame), hasFrame) {
                                Box(Modifier.fillMaxSize().background(Color.White))
                            }
                            Box(
                                Modifier
                                    .align(Alignment.BottomStart)
                                    .fillMaxWidth()
                                    .height(180.dp)
                                    .livingPosterCaptionPadding(hasIndicator = true),
                            )
                            Box(
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .height(LivingPosterDefaults.captionBottom(hasIndicator = true))
                                    .livingPosterPageBlend(background),
                            )
                        }
                    }
                }
            }
            waitUntil("The reel was never laid out") { hero.get().height > 0f }
            val bounds = hero.get()
            val density = bounds.height / 280f
            for (hasFrame in listOf(false, true)) {
                scenario.onActivity {
                    framed.value = hasFrame
                    background.value = if (hasFrame) Color(0xFF284462) else Color(0xFFB76232)
                }
                SystemClock.sleep(180)
                val shot =
                    capture(
                        scenario,
                        Rect(bounds.left, bounds.bottom - 40f * density, bounds.right, bounds.bottom + 12f * density),
                    )
                try {
                    for (x in listOf(1, shot.width / 2, shot.width - 2)) {
                        val pagePixel = shot.getPixel(x, (46f * density).roundToInt())
                        val edgePixel = shot.getPixel(x, (39f * density).roundToInt())
                        val largestChange =
                            listOf(16, 8, 0).maxOf { shift ->
                                kotlin.math.abs(((edgePixel ushr shift) and 255) - ((pagePixel ushr shift) and 255))
                            }
                        assertTrue(
                            "Visible bottom edge (framed=$hasFrame, x=$x, channel change=$largestChange)",
                            largestChange <= 4,
                        )
                        for (dp in 15..39 step 4) {
                            val lowerPixel = shot.getPixel(x, (dp * density).roundToInt())
                            val lowerChange =
                                listOf(16, 8, 0).maxOf { shift ->
                                    kotlin.math.abs(
                                        ((lowerPixel ushr shift) and 255) - ((pagePixel ushr shift) and 255),
                                    )
                                }
                            assertTrue(
                                "Bottom transition has a colour band (framed=$hasFrame, x=$x, row=$dp, change=$lowerChange)",
                                lowerChange <= 6,
                            )
                        }
                    }
                } finally {
                    shot.recycle()
                }
            }
        }
    }

    @Test(timeout = 30_000)
    fun caption_fades_above_text_and_keeps_white_artwork_readable() {
        val caption = AtomicReference(Rect.Zero)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    Box(Modifier.fillMaxSize().background(Color.White).padding(top = 80.dp)) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                                .onGloballyPositioned { caption.set(Rect(it.positionInWindow(), it.size.toSize())) }
                                .heroCaptionScrim(),
                        )
                    }
                }
            }
            waitUntil("The caption was never laid out") { caption.get().height > 0f }
            SystemClock.sleep(300)
            val bounds = caption.get()
            val density = bounds.height / 220f
            val shot = capture(scenario, Rect(bounds.left, bounds.top - 75 * density, bounds.right, bounds.bottom))
            try {
                fun grayAt(dpFromTop: Int): Int {
                    val y = ((75 + dpFromTop) * density).roundToInt()
                    return android.graphics.Color.red(shot.getPixel(shot.width / 2, y))
                }

                val profile = (-72..24 step 4).map(::grayAt)
                val largestDrop = profile.zipWithNext { above, below -> above - below }.max()
                assertTrue("Caption fade is too abrupt: $profile", largestDrop <= 20 && grayAt(-28) in 150..215)
                assertTrue("White artwork lost its brightness above the fade", profile.first() >= 246)
                assertTrue("Caption text lost its contrast on white artwork", grayAt(24) in 90..105)
            } finally {
                shot.recycle()
            }
        }
    }

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
