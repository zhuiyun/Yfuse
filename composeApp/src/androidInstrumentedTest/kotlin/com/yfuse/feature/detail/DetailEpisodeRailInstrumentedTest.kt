package com.yfuse.feature.detail

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.yfuse.core.designsystem.AccessibilityOptions
import com.yfuse.core.designsystem.LocalMotionBudget
import com.yfuse.core.designsystem.LocalRouteVisible
import com.yfuse.core.designsystem.MotionBudget
import com.yfuse.core.designsystem.YfuseTheme
import com.yfuse.core.model.Episode
import com.yfuse.launchMainActivityForInput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class DetailEpisodeRailInstrumentedTest {
    @Test(timeout = 90_000L)
    fun replacingCatalogEpisodesWithLibraryEpisodesNeverKeepsTheOldCardsOnTop() {
        val previousScale = shell("settings get global animator_duration_scale").trim()
        // Keep departing layers alive long enough to inspect a real replacement frame rather
        // than accepting a screenshot taken after the old fade has happened to finish.
        shell("settings put global animator_duration_scale 10")
        try {
            for (fontScale in listOf(1f, 1.3f)) checkReplacement(fontScale)
        } finally {
            shell(
                if (previousScale == "null") {
                    "settings delete global animator_duration_scale"
                } else {
                    "settings put global animator_duration_scale $previousScale"
                },
            )
        }
    }

    private fun checkReplacement(fontScale: Float) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val oldArt = artwork(android.graphics.Color.RED)
        val newArt = artwork(android.graphics.Color.GREEN)
        val source = mutableStateOf(false)
        val bounds = AtomicReference(Rect.Zero)
        try {
            runBlocking {
                for (file in listOf(oldArt, newArt)) {
                    val result =
                        SingletonImageLoader
                            .get(
                                context,
                            ).execute(ImageRequest.Builder(context).data(file.toURI().toString()).build())
                    assertTrue("The local regression artwork did not decode", result is SuccessResult)
                }
            }
            launchMainActivityForInput().use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        YfuseTheme(dark = false, accessibility = AccessibilityOptions()) {
                            val density = LocalDensity.current
                            CompositionLocalProvider(
                                LocalDensity provides Density(density.density, fontScale),
                                LocalRouteVisible provides true,
                                LocalMotionBudget provides MotionBudget(),
                            ) {
                                Box(Modifier.fillMaxSize().background(Color.White).padding(top = 90.dp)) {
                                    Box(Modifier.onGloballyPositioned { bounds.set(it.boundsInWindow()) }) {
                                        val replacement = source.value
                                        val episodes = episodes(replacement)
                                        EpisodeSection(
                                            baseUrl = "",
                                            accessToken = "",
                                            episodes = episodes,
                                            seriesPosterUrl = (if (replacement) newArt else oldArt).toURI().toString(),
                                            // Keep the same scroll target, so a snap to another index
                                            // cannot accidentally clear the old animation for us.
                                            selectedEpisodeId = episodes.first().id,
                                            accent = Color.Blue,
                                            seasonLabel = "第 1 季",
                                            availableEpisodeCount = episodes.size,
                                            seasonCount = 1,
                                            seasonLoading = false,
                                            listedSeasonId = "1",
                                            pickerOpen = false,
                                            onTogglePicker = {},
                                            onPickerAnchor = {},
                                            onManageProgress = {},
                                            onPlayEpisode = {},
                                            onSeeAll = {},
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                awaitFrame(bounds, "catalog-$fontScale") { redPixels(it, bounds.get()) > 400 }.recycle()
                scenario.onActivity { source.value = true }
                val frame = awaitFrame(bounds, "library-$fontScale") { greenPixels(it, bounds.get()) > 400 }
                try {
                    assertEquals(
                        "The previous source still paints a second episode card",
                        0,
                        redPixels(frame, bounds.get()),
                    )
                } finally {
                    frame.recycle()
                }
            }
        } finally {
            oldArt.delete()
            newArt.delete()
        }
    }

    private fun episodes(replacement: Boolean): List<Episode> =
        (1..3).map { number ->
            Episode(
                id = "${if (replacement) "library" else "tmdb"}-$number",
                name = if (replacement) "媒体库正片 $number" else "目录剧集 $number",
                indexNumber = number,
                seasonNumber = 1,
                seasonId = "1",
                // A taller neighbour keeps the rail height while the first replacement card
                // shrinks. A retained old image then protrudes above the new card, as reported.
                overview =
                    if (replacement && number == 1) {
                        null
                    } else if (replacement) {
                        "媒体库正片简介"
                    } else {
                        "切换来源后不应保留这段旧简介"
                    },
                runtimeMinutes = if (replacement) 48 else 59,
                primaryTag = null,
                playedPercentage = null,
                resumePositionTicks = null,
            )
        }

    private fun awaitFrame(
        bounds: AtomicReference<Rect>,
        name: String,
        predicate: (Bitmap) -> Boolean,
    ): Bitmap {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.elapsedRealtime() + 15_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (bounds.get().width > 0) {
                val frame = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                if (predicate(frame)) {
                    val output =
                        InstrumentationRegistry
                            .getArguments()
                            .getString("additionalTestOutputDir")
                            ?.let(::File) ?: instrumentation.targetContext.getExternalFilesDir(null)!!
                    output.mkdirs()
                    File(output, "episode-rail-$name.png").outputStream().use {
                        assertTrue(frame.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                    return frame
                }
                frame.recycle()
            }
            SystemClock.sleep(25L)
        }
        error("The $name episode artwork was not drawn")
    }

    private fun redPixels(
        bitmap: Bitmap,
        bounds: Rect,
    ): Int = coloredPixels(bitmap, bounds) { r, g, b -> r > 120 && r > g + 35 && r > b + 35 }

    private fun greenPixels(
        bitmap: Bitmap,
        bounds: Rect,
    ): Int = coloredPixels(bitmap, bounds) { r, g, b -> g > 100 && g > r + 35 && g > b + 35 }

    private fun coloredPixels(
        bitmap: Bitmap,
        bounds: Rect,
        matches: (Int, Int, Int) -> Boolean,
    ): Int {
        var count = 0
        for (y in bounds.top.toInt().coerceAtLeast(0) until bounds.bottom.toInt().coerceAtMost(bitmap.height) step 2) {
            for (x in bounds.left.toInt().coerceAtLeast(
                0,
            ) until bounds.right.toInt().coerceAtMost(bitmap.width) step 2) {
                val pixel = bitmap.getPixel(x, y)
                if (matches(
                        android.graphics.Color.red(pixel),
                        android.graphics.Color.green(pixel),
                        android.graphics.Color.blue(pixel),
                    )
                ) {
                    count++
                }
            }
        }
        return count
    }

    private fun artwork(color: Int): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(context.cacheDir, "episode-rail-${UUID.randomUUID()}.png")
        val bitmap = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        return output
    }

    private fun shell(command: String): String =
        InstrumentationRegistry
            .getInstrumentation()
            .uiAutomation
            .executeShellCommand(command)
            .let { ParcelFileDescriptor.AutoCloseInputStream(it) }
            .bufferedReader()
            .use { it.readText() }
}
