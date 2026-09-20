package com.yfuse.core2.android

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidMetadataProbeStageTest {
    @Test
    fun reserved_decoder_budget_does_not_publish_a_new_initial_track_selection() {
        val prior =
            com.yfuse.core2.api
                .YTrack("audio:1", com.yfuse.core2.api.YTrackType.Audio, "English", "eng")
        val requested =
            com.yfuse.core2.api
                .YTrack("audio:4", com.yfuse.core2.api.YTrackType.Audio, "Chinese", "zho")
        val published =
            java.util.concurrent.atomic
                .AtomicReference(prior)
        AndroidProbeBudget(1_000L).use { parent ->
            val result =
                runMetadataProbeStage(parent, AndroidBoundedProbe(), 100L, 2_000L, { false }) { stage ->
                    stage.ifActive { published.set(requested) }
                    true
                }
            assertFalse(result)
            assertEquals(prior, published.get())
            parent.ensureActive()
        }
    }

    @Test
    fun stage_deadline_cancels_io_preserves_parent_and_rejects_late_handoff() {
        val owner = Executors.newSingleThreadExecutor()
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val cancelled = AtomicBoolean()
        val published = AtomicBoolean()
        try {
            AndroidProbeBudget(5_000L).use { parent ->
                assertEquals(
                    -1,
                    runMetadataProbeStage(parent, AndroidBoundedProbe(owner), 150L, 100L, { -1 }) { stage ->
                        stage.onCancel { cancelled.set(true) }
                        entered.countDown()
                        while (true) {
                            try {
                                release.await()
                                break
                            } catch (_: InterruptedException) {
                            }
                        }
                        stage.ifActive { published.set(true) }
                        1
                    },
                )
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                assertTrue(cancelled.get())
                parent.ensureActive()
                release.countDown()
                owner.submit {}.get(1, TimeUnit.SECONDS)
                assertFalse(published.get())
                assertEquals(42, runMetadataProbeStage(parent, AndroidBoundedProbe(owner), 500L, 100L, { -1 }) { 42 })
            }
        } finally {
            release.countDown()
            owner.shutdownNow()
        }
    }

    @Test
    fun parent_cancellation_is_not_treated_as_backend_failure() {
        AndroidProbeBudget().use { parent ->
            parent.cancel("user left")
            assertFailsWith<AndroidProbeAbortedException> {
                runMetadataProbeStage(parent, AndroidBoundedProbe(), 100L, 0L, { -1 }) { 1 }
            }
        }
    }

    @Test
    fun reserved_decoder_time_is_never_spent_on_metadata() {
        AndroidProbeBudget(1_000L).use { parent ->
            assertEquals(
                -1,
                runMetadataProbeStage(parent, AndroidBoundedProbe(), 100L, 2_000L, { -1 }) { error("No budget") },
            )
            parent.ensureActive()
        }
    }
}
