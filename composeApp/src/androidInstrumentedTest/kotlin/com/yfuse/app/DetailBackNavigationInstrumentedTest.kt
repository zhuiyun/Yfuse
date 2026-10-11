package com.yfuse.app

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.BackEventCompat
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yfuse.MainActivity
import com.yfuse.core.designsystem.ItemAction
import com.yfuse.core.designsystem.LiftMenu
import com.yfuse.core.designsystem.LiftMenuHost
import com.yfuse.core.designsystem.LiftMenuState
import com.yfuse.core.designsystem.LocalLiftMenu
import com.yfuse.core.designsystem.LocalRouteVisible
import com.yfuse.core.designsystem.LocalSharedMediaTransitionController
import com.yfuse.core.designsystem.LocalZoomBackSources
import com.yfuse.core.designsystem.MediaSharedElementKey
import com.yfuse.core.designsystem.OfficialNavDisplay
import com.yfuse.core.designsystem.OfficialNavMotion
import com.yfuse.core.designsystem.PlatformBackHandler
import com.yfuse.core.designsystem.YfuseTheme
import com.yfuse.core.designsystem.sharedMediaArtwork
import com.yfuse.core.designsystem.sharedMediaOnClick
import com.yfuse.launchMainActivityForInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class DetailBackNavigationInstrumentedTest {
    @Test(timeout = 60_000L)
    fun activity_back_returns_through_nested_details_without_finishing_the_app() {
        val probe = NavigationProbe()
        launchMainActivityForInput().use { scenario ->
            installNavigation(scenario, probe)
            awaitComposedRoute("detail-b", probe)
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            awaitComposedRoute("detail-a", probe)
            assertStack(scenario, probe, listOf("library", "detail-a"), pops = 1)
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            awaitComposedRoute("library", probe)
            assertStack(scenario, probe, listOf("library"), pops = 2)
        }
    }

    @Test(timeout = 60_000L)
    fun system_edge_swipes_cancel_and_pop_nested_details_without_exiting() {
        // Calling onBackPressedDispatcher directly cannot prove that the callback is registered
        // with WindowManager. This goes through System UI's actual edge gesture recognizer.
        withGestureNavigation {
            val probe = NavigationProbe(initialRoutes = listOf("library"), posterPath = true)
            launchMainActivityForInput().use { scenario ->
                installNavigation(scenario, probe)
                awaitComposedRoute("library", probe)
                tapPoster(probe)
                awaitComposedRoute("detail-a", probe)
                awaitCondition("The first poster's forward morph did not settle") { !probe.transitionActive.get() }
                tapPoster(probe)
                awaitComposedRoute("detail-b", probe)
                awaitCondition("The nested poster's forward morph did not settle") { !probe.transitionActive.get() }
                injectEdgeSwipe(scenario, fromRight = false, cancel = true)
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                awaitCondition("The cancelled poster return did not settle") { !probe.previewActive.get() }
                assertStack(scenario, probe, listOf("library", "detail-a", "detail-b"), pops = 0)
                assertEquals("A cancelled system gesture hid the detail", "detail-b", probe.composedTop.get())
                assertTrue("The system swipe never reached the poster return host", probe.previewSeen.get())

                probe.previewSeen.set(false)
                injectEdgeSwipe(scenario, fromRight = false, cancel = false)
                awaitComposedRoute("detail-a", probe)
                assertStack(scenario, probe, listOf("library", "detail-a"), pops = 1)
                assertTrue("The committed left edge swipe bypassed the poster return host", probe.previewSeen.get())
                probe.previewSeen.set(false)
                injectEdgeSwipe(scenario, fromRight = true, cancel = false)
                awaitComposedRoute("library", probe)
                assertStack(scenario, probe, listOf("library"), pops = 2)
                assertTrue("The committed right edge swipe bypassed the poster return host", probe.previewSeen.get())
            }
        }
    }

    @Test(timeout = 60_000L)
    fun predictive_lifecycle_preserves_cancelled_routes_and_menu_and_root_priority() {
        val probe = NavigationProbe()
        launchMainActivityForInput().use { scenario ->
            installNavigation(scenario, probe)
            awaitComposedRoute("detail-b", probe)
            predictiveGesture(scenario, cancel = true)
            assertStack(scenario, probe, listOf("library", "detail-a", "detail-b"), pops = 0)
            assertEquals("detail-b", probe.composedTop.get())

            scenario.onActivity {
                probe.menu.lift(
                    menu = LiftMenu("详情菜单", sections = listOf(listOf(ItemAction("收藏", onSelect = {})))),
                    source = Rect(100f, 100f, 200f, 250f),
                    finger = null,
                    onOpen = null,
                    onSettled = {},
                )
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            predictiveGesture(scenario, cancel = false)
            awaitCondition("The detail's menu did not consume the predictive back") {
                var closed = false
                scenario.onActivity { closed = !probe.menu.isOpen }
                closed
            }
            assertStack(scenario, probe, listOf("library", "detail-a", "detail-b"), pops = 0)

            predictiveGesture(scenario, cancel = false)
            awaitComposedRoute("detail-a", probe)
            assertStack(scenario, probe, listOf("library", "detail-a"), pops = 1)
            predictiveGesture(scenario, cancel = false)
            awaitComposedRoute("library", probe)
            assertStack(scenario, probe, listOf("library"), pops = 2)

            predictiveGesture(scenario, cancel = true)
            assertStack(scenario, probe, listOf("library"), pops = 2)
            predictiveGesture(scenario, cancel = false)
            assertStack(scenario, probe, listOf("library"), pops = 2, rootBacks = 1)
        }
    }

    private fun installNavigation(
        scenario: ActivityScenario<MainActivity>,
        probe: NavigationProbe,
    ) {
        // The launch helper finishes MainActivity's deferred session restoration before this
        // setContent, and waits for window focus before any injected touch can reach System UI.
        scenario.onActivity { activity ->
            activity.setContent {
                YfuseTheme(dark = false) {
                    CompositionLocalProvider(LocalLiftMenu provides probe.menu) {
                        Box(Modifier.fillMaxSize()) {
                            PlatformBackHandler(enabled = probe.routes.size == 1) { probe.rootBacks.incrementAndGet() }
                            OfficialNavDisplay(
                                backStack = listOf("tab"),
                                onBack = { probe.rootPops.incrementAndGet() },
                                contentKey = { it },
                                motion = OfficialNavMotion.RootTab,
                            ) {
                                OfficialNavDisplay(
                                    backStack = probe.routes.toList(),
                                    contentKey = { it },
                                    onBack = {
                                        probe.pops.incrementAndGet()
                                        if (probe.routes.size > 1) probe.routes.removeAt(probe.routes.lastIndex)
                                    },
                                ) { route ->
                                    // Observe the actual entry, where visibility is read during
                                    // composition; an outer SideEffect alone misses child pops.
                                    val visible = LocalRouteVisible.current
                                    val transitionActive =
                                        LocalSharedMediaTransitionController.current?.activeKey != null
                                    val previewActive = LocalZoomBackSources.current?.wanted != null
                                    SideEffect {
                                        if (visible) {
                                            probe.composedTop.set(route)
                                            probe.transitionActive.set(transitionActive)
                                            probe.previewActive.set(previewActive)
                                            if (previewActive) probe.previewSeen.set(true)
                                        }
                                    }
                                    if (probe.posterPath) {
                                        PosterRoute(
                                            route,
                                            probe,
                                            visible,
                                        )
                                    } else {
                                        Box(Modifier.fillMaxSize())
                                    }
                                }
                            }
                            LiftMenuHost(probe.menu)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun PosterRoute(
        route: String,
        probe: NavigationProbe,
        visible: Boolean,
    ) {
        val heroKey = route.takeUnless { it == "library" }?.let { MediaSharedElementKey("test", it) }
        val next =
            when (route) {
                "library" -> "detail-a"
                "detail-a" -> "detail-b"
                else -> null
            }
        val posterKey = next?.let { MediaSharedElementKey("test", it) }
        val open = sharedMediaOnClick(posterKey) { if (next != null) probe.routes.add(next) }
        Box(Modifier.fillMaxSize().sharedMediaArtwork(heroKey).background(Color(0xFF334455))) {
            if (posterKey != null) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(140.dp, 200.dp)
                        .sharedMediaArtwork(posterKey)
                        .background(Color(0xFFCC8866))
                        .onGloballyPositioned { if (visible) probe.posterBounds.set(it.boundsInWindow()) }
                        .clickable(onClick = open),
                )
            }
        }
    }

    private fun tapPoster(probe: NavigationProbe) {
        val poster = probe.posterBounds.get()
        assertTrue("The source poster did not lay out", poster.width > 0f && poster.height > 0f)
        val down = SystemClock.uptimeMillis()
        injectPointer(MotionEvent.ACTION_DOWN, poster.center.x, poster.center.y, down)
        SystemClock.sleep(40L)
        injectPointer(MotionEvent.ACTION_UP, poster.center.x, poster.center.y, down)
    }

    private fun predictiveGesture(
        scenario: ActivityScenario<MainActivity>,
        cancel: Boolean,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        scenario.onActivity {
            it.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(1f, 300f, 0f, BackEventCompat.EDGE_LEFT))
        }
        instrumentation.waitForIdleSync()
        scenario.onActivity {
            it.onBackPressedDispatcher.dispatchOnBackProgressed(
                BackEventCompat(160f, 300f, 0.4f, BackEventCompat.EDGE_LEFT),
            )
        }
        instrumentation.waitForIdleSync()
        scenario.onActivity {
            if (cancel) {
                it.onBackPressedDispatcher.dispatchOnBackCancelled()
            } else {
                it.onBackPressedDispatcher
                    .onBackPressed()
            }
        }
        instrumentation.waitForIdleSync()
    }

    private fun injectEdgeSwipe(
        scenario: ActivityScenario<MainActivity>,
        fromRight: Boolean,
        cancel: Boolean,
    ) {
        var width = 0
        var height = 0
        scenario.onActivity {
            assertTrue("The system gesture needs the app's focused window", it.hasWindowFocus())
            width = it.window.decorView.width
            height = it.window.decorView.height
        }
        assertTrue("The app window has not been laid out", width > 0 && height > 0)
        val start = if (fromRight) width - 2f else 1f
        val distance = width * if (cancel) 0.32f else 0.72f
        val end = start + if (fromRight) -distance else distance
        val y = height * 0.5f
        val down = SystemClock.uptimeMillis()
        injectPointer(MotionEvent.ACTION_DOWN, start, y, down)
        for (step in 1..16) {
            SystemClock.sleep(20L)
            injectPointer(MotionEvent.ACTION_MOVE, start + (end - start) * step / 16f, y, down)
        }
        injectPointer(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, end, y, down)
    }

    private fun injectPointer(
        action: Int,
        x: Float,
        y: Float,
        down: Long,
    ) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            assertTrue(
                "System input injection rejected action $action",
                InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(event, true),
            )
        } finally {
            event.recycle()
        }
    }

    private fun withGestureNavigation(block: () -> Unit) {
        val originalMode = shell("settings get secure navigation_mode").trim()
        val originalOverlay =
            Regex("(?m)^\\[x] (com\\.android\\.internal\\.systemui\\.navbar\\.[\\w.]+)\\s*$")
                .find(shell("cmd overlay list"))
                ?.groupValues
                ?.get(1)
        // CI's Pixel 2 image can default to three-button navigation. Merely injecting an edge
        // swipe there would pass a cancellation check while testing no platform back at all.
        assertTrue(
            "Cannot restore the image's navigation overlay (mode=$originalMode)",
            originalMode == "2" || originalOverlay != null,
        )
        try {
            if (originalMode !=
                "2"
            ) {
                shell("cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.gestural")
            }
            awaitCondition("System gesture navigation could not be enabled: ${shell("cmd overlay list")}") {
                shell("settings get secure navigation_mode").trim() == "2"
            }
            block()
        } finally {
            if (originalMode != "2" && originalOverlay != null) {
                shell("cmd overlay enable-exclusive --category $originalOverlay")
                awaitCondition("The original system navigation mode was not restored") {
                    shell("settings get secure navigation_mode").trim() == originalMode
                }
            }
        }
    }

    private fun assertStack(
        scenario: ActivityScenario<MainActivity>,
        probe: NavigationProbe,
        expected: List<String>,
        pops: Int,
        rootBacks: Int = 0,
    ) {
        awaitCondition("Back left the app's foreground window") {
            var focused = false
            scenario.onActivity { focused = it.hasWindowFocus() }
            val topPackage =
                InstrumentationRegistry
                    .getInstrumentation()
                    .uiAutomation.rootInActiveWindow
                    ?.packageName
                    ?.toString()
            focused &&
                scenario.state == Lifecycle.State.RESUMED &&
                topPackage == InstrumentationRegistry.getInstrumentation().targetContext.packageName
        }
        scenario.onActivity {
            assertFalse("Back finished the Activity instead of handling its visible stack", it.isFinishing)
            assertEquals(expected, probe.routes.toList())
            assertEquals("A back gesture must pop exactly once", pops, probe.pops.get())
            assertEquals("A detail back reached the root NavDisplay", 0, probe.rootPops.get())
            assertEquals("Back reached the root consumer at the wrong depth", rootBacks, probe.rootBacks.get())
        }
    }

    private fun awaitComposedRoute(
        expected: String,
        probe: NavigationProbe,
    ) {
        awaitCondition("Navigation stack did not compose $expected (observed ${probe.composedTop.get()})") {
            probe.composedTop.get() == expected
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun awaitCondition(
        message: String,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.uptimeMillis() + 5_000L
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(20L)
        if (!condition()) {
            val window =
                shell("dumpsys window windows")
                    .lineSequence()
                    .filter { "mCurrentFocus=" in it || "mFocusedApp=" in it || "mOnBackInvokedCallbackInfo=" in it }
                    .joinToString("; ")
            assertTrue("$message; system window/back callbacks: $window", false)
        }
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor
            .AutoCloseInputStream(
                InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
            ).bufferedReader()
            .use { it.readText() }

    private class NavigationProbe(
        initialRoutes: List<String> = listOf("library", "detail-a", "detail-b"),
        val posterPath: Boolean = false,
    ) {
        val routes = mutableStateListOf<String>().apply { addAll(initialRoutes) }
        val pops = AtomicInteger()
        val rootPops = AtomicInteger()
        val rootBacks = AtomicInteger()
        val composedTop = AtomicReference<String>()
        val posterBounds = AtomicReference(Rect.Zero)
        val transitionActive = AtomicBoolean()
        val previewActive = AtomicBoolean()
        val previewSeen = AtomicBoolean()
        val menu = LiftMenuState()
    }
}
