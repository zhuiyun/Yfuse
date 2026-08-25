package com.yfuse.core.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticLogBoundaryTest {
    @Test
    fun prepared_payload_is_redacted_bounded_immutable_and_detached() {
        val rawAttributes =
            linkedMapOf(
                "token" to "attribute-secret",
                "large" to "a".repeat(DiagnosticMaxAttributeChars + 100),
                "thread" to "spoofed-thread",
            ).apply {
                repeat(DiagnosticMaxAttributes + 10) { index ->
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
                        "m".repeat(DiagnosticMaxMessageChars + 100),
                throwable =
                    IllegalStateException(
                        "token=throwable-secret " +
                            "t".repeat(DiagnosticMaxStackTraceChars + 100),
                    ),
                attributes = rawAttributes,
                threadName = "producer-" + "z".repeat(DiagnosticMaxThreadNameChars + 100),
            )

        rawAttributes["added-later"] = "must-not-appear"
        rawAttributes["large"] = "changed"

        assertEquals("playback_controls", prepared.category)
        assertEquals("request_failed", prepared.event)
        assertTrue(prepared.message.length <= DiagnosticMaxMessageChars)
        assertFalse("message-secret" in prepared.message)
        assertTrue(prepared.attributes.size <= DiagnosticMaxAttributes)
        assertEquals("<redacted>", prepared.attributes["token"])
        assertTrue(prepared.attributes.getValue("large").length <= DiagnosticMaxAttributeChars)
        assertFalse("added-later" in prepared.attributes)
        assertFalse("spoofed-thread" in prepared.attributes.values)
        assertTrue(
            prepared.attributes.getValue("thread").length <= DiagnosticMaxThreadNameChars,
        )
        val preparedException = requireNotNull(prepared.exception)
        val preparedExceptionMessage = requireNotNull(preparedException.message)
        assertTrue(preparedException.type.length <= DiagnosticMaxThrowableTypeChars)
        assertTrue(preparedExceptionMessage.length <= DiagnosticMaxMessageChars)
        assertFalse("throwable-secret" in preparedExceptionMessage)
        assertTrue(preparedException.stackTrace.length <= DiagnosticMaxStackTraceChars)
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
}
