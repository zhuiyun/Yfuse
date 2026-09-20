package com.yfuse.core.designsystem

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackdropFramesTest {
    @Test
    fun empty_surface_redraws_when_the_first_frame_arrives() =
        observing { observer ->
            val frames = BackdropFrames()
            Snapshot.notifyObjectsInitialized()
            var redraws = 0
            observer.observeReads(Any(), { redraws++ }) {
                assertFalse(frames.available)
            }

            Snapshot.withMutableSnapshot { frames.recorded() }
            Snapshot.sendApplyNotifications()

            assertTrue(frames.available)
            assertEquals(1, redraws)
        }

    @Test
    fun ready_surface_redraws_for_each_new_frame() =
        observing { observer ->
            val frames = BackdropFrames()
            Snapshot.notifyObjectsInitialized()
            Snapshot.withMutableSnapshot { frames.recorded() }
            Snapshot.sendApplyNotifications()
            val surface = Any()
            var redraws = 0
            val onChanged: (Any) -> Unit = { redraws++ }

            repeat(3) {
                observer.observeReads(surface, onChanged) { assertTrue(frames.available) }
                Snapshot.withMutableSnapshot { frames.recorded() }
                Snapshot.sendApplyNotifications()
            }

            assertEquals(3, redraws)
        }

    @Test
    fun recording_does_not_subscribe_the_source_to_its_own_frames() =
        observing { observer ->
            val frames = BackdropFrames()
            Snapshot.notifyObjectsInitialized()
            var redraws = 0
            observer.observeReads(Any(), { redraws++ }) { frames.recorded() }
            Snapshot.sendApplyNotifications()
            Snapshot.withMutableSnapshot { frames.recorded() }
            Snapshot.sendApplyNotifications()

            assertEquals(0, redraws)
        }

    private fun observing(block: (SnapshotStateObserver) -> Unit) {
        val observer = SnapshotStateObserver { it() }
        observer.start()
        try {
            block(observer)
        } finally {
            observer.stop()
            observer.clear()
        }
    }
}
