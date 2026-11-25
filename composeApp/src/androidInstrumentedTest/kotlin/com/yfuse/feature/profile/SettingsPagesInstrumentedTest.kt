package com.yfuse.feature.profile

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.russhwolf.settings.SharedPreferencesSettings
import com.yfuse.MainActivity
import com.yfuse.core.data.ThemePreferences
import com.yfuse.core.designsystem.AccessibilityOptions
import com.yfuse.core.designsystem.CaptionedPoster
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.LocalPosterCaptionsVisible
import com.yfuse.core.designsystem.LocalSettingsLocator
import com.yfuse.core.designsystem.MotionTheme
import com.yfuse.core.designsystem.SettingsLocatorRequest
import com.yfuse.core.designsystem.YfuseTheme
import com.yfuse.feature.library.GridDensityState
import com.yfuse.feature.library.GridState
import com.yfuse.feature.library.LibraryGridColumnsPreferences
import com.yfuse.feature.library.LibraryGridOptionsDialog
import com.yfuse.launchMainActivityForInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

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
                await("Back remains visible after locating ${anchor.label}") { clickableNode("返回") != null }
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
            click("返回")
            await("Return from a scrolled settings child") { page.value == ProfilePage.Appearance }
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

    @Test(timeout = 60_000L)
    fun homePosterSwitchRestoresCaptionsAndLeavesSpaceBelowHiddenTitles() {
        withSettings { scenario, prefs, page, locator ->
            scenario.onActivity {
                page.value = ProfilePage.AppearanceLayout
                locator.value = SettingsLocatorRequest("首页与导航", 1, "显示首页海报名")
            }
            await("Home captions default to hidden") { clickableNode("显示首页海报名")?.isChecked == false }
            click("显示首页海报名")
            await("Home caption preference saved") { ThemePreferences(storage()).homePosterNames.value }

            val tileHeight = AtomicInteger()
            val artworkHeight = AtomicInteger()
            val gap = AtomicInteger()
            scenario.onActivity { activity ->
                activity.setContent {
                    YfuseTheme(dark = true) {
                        val shown by prefs.homePosterNames.collectAsState()
                        gap.set(with(LocalDensity.current) { 8.dp.roundToPx() })
                        CompositionLocalProvider(LocalPosterCaptionsVisible provides shown) {
                            Box(Modifier.fillMaxSize()) {
                                CaptionedPoster(
                                    url = null,
                                    title = "海报示例",
                                    year = "2026",
                                    modifier = Modifier.width(112.dp).onSizeChanged { tileHeight.set(it.height) },
                                    posterModifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .height(
                                                150.dp,
                                            ).onSizeChanged { artworkHeight.set(it.height) },
                                )
                            }
                        }
                    }
                }
            }
            await("Caption includes the year") { visibleNode("2026") != null }
            val expanded = tileHeight.get()
            scenario.onActivity { prefs.setHomePosterNames(false) }
            await("Hidden caption keeps its bottom margin") {
                artworkHeight.get() > 0 &&
                    tileHeight.get() == artworkHeight.get() + gap.get() &&
                    tileHeight.get() < expanded &&
                    visibleNode("2026") == null
            }
            scenario.onActivity { prefs.setHomePosterNames(true) }
            await("Caption restored at the original height") {
                tileHeight.get() == expanded &&
                    visibleNode("2026") != null
            }
        }
    }

    @Test(timeout = 60_000L)
    fun poster_name_switch_saves_the_chosen_value_when_options_reopen() {
        val settings = storage()
        settings.clear()
        val optionsInstance = mutableIntStateOf(0)
        val library = "test-library"
        try {
            launchMainActivityForInput().use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        YfuseTheme(dark = true, accessibility = AccessibilityOptions(reduceMotion = true)) {
                            key(optionsInstance.intValue) {
                                val preferences = remember { LibraryGridColumnsPreferences(settings) }
                                var shown by remember { mutableStateOf(preferences.showPosterNames(library) ?: true) }
                                val gridState = rememberLazyGridState()
                                val scope = rememberCoroutineScope()
                                val density = remember { GridDensityState(gridState, scope, initialColumns = 3) }
                                LibraryGridOptionsDialog(
                                    state = GridState(sortable = false, resolutionFilterable = false),
                                    gridDensity = density,
                                    showPosterNames = shown,
                                    onShowPosterNamesChange = { show ->
                                        shown = show
                                        preferences.setShowPosterNames(library, show)
                                    },
                                    onIntent = {},
                                    onDismiss = {},
                                )
                            }
                        }
                    }
                }
                await("Poster names initially enabled") { clickableNode("显示海报名")?.isChecked == true }
                click("显示海报名")
                await("Poster names disabled") { clickableNode("显示海报名")?.isChecked == false }
                assertEquals(false, LibraryGridColumnsPreferences(settings).showPosterNames(library))
                scenario.onActivity { optionsInstance.intValue++ }
                await("Reopened options show the saved value") { clickableNode("显示海报名")?.isChecked == false }
                click("显示海报名")
                assertEquals(true, LibraryGridColumnsPreferences(settings).showPosterNames(library))
            }
        } finally {
            settings.clear()
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
            launchMainActivityForInput().use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        YfuseTheme(dark = true, accessibility = AccessibilityOptions(reduceMotion = true)) {
                            CompositionLocalProvider(LocalSettingsLocator provides locator.value) {
                                Box(Modifier.fillMaxSize().background(LocalPalette.current.background)) {
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
                }
                block(scenario, prefs, page, locator)
            }
        } finally {
            settings.clear()
        }
    }

    private fun click(label: String) {
        // Scrolling can replace an accessibility node between two lookups. Act on the same
        // enabled snapshot; a stale node rejects the action and is looked up on the next poll.
        await("Click $label") {
            val node = clickableNode(label)
            node?.isEnabled == true && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
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
            if (node.text?.lines()?.contains(label) == true ||
                node.contentDescription?.lines()?.contains(label) == true
            ) {
                // Scrolling invalidates cached child bounds independently of the parent. Query the
                // provider before judging visibility; preserve changed coordinates as evidence.
                val before = android.graphics.Rect().also(node::getBoundsInScreen)
                val wasVisible = node.isVisibleToUser
                if (node.refresh()) {
                    val after = android.graphics.Rect().also(node::getBoundsInScreen)
                    if (before != after || wasVisible != node.isVisibleToUser) {
                        recordRefreshedBounds(label, before, after, wasVisible, node.isVisibleToUser)
                    }
                    if (node.isVisibleToUser) return node
                }
            }
            repeat(node.childCount) { node.getChild(it)?.let(pending::addLast) }
        }
        return null
    }

    private fun recordRefreshedBounds(
        label: String,
        before: android.graphics.Rect,
        after: android.graphics.Rect,
        wasVisible: Boolean,
        visible: Boolean,
    ) {
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "settings-evidence")
        directory.mkdirs()
        File(directory, "refreshed-bounds.txt").appendText(
            "$label | cached=$before visible=$wasVisible | refreshed=$after visible=$visible\n",
        )
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
        if (!condition()) {
            val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "settings-evidence")
            directory.mkdirs()
            val name = "failure-${label.hashCode()}"
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            val rows = mutableListOf<String>()
            val pending = ArrayDeque<AccessibilityNodeInfo>()
            instrumentation.uiAutomation.rootInActiveWindow?.let(pending::addLast)
            while (pending.isNotEmpty()) {
                val node = pending.removeFirst()
                val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
                rows += "${node.text} | ${node.contentDescription} | visible=${node.isVisibleToUser} | $bounds"
                repeat(node.childCount) { node.getChild(it)?.let(pending::addLast) }
            }
            File(directory, "$name.txt").writeText("$label\n" + rows.joinToString("\n"))
            assertTrue(label, false)
        }
    }
}
