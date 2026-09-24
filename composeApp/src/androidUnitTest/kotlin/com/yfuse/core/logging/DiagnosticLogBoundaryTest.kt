package com.yfuse.core.logging

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DiagnosticLogBoundaryTest {
    @Test
    fun an_entry_prepared_on_the_writer_keeps_the_time_it_was_logged() {
        val loggedAt = Instant.parse("2026-09-24T08:00:00Z")

        val prepared =
            prepareDiagnosticLog(
                level = DiagnosticLevel.Info,
                category = "emby",
                event = "request_failed",
                message = "",
                throwable = null,
                attributes = emptyMap(),
                threadName = "main",
                timestamp = loggedAt,
            )

        assertEquals("2026-09-24T08:00:00Z", prepared.timestamp)
    }

    @Test
    fun server_references_correlate_failures_without_exporting_server_identifiers() {
        fun attributes(server: String) =
            prepareDiagnosticLog(
                DiagnosticLevel.Warning,
                "sync",
                "failed",
                "",
                null,
                mapOf("serverId" to server),
                "worker",
            ).attributes
        val first = attributes("https://private.example/user-secret")
        assertEquals("<redacted>", first["serverid"])
        assertEquals(first["serverref"], attributes("https://private.example/user-secret")["serverref"])
        assertTrue(first["serverref"] != attributes("https://another.example/user-secret")["serverref"])
        assertFalse(first.values.any { "private.example" in it || "user-secret" in it })
    }

    @Test
    fun prepared_payload_is_redacted_bounded_immutable_and_detached() {
        val rawAttributes =
            linkedMapOf(
                "token" to "attribute-secret",
                "large" to "a".repeat(DIAGNOSTIC_MAX_ATTRIBUTE_CHARS + 100),
                "thread" to "spoofed-thread",
            ).apply {
                repeat(DIAGNOSTIC_MAX_ATTRIBUTES + 10) { index ->
                    put("attribute-$index", "value-$index")
                }
            }
        val prepared =
            prepareDiagnosticLog(
                level = DiagnosticLevel.Warning,
                category = "Playback Controls",
                event = "Request Failed",
                message =
                    "Authorization: message-secret\n" +
                        "m".repeat(DIAGNOSTIC_MAX_MESSAGE_CHARS + 100),
                throwable =
                    IllegalStateException(
                        "token=throwable-secret " +
                            "t".repeat(DIAGNOSTIC_MAX_STACK_TRACE_CHARS + 100),
                    ),
                attributes = rawAttributes,
                threadName = "producer-" + "z".repeat(DIAGNOSTIC_MAX_THREAD_NAME_CHARS + 100),
            )

        rawAttributes["added-later"] = "must-not-appear"
        rawAttributes["large"] = "changed"

        assertEquals("playback_controls", prepared.category)
        assertEquals("request_failed", prepared.event)
        assertTrue(prepared.message.length <= DIAGNOSTIC_MAX_MESSAGE_CHARS)
        assertFalse("message-secret" in prepared.message)
        assertTrue(prepared.attributes.size <= DIAGNOSTIC_MAX_ATTRIBUTES)
        assertEquals("<redacted>", prepared.attributes["token"])
        assertTrue(prepared.attributes.getValue("large").length <= DIAGNOSTIC_MAX_ATTRIBUTE_CHARS)
        assertFalse("added-later" in prepared.attributes)
        assertFalse("spoofed-thread" in prepared.attributes.values)
        assertTrue(
            prepared.attributes.getValue("thread").length <= DIAGNOSTIC_MAX_THREAD_NAME_CHARS,
        )
        val preparedException = requireNotNull(prepared.exception)
        val preparedExceptionMessage = requireNotNull(preparedException.message)
        assertTrue(preparedException.type.length <= DIAGNOSTIC_MAX_THROWABLE_TYPE_CHARS)
        assertTrue(preparedExceptionMessage.length <= DIAGNOSTIC_MAX_MESSAGE_CHARS)
        assertFalse("throwable-secret" in preparedExceptionMessage)
        assertTrue(preparedException.stackTrace.length <= DIAGNOSTIC_MAX_STACK_TRACE_CHARS)
        assertFalse("throwable-secret" in preparedException.stackTrace)

        @Suppress("UNCHECKED_CAST")
        val mutableView = prepared.attributes as MutableMap<String, String>
        assertFailsWith<UnsupportedOperationException> {
            mutableView["injected"] = "value"
        }
    }

    @Test
    fun fingerprint_history_never_exceeds_capacity_and_evicts_oldest() {
        val history = BoundedDiagnosticFingerprintHistory()

        repeat(DIAGNOSTIC_MAX_FINGERPRINTS + 1) { index ->
            assertFalse(
                history.record(
                    fingerprint = "fingerprint-$index",
                    nowElapsedMs = index.toLong(),
                    duplicateWindowMs = 5L,
                    suppressDuplicates = true,
                ),
            )
        }

        assertEquals(DIAGNOSTIC_MAX_FINGERPRINTS, history.size)
        assertFalse(history.contains("fingerprint-0"))
        assertTrue(history.contains("fingerprint-1"))
        assertTrue(history.contains("fingerprint-$DIAGNOSTIC_MAX_FINGERPRINTS"))
    }

    @Test
    fun expired_fingerprint_becomes_newest_before_the_next_eviction() {
        val history = BoundedDiagnosticFingerprintHistory(maxEntries = 3)
        history.record("a", 0L, 5L, suppressDuplicates = true)
        history.record("b", 1L, 5L, suppressDuplicates = true)
        history.record("c", 2L, 5L, suppressDuplicates = true)

        assertTrue(history.record("a", 3L, 5L, suppressDuplicates = true))
        assertFalse(history.record("a", 5L, 5L, suppressDuplicates = true))
        history.record("d", 6L, 5L, suppressDuplicates = true)

        assertEquals(3, history.size)
        assertTrue(history.contains("a"))
        assertFalse(history.contains("b"))
        assertTrue(history.contains("c"))
        assertTrue(history.contains("d"))
    }

    private fun preparationSkipped(
        reason: String,
        extra: Map<String, String> = emptyMap(),
        threadName: String = "worker",
    ) = prepareDiagnosticLog(
        level = DiagnosticLevel.Info,
        category = "player.core2",
        event = "current_item_preparation_skipped",
        message = "Optional source preparation stopped",
        throwable = null,
        attributes = mapOf("reason" to reason) + extra,
        threadName = threadName,
    )

    @Test
    fun duplicate_fingerprint_differs_when_the_reason_differs() {
        assertNotEquals(
            diagnosticDuplicateFingerprint(preparationSkipped("selection_changed_or_handoff")),
            diagnosticDuplicateFingerprint(preparationSkipped("budget_or_resource_pressure")),
        )
    }

    @Test
    fun duplicate_fingerprint_ignores_volatile_measurements_and_thread() {
        val first =
            preparationSkipped(
                "budget_or_resource_pressure",
                extra = mapOf("elapsedMs" to "120", "queuedCount" to "3"),
                threadName = "worker-1",
            )
        val second =
            preparationSkipped(
                "budget_or_resource_pressure",
                extra = mapOf("elapsedMs" to "9001", "queuedCount" to "7"),
                threadName = "worker-2",
            )

        assertEquals(diagnosticDuplicateFingerprint(first), diagnosticDuplicateFingerprint(second))
    }

    @Test
    fun different_reasons_within_the_duplicate_window_are_both_kept_but_repeats_are_suppressed() {
        val history = BoundedDiagnosticFingerprintHistory()

        val firstIsDuplicate =
            history.record(
                fingerprint = diagnosticDuplicateFingerprint(preparationSkipped("selection_changed_or_handoff")),
                nowElapsedMs = 0L,
                duplicateWindowMs = 5_000L,
                suppressDuplicates = true,
            )
        val secondReasonIsDuplicate =
            history.record(
                fingerprint = diagnosticDuplicateFingerprint(preparationSkipped("budget_or_resource_pressure")),
                nowElapsedMs = 1_000L,
                duplicateWindowMs = 5_000L,
                suppressDuplicates = true,
            )
        val sameReasonRepeatedIsDuplicate =
            history.record(
                fingerprint = diagnosticDuplicateFingerprint(preparationSkipped("budget_or_resource_pressure")),
                nowElapsedMs = 1_500L,
                duplicateWindowMs = 5_000L,
                suppressDuplicates = true,
            )

        assertFalse(firstIsDuplicate, "the first occurrence of a reason is never a duplicate")
        assertFalse(secondReasonIsDuplicate, "a different reason within the window is a distinct event")
        assertTrue(sameReasonRepeatedIsDuplicate, "the same reason again inside the window is still suppressed")
    }
}
