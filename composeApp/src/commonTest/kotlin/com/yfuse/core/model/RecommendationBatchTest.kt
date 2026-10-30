package com.yfuse.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class RecommendationBatchTest {
    @Test fun repeated_refresh_reaches_all_candidates_without_duplicate_cards() {
        val pool = (1..30).map(Int::toString)
        var history = emptyList<String>()
        val seen = mutableSetOf<String>()
        repeat(4) {
            val batch = recommendationBatch(pool + pool, 8, history) { it }
            assertEquals(8, batch.distinct().size)
            history = recordRecommendationExposure(history, batch)
            seen += batch
        }
        assertEquals(pool.toSet(), seen)
    }

    @Test fun small_pool_advances_the_lead_and_new_candidates_take_priority() {
        val pool = listOf("a", "b", "c")
        val next = recommendationBatch(pool, 8, pool) { it }
        assertEquals(pool.toSet(), next.toSet())
        assertNotEquals(pool.first(), next.first())
        assertEquals("new", recommendationBatch(pool + "new", 2, pool) { it }.first())
        assertEquals(emptyList(), recommendationBatch(pool, 0, pool) { it })
    }

    @Test fun exposure_history_is_bounded_and_moves_repeated_entries_to_the_end() {
        val history = (1..600).map(Int::toString)
        val next = recordRecommendationExposure(history, listOf("1", "2", "2"))
        assertEquals(500, next.size)
        assertEquals(listOf("1", "2"), next.takeLast(2))
        assertEquals(next.size, next.distinct().size)
    }
}
