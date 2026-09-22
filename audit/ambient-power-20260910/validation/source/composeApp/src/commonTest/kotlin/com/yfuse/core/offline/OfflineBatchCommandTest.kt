package com.yfuse.core.offline

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OfflineBatchCommandTest {
    @Test
    fun pause_resume_and_remove_selections_larger_than_capacity_each_use_one_fifo_slot() =
        runTest {
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            try {
                val errors = mutableListOf<Throwable>()
                val queue = OfflineCommandQueue(scope, capacity = 3, onFailure = errors::add)
                val ids = List(130) { "download-$it" }
                val states = ids.associateWith { "queued" }.toMutableMap()
                val events = mutableListOf<Pair<String, String>>()
                assertTrue(
                    queue.submitBatch(ids) { id ->
                        states[id] = "paused"
                        events += "pause" to id
                    },
                )
                assertTrue(
                    queue.submitBatch(ids) { id ->
                        assertEquals("paused", states[id])
                        states[id] = "queued"
                        events += "resume" to id
                    },
                )
                assertTrue(
                    queue.submitBatch(ids) { id ->
                        assertEquals("queued", states.remove(id))
                        events += "remove" to id
                    },
                )
                assertTrue(events.isEmpty(), "No selected item may perform disk work on the caller")
                queue.awaitPending()
                assertTrue(states.isEmpty())
                assertTrue(errors.isEmpty())
                assertEquals(listOf("pause", "resume", "remove").flatMap { action -> ids.map { action to it } }, events)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun accepted_selection_is_snapshotted_and_a_failed_item_does_not_discard_later_deletions() =
        runTest {
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            try {
                val errors = mutableListOf<Throwable>()
                val queue = OfflineCommandQueue(scope, onFailure = errors::add)
                val selected = mutableListOf("a", "b", "a", "c")
                val events = mutableListOf<String>()
                assertTrue(
                    queue.submitBatch(selected, beforeBatch = { events += "index ready" }) { id ->
                        events += "persist revision:$id"
                        if (id == "b") error("directory permission lost")
                        events += "delete artifacts:$id"
                        events += "remove row:$id"
                    },
                )
                selected.clear()
                queue.submit { events += "next command" }
                queue.awaitPending()
                assertEquals(
                    listOf(
                        "index ready",
                        "persist revision:a",
                        "delete artifacts:a",
                        "remove row:a",
                        "persist revision:b",
                        "persist revision:c",
                        "delete artifacts:c",
                        "remove row:c",
                        "next command",
                    ),
                    events,
                )
                assertEquals("directory permission lost", errors.single().message)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun a_full_queue_rejects_the_entire_selection_with_feedback_and_a_failed_index_applies_nothing() =
        runTest {
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            try {
                val errors = mutableListOf<Throwable>()
                val queue = OfflineCommandQueue(scope, capacity = 1, onFailure = errors::add)
                val applied = mutableListOf<Int>()
                val ids = (0..129).toList()
                assertTrue(queue.submit {})
                assertFalse(queue.submitBatch(ids) { applied += it })
                queue.awaitPending()
                assertTrue(applied.isEmpty())
                assertTrue(errors.single() is OfflineCommandRejectedException)
                assertTrue(queue.submitBatch(ids, beforeBatch = { error("index unavailable") }) { applied += it })
                queue.awaitPending()
                assertTrue(applied.isEmpty())
                assertEquals("index unavailable", errors.last().message)
            } finally {
                scope.cancel()
            }
        }
}
