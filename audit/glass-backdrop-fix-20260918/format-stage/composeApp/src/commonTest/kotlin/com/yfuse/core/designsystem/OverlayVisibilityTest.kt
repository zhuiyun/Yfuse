package com.yfuse.core.designsystem

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverlayVisibilityTest {
    @Test
    fun dialogs_enable_shared_capture_until_the_last_dialog_closes() {
        val visibility = OverlayVisibility()
        val observer = SnapshotStateObserver { it() }
        val recomposer = Recomposer(EmptyCoroutineContext)
        val first = Composition(EmptyApplier(), recomposer)
        val second = Composition(EmptyApplier(), recomposer)
        var redraws = 0
        observer.start()
        try {
            observer.observeReads(Any(), { redraws++ }) { assertFalse(visibility.any) }
            first.setContent {
                CompositionLocalProvider(LocalOverlayVisibility provides visibility) {
                    ReportOverlayVisible()
                }
            }
            Snapshot.sendApplyNotifications()
            assertTrue(visibility.any)
            assertEquals(1, redraws)

            second.setContent {
                CompositionLocalProvider(LocalOverlayVisibility provides visibility) {
                    ReportOverlayVisible()
                }
            }
            assertEquals(2, visibility.count)
            first.dispose()
            assertTrue(visibility.any)
            second.dispose()
            assertFalse(visibility.any)
        } finally {
            first.dispose()
            second.dispose()
            recomposer.cancel()
            observer.stop()
            observer.clear()
        }
    }

    private class EmptyApplier : AbstractApplier<Unit>(Unit) {
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
}
