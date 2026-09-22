package com.yfuse.core.designsystem

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

class AnimatedColorStateTest {
    @Test
    fun semanticTargetDoesNotSubscribeThePageToAnimationFrames() {
        val frame = mutableStateOf(Color.Black)
        val color = AnimatedColorState(Color.White, frame)
        val observer = SnapshotStateObserver { it() }
        val page = Any()
        val paint = Any()
        var pageInvalidations = 0
        var paintInvalidations = 0
        observer.start()
        try {
            observer.observeReads(page, { pageInvalidations++ }) {
                assertEquals(Color.White, color.target)
            }
            observer.observeReads(paint, { paintInvalidations++ }) {
                assertEquals(Color.Black, color.value)
            }
            frame.value = Color.Gray
            Snapshot.sendApplyNotifications()
            assertEquals(0, pageInvalidations)
            assertEquals(1, paintInvalidations)
            assertEquals(Color.Gray, color.value)
            assertEquals(Color.White, color.target)
        } finally {
            observer.stop()
            observer.clear()
        }
    }
}
