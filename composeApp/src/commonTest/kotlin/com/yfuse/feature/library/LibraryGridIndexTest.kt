package com.yfuse.feature.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LibraryGridIndexTest {
    @Test
    fun latinTitlesFileUnderTheirFirstLetter() {
        assertEquals("M", asciiIndexLetter("matrix"))
        assertEquals("B", asciiIndexLetter("  \"Blade Runner\""))
        assertEquals("#", asciiIndexLetter("2001: A Space Odyssey"))
        assertEquals("#", asciiIndexLetter("…"))
        assertEquals("#", asciiIndexLetter(""))
    }

    @Test
    fun yearsMonthsAndScoresLabelTheirSorts() {
        assertEquals(GridIndexLabel("2024", "2024年"), yearIndexLabel(2024))
        assertNull(yearIndexLabel(null))
        assertEquals(GridIndexLabel("26.9", "2026年9月"), monthIndexLabel("2026-09-26"))
        assertEquals(GridIndexLabel("05.12", "2005年12月"), monthIndexLabel("2005-12"))
        assertNull(monthIndexLabel("20260926"))
        assertNull(monthIndexLabel("2026-13-01"))
        assertNull(monthIndexLabel(null))
        assertEquals(GridIndexLabel("8", "8分"), ratingIndexLabel(8.6))
        assertEquals(GridIndexLabel("10", "10分"), ratingIndexLabel(10.0))
        assertNull(ratingIndexLabel(0.0))
    }

    @Test
    fun sectionsStartAtTheFirstPosterOfEachLabelInGridOrder() {
        val a = GridIndexLabel("A", "A")
        val b = GridIndexLabel("B", "B")
        val t = GridIndexLabel("T", "T")
        val sections = gridIndexSections(listOf(a, a, null, b, t, b, b))
        assertEquals(listOf(0, 3, 4), sections.map { it.firstIndex })
        assertEquals(listOf("A", "B", "T"), sections.map { it.label.short })
        assertEquals(emptyList(), gridIndexSections(listOf(null, null)))
    }

    @Test
    fun aFingerOnTheStripPointsAtOneStop() {
        assertEquals(0, indexSectionAt(0f, 4))
        assertEquals(1, indexSectionAt(0.3f, 4))
        assertEquals(3, indexSectionAt(1f, 4))
        assertEquals(0, indexSectionAt(-2f, 4))
        assertEquals(-1, indexSectionAt(0.5f, 0))
    }

    @Test
    fun aLongIndexSpellsOutEvenlySpacedStops() {
        assertEquals(listOf(0, 1, 2), indexStripStops(count = 3, slots = 10))
        assertEquals(listOf(0, 5, 10), indexStripStops(count = 11, slots = 3))
        assertEquals(listOf(0), indexStripStops(count = 11, slots = 1))
        assertEquals(emptyList(), indexStripStops(count = 0, slots = 5))
    }
}
