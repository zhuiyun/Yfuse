package com.yfuse.feature.widgets

import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yfuse.core.designsystem.AccessibilityOptions
import com.yfuse.core.designsystem.LocalRouteVisible
import com.yfuse.core.designsystem.YfuseTheme
import com.yfuse.core.widget.InstalledWidget
import com.yfuse.core.widget.WidgetPage
import com.yfuse.core.widget.WidgetPersistence
import com.yfuse.core.widget.WidgetRepository
import com.yfuse.core.widget.WidgetRuntime
import com.yfuse.core.widget.WidgetSection
import com.yfuse.launchMainActivityForInput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Exercises the actual custom home, touch pull gesture and app/route lifecycle against an offline feed. */
@RunWith(AndroidJUnit4::class)
class WidgetHomeRefreshInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val recommended = WidgetSection("recommended", "fixture", "list", "推荐", "hero", limit = 2, compact = true)
    private val ranked = recommended.copy(id = "ranked", title = "排行榜", style = "rank-rail")
    private val page = WidgetPage("home", "我的首页", listOf(recommended, ranked))

    @Test(timeout = 90_000L)
    fun batch_action_and_real_pull_refresh_change_recommendations_but_keep_ranking() {
        withHome { fixture, scenario ->
            val feed = fixture.repo.feed(recommended)
            val ranking = fixture.repo.feed(ranked)
            val original = feed.state.value.homeItems
            val originalRanking = ranking.state.value.items
            val calls = fixture.runtime.calls.get()
            await("Batch action missing") { findClickable("推荐换一批") != null }
            assertTrue(requireNotNull(findClickable("推荐换一批")).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await("Batch did not change") { feed.state.value.homeItems != original }
            assertEquals(calls, fixture.runtime.calls.get())
            assertEquals(originalRanking, ranking.state.value.items)
            assertEquals(null, findClickable("排行榜换一批"))
            val beforePull = feed.state.value.homeItems
            val response = CompletableDeferred<Unit>()
            fixture.runtime.gate = response
            pull(fixture.bounds.get())
            await("Real pull did not refresh both rows") {
                feed.state.value.refreshing && ranking.state.value.refreshing
            }
            assertEquals(beforePull, feed.state.value.homeItems)
            response.complete(Unit)
            await("Pull refresh did not settle") { !feed.state.value.busy && !ranking.state.value.busy }
            assertNotEquals(beforePull, feed.state.value.homeItems)
            assertEquals(originalRanking, ranking.state.value.items)
            assertFalse(feed.state.value.refreshing)
            assertTrue(fixture.runtime.calls.get() >= calls + 2)
            scenario.onActivity { fixture.active.value = false }
        }
    }

    @Test(timeout = 90_000L)
    fun expired_rows_refresh_on_foreground_return_but_not_while_hidden() {
        withHome { fixture, scenario ->
            val calls = fixture.runtime.calls.get()
            scenario.onActivity { fixture.active.value = false }
            instrumentation.waitForIdleSync()
            fixture.now.set(901_000L)
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            instrumentation.waitForIdleSync()
            SystemClock.sleep(300)
            assertEquals(calls, fixture.runtime.calls.get())
            scenario.onActivity { fixture.active.value = true }
            await("Visible expired rows were not revalidated") { fixture.runtime.calls.get() >= calls + 2 }
            await("Background refresh did not settle") {
                !fixture.repo
                    .feed(recommended)
                    .state.value.busy &&
                    !fixture.repo
                        .feed(ranked)
                        .state.value.busy
            }
            val after = fixture.runtime.calls.get()
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            instrumentation.waitForIdleSync()
            SystemClock.sleep(300)
            assertEquals(after, fixture.runtime.calls.get())
        }
    }

    @Test(timeout = 60_000L)
    fun small_pool_batch_moves_the_visible_gallery_selection() {
        val section = recommended.copy(style = "arc-poster", limit = 20)
        withHome(page.copy(sections = listOf(section))) { fixture, _ ->
            val feed = fixture.repo.feed(section)
            val first = requireNotNull(feed.state.value.homeItems).first()
            await("Initial gallery selection missing") { findClickable(first.title, "查看详情") != null }
            assertTrue(requireNotNull(findClickable("推荐换一批")).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await("Small pool did not rotate") {
                feed.state.value.homeItems
                    ?.first()
                    ?.id != first.id
            }
            val next = requireNotNull(feed.state.value.homeItems).first()
            await(
                "Batch rotated but the gallery still shows the old film",
            ) { findClickable(next.title, "查看详情") != null }
        }
    }

    private fun withHome(
        page: WidgetPage = this.page,
        block: (Fixture, androidx.test.core.app.ActivityScenario<com.yfuse.MainActivity>) -> Unit,
    ) {
        val fixture = Fixture()
        try {
            runBlocking {
                fixture.repo.install(fixture.repo.inspect(METADATA, "file:home-fixture.js"))
                fixture.repo.savePage(page)
            }
            launchMainActivityForInput().use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        YfuseTheme(dark = false, accessibility = AccessibilityOptions(reduceMotion = true)) {
                            CompositionLocalProvider(LocalRouteVisible provides fixture.active.value) {
                                Box(
                                    Modifier.fillMaxSize().padding(top = 48.dp).onGloballyPositioned {
                                        fixture.bounds.set(it.boundsInWindow())
                                    },
                                ) {
                                    WidgetPageContent(page, fixture.repo, onEdit = {}, onOpenTmdb = { _, _ -> })
                                }
                            }
                        }
                    }
                }
                await("Home rows did not load") {
                    page.sections.all {
                        fixture.repo
                            .feed(it)
                            .state.value.loaded
                    }
                }
                instrumentation.waitForIdleSync()
                block(fixture, scenario)
            }
        } finally {
            fixture.scope.cancel()
        }
    }

    private class Fixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val now = AtomicLong(0)
        val runtime = Runtime()
        val active = mutableStateOf(true)
        val bounds = AtomicReference(Rect.Zero)
        val repo =
            WidgetRepository(
                object : WidgetPersistence {
                    override suspend fun read(): String? = null

                    override suspend fun write(value: String) = Unit
                },
                runtime,
                scope,
                { "generated" },
                { it.hashCode().toString() },
                now::get,
            )
    }

    private class Runtime : WidgetRuntime {
        val calls = AtomicInteger()

        @Volatile var gate: CompletableDeferred<Unit>? = null

        override suspend fun metadata(source: String) = source

        override suspend fun invoke(
            widget: InstalledWidget,
            function: String,
            argument: JsonElement,
            timeoutSeconds: Int,
        ): String {
            calls.incrementAndGet()
            gate?.await()
            return (1..10).joinToString(",", "[", "]") { "{\"id\":\"preview-$it\",\"title\":\"影片 $it\"}" }
        }
    }

    private fun pull(bounds: Rect) {
        val x = bounds.center.x
        val start = bounds.top + bounds.height * .10f
        val end = bounds.top + bounds.height * .55f
        val down = SystemClock.uptimeMillis()

        fun send(
            action: Int,
            y: Float,
        ) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            try {
                assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
        }
        send(MotionEvent.ACTION_DOWN, start)
        repeat(20) { index ->
            SystemClock.sleep(20)
            send(MotionEvent.ACTION_MOVE, start + (end - start) * (index + 1) / 20)
        }
        send(MotionEvent.ACTION_UP, end)
    }

    private fun findClickable(
        label: String,
        actionLabel: String? = null,
    ): AccessibilityNodeInfo? {
        fun contains(node: AccessibilityNodeInfo): Boolean {
            if (node.contentDescription?.toString()?.contains(label) == true ||
                node.text?.toString()?.contains(label) == true
            ) {
                return true
            }
            return (0 until node.childCount).any { index ->
                node.getChild(index)?.let { !it.isClickable && contains(it) } == true
            }
        }

        fun search(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isVisibleToUser &&
                node.isClickable &&
                contains(node) &&
                (actionLabel == null || node.actionList.any { it.label?.toString() == actionLabel })
            ) {
                return node
            }
            for (index in 0 until node.childCount) search(node.getChild(index))?.let { return it }
            return null
        }
        return search(instrumentation.uiAutomation.rootInActiveWindow)
    }

    private fun await(
        message: String,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + 20_000L
        while (!condition()) {
            assertTrue(message, SystemClock.elapsedRealtime() < deadline)
            SystemClock.sleep(50)
        }
    }

    private companion object {
        const val METADATA = """{
            "id":"fixture","title":"Fixture","version":"1",
            "modules":[{"id":"list","title":"List","functionName":"load","cacheDuration":900}]
        }"""
    }
}
