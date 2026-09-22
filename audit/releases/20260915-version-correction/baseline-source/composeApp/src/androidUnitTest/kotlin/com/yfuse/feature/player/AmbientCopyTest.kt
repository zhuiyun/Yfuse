package com.yfuse.feature.player

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AmbientCopyTest {
    private class Destination(
        var released: Boolean = false,
        var reads: Int = 0,
    )

    @Test
    fun cancelling_a_copy_does_not_allow_another_request_until_the_callback_finishes() =
        runTest {
            val queue = AmbientCopyQueue()
            val old = Destination()
            lateinit var oldCallback: (Boolean) -> Unit
            val first =
                async(start = CoroutineStart.UNDISPATCHED) {
                    queue.copy(
                        { old },
                        { _, callback -> oldCallback = callback },
                        { it.reads++ },
                        { it.released = true },
                    )
                }
            first.cancelAndJoin()
            val submitted = CompletableDeferred<(Boolean) -> Unit>()
            val second =
                async(start = CoroutineStart.UNDISPATCHED) {
                    queue.copy(
                        { Destination() },
                        { _, callback -> submitted.complete(callback) },
                        { "new" },
                        { it.released = true },
                    )
                }
            assertFalse(submitted.isCompleted)
            assertFalse(old.released)
            oldCallback(true)
            submitted.await()(true)
            assertEquals("new", second.await())
            assertTrue(old.released)
            assertEquals(0, old.reads)
        }

    @Test
    fun cancelling_a_queued_seek_does_not_allocate_a_destination() =
        runTest {
            val queue = AmbientCopyQueue()
            lateinit var complete: (Boolean) -> Unit
            val first =
                async(start = CoroutineStart.UNDISPATCHED) {
                    queue.copy(
                        { Destination() },
                        { _, callback -> complete = callback },
                        { "first" },
                        { it.released = true },
                    )
                }
            var allocations = 0
            val queued =
                async(start = CoroutineStart.UNDISPATCHED) {
                    queue.copy(
                        {
                            allocations++
                            Destination()
                        },
                        { _, _ -> error("Cancelled seek must not copy") },
                        { "queued" },
                        {},
                    )
                }
            queued.cancelAndJoin()
            complete(true)
            assertEquals("first", first.await())
            assertEquals(0, allocations)
        }

    @Test
    fun cancelled_copy_keeps_its_destination_until_callback_and_does_not_read_it() =
        runTest {
            val destination = Destination()
            lateinit var complete: (Boolean) -> Unit
            val result =
                async(start = CoroutineStart.UNDISPATCHED) {
                    awaitAmbientCopy(
                        destination,
                        { _, callback -> complete = callback },
                        { it.reads++ },
                        { it.released = true },
                    )
                }
            result.cancelAndJoin()
            assertFalse(destination.released)
            complete(true)
            assertTrue(destination.released)
            assertEquals(0, destination.reads)
        }

    @Test
    fun new_copy_can_finish_before_cancelled_copy_without_sharing_or_releasing_its_destination() =
        runTest {
            val old = Destination()
            val fresh = Destination()
            lateinit var oldCallback: (Boolean) -> Unit
            lateinit var newCallback: (Boolean) -> Unit
            val first =
                async(start = CoroutineStart.UNDISPATCHED) {
                    awaitAmbientCopy(
                        old,
                        { _, callback -> oldCallback = callback },
                        {
                            it.reads++
                            "old"
                        },
                        { it.released = true },
                    )
                }
            first.cancelAndJoin()
            val second =
                async(start = CoroutineStart.UNDISPATCHED) {
                    awaitAmbientCopy(
                        fresh,
                        { _, callback -> newCallback = callback },
                        {
                            it.reads++
                            "fresh"
                        },
                        { it.released = true },
                    )
                }
            newCallback(true)
            assertEquals("fresh", second.await())
            assertTrue(fresh.released)
            assertFalse(old.released)
            oldCallback(true)
            assertTrue(old.released)
            assertEquals(0, old.reads)
            assertEquals(1, fresh.reads)
        }

    @Test
    fun rejected_source_releases_destination_without_reading() =
        runTest {
            val destination = Destination()
            assertNull(
                awaitAmbientCopy(
                    destination,
                    { _, _ -> throw IllegalArgumentException("Surface destroyed") },
                    { it.reads++ },
                    { it.released = true },
                ),
            )
            assertTrue(destination.released)
            assertEquals(0, destination.reads)
        }

    @Test
    fun failed_copy_releases_destination_without_reading() =
        runTest {
            val destination = Destination()
            assertNull(
                awaitAmbientCopy(
                    destination,
                    { _, callback -> callback(false) },
                    { it.reads++ },
                    { it.released = true },
                ),
            )
            assertTrue(destination.released)
            assertEquals(0, destination.reads)
        }

    @Test
    fun a_copy_that_never_calls_back_times_out_and_frees_the_lane_for_the_next_request() =
        runTest {
            val queue = AmbientCopyQueue(timeoutMs = 1_000L)
            val stuck = Destination()
            val first =
                async(start = CoroutineStart.UNDISPATCHED) {
                    queue.copy({ stuck }, { _, _ -> }, { it.reads++ }, { it.released = true })
                }
            // Virtual time runs past the watchdog: the read is given up, the lane is not.
            assertNull(first.await())
            assertFalse(stuck.released)
            val second =
                async(start = CoroutineStart.UNDISPATCHED) {
                    queue.copy(
                        { Destination() },
                        { _, callback -> callback(true) },
                        { "fresh" },
                        { it.released = true },
                    )
                }
            assertEquals("fresh", second.await())
            assertEquals(0, stuck.reads)
        }

    @Test
    fun picture_sized_surface_ignores_container_crop_even_when_it_happens_to_fit() {
        assertNull(ambientCopyRect(false, IntRect(0, 100, 1000, 700), IntSize(1000, 800), IntSize(2000, 1600)))
    }

    @Test
    fun letterbox_crop_scales_layout_coordinates_to_surface_buffer() {
        assertEquals(
            IntRect(0, 280, 3840, 1880),
            ambientCopyRect(true, IntRect(0, 140, 1920, 940), IntSize(1920, 1080), IntSize(3840, 2160)),
        )
        assertEquals(
            IntRect(240, 0, 1680, 1080),
            ambientCopyRect(true, IntRect(480, 0, 3360, 2160), IntSize(3840, 2160), IntSize(1920, 1080)),
        )
    }

    @Test
    fun fill_overflow_and_unlaid_out_surfaces_fall_back_to_entire_buffer() {
        assertNull(ambientCopyRect(true, IntRect(-100, 0, 1100, 800), IntSize(1000, 800), IntSize(1000, 800)))
        assertNull(ambientCopyRect(true, IntRect(0, 0, 1000, 800), IntSize.Zero, IntSize(1000, 800)))
        assertNull(ambientCopyRect(true, IntRect(0, 0, 1000, 800), IntSize(1000, 800), IntSize.Zero))
    }

    @Test
    fun crop_collapsing_to_zero_buffer_pixels_is_not_submitted() {
        assertNull(ambientCopyRect(true, IntRect(0, 0, 1, 1), IntSize(1000, 800), IntSize(32, 18)))
    }
}
