package com.yfuse.core.security

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yfuse.MainActivity
import com.yfuse.core.data.ServerSessionRestoreException
import com.yfuse.feature.player.PlayerActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ServerSessionRecoveryInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val unavailable = AtomicBoolean(true)
    private val servicesStarted = AtomicInteger()

    private fun blockStartup() {
        instrumentation.runOnMainSync {
            ServerSessionRecovery.initialize(
                restore = {
                    if (unavailable.get()) throw ServerSessionRestoreException(SecureStoreException("test outage"))
                },
                startServices = { servicesStarted.incrementAndGet() },
            )
        }
    }

    @After
    fun resetStartupGate() {
        instrumentation.runOnMainSync {
            ServerSessionRecovery.initialize(restore = {}, startServices = {})
        }
    }

    @Test
    fun main_activity_retries_without_starting_services_during_the_outage() {
        blockStartup()
        ActivityScenario.launch(MainActivity::class.java).use {
            waitUntil { nodeWithText("重试") != null }
            assertEquals(0, servicesStarted.get())
            unavailable.set(false)
            var button = requireNotNull(nodeWithText("重试"))
            while (!button.isClickable) button = button.parent ?: break
            assertTrue(button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            waitUntil { servicesStarted.get() == 1 }
            instrumentation.waitForIdleSync()
            assertEquals(1, servicesStarted.get())
        }
    }

    @Test
    fun blocked_player_handles_volume_and_lifecycle_without_uninitialized_state() {
        blockStartup()
        val intent = Intent(instrumentation.targetContext, PlayerActivity::class.java)
        ActivityScenario.launch<PlayerActivity>(intent).use { scenario ->
            waitUntil { nodeWithText("重试") != null }
            scenario.onActivity { activity ->
                activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP))
                activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_UP))
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertEquals(0, servicesStarted.get())
        }
    }

    private fun nodeWithText(text: String): AccessibilityNodeInfo? {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            if (node.text?.toString() == text || node.contentDescription?.toString() == text) return node
            repeat(node.childCount) { index -> node.getChild(index)?.let(pending::addLast) }
        }
        return null
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        assertTrue("Recovery UI did not reach the expected state", condition())
    }
}
