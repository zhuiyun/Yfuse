package com.yfuse.app

import android.content.Intent
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.BackEventCompat
import androidx.activity.compose.setContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yfuse.MainActivity
import com.yfuse.app.RootComponent.Tab
import com.yfuse.awaitWindowFocus
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.ItemAction
import com.yfuse.core.designsystem.LiftExit
import com.yfuse.core.designsystem.LiftMenu
import com.yfuse.core.designsystem.LiftMenuHost
import com.yfuse.core.designsystem.LiftMenuState
import com.yfuse.core.designsystem.LocalLiftMenu
import com.yfuse.core.designsystem.LocalRouteVisibilityState
import com.yfuse.core.designsystem.LocalRouteVisible
import com.yfuse.core.designsystem.LocalSharedMediaTransitionController
import com.yfuse.core.designsystem.LocalSharedTransitionScope
import com.yfuse.core.designsystem.LocalZoomBackSources
import com.yfuse.core.designsystem.MediaSharedElementKey
import com.yfuse.core.designsystem.MotionTheme
import com.yfuse.core.designsystem.OfficialNavDisplay
import com.yfuse.core.designsystem.OfficialNavMotion
import com.yfuse.core.designsystem.PlatformBackHandler
import com.yfuse.core.designsystem.YfuseTheme
import com.yfuse.core.designsystem.sharedMediaArtwork
import com.yfuse.core.designsystem.sharedMediaOnClick
import com.yfuse.feature.library.LibraryComponent
import com.yfuse.launchMainActivityForInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class DetailBackNavigationInstrumentedTest {
    @Test(timeout = 60_000L)
    fun first_poster_back_previews_after_splash_visibility_changes() {
        withGestureNavigation {
            val probe =
                NavigationProbe(
                    initialRoutes = listOf("library"),
                    posterPath = true,
                    visual = true,
                    initiallyVisible = false,
                )
            launchMainActivityForInput().use { scenario ->
                installNavigation(scenario, probe)
                awaitCondition("The library did not compose behind the splash") { probe.hostComposed.get() }
                scenario.onActivity { probe.parentVisible.value = true }
                awaitComposedRoute("library", probe)
                tapPoster(probe)
                awaitComposedRoute("detail-a", probe)
                awaitCondition("The first poster's entry did not settle") { !probe.transitionActive.get() }
                // This must be the first system gesture, without warming another page's handler.
                injectEdgeSwipe(scenario, fromRight = false, cancel = true, duringGesture = {
                    val frame = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                    try {
                        val row = IntArray(frame.width) { frame.getPixel(it, frame.height * 4 / 5) }
                        assertTrue(
                            "The first poster return did not preview the library after the splash",
                            row.count { it == PREVIEW_UNDERLAY } > frame.width / 20,
                        )
                        assertTrue(
                            "The first detail faded during back",
                            row.count { it == PREVIEW_DETAIL } > frame.width / 20,
                        )
                    } finally {
                        frame.recycle()
                    }
                })
                assertStack(scenario, probe, listOf("library", "detail-a"), pops = 0)
                injectEdgeSwipe(scenario, fromRight = false, cancel = false)
                awaitComposedRoute("library", probe)
                assertStack(scenario, probe, listOf("library"), pops = 1)
            }
        }
    }

    @Test(timeout = 60_000L)
    fun system_gesture_reveals_the_destination_before_release() {
        withGestureNavigation {
            val probe =
                NavigationProbe(
                    initialRoutes = listOf("library", "plain-detail"),
                    visual = true,
                    theme = MotionTheme.Calm,
                )
            launchMainActivityForInput().use { scenario ->
                installNavigation(scenario, probe)
                awaitComposedRoute("plain-detail", probe)
                awaitCondition("Detail entry did not settle") { probe.routeSettled.get() }
                for (fromRight in listOf(false, true)) {
                    injectEdgeSwipe(scenario, fromRight = fromRight, cancel = true, duringGesture = {
                        val instrumentation = InstrumentationRegistry.getInstrumentation()
                        val frame = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                        try {
                            val file =
                                java.io.File(
                                    instrumentation.targetContext.getExternalFilesDir(null),
                                    "detail-system-back-$fromRight.png",
                                )
                            file.outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            val row = IntArray(frame.width) { frame.getPixel(it, frame.height / 2) }
                            assertTrue(
                                "System gesture never revealed the destination",
                                row.count { it == PREVIEW_UNDERLAY } > frame.width / 20,
                            )
                            assertTrue(
                                "Detail disappeared before gesture release",
                                row.count { it == PREVIEW_DETAIL } > frame.width / 20,
                            )
                            // System UI's back bubble can cover a few pixels at either edge.
                            assertTrue(
                                "Either edge must reveal the destination on the left",
                                row.take(frame.width / 2).count { it == PREVIEW_UNDERLAY } > frame.width / 20,
                            )
                            assertTrue(
                                "The outgoing detail must move to the right",
                                row.drop(frame.width / 2).none { it == PREVIEW_UNDERLAY },
                            )
                        } finally {
                            frame.recycle()
                        }
                    })
                    SystemClock.sleep(700L)
                    assertStack(scenario, probe, listOf("library", "plain-detail"), pops = 0)
                }
                injectEdgeSwipe(scenario, fromRight = false, cancel = false)
                awaitComposedRoute("library", probe)
                assertStack(scenario, probe, listOf("library"), pops = 1)
            }
        }
    }

    @Test(timeout = 90_000L)
    fun detail_preview_stays_opaque_and_respects_each_motion_theme() {
        for (theme in listOf(MotionTheme.Classic, MotionTheme.Calm, MotionTheme.Cinema)) {
            val probe = NavigationProbe(initialRoutes = listOf("library", "plain-detail"), visual = true, theme = theme)
            launchMainActivityForInput().use { scenario ->
                installNavigation(scenario, probe)
                awaitComposedRoute("plain-detail", probe)
                awaitCondition("Detail entry did not settle") { probe.routeSettled.get() }
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                for (edge in listOf(BackEventCompat.EDGE_LEFT, BackEventCompat.EDGE_RIGHT)) {
                    scenario.onActivity {
                        it.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(1f, 500f, 0f, edge))
                    }
                    instrumentation.waitForIdleSync()
                    scenario.onActivity {
                        it.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(400f, 500f, 0.4f, edge))
                    }
                    SystemClock.sleep(150L)
                    val frame = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                    try {
                        val y = frame.height / 2
                        val destinationX = (frame.width * 0.2f).toInt()
                        val detailX = frame.width - destinationX
                        assertEquals(
                            "$theme/$edge: destination must be visible during the gesture",
                            PREVIEW_UNDERLAY,
                            frame.getPixel(destinationX, y),
                        )
                        assertEquals(
                            "$theme/$edge: detail must remain fully opaque",
                            PREVIEW_DETAIL,
                            frame.getPixel(detailX, y),
                        )
                        // Classic/Cinema shrink by 4% over a complete gesture; Calm stays full size.
                        val stripeY = frame.height / 3
                        var stripeWidth = 0
                        for (x in 0 until frame.width) if (frame.getPixel(x, stripeY) == PREVIEW_STRIPE) stripeWidth++
                        val file =
                            java.io.File(
                                instrumentation.targetContext.getExternalFilesDir(null),
                                "detail-back-${theme.name}-$edge.png",
                            )
                        file.outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        val expectedWidth = probe.stripeWidth.get() * if (theme == MotionTheme.Calm) 1f else 0.984f
                        // Fractional translation/scaling can antialias the two boundary pixels.
                        assertTrue(
                            "$theme/$edge: wrong preview scale (expected $expectedWidth, got $stripeWidth)",
                            kotlin.math.abs(expectedWidth - stripeWidth) <= 2,
                        )
                    } finally {
                        frame.recycle()
                    }
                    scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackCancelled() }
                    SystemClock.sleep(700L)
                    assertStack(scenario, probe, listOf("library", "plain-detail"), pops = 0)
                }
                predictiveGesture(scenario, cancel = false)
                awaitComposedRoute("library", probe)
                assertStack(scenario, probe, listOf("library"), pops = 1)
            }
        }
    }

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
    fun ordinary_back_slides_nested_cinema_details_without_reversing_the_poster_morph() {
        val probe = NavigationProbe(initialRoutes = listOf("library"), posterPath = true, theme = MotionTheme.Cinema)
        launchMainActivityForInput().use { scenario ->
            installNavigation(scenario, probe)
            awaitComposedRoute("library", probe)
            tapPoster(probe)
            awaitComposedRoute("detail-a", probe)
            awaitCondition("The first poster did not expand into the detail") {
                probe.sharedOpeningRoute.get() ==
                    "detail-a"
            }
            awaitCondition("The first poster's forward morph did not settle") { !probe.transitionActive.get() }
            tapPoster(probe)
            awaitComposedRoute("detail-b", probe)
            awaitCondition("The nested poster did not expand into the detail") {
                probe.sharedOpeningRoute.get() ==
                    "detail-b"
            }
            awaitCondition("The nested poster's forward morph did not settle") { !probe.transitionActive.get() }
            probe.sharedReturningRoute.set(null)
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            awaitComposedRoute("detail-a", probe)
            awaitCondition("The nested side return did not settle") { probe.routeSettled.get() }
            assertEquals(null, probe.sharedReturningRoute.get())
            assertStack(scenario, probe, listOf("library", "detail-a"), pops = 1)
            probe.sharedReturningRoute.set(null)
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            awaitComposedRoute("library", probe)
            awaitCondition("The library side return did not settle") { probe.routeSettled.get() }
            assertEquals(null, probe.sharedReturningRoute.get())
            assertStack(scenario, probe, listOf("library"), pops = 2)
            assertFalse("Ordinary back showed a predictive preview", probe.previewSeen.get())
        }
    }

    @Test(timeout = 60_000L)
    fun cinema_system_back_slides_the_whole_detail_and_can_cancel_then_commit_from_either_edge() {
        val probe =
            NavigationProbe(
                initialRoutes = listOf("library"),
                posterPath = true,
                visual = true,
                theme = MotionTheme.Cinema,
            )
        launchMainActivityForInput().use { scenario ->
            installNavigation(scenario, probe)
            awaitComposedRoute("library", probe)
            tapPoster(probe)
            awaitComposedRoute("detail-a", probe)
            awaitCondition("The poster entry did not settle") { !probe.transitionActive.get() }
            for (edge in listOf(BackEventCompat.EDGE_LEFT, BackEventCompat.EDGE_RIGHT)) {
                predictiveGesture(scenario, cancel = true, edge = edge) {
                    assertOpaqueSidePreview()
                    assertFalse("Back must not seek the shared hero", probe.sharedMoving.get())
                }
                awaitCondition("The cancelled detail did not settle") {
                    !probe.sharedMoving.get() &&
                        probe.routeSettled.get()
                }
                assertStack(scenario, probe, listOf("library", "detail-a"), pops = 0)
            }
            probe.sharedReturningRoute.set(null)
            predictiveGesture(scenario, cancel = false, edge = BackEventCompat.EDGE_RIGHT) {
                assertOpaqueSidePreview()
                assertFalse("Commit must not restart the shared hero", probe.sharedMoving.get())
            }
            awaitComposedRoute("library", probe)
            assertEquals(null, probe.sharedReturningRoute.get())
            assertStack(scenario, probe, listOf("library"), pops = 1)
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
                assertFalse("A cancelled swipe showed a predictive poster preview", probe.previewSeen.get())

                probe.previewSeen.set(false)
                injectEdgeSwipe(scenario, fromRight = false, cancel = false)
                awaitComposedRoute("detail-a", probe)
                assertStack(scenario, probe, listOf("library", "detail-a"), pops = 1)
                assertFalse("The committed left edge swipe showed a predictive poster preview", probe.previewSeen.get())
                probe.previewSeen.set(false)
                injectEdgeSwipe(scenario, fromRight = true, cancel = false)
                awaitComposedRoute("library", probe)
                assertStack(scenario, probe, listOf("library"), pops = 2)
                assertFalse(
                    "The committed right edge swipe showed a predictive poster preview",
                    probe.previewSeen.get(),
                )

                // History/search can open a detail with no poster origin. That uses the host's
                // forward-to-NavDisplay branch rather than its poster-return controller.
                scenario.onActivity { probe.routes.add("plain-detail") }
                awaitComposedRoute("plain-detail", probe)
                awaitCondition("The plain detail did not finish its entry transition") { probe.routeSettled.get() }
                probe.previewSeen.set(false)
                injectEdgeSwipe(scenario, fromRight = false, cancel = false)
                awaitComposedRoute("library", probe)
                assertStack(scenario, probe, listOf("library"), pops = 3)
                assertFalse("The plain detail unexpectedly reused a poster origin", probe.previewSeen.get())

                scenario.onActivity { probe.routes.add("resumed-detail") }
                awaitComposedRoute("resumed-detail", probe)
                awaitCondition("The detail did not settle before leaving the app") { probe.routeSettled.get() }
                var originalActivity: MainActivity? = null
                var originalTask = -1
                scenario.onActivity {
                    originalActivity = it
                    originalTask = it.taskId
                }
                val activityBeforeHome = checkNotNull(originalActivity)
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                shell("input keyevent KEYCODE_HOME")
                // PAUSED and launcher focus can precede completion of Home's system transition.
                // Let the original task actually stop before asking Android to bring it back.
                awaitCondition("The real Home key did not stop the original activity behind a foreground task") {
                    var stopped = false
                    instrumentation.runOnMainSync {
                        stopped =
                            activityBeforeHome.lifecycle.currentState == Lifecycle.State.CREATED &&
                            !activityBeforeHome.hasWindowFocus()
                    }
                    val topPackage =
                        instrumentation.uiAutomation.rootInActiveWindow
                            ?.packageName
                            ?.toString()
                    stopped && topPackage != null && topPackage != instrumentation.targetContext.packageName
                }
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                context.startActivity(
                    Intent(context, MainActivity::class.java)
                        .setAction(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP,
                        ),
                )
                awaitWindowFocus(scenario)
                assertStack(scenario, probe, listOf("library", "resumed-detail"), pops = 3)
                scenario.onActivity {
                    assertSame("Returning from Home replaced the original Activity", originalActivity, it)
                    assertEquals("Returning from Home created a different task", originalTask, it.taskId)
                }
                assertEquals(
                    "Resuming the task replaced the test's visible route",
                    "resumed-detail",
                    probe.composedTop.get(),
                )
                injectEdgeSwipe(scenario, fromRight = true, cancel = false)
                awaitComposedRoute("library", probe)
                assertStack(scenario, probe, listOf("library"), pops = 4)
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
            // waitForIdleSync can return before Compose's next frame. The actual menu host
            // must have committed its BackHandler before the predictive session chooses one.
            awaitCondition("The actual detail menu host did not finish composing") { probe.menuComposed.get() }
            predictiveGesture(scenario, cancel = true)
            scenario.onActivity { assertTrue("Cancelling back dismissed the detail menu", probe.menu.isOpen) }
            assertStack(scenario, probe, listOf("library", "detail-a", "detail-b"), pops = 0)
            predictiveGesture(scenario, cancel = false)
            awaitCondition("The detail menu handler did not start dismissal") {
                var leaving = false
                scenario.onActivity { leaving = probe.menu.session?.exit != LiftExit.None }
                leaving
            }
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

    @Test(timeout = 60_000L)
    fun real_model_handles_system_back_without_a_route_ui_and_releases_the_root() {
        withGestureNavigation {
            launchMainActivityForInput().use { scenario ->
                val root = AtomicReference<RootComponent>()
                val emptyHostComposed = AtomicBoolean()
                scenario.onActivity { activity ->
                    val field = MainActivity::class.java.getDeclaredField("rootComponent").apply { isAccessible = true }
                    val model = field.get(activity) as RootComponent
                    assertTrue(
                        "The isolated validation app must have no saved media servers",
                        model.dependencies.serverRegistry.data.value.servers
                            .isEmpty(),
                    )
                    model.resetPersonalRoutes()
                    model.selectTab(model.startTab)
                    root.set(model)
                    // Deliberately remove every Compose route/menu handler. MainActivity's
                    // production model fallback is the only callback this test can rely on.
                    activity.setContent {
                        Box(Modifier.fillMaxSize())
                        SideEffect { emptyHostComposed.set(true) }
                    }
                }
                awaitCondition("The minimal host did not dispose the original route UI") { emptyHostComposed.get() }
                val model = root.get()
                scenario.onActivity { activity ->
                    assertFalse(
                        "The start tab's root must leave system back available",
                        model.backNavigationState().enabled,
                    )
                    assertFalse(
                        "A UI callback survived the minimal host",
                        activity.onBackPressedDispatcher.hasEnabledCallbacks(),
                    )
                    model.browse.openDetail(null, "gesture-model-detail-a")
                    model.browse.openDetail(null, "gesture-model-detail-b")
                    model.selectTab(Tab.Browse)
                    assertEquals(3, model.browse.stack.value.items.size)
                    assertTrue(
                        "The model did not synchronously enable its fallback",
                        model.backNavigationState().enabled,
                    )
                    assertTrue(
                        "Model navigation waited for a Compose frame before registering back",
                        activity.onBackPressedDispatcher.hasEnabledCallbacks(),
                    )
                }

                // Android keeps the callback selected at gesture start. A link or another
                // model action can replace that route before the platform sends completion.
                scenario.onActivity { activity ->
                    val dispatcher = activity.onBackPressedDispatcher
                    dispatcher.dispatchOnBackStarted(BackEventCompat(1f, 300f, 0f, BackEventCompat.EDGE_LEFT))
                    dispatcher.dispatchOnBackProgressed(BackEventCompat(160f, 300f, 0.4f, BackEventCompat.EDGE_LEFT))
                    model.browse.openDetail(null, "gesture-model-new-detail")
                    dispatcher.onBackPressed()
                    assertEquals(
                        "An old predictive gesture popped the newly opened detail",
                        4,
                        model.browse.stack.value.items.size,
                    )
                    val detail = model.browse.stack.value.active.configuration as LibraryComponent.Config.Detail
                    assertEquals("gesture-model-new-detail", detail.itemId)
                    model.browse.navigateBack()
                    assertEquals(3, model.browse.stack.value.items.size)

                    dispatcher.dispatchOnBackStarted(BackEventCompat(1f, 300f, 0f, BackEventCompat.EDGE_LEFT))
                    dispatcher.dispatchOnBackProgressed(BackEventCompat(160f, 300f, 0.4f, BackEventCompat.EDGE_LEFT))
                    dispatcher.dispatchOnBackCancelled()
                    assertEquals("Cancelling left a stale model gesture active", 3, model.browse.stack.value.items.size)
                    val returned = model.browse.stack.value.active.configuration as LibraryComponent.Config.Detail
                    assertEquals("gesture-model-detail-b", returned.itemId)
                }
                assertForeground(scenario)

                injectEdgeSwipe(scenario, fromRight = false, cancel = true)
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                assertForeground(scenario)
                scenario.onActivity {
                    assertEquals(Tab.Browse, model.activeTab.value)
                    assertEquals(
                        "Cancelling system back changed the real detail stack",
                        3,
                        model.browse.stack.value.items.size,
                    )
                }

                injectEdgeSwipe(scenario, fromRight = false, cancel = false)
                awaitCondition("Model fallback did not pop the second detail") {
                    model.browse.stack.value.items.size ==
                        2
                }
                assertForeground(scenario)
                scenario.onActivity {
                    assertEquals(Tab.Browse, model.activeTab.value)
                    val detail = model.browse.stack.value.active.configuration as LibraryComponent.Config.Detail
                    assertEquals("The fallback popped more than one detail", "gesture-model-detail-a", detail.itemId)
                }
                injectEdgeSwipe(scenario, fromRight = true, cancel = false)
                awaitCondition(
                    "Model fallback did not pop the first detail",
                ) { model.browse.stack.value.items.size == 1 }
                assertForeground(scenario)
                scenario.onActivity { assertEquals(Tab.Browse, model.activeTab.value) }

                if (model.startTab != Tab.Browse) {
                    injectEdgeSwipe(scenario, fromRight = false, cancel = false)
                    awaitCondition("Model fallback did not return to the session's start tab") {
                        model.activeTab.value ==
                            model.startTab
                    }
                    assertForeground(scenario)
                }
                scenario.onActivity {
                    assertFalse(
                        "The actual start-tab root still enabled the model fallback",
                        model.backNavigationState().enabled,
                    )
                    assertFalse(
                        "A callback at the actual root would swallow Android's exit gesture",
                        it.onBackPressedDispatcher.hasEnabledCallbacks(),
                    )
                }
                injectEdgeSwipe(scenario, fromRight = true, cancel = false)
                // The real root belongs to Android. Whether the launcher task is retained
                // or finished depends on platform policy; every detail above must stay alive.
                awaitCondition("The root did not return system back to Android") {
                    scenario.state != Lifecycle.State.RESUMED &&
                        InstrumentationRegistry
                            .getInstrumentation()
                            .uiAutomation.rootInActiveWindow
                            ?.packageName
                            ?.toString() !=
                        InstrumentationRegistry.getInstrumentation().targetContext.packageName
                }
            }
        }
    }

    @OptIn(ExperimentalSharedTransitionApi::class)
    private fun installNavigation(
        scenario: ActivityScenario<MainActivity>,
        probe: NavigationProbe,
    ) {
        // The launch helper finishes MainActivity's deferred session restoration before this
        // setContent, and waits for window focus before any injected touch can reach System UI.
        scenario.onActivity { activity ->
            activity.setContent {
                YfuseTheme(dark = false, motionTheme = probe.theme) {
                    CompositionLocalProvider(
                        LocalLiftMenu provides probe.menu,
                        LocalRouteVisible provides probe.parentVisible.value,
                        LocalRouteVisibilityState provides probe.parentVisible,
                    ) {
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
                                    isDetail = { it != "library" },
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
                                    val sharedMoving = LocalSharedTransitionScope.current?.isTransitionActive == true
                                    val previewActive = LocalZoomBackSources.current?.wanted != null
                                    val transition = LocalNavAnimatedContentScope.current.transition
                                    val routeSettled =
                                        transition.currentState == EnterExitState.Visible &&
                                            transition.targetState == EnterExitState.Visible
                                    SideEffect {
                                        if (sharedMoving &&
                                            visible &&
                                            transition.targetState == EnterExitState.Visible
                                        ) {
                                            probe.sharedOpeningRoute.set(route)
                                        }
                                        if (sharedMoving && transition.targetState == EnterExitState.PostExit) {
                                            probe.sharedReturningRoute.set(route)
                                        }
                                        if (visible) {
                                            probe.composedTop.set(route)
                                            probe.transitionActive.set(transitionActive)
                                            probe.sharedMoving.set(sharedMoving)
                                            probe.previewActive.set(previewActive)
                                            probe.routeSettled.set(routeSettled)
                                            if (previewActive) probe.previewSeen.set(true)
                                        }
                                    }
                                    if (probe.visual && !probe.posterPath) {
                                        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                                            drawRect(
                                                Color(
                                                    if (route ==
                                                        "library"
                                                    ) {
                                                        PREVIEW_UNDERLAY
                                                    } else {
                                                        PREVIEW_DETAIL
                                                    },
                                                ),
                                            )
                                            if (route != "library") {
                                                val width = 24.dp.roundToPx()
                                                probe.stripeWidth.set(width)
                                                drawRect(
                                                    Color(PREVIEW_STRIPE),
                                                    topLeft =
                                                        androidx.compose.ui.geometry.Offset(
                                                            size.width * 0.5f,
                                                            0f,
                                                        ),
                                                    size =
                                                        androidx.compose.ui.geometry.Size(
                                                            width.toFloat(),
                                                            size.height * 0.4f,
                                                        ),
                                                )
                                            }
                                        }
                                    } else if (probe.posterPath) {
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
                            val menuVisible = probe.menu.isOpen
                            SideEffect {
                                probe.menuComposed.set(menuVisible)
                                probe.hostComposed.set(true)
                            }
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
        val open =
            sharedMediaOnClick(posterKey, sourceShape = AppShapes.card) {
                if (next != null) {
                    probe.routes.add(next)
                }
            }
        val background =
            if (probe.visual) {
                Color(if (route == "library") PREVIEW_UNDERLAY else PREVIEW_DETAIL)
            } else {
                Color(0xFF334455)
            }
        Box(Modifier.fillMaxSize().sharedMediaArtwork(heroKey).background(background)) {
            if (posterKey != null) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(140.dp, 200.dp)
                        .clip(AppShapes.card)
                        .sharedMediaArtwork(posterKey, sourceShape = AppShapes.card)
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

    private fun assertOpaqueSidePreview() {
        SystemClock.sleep(100L)
        val frame = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            val row = IntArray(frame.width) { frame.getPixel(it, frame.height * 4 / 5) }
            assertTrue("Side preview must reveal the list", row.count { it == PREVIEW_UNDERLAY } > frame.width / 20)
            assertTrue("The detail must remain opaque", row.count { it == PREVIEW_DETAIL } > frame.width / 20)
        } finally {
            frame.recycle()
        }
    }

    private fun predictiveGesture(
        scenario: ActivityScenario<MainActivity>,
        cancel: Boolean,
        edge: Int = BackEventCompat.EDGE_LEFT,
        duringGesture: () -> Unit = {},
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        scenario.onActivity {
            it.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(1f, 300f, 0f, edge))
        }
        instrumentation.waitForIdleSync()
        scenario.onActivity {
            it.onBackPressedDispatcher.dispatchOnBackProgressed(
                BackEventCompat(160f, 300f, 0.4f, edge),
            )
        }
        instrumentation.waitForIdleSync()
        duringGesture()
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
        duringGesture: () -> Unit = {},
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
        // UiAutomation waits for WM animations before injecting DOWN. Sampling downTime first
        // would count that wait as a long press and make System UI reject the first MOVE.
        awaitSystemTransitionsIdle()
        val down = SystemClock.uptimeMillis()
        injectPointer(MotionEvent.ACTION_DOWN, start, y, down)
        for (step in 1..16) {
            SystemClock.sleep(20L)
            val eventTime = SystemClock.uptimeMillis()
            if (step == 1) {
                Log.i("DetailBackInput", "First MOVE eventTime=$eventTime downTime=$down deltaMs=${eventTime - down}")
            }
            injectPointer(MotionEvent.ACTION_MOVE, start + (end - start) * step / 16f, y, down, eventTime)
            if (step == 12) {
                SystemClock.sleep(80L)
                duringGesture()
            }
        }
        injectPointer(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, end, y, down)
    }

    private fun awaitSystemTransitionsIdle() {
        val started = SystemClock.uptimeMillis()
        var observed = ""
        try {
            awaitCondition("System UI's window transitions did not become idle before input") {
                observed = shell("dumpsys activity service com.android.systemui/.SystemUIService WMShell")
                shellTransitionsIdle(observed)
            }
        } catch (failure: AssertionError) {
            val section =
                observed
                    .lineSequence()
                    .dropWhile { it.trim() != "ShellTransitions" }
                    .takeWhile { it.isNotBlank() }
                    .joinToString("\n")
            throw AssertionError(
                "${failure.message}; last WMShell snapshot: ${section.ifEmpty { observed }.take(4_000)}",
                failure,
            )
        }
        Log.i("DetailBackInput", "System transition barrier elapsedUptimeMs=${SystemClock.uptimeMillis() - started}")
    }

    private fun shellTransitionsIdle(dump: String): Boolean {
        val allLines = dump.lines()
        val headers = allLines.indices.filter { allLines[it].trim() == "ShellTransitions" }
        assertEquals("The WMShell dump must contain exactly one ShellTransitions section", 1, headers.size)
        val start = headers.single()
        // ShellCommandHandler separates dump callbacks with a blank line. Neither version's
        // Transitions/RemoteTransitionHandler emits internal blank lines, so labels stay local.
        val sectionEnd = (start + 1 until allLines.size).firstOrNull { allLines[it].isBlank() } ?: allLines.size
        val lines = allLines.subList(start, sectionEnd)
        val indentation = lines.first().indexOfFirst { !it.isWhitespace() }

        fun label(name: String): Int {
            val matches = (1 until lines.size).filter { lines[it].trim() == name }
            assertEquals("Missing or repeated $name in the ShellTransitions dump", 1, matches.size)
            val index = matches.single()
            assertEquals(
                "Unexpected indentation for $name",
                indentation,
                lines[index].indexOfFirst { !it.isWhitespace() },
            )
            return index
        }
        val pending = label("Pending Transitions:")
        val ready = label("Ready-during-sync Transitions:")
        val tracks = label("Tracks:")
        assertTrue(
            "ShellTransitions dump labels are out of order",
            0 < pending && pending < ready && ready < tracks,
        )
        assertTrue("The pending/ready transition sections are empty", ready > pending + 1 && tracks > ready + 1)
        val end =
            (tracks + 1 until lines.size).firstOrNull {
                lines[it].indexOfFirst { char -> !char.isWhitespace() } <=
                    indentation
            }
                ?: lines.size
        var index = tracks + 1
        var trackCount = 0
        var tracksIdle = true
        while (index < end) {
            assertTrue(
                "Unexpected ShellTransitions track header: ${lines[index]}",
                lines[index].trim().matches(Regex("Track #[0-9]+")),
            )
            assertTrue(
                "Missing active state for ${lines[index].trim()}",
                index + 1 < end && lines[index + 1].trim().startsWith("active="),
            )
            tracksIdle = tracksIdle && lines[index + 1].trim() == "active=null"
            trackCount++
            index += 2
            // This spelling is emitted by both Android 15 and Android 17's Transitions.dump.
            if (index < end && lines[index].trim().startsWith("hander=")) index++
        }
        assertTrue("The ShellTransitions dump has no transition tracks", trackCount > 0)
        return ready == pending + 2 &&
            lines[pending + 1].trim() == "none" &&
            tracks == ready + 2 &&
            lines[ready + 1].trim() == "none" &&
            tracksIdle
    }

    private fun injectPointer(
        action: Int,
        x: Float,
        y: Float,
        down: Long,
        eventTime: Long = SystemClock.uptimeMillis(),
    ) {
        val event = MotionEvent.obtain(down, eventTime, action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        val before = SystemClock.uptimeMillis()
        try {
            assertTrue(
                "System input injection rejected action $action",
                InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(event, true),
            )
        } finally {
            if (action == MotionEvent.ACTION_DOWN) {
                val after = SystemClock.uptimeMillis()
                Log.i("DetailBackInput", "DOWN downTime=$down before=$before after=$after elapsedMs=${after - before}")
            }
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
        assertForeground(scenario)
        scenario.onActivity {
            assertFalse("Back finished the Activity instead of handling its visible stack", it.isFinishing)
            assertEquals(expected, probe.routes.toList())
            assertEquals("A back gesture must pop exactly once", pops, probe.pops.get())
            assertEquals("A detail back reached the root NavDisplay", 0, probe.rootPops.get())
            assertEquals("Back reached the root consumer at the wrong depth", rootBacks, probe.rootBacks.get())
        }
    }

    private fun assertForeground(scenario: ActivityScenario<MainActivity>) {
        var focused = false
        var lifecycle: Lifecycle.State? = null
        var topPackage: String? = null
        val started = SystemClock.uptimeMillis()
        try {
            awaitCondition("Back left the app's foreground window") {
                scenario.onActivity {
                    focused = it.hasWindowFocus()
                    lifecycle = it.lifecycle.currentState
                }
                topPackage =
                    InstrumentationRegistry
                        .getInstrumentation()
                        .uiAutomation.rootInActiveWindow
                        ?.packageName
                        ?.toString()
                focused &&
                    lifecycle == Lifecycle.State.RESUMED &&
                    topPackage == InstrumentationRegistry.getInstrumentation().targetContext.packageName
            }
        } catch (failure: AssertionError) {
            throw AssertionError(
                "${failure.message}; focused=$focused lifecycle=$lifecycle foreground=$topPackage " +
                    "elapsedUptimeMs=${SystemClock.uptimeMillis() - started}",
                failure,
            )
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
                shell("dumpsys window")
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
        initiallyVisible: Boolean = true,
        val posterPath: Boolean = false,
        val visual: Boolean = false,
        val theme: MotionTheme = MotionTheme.Classic,
    ) {
        val stripeWidth = AtomicInteger()
        val routes = mutableStateListOf<String>().apply { addAll(initialRoutes) }
        val parentVisible = mutableStateOf(initiallyVisible)
        val hostComposed = AtomicBoolean()
        val pops = AtomicInteger()
        val rootPops = AtomicInteger()
        val rootBacks = AtomicInteger()
        val composedTop = AtomicReference<String>()
        val posterBounds = AtomicReference(Rect.Zero)
        val transitionActive = AtomicBoolean()
        val sharedMoving = AtomicBoolean()
        val sharedReturningRoute = AtomicReference<String?>()
        val sharedOpeningRoute = AtomicReference<String?>()
        val previewActive = AtomicBoolean()
        val previewSeen = AtomicBoolean()
        val routeSettled = AtomicBoolean()
        val menuComposed = AtomicBoolean()
        val menu = LiftMenuState()
    }

    private companion object {
        const val PREVIEW_UNDERLAY = -16745473
        const val PREVIEW_DETAIL = -2911171
        const val PREVIEW_STRIPE = -1
    }
}
