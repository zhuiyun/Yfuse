package com.yfuse.core.cast

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CastDiscoveryInstrumentedTest {
    @Test
    fun device_discovery_can_be_refreshed_when_chromecast_is_unavailable() =
        runBlocking {
            initializeCastApplicationContext(InstrumentationRegistry.getInstrumentation().targetContext)
            val manager = createCastManager()
            repeat(2) {
                withTimeout(30_000) { manager.discover() }
                val state = manager.state.value
                assertFalse(state.discovering)
                assertFalse(state.hasActiveSession)
                assertEquals(state.devices.size, state.devices.distinctBy { it.id }.size)
                assertTrue(state.devices.isNotEmpty() || !state.error.isNullOrBlank())
            }
            assertFalse(manager.play("chromecast:unlisted", "https://example.test/video.mp4", "Test"))
            assertFalse(manager.state.value.hasActiveSession)
            assertEquals("Chromecast 设备已离线，请重新发现", manager.state.value.error)
        }
}
