package com.yfuse.app

import android.os.SystemClock
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yfuse.core.designsystem.LocalRouteVisibilityState
import com.yfuse.core.designsystem.LocalRouteVisible
import com.yfuse.core.designsystem.PlatformNavigationBackHandler
import com.yfuse.launchMainActivityForInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class PlatformNavigationBackInstrumentedTest {
    @Test(timeout = 60_000L)
    fun firstActivityGestureIgnoresPrivateSceneOwnersButRespectsVisibility() {
        val composed = AtomicBoolean()
        val composedVisible = AtomicBoolean()
        val starts = AtomicInteger()
        val progresses = AtomicInteger()
        val completes = AtomicInteger()
        val cancels = AtomicInteger()
        val fallbacks = AtomicInteger()
        val visible = mutableStateOf(true)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        launchMainActivityForInput().use { scenario ->
            lateinit var fallback: OnBackPressedCallback
            lateinit var privateDispatcher: NavigationEventDispatcher
            scenario.onActivity { activity ->
                privateDispatcher = NavigationEventDispatcher()
                val sceneDispatcher =
                    object : NavigationEventDispatcherOwner {
                        override val navigationEventDispatcher = privateDispatcher
                    }
                val sceneOwner =
                    object : LifecycleOwner {
                        override val lifecycle =
                            LifecycleRegistry(this).apply { currentState = Lifecycle.State.CREATED }
                    }
                fallback =
                    object : OnBackPressedCallback(true) {
                        override fun handleOnBackPressed() {
                            fallbacks.incrementAndGet()
                        }
                    }
                activity.onBackPressedDispatcher.addCallback(activity, fallback)
                activity.setContent {
                    CompositionLocalProvider(
                        LocalNavigationEventDispatcherOwner provides sceneDispatcher,
                        LocalLifecycleOwner provides sceneOwner,
                        LocalRouteVisible provides visible.value,
                        LocalRouteVisibilityState provides visible,
                    ) {
                        PlatformNavigationBackHandler(
                            enabled = true,
                            onStarted = { starts.incrementAndGet() },
                            onProgressed = { progresses.incrementAndGet() },
                            onCompleted = { completes.incrementAndGet() },
                            onCancelled = { cancels.incrementAndGet() },
                        )
                        Box(Modifier.fillMaxSize())
                        SideEffect {
                            composedVisible.set(visible.value)
                            composed.set(true)
                        }
                    }
                }
            }
            try {
                val deadline = SystemClock.uptimeMillis() + 5_000L
                while (!composed.get() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(20L)
                assertTrue("The bridge was not composed", composed.get())
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    assertEquals(Lifecycle.State.RESUMED, activity.lifecycle.currentState)
                    val dispatcher = activity.onBackPressedDispatcher
                    dispatcher.dispatchOnBackStarted(BackEventCompat(1f, 30f, 0f, BackEventCompat.EDGE_LEFT))
                    assertEquals("Start must be delivered synchronously, before any progress", 1, starts.get())
                    assertEquals(0, progresses.get())
                    dispatcher.dispatchOnBackCancelled()
                    assertEquals(1, cancels.get())
                    assertEquals(0, completes.get())
                    assertEquals(0, fallbacks.get())
                    dispatcher.dispatchOnBackStarted(BackEventCompat(100f, 30f, 0f, BackEventCompat.EDGE_RIGHT))
                    dispatcher.dispatchOnBackProgressed(BackEventCompat(60f, 30f, 0.4f, BackEventCompat.EDGE_RIGHT))
                    dispatcher.onBackPressed()
                    assertEquals(2, starts.get())
                    assertEquals(1, progresses.get())
                    assertEquals(1, completes.get())
                    dispatcher.dispatchOnBackStarted(BackEventCompat(1f, 30f, 0f, BackEventCompat.EDGE_LEFT))
                    visible.value = false
                }
                awaitCondition { !composedVisible.get() && cancels.get() == 2 }
                scenario.onActivity { visible.value = true }
                awaitCondition { composedVisible.get() }
                scenario.onActivity { activity ->
                    activity.onBackPressedDispatcher.onBackPressed()
                    assertEquals("Hide/reopen must reject the old gesture", 1, completes.get())
                    assertEquals("Disabling cancels only once", 2, cancels.get())
                    activity.onBackPressedDispatcher.onBackPressed()
                    assertEquals("An ordinary back must still work", 2, completes.get())
                    assertEquals(0, fallbacks.get())
                    visible.value = false
                }
                awaitCondition { !composedVisible.get() }
                scenario.onActivity { activity ->
                    activity.onBackPressedDispatcher.onBackPressed()
                    assertEquals("A hidden route must leave back to its predecessor", 1, fallbacks.get())
                    assertEquals(2, completes.get())
                }
            } finally {
                scenario.onActivity {
                    fallback.remove()
                    privateDispatcher.dispose()
                }
            }
        }
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000L
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(20L)
        assertTrue("The expected composition state was not reached", condition())
    }
}
