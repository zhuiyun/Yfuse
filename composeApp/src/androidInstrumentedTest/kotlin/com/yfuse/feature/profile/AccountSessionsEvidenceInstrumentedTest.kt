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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yfuse.core.account.AccountDeviceSession
import com.yfuse.core.designsystem.AccessibilityOptions
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.StatusBarIconStyle
import com.yfuse.core.designsystem.YfuseTheme
import com.yfuse.launchMainActivityForInput
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Actual account-session composables with isolated data and inert callbacks; no account service. */
@RunWith(AndroidJUnit4::class)
class AccountSessionsEvidenceInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val backCalls = AtomicInteger()
    private val output by lazy {
        File(instrumentation.targetContext.getExternalFilesDir(null), "account-session-evidence").apply { mkdirs() }
    }

    private data class Scene(
        val dark: Boolean,
        val scale: Float,
        val name: String,
    ) {
        val captureName: String get() = "${if (dark) "dark" else "light"}-$scale-$name"
    }

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
                            StatusBarIconStyle(darkIcons = !value.dark)
                            Box(
                                Modifier.fillMaxSize().background(LocalPalette.current.background).semantics {
                                    contentDescription = value.captureName
                                },
                            ) {
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
                                        onBack = { backCalls.incrementAndGet() },
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
                        val prefix = scene.value.captureName
                        awaitScene(prefix, name)
                        assertTrue(
                            "Session header is visible",
                            nodes().any {
                                it.isVisibleToUser &&
                                    it.text?.contains("登录与会话") == true
                            },
                        )
                        capture("$prefix-top")
                        if (name == "loading") {
                            val refresh =
                                requireNotNull(
                                    nodes().firstOrNull {
                                        it.contentDescription?.toString() ==
                                            "刷新登录设备"
                                    },
                                )
                            assertFalse(
                                "Refresh disabled during load",
                                effectivelyEnabled(refresh),
                            )
                        } else {
                            captureScrolls(prefix)
                        }
                        assertBackInvocable()
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
            assertTrue(
                "Header and Back remain visible while scrolling",
                nodes().any { it.isVisibleToUser && it.text?.contains("登录与会话") == true },
            )
        }
        assertTrue("Session list reaches its final actions: $prefix", reachedActions)
        if (prefix.endsWith("busy") || prefix.endsWith("empty")) {
            val label = if (prefix.endsWith("busy")) "全部设备退出" else "退出其他设备"
            val action = requireNotNull(nodes().firstOrNull { it.isVisibleToUser && it.text?.toString() == label })
            assertFalse("Unavailable bulk action is disabled", effectivelyEnabled(action))
        }
    }

    private fun effectivelyEnabled(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (!current.isEnabled) return false
            current = current.parent
        }
        return true
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val found = mutableListOf<AccessibilityNodeInfo>()
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        instrumentation.uiAutomation.rootInActiveWindow?.let(pending::addLast)
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            if (!node.refresh()) continue
            found += node
            repeat(node.childCount) { node.getChild(it)?.let(pending::addLast) }
        }
        return found
    }

    private fun awaitScene(
        prefix: String,
        name: String,
    ) {
        val marker =
            when (name) {
                "empty" -> "1 个会话保持登录"
                "error" -> "加载失败"
                "loading" -> "正在刷新"
                else -> "9 个会话保持登录"
            }
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            val visible = nodes().filter { it.isVisibleToUser }
            if (visible.any { it.contentDescription == prefix } &&
                visible.any { it.text?.contains(marker) == true } &&
                visible.any { it.text?.contains("登录与会话") == true }
            ) {
                return
            }
            SystemClock.sleep(100L)
        }
        capture("$prefix-readiness-failure")
        error("Expected session state was not visible: $prefix")
    }

    private fun assertBackInvocable() {
        var node = requireNotNull(nodes().firstOrNull { it.isVisibleToUser && it.contentDescription == "返回" })
        while (!node.isClickable) node = requireNotNull(node.parent)
        val before = backCalls.get()
        assertTrue(
            "Visible Back action is enabled",
            node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK),
        )
        val deadline = SystemClock.elapsedRealtime() + 2_000L
        while (backCalls.get() == before && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50L)
        assertTrue("Back callback is invoked once", backCalls.get() == before + 1)
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
