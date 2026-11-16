package com.yfuse.feature.profile

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yfuse.core.account.AccountDeviceSession
import com.yfuse.core.designsystem.AccessibilityOptions
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.YfuseTheme
import com.yfuse.launchMainActivityForInput
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Actual account-session composables with isolated data and inert callbacks; no account service. */
@RunWith(AndroidJUnit4::class)
class AccountSessionsEvidenceInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val output by lazy {
        File(instrumentation.targetContext.getExternalFilesDir(null), "account-session-evidence").apply { mkdirs() }
    }

    private data class Scene(
        val dark: Boolean,
        val scale: Float,
        val name: String,
    )

    @Test(timeout = 240_000L)
    fun actual_session_states_and_long_lists_are_captured_in_both_themes_and_text_sizes() {
        val scene = mutableStateOf(Scene(false, 1f, "populated"))
        val current = AccountDeviceSession("qa-current", "测试当前手机", 0L, 1_794_800_000_000L, true)
        val others = (1..8).map { current.copy(id = "qa-$it", deviceName = "测试客厅电视与平板 $it", current = false) }
        launchMainActivityForInput().use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    val value = scene.value
                    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, value.scale)) {
                        YfuseTheme(dark = value.dark, accessibility = AccessibilityOptions(reduceMotion = true)) {
                            Box(Modifier.fillMaxSize().background(LocalPalette.current.background)) {
                                key(value) {
                                    AccountSessionsContent(
                                        sessions =
                                            if (value.name ==
                                                "empty"
                                            ) {
                                                listOf(current)
                                            } else {
                                                listOf(current) + others
                                            },
                                        onBack = {},
                                        loading = value.name == "loading",
                                        loadedOnce = value.name != "loading",
                                        error = "测试服务暂时不可用，请稍后重试。".takeIf { value.name == "error" },
                                        bulkBusy = value.name == "busy",
                                    )
                                }
                            }
                        }
                    }
                }
            }
            for (dark in listOf(false, true)) {
                for (scale in listOf(1f, 1.3f)) {
                    for (name in listOf("populated", "empty", "error", "loading", "busy")) {
                        scenario.onActivity { scene.value = Scene(dark, scale, name) }
                        instrumentation.waitForIdleSync()
                        SystemClock.sleep(400L)
                        val prefix = "${if (dark) "dark" else "light"}-$scale-$name"
                        assertTrue(
                            "Session header is visible",
                            nodes().any {
                                it.isVisibleToUser &&
                                    it.text?.contains("登录与会话") == true
                            },
                        )
                        capture("$prefix-top")
                        if (name == "loading") {
                            assertTrue(
                                "Refresh disabled during load",
                                nodes().none {
                                    it.isEnabled &&
                                        it.contentDescription?.contains("刷新登录设备") == true
                                },
                            )
                        } else {
                            captureScrolls(prefix)
                        }
                    }
                }
            }
        }
    }

    private fun captureScrolls(prefix: String) {
        var reachedActions = false
        for (page in 1..12) {
            val visible = nodes().filter { it.isVisibleToUser }
            if (visible.any { it.text?.contains("全部设备退出") == true }) {
                reachedActions = true
                break
            }
            val scroller = visible.firstOrNull { it.isScrollable } ?: break
            if (!scroller.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) break
            instrumentation.waitForIdleSync()
            SystemClock.sleep(350L)
            capture("$prefix-scroll-$page")
        }
        assertTrue("Session list reaches its final actions: $prefix", reachedActions)
        if (prefix.endsWith("busy")) {
            assertTrue(
                "Busy bulk actions are disabled",
                nodes().none {
                    it.isVisibleToUser &&
                        it.isEnabled &&
                        it.actionList.any { action -> action.label == "全部设备退出" }
                },
            )
        }
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val found = mutableListOf<AccessibilityNodeInfo>()
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        instrumentation.uiAutomation.rootInActiveWindow?.let(pending::addLast)
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            found += node
            repeat(node.childCount) { node.getChild(it)?.let(pending::addLast) }
        }
        return found
    }

    private fun capture(name: String) {
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        File(output, "$name.txt").writeText(
            nodes().joinToString("\n") { node ->
                val bounds = Rect().also(node::getBoundsInScreen)
                "${node.text} | ${node.contentDescription} | visible=${node.isVisibleToUser} enabled=${node.isEnabled} $bounds"
            },
        )
    }
}
