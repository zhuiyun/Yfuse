from edit import read, write

base = 'composeApp/src/commonTest/kotlin/com/yfuse/'
write(base + 'core/designsystem/ThemeCrossfadeTest.kt', '''package com.yfuse.core.designsystem

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
    fun theme_frames_change_paint_without_republishing_palette_or_recomposing_reader() = runTest {
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
    fun ordinary_artwork_colour_changes_do_not_start_a_second_theme_animation() = runTest {
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
internal class ThemeConsumerHarness(private val scope: TestScope) {
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

    fun apply() = advance(16)

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
    override fun insertTopDown(index: Int, instance: Unit) = Unit
    override fun insertBottomUp(index: Int, instance: Unit) = Unit
    override fun remove(index: Int, count: Int) = Unit
    override fun move(from: Int, to: Int, count: Int) = Unit
    override fun onClear() = Unit
}
''')
write(base + 'core/designsystem/ThemeCrossfadeStateTest.kt', '''package com.yfuse.core.designsystem

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.math.abs

class ThemeCrossfadeStateTest {
    @Test
    fun reducing_motion_mid_transition_finishes_the_unchanged_target() = runTest {
        themeConsumerTest {
            theme.value = dark
            apply()
            advance(100)
            assertNotEquals(dark.palette.background, shown.value)
            reduced.value = true
            repeat(3) { apply() }
            assertEquals(dark.palette.background, shown.value)
        }
    }

    @Test
    fun rapid_theme_reversal_starts_from_the_visible_colour() = runTest {
        themeConsumerTest {
            theme.value = dark
            apply()
            advance(100)
            val before = shown.value
            theme.value = light
            apply()
            assertTrue(abs(before.red - shown.value.red) < 0.01f)
            advance(500)
            assertEquals(light.palette.background, shown.value)
        }
    }

    @Test
    fun hidden_routes_take_the_target_without_an_animation_clock() = runTest {
        themeConsumerTest {
            visible.value = false
            theme.value = dark
            repeat(3) { apply() }
            assertEquals(dark.palette.background, shown.value)
            val count = compositions
            advance(100)
            assertEquals(count, compositions)
        }
    }
}
''')

# Exercise the real runtime collector and projection, including telemetry that used to invalidate it.
s = read(base + 'feature/player/PlayerControlSnapshotTest.kt')
s = s.replace('import kotlinx.coroutines.launch', 'import kotlinx.coroutines.launch\nimport kotlinx.coroutines.flow.MutableStateFlow')
s = s.replace('class PlayerControlSnapshotTest', 'class PlaybackRuntimeContentTest')
s = s.replace('position_and_buffer_ticks_update_timeline_without_recomposing_the_control_reader', 'runtime_ticks_and_telemetry_keep_live_values_without_recomposing_routing')
s = s.replace('val source = mutableStateOf(', 'val source = MutableStateFlow(')
old = '''                    val chrome by rememberPlayerControlSnapshot(source)
                    val captured = chrome
                    SideEffect {
                        controls = captured
                        controlCompositions++
                    }
                    PlaybackTimelineContent(source) { current -> SideEffect { timeline = current } }'''
new = '''                    PlaybackRuntimeContent(owner = source, source = source, items = emptyList()) { structural, live ->
                        SideEffect {
                            controls = structural
                            controlCompositions++
                        }
                        PlaybackTimelineContent(live) { current -> SideEffect { timeline = current } }
                    }'''
assert old in s
s = s.replace(old, new)
s = s.replace('source.value.copy(positionMs = 1_500L + index * 500L, bufferedPositionMs = 30_000L + index)', '''source.value.copy(
                            positionMs = 1_500L + index * 500L,
                            bufferedPositionMs = 30_000L + index,
                            diagnostics = source.value.diagnostics.copy(
                                frameRate = 23.9f + index * 0.01f,
                                networkBitsPerSecond = 1_000_000L + index,
                                droppedFrames = index,
                                avSyncOffsetMs = index.toLong(),
                                playbackHealth = "sample $index",
                                outputEvidence = source.value.diagnostics.outputEvidence.copy(rendererDetail = "frame $index"),
                            ),
                        )''')
# All defaults still travel through routing, so pause/queue changes and reset-to-zero remain covered.
write(base + 'feature/player/PlaybackRuntimeContentTest.kt', s.replace('import androidx.compose.runtime.getValue\n', '').replace('import androidx.compose.runtime.mutableStateOf\n', ''))

write(base + 'feature/player/PlayerBatteryStatusTest.kt', '''package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayerBatteryStatusTest {
    @Test
    fun absent_or_invalid_battery_data_is_not_displayed_as_zero() {
        assertNull(playerBatteryPercent(-1, 100))
        assertNull(playerBatteryPercent(50, -1))
        assertNull(playerBatteryPercent(50, 0))
    }

    @Test
    fun non_percentage_scales_and_out_of_range_levels_are_bounded_without_overflow() {
        assertEquals(75, playerBatteryPercent(150, 200))
        assertEquals(0, playerBatteryPercent(0, 100))
        assertEquals(100, playerBatteryPercent(101, 100))
        assertEquals(100, playerBatteryPercent(Int.MAX_VALUE, 1))
    }
}
''')
