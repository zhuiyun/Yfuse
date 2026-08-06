package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MpvEndFileTrackerTest {

    @Test
    fun active_file_replacement_consumes_one_end_before_a_real_failure() {
        val tracker = MpvEndFileTracker()

        assertFalse(tracker.beforeLoad()) // Initial load has no predecessor.
        assertTrue(tracker.beforeLoad()) // selectItem/retry replaces the active load.
        assertEquals(1, tracker.pendingExpectedEnds)

        assertTrue(tracker.consumeExpectedEnd())
        assertEquals(0, tracker.pendingExpectedEnds)
        assertFalse(tracker.consumeExpectedEnd()) // The replacement itself really failed.
    }

    @Test
    fun rapid_replacements_reserve_an_end_for_each_superseded_load() {
        val tracker = MpvEndFileTracker()

        tracker.beforeLoad() // A
        assertTrue(tracker.beforeLoad()) // A -> B
        assertTrue(tracker.beforeLoad()) // B -> C before B emits START_FILE
        assertEquals(2, tracker.pendingExpectedEnds)

        assertTrue(tracker.consumeExpectedEnd())
        assertTrue(tracker.consumeExpectedEnd())
        assertFalse(tracker.consumeExpectedEnd()) // A genuine END_FILE from C.
    }

    @Test
    fun stop_then_progressive_load_does_not_reserve_a_second_phantom_end() {
        val tracker = MpvEndFileTracker()

        tracker.beforeLoad()
        assertTrue(tracker.beforeStop())
        assertFalse(tracker.beforeLoad()) // Progressive starts after logical stop.
        assertEquals(1, tracker.pendingExpectedEnds)

        assertTrue(tracker.consumeExpectedEnd()) // HLS stop event.
        assertFalse(tracker.consumeExpectedEnd()) // Progressive really ended.
    }

    @Test
    fun failed_commands_roll_back_their_expected_end() {
        val tracker = MpvEndFileTracker()

        tracker.beforeLoad()
        val replacing = tracker.beforeLoad()
        tracker.rollbackLoad(replacing)
        assertEquals(0, tracker.pendingExpectedEnds)

        val stopping = tracker.beforeStop()
        tracker.rollbackStop(stopping)
        assertEquals(0, tracker.pendingExpectedEnds)
        assertTrue(tracker.beforeLoad())
    }
}
