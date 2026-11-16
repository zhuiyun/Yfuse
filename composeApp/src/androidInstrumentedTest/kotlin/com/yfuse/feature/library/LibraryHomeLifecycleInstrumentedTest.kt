package com.yfuse.feature.library

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yfuse.core.designsystem.AccessibilityOptions
import com.yfuse.core.designsystem.YfuseTheme
import com.yfuse.core.model.HomeRow
import com.yfuse.launchMainActivityForInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class LibraryHomeLifecycleInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test(timeout = 90_000L)
    fun arriving_libraries_start_at_first_card_and_later_refresh_keeps_user_scroll() {
        val rows = mutableStateOf(emptyList<HomeRow>())
        launchMainActivityForInput().use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    YfuseTheme(dark = false, accessibility = AccessibilityOptions(reduceMotion = true)) {
                        Box(Modifier.fillMaxSize().padding(top = 80.dp), contentAlignment = Alignment.TopCenter) {
                            Box(Modifier.width(320.dp)) {
                                LibraryCategoryCards(
                                    baseUrl = "",
                                    accessToken = "",
                                    rows = rows.value,
                                    playlists = emptyList(),
                                    onOpen = {},
                                    onOpenPlaylists = {},
                                    onRetry = {},
                                )
                            }
                        }
                    }
                }
            }
            await("Initial playlist placeholder") { find("播放列表") != null }
            capture("categories-before-arrival")
            scenario.onActivity {
                rows.value = listOf(HomeRow("movies", "测试电影", emptyList()), HomeRow("series", "测试剧集", emptyList()))
            }
            val density = instrumentation.targetContext.resources.displayMetrics.density
            val screenWidth = instrumentation.targetContext.resources.displayMetrics.widthPixels
            val expectedMinimumLeft = ((screenWidth - 320 * density) / 2 + 16 * density).toInt()
            await("First category fully inside the starting content inset") {
                rect("测试电影")?.left?.let { it >= expectedMinimumLeft } == true
            }
            val initialLeft = requireNotNull(rect("测试剧集")).left
            capture("categories-after-arrival")
            var scrollable = requireNotNull(find("测试电影"))
            while (!scrollable.isScrollable) scrollable = requireNotNull(scrollable.parent)
            assertTrue(scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
            await("User can scroll the category shelf") { rect("测试剧集")?.left?.let { it < initialLeft } == true }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(300)
            val userLeft = requireNotNull(rect("测试剧集")).left
            scenario.onActivity { rows.value = rows.value.map { it.copy(totalCount = 25) } }
            await("Refreshed counts") { find("25部") != null }
            assertEquals("Content refresh must retain the user's position", userLeft, requireNotNull(rect("测试剧集")).left)
            capture("categories-refreshed-user-position")
        }
    }

    @Test(timeout = 90_000L)
    fun cache_age_clock_stops_in_background_and_hidden_route_then_refreshes_on_return() {
        val active = mutableStateOf(true)
        val calls = AtomicInteger()
        launchMainActivityForInput().use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    rememberLibraryFreshnessTime(active.value, 1L, intervalMs = 50L) {
                        2_000_000_000_000L + calls.incrementAndGet()
                    }
                }
            }
            await("Foreground clock runs") { calls.get() >= 3 }
            scenario.moveToState(Lifecycle.State.CREATED)
            instrumentation.waitForIdleSync()
            val backgroundCalls = calls.get()
            SystemClock.sleep(500)
            assertEquals("Stopped activity must have no clock callbacks", backgroundCalls, calls.get())
            scenario.moveToState(Lifecycle.State.RESUMED)
            await("Returning activity refreshes the age") { calls.get() > backgroundCalls }
            scenario.onActivity { active.value = false }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(100)
            val hiddenCalls = calls.get()
            SystemClock.sleep(500)
            assertEquals("Hidden route must have no clock callbacks", hiddenCalls, calls.get())
            scenario.onActivity { active.value = true }
            await("Returning route refreshes the age") { calls.get() > hiddenCalls }
        }
    }

    private fun find(label: String): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString() == label) {
                node.refresh()
                if (node.isVisibleToUser) return node
            }
            for (index in 0 until node.childCount) visit(node.getChild(index))?.let { return it }
            return null
        }
        return visit(instrumentation.uiAutomation.rootInActiveWindow)
    }

    private fun rect(label: String): Rect? = find(label)?.let { node -> Rect().also(node::getBoundsInScreen) }

    private fun await(
        description: String,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.uptimeMillis() + 15_000L
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        capture("failure")
        throw AssertionError(description)
    }

    private fun capture(name: String) {
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "library-home-evidence")
        directory.mkdirs()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
