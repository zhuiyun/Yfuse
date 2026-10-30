package com.yfuse.feature.widgets

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yfuse.core.designsystem.AccessibilityOptions
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.LocalRouteVisible
import com.yfuse.core.designsystem.YfuseTheme
import com.yfuse.core.widget.LocalWidgetCutoutRuntime
import com.yfuse.core.widget.WidgetCutoutArtwork
import com.yfuse.core.widget.WidgetCutoutConfiguration
import com.yfuse.core.widget.WidgetCutoutLoader
import com.yfuse.core.widget.WidgetCutoutRuntime
import com.yfuse.core.widget.WidgetCutoutStatus
import com.yfuse.core.widget.WidgetMedia
import com.yfuse.core.widget.WidgetStyle
import com.yfuse.launchMainActivityForInput
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import com.yfuse.core.designsystem.ThemeText as Text

/** Offline screenshots use the actual renderer and existing shipped artwork, with no cloud POST. */
@RunWith(AndroidJUnit4::class)
class WidgetBreakoutPresentationInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test(timeout = 60_000L)
    fun selectedSilhouetteAndOrdinaryPosterKeepTheSameCompactFrame() {
        val fallback = mutableStateOf(false)
        val frame = AtomicReference(androidx.compose.ui.geometry.Rect.Zero)
        val density = AtomicReference(1f)
        val astronaut = "file:///android_asset/widget-preview/breakout-astronaut.png"
        val rain = "file:///android_asset/widget-preview/rain.png"
        val runtime =
            WidgetCutoutRuntime(
                MutableStateFlow(WidgetCutoutConfiguration(true, true)),
                object : WidgetCutoutLoader {
                    override fun observe(source: String) = MutableStateFlow(WidgetCutoutStatus())

                    override suspend fun get(
                        source: String,
                        allowProcessing: Boolean,
                    ) = WidgetCutoutArtwork(
                        rain,
                        "file:///android_asset/widget-cutout/preview-astronaut.png",
                        512,
                        768,
                        .15f,
                        .82f,
                        supportsBreakout = false,
                    )
                },
            )
        launchMainActivityForInput().use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    val ordinary by fallback
                    YfuseTheme(dark = false, accessibility = AccessibilityOptions(reduceMotion = true)) {
                        density.set(LocalDensity.current.density)
                        CompositionLocalProvider(
                            LocalRouteVisible provides true,
                            LocalWidgetCutoutRuntime provides runtime,
                        ) {
                            Box(
                                Modifier.fillMaxSize().background(Color(0xFFF5F6FA)).padding(top = 64.dp),
                                Alignment.TopCenter,
                            ) {
                                Column(Modifier.width(330.dp)) {
                                    Text(
                                        "剧集推荐",
                                        Modifier.padding(start = 8.dp, bottom = 16.dp),
                                        style = AppTypography.body.medium,
                                    )
                                    Box(Modifier.onGloballyPositioned { frame.set(it.boundsInWindow()) }) {
                                        key(ordinary) {
                                            WidgetCards(
                                                WidgetStyle.BreakoutPoster,
                                                (0..2).map {
                                                    WidgetMedia(
                                                        id = "preview-breakout-${if (ordinary) it * 3 + 2 else it}",
                                                        title = "${if (ordinary) "普通海报" else "破框海报"} ${it + 1}",
                                                        poster = if (ordinary) rain else astronaut,
                                                    )
                                                },
                                                metadata = false,
                                                onOpen = {},
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            await("The current cutout did not finish loading") { findState("抠图完成") != null }
            clickNext()
            await("Selection did not advance") { findSelected("破框海报 2") != null }
            settle()
            val first = frame.get()
            assertTrue("Overhang still reserves half a poster", first.height < first.width * 1.5f)
            save("03-after-breakout.png")
            scenario.onActivity { fallback.value = true }
            await("Ordinary poster fallback never appeared") { findSelected("普通海报 1") != null }
            clickNext()
            await("Fallback selection did not advance") { findSelected("普通海报 2") != null }
            settle()
            assertEquals("Fallback changed gallery height", first.height, frame.get().height, density.get())
            save("04-after-fallback.png")
        }
    }

    private fun clickNext() {
        await("Next control missing") { findClickable("下一部")?.isEnabled == true }
        assertTrue(requireNotNull(findClickable("下一部")).performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun findSelected(label: String) = findClickable(label, "查看详情")

    private fun findClickable(
        label: String,
        actionLabel: String? = null,
    ) = find {
        it.isClickable &&
            hasLabel(it, label) &&
            (actionLabel == null || it.actionList.any { action -> action.label?.toString() == actionLabel })
    }

    private fun hasLabel(
        node: AccessibilityNodeInfo,
        label: String,
    ): Boolean {
        if (node.contentDescription?.toString()?.contains(label) == true ||
            node.text?.toString()?.contains(label) == true
        ) {
            return true
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            if (child.isVisibleToUser && !child.isClickable && hasLabel(child, label)) return true
        }
        return false
    }

    private fun findState(value: String) = find { it.stateDescription?.toString() == value }

    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun search(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (predicate(node)) return node
            for (index in 0 until node.childCount) search(node.getChild(index))?.let { return it }
            return null
        }
        return search(instrumentation.uiAutomation.rootInActiveWindow)
    }

    private fun await(
        message: String,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + 20_000L
        while (!condition()) {
            assertTrue(message, SystemClock.elapsedRealtime() < deadline)
            SystemClock.sleep(50)
        }
    }

    private fun settle() {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(600)
    }

    private fun save(name: String) {
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val folder =
                File(
                    instrumentation.targetContext.getExternalFilesDir(null),
                    "validation/breakout-presentation",
                ).apply {
                    mkdirs()
                }
            File(folder, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
    }
}
