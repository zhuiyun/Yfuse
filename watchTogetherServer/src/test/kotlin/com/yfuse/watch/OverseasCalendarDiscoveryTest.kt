package com.yfuse.watch

import java.io.IOException
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OverseasCalendarDiscoveryTest {
    private val today = LocalDate.of(2026, 9, 15)

    @Test
    fun unavailableAndMalformedSnapshotsCannotBecomeSuccessfulEmptyDiscoveries() {
        val config = OverseasCalendarConfig(enabled = true)
        assertFailsWith<IOException> { loadOverseasDiscovery(config, today) { null } }
        assertFailsWith<IllegalArgumentException> { loadOverseasDiscovery(config, today) { "invalid json" } }
        assertFailsWith<IllegalArgumentException> { loadOverseasDiscovery(config, today) { "{}" } }
        assertFailsWith<IllegalArgumentException> { loadOverseasDiscovery(config, today) { "[null]" } }
        assertFailsWith<IllegalArgumentException> { loadOverseasDiscovery(config, today) { "[{}]" } }
        assertEquals(emptyList(), loadOverseasDiscovery(config, today) { "[]" })
    }

    @Test
    fun malformed_show_identity_rejects_the_entire_snapshot_even_beside_a_valid_show() {
        val valid = episode()
        listOf(
            "null",
            "{}",
            """{"id":0,"name":"Show","type":"Scripted"}""",
            """{"id":"1","name":"Show","type":"Scripted"}""",
            """{"id":1,"name":" ","type":"Scripted"}""",
            """{"id":1,"name":42,"type":"Scripted"}""",
            """{"id":1,"name":"Show","type":null}""",
        ).forEach { invalidShow ->
            val body = "[$valid,${episode(show = invalidShow)}]"
            assertFailsWith<IllegalArgumentException> {
                loadOverseasDiscovery(OverseasCalendarConfig(enabled = true), today) { body }
            }
        }
    }

    @Test
    fun valid_news_specials_and_unknown_dates_are_successfully_filtered() {
        val config = OverseasCalendarConfig(enabled = true)
        val news = episode(show = """{"id":2,"name":"News show","type":"News"}""")
        val special = episode(number = "null", season = "0")
        val unknownDate = episode(airDate = "null")
        val blankDate = episode(airDate = "\"\"")

        assertEquals(
            emptyList(),
            loadOverseasDiscovery(config, today) { "[$news,$special,$unknownDate,$blankDate]" },
        )
    }

    @Test
    fun direct_show_and_embedded_show_formats_are_both_supported() {
        val config = OverseasCalendarConfig(enabled = true, countryCodes = emptyList())
        val embedded = episode()
        val direct =
            """
            {"airdate":"2026-09-15","number":1,"season":1,
             "show":{"id":1,"name":"Show","type":"Scripted"}}
            """.trimIndent()

        val discovered = loadOverseasDiscovery(config, today) { "[$embedded]" }
        assertTrue(discovered.isNotEmpty())
        assertEquals(discovered, loadOverseasDiscovery(config, today) { "[$direct]" })
    }

    @Test
    fun disabledDiscoveryDoesNotContactProvider() {
        assertEquals(
            emptyList(),
            loadOverseasDiscovery(OverseasCalendarConfig(enabled = false), today) { error("Unexpected fetch") },
        )
    }

    private fun episode(
        show: String = """{"id":1,"name":"Show","type":"Scripted"}""",
        airDate: String = "\"2026-09-15\"",
        number: String = "1",
        season: String = "1",
    ): String = """{"airdate":$airDate,"number":$number,"season":$season,"_embedded":{"show":$show}}"""
}
