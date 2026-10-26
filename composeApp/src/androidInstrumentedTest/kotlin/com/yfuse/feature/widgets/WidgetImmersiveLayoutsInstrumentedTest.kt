package com.yfuse.feature.widgets

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yfuse.MainActivity
import com.yfuse.core.designsystem.AccessibilityOptions
import com.yfuse.core.designsystem.LocalMotionBudget
import com.yfuse.core.designsystem.LocalRouteVisible
import com.yfuse.core.designsystem.MotionBudget
import com.yfuse.core.designsystem.YfuseTheme
import com.yfuse.core.widget.WidgetMedia
import com.yfuse.core.widget.WidgetStyle
import com.yfuse.launchMainActivityForInput
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Exercises the public style dispatch and actual Compose semantics, without network artwork. */
@RunWith(AndroidJUnit4::class)
class WidgetImmersiveLayoutsInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var currentCase: RenderCase? = null
    private val styles =
        listOf(
            WidgetStyle.ArcPoster,
            WidgetStyle.OrbitResume,
            WidgetStyle.BreakoutPoster,
            WidgetStyle.PrismGallery,
            WidgetStyle.RibbonGallery,
        )
    private val films =
        (1..4).map { index ->
            WidgetMedia(
                id = "preview-$index",
                title = "原始来源影片 $index：长标题与真实详情路径",
                year = "2026",
                progress = if (index == 1) .4f else null,
                durationMinutes = if (index == 1) 100 else null,
                link = "source://confirmed-film-$index",
                videoUrl = "source://confirmed-stream-$index",
                headers = mapOf("X-Test-Source" to "film-$index"),
            )
        }

    @Test(timeout = 180_000L)
    fun switching_and_refreshing_keep_the_original_detail_payload_for_every_style() {
        withWidgetScenario { scenario ->
            val probe = mount(scenario)
            for ((index, style) in styles.withIndex()) {
                show(scenario, probe, RenderCase(style, films, session = index))
                awaitDetail(films[0])
                clickDetail(films[0], probe)
                assertSame("$style replaced the source payload", films[0], probe.opened.get())

                probe.opened.set(null)
                clickControl("下一部")
                awaitDetail(films[1])
                assertNull("$style opened details while only selecting a neighbour", probe.opened.get())
                clickDetail(films[1], probe)
                assertSame(films[1], probe.opened.get())

                // A source refresh may reorder equal-sized collections; position alone is insufficient.
                show(scenario, probe, probe.input.value.copy(items = films.reversed()))
                awaitDetail(films[1])
                clickDetail(films[1], probe)
                assertSame("$style lost the selected identity after refresh", films[1], probe.opened.get())

                show(scenario, probe, probe.input.value.copy(items = listOf(films[3])))
                awaitDetail(films[3])
                clickDetail(films[3], probe)
                assertSame("$style retained a removed source item", films[3], probe.opened.get())
            }
        }
    }

    @Test(timeout = 180_000L)
    fun real_swipes_and_direction_keys_select_without_opening_details() {
        withWidgetScenario { scenario ->
            val probe = mount(scenario)
            for ((index, style) in styles.withIndex()) {
                show(scenario, probe, RenderCase(style, films, session = index))
                awaitDetail(films[0])
                probe.opened.set(null)
                swipeNext(scenario, probe.bounds.get())
                await("$style did not respond to the horizontal drag") {
                    films.drop(1).any { detailNode(it) != null }
                }
                assertNull("$style treated a drag as a click", probe.opened.get())

                // Reduced-motion RTL still needs physical left/right keyboard navigation.
                show(scenario, probe, RenderCase(style, films, rtl = true, reduced = true, session = index + 100))
                scenario.onActivity { probe.inputMode.get().requestInputMode(InputMode.Keyboard) }
                instrumentation.waitForIdleSync()
                val focused = awaitDetail(films[0])
                assertTrue(
                    "$style has no keyboard-focusable current artwork",
                    focused.performAction(AccessibilityNodeInfo.ACTION_FOCUS),
                )
                scenario.onActivity { activity ->
                    activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
                    activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT))
                }
                awaitDetail(films[1])
                assertNull("$style opened details for a direction key", probe.opened.get())
                clickDetail(films[1], probe)
                assertSame(films[1], probe.opened.get())
                scenario.onActivity { probe.inputMode.get().requestInputMode(InputMode.Touch) }
            }
        }
    }

    @Test(timeout = 180_000L)
    fun normal_narrow_large_text_rtl_empty_and_single_layouts_stay_bounded() {
        withWidgetScenario { scenario ->
            val probe = mount(scenario)
            var session = 0
            for (style in styles) {
                for (narrow in listOf(false, true)) {
                    val input =
                        RenderCase(
                            style,
                            films,
                            width = if (narrow) 180 else 320,
                            fontScale = if (narrow) 2f else 1f,
                            rtl = narrow,
                            reduced = narrow,
                            session = session++,
                        )
                    show(scenario, probe, input)
                    awaitDetail(films[0])
                    settle()
                    val name = "${style.id}-${if (narrow) "180dp-font2-rtl" else "320dp"}"
                    assertBoundedAndSave(probe, name)
                    clickControl("下一部")
                    awaitDetail(films[1])

                    // A one-item source must not manufacture duplicates or navigation affordances.
                    show(scenario, probe, input.copy(items = listOf(films[0]), session = session++))
                    await("$style did not expose one film without navigation") {
                        val snapshot = nodes()
                        snapshot.any {
                            it.collectionInfo?.let { info -> info.rowCount == 1 && info.columnCount == 1 } ==
                                true
                        } &&
                            detailNode(films[0], snapshot) != null &&
                            controlNode("下一部", snapshot) == null &&
                            controlNode("上一部", snapshot) == null
                    }
                    settle()
                    assertNull("$style shows next for one film", controlNode("下一部"))
                    assertNull("$style shows previous for one film", controlNode("上一部"))
                    assertBoundedAndSave(probe, "$name-single")

                    show(scenario, probe, input.copy(items = emptyList(), session = session++))
                    await("$style kept drawing an empty collection") {
                        probe.bounds.get().height == 0f && detailNode(films[0]) == null
                    }
                }
            }
        }
    }

    @Test(timeout = 90_000L)
    fun prism_vertical_drags_rotate_both_caps_without_opening_details() {
        withWidgetScenario { scenario ->
            val probe = mount(scenario)
            for (rtl in listOf(false, true)) {
                show(
                    scenario,
                    probe,
                    RenderCase(
                        WidgetStyle.PrismGallery,
                        films,
                        rtl = rtl,
                        reduced = rtl,
                        session = if (rtl) 1 else 0,
                    ),
                )
                awaitDetail(films[0])
                val bounds = probe.bounds.get()
                val upper = Offset(bounds.center.x, bounds.top + bounds.width * .17f)
                val lower = Offset(bounds.center.x, bounds.top + bounds.width * .63f)
                drag(scenario, lower, upper)
                awaitDetail(films[2])
                assertNull("Vertical drag opened details", probe.opened.get())
                assertBoundedAndSave(probe, "prism-top-rtl-$rtl")
                drag(scenario, upper, lower)
                awaitDetail(films[0])
                drag(scenario, upper, lower)
                awaitDetail(films[3])
                assertNull("Vertical drag opened details", probe.opened.get())
                assertBoundedAndSave(probe, "prism-bottom-rtl-$rtl")
                clickDetail(films[3], probe)
                assertSame("The visible cap lost its source payload", films[3], probe.opened.get())
                probe.opened.set(null)
                show(scenario, probe, probe.input.value.copy(items = films.reversed()))
                awaitDetail(films[3])
                clickDetail(films[3], probe)
                assertSame("Refresh lost the selected cap", films[3], probe.opened.get())
                probe.opened.set(null)
            }
        }
    }

    @Test(timeout = 90_000L)
    fun spatial_galleries_keep_copy_and_pagination_on_one_compact_footer_at_normal_width() {
        val compactStyles =
            styles +
                listOf(
                    WidgetStyle.LayeredPoster,
                    WidgetStyle.DepthGallery,
                    WidgetStyle.Accordion,
                    WidgetStyle.BookSpine,
                    WidgetStyle.FoldingGallery,
                )
        withWidgetScenario { scenario ->
            val probe = mount(scenario)
            compactStyles.forEachIndexed { index, style ->
                show(scenario, probe, RenderCase(style, films, session = index))
                awaitDetail(films[0])
                if (style == WidgetStyle.BreakoutPoster) {
                    val hero = probe.bounds.get()
                    assertTrue(
                        "The breakout hero was compressed back into a square card",
                        hero.height >= hero.width * 1.4f,
                    )
                }
                val snapshot = nodes()
                val footer =
                    snapshot
                        .filter {
                            it.isClickable &&
                                hasLabel(it, films[0].title) &&
                                it.actionList.any { action -> action.label?.toString() == "查看详情" }
                        }.maxBy {
                            android.graphics
                                .Rect()
                                .also(it::getBoundsInScreen)
                                .top
                        }
                val copyBounds = android.graphics.Rect().also(footer::getBoundsInScreen)
                val previous = android.graphics.Rect().also(requireNotNull(controlNode("上一部"))::getBoundsInScreen)
                val next = android.graphics.Rect().also(requireNotNull(controlNode("下一部"))::getBoundsInScreen)
                val height =
                    maxOf(copyBounds.bottom, previous.bottom, next.bottom) -
                        minOf(copyBounds.top, previous.top, next.top)
                assertTrue("$style reserved a separate pagination row", height <= 72f * probe.density.get())
                assertTrue("$style shrank the previous touch target", previous.height() >= 47f * probe.density.get())
                assertTrue("$style shrank the next touch target", next.height() >= 47f * probe.density.get())
            }
        }
    }

    @Test(timeout = 90_000L)
    fun ribbon_current_artwork_stays_centered_in_ltr_and_rtl() {
        val source =
            instrumentation.targetContext.assets.open("widget-preview/space.png").use {
                requireNotNull(BitmapFactory.decodeStream(it))
            }
        try {
            withWidgetScenario { scenario ->
                val probe = mount(scenario)
                var session = 0
                for (single in listOf(false, true)) {
                    for (rtl in listOf(false, true)) {
                        val input =
                            RenderCase(
                                WidgetStyle.RibbonGallery,
                                if (single) listOf(films[0]) else films,
                                width = 180,
                                fontScale = 2f,
                                rtl = rtl,
                                reduced = true,
                                session = session++,
                            )
                        show(scenario, probe, input)
                        awaitDetail(films[0])
                        val expected = ribbonSourceSamples(probe, source)
                        // Compare with the bundled photograph, not another screenshot that could
                        // still show Coil's placeholder. The selected film must stay at the marker.
                        await("Ribbon's actual first artwork was not centered (single=$single, rtl=$rtl)") {
                            val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                            try {
                                val bounds = probe.bounds.get()
                                val actual =
                                    ribbonCenterSamples(
                                        screenshot,
                                        bounds.left.roundToInt(),
                                        bounds.top.roundToInt(),
                                        bounds.width,
                                    )
                                meanRgbDifference(expected, actual) <= 8.0
                            } finally {
                                screenshot.recycle()
                            }
                        }
                        clickDetail(films[0], probe)
                        assertSame("Ribbon's centered artwork opened another film", films[0], probe.opened.get())
                    }
                }
            }
        } finally {
            source.recycle()
        }
    }

    private fun ribbonSourceSamples(
        probe: Probe,
        source: Bitmap,
    ): IntArray {
        val density = probe.density.get()
        val widthDp = probe.bounds.get().width / density
        val fraction = if (probe.input.value.items.size == 1) .84f else .44f
        val posterLeft = (widthDp * (.5f - fraction / 2) * density).roundToInt()
        val posterTop = (widthDp * .76f * .055f * density).roundToInt()
        val posterWidth = ((widthDp * fraction + .5f) * density).roundToInt()
        val posterHeight = (widthDp * .76f * .83f * density).roundToInt()
        // Reproduce ContentScale.Crop for the actual source image inside a centered poster.
        val scale = max(posterWidth.toFloat() / source.width, posterHeight.toFloat() / source.height)
        val scaled =
            Bitmap.createScaledBitmap(
                source,
                (source.width * scale).roundToInt(),
                (source.height * scale).roundToInt(),
                true,
            )
        try {
            val cropLeft = ((posterWidth - scaled.width) / 2f).roundToInt()
            val cropTop = ((posterHeight - scaled.height) / 2f).roundToInt()
            return ribbonCenterSamples(scaled, -posterLeft - cropLeft, -posterTop - cropTop, probe.bounds.get().width)
        } finally {
            if (scaled !== source) scaled.recycle()
        }
    }

    private fun ribbonCenterSamples(
        bitmap: Bitmap,
        originX: Int,
        originY: Int,
        stageWidth: Float,
    ): IntArray =
        IntArray(12 * 12) { index ->
            // This patch stays inside the photograph and clear of the ribbon edges/centre marker.
            val x = originX + (stageWidth * (.42f + (index % 12) * .16f / 11)).roundToInt()
            val y = originY + (stageWidth * (.24f + (index / 12) * .16f / 11)).roundToInt()
            bitmap.getPixel(x, y)
        }

    private fun meanRgbDifference(
        expected: IntArray,
        actual: IntArray,
    ): Double =
        expected.indices
            .sumOf { index ->
                abs(android.graphics.Color.red(expected[index]) - android.graphics.Color.red(actual[index])) +
                    abs(android.graphics.Color.green(expected[index]) - android.graphics.Color.green(actual[index])) +
                    abs(android.graphics.Color.blue(expected[index]) - android.graphics.Color.blue(actual[index]))
            }.toDouble() / (expected.size * 3)

    private data class RenderCase(
        val style: WidgetStyle,
        val items: List<WidgetMedia>,
        val width: Int = 320,
        val fontScale: Float = 1f,
        val rtl: Boolean = false,
        val reduced: Boolean = false,
        val session: Int = 0,
    )

    private class Probe(
        initial: RenderCase,
    ) {
        val input = mutableStateOf(initial)
        val opened = AtomicReference<WidgetMedia>()
        val bounds = AtomicReference(Rect.Zero)
        val density = AtomicReference(1f)
        val inputMode = AtomicReference<InputModeManager>()
        val committedInput = AtomicReference<RenderCase>()
    }

    private fun mount(scenario: ActivityScenario<MainActivity>): Probe {
        val probe = Probe(RenderCase(styles.first(), films, session = -1))
        scenario.onActivity { activity ->
            activity.setContent {
                val input = probe.input.value
                YfuseTheme(dark = false, accessibility = AccessibilityOptions(reduceMotion = input.reduced)) {
                    val density = LocalDensity.current
                    val inputMode = LocalInputModeManager.current
                    SideEffect {
                        probe.inputMode.set(inputMode)
                        probe.committedInput.set(input)
                    }
                    CompositionLocalProvider(
                        LocalDensity provides Density(density.density, input.fontScale),
                        LocalLayoutDirection provides if (input.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                        LocalRouteVisible provides true,
                        LocalMotionBudget provides MotionBudget(),
                    ) {
                        Box(
                            Modifier.fillMaxSize().background(Color.White).padding(top = 64.dp),
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            Box(
                                Modifier.width(input.width.dp).onGloballyPositioned {
                                    probe.bounds.set(it.boundsInWindow())
                                    probe.density.set(density.density)
                                },
                            ) {
                                key(input.style, input.session) {
                                    WidgetCards(
                                        input.style,
                                        input.items,
                                        metadata = true,
                                        compact = false,
                                        onOpen = probe.opened::set,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        return probe
    }

    private fun show(
        scenario: ActivityScenario<MainActivity>,
        probe: Probe,
        input: RenderCase,
    ) {
        currentCase = input
        scenario.onActivity { probe.input.value = input }
        // A title can exist in both collections, so it cannot prove this render was committed.
        await("$input was not committed by Compose") { probe.committedInput.get() === input }
        // Allow the identity-restoration effect and motion to settle after the actual commit.
        settle()
    }

    private fun awaitDetail(film: WidgetMedia): AccessibilityNodeInfo {
        await("Current details for ${film.id} never appeared") { detailNode(film) != null }
        return requireNotNull(detailNode(film))
    }

    private fun clickDetail(
        film: WidgetMedia,
        probe: Probe,
    ) {
        probe.opened.set(null)
        assertTrue(awaitDetail(film).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        await("Details callback did not receive ${film.id}") { probe.opened.get() != null }
    }

    private fun clickControl(label: String) {
        await("$label control never appeared") { controlNode(label)?.isEnabled == true }
        assertTrue(requireNotNull(controlNode(label)).performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun detailNode(
        film: WidgetMedia,
        snapshot: List<AccessibilityNodeInfo> = nodes(),
    ): AccessibilityNodeInfo? =
        snapshot
            .filter {
                it.isClickable &&
                    it.isEnabled &&
                    hasLabel(it, film.title) &&
                    it.actionList.any { action ->
                        action.id == AccessibilityNodeInfo.ACTION_CLICK &&
                            action.label?.toString() == "查看详情"
                    }
            }.minByOrNull { node ->
                // The footer repeats this title/action. Focus the actual artwork stage so its
                // ancestor's direction-key handler is the one under test.
                android.graphics
                    .Rect()
                    .also(node::getBoundsInScreen)
                    .top
            }

    private fun controlNode(
        label: String,
        snapshot: List<AccessibilityNodeInfo> = nodes(),
    ): AccessibilityNodeInfo? = snapshot.firstOrNull { it.isClickable && hasLabel(it, label) }

    private fun hasLabel(
        node: AccessibilityNodeInfo,
        text: String,
    ): Boolean {
        // Compose exposes a merging clickable's description as a virtual label child when it
        // has descendants. The Android ACTION_CLICK remains on its parent, so the spoken label
        // and action need not be fields of the same AccessibilityNodeInfo.
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending.add(node)
        while (pending.isNotEmpty()) {
            val candidate = pending.removeFirst()
            if (candidate.contentDescription?.toString()?.contains(text) == true ||
                candidate.text?.toString()?.contains(text) == true
            ) {
                return true
            }
            repeat(candidate.childCount) { index ->
                candidate.getChild(index)?.let { child ->
                    // A neighbouring film is another action, not part of this action's label.
                    if (child.isVisibleToUser && !child.isClickable && !child.isLongClickable) pending.add(child)
                }
            }
        }
        return false
    }

    private fun nodes(includeInvisible: Boolean = false): List<AccessibilityNodeInfo> {
        // Virtual Compose nodes can remain cached after the collection is replaced.
        if (Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return emptyList()
        val result = mutableListOf<AccessibilityNodeInfo>()
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            if (includeInvisible || node.isVisibleToUser) result.add(node)
            repeat(node.childCount) { index -> node.getChild(index)?.let(pending::addLast) }
        }
        return result
    }

    private fun withWidgetScenario(block: (ActivityScenario<MainActivity>) -> Unit) {
        launchMainActivityForInput().use { scenario ->
            try {
                block(scenario)
            } catch (failure: Throwable) {
                // Capture before ActivityScenario closes the actual failing widget window.
                saveFailureEvidence(failure)
                throw failure
            }
        }
    }

    private fun evidenceDirectory(): File =
        File(
            requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)),
            "validation/widget-layouts",
        ).apply { mkdirs() }

    private fun saveFailureEvidence(failure: Throwable) {
        runCatching {
            val output = evidenceDirectory()
            val stem = "widget-failure-${SystemClock.uptimeMillis()}"
            File(output, "$stem-nodes.txt").writeText(
                buildString {
                    appendLine("Render case: $currentCase")
                    appendLine(failure.stackTraceToString())
                    nodes(includeInvisible = true).forEach { node ->
                        val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
                        appendLine(
                            "package=${node.packageName} class=${node.className} bounds=$bounds " +
                                "visible=${node.isVisibleToUser} enabled=${node.isEnabled} " +
                                "clickable=${node.isClickable} focusable=${node.isFocusable} " +
                                "collection=${node.collectionInfo} state=${node.stateDescription} " +
                                "text=${node.text} description=${node.contentDescription} " +
                                "actions=${node.actionList.joinToString { "${it.id}:${it.label}" }}",
                        )
                    }
                },
            )
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                try {
                    File(output, "$stem.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                } finally {
                    bitmap.recycle()
                }
            }
        }
    }

    private fun swipeNext(
        scenario: ActivityScenario<MainActivity>,
        bounds: Rect,
    ) {
        val from = Offset(bounds.left + bounds.width * .78f, bounds.top + bounds.width * .38f)
        val to = Offset(bounds.left + bounds.width * .18f, from.y)
        drag(scenario, from, to)
    }

    private fun drag(
        scenario: ActivityScenario<MainActivity>,
        from: Offset,
        to: Offset,
    ) {
        val down = SystemClock.uptimeMillis()

        fun send(
            action: Int,
            point: Offset,
        ) {
            scenario.onActivity { activity ->
                val decor = activity.window.decorView
                val origin = IntArray(2)
                decor.getLocationInWindow(origin)
                val event =
                    MotionEvent.obtain(
                        down,
                        SystemClock.uptimeMillis(),
                        action,
                        point.x - origin[0],
                        point.y - origin[1],
                        0,
                    )
                try {
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    decor.dispatchTouchEvent(event)
                } finally {
                    event.recycle()
                }
            }
        }
        send(MotionEvent.ACTION_DOWN, from)
        for (step in 1..16) {
            send(MotionEvent.ACTION_MOVE, from + (to - from) * (step / 16f))
            SystemClock.sleep(20)
        }
        send(MotionEvent.ACTION_UP, to)
    }

    private fun assertBoundedAndSave(
        probe: Probe,
        name: String,
    ) {
        val bounds = probe.bounds.get()
        val density = probe.density.get()
        val input = probe.input.value
        assertTrue("$name exceeded its allocated width", bounds.width <= input.width * density + 1f)
        assertTrue("$name has no measured artwork", bounds.width > 0 && bounds.height > 0)
        assertTrue("$name became taller than a bounded home component", bounds.height < 640f * density)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            assertTrue("$name extends below the visible test viewport", bounds.bottom + 8f * density < bitmap.height)
            // A white gutter around the measured component catches drawing overflow, including
            // transformed posters reaching into the next component's space at large text sizes.
            val gap = 3f * density
            val thickness = 4f * density
            val strips =
                listOf(
                    Rect(bounds.left, bounds.top - gap - thickness, bounds.right, bounds.top - gap),
                    Rect(bounds.left, bounds.bottom + gap, bounds.right, bounds.bottom + gap + thickness),
                    Rect(bounds.left - gap - thickness, bounds.top, bounds.left - gap, bounds.bottom),
                    Rect(bounds.right + gap, bounds.top, bounds.right + gap + thickness, bounds.bottom),
                )
            strips.forEach { strip ->
                for (y in strip.top.toInt().coerceAtLeast(0) until strip.bottom.toInt().coerceAtMost(bitmap.height) step
                    2) {
                    for (x in strip.left.toInt().coerceAtLeast(
                        0,
                    ) until strip.right.toInt().coerceAtMost(bitmap.width) step
                        2) {
                        val pixel = bitmap.getPixel(x, y)
                        assertTrue(
                            "$name painted outside its measured bounds at ($x, $y)",
                            android.graphics.Color.red(pixel) >= 248 &&
                                android.graphics.Color.green(pixel) >= 248 &&
                                android.graphics.Color.blue(pixel) >= 248,
                        )
                    }
                }
            }
            val output = evidenceDirectory()
            File(output, "widget-$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun settle() {
        SystemClock.sleep(350L)
        instrumentation.waitForIdleSync()
    }

    private fun await(
        message: String,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(25L)
        }
        assertTrue(message, condition())
    }
}
