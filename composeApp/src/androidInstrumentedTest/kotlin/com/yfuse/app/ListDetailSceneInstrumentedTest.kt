package com.yfuse.app

import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yfuse.core.designsystem.OfficialNavDisplay
import com.yfuse.core.designsystem.YfuseTheme
import com.yfuse.launchMainActivityForInput
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentHashMap

@RunWith(AndroidJUnit4::class)
class ListDetailSceneInstrumentedTest {
    @Test(timeout = 90_000)
    fun pickingASiblingAndResizingKeepsTheSelectedDetail() {
        val wide = mutableStateOf(true)
        val stack = mutableStateOf(listOf("home", "A"))
        val bounds = ConcurrentHashMap<String, Rect>()
        launchMainActivityForInput().use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    YfuseTheme(dark = false) {
                        CompositionLocalProvider(LocalWideWindow provides wide.value) {
                            OfficialNavDisplay(
                                backStack = stack.value,
                                onBack = { stack.value = stack.value.dropLast(1) },
                                contentKey = { it },
                                listPane = { it == "home" },
                            ) { page ->
                                DisposableEffect(page) { onDispose { bounds.remove(page) } }
                                Box(Modifier.fillMaxSize().onGloballyPositioned { bounds[page] = it.boundsInWindow() })
                            }
                        }
                    }
                }
            }
            awaitLayout { adjacent(bounds["home"], bounds["A"]) }
            scenario.onActivity { stack.value = stackAfterSelection(stack.value, "home", "B") }
            awaitLayout { bounds["A"] == null && adjacent(bounds["home"], bounds["B"]) }
            val splitWidth = checkNotNull(bounds["B"]).width
            scenario.onActivity { wide.value = false }
            awaitLayout { bounds["home"] == null && (bounds["B"]?.width ?: 0f) > splitWidth + 1f }
            scenario.onActivity { wide.value = true }
            awaitLayout { adjacent(bounds["home"], bounds["B"]) }
            scenario.onActivity { stack.value = stack.value.dropLast(1) }
            awaitLayout { bounds["B"] == null && (bounds["home"]?.width ?: 0f) > 0f }
        }
    }

    private fun adjacent(
        left: Rect?,
        right: Rect?,
    ): Boolean = left != null && right != null && left.width > 0f && right.width > 0f && left.right <= right.left + 1f

    private fun awaitLayout(predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 8_000
        while (!predicate() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(20)
        assertTrue("Both pane bounds must reflect the current route and window", predicate())
    }
}
