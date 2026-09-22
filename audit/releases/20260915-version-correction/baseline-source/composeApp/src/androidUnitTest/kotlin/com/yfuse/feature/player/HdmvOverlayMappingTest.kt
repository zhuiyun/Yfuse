package com.yfuse.feature.player

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HdmvOverlayMappingTest {
    @Test
    fun fit_mapping_preserves_authored_coordinates_inside_letterbox() {
        val viewport = IntSize(width = 1920, height = 1200)

        assertEquals(
            960 to 540,
            mapHdmvOverlayPoint(
                position = Offset(960f, 600f),
                viewport = viewport,
                overlayWidth = 1920,
                overlayHeight = 1080,
            ),
        )
        // 60 px top letterbox is outside the authored 16:9 plane.
        assertNull(
            mapHdmvOverlayPoint(
                position = Offset(960f, 20f),
                viewport = viewport,
                overlayWidth = 1920,
                overlayHeight = 1080,
            ),
        )
    }

    @Test
    fun invalid_geometry_never_reaches_native_menu_input() {
        assertNull(mapHdmvOverlayPoint(Offset.Zero, IntSize.Zero, 1920, 1080))
        assertNull(mapHdmvOverlayPoint(Offset.Zero, IntSize(100, 100), 0, 1080))
    }
}
