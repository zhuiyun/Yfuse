package com.yfuse.core.designsystem

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ThemeCrossfadeTest {
    @Test
    fun theme_frames_change_paint_without_republishing_palette_or_recomposing_reader() =
        runTest {
            themeConsumerTest {
                theme.value = dark
                apply()
                val count = compositions
                advance(100)
                assertNotEquals(light.palette.background, shown.value)
                assertNotEquals(dark.palette.background, shown.value)
                assertEquals(count, compositions)
                advance(500)
                assertEquals(dark.palette.background, shown.value)
            }
        }

    @Test
    fun ordinary_artwork_colour_changes_do_not_start_a_second_theme_animation() =
        runTest {
            themeConsumerTest {
                overrideColor.value = Color.Red
                apply()
                assertEquals(Color.Red, shown.value)
                overrideColor.value = Color.Blue
                apply()
                assertEquals(Color.Blue, shown.value)
            }
        }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal suspend fun TestScope.themeConsumerTest(block: suspend ThemeConsumerHarness.() -> Unit) {
    val harness = ThemeConsumerHarness(this)
    try {
        harness.start()
        harness.block()
    } finally {
        harness.close()
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class ThemeConsumerHarness(
    private val scope: TestScope,
) {
    val light = ThemeColors(LightPalette, resolveAccentColors(Brand.Primary, dark = false))
    val dark = ThemeColors(DarkPalette, resolveAccentColors(Brand.Primary, dark = true))
    val theme = mutableStateOf(light)
    val reduced = mutableStateOf(false)
    val visible = mutableStateOf(true)
    val overrideColor = mutableStateOf<Color?>(null)
    lateinit var shown: State<Color>
    var compositions = 0
    private var nanos = 0L
    private val clock = BroadcastFrameClock()
    private val recomposer = Recomposer(scope.coroutineContext + clock)
    private val runner = scope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
    private val composition = Composition(ThemeNoNodes(), recomposer)

    fun start() {
        composition.setContent {
            val target = theme.value
            CompositionLocalProvider(
                LocalThemeColorTarget provides target,
                LocalAccessibilityOptions provides AccessibilityOptions(reduceMotion = reduced.value),
                LocalRouteVisible provides visible.value,
            ) {
                val paint = rememberThemeConsumerColor(overrideColor.value ?: target.palette.background)
                SideEffect {
                    shown = paint
                    compositions++
                }
            }
        }
        apply()
    }

    fun apply() {
        // Apply changes and start the effect's clock before advancing elapsed animation time.
        repeat(3) { advance(0) }
    }

    fun advance(milliseconds: Long) {
        Snapshot.sendApplyNotifications()
        scope.runCurrent()
        nanos += milliseconds * 1_000_000L
        clock.sendFrame(nanos)
        scope.runCurrent()
    }

    suspend fun close() {
        composition.dispose()
        recomposer.cancel()
        runner.join()
    }
}

private class ThemeNoNodes : AbstractApplier<Unit>(Unit) {
    override fun insertTopDown(
        index: Int,
        instance: Unit,
    ) = Unit

    override fun insertBottomUp(
        index: Int,
        instance: Unit,
    ) = Unit

    override fun remove(
        index: Int,
        count: Int,
    ) = Unit

    override fun move(
        from: Int,
        to: Int,
        count: Int,
    ) = Unit

    override fun onClear() = Unit
}
