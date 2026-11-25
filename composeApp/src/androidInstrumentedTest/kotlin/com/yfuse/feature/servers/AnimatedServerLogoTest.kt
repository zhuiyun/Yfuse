package com.yfuse.feature.servers

import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.util.Base64
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yfuse.core.designsystem.YfuseTheme
import com.yfuse.core.model.SavedServer
import com.yfuse.launchMainActivityForInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class AnimatedServerLogoTest {
    @Test
    fun serverCardDisplaysBothGifFramesInsideACircle() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val gif = File.createTempFile("server-logo-", ".gif", instrumentation.targetContext.cacheDir)
        // Two solid frames, red and green, alternating every 350 ms.
        gif.writeBytes(Base64.decode(TWO_FRAME_GIF, Base64.DEFAULT))
        val server =
            SavedServer("test", "https://example.test", "影院", "user", "用户", "", iconUrl = Uri.fromFile(gif).toString())
        val card = AtomicReference(Rect.Zero)
        val density = AtomicReference(1f)
        val seen = mutableSetOf<Int>()
        try {
            launchMainActivityForInput().use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        YfuseTheme(dark = false) {
                            density.set(LocalDensity.current.density)
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                ServerCard(
                                    server,
                                    false,
                                    null,
                                    false,
                                    null,
                                    "",
                                    false,
                                    {},
                                    {},
                                    Modifier.width(240.dp).onGloballyPositioned { card.set(it.boundsInWindow()) },
                                )
                            }
                        }
                    }
                }
                val deadline = SystemClock.uptimeMillis() + 15_000
                while (seen.size < 2 && SystemClock.uptimeMillis() < deadline) {
                    SystemClock.sleep(90)
                    val bounds = card.get()
                    if (bounds.isEmpty) continue
                    val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: continue
                    try {
                        val scale = density.get()
                        // The list card has a 30 dp badge inside a 12 dp inset.
                        val x = (bounds.left + 27 * scale).roundToInt()
                        val top = (bounds.top + 10 * scale).roundToInt()
                        val bottom = (bounds.top + 60 * scale).roundToInt()
                        val rows = (top..bottom).filter { bitmap.getPixel(x, it) in FRAME_COLORS }
                        if (rows.size < 26 * scale) continue
                        val y = (rows.first() + rows.last()) / 2
                        val color = bitmap.getPixel(x, y)
                        val offset = (12 * scale).roundToInt()
                        for (dx in listOf(-offset, offset)) {
                            assertEquals(color, bitmap.getPixel(x + dx, y))
                            for (dy in listOf(-offset, offset)) assertNotEquals(color, bitmap.getPixel(x + dx, y + dy))
                        }
                        seen.add(color)
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
            assertEquals("The displayed logo must advance to both frames", FRAME_COLORS, seen)
        } finally {
            gif.delete()
        }
    }

    private companion object {
        val FRAME_COLORS = setOf(Color.RED, Color.GREEN)
        const val TWO_FRAME_GIF =
            "R0lGODlhAQABAIAAAP8AAAD/ACH/C05FVFNDQVBFMi4wAwEAAAAh+QQAIwAAACwAAAAAAQABAAACAkQB" +
                "ACH5BAAjAAAALAAAAAABAAEAAAICTAEAOw=="
    }
}
