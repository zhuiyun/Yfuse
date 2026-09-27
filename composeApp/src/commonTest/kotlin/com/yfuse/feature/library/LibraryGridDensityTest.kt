package com.yfuse.feature.library

import com.russhwolf.settings.MapSettings
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryGridDensityTest {
    private fun assertNear(
        expected: Float,
        actual: Float,
    ) = assertTrue(abs(expected - actual) < 0.001f, "expected $expected but was $actual")

    @Test
    fun phonesPinchBetweenTwoAndFiveWhileWideWindowsKeepTheirAdaptiveDensity() {
        assertEquals(2..5, gridColumnRange(adaptiveColumns = 3))
        assertEquals(2..8, gridColumnRange(adaptiveColumns = 8))
    }

    @Test
    fun adaptiveColumnsMatchTheGridsOwnCount() {
        // 96 dp tiles 10 dp apart on a 324 dp wide phone grid: three across, as the grid has always had.
        assertEquals(3, adaptiveGridColumns(available = 324f, minTile = 96f, spacing = 10f))
        assertEquals(1, adaptiveGridColumns(available = 50f, minTile = 96f, spacing = 10f))
        assertEquals(7, adaptiveGridColumns(available = 760f, minTile = 96f, spacing = 10f))
    }

    @Test
    fun titlesMakeWayAtFiveAcross() {
        assertTrue(gridShowsTitles(4))
        assertFalse(gridShowsTitles(5))
    }

    @Test
    fun spreadingTheFingersMeansFewerBiggerPosters() {
        assertNear(2f, pinchedColumns(start = 3f, zoom = 1.5f, range = 2..5))
        assertNear(4f, pinchedColumns(start = 3f, zoom = 0.75f, range = 2..5))
        assertNear(3f, pinchedColumns(start = 3f, zoom = 1f, range = 2..5))
    }

    @Test
    fun pastEitherEndThePinchResistsWithoutStopping() {
        val beyond = pinchedColumns(start = 3f, zoom = 3f, range = 2..5)
        assertTrue(beyond < 2f && beyond > 1f, "resists below two: $beyond")
        val further = pinchedColumns(start = 3f, zoom = 6f, range = 2..5)
        assertTrue(further < beyond, "but still follows: $further")
        val dense = pinchedColumns(start = 5f, zoom = 0.5f, range = 2..5)
        assertTrue(dense > 5f && dense < 6f, "resists above five: $dense")
        assertNear(3f, pinchedColumns(start = 3f, zoom = 0f, range = 2..5))
    }

    @Test
    fun aPinchSettlesOnTheNearestWholeColumnCount() {
        assertEquals(3, settledColumns(3.4f, 2..5))
        assertEquals(4, settledColumns(3.6f, 2..5))
        assertEquals(2, settledColumns(1.4f, 2..5))
        assertEquals(5, settledColumns(5.7f, 2..5))
        assertEquals(2, settledColumns(Float.NaN, 2..5))
    }

    @Test
    fun theShownGridIsScaledToThePinchedSize() {
        // Three across shown while the fingers ask for 3.5: each poster is 3/3.5 of its laid-out size.
        assertNear(3f / 3.5f, pinchScale(shown = 3, columns = 3.5f))
        assertNear(1f, pinchScale(shown = 4, columns = 4f))
    }

    @Test
    fun tilesShareTheWidthLessTheGaps() {
        assertNear(100f, gridTileWidth(available = 320f, columns = 3, spacing = 10f))
        assertNear(0f, gridTileWidth(available = 320f, columns = 0, spacing = 10f))
    }

    @Test
    fun theAnchorPosterStaysUnderTheFingers() {
        // The fingers are 600px down, a third of the way into a poster that will be 300px tall.
        assertNear(500f, anchoredRowTop(focusY = 600f, fraction = 1f / 3f, height = 300f))
    }

    @Test
    fun eachLibraryRemembersItsOwnColumns() {
        val preferences = LibraryGridColumnsPreferences(MapSettings())
        assertNull(preferences.columns("movies"))
        preferences.setColumns("movies", 4)
        preferences.setColumns("shows", 2)
        assertEquals(4, preferences.columns("movies"))
        assertEquals(2, preferences.columns("shows"))
        preferences.setColumns("movies", 40)
        assertEquals(12, preferences.columns("movies"))
    }
}
