package com.yfuse.core.widget

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WidgetRecommendationPolicyTest {
    private val section = WidgetSection("row", "source", "recTv", "推荐", style = "breakout-poster")

    @Test fun only_recommendation_presentations_offer_batches() {
        assertEquals(12, section.recommendationLimit())
        assertEquals(8, section.copy(style = "hero", limit = 8).recommendationLimit())
        for (style in WidgetStyle.entries.filter { it.group in setOf("排行榜", "续播追剧", "分类合集") }) {
            assertNull(section.copy(style = style.id).recommendationLimit(), style.id)
        }
    }

    @Test fun ranked_sources_remain_ordered_even_with_a_recommendation_presentation() {
        assertNull(section.copy(moduleId = "topRated").recommendationLimit())
        for (chart in listOf("imdb/top250-tv", "justwatch/streaming-charts", "bangumi/calendar")) {
            assertNull(section.copy(params = JsonObject(mapOf("chart" to JsonPrimitive(chart)))).recommendationLimit())
        }
        assertEquals(
            12,
            section
                .copy(
                    params = JsonObject(mapOf("chart" to JsonPrimitive("feed/series-trending"))),
                ).recommendationLimit(),
        )
    }
}
