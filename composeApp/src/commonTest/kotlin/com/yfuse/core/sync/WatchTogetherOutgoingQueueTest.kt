package com.yfuse.core.sync

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WatchTogetherOutgoingQueueTest {
    @Test
    fun admitted_messages_are_sent_in_fifo_order() =
        runTest {
            val owner = Any()
            val sent = mutableListOf<String>()
            val results = mutableListOf<Boolean>()
            val queue =
                WatchOutgoingQueue<Any, String>(
                    scope = backgroundScope,
                    capacity = 4,
                    isCurrentOwner = { it === owner },
                    sender = { _, message ->
                        sent += message
                        true
                    },
                )

            assertTrue(queue.tryEnqueue(owner, "first", results::add))
            assertTrue(queue.tryEnqueue(owner, "second", results::add))
            assertTrue(queue.tryEnqueue(owner, "third", results::add))
            runCurrent()

            assertEquals(listOf("first", "second", "third"), sent)
            assertEquals(listOf(true, true, true), results)
        }

    @Test
    fun queued_message_for_old_owner_is_not_sent_to_replacement() =
        runTest {
            val oldOwner = Any()
            val newOwner = Any()
            var currentOwner: Any? = oldOwner
            val sent = mutableListOf<Pair<Any, String>>()
            val results = mutableListOf<Boolean>()
            val queue =
                WatchOutgoingQueue<Any, String>(
                    scope = backgroundScope,
                    capacity = 4,
                    isCurrentOwner = { it === currentOwner },
                    sender = { owner, message ->
                        sent += owner to message
                        true
                    },
                )

            assertTrue(queue.tryEnqueue(oldOwner, "stale", results::add))
            currentOwner = newOwner
            assertTrue(queue.tryEnqueue(newOwner, "fresh", results::add))
            runCurrent()

            assertEquals(listOf(false, true), results)
            assertEquals(1, sent.size)
            assertSame(newOwner, sent.single().first)
            assertEquals("fresh", sent.single().second)
        }

    @Test
    fun full_queue_rejects_without_growing_and_reports_failure_once() =
        runTest {
            val owner = Any()
            val sent = mutableListOf<String>()
            val rejectedResults = mutableListOf<Boolean>()
            val queue =
                WatchOutgoingQueue<Any, String>(
                    scope = backgroundScope,
                    capacity = 2,
                    isCurrentOwner = { true },
                    sender = { _, message ->
                        sent += message
                        true
                    },
                )

            assertTrue(queue.tryEnqueue(owner, "first"))
            assertTrue(queue.tryEnqueue(owner, "second"))
            assertFalse(queue.tryEnqueue(owner, "overflow", rejectedResults::add))
            assertEquals(listOf(false), rejectedResults)

            runCurrent()
            assertEquals(listOf("first", "second"), sent)
            assertEquals(listOf(false), rejectedResults)
        }

    @Test
    fun retry_gets_a_fresh_ack_timeout_window() =
        runTest {
            val timedOut = mutableListOf<String>()
            val deadlines =
                WatchChatAckTimeouts(
                    scope = backgroundScope,
                    timeoutMs = 8_000L,
                    onTimeout = timedOut::add,
                )

            deadlines.arm("message-1")
            advanceTimeBy(1_000L)
            deadlines.arm("message-1")

            advanceTimeBy(7_000L)
            runCurrent()
            assertEquals(emptyList(), timedOut)

            advanceTimeBy(1_000L)
            runCurrent()
            assertEquals(listOf("message-1"), timedOut)
        }

    @Test
    fun acknowledged_chat_does_not_time_out() =
        runTest {
            val timedOut = mutableListOf<String>()
            val deadlines =
                WatchChatAckTimeouts(
                    scope = backgroundScope,
                    timeoutMs = 8_000L,
                    onTimeout = timedOut::add,
                )

            deadlines.arm("message-1")
            advanceTimeBy(7_999L)
            deadlines.complete("message-1")
            advanceTimeBy(1L)
            runCurrent()

            assertEquals(emptyList(), timedOut)
        }
}
