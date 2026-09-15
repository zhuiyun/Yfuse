package com.yfuse.feature.search

import com.yfuse.core.data.CrossServerMediaGroup
import com.yfuse.core.data.CrossServerMediaHit
import com.yfuse.core.model.MediaItem
import kotlin.test.Test
import kotlin.test.assertEquals

class AggregatedSearchRankingTest {
    @Test
    fun exact_title_outranks_a_sequel_even_when_the_sequel_has_more_copies() {
        fun group(
            title: String,
            copies: Int,
        ): CrossServerMediaGroup {
            val item = MediaItem(title, title, null, "Movie", title, null, null, null, null)
            val hits = (1..copies).map { CrossServerMediaHit("$it", "$it", item) }
            return CrossServerMediaGroup(title, hits.first(), hits)
        }
        val groups = listOf(group("沙丘2", 5), group("沙丘", 1), group("沙丘预言", 3))
        assertEquals(listOf("沙丘", "沙丘2", "沙丘预言"), rankAggregatedSearch(groups, " 沙丘 ").map { it.identity })
        assertEquals(groups, rankAggregatedSearch(groups, ""))
    }
}
