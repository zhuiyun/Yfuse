package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NativeCrashContextOwnershipTest {
    @Test
    fun late_successful_teardown_cannot_clear_replacement_context_or_failure_count() {
        val ownership = NativeCrashContextOwnership()
        var context: String? = "new"
        var failures = 2
        ownership.arm("old")
        ownership.arm("new")
        ownership.disarm("old") {
            context = null
            failures = 0
        }
        assertEquals("new", context)
        assertEquals(2, failures)
        ownership.disarm("new") { context = null }
        assertEquals(null, context)
        assertEquals(2, failures)
    }

    @Test
    fun duplicate_teardown_is_ignored_but_failed_cleanup_keeps_ownership() {
        val ownership = NativeCrashContextOwnership()
        ownership.arm("current")
        var cleaned = 0
        assertFailsWith<IllegalStateException> {
            ownership.disarm("current") { error("store unavailable") }
        }
        ownership.disarm("current") { cleaned++ }
        ownership.disarm("current") { cleaned++ }
        assertEquals(1, cleaned)
    }
}
