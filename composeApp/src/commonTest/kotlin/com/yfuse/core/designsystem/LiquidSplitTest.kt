package com.yfuse.core.designsystem

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiquidSplitTest {
    private val radius = 26f
    private val viscosity = LiquidMotion.VISCOSITY

    /** Two equal drops [gap] apart, the second joined to the first with the full viscosity. */
    private fun pair(gap: Float): List<LiquidBody> =
        listOf(
            LiquidBody.drop(center = 0f, radius = radius),
            LiquidBody.drop(center = 2f * radius + gap, radius = radius, blend = viscosity),
        )

    private fun neck(gap: Float): Float {
        val middle = radius + gap / 2f
        val segments = liquidOutline(pair(gap), -radius - 1f, 3f * radius + gap + 1f, radius * 1.5f)
        val joined = segments.singleOrNull() ?: return 0f
        // The half-height sampled closest to the midpoint of the gap.
        return joined.u.indices
            .minBy { abs(joined.u[it] - middle) }
            .let { joined.halfHeight[it] }
    }

    @Test
    fun the_neck_narrows_as_the_gap_grows_and_is_gone_at_half_the_viscosity() {
        val gaps = (0..40).map { it * viscosity / 2f / 40f }
        val necks = gaps.map(::neck)
        necks.zipWithNext().forEach { (wider, narrower) -> assertTrue(narrower <= wider + 0.05f, "$necks") }
        assertTrue(necks.first() > radius * 0.3f, "touching drops share a thick neck: ${necks.first()}")
        assertEquals(0f, neck(viscosity / 2f + 0.5f))
    }

    @Test
    fun the_break_leaves_exactly_two_pieces() {
        val justJoined = liquidOutline(pair(viscosity / 2f - 1f), -radius - 1f, 4f * radius + viscosity, radius * 1.5f)
        val justBroken = liquidOutline(pair(viscosity / 2f + 1f), -radius - 1f, 4f * radius + viscosity, radius * 1.5f)
        assertEquals(1, justJoined.size)
        assertEquals(2, justBroken.size)
    }

    @Test
    fun equal_drops_are_mirror_images_of_each_other() {
        val gap = viscosity / 3f
        val middle = radius + gap / 2f
        val joined = liquidOutline(pair(gap), -radius - 1f, 3f * radius + gap + 1f, radius * 1.5f).single()
        assertEquals(middle, (joined.start + joined.end) / 2f, 0.05f)
        listOf(4f, 11f, 23f, 31f).forEach { offset ->
            assertEquals(
                LiquidTestSupport.heightAt(joined, middle - offset),
                LiquidTestSupport.heightAt(joined, middle + offset),
                0.15f,
            )
        }
    }

    @Test
    fun without_viscosity_the_union_is_plain() {
        assertEquals(-3f, liquidSmoothMin(-3f, 5f, 0f))
        // Fields at least k apart do not blend; closer than that, they bridge below the plain min.
        assertEquals(2f, liquidSmoothMin(2f, 42f, 40f))
        assertTrue(liquidSmoothMin(2f, 7f, 40f) < 2f)
        assertTrue(liquidSmoothMin(1f, 1f, 40f) < 1f)
    }

    @Test
    fun a_capsule_and_a_rounded_key_are_outlined_where_they_are() {
        val capsule = liquidOutline(listOf(LiquidBody.capsule(31f, 277f, 31f)), 0f, 400f, 50f).single()
        assertEquals(0f, capsule.start, 0.05f)
        assertEquals(308f, capsule.end, 0.05f)
        assertEquals(31f, capsule.peak, 0.05f)
        val key = liquidOutline(listOf(LiquidBody.box(100f, 100f, 26f, 16f)), 0f, 250f, 40f).single()
        assertEquals(200f, key.end, 0.05f)
        assertEquals(26f, key.peak, 0.05f)
    }

    @Test
    fun a_drop_below_the_threshold_leaves_its_parent_alone() {
        val parent = listOf(LiquidBody.drop(0f, radius))
        val speck = parent + LiquidBody.drop(radius + 1f, LiquidBody.MIN_RADIUS / 2f, blend = viscosity)
        assertEquals(liquidField(parent, radius + 1f, 0f), liquidField(speck, radius + 1f, 0f))
    }

    @Test
    fun the_spring_starts_at_rest_overshoots_when_underdamped_and_settles() {
        assertEquals(0f, LiquidMotion.spring(0f, 0.62f, 2.6f))
        assertEquals(0f, LiquidMotion.spring(-10f, 0.62f, 2.6f))
        val samples = (1..120).map { LiquidMotion.spring(it * 10f, 0.62f, 2.6f) }
        assertTrue(samples.max() > 1.01f, "an underdamped spring overshoots")
        assertEquals(1f, samples.last(), 0.001f)
        val critical = (1..120).map { LiquidMotion.spring(it * 10f, 1f, 2.6f) }
        critical.zipWithNext().forEach { (a, b) -> assertTrue(b >= a) }
    }

    @Test
    fun the_viscosity_starts_thin_peaks_and_lets_go() {
        assertEquals(0f, LiquidMotion.splitViscosity(0f))
        assertEquals(0.3f, LiquidMotion.splitViscosity(80f), 0.01f)
        assertEquals(1f, LiquidMotion.splitViscosity(250f), 0.01f)
        assertEquals(0f, LiquidMotion.splitViscosity(520f))
        assertEquals(40f, LiquidMotion.viscosityFor(62f), 0.001f)
    }

    @Test
    fun pieces_are_boxed_the_same_way_from_either_side() {
        val segment = liquidOutline(listOf(LiquidBody.drop(50f, 20f)), 0f, 100f, 30f).single()
        val left = segment.bounds(scale = 2f, axisY = 60f)
        val right = segment.bounds(scale = -2f, axisY = 60f, originX = 200f)
        assertEquals(left.width, right.width, 0.001f)
        assertEquals(left.height, right.height, 0.001f)
        assertEquals(200f - left.right, right.left, 0.001f)
        assertTrue(left.left < left.right && right.left < right.right)
    }
}

internal object LiquidTestSupport {
    /** The half-height of [segment] at [u], interpolated between its samples. */
    fun heightAt(
        segment: LiquidSegment,
        u: Float,
    ): Float {
        val index = segment.u.indexOfFirst { it >= u }
        if (index <= 0) return segment.halfHeight.first()
        val a = segment.u[index - 1]
        val b = segment.u[index]
        val t = if (b > a) (u - a) / (b - a) else 0f
        return segment.halfHeight[index - 1] + (segment.halfHeight[index] - segment.halfHeight[index - 1]) * t
    }

    /** The first time, in 2 ms steps from [from], at which [pieces] reports at least [count] pieces. */
    fun firstTimeWith(
        count: Int,
        from: Int = 0,
        until: Int = 1000,
        pieces: (Float) -> Int,
    ): Int? = (from until until step 2).firstOrNull { pieces(it.toFloat()) >= count }
}
