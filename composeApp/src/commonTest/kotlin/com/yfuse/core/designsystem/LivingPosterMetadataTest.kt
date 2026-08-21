package com.yfuse.core.designsystem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LivingPosterMetadataTest {
    @Test
    fun duration_uses_compact_chinese_units() {
        assertEquals("48分钟", heroDurationLabel(48))
        assertEquals("2小时", heroDurationLabel(120))
        assertEquals("2小时46分钟", heroDurationLabel(166))
        assertNull(heroDurationLabel(null))
        assertNull(heroDurationLabel(0))
    }

    @Test
    fun media_types_are_localized_for_both_sources() {
        assertEquals("电影", heroMediaTypeLabel("movie"))
        assertEquals("剧集", heroMediaTypeLabel("tv"))
        assertEquals("剧集", heroMediaTypeLabel("Series"))
        assertEquals("单集", heroMediaTypeLabel("Episode"))
    }
}
