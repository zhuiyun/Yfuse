package com.yfuse.feature.player

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import androidx.media3.exoplayer.source.TrackGroupArray
import com.yfuse.core.playback.PlaybackOptimizationMode
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@UnstableApi
class AdaptiveStartupLoadControlTest {
    @Test
    fun current_media3_callbacks_preserve_real_load_control_budget_and_lifecycle() {
        val delegate =
            DefaultLoadControl
                .Builder()
                .setBackBuffer(1_500, true)
                .setTargetBufferBytes(C.DEFAULT_BUFFER_SEGMENT_SIZE)
                .build()
        val control =
            AdaptiveStartupLoadControl(
                delegate,
                PlaybackOptimizationMode.Balanced,
                StartupTransferEvidence { 0L },
                currentItem = { null },
            )
        val playerId = PlayerId("adaptive-load-control-test")
        val timeline = SinglePeriodTimeline(10_000_000L, true, false, false, null, MediaItem.EMPTY)
        val mediaPeriodId = MediaPeriodId(timeline.getUidOfPeriod(0))
        val parameters =
            LoadControl.Parameters(
                playerId,
                timeline,
                mediaPeriodId,
                0L,
                0L,
                1f,
                true,
                false,
                C.TIME_UNSET,
                C.TIME_UNSET,
            )

        // ExoPlayer asks these during construction, before onPrepared registers its allocator state.
        assertEquals(1_500_000L, control.getBackBufferDurationUs(playerId))
        assertTrue(control.retainBackBufferFromKeyframe(playerId))
        control.onPrepared(playerId)
        control.onTracksSelected(parameters, TrackGroupArray.EMPTY, emptyArray())
        assertTrue(control.shouldContinueLoading(parameters))
        assertFalse(control.shouldStartPlayback(parameters))
        assertFalse(control.shouldContinuePreloading(playerId, timeline, mediaPeriodId, 0L))

        val allocator = control.getAllocator(playerId)
        val allocation = allocator.allocate()
        val previousLogLevel = Log.getLogLevel()
        try {
            // This deliberately hits Media3's small-buffer warning. The JVM Android Log stub
            // returns null for stack traces; only logging is muted, not the real budget check.
            Log.setLogLevel(Log.LOG_LEVEL_OFF)
            assertEquals(C.DEFAULT_BUFFER_SEGMENT_SIZE, allocator.totalBytesAllocated)
            assertFalse(control.shouldContinueLoading(parameters))
            assertTrue(control.shouldStartPlayback(parameters))
        } finally {
            Log.setLogLevel(previousLogLevel)
            allocator.release(allocation)
        }
        assertTrue(control.shouldContinueLoading(parameters))
        control.onStopped(playerId)
        assertTrue(control.shouldContinuePreloading(playerId, timeline, mediaPeriodId, 0L))

        control.onPrepared(playerId)
        assertTrue(control.shouldContinueLoading(parameters))
        control.onReleased(playerId)
        assertTrue(control.shouldContinuePreloading(playerId, timeline, mediaPeriodId, 0L))
    }

    @Test
    fun media3_upgrades_cannot_silently_inherit_unforwarded_java_default_callbacks() {
        val currentCallbacks =
            LoadControl::class.java.methods.filter { method ->
                !Modifier.isStatic(method.modifiers) && !method.isAnnotationPresent(java.lang.Deprecated::class.java)
            }
        assertTrue(currentCallbacks.isNotEmpty())
        for (callback in currentCallbacks) {
            val implementation =
                AdaptiveStartupLoadControl::class.java.getMethod(
                    callback.name,
                    *callback.parameterTypes,
                )
            assertEquals(AdaptiveStartupLoadControl::class.java, implementation.declaringClass, callback.toString())
        }
    }
}
