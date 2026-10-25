package com.yfuse.feature.widgets

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class WidgetPrismProjectionTest {
    @Test
    fun compose_matrix_maps_artwork_corners_to_the_shared_faces_at_every_rotation() {
        for (rtl in listOf(false, true)) {
            for (size in listOf(Size(340f, 309.4f), Size(480f, 436.8f), Size(125f, 113.75f))) {
                val textureCorners =
                    listOf(
                        Offset.Zero,
                        Offset(size.width, 0f),
                        Offset(size.width, size.height),
                        Offset(0f, size.height),
                    )
                for (step in 0..140) {
                    prismGalleryFaces(step / 20f, 8, rtl).forEach { face ->
                        val matrix = prismGalleryMatrix(face, size, rtl)
                        val cornerOrder = if (rtl) listOf(1, 0, 3, 2) else listOf(0, 1, 2, 3)
                        textureCorners.zip(cornerOrder).forEach { (texture, index) ->
                            val expected = face.corners[index]
                            val actual = matrix.map(texture)
                            assertTrue(
                                abs(actual.x - expected.x * size.width) < .0001f,
                                "x projection mismatch at $step, rtl=$rtl",
                            )
                            assertTrue(
                                abs(actual.y - expected.y * size.height) < .0001f,
                                "y projection mismatch at $step, rtl=$rtl",
                            )
                        }
                    }
                }
            }
        }
    }
}
