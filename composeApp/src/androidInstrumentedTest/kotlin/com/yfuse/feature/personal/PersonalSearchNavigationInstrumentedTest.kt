package com.yfuse.feature.personal

import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yfuse.core.designsystem.OfficialNavDisplay
import com.yfuse.core.designsystem.ThemeText
import com.yfuse.core.designsystem.YfuseTheme
import com.yfuse.launchMainActivityForInput
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real pointer presses retain the animated feedback which accessibility-only clicks bypass. */
@RunWith(AndroidJUnit4::class)
class PersonalSearchNavigationInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test(timeout = 120_000L)
    fun repeated_search_collapse_keeps_the_current_list_and_back_returns_one_level() {
        val pages = mutableStateOf(listOf("profile", "personal"))
        launchMainActivityForInput().use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    // Use the normal animated theme, including press feedback and particles.
                    YfuseTheme(dark = true) {
                        OfficialNavDisplay(
                            backStack = pages.value,
                            onBack = { pages.value = pages.value.dropLast(1) },
                            contentKey = { it },
                        ) { page ->
                            if (page == "personal") {
                                PersonalContentScreen(
                                    state = PersonalCenterState(),
                                    onIntent = {},
                                    servers = emptyList(),
                                    onBack = { pages.value = pages.value.dropLast(1) },
                                    onOpenMedia = {},
                                    initialTab = PersonalCenterTab.WatchLater,
                                    onSwipeable = {},
                                )
                            } else {
                                Box(Modifier.fillMaxSize()) { ThemeText("片单返回后的个人页") }
                            }
                        }
                    }
                }
            }
            for ((tab, empty) in listOf(
                "想看" to "还没有想看的作品。在作品详情中加入想看，留待下次观看。",
                "收藏" to "还没有收藏。在作品详情中加入收藏，喜欢的作品会留在这里。",
                "观看历史" to "还没有观看记录。播放后，可以在这里查看已看的作品。",
            )) {
                tap(tab)
                repeat(3) {
                    tap("搜索片单")
                    await("Search opened on $tab") { node("收起搜索") != null && node(empty) != null }
                    tap("收起搜索")
                    // The release crash happened here, before the subsequent Back command.
                    await("Search collapsed without leaving $tab") { node("搜索片单") != null && node(empty) != null }
                    assertTrue("Search remained open", node("收起搜索") == null)
                }
            }
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            await("Back returns to the immediate parent") { node("片单返回后的个人页") != null }
            assertTrue("Personal list is still visible after Back", node("搜索片单") == null)
            assertTrue("Back removed more than one route", pages.value == listOf("profile"))
        }
    }

    private fun tap(label: String) {
        var target: AccessibilityNodeInfo? = null
        await("Visible $label") {
            target = node(label)
            target != null
        }
        val bounds = Rect()
        requireNotNull(target).getBoundsInScreen(bounds)
        val downAt = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event =
                MotionEvent.obtain(
                    downAt,
                    SystemClock.uptimeMillis(),
                    action,
                    bounds.exactCenterX(),
                    bounds.exactCenterY(),
                    0,
                )
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try {
                assertTrue(
                    "Pointer event rejected for $label",
                    instrumentation.uiAutomation.injectInputEvent(event, true),
                )
            } finally {
                event.recycle()
            }
            if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(40L)
        }
    }

    private fun node(label: String): AccessibilityNodeInfo? {
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        instrumentation.uiAutomation.rootInActiveWindow?.let(pending::addLast)
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (current.isVisibleToUser &&
                current.isEnabled &&
                (current.text?.toString() == label || current.contentDescription?.toString() == label)
            ) {
                return current
            }
            repeat(current.childCount) { current.getChild(it)?.let(pending::addLast) }
        }
        return null
    }

    private fun await(
        description: String,
        predicate: () -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            SystemClock.sleep(50L)
        }
        assertTrue(description, predicate())
    }
}
