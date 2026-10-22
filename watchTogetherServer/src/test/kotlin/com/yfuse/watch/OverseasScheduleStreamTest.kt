package com.yfuse.watch

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream
import java.time.LocalDate
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OverseasScheduleStreamTest {
    private val today = LocalDate.of(2026, 10, 7)

    @Test
    fun a_schedule_is_read_episode_by_episode_and_the_stream_is_closed() {
        // Tens of thousands of episodes, generated as they are read: none inside the window.
        val episodes =
            (1..40_000).asSequence().map { index ->
                val separator = if (index == 1) "" else ","
                (
                    "$separator{\"airdate\":\"2001-01-01\",\"number\":1,\"season\":1," +
                        "\"show\":{\"id\":$index,\"name\":\"Show $index\",\"type\":\"Scripted\"}}"
                ).toByteArray()
            }
        val parts =
            sequenceOf("[".toByteArray()) + episodes + sequenceOf("]".toByteArray())
        val tracked =
            ClosingStream(SequenceInputStream(Collections.enumeration(parts.map(::ByteArrayInputStream).toList())))
        val shows = loadOverseasDiscoveryStream(OverseasCalendarConfig(enabled = true), today) { tracked }
        assertTrue(shows.isEmpty())
        assertTrue(tracked.closed)
    }

    @Test
    fun a_malformed_schedule_still_fails_the_round_and_closes_the_stream() {
        val tracked = ClosingStream(ByteArrayInputStream("{}".toByteArray()))
        assertFailsWith<IllegalArgumentException> {
            loadOverseasDiscoveryStream(OverseasCalendarConfig(enabled = true), today) { tracked }
        }
        assertTrue(tracked.closed)
    }

    @Test
    fun a_body_past_its_cap_fails_however_it_is_read() {
        val bounded = BoundedInputStream(ByteArrayInputStream(ByteArray(10)), maxBytes = 8L)
        assertEquals(8, bounded.read(ByteArray(8), 0, 8))
        assertFailsWith<IOException> { bounded.read() }
    }

    private class ClosingStream(
        source: InputStream,
    ) : java.io.FilterInputStream(source) {
        var closed = false

        override fun close() {
            closed = true
            super.close()
        }
    }
}
