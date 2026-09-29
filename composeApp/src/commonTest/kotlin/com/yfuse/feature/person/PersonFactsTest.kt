package com.yfuse.feature.person

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PersonFactsTest {
    @Test
    fun a_living_person_reads_birthday_and_age() {
        assertEquals("1962年6月27日出生 · 64 岁", personLifeLine("1962-06-27", null, today = "2026-09-29"))
        assertEquals("1962年6月27日出生 · 63 岁", personLifeLine("1962-06-27", null, today = "2026-06-26"))
    }

    @Test
    fun a_person_who_has_died_reads_both_dates_and_the_age_they_reached() {
        assertEquals(
            "1956年9月12日 – 2003年4月1日 · 享年 46 岁",
            personLifeLine("1956-09-12", "2003-04-01", today = "2026-09-29"),
        )
        assertEquals("2003年4月1日逝世", personLifeLine(null, "2003-04-01", today = "2026-09-29"))
    }

    @Test
    fun dates_that_cannot_be_an_age_show_the_date_alone() {
        assertEquals("1990年1月1日 – 1980年1月1日", personLifeLine("1990-01-01", "1980-01-01", today = "2026-09-29"))
        assertEquals("2030年1月1日出生", personLifeLine("2030-01-01", null, today = "2026-09-29"))
        assertEquals("1962年6月27日出生", personLifeLine("1962-06-27T00:00:00Z", null, today = "not today"))
        assertNull(personLifeLine(null, null, today = "2026-09-29"))
        assertNull(personLifeLine("unknown", "", today = "2026-09-29"))
    }

    @Test
    fun a_birthplace_reads_as_a_sentence_or_not_at_all() {
        assertEquals("出生于 香港", personBirthPlaceLine(" 香港 "))
        assertNull(personBirthPlaceLine(" "))
        assertNull(personBirthPlaceLine(null))
    }
}
