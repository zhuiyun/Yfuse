package com.yfuse.feature.profile

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.russhwolf.settings.SharedPreferencesSettings
import com.yfuse.MainActivity
import com.yfuse.core.data.ThemePreferences
import com.yfuse.core.designsystem.AccessibilityOptions
import com.yfuse.core.designsystem.LocalSettingsLocator
import com.yfuse.core.designsystem.MotionTheme
import com.yfuse.core.designsystem.SettingsLocatorRequest
import com.yfuse.core.designsystem.YfuseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real Compose rows and accessibility clicks; isolated storage, no user account or external service. */
@RunWith(AndroidJUnit4::class)
class SettingsPagesInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val appearancePages =
        setOf(
            ProfilePage.Appearance,
            ProfilePage.AppearanceLayout,
            ProfilePage.AppearanceMotion,
            ProfilePage.AppearanceAccessibility,
        )

    @Test(timeout = 180_000L)
    fun every_reorganized_appearance_search_target_is_reachable_and_page_links_open_the_right_page() {
        withSettings { scenario, _, page, locator ->
            val targets = settingsControlDestinations().filter { it.page in appearancePages && it.anchor != null }
            assertTrue(targets.size >= 20)
            val captured = mutableSetOf<ProfilePage>()
            for (target in targets) {
                val anchor = requireNotNull(target.anchor)
                scenario.onActivity {
                    page.value = requireNotNull(target.page)
                    locator.value = SettingsLocatorRequest(anchor.pageTitle, anchor.itemIndex, anchor.label)
                }
                await("Search target ${anchor.label}") { visibleNode(anchor.label) != null }
                if (captured.add(requireNotNull(target.page))) {
                    val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "settings-evidence")
                    directory.mkdirs()
                    instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                        File(directory, "${target.page}.png").outputStream().use {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                        bitmap.recycle()
                    }
                }
            }
            for ((label, destination) in listOf(
                "首页与导航" to ProfilePage.AppearanceLayout,
                "动画与动效" to ProfilePage.AppearanceMotion,
                "辅助功能" to ProfilePage.AppearanceAccessibility,
            )) {
                scenario.onActivity {
                    page.value = ProfilePage.Appearance
                    locator.value = SettingsLocatorRequest("外观与交互", 3, label)
                }
                click(label)
                await("Navigation to $destination") { page.value == destination }
            }
        }
    }

    @Test(timeout = 120_000L)
    fun switches_save_the_value_shown_and_theme_overrides_cannot_accept_ineffective_taps() {
        withSettings { scenario, prefs, page, locator ->
            scenario.onActivity {
                page.value = ProfilePage.AppearanceAccessibility
                locator.value = SettingsLocatorRequest("辅助功能", 1, "大号文字")
            }
            click("大号文字")
            await("Large text on") { prefs.largeText.value }
            click("减少透明效果")
            await("Reduced transparency on") { prefs.reduceTransparency.value }
            scenario.onActivity { locator.value = SettingsLocatorRequest("辅助功能", 2, "减少动画") }
            click("减少动画")
            await("Reduced motion on") { prefs.reduceMotion.value }
            val restored = ThemePreferences(storage())
            assertTrue(restored.largeText.value)
            assertTrue(restored.reduceTransparency.value)
            assertTrue(restored.reduceMotion.value)
            scenario.onActivity {
                page.value = ProfilePage.AppearanceMotion
                locator.value = SettingsLocatorRequest("动画与动效", 2, "搜索与导航动效")
            }
            await("Disabled motion control") { visibleNode("搜索与导航动效") != null }
            val pulseBefore = prefs.pulseSweep.value
            assertFalse(clickableNode("搜索与导航动效")?.isEnabled == true)
            scenario.onActivity {
                prefs.setReduceMotion(false)
                prefs.setMotionTheme(MotionTheme.Calm)
            }
            await("Calm override explanation") { visibleNode("跟随静息主题") != null }
            assertFalse(clickableNode("搜索与导航动效")?.isEnabled == true)
            assertEquals(pulseBefore, prefs.pulseSweep.value)
        }
    }

    private fun storage() =
        SharedPreferencesSettings(
            instrumentation.targetContext.getSharedPreferences("settings-pages-test", Context.MODE_PRIVATE),
        )

    private fun withSettings(
        block: (
            ActivityScenario<MainActivity>,
            ThemePreferences,
            androidx.compose.runtime.MutableState<ProfilePage>,
            androidx.compose.runtime.MutableState<SettingsLocatorRequest?>,
        ) -> Unit,
    ) {
        val settings = storage()
        settings.clear()
        val prefs = ThemePreferences(settings)
        val page = mutableStateOf(ProfilePage.Appearance)
        val locator = mutableStateOf<SettingsLocatorRequest?>(null)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        YfuseTheme(dark = true, accessibility = AccessibilityOptions(reduceMotion = true)) {
                            CompositionLocalProvider(LocalSettingsLocator provides locator.value) {
                                AppearanceSettingsScreen(
                                    page.value,
                                    prefs,
                                    "测试图标",
                                    onBack = { page.value = ProfilePage.Appearance },
                                    onOpenPage = { page.value = it },
                                    onAction = {},
                                )
                            }
                        }
                    }
                }
                block(scenario, prefs, page, locator)
            }
        } finally {
            settings.clear()
        }
    }

    private fun click(label: String) {
        await("Clickable $label") { clickableNode(label)?.isEnabled == true }
        assertTrue(requireNotNull(clickableNode(label)).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }

    private fun clickableNode(label: String): AccessibilityNodeInfo? {
        var node = visibleNode(label) ?: return null
        while (!node.isClickable) node = node.parent ?: return null
        return node
    }

    private fun visibleNode(label: String): AccessibilityNodeInfo? {
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        instrumentation.uiAutomation.rootInActiveWindow?.let(pending::addLast)
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            if (node.isVisibleToUser &&
                (
                    node.text?.lines()?.contains(label) == true ||
                        node.contentDescription?.lines()?.contains(label) == true
                )
            ) {
                return node
            }
            repeat(node.childCount) { node.getChild(it)?.let(pending::addLast) }
        }
        return null
    }

    private fun await(
        label: String,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(50L)
        }
        assertTrue(label, condition())
    }
}
