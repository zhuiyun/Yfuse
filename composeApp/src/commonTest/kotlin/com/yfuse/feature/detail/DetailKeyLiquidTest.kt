package com.yfuse.feature.detail

import com.yfuse.core.designsystem.LiquidTestSupport
import com.yfuse.core.designsystem.liquidOutline
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DetailKeyLiquidTest {
    private val metrics = DetailKeyMetrics(width = 376f)

    private fun pieces(frame: DetailKeyFrame) = liquidOutline(frame.bodies(), 0f, metrics.width, 40f)

    @Test
    fun the_key_arrives_whole_before_from_start_grows_out_of_it() {
        val key = pieces(detailSplitFrame(0f, metrics)).single()
        assertEquals(0f, key.start, 0.1f)
        assertEquals(metrics.width, key.end, 0.1f)
        assertEquals(0f, detailSplitFrame(0f, metrics).label)
    }

    @Test
    fun the_pinch_time_is_where_the_engine_breaks_the_neck() {
        val pinch =
            LiquidTestSupport.firstTimeWith(2) { ms ->
                // Before the drop squares up, so only the neck decides.
                val frame = detailSplitFrame(ms, metrics)
                pieces(frame).size
            }
        assertNotNull(pinch)
        assertTrue(abs(pinch - DETAIL_SPLIT_PINCH_MS) <= 4, "the neck breaks at $pinch ms")
    }

    @Test
    fun once_free_the_drop_squares_up_without_reaching_back() {
        val counts =
            (DETAIL_SPLIT_PINCH_MS + 4..DETAIL_SPLIT_MS step 4).map { ms ->
                pieces(detailSplitFrame(ms.toFloat(), metrics)).size
            }
        assertTrue(counts.all { it == 2 }, "$counts")
    }

    @Test
    fun the_split_lands_on_the_two_resting_keys() {
        val last = detailSplitFrame(DETAIL_SPLIT_MS.toFloat(), metrics)
        val (play, fromStart) = pieces(last)
        assertEquals(metrics.splitEdge, play.end, 0.3f)
        assertEquals(metrics.width - DETAIL_FROM_START_WIDTH, fromStart.start, 0.3f)
        assertEquals(metrics.width, fromStart.end, 0.3f)
        assertEquals(DETAIL_KEY_HALF_HEIGHT, fromStart.peak, 0.1f)
        assertEquals(1f, last.label)
        assertEquals(1f, last.morph)
    }

    @Test
    fun the_merge_starts_from_the_two_keys_and_ends_as_one() {
        val (play, fromStart) = pieces(detailMergeFrame(0f, metrics))
        assertEquals(metrics.splitEdge, play.end, 0.3f)
        assertEquals(metrics.width - DETAIL_FROM_START_WIDTH, fromStart.start, 0.3f)
        val last = detailMergeFrame(DETAIL_MERGE_MS.toFloat(), metrics)
        val key = pieces(last).single()
        assertEquals(metrics.width, key.end, 0.1f)
        assertEquals(0f, last.resume)
        assertEquals(0f, last.label)
    }

    @Test
    fun the_resume_time_waits_for_the_drop_to_start_flowing_back() {
        assertEquals(1f, detailMergeFrame(MERGE_LABEL_DELAY_MS.toFloat(), metrics).resume)
        assertTrue(detailMergeFrame(300f, metrics).resume < 1f)
    }
}
