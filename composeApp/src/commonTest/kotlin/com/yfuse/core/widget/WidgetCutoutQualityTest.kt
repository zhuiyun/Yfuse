package com.yfuse.core.widget

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WidgetCutoutQualityTest {
    private val width = 100
    private val height = 150

    @Test
    fun disconnectedLetteringCannotMoveTheMainSubjectToTheTop() {
        val pixels = IntArray(width * height)
        fill(pixels, 20, 50, 80, 145)
        fill(pixels, 35, 4, 65, 14)
        val subject = assertNotNull(dominantWidgetCutoutSubject(width, height, pixels))
        assertEquals(50f / height, subject.top)
        assertEquals(145f / height, subject.bottom)
        assertEquals(.2f, subject.left)
        assertEquals(.8f, subject.right)
    }

    @Test
    fun letteringOnlyMasksDoNotQualifyAsASubstantialSilhouette() {
        val pixels = IntArray(width * height)
        for (y in 5..65 step 20) fill(pixels, 35, y, 65, y + 10)
        assertNull(dominantWidgetCutoutSubject(width, height, pixels))
    }

    @Test
    fun transparentAndBroadFlatMatteMasksFallBackToAnOrdinaryPoster() {
        val pixels = IntArray(width * height)
        assertNull(dominantWidgetCutoutSubject(width, height, pixels))
        fill(pixels, 0, 0, width, 70)
        fill(pixels, 25, 70, 75, 140)
        assertNull(dominantWidgetCutoutSubject(width, height, pixels))
    }

    @Test
    fun aRoundedHeadAndTorsoRemainUsableWithOtherSmallIslands() {
        val pixels = IntArray(width * height)
        fill(pixels, 36, 15, 64, 40)
        fill(pixels, 25, 35, 75, 140)
        fill(pixels, 4, 1, 8, 4)
        assertNotNull(dominantWidgetCutoutSubject(width, height, pixels))
    }

    private fun fill(
        pixels: IntArray,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ) {
        for (y in top until bottom) {
            for (x in left until right) pixels[y * width + x] = -1
        }
    }
}
