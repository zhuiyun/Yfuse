package com.yfuse.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yfuse.MainActivity
import com.yfuse.core.designsystem.OfficialNavDisplay
import com.yfuse.core.designsystem.OfficialNavMotion
import com.yfuse.core.designsystem.YfuseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class DetailBackNavigationInstrumentedTest {
    @Test(timeout = 60_000L)
    fun activity_back_returns_through_nested_details_without_finishing_the_app() {
        val routes = mutableStateListOf("library", "detail-a", "detail-b")
        val pops = AtomicInteger()
        val rootPops = AtomicInteger()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    YfuseTheme(dark = false) {
                        OfficialNavDisplay(
                            backStack = listOf("tab"),
                            onBack = { rootPops.incrementAndGet() },
                            contentKey = { it },
                            motion = OfficialNavMotion.RootTab,
                        ) {
                            OfficialNavDisplay(
                                backStack = routes.toList(),
                                contentKey = { it },
                                onBack = {
                                    pops.incrementAndGet()
                                    if (routes.size > 1) routes.removeAt(routes.lastIndex)
                                },
                            ) { Box(Modifier.fillMaxSize()) }
                        }
                    }
                }
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                assertFalse(it.isFinishing)
                assertEquals(listOf("library", "detail-a"), routes.toList())
                assertEquals(1, pops.get())
                assertEquals(0, rootPops.get())
            }
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                assertFalse(it.isFinishing)
                assertEquals(listOf("library"), routes.toList())
                assertEquals(2, pops.get())
                assertEquals(0, rootPops.get())
            }
        }
    }
}
